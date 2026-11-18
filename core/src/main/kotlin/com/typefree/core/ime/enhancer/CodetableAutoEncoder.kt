package com.typefree.core.ime.enhancer

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class EncodedPhrase(
    val word: String,
    val code: String
)

/**
 * Automatic phrase encoder (disabled by default) deriving 4-letter codetable entries from consecutive
 * single-char / short-unit commits and persisting them safely as `word\tcode\t1` in `<schemaId>_user.txt`.
 */
class CodetableAutoEncoder(
    private val userDictFile: File,
    private val schemaId: String
) {
    private data class CharSlot(val charText: String, val codePart: String)

    private val sessionSlots = ArrayList<CharSlot>(4)
    private val codeToWords = LinkedHashMap<String, MutableList<String>>()
    private val headerLines = ArrayList<String>()
    private val entryLines = ArrayList<Pair<String, String>>() // (word, rawLine)

    init {
        loadFromDisk()
    }

    @Synchronized
    private fun loadFromDisk() {
        codeToWords.clear()
        headerLines.clear()
        entryLines.clear()

        if (!userDictFile.exists() || !userDictFile.isFile) {
            headerLines.addAll(defaultHeaderLines())
            return
        }

        val lines = runCatching { userDictFile.readLines(StandardCharsets.UTF_8) }.getOrNull() ?: emptyList()
        var sawBody = false
        for (raw in lines) {
            val trimmed = raw.trim()
            if (!sawBody && (trimmed.isEmpty() || trimmed.startsWith("#"))) {
                headerLines.add(raw)
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            sawBody = true
            val parts = raw.split('\t')
            if (parts.size < 2) continue
            val word = parts[0].trim()
            val code = parts[1].trim()
            if (word.isEmpty() || code.isEmpty()) continue
            entryLines.add(word to raw)
            val list = codeToWords.getOrPut(code) { ArrayList(2) }
            if (word !in list) list.add(word)
        }

        if (headerLines.isEmpty()) {
            headerLines.addAll(defaultHeaderLines())
        }
    }

    private fun defaultHeaderLines(): List<String> = listOf(
        "# Rime table",
        "# coding: utf-8",
        "#@/db_name\t${schemaId}_user",
        "#@/db_type\ttabledb"
    )

    @Synchronized
    fun resetSession() {
        sessionSlots.clear()
    }

    @Synchronized
    fun lookupExact(code: String): List<String> {
        val clean = code.trim()
        if (clean.isEmpty()) return emptyList()
        return codeToWords[clean]?.toList().orEmpty()
    }

    @Synchronized
    fun recordCommittedUnit(text: String, code: String): EncodedPhrase? {
        val cleanText = text.trim()
        val cleanCode = code.trim().lowercase()
        if (cleanText.isEmpty() || cleanCode.isEmpty() || !cleanCode.all { it in 'a'..'z' }) {
            sessionSlots.clear()
            return null
        }

        val chars = extractAllVisibleChars(cleanText)
        if (chars.isEmpty() || chars.size > 2 || !chars.all(::isPureHanziUnit)) {
            sessionSlots.clear()
            return null
        }

        val newSlots = when (chars.size) {
            1 -> listOf(CharSlot(chars[0], cleanCode))
            2 -> {
                if (cleanCode.length < 4) {
                    sessionSlots.clear()
                    return null
                }
                listOf(
                    CharSlot(chars[0], cleanCode.substring(0, 2)),
                    CharSlot(chars[1], cleanCode.substring(2, 4))
                )
            }
            else -> emptyList()
        }

        if (sessionSlots.size + newSlots.size > 4) {
            sessionSlots.clear()
        }
        sessionSlots.addAll(newSlots)

        val phraseCode = encodeSlots(sessionSlots)
        val phraseWord = sessionSlots.joinToString(separator = "") { it.charText }
        if (sessionSlots.size >= 4) {
            sessionSlots.clear()
        }
        if (phraseCode == null) {
            return null
        }

        addPhraseEntry(phraseWord, phraseCode)
        return EncodedPhrase(word = phraseWord, code = phraseCode)
    }

    @Synchronized
    fun removePhrase(word: String, code: String): Boolean {
        val cleanWord = word.trim()
        val cleanCode = code.trim()
        if (cleanWord.isEmpty() || cleanCode.isEmpty()) return false

        val wordsForCode = codeToWords[cleanCode] ?: return false
        val removedFromMap = wordsForCode.remove(cleanWord)
        if (!removedFromMap) return false
        if (wordsForCode.isEmpty()) {
            codeToWords.remove(cleanCode)
        }

        val beforeSize = entryLines.size
        entryLines.removeAll { (w, raw) ->
            val parts = raw.split('\t')
            w == cleanWord && parts.getOrNull(1)?.trim() == cleanCode
        }
        if (entryLines.size != beforeSize) {
            persistToDisk()
        }
        return true
    }

    private fun encodeSlots(slots: List<CharSlot>): String? {
        return when (slots.size) {
            2 -> {
                val c1 = slots[0].codePart
                val c2 = slots[1].codePart
                if (c1.length < 2 || c2.length < 2) null else c1.substring(0, 2) + c2.substring(0, 2)
            }
            3 -> {
                val c1 = slots[0].codePart
                val c2 = slots[1].codePart
                val c3 = slots[2].codePart
                if (c1.isEmpty() || c2.isEmpty() || c3.length < 2) null
                else "${c1[0]}${c2[0]}${c3.substring(0, 2)}"
            }
            4 -> {
                val c1 = slots[0].codePart
                val c2 = slots[1].codePart
                val c3 = slots[2].codePart
                val c4 = slots[3].codePart
                if (c1.isEmpty() || c2.isEmpty() || c3.isEmpty() || c4.isEmpty()) null
                else "${c1[0]}${c2[0]}${c3[0]}${c4[0]}"
            }
            else -> null
        }
    }

    private fun addPhraseEntry(word: String, code: String) {
        val list = codeToWords.getOrPut(code) { ArrayList(2) }
        if (word in list) return
        list.add(word)
        entryLines.add(word to "$word\t$code\t1")
        persistToDisk()
    }

    private fun persistToDisk() {
        val content = buildString {
            for (h in headerLines) {
                appendLine(h)
            }
            for ((_, raw) in entryLines) {
                appendLine(raw)
            }
        }
        userDictFile.parentFile?.mkdirs()
        val tmp = File(userDictFile.parentFile, "${userDictFile.name}.tmp.${System.nanoTime()}")
        tmp.writeText(content, StandardCharsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(),
                userDictFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            try {
                Files.move(tmp.toPath(), userDictFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                if (userDictFile.exists()) userDictFile.delete()
                if (!tmp.renameTo(userDictFile)) {
                    userDictFile.writeText(content, StandardCharsets.UTF_8)
                    tmp.delete()
                }
            }
        }
    }

    private fun extractAllVisibleChars(text: String): List<String> {
        val out = ArrayList<String>(text.length)
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            val len = Character.charCount(cp)
            out.add(text.substring(offset, offset + len))
            offset += len
        }
        return out
    }

    private fun isPureHanziUnit(singleChar: String): Boolean {
        if (singleChar.isEmpty()) return false
        val cp = singleChar.codePointAt(0)
        return Character.charCount(cp) == singleChar.length && Utf8Safety.isCjkCodePoint(cp)
    }
}
