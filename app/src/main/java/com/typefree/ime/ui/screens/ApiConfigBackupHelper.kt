package com.typefree.ime.ui.screens

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.typefree.core.offline.OfflineMode
import com.typefree.core.offline.PendingOfflineRestore
import com.typefree.ime.data.ApiPreset
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.AppToneRule
import com.typefree.ime.data.CandidateFontScale
import com.typefree.ime.data.ClipboardEntry
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.DictionarySource
import com.typefree.ime.data.HapticPreset
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.KeycapBadgeMode
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.OfflineModelConfig
import com.typefree.ime.data.OneHandedMode
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.ProviderCredential
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SpaceLongPressAction
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.ThemePreference
import com.typefree.ime.data.ThinkingMode
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.UnifiedProviderCatalog
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.data.stats.BigramBackupItem
import com.typefree.ime.data.stats.DailyStatsRecord
import com.typefree.ime.data.stats.FullBackupMergeSummary
import com.typefree.ime.data.stats.FullBackupOptions
import com.typefree.ime.data.stats.PredictionBackupArchive
import com.typefree.ime.data.stats.TrigramBackupItem
import com.typefree.ime.network.EndpointResolver
import com.typefree.ime.network.asr.VolcengineSaucAsrClient

object ApiConfigBackupHelper {

    const val REDACTED_SECRET = "***REDACTED***"
    const val BACKUP_FORMAT_VERSION = 2
    const val CLIPBOARD_SAFE_MAX_BYTES = 350_000

    private val prettyGson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val KNOWN_CONFIG_KEYS = setOf(
        "backupFormatVersion",
        "pipelineMode",
        "sttConfig",
        "llmConfig",
        "multimodalConfig",
        "offlineConfig",
        "providerVault",
        "snippets",
        "pauseMediaWhileRecording",
        "whisperModeEnabled",
        "equalEqualAskAiEnabled",
        "qwertyShowNumberRow",
        "backspaceSwipeUpClearEnabled",
        "keyboardHeightLevel",
        "keyboardHeightMode",
        "keyboardLiftLevel",
        "keyboardBottomLiftDp",
        "candidateFontScale",
        "oneHandedMode",
        "t9LeftHandedMirror",
        "spaceLongPressAction",
        "liveWriteToEditor",
        "inputScheme",
        "doublePinyinLayout",
        "fixedCandidateOrder",
        "codetableFiveKeyBottomRow",
        "xiaoheDirectCommandsEnabled",
        "xiaoheReverseSplitEnabled",
        "xiaoheNaturalContinuationEnabled",
        "xiaoheNaturalContinuationLimit",
        "passiveCalculatorEnabled",
        "englishModeWordSuggestionEnabled",
        "englishAutoCap",
        "englishAutoCorrect",
        "phraseSuggestionL2Enabled",
        "customKeyGesturesJson",
        "keycapBadgeMode",
        "predictionEnabled",
        "aiCompletionEnabled",
        "inputStatsEnabled",
        "themePreference",
        "appLanguage",
        "appAwareToneEnabled",
        "selectedManualStyleId",
        "hapticFeedback",
        "hapticPreset",
        "hapticStrength",
        "interactionSounds",
        "keySoundVolume",
        "recentEmojis",
        "emojiSkinTonePrefs",
        "useSurroundingContext",
        "autoLearnDictionary",
        "historyRetention",
        "bubbleEnabled",
        "translationTargets",
        "activeTranslateTarget",
        "styles",
        "appRules",
        "dictionary",
        "historyRecords",
        "dailyStatsRecords",
        "predictionArchive",
        "clipboardPinnedItems"
    )

    fun applyPresetToStt(current: SttConfig, preset: ApiPreset): SttConfig {
        val nextProtocol = preset.sttProtocol ?: SttProtocol.OPENAI_HTTP
        val nextResourceId = when {
            preset.defaultResourceId.isNotBlank() -> preset.defaultResourceId
            nextProtocol == SttProtocol.VOLCENGINE_SAUC_WS && current.resourceId.isBlank() ->
                VolcengineSaucAsrClient.DEFAULT_RESOURCE_ID
            else -> current.resourceId
        }
        return current.copy(
            presetId = preset.id,
            protocol = nextProtocol,
            baseUrl = preset.baseUrl,
            model = preset.defaultModel,
            apiKey = current.apiKey,
            appId = current.appId,
            resourceId = nextResourceId
        )
    }

    fun applyPresetToLlm(current: LlmConfig, preset: ApiPreset): LlmConfig {
        return current.copy(
            presetId = preset.id,
            baseUrl = preset.baseUrl,
            model = preset.defaultModel,
            apiKey = current.apiKey
        )
    }

    fun applyPresetToMultimodal(current: MultimodalConfig, preset: ApiPreset): MultimodalConfig {
        return current.copy(
            presetId = preset.id,
            protocol = preset.protocol ?: current.protocol,
            baseUrl = preset.baseUrl,
            model = preset.defaultModel,
            apiKey = current.apiKey
        )
    }

