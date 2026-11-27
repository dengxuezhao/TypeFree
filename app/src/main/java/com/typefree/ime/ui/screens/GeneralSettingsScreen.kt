package com.typefree.ime.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.core.offline.OfflineMode
import com.typefree.ime.BuildConfig
import com.typefree.ime.bubble.TypeFreeBubbleController
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.HapticPreset
import com.typefree.ime.data.ThemePreference
import com.typefree.ime.data.stats.StatsTimeRange
import com.typefree.ime.diagnostics.ImeDiagnosticLogger
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ime.HapticFeedbackPlanner
import com.typefree.ime.ime.HapticTier
import com.typefree.ime.ime.KeyFeedbackController
import com.typefree.ime.ime.KeySoundType
import com.typefree.ime.network.AppUpdateChecker
import com.typefree.ime.network.UpdateCheckInfo
import com.typefree.ime.ui.components.ContinuousRoundedCornerShape
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.TypeFreeEmerald
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal object AboutDiagnosticsSnapshotHelper {
    const val DEFAULT_EXPANDED: Boolean = false

    fun buildExtraState(
        settings: AppSettings,
        imeEnabled: Boolean,
        imeSelected: Boolean,
        accessibilityEnabled: Boolean
    ): Map<String, String> = linkedMapOf(
        "imeEnabled" to imeEnabled.toString(),
        "imeSelected" to imeSelected.toString(),
        "a11yEnabled" to accessibilityEnabled.toString(),
        "bubbleEnabled" to settings.bubbleEnabled.toString()
    ).apply {
        putAll(ImeDiagnosticLogger.snapshotRuntimeState())
    }

    fun buildSnapshotText(
        settings: AppSettings,
        imeEnabled: Boolean,
        imeSelected: Boolean,
        accessibilityEnabled: Boolean,
        appVersion: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        deviceSummary: String = "${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
    ): String {
        return ImeDiagnosticLogger.exportSnapshot(
            appVersion = appVersion,
            deviceSummary = deviceSummary,
            extraState = buildExtraState(
                settings = settings,
                imeEnabled = imeEnabled,
                imeSelected = imeSelected,
                accessibilityEnabled = accessibilityEnabled
            )
        )
    }

    fun sectionHeaderLabel(lang: AppLang): String = AppStrings.pick(
        lang,
        "高级排障与运行快照",
        "Troubleshooting & Diagnostics",
        "進階排障與執行快照"
    )

    fun copyActionLabel(copied: Boolean, lang: AppLang): String {
        return if (copied) {
            AppStrings.pick(
                lang,
                "✓ 已复制运行诊断快照",
                "✓ Diagnostic Snapshot Copied",
                "✓ 已複製執行診斷快照"
            )
        } else {
            AppStrings.pick(
                lang,
                "复制运行诊断快照",
                "Copy Diagnostic Snapshot",
                "複製執行診斷快照"
            )
        }
    }

    fun privacyNoticeLabel(lang: AppLang): String = AppStrings.pick(
        lang,
        "仅含最近窗口显隐、无障碍状态与耗时，绝不记录任何打字或聊天内容",
        "Contains recent window/accessibility states & timings only — never records typed or chat text",
        "僅含最近視窗顯隱、無障礙狀態與耗時，絕不記錄任何打字或聊天內容"
    )

    fun copiedToastLabel(lang: AppLang): String = AppStrings.pick(
        lang,
        "✓ 已复制运行诊断快照（零隐私正文）",
        "✓ Diagnostic snapshot copied (Zero-PII)",
        "✓ 已複製執行診斷快照（零隱私正文）"
    )
}

internal object HapticSettingsUiHelper {
    const val DEFAULT_EXPANDED: Boolean = false

    val SELECTABLE_PRESETS: List<HapticPreset> = listOf(
        HapticPreset.OFF,
        HapticPreset.SYSTEM,
        HapticPreset.SOFT,
        HapticPreset.CRISP,
        HapticPreset.STRONG
    )

    fun sectionHeaderLabel(lang: AppLang): String = AppStrings.pick(
        lang,
        "震感与音效精细调节",
        "Haptic & Sound Fine-Tuning",
        "震感與音效精細調節"
    )

