package com.typefree.ime.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.core.ime.profile.SchemaArchetype
import com.typefree.core.ime.profile.SchemaProfile
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.KeycapBadgeMode
import com.typefree.ime.data.stats.SchemeStatsKeys
import com.typefree.ime.data.stats.StatsTimeRange
import com.typefree.ime.ime.KeyboardMetrics
import com.typefree.ime.ime.engine.RimeDataManager
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.ime.ime.flypy.FlypyDictionary
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.ContinuousRoundedCornerShape
import com.typefree.ime.ui.components.SpringSegmentedControl
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SchemeIsolationKind {
    XIAOHE_YINXING,
    CUSTOM_CODETABLE,
    PINYIN_OR_DOUBLE_PINYIN
}

fun resolveSchemeIsolationKind(
    inputScheme: InputScheme,
    archetype: SchemaArchetype,
    hasCustomSchema: Boolean = false
): SchemeIsolationKind {
    return when (inputScheme) {
        InputScheme.FLYPY_TABLE -> SchemeIsolationKind.XIAOHE_YINXING
        InputScheme.RIME_CUSTOM -> {
            if (archetype == SchemaArchetype.SENTENCE_PINYIN && !hasCustomSchema) {
                SchemeIsolationKind.PINYIN_OR_DOUBLE_PINYIN
            } else if (archetype == SchemaArchetype.SENTENCE_PINYIN) {
                SchemeIsolationKind.PINYIN_OR_DOUBLE_PINYIN
            } else {
                SchemeIsolationKind.CUSTOM_CODETABLE
            }
        }
        InputScheme.RIME_PINYIN,
        InputScheme.RIME_T9_PINYIN,
        InputScheme.RIME_DOUBLE_PINYIN_FLYPY -> SchemeIsolationKind.PINYIN_OR_DOUBLE_PINYIN
    }
}

