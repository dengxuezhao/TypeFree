package com.typefree.ime.localai

import android.content.Context
import android.os.StatFs
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.typefree.core.offline.LocalModelDescriptor
import com.typefree.core.offline.LocalModelInstallStatus
import com.typefree.core.offline.LocalModelRecord
import com.typefree.core.offline.LocalModelRegistrySnapshot
import com.typefree.core.offline.LocalModelSlot
import com.typefree.core.offline.OfflineModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

sealed interface DiskSpaceCheckResult {
    data class Sufficient(
        val availableBytes: Long,
        val requiredBytes: Long
    ) : DiskSpaceCheckResult

    data class RequiresDeleteOldConfirmation(
        val oldModelId: String,
        val oldModelSizeBytes: Long,
        val availableBytes: Long,
        val requiredBytes: Long
    ) : DiskSpaceCheckResult

    data class Insufficient(
        val availableBytes: Long,
        val requiredBytes: Long
    ) : DiskSpaceCheckResult
}

sealed interface ModelInstallOutcome {
    data class Success(
        val modelId: String,
        val modelDirPath: String,
        val sha256: String,
        val generation: Long,
        val replacedModelId: String? = null
    ) : ModelInstallOutcome

    data class NeedsDeleteOldConfirmation(
        val modelId: String,
        val oldModelId: String,
        val oldModelSizeBytes: Long,
        val availableBytes: Long,
        val requiredBytes: Long
    ) : ModelInstallOutcome

    data class InsufficientDiskSpace(
        val modelId: String,
        val availableBytes: Long,
        val requiredBytes: Long
    ) : ModelInstallOutcome

    data class DownloadFailed(
        val modelId: String,
        val reason: String
    ) : ModelInstallOutcome

    data class VerificationFailed(
        val modelId: String,
        val reason: String
    ) : ModelInstallOutcome

    data class ProbeFailed(
        val modelId: String,
        val reason: String,
        val keptOldModelId: String?
    ) : ModelInstallOutcome
}

data class DownloadedArtifact(
    val bytesWritten: Long,
    val reportedTotalBytes: Long,
    val computedSha256: String = ""
)