    fun presetLabel(preset: HapticPreset, lang: AppLang): String = when (preset) {
        HapticPreset.OFF -> AppStrings.pick(lang, "关闭", "Off", "關閉")
        HapticPreset.SYSTEM -> AppStrings.pick(lang, "跟随系统", "System", "跟隨系統")
        HapticPreset.SOFT -> AppStrings.pick(lang, "轻柔", "Soft", "輕柔")
        HapticPreset.CRISP -> AppStrings.pick(lang, "清脆", "Crisp", "清脆")
        HapticPreset.STRONG -> AppStrings.pick(lang, "强劲", "Strong", "強勁")
        HapticPreset.CUSTOM -> AppStrings.pick(lang, "自定义", "Custom", "自訂")
    }

    fun formatCollapsedSummary(settings: AppSettings, lang: AppLang): String {
        val effective = HapticFeedbackPlanner.resolveEffectivePreset(settings)
        val hapticPart = when (effective) {
            HapticPreset.OFF -> presetLabel(HapticPreset.OFF, lang)
            HapticPreset.SYSTEM -> presetLabel(HapticPreset.SYSTEM, lang)
            HapticPreset.CUSTOM -> "${presetLabel(HapticPreset.CUSTOM, lang)} ${settings.hapticStrength.coerceIn(10, 100)}%"
            else -> presetLabel(effective, lang)
        }
        val soundPart = if (settings.interactionSounds) {
            val vol = settings.keySoundVolume.coerceIn(0, 100)
            if (vol == 0) {
                AppStrings.pick(lang, "静音", "Muted", "靜音")
            } else {
                AppStrings.pick(lang, "音效 $vol%", "Sound $vol%", "音效 $vol%")
            }
        } else {
            AppStrings.pick(lang, "静音", "Muted", "靜音")
        }
        return "$hapticPart · $soundPart"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeneralSettingsScreen(
    repository: AppRepository
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val coroutineScope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState()
    val clipboardItems by repository.clipboardItems.collectAsState()
    val statsRev by repository.dailyStatsRevision.collectAsState()
    val statsMeta = remember(settings, statsRev) {
        repository.computeInsightsSnapshot(StatsTimeRange.ALL)
    }
    val formattedStatsKb = remember(statsMeta.statsDbSizeBytes) {
        val bytes = statsMeta.statsDbSizeBytes.coerceAtLeast(0L)
        when {
            bytes == 0L -> "0 KB"
            bytes < 1024L -> "< 1 KB"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    var showResetStatsConfirm by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateDialogState by remember { mutableStateOf<SharedUpdateDialogState?>(null) }
    var availableUpdate by remember {
        mutableStateOf<UpdateCheckInfo?>(
            AppUpdateChecker.peekCachedUpdate(BuildConfig.VERSION_NAME)
        )
    }
    var dismissedUpdateTag by rememberSaveable { mutableStateOf<String?>(null) }

    // Silently check for updates when opening Settings (strictly skipped under OFFLINE_ONLY egress lock).
    LaunchedEffect(settings.offlineConfig.mode) {
        if (settings.offlineConfig.mode == OfflineMode.OFFLINE_ONLY) return@LaunchedEffect
        val latest = AppUpdateChecker.fetchLatestUpdateSilentlyIfNeeded(
            currentVersion = BuildConfig.VERSION_NAME,
            isPrivateDictBuild = BuildConfig.USE_PRIVATE_DICT
        )
        if (latest != null && latest.hasUpdate) {
            availableUpdate = latest
        }
    }

    fun triggerUpdateCheck() {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        coroutineScope.launch {
            val result = AppUpdateChecker.fetchLatestUpdate(
                currentVersion = BuildConfig.VERSION_NAME,
                isPrivateDictBuild = BuildConfig.USE_PRIVATE_DICT
            )
            isCheckingUpdate = false
            result.onSuccess { info ->
                availableUpdate = info.takeIf { it.hasUpdate }
                updateDialogState = if (info.hasUpdate) {
                    SharedUpdateDialogState.UpdateAvailable(info)
                } else {
                    SharedUpdateDialogState.UpToDate(info)
                }
            }.onFailure { err ->
                updateDialogState = SharedUpdateDialogState.Error(
                    err.message ?: AppStrings.pick(
                        appLang,
                        "检查更新失败，请稍后重试或前往 GitHub Releases 手动查看",
                        "Update check failed — please try again later or check GitHub Releases",
                        "檢查更新失敗，請稍後重試或前往 GitHub Releases 手動查看"
                    )
                )
            }
        }
    }

    val bannerUpdate = availableUpdate?.takeIf { it.hasUpdate && it.latestTag != dismissedUpdateTag }

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = tr("通用", "General", "一般"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Surface(
                    onClick = {
                        val cached = availableUpdate
                        if (cached != null && cached.hasUpdate) {
                            updateDialogState = SharedUpdateDialogState.UpdateAvailable(cached)
                        } else {
                            triggerUpdateCheck()
                        }
                    },
                    enabled = !isCheckingUpdate,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        1.dp,
                        if (availableUpdate?.hasUpdate == true) {
                            TypeFreeEmerald.copy(alpha = 0.60f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(11.dp),
                                strokeWidth = 1.6.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (availableUpdate?.hasUpdate == true) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(TypeFreeEmerald)
                            )
                        }
                        Text(
                            text = when {
                                isCheckingUpdate -> tr("检查更新中…", "Checking…", "檢查更新中…")
                                availableUpdate?.hasUpdate == true -> tr("有更新 v${availableUpdate!!.latestVersion}", "Update v${availableUpdate!!.latestVersion}", "有更新 v${availableUpdate!!.latestVersion}")
                                else -> "v${BuildConfig.VERSION_NAME} · BYOK"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (availableUpdate?.hasUpdate == true) {
                                TypeFreeEmerald
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AnimatedVisibility(visible = bannerUpdate != null) {
                bannerUpdate?.let { info ->
                    SettingsUpdatePromptBanner(
                        info = info,
                        onUpdateClick = {
                            updateDialogState = SharedUpdateDialogState.UpdateAvailable(info)
                        },
                        onDismiss = {
                            dismissedUpdateTag = info.latestTag
                        }
                    )
                }
            }

            // 1. 界面语言与外观主题
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
                        Text(
                            text = tr("界面语言 · Language", "Language / 界面语言", "介面語言 · Language"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr(
                                "即时切换应用、内置键盘与 AI 提示词语言（非中文系统默认展示英文）",
                                "Switch app, keyboard chrome, and AI prompt language live",
                                "即時切換應用、內建鍵盤與 AI 提示詞語言（非中文系統預設顯示英文）"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val normalizedCurrentLang = AppStrings.normalizeAppLanguage(settings.appLanguage)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AppStrings.LANGUAGE_OPTIONS.forEach { option ->
                            val isSelected = normalizedCurrentLang == option
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    repository.updateSettings { it.copy(appLanguage = option) }
                                },
                                label = {
                                    Text(
                                        text = AppStrings.languageOptionLabel(option, appLang),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = tr("外观主题", "Appearance", "外觀主題"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr(
                                "切换宿主应用、内置语音键盘与悬浮语音胶囊的视觉配色",
                                "Choose color theme across the app, keyboard, and floating voice bubble",
                                "切換宿主應用、內建語音鍵盤與懸浮語音膠囊的視覺配色"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GeneralThemeOptionCard(
                            title = tr("浅色单色", "Light", "淺色單色"),
                            subtitle = tr("TypeFree 经典", "Classic Mono", "TypeFree 經典"),
                            preference = ThemePreference.LIGHT,
                            selected = settings.themePreference == ThemePreference.LIGHT,
                            onClick = {
                                repository.updateSettings { it.copy(themePreference = ThemePreference.LIGHT) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        GeneralThemeOptionCard(
                            title = tr("深色曜石", "Obsidian", "深色曜石"),
                            subtitle = tr("沉浸暗色", "Pure Dark", "沉浸暗色"),
                            preference = ThemePreference.DARK,
                            selected = settings.themePreference == ThemePreference.DARK,
                            onClick = {
                                repository.updateSettings { it.copy(themePreference = ThemePreference.DARK) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        GeneralThemeOptionCard(
                            title = tr("跟随系统", "System", "跟隨系統"),
                            subtitle = tr("日夜自适应", "Auto Day/Night", "日夜自適應"),
                            preference = ThemePreference.SYSTEM,
                            selected = settings.themePreference == ThemePreference.SYSTEM,
                            onClick = {
                                repository.updateSettings { it.copy(themePreference = ThemePreference.SYSTEM) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 2. 面板记忆、音效与触觉振动
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
                    Text(
                        text = tr("面板记忆与声音振动", "Keyboard Memory, Sound & Haptics", "面板記憶與聲音震動"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    PreferenceSwitchRow(
                        title = tr("记忆上一次使用的键盘模式", "Remember Last Keyboard Mode", "記憶上一次使用的鍵盤模式"),
                        subtitle = tr(
                            "收起键盘或切换应用后自动恢复上次面板；密码框临时切英文后自动还原",
                            "Restore your last active panel across apps; auto-restores after password fields",
                            "收起鍵盤或切換應用後自動恢復上次面板；密碼框臨時切英文後自動還原"
                        ),
                        checked = settings.rememberLastKeyboardMode,
                        onCheckedChange = { enabled ->
                            repository.updateSettings { it.copy(rememberLastKeyboardMode = enabled) }
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    PreferenceSwitchRow(
                        title = tr("按键与录音提示音", "Key Click & Mic Sounds", "按鍵與錄音提示音"),
                        subtitle = tr(
                            "点击麦克风开始聆听、结束录音及按键反馈轻音效",
                            "Subtle audio cues when starting/stopping dictation and tapping keys",
                            "點擊麥克風開始聆聽、結束錄音及按鍵回饋輕音效"
                        ),
                        checked = settings.interactionSounds,
                        onCheckedChange = { enabled ->
                            repository.updateSettings { it.copy(interactionSounds = enabled) }
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    val effectiveHapticPreset = HapticFeedbackPlanner.resolveEffectivePreset(settings)
                    PreferenceSwitchRow(
                        title = tr("触觉振动反馈", "Haptic Feedback", "觸覺震動回饋"),
                        subtitle = tr(
                            "按下瞬间 0ms 触觉响应，区分字母键、功能键与上滑确认手感",
                            "Instant 0ms touch-down vibration tuned per key tier and swipe gesture",
                            "按下瞬間 0ms 觸覺回應，區分字母鍵、功能鍵與上滑確認手感"
                        ),
                        checked = effectiveHapticPreset != HapticPreset.OFF,
                        onCheckedChange = { enabled ->
                            repository.updateSettings { prev ->
                                prev.copy(
                                    hapticFeedback = enabled,
                                    hapticPreset = when {
                                        !enabled -> HapticPreset.OFF
                                        prev.hapticPreset == HapticPreset.OFF -> HapticPreset.SYSTEM
                                        else -> prev.hapticPreset
                                    }
                                )
                            }
                        }
                    )

                    // 默认折叠的震感与音效精细调节区
                    var showHapticTuning by rememberSaveable {
                        mutableStateOf(HapticSettingsUiHelper.DEFAULT_EXPANDED)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showHapticTuning = !showHapticTuning }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = HapticSettingsUiHelper.sectionHeaderLabel(appLang),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = HapticSettingsUiHelper.formatCollapsedSummary(settings, appLang),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                            )
                            Text(
                                text = if (showHapticTuning) "▾" else "▸",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    AnimatedVisibility(visible = showHapticTuning) {
                        val hostView = LocalView.current
                        val previewController = remember(context) {
                            KeyFeedbackController(context) { repository.settings.value }
                        }
                        var strengthSliderValue by remember(settings.hapticStrength) {
                            mutableFloatStateOf(settings.hapticStrength.coerceIn(10, 100).toFloat())
                        }
                        var lastPreviewStrength by remember {
                            mutableIntStateOf(settings.hapticStrength.coerceIn(10, 100))
                        }
                        var soundSliderValue by remember(settings.keySoundVolume) {
                            mutableFloatStateOf(settings.keySoundVolume.coerceIn(0, 100).toFloat())
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = tr("震感档位（点击立即试震）", "Haptic Profile (Tap to Preview)", "震感檔位（點擊立即試震）"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    HapticSettingsUiHelper.SELECTABLE_PRESETS.forEach { preset ->
                                        val selected = effectiveHapticPreset == preset
                                        FilterChip(
                                            selected = selected,
                                            onClick = {
                                                val nextStrength = if (preset.defaultStrength > 0) {
                                                    preset.defaultStrength
                                                } else {
                                                    settings.hapticStrength
                                                }
                                                strengthSliderValue = nextStrength.coerceIn(10, 100).toFloat()
                                                lastPreviewStrength = nextStrength.coerceIn(10, 100)
                                                repository.updateSettings { prev ->
                                                    prev.copy(
                                                        hapticFeedback = preset != HapticPreset.OFF,
                                                        hapticPreset = preset,
                                                        hapticStrength = if (preset.defaultStrength > 0) preset.defaultStrength else prev.hapticStrength
                                                    )
                                                }
                                                if (preset != HapticPreset.OFF) {
                                                    previewController.previewInSettings(
                                                        preset = preset,
                                                        strengthPercent = nextStrength,
                                                        tier = HapticTier.ALPHA_KEY,
                                                        view = hostView
                                                    )
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = HapticSettingsUiHelper.presetLabel(preset, appLang),
                                                    fontSize = 12.sp,
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

                                // Strength slider (available whenever haptics is not OFF; dragging switches to CUSTOM)
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = tr("震动强度", "Vibration Strength", "震動強度"),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = when (effectiveHapticPreset) {
                                                HapticPreset.OFF -> tr("已关闭", "Off", "已關閉")
                                                HapticPreset.SYSTEM -> tr("系统默认（拖动自定义）", "System (drag to customize)", "系統預設（拖動自訂）")
                                                else -> "${strengthSliderValue.roundToInt()}%"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Slider(
                                        value = strengthSliderValue,
                                        onValueChange = { raw ->
                                            val snapped = raw.roundToInt().coerceIn(10, 100)
                                            strengthSliderValue = snapped.toFloat()
                                            if (abs(snapped - lastPreviewStrength) >= 6) {
                                                lastPreviewStrength = snapped
                                                previewController.previewInSettings(
                                                    preset = HapticPreset.CUSTOM,
                                                    strengthPercent = snapped,
                                                    tier = HapticTier.ALPHA_KEY,
                                                    view = hostView
                                                )
                                            }
                                        },
                                        onValueChangeFinished = {
                                            val finalVal = strengthSliderValue.roundToInt().coerceIn(10, 100)
                                            lastPreviewStrength = finalVal
                                            repository.updateSettings { prev ->
                                                prev.copy(
                                                    hapticFeedback = true,
                                                    hapticPreset = HapticPreset.CUSTOM,
                                                    hapticStrength = finalVal
                                                )
                                            }
                                            previewController.previewInSettings(
                                                preset = HapticPreset.CUSTOM,
                                                strengthPercent = finalVal,
                                                tier = HapticTier.ALPHA_KEY,
                                                view = hostView
                                            )
                                        },
                                        valueRange = 10f..100f,
                                        enabled = effectiveHapticPreset != HapticPreset.OFF
                                    )
                                }

                                // Key sound volume slider
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = tr("按键音效音量", "Key Sound Volume", "按鍵音效音量"),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (settings.interactionSounds) {
                                                val pct = soundSliderValue.roundToInt().coerceIn(0, 100)
                                                if (pct == 0) {
                                                    tr("0%（静音）", "0% (Muted)", "0%（靜音）")
                                                } else {
                                                    "$pct%"
                                                }
                                            } else {
                                                tr("请先开启上方提示音", "Enable sounds above", "請先開啟上方提示音")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Slider(
                                        value = soundSliderValue,
                                        onValueChange = { raw ->
                                            soundSliderValue = raw.roundToInt().coerceIn(0, 100).toFloat()
                                        },
                                        onValueChangeFinished = {
                                            val finalVol = soundSliderValue.roundToInt().coerceIn(0, 100)
                                            repository.updateSettings { it.copy(keySoundVolume = finalVol) }
                                            if (settings.interactionSounds && finalVol > 0) {
                                                previewController.previewSoundInSettings(finalVol)
                                            }
                                        },
                                        valueRange = 0f..100f,
                                        enabled = settings.interactionSounds
                                    )
                                }

                                // Quick tactile tier test row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val testTiers = listOf(
                                        Triple(tr("试按字母键", "Letter", "試按字母鍵"), HapticTier.ALPHA_KEY, KeySoundType.STANDARD),
                                        Triple(tr("试按空格键", "Space", "試按空格鍵"), HapticTier.FUNCTION_KEY, KeySoundType.SPACEBAR),
                                        Triple(tr("试按上滑确认", "Swipe Up", "試按上滑確認"), HapticTier.GESTURE_CONFIRM, KeySoundType.NONE)
                                    )
                                    testTiers.forEach { (label, tier, sound) ->
                                        OutlinedButton(
                                            onClick = {
                                                val presetToPreview = if (effectiveHapticPreset == HapticPreset.OFF) {
                                                    HapticPreset.SYSTEM
                                                } else {
                                                    effectiveHapticPreset
                                                }
                                                previewController.previewInSettings(
                                                    preset = presetToPreview,
                                                    strengthPercent = strengthSliderValue.roundToInt(),
                                                    tier = tier,
                                                    view = hostView
                                                )
                                                if (settings.interactionSounds && sound != KeySoundType.NONE) {
                                                    previewController.playKeySound(sound)
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                val hwHint = if (previewController.hardwareCaps.supportsLowTick || previewController.hardwareCaps.supportsClick) {
                                    tr(
                                        "当前设备支持硬件线性马达瞬态原语，已启用主动刹停波形",
                                        "Linear motor primitives detected — active-braked waveforms enabled",
                                        "目前裝置支援硬體線性馬達瞬態原語，已啟用主動煞停波形"
                                    )
                                } else {
                                    tr(
                                        "已自动适配短促瞬态脉冲（4–13ms）与系统触感曲线",
                                        "Adaptive short crisp pulse (4–13ms) and system haptic curve active",
                                        "已自動適配短促瞬態脈衝（4–13ms）與系統觸感曲線"
                                    )
                                }
                                Text(
                                    text = hwHint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }

            // 3. 内置剪贴板历史管理
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            ) {
                val unpinnedCount = remember(clipboardItems) { clipboardItems.count { !it.isPinned } }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = tr("内置剪贴板历史管理", "Clipboard History", "內建剪貼簿歷史管理"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = tr(
                                    "键盘顶栏可唤起双列剪贴板，支持置顶常用语 (${clipboardItems.size} 条)",
                                    "Two-column clipboard in keyboard top bar with pin support (${clipboardItems.size} items)",
                                    "鍵盤頂欄可喚起雙欄剪貼簿，支援置頂常用語 (${clipboardItems.size} 條)"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (unpinnedCount > 0) {
                            TextButton(
                                onClick = {
                                    repository.clearUnpinnedClipboardItems()
                                    Toast.makeText(
                                        context,
                                        AppStrings.pick(appLang, "已清空未置顶剪贴板记录", "Cleared unpinned clipboard items", "已清空未置頂剪貼簿紀錄"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            ) {
                                Text(tr("清空未置顶 ($unpinnedCount)", "Clear ($unpinnedCount)", "清空未置頂 ($unpinnedCount)"))
                            }
                        }
                    }

                    PreferenceSwitchRow(
                        title = tr("自动记录剪贴板历史", "Save Clipboard History", "自動記錄剪貼簿歷史"),
                        subtitle = tr(
                            "自动收纳最近 200 条复制文本，自动过滤密码管理器敏感内容",
                            "Keep up to 200 copied snippets locally; automatically skips password-manager secrets",
                            "自動收納最近 200 條複製文字，自動過濾密碼管理器敏感內容"
                        ),
                        checked = settings.clipboardEnabled,
                        onCheckedChange = { enabled ->
                            repository.updateSettings { it.copy(clipboardEnabled = enabled) }
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    PreferenceSwitchRow(
                        title = tr("顶栏刚复制内容一键粘贴提示", "One-Tap Paste Pill in Top Bar", "頂欄剛複製內容一鍵貼上提示"),
                        subtitle = tr(
                            "复制短信验证码、链接或文本后在键盘顶栏浮现快捷胶囊，一键填入",
                            "Surface newly copied OTP codes, links, or text as a quick-paste pill in the top bar",
                            "複製簡訊驗證碼、連結或文字後在鍵盤頂欄浮現快捷膠囊，一鍵填入"
                        ),
                        checked = settings.clipboardQuickPasteSuggestion,
                        onCheckedChange = { enabled ->
                            repository.updateSettings { it.copy(clipboardQuickPasteSuggestion = enabled) }
                        }
                    )

                    if (clipboardItems.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        Text(
                            text = tr("最近记录预览（可置顶或删除）", "Recent Items (Pin or Delete)", "最近紀錄預覽（可置頂或刪除）"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        clipboardItems.take(8).forEach { item ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { repository.togglePinClipboardItem(item.id) },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = TypeFreeIcons.Pin,
                                                contentDescription = if (item.isPinned) tr("取消置顶", "Unpin", "取消置頂") else tr("置顶", "Pin", "置頂"),
                                                tint = if (item.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { repository.deleteClipboardItem(item.id) },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = tr("删除", "Delete", "刪除"),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. 端侧纯数字表达统计
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ContinuousRoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = tr("端侧纯数字表达统计", "On-Device Numeric Stats", "端側純數字表達統計"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = TypeFreeEmerald.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.34f))
                        ) {
                            Text(
                                text = tr("0 正文 · 纯本地", "0 Text · Local Only", "0 正文 · 純本機"),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TypeFreeEmerald,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                            )
                        }
                    }

                    PreferenceSwitchRow(
                        title = tr("开启端侧按日纯数字统计", "Enable Daily Numeric Insights", "開啟端側按日純數字統計"),
                        subtitle = tr(
                            "仅按日累计字数、耗时与使用次数等纯数字指标，绝不记录任何输入正文；无痕模式与密码框自动跳过",
                            "Counts daily character totals, time saved, and run counts only — never stores typed text; auto-skips incognito & password fields",
                            "僅按日累計字數、耗時與使用次數等純數字指標，絕不記錄任何輸入正文；無痕模式與密碼框自動略過"
                        ),
                        checked = settings.inputStatsEnabled,
                        onCheckedChange = { enabled ->
                            repository.setInputStatsEnabled(enabled)
                        }
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = tr(
                                    "本地统计表：${statsMeta.statsTrackedDays} 天 · $formattedStatsKb",
                                    "Local DB: ${statsMeta.statsTrackedDays} days · $formattedStatsKb",
                                    "本機統計表：${statsMeta.statsTrackedDays} 天 · $formattedStatsKb"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = tr("写入方式：内存累加 + 后台异步合并", "Write path: In-memory counters + async flush", "寫入方式：記憶體累加 + 背景非同步合併"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = tr("无痕 / 密码框隔离：强制零计数", "Incognito / Password fields: Strict zero-logging", "無痕 / 密碼框隔離：強制零計數"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { showResetStatsConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tr("重置累计纯数字统计与里程碑", "Reset Numeric Stats & Milestones", "重置累計純數字統計與里程碑"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 5. 更新与关于隐私
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.LogoMark,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TypeFree",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = tr(
                                    "v${BuildConfig.VERSION_NAME} · BYOK 自有接口版",
                                    "v${BuildConfig.VERSION_NAME} · Open-Source On-Device & BYOK",
                                    "v${BuildConfig.VERSION_NAME} · BYOK 自備介面版"
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = TypeFreeEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = tr("自有 API 直连 · 数据仅存本机", "Direct BYOK & On-Device Privacy", "自備 API 直連 · 資料僅存本機"),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = tr(
                                    "• 自有接口直连：音频与文本由手机直接发送至你配置的模型或自定义网关接口，无第三方中转。\n• 本机存储：API Key、历史记录、个人词库、剪贴板与风格配置均保存在当前设备本地。",
                                    "• Direct Connection: Audio and text run locally on-device or connect straight from your phone to your chosen API endpoint — zero middleman relay.\n• Local Storage: API keys, history, personal dictionary, and clipboard stay strictly on your device.",
                                    "• 自備介面直連：音訊與文字由手機直接發送至您設定的模型介面，無第三方中轉。\n• 本機儲存：API Key、歷史紀錄、個人詞庫與剪貼簿均保存在本機。"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 19.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { triggerUpdateCheck() },
                            enabled = !isCheckingUpdate,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("正在检查…", "Checking…", "正在檢查…"), style = MaterialTheme.typography.labelLarge)
                            } else {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("检查更新", "Check Updates", "檢查更新"), style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        OutlinedButton(
                            onClick = { openExternalBrowserUrl(context, AppUpdateChecker.GITHUB_RELEASES_PAGE_URL) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("GitHub Releases", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            repository.updateSettings { it.copy(onboardingCompleted = false) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(tr("重新查看首次引导向导", "Replay Onboarding Guide", "重新查看首次引導精靈"), style = MaterialTheme.typography.labelLarge)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "TypeFree Android · v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 默认收起的高级排障与运行诊断快照复制区
                    var showDiagnosticsSection by rememberSaveable {
                        mutableStateOf(AboutDiagnosticsSnapshotHelper.DEFAULT_EXPANDED)
                    }
                    var diagnosticCopiedFeedback by remember { mutableStateOf(false) }
                    LaunchedEffect(diagnosticCopiedFeedback) {
                        if (diagnosticCopiedFeedback) {
                            delay(2200L)
                            diagnosticCopiedFeedback = false
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val next = !showDiagnosticsSection
                                showDiagnosticsSection = next
                                if (!next) diagnosticCopiedFeedback = false
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = AboutDiagnosticsSnapshotHelper.sectionHeaderLabel(appLang),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (showDiagnosticsSection) "▾" else "▸",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(visible = showDiagnosticsSection) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = AboutDiagnosticsSnapshotHelper.privacyNoticeLabel(appLang),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 17.sp
                                )

                                OutlinedButton(
                                    onClick = {
                                        val imeEnabled = isSystemImeEnabled(context)
                                        val imeSelected = isSystemImeSelected(context)
                                        val a11yEnabled = TypeFreeBubbleController.isAccessibilityEnabledInSettings(context)
                                        val snapshotText = AboutDiagnosticsSnapshotHelper.buildSnapshotText(
                                            settings = settings,
                                            imeEnabled = imeEnabled,
                                            imeSelected = imeSelected,
                                            accessibilityEnabled = a11yEnabled
                                        )
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(
                                            ClipData.newPlainText("TypeFree Diagnostic Snapshot", snapshotText)
                                        )
                                        diagnosticCopiedFeedback = true
                                        Toast.makeText(
                                            context,
                                            AboutDiagnosticsSnapshotHelper.copiedToastLabel(appLang),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (diagnosticCopiedFeedback) {
                                            TypeFreeEmerald.copy(alpha = 0.65f)
                                        } else {
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                        }
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = AboutDiagnosticsSnapshotHelper.copyActionLabel(
                                            copied = diagnosticCopiedFeedback,
                                            lang = appLang
                                        ),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (diagnosticCopiedFeedback) {
                                            TypeFreeEmerald
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showResetStatsConfirm) {
        AlertDialog(
            onDismissRequest = { showResetStatsConfirm = false },
            title = { Text(tr("重置累计纯数字统计与里程碑", "Reset Numeric Stats & Milestones", "重置累計純數字統計與里程碑")) },
            text = {
                Text(
                    tr(
                        "确定要清零本机累计的 ${statsMeta.statsTrackedDays} 天纯数字表达统计、24 小时节律与里程碑进度吗？此操作不会影响你的识别历史或个人词典，且无法撤销。",
                        "Clear ${statsMeta.statsTrackedDays} days of local numeric stats, 24h rhythm, and milestones? Your dictation history and personal dictionary will NOT be affected. This cannot be undone.",
                        "確定要清零本機累計的 ${statsMeta.statsTrackedDays} 天純數字表達統計、24 小時節律與里程碑進度嗎？此操作不會影響您的識別歷史或個人詞典，且無法復原。"
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.resetDailyStats()
                        showResetStatsConfirm = false
                        Toast.makeText(
                            context,
                            AppStrings.pick(appLang, "已重置累计纯数字统计与里程碑", "Numeric stats & milestones reset", "已重置累計純數字統計與里程碑"),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text(tr("确认重置", "Reset", "確認重置"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetStatsConfirm = false }) {
                    Text(tr("取消", "Cancel", "取消"))
                }
            }
        )
    }

    updateDialogState?.let { dialogState ->
        SharedAppUpdateDialog(
            state = dialogState,
            onOpenUrl = { url -> openExternalBrowserUrl(context, url) },
            onDismiss = { updateDialogState = null }
        )
    }
}

@Composable
private fun GeneralThemeOptionCard(
    title: String,
    subtitle: String,
    preference: ThemePreference,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        },
        border = BorderStroke(
            width = if (selected) 1.6.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
            ) {
                when (preference) {
                    ThemePreference.LIGHT -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFF6F6F8)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(34.dp)
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFF18181B))
                            )
                        }
                    }
                    ThemePreference.DARK -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF141519)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(34.dp)
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFFF4F4F6))
                            )
                        }
                    }
                    ThemePreference.SYSTEM -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF6F6F8)))
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF141519)))
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF18181B))
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
