package com.typefree.ime.ime.jev.memory

import com.typefree.ime.ime.jev.ChatTopology

data class TrackedJevDraftContext(
    val anchor: AnchorResolution,
    val topology: ChatTopology,
    val ephemeralPrivateTag: PrivateRelationTag,
    val ephemeralGroupTag: GroupSceneTag,
    val axisGroup: JevAxisGroup,
    val rawSlotIndex: Int,
    val wasTopPinnedSlot: Boolean,
    val draftText: String,
    val initialInputPrefix: String,
    val activeStyleFeatures: Set<StyleFeatureKey>,
    val includeMaskedHint: Boolean,
    val targetPackage: String,
    val fieldId: Int,
    val startedAtMs: Long
)

data class FeedbackLearningEvent(
    val anchor: AnchorResolution,
    val topology: ChatTopology,
    val ephemeralPrivateTag: PrivateRelationTag,
    val ephemeralGroupTag: GroupSceneTag,
    val adoptedAxisGroup: JevAxisGroup?,
    val adoptedRawIndex: Int?,
    val wasTopPinnedSlot: Boolean,
    val featureObservations: List<FeatureObservation>,
    val includeMaskedHint: Boolean,
    val isZeroEditDirectSend: Boolean
)

/**
 * Pure Kotlin two-stage feedback state machine for `✦ Jev 军师`:
 * - Stage 1 (`PREVIEW_IN_EDITOR`): mounted as soon as `applyStrategyCard` previews a card,
 *   capturing the frequent case where the user taps the host app's Send button directly
 *   without closing the Jev panel first.
 * - Stage 2 (`COMMITTED_EDITING`): mounted when leaving Jev (`commitAndFinishOnExit`), tracking
 *   edits within a 45-second window without calling extra IPC while composing pinyin/flypy codes.
 */