    /**
     * 1-tap reuse of STT Base URL & API Key for LLM stage.
     * Automatically selects matching LLM model & HTTP endpoint when switching to
     * Google Gemini, Aliyun Bailian (DashScope), Volcengine, SiliconFlow, Groq, or OpenAI.
     */
    fun reuseSttCredentialsForLlm(stt: SttConfig, llm: LlmConfig): LlmConfig {
        val sttUrl = stt.baseUrl.trim()
        val sttLower = sttUrl.lowercase()
        val llmLower = llm.baseUrl.trim().lowercase()

        if (stt.protocol == SttProtocol.GEMINI_LIVE_WS || sttLower.contains("googleapis.com")) {
            return llm.copy(
                enabled = false,
                presetId = "gemini_llm",
                baseUrl = sttUrl.ifBlank { "https://generativelanguage.googleapis.com/v1beta" },
                apiKey = stt.apiKey.trim(),
                model = "gemini-3.5-flash-lite"
            )
        }

        if (stt.presetId == "deepseek_asr" ||
            stt.presetId == "deepseek" ||
            EndpointResolver.isDeepSeekAsrEndpoint(sttUrl)
        ) {
            val chatBaseUrl = sttUrl
                .removeSuffix("/tools/asr")
                .trimEnd('/')
                .ifBlank { "https://api.deepseek.com/v1" }
            val targetModel = if ((llmLower.contains("deepseek.com") || llm.presetId == "deepseek") && llm.model.isNotBlank()) {
                llm.model
            } else {
                "deepseek-chat"
            }
            return llm.copy(
                enabled = true,
                presetId = "deepseek",
                baseUrl = chatBaseUrl,
                apiKey = stt.apiKey.trim().ifBlank { llm.apiKey },
                model = targetModel
            )
        }

        if (stt.protocol == SttProtocol.ALIYUN_DASHSCOPE_WS || sttLower.contains("dashscope.aliyuncs.com")) {
            val targetModel = if (llmLower.contains("dashscope.aliyuncs.com") && llm.model.isNotBlank()) {
                llm.model
            } else {
                "qwen-turbo-latest"
            }
            return llm.copy(
                enabled = true,
                presetId = "aliyun_bailian",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                apiKey = stt.apiKey.trim(),
                model = targetModel
            )
        }

        if (stt.protocol == SttProtocol.VOLCENGINE_SAUC_WS ||
            sttLower.startsWith("ws://") ||
            sttLower.startsWith("wss://")
        ) {
            val useArkPreset = sttLower.contains("bytedance.com") || sttLower.contains("volces.com")
            return llm.copy(
                enabled = true,
                presetId = if (useArkPreset) "doubao" else llm.presetId,
                baseUrl = if (useArkPreset) "https://ark.cn-beijing.volces.com/api/v3" else llm.baseUrl,
                apiKey = stt.apiKey.trim().ifBlank { llm.apiKey },
                model = if (useArkPreset && !llmLower.contains("volces.com")) {
                    "doubao-seed-2-1-lite-260915"
                } else {
                    llm.model
                }
            )
        }

        val (targetPresetId, targetModel) = when {
            sttLower.contains("siliconflow.cn") && !llmLower.contains("siliconflow.cn") ->
                "siliconflow_llm" to "Qwen/Qwen2.5-7B-Instruct"

            sttLower.contains("groq.com") && !llmLower.contains("groq.com") ->
                "groq_llm" to "llama-3.3-70b-versatile"

            sttLower.contains("api.openai.com") && !llmLower.contains("api.openai.com") ->
                "openai_llm" to "gpt-4o-mini"

            else -> llm.presetId to llm.model
        }

        return llm.copy(
            enabled = true,
            presetId = targetPresetId,
            baseUrl = sttUrl,
            apiKey = stt.apiKey.trim(),
            model = targetModel
        )
    }

    fun sttModelSuggestions(presetId: String, protocol: SttProtocol = SttProtocol.OPENAI_HTTP): List<String> {
        if (protocol == SttProtocol.GEMINI_LIVE_WS || presetId == "gemini_live_ws") {
            return listOf("gemini-3.5-transcribe-live")
        }
        if (protocol == SttProtocol.ALIYUN_DASHSCOPE_WS || presetId == "aliyun_asr_ws") {
            return listOf(
                "paraformer-realtime-v2",
                "fun-asr-realtime",
                "paraformer-realtime-8k-v2"
            )
        }
        if (protocol == SttProtocol.VOLCENGINE_SAUC_WS || presetId == "volcengine_sauc_ws") {
            return listOf("bigmodel")
        }
        return when (presetId) {
            "siliconflow" -> listOf(
                "FunAudioLLM/SenseVoiceSmall",
                "TeleAI/TeleSpeechASR"
            )
            "deepseek_asr", "deepseek" -> listOf("deepseek-asr")
            "groq" -> listOf(
                "whisper-large-v3-turbo",
                "whisper-large-v3"
            )
            "openai" -> listOf(
                "gpt-4o-mini-transcribe",
                "gpt-4o-transcribe",
                "whisper-1"
            )
            else -> listOf(
                "whisper-1",
                "FunAudioLLM/SenseVoiceSmall",
                "whisper-large-v3-turbo",
                "TeleAI/TeleSpeechASR"
            )
        }
    }

    fun llmModelSuggestions(presetId: String): List<String> {
        return when (presetId) {
            "gemini_llm" -> listOf(
                "gemini-3.5-flash-lite",
                "gemini-3.6-flash",
                "gemini-2.5-flash"
            )
            "doubao" -> listOf(
                "doubao-seed-2-1-lite-260915",
                "doubao-seed-2-1-turbo-260628",
                "doubao-seed-1-6-flash-250828"
            )
            "aliyun_bailian" -> listOf(
                "qwen-turbo-latest",
                "qwen-plus-latest",
                "qwen3-8b"
            )
            "siliconflow_llm" -> listOf(
                "Qwen/Qwen3-8B",
                "Qwen/Qwen2.5-7B-Instruct",
                "Qwen/Qwen2.5-32B-Instruct",
                "deepseek-ai/DeepSeek-V3"
            )
            "deepseek" -> listOf("deepseek-chat", "deepseek-reasoner")
            "groq_llm" -> listOf(
                "llama-3.3-70b-versatile",
                "llama-3.1-8b-instant"
            )
            "zhipu" -> listOf("glm-4-flash", "glm-4-air", "glm-4-plus")
            "ollama" -> listOf("qwen2.5:7b", "qwen2.5:14b", "llama3.2:3b")
            else -> listOf(
                "gemini-3.5-flash-lite",
                "doubao-seed-2-1-lite-260915",
                "qwen-turbo-latest",
                "deepseek-chat"
            )
        }
    }

    fun multimodalModelSuggestions(presetId: String, protocol: MultimodalProtocol): List<String> {
        return when {
            presetId == "gemini" || presetId == "newapi_gemini" || protocol == MultimodalProtocol.GEMINI_NATIVE -> listOf(
                "gemini-3.5-flash-lite",
                "gemini-3.6-flash",
                "gemini-2.5-flash"
            )
            presetId == "openai_audio" -> listOf(
                "gpt-4o-mini-audio-preview",
                "gpt-4o-audio-preview"
            )
            else -> listOf(
                "google/gemini-3.5-flash-lite",
                "google/gemini-2.5-flash",
                "gpt-4o-mini-audio-preview"
            )
        }
    }

    /**
     * Resolves multimodal endpoint preview while masking secret API key query param.
     */
    fun previewMultimodalEndpoint(config: MultimodalConfig): String {
        return when (config.protocol) {
            MultimodalProtocol.GEMINI_NATIVE -> {
                val maskedKey = if (config.apiKey.isBlank()) "YOUR_API_KEY" else "***"
                EndpointResolver.resolveGeminiEndpoint(config.baseUrl, config.model, maskedKey)
            }
            MultimodalProtocol.OPENAI_AUDIO_CHAT -> {
                EndpointResolver.resolveChatEndpoint(config.baseUrl)
            }
        }
    }

    fun exportSettingsJson(settings: AppSettings, includeSecrets: Boolean = true): String {
        val target = if (includeSecrets) {
            settings
        } else {
            val redactedVault = settings.providerVault.mapValues { (_, cred) ->
                cred.copy(
                    apiKey = if (cred.apiKey.isNotBlank()) REDACTED_SECRET else "",
                    sttApiKey = if (cred.sttApiKey.isNotBlank()) REDACTED_SECRET else ""
                )
            }
            settings.copy(
                sttConfig = settings.sttConfig.copy(
                    apiKey = if (settings.sttConfig.apiKey.isNotBlank()) REDACTED_SECRET else ""
                ),
                llmConfig = settings.llmConfig.copy(
                    apiKey = if (settings.llmConfig.apiKey.isNotBlank()) REDACTED_SECRET else ""
                ),
                multimodalConfig = settings.multimodalConfig.copy(
                    apiKey = if (settings.multimodalConfig.apiKey.isNotBlank()) REDACTED_SECRET else ""
                ),
                providerVault = redactedVault
            )
        }
        return prettyGson.toJson(target)
    }

