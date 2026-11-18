package com.typefree.core.ime.importer

import com.typefree.core.ime.enhancer.BriefPrefixIndex
import com.typefree.core.ime.enhancer.RimeCodeProber
import com.typefree.core.ime.overlay.OverlaySymbolEntry
import com.typefree.core.ime.overlay.SymbolTrie
import com.typefree.core.ime.profile.SchemaArchetype
import com.typefree.core.ime.profile.SchemaInspector
import com.typefree.core.ime.profile.SchemaProfile
import com.typefree.core.ime.profile.TypeFreeTomlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

enum class ImportMode {
    /** 5.1 明文 `.dict.yaml` 离线 Staging 编译轨 */
    PLAIN_DICT_COMPILE,

    /** 5.2 官方预编译挂接包 `build/<schema>.table.bin` 免源码免重编直通轨 */
    PRECOMPILED_BIN
}

fun interface RimeStagingCompiler {
    /**
     * Compiles plain-text `.schema.yaml` + `.dict.yaml` inside [stagingDir] into `build/<schema>.bin`.
     * NEVER invoked in [ImportMode.PRECOMPILED_BIN] so precompiled `build/<schema>.table.bin` stays untouched.
     */
    fun compilePlainSchema(stagingDir: File, schemaId: String): Boolean
}

data class SchemaImportResult(
    val schemaId: String,
    val displayName: String,
    val importMode: ImportMode,
    val profile: SchemaProfile,
    val installedDir: File,
    val briefIndex: BriefPrefixIndex,
    val briefIndexSnapshotFile: File?,
    val quickSymbolTrie: SymbolTrie,
    val strippedDesktopFiles: List<String>,
    val sanitizedLuaTranslators: List<String>,
    val generatedPlaceholderFiles: List<String>,
    val precompiledTableSha256: String? = null,
    val probeDurationMs: Long = 0L
)

/**
 * Dual-track Rime `.zip` schema package importer implementing Spec Chapter 5:
 * - Track 5.1 (`PLAIN_DICT_COMPILE`): Streaming extraction with path-traversal/zip-bomb defense,
 *   UTF-8/UTF-8-BOM/GB18030 transcoding, `\r\n` & standalone `\r` normalization, `...` separator check,
 *   Lua & desktop config sanitization, `;` quick-symbol extraction, `ok_mode` completion, offline
 *   staging compilation, and `BriefPrefixIndex` `.brief.idx` snapshot serialization.
 * - Track 5.2 (`PRECOMPILED_BIN`): Official Xiaohe Squirrel/Weasel hang-up package (`build/<schema>.table.bin`
 *   without `.dict.yaml`) passthrough — validates `Rime::Table` binary header, preserves `build/<schema>.table.bin`
 *   byte-for-byte without recompiling, generates missing `.txt` stabledb placeholders, and builds
 *   `BriefPrefixIndex` in `< 15ms` via 702-slot (`26 + 676`) dynamic probing.
 */
