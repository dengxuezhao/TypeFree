package com.typefree.ime.ime

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import kotlin.math.abs
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SnippetMatcher
import com.typefree.ime.ime.engine.EngineResult
import com.typefree.ime.ime.engine.FlypyInputEngine
import com.typefree.ime.ime.engine.InputEngine
import com.typefree.ime.ime.engine.KeyInput
import com.typefree.ime.ime.flypy.FlypyDictionary
import com.typefree.ime.ime.predict.AiSentenceCompleter
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

    fun readSelectedTextSafely(): String
    fun readSurroundingBeforeCursor(maxChars: Int): String
    fun readFullEditorTextSafely(): String
    fun readSystemClipboardText(): String
    fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean)
    fun copyTextToClipboard(text: String) {}

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
    predictionIdleDismissMs: Long = PredictionCoordinator.IDLE_DISMISS_MS
) {
    var isIncognitoMode: Boolean = false
        private set

    var rimeInputEngine: InputEngine? = null
        set(value) {
            field = value
            value?.setIncognitoMode(isIncognitoMode)
        }

    var isRimeReady: Boolean = false

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
        return runCatching { host.repository.settings.value }.getOrDefault(AppSettings())
    }

    val activeEngine: InputEngine
        get() {
            val scheme = safeSettings().inputScheme
            val rime = rimeInputEngine
            return if (scheme.usesRime && isRimeReady && rime != null) {
                rime
            } else {
                flypyInputEngine
            }
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
        if (isComposing) {
            syncEngineStateToUi()
        }
    }

    fun setIncognitoMode(incognito: Boolean) {
        isIncognitoMode = incognito
        flypyInputEngine.setIncognitoMode(incognito)
        rimeInputEngine?.setIncognitoMode(incognito)
        predictionCoordinator.setIncognitoMode(incognito)
    }

    fun setFixedCandidateOrder(fixedOrder: Boolean?) {
        flypyInputEngine.setFixedCandidateOrder(fixedOrder)
        rimeInputEngine?.setFixedCandidateOrder(fixedOrder)
    }

    fun resetComposing() {
        flypyInputEngine.clear()
        rimeInputEngine?.clear()
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
            flypyInputEngine.recordExternalCommit(text)
            rimeInputEngine?.recordExternalCommit(text)
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
                activeInputScheme = scheme,
                isRimeDeploying = scheme.usesRime && !isRimeReady && rimeInputEngine != null,
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
                        markManualEdit = true,
                        playFeedback = false
                    )
                    predictionCoordinator.onWordCommitted(
                        committedText = result.text,
                        isComposing = stillComposing
                    )
                } else if (stillComposing) {
                    predictionCoordinator.onComposingStarted()
                } else {
                    predictionCoordinator.dismissPredictions(resetChain = false)
                }
            }
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
                isFlypyCandidateSheetExpanded = false,
                predictionCandidates = emptyList(),
                aiCompletionSuggestion = null
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
                predictionCoordinator.onWordCommitted(
                    committedText = combined,
                    isComposing = false
                )
                return
            }
        }
        if (PredictionStore.isPunctuationOrSymbolOnly(ch)) {
            predictionCoordinator.onPunctuationCommitted()
        } else {
            predictionCoordinator.dismissPredictions(resetChain = true)
        }
        host.commitDirectText(text = ch, recordUndo = true, markManualEdit = true)
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
    }

    fun onPanelSwitched() {
        predictionCoordinator.onPanelSwitched()
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
        } catch (_: Exception) {
        }
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
            val handled = try {
                ic.performEditorAction(actionId)
            } catch (_: Exception) {
                false
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
