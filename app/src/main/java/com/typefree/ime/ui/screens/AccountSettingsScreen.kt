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
import com.typefree.ime.BuildConfig
import com.typefree.ime.bubble.TypeFreeBubbleController
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.ThemePreference
import com.typefree.ime.ime.KeyboardMetrics
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.AppUpdateChecker
import com.typefree.ime.network.UpdateCheckInfo
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.TypeFreeIcons
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
    val coroutineScope = rememberCoroutineScope()
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateDialogState by remember { mutableStateOf<UpdateDialogState?>(null) }

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
                updateDialogState = if (info.hasUpdate) {
                    UpdateDialogState.UpdateAvailable(info)
                } else {
                    UpdateDialogState.UpToDate(info)
                }
            }.onFailure { err ->
                updateDialogState = UpdateDialogState.Error(
                    err.message ?: "检查更新失败，请稍后重试或前往 GitHub Releases 手动查看"
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
                    text = "设置与工作台",
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
                            text = if (isCheckingUpdate) "检查更新中…" else "v${BuildConfig.VERSION_NAME} · BYOK",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Three-Segment Capsule Bar
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
                        title = "自定义 API",
                        selected = activeSubTab == AppDestinations.SUBTAB_API,
                        onClick = { selectSubTab(AppDestinations.SUBTAB_API) },
                        modifier = Modifier.weight(1f)
                    )
                    StudioSubTabPill(
                        title = "翻译与风格",
                        selected = activeSubTab == AppDestinations.SUBTAB_STYLES,
                        onClick = { selectSubTab(AppDestinations.SUBTAB_STYLES) },
                        modifier = Modifier.weight(1f)
                    )
                    StudioSubTabPill(
                        title = "外观与偏好",
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
                            openExternalUrl(context, AppUpdateChecker.GITHUB_RELEASES_PAGE_URL)
                        }
                    )
                }
            }
        }
    }

    updateDialogState?.let { dialogState ->
        AppUpdateDialog(
            state = dialogState,
            onOpenUrl = { url -> openExternalUrl(context, url) },
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
                            text = "翻译目标语言管理",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "键盘长按说话上滑唤起弧形转盘，松手直译目标外语",
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
                        Toast.makeText(context, "已恢复默认翻译目标语言列表", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("默认", style = MaterialTheme.typography.labelMedium)
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
                            text = "默认快译目标：$activeTarget",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "共 ${targets.size} 种激活语言",
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
                                            text = "默认",
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
                                        Text("设为默认", style = MaterialTheme.typography.labelSmall)
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
                                        contentDescription = "上移",
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
                                        contentDescription = "下移",
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
                                            contentDescription = "移除语言",
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
                    text = "添加翻译目标语言（支持官方语言库或自定义语种）",
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
                    Toast.makeText(context, "已添加并设为默认快译语言：$clean", Toast.LENGTH_SHORT).show()
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
        title = { Text("添加弧形快译目标语言") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (availableOfficial.isNotEmpty()) {
                    Text(
                        text = "从常用语言库一键选择",
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
                    label = { Text("或输入任意自定义语言 / 方言 / 文体") },
                    placeholder = { Text("如：粤语、Nederlands、商务敬语日文") }
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
                Text("添加语言")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
                        text = "外观主题",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "切换宿主应用、内置语音键盘与悬浮语音胶囊的视觉配色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ThemePreviewOptionCard(
                        title = "浅色单色",
                        subtitle = "TypeFree 经典",
                        preference = ThemePreference.LIGHT,
                        selected = settings.themePreference == ThemePreference.LIGHT,
                        onClick = {
                            repository.updateSettings { it.copy(themePreference = ThemePreference.LIGHT) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ThemePreviewOptionCard(
                        title = "深色曜石",
                        subtitle = "沉浸暗色",
                        preference = ThemePreference.DARK,
                        selected = settings.themePreference == ThemePreference.DARK,
                        onClick = {
                            repository.updateSettings { it.copy(themePreference = ThemePreference.DARK) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ThemePreviewOptionCard(
                        title = "跟随系统",
                        subtitle = "日夜自适应",
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
                            text = "打字键盘与输入方案",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "切换面板高度稳定 · 支持顶部数字行与多种中文输入方案",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { showKeyboardGesturesDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("手势速查", style = MaterialTheme.typography.labelLarge)
                    }
                }

                PreferenceSwitchRow(
                    title = "记忆上一次使用的键盘模式",
                    subtitle = "收起键盘或切换应用后自动恢复上次面板；密码框临时切英文后自动还原",
                    checked = settings.rememberLastKeyboardMode,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(rememberLastKeyboardMode = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "顶部独立数字行 (1–0)",
                    subtitle = "默认关闭（Q–P 长按或上滑直输 1–0）；开启后显示顶部独立数字行",
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
                                text = "底部垫高 · ${KeyboardMetrics.labelForLiftLevel(currentLiftLevel)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "仅在键盘底部增加防误触留白托起按键区，所有面板同步生效",
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
                                Text("恢复默认", fontSize = 12.sp)
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
                            text = "输入方案 · ${settings.inputScheme.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "选择中文打字编码方案（键盘工具面板亦可一键循环切换）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val schemeOptions = remember {
                        listOf(
                            InputScheme.RIME_PINYIN to "全拼",
                            InputScheme.RIME_T9_PINYIN to "九键拼音",
                            InputScheme.RIME_DOUBLE_PINYIN_FLYPY to "小鹤双拼",
                            InputScheme.FLYPY_TABLE to "小鹤音形"
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
                    title = "AI 整句补全",
                    subtitle = "停顿后把光标前文字发送到你配置的模型生成续写建议（默认关闭，密码框与无痕模式永不触发）",
                    checked = settings.aiCompletionEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(aiCompletionEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "按键按下气泡放大预览",
                    subtitle = "按下字母或符号键时在键帽上方浮现放大预览气泡",
                    checked = settings.qwertyKeyPopupPreview,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(qwertyKeyPopupPreview = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "打字面板默认开启中文输入",
                    subtitle = "长按空格或点顶栏中/英按钮可随时切换",
                    checked = settings.flypyEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(flypyEnabled = enabled) }
                    }
                )

                AnimatedVisibility(visible = settings.inputScheme == InputScheme.FLYPY_TABLE) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                        PreferenceSwitchRow(
                            title = "候选状态下「, / .」快捷选第 2 / 第 3 候选",
                            subtitle = "有重码时底排「,」上屏次选、「.」上屏三选；关闭则用逗号/句号顶字上屏",
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
                title = { Text("打字键盘快捷手势速查") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val gestureTips = listOf(
                            "编码与上屏：四码唯一自动上屏；第五码顶字上屏；Shift 有码时清码",
                            "中英切换：长按空格键或点顶栏中/英按钮随时切换",
                            "通配反查：长按 Z 输入 ` 反查（如 ji` 知音查形、`bo 知形查音）",
                            "分号快符：长按 L 输入分号快符（;q ：“、;w ？、;f 重复上屏）",
                            "快捷日期时间：orq 输出日期，ouj 输出时间",
                            "编辑与撤回：长按 A/X/C/V 全选/剪切/复制/粘贴；退格键上滑撤回上屏"
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
                        Text("知道了")
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
                            text = "剪贴板与常用语",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "键盘顶栏可唤起双列剪贴板，可在剪贴板面板内直接置顶常用语",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (unpinnedCount > 0) {
                        TextButton(
                            onClick = {
                                repository.clearUnpinnedClipboardItems()
                                Toast.makeText(context, "已清空未置顶剪贴板记录", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("清空未置顶 ($unpinnedCount)")
                        }
                    }
                }

                PreferenceSwitchRow(
                    title = "自动记录剪贴板历史",
                    subtitle = "自动收纳最近 200 条复制文本，自动过滤密码管理器敏感内容",
                    checked = settings.clipboardEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(clipboardEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "顶栏刚复制内容一键粘贴提示",
                    subtitle = "复制短信验证码、链接或文本后在键盘顶栏浮现快捷胶囊，一键填入",
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
                    text = "语音收音与上屏",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                PreferenceSwitchRow(
                    title = "实时写入输入框",
                    subtitle = "边说边上屏，未确定的部分会自动修正；部分 App 不支持时自动回退",
                    checked = settings.liveWriteToEditor,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(liveWriteToEditor = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "🎵 录音时自动暂停外放媒体",
                    subtitle = "录音时自动暂停正在播放的音乐或视频，松手后恢复",
                    checked = settings.pauseMediaWhileRecording,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(pauseMediaWhileRecording = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "🤫 轻声高灵敏收音",
                    subtitle = "适合安静环境下贴近麦克风轻声说话，语音面板也可切换",
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
                    text = "AI 辅助与读屏隐私",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                PreferenceSwitchRow(
                    title = "🔍 顶栏「问AI」随手查胶囊",
                    subtitle = "在普通输入框打字满 2 个字后，键盘顶栏自动浮现「🔍 问AI」，点击在键盘内弹出解答卡片",
                    checked = settings.equalEqualAskAiEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(equalEqualAskAiEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "光标上文语境感知",
                    subtitle = "自动读取光标前最多 80 字上下文，使标点衔接、人称代词与续写更自然",
                    checked = settings.useSurroundingContext,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(useSurroundingContext = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                var showChatContextDisclosureDialog by remember { mutableStateOf(false) }

                PreferenceSwitchRow(
                    title = "✦ 启用 Jev 军师模式",
                    subtitle = "在键盘顶栏、语音面板与帮写顶栏启用潜台词分析与多策略回复草稿",
                    checked = settings.jevCopilotEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(jevCopilotEnabled = enabled) }
                    }
                )

                if (settings.jevCopilotEnabled) {
                    val activeJevSummary = if (settings.jevUseDedicatedModel) {
                        "独立模型 · ${settings.jevLlmConfig.model.substringAfterLast('/')}"
                    } else {
                        val fallbackModel = if (settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT) {
                            settings.multimodalConfig.model.substringAfterLast('/')
                        } else {
                            settings.llmConfig.model.substringAfterLast('/')
                        }
                        "跟随默认 · $fallbackModel"
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "当前军师推理引擎：$activeJevSummary",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(
                                onClick = onJumpToApiSubTab,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    text = "去指定模型 →",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "✦ Jev 军师读取聊天上文",
                    subtitle = "仅在你主动点击 ✦ 军师时按需读取当前窗口可见聊天记录，端侧脱敏后发给你配置的模型，不保存、不写入历史；密码框、无痕模式与支付类应用自动屏蔽",
                    checked = settings.chatContextReplyEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !settings.chatContextDisclosureAccepted) {
                            showChatContextDisclosureDialog = true
                        } else {
                            repository.updateSettings {
                                it.copy(chatContextReplyEnabled = enabled)
                            }
                        }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "🖼️ 读屏允许附带脱敏截图",
                    subtitle = "开启后可识别表情包与图片内容，端侧自动遮挡敏感信息；关闭则仅发送纯文本",
                    checked = settings.chatContextSendScreenshot,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(chatContextSendScreenshot = enabled) }
                    }
                )

                var showJevMemoryDialog by remember { mutableStateOf(false) }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "🧠 Jev 关系记忆与个人口癖自学习",
                    subtitle = "采用设备本地 HMAC-SHA256 匿名锚点，记忆不同对话的身份定位与你习惯的句末标点/称谓风格，不存明文姓名或聊天内容；密码框、无痕模式与支付类应用自动旁路",
                    checked = settings.jevMemoryEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(jevMemoryEnabled = enabled) }
                    }
                )

                AnimatedVisibility(visible = settings.jevMemoryEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreferenceSwitchRow(
                            title = "保存首尾字掩码提示（如 张*三）",
                            subtitle = "仅用于在管理列表中区分会话，关闭后只保留不可逆哈希",
                            checked = settings.jevMaskTitleHintEnabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(jevMaskTitleHintEnabled = enabled) }
                            }
                        )
                        OutlinedButton(
                            onClick = { showJevMemoryDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("管理已学风格规则与会话记忆")
                        }
                    }
                }

                if (showJevMemoryDialog) {
                    JevMemoryManagementDialog(
                        repository = repository,
                        onDismiss = { showJevMemoryDialog = false }
                    )
                }

                if (showChatContextDisclosureDialog) {
                    AlertDialog(
                        onDismissRequest = { showChatContextDisclosureDialog = false },
                        title = { Text("开启「✦ Jev 军师读取聊天上文」说明") },
                        text = {
                            Text(
                                "仅在你主动点击 ✦ 军师时按需读取当前窗口可见聊天记录，端侧脱敏后发给你配置的模型，不保存、不写入历史。\n\n密码框、无痕模式与支付类应用自动屏蔽，退出面板后内存立即清空。"
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showChatContextDisclosureDialog = false
                                    repository.updateSettings {
                                        it.copy(
                                            chatContextReplyEnabled = true,
                                            chatContextDisclosureAccepted = true,
                                            chatContextDisclosureDismissed = false
                                        )
                                    }
                                }
                            ) {
                                Text("同意并启用")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showChatContextDisclosureDialog = false }) {
                                Text("暂不开启")
                            }
                        }
                    )
                }
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
                    text = "声音触感与悬浮球",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                PreferenceSwitchRow(
                    title = "按键与录音提示音",
                    subtitle = "点击麦克风开始聆听、结束录音及按键反馈轻音效",
                    checked = settings.interactionSounds,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(interactionSounds = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "触觉振动反馈",
                    subtitle = "按下说话键、弧形转盘选词及文字上屏时提供振动反馈",
                    checked = settings.hapticFeedback,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(hapticFeedback = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "启用悬浮语音胶囊（免切键盘语音输入）",
                    subtitle = if (settings.bubbleDockLeft) {
                        "当前停靠在屏幕左侧边缘 · 可自由拖拽改变位置"
                    } else {
                        "当前停靠在屏幕右侧边缘 · 可自由拖拽改变位置"
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
                                Toast.makeText(context, "已重置悬浮胶囊停靠位置", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (settings.bubbleDockLeft) "切换至右侧停靠并居中" else "切换至左侧停靠并居中",
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
                            text = "v${BuildConfig.VERSION_NAME} · BYOK 自有接口版",
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
                                text = "自有 API 直连 · 数据仅存本机",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "• 自有接口直连：音频与文本由手机直接发送至你配置的 Gemini、DeepSeek、阿里云百炼、火山豆包、硅基流动或自定义网关接口，无第三方中转。\n• 本机存储：API Key、历史记录、个人词典、剪贴板与风格配置均保存在当前设备本地。\n• v3.5 主要功能：支持流式语音输入、雾凇/小鹤中文键盘、✦ Jev 军师、✍️ 帮写与选中改写、🔍 问AI、弧形快译与智能剪贴板。",
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
                            Text("正在检查…", style = MaterialTheme.typography.labelLarge)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("检查更新", style = MaterialTheme.typography.labelLarge)
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
                title = { Text("发现新版本 v${info.latestVersion}") },
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
                                            text = "防误覆盖提醒（私有词库定制包）",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "当前为私有小鹤全量词库定制包，GitHub 公开 Release APK 仅含公开词表，直接覆盖安装会替换内置私有词库。",
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
                        Text("浏览器下载 APK")
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
                            Text("打开发布页")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("暂不更新")
                        }
                    }
                }
            )
        }

        is UpdateDialogState.UpToDate -> {
            val info = state.info
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("已是最新版本") },
                text = {
                    Text(
                        text = "当前版本 v${info.currentVersion} 已是最新正式版（GitHub 最新版本：${info.latestTag}）。",
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("知道了")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            onOpenUrl(info.htmlUrl)
                            onDismiss()
                        }
                    ) {
                        Text("查看 Releases")
                    }
                }
            )
        }

        is UpdateDialogState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("检查更新失败") },
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
                        Text("手动查看 Releases")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            )
        }
    }
}

private fun openExternalUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "未能打开系统浏览器，请手动访问 GitHub Releases", Toast.LENGTH_SHORT).show()
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