interface LocalModelDownloader {
    suspend fun downloadToPartFile(
        descriptor: LocalModelDescriptor,
        partFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<DownloadedArtifact>

    /**
     * Optional post-verification staging hook: e.g. extracting `.tar.bz2` archives into
     * [stagingDir] with Zip-Slip checks + top-level flattening and caching companion files
     * (such as `silero_vad.int8.onnx`) in [sharedCacheDir].
     *
     * Returns `Result.success(true)` if [stagingDir] has been populated by the downloader,
     * or `Result.success(false)` to fall back to moving `partFile` -> `stagingDir/model.bin`.
     */
    suspend fun stageVerifiedPartFile(
        descriptor: LocalModelDescriptor,
        partFile: File,
        stagingDir: File,
        sharedCacheDir: File
    ): Result<Boolean> = Result.success(false)
}

/**
 * Phase 1 simulated downloader writing a deterministic lightweight mock model payload into `.part`
 * in progressive chunks so UI progress, SHA-256 verification, atomic rename, and swap rollback can
 * be verified end-to-end without fetching 900MB+ files over the network.
 */
class SimulatedLocalModelDownloader(
    private val steps: Int = 5,
    private val stepDelayMs: Long = 20L,
    private val payloadSizeBytes: Int = 4096
) : LocalModelDownloader {

    override suspend fun downloadToPartFile(
        descriptor: LocalModelDescriptor,
        partFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<DownloadedArtifact> = withContext(Dispatchers.IO) {
        runCatching {
            partFile.parentFile?.mkdirs()
            val totalDisplayBytes = descriptor.sizeBytes.coerceAtLeast(payloadSizeBytes.toLong())
            val safeSteps = steps.coerceAtLeast(1)
            val header = "TYPEFREE_MOCK_MODEL:${descriptor.id}:v${descriptor.minEngineVersion}\n"
                .toByteArray(Charsets.UTF_8)
            val payload = ByteArray(payloadSizeBytes.coerceAtLeast(header.size))
            System.arraycopy(header, 0, payload, 0, header.size)
            for (i in header.size until payload.size) {
                payload[i] = ((i * 31 + descriptor.id.hashCode()) and 0xFF).toByte()
            }

            FileOutputStream(partFile).use { fos ->
                var offset = 0
                for (step in 1..safeSteps) {
                    val end = if (step == safeSteps) payload.size else (payload.size * step) / safeSteps
                    if (end > offset) {
                        fos.write(payload, offset, end - offset)
                        offset = end
                    }
                    val reportedDownloaded = if (step == safeSteps) {
                        totalDisplayBytes
                    } else {
                        (totalDisplayBytes * step) / safeSteps
                    }
                    onProgress(reportedDownloaded, totalDisplayBytes)
                    if (stepDelayMs > 0L && step < safeSteps) {
                        delay(stepDelayMs)
                    }
                }
                fos.flush()
            }

            DownloadedArtifact(
                bytesWritten = partFile.length(),
                reportedTotalBytes = totalDisplayBytes
            )
        }
    }
}

private data class PersistedRegistryFile(
    val schemaVersion: Int = 1,
    val modelGeneration: Long = 1L,
    val records: Map<String, LocalModelRecord> = emptyMap()
)

/**
 * Main-process on-device model asset registry.
 *
 * - Persists install state independently of `AppSettings` at `noBackupFilesDir/local_models/registry.json`.
 * - Exposes [snapshot] as `StateFlow<LocalModelRegistrySnapshot>`.
 * - Implements the safe single-model-per-slot swap state machine (disk precheck -> `.part` download
 *   -> SHA-256 verification -> atomic rename -> unload old A -> probe new B -> two-step commit or rollback).
 * - Cleans orphan weight directories and `.part` files automatically at startup.
 */
class LocalModelRegistry(
    private val rootDir: File,
    private val runtimeBridge: LocalModelRuntimeBridge,
    private val downloader: LocalModelDownloader = SimulatedLocalModelDownloader(),
    private val availableBytesProvider: () -> Long = { queryAvailableDiskBytes(rootDir) },
    private val slmEngineAvailableProvider: () -> Boolean = { LocalInferenceEngineRegistry.isSlmEngineAvailable },
    private val keepSingleModelPerSlot: Boolean = KEEP_SINGLE_MODEL_PER_SLOT,
    private val diskReserveBytes: Long = DISK_RESERVE_BYTES
) {
    constructor(
        context: Context,
        localAiClient: LocalAiServiceClient,
        downloader: LocalModelDownloader = HttpResumableModelDownloader(context)
    ) : this(
        rootDir = File(context.noBackupFilesDir, ROOT_DIR_NAME),
        runtimeBridge = localAiClient,
        downloader = downloader,
        availableBytesProvider = { queryAvailableDiskBytes(File(context.noBackupFilesDir, ROOT_DIR_NAME)) }
    )

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val registryFile: File = File(rootDir, REGISTRY_FILE_NAME)
    private val opMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _snapshot = MutableStateFlow(
        LocalModelRegistrySnapshot(
            records = emptyMap(),
            localServiceAlive = runtimeBridge.isServiceAlive.value,
            slmEngineAvailable = slmEngineAvailableProvider(),
            modelGeneration = 1L
        )
    )
    val snapshot: StateFlow<LocalModelRegistrySnapshot> = _snapshot.asStateFlow()

    val isSlmEngineAvailable: Boolean
        get() = slmEngineAvailableProvider()

    init {
        rootDir.mkdirs()
        loadPersistedStateAndRecover()
        cleanupOrphansAndPartFiles()
        observeServiceLiveness()
    }

    private fun observeServiceLiveness() {
        scope.launch {
            runtimeBridge.isServiceAlive.collect { alive ->
                updateSnapshot { it.copy(localServiceAlive = alive) }
            }
        }
    }

    fun updateLocalServiceAlive(alive: Boolean) {
        updateSnapshot { it.copy(localServiceAlive = alive) }
    }

    fun getInstalledModelDir(modelId: String?): String? {
        if (modelId.isNullOrBlank()) return null
        val rec = _snapshot.value.records[modelId] ?: return null
        if (rec.status != LocalModelInstallStatus.INSTALLED) return null
        return rec.localDirPath.takeIf { it.isNotBlank() && File(it).isDirectory }
    }

    /**
     * Checks disk availability before downloading [modelId]:
     * - [DiskSpaceCheckResult.Sufficient] if `avail >= size(B) + 500MB`
     * - [DiskSpaceCheckResult.RequiresDeleteOldConfirmation] if deleting same-slot installed model A frees enough space
     * - [DiskSpaceCheckResult.Insufficient] otherwise
     */
    fun checkDiskSpace(modelId: String): DiskSpaceCheckResult {
        val descriptor = OfflineModelCatalog.findById(modelId)
            ?: return DiskSpaceCheckResult.Insufficient(availableBytes = availableBytesProvider(), requiredBytes = Long.MAX_VALUE)

        val requiredBytes = descriptor.sizeBytes + diskReserveBytes
        val availableBytes = availableBytesProvider().coerceAtLeast(0L)
        if (availableBytes >= requiredBytes) {
            return DiskSpaceCheckResult.Sufficient(
                availableBytes = availableBytes,
                requiredBytes = requiredBytes
            )
        }

        val existingInSlot = findInstalledModelsInSlot(descriptor.slot)
            .filter { it.modelId != modelId }
        if (existingInSlot.isNotEmpty()) {
            val oldPrimary = existingInSlot.first()
            val reclaimableBytes = existingInSlot.sumOf { record ->
                val diskBytes = dirSizeBytes(File(record.localDirPath))
                val catalogBytes = OfflineModelCatalog.findById(record.modelId)?.sizeBytes ?: 0L
                maxOf(diskBytes, catalogBytes, record.totalBytes)
            }
            if (availableBytes + reclaimableBytes >= requiredBytes) {
                return DiskSpaceCheckResult.RequiresDeleteOldConfirmation(
                    oldModelId = oldPrimary.modelId,
                    oldModelSizeBytes = reclaimableBytes,
                    availableBytes = availableBytes,
                    requiredBytes = requiredBytes
                )
            }
        }

        return DiskSpaceCheckResult.Insufficient(
            availableBytes = availableBytes,
            requiredBytes = requiredBytes
        )
    }

    /**
     * Safe model installation / same-slot swap state machine (Design Doc Section 2.3):
     * 1. Disk precheck (>= size + 500MB; or requires explicit confirmation if old A must be deleted first).
     * 2. Download into `.part` file with live progress emitted via [snapshot].
     * 3. SHA-256 verification.
     * 4. Atomic rename `.part` -> `<rootDir>/<slot>/<modelId>/model.bin`.
     * 5. Notify `:local_ai` to unload old model A in that slot (preventing 2x RAM OOM).
     * 6. Probe new model B in `:local_ai`.
     * 7. Success: two-step commit (mark B installed + generation++ -> delete old A if single-model-per-slot).
     *    Failure: delete B, mark B `UNSUPPORTED_ON_DEVICE`, keep old A intact (unless upfront-deleted).
     */
    suspend fun installModel(
        modelId: String,
        confirmDeleteOldBeforeDownload: Boolean = false,
        expectedSha256Override: String? = null
    ): ModelInstallOutcome = opMutex.withLock {
        val descriptor = OfflineModelCatalog.findById(modelId)
            ?: return@withLock ModelInstallOutcome.DownloadFailed(modelId, "未知模型 ID: $modelId")

        if (descriptor.slot == LocalModelSlot.SLM && !isSlmEngineAvailable) {
            return@withLock ModelInstallOutcome.ProbeFailed(
                modelId = modelId,
                reason = "当前开源版未内置离线文本大脑（SLM）引擎",
                keptOldModelId = findInstalledModelsInSlot(LocalModelSlot.SLM).firstOrNull()?.modelId
            )
        }

        val existingOldInSlot = findInstalledModelsInSlot(descriptor.slot)
            .filter { it.modelId != modelId }
        val primaryOldModelId = existingOldInSlot.firstOrNull()?.modelId
        var deletedOldUpfront = false

        when (val spaceCheck = checkDiskSpace(modelId)) {
            is DiskSpaceCheckResult.Insufficient -> {
                return@withLock ModelInstallOutcome.InsufficientDiskSpace(
                    modelId = modelId,
                    availableBytes = spaceCheck.availableBytes,
                    requiredBytes = spaceCheck.requiredBytes
                )
            }

            is DiskSpaceCheckResult.RequiresDeleteOldConfirmation -> {
                if (!confirmDeleteOldBeforeDownload) {
                    return@withLock ModelInstallOutcome.NeedsDeleteOldConfirmation(
                        modelId = modelId,
                        oldModelId = spaceCheck.oldModelId,
                        oldModelSizeBytes = spaceCheck.oldModelSizeBytes,
                        availableBytes = spaceCheck.availableBytes,
                        requiredBytes = spaceCheck.requiredBytes
                    )
                }
                // User explicitly confirmed deleting old A before downloading B (non-rollbackable)
                runtimeBridge.unloadSlot(descriptor.slot)
                for (oldRec in existingOldInSlot) {
                    deleteModelDirQuietly(oldRec.localDirPath)
                }
                updateSnapshotAndPersist { snap ->
                    val nextRecords = snap.records.toMutableMap()
                    existingOldInSlot.forEach { nextRecords.remove(it.modelId) }
                    snap.copy(
                        records = nextRecords,
                        modelGeneration = snap.modelGeneration + 1L
                    )
                }
                deletedOldUpfront = true
            }

            is DiskSpaceCheckResult.Sufficient -> Unit
        }

        val slotDir = slotDirectory(descriptor.slot).apply { mkdirs() }
        val partFile = File(slotDir, "$modelId$PART_SUFFIX")
        val existingPartBytes = if (partFile.isFile) partFile.length().coerceAtLeast(0L) else 0L

        updateRecord(
            LocalModelRecord(
                modelId = modelId,
                status = LocalModelInstallStatus.DOWNLOADING,
                downloadedBytes = existingPartBytes,
                totalBytes = maxOf(descriptor.sizeBytes, existingPartBytes),
                installedVersion = descriptor.minEngineVersion
            ),
            persist = false
        )

        val dlResult = try {
            downloader.downloadToPartFile(
                descriptor = descriptor,
                partFile = partFile,
                onProgress = { downloaded, total ->
                    updateRecord(
                        LocalModelRecord(
                            modelId = modelId,
                            status = LocalModelInstallStatus.DOWNLOADING,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            installedVersion = descriptor.minEngineVersion
                        ),
                        persist = false
                    )
                }
            )
        } catch (ce: CancellationException) {
            val savedBytes = if (partFile.isFile) partFile.length().coerceAtLeast(0L) else 0L
            if (savedBytes > 0L && downloader is HttpResumableModelDownloader) {
                updateRecord(
                    LocalModelRecord(
                        modelId = modelId,
                        status = LocalModelInstallStatus.PAUSED,
                        downloadedBytes = savedBytes,
                        totalBytes = maxOf(descriptor.sizeBytes, savedBytes),
                        installedVersion = descriptor.minEngineVersion,
                        errorReason = "已暂停，可随时断点续传"
                    ),
                    persist = true
                )
            } else {
                runCatching { partFile.delete() }
                updateRecord(
                    LocalModelRecord(
                        modelId = modelId,
                        status = LocalModelInstallStatus.NOT_INSTALLED,
                        errorReason = "已取消下载"
                    ),
                    persist = true
                )
            }
            throw ce
        }

        if (dlResult.isFailure) {
            val err = dlResult.exceptionOrNull()
            val reason = err?.message ?: "模型下载失败"
            if (err is ModelIntegrityException) {
                runCatching { partFile.delete() }
                updateRecord(
                    LocalModelRecord(
                        modelId = modelId,
                        status = LocalModelInstallStatus.CORRUPTED,
                        errorReason = reason
                    ),
                    persist = true
                )
                return@withLock ModelInstallOutcome.VerificationFailed(modelId = modelId, reason = reason)
            }

            val savedBytes = if (partFile.isFile) partFile.length().coerceAtLeast(0L) else 0L
            if (savedBytes > 0L && downloader is HttpResumableModelDownloader) {
                updateRecord(
                    LocalModelRecord(
                        modelId = modelId,
                        status = LocalModelInstallStatus.PAUSED,
                        downloadedBytes = savedBytes,
                        totalBytes = maxOf(descriptor.sizeBytes, savedBytes),
                        installedVersion = descriptor.minEngineVersion,
                        errorReason = reason
                    ),
                    persist = true
                )
            } else {
                runCatching { partFile.delete() }
                updateRecord(
                    LocalModelRecord(
                        modelId = modelId,
                        status = LocalModelInstallStatus.NOT_INSTALLED,
                        errorReason = reason
                    ),
                    persist = true
                )
            }
            return@withLock ModelInstallOutcome.DownloadFailed(modelId = modelId, reason = reason)
        }

        val artifact = dlResult.getOrThrow()
        updateRecord(
            LocalModelRecord(
                modelId = modelId,
                status = LocalModelInstallStatus.VERIFYING,
                downloadedBytes = artifact.reportedTotalBytes,
                totalBytes = artifact.reportedTotalBytes,
                installedVersion = descriptor.minEngineVersion
            ),
            persist = false
        )

        val actualSha256 = if (artifact.computedSha256.isNotBlank()) {
            artifact.computedSha256
        } else {
            withContext(Dispatchers.IO) {
                runCatching { computeFileSha256(partFile) }
            }.getOrElse { err ->
                runCatching { partFile.delete() }
                val reason = "SHA-256 计算失败: ${err.message}"
                updateRecord(
                    LocalModelRecord(
                        modelId = modelId,
                        status = LocalModelInstallStatus.CORRUPTED,
                        errorReason = reason
                    ),
                    persist = true
                )
                return@withLock ModelInstallOutcome.VerificationFailed(modelId = modelId, reason = reason)
            }
        }

        val expectedSha = (expectedSha256Override ?: descriptor.expectedSha256).trim()
        if (expectedSha.isNotEmpty() && !actualSha256.equals(expectedSha, ignoreCase = true)) {
            runCatching { partFile.delete() }
            val reason = "SHA-256 校验不匹配（期望 ${expectedSha.take(12)}…，实际 ${actualSha256.take(12)}…）"
            updateRecord(
                LocalModelRecord(
                    modelId = modelId,
                    status = LocalModelInstallStatus.CORRUPTED,
                    errorReason = reason
                ),
                persist = true
            )
            return@withLock ModelInstallOutcome.VerificationFailed(modelId = modelId, reason = reason)
        }

        // Stage into `.staging` (extract `.tar.bz2` + flatten + Silero VAD if applicable),
        // then atomically rename `stagingDir` -> `targetDir`.
        val targetDir = File(slotDir, modelId)
        val stagingDir = File(slotDir, "$modelId.staging")
        val sharedCacheDir = File(rootDir, SHARED_DIR_NAME)

        val stageResult = try {
            downloader.stageVerifiedPartFile(
                descriptor = descriptor,
                partFile = partFile,
                stagingDir = stagingDir,
                sharedCacheDir = sharedCacheDir
            )
        } catch (ce: CancellationException) {
            runCatching { stagingDir.deleteRecursively() }
            updateRecord(
                LocalModelRecord(
                    modelId = modelId,
                    status = LocalModelInstallStatus.NOT_INSTALLED,
                    errorReason = "已取消安装"
                ),
                persist = true
            )
            throw ce
        }

        if (stageResult.isFailure) {
            val err = stageResult.exceptionOrNull()
            runCatching { partFile.delete() }
            runCatching { stagingDir.deleteRecursively() }
            val reason = err?.message ?: "模型解压落盘失败"
            updateRecord(
                LocalModelRecord(
                    modelId = modelId,
                    status = LocalModelInstallStatus.CORRUPTED,
                    errorReason = reason
                ),
                persist = true
            )
            return@withLock if (err is SecurityException || err is ModelIntegrityException) {
                ModelInstallOutcome.VerificationFailed(modelId = modelId, reason = reason)
            } else {
                ModelInstallOutcome.DownloadFailed(modelId = modelId, reason = reason)
            }
        }

        val stagedByDownloader = stageResult.getOrDefault(false)
        val committedStageOk = withContext(Dispatchers.IO) {
            runCatching {
                if (!stagedByDownloader) {
                    if (stagingDir.exists()) stagingDir.deleteRecursively()
                    stagingDir.mkdirs()
                    val targetBin = File(stagingDir, MODEL_BIN_NAME)
                    if (!partFile.renameTo(targetBin)) {
                        partFile.copyTo(targetBin, overwrite = true)
                        partFile.delete()
                    }
                }
                if (targetDir.exists()) targetDir.deleteRecursively()
                if (!stagingDir.renameTo(targetDir)) {
                    throw IOException("无法原子提交模型目录到 ${targetDir.absolutePath}")
                }
            }
        }
        if (committedStageOk.isFailure) {
            runCatching { partFile.delete() }
            runCatching { stagingDir.deleteRecursively() }
            val reason = committedStageOk.exceptionOrNull()?.message ?: "模型落盘失败"
            updateRecord(
                LocalModelRecord(
                    modelId = modelId,
                    status = LocalModelInstallStatus.CORRUPTED,
                    errorReason = reason
                ),
                persist = true
            )
            return@withLock ModelInstallOutcome.DownloadFailed(modelId = modelId, reason = reason)
        }

        // Step 4: Unload old model A from `:local_ai` RAM BEFORE probing new model B
        runtimeBridge.unloadSlot(descriptor.slot)

        // Step 5: Probe new model B in `:local_ai`
        val probeRes = runtimeBridge.probeModel(descriptor.slot, modelId, targetDir.absolutePath)
        if (probeRes.isFailure) {
            // Rollback: remove failed model B, mark UNSUPPORTED_ON_DEVICE, preserve old model A
            withContext(Dispatchers.IO) {
                runCatching { targetDir.deleteRecursively() }
            }
            val reason = probeRes.exceptionOrNull()?.message ?: "模型在本机探活失败"
            updateRecord(
                LocalModelRecord(
                    modelId = modelId,
                    status = LocalModelInstallStatus.UNSUPPORTED_ON_DEVICE,
                    errorReason = reason
                ),
                persist = true
            )
            val keptOld = if (!deletedOldUpfront) primaryOldModelId else null
            return@withLock ModelInstallOutcome.ProbeFailed(
                modelId = modelId,
                reason = reason,
                keptOldModelId = keptOld
            )
        }

        // Step 6: Two-step commit
        // 6a: Mark B installed & increment modelGeneration, persist
        var committedGeneration = 0L
        val installedRecord = LocalModelRecord(
            modelId = modelId,
            status = LocalModelInstallStatus.INSTALLED,
            downloadedBytes = artifact.reportedTotalBytes,
            totalBytes = artifact.reportedTotalBytes,
            installedVersion = descriptor.minEngineVersion,
            sha256 = actualSha256,
            localDirPath = targetDir.absolutePath,
            errorReason = ""
        )
        updateSnapshotAndPersist { snap ->
            val nextGen = snap.modelGeneration + 1L
            committedGeneration = nextGen
            val nextRecords = snap.records.toMutableMap()
            nextRecords[modelId] = installedRecord
            snap.copy(
                records = nextRecords,
                modelGeneration = nextGen
            )
        }

        // 6b: Delete old model A on disk & remove from registry if single-model-per-slot policy active
        if (keepSingleModelPerSlot && !deletedOldUpfront && existingOldInSlot.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                for (oldRec in existingOldInSlot) {
                    deleteModelDirQuietly(oldRec.localDirPath)
                }
            }
            updateSnapshotAndPersist { snap ->
                val nextRecords = snap.records.toMutableMap()
                existingOldInSlot.forEach { nextRecords.remove(it.modelId) }
                snap.copy(records = nextRecords)
            }
        }

        ModelInstallOutcome.Success(
            modelId = modelId,
            modelDirPath = targetDir.absolutePath,
            sha256 = actualSha256,
            generation = committedGeneration,
            replacedModelId = if (keepSingleModelPerSlot || deletedOldUpfront) primaryOldModelId else null
        )
    }

    /**
     * Imports user-selected local `.gguf` model weights (and optional LoRA `.gguf`) via Android
     * SAF into `noBackupFilesDir/local_models/slm/<modelId>/model.gguf` (+ optional `lora.gguf`),
     * unloads any active SLM first, runs real `:local_ai` probe verification, and commits or
     * rolls back safely.
     */
    suspend fun importLocalSlmFiles(
        modelId: String,
        ggufStreamProvider: () -> InputStream,
        ggufFileName: String,
        loraStreamProvider: (() -> InputStream)? = null,
        loraFileName: String? = null
    ): ModelInstallOutcome = opMutex.withLock {
        val descriptor = OfflineModelCatalog.findById(modelId)
        if (descriptor == null || descriptor.slot != LocalModelSlot.SLM) {
            return@withLock ModelInstallOutcome.DownloadFailed(
                modelId = modelId,
                reason = "无效的离线文本大脑模型槽位: $modelId"
            )
        }

        if (!isSlmEngineAvailable) {
            return@withLock ModelInstallOutcome.ProbeFailed(
                modelId = modelId,
                reason = "当前开源版未内置离线文本大脑（SLM）引擎",
                keptOldModelId = findInstalledModelsInSlot(LocalModelSlot.SLM).firstOrNull()?.modelId
            )
        }

        val cleanGgufName = ggufFileName.trim()
        if (cleanGgufName.isNotEmpty() && !cleanGgufName.lowercase().endsWith(".gguf")) {
            return@withLock ModelInstallOutcome.VerificationFailed(
                modelId = modelId,
                reason = "仅支持导入 .gguf 格式的主模型文件（当前选择：$cleanGgufName）"
            )
        }
        val cleanLoraName = loraFileName?.trim().orEmpty()
        if (loraStreamProvider != null && cleanLoraName.isNotEmpty() && !cleanLoraName.lowercase().endsWith(".gguf")) {
            return@withLock ModelInstallOutcome.VerificationFailed(
                modelId = modelId,
                reason = "仅支持导入 .gguf 格式的 LoRA 文件（当前选择：$cleanLoraName）"
            )
        }

        val existingOldInSlot = findInstalledModelsInSlot(LocalModelSlot.SLM)
            .filter { it.modelId != modelId }
        val primaryOldModelId = existingOldInSlot.firstOrNull()?.modelId
        val previousSameRecord = _snapshot.value.records[modelId]

        val slotDir = slotDirectory(LocalModelSlot.SLM).apply { mkdirs() }
        val targetDir = File(slotDir, modelId)
        val stagingDir = File(slotDir, "$modelId.staging")
        val backupDir = File(slotDir, "$modelId.bak")

        updateRecord(
            LocalModelRecord(
                modelId = modelId,
                status = LocalModelInstallStatus.VERIFYING,
                downloadedBytes = 0L,
                totalBytes = descriptor.sizeBytes,
                installedVersion = descriptor.minEngineVersion
            ),
            persist = false
        )

        var ggufSha256 = ""
        var totalCopiedBytes = 0L
        val copyResult = withContext(Dispatchers.IO) {
            runCatching {
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                if (!stagingDir.mkdirs() && !stagingDir.isDirectory) {
                    throw IOException("无法创建模型导入暂存目录: ${stagingDir.absolutePath}")
                }

                val modelOut = File(stagingDir, SLM_MODEL_GGUF_NAME)
                val (modelBytes, modelSha) = copyStreamAndSha256(ggufStreamProvider(), modelOut)
                if (modelBytes <= 0L) {
                    throw ModelIntegrityException("导入的主模型 .gguf 文件为空")
                }
                ggufSha256 = modelSha
                totalCopiedBytes = modelBytes

                if (loraStreamProvider != null) {
                    val loraOut = File(stagingDir, SLM_LORA_GGUF_NAME)
                    val (loraBytes, _) = copyStreamAndSha256(loraStreamProvider(), loraOut)
                    if (loraBytes <= 0L) {
                        throw ModelIntegrityException("导入的 LoRA .gguf 文件为空")
                    }
                    totalCopiedBytes += loraBytes
                }

                if (backupDir.exists()) backupDir.deleteRecursively()
                if (targetDir.exists()) {
                    if (!targetDir.renameTo(backupDir)) {
                        throw IOException("无法备份原模型目录: ${targetDir.absolutePath}")
                    }
                }
                if (!stagingDir.renameTo(targetDir)) {
                    if (backupDir.exists()) backupDir.renameTo(targetDir)
                    throw IOException("无法原子提交导入的模型目录到 ${targetDir.absolutePath}")
                }
            }
        }

        if (copyResult.isFailure) {
            val err = copyResult.exceptionOrNull()
            runCatching { stagingDir.deleteRecursively() }
            val reason = err?.message ?: "本地 GGUF 导入失败"
            if (previousSameRecord != null && previousSameRecord.status == LocalModelInstallStatus.INSTALLED && targetDir.isDirectory) {
                updateRecord(previousSameRecord, persist = true)
            } else {
                updateRecord(
                    LocalModelRecord(
                        modelId = modelId,
                        status = LocalModelInstallStatus.CORRUPTED,
                        errorReason = reason
                    ),
                    persist = true
                )
            }
            return@withLock if (err is ModelIntegrityException || err is SecurityException) {
                ModelInstallOutcome.VerificationFailed(modelId = modelId, reason = reason)
            } else {
                ModelInstallOutcome.DownloadFailed(modelId = modelId, reason = reason)
            }
        }

        // Unload active SLM before probing imported model to avoid 2x RAM spike
        runtimeBridge.unloadSlot(LocalModelSlot.SLM)

        val probeRes = runtimeBridge.probeModel(LocalModelSlot.SLM, modelId, targetDir.absolutePath)
        if (probeRes.isFailure) {
            val restoredBackup = withContext(Dispatchers.IO) {
                runCatching { targetDir.deleteRecursively() }
                if (backupDir.exists()) {
                    backupDir.renameTo(targetDir)
                } else {
                    false
                }
            }
            val reason = probeRes.exceptionOrNull()?.message ?: "导入的 GGUF 在本机探活失败"
            if (restoredBackup && previousSameRecord != null && previousSameRecord.status == LocalModelInstallStatus.INSTALLED) {
                updateRecord(previousSameRecord, persist = true)
            } else {
                updateRecord(
                    LocalModelRecord(
                        modelId = modelId,
                        status = LocalModelInstallStatus.UNSUPPORTED_ON_DEVICE,
                        errorReason = reason
                    ),
                    persist = true
                )
            }
            return@withLock ModelInstallOutcome.ProbeFailed(
                modelId = modelId,
                reason = reason,
                keptOldModelId = if (restoredBackup) modelId else primaryOldModelId
            )
        }

        // Probe succeeded: clean backup and perform two-step commit
        withContext(Dispatchers.IO) {
            runCatching { if (backupDir.exists()) backupDir.deleteRecursively() }
        }

        var committedGeneration = 0L
        val installedRecord = LocalModelRecord(
            modelId = modelId,
            status = LocalModelInstallStatus.INSTALLED,
            downloadedBytes = totalCopiedBytes,
            totalBytes = totalCopiedBytes,
            installedVersion = descriptor.minEngineVersion,
            sha256 = ggufSha256,
            localDirPath = targetDir.absolutePath,
            errorReason = ""
        )
        updateSnapshotAndPersist { snap ->
            val nextGen = snap.modelGeneration + 1L
            committedGeneration = nextGen
            val nextRecords = snap.records.toMutableMap()
            nextRecords[modelId] = installedRecord
            snap.copy(
                records = nextRecords,
                modelGeneration = nextGen
            )
        }

        if (keepSingleModelPerSlot && existingOldInSlot.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                for (oldRec in existingOldInSlot) {
                    deleteModelDirQuietly(oldRec.localDirPath)
                }
            }
            updateSnapshotAndPersist { snap ->
                val nextRecords = snap.records.toMutableMap()
                existingOldInSlot.forEach { nextRecords.remove(it.modelId) }
                snap.copy(records = nextRecords)
            }
        }

        ModelInstallOutcome.Success(
            modelId = modelId,
            modelDirPath = targetDir.absolutePath,
            sha256 = ggufSha256,
            generation = committedGeneration,
            replacedModelId = if (keepSingleModelPerSlot) primaryOldModelId else null
        )
    }

