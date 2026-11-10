package com.typefree.ime.ime.engine

import android.content.Context
import com.typefree.ime.data.InputScheme
import com.typefree.rime.RimeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
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
        val customSchemaName: String? = null
    ) : RimeDeploymentState

    data class Error(
        val message: String
    ) : RimeDeploymentState
}

/**
 * Manages `filesDir/rime/shared` and `filesDir/rime/user`, background deployment,
 * on-demand `rime-ice` full lexicon downloading, and `.zip` Rime schema package importing.
 */
class RimeDataManager(
    val sharedDir: File,
    val userDir: File,
    val rimeBridge: RimeBridge = RimeBridge(),
    private val appContext: Context? = null
) {
    constructor(context: Context, rimeBridge: RimeBridge = RimeBridge()) : this(
        sharedDir = File(context.filesDir, "rime/shared"),
        userDir = File(context.filesDir, "rime/user"),
        rimeBridge = rimeBridge,
        appContext = context.applicationContext
    )

    private val _deploymentState = MutableStateFlow<RimeDeploymentState>(RimeDeploymentState.Idle)
    val deploymentState: StateFlow<RimeDeploymentState> = _deploymentState.asStateFlow()

    val isReady: Boolean
        get() = _deploymentState.value is RimeDeploymentState.Ready

    fun hasFullRimeIceDictionary(): Boolean {
        val f = File(sharedDir, FULL_DICT_FILENAME)
        return f.exists() && f.length() > 1024L
    }

    fun hasCustomSchemaImported(): Boolean {
        val meta = File(sharedDir, CUSTOM_SCHEMA_META_FILENAME)
        return meta.exists() && meta.length() > 0L
    }

    fun readCustomSchemaName(): String? {
        val meta = File(sharedDir, CUSTOM_SCHEMA_META_FILENAME)
        if (!meta.exists()) return null
        return runCatching { meta.readText(Charsets.UTF_8).trim().ifEmpty { null } }.getOrNull()
    }

    /**
     * Extracts built-in compact Rime assets into [sharedDir] and runs initial deployment.
     * Safe to call on a background dispatcher during app / IME startup.
     */
    fun deployDefaultSync(initialScheme: InputScheme = InputScheme.RIME_PINYIN): RimeDeploymentState {
        return try {
            _deploymentState.value = RimeDeploymentState.Deploying(0.15f, "正在准备 Rime 共享词库目录…")
            sharedDir.mkdirs()
            userDir.mkdirs()

            extractBuiltInAssetsIfNeeded()

            _deploymentState.value = RimeDeploymentState.Deploying(0.60f, "正在构建 Rime 音节与词频索引…")
            val schemaId = initialScheme.rimeSchemaId.ifBlank { "rime_ice" }
            rimeBridge.initialize(sharedDir.absolutePath, userDir.absolutePath)
            rimeBridge.deploy(schemaId)

            val ready = RimeDeploymentState.Ready(
                wordCount = rimeBridge.wordCount,
                hasFullRimeIce = hasFullRimeIceDictionary(),
                activeSchemaId = rimeBridge.activeSchemaId,
                customSchemaName = readCustomSchemaName()
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
     * Imports a Rime schema package (`.zip`) containing `.schema.yaml`, `.dict.yaml`, `.tsv`, or `.txt` files
     * into [sharedDir] and triggers a hot re-deploy.
     */
    fun importRimeSchemaZipSync(zipInputStream: InputStream): RimeDeploymentState {
        return try {
            _deploymentState.value = RimeDeploymentState.Deploying(0.10f, "正在解压 Rime 方案包 (.zip)…")
            sharedDir.mkdirs()
            userDir.mkdirs()
            extractBuiltInAssetsIfNeeded()

            var extractedFiles = 0
            var detectedSchemaId: String? = null
            var detectedSchemaName: String? = null

            val canonicalTargetRoot = sharedDir.canonicalPath + File.separator
            ZipInputStream(zipInputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val rawName = File(entry.name).name
                        val lower = rawName.lowercase()
                        val isAllowed = lower.endsWith(".yaml") ||
                            lower.endsWith(".tsv") ||
                            lower.endsWith(".txt")
                        if (isAllowed && !rawName.startsWith(".")) {
                            val outFile = File(sharedDir, rawName)
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
            rimeBridge.initialize(sharedDir.absolutePath, userDir.absolutePath)
            rimeBridge.deploy(targetSchema)

            val ready = RimeDeploymentState.Ready(
                wordCount = rimeBridge.wordCount,
                hasFullRimeIce = hasFullRimeIceDictionary(),
                activeSchemaId = rimeBridge.activeSchemaId,
                customSchemaName = metaLabel
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
     * Installs a full `rime-ice` dictionary stream (`word\tpinyin\tweight`) into `rime_ice_full.tsv`
     * and re-deploys the engine.
     */
    fun installFullRimeIceFromStreamSync(inputStream: InputStream): RimeDeploymentState {
        sharedDir.mkdirs()
        userDir.mkdirs()
        extractBuiltInAssetsIfNeeded()

        _deploymentState.value = RimeDeploymentState.Deploying(0.35f, "正在解析并写入雾凇拼音完整词库…")
        val tmpFile = File(sharedDir, "$FULL_DICT_FILENAME.tmp")
        val targetFile = File(sharedDir, FULL_DICT_FILENAME)
        var writtenLines = 0

        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            OutputStreamWriter(FileOutputStream(tmpFile), StandardCharsets.UTF_8).use { writer ->
                writer.write("# Rime-ice full lexicon\n")
                var inYamlHeader = false
                var line = reader.readLine()
                while (line != null) {
                    val trimmed = line.trimEnd('\r').trim()
                    if (trimmed == "---") {
                        inYamlHeader = true
                        line = reader.readLine()
                        continue
                    }
                    if (inYamlHeader) {
                        if (trimmed == "...") inYamlHeader = false
                        line = reader.readLine()
                        continue
                    }
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains('\t')) {
                        writer.write(trimmed)
                        writer.write("\n")
                        writtenLines++
                    }
                    line = reader.readLine()
                }
            }
        }

        if (writtenLines == 0) {
            tmpFile.delete()
            throw IllegalArgumentException("下载的雾凇词库内容为空或格式无效")
        }

        if (!tmpFile.renameTo(targetFile)) {
            tmpFile.copyTo(targetFile, overwrite = true)
            tmpFile.delete()
        }

        _deploymentState.value = RimeDeploymentState.Deploying(0.85f, "正在部署完整雾凇词库 ($writtenLines 词条)…")
        rimeBridge.initialize(sharedDir.absolutePath, userDir.absolutePath)
        rimeBridge.deploy(rimeBridge.activeSchemaId)

        val ready = RimeDeploymentState.Ready(
            wordCount = rimeBridge.wordCount,
            hasFullRimeIce = true,
            activeSchemaId = rimeBridge.activeSchemaId,
            customSchemaName = readCustomSchemaName()
        )
        _deploymentState.value = ready
        return ready
    }

    /**
     * Downloads the upstream `rime-ice` dictionary on demand and deploys it into [sharedDir].
     */
    suspend fun downloadFullRimeIceDictionary(): RimeDeploymentState = withContext(Dispatchers.IO) {
        try {
            _deploymentState.value = RimeDeploymentState.Deploying(0.10f, "正在连接雾凇拼音 (rime-ice) 词库源…")
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            var lastError: Exception? = null
            for ((idx, url) in RIME_ICE_DICT_URLS.withIndex()) {
                try {
                    _deploymentState.value = RimeDeploymentState.Deploying(
                        progress = 0.15f + idx * 0.10f,
                        message = "正在下载雾凇拼音词库 (${idx + 1}/${RIME_ICE_DICT_URLS.size})…"
                    )
                    val req = Request.Builder().url(url).get().build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("HTTP ${resp.code}")
                        }
                        val body = resp.body ?: throw IllegalStateException("空响应体")
                        return@withContext body.byteStream().use { stream ->
                            installFullRimeIceFromStreamSync(stream)
                        }
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("无法连接词库下载源")
        } catch (e: Exception) {
            val err = RimeDeploymentState.Error("下载失败: ${e.message ?: "网络异常"}")
            _deploymentState.value = err
            throw e
        }
    }

    suspend fun restoreDefaultRimeData(scheme: InputScheme = InputScheme.RIME_PINYIN): RimeDeploymentState {
        return withContext(Dispatchers.IO) {
            sharedDir.listFiles().orEmpty().forEach { f ->
                if (f.isFile && f.name !in BUILTIN_ASSET_FILES) {
                    f.delete()
                }
            }
            deployDefaultSync(scheme)
        }
    }

    private fun extractBuiltInAssetsIfNeeded() {
        val ctx = appContext
        if (ctx != null) {
            for (assetName in BUILTIN_ASSET_FILES) {
                val target = File(sharedDir, assetName)
                if (!target.exists() || target.length() == 0L) {
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
            File("app/src/main/assets/rime")
        )
        val sourceDir = localAssetDirs.firstOrNull { it.exists() && it.isDirectory } ?: return
        for (assetName in BUILTIN_ASSET_FILES) {
            val src = File(sourceDir, assetName)
            val dst = File(sharedDir, assetName)
            if (src.exists() && (!dst.exists() || dst.length() == 0L)) {
                src.copyTo(dst, overwrite = true)
            }
        }
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
        const val COMPACT_DICT_FILENAME = "rime_ice_compact.tsv"
        const val FULL_DICT_FILENAME = "rime_ice_full.tsv"
        const val CUSTOM_SCHEMA_META_FILENAME = ".custom_schema_name"

        val BUILTIN_ASSET_FILES = listOf(
            COMPACT_DICT_FILENAME,
            "default.yaml",
            "rime_ice.schema.yaml",
            "double_pinyin_flypy.schema.yaml"
        )

        val RIME_ICE_DICT_URLS = listOf(
            "https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/base.dict.yaml",
            "https://ghproxy.net/https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/base.dict.yaml"
        )
    }
}
