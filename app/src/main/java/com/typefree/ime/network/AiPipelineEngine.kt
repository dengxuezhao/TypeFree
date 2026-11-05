package com.typefree.ime.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DiscoveredModelItem
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.ModelCapabilitySlot
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class PipelineStage {
    TRANSCRIBING_STT,
    POLISHING_LLM,
    MULTIMODAL_PROCESSING
}

data class PipelineExecutionResult(
    val rawTranscript: String,
    val finalOutput: String,
    val latencyMs: Long,
    val modeSummary: String
)

class AiPipelineEngine(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    /**
     * Full voice pipeline execution: supports both Dual-Stage (STT + LLM)
     * and Direct Multimodal (Gemini Native / OpenAI Audio Chat).
     */
    suspend fun processVoiceAudio(
        wavFile: File,
        settings: AppSettings,
        activeStyle: ToneStyle,
        targetAppName: String,
        selectedText: String,
        surroundingBeforeCursor: String,
        intentMode: VoiceIntentMode = if (selectedText.isNotBlank()) VoiceIntentMode.SPEAK_TO_EDIT else VoiceIntentMode.DICTATE,
        translateTargetLanguage: String = settings.activeTranslateTarget,
        onStageChanged: (PipelineStage) -> Unit = {}
    ): Result<PipelineExecutionResult> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val effectiveIntent = if (intentMode == VoiceIntentMode.SPEAK_TO_EDIT && selectedText.isBlank()) {
            VoiceIntentMode.DICTATE
        } else {
            intentMode
        }
        val requiresGenerativeLlm = effectiveIntent != VoiceIntentMode.DICTATE
        val safeContext = if (settings.useSurroundingContext) surroundingBeforeCursor else ""

        try {
            when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> {
                    val sttCfg = settings.sttConfig
                    if (sttCfg.baseUrl.isBlank()) {
                        return@withContext Result.failure(IllegalStateException("请先在「API 配置」中填写语音识别 (STT) 接口地址"))
                    }
                    if (sttCfg.apiKey.isBlank() && !isLocalEndpoint(sttCfg.baseUrl)) {
                        return@withContext Result.failure(IllegalStateException("请先在「API 配置」中填写 STT API Key"))
                    }

                    onStageChanged(PipelineStage.TRANSCRIBING_STT)
                    val sttBias = PromptBuilder.buildSttBiasPrompt(settings.dictionary)
                    val rawTranscript = transcribeWithOpenAiStt(
                        wavFile = wavFile,
                        config = sttCfg,
                        biasPrompt = sttBias
                    )

                    if (rawTranscript.isBlank() || isHallucinatedSilence(rawTranscript)) {
                        return@withContext Result.failure(IllegalArgumentException("未识别到清晰语音内容，请重试"))
                    }

                    val llmCfg = settings.llmConfig
                    // If Speak-to-Edit / Help-me-write / Translate is active, LLM MUST run
                    if (!llmCfg.enabled && !requiresGenerativeLlm) {
                        val elapsed = System.currentTimeMillis() - startMs
                        return@withContext Result.success(
                            PipelineExecutionResult(
                                rawTranscript = rawTranscript,
                                finalOutput = rawTranscript,
                                latencyMs = elapsed,
                                modeSummary = "仅 STT (${sttCfg.model})"
                            )
                        )
                    }

                    if (llmCfg.apiKey.isBlank() && !isLocalEndpoint(llmCfg.baseUrl)) {
                        return@withContext Result.failure(IllegalStateException("请先在「API 配置」中填写 LLM 润色 API Key（或关闭 LLM 润色开关）"))
                    }

                    onStageChanged(PipelineStage.POLISHING_LLM)
                    val (sysPrompt, usrPrompt) = when (effectiveIntent) {
                        VoiceIntentMode.SPEAK_TO_EDIT -> PromptBuilder.buildSpeakToEditPrompts(
                            selectedText = selectedText,
                            voiceInstruction = rawTranscript,
                            style = activeStyle,
                            dictionary = settings.dictionary
                        )
                        VoiceIntentMode.HELP_ME_WRITE -> PromptBuilder.buildHelpMeWritePrompts(
                            voiceInstruction = rawTranscript,
                            style = activeStyle,
                            targetAppName = targetAppName,
                            dictionary = settings.dictionary,
                            surroundingBeforeCursor = safeContext
                        )
                        VoiceIntentMode.TRANSLATE -> PromptBuilder.buildTranslatePrompts(
                            rawSpeech = rawTranscript,
                            targetLanguage = translateTargetLanguage,
                            style = activeStyle,
                            dictionary = settings.dictionary
                        )
                        VoiceIntentMode.DICTATE -> PromptBuilder.buildDictationPrompts(
                            rawSpeech = rawTranscript,
                            style = activeStyle,
                            targetAppName = targetAppName,
                            dictionary = settings.dictionary,
                            surroundingBeforeCursor = safeContext
                        )
                    }

                    val polished = callOpenAiChatCompletion(
                        config = llmCfg,
                        systemPrompt = sysPrompt,
                        userPrompt = usrPrompt
                    )
                    val elapsed = System.currentTimeMillis() - startMs
                    Result.success(
                        PipelineExecutionResult(
                            rawTranscript = rawTranscript,
                            finalOutput = polished.ifBlank { rawTranscript },
                            latencyMs = elapsed,
                            modeSummary = "STT + ${llmCfg.model}"
                        )
                    )
                }

                PipelineMode.MULTIMODAL_DIRECT -> {
                    val mmCfg = settings.multimodalConfig
                    if (mmCfg.apiKey.isBlank() && !isLocalEndpoint(mmCfg.baseUrl)) {
                        return@withContext Result.failure(IllegalStateException("请先在「API 配置」中填写多模态模型 API Key"))
                    }

                    onStageChanged(PipelineStage.MULTIMODAL_PROCESSING)
                    val prompt = PromptBuilder.buildMultimodalDirectPrompt(
                        intentMode = effectiveIntent,
                        selectedText = selectedText,
                        translateTargetLanguage = translateTargetLanguage,
                        style = activeStyle,
                        targetAppName = targetAppName,
                        dictionary = settings.dictionary,
                        surroundingBeforeCursor = safeContext
                    )
                    val audioBytes = wavFile.readBytes()
                    val base64Audio = java.util.Base64.getEncoder().encodeToString(audioBytes)

                    val output = when (mmCfg.protocol) {
                        MultimodalProtocol.GEMINI_NATIVE -> callGeminiMultimodalAudio(
                            config = mmCfg,
                            prompt = prompt,
                            base64Wav = base64Audio
                        )
                        MultimodalProtocol.OPENAI_AUDIO_CHAT -> callOpenAiMultimodalAudio(
                            config = mmCfg,
                            prompt = prompt,
                            base64Wav = base64Audio
                        )
                    }

                    if (output.isBlank() || output.contains("[EMPTY_SPEECH]")) {
                        return@withContext Result.failure(IllegalArgumentException("未识别到清晰人声，请重试"))
                    }

                    val elapsed = System.currentTimeMillis() - startMs
                    val rawSummary = when (effectiveIntent) {
                        VoiceIntentMode.SPEAK_TO_EDIT -> "[多模态语音改写指令]"
                        VoiceIntentMode.HELP_ME_WRITE -> "[多模态帮我写需求]"
                        VoiceIntentMode.TRANSLATE -> "[多模态直译 → $translateTargetLanguage]"
                        VoiceIntentMode.DICTATE -> "[多模态音频一步直出]"
                    }
                    Result.success(
                        PipelineExecutionResult(
                            rawTranscript = rawSummary,
                            finalOutput = output,
                            latencyMs = elapsed,
                            modeSummary = "多模态 (${mmCfg.model})"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Quick text-only Speak-to-Edit or Re-polish (used by 1-tap keyboard edit chips,
     * History re-polish, and API Settings live connectivity tester!).
     */
    suspend fun runTextTransform(
        settings: AppSettings,
        activeStyle: ToneStyle,
        rawOrSelectedText: String,
        explicitInstruction: String? = null,
        targetAppName: String = ""
    ): Result<PipelineExecutionResult> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            val (sysPrompt, usrPrompt) = if (!explicitInstruction.isNullOrBlank()) {
                PromptBuilder.buildSpeakToEditPrompts(
                    selectedText = rawOrSelectedText,
                    voiceInstruction = explicitInstruction,
                    style = activeStyle,
                    dictionary = settings.dictionary
                )
            } else {
                PromptBuilder.buildDictationPrompts(
                    rawSpeech = rawOrSelectedText,
                    style = activeStyle,
                    targetAppName = targetAppName,
                    dictionary = settings.dictionary,
                    surroundingBeforeCursor = ""
                )
            }

            val output: String
            val modelLabel: String

            when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> {
                    val llmCfg = settings.llmConfig
                    if (llmCfg.apiKey.isBlank() && !isLocalEndpoint(llmCfg.baseUrl)) {
                        return@withContext Result.failure(IllegalStateException("请先填写 LLM API Key"))
                    }
                    output = callOpenAiChatCompletion(llmCfg, sysPrompt, usrPrompt)
                    modelLabel = llmCfg.model
                }
                PipelineMode.MULTIMODAL_DIRECT -> {
                    val mmCfg = settings.multimodalConfig
                    if (mmCfg.apiKey.isBlank() && !isLocalEndpoint(mmCfg.baseUrl)) {
                        return@withContext Result.failure(IllegalStateException("请先填写多模态 API Key"))
                    }
                    output = when (mmCfg.protocol) {
                        MultimodalProtocol.GEMINI_NATIVE -> callGeminiTextOnly(
                            config = mmCfg,
                            fullPrompt = "$sysPrompt\n\n$usrPrompt"
                        )
                        MultimodalProtocol.OPENAI_AUDIO_CHAT -> callOpenAiChatCompletion(
                            config = LlmConfig(
                                baseUrl = mmCfg.baseUrl,
                                apiKey = mmCfg.apiKey,
                                model = mmCfg.model,
                                temperature = mmCfg.temperature
                            ),
                            systemPrompt = sysPrompt,
                            userPrompt = usrPrompt
                        )
                    }
                    modelLabel = mmCfg.model
                }
            }

            val elapsed = System.currentTimeMillis() - startMs
            Result.success(
                PipelineExecutionResult(
                    rawTranscript = rawOrSelectedText,
                    finalOutput = output,
                    latencyMs = elapsed,
                    modeSummary = modelLabel
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Universal Provider Model Discovery:
     * Fetches available models from any OpenAI-compatible provider (/v1/models),
     * official Google Gemini (/v1beta/models), NewAPI / OneAPI gateways (with automatic
     * /v1beta/models -> /v1/models fallback), or local Ollama (/v1/models -> /api/tags).
     */
    suspend fun fetchAvailableModels(
        slot: ModelCapabilitySlot,
        baseUrl: String,
        apiKey: String,
        protocol: MultimodalProtocol = MultimodalProtocol.OPENAI_AUDIO_CHAT
    ): Result<List<DiscoveredModelItem>> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = baseUrl.trim()
            val cleanKey = apiKey.trim()
            if (cleanBase.isEmpty()) {
                throw IllegalArgumentException("请先填写 Base URL")
            }
            if (cleanKey.isEmpty() && !isLocalEndpoint(cleanBase)) {
                throw IllegalArgumentException("请先填写 API Key 后再拉取可用模型")
            }

            val models = if (slot == ModelCapabilitySlot.MULTIMODAL && protocol == MultimodalProtocol.GEMINI_NATIVE) {
                fetchGeminiOrGatewayModels(cleanBase, cleanKey, slot)
            } else {
                fetchOpenAiOrLocalModels(cleanBase, cleanKey, slot)
            }

            if (models.isEmpty()) {
                throw IOException("服务端返回模型列表为空，请检查 API Key 权限或渠道配置")
            }
            Result.success(models)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchGeminiOrGatewayModels(
        baseUrl: String,
        apiKey: String,
        slot: ModelCapabilitySlot
    ): List<DiscoveredModelItem> {
        val geminiUrl = EndpointResolver.resolveGeminiModelsEndpoint(baseUrl, apiKey)
        val isOfficialGoogle = baseUrl.lowercase().contains("googleapis.com")
        val primaryAttempt = runCatching {
            val reqBuilder = Request.Builder()
                .url(geminiUrl)
                .get()
            if (apiKey.isNotEmpty()) {
                reqBuilder.header("x-goog-api-key", apiKey)
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val respText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("拉取模型失败 (${resp.code}): ${extractErrorDetail(respText)}")
                }
                parseDiscoveredModelsResponse(respText, slot)
            }
        }

        val primaryList = primaryAttempt.getOrNull()
        if (!primaryList.isNullOrEmpty()) {
            return primaryList
        }

        // Automatic fallback for third-party gateways (NewAPI / OneAPI / LiteLLM) that expose /v1/models
        if (!isOfficialGoogle) {
            val fallbackUrl = EndpointResolver.resolveGatewayOpenAiModelsFallback(baseUrl)
            val fallbackAttempt = runCatching {
                val reqBuilder = Request.Builder()
                    .url(fallbackUrl)
                    .get()
                if (apiKey.isNotEmpty()) {
                    reqBuilder.header("Authorization", "Bearer $apiKey")
                }
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val respText = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IOException("拉取模型失败 (${resp.code}): ${extractErrorDetail(respText)}")
                    }
                    parseDiscoveredModelsResponse(respText, slot)
                }
            }
            val fallbackList = fallbackAttempt.getOrNull()
            if (!fallbackList.isNullOrEmpty()) {
                return fallbackList
            }
        }

        throw primaryAttempt.exceptionOrNull()
            ?: IOException("未能从该端点解析出可用模型列表")
    }

    private fun fetchOpenAiOrLocalModels(
        baseUrl: String,
        apiKey: String,
        slot: ModelCapabilitySlot
    ): List<DiscoveredModelItem> {
        val openAiUrl = EndpointResolver.resolveOpenAiModelsEndpoint(baseUrl)
        val primaryAttempt = runCatching {
            val reqBuilder = Request.Builder()
                .url(openAiUrl)
                .get()
            if (apiKey.isNotEmpty()) {
                reqBuilder.header("Authorization", "Bearer $apiKey")
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val respText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("拉取模型失败 (${resp.code}): ${extractErrorDetail(respText)}")
                }
                parseDiscoveredModelsResponse(respText, slot)
            }
        }

        val primaryList = primaryAttempt.getOrNull()
        if (!primaryList.isNullOrEmpty()) {
            return primaryList
        }

        // Local Ollama native /api/tags fallback
        if (isLocalEndpoint(baseUrl)) {
            val hostRoot = baseUrl.trimEnd('/')
                .removeSuffix("/v1")
                .removeSuffix("/models")
                .trimEnd('/')
            val ollamaAttempt = runCatching {
                val req = Request.Builder().url("$hostRoot/api/tags").get().build()
                httpClient.newCall(req).execute().use { resp ->
                    val respText = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IOException("本地模型查询失败 (${resp.code})")
                    }
                    parseDiscoveredModelsResponse(respText, slot)
                }
            }
            ollamaAttempt.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        throw primaryAttempt.exceptionOrNull()
            ?: IOException("未能从该端点解析出可用模型列表")
    }

    internal fun transcribeWithOpenAiStt(
        wavFile: File,
        config: SttConfig,
        biasPrompt: String
    ): String {
        val url = EndpointResolver.resolveSttEndpoint(config.baseUrl)
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                wavFile.name,
                wavFile.asRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", config.model.trim())
            .addFormDataPart("response_format", "json")

        if (config.language.isNotBlank()) {
            bodyBuilder.addFormDataPart("language", config.language.trim())
        }
        if (biasPrompt.isNotBlank()) {
            bodyBuilder.addFormDataPart("prompt", biasPrompt)
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(bodyBuilder.build())

        if (config.apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val respText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("STT 接口错误 (${resp.code}): ${extractErrorDetail(respText)}")
            }
            return parseSttResponse(respText)
        }
    }

    internal fun callOpenAiChatCompletion(
        config: LlmConfig,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val url = EndpointResolver.resolveChatEndpoint(config.baseUrl)
        val includeTemp = !isSamplingTemperatureDeprecated(config.model)

        fun buildBody(withTemp: Boolean): JsonObject = JsonObject().apply {
            addProperty("model", config.model.trim())
            if (withTemp) {
                addProperty("temperature", config.temperature)
            }
            addProperty("stream", false)
            val messages = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userPrompt)
                })
            }
            add("messages", messages)
        }

        fun sendOnce(bodyObj: JsonObject): Pair<Int, String> {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(bodyObj.toString().toRequestBody(JSON_MEDIA_TYPE))
            if (config.apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                return resp.code to resp.body?.string().orEmpty()
            }
        }

        var (code, respText) = sendOnce(buildBody(includeTemp))
        // Adaptive fallback if a custom gateway alias maps to Gemini 3.5+/o-series and rejects temperature
        if (code == 400 && includeTemp && respText.contains("temperature", ignoreCase = true)) {
            val retry = sendOnce(buildBody(withTemp = false))
            code = retry.first
            respText = retry.second
        }
        if (code !in 200..299) {
            throw IOException("LLM 接口错误 ($code): ${extractErrorDetail(respText)}")
        }
        return parseOpenAiChatResponse(respText)
    }

    internal fun callGeminiMultimodalAudio(
        config: MultimodalConfig,
        prompt: String,
        base64Wav: String
    ): String {
        val parts = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("text", prompt)
            })
            add(JsonObject().apply {
                add("inline_data", JsonObject().apply {
                    addProperty("mime_type", "audio/wav")
                    addProperty("data", base64Wav)
                })
            })
        }
        return executeGeminiGenerateContent(config, parts)
    }

    internal fun callGeminiTextOnly(
        config: MultimodalConfig,
        fullPrompt: String
    ): String {
        val parts = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("text", fullPrompt)
            })
        }
        return executeGeminiGenerateContent(config, parts)
    }

    private fun executeGeminiGenerateContent(
        config: MultimodalConfig,
        parts: JsonArray
    ): String {
        val url = EndpointResolver.resolveGeminiEndpoint(config.baseUrl, config.model, config.apiKey)
        val genConfig = buildGeminiGenerationConfig(
            model = config.model,
            temperature = config.temperature,
            includeThinkingHint = true
        )

        fun buildPayload(overrideGenConfig: JsonObject?): JsonObject = JsonObject().apply {
            val contents = JsonArray().apply {
                add(JsonObject().apply {
                    add("parts", parts)
                })
            }
            add("contents", contents)
            if (overrideGenConfig != null && overrideGenConfig.size() > 0) {
                add("generationConfig", overrideGenConfig)
            }
        }

        fun sendOnce(payload: JsonObject): Pair<Int, String> {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            if (config.apiKey.isNotBlank()) {
                reqBuilder.header("x-goog-api-key", config.apiKey.trim())
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                return resp.code to resp.body?.string().orEmpty()
            }
        }

        var (code, respText) = sendOnce(buildPayload(genConfig))
        // Transparent gateway compatibility fallback: if an older reverse-proxy schema rejects
        // thinkingConfig or sampling fields with HTTP 400, retry once with bare contents!
        if (code == 400 && genConfig != null) {
            val retry = sendOnce(buildPayload(overrideGenConfig = null))
            code = retry.first
            respText = retry.second
        }

        if (code !in 200..299) {
            throw IOException("Gemini 接口错误 ($code): ${extractErrorDetail(respText)}")
        }
        return parseGeminiResponse(respText)
    }

    internal fun callOpenAiMultimodalAudio(
        config: MultimodalConfig,
        prompt: String,
        base64Wav: String
    ): String {
        val url = EndpointResolver.resolveChatEndpoint(config.baseUrl)
        val includeTemp = !isSamplingTemperatureDeprecated(config.model)

        fun buildBody(withTemp: Boolean): JsonObject = JsonObject().apply {
            addProperty("model", config.model.trim())
            if (withTemp) {
                addProperty("temperature", config.temperature)
            }
            val messages = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    val contentParts = JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", prompt)
                        })
                        add(JsonObject().apply {
                            addProperty("type", "input_audio")
                            add("input_audio", JsonObject().apply {
                                addProperty("data", base64Wav)
                                addProperty("format", "wav")
                            })
                        })
                    }
                    add("content", contentParts)
                })
            }
            add("messages", messages)
        }

        fun sendOnce(bodyObj: JsonObject): Pair<Int, String> {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(bodyObj.toString().toRequestBody(JSON_MEDIA_TYPE))
            if (config.apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                return resp.code to resp.body?.string().orEmpty()
            }
        }

        var (code, respText) = sendOnce(buildBody(includeTemp))
        if (code == 400 && includeTemp && respText.contains("temperature", ignoreCase = true)) {
            val retry = sendOnce(buildBody(withTemp = false))
            code = retry.first
            respText = retry.second
        }
        if (code !in 200..299) {
            throw IOException("多模态接口错误 ($code): ${extractErrorDetail(respText)}")
        }
        return parseOpenAiChatResponse(respText)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SPECIAL_TOKEN_REGEX = Regex("<\\|[^|]+\\|>")
        private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        fun isLocalEndpoint(url: String): Boolean {
            val lower = url.lowercase()
            return lower.contains("localhost") ||
                lower.contains("127.0.0.1") ||
                lower.contains("10.0.2.2") ||
                lower.contains("192.168.") ||
                lower.contains("10.")
        }

        fun cleanRawTranscript(raw: String): String {
            return raw.replace(SPECIAL_TOKEN_REGEX, "").trim()
        }

        fun cleanModelOutput(raw: String): String {
            // Strip <think>...</think> blocks from reasoning models (e.g., DeepSeek-R1 / Qwen3)
            var cleaned = raw.replace(THINK_BLOCK_REGEX, "").trim()
            cleaned = cleaned.replace(SPECIAL_TOKEN_REGEX, "").trim()
            // Strip accidental XML tag wrappers if echoed
            cleaned = cleaned
                .removePrefix("<polished_text>")
                .removeSuffix("</polished_text>")
                .trim()
            return cleaned
        }

        fun parseSttResponse(respBody: String): String {
            val trimmed = respBody.trim()
            if (!trimmed.startsWith("{")) {
                return cleanRawTranscript(trimmed)
            }
            val json = JsonParser.parseString(trimmed).asJsonObject
            val text = json.get("text")?.asString.orEmpty()
            return cleanRawTranscript(text)
        }

        fun parseOpenAiChatResponse(respBody: String): String {
            val json = JsonParser.parseString(respBody).asJsonObject
            val choices = json.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                throw IOException("模型返回结果为空 (choices 为空)")
            }
            val message = choices[0].asJsonObject.getAsJsonObject("message")
            val contentElem = message?.get("content")
            val rawContent = when {
                contentElem == null || contentElem.isJsonNull -> ""
                contentElem.isJsonPrimitive -> contentElem.asString
                contentElem.isJsonArray -> {
                    contentElem.asJsonArray.joinToString("") { part ->
                        part.asJsonObject.get("text")?.asString.orEmpty()
                    }
                }
                else -> contentElem.toString()
            }
            return cleanModelOutput(rawContent)
        }

        fun parseGeminiResponse(respBody: String): String {
            val json = JsonParser.parseString(respBody).asJsonObject
            val candidates = json.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) {
                throw IOException("Gemini 未返回候选文本 (candidates 为空)")
            }
            val parts = candidates[0].asJsonObject
                .getAsJsonObject("content")
                ?.getAsJsonArray("parts")
            val combined = buildString {
                parts?.forEach { partElem ->
                    val obj = partElem.asJsonObject
                    // Skip thought parts if Gemini thinking is returned separately
                    val isThought = obj.get("thought")?.let {
                        if (it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) it.asBoolean else false
                    } ?: false
                    if (!isThought) {
                        append(obj.get("text")?.asString.orEmpty())
                    }
                }
            }
            return cleanModelOutput(combined)
        }

        fun extractErrorDetail(respBody: String): String {
            if (respBody.isBlank()) return "无响应内容"
            return try {
                val json = JsonParser.parseString(respBody).asJsonObject
                val errElem = json.get("error")
                val msg = when {
                    errElem == null -> json.get("message")?.asString
                    errElem.isJsonObject -> errElem.asJsonObject.get("message")?.asString
                    errElem.isJsonPrimitive -> errElem.asString
                    else -> null
                }
                (msg ?: respBody).take(180)
            } catch (_: Exception) {
                respBody.take(180)
            }
        }

        private val HALLUCINATION_PHRASES = listOf(
            "字幕由", "Amara.org", "明镜与点点", "优优独播剧场",
            "Thank you for watching", "Subtitles by", "请不吝点赞"
        )

        fun isHallucinatedSilence(transcript: String): Boolean {
            val t = transcript.trim()
            if (t.isEmpty()) return true
            return HALLUCINATION_PHRASES.any { t.contains(it, ignoreCase = true) }
        }

        /**
         * Normalizes provider/prefixed model identifiers (e.g. "models/gemini-3.8-flash",
         * "google/gemini-3.5-flash-lite") to bare lowercase model code for capability inspection.
         */
        fun normalizeModelCode(model: String): String {
            var clean = model.trim().lowercase()
            clean = clean.removePrefix("models/")
            if (clean.contains("/")) {
                clean = clean.substringAfterLast("/")
            }
            return clean
        }

        /**
         * Returns true when `temperature` sampling parameter is deprecated or disallowed
         * upstream (Gemini 3.x / 3.5 / 3.6 / 3.8 / 4.x family, OpenAI o1/o3/o4 reasoning models).
         */
        fun isSamplingTemperatureDeprecated(model: String): Boolean {
            val code = normalizeModelCode(model)
            if (code.isEmpty()) return false
            val isGemini3OrNewer = code.startsWith("gemini-3") || code.startsWith("gemini-4")
            val isOpenAiReasoning = code == "o1" || code.startsWith("o1-") ||
                code == "o3" || code.startsWith("o3-") ||
                code == "o4" || code.startsWith("o4-")
            return isGemini3OrNewer || isOpenAiReasoning
        }

        /**
         * For Gemini 3.6 / 3.7 / 3.8 Flash & Gemini 4 models (whose default thinking level is
         * MEDIUM and where MINIMAL is unsupported), explicitly request `LOW` thinking level so
         * real-time voice input stays sub-second without wasting thinking tokens.
         * Flash-Lite (3.5/3.1) already defaults to MINIMAL, and Gemini 2.5 does not accept thinkingLevel.
         */
        fun recommendedGeminiThinkingLevel(model: String): String? {
            val code = normalizeModelCode(model)
            if (code.isEmpty()) return null
            if (code.contains("lite") || code.contains("tts") || code.contains("live") || code.contains("image")) {
                return null
            }
            val isFlash36Plus = code.startsWith("gemini-3.6-flash") ||
                code.startsWith("gemini-3.7-flash") ||
                code.startsWith("gemini-3.8-flash") ||
                code.startsWith("gemini-3.9-flash") ||
                code.startsWith("gemini-4")
            return if (isFlash36Plus) "LOW" else null
        }

        fun buildGeminiGenerationConfig(
            model: String,
            temperature: Float,
            includeThinkingHint: Boolean = true
        ): JsonObject? {
            val cfg = JsonObject()
            if (!isSamplingTemperatureDeprecated(model)) {
                cfg.addProperty("temperature", temperature)
            }
            if (includeThinkingHint) {
                recommendedGeminiThinkingLevel(model)?.let { level ->
                    cfg.add("thinkingConfig", JsonObject().apply {
                        addProperty("thinkingLevel", level)
                    })
                }
            }
            return if (cfg.size() > 0) cfg else null
        }

        /**
         * Parses model discovery payloads across OpenAI/NewAPI/OpenRouter (`{"data":[{"id":...}]}`),
         * Google Gemini (`/v1beta/models` -> `{"models":[{"name":"models/...", "supportedGenerationMethods":[...]}]}`),
         * Ollama (`/api/tags` -> `{"models":[{"name":"..."}]}`), and flat JSON arrays.
         */
        fun parseDiscoveredModelsResponse(
            respBody: String,
            slot: ModelCapabilitySlot
        ): List<DiscoveredModelItem> {
            val parsed = JsonParser.parseString(respBody.trim())
            val rawItems = mutableListOf<Triple<String, String, String>>() // id, displayName, ownedBy

            when {
                parsed.isJsonArray -> {
                    parsed.asJsonArray.forEach { elem ->
                        if (elem.isJsonPrimitive) {
                            val id = elem.asString.trim()
                            if (id.isNotEmpty()) rawItems.add(Triple(id, "", ""))
                        } else if (elem.isJsonObject) {
                            extractModelFromObject(elem.asJsonObject)?.let { rawItems.add(it) }
                        }
                    }
                }
                parsed.isJsonObject -> {
                    val root = parsed.asJsonObject
                    val dataArr = root.getAsJsonArray("data")
                    val modelsArr = root.getAsJsonArray("models")
                    when {
                        dataArr != null -> {
                            dataArr.forEach { elem ->
                                if (elem.isJsonObject) {
                                    extractModelFromObject(elem.asJsonObject)?.let { rawItems.add(it) }
                                } else if (elem.isJsonPrimitive) {
                                    val id = elem.asString.trim()
                                    if (id.isNotEmpty()) rawItems.add(Triple(id, "", ""))
                                }
                            }
                        }
                        modelsArr != null -> {
                            modelsArr.forEach { elem ->
                                if (elem.isJsonObject) {
                                    extractGeminiOrOllamaModel(elem.asJsonObject)?.let { rawItems.add(it) }
                                }
                            }
                        }
                    }
                }
            }

            val seen = LinkedHashSet<String>()
            val decorated = mutableListOf<DiscoveredModelItem>()
            for ((rawId, displayName, ownedBy) in rawItems) {
                val cleanId = rawId.trim()
                if (cleanId.isEmpty() || !seen.add(cleanId)) continue
                decorated.add(classifyDiscoveredModel(cleanId, displayName, ownedBy, slot))
            }

            return decorated.sortedWith(
                compareByDescending<DiscoveredModelItem> { it.isRecommended }
                    .thenByDescending { modelPriorityRank(it.id, slot) }
                    .thenBy { it.id.lowercase() }
            )
        }

        private fun extractModelFromObject(obj: JsonObject): Triple<String, String, String>? {
            val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?: obj.get("model")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?: obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.removePrefix("models/")
                ?: return null
            if (id.isEmpty()) return null
            val displayName = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?.takeIf { it != id && it != "models/$id" }
                ?: obj.get("displayName")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?: ""
            val ownedBy = obj.get("owned_by")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            return Triple(id, displayName, ownedBy)
        }

        private fun extractGeminiOrOllamaModel(obj: JsonObject): Triple<String, String, String>? {
            // If Gemini `supportedGenerationMethods` is present, filter out pure embedding/aqa models
            val methodsArr = obj.getAsJsonArray("supportedGenerationMethods")
            if (methodsArr != null && methodsArr.size() > 0) {
                val methods = methodsArr.mapNotNull {
                    if (it.isJsonPrimitive) it.asString else null
                }
                if ("generateContent" !in methods) {
                    return null
                }
            }
            val rawName = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?: obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?: return null
            val cleanId = rawName.removePrefix("models/").trim()
            if (cleanId.isEmpty()) return null
            val displayName = obj.get("displayName")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            return Triple(cleanId, displayName, if (methodsArr != null) "google" else "")
        }

        private fun classifyDiscoveredModel(
            id: String,
            displayName: String,
            ownedBy: String,
            slot: ModelCapabilitySlot
        ): DiscoveredModelItem {
            val lower = id.lowercase()
            val code = normalizeModelCode(id)
            val isNonTextOrUtility = lower.contains("embedding") ||
                lower.contains("rerank") ||
                lower.contains("moderation") ||
                lower.contains("dall-e") ||
                lower.contains("imagen") ||
                lower.contains("veo") ||
                lower.contains("flux") ||
                lower.contains("stable-diffusion") ||
                lower.contains("tts") ||
                lower.contains("-image") ||
                lower.contains("banana") ||
                lower.contains("aqa")

            var recommended = false
            var badge = ""

            when (slot) {
                ModelCapabilitySlot.STT -> {
                    val isAsr = lower.contains("whisper") ||
                        lower.contains("sensevoice") ||
                        lower.contains("transcribe") ||
                        lower.contains("asr") ||
                        lower.contains("paraformer") ||
                        lower.contains("speech") ||
                        lower.contains("fun-asr")
                    if (isAsr && !lower.contains("tts")) {
                        recommended = true
                        badge = when {
                            lower.contains("turbo") || lower.contains("sensevoice") || lower.contains("mini") -> "⚡ 极速识别"
                            else -> "🔊 语音转写"
                        }
                    }
                }
                ModelCapabilitySlot.LLM -> {
                    val isPureStt = lower.contains("whisper") || lower.contains("sensevoice") || lower.endsWith("-asr")
                    if (!isNonTextOrUtility && !isPureStt && !lower.contains("live")) {
                        val isFastLlm = code.startsWith("gemini-3.5-flash-lite") ||
                            code.startsWith("gemini-3.1-flash-lite") ||
                            code.startsWith("gemini-2.5-flash") ||
                            code.startsWith("gemini-3.8-flash") ||
                            code.startsWith("gemini-3.6-flash") ||
                            code == "deepseek-chat" ||
                            lower.contains("deepseek-v3") ||
                            code.startsWith("gpt-4o-mini") ||
                            code.startsWith("gpt-4.1-mini") ||
                            code.startsWith("gpt-4.1-nano") ||
                            lower.contains("qwen2.5") ||
                            lower.contains("qwen3") ||
                            lower.contains("glm-4-flash") ||
                            lower.contains("llama-3.3") ||
                            lower.contains("doubao")
                        if (isFastLlm) {
                            recommended = true
                            badge = when {
                                code.contains("flash-lite") || code == "gemini-2.5-flash" || lower.contains("mini") || lower.contains("glm-4-flash") -> "⚡ 低延迟推荐"
                                code.startsWith("gemini-3.8") || code == "deepseek-chat" || lower.contains("deepseek-v3") -> "✨ 润色推荐"
                                else -> "推荐"
                            }
                        }
                    }
                }
                ModelCapabilitySlot.MULTIMODAL -> {
                    if (!isNonTextOrUtility && !lower.contains("live")) {
                        val isAudioMultimodal = code.startsWith("gemini-3.5-flash-lite") ||
                            code.startsWith("gemini-3.1-flash-lite") ||
                            code.startsWith("gemini-2.5-flash") ||
                            code.startsWith("gemini-3.8-flash") ||
                            code.startsWith("gemini-3.6-flash") ||
                            code.startsWith("gemini-2.5-pro") ||
                            code.startsWith("gemini-3.1-pro") ||
                            code.startsWith("gemini-2.0-flash") ||
                            code.startsWith("gemini-4") ||
                            (lower.contains("gpt-4o") && lower.contains("audio")) ||
                            (lower.contains("qwen") && lower.contains("omni")) ||
                            lower.contains("voxtral")
                        if (isAudioMultimodal) {
                            recommended = true
                            badge = when {
                                code == "gemini-3.5-flash-lite" || code == "gemini-2.5-flash" || code == "gemini-3.1-flash-lite" -> "⚡ 极速首选"
                                code == "gemini-3.8-flash" || code == "gemini-3.6-flash" -> "🧠 旗舰自适应"
                                lower.contains("audio") || lower.contains("omni") -> "🎙 原生音频"
                                else -> "支持音频"
                            }
                        }
                    }
                }
            }

            return DiscoveredModelItem(
                id = id,
                displayName = displayName,
                ownedBy = ownedBy,
                isRecommended = recommended,
                badge = badge
            )
        }

        private fun modelPriorityRank(id: String, slot: ModelCapabilitySlot): Int {
            val code = normalizeModelCode(id)
            return when (slot) {
                ModelCapabilitySlot.MULTIMODAL -> when {
                    code == "gemini-3.5-flash-lite" -> 100
                    code == "gemini-2.5-flash" -> 98
                    code == "gemini-3.1-flash-lite" -> 96
                    code == "gemini-3.8-flash" -> 94
                    code == "gemini-3.6-flash" -> 90
                    code == "gpt-4o-mini-audio-preview" -> 88
                    code.startsWith("gemini-") -> 75
                    else -> 10
                }
                ModelCapabilitySlot.LLM -> when {
                    code == "deepseek-chat" -> 100
                    code == "gemini-3.5-flash-lite" -> 98
                    code == "gemini-2.5-flash" -> 96
                    code == "gemini-3.8-flash" -> 94
                    code == "gpt-4o-mini" -> 92
                    else -> 10
                }
                ModelCapabilitySlot.STT -> when {
                    code.contains("sensevoice") -> 100
                    code.contains("whisper-large-v3-turbo") -> 98
                    code.contains("gpt-4o-mini-transcribe") -> 96
                    else -> 10
                }
            }
        }
    }
}
