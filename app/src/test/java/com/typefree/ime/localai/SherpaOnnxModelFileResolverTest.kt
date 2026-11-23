package com.typefree.ime.localai

import android.content.Context
import com.typefree.core.offline.AsrModelArch
import com.typefree.core.offline.OfflineModelCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class SherpaOnnxModelFileResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        LocalInferenceEngineBootstrap.resetForTesting()
        LocalInferenceEngineRegistry.resetDefaults()
    }

    // -------------------------------------------------------------------------
    // 1. All 8 Catalog ASR Model Directory Layouts (+ int8 preference & nesting)
    // -------------------------------------------------------------------------

    @Test
    fun `resolves zipformer zh 14m online transducer preferring int8 over fp32`() {
        val dir = tempFolder.newFolder("asr_zipformer_zh_14m")
        touch(dir, "encoder-epoch-99-avg-1.onnx")
        touch(dir, "encoder-epoch-99-avg-1.int8.onnx")
        touch(dir, "decoder-epoch-99-avg-1.onnx")
        touch(dir, "joiner-epoch-99-avg-1.onnx")
        touch(dir, "joiner-epoch-99-avg-1.int8.onnx")
        touch(dir, "tokens.txt")

        val resolved = SherpaOnnxModelFileResolver.resolveForModelId(
            OfflineModelCatalog.ID_ASR_ZIPFORMER_ZH_14M,
            dir.absolutePath
        ).getOrThrow()

        assertTrue(resolved is ResolvedAsrModelFiles.OnlineTransducer)
        resolved as ResolvedAsrModelFiles.OnlineTransducer
        assertTrue(resolved.encoderPath.endsWith("encoder-epoch-99-avg-1.int8.onnx"))
        assertTrue(resolved.decoderPath.endsWith("decoder-epoch-99-avg-1.onnx"))
        assertTrue(resolved.joinerPath.endsWith("joiner-epoch-99-avg-1.int8.onnx"))
        assertTrue(resolved.tokensPath.endsWith("tokens.txt"))
        assertNull(resolved.bpeVocabPath)
    }

    @Test
    fun `resolves zipformer bilingual online transducer with nested archive subdir and bpe vocab`() {
        val dir = tempFolder.newFolder("asr_zipformer_bilingual")
        val nested = File(dir, "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20").apply { mkdirs() }
        touch(nested, "encoder-epoch-99-avg-1.int8.onnx")
        touch(nested, "decoder-epoch-99-avg-1.onnx")
        touch(nested, "joiner-epoch-99-avg-1.int8.onnx")
        touch(nested, "tokens.txt")
        touch(nested, "bpe.vocab")

        val resolved = SherpaOnnxModelFileResolver.resolveForModelId(
            OfflineModelCatalog.ID_ASR_ZIPFORMER_BILINGUAL,
            dir.absolutePath
        ).getOrThrow()

        assertTrue(resolved is ResolvedAsrModelFiles.OnlineTransducer)
        resolved as ResolvedAsrModelFiles.OnlineTransducer
        assertEquals(nested.absolutePath, resolved.rootDir.absolutePath)
        assertTrue(resolved.encoderPath.endsWith("encoder-epoch-99-avg-1.int8.onnx"))
        assertTrue(resolved.bpeVocabPath!!.endsWith("bpe.vocab"))
        assertEquals("zipformer", resolved.modelType)
    }

    @Test
    fun `resolves streaming paraformer zh en online paraformer`() {
        val dir = tempFolder.newFolder("asr_streaming_paraformer_zh_en")
        touch(dir, "encoder.onnx")
        touch(dir, "encoder.int8.onnx")
        touch(dir, "decoder.onnx")
        touch(dir, "decoder.int8.onnx")
        touch(dir, "tokens.txt")

        val resolved = SherpaOnnxModelFileResolver.resolveForModelId(
            OfflineModelCatalog.ID_ASR_STREAMING_PARAFORMER_ZH_EN,
            dir.absolutePath
        ).getOrThrow()

        assertTrue(resolved is ResolvedAsrModelFiles.OnlineParaformer)
        resolved as ResolvedAsrModelFiles.OnlineParaformer
        assertTrue(resolved.encoderPath.endsWith("encoder.int8.onnx"))
        assertTrue(resolved.decoderPath.endsWith("decoder.int8.onnx"))
        assertTrue(resolved.tokensPath.endsWith("tokens.txt"))
    }

    @Test
    fun `resolves paraformer zh small offline paraformer with shared parent silero vad`() {
        val asrRoot = tempFolder.newFolder("asr_slot_root")
        touch(asrRoot, "silero_vad.int8.onnx")
        val dir = File(asrRoot, "asr_paraformer_zh_small").apply { mkdirs() }
        touch(dir, "model.int8.onnx")
        touch(dir, "tokens.txt")

        val resolved = SherpaOnnxModelFileResolver.resolveForModelId(
            OfflineModelCatalog.ID_ASR_PARAFORMER_ZH_SMALL,
            dir.absolutePath
        ).getOrThrow()

        assertTrue(resolved is ResolvedAsrModelFiles.OfflineParaformer)
        resolved as ResolvedAsrModelFiles.OfflineParaformer
        assertTrue(resolved.modelPath.endsWith("model.int8.onnx"))
        assertTrue(resolved.tokensPath.endsWith("tokens.txt"))
        assertNotNull(resolved.sileroVadPath)
        assertTrue(resolved.sileroVadPath!!.endsWith("silero_vad.int8.onnx"))
    }

    @Test
    fun `resolves sensevoice small offline model with local silero vad`() {
        val dir = tempFolder.newFolder("asr_sensevoice_small")
        val nested = File(dir, "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17").apply { mkdirs() }
        touch(nested, "model.int8.onnx")
        touch(nested, "tokens.txt")
        touch(dir, "silero_vad.int8.onnx")

        val resolved = SherpaOnnxModelFileResolver.resolveForModelId(
            OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
            dir.absolutePath
        ).getOrThrow()

        assertTrue(resolved is ResolvedAsrModelFiles.OfflineSenseVoice)
        resolved as ResolvedAsrModelFiles.OfflineSenseVoice
        assertTrue(resolved.modelPath.endsWith("model.int8.onnx"))
        assertTrue(resolved.tokensPath.endsWith("tokens.txt"))
        assertTrue(resolved.sileroVadPath!!.endsWith("silero_vad.int8.onnx"))
    }

    @Test
    fun `resolves firered2 ctc zh en offline model without silero vad`() {
        val dir = tempFolder.newFolder("asr_firered2_ctc_zh_en")
        touch(dir, "model.int8.onnx")
        touch(dir, "tokens.txt")

        val resolved = SherpaOnnxModelFileResolver.resolveForModelId(
            OfflineModelCatalog.ID_ASR_FIRERED2_CTC_ZH_EN,
            dir.absolutePath
        ).getOrThrow()

        assertTrue(resolved is ResolvedAsrModelFiles.OfflineFireRedCtc)
        resolved as ResolvedAsrModelFiles.OfflineFireRedCtc
        assertTrue(resolved.modelPath.endsWith("model.int8.onnx"))
        assertTrue(resolved.tokensPath.endsWith("tokens.txt"))
        assertNull(resolved.sileroVadPath)
    }

    @Test
    fun `resolves funasr nano offline model with Qwen3-0_6B tokenizer directory`() {
        val dir = tempFolder.newFolder("asr_funasr_nano")
        val nested = File(dir, "sherpa-onnx-funasr-nano-int8-2025-12-30").apply { mkdirs() }
        touch(nested, "encoder_adaptor.int8.onnx")
        touch(nested, "llm.int8.onnx")
        touch(nested, "embedding.int8.onnx")
        val tokDir = File(nested, "Qwen3-0.6B").apply { mkdirs() }
        touch(tokDir, "tokenizer.json")
        touch(tokDir, "vocab.json")
        touch(nested, "silero_vad.int8.onnx")

        val resolved = SherpaOnnxModelFileResolver.resolveForModelId(
            OfflineModelCatalog.ID_ASR_FUNASR_NANO,
            dir.absolutePath
        ).getOrThrow()

        assertTrue(resolved is ResolvedAsrModelFiles.OfflineFunAsrNano)
        resolved as ResolvedAsrModelFiles.OfflineFunAsrNano
        assertTrue(resolved.encoderAdaptorPath.endsWith("encoder_adaptor.int8.onnx"))
        assertTrue(resolved.llmPath.endsWith("llm.int8.onnx"))
        assertTrue(resolved.embeddingPath.endsWith("embedding.int8.onnx"))
        assertEquals(tokDir.absolutePath, resolved.tokenizerDirPath)
        assertTrue(resolved.sileroVadPath!!.endsWith("silero_vad.int8.onnx"))
    }

    @Test
    fun `resolves qwen3 asr 0_6b offline model with tokenizer directory`() {
        val dir = tempFolder.newFolder("asr_qwen3_asr_0_6b")
        val nested = File(dir, "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25").apply { mkdirs() }
        touch(nested, "conv_frontend.onnx")
        touch(nested, "encoder.int8.onnx")
        touch(nested, "decoder.int8.onnx")
        val tokDir = File(nested, "tokenizer").apply { mkdirs() }
        touch(tokDir, "vocab.json")
        touch(tokDir, "merges.txt")

        val resolved = SherpaOnnxModelFileResolver.resolveForModelId(
            OfflineModelCatalog.ID_ASR_QWEN3_ASR_0_6B,
            dir.absolutePath
        ).getOrThrow()

        assertTrue(resolved is ResolvedAsrModelFiles.OfflineQwen3Asr)
        resolved as ResolvedAsrModelFiles.OfflineQwen3Asr
        assertTrue(resolved.convFrontendPath.endsWith("conv_frontend.onnx"))
        assertTrue(resolved.encoderPath.endsWith("encoder.int8.onnx"))
        assertTrue(resolved.decoderPath.endsWith("decoder.int8.onnx"))
        assertEquals(tokDir.absolutePath, resolved.tokenizerDirPath)
    }

    @Test
    fun `fails cleanly when required files are missing`() {
        val emptyDir = tempFolder.newFolder("empty_model_dir")
        val res = SherpaOnnxModelFileResolver.resolve(emptyDir.absolutePath, AsrModelArch.OFFLINE_SENSE_VOICE)
        assertTrue(res.isFailure)

        val missingTok = tempFolder.newFolder("missing_tok_qwen3")
        touch(missingTok, "conv_frontend.onnx")
        touch(missingTok, "encoder.int8.onnx")
        touch(missingTok, "decoder.int8.onnx")
        val qwenFail = SherpaOnnxModelFileResolver.resolve(missingTok.absolutePath, AsrModelArch.OFFLINE_QWEN3_ASR)
        assertTrue(qwenFail.isFailure)
    }

    // -------------------------------------------------------------------------
    // 2. WAV Parser (standard 44-byte, non-standard LIST/JUNK odd-size chunks, cancel)
    // -------------------------------------------------------------------------

    @Test
    fun `parses standard 16kHz 16-bit mono WAV accurately`() {
        val pcmSamples = shortArrayOf(0, 16384, -16384, 32767, -32768)
        val wavFile = tempFolder.newFile("standard.wav")
        wavFile.writeBytes(buildWavBytes(sampleRate = 16_000, channels = 1, pcm16 = pcmSamples))

        val parsed = SherpaOnnxWavParser.readWavFile(wavFile).getOrThrow()
        assertEquals(16_000, parsed.sampleRateHz)
        assertEquals(1, parsed.numChannels)
        assertEquals(16, parsed.bitsPerSample)
        assertEquals(pcmSamples.size, parsed.samples.size)
        assertEquals(0.0f, parsed.samples[0], 1e-5f)
        assertEquals(0.5f, parsed.samples[1], 1e-4f)
        assertEquals(-0.5f, parsed.samples[2], 1e-4f)
        assertEquals(32767f / 32768f, parsed.samples[3], 1e-4f)
        assertEquals(-1.0f, parsed.samples[4], 1e-5f)
    }

    @Test
    fun `parses WAV with extra LIST and odd-sized JUNK chunks before data chunk`() {
        val pcmSamples = shortArrayOf(8192, -8192, 4096, -4096)
        val listPayload = "INFOINAM\u0008\u0000\u0000\u0000TestClip".toByteArray(Charsets.US_ASCII)
        // Odd-sized JUNK chunk (5 bytes) must skip 6 bytes (5 + 1 RIFF alignment pad byte)
        val junkPayload = byteArrayOf(1, 2, 3, 4, 5)
        val wavFile = tempFolder.newFile("with_list_junk.wav")
        wavFile.writeBytes(
            buildWavBytes(
                sampleRate = 16_000,
                channels = 1,
                pcm16 = pcmSamples,
                extraChunksBeforeData = listOf(
                    "JUNK" to junkPayload,
                    "LIST" to listPayload
                )
            )
        )

        val parsed = SherpaOnnxWavParser.readWavFile(wavFile).getOrThrow()
        assertEquals(16_000, parsed.sampleRateHz)
        assertEquals(4, parsed.samples.size)
        assertEquals(0.25f, parsed.samples[0], 1e-4f)
        assertEquals(-0.25f, parsed.samples[1], 1e-4f)
        assertEquals(0.125f, parsed.samples[2], 1e-4f)
        assertEquals(-0.125f, parsed.samples[3], 1e-4f)
    }

    @Test
    fun `downmixes stereo WAV to mono and honors cooperative cancellation`() {
        // Stereo frame 0: L=16384 (0.5), R=0 (0.0) -> avg 0.25
        // Stereo frame 1: L=-16384 (-0.5), R=-16384 (-0.5) -> avg -0.5
        val stereoPcm = shortArrayOf(16384, 0, -16384, -16384)
        val wavFile = tempFolder.newFile("stereo.wav")
        wavFile.writeBytes(buildWavBytes(sampleRate = 16_000, channels = 2, pcm16 = stereoPcm))

        val parsed = SherpaOnnxWavParser.readWavFile(wavFile).getOrThrow()
        assertEquals(2, parsed.samples.size)
        assertEquals(0.25f, parsed.samples[0], 1e-4f)
        assertEquals(-0.5f, parsed.samples[1], 1e-4f)

        val cancelled = AtomicBoolean(true)
        val cancelRes = SherpaOnnxWavParser.readWavFile(wavFile, cancelled)
        assertTrue(cancelRes.isFailure)
    }

    @Test
    fun `pcm16LeBytesToFloats preserves sample alignment across odd-byte pipe splits`() {
        // Two 16-bit LE samples: 16384 (0x00, 0x40) and -16384 (0x00, 0xC0)
        val raw = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte())
        var hasCarry = false
        var carry: Byte = 0

        // Split after 3 bytes (odd read!)
        val part1 = SherpaOnnxLocalAsrEngine.pcm16LeBytesToFloats(
            bytes = raw.copyOfRange(0, 3),
            length = 3,
            hasCarryByte = hasCarry,
            carryByte = carry
        ) { nextHas, nextByte ->
            hasCarry = nextHas
            carry = nextByte
        }
        assertEquals(1, part1.size)
        assertEquals(0.5f, part1[0], 1e-4f)
        assertTrue(hasCarry)

        val part2 = SherpaOnnxLocalAsrEngine.pcm16LeBytesToFloats(
            bytes = raw.copyOfRange(3, 4),
            length = 1,
            hasCarryByte = hasCarry,
            carryByte = carry
        ) { nextHas, nextByte ->
            hasCarry = nextHas
            carry = nextByte
        }
        assertEquals(1, part2.size)
        assertEquals(-0.5f, part2[0], 1e-4f)
        assertFalse(hasCarry)
    }

    // -------------------------------------------------------------------------
    // 3. Hotwords Formatter & Thread Count
    // -------------------------------------------------------------------------

    @Test
    fun `formats Chinese, English, and mixed hotwords with sherpa-onnx spacing rules`() {
        assertEquals("小 鹤 双 拼", SherpaOnnxHotwordsFormatter.formatHotwordLine("小鹤双拼"))
        assertEquals("TYPEFREE", SherpaOnnxHotwordsFormatter.formatHotwordLine("TypeFree"))
        assertEquals("TYPEFREE 输 入 法", SherpaOnnxHotwordsFormatter.formatHotwordLine("TypeFree输入法"))
        assertEquals("AI 语 音 润 色", SherpaOnnxHotwordsFormatter.formatHotwordLine("  AI   语音润色  "))
        assertEquals("DEEPSEEK V3 模 型", SherpaOnnxHotwordsFormatter.formatHotwordLine("DeepSeek-V3模型"))
        assertEquals("", SherpaOnnxHotwordsFormatter.formatHotwordLine("   "))

        val fileContent = SherpaOnnxHotwordsFormatter.formatHotwordsFileContent(
            listOf("小鹤双拼", "TypeFree输入法", "小鹤双拼", "   ", "OpenAI API")
        )
        assertEquals(
            "小 鹤 双 拼\nTYPEFREE 输 入 法\nOPENAI API",
            fileContent
        )
    }

    @Test
    fun `computes bounded CPU thread count between 2 and 4`() {
        assertEquals(2, SherpaOnnxLocalAsrEngine.computeOptimalThreadCount(1))
        assertEquals(2, SherpaOnnxLocalAsrEngine.computeOptimalThreadCount(2))
        assertEquals(2, SherpaOnnxLocalAsrEngine.computeOptimalThreadCount(4))
        assertEquals(3, SherpaOnnxLocalAsrEngine.computeOptimalThreadCount(6))
        assertEquals(4, SherpaOnnxLocalAsrEngine.computeOptimalThreadCount(8))
        assertEquals(4, SherpaOnnxLocalAsrEngine.computeOptimalThreadCount(16))
    }

    // -------------------------------------------------------------------------
    // 4. LocalInferenceEngineBootstrap SPI Detection & Reflection
    // -------------------------------------------------------------------------

    @Test
    fun `LocalInferenceEngineBootstrap skips Sherpa on JVM unit test runtime and silently ignores missing Pro registrar`() {
        LocalInferenceEngineRegistry.resetDefaults()
        LocalInferenceEngineBootstrap.resetForTesting()

        assertFalse(LocalInferenceEngineBootstrap.isAndroidDeviceRuntime())
        LocalInferenceEngineBootstrap.initializeInternal(
            context = null,
            isAndroidRuntime = false,
            sherpaClassName = LocalInferenceEngineBootstrap.SHERPA_CLASS_NAME,
            proRegistrarClassName = "com.typefree.pro.localai.NonExistentRegistrar"
        )

        assertFalse(LocalInferenceEngineBootstrap.isSherpaAsrRegistered)
        assertFalse(LocalInferenceEngineBootstrap.isProRegistrarLoaded)
        assertTrue(LocalInferenceEngineRegistry.createAsrEngine() is MockLocalAsrEngine)
        assertFalse(LocalInferenceEngineRegistry.isSlmEngineAvailable)
        assertTrue(LocalInferenceEngineRegistry.createSlmEngine() === NoOpLocalSlmEngine)
    }

    @Test
    fun `LocalInferenceEngineBootstrap registers SherpaOnnxLocalAsrEngine on Android runtime and invokes Pro registrar via reflection`() {
        LocalInferenceEngineRegistry.resetDefaults()
        LocalInferenceEngineBootstrap.resetForTesting()
        FakeProRegistrar.invoked = false

        LocalInferenceEngineBootstrap.initializeInternal(
            context = null,
            isAndroidRuntime = true,
            sherpaClassName = LocalInferenceEngineBootstrap.SHERPA_CLASS_NAME,
            proRegistrarClassName = FakeProRegistrar::class.java.name
        )

        assertTrue(
            "SherpaOnnx OfflineRecognizer class should be detectable on classpath without loading JNI",
            LocalInferenceEngineBootstrap.isSherpaAsrRegistered
        )
        assertTrue(LocalInferenceEngineRegistry.createAsrEngine() is SherpaOnnxLocalAsrEngine)
        assertTrue(LocalInferenceEngineBootstrap.isProRegistrarLoaded)
        assertTrue(FakeProRegistrar.invoked)
        assertTrue(LocalInferenceEngineRegistry.isSlmEngineAvailable)
    }

    object FakeProRegistrar {
        @Volatile
        var invoked: Boolean = false

        @JvmStatic
        fun register(context: Context?) {
            invoked = true
            LocalInferenceEngineRegistry.registerSlmFactory(available = true) { NoOpLocalSlmEngine }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun touch(dir: File, name: String): File {
        return File(dir, name).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
    }

    private fun buildWavBytes(
        sampleRate: Int,
        channels: Int,
        pcm16: ShortArray,
        extraChunksBeforeData: List<Pair<String, ByteArray>> = emptyList()
    ): ByteArray {
        val body = ByteArrayOutputStream()

        // fmt chunk (16 bytes payload)
        val fmt = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2)
            putShort((channels * 2).toShort())
            putShort(16)
        }
        body.write(fmt.array())

        // optional non-standard chunks (e.g., LIST, JUNK)
        for ((id, payload) in extraChunksBeforeData) {
            require(id.length == 4)
            val hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(id.toByteArray(Charsets.US_ASCII))
                putInt(payload.size)
            }
            body.write(hdr.array())
            body.write(payload)
            if (payload.size % 2 == 1) {
                body.write(0) // RIFF word alignment pad byte
            }
        }

        // data chunk
        val dataBytes = pcm16.size * 2
        val dataBuf = ByteBuffer.allocate(8 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
            for (s in pcm16) {
                putShort(s)
            }
        }
        body.write(dataBuf.array())

        val bodyBytes = body.toByteArray()
        val riff = ByteBuffer.allocate(12 + bodyBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(4 + bodyBytes.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put(bodyBytes)
        }
        return riff.array()
    }
}
