package com.typefree.ime.ime.engine

import com.typefree.core.ime.coordinator.CommitAction
import com.typefree.core.ime.coordinator.CoordinatorKeyOutcome
import com.typefree.core.ime.coordinator.DirectCommandType
import com.typefree.core.ime.coordinator.RimeCoreSessionPort
import com.typefree.core.ime.coordinator.RimeDecodeFrame
import com.typefree.core.ime.coordinator.SlashSnippetEntry
import com.typefree.core.ime.coordinator.TypeFreeKeyCoordinator
import com.typefree.core.ime.enhancer.BriefPrefixIndex
import com.typefree.core.ime.enhancer.CodetableCodeDeriver
import com.typefree.core.ime.enhancer.DirectCommandEngine
import com.typefree.core.ime.enhancer.PhraseSuggestion
import com.typefree.core.ime.enhancer.PhraseSuggestionTracker
import com.typefree.core.ime.enhancer.ReverseSplitEngine
import com.typefree.core.ime.enhancer.ShadowDictManager
import com.typefree.core.ime.importer.SubDictToggleManager
import com.typefree.core.ime.overlay.OverlaySymbolController
import com.typefree.core.ime.overlay.SymbolTrie
import com.typefree.core.ime.profile.SchemaProfile
import com.typefree.ime.commit.AndroidCommitExecutor
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SnippetMatcher
import com.typefree.ime.ui.CandidateBarViewModel
import com.typefree.rime.RimeBridge
import com.typefree.rime.RimeSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Adapter bridging [RimeSessionClient] (`:rime`) to pure Kotlin [RimeCoreSessionPort] (`:core`).
 */
class RimeSessionClientPortAdapter(
    val client: RimeSessionClient
) : RimeCoreSessionPort {

    override fun sendChar(ch: Char): RimeDecodeFrame {
        client.processKey(ch.code, 0)
        val committed = client.getCommit()
        val ctx = client.getContext(client.pageSize)
        return RimeDecodeFrame(
            rawInput = ctx.rawInput,
            preedit = ctx.preedit,
            candidates = ctx.candidates.map { it.text to it.comment },
            autoCommittedText = committed,
            hasMore = ctx.hasMore
        )
    }

    override fun sendBackspace(): RimeDecodeFrame {
        client.processKey(RimeBridge.XK_BackSpace, 0)
        val ctx = client.getContext(client.pageSize)
        return RimeDecodeFrame(
            rawInput = ctx.rawInput,
            preedit = ctx.preedit,
            candidates = ctx.candidates.map { it.text to it.comment },
            hasMore = ctx.hasMore
        )
    }

    override fun selectCandidate(index: Int): Pair<String?, RimeDecodeFrame> {
        val committed = client.selectCandidate(index)
        val ctx = client.getContext(client.pageSize)
        return committed to RimeDecodeFrame(
            rawInput = ctx.rawInput,
            preedit = ctx.preedit,
            candidates = ctx.candidates.map { it.text to it.comment },
            hasMore = ctx.hasMore
        )
    }

    override fun commitCurrentComposition(): String? = client.commitComposition()

    override fun clearComposition() = client.clearComposition()

    override fun currentFrame(maxCandidates: Int): RimeDecodeFrame {
        val ctx = client.getContext(maxCandidates)
        return RimeDecodeFrame(
            rawInput = ctx.rawInput,
            preedit = ctx.preedit,
            candidates = ctx.candidates.map { it.text to it.comment },
            hasMore = ctx.hasMore
        )
    }

    override fun setOption(option: String, value: Boolean) = client.setOption(option, value)

    override fun getOption(option: String): Boolean = client.getOption(option)
}

/**
 * Multi-schema codetable enhanced [InputEngine] backed by `:core` ([TypeFreeKeyCoordinator])
 * and `:rime` ([RimeSessionClient]), supporting Xiaohe Flypy (`FIXED_YINXING`), Keytao 6
 * (`PUSH_TOPUP_CODE`), Wubi/Tiger (`FIXED_SHAPECODE`), and Double-Pinyin/Pinyin (`SENTENCE_PINYIN`).
 */
