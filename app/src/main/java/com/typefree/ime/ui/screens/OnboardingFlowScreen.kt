package com.typefree.ime.ui.screens

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.typefree.core.offline.LocalModelInstallStatus
import com.typefree.core.offline.OfflineMode
import com.typefree.core.offline.OfflineModelCatalog
import com.typefree.core.offline.RamTier
import com.typefree.core.offline.ReadinessState
import com.typefree.ime.MainActivity
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.ModelCapabilitySlot
import com.typefree.ime.data.MultimodalProtocol
import com.typefree.ime.data.ProviderCredential
import com.typefree.ime.data.UnifiedProviderCatalog
import com.typefree.ime.data.UnifiedProviderSpec
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ime.engine.PresetSchemaCatalog
import com.typefree.ime.localai.DeviceRamTierResolver
import com.typefree.ime.localai.LocalInferenceEngineRegistry
import com.typefree.ime.localai.ModelDownloadWorker
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.ui.components.ContinuousRoundedCornerShape
import com.typefree.ime.ui.components.MochiSelectCard
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.components.rememberLocalModelRegistrySnapshot
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class OnboardingTypingChoice {
    ENGLISH_QWERTY,
    PINYIN_26,
    PINYIN_T9,
    DOUBLE_FLYPY,
    CODETABLE
}

private enum class OnboardingVoiceRoute {
    LOCAL_OFFLINE,
    CLOUD_API
}

private enum class OnboardingStep3Result {
    VOICE_READY,
    OFFLINE_DOWNLOADING,
    SKIPPED_TYPING_ONLY
}

object OnboardingProviderOrderHelper {
    fun orderedCloudProviders(preferDomesticOrder: Boolean = isSimplifiedChineseLocale()): List<UnifiedProviderSpec> {
        val preferredIds = if (preferDomesticOrder) {
            listOf(
                UnifiedProviderCatalog.ID_DEEPSEEK,
                UnifiedProviderCatalog.ID_VOLCENGINE,
                UnifiedProviderCatalog.ID_ALIYUN,
                UnifiedProviderCatalog.ID_SILICONFLOW,
                UnifiedProviderCatalog.ID_GOOGLE,
                UnifiedProviderCatalog.ID_CUSTOM
            )
        } else {
            listOf(
                UnifiedProviderCatalog.ID_GOOGLE,
                UnifiedProviderCatalog.ID_DEEPSEEK,
                UnifiedProviderCatalog.ID_CUSTOM,
                UnifiedProviderCatalog.ID_ALIYUN,
                UnifiedProviderCatalog.ID_VOLCENGINE,
                UnifiedProviderCatalog.ID_SILICONFLOW
            )
        }
        val byId = UnifiedProviderCatalog.providers.associateBy { it.id }
        return preferredIds.mapNotNull { byId[it] }
    }

