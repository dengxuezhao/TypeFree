package com.typefree.core.ime.enhancer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.BitSet

/**
 * Callback interface used during Track 5.2 (`ImportMode.PRECOMPILED_BIN`) to probe the 702
 * 1-brief (`a..z`) and 2-brief (`aa..zz`) slots directly from `librime` in-memory `*.table.bin`
 * without reverse-engineering or decompiling proprietary tables.
 */
fun interface RimeCodeProber {
    fun probeCandidates(code: String, maxCount: Int): List<String>

    fun probeBatch(codes: List<String>, maxCount: Int): List<List<String>> =
        codes.map { probeCandidates(it, maxCount) }
}

/**
 * Ultra-lightweight (< 96KB) immutable in-memory index for empty-code 2+(1~2) reverse splitting:
 * - [brief1]: 26 single-letter brief slots (`a..z`, Xiaohe Flypy = 52 entries)
 * - [brief2]: 676 two-letter brief slots (`aa..zz`, Xiaohe Flypy = 898 entries)
 * - [has4CodeFor3Prefix]: 17,576-bit `BitSet` (~2.2KB) marking 3-letter prefixes that still have >=4-code continuations
 * - [gt4PrefixSet]: open-addressed primitive `Int` set (< 8KB) storing packed 4-letter prefixes of >=5-code entries
 *   (e.g. Xiaohe's 93,565 `ok*` / `os*` entries collapse into ~1,016 unique 4-code prefixes).
 */
