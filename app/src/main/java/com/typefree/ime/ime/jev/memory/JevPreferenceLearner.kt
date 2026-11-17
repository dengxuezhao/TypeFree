package com.typefree.ime.ime.jev.memory

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure Kotlin preference learning algorithms:
 * 1. Position-debiased Probability Simplex EMA for orthogonal Jev strategy axes.
 * 2. Cold-start gate `gamma(n)` + 60-day silent regression (120-day half-life) toward uniform prior.
 * 3. Layer 5 bounded utility fusion (`beta_max = 0.35`, preserving LLM veto in adversarial turns).
 * 4. Bidirectional hysteresis EMA (`alpha_on = max(0.20, 1/n)`, `alpha_off = 0.30`, cap `0.90`)
 *    for edit-derived style rules so rules both activate fast and cleanly unwind on manual overrides.
 */
object JevPreferenceLearner {

    const val LAMBDA_TOP_SLOT = 0.12
    const val LAMBDA_NON_TOP_SLOT = 0.20
    const val MIN_SAMPLES_COLD_START = 5
    const val BETA_MAX = 0.35
    const val STALE_START_DAYS = 60.0
    const val STALE_HALF_LIFE_DAYS = 120.0
    private const val DAY_MS = 86_400_000.0
    private const val ACTIVE_EMA_CAP = 0.90

    private data class ThresholdSpec(
        val minOpp: Int,
        val onThreshold: Double,
        val offThreshold: Double
    )

    private val FEATURE_THRESHOLDS = mapOf(
        StyleFeatureKey.DROP_TRAILING_PERIOD to ThresholdSpec(5, 0.75, 0.55),
        StyleFeatureKey.DAMPEN_EXCLAMATION_TILDE to ThresholdSpec(5, 0.72, 0.50),
        StyleFeatureKey.STRIP_EMOJI to ThresholdSpec(4, 0.75, 0.50),
        StyleFeatureKey.HONORIFIC_NIN_TO_NI to ThresholdSpec(3, 0.70, 0.45),
        StyleFeatureKey.HONORIFIC_NI_TO_NIN to ThresholdSpec(3, 0.70, 0.45),
        StyleFeatureKey.TRIM_MODAL_PARTICLE to ThresholdSpec(5, 0.72, 0.50),
        StyleFeatureKey.TRIM_POLITE_PREFIX to ThresholdSpec(5, 0.72, 0.50)
    )

    fun coldStartGate(sampleCount: Int): Double {
        if (sampleCount < MIN_SAMPLES_COLD_START) return 0.0
        return ((sampleCount - 4) / 6.0).coerceIn(0.0, 1.0)
    }

    fun applyTemporalDecayToAxis(
        stats: AxisPreferenceStats?,
        nowMs: Long
    ): AxisPreferenceStats? {
        if (stats == null) return null
        val clean = stats.sanitized()
        if (clean.updatedAtMs <= 0L || nowMs <= clean.updatedAtMs) return clean
        val idleDays = (nowMs - clean.updatedAtMs).toDouble() / DAY_MS
        if (idleDays <= STALE_START_DAYS) return clean
        val excessDays = idleDays - STALE_START_DAYS
        val retention = 0.5.pow(excessDays / STALE_HALF_LIFE_DAYS)
        val uniform = AxisPreferenceStats.DEFAULT_UNIFORM_PROBS
        val decayedProbs = clean.probs.mapIndexed { idx, p ->
            retention * p + (1.0 - retention) * uniform[idx]
        }
        return clean.copy(probs = decayedProbs).sanitized()
    }

    fun updateAxisStats(
        current: AxisPreferenceStats?,
        adoptedIndex: Int,
        wasTopPinnedSlot: Boolean,
        nowMs: Long
    ): AxisPreferenceStats {
        val base = (current ?: AxisPreferenceStats()).sanitized()
        if (adoptedIndex !in 0..2) return base
        val lambda = if (wasTopPinnedSlot) LAMBDA_TOP_SLOT else LAMBDA_NON_TOP_SLOT
        val updated = DoubleArray(3) { idx ->
            val target = if (idx == adoptedIndex) 1.0 else 0.0
            (1.0 - lambda) * base.probs[idx] + lambda * target
        }
        val sum = updated.sum().takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val normalized = updated.map { (it / sum).coerceIn(0.0, 1.0) }
        return AxisPreferenceStats(
            probs = normalized,
            sampleCount = base.sampleCount + 1,
            updatedAtMs = nowMs
        ).sanitized()
    }

    fun fuseUtilityWeights(
        rawModelWeights: List<Int>,
        sessionStats: AxisPreferenceStats?,
        bucketStats: AxisPreferenceStats?,
        nowMs: Long
    ): List<Int> {
        if (rawModelWeights.size != 3) return rawModelWeights
        val safeRaw = rawModelWeights.map { it.coerceAtLeast(0).toDouble() }
        val rawSum = safeRaw.sum()
        val wNorm = if (rawSum > 0.0) {
            safeRaw.map { it / rawSum }
        } else {
            AxisPreferenceStats.DEFAULT_UNIFORM_PROBS
        }

        val decayedSess = applyTemporalDecayToAxis(sessionStats, nowMs)
        val decayedBucket = applyTemporalDecayToAxis(bucketStats, nowMs)
        val gSess = coldStartGate(decayedSess?.sampleCount ?: 0)
        val gBucket = coldStartGate(decayedBucket?.sampleCount ?: 0)
        val cEff = maxOf(gSess, 0.7 * gBucket).coerceIn(0.0, 1.0)
        if (cEff <= 0.0) {
            return rawModelWeights
        }

        val uniform = AxisPreferenceStats.DEFAULT_UNIFORM_PROBS
        val pBucket = decayedBucket?.probs ?: uniform
        val pBackoff = List(3) { i -> gBucket * pBucket[i] + (1.0 - gBucket) * uniform[i] }
        val pSess = decayedSess?.probs ?: uniform
        val pHat = List(3) { i -> gSess * pSess[i] + (1.0 - gSess) * pBackoff[i] }

        val alphaPrior = (BETA_MAX * cEff).coerceIn(0.0, BETA_MAX)
        val fused = List(3) { i -> (1.0 - alphaPrior) * wNorm[i] + alphaPrior * pHat[i] }
        return normalizeToIntPercentages(fused)
    }

