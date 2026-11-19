package com.typefree.ime.ime.engine

/**
 * Pure Kotlin helper for T9 9-key Chinese Pinyin:
 * - Maps `a-z` to ITU E.161 digits `2-9`.
 * - Indexes all ~412 standard Mandarin Pinyin syllables for accurate left-rail syllable filtering.
 * - Formats Rime T9 preedit + candidate spelling hints into clean human-readable Pinyin.
 */
object T9PinyinHelper {

    /** Digits accepted as T9 composing keys (`2`..`9`). */
    fun isT9Digit(ch: Char): Boolean = ch in '2'..'9'

    fun charToT9Digit(ch: Char): Char? = when (ch.lowercaseChar()) {
        'a', 'b', 'c' -> '2'
        'd', 'e', 'f' -> '3'
        'g', 'h', 'i' -> '4'
        'j', 'k', 'l' -> '5'
        'm', 'n', 'o' -> '6'
        'p', 'q', 'r', 's' -> '7'
        't', 'u', 'v' -> '8'
        'w', 'x', 'y', 'z' -> '9'
        else -> null
    }

    fun syllableToDigits(syllable: String): String {
        val sb = StringBuilder(syllable.length)
        for (ch in syllable) {
            val d = charToT9Digit(ch) ?: return ""
            sb.append(d)
        }
        return sb.toString()
    }

    /** Valid single-key Pinyin initials / zero-initial vowels for each T9 digit (omits impossible initials `i`, `u`, `v`). */
    private val SINGLE_KEY_INITIALS: Map<Char, List<String>> = mapOf(
        '2' to listOf("b", "c", "a"),
        '3' to listOf("d", "f", "e"),
        '4' to listOf("g", "h"),
        '5' to listOf("j", "k", "l"),
        '6' to listOf("m", "n", "o"),
        '7' to listOf("s", "q", "p", "r"),
        '8' to listOf("t"),
        '9' to listOf("w", "z", "y", "x")
    )

    /** High-frequency Mandarin syllables prioritized earlier inside the left T9 syllable rail. */
    private val HIGH_FREQ_RANK: Map<String, Int> = listOf(
        "de", "shi", "bu", "wo", "ni", "yi", "le", "zai", "you", "ta", "zhe", "ge", "men", "hao",
        "ren", "zhong", "guo", "wei", "shang", "lai", "shuo", "dao", "yao", "hui", "jiu", "ke", "yi",
        "he", "dou", "ye", "hen", "dao", "qu", "kan", "neng", "xiang", "mei", "hai", "zhi", "na",
        "da", "xiao", "duo", "shao", "dui", "xia", "tian", "jin", "ming", "nian", "yue", "ri", "shi",
        "fen", "dian", "xian", "gong", "zuo", "xue", "xi", "jia", "peng", "you", "shen", "me", "zen",
        "ma", "ba", "ne", "a", "o", "e", "en", "ai", "gei", "ba", "bei", "rang", "cong", "xiang",
        "gen", "bi", "yin", "suo", "dan", "ru", "guo", "huo", "er", "bing", "qie", "ran", "hou",
        "zhi", "qian", "li", "wai", "nei", "dong", "xi", "nan", "bei", "gao", "di", "chang", "duan",
        "xin", "lao", "kuai", "man", "kai", "guan", "fa", "song", "shou", "xie", "du", "ting", "wen",
        "da", "hua", "zi", "ci", "ju", "pin", "yin", "jian", "pan", "shu", "ru", "dian", "nao", "ji",
        "wang", "luo", "ruan", "ying", "che", "lu", "fang", "qian", "shui", "huo", "feng", "yu",
        "qing", "gan", "xin", "si", "yi", "xiang", "jue", "de", "zhi", "dao", "ming", "bai", "qing",
        "chu", "wen", "ti", "ban", "fa", "jie", "jue", "kai", "shi", "wan", "cheng", "ji", "hua",
        "zhun", "bei", "que", "ding", "xuan", "ze", "gai", "bian", "zeng", "jia", "shan", "chu",
        "xiu", "gai", "cha", "zhao", "ce", "shi", "yan", "zheng", "da", "bao", "an", "zhuang",
        "mi", "gao", "gan", "han", "nan", "nv", "lv", "qu", "xu", "ju", "yu", "xue", "jue", "que"
    ).distinct().withIndex().associate { (idx, syl) -> syl to idx }

