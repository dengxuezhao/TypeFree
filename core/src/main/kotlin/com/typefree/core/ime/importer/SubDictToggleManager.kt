package com.typefree.core.ime.importer

import com.typefree.core.ime.enhancer.DirectCommandEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Manages hot-pluggable sub-dictionaries (Layer +1, e.g. `flypy_full` for `oqm` 全码字出简让全
 * and user `ext/<name>.dict.yaml` domain lexicons) by writing `<schemaId>.custom.yaml` patches and
 * triggering fast session reloads without altering Layer 0 main dictionary order.
 */
class SubDictToggleManager(
    private val schemaRootDir: File,
    private val onReloadSession: (schemaId: String) -> Unit = {}
) {
    data class SubDictDescriptor(
        val name: String,
        val displayName: String,
        val isBuiltIn: Boolean,
        val enabled: Boolean,
        val relativePath: String
    )

    private val schemaStates = HashMap<String, LinkedHashMap<String, Boolean>>()
    private val loadedSchemas = HashSet<String>()

    private fun stateMap(schemaId: String): LinkedHashMap<String, Boolean> {
        return schemaStates.getOrPut(schemaId) { LinkedHashMap() }
    }

    private fun ensureLoadedFromDisk(schemaId: String) {
        if (schemaId.isBlank() || !loadedSchemas.add(schemaId)) return
        val customFile = File(schemaRootDir, "$schemaId.custom.yaml")
        if (!customFile.isFile) return
        val text = runCatching { customFile.readText(StandardCharsets.UTF_8) }.getOrDefault("")
        if (text.isEmpty()) return

        val enabledSubDicts = stateMap(schemaId)
        if (text.contains("table_translator@custom_phraseQMZ") && "flypy_full" !in enabledSubDicts) {
            enabledSubDicts["flypy_full"] = false
        }
        val extDictRegex = Regex("""^\s*"ext_dict_\d+/dictionary":\s*(.+?)\s*$""", RegexOption.MULTILINE)
        extDictRegex.findAll(text).forEach { match ->
            val dictName = match.groupValues[1].trim().trim('"', '\'')
            if (dictName.isNotEmpty() && dictName !in enabledSubDicts) {
                enabledSubDicts[dictName] = true
            }
        }
    }

    fun isSubDictEnabled(
        subDictName: String,
        defaultEnabled: Boolean = true,
        schemaId: String = "flypy"
    ): Boolean {
        ensureLoadedFromDisk(schemaId)
        return stateMap(schemaId)[subDictName] ?: defaultEnabled
    }

    fun setSubDictEnabled(schemaId: String, subDictName: String, enabled: Boolean) {
        ensureLoadedFromDisk(schemaId)
        stateMap(schemaId)[subDictName] = enabled
        writeCustomYamlPatch(schemaId)
        onReloadSession(schemaId)
    }

    /**
     * Flips `flypy_full` (`custom_phraseQMZ`) for Xiaohe `oqm` command and returns the new state.
     */
    fun toggleFullCodeDict(schemaId: String = "flypy"): Boolean {
        val next = !isSubDictEnabled("flypy_full", defaultEnabled = true, schemaId = schemaId)
        setSubDictEnabled(schemaId, "flypy_full", next)
        return next
    }

    fun listAvailableSubDicts(schemaId: String = "flypy"): List<SubDictDescriptor> {
        ensureLoadedFromDisk(schemaId)
        val result = ArrayList<SubDictDescriptor>()

        val fullDictFile = File(schemaRootDir, "flypy_full.dict.yaml")
        val fullBinFile = File(schemaRootDir, "build/flypy_full.table.bin")
        val hasFlypyFull = fullDictFile.isFile ||
            fullBinFile.isFile ||
            (schemaId == "flypy" && File(schemaRootDir, "flypy.schema.yaml").let { f ->
                f.isFile && runCatching { f.readText(StandardCharsets.UTF_8) }.getOrDefault("").contains("flypy_full")
            })
        if (hasFlypyFull) {
            result.add(
                SubDictDescriptor(
                    name = "flypy_full",
                    displayName = "全码字词库 (flypy_full)",
                    isBuiltIn = true,
                    enabled = isSubDictEnabled("flypy_full", defaultEnabled = true, schemaId = schemaId),
                    relativePath = if (fullDictFile.isFile) "flypy_full.dict.yaml" else "build/flypy_full.table.bin"
                )
            )
        }

        val extDir = File(schemaRootDir, "ext")
        if (extDir.isDirectory) {
            extDir.listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.endsWith(".dict.yaml", ignoreCase = true) }
                .sortedBy { it.name }
                .forEach { dictFile ->
                    val stem = dictFile.name.removeSuffix(".dict.yaml")
                    val dictName = "ext/$stem"
                    result.add(
                        SubDictDescriptor(
                            name = dictName,
                            displayName = stem,
                            isBuiltIn = false,
                            enabled = isSubDictEnabled(dictName, defaultEnabled = false, schemaId = schemaId),
                            relativePath = "ext/${dictFile.name}"
                        )
                    )
                }
        }
        return result
    }

    fun importExtDict(
        sourceFileName: String,
        contentStream: InputStream,
        schemaId: String = "flypy"
    ): SubDictDescriptor {
        val baseName = sourceFileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val lower = baseName.lowercase()
        val (rawStem, isDictYaml) = when {
            lower.endsWith(".dict.yaml") -> baseName.dropLast(".dict.yaml".length) to true
            lower.endsWith(".yaml") -> baseName.dropLast(".yaml".length) to true
            lower.endsWith(".yml") -> baseName.dropLast(".yml".length) to true
            lower.endsWith(".tsv") -> baseName.dropLast(".tsv".length) to false
            lower.endsWith(".txt") -> baseName.dropLast(".txt".length) to false
            else -> throw IllegalArgumentException("不支持的扩展词库格式: $baseName（仅支持 .dict.yaml / .tsv / .txt）")
        }

        val sanitizedStem = sanitizeFileStem(rawStem)
        if (sanitizedStem.isEmpty()) {
            throw IllegalArgumentException("无效的扩展词库文件名: $sourceFileName")
        }

        val rawText = decodeUtf8OrGb18030(readBoundedBytes(contentStream))
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val dictName = "ext/$sanitizedStem"
        val normalizedContent = if (isDictYaml) {
            normalizeRimeDictYaml(rawText, dictName, baseName)
        } else {
            convertTsvToRimeDictYaml(rawText, dictName, baseName)
        }

        val extDir = File(schemaRootDir, "ext")
        extDir.mkdirs()
        val targetFile = File(extDir, "$sanitizedStem.dict.yaml")
        targetFile.writeText(normalizedContent, StandardCharsets.UTF_8)

        setSubDictEnabled(schemaId, dictName, true)
        return SubDictDescriptor(
            name = dictName,
            displayName = sanitizedStem,
            isBuiltIn = false,
            enabled = true,
            relativePath = "ext/$sanitizedStem.dict.yaml"
        )
    }

    fun loadEnabledExtReverseLookup(schemaId: String = "flypy"): Map<String, List<String>> {
        val merged = LinkedHashMap<String, MutableList<String>>()
        for (descriptor in listAvailableSubDicts(schemaId)) {
            if (descriptor.isBuiltIn || !descriptor.enabled) continue
            val dictFile = File(schemaRootDir, descriptor.relativePath)
            if (!dictFile.isFile) continue
            val parsed = runCatching {
                dictFile.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    DirectCommandEngine.loadReverseLookupFromDictLines(lines)
                }
            }.getOrDefault(emptyMap())
            for ((word, codes) in parsed) {
                val dst = merged.getOrPut(word) { ArrayList(codes.size) }
                for (code in codes) {
                    if (code !in dst) {
                        dst.add(code)
                    }
                }
            }
        }
        return merged
    }

    private fun writeCustomYamlPatch(schemaId: String) {
        schemaRootDir.mkdirs()
        val customFile = File(schemaRootDir, "$schemaId.custom.yaml")
        val enabledSubDicts = stateMap(schemaId)
        val fullEnabled = enabledSubDicts["flypy_full"] ?: true
        val extraEnabled = enabledSubDicts.entries
            .filter { it.key != "flypy_full" && it.value }
            .map { it.key }

        val content = buildString {
            appendLine("# Generated by TypeFree SubDictToggleManager")
            appendLine("patch:")
            if (!fullEnabled) {
                appendLine("  \"engine/translators/-\": table_translator@custom_phraseQMZ")
            }
            if (extraEnabled.isNotEmpty()) {
                appendLine("  \"engine/translators/+\":")
                extraEnabled.forEachIndexed { idx, _ ->
                    appendLine("    - table_translator@ext_dict_$idx")
                }
                extraEnabled.forEachIndexed { idx, extDict ->
                    val tag = "ext_dict_$idx"
                    appendLine("  \"$tag/dictionary\": $extDict")
                    appendLine("  \"$tag/enable_user_dict\": false")
                    appendLine("  \"$tag/enable_sentence\": false")
                    appendLine("  \"$tag/initial_quality\": -1")
                }
            }
        }
        customFile.writeText(content, StandardCharsets.UTF_8)
    }

    private fun sanitizeFileStem(raw: String): String {
        val replaced = raw.replace(Regex("""[^A-Za-z0-9_\-\u4E00-\u9FFF]"""), "_")
            .replace(Regex("""_+"""), "_")
            .trim('_', '-', '.')
        return replaced.take(64)
    }

    private fun normalizeRimeDictYaml(rawText: String, dictName: String, fileName: String): String {
        val lines = rawText.lines()
        val ellipsisIndex = lines.indexOfFirst { it.trim() == "..." }
        if (ellipsisIndex < 0) {
            throw IllegalArgumentException("词库语法错误：$fileName 缺失独立的 `...` 表头结束分隔行")
        }
        val headerLines = lines.subList(0, ellipsisIndex).toMutableList()
        val bodyLines = lines.subList(ellipsisIndex + 1, lines.size)

        val nameIdx = headerLines.indexOfFirst { it.trimStart().startsWith("name:") }
        if (nameIdx >= 0) {
            headerLines[nameIdx] = "name: $dictName"
        } else {
            val dashIdx = headerLines.indexOfFirst { it.trim() == "---" }
            if (dashIdx >= 0) {
                headerLines.add(dashIdx + 1, "name: $dictName")
            } else {
                headerLines.add(0, "---")
                headerLines.add(1, "name: $dictName")
            }
        }

        return buildString {
            headerLines.forEach { appendLine(it) }
            appendLine("...")
            bodyLines.forEachIndexed { idx, line ->
                if (idx < bodyLines.lastIndex || line.isNotEmpty()) {
                    appendLine(line)
                }
            }
        }
    }

    private fun convertTsvToRimeDictYaml(rawText: String, dictName: String, fileName: String): String {
        val parsedRows = ArrayList<List<String>>()
        for (rawLine in rawText.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val cols = rawLine.split('\t').map { it.trim() }
            if (cols.size >= 2 && cols[0].isNotEmpty() && cols[1].isNotEmpty()) {
                parsedRows.add(cols)
            }
        }
        if (parsedRows.isEmpty()) {
            throw IllegalArgumentException("未检测到有效的制表符词条: $fileName")
        }

        fun isAsciiCodeToken(token: String): Boolean {
            return token.isNotEmpty() && token.all { it in 'a'..'z' || it in 'A'..'Z' || it == ';' || it == '\'' }
        }

        var col0CodeVotes = 0
        var col1CodeVotes = 0
        for (cols in parsedRows.take(64)) {
            val c0 = isAsciiCodeToken(cols[0])
            val c1 = isAsciiCodeToken(cols[1])
            if (c0 && !c1) col0CodeVotes++
            else if (!c0 && c1) col1CodeVotes++
        }
        val codeFirst = col0CodeVotes > col1CodeVotes

        val entries = ArrayList<String>(parsedRows.size)
        for (cols in parsedRows) {
            if (codeFirst) {
                val code = cols[0].lowercase()
                if (cols.size == 3 && cols[2].all { it.isDigit() }) {
                    entries.add("${cols[1]}\t$code\t${cols[2]}")
                } else {
                    for (word in cols.drop(1)) {
                        if (word.isNotEmpty()) {
                            entries.add("$word\t$code")
                        }
                    }
                }
            } else {
                val word = cols[0]
                val code = cols[1].lowercase()
                if (cols.size >= 3 && cols[2].all { it.isDigit() }) {
                    entries.add("$word\t$code\t${cols[2]}")
                } else {
                    entries.add("$word\t$code")
                }
            }
        }
        if (entries.isEmpty()) {
            throw IllegalArgumentException("未检测到有效的词条映射: $fileName")
        }

        return buildString {
            appendLine("# Rime dictionary - Imported by TypeFree")
            appendLine("# encoding: utf-8")
            appendLine("---")
            appendLine("name: $dictName")
            appendLine("version: \"1.0\"")
            appendLine("sort: original")
            appendLine("use_preset_vocabulary: false")
            appendLine("...")
            entries.forEach { appendLine(it) }
        }
    }

    private fun readBoundedBytes(stream: InputStream): ByteArray {
        val buffer = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n <= 0) break
            total += n
            if (total > MAX_EXT_DICT_BYTES) {
                throw IllegalArgumentException("扩展词库体积超过 64MB 上限")
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun decodeUtf8OrGb18030(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        val strictUtf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strictUtf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }

    companion object {
        private const val MAX_EXT_DICT_BYTES = 64L * 1024L * 1024L
    }
}
