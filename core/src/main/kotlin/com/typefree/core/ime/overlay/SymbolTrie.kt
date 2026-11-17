package com.typefree.core.ime.overlay

import com.typefree.core.ime.coordinator.CommitAction
import com.typefree.core.ime.coordinator.DirectCommandType
import com.typefree.core.ime.coordinator.EnhancedCandidate

data class OverlaySymbolEntry(
    val key: String,
    val displayText: String,
    val label: String = "",
    val action: CommitAction
)

/**
 * Prefix trie supporting 1~2+ key non-code-element quick symbols with action payloads
 * (`PlainText`, `PairedText`, `UndoLastCommit`, `RepeatLastCommit`, `Command`).
 */
class SymbolTrie(
    val guideKey: Char = ';',
    val doubleTapCommit: String = "；",
    val doubleTapAsciiCommit: String = ";",
    val showAllOnEnter: Boolean = true,
    entries: List<OverlaySymbolEntry> = defaultXiaoheEntries()
) {
    class Node {
        var entry: OverlaySymbolEntry? = null
        val children: LinkedHashMap<Char, Node> = LinkedHashMap()
        val hasChildren: Boolean get() = children.isNotEmpty()
    }

    private val root = Node()
    private val orderedEntries: List<OverlaySymbolEntry> = entries.toList()

    init {
        for (item in orderedEntries) {
            if (item.key.isEmpty()) continue
            var cur = root
            for (ch in item.key) {
                cur = cur.children.getOrPut(ch) { Node() }
            }
            cur.entry = item
        }
    }

    val size: Int
        get() = orderedEntries.size

    fun findNode(prefix: String): Node? {
        var cur = root
        for (ch in prefix) {
            cur = cur.children[ch] ?: return null
        }
        return cur
    }

    fun collectCandidates(prefix: String, lastCommittedPreview: String = ""): List<EnhancedCandidate> {
        if (prefix.isEmpty()) {
            if (!showAllOnEnter) return emptyList()
            return orderedEntries
                .filter { it.key.length == 1 }
                .map { toCandidate(it, showKeyHint = true, lastCommittedPreview = lastCommittedPreview) }
        }
        val node = findNode(prefix) ?: return emptyList()
        val out = ArrayList<EnhancedCandidate>(12)
        node.entry?.let { primary ->
            out.add(toCandidate(primary, showKeyHint = false, lastCommittedPreview = lastCommittedPreview))
        }
        fun dfs(cur: Node) {
            for ((_, child) in cur.children) {
                child.entry?.let { e ->
                    out.add(toCandidate(e, showKeyHint = true, lastCommittedPreview = lastCommittedPreview))
                }
                dfs(child)
            }
        }
        dfs(node)
        return out
    }

    private fun toCandidate(
        entry: OverlaySymbolEntry,
        showKeyHint: Boolean,
        lastCommittedPreview: String
    ): EnhancedCandidate {
        val display = when (val act = entry.action) {
            is CommitAction.RepeatLastCommit -> {
                if (lastCommittedPreview.isNotEmpty()) lastCommittedPreview else "↻重复"
            }
            is CommitAction.UndoLastCommit -> "↶撤回"
            is CommitAction.PairedText -> "${act.left}${act.right}"
            is CommitAction.PlainText -> when (act.text) {
                "\u3000" -> "␣全角"
                "\t" -> "⇥Tab"
                else -> entry.displayText
            }
            else -> entry.displayText
        }
        val hint = buildString {
            if (showKeyHint) append(entry.key)
            if (entry.label.isNotEmpty()) {
                if (isNotEmpty()) append("·")
                append(entry.label)
            }
        }
        return EnhancedCandidate(
            displayText = display,
            commitText = when (val a = entry.action) {
                is CommitAction.PlainText -> a.text
                is CommitAction.PairedText -> a.left + a.right
                else -> display
            },
            codeHint = hint,
            action = entry.action
        )
    }

    companion object {
        /**
         * Parses `quick_symbols.yaml` preserving full-width space (`\u3000`) and escape sequences (`\t`).
         */
        fun parseQuickSymbolsYaml(yamlContent: String): SymbolTrie {
            var guideKey = ';'
            var doubleTap = "；"
            var doubleTapAscii = ";"
            var showAll = true
            val entries = ArrayList<OverlaySymbolEntry>(40)

            val entryRegex = Regex("""^\s*([a-zA-Z0-9]{1,4}):\s*\{(.*)}\s*$""")

            yamlContent.lineSequence().forEach { rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                when {
                    trimmed.startsWith("guide_key:") -> {
                        val v = unquoteYaml(trimmed.substringAfter("guide_key:"))
                        if (v.isNotEmpty()) guideKey = v[0]
                    }
                    trimmed.startsWith("double_tap_commit:") -> {
                        doubleTap = unquoteYaml(trimmed.substringAfter("double_tap_commit:"))
                    }
                    trimmed.startsWith("double_tap_ascii_commit:") -> {
                        doubleTapAscii = unquoteYaml(trimmed.substringAfter("double_tap_ascii_commit:"))
                    }
                    trimmed.startsWith("show_all_on_enter:") -> {
                        showAll = trimmed.substringAfter("show_all_on_enter:").trim().toBooleanStrictOrNull() ?: true
                    }
                    else -> {
                        val m = entryRegex.matchEntire(rawLine)
                        if (m != null) {
                            val key = m.groupValues[1]
                            val props = parseInlineObject(m.groupValues[2])
                            val type = props["type"] ?: "plain"
                            val label = props["label"].orEmpty()
                            val parsed = when (type) {
                                "pair" -> {
                                    val left = props["left"].orEmpty()
                                    val right = props["right"].orEmpty()
                                    OverlaySymbolEntry(
                                        key = key,
                                        displayText = left + right,
                                        label = label,
                                        action = CommitAction.PairedText(left, right)
                                    )
                                }
                                "undo_last_commit" -> OverlaySymbolEntry(
                                    key = key,
                                    displayText = "↶撤回",
                                    label = label.ifEmpty { "撤回上屏" },
                                    action = CommitAction.UndoLastCommit
                                )
                                "repeat_last_commit" -> OverlaySymbolEntry(
                                    key = key,
                                    displayText = "↻重复",
                                    label = label.ifEmpty { "重复上屏" },
                                    action = CommitAction.RepeatLastCommit
                                )
                                "open_jev_copilot" -> OverlaySymbolEntry(
                                    key = key,
                                    displayText = "✦军师",
                                    label = label.ifEmpty { "Jev军师" },
                                    action = CommitAction.Command(DirectCommandType.OPEN_JEV_COPILOT)
                                )
                                else -> {
                                    val text = props["text"].orEmpty()
                                    OverlaySymbolEntry(
                                        key = key,
                                        displayText = text,
                                        label = label,
                                        action = CommitAction.PlainText(text)
                                    )
                                }
                            }
                            entries.add(parsed)
                        }
                    }
                }
            }

            return SymbolTrie(
                guideKey = guideKey,
                doubleTapCommit = doubleTap,
                doubleTapAsciiCommit = doubleTapAscii,
                showAllOnEnter = showAll,
                entries = if (entries.isNotEmpty()) entries else defaultXiaoheEntries()
            )
        }

        fun defaultXiaohe(): SymbolTrie = SymbolTrie()

        fun defaultXiaoheEntries(): List<OverlaySymbolEntry> = listOf(
            OverlaySymbolEntry("q", "：“", action = CommitAction.PlainText("：“")),
            OverlaySymbolEntry("w", "？", action = CommitAction.PlainText("？")),
            OverlaySymbolEntry("e", "（", action = CommitAction.PlainText("（")),
            OverlaySymbolEntry("r", "）", action = CommitAction.PlainText("）")),
            OverlaySymbolEntry("t", "\t", label = "Tab", action = CommitAction.PlainText("\t")),
            OverlaySymbolEntry("y", "《》", action = CommitAction.PairedText("《", "》")),
            OverlaySymbolEntry("u", "『", action = CommitAction.PlainText("『")),
            OverlaySymbolEntry("i", "↶撤回", label = "撤回上屏", action = CommitAction.UndoLastCommit),
            OverlaySymbolEntry("o", "「」", action = CommitAction.PairedText("「", "」")),
            OverlaySymbolEntry("p", "『』", action = CommitAction.PairedText("『", "』")),
            OverlaySymbolEntry("a", "！", action = CommitAction.PlainText("！")),
            OverlaySymbolEntry("s", "……", action = CommitAction.PlainText("……")),
            OverlaySymbolEntry("d", "、", action = CommitAction.PlainText("、")),
            OverlaySymbolEntry("f", "↻重复", label = "重复上屏", action = CommitAction.RepeatLastCommit),
            OverlaySymbolEntry("g", "·", action = CommitAction.PlainText("·")),
            OverlaySymbolEntry("h", "[]", action = CommitAction.PairedText("[", "]")),
            OverlaySymbolEntry("j", "“”", action = CommitAction.PairedText("“", "”")),
            OverlaySymbolEntry("k", "（）", action = CommitAction.PairedText("（", "）")),
            OverlaySymbolEntry("l", "〔〕", action = CommitAction.PairedText("〔", "〕")),
            OverlaySymbolEntry("z", "”", action = CommitAction.PlainText("”")),
            OverlaySymbolEntry("x", "→", action = CommitAction.PlainText("→")),
            OverlaySymbolEntry("c", "’", action = CommitAction.PlainText("’")),
            OverlaySymbolEntry("v", "——", action = CommitAction.PlainText("——")),
            OverlaySymbolEntry("b", "　", label = "全角空格", action = CommitAction.PlainText("\u3000")),
            OverlaySymbolEntry("n", "=", action = CommitAction.PlainText("=")),
            OverlaySymbolEntry("m", "@", action = CommitAction.PlainText("@")),
            OverlaySymbolEntry("mq", "①", action = CommitAction.PlainText("①")),
            OverlaySymbolEntry("mw", "②", action = CommitAction.PlainText("②")),
            OverlaySymbolEntry("me", "③", action = CommitAction.PlainText("③")),
            OverlaySymbolEntry("mr", "④", action = CommitAction.PlainText("④")),
            OverlaySymbolEntry("mt", "⑤", action = CommitAction.PlainText("⑤")),
            OverlaySymbolEntry("my", "⑥", action = CommitAction.PlainText("⑥")),
            OverlaySymbolEntry("mu", "⑦", action = CommitAction.PlainText("⑦")),
            OverlaySymbolEntry("mi", "⑧", action = CommitAction.PlainText("⑧")),
            OverlaySymbolEntry("mo", "⑨", action = CommitAction.PlainText("⑨")),
            OverlaySymbolEntry("mp", "⑩", action = CommitAction.PlainText("⑩"))
        )

        private fun parseInlineObject(body: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            val tokenRegex = Regex("""([a-zA-Z0-9_]+)\s*:\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|[^,}]+)""")
            tokenRegex.findAll(body).forEach { m ->
                val k = m.groupValues[1].trim()
                val v = unquoteYaml(m.groupValues[2])
                result[k] = v
            }
            return result
        }

        private fun unquoteYaml(raw: String): String {
            val t = raw.trim()
            if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) {
                return buildString {
                    var i = 1
                    while (i < t.length - 1) {
                        val c = t[i]
                        if (c == '\\' && i + 1 < t.length - 1) {
                            when (val next = t[i + 1]) {
                                't' -> append('\t')
                                'n' -> append('\n')
                                'r' -> append('\r')
                                '"' -> append('"')
                                '\\' -> append('\\')
                                else -> append(next)
                            }
                            i += 2
                        } else {
                            append(c)
                            i += 1
                        }
                    }
                }
            }
            if (t.length >= 2 && t.startsWith('\'') && t.endsWith('\'')) {
                return t.substring(1, t.length - 1)
            }
            return t
        }
    }
}
