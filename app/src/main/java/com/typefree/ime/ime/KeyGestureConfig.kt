package com.typefree.ime.ime

import java.util.Locale

enum class KeyGestureActionType {
    NONE,
    COMMIT_TEXT,
    COMMIT_PAIRED,
    UNDO_LAST,
    REPEAT_LAST,
    EDITOR_SHORTCUT
}

data class KeyGestureBinding(
    val type: KeyGestureActionType,
    val text: String = "",
    val rightText: String = "",
    val label: String = "",
    val editorShortcut: EditorShortcutAction? = null
) {
    val isEnabled: Boolean
        get() = type != KeyGestureActionType.NONE

    fun displayBadge(maxChars: Int = 4): String {
        if (type == KeyGestureActionType.NONE) return ""
        val raw = when {
            label.isNotEmpty() -> label
            type == KeyGestureActionType.COMMIT_PAIRED -> "$text$rightText"
            type == KeyGestureActionType.COMMIT_TEXT -> when (text) {
                "\t" -> "Tab"
                "\u3000" -> "全角"
                else -> text
            }
            type == KeyGestureActionType.UNDO_LAST -> "撤回"
            type == KeyGestureActionType.REPEAT_LAST -> "重复"
            type == KeyGestureActionType.EDITOR_SHORTCUT -> when (editorShortcut) {
                EditorShortcutAction.SELECT_ALL -> "全"
                EditorShortcutAction.CUT -> "剪"
                EditorShortcutAction.COPY -> "复"
                EditorShortcutAction.PASTE -> "粘"
                null -> ""
            }
            else -> ""
        }
        return if (raw.length > maxChars) raw.take(maxChars) else raw
    }
}

data class KeyGestureProfile(
    val swipeUpMap: Map<Char, KeyGestureBinding>,
    val longPressMap: Map<Char, KeyGestureBinding>
) {
    fun resolveSwipeUp(keyChar: Char): KeyGestureBinding? {
        return swipeUpMap[keyChar.lowercaseChar()]?.takeIf { it.isEnabled }
    }

    fun resolveLongPress(keyChar: Char): KeyGestureBinding? {
        return longPressMap[keyChar.lowercaseChar()]?.takeIf { it.isEnabled }
    }
}

/**
 * 26-key QWERTY gesture profile (independent Tap / Swipe-Up / Long-Press) with
 * zero-dependency JSON serialization for `AppSettings.customKeyGesturesJson`.
 */
object KeyGestureConfig {

    val ALL_QWERTY_KEYS: List<Char> = listOf(
        'q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p',
        'a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l',
        'z', 'x', 'c', 'v', 'b', 'n', 'm'
    )

