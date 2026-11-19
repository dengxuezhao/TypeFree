package com.typefree.ime.ime

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import kotlin.math.abs
import com.typefree.core.ime.enhancer.CodetableCodeDeriver
import com.typefree.core.ime.enhancer.PhraseSuggestion
import com.typefree.core.ime.enhancer.SafeMathEvaluator
import com.typefree.core.ime.enhancer.Utf8Safety
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SnippetMatcher
import com.typefree.ime.ime.engine.CodetableEnhancedInputEngine
import com.typefree.ime.ime.engine.EngineResult
import com.typefree.ime.ime.engine.FlypyInputEngine
import com.typefree.ime.ime.engine.InputEngine
import com.typefree.ime.ime.engine.KeyInput
import com.typefree.ime.ime.flypy.FlypyDictionary
import com.typefree.ime.ime.predict.AiSentenceCompleter
import com.typefree.ime.ime.predict.EnglishWordSuggester
import com.typefree.ime.ime.predict.PassiveCalculatorHelper
import com.typefree.ime.ime.predict.PredictionCoordinator
import com.typefree.ime.ime.predict.PredictionStore
import com.typefree.ime.ime.predict.StaticPredictor
import com.typefree.ime.network.AiPipelineEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

interface ImeHostCallbacks {
    val scope: CoroutineScope
    val repository: AppRepository
    val aiEngine: AiPipelineEngine
    val stateFlow: MutableStateFlow<ImeUiState>

    fun currentInputConnection(): InputConnection?
    fun performKeyFeedback(strong: Boolean = false)
    fun performHaptic(strong: Boolean = false)
    fun showQuickActionToast(message: String)

    fun commitDirectText(
        text: String,
        recordUndo: Boolean,
        markManualEdit: Boolean = false,
        playFeedback: Boolean = true
    )

    fun pushUndoSnapshot(snapshot: UndoSnapshot?)
    fun peekTopUndoSnapshot(): UndoSnapshot?
    fun recordManualEditTouch()
    fun tryAutoLearnFromRecentEdit()
    fun onDictationCommittedForAutoLearn(fullBufferAfterCommit: String)
    fun clearAutoLearnBaseline()
    fun onUserDeleteOrCutForJev(remainingTextLengthHint: Int = -1) {}
    fun onUserUndoForJev() {}
    fun onExplicitImeSendForJev(currentEditorText: String?) {}
    fun readClipboardTextForJevNote(): String? = null

    fun readSelectedTextSafely(): String
    fun readSurroundingBeforeCursor(maxChars: Int): String
    fun readFullEditorTextSafely(): String
    fun readSystemClipboardText(): String
    fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean)
    fun copyTextToClipboard(text: String) {}
    fun openJevCopilotFromCommand() {}

    fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File
    fun getLastRecordingWavFile(): File
    fun hasRetryWavFile(): Boolean
}

/**
 * Controller encapsulating QWERTY, Xiaohe Yinxing ([FlypyInputEngine]), Rime ([InputEngine]),
 * L1/L2/L3 prediction strip ([PredictionCoordinator]), punctuation shortcuts, and editor actions.
 */
