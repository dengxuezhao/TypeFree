package com.typefree.ime.localai

import android.content.Context
import com.typefree.core.offline.LocalModelDescriptor
import com.typefree.core.offline.LocalModelSlot
import com.typefree.core.offline.OfflineModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Raised when a downloaded model artifact fails SHA-256 integrity or archive security checks.
 */
class ModelIntegrityException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Production HTTPS-only resumable model downloader backed by OkHttp + Apache Commons Compress.
 *
 * Security & reliability guarantees:
 * 1. Rejects any non-`https://` URL (and blocks `https -> http` redirect downgrades), ignoring
 *    Manifest `usesCleartextTraffic="true"`.
 * 2. Supports HTTP `Range: bytes=<existing>-` resumption into `.part` files (206 append, 200
 *    restart, 416 reconciliation) with ordered `mirrorUrls` failover and immediate coroutine
 *    cancellation.
 * 3. Extracts `.tar.bz2` archives with strict Zip-Slip canonical-path containment checks, rejects
 *    symlinks/hardlinks, and flattens a single top-level wrapper directory so `.onnx` / `tokens.txt`
 *    are directly discoverable.
 * 4. Downloads and caches `silero_vad.int8.onnx` (~200KB) in a shared directory for non-streaming
 *    ASR models (`requiresSileroVad = true`) and copies it into the model directory.
 * 5. Computes and enforces SHA-256 when `descriptor.expectedSha256` is specified.
 */
