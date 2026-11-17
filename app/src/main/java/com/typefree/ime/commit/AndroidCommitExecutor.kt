package com.typefree.ime.commit

import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.typefree.core.ime.coordinator.CommitAction
import com.typefree.core.ime.coordinator.DirectCommandType
import com.typefree.core.ime.profile.SchemaProfile

/**
 * Test-friendly abstraction over Android [InputConnection] batch editing so both live Android
 * editors and pure JVM unit tests (`QA-OVERLAY-02`) run the exact same batch cursor-centering
 * and atomic undo state machine.
 */
interface EditorBatchConnection {
    fun beginBatchEdit(): Boolean
    fun endBatchEdit(): Boolean
    fun commitText(text: CharSequence, newCursorPosition: Int): Boolean
    fun getExtractedSelectionStart(): Int?
    fun setSelection(start: Int, end: Int): Boolean
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
    fun sendDpadLeftKeyEvent()
}

class RealInputConnectionAdapter(
    private val ic: InputConnection
) : EditorBatchConnection {
    override fun beginBatchEdit(): Boolean = ic.beginBatchEdit()
    override fun endBatchEdit(): Boolean = ic.endBatchEdit()
    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean =
        ic.commitText(text, newCursorPosition)

    override fun getExtractedSelectionStart(): Int? {
        return runCatching {
            ic.getExtractedText(ExtractedTextRequest(), 0)?.selectionStart?.takeIf { it >= 0 }
        }.getOrNull()
    }

    override fun setSelection(start: Int, end: Int): Boolean = ic.setSelection(start, end)

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        ic.deleteSurroundingText(beforeLength, afterLength)

    override fun sendDpadLeftKeyEvent() {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
    }
}

/**
 * Executes [CommitAction] on Android [InputConnection]:
 * - Batch paired-bracket commit + flicker-free cursor centering (`;o -> 「|」`) with `DPAD_LEFT` fallback,
 * - Atomic `;i` (`UndoLastCommit`) across both centered pairs and 4-byte UTF-16 surrogate pairs,
 * - `;f` (`RepeatLastCommit`), clipboard copy (`ofi`), and `DirectCommandType` host callbacks,
 * - Strict `SchemaProfile.allowAutoLearn` isolation (`FIXED_YINXING` / `PUSH_TOPUP_CODE` / `FIXED_SHAPECODE`
 *   never feed `Auto-Learn Engine v2.0`).
 */
