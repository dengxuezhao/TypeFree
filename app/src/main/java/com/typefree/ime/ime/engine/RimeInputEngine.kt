package com.typefree.ime.ime.engine

import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.ime.flypy.FlypyCandidate
import com.typefree.rime.RimeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [InputEngine] implementation backed by `:rime` ([RimeBridge]) supporting:
 * - [InputScheme.RIME_PINYIN] (`rime_ice`, 雾凇全拼整句)
 * - [InputScheme.RIME_DOUBLE_PINYIN_FLYPY] (`double_pinyin_flypy`, 小鹤双拼整句)
 * - [InputScheme.RIME_CUSTOM] (`custom`, 用户导入的 Rime 方案包)
 *
 * Shares [CandidateMerger] with [FlypyInputEngine] so custom snippets (`;sd`, `;yx`, etc.),
 * dynamic date/time/weekday commands (`orq`, `ouj`, `oxq`), `;x` quick symbols (`;q -> ：“`),
 * and `;f` repeat commit behave identically across all input schemes without requiring librime-lua.
 */
class RimeInputEngine(
    val rimeBridge: RimeBridge = RimeBridge(),
    initialScheme: InputScheme = InputScheme.RIME_PINYIN
) : InputEngine {

    var inputScheme: InputScheme = initialScheme
        private set

    private var normalizedSnippets: List<SnippetEntry> = emptyList()
    private var currentRawCode: String = ""
    private var currentMaxCandidates: Int = DEFAULT_PAGE_SIZE

    var lastCommittedText: String = ""
        private set

    var isIncognito: Boolean = false
        private set

    var isFixedCandidateOrder: Boolean = false
        private set

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    val isComposing: Boolean
        get() = currentRawCode.isNotEmpty()

    init {
        applySchemeToBridge(initialScheme)
    }

    fun setInputScheme(scheme: InputScheme) {
        if (inputScheme == scheme && rimeBridge.activeSchemaId == resolveSchemaId(scheme)) {
            return
        }
        inputScheme = scheme
        applySchemeToBridge(scheme)
        clear()
    }

    private fun resolveSchemaId(scheme: InputScheme): String {
        return when (scheme) {
            InputScheme.RIME_DOUBLE_PINYIN_FLYPY -> "double_pinyin_flypy"
            InputScheme.RIME_CUSTOM -> "custom"
            else -> "rime_ice"
        }
    }

    private fun applySchemeToBridge(scheme: InputScheme) {
        val targetSchema = resolveSchemaId(scheme)
        rimeBridge.selectSchema(targetSchema)
    }

    override fun updateSnippets(snippets: List<SnippetEntry>, nowMillis: Long) {
        normalizedSnippets = CandidateMerger.normalizeSnippets(snippets)
        if (currentRawCode.isNotEmpty()) {
            syncState(nowMillis)
        }
    }

    override fun recordExternalCommit(text: String) {
        if (text.isNotBlank()) {
            lastCommittedText = text
        }
    }

    override fun requestMoreCandidates(minCount: Int) {
        val target = maxOf(currentMaxCandidates, minCount)
        currentMaxCandidates = target
        rimeBridge.pageSize = target
        if (currentRawCode.isNotEmpty()) {
            syncState()
        }
    }

    override fun setIncognitoMode(incognito: Boolean) {
        isIncognito = incognito
        rimeBridge.setOption("_incognito", incognito)
        rimeBridge.setOption("enable_user_dict", !incognito)
        if (currentRawCode.isNotEmpty()) {
            syncState()
        }
    }

    override fun setFixedCandidateOrder(fixedOrder: Boolean?) {
        val resolved = fixedOrder ?: false
        isFixedCandidateOrder = resolved
        rimeBridge.setOption("fixed_candidate_order", resolved)
        if (currentRawCode.isNotEmpty()) {
            syncState()
        }
    }

    fun learnCustomPhrase(pinyinKey: String, word: String): Boolean {
        return rimeBridge.learnWord(pinyinKey, word)
    }

    override fun onKey(key: KeyInput): EngineResult {
        return when (key) {
            is KeyInput.CharKey -> onCharInput(key.char, key.nowMillis)
            is KeyInput.Backspace -> onBackspace(key.nowMillis)
            KeyInput.Space -> onSpace()
            KeyInput.Enter -> onEnter()
            is KeyInput.CommaOrPeriod -> onCommaOrPeriod(key.isComma, key.commaPeriodQuickSelect)
        }
    }

    override fun select(index: Int): EngineResult {
        val cands = _state.value.candidates
        val picked = cands.getOrNull(index) ?: return EngineResult.Consumed
        if (!currentRawCode.startsWith(";")) {
            val prefixCount = CandidateMerger.exactSnippetsForCode(currentRawCode, normalizedSnippets).size +
                CandidateMerger.dynamicCommandCandidates(currentRawCode).size
            val rimeIdx = index - prefixCount
            if (rimeIdx >= 0) {
                rimeBridge.selectCandidate(rimeIdx)
            }
        }
        clear()
        lastCommittedText = picked.text
        return EngineResult.Commit(picked.text, remainComposing = false)
    }

    override fun clear() {
        currentRawCode = ""
        currentMaxCandidates = DEFAULT_PAGE_SIZE
        rimeBridge.pageSize = DEFAULT_PAGE_SIZE
        rimeBridge.clearComposition()
        _state.value = EngineState()
    }

    override fun flush(): String {
        if (currentRawCode.isEmpty()) return ""
        val cands = _state.value.candidates
        if (cands.isNotEmpty()) {
            val res = select(0)
            return (res as? EngineResult.Commit)?.text.orEmpty()
        }
        val raw = currentRawCode
        clear()
        lastCommittedText = raw
        return raw
    }

    private fun onCharInput(ch: Char, nowMillis: Long): EngineResult {
        // 1. Semicolon (`;`) — Quick-symbol prefix when idle, secondary select (`分号次选`) when composing
        if (ch == ';') {
            return when {
                currentRawCode.isEmpty() -> {
                    currentRawCode = ";"
                    rimeBridge.clearComposition()
                    syncState(nowMillis)
                    EngineResult.Consumed
                }
                currentRawCode == ";" -> {
                    val semiSecond = _state.value.candidates.getOrNull(1)?.text ?: "；"
                    clear()
                    lastCommittedText = semiSecond
                    EngineResult.Commit(semiSecond, remainComposing = false)
                }
                _state.value.candidates.size >= 2 -> {
                    select(1)
                }
                else -> {
                    val pushed = flush()
                    currentRawCode = ";"
                    rimeBridge.clearComposition()
                    syncState(nowMillis)
                    if (pushed.isNotEmpty()) {
                        lastCommittedText = pushed
                        EngineResult.Commit(pushed, remainComposing = true)
                    } else {
                        EngineResult.Consumed
                    }
                }
            }
        }

        // 2. Uppercase letters (`Shift` / `Caps Lock`): flush active composition + commit uppercase letter
        if (ch in 'A'..'Z') {
            val pushed = flush()
            val out = pushed + ch
            lastCommittedText = out
            return EngineResult.Commit(out, remainComposing = false)
        }

        // 3. Semicolon quick symbols (`;q -> ：“`, `;w -> ？`), `;f` repeat, and `;xx` custom snippets
        if (currentRawCode.startsWith(";")) {
            if (ch !in 'a'..'z') {
                val pushed = flush()
                val out = pushed + ch
                if (out.isNotBlank()) lastCommittedText = out
                return EngineResult.Commit(out, remainComposing = false)
            }
            if (currentRawCode.length >= 6) {
                val pushed = flush()
                val follow = onCharInput(ch, nowMillis)
                return mergePushedWithFollowUp(pushed, follow)
            }
            val nextCode = currentRawCode + ch
            val nextCands = CandidateMerger.mergeCandidates(
                rawCode = nextCode,
                engineCandidates = emptyList(),
                normalizedSnippets = normalizedSnippets,
                lastCommittedText = lastCommittedText,
                nowMillis = nowMillis,
                includeBuiltInQuickSymbols = true
            )
            val hasMoreSnippet = CandidateMerger.hasCustomSnippetContinuation(nextCode, normalizedSnippets)
            return when {
                nextCands.size == 1 && !hasMoreSnippet -> {
                    val committed = nextCands[0].text
                    clear()
                    lastCommittedText = committed
                    EngineResult.Commit(committed, remainComposing = false)
                }
                nextCands.isEmpty() && !hasMoreSnippet -> {
                    if (currentRawCode == ";") {
                        clear()
                        EngineResult.Consumed
                    } else {
                        val pushed = flush()
                        val follow = onCharInput(ch, nowMillis)
                        mergePushedWithFollowUp(pushed, follow)
                    }
                }
                else -> {
                    currentRawCode = nextCode
                    syncState(nowMillis)
                    EngineResult.Consumed
                }
            }
        }

        // 4. Standard Rime input (`a-z` and syllable separator `'`)
        if (ch in 'a'..'z' || (ch == '\'' && currentRawCode.isNotEmpty())) {
            if (currentRawCode.length >= MAX_RIME_INPUT_LENGTH) {
                return EngineResult.Consumed
            }
            currentRawCode += ch
            rimeBridge.processKey(ch.code, 0)
            syncState(nowMillis)
            return EngineResult.Consumed
        }

        // 5. Other characters: flush active candidate + commit character directly
        val pushed = flush()
        val out = pushed + ch
        if (out.isNotBlank()) {
            lastCommittedText = out
        }
        return EngineResult.Commit(out, remainComposing = false)
    }

    private fun onBackspace(nowMillis: Long): EngineResult {
        if (currentRawCode.isEmpty()) return EngineResult.PassThrough
        currentRawCode = currentRawCode.dropLast(1)
        if (currentRawCode.isEmpty()) {
            clear()
        } else {
            if (!currentRawCode.startsWith(";")) {
                rimeBridge.processKey(RimeBridge.KEY_BACKSPACE, 0)
            }
            syncState(nowMillis)
        }
        return EngineResult.Consumed
    }

    private fun onSpace(): EngineResult {
        if (currentRawCode.isEmpty()) return EngineResult.PassThrough
        val cands = _state.value.candidates
        return if (cands.isNotEmpty()) {
            select(0)
        } else {
            clear()
            EngineResult.Consumed
        }
    }

    private fun onEnter(): EngineResult {
        if (currentRawCode.isEmpty()) return EngineResult.PassThrough
        val raw = currentRawCode
        clear()
        lastCommittedText = raw
        return EngineResult.Commit(raw, remainComposing = false)
    }

    private fun onCommaOrPeriod(isComma: Boolean, commaPeriodQuickSelect: Boolean): EngineResult {
        val cnPunct = if (isComma) "，" else "。"
        if (currentRawCode.isEmpty()) {
            lastCommittedText = cnPunct
            return EngineResult.Commit(cnPunct, remainComposing = false)
        }

        val cands = _state.value.candidates
        if (cands.isEmpty()) {
            clear()
            return EngineResult.Consumed
        }

        if (commaPeriodQuickSelect) {
            if (isComma && cands.size >= 2) {
                return select(1)
            }
            if (!isComma && cands.size >= 3) {
                return select(2)
            }
        }

        val firstRes = select(0)
        val firstText = (firstRes as? EngineResult.Commit)?.text.orEmpty()
        val topUp = firstText + cnPunct
        lastCommittedText = topUp
        return EngineResult.Commit(topUp, remainComposing = false)
    }

    private fun mergePushedWithFollowUp(pushed: String, follow: EngineResult): EngineResult {
        return when (follow) {
            is EngineResult.Commit -> EngineResult.Commit(
                text = pushed + follow.text,
                remainComposing = follow.remainComposing
            )
            EngineResult.Consumed, EngineResult.PassThrough -> {
                if (pushed.isNotEmpty()) {
                    EngineResult.Commit(text = pushed, remainComposing = isComposing)
                } else {
                    EngineResult.Consumed
                }
            }
        }
    }

    fun syncState(nowMillis: Long = System.currentTimeMillis()) {
        if (currentRawCode.isEmpty()) {
            _state.value = EngineState()
            return
        }
        if (currentRawCode.startsWith(";")) {
            val merged = CandidateMerger.mergeCandidates(
                rawCode = currentRawCode,
                engineCandidates = emptyList(),
                normalizedSnippets = normalizedSnippets,
                lastCommittedText = lastCommittedText,
                nowMillis = nowMillis,
                includeBuiltInQuickSymbols = true
            )
            _state.value = EngineState(
                preedit = currentRawCode,
                rawCode = currentRawCode,
                candidates = merged,
                hasMore = false,
                isComposing = true
            )
            return
        }

        val rimeCtx = rimeBridge.getContext(currentMaxCandidates)
        val engineCands = rimeCtx.candidates.map { rc ->
            FlypyCandidate(text = rc.text, codeHint = rc.comment)
        }
        val merged = CandidateMerger.mergeCandidates(
            rawCode = currentRawCode,
            engineCandidates = engineCands,
            normalizedSnippets = normalizedSnippets,
            lastCommittedText = lastCommittedText,
            nowMillis = nowMillis,
            includeBuiltInQuickSymbols = true
        )
        val preeditStr = rimeCtx.preedit.ifEmpty { currentRawCode }
        _state.value = EngineState(
            preedit = preeditStr,
            rawCode = currentRawCode,
            candidates = merged,
            hasMore = rimeCtx.hasMore,
            isComposing = true
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 24
        const val MAX_RIME_INPUT_LENGTH = 64
    }
}
