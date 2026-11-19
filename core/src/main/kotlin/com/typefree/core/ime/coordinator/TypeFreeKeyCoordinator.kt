package com.typefree.core.ime.coordinator

import com.typefree.core.ime.enhancer.BriefPrefixIndex
import com.typefree.core.ime.enhancer.DirectCommandEngine
import com.typefree.core.ime.enhancer.NaturalContinuation
import com.typefree.core.ime.enhancer.ReverseSplitEngine
import com.typefree.core.ime.enhancer.ShadowDictManager
import com.typefree.core.ime.enhancer.SplitResult
import com.typefree.core.ime.enhancer.Utf8Safety
import com.typefree.core.ime.overlay.OverlayStepResult
import com.typefree.core.ime.overlay.OverlaySymbolController
import com.typefree.core.ime.profile.SchemaProfile
import java.util.Locale

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
    SINGLE_CHAR("单"),
    SLASH_SNIPPET("短语")
}

data class CoordinatorUiSnapshot(
    val isComposing: Boolean = false,
    val rawCode: String = "",
    val preedit: String = "",
    val badge: CoordinatorModeBadge = CoordinatorModeBadge.NONE,
    val candidates: List<EnhancedCandidate> = emptyList(),
    val isReverseSplitActive: Boolean = false,
    val hasMoreCandidates: Boolean = false,
    val isOrqDateActive: Boolean = false,
    val orqDateOffsetDays: Int = 0
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
 * Unified keystroke arbitrator in `:core` (`Overlay -> SlashSnippet -> Idle Guide -> Composing
 * Semicolon/Punct -> DirectCommand -> librime Decode/PushTopup -> Empty-Code ReverseSplit ->
 * Commit & Auto-Learn Isolation`).
 */
class TypeFreeKeyCoordinator(
    var profile: SchemaProfile,
    private val rimePort: RimeCoreSessionPort,
    val overlayController: OverlaySymbolController = OverlaySymbolController(),
    var reverseSplitEngine: ReverseSplitEngine? = null,
    val directCommandEngine: DirectCommandEngine = DirectCommandEngine(),
    applyInitialIsolationOptions: Boolean = true,
    private val shadowDictManager: ShadowDictManager? = null,
    naturalContinuationEnabled: Boolean = true
) {
    private data class CandidateSlot(
        val candidate: EnhancedCandidate,
        val rimeIndex: Int? = null
    )

    var singleCharMode: Boolean = false
        private set

    var naturalContinuationEnabled: Boolean = naturalContinuationEnabled
        private set

    var orqDateOffsetDays: Int = 0
        set(value) {
            field = value.coerceIn(-MAX_ORQ_OFFSET_DAYS, MAX_ORQ_OFFSET_DAYS)
        }

    var lastCommittedPreview: String = ""
        private set

    private var rawBuffer: String = ""
    private var activeSplit: SplitResult? = null
    private var activeDirectCandidates: List<EnhancedCandidate> = emptyList()
    private var syntheticRimeCandidatesOverride: List<Pair<String, String>>? = null
    private var rimeClearedByCoordinator: Boolean = false
    private var isSlashSnippetMode: Boolean = false
    private var slashBuffer: String = ""
    private var slashSnippetEntries: List<SlashSnippetEntry> = emptyList()
    private var extraUserWordsByCode: Map<String, List<String>> = emptyMap()
    private var shadowRuleState: ShadowDictManager.ShadowRuleState =
        shadowDictManager?.loadRules() ?: ShadowDictManager.ShadowRuleState()

    init {
        directCommandEngine.boundSchemaIdProvider = { profile.schemaId }
        directCommandEngine.boundSingleCharModeProvider = { singleCharMode }
        if (applyInitialIsolationOptions) {
            applyProfileIsolationOptions()
        }
    }

    fun updateProfile(newProfile: SchemaProfile) {
        profile = newProfile
        reloadShadowRules()
        reset()
        applyProfileIsolationOptions()
    }

    fun setSingleCharModeEnabled(enabled: Boolean) {
        singleCharMode = enabled
    }

    fun setNaturalContinuationEnabled(enabled: Boolean) {
        naturalContinuationEnabled = enabled
    }

    fun updateExtraUserWordsByCode(wordsByCode: Map<String, List<String>>) {
        extraUserWordsByCode = wordsByCode
    }

    fun updateSlashSnippets(entries: List<SlashSnippetEntry>) {
        slashSnippetEntries = entries.mapNotNull { entry ->
            val cleanCode = entry.code.trim().trimStart('/', ';', '；').lowercase(Locale.ROOT)
            if (cleanCode.isEmpty() || entry.content.isEmpty()) {
                null
            } else {
                SlashSnippetEntry(
                    code = cleanCode,
                    title = entry.title.trim(),
                    content = entry.content
                )
            }
        }
    }

    @JvmOverloads
    fun adjustOrqDateOffset(
        deltaDays: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): CoordinatorUiSnapshot {
        if (rawBuffer != "orq" || !profile.directCommandsEnabled || profile.schemaId != "flypy") {
            return snapshotUi(nowMillis)
        }
        orqDateOffsetDays = (orqDateOffsetDays + deltaDays).coerceIn(-MAX_ORQ_OFFSET_DAYS, MAX_ORQ_OFFSET_DAYS)
        activeDirectCandidates = directCommandEngine.evaluate(
            code = "orq",
            nowMillis = nowMillis,
            enabled = profile.directCommandsEnabled,
            dateOffsetDays = orqDateOffsetDays
        )
        val syntheticFrame = RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
        return snapshotUi(nowMillis, syntheticFrame)
    }

    fun reloadShadowRules() {
        shadowRuleState = shadowDictManager?.loadRules() ?: ShadowDictManager.ShadowRuleState()
    }

    fun currentPinnedWordsForBuffer(): List<String> {
        if (rawBuffer.isEmpty()) return emptyList()
        return shadowRuleState.pinnedByCode[rawBuffer].orEmpty()
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
        get() = overlayController.isActive || isSlashSnippetMode || rawBuffer.isNotEmpty()

    fun reset() {
        overlayController.reset()
        rawBuffer = ""
        activeSplit = null
        activeDirectCandidates = emptyList()
        syntheticRimeCandidatesOverride = null
        rimeClearedByCoordinator = false
        isSlashSnippetMode = false
        slashBuffer = ""
        orqDateOffsetDays = 0
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

        // Stage 1b: Active SlashSnippet (`/...`) mode
        if (isSlashSnippetMode) {
            return handleSlashSnippetChar(ch, asciiPunct, nowMillis)
        }

        // Stage 2: Idle non-code-element guide keys (`;` overlay or `/` snippet guide)
        if (rawBuffer.isEmpty()) {
            if (profile.overlayEnabled && isOverlayGuideTrigger(ch)) {
                overlayController.enter()
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
            }
            if (ch == '/' && !profile.isCodeAlphabetChar('/')) {
                isSlashSnippetMode = true
                slashBuffer = ""
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
            }
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

        if (rawBuffer == "orq") {
            orqDateOffsetDays = 0
        }

        // Stage 4b (Pre-check): DirectCommandEngine priority before Stage 5 librime auto-commit.
        // Note: `ouj` is NOT intercepted early so Rime preserves native candidates (e.g. `偶见`).
        val nextCandidateCode = rawBuffer + ch
        if (profile.directCommandsEnabled && !nextCandidateCode.contains('`') && nextCandidateCode != "ouj") {
            val preDirect = directCommandEngine.evaluate(
                code = nextCandidateCode,
                nowMillis = nowMillis,
                enabled = true,
                dateOffsetDays = if (nextCandidateCode == "orq") orqDateOffsetDays else 0
            )
            if (preDirect.isNotEmpty()) {
                rawBuffer = nextCandidateCode
                activeSplit = null
                activeDirectCandidates = preDirect
                rimePort.clearComposition()
                rimeClearedByCoordinator = true
                val syntheticFrame = RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, syntheticFrame))
            }
        }

        // Stage 5: Send regular code char to librime (handles native speller & PUSH_TOPUP_CODE 3-code/4-code push)
        val wasClearedInRime = rimeClearedByCoordinator || syntheticRimeCandidatesOverride != null
        if (wasClearedInRime) {
            rimePort.clearComposition()
            for (c in rawBuffer) {
                rimePort.sendChar(c)
            }
        }
        rimeClearedByCoordinator = false
        syntheticRimeCandidatesOverride = null

        val prevRawBuffer = rawBuffer
        val intendedNext = prevRawBuffer + ch
        val hasKotlinLayeredWords = shadowRuleState.pinnedByCode[intendedNext].orEmpty().any {
            it !in shadowRuleState.blockedWords && (!singleCharMode || Utf8Safety.isSingleCharacter(it))
        } || lookupValidExtraUserWords(intendedNext).isNotEmpty()

        val frame = rimePort.sendChar(ch)
        val rimeCommittedWholeCode = frame.rawInput.isEmpty() && !frame.autoCommittedText.isNullOrEmpty()

        val shouldPreserve4CharReverseSplit = frame.autoCommittedText.isNullOrEmpty() &&
            frame.rawInput != intendedNext &&
            prevRawBuffer.length == 3 &&
            intendedNext.length == 4 &&
            profile.reverseSplitEnabled &&
            !isExtendedGuardBuffer(intendedNext) &&
            reverseSplitEngine?.tryReverseSplit(
                code = intendedNext,
                rimeHasCandidates = false,
                profile = profile,
                singleCharMode = singleCharMode,
                directCmdMatched = false
            ) != null

        val workingFrame = if (hasKotlinLayeredWords && frame.rawInput != intendedNext) {
            rimePort.clearComposition()
            rimeClearedByCoordinator = true
            val syntheticCands = if (rimeCommittedWholeCode) {
                listOf(frame.autoCommittedText!! to "")
            } else {
                emptyList()
            }
            syntheticRimeCandidatesOverride = syntheticCands
            RimeDecodeFrame(
                rawInput = intendedNext,
                preedit = intendedNext,
                candidates = syntheticCands
            )
        } else if (shouldPreserve4CharReverseSplit) {
            rimePort.clearComposition()
            rimeClearedByCoordinator = true
            syntheticRimeCandidatesOverride = emptyList()
            RimeDecodeFrame(
                rawInput = intendedNext,
                preedit = intendedNext,
                candidates = emptyList()
            )
        } else if (!frame.autoCommittedText.isNullOrEmpty()) {
            val committed = frame.autoCommittedText
            val directHitForIntended = if (profile.directCommandsEnabled && !intendedNext.contains('`')) {
                directCommandEngine.evaluate(
                    code = intendedNext,
                    nowMillis = nowMillis,
                    enabled = true,
                    dateOffsetDays = if (intendedNext == "orq") orqDateOffsetDays else 0
                )
            } else {
                emptyList()
            }
            if (directHitForIntended.isNotEmpty()) {
                rimePort.clearComposition()
                rimeClearedByCoordinator = true
                val syntheticCands = listOf(committed to "")
                syntheticRimeCandidatesOverride = syntheticCands
                RimeDecodeFrame(
                    rawInput = intendedNext,
                    preedit = intendedNext,
                    candidates = syntheticCands
                )
            } else if (!singleCharMode || Utf8Safety.isSingleCharacter(committed)) {
                rawBuffer = frame.rawInput
                if (rawBuffer != "orq") orqDateOffsetDays = 0
                activeSplit = null
                activeDirectCandidates = if (rawBuffer.isNotEmpty()) {
                    directCommandEngine.evaluate(
                        code = rawBuffer,
                        nowMillis = nowMillis,
                        enabled = profile.directCommandsEnabled,
                        dateOffsetDays = if (rawBuffer == "orq") orqDateOffsetDays else 0
                    )
                } else {
                    emptyList()
                }
                recordCommittedPreview(committed)
                return CoordinatorKeyOutcome.Commit(
                    action = CommitAction.PlainText(committed, fromRimeNative = true),
                    ui = snapshotUi(nowMillis, frame),
                    feedToAutoLearn = profile.allowAutoLearn
                )
            } else {
                // Single-char mode intercepted multi-char autoCommittedText: mark Rime cleared and preserve rawBuffer
                rimePort.clearComposition()
                rimeClearedByCoordinator = true
                syntheticRimeCandidatesOverride = emptyList()
                RimeDecodeFrame(
                    rawInput = intendedNext,
                    preedit = intendedNext,
                    candidates = emptyList()
                )
            }
        } else {
            frame
        }

        rawBuffer = workingFrame.rawInput.ifEmpty { intendedNext }
        if (rawBuffer != "orq") {
            orqDateOffsetDays = 0
        }

        // Stage 4b (Fallback): DirectCommandEngine check (also catches `ouj` while keeping Rime candidates!)
        activeDirectCandidates = directCommandEngine.evaluate(
            code = rawBuffer,
            nowMillis = nowMillis,
            enabled = profile.directCommandsEnabled,
            dateOffsetDays = if (rawBuffer == "orq") orqDateOffsetDays else 0
        )
        if (activeDirectCandidates.isNotEmpty()) {
            activeSplit = null
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, workingFrame))
        }

        val hasExactRimeMatch = workingFrame.candidates.any { (_, comment) -> !isCompletionComment(comment) }
        val rimeFilteredCandidates = filterRimeCandidates(workingFrame.candidates)
        val validNats = findValidNaturalContinuations(rawBuffer, hasExactRimeMatch)

        // Stage 6: Empty-code 2+(1~2) ReverseSplitEngine fallback (with 6 strict gates & >4-code ok*/os* guard)
        val extraUserWords = lookupValidExtraUserWords(rawBuffer)
        activeSplit = if (validNats.isNotEmpty() || extraUserWords.isNotEmpty()) {
            null
        } else {
            reverseSplitEngine?.tryReverseSplit(
                code = rawBuffer,
                rimeHasCandidates = rimeFilteredCandidates.isNotEmpty(),
                profile = profile,
                singleCharMode = singleCharMode,
                directCmdMatched = activeDirectCandidates.isNotEmpty()
            )
        }

        // Auto-commit unique full-length code (4-code exact or 4-code unique reverse-split)
        if (profile.autoCommitUniqueFull &&
            profile.maxCodeLength > 0 &&
            rawBuffer.length == profile.maxCodeLength &&
            !isExtendedGuardBuffer(rawBuffer) &&
            activeDirectCandidates.isEmpty()
        ) {
            val split = activeSplit
            if (split != null) {
                val splitSlots = buildSplitCandidateSlots(split)
                if (splitSlots.size == 1) {
                    val uniqueText = splitSlots[0].candidate.commitText
                    reset()
                    recordCommittedPreview(uniqueText)
                    return CoordinatorKeyOutcome.Commit(
                        action = CommitAction.PlainText(uniqueText, fromRimeNative = false),
                        ui = snapshotUi(nowMillis),
                        feedToAutoLearn = false // Reverse split NEVER pollutes user_dict or Auto-Learn v2.0
                    )
                }
            } else if (validNats.isEmpty()) {
                val slots = buildRegularCandidateSlots(workingFrame)
                val hasExactOrUserSource =
                    hasExactRimeMatch || extraUserWords.isNotEmpty() || currentPinnedWordsForBuffer().isNotEmpty()
                if (slots.size == 1 && hasExactOrUserSource) {
                    val uniqueText = slots[0].candidate.commitText
                    reset()
                    recordCommittedPreview(uniqueText)
                    return CoordinatorKeyOutcome.Commit(
                        action = CommitAction.PlainText(uniqueText, fromRimeNative = false),
                        ui = snapshotUi(nowMillis),
                        feedToAutoLearn = profile.allowAutoLearn
                    )
                }
            }
        }

        return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, workingFrame))
    }

    private fun handleSlashSnippetChar(
        ch: Char,
        asciiPunct: Boolean,
        nowMillis: Long
    ): CoordinatorKeyOutcome {
        if (ch == '/' && slashBuffer.isEmpty()) {
            reset()
            recordCommittedPreview("/")
            return CoordinatorKeyOutcome.Commit(
                action = CommitAction.PlainText("/", fromRimeNative = false),
                ui = snapshotUi(nowMillis),
                feedToAutoLearn = false
            )
        }
        if (ch in 'a'..'z' || ch in 'A'..'Z') {
            slashBuffer += ch.lowercaseChar()
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
        }
        if ((ch == ';' || ch == '；') && slashBuffer.isNotEmpty()) {
            val cands = buildSlashSnippetCandidates(slashBuffer)
            if (profile.semicolonSelectsSecond && cands.size >= 2) {
                return selectCandidate(1, nowMillis)
            }
            if (cands.size == 1) {
                return selectCandidate(0, nowMillis)
            }
        }
        val punct = mapPunctuation(ch, asciiPunct)
        if (slashBuffer.isEmpty()) {
            val out = "/$punct"
            reset()
            recordCommittedPreview(out)
            return CoordinatorKeyOutcome.Commit(
                action = CommitAction.PlainText(out, fromRimeNative = false),
                ui = snapshotUi(nowMillis),
                feedToAutoLearn = false
            )
        }
        val topCand = buildSlashSnippetCandidates(slashBuffer).firstOrNull()
        reset()
        return if (topCand != null) {
            recordCommittedPreview(topCand.commitText)
            CoordinatorKeyOutcome.Commit(
                action = CommitAction.Composite(listOf(topCand.action, CommitAction.PlainText(punct))),
                ui = snapshotUi(nowMillis),
                feedToAutoLearn = false
            )
        } else {
            recordCommittedPreview(punct)
            CoordinatorKeyOutcome.Commit(
                action = CommitAction.PlainText(punct),
                ui = snapshotUi(nowMillis),
                feedToAutoLearn = false
            )
        }
    }

    fun onSpace(nowMillis: Long = System.currentTimeMillis()): CoordinatorKeyOutcome {
        if (overlayController.isActive) {
            return handleOverlayStep(overlayController.onSpace(lastCommittedPreview), nowMillis)
        }
        if (isSlashSnippetMode) {
            if (slashBuffer.isEmpty()) {
                reset()
                recordCommittedPreview("/")
                return CoordinatorKeyOutcome.Commit(
                    action = CommitAction.PlainText("/", fromRimeNative = false),
                    ui = snapshotUi(nowMillis),
                    feedToAutoLearn = false
                )
            }
            val cands = buildSlashSnippetCandidates(slashBuffer)
            if (cands.isEmpty()) {
                reset()
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
            }
            return selectCandidate(0, nowMillis)
        }
        if (rawBuffer.isEmpty()) return CoordinatorKeyOutcome.PassThrough
        val cands = snapshotUi(nowMillis).candidates
        if (cands.isEmpty()) {
            reset()
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
        }
        return selectCandidate(0, nowMillis)
    }

    fun onEnter(nowMillis: Long = System.currentTimeMillis()): CoordinatorKeyOutcome {
        if (overlayController.isActive) {
            overlayController.reset()
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
        }
        if (isSlashSnippetMode) {
            val raw = if (slashBuffer.isEmpty()) "/" else "/$slashBuffer"
            reset()
            recordCommittedPreview(raw)
            return CoordinatorKeyOutcome.Commit(
                action = CommitAction.PlainText(raw, fromRimeNative = false),
                ui = snapshotUi(nowMillis),
                feedToAutoLearn = false
            )
        }
        if (rawBuffer.isEmpty()) return CoordinatorKeyOutcome.PassThrough
        val raw = rawBuffer
        reset()
        return CoordinatorKeyOutcome.Commit(
            action = CommitAction.PlainText(raw, fromRimeNative = false),
            ui = snapshotUi(nowMillis),
            feedToAutoLearn = false
        )
    }

    fun onBackspace(nowMillis: Long = System.currentTimeMillis()): CoordinatorKeyOutcome {
        if (overlayController.isActive) {
            overlayController.onBackspace()
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
        }
        if (isSlashSnippetMode) {
            if (slashBuffer.isNotEmpty()) {
                slashBuffer = slashBuffer.dropLast(1)
            } else {
                reset()
            }
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
        }
        if (rawBuffer.isEmpty()) return CoordinatorKeyOutcome.PassThrough
        if (rawBuffer == "orq") {
            orqDateOffsetDays = 0
        }
        val frame = if (syntheticRimeCandidatesOverride != null || rimeClearedByCoordinator || activeDirectCandidates.isNotEmpty()) {
            syntheticRimeCandidatesOverride = null
            rimeClearedByCoordinator = false
            val nextBuf = rawBuffer.dropLast(1)
            if (nextBuf.isEmpty()) {
                reset()
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
            }
            rawBuffer = nextBuf
            val nextDirect = directCommandEngine.evaluate(
                code = nextBuf,
                nowMillis = nowMillis,
                enabled = profile.directCommandsEnabled,
                dateOffsetDays = if (nextBuf == "orq") orqDateOffsetDays else 0
            )
            if (nextDirect.isNotEmpty()) {
                activeDirectCandidates = nextDirect
                rimeClearedByCoordinator = true
                val syntheticFrame = RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, syntheticFrame))
            }
            activeDirectCandidates = emptyList()
            replayBufferIntoRime(nextBuf)
        } else {
            val backFrame = rimePort.sendBackspace()
            rawBuffer = backFrame.rawInput
            backFrame
        }
        if (rawBuffer.isEmpty()) {
            reset()
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
        }
        return finishBackspaceWithFrame(frame, nowMillis)
    }

    private fun replayBufferIntoRime(buf: String): RimeDecodeFrame {
        rimePort.clearComposition()
        var replayed = RimeDecodeFrame(rawInput = buf, preedit = buf)
        for (c in buf) {
            replayed = rimePort.sendChar(c)
        }
        return replayed
    }

    private fun finishBackspaceWithFrame(frame: RimeDecodeFrame, nowMillis: Long): CoordinatorKeyOutcome {
        activeDirectCandidates = directCommandEngine.evaluate(
            code = rawBuffer,
            nowMillis = nowMillis,
            enabled = profile.directCommandsEnabled,
            dateOffsetDays = if (rawBuffer == "orq") orqDateOffsetDays else 0
        )
        if (activeDirectCandidates.isNotEmpty()) {
            activeSplit = null
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, frame))
        }
        val hasExactRimeMatch = frame.candidates.any { (_, comment) -> !isCompletionComment(comment) }
        val validNats = findValidNaturalContinuations(rawBuffer, hasExactRimeMatch)
        val filtered = filterRimeCandidates(frame.candidates)
        activeSplit = if (validNats.isNotEmpty()) {
            null
        } else {
            reverseSplitEngine?.tryReverseSplit(
                code = rawBuffer,
                rimeHasCandidates = filtered.isNotEmpty(),
                profile = profile,
                singleCharMode = singleCharMode,
                directCmdMatched = activeDirectCandidates.isNotEmpty()
            )
        }
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
        if (isSlashSnippetMode) {
            val cands = buildSlashSnippetCandidates(slashBuffer)
            val picked = cands.getOrNull(index) ?: return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
            reset()
            recordCommittedPreview(picked.commitText)
            return CoordinatorKeyOutcome.Commit(
                action = picked.action,
                ui = snapshotUi(nowMillis),
                feedToAutoLearn = false
            )
        }
        val frame = if (activeSplit == null && rawBuffer.isNotEmpty()) {
            val overrideCands = syntheticRimeCandidatesOverride
            if (overrideCands != null) {
                RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer, candidates = overrideCands)
            } else if (rimeClearedByCoordinator) {
                RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
            } else {
                rimePort.currentFrame()
            }
        } else {
            RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
        }
        val slots = buildCandidateSlots(frame)
        val ui = snapshotUi(nowMillis, frame)
        val slot = slots.getOrNull(index) ?: return CoordinatorKeyOutcome.Consumed(ui)
        val picked = slot.candidate

        if (picked.action is CommitAction.Command &&
            picked.action.commandType == DirectCommandType.TOGGLE_SINGLE_CHAR_MODE
        ) {
            singleCharMode = !singleCharMode
        }

        // Virtual split or direct command candidate: commit directly & reset Rime composition (0 userdb write!)
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

        // Native Rime or virtual/hoisted codetable candidate
        if (profile.lockNaturalOrder || slot.rimeIndex == null) {
            // Bypass `rimeSelectCandidate` learning path when natural order is locked or candidate is virtual!
            reset()
            recordCommittedPreview(picked.commitText)
            return CoordinatorKeyOutcome.Commit(
                action = picked.action,
                ui = snapshotUi(nowMillis),
                feedToAutoLearn = profile.allowAutoLearn
            )
        }

        val (committed, afterFrame) = rimePort.selectCandidate(slot.rimeIndex)
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
        if (isSlashSnippetMode) {
            val slashCode = "/$slashBuffer"
            return CoordinatorUiSnapshot(
                isComposing = true,
                rawCode = slashCode,
                preedit = slashCode,
                badge = CoordinatorModeBadge.SLASH_SNIPPET,
                candidates = buildSlashSnippetCandidates(slashBuffer)
            )
        }
        if (rawBuffer.isEmpty()) {
            return CoordinatorUiSnapshot()
        }

        val split = activeSplit
        if (split != null) {
            val splitSlots = buildSplitCandidateSlots(split)
            return CoordinatorUiSnapshot(
                isComposing = true,
                rawCode = rawBuffer,
                preedit = split.preedit,
                badge = if (singleCharMode) CoordinatorModeBadge.SINGLE_CHAR else CoordinatorModeBadge.NONE,
                candidates = splitSlots.map { it.candidate },
                isReverseSplitActive = true
            )
        }

        val frame = cachedFrame ?: syntheticRimeCandidatesOverride?.let { overrideCands ->
            RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer, candidates = overrideCands)
        } ?: if (rimeClearedByCoordinator) {
            RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
        } else {
            rimePort.currentFrame(maxCandidates)
        }
        val slots = buildCandidateSlots(frame)
        val isOrqActive = rawBuffer == "orq" && activeDirectCandidates.isNotEmpty()
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
            candidates = slots.map { it.candidate },
            hasMoreCandidates = frame.hasMore,
            isOrqDateActive = isOrqActive,
            orqDateOffsetDays = if (isOrqActive) orqDateOffsetDays else 0
        )
    }

    private fun buildSlashSnippetCandidates(query: String): List<EnhancedCandidate> {
        if (slashSnippetEntries.isEmpty()) return emptyList()
        val matched = if (query.isEmpty()) {
            slashSnippetEntries
        } else {
            val exact = ArrayList<SlashSnippetEntry>()
            val prefix = ArrayList<SlashSnippetEntry>()
            for (entry in slashSnippetEntries) {
                if (entry.code == query) {
                    exact.add(entry)
                } else if (entry.code.startsWith(query)) {
                    prefix.add(entry)
                }
            }
            exact + prefix
        }
        return matched.map { entry ->
            EnhancedCandidate(
                displayText = entry.title.ifBlank { entry.content },
                commitText = entry.content,
                codeHint = "/${entry.code}",
                action = CommitAction.PlainText(entry.content, fromRimeNative = false)
            )
        }
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

    private fun buildCandidateSlots(frame: RimeDecodeFrame): List<CandidateSlot> {
        val split = activeSplit
        if (split != null) {
            return buildSplitCandidateSlots(split)
        }
        val directSlots = activeDirectCandidates.map { CandidateSlot(it, null) }
        val regularSlots = buildRegularCandidateSlots(frame)
        return directSlots + regularSlots
    }

    private fun buildSplitCandidateSlots(split: SplitResult): List<CandidateSlot> {
        val blocked = shadowRuleState.blockedWords
        val base = split.candidates
            .filter { it.commitText !in blocked }
            .map { vc ->
                CandidateSlot(
                    candidate = EnhancedCandidate(
                        displayText = vc.displayText,
                        commitText = vc.commitText,
                        action = CommitAction.PlainText(vc.commitText, fromRimeNative = false)
                    ),
                    rimeIndex = null
                )
            }
        return applyShadowPinning(base)
    }

    private fun buildRegularCandidateSlots(frame: RimeDecodeFrame): List<CandidateSlot> {
        val blocked = shadowRuleState.blockedWords
        val rawRime = frame.candidates
        val hasExactRimeMatch = rawRime.any { (_, comment) -> !isCompletionComment(comment) }
        val validNats = findValidNaturalContinuations(rawBuffer, hasExactRimeMatch)

        val slots = ArrayList<CandidateSlot>(rawRime.size + validNats.size)
        val seenWords = HashSet<String>(rawRime.size + validNats.size)

        for (nat in validNats) {
            if (!seenWords.add(nat.word)) continue
            slots.add(
                CandidateSlot(
                    candidate = EnhancedCandidate(
                        displayText = nat.word,
                        commitText = nat.word,
                        codeHint = "~${nat.suffixCode}",
                        action = CommitAction.PlainText(nat.word, fromRimeNative = false)
                    ),
                    rimeIndex = null
                )
            )
        }

        val isSyntheticRimeFrame = syntheticRimeCandidatesOverride != null
        rawRime.forEachIndexed { origIdx, (txt, comment) ->
            if (txt in blocked) return@forEachIndexed
            if (singleCharMode && !Utf8Safety.isSingleCharacter(txt)) return@forEachIndexed
            if (!seenWords.add(txt)) return@forEachIndexed
            slots.add(
                CandidateSlot(
                    candidate = EnhancedCandidate(
                        displayText = txt,
                        commitText = txt,
                        codeHint = comment,
                        action = CommitAction.PlainText(txt, fromRimeNative = !isSyntheticRimeFrame)
                    ),
                    rimeIndex = if (isSyntheticRimeFrame) null else origIdx
                )
            )
        }

        for (userWord in lookupValidExtraUserWords(rawBuffer)) {
            if (!seenWords.add(userWord)) continue
            slots.add(
                CandidateSlot(
                    candidate = EnhancedCandidate(
                        displayText = userWord,
                        commitText = userWord,
                        codeHint = "",
                        action = CommitAction.PlainText(userWord, fromRimeNative = false)
                    ),
                    rimeIndex = null
                )
            )
        }

        return applyShadowPinning(slots)
    }

    private fun lookupValidExtraUserWords(code: String): List<String> {
        if (code.isEmpty()) return emptyList()
        val blocked = shadowRuleState.blockedWords
        return extraUserWordsByCode[code].orEmpty().filter { w ->
            w !in blocked && (!singleCharMode || Utf8Safety.isSingleCharacter(w))
        }
    }

    private fun applyShadowPinning(base: List<CandidateSlot>): List<CandidateSlot> {
        val blocked = shadowRuleState.blockedWords
        val pinned = shadowRuleState.pinnedByCode[rawBuffer]
            .orEmpty()
            .filter { it !in blocked && (!singleCharMode || Utf8Safety.isSingleCharacter(it)) }
        if (pinned.isEmpty()) return base

        val pinnedSet = pinned.toHashSet()
        val existingByWord = LinkedHashMap<String, CandidateSlot>(base.size)
        val remaining = ArrayList<CandidateSlot>(base.size)
        for (slot in base) {
            val w = slot.candidate.commitText
            if (w in pinnedSet && !existingByWord.containsKey(w)) {
                existingByWord[w] = slot
            } else {
                remaining.add(slot)
            }
        }

        val result = ArrayList<CandidateSlot>(pinned.size + remaining.size)
        for (w in pinned) {
            val promoted = existingByWord[w]
            if (promoted != null) {
                result.add(promoted)
            } else {
                result.add(
                    CandidateSlot(
                        candidate = EnhancedCandidate(
                            displayText = w,
                            commitText = w,
                            codeHint = "置顶",
                            action = CommitAction.PlainText(w, fromRimeNative = false)
                        ),
                        rimeIndex = null
                    )
                )
            }
        }
        result.addAll(remaining)
        return result
    }

    private fun findValidNaturalContinuations(
        buffer: String,
        hasExactRimeMatch: Boolean
    ): List<NaturalContinuation> {
        if (!naturalContinuationEnabled || hasExactRimeMatch) return emptyList()
        if (!isExtendedGuardBuffer(buffer) && buffer.length !in 2..7) return emptyList()
        val list = reverseSplitEngine?.index?.lookupNaturalContinuations(
            prefix = buffer,
            limit = BriefPrefixIndex.MAX_NATURAL_CONTINUATIONS
        ).orEmpty()
        if (list.isEmpty()) return emptyList()
        return list.filter { nat ->
            nat.word !in shadowRuleState.blockedWords &&
                (!singleCharMode || Utf8Safety.isSingleCharacter(nat.word))
        }
    }

    private fun isCompletionComment(comment: String): Boolean {
        val c = comment.trim()
        return c.startsWith("~") || c.startsWith("〔~")
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

    companion object {
        private const val MAX_ORQ_OFFSET_DAYS = 3650
    }
}
