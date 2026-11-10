package com.typefree.ime.ime.predict

import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.flypy.FlypyCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates L1 ([StaticPredictor]), L2 ([PredictionStore]), and L3 ([AiSentenceCompleter])
 * predictions, auto-dismiss lifecycle rules, and incognito protection for TypeFree v2.7.
 */
class PredictionCoordinator(
    private val scope: CoroutineScope,
    private val stateFlow: MutableStateFlow<ImeUiState>,
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    private val activeStyleResolver: (packageName: String, inputType: Int) -> ToneStyle = { _, _ ->
        DefaultPresets.defaultStyles().first()
    },
    private val surroundingTextReader: (maxChars: Int) -> String = { "" },
    val predictionStore: PredictionStore = PredictionStore.inMemory(),
    val staticPredictor: StaticPredictor = StaticPredictor(),
    val aiSentenceCompleter: AiSentenceCompleter = AiSentenceCompleter(scope = scope),
    private val idleDismissMs: Long = IDLE_DISMISS_MS,
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES
) {

    @Volatile
    var lastCommittedWord: String? = null
        internal set

    @Volatile
    var isIncognitoMode: Boolean = false
        private set

    private var idleDismissJob: Job? = null

    init {
        // Observe panel / mode transitions on stateFlow so switching tabs or opening
        // Clipboard / ToolPanel automatically exits prediction mode.
        val initial = stateFlow.value
        var lastTab: KeyboardTabMode = initial.tabMode
        var lastClipboard: Boolean = initial.isClipboardOpen
        var lastToolPanel: Boolean = initial.isToolPanelOpen
        var lastChineseMode: Boolean = initial.isFlypyChineseMode
        scope.launch {
            stateFlow.collect { curr ->
                val tabChanged = curr.tabMode != lastTab
                val clipboardOpened = !lastClipboard && curr.isClipboardOpen
                val toolPanelOpened = !lastToolPanel && curr.isToolPanelOpen
                val exitedChinese = lastChineseMode && !curr.isFlypyChineseMode

                lastTab = curr.tabMode
                lastClipboard = curr.isClipboardOpen
                lastToolPanel = curr.isToolPanelOpen
                lastChineseMode = curr.isFlypyChineseMode

                if (tabChanged || clipboardOpened || toolPanelOpened || exitedChinese) {
                    if (curr.predictionCandidates.isNotEmpty() ||
                        curr.aiCompletionSuggestion != null ||
                        idleDismissJob?.isActive == true ||
                        aiSentenceCompleter.isPending
                    ) {
                        onPanelSwitched()
                    } else {
                        lastCommittedWord = null
                    }
                }
            }
        }
    }

    private fun effectiveIncognito(): Boolean {
        return isIncognitoMode || stateFlow.value.isIncognitoMode || predictionStore.isIncognitoMode
    }

    fun setIncognitoMode(incognito: Boolean) {
        isIncognitoMode = incognito
        predictionStore.isIncognitoMode = incognito
        if (incognito) {
            lastCommittedWord = null
            aiSentenceCompleter.cancel()
            stateFlow.update {
                it.copy(
                    isIncognitoMode = true,
                    aiCompletionSuggestion = null
                )
            }
        } else {
            stateFlow.update {
                it.copy(isIncognitoMode = false)
            }
        }
    }

    /**
     * Merges L2 personal bigrams ([PredictionStore]) before L1 static predictions ([StaticPredictor]),
     * deduplicating and excluding self-repetition of [prevWord].
     */
    fun mergePredictions(prevWord: String, limit: Int = maxCandidates): List<FlypyCandidate> {
        if (limit <= 0) return emptyList()
        val cleanPrev = prevWord.trim()
        if (!PredictionStore.isValidWord(cleanPrev)) return emptyList()

        val l2Words = predictionStore.queryNextWords(cleanPrev, limit = limit)
        val l1Words = staticPredictor.predictWords(cleanPrev, limit = limit)

        val merged = ArrayList<FlypyCandidate>(limit)
        val seen = HashSet<String>(limit * 2)
        seen.add(cleanPrev) // Exclude self-repetition

        for (w in l2Words) {
            val trimmed = w.trim()
            if (trimmed.isNotEmpty() && PredictionStore.isValidWord(trimmed) && seen.add(trimmed)) {
                merged.add(FlypyCandidate(text = trimmed))
                if (merged.size >= limit) return merged
            }
        }

        for (w in l1Words) {
            val trimmed = w.trim()
            if (trimmed.isNotEmpty() && PredictionStore.isValidWord(trimmed) && seen.add(trimmed)) {
                merged.add(FlypyCandidate(text = trimmed))
                if (merged.size >= limit) return merged
            }
        }

        return merged
    }

    /**
     * Called when a word or symbol is committed from the input engine or prediction bar.
     */
    fun onWordCommitted(committedText: String, isComposing: Boolean = false) {
        aiSentenceCompleter.cancel()
        cancelIdleTimer()

        val trimmed = committedText.trim()
        if (trimmed.isEmpty()) {
            dismissPredictions(resetChain = false)
            return
        }

        // 1. Pure punctuation / symbol input -> break bigram chain and exit prediction bar immediately
        if (PredictionStore.isPunctuationOrSymbolOnly(trimmed)) {
            onPunctuationCommitted()
            return
        }

        // 2. Word + trailing punctuation (e.g. "你好，" from comma/period top-up) -> record word bigram then break chain
        val leadingWord = trimmed.dropLastWhile { PredictionStore.isPunctuationChar(it) }.trim()
        if (leadingWord.length < trimmed.length) {
            val prev = lastCommittedWord
            if (!effectiveIncognito() && prev != null && PredictionStore.isValidWord(leadingWord)) {
                predictionStore.recordBigram(prev, leadingWord, isIncognito = false)
            }
            onPunctuationCommitted()
            return
        }

        // 3. Normal committed word
        val isValid = PredictionStore.isValidWord(trimmed)
        if (isValid) {
            val prev = lastCommittedWord
            if (!effectiveIncognito() && prev != null) {
                predictionStore.recordBigram(prev, trimmed, isIncognito = false)
            }
            lastCommittedWord = trimmed
        } else {
            lastCommittedWord = null
        }

        if (isComposing) {
            stateFlow.update {
                it.copy(
                    predictionCandidates = emptyList(),
                    aiCompletionSuggestion = null
                )
            }
            return
        }

        val settings = settingsProvider()
        val nextPredictions = if (settings.predictionEnabled && isValid) {
            mergePredictions(trimmed, maxCandidates)
        } else {
            emptyList()
        }

        stateFlow.update {
            it.copy(
                predictionCandidates = nextPredictions,
                aiCompletionSuggestion = null
            )
        }

        val shouldRunAi = settings.aiCompletionEnabled && !effectiveIncognito() && isValid
        if (shouldRunAi) {
            val surrounding = surroundingTextReader(AiSentenceCompleter.MAX_CONTEXT_CHARS)
            val contextWindow = if (surrounding.isNotBlank()) surrounding else trimmed
            val uiSnap = stateFlow.value
            val activeStyle = activeStyleResolver(uiSnap.targetPackage, uiSnap.currentInputType)
            aiSentenceCompleter.scheduleCompletion(
                contextBeforeCursor = contextWindow,
                settings = settings,
                activeStyle = activeStyle,
                targetAppName = uiSnap.targetAppName,
                isIncognitoMode = effectiveIncognito(),
                onResult = { suggestion ->
                    if (!stateFlow.value.isFlypyComposing) {
                        stateFlow.update { curr ->
                            curr.copy(aiCompletionSuggestion = suggestion)
                        }
                    }
                }
            )
        }

        if (nextPredictions.isNotEmpty() || shouldRunAi) {
            restartIdleTimer()
        }
    }

    /**
     * Cancels pending L3 AI request and 4s idle timer on any new key press.
     */
    fun onUserKeyPressed() {
        aiSentenceCompleter.cancel()
        cancelIdleTimer()
    }

    /**
     * Called when the user enters `isComposing` (starts typing a new code):
     * exits the prediction bar and cancels L3/idle timer while keeping [lastCommittedWord]
     * so the bigram transition can be learned when the new composing word is committed.
     */
    fun onComposingStarted() {
        dismissPredictions(resetChain = false)
    }

    /**
     * Punctuation input immediately exits predictions and breaks the [lastCommittedWord] chain.
     */
    fun onPunctuationCommitted() {
        dismissPredictions(resetChain = true)
    }

    /**
     * Backspace immediately exits predictions and breaks the [lastCommittedWord] chain.
     */
    fun onBackspacePressed() {
        dismissPredictions(resetChain = true)
    }

    /**
     * Switching panels (`selectTabMode`, opening clipboard, opening tool panel) immediately exits predictions.
     */
    fun onPanelSwitched() {
        dismissPredictions(resetChain = true)
    }

    /**
     * Dismisses the prediction bar (`×` button or auto-exit), clearing `predictionCandidates`
     * and `aiCompletionSuggestion` and restoring the 34dp top bar.
     */
    fun dismissPredictions(resetChain: Boolean = false) {
        aiSentenceCompleter.cancel()
        cancelIdleTimer()
        if (resetChain) {
            lastCommittedWord = null
        }
        stateFlow.update {
            if (it.predictionCandidates.isEmpty() && it.aiCompletionSuggestion == null) {
                it
            } else {
                it.copy(
                    predictionCandidates = emptyList(),
                    aiCompletionSuggestion = null
                )
            }
        }
    }

    /**
     * Selects the prediction candidate at [index], advancing the prediction chain to the selected word.
     * Returns the selected word text, or `null` if [index] is out of bounds.
     */
    fun selectPredictionCandidate(index: Int): String? {
        val picked = stateFlow.value.predictionCandidates.getOrNull(index)?.text ?: return null
        onWordCommitted(committedText = picked, isComposing = false)
        return picked
    }

    /**
     * Adopts the full L3 AI completion suggestion and clears the prediction bar.
     * Returns the adopted suggestion text, or `null` if none is present.
     */
    fun selectAiCompletion(): String? {
        val suggestion = stateFlow.value.aiCompletionSuggestion?.takeIf { it.isNotBlank() } ?: return null
        dismissPredictions(resetChain = true)
        return suggestion
    }

    /**
     * Exposed for deterministic testing or timer callback when 4s idle window elapses.
     */
    fun onIdleTimeout() {
        dismissPredictions(resetChain = false)
    }

    private fun restartIdleTimer() {
        cancelIdleTimer()
        if (idleDismissMs <= 0L) return
        idleDismissJob = scope.launch {
            delay(idleDismissMs)
            onIdleTimeout()
        }
    }

    private fun cancelIdleTimer() {
        idleDismissJob?.cancel()
        idleDismissJob = null
    }

    companion object {
        const val IDLE_DISMISS_MS: Long = 4_000L
        const val DEFAULT_MAX_CANDIDATES: Int = 8
    }
}