    fun normalizeToIntPercentages(scores: List<Double>): List<Int> {
        if (scores.isEmpty()) return emptyList()
        val sum = scores.sum().coerceAtLeast(1e-9)
        val ints = scores.map { ((it / sum) * 100.0).roundToInt().coerceAtLeast(1) }.toMutableList()
        val diff = 100 - ints.sum()
        if (diff != 0) {
            val maxIdx = scores.indices.maxByOrNull { scores[it] } ?: 0
            ints[maxIdx] = (ints[maxIdx] + diff).coerceAtLeast(1)
        }
        return ints
    }

    fun updateFeatureCounters(
        current: Map<StyleFeatureKey, FeatureCounter>,
        observations: List<FeatureObservation>,
        nowMs: Long
    ): Map<StyleFeatureKey, FeatureCounter> {
        if (observations.isEmpty()) return current
        val next = current.toMutableMap()
        for (obs in observations) {
            val prev = next[obs.key] ?: FeatureCounter()
            val updated = updateSingleCounter(obs.key, prev, obs, nowMs)
            next[obs.key] = updated
        }
        return next
    }

    private fun updateSingleCounter(
        key: StyleFeatureKey,
        prev: FeatureCounter,
        obs: FeatureObservation,
        nowMs: Long
    ): FeatureCounter {
        if (key == StyleFeatureKey.COMPRESS_LENGTH) {
            val nextCount = prev.opportunityCount + 1
            val alphaPrimary = if (prev.isActive && (obs.isReverseEscape || obs.targetValue > 0.78)) {
                0.30
            } else {
                maxOf(0.20, 1.0 / nextCount.toDouble())
            }
            val effectiveTarget = if (prev.isActive && obs.isReverseEscape) {
                obs.targetValue.coerceAtMost(1.25)
            } else {
                obs.targetValue
            }
            val nextRatio = if (prev.opportunityCount == 0) {
                effectiveTarget
            } else {
                (1.0 - alphaPrimary) * prev.emaValue + alphaPrimary * effectiveTarget
            }
            val alphaLen = maxOf(0.20, 1.0 / nextCount.toDouble())
            val nextLen = if (prev.opportunityCount == 0 || prev.secondaryEma <= 0.0) {
                obs.secondaryValue
            } else {
                (1.0 - alphaLen) * prev.secondaryEma + alphaLen * obs.secondaryValue
            }
            val nextActive = when {
                !prev.isActive && nextCount >= 6 && nextRatio <= 0.65 -> true
                prev.isActive && nextRatio > 0.78 -> false
                else -> prev.isActive
            }
            return prev.copy(
                emaValue = nextRatio,
                secondaryEma = nextLen,
                opportunityCount = nextCount,
                isActive = nextActive,
                updatedAtMs = nowMs
            )
        }

        val spec = FEATURE_THRESHOLDS[key] ?: ThresholdSpec(5, 0.75, 0.55)
        val nextCount = prev.opportunityCount + 1
        val isDeactivatingSignal = prev.isActive && (obs.isReverseEscape || obs.targetValue < 0.5)
        val alpha = if (isDeactivatingSignal) {
            0.30
        } else {
            maxOf(0.20, 1.0 / nextCount.toDouble())
        }
        val rawEma = if (prev.opportunityCount == 0) {
            obs.targetValue
        } else {
            (1.0 - alpha) * prev.emaValue + alpha * obs.targetValue
        }
        val cappedEma = rawEma.coerceIn(0.0, ACTIVE_EMA_CAP)
        val nextActive = when {
            !prev.isActive && nextCount >= spec.minOpp && cappedEma >= spec.onThreshold -> true
            prev.isActive && cappedEma < spec.offThreshold -> false
            else -> prev.isActive
        }
        return prev.copy(
            emaValue = cappedEma,
            opportunityCount = nextCount,
            isActive = nextActive,
            updatedAtMs = nowMs
        )
    }

    fun computeCompressedLengthCap(avgSentChars: Double): Int {
        if (!avgSentChars.isFinite() || avgSentChars <= 0.0) return 25
        val raw = (avgSentChars * 1.15) / 5.0
        val rounded = raw.roundToInt() * 5
        return rounded.coerceIn(15, 45)
    }

    fun formatRuleText(key: StyleFeatureKey, counter: FeatureCounter): String {
        return if (key == StyleFeatureKey.COMPRESS_LENGTH) {
            val cap = computeCompressedLengthCap(counter.secondaryEma)
            "极简短句：每条候选严格控制在 $cap 字以内"
        } else {
            key.defaultRuleText
        }
    }
}