class TypingController(
    private val host: ImeHostCallbacks,
    val flypyInputEngine: FlypyInputEngine = FlypyInputEngine(),
    val predictionStore: PredictionStore = PredictionStore.fromContextOrInMemory { host as? Context },
    val staticPredictor: StaticPredictor = StaticPredictor(),
    aiSentenceCompleter: AiSentenceCompleter? = null,
    predictionIdleDismissMs: Long = PredictionCoordinator.IDLE_DISMISS_MS,
    private val settingsOverride: (() -> AppSettings)? = null
) {
    var isIncognitoMode: Boolean = false
        private set

    var rimeInputEngine: InputEngine? = null
        set(value) {
            field = value
            value?.setIncognitoMode(isIncognitoMode)
        }

    var codetableEnhancedEngine: CodetableEnhancedInputEngine? = null
        set(value) {
            field = value
            value?.isActiveEnginePredicate = { activeEngine === value }
            if (value != null && activeEngine === value) {
                value.setIncognitoMode(isIncognitoMode)
                value.setFixedCandidateOrder(safeSettings().fixedCandidateOrder)
            }
            value?.updateSnippets(safeSettings().snippets)
        }

    var enhancedSchemePredicate: (InputScheme) -> Boolean = { scheme ->
        (scheme == InputScheme.FLYPY_TABLE && codetableEnhancedEngine?.activeProfile?.schemaId == "flypy") ||
            scheme == InputScheme.RIME_CUSTOM
    }

    var isRimeReady: Boolean = false

    val englishWordSuggester: EnglishWordSuggester = EnglishWordSuggester()
    var reverseLookupMapProvider: () -> Map<String, List<String>> = { emptyMap() }
    var onSaveUserPhrase: ((word: String, code: String) -> Unit)? = null
    var onUndoRequested: (() -> Unit)? = null
    private var lastCommittedForRepeat: String = ""

    val aiCompleter: AiSentenceCompleter = aiSentenceCompleter ?: AiSentenceCompleter(
        scope = host.scope,
        executor = { settings, activeStyle, contextBeforeCursor, targetAppName ->
            host.aiEngine.completeSentence(
                settings = settings,
                activeStyle = activeStyle,
                contextBeforeCursor = contextBeforeCursor,
                targetAppName = targetAppName
            )
        }
    )

    val predictionCoordinator: PredictionCoordinator = PredictionCoordinator(
        scope = host.scope,
        stateFlow = host.stateFlow,
        settingsProvider = ::safeSettings,
        activeStyleResolver = { pkg, inputType ->
            runCatching { host.repository.resolveActiveStyle(pkg, inputType).first }
                .getOrElse { DefaultPresets.defaultStyles().first() }
        },
        surroundingTextReader = { maxChars -> host.readSurroundingBeforeCursor(maxChars) },
        predictionStore = predictionStore,
        staticPredictor = staticPredictor,
        aiSentenceCompleter = aiCompleter,
        idleDismissMs = predictionIdleDismissMs
    )

    private fun safeSettings(): AppSettings {
        return settingsOverride?.invoke()
            ?: runCatching { host.repository.settings.value }.getOrDefault(AppSettings())
    }

    val activeEngine: InputEngine
        get() {
            val scheme = safeSettings().inputScheme
            val enhanced = codetableEnhancedEngine
            if (enhanced != null && isRimeReady && enhancedSchemePredicate(scheme)) {
                return enhanced
            }
            val rime = rimeInputEngine
            return if (scheme.usesRime && isRimeReady && rime != null) {
                rime
            } else {
                flypyInputEngine
            }
        }

    val activeAllowsAutoLearn: Boolean
        get() {
            val eng = activeEngine
            if (eng is CodetableEnhancedInputEngine) {
                return eng.activeProfile.allowAutoLearn
            }
            return safeSettings().inputScheme != InputScheme.FLYPY_TABLE
        }

    val isDictionaryLoaded: Boolean
        get() = flypyInputEngine.dictionary.isLoaded

    val isComposing: Boolean
        get() = activeEngine.state.value.isComposing

    fun updateFlypyDictionary(dict: FlypyDictionary) {
        flypyInputEngine.updateDictionary(dict)
        syncEngineStateToUi()
    }

    fun updateSnippets(snippets: List<SnippetEntry>) {
        flypyInputEngine.updateSnippets(snippets)
        rimeInputEngine?.updateSnippets(snippets)
        codetableEnhancedEngine?.updateSnippets(snippets)
        if (isComposing) {
            syncEngineStateToUi()
        }
    }

    fun setIncognitoMode(incognito: Boolean) {
        isIncognitoMode = incognito
        flypyInputEngine.setIncognitoMode(incognito)
        rimeInputEngine?.setIncognitoMode(incognito)
        codetableEnhancedEngine?.setIncognitoMode(incognito)
        predictionCoordinator.setIncognitoMode(incognito)
    }

    fun setFixedCandidateOrder(fixedOrder: Boolean?) {
        flypyInputEngine.setFixedCandidateOrder(fixedOrder)
        rimeInputEngine?.setFixedCandidateOrder(fixedOrder)
        codetableEnhancedEngine?.setFixedCandidateOrder(fixedOrder)
    }

    fun resetComposing() {
        flypyInputEngine.clear()
        rimeInputEngine?.clear()
        codetableEnhancedEngine?.clear()
        predictionCoordinator.onPanelSwitched()
        syncEngineStateToUi()
    }

    fun clearComposing(): Boolean {
        val wasComposing = isComposing
        if (!wasComposing) return false
        activeEngine.clear()
        predictionCoordinator.dismissPredictions(resetChain = false)
        syncEngineStateToUi()
        return true
    }

    fun recordExternalCommit(text: String) {
        if (text.isNotBlank()) {
            lastCommittedForRepeat = text
            flypyInputEngine.recordExternalCommit(text)
            rimeInputEngine?.recordExternalCommit(text)
            codetableEnhancedEngine?.recordExternalCommit(text)
        }
    }

    fun syncEngineStateToUi() {
        val engineState = activeEngine.state.value
        val code = engineState.rawCode
        val preedit = engineState.preedit.ifEmpty { code }
        val cands = engineState.candidates
        val scheme = safeSettings().inputScheme
        host.stateFlow.update { prev ->
            prev.copy(
                flypyComposingCode = code,
                preeditDisplay = preedit,
                flypyCandidates = cands,
                hasMoreCandidates = engineState.hasMore,
                isOrqDateActive = engineState.isOrqDateActive,
                orqDateOffsetDays = engineState.orqDateOffsetDays,
                activeInputScheme = scheme,
                isRimeDeploying = scheme.usesRime && !isRimeReady && rimeInputEngine != null,
                passiveMathResult = if (engineState.isComposing) null else prev.passiveMathResult,
                passiveMathExpression = if (engineState.isComposing) null else prev.passiveMathExpression,
                passiveMathEndsWithEquals = if (engineState.isComposing) false else prev.passiveMathEndsWithEquals,
                englishSuggestions = if (engineState.isComposing) emptyList() else prev.englishSuggestions,
                englishSuggestionReplaceLength = if (engineState.isComposing) 0 else prev.englishSuggestionReplaceLength,
                isFlypyCandidateSheetExpanded = if (!engineState.isComposing || cands.size <= 4) {
                    false
                } else {
                    prev.isFlypyCandidateSheetExpanded
                }
            )
        }
    }

    fun handleEngineResult(result: EngineResult) {
        when (result) {
            EngineResult.Consumed -> {
                syncEngineStateToUi()
                if (isComposing) {
                    predictionCoordinator.onComposingStarted()
                } else {
                    predictionCoordinator.dismissPredictions(resetChain = false)
                }
            }
            EngineResult.PassThrough -> {
                syncEngineStateToUi()
                if (isComposing) {
                    predictionCoordinator.onComposingStarted()
                } else {
                    predictionCoordinator.dismissPredictions(resetChain = false)
                }
            }
            is EngineResult.Commit -> {
                val stillComposing = result.remainComposing || isComposing
                syncEngineStateToUi()
                if (result.text.isNotEmpty()) {
                    host.commitDirectText(
                        text = result.text,
                        recordUndo = true,
                        markManualEdit = activeAllowsAutoLearn,
                        playFeedback = false
                    )
                    onCommittedTextForPhraseTracking(result.text)
                    predictionCoordinator.onWordCommitted(
                        committedText = result.text,
                        isComposing = stillComposing
                    )
                    if (!stillComposing) {
                        refreshPassiveMathSuggestion()
                    }
                } else if (stillComposing) {
                    predictionCoordinator.onComposingStarted()
                } else {
                    predictionCoordinator.dismissPredictions(resetChain = false)
                }
            }
        }
    }

    fun stepOrqDate(deltaDays: Int) {
        val eng = codetableEnhancedEngine ?: return
        host.performKeyFeedback(strong = false)
        eng.adjustOrqDateOffset(deltaDays)
        syncEngineStateToUi()
    }

    fun handleKeyGesture(binding: KeyGestureBinding) {
        if (!binding.isEnabled) return
        when (binding.type) {
            KeyGestureActionType.NONE -> Unit
            KeyGestureActionType.COMMIT_TEXT -> {
                if (binding.text.isNotEmpty()) {
                    commitSecondarySymbol(binding.text)
                }
            }
            KeyGestureActionType.COMMIT_PAIRED -> {
                commitPairedSymbol(binding.text, binding.rightText)
            }
            KeyGestureActionType.UNDO_LAST -> {
                onUndoRequested?.invoke()
            }
            KeyGestureActionType.REPEAT_LAST -> {
                repeatLastCommittedText()
            }
            KeyGestureActionType.EDITOR_SHORTCUT -> {
                binding.editorShortcut?.let(::handleEditorShortcutAction)
            }
        }
    }

    fun repeatLastCommittedText() {
        if (isComposing) {
            flushFlypyComposing()
        }
        val last = codetableEnhancedEngine?.coordinator?.lastCommittedPreview?.ifEmpty { null }
            ?: lastCommittedForRepeat.ifEmpty { null }
            ?: flypyInputEngine.delegate.lastCommittedText.ifEmpty { null }
            ?: return
        host.commitDirectText(
            text = last,
            recordUndo = true,
            markManualEdit = false,
            playFeedback = true
        )
        onCommittedTextForPhraseTracking(last)
        refreshPassiveMathSuggestion()
    }

    fun triggerSwipeUpPeriodSlash() {
        val curr = host.stateFlow.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode && !isComposing) {
            commitQwertyChar("/")
        } else {
            commitSecondarySymbol("/")
        }
    }

    fun refreshPassiveMathSuggestion() {
        val curr = host.stateFlow.value
        if (isComposing ||
            curr.tabMode != KeyboardTabMode.QWERTY ||
            !safeSettings().passiveCalculatorEnabled ||
            ImeLogic.isPasswordEditorInputType(curr.currentInputType)
        ) {
            if (curr.passiveMathResult != null || curr.passiveMathExpression != null || curr.passiveMathEndsWithEquals) {
                host.stateFlow.update {
                    it.copy(
                        passiveMathResult = null,
                        passiveMathExpression = null,
                        passiveMathEndsWithEquals = false
                    )
                }
            }
            return
        }
        val before = host.readSurroundingBeforeCursor(64)
        val helperRes = PassiveCalculatorHelper.evaluateTrailing(before)
        val coreRes = if (helperRes != null) {
            SafeMathEvaluator.detectTrailingExpression(before)
        } else {
            null
        }
        val nextResult = coreRes?.formattedResult ?: helperRes?.formattedResult
        val nextExpr = coreRes?.expression ?: helperRes?.expression
        val nextEndsWithEquals = coreRes?.endsWithEquals ?: helperRes?.endsWithEquals ?: false
        if (curr.passiveMathResult != nextResult ||
            curr.passiveMathExpression != nextExpr ||
            curr.passiveMathEndsWithEquals != nextEndsWithEquals
        ) {
            host.stateFlow.update {
                it.copy(
                    passiveMathResult = nextResult,
                    passiveMathExpression = nextExpr,
                    passiveMathEndsWithEquals = nextEndsWithEquals
                )
            }
        }
    }

    fun selectPassiveMath(result: String) {
        val clean = result.trim().removePrefix("=").trim()
        if (clean.isEmpty()) return
        val stateEndsWithEquals = host.stateFlow.value.passiveMathEndsWithEquals
        val tail = host.readSurroundingBeforeCursor(4).trimEnd()
        val alreadyHasEquals = stateEndsWithEquals || tail.endsWith('=') || tail.endsWith('＝')
        val textToCommit = if (alreadyHasEquals) clean else "=$clean"
        host.stateFlow.update {
            it.copy(
                passiveMathResult = null,
                passiveMathExpression = null,
                passiveMathEndsWithEquals = false
            )
        }
        host.commitDirectText(
            text = textToCommit,
            recordUndo = true,
            markManualEdit = false,
            playFeedback = true
        )
    }

    fun refreshEnglishWordSuggestions() {
        val curr = host.stateFlow.value
        if (curr.tabMode != KeyboardTabMode.QWERTY ||
            curr.isFlypyChineseMode ||
            !safeSettings().englishModeWordSuggestionEnabled ||
            ImeLogic.isPasswordEditorInputType(curr.currentInputType)
        ) {
            if (curr.englishSuggestions.isNotEmpty() || curr.englishSuggestionReplaceLength != 0) {
                host.stateFlow.update {
                    it.copy(englishSuggestions = emptyList(), englishSuggestionReplaceLength = 0)
                }
            }
            return
        }
        val before = host.readSurroundingBeforeCursor(32)
        val suggestion = englishWordSuggester.suggestForBeforeCursor(before, limit = 6)
        val nextWords = suggestion?.suggestions.orEmpty()
        val nextReplace = suggestion?.replaceLength ?: 0
        if (curr.englishSuggestions != nextWords || curr.englishSuggestionReplaceLength != nextReplace) {
            host.stateFlow.update {
                it.copy(
                    englishSuggestions = nextWords,
                    englishSuggestionReplaceLength = nextReplace
                )
            }
        }
    }

    fun selectEnglishSuggestion(word: String) {
        val clean = word.trim()
        if (clean.isEmpty()) return
        val replaceLen = host.stateFlow.value.englishSuggestionReplaceLength
        host.stateFlow.update {
            it.copy(englishSuggestions = emptyList(), englishSuggestionReplaceLength = 0)
        }
        host.performKeyFeedback(strong = false)
        val ic = host.currentInputConnection()
        if (ic != null) {
            ic.beginBatchEdit()
            if (replaceLen > 0) {
                ic.deleteSurroundingText(replaceLen, 0)
            }
            ic.commitText("$clean ", 1)
            ic.endBatchEdit()
            recordExternalCommit(clean)
            host.recordManualEditTouch()
        } else {
            host.commitDirectText(
                text = "$clean ",
                recordUndo = true,
                markManualEdit = true,
                playFeedback = false
            )
        }
        refreshPassiveMathSuggestion()
    }

    fun togglePhraseRecording() {
        val curr = host.stateFlow.value
        val nextRecording = !curr.isPhraseRecording
        host.performKeyFeedback(strong = false)
        host.stateFlow.update {
            it.copy(
                isPhraseRecording = nextRecording,
                recordedPhraseBuffer = "",
                recordedPhraseCode = null,
                isToolPanelOpen = if (nextRecording) false else it.isToolPanelOpen,
                isToolPanelHeightExpanded = if (nextRecording) false else it.isToolPanelHeightExpanded
            )
        }
        host.showQuickActionToast(
            if (nextRecording) "🎙️ 造词模式已开启，连续打字后点击顶部芯片保存" else "造词模式已关闭"
        )
    }

    fun onL2PhraseSuggestion(suggestion: PhraseSuggestion?) {
        if (suggestion == null) {
            host.stateFlow.update {
                it.copy(l2PhraseSuggestionWord = null, l2PhraseSuggestionCode = null)
            }
            return
        }
        val curr = host.stateFlow.value
        if (!safeSettings().phraseSuggestionL2Enabled ||
            isIncognitoMode ||
            curr.isIncognitoMode ||
            ImeLogic.isPasswordEditorInputType(curr.currentInputType) ||
            curr.isPhraseRecording
        ) {
            return
        }
        host.stateFlow.update {
            it.copy(
                l2PhraseSuggestionWord = suggestion.word,
                l2PhraseSuggestionCode = suggestion.code
            )
        }
    }

    fun onExternalCommittedTextForV362(committedText: String) {
        onCommittedTextForPhraseTracking(committedText)
        refreshPassiveMathSuggestion()
    }

    fun onCommittedTextForPhraseTracking(committedText: String) {
        val curr = host.stateFlow.value
        if (!curr.isPhraseRecording) return
        if (isIncognitoMode || curr.isIncognitoMode || ImeLogic.isPasswordEditorInputType(curr.currentInputType)) {
            return
        }
        val trimmed = committedText.trim()
        if (trimmed.isEmpty()) return
        val hanzi = Utf8Safety.extractHanziCodePoints(trimmed, limit = 8)
        if (hanzi.isEmpty() || hanzi.joinToString("") != trimmed) return

        val combinedChars = Utf8Safety.extractHanziCodePoints(curr.recordedPhraseBuffer + trimmed, limit = 8)
        val nextPhrase = combinedChars.joinToString("")
        val derivedCode = CodetableCodeDeriver.deriveXiaohe4Code(nextPhrase, reverseLookupMapProvider())
        host.stateFlow.update {
            it.copy(
                recordedPhraseBuffer = nextPhrase,
                recordedPhraseCode = derivedCode
            )
        }
    }

    fun acceptPhraseSuggestion() {
        val curr = host.stateFlow.value
        if (curr.isPhraseRecording) {
            val word = curr.recordedPhraseBuffer.trim()
            val code = curr.recordedPhraseCode?.trim().orEmpty()
            if (word.codePointCount(0, word.length) < 2 || code.isEmpty()) {
                host.showQuickActionToast("请继续打出至少 2 个汉字以自动生成 4 码")
                return
            }
            host.performKeyFeedback(strong = true)
            host.stateFlow.update {
                it.copy(
                    isPhraseRecording = false,
                    recordedPhraseBuffer = "",
                    recordedPhraseCode = null
                )
            }
            codetableEnhancedEngine?.addRuntimeUserPhrase(word, code)
            codetableEnhancedEngine?.markPhraseSuggestionAccepted(word)
            onSaveUserPhrase?.invoke(word, code)
            return
        }

        val l2Word = curr.l2PhraseSuggestionWord?.trim().orEmpty()
        val l2Code = curr.l2PhraseSuggestionCode?.trim().orEmpty()
        if (l2Word.isEmpty() || l2Code.isEmpty()) return
        host.performKeyFeedback(strong = true)
        host.stateFlow.update {
            it.copy(l2PhraseSuggestionWord = null, l2PhraseSuggestionCode = null)
        }
        codetableEnhancedEngine?.addRuntimeUserPhrase(l2Word, l2Code)
        codetableEnhancedEngine?.markPhraseSuggestionAccepted(l2Word)
        onSaveUserPhrase?.invoke(l2Word, l2Code)
    }

    fun dismissPhraseSuggestion() {
        val curr = host.stateFlow.value
        host.performKeyFeedback(strong = false)
        if (curr.isPhraseRecording) {
            host.stateFlow.update {
                it.copy(
                    isPhraseRecording = false,
                    recordedPhraseBuffer = "",
                    recordedPhraseCode = null
                )
            }
            return
        }
        val l2Word = curr.l2PhraseSuggestionWord?.trim().orEmpty()
        if (l2Word.isNotEmpty()) {
            codetableEnhancedEngine?.markPhraseSuggestionDismissed(l2Word)
        }
        host.stateFlow.update {
            it.copy(l2PhraseSuggestionWord = null, l2PhraseSuggestionCode = null)
        }
    }

    fun toggleFlypyLanguageMode() {
        host.performKeyFeedback(strong = true)
        val wasChinese = host.stateFlow.value.isFlypyChineseMode
        if (wasChinese && isComposing) {
            val res = activeEngine.onKey(KeyInput.Enter)
            if (res is EngineResult.Commit && res.text.isNotEmpty()) {
                host.commitDirectText(
                    text = res.text,
                    recordUndo = true,
                    markManualEdit = true,
                    playFeedback = false
                )
            }
            activeEngine.clear()
        } else {
            resetComposing()
        }
        predictionCoordinator.onPanelSwitched()
        val nextChinese = !wasChinese
        val currentInputType = host.stateFlow.value.currentInputType
        if (!ImeLogic.isPasswordEditorInputType(currentInputType)) {
            runCatching {
                host.repository.updateSettings {
                    it.copy(
                        lastKeyboardTab = KeyboardTabMode.QWERTY.name,
                        lastQwertyIsChinese = nextChinese
                    )
                }
            }
        }
        val scheme = safeSettings().inputScheme
        host.stateFlow.update {
            it.copy(
                isFlypyChineseMode = nextChinese,
                flypyComposingCode = "",
                preeditDisplay = "",
                flypyCandidates = emptyList(),
                hasMoreCandidates = false,
                isOrqDateActive = false,
                orqDateOffsetDays = 0,
                isFlypyCandidateSheetExpanded = false,
                predictionCandidates = emptyList(),
                aiCompletionSuggestion = null,
                englishSuggestions = emptyList(),
                englishSuggestionReplaceLength = 0
            )
        }
        val toastText = if (nextChinese) {
            "${scheme.shortBadge} · ${scheme.label}"
        } else {
            "EN · 英文键盘"
        }
        host.showQuickActionToast(toastText)
    }

    fun commitQwertyChar(ch: String) {
        if (ch.isEmpty()) return
        predictionCoordinator.onUserKeyPressed()
        val curr = host.stateFlow.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode) {
            if (ch.length == 1) {
                host.performKeyFeedback(strong = false)
                val result = activeEngine.onKey(KeyInput.CharKey(ch[0]))
                handleEngineResult(result)
                return
            } else {
                val flushed = activeEngine.flush()
                syncEngineStateToUi()
                val combined = flushed + ch
                host.commitDirectText(
                    text = combined,
                    recordUndo = true,
                    markManualEdit = true
                )
                onCommittedTextForPhraseTracking(combined)
                predictionCoordinator.onWordCommitted(
                    committedText = combined,
                    isComposing = false
                )
                refreshPassiveMathSuggestion()
                return
            }
        }
        if (PredictionStore.isPunctuationOrSymbolOnly(ch)) {
            predictionCoordinator.onPunctuationCommitted()
        } else {
            predictionCoordinator.dismissPredictions(resetChain = true)
        }
        host.commitDirectText(text = ch, recordUndo = true, markManualEdit = true)
        refreshEnglishWordSuggestions()
        refreshPassiveMathSuggestion()
    }

    fun handleCommaOrPeriodKey(isComma: Boolean) {
        predictionCoordinator.onUserKeyPressed()
        val curr = host.stateFlow.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode) {
            host.performKeyFeedback(strong = false)
            val quickSelect = safeSettings().flypyCommaPeriodQuickSelect
            val result = activeEngine.onKey(
                KeyInput.CommaOrPeriod(
                    isComma = isComma,
                    commaPeriodQuickSelect = quickSelect
                )
            )
            handleEngineResult(result)
            return
        }
        predictionCoordinator.onPunctuationCommitted()
        host.commitDirectText(
            text = if (isComma) "," else ".",
            recordUndo = true,
            markManualEdit = true
        )
    }

    fun selectFlypyCandidate(index: Int) {
        predictionCoordinator.onUserKeyPressed()
        host.performKeyFeedback(strong = false)
        val result = activeEngine.select(index)
        handleEngineResult(result)
    }

    fun selectPredictionCandidate(index: Int) {
        val picked = host.stateFlow.value.predictionCandidates.getOrNull(index)?.text ?: return
        host.performKeyFeedback(strong = false)
        host.commitDirectText(
            text = picked,
            recordUndo = true,
            markManualEdit = true,
            playFeedback = false
        )
        predictionCoordinator.onWordCommitted(
            committedText = picked,
            isComposing = false
        )
    }

    fun selectAiCompletion() {
        val suggestion = predictionCoordinator.selectAiCompletion() ?: return
        host.performKeyFeedback(strong = true)
        host.commitDirectText(
            text = suggestion,
            recordUndo = true,
            markManualEdit = true,
            playFeedback = false
        )
    }

    fun dismissPredictions() {
        host.performKeyFeedback(strong = false)
        predictionCoordinator.dismissPredictions(resetChain = false)
        val curr = host.stateFlow.value
        val l2Word = curr.l2PhraseSuggestionWord?.trim().orEmpty()
        if (l2Word.isNotEmpty()) {
            codetableEnhancedEngine?.markPhraseSuggestionDismissed(l2Word)
        }
        host.stateFlow.update {
            it.copy(
                passiveMathResult = null,
                passiveMathExpression = null,
                passiveMathEndsWithEquals = false,
                englishSuggestions = emptyList(),
                englishSuggestionReplaceLength = 0,
                l2PhraseSuggestionWord = null,
                l2PhraseSuggestionCode = null,
                isPhraseRecording = false,
                recordedPhraseBuffer = "",
                recordedPhraseCode = null
            )
        }
    }

    fun onPanelSwitched() {
        predictionCoordinator.onPanelSwitched()
        host.stateFlow.update {
            it.copy(
                passiveMathResult = null,
                passiveMathExpression = null,
                passiveMathEndsWithEquals = false,
                englishSuggestions = emptyList(),
                englishSuggestionReplaceLength = 0
            )
        }
    }

    fun clearFlypyComposing() {
        host.performKeyFeedback(strong = false)
        clearComposing()
    }

    fun flushFlypyComposing() {
        val flushed = activeEngine.flush()
        syncEngineStateToUi()
        if (flushed.isNotEmpty()) {
            host.commitDirectText(
                text = flushed,
                recordUndo = true,
                markManualEdit = true,
                playFeedback = false
            )
            predictionCoordinator.onWordCommitted(
                committedText = flushed,
                isComposing = false
            )
        } else {
            predictionCoordinator.dismissPredictions(resetChain = false)
        }
    }

    fun toggleFlypyCandidateSheet() {
        host.performKeyFeedback(strong = false)
        val nextExpanded = !host.stateFlow.value.isFlypyCandidateSheetExpanded
        if (nextExpanded) {
            activeEngine.requestMoreCandidates(60)
            syncEngineStateToUi()
        }
        host.stateFlow.update {
            it.copy(isFlypyCandidateSheetExpanded = nextExpanded)
        }
    }

    fun requestMoreCandidates(minCount: Int = 60) {
        activeEngine.requestMoreCandidates(minCount)
        syncEngineStateToUi()
    }

    fun insertSpace() {
        predictionCoordinator.onUserKeyPressed()
        val curr = host.stateFlow.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode && isComposing) {
            host.performKeyFeedback(strong = false)
            val result = activeEngine.onKey(KeyInput.Space)
            handleEngineResult(result)
            return
        }
        predictionCoordinator.dismissPredictions(resetChain = true)
        host.stateFlow.update {
            it.copy(
                passiveMathResult = null,
                passiveMathExpression = null,
                passiveMathEndsWithEquals = false,
                englishSuggestions = emptyList(),
                englishSuggestionReplaceLength = 0
            )
        }
        host.tryAutoLearnFromRecentEdit()
        host.commitDirectText(" ", recordUndo = false, markManualEdit = false)
    }

    fun deleteBackward() {
        predictionCoordinator.onBackspacePressed()
        val curr = host.stateFlow.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode) {
            val result = activeEngine.onKey(KeyInput.Backspace())
            if (result != EngineResult.PassThrough) {
                host.performKeyFeedback(strong = false)
                syncEngineStateToUi()
                return
            }
        }
        host.performKeyFeedback(strong = false)
        val ic = host.currentInputConnection() ?: return
        try {
            val selected = ic.getSelectedText(0)?.toString().orEmpty()
            if (selected.isNotEmpty()) {
                ic.commitText("", 1)
            } else {
                val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                val deleteLen = ImeLogic.computeBackspaceDeleteLength(before).coerceAtLeast(1)
                ic.deleteSurroundingText(deleteLen, 0)
            }
            host.recordManualEditTouch()
            host.onUserDeleteOrCutForJev()
        } catch (_: Exception) {
        }
        refreshEnglishWordSuggestions()
        refreshPassiveMathSuggestion()
    }

    fun performEnterOrNewline(onRunEqualEqualAskAi: (String, String) -> Unit) {
        predictionCoordinator.onUserKeyPressed()
        val curr = host.stateFlow.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode && isComposing) {
            val res = activeEngine.onKey(KeyInput.Enter)
            syncEngineStateToUi()
            if (res is EngineResult.Commit && res.text.isNotEmpty()) {
                predictionCoordinator.dismissPredictions(resetChain = true)
                host.commitDirectText(
                    text = res.text,
                    recordUndo = true,
                    markManualEdit = true,
                    playFeedback = true
                )
                return
            }
        }

        predictionCoordinator.dismissPredictions(resetChain = true)

        host.performKeyFeedback(strong = false)
        host.tryAutoLearnFromRecentEdit()

        val ic = host.currentInputConnection() ?: return
        val actionInfo = ImeLogic.resolveEnterAction(
            imeOptions = host.stateFlow.value.currentImeOptions,
            inputType = host.stateFlow.value.currentInputType
        )
        val actionId = actionInfo.performEditorActionId
        if (actionId != null) {
            val preSendText = host.stateFlow.value.currentEditorText
            val handled = try {
                ic.performEditorAction(actionId)
            } catch (_: Exception) {
                false
            }
            if (handled && actionId in setOf(
                    EditorInfo.IME_ACTION_SEND,
                    EditorInfo.IME_ACTION_DONE,
                    EditorInfo.IME_ACTION_GO
                )
            ) {
                host.onExplicitImeSendForJev(preSendText)
            }
            if (!handled) {
                ic.commitText("\n", 1)
            }
        } else {
            ic.commitText("\n", 1)
        }
    }

    fun handleEditorShortcutAction(action: EditorShortcutAction) {
        resetComposing()
        host.performHaptic(strong = true)
        val ic = host.currentInputConnection() ?: return
        when (action) {
            EditorShortcutAction.SELECT_ALL -> {
                runCatching { ic.performContextMenuAction(android.R.id.selectAll) }
                host.showQuickActionToast("✓ 已全选")
            }
            EditorShortcutAction.CUT -> {
                runCatching { ic.performContextMenuAction(android.R.id.cut) }
                host.recordManualEditTouch()
                host.onUserDeleteOrCutForJev()
                host.syncSystemClipboardIfNeeded(allowFreshBanner = true)
                host.showQuickActionToast("✓ 已剪切")
            }
            EditorShortcutAction.COPY -> {
                runCatching { ic.performContextMenuAction(android.R.id.copy) }
                host.syncSystemClipboardIfNeeded(allowFreshBanner = true)
                host.showQuickActionToast("✓ 已复制")
            }
            EditorShortcutAction.PASTE -> {
                val handled = runCatching {
                    ic.performContextMenuAction(android.R.id.paste)
                }.getOrDefault(false)
                if (!handled) {
                    val clipText = host.readSystemClipboardText()
                    if (clipText.isNotEmpty()) {
                        host.commitDirectText(
                            text = clipText,
                            recordUndo = true,
                            markManualEdit = true,
                            playFeedback = false
                        )
                    }
                } else {
                    host.recordManualEditTouch()
                }
                host.showQuickActionToast("✓ 已粘贴")
            }
        }
    }

    fun commitSecondarySymbol(symbol: String) {
        if (symbol.isEmpty()) return
        val state = host.stateFlow.value
        // Special case: preserve Xiaohe Yinxing (`FLYPY_TABLE`) stroke reverse-lookup `ji\`` when composing
        if (
            symbol == "`" &&
            state.activeInputScheme == InputScheme.FLYPY_TABLE &&
            state.isFlypyChineseMode &&
            isComposing
        ) {
            commitQwertyChar("`")
            return
        }

        if (isComposing) {
            flushFlypyComposing()
        }
        predictionCoordinator.onPunctuationCommitted()
        host.commitDirectText(
            text = symbol,
            recordUndo = true,
            markManualEdit = true,
            playFeedback = true
        )
        refreshEnglishWordSuggestions()
        refreshPassiveMathSuggestion()
    }

    fun commitPairedSymbol(open: String, close: String) {
        if (isComposing) {
            flushFlypyComposing()
        }
        predictionCoordinator.onPunctuationCommitted()
        host.performKeyFeedback(strong = false)
        val ic = host.currentInputConnection() ?: return
        val selected = host.readSelectedTextSafely()
        ic.beginBatchEdit()
        if (selected.isNotEmpty()) {
            ic.commitText("$open$selected$close", 1)
        } else {
            ic.commitText("$open$close", 1)
            val extracted = try {
                ic.getExtractedText(ExtractedTextRequest(), 0)
            } catch (_: Exception) {
                null
            }
            if (extracted != null && extracted.selectionStart >= close.length) {
                val cursorInside = extracted.selectionStart - close.length
                ic.setSelection(cursorInside, cursorInside)
            } else {
                val before = runCatching { ic.getTextBeforeCursor(4000, 0) }.getOrNull()
                if (before != null && before.length >= close.length) {
                    val cursorInside = before.length - close.length
                    ic.setSelection(cursorInside, cursorInside)
                } else {
                    repeat(close.length) {
                        runCatching {
                            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
                        }
                    }
                }
            }
        }
        ic.endBatchEdit()
        host.recordManualEditTouch()
    }

    fun insertPairedSymbol(left: String, right: String) {
        commitPairedSymbol(left, right)
    }

    fun moveCursorByChars(delta: Int) {
        if (delta == 0) return
        if (isComposing) {
            flushFlypyComposing()
        }
        host.performHaptic(strong = false)
        val ic = host.currentInputConnection() ?: return
        val extracted = runCatching {
            ic.getExtractedText(ExtractedTextRequest(), 0)
        }.getOrNull()
        val fullText = extracted?.text
        if (extracted != null && fullText != null) {
            val baseCursor = extracted.selectionEnd.coerceIn(0, fullText.length)
            val target = (baseCursor + delta).coerceIn(0, fullText.length)
            if (target != baseCursor || extracted.selectionStart != extracted.selectionEnd) {
                runCatching { ic.setSelection(target, target) }
            }
            return
        }
        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val steps = abs(delta).coerceIn(1, 32)
        repeat(steps) {
            runCatching {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
        }
    }
}