    data class FullBackupImportOutcome(
        val settings: AppSettings,
        val mergeSummary: FullBackupMergeSummary = FullBackupMergeSummary()
    )

    /**
     * Full lossless user-data backup exporter (v2):
     * Exports [AppSettings] plus history records (with local `audioFilePath` stripped),
     * zero-PII daily stats + token usage records, L2 personal bigram/trigram prediction archive,
     * and pinned clipboard items.
     *
     * When `options.includeSecrets == false` (redacted share mode), strictly omits secrets and personal user data.
     */
    fun exportFullBackupJson(
        settings: AppSettings,
        options: FullBackupOptions = FullBackupOptions(),
        history: List<HistoryRecord> = emptyList(),
        dailyStats: List<DailyStatsRecord> = emptyList(),
        predictions: PredictionBackupArchive = PredictionBackupArchive(),
        pinnedClipboard: List<ClipboardEntry> = emptyList()
    ): String {
        val baseJson = exportSettingsJson(settings, includeSecrets = options.includeSecrets)
        val rootObj = JsonParser.parseString(baseJson).asJsonObject
        rootObj.addProperty("backupFormatVersion", BACKUP_FORMAT_VERSION)

        if (!options.includeSecrets) {
            return prettyGson.toJson(rootObj)
        }

        if (options.includeHistory && history.isNotEmpty()) {
            val portableHistory = history.map { it.copy(audioFilePath = "") }
            rootObj.add("historyRecords", prettyGson.toJsonTree(portableHistory))
        }

        if (options.includeDailyStats && dailyStats.isNotEmpty()) {
            rootObj.add("dailyStatsRecords", prettyGson.toJsonTree(dailyStats))
        }

        if (options.includePredictions && !predictions.isEmpty) {
            rootObj.add("predictionArchive", prettyGson.toJsonTree(predictions))
        }

        if (options.includePinnedClipboard) {
            val pinnedOnly = pinnedClipboard.filter { it.isPinned && it.text.isNotBlank() }
            if (pinnedOnly.isNotEmpty()) {
                rootObj.add("clipboardPinnedItems", prettyGson.toJsonTree(pinnedOnly))
            }
        }

        return prettyGson.toJson(rootObj)
    }

    /**
     * Full lossless user-data backup importer (v2 + 100% backward compatible with v1 config JSON):
     * Restores settings and losslessly merges/deduplicates
     * daily stats (incl. v2 token counters), history records, L2 prediction archive, and pinned clipboard items.
     */
    fun importFullBackupJson(
        rawJson: String,
        existingSettings: AppSettings = AppSettings(),
        repository: AppRepository? = null
    ): Result<FullBackupImportOutcome> {
        val mergedSettings = parseAndMergeConfigJson(rawJson, existingSettings)
            .getOrElse { return Result.failure(it) }
        val baseOutcome = FullBackupImportOutcome(settings = mergedSettings)

        val cleaned = unwrapCodeFence(rawJson)
        val rootObj = runCatching { JsonParser.parseString(cleaned).asJsonObject }.getOrNull()
            ?: return Result.success(baseOutcome)

        val parsedHistory = parseHistoryRecords(rootObj)
        val parsedDailyStats = parseDailyStatsRecords(rootObj)
        val parsedPredictions = parsePredictionArchive(rootObj)
        val parsedPinnedClipboard = parsePinnedClipboardItems(rootObj)

        val mergeSummary = if (repository != null) {
            val mergedDays = if (parsedDailyStats.isNotEmpty()) {
                repository.mergeImportedDailyStats(parsedDailyStats)
            } else {
                0
            }
            val mergedHistory = if (parsedHistory.isNotEmpty()) {
                repository.mergeImportedHistory(parsedHistory)
            } else {
                0
            }
            val (mergedBigrams, mergedTrigrams) = if (!parsedPredictions.isEmpty) {
                repository.importAndMergePredictions(parsedPredictions)
            } else {
                0 to 0
            }
            val mergedPinned = if (parsedPinnedClipboard.isNotEmpty()) {
                repository.mergeImportedPinnedClipboard(parsedPinnedClipboard)
            } else {
                0
            }
            FullBackupMergeSummary(
                mergedHistoryCount = mergedHistory,
                mergedDailyStatsDays = mergedDays,
                mergedBigramCount = mergedBigrams,
                mergedTrigramCount = mergedTrigrams,
                mergedPinnedClipboardCount = mergedPinned
            )
        } else {
            FullBackupMergeSummary(
                mergedHistoryCount = parsedHistory.size,
                mergedDailyStatsDays = parsedDailyStats.size,
                mergedBigramCount = parsedPredictions.bigrams.size,
                mergedTrigramCount = parsedPredictions.trigrams.size,
                mergedPinnedClipboardCount = parsedPinnedClipboard.size
            )
        }

        return Result.success(
            baseOutcome.copy(mergeSummary = mergeSummary)
        )
    }

    private fun parseHistoryRecords(rootObj: JsonObject): List<HistoryRecord> {
        val arr = rootObj.optArrayObjects("historyRecords") ?: return emptyList()
        return arr.mapNotNull { obj ->
            val polished = obj.optString("polishedText")?.trim().orEmpty()
            if (polished.isEmpty()) return@mapNotNull null
            val isSpeakToEdit = obj.optBoolean("isSpeakToEdit") ?: false
            val intent = obj.optString("intentMode")?.let { raw ->
                VoiceIntentMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            } ?: if (isSpeakToEdit) VoiceIntentMode.SPEAK_TO_EDIT else VoiceIntentMode.DICTATE
            HistoryRecord(
                id = obj.optString("id")?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                timestamp = obj.optLong("timestamp")?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                targetAppName = obj.optString("targetAppName") ?: "未知应用",
                targetPackage = obj.optString("targetPackage") ?: "",
                styleName = obj.optString("styleName") ?: "智能润色",
                isSpeakToEdit = isSpeakToEdit,
                intentMode = intent,
                translateTargetLanguage = obj.optString("translateTargetLanguage") ?: "",
                originalSelectedText = obj.optString("originalSelectedText") ?: "",
                rawTranscript = obj.optString("rawTranscript") ?: "",
                polishedText = polished,
                durationMs = (obj.optLong("durationMs") ?: 0L).coerceAtLeast(0L),
                latencyMs = (obj.optLong("latencyMs") ?: 0L).coerceAtLeast(0L),
                modeLabel = obj.optString("modeLabel") ?: "",
                audioFilePath = "",
                executionSource = obj.optString("executionSource") ?: ""
            )
        }
    }

