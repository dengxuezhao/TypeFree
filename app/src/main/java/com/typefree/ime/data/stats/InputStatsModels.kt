package com.typefree.ime.data.stats

import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.VoiceIntentMode

enum class StatsTimeRange(val label: String, val posterBadge: String) {
    DAYS_7("近 7 天", "近 7 天"),
    THIS_MONTH("本月", "本月"),
    THIS_YEAR("本年", "年度"),
    ALL("全部", "累计")
}

/**
 * Normalized token usage snapshot from a cloud LLM / multimodal API call.
 *
 * Invariant:
 * - [promptTokens] is total input tokens (including [cachedTokens], where `cachedTokens <= promptTokens`).
 * - [completionTokens] is **visible** output tokens (non-overlapping with [reasoningTokens]).
 * - [reasoningTokens] is thinking/CoT tokens (non-overlapping with [completionTokens]).
 * - [isEstimated] is `true` when the gateway omitted `usage` / `usageMetadata` and counts were
 *   approximated from character lengths so the UI can show an "含估算" badge.
 */
data class TokenUsage(
    val promptTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val totalTokensOverride: Long = 0L,
    val isEstimated: Boolean = false
) {
    val safePromptTokens: Long get() = promptTokens.coerceAtLeast(0L)
    val safeCachedTokens: Long get() = cachedTokens.coerceIn(0L, safePromptTokens)
    val uncachedPromptTokens: Long get() = (safePromptTokens - safeCachedTokens).coerceAtLeast(0L)
    val visibleOutputTokens: Long get() = completionTokens.coerceAtLeast(0L)
    val safeReasoningTokens: Long get() = reasoningTokens.coerceAtLeast(0L)

    val totalTokens: Long
        get() {
            val computed = safePromptTokens + visibleOutputTokens + safeReasoningTokens
            return if (totalTokensOverride > 0L) maxOf(totalTokensOverride, computed) else computed
        }

    val hasUsage: Boolean
        get() = safePromptTokens > 0L || visibleOutputTokens > 0L || safeReasoningTokens > 0L || totalTokensOverride > 0L

    operator fun plus(other: TokenUsage?): TokenUsage {
        if (other == null || !other.hasUsage) return this
        if (!this.hasUsage) return other
        val sumPrompt = safePromptTokens + other.safePromptTokens
        val sumCached = (safeCachedTokens + other.safeCachedTokens).coerceAtMost(sumPrompt)
        val sumVisible = visibleOutputTokens + other.visibleOutputTokens
        val sumReasoning = safeReasoningTokens + other.safeReasoningTokens
        val sumTotal = totalTokens + other.totalTokens
        return TokenUsage(
            promptTokens = sumPrompt,
            cachedTokens = sumCached,
            completionTokens = sumVisible,
            reasoningTokens = sumReasoning,
            totalTokensOverride = sumTotal,
            isEstimated = this.isEstimated || other.isEstimated
        )
    }

    companion object {
        /**
         * Rough fallback token estimator when a third-party OpenAI-compatible gateway omits `usage`.
         * CJK/non-ASCII chars average ~1.5 tokens per 2 chars (~0.75/char) while ASCII words average ~0.35/char.
         */
        fun estimateFromTexts(inputText: String, outputText: String): TokenUsage {
            fun estimateTokens(text: String): Long {
                if (text.isBlank()) return 0L
                var cjk = 0
                var other = 0
                for (ch in text) {
                    if (ch.code > 127) cjk++ else other++
                }
                val est = kotlin.math.ceil(cjk * 0.8 + other * 0.30).toLong()
                return est.coerceAtLeast(1L)
            }
            val p = estimateTokens(inputText)
            val c = estimateTokens(outputText)
            return TokenUsage(
                promptTokens = p,
                cachedTokens = 0L,
                completionTokens = c,
                reasoningTokens = 0L,
                totalTokensOverride = p + c,
                isEstimated = true
            )
        }
    }
}

