package com.typefree.ime.ime.jev.memory

import kotlin.math.max

data class FeatureObservation(
    val key: StyleFeatureKey,
    val targetValue: Double,
    val secondaryValue: Double = 0.0,
    val isReverseEscape: Boolean = false
)

/**
 * Pure Kotlin pre-send draft difference analyzer:
 * 1. Filters full rewrites via rolling-array character LCS (`LCSRatio < 0.25` -> null).
 * 2. Extracts opportunity-conditioned forward edit signals (`D_commit -> D_sent`).
 * 3. Extracts reverse-escape signals when a feature is already active so users can naturally
 *    unwind any learned rule simply by manually adding punctuation/emoji/honorifics back.
 */
object DraftDiffAnalyzer {

    private const val MAX_LCS_CHARS = 300
    const val MIN_VALID_LCS_RATIO = 0.25

    private val TRAILING_SINGLE_PERIOD_REGEX = Regex("""(?<![.\u2026])([.。])$""")
    private val TRAILING_END_PUNCT_REGEX = Regex("""[。.!！?？~～…]+$""")
    private val EXCL_TILDE_REGEX = Regex("""[!！~～]""")
    private val BRACKET_EMOJI_REGEX = Regex("""\[[\u4e00-\u9fa5A-Za-z0-9_]{1,6}]""")
    private val PLAIN_NI_REGEX = Regex("""你(?!们)""")
    private val MODAL_PARTICLE_REGEX = Regex("""[哈呢呀啦喔](?=[，,。.!！?？~～\s]|$)""")
    private val POLITE_PREFIX_REGEX = Regex("""(?:不好意思|打扰一下|打扰了|麻烦您|麻烦你|烦请|实在抱歉)[，,、\s]*""")

    fun computeLcsRatio(committed: String, sent: String): Double {
        val a = committed.trim().take(MAX_LCS_CHARS)
        val b = sent.trim().take(MAX_LCS_CHARS)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0

        val n = a.length
        val m = b.length
        // Swap so inner DP row is the shorter string for O(min(n, m)) memory
        val (s1, s2) = if (n >= m) a to b else b to a
        val rows = s1.length
        val cols = s2.length
        var prev = IntArray(cols + 1)
        var curr = IntArray(cols + 1)
        for (i in 1..rows) {
            val ch1 = s1[i - 1]
            for (j in 1..cols) {
                curr[j] = if (ch1 == s2[j - 1]) {
                    prev[j - 1] + 1
                } else {
                    max(prev[j], curr[j - 1])
                }
            }
            val tmp = prev
            prev = curr
            curr = tmp
            curr.fill(0)
        }
        val lcsLen = prev[cols]
        return lcsLen.toDouble() / max(a.length, 1).toDouble()
    }

