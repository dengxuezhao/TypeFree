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
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
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
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
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

private val FACTORY_STYLES_BY_ID: Map<String, ToneStyle> by lazy {
    DefaultPresets.defaultStyles().associateBy { it.id }
}

private fun localizedStyleName(style: ToneStyle, lang: AppLang): String {
    if (!style.isBuiltIn) return style.name
    val factory = FACTORY_STYLES_BY_ID[style.id]
    if (factory != null && style.name != factory.name) return style.name
    return AppStrings.toneStyleName(style, lang)
}

private fun localizedStyleSummary(style: ToneStyle, lang: AppLang): String {
    if (!style.isBuiltIn) {
        return if (style.summary == "自定义场景润色风格") {
            AppStrings.pick(lang, "自定义场景润色风格", "Custom tone polishing style", "自訂場景潤飾風格")
        } else {
            style.summary
        }
    }
    val factory = FACTORY_STYLES_BY_ID[style.id]
    if (factory != null && style.summary != factory.summary) return style.summary
    return AppStrings.toneStyleSummary(style, lang)
}

private fun localizedRuleAppName(rule: AppToneRule, lang: AppLang): String {
    return when (rule.appName.trim()) {
        "微信" -> AppStrings.pick(lang, "微信", "WeChat", "微信")
        "飞书" -> AppStrings.pick(lang, "飞书", "Feishu / Lark", "飛書")
        "钉钉" -> AppStrings.pick(lang, "钉钉", "DingTalk", "釘釘")
        "企业微信" -> AppStrings.pick(lang, "企业微信", "WeCom", "企業微信")
        "小红书" -> AppStrings.pick(lang, "小红书", "Xiaohongshu", "小紅書")
        "系统短信" -> AppStrings.pick(lang, "系统短信", "Messages (SMS)", "系統簡訊")
        "网易邮箱大师" -> AppStrings.pick(lang, "网易邮箱大师", "NetEase Mail", "網易郵箱大師")
        "QQ邮箱" -> AppStrings.pick(lang, "QQ邮箱", "QQ Mail", "QQ信箱")
        else -> rule.appName
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StylesAppAwareScreen(
    repository: AppRepository,
    showScreenHeader: Boolean = true,
    fixedSubTab: Int? = null,
    onBack: (() -> Unit)? = null,
    headerSlot: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val settings by repository.settings.collectAsState()
    val styles = settings.styles.ifEmpty { DefaultPresets.defaultStyles() }
    val appRules = settings.appRules

    var selectedSubTab by rememberSaveable(fixedSubTab) { mutableIntStateOf(fixedSubTab ?: 0) }
    val effectiveSubTab = fixedSubTab ?: selectedSubTab

    if (onBack != null) {
        BackHandler(onBack = onBack)
    }
    var ruleSearchQuery by rememberSaveable { mutableStateOf("") }

    // Dialog states
    var showStyleEditor by remember { mutableStateOf(false) }
    var editingStyle by remember { mutableStateOf<ToneStyle?>(null) }
    var stylePendingDelete by remember { mutableStateOf<ToneStyle?>(null) }
    var showRestoreStylesConfirm by remember { mutableStateOf(false) }
    var showRestoreRulesConfirm by remember { mutableStateOf(false) }
    var showAddAppRuleDialog by remember { mutableStateOf(false) }

    val filteredRules = remember(appRules, ruleSearchQuery, appLang) {
        val q = ruleSearchQuery.trim()
        if (q.isEmpty()) {
            appRules
        } else {
            appRules.filter {
                it.appName.contains(q, ignoreCase = true) ||
                    localizedRuleAppName(it, appLang).contains(q, ignoreCase = true) ||
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
        if (onBack != null) {
            item(key = "styles_subpage_back_bar") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = onBack,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = tr("返回", "Back", "返回"),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(tr("返回 AI 助手", "Back", "返回 AI 助手"), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Text(
                        text = if (effectiveSubTab == 0) {
                            tr("语气风格库 (${styles.size})", "Tone Styles (${styles.size})", "語氣風格庫 (${styles.size})")
                        } else {
                            tr("应用自动匹配规则 (${appRules.size})", "App Rules (${appRules.size})", "應用匹配規則 (${appRules.size})")
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(56.dp))
                }
            }
        }

        if (headerSlot != null) {
            item(key = "styles_top_header_slot") {
                headerSlot()
            }
        }

        // Screen Header
        if (showScreenHeader && fixedSubTab == null) {
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
                            text = tr("场景风格与应用感知", "Tone Styles & App-Awareness", "場景風格與應用感知"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = tr(
                            "自动识别宿主应用切换得体语气，亦可自由定制专属 System Prompt",
                            "Automatically switch tone by active app, or customize your own System Prompts",
                            "自動識別宿主應用程式切換得體語氣，亦可自由客製專屬 System Prompt"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Top Default Mode Selector Card (hidden when opened as dedicated sub-screen)
        if (fixedSubTab == null) {
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = tr("默认模式选择", "Default Mode", "預設模式選擇"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            val isAutoMatched = settings.selectedManualStyleId == "auto" && settings.appAwareToneEnabled
                            FilterChip(
                                selected = isAutoMatched,
                                onClick = {
                                    repository.updateSettings {
                                        it.copy(
                                            selectedManualStyleId = "auto",
                                            appAwareToneEnabled = true
                                        )
                                    }
                                },
                                label = { Text(tr("🧭 自动匹配宿主应用", "🧭 Auto-Match Active App", "🧭 自動匹配宿主應用")) },
                                leadingIcon = if (isAutoMatched) {
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
                            val dispName = localizedStyleName(style, appLang)
                            FilterChip(
                                selected = isLocked,
                                onClick = {
                                    repository.updateSettings {
                                        it.copy(selectedManualStyleId = style.id)
                                    }
                                },
                                label = {
                                    Text(
                                        tr(
                                            "${style.emoji} 锁定：$dispName",
                                            "${style.emoji} Lock: $dispName",
                                            "${style.emoji} 鎖定：$dispName"
                                        )
                                    )
                                },
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
                        lockedStyle != null -> {
                            val lockedName = localizedStyleName(lockedStyle, appLang)
                            tr(
                                "已全局锁定「${lockedStyle.emoji} $lockedName」，所有应用固定使用该风格",
                                "Globally locked to \"${lockedStyle.emoji} $lockedName\" across all apps",
                                "已全域鎖定「${lockedStyle.emoji} $lockedName」，所有應用程式固定使用該風格"
                            )
                        }
                        settings.appAwareToneEnabled ->
                            tr(
                                "自动匹配宿主应用（已启用 $enabledRuleCount 条规则，未命中时使用通用风格）",
                                "Auto-matching active app ($enabledRuleCount rules enabled; falls back to Smart Default)",
                                "自動匹配宿主應用（已啟用 $enabledRuleCount 條規則，未命中時使用通用風格）"
                            )
                        else ->
                            tr(
                                "当前统一使用「✨ 通用灵动」风格，点选「自动匹配宿主应用」可恢复自动切换",
                                "Using \"✨ Smart Default\" everywhere. Select \"Auto-Match Active App\" to resume auto-switching.",
                                "目前統一使用「✨ 通用靈動」風格，點選「自動匹配宿主應用」可恢復自動切換"
                            )
                    }

                    Text(
                        text = statusSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        }

        // Sub-tab Segmented Switcher (hidden when opened as dedicated sub-screen)
        if (fixedSubTab == null) {
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
                            title = tr("语气风格库 (${styles.size})", "Tone Styles (${styles.size})", "語氣風格庫 (${styles.size})"),
                            selected = selectedSubTab == 0,
                            onClick = { selectedSubTab = 0 },
                            modifier = Modifier.weight(1f)
                        )
                        SegmentedTabButton(
                            title = tr("应用自动匹配规则 (${appRules.size})", "App Rules (${appRules.size})", "應用匹配規則 (${appRules.size})"),
                            selected = selectedSubTab == 1,
                            onClick = { selectedSubTab = 1 },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (effectiveSubTab == 0) {
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
                        Text(tr("新建风格", "New Style", "新增風格"), maxLines = 1)
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
                        Text(tr("恢复默认风格", "Reset Defaults", "恢復預設風格"), maxLines = 1)
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
                        repository.updateSettings {
                            it.copy(
                                selectedManualStyleId = nextId,
                                appAwareToneEnabled = if (nextId == "auto") true else it.appAwareToneEnabled
                            )
                        }
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
                            Text(tr("添加应用规则", "Add Rule", "新增應用規則"), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                            Text(tr("恢复默认规则", "Reset Defaults", "恢復預設規則"), maxLines = 1)
                        }
                    }

                    OutlinedTextField(
                        value = ruleSearchQuery,
                        onValueChange = { ruleSearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        placeholder = {
                            Text(
                                tr(
                                    "搜索应用名称或包名（如 微信 / com.tencent.mm）",
                                    "Search app name or package (e.g. Slack / com.Slack)",
                                    "搜尋應用名稱或套件名（如 LINE / jp.naver.line.android）"
                                )
                            )
                        },
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
                                        contentDescription = tr("清除搜索", "Clear search", "清除搜尋")
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
                                text = if (appRules.isEmpty()) {
                                    tr("尚未配置应用自动匹配规则", "No App Matching Rules Configured", "尚未設定應用程式自動匹配規則")
                                } else {
                                    tr("未找到匹配「$ruleSearchQuery」的应用规则", "No app rules matching \"$ruleSearchQuery\"", "未找到符合「$ruleSearchQuery」的應用規則")
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = tr(
                                    "点击上方「+ 添加应用规则」可一键扫描本机已安装应用并绑定专属语气",
                                    "Tap \"+ Add Rule\" above to scan installed apps and bind a dedicated tone style.",
                                    "點選上方「+ 新增應用規則」可一鍵掃描本機已安裝應用並綁定專屬語氣"
                                ),
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
                            val dispName = localizedRuleAppName(rule, appLang).ifBlank { rule.packageName }
                            repository.updateSettings { current ->
                                current.copy(
                                    appRules = current.appRules.filterNot { it.id == rule.id }
                                )
                            }
                            Toast.makeText(
                                context,
                                AppStrings.pick(appLang, "已移除「$dispName」规则", "Removed rule for \"$dispName\"", "已移除「$dispName」規則"),
                                Toast.LENGTH_SHORT
                            ).show()
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
                val dispSaved = localizedStyleName(savedStyle, appLang)
                Toast.makeText(
                    context,
                    if (editingStyle == null) {
                        AppStrings.pick(appLang, "已创建风格「$dispSaved」", "Created style \"$dispSaved\"", "已建立風格「$dispSaved」")
                    } else {
                        AppStrings.pick(appLang, "已更新风格「$dispSaved」", "Updated style \"$dispSaved\"", "已更新風格「$dispSaved」")
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    // Delete Custom ToneStyle Confirmation
    stylePendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { stylePendingDelete = null },
            title = { Text(tr("删除自定义风格", "Delete Custom Style", "刪除自訂風格")) },
            text = {
                Text(
                    tr(
                        "确定要删除自定义风格「${target.emoji} ${target.name}」吗？关联到该风格的应用规则将自动回退为「通用灵动」。",
                        "Delete custom style \"${target.emoji} ${target.name}\"? App rules bound to it will fall back to \"Smart Default\".",
                        "確定要刪除自訂風格「${target.emoji} ${target.name}」嗎？關聯到該風格的應用規則將自動回退為「通用靈動」。"
                    )
                )
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
                        Toast.makeText(
                            context,
                            AppStrings.pick(appLang, "已删除风格「${target.name}」", "Deleted style \"${target.name}\"", "已刪除風格「${target.name}」"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text(tr("确认删除", "Delete", "確認刪除"))
                }
            },
            dismissButton = {
                TextButton(onClick = { stylePendingDelete = null }) {
                    Text(tr("取消", "Cancel", "取消"))
                }
            }
        )
    }

    // Restore Default Built-in Styles Confirmation
    if (showRestoreStylesConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreStylesConfirm = false },
            title = { Text(tr("恢复默认内置风格", "Reset Built-in Styles", "恢復預設內建風格")) },
            text = {
                Text(
                    tr(
                        "这会将 6 种内置预设风格的名称、简介与 System Prompt 恢复为出厂默认配置；您自己新建的自定义风格将予以保留。",
                        "This resets the 6 built-in styles (names, summaries, and System Prompts) to factory defaults. Your custom styles will be kept.",
                        "這會將 6 種內建預設風格的名稱、簡介與 System Prompt 恢復為出廠預設設定；您自己新增的自訂風格將予以保留。"
                    )
                )
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
                        Toast.makeText(
                            context,
                            AppStrings.pick(appLang, "已恢复默认内置风格指令", "Restored built-in styles to defaults", "已恢復預設內建風格指令"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text(tr("立即恢复", "Reset Now", "立即恢復"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreStylesConfirm = false }) {
                    Text(tr("取消", "Cancel", "取消"))
                }
            }
        )
    }

    // Restore Default App Rules Confirmation
    if (showRestoreRulesConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreRulesConfirm = false },
            title = { Text(tr("恢复默认应用匹配规则", "Reset Default App Rules", "恢復預設應用匹配規則")) },
            text = {
                Text(
                    tr(
                        "将补全并恢复微信、飞书、钉钉、Gmail、Notion、ChatGPT 等默认宿主应用绑定规则（已添加的其他自定义包名规则仍会保留）。",
                        "Restores default app tone rules for messaging, work, email, docs, and AI apps. Your custom package rules will be kept.",
                        "將補全並恢復微信、飛書、釘釘、Gmail、Notion、ChatGPT 等預設宿主應用綁定規則（已新增的其他自訂套件規則仍會保留）。"
                    )
                )
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
                        Toast.makeText(
                            context,
                            AppStrings.pick(appLang, "已恢复默认应用规则", "Restored default app rules", "已恢復預設應用規則"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text(tr("确认恢复", "Reset", "確認恢復"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreRulesConfirm = false }) {
                    Text(tr("取消", "Cancel", "取消"))
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
                Toast.makeText(
                    context,
                    AppStrings.pick(appLang, "已绑定「$cleanName」语气规则", "Bound tone rule for \"$cleanName\"", "已綁定「$cleanName」語氣規則"),
                    Toast.LENGTH_SHORT
                ).show()
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
    val appLang = LocalAppLanguage.current
    var promptExpanded by rememberSaveable(style.id) { mutableStateOf(false) }
    val dispName = localizedStyleName(style, appLang)
    val dispSummary = localizedStyleSummary(style, appLang)

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
                                text = dispName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            BadgePill(
                                text = if (style.isBuiltIn) tr("内置", "Built-in", "內建") else tr("自定义", "Custom", "自訂"),
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
                                    text = tr("当前锁定", "Locked", "目前鎖定"),
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        Text(
                            text = dispSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = tr("删除自定义风格", "Delete custom style", "刪除自訂風格"),
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
                            text = tr("System Prompt 润色指令", "System Prompt", "System Prompt 潤飾指令"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (boundAppCount > 0) {
                                tr(
                                    "已关联 $boundAppCount 个应用 · 点击展开",
                                    "$boundAppCount apps · Tap to toggle",
                                    "已關聯 $boundAppCount 個應用 · 點擊展開"
                                )
                            } else {
                                tr("点击展开/收起", "Tap to toggle", "點擊展開/收起")
                            },
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
                    Text(if (isLocked) tr("解除全局锁定", "Unlock Global", "解除全域鎖定") else tr("设为全局固定", "Lock Globally", "設為全域固定"))
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
                    Text(tr("编辑 Prompt", "Edit Prompt", "編輯 Prompt"))
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
    val appLang = LocalAppLanguage.current
    var styleDropdownExpanded by remember { mutableStateOf(false) }
    val boundStyle = styles.firstOrNull { it.id == rule.styleId }
        ?: styles.firstOrNull { it.id == "smart_default" }
        ?: styles.first()
    val dispAppName = localizedRuleAppName(rule, appLang).ifBlank { rule.packageName }
    val dispBoundStyleName = localizedStyleName(boundStyle, appLang)

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
                            text = dispAppName.trim().take(1).uppercase().ifBlank { "A" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = dispAppName,
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
                            contentDescription = tr("删除规则", "Delete rule", "刪除規則"),
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
                    text = tr("自动匹配风格 ->", "Matched Style ->", "自動匹配風格 ->"),
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
                                text = "${boundStyle.emoji} $dispBoundStyleName",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = tr("切换绑定风格", "Switch style", "切換綁定風格"),
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
                                            text = "${candidate.emoji} ${localizedStyleName(candidate, appLang)}",
                                            fontWeight = if (candidate.id == boundStyle.id) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            }
                                        )
                                        Text(
                                            text = localizedStyleSummary(candidate, appLang),
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
    val appLang = LocalAppLanguage.current
    val factoryBuiltin = remember(initialStyle?.id) {
        DefaultPresets.defaultStyles().firstOrNull { it.id == initialStyle?.id }
    }

    var emoji by rememberSaveable(initialStyle?.id) { mutableStateOf(initialStyle?.emoji ?: "✨") }
    var name by rememberSaveable(initialStyle?.id, appLang) {
        mutableStateOf(initialStyle?.let { localizedStyleName(it, appLang) } ?: "")
    }
    var summary by rememberSaveable(initialStyle?.id, appLang) {
        mutableStateOf(initialStyle?.let { localizedStyleSummary(it, appLang) } ?: "")
    }
    var promptInstruction by rememberSaveable(initialStyle?.id) { mutableStateOf(initialStyle?.promptInstruction ?: "") }

    val canSave = name.trim().isNotEmpty() && promptInstruction.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when {
                    initialStyle == null -> tr("新建自定义语气风格", "New Custom Tone Style", "新增自訂語氣風格")
                    initialStyle.isBuiltIn -> {
                        val dispBuiltin = localizedStyleName(initialStyle, appLang)
                        tr("编辑内置风格：$dispBuiltin", "Edit Built-in: $dispBuiltin", "編輯內建風格：$dispBuiltin")
                    }
                    else -> tr("编辑自定义风格", "Edit Custom Style", "編輯自訂風格")
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
                    text = tr("选择图标 Emoji", "Choose Icon Emoji", "選擇圖示 Emoji"),
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
                        label = { Text(tr("风格名称 *", "Style Name *", "風格名稱 *")) },
                        placeholder = { Text(tr("如：小红书种草 / 学术严谨", "e.g. Social Post / Academic", "如：社群分享 / 學術嚴謹")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text(tr("适用场景一句话简介", "One-Line Summary", "適用場景一句話簡介")) },
                    placeholder = { Text(tr("如：适合小红书与朋友圈，分段清爽、富有感染力", "e.g. Clean paragraphs and engaging tone for social posts", "如：適合社群貼文，分段清爽、富有感染力")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = promptInstruction,
                    onValueChange = { promptInstruction = it },
                    label = { Text(tr("系统润色指令 (System Prompt) *", "System Prompt *", "系統潤飾指令 (System Prompt) *")) },
                    placeholder = {
                        Text(
                            tr(
                                "描述大模型应当如何处理口语转写稿的语气、标点、分段与用词...",
                                "Describe how the model should polish tone, punctuation, paragraphs, and wording...",
                                "描述大模型應當如何處理口語轉寫稿的語氣、標點、分段與用詞..."
                            )
                        )
                    },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                if (factoryBuiltin != null && promptInstruction != factoryBuiltin.promptInstruction) {
                    TextButton(
                        onClick = {
                            name = localizedStyleName(factoryBuiltin, appLang)
                            emoji = factoryBuiltin.emoji
                            summary = localizedStyleSummary(factoryBuiltin, appLang)
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
                        Text(tr("恢复此内置风格出厂 Prompt", "Reset to Factory Prompt", "恢復此內建風格出廠 Prompt"))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    val cleanName = name.trim()
                    val cleanSummary = summary.trim().ifBlank {
                        AppStrings.pick(appLang, "自定义场景润色风格", "Custom tone polishing style", "自訂場景潤飾風格")
                    }
                    // Preserve factory Chinese baseline if user didn't customize built-in name/summary
                    val storedName = if (factoryBuiltin != null && cleanName == AppStrings.toneStyleName(factoryBuiltin, appLang)) {
                        factoryBuiltin.name
                    } else {
                        cleanName
                    }
                    val storedSummary = if (factoryBuiltin != null && cleanSummary == AppStrings.toneStyleSummary(factoryBuiltin, appLang)) {
                        factoryBuiltin.summary
                    } else {
                        cleanSummary
                    }
                    val result = if (initialStyle != null) {
                        initialStyle.copy(
                            emoji = emoji.trim().ifBlank { "✨" },
                            name = storedName,
                            summary = storedSummary,
                            promptInstruction = promptInstruction.trim()
                        )
                    } else {
                        ToneStyle(
                            emoji = emoji.trim().ifBlank { "✨" },
                            name = storedName,
                            summary = storedSummary,
                            promptInstruction = promptInstruction.trim(),
                            isBuiltIn = false
                        )
                    }
                    onConfirm(result)
                }
            ) {
                Text(tr("保存风格", "Save Style", "儲存風格"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消", "Cancel", "取消"))
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
    val appLang = LocalAppLanguage.current

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
        title = { Text(tr("添加应用自动匹配规则", "Add App Matching Rule", "新增應用自動匹配規則")) },
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
                            title = tr("📱 本机已装应用", "📱 Installed Apps", "📱 本機已裝應用"),
                            selected = inputMode == 0,
                            onClick = { inputMode = 0 },
                            modifier = Modifier.weight(1f)
                        )
                        SegmentedTabButton(
                            title = tr("⌨️ 手动输入包名", "⌨️ Manual Package", "⌨️ 手動輸入套件名"),
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
                        placeholder = { Text(tr("搜索本机应用名或包名...", "Search installed app or package...", "搜尋本機應用名或套件名...")) },
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
                                        contentDescription = tr("清除", "Clear", "清除"),
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
                                        Text(tr("正在扫描已安装应用...", "Scanning installed apps...", "正在掃描已安裝應用..."), style = MaterialTheme.typography.bodySmall)
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
                                            tr(
                                                "未读取到启动器应用列表，可切换至「手动输入包名」直接添加",
                                                "Launcher app list unavailable. Switch to \"Manual Package\" to add directly.",
                                                "未讀取到啟動器應用清單，可切換至「手動輸入套件名」直接新增"
                                            )
                                        } else {
                                            tr(
                                                "没有找到匹配「$appSearchQuery」的已安装应用",
                                                "No installed apps matching \"$appSearchQuery\"",
                                                "沒有找到符合「$appSearchQuery」的已安裝應用"
                                            )
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
                                                                text = tr("已配置", "Configured", "已設定"),
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
                                                        contentDescription = tr("已选中", "Selected", "已選取"),
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
                                text = tr(
                                    "已选应用：${appName.ifBlank { packageName }} ($packageName)",
                                    "Selected: ${appName.ifBlank { packageName }} ($packageName)",
                                    "已選應用：${appName.ifBlank { packageName }} ($packageName)"
                                ),
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
                        label = { Text(tr("应用显示名称", "App Display Name", "應用顯示名稱")) },
                        placeholder = { Text(tr("如：微信 / 飞书 / Obsidian", "e.g. Slack / WhatsApp / Obsidian", "如：LINE / Slack / Obsidian")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text(tr("应用包名 *", "Package Name *", "應用套件名 *")) },
                        placeholder = { Text(tr("如：com.tencent.mm", "e.g. com.Slack", "如：jp.naver.line.android")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text = tr("绑定到此语气风格", "Bind to Tone Style", "綁定到此語氣風格"),
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
                                label = { Text("${style.emoji} ${localizedStyleName(style, appLang)}") }
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
                Text(tr("绑定规则", "Bind Rule", "綁定規則"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消", "Cancel", "取消"))
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
