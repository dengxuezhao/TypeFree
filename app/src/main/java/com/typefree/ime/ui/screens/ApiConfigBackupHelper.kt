package com.typefree.ime.ui.screens

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.typefree.ime.data.ApiPreset
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.AppToneRule
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.DictionarySource
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.ProviderCredential
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.ThemePreference
import com.typefree.ime.data.ThinkingMode
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.UnifiedProviderCatalog
import com.typefree.ime.network.EndpointResolver
import com.typefree.ime.network.asr.VolcengineSaucAsrClient

object ApiConfigBackupHelper {

    const val REDACTED_SECRET = "***REDACTED***"

    private val prettyGson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val KNOWN_CONFIG_KEYS = setOf(
        "pipelineMode",
        "sttConfig",
        "llmConfig",
        "multimodalConfig",
        "providerVault",
        "snippets",
        "pauseMediaWhileRecording",
        "whisperModeEnabled",
        "equalEqualAskAiEnabled",
        "keyboardHeightLevel",
        "keyboardHeightMode",
        "keyboardLiftLevel",
        "liveWriteToEditor",
        "inputScheme",
        "fixedCandidateOrder",
        "predictionEnabled",
        "aiCompletionEnabled",
        "jevCopilotEnabled",
        "jevUseDedicatedModel",
        "jevLlmConfig",
        "chatContextSendScreenshot",
        "themePreference",
        "appAwareToneEnabled",
        "selectedManualStyleId",
        "hapticFeedback",
        "interactionSounds",
        "useSurroundingContext",
        "autoLearnDictionary",
        "historyRetention",
        "bubbleEnabled",
        "translationTargets",
        "activeTranslateTarget",
        "styles",
        "appRules",
        "dictionary"
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
                jevLlmConfig = settings.jevLlmConfig.copy(
                    apiKey = if (settings.jevLlmConfig.apiKey.isNotBlank()) REDACTED_SECRET else ""
                ),
                providerVault = redactedVault
            )
        }
        return prettyGson.toJson(target)
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
                existingSettings.sttConfig.copy(
                    presetId = sttObj.optString("presetId") ?: existingSettings.sttConfig.presetId,
                    protocol = importedSttProto ?: existingSettings.sttConfig.protocol,
                    baseUrl = sttObj.optString("baseUrl")?.trim() ?: existingSettings.sttConfig.baseUrl,
                    apiKey = resolveImportedSecret(importedKey, existingSettings.sttConfig.apiKey),
                    appId = sttObj.optString("appId")?.trim() ?: existingSettings.sttConfig.appId,
                    resourceId = sttObj.optString("resourceId")?.trim() ?: existingSettings.sttConfig.resourceId,
                    model = sttObj.optString("model")?.trim() ?: existingSettings.sttConfig.model,
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

            val jevLlmObj = rootObj.optObject("jevLlmConfig")
            val mergedJevLlm = if (jevLlmObj != null) {
                val importedKey = jevLlmObj.optString("apiKey")
                val importedThinking = jevLlmObj.optString("thinkingMode")?.let { raw ->
                    ThinkingMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                }
                existingSettings.jevLlmConfig.copy(
                    enabled = jevLlmObj.optBoolean("enabled") ?: existingSettings.jevLlmConfig.enabled,
                    presetId = jevLlmObj.optString("presetId") ?: existingSettings.jevLlmConfig.presetId,
                    baseUrl = jevLlmObj.optString("baseUrl")?.trim() ?: existingSettings.jevLlmConfig.baseUrl,
                    apiKey = resolveImportedSecret(importedKey, existingSettings.jevLlmConfig.apiKey),
                    model = jevLlmObj.optString("model")?.trim() ?: existingSettings.jevLlmConfig.model,
                    temperature = (jevLlmObj.optFloat("temperature") ?: existingSettings.jevLlmConfig.temperature)
                        .coerceIn(0.0f, 1.0f),
                    thinkingMode = importedThinking ?: existingSettings.jevLlmConfig.thinkingMode
                )
            } else {
                existingSettings.jevLlmConfig
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

            val mergedInputScheme = rootObj.optString("inputScheme")?.let { rawScheme ->
                InputScheme.entries.firstOrNull { it.name.equals(rawScheme, ignoreCase = true) }
            } ?: existingSettings.inputScheme

            val mergedFixedOrder = if (rootObj.has("fixedCandidateOrder")) {
                rootObj.optBoolean("fixedCandidateOrder")
            } else {
                existingSettings.fixedCandidateOrder
            }

            val candidate = existingSettings.copy(
                pipelineMode = mode,
                sttConfig = mergedStt,
                llmConfig = mergedLlm,
                multimodalConfig = mergedMm,
                providerVault = mergedVault,
                snippets = mergedSnippets,
                pauseMediaWhileRecording = rootObj.optBoolean("pauseMediaWhileRecording")
                    ?: existingSettings.pauseMediaWhileRecording,
                whisperModeEnabled = rootObj.optBoolean("whisperModeEnabled")
                    ?: existingSettings.whisperModeEnabled,
                equalEqualAskAiEnabled = rootObj.optBoolean("equalEqualAskAiEnabled")
                    ?: existingSettings.equalEqualAskAiEnabled,
                keyboardHeightLevel = mergedHeightLevel,
                keyboardHeightMode = mergedHeightMode,
                keyboardLiftLevel = mergedLiftLevel,
                liveWriteToEditor = rootObj.optBoolean("liveWriteToEditor")
                    ?: existingSettings.liveWriteToEditor,
                inputScheme = mergedInputScheme,
                fixedCandidateOrder = mergedFixedOrder,
                predictionEnabled = rootObj.optBoolean("predictionEnabled")
                    ?: existingSettings.predictionEnabled,
                aiCompletionEnabled = rootObj.optBoolean("aiCompletionEnabled")
                    ?: existingSettings.aiCompletionEnabled,
                jevCopilotEnabled = rootObj.optBoolean("jevCopilotEnabled")
                    ?: existingSettings.jevCopilotEnabled,
                jevUseDedicatedModel = rootObj.optBoolean("jevUseDedicatedModel")
                    ?: existingSettings.jevUseDedicatedModel,
                jevLlmConfig = mergedJevLlm,
                chatContextSendScreenshot = rootObj.optBoolean("chatContextSendScreenshot")
                    ?: existingSettings.chatContextSendScreenshot,
                themePreference = themePref,
                appAwareToneEnabled = rootObj.optBoolean("appAwareToneEnabled")
                    ?: existingSettings.appAwareToneEnabled,
                selectedManualStyleId = rootObj.optString("selectedManualStyleId")
                    ?.takeIf { it.isNotBlank() } ?: existingSettings.selectedManualStyleId,
                hapticFeedback = rootObj.optBoolean("hapticFeedback")
                    ?: existingSettings.hapticFeedback,
                interactionSounds = rootObj.optBoolean("interactionSounds")
                    ?: existingSettings.interactionSounds,
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

            Result.success(
                candidate.copy(providerVault = UnifiedProviderCatalog.hydrateVaultFromSettings(candidate))
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
}