    fun defaultProfile(
        isChinese: Boolean,
        @Suppress("UNUSED_PARAMETER") showNumberRow: Boolean = false
    ): KeyGestureProfile {
        val swipeUp = linkedMapOf(
            'q' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "：“", label = "：“"),
            'w' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "？", label = "？"),
            'e' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "（", label = "（"),
            'r' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "）", label = "）"),
            't' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "\t", label = "Tab"),
            'y' to KeyGestureBinding(KeyGestureActionType.COMMIT_PAIRED, text = "《", rightText = "》", label = "《》"),
            'u' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "『", label = "『"),
            'i' to KeyGestureBinding(KeyGestureActionType.UNDO_LAST, label = "撤回"),
            'o' to KeyGestureBinding(KeyGestureActionType.COMMIT_PAIRED, text = "「", rightText = "」", label = "「」"),
            'p' to KeyGestureBinding(KeyGestureActionType.COMMIT_PAIRED, text = "『", rightText = "』", label = "『』"),
            'a' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "！", label = "！"),
            's' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "……", label = "……"),
            'd' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "、", label = "、"),
            'f' to KeyGestureBinding(KeyGestureActionType.REPEAT_LAST, label = "重复"),
            'g' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "·", label = "·"),
            'h' to KeyGestureBinding(KeyGestureActionType.COMMIT_PAIRED, text = "[", rightText = "]", label = "[]"),
            'j' to KeyGestureBinding(KeyGestureActionType.COMMIT_PAIRED, text = "“", rightText = "”", label = "“”"),
            'k' to KeyGestureBinding(KeyGestureActionType.COMMIT_PAIRED, text = "（", rightText = "）", label = "（）"),
            'l' to KeyGestureBinding(KeyGestureActionType.COMMIT_PAIRED, text = "〔", rightText = "〕", label = "〔〕"),
            'z' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "”", label = "”"),
            'x' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "→", label = "→"),
            'c' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "’", label = "’"),
            'v' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "——", label = "——"),
            'b' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "\u3000", label = "全角"),
            'n' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "=", label = "="),
            'm' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "@", label = "@")
        )

        val longPress = linkedMapOf(
            'q' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "1", label = "1"),
            'w' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "2", label = "2"),
            'e' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "3", label = "3"),
            'r' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "4", label = "4"),
            't' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "5", label = "5"),
            'y' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "6", label = "6"),
            'u' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "7", label = "7"),
            'i' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "8", label = "8"),
            'o' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "9", label = "9"),
            'p' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "0", label = "0"),
            'a' to KeyGestureBinding(
                KeyGestureActionType.EDITOR_SHORTCUT,
                label = "全",
                editorShortcut = EditorShortcutAction.SELECT_ALL
            ),
            's' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "@", label = "@"),
            'd' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "#", label = "#"),
            'f' to KeyGestureBinding(
                KeyGestureActionType.COMMIT_TEXT,
                text = if (isChinese) "¥" else "$",
                label = if (isChinese) "¥" else "$"
            ),
            'g' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "~", label = "~"),
            'h' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "-", label = "-"),
            'j' to KeyGestureBinding(
                KeyGestureActionType.COMMIT_PAIRED,
                text = if (isChinese) "“" else "\"",
                rightText = if (isChinese) "”" else "\"",
                label = if (isChinese) "“”" else "\"\""
            ),
            'k' to KeyGestureBinding(
                KeyGestureActionType.COMMIT_PAIRED,
                text = if (isChinese) "（" else "(",
                rightText = if (isChinese) "）" else ")",
                label = if (isChinese) "（）" else "()"
            ),
            'l' to KeyGestureBinding(
                KeyGestureActionType.COMMIT_TEXT,
                text = if (isChinese) "：" else ":",
                label = if (isChinese) "：" else ":"
            ),
            'z' to KeyGestureBinding(KeyGestureActionType.COMMIT_TEXT, text = "`", label = "`"),
            'x' to KeyGestureBinding(
                KeyGestureActionType.EDITOR_SHORTCUT,
                label = "剪",
                editorShortcut = EditorShortcutAction.CUT
            ),
            'c' to KeyGestureBinding(
                KeyGestureActionType.EDITOR_SHORTCUT,
                label = "复",
                editorShortcut = EditorShortcutAction.COPY
            ),
            'v' to KeyGestureBinding(
                KeyGestureActionType.EDITOR_SHORTCUT,
                label = "粘",
                editorShortcut = EditorShortcutAction.PASTE
            ),
            'b' to KeyGestureBinding(
                KeyGestureActionType.COMMIT_TEXT,
                text = if (isChinese) "、" else "/",
                label = if (isChinese) "、" else "/"
            ),
            'n' to KeyGestureBinding(
                KeyGestureActionType.COMMIT_TEXT,
                text = if (isChinese) "！" else "!",
                label = if (isChinese) "！" else "!"
            ),
            'm' to KeyGestureBinding(
                KeyGestureActionType.COMMIT_TEXT,
                text = if (isChinese) "？" else "?",
                label = if (isChinese) "？" else "?"
            )
        )

        return KeyGestureProfile(swipeUpMap = swipeUp, longPressMap = longPress)
    }

    fun toJson(profile: KeyGestureProfile): String {
        val sb = StringBuilder(1024)
        sb.append("{\"swipeUp\":")
        appendBindingMapJson(sb, profile.swipeUpMap)
        sb.append(",\"longPress\":")
        appendBindingMapJson(sb, profile.longPressMap)
        sb.append('}')
        return sb.toString()
    }

    /**
     * Parses `customKeyGesturesJson` into a [KeyGestureProfile], overlaying parsed entries
     * onto [fallback] so both full-map JSON and partial-override JSON work safely.
     */
    fun fromJson(
        json: String,
        fallback: KeyGestureProfile = defaultProfile(isChinese = true, showNumberRow = false)
    ): KeyGestureProfile {
        if (json.isBlank()) return fallback
        val root = runCatching { MiniJsonParser(json).parseObject() }.getOrNull() ?: return fallback

        val swipeUp = LinkedHashMap(fallback.swipeUpMap)
        val longPress = LinkedHashMap(fallback.longPressMap)

        (root["swipeUp"] as? Map<*, *>)?.let { rawMap ->
            applyParsedMap(rawMap, swipeUp)
        }
        (root["longPress"] as? Map<*, *>)?.let { rawMap ->
            applyParsedMap(rawMap, longPress)
        }

        return KeyGestureProfile(
            swipeUpMap = swipeUp,
            longPressMap = longPress
        )
    }

    private fun applyParsedMap(rawMap: Map<*, *>, target: MutableMap<Char, KeyGestureBinding>) {
        for ((k, v) in rawMap) {
            val keyStr = (k as? String)?.trim()?.lowercase(Locale.ROOT) ?: continue
            if (keyStr.length != 1) continue
            val ch = keyStr[0]
            if (ch !in 'a'..'z') continue
            val obj = v as? Map<*, *> ?: continue
            val binding = parseBindingObject(obj) ?: continue
            target[ch] = binding
        }
    }

    private fun parseBindingObject(obj: Map<*, *>): KeyGestureBinding? {
        val typeName = (obj["type"] as? String)?.trim()?.uppercase(Locale.ROOT) ?: return null
        val type = KeyGestureActionType.entries.firstOrNull { it.name == typeName } ?: return null
        val text = (obj["text"] as? String).orEmpty()
        val rightText = (obj["rightText"] as? String).orEmpty()
        val label = (obj["label"] as? String).orEmpty()
        val shortcutName = (obj["editorShortcut"] as? String)?.trim()?.uppercase(Locale.ROOT)
        val shortcut = shortcutName?.let { name ->
            EditorShortcutAction.entries.firstOrNull { it.name == name }
        }
        return KeyGestureBinding(
            type = type,
            text = text,
            rightText = rightText,
            label = label,
            editorShortcut = shortcut
        )
    }

    private fun appendBindingMapJson(sb: StringBuilder, map: Map<Char, KeyGestureBinding>) {
        sb.append('{')
        var first = true
        for ((ch, binding) in map) {
            if (!first) sb.append(',')
            first = false
            appendJsonString(sb, ch.lowercaseChar().toString())
            sb.append(':')
            appendBindingJson(sb, binding)
        }
        sb.append('}')
    }

    private fun appendBindingJson(sb: StringBuilder, b: KeyGestureBinding) {
        sb.append("{\"type\":")
        appendJsonString(sb, b.type.name)
        sb.append(",\"text\":")
        appendJsonString(sb, b.text)
        sb.append(",\"rightText\":")
        appendJsonString(sb, b.rightText)
        sb.append(",\"label\":")
        appendJsonString(sb, b.label)
        if (b.editorShortcut != null) {
            sb.append(",\"editorShortcut\":")
            appendJsonString(sb, b.editorShortcut.name)
        }
        sb.append('}')
    }

    private fun appendJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class MiniJsonParser(private val src: String) {
        private var pos = 0

        fun parseObject(): Map<String, Any?> {
            skipWs()
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return map
            }
            while (pos < src.length) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                skipWs()
                val value = parseValue()
                map[key] = value
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
            throw IllegalArgumentException("Unterminated JSON object")
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return list
            }
            while (pos < src.length) {
                skipWs()
                list.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
            throw IllegalArgumentException("Unterminated JSON array")
        }

        private fun parseValue(): Any? {
            skipWs()
            return when (val c = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected char '$c' at $pos")
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw IllegalArgumentException("Invalid unicode escape")
                                val hex = src.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseLiteral(expected: String, result: Any?): Any? {
            if (!src.startsWith(expected, pos)) {
                throw IllegalArgumentException("Invalid literal at $pos")
            }
            pos += expected.length
            return result
        }

        private fun parseNumber(): String {
            val start = pos
            while (pos < src.length && src[pos] in "0123456789+-.eE") {
                pos++
            }
            if (start == pos) throw IllegalArgumentException("Invalid number at $pos")
            return src.substring(start, pos)
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) {
                pos++
            }
        }

        private fun peek(): Char {
            if (pos >= src.length) throw IllegalArgumentException("Unexpected end of JSON")
            return src[pos]
        }

        private fun expect(expected: Char) {
            if (pos >= src.length || src[pos] != expected) {
                throw IllegalArgumentException("Expected '$expected' at $pos")
            }
            pos++
        }
    }
}
