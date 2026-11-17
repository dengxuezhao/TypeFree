package com.typefree.ime.ime.engine

import android.content.Context
import com.typefree.core.ime.enhancer.BriefPrefixIndex
import com.typefree.core.ime.enhancer.DirectCommandEngine
import com.typefree.core.ime.enhancer.RimeCodeProber
import com.typefree.core.ime.importer.ImportMode
import com.typefree.core.ime.importer.SchemaImportResult
import com.typefree.core.ime.importer.SchemaPackageImporter
import com.typefree.core.ime.overlay.SymbolTrie
import com.typefree.core.ime.profile.SchemaInspector
import com.typefree.core.ime.profile.SchemaProfile
import com.typefree.ime.BuildConfig
import com.typefree.ime.data.InputScheme
import com.typefree.rime.RimeBridge
import com.typefree.rime.RimeSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

sealed interface RimeDeploymentState {
    data object Idle : RimeDeploymentState

    data class Deploying(
        val progress: Float,
        val message: String
    ) : RimeDeploymentState

    data class Ready(
        val wordCount: Int,
        val hasFullRimeIce: Boolean,
        val activeSchemaId: String,
        val customSchemaName: String? = null,
        val deployDurationMs: Long = 0L
    ) : RimeDeploymentState

    data class Error(
        val message: String
    ) : RimeDeploymentState
}

/**
 * Manages `filesDir/rime/shared` and `filesDir/rime/user`, background `librime` deployment,
 * SHA-256-verified on-demand `rime-ice` full lexicon downloading, and `.zip` Rime schema importing.
 */
