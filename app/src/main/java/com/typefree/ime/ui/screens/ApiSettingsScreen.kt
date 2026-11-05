package com.typefree.ime.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.typefree.ime.data.ApiPresets
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.AppToneRule
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.DictionarySource
import com.typefree.ime.data.DiscoveredModelItem
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.ModelCapabilitySlot
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.ThemePreference
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.EndpointResolver
import com.typefree.ime.network.PipelineExecutionResult
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
        return current.copy(
            presetId = preset.id,
            baseUrl = preset.baseUrl,
            model = preset.defaultModel,
            apiKey = current.apiKey
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
     * Automatically selects matching LLM model when switching to SiliconFlow, Groq, or OpenAI.
     */
    fun reuseSttCredentialsForLlm(stt: SttConfig, llm: LlmConfig): LlmConfig {
        val sttUrl = stt.baseUrl.trim()
        val sttLower = sttUrl.lowercase()
        val llmLower = llm.baseUrl.trim().lowercase()

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

    fun sttModelSuggestions(presetId: String): List<String> {
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
            "deepseek" -> listOf("deepseek-chat", "deepseek-reasoner")
            "siliconflow_llm" -> listOf(
                "Qwen/Qwen2.5-7B-Instruct",
                "Qwen/Qwen2.5-32B-Instruct",
                "deepseek-ai/DeepSeek-V3"
            )
            "groq_llm" -> listOf(
                "llama-3.3-70b-versatile",
                "llama-3.1-8b-instant"
            )
            "zhipu" -> listOf("glm-4-flash", "glm-4-air", "glm-4-plus")
            "doubao" -> listOf("doubao-1-5-lite-32k-250115", "doubao-1-5-pro-32k-250115")
            "ollama" -> listOf("qwen2.5:7b", "qwen2.5:14b", "llama3.2:3b")
            else -> listOf("gpt-4o-mini", "gemini-3.5-flash-lite", "gemini-3.8-flash", "deepseek-chat")
        }
    }

    fun multimodalModelSuggestions(presetId: String, protocol: MultimodalProtocol): List<String> {
        return when {
            presetId == "gemini" || presetId == "newapi_gemini" || protocol == MultimodalProtocol.GEMINI_NATIVE -> listOf(
                "gemini-3.5-flash-lite",
                "gemini-2.5-flash",
                "gemini-3.8-flash",
                "gemini-2.5-pro"
            )
            presetId == "openai_audio" -> listOf(
                "gpt-4o-mini-audio-preview",
                "gpt-4o-audio-preview"
            )
            else -> listOf(
                "google/gemini-3.5-flash-lite",
                "google/gemini-2.5-flash",
                "google/gemini-3.8-flash",
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
            settings.copy(
                sttConfig = settings.sttConfig.copy(
                    apiKey = if (settings.sttConfig.apiKey.isNotBlank()) REDACTED_SECRET else ""
                ),
                llmConfig = settings.llmConfig.copy(
                    apiKey = if (settings.llmConfig.apiKey.isNotBlank()) REDACTED_SECRET else ""
                ),
                multimodalConfig = settings.multimodalConfig.copy(
                    apiKey = if (settings.multimodalConfig.apiKey.isNotBlank()) REDACTED_SECRET else ""
                )
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
                existingSettings.sttConfig.copy(
                    presetId = sttObj.optString("presetId") ?: existingSettings.sttConfig.presetId,
                    baseUrl = sttObj.optString("baseUrl")?.trim() ?: existingSettings.sttConfig.baseUrl,
                    apiKey = resolveImportedSecret(importedKey, existingSettings.sttConfig.apiKey),
                    model = sttObj.optString("model")?.trim() ?: existingSettings.sttConfig.model,
                    language = sttObj.optString("language") ?: existingSettings.sttConfig.language
                )
            } else {
                existingSettings.sttConfig
            }

            val llmObj = rootObj.optObject("llmConfig")
            val mergedLlm = if (llmObj != null) {
                val importedKey = llmObj.optString("apiKey")
                existingSettings.llmConfig.copy(
                    enabled = llmObj.optBoolean("enabled") ?: existingSettings.llmConfig.enabled,
                    presetId = llmObj.optString("presetId") ?: existingSettings.llmConfig.presetId,
                    baseUrl = llmObj.optString("baseUrl")?.trim() ?: existingSettings.llmConfig.baseUrl,
                    apiKey = resolveImportedSecret(importedKey, existingSettings.llmConfig.apiKey),
                    model = llmObj.optString("model")?.trim() ?: existingSettings.llmConfig.model,
                    temperature = (llmObj.optFloat("temperature") ?: existingSettings.llmConfig.temperature)
                        .coerceIn(0.0f, 1.0f)
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
                existingSettings.multimodalConfig.copy(
                    presetId = mmObj.optString("presetId") ?: existingSettings.multimodalConfig.presetId,
                    protocol = importedProto ?: existingSettings.multimodalConfig.protocol,
                    baseUrl = mmObj.optString("baseUrl")?.trim() ?: existingSettings.multimodalConfig.baseUrl,
                    apiKey = resolveImportedSecret(importedKey, existingSettings.multimodalConfig.apiKey),
                    model = mmObj.optString("model")?.trim() ?: existingSettings.multimodalConfig.model,
                    temperature = (mmObj.optFloat("temperature") ?: existingSettings.multimodalConfig.temperature)
                        .coerceIn(0.0f, 1.0f)
                )
            } else {
                existingSettings.multimodalConfig
            }

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

            Result.success(
                existingSettings.copy(
                    pipelineMode = mode,
                    sttConfig = mergedStt,
                    llmConfig = mergedLlm,
                    multimodalConfig = mergedMm,
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

    var reuseFeedback by remember { mutableStateOf<String?>(null) }

    // Live API Tester state
    var testInput by remember { mutableStateOf(DEFAULT_LIVE_TEST_INPUT) }
    var isTestingApi by remember { mutableStateOf(false) }
    var testSuccessResult by remember { mutableStateOf<PipelineExecutionResult?>(null) }
    var testErrorMessage by remember { mutableStateOf<String?>(null) }
    var copiedTestResult by remember { mutableStateOf(false) }

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
            ModelCapabilitySlot.LLM -> Triple(
                settings.llmConfig.baseUrl,
                settings.llmConfig.apiKey,
                MultimodalProtocol.OPENAI_AUDIO_CHAT
            )
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
        val (activeBaseUrl, currentModel) = when (activeSlot) {
            ModelCapabilitySlot.STT -> settings.sttConfig.baseUrl to settings.sttConfig.model
            ModelCapabilitySlot.LLM -> settings.llmConfig.baseUrl to settings.llmConfig.model
            ModelCapabilitySlot.MULTIMODAL -> settings.multimodalConfig.baseUrl to settings.multimodalConfig.model
        }
        ProviderModelPickerDialog(
            slot = activeSlot,
            baseUrl = activeBaseUrl,
            currentModel = currentModel,
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
                    text = "自定义 API 工作台",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "完全自托管密钥与模型端点，兼容硅基流动 / DeepSeek / Groq / Gemini / OpenAI / Ollama",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Top Readiness Banner Pill
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
                            "待填写 API Key · ${SetupStatsHelper.formatActivePipelineBadge(settings)}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "直连无中转",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 1. Pipeline Mode Segmented Selector
        PipelineModeSelectorCard(
            selectedMode = settings.pipelineMode,
            onSelectMode = { mode ->
                repository.updateSettings { it.copy(pipelineMode = mode) }
                testSuccessResult = null
                testErrorMessage = null
            }
        )

        // 2. Dual-Stage Configuration OR 3. Multimodal Direct Configuration
        when (settings.pipelineMode) {
            PipelineMode.DUAL_STAGE -> {
                SttConfigurationCard(
                    sttConfig = settings.sttConfig,
                    isFetchingModels = fetchingSlot == ModelCapabilitySlot.STT,
                    modelFetchError = modelFetchErrors[ModelCapabilitySlot.STT],
                    onFetchModels = { triggerModelDiscovery(ModelCapabilitySlot.STT) },
                    onUpdateStt = { updatedStt ->
                        repository.updateSettings { it.copy(sttConfig = updatedStt) }
                    },
                    onPasteClipboard = { readFromClipboard(context) }
                )

                LlmConfigurationCard(
                    llmConfig = settings.llmConfig,
                    reuseFeedback = reuseFeedback,
                    isFetchingModels = fetchingSlot == ModelCapabilitySlot.LLM,
                    modelFetchError = modelFetchErrors[ModelCapabilitySlot.LLM],
                    onFetchModels = { triggerModelDiscovery(ModelCapabilitySlot.LLM) },
                    onUpdateLlm = { updatedLlm ->
                        reuseFeedback = null
                        repository.updateSettings { it.copy(llmConfig = updatedLlm) }
                    },
                    onReuseSttCredentials = {
                        val synced = ApiConfigBackupHelper.reuseSttCredentialsForLlm(
                            stt = settings.sttConfig,
                            llm = settings.llmConfig
                        )
                        repository.updateSettings { it.copy(llmConfig = synced) }
                        reuseFeedback = "✓ 已同步 STT 的 Base URL 与 API Key（模型：${synced.model}）"
                    },
                    onPasteClipboard = { readFromClipboard(context) }
                )
            }

            PipelineMode.MULTIMODAL_DIRECT -> {
                MultimodalConfigurationCard(
                    mmConfig = settings.multimodalConfig,
                    isFetchingModels = fetchingSlot == ModelCapabilitySlot.MULTIMODAL,
                    modelFetchError = modelFetchErrors[ModelCapabilitySlot.MULTIMODAL],
                    onFetchModels = { triggerModelDiscovery(ModelCapabilitySlot.MULTIMODAL) },
                    onUpdateMultimodal = { updatedMm ->
                        repository.updateSettings { it.copy(multimodalConfig = updatedMm) }
                    },
                    onPasteClipboard = { readFromClipboard(context) }
                )
            }
        }

        // 4. Live API Connectivity & Polish Tester Card
        val (activeStyle, _) = remember(settings) {
            repository.resolveActiveStyle(context.packageName)
        }
        val activeDictCount = remember(settings.dictionary) {
            settings.dictionary.count { it.enabled && it.term.isNotBlank() }
        }

        LiveApiTesterCard(
            testInput = testInput,
            onTestInputChange = { testInput = it },
            onResetDefaultInput = { testInput = DEFAULT_LIVE_TEST_INPUT },
            activeStyleLabel = "${activeStyle.emoji} ${activeStyle.name}",
            activeDictionaryCount = activeDictCount,
            isTesting = isTestingApi,
            successResult = testSuccessResult,
            errorMessage = testErrorMessage,
            copiedResult = copiedTestResult,
            isDualStageLlmDisabled = settings.pipelineMode == PipelineMode.DUAL_STAGE && !settings.llmConfig.enabled,
            onRunTest = {
                if (testInput.isBlank()) {
                    testErrorMessage = "请先输入一段待润色的测试文字"
                    testSuccessResult = null
                    return@LiveApiTesterCard
                }
                coroutineScope.launch {
                    isTestingApi = true
                    testErrorMessage = null
                    testSuccessResult = null
                    copiedTestResult = false

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
            }
        )

        // 5. JSON Config Import / Export Backup Card
        ConfigBackupCard(
            statusMessage = backupStatusMsg,
            statusIsError = backupIsError,
            onExportFullJson = {
                val json = ApiConfigBackupHelper.exportSettingsJson(settings, includeSecrets = true)
                writeToClipboard(context, "TypeFree Config Backup", json)
                backupIsError = false
                backupStatusMsg = "✓ 完整配置 JSON 已复制到剪贴板（包含 API Key，请妥善保管）"
            },
            onExportRedactedJson = {
                val json = ApiConfigBackupHelper.exportSettingsJson(settings, includeSecrets = false)
                writeToClipboard(context, "TypeFree Config Share", json)
                backupIsError = false
                backupStatusMsg = "✓ 脱敏配置 JSON 已复制到剪贴板（已隐藏 API Key，可安全分享）"
            },
            onImportFromClipboard = {
                val clipText = readFromClipboard(context)
                ApiConfigBackupHelper.parseAndMergeConfigJson(clipText, settings)
                    .onSuccess { merged ->
                        repository.updateSettings { merged }
                        backupIsError = false
                        backupStatusMsg = "✓ 配置 JSON 导入成功！已应用接口、风格、翻译目标与词典设置"
                    }
                    .onFailure { err ->
                        backupIsError = true
                        backupStatusMsg = "✕ ${err.message ?: "导入配置失败"}"
                    }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun PipelineModeSelectorCard(
    selectedMode: PipelineMode,
    onSelectMode: (PipelineMode) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "语音推理管线架构",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            // Monochrome segmented capsule switcher
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
                        title = "双阶段 STT + LLM",
                        selected = selectedMode == PipelineMode.DUAL_STAGE,
                        onClick = { onSelectMode(PipelineMode.DUAL_STAGE) },
                        modifier = Modifier.weight(1f)
                    )
                    PipelineSegmentPill(
                        title = "多模态原生直连",
                        selected = selectedMode == PipelineMode.MULTIMODAL_DIRECT,
                        onClick = { onSelectMode(PipelineMode.MULTIMODAL_DIRECT) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text(
                text = when (selectedMode) {
                    PipelineMode.DUAL_STAGE ->
                        "国内直连低延迟首选：阶段 1 极速语音转文字（如硅基流动 SenseVoice / Groq Whisper），阶段 2 大模型去口癖与语义排版（如 DeepSeek / Qwen / GLM）。"
                    PipelineMode.MULTIMODAL_DIRECT ->
                        "端到端原生听音理解：将 16kHz WAV 音频一步直传多模态大模型（如 Google Gemini 2.5 Flash / GPT-4o Audio），单次请求完成识别、润色与快译。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SttConfigurationCard(
    sttConfig: SttConfig,
    isFetchingModels: Boolean,
    modelFetchError: String?,
    onFetchModels: () -> Unit,
    onUpdateStt: (SttConfig) -> Unit,
    onPasteClipboard: () -> String
) {
    val resolvedEndpoint = remember(sttConfig.baseUrl) {
        EndpointResolver.resolveSttEndpoint(sttConfig.baseUrl)
    }
    val modelSuggestions = remember(sttConfig.presetId) {
        ApiConfigBackupHelper.sttModelSuggestions(sttConfig.presetId)
    }

    val languageOptions = listOf(
        "" to "自动识别",
        "zh" to "中文 (zh)",
        "en" to "英文 (en)",
        "ja" to "日文 (ja)"
    )

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "阶段 1 · STT 语音转写引擎",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "标准 OpenAI /v1/audio/transcriptions 兼容接口",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            PresetHorizontalRow(
                presets = ApiPresets.sttPresets,
                selectedPresetId = sttConfig.presetId,
                onSelectPreset = { preset ->
                    onUpdateStt(ApiConfigBackupHelper.applyPresetToStt(sttConfig, preset))
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SyncedOutlinedField(
                    externalValue = sttConfig.baseUrl,
                    onValueCommit = { onUpdateStt(sttConfig.copy(baseUrl = it)) },
                    label = "STT Base URL",
                    placeholder = "https://api.siliconflow.cn/v1"
                )
                EndpointPreviewCaption(endpoint = resolvedEndpoint)
            }

            SecretApiKeyField(
                externalKey = sttConfig.apiKey,
                onKeyCommit = { onUpdateStt(sttConfig.copy(apiKey = it)) },
                label = "STT API Key",
                isLocalUrl = AiPipelineEngine.isLocalEndpoint(sttConfig.baseUrl),
                onPasteClipboard = onPasteClipboard
            )

            ProviderModelFieldSection(
                externalModel = sttConfig.model,
                onModelCommit = { onUpdateStt(sttConfig.copy(model = it)) },
                label = "识别模型名称 (Model)",
                placeholder = "FunAudioLLM/SenseVoiceSmall",
                suggestions = modelSuggestions,
                isFetching = isFetchingModels,
                fetchError = modelFetchError,
                onFetchModels = onFetchModels
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "识别语言偏好",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    languageOptions.forEach { (code, label) ->
                        val selected = sttConfig.language == code
                        FilterChip(
                            selected = selected,
                            onClick = { onUpdateStt(sttConfig.copy(language = code)) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
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
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlmConfigurationCard(
    llmConfig: LlmConfig,
    reuseFeedback: String?,
    isFetchingModels: Boolean,
    modelFetchError: String?,
    onFetchModels: () -> Unit,
    onUpdateLlm: (LlmConfig) -> Unit,
    onReuseSttCredentials: () -> Unit,
    onPasteClipboard: () -> String
) {
    val resolvedEndpoint = remember(llmConfig.baseUrl) {
        EndpointResolver.resolveChatEndpoint(llmConfig.baseUrl)
    }
    val modelSuggestions = remember(llmConfig.presetId) {
        ApiConfigBackupHelper.llmModelSuggestions(llmConfig.presetId)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.Sparkles,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "阶段 2 · LLM 语义提纯与排版",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (llmConfig.enabled) {
                                "自动去口癖、改口自纠、应用语气适配与快译"
                            } else {
                                "日常口述仅输出原始 STT（帮我写/编辑/快译仍会调用）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = llmConfig.enabled,
                    onCheckedChange = { onUpdateLlm(llmConfig.copy(enabled = it)) }
                )
            }

            FilledTonalButton(
                onClick = onReuseSttCredentials,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = TypeFreeIcons.Copy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "一键复用上方 STT 的 Base URL 与 API Key",
                    fontWeight = FontWeight.SemiBold
                )
            }

            AnimatedVisibility(visible = reuseFeedback != null) {
                reuseFeedback?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = TypeFreeEmerald
                    )
                }
            }

            PresetHorizontalRow(
                presets = ApiPresets.llmPresets,
                selectedPresetId = llmConfig.presetId,
                onSelectPreset = { preset ->
                    onUpdateLlm(ApiConfigBackupHelper.applyPresetToLlm(llmConfig, preset))
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SyncedOutlinedField(
                    externalValue = llmConfig.baseUrl,
                    onValueCommit = { onUpdateLlm(llmConfig.copy(baseUrl = it)) },
                    label = "LLM Base URL",
                    placeholder = "https://api.deepseek.com/v1"
                )
                EndpointPreviewCaption(endpoint = resolvedEndpoint)
            }

            SecretApiKeyField(
                externalKey = llmConfig.apiKey,
                onKeyCommit = { onUpdateLlm(llmConfig.copy(apiKey = it)) },
                label = "LLM API Key",
                isLocalUrl = AiPipelineEngine.isLocalEndpoint(llmConfig.baseUrl),
                onPasteClipboard = onPasteClipboard
            )

            ProviderModelFieldSection(
                externalModel = llmConfig.model,
                onModelCommit = { onUpdateLlm(llmConfig.copy(model = it)) },
                label = "润色模型名称 (Model)",
                placeholder = "deepseek-chat",
                suggestions = modelSuggestions,
                isFetching = isFetchingModels,
                fetchError = modelFetchError,
                onFetchModels = onFetchModels
            )

            TemperatureSliderSection(
                temperature = llmConfig.temperature,
                model = llmConfig.model,
                onTemperatureChange = { newTemp ->
                    onUpdateLlm(llmConfig.copy(temperature = newTemp))
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MultimodalConfigurationCard(
    mmConfig: MultimodalConfig,
    isFetchingModels: Boolean,
    modelFetchError: String?,
    onFetchModels: () -> Unit,
    onUpdateMultimodal: (MultimodalConfig) -> Unit,
    onPasteClipboard: () -> String
) {
    val endpointPreview = remember(mmConfig.baseUrl, mmConfig.model, mmConfig.protocol, mmConfig.apiKey) {
        ApiConfigBackupHelper.previewMultimodalEndpoint(mmConfig)
    }
    val modelSuggestions = remember(mmConfig.presetId, mmConfig.protocol) {
        ApiConfigBackupHelper.multimodalModelSuggestions(mmConfig.presetId, mmConfig.protocol)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.VoiceWaveform,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "多模态音频一步直出配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "16kHz WAV 原生音频直传，单次请求完成识别与润色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            PresetHorizontalRow(
                presets = ApiPresets.multimodalPresets,
                selectedPresetId = mmConfig.presetId,
                onSelectPreset = { preset ->
                    onUpdateMultimodal(ApiConfigBackupHelper.applyPresetToMultimodal(mmConfig, preset))
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "音频传输协议格式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = mmConfig.protocol == MultimodalProtocol.GEMINI_NATIVE,
                        onClick = {
                            onUpdateMultimodal(mmConfig.copy(protocol = MultimodalProtocol.GEMINI_NATIVE))
                        },
                        label = {
                            Text(
                                "Gemini 原生 (generateContent)",
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        leadingIcon = if (mmConfig.protocol == MultimodalProtocol.GEMINI_NATIVE) {
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
                        selected = mmConfig.protocol == MultimodalProtocol.OPENAI_AUDIO_CHAT,
                        onClick = {
                            onUpdateMultimodal(mmConfig.copy(protocol = MultimodalProtocol.OPENAI_AUDIO_CHAT))
                        },
                        label = {
                            Text(
                                "OpenAI 音频兼容 (input_audio)",
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        leadingIcon = if (mmConfig.protocol == MultimodalProtocol.OPENAI_AUDIO_CHAT) {
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

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SyncedOutlinedField(
                    externalValue = mmConfig.baseUrl,
                    onValueCommit = { onUpdateMultimodal(mmConfig.copy(baseUrl = it)) },
                    label = "Multimodal Base URL",
                    placeholder = "https://generativelanguage.googleapis.com/v1beta"
                )
                EndpointPreviewCaption(endpoint = endpointPreview)
            }

            SecretApiKeyField(
                externalKey = mmConfig.apiKey,
                onKeyCommit = { onUpdateMultimodal(mmConfig.copy(apiKey = it)) },
                label = "Multimodal API Key",
                isLocalUrl = AiPipelineEngine.isLocalEndpoint(mmConfig.baseUrl),
                onPasteClipboard = onPasteClipboard
            )

            ProviderModelFieldSection(
                externalModel = mmConfig.model,
                onModelCommit = { onUpdateMultimodal(mmConfig.copy(model = it)) },
                label = "多模态模型名称 (Model)",
                placeholder = "gemini-3.5-flash-lite",
                suggestions = modelSuggestions,
                isFetching = isFetchingModels,
                fetchError = modelFetchError,
                onFetchModels = onFetchModels
            )

            TemperatureSliderSection(
                temperature = mmConfig.temperature,
                model = mmConfig.model,
                onTemperatureChange = { newTemp ->
                    onUpdateMultimodal(mmConfig.copy(temperature = newTemp))
                }
            )
        }
    }
}

@Composable
private fun LiveApiTesterCard(
    testInput: String,
    onTestInputChange: (String) -> Unit,
    onResetDefaultInput: () -> Unit,
    activeStyleLabel: String,
    activeDictionaryCount: Int,
    isTesting: Boolean,
    successResult: PipelineExecutionResult?,
    errorMessage: String?,
    copiedResult: Boolean,
    isDualStageLlmDisabled: Boolean,
    onRunTest: () -> Unit,
    onCopyPolishedResult: (String) -> Unit
) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "真实连通性与润色效果实测",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "当前风格：$activeStyleLabel · 启用词典：$activeDictionaryCount 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (testInput != DEFAULT_LIVE_TEST_INPUT) {
                    TextButton(
                        onClick = onResetDefaultInput,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("重置示例", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (isDualStageLlmDisabled) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TypeFreeAmber.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, TypeFreeAmber.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "提示：上方 LLM 润色开关当前已关闭，此按钮仍会验证已填写的 LLM 接口连通性。",
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
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("正在请求自定义 API 验证连通性...", fontWeight = FontWeight.Bold)
                } else {
                    Text("一键发送真实连通性测试", fontWeight = FontWeight.Bold)
                }
            }

            AnimatedVisibility(visible = successResult != null) {
                successResult?.let { res ->
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
                                TextButton(
                                    onClick = { onCopyPolishedResult(res.finalOutput) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
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
                            Text(
                                text = "✕ 接口请求失败（诊断详情）",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = TypeFreeRose
                            )
                            Text(
                                text = errMsg,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = TypeFreeRose
                            )
                            Text(
                                text = "排查建议：请核对 Base URL、API Key 余额权限及模型名称（Model）拼写是否正确。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigBackupCard(
    statusMessage: String?,
    statusIsError: Boolean,
    onExportFullJson: () -> Unit,
    onExportRedactedJson: () -> Unit,
    onImportFromClipboard: () -> Unit
) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TypeFreeIcons.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "JSON 配置备份与跨机迁移",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "一键导入导出 API 端点、模型预设、翻译语言、语气规则与个人词典",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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
    }
}

@Composable
private fun PresetHorizontalRow(
    presets: List<ApiPreset>,
    selectedPresetId: String,
    onSelectPreset: (ApiPreset) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "国内外主流服务商一键填充（切换时自动保留已填 API Key）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            presets.forEach { preset ->
                val isSelected = preset.id == selectedPresetId
                Surface(
                    onClick = { onSelectPreset(preset) },
                    modifier = Modifier.width(196.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        Text(
                            text = preset.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

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
                Text(if (isLocalUrl) "局域网本地服务可留空" else "sk-...")
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
                    text = "正在从当前 Provider 拉取可用模型列表...",
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
                    text = "拉取当前服务商可用模型列表（弹窗点选）",
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
    currentModel: String,
    models: List<DiscoveredModelItem>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onSelectModel: (String) -> Unit
) {
    val recommendedCount = remember(models) { models.count { it.isRecommended } }
    var showRecommendedOnly by remember(models) {
        mutableStateOf(recommendedCount > 0 && models.size > 8)
    }
    var searchQuery by remember { mutableStateOf("") }

    val filteredModels = remember(models, showRecommendedOnly, searchQuery) {
        val q = searchQuery.trim().lowercase()
        models.filter { item ->
            val passFilter = !showRecommendedOnly || item.isRecommended || item.id == currentModel.trim()
            val passQuery = q.isEmpty() ||
                item.id.lowercase().contains(q) ||
                item.displayName.lowercase().contains(q) ||
                item.ownedBy.lowercase().contains(q)
            passFilter && passQuery
        }
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
                    text = "已从当前端点获取 ${models.size} 个模型 · 点击即选中",
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
                    placeholder = { Text("搜索模型 ID（如 gemini / flash / whisper）") },
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

                if (recommendedCount > 0) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = showRecommendedOnly,
                            onClick = { showRecommendedOnly = true },
                            label = {
                                Text(
                                    "✨ 推荐适配 ($recommendedCount)",
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
                                    "全部模型 (${models.size})",
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
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = item.id,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontFamily = FontFamily.Monospace
                                                ),
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        val subMeta = buildList {
                                            if (item.badge.isNotBlank()) add(item.badge)
                                            if (item.displayName.isNotBlank()) add(item.displayName)
                                            else if (item.ownedBy.isNotBlank()) add(item.ownedBy)
                                        }.joinToString(" · ")
                                        if (subMeta.isNotBlank()) {
                                            Text(
                                                text = subMeta,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (item.isRecommended) {
                                                    TypeFreeEmerald
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "当前已选",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
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
                        "检测到 ${model.trim()}：请求时将自动移除已废弃的 temperature 参数，并自动注入 thinkingLevel=$thinkingLevel 低延迟思考档位（若网关不支持将无感自动回退）。"
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
