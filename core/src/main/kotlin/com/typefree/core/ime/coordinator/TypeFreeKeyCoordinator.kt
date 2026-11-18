package com.typefree.core.ime.coordinator

import com.typefree.core.ime.enhancer.CodetableAutoEncoder
import com.typefree.core.ime.enhancer.DirectCommandEngine
import com.typefree.core.ime.enhancer.EnglishWordTrie
import com.typefree.core.ime.enhancer.NaturalContinuation
import com.typefree.core.ime.enhancer.ReverseSplitEngine
import com.typefree.core.ime.enhancer.ShadowDictManager
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
    applyInitialIsolationOptions: Boolean = true,
    private val englishWordTrie: EnglishWordTrie = EnglishWordTrie.defaultBuiltIn(),
    private val shadowDictManager: ShadowDictManager? = null,
    private val autoEncoder: CodetableAutoEncoder? = null
) {
    private data class CandidateSlot(
        val candidate: EnhancedCandidate,
        val rimeIndex: Int? = null
    )

    var singleCharMode: Boolean = false
        private set

    var englishPrefixVetoEnabled: Boolean = profile.englishPrefixVetoEnabled
        private set

    var autoEncodeEnabled: Boolean = profile.autoEncodePhrasesEnabled
        private set

    var lastCommittedPreview: String = ""
        private set

    private var rawBuffer: String = ""
    private var activeSplit: SplitResult? = null
    private var activeDirectCandidates: List<EnhancedCandidate> = emptyList()
    private var activeEnglishSuggestions: List<EnhancedCandidate> = emptyList()
    private var activeDirectOffsetPending: Boolean = false
    private var syntheticRimeCandidatesOverride: List<Pair<String, String>>? = null
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
        englishPrefixVetoEnabled = newProfile.englishPrefixVetoEnabled
        autoEncodeEnabled = newProfile.autoEncodePhrasesEnabled
        reloadShadowRules()
        autoEncoder?.resetSession()
        reset()
        applyProfileIsolationOptions()
    }

    fun setSingleCharModeEnabled(enabled: Boolean) {
        singleCharMode = enabled
    }

    fun setEnglishPrefixVetoEnabled(enabled: Boolean) {
        englishPrefixVetoEnabled = enabled
    }

    fun reloadShadowRules() {
        shadowRuleState = shadowDictManager?.loadRules() ?: ShadowDictManager.ShadowRuleState()
    }

    fun currentPinnedWordsForBuffer(): List<String> {
        if (rawBuffer.isEmpty()) return emptyList()
        return shadowRuleState.pinnedByCode[rawBuffer].orEmpty()
    }

    fun setAutoEncodeEnabled(enabled: Boolean) {
        autoEncodeEnabled = enabled
        if (!enabled) {
            autoEncoder?.resetSession()
        }
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
        activeEnglishSuggestions = emptyList()
        activeDirectOffsetPending = false
        syntheticRimeCandidatesOverride = null
        rimePort.clearComposition()
    }

    fun onCharKey(ch: Char, nowMillis: Long = System.currentTimeMillis()): CoordinatorKeyOutcome {
        val asciiPunct = rimePort.getOption("ascii_punct")

        // Stage 1: Active OverlaySymbolController priority interception
        if (overlayController.isActive) {
            autoEncoder?.resetSession()
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
            activeSplit = null
            activeEnglishSuggestions = emptyList()
            activeDirectOffsetPending = false
            activeDirectCandidates = directCommandEngine.evaluate(rawBuffer, nowMillis, enabled = true)
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
        }

        // Stage 4a-ext: Date offset direct command continuation (`orq+N`, `orq-N`, `date+N`, `date-N`)
        if (profile.directCommandsEnabled && isDirectCommandOffsetStart(rawBuffer, ch)) {
            rawBuffer += ch
            activeSplit = null
            activeEnglishSuggestions = emptyList()
            activeDirectOffsetPending = true
            activeDirectCandidates = listOf(
                EnhancedCandidate(
                    displayText = "[输入偏移天数 0~3650]",
                    commitText = "",
                    codeHint = "${rawBuffer}N",
                    action = CommitAction.PlainText("")
                )
            )
            rimePort.clearComposition()
            val syntheticFrame = RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, syntheticFrame))
        }
        if (profile.directCommandsEnabled && isDirectCommandOffsetDigit(rawBuffer, ch)) {
            val candidateCode = rawBuffer + ch
            val evaluated = directCommandEngine.evaluate(candidateCode, nowMillis, enabled = true)
            if (evaluated.isNotEmpty()) {
                rawBuffer = candidateCode
                activeSplit = null
                activeEnglishSuggestions = emptyList()
                activeDirectOffsetPending = false
                activeDirectCandidates = evaluated
                rimePort.clearComposition()
                val syntheticFrame = RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, syntheticFrame))
            }
        }

        // Non-alphabet punctuation while composing -> push-commit #1 + converted punctuation
        if (!profile.isCodeAlphabetChar(ch) && ch != '`') {
            if (rawBuffer.isEmpty()) {
                val punct = mapPunctuation(ch, asciiPunct)
                recordCommittedPreview(punct)
                autoEncoder?.resetSession()
                return CoordinatorKeyOutcome.Commit(
                    action = CommitAction.PlainText(punct),
                    ui = snapshotUi(nowMillis),
                    feedToAutoLearn = false
                )
            }
            val topCand = snapshotUi(nowMillis).candidates.firstOrNull()
            if (autoEncodeEnabled &&
                topCand != null &&
                activeDirectCandidates.isEmpty() &&
                activeEnglishSuggestions.isEmpty() &&
                activeSplit == null
            ) {
                autoEncoder?.recordCommittedUnit(topCand.commitText, rawBuffer)
            }
            autoEncoder?.resetSession()
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

        // English prefix extension (4..13 -> 5..14): veto 5th-key auto-commit while matching English prefixes
        if (activeEnglishSuggestions.isNotEmpty() && ch in 'a'..'z' && rawBuffer.length < MAX_ENGLISH_BUFFER_LENGTH) {
            val nextEnglishBuf = rawBuffer + ch
            if (englishWordTrie.hasLongerWordPrefix(nextEnglishBuf) || englishWordTrie.containsWord(nextEnglishBuf)) {
                rawBuffer = nextEnglishBuf
                activeSplit = null
                activeDirectCandidates = emptyList()
                activeDirectOffsetPending = false
                activeEnglishSuggestions = buildEnglishCandidates(nextEnglishBuf)
                rimePort.clearComposition()
                val syntheticFrame = RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, syntheticFrame))
            }
        }

        // Fixed-length 5th-key push-commit (`fifthKeyTopCommit == true`, bypassed for PUSH_TOPUP_CODE like Keytao 6)
        if (profile.fifthKeyTopCommit &&
            profile.maxCodeLength > 0 &&
            rawBuffer.length >= profile.maxCodeLength &&
            !isExtendedGuardBuffer(rawBuffer) &&
            !rawBuffer.contains('`')
        ) {
            val topCand = snapshotUi(nowMillis).candidates.firstOrNull()
            if (autoEncodeEnabled &&
                topCand != null &&
                activeDirectCandidates.isEmpty() &&
                activeEnglishSuggestions.isEmpty() &&
                activeSplit == null
            ) {
                autoEncoder?.recordCommittedUnit(topCand.commitText, rawBuffer)
            } else if (activeEnglishSuggestions.isNotEmpty() || activeDirectCandidates.isNotEmpty() || activeSplit != null) {
                autoEncoder?.resetSession()
            }
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
                activeEnglishSuggestions = emptyList()
                activeDirectOffsetPending = false
                activeDirectCandidates = preDirect
                rimePort.clearComposition()
                val syntheticFrame = RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, syntheticFrame))
            }
        }

        // Stage 5: Send regular code char to librime (handles native speller & PUSH_TOPUP_CODE 3-code/4-code push)
        val wasClearedInRime =
            activeDirectCandidates.isNotEmpty() ||
                activeEnglishSuggestions.isNotEmpty() ||
                activeDirectOffsetPending ||
                syntheticRimeCandidatesOverride != null
        if (wasClearedInRime) {
            rimePort.clearComposition()
            for (c in rawBuffer) {
                rimePort.sendChar(c)
            }
        }
        activeDirectOffsetPending = false
        activeEnglishSuggestions = emptyList()
        syntheticRimeCandidatesOverride = null

        val prevRawBuffer = rawBuffer
        val intendedNext = prevRawBuffer + ch
        val hasKotlinLayeredWords = lookupValidAutoEncodedWords(intendedNext).isNotEmpty() ||
            shadowRuleState.pinnedByCode[intendedNext].orEmpty().any {
                it !in shadowRuleState.blockedWords && (!singleCharMode || Utf8Safety.isSingleCharacter(it))
            }
        val mayTrigger4CharEnglishVeto = englishPrefixVetoEnabled &&
            (profile.archetype == SchemaArchetype.FIXED_YINXING ||
                profile.archetype == SchemaArchetype.FIXED_SHAPECODE) &&
            prevRawBuffer.length == 3 &&
            ch in 'a'..'z' &&
            prevRawBuffer.all { it in 'a'..'z' } &&
            !prevRawBuffer.startsWith("ok") &&
            !prevRawBuffer.startsWith("os") &&
            !isExtendedGuardBuffer(intendedNext) &&
            englishWordTrie.hasLongerWordPrefix(intendedNext)

        val frame = rimePort.sendChar(ch)
        val rimeCommittedWholeCode = frame.rawInput.isEmpty() && !frame.autoCommittedText.isNullOrEmpty()

        val workingFrame = if (hasKotlinLayeredWords && frame.rawInput != intendedNext) {
            rimePort.clearComposition()
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
        } else if (mayTrigger4CharEnglishVeto && frame.rawInput != intendedNext && !rimeCommittedWholeCode) {
            rimePort.clearComposition()
            rawBuffer = intendedNext
            activeSplit = null
            activeDirectCandidates = emptyList()
            activeDirectOffsetPending = false
            activeEnglishSuggestions = buildEnglishCandidates(intendedNext)
            val syntheticFrame = RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, syntheticFrame))
        } else {
            if (!frame.autoCommittedText.isNullOrEmpty()) {
                val committed = frame.autoCommittedText
                if (!singleCharMode || Utf8Safety.isSingleCharacter(committed)) {
                    if (autoEncodeEnabled) {
                        autoEncoder?.recordCommittedUnit(committed, intendedNext)
                    }
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
            frame
        }

        rawBuffer = workingFrame.rawInput.ifEmpty { intendedNext }

        // Stage 4b (Fallback): DirectCommandEngine phrase check if Rime altered rawInput
        activeDirectCandidates = directCommandEngine.evaluate(
            code = rawBuffer,
            nowMillis = nowMillis,
            enabled = profile.directCommandsEnabled
        )
        if (activeDirectCandidates.isNotEmpty()) {
            activeSplit = null
            syntheticRimeCandidatesOverride = null
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, workingFrame))
        }

        val hasExactRimeMatch = workingFrame.candidates.any { (_, comment) -> !isCompletionComment(comment) }

        // English prefix veto on 4-letter lowercase buffer (FIXED_YINXING / FIXED_SHAPECODE)
        if (shouldActivateEnglishPrefixVeto(rawBuffer, hasExactRimeMatch)) {
            activeSplit = null
            syntheticRimeCandidatesOverride = null
            activeEnglishSuggestions = buildEnglishCandidates(rawBuffer)
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, workingFrame))
        }

        val rimeFilteredCandidates = filterRimeCandidates(workingFrame.candidates)
        val validNat = findValidNaturalContinuation(rawBuffer, hasExactRimeMatch)
        val autoEncodedWords = lookupValidAutoEncodedWords(rawBuffer)

        // Stage 6: Empty-code 2+(1~2) ReverseSplitEngine fallback (with 6 strict gates & >4-code ok*/os* guard)
        activeSplit = if (validNat != null || autoEncodedWords.isNotEmpty()) {
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
            activeDirectCandidates.isEmpty() &&
            activeEnglishSuggestions.isEmpty()
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
            } else if (validNat == null) {
                val slots = buildRegularCandidateSlots(workingFrame)
                val hasExactOrUserSource =
                    hasExactRimeMatch || autoEncodedWords.isNotEmpty() || currentPinnedWordsForBuffer().isNotEmpty()
                if (slots.size == 1 && hasExactOrUserSource) {
                    val uniqueText = slots[0].candidate.commitText
                    if (autoEncodeEnabled) {
                        autoEncoder?.recordCommittedUnit(uniqueText, rawBuffer)
                    }
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
        if (activeEnglishSuggestions.isNotEmpty()) {
            val nextBuf = rawBuffer.dropLast(1)
            if (nextBuf.length >= 4 &&
                (englishWordTrie.hasLongerWordPrefix(nextBuf) || englishWordTrie.containsWord(nextBuf))
            ) {
                rawBuffer = nextBuf
                activeEnglishSuggestions = buildEnglishCandidates(nextBuf)
                val syntheticFrame = RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, syntheticFrame))
            }
            activeEnglishSuggestions = emptyList()
            if (nextBuf.isEmpty()) {
                reset()
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
            }
            val replayed = replayBufferIntoRime(nextBuf)
            rawBuffer = nextBuf
            return finishBackspaceWithFrame(replayed, nowMillis)
        }
        val frame = if (syntheticRimeCandidatesOverride != null) {
            syntheticRimeCandidatesOverride = null
            val nextBuf = rawBuffer.dropLast(1)
            if (nextBuf.isEmpty()) {
                reset()
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
            }
            rawBuffer = nextBuf
            replayBufferIntoRime(nextBuf)
        } else if (activeDirectCandidates.isNotEmpty() || activeDirectOffsetPending) {
            val nextBuf = rawBuffer.dropLast(1)
            if (nextBuf.isEmpty()) {
                reset()
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis))
            }
            if (profile.directCommandsEnabled && DATE_OFFSET_ONLY_SIGN_REGEX.matches(nextBuf)) {
                rawBuffer = nextBuf
                activeDirectOffsetPending = true
                activeDirectCandidates = listOf(
                    EnhancedCandidate(
                        displayText = "[输入偏移天数 0~3650]",
                        commitText = "",
                        codeHint = "${rawBuffer}N",
                        action = CommitAction.PlainText("")
                    )
                )
                val syntheticFrame = RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, syntheticFrame))
            }
            activeDirectOffsetPending = false
            val nextDirect = directCommandEngine.evaluate(nextBuf, nowMillis, profile.directCommandsEnabled)
            if (nextDirect.isNotEmpty()) {
                rawBuffer = nextBuf
                activeDirectCandidates = nextDirect
                val syntheticFrame = RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer)
                return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, syntheticFrame))
            }
            activeDirectCandidates = emptyList()
            rawBuffer = nextBuf
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
        activeDirectCandidates = directCommandEngine.evaluate(rawBuffer, nowMillis, profile.directCommandsEnabled)
        if (activeDirectCandidates.isNotEmpty()) {
            activeSplit = null
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, frame))
        }
        val hasExactRimeMatch = frame.candidates.any { (_, comment) -> !isCompletionComment(comment) }
        if (shouldActivateEnglishPrefixVeto(rawBuffer, hasExactRimeMatch)) {
            activeSplit = null
            activeEnglishSuggestions = buildEnglishCandidates(rawBuffer)
            return CoordinatorKeyOutcome.Consumed(snapshotUi(nowMillis, frame))
        }
        val validNat = findValidNaturalContinuation(rawBuffer, hasExactRimeMatch)
        val autoEncodedWords = lookupValidAutoEncodedWords(rawBuffer)
        val filtered = filterRimeCandidates(frame.candidates)
        activeSplit = if (validNat != null || autoEncodedWords.isNotEmpty()) {
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
        autoEncoder?.resetSession()
        reset()
        return CoordinatorKeyOutcome.Consumed(snapshotUi())
    }

    fun selectCandidate(index: Int, nowMillis: Long = System.currentTimeMillis()): CoordinatorKeyOutcome {
        if (overlayController.isActive) {
            autoEncoder?.resetSession()
            return handleOverlayStep(
                overlayController.onSelectCandidate(index, lastCommittedPreview),
                nowMillis
            )
        }
        val frame = if (activeDirectCandidates.isEmpty() && activeEnglishSuggestions.isEmpty() && activeSplit == null && rawBuffer.isNotEmpty()) {
            val overrideCands = syntheticRimeCandidatesOverride
            if (overrideCands != null) {
                RimeDecodeFrame(rawInput = rawBuffer, preedit = rawBuffer, candidates = overrideCands)
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

        // Virtual split, English suggestion, or direct command: commit directly & reset Rime composition (0 userdb write!)
        if (index < activeDirectCandidates.size || activeSplit != null || activeEnglishSuggestions.isNotEmpty()) {
            autoEncoder?.resetSession()
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
        val commitCode = rawBuffer
        if (profile.lockNaturalOrder || slot.rimeIndex == null) {
            // Bypass `rimeSelectCandidate` learning path when natural order is locked or candidate is virtual!
            if (autoEncodeEnabled) {
                autoEncoder?.recordCommittedUnit(picked.commitText, commitCode)
            }
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
        if (autoEncodeEnabled) {
            autoEncoder?.recordCommittedUnit(outText, commitCode)
        }
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

        if (activeEnglishSuggestions.isNotEmpty()) {
            return CoordinatorUiSnapshot(
                isComposing = true,
                rawCode = rawBuffer,
                preedit = rawBuffer,
                badge = if (singleCharMode) CoordinatorModeBadge.SINGLE_CHAR else CoordinatorModeBadge.NONE,
                candidates = activeEnglishSuggestions
            )
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
        } ?: rimePort.currentFrame(maxCandidates)
        val slots = buildCandidateSlots(frame)
        val badge = when {
            rawBuffer == "ofi" || rawBuffer.contains('`') -> CoordinatorModeBadge.REVERSE_LOOKUP
            activeDirectCandidates.isNotEmpty() || activeDirectOffsetPending -> CoordinatorModeBadge.DIRECT_COMMAND
            singleCharMode -> CoordinatorModeBadge.SINGLE_CHAR
            else -> CoordinatorModeBadge.NONE
        }
        return CoordinatorUiSnapshot(
            isComposing = true,
            rawCode = rawBuffer,
            preedit = frame.preedit.ifEmpty { rawBuffer },
            badge = badge,
            candidates = slots.map { it.candidate },
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

    private fun buildCandidateSlots(frame: RimeDecodeFrame): List<CandidateSlot> {
        if (activeEnglishSuggestions.isNotEmpty()) {
            return activeEnglishSuggestions.map { CandidateSlot(it, null) }
        }
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
        val validNat = findValidNaturalContinuation(rawBuffer, hasExactRimeMatch)

        val slots = ArrayList<CandidateSlot>(rawRime.size + 4)
        val seenWords = HashSet<String>(rawRime.size + 4)

        if (validNat != null) {
            slots.add(
                CandidateSlot(
                    candidate = EnhancedCandidate(
                        displayText = validNat.word,
                        commitText = validNat.word,
                        codeHint = "~${validNat.suffixCode}",
                        action = CommitAction.PlainText(validNat.word, fromRimeNative = false)
                    ),
                    rimeIndex = null
                )
            )
            seenWords.add(validNat.word)
        }

        val isSyntheticRimeFrame = syntheticRimeCandidatesOverride != null
        rawRime.forEachIndexed { origIdx, (txt, comment) ->
            if (txt in blocked) return@forEachIndexed
            if (singleCharMode && !Utf8Safety.isSingleCharacter(txt)) return@forEachIndexed
            if (validNat != null && txt == validNat.word) return@forEachIndexed
            seenWords.add(txt)
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

        if (autoEncodeEnabled && autoEncoder != null) {
            for (word in lookupValidAutoEncodedWords(rawBuffer)) {
                if (word in seenWords) continue
                seenWords.add(word)
                slots.add(
                    CandidateSlot(
                        candidate = EnhancedCandidate(
                            displayText = word,
                            commitText = word,
                            codeHint = "造词",
                            action = CommitAction.PlainText(word, fromRimeNative = false)
                        ),
                        rimeIndex = null
                    )
                )
            }
        }

        return applyShadowPinning(slots)
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

    private fun findValidNaturalContinuation(
        buffer: String,
        hasExactRimeMatch: Boolean
    ): NaturalContinuation? {
        if (hasExactRimeMatch) return null
        if (!isExtendedGuardBuffer(buffer) && buffer.length !in 2..6) return null
        val nat = reverseSplitEngine?.index?.lookupNaturalFirstContinuation(buffer) ?: return null
        if (nat.word in shadowRuleState.blockedWords) return null
        if (singleCharMode && !Utf8Safety.isSingleCharacter(nat.word)) return null
        return nat
    }

    private fun lookupValidAutoEncodedWords(buffer: String): List<String> {
        if (!autoEncodeEnabled || autoEncoder == null || buffer.isEmpty()) return emptyList()
        return autoEncoder.lookupExact(buffer).filter { w ->
            w !in shadowRuleState.blockedWords &&
                (!singleCharMode || Utf8Safety.isSingleCharacter(w))
        }
    }

    private fun shouldActivateEnglishPrefixVeto(buffer: String, hasExactRimeMatch: Boolean): Boolean {
        if (!englishPrefixVetoEnabled) return false
        if (profile.archetype != SchemaArchetype.FIXED_YINXING &&
            profile.archetype != SchemaArchetype.FIXED_SHAPECODE
        ) {
            return false
        }
        if (buffer.length != 4 || !buffer.all { it in 'a'..'z' }) return false
        if (isExtendedGuardBuffer(buffer) || buffer.startsWith("ok") || buffer.startsWith("os")) return false
        if (hasExactRimeMatch) return false
        return englishWordTrie.hasLongerWordPrefix(buffer)
    }

    private fun buildEnglishCandidates(prefix: String): List<EnhancedCandidate> {
        val suggestions = englishWordTrie.suggestWords(prefix, limit = 6)
        if (suggestions.isEmpty()) {
            return listOf(
                EnhancedCandidate(
                    displayText = prefix,
                    commitText = prefix,
                    codeHint = "英文",
                    action = CommitAction.PlainText(prefix, fromRimeNative = false)
                )
            )
        }
        return suggestions.map { word ->
            EnhancedCandidate(
                displayText = word,
                commitText = word,
                codeHint = "英文",
                action = CommitAction.PlainText(word, fromRimeNative = false)
            )
        }
    }

    private fun isCompletionComment(comment: String): Boolean {
        val c = comment.trim()
        return c.startsWith("~") || c.startsWith("〔~")
    }

    private fun isDirectCommandOffsetStart(buf: String, ch: Char): Boolean {
        return (buf == "orq" || buf == "date") && (ch == '+' || ch == '-')
    }

    private fun isDirectCommandOffsetDigit(buf: String, ch: Char): Boolean {
        return ch in '0'..'9' && DATE_OFFSET_PARTIAL_REGEX.matches(buf)
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
        private const val MAX_ENGLISH_BUFFER_LENGTH = 14
        private val DATE_OFFSET_ONLY_SIGN_REGEX = Regex("^(?:orq|date)[+-]$")
        private val DATE_OFFSET_PARTIAL_REGEX = Regex("^(?:orq|date)[+-]\\d{0,3}$")
    }
}
