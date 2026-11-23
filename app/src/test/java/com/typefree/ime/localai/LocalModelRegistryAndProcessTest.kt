package com.typefree.ime.localai

import com.typefree.core.offline.LocalModelInstallStatus
import com.typefree.core.offline.LocalModelSlot
import com.typefree.core.offline.OfflineModelCatalog
import com.typefree.core.offline.RamTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LocalModelRegistryAndProcessTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // 1. ProcessUtil & Multi-process Guard
    // -------------------------------------------------------------------------

    @Test
    fun `ProcessUtil matches local_ai suffix and parses null-terminated cmdline bytes`() {
        assertTrue(ProcessUtil.isLocalAiProcessName("com.typefree.ime:local_ai"))
        assertTrue(ProcessUtil.isLocalAiProcessName(":local_ai"))
        assertFalse(ProcessUtil.isLocalAiProcessName("com.typefree.ime"))
        assertFalse(ProcessUtil.isLocalAiProcessName("com.typefree.ime:local_ai_extra"))
        assertFalse(ProcessUtil.isLocalAiProcessName(null))
        assertFalse(ProcessUtil.isLocalAiProcessName("   "))

        val cmdlineFile = tempFolder.newFile("cmdline")
        val raw = "com.typefree.ime:local_ai\u0000\u0000\u0000ignored".toByteArray(Charsets.UTF_8)
        cmdlineFile.writeBytes(raw)
        val parsed = ProcessUtil.readSelfCmdline(cmdlineFile)
        assertEquals("com.typefree.ime:local_ai", parsed)
        assertTrue(ProcessUtil.isLocalAiProcessName(parsed))
    }

    // -------------------------------------------------------------------------
    // 2. GenRequest 64KB UTF-8 Budget & AsrParams Sanitization
    // -------------------------------------------------------------------------

    @Test
    fun `GenRequest enforces 64KB UTF-8 cap without corrupting multibyte Chinese or Emoji`() {
        val normal = GenRequest(
            modelId = "slm_core_0_8b",
            modelDirPath = "/tmp/model",
            taskName = "DICTATE",
            systemPrompt = "你是净写助手",
            userPrompt = "今天天气很好"
        )
        assertFalse(normal.wasTruncated)
        assertTrue(normal.totalPromptBytes <= MAX_GEN_REQUEST_BYTES)

        val hugeSys = "系统规则".repeat(8_000) // ~96KB UTF-8
        val hugeUser = "语音文本😀".repeat(20_000) // ~320KB UTF-8
        val truncated = GenRequest(
            modelId = "slm_plus_2b",
            modelDirPath = "/tmp/model",
            taskName = "SPEAK_TO_EDIT",
            systemPrompt = hugeSys,
            userPrompt = hugeUser
        )
        assertTrue(truncated.wasTruncated)
        val sysBytes = truncated.systemPrompt.toByteArray(Charsets.UTF_8).size
        val userBytes = truncated.userPrompt.toByteArray(Charsets.UTF_8).size
        assertTrue("Expected total <= 64KB, got ${sysBytes + userBytes}", sysBytes + userBytes <= MAX_GEN_REQUEST_BYTES)
        assertTrue("System prompt budget should stay <= 16KB", sysBytes <= MAX_SYSTEM_PROMPT_BYTES)
        assertEquals(sysBytes + userBytes, truncated.totalPromptBytes)
        // Ensure no lone high/low surrogates exist at truncation boundaries
        assertFalse(Character.isHighSurrogate(truncated.userPrompt.last()))
    }

    @Test
    fun `AsrParams caps hotwords list to prevent Binder bloat`() {
        val rawHotwords = (1..120).map { "热词$it" }
        val params = AsrParams(
            modelId = "asr_zipformer_bilingual",
            modelDirPath = "/tmp/asr",
            hotwords = rawHotwords
        )
        assertEquals(MAX_ASR_HOTWORDS, params.sanitizedHotwords.size)
    }

    // -------------------------------------------------------------------------
    // 3. DeviceRamTierResolver & Thermal Headroom
    // -------------------------------------------------------------------------

    @Test
    fun `DeviceRamTierResolver classifies RAM tiers, runtime availMem, and thermal throttling`() {
        val mb = DeviceMemorySnapshot.BYTES_PER_MB
        assertEquals(RamTier.LOW_6GB, DeviceRamTierResolver.resolveRamTierFromBytes(6_000L * mb))
        assertEquals(RamTier.MID_8GB, DeviceRamTierResolver.resolveRamTierFromBytes(8_000L * mb))
        assertEquals(RamTier.HIGH_12GB_PLUS, DeviceRamTierResolver.resolveRamTierFromBytes(12_000L * mb))

        val lowMemCheck = DeviceRamTierResolver.canLoadModel(
            snapshot = DeviceMemorySnapshot(8_000L * mb, 2_000L * mb, lowMemory = true),
            approxRamMb = 400
        )
        assertFalse(lowMemCheck.allowed)

        val tightAvailCheck = DeviceRamTierResolver.canLoadModel(
            snapshot = DeviceMemorySnapshot(8_000L * mb, 500L * mb, lowMemory = false),
            approxRamMb = 400,
            safetyMarginMb = 256
        )
        assertFalse(tightAvailCheck.allowed)

        val okCheck = DeviceRamTierResolver.canLoadModel(
            snapshot = DeviceMemorySnapshot(8_000L * mb, 2_000L * mb, lowMemory = false),
            approxRamMb = 400,
            safetyMarginMb = 256
        )
        assertTrue(okCheck.allowed)

        assertFalse(DeviceRamTierResolver.evaluateThermalState(headroom = Float.NaN, thermalStatus = 0).isThrottled)
        assertFalse(DeviceRamTierResolver.evaluateThermalState(headroom = 0.50f, thermalStatus = 1).isThrottled)
        assertTrue(DeviceRamTierResolver.evaluateThermalState(headroom = 0.90f, thermalStatus = 0).isThrottled)
        assertTrue(DeviceRamTierResolver.evaluateThermalState(headroom = null, thermalStatus = 2).isThrottled)
    }

    // -------------------------------------------------------------------------
    // 4. LocalModelRegistry Safe Swap, Disk Precheck, Rollback & Orphan Cleanup
    // -------------------------------------------------------------------------

    private class FakeRuntimeBridge(
        var probeHandler: (LocalModelSlot, String, String) -> Result<Unit> = { _, _, _ -> Result.success(Unit) }
    ) : LocalModelRuntimeBridge {
        val aliveFlow = MutableStateFlow(true)
        override val isServiceAlive: StateFlow<Boolean> = aliveFlow
        val events = CopyOnWriteArrayList<String>()

        override suspend fun unloadSlot(slot: LocalModelSlot) {
            events.add("unload:${slot.name}")
        }

        override suspend fun unloadAll() {
            events.add("unloadAll")
        }

        override suspend fun probeModel(slot: LocalModelSlot, modelId: String, dirPath: String): Result<Unit> {
            events.add("probe:${slot.name}:$modelId")
            return probeHandler(slot, modelId, dirPath)
        }
    }

    @Test
    fun `LocalModelRegistry safe install unloads before probe, verifies SHA-256, persists, and cleans orphans`() = runBlocking {
        val root = tempFolder.newFolder("local_models")
        val bridge = FakeRuntimeBridge()
        val registry = LocalModelRegistry(
            rootDir = root,
            runtimeBridge = bridge,
            downloader = SimulatedLocalModelDownloader(steps = 3, stepDelayMs = 0L, payloadSizeBytes = 2048),
            availableBytesProvider = { 10L * 1024 * 1024 * 1024 },
            slmEngineAvailableProvider = { false }
        )

        assertFalse(registry.isSlmEngineAvailable)

        // Open-source build blocks SLM install gracefully
        val slmAttempt = registry.installModel("slm_core_0_8b")
        assertTrue(slmAttempt is ModelInstallOutcome.ProbeFailed)

        // Install ASR model A
        val resA = registry.installModel("asr_zipformer_zh_14m")
        assertTrue("Expected Success, got $resA", resA is ModelInstallOutcome.Success)
        val successA = resA as ModelInstallOutcome.Success
        assertEquals(listOf("unload:ASR", "probe:ASR:asr_zipformer_zh_14m"), bridge.events)
        assertTrue(registry.snapshot.value.isInstalled("asr_zipformer_zh_14m"))
        assertTrue(registry.totalStorageBytes() > 0L)
        assertTrue(File(successA.modelDirPath).isDirectory)

        // Create orphan dir & stray .part file, then reload a fresh registry instance
        val orphanDir = File(root, "asr/orphan_old_model").apply { mkdirs() }
        File(orphanDir, "junk.bin").writeBytes(ByteArray(128))
        val strayPart = File(root, "asr/interrupted.part").apply { writeBytes(ByteArray(64)) }

        val reloaded = LocalModelRegistry(
            rootDir = root,
            runtimeBridge = bridge,
            downloader = SimulatedLocalModelDownloader(steps = 2, stepDelayMs = 0L, payloadSizeBytes = 1024),
            availableBytesProvider = { 10L * 1024 * 1024 * 1024 },
            slmEngineAvailableProvider = { false }
        )
        assertFalse("Orphan dir should be auto-cleaned on startup", orphanDir.exists())
        assertFalse("Stray .part file should be auto-cleaned on startup", strayPart.exists())
        assertTrue("Installed model should survive reload", reloaded.snapshot.value.isInstalled("asr_zipformer_zh_14m"))
        assertEquals(successA.generation, reloaded.snapshot.value.modelGeneration)
    }

    @Test
    fun `LocalModelRegistry SHA-256 mismatch and probe failure rollback preserve old model A`() = runBlocking {
        val root = tempFolder.newFolder("local_models_rollback")
        val bridge = FakeRuntimeBridge()
        val registry = LocalModelRegistry(
            rootDir = root,
            runtimeBridge = bridge,
            downloader = SimulatedLocalModelDownloader(steps = 2, stepDelayMs = 0L, payloadSizeBytes = 1024),
            availableBytesProvider = { 10L * 1024 * 1024 * 1024 }
        )

        val installA = registry.installModel("asr_sensevoice_small") as ModelInstallOutcome.Success
        val dirA = File(installA.modelDirPath)
        assertTrue(dirA.isDirectory)

        // 1. SHA-256 mismatch on B must fail verification and preserve A
        val badSha = registry.installModel(
            modelId = "asr_zipformer_bilingual",
            expectedSha256Override = "0".repeat(64)
        )
        assertTrue(badSha is ModelInstallOutcome.VerificationFailed)
        assertEquals(
            LocalModelInstallStatus.CORRUPTED,
            registry.snapshot.value.statusOf("asr_zipformer_bilingual")
        )
        assertTrue(registry.snapshot.value.isInstalled("asr_sensevoice_small"))
        assertTrue(dirA.isDirectory)

        // 2. Probe failure (or SIGKILL during probe) on B must delete B, mark UNSUPPORTED_ON_DEVICE, keep A
        bridge.probeHandler = { _, modelId, _ ->
            if (modelId == "asr_funasr_nano") {
                Result.failure(IOException("SIGKILL during probe"))
            } else {
                Result.success(Unit)
            }
        }
        val failedSwap = registry.installModel("asr_funasr_nano")
        assertTrue(failedSwap is ModelInstallOutcome.ProbeFailed)
        val probeFail = failedSwap as ModelInstallOutcome.ProbeFailed
        assertEquals("asr_sensevoice_small", probeFail.keptOldModelId)
        assertEquals(
            LocalModelInstallStatus.UNSUPPORTED_ON_DEVICE,
            registry.snapshot.value.statusOf("asr_funasr_nano")
        )
        assertFalse(File(root, "asr/asr_funasr_nano").exists())
        assertTrue(registry.snapshot.value.isInstalled("asr_sensevoice_small"))
        assertTrue(dirA.isDirectory)

        // 3. Successful install of B keeps old model A on disk (multi-model coexistence; user chooses active)
        bridge.probeHandler = { _, _, _ -> Result.success(Unit) }
        val swapOk = registry.installModel("asr_zipformer_zh_14m")
        assertTrue(swapOk is ModelInstallOutcome.Success)
        assertEquals(null, (swapOk as ModelInstallOutcome.Success).replacedModelId)
        assertTrue(registry.snapshot.value.isInstalled("asr_zipformer_zh_14m"))
        assertTrue("Old model A should remain installed for manual switching", registry.snapshot.value.isInstalled("asr_sensevoice_small"))
        assertTrue("Old model A dir should stay on disk when disk space is sufficient", dirA.isDirectory)
    }

    @Test
    fun `LocalModelRegistry disk precheck requires confirmation when only deleting old model A frees enough space`() = runBlocking {
        val root = tempFolder.newFolder("local_models_disk")
        val bridge = FakeRuntimeBridge()
        var fakeAvailBytes = 10L * 1024 * 1024 * 1024

        val registry = LocalModelRegistry(
            rootDir = root,
            runtimeBridge = bridge,
            downloader = SimulatedLocalModelDownloader(steps = 2, stepDelayMs = 0L, payloadSizeBytes = 1024),
            availableBytesProvider = { fakeAvailBytes }
        )

        // Install large model A (~900MB in catalog)
        val descA = OfflineModelCatalog.findById("asr_funasr_nano")!!
        val descB = OfflineModelCatalog.findById("asr_sensevoice_small")!!
        val installA = registry.installModel(descA.id)
        assertTrue(installA is ModelInstallOutcome.Success)

        // Case 1: Available space too low even after deleting A
        fakeAvailBytes = 10L * 1024 * 1024 // 10MB
        val tooSmall = registry.installModel("asr_qwen3_asr_0_6b")
        assertTrue(tooSmall is ModelInstallOutcome.InsufficientDiskSpace)
        assertTrue(registry.snapshot.value.isInstalled(descA.id))

        // Case 2: Available space < size(B) + 500MB, but avail + size(A) >= size(B) + 500MB
        val neededForB = descB.sizeBytes + LocalModelRegistry.DISK_RESERVE_BYTES
        fakeAvailBytes = neededForB - (100L * 1024 * 1024) // 100MB short unless A (~900MB) is deleted first
        val needsConfirm = registry.installModel(descB.id, confirmDeleteOldBeforeDownload = false)
        assertTrue("Expected NeedsDeleteOldConfirmation, got $needsConfirm", needsConfirm is ModelInstallOutcome.NeedsDeleteOldConfirmation)
        assertEquals(descA.id, (needsConfirm as ModelInstallOutcome.NeedsDeleteOldConfirmation).oldModelId)
        assertTrue("Old model A must not be deleted before confirmation", registry.snapshot.value.isInstalled(descA.id))

        // Confirm upfront deletion -> A is deleted, B installs
        val confirmed = registry.installModel(descB.id, confirmDeleteOldBeforeDownload = true)
        assertTrue(confirmed is ModelInstallOutcome.Success)
        assertTrue(registry.snapshot.value.isInstalled(descB.id))
        assertFalse(registry.snapshot.value.isInstalled(descA.id))

        // deleteModel and clearAllModels reclaim storage to 0
        assertTrue(registry.deleteModel(descB.id))
        assertEquals(0L, registry.totalStorageBytes())
        registry.clearAllModels()
        assertTrue(registry.snapshot.value.records.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 5. LocalAiRuntimeController: Streaming ASR, Preemption, Cancel, Generation, Incognito & DeathRecipient
    // -------------------------------------------------------------------------

    @Test
    fun `MockLocalAsrEngine streaming through LocalAiRuntimeController emits partials, final, and honors cancel and generation`() {
        val modelDir = tempFolder.newFolder("mock_asr_model")
        File(modelDir, "model.bin").writeBytes(ByteArray(256) { 1 })

        val controller = LocalAiRuntimeController(
            asrEngine = MockLocalAsrEngine(chunkDelayMs = 0L),
            slmEngine = NoOpLocalSlmEngine,
            pssReader = { MemoryPssSnapshot(totalPssKb = 65_536, dalvikPssKb = 10_000, nativePssKb = 55_536, pid = 4321) }
        )

        try {
            // Probe succeeds on valid dir
            assertEquals("", controller.probeModel(SLOT_ASR, "asr_sensevoice_small", modelDir.absolutePath))

            val partials = CopyOnWriteArrayList<Pair<String, String>>()
            val finalLatch = CountDownLatch(1)
            var finalText: String? = null
            var finalGen = 0L

            val cb = object : IAsrCallback.Default() {
                override fun onPartial(sid: Long, finalizedText: String?, interimText: String?, generation: Long) {
                    partials.add(finalizedText.orEmpty() to interimText.orEmpty())
                }

                override fun onFinal(sid: Long, fullText: String?, generation: Long, latencyMs: Long) {
                    finalText = fullText
                    finalGen = generation
                    finalLatch.countDown()
                }

                override fun onError(sid: Long, errorCode: Int, message: String?, generation: Long) {
                    finalLatch.countDown()
                }
            }

            // Stream 5 PCM chunks (~1s = 32,000 bytes)
            val pcm = ByteArray(MockLocalAsrEngine.PCM_CHUNK_BYTES * 5) { 7 }
            val expectedGen = controller.currentGeneration
            controller.openAsrSession(
                sid = 101L,
                pcmStream = ByteArrayInputStream(pcm),
                params = AsrParams(
                    modelId = "asr_sensevoice_small",
                    modelDirPath = modelDir.absolutePath,
                    hotwords = listOf("TypeFree"),
                    expectedGeneration = expectedGen
                ),
                cb = cb
            )

            assertTrue("Timed out waiting for mock ASR final", finalLatch.await(3, TimeUnit.SECONDS))
            assertTrue("Expected incremental partials", partials.size >= 4)
            assertNotNull(finalText)
            assertTrue(finalText!!.contains("TypeFree"))
            assertEquals(expectedGen, finalGen)

            // Metrics report PSS and loaded model
            val metrics = controller.getRuntimeMetrics()
            assertEquals(LOCAL_AI_PROTOCOL_VERSION, metrics.protocolVersion)
            assertEquals("asr_sensevoice_small", metrics.loadedAsrModelId)
            assertEquals(65_536, metrics.totalPssKb)
            assertFalse(metrics.slmEngineAvailable)

            // Unload increments generation and rejects stale generation request
            controller.unloadSlot(SLOT_ASR)
            assertTrue(controller.currentGeneration > expectedGen)
            val staleLatch = CountDownLatch(1)
            var staleErrCode = 0
            controller.transcribeFile(
                rid = 102L,
                wavPath = File(modelDir, "model.bin").absolutePath,
                params = AsrParams(
                    modelId = "asr_sensevoice_small",
                    modelDirPath = modelDir.absolutePath,
                    expectedGeneration = expectedGen // stale!
                ),
                cb = object : IAsrCallback.Default() {
                    override fun onPartial(sid: Long, finalizedText: String?, interimText: String?, generation: Long) = Unit
                    override fun onFinal(sid: Long, fullText: String?, generation: Long, latencyMs: Long) = Unit
                    override fun onError(sid: Long, errorCode: Int, message: String?, generation: Long) {
                        staleErrCode = errorCode
                        staleLatch.countDown()
                    }
                }
            )
            assertTrue(staleLatch.await(2, TimeUnit.SECONDS))
            assertEquals(ERR_STALE_GENERATION, staleErrCode)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `SLM single-slot scheduler preempts low-priority completion for high-priority voice and clears incognito state`() {
        val completionStarted = CountDownLatch(1)
        val completionObservedCancel = AtomicBoolean(false)
        val incognitoClearCount = AtomicInteger(0)

        val fakeSlm = object : LocalSlmEngine {
            override val isAvailable: Boolean = true
            override var loadedModelId: String? = "slm_plus_2b"
            override fun loadModel(modelId: String, modelDirPath: String) = Result.success(Unit)
            override fun unload() { loadedModelId = null }
            override fun clearSessionState() { incognitoClearCount.incrementAndGet() }
            override fun probe(modelId: String, modelDirPath: String) = Result.success(Unit)

            override fun generate(
                requestId: Long,
                request: GenRequest,
                cancelled: AtomicBoolean,
                onTokenBatch: (chunk: String, tokenCount: Int) -> Unit
            ): Result<LocalSlmRuntimeResult> {
                if (request.priority == PRIORITY_COMPLETION) {
                    completionStarted.countDown()
                    val deadline = System.currentTimeMillis() + 3_000L
                    while (!cancelled.get() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(10L)
                    }
                    completionObservedCancel.set(cancelled.get())
                    return Result.failure(IOException("Preempted"))
                }
                onTokenBatch("你好", 2)
                onTokenBatch("世界", 2)
                return Result.success(
                    LocalSlmRuntimeResult(text = "你好世界", ttftMs = 15L, totalMs = 40L, totalTokens = 4, isMock = true)
                )
            }
        }

        val controller = LocalAiRuntimeController(
            asrEngine = MockLocalAsrEngine(),
            slmEngine = fakeSlm
        )

        try {
            val compErrLatch = CountDownLatch(1)
            var compErrCode = 0
            controller.generate(
                rid = 201L,
                req = GenRequest(
                    modelId = "slm_plus_2b",
                    modelDirPath = "/tmp/slm",
                    taskName = "SENTENCE_COMPLETION",
                    systemPrompt = "补全",
                    userPrompt = "今天",
                    priority = PRIORITY_COMPLETION
                ),
                cb = object : ITokenCallback.Default() {
                    override fun onTokenBatch(rid: Long, tokenChunk: String?, tokenCount: Int, generation: Long) = Unit
                    override fun onCompleted(rid: Long, fullText: String?, generation: Long, ttftMs: Long, totalMs: Long, totalTokens: Int) = Unit
                    override fun onError(rid: Long, errorCode: Int, message: String?, generation: Long) {
                        compErrCode = errorCode
                        compErrLatch.countDown()
                    }
                }
            )
            assertTrue(completionStarted.await(2, TimeUnit.SECONDS))

            // High-priority incognito voice request arrives -> preempts completion and wipes KV state
            val voiceDoneLatch = CountDownLatch(1)
            var voiceText: String? = null
            controller.generate(
                rid = 202L,
                req = GenRequest(
                    modelId = "slm_plus_2b",
                    modelDirPath = "/tmp/slm",
                    taskName = "DICTATE",
                    systemPrompt = "净写",
                    userPrompt = "嗯那个你好世界",
                    priority = PRIORITY_VOICE,
                    incognito = true
                ),
                cb = object : ITokenCallback.Default() {
                    override fun onTokenBatch(rid: Long, tokenChunk: String?, tokenCount: Int, generation: Long) = Unit
                    override fun onCompleted(rid: Long, fullText: String?, generation: Long, ttftMs: Long, totalMs: Long, totalTokens: Int) {
                        voiceText = fullText
                        voiceDoneLatch.countDown()
                    }
                    override fun onError(rid: Long, errorCode: Int, message: String?, generation: Long) {
                        voiceDoneLatch.countDown()
                    }
                }
            )

            assertTrue(compErrLatch.await(3, TimeUnit.SECONDS))
            assertTrue("Completion engine loop must observe underlying cancel flag", completionObservedCancel.get())
            assertEquals(ERR_PREEMPTED, compErrCode)

            assertTrue(voiceDoneLatch.await(3, TimeUnit.SECONDS))
            assertEquals("你好世界", voiceText)
            assertEquals("Incognito request must wipe KV/GDN session state", 1, incognitoClearCount.get())
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `LocalAiServiceClient DeathRecipient flips isServiceAlive, notifies registry, and aborts active streaming session`() = runBlocking {
        val pipedIn = PipedInputStream(64 * 1024)
        val pipedOut = PipedOutputStream(pipedIn)
        val sessionOpenedLatch = CountDownLatch(1)

        val stubService = object : ILocalAiService.Default() {
            override fun protocolVersion(): Int = LOCAL_AI_PROTOCOL_VERSION
            override fun openAsrSession(sid: Long, pcmPipe: android.os.ParcelFileDescriptor?, params: AsrParams?, cb: IAsrCallback?) {
                sessionOpenedLatch.countDown()
            }
            override fun transcribeFile(rid: Long, wavPath: String?, params: AsrParams?, cb: IAsrCallback?) = Unit
            override fun generate(rid: Long, req: GenRequest?, cb: ITokenCallback?) = Unit
            override fun cancel(rid: Long) = Unit
            override fun prewarmSlot(slot: Int, modelId: String?, modelDirPath: String?) = Unit
            override fun unloadSlot(slot: Int) = Unit
            override fun unloadAll() = Unit
            override fun probeModel(slot: Int, modelId: String?, modelDirPath: String?): String = ""
            override fun getRuntimeMetrics(): RuntimeMetrics = RuntimeMetrics()
        }

        val currentService = AtomicReference<ILocalAiService?>(stubService)

        val client = LocalAiServiceClient.createForTesting(
            serviceProvider = { currentService.get() },
            pipeOpener = {
                object : LocalAiServiceClient.PipeHandle {
                    override val readPfd: android.os.ParcelFileDescriptor? = null
                    override val writeStream = pipedOut
                    override fun closeLocalReadEnd() = Unit
                    override fun closeWriteEnd() {
                        runCatching { pipedOut.close() }
                        runCatching { pipedIn.close() }
                    }
                }
            }
        )

        val registry = LocalModelRegistry(
            rootDir = tempFolder.newFolder("local_models_death"),
            runtimeBridge = client,
            availableBytesProvider = { 10L * 1024 * 1024 * 1024 }
        )

        val deathNotified = AtomicBoolean(false)
        client.addDeathListener { deathNotified.set(true) }
        client.preBind()
        assertTrue(client.isServiceAlive.value)

        val session = client.openStreamingAsrSession(
            modelId = "asr_sensevoice_small",
            modelDirPath = "/tmp/asr"
        )
        session.sendPcmFrame(ByteArray(3200) { 1 })
        assertTrue("Streaming session should open before simulated SIGKILL", sessionOpenedLatch.await(2, TimeUnit.SECONDS))

        // Simulate lmkd SIGKILL of `:local_ai`
        currentService.set(null)
        client.simulateBinderDeathForTesting("Simulated lmkd SIGKILL")
        assertFalse(client.isServiceAlive.value)
        assertEquals(1, client.serviceDeathCount.value)
        assertTrue(deathNotified.get())

        val finishResult = session.finishAndAwaitFinal(timeoutMs = 1_000L)
        assertTrue("Active streaming session must fail cleanly upon binder death", finishResult.isFailure)
        assertNull(finishResult.getOrNull())

        // Wait briefly for registry's StateFlow collector to reflect service death
        val deadline = System.currentTimeMillis() + 1_000L
        while (registry.snapshot.value.localServiceAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
        }
        assertFalse(registry.snapshot.value.localServiceAlive)
    }
}