class JevDraftFeedbackMonitor(
    private val clock: JevClock = JevSystemClock,
    private val onLearningEvent: (FeedbackLearningEvent) -> Unit,
    private val onDirectSendDuringPreview: () -> Unit = {}
) {
    enum class MonitorPhase {
        IDLE,
        PREVIEW_IN_EDITOR,
        COMMITTED_EDITING
    }

    var phase: MonitorPhase = MonitorPhase.IDLE
        private set

    private var activeContext: TrackedJevDraftContext? = null
    private var latestNonEmptySnapshot: String = ""
    private var lastNonEmptySnapshotAtMs: Long = 0L
    private var lastSnapshotReadAtMs: Long = 0L
    private var lastPreviewAppliedAtMs: Long = 0L
    private var committedAtMs: Long = 0L
    private var lastDeleteOrCutAtMs: Long = 0L
    private var userClearedToEmpty: Boolean = false
    private var userUndidDraft: Boolean = false
    private var isApplyingCardTransition: Boolean = false

    fun beginCardApplyTransition() {
        isApplyingCardTransition = true
    }

    fun endCardApplyTransition() {
        isApplyingCardTransition = false
    }

    fun onPreviewCardApplied(ctx: TrackedJevDraftContext) {
        val now = clock.nowMs()
        activeContext = ctx
        phase = MonitorPhase.PREVIEW_IN_EDITOR
        latestNonEmptySnapshot = ctx.initialInputPrefix + ctx.draftText
        lastNonEmptySnapshotAtMs = now
        lastSnapshotReadAtMs = now
        lastPreviewAppliedAtMs = now
        lastDeleteOrCutAtMs = 0L
        userClearedToEmpty = false
        userUndidDraft = false
    }

    fun onDraftCommittedOnExit(committedDraft: String) {
        val ctx = activeContext ?: return
        if (phase != MonitorPhase.PREVIEW_IN_EDITOR) return
        val clean = committedDraft.trim()
        if (clean.length < 2) {
            clearSession()
            return
        }
        val now = clock.nowMs()
        activeContext = ctx.copy(draftText = clean)
        phase = MonitorPhase.COMMITTED_EDITING
        committedAtMs = now
        if (latestNonEmptySnapshot.isBlank()) {
            latestNonEmptySnapshot = ctx.initialInputPrefix + clean
            lastNonEmptySnapshotAtMs = now
        }
    }

    fun onUserDeleteOrCutTriggered(remainingTextLengthHint: Int = -1) {
        if (phase == MonitorPhase.IDLE) return
        val now = clock.nowMs()
        lastDeleteOrCutAtMs = now
        if (remainingTextLengthHint == 0) {
            userClearedToEmpty = true
        }
    }

    fun onUserUndoTriggered() {
        if (phase == MonitorPhase.IDLE) return
        lastDeleteOrCutAtMs = clock.nowMs()
        userUndidDraft = true
    }

    fun onInputTargetChanged(packageName: String, fieldId: Int) {
        val ctx = activeContext ?: return
        if (packageName != ctx.targetPackage || (fieldId != 0 && ctx.fieldId != 0 && fieldId != ctx.fieldId)) {
            clearSession()
        }
    }

    fun shouldPollSnapshotNow(isComposing: Boolean): Boolean {
        if (phase != MonitorPhase.COMMITTED_EDITING || isComposing || isApplyingCardTransition) {
            return false
        }
        val now = clock.nowMs()
        if (now - committedAtMs > COMMIT_WINDOW_MS) {
            clearSession()
            return false
        }
        return (now - lastSnapshotReadAtMs) >= SNAPSHOT_THROTTLE_MS
    }

    fun onSelectionOrTextUpdated(
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
        isComposing: Boolean,
        editorTextSnapshot: String?
    ) {
        if (phase == MonitorPhase.IDLE || isApplyingCardTransition) return
        val ctx = activeContext ?: run {
            clearSession()
            return
        }
        val now = clock.nowMs()
        if (phase == MonitorPhase.COMMITTED_EDITING && now - committedAtMs > COMMIT_WINDOW_MS) {
            clearSession()
            return
        }

        if (isComposing) return

        val snap = editorTextSnapshot?.take(MAX_TRACKED_CHARS)
        if (snap != null) {
            lastSnapshotReadAtMs = now
            if (snap.isNotEmpty()) {
                latestNonEmptySnapshot = snap
                lastNonEmptySnapshotAtMs = now
                userClearedToEmpty = false
                return
            }
        }

        // Detect host-side Send button clear: cursor reset to (0,0), no composing span, editor now empty
        val looksClearedByHostSend = newSelStart == 0 &&
            newSelEnd == 0 &&
            candidatesStart == -1 &&
            candidatesEnd == -1 &&
            (snap != null && snap.isEmpty())

        if (!looksClearedByHostSend) return

        val deletedRecently = userClearedToEmpty || userUndidDraft ||
            (lastDeleteOrCutAtMs > 0L && (now - lastDeleteOrCutAtMs) <= DELETE_GUARD_MS)
        if (deletedRecently) {
            // Within undo/delete grace window: treat as user discarding the draft
            clearSession()
            return
        }

        if (phase == MonitorPhase.PREVIEW_IN_EDITOR) {
            if (now - lastPreviewAppliedAtMs < PREVIEW_SETTLE_GUARD_MS) {
                return
            }
            finalizeAndEmit(ctx, rawSentText = ctx.draftText, isDirectPreviewSend = true)
            onDirectSendDuringPreview()
        } else if (phase == MonitorPhase.COMMITTED_EDITING) {
            val candidateSent = latestNonEmptySnapshot.ifBlank { ctx.draftText }
            if (now - lastNonEmptySnapshotAtMs <= HOST_CLEAR_MAX_GAP_MS) {
                finalizeAndEmit(ctx, rawSentText = candidateSent, isDirectPreviewSend = false)
            } else {
                clearSession()
            }
        }
    }

    fun onExplicitImeSendAction(currentEditorText: String?) {
        if (phase == MonitorPhase.IDLE) return
        val ctx = activeContext ?: return
        val candidate = currentEditorText?.trim()?.take(MAX_TRACKED_CHARS).orEmpty()
            .ifEmpty { latestNonEmptySnapshot.trim() }
            .ifEmpty { ctx.draftText }
        val isDirectPreview = phase == MonitorPhase.PREVIEW_IN_EDITOR
        finalizeAndEmit(ctx, rawSentText = candidate, isDirectPreviewSend = isDirectPreview)
        if (isDirectPreview) {
            onDirectSendDuringPreview()
        }
    }

    fun clearSession() {
        phase = MonitorPhase.IDLE
        activeContext = null
        latestNonEmptySnapshot = ""
        lastNonEmptySnapshotAtMs = 0L
        lastSnapshotReadAtMs = 0L
        lastPreviewAppliedAtMs = 0L
        committedAtMs = 0L
        lastDeleteOrCutAtMs = 0L
        userClearedToEmpty = false
        userUndidDraft = false
        isApplyingCardTransition = false
    }

    private fun finalizeAndEmit(
        ctx: TrackedJevDraftContext,
        rawSentText: String,
        isDirectPreviewSend: Boolean
    ) {
        val sentWithoutPrefix = if (
            ctx.initialInputPrefix.isNotEmpty() &&
            rawSentText.startsWith(ctx.initialInputPrefix)
        ) {
            rawSentText.removePrefix(ctx.initialInputPrefix).trim()
        } else {
            rawSentText.trim()
        }

        // Clear all text references immediately (zero retention)
        clearSession()

        if (sentWithoutPrefix.isEmpty()) return
        val observations = DraftDiffAnalyzer.extractFeatureObservations(
            committed = ctx.draftText,
            sent = sentWithoutPrefix,
            activeFeatures = ctx.activeStyleFeatures
        ) ?: return // LCSRatio < 0.25 -> full rewrite, skip both axis & style learning

        onLearningEvent(
            FeedbackLearningEvent(
                anchor = ctx.anchor,
                topology = ctx.topology,
                ephemeralPrivateTag = ctx.ephemeralPrivateTag,
                ephemeralGroupTag = ctx.ephemeralGroupTag,
                adoptedAxisGroup = ctx.axisGroup,
                adoptedRawIndex = ctx.rawSlotIndex,
                wasTopPinnedSlot = ctx.wasTopPinnedSlot,
                featureObservations = observations,
                includeMaskedHint = ctx.includeMaskedHint,
                isZeroEditDirectSend = isDirectPreviewSend
            )
        )
    }

    companion object {
        const val COMMIT_WINDOW_MS = 45_000L
        const val SNAPSHOT_THROTTLE_MS = 250L
        const val DELETE_GUARD_MS = 600L
        const val PREVIEW_SETTLE_GUARD_MS = 180L
        const val HOST_CLEAR_MAX_GAP_MS = 45_000L
        const val MAX_TRACKED_CHARS = 500
    }
}
