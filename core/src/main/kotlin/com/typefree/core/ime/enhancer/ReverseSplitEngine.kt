package com.typefree.core.ime.enhancer

import com.typefree.core.ime.profile.SchemaProfile

data class VirtualCandidate(
    val commitText: String,
    val displayText: String,
    val preedit: String
)

data class SplitResult(
    val preedit: String,
    val candidates: List<VirtualCandidate>
)

/**
 * Pure in-memory empty-code `2+(1~2)` reverse-split engine aligned with WindInput `split_input`
 * and Xiaohe Flypy author rules, guarded by 6 mandatory trigger gates.
 */
class ReverseSplitEngine(
    var index: BriefPrefixIndex
) {
    fun tryReverseSplit(
        code: String,
        rimeHasCandidates: Boolean,
        profile: SchemaProfile,
        singleCharMode: Boolean = false,
        directCmdMatched: Boolean = false
    ): SplitResult? {
        // Gate 1: Schema toggle & single-character mode mutual exclusion guard
        if (!profile.reverseSplitEnabled || singleCharMode) return null

        // Gate 2: Only process 3-letter or 4-letter pure lowercase codes
        if (code.length != 3 && code.length != 4) return null
        if (!code.all { it in 'a'..'z' }) return null

        // Gate 3 & 4: Never split when exact Rime candidates exist or a direct command matched
        if (rimeHasCandidates || directCmdMatched) return null

        // Gate 5a: Do not split a 3-letter code prematurely if >=4-letter continuations exist
        if (code.length == 3 && index.hasLongerFrom3(code)) return null

        // Gate 5b: >4-code prefix guard (protects all 93,565 ok*/os* entries such as okhguu -> 丁)
        if (code.length == 4 && index.hasGt4Prefix(code)) return null

        // Gate 6: Lookup front 2-brief and back 1/2-brief; abort if either side has 0 entries
        val frontList = index.lookup2(code[0], code[1])
        val backList = if (code.length == 3) {
            index.lookup1(code[2])
        } else {
            index.lookup2(code[2], code[3])
        }
        if (frontList.isEmpty() || backList.isEmpty()) return null

        val frontTop = frontList.first()
        val segmentedPreedit = "${code.substring(0, 2)}'${code.substring(2)}"
        val candidates = backList.mapIndexed { idx, backWord ->
            VirtualCandidate(
                commitText = frontTop + backWord,
                displayText = if (idx == 0 || !profile.reverseSplitSuffixOnly) {
                    frontTop + backWord
                } else {
                    backWord
                },
                preedit = segmentedPreedit
            )
        }
        return SplitResult(preedit = segmentedPreedit, candidates = candidates)
    }
}
