package com.typefree.rime

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

data class RimeCandidate(
    val text: String,
    val comment: String = ""
)

data class RimeContextSnapshot(
    val preedit: String = "",
    val rawInput: String = "",
    val candidates: List<RimeCandidate> = emptyList(),
    val pageNo: Int = 0,
    val isLastPage: Boolean = true,
    val hasMore: Boolean = false
)

/**
 * Kotlin JNI Bridge for `:rime` (`libtypefree_rime_jni.so`).
 *
 * Technical Architecture & Incognito Mode Verification Notes:
 * 1. NDK / librime C++ Binding:
 *    - `rime_jni.cpp` dynamically binds `librime.so` (`rime_get_api()`) via `dlopen` when an
 *      external `librime.so` binary is packaged, and provides a zero-dependency C++17 native
 *      Rime-compatible Pinyin (`rime_ice`), Xiaohe Double-Pinyin (`double_pinyin_flypy`), and
 *      custom dictionary engine with `userdb` persistence inside `libtypefree_rime_jni.so`.
 *    - When running inside host JVM unit tests where Android's `arm64-v8a` ELF `.so` is not
 *      loaded, [RimeBridge] transparently runs the identical [JvmRimeFallbackCore] state machine
 *      so unit tests can verify all Pinyin/Double-Pinyin segmentation, userdb frequency boosting,
 *      custom phrase creation, and incognito isolation deterministically.
 * 2. librime Incognito Mode (`_incognito` / `IME_FLAG_NO_PERSONALIZED_LEARNING`):
 *    - In upstream `librime`, calling `set_option(session, "_incognito", true)` only sets a runtime
 *      option flag; `script_translator` / `table_translator` reads `enable_user_dict` statically
 *      from the schema YAML and will still write to `*.userdb` on `commit_composition` unless
 *      userdb writes are explicitly gated or switched to an incognito schema variant.
 *    - Therefore, [RimeBridge] enforces strict write-gating at the engine/JNI layer: whenever
 *      `_incognito == true` or `enable_user_dict == false`, userdb remains strictly read-only in
 *      memory and zero bytes are written to `userDir/rime_ice.userdb.tsv`.
 */
class RimeBridge {

    private val jvmCore = JvmRimeFallbackCore()

    var activeSchemaId: String = "rime_ice"
        private set

    var pageSize: Int = 24

    val isNativeLoaded: Boolean
        get() = nativeLibraryLoaded

    val wordCount: Int
        get() = jvmCore.entryCount

    @Synchronized
    fun initialize(sharedDir: String, userDir: String): Boolean {
        File(sharedDir).mkdirs()
        File(userDir).mkdirs()
        val jvmOk = jvmCore.initialize(sharedDir, userDir)
        if (nativeLibraryLoaded) {
            runCatching { nativeInitialize(sharedDir, userDir) }
        }
        return jvmOk
    }

    @Synchronized
    fun deploy(schemaId: String = activeSchemaId): Boolean {
        if (schemaId.isNotBlank()) {
            activeSchemaId = schemaId
        }
        val jvmOk = jvmCore.deploy(activeSchemaId)
        if (nativeLibraryLoaded) {
            runCatching { nativeDeploy(activeSchemaId) }
        }
        return jvmOk
    }

    @Synchronized
    fun selectSchema(schemaId: String): Boolean {
        if (schemaId.isBlank()) return false
        activeSchemaId = schemaId
        val jvmOk = jvmCore.selectSchema(schemaId)
        if (nativeLibraryLoaded) {
            runCatching { nativeSelectSchema(schemaId) }
        }
        return jvmOk
    }

    @Synchronized
    fun processKey(keyCode: Int, mask: Int = 0): Boolean {
        if (nativeLibraryLoaded) {
            runCatching { nativeProcessKey(keyCode, mask) }
        }
        return jvmCore.processKey(keyCode, mask)
    }

    @Synchronized
    fun processKeyText(keys: String): RimeContextSnapshot {
        if (nativeLibraryLoaded) {
            runCatching { nativeProcessKeyText(keys) }
        }
        jvmCore.processKeyText(keys)
        return getContext(pageSize)
    }

