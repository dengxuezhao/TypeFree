package com.typefree.ime.v350

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SecretVault
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.ime.HelpWritePromptPreset
import com.typefree.ime.ime.ImeHostCallbacks
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.ImeUiAction
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.TypingController
import com.typefree.ime.ime.UndoSnapshot
import com.typefree.ime.ime.VoiceController
import com.typefree.ime.ime.VoiceSubPanel
import com.typefree.ime.ime.chrome.TopBarSlotPolicy
import com.typefree.ime.ime.jev.ChatTopology
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.ime.jev.JevRoleMode
import com.typefree.ime.ime.panels.HelpWriteChipFilter
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.PipelineExecutionResult
import com.typefree.ime.network.PromptBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * TypeFree v3.5.0 全面质量审计与高对抗性独立单元测试套件：
 * 
 * 模块 1：帮写彻底去回复化 + 7大主动创作体裁 + 静音草稿免开口扩写与按住说话原地替换（双阶段 & 多模态直通）
 * 模块 2：无前缀顶栏 [🔍 问AI] 胶囊 + 键盘内 AskAiCardPanel + == 回车下线 + Undo 恢复完整闭环对抗
 * 模块 3：Jev 军师五层反装蠢决策树（二元分流/事实筛子/内行认知平视三轴/禁用装蠢反问/笃定陈述句/禁谐音烂梗与黄豆）
 * 模块 4：架构红线（KeyboardScreen.kt <= 650 行、Prompt 100% 静态 const val）
 */
