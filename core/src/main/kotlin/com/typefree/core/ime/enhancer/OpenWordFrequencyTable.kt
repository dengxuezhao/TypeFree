package com.typefree.core.ime.enhancer

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Lightweight parser for open-source Rime frequency dictionaries (`cn_dicts/base.dict.yaml`,
 * `cn_dicts/8105.dict.yaml`). Used ONLY as a fallback frequency ranker for live 26-slot
 * empty-code continuations on precompiled `.table.bin` bundles where cross-code `.dict.yaml`
 * line order no longer exists.
 *
 * Plain-text `.dict.yaml` imports use [BriefPrefixIndex.lookupNaturalContinuations] directly
 * and never invoke this table.
 */
object OpenWordFrequencyTable {
    const val DEFAULT_MAX_ENTRIES = 80_000

    fun loadFromRimeDictFiles(
        files: List<File>,
        maxEntries: Int = DEFAULT_MAX_ENTRIES
    ): Map<String, Int> {
        if (files.isEmpty() || maxEntries <= 0) return emptyMap()
        val freqMap = HashMap<String, Int>(minOf(32_768, maxEntries))
        for (file in files) {
            if (!file.isFile) continue
            runCatching {
                BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 32 * 1024).use { reader ->
                    parseLinesInto(reader.lineSequence(), freqMap, maxEntries)
                }
            }
            if (freqMap.size >= maxEntries) break
        }
        return freqMap
    }

    fun parseLines(
        lines: Sequence<String>,
        maxEntries: Int = DEFAULT_MAX_ENTRIES
    ): Map<String, Int> {
        if (maxEntries <= 0) return emptyMap()
        val freqMap = HashMap<String, Int>(minOf(4096, maxEntries))
        parseLinesInto(lines, freqMap, maxEntries)
        return freqMap
    }

    private fun parseLinesInto(
        lines: Sequence<String>,
        out: MutableMap<String, Int>,
        maxEntries: Int
    ) {
        var inYamlHeader = false
        for (rawLine in lines) {
            if (out.size >= maxEntries) break
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
            val tab2 = rawLine.indexOf('\t', tab1 + 1)
            if (tab2 <= tab1 + 1 || tab2 >= rawLine.length - 1) continue

            val weightEnd = rawLine.indexOf('\t', tab2 + 1).let { if (it < 0) rawLine.length else it }
            val weight = rawLine.substring(tab2 + 1, weightEnd).trim().toIntOrNull() ?: continue
            if (weight <= 0) continue

            val word = rawLine.substring(0, tab1).trim()
            if (word.isEmpty()) continue
            val existing = out[word]
            if (existing == null || weight > existing) {
                out[word] = weight
            }
        }
    }
}
