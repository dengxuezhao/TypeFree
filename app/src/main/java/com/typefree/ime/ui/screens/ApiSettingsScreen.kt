package com.typefree.ime.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.typefree.core.offline.AiTask
import com.typefree.core.offline.AsrRoute
import com.typefree.core.offline.ExecutionSource
import com.typefree.core.offline.LocalModelInstallStatus
import com.typefree.core.offline.LocalModelRegistrySnapshot
import com.typefree.core.offline.LocalModelSlot
import com.typefree.core.offline.OfflineMode
import com.typefree.core.offline.OfflineModelCatalog
import com.typefree.core.offline.ReadinessState
import com.typefree.core.offline.RoutePlan
import com.typefree.core.offline.TextRoute
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.localai.LocalInferenceEngineRegistry
import com.typefree.ime.localai.ModelDownloadWorker
import com.typefree.ime.data.ApiPreset
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.AppToneRule
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.DictionarySource
import com.typefree.ime.data.DiscoveredModelItem
import com.typefree.ime.ime.jev.memory.AndroidKeystoreSaltProvider
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.ModelCapabilitySlot
import com.typefree.ime.data.ModelProbeResult
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.OfflineRouteBridge
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.ProviderCredential
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SttConfig
import com.typefree.ime.data.SttProtocol
import com.typefree.ime.data.ThemePreference
import com.typefree.ime.data.ThinkingMode
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.UnifiedProviderCatalog
import com.typefree.ime.data.UnifiedProviderSpec
import com.typefree.ime.data.stats.FullBackupMergeSummary
import com.typefree.ime.data.stats.FullBackupOptions
import com.typefree.ime.data.stats.TokenFeatureKeys
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.EndpointResolver
import com.typefree.ime.network.PipelineExecutionResult
import com.typefree.ime.network.asr.VolcengineSaucAsrClient
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.components.rememberLocalModelRegistrySnapshot
import com.typefree.ime.ui.screens.insights.TokenUsageSummaryJumpStrip
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val DEFAULT_LIVE_TEST_INPUT =
    "嗯那个明天下午两点，哦不对改成三点半，跟王总对一下太普利斯API接口方案"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ApiSettingsScreen(
    repository: AppRepository,
    aiEngine: AiPipelineEngine,
    showScreenHeader: Boolean = true,
    initialOpenOfflineModels: Boolean = false,
    onConsumeOpenOfflineModels: () -> Unit = {},
    onOpenInsights: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState()
    val registrySnapshot = rememberLocalModelRegistrySnapshot()

    var showOfflineModelsScreen by rememberSaveable { mutableStateOf(initialOpenOfflineModels) }
    LaunchedEffect(initialOpenOfflineModels) {
        if (initialOpenOfflineModels) {
            showOfflineModelsScreen = true
            onConsumeOpenOfflineModels()
        }
    }

    if (showOfflineModelsScreen) {
        OfflineModelsScreen(
            repository = repository,
            onBack = { showOfflineModelsScreen = false }
        )
        return
    }

    val hydratedVault = remember(settings) {
        UnifiedProviderCatalog.hydrateVaultFromSettings(settings)
    }

    val activePrimaryProviderId = remember(settings.pipelineMode, settings.sttConfig, settings.multimodalConfig) {
        if (settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT) {
            UnifiedProviderCatalog.inferMultimodalProviderId(settings.multimodalConfig)
        } else {
            UnifiedProviderCatalog.inferSttProviderId(settings.sttConfig)
        }
    }

    var selectedVaultProviderId by rememberSaveable {
        mutableStateOf(
            if (settings.offlineConfig.mode != OfflineMode.OFF) {
                ID_LOCAL_OFFLINE_PROVIDER
            } else {
                activePrimaryProviderId
            }
        )
    }

    // Collapsible Advanced Drawers (collapsed by default for a clean, zero-clutter studio)
    var showMixRoutingDrawer by rememberSaveable { mutableStateOf(false) }
    var showAdvancedDrawer by rememberSaveable { mutableStateOf(false) }
    var showTesterDrawer by rememberSaveable { mutableStateOf(false) }
    var showBackupDrawer by rememberSaveable { mutableStateOf(false) }

    // Live API Tester state
    var testInput by remember { mutableStateOf(DEFAULT_LIVE_TEST_INPUT) }
    var isTestingApi by remember { mutableStateOf(false) }
    var testSuccessResult by remember { mutableStateOf<PipelineExecutionResult?>(null) }
    var testErrorMessage by remember { mutableStateOf<String?>(null) }
    var copiedTestResult by remember { mutableStateOf(false) }
    var copiedDiagLog by remember { mutableStateOf(false) }

    // Backup / Import status banner & full backup module toggles
    val historyItems by repository.history.collectAsState()
    val clipboardItems by repository.clipboardItems.collectAsState()
    val statsRevision by repository.dailyStatsRevision.collectAsState()
    val dailyStatsDaysCount = remember(statsRevision) {
        repository.dailyStatsStore.listAllRecords().size
    }
    val pinnedClipboardCount = remember(clipboardItems) {
        clipboardItems.count { it.isPinned }
    }

    var backupStatusMsg by remember { mutableStateOf<String?>(null) }
    var backupIsError by remember { mutableStateOf(false) }
    var isBackupBusy by remember { mutableStateOf(false) }
    var includeEncryptedJevMemory by remember { mutableStateOf(false) }
    var includeHistoryBackup by remember { mutableStateOf(true) }
    var includeDailyStatsBackup by remember { mutableStateOf(true) }
    var includePredictionsBackup by remember { mutableStateOf(true) }
    var includePinnedClipboardBackup by remember { mutableStateOf(true) }
    var backupMemoryPassphrase by remember { mutableStateOf("") }
    var showImportMemorySkipAction by remember { mutableStateOf(false) }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }

    fun currentFullBackupOptions(includeSecrets: Boolean = true): FullBackupOptions {
        return FullBackupOptions(
            includeSecrets = includeSecrets,
            includeJevMemory = includeSecrets && includeEncryptedJevMemory,
            includeHistory = includeSecrets && includeHistoryBackup,
            includeDailyStats = includeSecrets && includeDailyStatsBackup,
            includePredictions = includeSecrets && includePredictionsBackup,
            includePinnedClipboard = includeSecrets && includePinnedClipboardBackup
        )
    }

    fun formatMergeSuccessMessage(
        outcome: ApiConfigBackupHelper.FullBackupImportOutcome,
        skippedEncryptedMemory: Boolean
    ): String {
        val parts = mutableListOf<String>()
        val merge: FullBackupMergeSummary = outcome.mergeSummary
        if (merge.mergedDailyStatsDays > 0) parts.add("每日统计 ${merge.mergedDailyStatsDays} 天")
        if (merge.mergedHistoryCount > 0) parts.add("历史 +${merge.mergedHistoryCount} 条")
        val predTotal = merge.mergedBigramCount + merge.mergedTrigramCount
        if (predTotal > 0) parts.add("L2 连词库 $predTotal 条")
        if (merge.mergedPinnedClipboardCount > 0) parts.add("置顶剪贴板 +${merge.mergedPinnedClipboardCount} 条")
        outcome.memorySummary?.let {
            parts.add("军师记忆 ${it.importedSessions} 会话 / ${it.mergedBuckets} 风格组")
        }
        val extra = if (parts.isNotEmpty()) "；无损合并：${parts.joinToString(" · ")}" else ""
        val skipNote = if (skippedEncryptedMemory) "（已按要求跳过加密军师记忆）" else ""
        return "✓ 全量配置与数据导入成功！已同步密钥库、短语与个人词典$extra$skipNote"
    }

    fun executeImportJsonPayload(rawJson: String, skipMemoryIfNoPassphrase: Boolean) {
        if (rawJson.isBlank()) {
            backupIsError = true
            backupStatusMsg = "✕ 未检测到可导入的备份 JSON 内容"
            return
        }
        val hasEncryptedMemory = ApiConfigBackupHelper.hasEncryptedJevMemoryArchive(rawJson)
        if (hasEncryptedMemory && !skipMemoryIfNoPassphrase && backupMemoryPassphrase.isBlank()) {
            pendingImportJson = rawJson
            showImportMemorySkipAction = true
            backupIsError = true
            backupStatusMsg = "检测到备份内含加密的军师记忆：请输入密码后点击「解锁并导入」，或点击「跳过记忆仅导其余数据」"
            return
        }
        coroutineScope.launch {
            isBackupBusy = true
            val passChars = if (skipMemoryIfNoPassphrase) null else backupMemoryPassphrase.takeIf { it.isNotBlank() }?.toCharArray()
            val res = withContext(Dispatchers.IO) {
                ApiConfigBackupHelper.importFullBackupJson(
                    rawJson = rawJson,
                    existingSettings = settings,
                    repository = repository,
                    memoryRepository = repository.jevMemoryRepository,
                    memoryPassphrase = passChars,
                    adoptSaltProvider = repository.jevMemorySaltProvider as? AndroidKeystoreSaltProvider,
                    skipMemoryIfNoPassphrase = skipMemoryIfNoPassphrase,
                    wipePassphraseAfterUse = true
                )
            }
            isBackupBusy = false
            res.onSuccess { outcome ->
                repository.updateSettings { outcome.settings }
                backupMemoryPassphrase = ""
                pendingImportJson = null
                showImportMemorySkipAction = false
                backupIsError = false
                backupStatusMsg = formatMergeSuccessMessage(
                    outcome = outcome,
                    skippedEncryptedMemory = skipMemoryIfNoPassphrase && hasEncryptedMemory
                )
            }.onFailure { err ->
                backupIsError = true
                backupStatusMsg = "✕ ${err.message ?: "导入备份失败"}"
            }
        }
    }

    val exportBackupFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isBackupBusy = true
            val options = currentFullBackupOptions(includeSecrets = true)
            val passChars = if (options.includeJevMemory) backupMemoryPassphrase.toCharArray() else null
            val writeRes = withContext(Dispatchers.IO) {
                runCatching {
                    val dailyStats = if (options.includeDailyStats) {
                        repository.dailyStatsStore.flushBlocking(1_000L)
                        repository.dailyStatsStore.listAllRecords()
                    } else {
                        emptyList()
                    }
                    val predictions = if (options.includePredictions) {
                        repository.exportPredictionsArchive()
                    } else {
                        com.typefree.ime.data.stats.PredictionBackupArchive()
                    }
                    val json = ApiConfigBackupHelper.exportFullBackupJson(
                        settings = settings,
                        options = options,
                        history = if (options.includeHistory) repository.history.value else emptyList(),
                        dailyStats = dailyStats,
                        predictions = predictions,
                        pinnedClipboard = if (options.includePinnedClipboard) repository.clipboardItems.value else emptyList(),
                        memoryRepository = repository.jevMemoryRepository,
                        memoryPassphrase = passChars,
                        wipePassphraseAfterUse = true
                    )
                    val bytes = json.toByteArray(Charsets.UTF_8)
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法写入所选文件")
                    out.use { it.write(bytes) }
                    bytes.size
                }
            }
            isBackupBusy = false
            if (options.includeJevMemory) {
                backupMemoryPassphrase = ""
            }
            writeRes.onSuccess { byteCount ->
                val kb = String.format(Locale.US, "%.1f KB", byteCount / 1024.0)
                backupIsError = false
                backupStatusMsg = "✓ 全量数据备份已导出为 .json 文件（$kb，包含所选数据与密钥库，请妥善保管）"
            }.onFailure { err ->
                backupIsError = true
                backupStatusMsg = "✕ 导出 .json 文件失败：${err.message ?: "未知错误"}"
            }
        }
    }

    val importBackupFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isBackupBusy = true
            val readRes = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法读取所选文件")
                    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            }
            isBackupBusy = false
            readRes.onSuccess { raw ->
                executeImportJsonPayload(rawJson = raw, skipMemoryIfNoPassphrase = false)
            }.onFailure { err ->
                backupIsError = true
                backupStatusMsg = "✕ 读取 .json 文件失败：${err.message ?: "未知错误"}"
            }
        }
    }

    // Universal Provider Model Discovery state (STT / LLM / Multimodal)
    var fetchingSlot by remember { mutableStateOf<ModelCapabilitySlot?>(null) }
    var modelFetchErrors by remember { mutableStateOf<Map<ModelCapabilitySlot, String>>(emptyMap()) }
    var pickerDialogSlot by remember { mutableStateOf<ModelCapabilitySlot?>(null) }
    var discoveredModels by remember { mutableStateOf<List<DiscoveredModelItem>>(emptyList()) }

    fun triggerModelDiscovery(slot: ModelCapabilitySlot) {
        val (baseUrl, apiKey, protocol) = when (slot) {
            ModelCapabilitySlot.STT -> Triple(
                settings.sttConfig.baseUrl,
                settings.sttConfig.apiKey,
                MultimodalProtocol.OPENAI_AUDIO_CHAT
            )
            ModelCapabilitySlot.LLM -> {
                val effKey = settings.llmConfig.apiKey.ifBlank {
                    if (settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS ||
                        EndpointResolver.isDeepSeekAsrEndpoint(settings.sttConfig.baseUrl)
                    ) settings.sttConfig.apiKey else ""
                }
                Triple(
                    settings.llmConfig.baseUrl,
                    effKey,
                    MultimodalProtocol.OPENAI_AUDIO_CHAT
                )
            }
            ModelCapabilitySlot.MULTIMODAL -> Triple(
                settings.multimodalConfig.baseUrl,
                settings.multimodalConfig.apiKey,
                settings.multimodalConfig.protocol
            )
            ModelCapabilitySlot.JEV_COPILOT -> {
                val effJev = aiEngine.resolveEffectiveJevLlmConfig(
                    settings.copy(jevUseDedicatedModel = true)
                ) ?: settings.jevLlmConfig
                Triple(
                    effJev.baseUrl,
                    effJev.apiKey,
                    MultimodalProtocol.OPENAI_AUDIO_CHAT
                )
            }
        }
        coroutineScope.launch {
            fetchingSlot = slot
            modelFetchErrors = modelFetchErrors - slot
            val res = aiEngine.fetchAvailableModels(
                slot = slot,
                baseUrl = baseUrl,
                apiKey = apiKey,
                protocol = protocol
            )
            fetchingSlot = null
            res.onSuccess { list ->
                discoveredModels = list
                pickerDialogSlot = slot
            }.onFailure { err ->
                modelFetchErrors = modelFetchErrors + (slot to (err.message ?: "拉取模型列表失败"))
            }
        }
    }

    val readiness = remember(settings, registrySnapshot) {
        SetupStatsHelper.resolveReadiness(settings, registrySnapshot)
    }
    val dictatePlan = remember(settings, registrySnapshot) {
        OfflineRouteBridge.resolvePlan(
            task = AiTask.DICTATE,
            settings = settings,
            registrySnapshot = registrySnapshot
        )
    }
    val isFullReady = readiness is ReadinessState.Ready

    pickerDialogSlot?.let { activeSlot ->
        val activeBaseUrl: String
        val activeApiKey: String
        val currentModel: String
        val activeProtocol: MultimodalProtocol
        val activeThinkingMode: ThinkingMode
        when (activeSlot) {
            ModelCapabilitySlot.STT -> {
                activeBaseUrl = settings.sttConfig.baseUrl
                activeApiKey = settings.sttConfig.apiKey
                currentModel = settings.sttConfig.model
                activeProtocol = MultimodalProtocol.OPENAI_AUDIO_CHAT
                activeThinkingMode = ThinkingMode.DISABLED
            }
            ModelCapabilitySlot.LLM -> {
                activeBaseUrl = settings.llmConfig.baseUrl
                activeApiKey = settings.llmConfig.apiKey.ifBlank {
                    if (settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS ||
                        EndpointResolver.isDeepSeekAsrEndpoint(settings.sttConfig.baseUrl)
                    ) settings.sttConfig.apiKey else ""
                }
                currentModel = settings.llmConfig.model
                activeProtocol = MultimodalProtocol.OPENAI_AUDIO_CHAT
                activeThinkingMode = settings.llmConfig.thinkingMode
            }
            ModelCapabilitySlot.MULTIMODAL -> {
                activeBaseUrl = settings.multimodalConfig.baseUrl
                activeApiKey = settings.multimodalConfig.apiKey
                currentModel = settings.multimodalConfig.model
                activeProtocol = settings.multimodalConfig.protocol
                activeThinkingMode = settings.multimodalConfig.thinkingMode
            }
            ModelCapabilitySlot.JEV_COPILOT -> {
                val effJev = aiEngine.resolveEffectiveJevLlmConfig(
                    settings.copy(jevUseDedicatedModel = true)
                ) ?: settings.jevLlmConfig
                activeBaseUrl = effJev.baseUrl
                activeApiKey = effJev.apiKey
                currentModel = settings.jevLlmConfig.model
                activeProtocol = MultimodalProtocol.OPENAI_AUDIO_CHAT
                activeThinkingMode = settings.jevLlmConfig.thinkingMode
            }
        }
        ProviderModelPickerDialog(
            slot = activeSlot,
            baseUrl = activeBaseUrl,
            apiKey = activeApiKey,
            currentModel = currentModel,
            protocol = activeProtocol,
            thinkingMode = activeThinkingMode,
            aiEngine = aiEngine,
            models = discoveredModels,
            isRefreshing = fetchingSlot == activeSlot,
            onRefresh = { triggerModelDiscovery(activeSlot) },
            onDismiss = { pickerDialogSlot = null },
            onSelectModel = { chosenId ->
                when (activeSlot) {
                    ModelCapabilitySlot.STT -> repository.updateSettings {
                        it.copy(sttConfig = it.sttConfig.copy(model = chosenId))
                    }
                    ModelCapabilitySlot.LLM -> repository.updateSettings {
                        it.copy(llmConfig = it.llmConfig.copy(model = chosenId))
                    }
                    ModelCapabilitySlot.MULTIMODAL -> repository.updateSettings {
                        it.copy(multimodalConfig = it.multimodalConfig.copy(model = chosenId))
                    }
                    ModelCapabilitySlot.JEV_COPILOT -> repository.updateSettings {
                        it.copy(jevLlmConfig = it.jevLlmConfig.copy(model = chosenId))
                    }
                }
                pickerDialogSlot = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (showScreenHeader) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "统一 API 服务商与模型路由",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "同一服务商只需填写一次 Key，一键切换 Google Gemini 0.5s 流式、DeepSeek 单 Key 方案、百炼、豆包或自由混搭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Top Readiness Summary Banner
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = if (isFullReady) {
                TypeFreeEmerald.copy(alpha = 0.08f)
            } else {
                TypeFreeAmber.copy(alpha = 0.10f)
            },
            border = BorderStroke(
                1.dp,
                if (isFullReady) TypeFreeEmerald.copy(alpha = 0.35f) else TypeFreeAmber.copy(alpha = 0.45f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isFullReady) TypeFreeEmerald else TypeFreeAmber)
                    )
                    Text(
                        text = when (readiness) {
                            is ReadinessState.Ready ->
                                "引擎就绪 · ${SetupStatsHelper.formatActivePipelineBadge(settings, registrySnapshot)}"
                            is ReadinessState.DegradedRawOnly ->
                                "仅转写 · ${readiness.badge.shortLabel}"
                            is ReadinessState.NotReady ->
                                "待配置 · ${readiness.reason}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = when {
                        dictatePlan.badge.isPureOffline -> "纯离线零出网"
                        dictatePlan.badge.isHybrid -> "端云混合路由"
                        else -> "本地加密直连"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Layer 1: Unified Provider & Local Offline Vault Card (Local Offline as 1st Provider + 1-Tap Pure Offline)
        UnifiedProviderVaultCard(
            settings = settings,
            registrySnapshot = registrySnapshot,
            vault = hydratedVault,
            selectedProviderId = selectedVaultProviderId,
            activePrimaryProviderId = activePrimaryProviderId,
            activeLlmProviderId = UnifiedProviderCatalog.inferLlmProviderId(settings.llmConfig),
            isLlmEnabled = settings.llmConfig.enabled,
            activeJevProviderId = UnifiedProviderCatalog.inferLlmProviderId(settings.jevLlmConfig),
            isJevDedicatedEnabled = settings.jevUseDedicatedModel,
            hasConfiguredStt = UnifiedProviderCatalog.hasConfiguredSttCredential(settings.sttConfig),
            onSelectProvider = { selectedVaultProviderId = it },
            onUpdateCredential = { updatedCred ->
                repository.updateSettings { curr ->
                    UnifiedProviderCatalog.updateProviderCredential(curr, updatedCred)
                }
            },
            onApplyQuickSetup = { providerId, keepExistingThirdPartyStt ->
                val wasOffline = settings.offlineConfig.mode != OfflineMode.OFF
                repository.updateSettings { curr ->
                    val next = UnifiedProviderCatalog.applyQuickProviderSetup(
                        settings = curr,
                        providerId = providerId,
                        keepExistingThirdPartyStt = keepExistingThirdPartyStt
                    )
                    if (providerId == UnifiedProviderCatalog.ID_DEEPSEEK && keepExistingThirdPartyStt && curr.offlineConfig.asrSlotOn) {
                        next.copy(
                            offlineConfig = curr.offlineConfig.copy(
                                mode = OfflineMode.HYBRID,
                                slmSlotOn = false
                            )
                        )
                    } else {
                        next.copy(
                            offlineConfig = curr.offlineConfig.copy(
                                mode = OfflineMode.OFF,
                                asrSlotOn = false,
                                slmSlotOn = false
                            )
                        )
                    }
                }
                if (wasOffline && !(providerId == UnifiedProviderCatalog.ID_DEEPSEEK && keepExistingThirdPartyStt && settings.offlineConfig.asrSlotOn)) {
                    coroutineScope.launch {
                        runCatching { TypeFreeApp.instance.localAiClient.unloadAll() }
                    }
                }
                testSuccessResult = null
                testErrorMessage = null
                val spec = UnifiedProviderCatalog.specFor(providerId)
                Toast.makeText(
                    context,
                    when {
                        providerId == UnifiedProviderCatalog.ID_DEEPSEEK && keepExistingThirdPartyStt ->
                            "✓ 已将 DeepSeek 设为文本大脑（保留当前听音引擎）"
                        providerId == UnifiedProviderCatalog.ID_DEEPSEEK ->
                            "✓ 已启用 DeepSeek 单 Key 方案（语音识别 + 润色与军师）"
                        else ->
                            "🚀 已切换主力方案：${spec.name}"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            },
            onActivatePureOffline = {
                val installedAsr = OfflineModelCatalog.asrModels.firstOrNull { registrySnapshot.isInstalled(it.id) }
                val installedSlm = OfflineModelCatalog.slmModels.firstOrNull { registrySnapshot.isInstalled(it.id) }
                if (installedAsr == null) {
                    Toast.makeText(context, "请先下载至少一个本机听音模型（推荐 SenseVoiceSmall）", Toast.LENGTH_SHORT).show()
                    showOfflineModelsScreen = true
                } else {
                    repository.updateSettings { curr ->
                        val nextAsrId = curr.offlineConfig.selectedAsrModelId
                            ?.takeIf { registrySnapshot.isInstalled(it) }
                            ?: installedAsr.id
                        val nextSlmId = curr.offlineConfig.selectedSlmModelId
                            ?.takeIf { registrySnapshot.isInstalled(it) }
                            ?: installedSlm?.id
                        val enableSlm = nextSlmId != null && registrySnapshot.slmEngineAvailable
                        curr.copy(
                            pipelineMode = PipelineMode.DUAL_STAGE,
                            llmConfig = curr.llmConfig.copy(enabled = enableSlm),
                            offlineConfig = curr.offlineConfig.copy(
                                mode = OfflineMode.OFFLINE_ONLY,
                                asrSlotOn = true,
                                slmSlotOn = enableSlm,
                                selectedAsrModelId = nextAsrId,
                                selectedSlmModelId = nextSlmId
                            )
                        )
                    }
                    testSuccessResult = null
                    testErrorMessage = null
                    Toast.makeText(
                        context,
                        if (installedSlm != null && registrySnapshot.slmEngineAvailable) {
                            "🔒 已切换纯离线零出网（本机听音 + 本机净写）"
                        } else {
                            "🔒 已切换纯离线零出网（本机听音直出）"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onUnlockPureOffline = {
                repository.updateOfflineConfig { curr ->
                    if (curr.asrSlotOn || curr.slmSlotOn) {
                        curr.copy(mode = OfflineMode.HYBRID)
                    } else {
                        curr.copy(mode = OfflineMode.OFF)
                    }
                }
                Toast.makeText(context, "已解除纯离线零出网锁（保留当前本机模型）", Toast.LENGTH_SHORT).show()
            },
            onOpenOfflineModels = { showOfflineModelsScreen = true },
            onPasteClipboard = { readFromClipboard(context) }
        )

        // Layer 2: Collapsible Workflow & Multi-Provider Mix-and-Match Drawer (collapsed by default)
        val activeLlmProviderId = remember(settings.llmConfig) {
            UnifiedProviderCatalog.inferLlmProviderId(settings.llmConfig)
        }
        val isMixedSetup = dictatePlan.badge.isHybrid ||
            settings.jevUseDedicatedModel ||
            (settings.pipelineMode == PipelineMode.DUAL_STAGE &&
                settings.llmConfig.enabled &&
                activePrimaryProviderId != activeLlmProviderId)
        val mixDrawerSubtitle = remember(settings, registrySnapshot, isMixedSetup) {
            val activeLabel = SetupStatsHelper.formatActivePipelineBadge(settings, registrySnapshot)
            if (isMixedSetup) {
                "混搭中 · 当前：$activeLabel · 点击调整多平台混搭"
            } else {
                "当前：$activeLabel · 点击自定义双阶段、多模态或军师独立模型"
            }
        }

        CollapsibleStudioDrawer(
            title = "🔀 进阶：工作流与多平台混搭",
            subtitle = mixDrawerSubtitle,
            expanded = showMixRoutingDrawer,
            onToggle = { showMixRoutingDrawer = !showMixRoutingDrawer }
        ) {
            UnifiedWorkflowRoutingCard(
                settings = settings,
                dictatePlan = dictatePlan,
                registrySnapshot = registrySnapshot,
                vault = hydratedVault,
                fetchingSlot = fetchingSlot,
                modelFetchErrors = modelFetchErrors,
                onSelectMode = { mode ->
                    repository.updateSettings { it.copy(pipelineMode = mode) }
                    testSuccessResult = null
                    testErrorMessage = null
                },
                onSelectLocalAsr = {
                    selectedVaultProviderId = ID_LOCAL_OFFLINE_PROVIDER
                    val installedAsr = OfflineModelCatalog.asrModels.firstOrNull { registrySnapshot.isInstalled(it.id) }
                    repository.updateOfflineConfig { curr ->
                        val nextAsrId = curr.selectedAsrModelId?.takeIf { registrySnapshot.isInstalled(it) }
                            ?: installedAsr?.id
                            ?: curr.selectedAsrModelId
                        curr.copy(
                            mode = if (curr.mode == OfflineMode.OFF) OfflineMode.HYBRID else curr.mode,
                            asrSlotOn = true,
                            selectedAsrModelId = nextAsrId
                        )
                    }
                },
                onSelectLocalAsrModel = { modelId ->
                    repository.updateOfflineConfig { curr ->
                        curr.copy(
                            mode = if (curr.mode == OfflineMode.OFF) OfflineMode.HYBRID else curr.mode,
                            asrSlotOn = true,
                            selectedAsrModelId = modelId
                        )
                    }
                },
                onSelectLocalSlm = {
                    selectedVaultProviderId = ID_LOCAL_OFFLINE_PROVIDER
                    val installedSlm = OfflineModelCatalog.slmModels.firstOrNull { registrySnapshot.isInstalled(it.id) }
                    repository.updateSettings { curr ->
                        val nextSlmId = curr.offlineConfig.selectedSlmModelId?.takeIf { registrySnapshot.isInstalled(it) }
                            ?: installedSlm?.id
                            ?: curr.offlineConfig.selectedSlmModelId
                        curr.copy(
                            llmConfig = curr.llmConfig.copy(enabled = true),
                            offlineConfig = curr.offlineConfig.copy(
                                mode = if (curr.offlineConfig.mode == OfflineMode.OFF) OfflineMode.HYBRID else curr.offlineConfig.mode,
                                slmSlotOn = true,
                                selectedSlmModelId = nextSlmId
                            )
                        )
                    }
                },
                onSelectLocalSlmModel = { modelId ->
                    repository.updateSettings { curr ->
                        curr.copy(
                            llmConfig = curr.llmConfig.copy(enabled = true),
                            offlineConfig = curr.offlineConfig.copy(
                                mode = if (curr.offlineConfig.mode == OfflineMode.OFF) OfflineMode.HYBRID else curr.offlineConfig.mode,
                                slmSlotOn = true,
                                selectedSlmModelId = modelId
                            )
                        )
                    }
                },
                onQuickDownloadLocalModel = { modelId ->
                    val workId = runCatching {
                        ModelDownloadWorker.enqueueDownload(context, modelId, allowDeleteExistingFirst = false)
                    }.getOrNull()
                    if (workId != null) {
                        Toast.makeText(context, "已开始后台下载，正在打开模型管理查看进度…", Toast.LENGTH_SHORT).show()
                    }
                    showOfflineModelsScreen = true
                },
                onOpenOfflineModels = { showOfflineModelsScreen = true },
                onSelectSttProvider = { pid ->
                    selectedVaultProviderId = pid
                    val wasLocked = settings.offlineConfig.mode == OfflineMode.OFFLINE_ONLY
                    val willDisableAllOffline = !settings.offlineConfig.slmSlotOn
                    repository.updateSettings { curr ->
                        val updated = UnifiedProviderCatalog.selectSttProvider(curr, pid)
                        val nextMode = when {
                            !curr.offlineConfig.slmSlotOn -> OfflineMode.OFF
                            curr.offlineConfig.mode == OfflineMode.OFFLINE_ONLY -> OfflineMode.HYBRID
                            else -> curr.offlineConfig.mode
                        }
                        updated.copy(
                            offlineConfig = curr.offlineConfig.copy(
                                mode = nextMode,
                                asrSlotOn = false
                            )
                        )
                    }
                    if (willDisableAllOffline) {
                        coroutineScope.launch {
                            runCatching { TypeFreeApp.instance.localAiClient.unloadAll() }
                        }
                    } else {
                        coroutineScope.launch {
                            runCatching { TypeFreeApp.instance.localAiClient.unloadSlot(LocalModelSlot.ASR) }
                        }
                    }
                    if (wasLocked) {
                        Toast.makeText(context, "已自动解除纯离线锁，切为端云混合/云端听音", Toast.LENGTH_SHORT).show()
                    }
                },
                onSelectLlmProvider = { pid ->
                    selectedVaultProviderId = pid
                    val wasLocked = settings.offlineConfig.mode == OfflineMode.OFFLINE_ONLY
                    val willDisableAllOffline = !settings.offlineConfig.asrSlotOn
                    repository.updateSettings { curr ->
                        val updated = UnifiedProviderCatalog.selectLlmProvider(curr, pid)
                        val nextMode = when {
                            !curr.offlineConfig.asrSlotOn -> OfflineMode.OFF
                            curr.offlineConfig.mode == OfflineMode.OFFLINE_ONLY -> OfflineMode.HYBRID
                            else -> curr.offlineConfig.mode
                        }
                        updated.copy(
                            offlineConfig = curr.offlineConfig.copy(
                                mode = nextMode,
                                slmSlotOn = false
                            )
                        )
                    }
                    if (willDisableAllOffline) {
                        coroutineScope.launch {
                            runCatching { TypeFreeApp.instance.localAiClient.unloadAll() }
                        }
                    } else {
                        coroutineScope.launch {
                            runCatching { TypeFreeApp.instance.localAiClient.unloadSlot(LocalModelSlot.SLM) }
                        }
                    }
                    if (wasLocked) {
                        Toast.makeText(context, "已自动解除纯离线锁，切为端云混合润色", Toast.LENGTH_SHORT).show()
                    }
                },
                onSelectMultimodalProvider = { pid ->
                    selectedVaultProviderId = pid
                    repository.updateSettings { UnifiedProviderCatalog.selectMultimodalProvider(it, pid) }
                },
                onToggleJevDedicated = { enabled ->
                    repository.updateSettings { it.copy(jevUseDedicatedModel = enabled) }
                },
                onSelectJevProvider = { pid ->
                    selectedVaultProviderId = pid
                    repository.updateSettings { UnifiedProviderCatalog.selectJevProvider(it, pid) }
                },
                onUpdateStt = { updated -> repository.updateSettings { it.copy(sttConfig = updated) } },
                onUpdateLlm = { updated -> repository.updateSettings { it.copy(llmConfig = updated) } },
                onUpdateMultimodal = { updated -> repository.updateSettings { it.copy(multimodalConfig = updated) } },
                onUpdateJev = { updated -> repository.updateSettings { it.copy(jevLlmConfig = updated) } },
                onFetchModels = ::triggerModelDiscovery
            )
        }

        // Layer 2.5: Compact 1-Line Token & Voice Duration Summary Jump Strip -> Unified InsightsScreen
        TokenUsageSummaryJumpStrip(
            repository = repository,
            onOpenInsights = onOpenInsights
        )

        // Layer 3A: Collapsible Advanced Tuning Drawer
        CollapsibleStudioDrawer(
            title = "⚙️ 高级协议与路由参数",
            subtitle = "思考模式开关、采样温度、自定义 Base URL 反代地址与火山 Resource ID",
            expanded = showAdvancedDrawer,
            onToggle = { showAdvancedDrawer = !showAdvancedDrawer }
        ) {
            AdvancedParametersDrawerContent(
                settings = settings,
                onUpdateStt = { updated -> repository.updateSettings { it.copy(sttConfig = updated) } },
                onUpdateLlm = { updated -> repository.updateSettings { it.copy(llmConfig = updated) } },
                onUpdateMultimodal = { updated -> repository.updateSettings { it.copy(multimodalConfig = updated) } },
                onUpdateJev = { updated -> repository.updateSettings { it.copy(jevLlmConfig = updated) } }
            )
        }

        // Layer 3B: Collapsible Live Connectivity & Polish Tester Drawer
        val (activeStyle, _) = remember(settings) {
            repository.resolveActiveStyle(context.packageName)
        }
        val activeDictCount = remember(settings.dictionary) {
            settings.dictionary.count { it.enabled && it.term.isNotBlank() }
        }

        CollapsibleStudioDrawer(
            title = "🩺 连通性实测与毫秒级诊断日志",
            subtitle = "模拟带口癖与改口的真实句子，一键验证首包与全链路耗时",
            expanded = showTesterDrawer,
            onToggle = { showTesterDrawer = !showTesterDrawer }
        ) {
            LiveApiTesterContent(
                testInput = testInput,
                onTestInputChange = { testInput = it },
                onResetDefaultInput = { testInput = DEFAULT_LIVE_TEST_INPUT },
                activeStyleLabel = "${activeStyle.emoji} ${activeStyle.name}",
                activeDictionaryCount = activeDictCount,
                isTesting = isTestingApi,
                successResult = testSuccessResult,
                errorMessage = testErrorMessage,
                copiedResult = copiedTestResult,
                copiedDiagLog = copiedDiagLog,
                isGeminiSmartSingleStage = settings.pipelineMode == PipelineMode.DUAL_STAGE &&
                    settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS &&
                    !settings.llmConfig.enabled,
                isDualStageLlmDisabled = settings.pipelineMode == PipelineMode.DUAL_STAGE &&
                    settings.sttConfig.protocol != SttProtocol.GEMINI_LIVE_WS &&
                    !settings.llmConfig.enabled,
                onRunTest = {
                    if (testInput.isBlank()) {
                        testErrorMessage = "请先输入一段待润色的测试文字"
                        testSuccessResult = null
                        return@LiveApiTesterContent
                    }
                    coroutineScope.launch {
                        isTestingApi = true
                        testErrorMessage = null
                        testSuccessResult = null
                        copiedTestResult = false
                        copiedDiagLog = false

                        val (styleNow, _) = repository.resolveActiveStyle(context.packageName)
                        val res = aiEngine.runTextTransform(
                            settings = settings,
                            activeStyle = styleNow,
                            rawOrSelectedText = testInput,
                            explicitInstruction = null,
                            targetAppName = "API 实测台"
                        )
                        isTestingApi = false
                        res.onSuccess { exec ->
                            if (settings.inputStatsEnabled && !settings.manualIncognitoMode) {
                                repository.dailyStatsStore.recordTokenUsage(
                                    usage = exec.tokenUsage,
                                    modelName = exec.modelName,
                                    featureKey = TokenFeatureKeys.API_TEST,
                                    enabled = true,
                                    incognito = false
                                )
                            }
                            testSuccessResult = exec
                        }.onFailure { err ->
                            testErrorMessage = err.message ?: "请求失败，请检查网络连接与接口地址"
                        }
                    }
                },
                onCopyPolishedResult = { text ->
                    writeToClipboard(context, "TypeFree Test Output", text)
                    copiedTestResult = true
                },
                onCopyDiagnosticLog = { diagText ->
                    writeToClipboard(context, "TypeFree API Diagnostic Log", diagText)
                    copiedDiagLog = true
                }
            )
        }

        // Layer 3C: Collapsible Full Data Backup / Cross-Package Migration Drawer
        CollapsibleStudioDrawer(
            title = "📦 全量数据备份与跨机迁移",
            subtitle = "支持 .json 文件与剪贴板无损迁移：密钥、词典、短语、历史、每日统计、Token、连词库、置顶剪贴板与军师记忆",
            expanded = showBackupDrawer,
            onToggle = { showBackupDrawer = !showBackupDrawer }
        ) {
            ConfigBackupContent(
                statusMessage = backupStatusMsg,
                statusIsError = backupIsError,
                isBusy = isBackupBusy,
                historyCount = historyItems.size,
                dailyStatsDaysCount = dailyStatsDaysCount,
                pinnedClipboardCount = pinnedClipboardCount,
                includeHistory = includeHistoryBackup,
                onIncludeHistoryChange = { includeHistoryBackup = it },
                includeDailyStats = includeDailyStatsBackup,
                onIncludeDailyStatsChange = { includeDailyStatsBackup = it },
                includePredictions = includePredictionsBackup,
                onIncludePredictionsChange = { includePredictionsBackup = it },
                includePinnedClipboard = includePinnedClipboardBackup,
                onIncludePinnedClipboardChange = { includePinnedClipboardBackup = it },
                includeEncryptedJevMemory = includeEncryptedJevMemory,
                onIncludeEncryptedJevMemoryChange = { checked ->
                    includeEncryptedJevMemory = checked
                    if (!checked && !showImportMemorySkipAction) {
                        backupMemoryPassphrase = ""
                    }
                },
                memoryPassphrase = backupMemoryPassphrase,
                onMemoryPassphraseChange = { backupMemoryPassphrase = it },
                showPassphraseField = includeEncryptedJevMemory || showImportMemorySkipAction,
                showSkipMemoryImportButton = showImportMemorySkipAction,
                hasPendingFileImport = pendingImportJson != null,
                onExportToJsonFile = {
                    if (includeEncryptedJevMemory && backupMemoryPassphrase.isBlank()) {
                        backupIsError = true
                        backupStatusMsg = "✕ 请先输入军师记忆加密密码再导出文件"
                    } else {
                        val stamp = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                        exportBackupFileLauncher.launch("typefree-backup-$stamp.json")
                    }
                },
                onImportFromJsonFile = {
                    importBackupFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
                onUnlockPendingImport = {
                    val cached = pendingImportJson
                    if (cached != null) {
                        executeImportJsonPayload(rawJson = cached, skipMemoryIfNoPassphrase = false)
                    } else {
                        executeImportJsonPayload(rawJson = readFromClipboard(context), skipMemoryIfNoPassphrase = false)
                    }
                },
                onExportFullJsonToClipboard = {
                    if (includeEncryptedJevMemory && backupMemoryPassphrase.isBlank()) {
                        backupIsError = true
                        backupStatusMsg = "✕ 请先输入军师记忆加密密码再导出"
                    } else {
                        coroutineScope.launch {
                            isBackupBusy = true
                            val options = currentFullBackupOptions(includeSecrets = true)
                            val passChars = if (options.includeJevMemory) backupMemoryPassphrase.toCharArray() else null
                            val json = withContext(Dispatchers.IO) {
                                val dailyStats = if (options.includeDailyStats) {
                                    repository.dailyStatsStore.flushBlocking(1_000L)
                                    repository.dailyStatsStore.listAllRecords()
                                } else {
                                    emptyList()
                                }
                                val predictions = if (options.includePredictions) {
                                    repository.exportPredictionsArchive()
                                } else {
                                    com.typefree.ime.data.stats.PredictionBackupArchive()
                                }
                                ApiConfigBackupHelper.exportFullBackupJson(
                                    settings = settings,
                                    options = options,
                                    history = if (options.includeHistory) repository.history.value else emptyList(),
                                    dailyStats = dailyStats,
                                    predictions = predictions,
                                    pinnedClipboard = if (options.includePinnedClipboard) repository.clipboardItems.value else emptyList(),
                                    memoryRepository = repository.jevMemoryRepository,
                                    memoryPassphrase = passChars,
                                    wipePassphraseAfterUse = true
                                )
                            }
                            isBackupBusy = false
                            if (options.includeJevMemory) {
                                backupMemoryPassphrase = ""
                            }
                            val byteSize = json.toByteArray(Charsets.UTF_8).size
                            if (byteSize > ApiConfigBackupHelper.CLIPBOARD_SAFE_MAX_BYTES) {
                                val kb = String.format(Locale.US, "%.1f KB", byteSize / 1024.0)
                                backupIsError = true
                                backupStatusMsg = "全量备份体积约 $kb，超过系统剪贴板安全上限，请点「导出 .json 文件」进行无损迁移"
                            } else {
                                writeToClipboard(context, "TypeFree Full Backup", json)
                                val kb = String.format(Locale.US, "%.1f KB", byteSize / 1024.0)
                                backupIsError = false
                                backupStatusMsg = "✓ 全量备份 JSON（$kb）已复制到剪贴板（包含密钥与所选数据，请妥善保管）"
                            }
                        }
                    }
                },
                onExportRedactedJson = {
                    val json = ApiConfigBackupHelper.exportFullBackupJson(
                        settings = settings,
                        options = FullBackupOptions(
                            includeSecrets = false,
                            includeJevMemory = false,
                            includeHistory = false,
                            includeDailyStats = false,
                            includePredictions = false,
                            includePinnedClipboard = false
                        )
                    )
                    writeToClipboard(context, "TypeFree Config Share", json)
                    backupIsError = false
                    backupStatusMsg = "✓ 脱敏配置 JSON 已复制到剪贴板（已隐藏所有 API Key，不含历史/剪贴板/记忆，可安全分享）"
                },
                onImportFromClipboard = {
                    pendingImportJson = null
                    executeImportJsonPayload(
                        rawJson = readFromClipboard(context),
                        skipMemoryIfNoPassphrase = false
                    )
                },
                onImportSkipEncryptedMemory = {
                    val sourceJson = pendingImportJson ?: readFromClipboard(context)
                    executeImportJsonPayload(
                        rawJson = sourceJson,
                        skipMemoryIfNoPassphrase = true
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

// =============================================================================
// Layer 1: Unified Provider Vault Card
// =============================================================================

private const val ID_LOCAL_OFFLINE_PROVIDER = "local_offline"

private val LOCAL_OFFLINE_VAULT_SPEC = UnifiedProviderSpec(
    id = ID_LOCAL_OFFLINE_PROVIDER,
    name = "📱 本机离线",
    badge = "免Key/零费用",
    summary = "语音听音与端侧净写均在本机运行，不消耗 API 额度、断网可用。",
    defaultBaseUrl = "",
    supportsStt = true,
    supportsLlm = true,
    supportsMultimodal = false,
    hasSeparateSpeechToken = false
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnifiedProviderVaultCard(
    settings: AppSettings,
    registrySnapshot: LocalModelRegistrySnapshot,
    vault: Map<String, ProviderCredential>,
    selectedProviderId: String,
    activePrimaryProviderId: String,
    activeLlmProviderId: String,
    isLlmEnabled: Boolean,
    activeJevProviderId: String,
    isJevDedicatedEnabled: Boolean,
    hasConfiguredStt: Boolean,
    onSelectProvider: (String) -> Unit,
    onUpdateCredential: (ProviderCredential) -> Unit,
    onApplyQuickSetup: (String, Boolean) -> Unit,
    onActivatePureOffline: () -> Unit,
    onUnlockPureOffline: () -> Unit,
    onOpenOfflineModels: () -> Unit,
    onPasteClipboard: () -> String
) {
    val isLocalSelected = selectedProviderId == ID_LOCAL_OFFLINE_PROVIDER
    val selectedSpec = remember(selectedProviderId) {
        if (selectedProviderId == ID_LOCAL_OFFLINE_PROVIDER) {
            LOCAL_OFFLINE_VAULT_SPEC
        } else {
            UnifiedProviderCatalog.specFor(selectedProviderId)
        }
    }
    val selectedCred = vault[selectedSpec.id] ?: ProviderCredential(providerId = selectedSpec.id)
    val isSelectedReady = UnifiedProviderCatalog.isCredentialReady(selectedSpec.id, selectedCred)

    val installedAsrModels = remember(registrySnapshot) {
        OfflineModelCatalog.asrModels.filter { registrySnapshot.isInstalled(it.id) }
    }
    val installedSlmModels = remember(registrySnapshot) {
        OfflineModelCatalog.slmModels.filter { registrySnapshot.isInstalled(it.id) }
    }
    val isLocalAnyInstalled = installedAsrModels.isNotEmpty() || installedSlmModels.isNotEmpty()
    val offlineCfg = settings.offlineConfig
    val isLocalActive = offlineCfg.mode != OfflineMode.OFF && (offlineCfg.asrSlotOn || offlineCfg.slmSlotOn)
    val isPureOfflineLocked = offlineCfg.mode == OfflineMode.OFFLINE_ONLY

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "快速接入：本机离线与服务商密钥",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val readyCount = UnifiedProviderCatalog.providers.count { spec ->
                        UnifiedProviderCatalog.isCredentialReady(spec.id, vault[spec.id])
                    } + if (isLocalAnyInstalled) 1 else 0
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "已就绪 $readyCount / ${UnifiedProviderCatalog.providers.size + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    text = "本机离线免 Key 零费用；云端每个平台只需填一次 API Key，听音与润色自动共享",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Local Offline (1st tile) + Channel-Ordered Cloud Providers in clean 2-column grid
            val allVaultSpecs = remember(isPureOfflineLocked) {
                listOf(
                    LOCAL_OFFLINE_VAULT_SPEC.copy(
                        badge = if (isPureOfflineLocked) "纯离线零出网" else "免Key/零费用"
                    )
                ) + OnboardingProviderOrderHelper.orderedCloudProviders()
            }
            allVaultSpecs.chunked(2).forEach { rowSpecs ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowSpecs.forEach { spec ->
                        val isLocalTile = spec.id == ID_LOCAL_OFFLINE_PROVIDER
                        val ready = if (isLocalTile) {
                            isLocalAnyInstalled
                        } else {
                            UnifiedProviderCatalog.isCredentialReady(spec.id, vault[spec.id])
                        }
                        val isSelected = spec.id == selectedSpec.id
                        val isActiveRole = if (isLocalTile) {
                            isLocalActive
                        } else {
                            (!isLocalActive || !offlineCfg.asrSlotOn) && spec.id == activePrimaryProviderId ||
                                (isLlmEnabled && (!isLocalActive || !offlineCfg.slmSlotOn) && spec.id == activeLlmProviderId) ||
                                (isJevDedicatedEnabled && !isPureOfflineLocked && spec.id == activeJevProviderId)
                        }

                        ProviderVaultTile(
                            spec = spec,
                            isSelected = isSelected,
                            isReady = ready,
                            isActiveRole = isActiveRole,
                            onClick = { onSelectProvider(spec.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowSpecs.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // Selected Provider Detail Drawer (Local Offline OR Cloud Credential Form)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isLocalSelected && isPureOfflineLocked) {
                        TypeFreeEmerald.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    }
                )
            ) {
                if (isLocalSelected) {
                    val activeAsrName = OfflineModelCatalog.findById(offlineCfg.selectedAsrModelId)
                        ?.takeIf { registrySnapshot.isInstalled(it.id) }?.displayName
                        ?: installedAsrModels.firstOrNull()?.displayName
                        ?: "未安装"
                    val activeSlmName = OfflineModelCatalog.findById(offlineCfg.selectedSlmModelId)
                        ?.takeIf { registrySnapshot.isInstalled(it.id) }?.displayName
                        ?: installedSlmModels.firstOrNull()?.displayName
                        ?: if (registrySnapshot.slmEngineAvailable) "未安装" else "仅听音版"

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
                                text = "📱 本机离线引擎",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isLocalAnyInstalled) {
                                    TypeFreeEmerald.copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                }
                            ) {
                                Text(
                                    text = when {
                                        isPureOfflineLocked -> "🔒 纯离线零出网"
                                        isLocalAnyInstalled -> "已装 ${installedAsrModels.size} 听音 / ${installedSlmModels.size} 净写"
                                        else -> "尚未下载模型"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isLocalAnyInstalled) {
                                        TypeFreeEmerald
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "本机听音",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = activeAsrName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "端侧净写",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = activeSlmName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isPureOfflineLocked) {
                                OutlinedButton(
                                    onClick = onUnlockPureOffline,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.2.dp, TypeFreeEmerald.copy(alpha = 0.65f))
                                ) {
                                    Text(
                                        text = "✓ 纯离线锁定（点击解锁）",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TypeFreeEmerald,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            } else {
                                Button(
                                    onClick = onActivatePureOffline,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "🔒 一键切换纯离线",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = onOpenOfflineModels,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "管理 / 下载 →",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Text(
                            text = "开启「纯离线」后全部任务严禁联网；若在下方混搭区改选任意云端服务商，将自动解锁并切回端云混合。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                } else {
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
                            text = selectedSpec.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelectedReady) {
                                TypeFreeEmerald.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            }
                        ) {
                            Text(
                                text = if (isSelectedReady) "✓ 密钥已就绪" else selectedSpec.badge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelectedReady) {
                                    TypeFreeEmerald
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = selectedSpec.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )

                    if (selectedSpec.id == UnifiedProviderCatalog.ID_CUSTOM) {
                        SyncedOutlinedField(
                            externalValue = selectedCred.customBaseUrl,
                            onValueCommit = { url ->
                                onUpdateCredential(selectedCred.copy(customBaseUrl = url.trim()))
                            },
                            label = "聚合网关 / 本地服务 Base URL",
                            placeholder = "https://api.openai.com/v1 或 http://192.168.1.10:11434/v1"
                        )
                    }

                    val primaryKeyLabel = when (selectedSpec.id) {
                        UnifiedProviderCatalog.ID_GOOGLE -> "Google AI Studio API Key (AIza...)"
                        UnifiedProviderCatalog.ID_ALIYUN -> "阿里云百炼 API Key (sk-...)"
                        UnifiedProviderCatalog.ID_VOLCENGINE -> "火山方舟 API Key（豆包 LLM 润色 / 单 Key）"
                        UnifiedProviderCatalog.ID_SILICONFLOW -> "硅基流动 API Key (sk-...)"
                        UnifiedProviderCatalog.ID_DEEPSEEK -> "DeepSeek API Key (sk-...)"
                        else -> "API Key"
                    }

                    SecretApiKeyField(
                        externalKey = selectedCred.apiKey,
                        onKeyCommit = { newKey ->
                            onUpdateCredential(selectedCred.copy(apiKey = newKey.trim()))
                        },
                        label = primaryKeyLabel,
                        isLocalUrl = selectedSpec.id == UnifiedProviderCatalog.ID_CUSTOM &&
                            AiPipelineEngine.isLocalEndpoint(selectedCred.customBaseUrl),
                        onPasteClipboard = onPasteClipboard
                    )

                    if (selectedSpec.hasSeparateSpeechToken) {
                        SecretApiKeyField(
                            externalKey = selectedCred.sttApiKey,
                            onKeyCommit = { tok ->
                                onUpdateCredential(selectedCred.copy(sttApiKey = tok.trim()))
                            },
                            label = "火山语音豆包流式 Access Token（与上方同 Key 可留空）",
                            isLocalUrl = false,
                            onPasteClipboard = onPasteClipboard
                        )
                        SyncedOutlinedField(
                            externalValue = selectedCred.appId,
                            onValueCommit = { app ->
                                onUpdateCredential(selectedCred.copy(appId = app.trim()))
                            },
                            label = "火山语音 AppID（新版单 X-Api-Key 可留空）",
                            placeholder = "选填，如 1234567890"
                        )
                    }

                    val isAlreadyPrimary = selectedSpec.id == activePrimaryProviderId &&
                        (selectedSpec.id != UnifiedProviderCatalog.ID_GOOGLE ||
                            !isLlmEnabled) &&
                        (selectedSpec.id != UnifiedProviderCatalog.ID_DEEPSEEK ||
                            (activeLlmProviderId == UnifiedProviderCatalog.ID_DEEPSEEK && isLlmEnabled))
                    val isDeepSeekFullLoop = selectedSpec.id == UnifiedProviderCatalog.ID_DEEPSEEK &&
                        activePrimaryProviderId == UnifiedProviderCatalog.ID_DEEPSEEK &&
                        activeLlmProviderId == UnifiedProviderCatalog.ID_DEEPSEEK &&
                        isLlmEnabled
                    val isDeepSeekLlmWithThirdPartyStt = selectedSpec.id == UnifiedProviderCatalog.ID_DEEPSEEK &&
                        activePrimaryProviderId != UnifiedProviderCatalog.ID_DEEPSEEK &&
                        activeLlmProviderId == UnifiedProviderCatalog.ID_DEEPSEEK &&
                        isLlmEnabled
                    val hasThirdPartySttReady = (hasConfiguredStt && activePrimaryProviderId != UnifiedProviderCatalog.ID_DEEPSEEK) ||
                        UnifiedProviderCatalog.resolveFallbackSttProviderFromVault(
                            vault = vault,
                            excludeProviderId = UnifiedProviderCatalog.ID_DEEPSEEK
                        ) != null
                    val useSecondaryButton = isAlreadyPrimary || isDeepSeekFullLoop

                    Button(
                        onClick = { onApplyQuickSetup(selectedSpec.id, false) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (useSecondaryButton) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            contentColor = if (useSecondaryButton) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            }
                        )
                    ) {
                        val btnText = when {
                            isDeepSeekFullLoop ->
                                "✓ 已启用 DeepSeek 单 Key 方案（语音识别 + 润色与军师）"
                            selectedSpec.id == UnifiedProviderCatalog.ID_DEEPSEEK ->
                                "🚀 一键启用 DeepSeek 单 Key 方案（语音识别 + 润色与军师）"
                            isAlreadyPrimary ->
                                "✓ 当前已是主力方案（点击重置为官方推荐模型）"
                            else ->
                                "🚀 一键设为当前主力方案"
                        }
                        Text(
                            text = btnText,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (selectedSpec.id == UnifiedProviderCatalog.ID_DEEPSEEK && hasThirdPartySttReady) {
                        OutlinedButton(
                            onClick = { onApplyQuickSetup(UnifiedProviderCatalog.ID_DEEPSEEK, true) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(11.dp)
                        ) {
                            Text(
                                text = if (isDeepSeekLlmWithThirdPartyStt) {
                                    "✓ 当前混搭中：保留第三方听音 + DeepSeek 文本大脑"
                                } else {
                                    "🧠 保留当前第三方听音引擎，仅将 DeepSeek 设为文本大脑"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun ProviderVaultTile(
    spec: UnifiedProviderSpec,
    isSelected: Boolean,
    isReady: Boolean,
    isActiveRole: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        },
        border = BorderStroke(
            width = if (isSelected) 1.6.dp else 1.dp,
            color = when {
                isSelected -> MaterialTheme.colorScheme.primary
                isActiveRole -> TypeFreeEmerald.copy(alpha = 0.55f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = spec.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (isReady) {
                                TypeFreeEmerald
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                            }
                        )
                )
            }
            Text(
                text = if (isActiveRole) "正在使用 · ${spec.badge}" else spec.badge,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActiveRole) {
                    TypeFreeEmerald
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// =============================================================================
// Layer 2: Unified Workflow & Role Model Routing Card
// =============================================================================

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun UnifiedWorkflowRoutingCard(
    settings: AppSettings,
    dictatePlan: RoutePlan,
    registrySnapshot: LocalModelRegistrySnapshot,
    vault: Map<String, ProviderCredential>,
    fetchingSlot: ModelCapabilitySlot?,
    modelFetchErrors: Map<ModelCapabilitySlot, String>,
    onSelectMode: (PipelineMode) -> Unit,
    onSelectLocalAsr: () -> Unit,
    onSelectLocalAsrModel: (String) -> Unit,
    onSelectLocalSlm: () -> Unit,
    onSelectLocalSlmModel: (String) -> Unit,
    onQuickDownloadLocalModel: (String) -> Unit,
    onOpenOfflineModels: () -> Unit,
    onSelectSttProvider: (String) -> Unit,
    onSelectLlmProvider: (String) -> Unit,
    onSelectMultimodalProvider: (String) -> Unit,
    onToggleJevDedicated: (Boolean) -> Unit,
    onSelectJevProvider: (String) -> Unit,
    onUpdateStt: (SttConfig) -> Unit,
    onUpdateLlm: (LlmConfig) -> Unit,
    onUpdateMultimodal: (MultimodalConfig) -> Unit,
    onUpdateJev: (LlmConfig) -> Unit,
    onFetchModels: (ModelCapabilitySlot) -> Unit
) {
    val activeSttPid = remember(settings.sttConfig) {
        UnifiedProviderCatalog.inferSttProviderId(settings.sttConfig)
    }
    val activeLlmPid = remember(settings.llmConfig) {
        UnifiedProviderCatalog.inferLlmProviderId(settings.llmConfig)
    }
    val activeMmPid = remember(settings.multimodalConfig) {
        UnifiedProviderCatalog.inferMultimodalProviderId(settings.multimodalConfig)
    }
    val activeJevPid = remember(settings.jevLlmConfig) {
        UnifiedProviderCatalog.inferLlmProviderId(settings.jevLlmConfig)
    }
    val installedAsrModels = remember(registrySnapshot) {
        OfflineModelCatalog.asrModels.filter { registrySnapshot.isInstalled(it.id) }
    }
    val installedSlmModels = remember(registrySnapshot) {
        OfflineModelCatalog.slmModels.filter { registrySnapshot.isInstalled(it.id) }
    }
    val isLocalAsrSelected = settings.offlineConfig.mode != OfflineMode.OFF && settings.offlineConfig.asrSlotOn
    val isLocalSlmSelected = settings.llmConfig.enabled &&
        settings.offlineConfig.mode != OfflineMode.OFF &&
        settings.offlineConfig.slmSlotOn &&
        (registrySnapshot.slmEngineAvailable || installedSlmModels.isNotEmpty())

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "2. 工作流与模型自由混搭",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "无需手动填写底层 WS/HTTP 协议地址，点选服务商即可自动挂载对应密钥",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Clean 2-Segment Mode Switcher
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
                    PipelineSegmentPill(
                        title = "⚡ 实时流式 / 双阶段",
                        selected = settings.pipelineMode == PipelineMode.DUAL_STAGE,
                        onClick = { onSelectMode(PipelineMode.DUAL_STAGE) },
                        modifier = Modifier.weight(1f)
                    )
                    PipelineSegmentPill(
                        title = "🎙️ 多模态整包直出",
                        selected = settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT,
                        onClick = { onSelectMode(PipelineMode.MULTIMODAL_DIRECT) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> {
                    // Role A: Speech Listening Engine
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "👂 听音转写引擎（边说边传 / 声学热词）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )

                            val sttProviders = remember {
                                UnifiedProviderCatalog.providers.filter { it.supportsStt }
                            }
                            RoleProviderChipsRow(
                                specs = sttProviders,
                                selectedId = if (isLocalAsrSelected) ID_LOCAL_OFFLINE_PROVIDER else activeSttPid,
                                vault = vault,
                                includeLocalOfflineChip = true,
                                isLocalOfflineReady = installedAsrModels.isNotEmpty(),
                                onSelectLocalOffline = onSelectLocalAsr,
                                onSelect = onSelectSttProvider
                            )

                            if (isLocalAsrSelected) {
                                LocalRoleModelSelectorPanel(
                                    title = "已下载的本机听音模型",
                                    installedModels = installedAsrModels,
                                    selectedModelId = settings.offlineConfig.selectedAsrModelId,
                                    emptyTitle = "尚未下载本机听音模型",
                                    emptySubtitle = "推荐 SenseVoiceSmall（226MB，0.24s 极速中英混说）",
                                    quickDownloadModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
                                    quickDownloadLabel = "一键下载",
                                    onSelectModel = onSelectLocalAsrModel,
                                    onQuickDownload = onQuickDownloadLocalModel,
                                    onOpenManager = onOpenOfflineModels
                                )
                            } else {
                            if (settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = TypeFreeEmerald.copy(alpha = 0.10f),
                                    border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.42f))
                                ) {
                                    Text(
                                        text = "✨ Gemini 3.5 Live SMART 已激活：边说边实时出字，松手 ~0.5s 秒出净稿，原生自带去口癖与改口自纠！日常口述无需开启二阶段润色；使用「帮我写 / 改写 / 快译 / 🔍 问AI」时会自动共享同一把 Gemini Key 调用 Flash-Lite。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 16.sp,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            } else if (activeSttPid == UnifiedProviderCatalog.ID_DEEPSEEK ||
                                EndpointResolver.isDeepSeekAsrEndpoint(settings.sttConfig.baseUrl)
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = TypeFreeEmerald.copy(alpha = 0.10f),
                                    border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.42f))
                                ) {
                                    Text(
                                        text = "🎙️ DeepSeek 官方语音识别已激活：与 DeepSeek 文本润色共用单 Key，自动检测中/粤/英/日/韩等语种。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 16.sp,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }

                            val sttSuggestions = UnifiedProviderCatalog.sttSuggestedModelsByProvider[activeSttPid]
                                ?: ApiConfigBackupHelper.sttModelSuggestions(
                                    settings.sttConfig.presetId,
                                    settings.sttConfig.protocol
                                )

                            if (settings.sttConfig.protocol == SttProtocol.OPENAI_HTTP) {
                                ProviderModelFieldSection(
                                    externalModel = settings.sttConfig.model,
                                    onModelCommit = { m -> onUpdateStt(settings.sttConfig.copy(model = m)) },
                                    label = "听音模型名称 (Model)",
                                    placeholder = "FunAudioLLM/SenseVoiceSmall",
                                    suggestions = sttSuggestions,
                                    isFetching = fetchingSlot == ModelCapabilitySlot.STT,
                                    fetchError = modelFetchErrors[ModelCapabilitySlot.STT],
                                    onFetchModels = { onFetchModels(ModelCapabilitySlot.STT) }
                                )
                            } else {
                                SyncedOutlinedField(
                                    externalValue = settings.sttConfig.model,
                                    onValueCommit = { m -> onUpdateStt(settings.sttConfig.copy(model = m)) },
                                    label = "流式听音模型 (Model)",
                                    placeholder = sttSuggestions.firstOrNull().orEmpty()
                                )
                                QuickModelChipsRow(
                                    suggestions = sttSuggestions,
                                    currentModel = settings.sttConfig.model,
                                    onSelectModel = { m -> onUpdateStt(settings.sttConfig.copy(model = m)) }
                                )
                            }
                            }
                        }
                    }

                    // Role B: Optional 2nd-Stage LLM Polish & Generative Engine
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = "🧠 二阶段语义润色 & 写作快译模型",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = when {
                                            settings.llmConfig.enabled ->
                                                "日常口述也会经过该模型按应用语气深度润色"
                                            settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS ->
                                                "日常口述由 Gemini Live 单阶直出；帮我写/快译自动用下方模型"
                                            else ->
                                                "日常口述直出原始识别词；仅帮我写/编辑/快译调用下方模型"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = settings.llmConfig.enabled,
                                    onCheckedChange = { enabled ->
                                        onUpdateLlm(settings.llmConfig.copy(enabled = enabled))
                                    }
                                )
                            }

                            val llmProviders = remember {
                                UnifiedProviderCatalog.providers.filter { it.supportsLlm }
                            }
                            RoleProviderChipsRow(
                                specs = llmProviders,
                                selectedId = if (isLocalSlmSelected) ID_LOCAL_OFFLINE_PROVIDER else activeLlmPid,
                                vault = vault,
                                includeLocalOfflineChip = registrySnapshot.slmEngineAvailable || installedSlmModels.isNotEmpty(),
                                isLocalOfflineReady = installedSlmModels.isNotEmpty(),
                                onSelectLocalOffline = onSelectLocalSlm,
                                onSelect = onSelectLlmProvider
                            )

                            if (isLocalSlmSelected) {
                                val canQuickDownload08b = LocalInferenceEngineRegistry.hasDownloadableSlmSource(
                                    OfflineModelCatalog.ID_SLM_CORE_0_8B
                                )
                                LocalRoleModelSelectorPanel(
                                    title = "已就绪的端侧净写模型",
                                    installedModels = installedSlmModels,
                                    selectedModelId = settings.offlineConfig.selectedSlmModelId,
                                    emptyTitle = "尚未下载本机净写模型",
                                    emptySubtitle = "推荐轻巧净写版（0.8B，官方底座 + 12MB 精调权重）",
                                    quickDownloadModelId = if (canQuickDownload08b) OfflineModelCatalog.ID_SLM_CORE_0_8B else null,
                                    quickDownloadLabel = "一键下载 0.8B",
                                    onSelectModel = onSelectLocalSlmModel,
                                    onQuickDownload = onQuickDownloadLocalModel,
                                    onOpenManager = onOpenOfflineModels
                                )
                            } else {
                            val llmSuggestions = UnifiedProviderCatalog.llmSuggestedModelsByProvider[activeLlmPid]
                                ?: ApiConfigBackupHelper.llmModelSuggestions(settings.llmConfig.presetId)

                            ProviderModelFieldSection(
                                externalModel = settings.llmConfig.model,
                                onModelCommit = { m -> onUpdateLlm(settings.llmConfig.copy(model = m)) },
                                label = "润色 / 写作 / 翻译模型 (Model)",
                                placeholder = "gemini-3.5-flash-lite",
                                suggestions = llmSuggestions,
                                isFetching = fetchingSlot == ModelCapabilitySlot.LLM,
                                fetchError = modelFetchErrors[ModelCapabilitySlot.LLM],
                                onFetchModels = { onFetchModels(ModelCapabilitySlot.LLM) }
                            )
                            }
                        }
                    }
                }

                PipelineMode.MULTIMODAL_DIRECT -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "🎙️ 多模态音频一步直出",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (dictatePlan.asr is AsrRoute.Local || dictatePlan.text is TextRoute.Local) {
                                val asrNote = (dictatePlan.asr as? AsrRoute.Local)?.let {
                                    OfflineModelCatalog.findById(it.modelId)?.displayName ?: it.modelId
                                }
                                val slmNote = (dictatePlan.text as? TextRoute.Local)?.let {
                                    OfflineModelCatalog.findById(it.modelId)?.displayName ?: it.modelId
                                }
                                val takeoverDesc = listOfNotNull(
                                    asrNote?.let { "听音（$it）" },
                                    slmNote?.let { "润色（$it）" }
                                ).joinToString(" 与 ")
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = TypeFreeEmerald.copy(alpha = 0.10f),
                                    border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.42f))
                                ) {
                                    Text(
                                        text = "📱 本机离线接管中：当前由本机接管 $takeoverDesc；关闭离线开关即自动恢复下方多模态云端配置（绝不清空密钥）。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 16.sp,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                            Text(
                                text = "录音结束后单次请求直接完成听音理解、语气适配与翻译（如需边说边出字，可在上方切换「实时流式」）。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )

                            val mmProviders = remember {
                                UnifiedProviderCatalog.providers.filter { it.supportsMultimodal }
                            }
                            RoleProviderChipsRow(
                                specs = mmProviders,
                                selectedId = activeMmPid,
                                vault = vault,
                                onSelect = onSelectMultimodalProvider
                            )

                            val mmSuggestions = UnifiedProviderCatalog.multimodalSuggestedModelsByProvider[activeMmPid]
                                ?: ApiConfigBackupHelper.multimodalModelSuggestions(
                                    settings.multimodalConfig.presetId,
                                    settings.multimodalConfig.protocol
                                )

                            ProviderModelFieldSection(
                                externalModel = settings.multimodalConfig.model,
                                onModelCommit = { m ->
                                    onUpdateMultimodal(settings.multimodalConfig.copy(model = m))
                                },
                                label = "多模态音频模型 (Model)",
                                placeholder = "gemini-3.5-flash-lite",
                                suggestions = mmSuggestions,
                                isFetching = fetchingSlot == ModelCapabilitySlot.MULTIMODAL,
                                fetchError = modelFetchErrors[ModelCapabilitySlot.MULTIMODAL],
                                onFetchModels = { onFetchModels(ModelCapabilitySlot.MULTIMODAL) }
                            )
                        }
                    }
                }
            }

            // Role C: Optional Dedicated Jev Copilot Model (Visible in both DUAL_STAGE and MULTIMODAL_DIRECT)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (settings.jevUseDedicatedModel) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = "✦ Jev 军师专用模型（高情商/多模态独立路由）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (settings.jevUseDedicatedModel) {
                                    "已开启：点按『✦ 军师』时固定调用下方高智商模型（日常语音仍走极速通道）"
                                } else {
                                    "默认关闭：跟随当前润色或多模态模型；开启后可单独指定旗舰模型做局势深算"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.jevUseDedicatedModel,
                            onCheckedChange = onToggleJevDedicated
                        )
                    }

                    AnimatedVisibility(visible = settings.jevUseDedicatedModel) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val llmProviders = remember {
                                UnifiedProviderCatalog.providers.filter { it.supportsLlm }
                            }
                            RoleProviderChipsRow(
                                specs = llmProviders,
                                selectedId = activeJevPid,
                                vault = vault,
                                onSelect = onSelectJevProvider
                            )

                            val jevSuggestions = UnifiedProviderCatalog.jevSuggestedModelsByProvider[activeJevPid]
                                ?: UnifiedProviderCatalog.llmSuggestedModelsByProvider[activeJevPid]
                                ?: ApiConfigBackupHelper.llmModelSuggestions(settings.jevLlmConfig.presetId)

                            ProviderModelFieldSection(
                                externalModel = settings.jevLlmConfig.model,
                                onModelCommit = { m -> onUpdateJev(settings.jevLlmConfig.copy(model = m)) },
                                label = "Jev 军师专用模型 (Model)",
                                placeholder = "gemini-3.8-flash / deepseek-chat",
                                suggestions = jevSuggestions,
                                isFetching = fetchingSlot == ModelCapabilitySlot.JEV_COPILOT,
                                fetchError = modelFetchErrors[ModelCapabilitySlot.JEV_COPILOT],
                                onFetchModels = { onFetchModels(ModelCapabilitySlot.JEV_COPILOT) }
                            )

                            ThinkingModeSelectorSection(
                                thinkingMode = settings.jevLlmConfig.thinkingMode,
                                onSelectMode = { mode ->
                                    onUpdateJev(settings.jevLlmConfig.copy(thinkingMode = mode))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RoleProviderChipsRow(
    specs: List<UnifiedProviderSpec>,
    selectedId: String,
    vault: Map<String, ProviderCredential>,
    includeLocalOfflineChip: Boolean = false,
    isLocalOfflineReady: Boolean = false,
    onSelectLocalOffline: (() -> Unit)? = null,
    onSelect: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (includeLocalOfflineChip && onSelectLocalOffline != null) {
            val localSelected = selectedId == ID_LOCAL_OFFLINE_PROVIDER
            FilterChip(
                selected = localSelected,
                onClick = onSelectLocalOffline,
                label = {
                    Text(
                        text = if (isLocalOfflineReady) "● 📱 本机离线" else "📱 本机离线",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = TypeFreeEmerald.copy(alpha = 0.18f),
                    selectedLabelColor = TypeFreeEmerald
                )
            )
        }
        specs.forEach { spec ->
            val selected = spec.id == selectedId
            val ready = UnifiedProviderCatalog.isCredentialReady(spec.id, vault[spec.id])
            FilterChip(
                selected = selected,
                onClick = { onSelect(spec.id) },
                label = {
                    Text(
                        text = if (ready) "● ${spec.name}" else spec.name,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LocalRoleModelSelectorPanel(
    title: String,
    installedModels: List<com.typefree.core.offline.LocalModelDescriptor>,
    selectedModelId: String?,
    emptyTitle: String,
    emptySubtitle: String,
    quickDownloadModelId: String?,
    quickDownloadLabel: String,
    onSelectModel: (String) -> Unit,
    onQuickDownload: (String) -> Unit,
    onOpenManager: () -> Unit
) {
    if (installedModels.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onOpenManager,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "管理更多 →",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                installedModels.forEach { model ->
                    val selected = model.id == selectedModelId ||
                        (selectedModelId.isNullOrBlank() && model == installedModels.first())
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectModel(model.id) },
                        label = {
                            Text(
                                text = if (selected) "✓ ${model.displayName}" else model.displayName,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TypeFreeEmerald.copy(alpha = 0.16f),
                            selectedLabelColor = TypeFreeEmerald
                        )
                    )
                }
            }
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = TypeFreeAmber.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, TypeFreeAmber.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
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
                        text = emptyTitle,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = emptySubtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!quickDownloadModelId.isNullOrBlank()) {
                    Button(
                        onClick = { onQuickDownload(quickDownloadModelId) },
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = quickDownloadLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onOpenManager,
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "打开模型库",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// Layer 3: Collapsible Studio Drawers (Advanced / Diagnostics / JSON Backup)
// =============================================================================

@Composable
private fun CollapsibleStudioDrawer(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.50f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
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
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedParametersDrawerContent(
    settings: AppSettings,
    onUpdateStt: (SttConfig) -> Unit,
    onUpdateLlm: (LlmConfig) -> Unit,
    onUpdateMultimodal: (MultimodalConfig) -> Unit,
    onUpdateJev: (LlmConfig) -> Unit
) {
    val languageOptions = listOf(
        "" to "自动识别",
        "zh" to "中文 (zh)",
        "en" to "英文 (en)",
        "ja" to "日文 (ja)"
    )

    if (settings.pipelineMode == PipelineMode.DUAL_STAGE) {
        val sttEndpoint = remember(settings.sttConfig.baseUrl, settings.sttConfig.protocol) {
            when (settings.sttConfig.protocol) {
                SttProtocol.OPENAI_HTTP -> EndpointResolver.resolveSttEndpoint(settings.sttConfig.baseUrl)
                SttProtocol.ALIYUN_DASHSCOPE_WS -> EndpointResolver.resolveDashScopeWsEndpoint(settings.sttConfig.baseUrl)
                SttProtocol.VOLCENGINE_SAUC_WS -> EndpointResolver.resolveVolcengineSaucWsEndpoint(settings.sttConfig.baseUrl)
                SttProtocol.GEMINI_LIVE_WS -> EndpointResolver.resolveGeminiLiveWsEndpoint(
                    settings.sttConfig.baseUrl,
                    if (settings.sttConfig.apiKey.isBlank()) "YOUR_API_KEY" else "***"
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SyncedOutlinedField(
                externalValue = settings.sttConfig.baseUrl,
                onValueCommit = { onUpdateStt(settings.sttConfig.copy(baseUrl = it.trim())) },
                label = "听音引擎地址覆盖（反代 / 自建网关可在此修改）",
                placeholder = "https://generativelanguage.googleapis.com/v1beta"
            )
            EndpointPreviewCaption(endpoint = sttEndpoint)
        }

        if (settings.sttConfig.protocol == SttProtocol.VOLCENGINE_SAUC_WS) {
            SyncedOutlinedField(
                externalValue = settings.sttConfig.resourceId,
                onValueCommit = { onUpdateStt(settings.sttConfig.copy(resourceId = it.trim())) },
                label = "火山豆包语音 Resource ID",
                placeholder = VolcengineSaucAsrClient.DEFAULT_RESOURCE_ID
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "听音语言偏好",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                languageOptions.forEach { (code, label) ->
                    val selected = settings.sttConfig.language == code
                    FilterChip(
                        selected = selected,
                        onClick = { onUpdateStt(settings.sttConfig.copy(language = code)) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SyncedOutlinedField(
                externalValue = settings.llmConfig.baseUrl,
                onValueCommit = { onUpdateLlm(settings.llmConfig.copy(baseUrl = it.trim())) },
                label = "LLM 润色/写作端点覆盖",
                placeholder = "https://generativelanguage.googleapis.com/v1beta"
            )
            EndpointPreviewCaption(
                endpoint = if (EndpointResolver.isGeminiNativeHttpUrl(settings.llmConfig.baseUrl) ||
                    settings.llmConfig.presetId == "gemini_llm"
                ) {
                    EndpointResolver.resolveGeminiEndpoint(
                        settings.llmConfig.baseUrl,
                        settings.llmConfig.model,
                        "***"
                    )
                } else {
                    EndpointResolver.resolveChatEndpoint(settings.llmConfig.baseUrl)
                }
            )
        }

        ThinkingModeSelectorSection(
            thinkingMode = settings.llmConfig.thinkingMode,
            onSelectMode = { mode -> onUpdateLlm(settings.llmConfig.copy(thinkingMode = mode)) }
        )

        TemperatureSliderSection(
            temperature = settings.llmConfig.temperature,
            model = settings.llmConfig.model,
            onTemperatureChange = { temp -> onUpdateLlm(settings.llmConfig.copy(temperature = temp)) }
        )
    } else {
        val mmPreview = remember(
            settings.multimodalConfig.baseUrl,
            settings.multimodalConfig.model,
            settings.multimodalConfig.protocol,
            settings.multimodalConfig.apiKey
        ) {
            ApiConfigBackupHelper.previewMultimodalEndpoint(settings.multimodalConfig)
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "多模态音频协议格式",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = settings.multimodalConfig.protocol == MultimodalProtocol.GEMINI_NATIVE,
                    onClick = {
                        onUpdateMultimodal(
                            settings.multimodalConfig.copy(protocol = MultimodalProtocol.GEMINI_NATIVE)
                        )
                    },
                    label = { Text("Gemini 原生 (generateContent)") }
                )
                FilterChip(
                    selected = settings.multimodalConfig.protocol == MultimodalProtocol.OPENAI_AUDIO_CHAT,
                    onClick = {
                        onUpdateMultimodal(
                            settings.multimodalConfig.copy(protocol = MultimodalProtocol.OPENAI_AUDIO_CHAT)
                        )
                    },
                    label = { Text("OpenAI 音频兼容 (input_audio)") }
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SyncedOutlinedField(
                externalValue = settings.multimodalConfig.baseUrl,
                onValueCommit = {
                    onUpdateMultimodal(settings.multimodalConfig.copy(baseUrl = it.trim()))
                },
                label = "多模态网关地址覆盖",
                placeholder = "https://generativelanguage.googleapis.com/v1beta"
            )
            EndpointPreviewCaption(endpoint = mmPreview)
        }

        ThinkingModeSelectorSection(
            thinkingMode = settings.multimodalConfig.thinkingMode,
            onSelectMode = { mode ->
                onUpdateMultimodal(settings.multimodalConfig.copy(thinkingMode = mode))
            }
        )

        TemperatureSliderSection(
            temperature = settings.multimodalConfig.temperature,
            model = settings.multimodalConfig.model,
            onTemperatureChange = { temp ->
                onUpdateMultimodal(settings.multimodalConfig.copy(temperature = temp))
            }
        )
    }

    if (settings.jevUseDedicatedModel) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SyncedOutlinedField(
                externalValue = settings.jevLlmConfig.baseUrl,
                onValueCommit = { onUpdateJev(settings.jevLlmConfig.copy(baseUrl = it.trim())) },
                label = "✦ Jev 军师端点覆盖 (Base URL)",
                placeholder = "https://generativelanguage.googleapis.com/v1beta"
            )
            EndpointPreviewCaption(
                endpoint = if (EndpointResolver.isGeminiNativeHttpUrl(settings.jevLlmConfig.baseUrl) ||
                    settings.jevLlmConfig.presetId == "gemini_llm"
                ) {
                    EndpointResolver.resolveGeminiEndpoint(
                        settings.jevLlmConfig.baseUrl,
                        settings.jevLlmConfig.model,
                        "***"
                    )
                } else {
                    EndpointResolver.resolveChatEndpoint(settings.jevLlmConfig.baseUrl)
                }
            )
        }

        TemperatureSliderSection(
            temperature = settings.jevLlmConfig.temperature,
            model = settings.jevLlmConfig.model,
            onTemperatureChange = { temp ->
                onUpdateJev(settings.jevLlmConfig.copy(temperature = temp))
            }
        )
    }
}

@Composable
private fun PipelineSegmentPill(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp
    ) {
        Box(
            modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
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

@Composable
private fun LiveApiTesterContent(
    testInput: String,
    onTestInputChange: (String) -> Unit,
    onResetDefaultInput: () -> Unit,
    activeStyleLabel: String,
    activeDictionaryCount: Int,
    isTesting: Boolean,
    successResult: PipelineExecutionResult?,
    errorMessage: String?,
    copiedResult: Boolean,
    copiedDiagLog: Boolean,
    isGeminiSmartSingleStage: Boolean,
    isDualStageLlmDisabled: Boolean,
    onRunTest: () -> Unit,
    onCopyPolishedResult: (String) -> Unit,
    onCopyDiagnosticLog: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "当前风格：$activeStyleLabel · 启用词典：$activeDictionaryCount 条",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (testInput != DEFAULT_LIVE_TEST_INPUT) {
            TextButton(
                onClick = onResetDefaultInput,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("重置示例", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (isGeminiSmartSingleStage) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = TypeFreeEmerald.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.4f))
        ) {
            Text(
                text = "当前处于 Gemini Live SMART 单阶听写模式；点击下方按钮将验证同把 Gemini Key 驱动的写作/快译引擎连通性与响应延迟。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(10.dp)
            )
        }
    } else if (isDualStageLlmDisabled) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = TypeFreeAmber.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, TypeFreeAmber.copy(alpha = 0.4f))
        ) {
            Text(
                text = "提示：二阶段润色开关当前已关闭，此按钮仍会验证已配置的写作/快译模型连通性。",
                style = MaterialTheme.typography.bodySmall,
                color = TypeFreeAmber,
                modifier = Modifier.padding(10.dp)
            )
        }
    }

    OutlinedTextField(
        value = testInput,
        onValueChange = onTestInputChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 4,
        shape = RoundedCornerShape(12.dp),
        label = { Text("模拟带口癖、改口与专有名词的口述原稿") }
    )

    Button(
        onClick = onRunTest,
        enabled = !isTesting,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (isTesting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text("正在请求接口实测连通性...", fontWeight = FontWeight.Bold)
        } else {
            Text("一键发送真实连通性测试", fontWeight = FontWeight.Bold)
        }
    }

    AnimatedVisibility(visible = successResult != null) {
        successResult?.let { res ->
            val sourceLabel = when (res.executionSource) {
                ExecutionSource.LOCAL -> "📱 本机离线推理"
                ExecutionSource.HYBRID_LOCAL_ASR_CLOUD_LLM -> "⚡ 混合（本机听音 + 云端润色）"
                ExecutionSource.LOCAL_RAW_PASSTHROUGH -> "📱 本机直出（未润色）"
                ExecutionSource.CLOUD -> "☁️ 云端接口"
                ExecutionSource.BLOCKED -> "🔒 已拦截"
            }
            val fullDiagnosticClipboard = buildString {
                appendLine("TypeFree API 连通性诊断日志")
                appendLine("总耗时: ${res.latencyMs} ms")
                appendLine("执行路径: $sourceLabel (${res.executionSource.name})")
                appendLine("响应模型: ${res.modeSummary}")
                if (res.localMetricsSummary.isNotBlank()) {
                    appendLine("本机性能指标: ${res.localMetricsSummary}")
                }
                if (res.diagnosticLog.isNotBlank()) {
                    appendLine(res.diagnosticLog)
                }
                append("输出结果: ${res.finalOutput}")
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = TypeFreeEmerald.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.50f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✓ 接口连通成功 · ${res.latencyMs} ms",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = TypeFreeEmerald
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { onCopyDiagnosticLog(fullDiagnosticClipboard) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = if (copiedDiagLog) "✓ 已复制日志" else "复制日志",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { onCopyPolishedResult(res.finalOutput) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    imageVector = TypeFreeIcons.Copy,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = TypeFreeEmerald
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (copiedResult) "已复制" else "复制",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TypeFreeEmerald
                                )
                            }
                        }
                    }
                    Text(
                        text = "执行路径：$sourceLabel · 响应模型：${res.modeSummary}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (res.localMetricsSummary.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = TypeFreeEmerald.copy(alpha = 0.12f),
                            border = BorderStroke(0.5.dp, TypeFreeEmerald.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "📊 本机指标：${res.localMetricsSummary}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Text(
                        text = res.finalOutput,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (res.diagnosticLog.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = res.diagnosticLog,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    AnimatedVisibility(visible = errorMessage != null) {
        errorMessage?.let { errMsg ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = TypeFreeRose.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, TypeFreeRose.copy(alpha = 0.50f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✕ 接口请求失败（诊断详情）",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = TypeFreeRose
                        )
                        TextButton(
                            onClick = { onCopyDiagnosticLog(errMsg) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = if (copiedDiagLog) "✓ 已复制日志" else "复制日志",
                                style = MaterialTheme.typography.labelSmall,
                                color = TypeFreeRose
                            )
                        }
                    }
                    Text(
                        text = errMsg,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = TypeFreeRose
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ConfigBackupContent(
    statusMessage: String?,
    statusIsError: Boolean,
    isBusy: Boolean = false,
    historyCount: Int = 0,
    dailyStatsDaysCount: Int = 0,
    pinnedClipboardCount: Int = 0,
    includeHistory: Boolean = true,
    onIncludeHistoryChange: (Boolean) -> Unit = {},
    includeDailyStats: Boolean = true,
    onIncludeDailyStatsChange: (Boolean) -> Unit = {},
    includePredictions: Boolean = true,
    onIncludePredictionsChange: (Boolean) -> Unit = {},
    includePinnedClipboard: Boolean = true,
    onIncludePinnedClipboardChange: (Boolean) -> Unit = {},
    includeEncryptedJevMemory: Boolean = false,
    onIncludeEncryptedJevMemoryChange: (Boolean) -> Unit = {},
    memoryPassphrase: String = "",
    onMemoryPassphraseChange: (String) -> Unit = {},
    showPassphraseField: Boolean = false,
    showSkipMemoryImportButton: Boolean = false,
    hasPendingFileImport: Boolean = false,
    onExportToJsonFile: () -> Unit = {},
    onImportFromJsonFile: () -> Unit = {},
    onUnlockPendingImport: () -> Unit = {},
    onExportFullJsonToClipboard: () -> Unit,
    onExportRedactedJson: () -> Unit,
    onImportFromClipboard: () -> Unit,
    onImportSkipEncryptedMemory: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "包含的用户数据（默认全选，导入时自动无损合并去重）",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BackupModuleFilterChip(
                selected = includeHistory,
                label = if (historyCount > 0) "识别历史 ($historyCount)" else "识别历史",
                onClick = { onIncludeHistoryChange(!includeHistory) }
            )
            BackupModuleFilterChip(
                selected = includeDailyStats,
                label = if (dailyStatsDaysCount > 0) "统计与 Token (${dailyStatsDaysCount}天)" else "统计与 Token",
                onClick = { onIncludeDailyStatsChange(!includeDailyStats) }
            )
            BackupModuleFilterChip(
                selected = includePredictions,
                label = "L2 私人连词库",
                onClick = { onIncludePredictionsChange(!includePredictions) }
            )
            BackupModuleFilterChip(
                selected = includePinnedClipboard,
                label = if (pinnedClipboardCount > 0) "置顶剪贴板 ($pinnedClipboardCount)" else "置顶剪贴板",
                onClick = { onIncludePinnedClipboardChange(!includePinnedClipboard) }
            )
        }
        Text(
            text = "配置项（密钥库、短语、个人词典、风格、应用规则、手势）始终包含；脱敏导出仅保留配置结构并隐藏密钥与个人数据。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "附带端到端加密的 Jev 军师记忆",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "使用密码 AES-GCM 加密打包关系与风格记忆；脱敏导出绝不包含记忆",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = includeEncryptedJevMemory,
            onCheckedChange = onIncludeEncryptedJevMemoryChange
        )
    }

    AnimatedVisibility(visible = showPassphraseField) {
        OutlinedTextField(
            value = memoryPassphrase,
            onValueChange = onMemoryPassphraseChange,
            label = { Text("军师记忆加密 / 解锁密码") },
            placeholder = { Text("仅在本地用于 PBKDF2-AES-256 加密，不会保存") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        )
    }

    // Primary File Export / Import Row (Recommended for Full Lossless Migration)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onExportToJsonFile,
            enabled = !isBusy,
            shape = RoundedCornerShape(11.dp)
        ) {
            Text(if (includeEncryptedJevMemory) "导出全量加密 .json 文件" else "导出全量 .json 文件")
        }

        FilledTonalButton(
            onClick = onImportFromJsonFile,
            enabled = !isBusy,
            shape = RoundedCornerShape(11.dp)
        ) {
            Text("从 .json 文件导入")
        }

        if (showSkipMemoryImportButton && hasPendingFileImport) {
            Button(
                onClick = onUnlockPendingImport,
                enabled = !isBusy && memoryPassphrase.isNotBlank(),
                shape = RoundedCornerShape(11.dp)
            ) {
                Text("密码解锁并导入")
            }
        }
    }

    // Secondary Clipboard Export / Import Row
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onExportFullJsonToClipboard,
            enabled = !isBusy,
            shape = RoundedCornerShape(11.dp)
        ) {
            Icon(
                imageVector = TypeFreeIcons.Copy,
                contentDescription = null,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("复制全量 JSON")
        }

        OutlinedButton(
            onClick = onImportFromClipboard,
            enabled = !isBusy,
            shape = RoundedCornerShape(11.dp)
        ) {
            Text("从剪贴板导入")
        }

        OutlinedButton(
            onClick = onExportRedactedJson,
            enabled = !isBusy,
            shape = RoundedCornerShape(11.dp)
        ) {
            Text("脱敏分享 (隐藏 Key)")
        }

        if (showSkipMemoryImportButton) {
            OutlinedButton(
                onClick = onImportSkipEncryptedMemory,
                enabled = !isBusy,
                shape = RoundedCornerShape(11.dp)
            ) {
                Text("跳过记忆仅导其余数据")
            }
        }
    }

    if (isBusy) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Text(
                text = "正在处理全量备份数据…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    AnimatedVisibility(visible = statusMessage != null) {
        statusMessage?.let { msg ->
            val statusColor = if (statusIsError) TypeFreeRose else TypeFreeEmerald
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = statusColor.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.40f))
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupModuleFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            }
        } else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

// =============================================================================
// Shared Reusable Field & Model Discovery Components
// =============================================================================

@Composable
private fun SyncedOutlinedField(
    externalValue: String,
    onValueCommit: (String) -> Unit,
    label: String,
    placeholder: String
) {
    var localText by remember { mutableStateOf(externalValue) }
    LaunchedEffect(externalValue) {
        if (localText != externalValue) {
            localText = externalValue
        }
    }

    OutlinedTextField(
        value = localText,
        onValueChange = { updated ->
            localText = updated
            onValueCommit(updated)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        label = { Text(label) },
        placeholder = { Text(placeholder) }
    )
}

@Composable
private fun SecretApiKeyField(
    externalKey: String,
    onKeyCommit: (String) -> Unit,
    label: String,
    isLocalUrl: Boolean,
    onPasteClipboard: () -> String
) {
    var localKey by remember { mutableStateOf(externalKey) }
    var keyVisible by remember { mutableStateOf(false) }

    LaunchedEffect(externalKey) {
        if (localKey != externalKey) {
            localKey = externalKey
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = localKey,
            onValueChange = { updated ->
                localKey = updated
                onKeyCommit(updated)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            label = { Text(label) },
            placeholder = {
                Text(if (isLocalUrl) "局域网本地服务可留空" else "粘贴或输入密钥…")
            },
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            imageVector = if (keyVisible) TypeFreeIcons.EyeOff else TypeFreeIcons.Eye,
                            contentDescription = if (keyVisible) "隐藏密钥" else "显示密钥",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    TextButton(
                        onClick = {
                            val pasted = onPasteClipboard().trim()
                            if (pasted.isNotEmpty()) {
                                localKey = pasted
                                onKeyCommit(pasted)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("粘贴", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        )
        if (isLocalUrl && localKey.isBlank()) {
            Text(
                text = "已识别局域网 / 本地推理端点，无需填写云端 API Key 即可直接请求",
                style = MaterialTheme.typography.labelSmall,
                color = TypeFreeEmerald
            )
        }
    }
}

@Composable
private fun EndpointPreviewCaption(endpoint: String) {
    Text(
        text = "实际请求端点: $endpoint",
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun ProviderModelFieldSection(
    externalModel: String,
    onModelCommit: (String) -> Unit,
    label: String,
    placeholder: String,
    suggestions: List<String>,
    isFetching: Boolean,
    fetchError: String?,
    onFetchModels: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SyncedOutlinedField(
            externalValue = externalModel,
            onValueCommit = onModelCommit,
            label = label,
            placeholder = placeholder
        )

        FilledTonalButton(
            onClick = onFetchModels,
            enabled = !isFetching,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
        ) {
            if (isFetching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "正在拉取可用模型列表...",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "拉取可用模型并实测延迟 (ms)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        AnimatedVisibility(visible = !fetchError.isNullOrBlank()) {
            fetchError?.let { err ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = TypeFreeRose.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, TypeFreeRose.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "✕ $err",
                        style = MaterialTheme.typography.labelSmall,
                        color = TypeFreeRose,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
        }

        QuickModelChipsRow(
            suggestions = suggestions,
            currentModel = externalModel,
            onSelectModel = onModelCommit
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProviderModelPickerDialog(
    slot: ModelCapabilitySlot,
    baseUrl: String,
    apiKey: String,
    currentModel: String,
    protocol: MultimodalProtocol,
    thinkingMode: ThinkingMode,
    aiEngine: AiPipelineEngine,
    models: List<DiscoveredModelItem>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onSelectModel: (String) -> Unit
) {
    val dialogScope = rememberCoroutineScope()
    val recommendedCount = remember(models) { models.count { it.isRecommended } }
    val hasDistinctFilter = recommendedCount in 1 until models.size
    var showRecommendedOnly by remember(models) {
        mutableStateOf(hasDistinctFilter && models.size > 6)
    }
    var searchQuery by remember { mutableStateOf("") }

    var probeResults by remember(models, thinkingMode, baseUrl) {
        mutableStateOf<Map<String, ModelProbeResult>>(emptyMap())
    }
    var probingIds by remember(models, thinkingMode, baseUrl) {
        mutableStateOf<Set<String>>(emptySet())
    }

    val canAutoProbe = remember(apiKey, baseUrl) {
        apiKey.isNotBlank() || AiPipelineEngine.isLocalEndpoint(baseUrl)
    }

    fun launchBatchProbe(targetIds: List<String>) {
        val cleanIds = targetIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanIds.isEmpty()) return
        probingIds = probingIds + cleanIds
        dialogScope.launch {
            try {
                aiEngine.probeModelsBatch(
                    slot = slot,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    modelIds = cleanIds,
                    protocol = protocol,
                    thinkingMode = thinkingMode,
                    maxConcurrency = 3,
                    onEachResult = { res ->
                        dialogScope.launch {
                            probeResults = probeResults + (res.modelId to res)
                            probingIds = probingIds - res.modelId
                        }
                    }
                )
            } finally {
                probingIds = probingIds - cleanIds.toSet()
            }
        }
    }

    LaunchedEffect(models, thinkingMode, baseUrl, canAutoProbe) {
        if (!canAutoProbe || models.isEmpty()) return@LaunchedEffect
        val trimmedCurrent = currentModel.trim()
        val initialPool = models.filter { !showRecommendedOnly || it.isRecommended || it.id == trimmedCurrent }
            .ifEmpty { models }
        val autoTargets = buildList {
            if (trimmedCurrent.isNotEmpty() && models.any { it.id == trimmedCurrent }) {
                add(trimmedCurrent)
            }
            initialPool.forEach { item ->
                if (size < 8 && item.id !in this) {
                    add(item.id)
                }
            }
        }
        launchBatchProbe(autoTargets)
    }

    val filteredModels = remember(models, showRecommendedOnly, searchQuery, probeResults) {
        val q = searchQuery.trim().lowercase()
        val baseFiltered = models.filter { item ->
            val passFilter = !showRecommendedOnly || item.isRecommended || item.id == currentModel.trim()
            val passQuery = q.isEmpty() ||
                item.id.lowercase().contains(q) ||
                item.displayName.lowercase().contains(q) ||
                item.ownedBy.lowercase().contains(q)
            passFilter && passQuery
        }
        baseFiltered.sortedWith(
            compareBy<DiscoveredModelItem> { item ->
                val res = probeResults[item.id]
                when {
                    res?.latencyMs != null -> 0
                    res == null -> 1
                    else -> 2
                }
            }.thenBy { item ->
                probeResults[item.id]?.latencyMs ?: Long.MAX_VALUE
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "选择可用模型 · ${slot.label}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (slot == ModelCapabilitySlot.STT) {
                        "共 ${models.size} 个模型 · 实测短音频耗时并按延迟从低到高排序"
                    } else {
                        "共 ${models.size} 个模型 · 实测 1-Token 首包延迟（${thinkingMode.label}）自动排最前"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("搜索模型 ID（如 gemini / seed / qwen）") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "清空搜索",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else null
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (hasDistinctFilter) {
                        FilterChip(
                            selected = showRecommendedOnly,
                            onClick = { showRecommendedOnly = true },
                            label = {
                                Text(
                                    "适配模型 ($recommendedCount)",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            leadingIcon = if (showRecommendedOnly) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                        FilterChip(
                            selected = !showRecommendedOnly,
                            onClick = { showRecommendedOnly = false },
                            label = {
                                Text(
                                    "全部 (${models.size})",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            leadingIcon = if (!showRecommendedOnly) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }

                    FilterChip(
                        selected = probingIds.isNotEmpty(),
                        onClick = {
                            if (probingIds.isEmpty()) {
                                launchBatchProbe(filteredModels.take(12).map { it.id })
                            }
                        },
                        enabled = filteredModels.isNotEmpty() && probingIds.isEmpty(),
                        label = {
                            Text(
                                text = if (probingIds.isNotEmpty()) {
                                    "⚡ 测速中 (${probingIds.size})..."
                                } else {
                                    "⚡ 测速当前列表"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                if (filteredModels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "未找到匹配的模型",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (showRecommendedOnly) {
                                TextButton(onClick = { showRecommendedOnly = false }) {
                                    Text("查看全部 ${models.size} 个模型")
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredModels, key = { it.id }) { item ->
                            val isSelected = item.id == currentModel.trim()
                            val probeResult = probeResults[item.id]
                            val isProbingItem = item.id in probingIds

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectModel(item.id) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                },
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = if (isSelected) "✓ ${item.id}" else item.id,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val subMeta = buildList {
                                            if (item.badge.isNotBlank()) add(item.badge)
                                            if (item.displayName.isNotBlank()) add(item.displayName)
                                            else if (item.ownedBy.isNotBlank()) add(item.ownedBy)
                                        }.joinToString(" · ")
                                        if (subMeta.isNotBlank()) {
                                            Text(
                                                text = subMeta,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    ModelLatencyBadge(
                                        probeResult = probeResult,
                                        isProbing = isProbingItem,
                                        onTriggerProbe = {
                                            if (!isProbingItem) {
                                                launchBatchProbe(listOf(item.id))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onRefresh,
                enabled = !isRefreshing
            ) {
                Text(if (isRefreshing) "刷新中..." else "重新拉取")
            }
        }
    )
}

@Composable
private fun ModelLatencyBadge(
    probeResult: ModelProbeResult?,
    isProbing: Boolean,
    onTriggerProbe: () -> Unit
) {
    val latency = probeResult?.latencyMs
    val error = probeResult?.errorSummary

    val (bgColor, borderColor, textColor, labelText) = when {
        isProbing -> Quad(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            MaterialTheme.colorScheme.onSurfaceVariant,
            "测速中"
        )
        latency != null && latency < 400L -> Quad(
            TypeFreeEmerald.copy(alpha = 0.14f),
            TypeFreeEmerald.copy(alpha = 0.50f),
            TypeFreeEmerald,
            "⚡ $latency ms"
        )
        latency != null && latency <= 900L -> Quad(
            TypeFreeAmber.copy(alpha = 0.14f),
            TypeFreeAmber.copy(alpha = 0.50f),
            TypeFreeAmber,
            "$latency ms"
        )
        latency != null -> Quad(
            TypeFreeRose.copy(alpha = 0.12f),
            TypeFreeRose.copy(alpha = 0.45f),
            TypeFreeRose,
            "$latency ms"
        )
        error != null -> Quad(
            TypeFreeRose.copy(alpha = 0.10f),
            TypeFreeRose.copy(alpha = 0.40f),
            TypeFreeRose,
            error
        )
        else -> Quad(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            MaterialTheme.colorScheme.primary,
            "⚡ 测速"
        )
    }

    Surface(
        onClick = onTriggerProbe,
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isProbing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.6.dp,
                    color = textColor
                )
            }
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThinkingModeSelectorSection(
    thinkingMode: ThinkingMode,
    onSelectMode: (ThinkingMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "深度思考开关 (Thinking / CoT)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ThinkingMode.entries.forEach { mode ->
                val selected = thinkingMode == mode
                FilterChip(
                    selected = selected,
                    onClick = { onSelectMode(mode) },
                    label = {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
        Text(
            text = "${thinkingMode.description}（若所选模型不支持该参数，将无感自动回退并记忆）",
            style = MaterialTheme.typography.labelSmall,
            color = if (thinkingMode == ThinkingMode.DISABLED) {
                TypeFreeEmerald
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            lineHeight = 16.sp
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickModelChipsRow(
    suggestions: List<String>,
    currentModel: String,
    onSelectModel: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        suggestions.forEach { modelName ->
            val isCurrent = currentModel.trim() == modelName
            SuggestionChip(
                onClick = { onSelectModel(modelName) },
                label = {
                    Text(
                        text = if (isCurrent) "✓ $modelName" else modelName,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            )
        }
    }
}

@Composable
private fun TemperatureSliderSection(
    temperature: Float,
    model: String = "",
    onTemperatureChange: (Float) -> Unit
) {
    val isTempDeprecated = remember(model) {
        AiPipelineEngine.isSamplingTemperatureDeprecated(model)
    }
    val thinkingLevel = remember(model) {
        AiPipelineEngine.recommendedGeminiThinkingLevel(model)
    }

    if (isTempDeprecated) {
        Text(
            text = if (thinkingLevel != null) {
                "当前模型（${model.trim()}）自动管理采样温度与思考档位，无需手动调节。"
            } else {
                "当前模型（${model.trim()}）自动管理采样参数，无需手动调节温度。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "采样温度 (Temperature)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = String.format(Locale.US, "%.2f", temperature),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = temperature.coerceIn(0.0f, 1.0f),
                onValueChange = { raw ->
                    val stepped = (raw * 20f).roundToInt() / 20f
                    onTemperatureChange(stepped.coerceIn(0.0f, 1.0f))
                },
                valueRange = 0.0f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
            Text(
                text = "建议保持在 0.10 ~ 0.30，数值越低越忠实还原口述原意，避免模型自由发挥",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun readFromClipboard(context: Context): String {
    return try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        } else {
            ""
        }
    } catch (_: Exception) {
        ""
    }
}

private fun writeToClipboard(context: Context, label: String, text: String) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    } catch (_: Exception) {
    }
}