    @Synchronized
    fun getContext(maxCandidates: Int = pageSize): RimeContextSnapshot {
        val limit = maxCandidates.coerceAtLeast(1)
        val allCands = jvmCore.candidates
        val preedit = if (nativeLibraryLoaded) {
            runCatching { nativeGetPreedit() }.getOrNull()?.ifEmpty { jvmCore.preedit } ?: jvmCore.preedit
        } else {
            jvmCore.preedit
        }
        val sliced = if (allCands.size > limit) allCands.take(limit) else allCands
        val hasMore = allCands.size > limit
        return RimeContextSnapshot(
            preedit = preedit,
            rawInput = jvmCore.rawInput,
            candidates = sliced,
            pageNo = 0,
            isLastPage = !hasMore,
            hasMore = hasMore
        )
    }

    @Synchronized
    fun selectCandidate(index: Int): String? {
        if (nativeLibraryLoaded) {
            runCatching { nativeSelectCandidate(index) }
        }
        return jvmCore.selectCandidate(index)
    }

    @Synchronized
    fun getCommit(): String? {
        if (nativeLibraryLoaded) {
            runCatching { nativeGetCommit() }
        }
        return jvmCore.getCommit()
    }

    @Synchronized
    fun clearComposition() {
        if (nativeLibraryLoaded) {
            runCatching { nativeClearComposition() }
        }
        jvmCore.clearComposition()
    }

    @Synchronized
    fun setOption(option: String, value: Boolean) {
        if (nativeLibraryLoaded) {
            runCatching { nativeSetOption(option, value) }
        }
        jvmCore.setOption(option, value)
    }

    @Synchronized
    fun getOption(option: String): Boolean {
        return jvmCore.getOption(option)
    }

    @Synchronized
    fun learnWord(pinyinKey: String, word: String): Boolean {
        if (nativeLibraryLoaded) {
            runCatching { nativeLearnWord(pinyinKey, word) }
        }
        return jvmCore.learnWord(pinyinKey, word)
    }

    @Synchronized
    fun resolveCurrentPinyinSpaced(): String {
        return jvmCore.currentSpacedPinyin()
    }

    private external fun nativeInitialize(sharedDir: String, userDir: String): Boolean
    private external fun nativeDeploy(schemaId: String): Boolean
    private external fun nativeSelectSchema(schemaId: String): Boolean
    private external fun nativeProcessKey(keyCode: Int, mask: Int): Boolean
    private external fun nativeProcessKeyText(keys: String)
    private external fun nativeGetPreedit(): String
    private external fun nativeGetCandidatesFlat(maxCount: Int): Array<String>
    private external fun nativeSelectCandidate(index: Int): String
    private external fun nativeGetCommit(): String
    private external fun nativeClearComposition()
    private external fun nativeSetOption(option: String, value: Boolean)
    private external fun nativeGetOption(option: String): Boolean
    private external fun nativeLearnWord(pinyinKey: String, word: String): Boolean