    private fun parseDailyStatsRecords(rootObj: JsonObject): List<DailyStatsRecord> {
        val arr = rootObj.optArrayObjects("dailyStatsRecords") ?: return emptyList()
        return arr.mapNotNull { obj ->
            val dateKey = obj.optLong("dateKey")?.toInt() ?: return@mapNotNull null
            if (dateKey <= 0) return@mapNotNull null
            val promptTok = (obj.optLong("cloudPromptTokens") ?: 0L).coerceAtLeast(0L)
            val cachedTok = (obj.optLong("cloudCachedTokens") ?: 0L).coerceIn(0L, promptTok)
            DailyStatsRecord(
                dateKey = dateKey,
                voiceChars = (obj.optLong("voiceChars") ?: 0L).toInt().coerceAtLeast(0),
                voiceCount = (obj.optLong("voiceCount") ?: 0L).toInt().coerceAtLeast(0),
                voiceDurationMs = (obj.optLong("voiceDurationMs") ?: 0L).coerceAtLeast(0L),
                voiceLatencySumMs = (obj.optLong("voiceLatencySumMs") ?: 0L).coerceAtLeast(0L),
                voiceLatencyCount = (obj.optLong("voiceLatencyCount") ?: 0L).toInt().coerceAtLeast(0),
                helpWriteRawChars = (obj.optLong("helpWriteRawChars") ?: 0L).toInt().coerceAtLeast(0),
                helpWritePolishedChars = (obj.optLong("helpWritePolishedChars") ?: 0L).toInt().coerceAtLeast(0),
                helpWriteCount = (obj.optLong("helpWriteCount") ?: 0L).toInt().coerceAtLeast(0),
                keyChars = (obj.optLong("keyChars") ?: 0L).toInt().coerceAtLeast(0),
                keyCommits = (obj.optLong("keyCommits") ?: 0L).toInt().coerceAtLeast(0),
                offlineCount = (obj.optLong("offlineCount") ?: 0L).toInt().coerceAtLeast(0),
                cloudCount = (obj.optLong("cloudCount") ?: 0L).toInt().coerceAtLeast(0),
                snippetHits = (obj.optLong("snippetHits") ?: 0L).toInt().coerceAtLeast(0),
                snippetSavedChars = (obj.optLong("snippetSavedChars") ?: 0L).toInt().coerceAtLeast(0),
                predictionHits = (obj.optLong("predictionHits") ?: 0L).toInt().coerceAtLeast(0),
                predictionSavedKeys = (obj.optLong("predictionSavedKeys") ?: 0L).toInt().coerceAtLeast(0),
                hourlyVoiceChars = obj.optIntList24("hourlyVoiceChars"),
                hourlyKeyChars = obj.optIntList24("hourlyKeyChars"),
                schemeChars = obj.optIntMap("schemeChars"),
                intentCounts = obj.optIntMap("intentCounts"),
                appCounts = obj.optIntMap("appCounts"),
                styleCounts = obj.optIntMap("styleCounts"),
                cloudPromptTokens = promptTok,
                cloudCachedTokens = cachedTok,
                cloudCompletionTokens = (obj.optLong("cloudCompletionTokens") ?: 0L).coerceAtLeast(0L),
                cloudReasoningTokens = (obj.optLong("cloudReasoningTokens") ?: 0L).coerceAtLeast(0L),
                cloudEstimatedTokenCalls = (obj.optLong("cloudEstimatedTokenCalls") ?: 0L).toInt().coerceAtLeast(0),
                cloudVoiceDurationMs = (obj.optLong("cloudVoiceDurationMs") ?: 0L).coerceAtLeast(0L),
                offlineVoiceDurationMs = (obj.optLong("offlineVoiceDurationMs") ?: 0L).coerceAtLeast(0L),
                tokenByModel = obj.optLongMap("tokenByModel"),
                tokenByFeature = obj.optLongMap("tokenByFeature")
            )
        }
    }

    private fun parsePredictionArchive(rootObj: JsonObject): PredictionBackupArchive {
        val archObj = rootObj.optObject("predictionArchive") ?: return PredictionBackupArchive()
        val bigrams = archObj.optArrayObjects("bigrams")?.mapNotNull { obj ->
            val prev = obj.optString("prevWord")?.trim().orEmpty()
            val next = obj.optString("nextWord")?.trim().orEmpty()
            if (prev.isEmpty() || next.isEmpty() || prev == next) return@mapNotNull null
            BigramBackupItem(
                prevWord = prev,
                nextWord = next,
                count = (obj.optLong("count") ?: 1L).toInt().coerceAtLeast(1),
                lastUsedMs = (obj.optLong("lastUsedMs") ?: 0L).coerceAtLeast(0L)
            )
        }.orEmpty()
        val trigrams = archObj.optArrayObjects("trigrams")?.mapNotNull { obj ->
            val pp = obj.optString("prevPrevWord")?.trim().orEmpty()
            val prev = obj.optString("prevWord")?.trim().orEmpty()
            val next = obj.optString("nextWord")?.trim().orEmpty()
            if (pp.isEmpty() || prev.isEmpty() || next.isEmpty() || prev == next) return@mapNotNull null
            TrigramBackupItem(
                prevPrevWord = pp,
                prevWord = prev,
                nextWord = next,
                count = (obj.optLong("count") ?: 1L).toInt().coerceAtLeast(1),
                lastUsedMs = (obj.optLong("lastUsedMs") ?: 0L).coerceAtLeast(0L)
            )
        }.orEmpty()
        return PredictionBackupArchive(bigrams = bigrams, trigrams = trigrams)
    }

    private fun parsePinnedClipboardItems(rootObj: JsonObject): List<ClipboardEntry> {
        val arr = rootObj.optArrayObjects("clipboardPinnedItems") ?: return emptyList()
        return arr.mapNotNull { obj ->
            val text = obj.optString("text")?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            ClipboardEntry(
                id = obj.optString("id")?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                text = text,
                timestamp = obj.optLong("timestamp")?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                isPinned = true
            )
        }
    }

