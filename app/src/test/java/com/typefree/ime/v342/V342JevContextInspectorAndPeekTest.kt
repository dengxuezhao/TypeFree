package com.typefree.ime.v342

import android.view.inputmethod.InputConnection
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.bubble.CapturedChatContext
import com.typefree.ime.bubble.ChatScreenContextExtractor
import com.typefree.ime.bubble.ChatSpeaker
import com.typefree.ime.bubble.ExtractedChatMessage
import com.typefree.ime.bubble.RawScreenTextNode
import com.typefree.ime.bubble.TypeFreeAccessibilityService
import com.typefree.ime.bubble.ViewportOcrExtractor
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.SecretVault
import com.typefree.ime.ime.ImeHostCallbacks
import com.typefree.ime.ime.ImeUiAction
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.UndoSnapshot
import com.typefree.ime.ime.VoiceController
import com.typefree.ime.ime.VoiceSubPanel
import com.typefree.ime.ime.jev.ChatTopology
import com.typefree.ime.ime.jev.JevCopilotResult
import com.typefree.ime.ime.jev.JevRoleMode
import com.typefree.ime.ime.jev.JevStrategyOption
import com.typefree.ime.ime.panels.buildJevInspectorHeaderSummary
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.privacy.LocalPiiRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V342JevContextInspectorAndPeekTest {

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
    }

    private class FakeImeHost(
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
        override val aiEngine: AiPipelineEngine = AiPipelineEngine()
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

    @Test
    fun toggleJevContextInspector_togglesCleanly_andAutoCollapsesOnSelectCardRefreshPeekSwitchOrClose() {
        val sampleResult = JevCopilotResult(
            level = 5,
            tag = "排期协同",
            subtext = "对方希望确认今日节点",
            options = listOf(
                JevStrategyOption(label = "稳妥承接", replyText = "收到，下午四点前同步结论。", probability = 55),
                JevStrategyOption(label = "温和设限", replyText = "今天先出初版，明早对齐细节。", probability = 28),
                JevStrategyOption(label = "反客为主", replyText = "先把接口文档发我确认下范围。", probability = 17)
            )
        )
        val fakeHost = FakeImeHost(
            initialState = ImeUiState(
                voiceSubPanel = VoiceSubPanel.JEV_COPILOT,
                targetPackage = "com.ss.android.lark",
                targetAppName = "飞书",
                jevCopilotResult = sampleResult
            )
        )
        try {
            val controller = VoiceController(
                host = fakeHost,
                audioRecorderProvider = { throw UnsupportedOperationException() },
                onExternalCommitToEngine = {},
                onResetComposing = {}
            )

            // 1. Default is false; ToggleJevContextInspector flips false -> true -> false
            assertFalse(fakeHost.stateFlow.value.isJevContextInspectorOpen)
            val action: ImeUiAction = ImeUiAction.ToggleJevContextInspector
            assertEquals(ImeUiAction.ToggleJevContextInspector, action)

            controller.toggleJevContextInspector()
            assertTrue(fakeHost.stateFlow.value.isJevContextInspectorOpen)
            controller.toggleJevContextInspector()
            assertFalse(fakeHost.stateFlow.value.isJevContextInspectorOpen)

            // 2. Selecting a strategy card auto-collapses the inspector
            controller.toggleJevContextInspector()
            assertTrue(fakeHost.stateFlow.value.isJevContextInspectorOpen)
            controller.selectJevStrategyCard(1)
            assertEquals(1, fakeHost.stateFlow.value.selectedJevStrategyIndex)
            assertFalse(
                "Selecting a strategy card must auto-reset isJevContextInspectorOpen to false",
                fakeHost.stateFlow.value.isJevContextInspectorOpen
            )

            // 3. Refreshing strategies (换一批) auto-collapses the inspector
            fakeHost.stateFlow.update { it.copy(isJevLoading = false, isJevContextInspectorOpen = true) }
            controller.refreshJevStrategies()
            assertFalse(
                "Refreshing strategies must auto-reset isJevContextInspectorOpen to false",
                fakeHost.stateFlow.value.isJevContextInspectorOpen
            )

            // 4. Triggering 240ms full-screen peek capture auto-collapses the inspector immediately
            fakeHost.stateFlow.update {
                it.copy(
                    isJevLoading = false,
                    isFullScreenPeekCapturing = false,
                    isJevContextInspectorOpen = true
                )
            }
            var insetsRequestedCount = 0
            controller.triggerFullScreenPeekCapture { insetsRequestedCount += 1 }
            assertFalse(
                "Triggering full-screen peek capture must immediately reset isJevContextInspectorOpen to false",
                fakeHost.stateFlow.value.isJevContextInspectorOpen
            )
            assertTrue(insetsRequestedCount >= 1)

            // 5. Switching to HelpMeWrite or reopening/closing Jev Copilot auto-collapses the inspector
            fakeHost.stateFlow.update { it.copy(isJevContextInspectorOpen = true) }
            controller.openOrRestoreVoicePenPanel()
            assertEquals(VoiceSubPanel.HELP_ME_WRITE, fakeHost.stateFlow.value.voiceSubPanel)
            assertFalse(fakeHost.stateFlow.value.isJevContextInspectorOpen)

            fakeHost.stateFlow.update { it.copy(isJevContextInspectorOpen = true) }
            controller.openJevCopilotPanel()
            assertEquals(VoiceSubPanel.JEV_COPILOT, fakeHost.stateFlow.value.voiceSubPanel)
            assertFalse(fakeHost.stateFlow.value.isJevContextInspectorOpen)

            fakeHost.stateFlow.update { it.copy(isJevContextInspectorOpen = true) }
            controller.closeVoiceSubPanel()
            assertEquals(VoiceSubPanel.NONE, fakeHost.stateFlow.value.voiceSubPanel)
            assertFalse(fakeHost.stateFlow.value.isJevContextInspectorOpen)
        } finally {
            fakeHost.dispose()
        }
    }

    @Test
    fun computeChatRegionCropRect_preserves2120pxBottomInFullScreenPeek_andGuards076RatioByDefault() {
        // On a 1080x2400 screen, cropTop = (2400 * 0.052f).toInt() = 124.
        // When keyboard is visible (default allowFullScreenViewport = false):
        // - maxSafeBottom = (2400 * 0.76f).toInt() = 1824
        // - defaultBottom = (2400 * 0.58f).toInt() = 1392
        // If inputBoxTop = 2120 (> 1824), default mode treats it as stale pre-resize and falls back to 1392.
        val defaultCropAt2120 = ChatScreenContextExtractor.computeChatRegionCropRect(
            bitmapWidth = 1080,
            bitmapHeight = 2400,
            inputBoxTop = 2120
        )
        assertEquals(0, defaultCropAt2120[0])
        assertEquals(124, defaultCropAt2120[1])
        assertEquals(1080, defaultCropAt2120[2])
        assertEquals(1392, defaultCropAt2120[1] + defaultCropAt2120[3])

        // Normal keyboard-up inputBoxTop = 1520 (<= 1824) is preserved in default mode
        val defaultCropAt1520 = ChatScreenContextExtractor.computeChatRegionCropRect(
            bitmapWidth = 1080,
            bitmapHeight = 2400,
            inputBoxTop = 1520,
            allowFullScreenViewport = false
        )
        assertEquals(1520, defaultCropAt1520[1] + defaultCropAt1520[3])

        // During 240ms Full-Screen Peek (allowFullScreenViewport = true):
        // - maxSafeBottom = (2400 * 0.92f).toInt() = 2208
        // - defaultBottom = (2400 * 0.88f).toInt() = 2112
        // When inputBoxTop = 2120, cropBottom accurately preserves 2120 instead of falling back to 1392!
        val peekCropAt2120 = ChatScreenContextExtractor.computeChatRegionCropRect(
            bitmapWidth = 1080,
            bitmapHeight = 2400,
            inputBoxTop = 2120,
            allowFullScreenViewport = true
        )
        assertEquals(0, peekCropAt2120[0])
        assertEquals(124, peekCropAt2120[1])
        assertEquals(1080, peekCropAt2120[2])
        assertEquals(
            "Full-screen peek with inputBoxTop=2120 on 2400px screen must preserve bottom at 2120 (not 1392)",
            2120,
            peekCropAt2120[1] + peekCropAt2120[3]
        )

        // When inputBoxTop is unknown (-1) during full-screen peek, falls back to 0.88f (2112px)
        val peekCropFallback = ChatScreenContextExtractor.computeChatRegionCropRect(
            bitmapWidth = 1080,
            bitmapHeight = 2400,
            inputBoxTop = -1,
            allowFullScreenViewport = true
        )
        assertEquals(2112, peekCropFallback[1] + peekCropFallback[3])
    }

    @Test
    fun fullScreenPeekCapture_extractsUpTo12MessagesAnd1100Chars_acrossLowerScreenViewport() = runBlocking {
        // Construct 14 chat bubbles spanning Y=300..2020 above a bottom input box at Y=2120 on a 1080x2400 screen
        val chatNodes = (1..14).map { idx ->
            val top = 280 + idx * 120 // 400, 520, ..., 1960
            val isSelf = idx % 3 == 0
            RawScreenTextNode(
                text = "第${idx}条上下文消息：确认分布式索引压测指标与今日发版窗口排期节点",
                boundsTop = top,
                boundsBottom = top + 72,
                boundsLeft = if (isSelf) 380 else 140,
                boundsRight = if (isSelf) 940 else 720,
                className = "android.widget.TextView"
            )
        }
        val bottomInputNode = RawScreenTextNode(
            text = "",
            boundsTop = 2120,
            boundsBottom = 2220,
            boundsLeft = 120,
            boundsRight = 920,
            isEditable = true,
            className = "android.widget.EditText"
        )
        TypeFreeAccessibilityService.debugSimulatedNodesForTest =
            "com.tencent.mm" to (chatNodes + bottomInputNode)

        val settings = AppSettings(
            jevCopilotEnabled = true,
            chatContextReplyEnabled = true,
            chatContextDisclosureAccepted = true,
            chatContextSendScreenshot = false
        )

        // 1. Standard non-peek capture with inputBoxTop=2120 (> 0.76f) clamps at 1392px and max 10 messages
        val standardCtx = TypeFreeAccessibilityService.captureChatContextIfAvailable(
            expectedPackage = "com.tencent.mm",
            appName = "微信",
            settings = settings,
            isIncognitoMode = false,
            isPasswordInput = false,
            ignoreChatContextForTurn = false,
            forJevCopilot = true,
            isFullScreenPeek = false
        )
        assertTrue(
            "Standard non-peek capture must stay <= 10 messages (actual: ${standardCtx.messageCount})",
            standardCtx.messageCount <= 10
        )

        // 2. Full-screen peek capture (isFullScreenPeek = true) preserves up to Y=2120 and extracts 12 messages
        val peekCtx = TypeFreeAccessibilityService.captureChatContextIfAvailable(
            expectedPackage = "com.tencent.mm",
            appName = "微信",
            settings = settings,
            isIncognitoMode = false,
            isPasswordInput = false,
            ignoreChatContextForTurn = false,
            forJevCopilot = true,
            isFullScreenPeek = true
        )
        assertEquals(
            "isFullScreenPeek=true must extract 12 messages across the full 2120px viewport",
            12,
            peekCtx.messageCount
        )
        assertTrue(peekCtx.formattedMessages.first().contains("第3条上下文消息"))
        assertTrue(peekCtx.formattedMessages.last().contains("第14条上下文消息"))

        // 3. Verify captureChatViewportOutcomeIfAvailable(isFullScreenPeek = true) with OCR nodes also supports 12 messages
        TypeFreeAccessibilityService.debugSimulatedNodesForTest = "com.tencent.mm" to listOf(bottomInputNode)
        ViewportOcrExtractor.debugSimulatedOcrNodesForTest = chatNodes
        val peekOutcome = TypeFreeAccessibilityService.captureChatViewportOutcomeIfAvailable(
            expectedPackage = "com.tencent.mm",
            settings = settings,
            isIncognitoMode = false,
            isPasswordInput = false,
            ignoreChatContextForTurn = false,
            forJevCopilot = true,
            isFullScreenPeek = true
        )
        assertNotNull(peekOutcome)
        assertEquals(12, peekOutcome!!.mergedMessages.size)
        assertTrue(peekOutcome.mergedMessages.last().contains("第14条上下文消息"))
    }

    @Test
    fun inspectorHeaderSummary_andJevCopilotPanelSourceGuards_matchV342Spec() {
        val groupCtx = CapturedChatContext(
            packageName = "com.tencent.mobileqq",
            appName = "QQ",
            messages = listOf(
                ExtractedChatMessage(ChatSpeaker.SELF, "122万块是单机吗？", 540),
                ExtractedChatMessage(ChatSpeaker.OTHER, "单机", 750, senderAlias = "群友A"),
                ExtractedChatMessage(ChatSpeaker.OTHER, "单机，这是思源啊", 910, senderAlias = "群友B")
            ),
            topology = ChatTopology(
                isGroupChat = true,
                groupMemberCount = 1971,
                distinctOtherSenders = listOf("群友A", "群友B"),
                recentSelfTurns = 1,
                roleMode = JevRoleMode.GROUP_INVOLVED_PARTY
            )
        )
        assertEquals(
            "多人聊天群(1971人) · 群聊当事人 · 共3条",
            buildJevInspectorHeaderSummary(groupCtx, usedVision = false)
        )

        val redaction = LocalPiiRedactor.redactText("打我手机 13812345678", context = null)
        val privateCtx = CapturedChatContext(
            packageName = "com.tencent.mm",
            appName = "微信",
            messages = List(5) { idx ->
                ExtractedChatMessage(ChatSpeaker.OTHER, "消息${idx + 1} [手机号_1]", 300 + idx * 100)
            },
            redactionSession = redaction,
            topology = ChatTopology(
                isGroupChat = false,
                roleMode = JevRoleMode.PRIVATE_1V1
            )
        )
        assertEquals(
            "双人私聊 · 1v1私聊对象 · 共5条 · 🛡脱敏1处 · 含视觉",
            buildJevInspectorHeaderSummary(privateCtx, usedVision = true)
        )

        val root = resolveProjectRoot()
        val jevPanelFile = File(root, "app/src/main/java/com/typefree/ime/ime/panels/JevCopilotPanel.kt")
        val jevPanelSource = jevPanelFile.readText()

        assertFalse(
            "JevCopilotPanel.kt must never contain TextOverflow.Ellipsis",
            jevPanelSource.contains("TextOverflow.Ellipsis")
        )
        assertFalse(
            "JevCopilotPanel.kt must never contain BottomCornerUtilityIcons",
            jevPanelSource.contains("BottomCornerUtilityIcons")
        )
        assertFalse(
            "JevCopilotPanel.kt top status pill must no longer bind ToggleIgnoreChatContext",
            jevPanelSource.contains("ToggleIgnoreChatContext")
        )
        assertTrue(
            "JevCopilotPanel.kt must bind ToggleJevContextInspector",
            jevPanelSource.contains("ToggleJevContextInspector")
        )
        assertTrue(
            "JevCopilotPanel.kt must define JevContextInspectorCard",
            jevPanelSource.contains("fun JevContextInspectorCard(")
        )
        assertTrue(
            "JevCopilotPanel.kt must include the '全屏补抓更多上文' action button inside the inspector card",
            jevPanelSource.contains("全屏补抓更多上文")
        )
        assertTrue(
            "JevCopilotPanel.kt must retain the compact '⛶ 深读' header pill",
            jevPanelSource.contains("\"⛶ 深读\"")
        )

        val keyboardScreenLines = File(root, "app/src/main/java/com/typefree/ime/ime/KeyboardScreen.kt").readLines().size
        assertTrue(
            "KeyboardScreen.kt must remain <= 650 lines (actual: $keyboardScreenLines)",
            keyboardScreenLines <= 650
        )
    }
}
