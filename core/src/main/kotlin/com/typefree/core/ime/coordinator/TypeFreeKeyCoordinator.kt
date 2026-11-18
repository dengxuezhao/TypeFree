package com.typefree.core.ime.coordinator

import com.typefree.core.ime.enhancer.DirectCommandEngine
import com.typefree.core.ime.enhancer.ReverseSplitEngine
import com.typefree.core.ime.enhancer.SplitResult
import com.typefree.core.ime.enhancer.Utf8Safety
import com.typefree.core.ime.overlay.OverlayStepResult
import com.typefree.core.ime.overlay.OverlaySymbolController
import com.typefree.core.ime.profile.SchemaArchetype
import com.typefree.core.ime.profile.SchemaProfile

data class RimeDecodeFrame(
    val rawInput: String = "",
    val preedit: String = "",
    val candidates: List<Pair<String, String>> = emptyList(), // (text, comment)
    val autoCommittedText: String? = null,
    val hasMore: Boolean = false
)

/**
 * Platform-neutral adapter over `:rime` (`RimeSessionClient`) so `:core` remains 100% pure Kotlin JVM_17.
 */
interface RimeCoreSessionPort {
    fun sendChar(ch: Char): RimeDecodeFrame
    fun sendBackspace(): RimeDecodeFrame
    fun selectCandidate(index: Int): Pair<String?, RimeDecodeFrame>
    fun commitCurrentComposition(): String?
    fun clearComposition()
    fun currentFrame(maxCandidates: Int = 24): RimeDecodeFrame
    fun setOption(option: String, value: Boolean)
    fun getOption(option: String): Boolean
}

enum class CoordinatorModeBadge(val label: String) {
    NONE(""),
    OVERLAY_SYMBOL("符"),
    REVERSE_LOOKUP("反查"),
    DIRECT_COMMAND("直通"),
    SINGLE_CHAR("单")
}

data class CoordinatorUiSnapshot(
    val isComposing: Boolean = false,
    val rawCode: String = "",
    val preedit: String = "",
    val badge: CoordinatorModeBadge = CoordinatorModeBadge.NONE,
    val candidates: List<EnhancedCandidate> = emptyList(),
    val isReverseSplitActive: Boolean = false,
    val hasMoreCandidates: Boolean = false
)

sealed interface CoordinatorKeyOutcome {
    data class Consumed(
        val ui: CoordinatorUiSnapshot
    ) : CoordinatorKeyOutcome

    data class Commit(
        val action: CommitAction,
        val ui: CoordinatorUiSnapshot,
        val feedToAutoLearn: Boolean
    ) : CoordinatorKeyOutcome

    data object PassThrough : CoordinatorKeyOutcome
}

/**
 * Unified 7-stage keystroke arbitrator in `:core` (`Overlay -> Idle Guide -> Composing Semicolon/Punct
 * -> DirectCommand -> librime Decode/PushTopup -> Empty-Code ReverseSplit -> Commit & Auto-Learn Isolation`).
 */
