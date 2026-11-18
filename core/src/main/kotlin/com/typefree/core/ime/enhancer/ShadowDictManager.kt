package com.typefree.core.ime.enhancer

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Manages per-schema candidate pinning (`pinnedByCode`) and global word blocking (`blockedWords`)
 * persisted atomically to `<schemaId>.shadow.yaml` in UTF-8 without modifying `.dict.yaml`.
 */
class ShadowDictManager(
    private val schemaDir: File,
    private val schemaId: String
) {
    data class ShadowRuleState(
        val pinnedByCode: Map<String, List<String>> = emptyMap(),
        val blockedWords: Set<String> = emptySet()
    )

    private val shadowFile: File
        get() = File(schemaDir, "$schemaId.shadow.yaml")

    @Synchronized
    fun loadRules(): ShadowRuleState {
        val file = shadowFile
        if (!file.exists() || !file.isFile) return ShadowRuleState()
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrNull() ?: return ShadowRuleState()
        return parseYaml(lines)
    }

    @Synchronized
    fun pinWord(code: String, word: String) {
        val cleanCode = code.trim()
        val cleanWord = word.trim()
        if (cleanCode.isEmpty() || cleanWord.isEmpty()) return

        val cur = loadRules()
        val nextPinned = LinkedHashMap<String, List<String>>(cur.pinnedByCode.size + 1)
        for ((k, v) in cur.pinnedByCode) {
            nextPinned[k] = v.toList()
        }
        val existing = nextPinned[cleanCode].orEmpty()
        val updatedList = ArrayList<String>(existing.size + 1)
        updatedList.add(cleanWord)
        for (item in existing) {
            if (item != cleanWord) updatedList.add(item)
        }
        nextPinned[cleanCode] = updatedList

        val nextBlocked = cur.blockedWords - cleanWord
        persistRules(ShadowRuleState(pinnedByCode = nextPinned, blockedWords = nextBlocked))
    }

    @Synchronized
    fun unpinWord(code: String, word: String) {
        val cleanCode = code.trim()
        val cleanWord = word.trim()
        if (cleanCode.isEmpty() || cleanWord.isEmpty()) return

        val cur = loadRules()
        val existing = cur.pinnedByCode[cleanCode] ?: return
        val filtered = existing.filter { it != cleanWord }
        if (filtered.size == existing.size) return

        val nextPinned = LinkedHashMap<String, List<String>>(cur.pinnedByCode.size)
        for ((k, v) in cur.pinnedByCode) {
            if (k == cleanCode) {
                if (filtered.isNotEmpty()) nextPinned[k] = filtered
            } else {
                nextPinned[k] = v
            }
        }
        persistRules(cur.copy(pinnedByCode = nextPinned))
    }

    @Synchronized
    fun blockWord(word: String) {
        val cleanWord = word.trim()
        if (cleanWord.isEmpty()) return

        val cur = loadRules()
        if (cleanWord in cur.blockedWords) return
        val nextBlocked = LinkedHashSet<String>(cur.blockedWords.size + 1)
        nextBlocked.addAll(cur.blockedWords)
        nextBlocked.add(cleanWord)

        val nextPinned = LinkedHashMap<String, List<String>>(cur.pinnedByCode.size)
        for ((k, v) in cur.pinnedByCode) {
            val kept = v.filter { it != cleanWord }
            if (kept.isNotEmpty()) nextPinned[k] = kept
        }
        persistRules(ShadowRuleState(pinnedByCode = nextPinned, blockedWords = nextBlocked))
    }

    @Synchronized
    fun unblockWord(word: String) {
        val cleanWord = word.trim()
        if (cleanWord.isEmpty()) return

        val cur = loadRules()
        if (cleanWord !in cur.blockedWords) return
        persistRules(cur.copy(blockedWords = cur.blockedWords - cleanWord))
    }

    @Synchronized
    fun clearAll() {
        persistRules(ShadowRuleState())
    }

    private fun persistRules(state: ShadowRuleState) {
        val yaml = serializeYaml(state)
        writeAtomically(yaml)
    }

    private fun writeAtomically(content: String) {
        schemaDir.mkdirs()
        val target = shadowFile
        val tmp = File(schemaDir, "$schemaId.shadow.yaml.tmp.${System.nanoTime()}")
        tmp.writeText(content, StandardCharsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    target.writeText(content, StandardCharsets.UTF_8)
                    tmp.delete()
                }
            }
        }
    }

    private fun serializeYaml(state: ShadowRuleState): String = buildString {
        appendLine("# TypeFree Shadow Dictionary Rules (UTF-8)")
        if (state.pinnedByCode.isEmpty()) {
            appendLine("pinned: {}")
        } else {
            appendLine("pinned:")
            for ((code, words) in state.pinnedByCode) {
                if (words.isEmpty()) continue
                appendLine("  \"${escapeYaml(code)}\":")
                for (w in words) {
                    appendLine("    - \"${escapeYaml(w)}\"")
                }
            }
        }
        if (state.blockedWords.isEmpty()) {
            appendLine("blocked: []")
        } else {
            appendLine("blocked:")
            for (w in state.blockedWords) {
                appendLine("  - \"${escapeYaml(w)}\"")
            }
        }
    }

    private fun parseYaml(lines: List<String>): ShadowRuleState {
        val pinned = LinkedHashMap<String, MutableList<String>>()
        val blocked = LinkedHashSet<String>()

        var section = ""
        var currentCode: String? = null

        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)

            if (indent == 0) {
                currentCode = null
                when {
                    trimmed == "pinned:" -> section = "pinned"
                    trimmed == "pinned: {}" -> section = ""
                    trimmed == "blocked:" -> section = "blocked"
                    trimmed == "blocked: []" -> section = ""
                    else -> section = ""
                }
                continue
            }

            when (section) {
                "pinned" -> {
                    if (trimmed.startsWith("- ")) {
                        val word = unquoteYaml(trimmed.removePrefix("- ").trim())
                        val code = currentCode
                        if (!code.isNullOrEmpty() && word.isNotEmpty()) {
                            val list = pinned.getOrPut(code) { ArrayList() }
                            if (word !in list) list.add(word)
                        }
                    } else if (trimmed.endsWith(":")) {
                        val rawKey = trimmed.removeSuffix(":").trim()
                        val code = unquoteYaml(rawKey)
                        currentCode = code.ifEmpty { null }
                    }
                }
                "blocked" -> {
                    if (trimmed.startsWith("- ")) {
                        val word = unquoteYaml(trimmed.removePrefix("- ").trim())
                        if (word.isNotEmpty()) blocked.add(word)
                    }
                }
            }
        }

        val immutablePinned = LinkedHashMap<String, List<String>>(pinned.size)
        for ((k, v) in pinned) {
            if (v.isNotEmpty()) immutablePinned[k] = v.toList()
        }
        return ShadowRuleState(pinnedByCode = immutablePinned, blockedWords = blocked)
    }

    private fun escapeYaml(value: String): String {
        val sb = StringBuilder(value.length + 4)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unquoteYaml(raw: String): String {
        val t = raw.trim()
        if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) {
            val inner = t.substring(1, t.length - 1)
            val sb = StringBuilder(inner.length)
            var i = 0
            while (i < inner.length) {
                val ch = inner[i]
                if (ch == '\\' && i + 1 < inner.length) {
                    when (val nxt = inner[i + 1]) {
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        else -> sb.append(nxt)
                    }
                    i += 2
                } else {
                    sb.append(ch)
                    i++
                }
            }
            return sb.toString()
        }
        if (t.length >= 2 && t.startsWith('\'') && t.endsWith('\'')) {
            return t.substring(1, t.length - 1).replace("''", "'")
        }
        return t
    }
}
