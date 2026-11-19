package com.typefree.core.ime.importer

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Edits Rime `custom_phrase` user files (`<schemaId>_top.txt` for top-pinned entries and
 * `<schemaId>_user.txt` for user-created phrases) in UTF-8 while preserving `# ...` header lines.
 */
class CustomUserPhraseFileEditor(
    private val schemaDir: File,
    private val schemaId: String
) {
    data class UserPhraseEntry(
        val word: String,
        val code: String,
        val isTopPinned: Boolean
    )

    init {
        require(schemaId.isNotBlank() && !schemaId.contains('/') && !schemaId.contains('\\') && !schemaId.contains("..")) {
            "无效的方案标识: $schemaId"
        }
    }

    private fun targetFile(isTopPinned: Boolean): File {
        val suffix = if (isTopPinned) "top" else "user"
        return File(schemaDir, "${schemaId}_${suffix}.txt")
    }

    private fun defaultHeaderLines(isTopPinned: Boolean): List<String> {
        val dbName = "${schemaId}_${if (isTopPinned) "top" else "user"}"
        return listOf(
            "# Rime table",
            "# coding: utf-8",
            "#@/db_name\t$dbName",
            "#@/db_type\tstabledb"
        )
    }

    private data class ParsedFile(
        val headerLines: List<String>,
        val bodyLines: MutableList<String>,
        val entries: MutableList<UserPhraseEntry>
    )

    private fun readParsedFile(isTopPinned: Boolean): ParsedFile {
        val file = targetFile(isTopPinned)
        if (!file.isFile) {
            return ParsedFile(
                headerLines = defaultHeaderLines(isTopPinned),
                bodyLines = mutableListOf(),
                entries = mutableListOf()
            )
        }
        val rawLines = file.readLines(StandardCharsets.UTF_8)
        val headerLines = ArrayList<String>()
        val bodyLines = ArrayList<String>()
        val entries = ArrayList<UserPhraseEntry>()
        var inHeader = true
        for (line in rawLines) {
            val clean = line.removePrefix("\uFEFF")
            if (inHeader && (clean.startsWith("#") || clean.isBlank())) {
                if (clean.startsWith("#")) {
                    headerLines.add(clean)
                }
                continue
            }
            inHeader = false
            if (clean.isBlank()) continue
            val tabIdx = clean.indexOf('\t')
            if (tabIdx > 0 && tabIdx < clean.length - 1) {
                val word = clean.substring(0, tabIdx).trim()
                val code = clean.substring(tabIdx + 1).substringBefore('\t').trim()
                if (word.isNotEmpty() && code.isNotEmpty()) {
                    bodyLines.add(clean)
                    entries.add(UserPhraseEntry(word = word, code = code, isTopPinned = isTopPinned))
                }
            }
        }
        val effectiveHeaders = if (headerLines.isNotEmpty()) headerLines else defaultHeaderLines(isTopPinned)
        return ParsedFile(effectiveHeaders, bodyLines, entries)
    }

    private fun writeParsedFile(isTopPinned: Boolean, parsed: ParsedFile) {
        schemaDir.mkdirs()
        val file = targetFile(isTopPinned)
        val content = buildString {
            parsed.headerLines.forEach { appendLine(it) }
            parsed.bodyLines.forEach { appendLine(it) }
        }
        file.writeText(content, StandardCharsets.UTF_8)
    }

    fun listPhrases(isTopPinned: Boolean): List<UserPhraseEntry> {
        return readParsedFile(isTopPinned).entries
    }

    fun addPhrase(word: String, code: String, isTopPinned: Boolean) {
        require(!word.contains('\r') && !word.contains('\n') && !word.contains('\t')) {
            "词条不能包含换行符或制表符"
        }
        require(!code.contains('\r') && !code.contains('\n') && !code.contains('\t')) {
            "编码不能包含换行符或制表符"
        }
        val cleanWord = word.trim()
        val cleanCode = code.trim()
        require(cleanWord.isNotEmpty() && cleanCode.isNotEmpty()) {
            "词条和编码不能为空"
        }

        val parsed = readParsedFile(isTopPinned)
        val alreadyExists = parsed.entries.any { it.word == cleanWord && it.code == cleanCode }
        if (alreadyExists) return

        parsed.bodyLines.add("$cleanWord\t$cleanCode")
        parsed.entries.add(UserPhraseEntry(word = cleanWord, code = cleanCode, isTopPinned = isTopPinned))
        writeParsedFile(isTopPinned, parsed)
    }

    fun removePhrase(word: String, code: String, isTopPinned: Boolean): Boolean {
        val file = targetFile(isTopPinned)
        if (!file.isFile) return false
        val cleanWord = word.trim()
        val cleanCode = code.trim()
        if (cleanWord.isEmpty() || cleanCode.isEmpty()) return false

        val parsed = readParsedFile(isTopPinned)
        var removed = false
        val keptBody = ArrayList<String>(parsed.bodyLines.size)
        for (i in parsed.entries.indices) {
            val entry = parsed.entries[i]
            if (entry.word == cleanWord && entry.code == cleanCode) {
                removed = true
            } else {
                keptBody.add(parsed.bodyLines[i])
            }
        }
        if (!removed) return false

        parsed.bodyLines.clear()
        parsed.bodyLines.addAll(keptBody)
        writeParsedFile(isTopPinned, parsed)
        return true
    }
}