object TokenFeatureKeys {
    const val VOICE_POLISH = "VOICE_POLISH"
    const val SPEAK_TO_EDIT = "SPEAK_TO_EDIT"
    const val HELP_ME_WRITE = "HELP_ME_WRITE"
    const val TRANSLATE = "TRANSLATE"
    const val ASK_AI = "ASK_AI"
    const val SENTENCE_COMPLETION = "SENTENCE_COMPLETION"
    const val MULTIMODAL_AUDIO = "MULTIMODAL_AUDIO"
    const val API_TEST = "API_TEST"

    fun displayNameForKey(key: String): String = when (key) {
        VOICE_POLISH -> "语音口述润色"
        SPEAK_TO_EDIT -> "语音/快捷改写"
        HELP_ME_WRITE -> "帮我写 / 灵感成稿"
        TRANSLATE -> "实时语音翻译"
        ASK_AI -> "问 AI / == 直问"
        SENTENCE_COMPLETION -> "AI 整句续写"
        MULTIMODAL_AUDIO -> "多模态音频直出"
        API_TEST -> "连通性实测"
        else -> key
    }

    fun fromVoiceIntent(intent: VoiceIntentMode, isMultimodalAudio: Boolean = false): String {
        if (isMultimodalAudio && intent == VoiceIntentMode.DICTATE) return MULTIMODAL_AUDIO
        return when (intent) {
            VoiceIntentMode.DICTATE -> VOICE_POLISH
            VoiceIntentMode.SPEAK_TO_EDIT -> SPEAK_TO_EDIT
            VoiceIntentMode.HELP_ME_WRITE -> HELP_ME_WRITE
            VoiceIntentMode.TRANSLATE -> TRANSLATE
        }
    }
}

/**
 * Zero-PII daily numeric rollup record (`daily_input_stats` table).
 * Stores only counts, character totals, durations, token counters, and bucketed distributions — never raw text.
 */
data class DailyStatsRecord(
    val dateKey: Int, // YYYYMMDD, e.g. 20261123
    val voiceChars: Int = 0,
    val voiceCount: Int = 0,
    val voiceDurationMs: Long = 0L,
    val voiceLatencySumMs: Long = 0L,
    val voiceLatencyCount: Int = 0,
    val helpWriteRawChars: Int = 0,
    val helpWritePolishedChars: Int = 0,
    val helpWriteCount: Int = 0,
    val keyChars: Int = 0,
    val keyCommits: Int = 0,
    val offlineCount: Int = 0,
    val cloudCount: Int = 0,
    val snippetHits: Int = 0,
    val snippetSavedChars: Int = 0,
    val predictionHits: Int = 0,
    val predictionSavedKeys: Int = 0,
    val hourlyVoiceChars: List<Int> = List(24) { 0 },
    val hourlyKeyChars: List<Int> = List(24) { 0 },
    val schemeChars: Map<String, Int> = emptyMap(), // key: InputScheme.name or "ENGLISH"
    val intentCounts: Map<String, Int> = emptyMap(), // key: VoiceIntentMode.name
    val appCounts: Map<String, Int> = emptyMap(), // key: targetAppName (coarse app label)
    val styleCounts: Map<String, Int> = emptyMap(), // key: styleName
    // Schema v2: Pure Token & Cloud/Offline Voice Duration Observability (zero cost estimation)
    val cloudPromptTokens: Long = 0L,
    val cloudCachedTokens: Long = 0L,
    val cloudCompletionTokens: Long = 0L,
    val cloudReasoningTokens: Long = 0L,
    val cloudEstimatedTokenCalls: Int = 0,
    val cloudVoiceDurationMs: Long = 0L,
    val offlineVoiceDurationMs: Long = 0L,
    val tokenByModel: Map<String, Long> = emptyMap(),
    val tokenByFeature: Map<String, Long> = emptyMap()
) {
    val totalChars: Int get() = voiceChars + keyChars
    val hasActivity: Boolean get() = totalChars > 0 || voiceCount > 0 || keyCommits > 0
    val totalCloudTokens: Long
        get() = cloudPromptTokens.coerceAtLeast(0L) +
            cloudCompletionTokens.coerceAtLeast(0L) +
            cloudReasoningTokens.coerceAtLeast(0L)
}

