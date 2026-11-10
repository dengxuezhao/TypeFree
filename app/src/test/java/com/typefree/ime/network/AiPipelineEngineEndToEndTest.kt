package com.typefree.ime.network

import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.ModelCapabilitySlot
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SttConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AiPipelineEngineEndToEndTest {

    private lateinit var tempWav: File
    private val capturedRequests = mutableListOf<Pair<Request, String>>()

    private val mockClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val bodyBuf = Buffer()
                req.body?.writeTo(bodyBuf)
                val bodyString = bodyBuf.readUtf8()
                synchronized(capturedRequests) {
                    capturedRequests.add(req to bodyString)
                }

                val path = req.url.encodedPath
                val responseJson = when {
                    path.endsWith("/audio/transcriptions") -> {
                        """{"text":"<|zh|><|NEUTRAL|>嗯那个明天下午两点不对三点半跟王总同步太普利斯方案"}"""
                    }
                    path.endsWith("/chat/completions") -> {
                        val reply = if (bodyString.contains("<selected_text>")) {
                            "Could we sync on the TypeFree API plan at 3:30 PM tomorrow?"
                        } else {
                            "明天下午三点半跟王总同步 TypeFree 方案。"
                        }
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "$reply"
                              }
                            }
                          ]
                        }
                        """.trimIndent()
                    }
                    path.contains(":generateContent") -> {
                        // Simulate legacy gateway rejecting thinkingConfig when host is legacy-proxy.example.com
                        if (req.url.host == "legacy-proxy.example.com" && bodyString.contains("thinkingConfig")) {
                            return@Interceptor Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(400)
                                .message("Bad Request")
                                .body("""{"error":{"message":"Unknown field thinkingConfig"}}""".toResponseBody("application/json".toMediaType()))
                                .build()
                        }
                        """
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  { "text": "已通过 Gemini 多模态一步整理完毕。" }
                                ]
                              }
                            }
                          ]
                        }
                        """.trimIndent()
                    }
                    path == "/v1beta/models" -> {
                        if (req.url.host == "newapi-v1-only.example.com") {
                            return@Interceptor Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(404)
                                .message("Not Found")
                                .body("""{"error":{"message":"Route not found"}}""".toResponseBody("application/json".toMediaType()))
                                .build()
                        }
                        """
                        {
                          "models": [
                            {
                              "name": "models/gemini-3.5-flash-lite",
                              "displayName": "Gemini 3.5 Flash-Lite",
                              "supportedGenerationMethods": ["generateContent"]
                            },
                            {
                              "name": "models/gemini-3.8-flash",
                              "displayName": "Gemini 3.8 Flash",
                              "supportedGenerationMethods": ["generateContent"]
                            },
                            {
                              "name": "models/text-embedding-004",
                              "displayName": "Text Embedding 004",
                              "supportedGenerationMethods": ["embedContent"]
                            }
                          ]
                        }
                        """.trimIndent()
                    }
                    path.endsWith("/models") -> {
                        """
                        {
                          "object": "list",
                          "data": [
                            { "id": "text-embedding-3-small", "owned_by": "openai" },
                            { "id": "gemini-3.8-flash", "owned_by": "vertex-ai" },
                            { "id": "gemini-3.5-flash-lite", "owned_by": "vertex-ai" },
                            { "id": "deepseek-chat", "owned_by": "deepseek" }
                          ]
                        }
                        """.trimIndent()
                    }
                    else -> """{"error":{"message":"Unknown mock path: $path"}}"""
                }

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    @Before
    fun setUp() {
        capturedRequests.clear()
        tempWav = File.createTempFile("typefree_e2e_", ".wav").apply {
            writeBytes(ByteArray(128) { 1 })
            deleteOnExit()
        }
    }

    @After
    fun tearDown() {
        tempWav.delete()
    }

    @Test
    fun `dual-stage pipeline executes multipart STT upload and LLM polish with auth headers`() = runBlocking {
        val engine = AiPipelineEngine(httpClient = mockClient)
        val stages = mutableListOf<PipelineStage>()
        val settings = AppSettings(
            pipelineMode = PipelineMode.DUAL_STAGE,
            sttConfig = SttConfig(
                baseUrl = "https://api.siliconflow.cn/v1",
                apiKey = "sf-test-key",
                model = "FunAudioLLM/SenseVoiceSmall"
            ),
            llmConfig = LlmConfig(
                enabled = true,
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "ds-test-key",
                model = "deepseek-chat"
            )
        )
        val style = DefaultPresets.defaultStyles().first()

        val result = engine.processVoiceAudio(
            wavFile = tempWav,
            settings = settings,
            activeStyle = style,
            targetAppName = "飞书",
            selectedText = "",
            surroundingBeforeCursor = "",
            onStageChanged = { stages.add(it) }
        ).getOrThrow()

        assertEquals(
            listOf(PipelineStage.TRANSCRIBING_STT, PipelineStage.POLISHING_LLM),
            stages
        )
        assertEquals("嗯那个明天下午两点不对三点半跟王总同步太普利斯方案", result.rawTranscript)
        assertEquals("明天下午三点半跟王总同步 TypeFree 方案。", result.finalOutput)

        assertEquals(2, capturedRequests.size)
        assertEquals("Bearer sf-test-key", capturedRequests[0].first.header("Authorization"))
        assertTrue(capturedRequests[0].second.contains("FunAudioLLM/SenseVoiceSmall"))
        assertEquals("Bearer ds-test-key", capturedRequests[1].first.header("Authorization"))
        assertTrue(capturedRequests[1].second.contains("TypeFree"))
    }

    @Test
    fun `dual-stage Speak-to-Edit invokes LLM transform even when dictation polish toggle is off`() = runBlocking {
        val engine = AiPipelineEngine(httpClient = mockClient)
        val settings = AppSettings(
            pipelineMode = PipelineMode.DUAL_STAGE,
            sttConfig = SttConfig(baseUrl = "https://api.groq.com/openai/v1", apiKey = "gsk-key", model = "whisper-large-v3-turbo"),
            llmConfig = LlmConfig(enabled = false, baseUrl = "https://api.openai.com/v1", apiKey = "sk-key", model = "gpt-4o-mini")
        )
        val style = DefaultPresets.defaultStyles().first()

        val result = engine.processVoiceAudio(
            wavFile = tempWav,
            settings = settings,
            activeStyle = style,
            targetAppName = "Gmail",
            selectedText = "明天下午三点半对一下方案",
            surroundingBeforeCursor = ""
        ).getOrThrow()

        assertEquals(
            "Could we sync on the TypeFree API plan at 3:30 PM tomorrow?",
            result.finalOutput
        )
        assertEquals(2, capturedRequests.size)
        assertTrue(capturedRequests[1].second.contains("<selected_text>"))
    }

    @Test
    fun `multimodal Gemini direct pipeline sends inline base64 audio wav and x-goog-api-key`() = runBlocking {
        val engine = AiPipelineEngine(httpClient = mockClient)
        val stages = mutableListOf<PipelineStage>()
        val settings = AppSettings(
            pipelineMode = PipelineMode.MULTIMODAL_DIRECT,
            multimodalConfig = MultimodalConfig(
                protocol = MultimodalProtocol.GEMINI_NATIVE,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "AIzaMockKey",
                model = "gemini-2.5-flash"
            )
        )
        val style = DefaultPresets.defaultStyles().first()

        val result = engine.processVoiceAudio(
            wavFile = tempWav,
            settings = settings,
            activeStyle = style,
            targetAppName = "微信",
            selectedText = "",
            surroundingBeforeCursor = "",
            onStageChanged = { stages.add(it) }
        ).getOrThrow()

        assertEquals(listOf(PipelineStage.MULTIMODAL_PROCESSING), stages)
        assertEquals("已通过 Gemini 多模态一步整理完毕。", result.finalOutput)
        assertEquals(1, capturedRequests.size)
        assertEquals("AIzaMockKey", capturedRequests[0].first.header("x-goog-api-key"))
        assertTrue(capturedRequests[0].second.contains("\"mime_type\":\"audio/wav\""))
        assertTrue(capturedRequests[0].second.contains("\"temperature\":0.2"))
    }

    @Test
    fun `Gemini 3_5 and 3_8 adaptive parameters omit temperature, set LOW thinking, and fallback on legacy proxy`() = runBlocking {
        val engine = AiPipelineEngine(httpClient = mockClient)
        val style = DefaultPresets.defaultStyles().first()

        // 1. gemini-3.5-flash-lite omits temperature and explicitly sets thinkingLevel=MINIMAL
        val settings35Lite = AppSettings(
            pipelineMode = PipelineMode.MULTIMODAL_DIRECT,
            multimodalConfig = MultimodalConfig(
                protocol = MultimodalProtocol.GEMINI_NATIVE,
                baseUrl = "https://my-newapi.example.com/v1beta",
                apiKey = "sk-newapi-token",
                model = "gemini-3.5-flash-lite",
                temperature = 0.25f
            )
        )
        engine.processVoiceAudio(tempWav, settings35Lite, style, "微信", "", "").getOrThrow()
        val body35 = capturedRequests.last().second
        assertFalse(body35.contains("temperature"))
        assertTrue(body35.contains("\"thinkingLevel\":\"MINIMAL\""))

        // 2. gemini-3.8-flash omits temperature and sets thinkingLevel=LOW
        val settings38 = AppSettings(
            pipelineMode = PipelineMode.MULTIMODAL_DIRECT,
            multimodalConfig = MultimodalConfig(
                protocol = MultimodalProtocol.GEMINI_NATIVE,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "AIzaKey",
                model = "gemini-3.8-flash",
                temperature = 0.2f
            )
        )
        engine.processVoiceAudio(tempWav, settings38, style, "飞书", "", "").getOrThrow()
        val body38 = capturedRequests.last().second
        assertFalse(body38.contains("temperature"))
        assertTrue(body38.contains("\"thinkingLevel\":\"LOW\""))

        // 3. Legacy gateway rejecting thinkingConfig automatically falls back cleanly
        capturedRequests.clear()
        val settingsLegacyProxy = settings38.copy(
            multimodalConfig = settings38.multimodalConfig.copy(
                baseUrl = "https://legacy-proxy.example.com/v1beta",
                apiKey = "sk-legacy"
            )
        )
        val fallbackRes = engine.processVoiceAudio(tempWav, settingsLegacyProxy, style, "飞书", "", "").getOrThrow()
        assertEquals("已通过 Gemini 多模态一步整理完毕。", fallbackRes.finalOutput)
        assertEquals(2, capturedRequests.size)
        assertTrue(capturedRequests[0].second.contains("thinkingConfig"))
        assertFalse(capturedRequests[1].second.contains("generationConfig"))
    }

    @Test
    fun `universal fetchAvailableModels supports Gemini native, OpenAI compatible, and NewAPI v1 fallback`() = runBlocking {
        val engine = AiPipelineEngine(httpClient = mockClient)

        // 1. Official Gemini /v1beta/models strips models/ prefix and filters out embedContent-only models
        val geminiList = engine.fetchAvailableModels(
            slot = ModelCapabilitySlot.MULTIMODAL,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "AIzaTest",
            protocol = MultimodalProtocol.GEMINI_NATIVE
        ).getOrThrow()
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.8-flash"), geminiList.map { it.id })
        assertTrue(geminiList.all { it.isRecommended })

        // 2. NewAPI gateway where /v1beta/models returns 404 automatically falls back to /v1/models with Bearer auth
        capturedRequests.clear()
        val gatewayList = engine.fetchAvailableModels(
            slot = ModelCapabilitySlot.MULTIMODAL,
            baseUrl = "https://newapi-v1-only.example.com/v1beta",
            apiKey = "sk-gateway-123",
            protocol = MultimodalProtocol.GEMINI_NATIVE
        ).getOrThrow()
        assertEquals("gemini-3.5-flash-lite", gatewayList.first().id)
        assertTrue(gatewayList.any { it.id == "gemini-3.8-flash" })
        assertEquals("Bearer sk-gateway-123", capturedRequests.last().first.header("Authorization"))
    }
}
