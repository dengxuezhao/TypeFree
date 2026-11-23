package com.typefree.ime.network

import android.os.ParcelFileDescriptor
import com.typefree.core.offline.AiTask
import com.typefree.core.offline.AsrRoute
import com.typefree.core.offline.ExecutionSource
import com.typefree.core.offline.LocalModelInstallStatus
import com.typefree.core.offline.LocalModelRecord
import com.typefree.core.offline.LocalModelRegistrySnapshot
import com.typefree.core.offline.LocalModelSlot
import com.typefree.core.offline.OfflineMode
import com.typefree.core.offline.OfflineModelCatalog
import com.typefree.core.offline.PendingOfflineRestore
import com.typefree.core.offline.ReadinessState
import com.typefree.core.offline.RouteBlockReason
import com.typefree.core.offline.TextRoute
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.LocalDictionaryPostProcessor
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.OfflineModelConfig
import com.typefree.ime.data.OfflineRouteBridge
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SecretVault
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.ime.StreamingComposer
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.ime.predict.AiSentenceCompleter
import com.typefree.ime.localai.AsrParams
import com.typefree.ime.localai.GenRequest
import com.typefree.ime.localai.IAsrCallback
import com.typefree.ime.localai.ILocalAiService
import com.typefree.ime.localai.ITokenCallback
import com.typefree.ime.localai.LocalAiServiceClient
import com.typefree.ime.localai.LocalModelRegistry
import com.typefree.ime.localai.LocalModelRuntimeBridge
import com.typefree.ime.localai.RuntimeMetrics
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class OfflineRoutingIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val defaultStyle = ToneStyle(
        id = "smart_default",
        name = "通用灵动",
        emoji = "✨",
        summary = "测试风格",
        promptInstruction = "清理口癖"
    )

    private fun installedSnapshot(
        asrId: String? = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
        slmId: String? = null,
        slmEngineAvailable: Boolean = slmId != null,
        localServiceAlive: Boolean = true
    ): LocalModelRegistrySnapshot {
        val map = mutableMapOf<String, LocalModelRecord>()
        if (asrId != null) {
            map[asrId] = LocalModelRecord(
                modelId = asrId,
                status = LocalModelInstallStatus.INSTALLED,
                localDirPath = "/fake/asr/$asrId"
            )
        }
        if (slmId != null) {
            map[slmId] = LocalModelRecord(
                modelId = slmId,
                status = LocalModelInstallStatus.INSTALLED,
                localDirPath = "/fake/slm/$slmId"
            )
        }
        return LocalModelRegistrySnapshot(
            records = map,
            localServiceAlive = localServiceAlive,
            slmEngineAvailable = slmEngineAvailable,
            modelGeneration = 3L
        )
    }

    // =========================================================================
    // 1. OfflineRouteBridge projection, readiness, and badges
    // =========================================================================

    @Test
    fun offlineRouteBridge_hybridLocalAsrOnlyWithoutCloudKey_resolvesRawPassthroughAndDegradedReadiness() {
        val settings = AppSettings(
            pipelineMode = PipelineMode.DUAL_STAGE,
            sttConfig = SttConfig(apiKey = "", protocol = SttProtocol.OPENAI_HTTP),
            llmConfig = LlmConfig(enabled = true, apiKey = ""),
            offlineConfig = OfflineModelConfig(
                mode = OfflineMode.HYBRID,
                asrSlotOn = true,
                selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL
            ),
            liveWriteToEditor = true
        )
        val snap = installedSnapshot(asrId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL)

        val plan = OfflineRouteBridge.resolvePlan(
            task = AiTask.DICTATE,
            settings = settings,
            registrySnapshot = snap
        )
        assertEquals(AsrRoute.Local(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL), plan.asr)
        assertEquals(TextRoute.RawPassthrough, plan.text)
        assertFalse(plan.liveWriteCapable)
        assertTrue(plan.egressAllowed)

        val readiness = OfflineRouteBridge.resolveReadiness(settings, snap)
        assertTrue("Expected DegradedRawOnly, got $readiness", readiness is ReadinessState.DegradedRawOnly)
        val badge = OfflineRouteBridge.formatBadgeForDictate(settings, snap)
        assertTrue(badge.shortLabel.contains("仅转写"))
    }

    @Test
    fun offlineRouteBridge_hybridLocalAsrWithOpenAiAudioMultimodal_resolvesCloudMultimodalTextFallback() {
        val settings = AppSettings(
            pipelineMode = PipelineMode.MULTIMODAL_DIRECT,
            sttConfig = SttConfig(apiKey = ""),
            llmConfig = LlmConfig(enabled = true, apiKey = ""),
            multimodalConfig = MultimodalConfig(
                protocol = MultimodalProtocol.OPENAI_AUDIO_CHAT,
                baseUrl = "https://api.openai.com/v1",
                apiKey = "sk-mm-test",
                model = "gpt-4o-mini-audio-preview"
            ),
            offlineConfig = OfflineModelConfig(
                mode = OfflineMode.HYBRID,
                asrSlotOn = true,
                selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL
            )
        )
        val snap = installedSnapshot(asrId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL)

        val plan = OfflineRouteBridge.resolvePlan(AiTask.DICTATE, settings, snap)
        assertEquals(AsrRoute.Local(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL), plan.asr)
        assertEquals(TextRoute.CloudMultimodalTextFallback, plan.text)
        assertFalse(plan.liveWriteCapable)

        val readiness = OfflineRouteBridge.resolveReadiness(settings, snap)
        assertTrue(readiness is ReadinessState.Ready)
        assertTrue(plan.badge.isHybrid)
        assertTrue(plan.badge.shortLabel.contains("混合"))
    }

    @Test
    fun offlineRouteBridge_hybrid4BWithDedicatedJevCloud_preservesCloudVisionOnlineAndFallsBackOffline() {
        val settings = AppSettings(
            jevUseDedicatedModel = true,
            jevLlmConfig = LlmConfig(
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "AIza-test-jev",
                model = "gemini-3.8-flash"
            ),
            chatContextSendScreenshot = true,
            offlineConfig = OfflineModelConfig(
                mode = OfflineMode.HYBRID,
                asrSlotOn = true,
                slmSlotOn = true,
                selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
                selectedSlmModelId = OfflineModelCatalog.ID_SLM_PRO_4B
            )
        )
        val snap = installedSnapshot(
            asrId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
            slmId = OfflineModelCatalog.ID_SLM_PRO_4B,
            slmEngineAvailable = true
        )

        val onlinePlan = OfflineRouteBridge.resolvePlan(
            task = AiTask.JEV_COPILOT,
            settings = settings,
            registrySnapshot = snap,
            isNetworkAvailable = true
        )
        assertEquals(TextRoute.Cloud, onlinePlan.text)
        assertTrue(onlinePlan.jevVisionAllowed)

        val offlinePlan = OfflineRouteBridge.resolvePlan(
            task = AiTask.JEV_COPILOT,
            settings = settings,
            registrySnapshot = snap,
            isNetworkAvailable = false
        )
        assertEquals(TextRoute.Local(OfflineModelCatalog.ID_SLM_PRO_4B, isFallbackTier = true), offlinePlan.text)
        assertFalse(offlinePlan.jevVisionAllowed)
    }

    // =========================================================================
    // 2. LocalDictionaryPostProcessor: SenseVoice tags & wrongVariants
    // =========================================================================

    @Test
    fun localDictionaryPostProcessor_stripsSenseVoiceTagsAndAppliesLongestVariantFirst() {
        val raw = "<|zh|><|HAPPY|><|Speech|><|woitn|>今天测试一下太普利斯增强版和太普利斯，还有 deep seek <|BGM|>"
        val stripped = LocalDictionaryPostProcessor.cleanSenseVoiceOutput(raw)
        assertEquals("今天测试一下太普利斯增强版和太普利斯，还有 deep seek", stripped)

        val dict = listOf(
            DictionaryEntry(term = "TypeFree", wrongVariants = "太普利斯", enabled = true),
            DictionaryEntry(term = "TypeFree Pro", wrongVariants = "太普利斯增强版", enabled = true),
            DictionaryEntry(term = "DeepSeek", wrongVariants = "deep seek, 迪普西克", enabled = true),
            DictionaryEntry(term = "IGNORED", wrongVariants = "测试", enabled = false)
        )
        val corrected = LocalDictionaryPostProcessor.applyWrongVariantsCorrection(stripped, dict)
        assertEquals("今天测试一下TypeFree Pro和TypeFree，还有 DeepSeek", corrected)
    }

    // =========================================================================
    // 3. ApiConfigBackupHelper pendingOfflineRestore & AppRepository safety
    // =========================================================================

    @Test
    fun apiConfigBackupHelper_importsOfflineConfigIntoPendingRestoreWithModeOffToPreventDeadlock() {
        val sourceSettings = AppSettings(
            offlineConfig = OfflineModelConfig(
                mode = OfflineMode.OFFLINE_ONLY,
                asrSlotOn = true,
                slmSlotOn = false,
                selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
                selectedSlmModelId = OfflineModelCatalog.ID_SLM_PLUS_2B
            )
        )
        val exported = ApiConfigBackupHelper.exportSettingsJson(sourceSettings, includeSecrets = false)
        assertTrue(exported.contains("\"offlineConfig\""))

        val merged = ApiConfigBackupHelper.parseAndMergeConfigJson(
            rawJson = exported,
            existingSettings = AppSettings()
        ).getOrThrow()

        assertEquals(OfflineMode.OFF, merged.offlineConfig.mode)
        assertTrue(merged.offlineConfig.asrSlotOn)
        assertFalse(merged.offlineConfig.slmSlotOn)
        assertEquals(
            PendingOfflineRestore(
                mode = OfflineMode.OFFLINE_ONLY,
                selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
                selectedSlmModelId = OfflineModelCatalog.ID_SLM_PLUS_2B
            ),
            merged.pendingOfflineRestore
        )

        // Minimal JSON containing only offlineConfig is recognized by KNOWN_CONFIG_KEYS
        val minimalOnly = """{"offlineConfig":{"mode":"HYBRID","selectedAsrModelId":"asr_zipformer_zh_14m"}}"""
        val mergedMinimal = ApiConfigBackupHelper.parseAndMergeConfigJson(minimalOnly).getOrThrow()
        assertEquals(OfflineMode.OFF, mergedMinimal.offlineConfig.mode)
        assertEquals(OfflineMode.HYBRID, mergedMinimal.pendingOfflineRestore?.mode)
        assertEquals("asr_zipformer_zh_14m", mergedMinimal.pendingOfflineRestore?.selectedAsrModelId)
    }

    @Test
    fun appRepository_defensivelyHandlesMissingOrUnknownOfflineEnumAndSupportsOfflineHelpers() {
        val store = InMemoryKeyValueStore()
        // Simulate corrupted/future enum in persisted JSON
        store.putString(
            AppRepository.KEY_SETTINGS,
            """{"pipelineMode":"DUAL_STAGE","offlineConfig":{"mode":"FUTURE_UNKNOWN_MODE","selectedAsrModelId":"asr_sensevoice_small"},"pendingOfflineRestore":{"mode":"FUTURE_MODE","selectedAsrModelId":"asr_sensevoice_small"}}"""
        )
        val repo = AppRepository(
            appContext = null,
            publicStore = store,
            secretVault = SecretVault(InMemoryKeyValueStore(), JvmAesGcmCipher())
        )
        assertEquals(OfflineMode.OFF, repo.settings.value.offlineConfig.mode)
        assertEquals("asr_sensevoice_small", repo.settings.value.offlineConfig.selectedAsrModelId)
        assertEquals(OfflineMode.OFF, repo.settings.value.pendingOfflineRestore?.mode)

        repo.updateOfflineConfig { it.copy(mode = OfflineMode.HYBRID, asrSlotOn = false) }
        assertEquals(OfflineMode.HYBRID, repo.settings.value.offlineConfig.mode)
        assertFalse(repo.settings.value.offlineConfig.asrSlotOn)

        repo.clearPendingOfflineRestore()
        assertNull(repo.settings.value.pendingOfflineRestore)
    }

    // =========================================================================
    // 4. StreamingComposer offline liveWrite bypasses cloud OPENAI_HTTP / MULTIMODAL
    // =========================================================================

    @Test
    fun streamingComposer_shouldEnableLiveWrite_allowsTrueStreamingOfflineAsrAndDisablesForNonStreamingSenseVoice() {
        val defaultHttpSettings = AppSettings(
            pipelineMode = PipelineMode.MULTIMODAL_DIRECT,
            sttConfig = SttConfig(protocol = SttProtocol.OPENAI_HTTP),
            liveWriteToEditor = true,
            offlineConfig = OfflineModelConfig(
                mode = OfflineMode.OFFLINE_ONLY,
                asrSlotOn = true,
                selectedAsrModelId = OfflineModelCatalog.ID_ASR_ZIPFORMER_BILINGUAL
            )
        )
        val snap = installedSnapshot(asrId = OfflineModelCatalog.ID_ASR_ZIPFORMER_BILINGUAL)
        val plan = OfflineRouteBridge.resolvePlan(AiTask.DICTATE, defaultHttpSettings, snap)
        assertTrue(plan.liveWriteCapable)

        // Non-streaming SenseVoiceSmall must NOT live-write drafts before polish (commits cleanly once)
        val svSettings = defaultHttpSettings.copy(
            offlineConfig = defaultHttpSettings.offlineConfig.copy(
                selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL
            )
        )
        val svSnap = installedSnapshot(asrId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL)
        val svPlan = OfflineRouteBridge.resolvePlan(AiTask.DICTATE, svSettings, svSnap)
        assertFalse(svPlan.liveWriteCapable)
        assertFalse(
            StreamingComposer.shouldEnableLiveWrite(
                settings = svSettings,
                intentMode = VoiceIntentMode.DICTATE,
                effectiveSelectedText = "",
                hasStreamingSession = true,
                targetPackage = "com.tencent.mm",
                routePlan = svPlan
            )
        )

        // Legacy 6-arg signature remains strict for cloud-only tests
        assertFalse(
            StreamingComposer.shouldEnableLiveWrite(
                settings = defaultHttpSettings,
                intentMode = VoiceIntentMode.DICTATE,
                effectiveSelectedText = "",
                hasStreamingSession = true,
                targetPackage = "com.tencent.mm"
            )
        )

        // RoutePlan-aware overload enables live write for offline local ASR
        assertTrue(
            StreamingComposer.shouldEnableLiveWrite(
                settings = defaultHttpSettings,
                intentMode = VoiceIntentMode.DICTATE,
                effectiveSelectedText = "",
                hasStreamingSession = true,
                targetPackage = "com.tencent.mm",
                isFloatingBubbleChannel = false,
                routePlan = plan
            )
        )

        // Blacklisted terminal app is still rejected
        assertFalse(
            StreamingComposer.shouldEnableLiveWrite(
                settings = defaultHttpSettings,
                intentMode = VoiceIntentMode.DICTATE,
                effectiveSelectedText = "",
                hasStreamingSession = true,
                targetPackage = "com.termux",
                isFloatingBubbleChannel = false,
                routePlan = plan
            )
        )
    }

    // =========================================================================
    // 5. AiSentenceCompleter cross-backend cache isolation & remote cancel hook
    // =========================================================================

    @Test
    fun aiSentenceCompleter_isolatesCacheByBackendIdAndInvokesOnCancelRemote() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val execCalls = AtomicInteger(0)
            val remoteCancels = AtomicInteger(0)
            val completer = AiSentenceCompleter(
                scope = scope,
                debounceMs = 0L,
                executor = { settings, _, _, _ ->
                    val n = execCalls.incrementAndGet()
                    if (settings.offlineConfig.mode == OfflineMode.OFF) {
                        Result.success("云端续写$n")
                    } else {
                        Result.success("本机续写$n")
                    }
                },
                onCancelRemote = { remoteCancels.incrementAndGet() }
            )

            val cloudSettings = AppSettings(
                aiCompletionEnabled = true,
                offlineConfig = OfflineModelConfig(mode = OfflineMode.OFF)
            )
            val local08bSettings = AppSettings(
                aiCompletionEnabled = true,
                offlineConfig = OfflineModelConfig(
                    mode = OfflineMode.HYBRID,
                    slmSlotOn = true,
                    selectedSlmModelId = OfflineModelCatalog.ID_SLM_CORE_0_8B
                )
            )

            var cloudOut = ""
            completer.scheduleCompletion(
                contextBeforeCursor = "明天下午我们一起",
                settings = cloudSettings,
                activeStyle = defaultStyle,
                onResult = { cloudOut = it }
            )?.join()
            assertEquals("云端续写1", cloudOut)
            assertEquals(1, execCalls.get())

            // Switching to local 0.8B MUST NOT hit cloud cache
            var localOut = ""
            completer.scheduleCompletion(
                contextBeforeCursor = "明天下午我们一起",
                settings = local08bSettings,
                activeStyle = defaultStyle,
                onResult = { localOut = it }
            )?.join()
            assertEquals("本机续写2", localOut)
            assertEquals(2, execCalls.get())

            // Re-querying local 0.8B hits local cache without calling executor again
            var localCached = ""
            val cachedJob = completer.scheduleCompletion(
                contextBeforeCursor = "明天下午我们一起",
                settings = local08bSettings,
                activeStyle = defaultStyle,
                onResult = { localCached = it }
            )
            assertNull(cachedJob)
            assertEquals("本机续写2", localCached)
            assertEquals(2, execCalls.get())

            // Explicit cancel invokes onCancelRemote to interrupt native local inference
            val beforeCancel = remoteCancels.get()
            completer.cancel()
            assertEquals(beforeCancel + 1, remoteCancels.get())
        } finally {
            scope.cancel()
        }
    }

    // =========================================================================
    // 6. AiPipelineEngine end-to-end: OFFLINE_ONLY lock, RawPassthrough, Local SLM
    // =========================================================================

    @Test
    fun aiPipelineEngine_offlineOnlyEgressLockBlocksPrewarmAndCloudTasksCompletely() = runBlocking {
        val networkCalls = AtomicInteger(0)
        val countingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                networkCalls.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"choices":[{"message":{"content":"should never happen"}}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        // Intentionally use 10.0.0.1 / 127.0.0.1 URLs to verify OFFLINE_ONLY never relies on isLocalEndpoint()
        val lanUrl = "http://10.0.0.5:11434/v1"
        val settings = AppSettings(
            pipelineMode = PipelineMode.DUAL_STAGE,
            sttConfig = SttConfig(baseUrl = lanUrl, apiKey = "sk-test"),
            llmConfig = LlmConfig(enabled = true, baseUrl = lanUrl, apiKey = "sk-test"),
            offlineConfig = OfflineModelConfig(
                mode = OfflineMode.OFFLINE_ONLY,
                asrSlotOn = true,
                slmSlotOn = false,
                selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL
            )
        )
        val engine = AiPipelineEngine(httpClient = countingClient)
        assertFalse(engine.isEgressAllowed(settings))

        engine.prewarmEndpoints(settings)
        engine.prewarmLiveStreamingIfNeeded(settings)
        val askRes = engine.runAskAiCardQuery(settings, "1+1等于几")
        assertTrue(askRes.isFailure)
        val jevRes = engine.runJevCopilot(
            settings = settings,
            activeStyle = defaultStyle,
            domainProfile = JevDomainProfile.FORMAL_WORKFLOW,
            chatContextMessages = listOf("对方：明天开会")
        )
        assertTrue(jevRes.isFailure)
        assertFalse(engine.canJevUseVisionScreenshot(settings))

        // Strictly ZERO HTTP requests made
        assertEquals(0, networkCalls.get())
    }

    @Test
    fun aiPipelineEngine_hybridLocalAsrWithMultimodalTextFallback_executesCloudTextPolish() = runBlocking {
        val fakeSvc = FakeLocalAiService(
            asrTranscript = "<|zh|>那个明天我们评审一下方案",
            slmResponse = ""
        )
        val localClient = LocalAiServiceClient.createForTesting(serviceProvider = { fakeSvc })
        val fakeBridge = object : LocalModelRuntimeBridge {
            override val isServiceAlive: StateFlow<Boolean> = MutableStateFlow(true)
            override suspend fun unloadSlot(slot: LocalModelSlot) = Unit
            override suspend fun unloadAll() = Unit
            override suspend fun probeModel(slot: LocalModelSlot, modelId: String, dirPath: String): Result<Unit> =
                Result.success(Unit)
        }
        val registry = LocalModelRegistry(
            rootDir = tempFolder.newFolder("local_models_hybrid_mm"),
            runtimeBridge = fakeBridge,
            availableBytesProvider = { 20L * 1024L * 1024L * 1024L },
            slmEngineAvailableProvider = { false }
        )
        registry.installModel(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL)

        val chatCalls = AtomicInteger(0)
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chatCalls.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"choices":[{"message":{"content":"明天我们评审一下方案。"}}]}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()

        val engine = AiPipelineEngine(
            httpClient = mockHttp,
            localAiClient = localClient,
            localModelRegistry = registry
        )
        val wavFile = File(tempFolder.root, "hybrid_mm.wav").apply { writeBytes(ByteArray(512) { 2 }) }
        val settings = AppSettings(
            pipelineMode = PipelineMode.MULTIMODAL_DIRECT,
            sttConfig = SttConfig(apiKey = ""),
            llmConfig = LlmConfig(enabled = true, apiKey = ""),
            multimodalConfig = MultimodalConfig(
                protocol = MultimodalProtocol.OPENAI_AUDIO_CHAT,
                baseUrl = "https://api.openai.com/v1",
                apiKey = "sk-mm-key",
                model = "gpt-4o-mini-audio-preview"
            ),
            offlineConfig = OfflineModelConfig(
                mode = OfflineMode.HYBRID,
                asrSlotOn = true,
                slmSlotOn = false,
                selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL
            )
        )

        val res = engine.processVoiceAudio(
            wavFile = wavFile,
            settings = settings,
            activeStyle = defaultStyle,
            targetAppName = "飞书",
            selectedText = "",
            surroundingBeforeCursor = ""
        ).getOrThrow()

        assertEquals(ExecutionSource.HYBRID_LOCAL_ASR_CLOUD_LLM, res.executionSource)
        assertEquals("明天我们评审一下方案。", res.finalOutput)
        assertEquals(1, chatCalls.get())
    }

    @Test
    fun aiPipelineEngine_localAsrRawPassthroughAndLocalSlmEndToEndWithMetrics() = runBlocking {
        val fakeSvc = FakeLocalAiService(
            asrTranscript = "<|zh|><|HAPPY|>今天体验太普利斯",
            slmResponse = "今天体验了 TypeFree。"
        )
        val client = LocalAiServiceClient.createForTesting(serviceProvider = { fakeSvc })
        val fakeBridge = object : LocalModelRuntimeBridge {
            override val isServiceAlive: StateFlow<Boolean> = MutableStateFlow(true)
            override suspend fun unloadSlot(slot: LocalModelSlot) = Unit
            override suspend fun unloadAll() = Unit
            override suspend fun probeModel(slot: LocalModelSlot, modelId: String, dirPath: String): Result<Unit> =
                Result.success(Unit)
        }
        val registry = LocalModelRegistry(
            rootDir = tempFolder.newFolder("local_models"),
            runtimeBridge = fakeBridge,
            availableBytesProvider = { 20L * 1024L * 1024L * 1024L },
            slmEngineAvailableProvider = { true }
        )
        registry.installModel(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL)

        val engine = AiPipelineEngine(
            localAiClient = client,
            localModelRegistry = registry
        )
        val wavFile = File(tempFolder.root, "sample.wav").apply {
            writeBytes(ByteArray(1024) { 1 })
        }

        // Case A: Local ASR only + no cloud key -> RawPassthrough with SenseVoice clean + wrongVariants fix
        val rawSettings = AppSettings(
            sttConfig = SttConfig(apiKey = ""),
            llmConfig = LlmConfig(enabled = true, apiKey = ""),
            dictionary = listOf(DictionaryEntry(term = "TypeFree", wrongVariants = "太普利斯")),
            snippets = emptyList(),
            offlineConfig = OfflineModelConfig(
                mode = OfflineMode.HYBRID,
                asrSlotOn = true,
                slmSlotOn = false,
                selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL
            )
        )
        val rawResult = engine.processVoiceAudio(
            wavFile = wavFile,
            settings = rawSettings,
            activeStyle = defaultStyle,
            targetAppName = "微信",
            selectedText = "",
            surroundingBeforeCursor = ""
        ).getOrThrow()

        assertEquals(ExecutionSource.LOCAL_RAW_PASSTHROUGH, rawResult.executionSource)
        assertEquals("今天体验TypeFree", rawResult.finalOutput)
        assertTrue(rawResult.localMetricsSummary.contains("PSS"))

        // Case B: Voice Snippet still expands on Local ASR RawPassthrough
        val snippetSettings = rawSettings.copy(
            snippets = listOf(
                SnippetEntry(
                    title = "测试口令",
                    voiceTriggers = "今天体验TypeFree",
                    content = "expanded@typefree.dev",
                    enabled = true
                )
            )
        )
        val snippetResult = engine.processVoiceAudio(
            wavFile = wavFile,
            settings = snippetSettings,
            activeStyle = defaultStyle,
            targetAppName = "微信",
            selectedText = "",
            surroundingBeforeCursor = ""
        ).getOrThrow()
        assertEquals("expanded@typefree.dev", snippetResult.finalOutput)
        assertEquals(ExecutionSource.LOCAL_RAW_PASSTHROUGH, snippetResult.executionSource)

        // Case C: Install 0.8B SLM and run OFFLINE_ONLY -> ExecutionSource.LOCAL + SLM metrics
        registry.installModel(OfflineModelCatalog.ID_SLM_CORE_0_8B)
        val localFullSettings = rawSettings.copy(
            offlineConfig = OfflineModelConfig(
                mode = OfflineMode.OFFLINE_ONLY,
                asrSlotOn = true,
                slmSlotOn = true,
                selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
                selectedSlmModelId = OfflineModelCatalog.ID_SLM_CORE_0_8B
            )
        )
        val localFullResult = engine.processVoiceAudio(
            wavFile = wavFile,
            settings = localFullSettings,
            activeStyle = defaultStyle,
            targetAppName = "飞书",
            selectedText = "",
            surroundingBeforeCursor = ""
        ).getOrThrow()

        assertEquals(ExecutionSource.LOCAL, localFullResult.executionSource)
        assertEquals("今天体验了 TypeFree。", localFullResult.finalOutput)
        assertTrue(localFullResult.localMetricsSummary.contains("TTFT 95ms"))
        assertTrue(localFullResult.localMetricsSummary.contains("tok/s"))
        assertNotNull(engine.openLiveStreamingSession(localFullSettings))
    }

    private class FakeLocalAiService(
        private val asrTranscript: String,
        private val slmResponse: String
    ) : ILocalAiService.Default() {

        override fun protocolVersion(): Int = 1

        override fun openAsrSession(
            sid: Long,
            pcmPipe: ParcelFileDescriptor?,
            params: AsrParams?,
            cb: IAsrCallback?
        ) {
            cb?.onFinal(sid, asrTranscript, params?.expectedGeneration ?: 0L, 42L)
        }

        override fun transcribeFile(
            rid: Long,
            wavPath: String?,
            params: AsrParams?,
            cb: IAsrCallback?
        ) {
            cb?.onFinal(rid, asrTranscript, params?.expectedGeneration ?: 0L, 65L)
        }

        override fun generate(
            rid: Long,
            req: GenRequest?,
            cb: ITokenCallback?
        ) {
            cb?.onTokenBatch(rid, slmResponse, 8, req?.expectedGeneration ?: 0L)
            cb?.onCompleted(rid, slmResponse, req?.expectedGeneration ?: 0L, 95L, 210L, 8)
        }

        override fun getRuntimeMetrics(): RuntimeMetrics = RuntimeMetrics(
            totalPssKb = 512 * 1024,
            lastAsrLatencyMs = 65L,
            lastSlmTtftMs = 95L,
            lastSlmTokensPerSec = 38.5f,
            slmEngineAvailable = true
        )
    }

    @Test
    fun localDictionaryRelevanceFilterAndAntiHallucinationGuard_preventsUnrelatedDictRegurgitation() {
        val dict = listOf(
            DictionaryEntry(term = "Antigravity", wrongVariants = "反重力"),
            DictionaryEntry(term = "Supabase", wrongVariants = "苏帕贝斯"),
            DictionaryEntry(term = "RAG", wrongVariants = ""),
            DictionaryEntry(term = "Agent", wrongVariants = ""),
            DictionaryEntry(term = "Claude Code", wrongVariants = "克劳德扣"),
            DictionaryEntry(term = "DeepSeek", wrongVariants = "迪普西克, deep seek")
        )

        // 1. Fuzzy Latin token correction fixes SEEPSEK -> DeepSeek without touching unrelated words
        val corrected = LocalDictionaryPostProcessor.applyWrongVariantsCorrection("SEEPSEK 效果不错", dict)
        assertEquals("DeepSeek 效果不错", corrected)

        // 2. Relevance filter ONLY picks DeepSeek and excludes Antigravity / Supabase / RAG / Agent / Claude Code
        val relevant = LocalDictionaryPostProcessor.selectRelevantDictionaryLinesForLocalSlm("SEEPSEK 效果不错", dict)
        assertEquals(1, relevant.size)
        assertTrue(relevant.first().startsWith("DeepSeek"))

        // 3. Unrelated sentence gets ZERO dictionary lines injected into 0.8B
        val unrelated = LocalDictionaryPostProcessor.selectRelevantDictionaryLinesForLocalSlm("今天天气真不错", dict)
        assertTrue(unrelated.isEmpty())

        // 4. Anti-hallucination guard rejects dictionary-regurgitation garbage
        assertTrue(
            LocalDictionaryPostProcessor.isLikelyHallucinatedDictation(
                rawTranscript = "SEEPSEK 效果不错",
                polishedText = "Antigravity 应用、Supabase 集群、RAG 机制、Agent 类型、Claude Code 模型。"
            )
        )
        // 5. Anti-hallucination guard allows legitimate self-correction dictation
        assertFalse(
            LocalDictionaryPostProcessor.isLikelyHallucinatedDictation(
                rawTranscript = "我们下午两点半哦不对三点半开会吧",
                polishedText = "我们下午三点半开会吧。"
            )
        )
        assertFalse(
            LocalDictionaryPostProcessor.isLikelyHallucinatedDictation(
                rawTranscript = "DeepSeek 效果不错",
                polishedText = "DeepSeek 效果不错。"
            )
        )
    }
}