data class HeatmapDayCell(
    val dateKey: Int,
    val dateLabel: String, // e.g. "11月23日"
    val voiceChars: Int,
    val keyChars: Int,
    val voiceCount: Int,
    val topAppName: String,
    val intensityLevel: Int // 0..4
) {
    val totalChars: Int get() = voiceChars + keyChars
}

data class MonthlyTrendPoint(
    val year: Int,
    val month: Int, // 1..12
    val label: String, // e.g. "11月"
    val voiceChars: Int,
    val keyChars: Int,
    val isFuture: Boolean = false
) {
    val totalChars: Int get() = voiceChars + keyChars
}

data class SchemeShareItem(
    val key: String,
    val displayName: String,
    val chars: Int,
    val percent: Int
)

data class IntentShareItem(
    val mode: VoiceIntentMode,
    val label: String,
    val count: Int,
    val percent: Int
)

data class AppSceneShareItem(
    val appName: String,
    val sceneCategory: String,
    val count: Int,
    val percent: Int
)

data class MilestoneItem(
    val id: String,
    val glyph: String,
    val title: String,
    val description: String,
    val unlocked: Boolean,
    val progressPercent: Int,
    val progressText: String
)

data class InsightsSnapshot(
    val range: StatsTimeRange,
    val isColdStart: Boolean,
    val totalChars: Int,
    val voiceChars: Int,
    val keyChars: Int,
    val voiceCount: Int,
    val keyCommits: Int,
    val savedMinutes: Int,
    val averageWpm: Int,
    val hasMeasuredWpm: Boolean,
    val speedMultiplier: Float,
    val avgLatencyMs: Long,
    val helpWriteLeverage: Float,
    val offlinePercent: Int,
    val hasOfflineSplitData: Boolean,
    val streakDays: Int,
    val activeDaysInRange: Int,
    val lifetimeActiveDays: Int,
    val lifetimeTotalChars: Int,
    val metaphorLine: String,
    val rhythmHeadline: String,
    val mini7DayChars: List<Int>,
    val heatmapDays: List<HeatmapDayCell>,
    val monthlyTrend: List<MonthlyTrendPoint>,
    val hourlyVoiceChars: List<Int>,
    val hourlyKeyChars: List<Int>,
    val schemeShares: List<SchemeShareItem>,
    val intentShares: List<IntentShareItem>,
    val topAppScenes: List<AppSceneShareItem>,
    val snippetHits: Int,
    val snippetSavedChars: Int,
    val predictionHits: Int,
    val predictionSavedKeys: Int,
    val autoLearnedDictCount: Int,
    val totalDictCount: Int,
    val personalBigramCount: Int,
    val personalTrigramCount: Int,
    val topWordChains: List<String>,
    val milestones: List<MilestoneItem>,
    val statsDbSizeBytes: Long,
    val statsTrackedDays: Int
)

enum class SharePosterModule(val title: String, val chipLabel: String) {
    HERO("解放双手大字账单", "大字账单"),
    DUAL_TRACK("语音 × 键盘双轨", "双轨占比"),
    SHORTCUTS("短语与联想省键", "省键账单"),
    HEATMAP("陪伴热力足迹", "热力足迹"),
    MONTHLY_TREND("12 月成长走势", "月度走势"),
    RHYTHM_24H("24h 双色作息节律", "24h 节律"),
    SCENES("主要表达场景", "场景分布"),
    MILESTONES("离线护航与印记", "离线印记")
}

enum class SharePosterSkin(val label: String) {
    OBSIDIAN_DARK("曜石黑"),
    PAPER_LIGHT("纸感白"),
    EMERALD_NIGHT("暗夜翠")
}

