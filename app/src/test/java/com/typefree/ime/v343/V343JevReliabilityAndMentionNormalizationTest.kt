package com.typefree.ime.v343

import android.view.inputmethod.InputConnection
import com.typefree.ime.bubble.ChatScreenContextExtractor
import com.typefree.ime.bubble.RawScreenTextNode
import com.typefree.ime.bubble.TypeFreeAccessibilityService
import com.typefree.ime.bubble.ViewportOcrExtractor
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SecretVault
import com.typefree.ime.data.ThinkingMode
import com.typefree.ime.ime.ImeHostCallbacks
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.UndoSnapshot
import com.typefree.ime.ime.VoiceController
import com.typefree.ime.ime.VoiceSubPanel
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.ime.jev.JevRoleMode
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
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class V343JevReliabilityAndMentionNormalizationTest {

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "app/src/main").exists()) {
            cwd
        } else {
            cwd.parentFile ?: cwd
        }
    }

    @After
    fun tearDown() {
        TypeFreeAccessibilityService.debugSimulatedNodesForTest = null
        TypeFreeAccessibilityService.debugSimulatedScreenshotBase64ForTest = null
        ViewportOcrExtractor.debugSimulatedOcrNodesForTest = null
        TypeFreeAccessibilityService.clearInputBoxTopHint()
        AiPipelineEngine.clearUnsupportedThinkingCache()
    }

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
        val toasts = mutableListOf<String>()

        override fun currentInputConnection(): InputConnection? = null
        override fun performKeyFeedback(strong: Boolean) {}
        override fun performHaptic(strong: Boolean) {}
        override fun showQuickActionToast(message: String) {
            toasts.add(message)
        }
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

    @Test
    fun realDeviceScreenshot4ctn_qqGroupLongNicknameMentionAndQuote_stripsLiteralHandleSemantics() {
        // Exact geometric reproduction of screenshot `4ctn.png` (`工具软件爱好者折腾群-综合讨论(1971)` on 1080x2400):
        // 1. Group owner (`我一直在寻找，有下一顿饭钱的世界在哪里`) posted a screenshot image above (standalone header row)
        // 2. Member `terwer` sent: `@我一直在寻找，有下一顿饭钱的世界在哪里 这是什么，嗯干啥`
        // 3. Group owner quoted `terwer 13:49` and replied: `@terwer 用来对接dsh和思源的啊`
        val nodes = listOf(
            RawScreenTextNode(
                text = "工具软件爱好者折腾群-综合讨论(1971)",
                boundsTop = 132,
                boundsBottom = 188,
                boundsLeft = 180,
                boundsRight = 900,
                className = "android.widget.TextView"
            ),
            // Standalone role-badge + long nickname above an image bubble (no text node paired directly below)
            RawScreenTextNode(
                text = "[群主] 我一直在寻找，有下一顿饭钱的世界在哪里",
                boundsTop = 340,
                boundsBottom = 378,
                boundsLeft = 210,
                boundsRight = 890,
                className = "android.widget.TextView"
            ),
            // Member `terwer` avatar + header + `@群主` question bubble
            RawScreenTextNode(
                text = "",
                boundsTop = 1190,
                boundsBottom = 1286,
                boundsLeft = 40,
                boundsRight = 136,
                className = "android.widget.ImageView"
            ),
            RawScreenTextNode(
                text = "terwer",
                boundsTop = 1194,
                boundsBottom = 1230,
                boundsLeft = 162,
                boundsRight = 286,
                className = "android.widget.TextView"
            ),
            RawScreenTextNode(
                text = "@我一直在寻找，有下一顿饭钱的世界在哪里 这是什么，嗯干啥",
                boundsTop = 1244,
                boundsBottom = 1352,
                boundsLeft = 162,
                boundsRight = 860,
                className = "android.widget.TextView"
            ),
            // Group owner's reply header + gray quoted sender/time + quoted body + actual reply
            RawScreenTextNode(
                text = "",
                boundsTop = 1420,
                boundsBottom = 1516,
                boundsLeft = 40,
                boundsRight = 136,
                className = "android.widget.ImageView"
            ),
            RawScreenTextNode(
                text = "群主",
                boundsTop = 1424,
                boundsBottom = 1458,
                boundsLeft = 162,
                boundsRight = 228,
                className = "android.widget.TextView"
            ),
            RawScreenTextNode(
                text = "我一直在寻找，有下一顿饭钱的世界在哪里",
                boundsTop = 1424,
                boundsBottom = 1458,
                boundsLeft = 236,
                boundsRight = 888,
                className = "android.widget.TextView"
            ),
            RawScreenTextNode(
                text = "terwer 13:49",
                boundsTop = 1476,
                boundsBottom = 1510,
                boundsLeft = 184,
                boundsRight = 380,
                className = "android.widget.TextView"
            ),
            RawScreenTextNode(
                text = "@我一直在寻找，有下一顿饭钱的世界在哪里 这是什么，嗯干啥",
                boundsTop = 1518,
                boundsBottom = 1602,
                boundsLeft = 184,
                boundsRight = 830,
                className = "android.widget.TextView"
            ),
            RawScreenTextNode(
                text = "@terwer 用来对接dsh和思源的啊",
                boundsTop = 1620,
                boundsBottom = 1692,
                boundsLeft = 162,
                boundsRight = 720,
                className = "android.widget.TextView"
            )
        )

        val ctx = ChatScreenContextExtractor.extractChatContext(
            nodes = nodes,
            screenWidth = 1080,
            screenHeight = 2400,
            inputBoxTop = 1820,
            packageName = "com.tencent.mobileqq",
            appName = "QQ",
            maxMessages = 10,
            maxTotalChars = 800,
            redactPii = false
        )

        assertTrue(ctx.topology.isGroupChat)
        assertEquals(1971, ctx.topology.groupMemberCount)
        assertFalse(
            "`@我一直在寻找...` must NEVER falsely trigger `@我` mention detection",
            ctx.topology.hasExplicitMentionMe
        )
        assertEquals(JevRoleMode.GROUP_BYSTANDER, ctx.topology.roleMode)

        val formattedJoined = ctx.formattedMessages.joinToString("\n")
        assertFalse(
            "Long sentence-like @nickname must be completely stripped from formatted context: $formattedJoined",
            formattedJoined.contains("寻找") || formattedJoined.contains("饭钱") || formattedJoined.contains("下一顿")
        )
        assertFalse(
            "Quoted reply sender+time header `terwer 13:49` must be filtered out: $formattedJoined",
            formattedJoined.contains("13:49")
        )
        assertTrue(
            "Expected terwer's message to replace @owner with symbolic [@群友B]: $formattedJoined",
            ctx.formattedMessages.any { it == "[群友A(terwer)] [@群友B] 这是什么，嗯干啥" }
        )
        assertTrue(
            "Expected owner's reply to replace @terwer with symbolic [@群友A]: $formattedJoined",
            ctx.formattedMessages.any { it.endsWith("[@群友A] 用来对接dsh和思源的啊") }
        )

        val (_, userPrompt) = PromptBuilder.buildJevCopilotPrompts(
            domainProfile = JevDomainProfile.IM_CHAT,
            targetAppName = "QQ",
            chatContextMessages = ctx.formattedMessages,
            hasChatScreenshot = true,
            chatTopology = ctx.topology,
            targetPackageName = "com.tencent.mobileqq"
        )
        assertFalse(userPrompt.contains("下一顿饭钱"))
        assertTrue(userPrompt.contains("[@群友B] 这是什么，嗯干啥"))
    }

    @Test
    fun normalizeGroupMentions_handlesOffScreenU2005AndOcrPlainSpaceFallback_whilePreservingMentionMe() {
        // 1. Mentioned member's own bubbles scrolled off-screen, WeChat/QQ inserted `\u2005` delimiter
        val u2005Normalized = ChatScreenContextExtractor.normalizeGroupMentionsInMessage(
            rawText = "@我一直在寻找，有下一顿饭钱的世界在哪里\u2005这是什么，能干啥",
            isGroupChat = true,
            nicknameToBaseAlias = emptyMap()
        )
        assertEquals("[@群友] 这是什么，能干啥", u2005Normalized)

        // 2. Mentioned member's own bubbles scrolled off-screen, OCR flattened `\u2005` to plain space
        val ocrFallbackNormalized = ChatScreenContextExtractor.normalizeGroupMentionsInMessage(
            rawText = "@我一直在寻找，有下一顿饭钱的世界在哪里 这是什么，能干啥",
            isGroupChat = true,
            nicknameToBaseAlias = emptyMap()
        )
        assertEquals("[@群友] 这是什么，能干啥", ocrFallbackNormalized)

        // 3. `@我` and `@所有人` must NEVER be erased into `[@群友]`, and must trigger `hasExplicitMentionMe`
        assertEquals(
            "@我 今晚九点上线联调吗",
            ChatScreenContextExtractor.normalizeGroupMentionsInMessage(
                rawText = "@我\u2005今晚九点上线联调吗",
                isGroupChat = true,
                nicknameToBaseAlias = mapOf("terwer" to "群友A")
            )
        )
        assertEquals(
            "@所有人 请查收新版发布说明",
            ChatScreenContextExtractor.normalizeGroupMentionsInMessage(
                rawText = "@所有人 请查收新版发布说明",
                isGroupChat = true
            )
        )

        val mentionMeCtx = ChatScreenContextExtractor.extractChatContext(
            nodes = listOf(
                RawScreenTextNode("研发协同群(12)", 120, 175, 220, 860),
                RawScreenTextNode("阿泽", 500, 536, 160, 260),
                RawScreenTextNode("@我 帮我看下这个接口日志", 548, 620, 160, 740)
            ),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm",
            redactPii = false
        )
        assertTrue(mentionMeCtx.topology.hasExplicitMentionMe)
        assertEquals(JevRoleMode.GROUP_INVOLVED_PARTY, mentionMeCtx.topology.roleMode)
        assertEquals(listOf("[群友A(阿泽)] @我 帮我看下这个接口日志"), mentionMeCtx.formattedMessages)

        // 4. Normal time schedules & inline emails must remain untouched
        val scheduleCtx = ChatScreenContextExtractor.extractChatContext(
            nodes = listOf(
                RawScreenTextNode("今晚 19:30", 600, 668, 160, 460),
                RawScreenTextNode("发邮件到 dev@example.com 确认", 700, 768, 160, 760)
            ),
            screenWidth = 1080,
            screenHeight = 2400,
            packageName = "com.tencent.mm",
            redactPii = false
        )
        assertEquals(
            listOf("[对方] 今晚 19:30", "[对方] 发邮件到 dev@example.com 确认"),
            scheduleCtx.formattedMessages
        )
    }

    @Test
    fun promptBuilder_enforcesMentionCallTargetIronLaw_inSystemAndVisionPrompts() {
        val (sysPrompt, userPrompt) = PromptBuilder.buildJevCopilotPrompts(
            domainProfile = JevDomainProfile.IM_CHAT,
            targetAppName = "QQ",
            chatContextMessages = listOf("[群友A(terwer)] [@群友B] 这是什么，能干啥"),
            hasChatScreenshot = true
        )

        assertTrue(sysPrompt.contains("`@艾特` 呼叫指代铁律"))
        assertTrue(sysPrompt.contains("严禁将 `@` 后面的人名/网名字面含义当作讨论话题"))
        assertTrue(sysPrompt.contains("严禁诊断为“对某人昵称好奇”"))
        assertTrue(userPrompt.contains("严禁把被 `@` 对象的网名字面意思当作讨论话题"))
    }

    @Test
    fun realDeviceScreenshot8cdu_openJevCopilot_surfacesExplicitErrorOnFailure_andRecoversWithVisionFallback() = runBlocking {
        // Simulate `思源笔记④群(1470)` with 6 group chat messages (`群聊局势·6条 ▾`)
        val siyuanGroupNodes = listOf(
            RawScreenTextNode("思源笔记④群, v3810(1470)", 130, 185, 180, 900)
        ) + (1..6).flatMap { i ->
            val top = 360 + i * 160
            listOf(
                RawScreenTextNode("群友昵称$i", top, top + 34, 160, 320),
                RawScreenTextNode("第${i}条关于思源笔记同步与插件配置的讨论内容", top + 44, top + 112, 160, 820)
            )
        }
        TypeFreeAccessibilityService.debugSimulatedNodesForTest = "com.tencent.mobileqq" to siyuanGroupNodes

        // Phase 1: Missing API Key -> opening Jev Copilot MUST populate `jevErrorMessage` (never null/silent!)
        val hostMissingKey = FakeImeHost(
            initialState = ImeUiState(
                targetPackage = "com.tencent.mobileqq",
                targetAppName = "QQ"
            )
        )
        try {
            hostMissingKey.repository.updateSettings {
                it.copy(
                    jevCopilotEnabled = true,
                    chatContextReplyEnabled = true,
                    chatContextDisclosureAccepted = true,
                    chatContextSendScreenshot = false,
                    pipelineMode = PipelineMode.DUAL_STAGE,
                    llmConfig = LlmConfig(
                        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                        apiKey = "",
                        model = "doubao-seed-2.0-lite"
                    )
                )
            }
            val controller = VoiceController(
                host = hostMissingKey,
                audioRecorderProvider = { throw UnsupportedOperationException() },
                onExternalCommitToEngine = {},
                onResetComposing = {}
            )
            controller.openJevCopilotPanel()
            // Wait briefly for IO coroutine
            repeat(40) {
                if (!hostMissingKey.stateFlow.value.isJevLoading) return@repeat
                delay(15L)
            }
            val failedState = hostMissingKey.stateFlow.value
            assertEquals(VoiceSubPanel.JEV_COPILOT, failedState.voiceSubPanel)
            assertEquals(6, failedState.capturedChatContext.messageCount)
            assertFalse(failedState.isJevLoading)
            assertNull(failedState.jevCopilotResult)
            assertNotNull(
                "When inference fails, jevErrorMessage MUST be non-null so UI never falls back to fake placeholders",
                failedState.jevErrorMessage
            )
            assertTrue(failedState.jevErrorMessage!!.contains("API Key"))
        } finally {
            hostMissingKey.dispose()
        }

        // Phase 2: Vision HTTP 400 Chinese rejection ("该模型不支持图片输入") -> instant text-only fallback
        // + runtime non-vision cache + real 3 strategies rendered with `jevErrorMessage == null`!
        val requestPayloads = mutableListOf<String>()
        val validJevXml = """
            <jev>
            <lv>2</lv>
            <tag>工具技术交流</tag>
            <subtext>群友正在围绕思源笔记同步与插件用法交换实操经验</subtext>
            <opt label="顺题分享">我也用过这套同步方案，配好对象存储后挺稳的</opt>
            <opt label="追问细节">这个插件对移动端启动速度有影响吗</opt>
            <opt label="场外递梗">好家伙思源已经被你们玩成全能工作台了</opt>
            <weights>51,31,18</weights>
            </jev>
        """.trimIndent()
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                val body = buf.readUtf8()
                synchronized(requestPayloads) { requestPayloads.add(body) }
                if (body.contains("image_url")) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("Bad Request")
                        .body(
                            """{"error":{"message":"InvalidParameter: 当前推理接入点不支持图片输入"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"choices":[{"message":{"role":"assistant","content":${com.google.gson.Gson().toJson(validJevXml)}}}]}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
            })
            .build()

        TypeFreeAccessibilityService.debugSimulatedScreenshotBase64ForTest = "dGVzdC1qcGVnLWJhc2U2NA=="
        val hostWithMockAi = FakeImeHost(
            aiEngine = AiPipelineEngine(httpClient = mockHttp),
            initialState = ImeUiState(
                targetPackage = "com.tencent.mobileqq",
                targetAppName = "QQ"
            )
        )
        try {
            val arkUrl = "https://ark.cn-beijing.volces.com/api/v3"
            val arkEndpoint = "ep-20261112-textonly"
            hostWithMockAi.repository.updateSettings {
                it.copy(
                    jevCopilotEnabled = true,
                    chatContextReplyEnabled = true,
                    chatContextDisclosureAccepted = true,
                    chatContextSendScreenshot = true,
                    pipelineMode = PipelineMode.DUAL_STAGE,
                    llmConfig = LlmConfig(
                        baseUrl = arkUrl,
                        apiKey = "ark-test-key",
                        model = arkEndpoint,
                        thinkingMode = ThinkingMode.DISABLED
                    )
                )
            }
            val controller = VoiceController(
                host = hostWithMockAi,
                audioRecorderProvider = { throw UnsupportedOperationException() },
                onExternalCommitToEngine = {},
                onResetComposing = {}
            )
            controller.openJevCopilotPanel()
            repeat(50) {
                if (!hostWithMockAi.stateFlow.value.isJevLoading) return@repeat
                delay(15L)
            }
            val okState = hostWithMockAi.stateFlow.value
            assertFalse(okState.isJevLoading)
            assertNull(okState.jevErrorMessage)
            assertNotNull("Opening Jev Copilot must auto-populate real 3 strategies", okState.jevCopilotResult)
            assertEquals("工具技术交流", okState.jevCopilotResult!!.tag)
            assertEquals(3, okState.jevCopilotResult!!.options.size)
            assertTrue(
                "Chinese HTTP 400 vision rejection must cache endpoint as non-vision for 0ms future calls",
                AiPipelineEngine.isVisionMarkedUnsupported(arkUrl, arkEndpoint)
            )
            // First open made 1 vision attempt (400) + 1 text-only retry (200) = 2 calls (zero wasted param retries!)
            assertEquals(2, requestPayloads.size)
        } finally {
            hostWithMockAi.dispose()
        }

        // Verify diagnostic error classifier for timeout & offline errors
        assertEquals(
            "模型请求超时，请点「↻ 换一批」重试",
            VoiceController.formatJevUserFacingError(SocketTimeoutException("Read timed out"))
        )
        assertEquals(
            "网络连接异常，请检查网络后点「↻ 换一批」重试",
            VoiceController.formatJevUserFacingError(UnknownHostException("Unable to resolve host"))
        )
    }

    @Test
    fun v343SourceGuard_jevPanelRemovesStaticPlaceholder_wiresRadarError_andKeepsKeyboardScreenUnder650Lines() {
        val root = resolveProjectRoot()
        val jevPanelSrc = File(
            root,
            "app/src/main/java/com/typefree/ime/ime/panels/JevCopilotPanel.kt"
        ).readText()
        assertFalse(
            "JevCopilotPanel must never display confusing static placeholder card copy",
            jevPanelSrc.contains("点击「↻ 换一批」或按住下方底牌条生成策略")
        )
        assertFalse(
            "JevSubtextRadarBar must never display confusing static ungenerated radar copy",
            jevPanelSrc.contains("点「↻ 换一批」读取上文推演")
        )
        assertFalse(
            "JevCopilotPanel must keep zero TextOverflow.Ellipsis",
            jevPanelSrc.contains("TextOverflow.Ellipsis")
        )
        assertTrue(jevPanelSrc.contains("errorMessage = uiState.jevErrorMessage"))
        assertTrue(jevPanelSrc.contains("⛶ 深读"))

        val keyboardScreenLines = File(
            root,
            "app/src/main/java/com/typefree/ime/ime/KeyboardScreen.kt"
        ).readLines().size
        assertTrue(
            "KeyboardScreen.kt must remain <= 650 lines (actual: $keyboardScreenLines)",
            keyboardScreenLines <= 650
        )
    }
}