    /** Complete Mandarin standard Pinyin syllable inventory (including `lv`/`nv`/`lve`/`nve`). */
    private val ALL_PINYIN_SYLLABLES: List<String> = listOf(
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
        "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian", "liang", "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu", "luan", "lue", "lun", "luo", "lv", "lve",
        "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
        "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nuan", "nue", "nun", "nuo", "nv", "nve",
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

    private val DIGITS_TO_SYLLABLES: Map<String, List<String>> by lazy {
        ALL_PINYIN_SYLLABLES
            .groupBy { syllableToDigits(it) }
            .mapValues { (_, list) ->
                list.sortedWith(
                    compareBy<String> { HIGH_FREQ_RANK[it] ?: Int.MAX_VALUE }
                        .thenBy { it }
                )
            }
    }

    private val VALID_INITIAL_DIGITS: Set<Char> by lazy {
        SINGLE_KEY_INITIALS.keys
    }

    /**
     * Returns ordered Pinyin syllables matching a leading prefix of [unlockedDigits] for the left T9 rail.
     *
     * Priority:
     * 1. [preferredLeadingSyllable] (if it matches a valid leading prefix of [unlockedDigits]) so the
     *    top left-rail item matches Rime's primary candidate.
     * 2. Full valid Pinyin syllables from longest prefix (up to 6 digits) down to 2 digits, preferring
     *    splits whose remainder (if any) starts cleanly.
     * 3. Single-letter Pinyin initials / standalone vowels (`a`, `e`, `o`) for the first digit so
     *    abbreviated-pinyin users can also filter by initial.
     */
    fun matchLeadingSyllables(
        unlockedDigits: String,
        preferredLeadingSyllable: String? = null,
        maxResults: Int = 18
    ): List<String> {
        val clean = unlockedDigits.filter { it in '2'..'9' }
        if (clean.isEmpty()) return emptyList()

        val result = LinkedHashSet<String>()

        val preferred = preferredLeadingSyllable?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (preferred != null && preferred.length <= clean.length) {
            val prefDigits = syllableToDigits(preferred)
            if (prefDigits.isNotEmpty() && clean.startsWith(prefDigits)) {
                result.add(preferred)
            }
        }

        val maxLen = minOf(6, clean.length)
        // Collect full multi-char syllables from longest to shortest, prioritizing those that match exact length or leave a valid next digit
        val exactFull = ArrayList<String>()
        val prefixFull = ArrayList<String>()
        for (len in maxLen downTo 2) {
            val sub = clean.substring(0, len)
            val matches = DIGITS_TO_SYLLABLES[sub] ?: continue
            val isExactEnd = (len == clean.length)
            val hasValidRemainder = !isExactEnd && clean[len] in VALID_INITIAL_DIGITS
            for (syl in matches) {
                if (isExactEnd) {
                    exactFull.add(syl)
                } else if (hasValidRemainder) {
                    prefixFull.add(syl)
                }
            }
        }

        for (syl in exactFull) {
            result.add(syl)
            if (result.size >= maxResults) return result.toList()
        }
        for (syl in prefixFull) {
            result.add(syl)
            if (result.size >= maxResults) return result.toList()
        }

        // Fallback single-letter syllables / initials for the first digit
        val firstDigit = clean[0]
        val singles = SINGLE_KEY_INITIALS[firstDigit].orEmpty()
        for (s in singles) {
            result.add(s)
            if (result.size >= maxResults) break
        }
        return result.toList()
    }

    /**
     * Splits Rime's `preedit` (e.g. `"今天8426 74"` or `"ni'426 62"`) into:
     * - `committedHanPrefix`: already selected CJK/non-ASCII prefix inside Rime's active composition
     * - `activeCodeSegment`: remaining ASCII encoding segment (`"8426 74"` / `"ni'426 62"`)
     */
    fun splitRimePreedit(rimePreedit: String): Pair<String, String> {
        if (rimePreedit.isEmpty()) return "" to ""
        var splitIdx = 0
        while (splitIdx < rimePreedit.length) {
            val ch = rimePreedit[splitIdx]
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '2'..'9' || ch == '\'') {
                break
            }
            splitIdx++
        }
        val prefix = rimePreedit.substring(0, splitIdx).trim()
        val tail = rimePreedit.substring(splitIdx).trim()
        return prefix to tail
    }

    /**
     * Formats Rime T9 preedit into readable Pinyin (e.g. `"ni hao"`, `"今天 tian qi"`),
     * combining [pendingCommittedPrefix], any in-composition selected Chinese prefix from [rimePreedit],
     * and the top candidate's spelling hint ([topCandidateComment]).
     */
    fun formatT9Preedit(
        rimePreedit: String,
        topCandidateComment: String,
        pendingCommittedPrefix: String = ""
    ): String {
        val (inCompPrefix, tailCode) = splitRimePreedit(rimePreedit)
        val combinedPrefix = (pendingCommittedPrefix + inCompPrefix).trim()
        val cleanComment = topCandidateComment.trim()

        val tailDisplay = when {
            tailCode.isEmpty() -> ""
            tailCode.none { it in '2'..'9' } -> {
                // Already pure locked pinyin letters (e.g. "ni'hao")
                tailCode
            }
            cleanComment.isNotEmpty() -> {
                // Rime spelling_hints returns pinyin for the matched portion; if tailCode has unmatched trailing digits, keep them visible
                val commentSyllables = cleanComment.split(' ').filter { it.isNotBlank() }
                val rawTailCompact = tailCode.replace(" ", "").replace("'", "")
                val commentCompactLen = commentSyllables.sumOf { it.length }
                if (commentCompactLen in 1 until rawTailCompact.length) {
                    val unmatchedTail = rawTailCompact.substring(commentCompactLen)
                    if (unmatchedTail.all { it in '2'..'9' || it in 'a'..'z' }) {
                        "$cleanComment $unmatchedTail"
                    } else {
                        cleanComment
                    }
                } else {
                    cleanComment
                }
            }
            else -> tailCode
        }

        return when {
            combinedPrefix.isNotEmpty() && tailDisplay.isNotEmpty() -> "$combinedPrefix $tailDisplay"
            combinedPrefix.isNotEmpty() -> combinedPrefix
            else -> tailDisplay
        }
    }
}
