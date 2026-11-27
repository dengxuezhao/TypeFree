package com.typefree.ime.v391

import android.os.Build
import android.text.InputType
import android.view.inputmethod.InputConnection
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.CandidateFontScale
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.SpaceLongPressAction
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.ime.ImeHostCallbacks
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeyboardMetrics
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.T9_IDLE_PUNCTUATIONS
import com.typefree.ime.ime.TypingController
import com.typefree.ime.ime.UndoSnapshot
import com.typefree.ime.ime.VoicePhase
import com.typefree.ime.ime.autofill.InlineAutofillController
import com.typefree.ime.ime.autofill.InlineSuggestionDescriptor
import com.typefree.ime.ime.autofill.InlineSuggestionKind
import com.typefree.ime.ime.engine.EngineResult
import com.typefree.ime.ime.engine.EngineState
import com.typefree.ime.ime.engine.InputEngine
import com.typefree.ime.ime.engine.KeyInput
import com.typefree.ime.ime.flypy.FlypyCandidate
import com.typefree.ime.network.AiPipelineEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class V391ImeInteractionsAndT9Test {

    private class BufferInputConnectionHost(
        override val scope: CoroutineScope,
        initialSettings: AppSettings = AppSettings(inputScheme = InputScheme.RIME_T9_PINYIN)
    ) : ImeHostCallbacks {
        override val repository: AppRepository
            get() = throw UnsupportedOperationException("Uses settingsOverride")
        override val aiEngine: AiPipelineEngine = AiPipelineEngine()
        override val stateFlow = MutableStateFlow(
            ImeUiState(
                tabMode = KeyboardTabMode.QWERTY,
                isFlypyChineseMode = true,
                activeInputScheme = initialSettings.inputScheme
            )
        )

        val buffer = StringBuilder()
        var currentSettings: AppSettings = initialSettings
        val undoStack = ArrayDeque<UndoSnapshot>()

        private val proxyIc: InputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)
        ) { _, method, args ->
            when (method.name) {
                "beginBatchEdit", "endBatchEdit" -> true
                "getTextBeforeCursor" -> {
                    val n = (args?.getOrNull(0) as? Int ?: 0).coerceAtLeast(0)
                    buffer.toString().takeLast(n)
                }
                "getTextAfterCursor" -> ""
                "getSelectedText" -> ""
                "deleteSurroundingText" -> {
                    val beforeLen = (args?.getOrNull(0) as? Int ?: 0).coerceIn(0, buffer.length)
                    if (beforeLen > 0) {
                        buffer.delete(buffer.length - beforeLen, buffer.length)
                    }
                    stateFlow.update { it.copy(currentEditorText = buffer.toString()) }
                    true
                }
                "commitText" -> {
                    val text = args?.getOrNull(0)?.toString().orEmpty()
                    buffer.append(text)
                    stateFlow.update { it.copy(currentEditorText = buffer.toString()) }
                    true
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    else -> null
                }
            }
        } as InputConnection

        override fun currentInputConnection(): InputConnection = proxyIc
        override fun performKeyFeedback(strong: Boolean) {}
        override fun performHaptic(strong: Boolean) {}
        override fun showQuickActionToast(message: String) {}

        override fun commitDirectText(
            text: String,
            recordUndo: Boolean,
            markManualEdit: Boolean,
            playFeedback: Boolean
        ) {
            buffer.append(text)
            if (recordUndo && text.isNotEmpty()) {
                val snap = UndoSnapshot(replacedOriginalText = "", insertedTextLength = text.length)
                pushUndoSnapshot(snap)
            }
            stateFlow.update {
                it.copy(
                    currentEditorText = buffer.toString(),
                    undoSnapshot = undoStack.firstOrNull()
                )
            }
        }

        override fun pushUndoSnapshot(snapshot: UndoSnapshot?) {
            if (snapshot != null) undoStack.addFirst(snapshot)
        }

        override fun peekTopUndoSnapshot(): UndoSnapshot? = undoStack.firstOrNull()

        override fun replaceTopUndoInsertedLength(prevInsertedLength: Int, newInsertedLength: Int) {
            val top = undoStack.firstOrNull() ?: return
            if (top.insertedTextLength == prevInsertedLength) {
                undoStack.removeFirst()
                val updated = top.copy(insertedTextLength = newInsertedLength)
                undoStack.addFirst(updated)
                stateFlow.update { it.copy(undoSnapshot = updated) }
            }
        }

        override fun recordManualEditTouch() {}
        override fun tryAutoLearnFromRecentEdit() {}
        override fun onDictationCommittedForAutoLearn(fullBufferAfterCommit: String) {}
        override fun clearAutoLearnBaseline() {}
        override fun readSelectedTextSafely(): String = ""
        override fun readSurroundingBeforeCursor(maxChars: Int): String = buffer.toString().takeLast(maxChars)
        override fun readFullEditorTextSafely(): String = buffer.toString()
        override fun readSystemClipboardText(): String = ""
        override fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean) {}
        override fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File = wavFile
        override fun getLastRecordingWavFile(): File = File("dummy.wav")
        override fun hasRetryWavFile(): Boolean = false
    }

    private class ThreeCandidateFakeEngine : InputEngine {
        private val _state = MutableStateFlow(EngineState())
        override val state: StateFlow<EngineState> = _state
        var requestedMoreMinCount: Int = 0

        override fun onKey(key: KeyInput): EngineResult {
            if (key is KeyInput.CharKey) {
                val nextCode = _state.value.rawCode + key.char
                _state.value = EngineState(
                    rawCode = nextCode,
                    preedit = nextCode,
                    candidates = listOf(
                        FlypyCandidate("测试一", "cs"),
                        FlypyCandidate("测试二", "cs"),
                        FlypyCandidate("测试三", "cs")
                    ),
                    hasMore = false
                )
                return EngineResult.Consumed
            }
            return EngineResult.PassThrough
        }

        override fun select(index: Int): EngineResult {
            val picked = _state.value.candidates.getOrNull(index)?.text.orEmpty()
            clear()
            return EngineResult.Commit(picked)
        }

        override fun requestMoreCandidates(minCount: Int) {
            requestedMoreMinCount = minCount
        }

        override fun clear() {
            _state.value = EngineState()
        }

        override fun flush(): String {
            val top = _state.value.candidates.firstOrNull()?.text.orEmpty()
            clear()
            return top
        }

        override fun setIncognitoMode(incognito: Boolean) {}
        override fun setFixedCandidateOrder(fixedOrder: Boolean?) {}
    }

    @Test
    fun `spacebar VOICE_HOLD vs TOGGLE_LANGUAGE, 5-key swipe-up and Shift language fallbacks, and clean idle labels`() {
        // 1. Default SpaceLongPressAction is VOICE_HOLD (240ms), while legacy TOGGLE_LANGUAGE stays 420ms
        val defaults = AppSettings()
        assertEquals(SpaceLongPressAction.VOICE_HOLD, defaults.spaceLongPressAction)
        assertEquals(240L, ImeLogic.resolveSpaceHoldDelayMs(SpaceLongPressAction.VOICE_HOLD))
        assertEquals(420L, ImeLogic.resolveSpaceHoldDelayMs(SpaceLongPressAction.TOGGLE_LANGUAGE))

        // 2. 5-key bottom row swipe-up on spacebar toggles language ONLY when fiveKeyBottomRow && VOICE_HOLD
        val thresholdPx = 36f // 18.dp @ 2x
        assertTrue(
            ImeLogic.shouldSwipeUpSpaceToggleLanguage(
                fiveKeyBottomRow = true,
                spaceLongPressAction = SpaceLongPressAction.VOICE_HOLD,
                dxPx = 5f,
                dyPx = -42f,
                thresholdPx = thresholdPx
            )
        )
        assertFalse(
            "6-key bottom row already has a dedicated [中/英] key so swipe-up on spacebar stays off",
            ImeLogic.shouldSwipeUpSpaceToggleLanguage(
                fiveKeyBottomRow = false,
                spaceLongPressAction = SpaceLongPressAction.VOICE_HOLD,
                dxPx = 5f,
                dyPx = -42f,
                thresholdPx = thresholdPx
            )
        )
        assertFalse(
            "TOGGLE_LANGUAGE mode uses 420ms long-press instead of swipe-up",
            ImeLogic.shouldSwipeUpSpaceToggleLanguage(
                fiveKeyBottomRow = true,
                spaceLongPressAction = SpaceLongPressAction.TOGGLE_LANGUAGE,
                dxPx = 5f,
                dyPx = -42f,
                thresholdPx = thresholdPx
            )
        )
        assertFalse(
            "Horizontal cursor slide must NOT falsely trigger 5-key swipe-up language switch",
            ImeLogic.shouldSwipeUpSpaceToggleLanguage(
                fiveKeyBottomRow = true,
                spaceLongPressAction = SpaceLongPressAction.VOICE_HOLD,
                dxPx = 50f,
                dyPx = -40f,
                thresholdPx = thresholdPx
            )
        )

        // 3. 5-key left Shift tap toggles Chinese -> English ONLY when idle in Chinese mode (never steals Clear Code or EN Shift)
        assertTrue(
            ImeLogic.shouldFiveKeyShiftTapToggleLanguage(
                fiveKeyBottomRow = true,
                isFlypyChineseMode = true,
                hasActiveFlypyComposing = false,
                isSymbolLayer = false
            )
        )
        assertFalse(
            "Composing state must keep left Shift as [清码]",
            ImeLogic.shouldFiveKeyShiftTapToggleLanguage(
                fiveKeyBottomRow = true,
                isFlypyChineseMode = true,
                hasActiveFlypyComposing = true,
                isSymbolLayer = false
            )
        )
        assertFalse(
            "English mode must keep left Shift as uppercase Shift/CapsLock",
            ImeLogic.shouldFiveKeyShiftTapToggleLanguage(
                fiveKeyBottomRow = true,
                isFlypyChineseMode = false,
                hasActiveFlypyComposing = false,
                isSymbolLayer = false
            )
        )
        assertFalse(
            ImeLogic.shouldFiveKeyShiftTapToggleLanguage(
                fiveKeyBottomRow = false,
                isFlypyChineseMode = true,
                hasActiveFlypyComposing = false,
                isSymbolLayer = false
            )
        )

        // 4. Clean minimal spacebar labels: never clutter idle label with "按住说话" or "↑切EN"
        val idle6KeyCn = ImeLogic.resolveQwertySpaceBarLabel(
            lang = AppLang.ZH_CN,
            isSpaceVoiceActive = false,
            isCursorSliding = false,
            hasComposing = false,
            isFlypyChineseMode = true,
            fiveKeyBottomRow = false,
            resolvedChineseSchemeName = "雾凇全拼"
        )
        assertEquals("雾凇全拼", idle6KeyCn)
        assertFalse(idle6KeyCn.contains("按住说话"))
        assertFalse(idle6KeyCn.contains("切EN"))

        val idle5KeyCn = ImeLogic.resolveQwertySpaceBarLabel(
            lang = AppLang.ZH_CN,
            isSpaceVoiceActive = false,
            isCursorSliding = false,
            hasComposing = false,
            isFlypyChineseMode = true,
            fiveKeyBottomRow = true,
            resolvedChineseSchemeName = "小鹤音形"
        )
        assertEquals("小鹤音形 · 中", idle5KeyCn)

        val activeVoiceZh = ImeLogic.resolveQwertySpaceBarLabel(
            lang = AppLang.ZH_CN,
            isSpaceVoiceActive = true,
            isCursorSliding = false,
            hasComposing = false,
            isFlypyChineseMode = true,
            fiveKeyBottomRow = false,
            resolvedChineseSchemeName = "雾凇全拼"
        )
        assertEquals("🎙 聆听中 · 松手上屏", activeVoiceZh)

        val activeVoiceEn = ImeLogic.resolveQwertySpaceBarLabel(
            lang = AppLang.EN,
            isSpaceVoiceActive = true,
            isCursorSliding = false,
            hasComposing = false,
            isFlypyChineseMode = false,
            fiveKeyBottomRow = false,
            resolvedChineseSchemeName = "Rime"
        )
        assertEquals("🎙 Listening · Release", activeVoiceEn)
    }

    @Test
    fun `T9_IDLE_PUNCTUATIONS puts at-sign in top 3 and T9 Key 1 cycles punctuations within 1200ms and supports top-strip replace`() {
        // 1. `@` is at index 2 (3rd slot: `，`, `。`, `@`) so it is immediately visible on screen 1 of the left rail
        assertEquals(listOf("，", "。", "@"), T9_IDLE_PUNCTUATIONS.take(3).map { it.first })
        assertNull("`@` must be a direct single symbol (not paired)", T9_IDLE_PUNCTUATIONS[2].second)
        assertEquals(listOf("，", "。", "@", "？", "！", "、", "：", "；"), ImeLogic.t9QuickPunctuationItems)

        // 2. Multi-tap Key 1 within 1200ms cycles `， -> 。 -> @ -> ？ -> ！` in-place
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var nowMs = 1_000L
            val host = BufferInputConnectionHost(scope)
            val controller = TypingController(
                host = host,
                settingsOverride = { host.currentSettings },
                clock = { nowMs }
            )

            host.commitDirectText("你好", recordUndo = true, markManualEdit = true, playFeedback = false)
            assertEquals("你好", host.buffer.toString())

            // Tap 1 at t=1000ms -> commits "，" and opens quick punctuation bar
            controller.handleT9Key1IdleTap()
            assertEquals("你好，", host.buffer.toString())
            assertTrue(host.stateFlow.value.isT9QuickPunctuationOpen)
            assertEquals("，", host.stateFlow.value.activeT9CycleSymbol)

            // Tap 2 at t=1350ms (+350ms <= 1200ms) -> replaces "，" with "。"
            nowMs = 1_350L
            controller.handleT9Key1IdleTap()
            assertEquals("你好。", host.buffer.toString())
            assertEquals("。", host.stateFlow.value.activeT9CycleSymbol)

            // Tap 3 at t=1700ms (+350ms <= 1200ms) -> replaces "。" with "@"
            nowMs = 1_700L
            controller.handleT9Key1IdleTap()
            assertEquals("你好@", host.buffer.toString())
            assertEquals("@", host.stateFlow.value.activeT9CycleSymbol)

            // Tap 4 & Tap 5 -> "？" -> "！"
            nowMs = 2_000L
            controller.handleT9Key1IdleTap()
            assertEquals("你好？", host.buffer.toString())
            nowMs = 2_300L
            controller.handleT9Key1IdleTap()
            assertEquals("你好！", host.buffer.toString())

            // Tap 6 within 1200ms wraps back to "，"
            nowMs = 2_600L
            controller.handleT9Key1IdleTap()
            assertEquals("你好，", host.buffer.toString())

            // Waiting > 1200ms (t=3900ms, +1300ms) commits a fresh "，" instead of replacing the previous one
            nowMs = 3_900L
            controller.handleT9Key1IdleTap()
            assertEquals("你好，，", host.buffer.toString())
            assertTrue(host.stateFlow.value.isT9QuickPunctuationOpen)

            // 3. Tapping `@` in the top-bar T9 quick punctuation strip replaces the just-typed `，` with `@` and closes strip
            controller.selectT9QuickPunctuation("@")
            assertEquals("你好，@", host.buffer.toString())
            assertFalse(host.stateFlow.value.isT9QuickPunctuationOpen)
            assertNull(host.stateFlow.value.activeT9CycleSymbol)

            // 4. Intermediate edit (e.g. Backspace or Space) dismisses strip cleanly
            nowMs = 5_000L
            controller.handleT9Key1IdleTap()
            assertEquals("你好，@，", host.buffer.toString())
            assertTrue(host.stateFlow.value.isT9QuickPunctuationOpen)
            controller.deleteBackward()
            assertEquals("你好，@", host.buffer.toString())
            assertFalse("Backspace must dismiss T9 quick punctuation strip", host.stateFlow.value.isT9QuickPunctuationOpen)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `candidate expand button threshold lowers from 4 to 2 at LARGE and EXTRA_LARGE font scales`() {
        // Pure threshold invariants
        assertEquals(4, KeyboardMetrics.expandButtonCandidateThreshold(CandidateFontScale.STANDARD))
        assertEquals(2, KeyboardMetrics.expandButtonCandidateThreshold(CandidateFontScale.LARGE))
        assertEquals(2, KeyboardMetrics.expandButtonCandidateThreshold(CandidateFontScale.EXTRA_LARGE))

        // 3 or 4 candidates: hidden at STANDARD, visible at LARGE and EXTRA_LARGE!
        for (count in listOf(3, 4)) {
            assertFalse(
                KeyboardMetrics.shouldShowCandidateExpandButton(
                    candidateCount = count,
                    hasMoreCandidates = false,
                    scale = CandidateFontScale.STANDARD
                )
            )
            assertTrue(
                KeyboardMetrics.shouldShowCandidateExpandButton(
                    candidateCount = count,
                    hasMoreCandidates = false,
                    scale = CandidateFontScale.LARGE
                )
            )
            assertTrue(
                KeyboardMetrics.shouldShowCandidateExpandButton(
                    candidateCount = count,
                    hasMoreCandidates = false,
                    scale = CandidateFontScale.EXTRA_LARGE
                )
            )
        }
        // 5 candidates: visible across all scales
        assertTrue(KeyboardMetrics.shouldShowCandidateExpandButton(5, false, CandidateFontScale.STANDARD))
        // 2 candidates without more: hidden across all scales
        assertFalse(KeyboardMetrics.shouldShowCandidateExpandButton(2, false, CandidateFontScale.EXTRA_LARGE))
        // hasMoreCandidates = true always exposes expand button
        assertTrue(KeyboardMetrics.shouldShowCandidateExpandButton(2, true, CandidateFontScale.STANDARD))

        // Expanded grid font & row height scaling
        assertEquals(15.0f, KeyboardMetrics.expandedGridFontSizeSpForScale(CandidateFontScale.STANDARD), 0.001f)
        assertEquals(17.5f, KeyboardMetrics.expandedGridFontSizeSpForScale(CandidateFontScale.LARGE), 0.001f)
        assertEquals(20.0f, KeyboardMetrics.expandedGridFontSizeSpForScale(CandidateFontScale.EXTRA_LARGE), 0.001f)
        assertEquals(38, KeyboardMetrics.expandedGridRowHeightDpForScale(CandidateFontScale.STANDARD))
        assertEquals(42, KeyboardMetrics.expandedGridRowHeightDpForScale(CandidateFontScale.LARGE))
        assertEquals(48, KeyboardMetrics.expandedGridRowHeightDpForScale(CandidateFontScale.EXTRA_LARGE))

        // TypingController preserves expanded candidate sheet for 3 candidates when CandidateFontScale == EXTRA_LARGE
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val host = BufferInputConnectionHost(
                scope = scope,
                initialSettings = AppSettings(
                    inputScheme = InputScheme.RIME_PINYIN,
                    candidateFontScale = CandidateFontScale.EXTRA_LARGE
                )
            )
            val controller = TypingController(
                host = host,
                settingsOverride = { host.currentSettings }
            )
            val threeCandEngine = ThreeCandidateFakeEngine()
            controller.rimeInputEngine = threeCandEngine
            controller.isRimeReady = true

            controller.commitQwertyChar("c")
            assertEquals(3, host.stateFlow.value.flypyCandidates.size)
            controller.toggleFlypyCandidateSheet()
            assertTrue(
                "3 candidates at EXTRA_LARGE font scale (> 2) must allow expanding the candidate grid sheet",
                host.stateFlow.value.isFlypyCandidateSheetExpanded
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `top bar prediction strip yields cleanly to Android 11 inline autofill, T9 quick strip, and in-place voice`() {
        val baseWithPredictions = ImeUiState(
            tabMode = KeyboardTabMode.QWERTY,
            isFlypyChineseMode = true,
            predictionCandidates = listOf(FlypyCandidate("下午", ""))
        )
        assertTrue(baseWithPredictions.isPredictionBarVisible)

        // 1. Yields when T9 quick punctuation strip is open
        assertFalse(
            baseWithPredictions.copy(isT9QuickPunctuationOpen = true).isPredictionBarVisible
        )

        // 2. Yields when in-place voice recording or processing is active
        assertFalse(
            baseWithPredictions.copy(voicePhase = VoicePhase.Recording(isHandsFree = false)).isPredictionBarVisible
        )
        assertFalse(
            baseWithPredictions.copy(voicePhase = VoicePhase.Processing("转写中")).isPredictionBarVisible
        )

        // 3. Yields when Android 11+ InlineAutofillController has visible suggestions, and restores on dismiss
        val autofill = InlineAutofillController()
        autofill.onStartInputSession(
            sdkInt = Build.VERSION_CODES.R,
            inputType = InputType.TYPE_CLASS_NUMBER
        )
        val descriptor = InlineSuggestionDescriptor(index = 0, kind = InlineSuggestionKind.SUGGESTION)
        val sessionId = autofill.beginResponseInflation(listOf(descriptor))
        autofill.onSuggestionInflated(
            sessionId = sessionId,
            descriptor = descriptor,
            view = null,
            widthPx = 220,
            heightPx = 87,
            inflationSucceeded = true
        )
        assertTrue(autofill.state.value.hasVisibleSuggestions)
        assertFalse(
            baseWithPredictions.copy(inlineAutofillState = autofill.state.value).isPredictionBarVisible
        )

        autofill.dismissCurrentSuggestions()
        assertFalse(autofill.state.value.hasVisibleSuggestions)
        assertTrue(
            baseWithPredictions.copy(inlineAutofillState = autofill.state.value).isPredictionBarVisible
        )
    }

    @Test
    fun `method xml declares supportsInlineSuggestions for Android 11 plus IMMS`() {
        val candidates = listOf(
            java.io.File("src/main/res/xml/method.xml"),
            java.io.File("app/src/main/res/xml/method.xml")
        )
        val methodXml = candidates.firstOrNull { it.exists() }
        org.junit.Assert.assertNotNull("method.xml must exist", methodXml)
        val xmlText = methodXml!!.readText()
        assertTrue(
            "method.xml must declare android:supportsInlineSuggestions=\"true\"",
            xmlText.contains("android:supportsInlineSuggestions=\"true\"")
        )
    }
}