class HttpResumableModelDownloader(
    private val okHttpClient: OkHttpClient = createDefaultHttpsClient(),
    private val defaultVadCacheDir: File? = null,
    private val sileroVadUrl: String = OfflineModelCatalog.SILERO_VAD_URL,
    private val sileroVadMirrorUrls: List<String> = OfflineModelCatalog.SILERO_VAD_MIRROR_URLS
) : LocalModelDownloader {

    constructor(context: Context) : this(
        okHttpClient = createDefaultHttpsClient(),
        defaultVadCacheDir = File(
            context.noBackupFilesDir,
            "${LocalModelRegistry.ROOT_DIR_NAME}/${LocalModelRegistry.SHARED_DIR_NAME}"
        )
    )

    override suspend fun downloadToPartFile(
        descriptor: LocalModelDescriptor,
        partFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<DownloadedArtifact> = withContext(Dispatchers.IO) {
        try {
            val slmSource = if (descriptor.slot == LocalModelSlot.SLM) {
                LocalInferenceEngineRegistry.getSlmDownloadSource(descriptor.id)
            } else {
                null
            }
            if (slmSource != null) {
                return@withContext downloadSlmSourceToPartFiles(
                    descriptor = descriptor,
                    source = slmSource,
                    basePartFile = partFile,
                    onProgress = onProgress
                )
            }

            val candidateUrls = buildValidatedHttpsCandidateUrls(
                primaryUrl = descriptor.downloadUrl,
                mirrorUrls = descriptor.mirrorUrls,
                label = descriptor.id
            )

            val totalBytes = downloadResumableWithMirrors(
                candidateUrls = candidateUrls,
                partFile = partFile,
                fallbackTotalBytes = descriptor.sizeBytes,
                onProgress = onProgress
            )

            val actualSha256 = LocalModelRegistry.computeFileSha256(partFile)
            val expectedSha = descriptor.expectedSha256.trim()
            if (expectedSha.isNotEmpty() && !actualSha256.equals(expectedSha, ignoreCase = true)) {
                runCatching { partFile.delete() }
                throw ModelIntegrityException(
                    "SHA-256 校验不匹配（期望 ${expectedSha.take(12)}…，实际 ${actualSha256.take(12)}…）"
                )
            }

            Result.success(
                DownloadedArtifact(
                    bytesWritten = partFile.length(),
                    reportedTotalBytes = maxOf(partFile.length(), totalBytes),
                    computedSha256 = actualSha256
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun downloadSlmSourceToPartFiles(
        descriptor: LocalModelDescriptor,
        source: SlmDownloadSource,
        basePartFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<DownloadedArtifact> {
        val baseUrls = buildValidatedHttpsCandidateUrls(
            primaryUrl = source.baseUrl,
            mirrorUrls = source.baseMirrorUrls,
            label = "${descriptor.id}:base"
        )
        val hasLora = source.loraUrl.isNotBlank()
        val loraUrls = if (hasLora) {
            buildValidatedHttpsCandidateUrls(
                primaryUrl = source.loraUrl,
                mirrorUrls = source.loraMirrorUrls,
                label = "${descriptor.id}:lora"
            )
        } else {
            emptyList()
        }
        val expectedBaseSize = source.baseSizeBytes.takeIf { it > 0L } ?: descriptor.sizeBytes
        val expectedLoraSize = if (hasLora) source.loraSizeBytes.coerceAtLeast(0L) else 0L

        val baseReportedTotal = downloadResumableWithMirrors(
            candidateUrls = baseUrls,
            partFile = basePartFile,
            fallbackTotalBytes = expectedBaseSize,
            onProgress = { dl, tot ->
                onProgress(dl, tot + expectedLoraSize)
            }
        )
        val baseActualBytes = basePartFile.length()
        val baseSha256 = LocalModelRegistry.computeFileSha256(basePartFile)
        val expectedBaseSha = source.baseExpectedSha256.trim()
        if (expectedBaseSha.isNotEmpty() && !baseSha256.equals(expectedBaseSha, ignoreCase = true)) {
            runCatching { basePartFile.delete() }
            throw ModelIntegrityException(
                "底座模型 SHA-256 校验不匹配（期望 ${expectedBaseSha.take(12)}…，实际 ${baseSha256.take(12)}…）"
            )
        }

        var loraActualBytes = 0L
        var loraReportedTotal = 0L
        val loraPartFile = siblingLoraPartFile(basePartFile)
        if (hasLora) {
            loraReportedTotal = downloadResumableWithMirrors(
                candidateUrls = loraUrls,
                partFile = loraPartFile,
                fallbackTotalBytes = expectedLoraSize,
                onProgress = { dl, tot ->
                    onProgress(baseActualBytes + dl, baseActualBytes + tot)
                }
            )
            loraActualBytes = loraPartFile.length()
            val loraSha256 = LocalModelRegistry.computeFileSha256(loraPartFile)
            val expectedLoraSha = source.loraExpectedSha256.trim()
            if (expectedLoraSha.isNotEmpty() && !loraSha256.equals(expectedLoraSha, ignoreCase = true)) {
                runCatching { loraPartFile.delete() }
                throw ModelIntegrityException(
                    "LoRA 权重 SHA-256 校验不匹配（期望 ${expectedLoraSha.take(12)}…，实际 ${loraSha256.take(12)}…）"
                )
            }
        } else {
            runCatching { loraPartFile.delete() }
        }

        val combinedWritten = baseActualBytes + loraActualBytes
        val combinedTotal = maxOf(combinedWritten, baseReportedTotal + loraReportedTotal)
        return Result.success(
            DownloadedArtifact(
                bytesWritten = combinedWritten,
                reportedTotalBytes = combinedTotal,
                computedSha256 = baseSha256
            )
        )
    }

    override suspend fun stageVerifiedPartFile(
        descriptor: LocalModelDescriptor,
        partFile: File,
        stagingDir: File,
        sharedCacheDir: File
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            if (!stagingDir.mkdirs() && !stagingDir.isDirectory) {
                throw IOException("无法创建暂存目录: ${stagingDir.absolutePath}")
            }

            if (descriptor.isTarBz2Archive) {
                val ctx = currentCoroutineContext()
                extractTarBz2Safely(
                    archiveFile = partFile,
                    targetDir = stagingDir,
                    isCancelled = { !ctx.isActive }
                )
                if (stagingDir.listFiles().orEmpty().isEmpty()) {
                    throw IOException("压缩包解压后未包含任何模型文件: ${descriptor.id}")
                }
                runCatching { partFile.delete() }
            } else if (descriptor.slot == LocalModelSlot.SLM) {
                val targetGguf = File(stagingDir, "model.gguf")
                if (!partFile.renameTo(targetGguf)) {
                    partFile.copyTo(targetGguf, overwrite = true)
                    partFile.delete()
                }
                val loraPart = siblingLoraPartFile(partFile)
                if (loraPart.isFile && loraPart.length() > 0L) {
                    val targetLora = File(stagingDir, "lora.gguf")
                    if (!loraPart.renameTo(targetLora)) {
                        loraPart.copyTo(targetLora, overwrite = true)
                        loraPart.delete()
                    }
                }
            } else {
                val targetBin = File(stagingDir, LocalModelRegistry.MODEL_BIN_NAME)
                if (!partFile.renameTo(targetBin)) {
                    partFile.copyTo(targetBin, overwrite = true)
                    partFile.delete()
                }
            }

            if (descriptor.requiresSileroVad) {
                ensureSileroVadInModelDir(
                    targetModelDir = stagingDir,
                    cacheDir = defaultVadCacheDir ?: sharedCacheDir
                )
            }

            Result.success(true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun ensureSileroVadInModelDir(
        targetModelDir: File,
        cacheDir: File
    ) {
        val targetVad = File(targetModelDir, OfflineModelCatalog.SILERO_VAD_FILE_NAME)
        if (targetVad.isFile && targetVad.length() > 0L) {
            return
        }

        cacheDir.mkdirs()
        val cachedVad = File(cacheDir, OfflineModelCatalog.SILERO_VAD_FILE_NAME)
        if (!cachedVad.isFile || cachedVad.length() <= 0L) {
            val vadPart = File(cacheDir, "${OfflineModelCatalog.SILERO_VAD_FILE_NAME}.part")
            val vadCandidates = buildValidatedHttpsCandidateUrls(
                primaryUrl = sileroVadUrl,
                mirrorUrls = sileroVadMirrorUrls,
                label = OfflineModelCatalog.SILERO_VAD_FILE_NAME
            )
            downloadResumableWithMirrors(
                candidateUrls = vadCandidates,
                partFile = vadPart,
                fallbackTotalBytes = 0L,
                onProgress = { _, _ -> }
            )
            if (vadPart.length() <= 0L) {
                runCatching { vadPart.delete() }
                throw IOException("Silero VAD 下载结果为空")
            }
            if (cachedVad.exists()) cachedVad.delete()
            if (!vadPart.renameTo(cachedVad)) {
                vadPart.copyTo(cachedVad, overwrite = true)
                vadPart.delete()
            }
        }

        cachedVad.copyTo(targetVad, overwrite = true)
    }

    private suspend fun downloadResumableWithMirrors(
        candidateUrls: List<HttpUrl>,
        partFile: File,
        fallbackTotalBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Long {
        partFile.parentFile?.mkdirs()
        val errors = mutableListOf<String>()

        for ((index, url) in candidateUrls.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                return downloadSingleUrl(
                    url = url,
                    partFile = partFile,
                    fallbackTotalBytes = fallbackTotalBytes,
                    onProgress = onProgress,
                    allowRestartOn416 = true
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SecurityException) {
                // Security violations (e.g., downgrade redirect to http://) abort immediately
                throw se
            } catch (err: Throwable) {
                val msg = "[源 ${index + 1}/${candidateUrls.size}] ${url.host}: ${err.message ?: err.javaClass.simpleName}"
                LocalAiLog.w("Model download attempt failed: $msg")
                errors.add(msg)
            }
        }

        throw IOException("模型下载失败（已尝试 ${candidateUrls.size} 个 HTTPS 源）：${errors.joinToString("; ")}")
    }

    private suspend fun downloadSingleUrl(
        url: HttpUrl,
        partFile: File,
        fallbackTotalBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        allowRestartOn416: Boolean
    ): Long {
        currentCoroutineContext().ensureActive()
        val existingBytes = if (partFile.isFile) partFile.length().coerceAtLeast(0L) else 0L

        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val call = okHttpClient.newCall(requestBuilder.build())
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion { cause ->
            if (cause != null) {
                call.cancel()
            }
        }

        try {
            call.execute().use { response ->
                if (!response.request.url.isHttps) {
                    throw SecurityException("检测到非 HTTPS 降级重定向，已中断下载: ${response.request.url}")
                }

                when (response.code) {
                    206 -> {
                        val contentRange = response.header("Content-Range")
                        val parsedRange = parseContentRange(contentRange)
                        val append = when {
                            parsedRange == null -> true
                            parsedRange.start == existingBytes -> true
                            parsedRange.start == 0L -> false
                            else -> throw IOException(
                                "服务端返回了不匹配的断点范围 Content-Range=$contentRange (local=$existingBytes)"
                            )
                        }
                        val body = response.body ?: throw IOException("响应体为空 (HTTP 206)")
                        val bodyLen = body.contentLength()
                        val totalBytes = parsedRange?.total
                            ?: if (bodyLen > 0L) (if (append) existingBytes + bodyLen else bodyLen) else fallbackTotalBytes
                        return streamResponseToPartFile(
                            bodyStream = body.byteStream(),
                            partFile = partFile,
                            append = append,
                            initialBytes = if (append) existingBytes else 0L,
                            expectedTotalBytes = totalBytes,
                            onProgress = onProgress
                        )
                    }

                    200 -> {
                        val body = response.body ?: throw IOException("响应体为空 (HTTP 200)")
                        val bodyLen = body.contentLength()
                        val totalBytes = if (bodyLen > 0L) bodyLen else fallbackTotalBytes
                        return streamResponseToPartFile(
                            bodyStream = body.byteStream(),
                            partFile = partFile,
                            append = false,
                            initialBytes = 0L,
                            expectedTotalBytes = totalBytes,
                            onProgress = onProgress
                        )
                    }

                    416 -> {
                        val totalFrom416 = parseTotalFrom416(response.header("Content-Range"))
                        if (totalFrom416 != null && totalFrom416 > 0L && existingBytes == totalFrom416) {
                            onProgress(existingBytes, totalFrom416)
                            return totalFrom416
                        }
                        if (allowRestartOn416 && partFile.exists()) {
                            partFile.delete()
                            return downloadSingleUrl(
                                url = url,
                                partFile = partFile,
                                fallbackTotalBytes = fallbackTotalBytes,
                                onProgress = onProgress,
                                allowRestartOn416 = false
                            )
                        }
                        throw IOException("HTTP 416 Range Not Satisfiable (${response.header("Content-Range")})")
                    }

                    else -> {
                        throw IOException("HTTP ${response.code} ${response.message}")
                    }
                }
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    private suspend fun streamResponseToPartFile(
        bodyStream: java.io.InputStream,
        partFile: File,
        append: Boolean,
        initialBytes: Long,
        expectedTotalBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Long {
        var written = initialBytes
        var lastEmitTimeMs = 0L
        var lastEmitBytes = initialBytes
        val buf = ByteArray(IO_BUFFER_SIZE)

        FileOutputStream(partFile, append).use { fos ->
            bodyStream.use { input ->
                if (initialBytes > 0L) {
                    val displayTotal = maxOf(expectedTotalBytes, initialBytes)
                    onProgress(initialBytes, displayTotal)
                }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buf)
                    if (read <= 0) break
                    fos.write(buf, 0, read)
                    written += read

                    val now = System.currentTimeMillis()
                    if (now - lastEmitTimeMs >= PROGRESS_THROTTLE_MS ||
                        written - lastEmitBytes >= PROGRESS_BYTE_STEP
                    ) {
                        val displayTotal = maxOf(expectedTotalBytes, written)
                        onProgress(written, displayTotal)
                        lastEmitTimeMs = now
                        lastEmitBytes = written
                    }
                }
                fos.flush()
            }
        }

        val finalTotal = maxOf(expectedTotalBytes, written)
        onProgress(written, finalTotal)
        return finalTotal
    }

    private data class ContentRangeInfo(
        val start: Long,
        val end: Long,
        val total: Long?
    )

    companion object {
        private const val USER_AGENT = "TypeFree-Android/3.7"
        private const val IO_BUFFER_SIZE = 32 * 1024
        private const val PROGRESS_THROTTLE_MS = 120L
        private const val PROGRESS_BYTE_STEP = 256L * 1024L

        private val CONTENT_RANGE_REGEX =
            Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)
        private val CONTENT_RANGE_416_REGEX =
            Regex("""^bytes\s+\*/(\d+)$""", RegexOption.IGNORE_CASE)

        fun createDefaultHttpsClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(false)
                .addInterceptor { chain ->
                    val req = chain.request()
                    if (!req.url.isHttps) {
                        throw SecurityException("安全策略禁止非 HTTPS 模型下载请求: ${req.url}")
                    }
                    chain.proceed(req)
                }
                .build()
        }

        /**
         * Strictly validates that [rawUrl] uses the `https://` scheme and has a non-empty host.
         * Throws [SecurityException] immediately for `http://`, `file://`, or malformed URLs.
         */
        fun validateHttpsUrl(rawUrl: String): HttpUrl {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) {
                throw SecurityException("下载地址为空")
            }
            if (!trimmed.startsWith("https://", ignoreCase = true)) {
                throw SecurityException("仅允许 HTTPS 下载地址，已拒绝: $trimmed")
            }
            val parsed = trimmed.toHttpUrlOrNull()
                ?: throw SecurityException("无效的 HTTPS 下载地址: $trimmed")
            if (!parsed.isHttps || parsed.host.isBlank()) {
                throw SecurityException("仅允许有效的 HTTPS 下载地址，已拒绝: $trimmed")
            }
            return parsed
        }

        fun buildValidatedHttpsCandidateUrls(
            primaryUrl: String,
            mirrorUrls: List<String>,
            label: String
        ): List<HttpUrl> {
            val rawList = buildList {
                if (primaryUrl.isNotBlank()) add(primaryUrl.trim())
                mirrorUrls.forEach { m ->
                    val t = m.trim()
                    if (t.isNotEmpty()) add(t)
                }
            }.distinct()

            if (rawList.isEmpty()) {
                throw IllegalArgumentException("模型未配置下载地址: $label")
            }
            return rawList.map { validateHttpsUrl(it) }
        }

        /**
         * Safely extracts a `.tar.bz2` archive into [targetDir]:
         * - Enforces Zip-Slip protection via canonical path containment (including Windows-style `..\`).
         * - Rejects symbolic links and hard links.
         * - Flattens a single top-level wrapper directory so model files (`.onnx`, `tokens.txt`)
         *   live directly under [targetDir].
         */
        fun extractTarBz2Safely(
            archiveFile: File,
            targetDir: File,
            isCancelled: () -> Boolean = { false }
        ) {
            if (!archiveFile.isFile) {
                throw IOException("待解压的压缩包不存在: ${archiveFile.absolutePath}")
            }
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IOException("无法创建解压目标目录: ${targetDir.absolutePath}")
            }
            val canonicalTargetDir = targetDir.canonicalFile
            val targetPrefix = canonicalTargetDir.path + File.separator
            val buffer = ByteArray(IO_BUFFER_SIZE)

            FileInputStream(archiveFile).use { fis ->
                BufferedInputStream(fis, IO_BUFFER_SIZE).use { bis ->
                    BZip2CompressorInputStream(bis, true).use { bz2 ->
                        TarArchiveInputStream(bz2).use { tar ->
                            while (true) {
                                if (isCancelled()) {
                                    throw CancellationException("解压已取消")
                                }
                                val entry = tar.nextEntry ?: break
                                val rawName = entry.name.orEmpty()
                                if (rawName.isBlank()) continue

                                if (entry.isSymbolicLink || entry.isLink) {
                                    throw SecurityException("拒绝解压含符号链接/硬链接的归档条目: $rawName")
                                }

                                val normalizedName = rawName.replace('\\', '/')
                                if (normalizedName.startsWith("/")) {
                                    throw SecurityException("检测到绝对路径归档条目（Zip-Slip）: $rawName")
                                }

                                val destFile = File(canonicalTargetDir, normalizedName)
                                val canonicalDest = destFile.canonicalFile
                                val destPath = canonicalDest.path
                                if (destPath != canonicalTargetDir.path && !destPath.startsWith(targetPrefix)) {
                                    throw SecurityException("检测到路径穿越（Zip-Slip）条目: $rawName")
                                }

                                if (entry.isDirectory) {
                                    if (!canonicalDest.exists() && !canonicalDest.mkdirs()) {
                                        throw IOException("无法创建解压子目录: ${canonicalDest.absolutePath}")
                                    }
                                } else if (entry.isFile) {
                                    val parent = canonicalDest.parentFile
                                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                                        throw IOException("无法创建父目录: ${parent.absolutePath}")
                                    }
                                    FileOutputStream(canonicalDest).use { fos ->
                                        while (true) {
                                            if (isCancelled()) {
                                                throw CancellationException("解压已取消")
                                            }
                                            val read = tar.read(buffer)
                                            if (read <= 0) break
                                            fos.write(buffer, 0, read)
                                        }
                                        fos.flush()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            flattenSingleTopLevelDirectory(canonicalTargetDir)
        }

        private fun flattenSingleTopLevelDirectory(targetDir: File) {
            val entries = targetDir.listFiles().orEmpty()
                .filter { it.name != ".DS_Store" && !it.name.startsWith("._") }
            if (entries.size != 1 || !entries[0].isDirectory) {
                return
            }
            val singleDir = entries[0]
            val wrapperTmp = File(targetDir, ".__flatten_wrapper_${System.nanoTime()}")
            if (!singleDir.renameTo(wrapperTmp)) {
                throw IOException("无法重命名顶层目录以展平归档: ${singleDir.name}")
            }
            try {
                wrapperTmp.listFiles().orEmpty().forEach { child ->
                    val destination = File(targetDir, child.name)
                    if (destination.exists()) {
                        destination.deleteRecursively()
                    }
                    if (!child.renameTo(destination)) {
                        child.copyRecursively(destination, overwrite = true)
                        child.deleteRecursively()
                    }
                }
            } finally {
                wrapperTmp.deleteRecursively()
            }
        }

        private fun parseContentRange(header: String?): ContentRangeInfo? {
            if (header.isNullOrBlank()) return null
            val match = CONTENT_RANGE_REGEX.matchEntire(header.trim()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull()
            return ContentRangeInfo(start = start, end = end, total = total)
        }

        fun siblingLoraPartFile(basePartFile: File): File =
            File(basePartFile.parentFile, "${basePartFile.name}.lora")

        private fun parseTotalFrom416(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            val match = CONTENT_RANGE_416_REGEX.matchEntire(header.trim()) ?: return null
            return match.groupValues[1].toLongOrNull()
        }
    }
}
