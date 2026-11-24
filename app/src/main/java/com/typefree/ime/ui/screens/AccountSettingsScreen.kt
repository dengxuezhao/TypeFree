package com.typefree.ime.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.core.offline.OfflineMode
import com.typefree.ime.BuildConfig
import com.typefree.ime.bubble.TypeFreeBubbleController
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.ThemePreference
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ime.KeyboardMetrics
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.AppUpdateChecker
import com.typefree.ime.network.UpdateCheckInfo
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.CompletionBackendHint
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.components.rememberLocalModelRegistrySnapshot
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import kotlinx.coroutines.launch

@Composable
fun AccountSettingsScreen(
    repository: AppRepository,
    aiEngine: AiPipelineEngine,
    initialSubTab: Int = AppDestinations.SUBTAB_API,
    onSubTabChanged: ((Int) -> Unit)? = null
) {
    val validSubTabs = setOf(
        AppDestinations.SUBTAB_API,
        AppDestinations.SUBTAB_STYLES,
        AppDestinations.SUBTAB_PREFERENCES
    )
    var activeSubTab by rememberSaveable {
        mutableIntStateOf(
            if (initialSubTab in validSubTabs) initialSubTab else AppDestinations.SUBTAB_API
        )
    }

    LaunchedEffect(initialSubTab) {
        val resolved = if (initialSubTab in validSubTabs) initialSubTab else AppDestinations.SUBTAB_API
        if (activeSubTab != resolved) {
            activeSubTab = resolved
        }
    }

    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val coroutineScope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState()
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateDialogState by remember { mutableStateOf<UpdateDialogState?>(null) }
    var availableUpdate by remember {
        mutableStateOf<UpdateCheckInfo?>(
            AppUpdateChecker.peekCachedUpdate(BuildConfig.VERSION_NAME)
        )
    }
    var dismissedUpdateTag by rememberSaveable { mutableStateOf<String?>(null) }

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
                    UpdateDialogState.UpdateAvailable(info)
                } else {
                    UpdateDialogState.UpToDate(info)
                }
            }.onFailure { err ->
                updateDialogState = UpdateDialogState.Error(
                    err.message ?: AppStrings.pick(
                        appLang,
                        "检查更新失败，请稍后重试或前往 GitHub Releases 手动查看",
                        "Update check failed. Try again later or check GitHub Releases manually.",
                        "檢查更新失敗，請稍後重試或前往 GitHub Releases 手動查看"
                    )
                )
            }
        }
    }

    fun selectSubTab(index: Int) {
        val resolved = if (index in validSubTabs) index else AppDestinations.SUBTAB_API
        activeSubTab = resolved
        onSubTabChanged?.invoke(resolved)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned Top Studio Header + 3-Segment TypeFree Capsule Switcher
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = tr("设置与工作台", "Settings & Studio", "設定與工作台"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Surface(
                    onClick = { triggerUpdateCheck() },
                    enabled = !isCheckingUpdate,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
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
                        }
                        Text(
                            text = if (isCheckingUpdate) {
                                tr("检查更新中…", "Checking…", "檢查更新中…")
                            } else {
                                "v${BuildConfig.VERSION_NAME} · BYOK"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Three-Segment Capsule Bar
            val bannerUpdate = availableUpdate?.takeIf { it.hasUpdate && it.latestTag != dismissedUpdateTag }
            AnimatedVisibility(visible = bannerUpdate != null) {
                bannerUpdate?.let { info ->
                    SettingsUpdatePromptBanner(
                        info = info,
                        onUpdateClick = {
                            updateDialogState = UpdateDialogState.UpdateAvailable(info)
                        },
                        onDismiss = {
                            dismissedUpdateTag = info.latestTag
                        }
                    )
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StudioSubTabPill(
                        title = tr("自定义 API", "Custom API", "自訂 API"),
                        selected = activeSubTab == AppDestinations.SUBTAB_API,
                        onClick = { selectSubTab(AppDestinations.SUBTAB_API) },
                        modifier = Modifier.weight(1f)
                    )
                    StudioSubTabPill(
                        title = tr("翻译与风格", "Translate & Style", "翻譯與風格"),
                        selected = activeSubTab == AppDestinations.SUBTAB_STYLES,
                        onClick = { selectSubTab(AppDestinations.SUBTAB_STYLES) },
                        modifier = Modifier.weight(1f)
                    )
                    StudioSubTabPill(
                        title = tr("外观与偏好", "Preferences", "外觀與偏好"),
                        selected = activeSubTab == AppDestinations.SUBTAB_PREFERENCES,
                        onClick = { selectSubTab(AppDestinations.SUBTAB_PREFERENCES) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Sub-Tab Body Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (activeSubTab) {
                AppDestinations.SUBTAB_API -> {
                    ApiSettingsScreen(
                        repository = repository,
                        aiEngine = aiEngine,
                        showScreenHeader = false
                    )
                }

                AppDestinations.SUBTAB_STYLES -> {
                    StylesAppAwareScreen(
                        repository = repository,
                        showScreenHeader = false,
                        headerSlot = {
                            TranslationTargetsManagementCard(repository = repository)
                        }
                    )
                }

                AppDestinations.SUBTAB_PREFERENCES -> {
                    AppearanceAndPreferencesPane(
                        repository = repository,
                        onJumpToApiSubTab = { selectSubTab(AppDestinations.SUBTAB_API) },
                        isCheckingUpdate = isCheckingUpdate,
                        onCheckUpdateClick = { triggerUpdateCheck() },
                        onOpenReleasesClick = {
                            openExternalUrl(
                                context,
                                AppUpdateChecker.GITHUB_RELEASES_PAGE_URL,
                                appLang
                            )
                        }
                    )
                }
            }
        }
    }

    updateDialogState?.let { dialogState ->
        AppUpdateDialog(
            state = dialogState,
            onOpenUrl = { url -> openExternalUrl(context, url, appLang) },
            onDismiss = { updateDialogState = null }
        )
    }
}

/**
 * Official-style Translation Targets Management Card for the Arc Quick-Translate Wheel
 * (`settings.translationTargets` + `settings.activeTranslateTarget`).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TranslationTargetsManagementCard(repository: AppRepository) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val settings by repository.settings.collectAsState()
    val targets = settings.translationTargets.ifEmpty { DefaultPresets.defaultTranslationTargets() }
    val activeTarget = settings.activeTranslateTarget.ifBlank { targets.first() }

    var showAddLangDialog by remember { mutableStateOf(false) }

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
            // Card Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = tr("翻译目标语言管理", "Translation Target Languages", "翻譯目標語言管理"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr(
                                "键盘长按说话上滑唤起弧形转盘，松手直译目标外语",
                                "Hold Mic & slide up for the arc wheel; release to translate directly",
                                "鍵盤長按說話上滑喚起弧形轉盤，鬆手直譯目標外語"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TextButton(
                    onClick = {
                        val defaults = DefaultPresets.defaultTranslationTargets()
                        repository.updateSettings {
                            it.copy(
                                translationTargets = defaults,
                                activeTranslateTarget = defaults.first()
                            )
                        }
                        Toast.makeText(
                            context,
                            AppStrings.pick(
                                appLang,
                                "已恢复默认翻译目标语言列表",
                                "Restored default translation languages",
                                "已恢復預設翻譯目標語言清單"
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(tr("默认", "Reset", "預設"), style = MaterialTheme.typography.labelMedium)
                }
            }

            // Active Default Translate Target Summary Pill
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.Globe,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = tr(
                                "默认快译目标：$activeTarget",
                                "Default Target: $activeTarget",
                                "預設快譯目標：$activeTarget"
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = tr(
                            "共 ${targets.size} 种激活语言",
                            "${targets.size} active",
                            "共 ${targets.size} 種啟用語言"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Ordered Language List with Up / Down / Set Default / Remove
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEachIndexed { index, lang ->
                    val isDefault = lang == activeTarget
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDefault) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                        },
                        border = BorderStroke(
                            width = if (isDefault) 1.2.dp else 1.dp,
                            color = if (isDefault) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isDefault) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDefault) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }

                                Text(
                                    text = lang,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isDefault) FontWeight.Bold else FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (isDefault) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    ) {
                                        Text(
                                            text = tr("默认", "Default", "預設"),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                if (!isDefault) {
                                    TextButton(
                                        onClick = {
                                            repository.updateSettings {
                                                it.copy(activeTranslateTarget = lang)
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(tr("设为默认", "Set Default", "設為預設"), style = MaterialTheme.typography.labelSmall)
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val mutable = targets.toMutableList()
                                            val tmp = mutable[index - 1]
                                            mutable[index - 1] = mutable[index]
                                            mutable[index] = tmp
                                            repository.updateSettings {
                                                it.copy(translationTargets = mutable)
                                            }
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = tr("上移", "Move up", "上移"),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        if (index < targets.lastIndex) {
                                            val mutable = targets.toMutableList()
                                            val tmp = mutable[index + 1]
                                            mutable[index + 1] = mutable[index]
                                            mutable[index] = tmp
                                            repository.updateSettings {
                                                it.copy(translationTargets = mutable)
                                            }
                                        }
                                    },
                                    enabled = index < targets.lastIndex,
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = tr("下移", "Move down", "下移"),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                if (targets.size > 1) {
                                    IconButton(
                                        onClick = {
                                            val remaining = targets.filterIndexed { i, _ -> i != index }
                                            val nextDefault = if (activeTarget == lang) {
                                                remaining.first()
                                            } else {
                                                activeTarget
                                            }
                                            repository.updateSettings {
                                                it.copy(
                                                    translationTargets = remaining,
                                                    activeTranslateTarget = nextDefault
                                                )
                                            }
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = tr("移除语言", "Remove language", "移除語言"),
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

            FilledTonalButton(
                onClick = { showAddLangDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = tr(
                        "添加翻译目标语言（支持官方语言库或自定义语种）",
                        "Add Target Language (Presets or Custom)",
                        "新增翻譯目標語言（支援官方語言庫或自訂語種）"
                    ),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (showAddLangDialog) {
        AddTranslationLanguageDialog(
            currentTargets = targets,
            onDismiss = { showAddLangDialog = false },
            onAddLanguage = { newLang ->
                val clean = newLang.trim()
                if (clean.isNotEmpty()) {
                    repository.updateSettings { curr ->
                        val existing = curr.translationTargets.ifEmpty { DefaultPresets.defaultTranslationTargets() }
                        if (existing.any { it.equals(clean, ignoreCase = true) }) {
                            curr.copy(activeTranslateTarget = existing.first { it.equals(clean, ignoreCase = true) })
                        } else {
                            curr.copy(
                                translationTargets = existing + clean,
                                activeTranslateTarget = clean
                            )
                        }
                    }
                    Toast.makeText(
                        context,
                        AppStrings.pick(
                            appLang,
                            "已添加并设为默认快译语言：$clean",
                            "Added & set as default target: $clean",
                            "已新增並設為預設快譯語言：$clean"
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                showAddLangDialog = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AddTranslationLanguageDialog(
    currentTargets: List<String>,
    onDismiss: () -> Unit,
    onAddLanguage: (String) -> Unit
) {
    var customLanguage by rememberSaveable { mutableStateOf("") }
    var pickedPreset by rememberSaveable { mutableStateOf("") }

    val availableOfficial = remember(currentTargets) {
        val lowerExisting = currentTargets.map { it.lowercase() }.toSet()
        DefaultPresets.allSupportedLanguages.filter { it.lowercase() !in lowerExisting }
    }

    val effectiveChoice = customLanguage.trim().ifBlank { pickedPreset.trim() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("添加弧形快译目标语言", "Add Quick-Translate Language", "新增弧形快譯目標語言")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (availableOfficial.isNotEmpty()) {
                    Text(
                        text = tr("从常用语言库一键选择", "Pick from common languages", "從常用語言庫一鍵選擇"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableOfficial.forEach { lang ->
                            val selected = pickedPreset == lang && customLanguage.isBlank()
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    pickedPreset = lang
                                    customLanguage = ""
                                },
                                label = { Text(lang) },
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
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                OutlinedTextField(
                    value = customLanguage,
                    onValueChange = { customLanguage = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = {
                        Text(
                            tr(
                                "或输入任意自定义语言 / 方言 / 文体",
                                "Or enter any custom language / dialect / register",
                                "或輸入任意自訂語言 / 方言 / 文體"
                            )
                        )
                    },
                    placeholder = {
                        Text(
                            tr(
                                "如：粤语、Nederlands、商务敬语日文",
                                "e.g. Cantonese, Nederlands, Polite Business Japanese",
                                "如：粵語、Nederlands、商務敬語日文"
                            )
                        )
                    }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = effectiveChoice.isNotEmpty(),
                onClick = { onAddLanguage(effectiveChoice) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(tr("添加语言", "Add Language", "新增語言"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消", "Cancel", "取消"))
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceAndPreferencesPane(
    repository: AppRepository,
    onJumpToApiSubTab: () -> Unit = {},
    isCheckingUpdate: Boolean = false,
    onCheckUpdateClick: () -> Unit = {},
    onOpenReleasesClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val settings by repository.settings.collectAsState()
    val clipboardItems by repository.clipboardItems.collectAsState()
    var showKeyboardGesturesDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Official TypeFree Appearance Switcher Card
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
                        text = tr("外观主题", "Appearance Theme", "外觀主題"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = tr(
                            "切换宿主应用、内置语音键盘与悬浮语音胶囊的视觉配色",
                            "Choose visual theme for the app, voice keyboard, and floating capsule",
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
                    ThemePreviewOptionCard(
                        title = tr("浅色单色", "Light", "淺色單色"),
                        subtitle = tr("TypeFree 经典", "Classic", "TypeFree 經典"),
                        preference = ThemePreference.LIGHT,
                        selected = settings.themePreference == ThemePreference.LIGHT,
                        onClick = {
                            repository.updateSettings { it.copy(themePreference = ThemePreference.LIGHT) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ThemePreviewOptionCard(
                        title = tr("深色曜石", "Dark", "深色曜石"),
                        subtitle = tr("沉浸暗色", "Obsidian", "沉浸暗色"),
                        preference = ThemePreference.DARK,
                        selected = settings.themePreference == ThemePreference.DARK,
                        onClick = {
                            repository.updateSettings { it.copy(themePreference = ThemePreference.DARK) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ThemePreviewOptionCard(
                        title = tr("跟随系统", "System", "跟隨系統"),
                        subtitle = tr("日夜自适应", "Auto", "日夜自適應"),
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

        // 2. Built-in Keyboard & Input Scheme Preferences Card
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
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = tr("打字键盘与输入方案", "Keyboard & Input Schemes", "打字鍵盤與輸入方案"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr(
                                "切换面板高度稳定 · 支持顶部数字行与多种中文输入方案",
                                "Stable panel height · Optional number row & multiple input schemes",
                                "切換面板高度穩定 · 支援頂部數字列與多種中文輸入方案"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { showKeyboardGesturesDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(tr("手势速查", "Gestures", "手勢速查"), style = MaterialTheme.typography.labelLarge)
                    }
                }

                PreferenceSwitchRow(
                    title = tr("记忆上一次使用的键盘模式", "Remember Last Keyboard Mode", "記憶上一次使用的鍵盤模式"),
                    subtitle = tr(
                        "收起键盘或切换应用后自动恢复上次面板；密码框临时切英文后自动还原",
                        "Restores your last panel across apps; auto-reverts after password fields",
                        "收起鍵盤或切換應用後自動恢復上次面板；密碼框臨時切英文後自動還原"
                    ),
                    checked = settings.rememberLastKeyboardMode,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(rememberLastKeyboardMode = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = tr("顶部独立数字行 (1–0)", "Dedicated Number Row (1–0)", "頂部獨立數字列 (1–0)"),
                    subtitle = tr(
                        "默认关闭（Q–P 长按或上滑直输 1–0）；开启后显示顶部独立数字行",
                        "Off by default (hold/swipe Q–P for 1–0); turn on to show a dedicated top row",
                        "預設關閉（Q–P 長按或上滑直輸 1–0）；開啟後顯示頂部獨立數字列"
                    ),
                    checked = settings.qwertyShowNumberRow,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(qwertyShowNumberRow = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                // Unified Keyboard Bottom Cushion
                val currentLiftLevel = settings.keyboardLiftLevel.coerceIn(
                    KeyboardMetrics.MIN_LIFT_LEVEL,
                    KeyboardMetrics.MAX_LIFT_LEVEL
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                text = "${tr("底部垫高", "Bottom Cushion Lift", "底部墊高")} · ${KeyboardMetrics.labelForLiftLevel(currentLiftLevel)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = tr(
                                    "仅在键盘底部增加防误触留白托起按键区，所有面板同步生效",
                                    "Adds bottom padding beneath keys to prevent edge mistaps across all panels",
                                    "僅在鍵盤底部增加防誤觸留白托起按鍵區，所有面板同步生效"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (currentLiftLevel != KeyboardMetrics.DEFAULT_LIFT_LEVEL) {
                            TextButton(
                                onClick = {
                                    repository.updateSettings {
                                        it.copy(
                                            keyboardHeightMode = KeyboardHeightMode.LIFT,
                                            keyboardLiftLevel = KeyboardMetrics.DEFAULT_LIFT_LEVEL
                                        )
                                    }
                                }
                            ) {
                                Text(tr("恢复默认", "Reset", "恢復預設"), fontSize = 12.sp)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        KeyboardMetrics.ALL_LIFT_LEVELS.forEach { level ->
                            val selected = level == currentLiftLevel
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    repository.updateSettings {
                                        it.copy(
                                            keyboardHeightMode = KeyboardHeightMode.LIFT,
                                            keyboardLiftLevel = level
                                        )
                                    }
                                },
                                label = {
                                    Text(
                                        text = KeyboardMetrics.labelForLiftLevel(level),
                                        fontSize = 12.sp,
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
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                // Input Scheme (全拼 / 小鹤双拼 / 小鹤音形)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${tr("输入方案", "Input Scheme", "輸入方案")} · ${AppStrings.inputSchemeLabel(settings.inputScheme, appLang)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = tr(
                                "选择中文打字编码方案（键盘工具面板亦可一键循环切换）",
                                "Select Chinese input scheme (also switchable from the keyboard tool panel)",
                                "選擇中文打字編碼方案（鍵盤工具面板亦可一鍵循環切換）"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val schemeOptions = remember(appLang) {
                        listOf(
                            InputScheme.RIME_PINYIN to AppStrings.pick(appLang, "全拼", "Pinyin", "全拼"),
                            InputScheme.RIME_T9_PINYIN to AppStrings.pick(appLang, "九键拼音", "T9 Pinyin", "九鍵拼音"),
                            InputScheme.RIME_DOUBLE_PINYIN_FLYPY to AppStrings.pick(appLang, "小鹤双拼", "Flypy DP", "小鶴雙拼"),
                            InputScheme.FLYPY_TABLE to AppStrings.pick(appLang, "小鹤音形", "Flypy 4-Code", "小鶴音形")
                        )
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        schemeOptions.forEach { (scheme, shortTitle) ->
                            val selected = settings.inputScheme == scheme
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    repository.updateSettings { it.copy(inputScheme = scheme) }
                                },
                                label = {
                                    Text(
                                        text = shortTitle,
                                        fontSize = 12.sp,
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
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = tr("AI 整句补全", "AI Inline Completion", "AI 整句補全"),
                    subtitle = tr(
                        "停顿后把光标前文字发送到你配置的模型生成续写建议（默认关闭，密码框与无痕模式永不触发）",
                        "Sends text before cursor to your configured model after a brief pause for inline suggestions (never triggers in passwords or incognito)",
                        "停頓後把游標前文字傳送到你設定的模型產生續寫建議（預設關閉，密碼框與無痕模式永不觸發）"
                    ),
                    checked = settings.aiCompletionEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(aiCompletionEnabled = enabled) }
                    }
                )
                CompletionBackendHint(
                    settings = settings,
                    registrySnapshot = rememberLocalModelRegistrySnapshot()
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = tr("按键按下气泡放大预览", "Keypress Popup Preview", "按鍵按下氣泡放大預覽"),
                    subtitle = tr(
                        "按下字母或符号键时在键帽上方浮现放大预览气泡",
                        "Shows a magnified preview bubble above letter and symbol keys on press",
                        "按下字母或符號鍵時在鍵帽上方浮現放大預覽氣泡"
                    ),
                    checked = settings.qwertyKeyPopupPreview,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(qwertyKeyPopupPreview = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = tr("打字面板默认开启中文输入", "Default to Chinese Input on QWERTY", "打字面板預設開啟中文輸入"),
                    subtitle = tr(
                        "长按空格或点顶栏中/英按钮可随时切换",
                        "Hold Space or tap the CN/EN button on the top bar anytime to switch",
                        "長按空白鍵或點頂欄中/英按鈕可隨時切換"
                    ),
                    checked = settings.flypyEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(flypyEnabled = enabled) }
                    }
                )

                AnimatedVisibility(visible = settings.inputScheme == InputScheme.FLYPY_TABLE) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                        PreferenceSwitchRow(
                            title = tr(
                                "候选状态下「, / .」快捷选第 2 / 第 3 候选",
                                "Quick-Select 2nd / 3rd Candidate with ', / .'",
                                "候選狀態下「, / .」快捷選第 2 / 第 3 候選"
                            ),
                            subtitle = tr(
                                "有重码时底排「,」上屏次选、「.」上屏三选；关闭则用逗号/句号顶字上屏",
                                "While composing, ',' commits 2nd candidate and '.' commits 3rd; when off, commits 1st + punctuation",
                                "有重碼時底排「,」上屏次選、「.」上屏三選；關閉則用逗號/句號頂字上屏"
                            ),
                            checked = settings.flypyCommaPeriodQuickSelect,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(flypyCommaPeriodQuickSelect = enabled) }
                            }
                        )
                    }
                }
            }
        }

        if (showKeyboardGesturesDialog) {
            AlertDialog(
                onDismissRequest = { showKeyboardGesturesDialog = false },
                title = { Text(tr("打字键盘快捷手势速查", "Keyboard Gesture Cheat Sheet", "打字鍵盤快捷手勢速查")) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val gestureTips = listOf(
                            tr(
                                "编码与上屏：四码唯一自动上屏；第五码顶字上屏；Shift 有码时清码",
                                "Code & Commit: Unique 4-code auto-commits; 5th key pushes 1st candidate; Shift clears active code",
                                "編碼與上屏：四碼唯一自動上屏；第五碼頂字上屏；Shift 有碼時清碼"
                            ),
                            tr(
                                "中英切换：长按空格键或点顶栏中/英按钮随时切换",
                                "CN / EN Toggle: Hold Space or tap the top-bar CN/EN switch anytime",
                                "中英切換：長按空白鍵或點頂欄中/英按鈕隨時切換"
                            ),
                            tr(
                                "通配反查：长按 Z 输入 ` 反查（如 ji` 知音查形、`bo 知形查音）",
                                "Reverse Lookup: Hold Z to type ` for wildcard/reverse lookup (e.g. ji` or `bo)",
                                "萬用反查：長按 Z 輸入 ` 反查（如 ji` 知音查形、`bo 知形查音）"
                            ),
                            tr(
                                "分号快符：长按 L 输入分号快符（;q ：“、;w ？、;f 重复上屏）",
                                "Semicolon Symbols: Hold L for `;` quick symbols (;q quotes, ;w ?, ;f repeat last)",
                                "分號快符：長按 L 輸入分號快符（;q ：“、;w ？、;f 重複上屏）"
                            ),
                            tr(
                                "快捷日期时间：orq 输出日期，ouj 输出时间",
                                "Date & Time: Type `orq` for current date, `ouj` for current time",
                                "快捷日期時間：orq 輸出日期，ouj 輸出時間"
                            ),
                            tr(
                                "编辑与撤回：长按 A/X/C/V 全选/剪切/复制/粘贴；退格键上滑撤回上屏",
                                "Edit & Undo: Hold A/X/C/V for Select All/Cut/Copy/Paste; swipe up Backspace to undo",
                                "編輯與復原：長按 A/X/C/V 全選/剪下/複製/貼上；退格鍵上滑復原上屏"
                            )
                        )
                        gestureTips.forEach { tip ->
                            Text(
                                text = "• $tip",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showKeyboardGesturesDialog = false }) {
                        Text(tr("知道了", "Got It", "知道了"))
                    }
                }
            )
        }

        // 3. Built-in IME Clipboard Card
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
                            text = tr("剪贴板与常用语", "Clipboard & Pinned Phrases", "剪貼簿與常用語"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr(
                                "键盘顶栏可唤起双列剪贴板，可在剪贴板面板内直接置顶常用语",
                                "Open the two-column clipboard from the top bar and pin frequent snippets",
                                "鍵盤頂欄可喚起雙欄剪貼簿，可在剪貼簿面板內直接置頂常用語"
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
                                    AppStrings.pick(
                                        appLang,
                                        "已清空未置顶剪贴板记录",
                                        "Cleared unpinned clipboard items",
                                        "已清空未置頂剪貼簿記錄"
                                    ),
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
                        "Stores up to 200 copied texts and filters sensitive password manager entries",
                        "自動收納最近 200 條複製文字，自動過濾密碼管理器敏感內容"
                    ),
                    checked = settings.clipboardEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(clipboardEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = tr("顶栏刚复制内容一键粘贴提示", "Top-Bar Quick Paste Chip", "頂欄剛複製內容一鍵貼上提示"),
                    subtitle = tr(
                        "复制短信验证码、链接或文本后在键盘顶栏浮现快捷胶囊，一键填入",
                        "Shows a one-tap paste chip on the top bar right after copying codes, links, or text",
                        "複製簡訊驗證碼、連結或文字後在鍵盤頂欄浮現快捷膠囊，一鍵填入"
                    ),
                    checked = settings.clipboardQuickPasteSuggestion,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(clipboardQuickPasteSuggestion = enabled) }
                    }
                )
            }
        }

        // 4A. Voice Capture & Commit Card
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
                    text = tr("语音收音与上屏", "Voice Capture & Commit", "語音收音與上屏"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                PreferenceSwitchRow(
                    title = tr("实时写入输入框", "Live Streaming to Text Field", "即時寫入輸入框"),
                    subtitle = tr(
                        "边说边上屏，未确定的部分会自动修正；部分 App 不支持时自动回退",
                        "Streams text live while speaking and refines in place; falls back automatically if unsupported",
                        "邊說邊上屏，未確定的部分會自動修正；部分 App 不支援時自動回退"
                    ),
                    checked = settings.liveWriteToEditor,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(liveWriteToEditor = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = tr("🎵 录音时自动暂停外放媒体", "🎵 Pause Media While Recording", "🎵 錄音時自動暫停外放媒體"),
                    subtitle = tr(
                        "录音时自动暂停正在播放的音乐或视频，松手后恢复",
                        "Pauses active music or video playback during recording and resumes on release",
                        "錄音時自動暫停正在播放的音樂或影片，鬆手後恢復"
                    ),
                    checked = settings.pauseMediaWhileRecording,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(pauseMediaWhileRecording = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = tr("🤫 轻声高灵敏收音", "🤫 Whisper High-Gain Mode", "🤫 輕聲高靈敏收音"),
                    subtitle = tr(
                        "适合安静环境下贴近麦克风轻声说话，语音面板也可切换",
                        "Boosts mic sensitivity for quiet speaking; also toggleable on the voice panel",
                        "適合安靜環境下貼近麥克風輕聲說話，語音面板也可切換"
                    ),
                    checked = settings.whisperModeEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(whisperModeEnabled = enabled) }
                    }
                )
            }
        }

        // 4B. AI Assistance & Screen-Reading Privacy Card
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
                    text = tr("AI 辅助与读屏隐私", "AI Assistance & Privacy", "AI 輔助與讀屏隱私"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                PreferenceSwitchRow(
                    title = tr("🔍 顶栏「问AI」随手查胶囊", "🔍 Top-Bar \"Ask AI\" Chip", "🔍 頂欄「問AI」隨手查膠囊"),
                    subtitle = tr(
                        "在普通输入框打字满 2 个字后，键盘顶栏自动浮现「🔍 问AI」，点击在键盘内弹出解答卡片",
                        "Shows \"🔍 Ask AI\" on the top bar after typing 2+ chars to answer queries inline",
                        "在一般輸入框打字滿 2 個字後，鍵盤頂欄自動浮現「🔍 問AI」，點擊在鍵盤內彈出解答卡片"
                    ),
                    checked = settings.equalEqualAskAiEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(equalEqualAskAiEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = tr("光标上文语境感知", "Surrounding Text Context", "游標上文語境感知"),
                    subtitle = tr(
                        "自动读取光标前最多 80 字上下文，使标点衔接、人称代词与续写更自然",
                        "Reads up to 80 chars before the cursor for natural punctuation, pronouns, and continuation",
                        "自動讀取游標前最多 80 字上下文，使標點銜接、人稱代名詞與續寫更自然"
                    ),
                    checked = settings.useSurroundingContext,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(useSurroundingContext = enabled) }
                    }
                )
            }
        }

        // 4C. Sound, Haptics & Floating Bubble Card
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
                    text = tr("声音触感与悬浮球", "Sound, Haptics & Floating Capsule", "聲音觸感與懸浮球"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                PreferenceSwitchRow(
                    title = tr("按键与录音提示音", "Keypress & Recording Sounds", "按鍵與錄音提示音"),
                    subtitle = tr(
                        "点击麦克风开始聆听、结束录音及按键反馈轻音效",
                        "Subtle sound effects when starting/stopping recording and pressing keys",
                        "點擊麥克風開始聆聽、結束錄音及按鍵回饋輕音效"
                    ),
                    checked = settings.interactionSounds,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(interactionSounds = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = tr("触觉振动反馈", "Haptic Vibration", "觸覺震動回饋"),
                    subtitle = tr(
                        "按下说话键、弧形转盘选词及文字上屏时提供振动反馈",
                        "Tactile feedback on mic press, arc wheel selection, and text commit",
                        "按下說話鍵、弧形轉盤選詞及文字上屏時提供震動回饋"
                    ),
                    checked = settings.hapticFeedback,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(hapticFeedback = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = tr("启用悬浮语音胶囊（免切键盘语音输入）", "Floating Voice Capsule", "啟用懸浮語音膠囊（免切鍵盤語音輸入）"),
                    subtitle = if (settings.bubbleDockLeft) {
                        tr(
                            "当前停靠在屏幕左侧边缘 · 可自由拖拽改变位置",
                            "Docked on left screen edge · Drag freely to reposition",
                            "目前停靠在螢幕左側邊緣 · 可自由拖曳改變位置"
                        )
                    } else {
                        tr(
                            "当前停靠在屏幕右侧边缘 · 可自由拖拽改变位置",
                            "Docked on right screen edge · Drag freely to reposition",
                            "目前停靠在螢幕右側邊緣 · 可自由拖曳改變位置"
                        )
                    },
                    checked = settings.bubbleEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !TypeFreeBubbleController.hasOverlayPermission(context)) {
                            TypeFreeBubbleController.openOverlayPermissionSettings(context)
                        }
                        repository.updateSettings { it.copy(bubbleEnabled = enabled) }
                        TypeFreeBubbleController.syncBubbleService(context)
                    }
                )

                if (settings.bubbleEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                repository.updateSettings {
                                    it.copy(
                                        bubbleDockLeft = !it.bubbleDockLeft,
                                        bubbleYRatio = 0.48f
                                    )
                                }
                                TypeFreeBubbleController.syncBubbleService(context)
                                Toast.makeText(
                                    context,
                                    AppStrings.pick(
                                        appLang,
                                        "已重置悬浮胶囊停靠位置",
                                        "Floating capsule position reset",
                                        "已重設懸浮膠囊停靠位置"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (settings.bubbleDockLeft) {
                                    tr("切换至右侧停靠并居中", "Dock to Right Edge & Center", "切換至右側停靠並置中")
                                } else {
                                    tr("切换至左侧停靠并居中", "Dock to Left Edge & Center", "切換至左側停靠並置中")
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }

        // 5. TypeFree About & Privacy Card
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
                                "v${BuildConfig.VERSION_NAME} · BYOK Edition",
                                "v${BuildConfig.VERSION_NAME} · BYOK 自有介面版"
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
                                text = tr(
                                    "自有 API 直连 · 数据仅存本机",
                                    "Direct BYOK Connection · Local-First Data",
                                    "自有 API 直連 · 資料僅存本機"
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = tr(
                                "• 自有接口直连：音频与文本由手机直接发送至你配置的 Gemini、DeepSeek、阿里云百炼、火山豆包、硅基流动或自定义网关接口，无第三方中转。\n• 本机存储：API Key、历史记录、个人词典、剪贴板与风格配置均保存在当前设备本地。\n• v3.9 主要功能：支持智能英文键盘、流式语音与离线 AI、雾凇/小鹤中文键盘、✍️ 帮我写与选中改写、🔍 问AI、弧形快译与智能剪贴板。",
                                "• Direct API Connection: Audio and text are sent directly from your phone to your configured Gemini, OpenAI, DeepSeek, DashScope, Doubao, SiliconFlow, or custom gateway with zero middleman servers.\n• On-Device Storage: API keys, history, personal dictionary, clipboard, and tone styles stay locally on this device.\n• Key Features: Smart English QWERTY, streaming & offline voice AI, Rime/Flypy keyboard, ✍️ Help Me Write & Rewrite, 🔍 Ask AI, Arc Translate, and Smart Clipboard.",
                                "• 自有介面直連：音訊與文字由手機直接傳送至你設定的 Gemini、DeepSeek、阿里雲百煉、火山豆包、矽基流動或自訂閘道介面，無第三方中轉。\n• 本機儲存：API Key、歷史記錄、個人詞典、剪貼簿與風格設定均儲存在目前裝置本機。\n• 核心功能：支援智慧英文鍵盤、串流與離線語音 AI、霧凇/小鶴中文鍵盤、✍️ 幫我寫與選取改寫、🔍 問AI、弧形快譯與智慧剪貼簿。"
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
                        onClick = onCheckUpdateClick,
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
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(tr("检查更新", "Check Updates", "檢查更新"), style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenReleasesClick,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("GitHub Releases", style = MaterialTheme.typography.labelLarge)
                    }
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
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

private sealed interface UpdateDialogState {
    data class UpdateAvailable(val info: UpdateCheckInfo) : UpdateDialogState
    data class UpToDate(val info: UpdateCheckInfo) : UpdateDialogState
    data class Error(val message: String) : UpdateDialogState
}

@Composable
private fun AppUpdateDialog(
    state: UpdateDialogState,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        is UpdateDialogState.UpdateAvailable -> {
            val info = state.info
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        tr(
                            "发现新版本 v${info.latestVersion}",
                            "New Version v${info.latestVersion} Available",
                            "發現新版本 v${info.latestVersion}"
                        )
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (info.requiresPrivateDictWarning) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = TypeFreeAmber.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, TypeFreeAmber.copy(alpha = 0.65f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = TypeFreeAmber,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(18.dp)
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = tr(
                                                "防误覆盖提醒（私有词库定制包）",
                                                "Custom Private Lexicon Warning",
                                                "防誤覆蓋提醒（私有詞庫客製包）"
                                            ),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = tr(
                                                "当前为私有小鹤全量词库定制包，GitHub 公开 Release APK 仅含公开词表，直接覆盖安装会替换内置私有词库。",
                                                "This build bundles a private full Flypy lexicon. Public GitHub Release APKs include public tables only and will overwrite the private lexicon if installed directly.",
                                                "目前為私有小鶴全量詞庫客製包，GitHub 公開 Release APK 僅含公開詞表，直接覆蓋安裝會替換內建私有詞庫。"
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "v${info.currentVersion} → v${info.latestVersion}",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val dateText = info.publishedAt.take(10)
                                if (dateText.isNotBlank()) {
                                    Text(
                                        text = dateText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (info.releaseName.isNotBlank() &&
                                    !info.releaseName.equals(info.latestTag, ignoreCase = true)
                                ) {
                                    Text(
                                        text = info.releaseName,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = info.releaseNotes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 19.sp
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onOpenUrl(info.apkDownloadUrl ?: info.htmlUrl)
                            onDismiss()
                        }
                    ) {
                        Text(tr("浏览器下载 APK", "Download APK", "瀏覽器下載 APK"))
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                onOpenUrl(info.htmlUrl)
                                onDismiss()
                            }
                        ) {
                            Text(tr("打开发布页", "Release Page", "開啟發佈頁"))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(tr("暂不更新", "Later", "暫不更新"))
                        }
                    }
                }
            )
        }

        is UpdateDialogState.UpToDate -> {
            val info = state.info
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(tr("已是最新版本", "Up to Date", "已是最新版本")) },
                text = {
                    Text(
                        text = tr(
                            "当前版本 v${info.currentVersion} 已是最新正式版（GitHub 最新版本：${info.latestTag}）。",
                            "Current version v${info.currentVersion} is already up to date (latest on GitHub: ${info.latestTag}).",
                            "目前版本 v${info.currentVersion} 已是最新正式版（GitHub 最新版本：${info.latestTag}）。"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(tr("知道了", "OK", "知道了"))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            onOpenUrl(info.htmlUrl)
                            onDismiss()
                        }
                    ) {
                        Text(tr("查看 Releases", "View Releases", "查看 Releases"))
                    }
                }
            )
        }

        is UpdateDialogState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(tr("检查更新失败", "Update Check Failed", "檢查更新失敗")) },
                text = {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onOpenUrl(AppUpdateChecker.GITHUB_RELEASES_PAGE_URL)
                            onDismiss()
                        }
                    ) {
                        Text(tr("手动查看 Releases", "Open Releases", "手動查看 Releases"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(tr("关闭", "Close", "關閉"))
                    }
                }
            )
        }
    }
}

private fun openExternalUrl(
    context: Context,
    url: String,
    lang: AppLang = AppStrings.resolveLang("system")
) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(
            context,
            AppStrings.pick(
                lang,
                "未能打开系统浏览器，请手动访问 GitHub Releases",
                "Could not open browser. Please visit GitHub Releases manually.",
                "未能開啟系統瀏覽器，請手動造訪 GitHub Releases"
            ),
            Toast.LENGTH_SHORT
        ).show()
    }
}

@Composable
private fun ThemePreviewOptionCard(
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
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Miniature Theme Canvas Swatch
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                        RoundedCornerShape(9.dp)
                    )
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
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color(0xFFF6F6F8))
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color(0xFF141519))
                            )
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
                        color = MaterialTheme.colorScheme.onSurface,
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