    private fun copyStreamAndSha256(input: InputStream, destFile: File): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(32 * 1024)
        var totalBytes = 0L
        input.use { src ->
            FileOutputStream(destFile).use { fos ->
                while (true) {
                    val read = src.read(buf)
                    if (read <= 0) break
                    fos.write(buf, 0, read)
                    digest.update(buf, 0, read)
                    totalBytes += read
                }
                fos.flush()
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        return totalBytes to sha256
    }

    suspend fun deleteModel(modelId: String): Boolean = opMutex.withLock {
        val current = _snapshot.value.records[modelId]
        val descriptor = OfflineModelCatalog.findById(modelId)
        if (descriptor != null) {
            runtimeBridge.unloadSlot(descriptor.slot)
        }
        val deletedDisk = withContext(Dispatchers.IO) {
            var removed = false
            if (current != null && current.localDirPath.isNotBlank()) {
                removed = deleteModelDirQuietly(current.localDirPath) || removed
            }
            if (descriptor != null) {
                val slotDir = slotDirectory(descriptor.slot)
                val expectedDir = File(slotDir, modelId)
                if (expectedDir.exists()) {
                    removed = expectedDir.deleteRecursively() || removed
                }
                val partFile = File(slotDir, "$modelId$PART_SUFFIX")
                if (partFile.exists()) {
                    removed = partFile.delete() || removed
                }
                val loraPart = HttpResumableModelDownloader.siblingLoraPartFile(partFile)
                if (loraPart.exists()) {
                    removed = loraPart.delete() || removed
                }
            }
            removed
        }

        if (current != null) {
            updateSnapshotAndPersist { snap ->
                val nextRecords = snap.records.toMutableMap()
                nextRecords.remove(modelId)
                snap.copy(
                    records = nextRecords,
                    modelGeneration = snap.modelGeneration + 1L
                )
            }
            return@withLock true
        }
        deletedDisk
    }

    suspend fun clearAllModels() = opMutex.withLock {
        runtimeBridge.unloadAll()
        withContext(Dispatchers.IO) {
            for (slot in LocalModelSlot.entries) {
                val dir = slotDirectory(slot)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
            rootDir.listFiles().orEmpty().forEach { child ->
                if (child.name != REGISTRY_FILE_NAME) {
                    runCatching { child.deleteRecursively() }
                }
            }
        }
        updateSnapshotAndPersist { snap ->
            snap.copy(
                records = emptyMap(),
                modelGeneration = snap.modelGeneration + 1L
            )
        }
    }

    fun markModelCorrupted(modelId: String, reason: String) {
        updateSnapshotAndPersist { snap ->
            val existing = snap.records[modelId] ?: LocalModelRecord(modelId = modelId)
            val nextRecords = snap.records.toMutableMap()
            nextRecords[modelId] = existing.copy(
                status = LocalModelInstallStatus.CORRUPTED,
                errorReason = reason
            )
            snap.copy(
                records = nextRecords,
                modelGeneration = snap.modelGeneration + 1L
            )
        }
    }

    /**
     * Total disk bytes currently occupied by downloaded offline model files under `local_models/`.
     */
    fun totalStorageBytes(): Long {
        if (!rootDir.exists()) return 0L
        var total = 0L
        for (slot in LocalModelSlot.entries) {
            total += dirSizeBytes(slotDirectory(slot))
        }
        return total
    }

    /**
     * Removes any leftover `.part` / `.staging` / `.tmp` files and orphan directories not referenced
     * by an [LocalModelInstallStatus.INSTALLED] record in `registry.json`.
     */
    fun cleanupOrphansAndPartFiles(): Int {
        if (!rootDir.exists()) return 0
        val validInstalledDirs = _snapshot.value.records.values
            .filter { it.status == LocalModelInstallStatus.INSTALLED && it.localDirPath.isNotBlank() }
            .mapNotNull { runCatching { File(it.localDirPath).canonicalPath }.getOrNull() }
            .toSet()

        var cleanedCount = 0
        val allowedSlotNames = LocalModelSlot.entries.map { slotFolderName(it) }.toSet()

        rootDir.listFiles().orEmpty().forEach { entry ->
            when {
                entry.name == REGISTRY_FILE_NAME -> Unit
                entry.name == SHARED_DIR_NAME && entry.isDirectory -> {
                    entry.listFiles().orEmpty().forEach { child ->
                        if (child.name.endsWith(PART_SUFFIX) ||
                            child.name.endsWith(".tmp") ||
                            child.name.endsWith(".staging") ||
                            child.name.endsWith(".bak")
                        ) {
                            if (child.deleteRecursively()) cleanedCount++
                        }
                    }
                }
                entry.name.endsWith(PART_SUFFIX) ||
                    entry.name.endsWith(".tmp") ||
                    entry.name.endsWith(".staging") ||
                    entry.name.endsWith(".bak") -> {
                    if (entry.deleteRecursively()) cleanedCount++
                }

                entry.isDirectory && entry.name in allowedSlotNames -> {
                    entry.listFiles().orEmpty().forEach { child ->
                        val canonical = runCatching { child.canonicalPath }.getOrNull()
                        val isPartOrTemp = child.name.endsWith(PART_SUFFIX) ||
                            child.name.endsWith("$PART_SUFFIX.lora") ||
                            child.name.endsWith(".tmp") ||
                            child.name.endsWith(".staging") ||
                            child.name.endsWith(".bak")
                        if (isPartOrTemp || canonical == null || canonical !in validInstalledDirs) {
                            if (child.deleteRecursively()) cleanedCount++
                        }
                    }
                }

                else -> {
                    if (entry.deleteRecursively()) cleanedCount++
                }
            }
        }
        if (cleanedCount > 0) {
            LocalAiLog.i("LocalModelRegistry cleaned $cleanedCount orphan/part entries")
        }
        return cleanedCount
    }

    private fun loadPersistedStateAndRecover() {
        if (!registryFile.isFile) return
        val parsed = runCatching {
            gson.fromJson(registryFile.readText(Charsets.UTF_8), PersistedRegistryFile::class.java)
        }.getOrNull() ?: return

        var mutated = false
        val recovered = LinkedHashMap<String, LocalModelRecord>()
        for ((id, rec) in parsed.records) {
            when (rec.status) {
                LocalModelInstallStatus.DOWNLOADING,
                LocalModelInstallStatus.VERIFYING -> {
                    // Process was killed mid-download; `.part` will be cleaned up immediately after
                    mutated = true
                }

                LocalModelInstallStatus.INSTALLED -> {
                    val dir = File(rec.localDirPath)
                    val dirValid = rec.localDirPath.isNotBlank() && dir.isDirectory && dir.listFiles().orEmpty().isNotEmpty()
                    val payloadValid = if (!dirValid) {
                        false
                    } else if (downloader is SimulatedLocalModelDownloader) {
                        true
                    } else {
                        validateRealInstalledModelDir(id, dir)
                    }
                    if (payloadValid) {
                        recovered[id] = rec
                    } else {
                        if (dirValid && isLegacyMockPayloadDir(dir)) {
                            // Auto-clean Phase 1 mock placeholder directory so user can directly tap Download
                            deleteModelDirQuietly(rec.localDirPath)
                        } else {
                            recovered[id] = rec.copy(
                                status = LocalModelInstallStatus.CORRUPTED,
                                errorReason = "模型权重文件缺失或与当前版本不兼容，请重新下载"
                            )
                        }
                        mutated = true
                    }
                }

                else -> recovered[id] = rec
            }
        }

        _snapshot.value = LocalModelRegistrySnapshot(
            records = recovered,
            localServiceAlive = runtimeBridge.isServiceAlive.value,
            slmEngineAvailable = slmEngineAvailableProvider(),
            modelGeneration = parsed.modelGeneration.coerceAtLeast(1L)
        )
        if (mutated) {
            persistSnapshot(_snapshot.value)
        }
    }

    private fun validateRealInstalledModelDir(modelId: String, dir: File): Boolean {
        if (isLegacyMockPayloadDir(dir)) return false
        val descriptor = OfflineModelCatalog.findById(modelId) ?: return false
        return when (descriptor.slot) {
            LocalModelSlot.ASR -> SherpaOnnxModelFileResolver.resolveForModelId(modelId, dir.absolutePath).isSuccess
            LocalModelSlot.SLM -> {
                dir.listFiles().orEmpty().any { f ->
                    f.isFile && f.length() > 0L && f.name.endsWith(".gguf", ignoreCase = true)
                }
            }
        }
    }

    private fun isLegacyMockPayloadDir(dir: File): Boolean {
        val mockBin = File(dir, MODEL_BIN_NAME)
        if (!mockBin.isFile || mockBin.length() <= 0L || mockBin.length() > 64L * 1024L) {
            return false
        }
        return runCatching {
            val buf = ByteArray(32)
            val n = FileInputStream(mockBin).use { it.read(buf) }
            n > 0 && String(buf, 0, n, Charsets.UTF_8).startsWith("TYPEFREE_MOCK_MODEL:")
        }.getOrDefault(false)
    }

    private fun findInstalledModelsInSlot(slot: LocalModelSlot): List<LocalModelRecord> {
        return _snapshot.value.records.values.filter { rec ->
            rec.status == LocalModelInstallStatus.INSTALLED &&
                OfflineModelCatalog.findById(rec.modelId)?.slot == slot
        }
    }

    private fun updateRecord(record: LocalModelRecord, persist: Boolean) {
        val next = updateSnapshot { snap ->
            val map = snap.records.toMutableMap()
            map[record.modelId] = record
            snap.copy(records = map)
        }
        if (persist) {
            persistSnapshot(next)
        }
    }

    private fun updateSnapshot(
        transform: (LocalModelRegistrySnapshot) -> LocalModelRegistrySnapshot
    ): LocalModelRegistrySnapshot {
        val next = transform(_snapshot.value).copy(
            slmEngineAvailable = slmEngineAvailableProvider()
        )
        _snapshot.value = next
        return next
    }

    private fun updateSnapshotAndPersist(
        transform: (LocalModelRegistrySnapshot) -> LocalModelRegistrySnapshot
    ): LocalModelRegistrySnapshot {
        val next = updateSnapshot(transform)
        persistSnapshot(next)
        return next
    }

    private fun persistSnapshot(snapshot: LocalModelRegistrySnapshot) {
        runCatching {
            rootDir.mkdirs()
            val payload = PersistedRegistryFile(
                schemaVersion = 1,
                modelGeneration = snapshot.modelGeneration,
                records = snapshot.records
            )
            val tmp = File(rootDir, "$REGISTRY_FILE_NAME.tmp")
            tmp.writeText(gson.toJson(payload), Charsets.UTF_8)
            if (!tmp.renameTo(registryFile)) {
                tmp.copyTo(registryFile, overwrite = true)
                tmp.delete()
            }
        }.onFailure { err ->
            LocalAiLog.e("Failed to persist registry.json", err)
        }
    }

    private fun slotDirectory(slot: LocalModelSlot): File = File(rootDir, slotFolderName(slot))

    private fun slotFolderName(slot: LocalModelSlot): String = when (slot) {
        LocalModelSlot.ASR -> "asr"
        LocalModelSlot.SLM -> "slm"
    }

    private fun deleteModelDirQuietly(path: String): Boolean {
        if (path.isBlank()) return false
        return runCatching {
            val f = File(path)
            if (f.exists()) f.deleteRecursively() else false
        }.getOrDefault(false)
    }

    private fun dirSizeBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles().orEmpty().sumOf { child -> dirSizeBytes(child) }
    }

    companion object {
        const val ROOT_DIR_NAME: String = "local_models"
        const val SHARED_DIR_NAME: String = "shared"
        const val REGISTRY_FILE_NAME: String = "registry.json"
        const val MODEL_BIN_NAME: String = "model.bin"
        const val SLM_MODEL_GGUF_NAME: String = "model.gguf"
        const val SLM_LORA_GGUF_NAME: String = "lora.gguf"
        const val PART_SUFFIX: String = ".part"
        const val IDLE_UNLOAD_TIMEOUT_SEC: Int = 180
        const val KEEP_SINGLE_MODEL_PER_SLOT: Boolean = false
        const val DISK_RESERVE_BYTES: Long = 500L * 1024L * 1024L

        fun computeFileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(16 * 1024)
            FileInputStream(file).use { fis ->
                while (true) {
                    val read = fis.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun queryAvailableDiskBytes(dir: File): Long {
            return runCatching {
                val target = if (dir.exists()) dir else (dir.parentFile ?: dir)
                StatFs(target.absolutePath).availableBytes
            }.getOrElse {
                runCatching { dir.usableSpace }.getOrDefault(0L)
            }
        }
    }
}