class BriefPrefixIndex(
    private val brief1: Array<List<String>>,
    private val brief2: Array<List<String>>,
    private val has4CodeFor3Prefix: BitSet,
    private val gt4PackedTable: IntArray,
    private val gt4GuardTwoPrefixes: Set<String> = emptySet()
) {
    private val hasExactGt4Table: Boolean = gt4PackedTable.any { it != EMPTY_SLOT }

    init {
        require(brief1.size == 26) { "brief1 must have length 26" }
        require(brief2.size == 676) { "brief2 must have length 676" }
    }

    val totalBrief1Entries: Int
        get() = brief1.sumOf { it.size }

    val totalBrief2Entries: Int
        get() = brief2.sumOf { it.size }

    val populatedBrief2Slots: Int
        get() = brief2.count { it.isNotEmpty() }

    /**
     * Fraction of 676 `aa..zz` slots whose top-1 candidate has `>= 2` Unicode code points.
     */
    val twoBriefMultiCharTop1Ratio: Double
        get() {
            var multi = 0
            for (slot in brief2) {
                val first = slot.firstOrNull() ?: continue
                if (first.codePointCount(0, first.length) >= 2) {
                    multi++
                }
            }
            return multi.toDouble() / 676.0
        }

    fun lookup1(c0: Char): List<String> {
        if (c0 !in 'a'..'z') return emptyList()
        return brief1[c0 - 'a']
    }

    fun lookup2(c0: Char, c1: Char): List<String> {
        if (c0 !in 'a'..'z' || c1 !in 'a'..'z') return emptyList()
        return brief2[(c0 - 'a') * 26 + (c1 - 'a')]
    }

    fun hasLongerFrom3(code3: String): Boolean {
        if (code3.length != 3) return false
        val c0 = code3[0]
        val c1 = code3[1]
        val c2 = code3[2]
        if (c0 !in 'a'..'z' || c1 !in 'a'..'z' || c2 !in 'a'..'z') return false
        if (gt4GuardTwoPrefixes.contains(code3.substring(0, 2))) return true
        val bitIdx = ((c0 - 'a') * 26 + (c1 - 'a')) * 26 + (c2 - 'a')
        return has4CodeFor3Prefix.get(bitIdx)
    }

    fun hasGt4Prefix(code4: String): Boolean {
        if (code4.length != 4) return false
        if ( code4[0] !in 'a'..'z' || code4[1] !in 'a'..'z' ||
            code4[2] !in 'a'..'z' || code4[3] !in 'a'..'z'
        ) {
            return false
        }
        val packed = pack4(code4[0], code4[1], code4[2], code4[3])
        if (containsPacked(gt4PackedTable, packed)) return true
        return if (hasExactGt4Table) {
            false
        } else {
            gt4GuardTwoPrefixes.contains(code4.substring(0, 2))
        }
    }

    /**
     * Writes compact binary snapshot (`<schema>.brief.idx`, ~30–40KB) for `< 2ms` cold-start reload.
     */
    fun writeSnapshot(targetFile: File) {
        targetFile.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(FileOutputStream(targetFile), 16 * 1024)).use { out ->
            out.writeInt(SNAPSHOT_MAGIC)
            out.writeInt(SNAPSHOT_VERSION)

            for (i in 0 until 26) {
                val list = brief1[i]
                out.writeShort(list.size)
                for (w in list) out.writeUTF(w)
            }
            for (i in 0 until 676) {
                val list = brief2[i]
                out.writeShort(list.size)
                for (w in list) out.writeUTF(w)
            }

            val bitBytes = has4CodeFor3Prefix.toByteArray()
            out.writeInt(bitBytes.size)
            out.write(bitBytes)

            val nonEmptyPacked = gt4PackedTable.filter { it != EMPTY_SLOT }
            out.writeInt(nonEmptyPacked.size)
            for (v in nonEmptyPacked) out.writeInt(v)

            out.writeShort(gt4GuardTwoPrefixes.size)
            for (p in gt4GuardTwoPrefixes) out.writeUTF(p)
        }
    }

    companion object {
        private const val SNAPSHOT_MAGIC = 0x54464249 // "TFBI"
        private const val SNAPSHOT_VERSION = 1
        private const val EMPTY_SLOT = -1

        fun empty(): BriefPrefixIndex = BriefPrefixIndex(
            brief1 = Array(26) { emptyList() },
            brief2 = Array(676) { emptyList() },
            has4CodeFor3Prefix = BitSet(17_576),
            gt4PackedTable = IntArray(16) { EMPTY_SLOT },
            gt4GuardTwoPrefixes = emptySet()
        )

        fun readSnapshot(file: File): BriefPrefixIndex {
            DataInputStream(BufferedInputStream(FileInputStream(file), 16 * 1024)).use { input ->
                val magic = input.readInt()
                val version = input.readInt()
                require(magic == SNAPSHOT_MAGIC && version == SNAPSHOT_VERSION) {
                    "Invalid BriefPrefixIndex snapshot header in ${file.name}"
                }
                val b1 = Array<List<String>>(26) {
                    val count = input.readUnsignedShort()
                    if (count == 0) emptyList() else List(count) { input.readUTF() }
                }
                val b2 = Array<List<String>>(676) {
                    val count = input.readUnsignedShort()
                    if (count == 0) emptyList() else List(count) { input.readUTF() }
                }
                val bitLen = input.readInt()
                val bitBytes = ByteArray(bitLen)
                input.readFully(bitBytes)
                val bits = BitSet.valueOf(bitBytes)

                val gt4Count = input.readInt()
                val rawPacked = IntArray(gt4Count) { input.readInt() }
                val hashTable = buildOpenHashSet(rawPacked.asList())

                val guardCount = input.readUnsignedShort()
                val guards = LinkedHashSet<String>(guardCount)
                repeat(guardCount) { guards.add(input.readUTF()) }

                return BriefPrefixIndex(
                    brief1 = b1,
                    brief2 = b2,
                    has4CodeFor3Prefix = bits,
                    gt4PackedTable = hashTable,
                    gt4GuardTwoPrefixes = guards
                )
            }
        }

        /**
         * Single-pass builder over plain-text `.dict.yaml` plus optional `flypy_top.txt` (pinned front)
         * and `flypy_user.txt` (appended back).
         */
        fun buildFromDictLines(
            mainDictLines: Sequence<String>,
            topDictLines: Sequence<String> = emptySequence(),
            userDictLines: Sequence<String> = emptySequence(),
            blockedWords: Set<String> = emptySet(),
            gt4GuardTwoPrefixes: Set<String> = emptySet()
        ): BriefPrefixIndex {
            val b1 = Array(26) { ArrayList<String>(3) }
            val b2 = Array(676) { ArrayList<String>(2) }
            val has4Bits = BitSet(17_576)
            val gt4Raw = LinkedHashSet<Int>(1536)

            fun addBriefCandidate(code: String, word: String, prepend: Boolean = false) {
                if (word.isEmpty() || word in blockedWords || word.contains('$') || word.contains('{')) return
                if (code.length == 1 && code[0] in 'a'..'z') {
                    val bucket = b1[code[0] - 'a']
                    if (word !in bucket) {
                        if (prepend) bucket.add(0, word) else bucket.add(word)
                    }
                } else if (code.length == 2 && code[0] in 'a'..'z' && code[1] in 'a'..'z') {
                    val idx = (code[0] - 'a') * 26 + (code[1] - 'a')
                    val bucket = b2[idx]
                    if (word !in bucket) {
                        if (prepend) bucket.add(0, word) else bucket.add(word)
                    }
                }
            }

            // 1. Pinned user top phrases (`flypy_top.txt`: `word \t code`)
            parseCustomPhraseLines(topDictLines) { word, code ->
                addBriefCandidate(code, word, prepend = true)
            }

            // 2. Main `.dict.yaml`
            var inYamlHeader = false
            for (rawLine in mainDictLines) {
                val trimmed = rawLine.trim()
                if (trimmed == "---") {
                    inYamlHeader = true
                    continue
                }
                if (inYamlHeader) {
                    if (trimmed == "...") inYamlHeader = false
                    continue
                }
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val tab1 = rawLine.indexOf('\t')
                if (tab1 <= 0 || tab1 >= rawLine.length - 1) continue
                val word = rawLine.substring(0, tab1).trim()
                val rest = rawLine.substring(tab1 + 1)
                val tab2 = rest.indexOf('\t')
                val code = (if (tab2 >= 0) rest.substring(0, tab2) else rest).trim()

                if (code.isEmpty()) continue
                if (code.length <= 2) {
                    addBriefCandidate(code, word, prepend = false)
                }
                if (code.length >= 4 &&
                    code[0] in 'a'..'z' && code[1] in 'a'..'z' && code[2] in 'a'..'z'
                ) {
                    val bitIdx = ((code[0] - 'a') * 26 + (code[1] - 'a')) * 26 + (code[2] - 'a')
                    has4Bits.set(bitIdx)
                }
                if (code.length >= 5 &&
                    code[0] in 'a'..'z' && code[1] in 'a'..'z' &&
                    code[2] in 'a'..'z' && code[3] in 'a'..'z'
                ) {
                    gt4Raw.add(pack4(code[0], code[1], code[2], code[3]))
                }
            }

            // 3. Trailing personal phrases (`flypy_user.txt`: `word \t code`)
            parseCustomPhraseLines(userDictLines) { word, code ->
                addBriefCandidate(code, word, prepend = false)
            }

            return BriefPrefixIndex(
                brief1 = Array(26) { b1[it].toList() },
                brief2 = Array(676) { b2[it].toList() },
                has4CodeFor3Prefix = has4Bits,
                gt4PackedTable = buildOpenHashSet(gt4Raw),
                gt4GuardTwoPrefixes = gt4GuardTwoPrefixes.filter {
                    it.length == 2 && it[0] in 'a'..'z' && it[1] in 'a'..'z'
                }.toSet()
            )
        }

        /**
         * Track 5.2 zero-decompile dynamic builder: probes exactly 26 (`a..z`) + 676 (`aa..zz`) = 702
         * slots through `librime` in-memory lookup (< 15ms) and injects `ok*`/`os*` affix guards.
         */
        fun buildFrom702SlotProbing(
            prober: RimeCodeProber,
            topDictLines: Sequence<String> = emptySequence(),
            userDictLines: Sequence<String> = emptySequence(),
            affixGuardPrefixes: Set<String> = setOf("ok", "os"),
            threeCodeDeadEnds: Set<String>? = null
        ): BriefPrefixIndex {
            val b1 = Array(26) { ArrayList<String>(3) }
            val b2 = Array(676) { ArrayList<String>(3) }

            parseCustomPhraseLines(topDictLines) { word, code ->
                if (code.length == 1 && code[0] in 'a'..'z' && word !in b1[code[0] - 'a']) {
                    b1[code[0] - 'a'].add(word)
                } else if (code.length == 2 && code[0] in 'a'..'z' && code[1] in 'a'..'z') {
                    val idx = (code[0] - 'a') * 26 + (code[1] - 'a')
                    if (word !in b2[idx]) b2[idx].add(word)
                }
            }

            // Single-batch 702-slot probe (`26` 1-brief + `676` 2-brief)
            val allCodes = ArrayList<String>(702)
            for (i in 0 until 26) {
                allCodes.add(('a' + i).toString())
            }
            for (i in 0 until 26) {
                val c0 = ('a' + i)
                for (j in 0 until 26) {
                    val c1 = ('a' + j)
                    allCodes.add("$c0$c1")
                }
            }
            val probedSlots = prober.probeBatch(allCodes, 6)
            for (i in 0 until 26) {
                val cands = probedSlots.getOrNull(i).orEmpty()
                for (w in cands) {
                    if (w.isNotEmpty() && !w.contains('$') && !w.contains('{') && w !in b1[i]) {
                        b1[i].add(w)
                    }
                }
            }
            for (slotIdx in 0 until 676) {
                val cands = probedSlots.getOrNull(26 + slotIdx).orEmpty()
                for (w in cands) {
                    if (w.isNotEmpty() && !w.contains('$') && !w.contains('{') && w !in b2[slotIdx]) {
                        b2[slotIdx].add(w)
                    }
                }
            }

            parseCustomPhraseLines(userDictLines) { word, code ->
                if (code.length == 1 && code[0] in 'a'..'z' && word !in b1[code[0] - 'a']) {
                    b1[code[0] - 'a'].add(word)
                } else if (code.length == 2 && code[0] in 'a'..'z' && code[1] in 'a'..'z') {
                    val idx = (code[0] - 'a') * 26 + (code[1] - 'a')
                    if (word !in b2[idx]) b2[idx].add(word)
                }
            }

            val has4Bits = BitSet(17_576)
            // Conservative guard in Track 5.2 precompiled probing: unless explicitly marked as dead-end,
            // assume 3-code prefixes still have 4-code continuations so we don't prematurely 2+1 split at key 3.
            has4Bits.set(0, 17_576)
            for (dead in threeCodeDeadEnds.orEmpty()) {
                if (dead.length == 3 && dead.all { it in 'a'..'z' }) {
                    val idx = ((dead[0] - 'a') * 26 + (dead[1] - 'a')) * 26 + (dead[2] - 'a')
                    has4Bits.clear(idx)
                }
            }

            val cleanGuards = affixGuardPrefixes
                .filter { it.length == 2 && it[0] in 'a'..'z' && it[1] in 'a'..'z' }
                .toSet()

            return BriefPrefixIndex(
                brief1 = Array(26) { b1[it].toList() },
                brief2 = Array(676) { b2[it].toList() },
                has4CodeFor3Prefix = has4Bits,
                gt4PackedTable = IntArray(16) { EMPTY_SLOT },
                gt4GuardTwoPrefixes = cleanGuards
            )
        }

        private fun parseCustomPhraseLines(
            lines: Sequence<String>,
            consumer: (word: String, code: String) -> Unit
        ) {
            for (raw in lines) {
                val trimmed = raw.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val tab1 = raw.indexOf('\t')
                if (tab1 <= 0 || tab1 >= raw.length - 1) continue
                val word = raw.substring(0, tab1).trim()
                val rest = raw.substring(tab1 + 1)
                val tab2 = rest.indexOf('\t')
                val code = (if (tab2 >= 0) rest.substring(0, tab2) else rest).trim()
                if (word.isNotEmpty() && code.isNotEmpty()) {
                    consumer(word, code)
                }
            }
        }

        internal fun pack4(c0: Char, c1: Char, c2: Char, c3: Char): Int {
            return ((c0 - 'a') shl 15) or
                ((c1 - 'a') shl 10) or
                ((c2 - 'a') shl 5) or
                (c3 - 'a')
        }

        private fun buildOpenHashSet(values: Collection<Int>): IntArray {
            var cap = 16
            while (cap < values.size * 2) cap = cap shl 1
            val table = IntArray(cap) { EMPTY_SLOT }
            val mask = cap - 1
            for (v in values) {
                if (v == EMPTY_SLOT) continue
                var slot = mixHash(v) and mask
                while (table[slot] != EMPTY_SLOT && table[slot] != v) {
                    slot = (slot + 1) and mask
                }
                table[slot] = v
            }
            return table
        }

        private fun containsPacked(table: IntArray, value: Int): Boolean {
            if (table.isEmpty() || value == EMPTY_SLOT) return false
            val mask = table.size - 1
            var slot = mixHash(value) and mask
            while (true) {
                val cur = table[slot]
                if (cur == EMPTY_SLOT) return false
                if (cur == value) return true
                slot = (slot + 1) and mask
            }
        }

        private fun mixHash(x: Int): Int {
            var h = x * -1640531527
            h = h xor (h ushr 16)
            return h
        }
    }
}
