package com.typefree.ime.ime.engine

import com.typefree.ime.data.DoublePinyinLayout
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.ime.flypy.FlypyCandidate
import com.typefree.rime.RimeBridge
import com.typefree.rime.RimeSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [InputEngine] implementation backed by `:rime` ([RimeSessionClient] / [RimeBridge]) supporting:
 * - [InputScheme.RIME_PINYIN] (`rime_ice`, 雾凇全拼整句)
 * - [InputScheme.RIME_DOUBLE_PINYIN_FLYPY] (`double_pinyin_flypy`, 小鹤双拼整句)
 * - [InputScheme.RIME_CUSTOM] (`custom`, 用户导入的 Rime 方案包)
 *
 * Shares [CandidateMerger] with [FlypyInputEngine] so custom snippets (`;sd`, `;yx`, etc.),
 * dynamic date/time/weekday commands (`orq`, `ouj`, `oxq`), `;x` quick symbols (`;q -> ：“`),
 * and `;f` repeat commit behave identically across all input schemes.
 */
class RimeInputEngine(
    val rimeBridge: RimeSessionClient = RimeBridge(),
    initialScheme: InputScheme = InputScheme.RIME_PINYIN,
    var externalEnhancedSchemePredicate: (InputScheme) -> Boolean = { it == InputScheme.FLYPY_TABLE },
    var customSchemaIdProvider: () -> String = { "custom" },
    initialDoublePinyinLayout: DoublePinyinLayout = DoublePinyinLayout.FLYPY,
    var onEnsureSchemaDeployed: ((String) -> Boolean)? = null
) : InputEngine {

    var inputScheme: InputScheme = initialScheme
        private set

    var doublePinyinLayout: DoublePinyinLayout = initialDoublePinyinLayout
        private set

    private var normalizedSnippets: List<SnippetEntry> = emptyList()
    private var currentRawCode: String = ""
    private var currentMaxCandidates: Int = DEFAULT_PAGE_SIZE
    private var t9PendingCommittedPrefix: String = ""
    private val t9LockedSegments: ArrayDeque<Pair<String, String>> = ArrayDeque()

    private val isT9Mode: Boolean
        get() = inputScheme == InputScheme.RIME_T9_PINYIN

    var lastCommittedText: String = ""
        private set

    var isIncognito: Boolean = false
        private set

    var isFixedCandidateOrder: Boolean = false
        private set

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    val isComposing: Boolean
        get() = currentRawCode.isNotEmpty() || t9PendingCommittedPrefix.isNotEmpty()

    init {
        if (!externalEnhancedSchemePredicate(initialScheme)) {
            applySchemeToBridge(initialScheme)
        }
    }

    fun setDoublePinyinLayout(layout: DoublePinyinLayout) {
        if (doublePinyinLayout == layout &&
            (inputScheme != InputScheme.RIME_DOUBLE_PINYIN_FLYPY || rimeBridge.activeSchemaId == layout.rimeSchemaId)
        ) {
            return
        }
        doublePinyinLayout = layout
        if (inputScheme == InputScheme.RIME_DOUBLE_PINYIN_FLYPY && !externalEnhancedSchemePredicate(inputScheme)) {
            applySchemeToBridge(inputScheme)
            clear()
        }
    }

    fun setInputScheme(
        scheme: InputScheme,
        layout: DoublePinyinLayout = doublePinyinLayout
    ) {
        doublePinyinLayout = layout
        if (externalEnhancedSchemePredicate(scheme)) {
            inputScheme = scheme
            currentRawCode = ""
            _state.value = EngineState()
            return
        }
        val targetSchema = resolveSchemaId(scheme)
        if (inputScheme == scheme && rimeBridge.activeSchemaId == targetSchema) {
            return
        }
        inputScheme = scheme
        applySchemeToBridge(scheme)
        clear()
    }

    private fun resolveSchemaId(scheme: InputScheme): String {
        return when (scheme) {
            InputScheme.RIME_T9_PINYIN -> "t9"
            InputScheme.RIME_DOUBLE_PINYIN_FLYPY -> doublePinyinLayout.rimeSchemaId
            InputScheme.RIME_CUSTOM -> customSchemaIdProvider().ifBlank { "custom" }
            else -> "rime_ice"
        }
    }

    private fun applySchemeToBridge(scheme: InputScheme) {
        val targetSchema = resolveSchemaId(scheme)
        val selected = rimeBridge.selectSchema(targetSchema)
        if (!selected) {
            val deployedViaManager = onEnsureSchemaDeployed?.invoke(targetSchema) == true
            if (!deployedViaManager && rimeBridge.isNativeLoaded && rimeBridge.isDeployed) {
                rimeBridge.deploy(targetSchema)
                rimeBridge.selectSchema(targetSchema)
            } else if (deployedViaManager && rimeBridge.activeSchemaId != targetSchema) {
                rimeBridge.selectSchema(targetSchema)
            }
        }
        applySessionOptions()
    }

    private fun applySessionOptions() {
        rimeBridge.setOption("_incognito", isIncognito)
        rimeBridge.setOption("enable_user_dict", !isIncognito)
        rimeBridge.setOption("fixed_candidate_order", isFixedCandidateOrder)
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
        if (externalEnhancedSchemePredicate(inputScheme)) return
        rimeBridge.setOption("_incognito", incognito)
        rimeBridge.setOption("enable_user_dict", !incognito)
        if (currentRawCode.isNotEmpty()) {
            syncState()
        }
    }

    override fun setFixedCandidateOrder(fixedOrder: Boolean?) {
        val resolved = fixedOrder ?: false
        isFixedCandidateOrder = resolved
        if (externalEnhancedSchemePredicate(inputScheme)) return
        rimeBridge.setOption("fixed_candidate_order", resolved)
        if (currentRawCode.isNotEmpty()) {
            syncState()
        }
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
        if (!currentRawCode.startsWith(";") && !currentRawCode.startsWith("/")) {
            val prefixCount = CandidateMerger.exactSnippetsForCode(currentRawCode, normalizedSnippets).size +
                CandidateMerger.dynamicCommandCandidates(currentRawCode).size
            val rimeIdx = index - prefixCount
            if (rimeIdx >= 0) {
                val committed = rimeBridge.selectCandidate(rimeIdx)
                val afterCtx = rimeBridge.getContext(currentMaxCandidates)
                if (committed == null && afterCtx.rawInput.isNotEmpty() && afterCtx.candidates.isNotEmpty()) {
                    // Partial segment selection in multi-syllable composition: stay composing remaining syllables
                    currentRawCode = afterCtx.rawInput
                    syncState()
                    return EngineResult.Consumed
                }
                val finalOut = t9PendingCommittedPrefix + (committed?.takeIf { it.isNotEmpty() } ?: picked.text)
                clear()
                lastCommittedText = finalOut
                return EngineResult.Commit(finalOut, remainComposing = false)
            }
        }
        val finalOut = t9PendingCommittedPrefix + picked.text
        clear()
        lastCommittedText = finalOut
        return EngineResult.Commit(finalOut, remainComposing = false)
    }

    /**
     * Locks the leading unlocked T9 digit span into an explicit Pinyin syllable (e.g. `64426` + `"mi"` -> `"mi'426"`).
     * Works both from the beginning of a sentence and after partial candidate selection.
     */
    fun lockT9Syllable(syllable: String, nowMillis: Long = System.currentTimeMillis()): EngineResult {
        if (!isT9Mode || currentRawCode.isEmpty()) return EngineResult.Consumed
        val cleanSyl = syllable.trim().lowercase().filter { it in 'a'..'z' }
        if (cleanSyl.isEmpty()) return EngineResult.Consumed
        val sylDigits = T9PinyinHelper.syllableToDigits(cleanSyl)
        if (sylDigits.isEmpty()) return EngineResult.Consumed

        val rimeCtx = rimeBridge.getContext(currentMaxCandidates)
        val (inCompPrefix, tailCode) = T9PinyinHelper.splitRimePreedit(rimeCtx.preedit)
        val workCode = if (inCompPrefix.isNotEmpty()) {
            t9PendingCommittedPrefix += inCompPrefix
            t9LockedSegments.clear()
            tailCode.replace(" ", "")
        } else {
            currentRawCode
        }

        val firstDigitIdx = workCode.indexOfFirst { it in '2'..'9' }
        if (firstDigitIdx < 0) return EngineResult.Consumed

        // Consume exactly sylDigits.length digits starting from firstDigitIdx (skipping any delimiter `'`)
        var cursor = firstDigitIdx
        var matched = 0
        while (cursor < workCode.length && matched < sylDigits.length) {
            val ch = workCode[cursor]
            if (ch == '\'') {
                cursor++
                continue
            }
            if (ch != sylDigits[matched]) {
                return EngineResult.Consumed
            }
            matched++
            cursor++
        }
        if (matched != sylDigits.length) return EngineResult.Consumed

        val rawPrefix = workCode.substring(0, firstDigitIdx)
        val normalizedPrefix = if (rawPrefix.isNotEmpty() && !rawPrefix.endsWith('\'')) {
            "$rawPrefix'"
        } else {
            rawPrefix
        }
        val rawSuffix = workCode.substring(cursor).trimStart('\'')
        val rebuilt = buildString {
            append(normalizedPrefix)
            append(cleanSyl)
            if (rawSuffix.isNotEmpty()) {
                append('\'')
                append(rawSuffix)
            }
        }

        t9LockedSegments.addLast(cleanSyl to sylDigits)
        currentRawCode = rebuilt
        rimeBridge.clearComposition()
        rimeBridge.processKeyText(rebuilt)
        syncState(nowMillis)
        return EngineResult.Consumed
    }

    override fun clear() {
        val wasComposing = currentRawCode.isNotEmpty() || t9PendingCommittedPrefix.isNotEmpty()
        currentRawCode = ""
        t9PendingCommittedPrefix = ""
        t9LockedSegments.clear()
        currentMaxCandidates = DEFAULT_PAGE_SIZE
        if (!externalEnhancedSchemePredicate(inputScheme) || wasComposing) {
            rimeBridge.pageSize = DEFAULT_PAGE_SIZE
            rimeBridge.clearComposition()
        }
        _state.value = EngineState()
    }

    override fun flush(): String {
        if (currentRawCode.isEmpty() && t9PendingCommittedPrefix.isEmpty()) return ""
        val prefix = t9PendingCommittedPrefix
        val cands = _state.value.candidates
        if (cands.isNotEmpty()) {
            if (!currentRawCode.startsWith(";") && !currentRawCode.startsWith("/")) {
                val committed = rimeBridge.commitComposition()
                if (!committed.isNullOrEmpty()) {
                    val full = prefix + committed
                    clear()
                    lastCommittedText = full
                    return full
                }
            }
            val firstText = prefix + cands.first().text
            clear()
            lastCommittedText = firstText
            return firstText
        }
        val raw = prefix + currentRawCode
        clear()
        lastCommittedText = raw
        return raw
    }

    private fun onCharInput(ch: Char, nowMillis: Long): EngineResult {
        // T9 9-key Pinyin mode: route digits `2-9` and syllable delimiter `'` directly into Rime `t9` schema;
        // any other character (punctuation, symbols, 0/1) flushes active composition and commits directly.
        if (isT9Mode) {
            if (rimeBridge.activeSchemaId != "t9") {
                applySchemeToBridge(InputScheme.RIME_T9_PINYIN)
            }
            if (T9PinyinHelper.isT9Digit(ch) || (ch == '\'' && currentRawCode.isNotEmpty())) {
                if (currentRawCode.length >= MAX_RIME_INPUT_LENGTH) {
                    return EngineResult.Consumed
                }
                if (ch == '\'' && currentRawCode.endsWith('\'')) {
                    return EngineResult.Consumed
                }
                // Auto-insert separator if appending a digit immediately after a locked letter syllable (e.g. "ni" + '4' -> "ni'4")
                if (T9PinyinHelper.isT9Digit(ch) && currentRawCode.lastOrNull() in 'a'..'z') {
                    currentRawCode += '\''
                    rimeBridge.processKey('\''.code, 0)
                }
                currentRawCode += ch
                rimeBridge.processKey(ch.code, 0)
                val autoCommit = rimeBridge.getCommit()
                if (!autoCommit.isNullOrEmpty()) {
                    val afterCtx = rimeBridge.getContext(currentMaxCandidates)
                    if (afterCtx.rawInput.isEmpty()) {
                        val full = t9PendingCommittedPrefix + autoCommit
                        clear()
                        lastCommittedText = full
                        return EngineResult.Commit(full, remainComposing = false)
                    }
                }
                syncState(nowMillis)
                return EngineResult.Consumed
            }
            val pushed = flush()
            val out = pushed + ch
            if (out.isNotBlank()) {
                lastCommittedText = out
            }
            return EngineResult.Commit(out, remainComposing = false)
        }

        // 0. Slash (`/`): idle enters `/xx` snippet mode, `//` commits literal `/`
        if (ch == '/') {
            return when {
                currentRawCode.isEmpty() -> {
                    currentRawCode = "/"
                    rimeBridge.clearComposition()
                    syncState(nowMillis)
                    EngineResult.Consumed
                }
                currentRawCode == "/" -> {
                    clear()
                    lastCommittedText = "/"
                    EngineResult.Commit("/", remainComposing = false)
                }
                else -> {
                    val pushed = flush()
                    val out = pushed + '/'
                    lastCommittedText = out
                    EngineResult.Commit(out, remainComposing = false)
                }
            }
        }

        // 0b. Active `/xx` custom snippet mode
        if (currentRawCode.startsWith("/")) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                currentRawCode += ch.lowercaseChar()
                syncState(nowMillis)
                return EngineResult.Consumed
            }
            if ((ch == ';' || ch == '；') && currentRawCode.length > 1) {
                return when {
                    _state.value.candidates.size >= 2 -> select(1)
                    _state.value.candidates.size == 1 -> select(0)
                    else -> {
                        clear()
                        EngineResult.Consumed
                    }
                }
            }
            if (currentRawCode == "/") {
                clear()
                val out = "/$ch"
                lastCommittedText = out
                return EngineResult.Commit(out, remainComposing = false)
            }
            val pushed = flush()
            val out = pushed + ch
            if (out.isNotBlank()) lastCommittedText = out
            return EngineResult.Commit(out, remainComposing = false)
        }

        val isSemicolonShuangpinRhyme = ch == ';' &&
            inputScheme == InputScheme.RIME_DOUBLE_PINYIN_FLYPY &&
            doublePinyinLayout.usesSemicolonKey &&
            currentRawCode.isNotEmpty() &&
            !currentRawCode.startsWith(";") &&
            !currentRawCode.startsWith("/")

        // 1. Semicolon (`;` / `；`) — Quick-symbol prefix when idle, secondary select (`分号次选`) when composing
        //    (except in Microsoft / Sogou / Ziguang Shuangpin where `;` is the `ing` / `uai` phonetic rhyme key).
        if ((ch == ';' || ch == '；') && !isSemicolonShuangpinRhyme) {
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

        // 4. Standard Rime input (`a-z`, syllable separator `'`, and `;` rhyme in MSPY/Sogou/Ziguang Shuangpin)
        if (ch in 'a'..'z' || (ch == '\'' && currentRawCode.isNotEmpty()) || isSemicolonShuangpinRhyme) {
            if (currentRawCode.length >= MAX_RIME_INPUT_LENGTH) {
                return EngineResult.Consumed
            }
            currentRawCode += ch
            rimeBridge.processKey(ch.code, 0)
            val autoCommit = rimeBridge.getCommit()
            if (!autoCommit.isNullOrEmpty()) {
                val afterCtx = rimeBridge.getContext(currentMaxCandidates)
                if (afterCtx.rawInput.isEmpty()) {
                    clear()
                    lastCommittedText = autoCommit
                    return EngineResult.Commit(autoCommit, remainComposing = false)
                }
            }
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
        if (currentRawCode.isEmpty() && t9PendingCommittedPrefix.isEmpty()) return EngineResult.PassThrough
        if (isT9Mode) {
            return onT9Backspace(nowMillis)
        }
        if (currentRawCode.startsWith(";") || currentRawCode.startsWith("/")) {
            currentRawCode = currentRawCode.dropLast(1)
            if (currentRawCode.isEmpty()) {
                clear()
            } else {
                syncState(nowMillis)
            }
            return EngineResult.Consumed
        }

        rimeBridge.processKey(RimeBridge.XK_BackSpace, 0)
        val afterCtx = rimeBridge.getContext(currentMaxCandidates)
        val nextRaw = afterCtx.rawInput.ifEmpty { currentRawCode.dropLast(1) }
        if (nextRaw.isEmpty() && afterCtx.preedit.isEmpty()) {
            clear()
        } else {
            currentRawCode = nextRaw
            syncState(nowMillis)
        }
        return EngineResult.Consumed
    }

    private fun onT9Backspace(nowMillis: Long): EngineResult {
        if (currentRawCode.isEmpty()) {
            if (t9PendingCommittedPrefix.isNotEmpty()) {
                t9PendingCommittedPrefix = t9PendingCommittedPrefix.dropLast(1)
                if (t9PendingCommittedPrefix.isEmpty()) {
                    clear()
                } else {
                    syncState(nowMillis)
                }
                return EngineResult.Consumed
            }
            return EngineResult.PassThrough
        }

        // 1. If Rime holds a partial Chinese selection inside active preedit (e.g. "今天8426 74"),
        // let Rime natively undo that partial selection first.
        val beforeCtx = rimeBridge.getContext(currentMaxCandidates)
        val (inCompPrefix, _) = T9PinyinHelper.splitRimePreedit(beforeCtx.preedit)
        if (inCompPrefix.isNotEmpty()) {
            rimeBridge.processKey(RimeBridge.XK_BackSpace, 0)
            val afterCtx = rimeBridge.getContext(currentMaxCandidates)
            currentRawCode = afterCtx.rawInput.ifEmpty { currentRawCode }
            syncState(nowMillis)
            return EngineResult.Consumed
        }

        // 2. If the composition ends right on a locked left-rail syllable (no trailing digits after it),
        // unlock that syllable back to its original T9 digits so accidental taps undo cleanly.
        val trimmedCode = currentRawCode.trimEnd('\'')
        if (t9LockedSegments.isNotEmpty() && trimmedCode.isNotEmpty() && trimmedCode.last() in 'a'..'z') {
            val (lockedSyl, origDigits) = t9LockedSegments.last()
            if (trimmedCode.endsWith(lockedSyl)) {
                t9LockedSegments.removeLast()
                val base = trimmedCode.dropLast(lockedSyl.length)
                val restored = base + origDigits
                currentRawCode = restored
                rimeBridge.clearComposition()
                rimeBridge.processKeyText(restored)
                syncState(nowMillis)
                return EngineResult.Consumed
            }
        }

        // 3. Otherwise delete the trailing key from Rime; also prune an orphaned trailing `'` after a locked syllable.
        rimeBridge.processKey(RimeBridge.XK_BackSpace, 0)
        var afterCtx = rimeBridge.getContext(currentMaxCandidates)
        var nextRaw = afterCtx.rawInput.ifEmpty { currentRawCode.dropLast(1) }
        if (nextRaw.endsWith('\'') && t9LockedSegments.isNotEmpty()) {
            rimeBridge.processKey(RimeBridge.XK_BackSpace, 0)
            afterCtx = rimeBridge.getContext(currentMaxCandidates)
            nextRaw = afterCtx.rawInput.ifEmpty { nextRaw.dropLast(1) }
        }
        if (nextRaw.isEmpty() && afterCtx.preedit.isEmpty()) {
            if (t9PendingCommittedPrefix.isNotEmpty()) {
                currentRawCode = ""
                syncState(nowMillis)
            } else {
                clear()
            }
        } else {
            currentRawCode = nextRaw
            syncState(nowMillis)
        }
        return EngineResult.Consumed
    }

    private fun onSpace(): EngineResult {
        if (currentRawCode.isEmpty() && t9PendingCommittedPrefix.isEmpty()) return EngineResult.PassThrough
        if (currentRawCode == "/") {
            clear()
            lastCommittedText = "/"
            return EngineResult.Commit("/", remainComposing = false)
        }
        val cands = _state.value.candidates
        return if (cands.isNotEmpty()) {
            select(0)
        } else if (t9PendingCommittedPrefix.isNotEmpty()) {
            val text = flush()
            EngineResult.Commit(text, remainComposing = false)
        } else {
            clear()
            EngineResult.Consumed
        }
    }

    private fun onEnter(): EngineResult {
        if (currentRawCode.isEmpty() && t9PendingCommittedPrefix.isEmpty()) return EngineResult.PassThrough
        if (isT9Mode) {
            val text = flush()
            return if (text.isNotEmpty()) {
                EngineResult.Commit(text, remainComposing = false)
            } else {
                EngineResult.Consumed
            }
        }
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

        val firstText = flush()
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
        if (currentRawCode.isEmpty() && t9PendingCommittedPrefix.isEmpty()) {
            _state.value = EngineState()
            return
        }
        if (isT9Mode) {
            val rimeCtx = if (currentRawCode.isNotEmpty()) {
                rimeBridge.getContext(currentMaxCandidates)
            } else {
                com.typefree.rime.RimeContextSnapshot()
            }
            val topComment = rimeCtx.candidates.firstOrNull()?.comment.orEmpty()
            val engineCands = if (rimeCtx.candidates.isNotEmpty()) {
                rimeCtx.candidates.map { rc ->
                    // Keep T9 top candidate chips crisp (spelling hints are rendered in the floating preedit capsule)
                    FlypyCandidate(text = rc.text, codeHint = "")
                }
            } else if (t9PendingCommittedPrefix.isNotEmpty()) {
                listOf(FlypyCandidate(text = t9PendingCommittedPrefix, codeHint = ""))
            } else {
                emptyList()
            }
            val preeditStr = T9PinyinHelper.formatT9Preedit(
                rimePreedit = rimeCtx.preedit.ifEmpty { currentRawCode },
                topCandidateComment = topComment,
                pendingCommittedPrefix = t9PendingCommittedPrefix
            ).ifEmpty { currentRawCode }

            // Derive left-rail Pinyin syllable filter options from the active unlocked digit tail
            val (inCompPrefix, tailCodeRaw) = T9PinyinHelper.splitRimePreedit(
                rimeCtx.preedit.ifEmpty { currentRawCode }
            )
            val tailCode = tailCodeRaw.replace(" ", "")
            val firstDigitIdx = tailCode.indexOfFirst { it in '2'..'9' }
            val unlockedDigits = if (firstDigitIdx >= 0) {
                tailCode.substring(firstDigitIdx).filter { it in '2'..'9' }
            } else {
                ""
            }
            val lockedPrefixCount = if (inCompPrefix.isEmpty() && firstDigitIdx > 0) {
                tailCode.substring(0, firstDigitIdx)
                    .split('\'')
                    .count { part -> part.any { it in 'a'..'z' } }
            } else {
                0
            }
            val commentSyllables = topComment.trim().split(' ').filter { it.isNotBlank() }
            val preferredSyl = commentSyllables.getOrNull(lockedPrefixCount)
            val syllables = if (unlockedDigits.isNotEmpty()) {
                T9PinyinHelper.matchLeadingSyllables(
                    unlockedDigits = unlockedDigits,
                    preferredLeadingSyllable = preferredSyl
                )
            } else {
                emptyList()
            }

            _state.value = EngineState(
                preedit = preeditStr,
                rawCode = currentRawCode.ifEmpty { t9PendingCommittedPrefix },
                candidates = engineCands,
                hasMore = rimeCtx.hasMore,
                isComposing = true,
                t9Syllables = syllables
            )
            return
        }
        if (currentRawCode.startsWith(";") || currentRawCode.startsWith("/")) {
            val merged = CandidateMerger.mergeCandidates(
                rawCode = currentRawCode,
                engineCandidates = emptyList(),
                normalizedSnippets = normalizedSnippets,
                lastCommittedText = lastCommittedText,
                nowMillis = nowMillis,
                includeBuiltInQuickSymbols = currentRawCode.startsWith(";")
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
