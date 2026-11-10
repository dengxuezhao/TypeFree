package com.typefree.ime.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.typefree.ime.audio.AacAudioCompressor
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DiscoveredModelItem
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.ModelCapabilitySlot
import com.typefree.ime.data.ModelProbeResult
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SnippetMatcher
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.ThinkingMode
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.UnifiedProviderCatalog
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.network.asr.AsrSessionLimits
import com.typefree.ime.network.asr.DashScopeStreamingAsrClient
import com.typefree.ime.network.asr.GeminiLiveTranscribeClient
import com.typefree.ime.network.asr.RotatingStreamingAsrSession
import com.typefree.ime.network.asr.StreamingAsrClient
import com.typefree.ime.network.asr.StreamingAsrSession
import com.typefree.ime.network.asr.VolcengineSaucAsrClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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
    val modeSummary: String,
    val sttLatencyMs: Long = 0L,
    val llmLatencyMs: Long = 0L,
    val usedStreamingAsr: Boolean = false,
    val diagnosticLog: String = ""
)

internal data class ChatCallOutcome(
    val text: String,
    val diagnosticLog: String
)

class AiPipelineEngine(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val dashScopeAsrClient: StreamingAsrClient = DashScopeStreamingAsrClient(httpClient),
    private val volcengineAsrClient: StreamingAsrClient = VolcengineSaucAsrClient(httpClient),
    private val geminiLiveAsrClient: StreamingAsrClient = GeminiLiveTranscribeClient(httpClient)
) {

    private val lastPrewarmedByOrigin = ConcurrentHashMap<String, Long>()

    /**
     * Asynchronously warms up DNS + TCP + TLS 1.3 sockets into [httpClient]'s [ConnectionPool]
     * right when the user presses down on the microphone button, hiding 150-350ms of cold
     * connection setup inside the first ~300ms of speaking time.
     */
    suspend fun prewarmEndpoints(settings: AppSettings) = withContext(Dispatchers.IO) {
        val candidateUrls = buildList {
            when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> {
                    if ((settings.sttConfig.protocol == SttProtocol.OPENAI_HTTP ||
                            settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS) &&
                        settings.sttConfig.baseUrl.isNotBlank() &&
                        !isLocalEndpoint(settings.sttConfig.baseUrl)
                    ) {
                        add(settings.sttConfig.baseUrl)
                    }
                    val effectiveLlm = resolveEffectiveLlmConfig(settings)
                    if (effectiveLlm.baseUrl.isNotBlank() &&
                        !isLocalEndpoint(effectiveLlm.baseUrl)
                    ) {
                        add(effectiveLlm.baseUrl)
                    }
                }
                PipelineMode.MULTIMODAL_DIRECT -> {
                    if (settings.multimodalConfig.baseUrl.isNotBlank() &&
                        !isLocalEndpoint(settings.multimodalConfig.baseUrl)
                    ) {
                        add(settings.multimodalConfig.baseUrl)
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        val originsToWarm = candidateUrls
            .mapNotNull { EndpointResolver.extractHttpOrigin(it) }
            .distinct()
            .filter { origin ->
                val prev = lastPrewarmedByOrigin[origin] ?: 0L
                now - prev >= PREWARM_COOLDOWN_MS
            }

        if (originsToWarm.isEmpty()) return@withContext

        val warmClient = httpClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()

        coroutineScope {
            originsToWarm.map { origin ->
                async {
                    lastPrewarmedByOrigin[origin] = now
                    runCatching {
                        val req = Request.Builder()
                            .url(origin)
                            .head()
                            .build()
                        warmClient.newCall(req).execute().close()
                    }
                }
            }.awaitAll()
        }
    }

    private fun prewarmSingleHttpUrl(url: String) {
        if (url.isBlank() || isLocalEndpoint(url)) return
        val origin = EndpointResolver.extractHttpOrigin(url) ?: return
        runCatching {
            val warmClient = httpClient.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(origin)
                .head()
                .build()
            warmClient.newCall(req).execute().close()
        }
    }

    /**
     * Pre-establishes an idle, ready-to-stream `BidiGenerateContent` WebSocket session when
     * `GEMINI_LIVE_WS` is active so short 1-2s utterances skip ~800-1100ms of cold setup wait.
     */
    fun prewarmLiveStreamingIfNeeded(settings: AppSettings) {
        if (settings.pipelineMode != PipelineMode.DUAL_STAGE) return
        val sttCfg = settings.sttConfig
        if (sttCfg.protocol != SttProtocol.GEMINI_LIVE_WS || sttCfg.apiKey.isBlank()) return
        val hotwords = PromptBuilder.extractHotwordTerms(settings.dictionary)
        geminiLiveAsrClient.prewarmSession(sttCfg, hotwords)
    }

    fun evictLiveStreamingPrewarm() {
        geminiLiveAsrClient.evictPrewarmSession()
    }

    /**
     * Opens a live WebSocket streaming ASR session when Dual-Stage mode is configured with
     * Aliyun DashScope, Volcengine Doubao, or Google Gemini Live streaming protocol;
     * returns `null` for HTTP batch mode.
     */
    fun openLiveStreamingSession(settings: AppSettings): StreamingAsrSession? {
        if (settings.pipelineMode != PipelineMode.DUAL_STAGE) return null
        val sttCfg = settings.sttConfig
        val hotwords = PromptBuilder.extractHotwordTerms(settings.dictionary)
        val limit = AsrSessionLimits.forProtocol(sttCfg.protocol)
        return when (sttCfg.protocol) {
            SttProtocol.OPENAI_HTTP -> null
            SttProtocol.ALIYUN_DASHSCOPE_WS -> RotatingStreamingAsrSession(
                sessionFactory = { dashScopeAsrClient.openSession(sttCfg, hotwords) },
                limit = limit
            )
            SttProtocol.VOLCENGINE_SAUC_WS -> RotatingStreamingAsrSession(
                sessionFactory = { volcengineAsrClient.openSession(sttCfg, hotwords) },
                limit = limit
            )
            SttProtocol.GEMINI_LIVE_WS -> RotatingStreamingAsrSession(
                sessionFactory = { geminiLiveAsrClient.openSession(sttCfg, hotwords) },
                limit = limit
            )
        }
    }

    /**
     * Full voice pipeline execution: supports both Dual-Stage (Live Streaming WS / HTTP STT + LLM)
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
        preTranscribedText: String? = null,
        sttTailWaitMs: Long = 0L,
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
                    val usedLiveStream: Boolean
                    val sttElapsedMs: Long

                    val rawTranscript = if (!preTranscribedText.isNullOrBlank()) {
                        usedLiveStream = true
                        sttElapsedMs = sttTailWaitMs.coerceAtLeast(0L)
                        cleanRawTranscript(preTranscribedText)
                    } else {
                        usedLiveStream = false
                        validateSttConfig(sttCfg)?.let { errMsg ->
                            return@withContext Result.failure(IllegalStateException(errMsg))
                        }

                        onStageChanged(PipelineStage.TRANSCRIBING_STT)
                        val sttStart = System.currentTimeMillis()
                        val sttBias = PromptBuilder.buildSttBiasPrompt(settings.dictionary)
                        val hotwords = PromptBuilder.extractHotwordTerms(settings.dictionary)

                        val transcribed = when (sttCfg.protocol) {
                            SttProtocol.OPENAI_HTTP -> transcribeWithOpenAiStt(
                                wavFile = wavFile,
                                config = sttCfg,
                                biasPrompt = sttBias
                            )
                            SttProtocol.ALIYUN_DASHSCOPE_WS -> dashScopeAsrClient.transcribeWavFile(
                                wavFile = wavFile,
                                config = sttCfg,
                                hotwords = hotwords
                            )
                            SttProtocol.VOLCENGINE_SAUC_WS -> volcengineAsrClient.transcribeWavFile(
                                wavFile = wavFile,
                                config = sttCfg,
                                hotwords = hotwords
                            )
                            SttProtocol.GEMINI_LIVE_WS -> transcribeGeminiWavWithFastFallback(
                                wavFile = wavFile,
                                config = sttCfg,
                                hotwords = hotwords
                            )
                        }
                        sttElapsedMs = (System.currentTimeMillis() - sttStart).coerceAtLeast(0L)
                        transcribed
                    }

                    if (rawTranscript.isBlank() || isHallucinatedSilence(rawTranscript)) {
                        return@withContext Result.failure(IllegalArgumentException("未识别到清晰语音内容，请重试"))
                    }

                    // Voice Snippet 0ms Instant Expansion (before calling Stage 2 LLM)
                    if (effectiveIntent == VoiceIntentMode.DICTATE) {
                        SnippetMatcher.matchVoiceTranscript(rawTranscript, settings.snippets)?.let { matched ->
                            val elapsed = (System.currentTimeMillis() - startMs) + if (usedLiveStream) sttTailWaitMs else 0L
                            return@withContext Result.success(
                                PipelineExecutionResult(
                                    rawTranscript = rawTranscript,
                                    finalOutput = matched.content,
                                    latencyMs = elapsed,
                                    modeSummary = "口令展开 (${matched.title})",
                                    sttLatencyMs = sttElapsedMs,
                                    llmLatencyMs = 0L,
                                    usedStreamingAsr = usedLiveStream
                                )
                            )
                        }
                    }

                    val sttBadge = when (sttCfg.protocol) {
                        SttProtocol.OPENAI_HTTP -> sttCfg.model.substringAfterLast('/')
                        SttProtocol.ALIYUN_DASHSCOPE_WS -> "百炼流式"
                        SttProtocol.VOLCENGINE_SAUC_WS -> "豆包流式"
                        SttProtocol.GEMINI_LIVE_WS -> "Gemini Live"
                    }

                    // If Speak-to-Edit / Help-me-write / Translate is active, LLM MUST run
                    if (!settings.llmConfig.enabled && !requiresGenerativeLlm) {
                        val elapsed = (System.currentTimeMillis() - startMs) + if (usedLiveStream) sttTailWaitMs else 0L
                        val directLabel = if (sttCfg.protocol == SttProtocol.GEMINI_LIVE_WS) {
                            "Gemini Live 智能流式"
                        } else {
                            "仅 STT ($sttBadge)"
                        }
                        return@withContext Result.success(
                            PipelineExecutionResult(
                                rawTranscript = rawTranscript,
                                finalOutput = rawTranscript,
                                latencyMs = elapsed,
                                modeSummary = directLabel,
                                sttLatencyMs = sttElapsedMs,
                                llmLatencyMs = 0L,
                                usedStreamingAsr = usedLiveStream
                            )
                        )
                    }

                    val llmCfg = resolveEffectiveLlmConfig(settings)
                    if (llmCfg.apiKey.isBlank() && !isLocalEndpoint(llmCfg.baseUrl)) {
                        return@withContext Result.failure(
                            IllegalStateException("请先在「API 配置」中填写 LLM 润色 API Key（或关闭 LLM 润色开关）")
                        )
                    }

                    onStageChanged(PipelineStage.POLISHING_LLM)
                    val llmStart = System.currentTimeMillis()
                    val chunks = if (effectiveIntent == VoiceIntentMode.DICTATE) {
                        splitLongTextForChunkedPolish(rawTranscript, CHUNKED_POLISH_THRESHOLD_CHARS)
                    } else {
                        listOf(rawTranscript)
                    }

                    val llmOutcome = if (chunks.size > 1 && effectiveIntent == VoiceIntentMode.DICTATE) {
                        val semaphore = Semaphore(MAX_CONCURRENT_POLISH_CHUNKS)
                        val chunkOutcomes = coroutineScope {
                            chunks.mapIndexed { idx, chunkText ->
                                async {
                                    semaphore.withPermit {
                                        val chunkContext = if (idx == 0) {
                                            safeContext
                                        } else {
                                            chunks[idx - 1].takeLast(80)
                                        }
                                        val (sysPrompt, usrPrompt) = PromptBuilder.buildDictationPrompts(
                                            rawSpeech = chunkText,
                                            style = activeStyle,
                                            targetAppName = targetAppName,
                                            dictionary = settings.dictionary,
                                            surroundingBeforeCursor = chunkContext
                                        )
                                        executeLlmChatWithDiagnostics(
                                            config = llmCfg,
                                            systemPrompt = sysPrompt,
                                            userPrompt = usrPrompt
                                        )
                                    }
                                }
                            }.awaitAll()
                        }
                        ChatCallOutcome(
                            text = chunkOutcomes.mapIndexed { idx, outcome ->
                                outcome.text.ifBlank { chunks[idx] }
                            }.joinToString(""),
                            diagnosticLog = chunkOutcomes.mapIndexed { idx, outcome ->
                                "[Chunk ${idx + 1}/${chunkOutcomes.size}] ${outcome.diagnosticLog}"
                            }.joinToString("\n")
                        )
                    } else {
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
                        executeLlmChatWithDiagnostics(
                            config = llmCfg,
                            systemPrompt = sysPrompt,
                            userPrompt = usrPrompt
                        )
                    }
                    val polished = llmOutcome.text
                    val llmElapsedMs = (System.currentTimeMillis() - llmStart).coerceAtLeast(0L)
                    val totalPostReleaseMs = (System.currentTimeMillis() - startMs) +
                        if (usedLiveStream) sttTailWaitMs else 0L

                    val modePrefix = if (usedLiveStream) "$sttBadge" else "STT"
                    val fullDiag = buildString {
                        appendLine("STT [$sttBadge]: ${sttElapsedMs} ms (streaming=$usedLiveStream)")
                        append(llmOutcome.diagnosticLog)
                    }
                    Result.success(
                        PipelineExecutionResult(
                            rawTranscript = rawTranscript,
                            finalOutput = polished.ifBlank { rawTranscript },
                            latencyMs = totalPostReleaseMs,
                            modeSummary = "$modePrefix + ${llmCfg.model.substringAfterLast('/')}",
                            sttLatencyMs = sttElapsedMs,
                            llmLatencyMs = llmElapsedMs,
                            usedStreamingAsr = usedLiveStream,
                            diagnosticLog = fullDiag
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
                    val output = when (mmCfg.protocol) {
                        MultimodalProtocol.GEMINI_NATIVE -> {
                            val compressedM4a = AacAudioCompressor.compressWavToM4aOrNull(wavFile)
                            try {
                                val (uploadBytes, uploadMime) = if (compressedM4a != null) {
                                    compressedM4a.readBytes() to "audio/mp4"
                                } else {
                                    wavFile.readBytes() to "audio/wav"
                                }
                                val b64 = java.util.Base64.getEncoder().encodeToString(uploadBytes)
                                callGeminiMultimodalAudio(
                                    config = mmCfg,
                                    prompt = prompt,
                                    base64Wav = b64,
                                    mimeType = uploadMime
                                )
                            } finally {
                                if (compressedM4a != null) {
                                    runCatching { compressedM4a.delete() }
                                }
                            }
                        }
                        MultimodalProtocol.OPENAI_AUDIO_CHAT -> {
                            val b64 = java.util.Base64.getEncoder().encodeToString(wavFile.readBytes())
                            callOpenAiMultimodalAudio(
                                config = mmCfg,
                                prompt = prompt,
                                base64Wav = b64
                            )
                        }
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

    private fun validateSttConfig(sttCfg: SttConfig): String? {
        return when (sttCfg.protocol) {
            SttProtocol.OPENAI_HTTP -> {
                when {
                    sttCfg.baseUrl.isBlank() -> "请先在「API 配置」中填写语音识别 (STT) 接口地址"
                    sttCfg.apiKey.isBlank() && !isLocalEndpoint(sttCfg.baseUrl) ->
                        "请先在「API 配置」中填写 STT API Key"
                    else -> null
                }
            }
            SttProtocol.ALIYUN_DASHSCOPE_WS -> {
                if (sttCfg.apiKey.isBlank()) {
                    "请先在「API 配置」中填写阿里云百炼 API Key (sk-...)"
                } else null
            }
            SttProtocol.VOLCENGINE_SAUC_WS -> {
                if (sttCfg.apiKey.isBlank()) {
                    "请先在「API 配置」中填写火山豆包语音 Access Token 或 API Key"
                } else null
            }
            SttProtocol.GEMINI_LIVE_WS -> {
                if (sttCfg.apiKey.isBlank()) {
                    "请先在「API 配置」中填写 Google Gemini API Key"
                } else null
            }
        }
    }

    private suspend fun transcribeGeminiWavWithFastFallback(
        wavFile: File,
        config: SttConfig,
        hotwords: List<String>
    ): String {
        val wsAttempt = runCatching {
            geminiLiveAsrClient.transcribeWavFile(
                wavFile = wavFile,
                config = config,
                hotwords = hotwords,
                timeoutMs = 2_400L
            )
        }
        val wsText = wsAttempt.getOrNull()?.trim().orEmpty()
        if (wsText.isNotEmpty() && !isHallucinatedSilence(wsText)) {
            return wsText
        }

        // Fast HTTP .m4a fallback via gemini-3.5-flash-lite if WebSocket was blocked or dropped by proxy
        val httpBase = config.baseUrl
            .takeIf { it.isNotBlank() && !it.startsWith("wss://", ignoreCase = true) && !it.startsWith("ws://", ignoreCase = true) }
            ?: "https://generativelanguage.googleapis.com/v1beta"
        val vocabHint = if (hotwords.isNotEmpty()) {
            "专有名词参考：${hotwords.take(50).joinToString("、")}。"
        } else ""
        val fastPrompt = "请将这段语音准确转写为文字，自动去除无意义口头禅并补全自然标点，不要输出任何额外解释。若无有效人声请只输出 [EMPTY_SPEECH]。$vocabHint"

        val compressedM4a = AacAudioCompressor.compressWavToM4aOrNull(wavFile)
        val fallbackOut = try {
            val (uploadBytes, uploadMime) = if (compressedM4a != null) {
                compressedM4a.readBytes() to "audio/mp4"
            } else {
                wavFile.readBytes() to "audio/wav"
            }
            val b64 = java.util.Base64.getEncoder().encodeToString(uploadBytes)
            callGeminiMultimodalAudio(
                config = MultimodalConfig(
                    presetId = "gemini",
                    protocol = MultimodalProtocol.GEMINI_NATIVE,
                    baseUrl = httpBase,
                    apiKey = config.apiKey,
                    model = "gemini-3.5-flash-lite",
                    temperature = 0.1f,
                    thinkingMode = ThinkingMode.DISABLED
                ),
                prompt = fastPrompt,
                base64Wav = b64,
                mimeType = uploadMime
            )
        } catch (_: Throwable) {
            wsAttempt.getOrThrow()
        } finally {
            if (compressedM4a != null) {
                runCatching { compressedM4a.delete() }
            }
        }
        return if (fallbackOut.contains("[EMPTY_SPEECH]")) "" else cleanRawTranscript(fallbackOut)
    }

    private fun resolveEffectiveLlmConfig(settings: AppSettings): LlmConfig {
        val primary = settings.llmConfig
        if (primary.apiKey.isNotBlank() || isLocalEndpoint(primary.baseUrl)) {
            return primary
        }
        // Single-Key Google Gemini fallback when user configured Gemini Live STT or Vault
        val googleVaultKey = settings.providerVault[UnifiedProviderCatalog.ID_GOOGLE]?.apiKey.orEmpty()
        val geminiKey = when {
            settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS && settings.sttConfig.apiKey.isNotBlank() ->
                settings.sttConfig.apiKey
            settings.multimodalConfig.protocol == MultimodalProtocol.GEMINI_NATIVE && settings.multimodalConfig.apiKey.isNotBlank() ->
                settings.multimodalConfig.apiKey
            googleVaultKey.isNotBlank() -> googleVaultKey
            else -> ""
        }
        if (geminiKey.isNotBlank()) {
            val geminiBase = settings.providerVault[UnifiedProviderCatalog.ID_GOOGLE]?.customBaseUrl
                ?.takeIf { it.isNotBlank() }
                ?: settings.sttConfig.baseUrl.takeIf {
                    settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS && it.isNotBlank() && !it.startsWith("wss://", true)
                }
                ?: "https://generativelanguage.googleapis.com/v1beta"
            return primary.copy(
                presetId = "gemini_llm",
                baseUrl = geminiBase,
                apiKey = geminiKey,
                model = "gemini-3.5-flash-lite",
                thinkingMode = ThinkingMode.DISABLED
            )
        }
        return primary
    }

    private fun executeLlmChatWithDiagnostics(
        config: LlmConfig,
        systemPrompt: String,
        userPrompt: String
    ): ChatCallOutcome {
        if (EndpointResolver.isGeminiNativeHttpUrl(config.baseUrl)) {
            return callGeminiTextOnlyWithDiagnostics(
                config = MultimodalConfig(
                    presetId = "gemini",
                    protocol = MultimodalProtocol.GEMINI_NATIVE,
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    model = config.model.ifBlank { "gemini-3.5-flash-lite" },
                    temperature = config.temperature,
                    thinkingMode = config.thinkingMode
                ),
                fullPrompt = "$systemPrompt\n\n$userPrompt"
            )
        }
        return callOpenAiChatCompletionWithDiagnostics(config, systemPrompt, userPrompt)
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
            var diagLog = ""

            when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> {
                    val llmCfg = resolveEffectiveLlmConfig(settings)
                    if (llmCfg.apiKey.isBlank() && !isLocalEndpoint(llmCfg.baseUrl)) {
                        return@withContext Result.failure(IllegalStateException("请先填写 LLM API Key"))
                    }
                    val outcome = executeLlmChatWithDiagnostics(llmCfg, sysPrompt, usrPrompt)
                    output = outcome.text
                    diagLog = outcome.diagnosticLog
                    modelLabel = llmCfg.model
                }
                PipelineMode.MULTIMODAL_DIRECT -> {
                    val mmCfg = settings.multimodalConfig
                    if (mmCfg.apiKey.isBlank() && !isLocalEndpoint(mmCfg.baseUrl)) {
                        return@withContext Result.failure(IllegalStateException("请先填写多模态 API Key"))
                    }
                    when (mmCfg.protocol) {
                        MultimodalProtocol.GEMINI_NATIVE -> {
                            val outcome = callGeminiTextOnlyWithDiagnostics(
                                config = mmCfg,
                                fullPrompt = "$sysPrompt\n\n$usrPrompt"
                            )
                            output = outcome.text
                            diagLog = outcome.diagnosticLog
                        }
                        MultimodalProtocol.OPENAI_AUDIO_CHAT -> {
                            val outcome = callOpenAiChatCompletionWithDiagnostics(
                                config = LlmConfig(
                                    baseUrl = mmCfg.baseUrl,
                                    apiKey = mmCfg.apiKey,
                                    model = mmCfg.model,
                                    temperature = mmCfg.temperature,
                                    thinkingMode = mmCfg.thinkingMode
                                ),
                                systemPrompt = sysPrompt,
                                userPrompt = usrPrompt
                            )
                            output = outcome.text
                            diagLog = outcome.diagnosticLog
                        }
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
                    modeSummary = modelLabel,
                    llmLatencyMs = elapsed,
                    diagnosticLog = diagLog
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Lightweight L3 AI sentence continuation (`<= 20` chars) based on the last `<= 200` chars
     * before cursor, active App [ToneStyle], and personal dictionary hotwords.
     */
    suspend fun completeSentence(
        settings: AppSettings,
        activeStyle: ToneStyle,
        contextBeforeCursor: String,
        targetAppName: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val clippedContext = contextBeforeCursor.takeLast(200).trimEnd()
            if (clippedContext.isEmpty()) {
                return@withContext Result.success("")
            }
            val (sysPrompt, usrPrompt) = PromptBuilder.buildSentenceCompletionPrompts(
                contextBeforeCursor = clippedContext,
                style = activeStyle,
                targetAppName = targetAppName,
                dictionary = settings.dictionary
            )
            val rawOutput: String = when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> {
                    val llmCfg = resolveEffectiveLlmConfig(settings).copy(
                        thinkingMode = ThinkingMode.DISABLED
                    )
                    if (llmCfg.apiKey.isBlank() && !isLocalEndpoint(llmCfg.baseUrl)) {
                        return@withContext Result.failure(IllegalStateException("请先填写 LLM API Key"))
                    }
                    executeLlmChatWithDiagnostics(llmCfg, sysPrompt, usrPrompt).text
                }
                PipelineMode.MULTIMODAL_DIRECT -> {
                    val mmCfg = settings.multimodalConfig.copy(
                        thinkingMode = ThinkingMode.DISABLED
                    )
                    if (mmCfg.apiKey.isBlank() && !isLocalEndpoint(mmCfg.baseUrl)) {
                        return@withContext Result.failure(IllegalStateException("请先填写多模态 API Key"))
                    }
                    when (mmCfg.protocol) {
                        MultimodalProtocol.GEMINI_NATIVE -> {
                            callGeminiTextOnlyWithDiagnostics(
                                config = mmCfg,
                                fullPrompt = "$sysPrompt\n\n$usrPrompt"
                            ).text
                        }
                        MultimodalProtocol.OPENAI_AUDIO_CHAT -> {
                            callOpenAiChatCompletionWithDiagnostics(
                                config = LlmConfig(
                                    baseUrl = mmCfg.baseUrl,
                                    apiKey = mmCfg.apiKey,
                                    model = mmCfg.model,
                                    temperature = mmCfg.temperature,
                                    thinkingMode = ThinkingMode.DISABLED
                                ),
                                systemPrompt = sysPrompt,
                                userPrompt = usrPrompt
                            ).text
                        }
                    }
                }
            }
            val cleaned = com.typefree.ime.ime.predict.AiSentenceCompleter.cleanCompletionOutput(
                rawOutput = rawOutput,
                contextBeforeCursor = clippedContext
            )
            Result.success(cleaned)
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
            if (cleanBase.startsWith("wss://", ignoreCase = true) || cleanBase.startsWith("ws://", ignoreCase = true)) {
                throw IllegalArgumentException("WebSocket 流式语音接口请直接选择下方推荐模型预设")
            }
            if (cleanKey.isEmpty() && !isLocalEndpoint(cleanBase)) {
                throw IllegalArgumentException("请先填写 API Key 后再拉取可用模型")
            }

            val isGeminiNativeSlot =
                (slot == ModelCapabilitySlot.MULTIMODAL && protocol == MultimodalProtocol.GEMINI_NATIVE) ||
                    (slot == ModelCapabilitySlot.LLM && EndpointResolver.isGeminiNativeHttpUrl(cleanBase))
            val models = if (isGeminiNativeSlot) {
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

    /**
     * Live latency benchmark for a batch of discovered models.
     * Pre-warms the TCP+TLS connection once upfront so the first model is not unfairly penalized
     * by handshake RTT, then runs bounded-concurrency (`maxConcurrency = 3`) 1-token micro-probes
     * and emits each [ModelProbeResult] immediately via [onEachResult].
     */
    suspend fun probeModelsBatch(
        slot: ModelCapabilitySlot,
        baseUrl: String,
        apiKey: String,
        modelIds: List<String>,
        protocol: MultimodalProtocol = MultimodalProtocol.OPENAI_AUDIO_CHAT,
        thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
        maxConcurrency: Int = 3,
        onEachResult: (ModelProbeResult) -> Unit = {}
    ): List<ModelProbeResult> = withContext(Dispatchers.IO) {
        if (modelIds.isEmpty()) return@withContext emptyList()
        val warmTarget = when {
            (slot == ModelCapabilitySlot.MULTIMODAL && protocol == MultimodalProtocol.GEMINI_NATIVE) ||
                (slot == ModelCapabilitySlot.LLM && EndpointResolver.isGeminiNativeHttpUrl(baseUrl)) ->
                EndpointResolver.resolveGeminiModelsEndpoint(baseUrl, apiKey)
            slot == ModelCapabilitySlot.STT ->
                EndpointResolver.resolveSttEndpoint(baseUrl)
            else ->
                EndpointResolver.resolveChatEndpoint(baseUrl)
        }
        prewarmSingleHttpUrl(warmTarget)

        val sem = Semaphore(maxConcurrency.coerceIn(1, 5))
        coroutineScope {
            modelIds.map { id ->
                async {
                    sem.withPermit {
                        val res = probeModelLatency(
                            slot = slot,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            modelId = id,
                            protocol = protocol,
                            thinkingMode = thinkingMode
                        )
                        onEachResult(res)
                        res
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Measures real end-to-end model inference latency (gateway routing + model TTFT for 1 token)
     * under the user's currently selected [thinkingMode].
     */
    suspend fun probeModelLatency(
        slot: ModelCapabilitySlot,
        baseUrl: String,
        apiKey: String,
        modelId: String,
        protocol: MultimodalProtocol = MultimodalProtocol.OPENAI_AUDIO_CHAT,
        thinkingMode: ThinkingMode = ThinkingMode.DISABLED
    ): ModelProbeResult = withContext(Dispatchers.IO) {
        val cleanModel = modelId.trim()
        if (cleanModel.isEmpty()) {
            return@withContext ModelProbeResult(modelId, errorSummary = "模型为空")
        }
        val probeClient = httpClient.newBuilder()
            .callTimeout(7, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .build()

        try {
            when {
                slot == ModelCapabilitySlot.STT -> {
                    val url = EndpointResolver.resolveSttEndpoint(baseUrl)
                    val silentWav = WavAudioRecorder.buildWavHeader(6400) + ByteArray(6400)
                    val body = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(
                            "file",
                            "probe.wav",
                            silentWav.toRequestBody("audio/wav".toMediaType())
                        )
                        .addFormDataPart("model", cleanModel)
                        .addFormDataPart("response_format", "json")
                        .build()
                    val reqBuilder = Request.Builder().url(url).post(body)
                    if (apiKey.isNotBlank()) {
                        reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
                    }
                    val t0 = System.nanoTime()
                    probeClient.newCall(reqBuilder.build()).execute().use { resp ->
                        val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                        resp.body?.string()
                        if (resp.code in 200..299) {
                            ModelProbeResult(cleanModel, latencyMs = elapsed)
                        } else {
                            ModelProbeResult(cleanModel, errorSummary = formatProbeHttpError(resp.code))
                        }
                    }
                }

                (slot == ModelCapabilitySlot.MULTIMODAL && protocol == MultimodalProtocol.GEMINI_NATIVE) ||
                    (slot == ModelCapabilitySlot.LLM && EndpointResolver.isGeminiNativeHttpUrl(baseUrl)) -> {
                    val url = EndpointResolver.resolveGeminiEndpoint(baseUrl, cleanModel, apiKey)
                    fun buildGeminiProbe(withThinking: Boolean): JsonObject {
                        val genCfg = buildGeminiGenerationConfig(
                            model = cleanModel,
                            temperature = 0.1f,
                            thinkingMode = thinkingMode,
                            includeThinkingHint = withThinking
                        ) ?: JsonObject()
                        genCfg.addProperty("maxOutputTokens", 2)
                        return JsonObject().apply {
                            add("contents", JsonArray().apply {
                                add(JsonObject().apply {
                                    add("parts", JsonArray().apply {
                                        add(JsonObject().apply { addProperty("text", "1") })
                                    })
                                })
                            })
                            add("generationConfig", genCfg)
                        }
                    }

                    fun runGeminiProbe(payload: JsonObject): Triple<Int, Long, String> {
                        val reqBuilder = Request.Builder()
                            .url(url)
                            .header("Content-Type", "application/json")
                            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        if (apiKey.isNotBlank()) {
                            reqBuilder.header("x-goog-api-key", apiKey.trim())
                        }
                        val t0 = System.nanoTime()
                        probeClient.newCall(reqBuilder.build()).execute().use { resp ->
                            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                            return Triple(resp.code, elapsed, resp.body?.string().orEmpty())
                        }
                    }

                    var (code, elapsed, _) = runGeminiProbe(buildGeminiProbe(withThinking = true))
                    if (code == 400) {
                        val retry = runGeminiProbe(buildGeminiProbe(withThinking = false))
                        code = retry.first
                        elapsed = retry.second
                    }
                    if (code in 200..299) {
                        ModelProbeResult(cleanModel, latencyMs = elapsed)
                    } else {
                        ModelProbeResult(cleanModel, errorSummary = formatProbeHttpError(code))
                    }
                }

                else -> {
                    val url = EndpointResolver.resolveChatEndpoint(baseUrl)
                    val tempDeprecated = isSamplingTemperatureDeprecated(cleanModel)

                    fun buildChatProbe(
                        thinkingOverride: String?, // null = default applyThinkingControl, "none" = omit
                        withMaxTokens: Boolean
                    ): Pair<JsonObject, Boolean> {
                        var injectedThinking = false
                        val obj = JsonObject().apply {
                            addProperty("model", cleanModel)
                            addProperty("stream", false)
                            if (withMaxTokens) {
                                if (tempDeprecated) {
                                    addProperty("max_completion_tokens", 5)
                                } else {
                                    addProperty("max_tokens", 1)
                                }
                            }
                            when (thinkingOverride) {
                                null -> {
                                    injectedThinking = applyThinkingControl(this, baseUrl, cleanModel, thinkingMode)
                                }
                                "reasoning_effort" -> {
                                    addProperty(
                                        "reasoning_effort",
                                        if (thinkingMode == ThinkingMode.ENABLED) "high" else "minimal"
                                    )
                                    injectedThinking = true
                                }
                                "thinking_obj" -> {
                                    add("thinking", JsonObject().apply {
                                        addProperty(
                                            "type",
                                            if (thinkingMode == ThinkingMode.ENABLED) "enabled" else "disabled"
                                        )
                                    })
                                    injectedThinking = true
                                }
                                "none" -> Unit
                            }
                            add("messages", JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("role", "user")
                                    addProperty("content", "1")
                                })
                            })
                        }
                        return obj to injectedThinking
                    }

                    fun runChatProbe(payload: JsonObject): Triple<Int, Long, String> {
                        val reqBuilder = Request.Builder()
                            .url(url)
                            .header("Content-Type", "application/json")
                            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        if (apiKey.isNotBlank()) {
                            reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
                        }
                        val t0 = System.nanoTime()
                        probeClient.newCall(reqBuilder.build()).execute().use { resp ->
                            val elapsed = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                            return Triple(resp.code, elapsed, resp.body?.string().orEmpty())
                        }
                    }

                    val (probeBody, hadThinking) = buildChatProbe(thinkingOverride = null, withMaxTokens = true)
                    var (code, elapsed, errBody) = runChatProbe(probeBody)

                    if (code == 400 && hadThinking) {
                        val altSyntax = alternateThinkingSyntaxForFallback(probeBody, baseUrl, cleanModel, thinkingMode)
                        if (altSyntax != null) {
                            val (altBody, _) = buildChatProbe(thinkingOverride = altSyntax, withMaxTokens = true)
                            val altRes = runChatProbe(altBody)
                            code = altRes.first
                            elapsed = altRes.second
                            errBody = altRes.third
                            if (code in 200..299) {
                                rememberPreferredThinkingSyntax(baseUrl, cleanModel, thinkingMode, altSyntax)
                            }
                        }
                    }

                    if (code == 400) {
                        if (hadThinking && !errBody.contains("max_tokens", ignoreCase = true)) {
                            markThinkingHintUnsupported(baseUrl, cleanModel, thinkingMode)
                        }
                        val (fallbackBody, _) = buildChatProbe(thinkingOverride = "none", withMaxTokens = false)
                        val retry = runChatProbe(fallbackBody)
                        code = retry.first
                        elapsed = retry.second
                    }
                    if (code in 200..299) {
                        ModelProbeResult(cleanModel, latencyMs = elapsed)
                    } else {
                        ModelProbeResult(cleanModel, errorSummary = formatProbeHttpError(code))
                    }
                }
            }
        } catch (_: InterruptedIOException) {
            ModelProbeResult(cleanModel, errorSummary = "超时")
        } catch (_: Exception) {
            ModelProbeResult(cleanModel, errorSummary = "连接失败")
        }
    }

    internal fun transcribeWithOpenAiStt(
        wavFile: File,
        config: SttConfig,
        biasPrompt: String
    ): String {
        val url = EndpointResolver.resolveSttEndpoint(config.baseUrl)
        val isLocal = isLocalEndpoint(config.baseUrl)
        val compressedM4a = if (!isLocal) AacAudioCompressor.compressWavToM4aOrNull(wavFile) else null

        fun sendAudioMultipart(uploadFile: File, mimeType: String): Pair<Int, String> {
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    uploadFile.name,
                    uploadFile.asRequestBody(mimeType.toMediaType())
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
                return resp.code to resp.body?.string().orEmpty()
            }
        }

        try {
            if (compressedM4a != null) {
                val (m4aCode, m4aResp) = sendAudioMultipart(compressedM4a, "audio/mp4")
                if (m4aCode in 200..299) {
                    return parseSttResponse(m4aResp)
                }
                // If a custom gateway only accepts audio/wav (HTTP 400 / 415), fall back transparently to WAV!
                if (m4aCode != 400 && m4aCode != 415 && m4aCode != 422) {
                    throw IOException("STT 接口错误 ($m4aCode): ${extractErrorDetail(m4aResp)}")
                }
            }

            val (wavCode, wavResp) = sendAudioMultipart(wavFile, "audio/wav")
            if (wavCode !in 200..299) {
                throw IOException("STT 接口错误 ($wavCode): ${extractErrorDetail(wavResp)}")
            }
            return parseSttResponse(wavResp)
        } finally {
            runCatching { compressedM4a?.delete() }
        }
    }

    internal fun callOpenAiChatCompletion(
        config: LlmConfig,
        systemPrompt: String,
        userPrompt: String
    ): String = callOpenAiChatCompletionWithDiagnostics(config, systemPrompt, userPrompt).text

    internal fun callOpenAiChatCompletionWithDiagnostics(
        config: LlmConfig,
        systemPrompt: String,
        userPrompt: String
    ): ChatCallOutcome {
        val url = EndpointResolver.resolveChatEndpoint(config.baseUrl)
        val includeTemp = !isSamplingTemperatureDeprecated(config.model)
        val traceLines = mutableListOf<String>()
        traceLines.add("POST $url")
        traceLines.add("Model=${config.model.trim()} | ThinkingMode=${config.thinkingMode.name}")

        fun buildBody(
            withTemp: Boolean,
            thinkingOverride: String? // null = default applyThinkingControl, "none" = omit
        ): Pair<JsonObject, Boolean> {
            var injectedThinking = false
            val obj = JsonObject().apply {
                addProperty("model", config.model.trim())
                if (withTemp) {
                    addProperty("temperature", config.temperature)
                }
                addProperty("stream", false)
                when (thinkingOverride) {
                    null -> {
                        injectedThinking = applyThinkingControl(
                            body = this,
                            baseUrl = config.baseUrl,
                            model = config.model,
                            thinkingMode = config.thinkingMode
                        )
                    }
                    "reasoning_effort" -> {
                        addProperty(
                            "reasoning_effort",
                            if (config.thinkingMode == ThinkingMode.ENABLED) "high" else "minimal"
                        )
                        injectedThinking = true
                    }
                    "thinking_obj" -> {
                        add("thinking", JsonObject().apply {
                            addProperty(
                                "type",
                                if (config.thinkingMode == ThinkingMode.ENABLED) "enabled" else "disabled"
                            )
                        })
                        injectedThinking = true
                    }
                    "none" -> Unit
                }
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
            return obj to injectedThinking
        }

        var attemptNo = 0
        fun sendOnce(bodyObj: JsonObject): Pair<Int, String> {
            attemptNo += 1
            val paramTag = describeInjectedParams(bodyObj)
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(bodyObj.toString().toRequestBody(JSON_MEDIA_TYPE))
            if (config.apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
            }
            val t0 = System.nanoTime()
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                val text = resp.body?.string().orEmpty()
                if (resp.code in 200..299) {
                    traceLines.add("#$attemptNo [$paramTag] -> HTTP ${resp.code} (${ms} ms)")
                } else {
                    traceLines.add("#$attemptNo [$paramTag] -> HTTP ${resp.code} (${ms} ms): ${extractErrorDetail(text)}")
                }
                return resp.code to text
            }
        }

        val (initialBody, hadThinkingHint) = buildBody(
            withTemp = includeTemp,
            thinkingOverride = null
        )
        var (code, respText) = sendOnce(initialBody)

        // Step 1: Cross-syntax non-thinking fallback (e.g. `thinking.disabled` <-> `reasoning_effort=minimal`
        // for custom Volcengine `ep-...` or Seed 2.x proxies) so we NEVER accidentally run full CoT!
        if (code == 400 && hadThinkingHint && !respText.contains("temperature", ignoreCase = true)) {
            val altSyntax = alternateThinkingSyntaxForFallback(
                sentBody = initialBody,
                baseUrl = config.baseUrl,
                model = config.model,
                thinkingMode = config.thinkingMode
            )
            if (altSyntax != null) {
                val (altBody, _) = buildBody(withTemp = includeTemp, thinkingOverride = altSyntax)
                val altRetry = sendOnce(altBody)
                code = altRetry.first
                respText = altRetry.second
                if (code in 200..299) {
                    rememberPreferredThinkingSyntax(
                        config.baseUrl,
                        config.model,
                        config.thinkingMode,
                        altSyntax
                    )
                }
            }
        }

        // Step 2: Bare compatibility fallback if a strict reverse-proxy rejects all thinking hints or temperature
        if (code == 400 && (hadThinkingHint || includeTemp)) {
            val rejectTemp = includeTemp && respText.contains("temperature", ignoreCase = true)
            if (hadThinkingHint && !rejectTemp) {
                markThinkingHintUnsupported(config.baseUrl, config.model, config.thinkingMode)
            }
            val (fallbackBody, _) = buildBody(
                withTemp = if (rejectTemp) false else includeTemp,
                thinkingOverride = "none"
            )
            val retry = sendOnce(fallbackBody)
            code = retry.first
            respText = retry.second

            if (code == 400 && includeTemp && !rejectTemp) {
                val (bareBody, _) = buildBody(withTemp = false, thinkingOverride = "none")
                val secondRetry = sendOnce(bareBody)
                code = secondRetry.first
                respText = secondRetry.second
            }
        }

        if (code !in 200..299) {
            throw IOException(
                "LLM 接口错误 ($code): ${extractErrorDetail(respText)}\n--- 诊断日志 ---\n${traceLines.joinToString("\n")}"
            )
        }
        extractUsageDiagnostics(respText)?.let { traceLines.add(it) }
        return ChatCallOutcome(
            text = parseOpenAiChatResponse(respText),
            diagnosticLog = traceLines.joinToString("\n")
        )
    }

    internal fun callGeminiMultimodalAudio(
        config: MultimodalConfig,
        prompt: String,
        base64Wav: String,
        mimeType: String = "audio/wav"
    ): String {
        val parts = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("text", prompt)
            })
            add(JsonObject().apply {
                add("inline_data", JsonObject().apply {
                    addProperty("mime_type", mimeType)
                    addProperty("data", base64Wav)
                })
            })
        }
        return executeGeminiGenerateContentWithDiagnostics(config, parts).text
    }

    internal fun callGeminiTextOnly(
        config: MultimodalConfig,
        fullPrompt: String
    ): String = callGeminiTextOnlyWithDiagnostics(config, fullPrompt).text

    internal fun callGeminiTextOnlyWithDiagnostics(
        config: MultimodalConfig,
        fullPrompt: String
    ): ChatCallOutcome {
        val parts = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("text", fullPrompt)
            })
        }
        return executeGeminiGenerateContentWithDiagnostics(config, parts)
    }

    private fun executeGeminiGenerateContentWithDiagnostics(
        config: MultimodalConfig,
        parts: JsonArray
    ): ChatCallOutcome {
        val url = EndpointResolver.resolveGeminiEndpoint(config.baseUrl, config.model, config.apiKey)
        val maskedUrl = EndpointResolver.resolveGeminiEndpoint(
            config.baseUrl,
            config.model,
            if (config.apiKey.isBlank()) "" else "***"
        )
        val genConfig = buildGeminiGenerationConfig(
            model = config.model,
            temperature = config.temperature,
            thinkingMode = config.thinkingMode,
            includeThinkingHint = true
        )
        val traceLines = mutableListOf<String>()
        traceLines.add("POST $maskedUrl")
        traceLines.add("Model=${config.model.trim()} | ThinkingMode=${config.thinkingMode.name}")

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

        var attemptNo = 0
        fun sendOnce(payload: JsonObject, label: String): Pair<Int, String> {
            attemptNo += 1
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            if (config.apiKey.isNotBlank()) {
                reqBuilder.header("x-goog-api-key", config.apiKey.trim())
            }
            val t0 = System.nanoTime()
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                val text = resp.body?.string().orEmpty()
                if (resp.code in 200..299) {
                    traceLines.add("#$attemptNo [$label] -> HTTP ${resp.code} (${ms} ms)")
                } else {
                    traceLines.add("#$attemptNo [$label] -> HTTP ${resp.code} (${ms} ms): ${extractErrorDetail(text)}")
                }
                return resp.code to text
            }
        }

        var (code, respText) = sendOnce(buildPayload(genConfig), genConfig?.toString() ?: "bare")
        if (code == 400 && genConfig != null) {
            val retry = sendOnce(buildPayload(overrideGenConfig = null), "bare-fallback")
            code = retry.first
            respText = retry.second
        }

        if (code !in 200..299) {
            throw IOException(
                "Gemini 接口错误 ($code): ${extractErrorDetail(respText)}\n--- 诊断日志 ---\n${traceLines.joinToString("\n")}"
            )
        }
        return ChatCallOutcome(
            text = parseGeminiResponse(respText),
            diagnosticLog = traceLines.joinToString("\n")
        )
    }

    internal fun callOpenAiMultimodalAudio(
        config: MultimodalConfig,
        prompt: String,
        base64Wav: String
    ): String {
        val url = EndpointResolver.resolveChatEndpoint(config.baseUrl)
        val includeTemp = !isSamplingTemperatureDeprecated(config.model)

        fun buildBody(withTemp: Boolean, withThinkingHint: Boolean): Pair<JsonObject, Boolean> {
            var injectedThinking = false
            val obj = JsonObject().apply {
                addProperty("model", config.model.trim())
                if (withTemp) {
                    addProperty("temperature", config.temperature)
                }
                if (withThinkingHint) {
                    injectedThinking = applyThinkingControl(
                        body = this,
                        baseUrl = config.baseUrl,
                        model = config.model,
                        thinkingMode = config.thinkingMode
                    )
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
            return obj to injectedThinking
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

        val (initialBody, hadThinkingHint) = buildBody(includeTemp, withThinkingHint = true)
        var (code, respText) = sendOnce(initialBody)
        if (code == 400 && (hadThinkingHint || includeTemp)) {
            val rejectTemp = includeTemp && respText.contains("temperature", ignoreCase = true)
            if (hadThinkingHint && !rejectTemp) {
                markThinkingHintUnsupported(config.baseUrl, config.model, config.thinkingMode)
            }
            val (fallbackBody, _) = buildBody(
                withTemp = if (rejectTemp) false else includeTemp,
                withThinkingHint = false
            )
            val retry = sendOnce(fallbackBody)
            code = retry.first
            respText = retry.second
        }
        if (code !in 200..299) {
            throw IOException("多模态接口错误 ($code): ${extractErrorDetail(respText)}")
        }
        return parseOpenAiChatResponse(respText)
    }

    companion object {
        private const val PREWARM_COOLDOWN_MS = 25_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SPECIAL_TOKEN_REGEX = Regex("<\\|[^|]+\\|>")
        private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
        private val unsupportedThinkingHintKeys: MutableSet<String> =
            Collections.newSetFromMap(ConcurrentHashMap())
        private val preferredThinkingSyntaxCache: ConcurrentHashMap<String, String> =
            ConcurrentHashMap()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(6, 90, TimeUnit.SECONDS))
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private fun thinkingCacheKey(baseUrl: String, model: String, mode: ThinkingMode): String =
            "${baseUrl.trim().lowercase()}|${model.trim().lowercase()}|${mode.name}"

        internal fun markThinkingHintUnsupported(baseUrl: String, model: String, mode: ThinkingMode) {
            unsupportedThinkingHintKeys.add(thinkingCacheKey(baseUrl, model, mode))
        }

        internal fun rememberPreferredThinkingSyntax(
            baseUrl: String,
            model: String,
            mode: ThinkingMode,
            syntax: String
        ) {
            preferredThinkingSyntaxCache[thinkingCacheKey(baseUrl, model, mode)] = syntax
        }

        fun clearUnsupportedThinkingCache() {
            unsupportedThinkingHintKeys.clear()
            preferredThinkingSyntaxCache.clear()
        }

        internal fun alternateThinkingSyntaxForFallback(
            sentBody: JsonObject,
            baseUrl: String,
            model: String,
            thinkingMode: ThinkingMode
        ): String? {
            if (thinkingMode == ThinkingMode.AUTO) return null
            val lowerUrl = baseUrl.trim().lowercase()
            val code = normalizeModelCode(model)
            val isDoubaoOrArk = lowerUrl.contains("volces.com") ||
                code.startsWith("doubao") ||
                code.startsWith("ep-")
            return when {
                sentBody.has("thinking") && isDoubaoOrArk -> "reasoning_effort"
                sentBody.has("reasoning_effort") && isDoubaoOrArk -> "thinking_obj"
                else -> null
            }
        }

        internal fun describeInjectedParams(body: JsonObject): String {
            val parts = mutableListOf<String>()
            body.get("reasoning_effort")?.takeIf { it.isJsonPrimitive }?.asString?.let {
                parts.add("reasoning_effort=$it")
            }
            body.getAsJsonObject("thinking")?.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.let {
                parts.add("thinking.type=$it")
            }
            body.get("enable_thinking")?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                parts.add("enable_thinking=$it")
            }
            body.get("temperature")?.takeIf { it.isJsonPrimitive }?.asFloat?.let {
                parts.add("temp=$it")
            }
            return if (parts.isEmpty()) "bare" else parts.joinToString(", ")
        }

        internal fun extractUsageDiagnostics(respBody: String): String? {
            return try {
                val root = JsonParser.parseString(respBody.trim()).asJsonObject
                val usage = root.getAsJsonObject("usage")
                val choices = root.getAsJsonArray("choices")
                val msg = if (choices != null && choices.size() > 0) {
                    choices[0].asJsonObject.getAsJsonObject("message")
                } else null
                val reasoningTextLen = msg?.get("reasoning_content")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.length ?: 0

                if (usage == null && reasoningTextLen == 0) return null

                val promptTok = usage?.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                val cachedTok = usage?.getAsJsonObject("prompt_tokens_details")
                    ?.get("cached_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                    ?: usage?.get("prompt_cache_hit_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                    ?: 0
                val compTok = usage?.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                val reasoningTok = usage?.getAsJsonObject("completion_tokens_details")
                    ?.get("reasoning_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                    ?: 0

                buildString {
                    append("Tokens: in=$promptTok (cache=$cachedTok) | out=$compTok | reasoning_tokens=$reasoningTok")
                    if (reasoningTextLen > 0) {
                        append(" (reasoning_chars=$reasoningTextLen)")
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        internal fun formatProbeHttpError(code: Int): String = when (code) {
            401 -> "鉴权失败 (401)"
            403 -> "未开通/无权限 (403)"
            404 -> "不可用 (404)"
            429 -> "限流 (429)"
            in 500..599 -> "服务端错误 ($code)"
            else -> "HTTP $code"
        }

        /**
         * Universal user-controlled thinking mode injector across Volcengine Ark / Doubao Seed,
         * Aliyun Bailian, SiliconFlow, Qwen3+, Zhipu GLM-4.5+, DeepSeek V3.1+, OpenAI o-series/GPT-5,
         * and custom OpenAI-compatible gateways.
         *
         * Automatically skips any `(baseUrl, model, thinkingMode)` tuple previously recorded as
         * rejecting thinking fields with HTTP 400.
         */
        fun applyThinkingControl(
            body: JsonObject,
            baseUrl: String,
            model: String,
            thinkingMode: ThinkingMode
        ): Boolean {
            if (thinkingMode == ThinkingMode.AUTO) return false
            val lowerUrl = baseUrl.trim().lowercase()
            val rawLowerModel = model.trim().lowercase()
            val code = normalizeModelCode(model)
            if (code.isEmpty()) return false

            val cacheKey = thinkingCacheKey(baseUrl, model, thinkingMode)
            if (cacheKey in unsupportedThinkingHintKeys) {
                return false
            }

            // Dedicated reasoning-only models always think natively and reject non-thinking flags
            if (code == "deepseek-reasoner" ||
                code.startsWith("qwq") ||
                code.contains("-thinking") ||
                code.endsWith("thinking")
            ) {
                return false
            }

            val enableFlag = thinkingMode == ThinkingMode.ENABLED

            // Honor learned syntax override (e.g. custom Volcengine `ep-...` resolved on 1st call)
            preferredThinkingSyntaxCache[cacheKey]?.let { syntax ->
                when (syntax) {
                    "reasoning_effort" -> {
                        body.addProperty("reasoning_effort", if (enableFlag) "high" else "minimal")
                        return true
                    }
                    "thinking_obj" -> {
                        body.add("thinking", JsonObject().apply {
                            addProperty("type", if (enableFlag) "enabled" else "disabled")
                        })
                        return true
                    }
                }
            }

            // 1a. Doubao Seed 2.0 / 2.1 / 3.x family: uses `reasoning_effort = "minimal"` to turn off CoT
            // (sending `thinking.type = "disabled"` returns HTTP 400 on several Seed 2.x models!)
            val isDoubaoSeed2Plus = code.startsWith("doubao-seed-2") ||
                code.startsWith("doubao-seed-3") ||
                code.startsWith("doubao-2") ||
                code.startsWith("doubao-3")
            if (isDoubaoSeed2Plus) {
                body.addProperty(
                    "reasoning_effort",
                    if (enableFlag) "high" else "minimal"
                )
                return true
            }

            // 1b. OpenAI o1 / o3 / o4 / GPT-5 reasoning_effort control
            val isOpenAiReasoning = code == "o1" || code.startsWith("o1-") ||
                code == "o3" || code.startsWith("o3-") ||
                code == "o4" || code.startsWith("o4-") ||
                code.startsWith("gpt-5")
            if (isOpenAiReasoning) {
                body.addProperty(
                    "reasoning_effort",
                    if (enableFlag) "high" else "low"
                )
                return true
            }

            // 2. Qwen3+ / Bailian DashScope / SiliconFlow `enable_thinking` boolean family
            val isQwenHybrid = (code.startsWith("qwen") &&
                !code.startsWith("qwen2") &&
                !code.startsWith("qwen1")) ||
                rawLowerModel.contains("qwen3") ||
                rawLowerModel.contains("qwen4")
            val isDashScopeHybrid = lowerUrl.contains("dashscope.aliyuncs.com") &&
                !code.startsWith("qwen2") &&
                !code.startsWith("qwen1")
            val isSiliconFlowHybrid = lowerUrl.contains("siliconflow") && (
                enableFlag ||
                    isQwenHybrid ||
                    rawLowerModel.contains("deepseek-v3.1") ||
                    rawLowerModel.contains("deepseek-v3.2") ||
                    code.startsWith("hunyuan") ||
                    code.startsWith("glm-4.5") ||
                    code.startsWith("glm-4.6") ||
                    code.startsWith("glm-4.7") ||
                    code.startsWith("glm-5")
                )

            if (isQwenHybrid || isDashScopeHybrid || isSiliconFlowHybrid) {
                body.addProperty("enable_thinking", enableFlag)
                return true
            }

            // 3. Volcengine Ark (Seed 1.6 / `ep-...`) / Zhipu GLM-4.5+ / DeepSeek V3.1+ `thinking.type` family
            val isVolcengineHybrid = lowerUrl.contains("volces.com") &&
                !code.startsWith("doubao-1-5") &&
                !code.startsWith("doubao-pro") &&
                !code.startsWith("doubao-lite") &&
                !code.startsWith("deepseek-r1")
            val isDoubaoSeed1Model = code.startsWith("doubao-seed")
            val isZhipuHybrid = (code.startsWith("glm-") &&
                !code.startsWith("glm-3") &&
                !code.startsWith("glm-4-") &&
                code != "glm-4") ||
                (lowerUrl.contains("bigmodel.cn") &&
                    !code.startsWith("glm-4-flash") &&
                    !code.startsWith("glm-4-air") &&
                    code != "glm-4")
            val isDeepSeekHybrid = code.startsWith("deepseek-v3.1") ||
                code.startsWith("deepseek-v3-1") ||
                code.startsWith("deepseek-v3.2") ||
                code.startsWith("deepseek-v3-2") ||
                (enableFlag && code == "deepseek-chat")

            if (isVolcengineHybrid || isDoubaoSeed1Model || isZhipuHybrid || isDeepSeekHybrid) {
                body.add("thinking", JsonObject().apply {
                    addProperty("type", if (enableFlag) "enabled" else "disabled")
                })
                return true
            }

            // 4. Classic pure non-thinking models need no extra field when DISABLED
            val isKnownNonThinkingModel = code == "deepseek-chat" ||
                code.startsWith("gpt-4o") ||
                code.startsWith("gpt-4.1") ||
                code.startsWith("gpt-3.5") ||
                code.startsWith("llama") ||
                code.startsWith("mistral") ||
                code.startsWith("gemma") ||
                code.startsWith("qwen2") ||
                code.startsWith("glm-4-") ||
                code == "glm-4" ||
                code.startsWith("doubao-1-5") ||
                code.startsWith("doubao-lite") ||
                code.startsWith("doubao-pro") ||
                code.startsWith("gemini-") ||
                isLocalEndpoint(lowerUrl)

            if (!enableFlag && isKnownNonThinkingModel) {
                return false
            }

            // 5. Universal fallback for custom/future models (protected by HTTP 400 auto-retry + cache)
            body.add("thinking", JsonObject().apply {
                addProperty("type", if (enableFlag) "enabled" else "disabled")
            })
            return true
        }

        fun applyNonThinkingHints(
            body: JsonObject,
            baseUrl: String,
            model: String
        ): Boolean = applyThinkingControl(body, baseUrl, model, ThinkingMode.DISABLED)

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
            if (code.contains("tts") || code.contains("live") || code.contains("image") || code.contains("transcribe")) {
                return null
            }
            if (code.startsWith("gemini-3") && code.contains("lite")) {
                return "MINIMAL"
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
            thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
            includeThinkingHint: Boolean = true
        ): JsonObject? {
            val cfg = JsonObject()
            if (!isSamplingTemperatureDeprecated(model)) {
                cfg.addProperty("temperature", temperature)
            }
            if (includeThinkingHint && thinkingMode != ThinkingMode.AUTO) {
                val code = normalizeModelCode(model)
                when (thinkingMode) {
                    ThinkingMode.DISABLED -> {
                        recommendedGeminiThinkingLevel(model)?.let { level ->
                            cfg.add("thinkingConfig", JsonObject().apply {
                                addProperty("thinkingLevel", level)
                            })
                        }
                    }
                    ThinkingMode.ENABLED -> {
                        if (!code.contains("tts") && !code.contains("live") && !code.contains("image")) {
                            when {
                                code.startsWith("gemini-3") || code.startsWith("gemini-4") -> {
                                    cfg.add("thinkingConfig", JsonObject().apply {
                                        addProperty("thinkingLevel", "HIGH")
                                    })
                                }
                                code.startsWith("gemini-2.5") -> {
                                    cfg.add("thinkingConfig", JsonObject().apply {
                                        addProperty("thinkingBudget", 1024)
                                    })
                                }
                            }
                        }
                    }
                    ThinkingMode.AUTO -> Unit
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
                        badge = "🔊 语音转写"
                    }
                }
                ModelCapabilitySlot.LLM -> {
                    // Universal chat eligibility: filter out non-chat utility/embedding/STT/live models,
                    // while letting real live-probed `ms` latency badges replace hardcoded speed strings.
                    val isPureStt = lower.contains("whisper") || lower.contains("sensevoice") || lower.endsWith("-asr")
                    if (!isNonTextOrUtility && !isPureStt && !lower.contains("live")) {
                        recommended = true
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
                            badge = "🎙 原生音频"
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
                    code == "gemini-3.6-flash" -> 92
                    code == "gemini-3.8-flash" -> 90
                    code == "gpt-4o-mini-audio-preview" -> 88
                    code.startsWith("gemini-") -> 75
                    else -> 10
                }
                ModelCapabilitySlot.LLM -> when {
                    code.startsWith("doubao-seed-2") && code.contains("lite") -> 104
                    code.startsWith("doubao-seed-2") && code.contains("turbo") -> 103
                    code.startsWith("doubao-seed") && code.contains("flash") -> 102
                    code.startsWith("qwen-turbo") -> 101
                    code == "deepseek-chat" -> 100
                    code == "gemini-3.5-flash-lite" -> 98
                    code == "gemini-2.5-flash" -> 96
                    code == "gemini-3.6-flash" -> 94
                    code == "gemini-3.8-flash" -> 93
                    code == "gpt-4o-mini" -> 92
                    code.startsWith("qwen3-8b") -> 91
                    else -> 10
                }
                ModelCapabilitySlot.STT -> when {
                    code.contains("paraformer-realtime") -> 102
                    code.contains("sensevoice") -> 100
                    code.contains("whisper-large-v3-turbo") -> 98
                    code.contains("gpt-4o-mini-transcribe") -> 96
                    else -> 10
                }
            }
        }

        const val CHUNKED_POLISH_THRESHOLD_CHARS: Int = 600
        const val MAX_CONCURRENT_POLISH_CHUNKS: Int = 3

        private val PARAGRAPH_BREAK_CHARS = charArrayOf('\n', '\r')
        private val SENTENCE_END_PUNCTUATION = charArrayOf('。', '！', '？', '；', '.', '!', '?', ';')
        private val CLAUSE_PUNCTUATION = charArrayOf('，', '、', '：', ',', ':')

        /**
         * Splits long dictation transcripts exceeding [thresholdChars] (default 600 chars) into
         * ~300-600 character chunks along natural paragraph or sentence boundaries so Stage-2 LLM
         * polishing can run up to [MAX_CONCURRENT_POLISH_CHUNKS] (3) requests concurrently.
         */
        fun splitLongTextForChunkedPolish(
            text: String,
            thresholdChars: Int = CHUNKED_POLISH_THRESHOLD_CHARS
        ): List<String> {
            val safeThreshold = thresholdChars.coerceAtLeast(50)
            if (text.isEmpty()) return emptyList()
            if (text.length <= safeThreshold) return listOf(text)

            val minPreferredLen = (safeThreshold / 2).coerceAtLeast(1)
            val chunks = mutableListOf<String>()
            var remaining = text

            while (remaining.length > safeThreshold) {
                val window = remaining.substring(0, safeThreshold)
                val splitIdx = findBestChunkBoundary(window, minPreferredLen, safeThreshold)
                chunks.add(remaining.substring(0, splitIdx))
                remaining = remaining.substring(splitIdx)
            }
            if (remaining.isNotEmpty()) {
                chunks.add(remaining)
            }
            return chunks
        }

        private fun findBestChunkBoundary(
            window: String,
            minPreferredLen: Int,
            maxLen: Int
        ): Int {
            // 1. Prefer paragraph newline in [minPreferredLen, maxLen)
            val paraIdx = window.lastIndexOfAny(PARAGRAPH_BREAK_CHARS)
            if (paraIdx >= minPreferredLen) {
                return paraIdx + 1
            }
            // 2. Prefer sentence-ending punctuation (。！？；.!?; ) in [minPreferredLen, maxLen)
            val sentIdx = window.lastIndexOfAny(SENTENCE_END_PUNCTUATION)
            if (sentIdx >= minPreferredLen) {
                return sentIdx + 1
            }
            // 3. Fallback to clause punctuation (，、：,:) in [minPreferredLen, maxLen)
            val clauseIdx = window.lastIndexOfAny(CLAUSE_PUNCTUATION)
            if (clauseIdx >= minPreferredLen) {
                return clauseIdx + 1
            }
            // 4. Fallback to any sentence/clause punctuation earlier in window
            val anySentIdx = maxOf(paraIdx, sentIdx, clauseIdx)
            if (anySentIdx > 0) {
                return anySentIdx + 1
            }
            // 5. Hard split at maxLen
            return maxLen.coerceAtMost(window.length)
        }
    }
}