    fun unwrapCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val withoutStart = trimmed
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .trimStart()
        return withoutStart.removeSuffix("```").trim()
    }

    /**
     * Validates and merges imported JSON over existing AppSettings safely.
     * Protects non-null Kotlin fields from partial JSON and preserves local keys if imported JSON was redacted.
     */
    fun parseAndMergeConfigJson(
        rawJson: String,
        existingSettings: AppSettings = AppSettings()
    ): Result<AppSettings> {
        val cleaned = unwrapCodeFence(rawJson)
        if (cleaned.isBlank()) {
            return Result.failure(IllegalArgumentException("剪贴板中没有可导入的配置文本"))
        }

        val rootObj: JsonObject = try {
            val parsed = JsonParser.parseString(cleaned)
            if (!parsed.isJsonObject) {
                return Result.failure(IllegalArgumentException("配置格式无效：根节点必须是 JSON 对象"))
            }
            parsed.asJsonObject
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("JSON 语法解析失败：${e.message ?: "格式错误"}"))
        }

        if (KNOWN_CONFIG_KEYS.none { rootObj.has(it) }) {
            return Result.failure(IllegalArgumentException("未识别到灵言 TypeFree 配置字段，请检查复制的 JSON 内容"))
        }

        return try {
            val mode = rootObj.optString("pipelineMode")?.let { rawMode ->
                PipelineMode.entries.firstOrNull { it.name.equals(rawMode, ignoreCase = true) }
            } ?: existingSettings.pipelineMode

            val themePref = rootObj.optString("themePreference")?.let { rawTheme ->
                ThemePreference.entries.firstOrNull { it.name.equals(rawTheme, ignoreCase = true) }
            } ?: existingSettings.themePreference

            val retention = rootObj.optString("historyRetention")?.let { rawRet ->
                HistoryRetention.entries.firstOrNull { it.name.equals(rawRet, ignoreCase = true) }
            } ?: existingSettings.historyRetention

            val sttObj = rootObj.optObject("sttConfig")
            val mergedStt = if (sttObj != null) {
                val importedKey = sttObj.optString("apiKey")
                val importedSttProto = sttObj.optString("protocol")?.let { p ->
                    SttProtocol.entries.firstOrNull { it.name.equals(p, ignoreCase = true) }
                }
                val rawPresetId = sttObj.optString("presetId") ?: existingSettings.sttConfig.presetId
                val rawBaseUrl = sttObj.optString("baseUrl")?.trim() ?: existingSettings.sttConfig.baseUrl
                val isDeepSeekStt = rawPresetId == "deepseek_asr" ||
                    rawPresetId == "deepseek" ||
                    EndpointResolver.isDeepSeekAsrEndpoint(rawBaseUrl)
                val normalizedBaseUrl = if (isDeepSeekStt) {
                    EndpointResolver.resolveSttEndpoint(rawBaseUrl.ifBlank { EndpointResolver.DEFAULT_DEEPSEEK_ASR_URL })
                } else {
                    rawBaseUrl
                }
                val rawModel = sttObj.optString("model")?.trim() ?: existingSettings.sttConfig.model
                val normalizedModel = if (
                    isDeepSeekStt &&
                    (rawModel.isBlank() || (!sttObj.has("model") && rawModel == "FunAudioLLM/SenseVoiceSmall"))
                ) {
                    "deepseek-asr"
                } else {
                    rawModel
                }
                existingSettings.sttConfig.copy(
                    presetId = if (isDeepSeekStt && !sttObj.has("presetId")) "deepseek_asr" else rawPresetId,
                    protocol = importedSttProto ?: existingSettings.sttConfig.protocol,
                    baseUrl = normalizedBaseUrl,
                    apiKey = resolveImportedSecret(importedKey, existingSettings.sttConfig.apiKey),
                    appId = sttObj.optString("appId")?.trim() ?: existingSettings.sttConfig.appId,
                    resourceId = sttObj.optString("resourceId")?.trim() ?: existingSettings.sttConfig.resourceId,
                    model = normalizedModel,
                    language = sttObj.optString("language") ?: existingSettings.sttConfig.language
                )
            } else {
                existingSettings.sttConfig
            }

            val llmObj = rootObj.optObject("llmConfig")
            val mergedLlm = if (llmObj != null) {
                val importedKey = llmObj.optString("apiKey")
                val importedThinking = llmObj.optString("thinkingMode")?.let { raw ->
                    ThinkingMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                }
                existingSettings.llmConfig.copy(
                    enabled = llmObj.optBoolean("enabled") ?: existingSettings.llmConfig.enabled,
                    presetId = llmObj.optString("presetId") ?: existingSettings.llmConfig.presetId,
                    baseUrl = llmObj.optString("baseUrl")?.trim() ?: existingSettings.llmConfig.baseUrl,
                    apiKey = resolveImportedSecret(importedKey, existingSettings.llmConfig.apiKey),
                    model = llmObj.optString("model")?.trim() ?: existingSettings.llmConfig.model,
                    temperature = (llmObj.optFloat("temperature") ?: existingSettings.llmConfig.temperature)
                        .coerceIn(0.0f, 1.0f),
                    thinkingMode = importedThinking ?: existingSettings.llmConfig.thinkingMode
                )
            } else {
                existingSettings.llmConfig
            }

            val mmObj = rootObj.optObject("multimodalConfig")
            val mergedMm = if (mmObj != null) {
                val importedKey = mmObj.optString("apiKey")
                val importedProto = mmObj.optString("protocol")?.let { p ->
                    MultimodalProtocol.entries.firstOrNull { it.name.equals(p, ignoreCase = true) }
                }
                val importedThinking = mmObj.optString("thinkingMode")?.let { raw ->
                    ThinkingMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                }
                existingSettings.multimodalConfig.copy(
                    presetId = mmObj.optString("presetId") ?: existingSettings.multimodalConfig.presetId,
                    protocol = importedProto ?: existingSettings.multimodalConfig.protocol,
                    baseUrl = mmObj.optString("baseUrl")?.trim() ?: existingSettings.multimodalConfig.baseUrl,
                    apiKey = resolveImportedSecret(importedKey, existingSettings.multimodalConfig.apiKey),
                    model = mmObj.optString("model")?.trim() ?: existingSettings.multimodalConfig.model,
                    temperature = (mmObj.optFloat("temperature") ?: existingSettings.multimodalConfig.temperature)
                        .coerceIn(0.0f, 1.0f),
                    thinkingMode = importedThinking ?: existingSettings.multimodalConfig.thinkingMode
                )
            } else {
                existingSettings.multimodalConfig
            }

            val mergedVault = rootObj.optObject("providerVault")?.let { vaultObj ->
                val mutable = existingSettings.providerVault.toMutableMap()
                vaultObj.entrySet().forEach { (pid, el) ->
                    if (el.isJsonObject) {
                        val obj = el.asJsonObject
                        val prev = mutable[pid] ?: ProviderCredential(providerId = pid)
                        mutable[pid] = prev.copy(
                            providerId = pid,
                            apiKey = resolveImportedSecret(obj.optString("apiKey"), prev.apiKey),
                            sttApiKey = resolveImportedSecret(obj.optString("sttApiKey"), prev.sttApiKey),
                            appId = obj.optString("appId")?.trim() ?: prev.appId,
                            customBaseUrl = obj.optString("customBaseUrl")?.trim() ?: prev.customBaseUrl
                        )
                    }
                }
                mutable
            } ?: existingSettings.providerVault

            val mergedSnippets = rootObj.optArrayObjects("snippets")?.mapNotNull { item ->
                val expanded = (item.optString("content") ?: item.optString("expandedContent"))
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                SnippetEntry(
                    id = item.optString("id")?.takeIf { it.isNotBlank() } ?: expanded.take(12),
                    title = item.optString("title") ?: "短语",
                    voiceTriggers = item.optString("voiceTriggers") ?: "",
                    flypyCode = item.optString("flypyCode") ?: "",
                    content = expanded,
                    enabled = item.optBoolean("enabled") ?: true
                )
            } ?: existingSettings.snippets

            val mergedStyles = rootObj.optArrayObjects("styles")?.mapNotNull { item ->
                val name = item.optString("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val instruction = item.optString("promptInstruction")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ToneStyle(
                    id = item.optString("id")?.takeIf { it.isNotBlank() } ?: name,
                    name = name,
                    emoji = item.optString("emoji")?.ifBlank { "✨" } ?: "✨",
                    summary = item.optString("summary") ?: "",
                    promptInstruction = instruction,
                    isBuiltIn = item.optBoolean("isBuiltIn") ?: false
                )
            }?.takeIf { it.isNotEmpty() } ?: existingSettings.styles.ifEmpty { DefaultPresets.defaultStyles() }

            val mergedRules = rootObj.optArrayObjects("appRules")?.mapNotNull { item ->
                val pkg = item.optString("packageName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val styleId = item.optString("styleId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AppToneRule(
                    id = item.optString("id")?.takeIf { it.isNotBlank() } ?: pkg,
                    packageName = pkg,
                    appName = item.optString("appName") ?: pkg,
                    styleId = styleId,
                    enabled = item.optBoolean("enabled") ?: true
                )
            } ?: existingSettings.appRules

            val mergedDict = rootObj.optArrayObjects("dictionary")?.mapNotNull { item ->
                val term = item.optString("term")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val parsedSource = item.optString("source")?.let { s ->
                    DictionarySource.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
                } ?: DictionarySource.MANUAL
                DictionaryEntry(
                    id = item.optString("id")?.takeIf { it.isNotBlank() } ?: term,
                    term = term,
                    wrongVariants = item.optString("wrongVariants") ?: "",
                    note = item.optString("note") ?: "",
                    enabled = item.optBoolean("enabled") ?: true,
                    source = parsedSource,
                    createdAt = item.optLong("createdAt") ?: System.currentTimeMillis()
                )
            } ?: existingSettings.dictionary

            val mergedTargets = rootObj.optStringArray("translationTargets")
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?: existingSettings.translationTargets

            val mergedActiveTarget = rootObj.optString("activeTranslateTarget")
                ?.takeIf { it.isNotBlank() }
                ?: existingSettings.activeTranslateTarget

            val mergedHeightLevel = rootObj.optLong("keyboardHeightLevel")
                ?.toInt()
                ?.coerceIn(-2, 2)
                ?: existingSettings.keyboardHeightLevel

            val mergedHeightMode = rootObj.optString("keyboardHeightMode")?.let { rawMode ->
                KeyboardHeightMode.entries.firstOrNull { it.name.equals(rawMode, ignoreCase = true) }
            } ?: existingSettings.keyboardHeightMode

            val mergedLiftLevel = rootObj.optLong("keyboardLiftLevel")
                ?.toInt()
                ?.coerceIn(0, 4)
                ?: existingSettings.keyboardLiftLevel

            val rawImportedBottomLiftDp = rootObj.optLong("keyboardBottomLiftDp")?.toInt()
            val mergedBottomLiftDp = when {
                rawImportedBottomLiftDp != null && rawImportedBottomLiftDp >= 0 ->
                    rawImportedBottomLiftDp.coerceIn(0, 56)
                rootObj.has("keyboardLiftLevel") || rootObj.has("keyboardHeightMode") ->
                    AppRepository.resolveMigratedBottomLiftDp(
                        rawBottomLiftDp = -1,
                        heightMode = mergedHeightMode,
                        liftLevel = mergedLiftLevel
                    )
                rawImportedBottomLiftDp != null ->
                    AppRepository.resolveMigratedBottomLiftDp(
                        rawBottomLiftDp = rawImportedBottomLiftDp,
                        heightMode = mergedHeightMode,
                        liftLevel = mergedLiftLevel
                    )
                else -> existingSettings.keyboardBottomLiftDp
            }

            val mergedCandidateFontScale = rootObj.optString("candidateFontScale")?.let { rawScale ->
                CandidateFontScale.entries.firstOrNull { it.name.equals(rawScale, ignoreCase = true) }
            } ?: existingSettings.candidateFontScale

            val mergedOneHandedMode = rootObj.optString("oneHandedMode")?.let { rawOneHanded ->
                OneHandedMode.entries.firstOrNull { it.name.equals(rawOneHanded, ignoreCase = true) }
            } ?: existingSettings.oneHandedMode

            val mergedT9LeftHandedMirror = rootObj.optBoolean("t9LeftHandedMirror")
                ?: existingSettings.t9LeftHandedMirror

            val mergedSpaceLongPressAction = rootObj.optString("spaceLongPressAction")?.let { rawAction ->
                SpaceLongPressAction.entries.firstOrNull { it.name.equals(rawAction, ignoreCase = true) }
            } ?: existingSettings.spaceLongPressAction

            val importedHapticFeedback = rootObj.optBoolean("hapticFeedback")
            val importedHapticPreset = rootObj.optString("hapticPreset")?.let { rawPreset ->
                HapticPreset.entries.firstOrNull { it.name.equals(rawPreset, ignoreCase = true) }
            }
            val mergedHapticPreset = when {
                importedHapticFeedback == false -> HapticPreset.OFF
                importedHapticPreset != null -> importedHapticPreset
                importedHapticFeedback == true ->
                    if (existingSettings.hapticPreset == HapticPreset.OFF) HapticPreset.SYSTEM else existingSettings.hapticPreset
                else -> existingSettings.hapticPreset
            }
            val mergedHapticStrength = rootObj.optLong("hapticStrength")
                ?.toInt()?.coerceIn(10, 100)
                ?: existingSettings.hapticStrength
            val mergedKeySoundVolume = rootObj.optLong("keySoundVolume")
                ?.toInt()?.coerceIn(0, 100)
                ?: existingSettings.keySoundVolume
            val mergedRecentEmojis = rootObj.getAsJsonArray("recentEmojis")?.let { arr ->
                arr.mapNotNull { el -> runCatching { el.asString }.getOrNull()?.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(24)
            } ?: existingSettings.recentEmojis
            val mergedSkinTonePrefs = rootObj.optObject("emojiSkinTonePrefs")?.let { obj ->
                buildMap<String, String> {
                    for ((k, v) in obj.entrySet()) {
                        val valueStr = runCatching { v.asString }.getOrNull()?.trim().orEmpty()
                        if (k.isNotBlank() && valueStr.isNotEmpty()) {
                            put(k, valueStr)
                        }
                    }
                }
            } ?: existingSettings.emojiSkinTonePrefs

            val mergedInputScheme = rootObj.optString("inputScheme")?.let { rawScheme ->
                InputScheme.entries.firstOrNull { it.name.equals(rawScheme, ignoreCase = true) }
            } ?: existingSettings.inputScheme

            val mergedDoublePinyinLayout = rootObj.optString("doublePinyinLayout")?.let { rawDp ->
                com.typefree.ime.data.DoublePinyinLayout.entries.firstOrNull { it.name.equals(rawDp, ignoreCase = true) }
            } ?: existingSettings.doublePinyinLayout

            val mergedKeycapBadgeMode = rootObj.optString("keycapBadgeMode")?.let { rawBadgeMode ->
                KeycapBadgeMode.entries.firstOrNull { it.name.equals(rawBadgeMode, ignoreCase = true) }
            } ?: existingSettings.keycapBadgeMode

            val mergedFixedOrder = if (rootObj.has("fixedCandidateOrder")) {
                rootObj.optBoolean("fixedCandidateOrder")
            } else {
                existingSettings.fixedCandidateOrder
            }

            val offlineObj = rootObj.optObject("offlineConfig")
            val (mergedOfflineConfig, mergedPendingOfflineRestore) = if (offlineObj != null) {
                val importedMode = offlineObj.optString("mode")?.let { rawMode ->
                    OfflineMode.entries.firstOrNull { it.name.equals(rawMode, ignoreCase = true) }
                } ?: OfflineMode.OFF
                val importedAsrSlotOn = offlineObj.optBoolean("asrSlotOn")
                    ?: existingSettings.offlineConfig.asrSlotOn
                val importedSlmSlotOn = offlineObj.optBoolean("slmSlotOn")
                    ?: existingSettings.offlineConfig.slmSlotOn
                val importedAsrId = offlineObj.optString("selectedAsrModelId")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val importedSlmId = offlineObj.optString("selectedSlmModelId")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val importedSchemaVersion = offlineObj.optLong("schemaVersion")
                    ?.toInt()
                    ?.coerceAtLeast(1)
                    ?: existingSettings.offlineConfig.schemaVersion
                // Parse layer has no local model directory context: keep active mode OFF to avoid
                // new-device deadlock and record pendingOfflineRestore for guided re-download.
                val safeConfig = OfflineModelConfig(
                    mode = OfflineMode.OFF,
                    asrSlotOn = importedAsrSlotOn,
                    slmSlotOn = importedSlmSlotOn,
                    selectedAsrModelId = importedAsrId,
                    selectedSlmModelId = importedSlmId,
                    schemaVersion = importedSchemaVersion
                )
                val pending = if (importedMode != OfflineMode.OFF) {
                    PendingOfflineRestore(
                        mode = importedMode,
                        selectedAsrModelId = importedAsrId,
                        selectedSlmModelId = importedSlmId
                    )
                } else {
                    null
                }
                safeConfig to pending
            } else {
                existingSettings.offlineConfig to existingSettings.pendingOfflineRestore
            }

            val candidate = existingSettings.copy(
                pipelineMode = mode,
                sttConfig = mergedStt,
                llmConfig = mergedLlm,
                multimodalConfig = mergedMm,
                offlineConfig = mergedOfflineConfig,
                pendingOfflineRestore = mergedPendingOfflineRestore,
                providerVault = mergedVault,
                snippets = mergedSnippets,
                pauseMediaWhileRecording = rootObj.optBoolean("pauseMediaWhileRecording")
                    ?: existingSettings.pauseMediaWhileRecording,
                whisperModeEnabled = rootObj.optBoolean("whisperModeEnabled")
                    ?: existingSettings.whisperModeEnabled,
                equalEqualAskAiEnabled = rootObj.optBoolean("equalEqualAskAiEnabled")
                    ?: existingSettings.equalEqualAskAiEnabled,
                qwertyShowNumberRow = rootObj.optBoolean("qwertyShowNumberRow")
                    ?: existingSettings.qwertyShowNumberRow,
                backspaceSwipeUpClearEnabled = rootObj.optBoolean("backspaceSwipeUpClearEnabled")
                    ?: existingSettings.backspaceSwipeUpClearEnabled,
                keyboardHeightLevel = mergedHeightLevel,
                keyboardHeightMode = mergedHeightMode,
                keyboardLiftLevel = mergedLiftLevel,
                keyboardBottomLiftDp = mergedBottomLiftDp,
                candidateFontScale = mergedCandidateFontScale,
                oneHandedMode = mergedOneHandedMode,
                t9LeftHandedMirror = mergedT9LeftHandedMirror,
                spaceLongPressAction = mergedSpaceLongPressAction,
                liveWriteToEditor = rootObj.optBoolean("liveWriteToEditor")
                    ?: existingSettings.liveWriteToEditor,
                inputScheme = mergedInputScheme,
                doublePinyinLayout = mergedDoublePinyinLayout,
                fixedCandidateOrder = mergedFixedOrder,
                codetableFiveKeyBottomRow = rootObj.optBoolean("codetableFiveKeyBottomRow")
                    ?: existingSettings.codetableFiveKeyBottomRow,
                xiaoheDirectCommandsEnabled = rootObj.optBoolean("xiaoheDirectCommandsEnabled")
                    ?: existingSettings.xiaoheDirectCommandsEnabled,
                xiaoheReverseSplitEnabled = rootObj.optBoolean("xiaoheReverseSplitEnabled")
                    ?: existingSettings.xiaoheReverseSplitEnabled,
                xiaoheNaturalContinuationEnabled = rootObj.optBoolean("xiaoheNaturalContinuationEnabled")
                    ?: existingSettings.xiaoheNaturalContinuationEnabled,
                xiaoheNaturalContinuationLimit = rootObj.optLong("xiaoheNaturalContinuationLimit")
                    ?.toInt()
                    ?.coerceIn(1, com.typefree.ime.data.MAX_NATURAL_CONTINUATION_LIMIT)
                    ?: existingSettings.xiaoheNaturalContinuationLimit,
                passiveCalculatorEnabled = rootObj.optBoolean("passiveCalculatorEnabled")
                    ?: existingSettings.passiveCalculatorEnabled,
                englishModeWordSuggestionEnabled = rootObj.optBoolean("englishModeWordSuggestionEnabled")
                    ?: existingSettings.englishModeWordSuggestionEnabled,
                englishAutoCap = rootObj.optBoolean("englishAutoCap")
                    ?: existingSettings.englishAutoCap,
                englishAutoCorrect = rootObj.optBoolean("englishAutoCorrect")
                    ?: existingSettings.englishAutoCorrect,
                phraseSuggestionL2Enabled = rootObj.optBoolean("phraseSuggestionL2Enabled")
                    ?: existingSettings.phraseSuggestionL2Enabled,
                customKeyGesturesJson = rootObj.optString("customKeyGesturesJson")
                    ?: existingSettings.customKeyGesturesJson,
                keycapBadgeMode = mergedKeycapBadgeMode,
                predictionEnabled = rootObj.optBoolean("predictionEnabled")
                    ?: existingSettings.predictionEnabled,
                aiCompletionEnabled = rootObj.optBoolean("aiCompletionEnabled")
                    ?: existingSettings.aiCompletionEnabled,
                inputStatsEnabled = rootObj.optBoolean("inputStatsEnabled")
                    ?: existingSettings.inputStatsEnabled,
                themePreference = themePref,
                appLanguage = rootObj.optString("appLanguage")
                    ?.let { com.typefree.ime.i18n.AppStrings.normalizeAppLanguage(it) }
                    ?: existingSettings.appLanguage,
                appAwareToneEnabled = rootObj.optBoolean("appAwareToneEnabled")
                    ?: existingSettings.appAwareToneEnabled,
                selectedManualStyleId = rootObj.optString("selectedManualStyleId")
                    ?.takeIf { it.isNotBlank() } ?: existingSettings.selectedManualStyleId,
                hapticFeedback = mergedHapticPreset != HapticPreset.OFF,
                hapticPreset = mergedHapticPreset,
                hapticStrength = mergedHapticStrength,
                interactionSounds = rootObj.optBoolean("interactionSounds")
                    ?: existingSettings.interactionSounds,
                keySoundVolume = mergedKeySoundVolume,
                recentEmojis = mergedRecentEmojis,
                emojiSkinTonePrefs = mergedSkinTonePrefs,
                useSurroundingContext = rootObj.optBoolean("useSurroundingContext")
                    ?: existingSettings.useSurroundingContext,
                autoLearnDictionary = rootObj.optBoolean("autoLearnDictionary")
                    ?: existingSettings.autoLearnDictionary,
                historyRetention = retention,
                bubbleEnabled = rootObj.optBoolean("bubbleEnabled")
                    ?: existingSettings.bubbleEnabled,
                translationTargets = mergedTargets,
                activeTranslateTarget = mergedActiveTarget,
                styles = mergedStyles,
                appRules = mergedRules,
                dictionary = mergedDict
            )

            val hydratedVault = UnifiedProviderCatalog.hydrateVaultFromSettings(candidate)
            val syncedStt = if (candidate.sttConfig.apiKey.isBlank()) {
                val sttPid = UnifiedProviderCatalog.inferSttProviderId(candidate.sttConfig)
                val cred = hydratedVault[sttPid]
                if (cred != null && cred.apiKey.isNotBlank()) {
                    candidate.sttConfig.copy(apiKey = cred.apiKey)
                } else {
                    candidate.sttConfig
                }
            } else {
                candidate.sttConfig
            }
            val syncedLlm = if (candidate.llmConfig.apiKey.isBlank()) {
                val llmPid = UnifiedProviderCatalog.inferLlmProviderId(candidate.llmConfig)
                val cred = hydratedVault[llmPid]
                if (cred != null && cred.apiKey.isNotBlank()) {
                    candidate.llmConfig.copy(apiKey = cred.apiKey)
                } else {
                    candidate.llmConfig
                }
            } else {
                candidate.llmConfig
            }
            Result.success(
                candidate.copy(
                    sttConfig = syncedStt,
                    llmConfig = syncedLlm,
                    providerVault = hydratedVault
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("导入配置时发生异常：${e.message}"))
        }
    }

    private fun resolveImportedSecret(importedKey: String?, existingKey: String): String {
        if (importedKey == null) return existingKey
        val trimmed = importedKey.trim()
        return if (trimmed == REDACTED_SECRET) existingKey else trimmed
    }

    private fun JsonObject.optString(key: String): String? {
        val el = get(key) ?: return null
        return if (el.isJsonPrimitive) el.asString else null
    }

    private fun JsonObject.optBoolean(key: String): Boolean? {
        val el = get(key) ?: return null
        return if (el.isJsonPrimitive && el.asJsonPrimitive.isBoolean) el.asBoolean else null
    }

    private fun JsonObject.optFloat(key: String): Float? {
        val el = get(key) ?: return null
        return if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asFloat else null
    }

    private fun JsonObject.optLong(key: String): Long? {
        val el = get(key) ?: return null
        return if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asLong else null
    }

    private fun JsonObject.optObject(key: String): JsonObject? {
        val el = get(key) ?: return null
        return if (el.isJsonObject) el.asJsonObject else null
    }

    private fun JsonObject.optArrayObjects(key: String): List<JsonObject>? {
        val el = get(key) ?: return null
        if (!el.isJsonArray) return null
        return el.asJsonArray.mapNotNull { item ->
            if (item.isJsonObject) item.asJsonObject else null
        }
    }

    private fun JsonObject.optStringArray(key: String): List<String>? {
        val el = get(key) ?: return null
        if (!el.isJsonArray) return null
        return el.asJsonArray.mapNotNull { item ->
            if (item.isJsonPrimitive) item.asString else null
        }
    }

    private fun JsonObject.optIntList24(key: String): List<Int> {
        val el = get(key) ?: return List(24) { 0 }
        if (!el.isJsonArray) return List(24) { 0 }
        val arr = el.asJsonArray
        return List(24) { idx ->
            if (idx < arr.size() && arr[idx].isJsonPrimitive && arr[idx].asJsonPrimitive.isNumber) {
                arr[idx].asInt.coerceAtLeast(0)
            } else {
                0
            }
        }
    }

    private fun JsonObject.optIntMap(key: String): Map<String, Int> {
        val obj = optObject(key) ?: return emptyMap()
        val out = LinkedHashMap<String, Int>(obj.size())
        for ((k, v) in obj.entrySet()) {
            val cleanKey = k.trim()
            if (cleanKey.isNotEmpty() && v.isJsonPrimitive && v.asJsonPrimitive.isNumber) {
                val count = v.asInt
                if (count > 0) out[cleanKey] = count
            }
        }
        return out
    }

    private fun JsonObject.optLongMap(key: String): Map<String, Long> {
        val obj = optObject(key) ?: return emptyMap()
        val out = LinkedHashMap<String, Long>(obj.size())
        for ((k, v) in obj.entrySet()) {
            val cleanKey = k.trim()
            if (cleanKey.isNotEmpty() && v.isJsonPrimitive && v.asJsonPrimitive.isNumber) {
                val count = v.asLong
                if (count > 0L) out[cleanKey] = count
            }
        }
        return out
    }
}
