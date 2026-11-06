package com.typefree.ime.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.DictionarySource
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.ModelCapabilitySlot
import com.typefree.ime.data.ModelProbeResult
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.ThinkingMode
import com.typefree.ime.network.asr.DashScopeStreamingAsrClient
import com.typefree.ime.network.asr.VolcengineSaucAsrClient
import com.typefree.ime.ui.screens.ApiConfigBackupHelper
import com.typefree.ime.ui.screens.SetupStatsHelper
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LowLatencyAndStreamingAsrTest {

    @Before
    fun resetThinkingCache() {
        AiPipelineEngine.clearUnsupportedThinkingCache()
    }

    @Test
    fun `applyNonThinkingHints disables CoT on Doubao Seed, Qwen3, Bailian, and GLM-4_5 while preserving reasoners`() {
        // 1. Doubao Seed 2.1 Lite (uses official `reasoning_effort = "minimal"`) & 1.6 Flash (`thinking.type = "disabled"`)
        val ark21Body = JsonObject()
        assertTrue(
            AiPipelineEngine.applyNonThinkingHints(
                body = ark21Body,
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                model = "doubao-seed-2-1-lite-260915"
            )
        )
        assertEquals("minimal", ark21Body.get("reasoning_effort").asString)
        assertFalse(ark21Body.has("thinking"))

        val arkBody = JsonObject()
        assertTrue(
            AiPipelineEngine.applyNonThinkingHints(
                body = arkBody,
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                model = "doubao-seed-1-6-flash-250828"
            )
        )
        assertEquals("disabled", arkBody.getAsJsonObject("thinking").get("type").asString)

        // 2. Custom Volcengine inference endpoint ID (`ep-...`)
        val epBody = JsonObject()
        assertTrue(
            AiPipelineEngine.applyNonThinkingHints(
                body = epBody,
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                model = "ep-20260501120000-abcde"
            )
        )
        assertEquals("disabled", epBody.getAsJsonObject("thinking").get("type").asString)

        // 3. Qwen3 on SiliconFlow
        val sfQwen3Body = JsonObject()
        assertTrue(
            AiPipelineEngine.applyNonThinkingHints(
                body = sfQwen3Body,
                baseUrl = "https://api.siliconflow.cn/v1",
                model = "Qwen/Qwen3-8B"
            )
        )
        assertFalse(sfQwen3Body.get("enable_thinking").asBoolean)

        // 4. Qwen-Turbo on Aliyun Bailian DashScope compatible-mode
        val bailianBody = JsonObject()
        assertTrue(
            AiPipelineEngine.applyNonThinkingHints(
                body = bailianBody,
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                model = "qwen-turbo-latest"
            )
        )
        assertFalse(bailianBody.get("enable_thinking").asBoolean)

        // 5. Explicit reasoning models must NOT have thinking disabled
        val r1Body = JsonObject()
        assertFalse(
            AiPipelineEngine.applyNonThinkingHints(
                body = r1Body,
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-reasoner"
            )
        )
        assertFalse(r1Body.has("thinking"))
        assertFalse(r1Body.has("enable_thinking"))
    }

    @Test
    fun `OpenAI chat completion falls back cleanly if legacy proxy rejects non-thinking hint with HTTP 400`() = runBlocking {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                val bodyStr = buf.readUtf8()
                requests.add(bodyStr)

                if (bodyStr.contains("enable_thinking")) {
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("Bad Request")
                        .body("""{"error":{"message":"Unrecognized field enable_thinking"}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"choices":[{"message":{"role":"assistant","content":"网关降级重试成功。"}}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val engine = AiPipelineEngine(httpClient = client)
        val settings = AppSettings(
            pipelineMode = PipelineMode.DUAL_STAGE,
            llmConfig = LlmConfig(
                enabled = true,
                baseUrl = "https://legacy-oneapi.example.com/v1",
                apiKey = "sk-test",
                model = "Qwen/Qwen3-8B"
            )
        )
        val res = engine.runTextTransform(
            settings = settings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            rawOrSelectedText = "嗯测试一下网关回退",
            explicitInstruction = null,
            targetAppName = "飞书"
        ).getOrThrow()

        assertEquals("网关降级重试成功。", res.finalOutput)
        assertEquals(2, requests.size)
        assertTrue(requests[0].contains("enable_thinking"))
        assertFalse(requests[1].contains("enable_thinking"))
    }

    @Test
    fun `PromptBuilder caps large dictionary bloat while prioritizing matched homophones`() {
        val bigDict = (1..65).map { idx ->
            DictionaryEntry(
                id = "auto_$idx",
                term = "UnrelatedTerm$idx",
                source = DictionarySource.AUTO_LEARNED,
                createdAt = idx.toLong()
            )
        } + DictionaryEntry(
            id = "hit_entry",
            term = "VolcengineSAUC",
            wrongVariants = "火山流式接口",
            source = DictionarySource.AUTO_LEARNED,
            createdAt = 0L
        )

        val (_, usr) = PromptBuilder.buildDictationPrompts(
            rawSpeech = "我们今天接入火山流式接口测试一下延迟",
            style = DefaultPresets.defaultStyles().first(),
            targetAppName = "Cursor",
            dictionary = bigDict,
            surroundingBeforeCursor = ""
        )

        assertTrue(usr.contains("VolcengineSAUC"))
        val selected = PromptBuilder.selectRelevantDictionaryEntries(
            dictionary = bigDict,
            referenceText = "我们今天接入火山流式接口测试一下延迟",
            maxEntries = 40
        )
        assertEquals(40, selected.size)
        assertEquals("VolcengineSAUC", selected.first().term)
    }

    @Test
    fun `WavAudioRecorder multi-frame VAD filters 1-frame mechanical clicks and accepts whispers`() {
        // Single mechanical button click spike followed by room noise -> rejected
        val mechanicalTap = listOf(690.0, 42.0, 38.0, 40.0, 35.0, 39.0)
        assertFalse(WavAudioRecorder.scanFramesForSpeech(mechanicalTap))

        // Soft continuous whisper (>= 3 frames above 155 RMS, even if never exceeding 420) -> accepted
        val softWhisper = listOf(50.0, 175.0, 190.0, 168.0, 60.0)
        assertTrue(WavAudioRecorder.scanFramesForSpeech(softWhisper))

        // Crisp 2-frame short syllable ("好" / "OK") -> accepted
        val shortCrispWord = listOf(45.0, 520.0, 480.0, 50.0)
        assertTrue(WavAudioRecorder.scanFramesForSpeech(shortCrispWord))
    }

    @Test
    fun `WavAudioRecorder buildWavHeader and extractPcmFromWav round-trip exact PCM payload`() {
        val pcm = ByteArray(1600) { i -> ((i * 7) and 0xFF).toByte() }
        val header = WavAudioRecorder.buildWavHeader(dataSize = pcm.size)
        assertEquals(44, header.size)

        val tmp = File.createTempFile("wav_roundtrip_", ".wav")
        try {
            tmp.writeBytes(header + pcm)
            val extracted = WavAudioRecorder.extractPcmFromWav(tmp)
            assertArrayEquals(pcm, extracted)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `VolcengineSaucAsrClient encodes full request with hotwords and decodes sequence-prefixed server frames`() {
        val initJson = VolcengineSaucAsrClient.buildFullRequestJson(
            uid = "u_1001",
            modelName = "bigmodel",
            language = "zh",
            hotwords = listOf("TypeFree", "Doubao-Seed")
        )
        val parsedInit = JsonParser.parseString(initJson).asJsonObject
        assertEquals(16000, parsedInit.getAsJsonObject("audio").get("rate").asInt)
        assertFalse(parsedInit.getAsJsonObject("request").get("enable_ddc").asBoolean)
        val corpusContext = parsedInit.getAsJsonObject("request")
            .getAsJsonObject("corpus")
            .get("context").asString
        assertTrue(corpusContext.contains("TypeFree"))
        assertTrue(corpusContext.contains("Doubao-Seed"))

        // Verify audio chunk flags (intermediate vs final EOF)
        val midChunk = VolcengineSaucAsrClient.encodeAudioChunk(ByteArray(320) { 3 }, isLast = false)
        val eofChunk = VolcengineSaucAsrClient.encodeAudioChunk(ByteArray(0), isLast = true)
        assertEquals(0x20, midChunk[1].toInt() and 0xFF) // MSG_CLIENT_AUDIO_ONLY (0x2) | FLAG_NO_SEQUENCE (0x0)
        assertEquals(0x22, eofChunk[1].toInt() and 0xFF) // MSG_CLIENT_AUDIO_ONLY (0x2) | FLAG_NEG_SEQUENCE (0x2)

        // Simulate Volcengine SAUC v3 server response with FLAG_NEG_WITH_SEQUENCE (0x03):
        // [4B header] + [4B sequence = -5] + [4B payloadSize] + [Gzipped JSON]
        val serverHeader = VolcengineSaucAsrClient.buildHeader(
            messageType = VolcengineSaucAsrClient.MSG_SERVER_FULL_RESPONSE,
            flags = VolcengineSaucAsrClient.FLAG_NEG_WITH_SEQUENCE,
            serialization = VolcengineSaucAsrClient.SERIALIZATION_JSON,
            compression = VolcengineSaucAsrClient.COMPRESSION_GZIP
        )
        val respPayload = """{"result":{"text":"嗯明天下午三点跟王总对一下 TypeFree 方案。","utterances":[{"text":"嗯明天下午三点跟王总对一下 TypeFree 方案。","definite":true}]}}"""
        val gzPayload = VolcengineSaucAsrClient.gzipCompress(respPayload.toByteArray(Charsets.UTF_8))
        val rawServerFrame = ByteBuffer.allocate(4 + 4 + 4 + gzPayload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(serverHeader)
            .putInt(-5)
            .putInt(gzPayload.size)
            .put(gzPayload)
            .array()

        val decoded = VolcengineSaucAsrClient.decodeServerFrame(rawServerFrame)
        assertTrue(decoded is VolcengineSaucAsrClient.DecodedServerFrame.RecognitionResult)
        val rec = decoded as VolcengineSaucAsrClient.DecodedServerFrame.RecognitionResult
        assertEquals("嗯明天下午三点跟王总对一下 TypeFree 方案。", rec.text)
        assertTrue(rec.isFinalPackage)
        assertTrue(rec.definiteUtteranceEnded)

        // Simulate Volcengine server error frame (0x0F)
        val errHeader = VolcengineSaucAsrClient.buildHeader(
            messageType = VolcengineSaucAsrClient.MSG_SERVER_ERROR,
            flags = VolcengineSaucAsrClient.FLAG_NO_SEQUENCE,
            serialization = VolcengineSaucAsrClient.SERIALIZATION_JSON,
            compression = VolcengineSaucAsrClient.COMPRESSION_NONE
        )
        val errBytes = "Invalid resource id".toByteArray(Charsets.UTF_8)
        val rawErrFrame = ByteBuffer.allocate(4 + 8 + errBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(errHeader)
            .putInt(45000001)
            .putInt(errBytes.size)
            .put(errBytes)
            .array()
        val decodedErr = VolcengineSaucAsrClient.decodeServerFrame(rawErrFrame) as VolcengineSaucAsrClient.DecodedServerFrame.ServerError
        assertEquals(45000001, decodedErr.code)
        assertEquals("Invalid resource id", decodedErr.message)
    }

    @Test
    fun `DashScopeStreamingAsrClient parses events and aggregates multi-sentence utterances`() {
        val runJson = DashScopeStreamingAsrClient.buildRunTaskJson(
            taskId = "task123456",
            model = "paraformer-realtime-v2",
            language = "zh"
        )
        val runObj = JsonParser.parseString(runJson).asJsonObject
        assertEquals("run-task", runObj.getAsJsonObject("header").get("action").asString)
        assertEquals("paraformer-realtime-v2", runObj.getAsJsonObject("payload").get("model").asString)

        val aggregator = DashScopeStreamingAsrClient.SentenceAggregator()

        val ev1Partial = DashScopeStreamingAsrClient.parseServerEvent(
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"sentence_id":1,"begin_time":0,"end_time":null,"text":"第一句话正在识别","sentence_end":false}}}}"""
        ) as DashScopeStreamingAsrClient.ServerEvent.ResultGenerated
        aggregator.update(ev1Partial.sentenceId, ev1Partial.beginTime, ev1Partial.text, ev1Partial.isSentenceEnd)
        assertEquals("第一句话正在识别", aggregator.buildFullText())

        val ev1Final = DashScopeStreamingAsrClient.parseServerEvent(
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"sentence_id":1,"begin_time":0,"end_time":1820,"text":"第一句话已经说完，","sentence_end":true}}}}"""
        ) as DashScopeStreamingAsrClient.ServerEvent.ResultGenerated
        aggregator.update(ev1Final.sentenceId, ev1Final.beginTime, ev1Final.text, ev1Final.isSentenceEnd)

        val ev2Final = DashScopeStreamingAsrClient.parseServerEvent(
            """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"sentence_id":2,"begin_time":1950,"end_time":3600,"text":"接着说第二句话。","sentence_end":true}}}}"""
        ) as DashScopeStreamingAsrClient.ServerEvent.ResultGenerated
        aggregator.update(ev2Final.sentenceId, ev2Final.beginTime, ev2Final.text, ev2Final.isSentenceEnd)

        assertEquals("第一句话已经说完，接着说第二句话。", aggregator.buildFullText())
    }

    @Test
    fun `processVoiceAudio with preTranscribedText bypasses HTTP STT upload and records streaming telemetry`() = runBlocking {
        val capturedPaths = mutableListOf<String>()
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req: Request = chain.request()
                capturedPaths.add(req.url.encodedPath)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"choices":[{"message":{"role":"assistant","content":"明天下午四点评审流式方案。"}}]}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            })
            .build()

        val tempWav = File.createTempFile("streaming_bypass_", ".wav").apply {
            writeBytes(WavAudioRecorder.buildWavHeader(64) + ByteArray(64))
            deleteOnExit()
        }

        try {
            val engine = AiPipelineEngine(httpClient = mockClient)
            val stages = mutableListOf<PipelineStage>()
            val settings = AppSettings(
                pipelineMode = PipelineMode.DUAL_STAGE,
                sttConfig = SttConfig(
                    presetId = "volcengine_sauc_ws",
                    protocol = SttProtocol.VOLCENGINE_SAUC_WS,
                    baseUrl = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
                    apiKey = "volc-token",
                    appId = "123456"
                ),
                llmConfig = LlmConfig(
                    enabled = true,
                    presetId = "doubao",
                    baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                    apiKey = "ark-key",
                    model = "doubao-seed-1-6-flash-250828"
                )
            )

            val res = engine.processVoiceAudio(
                wavFile = tempWav,
                settings = settings,
                activeStyle = DefaultPresets.defaultStyles().first(),
                targetAppName = "飞书",
                selectedText = "",
                surroundingBeforeCursor = "",
                preTranscribedText = "嗯明天下午三点不对四点评审流式方案",
                sttTailWaitMs = 95L,
                onStageChanged = { stages.add(it) }
            ).getOrThrow()

            // Zero HTTP STT uploads! Jumped directly to POLISHING_LLM
            assertEquals(listOf("/api/v3/chat/completions"), capturedPaths)
            assertEquals(listOf(PipelineStage.POLISHING_LLM), stages)
            assertTrue(res.usedStreamingAsr)
            assertEquals(95L, res.sttLatencyMs)
            assertEquals("明天下午四点评审流式方案。", res.finalOutput)
        } finally {
            tempWav.delete()
        }
    }

    @Test
    fun `EndpointResolver, SetupStatsHelper, and ApiConfigBackupHelper support Bailian and Doubao streaming presets`() {
        assertEquals(
            "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            EndpointResolver.resolveDashScopeWsEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1")
        )
        assertEquals(
            "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
            EndpointResolver.resolveVolcengineSaucWsEndpoint("")
        )
        assertEquals(
            "https://openspeech.bytedance.com",
            EndpointResolver.extractHttpOrigin("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async")
        )

        // 1-tap Bailian WS -> Bailian HTTP LLM credential reuse never leaks wss:// into LLM Base URL
        val bailianWsStt = SttConfig(
            presetId = "aliyun_asr_ws",
            protocol = SttProtocol.ALIYUN_DASHSCOPE_WS,
            baseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            apiKey = "sk-bailian-unified",
            model = "paraformer-realtime-v2"
        )
        val syncedLlm = ApiConfigBackupHelper.reuseSttCredentialsForLlm(bailianWsStt, LlmConfig())
        assertEquals("aliyun_bailian", syncedLlm.presetId)
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", syncedLlm.baseUrl)
        assertEquals("sk-bailian-unified", syncedLlm.apiKey)
        assertEquals("qwen-turbo-latest", syncedLlm.model)

        // Config export & import round-trips SttProtocol, appId, and resourceId
        val volcSettings = AppSettings(
            sttConfig = SttConfig(
                presetId = "volcengine_sauc_ws",
                protocol = SttProtocol.VOLCENGINE_SAUC_WS,
                baseUrl = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
                apiKey = "volc-access-token",
                appId = "99887766",
                resourceId = "volc.seedasr.sauc.duration",
                model = "bigmodel"
            ),
            llmConfig = LlmConfig(enabled = false)
        )
        assertTrue(SetupStatsHelper.isApiConfigured(volcSettings))
        assertTrue(SetupStatsHelper.formatActivePipelineBadge(volcSettings).contains("豆包流式"))

        val exported = ApiConfigBackupHelper.exportSettingsJson(volcSettings, includeSecrets = true)
        val imported = ApiConfigBackupHelper.parseAndMergeConfigJson(exported, AppSettings()).getOrThrow()
        assertEquals(SttProtocol.VOLCENGINE_SAUC_WS, imported.sttConfig.protocol)
        assertEquals("99887766", imported.sttConfig.appId)
        assertEquals("volc.seedasr.sauc.duration", imported.sttConfig.resourceId)
    }

    @Test
    fun `universal applyThinkingControl supports DISABLED, AUTO, ENABLED and caches HTTP 400 fallback`() = runBlocking {
        // 1. AUTO leaves request payload untouched even on Doubao Seed 2.1 Lite & Qwen3
        val autoBody = JsonObject()
        assertFalse(
            AiPipelineEngine.applyThinkingControl(
                body = autoBody,
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                model = "doubao-seed-2-1-lite-260915",
                thinkingMode = ThinkingMode.AUTO
            )
        )
        assertFalse(autoBody.has("thinking"))

        // 2. ENABLED turns on thinking across Volcengine Seed 2.1 (`reasoning_effort = "high"`), Qwen3/Bailian, OpenAI GPT-5, and Gemini
        val arkEnabled = JsonObject()
        assertTrue(
            AiPipelineEngine.applyThinkingControl(
                body = arkEnabled,
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                model = "doubao-seed-2-1-lite-260915",
                thinkingMode = ThinkingMode.ENABLED
            )
        )
        assertEquals("high", arkEnabled.get("reasoning_effort").asString)

        val qwenEnabled = JsonObject()
        assertTrue(
            AiPipelineEngine.applyThinkingControl(
                body = qwenEnabled,
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                model = "qwen-plus-latest",
                thinkingMode = ThinkingMode.ENABLED
            )
        )
        assertTrue(qwenEnabled.get("enable_thinking").asBoolean)

        val gpt5Disabled = JsonObject()
        assertTrue(
            AiPipelineEngine.applyThinkingControl(
                body = gpt5Disabled,
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-5-mini",
                thinkingMode = ThinkingMode.DISABLED
            )
        )
        assertEquals("low", gpt5Disabled.get("reasoning_effort").asString)

        val geminiEnabled = AiPipelineEngine.buildGeminiGenerationConfig(
            model = "gemini-3.8-flash",
            temperature = 0.2f,
            thinkingMode = ThinkingMode.ENABLED
        )
        assertNotNull(geminiEnabled)
        assertEquals("HIGH", geminiEnabled!!.getAsJsonObject("thinkingConfig").get("thinkingLevel").asString)

        val geminiAuto = AiPipelineEngine.buildGeminiGenerationConfig(
            model = "gemini-3.8-flash",
            temperature = 0.2f,
            thinkingMode = ThinkingMode.AUTO
        )
        assertNull(geminiAuto)

        // 3. Unknown custom model on strict proxy: 1st call retries on HTTP 400 and caches, 2nd call sends 1 clean request!
        val sentBodies = mutableListOf<String>()
        val strictClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                val str = buf.readUtf8()
                sentBodies.add(str)
                if (str.contains("\"thinking\"")) {
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("Bad Request")
                        .body("""{"error":{"message":"Unknown field: thinking"}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val engine = AiPipelineEngine(httpClient = strictClient)
        val customSettings = AppSettings(
            pipelineMode = PipelineMode.DUAL_STAGE,
            llmConfig = LlmConfig(
                enabled = true,
                baseUrl = "https://strict-custom-gateway.example.com/v1",
                apiKey = "sk-custom",
                model = "moonshot-kimi-k2-custom",
                thinkingMode = ThinkingMode.DISABLED
            )
        )
        engine.runTextTransform(
            settings = customSettings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            rawOrSelectedText = "第一次请求触发自愈记忆",
            explicitInstruction = null,
            targetAppName = "飞书"
        ).getOrThrow()
        assertEquals(2, sentBodies.size)

        sentBodies.clear()
        engine.runTextTransform(
            settings = customSettings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            rawOrSelectedText = "第二次请求零额外重试开销",
            explicitInstruction = null,
            targetAppName = "飞书"
        ).getOrThrow()
        assertEquals(1, sentBodies.size)
        assertFalse(sentBodies.single().contains("\"thinking\""))
    }

    @Test
    fun `probeModelsBatch measures real 1-token latency, reports 403 status, and removes hardcoded LLM speed badges`() = runBlocking {
        // 1. Universal discovery marks arbitrary chat models as recommended with no hardcoded speed string
        val discovered = AiPipelineEngine.parseDiscoveredModelsResponse(
            respBody = """
                {
                  "data": [
                    {"id": "text-embedding-3-large"},
                    {"id": "doubao-seed-2-1-lite-260915"},
                    {"id": "brand-new-future-chat-model"}
                  ]
                }
            """.trimIndent(),
            slot = ModelCapabilitySlot.LLM
        )
        val byId = discovered.associateBy { it.id }
        assertFalse(byId.getValue("text-embedding-3-large").isRecommended)
        assertTrue(byId.getValue("doubao-seed-2-1-lite-260915").isRecommended)
        assertTrue(byId.getValue("brand-new-future-chat-model").isRecommended)
        assertEquals("", byId.getValue("doubao-seed-2-1-lite-260915").badge)

        // 2. Batch latency probing sends max_tokens=1 + thinkingMode and distinguishes 200 vs 403
        val streamedCallbacks = java.util.concurrent.CopyOnWriteArrayList<ModelProbeResult>()
        val probePayloads = java.util.concurrent.ConcurrentHashMap<String, String>()
        val mockProbeClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                if (req.method == "HEAD") {
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("".toResponseBody(null))
                        .build()
                }
                val buf = Buffer()
                req.body?.writeTo(buf)
                val bodyStr = buf.readUtf8()
                val modelId = JsonParser.parseString(bodyStr).asJsonObject.get("model").asString
                probePayloads[modelId] = bodyStr

                if (modelId == "unsubscribed-pro-model") {
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body("""{"error":{"message":"Model not activated"}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"choices":[{"message":{"role":"assistant","content":"1"}}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val engine = AiPipelineEngine(httpClient = mockProbeClient)
        val batchResults = engine.probeModelsBatch(
            slot = ModelCapabilitySlot.LLM,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            apiKey = "ark-test-key",
            modelIds = listOf("doubao-seed-2-1-lite-260915", "unsubscribed-pro-model"),
            thinkingMode = ThinkingMode.DISABLED,
            onEachResult = { streamedCallbacks.add(it) }
        )

        assertEquals(2, batchResults.size)
        assertEquals(2, streamedCallbacks.size)

        val okRes = batchResults.first { it.modelId == "doubao-seed-2-1-lite-260915" }
        assertTrue(okRes.isSuccess)
        assertTrue((okRes.latencyMs ?: 0L) >= 1L)
        assertTrue(probePayloads.getValue("doubao-seed-2-1-lite-260915").contains("\"max_tokens\":1"))
        assertTrue(probePayloads.getValue("doubao-seed-2-1-lite-260915").contains("\"reasoning_effort\":\"minimal\""))

        val forbiddenRes = batchResults.first { it.modelId == "unsubscribed-pro-model" }
        assertFalse(forbiddenRes.isSuccess)
        assertEquals("未开通/无权限 (403)", forbiddenRes.errorSummary)

        // 3. Config JSON round-trips thinkingMode for both LLM and Multimodal
        val settingsWithThinking = AppSettings(
            llmConfig = LlmConfig(thinkingMode = ThinkingMode.ENABLED),
            multimodalConfig = MultimodalConfig(thinkingMode = ThinkingMode.AUTO)
        )
        val json = ApiConfigBackupHelper.exportSettingsJson(settingsWithThinking)
        val restored = ApiConfigBackupHelper.parseAndMergeConfigJson(json, AppSettings()).getOrThrow()
        assertEquals(ThinkingMode.ENABLED, restored.llmConfig.thinkingMode)
        assertEquals(ThinkingMode.AUTO, restored.multimodalConfig.thinkingMode)
    }

    @Test
    fun `Volcengine ep endpoint cross-falls back from thinking_disabled to reasoning_effort_minimal and emits usage diagnostics`() = runBlocking {
        val sentPayloads = mutableListOf<String>()
        val mockArkSeed21Client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                val bodyStr = buf.readUtf8()
                sentPayloads.add(bodyStr)

                // Simulate Volcengine Ark Seed 2.1 endpoint rejecting legacy `thinking.type = disabled`
                if (bodyStr.contains("\"thinking\"")) {
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("Bad Request")
                        .body(
                            """{"error":{"message":"InvalidParameter: thinking.type 'disabled' is not supported by this model, use reasoning_effort"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                        {
                          "choices": [{"message": {"role": "assistant", "content": "明天下午三点半，跟王总对一下 Typeless API 接口方案。"}}],
                          "usage": {
                            "prompt_tokens": 186,
                            "prompt_tokens_details": {"cached_tokens": 128},
                            "completion_tokens": 22,
                            "completion_tokens_details": {"reasoning_tokens": 0}
                          }
                        }
                        """.trimIndent().toResponseBody("application/json".toMediaType())
                    )
                    .build()
            })
            .build()

        val engine = AiPipelineEngine(httpClient = mockArkSeed21Client)

        // Case A: Direct `doubao-seed-2-1-lite-260915` sends `reasoning_effort=minimal` on 1st try (zero 400 retry!)
        val directSettings = AppSettings(
            pipelineMode = PipelineMode.DUAL_STAGE,
            llmConfig = LlmConfig(
                enabled = true,
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                apiKey = "ark-key",
                model = "doubao-seed-2-1-lite-260915",
                thinkingMode = ThinkingMode.DISABLED
            )
        )
        val directRes = engine.runTextTransform(
            settings = directSettings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            rawOrSelectedText = "嗯明天下午三点半对一下方案",
            explicitInstruction = null,
            targetAppName = "API 实测台"
        ).getOrThrow()

        assertEquals(1, sentPayloads.size)
        assertTrue(sentPayloads.single().contains("\"reasoning_effort\":\"minimal\""))
        assertTrue(directRes.diagnosticLog.contains("reasoning_effort=minimal"))
        assertTrue(directRes.diagnosticLog.contains("reasoning_tokens=0"))
        assertTrue(directRes.diagnosticLog.contains("cache=128"))

        // Case B: Custom `ep-...` pointing to Seed 2.1 cross-falls back to `reasoning_effort=minimal` (never bare CoT!)
        sentPayloads.clear()
        val epSettings = directSettings.copy(
            llmConfig = directSettings.llmConfig.copy(model = "ep-20261106170000-seed21")
        )
        val epRes1 = engine.runTextTransform(
            settings = epSettings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            rawOrSelectedText = "第一次请求推理接入点",
            explicitInstruction = null,
            targetAppName = "API 实测台"
        ).getOrThrow()

        assertEquals(2, sentPayloads.size)
        assertTrue(sentPayloads[0].contains("\"thinking\""))
        assertTrue(sentPayloads[1].contains("\"reasoning_effort\":\"minimal\""))
        assertTrue(epRes1.diagnosticLog.contains("#1 [thinking.type=disabled"))
        assertTrue(epRes1.diagnosticLog.contains("#2 [reasoning_effort=minimal"))

        // Second request to the same `ep-...` uses cached `reasoning_effort=minimal` directly in 1 round-trip!
        sentPayloads.clear()
        engine.runTextTransform(
            settings = epSettings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            rawOrSelectedText = "第二次请求零重试",
            explicitInstruction = null,
            targetAppName = "API 实测台"
        ).getOrThrow()
        assertEquals(1, sentPayloads.size)
        assertTrue(sentPayloads.single().contains("\"reasoning_effort\":\"minimal\""))
    }
}