    fun extractFeatureObservations(
        committed: String,
        sent: String,
        activeFeatures: Set<StyleFeatureKey> = emptySet()
    ): List<FeatureObservation>? {
        val cTrim = committed.trim()
        val sTrim = sent.trim()
        if (cTrim.isEmpty() || sTrim.isEmpty()) return null
        if (computeLcsRatio(cTrim, sTrim) < MIN_VALID_LCS_RATIO) {
            return null
        }

        val out = ArrayList<FeatureObservation>(6)

        // 1. DROP_TRAILING_PERIOD
        val commitEndsWithPeriod = TRAILING_SINGLE_PERIOD_REGEX.containsMatchIn(cTrim)
        val sentEndsWithAnyPunct = TRAILING_END_PUNCT_REGEX.containsMatchIn(sTrim)
        val sentEndsWithPeriod = TRAILING_SINGLE_PERIOD_REGEX.containsMatchIn(sTrim)
        if (commitEndsWithPeriod) {
            val dropped = !sentEndsWithAnyPunct
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.DROP_TRAILING_PERIOD,
                    targetValue = if (dropped) 1.0 else 0.0
                )
            )
        } else if (StyleFeatureKey.DROP_TRAILING_PERIOD in activeFeatures &&
            !TRAILING_END_PUNCT_REGEX.containsMatchIn(cTrim) &&
            sentEndsWithPeriod
        ) {
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.DROP_TRAILING_PERIOD,
                    targetValue = 0.0,
                    isReverseEscape = true
                )
            )
        }

        // 2. DAMPEN_EXCLAMATION_TILDE
        val excl0 = EXCL_TILDE_REGEX.findAll(cTrim).count()
        val excl1 = EXCL_TILDE_REGEX.findAll(sTrim).count()
        if (excl0 >= 1) {
            val dampened = excl1 == 0 || excl1 <= 0.3 * excl0
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.DAMPEN_EXCLAMATION_TILDE,
                    targetValue = if (dampened) 1.0 else 0.0
                )
            )
        } else if (StyleFeatureKey.DAMPEN_EXCLAMATION_TILDE in activeFeatures && excl1 >= 1) {
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.DAMPEN_EXCLAMATION_TILDE,
                    targetValue = 0.0,
                    isReverseEscape = true
                )
            )
        }

        // 3. STRIP_EMOJI
        val emoji0 = countEmojisAndTags(cTrim)
        val emoji1 = countEmojisAndTags(sTrim)
        if (emoji0 >= 1) {
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.STRIP_EMOJI,
                    targetValue = if (emoji1 == 0) 1.0 else 0.0
                )
            )
        } else if (StyleFeatureKey.STRIP_EMOJI in activeFeatures && emoji1 >= 1) {
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.STRIP_EMOJI,
                    targetValue = 0.0,
                    isReverseEscape = true
                )
            )
        }

        // 4. HONORIFIC_NIN_TO_NI & HONORIFIC_NI_TO_NIN
        val nin0 = cTrim.count { it == '您' }
        val nin1 = sTrim.count { it == '您' }
        val ni0 = PLAIN_NI_REGEX.findAll(cTrim).count()
        val ni1 = PLAIN_NI_REGEX.findAll(sTrim).count()

        if (nin0 >= 1) {
            val downgradedToNi = nin1 == 0 && (ni1 >= 1 || sTrim.length < cTrim.length)
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.HONORIFIC_NIN_TO_NI,
                    targetValue = if (downgradedToNi) 1.0 else 0.0
                )
            )
            if (StyleFeatureKey.HONORIFIC_NI_TO_NIN in activeFeatures && downgradedToNi) {
                out.add(
                    FeatureObservation(
                        key = StyleFeatureKey.HONORIFIC_NI_TO_NIN,
                        targetValue = 0.0,
                        isReverseEscape = true
                    )
                )
            }
        } else if (StyleFeatureKey.HONORIFIC_NIN_TO_NI in activeFeatures && ni0 >= 1 && nin1 >= 1 && ni1 < ni0) {
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.HONORIFIC_NIN_TO_NI,
                    targetValue = 0.0,
                    isReverseEscape = true
                )
            )
        }

        if (ni0 >= 1 && nin0 == 0) {
            val upgradedToNin = nin1 >= 1 && ni1 < ni0
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.HONORIFIC_NI_TO_NIN,
                    targetValue = if (upgradedToNin) 1.0 else 0.0
                )
            )
        }

        // 5. TRIM_MODAL_PARTICLE
        val modal0 = MODAL_PARTICLE_REGEX.findAll(cTrim).count()
        val modal1 = MODAL_PARTICLE_REGEX.findAll(sTrim).count()
        if (modal0 >= 1) {
            val trimmedModals = modal1 == 0 || (modal0 - modal1).toDouble() / modal0 >= 0.75
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.TRIM_MODAL_PARTICLE,
                    targetValue = if (trimmedModals) 1.0 else 0.0
                )
            )
        } else if (StyleFeatureKey.TRIM_MODAL_PARTICLE in activeFeatures && modal1 >= 1) {
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.TRIM_MODAL_PARTICLE,
                    targetValue = 0.0,
                    isReverseEscape = true
                )
            )
        }

        // 6. TRIM_POLITE_PREFIX
        val polite0 = POLITE_PREFIX_REGEX.findAll(cTrim).count()
        val polite1 = POLITE_PREFIX_REGEX.findAll(sTrim).count()
        if (polite0 >= 1) {
            val trimmedPolite = polite1 == 0 || (polite0 - polite1).toDouble() / polite0 >= 0.75
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.TRIM_POLITE_PREFIX,
                    targetValue = if (trimmedPolite) 1.0 else 0.0
                )
            )
        } else if (StyleFeatureKey.TRIM_POLITE_PREFIX in activeFeatures && polite1 >= 1) {
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.TRIM_POLITE_PREFIX,
                    targetValue = 0.0,
                    isReverseEscape = true
                )
            )
        }

        // 7. COMPRESS_LENGTH
        val len0 = effectiveCharCount(cTrim)
        val len1 = effectiveCharCount(sTrim)
        if (len0 >= 18) {
            val ratio = (len1.toDouble() / len0.toDouble()).coerceIn(0.1, 1.5)
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.COMPRESS_LENGTH,
                    targetValue = ratio,
                    secondaryValue = len1.toDouble()
                )
            )
        } else if (StyleFeatureKey.COMPRESS_LENGTH in activeFeatures &&
            len0 >= 6 &&
            len1 >= 28 &&
            len1 >= len0 * 1.4
        ) {
            out.add(
                FeatureObservation(
                    key = StyleFeatureKey.COMPRESS_LENGTH,
                    targetValue = (len1.toDouble() / len0.toDouble()).coerceIn(1.0, 1.25),
                    secondaryValue = len1.toDouble(),
                    isReverseEscape = true
                )
            )
        }

        return out
    }

    fun effectiveCharCount(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!Character.isWhitespace(cp)) {
                count++
            }
            i += Character.charCount(cp)
        }
        return count
    }

    fun countEmojisAndTags(text: String): Int {
        var count = BRACKET_EMOJI_REGEX.findAll(text).count()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isEmojiCodePoint(cp)) {
                count++
            }
            i += Character.charCount(cp)
        }
        return count
    }

    private fun isEmojiCodePoint(cp: Int): Boolean {
        return cp in 0x1F300..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0xFE00..0xFE0F && false
    }
}
