package com.typefree.ime.jev

import android.view.inputmethod.InputConnection
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.typefree.ime.bubble.RawScreenTextNode
import com.typefree.ime.bubble.TypeFreeAccessibilityService
import com.typefree.ime.bubble.ViewportOcrExtractor
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.ProviderCredential
import com.typefree.ime.data.SecretVault
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.ThinkingMode
import com.typefree.ime.data.UnifiedProviderCatalog
import com.typefree.ime.ime.ImeHostCallbacks
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.UndoSnapshot
import com.typefree.ime.ime.VoiceController
import com.typefree.ime.ime.jev.JevCopilotResult
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.ime.jev.JevResponseParser
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.PromptBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class JevPerformanceAndDeepSeekConfigTest {

    private class FakeImeHost(
        override val aiEngine: AiPipelineEngine = AiPipelineEngine(),
        initialState: ImeUiState = ImeUiState()
    ) : ImeHostCallbacks {
        override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override val repository: AppRepository = AppRepository(
            appContext = null,
            publicStore = InMemoryKeyValueStore(),
            secretVault = SecretVault(
                store = InMemoryKeyValueStore(),
                cipher = JvmAesGcmCipher()
            )
        )
        override val stateFlow: MutableStateFlow<ImeUiState> = MutableStateFlow(initialState)

        override fun currentInputConnection(): InputConnection? = null
        override fun performKeyFeedback(strong: Boolean) {}
        override fun performHaptic(strong: Boolean) {}
        override fun showQuickActionToast(message: String) {}
        override fun commitDirectText(
            text: String,
            recordUndo: Boolean,
            markManualEdit: Boolean,
            playFeedback: Boolean
        ) {}
        override fun pushUndoSnapshot(snapshot: UndoSnapshot?) {}
        override fun peekTopUndoSnapshot(): UndoSnapshot? = null
        override fun recordManualEditTouch() {}
        override fun tryAutoLearnFromRecentEdit() {}
        override fun onDictationCommittedForAutoLearn(fullBufferAfterCommit: String) {}
        override fun clearAutoLearnBaseline() {}
        override fun readSelectedTextSafely(): String = ""
        override fun readSurroundingBeforeCursor(maxChars: Int): String = ""
        override fun readFullEditorTextSafely(): String = ""
        override fun readSystemClipboardText(): String = ""
        override fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean) {}
        override fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File = wavFile
        override fun getLastRecordingWavFile(): File = File("unused.wav")
        override fun hasRetryWavFile(): Boolean = false

        fun dispose() {
            scope.cancel()
        }
    }

    @Before
    fun setUp() {
        AiPipelineEngine.clearUnsupportedThinkingCache()
    }

    @After
    fun tearDown() {
        TypeFreeAccessibilityService.debugSimulatedNodesForTest = null
        TypeFreeAccessibilityService.debugSimulatedScreenshotBase64ForTest = null
        ViewportOcrExtractor.debugSimulatedOcrNodesForTest = null
        TypeFreeAccessibilityService.clearInputBoxTopHint()
    }

    @Test
    fun deepSeekQuickSetup_autoBindsVaultStt_preservesJevThinking_andClarifiesMissingSttError() = runBlocking {
        // 1. User configures ONLY DeepSeek key -> default 1-tap sets full-loop DeepSeek ASR + DeepSeek LLM + Jev synced!
        val onlyDeepSeek = UnifiedProviderCatalog.applyQuickProviderSetup(
            settings = UnifiedProviderCatalog.updateProviderCredential(
                AppSettings(),
                ProviderCredential(
                    providerId = UnifiedProviderCatalog.ID_DEEPSEEK,
                    apiKey = "sk-ds-only-123"
                )
            ),
            providerId = UnifiedProviderCatalog.ID_DEEPSEEK
        )
        assertEquals(PipelineMode.DUAL_STAGE, onlyDeepSeek.pipelineMode)
        assertEquals("deepseek_asr", onlyDeepSeek.sttConfig.presetId)
        assertEquals(SttProtocol.OPENAI_HTTP, onlyDeepSeek.sttConfig.protocol)
        assertEquals("https://api.deepseek.com/v1/tools/asr", onlyDeepSeek.sttConfig.baseUrl)
        assertEquals("sk-ds-only-123", onlyDeepSeek.sttConfig.apiKey)
        assertEquals("deepseek-asr", onlyDeepSeek.sttConfig.model)
        assertTrue(UnifiedProviderCatalog.hasConfiguredSttCredential(onlyDeepSeek.sttConfig))
        assertEquals("deepseek-chat", onlyDeepSeek.llmConfig.model)
        assertEquals("sk-ds-only-123", onlyDeepSeek.llmConfig.apiKey)
        assertEquals("sk-ds-only-123", onlyDeepSeek.jevLlmConfig.apiKey)
        assertEquals("deepseek-chat", onlyDeepSeek.jevLlmConfig.model)

        // When user explicitly kept an unconfigured third-party STT slot -> clear missing STT error message!
        val missingThirdPartyStt = UnifiedProviderCatalog.applyQuickProviderSetup(
            settings = UnifiedProviderCatalog.updateProviderCredential(
                AppSettings(),
                ProviderCredential(
                    providerId = UnifiedProviderCatalog.ID_DEEPSEEK,
                    apiKey = "sk-ds-only-123"
                )
            ),
            providerId = UnifiedProviderCatalog.ID_DEEPSEEK,
            keepExistingThirdPartyStt = true
        )
        assertFalse(UnifiedProviderCatalog.hasConfiguredSttCredential(missingThirdPartyStt.sttConfig))

        val dummyWav = File.createTempFile("ds_missing_stt_", ".wav").apply {
            writeBytes(ByteArray(128) { 1 })
            deleteOnExit()
        }
        val engine = AiPipelineEngine()
        val voiceErr = engine.processVoiceAudio(
            wavFile = dummyWav,
            settings = missingThirdPartyStt,
            activeStyle = DefaultPresets.defaultStyles().first(),
            targetAppName = "微信",
            selectedText = "",
            surroundingBeforeCursor = ""
        ).exceptionOrNull()
        assertNotNull(voiceErr)
        assertTrue(
            "Missing STT with DeepSeek must explain Stage-1 vs Stage-2 clearly: ${voiceErr!!.message}",
            voiceErr.message!!.contains("deepseek-chat") && voiceErr.message!!.contains("一阶段")
        )

        // 2. User already stored SiliconFlow key in Vault, then activates DeepSeek with keepExistingThirdPartyStt=true ->
        //    auto-binds SiliconFlow FunAudioLLM/SenseVoiceSmall for Stage-1 STT!
        val withSiliconFlowAndDeepSeek = UnifiedProviderCatalog.applyQuickProviderSetup(
            settings = missingThirdPartyStt.copy(
                providerVault = missingThirdPartyStt.providerVault + (
                    UnifiedProviderCatalog.ID_SILICONFLOW to ProviderCredential(
                        providerId = UnifiedProviderCatalog.ID_SILICONFLOW,
                        apiKey = "sk-sf-stt-999"
                    )
                )
            ),
            providerId = UnifiedProviderCatalog.ID_DEEPSEEK,
            keepExistingThirdPartyStt = true
        )
        assertEquals(SttProtocol.OPENAI_HTTP, withSiliconFlowAndDeepSeek.sttConfig.protocol)
        assertEquals("FunAudioLLM/SenseVoiceSmall", withSiliconFlowAndDeepSeek.sttConfig.model)
        assertEquals("sk-sf-stt-999", withSiliconFlowAndDeepSeek.sttConfig.apiKey)
        assertTrue(UnifiedProviderCatalog.hasConfiguredSttCredential(withSiliconFlowAndDeepSeek.sttConfig))
    }

    @Test
    fun jevThinkingControl_preservesBoundedThinkingAcrossGeminiAndDeepSeek_withoutBreakingDefaultAuto() {
        // 1. Default non-Jev AUTO remains null on gemini-3.8-flash for Stage-2 dictation speed
        val defaultNonJevAuto = AiPipelineEngine.buildGeminiGenerationConfig(
            model = "gemini-3.8-flash",
            temperature = 0.2f,
            thinkingMode = ThinkingMode.AUTO,
            forJevCopilot = false
        )
        assertNull(defaultNonJevAuto)

        // 2. Explicit DISABLED on gemini-2.5-flash now sends thinkingBudget=0 cleanly
        val gemini25Disabled = AiPipelineEngine.buildGeminiGenerationConfig(
            model = "gemini-2.5-flash",
            temperature = 0.2f,
            thinkingMode = ThinkingMode.DISABLED,
            forJevCopilot = false
        )!!
        assertEquals(0, gemini25Disabled.getAsJsonObject("thinkingConfig").get("thinkingBudget").asInt)

        // 3. Jev AUTO preserves real thinking with generous 1536 maxOutputTokens across 2.5/3.8/4-hydrogen
        val jev25 = AiPipelineEngine.buildGeminiGenerationConfig(
            model = "gemini-2.5-flash",
            temperature = 0.35f,
            thinkingMode = ThinkingMode.AUTO,
            forJevCopilot = true,
            maxOutputTokens = 1536
        )!!
        assertEquals(1536, jev25.get("maxOutputTokens").asInt)
        assertEquals(256, jev25.getAsJsonObject("thinkingConfig").get("thinkingBudget").asInt)

        val jev38 = AiPipelineEngine.buildGeminiGenerationConfig(
            model = "gemini-3.8-flash",
            temperature = 0.35f,
            thinkingMode = ThinkingMode.AUTO,
            forJevCopilot = true,
            maxOutputTokens = 1536
        )!!
        assertEquals(1536, jev38.get("maxOutputTokens").asInt)
        assertEquals("MEDIUM", jev38.getAsJsonObject("thinkingConfig").get("thinkingLevel").asString)

        val jev4 = AiPipelineEngine.buildGeminiGenerationConfig(
            model = "gemini-4-hydrogen",
            temperature = 0.35f,
            thinkingMode = ThinkingMode.AUTO,
            forJevCopilot = true,
            maxOutputTokens = 1536
        )!!
        assertEquals(1536, jev4.get("maxOutputTokens").asInt)
        assertEquals("MEDIUM", jev4.getAsJsonObject("thinkingConfig").get("thinkingLevel").asString)

        // 4. OpenAI-compatible Jev AUTO enables thinking for DeepSeek & Doubao Seed 2.1 while keeping concise CoT rule
        val dsJevBody = JsonObject()
        assertTrue(
            AiPipelineEngine.applyThinkingControl(
                body = dsJevBody,
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-chat",
                thinkingMode = ThinkingMode.AUTO,
                forJevCopilot = true
            )
        )
        assertEquals("enabled", dsJevBody.getAsJsonObject("thinking").get("type").asString)

        val arkJevBody = JsonObject()
        assertTrue(
            AiPipelineEngine.applyThinkingControl(
                body = arkJevBody,
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                model = "doubao-seed-2-1-pro-260915",
                thinkingMode = ThinkingMode.AUTO,
                forJevCopilot = true
            )
        )
        assertEquals("medium", arkJevBody.get("reasoning_effort").asString)

        assertTrue(PromptBuilder.JEV_COPILOT_SYSTEM_PROMPT.contains("极速精炼快思纪律"))
    }

    @Test
    fun jevStreamingProgressiveParser_andSseEngine_emitThinkingSubtextAndIncrementalCards() = runBlocking {
        // 1. Parser ignores unclosed <think>, emits Subtext early, then 1..3 cards, then sorts on <weights>
        assertNull(JevResponseParser.parseStreamingPreview("<think>先分析局势"))

        val afterSubtext = JevResponseParser.parseStreamingPreview(
            "<jev><lv>6</lv><tag>试探边界</tag><subtext>对方嘴上客气，实则在测试你的底线</subtext><opt label=\"策略"
        )
        assertNotNull(afterSubtext)
        assertEquals(6, afterSubtext!!.clampedLevel)
        assertEquals("试探边界", afterSubtext.tag)
        assertEquals("对方嘴上客气，实则在测试你的底线", afterSubtext.subtext)
        assertTrue(afterSubtext.options.isEmpty())

        val afterOpt1 = JevResponseParser.parseStreamingPreview(
            "<jev><lv>6</lv><tag>试探边界</tag><subtext>对方嘴上客气，实则在测试你的底线</subtext>" +
                "<opt label=\"温和顺接\">这周比较紧，下周三对齐</opt><opt label=\"直截"
        )!!
        assertEquals(1, afterOpt1.options.size)
        assertEquals("温和顺接", afterOpt1.options[0].label)

        val afterWeights = JevResponseParser.parseStreamingPreview(
            "<jev><lv>6</lv><tag>试探边界</tag><subtext>对方嘴上客气，实则在测试你的底线</subtext>" +
                "<opt label=\"温和顺接\">这周比较紧，下周三对齐</opt>" +
                "<opt label=\"体面守界\">这周排期已满，下周二后再看</opt>" +
                "<opt label=\"反客为主\">把核心需求先发我评估下优先级</opt>" +
                "<weights>22,58,20</weights></jev>"
        )!!
        assertEquals(3, afterWeights.options.size)
        assertEquals("体面守界", afterWeights.options[0].label)
        assertEquals(58, afterWeights.options[0].probability)

        // 2. End-to-end DeepSeek SSE + Gemini SSE streaming via AiPipelineEngine.runJevCopilot
        val recordedBodies = mutableListOf<String>()
        val mockSseClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                val reqBody = buf.readUtf8()
                recordedBodies.add("${req.url}::$reqBody")

                val ssePayload = if (req.url.encodedPath.contains(":streamGenerateContent")) {
                    listOf(
                        """data: {"candidates":[{"content":{"parts":[{"text":"①私聊试探 ②切入三策","thought":true}]}}]}""",
                        """data: {"candidates":[{"content":{"parts":[{"text":"<jev><lv>5</lv><tag>隐性试探</tag><subtext>对方在试探你周末是否有空</subtext>"}]}}]}""",
                        """data: {"candidates":[{"content":{"parts":[{"text":"<opt label=\"顺水给梯\">这周末行，周六下午碰一下</opt>"}]}}]}""",
                        """data: {"candidates":[{"content":{"parts":[{"text":"<opt label=\"留白设限\">周六白天有事，周日傍晚行不行</opt>"}]}}]}""",
                        """data: {"candidates":[{"content":{"parts":[{"text":"<opt label=\"反问探路\">你想聊哪个点，先发我瞅瞅</opt><weights>28,53,19</weights></jev>"}]}}]}"""
                    ).joinToString("\n\n", postfix = "\n\n")
                } else {
                    listOf(
                        """data: {"choices":[{"delta":{"reasoning_content":"快速定位：1v1私聊拉扯"}}]}""",
                        """data: {"choices":[{"delta":{"content":"<jev><lv>5</lv><tag>隐性试探</tag><subtext>对方在试探你周末是否有空</subtext>"}}]}""",
                        """data: {"choices":[{"delta":{"content":"<opt label=\"顺水给梯\">这周末行，周六下午碰一下</opt>"}}]}""",
                        """data: {"choices":[{"delta":{"content":"<opt label=\"留白设限\">周六白天有事，周日傍晚行不行</opt>"}}]}""",
                        """data: {"choices":[{"delta":{"content":"<opt label=\"反问探路\">你想聊哪个点，先发我瞅瞅</opt><weights>28,53,19</weights></jev>"}}]}""",
                        "data: [DONE]"
                    ).joinToString("\n\n", postfix = "\n\n")
                }

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/event-stream")
                    .body(ssePayload.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            })
            .build()

        val engine = AiPipelineEngine(httpClient = mockSseClient)

        // DeepSeek SSE (shared global LLM with DISABLED auto-promoted to AUTO for Jev!)
        val dsSettings = UnifiedProviderCatalog.applyQuickProviderSetup(
            settings = UnifiedProviderCatalog.updateProviderCredential(
                AppSettings(jevCopilotEnabled = true, jevUseDedicatedModel = false),
                ProviderCredential(providerId = UnifiedProviderCatalog.ID_DEEPSEEK, apiKey = "sk-ds-sse")
            ),
            providerId = UnifiedProviderCatalog.ID_DEEPSEEK
        )
        val dsThinkingCount = AtomicInteger(0)
        val dsPartials = mutableListOf<JevCopilotResult>()
        val dsFinal = engine.runJevCopilot(
            settings = dsSettings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            domainProfile = JevDomainProfile.IM_CHAT,
            chatContextMessages = listOf("[对方] 周末有空聊聊吗"),
            onThinkingStarted = { dsThinkingCount.incrementAndGet() },
            onPartialResult = { dsPartials.add(it) }
        ).getOrThrow().first

        assertEquals(1, dsThinkingCount.get())
        assertTrue("Progressive updates must emit 0 -> 1 -> 2 -> 3 cards", dsPartials.map { it.options.size }.containsAll(listOf(0, 1, 2, 3)))
        assertEquals("留白设限", dsFinal.options[0].label)
        assertEquals(53, dsFinal.options[0].probability)
        val dsReqJson = JsonParser.parseString(recordedBodies.last().substringAfter("::")).asJsonObject
        assertTrue(dsReqJson.get("stream").asBoolean)
        assertEquals("enabled", dsReqJson.getAsJsonObject("thinking").get("type").asString)

        // Gemini SSE
        val geminiSettings = AppSettings(
            jevCopilotEnabled = true,
            jevUseDedicatedModel = true,
            jevLlmConfig = LlmConfig(
                enabled = true,
                presetId = "gemini_llm",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "AIza-sse-test",
                model = "gemini-3.8-flash",
                thinkingMode = ThinkingMode.AUTO
            )
        )
        val gemThinkingCount = AtomicInteger(0)
        val gemPartials = mutableListOf<JevCopilotResult>()
        val gemFinal = engine.runJevCopilot(
            settings = geminiSettings,
            activeStyle = DefaultPresets.defaultStyles().first(),
            domainProfile = JevDomainProfile.IM_CHAT,
            chatContextMessages = listOf("[对方] 周末有空聊聊吗"),
            onThinkingStarted = { gemThinkingCount.incrementAndGet() },
            onPartialResult = { gemPartials.add(it) }
        ).getOrThrow().first

        assertEquals(1, gemThinkingCount.get())
        assertTrue(gemPartials.map { it.options.size }.containsAll(listOf(0, 1, 2, 3)))
        assertEquals("留白设限", gemFinal.options[0].label)
        assertTrue(recordedBodies.last().substringBefore("::").contains(":streamGenerateContent?alt=sse"))
        val gemReqJson = JsonParser.parseString(recordedBodies.last().substringAfter("::")).asJsonObject
        val genCfg = gemReqJson.getAsJsonObject("generationConfig")
        assertEquals(1536, genCfg.get("maxOutputTokens").asInt)
        assertEquals("MEDIUM", genCfg.getAsJsonObject("thinkingConfig").get("thinkingLevel").asString)
    }

    @Test
    fun voiceController_skipsRedundantScreenshotWhenJevUsesPureTextDeepSeek() = runBlocking {
        val chatNodes = (1..4).flatMap { i ->
            val top = 360 + i * 160
            listOf(
                RawScreenTextNode("好友A", top, top + 34, 160, 280),
                RawScreenTextNode("第${i}轮聊天消息内容用于验证无需截图OCR直出", top + 44, top + 112, 160, 820)
            )
        }
        TypeFreeAccessibilityService.debugSimulatedNodesForTest = "com.tencent.mm" to chatNodes
        TypeFreeAccessibilityService.debugSimulatedScreenshotBase64ForTest = "SHOULD_NOT_BE_ATTACHED_JPEG"

        val capturedBodies = mutableListOf<String>()
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                capturedBodies.add(buf.readUtf8())
                val sse = listOf(
                    """data: {"choices":[{"delta":{"reasoning_content":"私聊直接拆解"}}]}""",
                    """data: {"choices":[{"delta":{"content":"<jev><lv>3</lv><tag>正常交流</tag><subtext>普通约见沟通</subtext><opt label=\"爽快接招\">行呀，时间你定</opt><opt label=\"弹性微调\">周六下午我都可以</opt><opt label=\"顺便确认\">地点就定在公司楼下吧</opt><weights>48,32,20</weights></jev>"}}]}""",
                    "data: [DONE]"
                ).joinToString("\n\n", postfix = "\n\n")
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/event-stream")
                    .body(sse.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            })
            .build()

        val host = FakeImeHost(
            aiEngine = AiPipelineEngine(httpClient = mockHttp),
            initialState = ImeUiState(targetPackage = "com.tencent.mm", targetAppName = "微信")
        )
        try {
            host.repository.updateSettings {
                it.copy(
                    jevCopilotEnabled = true,
                    chatContextReplyEnabled = true,
                    chatContextDisclosureAccepted = true,
                    chatContextSendScreenshot = true,
                    jevUseDedicatedModel = true,
                    jevLlmConfig = LlmConfig(
                        enabled = true,
                        presetId = "deepseek",
                        baseUrl = "https://api.deepseek.com/v1",
                        apiKey = "sk-ds-puretext",
                        model = "deepseek-chat",
                        thinkingMode = ThinkingMode.AUTO
                    )
                )
            }
            val controller = VoiceController(
                host = host,
                audioRecorderProvider = { throw UnsupportedOperationException() },
                onExternalCommitToEngine = {},
                onResetComposing = {}
            )
            controller.openJevCopilotPanel()
            repeat(50) {
                if (!host.stateFlow.value.isJevLoading) return@repeat
                delay(15L)
            }
            val state = host.stateFlow.value
            assertFalse(state.isJevLoading)
            assertFalse(state.isJevThinking)
            assertNotNull(state.jevCopilotResult)
            assertFalse(state.jevCopilotResult!!.usedVision)
            assertEquals("", state.capturedChatContext.screenshotJpegBase64)
            assertEquals(1, capturedBodies.size)
            assertFalse(capturedBodies.first().contains("SHOULD_NOT_BE_ATTACHED_JPEG"))
            assertFalse(capturedBodies.first().contains("<chat_screen_vision_guide>"))
        } finally {
            host.dispose()
        }
    }
}
