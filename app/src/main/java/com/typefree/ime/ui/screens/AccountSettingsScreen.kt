package com.typefree.ime.ui.screens

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.TypeFreeEmerald

@Composable
fun AccountSettingsScreen(
    repository: AppRepository,
    aiEngine: AiPipelineEngine,
    initialSubTab: Int = AppDestinations.SUBTAB_API,
    onSubTabChanged: ((Int) -> Unit)? = null
) {
    var activeSubTab by rememberSaveable {
        mutableIntStateOf(
            initialSubTab.coerceIn(
                AppDestinations.SUBTAB_API,
                AppDestinations.SUBTAB_PREFERENCES
            )
        )
    }

    LaunchedEffect(initialSubTab) {
        val coerced = initialSubTab.coerceIn(
            AppDestinations.SUBTAB_API,
            AppDestinations.SUBTAB_PREFERENCES
        )
        if (activeSubTab != coerced) {
            activeSubTab = coerced
        }
    }

    fun selectSubTab(index: Int) {
        val coerced = index.coerceIn(
            AppDestinations.SUBTAB_API,
            AppDestinations.SUBTAB_PREFERENCES
        )
        activeSubTab = coerced
        onSubTabChanged?.invoke(coerced)
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
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "Custom API v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
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
                        onJumpToApiSubTab = { selectSubTab(AppDestinations.SUBTAB_API) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioSubTabPill(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp
    ) {
        Box(
            modifier = Modifier.padding(vertical = 9.dp, horizontal = 6.dp),
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
    onJumpToApiSubTab: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings by repository.settings.collectAsState()
    val clipboardItems by repository.clipboardItems.collectAsState()
    var newSnippetText by rememberSaveable { mutableStateOf("") }

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
                        text = "外观主题 (Appearance)",
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

        // 2. Built-in Xiaohe Yinxing (小鹤音形) Keyboard Preferences Card
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
                        text = "打字键盘与小鹤音形 (Flypy v10.13)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "恒定帧高 零跳变视口 · 1.17:1 黄金键帽比例 · 顶部独立数字行 · 记忆键盘模式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                PreferenceSwitchRow(
                    title = "记忆上一次使用的键盘模式",
                    subtitle = "收起键盘、切换输入框或应用后自动恢复上次使用的面板（语音 / 小鹤 / EN / 快译）；密码框临时切英文后自动还原",
                    checked = settings.rememberLastKeyboardMode,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(rememberLastKeyboardMode = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "顶部独立数字行 (1–0)",
                    subtitle = "默认关闭（4 行 252dp 黄金紧凑高度，Q–P 长按或上滑直输 1–0）；开启后显示顶部独立数字行（294dp）",
                    checked = settings.qwertyShowNumberRow,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(qwertyShowNumberRow = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                // Unified Keyboard Bottom Cushion (底部垫高 0 / 8 / 16 / 24 / 32dp)
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
                                text = "按键保持 46dp 黄金高度不变，仅在底部增加防误触留白托起键盘；所有面板同步生效",
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
                            InputScheme.RIME_DOUBLE_PINYIN_FLYPY to "小鹤双拼",
                            InputScheme.FLYPY_TABLE to "小鹤音形"
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    subtitle = "按下字母或符号键时在键帽上方浮现清晰预览气泡（对齐 Gboard / iOS 触控反馈）",
                    checked = settings.qwertyKeyPopupPreview,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(qwertyKeyPopupPreview = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "启用小鹤音形中文模式（「鹤」）",
                    subtitle = "开启后打字面板支持中文小鹤音形；键盘中长按空格或点顶部「鹤/EN」随时切换中英并自动记忆",
                    checked = settings.flypyEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(flypyEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "候选状态下「, / .」快捷选第 2 / 第 3 候选",
                    subtitle = "对齐小鹤安卓版习惯：有重码时底排「,」上屏次选、「.」上屏三选；关闭则改为鼠须管逗号/句号顶字上屏",
                    checked = settings.flypyCommaPeriodQuickSelect,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(flypyCommaPeriodQuickSelect = enabled) }
                    }
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    Text(
                        text = "快捷手势速查：四码唯一自动上屏 · 第五码顶字上屏 · Shift 有码清码 · 长按空格切中英 · 长按 Z 输入 ` 通配反查（如 ji` 知音查形、`bo 知形查音）· 长按 L 输入分号快符（;q ：“、;w ？、;f 重复上屏）· orq 日期 / ouj 时间 · 长按 A/X/C/V 全选/剪切/复制/粘贴 · 退格键上划撤回上屏",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        // 3. Built-in IME Clipboard & Pinned Snippets Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
        ) {
            val pinnedItems = remember(clipboardItems) { clipboardItems.filter { it.isPinned } }
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
                            text = "剪贴板与常用语 (Clipboard)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "键盘顶栏一键唤起双列剪贴板，支持验证码/链接智能提取与常用语永久置顶",
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
                    subtitle = "自动收纳最近 40 条复制文本（自动过滤 Android 13+ 密码管理器敏感剪贴板）",
                    checked = settings.clipboardEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(clipboardEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "顶栏刚复制内容 / 验证码一键粘贴提示",
                    subtitle = "复制短信验证码、链接或文本后在键盘顶栏浮现 45 秒快捷胶囊，无需展开面板一键填入",
                    checked = settings.clipboardQuickPasteSuggestion,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(clipboardQuickPasteSuggestion = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                // Add Custom Pinned Phrase Inline Input
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "添加永久置顶常用短语（共 ${pinnedItems.size} 条置顶 / ${clipboardItems.size} 条记录）",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newSnippetText,
                            onValueChange = { newSnippetText = it },
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            shape = RoundedCornerShape(12.dp),
                            placeholder = { Text("输入收货地址、邮箱、微信号或常用回复…") }
                        )
                        FilledTonalButton(
                            enabled = newSnippetText.trim().isNotEmpty(),
                            onClick = {
                                val added = repository.recordClipboardText(newSnippetText, pinned = true)
                                if (added != null) {
                                    newSnippetText = ""
                                    Toast.makeText(context, "📌 已添加置顶常用语", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("置顶")
                        }
                    }

                    if (pinnedItems.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            pinnedItems.forEach { pinned ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "📌 " + pinned.text.replace("\n", " ").take(24) +
                                                if (pinned.text.length > 24) "…" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        IconButton(
                                            onClick = { repository.deleteClipboardItem(pinned.id) },
                                            modifier = Modifier.size(22.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "删除常用语",
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Sound, Haptics & Context Preferences Card
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
                    text = "输入与感知偏好",
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
                    subtitle = "按下麦克风瞬间自动向系统请求短暂独占焦点暂停音乐/视频外放（防止歌词串音识别），松手后立即恢复播放",
                    checked = settings.pauseMediaWhileRecording,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(pauseMediaWhileRecording = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "🤫 轻声耳语高灵敏收音模式",
                    subtitle = "适合深夜、图书馆、工位贴近麦克风轻声说话；自动下调 VAD 门限并启用防削波软限幅增益（语音面板右上角亦可一键切换）",
                    checked = settings.whisperModeEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(whisperModeEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "🤖 输入框「==问题」回车直接问 AI",
                    subtitle = "在任意输入框以 == 开头输入问题或指令（如 ==把下午3点换算成美西时间）后按回车/发送键，自动拦截并替换为 AI 答案",
                    checked = settings.equalEqualAskAiEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(equalEqualAskAiEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

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
                    subtitle = "按下说话键、弧形转盘选词及文字上屏时提供细腻清脆震动",
                    checked = settings.hapticFeedback,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(hapticFeedback = enabled) }
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
                    subtitle = "在键盘顶栏、语音面板与帮写顶栏启用潜台词雷达（Lv.1–9）与 3 张高筹码策略键帽草稿",
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
                    title = "💬 AI 帮回与军师读取屏幕上文",
                    subtitle = "仅在你主动点 ✍️ 或 ✦ 军师时按需读取当前窗口上文，端侧脱敏后发给你配置的 AI 模型；密码框、全屏含可见密码节点、无痕模式与金融支付类软件一票否决永不读取",
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
                    title = "🖼️ 读屏时允许附带脱敏视口截图",
                    subtitle = "开启后多模态模型可识别表情包与图片上下文（端侧自动 #18181B 涂黑联系信息，检测到验证码/密码/身份证/银行卡/密钥时自动弃图）；关闭则仅发送脱敏纯文本",
                    checked = settings.chatContextSendScreenshot,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(chatContextSendScreenshot = enabled) }
                    }
                )

                if (showChatContextDisclosureDialog) {
                    AlertDialog(
                        onDismissRequest = { showChatContextDisclosureDialog = false },
                        title = { Text("开启「💬 AI 帮回读取屏幕上文」说明") },
                        text = {
                            Text(
                                "仅在你点 ✍️ 时读取当前聊天最近几条消息，发送给你配置的 AI 模型生成回复；不保存、不写入历史或词库。\n\n无痕模式、密码输入框与金融支付/认证类软件永不读取，退出面板后内存立即清空。"
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                PreferenceSwitchRow(
                    title = "自动学习打字改错",
                    subtitle = "口述上屏后若用内置键盘手动修正专有名词，自动收录至个人词典",
                    checked = settings.autoLearnDictionary,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(autoLearnDictionary = enabled) }
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

        // 3. OpenTypeFree Custom-API Architecture & Privacy About Card
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
                            text = "OpenTypeFree Custom-API",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME} · 开源自定义接口致敬版",
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
                                text = "零中转直连 · 本机隐私优先架构",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "• 自有 API 直连：音频与文本由手机直接加密发送至你配置的硅基流动、DeepSeek、Groq、Gemini、OpenAI 或局域网 Ollama 接口，无任何第三方中转服务器。\n• 本机零云端留存：API Key、历史记录、个人词典与场景风格 100% 存储于当前设备沙盒。\n• 全场景生产力闭环：完整支持口述净写、左下角语音笔 Help me write、选中文字 Speak to edit、长按弧形快译、免切键盘悬浮胶囊与改错自学习词典。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 19.sp
                        )
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
                        text = "Built with Jetpack Compose · 16kHz PCM/WAV Low-Latency Pipeline",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
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

@Composable
private fun PreferenceSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
