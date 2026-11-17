package com.typefree.ime.v360

import com.typefree.core.ime.coordinator.CommitAction
import com.typefree.core.ime.coordinator.CoordinatorModeBadge
import com.typefree.core.ime.coordinator.CoordinatorUiSnapshot
import com.typefree.core.ime.coordinator.EnhancedCandidate
import com.typefree.core.ime.profile.SchemaProfile
import com.typefree.ime.commit.AndroidCommitExecutor
import com.typefree.ime.commit.EditorBatchConnection
import com.typefree.ime.ui.CandidateBarViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host `:app` acceptance tests verifying `AndroidCommitExecutor` Mock InputConnection
 * paired-symbol cursor centering & `;i` atomic undo (`QA-OVERLAY-02`), 4-byte CJK Ext-B
 * surrogate pair undo, `Auto-Learn v2.0` isolation, and `CandidateBarViewModel` mapping.
 */
class XiaoheCodetableAcceptanceTest {

    private class MockBufferInputConnection : EditorBatchConnection {
        val buffer = StringBuilder()
        var selectionStart: Int = 0
        var selectionEnd: Int = 0
        var batchDepth: Int = 0
        val commitHistory = ArrayList<Pair<String, Int>>()

        override fun beginBatchEdit(): Boolean {
            batchDepth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            batchDepth = (batchDepth - 1).coerceAtLeast(0)
            return batchDepth == 0
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            val s = selectionStart.coerceIn(0, buffer.length)
            val e = selectionEnd.coerceIn(s, buffer.length)
            buffer.replace(s, e, text.toString())
            val nextCursor = if (newCursorPosition > 0) {
                s + text.length
            } else {
                s
            }
            selectionStart = nextCursor
            selectionEnd = nextCursor
            commitHistory.add(text.toString() to newCursorPosition)
            return true
        }

        override fun getExtractedSelectionStart(): Int = selectionStart

        override fun setSelection(start: Int, end: Int): Boolean {
            selectionStart = start.coerceIn(0, buffer.length)
            selectionEnd = end.coerceIn(0, buffer.length)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val delAfterEnd = (selectionEnd + afterLength).coerceAtMost(buffer.length)
            if (delAfterEnd > selectionEnd) {
                buffer.delete(selectionEnd, delAfterEnd)
            }
            val delBeforeStart = (selectionStart - beforeLength).coerceAtLeast(0)
            if (selectionStart > delBeforeStart) {
                buffer.delete(delBeforeStart, selectionStart)
            }
            selectionStart = delBeforeStart
            selectionEnd = delBeforeStart
            return true
        }

        override fun sendDpadLeftKeyEvent() {
            if (selectionStart > 0) {
                selectionStart--
                selectionEnd = selectionStart
            }
        }
    }

    @Test
    fun `QA-OVERLAY-02 Mock InputConnection paired bracket cursor centering and atomic undo`() {
        val mockIc = MockBufferInputConnection()
        val xiaoheProfile = SchemaProfile.defaultXiaoheFlypy()
        val executor = AndroidCommitExecutor(connectionProvider = { mockIc })

        // 1) `;o` -> commitText("「」", 1) + cursor centered at index 1
        executor.execute(CommitAction.PairedText("「", "」"), profile = xiaoheProfile)
        assertEquals(listOf("「」" to 1), mockIc.commitHistory)
        assertEquals("「」", mockIc.buffer.toString())
        assertEquals("Cursor must sit at index 1 between 「 and 」", 1, mockIc.selectionStart)
        assertEquals(1, mockIc.selectionEnd)
        assertEquals(0, mockIc.batchDepth)

        // 2) Immediate `;i` -> atomically deletes both 「 and 」
        executor.execute(CommitAction.UndoLastCommit, profile = xiaoheProfile)
        assertEquals("Both brackets must be atomically undone", "", mockIc.buffer.toString())
        assertEquals(0, mockIc.selectionStart)

        // 3) 4-byte CJK Ext-B `𩽾` (2 UTF-16 code units) commit + `;f` repeat + `;i` undo
        val extB = "\uD867\uDF7E"
        executor.execute(CommitAction.PlainText(extB), profile = xiaoheProfile)
        assertEquals(extB, mockIc.buffer.toString())
        executor.execute(CommitAction.RepeatLastCommit, profile = xiaoheProfile)
        assertEquals(extB + extB, mockIc.buffer.toString())
        executor.execute(CommitAction.UndoLastCommit, profile = xiaoheProfile)
        assertEquals(extB, mockIc.buffer.toString())
        assertEquals("Xiaohe codetable must NEVER increment Auto-Learn count", 0, executor.autoLearnCommitCount)
    }

    @Test
    fun `CandidateBarViewModel maps reverse-split preedit and suffix-only secondary candidate`() {
        val snapshot = CoordinatorUiSnapshot(
            isComposing = true,
            rawCode = "hfkn",
            preedit = "hf'kn",
            badge = CoordinatorModeBadge.NONE,
            candidates = listOf(
                EnhancedCandidate(displayText = "很可能", commitText = "很可能"),
                EnhancedCandidate(displayText = "困难", commitText = "很困难")
            ),
            isReverseSplitActive = true
        )
        val vm = CandidateBarViewModel()
        val state = vm.updateFromCoordinator(snapshot)
        assertTrue(state.isComposing)
        assertEquals("hf'kn", state.preeditText)
        assertEquals("很可能", state.candidates[0].displayText)
        assertEquals("很可能", state.candidates[0].text)
        assertEquals("困难", state.candidates[1].displayText)
        assertEquals("很困难", state.candidates[1].text)
    }
}
