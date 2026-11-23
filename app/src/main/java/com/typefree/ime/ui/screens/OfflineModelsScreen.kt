package com.typefree.ime.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.core.offline.AiTask
import com.typefree.core.offline.AsrTierGroup
import com.typefree.core.offline.LocalModelDescriptor
import com.typefree.core.offline.LocalModelInstallStatus
import com.typefree.core.offline.LocalModelRecord
import com.typefree.core.offline.LocalModelRegistrySnapshot
import com.typefree.core.offline.LocalModelSlot
import com.typefree.core.offline.OfflineMode
import com.typefree.core.offline.OfflineModelCatalog
import com.typefree.core.offline.OfflineRoutePlanner
import com.typefree.core.offline.RamCompatibility
import com.typefree.core.offline.RamTier
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.localai.DeviceRamTierResolver
import com.typefree.ime.localai.DiskSpaceCheckResult
import com.typefree.ime.localai.LocalInferenceEngineRegistry
import com.typefree.ime.localai.LocalModelRegistry
import com.typefree.ime.localai.ModelDownloadWorker
import com.typefree.ime.localai.ModelInstallOutcome
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

private data class PendingDeleteOldConfirm(
    val targetModel: LocalModelDescriptor,
    val oldModelId: String,
    val oldModelSizeBytes: Long,
    val availableBytes: Long,
    val requiredBytes: Long
)

private data class FirstModelReadyPrompt(
    val model: LocalModelDescriptor
)

private data class ImportedSlmReadyPrompt(
    val model: LocalModelDescriptor,
    val hadLora: Boolean
)

private data class LocalSlmImportDialogState(
    val targetModel: LocalModelDescriptor,
    val ggufUri: Uri? = null,
    val ggufName: String = "",
    val loraUri: Uri? = null,
    val loraName: String = ""
)

/**
 * Thin Layer 0 entry card mounted at the top of [ApiSettingsScreen] so ApiSettingsScreen stays lean.
 * - `mode == OFF`: collapses into a compact single-row card (keeps Layer 1 BYOK on first screen).
 * - `mode != OFF`: shows 3-segment mode switcher + 2-line ASR/SLM status summary + manage button.
 */