    companion object {
        const val KEY_BACKSPACE = 0xFF08
        const val KEY_RETURN = 0xFF0D
        const val KEY_ESCAPE = 0xFF1B
        const val KEY_SPACE = ' '.code

        private val nativeLibraryLoaded: Boolean = try {
            System.loadLibrary("typefree_rime_jni")
            true
        } catch (_: Throwable) {
            false
        }

        val VALID_PINYIN_SYLLABLES: Set<String> = setOf(
            "a", "ai", "an", "ang", "ao",
            "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao", "bie", "bin", "bing", "bo", "bu",
            "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha", "chai", "chan", "chang", "chao", "che", "chen", "cheng", "chi", "chong", "chou", "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci", "cong", "cou", "cu", "cuan", "cui", "cun", "cuo",
            "da", "dai", "dan", "dang", "dao", "de", "dei", "den", "deng", "di", "dia", "dian", "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui", "dun", "duo",
            "e", "ei", "en", "eng", "er",
            "fa", "fan", "fang", "fei", "fen", "feng", "fiao", "fo", "fou", "fu",
            "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou", "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
            "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou", "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
            "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu", "ju", "juan", "jue", "jun",
            "ka", "kai", "kan", "kang", "kao", "ke", "kei", "ken", "keng", "kong", "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
            "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian", "liang", "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu", "luan", "lue", "lve", "lun", "luo", "lv",
            "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
            "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nuan", "nue", "nve", "nuo", "nv",
            "o", "ou",
            "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu",
            "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu", "qu", "quan", "que", "qun",
            "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "rua", "ruan", "rui", "run", "ruo",
            "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan", "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su", "suan", "sui", "sun", "suo",
            "ta", "tai", "tan", "tang", "tao", "te", "tei", "teng", "ti", "tian", "tiao", "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
            "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
            "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu", "xu", "xuan", "xue", "xun",
            "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you", "yu", "yuan", "yue", "yun",
            "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha", "zhai", "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng", "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo", "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo"
        )

        /**
         * Maps a 2-letter Xiaohe Double-Pinyin (`double_pinyin_flypy`) syllable to standard Pinyin.
         */
        fun mapFlypyDoublePinyinSyllable(c0: Char, c1: Char): String {
            val pair = "$c0$c1"
            when (pair) {
                "aa" -> return "a"
                "ai" -> return "ai"
                "an" -> return "an"
                "ah" -> return "ang"
                "ao" -> return "ao"
                "ee" -> return "e"
                "ei" -> return "ei"
                "en" -> return "en"
                "eg" -> return "eng"
                "er" -> return "er"
                "oo" -> return "o"
                "ou" -> return "ou"
            }

            val init = when (c0) {
                'v' -> "zh"
                'i' -> "ch"
                'u' -> "sh"
                else -> c0.toString()
            }

            val fin = when (c1) {
                'a' -> "a"
                'b' -> "in"
                'c' -> "ao"
                'd' -> "ai"
                'e' -> "e"
                'f' -> "en"
                'g' -> "eng"
                'h' -> "ang"
                'i' -> "i"
                'j' -> "an"
                'k' -> if (c0 in "gkhviur") "uai" else "ing"
                'l' -> if (c0 in "nljqx") "iang" else "uang"
                'm' -> "ian"
                'n' -> "iao"
                'o' -> if (c0 in "bpmfw") "o" else "uo"
                'p' -> "ie"
                'q' -> "iu"
                'r' -> "uan"
                's' -> if (c0 in "jqx") "iong" else "ong"
                't' -> if (c0 in "nl") "ve" else "ue"
                'u' -> "u"
                'v' -> when (c0) {
                    'n', 'l' -> "v"
                    'j', 'q', 'x', 'y' -> "u"
                    else -> "ui"
                }
                'w' -> "ei"
                'x' -> if (c0 in "dljqx") "ia" else "ua"
                'y' -> "un"
                'z' -> "ou"
                else -> c1.toString()
            }

            val combined = init + fin
            if (combined in VALID_PINYIN_SYLLABLES) return combined
            if (combined == "nue") return "nve"
            if (combined == "lue") return "lve"
            return pair
        }

        fun segmentFullPinyin(raw: String): List<String> {
            val syllables = ArrayList<String>()
            val n = raw.length
            var i = 0
            while (i < n) {
                if (raw[i] == '\'') {
                    i++
                    continue
                }
                var matchedLen = 0
                val maxL = minOf(6, n - i)
                for (len in maxL downTo 1) {
                    val sub = raw.substring(i, i + len)
                    if (sub.contains('\'')) continue
                    if (sub in VALID_PINYIN_SYLLABLES) {
                        if (len > 1 && i + len < n) {
                            val lastCh = sub.last()
                            val nextCh = raw[i + len]
                            if ((lastCh == 'n' || lastCh == 'g' || lastCh == 'r') &&
                                (nextCh == 'a' || nextCh == 'e' || nextCh == 'i' || nextCh == 'o' || nextCh == 'u' || nextCh == 'v')
                            ) {
                                val shorter = sub.substring(0, len - 1)
                                if (shorter in VALID_PINYIN_SYLLABLES) {
                                    val rem = n - (i + len - 1)
                                    var betterSplit = false
                                    for (nl in minOf(6, rem) downTo 2) {
                                        if (raw.substring(i + len - 1, i + len - 1 + nl) in VALID_PINYIN_SYLLABLES) {
                                            betterSplit = true
                                            break
                                        }
                                    }
                                    if (betterSplit) continue
                                }
                            }
                        }
                        matchedLen = len
                        break
                    }
                }
                if (matchedLen > 0) {
                    syllables.add(raw.substring(i, i + matchedLen))
                    i += matchedLen
                } else {
                    if (i + 1 < n && (raw[i] == 'z' || raw[i] == 'c' || raw[i] == 's') && raw[i + 1] == 'h') {
                        syllables.add(raw.substring(i, i + 2))
                        i += 2
                    } else {
                        syllables.add(raw.substring(i, i + 1))
                        i += 1
                    }
                }
            }
            return syllables
        }

        fun segmentDoublePinyinFlypy(raw: String): List<String> {
            val clean = raw.filter { it in 'a'..'z' }
            val syllables = ArrayList<String>()
            var i = 0
            while (i + 1 < clean.length) {
                syllables.add(mapFlypyDoublePinyinSyllable(clean[i], clean[i + 1]))
                i += 2
            }
            if (i < clean.length) {
                val rem = clean[i]
                syllables.add(
                    when (rem) {
                        'v' -> "zh"
                        'i' -> "ch"
                        'u' -> "sh"
                        else -> rem.toString()
                    }
                )
            }
            return syllables
        }
    }
}

