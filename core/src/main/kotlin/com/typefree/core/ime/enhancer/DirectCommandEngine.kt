package com.typefree.core.ime.enhancer

import com.typefree.core.ime.coordinator.CommitAction
import com.typefree.core.ime.coordinator.DirectCommandType
import com.typefree.core.ime.coordinator.EnhancedCandidate
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.text.BreakIterator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.pow

/**
 * End-to-end UTF-8 4-byte supplementary character (`CJK Ext B~I`, e.g. `𩽾` U+29F7E) utilities:
 * - Standard UTF-8 `ByteArray` codec for JNI boundary safety (never Modified UTF-8).
 * - Code-point & grapheme-cluster aware visible character counting (`codePointCount` + `BreakIterator`).
 */
object Utf8Safety {

    fun toStandardUtf8Bytes(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

    fun fromStandardUtf8Bytes(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)

    /**
     * Counts visible characters accurately across CJK Extension B~I surrogate pairs (`𩽾` = 1)
     * and ZWJ / skin-tone Emoji sequences (`👨‍👩‍👧` or `👍🏽` = 1).
     */
    fun countVisibleCharacters(text: String): Int {
        if (text.isEmpty()) return 0
        val hasFormatOrMod = text.codePoints().anyMatch { cp ->
            cp == 0x200D || cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF
        }
        if (!hasFormatOrMod) {
            return text.codePointCount(0, text.length)
        }
        val iter = BreakIterator.getCharacterInstance(Locale.CHINA)
        iter.setText(text)
        var count = 0
        var boundary = iter.first()
        while (boundary != BreakIterator.DONE) {
            val next = iter.next()
            if (next != BreakIterator.DONE) count++
            boundary = next
        }
        return count
    }

    fun isSingleCharacter(text: String): Boolean = countVisibleCharacters(text) == 1

    /**
     * Iterates full Unicode code points (preserving 4-byte supplementary CJK characters as intact Strings).
     */
    fun extractHanziCodePoints(text: String, limit: Int = 12): List<String> {
        if (text.isEmpty() || limit <= 0) return emptyList()
        val result = ArrayList<String>(limit)
        var offset = 0
        while (offset < text.length && result.size < limit) {
            val cp = text.codePointAt(offset)
            val charCount = Character.charCount(cp)
            if (isCjkCodePoint(cp)) {
                result.add(text.substring(offset, offset + charCount))
            }
            offset += charCount
        }
        return result
    }

    fun isCjkCodePoint(cp: Int): Boolean {
        return cp in 0x4E00..0x9FFF ||       // CJK Unified Ideographs
            cp in 0x3400..0x4DBF ||          // CJK Extension A
            cp in 0x20000..0x2A6DF ||        // CJK Extension B (includes 𩽾 U+29F7E)
            cp in 0x2A700..0x2B73F ||        // CJK Extension C
            cp in 0x2B740..0x2B81F ||        // CJK Extension D
            cp in 0x2B820..0x2CEAF ||        // CJK Extension E
            cp in 0x2CEB0..0x2EBEF ||        // CJK Extension F
            cp in 0x30000..0x3134F ||        // CJK Extension G
            cp in 0x31350..0x323AF ||        // CJK Extension H
            cp in 0x2EBF0..0x2EE5F           // CJK Extension I
    }
}

/**
 * Lua-free direct command engine (`orq`, `orq±N`, `ouj`, `=expr`, `ofi`, `oqm`, `ovy`, `ojf`, `oai`, `odz`, `zz`, `zza..zzf`).
 */
class DirectCommandEngine(
    private var reverseLookupMap: Map<String, List<String>> = emptyMap(),
    private val clipboardReader: () -> String = { "" },
    private val fullCodeEnabledStateProvider: () -> Boolean = { true },
    private val asciiPunctStateProvider: () -> Boolean = { false },
    private val simplificationStateProvider: () -> Boolean = { false },
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
    private val activeSchemaIdProvider: () -> String = DEFAULT_SCHEMA_ID_PROVIDER,
    private val singleCharModeStateProvider: () -> Boolean = DEFAULT_SINGLE_CHAR_PROVIDER,
    private val dateProvider: () -> LocalDate = DEFAULT_DATE_PROVIDER
) {
    internal var boundSchemaIdProvider: (() -> String)? = null
    internal var boundSingleCharModeProvider: (() -> Boolean)? = null

    private fun currentSchemaId(): String =
        if (activeSchemaIdProvider !== DEFAULT_SCHEMA_ID_PROVIDER) activeSchemaIdProvider()
        else boundSchemaIdProvider?.invoke() ?: activeSchemaIdProvider()

    private fun currentSingleCharMode(): Boolean =
        if (singleCharModeStateProvider !== DEFAULT_SINGLE_CHAR_PROVIDER) singleCharModeStateProvider()
        else boundSingleCharModeProvider?.invoke() ?: singleCharModeStateProvider()

    fun updateReverseLookupMap(newMap: Map<String, List<String>>) {
        reverseLookupMap = newMap
    }

    fun hasReverseLookupLoaded(): Boolean = reverseLookupMap.isNotEmpty()

    fun isCommandPrefixOrMatch(code: String, enabled: Boolean = true): Boolean {
        if (!enabled || code.isEmpty()) return false
        if (code.startsWith("=")) return code.length >= 2
        if (code == "oqm") return currentSchemaId() == "flypy"
        if (parseDateOffsetDays(code) != null) return true
        return code in DIRECT_COMMAND_CODES
    }

    fun evaluate(code: String, nowMillis: Long = System.currentTimeMillis(), enabled: Boolean = true): List<EnhancedCandidate> {
        if (!enabled || code.isEmpty()) return emptyList()

        if (code.startsWith("=")) {
            val expr = code.removePrefix("=").trim()
            if (expr.isEmpty()) return emptyList()
            val valueStr = SafeMathEvaluator.evaluateFormatted(expr) ?: return emptyList()
            return listOf(
                EnhancedCandidate(
                    displayText = valueStr,
                    commitText = valueStr,
                    codeHint = "算式"
                ),
                EnhancedCandidate(
                    displayText = "$expr=$valueStr",
                    commitText = "$expr=$valueStr",
                    codeHint = "含原式"
                )
            )
        }

        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
        val baseDate = if (dateProvider !== DEFAULT_DATE_PROVIDER) {
            dateProvider()
        } else {
            zdt.toLocalDate()
        }

        val offsetDays = parseDateOffsetDays(code)
        if (offsetDays != null) {
            return formatDateCandidates(baseDate.plusDays(offsetDays))
        }

        return when (code) {
            "orq", "date" -> formatDateCandidates(baseDate)

            "ouj", "time" -> {
                val hm = zdt.format(HM_FMT)
                val hmsCn = zdt.format(HMS_CN_FMT)
                val unixSec = (nowMillis / 1000L).toString()
                listOf(
                    EnhancedCandidate(displayText = hm, commitText = hm),
                    EnhancedCandidate(displayText = hmsCn, commitText = hmsCn),
                    EnhancedCandidate(displayText = unixSec, commitText = unixSec, codeHint = "时间戳")
                )
            }

            "oxq" -> {
                val wd = when (zdt.dayOfWeek.value) {
                    1 -> "一"
                    2 -> "二"
                    3 -> "三"
                    4 -> "四"
                    5 -> "五"
                    6 -> "六"
                    else -> "日"
                }
                listOf(
                    EnhancedCandidate(displayText = "星期$wd", commitText = "星期$wd"),
                    EnhancedCandidate(displayText = "周$wd", commitText = "周$wd")
                )
            }

            "ofi" -> {
                val clip = clipboardReader().trim()
                val hanziList = Utf8Safety.extractHanziCodePoints(clip, limit = 12)
                if (hanziList.isEmpty()) {
                    listOf(
                        EnhancedCandidate(
                            displayText = "[剪贴板无待查汉字]",
                            commitText = "",
                            codeHint = "反查",
                            action = CommitAction.PlainText("")
                        )
                    )
                } else {
                    hanziList.map { ch ->
                        val codes = reverseLookupMap[ch].orEmpty()
                        val joined = if (codes.isNotEmpty()) codes.joinToString("/") else "未收录"
                        val primaryCode = codes.firstOrNull().orEmpty()
                        EnhancedCandidate(
                            displayText = "[$ch: $joined]",
                            commitText = primaryCode,
                            codeHint = "点选复制编码",
                            action = if (primaryCode.isNotEmpty()) {
                                CommitAction.CopyToClipboard(
                                    text = primaryCode,
                                    toastMessage = "✓ 已复制「$ch」编码: $primaryCode"
                                )
                            } else {
                                CommitAction.PlainText("")
                            }
                        )
                    }
                }
            }

            "oqm" -> {
                if (currentSchemaId() != "flypy") return emptyList()
                val currentOn = fullCodeEnabledStateProvider()
                val statusLabel = if (currentOn) "已开启 → 点击关闭" else "已关闭 → 点击开启"
                listOf(
                    EnhancedCandidate(
                        displayText = "[全码字: ${if (currentOn) "已开启" else "已关闭"}]",
                        commitText = "",
                        codeHint = statusLabel,
                        action = CommitAction.Command(DirectCommandType.TOGGLE_FULL_CODE_DICT)
                    )
                )
            }

            "ovy" -> {
                val ascii = asciiPunctStateProvider()
                listOf(
                    EnhancedCandidate(
                        displayText = "[中英标点切换]",
                        commitText = "",
                        codeHint = if (ascii) "当前英文标点" else "当前中文标点",
                        action = CommitAction.Command(DirectCommandType.TOGGLE_ASCII_PUNCT)
                    )
                )
            }

            "ojf" -> {
                val simp = simplificationStateProvider()
                listOf(
                    EnhancedCandidate(
                        displayText = "[简繁切换]",
                        commitText = "",
                        codeHint = if (simp) "当前繁体" else "当前简体",
                        action = CommitAction.Command(DirectCommandType.TOGGLE_SIMPLIFICATION)
                    )
                )
            }

            "oai" -> {
                listOf(
                    EnhancedCandidate(
                        displayText = "[✦ Jev 军师润色]",
                        commitText = "",
                        codeHint = "252dp平滑联动",
                        action = CommitAction.Command(DirectCommandType.OPEN_JEV_COPILOT)
                    )
                )
            }

            "odz" -> {
                val singleOn = currentSingleCharMode()
                val label = if (singleOn) "关闭单字模式" else "开启单字模式"
                listOf(
                    EnhancedCandidate(
                        displayText = label,
                        commitText = "",
                        codeHint = if (singleOn) "当前仅单字" else "当前字词混排",
                        action = CommitAction.Command(DirectCommandType.TOGGLE_SINGLE_CHAR_MODE)
                    )
                )
            }

            "zz" -> buildZzOverviewCandidates()

            "zza", "zzb", "zzc", "zzd", "zze", "zzf" -> buildZzGroupCandidates(code)

            else -> emptyList()
        }
    }

    private fun formatDateCandidates(date: LocalDate): List<EnhancedCandidate> {
        val cnDate = date.format(CN_DATE_FMT)
        val isoDate = date.format(ISO_DATE_FMT)
        val lunar = ChineseLunarCalendar.formatGanZhiLunar(date)
        return listOf(
            EnhancedCandidate(displayText = cnDate, commitText = cnDate),
            EnhancedCandidate(displayText = isoDate, commitText = isoDate),
            EnhancedCandidate(displayText = lunar, commitText = lunar, codeHint = "农历")
        )
    }

    private fun parseDateOffsetDays(code: String): Long? {
        val match = DATE_OFFSET_REGEX.matchEntire(code) ?: return null
        val sign = match.groupValues[1]
        val n = match.groupValues[2].toLongOrNull() ?: return null
        if (n !in 0L..3650L) return null
        return if (sign == "-") -n else n
    }

    private fun buildZzOverviewCandidates(): List<EnhancedCandidate> {
        val hints = ZZ_CATEGORY_HINTS.map { (hintLabel, sampleHint) ->
            EnhancedCandidate(
                displayText = hintLabel,
                commitText = "",
                codeHint = sampleHint,
                action = CommitAction.PlainText("")
            )
        }
        val popular = ZZ_POPULAR_SYMBOLS.map { sym ->
            EnhancedCandidate(
                displayText = sym,
                commitText = sym,
                action = CommitAction.PlainText(sym)
            )
        }
        return hints + popular
    }

    private fun buildZzGroupCandidates(code: String): List<EnhancedCandidate> {
        val symbols = ZZ_GROUPS[code].orEmpty()
        return symbols.map { sym ->
            EnhancedCandidate(
                displayText = sym,
                commitText = sym,
                action = CommitAction.PlainText(sym)
            )
        }
    }

    companion object {
        internal val DEFAULT_SCHEMA_ID_PROVIDER: () -> String = { "flypy" }
        internal val DEFAULT_SINGLE_CHAR_PROVIDER: () -> Boolean = { false }
        internal val DEFAULT_DATE_PROVIDER: () -> LocalDate = { LocalDate.now() }

        private val DATE_OFFSET_REGEX = Regex("^(?:orq|date)([+-])(\\d{1,4})$")

        val DIRECT_COMMAND_CODES = setOf(
            "orq", "date", "ouj", "time", "oxq", "ofi", "oqm", "ovy", "ojf", "oai", "odz",
            "zz", "zza", "zzb", "zzc", "zzd", "zze", "zzf"
        )

        private val ZZ_CATEGORY_HINTS = listOf(
            "zza 常用符号" to "★ ✓ ※",
            "zzb 数学/单位" to "± × ℃",
            "zzc 序号/编号" to "① Ⅰ 一",
            "zzd 箭头/方向" to "→ ↑ ↙",
            "zze 制表/几何" to "─ ■ ●",
            "zzf 标点/括号" to "「」【】"
        )

        private val ZZ_POPULAR_SYMBOLS = listOf(
            "★", "✓", "✗", "→", "←", "↑", "↓", "℃", "①", "②", "③",
            "•", "…", "—", "±", "×", "÷", "°", "￥", "©", "®", "™"
        )

        private val ZZ_GROUPS: Map<String, List<String>> = mapOf(
            "zza" to listOf(
                "★", "☆", "✓", "✗", "✕", "※", "•", "·", "…", "—", "～", "｜",
                "＠", "＃", "￥", "＄", "€", "£", "©", "®", "™", "§", "¶", "†",
                "‡", "♠", "♥", "♦", "♣", "☀", "☁", "☂", "☃", "♪", "♫", "⚡"
            ),
            "zzb" to listOf(
                "＋", "－", "×", "÷", "±", "＝", "≠", "≈", "≡", "＜", "＞", "≤",
                "≥", "∞", "√", "∑", "∏", "∫", "∂", "∆", "∇", "∈", "∉", "⊂",
                "⊃", "∪", "∩", "∧", "∨", "¬", "∀", "∃", "∴", "∵", "°", "℃",
                "℉", "‰", "㎡", "㎥", "㎝", "㎜", "㎞", "㎏", "㎖", "Ω", "μ", "π"
            ),
            "zzc" to listOf(
                "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩", "⑪", "⑫",
                "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳", "⑴", "⑵", "⑶", "⑷",
                "⑸", "⑹", "⑺", "⑻", "⑼", "⑽", "⒈", "⒉", "⒊", "⒋", "⒌", "Ⅰ",
                "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ", "一", "二", "三",
                "四", "五", "六", "七", "八", "九", "十"
            ),
            "zzd" to listOf(
                "→", "←", "↑", "↓", "↔", "↕", "↖", "↗", "↘", "↙", "⇒", "⇐",
                "⇑", "⇓", "⇔", "⇕", "➜", "➡", "⬅", "⬆", "⬇", "↩", "↪", "↺",
                "↻", "⇄", "⇅", "⇆", "➔", "➤", "➢", "➣", "▶", "◀", "▲", "▼"
            ),
            "zze" to listOf(
                "─", "│", "┌", "┐", "└", "┘", "├", "┤", "┬", "┴", "┼", "═",
                "║", "╔", "╗", "╚", "╝", "╠", "╣", "╦", "╩", "╬", "■", "□",
                "▪", "▫", "●", "○", "◎", "◉", "▲", "△", "▼", "▽", "◆", "◇",
                "◈", "★", "☆", "◢", "◣", "◤", "◥"
            ),
            "zzf" to listOf(
                "「", "」", "『", "』", "【", "】", "〔", "〕", "《", "》", "〈", "〉",
                "（", "）", "［", "］", "｛", "｝", "＂", "＇", "‘", "’", "“", "”",
                "、", "。", "，", "．", "；", "：", "？", "！", "～", "·", "…", "—"
            )
        )

        private val CN_DATE_FMT = DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.CHINA)
        private val ISO_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        private val HM_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        private val HMS_CN_FMT = DateTimeFormatter.ofPattern("HH时mm分ss秒", Locale.CHINA)

        /**
         * Loads `flypydz.dict.yaml` or `flypy_dz.tsv` via standard UTF-8 (`Charsets.UTF_8`),
         * preserving all CJK Extension B~I 4-byte characters (e.g. `𩽾` -> `anan`).
         */
        fun loadReverseLookupFromDictLines(lines: Sequence<String>): Map<String, List<String>> {
            val map = LinkedHashMap<String, MutableList<String>>(9600)
            var inYamlHeader = false
            var isRimeDictYaml = false

            for (raw in lines) {
                val trimmed = raw.trim()
                if (trimmed == "---") {
                    inYamlHeader = true
                    isRimeDictYaml = true
                    continue
                }
                if (inYamlHeader) {
                    if (trimmed == "...") inYamlHeader = false
                    continue
                }
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val tab = raw.indexOf('\t')
                if (tab <= 0 || tab >= raw.length - 1) continue
                val col0 = raw.substring(0, tab).trim()
                val col1 = raw.substring(tab + 1).substringBefore('\t').trim()

                val (charText, rawCode) = if (isRimeDictYaml || !col0.all { it in 'a'..'z' || it == '\'' }) {
                    col0 to col1
                } else {
                    col1 to col0
                }
                val cleanCode = rawCode.trimEnd('\'')
                if (charText.isEmpty() || cleanCode.isEmpty() || cleanCode.startsWith("ok")) continue
                val list = map.getOrPut(charText) { ArrayList(2) }
                if (cleanCode !in list) {
                    list.add(cleanCode)
                }
            }
            return map
        }
    }
}

