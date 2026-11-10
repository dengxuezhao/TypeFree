package com.typefree.ime.ime.engine

import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.ime.flypy.FlypyAction
import com.typefree.ime.ime.flypy.FlypyDictionary
import com.typefree.ime.ime.flypy.FlypyEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [InputEngine] adapter wrapping the existing [FlypyEngine] with 0 behavior change.
 */
class FlypyInputEngine(
    val delegate: FlypyEngine = FlypyEngine()
) : InputEngine {

    private val _state = MutableStateFlow(snapshotState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    val dictionary: FlypyDictionary
        get() = delegate.dictionary

    val isComposing: Boolean
        get() = delegate.isComposing

    fun updateDictionary(newDict: FlypyDictionary, nowMillis: Long = System.currentTimeMillis()) {
        delegate.updateDictionary(newDict, nowMillis)
        syncState()
    }

    override fun updateSnippets(snippets: List<SnippetEntry>, nowMillis: Long) {
        delegate.updateSnippets(snippets, nowMillis)
        syncState()
    }

    override fun recordExternalCommit(text: String) {
        delegate.recordExternalCommit(text)
    }

    override fun onKey(key: KeyInput): EngineResult {
        return when (key) {
            is KeyInput.CharKey -> {
                val action = delegate.onCharInput(key.char, key.nowMillis)
                syncState()
                mapAction(action)
            }
            is KeyInput.Backspace -> {
                val consumed = delegate.onBackspace(key.nowMillis)
                syncState()
                if (consumed) EngineResult.Consumed else EngineResult.PassThrough
            }
            KeyInput.Space -> {
                if (!delegate.isComposing) {
                    EngineResult.PassThrough
                } else {
                    val action = delegate.onSpace()
                    syncState()
                    mapAction(action)
                }
            }
            KeyInput.Enter -> {
                val raw = delegate.onEnterWhenComposing()
                syncState()
                if (raw != null) {
                    EngineResult.Commit(raw, remainComposing = false)
                } else {
                    EngineResult.PassThrough
                }
            }
            is KeyInput.CommaOrPeriod -> {
                val action = delegate.onCommaOrPeriod(
                    isComma = key.isComma,
                    commaPeriodQuickSelect = key.commaPeriodQuickSelect
                )
                syncState()
                mapAction(action)
            }
        }
    }

    override fun select(index: Int): EngineResult {
        val action = delegate.selectCandidate(index)
        syncState()
        return mapAction(action)
    }

    override fun clear() {
        delegate.resetComposing()
        syncState()
    }

    fun clearComposing(): Boolean {
        val cleared = delegate.clearComposing()
        if (cleared) syncState()
        return cleared
    }

    override fun flush(): String {
        val flushed = delegate.flushTopCandidateOrClear()
        syncState()
        return flushed
    }

    private fun mapAction(action: FlypyAction): EngineResult {
        return when (action) {
            FlypyAction.ConsumedComposing -> EngineResult.Consumed
            is FlypyAction.CommitText -> EngineResult.Commit(
                text = action.committed,
                remainComposing = action.remainComposing
            )
        }
    }

    fun syncState() {
        _state.value = snapshotState()
    }

    private fun snapshotState(): EngineState {
        val code = delegate.composingCode
        val cands = delegate.candidates
        return EngineState(
            preedit = code,
            rawCode = code,
            candidates = cands,
            hasMore = false,
            isComposing = code.isNotEmpty()
        )
    }
}