class TypeFreeKeyCoordinator(
    var profile: SchemaProfile,
    private val rimePort: RimeCoreSessionPort,
    val overlayController: OverlaySymbolController = OverlaySymbolController(),
    var reverseSplitEngine: ReverseSplitEngine? = null,
    val directCommandEngine: DirectCommandEngine = DirectCommandEngine(),
    applyInitialIsolationOptions: Boolean = true
) {
    var singleCharMode: Boolean = false
        private set

    var lastCommittedPreview: String = ""
        private set

    private var rawBuffer: String = ""
    private var activeSplit: SplitResult? = null
    private var activeDirectCandidates: List<EnhancedCandidate> = emptyList()

    init {
        if (applyInitialIsolationOptions) {
            applyProfileIsolationOptions()
        }
    }

    fun updateProfile(newProfile: SchemaProfile) {
        profile = newProfile
        reset()
        applyProfileIsolationOptions()
    }

    fun setSingleCharModeEnabled(enabled: Boolean) {
        singleCharMode = enabled
    }

    fun recordCommittedPreview(text: String) {
        if (text.isNotEmpty()) {
            lastCommittedPreview = text
        }
    }

    private fun applyProfileIsolationOptions() {
        if (profile.lockNaturalOrder) {
            rimePort.setOption("enable_user_dict", false)
            rimePort.setOption("fixed_candidate_order", true)
            rimePort.setOption("_incognito", true)
        } else {
            rimePort.setOption("enable_user_dict", true)
            rimePort.setOption("fixed_candidate_order", false)
        }
    }

    val isComposing: Boolean
        get() = overlayController.isActive || rawBuffer.isNotEmpty()

    fun reset() {
        overlayController.reset()
        rawBuffer = ""
        activeSplit = null
        activeDirectCandidates = emptyList()
        rimePort.clearComposition()
    }

    fun onCharKey(ch: Char, nowMillis: Long = System.currentTimeMillis()): CoordinatorKeyOutcome {
        val asciiPunct = rimePort.getOption("ascii_punct")

        // Stage 1: Active OverlaySymbolController priority interception
        if (overlayController.isActive) {
            return handleOverlayStep(
                overlayController.onChar(ch, asciiPunct = asciiPunct, lastCommittedPreview = lastCommittedPreview),
                nowMillis = nowMillis
            )
        }

        // Stage 2: Idle non-code-element guide key -> enter overlay without touching librime
        if (rawBuffer.isEmpty() && profile.overlayEnabled && isOverlayGuideTrigger(ch)) {
            overlayController.enter()
            return CoordinatorKeyOutcome.Consumed(snapshotUi())
        }

        // Stage 3: Composing state semicolon secondary select & non-alphabet punctuation top-up
        if (rawBuffer.isNotEmpty() && (ch == ';' || ch == '；') && !profile.isCodeAlphabetChar(';')) {
            val currentCands = snapshotUi(nowMillis).candidates
            if (profile.semicolonSelectsSecond && currentCands.size >= 2) {
                return selectCandidate(1, nowMillis)
            }
            if (currentCands.size == 1) {
                return selectCandidate(0, nowMillis)
            }
        }

        // Stage 4a: Inline math expression (`=...`) when idle or already in `=` expression
        if (profile.directCommandsEnabled && ((rawBuffer.isEmpty() && ch == '=') || rawBuffer.startsWith("="))) {
            rawBuffer += ch
            activeDirectCandidates = directCommandEngine.evaluate(rawBuffer, nowMillis, enabled = true)
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
        }

        // Non-alphabet punctuation while composing -> push-commit #1 + converted punctuation
        if (!profile.isCodeAlphabetChar(ch) && ch != '`') {
            if (rawBuffer.isEmpty()) {
                val punct = mapPunctuation(ch, asciiPunct)
                recordCommittedPreview(punct)
                return CoordinatorKeyOutcome.Commit(
                    action = CommitAction.PlainText(punct),
                    ui = snapshotUi(nowMillis),
                    feedToAutoLearn = false
                )
            }
            val topCand = snapshotUi(nowMillis).candidates.firstOrNull()
            val punct = mapPunctuation(ch, asciiPunct)
            reset()
            val outAction = if (topCand != null) {
                CommitAction.Composite(listOf(topCand.action, CommitAction.PlainText(punct)))
            } else {
                CommitAction.PlainText(punct)
            }
            return CoordinatorKeyOutcome.Commit(
                action = outAction,
                ui = snapshotUi(nowMillis),
                feedToAutoLearn = profile.allowAutoLearn
            )
        }

        // Fixed-length 5th-key push-commit (`fifthKeyTopCommit == true`, bypassed for PUSH_TOPUP_CODE like Keytao 6)
        if (profile.fifthKeyTopCommit &&
            profile.maxCodeLength > 0 &&
            rawBuffer.length >= profile.maxCodeLength &&
            !isExtendedGuardBuffer(rawBuffer) &&
            !rawBuffer.contains('`')
        ) {
            val topCand = snapshotUi(nowMillis).candidates.firstOrNull()
            reset()
            val follow = onCharKey(ch, nowMillis)
            if (topCand == null) return follow
            recordCommittedPreview(topCand.commitText)
            return when (follow) {
                is CoordinatorKeyOutcome.Commit -> CoordinatorKeyOutcome.Commit(
                    action = CommitAction.Composite(listOf(topCand.action, follow.action)),
                    ui = follow.ui,
                    feedToAutoLearn = profile.allowAutoLearn
                )
                is CoordinatorKeyOutcome.Consumed -> CoordinatorKeyOutcome.Commit(
                    action = topCand.action,
                    ui = follow.ui,
                    feedToAutoLearn = profile.allowAutoLearn
                )
                CoordinatorKeyOutcome.PassThrough -> CoordinatorKeyOutcome.Commit(
                    action = topCand.action,
                    ui = snapshotUi(nowMillis),
                    feedToAutoLearn = profile.allowAutoLearn
                )
            }
        }

        // Stage 4b (Pre-check): DirectCommandEngine priority before Stage 5 librime auto-commit
        val nextCandidateCode = rawBuffer + ch
        if (profile.directCommandsEnabled && !nextCandidateCode.contains('`')) {
            val preDirect = directCommandEngine.evaluate(
                code = nextCandidateCode,
                nowMillis = nowMillis,
                enabled = true
            )
            if (preDirect.isNotEmpty()) {
                rawBuffer = nextCandidateCode
                activeSplit = null
                activeDirectCandidates = preDirect
                rimePort.clearComposition()
                val syntheticFrame = RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, syntheticFrame))
            }
        }

        // Stage 5: Send regular code char to librime (handles native speller & PUSH_TOPUP_CODE 3-code/4-code push)
        val frame = rimePort.sendChar(ch)
        if (!frame.autoCommittedText.isNullOrEmpty()) {
            val committed = frame.autoCommittedText
            if (!singleCharMode || Utf8Safety.isSingleCharacter(committed)) {
                rawBuffer = frame.rawInput
                activeSplit = null
                activeDirectCandidates = if (rawBuffer.isNotEmpty()) {
                    directCommandEngine.evaluate(rawBuffer, nowMillis, profile.directCommandsEnabled)
                } else {
                    emptyList()
                }
                recordCommittedPreview(committed)
                return CoordinatorKeyOutcome.Commit(
                    action = CommitAction.PlainText(committed, fromRimeNative = true),
                    ui = snapshotUi(nowMillis, frame),
                    feedToAutoLearn = profile.allowAutoLearn
                )
            }
        }

        rawBuffer = frame.rawInput.ifEmpty { rawBuffer + ch }

        // Stage 4b (Fallback): DirectCommandEngine phrase check if Rime altered rawInput
        activeDirectCandidates = directCommandEngine.evaluate(
            code = rawBuffer,
            nowMillis = nowMillis,
            enabled = profile.directCommandsEnabled
        )

        val rimeFilteredCandidates = filterRimeCandidates(frame.candidates)

        // Stage 6: Empty-code 2+(1~2) ReverseSplitEngine fallback (with 6 strict gates & >4-code ok*/os* guard)
        activeSplit = reverseSplitEngine?.tryReverseSplit(
            code = rawBuffer,
            rimeHasCandidates = rimeFilteredCandidates.isNotEmpty(),
            profile = profile,
            singleCharMode = singleCharMode,
            directCmdMatched = activeDirectCandidates.isNotEmpty()
        )

        // Auto-commit unique full-length code (4-code exact or 4-code unique reverse-split)
        if (profile.autoCommitUniqueFull &&
            profile.maxCodeLength > 0 &&
            rawBuffer.length == profile.maxCodeLength &&
            !isExtendedGuardBuffer(rawBuffer) &&
            activeDirectCandidates.isEmpty()
        ) {
            val split = activeSplit
            if (split != null && split.candidates.size == 1) {
                val uniqueText = split.candidates[0].commitText
                reset()
                recordCommittedPreview(uniqueText)
                return CoordinatorKeyOutcome.Commit(
                    action = CommitAction.PlainText(uniqueText, fromRimeNative = false),
                    ui = snapshotUi(nowMillis),
                    feedToAutoLearn = false // Reverse split NEVER pollutes user_dict or Auto-Learn v2.0
                )
            }
            if (split == null && rimeFilteredCandidates.size == 1) {
                val uniqueText = rimeFilteredCandidates[0].first
                reset()
                recordCommittedPreview(uniqueText)
                return CoordinatorKeyOutcome.Commit(
                    action = CommitAction.PlainText(uniqueText, fromRimeNative = false),
                    ui = snapshotUi(nowMillis),
                    feedToAutoLearn = profile.allowAutoLearn
                )
            }
        }

        return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, frame))
    }

    fun onSpace(nowMillis: Long = System.currentTimeMillis()): CoordinatorKeyOutcome {
        if (overlayController.isActive) {
            return handleOverlayStep(overlayController.onSpace(lastCommittedPreview), nowMillis)
        }
        if (rawBuffer.isEmpty()) return CoordinatorKeyOutcome.PassThrough
        val cands = snapshotUi(nowMillis).candidates
        if (cands.isEmpty()) {
            reset()
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
        }
        return selectCandidate(0, nowMillis)
    }

    fun onBackspace(nowMillis: Long = System.currentTimeMillis()): CoordinatorKeyOutcome {
        if (overlayController.isActive) {
            overlayController.onBackspace()
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
        }
        if (rawBuffer.isEmpty()) return CoordinatorKeyOutcome.PassThrough
        if (rawBuffer.startsWith("=")) {
            rawBuffer = rawBuffer.dropLast(1)
            activeDirectCandidates = if (rawBuffer.isNotEmpty()) {
                directCommandEngine.evaluate(rawBuffer, nowMillis, profile.directCommandsEnabled)
            } else {
                emptyList()
            }
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
        }
        val frame = if (activeDirectCandidates.isNotEmpty()) {
            val nextBuf = rawBuffer.dropLast(1)
            if (nextBuf.isEmpty()) {
                reset()
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
            }
            rimePort.clearComposition()
            var replayed = RimeDecodeFrame(rawInput = nextBuf, preedit = nextBuf)
            for (c in nextBuf) {
                replayed = rimePort.sendChar(c)
            }
            rawBuffer = nextBuf
            replayed
        } else {
            val backFrame = rimePort.sendBackspace()
            rawBuffer = backFrame.rawInput
            backFrame
        }
        if (rawBuffer.isEmpty()) {
            reset()
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
        }
        activeDirectCandidates = directCommandEngine.evaluate(rawBuffer, nowMillis, profile.directCommandsEnabled)
        val filtered = filterRimeCandidates(frame.candidates)
        activeSplit = reverseSplitEngine?.tryReverseSplit(
            code = rawBuffer,
            rimeHasCandidates = filtered.isNotEmpty(),
            profile = profile,
            singleCharMode = singleCharMode,
            directCmdMatched = activeDirectCandidates.isNotEmpty()
        )
        return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, frame))
    }

    fun onEscape(): CoordinatorKeyOutcome {
        if (!isComposing) return CoordinatorKeyOutcome.PassThrough
        reset()
        return CoordinatorKeyOutcome.Consumed(snapshotUi())
    }

    fun selectCandidate(index: Int, nowMillis: Long = System.currentTimeMillis()): CoordinatorKeyOutcome {
        if (overlayController.isActive) {
            return handleOverlayStep(
                overlayController.onSelectCandidate(index, lastCommittedPreview),
                nowMillis
            )
        }
        val ui = snapshotUi(nowMillis)
        val picked = ui.candidates.getOrNull(index) ?: return CoordinatorKeyOutcome.Consumed(ui)

        // Virtual split or direct command: commit directly & reset Rime composition (0 userdb write!)
        if (index < activeDirectCandidates.size || activeSplit != null) {
            reset()
            if (picked.commitText.isNotEmpty() &&
                picked.action !is CommitAction.CopyToClipboard &&
                picked.action !is CommitAction.Command
            ) {
                recordCommittedPreview(picked.commitText)
            }
            return CoordinatorKeyOutcome.Commit(
                action = picked.action,
                ui = snapshotUi(nowMillis),
                feedToAutoLearn = false
            )
        }

        // Native Rime candidate
        val rimeIndex = index - activeDirectCandidates.size
        if (profile.lockNaturalOrder) {
            // Bypass `rimeSelectCandidate` learning path when natural order is locked!
            reset()
            recordCommittedPreview(picked.commitText)
            return CoordinatorKeyOutcome.Commit(
                action = picked.action,
                ui = snapshotUi(nowMillis),
                feedToAutoLearn = profile.allowAutoLearn
            )
        }

        val (committed, afterFrame) = rimePort.selectCandidate(rimeIndex)
        if (committed == null && afterFrame.rawInput.isNotEmpty()) {
            rawBuffer = afterFrame.rawInput
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, afterFrame))
        }
        val outText = committed ?: picked.commitText
        reset()
        recordCommittedPreview(outText)
        return CoordinatorKeyOutcome.Commit(
            action = CommitAction.PlainText(outText, fromRimeNative = true),
            ui = snapshotUi(nowMillis),
            feedToAutoLearn = profile.allowAutoLearn
        )
    }

    fun snapshotUi(
        nowMillis: Long = System.currentTimeMillis(),
        cachedFrame: RimeDecodeFrame? = null,
        maxCandidates: Int = 24
    ): CoordinatorUiSnapshot {
        if (overlayController.isActive) {
            return CoordinatorUiSnapshot(
                isComposing = true,
                rawCode = overlayController.preeditWithGuide,
                preedit = overlayController.preeditWithGuide,
                badge = CoordinatorModeBadge.OVERLAY_SYMBOL,
                candidates = overlayController.currentCandidates(lastCommittedPreview)
            )
        }
        if (rawBuffer.isEmpty()) {
            return CoordinatorUiSnapshot()
        }

        val split = activeSplit
        if (split != null) {
            return CoordinatorUiSnapshot(
                isComposing = true,
                rawCode = rawBuffer,
                preedit = split.preedit,
                badge = if (singleCharMode) CoordinatorModeBadge.SINGLE_CHAR else CoordinatorModeBadge.NONE,
                candidates = split.candidates.map { vc ->
                    EnhancedCandidate(
                        displayText = vc.displayText,
                        commitText = vc.commitText,
                        action = CommitAction.PlainText(vc.commitText, fromRimeNative = false)
                    )
                },
                isReverseSplitActive = true
            )
        }

        val frame = cachedFrame ?: rimePort.currentFrame(maxCandidates)
        val rimeCands = filterRimeCandidates(frame.candidates).map { (txt, comment) ->
            EnhancedCandidate(
                displayText = txt,
                commitText = txt,
                codeHint = comment,
                action = CommitAction.PlainText(txt, fromRimeNative = true)
            )
        }
        val badge = when {
            rawBuffer == "ofi" || rawBuffer.contains('`') -> CoordinatorModeBadge.REVERSE_LOOKUP
            activeDirectCandidates.isNotEmpty() -> CoordinatorModeBadge.DIRECT_COMMAND
            singleCharMode -> CoordinatorModeBadge.SINGLE_CHAR
            else -> CoordinatorModeBadge.NONE
        }
        return CoordinatorUiSnapshot(
            isComposing = true,
            rawCode = rawBuffer,
            preedit = frame.preedit.ifEmpty { rawBuffer },
            badge = badge,
            candidates = activeDirectCandidates + rimeCands,
            hasMoreCandidates = frame.hasMore
        )
    }

    private fun handleOverlayStep(step: OverlayStepResult, nowMillis: Long): CoordinatorKeyOutcome {
        return when (step) {
            OverlayStepResult.StayInOverlay -> CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
            OverlayStepResult.CancelledAndExit -> CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
            is OverlayStepResult.CommitAndExit -> {
                val replay = step.replayCharToMainSchema
                if (step.action is CommitAction.PlainText && step.action.text.isNotEmpty()) {
                    recordCommittedPreview(step.action.text)
                } else if (step.action is CommitAction.PairedText) {
                    recordCommittedPreview(step.action.left + step.action.right)
                }
                if (replay == null) {
                    CoordinatorKeyOutcome.Commit(
                        action = step.action,
                        ui = snapshotUi(nowMillis),
                        feedToAutoLearn = false
                    )
                } else {
                    val follow = onCharKey(replay, nowMillis)
                    when (follow) {
                        is CoordinatorKeyOutcome.Commit -> CoordinatorKeyOutcome.Commit(
                            action = CommitAction.Composite(listOf(step.action, follow.action)),
                            ui = follow.ui,
                            feedToAutoLearn = follow.feedToAutoLearn
                        )
                        is CoordinatorKeyOutcome.Consumed -> CoordinatorKeyOutcome.Commit(
                            action = step.action,
                            ui = follow.ui,
                            feedToAutoLearn = false
                        )
                        CoordinatorKeyOutcome.PassThrough -> CoordinatorKeyOutcome.Commit(
                            action = step.action,
                            ui = snapshotUi(nowMillis),
                            feedToAutoLearn = false
                        )
                    }
                }
            }
        }
    }

    private fun filterRimeCandidates(rawList: List<Pair<String, String>>): List<Pair<String, String>> {
        if (!singleCharMode) return rawList
        return rawList.filter { (text, _) -> Utf8Safety.isSingleCharacter(text) }
    }

    private fun isOverlayGuideTrigger(ch: Char): Boolean {
        val guide = profile.overlayGuideKey ?: return false
        return ch == guide || (guide == ';' && ch == '；')
    }

    private fun isExtendedGuardBuffer(code: String): Boolean {
        if (code.length < 2) return false
        val p2 = code.substring(0, 2)
        if (code.length == 4) {
            val idx = reverseSplitEngine?.index
            if (idx != null) return idx.hasGt4Prefix(code)
        }
        return p2 in profile.gt4GuardPrefixes
    }

    private fun mapPunctuation(ch: Char, asciiPunct: Boolean): String {
        if (asciiPunct) return ch.toString()
        return when (ch) {
            ',' -> "，"
            '.' -> "。"
            '?' -> "？"
            '!' -> "！"
            ':' -> "："
            ';' -> "；"
            else -> ch.toString()
        }
    }
}
