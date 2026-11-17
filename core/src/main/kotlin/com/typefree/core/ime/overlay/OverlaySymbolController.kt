package com.typefree.core.ime.overlay

import com.typefree.core.ime.coordinator.CommitAction
import com.typefree.core.ime.coordinator.EnhancedCandidate

sealed interface OverlayStepResult {
    data object StayInOverlay : OverlayStepResult

    data class CommitAndExit(
        val action: CommitAction,
        val replayCharToMainSchema: Char? = null
    ) : OverlayStepResult

    data object CancelledAndExit : OverlayStepResult
}

/**
 * Deterministic bimodal non-code-element quick-symbol controller aligned with WindInput `[overlay]`
 * and Xiaohe Flypy author specification:
 * - Idle `;` enters overlay mode without feeding `;` to `librime`.
 * - Unique leaf (`;a -> ！`, `;o -> 「|」`, `;mq -> ①`, `;i -> Undo`, `;f -> Repeat`) commits & exits immediately.
 * - Branch node (`;m -> @` with children `mq..mp`) stays in overlay; `Space` selects `@`, `q` commits `①`.
 * - Double `;` (`;;`) outputs `；` (or `;` in `asciiPunct` mode) and exits.
 * - `Backspace` on empty code or `Escape` cancels and exits cleanly; focus change resets state.
 */
class OverlaySymbolController(
    var trie: SymbolTrie = SymbolTrie.defaultXiaohe()
) {
    var isActive: Boolean = false
        private set

    var buffer: String = ""
        private set

    val preeditWithGuide: String
        get() = if (isActive) "${trie.guideKey}$buffer" else ""

    fun enter(): OverlayStepResult {
        isActive = true
        buffer = ""
        return OverlayStepResult.StayInOverlay
    }

    fun reset() {
        isActive = false
        buffer = ""
    }

    fun updateTrie(newTrie: SymbolTrie) {
        trie = newTrie
        reset()
    }

    fun currentCandidates(lastCommittedPreview: String = ""): List<EnhancedCandidate> {
        if (!isActive) return emptyList()
        return trie.collectCandidates(buffer, lastCommittedPreview)
    }

    fun onChar(ch: Char, asciiPunct: Boolean = false, lastCommittedPreview: String = ""): OverlayStepResult {
        if (!isActive) return OverlayStepResult.CancelledAndExit

        // Double tap guide key (e.g. `;;`) when overlay buffer is empty -> output `；` / `;` and exit
        if (ch == trie.guideKey && buffer.isEmpty()) {
            val out = if (asciiPunct) trie.doubleTapAsciiCommit else trie.doubleTapCommit
            reset()
            return OverlayStepResult.CommitAndExit(CommitAction.PlainText(out))
        }

        val nextBuf = buffer + ch
        val node = trie.findNode(nextBuf)

        if (node != null) {
            // Unique leaf node with no continuation -> immediate commit & exit
            if (!node.hasChildren && node.entry != null) {
                val act = node.entry!!.action
                reset()
                return OverlayStepResult.CommitAndExit(act)
            }
            // Branch node (e.g. `;m` has children `mq..mp`) -> wait for 2nd key or Space
            buffer = nextBuf
            return OverlayStepResult.StayInOverlay
        }

        // Non-trie character pressed while in overlay:
        // 1) If buffer already holds a valid branch entry (e.g. `;m`), commit that entry;
        //    if `ch` is a letter/guide, replay to main schema; if `ch` is punctuation, append punctuation and exit.
        val currentEntry = if (buffer.isNotEmpty()) trie.findNode(buffer)?.entry else null
        if (currentEntry != null) {
            reset()
            return if (ch in 'a'..'z' || ch in 'A'..'Z') {
                OverlayStepResult.CommitAndExit(
                    action = currentEntry.action,
                    replayCharToMainSchema = ch
                )
            } else {
                OverlayStepResult.CommitAndExit(
                    action = CommitAction.Composite(
                        listOf(currentEntry.action, CommitAction.PlainText(ch.toString()))
                    )
                )
            }
        }

        // 2) Empty overlay buffer + unmapped punctuation -> commit punctuation and exit;
        //    unmapped alpha -> cancel overlay cleanly
        reset()
        return if (ch !in 'a'..'z' && ch !in 'A'..'Z') {
            OverlayStepResult.CommitAndExit(CommitAction.PlainText(ch.toString()))
        } else {
            OverlayStepResult.CancelledAndExit
        }
    }

    fun onSpace(lastCommittedPreview: String = ""): OverlayStepResult {
        if (!isActive) return OverlayStepResult.CancelledAndExit
        val top = currentCandidates(lastCommittedPreview).firstOrNull()
        reset()
        return if (top != null) {
            OverlayStepResult.CommitAndExit(top.action)
        } else {
            OverlayStepResult.CancelledAndExit
        }
    }

    fun onSelectCandidate(index: Int, lastCommittedPreview: String = ""): OverlayStepResult {
        if (!isActive) return OverlayStepResult.CancelledAndExit
        val picked = currentCandidates(lastCommittedPreview).getOrNull(index)
            ?: return OverlayStepResult.StayInOverlay
        reset()
        return OverlayStepResult.CommitAndExit(picked.action)
    }

    fun onBackspace(): OverlayStepResult {
        if (!isActive) return OverlayStepResult.CancelledAndExit
        if (buffer.isEmpty()) {
            reset()
            return OverlayStepResult.CancelledAndExit
        }
        buffer = buffer.dropLast(1)
        return OverlayStepResult.StayInOverlay
    }

    fun onEscape(): OverlayStepResult {
        reset()
        return OverlayStepResult.CancelledAndExit
    }
}
