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
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
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
    val appLang = LocalAppLanguage.current
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
                        text = tr("📱 本机离线模型", "📱 On-Device Models", "📱 本機離線模型"),
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
                            text = if (pendingRestore != null) {
                                tr("待恢复", "Pending Restore", "待恢復")
                            } else {
                                tr("未开启 · 免费开源听音", "Off · Free Local ASR", "未開啟 · 免費開源聽音")
                            },
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
                        text = tr("配置本机离线", "Set Up", "設定本機離線"),
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
                    text = tr("📱 本机离线模型", "📱 On-Device Models", "📱 本機離線模型"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onOpenOfflineModels,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        text = tr("管理模型与下载", "Manage Models", "管理模型與下載"),
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
                val asrDisplay = asrModel?.let { offlineModelDisplayName(it, appLang) }
                val slmDisplay = slmModel?.let { offlineModelDisplayName(it, appLang) }
                val asrSummary = when {
                    !offline.asrSlotOn -> tr(
                        "👂 离线听音：槽位开关已关闭（保留已选模型）",
                        "👂 Local ASR: Slot disabled (selected model kept)",
                        "👂 離線聽音：槽位開關已關閉（保留已選模型）"
                    )
                    asrEffective && asrDisplay != null -> tr(
                        "👂 离线听音：已生效 · $asrDisplay",
                        "👂 Local ASR: Active · $asrDisplay",
                        "👂 離線聽音：已生效 · $asrDisplay"
                    )
                    asrDisplay != null -> tr(
                        "👂 离线听音：$asrDisplay（未安装或暂不可用）",
                        "👂 Local ASR: $asrDisplay (not installed or unavailable)",
                        "👂 離線聽音：$asrDisplay（未安裝或暫不可用）"
                    )
                    else -> tr(
                        "👂 离线听音：尚未下载模型（点击右上角下载）",
                        "👂 Local ASR: No model downloaded (tap Manage Models)",
                        "👂 離線聽音：尚未下載模型（點擊右上角下載）"
                    )
                }
                val slmSummary = when {
                    !registrySnapshot.slmEngineAvailable -> tr(
                        "🧠 离线大脑：公开开源版占位（可搭配云端 BYOK 或免费直出）",
                        "🧠 Local Brain: Open-source build (pair with Cloud BYOK or raw ASR)",
                        "🧠 離線大腦：公開開源版佔位（可搭配雲端 BYOK 或免費直出）"
                    )
                    !offline.slmSlotOn -> tr(
                        "🧠 离线大脑：槽位开关已关闭",
                        "🧠 Local Brain: Slot disabled",
                        "🧠 離線大腦：槽位開關已關閉"
                    )
                    slmEffective && slmDisplay != null -> tr(
                        "🧠 离线大脑：已生效 · $slmDisplay",
                        "🧠 Local Brain: Active · $slmDisplay",
                        "🧠 離線大腦：已生效 · $slmDisplay"
                    )
                    slmDisplay != null -> tr(
                        "🧠 离线大脑：$slmDisplay（未安装或暂不可用）",
                        "🧠 Local Brain: $slmDisplay (not installed or unavailable)",
                        "🧠 離線大腦：$slmDisplay（未安裝或暫不可用）"
                    )
                    else -> tr(
                        "🧠 离线大脑：未下载（点击右上角按需下载）",
                        "🧠 Local Brain: Not downloaded (tap Manage Models)",
                        "🧠 離線大腦：未下載（點擊右上角按需下載）"
                    )
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
                            text = tr(
                                "听写润色已关闭，语音听写将仅输出转写原稿",
                                "Dictation Polish is off — voice input outputs raw transcripts only",
                                "聽寫潤飾已關閉，語音聽寫將僅輸出轉寫原稿"
                            ),
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
                                text = tr("一键开启", "Enable", "一鍵開啟"),
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
    val appLang = LocalAppLanguage.current
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
                AppStrings.pick(
                    appLang,
                    "✓ 离线模型校验完成，已自动恢复导入的离线模式配置",
                    "✓ Offline models verified — restored imported offline mode",
                    "✓ 離線模型校驗完成，已自動恢復匯入的離線模式設定"
                ),
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
            val disp = offlineModelDisplayName(model, appLang)
            Toast.makeText(
                context,
                AppStrings.pick(
                    appLang,
                    "✓ 「$disp」已安装并设为启用",
                    "✓ \"$disp\" installed and set active",
                    "✓ 「$disp」已安裝並設為啟用"
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun startImportLocalSlm(dialogState: LocalSlmImportDialogState) {
        val targetRegistry = registry ?: run {
            Toast.makeText(
                context,
                AppStrings.pick(appLang, "本机模型注册表未就绪", "Local model registry is not ready", "本機模型註冊表未就緒"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val ggufUri = dialogState.ggufUri ?: run {
            Toast.makeText(
                context,
                AppStrings.pick(appLang, "请先选择主模型 .gguf 文件", "Please select the base .gguf file first", "請先選擇主模型 .gguf 檔案"),
                Toast.LENGTH_SHORT
            ).show()
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
                        ?: throw IOException(
                            AppStrings.pick(
                                appLang,
                                "无法打开所选主模型文件：${dialogState.ggufName}",
                                "Cannot open selected base model file: ${dialogState.ggufName}",
                                "無法開啟所選主模型檔案：${dialogState.ggufName}"
                            )
                        )
                },
                ggufFileName = dialogState.ggufName,
                loraStreamProvider = dialogState.loraUri?.let { loraUri ->
                    {
                        context.contentResolver.openInputStream(loraUri)
                            ?: throw IOException(
                                AppStrings.pick(
                                    appLang,
                                    "无法打开所选 LoRA 文件：${dialogState.loraName}",
                                    "Cannot open selected LoRA file: ${dialogState.loraName}",
                                    "無法開啟所選 LoRA 檔案：${dialogState.loraName}"
                                )
                            )
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
                    Toast.makeText(
                        context,
                        AppStrings.pick(
                            appLang,
                            "导入校验失败：${outcome.reason}",
                            "Import verification failed: ${outcome.reason}",
                            "匯入校驗失敗：${outcome.reason}"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }

                is ModelInstallOutcome.DownloadFailed -> {
                    Toast.makeText(
                        context,
                        AppStrings.pick(
                            appLang,
                            "导入失败：${outcome.reason}",
                            "Import failed: ${outcome.reason}",
                            "匯入失敗：${outcome.reason}"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }

                is ModelInstallOutcome.ProbeFailed -> {
                    val rollbackTip = if (outcome.keptOldModelId != null) {
                        AppStrings.pick(
                            appLang,
                            "已保留原模型不受影响。",
                            " Existing model was kept intact.",
                            "已保留原模型不受影響。"
                        )
                    } else {
                        ""
                    }
                    Toast.makeText(
                        context,
                        AppStrings.pick(
                            appLang,
                            "本机探活失败：${outcome.reason}。$rollbackTip",
                            "On-device health check failed: ${outcome.reason}.$rollbackTip",
                            "本機探活失敗：${outcome.reason}。$rollbackTip"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }

                is ModelInstallOutcome.InsufficientDiskSpace -> {
                    val avail = formatByteSize(outcome.availableBytes)
                    diskInsufficientMsg = AppStrings.pick(
                        appLang,
                        "存储空间不足：当前可用 $avail。",
                        "Insufficient storage: $avail currently available.",
                        "儲存空間不足：目前可用 $avail。"
                    )
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
            Toast.makeText(
                context,
                AppStrings.pick(appLang, "本机模型注册表未就绪", "Local model registry is not ready", "本機模型註冊表未就緒"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val modeBeforeDownload = repository.settings.value.offlineConfig.mode
        val modelDisp = offlineModelDisplayName(model, appLang)
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
                        Toast.makeText(
                            context,
                            AppStrings.pick(
                                appLang,
                                "已取消下载「$modelDisp」",
                                "Cancelled downloading \"$modelDisp\"",
                                "已取消下載「$modelDisp」"
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    WorkInfo.State.FAILED -> {
                        val outcomeType = finalInfo.outputData.getString(ModelDownloadWorker.KEY_OUTCOME_TYPE)
                        val reason = finalInfo.outputData.getString(ModelDownloadWorker.KEY_ERROR_REASON)
                            ?: AppStrings.pick(appLang, "模型安装失败", "Model installation failed", "模型安裝失敗")
                        when (outcomeType) {
                            ModelDownloadWorker.OUTCOME_INSUFFICIENT_DISK -> {
                                diskInsufficientMsg = reason
                            }
                            ModelDownloadWorker.OUTCOME_VERIFICATION_FAILED -> {
                                Toast.makeText(
                                    context,
                                    AppStrings.pick(
                                        appLang,
                                        "完整性校验失败：$reason",
                                        "Integrity check failed: $reason",
                                        "完整性校驗失敗：$reason"
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            ModelDownloadWorker.OUTCOME_PROBE_FAILED -> {
                                Toast.makeText(
                                    context,
                                    AppStrings.pick(
                                        appLang,
                                        "本机探活失败：$reason",
                                        "On-device health check failed: $reason",
                                        "本機探活失敗：$reason"
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            else -> {
                                Toast.makeText(
                                    context,
                                    AppStrings.pick(
                                        appLang,
                                        "下载失败：$reason",
                                        "Download failed: $reason",
                                        "下載失敗：$reason"
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
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
                    val avail = formatByteSize(outcome.availableBytes)
                    val req = formatByteSize(outcome.requiredBytes)
                    diskInsufficientMsg = AppStrings.pick(
                        appLang,
                        "存储空间不足：当前可用 $avail，下载「$modelDisp」并保留 500MB 安全余量需要约 $req。",
                        "Insufficient storage: $avail available, ~$req required to download \"$modelDisp\" (including 500 MB safety buffer).",
                        "儲存空間不足：目前可用 $avail，下載「$modelDisp」並保留 500MB 安全餘量需要約 $req。"
                    )
                }

                is ModelInstallOutcome.DownloadFailed -> {
                    Toast.makeText(
                        context,
                        AppStrings.pick(
                            appLang,
                            "下载失败：${outcome.reason}",
                            "Download failed: ${outcome.reason}",
                            "下載失敗：${outcome.reason}"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }

                is ModelInstallOutcome.VerificationFailed -> {
                    Toast.makeText(
                        context,
                        AppStrings.pick(
                            appLang,
                            "完整性校验失败：${outcome.reason}",
                            "Integrity check failed: ${outcome.reason}",
                            "完整性校驗失敗：${outcome.reason}"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }

                is ModelInstallOutcome.ProbeFailed -> {
                    val rollbackTip = if (outcome.keptOldModelId != null) {
                        AppStrings.pick(
                            appLang,
                            "已保留原模型不受影响。",
                            " Existing model was kept intact.",
                            "已保留原模型不受影響。"
                        )
                    } else {
                        ""
                    }
                    Toast.makeText(
                        context,
                        AppStrings.pick(
                            appLang,
                            "本机探活失败：${outcome.reason}。$rollbackTip",
                            "On-device health check failed: ${outcome.reason}.$rollbackTip",
                            "本機探活失敗：${outcome.reason}。$rollbackTip"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun handleRequestDownload(model: LocalModelDescriptor) {
        val targetRegistry = registry ?: run {
            Toast.makeText(
                context,
                AppStrings.pick(appLang, "本机模型注册表未就绪", "Local model registry is not ready", "本機模型註冊表未就緒"),
                Toast.LENGTH_SHORT
            ).show()
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
                val avail = formatByteSize(check.availableBytes)
                val req = formatByteSize(check.requiredBytes)
                val disp = offlineModelDisplayName(model, appLang)
                diskInsufficientMsg = AppStrings.pick(
                    appLang,
                    "存储空间不足：当前可用 $avail，安装「$disp」（含 500MB 安全余量）至少需要 $req。",
                    "Insufficient storage: $avail available, at least $req required to install \"$disp\" (including 500 MB safety buffer).",
                    "儲存空間不足：目前可用 $avail，安裝「$disp」（含 500MB 安全餘量）至少需要 $req。"
                )
            }
        }
    }

    // Dialog 1: Disk space requires deleting old model first (non-rollbackable warning)
    deleteOldConfirm?.let { pending ->
        val oldName = OfflineModelCatalog.findById(pending.oldModelId)
            ?.let { offlineModelDisplayName(it, appLang) }
            ?: pending.oldModelId
        val targetName = offlineModelDisplayName(pending.targetModel, appLang)
        val avail = formatByteSize(pending.availableBytes)
        val req = formatByteSize(pending.requiredBytes)
        val oldSize = formatByteSize(pending.oldModelSizeBytes)
        AlertDialog(
            onDismissRequest = { deleteOldConfirm = null },
            title = {
                Text(
                    tr(
                        "存储空间不足 · 需先删除旧模型",
                        "Low Storage · Remove Previous Model",
                        "儲存空間不足 · 需先刪除舊模型"
                    )
                )
            },
            text = {
                Text(
                    tr(
                        "当前可用空间 $avail，直接下载「$targetName」（含 500MB 安全余量需 $req）空间不足。\n\n需要先删除同槽位旧模型「$oldName」（约 $oldSize）以腾出空间。请注意：若后续下载或探活失败，旧模型将无法自动回滚。是否继续？",
                        "Only $avail is available, which is not enough to download \"$targetName\" ($req needed including a 500 MB buffer).\n\nTypeFree needs to delete the previous model in this slot (\"$oldName\", ~$oldSize) first. Note: if the download or health check fails afterward, the old model cannot be rolled back automatically. Continue?",
                        "目前可用空間 $avail，直接下載「$targetName」（含 500MB 安全餘量需 $req）空間不足。\n\n需要先刪除同槽位舊模型「$oldName」（約 $oldSize）以騰出空間。請注意：若後續下載或探活失敗，舊模型將無法自動回滾。是否繼續？"
                    )
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
                    Text(tr("删除旧模型并下载", "Delete Old & Download", "刪除舊模型並下載"))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteOldConfirm = null }) {
                    Text(tr("取消", "Cancel", "取消"))
                }
            }
        )
    }

    // Dialog 2: First model ready while mode == OFF (never silently overwrite user's cloud BYOK)
    firstReadyPrompt?.let { prompt ->
        val disp = offlineModelDisplayName(prompt.model, appLang)
        AlertDialog(
            onDismissRequest = { firstReadyPrompt = null },
            title = {
                Text(tr("模型已就绪，是否立即启用？", "Model Ready — Enable Now?", "模型已就緒，是否立即啟用？"))
            },
            text = {
                val body = if (prompt.model.slot == LocalModelSlot.ASR) {
                    tr(
                        "「$disp」已下载并通过完整性探活。\n\n是否立即开启「混合模式」并用本机听音替代当前云端听音？\n（你原有的云端 API 密钥与配置将完整保留，随时可切回「关闭」。）",
                        "\"$disp\" has been downloaded and passed on-device verification.\n\nSwitch to Hybrid Mode now and use on-device ASR for speech recognition?\n(Your cloud API keys and settings remain untouched and you can switch back to Off anytime.)",
                        "「$disp」已下載並通過完整性探活。\n\n是否立即開啟「混合模式」並用本機聽音替代目前雲端聽音？\n（你原有的雲端 API 金鑰與設定將完整保留，隨時可切回「關閉」。）"
                    )
                } else {
                    tr(
                        "「$disp」已下载并通过完整性探活。\n\n是否立即开启「混合模式」并用本机大脑接管支持的润色与补全任务？\n（你原有的云端 API 密钥与配置将完整保留，随时可切回「关闭」。）",
                        "\"$disp\" has been downloaded and passed on-device verification.\n\nSwitch to Hybrid Mode now and let the local text brain handle supported polish and completion tasks?\n(Your cloud API keys and settings remain untouched and you can switch back to Off anytime.)",
                        "「$disp」已下載並通過完整性探活。\n\n是否立即開啟「混合模式」並用本機大腦接管支援的潤飾與補全任務？\n（你原有的雲端 API 金鑰與設定將完整保留，隨時可切回「關閉」。）"
                    )
                }
                Text(body)
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
                        Toast.makeText(
                            context,
                            AppStrings.pick(
                                appLang,
                                "✓ 已开启混合模式并启用本机模型",
                                "✓ Enabled Hybrid Mode with local model",
                                "✓ 已開啟混合模式並啟用本機模型"
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text(tr("立即开启混合模式", "Enable Hybrid Mode", "立即開啟混合模式"))
                }
            },
            dismissButton = {
                TextButton(onClick = { firstReadyPrompt = null }) {
                    Text(tr("暂不开启", "Not Now", "暫不開啟"))
                }
            }
        )
    }

    // Dialog 3: Uninstall single model
    uninstallConfirmModel?.let { model ->
        val disp = offlineModelDisplayName(model, appLang)
        AlertDialog(
            onDismissRequest = { uninstallConfirmModel = null },
            title = { Text(tr("卸载离线模型", "Uninstall Offline Model", "解除安裝離線模型")) },
            text = {
                Text(
                    tr(
                        "确定卸载「$disp」？卸载将释放本机磁盘空间并卸载对应内存权重，不会影响你的云端 API 配置。",
                        "Uninstall \"$disp\"? This frees local disk space and unloads weights from RAM without affecting your cloud API settings.",
                        "確定解除安裝「$disp」？解除安裝將釋放本機磁碟空間並卸載對應記憶體權重，不會影響你的雲端 API 設定。"
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = model
                        val targetDisp = offlineModelDisplayName(target, appLang)
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
                            Toast.makeText(
                                context,
                                AppStrings.pick(
                                    appLang,
                                    "已卸载「$targetDisp」",
                                    "Uninstalled \"$targetDisp\"",
                                    "已解除安裝「$targetDisp」"
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TypeFreeRose)
                ) {
                    Text(tr("确认卸载", "Uninstall", "確認解除安裝"))
                }
            },
            dismissButton = {
                TextButton(onClick = { uninstallConfirmModel = null }) {
                    Text(tr("取消", "Cancel", "取消"))
                }
            }
        )
    }

    // Dialog 4: Clear all offline weights
    if (showClearAllDialog) {
        val usedSize = formatByteSize(storageBytes)
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text(tr("一键清理全部离线权重", "Clear All Offline Weights", "一鍵清理全部離線權重")) },
            text = {
                Text(
                    tr(
                        "将删除本机所有已下载的离线语音与文本模型文件（当前占用 $usedSize），并清空当前已选模型记录。云端 API 配置不受影响。",
                        "Deletes all downloaded local speech and text model files (currently using $usedSize) and clears active model selections. Your cloud API settings are unaffected.",
                        "將刪除本機所有已下載的離線語音與文字模型檔案（目前佔用 $usedSize），並清空目前已選模型紀錄。雲端 API 設定不受影響。"
                    )
                )
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
                            Toast.makeText(
                                context,
                                AppStrings.pick(
                                    appLang,
                                    "已清理全部离线权重",
                                    "Cleared all offline model weights",
                                    "已清理全部離線權重"
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TypeFreeRose)
                ) {
                    Text(tr("确认清理", "Clear All", "確認清理"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text(tr("取消", "Cancel", "取消"))
                }
            }
        )
    }

    // Dialog 5: Disk insufficient notice
    diskInsufficientMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { diskInsufficientMsg = null },
            title = { Text(tr("可用存储空间不足", "Insufficient Storage", "可用儲存空間不足")) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { diskInsufficientMsg = null }) {
                    Text(tr("知道了", "Got It", "知道了"))
                }
            }
        )
    }

    // Dialog 6: Local SLM `.gguf` (+ optional LoRA `.gguf`) SAF Import Dialog (Pro Engine Only)
    slmImportDialog?.let { state ->
        val targetDisp = offlineModelDisplayName(state.targetModel, appLang)
        AlertDialog(
            onDismissRequest = { slmImportDialog = null },
            title = { Text(tr("导入本机 .gguf 模型", "Import Local .gguf Model", "匯入本機 .gguf 模型")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = tr(
                            "目标槽位：$targetDisp\n将复制到应用私有不备份目录并执行真实探活。",
                            "Target slot: $targetDisp\nCopied into private non-backup app storage and verified on device.",
                            "目標槽位：$targetDisp\n將複製到應用私有不備份目錄並執行真實探活。"
                        ),
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
                                text = tr("主模型权重（必选 .gguf）", "Base Model Weights (Required .gguf)", "主模型權重（必選 .gguf）"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = state.ggufName.ifBlank { tr("尚未选择文件", "No file selected", "尚未選擇檔案") },
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
                                    text = if (state.ggufUri != null) {
                                        tr("重新选择主模型 .gguf", "Reselect Base .gguf", "重新選擇主模型 .gguf")
                                    } else {
                                        tr("选择主模型 .gguf", "Choose Base .gguf", "選擇主模型 .gguf")
                                    },
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
                                text = tr("LoRA 适配器（可选 .gguf）", "LoRA Adapter (Optional .gguf)", "LoRA 適配器（可選 .gguf）"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = state.loraName.ifBlank {
                                    tr(
                                        "不附带 LoRA（合并版模型直接留空）",
                                        "No LoRA attached (leave empty for merged GGUF)",
                                        "不附帶 LoRA（合併版模型直接留空）"
                                    )
                                },
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
                                        text = if (state.loraUri != null) {
                                            tr("更换 LoRA", "Change LoRA", "更換 LoRA")
                                        } else {
                                            tr("选择可选 LoRA", "Select Optional LoRA", "選擇可選 LoRA")
                                        },
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                if (state.loraUri != null) {
                                    TextButton(
                                        onClick = {
                                            slmImportDialog = state.copy(loraUri = null, loraName = "")
                                        }
                                    ) {
                                        Text(tr("清除", "Clear", "清除"), color = TypeFreeRose)
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
                    Text(tr("开始导入并探活", "Import & Verify", "開始匯入並探活"))
                }
            },
            dismissButton = {
                TextButton(onClick = { slmImportDialog = null }) {
                    Text(tr("取消", "Cancel", "取消"))
                }
            }
        )
    }

    // Dialog 7: Imported local SLM succeeded — one-tap activate prompt
    importedSlmReadyPrompt?.let { prompt ->
        val disp = offlineModelDisplayName(prompt.model, appLang)
        AlertDialog(
            onDismissRequest = { importedSlmReadyPrompt = null },
            title = { Text(tr("本机 GGUF 导入并探活成功", "GGUF Imported & Verified", "本機 GGUF 匯入並探活成功")) },
            text = {
                val loraTip = if (prompt.hadLora) {
                    tr("（已附带 LoRA 权重）", " (with LoRA weights)", "（已附帶 LoRA 權重）")
                } else {
                    ""
                }
                Text(
                    tr(
                        "「$disp」$loraTip 已通过本机真实推理探活。\n\n是否立即将其设为当前启用的离线文本大脑？",
                        "\"$disp\"$loraTip passed on-device inference verification.\n\nSet it as your active local text brain now?",
                        "「$disp」$loraTip 已通過本機真實推理探活。\n\n是否立即將其設為目前啟用的離線文字大腦？"
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val model = prompt.model
                        val modelDisp = offlineModelDisplayName(model, appLang)
                        importedSlmReadyPrompt = null
                        repository.updateOfflineConfig { curr ->
                            curr.copy(
                                slmSlotOn = true,
                                selectedSlmModelId = model.id
                            )
                        }
                        Toast.makeText(
                            context,
                            AppStrings.pick(
                                appLang,
                                "✓ 已将「$modelDisp」设为启用大脑",
                                "✓ Set \"$modelDisp\" as active local brain",
                                "✓ 已將「$modelDisp」設為啟用大腦"
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text(tr("设为启用", "Set Active", "設為啟用"))
                }
            },
            dismissButton = {
                TextButton(onClick = { importedSlmReadyPrompt = null }) {
                    Text(tr("稍后再说", "Later", "稍後再說"))
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
                        contentDescription = tr("返回", "Back", "返回"),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(tr("返回", "Back", "返回"), fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = tr("本机离线模型", "On-Device Models", "本機離線模型"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (registrySnapshot.slmEngineAvailable) {
                    Box {
                        TextButton(onClick = { showOverflowMenu = true }) {
                            Text(tr("更多 ⋯", "More ⋯", "更多 ⋯"), fontWeight = FontWeight.SemiBold)
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            OfflineModelCatalog.slmModels.forEach { slmModel ->
                                val slmDisp = offlineModelDisplayName(slmModel, appLang)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            tr(
                                                "高级：手动导入 $slmDisp (.gguf)",
                                                "Advanced: Import $slmDisp (.gguf)",
                                                "進階：手動匯入 $slmDisp (.gguf)"
                                            )
                                        )
                                    },
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
            val tierLabel = ramTierLabel(ramTier, appLang)
            val ramRecommendation = when (ramTier) {
                RamTier.LOW_6GB -> tr("推荐 SenseVoice + 0.8B", "Rec: SenseVoice + 0.8B", "推薦 SenseVoice + 0.8B")
                RamTier.MID_8GB -> tr("推荐 SenseVoice + 0.8B / 2B", "Rec: SenseVoice + 0.8B / 2B", "推薦 SenseVoice + 0.8B / 2B")
                RamTier.HIGH_12GB_PLUS -> tr("推荐 SenseVoice + 0.8B / 2B", "Rec: SenseVoice + 0.8B / 2B", "推薦 SenseVoice + 0.8B / 2B")
            }
            val totalRamGb = memorySnapshot.totalRamMb / 1024.0
            val availRamGb = memorySnapshot.availRamMb / 1024.0
            val totalRamStr = String.format(Locale.US, "%.1f", totalRamGb)
            val availRamStr = String.format(Locale.US, "%.1f", availRamGb)
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
                            text = tr(
                                "本机 $tierLabel · $ramRecommendation",
                                "Device $tierLabel · $ramRecommendation",
                                "本機 $tierLabel · $ramRecommendation"
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = tr(
                                "物理内存 $totalRamStr GB · 当前可用 $availRamStr GB（不含虚拟内存扩展）",
                                "Physical RAM $totalRamStr GB · Available $availRamStr GB (excl. virtual RAM)",
                                "實體記憶體 $totalRamStr GB · 目前可用 $availRamStr GB（不含虛擬記憶體擴充）"
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
                            text = tierLabel,
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
                val restoreAsrName = OfflineModelCatalog.findById(pendingRestore.selectedAsrModelId)
                    ?.let { offlineModelDisplayName(it, appLang) }
                val restoreSlmName = OfflineModelCatalog.findById(pendingRestore.selectedSlmModelId)
                    ?.let { offlineModelDisplayName(it, appLang) }
                val modelListText = listOfNotNull(restoreAsrName, restoreSlmName).joinToString(" + ")
                val modeName = offlineModeLabel(pendingRestore.mode, appLang)
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
                                text = tr(
                                    "检测到导入配置启用了离线模型，下载完成后自动恢复",
                                    "Imported backup uses offline models — will auto-restore once downloaded",
                                    "偵測到匯入設定啟用了離線模型，下載完成後自動恢復"
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (modelListText.isNotBlank()) {
                                Text(
                                    text = tr(
                                        "目标配置（$modeName）：$modelListText",
                                        "Target ($modeName): $modelListText",
                                        "目標設定（$modeName）：$modelListText"
                                    ),
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
                                text = tr("忽略", "Dismiss", "忽略"),
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
                        text = tr("离线运行模式", "Offline Routing Mode", "離線執行模式"),
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
                        OfflineMode.OFF -> tr(
                            "当前完全沿用云端 BYOK 配置；已下载的离线模型仍保留在磁盘中，随时可一键开启。",
                            "Uses your Cloud BYOK settings exclusively. Downloaded local models stay on disk and can be enabled anytime.",
                            "目前完全沿用雲端 BYOK 設定；已下載的離線模型仍保留在磁碟中，隨時可一鍵開啟。"
                        )
                        OfflineMode.HYBRID -> tr(
                            "混合模式（推荐）：主接管任务优先本机执行，超纲任务自动走云端。注意：「本地听音 + 云端润色」仍会将转写文本发送至你配置的云端服务。",
                            "Hybrid (Recommended): Supported tasks run on device first and fall back to cloud for advanced tasks. Note: \"Local ASR + Cloud Polish\" still sends transcripts to your configured cloud API.",
                            "混合模式（推薦）：主接管任務優先本機執行，進階任務自動走雲端。注意：「本地聽音 + 雲端潤飾」仍會將轉寫文字發送至你設定的雲端服務。"
                        )
                        OfflineMode.OFFLINE_ONLY -> tr(
                            "纯离线锁：严格禁止所有语音、文本、补全与端点预热出网（你在本页主动点击下载模型包除外）；当前模型不支持的任务将直接提示并拦截。",
                            "Offline Lock: Strictly blocks all network egress for speech, text, completion, and warmup (except manual model downloads on this screen). Unsupported tasks are blocked with a notice.",
                            "純離線鎖：嚴格禁止所有語音、文字、補全與端點預熱連網（你在本頁主動點擊下載模型包除外）；目前模型不支援的任務將直接提示並攔截。"
                        )
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
                                    text = tr(
                                        "听写润色开关当前已关闭，语音听写将仅做转写直出（不影响主动改写/补全）",
                                        "Dictation Polish is off — dictation outputs raw transcripts (Voice Edit & Completion still work)",
                                        "聽寫潤飾開關目前已關閉，語音聽寫將僅做轉寫直出（不影響主動改寫/補全）"
                                    ),
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
                                    Text(tr("开启润色", "Enable Polish", "開啟潤飾"), fontWeight = FontWeight.Bold)
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
                                    text = tr("👂 离线听音（ASR）", "👂 On-Device Speech (ASR)", "👂 離線聽音（ASR）"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = TypeFreeEmerald.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = tr("开源免费", "Free OSS", "開源免費"),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TypeFreeEmerald,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (offlineConfig.asrSlotOn) {
                                    tr(
                                        "基于 sherpa-onnx 纯 CPU 引擎 · 关闭槽位开关将释放内存并保留模型文件",
                                        "Powered by sherpa-onnx CPU engine · Turning off unloads RAM while keeping downloaded files",
                                        "基於 sherpa-onnx 純 CPU 引擎 · 關閉槽位開關將釋放記憶體並保留模型檔案"
                                    )
                                } else {
                                    tr(
                                        "离线听音槽位已关闭 · 当前暂停参与路由，已选模型与磁盘权重已保留",
                                        "Local ASR slot is off · Paused from routing; selected model and disk files are preserved",
                                        "離線聽音槽位已關閉 · 目前暫停參與路由，已選模型與磁碟權重已保留"
                                    )
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
                                    text = tr(
                                        "小巧极速 30–230MB ($compactCount)",
                                        "Compact 30–230MB ($compactCount)",
                                        "小巧極速 30–230MB ($compactCount)"
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                                    text = tr(
                                        "大体积高精 400MB–1GB ($largeCount)",
                                        "Accurate 400MB–1GB ($largeCount)",
                                        "大體積高精 400MB–1GB ($largeCount)"
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                                val disp = offlineModelDisplayName(model, appLang)
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
                                    AppStrings.pick(
                                        appLang,
                                        "✓ 已启用「$disp」（其余已下载模型自动保留待用）",
                                        "✓ Enabled \"$disp\" (other downloaded models stay ready on disk)",
                                        "✓ 已啟用「$disp」（其餘已下載模型自動保留待用）"
                                    ),
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
                                text = tr("🧠 离线文本大脑（SLM）", "🧠 On-Device Text Brain (SLM)", "🧠 離線文字大腦（SLM）"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = tr("端侧微调占位", "Optional Local SLM", "端側微調佔位"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = tr(
                                "当前为 TypeFree 公开开源构建：你可以在上方自由按需下载全部开源离线语音（ASR）模型，搭配任意云端 BYOK 文本模型混搭润色，或免 Key 直接输出原始转写稿。",
                                "You are running the TypeFree Open-Source build: download any open-source local ASR model above and pair it with your own Cloud BYOK LLM, or output raw transcripts with zero API keys.",
                                "目前為 TypeFree 公開開源建置：你可以在上方自由按需下載全部開源離線語音（ASR）模型，搭配任意雲端 BYOK 文字模型混搭潤飾，或免 Key 直接輸出原始轉寫稿。"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                        Text(
                            text = tr(
                                "自研微调端侧润色/补全/改写大脑（Qwen3.5 0.8B / 2B / 4B）归独立商业 Pro 构建提供。",
                                "Fine-tuned on-device Qwen3.5 (0.8B / 2B / 4B) polish, completion, and rewrite models are provided in the commercial Pro build.",
                                "自研微調端側潤飾/補全/改寫大腦（Qwen3.5 0.8B / 2B / 4B）由獨立商業 Pro 建置提供。"
                            ),
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
                                        text = tr("🧠 离线文本大脑（SLM）", "🧠 On-Device Text Brain (SLM)", "🧠 離線文字大腦（SLM）"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = tr("端侧微调", "Fine-Tuned", "端側微調"),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = if (offlineConfig.slmSlotOn) {
                                        tr(
                                            "按主接管 / 断网兜底能力矩阵路由，不静默降级你已配好的云端专用军师",
                                            "Routes by Primary / Offline Fallback capability matrix without silently downgrading your dedicated Cloud Copilot",
                                            "按主接管 / 斷網兜底能力矩陣路由，不靜默降級你已配好的雲端專用軍師"
                                        )
                                    } else {
                                        tr(
                                            "离线大脑槽位已关闭 · 文本任务将交由云端或直出，权重文件保留",
                                            "Local Brain slot is off · Text tasks use Cloud or raw output; model files remain on disk",
                                            "離線大腦槽位已關閉 · 文字任務將交由雲端或直出，權重檔案保留"
                                        )
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
                                tr(
                                    "与当前离线听音组合将自动启用串行调度（先卸听音再润色），防止内存峰值过高",
                                    "Uses serial scheduling with your current ASR model (unloads ASR before polishing) to prevent RAM spikes",
                                    "與目前離線聽音組合將自動啟用串列排程（先卸聽音再潤飾），防止記憶體峰值過高"
                                )
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
                                    val disp = offlineModelDisplayName(model, appLang)
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
                                        AppStrings.pick(
                                            appLang,
                                            "✓ 已启用「$disp」（其余已下载大脑自动保留待用）",
                                            "✓ Enabled \"$disp\" (other downloaded models stay ready on disk)",
                                            "✓ 已啟用「$disp」（其餘已下載大腦自動保留待用）"
                                        ),
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
            val storageText = formatByteSize(storageBytes)
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
                            text = tr("本机离线权重存储", "Local Model Storage", "本機離線權重儲存"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr(
                                "当前占用 $storageText · 存放于应用私有不备份目录",
                                "Using $storageText · Stored in private non-backup app directory",
                                "目前佔用 $storageText · 存放於應用私有不備份目錄"
                            ),
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
                            text = tr("一键清理全部", "Clear All", "一鍵清理全部"),
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
        OfflineMode.OFF to tr("关闭", "Off", "關閉"),
        OfflineMode.HYBRID to if (compact) {
            tr("混合", "Hybrid", "混合")
        } else {
            tr("混合（推荐）", "Hybrid (Rec.)", "混合（推薦）")
        },
        OfflineMode.OFFLINE_ONLY to if (compact) {
            tr("纯离线", "Offline", "純離線")
        } else {
            tr("纯离线锁", "Offline Lock", "純離線鎖")
        }
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
    val appLang = LocalAppLanguage.current
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
                        text = offlineModelDisplayName(model, appLang),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = offlineModelSubtitle(model, appLang),
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
                            (if (model.isDefaultRecommended) {
                                tr("默认推荐", "Default Pick", "預設推薦")
                            } else {
                                tr("推荐", "Recommended", "推薦")
                            }) to TypeFreeEmerald
                        RamCompatibility.TIGHT -> tr("偏紧", "Tight RAM", "偏緊") to TypeFreeAmber
                        RamCompatibility.RISK_INSUFFICIENT -> tr("内存风险", "RAM Risk", "記憶體風險") to TypeFreeRose
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
                        text = if (model.supportsTrueStreaming) {
                            tr("真逐字流式", "Live Streaming", "真逐字串流")
                        } else {
                            tr("准流式 · VAD 分句", "Near-Live · VAD", "準串流 · VAD 分句")
                        },
                        accent = if (model.supportsTrueStreaming) TypeFreeEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ModelTagChip(
                        text = if (model.supportsNativeHotwords) {
                            tr("原生热词偏置", "Native Hotwords", "原生熱詞偏置")
                        } else {
                            tr("词典后处理纠错", "Dict Post-Fix", "詞典後處理糾錯")
                        },
                        accent = if (model.supportsNativeHotwords) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (model.isPendingVerification) {
                        ModelTagChip(
                            text = tr("待真机验证", "Pending Device Test", "待實機驗證"),
                            accent = TypeFreeAmber
                        )
                    }
                } else {
                    if (model.primaryTasks.isNotEmpty()) {
                        val primaryText = model.primaryTasks.joinToString(" / ") { taskShortName(it, appLang) }
                        ModelTagChip(
                            text = tr("主接管：$primaryText", "Primary: $primaryText", "主接管：$primaryText"),
                            accent = TypeFreeEmerald
                        )
                    }
                    if (model.fallbackTasks.isNotEmpty()) {
                        val fallbackText = model.fallbackTasks.joinToString(" / ") { taskShortName(it, appLang) }
                        ModelTagChip(
                            text = tr("断网兜底：$fallbackText", "Offline Fallback: $fallbackText", "斷網兜底：$fallbackText"),
                            accent = TypeFreeAmber
                        )
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
                val dlNow = formatByteSize(record.downloadedBytes)
                val dlTotal = formatByteSize(record.totalBytes)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (status == LocalModelInstallStatus.VERIFYING) {
                                tr(
                                    "正在 SHA-256 校验与短推理探活…",
                                    "Verifying SHA-256 & running health probe…",
                                    "正在 SHA-256 校驗與短推理探活…"
                                )
                            } else {
                                tr(
                                    "正在下载… $dlNow / $dlTotal",
                                    "Downloading… $dlNow / $dlTotal",
                                    "正在下載… $dlNow / $dlTotal"
                                )
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
                                text = if (isEffective) {
                                    tr("已启用 · 生效中", "Active · In Use", "已啟用 · 生效中")
                                } else {
                                    tr("已启用", "Selected", "已啟用")
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TypeFreeEmerald
                            )
                        }
                    }
                } else {
                    Text(
                        text = when (status) {
                            LocalModelInstallStatus.INSTALLED -> tr("已下载（未选中）", "Downloaded (Idle)", "已下載（未選取）")
                            LocalModelInstallStatus.DOWNLOADING -> tr("下载中…", "Downloading…", "下載中…")
                            LocalModelInstallStatus.PAUSED -> tr("已暂停（可断点续传）", "Paused (Resumable)", "已暫停（可斷點續傳）")
                            LocalModelInstallStatus.VERIFYING -> tr("校验中…", "Verifying…", "校驗中…")
                            LocalModelInstallStatus.CORRUPTED -> tr("权重异常", "Corrupted Weights", "權重異常")
                            LocalModelInstallStatus.UNSUPPORTED_ON_DEVICE -> tr("本机探活未通过", "Probe Failed on Device", "本機探活未通過")
                            else -> tr(
                                "约需 ${model.approxRamMb}MB 运行内存",
                                "~${model.approxRamMb} MB RAM",
                                "約需 ${model.approxRamMb}MB 執行記憶體"
                            )
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
                                Text(
                                    tr("设为启用", "Set Active", "設為啟用"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (onImportLocalGguf != null) {
                            TextButton(
                                onClick = onImportLocalGguf,
                                enabled = !isBusy,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = tr("导入 .gguf", "Import .gguf", "匯入 .gguf"),
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
                                text = tr("卸载", "Uninstall", "解除安裝"),
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
                                text = tr("取消", "Cancel", "取消"),
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
                                text = if (isDownloading) {
                                    tr("导入中…", "Importing…", "匯入中…")
                                } else {
                                    tr("导入本机 .gguf", "Import .gguf", "匯入本機 .gguf")
                                },
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
                                    text = tr("导入 .gguf", "Import .gguf", "匯入 .gguf"),
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
                                    isDownloading -> tr("处理中…", "Working…", "處理中…")
                                    status == LocalModelInstallStatus.PAUSED -> tr("继续下载", "Resume", "繼續下載")
                                    status == LocalModelInstallStatus.CORRUPTED ||
                                        status == LocalModelInstallStatus.UNSUPPORTED_ON_DEVICE -> tr("重新下载", "Redownload", "重新下載")
                                    else -> tr("下载", "Download", "下載")
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

internal fun offlineModelDisplayName(model: LocalModelDescriptor, lang: AppLang): String {
    return when (model.id) {
        OfflineModelCatalog.ID_ASR_ZIPFORMER_ZH_14M -> AppStrings.pick(
            lang,
            "极致迷你流式（Zipformer 14M）",
            "Ultra-Mini Streaming (Zipformer 14M)",
            "極致迷你串流（Zipformer 14M）"
        )
        OfflineModelCatalog.ID_ASR_PARAFORMER_ZH_SMALL -> AppStrings.pick(
            lang,
            "超轻中文（Paraformer Small）",
            "Ultra-Light Chinese (Paraformer Small)",
            "超輕中文（Paraformer Small）"
        )
        OfflineModelCatalog.ID_ASR_ZIPFORMER_BILINGUAL -> AppStrings.pick(
            lang,
            "双语流式热词版（Zipformer 中英）",
            "Bilingual Hotwords (Zipformer ZH-EN)",
            "雙語串流熱詞版（Zipformer 中英）"
        )
        OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL -> AppStrings.pick(
            lang,
            "默认均衡推荐（SenseVoiceSmall）",
            "Balanced Default (SenseVoiceSmall)",
            "預設均衡推薦（SenseVoiceSmall）"
        )
        OfflineModelCatalog.ID_ASR_STREAMING_PARAFORMER_ZH_EN -> AppStrings.pick(
            lang,
            "双语流式（Paraformer 中英）",
            "Bilingual Streaming (Paraformer ZH-EN)",
            "雙語串流（Paraformer 中英）"
        )
        OfflineModelCatalog.ID_ASR_FIRERED2_CTC_ZH_EN -> AppStrings.pick(
            lang,
            "方言口音增强（FireRedASR2-CTC）",
            "Accent & Dialect Boost (FireRedASR2-CTC)",
            "方言口音增強（FireRedASR2-CTC）"
        )
        OfflineModelCatalog.ID_ASR_FUNASR_NANO -> AppStrings.pick(
            lang,
            "精准多方言大模型（Fun-ASR-Nano）",
            "Multi-Dialect Accurate (Fun-ASR-Nano)",
            "精準多方言大模型（Fun-ASR-Nano）"
        )
        OfflineModelCatalog.ID_ASR_QWEN3_ASR_0_6B -> AppStrings.pick(
            lang,
            "千问语音大模型（Qwen3-ASR-0.6B）",
            "Qwen Speech Model (Qwen3-ASR-0.6B)",
            "千問語音大模型（Qwen3-ASR-0.6B）"
        )
        OfflineModelCatalog.ID_SLM_CORE_0_8B -> AppStrings.pick(
            lang,
            "轻巧净写版（0.8B）",
            "Compact Clean-Write (0.8B)",
            "輕巧淨寫版（0.8B）"
        )
        OfflineModelCatalog.ID_SLM_PLUS_2B -> AppStrings.pick(
            lang,
            "均衡三件套版（2B）",
            "Balanced Suite (2B)",
            "均衡三件套版（2B）"
        )
        OfflineModelCatalog.ID_SLM_PRO_4B -> AppStrings.pick(
            lang,
            "旗舰全能版（4B）",
            "Flagship All-Rounder (4B)",
            "旗艦全能版（4B）"
        )
        else -> model.displayName
    }
}

private fun offlineModelSubtitle(model: LocalModelDescriptor, lang: AppLang): String {
    return when (model.id) {
        OfflineModelCatalog.ID_ASR_ZIPFORMER_ZH_14M -> AppStrings.pick(
            lang,
            "体积最小、几乎零内存压力；纯中文真逐字流式与原生热词偏置，适合 4GB 老机",
            "Smallest footprint with near-zero RAM load; live Chinese streaming with native hotword biasing for 4 GB phones",
            "體積最小、幾乎零記憶體壓力；純中文真逐字串流與原生熱詞偏置，適合 4GB 舊機"
        )
        OfflineModelCatalog.ID_ASR_PARAFORMER_ZH_SMALL -> AppStrings.pick(
            lang,
            "单文件约 78MB、中文准流式转写稳健，4–6GB 机型极低负担",
            "~78 MB single-archive model with reliable Chinese near-live transcription for 4–6 GB devices",
            "單檔案約 78MB、中文準串流轉寫穩健，4–6GB 機型極低負擔"
        )
        OfflineModelCatalog.ID_ASR_ZIPFORMER_BILINGUAL -> AppStrings.pick(
            lang,
            "原生支持解码期热词偏置的中英双语真流式模型，边说边出字、术语命中高",
            "True streaming Chinese-English model with decoding-time hotword biasing and strong technical term accuracy",
            "原生支援解碼期熱詞偏置的中英雙語真串流模型，邊說邊出字、術語命中高"
        )
        OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL -> AppStrings.pick(
            lang,
            "6GB/8GB 默认推荐：非自回归极速，支持中/粤/英/日/韩多语与自动标点",
            "Default pick for 6/8 GB RAM: ultra-fast non-autoregressive ASR supporting ZH/YUE/EN/JA/KO with auto punctuation",
            "6GB/8GB 預設推薦：非自迴歸極速，支援中/粵/英/日/韓多語與自動標點"
        )
        OfflineModelCatalog.ID_ASR_STREAMING_PARAFORMER_ZH_EN -> AppStrings.pick(
            lang,
            "中英混读真逐字流式体验顺滑（官方完整流式包，含中英双语权重）",
            "Smooth token-by-token live streaming for mixed Chinese-English speech (full bilingual package)",
            "中英混讀真逐字串流體驗順滑（官方完整串流包，含中英雙語權重）"
        )
        OfflineModelCatalog.ID_ASR_FIRERED2_CTC_ZH_EN -> AppStrings.pick(
            lang,
            "非自回归 CTC 架构，中英与方言口音识别强，适合 8GB+ 机型",
            "Non-autoregressive CTC architecture with strong Chinese-English & regional accent recognition for 8 GB+ devices",
            "非自迴歸 CTC 架構，中英與方言口音識別強，適合 8GB+ 機型"
        )
        OfflineModelCatalog.ID_ASR_FUNASR_NANO -> AppStrings.pick(
            lang,
            "12GB+ 旗舰推荐（8GB 偏紧）：覆盖 7 大方言组与 26 种口音及上下文标点",
            "Recommended for 12 GB+ flagships (tight on 8 GB): covers 7 dialect groups, 26 accents, and context punctuation",
            "12GB+ 旗艦推薦（8GB 偏緊）：涵蓋 7 大方言組與 26 種口音及上下文標點"
        )
        OfflineModelCatalog.ID_ASR_QWEN3_ASR_0_6B -> AppStrings.pick(
            lang,
            "复杂长句与中英混读语义理解强，仅推荐 12GB+ 旗舰机尝鲜",
            "Strong semantic understanding on complex long sentences and mixed ZH-EN speech; for 12 GB+ flagships",
            "複雜長句與中英混讀語義理解強，僅推薦 12GB+ 旗艦機嚐鮮"
        )
        OfflineModelCatalog.ID_SLM_CORE_0_8B -> AppStrings.pick(
            lang,
            "Qwen3.5-0.8B Q4_K_M：免费端侧净写与整句补全，断网兜底选中改写与润色，6GB 机型友好",
            "Qwen3.5-0.8B Q4_K_M: Free on-device dictation cleanup & sentence completion, offline fallback for edit/polish (6 GB+ friendly)",
            "Qwen3.5-0.8B Q4_K_M：免費端側淨寫與整句補全，斷網兜底選取改寫與潤飾，6GB 機型友善"
        )
        OfflineModelCatalog.ID_SLM_PLUS_2B -> AppStrings.pick(
            lang,
            "Qwen3.5-2B Q4_K_M：主接管净写/补全/选中改写，断网兜底翻译与帮写",
            "Qwen3.5-2B Q4_K_M: Primary engine for clean dictate, completion & voice edit; offline fallback for translate & help write",
            "Qwen3.5-2B Q4_K_M：主接管淨寫/補全/選取改寫，斷網兜底翻譯與幫寫"
        )
        OfflineModelCatalog.ID_SLM_PRO_4B -> AppStrings.pick(
            lang,
            "Qwen3.5-4B Q4_K_M：全能离线大脑，断网兜底翻译/帮写/离线军师（纯 OCR 文本）",
            "Qwen3.5-4B Q4_K_M: Full-power local brain with offline fallback for translate, help write & Offline Copilot (OCR text)",
            "Qwen3.5-4B Q4_K_M：全能離線大腦，斷網兜底翻譯/幫寫/離線軍師（純 OCR 文字）"
        )
        else -> model.subtitle
    }
}

private fun ramTierLabel(tier: RamTier, lang: AppLang): String = when (tier) {
    RamTier.LOW_6GB -> AppStrings.pick(lang, "4–6GB 档", "4–6 GB Tier", "4–6GB 檔")
    RamTier.MID_8GB -> AppStrings.pick(lang, "8GB 档", "8 GB Tier", "8GB 檔")
    RamTier.HIGH_12GB_PLUS -> AppStrings.pick(lang, "12GB+ 档", "12 GB+ Tier", "12GB+ 檔")
}

private fun taskShortName(task: AiTask, lang: AppLang): String = when (task) {
    AiTask.DICTATE -> AppStrings.pick(lang, "净写", "Clean Dictate", "淨寫")
    AiTask.SENTENCE_COMPLETION -> AppStrings.pick(lang, "整句补全", "Completion", "整句補全")
    AiTask.SPEAK_TO_EDIT -> AppStrings.pick(lang, "选中改写", "Voice Edit", "選取改寫")
    AiTask.TEXT_TRANSFORM -> AppStrings.pick(lang, "文本润色", "Polish", "文本潤飾")
    AiTask.TRANSLATE -> AppStrings.pick(lang, "翻译", "Translate", "翻譯")
    AiTask.HELP_ME_WRITE -> AppStrings.pick(lang, "帮我写", "Help Write", "幫我寫")
    AiTask.JEV_COPILOT -> AppStrings.pick(lang, "离线军师(纯文本)", "Offline Copilot (Text)", "離線軍師(純文字)")
    AiTask.BUBBLE_COPILOT -> AppStrings.pick(lang, "悬浮军师", "Bubble Copilot", "懸浮軍師")
    AiTask.ASK_AI_CARD -> AppStrings.pick(lang, "问AI", "Ask AI", "問AI")
    AiTask.NOTE_TRANSCRIBE -> AppStrings.pick(lang, "笔记转写", "Note ASR", "筆記轉寫")
}

private fun offlineModeLabel(mode: OfflineMode, lang: AppLang): String = when (mode) {
    OfflineMode.OFF -> AppStrings.pick(lang, "关闭", "Off", "關閉")
    OfflineMode.HYBRID -> AppStrings.pick(lang, "混合模式", "Hybrid", "混合模式")
    OfflineMode.OFFLINE_ONLY -> AppStrings.pick(lang, "纯离线锁", "Offline Lock", "純離線鎖")
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
