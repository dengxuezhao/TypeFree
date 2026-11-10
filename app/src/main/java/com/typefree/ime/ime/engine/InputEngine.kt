package com.typefree.ime.ime.engine

import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.ime.flypy.FlypyCandidate
import kotlinx.coroutines.flow.StateFlow

data class EngineState(
    val preedit: String = "",
    val rawCode: String = "",
    val candidates: List<FlypyCandidate> = emptyList(),
    val hasMore: Boolean = false,
    val isComposing: Boolean = false
)

sealed interface KeyInput {
    data class CharKey(
        val char: Char,
        val nowMillis: Long = System.currentTimeMillis()
    ) : KeyInput

    data class Backspace(
        val nowMillis: Long = System.currentTimeMillis()
    ) : KeyInput

    data object Space : KeyInput
    data object Enter : KeyInput

    data class CommaOrPeriod(
        val isComma: Boolean,
        val commaPeriodQuickSelect: Boolean = true
    ) : KeyInput
}

sealed interface EngineResult {
    data class Commit(
        val text: String,
        val remainComposing: Boolean = false
    ) : EngineResult

    data object Consumed : EngineResult
    data object PassThrough : EngineResult
}

/**
 * Unified typing engine contract for TypeFree v2.7, implemented by both
 * [FlypyInputEngine] (in-memory 4-code Xiaohe Yinxing / custom TSV) and
 * `RimeInputEngine` (native librime JNI for Pinyin, Double Pinyin, and custom Rime schemas).
 */
interface InputEngine {
    val state: StateFlow<EngineState>
    fun onKey(key: KeyInput): EngineResult
    fun select(index: Int): EngineResult
    fun clear()
    fun flush(): String

    fun updateSnippets(snippets: List<SnippetEntry>, nowMillis: Long = System.currentTimeMillis()) {}
    fun recordExternalCommit(text: String) {}
    fun requestMoreCandidates(minCount: Int = 60) {}
    fun setIncognitoMode(incognito: Boolean) {}
    fun setFixedCandidateOrder(fixedOrder: Boolean?) {}
}