class AndroidCommitExecutor(
    private val connectionProvider: () -> EditorBatchConnection?,
    private val onCopyClipboard: (text: String, toast: String) -> Unit = { _, _ -> },
    private val onDirectCommand: (DirectCommandType) -> Unit = {},
    private val onAutoLearnEligibleCommit: (String) -> Unit = {},
    private val onTextCommitted: (text: String, feedToAutoLearn: Boolean) -> Unit = { _, _ -> },
    private val onUndoExecuted: () -> Unit = {}
) {
    var lastCommittedText: String = ""
        private set

    var lastCommittedCodeUnits: Int = 0
        private set

    private var centeredPairUnits: Pair<Int, Int>? = null

    var expectedCenteredCursor: Int? = null
        private set

    private var isExecutingInternalCommit: Boolean = false

    var autoLearnCommitCount: Int = 0
        private set

    fun execute(
        action: CommitAction,
        profile: SchemaProfile,
        feedToAutoLearn: Boolean = profile.allowAutoLearn
    ) {
        when (action) {
            is CommitAction.PlainText -> {
                if (action.text.isEmpty()) return
                val ic = connectionProvider() ?: return
                ic.beginBatchEdit()
                ic.commitText(action.text, 1)
                ic.endBatchEdit()

                lastCommittedText = action.text
                lastCommittedCodeUnits = action.text.length
                centeredPairUnits = null
                expectedCenteredCursor = null

                val allowLearn = profile.allowAutoLearn && feedToAutoLearn
                if (allowLearn) {
                    autoLearnCommitCount++
                    onAutoLearnEligibleCommit(action.text)
                }
                isExecutingInternalCommit = true
                try {
                    onTextCommitted(action.text, allowLearn)
                } finally {
                    isExecutingInternalCommit = false
                }
            }

            is CommitAction.PairedText -> {
                val ic = connectionProvider() ?: return
                val combined = action.left + action.right
                val cursorBefore = ic.getExtractedSelectionStart()
                ic.beginBatchEdit()
                ic.commitText(combined, 1)
                val cursorAfter = ic.getExtractedSelectionStart()
                // Guard against async custom-drawn editors (e.g. WeChat / Feishu) returning stale
                // pre-commit cursor inside beginBatchEdit(): fall back to `cursorBefore + left.length`.
                val targetCursor: Int? = when {
                    cursorBefore != null && cursorAfter != null &&
                        cursorAfter != cursorBefore && cursorAfter >= action.right.length ->
                        cursorAfter - action.right.length
                    cursorBefore != null ->
                        cursorBefore + action.left.length
                    cursorAfter != null && cursorAfter >= combined.length ->
                        cursorAfter - action.right.length
                    else -> null
                }
                if (targetCursor != null && targetCursor >= 0) {
                    ic.setSelection(targetCursor, targetCursor)
                } else {
                    repeat(action.right.codePointCount(0, action.right.length).coerceAtLeast(1)) {
                        ic.sendDpadLeftKeyEvent()
                    }
                }
                ic.endBatchEdit()

                lastCommittedText = combined
                lastCommittedCodeUnits = combined.length
                centeredPairUnits = action.left.length to action.right.length
                expectedCenteredCursor = targetCursor

                val allowLearn = profile.allowAutoLearn && feedToAutoLearn
                if (allowLearn) {
                    autoLearnCommitCount++
                    onAutoLearnEligibleCommit(combined)
                }
                isExecutingInternalCommit = true
                try {
                    onTextCommitted(combined, allowLearn)
                } finally {
                    isExecutingInternalCommit = false
                }
            }

            CommitAction.RepeatLastCommit -> {
                if (lastCommittedText.isEmpty()) return
                execute(
                    action = CommitAction.PlainText(lastCommittedText),
                    profile = profile,
                    feedToAutoLearn = false
                )
            }

            CommitAction.UndoLastCommit -> {
                if (lastCommittedCodeUnits <= 0) return
                val ic = connectionProvider() ?: return
                val pair = centeredPairUnits
                ic.beginBatchEdit()
                if (pair != null) {
                    ic.deleteSurroundingText(pair.first, pair.second)
                } else {
                    ic.deleteSurroundingText(lastCommittedCodeUnits, 0)
                }
                ic.endBatchEdit()

                lastCommittedCodeUnits = 0
                centeredPairUnits = null
                expectedCenteredCursor = null
                onUndoExecuted()
            }

            is CommitAction.CopyToClipboard -> {
                onCopyClipboard(action.text, action.toastMessage)
            }

            is CommitAction.Command -> {
                onDirectCommand(action.commandType)
            }

            is CommitAction.Composite -> {
                for (step in action.steps) {
                    execute(step, profile = profile, feedToAutoLearn = feedToAutoLearn)
                }
            }
        }
    }

    fun recordExternalCommit(text: String) {
        if (isExecutingInternalCommit || text.isEmpty()) return
        lastCommittedText = text
        lastCommittedCodeUnits = text.length
        centeredPairUnits = null
        expectedCenteredCursor = null
    }

    fun notifyExternalCursorChange(newSelStart: Int = -1, newSelEnd: Int = -1) {
        if (newSelStart < 0 && newSelEnd < 0) {
            if (centeredPairUnits != null) {
                lastCommittedCodeUnits = 0
            }
            centeredPairUnits = null
            expectedCenteredCursor = null
            return
        }
        val movedAway = newSelStart != newSelEnd ||
            (expectedCenteredCursor != null && newSelStart != expectedCenteredCursor)
        if (movedAway) {
            if (centeredPairUnits != null) {
                lastCommittedCodeUnits = 0
            }
            centeredPairUnits = null
            expectedCenteredCursor = null
        }
    }

    companion object {
        fun fromInputConnection(
            inputConnectionProvider: () -> InputConnection?,
            onCopyClipboard: (text: String, toast: String) -> Unit = { _, _ -> },
            onDirectCommand: (DirectCommandType) -> Unit = {},
            onAutoLearnEligibleCommit: (String) -> Unit = {},
            onTextCommitted: (text: String, feedToAutoLearn: Boolean) -> Unit = { _, _ -> },
            onUndoExecuted: () -> Unit = {}
        ): AndroidCommitExecutor {
            return AndroidCommitExecutor(
                connectionProvider = { inputConnectionProvider()?.let(::RealInputConnectionAdapter) },
                onCopyClipboard = onCopyClipboard,
                onDirectCommand = onDirectCommand,
                onAutoLearnEligibleCommit = onAutoLearnEligibleCommit,
                onTextCommitted = onTextCommitted,
                onUndoExecuted = onUndoExecuted
            )
        }
    }
}