enum class SharePosterPreset(
    val label: String,
    val modules: List<SharePosterModule>
) {
    EFFICIENCY(
        label = "效率战报",
        modules = listOf(SharePosterModule.HERO, SharePosterModule.RHYTHM_24H, SharePosterModule.MILESTONES)
    ),
    ANNUAL(
        label = "年度手账",
        modules = listOf(
            SharePosterModule.HERO,
            SharePosterModule.DUAL_TRACK,
            SharePosterModule.MONTHLY_TREND,
            SharePosterModule.MILESTONES
        )
    ),
    KEYBOARD(
        label = "键盘战报",
        modules = listOf(
            SharePosterModule.HERO,
            SharePosterModule.DUAL_TRACK,
            SharePosterModule.SHORTCUTS,
            SharePosterModule.RHYTHM_24H
        )
    ),
    SCENES(
        label = "场景足迹",
        modules = listOf(SharePosterModule.SCENES, SharePosterModule.RHYTHM_24H, SharePosterModule.MILESTONES)
    )
}

object SchemeStatsKeys {
    const val ENGLISH_DIRECT = "ENGLISH"

    fun fromInputScheme(scheme: InputScheme, isChineseMode: Boolean): String {
        return if (!isChineseMode) ENGLISH_DIRECT else scheme.name
    }

    fun displayNameForKey(key: String): String {
        return when (key) {
            InputScheme.FLYPY_TABLE.name -> "小鹤音形"
            InputScheme.RIME_PINYIN.name -> "雾凇全拼"
            InputScheme.RIME_T9_PINYIN.name -> "九键拼音"
            InputScheme.RIME_DOUBLE_PINYIN_FLYPY.name -> "小鹤双拼"
            InputScheme.RIME_CUSTOM.name -> "自定义码表"
            ENGLISH_DIRECT -> "英文直输"
            else -> key
        }
    }
}

data class TokenShareItem(
    val key: String,
    val label: String,
    val tokens: Long,
    val percent: Int
)

/**
 * Pure Token & Cloud/Offline Voice Duration Observability snapshot (strictly zero cost/price estimation).
 */
data class TokenUsageSnapshot(
    val range: StatsTimeRange,
    val hasData: Boolean,
    val totalTokens: Long,
    val promptTokens: Long,
    val cachedTokens: Long,
    val uncachedPromptTokens: Long,
    val visibleOutputTokens: Long,
    val reasoningTokens: Long,
    val cacheHitPercent: Int,
    val estimatedCallCount: Int,
    val cloudVoiceDurationMs: Long,
    val offlineVoiceDurationMs: Long,
    val offlineSavedCloudCalls: Int,
    val byFeature: List<TokenShareItem>,
    val byModel: List<TokenShareItem>
)

data class BigramBackupItem(
    val prevWord: String,
    val nextWord: String,
    val count: Int,
    val lastUsedMs: Long
)

data class TrigramBackupItem(
    val prevPrevWord: String,
    val prevWord: String,
    val nextWord: String,
    val count: Int,
    val lastUsedMs: Long
)

data class PredictionBackupArchive(
    val bigrams: List<BigramBackupItem> = emptyList(),
    val trigrams: List<TrigramBackupItem> = emptyList()
) {
    val isEmpty: Boolean get() = bigrams.isEmpty() && trigrams.isEmpty()
}

data class FullBackupOptions(
    val includeSecrets: Boolean = true,
    val includeHistory: Boolean = true,
    val includeDailyStats: Boolean = true,
    val includePredictions: Boolean = true,
    val includePinnedClipboard: Boolean = true
)

data class FullBackupMergeSummary(
    val mergedHistoryCount: Int = 0,
    val mergedDailyStatsDays: Int = 0,
    val mergedBigramCount: Int = 0,
    val mergedTrigramCount: Int = 0,
    val mergedPinnedClipboardCount: Int = 0
) {
    val hasExtendedData: Boolean
        get() = mergedHistoryCount > 0 ||
            mergedDailyStatsDays > 0 ||
            mergedBigramCount > 0 ||
            mergedTrigramCount > 0 ||
            mergedPinnedClipboardCount > 0
}
