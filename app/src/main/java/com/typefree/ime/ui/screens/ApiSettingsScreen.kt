package com.typefree.ime.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.typefree.ime.data.ApiPreset
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.AppToneRule
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.DictionarySource
import com.typefree.ime.data.DiscoveredModelItem
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.ModelCapabilitySlot
import com.typefree.ime.data.ModelProbeResult
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
import com.typefree.ime.data.UnifiedProviderSpec
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.EndpointResolver
import com.typefree.ime.network.PipelineExecutionResult
import com.typefree.ime.network.asr.VolcengineSaucAsrClient
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

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
        "liveWriteToEditor",
        "inputScheme",
        "fixedCandidateOrder",
        "predictionEnabled",
        "aiCompletionEnabled",
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
                liveWriteToEditor = rootObj.optBoolean("liveWriteToEditor")
                    ?: existingSettings.liveWriteToEditor,
                inputScheme = mergedInputScheme,
                fixedCandidateOrder = mergedFixedOrder,
                predictionEnabled = rootObj.optBoolean("predictionEnabled")
                    ?: existingSettings.predictionEnabled,
                aiCompletionEnabled = rootObj.optBoolean("aiCompletionEnabled")
                    ?: existingSettings.aiCompletionEnabled,
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

private const val DEFAULT_LIVE_TEST_INPUT =
    "嗯那个明天下午两点，哦不对改成三点半，跟王总对一下太普利斯API接口方案"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ApiSettingsScreen(
    repository: AppRepository,
    aiEngine: AiPipelineEngine,
    showScreenHeader: Boolean = true
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState()

    val hydratedVault = remember(settings) {
        UnifiedProviderCatalog.hydrateVaultFromSettings(settings)
    }

    val activePrimaryProviderId = remember(settings.pipelineMode, settings.sttConfig, settings.multimodalConfig) {
        if (settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT) {
            UnifiedProviderCatalog.inferMultimodalProviderId(settings.multimodalConfig)
        } else {
            UnifiedProviderCatalog.inferSttProviderId(settings.sttConfig)
        }
    }

    var selectedVaultProviderId by rememberSaveable {
        mutableStateOf(activePrimaryProviderId)
    }

    // Collapsible Advanced Drawers (collapsed by default for a clean, zero-clutter studio)
    var showAdvancedDrawer by rememberSaveable { mutableStateOf(false) }
    var showTesterDrawer by rememberSaveable { mutableStateOf(false) }
    var showBackupDrawer by rememberSaveable { mutableStateOf(false) }

    // Live API Tester state
    var testInput by remember { mutableStateOf(DEFAULT_LIVE_TEST_INPUT) }
    var isTestingApi by remember { mutableStateOf(false) }
    var testSuccessResult by remember { mutableStateOf<PipelineExecutionResult?>(null) }
    var testErrorMessage by remember { mutableStateOf<String?>(null) }
    var copiedTestResult by remember { mutableStateOf(false) }
    var copiedDiagLog by remember { mutableStateOf(false) }

    // Backup / Import status banner
    var backupStatusMsg by remember { mutableStateOf<String?>(null) }
    var backupIsError by remember { mutableStateOf(false) }

    // Universal Provider Model Discovery state (STT / LLM / Multimodal)
    var fetchingSlot by remember { mutableStateOf<ModelCapabilitySlot?>(null) }
    var modelFetchErrors by remember { mutableStateOf<Map<ModelCapabilitySlot, String>>(emptyMap()) }
    var pickerDialogSlot by remember { mutableStateOf<ModelCapabilitySlot?>(null) }
    var discoveredModels by remember { mutableStateOf<List<DiscoveredModelItem>>(emptyList()) }

    fun triggerModelDiscovery(slot: ModelCapabilitySlot) {
        val (baseUrl, apiKey, protocol) = when (slot) {
            ModelCapabilitySlot.STT -> Triple(
                settings.sttConfig.baseUrl,
                settings.sttConfig.apiKey,
                MultimodalProtocol.OPENAI_AUDIO_CHAT
            )
            ModelCapabilitySlot.LLM -> {
                val effKey = settings.llmConfig.apiKey.ifBlank {
                    if (settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS) settings.sttConfig.apiKey else ""
                }
                Triple(
                    settings.llmConfig.baseUrl,
                    effKey,
                    MultimodalProtocol.OPENAI_AUDIO_CHAT
                )
            }
            ModelCapabilitySlot.MULTIMODAL -> Triple(
                settings.multimodalConfig.baseUrl,
                settings.multimodalConfig.apiKey,
                settings.multimodalConfig.protocol
            )
        }
        coroutineScope.launch {
            fetchingSlot = slot
            modelFetchErrors = modelFetchErrors - slot
            val res = aiEngine.fetchAvailableModels(
                slot = slot,
                baseUrl = baseUrl,
                apiKey = apiKey,
                protocol = protocol
            )
            fetchingSlot = null
            res.onSuccess { list ->
                discoveredModels = list
                pickerDialogSlot = slot
            }.onFailure { err ->
                modelFetchErrors = modelFetchErrors + (slot to (err.message ?: "拉取模型列表失败"))
            }
        }
    }

    val isApiReady = remember(settings) { SetupStatsHelper.isApiConfigured(settings) }

    pickerDialogSlot?.let { activeSlot ->
        val activeBaseUrl: String
        val activeApiKey: String
        val currentModel: String
        val activeProtocol: MultimodalProtocol
        val activeThinkingMode: ThinkingMode
        when (activeSlot) {
            ModelCapabilitySlot.STT -> {
                activeBaseUrl = settings.sttConfig.baseUrl
                activeApiKey = settings.sttConfig.apiKey
                currentModel = settings.sttConfig.model
                activeProtocol = MultimodalProtocol.OPENAI_AUDIO_CHAT
                activeThinkingMode = ThinkingMode.DISABLED
            }
            ModelCapabilitySlot.LLM -> {
                activeBaseUrl = settings.llmConfig.baseUrl
                activeApiKey = settings.llmConfig.apiKey.ifBlank {
                    if (settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS) settings.sttConfig.apiKey else ""
                }
                currentModel = settings.llmConfig.model
                activeProtocol = MultimodalProtocol.OPENAI_AUDIO_CHAT
                activeThinkingMode = settings.llmConfig.thinkingMode
            }
            ModelCapabilitySlot.MULTIMODAL -> {
                activeBaseUrl = settings.multimodalConfig.baseUrl
                activeApiKey = settings.multimodalConfig.apiKey
                currentModel = settings.multimodalConfig.model
                activeProtocol = settings.multimodalConfig.protocol
                activeThinkingMode = settings.multimodalConfig.thinkingMode
            }
        }
        ProviderModelPickerDialog(
            slot = activeSlot,
            baseUrl = activeBaseUrl,
            apiKey = activeApiKey,
            currentModel = currentModel,
            protocol = activeProtocol,
            thinkingMode = activeThinkingMode,
            aiEngine = aiEngine,
            models = discoveredModels,
            isRefreshing = fetchingSlot == activeSlot,
            onRefresh = { triggerModelDiscovery(activeSlot) },
            onDismiss = { pickerDialogSlot = null },
            onSelectModel = { chosenId ->
                when (activeSlot) {
                    ModelCapabilitySlot.STT -> repository.updateSettings {
                        it.copy(sttConfig = it.sttConfig.copy(model = chosenId))
                    }
                    ModelCapabilitySlot.LLM -> repository.updateSettings {
                        it.copy(llmConfig = it.llmConfig.copy(model = chosenId))
                    }
                    ModelCapabilitySlot.MULTIMODAL -> repository.updateSettings {
                        it.copy(multimodalConfig = it.multimodalConfig.copy(model = chosenId))
                    }
                }
                pickerDialogSlot = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (showScreenHeader) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "统一 API 服务商与模型路由",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "同一服务商只需填写一次 Key，一键切换 Google Gemini 0.5s 流式、百炼、豆包或自由混搭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Top Readiness Summary Banner
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = if (isApiReady) {
                TypeFreeEmerald.copy(alpha = 0.08f)
            } else {
                TypeFreeAmber.copy(alpha = 0.10f)
            },
            border = BorderStroke(
                1.dp,
                if (isApiReady) TypeFreeEmerald.copy(alpha = 0.35f) else TypeFreeAmber.copy(alpha = 0.45f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isApiReady) TypeFreeEmerald else TypeFreeAmber)
                    )
                    Text(
                        text = if (isApiReady) {
                            "引擎就绪 · ${SetupStatsHelper.formatActivePipelineBadge(settings)}"
                        } else {
                            "待填密钥 · ${SetupStatsHelper.formatActivePipelineBadge(settings)}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "本地加密直连",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Layer 1: Unified Provider Vault Card (1 Key per Provider + 1-Tap Activate)
        UnifiedProviderVaultCard(
            vault = hydratedVault,
            selectedProviderId = selectedVaultProviderId,
            activePrimaryProviderId = activePrimaryProviderId,
            activeLlmProviderId = UnifiedProviderCatalog.inferLlmProviderId(settings.llmConfig),
            isLlmEnabled = settings.llmConfig.enabled,
            onSelectProvider = { selectedVaultProviderId = it },
            onUpdateCredential = { updatedCred ->
                repository.updateSettings { curr ->
                    UnifiedProviderCatalog.updateProviderCredential(curr, updatedCred)
                }
            },
            onApplyQuickSetup = { providerId ->
                repository.updateSettings { curr ->
                    UnifiedProviderCatalog.applyQuickProviderSetup(curr, providerId)
                }
                testSuccessResult = null
                testErrorMessage = null
                val spec = UnifiedProviderCatalog.specFor(providerId)
                Toast.makeText(
                    context,
                    if (providerId == UnifiedProviderCatalog.ID_DEEPSEEK) {
                        "✓ 已将 DeepSeek 设为二阶段语义润色引擎"
                    } else {
                        "🚀 已切换主力方案：${spec.name}"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            },
            onPasteClipboard = { readFromClipboard(context) }
        )

        // Layer 2: Clean Workflow & Role Model Routing Card
        UnifiedWorkflowRoutingCard(
            settings = settings,
            vault = hydratedVault,
            fetchingSlot = fetchingSlot,
            modelFetchErrors = modelFetchErrors,
            onSelectMode = { mode ->
                repository.updateSettings { it.copy(pipelineMode = mode) }
                testSuccessResult = null
                testErrorMessage = null
            },
            onSelectSttProvider = { pid ->
                selectedVaultProviderId = pid
                repository.updateSettings { UnifiedProviderCatalog.selectSttProvider(it, pid) }
            },
            onSelectLlmProvider = { pid ->
                selectedVaultProviderId = pid
                repository.updateSettings { UnifiedProviderCatalog.selectLlmProvider(it, pid) }
            },
            onSelectMultimodalProvider = { pid ->
                selectedVaultProviderId = pid
                repository.updateSettings { UnifiedProviderCatalog.selectMultimodalProvider(it, pid) }
            },
            onUpdateStt = { updated -> repository.updateSettings { it.copy(sttConfig = updated) } },
            onUpdateLlm = { updated -> repository.updateSettings { it.copy(llmConfig = updated) } },
            onUpdateMultimodal = { updated -> repository.updateSettings { it.copy(multimodalConfig = updated) } },
            onFetchModels = ::triggerModelDiscovery
        )

        // Layer 3A: Collapsible Advanced Tuning Drawer
        CollapsibleStudioDrawer(
            title = "⚙️ 高级参数微调",
            subtitle = "思考模式开关、采样温度、自定义反代地址与火山 Resource ID",
            expanded = showAdvancedDrawer,
            onToggle = { showAdvancedDrawer = !showAdvancedDrawer }
        ) {
            AdvancedParametersDrawerContent(
                settings = settings,
                onUpdateStt = { updated -> repository.updateSettings { it.copy(sttConfig = updated) } },
                onUpdateLlm = { updated -> repository.updateSettings { it.copy(llmConfig = updated) } },
                onUpdateMultimodal = { updated -> repository.updateSettings { it.copy(multimodalConfig = updated) } }
            )
        }

        // Layer 3B: Collapsible Live Connectivity & Polish Tester Drawer
        val (activeStyle, _) = remember(settings) {
            repository.resolveActiveStyle(context.packageName)
        }
        val activeDictCount = remember(settings.dictionary) {
            settings.dictionary.count { it.enabled && it.term.isNotBlank() }
        }

        CollapsibleStudioDrawer(
            title = "🩺 连通性实测与毫秒级诊断日志",
            subtitle = "模拟带口癖与改口的真实句子，一键验证首包与全链路耗时",
            expanded = showTesterDrawer,
            onToggle = { showTesterDrawer = !showTesterDrawer }
        ) {
            LiveApiTesterContent(
                testInput = testInput,
                onTestInputChange = { testInput = it },
                onResetDefaultInput = { testInput = DEFAULT_LIVE_TEST_INPUT },
                activeStyleLabel = "${activeStyle.emoji} ${activeStyle.name}",
                activeDictionaryCount = activeDictCount,
                isTesting = isTestingApi,
                successResult = testSuccessResult,
                errorMessage = testErrorMessage,
                copiedResult = copiedTestResult,
                copiedDiagLog = copiedDiagLog,
                isGeminiSmartSingleStage = settings.pipelineMode == PipelineMode.DUAL_STAGE &&
                    settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS &&
                    !settings.llmConfig.enabled,
                isDualStageLlmDisabled = settings.pipelineMode == PipelineMode.DUAL_STAGE &&
                    settings.sttConfig.protocol != SttProtocol.GEMINI_LIVE_WS &&
                    !settings.llmConfig.enabled,
                onRunTest = {
                    if (testInput.isBlank()) {
                        testErrorMessage = "请先输入一段待润色的测试文字"
                        testSuccessResult = null
                        return@LiveApiTesterContent
                    }
                    coroutineScope.launch {
                        isTestingApi = true
                        testErrorMessage = null
                        testSuccessResult = null
                        copiedTestResult = false
                        copiedDiagLog = false

                        val (styleNow, _) = repository.resolveActiveStyle(context.packageName)
                        val res = aiEngine.runTextTransform(
                            settings = settings,
                            activeStyle = styleNow,
                            rawOrSelectedText = testInput,
                            explicitInstruction = null,
                            targetAppName = "API 实测台"
                        )
                        isTestingApi = false
                        res.onSuccess { exec ->
                            testSuccessResult = exec
                        }.onFailure { err ->
                            testErrorMessage = err.message ?: "请求失败，请检查网络连接与接口地址"
                        }
                    }
                },
                onCopyPolishedResult = { text ->
                    writeToClipboard(context, "TypeFree Test Output", text)
                    copiedTestResult = true
                },
                onCopyDiagnosticLog = { diagText ->
                    writeToClipboard(context, "TypeFree API Diagnostic Log", diagText)
                    copiedDiagLog = true
                }
            )
        }

        // Layer 3C: Collapsible JSON Config Backup / Migration Drawer
        CollapsibleStudioDrawer(
            title = "📦 配置 JSON 备份与跨机迁移",
            subtitle = "一键导出完整或脱敏 JSON，跨设备恢复所有密钥、短语与词典",
            expanded = showBackupDrawer,
            onToggle = { showBackupDrawer = !showBackupDrawer }
        ) {
            ConfigBackupContent(
                statusMessage = backupStatusMsg,
                statusIsError = backupIsError,
                onExportFullJson = {
                    val json = ApiConfigBackupHelper.exportSettingsJson(settings, includeSecrets = true)
                    writeToClipboard(context, "TypeFree Config Backup", json)
                    backupIsError = false
                    backupStatusMsg = "✓ 完整配置 JSON 已复制到剪贴板（包含服务商密钥库，请妥善保管）"
                },
                onExportRedactedJson = {
                    val json = ApiConfigBackupHelper.exportSettingsJson(settings, includeSecrets = false)
                    writeToClipboard(context, "TypeFree Config Share", json)
                    backupIsError = false
                    backupStatusMsg = "✓ 脱敏配置 JSON 已复制到剪贴板（已隐藏所有 API Key，可安全分享）"
                },
                onImportFromClipboard = {
                    val clipText = readFromClipboard(context)
                    ApiConfigBackupHelper.parseAndMergeConfigJson(clipText, settings)
                        .onSuccess { merged ->
                            repository.updateSettings { merged }
                            backupIsError = false
                            backupStatusMsg = "✓ 配置 JSON 导入成功！已同步服务商密钥库、模型路由、短语与个人词典"
                        }
                        .onFailure { err ->
                            backupIsError = true
                            backupStatusMsg = "✕ ${err.message ?: "导入配置失败"}"
                        }
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

// =============================================================================
// Layer 1: Unified Provider Vault Card
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnifiedProviderVaultCard(
    vault: Map<String, ProviderCredential>,
    selectedProviderId: String,
    activePrimaryProviderId: String,
    activeLlmProviderId: String,
    isLlmEnabled: Boolean,
    onSelectProvider: (String) -> Unit,
    onUpdateCredential: (ProviderCredential) -> Unit,
    onApplyQuickSetup: (String) -> Unit,
    onPasteClipboard: () -> String
) {
    val selectedSpec = remember(selectedProviderId) {
        UnifiedProviderCatalog.specFor(selectedProviderId)
    }
    val selectedCred = vault[selectedSpec.id] ?: ProviderCredential(providerId = selectedSpec.id)
    val isSelectedReady = UnifiedProviderCatalog.isCredentialReady(selectedSpec.id, selectedCred)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "1. 服务商统一密钥库",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val readyCount = UnifiedProviderCatalog.providers.count { spec ->
                        UnifiedProviderCatalog.isCredentialReady(spec.id, vault[spec.id])
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "已填 $readyCount / ${UnifiedProviderCatalog.providers.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    text = "每个平台只需填一次 API Key，听音、润色、帮我写与快译自动共享复用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 6 Unified Provider Tiles (2 per row via FlowRow / Row chunks)
            UnifiedProviderCatalog.providers.chunked(2).forEach { rowSpecs ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowSpecs.forEach { spec ->
                        val cred = vault[spec.id]
                        val ready = UnifiedProviderCatalog.isCredentialReady(spec.id, cred)
                        val isSelected = spec.id == selectedSpec.id
                        val isActiveRole = spec.id == activePrimaryProviderId ||
                            (isLlmEnabled && spec.id == activeLlmProviderId)

                        ProviderVaultTile(
                            spec = spec,
                            isSelected = isSelected,
                            isReady = ready,
                            isActiveRole = isActiveRole,
                            onClick = { onSelectProvider(spec.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Selected Provider Single-Form Credential Drawer
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = selectedSpec.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelectedReady) {
                                TypeFreeEmerald.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            }
                        ) {
                            Text(
                                text = if (isSelectedReady) "✓ 密钥已就绪" else selectedSpec.badge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelectedReady) {
                                    TypeFreeEmerald
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = selectedSpec.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )

                    if (selectedSpec.id == UnifiedProviderCatalog.ID_CUSTOM) {
                        SyncedOutlinedField(
                            externalValue = selectedCred.customBaseUrl,
                            onValueCommit = { url ->
                                onUpdateCredential(selectedCred.copy(customBaseUrl = url.trim()))
                            },
                            label = "聚合网关 / 本地服务 Base URL",
                            placeholder = "https://api.openai.com/v1 或 http://192.168.1.10:11434/v1"
                        )
                    }

                    val primaryKeyLabel = when (selectedSpec.id) {
                        UnifiedProviderCatalog.ID_GOOGLE -> "Google AI Studio API Key (AIza...)"
                        UnifiedProviderCatalog.ID_ALIYUN -> "阿里云百炼 API Key (sk-...)"
                        UnifiedProviderCatalog.ID_VOLCENGINE -> "火山方舟 API Key（豆包 LLM 润色 / 单 Key）"
                        UnifiedProviderCatalog.ID_SILICONFLOW -> "硅基流动 API Key (sk-...)"
                        UnifiedProviderCatalog.ID_DEEPSEEK -> "DeepSeek API Key (sk-...)"
                        else -> "API Key"
                    }

                    SecretApiKeyField(
                        externalKey = selectedCred.apiKey,
                        onKeyCommit = { newKey ->
                            onUpdateCredential(selectedCred.copy(apiKey = newKey.trim()))
                        },
                        label = primaryKeyLabel,
                        isLocalUrl = selectedSpec.id == UnifiedProviderCatalog.ID_CUSTOM &&
                            AiPipelineEngine.isLocalEndpoint(selectedCred.customBaseUrl),
                        onPasteClipboard = onPasteClipboard
                    )

                    if (selectedSpec.hasSeparateSpeechToken) {
                        SecretApiKeyField(
                            externalKey = selectedCred.sttApiKey,
                            onKeyCommit = { tok ->
                                onUpdateCredential(selectedCred.copy(sttApiKey = tok.trim()))
                            },
                            label = "火山语音豆包流式 Access Token（与上方同 Key 可留空）",
                            isLocalUrl = false,
                            onPasteClipboard = onPasteClipboard
                        )
                        SyncedOutlinedField(
                            externalValue = selectedCred.appId,
                            onValueCommit = { app ->
                                onUpdateCredential(selectedCred.copy(appId = app.trim()))
                            },
                            label = "火山语音 AppID（新版单 X-Api-Key 可留空）",
                            placeholder = "选填，如 1234567890"
                        )
                    }

                    val isAlreadyPrimary = selectedSpec.id == activePrimaryProviderId &&
                        (selectedSpec.id != UnifiedProviderCatalog.ID_GOOGLE ||
                            !isLlmEnabled)

                    Button(
                        onClick = { onApplyQuickSetup(selectedSpec.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAlreadyPrimary) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            contentColor = if (isAlreadyPrimary) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            }
                        )
                    ) {
                        val btnText = when {
                            selectedSpec.id == UnifiedProviderCatalog.ID_DEEPSEEK ->
                                "🧠 一键设为二阶段润色引擎"
                            isAlreadyPrimary ->
                                "✓ 当前已是主力方案（点击重置为官方推荐模型）"
                            else ->
                                "🚀 一键设为当前主力方案"
                        }
                        Text(
                            text = btnText,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderVaultTile(
    spec: UnifiedProviderSpec,
    isSelected: Boolean,
    isReady: Boolean,
    isActiveRole: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        },
        border = BorderStroke(
            width = if (isSelected) 1.6.dp else 1.dp,
            color = when {
                isSelected -> MaterialTheme.colorScheme.primary
                isActiveRole -> TypeFreeEmerald.copy(alpha = 0.55f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = spec.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (isReady) {
                                TypeFreeEmerald
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                            }
                        )
                )
            }
            Text(
                text = if (isActiveRole) "正在使用 · ${spec.badge}" else spec.badge,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActiveRole) {
                    TypeFreeEmerald
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// =============================================================================
// Layer 2: Unified Workflow & Role Model Routing Card
// =============================================================================

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun UnifiedWorkflowRoutingCard(
    settings: AppSettings,
    vault: Map<String, ProviderCredential>,
    fetchingSlot: ModelCapabilitySlot?,
    modelFetchErrors: Map<ModelCapabilitySlot, String>,
    onSelectMode: (PipelineMode) -> Unit,
    onSelectSttProvider: (String) -> Unit,
    onSelectLlmProvider: (String) -> Unit,
    onSelectMultimodalProvider: (String) -> Unit,
    onUpdateStt: (SttConfig) -> Unit,
    onUpdateLlm: (LlmConfig) -> Unit,
    onUpdateMultimodal: (MultimodalConfig) -> Unit,
    onFetchModels: (ModelCapabilitySlot) -> Unit
) {
    val activeSttPid = remember(settings.sttConfig) {
        UnifiedProviderCatalog.inferSttProviderId(settings.sttConfig)
    }
    val activeLlmPid = remember(settings.llmConfig) {
        UnifiedProviderCatalog.inferLlmProviderId(settings.llmConfig)
    }
    val activeMmPid = remember(settings.multimodalConfig) {
        UnifiedProviderCatalog.inferMultimodalProviderId(settings.multimodalConfig)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "2. 工作流与模型自由混搭",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "无需手动填写底层 WS/HTTP 协议地址，点选服务商即可自动挂载对应密钥",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Clean 2-Segment Mode Switcher
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PipelineSegmentPill(
                        title = "⚡ 实时流式 / 双阶段",
                        selected = settings.pipelineMode == PipelineMode.DUAL_STAGE,
                        onClick = { onSelectMode(PipelineMode.DUAL_STAGE) },
                        modifier = Modifier.weight(1f)
                    )
                    PipelineSegmentPill(
                        title = "🎙️ 多模态整包直出",
                        selected = settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT,
                        onClick = { onSelectMode(PipelineMode.MULTIMODAL_DIRECT) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> {
                    // Role A: Speech Listening Engine
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "👂 听音转写引擎（边说边传 / 声学热词）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )

                            val sttProviders = remember {
                                UnifiedProviderCatalog.providers.filter { it.supportsStt }
                            }
                            RoleProviderChipsRow(
                                specs = sttProviders,
                                selectedId = activeSttPid,
                                vault = vault,
                                onSelect = onSelectSttProvider
                            )

                            if (settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = TypeFreeEmerald.copy(alpha = 0.10f),
                                    border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.42f))
                                ) {
                                    Text(
                                        text = "✨ Gemini 3.5 Live SMART 已激活：边说边实时出字，松手 ~0.5s 秒出净稿，原生自带去口癖与改口自纠！日常口述无需开启二阶段润色；使用「帮我写 / 改写 / 快译 / ==问AI」时会自动共享同一把 Gemini Key 调用 Flash-Lite。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 16.sp,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }

                            val sttSuggestions = UnifiedProviderCatalog.sttSuggestedModelsByProvider[activeSttPid]
                                ?: ApiConfigBackupHelper.sttModelSuggestions(
                                    settings.sttConfig.presetId,
                                    settings.sttConfig.protocol
                                )

                            if (settings.sttConfig.protocol == SttProtocol.OPENAI_HTTP) {
                                ProviderModelFieldSection(
                                    externalModel = settings.sttConfig.model,
                                    onModelCommit = { m -> onUpdateStt(settings.sttConfig.copy(model = m)) },
                                    label = "听音模型名称 (Model)",
                                    placeholder = "FunAudioLLM/SenseVoiceSmall",
                                    suggestions = sttSuggestions,
                                    isFetching = fetchingSlot == ModelCapabilitySlot.STT,
                                    fetchError = modelFetchErrors[ModelCapabilitySlot.STT],
                                    onFetchModels = { onFetchModels(ModelCapabilitySlot.STT) }
                                )
                            } else {
                                SyncedOutlinedField(
                                    externalValue = settings.sttConfig.model,
                                    onValueCommit = { m -> onUpdateStt(settings.sttConfig.copy(model = m)) },
                                    label = "流式听音模型 (Model)",
                                    placeholder = sttSuggestions.firstOrNull().orEmpty()
                                )
                                QuickModelChipsRow(
                                    suggestions = sttSuggestions,
                                    currentModel = settings.sttConfig.model,
                                    onSelectModel = { m -> onUpdateStt(settings.sttConfig.copy(model = m)) }
                                )
                            }
                        }
                    }

                    // Role B: Optional 2nd-Stage LLM Polish & Generative Engine
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = "🧠 二阶段语义润色 & 写作快译模型",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = when {
                                            settings.llmConfig.enabled ->
                                                "日常口述也会经过该模型按应用语气深度润色"
                                            settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS ->
                                                "日常口述由 Gemini Live 单阶直出；帮我写/快译自动用下方模型"
                                            else ->
                                                "日常口述直出原始识别词；仅帮我写/编辑/快译调用下方模型"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = settings.llmConfig.enabled,
                                    onCheckedChange = { enabled ->
                                        onUpdateLlm(settings.llmConfig.copy(enabled = enabled))
                                    }
                                )
                            }

                            val llmProviders = remember {
                                UnifiedProviderCatalog.providers.filter { it.supportsLlm }
                            }
                            RoleProviderChipsRow(
                                specs = llmProviders,
                                selectedId = activeLlmPid,
                                vault = vault,
                                onSelect = onSelectLlmProvider
                            )

                            val llmSuggestions = UnifiedProviderCatalog.llmSuggestedModelsByProvider[activeLlmPid]
                                ?: ApiConfigBackupHelper.llmModelSuggestions(settings.llmConfig.presetId)

                            ProviderModelFieldSection(
                                externalModel = settings.llmConfig.model,
                                onModelCommit = { m -> onUpdateLlm(settings.llmConfig.copy(model = m)) },
                                label = "润色 / 写作 / 翻译模型 (Model)",
                                placeholder = "gemini-3.5-flash-lite",
                                suggestions = llmSuggestions,
                                isFetching = fetchingSlot == ModelCapabilitySlot.LLM,
                                fetchError = modelFetchErrors[ModelCapabilitySlot.LLM],
                                onFetchModels = { onFetchModels(ModelCapabilitySlot.LLM) }
                            )
                        }
                    }
                }

                PipelineMode.MULTIMODAL_DIRECT -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "🎙️ 多模态音频一步直出（已启用 .m4a 硬件极速压缩）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "录音结束后硬件压缩体积缩小约 87%，单次请求完成听音理解、语气适配与翻译（注：若追求松手 0.5s 秒出体验，推荐切回上方「实时流式」使用 Gemini Live）。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )

                            val mmProviders = remember {
                                UnifiedProviderCatalog.providers.filter { it.supportsMultimodal }
                            }
                            RoleProviderChipsRow(
                                specs = mmProviders,
                                selectedId = activeMmPid,
                                vault = vault,
                                onSelect = onSelectMultimodalProvider
                            )

                            val mmSuggestions = UnifiedProviderCatalog.multimodalSuggestedModelsByProvider[activeMmPid]
                                ?: ApiConfigBackupHelper.multimodalModelSuggestions(
                                    settings.multimodalConfig.presetId,
                                    settings.multimodalConfig.protocol
                                )

                            ProviderModelFieldSection(
                                externalModel = settings.multimodalConfig.model,
                                onModelCommit = { m ->
                                    onUpdateMultimodal(settings.multimodalConfig.copy(model = m))
                                },
                                label = "多模态音频模型 (Model)",
                                placeholder = "gemini-3.5-flash-lite",
                                suggestions = mmSuggestions,
                                isFetching = fetchingSlot == ModelCapabilitySlot.MULTIMODAL,
                                fetchError = modelFetchErrors[ModelCapabilitySlot.MULTIMODAL],
                                onFetchModels = { onFetchModels(ModelCapabilitySlot.MULTIMODAL) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RoleProviderChipsRow(
    specs: List<UnifiedProviderSpec>,
    selectedId: String,
    vault: Map<String, ProviderCredential>,
    onSelect: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        specs.forEach { spec ->
            val selected = spec.id == selectedId
            val ready = UnifiedProviderCatalog.isCredentialReady(spec.id, vault[spec.id])
            FilterChip(
                selected = selected,
                onClick = { onSelect(spec.id) },
                label = {
                    Text(
                        text = if (ready) "● ${spec.name}" else spec.name,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

// =============================================================================
// Layer 3: Collapsible Studio Drawers (Advanced / Diagnostics / JSON Backup)
// =============================================================================

@Composable
private fun CollapsibleStudioDrawer(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.50f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedParametersDrawerContent(
    settings: AppSettings,
    onUpdateStt: (SttConfig) -> Unit,
    onUpdateLlm: (LlmConfig) -> Unit,
    onUpdateMultimodal: (MultimodalConfig) -> Unit
) {
    val languageOptions = listOf(
        "" to "自动识别",
        "zh" to "中文 (zh)",
        "en" to "英文 (en)",
        "ja" to "日文 (ja)"
    )

    if (settings.pipelineMode == PipelineMode.DUAL_STAGE) {
        val sttEndpoint = remember(settings.sttConfig.baseUrl, settings.sttConfig.protocol) {
            when (settings.sttConfig.protocol) {
                SttProtocol.OPENAI_HTTP -> EndpointResolver.resolveSttEndpoint(settings.sttConfig.baseUrl)
                SttProtocol.ALIYUN_DASHSCOPE_WS -> EndpointResolver.resolveDashScopeWsEndpoint(settings.sttConfig.baseUrl)
                SttProtocol.VOLCENGINE_SAUC_WS -> EndpointResolver.resolveVolcengineSaucWsEndpoint(settings.sttConfig.baseUrl)
                SttProtocol.GEMINI_LIVE_WS -> EndpointResolver.resolveGeminiLiveWsEndpoint(
                    settings.sttConfig.baseUrl,
                    if (settings.sttConfig.apiKey.isBlank()) "YOUR_API_KEY" else "***"
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SyncedOutlinedField(
                externalValue = settings.sttConfig.baseUrl,
                onValueCommit = { onUpdateStt(settings.sttConfig.copy(baseUrl = it.trim())) },
                label = "听音引擎地址覆盖（反代 / 自建网关可在此修改）",
                placeholder = "https://generativelanguage.googleapis.com/v1beta"
            )
            EndpointPreviewCaption(endpoint = sttEndpoint)
        }

        if (settings.sttConfig.protocol == SttProtocol.VOLCENGINE_SAUC_WS) {
            SyncedOutlinedField(
                externalValue = settings.sttConfig.resourceId,
                onValueCommit = { onUpdateStt(settings.sttConfig.copy(resourceId = it.trim())) },
                label = "火山豆包语音 Resource ID",
                placeholder = VolcengineSaucAsrClient.DEFAULT_RESOURCE_ID
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "听音语言偏好",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                languageOptions.forEach { (code, label) ->
                    val selected = settings.sttConfig.language == code
                    FilterChip(
                        selected = selected,
                        onClick = { onUpdateStt(settings.sttConfig.copy(language = code)) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SyncedOutlinedField(
                externalValue = settings.llmConfig.baseUrl,
                onValueCommit = { onUpdateLlm(settings.llmConfig.copy(baseUrl = it.trim())) },
                label = "LLM 润色/写作端点覆盖",
                placeholder = "https://generativelanguage.googleapis.com/v1beta"
            )
            EndpointPreviewCaption(
                endpoint = if (EndpointResolver.isGeminiNativeHttpUrl(settings.llmConfig.baseUrl) ||
                    settings.llmConfig.presetId == "gemini_llm"
                ) {
                    EndpointResolver.resolveGeminiEndpoint(
                        settings.llmConfig.baseUrl,
                        settings.llmConfig.model,
                        "***"
                    )
                } else {
                    EndpointResolver.resolveChatEndpoint(settings.llmConfig.baseUrl)
                }
            )
        }

        ThinkingModeSelectorSection(
            thinkingMode = settings.llmConfig.thinkingMode,
            onSelectMode = { mode -> onUpdateLlm(settings.llmConfig.copy(thinkingMode = mode)) }
        )

        TemperatureSliderSection(
            temperature = settings.llmConfig.temperature,
            model = settings.llmConfig.model,
            onTemperatureChange = { temp -> onUpdateLlm(settings.llmConfig.copy(temperature = temp)) }
        )
    } else {
        val mmPreview = remember(
            settings.multimodalConfig.baseUrl,
            settings.multimodalConfig.model,
            settings.multimodalConfig.protocol,
            settings.multimodalConfig.apiKey
        ) {
            ApiConfigBackupHelper.previewMultimodalEndpoint(settings.multimodalConfig)
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "多模态音频协议格式",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = settings.multimodalConfig.protocol == MultimodalProtocol.GEMINI_NATIVE,
                    onClick = {
                        onUpdateMultimodal(
                            settings.multimodalConfig.copy(protocol = MultimodalProtocol.GEMINI_NATIVE)
                        )
                    },
                    label = { Text("Gemini 原生 (generateContent)") }
                )
                FilterChip(
                    selected = settings.multimodalConfig.protocol == MultimodalProtocol.OPENAI_AUDIO_CHAT,
                    onClick = {
                        onUpdateMultimodal(
                            settings.multimodalConfig.copy(protocol = MultimodalProtocol.OPENAI_AUDIO_CHAT)
                        )
                    },
                    label = { Text("OpenAI 音频兼容 (input_audio)") }
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SyncedOutlinedField(
                externalValue = settings.multimodalConfig.baseUrl,
                onValueCommit = {
                    onUpdateMultimodal(settings.multimodalConfig.copy(baseUrl = it.trim()))
                },
                label = "多模态网关地址覆盖",
                placeholder = "https://generativelanguage.googleapis.com/v1beta"
            )
            EndpointPreviewCaption(endpoint = mmPreview)
        }

        ThinkingModeSelectorSection(
            thinkingMode = settings.multimodalConfig.thinkingMode,
            onSelectMode = { mode ->
                onUpdateMultimodal(settings.multimodalConfig.copy(thinkingMode = mode))
            }
        )

        TemperatureSliderSection(
            temperature = settings.multimodalConfig.temperature,
            model = settings.multimodalConfig.model,
            onTemperatureChange = { temp ->
                onUpdateMultimodal(settings.multimodalConfig.copy(temperature = temp))
            }
        )
    }
}

@Composable
private fun PipelineSegmentPill(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp
    ) {
        Box(
            modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LiveApiTesterContent(
    testInput: String,
    onTestInputChange: (String) -> Unit,
    onResetDefaultInput: () -> Unit,
    activeStyleLabel: String,
    activeDictionaryCount: Int,
    isTesting: Boolean,
    successResult: PipelineExecutionResult?,
    errorMessage: String?,
    copiedResult: Boolean,
    copiedDiagLog: Boolean,
    isGeminiSmartSingleStage: Boolean,
    isDualStageLlmDisabled: Boolean,
    onRunTest: () -> Unit,
    onCopyPolishedResult: (String) -> Unit,
    onCopyDiagnosticLog: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "当前风格：$activeStyleLabel · 启用词典：$activeDictionaryCount 条",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (testInput != DEFAULT_LIVE_TEST_INPUT) {
            TextButton(
                onClick = onResetDefaultInput,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("重置示例", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (isGeminiSmartSingleStage) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = TypeFreeEmerald.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.4f))
        ) {
            Text(
                text = "当前处于 Gemini Live SMART 单阶听写模式；点击下方按钮将验证同把 Gemini Key 驱动的写作/快译引擎连通性与响应延迟。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(10.dp)
            )
        }
    } else if (isDualStageLlmDisabled) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = TypeFreeAmber.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, TypeFreeAmber.copy(alpha = 0.4f))
        ) {
            Text(
                text = "提示：二阶段润色开关当前已关闭，此按钮仍会验证已配置的写作/快译模型连通性。",
                style = MaterialTheme.typography.bodySmall,
                color = TypeFreeAmber,
                modifier = Modifier.padding(10.dp)
            )
        }
    }

    OutlinedTextField(
        value = testInput,
        onValueChange = onTestInputChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 4,
        shape = RoundedCornerShape(12.dp),
        label = { Text("模拟带口癖、改口与专有名词的口述原稿") }
    )

    Button(
        onClick = onRunTest,
        enabled = !isTesting,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (isTesting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text("正在请求接口实测连通性...", fontWeight = FontWeight.Bold)
        } else {
            Text("一键发送真实连通性测试", fontWeight = FontWeight.Bold)
        }
    }

    AnimatedVisibility(visible = successResult != null) {
        successResult?.let { res ->
            val fullDiagnosticClipboard = buildString {
                appendLine("TypeFree API 连通性诊断日志")
                appendLine("总耗时: ${res.latencyMs} ms")
                appendLine("响应模型: ${res.modeSummary}")
                if (res.diagnosticLog.isNotBlank()) {
                    appendLine(res.diagnosticLog)
                }
                append("输出结果: ${res.finalOutput}")
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = TypeFreeEmerald.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.50f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✓ 接口连通成功 · ${res.latencyMs} ms",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = TypeFreeEmerald
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { onCopyDiagnosticLog(fullDiagnosticClipboard) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = if (copiedDiagLog) "✓ 已复制日志" else "复制日志",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { onCopyPolishedResult(res.finalOutput) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    imageVector = TypeFreeIcons.Copy,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = TypeFreeEmerald
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (copiedResult) "已复制" else "复制",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TypeFreeEmerald
                                )
                            }
                        }
                    }
                    Text(
                        text = "响应模型：${res.modeSummary}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = res.finalOutput,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (res.diagnosticLog.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = res.diagnosticLog,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    AnimatedVisibility(visible = errorMessage != null) {
        errorMessage?.let { errMsg ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = TypeFreeRose.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, TypeFreeRose.copy(alpha = 0.50f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✕ 接口请求失败（诊断详情）",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = TypeFreeRose
                        )
                        TextButton(
                            onClick = { onCopyDiagnosticLog(errMsg) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = if (copiedDiagLog) "✓ 已复制日志" else "复制日志",
                                style = MaterialTheme.typography.labelSmall,
                                color = TypeFreeRose
                            )
                        }
                    }
                    Text(
                        text = errMsg,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = TypeFreeRose
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigBackupContent(
    statusMessage: String?,
    statusIsError: Boolean,
    onExportFullJson: () -> Unit,
    onExportRedactedJson: () -> Unit,
    onImportFromClipboard: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(
            onClick = onExportFullJson,
            shape = RoundedCornerShape(11.dp)
        ) {
            Icon(
                imageVector = TypeFreeIcons.Copy,
                contentDescription = null,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("导出完整配置 JSON")
        }

        OutlinedButton(
            onClick = onExportRedactedJson,
            shape = RoundedCornerShape(11.dp)
        ) {
            Text("脱敏导出 (隐藏 Key)")
        }

        OutlinedButton(
            onClick = onImportFromClipboard,
            shape = RoundedCornerShape(11.dp)
        ) {
            Text("从剪贴板导入 JSON")
        }
    }

    AnimatedVisibility(visible = statusMessage != null) {
        statusMessage?.let { msg ->
            val statusColor = if (statusIsError) TypeFreeRose else TypeFreeEmerald
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = statusColor.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.40f))
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

// =============================================================================
// Shared Reusable Field & Model Discovery Components
// =============================================================================

@Composable
private fun SyncedOutlinedField(
    externalValue: String,
    onValueCommit: (String) -> Unit,
    label: String,
    placeholder: String
) {
    var localText by remember { mutableStateOf(externalValue) }
    LaunchedEffect(externalValue) {
        if (localText != externalValue) {
            localText = externalValue
        }
    }

    OutlinedTextField(
        value = localText,
        onValueChange = { updated ->
            localText = updated
            onValueCommit(updated)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        label = { Text(label) },
        placeholder = { Text(placeholder) }
    )
}

@Composable
private fun SecretApiKeyField(
    externalKey: String,
    onKeyCommit: (String) -> Unit,
    label: String,
    isLocalUrl: Boolean,
    onPasteClipboard: () -> String
) {
    var localKey by remember { mutableStateOf(externalKey) }
    var keyVisible by remember { mutableStateOf(false) }

    LaunchedEffect(externalKey) {
        if (localKey != externalKey) {
            localKey = externalKey
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = localKey,
            onValueChange = { updated ->
                localKey = updated
                onKeyCommit(updated)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            label = { Text(label) },
            placeholder = {
                Text(if (isLocalUrl) "局域网本地服务可留空" else "粘贴或输入密钥…")
            },
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            imageVector = if (keyVisible) TypeFreeIcons.EyeOff else TypeFreeIcons.Eye,
                            contentDescription = if (keyVisible) "隐藏密钥" else "显示密钥",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    TextButton(
                        onClick = {
                            val pasted = onPasteClipboard().trim()
                            if (pasted.isNotEmpty()) {
                                localKey = pasted
                                onKeyCommit(pasted)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("粘贴", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        )
        if (isLocalUrl && localKey.isBlank()) {
            Text(
                text = "已识别局域网 / 本地推理端点，无需填写云端 API Key 即可直接请求",
                style = MaterialTheme.typography.labelSmall,
                color = TypeFreeEmerald
            )
        }
    }
}

@Composable
private fun EndpointPreviewCaption(endpoint: String) {
    Text(
        text = "实际请求端点: $endpoint",
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun ProviderModelFieldSection(
    externalModel: String,
    onModelCommit: (String) -> Unit,
    label: String,
    placeholder: String,
    suggestions: List<String>,
    isFetching: Boolean,
    fetchError: String?,
    onFetchModels: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SyncedOutlinedField(
            externalValue = externalModel,
            onValueCommit = onModelCommit,
            label = label,
            placeholder = placeholder
        )

        FilledTonalButton(
            onClick = onFetchModels,
            enabled = !isFetching,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
        ) {
            if (isFetching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "正在拉取可用模型列表...",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "拉取可用模型并实测延迟 (ms)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        AnimatedVisibility(visible = !fetchError.isNullOrBlank()) {
            fetchError?.let { err ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = TypeFreeRose.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, TypeFreeRose.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "✕ $err",
                        style = MaterialTheme.typography.labelSmall,
                        color = TypeFreeRose,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
        }

        QuickModelChipsRow(
            suggestions = suggestions,
            currentModel = externalModel,
            onSelectModel = onModelCommit
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProviderModelPickerDialog(
    slot: ModelCapabilitySlot,
    baseUrl: String,
    apiKey: String,
    currentModel: String,
    protocol: MultimodalProtocol,
    thinkingMode: ThinkingMode,
    aiEngine: AiPipelineEngine,
    models: List<DiscoveredModelItem>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onSelectModel: (String) -> Unit
) {
    val dialogScope = rememberCoroutineScope()
    val recommendedCount = remember(models) { models.count { it.isRecommended } }
    val hasDistinctFilter = recommendedCount in 1 until models.size
    var showRecommendedOnly by remember(models) {
        mutableStateOf(hasDistinctFilter && models.size > 6)
    }
    var searchQuery by remember { mutableStateOf("") }

    var probeResults by remember(models, thinkingMode, baseUrl) {
        mutableStateOf<Map<String, ModelProbeResult>>(emptyMap())
    }
    var probingIds by remember(models, thinkingMode, baseUrl) {
        mutableStateOf<Set<String>>(emptySet())
    }

    val canAutoProbe = remember(apiKey, baseUrl) {
        apiKey.isNotBlank() || AiPipelineEngine.isLocalEndpoint(baseUrl)
    }

    fun launchBatchProbe(targetIds: List<String>) {
        val cleanIds = targetIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanIds.isEmpty()) return
        probingIds = probingIds + cleanIds
        dialogScope.launch {
            try {
                aiEngine.probeModelsBatch(
                    slot = slot,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    modelIds = cleanIds,
                    protocol = protocol,
                    thinkingMode = thinkingMode,
                    maxConcurrency = 3,
                    onEachResult = { res ->
                        dialogScope.launch {
                            probeResults = probeResults + (res.modelId to res)
                            probingIds = probingIds - res.modelId
                        }
                    }
                )
            } finally {
                probingIds = probingIds - cleanIds.toSet()
            }
        }
    }

    LaunchedEffect(models, thinkingMode, baseUrl, canAutoProbe) {
        if (!canAutoProbe || models.isEmpty()) return@LaunchedEffect
        val trimmedCurrent = currentModel.trim()
        val initialPool = models.filter { !showRecommendedOnly || it.isRecommended || it.id == trimmedCurrent }
            .ifEmpty { models }
        val autoTargets = buildList {
            if (trimmedCurrent.isNotEmpty() && models.any { it.id == trimmedCurrent }) {
                add(trimmedCurrent)
            }
            initialPool.forEach { item ->
                if (size < 8 && item.id !in this) {
                    add(item.id)
                }
            }
        }
        launchBatchProbe(autoTargets)
    }

    val filteredModels = remember(models, showRecommendedOnly, searchQuery, probeResults) {
        val q = searchQuery.trim().lowercase()
        val baseFiltered = models.filter { item ->
            val passFilter = !showRecommendedOnly || item.isRecommended || item.id == currentModel.trim()
            val passQuery = q.isEmpty() ||
                item.id.lowercase().contains(q) ||
                item.displayName.lowercase().contains(q) ||
                item.ownedBy.lowercase().contains(q)
            passFilter && passQuery
        }
        baseFiltered.sortedWith(
            compareBy<DiscoveredModelItem> { item ->
                val res = probeResults[item.id]
                when {
                    res?.latencyMs != null -> 0
                    res == null -> 1
                    else -> 2
                }
            }.thenBy { item ->
                probeResults[item.id]?.latencyMs ?: Long.MAX_VALUE
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "选择可用模型 · ${slot.label}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (slot == ModelCapabilitySlot.STT) {
                        "共 ${models.size} 个模型 · 实测短音频耗时并按延迟从低到高排序"
                    } else {
                        "共 ${models.size} 个模型 · 实测 1-Token 首包延迟（${thinkingMode.label}）自动排最前"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("搜索模型 ID（如 gemini / seed / qwen）") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "清空搜索",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else null
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (hasDistinctFilter) {
                        FilterChip(
                            selected = showRecommendedOnly,
                            onClick = { showRecommendedOnly = true },
                            label = {
                                Text(
                                    "适配模型 ($recommendedCount)",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            leadingIcon = if (showRecommendedOnly) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                        FilterChip(
                            selected = !showRecommendedOnly,
                            onClick = { showRecommendedOnly = false },
                            label = {
                                Text(
                                    "全部 (${models.size})",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            leadingIcon = if (!showRecommendedOnly) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }

                    FilterChip(
                        selected = probingIds.isNotEmpty(),
                        onClick = {
                            if (probingIds.isEmpty()) {
                                launchBatchProbe(filteredModels.take(12).map { it.id })
                            }
                        },
                        enabled = filteredModels.isNotEmpty() && probingIds.isEmpty(),
                        label = {
                            Text(
                                text = if (probingIds.isNotEmpty()) {
                                    "⚡ 测速中 (${probingIds.size})..."
                                } else {
                                    "⚡ 测速当前列表"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                if (filteredModels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "未找到匹配的模型",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (showRecommendedOnly) {
                                TextButton(onClick = { showRecommendedOnly = false }) {
                                    Text("查看全部 ${models.size} 个模型")
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredModels, key = { it.id }) { item ->
                            val isSelected = item.id == currentModel.trim()
                            val probeResult = probeResults[item.id]
                            val isProbingItem = item.id in probingIds

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectModel(item.id) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                },
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = if (isSelected) "✓ ${item.id}" else item.id,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val subMeta = buildList {
                                            if (item.badge.isNotBlank()) add(item.badge)
                                            if (item.displayName.isNotBlank()) add(item.displayName)
                                            else if (item.ownedBy.isNotBlank()) add(item.ownedBy)
                                        }.joinToString(" · ")
                                        if (subMeta.isNotBlank()) {
                                            Text(
                                                text = subMeta,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    ModelLatencyBadge(
                                        probeResult = probeResult,
                                        isProbing = isProbingItem,
                                        onTriggerProbe = {
                                            if (!isProbingItem) {
                                                launchBatchProbe(listOf(item.id))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onRefresh,
                enabled = !isRefreshing
            ) {
                Text(if (isRefreshing) "刷新中..." else "重新拉取")
            }
        }
    )
}

@Composable
private fun ModelLatencyBadge(
    probeResult: ModelProbeResult?,
    isProbing: Boolean,
    onTriggerProbe: () -> Unit
) {
    val latency = probeResult?.latencyMs
    val error = probeResult?.errorSummary

    val (bgColor, borderColor, textColor, labelText) = when {
        isProbing -> Quad(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            MaterialTheme.colorScheme.onSurfaceVariant,
            "测速中"
        )
        latency != null && latency < 400L -> Quad(
            TypeFreeEmerald.copy(alpha = 0.14f),
            TypeFreeEmerald.copy(alpha = 0.50f),
            TypeFreeEmerald,
            "⚡ $latency ms"
        )
        latency != null && latency <= 900L -> Quad(
            TypeFreeAmber.copy(alpha = 0.14f),
            TypeFreeAmber.copy(alpha = 0.50f),
            TypeFreeAmber,
            "$latency ms"
        )
        latency != null -> Quad(
            TypeFreeRose.copy(alpha = 0.12f),
            TypeFreeRose.copy(alpha = 0.45f),
            TypeFreeRose,
            "$latency ms"
        )
        error != null -> Quad(
            TypeFreeRose.copy(alpha = 0.10f),
            TypeFreeRose.copy(alpha = 0.40f),
            TypeFreeRose,
            error
        )
        else -> Quad(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            MaterialTheme.colorScheme.primary,
            "⚡ 测速"
        )
    }

    Surface(
        onClick = onTriggerProbe,
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isProbing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.6.dp,
                    color = textColor
                )
            }
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThinkingModeSelectorSection(
    thinkingMode: ThinkingMode,
    onSelectMode: (ThinkingMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "深度思考开关 (Thinking / CoT)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ThinkingMode.entries.forEach { mode ->
                val selected = thinkingMode == mode
                FilterChip(
                    selected = selected,
                    onClick = { onSelectMode(mode) },
                    label = {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
        Text(
            text = "${thinkingMode.description}（若所选模型不支持该参数，将无感自动回退并记忆）",
            style = MaterialTheme.typography.labelSmall,
            color = if (thinkingMode == ThinkingMode.DISABLED) {
                TypeFreeEmerald
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            lineHeight = 16.sp
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickModelChipsRow(
    suggestions: List<String>,
    currentModel: String,
    onSelectModel: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        suggestions.forEach { modelName ->
            val isCurrent = currentModel.trim() == modelName
            SuggestionChip(
                onClick = { onSelectModel(modelName) },
                label = {
                    Text(
                        text = if (isCurrent) "✓ $modelName" else modelName,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            )
        }
    }
}

@Composable
private fun TemperatureSliderSection(
    temperature: Float,
    model: String = "",
    onTemperatureChange: (Float) -> Unit
) {
    val isTempDeprecated = remember(model) {
        AiPipelineEngine.isSamplingTemperatureDeprecated(model)
    }
    val thinkingLevel = remember(model) {
        AiPipelineEngine.recommendedGeminiThinkingLevel(model)
    }

    if (isTempDeprecated) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = TypeFreeEmerald.copy(alpha = 0.09f),
            border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.38f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "✓ 已启用最新推理模型参数自适应",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TypeFreeEmerald
                )
                Text(
                    text = if (thinkingLevel != null) {
                        "检测到 ${model.trim()}：请求时将自动移除已废弃的 temperature 参数，并适配思考档位控制（若网关不支持将无感自动回退）。"
                    } else {
                        "检测到 ${model.trim()}：已按官方最新规范自动移除废弃的 temperature 采样参数，确保直连与聚合网关零报错极速响应。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "采样温度 (Temperature)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = String.format(Locale.US, "%.2f", temperature),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = temperature.coerceIn(0.0f, 1.0f),
                onValueChange = { raw ->
                    val stepped = (raw * 20f).roundToInt() / 20f
                    onTemperatureChange(stepped.coerceIn(0.0f, 1.0f))
                },
                valueRange = 0.0f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
            Text(
                text = "建议保持在 0.10 ~ 0.30，数值越低越忠实还原口述原意，避免模型自由发挥",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun readFromClipboard(context: Context): String {
    return try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        } else {
            ""
        }
    } catch (_: Exception) {
        ""
    }
}

private fun writeToClipboard(context: Context, label: String, text: String) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    } catch (_: Exception) {
    }
}