/**
 * Built-in zero-dependency recursive-descent arithmetic evaluator supporting `+ - * / % ^ ()`.
 */
object SafeMathEvaluator {

    fun evaluateFormatted(expression: String): String? {
        if (expression.isBlank() || expression.length > 96) return null
        // Require at least one operator or parens so bare numbers don't clutter unless desired
        val normalized = expression
            .replace('×', '*')
            .replace('÷', '/')
            .replace('（', '(')
            .replace('）', ')')
            .replace(" ", "")
        if (normalized.none { it.isDigit() }) return null
        if (normalized.any { it !in "0123456789.+-*/%^()" }) return null

        val value = runCatching { Parser(normalized).parseFull() }.getOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null

        val bd = BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString()
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseFull(): Double {
            val res = parseAddSub()
            if (pos != s.length) throw IllegalArgumentException("Unexpected token at $pos")
            return res
        }

        private fun parseAddSub(): Double {
            var left = parseMulDiv()
            while (pos < s.length) {
                when (s[pos]) {
                    '+' -> {
                        pos++
                        left += parseMulDiv()
                    }
                    '-' -> {
                        pos++
                        left -= parseMulDiv()
                    }
                    else -> break
                }
            }
            return left
        }

        private fun parseMulDiv(): Double {
            var left = parsePower()
            while (pos < s.length) {
                when (s[pos]) {
                    '*' -> {
                        pos++
                        left *= parsePower()
                    }
                    '/' -> {
                        pos++
                        val right = parsePower()
                        if (right == 0.0) throw ArithmeticException("Div by zero")
                        left /= right
                    }
                    '%' -> {
                        pos++
                        val right = parsePower()
                        if (right == 0.0) throw ArithmeticException("Mod by zero")
                        left %= right
                    }
                    else -> break
                }
            }
            return left
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            if (pos < s.length && s[pos] == '^') {
                pos++
                val exp = parsePower() // right-associative
                return base.pow(exp)
            }
            return base
        }

        private fun parseUnary(): Double {
            if (pos < s.length && s[pos] == '+') {
                pos++
                return parseUnary()
            }
            if (pos < s.length && s[pos] == '-') {
                pos++
                return -parseUnary()
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            if (pos >= s.length) throw IllegalArgumentException("Unexpected end")
            if (s[pos] == '(') {
                pos++
                val inner = parseAddSub()
                if (pos >= s.length || s[pos] != ')') throw IllegalArgumentException("Missing ')'")
                pos++
                return inner
            }
            val start = pos
            var dotSeen = false
            while (pos < s.length && (s[pos].isDigit() || (!dotSeen && s[pos] == '.'))) {
                if (s[pos] == '.') dotSeen = true
                pos++
            }
            if (start == pos) throw IllegalArgumentException("Expected number")
            return s.substring(start, pos).toDouble()
        }
    }
}

/**
 * Pure Kotlin Chinese Lunar Calendar (1900–2100 astronomical table) producing GanZhi year,
 * lunar month, and lunar day (e.g. `2026-11-17 -> 丙午年十月初九`).
 */
object ChineseLunarCalendar {

