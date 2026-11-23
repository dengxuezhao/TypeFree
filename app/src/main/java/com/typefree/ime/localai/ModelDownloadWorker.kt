package com.typefree.ime.localai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.typefree.core.offline.LocalModelInstallStatus
import com.typefree.core.offline.OfflineModelCatalog
import com.typefree.ime.TypeFreeApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Main-process WorkManager worker executing resumable HTTPS offline model downloads, SHA-256
 * verification, `.tar.bz2` extraction, and `:local_ai` probe checks.
 *
 * - Runs in the main process alongside [LocalModelRegistry] so progress and terminal state flow
 *   directly into `registry.snapshot`.
 * - Promotes to a `dataSync` foreground notification via [setForeground] so large downloads
 *   (50MB–1GB) survive backgrounding.
 * - Uses expedited scheduling + user-initiated transfer metadata for API 34+ compliance.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val modelId = inputData.getString(KEY_MODEL_ID).orEmpty()
        val displayName = OfflineModelCatalog.findById(modelId)?.displayName
            ?: modelId.ifBlank { "离线模型" }
        return createForegroundInfo(
            modelId = modelId,
            displayName = displayName,
            progressPercent = -1,
            statusText = "正在连接安全 HTTPS 下载源…"
        )
    }

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID)?.trim().orEmpty()
        if (modelId.isEmpty()) {
            return Result.failure(
                workDataOf(
                    KEY_OUTCOME_TYPE to OUTCOME_DOWNLOAD_FAILED,
                    KEY_ERROR_REASON to "缺少目标模型 ID"
                )
            )
        }

        val allowDeleteOld = inputData.getBoolean(KEY_ALLOW_DELETE_OLD, false)
        val displayName = OfflineModelCatalog.findById(modelId)?.displayName ?: modelId
        val registry = runCatching { TypeFreeApp.instance.localModelRegistry }.getOrNull()
            ?: return Result.failure(
                workDataOf(
                    KEY_MODEL_ID to modelId,
                    KEY_OUTCOME_TYPE to OUTCOME_DOWNLOAD_FAILED,
                    KEY_ERROR_REASON to "本机模型注册表未初始化"
                )
            )

        runCatching {
            setForeground(
                createForegroundInfo(
                    modelId = modelId,
                    displayName = displayName,
                    progressPercent = 0,
                    statusText = "正在准备下载…"
                )
            )
        }.onFailure { err ->
            LocalAiLog.w("ModelDownloadWorker setForeground initial call skipped: ${err.message}")
        }

        return try {
            val outcome = coroutineScope {
                val progressJob = launch {
                    var lastNotifyTimeMs = 0L
                    var lastPercent = -1
                    var lastStatus: LocalModelInstallStatus? = null
                    registry.snapshot.collect { snap ->
                        val rec = snap.records[modelId] ?: return@collect
                        if (rec.status != LocalModelInstallStatus.DOWNLOADING &&
                            rec.status != LocalModelInstallStatus.VERIFYING
                        ) {
                            return@collect
                        }
                        val total = rec.totalBytes.coerceAtLeast(1L)
                        val percent = if (rec.status == LocalModelInstallStatus.VERIFYING) {
                            100
                        } else {
                            ((rec.downloadedBytes * 100L) / total).toInt().coerceIn(0, 100)
                        }
                        val now = System.currentTimeMillis()
                        val shouldUpdate = rec.status != lastStatus ||
                            percent != lastPercent && (now - lastNotifyTimeMs >= NOTIFY_THROTTLE_MS || percent == 100)
                        if (!shouldUpdate) return@collect

                        lastNotifyTimeMs = now
                        lastPercent = percent
                        lastStatus = rec.status

                        val statusText = if (rec.status == LocalModelInstallStatus.VERIFYING) {
                            "正在 SHA-256 校验、解压与探活…"
                        } else {
                            "已下载 ${formatShortSize(rec.downloadedBytes)} / ${formatShortSize(rec.totalBytes)} ($percent%)"
                        }

                        runCatching {
                            setProgress(
                                workDataOf(
                                    KEY_MODEL_ID to modelId,
                                    KEY_PROGRESS_PERCENT to percent,
                                    KEY_DOWNLOADED_BYTES to rec.downloadedBytes,
                                    KEY_TOTAL_BYTES to rec.totalBytes
                                )
                            )
                        }
                        runCatching {
                            setForeground(
                                createForegroundInfo(
                                    modelId = modelId,
                                    displayName = displayName,
                                    progressPercent = if (rec.status == LocalModelInstallStatus.VERIFYING) -1 else percent,
                                    statusText = statusText
                                )
                            )
                        }
                    }
                }

                try {
                    registry.installModel(
                        modelId = modelId,
                        confirmDeleteOldBeforeDownload = allowDeleteOld
                    )
                } finally {
                    progressJob.cancel()
                }
            }

            when (outcome) {
                is ModelInstallOutcome.Success -> Result.success(
                    workDataOf(
                        KEY_MODEL_ID to modelId,
                        KEY_OUTCOME_TYPE to OUTCOME_SUCCESS
                    )
                )

                is ModelInstallOutcome.NeedsDeleteOldConfirmation -> Result.failure(
                    workDataOf(
                        KEY_MODEL_ID to modelId,
                        KEY_OUTCOME_TYPE to OUTCOME_NEEDS_DELETE_OLD,
                        KEY_ERROR_REASON to "需要先删除同槽位旧模型以腾出存储空间"
                    )
                )

                is ModelInstallOutcome.InsufficientDiskSpace -> Result.failure(
                    workDataOf(
                        KEY_MODEL_ID to modelId,
                        KEY_OUTCOME_TYPE to OUTCOME_INSUFFICIENT_DISK,
                        KEY_ERROR_REASON to "可用存储空间不足（需要 ${formatShortSize(outcome.requiredBytes)}，当前仅剩 ${formatShortSize(outcome.availableBytes)}）"
                    )
                )

                is ModelInstallOutcome.DownloadFailed -> Result.failure(
                    workDataOf(
                        KEY_MODEL_ID to modelId,
                        KEY_OUTCOME_TYPE to OUTCOME_DOWNLOAD_FAILED,
                        KEY_ERROR_REASON to outcome.reason
                    )
                )

                is ModelInstallOutcome.VerificationFailed -> Result.failure(
                    workDataOf(
                        KEY_MODEL_ID to modelId,
                        KEY_OUTCOME_TYPE to OUTCOME_VERIFICATION_FAILED,
                        KEY_ERROR_REASON to outcome.reason
                    )
                )

                is ModelInstallOutcome.ProbeFailed -> Result.failure(
                    workDataOf(
                        KEY_MODEL_ID to modelId,
                        KEY_OUTCOME_TYPE to OUTCOME_PROBE_FAILED,
                        KEY_ERROR_REASON to outcome.reason
                    )
                )
            }
        } catch (ce: CancellationException) {
            LocalAiLog.i("ModelDownloadWorker cancelled for $modelId")
            throw ce
        } catch (t: Throwable) {
            LocalAiLog.e("ModelDownloadWorker unexpected error for $modelId", t)
            Result.failure(
                workDataOf(
                    KEY_MODEL_ID to modelId,
                    KEY_OUTCOME_TYPE to OUTCOME_DOWNLOAD_FAILED,
                    KEY_ERROR_REASON to (t.message ?: "模型下载异常中断")
                )
            )
        }
    }

    private fun createForegroundInfo(
        modelId: String,
        displayName: String,
        progressPercent: Int,
        statusText: String
    ): ForegroundInfo {
        ensureNotificationChannel(applicationContext)
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val indeterminate = progressPercent < 0
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载 $displayName")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, progressPercent.coerceIn(0, 100), indeterminate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消",
                cancelIntent
            )
            .build()

        val notificationId = NOTIFICATION_ID_BASE + (modelId.hashCode() and 0x0FFF)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_MODEL_ID: String = "model_id"
        const val KEY_ALLOW_DELETE_OLD: String = "allow_delete_existing_first"
        const val KEY_USER_INITIATED: String = "user_initiated_transfer"
        const val KEY_OUTCOME_TYPE: String = "outcome_type"
        const val KEY_ERROR_REASON: String = "error_reason"
        const val KEY_PROGRESS_PERCENT: String = "progress_percent"
        const val KEY_DOWNLOADED_BYTES: String = "downloaded_bytes"
        const val KEY_TOTAL_BYTES: String = "total_bytes"

        const val OUTCOME_SUCCESS: String = "SUCCESS"
        const val OUTCOME_DOWNLOAD_FAILED: String = "DOWNLOAD_FAILED"
        const val OUTCOME_VERIFICATION_FAILED: String = "VERIFICATION_FAILED"
        const val OUTCOME_PROBE_FAILED: String = "PROBE_FAILED"
        const val OUTCOME_INSUFFICIENT_DISK: String = "INSUFFICIENT_DISK"
        const val OUTCOME_NEEDS_DELETE_OLD: String = "NEEDS_DELETE_OLD"

        const val TAG_MODEL_DOWNLOAD: String = "typefree_model_download"
        const val TAG_USER_INITIATED_DATA_TRANSFER: String = "typefree_user_initiated_data_transfer"
        const val NOTIFICATION_CHANNEL_ID: String = "typefree_offline_model_downloads"
        private const val NOTIFICATION_CHANNEL_NAME: String = "离线模型下载"
        private const val NOTIFICATION_ID_BASE: Int = 34_100
        private const val NOTIFY_THROTTLE_MS: Long = 450L

        fun uniqueWorkName(modelId: String): String = "typefree_model_dl_$modelId"

        fun enqueueDownload(
            context: Context,
            modelId: String,
            allowDeleteExistingFirst: Boolean = false
        ): UUID {
            val isApi34Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val requestBuilder = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_MODEL_ID to modelId,
                        KEY_ALLOW_DELETE_OLD to allowDeleteExistingFirst,
                        KEY_USER_INITIATED to isApi34Plus
                    )
                )
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG_MODEL_DOWNLOAD)
                .addTag(uniqueWorkName(modelId))

            if (isApi34Plus) {
                requestBuilder.addTag(TAG_USER_INITIATED_DATA_TRANSFER)
            }

            val request = requestBuilder.build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueWorkName(modelId),
                ExistingWorkPolicy.REPLACE,
                request
            )
            return request.id
        }

        fun cancelDownload(context: Context, modelId: String) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(uniqueWorkName(modelId))
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "离线语音与文本模型后台断点下载进度通知"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        private fun formatShortSize(bytes: Long): String {
            val safe = bytes.coerceAtLeast(0L)
            val mb = 1024.0 * 1024.0
            val gb = mb * 1024.0
            return when {
                safe >= gb -> String.format(Locale.US, "%.2f GB", safe / gb)
                safe >= mb -> String.format(Locale.US, "%.0f MB", safe / mb)
                else -> "${safe / 1024L} KB"
            }
        }
    }
}
