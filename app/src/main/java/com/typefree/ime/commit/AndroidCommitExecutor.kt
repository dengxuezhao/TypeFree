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
    private val onAutoLearnEligibleCommit: (String) -> Unit = {}
) {
    var lastCommittedText: String = ""
        private set

    var lastCommittedCodeUnits: Int = 0
        private set

    private var centeredPairUnits: Pair<Int, Int>? = null

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

                if (profile.allowAutoLearn && feedToAutoLearn) {
                    autoLearnCommitCount++
                    onAutoLearnEligibleCommit(action.text)
                }
            }

            is CommitAction.PairedText -> {
                val ic = connectionProvider() ?: return
                val combined = action.left + action.right
                ic.beginBatchEdit()
                ic.commitText(combined, 1)
                val cursor = ic.getExtractedSelectionStart()
                if (cursor != null && cursor >= action.right.length) {
                    val target = cursor - action.right.length
                    ic.setSelection(target, target)
                } else {
                    repeat(action.right.codePointCount(0, action.right.length).coerceAtLeast(1)) {
                        ic.sendDpadLeftKeyEvent()
                    }
                }
                ic.endBatchEdit()

                lastCommittedText = combined
                lastCommittedCodeUnits = combined.length
                centeredPairUnits = action.left.length to action.right.length
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

    fun notifyExternalCursorChange() {
        centeredPairUnits = null
    }

    companion object {
        fun fromInputConnection(
            inputConnectionProvider: () -> InputConnection?,
            onCopyClipboard: (text: String, toast: String) -> Unit = { _, _ -> },
            onDirectCommand: (DirectCommandType) -> Unit = {},
            onAutoLearnEligibleCommit: (String) -> Unit = {}
        ): AndroidCommitExecutor {
            return AndroidCommitExecutor(
                connectionProvider = { inputConnectionProvider()?.let(::RealInputConnectionAdapter) },
                onCopyClipboard = onCopyClipboard,
                onDirectCommand = onDirectCommand,
                onAutoLearnEligibleCommit = onAutoLearnEligibleCommit
            )
        }
    }
}