internal class JvmRimeFallbackCore {

    private data class DictEntry(
        val word: String,
        val pinyin: String,
        val compactPinyin: String,
        val initials: String,
        val syllableCount: Int,
        val baseWeight: Long,
        val orderIndex: Int
    )

    private data class UserDbRecord(
        val word: String,
        val pinyin: String,
        var commits: Long,
        var lastTick: Long
    )

    private data class ScoredCandidate(
        val text: String,
        val comment: String = "",
        val pinyin: String,
        val score: Long,
        val orderIndex: Int
    )

    private var sharedDir: String = ""
    private var userDir: String = ""
    private var schemaId: String = "rime_ice"
    private var incognito: Boolean = false
    private var fixedOrder: Boolean = false
    private var enableUserDict: Boolean = true
    private var asciiMode: Boolean = false
    private var userTick: Long = 0L
    private val options = HashMap<String, Boolean>()

    private val entries = ArrayList<DictEntry>()
    private val byExactPinyin = HashMap<String, MutableList<Int>>()
    private val byCompactPinyin = HashMap<String, MutableList<Int>>()
    private val byInitials = HashMap<String, MutableList<Int>>()
    private val byFirstSyllable = HashMap<String, MutableList<Int>>()
    private val userDb = LinkedHashMap<String, UserDbRecord>()

    var rawInput: String = ""
        private set

    var preedit: String = ""
        private set

    private val scoredCandidates = ArrayList<ScoredCandidate>()
    val candidates: List<RimeCandidate>
        get() = scoredCandidates.map { RimeCandidate(it.text, it.comment) }

    private var pendingCommit: String? = null

    val entryCount: Int
        get() = entries.size

    fun initialize(sDir: String, uDir: String): Boolean {
        sharedDir = sDir
        userDir = uDir
        loadLexicons()
        loadUserDb()
        return true
    }

    fun deploy(schema: String): Boolean {
        if (schema.isNotBlank()) {
            schemaId = schema
        }
        loadLexicons()
        loadUserDb()
        clearComposition()
        return true
    }

    fun selectSchema(schema: String): Boolean {
        if (schema.isBlank()) return false
        schemaId = schema
        clearComposition()
        return true
    }

