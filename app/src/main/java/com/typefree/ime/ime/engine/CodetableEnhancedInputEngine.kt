package com.typefree.ime.ime.engine

import com.typefree.core.ime.coordinator.CommitAction
import com.typefree.core.ime.coordinator.CoordinatorKeyOutcome
import com.typefree.core.ime.coordinator.DirectCommandType
import com.typefree.core.ime.coordinator.RimeCoreSessionPort
import com.typefree.core.ime.coordinator.RimeDecodeFrame
import com.typefree.core.ime.coordinator.TypeFreeKeyCoordinator
import com.typefree.core.ime.enhancer.BriefPrefixIndex
import com.typefree.core.ime.enhancer.DirectCommandEngine
import com.typefree.core.ime.enhancer.ReverseSplitEngine
import com.typefree.core.ime.importer.SubDictToggleManager
import com.typefree.core.ime.overlay.OverlaySymbolController
import com.typefree.core.ime.overlay.SymbolTrie
import com.typefree.core.ime.profile.SchemaProfile
import com.typefree.ime.commit.AndroidCommitExecutor
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.ui.CandidateBarViewModel
import com.typefree.rime.RimeBridge
import com.typefree.rime.RimeSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

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
    var isActiveEnginePredicate: () -> Boolean = { true }
) : InputEngine {

    private val portAdapter = RimeSessionClientPortAdapter(rimeClient)
    private var currentMaxCandidates: Int = RimeInputEngine.DEFAULT_PAGE_SIZE

    val subDictToggleManager: SubDictToggleManager? = schemaDir?.let { dir ->
        SubDictToggleManager(dir) { schemaId ->
            if (onAsyncReloadSchema != null) {
                onAsyncReloadSchema.invoke(schemaId)
            } else {
                rimeClient.deploy(schemaId)
                coordinator.updateProfile(coordinator.profile)
            }
        }
    }

    val coordinator = TypeFreeKeyCoordinator(
        profile = initialProfile,
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
            simplificationStateProvider = { rimeClient.getOption("simplification") }
        ),
        applyInitialIsolationOptions = isActiveEnginePredicate()
    )

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    val activeProfile: SchemaProfile
        get() = coordinator.profile

    fun refreshFromMetadata(
        profile: SchemaProfile,
        symbolTrie: SymbolTrie,
        briefIndex: BriefPrefixIndex,
        reverseLookupMap: Map<String, List<String>>
    ) {
        currentMaxCandidates = RimeInputEngine.DEFAULT_PAGE_SIZE
        coordinator.overlayController.updateTrie(symbolTrie)
        coordinator.reverseSplitEngine = ReverseSplitEngine(briefIndex)
        coordinator.directCommandEngine.updateReverseLookupMap(reverseLookupMap)
        if (isActiveEnginePredicate()) {
            rimeClient.pageSize = currentMaxCandidates
            rimeClient.selectSchema(profile.schemaId)
            coordinator.updateProfile(profile)
        } else {
            coordinator.profile = profile
            coordinator.reset()
        }
        syncUiState()
    }

    override fun onKey(key: KeyInput): EngineResult {
        return when (key) {
            is KeyInput.CharKey -> dispatchOutcome(coordinator.onCharKey(key.char, key.nowMillis))
            is KeyInput.Backspace -> dispatchOutcome(coordinator.onBackspace(key.nowMillis))
            KeyInput.Space -> dispatchOutcome(coordinator.onSpace())
            KeyInput.Enter -> {
                val snap = coordinator.snapshotUi()
                if (!snap.isComposing) {
                    EngineResult.PassThrough
                } else {
                    val raw = snap.rawCode
                    coordinator.reset()
                    syncUiState()
                    EngineResult.Commit(raw, remainComposing = false)
                }
            }
            is KeyInput.CommaOrPeriod -> {
                val ch = if (key.isComma) ',' else '.'
                if (coordinator.isComposing && key.commaPeriodQuickSelect) {
                    val cands = coordinator.snapshotUi().candidates
                    if (key.isComma && cands.size >= 2) {
                        return dispatchOutcome(coordinator.selectCandidate(1))
                    }
                    if (!key.isComma && cands.size >= 3) {
                        return dispatchOutcome(coordinator.selectCandidate(2))
                    }
                }
                dispatchOutcome(coordinator.onCharKey(ch))
            }
        }
    }

    override fun select(index: Int): EngineResult {
        return dispatchOutcome(coordinator.selectCandidate(index))
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
        // Preserves interface compatibility
    }

    private fun dispatchOutcome(outcome: CoordinatorKeyOutcome): EngineResult {
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
                handleCommitAction(outcome.action, outcome.feedToAutoLearn, outcome.ui.isComposing)
            }
        }
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
        }
    }

    private fun syncUiState() {
        val barState = CandidateBarViewModel.mapSnapshot(
            coordinator.snapshotUi(maxCandidates = currentMaxCandidates)
        )
        _state.value = EngineState(
            preedit = barState.preeditText,
            rawCode = barState.rawCode,
            candidates = barState.candidates,
            hasMore = barState.hasMore,
            isComposing = barState.isComposing
        )
    }
}
