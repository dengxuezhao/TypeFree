package com.typefree.ime.ime

import android.view.inputmethod.InputConnection
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.network.asr.LiveTranscript

/**
 * Lightweight abstraction over [InputConnection] enabling deterministic pure-JVM unit tests
 * for [StreamingComposer] without Android framework instrumentation.
 */
interface ComposerEditorBridge {
    fun beginBatchEdit(): Boolean
    fun endBatchEdit(): Boolean
    fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean
    fun commitText(text: CharSequence, newCursorPosition: Int): Boolean
    fun finishComposingText(): Boolean
    fun setComposingRegion(start: Int, end: Int): Boolean
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
    fun getTextBeforeCursor(n: Int, flags: Int): CharSequence?
}

/**
 * Production [ComposerEditorBridge] backed by the active Android IME [InputConnection].
 */
class InputConnectionEditorBridge(
    private val connectionProvider: () -> InputConnection?
) : ComposerEditorBridge {
    override fun beginBatchEdit(): Boolean =
        runCatching { connectionProvider()?.beginBatchEdit() ?: false }.getOrDefault(false)

    override fun endBatchEdit(): Boolean =
        runCatching { connectionProvider()?.endBatchEdit() ?: false }.getOrDefault(false)

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
        runCatching { connectionProvider()?.setComposingText(text, newCursorPosition) ?: false }.getOrDefault(false)

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean =
        runCatching { connectionProvider()?.commitText(text, newCursorPosition) ?: false }.getOrDefault(false)

    override fun finishComposingText(): Boolean =
        runCatching { connectionProvider()?.finishComposingText() ?: false }.getOrDefault(false)

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        runCatching { connectionProvider()?.setComposingRegion(start, end) ?: false }.getOrDefault(false)

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        runCatching { connectionProvider()?.deleteSurroundingText(beforeLength, afterLength) ?: false }.getOrDefault(false)

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        runCatching { connectionProvider()?.getTextBeforeCursor(n, flags) }.getOrNull()
}

/**
 * Outcome returned when [StreamingComposer] completes a voice turn (either SMART direct or Dual-Stage polished).
 */
data class ComposerFinalizeOutcome(
    val appliedToEditor: Boolean,
    val wasFrozen: Boolean,
    val rawWrittenText: String,
    val finalCommittedText: String,
    val undoSnapshot: UndoSnapshot?
)

/**
 * State machine that streams real-time ASR recognition directly into the host editor's
 * [InputConnection] (`Idle` -> `Live` -> `Frozen` -> `Finishing` -> `Polishing` -> `Done`).
 *
 * Implements Scheme 3.3 & 3.4:
 * 1. Mutable `interim` stays inside `setComposingText(interim, 1)` so self-corrections like `"你"` -> `"泥土"`
 *    replace in-place; newly finalized clauses call `commitText(newFinalDelta, 1)`.
 * 2. Throttled to at most 15 updates/sec (`66ms` minimum interval), skipping identical content,
 *    wrapping every update in `beginBatchEdit()` / `endBatchEdit()`, while flushing finalized advances
 *    and `finish` immediately without throttling.
 * 3. Tracks `anchor` and `totalWrittenLength`; if `onUpdateSelection` detects the user moved the cursor
 *    away from `anchor + totalWrittenLength`, immediately calls `finishComposingText()` and enters `Frozen`.
 * 4. On mic release in Dual-Stage mode, verifies `getTextBeforeCursor(writtenText.length, 0) == writtenText`
 *    before replacing with the LLM-polished text once via `setComposingRegion` + `commitText`
 *    (falling back to `deleteSurroundingText` + `commitText`). If verification fails, never overwrites.
 *    Saves `UndoSnapshot(replacedOriginalText = rawWrittenText, insertedTextLength = polishedText.length)`
 *    so 1-tap Undo restores the unpolished raw transcript.
 * 5. In SMART direct mode (Gemini Live SMART), transitions `Finishing -> Done` with zero post-release jump.
 */