class CodetableEnhancedInputEngine(
    val rimeClient: RimeSessionClient,
    initialProfile: SchemaProfile = SchemaProfile.defaultXiaoheFlypy(),
    symbolTrie: SymbolTrie = SymbolTrie.defaultXiaohe(),
    briefIndex: BriefPrefixIndex = BriefPrefixIndex.empty(),
    reverseLookupMap: Map<String, List<String>> = emptyMap(),
    private val schemaDir: File? = null,
    private val clipboardReader: () -> String = { "" },
    private val commitExecutor: AndroidCommitExecutor? = null,
    private val onToast: (String) -> Unit = {},
    private val onOpenJevCopilot: () -> Unit = {},
    private val onAsyncReloadSchema: ((String) -> Unit)? = null,
    var isActiveEnginePredicate: () -> Boolean = { true },
    var isIncognitoPredicate: () -> Boolean = { false },
    var onSingleCharModeChanged: ((Boolean) -> Unit)? = null,
    var onPhraseSuggestion: ((PhraseSuggestion?) -> Unit)? = null,
    var onUiStateSynced: ((EngineState, Boolean, Set<String>) -> Unit)? = null
) : InputEngine {

    private val portAdapter = RimeSessionClientPortAdapter(rimeClient)
    private var currentMaxCandidates: Int = RimeInputEngine.DEFAULT_PAGE_SIZE
    private var baseProfile: SchemaProfile = initialProfile
    private var currentSymbolTrie: SymbolTrie = symbolTrie
    private var currentBriefIndex: BriefPrefixIndex = briefIndex
    private var currentReverseLookupMap: Map<String, List<String>> = reverseLookupMap
    private var xiaoheDirectCommandsOverride: Boolean? = null
    private var xiaoheReverseSplitOverride: Boolean? = null
    private var naturalContinuationEnabled: Boolean = true
    var phraseSuggestionL2Enabled: Boolean = true
        private set
    private var currentSlashSnippets: List<SlashSnippetEntry> = emptyList()
    private val knownUserWords: MutableSet<String> = HashSet()
    private var uniqueSingleChar4Codes: Set<String> = buildUniqueSingleChar4Codes(reverseLookupMap)

    val phraseSuggestionTracker: PhraseSuggestionTracker = PhraseSuggestionTracker()

    val subDictToggleManager: SubDictToggleManager? = schemaDir?.let { dir ->
        SubDictToggleManager(dir) { schemaId ->
            if (onAsyncReloadSchema != null) {
                onAsyncReloadSchema.invoke(schemaId)
            } else {
                rimeClient.deploy(schemaId)
                coordinator.updateProfile(resolveEffectiveProfile(baseProfile))
                applyPreferenceOverrides()
            }
        }
    }

    var shadowDictManager: ShadowDictManager? = schemaDir?.let { dir ->
        ShadowDictManager(dir, initialProfile.schemaId)
    }
        private set

    var coordinator: TypeFreeKeyCoordinator = buildCoordinator(
        profile = resolveEffectiveProfile(initialProfile),
        symbolTrie = currentSymbolTrie,
        briefIndex = currentBriefIndex,
        reverseLookupMap = currentReverseLookupMap,
        applyIsolation = isActiveEnginePredicate()
    )
        private set

    init {
        reloadUserDictWordsCache(initialProfile.schemaId)
    }

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    val activeProfile: SchemaProfile
        get() = coordinator.profile

    val singleCharModeEnabled: Boolean
        get() = coordinator.singleCharMode

    private fun resolveEffectiveProfile(base: SchemaProfile): SchemaProfile {
        val effectiveDirect = if (base.schemaId == "flypy") {
            xiaoheDirectCommandsOverride ?: base.directCommandsEnabled
        } else {
            false
        }
        val effectiveReverse = if (base.schemaId == "flypy") {
            xiaoheReverseSplitOverride ?: base.reverseSplitEnabled
        } else {
            base.reverseSplitEnabled
        }
        return base.copy(
            directCommandsEnabled = effectiveDirect,
            reverseSplitEnabled = effectiveReverse
        )
    }

    private fun buildCoordinator(
        profile: SchemaProfile,
        symbolTrie: SymbolTrie,
        briefIndex: BriefPrefixIndex,
        reverseLookupMap: Map<String, List<String>>,
        applyIsolation: Boolean
    ): TypeFreeKeyCoordinator {
        val created = TypeFreeKeyCoordinator(
            profile = profile,
            rimePort = portAdapter,
            overlayController = OverlaySymbolController(symbolTrie),
            reverseSplitEngine = ReverseSplitEngine(briefIndex),
            directCommandEngine = DirectCommandEngine(
                reverseLookupMap = reverseLookupMap,
                clipboardReader = clipboardReader,
                fullCodeEnabledStateProvider = {
                    subDictToggleManager?.isSubDictEnabled(
                        subDictName = "flypy_full",
                        defaultEnabled = true,
                        schemaId = activeProfile.schemaId
                    ) ?: true
                },
                asciiPunctStateProvider = { rimeClient.getOption("ascii_punct") },
                simplificationStateProvider = { rimeClient.getOption("simplification") },
                activeSchemaIdProvider = { activeProfile.schemaId },
                singleCharModeStateProvider = { coordinator.singleCharMode }
            ),
            applyInitialIsolationOptions = applyIsolation,
            shadowDictManager = shadowDictManager,
            naturalContinuationEnabled = naturalContinuationEnabled
        )
        if (currentSlashSnippets.isNotEmpty()) {
            created.updateSlashSnippets(currentSlashSnippets)
        }
        return created
    }

    private fun applyPreferenceOverrides() {
        coordinator.profile = resolveEffectiveProfile(baseProfile)
        coordinator.setNaturalContinuationEnabled(naturalContinuationEnabled)
        coordinator.updateSlashSnippets(currentSlashSnippets)
    }

    fun refreshFromMetadata(
        profile: SchemaProfile,
        symbolTrie: SymbolTrie,
        briefIndex: BriefPrefixIndex,
        reverseLookupMap: Map<String, List<String>>
    ) {
        currentMaxCandidates = RimeInputEngine.DEFAULT_PAGE_SIZE
        baseProfile = profile
        currentSymbolTrie = symbolTrie
        currentBriefIndex = briefIndex
        currentReverseLookupMap = reverseLookupMap
        uniqueSingleChar4Codes = buildUniqueSingleChar4Codes(reverseLookupMap)
        reloadUserDictWordsCache(profile.schemaId)

        val effectiveProfile = resolveEffectiveProfile(profile)
        val schemaChanged = profile.schemaId != coordinator.profile.schemaId
        if (schemaChanged && schemaDir != null) {
            val prevSingleChar = coordinator.singleCharMode
            shadowDictManager = ShadowDictManager(schemaDir, profile.schemaId)
            phraseSuggestionTracker.resetWindow()
            if (isActiveEnginePredicate()) {
                rimeClient.pageSize = currentMaxCandidates
                rimeClient.selectSchema(profile.schemaId)
            }
            coordinator = buildCoordinator(
                profile = effectiveProfile,
                symbolTrie = symbolTrie,
                briefIndex = briefIndex,
                reverseLookupMap = reverseLookupMap,
                applyIsolation = isActiveEnginePredicate()
            )
            coordinator.setSingleCharModeEnabled(prevSingleChar)
        } else {
            coordinator.overlayController.updateTrie(symbolTrie)
            coordinator.reverseSplitEngine = ReverseSplitEngine(briefIndex)
            coordinator.directCommandEngine.updateReverseLookupMap(reverseLookupMap)
            if (isActiveEnginePredicate()) {
                rimeClient.pageSize = currentMaxCandidates
                rimeClient.selectSchema(profile.schemaId)
                coordinator.updateProfile(effectiveProfile)
            } else {
                coordinator.profile = effectiveProfile
                coordinator.reset()
            }
            applyPreferenceOverrides()
        }
        syncUiState()
    }

    fun reloadUserDictWordsCache(schemaId: String = activeProfile.schemaId) {
        knownUserWords.clear()
        val dir = schemaDir ?: return
        for (suffix in listOf("_user.txt", "_top.txt")) {
            val file = File(dir, "$schemaId$suffix")
            if (!file.isFile) continue
            runCatching {
                file.forEachLine(StandardCharsets.UTF_8) { raw ->
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                    val word = raw.substringBefore('\t').trim()
                    if (word.isNotEmpty()) {
                        knownUserWords.add(word)
                    }
                }
            }
        }
    }

    fun setXiaoheDirectCommandsEnabled(enabled: Boolean) {
        xiaoheDirectCommandsOverride = enabled
        coordinator.profile = resolveEffectiveProfile(baseProfile)
        if (coordinator.isComposing) syncUiState()
    }

    fun setXiaoheReverseSplitEnabled(enabled: Boolean) {
        xiaoheReverseSplitOverride = enabled
        coordinator.profile = resolveEffectiveProfile(baseProfile)
        if (coordinator.isComposing) syncUiState()
    }

    fun setXiaoheNaturalContinuationEnabled(enabled: Boolean) {
        naturalContinuationEnabled = enabled
        coordinator.setNaturalContinuationEnabled(enabled)
        if (coordinator.isComposing) syncUiState()
    }

    fun setPhraseSuggestionL2Enabled(enabled: Boolean) {
        phraseSuggestionL2Enabled = enabled
        if (!enabled) {
            phraseSuggestionTracker.resetWindow()
            onPhraseSuggestion?.invoke(null)
        }
    }

    fun markPhraseSuggestionAccepted(word: String) {
        phraseSuggestionTracker.markAccepted(word)
        if (word.isNotBlank()) knownUserWords.add(word.trim())
    }

    fun markPhraseSuggestionDismissed(word: String) {
        phraseSuggestionTracker.markDismissed(word)
    }

    fun resetPhraseSuggestionWindow() {
        phraseSuggestionTracker.resetWindow()
    }

    fun deriveXiaohe4Code(phrase: String): String? {
        return CodetableCodeDeriver.deriveXiaohe4Code(phrase, currentReverseLookupMap)
    }

    fun adjustOrqDateOffset(deltaDays: Int, nowMillis: Long = System.currentTimeMillis()) {
        coordinator.adjustOrqDateOffset(deltaDays, nowMillis)
        syncUiState()
    }

    fun setSingleCharModeEnabled(enabled: Boolean) {
        coordinator.setSingleCharModeEnabled(enabled)
        onSingleCharModeChanged?.invoke(enabled)
        syncUiState()
    }

    fun toggleSingleCharMode(): Boolean {
        val next = !coordinator.singleCharMode
        setSingleCharModeEnabled(next)
        return next
    }

    fun reloadShadowRules() {
        coordinator.reloadShadowRules()
        syncUiState()
    }

    fun currentPinnedWordsForBuffer(): Set<String> {
        return coordinator.currentPinnedWordsForBuffer().toSet()
    }

    fun pinCandidate(word: String) {
        val code = coordinator.snapshotUi().rawCode
        if (code.isBlank() || word.isBlank()) return
        shadowDictManager?.pinWord(code, word)
        coordinator.reloadShadowRules()
        syncUiState()
    }

    fun unpinCandidate(word: String) {
        val code = coordinator.snapshotUi().rawCode
        if (code.isBlank() || word.isBlank()) return
        shadowDictManager?.unpinWord(code, word)
        coordinator.reloadShadowRules()
        syncUiState()
    }

    fun blockCandidate(word: String) {
        if (word.isBlank()) return
        shadowDictManager?.blockWord(word)
        coordinator.reloadShadowRules()
        syncUiState()
    }

    override fun onKey(key: KeyInput): EngineResult {
        return when (key) {
            is KeyInput.CharKey -> {
                val snapBefore = coordinator.snapshotUi()
                val rawBefore = snapBefore.rawCode
                val candidateCode = if (
                    !coordinator.overlayController.isActive &&
                    !rawBefore.startsWith("/") &&
                    !rawBefore.startsWith(";") &&
                    key.char in 'a'..'z'
                ) {
                    if (rawBefore.length >= coordinator.profile.maxCodeLength &&
                        coordinator.profile.maxCodeLength > 0
                    ) {
                        rawBefore
                    } else {
                        rawBefore + key.char
                    }
                } else {
                    ""
                }
                dispatchOutcome(coordinator.onCharKey(key.char, key.nowMillis), candidateCode)
            }
            is KeyInput.Backspace -> dispatchOutcome(coordinator.onBackspace(key.nowMillis))
            KeyInput.Space -> {
                val rawBefore = coordinator.snapshotUi().rawCode
                val code = if (rawBefore.isNotEmpty() && rawBefore.all { it in 'a'..'z' }) rawBefore else ""
                dispatchOutcome(coordinator.onSpace(), code)
            }
            KeyInput.Enter -> dispatchOutcome(coordinator.onEnter())
            is KeyInput.CommaOrPeriod -> {
                val ch = if (key.isComma) ',' else '.'
                if (coordinator.isComposing && key.commaPeriodQuickSelect) {
                    val snapBefore = coordinator.snapshotUi()
                    val cands = snapBefore.candidates
                    val code = if (snapBefore.rawCode.all { it in 'a'..'z' }) snapBefore.rawCode else ""
                    if (key.isComma && cands.size >= 2) {
                        return dispatchOutcome(coordinator.selectCandidate(1), code)
                    }
                    if (!key.isComma && cands.size >= 3) {
                        return dispatchOutcome(coordinator.selectCandidate(2), code)
                    }
                }
                dispatchOutcome(coordinator.onCharKey(ch))
            }
        }
    }

    override fun select(index: Int): EngineResult {
        val snapBefore = coordinator.snapshotUi()
        val code = if (
            !snapBefore.isReverseSplitActive &&
            snapBefore.rawCode.isNotEmpty() &&
            snapBefore.rawCode.all { it in 'a'..'z' }
        ) {
            snapBefore.rawCode
        } else {
            ""
        }
        return dispatchOutcome(coordinator.selectCandidate(index), code)
    }

    override fun clear() {
        currentMaxCandidates = RimeInputEngine.DEFAULT_PAGE_SIZE
        rimeClient.pageSize = currentMaxCandidates
        coordinator.reset()
        syncUiState()
    }

    override fun flush(): String {
        val snap = coordinator.snapshotUi(maxCandidates = currentMaxCandidates)
        if (!snap.isComposing) return ""
        val first = snap.candidates.firstOrNull()?.commitText ?: snap.rawCode
        currentMaxCandidates = RimeInputEngine.DEFAULT_PAGE_SIZE
        rimeClient.pageSize = currentMaxCandidates
        coordinator.reset()
        syncUiState()
        return first
    }

    override fun recordExternalCommit(text: String) {
        coordinator.recordCommittedPreview(text)
        commitExecutor?.recordExternalCommit(text)
    }

    override fun requestMoreCandidates(minCount: Int) {
        val target = maxOf(currentMaxCandidates, minCount)
        currentMaxCandidates = target
        rimeClient.pageSize = target
        if (coordinator.isComposing) {
            syncUiState()
        }
    }

    override fun setIncognitoMode(incognito: Boolean) {
        if (incognito) {
            phraseSuggestionTracker.resetWindow()
        }
        if (!isActiveEnginePredicate()) return
        if (coordinator.profile.lockNaturalOrder) {
            rimeClient.setOption("_incognito", true)
            rimeClient.setOption("enable_user_dict", false)
        } else {
            rimeClient.setOption("_incognito", incognito)
            rimeClient.setOption("enable_user_dict", !incognito)
        }
    }

    override fun setFixedCandidateOrder(fixedOrder: Boolean?) {
        if (!isActiveEnginePredicate()) return
        val lock = fixedOrder ?: coordinator.profile.lockNaturalOrder
        rimeClient.setOption("fixed_candidate_order", lock)
    }

    override fun updateSnippets(snippets: List<SnippetEntry>, nowMillis: Long) {
        currentSlashSnippets = snippets.mapNotNull { entry ->
            if (!entry.enabled || entry.content.isBlank()) return@mapNotNull null
            val cleanCode = SnippetMatcher.extractSlashSnippetCode(entry.flypyCode)
            if (cleanCode.isEmpty()) {
                null
            } else {
                SlashSnippetEntry(
                    code = cleanCode,
                    title = entry.title.trim(),
                    content = entry.content
                )
            }
        }
        coordinator.updateSlashSnippets(currentSlashSnippets)
        if (coordinator.isComposing) {
            syncUiState()
        }
    }

    private fun dispatchOutcome(
        outcome: CoordinatorKeyOutcome,
        commitSourceCode: String = ""
    ): EngineResult {
        return when (outcome) {
            CoordinatorKeyOutcome.PassThrough -> {
                syncUiState()
                EngineResult.PassThrough
            }
            is CoordinatorKeyOutcome.Consumed -> {
                syncUiState()
                EngineResult.Consumed
            }
            is CoordinatorKeyOutcome.Commit -> {
                syncUiState()
                trackPhraseSuggestionIfEligible(outcome.action, commitSourceCode)
                handleCommitAction(outcome.action, outcome.feedToAutoLearn, outcome.ui.isComposing)
            }
        }
    }

    private fun trackPhraseSuggestionIfEligible(action: CommitAction, commitSourceCode: String) {
        if (!phraseSuggestionL2Enabled || isIncognitoPredicate()) {
            phraseSuggestionTracker.resetWindow()
            return
        }
        val plain = action as? CommitAction.PlainText
        if (plain == null || commitSourceCode.isEmpty() || !commitSourceCode.all { it in 'a'..'z' }) {
            phraseSuggestionTracker.resetWindow()
            return
        }
        val suggestion = phraseSuggestionTracker.recordCommittedUnit(
            text = plain.text,
            code = commitSourceCode,
            existsInDict = ::wordExistsInKnownDict,
            isUniqueSingleCharCode = { code -> code in uniqueSingleChar4Codes }
        )
        if (suggestion != null) {
            onPhraseSuggestion?.invoke(suggestion)
        }
    }

    private fun wordExistsInKnownDict(word: String): Boolean {
        if (word in knownUserWords) return true
        if (word in currentReverseLookupMap) return true
        val derived = CodetableCodeDeriver.deriveXiaohe4Code(word, currentReverseLookupMap)
        if (derived != null && derived.length == 2) {
            if (word in currentBriefIndex.lookup2(derived[0], derived[1])) return true
        }
        return false
    }

    private fun handleCommitAction(
        action: CommitAction,
        feedToAutoLearn: Boolean,
        remainComposing: Boolean
    ): EngineResult {
        // Execute builtin DirectCommand side effects (`oqm`, `ovy`, `ojf`, `oai`) directly when needed
        if (action is CommitAction.Command) {
            executeBuiltinCommand(action.commandType)
            return EngineResult.Consumed
        }

        // When host AndroidCommitExecutor is wired, delegate rich batch actions (`PairedText`, `UndoLastCommit`, etc.)
        if (commitExecutor != null) {
            commitExecutor.execute(
                action = action,
                profile = coordinator.profile,
                feedToAutoLearn = feedToAutoLearn
            )
            return EngineResult.Consumed
        }

        // Pure string fallback when running through standard EngineResult.Commit
        return when (action) {
            is CommitAction.PlainText -> EngineResult.Commit(action.text, remainComposing)
            is CommitAction.PairedText -> EngineResult.Commit(action.left + action.right, remainComposing)
            CommitAction.RepeatLastCommit -> EngineResult.Commit(coordinator.lastCommittedPreview, remainComposing)
            CommitAction.UndoLastCommit -> EngineResult.Consumed
            is CommitAction.CopyToClipboard -> {
                onToast(action.toastMessage)
                EngineResult.Consumed
            }
            is CommitAction.Command -> {
                executeBuiltinCommand(action.commandType)
                EngineResult.Consumed
            }
            is CommitAction.Composite -> {
                val sb = StringBuilder()
                for (step in action.steps) {
                    when (step) {
                        is CommitAction.PlainText -> sb.append(step.text)
                        is CommitAction.PairedText -> sb.append(step.left).append(step.right)
                        CommitAction.RepeatLastCommit -> sb.append(coordinator.lastCommittedPreview)
                        is CommitAction.Command -> executeBuiltinCommand(step.commandType)
                        else -> {}
                    }
                }
                if (sb.isNotEmpty()) {
                    EngineResult.Commit(sb.toString(), remainComposing)
                } else {
                    EngineResult.Consumed
                }
            }
        }
    }

    private fun executeBuiltinCommand(cmd: DirectCommandType) {
        when (cmd) {
            DirectCommandType.TOGGLE_FULL_CODE_DICT -> {
                val enabled = subDictToggleManager?.toggleFullCodeDict(coordinator.profile.schemaId) ?: false
                onToast(if (enabled) "✓ 全码字词库已开启 (oqm)" else "✓ 全码字词库已关闭 (oqm)")
            }
            DirectCommandType.TOGGLE_ASCII_PUNCT -> {
                val next = !rimeClient.getOption("ascii_punct")
                rimeClient.setOption("ascii_punct", next)
                onToast(if (next) "英文标点 (.)" else "中文标点 (。)")
            }
            DirectCommandType.TOGGLE_SIMPLIFICATION -> {
                val next = !rimeClient.getOption("simplification")
                rimeClient.setOption("simplification", next)
                onToast(if (next) "繁體中文模式" else "简体中文模式")
            }
            DirectCommandType.OPEN_JEV_COPILOT -> {
                onOpenJevCopilot()
            }
            DirectCommandType.TOGGLE_SINGLE_CHAR_MODE -> {
                val enabled = coordinator.singleCharMode
                onSingleCharModeChanged?.invoke(enabled)
                onToast(if (enabled) "✓ 单字模式已开启 (odz)" else "✓ 单字模式已关闭 (odz)")
            }
        }
    }

    private fun syncUiState() {
        val barState = CandidateBarViewModel.mapSnapshot(
            coordinator.snapshotUi(maxCandidates = currentMaxCandidates)
        )
        val next = EngineState(
            preedit = barState.preeditText,
            rawCode = barState.rawCode,
            candidates = barState.candidates,
            hasMore = barState.hasMore,
            isComposing = barState.isComposing,
            isOrqDateActive = barState.isOrqDateActive,
            orqDateOffsetDays = barState.orqDateOffsetDays
        )
        _state.value = next
        onUiStateSynced?.invoke(next, coordinator.singleCharMode, currentPinnedWordsForBuffer())
    }

    companion object {
        private fun buildUniqueSingleChar4Codes(reverseLookupMap: Map<String, List<String>>): Set<String> {
            if (reverseLookupMap.isEmpty()) return emptySet()
            val counts = HashMap<String, Int>(reverseLookupMap.size)
            for ((word, codes) in reverseLookupMap) {
                if (word.codePointCount(0, word.length) != 1) continue
                for (raw in codes) {
                    val c = raw.trim().lowercase(Locale.ROOT)
                    if (c.length == 4 && c.all { it in 'a'..'z' }) {
                        counts[c] = (counts[c] ?: 0) + 1
                    }
                }
            }
            return counts.filterValues { it == 1 }.keys
        }
    }
}