class V350PureHelpWriteAskAiAndJevEpistemicTest {

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "app/src/main").exists()) cwd else cwd.parentFile ?: cwd
    }

    /**
     * 模拟可交互的真实 InputConnection，支持完整的 batchEdit、setSelection、commitText、deleteSurroundingText。
     */
    private class SimulatedEditorInputConnection(initialText: String = "") : InputConnection {
        var textBuffer: StringBuilder = StringBuilder(initialText)
        var cursorStart: Int = initialText.length
        var cursorEnd: Int = initialText.length
        val actionsHistory = mutableListOf<String>()

        fun currentText(): String = textBuffer.toString()

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            val start = (cursorStart - n).coerceAtLeast(0)
            return textBuffer.substring(start, cursorStart)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
            val end = (cursorEnd + n).coerceAtMost(textBuffer.length)
            return textBuffer.substring(cursorEnd, end)
        }

        override fun getSelectedText(flags: Int): CharSequence {
            return if (cursorStart != cursorEnd) {
                val s = cursorStart.coerceAtMost(cursorEnd)
                val e = cursorStart.coerceAtLeast(cursorEnd)
                textBuffer.substring(s, e)
            } else ""
        }

        override fun getCursorCapsMode(reqModes: Int): Int = 0

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            val et = ExtractedText()
            et.text = textBuffer.toString()
            et.selectionStart = cursorStart
            et.selectionEnd = cursorEnd
            et.startOffset = 0
            return et
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            actionsHistory.add("deleteSurroundingText($beforeLength, $afterLength)")
            val deleteBeforeStart = (cursorStart - beforeLength).coerceAtLeast(0)
            val deleteAfterEnd = (cursorEnd + afterLength).coerceAtMost(textBuffer.length)
            textBuffer.delete(cursorEnd, deleteAfterEnd)
            textBuffer.delete(deleteBeforeStart, cursorStart)
            cursorStart = deleteBeforeStart
            cursorEnd = deleteBeforeStart
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            return deleteSurroundingText(beforeLength, afterLength)
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun finishComposingText(): Boolean = true

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val str = text?.toString().orEmpty()
            actionsHistory.add("commitText('$str')")
            val s = cursorStart.coerceAtMost(cursorEnd)
            val e = cursorStart.coerceAtLeast(cursorEnd)
            textBuffer.replace(s, e, str)
            cursorStart = s + str.length
            cursorEnd = cursorStart
            return true
        }

        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = true
        override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?): Boolean = true

        override fun setSelection(start: Int, end: Int): Boolean {
            actionsHistory.add("setSelection($start, $end)")
            cursorStart = start.coerceIn(0, textBuffer.length)
            cursorEnd = end.coerceIn(0, textBuffer.length)
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            actionsHistory.add("performEditorAction($editorAction)")
            return true
        }

        override fun performContextMenuAction(id: Int): Boolean = true
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean {
            actionsHistory.add("sendKeyEvent(${event?.keyCode})")
            return true
        }
        override fun clearMetaKeyStates(states: Int): Boolean = true
        override fun reportFullscreenMode(enabled: Boolean): Boolean = true
        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = true
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = true
        override fun getHandler(): android.os.Handler? = null
        override fun closeConnection() {}
        override fun commitContent(
            inputContentInfo: android.view.inputmethod.InputContentInfo,
            flags: Int,
            opts: android.os.Bundle?
        ): Boolean = true
    }

    private class FullSimulatedImeHost(
        override val aiEngine: AiPipelineEngine = AiPipelineEngine(),
        initialState: ImeUiState = ImeUiState(),
        var mockConnection: SimulatedEditorInputConnection? = null
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
        val committedTexts = mutableListOf<String>()
        val undoSnapshots = mutableListOf<UndoSnapshot>()
        var clipboardCopiedText: String? = null

        override fun currentInputConnection(): InputConnection? = mockConnection
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
        ) {
            committedTexts.add(text)
            mockConnection?.commitText(text, 1)
        }
        override fun pushUndoSnapshot(snapshot: UndoSnapshot?) {
            if (snapshot != null) undoSnapshots.add(snapshot)
        }
        override fun peekTopUndoSnapshot(): UndoSnapshot? = undoSnapshots.lastOrNull()
        override fun recordManualEditTouch() {}
        override fun tryAutoLearnFromRecentEdit() {}
        override fun onDictationCommittedForAutoLearn(fullBufferAfterCommit: String) {}
        override fun clearAutoLearnBaseline() {}
        override fun readSelectedTextSafely(): String = mockConnection?.getSelectedText(0)?.toString().orEmpty()
        override fun readSurroundingBeforeCursor(maxChars: Int): String = mockConnection?.getTextBeforeCursor(maxChars, 0)?.toString().orEmpty()
        override fun readFullEditorTextSafely(): String = mockConnection?.currentText().orEmpty()
        override fun readSystemClipboardText(): String = clipboardCopiedText.orEmpty()
        override fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean) {}
        override fun copyTextToClipboard(text: String) {
            clipboardCopiedText = text
        }
        override fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File = wavFile
        override fun getLastRecordingWavFile(): File = File("unused.wav")
        override fun hasRetryWavFile(): Boolean = false

        fun triggerUndo(): Boolean {
            val snapshot = undoSnapshots.removeLastOrNull() ?: stateFlow.value.undoSnapshot ?: return false
            val ic = mockConnection ?: return false
            ic.beginBatchEdit()
            val currentlySelected = readSelectedTextSafely()
            if (currentlySelected.isNotEmpty() && currentlySelected.length == snapshot.insertedTextLength) {
                ic.commitText(snapshot.replacedOriginalText, 1)
            } else {
                if (snapshot.insertedTextLength > 0) {
                    ic.deleteSurroundingText(snapshot.insertedTextLength, 0)
                }
                if (snapshot.replacedOriginalText.isNotEmpty()) {
                    ic.commitText(snapshot.replacedOriginalText, 1)
                }
            }
            ic.endBatchEdit()
            stateFlow.value = stateFlow.value.copy(
                undoSnapshot = undoSnapshots.lastOrNull(),
                currentEditorText = mockConnection?.currentText().orEmpty()
            )
            return true
        }

        fun dispose() {
            scope.cancel()
        }
    }

    // =========================================================================
    // 1. 密码框与各种特殊输入类型的安全边界严苛对抗测试
    // =========================================================================

    @Test
    fun security_passwordBox_neverRevealsContentToDraftOrAskAi_underAllVariations() {
        val passwordVariations = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )

        for (inputType in passwordVariations) {
            val simIc = SimulatedEditorInputConnection("SuperSecretP@ssw0rd999")
            val host = FullSimulatedImeHost(
                initialState = ImeUiState(
                    currentInputType = inputType,
                    tabMode = KeyboardTabMode.QWERTY
                ),
                mockConnection = simIc
            )
            val controller = VoiceController(
                host = host,
                audioRecorderProvider = { throw UnsupportedOperationException() },
                onExternalCommitToEngine = {},
                onResetComposing = {}
            )

            // 1. 验证顶栏问AI胶囊在任何密码框变体下绝对不显示
            assertFalse(
                "AskAI pill must never show in password inputType: $inputType",
                TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                    settings = AppSettings(equalEqualAskAiEnabled = true),
                    activeTab = KeyboardTabMode.QWERTY,
                    isComposing = false,
                    isToolPanelOpen = false,
                    isClipboardOpen = false,
                    currentInputType = inputType,
                    currentEditorText = "SuperSecretP@ssw0rd999"
                )
            )

            // 2. 验证进入帮写面板时，草稿被强制置空，绝不泄露密码
            controller.openOrRestoreVoicePenPanel()
            val state = host.stateFlow.value
            assertEquals("Password must NEVER be copied into currentEditorText", "", state.currentEditorText)
            assertFalse("Chat context must never be captured", state.capturedChatContext.hasContext)
            assertFalse("Disclosure must not prompt", state.showChatContextDisclosurePrompt)

            // 3. 验证点击问AI入口时，密码框下直接 return，不发起检索
            host.stateFlow.value = host.stateFlow.value.copy(
                tabMode = KeyboardTabMode.QWERTY,
                voiceSubPanel = VoiceSubPanel.NONE,
                currentInputType = inputType
            )
            controller.openAskAiCardPanel()
            assertEquals("Ask AI card must not open in password box", KeyboardTabMode.QWERTY, host.stateFlow.value.tabMode)
            assertEquals("", host.stateFlow.value.askAiQuery)

            // 4. 验证一键改写芯片在密码框下直接拦截，不发送密码
            controller.runHelpWriteDraftTransform(
                HelpWritePromptPreset("transform_polish", "✨ 润色", "润色")
            )
            assertNull("AI answer should be null", host.stateFlow.value.askAiErrorMessage)
            assertEquals("Simulated editor text must remain unchanged", "SuperSecretP@ssw0rd999", simIc.currentText())

            host.dispose()
        }
    }

    @Test
    fun security_nonTextInputTypes_suppressAskAiPill() {
        val nonTextTypes = listOf(
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE,
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        )
        val settings = AppSettings(equalEqualAskAiEnabled = true)
        for (t in nonTextTypes) {
            assertFalse(
                "Non-text inputType $t must never trigger Ask AI pill",
                TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                    settings = settings,
                    activeTab = KeyboardTabMode.QWERTY,
                    isComposing = false,
                    isToolPanelOpen = false,
                    isClipboardOpen = false,
                    currentInputType = t,
                    currentEditorText = "12345678"
                )
            )
        }
    }

    // =========================================================================
    // 2. 拼音 composing 防抖与最小字符边界测试
    // =========================================================================

    @Test
    fun antiDisturbance_topBarAskAiPill_composingAndWhitespaceBoundaries() {
        val settings = AppSettings(equalEqualAskAiEnabled = true)

        // 1. 处于输入法打字 composing 态时绝对不弹
        assertFalse(
            TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                settings = settings,
                activeTab = KeyboardTabMode.QWERTY,
                isComposing = true,
                isToolPanelOpen = false,
                isClipboardOpen = false,
                currentInputType = InputType.TYPE_CLASS_TEXT,
                currentEditorText = "正在打字中"
            )
        )

        // 2. 空白、全空格、仅 1 个字符（含周围被空格包裹的单字符）严防误弹
        val singleCharOrBlankInputs = listOf(
            "",
            " ",
            "   \t\n  ",
            "a",
            "好",
            "   a  ",
            "  好   ",
            " ? "
        )
        for (input in singleCharOrBlankInputs) {
            assertFalse(
                "Input '$input' (trimmed length ${input.trim().length}) must not trigger pill",
                TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                    settings = settings,
                    activeTab = KeyboardTabMode.QWERTY,
                    isComposing = false,
                    isToolPanelOpen = false,
                    isClipboardOpen = false,
                    currentInputType = InputType.TYPE_CLASS_TEXT,
                    currentEditorText = input
                )
            )
        }

        // 3. 有效字符 >= 2 时正常点亮
        val validInputs = listOf(
            "ab",
            "河口",
            "  河口湖  ",
            "AI",
            "12",
            "  a b  "
        )
        for (input in validInputs) {
            assertTrue(
                "Valid input '$input' should trigger pill",
                TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                    settings = settings,
                    activeTab = KeyboardTabMode.QWERTY,
                    isComposing = false,
                    isToolPanelOpen = false,
                    isClipboardOpen = false,
                    currentInputType = InputType.TYPE_CLASS_TEXT,
                    currentEditorText = input
                )
            )
        }
    }

    // =========================================================================
    // 3. 问 AI 卡片全生命周期与 Undo 恢复完整闭环对抗测试
    // =========================================================================

    @Test
    fun askAiCard_dismissAndClear_createsCompleteUndoRestorationLoop() {
        val initialQuestion = "河口湖到东京怎么坐车最方便？"
        val simIc = SimulatedEditorInputConnection(initialQuestion)
        val host = FullSimulatedImeHost(
            initialState = ImeUiState(
                tabMode = KeyboardTabMode.QWERTY,
                currentEditorText = initialQuestion
            ),
            mockConnection = simIc
        )
        val controller = VoiceController(
            host = host,
            audioRecorderProvider = { throw UnsupportedOperationException() },
            onExternalCommitToEngine = {},
            onResetComposing = {}
        )

        // 1. 打开问 AI 卡片：输入框内容必须纹丝不动！
        controller.openAskAiCardPanel()
        assertEquals(KeyboardTabMode.VOICE, host.stateFlow.value.tabMode)
        assertEquals(VoiceSubPanel.ASK_AI_CARD, host.stateFlow.value.voiceSubPanel)
        assertEquals(initialQuestion, host.stateFlow.value.askAiQuery)
        assertEquals("Host input field must NOT be touched when opening card", initialQuestion, simIc.currentText())

        // 2. 模拟 AI 回答完成
        val mockAnswer = "从河口湖乘坐富士回游号直达新宿，约2小时；或乘高速巴士直达东京站/新宿。"
        host.stateFlow.value = host.stateFlow.value.copy(
            askAiAnswer = mockAnswer,
            isAskAiLoading = false
        )

        // 3. 点击「✓ 知道了(清空)」：输入框被清空，但压入包含原问题的 UndoSnapshot
        controller.dismissAskAiAndClearEditor()
        assertEquals(KeyboardTabMode.QWERTY, host.stateFlow.value.tabMode)
        assertEquals(VoiceSubPanel.NONE, host.stateFlow.value.voiceSubPanel)
        assertEquals("Host editor must be emptied", "", simIc.currentText())
        val topUndo = host.peekTopUndoSnapshot()
        assertNotNull("Must push UndoSnapshot on dismiss & clear", topUndo)
        assertEquals(initialQuestion, topUndo!!.replacedOriginalText)
        assertEquals(0, topUndo.insertedTextLength)

        // 4. 纯函数断言：ImeLogic.applyUndoToBuffer 能够完美恢复原问题
        val (restoredBefore, restoredAfter) = ImeLogic.applyUndoToBuffer(
            beforeCursor = "",
            afterCursor = "",
            snapshot = topUndo
        )
        assertEquals(initialQuestion, restoredBefore)
        assertEquals("", restoredAfter)

        // 5. 模拟软键盘触发 TriggerUndo：输入框真实恢复出原问题！
        val undoSuccess = host.triggerUndo()
        assertTrue("Undo must succeed", undoSuccess)
        assertEquals("Input field must be 100% restored after TriggerUndo", initialQuestion, simIc.currentText())

        host.dispose()
    }

    @Test
    fun askAiCard_commitAnswerToEditor_replacesQuery_andUndoRestoresOriginalQuery() {
        val originalQuery = "量子计算的核心原理"
        val simIc = SimulatedEditorInputConnection(originalQuery)
        val host = FullSimulatedImeHost(
            initialState = ImeUiState(
                tabMode = KeyboardTabMode.VOICE,
                voiceSubPanel = VoiceSubPanel.ASK_AI_CARD,
                askAiQuery = originalQuery,
                askAiAnswer = "基于量子叠加与纠缠原理，利用量子比特并行处理海量计算。"
            ),
            mockConnection = simIc
        )
        val controller = VoiceController(
            host = host,
            audioRecorderProvider = { throw UnsupportedOperationException() },
            onExternalCommitToEngine = {},
            onResetComposing = {}
        )

        // 1. 点击「填入输入框 ↵」
        controller.commitAskAiAnswerToEditor()
        assertEquals(KeyboardTabMode.QWERTY, host.stateFlow.value.tabMode)
        val answer = "基于量子叠加与纠缠原理，利用量子比特并行处理海量计算。"
        assertEquals("Host editor should now contain answer", answer, simIc.currentText())
        val undo = host.peekTopUndoSnapshot()
        assertNotNull(undo)
        assertEquals(originalQuery, undo!!.replacedOriginalText)
        assertEquals(answer.length, undo.insertedTextLength)

        // 2. 纯函数断言：applyUndoToBuffer
        val (restoredBefore, _) = ImeLogic.applyUndoToBuffer(
            beforeCursor = answer,
            afterCursor = "",
            snapshot = undo
        )
        assertEquals(originalQuery, restoredBefore)

        // 3. 模拟触发 TriggerUndo：解答被删去，原问题重新完整上屏！
        val undoSuccess = host.triggerUndo()
        assertTrue("Undo must succeed", undoSuccess)
        assertEquals("Original query must be restored after Undo", originalQuery, simIc.currentText())

        host.dispose()
    }

    @Test
    fun askAiCard_returnToQwerty_leavesHostEditorUntouched() {
        val originalText = "未发送的普通文本"
        val simIc = SimulatedEditorInputConnection(originalText)
        val host = FullSimulatedImeHost(
            initialState = ImeUiState(
                tabMode = KeyboardTabMode.VOICE,
                voiceSubPanel = VoiceSubPanel.ASK_AI_CARD,
                askAiQuery = originalText
            ),
            mockConnection = simIc
        )
        val controller = VoiceController(
            host = host,
            audioRecorderProvider = { throw UnsupportedOperationException() },
            onExternalCommitToEngine = {},
            onResetComposing = {}
        )

        controller.returnFromAskAiToQwerty()
        assertEquals(KeyboardTabMode.QWERTY, host.stateFlow.value.tabMode)
        assertEquals(VoiceSubPanel.NONE, host.stateFlow.value.voiceSubPanel)
        assertEquals("Editor text must remain untouched", originalText, simIc.currentText())

        host.dispose()
    }

    // =========================================================================
    // 4. 旧版 == 回车问 AI 彻底下线测试
    // =========================================================================

    @Test
    fun equalEqual_isCompletelyDecommissioned_fromEnterKeyHandling() {
        var legacyCallbackInvoked = false
        val simIc = SimulatedEditorInputConnection("==把下午3点换算成美西时间")
        val host = FullSimulatedImeHost(
            initialState = ImeUiState(),
            mockConnection = simIc
        )
        val typingController = TypingController(host = host)

        // 执行回车：必须不再被 == 拦截！
        typingController.performEnterOrNewline(
            onRunEqualEqualAskAi = { _, _ ->
                legacyCallbackInvoked = true
            }
        )
        assertFalse("Legacy == ask AI callback must NEVER be invoked upon Enter", legacyCallbackInvoked)
        // 验证它触发了向 inputConnection 发送换行或 action
        assertTrue(
            "Must dispatch newline commit or editor action to host",
            simIc.actionsHistory.any { it.contains("commitText('\n')") || it.contains("performEditorAction") }
        )

        host.dispose()
    }

    // =========================================================================
    // 5. 帮写有草稿态：免开口芯片与按住说话成稿的原地替换闭环
    // =========================================================================

    @Test
    fun helpMeWrite_withDraft_replacesEditorTextInPlace_andSupportsUndo() {
        val originalDraft = "草莓熊联名保温杯，保温效果很好"
        val simIc = SimulatedEditorInputConnection(originalDraft)
        val host = FullSimulatedImeHost(
            initialState = ImeUiState(
                tabMode = KeyboardTabMode.VOICE,
                voiceSubPanel = VoiceSubPanel.HELP_ME_WRITE,
                currentEditorText = originalDraft
            ),
            mockConnection = simIc
        )
        val controller = VoiceController(
            host = host,
            audioRecorderProvider = { throw UnsupportedOperationException() },
            onExternalCommitToEngine = {},
            onResetComposing = {}
        )

        val expandedArticle = "🍓终于被我蹲到了！草莓熊联名保温杯颜值直接封顶！实测保热12小时以上，冬天出门喝热水太治愈了✨"

        // 模拟执行 replaceFullEditorTextSafely
        controller.replaceFullEditorTextSafely(
            finalOutput = expandedArticle,
            originalText = originalDraft
        )

        // 验证输入框已被原地替换为成稿，而不是拼在原有草稿后面
        assertEquals("Host text must be replaced with expanded article", expandedArticle, simIc.currentText())
        assertFalse("Must NOT append behind the old draft", simIc.currentText().startsWith(originalDraft + expandedArticle))

        // 验证压入了包含原草稿的 UndoSnapshot
        val undo = host.peekTopUndoSnapshot()
        assertNotNull(undo)
        assertEquals(originalDraft, undo!!.replacedOriginalText)
        assertEquals(expandedArticle.length, undo.insertedTextLength)

        // 验证撤回能够 100% 恢复原草稿
        val undoSuccess = host.triggerUndo()
        assertTrue("Undo must succeed", undoSuccess)
        assertEquals("Original draft must be restored", originalDraft, simIc.currentText())

        host.dispose()
    }

    @Test
    fun helpMeWrite_promptsIncludeEditorDraftContext_bothDualStageAndMultimodalDirect() {
        val draft = "新买的公路车骑了20公里，腿有点酸但很爽"

        // 1. 双阶段管线 Prompt 包含 <editor_draft>
        val (sysPromptDual, userPromptDual) = PromptBuilder.buildHelpMeWritePrompts(
            voiceInstruction = "改写得有骑行博主的感觉，带几个tag",
            style = DefaultPresets.defaultStyles().first(),
            targetAppName = "小红书",
            dictionary = emptyList(),
            surroundingBeforeCursor = "",
            editorDraft = draft
        )
        assertTrue(sysPromptDual.contains("<editor_draft>"))
        assertTrue(userPromptDual.contains("<editor_draft>\n$draft\n</editor_draft>"))
        assertTrue(userPromptDual.contains("改写得有骑行博主的感觉"))

        // 2. 多模态直通管线 Prompt 包含 <editor_draft>
        val multimodalPrompt = PromptBuilder.buildMultimodalDirectPrompt(
            intentMode = VoiceIntentMode.HELP_ME_WRITE,
            selectedText = "",
            translateTargetLanguage = "",
            style = DefaultPresets.defaultStyles().first(),
            targetAppName = "小红书",
            dictionary = emptyList(),
            surroundingBeforeCursor = "",
            editorDraft = draft
        )
        assertTrue(multimodalPrompt.contains("<editor_draft>\n$draft\n</editor_draft>"))
        assertTrue(multimodalPrompt.contains("若提供 <editor_draft>，则以此草稿为核心基础扩写或改写"))
    }

    // =========================================================================
    // 6. Jev 军师五层反装蠢决策树严苛对抗审查
    // =========================================================================

    @Test
    fun jevCopilot_epistemicDecisionTree_adheresStrictlyToAntiStupidityRules() {
        val prompt = PromptBuilder.JEV_COPILOT_SYSTEM_PROMPT

        // 1. 静态常量检查
        val field = PromptBuilder::class.java.getField("JEV_COPILOT_SYSTEM_PROMPT")
        assertTrue("Must be static", Modifier.isStatic(field.modifiers))
        assertTrue("Must be final", Modifier.isFinal(field.modifiers))

        // 2. 二元分流与事实筛子
        assertTrue("Must declare Path E & P dichotomy", prompt.contains("路径 E：认知/技术/观点探讨局"))
        assertTrue(prompt.contains("路径 P：人际/利益/情绪社交局"))
        assertTrue(prompt.contains("事实与专业度筛子"))
        assertTrue(prompt.contains("严禁盲目采信对方的“科普”或暴论"))
        assertTrue(prompt.contains("审视对方是否存在概念偷换、民科类比"))

        // 3. 封杀装蠢反问与陈年黄豆烂梗
        assertTrue(prompt.contains("封杀“装蠢捧哏提问”与“陈年谐音烂梗”"))
        assertTrue(prompt.contains("严禁为了合群而先商业吹捧“这个例子很形象/说得很有道理”再用“所以……？”、“……吧？”装外行好奇宝宝反问递话筒"))
        assertTrue(prompt.contains("严禁滥用“单身狗”式陈年低幼谐音烂梗与 😂/🤣 黄豆表情凑幽默"))

        // 4. 关键策略轴 3 校验：必须是「极客外推 · 逻辑冷幽默」，绝不能是提问/反问！
        assertTrue("Strategy 3 must be Geek Extrapolation", prompt.contains("策略3（极客外推 · 逻辑冷幽默）"))
        assertTrue("Must extrapolate logically", prompt.contains("顺着对方前提做荒谬好玩的逻辑外推造梗"))
        assertFalse(
            "Strategy 3 must NOT be questions or asks",
            prompt.contains("策略3（深度反问") || prompt.contains("策略3（延伸提问")
        )

        // 5. 语言铁律：笃定陈述句，禁用外行请教反问
        assertTrue(prompt.contains("统一以懂行同侪视角用笃定陈述句发言，禁用外行请教反问"))
    }

    // =========================================================================
    // 7. 架构红线与代码行数审查
    // =========================================================================

    @Test
    fun architectureRedline_keyboardScreenLineCountAndPrefixCacheConstants() {
        val root = resolveProjectRoot()
        val screenFile = File(root, "app/src/main/java/com/typefree/ime/ime/KeyboardScreen.kt")
        assertTrue("KeyboardScreen.kt must exist", screenFile.exists())
        val lines = screenFile.readLines().size
        assertTrue(
            "KeyboardScreen.kt line count must be <= 650 lines, actual: $lines",
            lines <= 650
        )

        // 验证 ASK_AI_CARD_SYSTEM_PROMPT 为 100% 静态 const val
        val askAiField = PromptBuilder::class.java.getField("ASK_AI_CARD_SYSTEM_PROMPT")
        assertTrue("ASK_AI_CARD_SYSTEM_PROMPT must be static", Modifier.isStatic(askAiField.modifiers))
        assertTrue("ASK_AI_CARD_SYSTEM_PROMPT must be final", Modifier.isFinal(askAiField.modifiers))
        val askAiPromptVal = askAiField.get(null) as String
        assertTrue(askAiPromptVal.contains("180 字以内"))
    }
}