class SchemaPackageImporter(
    private val stagingParentDir: File,
    private val targetSchemasRootDir: File,
    private val stagingCompiler: RimeStagingCompiler = RimeStagingCompiler { _, _ -> true },
    private val codeProberFactory: ((installedDir: File, schemaId: String) -> RimeCodeProber?)? = null
) {
    fun importZip(
        zipInputStream: InputStream,
        overrideProber: RimeCodeProber? = null
    ): SchemaImportResult {
        val stagingRaw = File(stagingParentDir, "schema_staging_${UUID.randomUUID()}")
        stagingRaw.mkdirs()

        try {
            val strippedDesktopFiles = ArrayList<String>()
            extractZipSafely(zipInputStream, stagingRaw, strippedDesktopFiles)

            // Step 1: Locate real schema root (auto-stripping outer `rime/`, `Squirrel/`, `Weasel/` shells)
            val realRoot = locateSchemaRoot(stagingRaw)
                ?: throw IllegalArgumentException("压缩包内未检测到有效的 *.schema.yaml 或 build/*.table.bin")

            // Remove desktop-only config files if any remained in nested folders
            realRoot.walkTopDown().filter { it.isFile }.forEach { f ->
                if (isDesktopFrontendFile(f.name)) {
                    strippedDesktopFiles.add(f.name)
                    f.delete()
                }
            }

            // Normalize encoding (UTF-8 / BOM / GB18030 -> UTF-8 without BOM) & line endings (\r\n, \r -> \n)
            normalizeAllTextFiles(realRoot)

            // Validate all `.dict.yaml` files contain standalone `...` body separator
            validateDictYamlSeparators(realRoot)

            val schemaFile = selectPrimarySchemaFile(realRoot)
                ?: throw IllegalArgumentException("未找到主方案 *.schema.yaml")
            val rawSchemaText = schemaFile.readText(StandardCharsets.UTF_8)
            val initialMeta = SchemaInspector.parseSchemaYaml(rawSchemaText)
            val schemaId = initialMeta.schemaId
            val isXiaohe = schemaId == "flypy" || initialMeta.displayName.contains("小鹤音形")
            val mainDictName = initialMeta.mainDictionary ?: schemaId

            if (isXiaohe) {
                val rimeLua = File(realRoot, "rime.lua")
                if (rimeLua.exists() && rimeLua.delete()) {
                    strippedDesktopFiles.add(rimeLua.name)
                }
            }

            val mainDictFile = findFileRecursive(realRoot, "$mainDictName.dict.yaml")
            val precompiledTableBin = File(realRoot, "build/$schemaId.table.bin")
                .takeIf { it.exists() }
                ?: File(realRoot, "build/$mainDictName.table.bin").takeIf { it.exists() }

            val importMode = if (mainDictFile == null && precompiledTableBin != null) {
                ImportMode.PRECOMPILED_BIN
            } else {
                ImportMode.PLAIN_DICT_COMPILE
            }

            if (importMode == ImportMode.PRECOMPILED_BIN) {
                validateRimeTableBinaryHeader(precompiledTableBin!!)
            }

            val sanitizedLua = ArrayList<String>()
            val generatedPlaceholders = ArrayList<String>()

            // Sanitize schema YAML (comment out lua_translator@..., strip `;` for Xiaohe plain compile or non-coupled overlay, add ok_mode if missing)
            val quickSymbolsFile = File(realRoot, "quick_symbols.yaml")
            val extractedQuickEntries = ArrayList<OverlaySymbolEntry>()
            if (mainDictFile != null && initialMeta.spellerAlphabet.contains(';')) {
                stripSemicolonEntriesFromDict(mainDictFile, extractedQuickEntries)
            }

            val sanitizedSchemaText = sanitizeSchemaYaml(
                rawYaml = rawSchemaText,
                meta = initialMeta,
                sanitizedLuaOut = sanitizedLua
            )
            schemaFile.writeText(sanitizedSchemaText, StandardCharsets.UTF_8)
            if (importMode == ImportMode.PRECOMPILED_BIN) {
                val prebuiltSchema = File(realRoot, "build/${schemaFile.name}")
                if (prebuiltSchema.exists()) {
                    prebuiltSchema.writeText(sanitizedSchemaText, StandardCharsets.UTF_8)
                }
            }

            // Ensure all referenced custom_phrase user_dict .txt files exist so librime never warns
            val updatedMeta = SchemaInspector.parseSchemaYaml(sanitizedSchemaText)
            for (userDictName in updatedMeta.customPhraseUserDicts) {
                val txtFile = File(realRoot, "$userDictName.txt")
                if (!txtFile.exists()) {
                    txtFile.writeText(
                        "# Rime table\n# coding: utf-8\n#@/db_name\t$userDictName\n#@/db_type\tstabledb\n",
                        StandardCharsets.UTF_8
                    )
                    generatedPlaceholders.add(txtFile.name)
                }
            }

            // Build or load quick_symbols.yaml (persisting any `;` entries extracted from `.dict.yaml`)
            val baseTrie = if (quickSymbolsFile.exists()) {
                SymbolTrie.parseQuickSymbolsYaml(quickSymbolsFile.readText(StandardCharsets.UTF_8))
            } else {
                SymbolTrie.defaultXiaohe()
            }
            val quickTrie = if (extractedQuickEntries.isNotEmpty()) {
                baseTrie.mergeExtractedEntries(extractedQuickEntries).also { merged ->
                    quickSymbolsFile.writeText(merged.serializeQuickSymbolsYaml(), StandardCharsets.UTF_8)
                }
            } else {
                baseTrie
            }

            val tomlFile = File(realRoot, "typefree.toml")
            val existingToml = if (tomlFile.exists()) tomlFile.readText(StandardCharsets.UTF_8) else null

            val targetDir = File(targetSchemasRootDir, schemaId)
            var precompiledSha256: String? = null
            var probeElapsedMs = 0L
            val profile: SchemaProfile
            val briefIndex: BriefPrefixIndex

            if (importMode == ImportMode.PLAIN_DICT_COMPILE) {
                val dictText = mainDictFile?.readText(StandardCharsets.UTF_8)
                val inspected = SchemaInspector.inspect(
                    schemaYaml = sanitizedSchemaText,
                    dictYamlContent = dictText,
                    tomlOverrideContent = existingToml
                )
                profile = if (sanitizedLua.isNotEmpty()) {
                    inspected.copy(directCommandsEnabled = true)
                } else {
                    inspected
                }
                tomlFile.writeText(TypeFreeTomlParser.serialize(profile), StandardCharsets.UTF_8)

                // Stage 5.1.4: Offline staging compilation
                val tempDefaultYaml = File(realRoot, "default.yaml")
                val wroteTempDefault = if (!tempDefaultYaml.exists()) {
                    tempDefaultYaml.writeText(
                        """
                        config_version: "2026-11-18"
                        schema_list:
                          - schema: $schemaId
                        punctuator:
                          half_shape: {}
                          full_shape: {}
                        key_binder:
                          bindings: []
                        recognizer:
                          patterns: {}
                        """.trimIndent() + "\n",
                        StandardCharsets.UTF_8
                    )
                    true
                } else {
                    false
                }
                val compiledOk = try {
                    stagingCompiler.compilePlainSchema(realRoot, schemaId)
                } finally {
                    if (wroteTempDefault) {
                        tempDefaultYaml.delete()
                    }
                }
                if (!compiledOk) {
                    throw IllegalStateException("离线编译方案 $schemaId 失败")
                }

                // Stage 5.1.5: Pre-build BriefPrefixIndex
                briefIndex = if (profile.reverseSplitEnabled && mainDictFile != null) {
                    val topLines = File(realRoot, "${schemaId}_top.txt")
                        .takeIf { it.exists() }
                        ?.readLines(StandardCharsets.UTF_8)
                        .orEmpty()
                        .asSequence()
                    val userLines = File(realRoot, "${schemaId}_user.txt")
                        .takeIf { it.exists() }
                        ?.readLines(StandardCharsets.UTF_8)
                        .orEmpty()
                        .asSequence()
                    BriefPrefixIndex.buildFromDictLines(
                        mainDictLines = mainDictFile.readLines(StandardCharsets.UTF_8).asSequence(),
                        topDictLines = topLines,
                        userDictLines = userLines,
                        gt4GuardTwoPrefixes = profile.gt4GuardPrefixes
                    )
                } else {
                    BriefPrefixIndex.empty()
                }

                atomicReplaceDirectory(realRoot, targetDir)
            } else {
                // Track 5.2: Precompiled-Bin Passthrough (`build/<schema>.table.bin` kept byte-identical!)
                val binBeforeSha = sha256Hex(precompiledTableBin!!)
                atomicReplaceDirectory(realRoot, targetDir)
                val installedBin = File(targetDir, "build/${precompiledTableBin.name}")
                precompiledSha256 = sha256Hex(installedBin)
                check(binBeforeSha == precompiledSha256) {
                    "Precompiled table.bin hash changed unexpectedly during passthrough!"
                }

                val prober = overrideProber ?: codeProberFactory?.invoke(targetDir, schemaId)
                val topLines = File(targetDir, "${schemaId}_top.txt")
                    .takeIf { it.exists() }
                    ?.readLines(StandardCharsets.UTF_8)
                    .orEmpty()
                    .asSequence()
                val userLines = File(targetDir, "${schemaId}_user.txt")
                    .takeIf { it.exists() }
                    ?.readLines(StandardCharsets.UTF_8)
                    .orEmpty()
                    .asSequence()

                val t0 = System.nanoTime()
                briefIndex = if (prober != null) {
                    BriefPrefixIndex.buildFrom702SlotProbing(
                        prober = prober,
                        topDictLines = topLines,
                        userDictLines = userLines,
                        affixGuardPrefixes = updatedMeta.affixPrefixes.ifEmpty {
                            if (schemaId == "flypy") setOf("ok", "os") else emptySet()
                        }
                    )
                } else {
                    BriefPrefixIndex.empty()
                }
                probeElapsedMs = (System.nanoTime() - t0) / 1_000_000L

                val probedRatio = if (prober != null) briefIndex.twoBriefMultiCharTop1Ratio else null
                val inspected = SchemaInspector.inspect(
                    schemaYaml = sanitizedSchemaText,
                    probedTwoBriefMultiCharRatio = probedRatio,
                    tomlOverrideContent = existingToml
                )
                profile = if (sanitizedLua.isNotEmpty()) {
                    inspected.copy(directCommandsEnabled = true)
                } else {
                    inspected
                }
                File(targetDir, "typefree.toml").writeText(
                    TypeFreeTomlParser.serialize(profile),
                    StandardCharsets.UTF_8
                )
            }

            val snapshotFile = if (profile.reverseSplitEnabled) {
                File(targetDir, "${profile.schemaId}.brief.idx").also { idxFile ->
                    briefIndex.writeSnapshot(idxFile)
                }
            } else {
                null
            }

            return SchemaImportResult(
                schemaId = profile.schemaId,
                displayName = profile.displayName,
                importMode = importMode,
                profile = profile,
                installedDir = targetDir,
                briefIndex = briefIndex,
                briefIndexSnapshotFile = snapshotFile,
                quickSymbolTrie = quickTrie,
                strippedDesktopFiles = strippedDesktopFiles,
                sanitizedLuaTranslators = sanitizedLua,
                generatedPlaceholderFiles = generatedPlaceholders,
                precompiledTableSha256 = precompiledSha256,
                probeDurationMs = probeElapsedMs
            )
        } finally {
            stagingRaw.deleteRecursively()
        }
    }

    private fun extractZipSafely(
        zipInputStream: InputStream,
        destRoot: File,
        strippedDesktopFiles: MutableList<String>
    ) {
        val canonicalRoot = destRoot.canonicalPath + File.separator
        var totalExtractedBytes = 0L
        val buffer = ByteArray(16 * 1024)

        ZipInputStream(zipInputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val rawName = entry.name.replace('\\', '/').trimStart('/')
                if (rawName.contains("..") || rawName.startsWith("/")) {
                    throw SecurityException("拒绝路径穿越压缩包条目: ${entry.name}")
                }
                if (!entry.isDirectory && rawName.isNotEmpty()) {
                    val baseName = rawName.substringAfterLast('/')
                    if (!baseName.startsWith("._") && baseName != ".DS_Store") {
                        if (isDesktopFrontendFile(baseName)) {
                            strippedDesktopFiles.add(baseName)
                        } else {
                            val outFile = File(destRoot, rawName)
                            val canonicalOut = outFile.canonicalPath
                            if (!canonicalOut.startsWith(canonicalRoot)) {
                                throw SecurityException("拒绝越界解压路径: ${entry.name}")
                            }
                            outFile.parentFile?.mkdirs()
                            if (Files.isSymbolicLink(outFile.toPath())) {
                                throw SecurityException("拒绝符号链接条目: ${entry.name}")
                            }
                            FileOutputStream(outFile).use { fos ->
                                var n = zis.read(buffer)
                                while (n > 0) {
                                    totalExtractedBytes += n
                                    if (totalExtractedBytes > MAX_UNZIPPED_BYTES) {
                                        throw SecurityException("方案包解压体积超过 150MB 安全上限")
                                    }
                                    fos.write(buffer, 0, n)
                                    n = zis.read(buffer)
                                }
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun locateSchemaRoot(stagingDir: File): File? {
        fun hasSchemaOrPrecompiledBin(dir: File): Boolean {
            val files = dir.listFiles().orEmpty()
            if (files.any { it.isFile && it.name.endsWith(".schema.yaml") }) return true
            val buildDir = File(dir, "build")
            if (buildDir.isDirectory && buildDir.listFiles().orEmpty().any { it.name.endsWith(".table.bin") }) {
                return true
            }
            return false
        }

        if (hasSchemaOrPrecompiledBin(stagingDir)) return stagingDir
        return stagingDir.walkTopDown()
            .filter { it.isDirectory && hasSchemaOrPrecompiledBin(it) }
            .minByOrNull { it.absolutePath.length }
    }

    private fun selectPrimarySchemaFile(root: File): File? {
        val schemas = root.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".schema.yaml") }
        if (schemas.isEmpty()) return null
        // Prefer main schema over auxiliary reverse-lookup `*dz.schema.yaml`
        return schemas.firstOrNull { !it.name.endsWith("dz.schema.yaml") } ?: schemas.first()
    }

    private fun findFileRecursive(root: File, fileName: String): File? {
        val direct = File(root, fileName)
        if (direct.exists()) return direct
        return root.walkTopDown().firstOrNull { it.isFile && it.name == fileName }
    }

    private fun normalizeAllTextFiles(root: File) {
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val lower = file.name.lowercase()
            if (lower.endsWith(".yaml") || lower.endsWith(".txt") || lower.endsWith(".toml") || lower.endsWith(".tsv") || lower.endsWith(".lua")) {
                val rawBytes = file.readBytes()
                val decoded = decodeUtf8OrGb18030(rawBytes)
                val normalized = decoded
                    .removePrefix("\uFEFF")
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                file.writeText(normalized, StandardCharsets.UTF_8)
            }
        }
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

    private fun validateDictYamlSeparators(root: File) {
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".dict.yaml") }
            .forEach { dictFile ->
                var hasEllipsis = false
                var lineCount = 0
                dictFile.forEachLine(StandardCharsets.UTF_8) { line ->
                    lineCount++
                    if (line.trim() == "...") {
                        hasEllipsis = true
                    }
                }
                if (!hasEllipsis) {
                    throw IllegalArgumentException(
                        "词库语法错误：${dictFile.name}（共 $lineCount 行）缺失独立的 `...` 表头结束分隔行"
                    )
                }
            }
    }

    private fun validateRimeTableBinaryHeader(binFile: File) {
        if (binFile.length() < 32L) {
            throw IllegalArgumentException("预编译二进制码表体积异常 (${binFile.name}: ${binFile.length()} bytes)")
        }
        val header = ByteArray(32)
        FileInputStream(binFile).use { fis ->
            val n = fis.read(header)
            if (n < 16) throw IllegalArgumentException("无法读取预编译码表魔数头: ${binFile.name}")
        }
        val magicPrefix = String(header, 0, 11, StandardCharsets.US_ASCII)
        if (!magicPrefix.startsWith("Rime::Table")) {
            throw IllegalArgumentException(
                "预编译码表魔数头校验失败 (${binFile.name}): expected 'Rime::Table*', got '$magicPrefix'"
            )
        }
    }

    private fun stripSemicolonEntriesFromDict(
        dictFile: File,
        extractedOut: MutableList<OverlaySymbolEntry>
    ) {
        val lines = dictFile.readLines(StandardCharsets.UTF_8)
        val kept = ArrayList<String>(lines.size)
        var inBody = false
        for (line in lines) {
            if (!inBody) {
                kept.add(line)
                if (line.trim() == "...") inBody = true
                continue
            }
            val tab1 = line.indexOf('\t')
            if (tab1 > 0) {
                val word = line.substring(0, tab1)
                val code = line.substring(tab1 + 1).substringBefore('\t').trim()
                if (code.startsWith(";") && code.length in 2..3) {
                    val key = code.removePrefix(";")
                    extractedOut.add(
                        OverlaySymbolEntry(
                            key = key,
                            displayText = word,
                            action = com.typefree.core.ime.coordinator.CommitAction.PlainText(word)
                        )
                    )
                    continue
                }
            }
            kept.add(line)
        }
        if (kept.size != lines.size) {
            dictFile.writeText(kept.joinToString("\n") + "\n", StandardCharsets.UTF_8)
        }
    }

    private fun sanitizeSchemaYaml(
        rawYaml: String,
        meta: SchemaInspector.ParsedSchemaMetadata,
        sanitizedLuaOut: MutableList<String>
    ): String {
        val isXiaohe = meta.schemaId == "flypy" || meta.displayName.contains("小鹤音形")
        val outLines = ArrayList<String>()
        var inSpeller = false
        var inOkMode = false
        var hasOkModeBlock = false

        for (line in rawYaml.lines()) {
            val trimmed = line.trim()
            val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            if (indent == 0 && trimmed.endsWith(":") && !trimmed.startsWith("#")) {
                val sec = trimmed.removeSuffix(":")
                inSpeller = (sec == "speller")
                inOkMode = (sec == "ok_mode")
                if (inOkMode) hasOkModeBlock = true
            }

            if (isXiaohe && isLuaPluginLine(trimmed)) {
                val luaName = trimmed.removePrefix("-").trim()
                sanitizedLuaOut.add(luaName)
                outLines.add(line.replaceFirst("- lua_", "# [TypeFree DirectCommand] - lua_"))
                continue
            }

            if (isXiaohe && inOkMode && trimmed.startsWith("prefix:")) {
                // `ok*`/`os*` entries in flypy.dict.yaml store full `ok...`/`os...` keys;
                // keeping `prefix: "o"` in affix_segmentor strips leading `o` and breaks lookup.
                continue
            }

            if (isXiaohe && inSpeller) {
                if (trimmed.startsWith("alphabet:")) {
                    outLines.add("  alphabet: \"abcdefghijklmnopqrstuvwxyz'\"")
                    continue
                }
                if (trimmed.startsWith("initials:")) {
                    outLines.add("  initials: \"abcdefghijklmnopqrstuvwxyz\"")
                    continue
                }
                if (trimmed.startsWith("auto_select_pattern:") && trimmed.contains(";")) {
                    outLines.add("  auto_select_pattern: \"^(?!o[ks])[a-z]{4}$|^o[ks][a-z]{6,8}$\"")
                    continue
                }
            }

            outLines.add(line)
        }

        if (isXiaohe && !hasOkModeBlock && rawYaml.contains("affix_segmentor@ok_mode")) {
            outLines.add("")
            outLines.add("ok_mode:")
            outLines.add("  tag: ok_mode")
            outLines.add("  tags: [ expression ]")
            outLines.add("  dictionary: flypy")
            outLines.add("  enable_charset_filter: false")
            outLines.add("  enable_sentence: false")
            outLines.add("  enable_completion: true")
            outLines.add("  enable_encoder: false")
            outLines.add("  enable_user_dict: false")
        }

        return outLines.joinToString("\n")
    }

    private fun atomicReplaceDirectory(srcDir: File, dstDir: File) {
        dstDir.parentFile?.mkdirs()
        val backupDir = File(dstDir.parentFile, "${dstDir.name}.bak_${UUID.randomUUID()}")
        if (dstDir.exists()) {
            if (!dstDir.renameTo(backupDir)) {
                dstDir.copyRecursively(backupDir, overwrite = true)
                dstDir.deleteRecursively()
            }
        }
        try {
            if (!srcDir.renameTo(dstDir)) {
                srcDir.copyRecursively(dstDir, overwrite = true)
            }
            backupDir.deleteRecursively()
        } catch (e: Exception) {
            if (backupDir.exists() && !dstDir.exists()) {
                backupDir.renameTo(dstDir)
            }
            throw e
        }
    }

    companion object {
        private fun isLuaPluginLine(trimmed: String): Boolean {
            return trimmed.startsWith("- lua_processor") ||
                trimmed.startsWith("- lua_segmentor") ||
                trimmed.startsWith("- lua_translator") ||
                trimmed.startsWith("- lua_filter")
        }

        const val MAX_UNZIPPED_BYTES = 150L * 1024L * 1024L
        val DESKTOP_FRONTEND_FILES = setOf(
            "default.yaml",
            "default.custom.yaml",
            "squirrel.yaml",
            "squirrel.custom.yaml",
            "weasel.yaml",
            "weasel.custom.yaml"
        )

        fun isDesktopFrontendFile(fileName: String): Boolean {
            val lower = fileName.lowercase()
            return lower in DESKTOP_FRONTEND_FILES ||
                lower.startsWith("squirrel.") ||
                lower.startsWith("weasel.") ||
                lower.endsWith(".trime.yaml")
        }

        fun sha256Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(16 * 1024)
                var n = fis.read(buf)
                while (n > 0) {
                    md.update(buf, 0, n)
                    n = fis.read(buf)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
