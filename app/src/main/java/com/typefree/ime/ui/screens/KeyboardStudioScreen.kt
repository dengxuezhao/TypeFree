package com.typefree.ime.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.core.ime.enhancer.DirectCommandEngine
import com.typefree.core.ime.enhancer.ShadowDictManager
import com.typefree.core.ime.importer.CustomUserPhraseFileEditor
import com.typefree.core.ime.importer.SubDictToggleManager
import com.typefree.core.ime.profile.SchemaArchetype
import com.typefree.core.ime.profile.SchemaProfile
import com.typefree.ime.BuildConfig
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.KeycapBadgeMode
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SnippetMatcher
import com.typefree.ime.data.stats.StatsTimeRange
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ime.KeyboardMetrics
import com.typefree.ime.ime.engine.GrammarModelPreset
import com.typefree.ime.ime.engine.PresetSchemaCatalog
import com.typefree.ime.ime.engine.RimeDataManager
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.ime.ime.flypy.FlypyDictionary
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.ContinuousRoundedCornerShape
import com.typefree.ime.ui.components.SpringSegmentedControl
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import java.io.ByteArrayInputStream
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

private fun formatCustomSchemaChipLabel(
    schemaId: String,
    rawDisplayName: String,
    lang: AppLang
): String {
    return when (schemaId) {
        "flypy" -> AppStrings.pick(
            lang,
            rawDisplayName.ifBlank { "小鹤音形" },
            "Flypy (小鹤音形)",
            "小鶴音形"
        )
        "wubi86" -> AppStrings.pick(
            lang,
            rawDisplayName.ifBlank { "五笔 86" },
            "Wubi 86",
            "五筆 86"
        )
        "tiger" -> AppStrings.pick(
            lang,
            rawDisplayName.ifBlank { "虎码官方单字" },
            "Tiger Code",
            "虎碼官方單字"
        )
        "tigress" -> AppStrings.pick(
            lang,
            rawDisplayName.ifBlank { "虎码官方字词" },
            "Tiger Phrases",
            "虎碼官方字詞"
        )
        "xkjd6" -> AppStrings.pick(
            lang,
            rawDisplayName.ifBlank { "星空键道 6" },
            "XKJD 6",
            "星空鍵道 6"
        )
        else -> rawDisplayName
    }
}

private fun formatCustomSchemaBadgeLabel(
    schemaId: String,
    rawName: String?,
    lang: AppLang
): String {
    val clean = rawName?.trim().orEmpty()
    return when {
        schemaId == "flypy" || clean.contains("小鹤音形") ->
            AppStrings.pick(lang, "小鹤音形", "Flypy · 小鹤音形", "小鶴音形")
        schemaId == "wubi86" || clean == "五笔 86" ->
            AppStrings.pick(lang, clean.ifBlank { "五笔 86" }, "Wubi 86", "五筆 86")
        schemaId == "tiger" || clean.contains("虎码官方单字") ->
            AppStrings.pick(lang, clean.ifBlank { "虎码官方单字" }, "Tiger Code", "虎碼官方單字")
        schemaId == "tigress" || clean.contains("虎码官方字词") ->
            AppStrings.pick(lang, clean.ifBlank { "虎码官方字词" }, "Tiger Phrases", "虎碼官方字詞")
        schemaId == "xkjd6" || clean.contains("星空键道") ->
            AppStrings.pick(lang, clean.ifBlank { "星空键道 6" }, "XKJD 6", "星空鍵道 6")
        clean.isNotBlank() -> clean
        else -> AppStrings.pick(lang, "自定义方案", "Custom Rime", "自訂方案")
    }
}

private fun compactKeycapBadgeModeLabel(mode: KeycapBadgeMode, lang: AppLang): String {
    return when (mode) {
        KeycapBadgeMode.SWIPE_UP -> AppStrings.pick(lang, "上滑角标", "Swipe-Up", "上滑角標")
        KeycapBadgeMode.LONG_PRESS -> AppStrings.pick(lang, "长按角标", "Long-Press", "長按角標")
        KeycapBadgeMode.NONE -> AppStrings.pick(lang, "隐藏角标", "Hidden", "隱藏角標")
    }
}

