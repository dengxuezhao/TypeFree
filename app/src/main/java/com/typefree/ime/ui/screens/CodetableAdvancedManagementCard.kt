package com.typefree.ime.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import com.typefree.core.ime.coordinator.CommitAction
import com.typefree.core.ime.enhancer.CodetableCodeDeriver
import com.typefree.core.ime.enhancer.DirectCommandEngine
import com.typefree.core.ime.enhancer.ShadowDictManager
import com.typefree.core.ime.importer.CustomUserPhraseFileEditor
import com.typefree.core.ime.importer.SubDictToggleManager
import com.typefree.core.ime.overlay.OverlaySymbolEntry
import com.typefree.core.ime.overlay.SymbolTrie
import com.typefree.core.ime.profile.SchemaProfile
import com.typefree.ime.BuildConfig
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.InputScheme
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ime.engine.PresetSchemaCatalog
import com.typefree.ime.ime.engine.RimeDataManager
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Collapsible Material 3 advanced codetable management card for preset schemas,
 * `_top`/`_user` phrases, `;` quick symbols, sub-dicts/`ext/` lexicons,
 * smart codetable toggles, and `shadow.yaml` pin/block rules.
 */
@Composable
fun CodetableAdvancedManagementCard(
    repository: AppRepository,
    settings: AppSettings,
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

    val profileTitle = localizedActiveProfileName(
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
                            CodetableBadgePill(
                                text = "$profileTitle ($schemaId)",
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
                    imageVector = if (cardExpanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (cardExpanded) {
                        tr("收起", "Collapse", "收起")
                    } else {
                        tr("展开", "Expand", "展開")
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            feedbackMessage?.let { (isSuccess, text) ->
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
                            onClick = { feedbackMessage = null },
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

            AnimatedVisibility(visible = cardExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    // 1. Built-in open-source codetable presets
                    ExpandableCodetableSubsection(
                        title = tr("内置开源码表方案一键安装", "Built-in Open Schemas", "內建開源碼表方案一鍵安裝"),
                        badge = tr(
                            "${PresetSchemaCatalog.BUILT_IN_PRESETS.size} 套",
                            "${PresetSchemaCatalog.BUILT_IN_PRESETS.size} packs",
                            "${PresetSchemaCatalog.BUILT_IN_PRESETS.size} 套"
                        ),
                        expanded = presetsExpanded,
                        onToggle = { presetsExpanded = !presetsExpanded }
                    ) {
                        PresetSchemaInstallSection(
                            context = context,
                            rimeDataManager = rimeDataManager,
                            repository = repository,
                            isBusy = isDeploying,
                            onFeedback = { ok, msg -> feedbackMessage = ok to msg }
                        )
                    }

                    // 2. User & pinned phrases (_top / _user)
                    ExpandableCodetableSubsection(
                        title = tr(
                            "自造词与置顶词管理 (_top / _user)",
                            "Pinned & User Phrases (_top / _user)",
                            "自造詞與置頂詞管理 (_top / _user)"
                        ),
                        badge = schemaId,
                        expanded = phrasesExpanded,
                        onToggle = { phrasesExpanded = !phrasesExpanded }
                    ) {
                        UserPhraseEditorSection(
                            context = context,
                            rimeDataManager = rimeDataManager,
                            schemaId = schemaId,
                            deployState = rimeDeployState,
                            metadataVersion = metadataVersion,
                            isBusy = isDeploying,
                            onFeedback = { ok, msg -> feedbackMessage = ok to msg }
                        )
                    }

                    // 3. Sub-dictionaries & ext/ lexicons
                    ExpandableCodetableSubsection(
                        title = tr(
                            "分类子词库与扩展词库 (ext/)",
                            "Sub-Dictionaries & Extensions (ext/)",
                            "分類子詞庫與擴充詞庫 (ext/)"
                        ),
                        badge = "Layer +1",
                        expanded = subDictsExpanded,
                        onToggle = { subDictsExpanded = !subDictsExpanded }
                    ) {
                        SubDictManagerSection(
                            context = context,
                            rimeDataManager = rimeDataManager,
                            schemaId = schemaId,
                            deployState = rimeDeployState,
                            metadataVersion = metadataVersion,
                            isBusy = isDeploying,
                            onFeedback = { ok, msg -> feedbackMessage = ok to msg }
                        )
                    }

                    // 4. Shadow pin/block rules (shadow.yaml)
                    ExpandableCodetableSubsection(
                        title = tr(
                            "明暗调频 / 屏蔽规则 (shadow.yaml)",
                            "Pin & Block Rules (shadow.yaml)",
                            "置頂 / 封鎖規則 (shadow.yaml)"
                        ),
                        badge = tr("置顶/屏蔽", "Pin/Block", "置頂/封鎖"),
                        expanded = shadowExpanded,
                        onToggle = { shadowExpanded = !shadowExpanded }
                    ) {
                        ShadowRulesSection(
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
private fun ExpandableCodetableSubsection(
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
                    CodetableBadgePill(text = badge, highlight = false)
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) {
                        tr("收起", "Collapse", "收起")
                    } else {
                        tr("展开", "Expand", "展開")
                    },
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

/**
 * Standalone collapsible card for one-click installation of built-in open-source codetable presets.
 * Visible even when the user is currently on Pinyin / Double Pinyin schemes so new users can
 * immediately discover and install Tiger Code, Wubi 86, and Xingkong Jiandao 6.
 */
@Composable
fun BuiltInPresetSchemasCard(
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
                            CodetableBadgePill(
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
                    imageVector = if (cardExpanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (cardExpanded) {
                        tr("收起", "Collapse", "收起")
                    } else {
                        tr("展开", "Expand", "展開")
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            feedbackMessage?.let { (isSuccess, text) ->
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
                            onClick = { feedbackMessage = null },
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

            AnimatedVisibility(visible = cardExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    PresetSchemaInstallSection(
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetSchemaInstallSection(
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
                                    CodetableBadgePill(text = tr("已安装", "Installed", "已安裝"), highlight = true)
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CodetableBadgePill(text = localizedLicense, highlight = false)
                                CodetableBadgePill(text = localizedArchetype, highlight = true)
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
                                        repository.updateSettings { it.copy(inputScheme = InputScheme.RIME_CUSTOM) }
                                        val okBanner = AppStrings.pick(
                                            appLang,
                                            "✓ 已安装并切换至「$localizedTitle」",
                                            "✓ Installed & activated \"$localizedTitle\"",
                                            "✓ 已安裝並切換至「$localizedTitle」"
                                        )
                                        val okToast = AppStrings.pick(
                                            appLang,
                                            "✓ 已安装并部署「$localizedTitle」",
                                            "✓ Installed & deployed \"$localizedTitle\"",
                                            "✓ 已安裝並部署「$localizedTitle」"
                                        )
                                        onFeedback(true, okBanner)
                                        Toast.makeText(context, okToast, Toast.LENGTH_LONG).show()
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

object XiaohePhraseCodeHelper {
    fun deriveXiaohe4Code(
        phrase: String,
        reverseLookupMap: Map<String, List<String>>
    ): String? = CodetableCodeDeriver.deriveXiaohe4Code(phrase, reverseLookupMap)
}

@Composable
private fun UserPhraseEditorSection(
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
    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0 = _top.txt, 1 = _user.txt
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
                CodetableSegmentTab(
                    title = tr("置顶词 (${schemaId}_top.txt)", "Pinned (_top.txt)", "置頂詞 (${schemaId}_top.txt)"),
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1f)
                )
                CodetableSegmentTab(
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
                                CodetableBadgePill(text = item.code, highlight = true, monospace = true)
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
                                    contentDescription = tr("删除词条", "Delete phrase", "刪除詞條"),
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
                                AppStrings.pick(
                                    appLang,
                                    "重新部署失败: ${resultState.message}",
                                    "Redeploy failed: ${resultState.message}",
                                    "重新部署失敗: ${resultState.message}"
                                )
                            )
                        } else {
                            onFeedback(
                                true,
                                AppStrings.pick(
                                    appLang,
                                    "✓ 已重新部署「$schemaId」",
                                    "✓ Redeployed \"$schemaId\"",
                                    "✓ 已重新部署「$schemaId」"
                                )
                            )
                            Toast.makeText(
                                context,
                                AppStrings.pick(
                                    appLang,
                                    "✓ 词库已重新部署生效",
                                    "✓ Lexicon redeployed",
                                    "✓ 詞庫已重新部署生效"
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        onFeedback(
                            false,
                            AppStrings.pick(
                                appLang,
                                "重新部署失败: ${e.message ?: "未知错误"}",
                                "Redeploy failed: ${e.message ?: "Unknown error"}",
                                "重新部署失敗: ${e.message ?: "未知錯誤"}"
                            )
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
        AddUserPhraseDialog(
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
                            AppStrings.pick(
                                appLang,
                                "添加失败: ${err.message ?: "输入不合法"}",
                                "Failed to add: ${err.message ?: "Invalid input"}",
                                "新增失敗: ${err.message ?: "輸入不合法"}"
                            )
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
private fun AddUserPhraseDialog(
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
        title = {
            Text(
                tr(
                    "添加个人词条 ($schemaId)",
                    "Add Phrase ($schemaId)",
                    "新增個人詞條 ($schemaId)"
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CodetableSegmentTab(
                        title = tr("置顶词 (_top.txt)", "Pinned (_top)", "置頂詞 (_top.txt)"),
                        selected = targetTopPinned,
                        onClick = { targetTopPinned = true },
                        modifier = Modifier.weight(1f)
                    )
                    CodetableSegmentTab(
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
                                tr(
                                    "输入 2~4+ 汉字，如：双拼输入",
                                    "2–4+ Chinese chars, e.g. 双拼输入",
                                    "輸入 2~4+ 漢字，如：雙拼輸入"
                                )
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
                                text = tr("自动算小鹤 4 码", "Auto 4-Code", "自動算小鶴 4 碼"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (isXiaoheSchema) {
                    if (derivedCode != null) {
                        Text(
                            text = tr(
                                "按单字反查表推算小鹤 4 码：$derivedCode",
                                "Derived Flypy 4-code: $derivedCode",
                                "按單字反查表推算小鶴 4 碼：$derivedCode"
                            ),
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
fun QuickSymbolsEditorCard(
    schemaId: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val rimeDataManager = remember { TypeFreeApp.instance.rimeDataManager }
    val deployState by rimeDataManager.deploymentState.collectAsState()
    val metadataVersion by rimeDataManager.metadataVersion.collectAsState()
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
                        text = tr(
                            "`;` 多码快符表 (quick_symbols.yaml)",
                            "`;` Quick Symbols (quick_symbols.yaml)",
                            "`;` 多碼快符表 (quick_symbols.yaml)"
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = tr(
                            "供分号引导多码快符（如 `;mq -> ①`）或外接物理键盘使用",
                            "Semicolon-prefixed quick symbols (e.g. `;mq -> ①`) and hardware keyboard mappings",
                            "供分號引導多碼快符（如 `;mq -> ①`）或外接實體鍵盤使用"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CodetableBadgePill(
                    text = tr(
                        "${rimeDataManager.activeSymbolTrie.size} 项",
                        "${rimeDataManager.activeSymbolTrie.size} items",
                        "${rimeDataManager.activeSymbolTrie.size} 項"
                    ),
                    highlight = true
                )
            }

            feedbackMessage?.let { (ok, text) ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ok) TypeFreeEmerald else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }

            QuickSymbolsEditorSection(
                context = context,
                rimeDataManager = rimeDataManager,
                schemaId = schemaId,
                deployState = deployState,
                metadataVersion = metadataVersion,
                onFeedback = { ok, msg -> feedbackMessage = ok to msg }
            )
        }
    }
}

@Composable
private fun QuickSymbolsEditorSection(
    context: Context,
    rimeDataManager: RimeDataManager,
    schemaId: String,
    deployState: RimeDeploymentState,
    metadataVersion: Long,
    onFeedback: (Boolean, String) -> Unit
) {
    val appLang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    var currentTrie by remember(schemaId, deployState, metadataVersion) {
        mutableStateOf(rimeDataManager.activeSymbolTrie)
    }
    var keyInput by rememberSaveable { mutableStateOf("") }
    var valueInput by rememberSaveable { mutableStateOf("") }

    fun saveUpdatedEntries(newEntries: List<OverlaySymbolEntry>, feedbackText: String) {
        val updatedTrie = SymbolTrie(
            guideKey = currentTrie.guideKey,
            doubleTapCommit = currentTrie.doubleTapCommit,
            doubleTapAsciiCommit = currentTrie.doubleTapAsciiCommit,
            showAllOnEnter = currentTrie.showAllOnEnter,
            entries = newEntries
        )
        scope.launch {
            withContext(Dispatchers.IO) {
                rimeDataManager.saveQuickSymbolsTrie(updatedTrie, schemaId)
            }
            currentTrie = updatedTrie
            onFeedback(true, feedbackText)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { raw ->
                    keyInput = raw.filter { it.isLetterOrDigit() }.take(4)
                },
                modifier = Modifier.weight(0.8f),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                label = { Text(tr("键码", "Key", "鍵碼")) },
                placeholder = { Text(tr("如 q", "e.g. q", "如 q")) }
            )
            OutlinedTextField(
                value = valueInput,
                onValueChange = { valueInput = it },
                modifier = Modifier.weight(1.4f),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                label = { Text(tr("上屏符号", "Symbol", "上屏符號")) },
                placeholder = { Text(tr("如 ：“ 或 →", "e.g. “” or →", "如 ：“ 或 →")) }
            )
            Button(
                onClick = {
                    val cleanKey = keyInput.trim()
                    if (cleanKey.isEmpty() || valueInput.isEmpty()) {
                        onFeedback(
                            false,
                            AppStrings.pick(
                                appLang,
                                "请输入 1~4 位字母/数字键码和输出符号",
                                "Enter a 1–4 char alphanumeric key and symbol",
                                "請輸入 1~4 位字母/數字鍵碼和輸出符號"
                            )
                        )
                        return@Button
                    }
                    val newItem = OverlaySymbolEntry(
                        key = cleanKey,
                        displayText = valueInput,
                        action = CommitAction.PlainText(valueInput)
                    )
                    val existing = currentTrie.entriesList().toMutableList()
                    val idx = existing.indexOfFirst { it.key == cleanKey }
                    if (idx >= 0) {
                        existing[idx] = newItem
                    } else {
                        existing.add(newItem)
                    }
                    keyInput = ""
                    valueInput = ""
                    saveUpdatedEntries(
                        existing,
                        AppStrings.pick(
                            appLang,
                            "✓ 已保存快符 ;${cleanKey}",
                            "✓ Saved quick symbol ;${cleanKey}",
                            "✓ 已儲存快符 ;${cleanKey}"
                        )
                    )
                },
                enabled = keyInput.isNotBlank() && valueInput.isNotEmpty(),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
            ) {
                Text(tr("保存", "Save", "儲存"), fontWeight = FontWeight.Bold)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            currentTrie.entriesList().forEach { item ->
                val preview = when (val act = item.action) {
                    is CommitAction.PairedText -> "${act.left}${act.right} (${tr("成对", "Paired", "成對")})"
                    is CommitAction.PlainText -> when (act.text) {
                        "\u3000" -> tr("全角空格", "Full-Width Space", "全形空白")
                        "\t" -> "Tab"
                        else -> act.text
                    }
                    CommitAction.UndoLastCommit -> tr("↶撤回", "↶ Undo", "↶復原")
                    CommitAction.RepeatLastCommit -> tr("↻重复", "↻ Repeat", "↻重複")
                    else -> when (item.displayText.trim()) {
                        "↶撤回" -> tr("↶撤回", "↶ Undo", "↶復原")
                        "↻重复" -> tr("↻重复", "↻ Repeat", "↻重複")
                        "✍️ AI 帮写", "打开帮写" -> tr("✍️ AI 帮写", "✍️ Help Write", "✍️ AI 幫寫")
                        else -> item.displayText
                    }
                }
                val localizedItemLabel = localizedQuickSymbolLabel(item.label, appLang)
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
                            CodetableBadgePill(
                                text = "${currentTrie.guideKey}${item.key}",
                                highlight = true,
                                monospace = true
                            )
                            Text(
                                text = preview,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (localizedItemLabel.isNotEmpty()) {
                                Text(
                                    text = localizedItemLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    keyInput = item.key
                                    valueInput = when (val act = item.action) {
                                        is CommitAction.PlainText -> act.text
                                        is CommitAction.PairedText -> "${act.left}${act.right}"
                                        else -> item.displayText
                                    }
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = tr("编辑快符", "Edit symbol", "編輯快符"),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    val remaining = currentTrie.entriesList().filterNot { it.key == item.key }
                                    saveUpdatedEntries(
                                        remaining,
                                        AppStrings.pick(
                                            appLang,
                                            "已移除快符 ${currentTrie.guideKey}${item.key}",
                                            "Removed quick symbol ${currentTrie.guideKey}${item.key}",
                                            "已移除快符 ${currentTrie.guideKey}${item.key}"
                                        )
                                    )
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = tr("删除快符", "Delete symbol", "刪除快符"),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    val resetTrie = withContext(Dispatchers.IO) {
                        rimeDataManager.resetQuickSymbolsToDefault(schemaId)
                    }
                    currentTrie = resetTrie
                    keyInput = ""
                    valueInput = ""
                    onFeedback(
                        true,
                        AppStrings.pick(
                            appLang,
                            "✓ 快符映射已恢复默认 (${resetTrie.size} 项)",
                            "✓ Restored default quick symbols (${resetTrie.size} items)",
                            "✓ 快符映射已恢復預設 (${resetTrie.size} 項)"
                        )
                    )
                    Toast.makeText(
                        context,
                        AppStrings.pick(
                            appLang,
                            "✓ 快符映射已恢复默认",
                            "✓ Quick symbols reset to default",
                            "✓ 快符映射已恢復預設"
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                tr("恢复默认快符映射", "Reset Default Quick Symbols", "恢復預設快符映射"),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SubDictManagerSection(
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
                    val fileName = resolveDocumentDisplayName(context, uri) ?: "custom_ext.tsv"
                    val descriptor = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            rimeDataManager.importExtSubDictionary(fileName, stream, schemaId)
                        } ?: throw IllegalArgumentException(
                            AppStrings.pick(
                                appLang,
                                "无法读取所选词库文件",
                                "Cannot read selected lexicon file",
                                "無法讀取所選詞庫檔案"
                            )
                        )
                    }
                    refreshSubDicts()
                    val dictTitle = localizedSubDictDisplayName(descriptor, appLang)
                    onFeedback(
                        true,
                        AppStrings.pick(
                            appLang,
                            "✓ 已导入扩展词库「$dictTitle」，点击重新部署生效",
                            "✓ Imported \"$dictTitle\" · Tap Redeploy to apply",
                            "✓ 已匯入擴充詞庫「$dictTitle」，點擊重新部署生效"
                        )
                    )
                    Toast.makeText(
                        context,
                        AppStrings.pick(
                            appLang,
                            "✓ 已导入扩展词库「$dictTitle」",
                            "✓ Imported \"$dictTitle\"",
                            "✓ 已匯入擴充詞庫「$dictTitle」"
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
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
                val dictTitle = localizedSubDictDisplayName(desc, appLang)
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
                                    text = dictTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                CodetableBadgePill(
                                    text = if (desc.isBuiltIn) {
                                        tr("内置", "Built-in", "內建")
                                    } else {
                                        tr("扩展", "Ext", "擴充")
                                    },
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
                                            "$dictTitle 已${if (checked) "启用" else "停用"}，重新部署后生效",
                                            "$dictTitle ${if (checked) "enabled" else "disabled"} · Redeploy to apply",
                                            "$dictTitle 已${if (checked) "啟用" else "停用"}，重新部署後生效"
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
                        tr(
                            "导入扩展词库 (.dict.yaml / .tsv)",
                            "Import Ext (.dict.yaml / .tsv)",
                            "匯入擴充詞庫 (.dict.yaml / .tsv)"
                        )
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
                                    AppStrings.pick(
                                        appLang,
                                        "重新部署失败: ${resultState.message}",
                                        "Redeploy failed: ${resultState.message}",
                                        "重新部署失敗: ${resultState.message}"
                                    )
                                )
                            } else {
                                onFeedback(
                                    true,
                                    AppStrings.pick(
                                        appLang,
                                        "✓ 子词库配置已重新部署生效",
                                        "✓ Sub-dictionaries redeployed",
                                        "✓ 子詞庫設定已重新部署生效"
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            onFeedback(
                                false,
                                AppStrings.pick(
                                    appLang,
                                    "重新部署失败: ${e.message ?: "未知错误"}",
                                    "Redeploy failed: ${e.message ?: "Unknown error"}",
                                    "重新部署失敗: ${e.message ?: "未知錯誤"}"
                                )
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
                    text = if (isReloading) {
                        tr("部署中...", "Deploying...", "部署中...")
                    } else {
                        tr("重新部署", "Redeploy", "重新部署")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ShadowRulesSection(
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

    val pinnedCount = rules.pinnedByCode.values.sumOf { it.size }
    val blockedCount = rules.blockedWords.size

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = tr(
                    "置顶 $pinnedCount 条 · 屏蔽 $blockedCount 词",
                    "Pinned: $pinnedCount · Blocked: $blockedCount",
                    "置頂 $pinnedCount 條 · 封鎖 $blockedCount 詞"
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
                            applyShadowMutation(
                                AppStrings.pick(
                                    appLang,
                                    "✓ 已清空全部置顶与屏蔽规则",
                                    "✓ Cleared all pin & block rules",
                                    "✓ 已清空全部置頂與封鎖規則"
                                )
                            ) { it.clearAll() }
                            Toast.makeText(
                                context,
                                AppStrings.pick(
                                    appLang,
                                    "✓ 已清空全部阴影规则",
                                    "✓ All shadow rules cleared",
                                    "✓ 已清空全部陰影規則"
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            tr("清空全部", "Clear All", "清空全部"),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        if (rules.pinnedByCode.isEmpty() && rules.blockedWords.isEmpty()) {
            Text(
                text = tr(
                    "暂无置顶或屏蔽规则。打字时长按候选词即可快速置顶首选或屏蔽词条。",
                    "No pinned or blocked rules yet. Long-press any candidate while typing to pin it to 1st or block it.",
                    "尚無置頂或封鎖規則。打字時長按候選詞即可快速置頂首選或封鎖詞條。"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            rules.pinnedByCode.forEach { (code, words) ->
                words.forEach { word ->
                    ShadowRuleRow(
                        tag = code,
                        tagHighlight = true,
                        word = word,
                        actionLabel = tr("取消置顶", "Unpin", "取消置頂"),
                        onAction = {
                            applyShadowMutation(
                                AppStrings.pick(
                                    appLang,
                                    "已取消置顶「$word ($code)」",
                                    "Unpinned \"$word ($code)\"",
                                    "已取消置頂「$word ($code)」"
                                )
                            ) {
                                it.unpinWord(code, word)
                            }
                        }
                    )
                }
            }
            rules.blockedWords.forEach { word ->
                ShadowRuleRow(
                    tag = tr("已屏蔽", "Blocked", "已封鎖"),
                    tagHighlight = false,
                    word = word,
                    actionLabel = tr("取消屏蔽", "Unblock", "取消封鎖"),
                    onAction = {
                        applyShadowMutation(
                            AppStrings.pick(
                                appLang,
                                "已取消屏蔽「$word」",
                                "Unblocked \"$word\"",
                                "已取消封鎖「$word」"
                            )
                        ) {
                            it.unblockWord(word)
                        }
                    }
                )
            }
        }
    }
}

private fun localizedActiveProfileName(
    schemaId: String,
    rawDisplayName: String,
    lang: AppLang
): String {
    return when (schemaId.lowercase()) {
        "flypy" -> AppStrings.pick(lang, "小鹤音形", "Flypy 4-Code", "小鶴音形")
        "xkjd6" -> AppStrings.pick(lang, "星空键道 6", "XKJD 6 (KeyTao)", "星空鍵道 6")
        "wubi86" -> AppStrings.pick(lang, "五笔 86", "Wubi 86", "五筆 86")
        "tigress" -> AppStrings.pick(lang, "虎码官方字词", "Tiger Code (Words)", "虎碼官方字詞")
        "tiger" -> AppStrings.pick(lang, "虎码官方单字", "Tiger Code (Chars)", "虎碼官方單字")
        "rime_ice" -> AppStrings.pick(lang, "雾凇拼音", "Rime Ice Pinyin", "霧凇拼音")
        else -> {
            val dp = com.typefree.ime.data.DoublePinyinLayout.fromSchemaId(schemaId.lowercase())
            if (dp != null) AppStrings.doublePinyinLayoutLabel(dp, lang) else rawDisplayName.ifBlank { schemaId }
        }
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
                "開源音形頂功方案（xkinput/KeyTao），聲韻雙拼 + a/e/i/o/u/v 頂功筆畫分離，內建 librime-lua topup_processor 原生頂屏。"
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
                "虎碼官方字詞版（11.7 萬單字 + 13 萬全碼詞 + 3 千簡詞），四碼唯一自動上屏、五碼頂、預設常用字集過濾。"
            )
        )
        "tiger" -> Triple(
            AppStrings.pick(lang, preset.displayName, "Tiger Code (Single-Char)", "虎碼官方單字"),
            AppStrings.pick(lang, preset.archetypeLabel, "4-Code Shape (FIXED_SHAPECODE)", "純形定長 (FIXED_SHAPECODE)"),
            AppStrings.pick(
                lang,
                preset.description,
                "Official Tiger Code single-character edition (117k chars, zero phrase collisions), ideal for single-char masters.",
                "虎碼官方單字全集版（11.7 萬單字，零詞組干擾），四碼唯一自動上屏、五碼頂、預設常用字集過濾，適合單字流。"
            )
        )
        else -> Triple(preset.displayName, preset.archetypeLabel, preset.description)
    }
}

private fun localizedSubDictDisplayName(
    desc: SubDictToggleManager.SubDictDescriptor,
    lang: AppLang
): String {
    return when (desc.name) {
        "flypy_full" -> AppStrings.pick(
            lang,
            "全码字词库 (flypy_full)",
            "Full-Code Chars (flypy_full)",
            "全碼字詞庫 (flypy_full)"
        )
        else -> desc.displayName
    }
}

private fun localizedQuickSymbolLabel(rawLabel: String, lang: AppLang): String {
    return when (rawLabel.trim()) {
        "撤回上屏", "撤回" -> AppStrings.pick(lang, rawLabel, "Undo Commit", "復原上屏")
        "重复上屏", "重复" -> AppStrings.pick(lang, rawLabel, "Repeat Last", "重複上屏")
        "全角空格", "全角" -> AppStrings.pick(lang, rawLabel, "Full-Width Space", "全形空白")
        "打开帮写", "AI 帮写" -> AppStrings.pick(lang, rawLabel, "Help Me Write", "AI 幫寫")
        else -> rawLabel
    }
}

@Composable
private fun ShadowRuleRow(
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
                CodetableBadgePill(text = tag, highlight = tagHighlight, monospace = tagHighlight)
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
private fun CodetableSegmentTab(
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
private fun CodetableBadgePill(
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
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

private fun resolveDocumentDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx != -1 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }
}