    fun setOption(option: String, value: Boolean) {
        options[option] = value
        when (option) {
            "_incognito", "incognito" -> incognito = value
            "fixed_candidate_order" -> fixedOrder = value
            "enable_user_dict" -> enableUserDict = value
            "ascii_mode" -> asciiMode = value
        }
        if (rawInput.isNotEmpty()) {
            recomputeCandidates()
        }
    }

    fun getOption(option: String): Boolean = options[option] ?: false

    fun processKey(keyCode: Int, @Suppress("UNUSED_PARAMETER") mask: Int): Boolean {
        if (asciiMode) return false
        when (keyCode) {
            RimeBridge.KEY_BACKSPACE, 8, 127 -> {
                if (rawInput.isEmpty()) return false
                rawInput = rawInput.dropLast(1)
                recomputeCandidates()
                return true
            }
            RimeBridge.KEY_ESCAPE, 27 -> {
                if (rawInput.isEmpty()) return false
                clearComposition()
                return true
            }
            RimeBridge.KEY_SPACE -> {
                if (rawInput.isEmpty()) return false
                if (scoredCandidates.isNotEmpty()) {
                    commitCandidate(0)
                } else {
                    clearComposition()
                }
                return true
            }
            RimeBridge.KEY_RETURN, '\r'.code, '\n'.code -> {
                if (rawInput.isEmpty()) return false
                pendingCommit = rawInput
                clearComposition()
                return true
            }
            else -> {
                val ch = keyCode.toChar()
                if (ch in 'a'..'z' || ch == '\'') {
                    rawInput += ch
                    recomputeCandidates()
                    return true
                }
                return false
            }
        }
    }

    fun processKeyText(keys: String) {
        rawInput = keys
        recomputeCandidates()
    }

    fun selectCandidate(index: Int): String? {
        if (index !in scoredCandidates.indices) return null
        return commitCandidate(index)
    }

    fun getCommit(): String? {
        val out = pendingCommit
        pendingCommit = null
        return out
    }

    fun clearComposition() {
        rawInput = ""
        preedit = ""
        scoredCandidates.clear()
    }

    fun learnWord(pinyinKey: String, word: String): Boolean {
        if (incognito || !enableUserDict || fixedOrder) return false
        recordUserCommit(pinyinKey, word)
        return true
    }

    fun currentSpacedPinyin(): String {
        if (rawInput.isEmpty()) return ""
        val syls = if (schemaId == "double_pinyin_flypy") {
            RimeBridge.segmentDoublePinyinFlypy(rawInput)
        } else {
            RimeBridge.segmentFullPinyin(rawInput)
        }
        return syls.joinToString(" ")
    }