@Composable
fun KeyboardStudioScreen(
    repository: AppRepository,
    initialSubTab: Int = AppDestinations.SUBTAB_KB_SCHEME,
    onSubTabChanged: ((Int) -> Unit)? = null
) {
    val appLang = LocalAppLanguage.current
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

    val schemeDisplayBadge = remember(
        settings.inputScheme,
        customSchemaName,
        activeProfile,
        activeCustomSchemaId,
        appLang,
        settings.lastQwertyIsChinese,
        settings.flypyEnabled
    ) {
        if (!settings.lastQwertyIsChinese || !settings.flypyEnabled) {
            AppStrings.pick(appLang, "英文键盘", "English QWERTY", "英文鍵盤")
        } else {
            when (settings.inputScheme) {
                InputScheme.RIME_CUSTOM -> formatCustomSchemaBadgeLabel(
                    schemaId = activeCustomSchemaId,
                    rawName = customSchemaName?.takeIf { it.isNotBlank() }
                        ?: activeProfile.displayName.takeIf { it.isNotBlank() },
                    lang = appLang
                )
                else -> AppStrings.inputSchemeLabel(settings.inputScheme, appLang)
            }
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
                    text = tr("键盘", "Keyboard", "鍵盤"),
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            SpringSegmentedControl(
                options = listOf(
                    tr("方案与布局", "Layouts", "方案與佈局"),
                    tr("手势与短语", "Gestures", "手勢與短語"),
                    tr("词库管理", "Dictionaries", "詞庫管理")
                ),
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
                        onSelectEnglishQwerty = {
                            repository.updateSettings { it.copy(lastQwertyIsChinese = false) }
                        },
                        onSwitchScheme = { nextScheme ->
                            repository.updateSettings {
                                it.copy(
                                    inputScheme = nextScheme,
                                    lastQwertyIsChinese = true,
                                    flypyEnabled = true
                                )
                            }
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
                            repository.updateSettings {
                                it.copy(
                                    inputScheme = InputScheme.RIME_CUSTOM,
                                    lastQwertyIsChinese = true,
                                    flypyEnabled = true
                                )
                            }
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
    installedCustomSchemas: List<RimeDataManager.InstalledCustomSchema>,
    activeCustomSchemaId: String,
    activeProfile: SchemaProfile,
    onSelectEnglishQwerty: () -> Unit,
    onSwitchScheme: (InputScheme) -> Unit,
    onSelectCustomSchema: (String) -> Unit,
    onGoToDictTab: () -> Unit
) {
    val appLang = LocalAppLanguage.current
    var showCheatSheetDialog by remember { mutableStateOf(false) }
    val isEnglishQwertyActive = !settings.lastQwertyIsChinese || !settings.flypyEnabled

    val customChipTitle = remember(hasCustomImported, customSchemaName, activeProfile, activeCustomSchemaId, appLang) {
        when {
            hasCustomImported && (!customSchemaName.isNullOrBlank() || activeProfile.displayName.isNotBlank()) ->
                formatCustomSchemaChipLabel(
                    schemaId = activeCustomSchemaId,
                    rawDisplayName = customSchemaName?.takeIf { it.isNotBlank() } ?: activeProfile.displayName,
                    lang = appLang
                )
            else -> AppStrings.pick(appLang, "五笔 / 虎码 / 自定义", "Wubi / Custom Rime", "五筆 / 虎碼 / 自訂")
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
                            text = tr("输入方案与专属设置", "Bilingual / Rime Input Scheme", "輸入方案與專屬設定"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr(
                                "仅显示当前激活方案的专属开关，互不干扰",
                                "Configure Chinese Rime / Shuangpin / Codetable schemes alongside English QWERTY",
                                "僅顯示目前啟用方案的專屬開關，互不干擾"
                            ),
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
                                SchemeIsolationKind.XIAOHE_YINXING -> tr("小鹤手势速查", "Cheat Sheet", "小鶴手勢速查")
                                SchemeIsolationKind.CUSTOM_CODETABLE -> tr("码表手势速查", "Cheat Sheet", "碼表手勢速查")
                                SchemeIsolationKind.PINYIN_OR_DOUBLE_PINYIN -> tr("拼音手势速查", "Cheat Sheet", "拼音手勢速查")
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                // 顶部方案选择器（English QWERTY + 内置方案 + 所有已安装的自定义/预设方案）
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = isEnglishQwertyActive,
                        onClick = onSelectEnglishQwerty,
                        label = {
                            Text(
                                text = tr("英文键盘", "English", "英文鍵盤"),
                                fontSize = 12.sp,
                                fontWeight = if (isEnglishQwertyActive) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )

                    val hideBuiltinFlypyTable = !BuildConfig.USE_PRIVATE_DICT &&
                        (installedCustomSchemas.any { it.schemaId == "flypy" } || settings.inputScheme != InputScheme.FLYPY_TABLE)

                    val builtinItems = buildList {
                        add(InputScheme.RIME_PINYIN to tr("全拼", "Full Pinyin", "全拼"))
                        add(InputScheme.RIME_T9_PINYIN to tr("九键拼音", "9-Key T9", "九鍵拼音"))
                        add(InputScheme.RIME_DOUBLE_PINYIN_FLYPY to tr("小鹤双拼", "Double Pinyin", "小鶴雙拼"))
                        if (!hideBuiltinFlypyTable) {
                            add(InputScheme.FLYPY_TABLE to tr("小鹤音形", "Flypy 4-Code", "小鶴音形"))
                        }
                    }

                    builtinItems.forEach { (scheme, label) ->
                        val selected = !isEnglishQwertyActive && settings.inputScheme == scheme
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
                            val selected = !isEnglishQwertyActive && (
                                (settings.inputScheme == InputScheme.RIME_CUSTOM && activeCustomSchemaId == customItem.schemaId) ||
                                    (customItem.schemaId == "flypy" && settings.inputScheme == InputScheme.FLYPY_TABLE)
                                )
                            val chipLabel = formatCustomSchemaChipLabel(
                                schemaId = customItem.schemaId,
                                rawDisplayName = customItem.displayName,
                                lang = appLang
                            )
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectCustomSchema(customItem.schemaId) },
                                label = {
                                    Text(
                                        text = chipLabel,
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
                        val selected = !isEnglishQwertyActive && settings.inputScheme == InputScheme.RIME_CUSTOM
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

                if (isEnglishQwertyActive) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f)
                    ) {
                        Text(
                            text = tr(
                                "当前键盘处于纯英文输入模式 · 点击上方任意中文方案可切回中文",
                                "Active: English QWERTY · Tap any Chinese scheme above or hold Space on keyboard to switch",
                                "目前鍵盤處於純英文輸入模式 · 點擊上方任意中文方案可切回中文"
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
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
                                text = tr(
                                    "当前方案：小鹤音形（四码定长）",
                                    "Active Scheme: Flypy (4-Code Fixed)",
                                    "目前方案：小鶴音形（四碼定長）"
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TypeFreeEmerald,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        PreferenceSwitchRow(
                            title = tr(
                                "四码 5 键底排（宽次选 ,）",
                                "5-Key Bottom Row (Wide ',' 2nd Choice)",
                                "四碼 5 鍵底排（寬次選 ,）"
                            ),
                            subtitle = tr(
                                "开启后底排变为「123 · ，次选 · 空格(长按切中英) · 。三选 · 回车」，更不易误触句号",
                                "Changes bottom row to '123 · , (2nd) · Space (hold for EN/中) · . (3rd) · Enter' to prevent period mis-taps",
                                "開啟後底排變為「123 · ，次選 · 空白鍵(長按切中英) · 。三選 · 換行」，更不易誤觸句號"
                            ),
                            checked = settings.codetableFiveKeyBottomRow,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(codetableFiveKeyBottomRow = enabled) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = tr(
                                "候选状态下「, / .」选第 2 / 第 3 候选",
                                "Select 2nd / 3rd Candidate with ',' and '.'",
                                "候選狀態下「, / .」選第 2 / 第 3 候選"
                            ),
                            subtitle = tr(
                                "有重码时底排「,」上屏次选、「.」上屏三选；关闭则用逗号/句号顶字上屏",
                                "When candidates are shown, ',' commits 2nd and '.' commits 3rd; when off, commits 1st + punctuation",
                                "有重碼時底排「,」上屏次選、「.」上屏三選；關閉則用逗號/句號頂字上屏"
                            ),
                            checked = settings.flypyCommaPeriodQuickSelect,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(flypyCommaPeriodQuickSelect = enabled) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = tr(
                                "小鹤 o* 快捷指令",
                                "Flypy 'o*' Quick Commands",
                                "小鶴 o* 快捷指令"
                            ),
                            subtitle = tr(
                                "支持 orq 日期星期、ouj 时间、ofi 反查、oqm 全码字、odz 单字、ovy/ojf/oai 等直通候选",
                                "Enable direct commands like orq (date), ouj (time), ofi (reverse lookup), oqm, odz, ovy, ojf & oai",
                                "支援 orq 日期星期、ouj 時間、ofi 反查、oqm 全碼字、odz 單字、ovy/ojf/oai 等直通候選"
                            ),
                            checked = settings.xiaoheDirectCommandsEnabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(xiaoheDirectCommandsEnabled = enabled) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = tr(
                                "空码二简逆切分",
                                "Empty-Code 2-Brief Fallback Split",
                                "空碼二簡逆切分"
                            ),
                            subtitle = tr(
                                "4 码无精确匹配时自动按「2+(1~2)」切分为前段二简字词与后段候选，加速连打",
                                "When a 4-letter code has no exact match, automatically split as 2+(1~2) brief words for continuous typing",
                                "4 碼無精確比對時自動按「2+(1~2)」切分為前段二簡字詞與後段候選，加速連打"
                            ),
                            checked = settings.xiaoheReverseSplitEnabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(xiaoheReverseSplitEnabled = enabled) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        val currentNatLimit = settings.xiaoheNaturalContinuationLimit
                            .coerceIn(1, com.typefree.ime.data.MAX_NATURAL_CONTINUATION_LIMIT)
                        PreferenceSwitchRow(
                            title = tr(
                                "空码多条原序顺延",
                                "Empty-Code Prefix Continuation",
                                "空碼多條原序順延"
                            ),
                            subtitle = tr(
                                "空码时展示最多 ${currentNatLimit} 条更长编码顺延词（明文码表按原行序，预编译包按常用词频）",
                                "Show up to $currentNatLimit longer prefix-matched words on empty codes in original table order",
                                "空碼時顯示最多 ${currentNatLimit} 條更長編碼順延詞（明文碼表按原行序，預編譯包按常用詞頻）"
                            ),
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
                                                    tr("${count} 条 · 默认", "$count · Default", "${count} 條 · 預設")
                                                } else {
                                                    tr("${count} 条", "$count items", "${count} 條")
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
                            title = tr(
                                "L2 高频复现造词建议",
                                "L2 Recurring Phrase Suggestions",
                                "L2 高頻複現造詞建議"
                            ),
                            subtitle = tr(
                                "仅在内存滑窗统计同一未收录 2~4 字词复现 ≥3 次时在候选条弹窗确认，绝不静默写盘",
                                "Prompt to add an unlisted 2–4 char phrase only after it recurs ≥3 times in RAM — never writes silently",
                                "僅在記憶體滑窗統計同一未收錄 2~4 字詞複現 ≥3 次時在候選條提示確認，絕不靜默寫入"
                            ),
                            checked = settings.phraseSuggestionL2Enabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(phraseSuggestionL2Enabled = enabled) }
                            }
                        )
                    }

                    SchemeIsolationKind.CUSTOM_CODETABLE -> {
                        if (!hasCustomImported && installedCustomSchemas.isEmpty()) {
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
                                        text = tr(
                                            "尚未导入五笔 / 虎码等自定义方案包",
                                            "No Wubi / Tiger / custom Rime schema installed yet",
                                            "尚未匯入五筆 / 虎碼等自訂方案包"
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = onGoToDictTab) {
                                        Text(tr("去安装 →", "Install →", "去安裝 →"), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else {
                            val activeCustomDisplay = formatCustomSchemaChipLabel(
                                schemaId = activeProfile.schemaId,
                                rawDisplayName = customSchemaName ?: activeProfile.displayName,
                                lang = appLang
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = TypeFreeEmerald.copy(alpha = 0.10f)
                            ) {
                                Text(
                                    text = tr(
                                        "当前码表方案：$activeCustomDisplay (${activeProfile.schemaId})",
                                        "Active Codetable: $activeCustomDisplay (${activeProfile.schemaId})",
                                        "目前碼表方案：$activeCustomDisplay (${activeProfile.schemaId})"
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TypeFreeEmerald,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        PreferenceSwitchRow(
                            title = tr(
                                "四码 5 键底排（宽次选 ,）",
                                "5-Key Bottom Row (Wide ',' 2nd Choice)",
                                "四碼 5 鍵底排（寬次選 ,）"
                            ),
                            subtitle = tr(
                                "开启后底排变为「123 · ，次选 · 空格(长按切中英) · 。三选 · 回车」",
                                "Changes bottom row to '123 · , (2nd) · Space (hold for EN/中) · . (3rd) · Enter'",
                                "開啟後底排變為「123 · ，次選 · 空白鍵(長按切中英) · 。三選 · 換行」"
                            ),
                            checked = settings.codetableFiveKeyBottomRow,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(codetableFiveKeyBottomRow = enabled) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = tr(
                                "候选状态下「, / .」选第 2 / 第 3 候选",
                                "Select 2nd / 3rd Candidate with ',' and '.'",
                                "候選狀態下「, / .」選第 2 / 第 3 候選"
                            ),
                            subtitle = tr(
                                "有重码时底排「,」上屏次选、「.」上屏三选；关闭则用逗号/句号顶字上屏",
                                "When candidates are shown, ',' commits 2nd and '.' commits 3rd; when off, commits 1st + punctuation",
                                "有重碼時底排「,」上屏次選、「.」上屏三選；關閉則用逗號/句號頂字上屏"
                            ),
                            checked = settings.flypyCommaPeriodQuickSelect,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(flypyCommaPeriodQuickSelect = enabled) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = tr(
                                "锁死码表原序",
                                "Lock Original Codetable Order",
                                "鎖死碼表原序"
                            ),
                            subtitle = tr(
                                "保持码表文件原始候选排序，关闭动态调频以保护定长肌肉记忆",
                                "Preserve static candidate ordering and disable dynamic frequency tuning to protect muscle memory",
                                "保持碼表檔案原始候選排序，關閉動態調頻以保護定長肌肉記憶"
                            ),
                            checked = settings.fixedCandidateOrder ?: true,
                            onCheckedChange = { fixed ->
                                repository.updateSettings { it.copy(fixedCandidateOrder = fixed) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        val customNatLimit = settings.xiaoheNaturalContinuationLimit
                            .coerceIn(1, com.typefree.ime.data.MAX_NATURAL_CONTINUATION_LIMIT)
                        PreferenceSwitchRow(
                            title = tr(
                                "空码多条原序顺延",
                                "Empty-Code Prefix Continuation",
                                "空碼多條原序順延"
                            ),
                            subtitle = tr(
                                "无精确匹配时展示最多 ${customNatLimit} 条更长编码顺延词（明文码表按原行序，预编译包按常用词频）",
                                "Show up to $customNatLimit longer prefix-matched words on empty codes in original table order",
                                "無精確比對時顯示最多 ${customNatLimit} 條更長編碼順延詞（明文碼表按原行序，預編譯包按常用詞頻）"
                            ),
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
                                                    tr("${count} 条 · 默认", "$count · Default", "${count} 條 · 預設")
                                                } else {
                                                    tr("${count} 条", "$count items", "${count} 條")
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
                            title = tr(
                                "L2 高频复现造词建议",
                                "L2 Recurring Phrase Suggestions",
                                "L2 高頻複現造詞建議"
                            ),
                            subtitle = tr(
                                "仅内存滑窗统计复现 ≥3 次后提示录入用户词库，绝不静默写盘",
                                "Suggest saving recurring phrases after ≥3 hits in RAM — never writes to disk without confirmation",
                                "僅記憶體滑窗統計複現 ≥3 次後提示寫入使用者詞庫，絕不靜默寫入"
                            ),
                            checked = settings.phraseSuggestionL2Enabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(phraseSuggestionL2Enabled = enabled) }
                            }
                        )
                    }

                    SchemeIsolationKind.PINYIN_OR_DOUBLE_PINYIN -> {
                        val pinyinLabel = AppStrings.inputSchemeLabel(settings.inputScheme, appLang)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        ) {
                            Text(
                                text = tr(
                                    "当前拼音方案：$pinyinLabel",
                                    "Active Pinyin Scheme: $pinyinLabel",
                                    "目前拼音方案：$pinyinLabel"
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        PreferenceSwitchRow(
                            title = tr(
                                "智能词频动态调频",
                                "Dynamic Word Frequency Learning",
                                "智慧詞頻動態調頻"
                            ),
                            subtitle = tr(
                                "根据日常选词习惯自动提升常用词句权重，整句连打更顺手",
                                "Boost frequently selected words and phrases automatically for smoother sentence input",
                                "根據日常選詞習慣自動提升常用詞句權重，整句連打更順手"
                            ),
                            checked = !(settings.fixedCandidateOrder ?: false),
                            onCheckedChange = { dynamicFreq ->
                                repository.updateSettings { it.copy(fixedCandidateOrder = !dynamicFreq) }
                            }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = tr(
                                "智能下一词联想预测",
                                "Smart Next-Word Prediction",
                                "智慧下一詞聯想預測"
                            ),
                            subtitle = tr(
                                "上屏中文词句后在候选条展示高概率后续词汇与常用搭配",
                                "Suggest high-probability follow-up words and collocations after committing Chinese text",
                                "上屏中文詞句後在候選條顯示高機率後續詞彙與常用搭配"
                            ),
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
                val cardLang = LocalAppLanguage.current
                Text(
                    text = tr("通用打字键盘与英文智能输入", "General Typing & Smart English", "一般打字鍵盤與英文智慧輸入"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                PreferenceSwitchRow(
                    title = tr("EN 英文句首与 I 自动大写", "English Smart Auto-Capitalization", "EN 英文句首與 I 自動大寫"),
                    subtitle = tr(
                        "在普通文本输入框首、换行后及「. / ! / ? 」后自动启用单次大写（自动跳过密码/网址/邮箱/代码框）",
                        "Auto-enable one-shot Shift at the start of text fields, new lines, and after \". \", \"! \", or \"? \" (skips URLs, emails & code)",
                        "在一般文字輸入框首、換行後及「. / ! / ? 」後自動啟用單次大寫（自動跳過密碼/網址/信箱/程式碼框）"
                    ),
                    checked = settings.englishAutoCap,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(englishAutoCap = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                PreferenceSwitchRow(
                    title = tr("EN 英文缩写与高频拼写纠错", "English Contractions & Typo Autocorrect", "EN 英文縮寫與高頻拼寫糾錯"),
                    subtitle = tr(
                        "空格自动补全 im→I'm、dont→don't、i→I 及 teh→the 等错词；误改后紧按一次退格立即还原",
                        "Auto-fix contractions (im→I'm, dont→don't, i→I) & common typos (teh→the) on Space; press Backspace once to undo immediately",
                        "空白鍵自動補全 im→I'm、dont→don't、i→I 及 teh→the 等錯詞；誤改後緊按一次退格立即還原"
                    ),
                    checked = settings.englishAutoCorrect,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(englishAutoCorrect = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                PreferenceSwitchRow(
                    title = tr("EN 英文模式词库联想", "English Word Suggestions", "EN 英文模式詞庫聯想"),
                    subtitle = tr(
                        "切换至英文模式输入 ≥2 个字母时，预测栏自动提供常用英文单词补全",
                        "Show instant English word completions in the suggestion strip as you type",
                        "切換至英文模式輸入 ≥2 個字母時，預測欄自動提供常用英文單字補全"
                    ),
                    checked = settings.englishModeWordSuggestionEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(englishModeWordSuggestionEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                PreferenceSwitchRow(
                    title = tr("打字面板默认开启中文", "Default QWERTY to Chinese Mode", "打字面板預設開啟中文"),
                    subtitle = tr(
                        "未开启“记忆上一次键盘模式”时默认进入中文状态（长按空格或顶栏中/英可随时切换）",
                        "Start QWERTY in Chinese when mode memory is off (tap EN/中 or hold Space anytime to toggle)",
                        "未開啟「記憶上一次鍵盤模式」時預設進入中文狀態（長按空白鍵或頂欄中/英可隨時切換）"
                    ),
                    checked = settings.flypyEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(flypyEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                PreferenceSwitchRow(
                    title = tr("顶部独立数字行 (1–0)", "Dedicated Top Number Row (1–0)", "頂部獨立數字列 (1–0)"),
                    subtitle = tr(
                        "默认关闭（Q–P 长按直输 1–0）；开启后在字母区上方显示独立数字行",
                        "Show a 5th row of number keys above Q–P (when off, swipe up or hold Q–P for 1–0)",
                        "預設關閉（Q–P 長按直輸 1–0）；開啟後在字母區上方顯示獨立數字列"
                    ),
                    checked = settings.qwertyShowNumberRow,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(qwertyShowNumberRow = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                PreferenceSwitchRow(
                    title = tr("上滑回退键清空输入框", "Swipe Up on Backspace to Clear All", "上滑退格鍵清空輸入框"),
                    subtitle = tr(
                        "默认关闭（上滑回退键为撤回上屏）；开启后在 26 键与九键回退键上滑直接清空当前输入框，清空后仍可撤回",
                        "When off, swiping up on Backspace undoes last commit; when on, clears the input box (still undoable)",
                        "預設關閉（上滑退格鍵為復原上屏）；開啟後在退格鍵上滑直接清空輸入框"
                    ),
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
                                text = "${tr("底部垫高", "Bottom Cushion Lift", "底部墊高")} · ${KeyboardMetrics.labelForLiftLevel(currentLiftLevel)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = tr(
                                    "在键盘底部增加防误触留白托起按键区，所有面板同步生效",
                                    "Raise the keyboard comfortably above your chin bezel without stretching keys",
                                    "在鍵盤底部增加防誤觸留白托起按鍵區，所有面板同步生效"
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
                            ) { Text(tr("恢复默认", "Reset", "恢復預設"), fontSize = 12.sp) }
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
                    title = tr("按键按下气泡放大预览", "Key-Press Magnifier Popup", "按鍵按下氣泡放大預覽"),
                    subtitle = tr(
                        "按下字母或符号键时在键帽上方浮现放大预览气泡",
                        "Show tactile callout bubble above keys on press",
                        "按下字母或符號鍵時在鍵帽上方浮現放大預覽氣泡"
                    ),
                    checked = settings.qwertyKeyPopupPreview,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(qwertyKeyPopupPreview = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${tr("键帽角标显示", "Secondary Keycap Badges", "鍵帽角標顯示")}（${AppStrings.keycapBadgeModeLabel(settings.keycapBadgeMode, cardLang)}）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = tr(
                            "自定义键帽顶部提示内容；选择「隐藏角标」后字母自动居中复原，不留顶空",
                            "Choose whether secondary symbols show on keycaps or hide for ultra-clean centered letters",
                            "自訂鍵帽頂部提示內容；選擇「隱藏角標」後字母自動置中復原"
                        ),
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
                                label = {
                                    Text(
                                        text = compactKeycapBadgeModeLabel(mode, cardLang),
                                        fontSize = 11.5.sp,
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
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                PreferenceSwitchRow(
                    title = tr("输入框算式自动提示", "Inline Math Calculator", "輸入框算式自動提示"),
                    subtitle = tr(
                        "在光标前输入如 125*8= 等合法算式时，预测栏自动浮现计算结果，点击直出",
                        "Type an expression like 125*8= to see and insert the instant math result",
                        "在游標前輸入如 125*8= 等算式時，預測欄自動浮現計算結果，點擊直出"
                    ),
                    checked = settings.passiveCalculatorEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(passiveCalculatorEnabled = enabled) }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
    }

    if (showCheatSheetDialog) {
        val customDisplay = formatCustomSchemaChipLabel(
            schemaId = activeProfile.schemaId,
            rawDisplayName = customSchemaName ?: activeProfile.displayName,
            lang = appLang
        )
        val pinyinLabel = AppStrings.inputSchemeLabel(settings.inputScheme, appLang)
        val (dialogTitle, tips) = when (isolationKind) {
            SchemeIsolationKind.XIAOHE_YINXING -> tr(
                "小鹤音形手势速查",
                "Flypy Gesture Cheat Sheet",
                "小鶴音形手勢速查"
            ) to listOf(
                tr(
                    "编码与顶字：四码唯一自动上屏；第 5 码顶首选上屏；Shift 有码时清码",
                    "4-Code Auto-Commit: Unique 4-code commits automatically; 5th key pushes 1st choice; Shift clears code",
                    "編碼與頂字：四碼唯一自動上屏；第 5 碼頂首選上屏；Shift 有碼時清碼"
                ),
                tr(
                    "o* 快捷指令：orq 日期星期、ouj 时间、ofi 反查、oqm 全码字、odz 单字",
                    "o* Quick Commands: orq (date), ouj (time), ofi (reverse lookup), oqm (full-code), odz (single char)",
                    "o* 快捷指令：orq 日期星期、ouj 時間、ofi 反查、oqm 全碼字、odz 單字"
                ),
                tr(
                    "通配反查：长按 Z 输入 ` 反查（如 ji` 知音查形、`bo 知形查音）",
                    "Wildcard Lookup: Hold Z to type ` (e.g. ji` to look up shape by sound, `bo for sound by shape)",
                    "萬用字元反查：長按 Z 輸入 ` 反查（如 ji` 知音查形、`bo 知形查音）"
                ),
                tr(
                    "短语与快符：空闲态上滑句号（.）出 / 触发编码短语；空闲态上滑或长按逗号输入分号 ;（长按 L 输入冒号 ：）",
                    "Snippets & Symbols: Swipe up on '.' when idle for '/' snippets; swipe/hold ',' for ';' (hold L for ':')",
                    "短語與快符：空閒態上滑句號（.）出 / 觸發編碼短語；空閒態上滑或長按逗號輸入分號 ;"
                ),
                tr(
                    "中英切换：长按空格或点击顶栏中/英切换；上滑字母键快速输出对应符号",
                    "EN/中文 Toggle: Hold Space or tap EN/中 on top bar; swipe up on letter keys for quick symbols",
                    "中英切換：長按空白鍵或點擊頂欄中/英切換；上滑字母鍵快速輸出對應符號"
                ),
                tr(
                    "编辑操作：长按 A/X/C/V 全选/剪切/复制/粘贴；上滑 I 撤回，上滑 F 重复",
                    "Editing Shortcuts: Hold A/X/C/V for Select All/Cut/Copy/Paste; swipe up I to Undo, F to Redo",
                    "編輯操作：長按 A/X/C/V 全選/剪下/複製/貼上；上滑 I 復原，上滑 F 重複"
                )
            )
            SchemeIsolationKind.CUSTOM_CODETABLE -> tr(
                "$customDisplay 手势速查",
                "$customDisplay Cheat Sheet",
                "$customDisplay 手勢速查"
            ) to listOf(
                tr(
                    "定长与顶字：按当前码表方案规则上屏或第 5 码顶字上屏；Shift 有码时清码",
                    "Auto-Commit & Push: Follows active codetable rules or 5th-key push commit; Shift clears active code",
                    "定長與頂字：按目前碼表方案規則上屏或第 5 碼頂字上屏；Shift 有碼時清碼"
                ),
                tr(
                    "重码选词：候选态点击底排 , / . 快速选第 2 / 第 3 候选",
                    "2nd / 3rd Candidate: Tap ',' or '.' on bottom row while candidates are open",
                    "重碼選詞：候選態點擊底排 , / . 快速選第 2 / 第 3 候選"
                ),
                tr(
                    "编码短语：空闲态上滑句号（.）出 / 触发自定义编码短语（如 /sd）",
                    "Slash Snippets: Swipe up on '.' when idle to trigger '/code' snippets (e.g. /sd)",
                    "編碼短語：空閒態上滑句號（.）出 / 觸發自訂編碼短語（如 /sd）"
                ),
                tr(
                    "中英切换：长按空格或点击顶栏中/英按钮随时切换",
                    "EN/中文 Toggle: Hold Space or tap the top-bar EN/中 pill anytime",
                    "中英切換：長按空白鍵或點擊頂欄中/英按鈕隨時切換"
                ),
                tr(
                    "26 键上滑 / 长按：上滑出快符，长按 Q–P 出数字 1–0，A/X/C/V 编辑操作",
                    "Swipe & Long-Press: Swipe up for symbols, hold Q–P for 1–0, hold A/X/C/V for Select All/Cut/Copy/Paste",
                    "26 鍵上滑 / 長按：上滑出快符，長按 Q–P 出數字 1–0，A/X/C/V 編輯操作"
                )
            )
            SchemeIsolationKind.PINYIN_OR_DOUBLE_PINYIN -> tr(
                "$pinyinLabel 手势速查",
                "$pinyinLabel Cheat Sheet",
                "$pinyinLabel 手勢速查"
            ) to listOf(
                tr(
                    "整句连打：连续输入拼音或小鹤双拼，空格上屏首选，数字或点选候选词",
                    "Sentence Typing: Type full phrases in Pinyin or Shuangpin; Space commits 1st candidate",
                    "整句連打：連續輸入拼音或小鶴雙拼，空白鍵上屏首選，數字或點選候選詞"
                ),
                tr(
                    "空格滑移选词与中英：长按空格切中/英，左右滑动空格移动光标",
                    "Spacebar Trackpad: Hold Space to toggle EN/中; slide left/right on Space to move cursor",
                    "空白鍵滑移與中英：長按空白鍵切中/英，左右滑動空白鍵移動游標"
                ),
                tr(
                    "编码短语：空闲态上滑句号（.）出 / 触发自定义编码短语（如 /sd、/yx）",
                    "Slash Snippets: Swipe up on '.' when idle to expand '/code' snippets (e.g. /sd, /yx)",
                    "編碼短語：空閒態上滑句號（.）出 / 觸發自訂編碼短語（如 /sd、/yx）"
                ),
                tr(
                    "26 键上滑 / 长按：上滑字母键出快捷符号，长按 Q–P 出数字 1–0",
                    "Swipe & Long-Press: Swipe up on letters for symbols; hold Q–P for numbers 1–0",
                    "26 鍵上滑 / 長按：上滑字母鍵出快捷符號，長按 Q–P 出數字 1–0"
                ),
                tr(
                    "编辑与撤回：长按 A/X/C/V 全选/剪切/复制/粘贴；上滑 I 撤回上屏",
                    "Edit & Undo: Hold A/X/C/V for Select All/Cut/Copy/Paste; swipe up on I to undo commit",
                    "編輯與復原：長按 A/X/C/V 全選/剪下/複製/貼上；上滑 I 復原上屏"
                )
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
                TextButton(onClick = { showCheatSheetDialog = false }) {
                    Text(tr("知道了", "Got it", "知道了"))
                }
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
        LocalizedKeyGestureCustomizerCard(
            repository = repository,
            settings = settings
        )

        // 区块 2：`/` 编码短语管理
        LocalizedSlashSnippetsManagementSection(
            repository = repository,
            snippets = settings.snippets
        )

        Spacer(modifier = Modifier.height(10.dp))
    }
}

private fun localizedActionKindTitle(kind: KeyGestureActionKind, lang: AppLang): String {
    return when (kind) {
        KeyGestureActionKind.COMMIT_TEXT -> AppStrings.pick(lang, "单符号 / 文本", "Symbol / Text", "單符號 / 文字")
        KeyGestureActionKind.COMMIT_PAIRED -> AppStrings.pick(lang, "成对居中符号", "Paired Brackets", "成對置中符號")
        KeyGestureActionKind.UNDO_LAST -> AppStrings.pick(lang, "撤回上屏", "Undo Commit", "復原上屏")
        KeyGestureActionKind.REPEAT_LAST -> AppStrings.pick(lang, "重复上屏", "Repeat Last", "重複上屏")
        KeyGestureActionKind.EDITOR_SHORTCUT -> AppStrings.pick(lang, "编辑快捷操作", "Edit Shortcut", "編輯快捷操作")
        KeyGestureActionKind.NONE -> AppStrings.pick(lang, "禁用", "Disabled", "停用")
    }
}

private fun localizedEditorShortcutLabel(opt: EditorShortcutOption, lang: AppLang): String {
    return when (opt) {
        EditorShortcutOption.SELECT_ALL -> AppStrings.pick(lang, "全选", "Select All", "全選")
        EditorShortcutOption.CUT -> AppStrings.pick(lang, "剪切", "Cut", "剪下")
        EditorShortcutOption.COPY -> AppStrings.pick(lang, "复制", "Copy", "複製")
        EditorShortcutOption.PASTE -> AppStrings.pick(lang, "粘贴", "Paste", "貼上")
    }
}

private fun localizedCompactKeycapBadge(spec: KeyGestureBindingSpec, lang: AppLang): String {
    if (spec.type == KeyGestureActionKind.NONE) return "—"
    if (spec.type == KeyGestureActionKind.EDITOR_SHORTCUT) {
        return when (spec.editorShortcut?.uppercase()) {
            "SELECT_ALL" -> AppStrings.pick(lang, "全", "All", "全")
            "CUT" -> AppStrings.pick(lang, "剪", "Cut", "剪")
            "COPY" -> AppStrings.pick(lang, "复", "Cpy", "複")
            "PASTE" -> AppStrings.pick(lang, "粘", "Pst", "貼")
            else -> spec.compactKeycapBadge()
        }
    }
    if (spec.type == KeyGestureActionKind.UNDO_LAST && (spec.label.isBlank() || spec.label == "撤回")) {
        return AppStrings.pick(lang, "撤回", "Undo", "復原")
    }
    if (spec.type == KeyGestureActionKind.REPEAT_LAST && (spec.label.isBlank() || spec.label == "重复")) {
        return AppStrings.pick(lang, "重复", "Redo", "重複")
    }
    return when (val raw = spec.compactKeycapBadge()) {
        "全" -> AppStrings.pick(lang, "全", "All", "全")
        "剪" -> AppStrings.pick(lang, "剪", "Cut", "剪")
        "复" -> AppStrings.pick(lang, "复", "Cpy", "複")
        "粘" -> AppStrings.pick(lang, "粘", "Pst", "貼")
        "撤回" -> AppStrings.pick(lang, "撤回", "Undo", "復原")
        "重复" -> AppStrings.pick(lang, "重复", "Redo", "重複")
        "全角" -> AppStrings.pick(lang, "全角", "FW", "全形")
        else -> raw
    }
}

@Composable
private fun LocalizedKeyGestureCustomizerCard(
    repository: AppRepository,
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val profile = remember(settings.customKeyGesturesJson) {
        KeyGestureConfigCodec.fromJsonOrDefault(settings.customKeyGesturesJson)
    }
    val defaults = remember { KeyGestureConfigCodec.defaultProfile() }
    val hasCustomChanges = remember(profile) {
        KeyGestureConfigCodec.isCustomized(profile)
    }
    var editingKey by remember { mutableStateOf<Char?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = tr("26 键上滑 / 长按自定义", "26-Key Swipe & Long-Press", "26 鍵上滑 / 長按自訂"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (hasCustomChanges) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = TypeFreeEmerald.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = tr("已自定义", "Customized", "已自訂"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TypeFreeEmerald,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = tr(
                            "点击下方任意键帽可分别设置「上滑动作（默认快符）」与「长按动作（默认数字/副功能/编辑）」",
                            "Tap any keycap below to customize its Swipe-Up symbol and Long-Press number or edit shortcut",
                            "點擊下方任意鍵帽可分別設定「上滑動作（預設快符）」與「長按動作（預設數字/副功能/編輯）」"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (hasCustomChanges) {
                    TextButton(
                        onClick = {
                            repository.updateSettings { it.copy(customKeyGesturesJson = "") }
                            Toast.makeText(
                                context,
                                AppStrings.pick(appLang, "已恢复 26 键默认上滑与长按手势", "Restored default 26-key gestures", "已恢復 26 鍵預設上滑與長按手勢"),
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
                        Text(tr("恢复默认", "Reset", "恢復預設"), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = tr("键盘键帽顶部角标", "Keycap Top Badges", "鍵盤鍵帽頂部角標"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
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
                            label = {
                                Text(
                                    text = compactKeycapBadgeModeLabel(mode, appLang),
                                    fontSize = 11.5.sp,
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
                }
                Text(
                    text = tr(
                        "选择「隐藏角标」可切换极简纯字母键帽（字母自动居中复原），所有上滑与长按手势仍可正常触发",
                        "Choose 'Hidden' for clean centered letters — all swipe-up and long-press gestures remain active",
                        "選擇「隱藏角標」可切換極簡純字母鍵帽（字母自動置中復原），所有上滑與長按手勢仍可正常觸發"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("顶部：↑ 上滑", "Top: ↑ Swipe Up", "頂部：↑ 上滑"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = tr("底部：⏱ 长按", "Bottom: ⏱ Hold", "底部：⏱ 長按"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val baseDensity = LocalDensity.current
            val lockedMiniPreviewDensity = remember(baseDensity.density) {
                Density(density = baseDensity.density, fontScale = 1.0f)
            }
            CompositionLocalProvider(LocalDensity provides lockedMiniPreviewDensity) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        KeyGestureConfigCodec.QWERTY_ROWS.forEachIndexed { rowIdx, rowKeys ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = when (rowIdx) {
                                            1 -> 10.dp
                                            2 -> 24.dp
                                            else -> 0.dp
                                        }
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                rowKeys.forEach { ch ->
                                    val swipeSpec = profile.swipeUp[ch] ?: KeyGestureBindingSpec(KeyGestureActionKind.NONE)
                                    val longSpec = profile.longPress[ch] ?: KeyGestureBindingSpec(KeyGestureActionKind.NONE)
                                    val isKeyModified = swipeSpec != defaults.swipeUp[ch] || longSpec != defaults.longPress[ch]

                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { editingKey = ch },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isKeyModified) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                        border = BorderStroke(
                                            width = if (isKeyModified) 1.2.dp else 0.8.dp,
                                            color = if (isKeyModified) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                            } else {
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                            }
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 2.dp, vertical = 3.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = localizedCompactKeycapBadge(swipeSpec, appLang),
                                                fontSize = 7.5.sp,
                                                lineHeight = 9.sp,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Clip
                                            )
                                            Text(
                                                text = ch.uppercaseChar().toString(),
                                                fontSize = 13.sp,
                                                lineHeight = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = localizedCompactKeycapBadge(longSpec, appLang),
                                                fontSize = 7.5.sp,
                                                lineHeight = 9.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Clip
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
    }

    editingKey?.let { keyChar ->
        val currentSwipe = profile.swipeUp[keyChar] ?: KeyGestureBindingSpec(KeyGestureActionKind.NONE)
        val currentLong = profile.longPress[keyChar] ?: KeyGestureBindingSpec(KeyGestureActionKind.NONE)
        val defaultSwipe = defaults.swipeUp[keyChar] ?: KeyGestureBindingSpec(KeyGestureActionKind.NONE)
        val defaultLong = defaults.longPress[keyChar] ?: KeyGestureBindingSpec(KeyGestureActionKind.NONE)

        LocalizedKeyGestureBindingDialog(
            keyChar = keyChar,
            initialSwipe = currentSwipe,
            initialLong = currentLong,
            defaultSwipe = defaultSwipe,
            defaultLong = defaultLong,
            onDismiss = { editingKey = null },
            onSave = { newSwipe, newLong ->
                val updatedSwipeMap = profile.swipeUp.toMutableMap().apply { put(keyChar, newSwipe) }
                val updatedLongMap = profile.longPress.toMutableMap().apply { put(keyChar, newLong) }
                val updatedProfile = KeyGestureProfileSpec(
                    swipeUp = updatedSwipeMap,
                    longPress = updatedLongMap
                )
                val newJson = if (KeyGestureConfigCodec.isCustomized(updatedProfile)) {
                    KeyGestureConfigCodec.toDeltaJson(updatedProfile)
                } else {
                    ""
                }
                repository.updateSettings { it.copy(customKeyGesturesJson = newJson) }
                editingKey = null
                Toast.makeText(
                    context,
                    AppStrings.pick(
                        appLang,
                        "已保存 ${keyChar.uppercaseChar()} 键手势设置",
                        "Saved gesture for key ${keyChar.uppercaseChar()}",
                        "已儲存 ${keyChar.uppercaseChar()} 鍵手勢設定"
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalizedKeyGestureBindingDialog(
    keyChar: Char,
    initialSwipe: KeyGestureBindingSpec,
    initialLong: KeyGestureBindingSpec,
    defaultSwipe: KeyGestureBindingSpec,
    defaultLong: KeyGestureBindingSpec,
    onDismiss: () -> Unit,
    onSave: (KeyGestureBindingSpec, KeyGestureBindingSpec) -> Unit
) {
    val appLang = LocalAppLanguage.current
    var gestureTab by rememberSaveable(keyChar) { mutableIntStateOf(0) }

    var swipeKind by rememberSaveable(keyChar) { mutableStateOf(initialSwipe.type) }
    var swipeText by rememberSaveable(keyChar) { mutableStateOf(initialSwipe.text) }
    var swipeRightText by rememberSaveable(keyChar) { mutableStateOf(initialSwipe.rightText) }
    var swipeLabel by rememberSaveable(keyChar) { mutableStateOf(initialSwipe.label) }
    var swipeShortcut by rememberSaveable(keyChar) { mutableStateOf(initialSwipe.editorShortcut ?: EditorShortcutOption.SELECT_ALL.wireName) }

    var longKind by rememberSaveable(keyChar) { mutableStateOf(initialLong.type) }
    var longText by rememberSaveable(keyChar) { mutableStateOf(initialLong.text) }
    var longRightText by rememberSaveable(keyChar) { mutableStateOf(initialLong.rightText) }
    var longLabel by rememberSaveable(keyChar) { mutableStateOf(initialLong.label) }
    var longShortcut by rememberSaveable(keyChar) { mutableStateOf(initialLong.editorShortcut ?: EditorShortcutOption.SELECT_ALL.wireName) }

    fun buildSpec(
        kind: KeyGestureActionKind,
        text: String,
        rightText: String,
        label: String,
        shortcut: String
    ): KeyGestureBindingSpec {
        return when (kind) {
            KeyGestureActionKind.NONE -> KeyGestureBindingSpec(KeyGestureActionKind.NONE, label = "")
            KeyGestureActionKind.COMMIT_TEXT -> KeyGestureBindingSpec(
                type = KeyGestureActionKind.COMMIT_TEXT,
                text = text,
                rightText = "",
                label = label.trim().ifEmpty {
                    when (text) {
                        "\t" -> "Tab"
                        "\u3000" -> "全角"
                        else -> text.take(4)
                    }
                }
            )
            KeyGestureActionKind.COMMIT_PAIRED -> KeyGestureBindingSpec(
                type = KeyGestureActionKind.COMMIT_PAIRED,
                text = text,
                rightText = rightText,
                label = label.trim().ifEmpty { "${text}${rightText}".take(4) }
            )
            KeyGestureActionKind.UNDO_LAST -> KeyGestureBindingSpec(
                type = KeyGestureActionKind.UNDO_LAST,
                label = label.trim().ifEmpty { "撤回" }
            )
            KeyGestureActionKind.REPEAT_LAST -> KeyGestureBindingSpec(
                type = KeyGestureActionKind.REPEAT_LAST,
                label = label.trim().ifEmpty { "重复" }
            )
            KeyGestureActionKind.EDITOR_SHORTCUT -> {
                val opt = EditorShortcutOption.entries.firstOrNull { it.wireName == shortcut }
                    ?: EditorShortcutOption.SELECT_ALL
                KeyGestureBindingSpec(
                    type = KeyGestureActionKind.EDITOR_SHORTCUT,
                    label = label.trim().ifEmpty { opt.label.take(1) },
                    editorShortcut = opt.wireName
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = tr(
                        "自定义 ${keyChar.uppercaseChar()} 键手势",
                        "Key ${keyChar.uppercaseChar()} Gestures",
                        "自訂 ${keyChar.uppercaseChar()} 鍵手勢"
                    )
                )
                TextButton(
                    onClick = {
                        swipeKind = defaultSwipe.type
                        swipeText = defaultSwipe.text
                        swipeRightText = defaultSwipe.rightText
                        swipeLabel = defaultSwipe.label
                        swipeShortcut = defaultSwipe.editorShortcut ?: EditorShortcutOption.SELECT_ALL.wireName

                        longKind = defaultLong.type
                        longText = defaultLong.text
                        longRightText = defaultLong.rightText
                        longLabel = defaultLong.label
                        longShortcut = defaultLong.editorShortcut ?: EditorShortcutOption.SELECT_ALL.wireName
                    }
                ) {
                    Text(tr("恢复该键默认", "Reset Key", "恢復該鍵預設"), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                        StudioSubTabPill(
                            title = tr("↑ 上滑动作", "↑ Swipe Up", "↑ 上滑動作"),
                            selected = gestureTab == 0,
                            onClick = { gestureTab = 0 },
                            modifier = Modifier.weight(1f)
                        )
                        StudioSubTabPill(
                            title = tr("⏱ 长按动作", "⏱ Long Press", "⏱ 長按動作"),
                            selected = gestureTab == 1,
                            onClick = { gestureTab = 1 },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                val activeKind = if (gestureTab == 0) swipeKind else longKind
                val activeText = if (gestureTab == 0) swipeText else longText
                val activeRight = if (gestureTab == 0) swipeRightText else longRightText
                val activeLabel = if (gestureTab == 0) swipeLabel else longLabel
                val activeShortcut = if (gestureTab == 0) swipeShortcut else longShortcut

                fun setKind(k: KeyGestureActionKind) {
                    if (gestureTab == 0) swipeKind = k else longKind = k
                }
                fun setText(v: String) {
                    if (gestureTab == 0) swipeText = v else longText = v
                }
                fun setRight(v: String) {
                    if (gestureTab == 0) swipeRightText = v else longRightText = v
                }
                fun setLabel(v: String) {
                    if (gestureTab == 0) swipeLabel = v else longLabel = v
                }
                fun setShortcut(v: String) {
                    if (gestureTab == 0) swipeShortcut = v else longShortcut = v
                }

                Text(
                    text = tr("动作类型", "Action Type", "動作類型"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    KeyGestureActionKind.entries.forEach { kind ->
                        FilterChip(
                            selected = activeKind == kind,
                            onClick = { setKind(kind) },
                            label = { Text(localizedActionKindTitle(kind, appLang), fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                when (activeKind) {
                    KeyGestureActionKind.COMMIT_TEXT -> {
                        OutlinedTextField(
                            value = activeText,
                            onValueChange = { setText(it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            label = { Text(tr("输出符号或文本", "Output Symbol or Text", "輸出符號或文字")) },
                            placeholder = { Text(tr("如：：“、→、#、@", "e.g. ->, #, @, ...", "如：：「、→、#、@")) }
                        )
                        val presets = listOf(
                            "Tab" to "\t",
                            tr("全角空格", "Full Space", "全形空白") to "\u3000",
                            "……" to "……",
                            "——" to "——",
                            "→" to "→"
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            presets.forEach { (presetLbl, presetVal) ->
                                OutlinedButton(
                                    onClick = {
                                        setText(presetVal)
                                        setLabel(if (presetVal == "\u3000") AppStrings.pick(appLang, "全角", "FW", "全形") else presetLbl.take(3))
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(presetLbl, fontSize = 11.sp)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = activeLabel,
                            onValueChange = { setLabel(it.take(4)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            label = { Text(tr("键帽角标（选填，最多 4 字）", "Keycap Badge (optional, max 4 chars)", "鍵帽角標（選填，最多 4 字）")) }
                        )
                    }
                    KeyGestureActionKind.COMMIT_PAIRED -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = activeText,
                                onValueChange = { setText(it) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                label = { Text(tr("左符号", "Left", "左符號")) },
                                placeholder = { Text(tr("如 「", "e.g. [", "如 「")) }
                            )
                            OutlinedTextField(
                                value = activeRight,
                                onValueChange = { setRight(it) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                label = { Text(tr("右符号", "Right", "右符號")) },
                                placeholder = { Text(tr("如 」", "e.g. ]", "如 」")) }
                            )
                        }
                        OutlinedTextField(
                            value = activeLabel,
                            onValueChange = { setLabel(it.take(4)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            label = { Text(tr("键帽角标（选填）", "Keycap Badge (optional)", "鍵帽角標（選填）")) }
                        )
                    }
                    KeyGestureActionKind.EDITOR_SHORTCUT -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            EditorShortcutOption.entries.forEach { opt ->
                                FilterChip(
                                    selected = activeShortcut == opt.wireName,
                                    onClick = {
                                        setShortcut(opt.wireName)
                                        setLabel("")
                                    },
                                    label = { Text(localizedEditorShortcutLabel(opt, appLang)) }
                                )
                            }
                        }
                    }
                    KeyGestureActionKind.UNDO_LAST -> {
                        Text(
                            text = tr("触发后撤回最近一次上屏内容。", "Undoes the most recently committed text.", "觸發後復原最近一次上屏內容。"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    KeyGestureActionKind.REPEAT_LAST -> {
                        Text(
                            text = tr("触发后重复输入上一次已上屏的文字。", "Inserts the last committed text again.", "觸發後重複輸入上一次已上屏的文字。"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    KeyGestureActionKind.NONE -> {
                        Text(
                            text = tr("已禁用该按键的此手势触发。", "This gesture is disabled for this key.", "已停用該按鍵的此手勢觸發。"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalSwipe = buildSpec(swipeKind, swipeText, swipeRightText, swipeLabel, swipeShortcut)
                    val finalLong = buildSpec(longKind, longText, longRightText, longLabel, longShortcut)
                    onSave(finalSwipe, finalLong)
                }
            ) {
                Text(tr("保存", "Save", "儲存"))
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
private fun LocalizedSlashSnippetsManagementSection(
    repository: AppRepository,
    snippets: List<SnippetEntry>
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    var showSnippetDialog by remember { mutableStateOf(false) }
    var editingSnippet by remember { mutableStateOf<SnippetEntry?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedAll by rememberSaveable { mutableStateOf(false) }

    val filteredSnippets = remember(snippets, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            snippets
        } else {
            snippets.filter { s ->
                s.title.contains(q, ignoreCase = true) ||
                    s.flypyCode.contains(q, ignoreCase = true) ||
                    s.content.contains(q, ignoreCase = true) ||
                    s.voiceTriggers.contains(q, ignoreCase = true)
            }
        }
    }
    val visibleSnippets = if (expandedAll || searchQuery.isNotBlank() || filteredSnippets.size <= 6) {
        filteredSnippets
    } else {
        filteredSnippets.take(6)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Text(
                        text = "${tr("/ 编码短语", "/ Slash Snippets", "/ 編碼短語")} (${snippets.count { it.enabled }}/${snippets.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    FilledTonalButton(
                        onClick = {
                            editingSnippet = null
                            showSnippetDialog = true
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(tr("新建", "New", "新增"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = tr(
                        "空闲态上滑句号（.）出 / 触发短码（如 /sd、/yx），亦可语音口令展开；点击下方卡片可编辑或删除",
                        "Swipe up on '.' when idle to expand '/code' snippets (e.g. /sd, /yx) or trigger by voice; tap any card to edit",
                        "空閒態上滑句號（.）出 / 觸發短碼（如 /sd、/yx），亦可語音口令展開；點擊下方卡片可編輯或刪除"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp
                )

                if (snippets.size > 4) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = {
                            Text(
                                tr("搜索短码、标题或内容…", "Search code, title, or text…", "搜尋短碼、標題或內容…"),
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = tr("清除", "Clear", "清除"),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else null,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (snippets.isEmpty()) {
                    OutlinedButton(
                        onClick = {
                            repository.updateSettings {
                                it.copy(snippets = DefaultPresets.defaultSnippets())
                            }
                            Toast.makeText(
                                context,
                                AppStrings.pick(appLang, "已载入示例短语模板", "Loaded starter snippet templates", "已載入範例短語範本"),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(tr("载入示例短语模板", "Load Sample Snippets", "載入範例短語範本"))
                    }
                } else if (visibleSnippets.isEmpty()) {
                    Text(
                        text = tr(
                            "未找到匹配「$searchQuery」的编码短语",
                            "No snippets matching \"$searchQuery\"",
                            "找不到符合「$searchQuery」的編碼短語"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        visibleSnippets.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pair.forEach { snip ->
                                    LocalizedCompactSnippetTile(
                                        snippet = snip,
                                        onToggleEnabled = { enabled ->
                                            repository.updateSettings { curr ->
                                                curr.copy(
                                                    snippets = curr.snippets.map {
                                                        if (it.id == snip.id) it.copy(enabled = enabled) else it
                                                    }
                                                )
                                            }
                                        },
                                        onEdit = {
                                            editingSnippet = snip
                                            showSnippetDialog = true
                                        },
                                        onDelete = {
                                            repository.updateSettings { curr ->
                                                curr.copy(snippets = curr.snippets.filterNot { it.id == snip.id })
                                            }
                                            Toast.makeText(
                                                context,
                                                AppStrings.pick(appLang, "已删除短语「${snip.title}」", "Deleted snippet \"${snip.title}\"", "已刪除短語「${snip.title}」"),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (pair.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }

                        if (searchQuery.isBlank() && filteredSnippets.size > 6) {
                            TextButton(
                                onClick = { expandedAll = !expandedAll },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text(
                                    text = if (expandedAll) {
                                        tr("收起列表", "Show Less", "收起清單")
                                    } else {
                                        tr(
                                            "展开全部 ${filteredSnippets.size} 条短语",
                                            "Show All ${filteredSnippets.size} Snippets",
                                            "展開全部 ${filteredSnippets.size} 條短語"
                                        )
                                    },
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSnippetDialog) {
        LocalizedSnippetEditorDialog(
            initialSnippet = editingSnippet,
            onDismiss = { showSnippetDialog = false },
            onSave = { saved ->
                repository.updateSettings { curr ->
                    val exists = curr.snippets.any { it.id == saved.id }
                    val updated = if (exists) {
                        curr.snippets.map { if (it.id == saved.id) saved else it }
                    } else {
                        listOf(saved) + curr.snippets
                    }
                    curr.copy(snippets = updated)
                }
                showSnippetDialog = false
                Toast.makeText(
                    context,
                    if (editingSnippet == null) {
                        AppStrings.pick(appLang, "已新建短语「${saved.title}」", "Created snippet \"${saved.title}\"", "已新增短語「${saved.title}」")
                    } else {
                        AppStrings.pick(appLang, "已更新短语「${saved.title}」", "Updated snippet \"${saved.title}\"", "已更新短語「${saved.title}」")
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@Composable
private fun LocalizedCompactSnippetTile(
    snippet: SnippetEntry,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val normCode = remember(snippet.flypyCode) {
        SnippetMatcher.normalizeFlypyCode(snippet.flypyCode)
    }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onEdit),
        shape = RoundedCornerShape(12.dp),
        color = if (snippet.enabled) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (snippet.enabled) {
                TypeFreeEmerald.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (snippet.enabled) {
                        TypeFreeEmerald.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                    }
                ) {
                    Text(
                        text = normCode.ifEmpty { tr("语音", "Voice", "語音") },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (snippet.enabled) TypeFreeEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = tr("删除短语", "Delete Snippet", "刪除短語"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(onClick = onDelete)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Checkbox(
                        checked = snippet.enabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                text = snippet.title.ifBlank { normCode.ifEmpty { tr("未命名短语", "Untitled Snippet", "未命名短語") } },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (snippet.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = snippet.content.replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LocalizedSnippetEditorDialog(
    initialSnippet: SnippetEntry?,
    onDismiss: () -> Unit,
    onSave: (SnippetEntry) -> Unit
) {
    var title by rememberSaveable { mutableStateOf(initialSnippet?.title.orEmpty()) }
    var voiceTriggers by rememberSaveable { mutableStateOf(initialSnippet?.voiceTriggers.orEmpty()) }
    var flypyCode by rememberSaveable {
        mutableStateOf(
            initialSnippet?.flypyCode?.let { SnippetMatcher.normalizeFlypyCode(it) }.orEmpty()
        )
    }
    var expandedContent by rememberSaveable { mutableStateOf(initialSnippet?.content.orEmpty()) }

    val normCode = SnippetMatcher.normalizeFlypyCode(flypyCode)
    val hasAnyTrigger = voiceTriggers.trim().isNotEmpty() || normCode.isNotEmpty()
    val canSave = expandedContent.trim().isNotEmpty() && hasAnyTrigger

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialSnippet == null) {
                    tr("新建 `/` 编码短语", "New '/' Slash Snippet", "新增 `/` 編碼短語")
                } else {
                    tr("编辑 `/` 编码短语", "Edit '/' Slash Snippet", "編輯 `/` 編碼短語")
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(tr("短语名称", "Snippet Title", "短語名稱")) },
                    placeholder = { Text(tr("如：工作邮箱、默认收货地址", "e.g. Work Email, Shipping Address", "如：工作信箱、預設收件地址")) }
                )

                OutlinedTextField(
                    value = voiceTriggers,
                    onValueChange = { voiceTriggers = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(tr("语音触发口令（逗号分隔，选填）", "Voice Triggers (comma-separated, optional)", "語音觸發口令（逗號分隔，選填）")) },
                    placeholder = { Text(tr("如：发我的邮箱, 我的工作邮箱", "e.g. send my email, my work email", "如：傳我的信箱, 我的工作信箱")) }
                )

                OutlinedTextField(
                    value = flypyCode,
                    onValueChange = { flypyCode = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(tr("编码快捷码（`/` 引导，选填）", "Slash Code (starts with '/', optional)", "編碼快捷碼（`/` 引導，選填）")) },
                    placeholder = { Text(tr("如：/yx、/dz、/sd", "e.g. /em, /addr, /sig", "如：/yx、/dz、/sd")) },
                    supportingText = if (normCode.isNotEmpty()) {
                        { Text(tr("将保存为：$normCode", "Saved as: $normCode", "將儲存為：$normCode")) }
                    } else null
                )

                OutlinedTextField(
                    value = expandedContent,
                    onValueChange = { expandedContent = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(tr("展开后的完整正文 *", "Expanded Full Text *", "展開後的完整正文 *")) },
                    placeholder = { Text(tr("输入命中后要直接上屏的完整内容…", "Enter the full text to insert when triggered…", "輸入命中後要直接上屏的完整內容…")) }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    val cleanTitle = title.trim().ifBlank {
                        normCode.ifBlank { voiceTriggers.trim().take(10) }
                    }
                    val base = initialSnippet ?: SnippetEntry(
                        title = cleanTitle,
                        voiceTriggers = "",
                        flypyCode = "",
                        content = ""
                    )
                    onSave(
                        base.copy(
                            title = cleanTitle,
                            voiceTriggers = voiceTriggers.trim(),
                            flypyCode = normCode,
                            content = expandedContent.trim(),
                            enabled = true
                        )
                    )
                }
            ) {
                Text(tr("保存并启用", "Save & Enable", "儲存並啟用"))
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
private fun KeyboardDictionaryPane(
    repository: AppRepository,
    settings: AppSettings,
    isolationKind: SchemeIsolationKind,
    activeProfile: SchemaProfile,
    rimeDeployState: RimeDeploymentState
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    val rimeDataManager = remember { TypeFreeApp.instance.rimeDataManager }
    val metadataVersion by rimeDataManager.metadataVersion.collectAsState()
    val flypyDict by TypeFreeApp.instance.flypyDictionary.collectAsState()
    val statsRev by repository.dailyStatsRevision.collectAsState()
    val allSnapshot = remember(settings, statsRev) {
        repository.computeInsightsSnapshot(StatsTimeRange.ALL)
    }

    val schemaIdForCount = activeProfile.schemaId.ifBlank { "flypy" }
    var customUserPhraseCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(schemaIdForCount, rimeDeployState, metadataVersion) {
        customUserPhraseCount = withContext(Dispatchers.IO) {
            runCatching {
                val editor = rimeDataManager.getUserPhraseEditor(schemaIdForCount)
                editor.listPhrases(isTopPinned = true).size + editor.listPhrases(isTopPinned = false).size
            }.getOrDefault(0)
        }
    }

    val customDictCount = allSnapshot.totalDictCount + customUserPhraseCount
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
                        } ?: throw IllegalArgumentException(
                            AppStrings.pick(appLang, "无法读取所选 ZIP 文件", "Cannot read selected ZIP file", "無法讀取所選 ZIP 檔案")
                        )
                    }
                    TypeFreeApp.instance.rimeInputEngine.setInputScheme(InputScheme.RIME_CUSTOM)
                    repository.updateSettings {
                        it.copy(
                            inputScheme = InputScheme.RIME_CUSTOM,
                            lastQwertyIsChinese = true,
                            flypyEnabled = true
                        )
                    }
                    val label = importResult.displayName.ifBlank {
                        AppStrings.pick(appLang, "自定义 Rime 方案", "Custom Rime Schema", "自訂 Rime 方案")
                    }
                    Toast.makeText(
                        context,
                        AppStrings.pick(appLang, "✓ 已导入并部署「$label」", "✓ Imported & deployed \"$label\"", "✓ 已匯入並部署「$label」"),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        AppStrings.pick(
                            appLang,
                            "导入 Rime 方案包失败: ${e.message ?: "格式错误"}",
                            "Failed to import Rime package: ${e.message ?: "Invalid format"}",
                            "匯入 Rime 方案包失敗: ${e.message ?: "格式錯誤"}"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
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
        // 轻量级端侧输入资产卡（自定词条 / 私人联想 / 短语省字 — 精简短标题防截断）
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
                        text = tr("本机输入资产", "On-Device Input Assets", "本機輸入資產"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = tr("端侧纯数字累计", "Local numeric totals", "端側純數字累計"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KeyboardAssetMetricTile(
                        label = tr("自定词条", "Custom Dict", "自訂詞條"),
                        value = SetupStatsHelper.formatCharCount(customDictCount, appLang),
                        unit = tr("条", "items", "條"),
                        modifier = Modifier.weight(1f)
                    )
                    KeyboardAssetMetricTile(
                        label = tr("私人联想", "L2 Phrases", "私人聯想"),
                        value = SetupStatsHelper.formatCharCount(personalPredictionCount, appLang),
                        unit = tr("条", "items", "條"),
                        modifier = Modifier.weight(1f)
                    )
                    KeyboardAssetMetricTile(
                        label = tr("短语省字", "Saved Chars", "短語省字"),
                        value = SetupStatsHelper.formatCharCount(allSnapshot.snippetSavedChars, appLang),
                        unit = tr("字", "chars", "字"),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (isolationKind != SchemeIsolationKind.PINYIN_OR_DOUBLE_PINYIN) {
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.KeyboardGrid,
                                contentDescription = null,
                                tint = TypeFreeEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = tr("码表词库与方案包", "Codetable & Schema Pack", "碼表詞庫與方案包"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = TypeFreeEmerald.copy(alpha = 0.14f)
                        ) {
                            val badgeLabel = if (isolationKind == SchemeIsolationKind.XIAOHE_YINXING) {
                                tr(
                                    "小鹤音形 (${flypyDict.codeCount} 编码)",
                                    "Flypy (${flypyDict.codeCount} codes)",
                                    "小鶴音形 (${flypyDict.codeCount} 編碼)"
                                )
                            } else {
                                val localizedName = formatCustomSchemaChipLabel(
                                    schemaId = activeProfile.schemaId,
                                    rawDisplayName = activeProfile.displayName,
                                    lang = appLang
                                )
                                "$localizedName (${activeProfile.schemaId})"
                            }
                            Text(
                                text = badgeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TypeFreeEmerald,
                                maxLines = 1,
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
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = tr("导入 Rime 方案包 (.zip)", "Import Rime (.zip)", "匯入 Rime 方案包 (.zip)"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { showRimeImportDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = tr("导入 TSV 码表", "Import TSV Table", "匯入 TSV 碼表"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (isCustomActive) {
                        TextButton(
                            onClick = { showRestoreConfirmDialog = true },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = tr("恢复内置默认开源词库", "Restore Built-in Open Dict", "恢復內建預設開源詞庫"),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            LocalizedCodetableAdvancedManagementCard(
                repository = repository,
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
                                text = tr("雾凇拼音词库管理 (rime-ice)", "Rime-Ice Pinyin Lexicon", "霧凇拼音詞庫管理 (rime-ice)"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = tr(
                                    "已内置中英混输与 8,200+ 本地高频联想；按需下载完整雾凇扩展词库与腾讯词向量",
                                    "Includes mixed CN/EN typing & 8,200+ local collocations; download full Rime-Ice & Tencent lexicons on demand",
                                    "已內建中英混輸與 8,200+ 本機高頻聯想；按需下載完整霧凇擴充詞庫與騰訊詞向量"
                                ),
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
                            is RimeDeploymentState.Idle -> tr("Rime 引擎：待部署", "Rime Engine: Idle", "Rime 引擎：待部署")
                            is RimeDeploymentState.Deploying -> if (appLang == AppLang.EN) "Deploying Rime lexicon…" else st.message
                            is RimeDeploymentState.Ready -> {
                                val parts = buildList {
                                    add(
                                        when {
                                            st.hasFullRimeIce && st.hasExtRimeIce ->
                                                tr("完整雾凇 (base+ext+others)", "Full Rime-Ice (base+ext+others)", "完整霧凇 (base+ext+others)")
                                            st.hasFullRimeIce ->
                                                tr("雾凇基础完整版", "Rime-Ice Base Full", "霧凇基礎完整版")
                                            else ->
                                                tr("内置精简词库", "Built-in Compact Lexicon", "內建精簡詞庫")
                                        }
                                    )
                                    if (st.hasTencentRimeIce) add(tr("腾讯词库", "Tencent Corpus", "騰訊詞庫"))
                                    if (!st.activeGrammarModelId.isNullOrBlank()) {
                                        val gPreset = RimeDataManager.GRAMMAR_MODEL_PRESETS
                                            .firstOrNull { it.id == st.activeGrammarModelId }
                                        val gName = if (gPreset != null) {
                                            localizedGrammarPresetName(gPreset, appLang)
                                        } else {
                                            st.activeGrammarModelId
                                        }
                                        add("${tr("语法", "Grammar", "語法")}:$gName")
                                    }
                                }
                                tr(
                                    "Rime 就绪：${parts.joinToString(" · ")} (${st.wordCount} 词条)",
                                    "Rime Ready: ${parts.joinToString(" · ")} (${st.wordCount} entries)",
                                    "Rime 就緒：${parts.joinToString(" · ")} (${st.wordCount} 詞條)"
                                )
                            }
                            is RimeDeploymentState.Error -> tr("Rime 状态：${st.message}", "Rime Error: ${st.message}", "Rime 狀態：${st.message}")
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
                                    Toast.makeText(
                                        context,
                                        AppStrings.pick(
                                            appLang,
                                            "✓ 完整雾凇扩展词库已部署 ($count 词条)",
                                            "✓ Full Rime-Ice lexicon deployed ($count entries)",
                                            "✓ 完整霧凇擴充詞庫已部署 ($count 詞條)"
                                        ),
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        AppStrings.pick(
                                            appLang,
                                            "雾凇词库下载失败: ${e.message ?: "请检查网络"}",
                                            "Rime-Ice download failed: ${e.message ?: "Check network"}",
                                            "霧凇詞庫下載失敗: ${e.message ?: "請檢查網路"}"
                                        ),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        enabled = !isDeployingRime,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (hasFullIce && hasExtIce) {
                                tr(
                                    "更新完整雾凇扩展词库 (base + ext + others · ~16 MB)",
                                    "Update Full Rime-Ice Lexicon (~16 MB)",
                                    "更新完整霧凇擴充詞庫 (base + ext + others · ~16 MB)"
                                )
                            } else {
                                tr(
                                    "下载完整雾凇扩展词库 (base + ext + others · ~16 MB)",
                                    "Download Full Rime-Ice Lexicon (~16 MB)",
                                    "下載完整霧凇擴充詞庫 (base + ext + others · ~16 MB)"
                                )
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
                                        Toast.makeText(
                                            context,
                                            AppStrings.pick(appLang, "已卸载腾讯词向量扩展库", "Removed Tencent corpus extension", "已解除安裝騰訊詞向量擴充庫"),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        val ready = rimeDataManager.downloadTencentRimeIceDictionary()
                                        val count = (ready as? RimeDeploymentState.Ready)?.wordCount ?: 0
                                        Toast.makeText(
                                            context,
                                            AppStrings.pick(appLang, "✓ 腾讯词向量扩展库已部署 ($count 词条)", "✓ Tencent corpus deployed ($count entries)", "✓ 騰訊詞向量擴充庫已部署 ($count 詞條)"),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        AppStrings.pick(appLang, "腾讯词库操作失败: ${e.message ?: "请检查网络"}", "Tencent corpus failed: ${e.message ?: "Check network"}", "騰訊詞庫操作失敗: ${e.message ?: "請檢查網路"}"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        enabled = !isDeployingRime,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (hasTencentIce) {
                                tr(
                                    "已安装腾讯词向量扩展库 · 点击卸载",
                                    "Tencent Corpus Installed · Tap to Remove",
                                    "已安裝騰訊詞向量擴充庫 · 點擊解除安裝"
                                )
                            } else {
                                tr(
                                    "可选追加：腾讯词向量扩展库 (tencent · ~17 MB，含海量口语词)",
                                    "Optional Add-on: Tencent Colloquial Corpus (~17 MB)",
                                    "可選追加：騰訊詞向量擴充庫 (tencent · ~17 MB，含海量口語詞)"
                                )
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
                            Text(
                                text = tr("导入 Rime 方案包 (.zip)", "Import Rime (.zip)", "匯入 Rime 方案包 (.zip)"),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { rimeDataManager.restoreDefaultRimeData(settings.inputScheme) }
                                        .onSuccess {
                                            Toast.makeText(
                                                context,
                                                AppStrings.pick(appLang, "已恢复默认雾凇词库", "Restored default Rime-Ice lexicon", "已恢復預設霧凇詞庫"),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        .onFailure {
                                            Toast.makeText(
                                                context,
                                                AppStrings.pick(appLang, "恢复失败: ${it.message}", "Restore failed: ${it.message}", "恢復失敗: ${it.message}"),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                }
                            },
                            enabled = !isDeployingRime,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(tr("恢复默认", "Reset", "恢復預設"), fontSize = 12.sp)
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
                                    text = tr("八元组语法模型 (librime-octagram)", "Octagram Language Model", "八元組語法模型 (librime-octagram)"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = tr(
                                        "离线大幅提升全拼 / 双拼 / 九键长句连打选词准确率（的/地/得、在/再、同音歧义）",
                                        "Boosts offline sentence disambiguation & homophone accuracy for Full Pinyin, Shuangpin & T9",
                                        "離線大幅提升全拼 / 雙拼 / 九鍵長句連打選詞準確率（的/地/得、在/再、同音歧義）"
                                    ),
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
                                        text = tr("已启用", "Active", "已啟用"),
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
                            val localizedName = localizedGrammarPresetName(preset, appLang)
                            val localizedSub = localizedGrammarPresetSubtitle(preset, appLang)
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
                                                text = localizedName,
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
                                            text = localizedSub,
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
                                                            Toast.makeText(
                                                                context,
                                                                AppStrings.pick(appLang, "已停用并删除语法模型", "Removed grammar model", "已停用並刪除語法模型"),
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                        .onFailure {
                                                            Toast.makeText(
                                                                context,
                                                                AppStrings.pick(appLang, "停用失败: ${it.message}", "Failed: ${it.message}", "停用失敗: ${it.message}"),
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                }
                                            },
                                            enabled = !isDeployingRime,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text(tr("卸载", "Remove", "解除安裝"), fontSize = 12.sp)
                                        }
                                    } else {
                                        FilledTonalButton(
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        rimeDataManager.downloadGrammarModel(preset)
                                                        Toast.makeText(
                                                            context,
                                                            AppStrings.pick(appLang, "✓ 已启用「$localizedName」", "✓ Enabled \"$localizedName\"", "✓ 已啟用「$localizedName」"),
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    } catch (e: Exception) {
                                                        Toast.makeText(
                                                            context,
                                                            AppStrings.pick(appLang, "语法模型下载失败: ${e.message ?: "请检查网络"}", "Download failed: ${e.message ?: "Check network"}", "語法模型下載失敗: ${e.message ?: "請檢查網路"}"),
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                }
                                            },
                                            enabled = !isDeployingRime,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text(tr("安装", "Install", "安裝"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
            }

            LocalizedBuiltInPresetSchemasCard(
                repository = repository,
                rimeDeployState = rimeDeployState,
                defaultExpanded = true
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
    }

    if (showRimeImportDialog) {
        LocalizedRimeDictionaryImportDialog(onDismiss = { showRimeImportDialog = false })
    }

    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = { Text(tr("恢复内置开源词库", "Restore Built-in Dictionary", "恢復內建開源詞庫")) },
            text = {
                Text(
                    tr(
                        "确定要清除当前自定义私有码表并恢复默认内置开源词库吗？",
                        "Clear the custom TSV codetable and restore the default built-in open dictionary?",
                        "確定要清除目前自訂私有碼表並恢復預設內建開源詞庫嗎？"
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    FlypyDictionary.restoreDefaultDictionary(context)
                                    TypeFreeApp.instance.reloadFlypyDictionary()
                                }
                                Toast.makeText(
                                    context,
                                    AppStrings.pick(appLang, "已恢复内置默认开源词库", "Restored built-in open dictionary", "已恢復內建預設開源詞庫"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    AppStrings.pick(appLang, "恢复失败: ${e.message}", "Restore failed: ${e.message}", "恢復失敗: ${e.message}"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            showRestoreConfirmDialog = false
                        }
                    }
                ) { Text(tr("确认恢复", "Restore", "確認恢復")) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmDialog = false }) {
                    Text(tr("取消", "Cancel", "取消"))
                }
            }
        )
    }
}

private fun localizedGrammarPresetName(preset: GrammarModelPreset, lang: AppLang): String {
    return when (preset.id) {
        "zh-hans-t-essay-bgc-compact" -> AppStrings.pick(lang, preset.displayName, "Essay Compact", "八股文輕量模型")
        "zh-hans-t-essay-bgw-compact" -> AppStrings.pick(lang, preset.displayName, "Essay Standard", "八股文標準模型")
        "wanxiang-lts-zh-hans" -> AppStrings.pick(lang, preset.displayName, "Wanxiang LTS Flagship", "萬象 LTS 旗艦模型")
        else -> preset.displayName
    }
}

private fun localizedGrammarPresetSubtitle(preset: GrammarModelPreset, lang: AppLang): String {
    return when (preset.id) {
        "zh-hans-t-essay-bgc-compact" -> AppStrings.pick(
            lang,
            preset.subtitle,
            "Official compact model · Fast download & ultra-low RAM footprint",
            "Rime 官方八股文精簡版 · 下載快、記憶體極省，適合先體驗長句糾偏"
        )
        "zh-hans-t-essay-bgw-compact" -> AppStrings.pick(
            lang,
            preset.subtitle,
            "Official balanced model · Recommended for daily Pinyin & Shuangpin",
            "Rime 官方八股文均衡版 · 覆蓋更廣，日常全拼/雙拼均衡推薦"
        )
        "wanxiang-lts-zh-hans" -> AppStrings.pick(
            lang,
            preset.subtitle,
            "Modern community octagram corpus · Highest sentence accuracy (Wi-Fi recommended)",
            "社群熱門現代語料八元組模型 · 最強整句準確率（建議 Wi-Fi 下載，mmap 按需載入）"
        )
        else -> preset.subtitle
    }
}

private fun localizedPresetInfo(
    preset: PresetSchemaCatalog.PresetSchemaInfo,
    lang: AppLang
): Triple<String, String, String> {
    return when (preset.id) {
        "xkjd6" -> Triple(
            AppStrings.pick(lang, preset.displayName, "XKJD 6 (KeyTao)", "星空鍵道 6"),
            AppStrings.pick(lang, preset.archetypeLabel, "Push-Commit (PUSH_TOPUP)", "頂功碼表 (PUSH_TOPUP_CODE)"),
            AppStrings.pick(
                lang,
                preset.description,
                "Open-source sound-shape push-commit scheme (xkinput/KeyTao) with native librime-lua top-up processor.",
                "開源音形頂功方案（xkinput/KeyTao），聲韻雙拼 + a/e/i/o/u/v 頂功筆畫分離，內建原生頂屏。"
            )
        )
        "wubi86" -> Triple(
            AppStrings.pick(lang, preset.displayName, "Wubi 86", "五筆 86"),
            AppStrings.pick(lang, preset.archetypeLabel, "4-Code Shape (FIXED_SHAPECODE)", "純形定長 (FIXED_SHAPECODE)"),
            AppStrings.pick(
                lang,
                preset.description,
                "Classic 86 Wubi shape-code table (rime/rime-wubi) with strict 4-code length and 5th-key auto-commit.",
                "經典 86 版五筆字型碼表（rime/rime-wubi），四碼定長空碼不亂切分，第 5 碼自動頂首選上屏。"
            )
        )
        "tigress" -> Triple(
            AppStrings.pick(lang, preset.displayName, "Tiger Code (Words & Phrases)", "虎碼官方字詞"),
            AppStrings.pick(lang, preset.archetypeLabel, "4-Code Shape (FIXED_SHAPECODE)", "純形定長 (FIXED_SHAPECODE)"),
            AppStrings.pick(
                lang,
                preset.description,
                "Official Tiger Code phrase edition (117k chars + 130k words), unique 4-code auto-commit & 5th-key push.",
                "虎碼官方字詞版（11.7 萬單字 + 13 萬全碼詞 + 3 千簡詞），四碼唯一自動上屏、五碼頂。"
            )
        )
        "tiger" -> Triple(
            AppStrings.pick(lang, preset.displayName, "Tiger Code (Single-Char)", "虎碼官方單字"),
            AppStrings.pick(lang, preset.archetypeLabel, "4-Code Shape (FIXED_SHAPECODE)", "純形定長 (FIXED_SHAPECODE)"),
            AppStrings.pick(
                lang,
                preset.description,
                "Official Tiger Code single-character edition (117k chars, zero phrase collisions), ideal for char-by-char masters.",
                "虎碼官方單字全集版（11.7 萬單字，零詞組干擾），四碼唯一自動上屏、五碼頂，適合單字流。"
            )
        )
        else -> Triple(preset.displayName, preset.archetypeLabel, preset.description)
    }
}

@Composable
private fun LocalizedCodetableAdvancedManagementCard(
    repository: AppRepository,
    activeProfile: SchemaProfile,
    rimeDeployState: RimeDeploymentState,
    defaultExpanded: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val rimeDataManager = remember { TypeFreeApp.instance.rimeDataManager }
    val metadataVersion by rimeDataManager.metadataVersion.collectAsState()
    val schemaId = activeProfile.schemaId.ifBlank { "flypy" }
    val isDeploying = rimeDeployState is RimeDeploymentState.Deploying

    var cardExpanded by rememberSaveable(defaultExpanded) { mutableStateOf(defaultExpanded) }
    var feedbackMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var presetsExpanded by rememberSaveable { mutableStateOf(true) }
    var phrasesExpanded by rememberSaveable { mutableStateOf(true) }
    var subDictsExpanded by rememberSaveable { mutableStateOf(false) }
    var shadowExpanded by rememberSaveable { mutableStateOf(false) }

    val profileLabel = formatCustomSchemaChipLabel(
        schemaId = schemaId,
        rawDisplayName = activeProfile.displayName,
        lang = appLang
    )

    Card(
        modifier = modifier.fillMaxWidth(),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { cardExpanded = !cardExpanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = tr("码表进阶管理", "Advanced Codetable", "碼表進階管理"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            LocalizedCodetableBadgePill(
                                text = "$profileLabel ($schemaId)",
                                highlight = true
                            )
                        }
                        Text(
                            text = tr(
                                "方案一键安装、置顶/自造词、子词库与阴影规则",
                                "One-tap preset schemas, pinned/user phrases, sub-dicts & pin/block rules",
                                "方案一鍵安裝、置頂/自造詞、子詞庫與陰影規則"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (cardExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = tr("展开或收起", "Toggle", "展開或收起"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            feedbackMessage?.let { (isSuccess, text) ->
                LocalizedFeedbackBanner(
                    isSuccess = isSuccess,
                    text = text,
                    onDismiss = { feedbackMessage = null }
                )
            }

            AnimatedVisibility(visible = cardExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    LocalizedExpandableSubsection(
                        title = tr("内置开源码表方案一键安装", "Built-in Open Schemas", "內建開源碼表方案一鍵安裝"),
                        badge = tr(
                            "${PresetSchemaCatalog.BUILT_IN_PRESETS.size} 套",
                            "${PresetSchemaCatalog.BUILT_IN_PRESETS.size} packs",
                            "${PresetSchemaCatalog.BUILT_IN_PRESETS.size} 套"
                        ),
                        expanded = presetsExpanded,
                        onToggle = { presetsExpanded = !presetsExpanded }
                    ) {
                        LocalizedPresetSchemaInstallSection(
                            context = context,
                            rimeDataManager = rimeDataManager,
                            repository = repository,
                            isBusy = isDeploying,
                            onFeedback = { ok, msg -> feedbackMessage = ok to msg }
                        )
                    }

                    LocalizedExpandableSubsection(
                        title = tr("自造词与置顶词管理 (_top / _user)", "Pinned & User Phrases (_top / _user)", "自造詞與置頂詞管理 (_top / _user)"),
                        badge = schemaId,
                        expanded = phrasesExpanded,
                        onToggle = { phrasesExpanded = !phrasesExpanded }
                    ) {
                        LocalizedUserPhraseEditorSection(
                            context = context,
                            rimeDataManager = rimeDataManager,
                            schemaId = schemaId,
                            deployState = rimeDeployState,
                            metadataVersion = metadataVersion,
                            isBusy = isDeploying,
                            onFeedback = { ok, msg -> feedbackMessage = ok to msg }
                        )
                    }

                    LocalizedExpandableSubsection(
                        title = tr("分类子词库与扩展词库 (ext/)", "Sub-Dictionaries & Extensions (ext/)", "分類子詞庫與擴充詞庫 (ext/)"),
                        badge = "Layer +1",
                        expanded = subDictsExpanded,
                        onToggle = { subDictsExpanded = !subDictsExpanded }
                    ) {
                        LocalizedSubDictManagerSection(
                            context = context,
                            rimeDataManager = rimeDataManager,
                            schemaId = schemaId,
                            deployState = rimeDeployState,
                            metadataVersion = metadataVersion,
                            isBusy = isDeploying,
                            onFeedback = { ok, msg -> feedbackMessage = ok to msg }
                        )
                    }

                    LocalizedExpandableSubsection(
                        title = tr("明暗调频 / 屏蔽规则 (shadow.yaml)", "Pin & Block Rules (shadow.yaml)", "置頂 / 封鎖規則 (shadow.yaml)"),
                        badge = tr("置顶/屏蔽", "Pin/Block", "置頂/封鎖"),
                        expanded = shadowExpanded,
                        onToggle = { shadowExpanded = !shadowExpanded }
                    ) {
                        LocalizedShadowRulesSection(
                            context = context,
                            rimeDataManager = rimeDataManager,
                            schemaId = schemaId,
                            deployState = rimeDeployState,
                            metadataVersion = metadataVersion,
                            onFeedback = { ok, msg -> feedbackMessage = ok to msg }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalizedBuiltInPresetSchemasCard(
    repository: AppRepository,
    rimeDeployState: RimeDeploymentState,
    defaultExpanded: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val rimeDataManager = remember { TypeFreeApp.instance.rimeDataManager }
    val isDeploying = rimeDeployState is RimeDeploymentState.Deploying

    var cardExpanded by rememberSaveable(defaultExpanded) { mutableStateOf(defaultExpanded) }
    var feedbackMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { cardExpanded = !cardExpanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.KeyboardGrid,
                        contentDescription = null,
                        tint = TypeFreeEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = tr("内置开源码表方案一键安装", "Built-in Open Codetables", "內建開源碼表方案一鍵安裝"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            LocalizedCodetableBadgePill(
                                text = tr(
                                    "${PresetSchemaCatalog.BUILT_IN_PRESETS.size} 套",
                                    "${PresetSchemaCatalog.BUILT_IN_PRESETS.size} packs",
                                    "${PresetSchemaCatalog.BUILT_IN_PRESETS.size} 套"
                                ),
                                highlight = true
                            )
                        }
                        Text(
                            text = tr(
                                "虎码 / 五笔 86 / 星空键道 6，安装后自动切换启用",
                                "One-tap install for Tiger Code, Wubi 86 & XKJD 6; activates automatically",
                                "虎碼 / 五筆 86 / 星空鍵道 6，安裝後自動切換啟用"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (cardExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = tr("展开或收起", "Toggle", "展開或收起"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            feedbackMessage?.let { (isSuccess, text) ->
                LocalizedFeedbackBanner(
                    isSuccess = isSuccess,
                    text = text,
                    onDismiss = { feedbackMessage = null }
                )
            }

            AnimatedVisibility(visible = cardExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    LocalizedPresetSchemaInstallSection(
                        context = context,
                        rimeDataManager = rimeDataManager,
                        repository = repository,
                        isBusy = isDeploying,
                        onFeedback = { ok, msg -> feedbackMessage = ok to msg }
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalizedFeedbackBanner(
    isSuccess: Boolean,
    text: String,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (isSuccess) {
            TypeFreeEmerald.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        },
        border = BorderStroke(
            0.8.dp,
            if (isSuccess) TypeFreeEmerald.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (isSuccess) TypeFreeEmerald else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = tr("关闭提示", "Dismiss", "關閉提示"),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun LocalizedExpandableSubsection(
    title: String,
    badge: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggle)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    LocalizedCodetableBadgePill(text = badge, highlight = false)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = tr("展开或收起", "Toggle", "展開或收起"),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalizedPresetSchemaInstallSection(
    context: Context,
    rimeDataManager: RimeDataManager,
    repository: AppRepository,
    isBusy: Boolean,
    onFeedback: (Boolean, String) -> Unit
) {
    val appLang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    val metadataVersion by rimeDataManager.metadataVersion.collectAsState()
    val installedIds = remember(metadataVersion) {
        rimeDataManager.listInstalledCustomSchemas().map { it.schemaId }.toSet()
    }
    var installingId by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PresetSchemaCatalog.BUILT_IN_PRESETS.forEach { preset ->
            val isInstallingThis = installingId == preset.id
            val isInstalled = preset.id in installedIds
            val (localizedTitle, localizedArchetype, localizedDesc) = localizedPresetInfo(preset, appLang)
            val localizedLicense = if (preset.license.contains("官方码表")) {
                AppStrings.pick(appLang, preset.license, "Official Table (2026.08)", "官方碼表 (2026.08.15)")
            } else {
                preset.license
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
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
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = localizedTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isInstalled) {
                                    LocalizedCodetableBadgePill(
                                        text = tr("已安装", "Installed", "已安裝"),
                                        highlight = true
                                    )
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                LocalizedCodetableBadgePill(text = localizedLicense, highlight = false)
                                LocalizedCodetableBadgePill(text = localizedArchetype, highlight = true)
                            }
                        }

                        FilledTonalButton(
                            onClick = {
                                installingId = preset.id
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            rimeDataManager.installPresetSchema(context, preset.assetPath)
                                        }
                                        TypeFreeApp.instance.rimeInputEngine.setInputScheme(InputScheme.RIME_CUSTOM)
                                        repository.updateSettings {
                                            it.copy(
                                                inputScheme = InputScheme.RIME_CUSTOM,
                                                lastQwertyIsChinese = true,
                                                flypyEnabled = true
                                            )
                                        }
                                        val okMsg = AppStrings.pick(
                                            appLang,
                                            "✓ 已安装并切换至「$localizedTitle」",
                                            "✓ Installed & activated \"$localizedTitle\"",
                                            "✓ 已安裝並切換至「$localizedTitle」"
                                        )
                                        onFeedback(true, okMsg)
                                        Toast.makeText(context, okMsg, Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        val errMsg = AppStrings.pick(
                                            appLang,
                                            "安装「$localizedTitle」失败: ${e.message ?: "未知错误"}",
                                            "Failed to install \"$localizedTitle\": ${e.message ?: "Unknown error"}",
                                            "安裝「$localizedTitle」失敗: ${e.message ?: "未知錯誤"}"
                                        )
                                        onFeedback(false, errMsg)
                                        Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
                                    } finally {
                                        installingId = null
                                    }
                                }
                            },
                            enabled = !isBusy && installingId == null,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (isInstallingThis) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("安装中", "Installing", "安裝中"), style = MaterialTheme.typography.labelMedium)
                            } else {
                                Text(
                                    text = if (isInstalled) {
                                        tr("重新安装", "Reinstall", "重新安裝")
                                    } else {
                                        tr("一键安装", "Install", "一鍵安裝")
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Text(
                        text = localizedDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalizedUserPhraseEditorSection(
    context: Context,
    rimeDataManager: RimeDataManager,
    schemaId: String,
    deployState: RimeDeploymentState,
    metadataVersion: Long,
    isBusy: Boolean,
    onFeedback: (Boolean, String) -> Unit
) {
    val appLang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val isTopPinned = selectedTab == 0

    var showAddPhraseDialog by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<CustomUserPhraseFileEditor.UserPhraseEntry>>(emptyList()) }
    var isReloading by remember { mutableStateOf(false) }
    val isXiaoheSchema = schemaId == "flypy"
    var fallbackReverseMap by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    val effectiveReverseMap = remember(isXiaoheSchema, rimeDataManager.activeReverseLookupMap, fallbackReverseMap, metadataVersion) {
        if (!isXiaoheSchema) {
            emptyMap()
        } else {
            rimeDataManager.activeReverseLookupMap.ifEmpty { fallbackReverseMap }
        }
    }

    LaunchedEffect(schemaId, metadataVersion) {
        if (isXiaoheSchema && rimeDataManager.activeReverseLookupMap.isEmpty() && fallbackReverseMap.isEmpty()) {
            fallbackReverseMap = withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open("flypy_dz.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                        DirectCommandEngine.loadReverseLookupFromDictLines(lines)
                    }
                }.getOrDefault(emptyMap())
            }
        }
    }

    fun reloadList() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    rimeDataManager.getUserPhraseEditor(schemaId).listPhrases(isTopPinned)
                }.getOrDefault(emptyList())
            }
            entries = loaded
        }
    }

    LaunchedEffect(schemaId, isTopPinned, deployState, metadataVersion) {
        reloadList()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LocalizedCodetableSegmentTab(
                    title = tr("置顶词 (${schemaId}_top.txt)", "Pinned (_top.txt)", "置頂詞 (${schemaId}_top.txt)"),
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1f)
                )
                LocalizedCodetableSegmentTab(
                    title = tr("自造词 (${schemaId}_user.txt)", "User (_user.txt)", "自造詞 (${schemaId}_user.txt)"),
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Button(
            onClick = { showAddPhraseDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = when {
                    isTopPinned && isXiaoheSchema -> tr(
                        "添加置顶词条（支持自动算小鹤 4 码）",
                        "Add Pinned Phrase (Auto Flypy 4-Code)",
                        "新增置頂詞條（支援自動算小鶴 4 碼）"
                    )
                    isTopPinned -> tr("添加置顶词条", "Add Pinned Phrase", "新增置頂詞條")
                    isXiaoheSchema -> tr(
                        "添加自造词条（支持自动算小鹤 4 码）",
                        "Add User Phrase (Auto Flypy 4-Code)",
                        "新增自造詞條（支援自動算小鶴 4 碼）"
                    )
                    else -> tr("添加自造词条", "Add User Phrase", "新增自造詞條")
                },
                fontWeight = FontWeight.Bold
            )
        }

        if (entries.isEmpty()) {
            Text(
                text = if (isTopPinned) {
                    tr("暂无置顶词记录", "No pinned phrases yet", "尚無置頂詞記錄")
                } else {
                    tr("暂无自造词记录", "No user phrases yet", "尚無自造詞記錄")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entries.forEach { item ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LocalizedCodetableBadgePill(text = item.code, highlight = true, monospace = true)
                                Text(
                                    text = item.word,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val removed = withContext(Dispatchers.IO) {
                                            rimeDataManager.getUserPhraseEditor(schemaId)
                                                .removePhrase(item.word, item.code, item.isTopPinned)
                                        }
                                        if (removed) {
                                            reloadList()
                                            onFeedback(
                                                true,
                                                AppStrings.pick(
                                                    appLang,
                                                    "已删除「${item.word}」，重新部署后生效",
                                                    "Removed \"${item.word}\" · Redeploy to apply",
                                                    "已刪除「${item.word}」，重新部署後生效"
                                                )
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = tr("删除词条", "Delete", "刪除詞條"),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                isReloading = true
                scope.launch {
                    try {
                        val resultState = withContext(Dispatchers.IO) {
                            rimeDataManager.reloadActiveEnhancedSchema(schemaId)
                        }
                        if (resultState is RimeDeploymentState.Error) {
                            onFeedback(
                                false,
                                AppStrings.pick(appLang, "重新部署失败: ${resultState.message}", "Redeploy failed: ${resultState.message}", "重新部署失敗: ${resultState.message}")
                            )
                        } else {
                            onFeedback(
                                true,
                                AppStrings.pick(appLang, "✓ 已重新部署「$schemaId」", "✓ Redeployed \"$schemaId\"", "✓ 已重新部署「$schemaId」")
                            )
                            Toast.makeText(
                                context,
                                AppStrings.pick(appLang, "✓ 词库已重新部署生效", "✓ Lexicon redeployed", "✓ 詞庫已重新部署生效"),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        onFeedback(
                            false,
                            AppStrings.pick(appLang, "重新部署失败: ${e.message ?: "未知错误"}", "Redeploy failed: ${e.message ?: "Error"}", "重新部署失敗: ${e.message ?: "未知錯誤"}")
                        )
                    } finally {
                        isReloading = false
                    }
                }
            },
            enabled = !isBusy && !isReloading,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            if (isReloading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(tr("正在部署...", "Deploying...", "正在部署..."))
            } else {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(tr("重新部署生效", "Redeploy to Apply", "重新部署生效"), fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showAddPhraseDialog) {
        LocalizedAddUserPhraseDialog(
            schemaId = schemaId,
            initialIsTopPinned = isTopPinned,
            reverseLookupMap = effectiveReverseMap,
            onDismiss = { showAddPhraseDialog = false },
            onConfirmAdd = { cleanWord, cleanCode, targetTopPinned ->
                scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        runCatching {
                            rimeDataManager.getUserPhraseEditor(schemaId)
                                .addPhrase(cleanWord, cleanCode, targetTopPinned)
                        }.exceptionOrNull()
                    }
                    if (err != null) {
                        onFeedback(
                            false,
                            AppStrings.pick(appLang, "添加失败: ${err.message ?: "输入不合法"}", "Failed to add: ${err.message ?: "Invalid input"}", "新增失敗: ${err.message ?: "輸入不合法"}")
                        )
                    } else {
                        selectedTab = if (targetTopPinned) 0 else 1
                        showAddPhraseDialog = false
                        reloadList()
                        onFeedback(
                            true,
                            AppStrings.pick(
                                appLang,
                                "✓ 已写入「$cleanWord ($cleanCode)」，点击重新部署生效",
                                "✓ Saved \"$cleanWord ($cleanCode)\" · Tap Redeploy to apply",
                                "✓ 已寫入「$cleanWord ($cleanCode)」，點擊重新部署生效"
                            )
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun LocalizedAddUserPhraseDialog(
    schemaId: String,
    initialIsTopPinned: Boolean,
    reverseLookupMap: Map<String, List<String>>,
    onDismiss: () -> Unit,
    onConfirmAdd: (word: String, code: String, isTopPinned: Boolean) -> Unit
) {
    var wordInput by rememberSaveable { mutableStateOf("") }
    var codeInput by rememberSaveable { mutableStateOf("") }
    var codeManuallyEdited by rememberSaveable { mutableStateOf(false) }
    var targetTopPinned by rememberSaveable { mutableStateOf(initialIsTopPinned) }

    val isXiaoheSchema = schemaId == "flypy"
    val derivedCode = remember(isXiaoheSchema, wordInput, reverseLookupMap) {
        if (isXiaoheSchema) XiaohePhraseCodeHelper.deriveXiaohe4Code(wordInput, reverseLookupMap) else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("添加个人词条 ($schemaId)", "Add Phrase ($schemaId)", "新增個人詞條 ($schemaId)")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LocalizedCodetableSegmentTab(
                        title = tr("置顶词 (_top.txt)", "Pinned (_top)", "置頂詞 (_top.txt)"),
                        selected = targetTopPinned,
                        onClick = { targetTopPinned = true },
                        modifier = Modifier.weight(1f)
                    )
                    LocalizedCodetableSegmentTab(
                        title = tr("自造词 (_user.txt)", "User (_user)", "自造詞 (_user.txt)"),
                        selected = !targetTopPinned,
                        onClick = { targetTopPinned = false },
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = wordInput,
                    onValueChange = { next ->
                        wordInput = next
                        if (isXiaoheSchema && !codeManuallyEdited) {
                            val auto = XiaohePhraseCodeHelper.deriveXiaohe4Code(next, reverseLookupMap)
                            if (auto != null) {
                                codeInput = auto
                            } else if (next.trim().length < 2) {
                                codeInput = ""
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    label = { Text(tr("词条 *", "Word / Phrase *", "詞條 *")) },
                    placeholder = {
                        Text(
                            if (isXiaoheSchema) {
                                tr("输入 2~4+ 汉字，如：双拼输入", "2–4+ Chinese chars, e.g. 双拼输入", "輸入 2~4+ 漢字，如：雙拼輸入")
                            } else {
                                tr("输入要添加的词条", "Enter word or phrase", "輸入要新增的詞條")
                            }
                        )
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = {
                            codeInput = it
                            codeManuallyEdited = true
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        label = { Text(tr("编码 *", "Code *", "編碼 *")) },
                        placeholder = {
                            Text(
                                if (isXiaoheSchema) {
                                    tr("如：uupb", "e.g. uupb", "如：uupb")
                                } else {
                                    tr("输入当前方案对应的编码", "Enter code for this schema", "輸入目前方案對應的編碼")
                                }
                            )
                        }
                    )
                    if (isXiaoheSchema) {
                        FilledTonalButton(
                            onClick = {
                                if (derivedCode != null) {
                                    codeInput = derivedCode
                                    codeManuallyEdited = false
                                }
                            },
                            enabled = derivedCode != null,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 14.dp)
                        ) {
                            Text(
                                text = tr("自动算码", "Auto 4-Code", "自動算碼"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (isXiaoheSchema) {
                    if (derivedCode != null) {
                        Text(
                            text = tr("按单字反查表推算小鹤 4 码：$derivedCode", "Derived Flypy 4-code: $derivedCode", "按單字反查表推算小鶴 4 碼：$derivedCode"),
                            style = MaterialTheme.typography.labelSmall,
                            color = TypeFreeEmerald,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (wordInput.trim().isNotEmpty()) {
                        Text(
                            text = tr(
                                "输入 2~4+ 个纯汉字可自动推导小鹤 4 码，也可手动填写自定义编码",
                                "Enter 2+ Chinese chars to auto-derive Flypy 4-code, or type any custom code",
                                "輸入 2~4+ 個純漢字可自動推導小鶴 4 碼，也可手動填寫自訂編碼"
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = wordInput.isNotBlank() && codeInput.isNotBlank(),
                onClick = {
                    onConfirmAdd(wordInput.trim(), codeInput.trim(), targetTopPinned)
                }
            ) {
                Text(tr("写入词库", "Save Phrase", "寫入詞庫"))
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
private fun LocalizedSubDictManagerSection(
    context: Context,
    rimeDataManager: RimeDataManager,
    schemaId: String,
    deployState: RimeDeploymentState,
    metadataVersion: Long,
    isBusy: Boolean,
    onFeedback: (Boolean, String) -> Unit
) {
    val appLang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var subDicts by remember { mutableStateOf<List<SubDictToggleManager.SubDictDescriptor>>(emptyList()) }
    var isImportingExt by remember { mutableStateOf(false) }
    var isReloading by remember { mutableStateOf(false) }

    fun refreshSubDicts() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    rimeDataManager.getSubDictToggleManager().listAvailableSubDicts(schemaId)
                }.getOrDefault(emptyList())
            }
            subDicts = loaded
        }
    }

    LaunchedEffect(schemaId, deployState, metadataVersion) {
        refreshSubDicts()
    }

    val extDictPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isImportingExt = true
            scope.launch {
                try {
                    val fileName = resolveStudioDocName(context, uri) ?: "custom_ext.tsv"
                    val descriptor = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            rimeDataManager.importExtSubDictionary(fileName, stream, schemaId)
                        } ?: throw IllegalArgumentException(
                            AppStrings.pick(appLang, "无法读取所选词库文件", "Cannot read selected file", "無法讀取所選詞庫檔案")
                        )
                    }
                    refreshSubDicts()
                    val okMsg = AppStrings.pick(
                        appLang,
                        "✓ 已导入扩展词库「${descriptor.displayName}」，点击重新部署生效",
                        "✓ Imported \"${descriptor.displayName}\" · Tap Redeploy to apply",
                        "✓ 已匯入擴充詞庫「${descriptor.displayName}」，點擊重新部署生效"
                    )
                    onFeedback(true, okMsg)
                    Toast.makeText(context, okMsg, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    onFeedback(
                        false,
                        AppStrings.pick(
                            appLang,
                            "导入扩展词库失败: ${e.message ?: "格式错误"}",
                            "Failed to import ext dict: ${e.message ?: "Invalid format"}",
                            "匯入擴充詞庫失敗: ${e.message ?: "格式錯誤"}"
                        )
                    )
                } finally {
                    isImportingExt = false
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (subDicts.isEmpty()) {
            Text(
                text = tr(
                    "未发现可用子词库或 ext/ 扩展词库，可点击下方导入 .dict.yaml / .tsv 词库。",
                    "No sub-dictionaries or ext/ files yet. Import a .dict.yaml or .tsv lexicon below.",
                    "未發現可用子詞庫或 ext/ 擴充詞庫，可點擊下方匯入 .dict.yaml / .tsv 詞庫。"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            subDicts.forEach { desc ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = desc.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                LocalizedCodetableBadgePill(
                                    text = if (desc.isBuiltIn) tr("内置", "Built-in", "內建") else tr("扩展", "Ext", "擴充"),
                                    highlight = !desc.isBuiltIn
                                )
                            }
                            Text(
                                text = desc.relativePath,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = desc.enabled,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        rimeDataManager.getSubDictToggleManager()
                                            .setSubDictEnabled(schemaId, desc.name, checked)
                                    }
                                    refreshSubDicts()
                                    onFeedback(
                                        true,
                                        AppStrings.pick(
                                            appLang,
                                            "${desc.displayName} 已${if (checked) "启用" else "停用"}，重新部署后生效",
                                            "${desc.displayName} ${if (checked) "enabled" else "disabled"} · Redeploy to apply",
                                            "${desc.displayName} 已${if (checked) "啟用" else "停用"}，重新部署後生效"
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    extDictPicker.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
                },
                enabled = !isBusy && !isImportingExt,
                modifier = Modifier.weight(1.3f),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (isImportingExt) {
                        tr("导入中...", "Importing...", "匯入中...")
                    } else {
                        tr("导入扩展词库 (.dict.yaml / .tsv)", "Import Ext (.dict.yaml / .tsv)", "匯入擴充詞庫 (.dict.yaml / .tsv)")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = {
                    isReloading = true
                    scope.launch {
                        try {
                            val resultState = withContext(Dispatchers.IO) {
                                rimeDataManager.reloadActiveEnhancedSchema(schemaId)
                            }
                            if (resultState is RimeDeploymentState.Error) {
                                onFeedback(
                                    false,
                                    AppStrings.pick(appLang, "重新部署失败: ${resultState.message}", "Redeploy failed: ${resultState.message}", "重新部署失敗: ${resultState.message}")
                                )
                            } else {
                                onFeedback(
                                    true,
                                    AppStrings.pick(appLang, "✓ 子词库配置已重新部署生效", "✓ Sub-dictionaries redeployed", "✓ 子詞庫設定已重新部署生效")
                                )
                            }
                        } catch (e: Exception) {
                            onFeedback(
                                false,
                                AppStrings.pick(appLang, "重新部署失败: ${e.message ?: "未知错误"}", "Redeploy failed: ${e.message ?: "Error"}", "重新部署失敗: ${e.message ?: "未知錯誤"}")
                            )
                        } finally {
                            isReloading = false
                        }
                    }
                },
                enabled = !isBusy && !isReloading,
                modifier = Modifier.weight(0.7f),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (isReloading) tr("部署中...", "Deploying...", "部署中...") else tr("重新部署", "Redeploy", "重新部署"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun LocalizedShadowRulesSection(
    context: Context,
    rimeDataManager: RimeDataManager,
    schemaId: String,
    deployState: RimeDeploymentState,
    metadataVersion: Long,
    onFeedback: (Boolean, String) -> Unit
) {
    val appLang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf(ShadowDictManager.ShadowRuleState()) }

    fun refreshRules() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    rimeDataManager.getShadowDictManager(schemaId).loadRules()
                }.getOrDefault(ShadowDictManager.ShadowRuleState())
            }
            rules = loaded
        }
    }

    LaunchedEffect(schemaId, deployState, metadataVersion) {
        refreshRules()
    }

    fun applyShadowMutation(feedbackText: String, mutation: (ShadowDictManager) -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) {
                mutation(rimeDataManager.getShadowDictManager(schemaId))
                if (rimeDataManager.isReady) {
                    runCatching { rimeDataManager.reloadActiveEnhancedSchema(schemaId) }
                }
            }
            refreshRules()
            onFeedback(true, feedbackText)
        }
    }

    val pinnedTotal = rules.pinnedByCode.values.sumOf { it.size }
    val blockedTotal = rules.blockedWords.size

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = tr(
                    "置顶 $pinnedTotal 条 · 屏蔽 $blockedTotal 词",
                    "$pinnedTotal pinned · $blockedTotal blocked",
                    "置頂 $pinnedTotal 條 · 封鎖 $blockedTotal 詞"
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = { refreshRules() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(tr("刷新", "Refresh", "重新整理"), style = MaterialTheme.typography.labelMedium)
                }
                if (rules.pinnedByCode.isNotEmpty() || rules.blockedWords.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            val clearMsg = AppStrings.pick(appLang, "✓ 已清空全部置顶与屏蔽规则", "✓ Cleared all pin & block rules", "✓ 已清空全部置頂與封鎖規則")
                            applyShadowMutation(clearMsg) { it.clearAll() }
                            Toast.makeText(context, clearMsg, Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(tr("清空全部", "Clear All", "清空全部"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (rules.pinnedByCode.isEmpty() && rules.blockedWords.isEmpty()) {
            Text(
                text = tr(
                    "暂无置顶或屏蔽规则。打字时长按候选词即可快速置顶首选或屏蔽词条。",
                    "No pin or block rules yet. Long-press any candidate on the keyboard to pin it to 1st or block it.",
                    "尚無置頂或封鎖規則。打字時長按候選詞即可快速置頂首選或封鎖詞條。"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            rules.pinnedByCode.forEach { (code, words) ->
                words.forEach { word ->
                    LocalizedShadowRuleRow(
                        tag = code,
                        tagHighlight = true,
                        word = word,
                        actionLabel = tr("取消置顶", "Unpin", "取消置頂"),
                        onAction = {
                            applyShadowMutation(
                                AppStrings.pick(appLang, "已取消置顶「$word ($code)」", "Unpinned \"$word ($code)\"", "已取消置頂「$word ($code)」")
                            ) {
                                it.unpinWord(code, word)
                            }
                        }
                    )
                }
            }
            rules.blockedWords.forEach { word ->
                LocalizedShadowRuleRow(
                    tag = tr("已屏蔽", "Blocked", "已封鎖"),
                    tagHighlight = false,
                    word = word,
                    actionLabel = tr("取消屏蔽", "Unblock", "取消封鎖"),
                    onAction = {
                        applyShadowMutation(
                            AppStrings.pick(appLang, "已取消屏蔽「$word」", "Unblocked \"$word\"", "已取消封鎖「$word」")
                        ) {
                            it.unblockWord(word)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun LocalizedShadowRuleRow(
    tag: String,
    tagHighlight: Boolean,
    word: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LocalizedCodetableBadgePill(text = tag, highlight = tagHighlight, monospace = tagHighlight)
                Text(
                    text = word,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (tagHighlight) MaterialTheme.colorScheme.primary else TypeFreeAmber
                )
            }
        }
    }
}

@Composable
private fun LocalizedCodetableSegmentTab(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun LocalizedCodetableBadgePill(
    text: String,
    highlight: Boolean,
    monospace: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (highlight) {
            TypeFreeEmerald.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Text(
            text = text,
            style = if (monospace) {
                MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.labelSmall
            },
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) TypeFreeEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun LocalizedRimeDictionaryImportDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val coroutineScope = rememberCoroutineScope()

    var mainDictUri by remember { mutableStateOf<Uri?>(null) }
    var mainDictFileName by remember { mutableStateOf("") }
    var dzDictUri by remember { mutableStateOf<Uri?>(null) }
    var dzDictFileName by remember { mutableStateOf("") }

    var isPasteExpanded by remember { mutableStateOf(false) }
    var rawTsvText by rememberSaveable { mutableStateOf("") }

    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val fileMimeTypes = remember {
        arrayOf("text/*", "application/octet-stream", "*/*")
    }

    val mainDictLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            mainDictUri = uri
            mainDictFileName = resolveStudioDocName(context, uri) ?: uri.lastPathSegment ?: "dict.tsv"
            errorMessage = null
        }
    }

    val dzDictLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            dzDictUri = uri
            dzDictFileName = resolveStudioDocName(context, uri) ?: uri.lastPathSegment ?: "dz.tsv"
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isImporting) onDismiss()
        },
        title = {
            Text(
                text = tr("导入自定义 TSV 码表", "Import Custom TSV Codetable", "匯入自訂 TSV 碼表"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
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
                    text = tr(
                        "通过系统安全文件选择器选取手机中的 TSV/TXT 码表文件，纯本地写入应用私有空间。",
                        "Pick a TSV/TXT codetable file from your device. Stored strictly offline in app-private storage.",
                        "透過系統安全檔案選擇器選取手機中的 TSV/TXT 碼表檔案，純本機寫入應用私有空間。"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = tr("主词库文件 (必需)", "Main Codetable File (Required)", "主詞庫檔案 (必需)"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr(
                                "格式: 编码\\t候选词1\\t候选词2...",
                                "Format: code\\tword1\\tword2...",
                                "格式: 編碼\\t候選詞1\\t候選詞2..."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (mainDictUri != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(TypeFreeEmerald.copy(alpha = 0.12f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = mainDictFileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TypeFreeEmerald,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        mainDictUri = null
                                        mainDictFileName = ""
                                    },
                                    enabled = !isImporting,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = tr("清除", "Clear", "清除"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { mainDictLauncher.launch(fileMimeTypes) },
                                enabled = !isImporting,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text(tr("选择主词库文件 (.tsv/.txt)", "Select Main TSV/TXT File", "選擇主詞庫檔案 (.tsv/.txt)"))
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = tr("单字四码反查表 (可选)", "4-Code Reverse Lookup Table (Optional)", "單字四碼反查表 (可選)"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr(
                                "格式: 4码编码\\t单字（用于 ` 通配符反查）",
                                "Format: 4code\\tchar (used for '`' wildcard lookup)",
                                "格式: 4碼編碼\\t單字（用於 ` 萬用字元反查）"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (dzDictUri != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(TypeFreeEmerald.copy(alpha = 0.12f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = dzDictFileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TypeFreeEmerald,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        dzDictUri = null
                                        dzDictFileName = ""
                                    },
                                    enabled = !isImporting,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = tr("清除", "Clear", "清除"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { dzDictLauncher.launch(fileMimeTypes) },
                                enabled = !isImporting,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text(tr("选择反查表文件 (可选)", "Select Reverse Table (Optional)", "選擇反查表檔案 (可選)"))
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isPasteExpanded = !isPasteExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isPasteExpanded) {
                                tr("▼ 收起文本粘贴区", "▼ Hide Direct TSV Paste", "▼ 收起文字貼上區")
                            } else {
                                tr("▶ 展开文本直接粘贴区 (限小片段)", "▶ Paste Small TSV Snippet Directly", "▶ 展開文字直接貼上區 (限小片段)")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (isPasteExpanded) {
                        OutlinedTextField(
                            value = rawTsvText,
                            onValueChange = {
                                rawTsvText = it
                                if (it.isNotBlank()) mainDictUri = null
                            },
                            label = { Text(tr("粘贴主词库小片段 (TSV)", "Paste TSV Snippet", "貼上主詞庫小片段 (TSV)")) },
                            placeholder = { Text("uijm\\t时间\\t实践\\nxhyx\\t小鹤音形") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp),
                            maxLines = 4,
                            enabled = !isImporting
                        )
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (isImporting) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = tr("正在导入并构建内存索引…", "Importing & indexing in memory…", "正在匯入並建立記憶體索引…"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            val canSubmit = (mainDictUri != null || rawTsvText.isNotBlank()) && !isImporting
            Button(
                onClick = {
                    isImporting = true
                    errorMessage = null
                    coroutineScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val dictIn = when {
                                    mainDictUri != null -> {
                                        context.contentResolver.openInputStream(mainDictUri!!)
                                            ?: throw IllegalStateException(
                                                AppStrings.pick(appLang, "无法读取选中的主词库文件", "Cannot read selected main file", "無法讀取選取的主詞庫檔案")
                                            )
                                    }
                                    rawTsvText.isNotBlank() -> ByteArrayInputStream(rawTsvText.toByteArray(Charsets.UTF_8))
                                    else -> throw IllegalStateException(
                                        AppStrings.pick(appLang, "请选择主词库文件或输入文本", "Please select a TSV file or paste text", "請選擇主詞庫檔案或輸入文字")
                                    )
                                }
                                val dzIn = dzDictUri?.let { context.contentResolver.openInputStream(it) }
                                dictIn.use { dStream ->
                                    if (dzIn != null) {
                                        dzIn.use { zStream ->
                                            FlypyDictionary.importCustomDictionary(context, dStream, zStream)
                                        }
                                    } else {
                                        FlypyDictionary.importCustomDictionary(context, dStream, null)
                                    }
                                }
                                TypeFreeApp.instance.reloadFlypyDictionary()
                            }
                            Toast.makeText(
                                context,
                                AppStrings.pick(appLang, "✓ 已导入自定义 TSV 码表", "✓ Imported custom TSV codetable", "✓ 已匯入自訂 TSV 碼表"),
                                Toast.LENGTH_LONG
                            ).show()
                            isImporting = false
                            onDismiss()
                        } catch (e: Exception) {
                            isImporting = false
                            errorMessage = e.message ?: AppStrings.pick(appLang, "导入失败", "Import failed", "匯入失敗")
                        }
                    }
                },
                enabled = canSubmit
            ) {
                Text(tr("开始导入", "Import", "開始匯入"))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (!isImporting) onDismiss() },
                enabled = !isImporting
            ) {
                Text(tr("取消", "Cancel", "取消"))
            }
        }
    )
}

private fun resolveStudioDocName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx != -1 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) {
        null
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
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }
    }
}