class StreamingComposer(
    private val editorBridge: ComposerEditorBridge,
    private val minUpdateIntervalMs: Long = MIN_UPDATE_INTERVAL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    enum class State {
        Idle,
        Live,
        Frozen,
        Finishing,
        Polishing,
        Done
    }

    var state: State = State.Idle
        private set

    var anchor: Int = 0
        private set

    var isSmartDirectMode: Boolean = false
        private set

    var committedFinalText: String = ""
        private set

    var currentInterimText: String = ""
        private set

    var writtenBeforeFreeze: String = ""
        private set

    var wasFrozenDuringSession: Boolean = false
        private set

    private var latestObservedTranscript: LiveTranscript = LiveTranscript()
    private var pendingThrottledTranscript: LiveTranscript? = null
    private var lastFlushAtMs: Long = -10_000L
    private var isInBatchEdit: Boolean = false
    private var cursorMovedDuringPolish: Boolean = false
    private var frozenRemainderCommitted: Boolean = false

    val totalWrittenLength: Int
        get() = committedFinalText.length + currentInterimText.length

    val expectedCursorPosition: Int
        get() = anchor + totalWrittenLength

    val writtenRawText: String
        get() = committedFinalText + currentInterimText

    val isActive: Boolean
        get() = state == State.Live || state == State.Frozen || state == State.Finishing || state == State.Polishing

    val uiStatus: LiveWriteUiStatus
        get() = when (state) {
            State.Live, State.Finishing, State.Polishing -> {
                if (wasFrozenDuringSession) LiveWriteUiStatus.FROZEN else LiveWriteUiStatus.WRITING
            }
            State.Frozen -> LiveWriteUiStatus.FROZEN
            State.Idle, State.Done -> LiveWriteUiStatus.INACTIVE
        }

    /**
     * Transitions from `Idle` -> `Live` when the microphone is pressed and live-write conditions are met.
     */
    fun startSession(
        anchorCursor: Int,
        smartDirectMode: Boolean = false
    ) {
        anchor = anchorCursor.coerceAtLeast(0)
        isSmartDirectMode = smartDirectMode
        committedFinalText = ""
        currentInterimText = ""
        writtenBeforeFreeze = ""
        wasFrozenDuringSession = false
        latestObservedTranscript = LiveTranscript()
        pendingThrottledTranscript = null
        lastFlushAtMs = -10_000L
        isInBatchEdit = false
        cursorMovedDuringPolish = false
        frozenRemainderCommitted = false
        state = State.Live
    }

    /**
     * Processes an incoming [LiveTranscript] while in `State.Live`.
     * Returns `true` if changes were flushed to [editorBridge], `false` if skipped or throttled.
     */
    fun onTranscriptUpdate(
        transcript: LiveTranscript,
        forceFlush: Boolean = false
    ): Boolean {
        latestObservedTranscript = transcript
        if (state != State.Live) {
            return false
        }

        val targetFinal = transcript.finalized
        val targetInterim = transcript.interim

        // Skip if identical to what is already displayed in the editor
        if (targetFinal == committedFinalText && targetInterim == currentInterimText) {
            pendingThrottledTranscript = null
            return false
        }

        val hasNewFinalized = targetFinal.length > committedFinalText.length &&
            targetFinal != committedFinalText
        val now = clockMs()
        val elapsedSinceLastFlush = now - lastFlushAtMs

        if (!forceFlush && !hasNewFinalized && elapsedSinceLastFlush < minUpdateIntervalMs) {
            pendingThrottledTranscript = transcript
            return false
        }

        pendingThrottledTranscript = null
        flushToEditor(targetFinal = targetFinal, targetInterim = targetInterim, nowMs = now)
        return true
    }

    private fun flushToEditor(
        targetFinal: String,
        targetInterim: String,
        nowMs: Long
    ) {
        isInBatchEdit = true
        editorBridge.beginBatchEdit()
        try {
            val hasNewFinalDelta = targetFinal.length > committedFinalText.length &&
                targetFinal != committedFinalText

            if (hasNewFinalDelta) {
                val delta = if (targetFinal.startsWith(committedFinalText)) {
                    targetFinal.substring(committedFinalText.length)
                } else {
                    targetFinal.drop(committedFinalText.length.coerceAtMost(targetFinal.length))
                }
                if (delta.isNotEmpty()) {
                    editorBridge.commitText(delta, 1)
                }
                committedFinalText = targetFinal
            }

            if (targetInterim.isNotEmpty()) {
                if (targetInterim != currentInterimText || hasNewFinalDelta) {
                    editorBridge.setComposingText(targetInterim, 1)
                }
                currentInterimText = targetInterim
            } else {
                if (!hasNewFinalDelta && currentInterimText.isNotEmpty()) {
                    editorBridge.setComposingText("", 1)
                }
                currentInterimText = ""
            }
            lastFlushAtMs = nowMs
        } finally {
            editorBridge.endBatchEdit()
            isInBatchEdit = false
        }
    }

    /**
     * Monitors selection / cursor callbacks from `InputMethodService.onUpdateSelection`.
     * If the cursor is moved away from [expectedCursorPosition] by the user or host app while `Live`,
     * immediately solidifies the composing region via `finishComposingText()` and transitions to `Frozen`.
     */
    fun onUpdateSelection(
        newSelStart: Int,
        newSelEnd: Int
    ) {
        if (isInBatchEdit) return
        if (newSelStart < 0 || newSelEnd < 0) return

        when (state) {
            State.Live -> {
                val expected = expectedCursorPosition
                if (newSelStart != newSelEnd || newSelStart != expected) {
                    isInBatchEdit = true
                    try {
                        editorBridge.finishComposingText()
                    } finally {
                        isInBatchEdit = false
                    }
                    writtenBeforeFreeze = committedFinalText + currentInterimText
                    committedFinalText = writtenBeforeFreeze
                    currentInterimText = ""
                    wasFrozenDuringSession = true
                    pendingThrottledTranscript = null
                    state = State.Frozen
                }
            }
            State.Polishing -> {
                val expected = anchor + committedFinalText.length
                if (newSelStart != newSelEnd || newSelStart != expected) {
                    cursorMovedDuringPolish = true
                }
            }
            else -> Unit
        }
    }

    /**
     * Called when the user releases the microphone (or finishes hands-free recording).
     *
     * - Transitions `Live` / `Frozen` -> `Finishing`.
     * - If in `Live`, flushes any remaining un-written raw transcript and calls `finishComposingText()`.
     * - If [needsPolishing] is `false` (or [isSmartDirectMode] is `true`), transitions directly
     *   `Finishing -> Done` without replacing existing text (or commits the remaining suffix at cursor if `Frozen`).
     * - If [needsPolishing] is `true`, transitions `Finishing -> Polishing` and awaits [applyPolishedResult].
     */
    fun onRecordingFinished(
        finalRawTranscript: String = latestObservedTranscript.fullText,
        needsPolishing: Boolean = !isSmartDirectMode
    ): ComposerFinalizeOutcome? {
        if (state != State.Live && state != State.Frozen) {
            return null
        }

        val wasFrozen = (state == State.Frozen) || wasFrozenDuringSession
        state = State.Finishing

        if (!wasFrozen) {
            // Flush any pending throttled transcript or final tail delta and solidify composing region
            val targetRaw = finalRawTranscript.ifEmpty {
                (pendingThrottledTranscript ?: latestObservedTranscript).fullText
            }
            pendingThrottledTranscript = null

            isInBatchEdit = true
            editorBridge.beginBatchEdit()
            try {
                val currentFull = committedFinalText + currentInterimText
                if (targetRaw.isNotEmpty() && targetRaw != currentFull) {
                    if (targetRaw.startsWith(committedFinalText)) {
                        val delta = targetRaw.substring(committedFinalText.length)
                        if (delta.isNotEmpty()) {
                            editorBridge.commitText(delta, 1)
                        }
                        committedFinalText = targetRaw
                        currentInterimText = ""
                    } else {
                        // Rare case where final packet revised an earlier character: replace currentInterim if possible
                        if (currentInterimText.isNotEmpty()) {
                            editorBridge.setComposingText("", 1)
                            currentInterimText = ""
                        }
                        if (targetRaw.length > committedFinalText.length) {
                            editorBridge.commitText(targetRaw.substring(committedFinalText.length), 1)
                            committedFinalText = targetRaw
                        }
                    }
                } else if (currentInterimText.isNotEmpty()) {
                    committedFinalText += currentInterimText
                    currentInterimText = ""
                }
                editorBridge.finishComposingText()
            } finally {
                editorBridge.endBatchEdit()
                isInBatchEdit = false
            }

            if (!needsPolishing) {
                state = State.Done
                val undo = ImeLogic.createUndoSnapshot(
                    committedText = committedFinalText,
                    replacedSelection = ""
                )
                return ComposerFinalizeOutcome(
                    appliedToEditor = true,
                    wasFrozen = false,
                    rawWrittenText = committedFinalText,
                    finalCommittedText = committedFinalText,
                    undoSnapshot = undo
                )
            } else {
                state = State.Polishing
                return null
            }
        } else {
            // Session was Frozen: do not touch historical anchor position
            if (!needsPolishing) {
                val remainder = extractRemainderAfterFreeze(
                    writtenPrefix = writtenBeforeFreeze,
                    finalText = finalRawTranscript,
                    rawTranscript = finalRawTranscript
                )
                if (remainder.isNotEmpty() && !frozenRemainderCommitted) {
                    isInBatchEdit = true
                    editorBridge.beginBatchEdit()
                    try {
                        editorBridge.commitText(remainder, 1)
                    } finally {
                        editorBridge.endBatchEdit()
                        isInBatchEdit = false
                    }
                    frozenRemainderCommitted = true
                }
                state = State.Done
                val undo = ImeLogic.createUndoSnapshot(
                    committedText = remainder,
                    replacedSelection = ""
                )
                return ComposerFinalizeOutcome(
                    appliedToEditor = remainder.isNotEmpty(),
                    wasFrozen = true,
                    rawWrittenText = writtenBeforeFreeze,
                    finalCommittedText = remainder,
                    undoSnapshot = undo
                )
            } else {
                state = State.Polishing
                return null
            }
        }
    }

    /**
     * Applies the Stage-2 LLM polished text in Dual-Stage mode (`Polishing -> Done`):
     * - If the session was `Frozen`, never overwrites the historical `[anchor, end]` range;
     *   commits only the remaining unwritten suffix at the current cursor.
     * - Otherwise verifies `getTextBeforeCursor(writtenText.length, 0) == writtenText`.
     * - If verified: replaces `[anchor, anchor + writtenText.length]` in one batch via
     *   `setComposingRegion(anchor, anchor + writtenText.length)` + `commitText(polishedText, 1)`
     *   (or `deleteSurroundingText(writtenText.length, 0)` + `commitText(polishedText, 1)` if `setComposingRegion` returns `false`),
     *   and returns `UndoSnapshot(replacedOriginalText = writtenText, insertedTextLength = polishedText.length)`.
     * - If verification fails: preserves the user's edited buffer without overwriting!
     */
    fun applyPolishedResult(
        polishedText: String,
        fullRawTranscript: String = latestObservedTranscript.fullText
    ): ComposerFinalizeOutcome {
        if (state == State.Live || state == State.Frozen) {
            onRecordingFinished(finalRawTranscript = fullRawTranscript, needsPolishing = true)
        }

        if (wasFrozenDuringSession) {
            val remainder = extractRemainderAfterFreeze(
                writtenPrefix = writtenBeforeFreeze,
                finalText = polishedText,
                rawTranscript = fullRawTranscript
            )
            if (remainder.isNotEmpty() && !frozenRemainderCommitted) {
                isInBatchEdit = true
                editorBridge.beginBatchEdit()
                try {
                    editorBridge.commitText(remainder, 1)
                } finally {
                    editorBridge.endBatchEdit()
                    isInBatchEdit = false
                }
                frozenRemainderCommitted = true
            }
            state = State.Done
            val undo = ImeLogic.createUndoSnapshot(
                committedText = remainder,
                replacedSelection = ""
            )
            return ComposerFinalizeOutcome(
                appliedToEditor = remainder.isNotEmpty(),
                wasFrozen = true,
                rawWrittenText = writtenBeforeFreeze,
                finalCommittedText = remainder,
                undoSnapshot = undo
            )
        }

        val writtenText = committedFinalText
        if (writtenText.isEmpty()) {
            if (polishedText.isNotEmpty()) {
                isInBatchEdit = true
                editorBridge.beginBatchEdit()
                try {
                    editorBridge.commitText(polishedText, 1)
                } finally {
                    editorBridge.endBatchEdit()
                    isInBatchEdit = false
                }
            }
            state = State.Done
            val undo = ImeLogic.createUndoSnapshot(
                committedText = polishedText,
                replacedSelection = ""
            )
            return ComposerFinalizeOutcome(
                appliedToEditor = polishedText.isNotEmpty(),
                wasFrozen = false,
                rawWrittenText = "",
                finalCommittedText = polishedText,
                undoSnapshot = undo
            )
        }

        // Verify that the text immediately before the cursor still matches `writtenText`
        val actualBeforeCursor = editorBridge.getTextBeforeCursor(writtenText.length, 0)?.toString()
        val verified = !cursorMovedDuringPolish && (actualBeforeCursor == writtenText)

        if (!verified) {
            // Verification failed: user edited or moved cursor -> NEVER overwrite!
            state = State.Done
            return ComposerFinalizeOutcome(
                appliedToEditor = false,
                wasFrozen = false,
                rawWrittenText = writtenText,
                finalCommittedText = writtenText,
                undoSnapshot = null
            )
        }

        isInBatchEdit = true
        editorBridge.beginBatchEdit()
        try {
            val regionSupported = editorBridge.setComposingRegion(anchor, anchor + writtenText.length)
            if (regionSupported) {
                editorBridge.commitText(polishedText, 1)
            } else {
                editorBridge.deleteSurroundingText(writtenText.length, 0)
                editorBridge.commitText(polishedText, 1)
            }
        } finally {
            editorBridge.endBatchEdit()
            isInBatchEdit = false
        }

        state = State.Done
        val undo = UndoSnapshot(
            replacedOriginalText = writtenText,
            insertedTextLength = polishedText.length
        )
        return ComposerFinalizeOutcome(
            appliedToEditor = true,
            wasFrozen = false,
            rawWrittenText = writtenText,
            finalCommittedText = polishedText,
            undoSnapshot = undo
        )
    }

    /**
     * Rolls back or solidifies any active live-write session when the user cancels recording.
     */
    fun cancelSession(rollbackWrittenText: Boolean = true) {
        if (state == State.Idle || state == State.Done) {
            state = State.Idle
            return
        }
        isInBatchEdit = true
        editorBridge.beginBatchEdit()
        try {
            if (rollbackWrittenText && !wasFrozenDuringSession) {
                if (currentInterimText.isNotEmpty()) {
                    editorBridge.setComposingText("", 1)
                    currentInterimText = ""
                }
                if (committedFinalText.isNotEmpty()) {
                    val before = editorBridge.getTextBeforeCursor(committedFinalText.length, 0)?.toString()
                    if (before == committedFinalText) {
                        editorBridge.deleteSurroundingText(committedFinalText.length, 0)
                    }
                    committedFinalText = ""
                }
            } else {
                editorBridge.finishComposingText()
            }
        } finally {
            editorBridge.endBatchEdit()
            isInBatchEdit = false
        }
        state = State.Idle
    }

    fun reset() {
        committedFinalText = ""
        currentInterimText = ""
        writtenBeforeFreeze = ""
        wasFrozenDuringSession = false
        pendingThrottledTranscript = null
        cursorMovedDuringPolish = false
        frozenRemainderCommitted = false
        state = State.Idle
    }

    companion object {
        /** Maximum 15 UI flushes per second (~66.6ms minimum interval). */
        const val MIN_UPDATE_INTERVAL_MS: Long = 66L

        /**
         * Built-in terminal / code editor packages where standard Android `setComposingText` /
         * `setComposingRegion` replacement either duplicates keystrokes or corrupts terminal buffers.
         */
        val LIVE_WRITE_BLACKLIST_PACKAGES: Set<String> = setOf(
            "com.termux",
            "com.termux.nvim",
            "com.termux.x11",
            "org.connectbot",
            "com.sonelli.juicessh",
            "jackpal.androidterm",
            "com.offsec.nethunter.kex"
        )

        /**
         * Evaluates all 6 enabling conditions for real-time live writing into the host editor (Scheme 3.4):
         * 1. [AppSettings.liveWriteToEditor] is `true`.
         * 2. [intentMode] is [VoiceIntentMode.DICTATE] (Speak-to-Edit, Help-me-Write, and Translate do not live-write raw speech).
         * 3. [effectiveSelectedText] is blank (no active text selection).
         * 4. Uses a streaming ASR session in [PipelineMode.DUAL_STAGE] (`hasStreamingSession == true` and protocol is not batch HTTP).
         * 5. Host app [targetPackage] is not in [LIVE_WRITE_BLACKLIST_PACKAGES].
         * 6. Not invoked from the floating bubble accessibility channel ([isFloatingBubbleChannel] == `false`).
         */
        fun shouldEnableLiveWrite(
            settings: AppSettings,
            intentMode: VoiceIntentMode,
            effectiveSelectedText: String,
            hasStreamingSession: Boolean,
            targetPackage: String,
            isFloatingBubbleChannel: Boolean = false
        ): Boolean {
            if (!settings.liveWriteToEditor) return false
            if (isFloatingBubbleChannel) return false
            if (intentMode != VoiceIntentMode.DICTATE) return false
            if (effectiveSelectedText.isNotBlank()) return false
            if (settings.pipelineMode != PipelineMode.DUAL_STAGE) return false
            if (!hasStreamingSession) return false
            if (settings.sttConfig.protocol == SttProtocol.OPENAI_HTTP) return false
            val cleanPkg = targetPackage.trim()
            if (cleanPkg.isNotEmpty() && cleanPkg in LIVE_WRITE_BLACKLIST_PACKAGES) return false
            return true
        }

        /**
         * Returns `true` when Gemini Live SMART streaming dictation is active without a separate Stage-2 LLM,
         * meaning the streamed transcript is already polished and requires zero post-release replacement.
         */
        fun isSmartDirectDictation(
            settings: AppSettings,
            intentMode: VoiceIntentMode
        ): Boolean {
            return settings.pipelineMode == PipelineMode.DUAL_STAGE &&
                settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS &&
                !settings.llmConfig.enabled &&
                intentMode == VoiceIntentMode.DICTATE
        }

        /**
         * Computes the unwritten remainder suffix when a session was interrupted into `Frozen`
         * after having already written [writtenPrefix] at the original anchor.
         */
        fun extractRemainderAfterFreeze(
            writtenPrefix: String,
            finalText: String,
            rawTranscript: String = finalText
        ): String {
            if (writtenPrefix.isEmpty()) return finalText
            if (finalText.isEmpty() || finalText == writtenPrefix) return ""
            if (finalText.startsWith(writtenPrefix)) {
                var cutIdx = writtenPrefix.length
                while (cutIdx < finalText.length && finalText[cutIdx] in "，。？！；、,.?! ") {
                    cutIdx += 1
                }
                return finalText.substring(cutIdx)
            }
            // Match ignoring punctuation differences between raw writtenPrefix and polished finalText
            val normPrefix = writtenPrefix.filter { it.isLetterOrDigit() }
            if (normPrefix.isNotEmpty()) {
                var matchedNormCount = 0
                var cutIdx = 0
                while (cutIdx < finalText.length && matchedNormCount < normPrefix.length) {
                    val ch = finalText[cutIdx]
                    if (ch.isLetterOrDigit()) {
                        if (ch.lowercaseChar() == normPrefix[matchedNormCount].lowercaseChar()) {
                            matchedNormCount += 1
                        } else {
                            break
                        }
                    }
                    cutIdx += 1
                }
                if (matchedNormCount == normPrefix.length) {
                    // Also skip one trailing punctuation if it immediately belonged to the frozen clause
                    while (cutIdx < finalText.length && finalText[cutIdx] in "，。？！；、,.?! ") {
                        cutIdx += 1
                    }
                    return finalText.substring(cutIdx)
                }
            }
            if (rawTranscript.startsWith(writtenPrefix) && rawTranscript.length > writtenPrefix.length) {
                return rawTranscript.substring(writtenPrefix.length)
            }
            return finalText
        }
    }
}