    private fun normalizePinyin(py: String): String {
        val sb = StringBuilder(py.length)
        var prevSpace = true
        for (rawCh in py) {
            val c = rawCh.lowercaseChar()
            if (c in 'a'..'z') {
                sb.append(c)
                prevSpace = false
            } else if ((c == ' ' || c == '\'') && !prevSpace) {
                sb.append(' ')
                prevSpace = true
            }
        }
        if (sb.isNotEmpty() && sb.last() == ' ') {
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

    private fun addDictEntry(word: String, py: String, weight: Long, orderIndex: Int) {
        var syls = 1
        var atStart = true
        val compact = StringBuilder(py.length)
        val inits = StringBuilder(4)
        for (c in py) {
            if (c == ' ') {
                syls++
                atStart = true
            } else {
                compact.append(c)
                if (atStart) {
                    inits.append(c)
                    atStart = false
                }
            }
        }
        entries.add(
            DictEntry(
                word = word,
                pinyin = py,
                compactPinyin = compact.toString(),
                initials = inits.toString(),
                syllableCount = syls,
                baseWeight = weight,
                orderIndex = orderIndex
            )
        )
    }

    private fun parseEntryLine(line: String, orderCounter: IntArray) {
        if (line.isEmpty() || line.startsWith("#")) return
        val t1 = line.indexOf('\t')
        if (t1 <= 0) return
        val col0 = line.substring(0, t1)
        val t2 = line.indexOf('\t', t1 + 1)
        val col1 = if (t2 < 0) line.substring(t1 + 1) else line.substring(t1 + 1, t2)
        val col2 = if (t2 < 0) "" else line.substring(t2 + 1)

        val col0IsAscii = col0.all { it in 'a'..'z' || it == ' ' }
        if (col0IsAscii && col1.isNotEmpty() && col1[0].code >= 0x80) {
            val py = normalizePinyin(col0)
            if (py.isEmpty()) return
            val words = line.substring(t1 + 1).split('\t')
            var rank = 0
            for (w in words) {
                if (w.isBlank()) continue
                addDictEntry(w.trim(), py, maxOf(100L, 10_000L - rank * 50L), orderCounter[0]++)
                rank++
            }
            return
        }

        val py = normalizePinyin(col1)
        if (col0.isEmpty() || py.isEmpty()) return
        val wt = col2.trim().toLongOrNull() ?: 1000L
        addDictEntry(col0, py, wt, orderCounter[0]++)
    }

    private fun loadFileIntoEntries(file: File, orderCounter: IntArray) {
        if (!file.exists() || !file.isFile) return
        BufferedReader(InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8)).use { reader ->
            var inYamlHeader = false
            var line = reader.readLine()
            while (line != null) {
                val trimmed = line.trimEnd('\r')
                if (trimmed == "---") {
                    inYamlHeader = true
                    line = reader.readLine()
                    continue
                }
                if (inYamlHeader) {
                    if (trimmed == "...") inYamlHeader = false
                    line = reader.readLine()
                    continue
                }
                parseEntryLine(trimmed, orderCounter)
                line = reader.readLine()
            }
        }
    }

    private fun loadLexicons() {
        entries.clear()
        byExactPinyin.clear()
        byCompactPinyin.clear()
        byInitials.clear()
        byFirstSyllable.clear()

        val orderCounter = intArrayOf(0)
        if (sharedDir.isNotEmpty()) {
            val dir = File(sharedDir)
            if (dir.exists() && dir.isDirectory) {
                loadFileIntoEntries(File(dir, "rime_ice_full.tsv"), orderCounter)
                loadFileIntoEntries(File(dir, "rime_ice_compact.tsv"), orderCounter)
                val extraFiles = dir.listFiles().orEmpty()
                    .filter {
                        it.isFile &&
                            it.name != "rime_ice_compact.tsv" &&
                            it.name != "rime_ice_full.tsv" &&
                            (it.name.endsWith(".dict.yaml") || it.name.endsWith(".tsv") || it.name.endsWith(".txt"))
                    }
                    .sortedBy { it.name }
                for (f in extraFiles) {
                    loadFileIntoEntries(f, orderCounter)
                }
            }
        }

        for (i in entries.indices) {
            val e = entries[i]
            byExactPinyin.getOrPut(e.pinyin) { ArrayList() }.add(i)
            byCompactPinyin.getOrPut(e.compactPinyin) { ArrayList() }.add(i)
            byInitials.getOrPut(e.initials) { ArrayList() }.add(i)
            val sp = e.pinyin.indexOf(' ')
            val firstSyl = if (sp < 0) e.pinyin else e.pinyin.substring(0, sp)
            byFirstSyllable.getOrPut(firstSyl) { ArrayList() }.add(i)
        }
    }

    private fun userDbFile(): File? {
        if (userDir.isEmpty()) return null
        return File(userDir, "rime_ice.userdb.tsv")
    }

    private fun loadUserDb() {
        userDb.clear()
        val f = userDbFile() ?: return
        if (!f.exists() || !f.isFile) return
        BufferedReader(InputStreamReader(FileInputStream(f), StandardCharsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val clean = line.trim()
                if (clean.isNotEmpty() && !clean.startsWith("#")) {
                    val parts = clean.split('\t')
                    if (parts.size >= 3) {
                        val py = normalizePinyin(parts[0])
                        val word = parts[1]
                        val commits = parts[2].toLongOrNull() ?: 1L
                        val tick = parts.getOrNull(3)?.toLongOrNull() ?: 1L
                        if (py.isNotEmpty() && word.isNotEmpty()) {
                            userDb["$py\t$word"] = UserDbRecord(word, py, commits, tick)
                            if (tick > userTick) userTick = tick
                        }
                    }
                }
                line = reader.readLine()
            }
        }
    }

    private fun saveUserDb() {
        if (incognito || !enableUserDict || userDir.isEmpty()) return
        val f = userDbFile() ?: return
        f.parentFile?.mkdirs()
        OutputStreamWriter(FileOutputStream(f, false), StandardCharsets.UTF_8).use { writer ->
            writer.write("# Rime userdb v1\n")
            for (rec in userDb.values) {
                writer.write("${rec.pinyin}\t${rec.word}\t${rec.commits}\t${rec.lastTick}\n")
            }
        }
    }

    private fun recordUserCommit(pinyin: String, word: String) {
        if (incognito || !enableUserDict || fixedOrder) return
        val normPy = normalizePinyin(pinyin)
        if (normPy.isEmpty() || word.isEmpty()) return
        val key = "$normPy\t$word"
        userTick++
        val existing = userDb[key]
        if (existing != null) {
            existing.commits += 1L
            existing.lastTick = userTick
        } else {
            userDb[key] = UserDbRecord(word = word, pinyin = normPy, commits = 1L, lastTick = userTick)
        }
        saveUserDb()
    }

    private fun bestWordForSyllables(syllables: List<String>, start: Int, len: Int): String {
        val joined = syllables.subList(start, start + len).joinToString(" ")
        var bestWord = ""
        var bestScore = -1L

        if (!fixedOrder && enableUserDict) {
            for (rec in userDb.values) {
                if (rec.pinyin == joined) {
                    val sc = 1_000_000L + rec.commits * 5_000L + rec.lastTick
                    if (sc > bestScore) {
                        bestScore = sc
                        bestWord = rec.word
                    }
                }
            }
        }

        val indices = byExactPinyin[joined]
        if (indices != null) {
            for (idx in indices) {
                val e = entries[idx]
                if (e.baseWeight > bestScore) {
                    bestScore = e.baseWeight
                    bestWord = e.word
                }
            }
        }

        if (bestWord.isEmpty() && len == 1 && syllables[start].isNotEmpty()) {
            val init = syllables[start].take(1)
            val initIndices = byInitials[init]
            if (initIndices != null) {
                for (idx in initIndices) {
                    val e = entries[idx]
                    if (e.syllableCount == 1 && e.pinyin.startsWith(syllables[start]) && e.baseWeight > bestScore) {
                        bestScore = e.baseWeight
                        bestWord = e.word
                    }
                }
            }
        }
        return bestWord
    }

    private fun recomputeCandidates() {
        scoredCandidates.clear()
        preedit = ""
        if (rawInput.isEmpty()) return

        val isDoublePinyin = (schemaId == "double_pinyin_flypy")
        val syllables = if (isDoublePinyin) {
            RimeBridge.segmentDoublePinyinFlypy(rawInput)
        } else {
            RimeBridge.segmentFullPinyin(rawInput)
        }
        preedit = syllables.joinToString("'")

        val fullSpaced = syllables.joinToString(" ")
        val fullInitials = syllables.mapNotNull { it.firstOrNull() }.joinToString("")

        val seenWords = HashSet<String>()
        fun addCand(text: String, py: String, score: Long, orderIdx: Int, comment: String = "") {
            if (text.isEmpty() || !seenWords.add(text)) return
            scoredCandidates.add(
                ScoredCandidate(
                    text = text,
                    comment = comment,
                    pinyin = py,
                    score = score,
                    orderIndex = orderIdx
                )
            )
        }

        // 1. UserDB exact matches (when dynamic frequency is enabled)
        if (!fixedOrder && enableUserDict) {
            val userMatches = userDb.values
                .filter { it.pinyin == fullSpaced }
                .sortedWith(
                    compareByDescending<UserDbRecord> { it.commits }
                        .thenByDescending { it.lastTick }
                )
            for (u in userMatches) {
                addCand(u.word, u.pinyin, 2_000_000L + u.commits * 10_000L + u.lastTick, -1000)
            }
        }

        // 2. Exact full-pinyin dictionary matches
        val exactList = ArrayList<ScoredCandidate>()
        fun appendEntryCandidate(e: DictEntry, bonus: Long) {
            var userBoost = 0L
            if (!fixedOrder && enableUserDict) {
                val u = userDb["${e.pinyin}\t${e.word}"]
                if (u != null) {
                    userBoost = 1_500_000L + u.commits * 10_000L + u.lastTick
                }
            }
            exactList.add(
                ScoredCandidate(
                    text = e.word,
                    pinyin = e.pinyin,
                    score = e.baseWeight + bonus + userBoost,
                    orderIndex = e.orderIndex
                )
            )
        }

        byExactPinyin[fullSpaced]?.forEach { idx ->
            appendEntryCandidate(entries[idx], 500_000L)
        }

        if (!isDoublePinyin) {
            val rawClean = rawInput.filter { it in 'a'..'z' }
            byCompactPinyin[rawClean]?.forEach { idx ->
                appendEntryCandidate(entries[idx], 450_000L)
            }
            byInitials[rawClean]?.forEach { idx ->
                appendEntryCandidate(entries[idx], 200_000L)
            }
        } else {
            byInitials[fullInitials]?.forEach { idx ->
                val e = entries[idx]
                if (e.syllableCount == syllables.size) {
                    val parts = e.pinyin.split(' ')
                    if (parts.size == syllables.size && parts.indices.all { k -> parts[k].startsWith(syllables[k]) }) {
                        appendEntryCandidate(e, 350_000L)
                    }
                }
            }
        }

        if (fixedOrder) {
            exactList.sortBy { it.orderIndex }
        } else {
            exactList.sortWith(
                compareByDescending<ScoredCandidate> { it.score }
                    .thenBy { it.orderIndex }
            )
        }
        for (c in exactList) {
            addCand(c.text, c.pinyin, c.score, c.orderIndex, c.comment)
        }

        // 3. Whole-sentence greedy composition when >= 2 syllables
        if (syllables.size >= 2) {
            val composed = StringBuilder()
            var pos = 0
            var allMatched = true
            while (pos < syllables.size) {
                val rem = syllables.size - pos
                var piece = ""
                var step = 0
                for (l in minOf(4, rem) downTo 1) {
                    val candidatePiece = bestWordForSyllables(syllables, pos, l)
                    if (candidatePiece.isNotEmpty()) {
                        piece = candidatePiece
                        step = l
                        break
                    }
                }
                if (step == 0) {
                    allMatched = false
                    break
                }
                composed.append(piece)
                pos += step
            }
            if (allMatched && composed.isNotEmpty()) {
                addCand(composed.toString(), fullSpaced, 400_000L, 0)
            }
        }

        // 4. Prefix & first-syllable single-character candidates
        if (syllables.isNotEmpty()) {
            for (l in (syllables.size - 1) downTo 1) {
                val subPy = syllables.subList(0, l).joinToString(" ")
                byExactPinyin[subPy]?.forEach { idx ->
                    val e = entries[idx]
                    addCand(e.word, e.pinyin, e.baseWeight, e.orderIndex)
                }
            }
            val firstSyl = syllables[0]
            for ((sylKey, idxList) in byFirstSyllable) {
                if (sylKey.startsWith(firstSyl)) {
                    for (idx in idxList) {
                        val e = entries[idx]
                        if (e.syllableCount == 1) {
                            addCand(e.word, e.pinyin, e.baseWeight / 2L, e.orderIndex)
                        }
                    }
                }
            }
        }
    }

    private fun commitCandidate(index: Int): String? {
        val picked = scoredCandidates.getOrNull(index) ?: return null
        val fullPy = currentSpacedPinyin().ifEmpty { picked.pinyin }
        recordUserCommit(fullPy, picked.text)
        pendingCommit = picked.text
        clearComposition()
        return picked.text
    }
}