    fun isSimplifiedChineseLocale(locale: Locale = Locale.getDefault()): Boolean {
        val lang = locale.language.lowercase(Locale.US)
        val country = locale.country.uppercase(Locale.US)
        return lang == "zh" && (country == "CN" || country.isEmpty())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingFlowScreen(
    repository: AppRepository,
    aiEngine: AiPipelineEngine,
    preferDomesticProviders: Boolean = OnboardingProviderOrderHelper.isSimplifiedChineseLocale(),
    privacyFooterSlot: (@Composable () -> Unit)? = null,
    onCompleteOnboarding: () -> Unit
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val effectivePreferDomestic = preferDomesticProviders && appLang == AppLang.ZH_CN
    val lifecycleOwner = LocalLifecycleOwner.current
    val windowInfo = LocalWindowInfo.current
    val scope = rememberCoroutineScope()

    val settings by repository.settings.collectAsState()
    val registrySnapshot = rememberLocalModelRegistrySnapshot()
    val rimeDataManager = remember { TypeFreeApp.instance.rimeDataManager }

    var isImeEnabled by remember { mutableStateOf(isSystemImeEnabled(context)) }
    var isImeSelected by remember { mutableStateOf(isSystemImeSelected(context)) }
    var hasMicPermission by remember { mutableStateOf(hasMicRecordPermission(context)) }

    var currentStep by rememberSaveable {
        mutableIntStateOf(if (isSystemImeEnabled(context) && isSystemImeSelected(context)) 1 else 0)
    }
    var waitingForSystemImeEnable by rememberSaveable { mutableStateOf(false) }
    var autoTriggerPickerPending by rememberSaveable { mutableStateOf(false) }
    var hasAutoAdvancedFromStep1 by rememberSaveable { mutableStateOf(false) }

    // Step 2 state
    var selectedTypingChoice by rememberSaveable(appLang == AppLang.EN) {
        mutableStateOf(
            if (appLang == AppLang.EN) OnboardingTypingChoice.ENGLISH_QWERTY else OnboardingTypingChoice.PINYIN_26
        )
    }
    val orderedPresets = remember {
        val priority = listOf("tigress", "tiger", "wubi86", "xkjd6")
        PresetSchemaCatalog.BUILT_IN_PRESETS.sortedBy { p ->
            val idx = priority.indexOf(p.id)
            if (idx >= 0) idx else 99
        }
    }
    var selectedPresetId by rememberSaveable {
        mutableStateOf(orderedPresets.firstOrNull()?.id ?: "tigress")
    }
    var customZipImportedLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var isApplyingScheme by remember { mutableStateOf(false) }

    // Step 3 state
    val ramTier = remember(context) { DeviceRamTierResolver.resolveRamTier(context) }
    val canDownload08b = remember {
        LocalInferenceEngineRegistry.hasDownloadableSlmSource(OfflineModelCatalog.ID_SLM_CORE_0_8B)
    }
    var selectedVoiceRoute by rememberSaveable { mutableStateOf<OnboardingVoiceRoute?>(null) }
    var include08bPolish by rememberSaveable {
        mutableStateOf(ramTier != RamTier.LOW_6GB)
    }
    val orderedCloudSpecs = remember(effectivePreferDomestic) {
        OnboardingProviderOrderHelper.orderedCloudProviders(effectivePreferDomestic)
    }
    var selectedCloudProviderId by rememberSaveable(effectivePreferDomestic) {
        mutableStateOf(orderedCloudSpecs.firstOrNull()?.id ?: UnifiedProviderCatalog.ID_DEEPSEEK)
    }
    var cloudApiKeyInput by rememberSaveable { mutableStateOf("") }
    var cloudCustomBaseUrl by rememberSaveable { mutableStateOf("") }
    var showApiKeyPlain by rememberSaveable { mutableStateOf(false) }
    var isVerifyingCloudKey by remember { mutableStateOf(false) }
    var cloudVerifyError by remember { mutableStateOf<String?>(null) }
    var showKeyGuideDialog by remember { mutableStateOf(false) }

    // Step 4 state
    var step3Result by rememberSaveable { mutableStateOf(OnboardingStep3Result.SKIPPED_TYPING_ONLY) }
    var trialText by rememberSaveable { mutableStateOf("") }
    var pendingAfterMicAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        val action = pendingAfterMicAction
        pendingAfterMicAction = null
        action?.invoke()
    }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isApplyingScheme = true
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            rimeDataManager.importEnhancedSchemaPackage(stream)
                        } ?: throw IllegalArgumentException("无法读取所选方案包")
                    }
                    TypeFreeApp.instance.rimeInputEngine.setInputScheme(InputScheme.RIME_CUSTOM)
                    repository.updateSettings { it.copy(inputScheme = InputScheme.RIME_CUSTOM) }
                    val name = imported.displayName.ifBlank { "自定义方案" }
                    customZipImportedLabel = name
                    selectedPresetId = "__imported_zip__"
                    Toast.makeText(context, "✓ 已导入「$name」", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "导入失败：${e.message ?: "格式不支持"}", Toast.LENGTH_LONG).show()
                } finally {
                    isApplyingScheme = false
                }
            }
        }
    }

    fun refreshSystemImeStatus() {
        isImeEnabled = isSystemImeEnabled(context)
        isImeSelected = isSystemImeSelected(context)
        hasMicPermission = hasMicRecordPermission(context)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshSystemImeStatus()
                waitingForSystemImeEnable = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(windowInfo.isWindowFocused) {
        if (windowInfo.isWindowFocused) {
            refreshSystemImeStatus()
            if (currentStep == 0 && autoTriggerPickerPending && isImeEnabled && !isImeSelected) {
                autoTriggerPickerPending = false
                delay(250L)
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
            }
        }
    }

    // Active 300ms foreground watcher while user is inside System Input Method Settings
    LaunchedEffect(waitingForSystemImeEnable) {
        if (!waitingForSystemImeEnable) return@LaunchedEffect
        repeat(300) {
            delay(300L)
            if (isSystemImeEnabled(context)) {
                isImeEnabled = true
                waitingForSystemImeEnable = false
                autoTriggerPickerPending = true
                runCatching {
                    val backToApp = Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }
                    context.startActivity(backToApp)
                }
                return@LaunchedEffect
            }
        }
        waitingForSystemImeEnable = false
    }

    // Auto-advance from Step 1 to Step 2 once both enabled & selected
    LaunchedEffect(currentStep, isImeEnabled, isImeSelected) {
        if (currentStep == 0 && isImeEnabled && isImeSelected && !hasAutoAdvancedFromStep1) {
            hasAutoAdvancedFromStep1 = true
            delay(400L)
            currentStep = 1
        }
    }

    BackHandler(enabled = currentStep > 0) {
        currentStep = (currentStep - 1).coerceAtLeast(0)
    }

    val readiness = remember(settings, registrySnapshot) {
        SetupStatsHelper.resolveReadiness(settings, registrySnapshot)
    }

    fun finishOnboarding() {
        val isReadyNow = SetupStatsHelper.resolveReadiness(repository.settings.value, registrySnapshot) !is ReadinessState.NotReady
        repository.updateSettings { curr ->
            curr.copy(
                onboardingCompleted = true,
                lastKeyboardTab = if (isReadyNow) "VOICE" else "QWERTY"
            )
        }
        onCompleteOnboarding()
    }

    val stepProgress by animateFloatAsState(
        targetValue = (currentStep + 1) / 4f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "onboardingProgress"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Pinned Top Bar (Always above soft keyboard in Step 4)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (currentStep > 0) {
                        TextButton(
                            onClick = { currentStep = (currentStep - 1).coerceAtLeast(0) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = tr("上一步", "Back", "上一步"),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(tr("上一步", "Back", "上一步"), style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.LogoMark,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "TypeFree",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "${currentStep + 1} / 4",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                        )
                    }
                }

                when (currentStep) {
                    2 -> {
                        TextButton(
                            onClick = {
                                repository.updateSettings { it.copy(lastKeyboardTab = "QWERTY") }
                                step3Result = OnboardingStep3Result.SKIPPED_TYPING_ONLY
                                currentStep = 3
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = tr("先纯打字 ›", "Typing Only ›", "先純打字 ›"),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    3 -> {
                        Button(
                            onClick = { finishOnboarding() },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = tr("完成，进入首页", "Finish", "完成，進入首頁"),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            LinearProgressIndicator(
                progress = { stepProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        // Animated Step Content
        AnimatedContent(
            targetState = currentStep,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            transitionSpec = {
                val forward = targetState >= initialState
                val slideSpec = spring<androidx.compose.ui.unit.IntOffset>(
                    dampingRatio = 0.86f,
                    stiffness = Spring.StiffnessMediumLow
                )
                if (forward) {
                    (slideInHorizontally(slideSpec) { it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally(slideSpec) { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally(slideSpec) { -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally(slideSpec) { it / 3 } + fadeOut())
                }
            },
            label = "onboardingStepTransition"
        ) { step ->
            when (step) {
                0 -> OnboardingStep1EnableAndSwitch(
                    isImeEnabled = isImeEnabled,
                    isImeSelected = isImeSelected,
                    privacyFooterSlot = privacyFooterSlot,
                    onOpenSystemImeSettings = {
                        waitingForSystemImeEnable = true
                        autoTriggerPickerPending = true
                        openImeSettings(context)
                    },
                    onShowImePicker = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showInputMethodPicker()
                    },
                    onManualNext = { currentStep = 1 }
                )

                1 -> OnboardingStep2TypingScheme(
                    selectedChoice = selectedTypingChoice,
                    onSelectChoice = { selectedTypingChoice = it },
                    presets = orderedPresets,
                    selectedPresetId = selectedPresetId,
                    importedZipLabel = customZipImportedLabel,
                    isApplying = isApplyingScheme,
                    onSelectPreset = { id -> selectedPresetId = id },
                    onPickLocalZip = {
                        zipPickerLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    onConfirmNext = {
                        isApplyingScheme = true
                        scope.launch {
                            try {
                                when (selectedTypingChoice) {
                                    OnboardingTypingChoice.ENGLISH_QWERTY -> {
                                        repository.updateSettings {
                                            it.copy(
                                                flypyEnabled = false,
                                                lastQwertyIsChinese = false,
                                                englishAutoCap = true,
                                                englishAutoCorrect = true
                                            )
                                        }
                                    }
                                    OnboardingTypingChoice.PINYIN_26 -> applyBuiltinScheme(repository, rimeDataManager, InputScheme.RIME_PINYIN)
                                    OnboardingTypingChoice.PINYIN_T9 -> applyBuiltinScheme(repository, rimeDataManager, InputScheme.RIME_T9_PINYIN)
                                    OnboardingTypingChoice.DOUBLE_FLYPY -> applyBuiltinScheme(repository, rimeDataManager, InputScheme.RIME_DOUBLE_PINYIN_FLYPY)
                                    OnboardingTypingChoice.CODETABLE -> {
                                        if (selectedPresetId != "__imported_zip__") {
                                            val preset = orderedPresets.firstOrNull { it.id == selectedPresetId }
                                            if (preset != null) {
                                                withContext(Dispatchers.IO) {
                                                    rimeDataManager.installPresetSchema(context, preset.assetPath)
                                                }
                                            }
                                        }
                                        TypeFreeApp.instance.rimeInputEngine.setInputScheme(InputScheme.RIME_CUSTOM)
                                        repository.updateSettings {
                                            it.copy(
                                                inputScheme = InputScheme.RIME_CUSTOM,
                                                flypyEnabled = true,
                                                lastQwertyIsChinese = true
                                            )
                                        }
                                    }
                                }
                                currentStep = 2
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    AppStrings.pick(appLang, "切换方案失败：${e.message}", "Failed to switch layout: ${e.message}", "切換方案失敗：${e.message}"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } finally {
                                isApplyingScheme = false
                            }
                        }
                    }
                )

                2 -> OnboardingStep3VoiceAndAiRoute(
                    selectedRoute = selectedVoiceRoute,
                    onSelectRoute = {
                        selectedVoiceRoute = it
                        cloudVerifyError = null
                    },
                    ramTier = ramTier,
                    canDownload08b = canDownload08b,
                    include08bPolish = include08bPolish,
                    onToggle08bPolish = { include08bPolish = it },
                    cloudSpecs = orderedCloudSpecs,
                    selectedCloudProviderId = selectedCloudProviderId,
                    onSelectCloudProvider = { pid ->
                        selectedCloudProviderId = pid
                        val savedCred = UnifiedProviderCatalog.hydrateVaultFromSettings(settings)[pid]
                        cloudApiKeyInput = savedCred?.apiKey.orEmpty()
                        cloudCustomBaseUrl = savedCred?.customBaseUrl.orEmpty()
                        cloudVerifyError = null
                    },
                    apiKeyInput = cloudApiKeyInput,
                    onApiKeyChange = {
                        cloudApiKeyInput = it
                        cloudVerifyError = null
                    },
                    customBaseUrl = cloudCustomBaseUrl,
                    onCustomBaseUrlChange = { cloudCustomBaseUrl = it },
                    showApiKeyPlain = showApiKeyPlain,
                    onToggleShowKey = { showApiKeyPlain = !showApiKeyPlain },
                    isVerifyingKey = isVerifyingCloudKey,
                    verifyError = cloudVerifyError,
                    onPasteKey = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                        if (clip.isNotEmpty()) {
                            cloudApiKeyInput = clip
                            cloudVerifyError = null
                        } else {
                            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenKeyGuide = { showKeyGuideDialog = true },
                    onActivateOffline = {
                        val proceedOffline = {
                            val asrId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL
                            val slmId = OfflineModelCatalog.ID_SLM_CORE_0_8B
                            val asrInstalled = registrySnapshot.isInstalled(asrId)
                            val wantSlm = include08bPolish && (canDownload08b || registrySnapshot.isInstalled(slmId))
                            val slmInstalled = registrySnapshot.isInstalled(slmId)

                            if (!asrInstalled) {
                                runCatching {
                                    ModelDownloadWorker.enqueueDownload(context, asrId, allowDeleteExistingFirst = false)
                                }
                            }
                            if (wantSlm && !slmInstalled && canDownload08b) {
                                runCatching {
                                    ModelDownloadWorker.enqueueDownload(context, slmId, allowDeleteExistingFirst = false)
                                }
                            }

                            val enableSlmSlot = wantSlm && registrySnapshot.slmEngineAvailable
                            repository.updateSettings { curr ->
                                curr.copy(
                                    llmConfig = curr.llmConfig.copy(enabled = enableSlmSlot),
                                    offlineConfig = curr.offlineConfig.copy(
                                        mode = OfflineMode.OFFLINE_ONLY,
                                        asrSlotOn = true,
                                        slmSlotOn = enableSlmSlot,
                                        selectedAsrModelId = asrId,
                                        selectedSlmModelId = if (enableSlmSlot) slmId else curr.offlineConfig.selectedSlmModelId
                                    ),
                                    lastKeyboardTab = if (asrInstalled) "VOICE" else "QWERTY"
                                )
                            }
                            step3Result = if (asrInstalled) {
                                OnboardingStep3Result.VOICE_READY
                            } else {
                                OnboardingStep3Result.OFFLINE_DOWNLOADING
                            }
                            currentStep = 3
                        }

                        if (hasMicRecordPermission(context)) {
                            proceedOffline()
                        } else {
                            pendingAfterMicAction = proceedOffline
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onVerifyAndActivateCloud = {
                        val cleanKey = cloudApiKeyInput.trim()
                        val isCustom = selectedCloudProviderId == UnifiedProviderCatalog.ID_CUSTOM
                        val cleanUrl = cloudCustomBaseUrl.trim().ifBlank { "https://api.openai.com/v1" }
                        val isLocalUrl = isCustom && AiPipelineEngine.isLocalEndpoint(cleanUrl)
                        if (cleanKey.isEmpty() && !isLocalUrl) {
                            cloudVerifyError = AppStrings.pick(
                                appLang,
                                "请先粘贴对应平台的 API Key",
                                "Please paste a valid API Key first",
                                "請先貼上對應平台的 API Key"
                            )
                            return@OnboardingStep3VoiceAndAiRoute
                        }

                        isVerifyingCloudKey = true
                        cloudVerifyError = null
                        scope.launch {
                            val spec = UnifiedProviderCatalog.specFor(selectedCloudProviderId)
                            val probeBaseUrl = if (isCustom) cleanUrl else spec.defaultBaseUrl
                            val probeRes = aiEngine.fetchAvailableModels(
                                slot = ModelCapabilitySlot.LLM,
                                baseUrl = probeBaseUrl,
                                apiKey = cleanKey,
                                protocol = spec.defaultMultimodalProtocol.takeIf { spec.supportsMultimodal }
                                    ?: MultimodalProtocol.OPENAI_AUDIO_CHAT
                            )

                            val errText = probeRes.exceptionOrNull()?.message.orEmpty()
                            val isHardAuthError = errText.contains("401") ||
                                errText.contains("403") ||
                                errText.contains("invalid", ignoreCase = true) ||
                                errText.contains("API key not valid", ignoreCase = true)

                            isVerifyingCloudKey = false
                            if (probeRes.isFailure && isHardAuthError) {
                                cloudVerifyError = AppStrings.pick(
                                    appLang,
                                    "密钥校验未通过，请确认 API Key 是否完整有效（$errText）",
                                    "API key verification failed — please check your key ($errText)",
                                    "金鑰校驗未通過，請確認 API Key 是否完整有效（$errText）"
                                )
                                return@launch
                            }

                            val proceedCloud = {
                                repository.updateSettings { curr ->
                                    val cred = ProviderCredential(
                                        providerId = selectedCloudProviderId,
                                        apiKey = cleanKey,
                                        customBaseUrl = if (isCustom) cleanUrl else curr.providerVault[selectedCloudProviderId]?.customBaseUrl.orEmpty()
                                    )
                                    val withCred = UnifiedProviderCatalog.updateProviderCredential(curr, cred)
                                    val configured = UnifiedProviderCatalog.applyQuickProviderSetup(
                                        settings = withCred,
                                        providerId = selectedCloudProviderId,
                                        keepExistingThirdPartyStt = false
                                    )
                                    configured.copy(
                                        offlineConfig = curr.offlineConfig.copy(
                                            mode = OfflineMode.OFF,
                                            asrSlotOn = false,
                                            slmSlotOn = false
                                        ),
                                        lastKeyboardTab = "VOICE"
                                    )
                                }
                                step3Result = OnboardingStep3Result.VOICE_READY
                                currentStep = 3
                            }

                            if (hasMicRecordPermission(context)) {
                                proceedCloud()
                            } else {
                                pendingAfterMicAction = proceedCloud
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                )

                else -> OnboardingStep4LiveTrial(
                    step3Result = step3Result,
                    readiness = readiness,
                    registrySnapshot = registrySnapshot,
                    trialText = trialText,
                    onTrialTextChange = { trialText = it },
                    onPromoteToVoiceTabWhenReady = {
                        repository.updateSettings { it.copy(lastKeyboardTab = "VOICE") }
                    }
                )
            }
        }
    }
    }

    if (showKeyGuideDialog) {
        ApiKeyQuickGuideDialog(
            preferDomestic = effectivePreferDomestic,
            onDismiss = { showKeyGuideDialog = false }
        )
    }
}

private suspend fun applyBuiltinScheme(
    repository: AppRepository,
    rimeDataManager: com.typefree.ime.ime.engine.RimeDataManager,
    scheme: InputScheme
) {
    repository.updateSettings {
        it.copy(
            inputScheme = scheme,
            flypyEnabled = true,
            lastQwertyIsChinese = true
        )
    }
    withContext(Dispatchers.IO) {
        runCatching {
            if (rimeDataManager.isSchemeHandledByEnhancedEngine(scheme)) {
                rimeDataManager.activateEnhancedSchemeSync(scheme)
            }
            TypeFreeApp.instance.rimeInputEngine.setInputScheme(scheme)
        }
    }
}

@Composable
private fun OnboardingStep1EnableAndSwitch(
    isImeEnabled: Boolean,
    isImeSelected: Boolean,
    privacyFooterSlot: (@Composable () -> Unit)?,
    onOpenSystemImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
    onManualNext: () -> Unit
) {
    val toggleTrackColor by animateColorAsState(
        targetValue = if (isImeEnabled) TypeFreeEmerald else MaterialTheme.colorScheme.primary,
        label = "imeDemoToggleColor"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = when {
                        !isImeEnabled -> tr("第一步，启用灵言键盘", "Step 1: Enable TypeFree Keyboard", "第一步，啟用靈言鍵盤")
                        !isImeSelected -> tr("最后一下，设为当前输入法", "Almost there: Select TypeFree", "最後一下，設為目前輸入法")
                        else -> tr("✓ 已设为当前默认输入法", "✓ TypeFree is now active", "✓ 已設為目前預設輸入法")
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = when {
                        !isImeEnabled -> tr(
                            "在系统输入法列表中打开「灵言 TypeFree」，开启后会自动返回这里",
                            "Toggle on \"TypeFree\" in Android Keyboard Settings — you'll return here automatically.",
                            "在系統輸入法清單中打開「TypeFree」，開啟後會自動返回這裡"
                        )
                        !isImeSelected -> tr(
                            "在弹出的菜单中选择「灵言 TypeFree」",
                            "Choose \"TypeFree\" in the system keyboard picker.",
                            "在彈出的選單中選擇「TypeFree」"
                        )
                        else -> tr("键盘已就绪，正在进入下一步…", "Keyboard ready — advancing to next step…", "鍵盤已就緒，正在進入下一步…")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 21.sp
                )
            }

            // Minimalist Android System Settings Toggle Preview Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = ContinuousRoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (!isImeEnabled) {
                            tr("系统键盘管理列表示意", "System On-Screen Keyboard List", "系統鍵盤管理清單示意")
                        } else {
                            tr("默认输入法状态", "Default Keyboard Status", "預設輸入法狀態")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = ContinuousRoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.2.dp, toggleTrackColor.copy(alpha = 0.55f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = TypeFreeIcons.LogoMark,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = tr("灵言 TypeFree", "TypeFree (AI Voice Keyboard)", "TypeFree"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = when {
                                            isImeEnabled && isImeSelected -> tr("已启用并设为默认键盘", "Enabled & set as default", "已啟用並設為預設鍵盤")
                                            isImeEnabled -> tr("已启用 · 待选为当前键盘", "Enabled · Tap below to select", "已啟用 · 待選為目前鍵盤")
                                            else -> tr("找到此项并打开右侧开关", "Find this item and toggle on", "找到此項並打開右側開關")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isImeEnabled) TypeFreeEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .width(46.dp)
                                    .height(26.dp)
                                    .clip(CircleShape)
                                    .background(toggleTrackColor)
                                    .padding(3.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                }
            }

            if (!isImeEnabled) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ContinuousRoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
                ) {
                    Text(
                        text = tr(
                            "🔒 开启时安卓系统会弹出通用安全例行提醒（所有第三方输入法均会显示）。TypeFree 无后台收集服务器，且支持纯离线零出网模式，可放心开启。",
                            "🔒 Android displays a standard system notice for every third-party keyboard. TypeFree has zero developer telemetry servers and supports 100% offline air-gapped speech & AI.",
                            "🔒 開啟時 Android 系統會彈出一般安全提醒（所有第三方輸入法皆會顯示）。TypeFree 無背景收集伺服器，且支援純離線零連網模式，請放心開啟。"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            privacyFooterSlot?.invoke()

            when {
                !isImeEnabled -> {
                    Button(
                        onClick = onOpenSystemImeSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = ContinuousRoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = tr("去系统设置开启", "Open Keyboard Settings", "去系統設定開啟"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                !isImeSelected -> {
                    Button(
                        onClick = onShowImePicker,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = ContinuousRoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = tr("调出键盘选择菜单", "Select TypeFree Keyboard", "叫出鍵盤選擇選單"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                else -> {
                    Button(
                        onClick = onManualNext,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = ContinuousRoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TypeFreeEmerald,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tr("下一步", "Next", "下一步"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingStep2TypingScheme(
    selectedChoice: OnboardingTypingChoice,
    onSelectChoice: (OnboardingTypingChoice) -> Unit,
    presets: List<PresetSchemaCatalog.PresetSchemaInfo>,
    selectedPresetId: String,
    importedZipLabel: String?,
    isApplying: Boolean,
    onSelectPreset: (String) -> Unit,
    onPickLocalZip: () -> Unit,
    onConfirmNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        val appLang = LocalAppLanguage.current

        @Composable
        fun EnglishQwertyOptionCard() {
            MochiSelectCard(
                selected = selectedChoice == OnboardingTypingChoice.ENGLISH_QWERTY,
                onClick = { onSelectChoice(OnboardingTypingChoice.ENGLISH_QWERTY) },
                accentColor = TypeFreeEmerald,
                headerContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = tr("英文 QWERTY（智能纠错 + AI 语音）", "English QWERTY (Smart Autocorrect + AI Voice)", "英文 QWERTY（智慧糾錯 + AI 語音）"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (appLang == AppLang.EN) {
                                Surface(
                                    shape = CircleShape,
                                    color = TypeFreeEmerald.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = "Recommended",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TypeFreeEmerald,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = tr(
                                "智能句首大写 · dont→don't 自动缩写纠错 · 单退格撤销 · 随时一键切中文",
                                "Smart auto-capitalization · Contractions & typo autocorrect · 1-tap Backspace undo",
                                "智慧句首大寫 · 自動縮寫糾錯 · 單退格復原 · 隨時一鍵切中文"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tr("选择你习惯的打字方式", "Choose Your Primary Keyboard", "選擇您習慣的打字方式"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = tr(
                        "后续随时可在「键盘」页切换，或导入外部 Rime 方案包",
                        "Switch between English and Bilingual Chinese/Rime layouts anytime in Keyboard settings.",
                        "後續隨時可在「鍵盤」頁切換，或匯入外部 Rime 方案包"
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            if (appLang == AppLang.EN) {
                EnglishQwertyOptionCard()
            }

            MochiSelectCard(
                selected = selectedChoice == OnboardingTypingChoice.PINYIN_26,
                onClick = { onSelectChoice(OnboardingTypingChoice.PINYIN_26) },
                headerContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = tr("26 键全拼", "Bilingual Pinyin (26-Key QWERTY)", "26 鍵全拼"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (appLang != AppLang.EN) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = tr("默认", "Default", "預設"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = tr("整句连打 · 动态词频联想", "Full-sentence Chinese Pinyin + 1-tap EN toggle", "整句連打 · 動態詞頻聯想"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )

            MochiSelectCard(
                selected = selectedChoice == OnboardingTypingChoice.PINYIN_T9,
                onClick = { onSelectChoice(OnboardingTypingChoice.PINYIN_T9) },
                headerContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = tr("九键拼音", "9-Key Pinyin (T9)", "九鍵拼音"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr("单手大键位 · 拇指盲打不易误触", "Large one-handed T9 keys for thumb typing", "單手大鍵位 · 拇指盲打不易誤觸"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )

            MochiSelectCard(
                selected = selectedChoice == OnboardingTypingChoice.DOUBLE_FLYPY,
                onClick = { onSelectChoice(OnboardingTypingChoice.DOUBLE_FLYPY) },
                headerContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = tr("小鹤双拼", "Double Pinyin (Flypy Shuangpin)", "小鶴雙拼"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr("两键一字 · 延续双拼肌肉记忆", "2-stroke Chinese syllable input for Shuangpin users", "兩鍵一字 · 延續雙拼肌肉記憶"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )

            MochiSelectCard(
                selected = selectedChoice == OnboardingTypingChoice.CODETABLE,
                onClick = { onSelectChoice(OnboardingTypingChoice.CODETABLE) },
                headerContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = tr("五笔 / 虎码 / 形码方案", "Rime Codetable (Wubi / Tiger / Custom)", "五筆 / 虎碼 / 形碼方案"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr("定长顶屏 · 零重码肌肉记忆", "Fixed-length shape codes or custom Rime .zip schemas", "定長頂屏 · 零重碼肌肉記憶"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                expandedContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            presets.forEachIndexed { idx, preset ->
                                val chipLabel = if (idx == 0 && preset.id == "tigress") {
                                    tr("虎码官方字词（默认）", "Tiger Code (Default)", "虎碼官方字詞（預設）")
                                } else {
                                    preset.displayName
                                }
                                val isChosen = selectedPresetId == preset.id
                                FilterChip(
                                    selected = isChosen,
                                    onClick = { onSelectPreset(preset.id) },
                                    label = {
                                        Text(
                                            text = chipLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }

                            val zipChosen = selectedPresetId == "__imported_zip__"
                            FilterChip(
                                selected = zipChosen,
                                onClick = onPickLocalZip,
                                label = {
                                    Text(
                                        text = if (zipChosen && !importedZipLabel.isNullOrBlank()) {
                                            tr("已导入：$importedZipLabel", "Imported: $importedZipLabel", "已匯入：$importedZipLabel")
                                        } else {
                                            tr("导入本地 .zip 方案包", "Import .zip Schema", "匯入本機 .zip 方案包")
                                        },
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TypeFreeEmerald,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            )

            if (appLang != AppLang.EN) {
                EnglishQwertyOptionCard()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp)
        ) {
            Button(
                onClick = onConfirmNext,
                enabled = !isApplying,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = ContinuousRoundedCornerShape(16.dp)
            ) {
                if (isApplying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(tr("正在部署方案…", "Applying keyboard…", "正在部署方案…"), fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        text = tr("下一步", "Next", "下一步"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingStep3VoiceAndAiRoute(
    selectedRoute: OnboardingVoiceRoute?,
    onSelectRoute: (OnboardingVoiceRoute) -> Unit,
    ramTier: RamTier,
    canDownload08b: Boolean,
    include08bPolish: Boolean,
    onToggle08bPolish: (Boolean) -> Unit,
    cloudSpecs: List<UnifiedProviderSpec>,
    selectedCloudProviderId: String,
    onSelectCloudProvider: (String) -> Unit,
    apiKeyInput: String,
    onApiKeyChange: (String) -> Unit,
    customBaseUrl: String,
    onCustomBaseUrlChange: (String) -> Unit,
    showApiKeyPlain: Boolean,
    onToggleShowKey: () -> Unit,
    isVerifyingKey: Boolean,
    verifyError: String?,
    onPasteKey: () -> Unit,
    onOpenKeyGuide: () -> Unit,
    onActivateOffline: () -> Unit,
    onVerifyAndActivateCloud: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = tr("选择语音与 AI 驱动方式", "Choose Your Voice & AI Engine", "選擇語音與 AI 驅動方式"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = tr(
                    "均由手机本机直连处理，无第三方中转；后续随时可在设置中切换或混搭",
                    "Runs directly on your phone or connects straight to your own API key — zero middleman servers.",
                    "均由手機本機直連處理，無第三方中轉；後續隨時可在設定中切換或混搭"
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }

        // Route Card 1: Local Offline Engine
        MochiSelectCard(
            selected = selectedRoute == OnboardingVoiceRoute.LOCAL_OFFLINE,
            onClick = { onSelectRoute(OnboardingVoiceRoute.LOCAL_OFFLINE) },
            accentColor = TypeFreeEmerald,
            headerContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = tr("📱 本机离线引擎", "📱 On-Device Offline AI", "📱 本機離線引擎"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = CircleShape,
                            color = TypeFreeEmerald.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = tr("免 Key · 零费用", "No API Key · 100% Free", "免 Key · 零費用"),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = TypeFreeEmerald,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    ProsConsLine(
                        tag = tr("优势", "Pros", "優勢"),
                        tagColor = TypeFreeEmerald,
                        text = tr(
                            "免注册填 Key、永久免费、断网可用，录音与文字 100% 不出手机",
                            "Zero sign-up, works offline in airplane mode (Moonshine / Whisper / SenseVoice + Qwen 0.8B); audio never leaves your device.",
                            "免註冊填 Key、永久免費、斷網可用，錄音與文字 100% 不出手機"
                        )
                    )
                    ProsConsLine(
                        tag = tr("局限", "Note", "注意"),
                        tagColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = tr(
                            "需下载约 163MB~680MB 模型包；长难句改写、外语互译与问 AI 深度推理弱于云端大模型",
                            "Downloads ~163MB–680MB once (more models like Moonshine & Whisper available in Model Hub); cloud LLMs remain stronger for deep rewrites & Ask AI.",
                            "需下載約 163MB~680MB 模型包；長難句改寫、外語互譯與問 AI 深度推理弱於雲端大模型"
                        )
                    )
                }
            },
            expandedContent = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = true, onCheckedChange = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = tr(
                                "极速离线听音（SenseVoice · 163MB，必选，中/粤/英多语转写）",
                                "Fast On-Device Speech (SenseVoice · 163MB, English + Multilingual ASR)",
                                "極速離線聽音（SenseVoice · 163MB，必選，中/粵/英多語轉寫）"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (canDownload08b) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onToggle08bPolish(!include08bPolish) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = include08bPolish,
                                    onCheckedChange = onToggle08bPolish
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = tr(
                                        "端侧去口癖净写（Qwen 0.8B · 520MB，免费，自动删口头禅与改口纠错）",
                                        "On-Device AI Polish (Qwen 0.8B · 520MB, Free, removes fillers & merges self-corrections)",
                                        "本機去口頭禪潤飾（Qwen 0.8B · 520MB，免費，自動刪口頭禪與改口糾錯）"
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            if (ramTier == RamTier.LOW_6GB) {
                                Text(
                                    text = tr(
                                        "小内存机型建议仅装听音，后续也可在模型库按需下载",
                                        "For <=6GB RAM devices, starting with speech-only is recommended (you can add models later).",
                                        "小記憶體機型建議僅裝聽音，後續也可在模型庫按需下載"
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TypeFreeAmber,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onActivateOffline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = ContinuousRoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TypeFreeEmerald,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = tr("授权麦克风并后台下载", "Allow Mic & Download in Background", "授權麥克風並背景下載"),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        )

        // Route Card 2: Cloud API (BYOK)
        MochiSelectCard(
            selected = selectedRoute == OnboardingVoiceRoute.CLOUD_API,
            onClick = { onSelectRoute(OnboardingVoiceRoute.CLOUD_API) },
            headerContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = tr("☁️ 服务商云端 API", "☁️ Bring Your Own Key (BYOK)", "☁️ 服務商雲端 API"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = tr("满血全功能", "Full Power", "滿血全功能"),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    ProsConsLine(
                        tag = tr("优势", "Pros", "優勢"),
                        tagColor = TypeFreeEmerald,
                        text = tr(
                            "约 0.5 秒流式极速出稿，完整支持帮我写、选中改写、弧形快译与 🔍 问 AI 知识卡片",
                            "~0.5s streaming polish via Gemini / OpenAI-compatible / DeepSeek, unlocking Help Write, Voice Edit, Arc Translate & 🔍 Ask AI.",
                            "約 0.5 秒串流極速出稿，完整支援幫我寫、選取改寫、弧形快譯與 🔍 問 AI 知識卡片"
                        )
                    )
                    ProsConsLine(
                        tag = tr("局限", "Note", "注意"),
                        tagColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = tr(
                            "需前往对应平台自行获取 API Key（按实际用量向服务商结算，日常使用月均仅几元）",
                            "Uses your own provider API key stored encrypted on-device (Google AI Studio offers a generous free tier).",
                            "需前往對應平台自行取得 API Key（金鑰加密儲存在本機）"
                        )
                    )
                }
            },
            expandedContent = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        cloudSpecs.forEach { spec ->
                            val selected = spec.id == selectedCloudProviderId
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectCloudProvider(spec.id) },
                                label = {
                                    Text(
                                        text = spec.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }

                    if (selectedCloudProviderId == UnifiedProviderCatalog.ID_CUSTOM) {
                        OutlinedTextField(
                            value = customBaseUrl,
                            onValueChange = onCustomBaseUrlChange,
                            singleLine = true,
                            label = { Text(tr("自定义网关 Base URL", "OpenAI-Compatible Base URL", "自訂閘道 Base URL")) },
                            placeholder = { Text("https://api.openai.com/v1") },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = onApiKeyChange,
                            singleLine = true,
                            label = { Text(tr("粘贴 API Key", "Paste API Key", "貼上 API Key")) },
                            visualTransformation = if (showApiKeyPlain) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                TextButton(onClick = onToggleShowKey) {
                                    Text(
                                        text = if (showApiKeyPlain) tr("隐藏", "Hide", "隱藏") else tr("显示", "Show", "顯示"),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedButton(
                            onClick = onPasteKey,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                        ) {
                            Text(tr("粘贴", "Paste", "貼上"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onOpenKeyGuide,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = tr("如何获取免费/低价 Key？", "How to get a free Gemini / API Key?", "如何取得免費/低價 Key？"),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    if (!verifyError.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = TypeFreeRose.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, TypeFreeRose.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = verifyError,
                                style = MaterialTheme.typography.labelSmall,
                                color = TypeFreeRose,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Button(
                        onClick = onVerifyAndActivateCloud,
                        enabled = !isVerifyingKey,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = ContinuousRoundedCornerShape(14.dp)
                    ) {
                        if (isVerifyingKey) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(tr("正在校验接口连通性…", "Verifying API connection…", "正在校驗介面連通性…"), fontWeight = FontWeight.Bold)
                        } else {
                            Text(tr("授权麦克风并验证启用", "Allow Mic & Verify Key", "授權麥克風並驗證啟用"), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        )

        // Floating Voice Bubble tip for Gboard / third-party keyboard users
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = ContinuousRoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Text(
                text = tr(
                    "💡 习惯保留系统键盘或 Gboard？完成引导后，你也可以在「首页」开启「悬浮语音胶囊」，不切键盘随时唤起 AI 语音直插光标。",
                    "💡 Love keeping Gboard? After setup, you can also enable the Floating Voice Bubble on the Home tab to use TypeFree AI voice dictation over any keyboard!",
                    "💡 習慣保留系統鍵盤或 Gboard？完成引導後，您也可以在「首頁」開啟「懸浮語音膠囊」，不切鍵盤隨時使用 AI 語音輸入。"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp,
                modifier = Modifier.padding(14.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun ProsConsLine(
    tag: String,
    tagColor: Color,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(5.dp),
            color = tagColor.copy(alpha = 0.12f)
        ) {
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = tagColor,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun OnboardingStep4LiveTrial(
    step3Result: OnboardingStep3Result,
    readiness: ReadinessState,
    registrySnapshot: com.typefree.core.offline.LocalModelRegistrySnapshot,
    trialText: String,
    onTrialTextChange: (String) -> Unit,
    onPromoteToVoiceTabWhenReady: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    val asrRecord = registrySnapshot.records[OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL]
    val slmRecord = registrySnapshot.records[OfflineModelCatalog.ID_SLM_CORE_0_8B]
    val activeDownloadRecord = listOfNotNull(asrRecord, slmRecord).firstOrNull {
        it.status == LocalModelInstallStatus.DOWNLOADING || it.status == LocalModelInstallStatus.VERIFYING
    }
    val isReadyNow = readiness !is ReadinessState.NotReady

    LaunchedEffect(isReadyNow, step3Result) {
        if (isReadyNow && step3Result == OnboardingStep3Result.OFFLINE_DOWNLOADING) {
            onPromoteToVoiceTabWhenReady()
        }
    }

    LaunchedEffect(Unit) {
        delay(300L)
        runCatching { focusRequester.requestFocus() }
    }

    val headline = when {
        isReadyNow && step3Result != OnboardingStep3Result.SKIPPED_TYPING_ONLY ->
            tr("准备就绪，试着说第一句话", "All Set — Try Speaking Your First Sentence", "準備就緒，試著說第一句話")
        activeDownloadRecord != null || step3Result == OnboardingStep3Result.OFFLINE_DOWNLOADING ->
            tr("键盘已就绪，先试试打字手感", "Keyboard Ready — Try Typing While AI Downloads", "鍵盤已就緒，先試試打字手感")
        else ->
            tr("键盘已就绪，试试打字手感", "Keyboard Ready — Try Typing Below", "鍵盤已就緒，試試打字手感")
    }

    val promptTip = when {
        isReadyNow && step3Result != OnboardingStep3Result.SKIPPED_TYPING_ONLY ->
            tr(
                "🎙️ 按住麦克风试着说：「明天下午两点，哦不对改成三点半，跟王总对一下方案」",
                "🎙️ Hold the mic and try saying: \"Let's meet tomorrow at 2pm — actually make that 3:30pm to review the launch plan.\"",
                "🎙️ 按住麥克風試著說：「明天下午兩點，喔不對改成三點半，跟王總對一下方案」"
            )
        else ->
            tr(
                "⌨️ 在下方试打几句；上滑字母键可输符号，长按空格切换中/英",
                "⌨️ Try typing e.g. \"im glad\" or \"teh\" — swipe up on keys for symbols, slide spacebar to move cursor.",
                "⌨️ 在下方試打幾句；上滑字母鍵可輸符號，長按空白鍵切換中/英"
            )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (activeDownloadRecord != null) {
            val fraction = if (activeDownloadRecord.totalBytes > 0L) {
                (activeDownloadRecord.downloadedBytes.toFloat() / activeDownloadRecord.totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val pct = (fraction * 100f).toInt().coerceIn(0, 100)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = ContinuousRoundedCornerShape(14.dp),
                color = TypeFreeEmerald.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.40f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = tr(
                            "📥 离线模型后台下载中 ($pct%)，完成后自动激活语音",
                            "📥 Downloading offline AI in background ($pct%) — voice activates automatically when ready",
                            "📥 離線模型背景下載中 ($pct%)，完成後自動啟用語音"
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TypeFreeEmerald
                    )
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = TypeFreeEmerald,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }

        Text(
            text = headline,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = ContinuousRoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ) {
            Text(
                text = promptTip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 19.sp,
                modifier = Modifier.padding(12.dp)
            )
        }

        OutlinedTextField(
            value = trialText,
            onValueChange = onTrialTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            minLines = 4,
            maxLines = 7,
            shape = ContinuousRoundedCornerShape(16.dp),
            placeholder = {
                Text(
                    text = tr("在这里试打或语音输入…", "Try typing or speaking here…", "在這裡試打或語音輸入…"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
            }
        )
    }
}

@Composable
private fun ApiKeyQuickGuideDialog(
    preferDomestic: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val items = if (preferDomestic) {
        listOf(
            Triple("DeepSeek 开放平台", "注册实名后创建 sk- 密钥，单 Key 同时支持语音润色、帮我写与问 AI", "https://platform.deepseek.com/api_keys"),
            Triple("阿里云百炼", "新用户赠送免费体验额度，支持实时流式识别与千问润色", "https://bailian.console.aliyun.com/"),
            Triple("硅基流动 SiliconFlow", "SenseVoiceSmall 语音识别免费调用，注册即送体验金", "https://cloud.siliconflow.cn/")
        )
    } else {
        listOf(
            Triple(
                "Google AI Studio (Gemini)",
                AppStrings.pick(
                    appLang,
                    "免费创建 AIza 密钥，0.5s 原生流式语音去口癖直出",
                    "Create a free AIza key in seconds — ~0.5s native Gemini Live voice dictation & AI polish",
                    "免費建立 AIza 金鑰，0.5s 原生串流語音直出"
                ),
                "https://aistudio.google.com/apikey"
            ),
            Triple(
                "OpenAI / Groq / DeepSeek",
                AppStrings.pick(
                    appLang,
                    "支持任意 OpenAI 兼容密钥，单 Key 驱动语音润色与问 AI",
                    "Bring any OpenAI, Groq Whisper, or DeepSeek API key for ultra-low-latency voice & Ask AI",
                    "支援任意 OpenAI 相容金鑰，單 Key 驅動語音潤飾與問 AI"
                ),
                "https://platform.openai.com/api-keys"
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("3 步快速获取 API Key", "Get an API Key in 3 Steps", "3 步快速取得 API Key")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = tr(
                        "1. 点击下方任一平台打开官方控制台注册登录\n2. 在「API Keys」页面新建并复制密钥\n3. 回到本页点击「粘贴」即可直连使用（密钥仅存本机）",
                        "1. Tap a provider below to open its official console\n2. Create and copy a new API key\n3. Return here and tap Paste (stored encrypted on your device only)",
                        "1. 點擊下方任一平台打開官方控制台\n2. 在「API Keys」頁面新建並複製金鑰\n3. 回到本頁點擊「貼上」即可直連使用（金鑰僅存本機）"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
                items.forEach { (title, desc, url) ->
                    Surface(
                        onClick = { openExternalBrowserUrl(context, url) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "$title ›",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("知道了", "Got It", "知道了")) }
        }
    )
}

internal fun isSystemImeEnabled(context: Context): Boolean {
    return try {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
    } catch (_: Exception) {
        false
    }
}

internal fun isSystemImeSelected(context: Context): Boolean {
    return try {
        val defaultIme = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        defaultIme?.startsWith(context.packageName) == true
    } catch (_: Exception) {
        false
    }
}

private fun hasMicRecordPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}

private fun openImeSettings(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
