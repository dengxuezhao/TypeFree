package com.typefree.ime.localai

import com.typefree.core.offline.LocalModelDescriptor
import com.typefree.core.offline.LocalModelInstallStatus
import com.typefree.core.offline.LocalModelSlot
import com.typefree.core.offline.OfflineModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class HttpResumableModelDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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

    // -------------------------------------------------------------------------
    // 1. HTTPS-Only Enforcement
    // -------------------------------------------------------------------------

    @Test
    fun `rejects non-HTTPS primary and mirror URLs immediately without making network requests`() = runBlocking {
        val networkCalls = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                networkCalls.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(0).toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()

        val downloader = HttpResumableModelDownloader(okHttpClient = client)
        val partFile = File(tempFolder.root, "insecure.part")

        val badHttpDescriptor = LocalModelDescriptor(
            id = "test_http_only",
            slot = LocalModelSlot.ASR,
            displayName = "Insecure",
            subtitle = "",
            sizeBytes = 1024L,
            approxRamMb = 100,
            minRamMb = 0,
            isProOnly = false,
            downloadUrl = "http://example.com/model.tar.bz2"
        )
        val res1 = downloader.downloadToPartFile(badHttpDescriptor, partFile) { _, _ -> }
        assertTrue(res1.isFailure)
        assertTrue("Expected SecurityException, got ${res1.exceptionOrNull()}", res1.exceptionOrNull() is SecurityException)

        val badMirrorDescriptor = badHttpDescriptor.copy(
            id = "test_http_mirror",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/test.tar.bz2",
            mirrorUrls = listOf("http://mirror.insecure.local/test.tar.bz2")
        )
        val res2 = downloader.downloadToPartFile(badMirrorDescriptor, partFile) { _, _ -> }
        assertTrue(res2.isFailure)
        assertTrue("Expected SecurityException, got ${res2.exceptionOrNull()}", res2.exceptionOrNull() is SecurityException)

        val badFileScheme = badHttpDescriptor.copy(downloadUrl = "file:///etc/passwd")
        val res3 = downloader.downloadToPartFile(badFileScheme, partFile) { _, _ -> }
        assertTrue(res3.isFailure)
        assertTrue(res3.exceptionOrNull() is SecurityException)

        assertEquals("No network request should ever be issued for non-HTTPS URLs", 0, networkCalls.get())
    }

    // -------------------------------------------------------------------------
    // 2. HTTP Range Resume, Mirror Failover & SHA-256 Verification
    // -------------------------------------------------------------------------

    @Test
    fun `resumes partial download via HTTP Range 206, fails over to mirror, and verifies SHA-256`() = runBlocking {
        val fullPayload = ByteArray(16_384) { idx -> ((idx * 17 + 3) and 0xFF).toByte() }
        val splitOffset = 6_000
        val partFile = File(tempFolder.root, "resume.part")
        partFile.writeBytes(fullPayload.copyOfRange(0, splitOffset))

        val seenRanges = CopyOnWriteArrayList<String>()
        val seenHosts = CopyOnWriteArrayList<String>()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                seenHosts.add(req.url.host)
                val rangeHeader = req.header("Range")
                if (rangeHeader != null) seenRanges.add(rangeHeader)

                if (req.url.host == "primary.example.com") {
                    // Simulate primary outage -> should fail over to HTTPS mirror
                    return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body("down".toResponseBody("text/plain".toMediaType()))
                        .build()
                }

                assertEquals("bytes=$splitOffset-", rangeHeader)
                val remaining = fullPayload.copyOfRange(splitOffset, fullPayload.size)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes $splitOffset-${fullPayload.size - 1}/${fullPayload.size}")
                    .body(remaining.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()

        val expectedFile = tempFolder.newFile("expected.bin").apply { writeBytes(fullPayload) }
        val expectedSha = LocalModelRegistry.computeFileSha256(expectedFile)

        val descriptor = LocalModelDescriptor(
            id = "test_resume",
            slot = LocalModelSlot.ASR,
            displayName = "Resume Test",
            subtitle = "",
            sizeBytes = fullPayload.size.toLong(),
            approxRamMb = 100,
            minRamMb = 0,
            isProOnly = false,
            downloadUrl = "https://primary.example.com/model.bin",
            mirrorUrls = listOf("https://mirror.example.com/model.bin"),
            expectedSha256 = expectedSha
        )

        val downloader = HttpResumableModelDownloader(okHttpClient = client)
        val result = downloader.downloadToPartFile(descriptor, partFile) { _, _ -> }
        assertTrue("Expected success, got $result", result.isSuccess)
        val artifact = result.getOrThrow()
        assertEquals(fullPayload.size.toLong(), artifact.bytesWritten)
        assertEquals(expectedSha, artifact.computedSha256)
        assertArrayEquals(fullPayload, partFile.readBytes())
        assertEquals(listOf("primary.example.com", "mirror.example.com"), seenHosts)
        assertTrue(seenRanges.contains("bytes=$splitOffset-"))

        // SHA-256 mismatch must fail with ModelIntegrityException and delete corrupted `.part`
        val badShaDesc = descriptor.copy(
            downloadUrl = "https://mirror.example.com/model.bin",
            mirrorUrls = emptyList(),
            expectedSha256 = "f".repeat(64)
        )
        partFile.writeBytes(fullPayload.copyOfRange(0, splitOffset))
        val badRes = downloader.downloadToPartFile(badShaDesc, partFile) { _, _ -> }
        assertTrue(badRes.isFailure)
        assertTrue(badRes.exceptionOrNull() is ModelIntegrityException)
        assertFalse("Corrupted .part should be deleted on SHA-256 mismatch", partFile.exists())
    }

    // -------------------------------------------------------------------------
    // 3. Zip-Slip Traversal & Symlink Blocking
    // -------------------------------------------------------------------------

    @Test
    fun `extractTarBz2Safely blocks Unix and Windows Zip-Slip entries and symlinks`() {
        val targetDir = tempFolder.newFolder("extract_safe")
        val escapedFile = File(tempFolder.root, "escaped_secret.txt")
        assertFalse(escapedFile.exists())

        // Case 1: Unix `../` traversal
        val unixTraversalArchive = createTarBz2(
            tempFolder.newFile("unix_slip.tar.bz2"),
            listOf("../escaped_secret.txt" to "pwned".toByteArray())
        )
        try {
            HttpResumableModelDownloader.extractTarBz2Safely(unixTraversalArchive, targetDir)
            fail("Expected SecurityException for Unix Zip-Slip entry")
        } catch (expected: SecurityException) {
            assertTrue(expected.message.orEmpty().contains("Zip-Slip"))
        }
        assertFalse("Escaped file must not be created", escapedFile.exists())

        // Case 2: Windows backslash `..\` traversal
        val winTraversalArchive = createTarBz2(
            tempFolder.newFile("win_slip.tar.bz2"),
            listOf("..\\escaped_secret.txt" to "pwned".toByteArray())
        )
        try {
            HttpResumableModelDownloader.extractTarBz2Safely(winTraversalArchive, targetDir)
            fail("Expected SecurityException for Windows Zip-Slip entry")
        } catch (expected: SecurityException) {
            assertTrue(expected.message.orEmpty().contains("Zip-Slip"))
        }
        assertFalse(escapedFile.exists())

        // Case 3: Symlink entry
        val symlinkArchive = tempFolder.newFile("symlink.tar.bz2")
        FileOutputStream(symlinkArchive).use { fos ->
            BZip2CompressorOutputStream(BufferedOutputStream(fos)).use { bz2 ->
                TarArchiveOutputStream(bz2).use { tar ->
                    val linkEntry = TarArchiveEntry("model_dir/evil_link", TarArchiveEntry.LF_SYMLINK)
                    linkEntry.linkName = "/etc/passwd"
                    tar.putArchiveEntry(linkEntry)
                    tar.closeArchiveEntry()
                }
            }
        }
        try {
            HttpResumableModelDownloader.extractTarBz2Safely(symlinkArchive, targetDir)
            fail("Expected SecurityException for symlink entry")
        } catch (expected: SecurityException) {
            assertTrue(expected.message.orEmpty().contains("符号链接"))
        }
    }

    // -------------------------------------------------------------------------
    // 4. tar.bz2 Flattening Extraction + Silero VAD Caching via LocalModelRegistry
    // -------------------------------------------------------------------------

    @Test
    fun `tar bz2 archive flattens single top-level directory and caches silero_vad across models`() = runBlocking {
        val archiveBytes = createTarBz2Bytes(
            listOf(
                "sherpa-onnx-sense-voice-test/model.int8.onnx" to "ONNX_MODEL_DATA".toByteArray(),
                "sherpa-onnx-sense-voice-test/tokens.txt" to "0 <blank>\n1 你好\n".toByteArray(),
                "sherpa-onnx-sense-voice-test/tokenizer/vocab.json" to "{}".toByteArray()
            )
        )
        val vadBytes = "SILERO_VAD_INT8_ONNX".toByteArray()
        val vadDownloadHits = AtomicInteger(0)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val urlStr = req.url.toString()
                val payload = if (urlStr.endsWith(OfflineModelCatalog.SILERO_VAD_FILE_NAME)) {
                    vadDownloadHits.incrementAndGet()
                    vadBytes
                } else {
                    archiveBytes
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()

        val root = tempFolder.newFolder("registry_tarbz2")
        val sharedVadCache = File(root, LocalModelRegistry.SHARED_DIR_NAME)
        val bridge = FakeRuntimeBridge()
        val downloader = HttpResumableModelDownloader(
            okHttpClient = client,
            defaultVadCacheDir = sharedVadCache
        )
        val registry = LocalModelRegistry(
            rootDir = root,
            runtimeBridge = bridge,
            downloader = downloader,
            availableBytesProvider = { 20L * 1024 * 1024 * 1024 },
            slmEngineAvailableProvider = { false }
        )

        // Install SenseVoiceSmall (isTarBz2Archive=true, requiresSileroVad=true)
        val res1 = registry.installModel(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL)
        assertTrue("Expected Success, got $res1", res1 is ModelInstallOutcome.Success)
        val dir1 = File((res1 as ModelInstallOutcome.Success).modelDirPath)

        // Single wrapper dir must be flattened so `.onnx` & `tokens.txt` are directly in model dir
        assertTrue(File(dir1, "model.int8.onnx").isFile)
        assertEquals("ONNX_MODEL_DATA", File(dir1, "model.int8.onnx").readText())
        assertTrue(File(dir1, "tokens.txt").isFile)
        assertTrue(File(dir1, "tokenizer/vocab.json").isFile)
        assertFalse(
            "Top-level wrapper dir should be flattened away",
            File(dir1, "sherpa-onnx-sense-voice-test").exists()
        )
        assertTrue(File(dir1, OfflineModelCatalog.SILERO_VAD_FILE_NAME).isFile)
        assertArrayEquals(vadBytes, File(dir1, OfflineModelCatalog.SILERO_VAD_FILE_NAME).readBytes())
        assertEquals(1, vadDownloadHits.get())

        // Install a second VAD-requiring ASR model -> should reuse cached silero_vad without re-downloading
        val res2 = registry.installModel(OfflineModelCatalog.ID_ASR_PARAFORMER_ZH_SMALL)
        assertTrue("Expected Success, got $res2", res2 is ModelInstallOutcome.Success)
        val dir2 = File((res2 as ModelInstallOutcome.Success).modelDirPath)
        assertTrue(File(dir2, OfflineModelCatalog.SILERO_VAD_FILE_NAME).isFile)
        assertEquals(
            "Silero VAD should be reused from shared cache without downloading a second time",
            1,
            vadDownloadHits.get()
        )
        assertTrue("Multi-model coexistence should preserve previous ASR dir on disk", dir1.isDirectory)
        assertTrue(registry.snapshot.value.isInstalled(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL))
        assertTrue(registry.snapshot.value.isInstalled(OfflineModelCatalog.ID_ASR_PARAFORMER_ZH_SMALL))
    }

    // -------------------------------------------------------------------------
    // 5. importLocalSlmFiles Import, LoRA Support & Probe Rollback
    // -------------------------------------------------------------------------

    @Test
    fun `importLocalSlmFiles copies gguf and optional lora, probes after unload, and rolls back on probe failure`() = runBlocking {
        val root = tempFolder.newFolder("registry_slm_import")
        val bridge = FakeRuntimeBridge()
        var slmAvailable = false
        val registry = LocalModelRegistry(
            rootDir = root,
            runtimeBridge = bridge,
            downloader = SimulatedLocalModelDownloader(steps = 1, stepDelayMs = 0L),
            availableBytesProvider = { 20L * 1024 * 1024 * 1024 },
            slmEngineAvailableProvider = { slmAvailable }
        )

        val ggufA = "GGUF_MODEL_0_8B_PAYLOAD".toByteArray()
        val loraA = "GGUF_LORA_0_8B_ADAPTER".toByteArray()

        // 1. Open-source build (slmEngineAvailable=false) blocks SLM import cleanly
        val blocked = registry.importLocalSlmFiles(
            modelId = OfflineModelCatalog.ID_SLM_CORE_0_8B,
            ggufStreamProvider = { ByteArrayInputStream(ggufA) },
            ggufFileName = "qwen35_0_8b.gguf"
        )
        assertTrue(blocked is ModelInstallOutcome.ProbeFailed)
        assertFalse(registry.snapshot.value.isInstalled(OfflineModelCatalog.ID_SLM_CORE_0_8B))

        // Enable Pro SLM engine
        slmAvailable = true

        // 2. Non-.gguf file extension is rejected
        val wrongExt = registry.importLocalSlmFiles(
            modelId = OfflineModelCatalog.ID_SLM_CORE_0_8B,
            ggufStreamProvider = { ByteArrayInputStream(ggufA) },
            ggufFileName = "not_a_model.txt"
        )
        assertTrue(wrongExt is ModelInstallOutcome.VerificationFailed)

        // 3. Successful import of 0.8B with optional LoRA
        val importA = registry.importLocalSlmFiles(
            modelId = OfflineModelCatalog.ID_SLM_CORE_0_8B,
            ggufStreamProvider = { ByteArrayInputStream(ggufA) },
            ggufFileName = "typefree_slm_0_8b_v3-Q4_K_M.gguf",
            loraStreamProvider = { ByteArrayInputStream(loraA) },
            loraFileName = "typefree_slm_0_8b_v3-lora.gguf"
        )
        assertTrue("Expected Success, got $importA", importA is ModelInstallOutcome.Success)
        val successA = importA as ModelInstallOutcome.Success
        val dirA = File(successA.modelDirPath)
        assertEquals(listOf("unload:SLM", "probe:SLM:${OfflineModelCatalog.ID_SLM_CORE_0_8B}"), bridge.events)
        assertTrue(registry.snapshot.value.isInstalled(OfflineModelCatalog.ID_SLM_CORE_0_8B))
        assertArrayEquals(ggufA, File(dirA, LocalModelRegistry.SLM_MODEL_GGUF_NAME).readBytes())
        assertArrayEquals(loraA, File(dirA, LocalModelRegistry.SLM_LORA_GGUF_NAME).readBytes())

        // 4. Probe failure on importing a different SLM (2B) rolls back 2B and keeps 0.8B intact
        bridge.probeHandler = { _, modelId, _ ->
            if (modelId == OfflineModelCatalog.ID_SLM_PLUS_2B) {
                Result.failure(IOException("OOM during local SLM probe"))
            } else {
                Result.success(Unit)
            }
        }
        val failedB = registry.importLocalSlmFiles(
            modelId = OfflineModelCatalog.ID_SLM_PLUS_2B,
            ggufStreamProvider = { ByteArrayInputStream("GGUF_2B_BROKEN".toByteArray()) },
            ggufFileName = "typefree_slm_2b_v3-Q4_K_M.gguf"
        )
        assertTrue(failedB is ModelInstallOutcome.ProbeFailed)
        assertEquals(OfflineModelCatalog.ID_SLM_CORE_0_8B, (failedB as ModelInstallOutcome.ProbeFailed).keptOldModelId)
        assertEquals(
            LocalModelInstallStatus.UNSUPPORTED_ON_DEVICE,
            registry.snapshot.value.statusOf(OfflineModelCatalog.ID_SLM_PLUS_2B)
        )
        assertFalse(File(root, "slm/${OfflineModelCatalog.ID_SLM_PLUS_2B}").exists())
        assertTrue(registry.snapshot.value.isInstalled(OfflineModelCatalog.ID_SLM_CORE_0_8B))
        assertArrayEquals(ggufA, File(dirA, LocalModelRegistry.SLM_MODEL_GGUF_NAME).readBytes())

        // 5. Probe failure when replacing the SAME slot (0.8B) restores previous backup intact
        bridge.probeHandler = { _, _, _ -> Result.failure(IOException("Corrupt GGUF header")) }
        val failedReplaceSame = registry.importLocalSlmFiles(
            modelId = OfflineModelCatalog.ID_SLM_CORE_0_8B,
            ggufStreamProvider = { ByteArrayInputStream("BAD_GGUF_REPLACEMENT".toByteArray()) },
            ggufFileName = "broken.gguf"
        )
        assertTrue(failedReplaceSame is ModelInstallOutcome.ProbeFailed)
        assertEquals(OfflineModelCatalog.ID_SLM_CORE_0_8B, (failedReplaceSame as ModelInstallOutcome.ProbeFailed).keptOldModelId)
        assertTrue(registry.snapshot.value.isInstalled(OfflineModelCatalog.ID_SLM_CORE_0_8B))
        assertArrayEquals(
            "Previous working model.gguf must be restored after same-slot probe failure",
            ggufA,
            File(dirA, LocalModelRegistry.SLM_MODEL_GGUF_NAME).readBytes()
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createTarBz2(outFile: File, entries: List<Pair<String, ByteArray>>): File {
        outFile.writeBytes(createTarBz2Bytes(entries))
        return outFile
    }

    private fun createTarBz2Bytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val baos = ByteArrayOutputStream()
        BZip2CompressorOutputStream(BufferedOutputStream(baos)).use { bz2 ->
            TarArchiveOutputStream(bz2).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for ((name, bytes) in entries) {
                    val entry = TarArchiveEntry(name)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return baos.toByteArray()
    }
}
