package com.typefree.ime.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.typefree.core.offline.AiTask
import com.typefree.core.offline.AsrRoute
import com.typefree.core.offline.ExecutionSource
import com.typefree.core.offline.LocalModelRegistrySnapshot
import com.typefree.core.offline.LocalModelSlot
import com.typefree.core.offline.LocalPromptPair
import com.typefree.core.offline.LocalPromptTemplates
import com.typefree.core.offline.OfflineMode
import com.typefree.core.offline.OfflineModelCatalog
import com.typefree.core.offline.RouteBlockReason
import com.typefree.core.offline.RoutePlan
import com.typefree.core.offline.TextRoute
import com.typefree.ime.audio.AacAudioCompressor
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DiscoveredModelItem
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.LocalDictionaryPostProcessor
import com.typefree.ime.data.ModelCapabilitySlot
import com.typefree.ime.data.ModelProbeResult
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.OfflineRouteBridge
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SnippetMatcher
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.ThinkingMode
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.UnifiedProviderCatalog
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.data.stats.TokenUsage
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.ime.jev.ChatTopology
import com.typefree.ime.ime.jev.JevCopilotResult
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.ime.jev.JevResponseParser
import com.typefree.ime.ime.jev.memory.JevMemoryPromptPayload
import com.typefree.ime.localai.GenRequest
import com.typefree.ime.localai.LocalAiServiceClient
import com.typefree.ime.localai.LocalModelRegistry
import com.typefree.ime.localai.LocalSlmRuntimeResult
import com.typefree.ime.localai.PRIORITY_COMPLETION
import com.typefree.ime.localai.PRIORITY_EDIT_WRITE
import com.typefree.ime.localai.PRIORITY_VOICE
import com.typefree.ime.privacy.RedactionSession
import com.typefree.ime.network.asr.AsrSessionLimits
import com.typefree.ime.network.asr.DashScopeStreamingAsrClient
import com.typefree.ime.network.asr.GeminiLiveTranscribeClient
import com.typefree.ime.network.asr.RotatingStreamingAsrSession
import com.typefree.ime.network.asr.StreamingAsrClient
import com.typefree.ime.network.asr.StreamingAsrSession
import com.typefree.ime.network.asr.VolcengineSaucAsrClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    val diagnosticLog: String = "",
    val executionSource: ExecutionSource = ExecutionSource.CLOUD,
    val localMetricsSummary: String = "",
    val tokenUsage: TokenUsage? = null,
    val modelName: String = ""
)

internal data class ChatCallOutcome(
    val text: String,
    val diagnosticLog: String,
    val tokenUsage: TokenUsage? = null,
    val modelName: String = ""
)

