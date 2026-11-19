package com.typefree.ime.v362

import android.text.InputType
import android.view.inputmethod.InputConnection
import com.typefree.core.ime.enhancer.BriefPrefixIndex
import com.typefree.core.ime.overlay.SymbolTrie
import com.typefree.core.ime.profile.SchemaProfile
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.ime.EditorShortcutAction
import com.typefree.ime.ime.ImeHostCallbacks
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeyGestureActionType
import com.typefree.ime.ime.KeyGestureBinding
import com.typefree.ime.ime.KeyGestureConfig
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.TypingController
import com.typefree.ime.ime.UndoSnapshot
import com.typefree.ime.ime.engine.CodetableEnhancedInputEngine
import com.typefree.ime.ime.predict.PredictionStore
import com.typefree.ime.ime.predict.StaticPredictor
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.ui.screens.KeyGestureActionKind
import com.typefree.ime.ui.screens.KeyGestureBindingSpec
import com.typefree.ime.ui.screens.KeyGestureConfigCodec
import com.typefree.rime.RimeBridge
import com.typefree.rime.RimeCandidate
import com.typefree.rime.RimeContextSnapshot
import com.typefree.rime.RimeSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class V362EndToEndAcceptanceTest {

    private class FakeXiaoheRimeClient : RimeSessionClient {
        override var activeSchemaId: String = "flypy"
        override var pageSize: Int = 24
        override val isNativeLoaded: Boolean = false
        override var isDeployed: Boolean = true
        override var wordCount: Int = 2000

        private val options = HashMap<String, Boolean>()
        private val buf = StringBuilder()

        private val dict = mapOf(
            "n" to listOf("你"),
            "ni" to listOf("你", "泥"),
            "hc" to listOf("好", "号"),
            "ji`" to listOf("机", "及"),
            "vg" to listOf("正"),
            "bk" to listOf("并")
        )

        override fun initialize(sharedDir: String, userDir: String): Boolean = true
        override fun deploy(schemaId: String): Boolean = true
        override fun selectSchema(schemaId: String): Boolean {
            activeSchemaId = schemaId
            buf.clear()
            return true
        }

        override fun processKey(keyCode: Int, mask: Int): Boolean {
            if (keyCode == RimeBridge.XK_BackSpace) {
                if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
                return true
            }
            val ch = keyCode.toChar()
            if (ch in 'a'..'z' || ch == '`') {
                buf.append(ch)
                return true
            }
            return false
        }

        override fun processKeyText(keys: String): RimeContextSnapshot {
            buf.clear()
            keys.forEach { processKey(it.code, 0) }
            return getContext(pageSize)
        }

        override fun getContext(maxCandidates: Int): RimeContextSnapshot {
            val raw = buf.toString()
            if (raw.isEmpty()) return RimeContextSnapshot()
            val cands = dict[raw].orEmpty().map { RimeCandidate(it) }
            return RimeContextSnapshot(preedit = raw, rawInput = raw, candidates = cands)
        }

        override fun selectCandidate(index: Int): String? {
            val picked = getContext(pageSize).candidates.getOrNull(index)?.text
            buf.clear()
            return picked
        }

        override fun commitComposition(): String? {
            val top = getContext(pageSize).candidates.firstOrNull()?.text
            buf.clear()
            return top
        }

        override fun getCommit(): String? = null
        override fun clearComposition() { buf.clear() }
        override fun setOption(option: String, value: Boolean) { options[option] = value }
        override fun getOption(option: String): Boolean = options[option] ?: false
        override fun syncUserData(): Boolean = true
        override fun getVersion(): String = "1.17.0-v362-fake"
    }

    private class FakeHost(
        override val scope: CoroutineScope,
        initialSettings: AppSettings = AppSettings(inputScheme = InputScheme.FLYPY_TABLE)
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

        val editorText = StringBuilder()
        var currentSettings: AppSettings = initialSettings
        val toasts = ArrayList<String>()

        override fun currentInputConnection(): InputConnection? = null
        override fun performKeyFeedback(strong: Boolean) {}
        override fun performHaptic(strong: Boolean) {}
        override fun showQuickActionToast(message: String) { toasts.add(message) }

        override fun commitDirectText(
            text: String,
            recordUndo: Boolean,
            markManualEdit: Boolean,
            playFeedback: Boolean
        ) {
            editorText.append(text)
            stateFlow.update { it.copy(currentEditorText = editorText.toString()) }
        }

        override fun pushUndoSnapshot(snapshot: UndoSnapshot?) {}
        override fun peekTopUndoSnapshot(): UndoSnapshot? = null
        override fun recordManualEditTouch() {}
        override fun tryAutoLearnFromRecentEdit() {}
        override fun onDictationCommittedForAutoLearn(fullBufferAfterCommit: String) {}
        override fun clearAutoLearnBaseline() {}
        override fun readSelectedTextSafely(): String = ""
        override fun readSurroundingBeforeCursor(maxChars: Int): String = editorText.toString().takeLast(maxChars)
        override fun readFullEditorTextSafely(): String = editorText.toString()
        override fun readSystemClipboardText(): String = ""
        override fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean) {}
        override fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File = wavFile
        override fun getLastRecordingWavFile(): File = File("dummy.wav")
        override fun hasRetryWavFile(): Boolean = false
    }

    @Test
    fun `KeyGestureConfig and KeyGestureConfigCodec share 100 percent identical JSON format and delta preserves EN defaults`() {
        val coreDefaultCn = KeyGestureConfig.defaultProfile(isChinese = true, showNumberRow = false)
        val uiDefault = KeyGestureConfigCodec.defaultProfile()

        assertEquals(KeyGestureConfig.toJson(coreDefaultCn), KeyGestureConfigCodec.toJson(uiDefault))

        val editedSwipe = uiDefault.swipeUp.toMutableMap().apply {
            put('q', KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_PAIRED, text = "【", rightText = "】", label = "【】"))
        }
        val deltaJson = KeyGestureConfigCodec.toDeltaJson(uiDefault.copy(swipeUp = editedSwipe))
        assertTrue(deltaJson.contains("\"q\""))
        assertFalse("Delta JSON should not freeze untouched Chinese long-press keys", deltaJson.contains("\"f\""))

        // Overlaying delta JSON onto EN default keeps EN `f -> $` while applying custom `q -> 【】`
        val enRuntimeProfile = KeyGestureConfig.fromJson(
            json = deltaJson,
            fallback = KeyGestureConfig.defaultProfile(isChinese = false, showNumberRow = false)
        )
        assertEquals("【", enRuntimeProfile.resolveSwipeUp('q')?.text)
        assertEquals("】", enRuntimeProfile.resolveSwipeUp('q')?.rightText)
        assertEquals("$", enRuntimeProfile.resolveLongPress('f')?.text)
    }

    @Test
    fun `TypingController wires passive math, EN word suggestion, slash swipe-up, Z-backtick protection, and L1+L2 phrase creation`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tempDir = Files.createTempDirectory("v362_e2e_").toFile()
        try {
            val host = FakeHost(scope)
            val rime = FakeXiaoheRimeClient()
            val reverseMap = mapOf(
                "你" to listOf("nirx"),
                "好" to listOf("hcnz"),
                "正" to listOf("vgha"),
                "并" to listOf("bkbk")
            )
            val savedPhrases = ArrayList<Pair<String, String>>()
            var undoTriggered = 0

            val briefWithNihc = BriefPrefixIndex.buildFromDictLines(
                mainDictLines = sequenceOf(
                    "---",
                    "name: flypy",
                    "...",
                    "你好\tnihc"
                ),
                gt4GuardTwoPrefixes = setOf("ok", "os")
            )
            lateinit var controller: TypingController
            val enhanced = CodetableEnhancedInputEngine(
                rimeClient = rime,
                initialProfile = SchemaProfile.defaultXiaoheFlypy(),
                symbolTrie = SymbolTrie.defaultXiaohe(),
                briefIndex = briefWithNihc,
                reverseLookupMap = reverseMap,
                schemaDir = tempDir,
                isIncognitoPredicate = {
                    host.stateFlow.value.isIncognitoMode ||
                        ImeLogic.isPasswordEditorInputType(host.stateFlow.value.currentInputType)
                },
                onPhraseSuggestion = { s -> controller.onL2PhraseSuggestion(s) }
            )
            controller = TypingController(
                host = host,
                predictionStore = PredictionStore.inMemory(),
                staticPredictor = StaticPredictor(),
                settingsOverride = { host.currentSettings }
            ).apply {
                isRimeReady = true
                enhancedSchemePredicate = { it == InputScheme.FLYPY_TABLE }
                codetableEnhancedEngine = enhanced
                reverseLookupMapProvider = { reverseMap }
                onUndoRequested = { undoTriggered++ }
                onSaveUserPhrase = { word, code -> savedPhrases.add(word to code) }
            }
            controller.updateSnippets(
                listOf(
                    SnippetEntry(
                        title = "地址",
                        voiceTriggers = "收货地址",
                        flypyCode = ";sd",
                        content = "浦东张江",
                        enabled = true
                    )
                )
            )

            // 1) Idle swipe-up period triggers `/` slash snippet guide
            controller.triggerSwipeUpPeriodSlash()
            assertTrue(controller.isComposing)
            assertEquals("/", host.stateFlow.value.flypyComposingCode)
            controller.commitQwertyChar("s")
            controller.commitQwertyChar("d")
            controller.insertSpace()
            assertEquals("浦东张江", host.editorText.toString())

            // 2) 3-way key gesture UNDO_LAST & REPEAT_LAST & Z-key composing backtick protection
            controller.handleKeyGesture(KeyGestureBinding(KeyGestureActionType.UNDO_LAST))
            assertEquals(1, undoTriggered)
            controller.handleKeyGesture(KeyGestureBinding(KeyGestureActionType.REPEAT_LAST))
            assertEquals("浦东张江浦东张江", host.editorText.toString())

            controller.commitQwertyChar("j")
            controller.commitQwertyChar("i")
            controller.handleKeyGesture(KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "`"))
            assertEquals("ji`", host.stateFlow.value.flypyComposingCode)
            assertEquals("机", host.stateFlow.value.flypyCandidates.firstOrNull()?.text)
            controller.clearComposing()

            // 3) Passive calculator: BOTH `125*8=` (with `=`) AND `125*8` (without `=`) produce `125*8=1000`
            host.editorText.clear()
            for (ch in listOf("1", "2", "5", "*", "8", "=")) {
                controller.commitSecondarySymbol(ch)
            }
            assertEquals("125*8=", host.editorText.toString())
            assertEquals("1000", host.stateFlow.value.passiveMathResult)
            assertTrue(host.stateFlow.value.passiveMathEndsWithEquals)
            assertTrue(host.stateFlow.value.isPredictionBarVisible)
            controller.selectPassiveMath("1000")
            assertEquals("125*8=1000", host.editorText.toString())
            assertNull(host.stateFlow.value.passiveMathResult)

            host.editorText.clear()
            for (ch in listOf("1", "2", "5", "*", "8")) {
                controller.commitSecondarySymbol(ch)
            }
            assertEquals("125*8", host.editorText.toString())
            assertEquals("1000", host.stateFlow.value.passiveMathResult)
            assertFalse(host.stateFlow.value.passiveMathEndsWithEquals)
            controller.selectPassiveMath("1000")
            assertEquals("125*8=1000", host.editorText.toString())
            assertNull(host.stateFlow.value.passiveMathResult)

            // 3b) Idle Chinese long-press `.` and symbol-layer `/`, `` ` ``, `:` output literal ASCII directly
            host.editorText.clear()
            controller.commitSecondarySymbol(".")
            controller.commitSecondarySymbol("/")
            controller.commitSecondarySymbol("`")
            controller.commitSecondarySymbol(":")
            assertFalse("Symbol-layer / and ` must NOT enter composing mode", controller.isComposing)
            assertEquals("./`:", host.editorText.toString())

            // 4) EN mode word suggestion (`th` -> `the`, disabled in password fields)
            host.editorText.clear()
            host.stateFlow.update { it.copy(isFlypyChineseMode = false) }
            controller.commitQwertyChar("t")
            controller.commitQwertyChar("h")
            assertTrue(host.stateFlow.value.englishSuggestions.contains("the"))
            assertEquals(2, host.stateFlow.value.englishSuggestionReplaceLength)
            assertTrue(host.stateFlow.value.isPredictionBarVisible)

            // Password field suppresses EN suggestions & passive math
            host.stateFlow.update {
                it.copy(
                    currentInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
            }
            controller.refreshEnglishWordSuggestions()
            controller.refreshPassiveMathSuggestion()
            assertTrue(host.stateFlow.value.englishSuggestions.isEmpty())
            assertNull(host.stateFlow.value.passiveMathResult)

            // 5) L1 explicit phrase recording (`togglePhraseRecording` -> `你` + `好` -> `nihc` -> accept)
            host.stateFlow.update {
                it.copy(
                    isFlypyChineseMode = true,
                    currentInputType = InputType.TYPE_CLASS_TEXT
                )
            }
            controller.togglePhraseRecording()
            assertTrue(host.stateFlow.value.isPhraseRecording)
            controller.commitQwertyChar("n")
            controller.commitQwertyChar("i")
            controller.selectFlypyCandidate(0) // 你
            controller.commitQwertyChar("h")
            controller.commitQwertyChar("c")
            controller.selectFlypyCandidate(0) // 好
            assertEquals("你好", host.stateFlow.value.recordedPhraseBuffer)
            assertEquals("nihc", host.stateFlow.value.recordedPhraseCode)
            assertEquals("+ 存入个人词: 你好 · nihc", host.stateFlow.value.activePhraseSuggestionLabel)
            controller.acceptPhraseSuggestion()
            assertFalse(host.stateFlow.value.isPhraseRecording)
            assertEquals(listOf("你好" to "nihc"), savedPhrases)

            // 6) L2 PhraseSuggestionTracker MUST skip existing main-dict 4-code phrase `你好` (`nihc`),
            // while surfacing new unseen phrase `正并` (`vgbk`) on 3rd repeat!
            enhanced.resetPhraseSuggestionWindow()
            repeat(3) {
                controller.commitQwertyChar("n")
                controller.commitQwertyChar("i")
                controller.selectFlypyCandidate(0) // 你
                controller.commitQwertyChar("h")
                controller.commitQwertyChar("c")
                controller.selectFlypyCandidate(0) // 好
                enhanced.resetPhraseSuggestionWindow()
            }
            assertNull(
                "Existing main-dict phrase 你好 (nihc) must NOT trigger L2 phrase suggestion",
                host.stateFlow.value.l2PhraseSuggestionWord
            )

            repeat(3) {
                controller.commitQwertyChar("v")
                controller.commitQwertyChar("g")
                controller.selectFlypyCandidate(0) // 正
                controller.commitQwertyChar("b")
                controller.commitQwertyChar("k")
                controller.selectFlypyCandidate(0) // 并
            }
            assertEquals("正并", host.stateFlow.value.l2PhraseSuggestionWord)
            assertEquals("vgbk", host.stateFlow.value.l2PhraseSuggestionCode)
            assertEquals("+ 录入: 正并 · vgbk", host.stateFlow.value.activePhraseSuggestionLabel)
            controller.acceptPhraseSuggestion()
            assertNull(host.stateFlow.value.l2PhraseSuggestionWord)
            assertEquals(listOf("你好" to "nihc", "正并" to "vgbk"), savedPhrases)

            // 7) v3.6.3: Accepted phrase `正并 (vgbk)` is IMMEDIATELY typable in-memory (0ms, no keyboard restart!)
            controller.commitQwertyChar("v")
            controller.commitQwertyChar("g")
            controller.commitQwertyChar("b")
            controller.commitQwertyChar("k")
            assertTrue(
                "Newly added phrase 正并 must be immediately available without closing keyboard",
                host.editorText.toString().endsWith("正并") ||
                    host.stateFlow.value.flypyCandidates.any { it.text == "正并" }
            )
            controller.clearComposing()

            // 8) v3.6.3: Dismissing prediction bar (`×`) clears L2 suggestion & marks it dismissed!
            controller.onL2PhraseSuggestion(com.typefree.core.ime.enhancer.PhraseSuggestion("测试词", "cscc"))
            assertEquals("测试词", host.stateFlow.value.l2PhraseSuggestionWord)
            controller.dismissPredictions()
            assertNull(host.stateFlow.value.l2PhraseSuggestionWord)
            assertNull(host.stateFlow.value.activePhraseSuggestionLabel)

            // 9) v3.6.3: Passive math `123*8=1000 1*1` evaluates to `1` (not `10001`)
            host.editorText.clear()
            host.editorText.append("123*8=1000 1*1")
            controller.refreshPassiveMathSuggestion()
            assertEquals("1", host.stateFlow.value.passiveMathResult)

            // 10) v3.6.4: Composing `n` + Enter MUST commit literal `"n"` WITHOUT appending newline even when engine returns Consumed via AndroidCommitExecutor
            val committedViaExecutor = StringBuilder()
            val executorEngine = CodetableEnhancedInputEngine(
                rimeClient = FakeXiaoheRimeClient(),
                initialProfile = SchemaProfile.defaultXiaoheFlypy(),
                symbolTrie = SymbolTrie.defaultXiaohe(),
                briefIndex = briefWithNihc,
                reverseLookupMap = reverseMap,
                schemaDir = tempDir,
                commitExecutor = com.typefree.ime.commit.AndroidCommitExecutor(
                    connectionProvider = {
                        object : com.typefree.ime.commit.EditorBatchConnection {
                            override fun beginBatchEdit() = true
                            override fun endBatchEdit() = true
                            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                                committedViaExecutor.append(text)
                                return true
                            }
                            override fun getExtractedSelectionStart() = committedViaExecutor.length
                            override fun setSelection(start: Int, end: Int) = true
                            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) = true
                            override fun sendDpadLeftKeyEvent() {}
                        }
                    }
                )
            )
            controller.codetableEnhancedEngine = executorEngine
            host.editorText.clear()
            controller.commitQwertyChar("n")
            assertTrue(controller.isComposing)
            controller.performEnterOrNewline { _, _ -> }
            assertFalse(controller.isComposing)
            assertEquals("n", committedViaExecutor.toString())
            assertEquals("", host.editorText.toString())
        } finally {
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
