package com.typefree.ime.ime.predict

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Dynamic prefix-continuation index built offline from installed Rime dictionary files
 * (e.g. `cn_dicts/base.dict.yaml`, `cn_dicts/ext.dict.yaml`, `cn_dicts/others.dict.yaml`).
 *
 * For a committed word like "人工" or "不好", this index yields high-weight suffixes
 * ("智能", "成本", "意思", ...) from the active lexicon with ~0ms O(1) lookup and zero
 * extra download footprint.
 */
class LexiconPrefixIndex(
    private val maxSuffixesPerPrefix: Int = DEFAULT_MAX_SUFFIXES_PER_PREFIX,
    private val maxPrefixes: Int = DEFAULT_MAX_PREFIXES
) {

    @Volatile
    private var prefixTable: Map<String, List<String>> = emptyMap()

    @Volatile
    var indexedEntryCount: Int = 0
        private set

    val prefixCount: Int
        get() = prefixTable.size

    fun querySuffixes(prefix: String, limit: Int = DEFAULT_MAX_SUFFIXES_PER_PREFIX): List<String> {
        if (limit <= 0) return emptyList()
        val clean = prefix.trim()
        if (clean.isEmpty() || clean.length > MAX_PREFIX_LEN) return emptyList()
        val hits = prefixTable[clean] ?: return emptyList()
        return if (hits.size <= limit) hits else hits.subList(0, limit)
    }

    fun clear() {
        prefixTable = emptyMap()
        indexedEntryCount = 0
    }

    /**
     * Rebuilds the index atomically from Rime `cn_dicts/` files under [sharedDir].
     * Scans `base.dict.yaml`, `ext.dict.yaml`, and `others.dict.yaml` (deliberately skipping
     * huge `tencent.dict.yaml` to keep mobile rebuild time under ~250ms and memory compact).
     */
    fun rebuildFromRimeSharedDir(sharedDir: File): Int {
        val cnDicts = File(sharedDir, "cn_dicts")
        if (!cnDicts.isDirectory) {
            clear()
            return 0
        }
        val files = listOf(
            File(cnDicts, "base.dict.yaml"),
            File(cnDicts, "ext.dict.yaml"),
            File(cnDicts, "others.dict.yaml")
        ).filter { it.isFile && it.length() > 0L }

        if (files.isEmpty()) {
            clear()
            return 0
        }

        val streams = ArrayList<Pair<File, InputStream>>(files.size)
        return try {
            val builder = Builder(maxSuffixesPerPrefix, maxPrefixes)
            for (file in files) {
                runCatching {
                    FileInputStream(file).use { input ->
                        builder.consumeRimeDictStream(input)
                    }
                }
            }
            val built = builder.build()
            prefixTable = built.first
            indexedEntryCount = built.second
            built.second
        } finally {
            streams.forEach { runCatching { it.second.close() } }
        }
    }

    /**
     * Rebuilds the index atomically from one or more Rime `.dict.yaml` streams (used in unit tests
     * or asset fallbacks).
     */
    fun rebuildFromStreams(streams: List<InputStream>): Int {
        val builder = Builder(maxSuffixesPerPrefix, maxPrefixes)
        for (stream in streams) {
            builder.consumeRimeDictStream(stream)
        }
        val built = builder.build()
        prefixTable = built.first
        indexedEntryCount = built.second
        return built.second
    }

    private class Builder(
        private val maxSuffixesPerPrefix: Int,
        private val maxPrefixes: Int
    ) {
        // prefix -> (suffix -> maxWeight)
        private val rawMap = HashMap<String, HashMap<String, Int>>(16_384)
        private val prefixMaxWeight = HashMap<String, Int>(16_384)
        private var acceptedEntries = 0

        fun consumeRimeDictStream(inputStream: InputStream) {
            BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                var inYamlHeader = false
                var line = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed == "---") {
                        inYamlHeader = true
                    } else if (inYamlHeader) {
                        if (trimmed == "...") inYamlHeader = false
                    } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val firstTab = trimmed.indexOf('\t')
                        if (firstTab > 0) {
                            val word = trimmed.substring(0, firstTab).trim()
                            if (word.length in 2..MAX_WORD_LEN && isPureCjk(word)) {
                                val weight = parseWeight(trimmed, firstTab)
                                indexWord(word, weight)
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        }

        private fun parseWeight(line: String, firstTab: Int): Int {
            val secondTab = line.indexOf('\t', firstTab + 1)
            if (secondTab < 0) return DEFAULT_IMPLICIT_WEIGHT
            val thirdTab = line.indexOf('\t', secondTab + 1)
            val weightStr = if (thirdTab > secondTab) {
                line.substring(secondTab + 1, thirdTab).trim()
            } else {
                line.substring(secondTab + 1).trim()
            }
            return weightStr.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_IMPLICIT_WEIGHT
        }

        private fun indexWord(word: String, weight: Int) {
            // Skip ultra-low-frequency noise words for prefix completion
            if (weight < MIN_WEIGHT_THRESHOLD && word.length == 2) return
            acceptedEntries++

            val len = word.length
            // 1. For 3..6 char words, index 2-char and 3-char prefixes (highest precision, e.g. "人工" -> "智能", "不好" -> "意思")
            if (len >= 3) {
                val maxPLen = minOf(MAX_PREFIX_LEN, len - 1)
                for (pLen in 2..maxPLen) {
                    val prefix = word.substring(0, pLen)
                    val suffix = word.substring(pLen)
                    if (suffix.isNotEmpty() && suffix != prefix) {
                        addCandidate(prefix, suffix, weight)
                    }
                }
            }

            // 2. For 2..4 char words with solid weight, index 1-char prefix (e.g. "好" -> "意思" / "看")
            if (len in 2..4 && weight >= SINGLE_CHAR_PREFIX_MIN_WEIGHT) {
                val prefix = word.substring(0, 1)
                val suffix = word.substring(1)
                if (suffix.isNotEmpty() && suffix != prefix) {
                    addCandidate(prefix, suffix, weight)
                }
            }
        }

        private fun addCandidate(prefix: String, suffix: String, weight: Int) {
            val existingBucket = rawMap[prefix]
            if (existingBucket != null) {
                val prev = existingBucket[suffix]
                if (prev == null || weight > prev) {
                    existingBucket[suffix] = weight
                }
                val prevMax = prefixMaxWeight[prefix] ?: 0
                if (weight > prevMax) prefixMaxWeight[prefix] = weight
                return
            }

            // Keep memory bounded while collecting
            if (rawMap.size >= maxPrefixes * 2) return
            val bucket = HashMap<String, Int>(4)
            bucket[suffix] = weight
            rawMap[prefix] = bucket
            prefixMaxWeight[prefix] = weight
        }

        fun build(): Pair<Map<String, List<String>>, Int> {
            if (rawMap.isEmpty()) return emptyMap<String, List<String>>() to 0

            val selectedKeys = if (rawMap.size <= maxPrefixes) {
                rawMap.keys.toList()
            } else {
                rawMap.keys
                    .sortedByDescending { prefixMaxWeight[it] ?: 0 }
                    .take(maxPrefixes)
            }

            val result = HashMap<String, List<String>>(selectedKeys.size * 4 / 3 + 16)
            for (prefix in selectedKeys) {
                val bucket = rawMap[prefix] ?: continue
                val topSuffixes = bucket.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, Int>> { it.value }
                            .thenBy { it.key.length }
                            .thenBy { it.key }
                    )
                    .take(maxSuffixesPerPrefix)
                    .map { it.key }
                if (topSuffixes.isNotEmpty()) {
                    result[prefix] = topSuffixes
                }
            }
            return result to acceptedEntries
        }
    }

    companion object {
        const val DEFAULT_MAX_SUFFIXES_PER_PREFIX = 8
        const val DEFAULT_MAX_PREFIXES = 36_000
        const val MAX_PREFIX_LEN = 3
        const val MAX_WORD_LEN = 6
        const val DEFAULT_IMPLICIT_WEIGHT = 100
        const val MIN_WEIGHT_THRESHOLD = 10
        const val SINGLE_CHAR_PREFIX_MIN_WEIGHT = 500

        private fun isPureCjk(text: String): Boolean {
            if (text.isEmpty()) return false
            for (i in text.indices) {
                val c = text[i].code
                if (c !in 0x4E00..0x9FFF) return false
            }
            return true
        }
    }
}