    private val TIAN_GAN = arrayOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    private val DI_ZHI = arrayOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    private val LUNAR_MONTHS = arrayOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    private val LUNAR_DAY_TENS = arrayOf("初", "十", "廿", "三")
    private val CN_DIGITS = arrayOf("十", "一", "二", "三", "四", "五", "六", "七", "八", "九")

    // Standard 1900–2100 lunar bitmask table (base date: 1900-01-31 = 庚子年正月初一)
    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x05ac0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0,
        0x092e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4,
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0,
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160,
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252,
        0x0d520
    )

    private val BASE_DATE = LocalDate.of(1900, 1, 31)

    fun formatGanZhiLunar(date: LocalDate): String {
        var offset = ChronoUnit.DAYS.between(BASE_DATE, date).toInt()
        if (offset < 0) return "${date.year}年"

        var year = 1900
        while (year <= 2100 && offset > 0) {
            val daysInYear = yearDays(year)
            if (offset < daysInYear) break
            offset -= daysInYear
            year++
        }
        if (year > 2100) return "${date.year}年"

        val leap = leapMonth(year)
        var isLeap = false
        var month = 1
        while (month <= 12 && offset > 0) {
            val daysInM = if (leap > 0 && month == leap + 1 && !isLeap) {
                isLeap = true
                month--
                leapDays(year)
            } else {
                monthDays(year, month)
            }
            if (offset < daysInM) break
            offset -= daysInM
            if (isLeap && month == leap) isLeap = false
            month++
        }
        if (offset == 0 && leap > 0 && month == leap + 1) {
            if (isLeap) {
                isLeap = false
            } else {
                isLeap = true
                month--
            }
        }
        val day = offset + 1
        val gan = TIAN_GAN[(year - 4) % 10]
        val zhi = DI_ZHI[(year - 4) % 12]
        val leapPrefix = if (isLeap) "闰" else ""
        val monthLabel = LUNAR_MONTHS[(month - 1).coerceIn(0, 11)] + "月"
        val dayLabel = formatDay(day)
        return "${gan}${zhi}年${leapPrefix}${monthLabel}${dayLabel}"
    }

    private fun yearDays(y: Int): Int {
        val info = LUNAR_INFO[y - 1900]
        var sum = 348
        var i = 0x8000
        while (i > 0x8) {
            if ((info and i) != 0) sum += 1
            i = i shr 1
        }
        return sum + leapDays(y)
    }

    private fun leapMonth(y: Int): Int = LUNAR_INFO[y - 1900] and 0xf

    private fun leapDays(y: Int): Int {
        if (leapMonth(y) == 0) return 0
        return if ((LUNAR_INFO[y - 1900] and 0x10000) != 0) 30 else 29
    }

    private fun monthDays(y: Int, m: Int): Int {
        return if ((LUNAR_INFO[y - 1900] and (0x10000 shr m)) != 0) 30 else 29
    }

    private fun formatDay(d: Int): String {
        return when (d) {
            10 -> "初十"
            20 -> "二十"
            30 -> "三十"
            else -> LUNAR_DAY_TENS[d / 10] + CN_DIGITS[d % 10]
        }
    }
}