class RimeDataManager(
    val sharedDir: File,
    val userDir: File,
    val rimeBridge: RimeSessionClient = RimeBridge(),
    private val appContext: Context? = null
) {
    constructor(context: Context, rimeBridge: RimeSessionClient = RimeBridge()) : this(
        sharedDir = File(context.filesDir, "rime/shared"),
        userDir = File(context.filesDir, "rime/user"),
        rimeBridge = rimeBridge,
        appContext = context.applicationContext
    )

    private val _deploymentState = MutableStateFlow<RimeDeploymentState>(RimeDeploymentState.Idle)
    val deploymentState: StateFlow<RimeDeploymentState> = _deploymentState.asStateFlow()

    @Volatile
    var activeSchemaProfile: SchemaProfile = if (BuildConfig.IS_XIAOHE_FLAVOR) {
        SchemaProfile.defaultXiaoheFlypy()
    } else {
        SchemaProfile.defaultSentencePinyin()
    }
        private set

    @Volatile
    var activeBriefIndex: BriefPrefixIndex = BriefPrefixIndex.empty()
        private set

    @Volatile
    var activeSymbolTrie: SymbolTrie = SymbolTrie.defaultXiaohe()
        private set

    @Volatile
    var activeReverseLookupMap: Map<String, List<String>> = emptyMap()
        private set

    val isReady: Boolean
        get() = _deploymentState.value is RimeDeploymentState.Ready

    fun hasFullRimeIceDictionary(): Boolean {
        val marker = File(sharedDir, FULL_DICT_MARKER_FILENAME)
        val baseDict = File(sharedDir, BASE_DICT_RELATIVE_PATH)
        return marker.exists() || (baseDict.exists() && baseDict.length() > 2_000_000L)
    }

    fun hasCustomSchemaImported(): Boolean {
        val meta = File(sharedDir, CUSTOM_SCHEMA_META_FILENAME)
        return meta.exists() && meta.length() > 0L
    }

    fun hasXiaoheSchemaInstalled(): Boolean = File(sharedDir, "flypy.schema.yaml").exists()

    fun resolveCustomSchemaId(): String {
        val idFile = File(sharedDir, CUSTOM_SCHEMA_ID_FILENAME)
        if (idFile.exists()) {
            val saved = runCatching { idFile.readText(Charsets.UTF_8).trim() }.getOrNull()
            if (!saved.isNullOrBlank()) return saved
        }
        val builtinSchemas = setOf(
            "rime_ice.schema.yaml",
            "double_pinyin_flypy.schema.yaml",
            "flypy.schema.yaml"
        )
        val customSchemaFile = sharedDir.listFiles().orEmpty()
            .filter {
                it.isFile &&
                    it.name.endsWith(".schema.yaml") &&
                    it.name !in builtinSchemas &&
                    !it.name.endsWith("dz.schema.yaml")
            }
            .sortedBy { it.name }
            .firstOrNull()
        if (customSchemaFile != null) {
            val parsedId = parseSchemaMetadata(customSchemaFile).first
            if (!parsedId.isNullOrBlank()) return parsedId
        }
        val activeId = activeSchemaProfile.schemaId
        if (activeId.isNotBlank() && activeId != "rime_ice" && activeId != "double_pinyin_flypy") {
            return activeId
        }
        return if (hasXiaoheSchemaInstalled()) "flypy" else "custom"
    }

    fun isSchemeHandledByEnhancedEngine(scheme: InputScheme): Boolean {
        return when (scheme) {
            InputScheme.FLYPY_TABLE -> hasXiaoheSchemaInstalled()
            InputScheme.RIME_CUSTOM -> hasCustomSchemaImported()
            else -> false
        }
    }

    fun activateEnhancedSchemeSync(scheme: InputScheme): Boolean {
        if (!isSchemeHandledByEnhancedEngine(scheme)) return false
        val targetSchemaId = when (scheme) {
            InputScheme.FLYPY_TABLE -> "flypy"
            InputScheme.RIME_CUSTOM -> resolveCustomSchemaId()
            else -> return false
        }
        refreshEnhancedMetadataFromDir(sharedDir, targetSchemaId)
        rimeBridge.selectSchema(targetSchemaId)
        return true
    }

    fun readCustomSchemaName(): String? {
        val meta = File(sharedDir, CUSTOM_SCHEMA_META_FILENAME)
        if (!meta.exists()) return null
        return runCatching { meta.readText(Charsets.UTF_8).trim().ifEmpty { null } }.getOrNull()
    }

    /**
     * Extracts built-in `iDvel/rime-ice` schemas and dictionaries into [sharedDir]
     * and runs `librime` deployment (`RimeStartMaintenance` + `build/<schema>.bin` generation).
     */
    fun deployDefaultSync(initialScheme: InputScheme = InputScheme.RIME_PINYIN): RimeDeploymentState {
        val t0 = System.currentTimeMillis()
        return try {
            _deploymentState.value = RimeDeploymentState.Deploying(0.15f, "正在准备 Rime 共享词库目录…")
            sharedDir.mkdirs()
            userDir.mkdirs()

            extractBuiltInAssetsIfNeeded(forceOverwrite = false)

            _deploymentState.value = RimeDeploymentState.Deploying(0.55f, "正在编译 librime 音节棱镜与词典表…")
            val hasXiaoheBundle = hasXiaoheSchemaInstalled()
            val schemaId = when {
                initialScheme == InputScheme.FLYPY_TABLE && hasXiaoheBundle -> "flypy"
                initialScheme == InputScheme.RIME_CUSTOM && hasCustomSchemaImported() -> resolveCustomSchemaId()
                initialScheme.rimeSchemaId.isNotBlank() -> initialScheme.rimeSchemaId
                hasXiaoheBundle && BuildConfig.IS_XIAOHE_FLAVOR -> "flypy"
                else -> "rime_ice"
            }
            rimeBridge.initialize(sharedDir.absolutePath, userDir.absolutePath)
            val ok = rimeBridge.deploy(schemaId)
            refreshEnhancedMetadataFromDir(sharedDir, schemaId)
            if (!ok && rimeBridge.isNativeLoaded) {
                throw IllegalStateException("librime 原生部署未完成 ($schemaId)")
            }

            val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            val ready = RimeDeploymentState.Ready(
                wordCount = rimeBridge.wordCount,
                hasFullRimeIce = hasFullRimeIceDictionary(),
                activeSchemaId = rimeBridge.activeSchemaId,
                customSchemaName = readCustomSchemaName(),
                deployDurationMs = elapsed
            )
            _deploymentState.value = ready
            ready
        } catch (e: Exception) {
            val err = RimeDeploymentState.Error(e.message ?: "Rime 部署失败")
            _deploymentState.value = err
            err
        }
    }

    suspend fun ensureDefaultDeployed(initialScheme: InputScheme = InputScheme.RIME_PINYIN): RimeDeploymentState {
        val current = _deploymentState.value
        if (current is RimeDeploymentState.Ready) return current
        return withContext(Dispatchers.IO) {
            deployDefaultSync(initialScheme)
        }
    }

    /**
     * Imports a Rime schema package (`.zip`) containing `.schema.yaml`, `.dict.yaml`, `.yaml`, `.tsv`, or `.txt`
     * into [sharedDir] and triggers a hot re-deploy.
     */
    fun importRimeSchemaZipSync(zipInputStream: InputStream): RimeDeploymentState {
        val t0 = System.currentTimeMillis()
        return try {
            _deploymentState.value = RimeDeploymentState.Deploying(0.10f, "正在解压 Rime 方案包 (.zip)…")
            sharedDir.mkdirs()
            userDir.mkdirs()
            extractBuiltInAssetsIfNeeded(forceOverwrite = false)

            var extractedFiles = 0
            var detectedSchemaId: String? = null
            var detectedSchemaName: String? = null

            val canonicalTargetRoot = sharedDir.canonicalPath + File.separator
            ZipInputStream(zipInputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val cleanRelative = entry.name
                            .replace('\\', '/')
                            .trimStart('/')
                        val rawName = File(cleanRelative).name
                        val lower = rawName.lowercase()
                        val isYamlOrDict = lower.endsWith(".yaml") ||
                            lower.endsWith(".tsv") ||
                            lower.endsWith(".txt")
                        val isLuaScript = lower.endsWith(".lua") &&
                            (cleanRelative == "rime.lua" || cleanRelative.startsWith("lua/"))
                        val isOpenccAsset = cleanRelative.startsWith("opencc/") && (
                            lower.endsWith(".json") ||
                                lower.endsWith(".ocd2") ||
                                lower.endsWith(".ocd") ||
                                lower.endsWith(".txt")
                            )
                        val isAllowed = isYamlOrDict || isLuaScript || isOpenccAsset
                        if (isAllowed && !rawName.startsWith(".") && !cleanRelative.contains("..")) {
                            val preserveRelativeDir = cleanRelative == "rime.lua" ||
                                cleanRelative.startsWith("cn_dicts/") ||
                                cleanRelative.startsWith("lua/") ||
                                cleanRelative.startsWith("opencc/")
                            val outFile = if (preserveRelativeDir) {
                                File(sharedDir, cleanRelative)
                            } else {
                                File(sharedDir, rawName)
                            }
                            outFile.parentFile?.mkdirs()
                            val canonicalOut = outFile.canonicalPath
                            if (!canonicalOut.startsWith(canonicalTargetRoot)) {
                                throw SecurityException("非法压缩包路径: ${entry.name}")
                            }
                            FileOutputStream(outFile).use { fos ->
                                zis.copyTo(fos)
                            }
                            extractedFiles++

                            if (lower.endsWith(".schema.yaml")) {
                                val (sid, sname) = parseSchemaMetadata(outFile)
                                if (sid != null && detectedSchemaId == null) detectedSchemaId = sid
                                if (sname != null && detectedSchemaName == null) detectedSchemaName = sname
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (extractedFiles == 0) {
                throw IllegalArgumentException("压缩包内未找到有效的 Rime 词库或方案文件 (.yaml / .dict.yaml / .tsv / .txt)")
            }

            val metaLabel = detectedSchemaName ?: detectedSchemaId ?: "自定义 Rime 方案 ($extractedFiles 个文件)"
            File(sharedDir, CUSTOM_SCHEMA_META_FILENAME).writeText(metaLabel, Charsets.UTF_8)

            _deploymentState.value = RimeDeploymentState.Deploying(0.75f, "正在重新部署 Rime 方案「$metaLabel」…")
            val targetSchema = detectedSchemaId ?: rimeBridge.activeSchemaId
            File(sharedDir, CUSTOM_SCHEMA_ID_FILENAME).writeText(targetSchema, Charsets.UTF_8)
            invalidateCompiledTableFiles()
            rimeBridge.initialize(sharedDir.absolutePath, userDir.absolutePath)
            rimeBridge.deploy(targetSchema)

            val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            val ready = RimeDeploymentState.Ready(
                wordCount = rimeBridge.wordCount,
                hasFullRimeIce = hasFullRimeIceDictionary(),
                activeSchemaId = rimeBridge.activeSchemaId,
                customSchemaName = metaLabel,
                deployDurationMs = elapsed
            )
            _deploymentState.value = ready
            ready
        } catch (e: Exception) {
            val err = RimeDeploymentState.Error(e.message ?: "导入 Rime 方案包失败")
            _deploymentState.value = err
            throw e
        }
    }

    suspend fun importRimeSchemaZip(zipInputStream: InputStream): RimeDeploymentState {
        return withContext(Dispatchers.IO) {
            importRimeSchemaZipSync(zipInputStream)
        }
    }

    /**
     * Verifies the downloaded `rime-ice` `cn_dicts/base.dict.yaml` stream against [expectedSha256]
     * before replacing `cn_dicts/base.dict.yaml` and triggering `librime` table compilation.
     *
     * Rejects deployment with [SecurityException] and transitions [deploymentState] to
     * [RimeDeploymentState.Error] if the SHA-256 digest does not match.
     */
    fun installFullRimeIceFromStreamSync(
        inputStream: InputStream,
        expectedSha256: String = RIME_ICE_BASE_DICT_SHA256
    ): RimeDeploymentState {
        val t0 = System.currentTimeMillis()
        return try {
            sharedDir.mkdirs()
            userDir.mkdirs()
            extractBuiltInAssetsIfNeeded(forceOverwrite = false)

            _deploymentState.value = RimeDeploymentState.Deploying(0.40f, "正在校验雾凇拼音词库 SHA-256 签名…")
            val targetFile = File(sharedDir, BASE_DICT_RELATIVE_PATH)
            targetFile.parentFile?.mkdirs()
            val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")

            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read = input.read(buffer)
                    while (read > 0) {
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        totalBytes += read
                        read = input.read(buffer)
                    }
                }
            }

            if (totalBytes <= 0L) {
                tmpFile.delete()
                throw IllegalArgumentException("下载的雾凇词库内容为空")
            }

            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val normalizedExpected = expectedSha256.trim().lowercase()
            if (normalizedExpected.isNotEmpty() && actualSha256 != normalizedExpected) {
                tmpFile.delete()
                throw SecurityException(
                    "词库 SHA-256 校验失败，已拒绝部署 (expected=${normalizedExpected.take(12)}…, actual=${actualSha256.take(12)}…)"
                )
            }

            val entryCount = countDictEntriesInFile(tmpFile)
            if (entryCount == 0) {
                tmpFile.delete()
                throw IllegalArgumentException("下载的雾凇词库格式无效（未找到有效词条）")
            }

            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            File(sharedDir, FULL_DICT_MARKER_FILENAME).writeText(actualSha256, Charsets.UTF_8)

            _deploymentState.value = RimeDeploymentState.Deploying(0.80f, "正在编译完整雾凇词库 ($entryCount 词条)…")
            invalidateCompiledTableFiles()
            rimeBridge.initialize(sharedDir.absolutePath, userDir.absolutePath)
            rimeBridge.deploy(rimeBridge.activeSchemaId)

            val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            val ready = RimeDeploymentState.Ready(
                wordCount = rimeBridge.wordCount.takeIf { it > 0 } ?: entryCount,
                hasFullRimeIce = true,
                activeSchemaId = rimeBridge.activeSchemaId,
                customSchemaName = readCustomSchemaName(),
                deployDurationMs = elapsed
            )
            _deploymentState.value = ready
            ready
        } catch (e: Exception) {
            val err = RimeDeploymentState.Error(e.message ?: "雾凇词库校验或部署失败")
            _deploymentState.value = err
            throw e
        }
    }

    /**
     * Downloads the pinned upstream `iDvel/rime-ice` dictionary (`tag 2026.06.30`) over HTTPS,
     * verifies its SHA-256 checksum ([RIME_ICE_BASE_DICT_SHA256]), and deploys it into [sharedDir].
     */
    suspend fun downloadFullRimeIceDictionary(): RimeDeploymentState = withContext(Dispatchers.IO) {
        try {
            _deploymentState.value = RimeDeploymentState.Deploying(
                progress = 0.12f,
                message = "正在从 GitHub 下载雾凇拼音 ($RIME_ICE_RELEASE_TAG)…"
            )
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build()

            val req = Request.Builder().url(RIME_ICE_BASE_DICT_URL).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IllegalStateException("空响应体")
                body.byteStream().use { stream ->
                    installFullRimeIceFromStreamSync(
                        inputStream = stream,
                        expectedSha256 = RIME_ICE_BASE_DICT_SHA256
                    )
                }
            }
        } catch (e: Exception) {
            val err = RimeDeploymentState.Error("下载或校验失败: ${e.message ?: "网络异常"}")
            _deploymentState.value = err
            throw e
        }
    }

    fun restoreDefaultRimeDataSync(scheme: InputScheme = InputScheme.RIME_PINYIN): RimeDeploymentState {
        File(sharedDir, FULL_DICT_MARKER_FILENAME).delete()
        File(sharedDir, CUSTOM_SCHEMA_META_FILENAME).delete()
        File(sharedDir, CUSTOM_SCHEMA_ID_FILENAME).delete()
        val builtinSet = BUILTIN_ASSET_FILES.toSet()
        sharedDir.listFiles().orEmpty().forEach { f ->
            if (f.isFile && f.name !in builtinSet) {
                f.delete()
            }
        }
        val cnDictsDir = File(sharedDir, "cn_dicts")
        if (cnDictsDir.exists()) {
            cnDictsDir.walkBottomUp().forEach { f ->
                if (f != cnDictsDir) {
                    val rel = f.relativeTo(sharedDir).invariantSeparatorsPath
                    if (f.isFile && rel !in builtinSet) {
                        f.delete()
                    } else if (f.isDirectory && f.listFiles().isNullOrEmpty()) {
                        f.delete()
                    }
                }
            }
        }
        File(sharedDir, "lua").deleteRecursively()
        File(sharedDir, "opencc").deleteRecursively()
        extractBuiltInAssetsIfNeeded(forceOverwrite = true)
        invalidateCompiledTableFiles()
        return deployDefaultSync(scheme)
    }

    suspend fun restoreDefaultRimeData(scheme: InputScheme = InputScheme.RIME_PINYIN): RimeDeploymentState {
        return withContext(Dispatchers.IO) {
            restoreDefaultRimeDataSync(scheme)
        }
    }

    private fun invalidateCompiledTableFiles() {
        listOf(sharedDir, userDir).forEach { root ->
            val buildDir = File(root, "build")
            if (buildDir.exists()) {
                buildDir.listFiles().orEmpty().forEach { f ->
                    if (f.name.endsWith(".bin") || f.name.endsWith(".yaml")) {
                        runCatching { f.delete() }
                    }
                }
            }
        }
    }

    /**
     * Dual-track `.zip` schema package import (5.1 Plain `.dict.yaml` Staging Compile +
     * 5.2 Precompiled `build/<schema>.table.bin` Passthrough with 702-slot JNI dynamic probing).
     */
    fun importEnhancedSchemaPackageSync(zipInputStream: InputStream): SchemaImportResult {
        val t0 = System.currentTimeMillis()
        return try {
            _deploymentState.value = RimeDeploymentState.Deploying(0.50f, "正在导入并校验方案包…")
            sharedDir.mkdirs()
            userDir.mkdirs()
            extractBuiltInAssetsIfNeeded(forceOverwrite = false)

            val stagingRoot = File(appContext?.cacheDir ?: File(sharedDir.parentFile, "cache"), "schema_staging")
            val schemasRoot = File(sharedDir.parentFile ?: sharedDir, "schemas")
            val batchProber = object : RimeCodeProber {
                override fun probeCandidates(code: String, maxCount: Int): List<String> =
                    rimeBridge.probeCandidatesForCode(code, maxCount)

                override fun probeBatch(codes: List<String>, maxCount: Int): List<List<String>> =
                    rimeBridge.probeBatchCandidatesForCodes(codes, maxCount)
            }
            val importer = SchemaPackageImporter(
                stagingParentDir = stagingRoot,
                targetSchemasRootDir = schemasRoot,
                stagingCompiler = { dir, sid ->
                    rimeBridge.initialize(dir.absolutePath, userDir.absolutePath)
                    if (rimeBridge.isNativeLoaded) rimeBridge.deploy(sid) else true
                },
                codeProberFactory = { dir, sid ->
                    rimeBridge.initialize(dir.absolutePath, userDir.absolutePath)
                    rimeBridge.selectSchema(sid)
                    batchProber
                }
            )
            val result = importer.importZip(zipInputStream)
            // Sync imported schema files into active sharedDir without invalidating staging or precompiled build/*.bin
            result.installedDir.walkTopDown().filter { it.isFile }.forEach { src ->
                val rel = src.relativeTo(result.installedDir).invariantSeparatorsPath
                val dst = File(sharedDir, rel)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
            if (result.importMode == ImportMode.PLAIN_DICT_COMPILE) {
                rimeBridge.initialize(sharedDir.absolutePath, userDir.absolutePath)
                rimeBridge.deploy(result.schemaId)
            } else {
                val sanitizedRootSchema = File(sharedDir, "${result.schemaId}.schema.yaml")
                val prebuiltSchema = File(sharedDir, "build/${result.schemaId}.schema.yaml")
                if (sanitizedRootSchema.exists() && prebuiltSchema.exists()) {
                    sanitizedRootSchema.copyTo(prebuiltSchema, overwrite = true)
                }
                rimeBridge.initialize(sharedDir.absolutePath, userDir.absolutePath)
                rimeBridge.selectSchema(result.schemaId)
            }
            activeSchemaProfile = result.profile
            activeBriefIndex = result.briefIndex
            activeSymbolTrie = result.quickSymbolTrie
            activeReverseLookupMap = emptyMap()
            refreshReverseLookupFromDir(result.installedDir, result.profile.reverseLookupDict)
            File(sharedDir, CUSTOM_SCHEMA_META_FILENAME).writeText(result.displayName, Charsets.UTF_8)
            File(sharedDir, CUSTOM_SCHEMA_ID_FILENAME).writeText(result.schemaId, Charsets.UTF_8)
            val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            _deploymentState.value = RimeDeploymentState.Ready(
                wordCount = rimeBridge.wordCount,
                hasFullRimeIce = hasFullRimeIceDictionary(),
                activeSchemaId = result.schemaId,
                customSchemaName = result.displayName,
                deployDurationMs = elapsed
            )
            result
        } catch (e: Exception) {
            _deploymentState.value = RimeDeploymentState.Error(e.message ?: "导入方案包失败")
            throw e
        }
    }

    suspend fun importEnhancedSchemaPackage(zipInputStream: InputStream): SchemaImportResult {
        return withContext(Dispatchers.IO) {
            importEnhancedSchemaPackageSync(zipInputStream)
        }
    }

    private fun refreshEnhancedMetadataFromDir(dir: File, schemaId: String) {
        val schemaFile = File(dir, "$schemaId.schema.yaml")
        val tomlFile = File(dir, "typefree.toml")
        val dictFile = File(dir, "$schemaId.dict.yaml")
        val quickFile = File(dir, "quick_symbols.yaml")
        val briefSnap = File(dir, "$schemaId.brief.idx")

        activeSymbolTrie = if (quickFile.exists()) {
            runCatching {
                SymbolTrie.parseQuickSymbolsYaml(quickFile.readText(Charsets.UTF_8))
            }.getOrDefault(SymbolTrie.defaultXiaohe())
        } else {
            SymbolTrie.defaultXiaohe()
        }
        if (schemaFile.exists()) {
            runCatching {
                activeSchemaProfile = SchemaInspector.inspect(
                    schemaYaml = schemaFile.readText(Charsets.UTF_8),
                    dictYamlContent = if (dictFile.exists()) dictFile.readText(Charsets.UTF_8) else null,
                    tomlOverrideContent = if (tomlFile.exists()) tomlFile.readText(Charsets.UTF_8) else null
                )
            }
        } else {
            activeSchemaProfile = if (BuildConfig.IS_XIAOHE_FLAVOR && schemaId == "flypy") {
                SchemaProfile.defaultXiaoheFlypy()
            } else {
                SchemaProfile.defaultSentencePinyin(schemaId = schemaId)
            }
        }
        activeBriefIndex = if (activeSchemaProfile.reverseSplitEnabled) {
            runCatching {
                when {
                    briefSnap.exists() -> BriefPrefixIndex.readSnapshot(briefSnap)
                    dictFile.exists() -> {
                        val built = BriefPrefixIndex.buildFromDictLines(
                            mainDictLines = dictFile.readLines(Charsets.UTF_8).asSequence(),
                            topDictLines = File(dir, "${schemaId}_top.txt").takeIf { it.exists() }
                                ?.readLines(Charsets.UTF_8).orEmpty().asSequence(),
                            userDictLines = File(dir, "${schemaId}_user.txt").takeIf { it.exists() }
                                ?.readLines(Charsets.UTF_8).orEmpty().asSequence(),
                            gt4GuardTwoPrefixes = activeSchemaProfile.gt4GuardPrefixes
                        )
                        runCatching { built.writeSnapshot(briefSnap) }
                        built
                    }
                    else -> BriefPrefixIndex.buildFrom702SlotProbing(
                        prober = object : RimeCodeProber {
                            override fun probeCandidates(code: String, maxCount: Int): List<String> =
                                rimeBridge.probeCandidatesForCode(code, maxCount)

                            override fun probeBatch(codes: List<String>, maxCount: Int): List<List<String>> =
                                rimeBridge.probeBatchCandidatesForCodes(codes, maxCount)
                        },
                        affixGuardPrefixes = activeSchemaProfile.gt4GuardPrefixes
                    )
                }
            }.getOrDefault(BriefPrefixIndex.empty())
        } else {
            BriefPrefixIndex.empty()
        }
        activeReverseLookupMap = emptyMap()
        refreshReverseLookupFromDir(dir, activeSchemaProfile.reverseLookupDict)
    }

    private fun refreshReverseLookupFromDir(dir: File, reverseDictName: String?) {
        val dzName = reverseDictName ?: return
        val dzFile = File(dir, "$dzName.dict.yaml")
        if (dzFile.exists()) {
            runCatching {
                activeReverseLookupMap = DirectCommandEngine.loadReverseLookupFromDictLines(
                    dzFile.readLines(Charsets.UTF_8).asSequence()
                )
            }
        }
    }

    private fun extractBuiltInAssetsIfNeeded(forceOverwrite: Boolean) {
        val ctx = appContext
        if (ctx != null) {
            val allAssets = BUILTIN_ASSET_FILES + XIAOHE_FLAVOR_OPTIONAL_ASSETS
            for (assetName in allAssets) {
                val target = File(sharedDir, assetName)
                target.parentFile?.mkdirs()
                if (forceOverwrite || !target.exists() || target.length() == 0L) {
                    runCatching {
                        ctx.assets.open("rime/$assetName").use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
            return
        }

        // Unit-test fallback when appContext is null: copy from workspace assets/rime if present
        val localAssetDirs = listOf(
            File("src/main/assets/rime"),
            File("app/src/main/assets/rime"),
            File("../app/src/main/assets/rime")
        )
        val sourceDir = localAssetDirs.firstOrNull { it.exists() && it.isDirectory } ?: return
        for (assetName in BUILTIN_ASSET_FILES) {
            val src = File(sourceDir, assetName)
            val dst = File(sharedDir, assetName)
            dst.parentFile?.mkdirs()
            if (src.exists() && (forceOverwrite || !dst.exists() || dst.length() == 0L)) {
                src.copyTo(dst, overwrite = true)
            }
        }
    }

    private fun countDictEntriesInFile(file: File): Int {
        if (!file.exists()) return 0
        var count = 0
        var inYamlHeader = false
        runCatching {
            file.forEachLine(Charsets.UTF_8) { raw ->
                val trimmed = raw.trim()
                if (trimmed == "---") {
                    inYamlHeader = true
                } else if (inYamlHeader) {
                    if (trimmed == "...") inYamlHeader = false
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains('\t')) {
                    count++
                }
            }
        }
        return count
    }

    private fun parseSchemaMetadata(schemaFile: File): Pair<String?, String?> {
        var schemaId: String? = null
        var schemaName: String? = null
        runCatching {
            schemaFile.forEachLine(Charsets.UTF_8) { raw ->
                val line = raw.trim()
                if (line.startsWith("schema_id:")) {
                    schemaId = line.substringAfter("schema_id:").trim().trim('"', '\'')
                } else if (line.startsWith("name:") && schemaName == null) {
                    schemaName = line.substringAfter("name:").trim().trim('"', '\'')
                }
            }
        }
        return schemaId to schemaName
    }

    companion object {
        const val BASE_DICT_RELATIVE_PATH = "cn_dicts/base.dict.yaml"
        const val CHAR_DICT_RELATIVE_PATH = "cn_dicts/8105.dict.yaml"
        const val FULL_DICT_MARKER_FILENAME = ".full_rime_ice_sha256"
        const val CUSTOM_SCHEMA_META_FILENAME = ".custom_schema_name"
        const val CUSTOM_SCHEMA_ID_FILENAME = ".custom_schema_id"

        val BUILTIN_ASSET_FILES = listOf(
            "default.yaml",
            "rime_ice.schema.yaml",
            "double_pinyin_flypy.schema.yaml",
            "rime_ice.dict.yaml",
            CHAR_DICT_RELATIVE_PATH,
            BASE_DICT_RELATIVE_PATH
        )

        val XIAOHE_FLAVOR_OPTIONAL_ASSETS = listOf(
            "flypy.schema.yaml",
            "flypy.dict.yaml",
            "flypy_top.txt",
            "flypy_sys.txt",
            "flypy_user.txt",
            "flypy_full.txt",
            "flypy_ok.txt",
            "flypydz.schema.yaml",
            "flypydz.dict.yaml",
            "quick_symbols.yaml",
            "typefree.toml"
        )

        /**
         * Pinned upstream `iDvel/rime-ice` release (`2026.06.30`, commit `6810e8916d160498620a16fef2135956fecbd485`)
         * and its exact SHA-256 digest for `cn_dicts/base.dict.yaml` (no third-party proxy).
         */
        const val RIME_ICE_RELEASE_TAG = "2026.06.30"
        const val RIME_ICE_RELEASE_COMMIT = "6810e8916d160498620a16fef2135956fecbd485"
        const val RIME_ICE_BASE_DICT_URL =
            "https://raw.githubusercontent.com/iDvel/rime-ice/$RIME_ICE_RELEASE_COMMIT/cn_dicts/base.dict.yaml"
        const val RIME_ICE_BASE_DICT_SHA256 =
            "5a10ef44d5939071c026d954e181c5ebea2b96468da84da391966866d12cbe9f"

        fun computeFileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(16 * 1024)
                var n = fis.read(buf)
                while (n > 0) {
                    digest.update(buf, 0, n)
                    n = fis.read(buf)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