@Composable
fun OfflineLayer0EntryCard(
    settings: AppSettings,
    registrySnapshot: LocalModelRegistrySnapshot,
    onSelectMode: (OfflineMode) -> Unit,
    onEnableLlmPolish: () -> Unit,
    onOpenOfflineModels: () -> Unit
) {
    val offline = settings.offlineConfig
    val pendingRestore = settings.pendingOfflineRestore

    if (offline.mode == OfflineMode.OFF) {
        Surface(
            onClick = onOpenOfflineModels,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = if (pendingRestore != null) {
                    TypeFreeAmber.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.50f)
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "📱 本机离线模型",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (pendingRestore != null) {
                            TypeFreeAmber.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = if (pendingRestore != null) "待恢复" else "未开启 · 免费开源听音",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pendingRestore != null) {
                                TypeFreeAmber
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "配置本机离线",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        return
    }

    val coreData = offline.toCoreData()
    val asrModel = OfflineModelCatalog.findById(offline.selectedAsrModelId)
    val asrEffective = OfflineRoutePlanner.isModelEffective(
        config = coreData,
        registry = registrySnapshot,
        slot = LocalModelSlot.ASR,
        modelId = offline.selectedAsrModelId
    )
    val slmModel = OfflineModelCatalog.findById(offline.selectedSlmModelId)
    val slmEffective = OfflineRoutePlanner.isModelEffective(
        config = coreData,
        registry = registrySnapshot,
        slot = LocalModelSlot.SLM,
        modelId = offline.selectedSlmModelId
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.2.dp,
            color = if (offline.mode == OfflineMode.OFFLINE_ONLY) {
                TypeFreeEmerald.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "📱 本机离线模型",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onOpenOfflineModels,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        text = "管理模型与下载",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            OfflineModeSegmentedRow(
                selectedMode = offline.mode,
                onSelectMode = onSelectMode,
                compact = true
            )

            // 2-line ASR / SLM concise status summary
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val asrSummary = when {
                    !offline.asrSlotOn -> "👂 离线听音：槽位开关已关闭（保留已选模型）"
                    asrEffective && asrModel != null -> "👂 离线听音：已生效 · ${asrModel.displayName}"
                    asrModel != null -> "👂 离线听音：${asrModel.displayName}（未安装或暂不可用）"
                    else -> "👂 离线听音：尚未下载模型（点击右上角下载）"
                }
                val slmSummary = when {
                    !registrySnapshot.slmEngineAvailable -> "🧠 离线大脑：公开开源版占位（可搭配云端 BYOK 或免费直出）"
                    !offline.slmSlotOn -> "🧠 离线大脑：槽位开关已关闭"
                    slmEffective && slmModel != null -> "🧠 离线大脑：已生效 · ${slmModel.displayName}"
                    slmModel != null -> "🧠 离线大脑：${slmModel.displayName}（未安装或暂不可用）"
                    else -> "🧠 离线大脑：未下载（点击右上角按需下载）"
                }
                Text(
                    text = asrSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (asrEffective) TypeFreeEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (asrEffective) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = slmSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (slmEffective) TypeFreeEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (slmEffective) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!settings.llmConfig.enabled) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = TypeFreeAmber.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, TypeFreeAmber.copy(alpha = 0.38f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "听写润色已关闭，语音听写将仅输出转写原稿",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )
                        TextButton(
                            onClick = onEnableLlmPolish,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text(
                                text = "一键开启",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Independent full-screen subpage for managing on-device offline ASR & SLM models.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfflineModelsScreen(
    repository: AppRepository,
    registry: LocalModelRegistry? = remember {
        runCatching { TypeFreeApp.instance.localModelRegistry }.getOrNull()
    },
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState()
    val registrySnapshot = if (registry != null) {
        val snap by registry.snapshot.collectAsState()
        snap
    } else {
        remember { LocalModelRegistrySnapshot() }
    }

    val memorySnapshot = remember(context) {
        DeviceRamTierResolver.snapshotMemory(context)
    }
    val ramTier = remember(memorySnapshot.totalRamBytes) {
        DeviceRamTierResolver.resolveRamTierFromBytes(memorySnapshot.totalRamBytes)
    }

    var selectedAsrTierGroup by rememberSaveable { mutableStateOf(AsrTierGroup.COMPACT_FAST) }
    var activeOperatingModelId by remember { mutableStateOf<String?>(null) }
    var deleteOldConfirm by remember { mutableStateOf<PendingDeleteOldConfirm?>(null) }
    var firstReadyPrompt by remember { mutableStateOf<FirstModelReadyPrompt?>(null) }
    var importedSlmReadyPrompt by remember { mutableStateOf<ImportedSlmReadyPrompt?>(null) }
    var slmImportDialog by remember { mutableStateOf<LocalSlmImportDialogState?>(null) }
    var uninstallConfirmModel by remember { mutableStateOf<LocalModelDescriptor?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var diskInsufficientMsg by remember { mutableStateOf<String?>(null) }

    val ggufPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = resolveDocumentDisplayName(context, uri, fallback = "model.gguf")
            slmImportDialog = slmImportDialog?.copy(ggufUri = uri, ggufName = fileName)
        }
    }

    val loraPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = resolveDocumentDisplayName(context, uri, fallback = "lora.gguf")
            slmImportDialog = slmImportDialog?.copy(loraUri = uri, loraName = fileName)
        }
    }

    val offlineConfig = settings.offlineConfig
    val pendingRestore = settings.pendingOfflineRestore
    val storageBytes = remember(registry, registrySnapshot) {
        registry?.totalStorageBytes() ?: 0L
    }

    fun unloadLocalSlotAsync(slot: LocalModelSlot) {
        coroutineScope.launch {
            runCatching { TypeFreeApp.instance.localAiClient.unloadSlot(slot) }
        }
    }

    fun unloadAllLocalAsync() {
        coroutineScope.launch {
            runCatching { TypeFreeApp.instance.localAiClient.unloadAll() }
        }
    }

    fun handleInstallSuccess(
        model: LocalModelDescriptor,
        targetRegistry: LocalModelRegistry,
        modeBeforeInstall: OfflineMode,
        autoSelectAndToast: Boolean = true,
        importedWithLora: Boolean? = null
    ) {
        if (autoSelectAndToast) {
            repository.updateOfflineConfig { curr ->
                when (model.slot) {
                    LocalModelSlot.ASR -> curr.copy(selectedAsrModelId = model.id)
                    LocalModelSlot.SLM -> curr.copy(selectedSlmModelId = model.id)
                }
            }
        }

        val latestSnap = targetRegistry.snapshot.value
        val restore = repository.settings.value.pendingOfflineRestore
        val restoreAsrReady = restore?.selectedAsrModelId.isNullOrBlank() ||
            latestSnap.isInstalled(restore?.selectedAsrModelId)
        val restoreSlmReady = restore?.selectedSlmModelId.isNullOrBlank() ||
            latestSnap.isInstalled(restore?.selectedSlmModelId)

        if (restore != null && restore.mode != OfflineMode.OFF && restoreAsrReady && restoreSlmReady) {
            repository.updateOfflineConfig { curr ->
                curr.copy(
                    mode = restore.mode,
                    selectedAsrModelId = restore.selectedAsrModelId ?: curr.selectedAsrModelId,
                    selectedSlmModelId = restore.selectedSlmModelId ?: curr.selectedSlmModelId
                )
            }
            repository.clearPendingOfflineRestore()
            Toast.makeText(
                context,
                "✓ 离线模型校验完成，已自动恢复导入的离线模式配置",
                Toast.LENGTH_LONG
            ).show()
        } else if (modeBeforeInstall == OfflineMode.OFF) {
            firstReadyPrompt = FirstModelReadyPrompt(model = model)
        } else if (importedWithLora != null) {
            importedSlmReadyPrompt = ImportedSlmReadyPrompt(
                model = model,
                hadLora = importedWithLora
            )
        } else {
            Toast.makeText(
                context,
                "✓ 「${model.displayName}」已安装并设为启用",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun startImportLocalSlm(dialogState: LocalSlmImportDialogState) {
        val targetRegistry = registry ?: run {
            Toast.makeText(context, "本机模型注册表未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val ggufUri = dialogState.ggufUri ?: run {
            Toast.makeText(context, "请先选择主模型 .gguf 文件", Toast.LENGTH_SHORT).show()
            return
        }
        val model = dialogState.targetModel
        val modeBeforeImport = repository.settings.value.offlineConfig.mode
        val hadLora = dialogState.loraUri != null
        slmImportDialog = null

        coroutineScope.launch {
            activeOperatingModelId = model.id
            val outcome = targetRegistry.importLocalSlmFiles(
                modelId = model.id,
                ggufStreamProvider = {
                    context.contentResolver.openInputStream(ggufUri)
                        ?: throw IOException("无法打开所选主模型文件：${dialogState.ggufName}")
                },
                ggufFileName = dialogState.ggufName,
                loraStreamProvider = dialogState.loraUri?.let { loraUri ->
                    {
                        context.contentResolver.openInputStream(loraUri)
                            ?: throw IOException("无法打开所选 LoRA 文件：${dialogState.loraName}")
                    }
                },
                loraFileName = dialogState.loraName.takeIf { it.isNotBlank() }
            )
            activeOperatingModelId = null

            when (outcome) {
                is ModelInstallOutcome.Success -> {
                    handleInstallSuccess(
                        model = model,
                        targetRegistry = targetRegistry,
                        modeBeforeInstall = modeBeforeImport,
                        autoSelectAndToast = false,
                        importedWithLora = hadLora
                    )
                }

                is ModelInstallOutcome.VerificationFailed -> {
                    Toast.makeText(context, "导入校验失败：${outcome.reason}", Toast.LENGTH_LONG).show()
                }

                is ModelInstallOutcome.DownloadFailed -> {
                    Toast.makeText(context, "导入失败：${outcome.reason}", Toast.LENGTH_LONG).show()
                }

                is ModelInstallOutcome.ProbeFailed -> {
                    val rollbackTip = if (outcome.keptOldModelId != null) {
                        "已保留原模型不受影响。"
                    } else {
                        ""
                    }
                    Toast.makeText(
                        context,
                        "本机探活失败：${outcome.reason}。$rollbackTip",
                        Toast.LENGTH_LONG
                    ).show()
                }

                is ModelInstallOutcome.InsufficientDiskSpace -> {
                    diskInsufficientMsg = "存储空间不足：当前可用 ${formatByteSize(outcome.availableBytes)}。"
                }

                is ModelInstallOutcome.NeedsDeleteOldConfirmation -> Unit
            }
        }
    }

    fun startInstallModel(
        model: LocalModelDescriptor,
        confirmDeleteOldBeforeDownload: Boolean
    ) {
        val targetRegistry = registry ?: run {
            Toast.makeText(context, "本机模型注册表未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val modeBeforeDownload = repository.settings.value.offlineConfig.mode
        coroutineScope.launch {
            activeOperatingModelId = model.id
            val workId = runCatching {
                ModelDownloadWorker.enqueueDownload(
                    context = context,
                    modelId = model.id,
                    allowDeleteExistingFirst = confirmDeleteOldBeforeDownload
                )
            }.getOrNull()

            if (workId != null) {
                val finalInfo = runCatching {
                    WorkManager.getInstance(context.applicationContext)
                        .getWorkInfoByIdFlow(workId)
                        .first { info -> info != null && info.state.isFinished }
                }.getOrNull()
                activeOperatingModelId = null

                when (finalInfo?.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        handleInstallSuccess(
                            model = model,
                            targetRegistry = targetRegistry,
                            modeBeforeInstall = modeBeforeDownload
                        )
                    }

                    WorkInfo.State.CANCELLED -> {
                        Toast.makeText(context, "已取消下载「${model.displayName}」", Toast.LENGTH_SHORT).show()
                    }

                    WorkInfo.State.FAILED -> {
                        val outcomeType = finalInfo.outputData.getString(ModelDownloadWorker.KEY_OUTCOME_TYPE)
                        val reason = finalInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR_REASON)
                            ?: "模型安装失败"
                        when (outcomeType) {
                            ModelDownloadWorker.OUTCOME_INSUFFICIENT_DISK -> {
                                diskInsufficientMsg = reason
                            }
                            ModelDownloadWorker.OUTCOME_VERIFICATION_FAILED -> {
                                Toast.makeText(context, "完整性校验失败：$reason", Toast.LENGTH_LONG).show()
                            }
                            ModelDownloadWorker.OUTCOME_PROBE_FAILED -> {
                                Toast.makeText(context, "本机探活失败：$reason", Toast.LENGTH_LONG).show()
                            }
                            else -> {
                                Toast.makeText(context, "下载失败：$reason", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    else -> Unit
                }
                return@launch
            }

            val outcome = targetRegistry.installModel(
                modelId = model.id,
                confirmDeleteOldBeforeDownload = confirmDeleteOldBeforeDownload
            )
            activeOperatingModelId = null

            when (outcome) {
                is ModelInstallOutcome.Success -> {
                    handleInstallSuccess(
                        model = model,
                        targetRegistry = targetRegistry,
                        modeBeforeInstall = modeBeforeDownload
                    )
                }

                is ModelInstallOutcome.NeedsDeleteOldConfirmation -> {
                    deleteOldConfirm = PendingDeleteOldConfirm(
                        targetModel = model,
                        oldModelId = outcome.oldModelId,
                        oldModelSizeBytes = outcome.oldModelSizeBytes,
                        availableBytes = outcome.availableBytes,
                        requiredBytes = outcome.requiredBytes
                    )
                }

                is ModelInstallOutcome.InsufficientDiskSpace -> {
                    diskInsufficientMsg = "存储空间不足：当前可用 ${formatByteSize(outcome.availableBytes)}，" +
                        "下载「${model.displayName}」并保留 500MB 安全余量需要约 ${formatByteSize(outcome.requiredBytes)}。"
                }

                is ModelInstallOutcome.DownloadFailed -> {
                    Toast.makeText(context, "下载失败：${outcome.reason}", Toast.LENGTH_LONG).show()
                }

                is ModelInstallOutcome.VerificationFailed -> {
                    Toast.makeText(context, "完整性校验失败：${outcome.reason}", Toast.LENGTH_LONG).show()
                }

                is ModelInstallOutcome.ProbeFailed -> {
                    val rollbackTip = if (outcome.keptOldModelId != null) {
                        "已保留原模型不受影响。"
                    } else {
                        ""
                    }
                    Toast.makeText(
                        context,
                        "本机探活失败：${outcome.reason}。$rollbackTip",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun handleRequestDownload(model: LocalModelDescriptor) {
        val targetRegistry = registry ?: run {
            Toast.makeText(context, "本机模型注册表未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        when (val check = targetRegistry.checkDiskSpace(model.id)) {
            is DiskSpaceCheckResult.Sufficient -> {
                startInstallModel(model = model, confirmDeleteOldBeforeDownload = false)
            }

            is DiskSpaceCheckResult.RequiresDeleteOldConfirmation -> {
                deleteOldConfirm = PendingDeleteOldConfirm(
                    targetModel = model,
                    oldModelId = check.oldModelId,
                    oldModelSizeBytes = check.oldModelSizeBytes,
                    availableBytes = check.availableBytes,
                    requiredBytes = check.requiredBytes
                )
            }

            is DiskSpaceCheckResult.Insufficient -> {
                diskInsufficientMsg = "存储空间不足：当前可用 ${formatByteSize(check.availableBytes)}，" +
                    "安装「${model.displayName}」（含 500MB 安全余量）至少需要 ${formatByteSize(check.requiredBytes)}。"
            }
        }
    }

    // Dialog 1: Disk space requires deleting old model first (non-rollbackable warning)
    deleteOldConfirm?.let { pending ->
        val oldName = OfflineModelCatalog.findById(pending.oldModelId)?.displayName ?: pending.oldModelId
        AlertDialog(
            onDismissRequest = { deleteOldConfirm = null },
            title = { Text("存储空间不足 · 需先删除旧模型") },
            text = {
                Text(
                    "当前可用空间 ${formatByteSize(pending.availableBytes)}，直接下载「${pending.targetModel.displayName}」" +
                        "（含 500MB 安全余量需 ${formatByteSize(pending.requiredBytes)}）空间不足。\n\n" +
                        "需要先删除同槽位旧模型「$oldName」（约 ${formatByteSize(pending.oldModelSizeBytes)}）以腾出空间。" +
                        "请注意：若后续下载或探活失败，旧模型将无法自动回滚。是否继续？"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = pending.targetModel
                        deleteOldConfirm = null
                        startInstallModel(model = target, confirmDeleteOldBeforeDownload = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TypeFreeRose)
                ) {
                    Text("删除旧模型并下载")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteOldConfirm = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Dialog 2: First model ready while mode == OFF (never silently overwrite user's cloud BYOK)
    firstReadyPrompt?.let { prompt ->
        val slotDesc = if (prompt.model.slot == LocalModelSlot.ASR) {
            "并用本机听音替代当前云端听音"
        } else {
            "并用本机大脑接管支持的润色与补全任务"
        }
        AlertDialog(
            onDismissRequest = { firstReadyPrompt = null },
            title = { Text("模型已就绪，是否立即启用？") },
            text = {
                Text(
                    "「${prompt.model.displayName}」已下载并通过完整性探活。\n\n" +
                        "是否立即开启「混合模式」$slotDesc？\n" +
                        "（你原有的云端 API 密钥与配置将完整保留，随时可切回「关闭」。）"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val model = prompt.model
                        firstReadyPrompt = null
                        repository.updateOfflineConfig { curr ->
                            when (model.slot) {
                                LocalModelSlot.ASR -> curr.copy(
                                    mode = OfflineMode.HYBRID,
                                    asrSlotOn = true,
                                    selectedAsrModelId = model.id
                                )
                                LocalModelSlot.SLM -> curr.copy(
                                    mode = OfflineMode.HYBRID,
                                    slmSlotOn = true,
                                    selectedSlmModelId = model.id
                                )
                            }
                        }
                        Toast.makeText(context, "✓ 已开启混合模式并启用本机模型", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("立即开启混合模式")
                }
            },
            dismissButton = {
                TextButton(onClick = { firstReadyPrompt = null }) {
                    Text("暂不开启")
                }
            }
        )
    }

    // Dialog 3: Uninstall single model
    uninstallConfirmModel?.let { model ->
        AlertDialog(
            onDismissRequest = { uninstallConfirmModel = null },
            title = { Text("卸载离线模型") },
            text = {
                Text("确定卸载「${model.displayName}」？卸载将释放本机磁盘空间并卸载对应内存权重，不会影响你的云端 API 配置。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = model
                        uninstallConfirmModel = null
                        coroutineScope.launch {
                            registry?.deleteModel(target.id)
                            val snapAfter = registry?.snapshot?.value ?: LocalModelRegistrySnapshot()
                            repository.updateOfflineConfig { curr ->
                                when (target.slot) {
                                    LocalModelSlot.ASR -> if (curr.selectedAsrModelId == target.id) {
                                        val fallback = OfflineModelCatalog.asrModels
                                            .firstOrNull { snapAfter.isInstalled(it.id) }?.id
                                        curr.copy(selectedAsrModelId = fallback)
                                    } else {
                                        curr
                                    }
                                    LocalModelSlot.SLM -> if (curr.selectedSlmModelId == target.id) {
                                        val fallback = OfflineModelCatalog.slmModels
                                            .firstOrNull { snapAfter.isInstalled(it.id) }?.id
                                        curr.copy(selectedSlmModelId = fallback)
                                    } else {
                                        curr
                                    }
                                }
                            }
                            Toast.makeText(context, "已卸载「${target.displayName}」", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TypeFreeRose)
                ) {
                    Text("确认卸载")
                }
            },
            dismissButton = {
                TextButton(onClick = { uninstallConfirmModel = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Dialog 4: Clear all offline weights
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("一键清理全部离线权重") },
            text = {
                Text("将删除本机所有已下载的离线语音与文本模型文件（当前占用 ${formatByteSize(storageBytes)}），并清空当前已选模型记录。云端 API 配置不受影响。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearAllDialog = false
                        coroutineScope.launch {
                            registry?.clearAllModels()
                            repository.updateOfflineConfig { curr ->
                                curr.copy(
                                    selectedAsrModelId = null,
                                    selectedSlmModelId = null
                                )
                            }
                            Toast.makeText(context, "已清理全部离线权重", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TypeFreeRose)
                ) {
                    Text("确认清理")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Dialog 5: Disk insufficient notice
    diskInsufficientMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { diskInsufficientMsg = null },
            title = { Text("可用存储空间不足") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { diskInsufficientMsg = null }) {
                    Text("知道了")
                }
            }
        )
    }

    // Dialog 6: Local SLM `.gguf` (+ optional LoRA `.gguf`) SAF Import Dialog (Pro Engine Only)
    slmImportDialog?.let { state ->
        AlertDialog(
            onDismissRequest = { slmImportDialog = null },
            title = { Text("导入本机 .gguf 模型") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "目标槽位：${state.targetModel.displayName}\n将复制到应用私有不备份目录并执行真实探活。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "主模型权重（必选 .gguf）",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = state.ggufName.ifBlank { "尚未选择文件" },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.ggufUri != null) TypeFreeEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            OutlinedButton(
                                onClick = { ggufPickerLauncher.launch(arrayOf("*/*")) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (state.ggufUri != null) "重新选择主模型 .gguf" else "选择主模型 .gguf",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "LoRA 适配器（可选 .gguf）",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = state.loraName.ifBlank { "不附带 LoRA（合并版模型直接留空）" },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.loraUri != null) TypeFreeEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { loraPickerLauncher.launch(arrayOf("*/*")) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (state.loraUri != null) "更换 LoRA" else "选择可选 LoRA",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                if (state.loraUri != null) {
                                    TextButton(
                                        onClick = {
                                            slmImportDialog = state.copy(loraUri = null, loraName = "")
                                        }
                                    ) {
                                        Text("清除", color = TypeFreeRose)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { startImportLocalSlm(state) },
                    enabled = state.ggufUri != null && activeOperatingModelId == null
                ) {
                    Text("开始导入并探活")
                }
            },
            dismissButton = {
                TextButton(onClick = { slmImportDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Dialog 7: Imported local SLM succeeded — one-tap activate prompt
    importedSlmReadyPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { importedSlmReadyPrompt = null },
            title = { Text("本机 GGUF 导入并探活成功") },
            text = {
                val loraTip = if (prompt.hadLora) "（已附带 LoRA 权重）" else ""
                Text(
                    "「${prompt.model.displayName}」$loraTip 已通过本机真实推理探活。\n\n" +
                        "是否立即将其设为当前启用的离线文本大脑？"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val model = prompt.model
                        importedSlmReadyPrompt = null
                        repository.updateOfflineConfig { curr ->
                            curr.copy(
                                slmSlotOn = true,
                                selectedSlmModelId = model.id
                            )
                        }
                        Toast.makeText(
                            context,
                            "✓ 已将「${model.displayName}」设为启用大脑",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text("设为启用")
                }
            },
            dismissButton = {
                TextButton(onClick = { importedSlmReadyPrompt = null }) {
                    Text("稍后再说")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned Top Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("返回", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = "本机离线模型",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (registrySnapshot.slmEngineAvailable) {
                    Box {
                        TextButton(onClick = { showOverflowMenu = true }) {
                            Text("更多 ⋯", fontWeight = FontWeight.SemiBold)
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            OfflineModelCatalog.slmModels.forEach { slmModel ->
                                DropdownMenuItem(
                                    text = { Text("高级：手动导入 ${slmModel.displayName} (.gguf)") },
                                    onClick = {
                                        showOverflowMenu = false
                                        slmImportDialog = LocalSlmImportDialogState(targetModel = slmModel)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.width(64.dp))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Device Physical RAM Tier Badge
            val ramRecommendation = when (ramTier) {
                RamTier.LOW_6GB -> "推荐 SenseVoice + 0.8B"
                RamTier.MID_8GB -> "推荐 SenseVoice + 0.8B / 2B"
                RamTier.HIGH_12GB_PLUS -> "推荐 SenseVoice + 0.8B / 2B"
            }
            val totalRamGb = memorySnapshot.totalRamMb / 1024.0
            val availRamGb = memorySnapshot.availRamMb / 1024.0
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.40f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "本机 ${ramTier.label} · $ramRecommendation",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "物理内存 %.1f GB · 当前可用 %.1f GB（不含虚拟内存扩展）",
                                totalRamGb,
                                availRamGb
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TypeFreeEmerald.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = ramTier.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TypeFreeEmerald,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // 2. Cross-device Migration Pending Restore Banner
            if (pendingRestore != null) {
                val restoreAsrName = OfflineModelCatalog.findById(pendingRestore.selectedAsrModelId)?.displayName
                val restoreSlmName = OfflineModelCatalog.findById(pendingRestore.selectedSlmModelId)?.displayName
                val modelListText = listOfNotNull(restoreAsrName, restoreSlmName).joinToString(" + ")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = TypeFreeAmber.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, TypeFreeAmber.copy(alpha = 0.50f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "检测到导入配置启用了离线模型，下载完成后自动恢复",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (modelListText.isNotBlank()) {
                                Text(
                                    text = "目标配置（${offlineModeLabel(pendingRestore.mode)}）：$modelListText",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        TextButton(
                            onClick = { repository.clearPendingOfflineRestore() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "忽略",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 3. 3-Segment Mode Switcher Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "离线运行模式",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    OfflineModeSegmentedRow(
                        selectedMode = offlineConfig.mode,
                        onSelectMode = { newMode ->
                            repository.updateOfflineConfig { it.copy(mode = newMode) }
                            if (newMode == OfflineMode.OFF) {
                                unloadAllLocalAsync()
                            }
                        },
                        compact = false
                    )

                    val modeExplanation = when (offlineConfig.mode) {
                        OfflineMode.OFF ->
                            "当前完全沿用云端 BYOK 配置；已下载的离线模型仍保留在磁盘中，随时可一键开启。"
                        OfflineMode.HYBRID ->
                            "混合模式（推荐）：主接管任务优先本机执行，超纲任务自动走云端。注意：「本地听音 + 云端润色」仍会将转写文本发送至你配置的云端服务。"
                        OfflineMode.OFFLINE_ONLY ->
                            "纯离线锁：严格禁止所有语音、文本、补全与端点预热出网（你在本页主动点击下载模型包除外）；当前模型不支持的任务将直接提示并拦截。"
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = when (offlineConfig.mode) {
                            OfflineMode.OFFLINE_ONLY -> TypeFreeEmerald.copy(alpha = 0.10f)
                            OfflineMode.HYBRID -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            OfflineMode.OFF -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        }
                    ) {
                        Text(
                            text = modeExplanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    if (!settings.llmConfig.enabled && offlineConfig.mode != OfflineMode.OFF) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = TypeFreeAmber.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, TypeFreeAmber.copy(alpha = 0.40f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "听写润色开关当前已关闭，语音听写将仅做转写直出（不影响主动改写/补全）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                )
                                TextButton(
                                    onClick = {
                                        repository.updateSettings {
                                            it.copy(llmConfig = it.llmConfig.copy(enabled = true))
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("开启润色", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // 4. Slot A: Offline ASR (All Open-Source Models, Free for All Editions)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "👂 离线听音（ASR）",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = TypeFreeEmerald.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = "开源免费",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TypeFreeEmerald,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (offlineConfig.asrSlotOn) {
                                    "基于 sherpa-onnx 纯 CPU 引擎 · 关闭槽位开关将释放内存并保留模型文件"
                                } else {
                                    "离线听音槽位已关闭 · 当前暂停参与路由，已选模型与磁盘权重已保留"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = offlineConfig.asrSlotOn,
                            onCheckedChange = { enabled ->
                                repository.updateOfflineConfig { it.copy(asrSlotOn = enabled) }
                                if (!enabled) {
                                    unloadLocalSlotAsync(LocalModelSlot.ASR)
                                }
                            }
                        )
                    }

                    // Two Tier Group Filter Chips: Compact Fast (5) vs Large Accurate (3)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val compactCount = remember {
                            OfflineModelCatalog.asrModels.count { it.asrTierGroup == AsrTierGroup.COMPACT_FAST }
                        }
                        val largeCount = remember {
                            OfflineModelCatalog.asrModels.count { it.asrTierGroup == AsrTierGroup.LARGE_ACCURATE }
                        }
                        FilterChip(
                            selected = selectedAsrTierGroup == AsrTierGroup.COMPACT_FAST,
                            onClick = { selectedAsrTierGroup = AsrTierGroup.COMPACT_FAST },
                            label = {
                                Text(
                                    text = "小巧极速 30–230MB ($compactCount)",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                        FilterChip(
                            selected = selectedAsrTierGroup == AsrTierGroup.LARGE_ACCURATE,
                            onClick = { selectedAsrTierGroup = AsrTierGroup.LARGE_ACCURATE },
                            label = {
                                Text(
                                    text = "大体积高精 400MB–1GB ($largeCount)",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }

                    val visibleAsrModels = remember(selectedAsrTierGroup) {
                        OfflineModelCatalog.asrModels.filter { it.asrTierGroup == selectedAsrTierGroup }
                    }

                    visibleAsrModels.forEach { model ->
                        val record = registrySnapshot.records[model.id]
                        val isSelected = offlineConfig.selectedAsrModelId == model.id
                        val isEffective = OfflineRoutePlanner.isModelEffective(
                            config = offlineConfig.toCoreData(),
                            registry = registrySnapshot,
                            slot = LocalModelSlot.ASR,
                            modelId = model.id
                        )
                        val compat = OfflineModelCatalog.compatibilityFor(model, ramTier)

                        OfflineModelCard(
                            model = model,
                            record = record,
                            ramCompatibility = compat,
                            isSelected = isSelected,
                            isEffective = isEffective,
                            isBusy = activeOperatingModelId != null,
                            serialExecutionWarning = null,
                            onDownload = { handleRequestDownload(model) },
                            onCancelDownload = {
                                ModelDownloadWorker.cancelDownload(context, model.id)
                            },
                            onImportLocalGguf = null,
                            onSelectActive = {
                                val prevId = offlineConfig.selectedAsrModelId
                                repository.updateOfflineConfig {
                                    it.copy(
                                        asrSlotOn = true,
                                        selectedAsrModelId = model.id
                                    )
                                }
                                if (prevId != model.id) {
                                    unloadLocalSlotAsync(LocalModelSlot.ASR)
                                }
                                Toast.makeText(
                                    context,
                                    "✓ 已启用「${model.displayName}」（其余已下载模型自动保留待用）",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onUninstall = { uninstallConfirmModel = model }
                        )
                    }
                }
            }

            // 5. Slot B: Offline Text Brain (SLM, Self-Finetuned Pro Exclusive)
            val isSlmEngineAvailable = registry?.isSlmEngineAvailable ?: registrySnapshot.slmEngineAvailable
            if (!isSlmEngineAvailable) {
                // Clean Open-Source Edition Info Card (zero dead switches or dead buttons)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.50f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "🧠 离线文本大脑（SLM）",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "自研微调 · Pro 专属",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "当前为 TypeFree 公开开源构建：你可以在上方自由按需下载全部开源离线语音（ASR）模型，搭配任意云端 BYOK 文本模型混搭润色，或免 Key 直接输出原始转写稿。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                        Text(
                            text = "自研微调端侧润色/补全/改写大脑（Qwen3.5 0.8B / 2B / 4B）归独立商业 Pro 构建提供。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Pro Build: Active SLM Slot with Switch + 0.8B / 2B / 4B Cards
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "🧠 离线文本大脑（SLM）",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = "端侧微调",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = if (offlineConfig.slmSlotOn) {
                                        "按主接管 / 断网兜底能力矩阵路由，不静默降级你已配好的云端专用军师"
                                    } else {
                                        "离线大脑槽位已关闭 · 文本任务将交由云端或直出，权重文件保留"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = offlineConfig.slmSlotOn,
                                onCheckedChange = { enabled ->
                                    repository.updateOfflineConfig { it.copy(slmSlotOn = enabled) }
                                    if (!enabled) {
                                        unloadLocalSlotAsync(LocalModelSlot.SLM)
                                    }
                                }
                            )
                        }

                        OfflineModelCatalog.slmModels.forEach { model ->
                            val record = registrySnapshot.records[model.id]
                            val isSelected = offlineConfig.selectedSlmModelId == model.id
                            val isEffective = OfflineRoutePlanner.isModelEffective(
                                config = offlineConfig.toCoreData(),
                                registry = registrySnapshot,
                                slot = LocalModelSlot.SLM,
                                modelId = model.id
                            )
                            val compat = OfflineModelCatalog.compatibilityFor(model, ramTier)
                            val serialWarn = if (
                                OfflineModelCatalog.requiresSerialAsrSlmExecution(
                                    asrId = offlineConfig.selectedAsrModelId,
                                    slmId = model.id,
                                    ramTier = ramTier
                                )
                            ) {
                                "与当前离线听音组合将自动启用串行调度（先卸听音再润色），防止内存峰值过高"
                            } else {
                                null
                            }

                            val hasOnlineDownload = model.downloadUrl.isNotBlank() ||
                                LocalInferenceEngineRegistry.hasDownloadableSlmSource(model.id)
                            OfflineModelCard(
                                model = model,
                                record = record,
                                ramCompatibility = compat,
                                isSelected = isSelected,
                                isEffective = isEffective,
                                isBusy = activeOperatingModelId != null,
                                serialExecutionWarning = serialWarn,
                                hasOnlineDownload = hasOnlineDownload,
                                onDownload = { handleRequestDownload(model) },
                                onCancelDownload = {
                                    ModelDownloadWorker.cancelDownload(context, model.id)
                                },
                                onImportLocalGguf = if (!hasOnlineDownload) {
                                    { slmImportDialog = LocalSlmImportDialogState(targetModel = model) }
                                } else {
                                    null
                                },
                                onSelectActive = {
                                    val prevId = offlineConfig.selectedSlmModelId
                                    repository.updateOfflineConfig {
                                        it.copy(
                                            slmSlotOn = true,
                                            selectedSlmModelId = model.id
                                        )
                                    }
                                    if (prevId != model.id) {
                                        unloadLocalSlotAsync(LocalModelSlot.SLM)
                                    }
                                    Toast.makeText(
                                        context,
                                        "✓ 已启用「${model.displayName}」（其余已下载大脑自动保留待用）",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onUninstall = { uninstallConfirmModel = model }
                            )
                        }
                    }
                }
            }

            // 6. Bottom Storage Management Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "本机离线权重存储",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "当前占用 ${formatByteSize(storageBytes)} · 存放于应用私有不备份目录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showClearAllDialog = true },
                        enabled = storageBytes > 0L || registrySnapshot.records.isNotEmpty(),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "一键清理全部",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (storageBytes > 0L || registrySnapshot.records.isNotEmpty()) {
                                TypeFreeRose
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun OfflineModeSegmentedRow(
    selectedMode: OfflineMode,
    onSelectMode: (OfflineMode) -> Unit,
    compact: Boolean
) {
    val options = listOf(
        OfflineMode.OFF to "关闭",
        OfflineMode.HYBRID to if (compact) "混合" else "混合（推荐）",
        OfflineMode.OFFLINE_ONLY to if (compact) "纯离线" else "纯离线锁"
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (mode, label) ->
                val selected = selectedMode == mode
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onSelectMode(mode) },
                    shape = RoundedCornerShape(9.dp),
                    color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shadowElevation = if (selected) 1.dp else 0.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = if (compact) 7.dp else 9.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfflineModelCard(
    model: LocalModelDescriptor,
    record: LocalModelRecord?,
    ramCompatibility: RamCompatibility,
    isSelected: Boolean,
    isEffective: Boolean,
    isBusy: Boolean,
    serialExecutionWarning: String?,
    hasOnlineDownload: Boolean = model.downloadUrl.isNotBlank(),
    onDownload: () -> Unit,
    onCancelDownload: (() -> Unit)? = null,
    onImportLocalGguf: (() -> Unit)? = null,
    onSelectActive: () -> Unit,
    onUninstall: () -> Unit
) {
    val status = record?.status ?: LocalModelInstallStatus.NOT_INSTALLED
    val isInstalled = status == LocalModelInstallStatus.INSTALLED
    val isDownloading = status == LocalModelInstallStatus.DOWNLOADING || status == LocalModelInstallStatus.VERIFYING

    val borderColor = when {
        isInstalled && isSelected -> TypeFreeEmerald.copy(alpha = 0.65f)
        status == LocalModelInstallStatus.CORRUPTED || status == LocalModelInstallStatus.UNSUPPORTED_ON_DEVICE ->
            TypeFreeRose.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isInstalled && isSelected) {
            TypeFreeEmerald.copy(alpha = 0.06f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = BorderStroke(if (isInstalled && isSelected) 1.3.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Title + Size + RAM compatibility chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = model.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = formatByteSize(model.sizeBytes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val (compatLabel, compatColor) = when (ramCompatibility) {
                        RamCompatibility.RECOMMENDED ->
                            (if (model.isDefaultRecommended) "默认推荐" else "推荐") to TypeFreeEmerald
                        RamCompatibility.TIGHT -> "偏紧" to TypeFreeAmber
                        RamCompatibility.RISK_INSUFFICIENT -> "内存风险" to TypeFreeRose
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = compatColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = compatLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = compatColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Feature tags
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (model.slot == LocalModelSlot.ASR) {
                    ModelTagChip(
                        text = if (model.supportsTrueStreaming) "真逐字流式" else "准流式 · VAD 分句",
                        accent = if (model.supportsTrueStreaming) TypeFreeEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ModelTagChip(
                        text = if (model.supportsNativeHotwords) "原生热词偏置" else "词典后处理纠错",
                        accent = if (model.supportsNativeHotwords) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (model.isPendingVerification) {
                        ModelTagChip(text = "待真机验证", accent = TypeFreeAmber)
                    }
                } else {
                    if (model.primaryTasks.isNotEmpty()) {
                        val primaryText = model.primaryTasks.joinToString(" / ") { taskShortName(it) }
                        ModelTagChip(text = "主接管：$primaryText", accent = TypeFreeEmerald)
                    }
                    if (model.fallbackTasks.isNotEmpty()) {
                        val fallbackText = model.fallbackTasks.joinToString(" / ") { taskShortName(it) }
                        ModelTagChip(text = "断网兜底：$fallbackText", accent = TypeFreeAmber)
                    }
                }
            }

            if (serialExecutionWarning != null) {
                Text(
                    text = "ℹ️ $serialExecutionWarning",
                    style = MaterialTheme.typography.labelSmall,
                    color = TypeFreeAmber
                )
            }

            // Progress bar while downloading or verifying
            if (isDownloading && record != null) {
                val total = record.totalBytes.coerceAtLeast(1L)
                val progress = (record.downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (status == LocalModelInstallStatus.VERIFYING) {
                                "正在 SHA-256 校验与短推理探活…"
                            } else {
                                "正在下载… ${formatByteSize(record.downloadedBytes)} / ${formatByteSize(record.totalBytes)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { if (status == LocalModelInstallStatus.VERIFYING) 1f else progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(50))
                    )
                }
            }

            // Corrupted / unsupported warning
            if ((status == LocalModelInstallStatus.CORRUPTED || status == LocalModelInstallStatus.UNSUPPORTED_ON_DEVICE) &&
                !record?.errorReason.isNullOrBlank()
            ) {
                Text(
                    text = "⚠️ ${record?.errorReason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TypeFreeRose
                )
            }

            // Deduplicated Action Row: ONLY Download / Set Active (or green Active badge) / Uninstall
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (isInstalled && isSelected) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = TypeFreeEmerald.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = TypeFreeEmerald,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (isEffective) "已启用 · 生效中" else "已启用",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TypeFreeEmerald
                            )
                        }
                    }
                } else {
                    Text(
                        text = when (status) {
                            LocalModelInstallStatus.INSTALLED -> "已下载（未选中）"
                            LocalModelInstallStatus.DOWNLOADING -> "下载中…"
                            LocalModelInstallStatus.PAUSED -> "已暂停（可断点续传）"
                            LocalModelInstallStatus.VERIFYING -> "校验中…"
                            LocalModelInstallStatus.CORRUPTED -> "权重异常"
                            LocalModelInstallStatus.UNSUPPORTED_ON_DEVICE -> "本机探活未通过"
                            else -> "约需 ${model.approxRamMb}MB 运行内存"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isInstalled) {
                        if (!isSelected) {
                            FilledTonalButton(
                                onClick = onSelectActive,
                                enabled = !isBusy,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("设为启用", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (onImportLocalGguf != null) {
                            TextButton(
                                onClick = onImportLocalGguf,
                                enabled = !isBusy,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "导入 .gguf",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        TextButton(
                            onClick = onUninstall,
                            enabled = !isBusy,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "卸载",
                                style = MaterialTheme.typography.labelMedium,
                                color = TypeFreeRose
                            )
                        }
                    } else if (status == LocalModelInstallStatus.DOWNLOADING && onCancelDownload != null) {
                        OutlinedButton(
                            onClick = onCancelDownload,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "取消",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TypeFreeRose
                            )
                        }
                    } else if (onImportLocalGguf != null && !hasOnlineDownload) {
                        Button(
                            onClick = onImportLocalGguf,
                            enabled = !isBusy && !isDownloading,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isDownloading) "导入中…" else "导入本机 .gguf",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        if (onImportLocalGguf != null) {
                            OutlinedButton(
                                onClick = onImportLocalGguf,
                                enabled = !isBusy && !isDownloading,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "导入 .gguf",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        Button(
                            onClick = onDownload,
                            enabled = !isBusy && !isDownloading,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = when {
                                    isDownloading -> "处理中…"
                                    status == LocalModelInstallStatus.PAUSED -> "继续下载"
                                    status == LocalModelInstallStatus.CORRUPTED ||
                                        status == LocalModelInstallStatus.UNSUPPORTED_ON_DEVICE -> "重新下载"
                                    else -> "下载"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelTagChip(
    text: String,
    accent: Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(0.5.dp, accent.copy(alpha = 0.28f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

private fun taskShortName(task: AiTask): String = when (task) {
    AiTask.DICTATE -> "净写"
    AiTask.SENTENCE_COMPLETION -> "整句补全"
    AiTask.SPEAK_TO_EDIT -> "选中改写"
    AiTask.TEXT_TRANSFORM -> "文本润色"
    AiTask.TRANSLATE -> "翻译"
    AiTask.HELP_ME_WRITE -> "帮我写"
    AiTask.JEV_COPILOT -> "离线军师(纯文本)"
    AiTask.BUBBLE_COPILOT -> "悬浮军师"
    AiTask.ASK_AI_CARD -> "问AI"
    AiTask.NOTE_TRANSCRIBE -> "笔记转写"
}

private fun offlineModeLabel(mode: OfflineMode): String = when (mode) {
    OfflineMode.OFF -> "关闭"
    OfflineMode.HYBRID -> "混合模式"
    OfflineMode.OFFLINE_ONLY -> "纯离线锁"
}

private fun resolveDocumentDisplayName(
    context: Context,
    uri: Uri,
    fallback: String
): String {
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(idx)?.trim()
                if (!name.isNullOrEmpty()) return name
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: fallback
}

private fun formatByteSize(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        safe >= gb -> String.format(Locale.US, "%.2f GB", safe / gb)
        safe >= mb -> String.format(Locale.US, "%.0f MB", safe / mb)
        safe >= kb -> String.format(Locale.US, "%.0f KB", safe / kb)
        else -> "$safe B"
    }
}