class AiPipelineEngine(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val dashScopeAsrClient: StreamingAsrClient = DashScopeStreamingAsrClient(httpClient),
    private val volcengineAsrClient: StreamingAsrClient = VolcengineSaucAsrClient(httpClient),
    private val geminiLiveAsrClient: StreamingAsrClient = GeminiLiveTranscribeClient(httpClient),
    private val localAiClient: LocalAiServiceClient? = null,
    private val localModelRegistry: LocalModelRegistry? = null
) {

    private val lastPrewarmedByOrigin = ConcurrentHashMap<String, Long>()
    private val activeLocalCompletionJob = AtomicReference<Job?>(null)

    private fun currentRegistrySnapshot(): LocalModelRegistrySnapshot {
        return localModelRegistry?.snapshot?.value ?: LocalModelRegistrySnapshot()
    }

    fun resolveRoutePlan(
        task: AiTask,
        settings: AppSettings,
        isNetworkAvailable: Boolean = true,
        isSecureField: Boolean = false
    ): RoutePlan {
        return OfflineRouteBridge.resolvePlan(
            task = task,
            settings = settings,
            registrySnapshot = currentRegistrySnapshot(),
            isNetworkAvailable = isNetworkAvailable,
            isSecureField = isSecureField,
            isIncognito = settings.manualIncognitoMode
        )
    }

    private suspend fun resolveRoutePlanEnsuringLocalService(
        task: AiTask,
        settings: AppSettings,
        isNetworkAvailable: Boolean = true,
        isSecureField: Boolean = false
    ): RoutePlan {
        val client = localAiClient
        if (settings.offlineConfig.mode != OfflineMode.OFF && client != null && !client.isServiceAlive.value) {
            val revived = client.ensureAlive(timeoutMs = 2_000L)
            if (revived) {
                localModelRegistry?.updateLocalServiceAlive(true)
            }
        }
        return resolveRoutePlan(
            task = task,
            settings = settings,
            isNetworkAvailable = isNetworkAvailable,
            isSecureField = isSecureField
        )
    }

    fun isEgressAllowed(
        settings: AppSettings,
        task: AiTask = AiTask.DICTATE
    ): Boolean {
        return resolveRoutePlan(task = task, settings = settings).egressAllowed
    }

    fun preBindLocalAiIfNeeded(
        settings: AppSettings,
        prewarmAsr: Boolean = false,
        prewarmSlm: Boolean = false
    ) {
        val offline = settings.offlineConfig
        if (offline.mode == OfflineMode.OFF) return
        val client = localAiClient ?: return
        client.preBind()
        val registry = localModelRegistry ?: return
        if (prewarmAsr && offline.asrSlotOn) {
            val asrId = offline.selectedAsrModelId
            val asrDir = registry.getInstalledModelDir(asrId)
            if (!asrId.isNullOrBlank() && !asrDir.isNullOrBlank()) {
                client.prewarmSlot(LocalModelSlot.ASR, asrId, asrDir)
            }
        }
        if (prewarmSlm && offline.slmSlotOn && registry.isSlmEngineAvailable) {
            val slmId = offline.selectedSlmModelId
            val slmDir = registry.getInstalledModelDir(slmId)
            if (!slmId.isNullOrBlank() && !slmDir.isNullOrBlank()) {
                client.prewarmSlot(LocalModelSlot.SLM, slmId, slmDir)
            }
        }
    }

    fun cancelActiveLocalCompletion() {
        activeLocalCompletionJob.getAndSet(null)?.cancel()
    }

    /**
     * Asynchronously warms up DNS + TCP + TLS 1.3 sockets into [httpClient]'s [ConnectionPool]
     * right when the user presses down on the microphone button, hiding 150-350ms of cold
     * connection setup inside the first ~300ms of speaking time.
     *
     * Respects [RoutePlan.egressAllowed]: in [OfflineMode.OFFLINE_ONLY], exits immediately
     * without issuing any network requests (including LAN/Ollama endpoints).
     */
    suspend fun prewarmEndpoints(settings: AppSettings) = withContext(Dispatchers.IO) {
        val plan = resolveRoutePlan(AiTask.DICTATE, settings)
        if (settings.offlineConfig.mode != OfflineMode.OFF) {
            preBindLocalAiIfNeeded(
                settings = settings,
                prewarmAsr = plan.asr is AsrRoute.Local,
                prewarmSlm = plan.text is TextRoute.Local
            )
        }
        if (!plan.egressAllowed) return@withContext

        val candidateUrls = buildList {
            when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> {
                    if (plan.asr !is AsrRoute.Local) {
                        val effectiveStt = resolveEffectiveSttConfig(settings)
                        if ((effectiveStt.protocol == SttProtocol.OPENAI_HTTP ||
                                effectiveStt.protocol == SttProtocol.GEMINI_LIVE_WS) &&
                            effectiveStt.baseUrl.isNotBlank() &&
                            !isLocalEndpoint(effectiveStt.baseUrl)
                        ) {
                            add(effectiveStt.baseUrl)
                        }
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
            if (settings.jevCopilotEnabled) {
                if (settings.jevUseDedicatedModel) {
                    resolveEffectiveJevLlmConfig(settings)?.baseUrl?.takeIf {
                        it.isNotBlank() && !isLocalEndpoint(it)
                    }?.let { add(it) }
                } else {
                    resolveAvailableGeminiVisionConfig(settings)?.baseUrl?.takeIf {
                        it.isNotBlank() && !isLocalEndpoint(it)
                    }?.let { add(it) }
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
     *
     * Respects [RoutePlan.egressAllowed] and prefers local `:local_ai` prebind/prewarm when
     * offline ASR is active.
     */
    fun prewarmLiveStreamingIfNeeded(settings: AppSettings) {
        val plan = resolveRoutePlan(AiTask.DICTATE, settings)
        if (plan.asr is AsrRoute.Local) {
            preBindLocalAiIfNeeded(settings, prewarmAsr = true, prewarmSlm = false)
            return
        }
        if (!plan.egressAllowed) return
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
     * Opens a live streaming ASR session guided by [RoutePlan]:
     * - When [AsrRoute.Local] is active, opens an on-device PFD-pipe session via [localAiClient]
     *   regardless of cloud `pipelineMode` or `sttConfig.protocol`.
     * - When [AsrRoute.Cloud] is active and egress is allowed, opens the configured cloud WS session.
     * - Returns `null` for HTTP batch mode or when no streaming ASR route is available.
     */
    fun openLiveStreamingSession(
        settings: AppSettings,
        routePlan: RoutePlan? = null
    ): StreamingAsrSession? {
        val plan = routePlan ?: resolveRoutePlan(AiTask.DICTATE, settings)
        return when (val asr = plan.asr) {
            is AsrRoute.Local -> {
                val client = localAiClient ?: return null
                val modelDir = localModelRegistry?.getInstalledModelDir(asr.modelId) ?: return null
                val hotwords = PromptBuilder.extractHotwordTerms(settings.dictionary)
                val defaultLangHint = if (AppStrings.isChinese(settings.appLanguage)) "zh" else "en"
                client.openStreamingAsrSession(
                    modelId = asr.modelId,
                    modelDirPath = modelDir,
                    hotwords = hotwords,
                    languageHint = settings.sttConfig.language.ifBlank { defaultLangHint },
                    incognito = plan.clearLocalStateAfterRun,
                    expectedGeneration = 0L
                )
            }
            is AsrRoute.Cloud -> {
                if (!plan.egressAllowed) return null
                if (settings.pipelineMode != PipelineMode.DUAL_STAGE) return null
                val sttCfg = settings.sttConfig
                val hotwords = PromptBuilder.extractHotwordTerms(settings.dictionary)
                val limit = AsrSessionLimits.forProtocol(sttCfg.protocol)
                when (sttCfg.protocol) {
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
            is AsrRoute.None -> null
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
        chatContextMessages: List<String> = emptyList(),
        chatContextScreenshotBase64: String = "",
        attitudeInstruction: String = "",
        editorDraft: String = "",
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
        val task = effectiveIntent.toAiTask()
        val plan = resolveRoutePlanEnsuringLocalService(task = task, settings = settings)
        val planAsr = plan.asr
        val planText = plan.text

        try {
            // Strict egress lock or offline-mode block before any cloud code runs
            if (!plan.egressAllowed) {
                if (planAsr is AsrRoute.None) {
                    val reason = (planText as? TextRoute.Blocked)?.reason ?: RouteBlockReason.OFFLINE_ONLY_NO_MODEL
                    return@withContext Result.failure(
                        IllegalStateException(routeBlockMessage(reason, "请先在「离线模型」中下载并启用本机语音模型"))
                    )
                }
                if (planText is TextRoute.Blocked) {
                    return@withContext Result.failure(
                        IllegalStateException(routeBlockMessage(planText.reason, "纯离线锁已开启，当前任务不支持离线执行"))
                    )
                }
            } else if (settings.offlineConfig.mode != OfflineMode.OFF &&
                planAsr is AsrRoute.None &&
                planText is TextRoute.Blocked
            ) {
                return@withContext Result.failure(
                    IllegalStateException(routeBlockMessage(planText.reason, "尚未配置云端 API 或启用本机离线模型"))
                )
            }

            // Local ASR or Local SLM hybrid/offline path
            if (planAsr is AsrRoute.Local ||
                planText is TextRoute.Local ||
                planText is TextRoute.CloudMultimodalTextFallback
            ) {
                return@withContext executeRoutedVoicePipeline(
                    plan = plan,
                    wavFile = wavFile,
                    settings = settings,
                    activeStyle = activeStyle,
                    targetAppName = targetAppName,
                    selectedText = selectedText,
                    safeContext = safeContext,
                    effectiveIntent = effectiveIntent,
                    translateTargetLanguage = translateTargetLanguage,
                    preTranscribedText = preTranscribedText,
                    sttTailWaitMs = sttTailWaitMs,
                    chatContextMessages = chatContextMessages,
                    chatContextScreenshotBase64 = chatContextScreenshotBase64,
                    attitudeInstruction = attitudeInstruction,
                    editorDraft = editorDraft,
                    startMs = startMs,
                    onStageChanged = onStageChanged
                )
            }

            // Cloud-only path (requires egressAllowed == true)
            if (!plan.egressAllowed) {
                return@withContext Result.failure(
                    IllegalStateException(routeBlockMessage(RouteBlockReason.OFFLINE_ONLY_NO_MODEL, "纯离线锁已开启"))
                )
            }

            when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> {
                    val sttCfg = resolveEffectiveSttConfig(settings)
                    val usedLiveStream: Boolean
                    val sttElapsedMs: Long

                    val rawTranscript = if (!preTranscribedText.isNullOrBlank()) {
                        usedLiveStream = true
                        sttElapsedMs = sttTailWaitMs.coerceAtLeast(0L)
                        cleanRawTranscript(preTranscribedText)
                    } else {
                        usedLiveStream = false
                        validateSttConfig(sttCfg, settings)?.let { errMsg ->
                            return@withContext Result.failure(IllegalStateException(errMsg))
                        }

                        onStageChanged(PipelineStage.TRANSCRIBING_STT)
                        val sttStart = System.currentTimeMillis()
                        val isZh = AppStrings.isChinese(settings.appLanguage)
                        val sttBias = PromptBuilder.buildSttBiasPrompt(settings.dictionary, isChineseLocale = isZh)
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
                        SttProtocol.OPENAI_HTTP -> if (EndpointResolver.isDeepSeekAsrEndpoint(sttCfg.baseUrl) ||
                            sttCfg.presetId == "deepseek_asr" ||
                            sttCfg.presetId == "deepseek"
                        ) {
                            "DeepSeek ASR"
                        } else {
                            sttCfg.model.substringAfterLast('/').ifBlank { "HTTP STT" }
                        }
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

                    val isZh = AppStrings.isChinese(settings.appLanguage)
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
                                            surroundingBeforeCursor = chunkContext,
                                            isChineseLocale = isZh
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
                            }.joinToString("\n"),
                            tokenUsage = sumTokenUsages(chunkOutcomes.map { it.tokenUsage }),
                            modelName = chunkOutcomes.firstOrNull { it.modelName.isNotBlank() }?.modelName
                                ?: llmCfg.model.trim()
                        )
                    } else {
                        val fallbackGeminiCfg = resolveAvailableGeminiVisionConfig(settings)
                        val canSendVision = canRouteVisionScreenshot(
                            primaryBaseUrl = llmCfg.baseUrl,
                            primaryModel = llmCfg.model,
                            fallbackGeminiConfig = fallbackGeminiCfg
                        )
                        val activeImageB64 = if (effectiveIntent == VoiceIntentMode.HELP_ME_WRITE && canSendVision) {
                            chatContextScreenshotBase64
                        } else {
                            ""
                        }
                        val (sysPrompt, usrPrompt) = when (effectiveIntent) {
                            VoiceIntentMode.SPEAK_TO_EDIT -> PromptBuilder.buildSpeakToEditPrompts(
                                selectedText = selectedText,
                                voiceInstruction = rawTranscript,
                                style = activeStyle,
                                dictionary = settings.dictionary,
                                isChineseLocale = isZh
                            )
                            VoiceIntentMode.HELP_ME_WRITE -> PromptBuilder.buildHelpMeWritePrompts(
                                voiceInstruction = PromptBuilder.combineAttitudeAndVoiceInstruction(
                                    attitudeInstruction = attitudeInstruction,
                                    voiceTranscript = rawTranscript,
                                    isChineseLocale = isZh
                                ),
                                style = activeStyle,
                                targetAppName = targetAppName,
                                dictionary = settings.dictionary,
                                surroundingBeforeCursor = safeContext,
                                chatContextMessages = chatContextMessages,
                                hasChatScreenshot = activeImageB64.isNotBlank(),
                                editorDraft = editorDraft,
                                isChineseLocale = isZh
                            )
                            VoiceIntentMode.JEV_COPILOT -> PromptBuilder.buildJevCopilotPrompts(
                                domainProfile = JevDomainProfile.FORMAL_WORKFLOW,
                                targetAppName = targetAppName,
                                chatContextMessages = chatContextMessages,
                                hasChatScreenshot = activeImageB64.isNotBlank(),
                                userBottomLine = rawTranscript,
                                surroundingBeforeCursor = safeContext,
                                style = activeStyle,
                                dictionary = settings.dictionary,
                                isChineseLocale = isZh
                            )
                            VoiceIntentMode.TRANSLATE -> PromptBuilder.buildTranslatePrompts(
                                rawSpeech = rawTranscript,
                                targetLanguage = translateTargetLanguage,
                                style = activeStyle,
                                dictionary = settings.dictionary,
                                isChineseLocale = isZh
                            )
                            VoiceIntentMode.DICTATE -> PromptBuilder.buildDictationPrompts(
                                rawSpeech = rawTranscript,
                                style = activeStyle,
                                targetAppName = targetAppName,
                                dictionary = settings.dictionary,
                                surroundingBeforeCursor = safeContext,
                                isChineseLocale = isZh
                            )
                        }
                        executeLlmChatWithDiagnostics(
                            config = llmCfg,
                            systemPrompt = sysPrompt,
                            userPrompt = usrPrompt,
                            imageJpegBase64 = activeImageB64,
                            fallbackGeminiConfig = fallbackGeminiCfg
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
                            diagnosticLog = fullDiag,
                            tokenUsage = llmOutcome.tokenUsage,
                            modelName = llmOutcome.modelName.ifBlank { llmCfg.model.trim() }
                        )
                    )
                }

                PipelineMode.MULTIMODAL_DIRECT -> {
                    val mmCfg = settings.multimodalConfig
                    if (mmCfg.apiKey.isBlank() && !isLocalEndpoint(mmCfg.baseUrl)) {
                        return@withContext Result.failure(IllegalStateException("请先在「API 配置」中填写多模态模型 API Key"))
                    }

                    onStageChanged(PipelineStage.MULTIMODAL_PROCESSING)
                    val activeImageB64 = if (
                        effectiveIntent == VoiceIntentMode.HELP_ME_WRITE &&
                        mmCfg.protocol == MultimodalProtocol.GEMINI_NATIVE
                    ) {
                        chatContextScreenshotBase64
                    } else {
                        ""
                    }
                    val prompt = PromptBuilder.buildMultimodalDirectPrompt(
                        intentMode = effectiveIntent,
                        selectedText = selectedText,
                        translateTargetLanguage = translateTargetLanguage,
                        style = activeStyle,
                        targetAppName = targetAppName,
                        dictionary = settings.dictionary,
                        surroundingBeforeCursor = safeContext,
                        chatContextMessages = chatContextMessages,
                        hasChatScreenshot = activeImageB64.isNotBlank(),
                        attitudeInstruction = attitudeInstruction,
                        editorDraft = editorDraft,
                        isChineseLocale = AppStrings.isChinese(settings.appLanguage)
                    )
                    val mmOutcome = when (mmCfg.protocol) {
                        MultimodalProtocol.GEMINI_NATIVE -> {
                            val compressedM4a = AacAudioCompressor.compressWavToM4aOrNull(wavFile)
                            try {
                                val (uploadBytes, uploadMime) = if (compressedM4a != null) {
                                    compressedM4a.readBytes() to "audio/mp4"
                                } else {
                                    wavFile.readBytes() to "audio/wav"
                                }
                                val b64 = java.util.Base64.getEncoder().encodeToString(uploadBytes)
                                callGeminiMultimodalAudioWithDiagnostics(
                                    config = mmCfg,
                                    prompt = prompt,
                                    base64Wav = b64,
                                    mimeType = uploadMime,
                                    imageJpegBase64 = activeImageB64
                                )
                            } finally {
                                if (compressedM4a != null) {
                                    runCatching { compressedM4a.delete() }
                                }
                            }
                        }
                        MultimodalProtocol.OPENAI_AUDIO_CHAT -> {
                            val b64 = java.util.Base64.getEncoder().encodeToString(wavFile.readBytes())
                            callOpenAiMultimodalAudioWithDiagnostics(
                                config = mmCfg,
                                prompt = prompt,
                                base64Wav = b64
                            )
                        }
                    }
                    val output = mmOutcome.text

                    if (output.isBlank() || output.contains("[EMPTY_SPEECH]")) {
                        return@withContext Result.failure(IllegalArgumentException("未识别到清晰人声，请重试"))
                    }

                    val elapsed = System.currentTimeMillis() - startMs
                    val rawSummary = when (effectiveIntent) {
                        VoiceIntentMode.SPEAK_TO_EDIT -> "[多模态语音改写指令]"
                        VoiceIntentMode.HELP_ME_WRITE -> "[多模态帮我写需求]"
                        VoiceIntentMode.JEV_COPILOT -> "[多模态军师底牌]"
                        VoiceIntentMode.TRANSLATE -> "[多模态直译 → $translateTargetLanguage]"
                        VoiceIntentMode.DICTATE -> "[多模态音频一步直出]"
                    }
                    Result.success(
                        PipelineExecutionResult(
                            rawTranscript = rawSummary,
                            finalOutput = output,
                            latencyMs = elapsed,
                            modeSummary = "多模态 (${mmCfg.model})",
                            diagnosticLog = mmOutcome.diagnosticLog,
                            tokenUsage = mmOutcome.tokenUsage,
                            modelName = mmOutcome.modelName.ifBlank { mmCfg.model.trim() }
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun executeRoutedVoicePipeline(
        plan: RoutePlan,
        wavFile: File,
        settings: AppSettings,
        activeStyle: ToneStyle,
        targetAppName: String,
        selectedText: String,
        safeContext: String,
        effectiveIntent: VoiceIntentMode,
        translateTargetLanguage: String,
        preTranscribedText: String?,
        sttTailWaitMs: Long,
        chatContextMessages: List<String>,
        chatContextScreenshotBase64: String,
        attitudeInstruction: String,
        editorDraft: String,
        startMs: Long,
        onStageChanged: (PipelineStage) -> Unit
    ): Result<PipelineExecutionResult> {
        var asrUsedLocal = plan.asr is AsrRoute.Local
        var usedLiveStream = false
        var sttElapsedMs = 0L
        var asrLabel: String

        val rawTranscript: String = when (val asr = plan.asr) {
            is AsrRoute.Local -> {
                asrLabel = "本机 ${shortLocalModelLabel(asr.modelId)}"
                if (!preTranscribedText.isNullOrBlank()) {
                    usedLiveStream = true
                    sttElapsedMs = sttTailWaitMs.coerceAtLeast(0L)
                    postProcessLocalAsrText(preTranscribedText, settings)
                } else {
                    onStageChanged(PipelineStage.TRANSCRIBING_STT)
                    val sttStart = System.currentTimeMillis()
                    val localRes = transcribeWithLocalAsrFile(
                        wavFile = wavFile,
                        modelId = asr.modelId,
                        settings = settings,
                        incognito = plan.clearLocalStateAfterRun
                    )
                    sttElapsedMs = (System.currentTimeMillis() - sttStart).coerceAtLeast(0L)
                    if (localRes.isSuccess) {
                        localRes.getOrThrow()
                    } else {
                        if (!plan.egressAllowed) {
                            return Result.failure(localRes.exceptionOrNull()!!)
                        }
                        // HYBRID fallback to cloud ASR if configured
                        val cloudFallback = runCatching {
                            transcribeWithCloudSttStage(wavFile, settings)
                        }
                        if (cloudFallback.isSuccess) {
                            asrUsedLocal = false
                            asrLabel = "云端回退 STT"
                            cloudFallback.getOrThrow()
                        } else {
                            return Result.failure(localRes.exceptionOrNull()!!)
                        }
                    }
                }
            }
            is AsrRoute.Cloud -> {
                if (!plan.egressAllowed) {
                    return Result.failure(
                        IllegalStateException(routeBlockMessage(RouteBlockReason.OFFLINE_ONLY_NO_MODEL, "纯离线锁已开启"))
                    )
                }
                asrLabel = "云端 STT"
                if (!preTranscribedText.isNullOrBlank()) {
                    usedLiveStream = true
                    sttElapsedMs = sttTailWaitMs.coerceAtLeast(0L)
                    cleanRawTranscript(preTranscribedText)
                } else {
                    onStageChanged(PipelineStage.TRANSCRIBING_STT)
                    val sttStart = System.currentTimeMillis()
                    val text = transcribeWithCloudSttStage(wavFile, settings)
                    sttElapsedMs = (System.currentTimeMillis() - sttStart).coerceAtLeast(0L)
                    text
                }
            }
            is AsrRoute.None -> {
                val reason = (plan.text as? TextRoute.Blocked)?.reason ?: RouteBlockReason.NO_CLOUD_OR_LOCAL_AVAILABLE
                return Result.failure(
                    IllegalStateException(routeBlockMessage(reason, "尚未配置可用语音识别引擎"))
                )
            }
        }

        if (rawTranscript.isBlank() || isHallucinatedSilence(rawTranscript)) {
            return Result.failure(IllegalArgumentException("未识别到清晰语音内容，请重试"))
        }

        // Voice Snippet 0ms Instant Expansion (runs even on local raw passthrough)
        if (effectiveIntent == VoiceIntentMode.DICTATE) {
            SnippetMatcher.matchVoiceTranscript(rawTranscript, settings.snippets)?.let { matched ->
                val elapsed = (System.currentTimeMillis() - startMs) + if (usedLiveStream) sttTailWaitMs else 0L
                val metrics = if (asrUsedLocal) formatLocalMetricsSummary(includeSlmMetrics = false) else ""
                return Result.success(
                    PipelineExecutionResult(
                        rawTranscript = rawTranscript,
                        finalOutput = matched.content,
                        latencyMs = elapsed,
                        modeSummary = "口令展开 (${matched.title})",
                        sttLatencyMs = sttElapsedMs,
                        llmLatencyMs = 0L,
                        usedStreamingAsr = usedLiveStream,
                        executionSource = if (asrUsedLocal) ExecutionSource.LOCAL_RAW_PASSTHROUGH else ExecutionSource.CLOUD,
                        localMetricsSummary = metrics
                    )
                )
            }
        }

        return when (val textRoute = plan.text) {
            is TextRoute.RawPassthrough -> {
                val elapsed = (System.currentTimeMillis() - startMs) + if (usedLiveStream) sttTailWaitMs else 0L
                val metrics = if (asrUsedLocal) formatLocalMetricsSummary(includeSlmMetrics = false) else ""
                Result.success(
                    PipelineExecutionResult(
                        rawTranscript = rawTranscript,
                        finalOutput = rawTranscript,
                        latencyMs = elapsed,
                        modeSummary = if (asrUsedLocal) "$asrLabel · 原始直出" else "仅 STT ($asrLabel)",
                        sttLatencyMs = sttElapsedMs,
                        llmLatencyMs = 0L,
                        usedStreamingAsr = usedLiveStream,
                        executionSource = if (asrUsedLocal) ExecutionSource.LOCAL_RAW_PASSTHROUGH else ExecutionSource.CLOUD,
                        localMetricsSummary = metrics
                    )
                )
            }

            is TextRoute.Local -> {
                onStageChanged(PipelineStage.POLISHING_LLM)
                val llmStart = System.currentTimeMillis()
                val localRes = runLocalSlmForVoiceIntent(
                    modelId = textRoute.modelId,
                    effectiveIntent = effectiveIntent,
                    rawTranscript = rawTranscript,
                    selectedText = selectedText,
                    translateTargetLanguage = translateTargetLanguage,
                    editorDraft = editorDraft,
                    chatContextMessages = chatContextMessages,
                    activeStyle = activeStyle,
                    settings = settings,
                    incognito = plan.clearLocalStateAfterRun
                )
                val llmElapsedMs = (System.currentTimeMillis() - llmStart).coerceAtLeast(0L)
                if (localRes.isSuccess) {
                    val polished = localRes.getOrThrow().ifBlank { rawTranscript }
                    val elapsed = (System.currentTimeMillis() - startMs) + if (usedLiveStream) sttTailWaitMs else 0L
                    val metrics = formatLocalMetricsSummary(includeSlmMetrics = true)
                    val slmTag = shortLocalModelLabel(textRoute.modelId)
                    Result.success(
                        PipelineExecutionResult(
                            rawTranscript = rawTranscript,
                            finalOutput = polished,
                            latencyMs = elapsed,
                            modeSummary = "$asrLabel + 本机 $slmTag",
                            sttLatencyMs = sttElapsedMs,
                            llmLatencyMs = llmElapsedMs,
                            usedStreamingAsr = usedLiveStream,
                            diagnosticLog = "Local SLM [$slmTag]: ${llmElapsedMs} ms${if (metrics.isNotBlank()) " ($metrics)" else ""}",
                            executionSource = ExecutionSource.LOCAL,
                            localMetricsSummary = metrics
                        )
                    )
                } else {
                    // Local SLM failure handling:
                    // 1. If HYBRID (egressAllowed), try cloud LLM / multimodal text fallback.
                    // 2. If still failing (or OFFLINE_ONLY) and task is DICTATE, degrade to raw passthrough.
                    if (plan.egressAllowed) {
                        val cloudFallback = executeCloudTextStageForVoice(
                            settings = settings,
                            effectiveIntent = effectiveIntent,
                            rawTranscript = rawTranscript,
                            selectedText = selectedText,
                            safeContext = safeContext,
                            translateTargetLanguage = translateTargetLanguage,
                            activeStyle = activeStyle,
                            targetAppName = targetAppName,
                            chatContextMessages = chatContextMessages,
                            chatContextScreenshotBase64 = chatContextScreenshotBase64,
                            attitudeInstruction = attitudeInstruction,
                            editorDraft = editorDraft,
                            preferMultimodalFallback = false
                        )
                        if (cloudFallback.isSuccess) {
                            val (outcome, modelLabel) = cloudFallback.getOrThrow()
                            val elapsed = (System.currentTimeMillis() - startMs) + if (usedLiveStream) sttTailWaitMs else 0L
                            val metrics = if (asrUsedLocal) formatLocalMetricsSummary(includeSlmMetrics = false) else ""
                            return Result.success(
                                PipelineExecutionResult(
                                    rawTranscript = rawTranscript,
                                    finalOutput = outcome.text.ifBlank { rawTranscript },
                                    latencyMs = elapsed,
                                    modeSummary = "$asrLabel + $modelLabel (云端回退)",
                                    sttLatencyMs = sttElapsedMs,
                                    llmLatencyMs = (System.currentTimeMillis() - llmStart).coerceAtLeast(0L),
                                    usedStreamingAsr = usedLiveStream,
                                    diagnosticLog = outcome.diagnosticLog,
                                    executionSource = if (asrUsedLocal) {
                                        ExecutionSource.HYBRID_LOCAL_ASR_CLOUD_LLM
                                    } else {
                                        ExecutionSource.CLOUD
                                    },
                                    localMetricsSummary = metrics,
                                    tokenUsage = outcome.tokenUsage,
                                    modelName = outcome.modelName.ifBlank { modelLabel }
                                )
                            )
                        }
                    }
                    if (effectiveIntent == VoiceIntentMode.DICTATE) {
                        val elapsed = (System.currentTimeMillis() - startMs) + if (usedLiveStream) sttTailWaitMs else 0L
                        val metrics = if (asrUsedLocal) formatLocalMetricsSummary(includeSlmMetrics = false) else ""
                        Result.success(
                            PipelineExecutionResult(
                                rawTranscript = rawTranscript,
                                finalOutput = rawTranscript,
                                latencyMs = elapsed,
                                modeSummary = "$asrLabel · 原始直出 (大脑回退)",
                                sttLatencyMs = sttElapsedMs,
                                llmLatencyMs = llmElapsedMs,
                                usedStreamingAsr = usedLiveStream,
                                executionSource = if (asrUsedLocal) {
                                    ExecutionSource.LOCAL_RAW_PASSTHROUGH
                                } else {
                                    ExecutionSource.CLOUD
                                },
                                localMetricsSummary = metrics
                            )
                        )
                    } else {
                        Result.failure(localRes.exceptionOrNull()!!)
                    }
                }
            }

            is TextRoute.Cloud,
            is TextRoute.CloudMultimodalTextFallback -> {
                if (!plan.egressAllowed) {
                    return Result.failure(
                        IllegalStateException(routeBlockMessage(RouteBlockReason.OFFLINE_ONLY_UNSUPPORTED_TASK, "纯离线锁已开启"))
                    )
                }
                onStageChanged(PipelineStage.POLISHING_LLM)
                val llmStart = System.currentTimeMillis()
                val cloudRes = executeCloudTextStageForVoice(
                    settings = settings,
                    effectiveIntent = effectiveIntent,
                    rawTranscript = rawTranscript,
                    selectedText = selectedText,
                    safeContext = safeContext,
                    translateTargetLanguage = translateTargetLanguage,
                    activeStyle = activeStyle,
                    targetAppName = targetAppName,
                    chatContextMessages = chatContextMessages,
                    chatContextScreenshotBase64 = chatContextScreenshotBase64,
                    attitudeInstruction = attitudeInstruction,
                    editorDraft = editorDraft,
                    preferMultimodalFallback = textRoute is TextRoute.CloudMultimodalTextFallback
                )
                val llmElapsedMs = (System.currentTimeMillis() - llmStart).coerceAtLeast(0L)
                cloudRes.fold(
                    onSuccess = { (outcome, modelLabel) ->
                        val elapsed = (System.currentTimeMillis() - startMs) + if (usedLiveStream) sttTailWaitMs else 0L
                        val metrics = if (asrUsedLocal) formatLocalMetricsSummary(includeSlmMetrics = false) else ""
                        Result.success(
                            PipelineExecutionResult(
                                rawTranscript = rawTranscript,
                                finalOutput = outcome.text.ifBlank { rawTranscript },
                                latencyMs = elapsed,
                                modeSummary = "$asrLabel + $modelLabel",
                                sttLatencyMs = sttElapsedMs,
                                llmLatencyMs = llmElapsedMs,
                                usedStreamingAsr = usedLiveStream,
                                diagnosticLog = outcome.diagnosticLog,
                                executionSource = if (asrUsedLocal) {
                                    ExecutionSource.HYBRID_LOCAL_ASR_CLOUD_LLM
                                } else {
                                    ExecutionSource.CLOUD
                                },
                                localMetricsSummary = metrics,
                                tokenUsage = outcome.tokenUsage,
                                modelName = outcome.modelName.ifBlank { modelLabel }
                            )
                        )
                    },
                    onFailure = { err ->
                        Result.failure(err)
                    }
                )
            }

            is TextRoute.Blocked -> {
                Result.failure(
                    IllegalStateException(routeBlockMessage(textRoute.reason, "尚未配置可用文本润色引擎"))
                )
            }
        }
    }

    private suspend fun transcribeWithLocalAsrFile(
        wavFile: File,
        modelId: String,
        settings: AppSettings,
        incognito: Boolean
    ): Result<String> {
        val client = localAiClient
            ?: return Result.failure(IllegalStateException("本机离线推理客户端未初始化"))
        val modelDir = localModelRegistry?.getInstalledModelDir(modelId)
            ?: return Result.failure(IllegalStateException("本机离线语音模型未安装或目录缺失 ($modelId)"))
        val hotwords = PromptBuilder.extractHotwordTerms(settings.dictionary)
        val defaultLangHint = if (AppStrings.isChinese(settings.appLanguage)) "zh" else "en"
        val startRegistryGen = currentRegistrySnapshot().modelGeneration
        val rawRes = client.transcribeFile(
            wavPath = wavFile.absolutePath,
            modelId = modelId,
            modelDirPath = modelDir,
            hotwords = hotwords,
            languageHint = settings.sttConfig.language.ifBlank { defaultLangHint },
            incognito = incognito,
            expectedGeneration = 0L
        )
        if (currentRegistrySnapshot().modelGeneration != startRegistryGen) {
            return Result.failure(IllegalStateException("本地语音模型已切换，已丢弃旧转写结果"))
        }
        return rawRes.map { raw -> postProcessLocalAsrText(raw, settings) }
    }

    private fun postProcessLocalAsrText(raw: String, settings: AppSettings): String {
        val stripped = LocalDictionaryPostProcessor.cleanSenseVoiceOutput(cleanRawTranscript(raw))
        return LocalDictionaryPostProcessor.applyWrongVariantsCorrection(stripped, settings.dictionary)
    }

    private suspend fun transcribeWithCloudSttStage(
        wavFile: File,
        settings: AppSettings
    ): String {
        val sttCfg = resolveEffectiveSttConfig(settings)
        validateSttConfig(sttCfg, settings)?.let { errMsg ->
            throw IllegalStateException(errMsg)
        }
        val sttBias = PromptBuilder.buildSttBiasPrompt(
            dictionary = settings.dictionary,
            isChineseLocale = AppStrings.isChinese(settings.appLanguage)
        )
        val hotwords = PromptBuilder.extractHotwordTerms(settings.dictionary)
        return when (sttCfg.protocol) {
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
    }

    private suspend fun runLocalSlmForVoiceIntent(
        modelId: String,
        effectiveIntent: VoiceIntentMode,
        rawTranscript: String,
        selectedText: String,
        translateTargetLanguage: String,
        editorDraft: String,
        chatContextMessages: List<String>,
        activeStyle: ToneStyle,
        settings: AppSettings,
        incognito: Boolean
    ): Result<String> {
        val effectiveTranscript = if (effectiveIntent == VoiceIntentMode.DICTATE) {
            LocalDictionaryPostProcessor.applyWrongVariantsCorrection(rawTranscript, settings.dictionary)
        } else {
            rawTranscript
        }
        val matchContext = if (effectiveIntent == VoiceIntentMode.SPEAK_TO_EDIT) {
            "$selectedText $effectiveTranscript"
        } else {
            effectiveTranscript
        }
        val topDict = LocalDictionaryPostProcessor.selectRelevantDictionaryLinesForLocalSlm(
            text = matchContext,
            dictionary = settings.dictionary
        )
        val toneHint = if (activeStyle.summary.isNotBlank()) {
            "${activeStyle.name} — ${activeStyle.summary}"
        } else {
            activeStyle.name
        }

        if (effectiveIntent == VoiceIntentMode.DICTATE) {
            val chunks = splitLongTextForChunkedPolish(effectiveTranscript, CHUNKED_POLISH_THRESHOLD_CHARS)
            if (chunks.size > 1) {
                // Local SLM MUST process chunks strictly serially (never concurrent) to avoid OOM & state contention.
                val sb = StringBuilder()
                for (chunk in chunks) {
                    val chunkDict = LocalDictionaryPostProcessor.selectRelevantDictionaryLinesForLocalSlm(
                        text = chunk,
                        dictionary = settings.dictionary
                    )
                    val prompt = LocalPromptTemplates.buildDictatePrompt(
                        rawTranscript = chunk,
                        dictionaryTerms = chunkDict,
                        toneHint = toneHint
                    )
                    val chunkRes = executeLocalSlmPrompt(
                        modelId = modelId,
                        taskName = AiTask.DICTATE.name,
                        prompt = prompt,
                        temperature = settings.llmConfig.temperature,
                        priority = PRIORITY_VOICE,
                        incognito = incognito
                    )
                    if (chunkRes.isFailure) return Result.failure(chunkRes.exceptionOrNull()!!)
                    val cleaned = cleanModelOutput(chunkRes.getOrThrow().text)
                    val safeChunk = if (cleaned.isBlank() ||
                        LocalDictionaryPostProcessor.isLikelyHallucinatedDictation(chunk, cleaned)
                    ) {
                        chunk
                    } else {
                        cleaned
                    }
                    sb.append(safeChunk)
                }
                return Result.success(sb.toString())
            }
        }

        val prompt = when (effectiveIntent) {
            VoiceIntentMode.DICTATE -> LocalPromptTemplates.buildDictatePrompt(
                rawTranscript = effectiveTranscript,
                dictionaryTerms = topDict,
                toneHint = toneHint
            )
            VoiceIntentMode.SPEAK_TO_EDIT -> LocalPromptTemplates.buildSpeakToEditPrompt(
                selectedText = selectedText,
                voiceInstruction = rawTranscript,
                dictionaryTerms = topDict,
                toneHint = toneHint
            )
            VoiceIntentMode.TRANSLATE -> LocalPromptTemplates.buildTranslatePrompt(
                rawTranscript = rawTranscript,
                targetLanguage = translateTargetLanguage,
                dictionaryTerms = topDict
            )
            VoiceIntentMode.HELP_ME_WRITE -> LocalPromptTemplates.buildHelpMeWritePrompt(
                writingRequest = rawTranscript,
                editorDraft = editorDraft,
                chatContext = chatContextMessages.joinToString("\n"),
                dictionaryTerms = topDict,
                toneHint = toneHint
            )
            VoiceIntentMode.JEV_COPILOT -> LocalPromptTemplates.buildJevCopilotPrompt(
                chatContext = chatContextMessages.joinToString("\n"),
                userBottomLine = rawTranscript
            )
        }
        val priority = if (effectiveIntent == VoiceIntentMode.DICTATE) PRIORITY_VOICE else PRIORITY_EDIT_WRITE
        return executeLocalSlmPrompt(
            modelId = modelId,
            taskName = effectiveIntent.toAiTask().name,
            prompt = prompt,
            temperature = settings.llmConfig.temperature,
            priority = priority,
            incognito = incognito
        ).map { res ->
            val cleaned = cleanModelOutput(res.text)
            if (effectiveIntent == VoiceIntentMode.DICTATE &&
                (cleaned.isBlank() || LocalDictionaryPostProcessor.isLikelyHallucinatedDictation(rawTranscript, cleaned))
            ) {
                rawTranscript
            } else {
                cleaned
            }
        }
    }

    private suspend fun executeLocalSlmPrompt(
        modelId: String,
        taskName: String,
        prompt: LocalPromptPair,
        temperature: Float,
        priority: Int,
        incognito: Boolean,
        onTokenBatch: ((chunk: String, tokenCount: Int) -> Unit)? = null
    ): Result<LocalSlmRuntimeResult> {
        val client = localAiClient
            ?: return Result.failure(IllegalStateException("本机离线推理客户端未初始化"))
        val modelDir = localModelRegistry?.getInstalledModelDir(modelId)
            ?: return Result.failure(IllegalStateException("本机离线文本模型未安装或目录缺失 ($modelId)"))
        val startRegistryGen = currentRegistrySnapshot().modelGeneration
        val request = GenRequest(
            modelId = modelId,
            modelDirPath = modelDir,
            taskName = taskName,
            systemPrompt = prompt.systemPrompt,
            userPrompt = prompt.userPrompt,
            maxTokens = prompt.maxTokens,
            temperature = temperature,
            priority = priority,
            incognito = incognito,
            expectedGeneration = 0L,
            promptTemplateVersion = prompt.templateVersion
        )
        val genRes = client.generate(request = request, onTokenBatch = onTokenBatch)
        if (currentRegistrySnapshot().modelGeneration != startRegistryGen) {
            return Result.failure(IllegalStateException("本地文本模型已切换，已丢弃旧生成结果"))
        }
        return genRes
    }

    private suspend fun executeCloudTextStageForVoice(
        settings: AppSettings,
        effectiveIntent: VoiceIntentMode,
        rawTranscript: String,
        selectedText: String,
        safeContext: String,
        translateTargetLanguage: String,
        activeStyle: ToneStyle,
        targetAppName: String,
        chatContextMessages: List<String>,
        chatContextScreenshotBase64: String,
        attitudeInstruction: String,
        editorDraft: String,
        preferMultimodalFallback: Boolean
    ): Result<Pair<ChatCallOutcome, String>> {
        return runCatching {
            val llmCfg = resolveEffectiveLlmConfig(settings)
            val hasCloudLlm = llmCfg.apiKey.isNotBlank() || isLocalEndpoint(llmCfg.baseUrl)
            val useMultimodal = preferMultimodalFallback || !hasCloudLlm
            if (useMultimodal) {
                val mmCfg = settings.multimodalConfig
                if (mmCfg.apiKey.isBlank() && !isLocalEndpoint(mmCfg.baseUrl)) {
                    throw IllegalStateException("请先在「API 配置」中填写云端 LLM 或多模态模型 API Key")
                }
                val (sysPrompt, usrPrompt) = buildCloudVoicePrompts(
                    effectiveIntent = effectiveIntent,
                    rawTranscript = rawTranscript,
                    selectedText = selectedText,
                    safeContext = safeContext,
                    translateTargetLanguage = translateTargetLanguage,
                    activeStyle = activeStyle,
                    targetAppName = targetAppName,
                    settings = settings,
                    chatContextMessages = chatContextMessages,
                    hasChatScreenshot = false,
                    attitudeInstruction = attitudeInstruction,
                    editorDraft = editorDraft
                )
                val outcome = executeCloudMultimodalTextFallback(
                    settings = settings,
                    sysPrompt = sysPrompt,
                    usrPrompt = usrPrompt
                )
                outcome to mmCfg.model.substringAfterLast('/').ifBlank { "多模态" }
            } else {
                val chunks = if (effectiveIntent == VoiceIntentMode.DICTATE) {
                    splitLongTextForChunkedPolish(rawTranscript, CHUNKED_POLISH_THRESHOLD_CHARS)
                } else {
                    listOf(rawTranscript)
                }
                val outcome = if (chunks.size > 1 && effectiveIntent == VoiceIntentMode.DICTATE) {
                    val semaphore = Semaphore(MAX_CONCURRENT_POLISH_CHUNKS)
                    val chunkOutcomes = coroutineScope {
                        chunks.mapIndexed { idx, chunkText ->
                            async {
                                semaphore.withPermit {
                                    val chunkContext = if (idx == 0) safeContext else chunks[idx - 1].takeLast(80)
                                    val (sysPrompt, usrPrompt) = PromptBuilder.buildDictationPrompts(
                                        rawSpeech = chunkText,
                                        style = activeStyle,
                                        targetAppName = targetAppName,
                                        dictionary = settings.dictionary,
                                        surroundingBeforeCursor = chunkContext,
                                        isChineseLocale = AppStrings.isChinese(settings.appLanguage)
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
                        text = chunkOutcomes.mapIndexed { idx, o -> o.text.ifBlank { chunks[idx] } }.joinToString(""),
                        diagnosticLog = chunkOutcomes.mapIndexed { idx, o ->
                            "[Chunk ${idx + 1}/${chunkOutcomes.size}] ${o.diagnosticLog}"
                        }.joinToString("\n"),
                        tokenUsage = sumTokenUsages(chunkOutcomes.map { it.tokenUsage }),
                        modelName = chunkOutcomes.firstOrNull { it.modelName.isNotBlank() }?.modelName
                            ?: llmCfg.model.trim()
                    )
                } else {
                    val fallbackGeminiCfg = resolveAvailableGeminiVisionConfig(settings)
                    val canSendVision = canRouteVisionScreenshot(
                        primaryBaseUrl = llmCfg.baseUrl,
                        primaryModel = llmCfg.model,
                        fallbackGeminiConfig = fallbackGeminiCfg
                    )
                    val activeImageB64 = if (effectiveIntent == VoiceIntentMode.HELP_ME_WRITE && canSendVision) {
                        chatContextScreenshotBase64
                    } else {
                        ""
                    }
                    val (sysPrompt, usrPrompt) = buildCloudVoicePrompts(
                        effectiveIntent = effectiveIntent,
                        rawTranscript = rawTranscript,
                        selectedText = selectedText,
                        safeContext = safeContext,
                        translateTargetLanguage = translateTargetLanguage,
                        activeStyle = activeStyle,
                        targetAppName = targetAppName,
                        settings = settings,
                        chatContextMessages = chatContextMessages,
                        hasChatScreenshot = activeImageB64.isNotBlank(),
                        attitudeInstruction = attitudeInstruction,
                        editorDraft = editorDraft
                    )
                    executeLlmChatWithDiagnostics(
                        config = llmCfg,
                        systemPrompt = sysPrompt,
                        userPrompt = usrPrompt,
                        imageJpegBase64 = activeImageB64,
                        fallbackGeminiConfig = fallbackGeminiCfg
                    )
                }
                outcome to llmCfg.model.substringAfterLast('/').ifBlank { "LLM" }
            }
        }
    }

    private fun buildCloudVoicePrompts(
        effectiveIntent: VoiceIntentMode,
        rawTranscript: String,
        selectedText: String,
        safeContext: String,
        translateTargetLanguage: String,
        activeStyle: ToneStyle,
        targetAppName: String,
        settings: AppSettings,
        chatContextMessages: List<String>,
        hasChatScreenshot: Boolean,
        attitudeInstruction: String,
        editorDraft: String
    ): Pair<String, String> {
        val isZh = AppStrings.isChinese(settings.appLanguage)
        return when (effectiveIntent) {
            VoiceIntentMode.SPEAK_TO_EDIT -> PromptBuilder.buildSpeakToEditPrompts(
                selectedText = selectedText,
                voiceInstruction = rawTranscript,
                style = activeStyle,
                dictionary = settings.dictionary,
                isChineseLocale = isZh
            )
            VoiceIntentMode.HELP_ME_WRITE -> PromptBuilder.buildHelpMeWritePrompts(
                voiceInstruction = PromptBuilder.combineAttitudeAndVoiceInstruction(
                    attitudeInstruction = attitudeInstruction,
                    voiceTranscript = rawTranscript,
                    isChineseLocale = isZh
                ),
                style = activeStyle,
                targetAppName = targetAppName,
                dictionary = settings.dictionary,
                surroundingBeforeCursor = safeContext,
                chatContextMessages = chatContextMessages,
                hasChatScreenshot = hasChatScreenshot,
                editorDraft = editorDraft,
                isChineseLocale = isZh
            )
            VoiceIntentMode.JEV_COPILOT -> PromptBuilder.buildJevCopilotPrompts(
                domainProfile = JevDomainProfile.FORMAL_WORKFLOW,
                targetAppName = targetAppName,
                chatContextMessages = chatContextMessages,
                hasChatScreenshot = hasChatScreenshot,
                userBottomLine = rawTranscript,
                surroundingBeforeCursor = safeContext,
                style = activeStyle,
                dictionary = settings.dictionary,
                isChineseLocale = isZh
            )
            VoiceIntentMode.TRANSLATE -> PromptBuilder.buildTranslatePrompts(
                rawSpeech = rawTranscript,
                targetLanguage = translateTargetLanguage,
                style = activeStyle,
                dictionary = settings.dictionary,
                isChineseLocale = isZh
            )
            VoiceIntentMode.DICTATE -> PromptBuilder.buildDictationPrompts(
                rawSpeech = rawTranscript,
                style = activeStyle,
                targetAppName = targetAppName,
                dictionary = settings.dictionary,
                surroundingBeforeCursor = safeContext,
                isChineseLocale = isZh
            )
        }
    }

    private fun executeCloudMultimodalTextFallback(
        settings: AppSettings,
        sysPrompt: String,
        usrPrompt: String,
        imageJpegBase64: String = "",
        forJevCopilot: Boolean = false,
        geminiMaxOutputTokens: Int? = null,
        onThinkingStarted: (() -> Unit)? = null,
        onStreamingTextUpdate: ((String) -> Unit)? = null
    ): ChatCallOutcome {
        val mmCfg = settings.multimodalConfig
        if (mmCfg.apiKey.isBlank() && !isLocalEndpoint(mmCfg.baseUrl)) {
            throw IllegalStateException("请先填写多模态 API Key")
        }
        return when (mmCfg.protocol) {
            MultimodalProtocol.GEMINI_NATIVE -> callGeminiTextOnlyWithDiagnostics(
                config = mmCfg,
                fullPrompt = "$sysPrompt\n\n$usrPrompt",
                imageJpegBase64 = imageJpegBase64,
                forJevCopilot = forJevCopilot,
                maxOutputTokens = geminiMaxOutputTokens,
                onThinkingStarted = onThinkingStarted,
                onStreamingTextUpdate = onStreamingTextUpdate
            )
            MultimodalProtocol.OPENAI_AUDIO_CHAT -> executeLlmChatWithDiagnostics(
                config = LlmConfig(
                    baseUrl = mmCfg.baseUrl,
                    apiKey = mmCfg.apiKey,
                    model = mmCfg.model,
                    temperature = mmCfg.temperature,
                    thinkingMode = mmCfg.thinkingMode
                ),
                systemPrompt = sysPrompt,
                userPrompt = usrPrompt,
                imageJpegBase64 = imageJpegBase64,
                fallbackGeminiConfig = resolveAvailableGeminiVisionConfig(settings),
                forJevCopilot = forJevCopilot,
                geminiMaxOutputTokens = geminiMaxOutputTokens,
                onThinkingStarted = onThinkingStarted,
                onStreamingTextUpdate = onStreamingTextUpdate
            )
        }
    }

    private suspend fun formatLocalMetricsSummary(includeSlmMetrics: Boolean): String {
        val m = localAiClient?.getRuntimeMetrics() ?: return ""
        val pssPart = if (m.totalPssKb > 0) {
            String.format(Locale.US, "PSS %.0fMB", m.totalPssMb)
        } else {
            ""
        }
        val parts = buildList {
            if (includeSlmMetrics) {
                if (m.lastSlmTtftMs > 0L) add("TTFT ${m.lastSlmTtftMs}ms")
                if (m.lastSlmTokensPerSec > 0f) add(String.format(Locale.US, "%.1f tok/s", m.lastSlmTokensPerSec))
            } else {
                if (m.lastAsrLatencyMs > 0L) add("ASR ${m.lastAsrLatencyMs}ms")
            }
            if (pssPart.isNotEmpty()) add(pssPart)
        }
        return parts.joinToString(" · ")
    }

    private fun extractTopDictionaryTerms(settings: AppSettings): List<String> {
        return settings.dictionary
            .asSequence()
            .filter { it.enabled && it.term.isNotBlank() }
            .map { it.term.trim() }
            .distinct()
            .take(LocalPromptTemplates.MAX_LOCAL_DICT_TERMS)
            .toList()
    }

    private fun VoiceIntentMode.toAiTask(): AiTask = when (this) {
        VoiceIntentMode.DICTATE -> AiTask.DICTATE
        VoiceIntentMode.SPEAK_TO_EDIT -> AiTask.SPEAK_TO_EDIT
        VoiceIntentMode.HELP_ME_WRITE -> AiTask.HELP_ME_WRITE
        VoiceIntentMode.TRANSLATE -> AiTask.TRANSLATE
        VoiceIntentMode.JEV_COPILOT -> AiTask.JEV_COPILOT
    }

    private fun shortLocalModelLabel(modelId: String): String = when (modelId) {
        OfflineModelCatalog.ID_ASR_ZIPFORMER_ZH_14M -> "Zipformer 14M"
        OfflineModelCatalog.ID_ASR_PARAFORMER_ZH_SMALL -> "Paraformer"
        OfflineModelCatalog.ID_ASR_ZIPFORMER_BILINGUAL -> "Zipformer 双语"
        OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL -> "SenseVoice"
        OfflineModelCatalog.ID_ASR_STREAMING_PARAFORMER_ZH_EN -> "Paraformer 流式"
        OfflineModelCatalog.ID_ASR_FIRERED2_CTC_ZH_EN -> "FireRed2"
        OfflineModelCatalog.ID_ASR_FUNASR_NANO -> "Fun-ASR-Nano"
        OfflineModelCatalog.ID_ASR_QWEN3_ASR_0_6B -> "Qwen3-ASR"
        OfflineModelCatalog.ID_SLM_CORE_0_8B -> "0.8B"
        OfflineModelCatalog.ID_SLM_PLUS_2B -> "2B"
        OfflineModelCatalog.ID_SLM_PRO_4B -> "4B"
        else -> OfflineModelCatalog.findById(modelId)?.displayName ?: modelId
    }

    private fun routeBlockMessage(reason: RouteBlockReason, defaultCloudHint: String): String = when (reason) {
        RouteBlockReason.SECURE_FIELD -> "当前为密码框或安全输入域，已禁用语音与 AI 推理"
        RouteBlockReason.OFFLINE_ONLY_UNSUPPORTED_TASK -> "纯离线锁已开启，当前任务不支持离线执行（可切换至混合模式或关闭纯离线锁）"
        RouteBlockReason.OFFLINE_ONLY_NO_MODEL -> "纯离线锁已开启，请先在「离线模型」页面下载并启用所需本机模型"
        RouteBlockReason.LOCAL_MODEL_UNAVAILABLE -> "已选本机离线模型损坏或推理服务暂不可用，请重新校验或切换模型"
        RouteBlockReason.NO_CLOUD_OR_LOCAL_AVAILABLE -> defaultCloudHint
    }

    private fun validateSttConfig(
        sttCfg: SttConfig,
        settings: AppSettings? = null
    ): String? {
        val rawError = when (sttCfg.protocol) {
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
        } ?: return null

        val isMissingKey = sttCfg.apiKey.isBlank()
        if (isMissingKey &&
            settings != null &&
            settings.llmConfig.enabled &&
            settings.llmConfig.apiKey.isNotBlank() &&
            isKnownPureTextModel(settings.llmConfig.model)
        ) {
            return "语音听写(一阶段 STT)尚未配置 API Key：当前 ${settings.llmConfig.model} 仅处理文本润色与军师，请在「API 配置」启用 DeepSeek 单 Key 语音识别或补填硅基流动/豆包/千问/Gemini 语音密钥"
        }
        return rawError
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

    internal fun resolveEffectiveSttConfig(settings: AppSettings): SttConfig {
        val primary = settings.sttConfig
        if (primary.apiKey.isNotBlank() || isLocalEndpoint(primary.baseUrl)) {
            return primary
        }
        val isDeepSeekStt = primary.presetId == "deepseek_asr" ||
            primary.presetId == "deepseek" ||
            EndpointResolver.isDeepSeekAsrEndpoint(primary.baseUrl)
        if (isDeepSeekStt) {
            val vaultKey = settings.providerVault[UnifiedProviderCatalog.ID_DEEPSEEK]?.apiKey.orEmpty()
            val llmKey = settings.llmConfig.apiKey.takeIf {
                UnifiedProviderCatalog.inferLlmProviderId(settings.llmConfig) == UnifiedProviderCatalog.ID_DEEPSEEK
            }.orEmpty()
            val resolvedKey = vaultKey.ifBlank { llmKey }
            if (resolvedKey.isNotBlank()) {
                return primary.copy(
                    apiKey = resolvedKey,
                    baseUrl = primary.baseUrl.ifBlank { EndpointResolver.DEFAULT_DEEPSEEK_ASR_URL },
                    model = primary.model.ifBlank { "deepseek-asr" }
                )
            }
        }
        return primary
    }

    private fun resolveEffectiveLlmConfig(settings: AppSettings): LlmConfig {
        val primary = settings.llmConfig
        if (primary.apiKey.isNotBlank() || isLocalEndpoint(primary.baseUrl)) {
            return primary
        }
        // Single-Key DeepSeek fallback when user configured DeepSeek ASR STT or Vault
        val deepSeekVaultKey = settings.providerVault[UnifiedProviderCatalog.ID_DEEPSEEK]?.apiKey.orEmpty()
        val deepSeekKey = when {
            EndpointResolver.isDeepSeekAsrEndpoint(settings.sttConfig.baseUrl) && settings.sttConfig.apiKey.isNotBlank() ->
                settings.sttConfig.apiKey
            (primary.presetId == "deepseek" || EndpointResolver.isOfficialDeepSeekHost(primary.baseUrl)) && deepSeekVaultKey.isNotBlank() ->
                deepSeekVaultKey
            else -> ""
        }
        if (deepSeekKey.isNotBlank()) {
            val deepSeekBase = settings.providerVault[UnifiedProviderCatalog.ID_DEEPSEEK]?.customBaseUrl
                ?.takeIf { it.isNotBlank() }
                ?.removeSuffix("/tools/asr")
                ?: primary.baseUrl.takeIf { it.isNotBlank() }?.removeSuffix("/tools/asr")
                ?: "https://api.deepseek.com/v1"
            return primary.copy(
                presetId = "deepseek",
                baseUrl = deepSeekBase,
                apiKey = deepSeekKey,
                model = primary.model.ifBlank { "deepseek-chat" }
            )
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

    private fun resolveAvailableGeminiVisionConfig(settings: AppSettings): MultimodalConfig? {
        val googleVaultKey = settings.providerVault[UnifiedProviderCatalog.ID_GOOGLE]?.apiKey.orEmpty()
        val geminiKey = when {
            settings.multimodalConfig.protocol == MultimodalProtocol.GEMINI_NATIVE && settings.multimodalConfig.apiKey.isNotBlank() ->
                settings.multimodalConfig.apiKey
            settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS && settings.sttConfig.apiKey.isNotBlank() ->
                settings.sttConfig.apiKey
            googleVaultKey.isNotBlank() -> googleVaultKey
            else -> ""
        }
        if (geminiKey.isBlank()) return null
        val geminiBase = settings.providerVault[UnifiedProviderCatalog.ID_GOOGLE]?.customBaseUrl
            ?.takeIf { it.isNotBlank() }
            ?: settings.sttConfig.baseUrl.takeIf {
                settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS && it.isNotBlank() && !it.startsWith("wss://", true)
            }
            ?: "https://generativelanguage.googleapis.com/v1beta"
        return MultimodalConfig(
            presetId = "gemini",
            protocol = MultimodalProtocol.GEMINI_NATIVE,
            baseUrl = geminiBase,
            apiKey = geminiKey,
            model = "gemini-3.5-flash-lite",
            temperature = 0.2f,
            thinkingMode = ThinkingMode.DISABLED
        )
    }

    internal fun resolveEffectiveJevLlmConfig(settings: AppSettings): LlmConfig? {
        if (!settings.jevUseDedicatedModel) return null
        val cfg = settings.jevLlmConfig
        if (cfg.apiKey.isNotBlank() || isLocalEndpoint(cfg.baseUrl)) {
            return cfg
        }
        val providerId = UnifiedProviderCatalog.inferLlmProviderId(cfg)
        val vault = UnifiedProviderCatalog.hydrateVaultFromSettings(settings)
        val cred = vault[providerId]
        val fallbackKey = when (providerId) {
            UnifiedProviderCatalog.ID_VOLCENGINE -> cred?.apiKey?.ifBlank { cred.sttApiKey }.orEmpty()
            UnifiedProviderCatalog.ID_GOOGLE ->
                cred?.apiKey?.ifBlank { resolveAvailableGeminiVisionConfig(settings)?.apiKey.orEmpty() }.orEmpty()
            else -> cred?.apiKey.orEmpty()
        }
        val fallbackBaseUrl = cfg.baseUrl.ifBlank {
            cred?.customBaseUrl?.ifBlank { null }
                ?: UnifiedProviderCatalog.specFor(providerId).defaultBaseUrl
        }
        return cfg.copy(
            baseUrl = fallbackBaseUrl,
            apiKey = fallbackKey
        )
    }

    private fun executeLlmChatWithDiagnostics(
        config: LlmConfig,
        systemPrompt: String,
        userPrompt: String,
        imageJpegBase64: String = "",
        fallbackGeminiConfig: MultimodalConfig? = null,
        forJevCopilot: Boolean = false,
        geminiMaxOutputTokens: Int? = null,
        onThinkingStarted: (() -> Unit)? = null,
        onStreamingTextUpdate: ((String) -> Unit)? = null
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
                fullPrompt = "$systemPrompt\n\n$userPrompt",
                imageJpegBase64 = imageJpegBase64,
                forJevCopilot = forJevCopilot,
                maxOutputTokens = geminiMaxOutputTokens,
                onThinkingStarted = onThinkingStarted,
                onStreamingTextUpdate = onStreamingTextUpdate
            )
        }
        if (imageJpegBase64.isNotBlank()) {
            if (isKnownPureTextModel(config.model) || isVisionMarkedUnsupported(config.baseUrl, config.model)) {
                if (fallbackGeminiConfig != null) {
                    val visionAttempt = runCatching {
                        callGeminiTextOnlyWithDiagnostics(
                            config = fallbackGeminiConfig,
                            fullPrompt = "$systemPrompt\n\n$userPrompt",
                            imageJpegBase64 = imageJpegBase64,
                            forJevCopilot = forJevCopilot,
                            maxOutputTokens = geminiMaxOutputTokens,
                            onThinkingStarted = onThinkingStarted,
                            onStreamingTextUpdate = onStreamingTextUpdate
                        )
                    }
                    visionAttempt.getOrNull()?.let { return it }
                }
                val strippedPrompt = PromptBuilder.stripChatScreenVisionGuide(userPrompt)
                return callOpenAiChatCompletionWithDiagnostics(
                    config = config,
                    systemPrompt = systemPrompt,
                    userPrompt = strippedPrompt,
                    imageJpegBase64 = "",
                    forJevCopilot = forJevCopilot,
                    onThinkingStarted = onThinkingStarted,
                    onStreamingTextUpdate = onStreamingTextUpdate
                )
            }
            val openAiVisionAttempt = runCatching {
                callOpenAiChatCompletionWithDiagnostics(
                    config = config,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    imageJpegBase64 = imageJpegBase64,
                    forJevCopilot = forJevCopilot,
                    onThinkingStarted = onThinkingStarted,
                    onStreamingTextUpdate = onStreamingTextUpdate
                )
            }
            openAiVisionAttempt.getOrNull()?.let { return it }
            val visionErrMsg = openAiVisionAttempt.exceptionOrNull()?.message.orEmpty()
            if (visionErrMsg.contains("(401)") || visionErrMsg.contains("(403)")) {
                throw openAiVisionAttempt.exceptionOrNull()!!
            }
            if (fallbackGeminiConfig != null) {
                val geminiFallback = runCatching {
                    callGeminiTextOnlyWithDiagnostics(
                        config = fallbackGeminiConfig,
                        fullPrompt = "$systemPrompt\n\n$userPrompt",
                        imageJpegBase64 = imageJpegBase64,
                        forJevCopilot = forJevCopilot,
                        maxOutputTokens = geminiMaxOutputTokens,
                        onThinkingStarted = onThinkingStarted,
                        onStreamingTextUpdate = onStreamingTextUpdate
                    )
                }
                geminiFallback.getOrNull()?.let { return it }
            }
        }
        val textOnlyPrompt = if (imageJpegBase64.isNotBlank()) {
            PromptBuilder.stripChatScreenVisionGuide(userPrompt)
        } else {
            userPrompt
        }
        return callOpenAiChatCompletionWithDiagnostics(
            config = config,
            systemPrompt = systemPrompt,
            userPrompt = textOnlyPrompt,
            imageJpegBase64 = "",
            forJevCopilot = forJevCopilot,
            onThinkingStarted = onThinkingStarted,
            onStreamingTextUpdate = onStreamingTextUpdate
        )
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
        targetAppName: String = "",
        chatContextMessages: List<String> = emptyList(),
        chatContextScreenshotBase64: String = ""
    ): Result<PipelineExecutionResult> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            val isContextualReply = chatContextMessages.isNotEmpty() || chatContextScreenshotBase64.isNotBlank()
            val task = when {
                isContextualReply -> AiTask.HELP_ME_WRITE
                !explicitInstruction.isNullOrBlank() -> AiTask.SPEAK_TO_EDIT
                else -> AiTask.TEXT_TRANSFORM
            }
            val plan = resolveRoutePlanEnsuringLocalService(task = task, settings = settings)
            val planText = plan.text
            if (planText is TextRoute.Blocked &&
                (!plan.egressAllowed || settings.offlineConfig.mode != OfflineMode.OFF)
            ) {
                return@withContext Result.failure(
                    IllegalStateException(routeBlockMessage(planText.reason, "请先填写 LLM API Key"))
                )
            }

            if (planText is TextRoute.Local) {
                val topDict = extractTopDictionaryTerms(settings)
                val prompt = when (task) {
                    AiTask.HELP_ME_WRITE -> LocalPromptTemplates.buildHelpMeWritePrompt(
                        writingRequest = explicitInstruction?.takeIf { it.isNotBlank() } ?: rawOrSelectedText,
                        chatContext = chatContextMessages.joinToString("\n"),
                        dictionaryTerms = topDict,
                        toneHint = activeStyle.name
                    )
                    AiTask.SPEAK_TO_EDIT -> LocalPromptTemplates.buildSpeakToEditPrompt(
                        selectedText = rawOrSelectedText,
                        voiceInstruction = explicitInstruction.orEmpty(),
                        dictionaryTerms = topDict,
                        toneHint = activeStyle.name
                    )
                    else -> LocalPromptTemplates.buildDictatePrompt(
                        rawTranscript = rawOrSelectedText,
                        dictionaryTerms = topDict,
                        toneHint = activeStyle.name
                    )
                }
                val localRes = executeLocalSlmPrompt(
                    modelId = planText.modelId,
                    taskName = task.name,
                    prompt = prompt,
                    temperature = settings.llmConfig.temperature,
                    priority = PRIORITY_EDIT_WRITE,
                    incognito = plan.clearLocalStateAfterRun
                )
                if (localRes.isSuccess) {
                    val elapsed = System.currentTimeMillis() - startMs
                    val cleaned = cleanModelOutput(localRes.getOrThrow().text)
                    val metrics = formatLocalMetricsSummary(includeSlmMetrics = true)
                    val slmTag = shortLocalModelLabel(planText.modelId)
                    return@withContext Result.success(
                        PipelineExecutionResult(
                            rawTranscript = rawOrSelectedText,
                            finalOutput = cleaned.ifBlank { rawOrSelectedText },
                            latencyMs = elapsed,
                            modeSummary = "本机 $slmTag",
                            llmLatencyMs = elapsed,
                            diagnosticLog = "Local SLM [$slmTag]: ${elapsed} ms${if (metrics.isNotBlank()) " ($metrics)" else ""}",
                            executionSource = ExecutionSource.LOCAL,
                            localMetricsSummary = metrics
                        )
                    )
                }
                if (!plan.egressAllowed) {
                    return@withContext Result.failure(localRes.exceptionOrNull()!!)
                }
            }

            if (!plan.egressAllowed) {
                return@withContext Result.failure(
                    IllegalStateException(routeBlockMessage(RouteBlockReason.OFFLINE_ONLY_UNSUPPORTED_TASK, "纯离线锁已开启"))
                )
            }

            val fallbackGeminiCfg = resolveAvailableGeminiVisionConfig(settings)
            val canSendVision = when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> {
                    val llmCfg = resolveEffectiveLlmConfig(settings)
                    canRouteVisionScreenshot(
                        primaryBaseUrl = llmCfg.baseUrl,
                        primaryModel = llmCfg.model,
                        fallbackGeminiConfig = fallbackGeminiCfg
                    )
                }
                PipelineMode.MULTIMODAL_DIRECT -> {
                    val mmCfg = settings.multimodalConfig
                    when (mmCfg.protocol) {
                        MultimodalProtocol.GEMINI_NATIVE -> true
                        MultimodalProtocol.OPENAI_AUDIO_CHAT ->
                            canRouteVisionScreenshot(
                                primaryBaseUrl = mmCfg.baseUrl,
                                primaryModel = mmCfg.model,
                                fallbackGeminiConfig = fallbackGeminiCfg
                            )
                    }
                }
            }
            val effectiveScreenshotB64 = if (canSendVision) chatContextScreenshotBase64 else ""
            val isZh = AppStrings.isChinese(settings.appLanguage)
            val (sysPrompt, usrPrompt) = if (isContextualReply) {
                PromptBuilder.buildHelpMeWritePrompts(
                    voiceInstruction = explicitInstruction?.takeIf { it.isNotBlank() } ?: rawOrSelectedText,
                    style = activeStyle,
                    targetAppName = targetAppName,
                    dictionary = settings.dictionary,
                    surroundingBeforeCursor = "",
                    chatContextMessages = chatContextMessages,
                    hasChatScreenshot = effectiveScreenshotB64.isNotBlank(),
                    isChineseLocale = isZh
                )
            } else if (!explicitInstruction.isNullOrBlank()) {
                PromptBuilder.buildSpeakToEditPrompts(
                    selectedText = rawOrSelectedText,
                    voiceInstruction = explicitInstruction,
                    style = activeStyle,
                    dictionary = settings.dictionary,
                    isChineseLocale = isZh
                )
            } else {
                PromptBuilder.buildDictationPrompts(
                    rawSpeech = rawOrSelectedText,
                    style = activeStyle,
                    targetAppName = targetAppName,
                    dictionary = settings.dictionary,
                    surroundingBeforeCursor = "",
                    isChineseLocale = isZh
                )
            }

            val output: String
            val modelLabel: String
            var diagLog = ""
            var usageOut: TokenUsage? = null
            var effectiveModelName = ""

            if (plan.text is TextRoute.CloudMultimodalTextFallback) {
                val outcome = executeCloudMultimodalTextFallback(
                    settings = settings,
                    sysPrompt = sysPrompt,
                    usrPrompt = usrPrompt,
                    imageJpegBase64 = effectiveScreenshotB64
                )
                output = outcome.text
                diagLog = outcome.diagnosticLog
                usageOut = outcome.tokenUsage
                effectiveModelName = outcome.modelName.ifBlank { settings.multimodalConfig.model.trim() }
                modelLabel = settings.multimodalConfig.model
            } else {
                when (settings.pipelineMode) {
                    PipelineMode.DUAL_STAGE -> {
                        val llmCfg = resolveEffectiveLlmConfig(settings)
                        if (llmCfg.apiKey.isBlank() && !isLocalEndpoint(llmCfg.baseUrl)) {
                            return@withContext Result.failure(IllegalStateException("请先填写 LLM API Key"))
                        }
                        val outcome = executeLlmChatWithDiagnostics(
                            config = llmCfg,
                            systemPrompt = sysPrompt,
                            userPrompt = usrPrompt,
                            imageJpegBase64 = effectiveScreenshotB64,
                            fallbackGeminiConfig = fallbackGeminiCfg
                        )
                        output = outcome.text
                        diagLog = outcome.diagnosticLog
                        usageOut = outcome.tokenUsage
                        effectiveModelName = outcome.modelName.ifBlank { llmCfg.model.trim() }
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
                                    fullPrompt = "$sysPrompt\n\n$usrPrompt",
                                    imageJpegBase64 = effectiveScreenshotB64
                                )
                                output = outcome.text
                                diagLog = outcome.diagnosticLog
                                usageOut = outcome.tokenUsage
                                effectiveModelName = outcome.modelName.ifBlank { mmCfg.model.trim() }
                            }
                            MultimodalProtocol.OPENAI_AUDIO_CHAT -> {
                                val outcome = executeLlmChatWithDiagnostics(
                                    config = LlmConfig(
                                        baseUrl = mmCfg.baseUrl,
                                        apiKey = mmCfg.apiKey,
                                        model = mmCfg.model,
                                        temperature = mmCfg.temperature,
                                        thinkingMode = mmCfg.thinkingMode
                                    ),
                                    systemPrompt = sysPrompt,
                                    userPrompt = usrPrompt,
                                    imageJpegBase64 = effectiveScreenshotB64,
                                    fallbackGeminiConfig = fallbackGeminiCfg
                                )
                                output = outcome.text
                                diagLog = outcome.diagnosticLog
                                usageOut = outcome.tokenUsage
                                effectiveModelName = outcome.modelName.ifBlank { mmCfg.model.trim() }
                            }
                        }
                        modelLabel = mmCfg.model
                    }
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
                    diagnosticLog = diagLog,
                    executionSource = ExecutionSource.CLOUD,
                    tokenUsage = usageOut,
                    modelName = effectiveModelName.ifBlank { modelLabel.trim() }
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Executes `🔍 问AI` query for [com.typefree.ime.ime.panels.AskAiCardPanel]:
     * Direct query answering with 100% static [PromptBuilder.ASK_AI_CARD_SYSTEM_PROMPT].
     */
    suspend fun runAskAiCardQuery(
        settings: AppSettings,
        query: String,
        targetAppName: String = ""
    ): Result<PipelineExecutionResult> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            val plan = resolveRoutePlan(task = AiTask.ASK_AI_CARD, settings = settings)
            val planText = plan.text
            if (!plan.egressAllowed) {
                val reason = (planText as? TextRoute.Blocked)?.reason
                    ?: RouteBlockReason.OFFLINE_ONLY_UNSUPPORTED_TASK
                return@withContext Result.failure(
                    IllegalStateException(routeBlockMessage(reason, "纯离线锁已开启，问AI需要联网"))
                )
            }
            if (planText is TextRoute.Blocked && settings.offlineConfig.mode != OfflineMode.OFF) {
                return@withContext Result.failure(
                    IllegalStateException(routeBlockMessage(planText.reason, "请先填写 LLM API Key"))
                )
            }

            val (sysPrompt, usrPrompt) = PromptBuilder.buildAskAiCardPrompts(
                query = query,
                targetAppName = targetAppName,
                dictionary = settings.dictionary,
                isChineseLocale = AppStrings.isChinese(settings.appLanguage)
            )
            val fallbackGeminiCfg = resolveAvailableGeminiVisionConfig(settings)
            val output: String
            val modelLabel: String
            var diagLog = ""
            var usageOut: TokenUsage? = null
            var effectiveModelName = ""

            if (plan.text is TextRoute.CloudMultimodalTextFallback) {
                val outcome = executeCloudMultimodalTextFallback(
                    settings = settings,
                    sysPrompt = sysPrompt,
                    usrPrompt = usrPrompt
                )
                output = outcome.text
                diagLog = outcome.diagnosticLog
                usageOut = outcome.tokenUsage
                effectiveModelName = outcome.modelName.ifBlank { settings.multimodalConfig.model.trim() }
                modelLabel = settings.multimodalConfig.model
            } else {
                when (settings.pipelineMode) {
                    PipelineMode.DUAL_STAGE -> {
                        val llmCfg = resolveEffectiveLlmConfig(settings)
                        if (llmCfg.apiKey.isBlank() && !isLocalEndpoint(llmCfg.baseUrl)) {
                            return@withContext Result.failure(IllegalStateException("请先填写 LLM API Key"))
                        }
                        val outcome = executeLlmChatWithDiagnostics(
                            config = llmCfg,
                            systemPrompt = sysPrompt,
                            userPrompt = usrPrompt,
                            imageJpegBase64 = "",
                            fallbackGeminiConfig = fallbackGeminiCfg
                        )
                        output = outcome.text
                        diagLog = outcome.diagnosticLog
                        usageOut = outcome.tokenUsage
                        effectiveModelName = outcome.modelName.ifBlank { llmCfg.model.trim() }
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
                                usageOut = outcome.tokenUsage
                                effectiveModelName = outcome.modelName.ifBlank { mmCfg.model.trim() }
                                modelLabel = mmCfg.model
                            }
                            MultimodalProtocol.OPENAI_AUDIO_CHAT -> {
                                val outcome = executeLlmChatWithDiagnostics(
                                    config = LlmConfig(
                                        baseUrl = mmCfg.baseUrl,
                                        apiKey = mmCfg.apiKey,
                                        model = mmCfg.model,
                                        temperature = mmCfg.temperature
                                    ),
                                    systemPrompt = sysPrompt,
                                    userPrompt = usrPrompt,
                                    imageJpegBase64 = "",
                                    fallbackGeminiConfig = fallbackGeminiCfg
                                )
                                output = outcome.text
                                diagLog = outcome.diagnosticLog
                                usageOut = outcome.tokenUsage
                                effectiveModelName = outcome.modelName.ifBlank { mmCfg.model.trim() }
                                modelLabel = mmCfg.model
                            }
                        }
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - startMs
            Result.success(
                PipelineExecutionResult(
                    rawTranscript = query,
                    finalOutput = output.trim(),
                    latencyMs = elapsed,
                    modeSummary = "问AI · $modelLabel",
                    llmLatencyMs = elapsed,
                    diagnosticLog = diagLog,
                    executionSource = ExecutionSource.CLOUD,
                    tokenUsage = usageOut,
                    modelName = effectiveModelName.ifBlank { modelLabel.trim() }
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Executes `✦ Jev 军师` (Subtext Radar & 3-Strategy Copilot):
     * - Supports zero-audio direct generation (`userBottomLine = ""`, `wavFile = null`) as well as
     *   `[↻ 换一批]` (`excludedPreviousReplies`) and voice bottom-line supplement (`userBottomLine` or `wavFile`).
     * - Automatically routes desensitive viewport screenshots when [canRouteVisionScreenshot] is true
     *   and strips `<chat_screen_vision_guide>` transparently for pure-text models (DeepSeek, Qwen, Groq, Ollama).
     * - Parses model output via 3-tier anti-fragile [JevResponseParser] and restores reversible
     *   contact placeholders via [redactionSession].
     */
    internal fun canJevUseVisionScreenshot(settings: AppSettings): Boolean {
        if (!settings.chatContextSendScreenshot) return false
        val plan = resolveRoutePlan(task = AiTask.JEV_COPILOT, settings = settings)
        if (!plan.egressAllowed || !plan.jevVisionAllowed) return false
        val fallbackGeminiCfg = resolveAvailableGeminiVisionConfig(settings)
        val dedicatedCfg = resolveEffectiveJevLlmConfig(settings)
        return if (dedicatedCfg != null) {
            canRouteVisionScreenshot(
                primaryBaseUrl = dedicatedCfg.baseUrl,
                primaryModel = dedicatedCfg.model,
                fallbackGeminiConfig = null
            ) && !isVisionMarkedUnsupported(dedicatedCfg.baseUrl, dedicatedCfg.model)
        } else {
            when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> {
                    val llmCfg = resolveEffectiveLlmConfig(settings)
                    val routeable = canRouteVisionScreenshot(
                        primaryBaseUrl = llmCfg.baseUrl,
                        primaryModel = llmCfg.model,
                        fallbackGeminiConfig = fallbackGeminiCfg
                    )
                    routeable && (
                        fallbackGeminiCfg != null ||
                            !isVisionMarkedUnsupported(llmCfg.baseUrl, llmCfg.model)
                        )
                }
                PipelineMode.MULTIMODAL_DIRECT -> {
                    val mmCfg = settings.multimodalConfig
                    when (mmCfg.protocol) {
                        MultimodalProtocol.GEMINI_NATIVE -> true
                        MultimodalProtocol.OPENAI_AUDIO_CHAT -> {
                            val routeable = canRouteVisionScreenshot(
                                primaryBaseUrl = mmCfg.baseUrl,
                                primaryModel = mmCfg.model,
                                fallbackGeminiConfig = fallbackGeminiCfg
                            )
                            routeable && (
                                fallbackGeminiCfg != null ||
                                    !isVisionMarkedUnsupported(mmCfg.baseUrl, mmCfg.model)
                                )
                        }
                    }
                }
            }
        }
    }

    suspend fun runJevCopilot(
        settings: AppSettings,
        activeStyle: ToneStyle,
        domainProfile: JevDomainProfile,
        targetAppName: String = "",
        chatContextMessages: List<String> = emptyList(),
        chatContextScreenshotBase64: String = "",
        userBottomLine: String = "",
        excludedPreviousReplies: List<String> = emptyList(),
        surroundingBeforeCursor: String = "",
        redactionSession: RedactionSession? = null,
        wavFile: File? = null,
        preTranscribedBottomLine: String? = null,
        chatTopology: ChatTopology = ChatTopology(),
        targetPackageName: String = "",
        memoryPayload: JevMemoryPromptPayload? = null,
        priorWeightFuser: ((List<Int>, String) -> List<Int>)? = null,
        shouldLockOptionOrder: () -> Boolean = { false },
        onThinkingStarted: (() -> Unit)? = null,
        onPartialResult: ((JevCopilotResult) -> Unit)? = null
    ): Result<Pair<JevCopilotResult, String>> = withContext(Dispatchers.IO) {
        try {
            val plan = resolveRoutePlan(task = AiTask.JEV_COPILOT, settings = settings)
            val planText = plan.text
            if (planText is TextRoute.Blocked &&
                (!plan.egressAllowed || settings.offlineConfig.mode != OfflineMode.OFF)
            ) {
                return@withContext Result.failure(
                    IllegalStateException(routeBlockMessage(planText.reason, "请先填写云端 API Key 或启用 4B 本机离线军师"))
                )
            }

            val resolvedBottomLine = when {
                !preTranscribedBottomLine.isNullOrBlank() -> cleanRawTranscript(preTranscribedBottomLine)
                wavFile != null && wavFile.exists() && wavFile.length() > 44L -> {
                    val asrPlan = resolveRoutePlan(task = AiTask.DICTATE, settings = settings)
                    when (val asr = asrPlan.asr) {
                        is AsrRoute.Local -> {
                            val localAsrRes = transcribeWithLocalAsrFile(
                                wavFile = wavFile,
                                modelId = asr.modelId,
                                settings = settings,
                                incognito = asrPlan.clearLocalStateAfterRun
                            )
                            if (localAsrRes.isSuccess) {
                                localAsrRes.getOrThrow()
                            } else if (plan.egressAllowed) {
                                transcribeWithCloudSttStage(wavFile, settings)
                            } else {
                                return@withContext Result.failure(localAsrRes.exceptionOrNull()!!)
                            }
                        }
                        is AsrRoute.Cloud -> {
                            if (!plan.egressAllowed) {
                                return@withContext Result.failure(
                                    IllegalStateException(routeBlockMessage(RouteBlockReason.OFFLINE_ONLY_NO_MODEL, "纯离线锁已开启"))
                                )
                            }
                            val effectiveStt = resolveEffectiveSttConfig(settings)
                            validateSttConfig(effectiveStt, settings)?.let { errMsg ->
                                return@withContext Result.failure(IllegalStateException(errMsg))
                            }
                            val hotwords = PromptBuilder.extractHotwordTerms(settings.dictionary)
                            val bias = PromptBuilder.buildSttBiasPrompt(
                                dictionary = settings.dictionary,
                                isChineseLocale = AppStrings.isChinese(settings.appLanguage)
                            )
                            if (effectiveStt.protocol == SttProtocol.GEMINI_LIVE_WS) {
                                transcribeGeminiWavWithFastFallback(wavFile, effectiveStt, hotwords)
                            } else {
                                transcribeWithOpenAiStt(wavFile, effectiveStt, bias)
                            }
                        }
                        is AsrRoute.None -> {
                            if (!plan.egressAllowed) {
                                return@withContext Result.failure(
                                    IllegalStateException(routeBlockMessage(RouteBlockReason.OFFLINE_ONLY_NO_MODEL, "纯离线锁已开启"))
                                )
                            }
                            val effectiveStt = resolveEffectiveSttConfig(settings)
                            validateSttConfig(effectiveStt, settings)?.let { errMsg ->
                                return@withContext Result.failure(IllegalStateException(errMsg))
                            }
                            val hotwords = PromptBuilder.extractHotwordTerms(settings.dictionary)
                            val bias = PromptBuilder.buildSttBiasPrompt(
                                dictionary = settings.dictionary,
                                isChineseLocale = AppStrings.isChinese(settings.appLanguage)
                            )
                            if (effectiveStt.protocol == SttProtocol.GEMINI_LIVE_WS) {
                                transcribeGeminiWavWithFastFallback(wavFile, effectiveStt, hotwords)
                            } else {
                                transcribeWithOpenAiStt(wavFile, effectiveStt, bias)
                            }
                        }
                    }
                }
                userBottomLine.isNotBlank() -> userBottomLine.trim()
                else -> ""
            }

            // On-device 4B Jev Copilot (pure OCR text mode, no vision screenshot)
            if (planText is TextRoute.Local) {
                val localPrompt = LocalPromptTemplates.buildJevCopilotPrompt(
                    chatContext = chatContextMessages.joinToString("\n"),
                    userBottomLine = resolvedBottomLine,
                    domainHint = domainProfile.name
                )
                val streamAccum = StringBuilder()
                var lastSubtextClosedLocal = false
                var lastOptCloseCountLocal = 0
                var lastWeightsClosedLocal = false
                val localRes = executeLocalSlmPrompt(
                    modelId = planText.modelId,
                    taskName = AiTask.JEV_COPILOT.name,
                    prompt = localPrompt,
                    temperature = settings.jevLlmConfig.temperature,
                    priority = PRIORITY_EDIT_WRITE,
                    incognito = plan.clearLocalStateAfterRun,
                    onTokenBatch = if (onPartialResult != null) {
                        { chunk, _ ->
                            streamAccum.append(chunk)
                            val partialText = streamAccum.toString()
                            val subtextClosed = partialText.contains("</subtext>", ignoreCase = true)
                            val optCloseCount = OPT_CLOSE_TAG_REGEX.findAll(partialText).count()
                            val weightsClosed = partialText.contains("</weights>", ignoreCase = true)
                            if ((subtextClosed && !lastSubtextClosedLocal) ||
                                optCloseCount > lastOptCloseCountLocal ||
                                (weightsClosed && !lastWeightsClosedLocal)
                            ) {
                                lastSubtextClosedLocal = subtextClosed
                                lastOptCloseCountLocal = optCloseCount
                                lastWeightsClosedLocal = weightsClosed
                                JevResponseParser.parseStreamingPreview(
                                    rawPartialOutput = partialText,
                                    redactionSession = redactionSession,
                                    usedVision = false,
                                    redactionCount = redactionSession?.redactionCount ?: 0,
                                    priorWeightFuser = priorWeightFuser,
                                    lockVisualOrder = shouldLockOptionOrder()
                                )?.let { preview -> onPartialResult(preview) }
                            }
                        }
                    } else null
                )
                if (localRes.isSuccess) {
                    val localRaw = cleanModelOutput(localRes.getOrThrow().text)
                    if (localRaw.isNotBlank()) {
                        val parsed = JevResponseParser.parse(
                            rawModelOutput = localRaw,
                            redactionSession = redactionSession,
                            usedVision = false,
                            redactionCount = redactionSession?.redactionCount ?: 0,
                            priorWeightFuser = priorWeightFuser,
                            lockVisualOrder = shouldLockOptionOrder()
                        )
                        return@withContext Result.success(parsed to resolvedBottomLine)
                    }
                }
                if (!plan.egressAllowed) {
                    return@withContext Result.failure(
                        localRes.exceptionOrNull() ?: IOException("离线军师返回内容为空，请重试")
                    )
                }
            }

            if (!plan.egressAllowed) {
                return@withContext Result.failure(
                    IllegalStateException(routeBlockMessage(RouteBlockReason.OFFLINE_ONLY_UNSUPPORTED_TASK, "纯离线锁已开启"))
                )
            }

            val fallbackGeminiCfg = resolveAvailableGeminiVisionConfig(settings)
            val dedicatedCfg = resolveEffectiveJevLlmConfig(settings)
            val canSendVision = if (dedicatedCfg != null) {
                canRouteVisionScreenshot(
                    primaryBaseUrl = dedicatedCfg.baseUrl,
                    primaryModel = dedicatedCfg.model,
                    fallbackGeminiConfig = null
                )
            } else {
                when (settings.pipelineMode) {
                    PipelineMode.DUAL_STAGE -> {
                        val llmCfg = resolveEffectiveLlmConfig(settings)
                        canRouteVisionScreenshot(
                            primaryBaseUrl = llmCfg.baseUrl,
                            primaryModel = llmCfg.model,
                            fallbackGeminiConfig = fallbackGeminiCfg
                        )
                    }
                    PipelineMode.MULTIMODAL_DIRECT -> {
                        val mmCfg = settings.multimodalConfig
                        when (mmCfg.protocol) {
                            MultimodalProtocol.GEMINI_NATIVE -> true
                            MultimodalProtocol.OPENAI_AUDIO_CHAT ->
                                canRouteVisionScreenshot(
                                    primaryBaseUrl = mmCfg.baseUrl,
                                    primaryModel = mmCfg.model,
                                    fallbackGeminiConfig = fallbackGeminiCfg
                                )
                        }
                    }
                }
            }

            val allowScreenshot = plan.jevVisionAllowed &&
                canSendVision &&
                settings.chatContextSendScreenshot &&
                redactionSession?.hasHighRiskCredentials != true
            val effectiveScreenshotB64 = if (allowScreenshot) chatContextScreenshotBase64 else ""

            val (sysPrompt, usrPrompt) = PromptBuilder.buildJevCopilotPrompts(
                domainProfile = domainProfile,
                targetAppName = targetAppName,
                chatContextMessages = chatContextMessages,
                hasChatScreenshot = effectiveScreenshotB64.isNotBlank(),
                userBottomLine = resolvedBottomLine,
                excludedPreviousReplies = excludedPreviousReplies,
                surroundingBeforeCursor = surroundingBeforeCursor,
                style = activeStyle,
                dictionary = settings.dictionary,
                chatTopology = chatTopology,
                targetPackageName = targetPackageName,
                memoryPayload = memoryPayload,
                isChineseLocale = AppStrings.isChinese(settings.appLanguage)
            )

            var lastSubtextClosed = false
            var lastOptCloseCount = 0
            var lastWeightsClosed = false
            var thinkingNotified = false
            val notifyThinkingStartedOnce = {
                if (!thinkingNotified) {
                    thinkingNotified = true
                    onThinkingStarted?.invoke()
                }
            }
            val onStreamingTextUpdate: ((String) -> Unit)? = if (onPartialResult != null) {
                { partialText ->
                    val subtextClosed = partialText.contains("</subtext>", ignoreCase = true)
                    val optCloseCount = OPT_CLOSE_TAG_REGEX.findAll(partialText).count()
                    val weightsClosed = partialText.contains("</weights>", ignoreCase = true)
                    if ((subtextClosed && !lastSubtextClosed) ||
                        optCloseCount > lastOptCloseCount ||
                        (weightsClosed && !lastWeightsClosed)
                    ) {
                        lastSubtextClosed = subtextClosed
                        lastOptCloseCount = optCloseCount
                        lastWeightsClosed = weightsClosed
                        JevResponseParser.parseStreamingPreview(
                            rawPartialOutput = partialText,
                            redactionSession = redactionSession,
                            usedVision = effectiveScreenshotB64.isNotBlank(),
                            redactionCount = redactionSession?.redactionCount ?: 0,
                            priorWeightFuser = priorWeightFuser,
                            lockVisualOrder = shouldLockOptionOrder()
                        )?.let { preview ->
                            onPartialResult(preview)
                        }
                    }
                }
            } else {
                null
            }

            // Shared global LLM defaults to ThinkingMode.DISABLED for dictation speed;
            // Jev Copilot needs real tactical reasoning by default, so promote DISABLED -> AUTO
            // unless the user explicitly enabled Dedicated Model mode and selected DISABLED.
            fun promoteSharedThinking(mode: ThinkingMode): ThinkingMode =
                if (mode == ThinkingMode.DISABLED) ThinkingMode.AUTO else mode

            val jevOutcome: ChatCallOutcome = if (dedicatedCfg != null) {
                if (dedicatedCfg.apiKey.isBlank() && !isLocalEndpoint(dedicatedCfg.baseUrl)) {
                    return@withContext Result.failure(
                        IllegalStateException("请先在「API 配置」中填写 Jev 军师专用模型 API Key")
                    )
                }
                executeLlmChatWithDiagnostics(
                    config = dedicatedCfg,
                    systemPrompt = sysPrompt,
                    userPrompt = usrPrompt,
                    imageJpegBase64 = effectiveScreenshotB64,
                    fallbackGeminiConfig = null,
                    forJevCopilot = true,
                    geminiMaxOutputTokens = JEV_GEMINI_MAX_OUTPUT_TOKENS,
                    onThinkingStarted = notifyThinkingStartedOnce,
                    onStreamingTextUpdate = onStreamingTextUpdate
                )
            } else {
                when (settings.pipelineMode) {
                    PipelineMode.DUAL_STAGE -> {
                        val llmCfg = resolveEffectiveLlmConfig(settings).let {
                            it.copy(thinkingMode = promoteSharedThinking(it.thinkingMode))
                        }
                        if (llmCfg.apiKey.isBlank() && !isLocalEndpoint(llmCfg.baseUrl)) {
                            return@withContext Result.failure(IllegalStateException("请先填写 LLM API Key"))
                        }
                        executeLlmChatWithDiagnostics(
                            config = llmCfg,
                            systemPrompt = sysPrompt,
                            userPrompt = usrPrompt,
                            imageJpegBase64 = effectiveScreenshotB64,
                            fallbackGeminiConfig = fallbackGeminiCfg?.copy(thinkingMode = ThinkingMode.AUTO),
                            forJevCopilot = true,
                            geminiMaxOutputTokens = JEV_GEMINI_MAX_OUTPUT_TOKENS,
                            onThinkingStarted = notifyThinkingStartedOnce,
                            onStreamingTextUpdate = onStreamingTextUpdate
                        )
                    }
                    PipelineMode.MULTIMODAL_DIRECT -> {
                        val mmCfg = settings.multimodalConfig.let {
                            it.copy(thinkingMode = promoteSharedThinking(it.thinkingMode))
                        }
                        if (mmCfg.apiKey.isBlank() && !isLocalEndpoint(mmCfg.baseUrl)) {
                            return@withContext Result.failure(IllegalStateException("请先填写多模态 API Key"))
                        }
                        when (mmCfg.protocol) {
                            MultimodalProtocol.GEMINI_NATIVE -> {
                                callGeminiTextOnlyWithDiagnostics(
                                    config = mmCfg,
                                    fullPrompt = "$sysPrompt\n\n$usrPrompt",
                                    imageJpegBase64 = effectiveScreenshotB64,
                                    forJevCopilot = true,
                                    maxOutputTokens = JEV_GEMINI_MAX_OUTPUT_TOKENS,
                                    onThinkingStarted = notifyThinkingStartedOnce,
                                    onStreamingTextUpdate = onStreamingTextUpdate
                                )
                            }
                            MultimodalProtocol.OPENAI_AUDIO_CHAT -> {
                                executeLlmChatWithDiagnostics(
                                    config = LlmConfig(
                                        baseUrl = mmCfg.baseUrl,
                                        apiKey = mmCfg.apiKey,
                                        model = mmCfg.model,
                                        temperature = mmCfg.temperature,
                                        thinkingMode = mmCfg.thinkingMode
                                    ),
                                    systemPrompt = sysPrompt,
                                    userPrompt = usrPrompt,
                                    imageJpegBase64 = effectiveScreenshotB64,
                                    fallbackGeminiConfig = fallbackGeminiCfg?.copy(thinkingMode = ThinkingMode.AUTO),
                                    forJevCopilot = true,
                                    geminiMaxOutputTokens = JEV_GEMINI_MAX_OUTPUT_TOKENS,
                                    onThinkingStarted = notifyThinkingStartedOnce,
                                    onStreamingTextUpdate = onStreamingTextUpdate
                                )
                            }
                        }
                    }
                }
            }
            val rawOutput = jevOutcome.text

            if (rawOutput.isBlank()) {
                return@withContext Result.failure(
                    IOException("模型返回内容为空，请点「↻ 换一批」重试")
                )
            }

            val parsed = JevResponseParser.parse(
                rawModelOutput = rawOutput,
                redactionSession = redactionSession,
                usedVision = effectiveScreenshotB64.isNotBlank(),
                redactionCount = redactionSession?.redactionCount ?: 0,
                priorWeightFuser = priorWeightFuser,
                lockVisualOrder = shouldLockOptionOrder()
            ).copy(
                tokenUsage = jevOutcome.tokenUsage,
                modelName = jevOutcome.modelName,
                executionSource = ExecutionSource.CLOUD
            )
            Result.success(parsed to resolvedBottomLine)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun transcribeNoteAudio(
        wavFile: File,
        settings: AppSettings,
        activeStyle: ToneStyle
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!wavFile.exists() || wavFile.length() <= 44L) {
                return@withContext Result.failure(IllegalArgumentException("未检测到有效语音，请贴近麦克风说话"))
            }
            val plan = resolveRoutePlanEnsuringLocalService(task = AiTask.NOTE_TRANSCRIBE, settings = settings)
            val planAsr = plan.asr
            if (planAsr is AsrRoute.Local) {
                val localRes = transcribeWithLocalAsrFile(
                    wavFile = wavFile,
                    modelId = planAsr.modelId,
                    settings = settings,
                    incognito = plan.clearLocalStateAfterRun
                )
                if (localRes.isSuccess) {
                    val cleaned = localRes.getOrThrow().trim()
                    return@withContext if (cleaned.isBlank()) {
                        Result.failure(IllegalArgumentException("未识别到清晰人声，请重试"))
                    } else {
                        Result.success(cleaned)
                    }
                }
                if (!plan.egressAllowed) {
                    return@withContext Result.failure(localRes.exceptionOrNull()!!)
                }
            } else if (!plan.egressAllowed) {
                val reason = (plan.text as? TextRoute.Blocked)?.reason ?: RouteBlockReason.OFFLINE_ONLY_NO_MODEL
                return@withContext Result.failure(
                    IllegalStateException(routeBlockMessage(reason, "纯离线锁已开启，请先启用本机语音模型"))
                )
            }

            when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> {
                    val effectiveStt = resolveEffectiveSttConfig(settings)
                    validateSttConfig(effectiveStt, settings)?.let { errMsg ->
                        return@withContext Result.failure(IllegalStateException(errMsg))
                    }
                    val hotwords = PromptBuilder.extractHotwordTerms(settings.dictionary)
                    val bias = PromptBuilder.buildSttBiasPrompt(
                        dictionary = settings.dictionary,
                        isChineseLocale = AppStrings.isChinese(settings.appLanguage)
                    )
                    val text = if (effectiveStt.protocol == SttProtocol.GEMINI_LIVE_WS) {
                        transcribeGeminiWavWithFastFallback(wavFile, effectiveStt, hotwords)
                    } else {
                        transcribeWithOpenAiStt(wavFile, effectiveStt, bias)
                    }
                    val cleaned = cleanRawTranscript(text).trim()
                    if (cleaned.isBlank()) {
                        Result.failure(IllegalArgumentException("未识别到清晰人声，请重试"))
                    } else {
                        Result.success(cleaned)
                    }
                }
                PipelineMode.MULTIMODAL_DIRECT -> {
                    val mmCfg = settings.multimodalConfig
                    if (mmCfg.apiKey.isBlank() && !isLocalEndpoint(mmCfg.baseUrl)) {
                        return@withContext Result.failure(IllegalStateException("请先在「API 配置」中填写多模态模型 API Key"))
                    }
                    val prompt = PromptBuilder.buildMultimodalDirectPrompt(
                        intentMode = VoiceIntentMode.DICTATE,
                        selectedText = "",
                        translateTargetLanguage = "",
                        style = activeStyle,
                        targetAppName = "",
                        dictionary = settings.dictionary,
                        surroundingBeforeCursor = "",
                        isChineseLocale = AppStrings.isChinese(settings.appLanguage)
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
                                    mimeType = uploadMime,
                                    imageJpegBase64 = ""
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
                    }.trim()
                    if (output.isBlank() || output.contains("[EMPTY_SPEECH]")) {
                        Result.failure(IllegalArgumentException("未识别到清晰人声，请重试"))
                    } else {
                        Result.success(output)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
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
        targetAppName: String = "",
        onTokenUsage: ((TokenUsage, String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val clippedContext = contextBeforeCursor.takeLast(200).trimEnd()
            if (clippedContext.isEmpty()) {
                return@withContext Result.success("")
            }
            val plan = resolveRoutePlanEnsuringLocalService(task = AiTask.SENTENCE_COMPLETION, settings = settings)
            val planText = plan.text
            if (planText is TextRoute.Blocked &&
                (!plan.egressAllowed || settings.offlineConfig.mode != OfflineMode.OFF)
            ) {
                return@withContext Result.failure(
                    IllegalStateException(routeBlockMessage(planText.reason, "请先填写 LLM API Key"))
                )
            }

            if (planText is TextRoute.Local) {
                val currentJob = coroutineContext[Job]
                activeLocalCompletionJob.set(currentJob)
                try {
                    val prompt = LocalPromptTemplates.buildSentenceCompletionPrompt(
                        cursorBeforeContext = clippedContext,
                        toneHint = activeStyle.name
                    )
                    val localRes = executeLocalSlmPrompt(
                        modelId = planText.modelId,
                        taskName = AiTask.SENTENCE_COMPLETION.name,
                        prompt = prompt,
                        temperature = 0.2f,
                        priority = PRIORITY_COMPLETION,
                        incognito = plan.clearLocalStateAfterRun
                    )
                    if (localRes.isSuccess) {
                        val cleaned = com.typefree.ime.ime.predict.AiSentenceCompleter.cleanCompletionOutput(
                            rawOutput = cleanModelOutput(localRes.getOrThrow().text),
                            contextBeforeCursor = clippedContext
                        )
                        return@withContext Result.success(cleaned)
                    }
                    if (!plan.egressAllowed) {
                        return@withContext Result.failure(localRes.exceptionOrNull()!!)
                    }
                } finally {
                    activeLocalCompletionJob.compareAndSet(currentJob, null)
                }
            }

            if (!plan.egressAllowed) {
                return@withContext Result.failure(
                    IllegalStateException(routeBlockMessage(RouteBlockReason.OFFLINE_ONLY_UNSUPPORTED_TASK, "纯离线锁已开启"))
                )
            }

            val (sysPrompt, usrPrompt) = PromptBuilder.buildSentenceCompletionPrompts(
                contextBeforeCursor = clippedContext,
                style = activeStyle,
                targetAppName = targetAppName,
                dictionary = settings.dictionary,
                isChineseLocale = AppStrings.isChinese(settings.appLanguage)
            )
            val completionOutcome: ChatCallOutcome = if (plan.text is TextRoute.CloudMultimodalTextFallback) {
                executeCloudMultimodalTextFallback(
                    settings = settings,
                    sysPrompt = sysPrompt,
                    usrPrompt = usrPrompt
                )
            } else {
                when (settings.pipelineMode) {
                    PipelineMode.DUAL_STAGE -> {
                        val llmCfg = resolveEffectiveLlmConfig(settings).copy(
                            thinkingMode = ThinkingMode.DISABLED
                        )
                        if (llmCfg.apiKey.isBlank() && !isLocalEndpoint(llmCfg.baseUrl)) {
                            return@withContext Result.failure(IllegalStateException("请先填写 LLM API Key"))
                        }
                        executeLlmChatWithDiagnostics(llmCfg, sysPrompt, usrPrompt)
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
                                )
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
                                )
                            }
                        }
                    }
                }
            }
            completionOutcome.tokenUsage?.takeIf { it.hasUsage }?.let { usage ->
                runCatching { onTokenUsage?.invoke(usage, completionOutcome.modelName) }
            }
            val cleaned = com.typefree.ime.ime.predict.AiSentenceCompleter.cleanCompletionOutput(
                rawOutput = completionOutcome.text,
                contextBeforeCursor = clippedContext
            )
            Result.success(cleaned)
        } catch (e: CancellationException) {
            throw e
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
                    ((slot == ModelCapabilitySlot.LLM || slot == ModelCapabilitySlot.JEV_COPILOT) &&
                        EndpointResolver.isGeminiNativeHttpUrl(cleanBase))
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
                ((slot == ModelCapabilitySlot.LLM || slot == ModelCapabilitySlot.JEV_COPILOT) &&
                    EndpointResolver.isGeminiNativeHttpUrl(baseUrl)) ->
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
                    val body = if (EndpointResolver.isDeepSeekAsrEndpoint(url) || EndpointResolver.isDeepSeekAsrEndpoint(baseUrl)) {
                        MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart(
                                "file",
                                "probe.wav",
                                silentWav.toRequestBody("audio/wav".toMediaType())
                            )
                            .build()
                    } else {
                        MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart(
                                "file",
                                "probe.wav",
                                silentWav.toRequestBody("audio/wav".toMediaType())
                            )
                            .addFormDataPart("model", cleanModel)
                            .addFormDataPart("response_format", "json")
                            .build()
                    }
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
                    ((slot == ModelCapabilitySlot.LLM || slot == ModelCapabilitySlot.JEV_COPILOT) &&
                        EndpointResolver.isGeminiNativeHttpUrl(baseUrl)) -> {
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
        if (EndpointResolver.isDeepSeekAsrEndpoint(url) ||
            EndpointResolver.isDeepSeekAsrEndpoint(config.baseUrl) ||
            config.presetId == "deepseek_asr" ||
            config.presetId == "deepseek"
        ) {
            return transcribeWithDeepSeekAsr(
                wavFile = wavFile,
                config = config,
                resolvedUrl = url
            )
        }
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

    internal fun transcribeWithDeepSeekAsr(
        wavFile: File,
        config: SttConfig,
        resolvedUrl: String = EndpointResolver.resolveSttEndpoint(config.baseUrl)
    ): String {
        val body = buildDeepSeekAsrMultipartBody(wavFile)
        val reqBuilder = Request.Builder()
            .url(resolvedUrl)
            .post(body)
        if (config.apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
        }
        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (resp.code !in 200..299) {
                throw IOException("STT 接口错误 (${resp.code}): ${extractErrorDetail(respBody)}")
            }
            return parseSttResponse(respBody)
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
        userPrompt: String,
        imageJpegBase64: String = "",
        forJevCopilot: Boolean = false,
        onThinkingStarted: (() -> Unit)? = null,
        onStreamingTextUpdate: ((String) -> Unit)? = null
    ): ChatCallOutcome {
        val url = EndpointResolver.resolveChatEndpoint(config.baseUrl)
        val includeTemp = !isSamplingTemperatureDeprecated(config.model)
        val useStream = onStreamingTextUpdate != null
        val streamOptionsKey = "${config.baseUrl.trim().lowercase()}|${config.model.trim().lowercase()}"
        var useStreamOptions = useStream && streamOptionsKey !in unsupportedStreamOptionsKeys
        val traceLines = mutableListOf<String>()
        traceLines.add("POST $url")
        traceLines.add("Model=${config.model.trim()} | ThinkingMode=${config.thinkingMode.name} | Stream=$useStream")

        fun buildBody(
            withTemp: Boolean,
            thinkingOverride: String?, // null = default applyThinkingControl, "none" = omit
            enableStream: Boolean = useStream,
            includeStreamUsage: Boolean = useStreamOptions
        ): Pair<JsonObject, Boolean> {
            var injectedThinking = false
            val obj = JsonObject().apply {
                addProperty("model", config.model.trim())
                if (withTemp) {
                    addProperty("temperature", config.temperature)
                }
                addProperty("stream", enableStream)
                if (enableStream && includeStreamUsage) {
                    add("stream_options", JsonObject().apply {
                        addProperty("include_usage", true)
                    })
                }
                when (thinkingOverride) {
                    null -> {
                        injectedThinking = applyThinkingControl(
                            body = this,
                            baseUrl = config.baseUrl,
                            model = config.model,
                            thinkingMode = config.thinkingMode,
                            forJevCopilot = forJevCopilot
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
                                if (config.thinkingMode == ThinkingMode.DISABLED) "disabled" else "enabled"
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
                        if (imageJpegBase64.isNotBlank()) {
                            val parts = JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("type", "text")
                                    addProperty("text", userPrompt)
                                })
                                add(JsonObject().apply {
                                    addProperty("type", "image_url")
                                    add("image_url", JsonObject().apply {
                                        addProperty("url", "data:image/jpeg;base64,$imageJpegBase64")
                                    })
                                })
                            }
                            add("content", parts)
                        } else {
                            addProperty("content", userPrompt)
                        }
                    })
                }
                add("messages", messages)
            }
            return obj to injectedThinking
        }

        var attemptNo = 0
        fun sendOnce(bodyObj: JsonObject, attemptStream: Boolean): Triple<Int, String, ChatCallOutcome?> {
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
                if (resp.code !in 200..299 || !attemptStream) {
                    val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                    val text = resp.body?.string().orEmpty()
                    if (resp.code in 200..299) {
                        traceLines.add("#$attemptNo [$paramTag] -> HTTP ${resp.code} (${ms} ms)")
                    } else {
                        traceLines.add("#$attemptNo [$paramTag] -> HTTP ${resp.code} (${ms} ms): ${extractErrorDetail(text)}")
                    }
                    return Triple(resp.code, text, null)
                }
                val streamRead = readOpenAiStreamOrBufferedJson(
                    reader = resp.body?.charStream()?.buffered(),
                    onThinkingStarted = onThinkingStarted,
                    onStreamingTextUpdate = onStreamingTextUpdate
                )
                val resolvedUsage = streamRead.tokenUsage
                    ?: TokenUsage.estimateFromTexts("$systemPrompt\n$userPrompt", streamRead.text)
                val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                traceLines.add("#$attemptNo [$paramTag] -> HTTP ${resp.code} (${ms} ms stream)")
                (streamRead.usageDiagnostic ?: formatTokenUsageDiagnostics(resolvedUsage))?.let {
                    traceLines.add(it)
                }
                return Triple(
                    resp.code,
                    "",
                    ChatCallOutcome(
                        text = streamRead.text,
                        diagnosticLog = traceLines.joinToString("\n"),
                        tokenUsage = resolvedUsage,
                        modelName = config.model.trim()
                    )
                )
            }
        }

        val (initialBody, hadThinkingHint) = buildBody(
            withTemp = includeTemp,
            thinkingOverride = null
        )
        var (code, respText, streamOutcome) = sendOnce(initialBody, useStream)

        // Short-circuit immediately when an endpoint rejects multimodal image_url/array content with HTTP 400/415/422
        // so we NEVER run thinking-hint fallback retries or pollute unsupportedThinkingHintKeys, and remember it!
        if (code in setOf(400, 415, 422) && imageJpegBase64.isNotBlank() && isVisionPayloadRejectionHttp400(respText)) {
            markVisionUnsupported(config.baseUrl, config.model)
            throw IOException(
                "当前模型不支持多模态图片输入 ($code): ${extractErrorDetail(respText)}\n--- 诊断日志 ---\n${traceLines.joinToString("\n")}"
            )
        }

        // Safe fallback when a legacy OpenAI-compatible proxy rejects `stream_options.include_usage` with HTTP 400
        if (code == 400 && useStream && useStreamOptions &&
            (respText.contains("stream_options", ignoreCase = true) || respText.contains("include_usage", ignoreCase = true))
        ) {
            unsupportedStreamOptionsKeys.add(streamOptionsKey)
            useStreamOptions = false
            val (noOptsBody, _) = buildBody(
                withTemp = includeTemp,
                thinkingOverride = null,
                enableStream = true,
                includeStreamUsage = false
            )
            val noOptsRes = sendOnce(noOptsBody, true)
            code = noOptsRes.first
            respText = noOptsRes.second
            streamOutcome = noOptsRes.third
        }

        if (code == 400 && useStream && respText.contains("stream", ignoreCase = true)) {
            useStreamOptions = false
            val (nonStreamBody, _) = buildBody(
                withTemp = includeTemp,
                thinkingOverride = null,
                enableStream = false,
                includeStreamUsage = false
            )
            val nonStreamRes = sendOnce(nonStreamBody, false)
            code = nonStreamRes.first
            respText = nonStreamRes.second
            streamOutcome = nonStreamRes.third
        }

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
                val altRetry = sendOnce(altBody, useStream)
                code = altRetry.first
                respText = altRetry.second
                streamOutcome = altRetry.third
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
            val retry = sendOnce(fallbackBody, useStream)
            code = retry.first
            respText = retry.second
            streamOutcome = retry.third

            if (code == 400 && includeTemp && !rejectTemp) {
                val (bareBody, _) = buildBody(withTemp = false, thinkingOverride = "none")
                val secondRetry = sendOnce(bareBody, useStream)
                code = secondRetry.first
                respText = secondRetry.second
                streamOutcome = secondRetry.third
            }
        }

        if (code !in 200..299) {
            throw IOException(
                "LLM 接口错误 ($code): ${extractErrorDetail(respText)}\n--- 诊断日志 ---\n${traceLines.joinToString("\n")}"
            )
        }
        if (streamOutcome != null) {
            return streamOutcome
        }
        val parsedText = parseOpenAiChatResponse(respText)
        val parsedUsage = extractOpenAiTokenUsageFromBody(respText)
            ?: TokenUsage.estimateFromTexts("$systemPrompt\n$userPrompt", parsedText)
        (extractUsageDiagnostics(respText) ?: formatTokenUsageDiagnostics(parsedUsage))?.let {
            traceLines.add(it)
        }
        return ChatCallOutcome(
            text = parsedText,
            diagnosticLog = traceLines.joinToString("\n"),
            tokenUsage = parsedUsage,
            modelName = config.model.trim()
        )
    }

    internal fun callGeminiMultimodalAudio(
        config: MultimodalConfig,
        prompt: String,
        base64Wav: String,
        mimeType: String = "audio/wav",
        imageJpegBase64: String = ""
    ): String = callGeminiMultimodalAudioWithDiagnostics(
        config = config,
        prompt = prompt,
        base64Wav = base64Wav,
        mimeType = mimeType,
        imageJpegBase64 = imageJpegBase64
    ).text

    internal fun callGeminiMultimodalAudioWithDiagnostics(
        config: MultimodalConfig,
        prompt: String,
        base64Wav: String,
        mimeType: String = "audio/wav",
        imageJpegBase64: String = ""
    ): ChatCallOutcome {
        val parts = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("text", prompt)
            })
            if (imageJpegBase64.isNotBlank()) {
                add(JsonObject().apply {
                    add("inline_data", JsonObject().apply {
                        addProperty("mime_type", "image/jpeg")
                        addProperty("data", imageJpegBase64)
                    })
                })
            }
            add(JsonObject().apply {
                add("inline_data", JsonObject().apply {
                    addProperty("mime_type", mimeType)
                    addProperty("data", base64Wav)
                })
            })
        }
        return executeGeminiGenerateContentWithDiagnostics(config, parts)
    }

    internal fun callGeminiTextOnly(
        config: MultimodalConfig,
        fullPrompt: String
    ): String = callGeminiTextOnlyWithDiagnostics(config, fullPrompt).text

    internal fun callGeminiTextOnlyWithDiagnostics(
        config: MultimodalConfig,
        fullPrompt: String,
        imageJpegBase64: String = "",
        forJevCopilot: Boolean = false,
        maxOutputTokens: Int? = null,
        onThinkingStarted: (() -> Unit)? = null,
        onStreamingTextUpdate: ((String) -> Unit)? = null
    ): ChatCallOutcome {
        val parts = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("text", fullPrompt)
            })
            if (imageJpegBase64.isNotBlank()) {
                add(JsonObject().apply {
                    add("inline_data", JsonObject().apply {
                        addProperty("mime_type", "image/jpeg")
                        addProperty("data", imageJpegBase64)
                    })
                })
            }
        }
        return executeGeminiGenerateContentWithDiagnostics(
            config = config,
            parts = parts,
            forJevCopilot = forJevCopilot,
            maxOutputTokens = maxOutputTokens,
            onThinkingStarted = onThinkingStarted,
            onStreamingTextUpdate = onStreamingTextUpdate
        )
    }

    private fun executeGeminiGenerateContentWithDiagnostics(
        config: MultimodalConfig,
        parts: JsonArray,
        forJevCopilot: Boolean = false,
        maxOutputTokens: Int? = null,
        onThinkingStarted: (() -> Unit)? = null,
        onStreamingTextUpdate: ((String) -> Unit)? = null
    ): ChatCallOutcome {
        val useStream = onStreamingTextUpdate != null
        val nonStreamUrl = EndpointResolver.resolveGeminiEndpoint(config.baseUrl, config.model, config.apiKey)
        val url = if (useStream) {
            EndpointResolver.resolveGeminiStreamEndpoint(config.baseUrl, config.model, config.apiKey)
        } else {
            nonStreamUrl
        }
        val maskedUrl = if (useStream) {
            EndpointResolver.resolveGeminiStreamEndpoint(
                config.baseUrl,
                config.model,
                if (config.apiKey.isBlank()) "" else "***"
            )
        } else {
            EndpointResolver.resolveGeminiEndpoint(
                config.baseUrl,
                config.model,
                if (config.apiKey.isBlank()) "" else "***"
            )
        }
        val genConfig = buildGeminiGenerationConfig(
            model = config.model,
            temperature = config.temperature,
            thinkingMode = config.thinkingMode,
            includeThinkingHint = true,
            forJevCopilot = forJevCopilot,
            maxOutputTokens = maxOutputTokens
        )
        val traceLines = mutableListOf<String>()
        traceLines.add("POST $maskedUrl")
        traceLines.add("Model=${config.model.trim()} | ThinkingMode=${config.thinkingMode.name} | Stream=$useStream")

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
        fun sendOnce(
            targetUrl: String,
            payload: JsonObject,
            label: String,
            attemptStream: Boolean
        ): Triple<Int, String, ChatCallOutcome?> {
            attemptNo += 1
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            if (config.apiKey.isNotBlank()) {
                reqBuilder.header("x-goog-api-key", config.apiKey.trim())
            }
            val t0 = System.nanoTime()
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code !in 200..299 || !attemptStream) {
                    val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                    val text = resp.body?.string().orEmpty()
                    if (resp.code in 200..299) {
                        traceLines.add("#$attemptNo [$label] -> HTTP ${resp.code} (${ms} ms)")
                    } else {
                        traceLines.add("#$attemptNo [$label] -> HTTP ${resp.code} (${ms} ms): ${extractErrorDetail(text)}")
                    }
                    return Triple(resp.code, text, null)
                }
                val streamRead = readGeminiStreamOrBufferedJson(
                    reader = resp.body?.charStream()?.buffered(),
                    onThinkingStarted = onThinkingStarted,
                    onStreamingTextUpdate = onStreamingTextUpdate
                )
                val fallbackPromptText = extractTextFromGeminiParts(parts)
                val resolvedUsage = streamRead.tokenUsage
                    ?: TokenUsage.estimateFromTexts(fallbackPromptText, streamRead.text)
                val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                traceLines.add("#$attemptNo [$label] -> HTTP ${resp.code} (${ms} ms stream)")
                (streamRead.usageDiagnostic ?: formatTokenUsageDiagnostics(resolvedUsage))?.let {
                    traceLines.add(it)
                }
                return Triple(
                    resp.code,
                    "",
                    ChatCallOutcome(
                        text = streamRead.text,
                        diagnosticLog = traceLines.joinToString("\n"),
                        tokenUsage = resolvedUsage,
                        modelName = config.model.trim()
                    )
                )
            }
        }

        var activeStream = useStream
        var activeUrl = url
        var (code, respText, streamOutcome) = sendOnce(
            targetUrl = activeUrl,
            payload = buildPayload(genConfig),
            label = genConfig?.toString() ?: "bare",
            attemptStream = activeStream
        )
        if (activeStream && code in setOf(404, 405)) {
            activeStream = false
            activeUrl = nonStreamUrl
            val nonStreamRetry = sendOnce(
                targetUrl = activeUrl,
                payload = buildPayload(genConfig),
                label = "non-stream-fallback",
                attemptStream = false
            )
            code = nonStreamRetry.first
            respText = nonStreamRetry.second
            streamOutcome = nonStreamRetry.third
        }
        if (code == 400 && genConfig != null) {
            val bareConfig = if (maxOutputTokens != null && maxOutputTokens > 0) {
                JsonObject().apply { addProperty("maxOutputTokens", maxOutputTokens) }
            } else {
                null
            }
            val retry = sendOnce(
                targetUrl = activeUrl,
                payload = buildPayload(overrideGenConfig = bareConfig),
                label = "bare-fallback",
                attemptStream = activeStream
            )
            code = retry.first
            respText = retry.second
            streamOutcome = retry.third
        }

        if (code !in 200..299) {
            throw IOException(
                "Gemini 接口错误 ($code): ${extractErrorDetail(respText)}\n--- 诊断日志 ---\n${traceLines.joinToString("\n")}"
            )
        }
        if (streamOutcome != null) {
            return streamOutcome
        }
        val parsedText = parseGeminiResponse(respText)
        val parsedUsage = extractGeminiTokenUsageFromBody(respText)
            ?: TokenUsage.estimateFromTexts(extractTextFromGeminiParts(parts), parsedText)
        formatTokenUsageDiagnostics(parsedUsage)?.let { traceLines.add(it) }
        return ChatCallOutcome(
            text = parsedText,
            diagnosticLog = traceLines.joinToString("\n"),
            tokenUsage = parsedUsage,
            modelName = config.model.trim()
        )
    }

    internal fun callOpenAiMultimodalAudio(
        config: MultimodalConfig,
        prompt: String,
        base64Wav: String
    ): String = callOpenAiMultimodalAudioWithDiagnostics(config, prompt, base64Wav).text

    internal fun callOpenAiMultimodalAudioWithDiagnostics(
        config: MultimodalConfig,
        prompt: String,
        base64Wav: String
    ): ChatCallOutcome {
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
        val parsedText = parseOpenAiChatResponse(respText)
        val parsedUsage = extractOpenAiTokenUsageFromBody(respText)
            ?: TokenUsage.estimateFromTexts(prompt, parsedText)
        return ChatCallOutcome(
            text = parsedText,
            diagnosticLog = formatTokenUsageDiagnostics(parsedUsage).orEmpty(),
            tokenUsage = parsedUsage,
            modelName = config.model.trim()
        )
    }

    companion object {
        internal data class StreamReadOutcome(
            val text: String,
            val usageDiagnostic: String?,
            val tokenUsage: TokenUsage?
        )

        private const val PREWARM_COOLDOWN_MS = 25_000L
        private const val JEV_GEMINI_MAX_OUTPUT_TOKENS = 1536
        private val OPT_CLOSE_TAG_REGEX = Regex("</opt>", RegexOption.IGNORE_CASE)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SPECIAL_TOKEN_REGEX = Regex("<\\|[^|]+\\|>")
        private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
        private val UNCLOSED_THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*$", RegexOption.IGNORE_CASE)
        private val unsupportedThinkingHintKeys: MutableSet<String> =
            Collections.newSetFromMap(ConcurrentHashMap())
        private val unsupportedStreamOptionsKeys: MutableSet<String> =
            Collections.newSetFromMap(ConcurrentHashMap())
        private val preferredThinkingSyntaxCache: ConcurrentHashMap<String, String> =
            ConcurrentHashMap()
        private val runtimeNonVisionModelKeys: MutableSet<String> =
            Collections.newSetFromMap(ConcurrentHashMap())

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
            unsupportedStreamOptionsKeys.clear()
            preferredThinkingSyntaxCache.clear()
            runtimeNonVisionModelKeys.clear()
        }

        private fun visionCacheKey(baseUrl: String, model: String): String =
            "${baseUrl.trim().lowercase()}|${model.trim().lowercase()}"

        internal fun markVisionUnsupported(baseUrl: String, model: String) {
            if (model.isNotBlank()) {
                runtimeNonVisionModelKeys.add(visionCacheKey(baseUrl, model))
            }
        }

        fun isVisionMarkedUnsupported(baseUrl: String, model: String): Boolean {
            if (model.isBlank()) return false
            return visionCacheKey(baseUrl, model) in runtimeNonVisionModelKeys
        }

        fun isThinkingHintMarkedUnsupported(baseUrl: String, model: String, mode: ThinkingMode): Boolean {
            return thinkingCacheKey(baseUrl, model, mode) in unsupportedThinkingHintKeys
        }

        fun isKnownPureTextModel(model: String): Boolean {
            val lower = model.trim().lowercase()
            val code = normalizeModelCode(model)
            if (code.isEmpty()) return false
            val hasVisionMarker = lower.contains("vl") ||
                lower.contains("vision") ||
                lower.contains("omni") ||
                lower.contains("4v") ||
                lower.contains("-v-") ||
                lower.endsWith("-v")
            return when {
                code.startsWith("deepseek-") && !code.contains("vl") -> true
                code.startsWith("qwen-turbo") || code.startsWith("qwen-plus") || code.startsWith("qwen-max") ->
                    !hasVisionMarker
                (code.startsWith("qwen2.5-") || code.startsWith("qwen2-") || code.startsWith("qwen3-") || lower.contains("qwen3-")) &&
                    !hasVisionMarker -> true
                (code.startsWith("doubao-1-5-lite") || code.startsWith("doubao-1-5-pro") ||
                    code.startsWith("doubao-lite") || code.startsWith("doubao-pro")) &&
                    !lower.contains("vision") -> true
                (code.startsWith("glm-4-flash") || code.startsWith("glm-4-air") ||
                    code.startsWith("glm-4-plus") || code.startsWith("glm-4-9b") || code == "glm-4") &&
                    !hasVisionMarker -> true
                (code.startsWith("yi-lightning") || code.startsWith("llama-3.3") || code.startsWith("llama-3.1")) &&
                    !hasVisionMarker -> true
                else -> false
            }
        }

        fun canRouteVisionScreenshot(
            primaryBaseUrl: String,
            primaryModel: String,
            fallbackGeminiConfig: MultimodalConfig?
        ): Boolean {
            if (EndpointResolver.isGeminiNativeHttpUrl(primaryBaseUrl)) return true
            if (isKnownPureTextModel(primaryModel)) {
                return fallbackGeminiConfig != null
            }
            return true
        }

        fun isVisionPayloadRejectionHttp400(respBody: String): Boolean {
            val lower = respBody.lowercase()
            return lower.contains("image_url") ||
                lower.contains("multimodal") ||
                lower.contains("vision") ||
                lower.contains("content must be a string") ||
                lower.contains("expected a string") ||
                lower.contains("invalid type: sequence") ||
                lower.contains("unsupported content type") ||
                lower.contains("does not support image") ||
                lower.contains("not support image") ||
                lower.contains("image input") ||
                respBody.contains("不支持图片") ||
                respBody.contains("不支持图像") ||
                respBody.contains("不支持多模态") ||
                respBody.contains("不支持视觉") ||
                (respBody.contains("多模态") && respBody.contains("不支持"))
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

        internal fun sumTokenUsages(usages: List<TokenUsage?>): TokenUsage? {
            var acc: TokenUsage? = null
            for (u in usages) {
                if (u != null && u.hasUsage) {
                    acc = if (acc == null) u else acc + u
                }
            }
            return acc
        }

        internal fun extractTextFromGeminiParts(parts: JsonArray): String {
            val sb = StringBuilder()
            parts.forEach { el ->
                if (el.isJsonObject) {
                    el.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.let {
                        sb.append(it)
                    }
                }
            }
            return sb.toString()
        }

        /**
         * Parses structured [TokenUsage] from an OpenAI-compatible `usage` JSON object.
         * Normalizes `completionTokens` to visible output tokens so OpenAI/DeepSeek/Ark responses
         * (where `completion_tokens` already includes `reasoning_tokens`) are never double-counted.
         */
        internal fun extractOpenAiTokenUsage(usage: JsonObject?): TokenUsage? {
            if (usage == null) return null
            val promptTok = usage.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asLong?.coerceAtLeast(0L) ?: 0L
            val cachedTok = (usage.getAsJsonObject("prompt_tokens_details")
                ?.get("cached_tokens")?.takeIf { it.isJsonPrimitive }?.asLong
                ?: usage.get("prompt_cache_hit_tokens")?.takeIf { it.isJsonPrimitive }?.asLong
                ?: 0L).coerceIn(0L, promptTok)
            val rawCompTok = usage.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asLong?.coerceAtLeast(0L) ?: 0L
            val reasoningTok = (usage.getAsJsonObject("completion_tokens_details")
                ?.get("reasoning_tokens")?.takeIf { it.isJsonPrimitive }?.asLong
                ?: 0L).coerceAtLeast(0L)
            val rawTotal = usage.get("total_tokens")?.takeIf { it.isJsonPrimitive }?.asLong?.coerceAtLeast(0L) ?: 0L
            if (promptTok == 0L && rawCompTok == 0L && reasoningTok == 0L && rawTotal == 0L) return null

            val visibleComp = when {
                reasoningTok <= 0L -> rawCompTok
                rawTotal > 0L && rawTotal >= promptTok + rawCompTok + reasoningTok -> rawCompTok
                rawCompTok >= reasoningTok -> (rawCompTok - reasoningTok).coerceAtLeast(0L)
                else -> rawCompTok
            }
            val effectiveTotal = if (rawTotal > 0L) {
                maxOf(rawTotal, promptTok + visibleComp + reasoningTok)
            } else {
                promptTok + visibleComp + reasoningTok
            }
            return TokenUsage(
                promptTokens = promptTok,
                cachedTokens = cachedTok,
                completionTokens = visibleComp,
                reasoningTokens = reasoningTok,
                totalTokensOverride = effectiveTotal,
                isEstimated = false
            )
        }

        internal fun extractOpenAiTokenUsageFromBody(respBody: String): TokenUsage? {
            return runCatching {
                val root = JsonParser.parseString(respBody.trim())
                if (!root.isJsonObject) return@runCatching null
                extractOpenAiTokenUsage(root.asJsonObject.getAsJsonObject("usage"))
            }.getOrNull()
        }

        /**
         * Parses structured [TokenUsage] from a Google Gemini `usageMetadata` JSON object.
         * Gemini reports `candidatesTokenCount` and `thoughtsTokenCount` as separate non-overlapping counts.
         */
        internal fun extractGeminiTokenUsage(usageMetadata: JsonObject?): TokenUsage? {
            if (usageMetadata == null) return null
            val promptTok = usageMetadata.get("promptTokenCount")?.takeIf { it.isJsonPrimitive }?.asLong?.coerceAtLeast(0L) ?: 0L
            val cachedTok = (usageMetadata.get("cachedContentTokenCount")
                ?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L).coerceIn(0L, promptTok)
            val candTok = usageMetadata.get("candidatesTokenCount")?.takeIf { it.isJsonPrimitive }?.asLong?.coerceAtLeast(0L) ?: 0L
            val thoughtsTok = usageMetadata.get("thoughtsTokenCount")?.takeIf { it.isJsonPrimitive }?.asLong?.coerceAtLeast(0L) ?: 0L
            val totalTok = usageMetadata.get("totalTokenCount")?.takeIf { it.isJsonPrimitive }?.asLong?.coerceAtLeast(0L) ?: 0L
            if (promptTok == 0L && candTok == 0L && thoughtsTok == 0L && totalTok == 0L) return null
            val effectiveTotal = if (totalTok > 0L) {
                maxOf(totalTok, promptTok + candTok + thoughtsTok)
            } else {
                promptTok + candTok + thoughtsTok
            }
            return TokenUsage(
                promptTokens = promptTok,
                cachedTokens = cachedTok,
                completionTokens = candTok,
                reasoningTokens = thoughtsTok,
                totalTokensOverride = effectiveTotal,
                isEstimated = false
            )
        }

        internal fun extractGeminiTokenUsageFromBody(respBody: String): TokenUsage? {
            return runCatching {
                val parsed = JsonParser.parseString(respBody.trim())
                when {
                    parsed.isJsonObject -> extractGeminiTokenUsage(parsed.asJsonObject.getAsJsonObject("usageMetadata"))
                    parsed.isJsonArray -> {
                        var lastUsage: TokenUsage? = null
                        parsed.asJsonArray.forEach { el ->
                            if (el.isJsonObject) {
                                extractGeminiTokenUsage(el.asJsonObject.getAsJsonObject("usageMetadata"))?.let {
                                    lastUsage = it
                                }
                            }
                        }
                        lastUsage
                    }
                    else -> null
                }
            }.getOrNull()
        }

        internal fun formatTokenUsageDiagnostics(usage: TokenUsage?, reasoningTextLen: Int = 0): String? {
            if (usage == null || !usage.hasUsage) return null
            return buildString {
                append("Tokens: in=${usage.safePromptTokens}")
                if (usage.safeCachedTokens > 0L) append(" (cache=${usage.safeCachedTokens})")
                append(" | out=${usage.visibleOutputTokens}")
                if (usage.safeReasoningTokens > 0L) append(" | reasoning=${usage.safeReasoningTokens}")
                if (usage.isEstimated) append(" (est)")
                if (reasoningTextLen > 0) append(" (reasoning_chars=$reasoningTextLen)")
            }
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
            thinkingMode: ThinkingMode,
            forJevCopilot: Boolean = false
        ): Boolean {
            if (thinkingMode == ThinkingMode.AUTO && !forJevCopilot) return false
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

            if (thinkingMode == ThinkingMode.AUTO && forJevCopilot) {
                val isDeepSeekHybridAuto = code == "deepseek-chat" ||
                    code.startsWith("deepseek-v3.1") ||
                    code.startsWith("deepseek-v3-1") ||
                    code.startsWith("deepseek-v3.2") ||
                    code.startsWith("deepseek-v3-2")
                if (isDeepSeekHybridAuto) {
                    if (lowerUrl.contains("siliconflow")) {
                        body.addProperty("enable_thinking", true)
                    } else {
                        body.add("thinking", JsonObject().apply {
                            addProperty("type", "enabled")
                        })
                    }
                    return true
                }
                val isDoubaoSeed2PlusAuto = code.startsWith("doubao-seed-2") ||
                    code.startsWith("doubao-seed-3") ||
                    code.startsWith("doubao-2") ||
                    code.startsWith("doubao-3")
                if (isDoubaoSeed2PlusAuto) {
                    body.addProperty("reasoning_effort", "medium")
                    return true
                }
                val isQwenHybridAuto = (code.startsWith("qwen") &&
                    !code.startsWith("qwen2") &&
                    !code.startsWith("qwen1")) ||
                    rawLowerModel.contains("qwen3") ||
                    rawLowerModel.contains("qwen4")
                val isDashScopeHybridAuto = lowerUrl.contains("dashscope.aliyuncs.com") &&
                    !code.startsWith("qwen2") &&
                    !code.startsWith("qwen1")
                if (isQwenHybridAuto || isDashScopeHybridAuto) {
                    body.addProperty("enable_thinking", true)
                    return true
                }
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

        /**
         * Builds the `multipart/form-data` request body for DeepSeek Official ASR (`POST /v1/tools/asr`).
         * Strictly uploads ONLY the `file` field (`16-bit PCM WAV`, `audio/wav`) and NEVER includes
         * `model`, `language`, `prompt`, or `response_format` fields.
         */
        fun buildDeepSeekAsrMultipartBody(wavFile: File): MultipartBody {
            return MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    wavFile.name,
                    wavFile.asRequestBody("audio/wav".toMediaType())
                )
                .build()
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
            includeThinkingHint: Boolean = true,
            forJevCopilot: Boolean = false,
            maxOutputTokens: Int? = null
        ): JsonObject? {
            val cfg = JsonObject()
            if (!isSamplingTemperatureDeprecated(model)) {
                cfg.addProperty("temperature", temperature)
            }
            if (maxOutputTokens != null && maxOutputTokens > 0) {
                cfg.addProperty("maxOutputTokens", maxOutputTokens)
            }
            if (includeThinkingHint) {
                val code = normalizeModelCode(model)
                val isNonThinkingSpecial = code.contains("tts") ||
                    code.contains("live") ||
                    code.contains("image") ||
                    code.contains("transcribe")
                if (forJevCopilot && !isNonThinkingSpecial) {
                    when (thinkingMode) {
                        ThinkingMode.AUTO -> {
                            when {
                                code.startsWith("gemini-2.5") -> {
                                    cfg.add("thinkingConfig", JsonObject().apply {
                                        addProperty("thinkingBudget", 256)
                                    })
                                }
                                code.startsWith("gemini-3") && code.contains("lite") -> {
                                    cfg.add("thinkingConfig", JsonObject().apply {
                                        addProperty("thinkingLevel", "LOW")
                                    })
                                }
                                code.startsWith("gemini-3") || code.startsWith("gemini-4") -> {
                                    cfg.add("thinkingConfig", JsonObject().apply {
                                        addProperty("thinkingLevel", "MEDIUM")
                                    })
                                }
                            }
                        }
                        ThinkingMode.ENABLED -> {
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
                        ThinkingMode.DISABLED -> {
                            if (code.startsWith("gemini-2.5") && !code.contains("pro")) {
                                cfg.add("thinkingConfig", JsonObject().apply {
                                    addProperty("thinkingBudget", 0)
                                })
                            } else {
                                recommendedGeminiThinkingLevel(model)?.let { level ->
                                    cfg.add("thinkingConfig", JsonObject().apply {
                                        addProperty("thinkingLevel", level)
                                    })
                                }
                            }
                        }
                    }
                } else if (thinkingMode != ThinkingMode.AUTO) {
                    when (thinkingMode) {
                        ThinkingMode.DISABLED -> {
                            if (code.startsWith("gemini-2.5") && !code.contains("pro")) {
                                cfg.add("thinkingConfig", JsonObject().apply {
                                    addProperty("thinkingBudget", 0)
                                })
                            } else {
                                recommendedGeminiThinkingLevel(model)?.let { level ->
                                    cfg.add("thinkingConfig", JsonObject().apply {
                                        addProperty("thinkingLevel", level)
                                    })
                                }
                            }
                        }
                        ThinkingMode.ENABLED -> {
                            if (!isNonThinkingSpecial) {
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
            }
            return if (cfg.size() > 0) cfg else null
        }

        internal fun readOpenAiStreamOrBufferedJson(
            reader: java.io.BufferedReader?,
            onThinkingStarted: (() -> Unit)? = null,
            onStreamingTextUpdate: ((String) -> Unit)? = null
        ): StreamReadOutcome {
            if (reader == null) throw IOException("模型返回结果为空")
            var firstNonBlank: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    firstNonBlank = line
                    break
                }
            }
            if (firstNonBlank == null) throw IOException("模型返回结果为空")
            val trimmedStart = firstNonBlank.trimStart()
            if (trimmedStart.startsWith("{") || trimmedStart.startsWith("[")) {
                val rest = reader.readText()
                val fullBody = if (rest.isEmpty()) firstNonBlank else "$firstNonBlank\n$rest"
                val parsed = parseOpenAiChatResponse(fullBody)
                val usage = extractUsageDiagnostics(fullBody)
                val tokenUsage = extractOpenAiTokenUsageFromBody(fullBody)
                return StreamReadOutcome(parsed, usage, tokenUsage)
            }

            val rawAcc = StringBuilder()
            var thinkingEmitted = false
            var lastUsageChunk: JsonObject? = null
            var currLine: String? = firstNonBlank
            while (currLine != null) {
                val t = currLine.trim()
                if (t.startsWith("data:")) {
                    val data = t.removePrefix("data:").trim()
                    if (data.isNotEmpty() && data != "[DONE]") {
                        runCatching {
                            val obj = JsonParser.parseString(data).asJsonObject
                            obj.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { lastUsageChunk = it }
                            val choices = obj.getAsJsonArray("choices")
                            if (choices != null && choices.size() > 0) {
                                val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                                val reasoningDelta = delta?.get("reasoning_content")
                                    ?.takeIf { it.isJsonPrimitive }
                                    ?.asString
                                    .orEmpty()
                                if (reasoningDelta.isNotEmpty() && !thinkingEmitted) {
                                    thinkingEmitted = true
                                    onThinkingStarted?.invoke()
                                }
                                val contentDelta = delta?.get("content")
                                    ?.takeIf { it.isJsonPrimitive }
                                    ?.asString
                                    .orEmpty()
                                if (contentDelta.isNotEmpty()) {
                                    rawAcc.append(contentDelta)
                                    val accStr = rawAcc.toString()
                                    val hasUnclosedThink = accStr.contains("<think>", ignoreCase = true) &&
                                        !accStr.contains("</think>", ignoreCase = true)
                                    if (hasUnclosedThink) {
                                        if (!thinkingEmitted) {
                                            thinkingEmitted = true
                                            onThinkingStarted?.invoke()
                                        }
                                    } else {
                                        val cleanSoFar = cleanModelOutput(
                                            accStr.replace(UNCLOSED_THINK_BLOCK_REGEX, "")
                                        )
                                        if (cleanSoFar.isNotEmpty()) {
                                            onStreamingTextUpdate?.invoke(cleanSoFar)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                currLine = reader.readLine()
            }
            val parsedUsage = extractOpenAiTokenUsage(lastUsageChunk)
            val usageDiag = formatTokenUsageDiagnostics(parsedUsage)
            return StreamReadOutcome(cleanModelOutput(rawAcc.toString()), usageDiag, parsedUsage)
        }

        internal fun readGeminiStreamOrBufferedJson(
            reader: java.io.BufferedReader?,
            onThinkingStarted: (() -> Unit)? = null,
            onStreamingTextUpdate: ((String) -> Unit)? = null
        ): StreamReadOutcome {
            if (reader == null) throw IOException("Gemini 未返回候选文本")
            var firstNonBlank: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    firstNonBlank = line
                    break
                }
            }
            if (firstNonBlank == null) throw IOException("Gemini 未返回候选文本")
            val trimmedStart = firstNonBlank.trimStart()
            if (trimmedStart.startsWith("{")) {
                val rest = reader.readText()
                val fullBody = if (rest.isEmpty()) firstNonBlank else "$firstNonBlank\n$rest"
                val parsedUsage = extractGeminiTokenUsageFromBody(fullBody)
                return StreamReadOutcome(
                    text = parseGeminiResponse(fullBody),
                    usageDiagnostic = formatTokenUsageDiagnostics(parsedUsage),
                    tokenUsage = parsedUsage
                )
            }
            if (trimmedStart.startsWith("[")) {
                val rest = reader.readText()
                val fullBody = if (rest.isEmpty()) firstNonBlank else "$firstNonBlank\n$rest"
                val arr = JsonParser.parseString(fullBody).asJsonArray
                val sb = StringBuilder()
                var lastUsage: TokenUsage? = null
                arr.forEach { chunk ->
                    val chunkObj = chunk.asJsonObject
                    extractGeminiTokenUsage(chunkObj.getAsJsonObject("usageMetadata"))?.let { lastUsage = it }
                    val parts = chunkObj.getAsJsonArray("candidates")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")
                    parts?.forEach { p ->
                        val pObj = p.asJsonObject
                        val isThought = pObj.get("thought")?.let {
                            it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
                        } ?: false
                        if (!isThought) {
                            sb.append(pObj.get("text")?.asString.orEmpty())
                        }
                    }
                }
                return StreamReadOutcome(
                    text = cleanModelOutput(sb.toString()),
                    usageDiagnostic = formatTokenUsageDiagnostics(lastUsage),
                    tokenUsage = lastUsage
                )
            }

            val accText = StringBuilder()
            var thinkingEmitted = false
            var thoughtsTok = 0L
            var candTok = 0L
            var promptTok = 0L
            var cachedTok = 0L
            var totalTok = 0L
            var currLine: String? = firstNonBlank
            while (currLine != null) {
                val t = currLine.trim()
                if (t.startsWith("data:")) {
                    val data = t.removePrefix("data:").trim()
                    if (data.isNotEmpty() && data != "[DONE]") {
                        runCatching {
                            val obj = JsonParser.parseString(data).asJsonObject
                            obj.getAsJsonObject("usageMetadata")?.let { um ->
                                um.get("promptTokenCount")?.takeIf { it.isJsonPrimitive }?.asLong?.let { promptTok = it.coerceAtLeast(0L) }
                                um.get("cachedContentTokenCount")?.takeIf { it.isJsonPrimitive }?.asLong?.let { cachedTok = it.coerceAtLeast(0L) }
                                um.get("thoughtsTokenCount")?.takeIf { it.isJsonPrimitive }?.asLong?.let { thoughtsTok = it.coerceAtLeast(0L) }
                                um.get("candidatesTokenCount")?.takeIf { it.isJsonPrimitive }?.asLong?.let { candTok = it.coerceAtLeast(0L) }
                                um.get("totalTokenCount")?.takeIf { it.isJsonPrimitive }?.asLong?.let { totalTok = it.coerceAtLeast(0L) }
                            }
                            val candidates = obj.getAsJsonArray("candidates")
                            if (candidates != null && candidates.size() > 0) {
                                val parts = candidates[0].asJsonObject
                                    .getAsJsonObject("content")
                                    ?.getAsJsonArray("parts")
                                parts?.forEach { partElem ->
                                    val pObj = partElem.asJsonObject
                                    val isThought = pObj.get("thought")?.let {
                                        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
                                    } ?: false
                                    if (isThought) {
                                        if (!thinkingEmitted) {
                                            thinkingEmitted = true
                                            onThinkingStarted?.invoke()
                                        }
                                    } else {
                                        val txt = pObj.get("text")
                                            ?.takeIf { it.isJsonPrimitive }
                                            ?.asString
                                            .orEmpty()
                                        if (txt.isNotEmpty()) {
                                            accText.append(txt)
                                            val cleanSoFar = cleanModelOutput(accText.toString())
                                            if (cleanSoFar.isNotEmpty()) {
                                                onStreamingTextUpdate?.invoke(cleanSoFar)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                currLine = reader.readLine()
            }
            val parsedUsage = if (promptTok > 0L || thoughtsTok > 0L || candTok > 0L || totalTok > 0L) {
                TokenUsage(
                    promptTokens = promptTok,
                    cachedTokens = cachedTok.coerceIn(0L, promptTok),
                    completionTokens = candTok,
                    reasoningTokens = thoughtsTok,
                    totalTokensOverride = maxOf(totalTok, promptTok + candTok + thoughtsTok),
                    isEstimated = false
                )
            } else {
                null
            }
            return StreamReadOutcome(
                text = cleanModelOutput(accText.toString()),
                usageDiagnostic = formatTokenUsageDiagnostics(parsedUsage),
                tokenUsage = parsedUsage
            )
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
                ModelCapabilitySlot.LLM,
                ModelCapabilitySlot.JEV_COPILOT -> {
                    // Universal chat eligibility: filter out non-chat utility/embedding/STT/live models,
                    // while letting real live-probed `ms` latency badges replace hardcoded speed strings.
                    val isPureStt = lower.contains("whisper") || lower.contains("sensevoice") || lower.endsWith("-asr")
                    if (!isNonTextOrUtility && !isPureStt && !lower.contains("live")) {
                        recommended = true
                        if (slot == ModelCapabilitySlot.JEV_COPILOT) {
                            val isHighIqFlagship = code.startsWith("gemini-3.8-flash") ||
                                code.startsWith("gemini-3.1-pro") ||
                                code.startsWith("gemini-2.5-pro") ||
                                code == "deepseek-chat" ||
                                code == "deepseek-reasoner" ||
                                (code.startsWith("doubao-seed-2") && code.contains("pro")) ||
                                code.startsWith("qwen3-max") ||
                                code.startsWith("qwen-max") ||
                                code.startsWith("gpt-4o") ||
                                code.startsWith("claude-")
                            if (isHighIqFlagship) {
                                badge = "🧠 军师推荐"
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
                ModelCapabilitySlot.JEV_COPILOT -> when {
                    code == "gemini-3.8-flash" -> 106
                    code.startsWith("gemini-3.1-pro") -> 105
                    code.startsWith("doubao-seed-2") && code.contains("pro") -> 104
                    code == "deepseek-chat" -> 103
                    code == "deepseek-reasoner" -> 102
                    code == "gemini-2.5-pro" -> 101
                    code.startsWith("qwen3-max") || code.startsWith("qwen-max") -> 100
                    code == "gpt-4o" -> 99
                    code.startsWith("claude-") -> 98
                    code == "gemini-2.5-flash" -> 95
                    code == "gemini-3.5-flash-lite" -> 92
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
