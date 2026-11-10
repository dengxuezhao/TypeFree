package com.typefree.ime.ime

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SnippetMatcher
import com.typefree.ime.ime.engine.EngineResult
import com.typefree.ime.ime.engine.FlypyInputEngine
import com.typefree.ime.ime.engine.InputEngine
import com.typefree.ime.ime.engine.KeyInput
import com.typefree.ime.ime.flypy.FlypyDictionary
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

    fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File
    fun getLastRecordingWavFile(): File
    fun hasRetryWavFile(): Boolean
}

/**
 * Controller encapsulating QWERTY, Xiaohe Yinxing ([FlypyInputEngine]), Rime ([InputEngine]),
 * candidate strip state, punctuation shortcuts, and editor actions.
 */
class TypingController(
    private val host: ImeHostCallbacks,
    val flypyInputEngine: FlypyInputEngine = FlypyInputEngine()
) {
    var rimeInputEngine: InputEngine? = null
    var isRimeReady: Boolean = false

    val activeEngine: InputEngine
        get() {
            val scheme = host.repository.settings.value.inputScheme
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
        flypyInputEngine.setIncognitoMode(incognito)
        rimeInputEngine?.setIncognitoMode(incognito)
    }

    fun setFixedCandidateOrder(fixedOrder: Boolean?) {
        flypyInputEngine.setFixedCandidateOrder(fixedOrder)
        rimeInputEngine?.setFixedCandidateOrder(fixedOrder)
    }

    fun resetComposing() {
        flypyInputEngine.clear()
        rimeInputEngine?.clear()
        syncEngineStateToUi()
    }

    fun clearComposing(): Boolean {
        val wasComposing = isComposing
        if (!wasComposing) return false
        activeEngine.clear()
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
        val scheme = host.repository.settings.value.inputScheme
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
            }
            EngineResult.PassThrough -> {
                syncEngineStateToUi()
            }
            is EngineResult.Commit -> {
                syncEngineStateToUi()
                if (result.text.isNotEmpty()) {
                    host.commitDirectText(
                        text = result.text,
                        recordUndo = true,
                        markManualEdit = true,
                        playFeedback = false
                    )
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
        val nextChinese = !wasChinese
        val currentInputType = host.stateFlow.value.currentInputType
        if (!ImeLogic.isPasswordEditorInputType(currentInputType)) {
            host.repository.updateSettings {
                it.copy(
                    lastKeyboardTab = KeyboardTabMode.QWERTY.name,
                    lastQwertyIsChinese = nextChinese
                )
            }
        }
        val scheme = host.repository.settings.value.inputScheme
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
                host.commitDirectText(
                    text = flushed + ch,
                    recordUndo = true,
                    markManualEdit = true
                )
                return
            }
        }
        host.commitDirectText(text = ch, recordUndo = true, markManualEdit = true)
    }

    fun handleCommaOrPeriodKey(isComma: Boolean) {
        val curr = host.stateFlow.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode) {
            host.performKeyFeedback(strong = false)
            val quickSelect = host.repository.settings.value.flypyCommaPeriodQuickSelect
            val result = activeEngine.onKey(
                KeyInput.CommaOrPeriod(
                    isComma = isComma,
                    commaPeriodQuickSelect = quickSelect
                )
            )
            handleEngineResult(result)
            return
        }
        host.commitDirectText(
            text = if (isComma) "," else ".",
            recordUndo = true,
            markManualEdit = true
        )
    }

    fun selectFlypyCandidate(index: Int) {
        host.performKeyFeedback(strong = false)
        val result = activeEngine.select(index)
        handleEngineResult(result)
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
        val curr = host.stateFlow.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode && isComposing) {
            host.performKeyFeedback(strong = false)
            val result = activeEngine.onKey(KeyInput.Space)
            handleEngineResult(result)
            return
        }
        host.tryAutoLearnFromRecentEdit()
        host.commitDirectText(" ", recordUndo = false, markManualEdit = false)
    }

    fun deleteBackward() {
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
        val curr = host.stateFlow.value
        if (curr.tabMode == KeyboardTabMode.QWERTY && curr.isFlypyChineseMode && isComposing) {
            val res = activeEngine.onKey(KeyInput.Enter)
            syncEngineStateToUi()
            if (res is EngineResult.Commit && res.text.isNotEmpty()) {
                host.commitDirectText(
                    text = res.text,
                    recordUndo = true,
                    markManualEdit = true,
                    playFeedback = true
                )
                return
            }
        }

        val settingsSnap = host.repository.settings.value
        if (settingsSnap.equalEqualAskAiEnabled) {
            val fullText = host.readFullEditorTextSafely()
            val directQuery = SnippetMatcher.extractEqualEqualAskAiQuery(fullText)
            if (directQuery != null) {
                onRunEqualEqualAskAi(fullText, directQuery)
                return
            }
        }

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

    fun insertPairedSymbol(left: String, right: String) {
        host.performKeyFeedback(strong = false)
        val ic = host.currentInputConnection() ?: return
        val selected = host.readSelectedTextSafely()
        ic.beginBatchEdit()
        if (selected.isNotEmpty()) {
            ic.commitText("$left$selected$right", 1)
        } else {
            ic.commitText("$left$right", 1)
            val extracted = try {
                ic.getExtractedText(ExtractedTextRequest(), 0)
            } catch (_: Exception) {
                null
            }
            if (extracted != null && extracted.selectionStart >= right.length) {
                val cursorInside = extracted.selectionStart - right.length
                ic.setSelection(cursorInside, cursorInside)
            }
        }
        ic.endBatchEdit()
    }
}
