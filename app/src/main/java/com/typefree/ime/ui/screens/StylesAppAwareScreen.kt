package com.typefree.ime.ui.screens

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppToneRule
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.ui.components.TypeFreeIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledAppOption(
    val appName: String,
    val packageName: String
)

private val PRESET_EMOJIS = listOf(
    "✨", "💬", "💼", "📝", "💻", "🌐", "🎯", "🔥", "📚", "🎙️", "🤖", "💡"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StylesAppAwareScreen(
    repository: AppRepository,
    showScreenHeader: Boolean = true,
    headerSlot: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val settings by repository.settings.collectAsState()
    val styles = settings.styles.ifEmpty { DefaultPresets.defaultStyles() }
    val appRules = settings.appRules

    var selectedSubTab by rememberSaveable { mutableIntStateOf(0) }
    var ruleSearchQuery by rememberSaveable { mutableStateOf("") }

    // Dialog states
    var showStyleEditor by remember { mutableStateOf(false) }
    var editingStyle by remember { mutableStateOf<ToneStyle?>(null) }
    var stylePendingDelete by remember { mutableStateOf<ToneStyle?>(null) }
    var showRestoreStylesConfirm by remember { mutableStateOf(false) }
    var showRestoreRulesConfirm by remember { mutableStateOf(false) }
    var showAddAppRuleDialog by remember { mutableStateOf(false) }

    val filteredRules = remember(appRules, ruleSearchQuery) {
        val q = ruleSearchQuery.trim()
        if (q.isEmpty()) {
            appRules
        } else {
            appRules.filter {
                it.appName.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true)
            }
        }
    }

    val lockedStyle = styles.firstOrNull { it.id == settings.selectedManualStyleId }
    val enabledRuleCount = appRules.count { it.enabled }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (headerSlot != null) {
            item(key = "styles_top_header_slot") {
                headerSlot()
            }
        }

        // Screen Header
        if (showScreenHeader) {
            item(key = "styles_screen_header") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.Sparkles,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "场景风格与应用感知",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "自动识别宿主应用切换得体语气，亦可自由定制专属 System Prompt",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Top App-Aware Switch + Default Mode Selector Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
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
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "开启应用感知自动切换风格 (App-Aware Context)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "在微信、飞书、邮箱、终端等不同宿主应用唤起键盘时自动适配语气",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.appAwareToneEnabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(appAwareToneEnabled = enabled) }
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "默认模式选择",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                FilterChip(
                                    selected = settings.selectedManualStyleId == "auto",
                                    onClick = {
                                        repository.updateSettings {
                                            it.copy(selectedManualStyleId = "auto")
                                        }
                                    },
                                    label = { Text("🧭 自动感知宿主应用") },
                                    leadingIcon = if (settings.selectedManualStyleId == "auto") {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null
                                )
                            }

                            items(styles, key = { "lock_${it.id}" }) { style ->
                                val isLocked = settings.selectedManualStyleId == style.id
                                FilterChip(
                                    selected = isLocked,
                                    onClick = {
                                        repository.updateSettings {
                                            it.copy(selectedManualStyleId = style.id)
                                        }
                                    },
                                    label = { Text("${style.emoji} 锁定：${style.name}") },
                                    leadingIcon = if (isLocked) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null
                                )
                            }
                        }

                        val statusSummary = when {
                            lockedStyle != null ->
                                "当前已全局锁定「${lockedStyle.emoji} ${lockedStyle.name}」，所有应用都将固定使用该风格（切回「自动感知宿主应用」可恢复自动匹配）。"
                            settings.appAwareToneEnabled ->
                                "当前处于自动感知模式：优先匹配下方 $enabledRuleCount 条启用中的应用规则，未命中时智能回退至通用灵动风格。"
                            else ->
                                "应用感知开关已关闭：所有应用统一使用默认「✨ 通用灵动」风格。"
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        ) {
                            Text(
                                text = statusSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // Sub-tab Segmented Switcher
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SegmentedTabButton(
                        title = "语气风格库 (${styles.size})",
                        selected = selectedSubTab == 0,
                        onClick = { selectedSubTab = 0 },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentedTabButton(
                        title = "应用自动匹配规则 (${appRules.size})",
                        selected = selectedSubTab == 1,
                        onClick = { selectedSubTab = 1 },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (selectedSubTab == 0) {
            // Action bar for ToneStyle library
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            editingStyle = null
                            showStyleEditor = true
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("新建风格", maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = { showRestoreStylesConfirm = true },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("恢复默认风格", maxLines = 1)
                    }
                }
            }

            items(styles, key = { it.id }) { style ->
                ToneStyleCard(
                    style = style,
                    isLocked = settings.selectedManualStyleId == style.id,
                    boundAppCount = appRules.count { it.enabled && it.styleId == style.id },
                    onEdit = {
                        editingStyle = style
                        showStyleEditor = true
                    },
                    onToggleLock = {
                        val nextId = if (settings.selectedManualStyleId == style.id) "auto" else style.id
                        repository.updateSettings { it.copy(selectedManualStyleId = nextId) }
                    },
                    onDelete = if (!style.isBuiltIn) {
                        { stylePendingDelete = style }
                    } else null
                )
            }
        } else {
            // Action bar + Search for AppToneRules
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { showAddAppRuleDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("添加应用规则")
                        }

                        OutlinedButton(
                            onClick = { showRestoreRulesConfirm = true },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("恢复默认规则")
                        }
                    }

                    OutlinedTextField(
                        value = ruleSearchQuery,
                        onValueChange = { ruleSearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        placeholder = { Text("搜索应用名称或包名（如 微信 / com.tencent.mm）") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null
                            )
                        },
                        trailingIcon = if (ruleSearchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { ruleSearchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "清除搜索"
                                    )
                                }
                            }
                        } else null
                    )
                }
            }

            if (filteredRules.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (appRules.isEmpty()) "尚未配置应用自动匹配规则" else "未找到匹配「$ruleSearchQuery」的应用规则",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "点击上方「+ 添加应用规则」可一键扫描本机已安装应用并绑定专属语气",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(filteredRules, key = { it.id }) { rule ->
                    AppToneRuleCard(
                        rule = rule,
                        styles = styles,
                        onToggleEnabled = { enabled ->
                            repository.updateSettings { current ->
                                current.copy(
                                    appRules = current.appRules.map {
                                        if (it.id == rule.id) it.copy(enabled = enabled) else it
                                    }
                                )
                            }
                        },
                        onChangeStyle = { newStyleId ->
                            repository.updateSettings { current ->
                                current.copy(
                                    appRules = current.appRules.map {
                                        if (it.id == rule.id) it.copy(styleId = newStyleId) else it
                                    }
                                )
                            }
                        },
                        onDelete = {
                            repository.updateSettings { current ->
                                current.copy(
                                    appRules = current.appRules.filterNot { it.id == rule.id }
                                )
                            }
                            Toast.makeText(context, "已移除「${rule.appName}」规则", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Add / Edit ToneStyle Dialog
    if (showStyleEditor) {
        ToneStyleEditorDialog(
            initialStyle = editingStyle,
            onDismiss = { showStyleEditor = false },
            onConfirm = { savedStyle ->
                repository.updateSettings { current ->
                    val existingStyles = current.styles.ifEmpty { DefaultPresets.defaultStyles() }
                    val updatedList = if (existingStyles.any { it.id == savedStyle.id }) {
                        existingStyles.map { if (it.id == savedStyle.id) savedStyle else it }
                    } else {
                        existingStyles + savedStyle
                    }
                    current.copy(styles = updatedList)
                }
                showStyleEditor = false
                Toast.makeText(
                    context,
                    if (editingStyle == null) "已创建风格「${savedStyle.name}」" else "已更新风格「${savedStyle.name}」",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    // Delete Custom ToneStyle Confirmation
    stylePendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { stylePendingDelete = null },
            title = { Text("删除自定义风格") },
            text = {
                Text("确定要删除自定义风格「${target.emoji} ${target.name}」吗？关联到该风格的应用规则将自动回退为「通用灵动」。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.updateSettings { current ->
                            val remaining = current.styles.filterNot { it.id == target.id }
                                .ifEmpty { DefaultPresets.defaultStyles() }
                            val fallbackId = remaining.firstOrNull { it.id == "smart_default" }?.id
                                ?: remaining.first().id
                            current.copy(
                                styles = remaining,
                                selectedManualStyleId = if (current.selectedManualStyleId == target.id) {
                                    "auto"
                                } else {
                                    current.selectedManualStyleId
                                },
                                appRules = current.appRules.map { rule ->
                                    if (rule.styleId == target.id) rule.copy(styleId = fallbackId) else rule
                                }
                            )
                        }
                        stylePendingDelete = null
                        Toast.makeText(context, "已删除风格「${target.name}」", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { stylePendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Restore Default Built-in Styles Confirmation
    if (showRestoreStylesConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreStylesConfirm = false },
            title = { Text("恢复默认内置风格") },
            text = {
                Text("这会将 6 种内置预设风格的名称、简介与 System Prompt 恢复为出厂默认配置；您自己新建的自定义风格将予以保留。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.updateSettings { current ->
                            val defaults = DefaultPresets.defaultStyles()
                            val defaultIds = defaults.map { it.id }.toSet()
                            val customStyles = current.styles.filter { !it.isBuiltIn && it.id !in defaultIds }
                            current.copy(styles = defaults + customStyles)
                        }
                        showRestoreStylesConfirm = false
                        Toast.makeText(context, "已恢复默认内置风格指令", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("立即恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreStylesConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Restore Default App Rules Confirmation
    if (showRestoreRulesConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreRulesConfirm = false },
            title = { Text("恢复默认应用匹配规则") },
            text = {
                Text("将补全并恢复微信、飞书、钉钉、Gmail、Notion、ChatGPT 等默认宿主应用绑定规则（已添加的其他自定义包名规则仍会保留）。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.updateSettings { current ->
                            val defaults = DefaultPresets.defaultAppRules()
                            val defaultPackages = defaults.map { it.packageName.lowercase() }.toSet()
                            val customRules = current.appRules.filter {
                                it.packageName.lowercase() !in defaultPackages
                            }
                            current.copy(appRules = customRules + defaults)
                        }
                        showRestoreRulesConfirm = false
                        Toast.makeText(context, "已恢复默认应用规则", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("确认恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreRulesConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Add App Rule Dialog (Launchable Installed Apps + Manual Entry)
    if (showAddAppRuleDialog) {
        AddAppRuleDialog(
            styles = styles,
            existingRules = appRules,
            onDismiss = { showAddAppRuleDialog = false },
            onConfirmRule = { packageName, appName, styleId ->
                val cleanPkg = packageName.trim()
                val cleanName = appName.trim().ifBlank { cleanPkg }
                repository.updateSettings { current ->
                    val existingIdx = current.appRules.indexOfFirst {
                        it.packageName.equals(cleanPkg, ignoreCase = true)
                    }
                    val nextRules = if (existingIdx >= 0) {
                        current.appRules.mapIndexed { index, rule ->
                            if (index == existingIdx) {
                                rule.copy(
                                    packageName = cleanPkg,
                                    appName = cleanName,
                                    styleId = styleId,
                                    enabled = true
                                )
                            } else {
                                rule
                            }
                        }
                    } else {
                        listOf(
                            AppToneRule(
                                packageName = cleanPkg,
                                appName = cleanName,
                                styleId = styleId,
                                enabled = true
                            )
                        ) + current.appRules
                    }
                    current.copy(appRules = nextRules)
                }
                showAddAppRuleDialog = false
                Toast.makeText(context, "已绑定「$cleanName」语气规则", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun SegmentedTabButton(
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
        color = if (selected) {
            MaterialTheme.colorScheme.surface
        } else {
            Color.Transparent
        },
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
private fun ToneStyleCard(
    style: ToneStyle,
    isLocked: Boolean,
    boundAppCount: Int,
    onEdit: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: (() -> Unit)?
) {
    var promptExpanded by rememberSaveable(style.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (isLocked) 1.5.dp else 1.dp,
            color = if (isLocked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = style.emoji, fontSize = 20.sp)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = style.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            BadgePill(
                                text = if (style.isBuiltIn) "内置" else "自定义",
                                containerColor = if (style.isBuiltIn) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                contentColor = if (style.isBuiltIn) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                            if (isLocked) {
                                BadgePill(
                                    text = "当前锁定",
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        Text(
                            text = style.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除自定义风格",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Prompt instruction preview
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { promptExpanded = !promptExpanded },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "System Prompt 润色指令",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (boundAppCount > 0) "已关联 $boundAppCount 个应用 · 点击展开" else "点击展开/收起",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = style.promptInstruction,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                        maxLines = if (promptExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onToggleLock) {
                    Text(if (isLocked) "解除全局锁定" else "设为全局固定")
                }
                FilledTonalButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("编辑 Prompt")
                }
            }
        }
    }
}

@Composable
private fun AppToneRuleCard(
    rule: AppToneRule,
    styles: List<ToneStyle>,
    onToggleEnabled: (Boolean) -> Unit,
    onChangeStyle: (String) -> Unit,
    onDelete: () -> Unit
) {
    var styleDropdownExpanded by remember { mutableStateOf(false) }
    val boundStyle = styles.firstOrNull { it.id == rule.styleId }
        ?: styles.firstOrNull { it.id == "smart_default" }
        ?: styles.first()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            }
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = rule.appName.trim().take(1).uppercase().ifBlank { "A" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rule.appName.ifBlank { rule.packageName },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = rule.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = onToggleEnabled
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除规则",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Style Mapping Row with Quick Dropdown Reassignment
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "自动匹配风格 ->",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Box {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { styleDropdownExpanded = true },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${boundStyle.emoji} ${boundStyle.name}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "切换绑定风格",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = styleDropdownExpanded,
                        onDismissRequest = { styleDropdownExpanded = false }
                    ) {
                        styles.forEach { candidate ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = "${candidate.emoji} ${candidate.name}",
                                            fontWeight = if (candidate.id == boundStyle.id) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            }
                                        )
                                        Text(
                                            text = candidate.summary,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                trailingIcon = if (candidate.id == boundStyle.id) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else null,
                                onClick = {
                                    styleDropdownExpanded = false
                                    onChangeStyle(candidate.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToneStyleEditorDialog(
    initialStyle: ToneStyle?,
    onDismiss: () -> Unit,
    onConfirm: (ToneStyle) -> Unit
) {
    val isEditing = initialStyle != null
    val factoryBuiltin = remember(initialStyle?.id) {
        DefaultPresets.defaultStyles().firstOrNull { it.id == initialStyle?.id }
    }

    var emoji by rememberSaveable(initialStyle?.id) { mutableStateOf(initialStyle?.emoji ?: "✨") }
    var name by rememberSaveable(initialStyle?.id) { mutableStateOf(initialStyle?.name ?: "") }
    var summary by rememberSaveable(initialStyle?.id) { mutableStateOf(initialStyle?.summary ?: "") }
    var promptInstruction by rememberSaveable(initialStyle?.id) { mutableStateOf(initialStyle?.promptInstruction ?: "") }

    val canSave = name.trim().isNotEmpty() && promptInstruction.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when {
                    initialStyle == null -> "新建自定义语气风格"
                    initialStyle.isBuiltIn -> "编辑内置风格：${initialStyle.name}"
                    else -> "编辑自定义风格"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "选择图标 Emoji",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PRESET_EMOJIS.forEach { candidateEmoji ->
                        val selected = emoji == candidateEmoji
                        Surface(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .clickable { emoji = candidateEmoji },
                            shape = RoundedCornerShape(9.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            border = if (selected) {
                                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else null
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = candidateEmoji, fontSize = 17.sp)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = emoji,
                        onValueChange = { if (it.length <= 4) emoji = it },
                        label = { Text("Emoji") },
                        singleLine = true,
                        modifier = Modifier.width(86.dp)
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("风格名称 *") },
                        placeholder = { Text("如：小红书种草 / 学术严谨") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("适用场景一句话简介") },
                    placeholder = { Text("如：适合小红书与朋友圈，分段清爽、富有感染力") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = promptInstruction,
                    onValueChange = { promptInstruction = it },
                    label = { Text("系统润色指令 (System Prompt) *") },
                    placeholder = {
                        Text("描述大模型应当如何处理口语转写稿的语气、标点、分段与用词...")
                    },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                if (factoryBuiltin != null && promptInstruction != factoryBuiltin.promptInstruction) {
                    TextButton(
                        onClick = {
                            name = factoryBuiltin.name
                            emoji = factoryBuiltin.emoji
                            summary = factoryBuiltin.summary
                            promptInstruction = factoryBuiltin.promptInstruction
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("恢复此内置风格出厂 Prompt")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    val result = if (initialStyle != null) {
                        initialStyle.copy(
                            emoji = emoji.trim().ifBlank { "✨" },
                            name = name.trim(),
                            summary = summary.trim().ifBlank { "自定义场景润色风格" },
                            promptInstruction = promptInstruction.trim()
                        )
                    } else {
                        ToneStyle(
                            emoji = emoji.trim().ifBlank { "✨" },
                            name = name.trim(),
                            summary = summary.trim().ifBlank { "自定义场景润色风格" },
                            promptInstruction = promptInstruction.trim(),
                            isBuiltIn = false
                        )
                    }
                    onConfirm(result)
                }
            ) {
                Text("保存风格")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddAppRuleDialog(
    styles: List<ToneStyle>,
    existingRules: List<AppToneRule>,
    onDismiss: () -> Unit,
    onConfirmRule: (packageName: String, appName: String, styleId: String) -> Unit
) {
    val context = LocalContext.current

    var inputMode by rememberSaveable { mutableIntStateOf(0) } // 0 = 从已安装应用选择, 1 = 手动输入
    var appSearchQuery by rememberSaveable { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<InstalledAppOption>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }

    var packageName by rememberSaveable { mutableStateOf("") }
    var appName by rememberSaveable { mutableStateOf("") }
    var selectedStyleId by rememberSaveable {
        mutableStateOf(
            styles.firstOrNull { it.id == "casual_chat" }?.id ?: styles.first().id
        )
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                val resolveList = pm.queryIntentActivities(launcherIntent, 0)
                resolveList
                    .mapNotNull { info ->
                        val pkg = info.activityInfo?.packageName?.trim().orEmpty()
                        if (pkg.isEmpty() || pkg == context.packageName) {
                            null
                        } else {
                            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                            InstalledAppOption(appName = label, packageName = pkg)
                        }
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.appName.lowercase() }
            } catch (_: Exception) {
                emptyList()
            }
        }
        installedApps = loaded
        isLoadingApps = false
    }

    val filteredInstalledApps = remember(installedApps, appSearchQuery) {
        val q = appSearchQuery.trim()
        if (q.isEmpty()) {
            installedApps
        } else {
            installedApps.filter {
                it.appName.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true)
            }
        }
    }

    val existingPackages = remember(existingRules) {
        existingRules.associateBy { it.packageName.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加应用自动匹配规则") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Mode Toggle: Installed Apps vs Manual Package Input
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SegmentedTabButton(
                            title = "📱 本机已装应用",
                            selected = inputMode == 0,
                            onClick = { inputMode = 0 },
                            modifier = Modifier.weight(1f)
                        )
                        SegmentedTabButton(
                            title = "⌨️ 手动输入包名",
                            selected = inputMode == 1,
                            onClick = { inputMode = 1 },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (inputMode == 0) {
                    OutlinedTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        placeholder = { Text("搜索本机应用名或包名...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = if (appSearchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { appSearchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "清除",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else null
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    ) {
                        when {
                            isLoadingApps -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Text("正在扫描已安装应用...", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            filteredInstalledApps.isEmpty() -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (installedApps.isEmpty()) {
                                            "未读取到启动器应用列表，可切换至「手动输入包名」直接添加"
                                        } else {
                                            "没有找到匹配「$appSearchQuery」的已安装应用"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            else -> {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(filteredInstalledApps, key = { it.packageName }) { app ->
                                        val isPicked = packageName.equals(app.packageName, ignoreCase = true)
                                        val alreadyConfigured = existingPackages[app.packageName.lowercase()]
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(9.dp))
                                                .clickable {
                                                    packageName = app.packageName
                                                    appName = app.appName
                                                    if (alreadyConfigured != null) {
                                                        selectedStyleId = alreadyConfigured.styleId
                                                    }
                                                },
                                            shape = RoundedCornerShape(9.dp),
                                            color = if (isPicked) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(
                                                            text = app.appName,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (alreadyConfigured != null) {
                                                            BadgePill(
                                                                text = "已配置",
                                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                                            )
                                                        }
                                                    }
                                                    Text(
                                                        text = app.packageName,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }

                                                if (isPicked) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "已选中",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (packageName.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = "已选应用：${appName.ifBlank { packageName }} ($packageName)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = appName,
                        onValueChange = { appName = it },
                        label = { Text("应用显示名称") },
                        placeholder = { Text("如：微信 / 飞书 / Obsidian") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text("应用包名 (Package Name) *") },
                        placeholder = { Text("如：com.tencent.mm") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text = "绑定到此语气风格",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 125.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        styles.forEach { style ->
                            FilterChip(
                                selected = selectedStyleId == style.id,
                                onClick = { selectedStyleId = style.id },
                                label = { Text("${style.emoji} ${style.name}") }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = packageName.trim().isNotEmpty(),
                onClick = {
                    onConfirmRule(
                        packageName.trim(),
                        appName.trim().ifBlank { packageName.trim() },
                        selectedStyleId
                    )
                }
            ) {
                Text("绑定规则")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun BadgePill(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