@Composable
fun KeyboardStudioScreen(
    repository: AppRepository,
    initialSubTab: Int = AppDestinations.SUBTAB_KB_SCHEME,
    onSubTabChanged: ((Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState()
    val rimeDataManager = remember { TypeFreeApp.instance.rimeDataManager }
    val rimeDeployState by rimeDataManager.deploymentState.collectAsState()
    val metadataVersion by rimeDataManager.metadataVersion.collectAsState()

    val hasCustomImported = remember(rimeDeployState, metadataVersion) {
        rimeDataManager.hasCustomSchemaImported()
    }
    val installedCustomSchemas = remember(rimeDeployState, metadataVersion) {
        rimeDataManager.listInstalledCustomSchemas()
    }
    val activeCustomSchemaId = remember(rimeDeployState, metadataVersion) {
        rimeDataManager.resolveCustomSchemaId()
    }
    val customSchemaName = remember(rimeDeployState, metadataVersion) {
        rimeDataManager.readCustomSchemaName()
    }
    val activeProfile = remember(rimeDeployState, settings.inputScheme, metadataVersion) {
        rimeDataManager.activeSchemaProfile
    }

    val effectiveArchetype = remember(settings.inputScheme, activeProfile, hasCustomImported) {
        when (settings.inputScheme) {
            InputScheme.FLYPY_TABLE -> SchemaArchetype.FIXED_YINXING
            InputScheme.RIME_PINYIN,
            InputScheme.RIME_T9_PINYIN,
            InputScheme.RIME_DOUBLE_PINYIN_FLYPY -> SchemaArchetype.SENTENCE_PINYIN
            InputScheme.RIME_CUSTOM -> {
                if (activeProfile.schemaId != "rime_ice" && activeProfile.schemaId != "t9" && activeProfile.schemaId != "double_pinyin_flypy") {
                    activeProfile.archetype
                } else if (hasCustomImported) {
                    SchemaArchetype.FIXED_SHAPECODE
                } else {
                    SchemaArchetype.FIXED_SHAPECODE
                }
            }
        }
    }

    val isolationKind = remember(settings.inputScheme, effectiveArchetype, hasCustomImported) {
        resolveSchemeIsolationKind(
            inputScheme = settings.inputScheme,
            archetype = effectiveArchetype,
            hasCustomSchema = hasCustomImported
        )
    }

    var activeSubTab by rememberSaveable {
        mutableIntStateOf(
            initialSubTab.coerceIn(AppDestinations.SUBTAB_KB_SCHEME, AppDestinations.SUBTAB_KB_DICT)
        )
    }
    LaunchedEffect(initialSubTab) {
        val coerced = initialSubTab.coerceIn(AppDestinations.SUBTAB_KB_SCHEME, AppDestinations.SUBTAB_KB_DICT)
        if (activeSubTab != coerced) activeSubTab = coerced
    }

    fun selectSubTab(idx: Int) {
        val coerced = idx.coerceIn(AppDestinations.SUBTAB_KB_SCHEME, AppDestinations.SUBTAB_KB_DICT)
        activeSubTab = coerced
        onSubTabChanged?.invoke(coerced)
    }

    val schemeDisplayBadge = remember(settings.inputScheme, customSchemaName, activeProfile) {
        when (settings.inputScheme) {
            InputScheme.RIME_CUSTOM -> customSchemaName?.takeIf { it.isNotBlank() }
                ?: activeProfile.displayName.takeIf { it.isNotBlank() }
                ?: "自定义方案"
            else -> settings.inputScheme.label
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned Header + 3-Segment Switcher
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
                    text = "键盘",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Text(
                        text = schemeDisplayBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            SpringSegmentedControl(
                options = listOf("方案与布局", "手势与短语", "词库管理"),
                selectedIndex = activeSubTab,
                onSelect = ::selectSubTab,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (activeSubTab) {
                AppDestinations.SUBTAB_KB_SCHEME -> {
                    KeyboardSchemeAndLayoutPane(
                        repository = repository,
                        settings = settings,
                        isolationKind = isolationKind,
                        hasCustomImported = hasCustomImported,
                        customSchemaName = customSchemaName,
                        installedCustomSchemas = installedCustomSchemas,
                        activeCustomSchemaId = activeCustomSchemaId,
                        activeProfile = activeProfile,
                        onSwitchScheme = { nextScheme ->
                            repository.updateSettings { it.copy(inputScheme = nextScheme) }
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    if (rimeDataManager.isSchemeHandledByEnhancedEngine(nextScheme)) {
                                        rimeDataManager.activateEnhancedSchemeSync(nextScheme)
                                    }
                                    TypeFreeApp.instance.rimeInputEngine.setInputScheme(nextScheme)
                                }
                            }
                        },
                        onSelectCustomSchema = { targetSchemaId ->
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    rimeDataManager.selectCustomSchemaSync(targetSchemaId)
                                    TypeFreeApp.instance.rimeInputEngine.setInputScheme(InputScheme.RIME_CUSTOM)
                                }
                            }
                            repository.updateSettings { it.copy(inputScheme = InputScheme.RIME_CUSTOM) }
                        },
                        onGoToDictTab = { selectSubTab(AppDestinations.SUBTAB_KB_DICT) }
                    )
                }

                AppDestinations.SUBTAB_KB_GESTURE -> {
                    KeyboardGestureAndSnippetsPane(
                        repository = repository,
                        settings = settings,
                        activeSchemaId = activeProfile.schemaId.ifBlank { "flypy" }
                    )
                }

                AppDestinations.SUBTAB_KB_DICT -> {
                    KeyboardDictionaryPane(
                        repository = repository,
                        settings = settings,
                        isolationKind = isolationKind,
                        activeProfile = activeProfile,
                        rimeDeployState = rimeDeployState
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyboardSchemeAndLayoutPane(
    repository: AppRepository,
    settings: AppSettings,
    isolationKind: SchemeIsolationKind,
    hasCustomImported: Boolean,
    customSchemaName: String?,
    installedCustomSchemas: List<com.typefree.ime.ime.engine.RimeDataManager.InstalledCustomSchema>,
    activeCustomSchemaId: String,
    activeProfile: SchemaProfile,
    onSwitchScheme: (InputScheme) -> Unit,
    onSelectCustomSchema: (String) -> Unit,
    onGoToDictTab: () -> Unit
) {
    var showCheatSheetDialog by remember { mutableStateOf(false) }

    val customChipTitle = remember(hasCustomImported, customSchemaName, activeProfile) {
        when {
            hasCustomImported && !customSchemaName.isNullOrBlank() -> customSchemaName
            hasCustomImported && activeProfile.displayName.isNotBlank() -> activeProfile.displayName
            else -> "五笔 / 键道 / 自定义"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 卡片 1：输入方案与专属设置（严格按方案隔离）
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
                            text = "输入方案与专属设置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "仅显示当前激活方案的专属开关，互不干扰",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { showCheatSheetDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = when (isolationKind) {
                                SchemeIsolationKind.XIAOHE_YINXING -> "小鹤手势速查"
                                SchemeIsolationKind.CUSTOM_CODETABLE -> "码表手势速查"
                                SchemeIsolationKind.PINYIN_OR_DOUBLE_PINYIN -> "拼音手势速查"
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                // 顶部方案选择器（内置方案 + 所有已安装的自定义/预设方案）
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val builtinItems = listOf(
                        InputScheme.RIME_PINYIN to "全拼",
                        InputScheme.RIME_T9_PINYIN to "九键拼音",
                        InputScheme.RIME_DOUBLE_PINYIN_FLYPY to "小鹤双拼",
                        InputScheme.FLYPY_TABLE to "小鹤音形"
                    )
                    builtinItems.forEach { (scheme, label) ->
                        val selected = settings.inputScheme == scheme
                        FilterChip(
                            selected = selected,
                            onClick = { onSwitchScheme(scheme) },
                            label = {
                                Text(
                                    text = label,
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
                    if (installedCustomSchemas.isNotEmpty()) {
                        installedCustomSchemas.forEach { customItem ->
                            val selected = settings.inputScheme == InputScheme.RIME_CUSTOM &&
                                activeCustomSchemaId == customItem.schemaId
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectCustomSchema(customItem.schemaId) },
                                label = {
                                    Text(
                                        text = customItem.displayName,
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
                    } else {
                        val selected = settings.inputScheme == InputScheme.RIME_CUSTOM
                        FilterChip(
                            selected = selected,
                            onClick = { onSwitchScheme(InputScheme.RIME_CUSTOM) },
                            label = {
                                Text(
                                    text = customChipTitle,
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

                // 按激活方案动态显示专属区
                when (isolationKind) {
                    SchemeIsolationKind.XIAOHE_YINXING -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = TypeFreeEmerald.copy(alpha = 0.10f)
                        ) {
                            Text(
                                text = "当前方案：小鹤音形（四码定长）",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TypeFreeEmerald,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        PreferenceSwitchRow(
                            title = "四码 5 键底排（宽次选 ,）",
                            subtitle = "开启后底排变为「123 · ，次选 · 空格(长按切中英) · 。三选 · 回车」，更不易误触句号",
                            checked = settings.codetableFiveKeyBottomRow,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(codetableFiveKeyBottomRow = enabled) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = "候选状态下「, / .」选第 2 / 第 3 候选",
                            subtitle = "有重码时底排「,」上屏次选、「.」上屏三选；关闭则用逗号/句号顶字上屏",
                            checked = settings.flypyCommaPeriodQuickSelect,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(flypyCommaPeriodQuickSelect = enabled) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = "小鹤 o* 快捷指令",
                            subtitle = "支持 orq 日期星期、ouj 时间、ofi 反查、oqm 全码字、odz 单字、ovy/ojf/oai 等直通候选",
                            checked = settings.xiaoheDirectCommandsEnabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(xiaoheDirectCommandsEnabled = enabled) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = "空码二简逆切分",
                            subtitle = "4 码无精确匹配时自动按「2+(1~2)」切分为前段二简字词与后段候选，加速连打",
                            checked = settings.xiaoheReverseSplitEnabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(xiaoheReverseSplitEnabled = enabled) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        val currentNatLimit = settings.xiaoheNaturalContinuationLimit
                            .coerceIn(1, com.typefree.ime.data.MAX_NATURAL_CONTINUATION_LIMIT)
                        PreferenceSwitchRow(
                            title = "空码多条原序顺延",
                            subtitle = "空码时展示最多 ${currentNatLimit} 条更长编码顺延词（明文码表按原行序，预编译包按常用词频）",
                            checked = settings.xiaoheNaturalContinuationEnabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(xiaoheNaturalContinuationEnabled = enabled) }
                            }
                        )
                        if (settings.xiaoheNaturalContinuationEnabled) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                com.typefree.ime.data.NATURAL_CONTINUATION_LIMIT_OPTIONS.forEach { count ->
                                    val selected = currentNatLimit == count
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            repository.updateSettings {
                                                it.copy(xiaoheNaturalContinuationLimit = count)
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = if (count == com.typefree.ime.data.DEFAULT_NATURAL_CONTINUATION_LIMIT) {
                                                    "${count} 条 · 默认"
                                                } else {
                                                    "${count} 条"
                                                },
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
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = "L2 高频复现造词建议",
                            subtitle = "仅在内存滑窗统计同一未收录 2~4 字词复现 ≥3 次时在候选条弹窗确认，绝不静默写盘",
                            checked = settings.phraseSuggestionL2Enabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(phraseSuggestionL2Enabled = enabled) }
                            }
                        )
                    }

                    SchemeIsolationKind.CUSTOM_CODETABLE -> {
                        if (!hasCustomImported) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = TypeFreeAmber.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, TypeFreeAmber.copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "尚未导入五笔 / 星空键道等自定义方案包",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = onGoToDictTab) {
                                        Text("去安装 →", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = TypeFreeEmerald.copy(alpha = 0.10f)
                            ) {
                                Text(
                                    text = "当前码表方案：${customSchemaName ?: activeProfile.displayName} (${activeProfile.schemaId})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TypeFreeEmerald,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        PreferenceSwitchRow(
                            title = "四码 5 键底排（宽次选 ,）",
                            subtitle = "开启后底排变为「123 · ，次选 · 空格(长按切中英) · 。三选 · 回车」",
                            checked = settings.codetableFiveKeyBottomRow,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(codetableFiveKeyBottomRow = enabled) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = "候选状态下「, / .」选第 2 / 第 3 候选",
                            subtitle = "有重码时底排「,」上屏次选、「.」上屏三选；关闭则用逗号/句号顶字上屏",
                            checked = settings.flypyCommaPeriodQuickSelect,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(flypyCommaPeriodQuickSelect = enabled) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = "锁死码表原序",
                            subtitle = "保持码表文件原始候选排序，关闭动态调频以保护定长肌肉记忆",
                            checked = settings.fixedCandidateOrder ?: true,
                            onCheckedChange = { fixed ->
                                repository.updateSettings { it.copy(fixedCandidateOrder = fixed) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        val customNatLimit = settings.xiaoheNaturalContinuationLimit
                            .coerceIn(1, com.typefree.ime.data.MAX_NATURAL_CONTINUATION_LIMIT)
                        PreferenceSwitchRow(
                            title = "空码多条原序顺延",
                            subtitle = "无精确匹配时展示最多 ${customNatLimit} 条更长编码顺延词（明文码表按原行序，预编译包按常用词频）",
                            checked = settings.xiaoheNaturalContinuationEnabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(xiaoheNaturalContinuationEnabled = enabled) }
                            }
                        )
                        if (settings.xiaoheNaturalContinuationEnabled) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                com.typefree.ime.data.NATURAL_CONTINUATION_LIMIT_OPTIONS.forEach { count ->
                                    val selected = customNatLimit == count
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            repository.updateSettings {
                                                it.copy(xiaoheNaturalContinuationLimit = count)
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = if (count == com.typefree.ime.data.DEFAULT_NATURAL_CONTINUATION_LIMIT) {
                                                    "${count} 条 · 默认"
                                                } else {
                                                    "${count} 条"
                                                },
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
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = "L2 高频复现造词建议",
                            subtitle = "仅内存滑窗统计复现 ≥3 次后提示录入用户词库，绝不静默写盘",
                            checked = settings.phraseSuggestionL2Enabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(phraseSuggestionL2Enabled = enabled) }
                            }
                        )
                    }

                    SchemeIsolationKind.PINYIN_OR_DOUBLE_PINYIN -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        ) {
                            Text(
                                text = "当前拼音方案：${settings.inputScheme.label}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        PreferenceSwitchRow(
                            title = "智能词频动态调频",
                            subtitle = "根据日常选词习惯自动提升常用词句权重，整句连打更顺手",
                            checked = !(settings.fixedCandidateOrder ?: false),
                            onCheckedChange = { dynamicFreq ->
                                repository.updateSettings { it.copy(fixedCandidateOrder = !dynamicFreq) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = "智能下一词联想预测",
                            subtitle = "上屏中文词句后在候选条展示高概率后续词汇与常用搭配",
                            checked = settings.predictionEnabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(predictionEnabled = enabled) }
                            }
                        )
                    }
                }
            }
        }

        // 卡片 2：通用打字键盘
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
                    text = "通用打字键盘",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                PreferenceSwitchRow(
                    title = "打字面板默认开启中文",
                    subtitle = "未开启“记忆上一次键盘模式”时默认进入中文状态（长按空格或顶栏中/英可随时切换）",
                    checked = settings.flypyEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(flypyEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                PreferenceSwitchRow(
                    title = "顶部独立数字行 (1–0)",
                    subtitle = "默认关闭（Q–P 长按直输 1–0）；开启后在字母区上方显示独立数字行",
                    checked = settings.qwertyShowNumberRow,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(qwertyShowNumberRow = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                PreferenceSwitchRow(
                    title = "上滑回退键清空输入框",
                    subtitle = "默认关闭（上滑回退键为撤回上屏）；开启后在 26 键与九键回退键上滑直接清空当前输入框，清空后仍可撤回",
                    checked = settings.backspaceSwipeUpClearEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(backspaceSwipeUpClearEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

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
                                text = "在键盘底部增加防误触留白托起按键区，所有面板同步生效",
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
                            ) { Text("恢复默认", fontSize = 12.sp) }
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
                                label = { Text(KeyboardMetrics.labelForLiftLevel(level), fontSize = 12.sp, maxLines = 1) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                PreferenceSwitchRow(
                    title = "按键按下气泡放大预览",
                    subtitle = "按下字母或符号键时在键帽上方浮现放大预览气泡",
                    checked = settings.qwertyKeyPopupPreview,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(qwertyKeyPopupPreview = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "键帽角标显示（${settings.keycapBadgeMode.label}）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "自定义键帽顶部提示内容；选择「隐藏角标」后字母自动居中复原，不留顶空",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        KeycapBadgeMode.entries.forEach { mode ->
                            val selected = settings.keycapBadgeMode == mode
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    repository.updateSettings { it.copy(keycapBadgeMode = mode) }
                                },
                                label = { Text(mode.label, fontSize = 12.sp, maxLines = 1) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                PreferenceSwitchRow(
                    title = "输入框算式自动提示",
                    subtitle = "在光标前输入如 125*8= 等合法算式时，预测栏自动浮现计算结果，点击直出",
                    checked = settings.passiveCalculatorEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(passiveCalculatorEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                PreferenceSwitchRow(
                    title = "EN 英文模式词库联想",
                    subtitle = "切换至英文模式输入 ≥2 个字母时，预测栏自动提供常用英文单词补全",
                    checked = settings.englishModeWordSuggestionEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(englishModeWordSuggestionEnabled = enabled) }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
    }

    if (showCheatSheetDialog) {
        val (dialogTitle, tips) = when (isolationKind) {
            SchemeIsolationKind.XIAOHE_YINXING -> "小鹤音形手势速查" to listOf(
                "编码与顶字：四码唯一自动上屏；第 5 码顶首选上屏；Shift 有码时清码",
                "o* 快捷指令：orq 日期星期、ouj 时间、ofi 反查、oqm 全码字、odz 单字",
                "通配反查：长按 Z 输入 ` 反查（如 ji` 知音查形、`bo 知形查音）",
                "短语与快符：空闲态上滑句号（.）出 / 触发编码短语；空闲态上滑或长按逗号输入分号 ;（长按 L 输入冒号 ：）",
                "中英切换：长按空格或点击顶栏中/英切换；上滑字母键快速输出对应符号",
                "编辑操作：长按 A/X/C/V 全选/剪切/复制/粘贴；上滑 I 撤回，上滑 F 重复"
            )
            SchemeIsolationKind.CUSTOM_CODETABLE -> "${customSchemaName ?: activeProfile.displayName} 手势速查" to listOf(
                "定长与顶字：按当前码表方案规则上屏或第 5 码顶字上屏；Shift 有码时清码",
                "重码选词：候选态点击底排 , / . 快速选第 2 / 第 3 候选",
                "编码短语：空闲态上滑句号（.）出 / 触发自定义编码短语（如 /sd）",
                "中英切换：长按空格或点击顶栏中/英按钮随时切换",
                "26 键上滑 / 长按：上滑出快符，长按 Q–P 出数字 1–0，A/X/C/V 编辑操作"
            )
            SchemeIsolationKind.PINYIN_OR_DOUBLE_PINYIN -> "${settings.inputScheme.label} 手势速查" to listOf(
                "整句连打：连续输入拼音或小鹤双拼，空格上屏首选，数字或点选候选词",
                "空格滑移选词与中英：长按空格切中/英，左右滑动空格移动光标",
                "编码短语：空闲态上滑句号（.）出 / 触发自定义编码短语（如 /sd、/yx）",
                "26 键上滑 / 长按：上滑字母键出快捷符号，长按 Q–P 出数字 1–0",
                "编辑与撤回：长按 A/X/C/V 全选/剪切/复制/粘贴；上滑 I 撤回上屏"
            )
        }
        AlertDialog(
            onDismissRequest = { showCheatSheetDialog = false },
            title = { Text(dialogTitle) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tips.forEach { tip ->
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
                TextButton(onClick = { showCheatSheetDialog = false }) { Text("知道了") }
            }
        )
    }
}

@Composable
private fun KeyboardGestureAndSnippetsPane(
    repository: AppRepository,
    settings: AppSettings,
    activeSchemaId: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 区块 1：26 键上滑 / 长按自定义
        KeyGestureCustomizerCard(
            repository = repository,
            settings = settings
        )

        // 区块 2：`/` 编码短语管理
        SlashSnippetsManagementSection(
            repository = repository,
            snippets = settings.snippets
        )

        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun KeyboardDictionaryPane(
    repository: AppRepository,
    settings: AppSettings,
    isolationKind: SchemeIsolationKind,
    activeProfile: SchemaProfile,
    rimeDeployState: RimeDeploymentState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rimeDataManager = remember { TypeFreeApp.instance.rimeDataManager }
    val flypyDict by TypeFreeApp.instance.flypyDictionary.collectAsState()
    val statsRev by repository.dailyStatsRevision.collectAsState()
    val allSnapshot = remember(settings, statsRev) {
        repository.computeInsightsSnapshot(StatsTimeRange.ALL)
    }
    val activeSchemeKey = remember(settings.inputScheme) {
        SchemeStatsKeys.fromInputScheme(settings.inputScheme, isChineseMode = true)
    }
    val mainSchemeStat = remember(allSnapshot.schemeShares, activeSchemeKey) {
        allSnapshot.schemeShares.firstOrNull { it.key == activeSchemeKey && it.chars > 0 }
            ?: allSnapshot.schemeShares.maxByOrNull { it.chars }
    }
    val primarySchemeLabel = mainSchemeStat?.displayName ?: SchemeStatsKeys.displayNameForKey(activeSchemeKey)
    val primarySchemeChars = mainSchemeStat?.chars ?: allSnapshot.keyChars
    val personalPredictionCount = allSnapshot.personalBigramCount + allSnapshot.personalTrigramCount
    val isCustomActive = remember(flypyDict) { FlypyDictionary.isCustomDictionaryActive(context) }
    val isDeployingRime = rimeDeployState is RimeDeploymentState.Deploying
    val readyRimeState = rimeDeployState as? RimeDeploymentState.Ready
    val hasFullIce = readyRimeState?.hasFullRimeIce ?: rimeDataManager.hasFullRimeIceDictionary()
    val hasExtIce = readyRimeState?.hasExtRimeIce ?: rimeDataManager.hasExtRimeIceDictionary()
    val hasTencentIce = readyRimeState?.hasTencentRimeIce ?: rimeDataManager.hasTencentRimeIceDictionary()
    val activeGrammarId = readyRimeState?.activeGrammarModelId ?: rimeDataManager.installedGrammarModelId()

    var showRimeImportDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }

    val rimeZipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val importResult = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            rimeDataManager.importEnhancedSchemaPackage(stream)
                        } ?: throw IllegalArgumentException("无法读取所选 ZIP 文件")
                    }
                    TypeFreeApp.instance.rimeInputEngine.setInputScheme(InputScheme.RIME_CUSTOM)
                    repository.updateSettings { it.copy(inputScheme = InputScheme.RIME_CUSTOM) }
                    val label = importResult.displayName.ifBlank { "自定义 Rime 方案" }
                    Toast.makeText(context, "✓ 已导入并部署「$label」", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "导入 Rime 方案包失败: ${e.message ?: "格式错误"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 轻量级端侧输入资产卡（主方案累计字数 / 私人 L2 联想记忆 / 短语省字）
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = ContinuousRoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.52f))
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
                        text = "本机输入资产",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "端侧纯数字累计",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KeyboardAssetMetricTile(
                        label = "${primarySchemeLabel}累计",
                        value = SetupStatsHelper.formatCharCount(primarySchemeChars),
                        unit = "字",
                        modifier = Modifier.weight(1f)
                    )
                    KeyboardAssetMetricTile(
                        label = "L2 私人联想",
                        value = SetupStatsHelper.formatCharCount(personalPredictionCount),
                        unit = "条",
                        modifier = Modifier.weight(1f)
                    )
                    KeyboardAssetMetricTile(
                        label = "短语省字",
                        value = SetupStatsHelper.formatCharCount(allSnapshot.snippetSavedChars),
                        unit = "字",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (isolationKind != SchemeIsolationKind.PINYIN_OR_DOUBLE_PINYIN) {
            // 小鹤 / 码表方案：精简后的 CodetableAdvancedManagementCard + ZIP 导入 & 兼容 TSV 导入
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.52f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.KeyboardGrid,
                                contentDescription = null,
                                tint = TypeFreeEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "码表词库与方案包",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = TypeFreeEmerald.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = if (isolationKind == SchemeIsolationKind.XIAOHE_YINXING) {
                                    "小鹤音形 (${flypyDict.codeCount} 编码)"
                                } else {
                                    "${activeProfile.displayName} (${activeProfile.schemaId})"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TypeFreeEmerald,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                rimeZipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                            },
                            enabled = !isDeployingRime,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("导入 Rime 方案包 (.zip)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { showRimeImportDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("导入 TSV 码表", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (isCustomActive) {
                        TextButton(
                            onClick = { showRestoreConfirmDialog = true },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("恢复内置默认开源词库", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            CodetableAdvancedManagementCard(
                repository = repository,
                settings = settings,
                activeProfile = activeProfile,
                rimeDeployState = rimeDeployState,
                defaultExpanded = true
            )
        } else {
            // 全拼 / 小鹤双拼 / 九键拼音：雾凇拼音词库管理 + 八元组语法模型
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.52f))
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
                                text = "雾凇拼音词库管理 (rime-ice)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "已内置中英混输与 8,200+ 本地高频联想；按需下载完整雾凇扩展词库与腾讯词向量",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ) {
                        val statusText = when (val st = rimeDeployState) {
                            is RimeDeploymentState.Idle -> "Rime 引擎：待部署"
                            is RimeDeploymentState.Deploying -> st.message
                            is RimeDeploymentState.Ready -> {
                                val parts = buildList {
                                    add(
                                        when {
                                            st.hasFullRimeIce && st.hasExtRimeIce -> "完整雾凇 (base+ext+others)"
                                            st.hasFullRimeIce -> "雾凇基础完整版"
                                            else -> "内置精简词库"
                                        }
                                    )
                                    if (st.hasTencentRimeIce) add("腾讯词库")
                                    if (!st.activeGrammarModelId.isNullOrBlank()) {
                                        val gName = RimeDataManager.GRAMMAR_MODEL_PRESETS
                                            .firstOrNull { it.id == st.activeGrammarModelId }
                                            ?.displayName ?: st.activeGrammarModelId
                                        add("语法:$gName")
                                    }
                                }
                                "Rime 就绪：${parts.joinToString(" · ")} (${st.wordCount} 词条)"
                            }
                            is RimeDeploymentState.Error -> "Rime 状态：${st.message}"
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isDeployingRime) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                }
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (rimeDeployState is RimeDeploymentState.Error) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                            if (rimeDeployState is RimeDeploymentState.Deploying) {
                                LinearProgressIndicator(
                                    progress = { rimeDeployState.progress.coerceIn(0.02f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val ready = rimeDataManager.downloadFullRimeIceDictionary()
                                    val count = (ready as? RimeDeploymentState.Ready)?.wordCount ?: 0
                                    Toast.makeText(context, "✓ 完整雾凇扩展词库已部署 ($count 词条)", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "雾凇词库下载失败: ${e.message ?: "请检查网络"}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !isDeployingRime,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (hasFullIce && hasExtIce) {
                                "更新完整雾凇扩展词库 (base + ext + others · ~16 MB)"
                            } else {
                                "下载完整雾凇扩展词库 (base + ext + others · ~16 MB)"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    if (hasTencentIce) {
                                        rimeDataManager.removeTencentRimeIceDictionary()
                                        Toast.makeText(context, "已卸载腾讯词向量扩展库", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val ready = rimeDataManager.downloadTencentRimeIceDictionary()
                                        val count = (ready as? RimeDeploymentState.Ready)?.wordCount ?: 0
                                        Toast.makeText(context, "✓ 腾讯词向量扩展库已部署 ($count 词条)", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "腾讯词库操作失败: ${e.message ?: "请检查网络"}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !isDeployingRime,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (hasTencentIce) {
                                "已安装腾讯词向量扩展库 · 点击卸载"
                            } else {
                                "可选追加：腾讯词向量扩展库 (tencent · ~17 MB，含海量口语词)"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                rimeZipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                            },
                            enabled = !isDeployingRime,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("导入 Rime 方案包 (.zip)", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { rimeDataManager.restoreDefaultRimeData(settings.inputScheme) }
                                        .onSuccess { Toast.makeText(context, "已恢复默认雾凇词库", Toast.LENGTH_SHORT).show() }
                                        .onFailure { Toast.makeText(context, "恢复失败: ${it.message}", Toast.LENGTH_SHORT).show() }
                                }
                            },
                            enabled = !isDeployingRime,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("恢复默认", fontSize = 12.sp)
                        }
                    }
                }
            }

            // 八元组语法模型卡片 (librime-octagram)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.52f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                text = "八元组语法模型 (librime-octagram)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "离线大幅提升全拼 / 双拼 / 九键长句连打选词准确率（的/地/得、在/再、同音歧义）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!activeGrammarId.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = TypeFreeEmerald.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = "已启用",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TypeFreeEmerald,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    RimeDataManager.GRAMMAR_MODEL_PRESETS.forEach { preset ->
                        val isInstalled = activeGrammarId == preset.id
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isInstalled) {
                                TypeFreeEmerald.copy(alpha = 0.08f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (isInstalled) TypeFreeEmerald.copy(alpha = 0.45f)
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = preset.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = preset.sizeLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Text(
                                        text = preset.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 16.sp
                                    )
                                }
                                if (isInstalled) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                runCatching { rimeDataManager.removeGrammarModel() }
                                                    .onSuccess {
                                                        Toast.makeText(context, "已停用并删除语法模型", Toast.LENGTH_SHORT).show()
                                                    }
                                                    .onFailure {
                                                        Toast.makeText(context, "停用失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                            }
                                        },
                                        enabled = !isDeployingRime,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("卸载", fontSize = 12.sp)
                                    }
                                } else {
                                    FilledTonalButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    rimeDataManager.downloadGrammarModel(preset)
                                                    Toast.makeText(
                                                        context,
                                                        "✓ 已启用「${preset.displayName}」",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } catch (e: Exception) {
                                                    Toast.makeText(
                                                        context,
                                                        "语法模型下载失败: ${e.message ?: "请检查网络"}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        },
                                        enabled = !isDeployingRime,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("安装", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            BuiltInPresetSchemasCard(
                repository = repository,
                rimeDeployState = rimeDeployState,
                defaultExpanded = true
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
    }

    if (showRimeImportDialog) {
        RimeDictionaryImportDialog(onDismiss = { showRimeImportDialog = false })
    }

    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = { Text("恢复内置开源词库") },
            text = { Text("确定要清除当前自定义私有码表并恢复默认内置开源词库吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    FlypyDictionary.restoreDefaultDictionary(context)
                                    TypeFreeApp.instance.reloadFlypyDictionary()
                                }
                                Toast.makeText(context, "已恢复内置默认开源词库", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "恢复失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                            showRestoreConfirmDialog = false
                        }
                    }
                ) { Text("确认恢复") }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirmDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun KeyboardAssetMetricTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }
    }
}
