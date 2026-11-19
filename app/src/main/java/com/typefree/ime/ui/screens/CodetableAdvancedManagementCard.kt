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
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.InputScheme
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
    val scope = rememberCoroutineScope()
    val rimeDataManager = remember { TypeFreeApp.instance.rimeDataManager }
    val metadataVersion by rimeDataManager.metadataVersion.collectAsState()
    val schemaId = activeProfile.schemaId.ifBlank { "flypy" }
    val isDeploying = rimeDeployState is RimeDeploymentState.Deploying

    var cardExpanded by rememberSaveable(defaultExpanded) { mutableStateOf(defaultExpanded) }
    var feedbackMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var presetsExpanded by rememberSaveable { mutableStateOf(false) }
    var phrasesExpanded by rememberSaveable { mutableStateOf(true) }
    var subDictsExpanded by rememberSaveable { mutableStateOf(false) }
    var shadowExpanded by rememberSaveable { mutableStateOf(false) }

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
                                text = "码表进阶管理",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            CodetableBadgePill(
                                text = "${activeProfile.displayName} ($schemaId)",
                                highlight = true
                            )
                        }
                        Text(
                            text = "方案一键安装、置顶/自造词、子词库与阴影规则",
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
                    contentDescription = if (cardExpanded) "收起" else "展开",
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
                                contentDescription = "关闭提示",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = cardExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    // 1. 内置开源码表方案一键安装
                    ExpandableCodetableSubsection(
                        title = "内置开源码表方案一键安装",
                        badge = "${PresetSchemaCatalog.BUILT_IN_PRESETS.size} 套",
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

                    // 2. 自造词与置顶词管理 (_top / _user)
                    ExpandableCodetableSubsection(
                        title = "自造词与置顶词管理 (_top / _user)",
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

                    // 3. 分类子词库与扩展词库 (flypy_full / ext/)
                    ExpandableCodetableSubsection(
                        title = "分类子词库与扩展词库 (ext/)",
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

                    // 4. 明暗调频 / 屏蔽规则 (shadow.yaml)
                    ExpandableCodetableSubsection(
                        title = "明暗调频 / 屏蔽规则 (shadow.yaml)",
                        badge = "置顶/屏蔽",
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
                        fontWeight = FontWeight.Bold
                    )
                    CodetableBadgePill(text = badge, highlight = false)
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "收起" else "展开",
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
private fun PresetSchemaInstallSection(
    context: Context,
    rimeDataManager: RimeDataManager,
    repository: AppRepository,
    isBusy: Boolean,
    onFeedback: (Boolean, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var installingId by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PresetSchemaCatalog.BUILT_IN_PRESETS.forEach { preset ->
            val isInstallingThis = installingId == preset.id
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
                            Text(
                                text = preset.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CodetableBadgePill(text = preset.license, highlight = false)
                                CodetableBadgePill(text = preset.archetypeLabel, highlight = true)
                            }
                        }

                        FilledTonalButton(
                            onClick = {
                                installingId = preset.id
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            rimeDataManager.installPresetSchema(context, preset.assetPath)
                                        }
                                        TypeFreeApp.instance.rimeInputEngine.setInputScheme(InputScheme.RIME_CUSTOM)
                                        repository.updateSettings { it.copy(inputScheme = InputScheme.RIME_CUSTOM) }
                                        val label = result.displayName.ifBlank { preset.displayName }
                                        onFeedback(true, "✓ 已安装并切换至「$label」")
                                        Toast.makeText(context, "✓ 已安装并部署「$label」", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        val errMsg = "安装「${preset.displayName}」失败: ${e.message ?: "未知错误"}"
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
                                Text("安装中", style = MaterialTheme.typography.labelMedium)
                            } else {
                                Text("一键安装", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text(
                        text = preset.description,
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
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0 = _top.txt, 1 = _user.txt
    val isTopPinned = selectedTab == 0

    var showAddPhraseDialog by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<CustomUserPhraseFileEditor.UserPhraseEntry>>(emptyList()) }
    var isReloading by remember { mutableStateOf(false) }
    var fallbackReverseMap by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    val effectiveReverseMap = remember(rimeDataManager.activeReverseLookupMap, fallbackReverseMap, metadataVersion) {
        rimeDataManager.activeReverseLookupMap.ifEmpty { fallbackReverseMap }
    }

    LaunchedEffect(schemaId, metadataVersion) {
        if (rimeDataManager.activeReverseLookupMap.isEmpty() && fallbackReverseMap.isEmpty()) {
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
                    title = "置顶词 (${schemaId}_top.txt)",
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1f)
                )
                CodetableSegmentTab(
                    title = "自造词 (${schemaId}_user.txt)",
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
                text = if (isTopPinned) "添加置顶词条（支持自动算小鹤 4 码）" else "添加自造词条（支持自动算小鹤 4 码）",
                fontWeight = FontWeight.Bold
            )
        }

        if (entries.isEmpty()) {
            Text(
                text = if (isTopPinned) "暂无置顶词记录" else "暂无自造词记录",
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
                                            onFeedback(true, "已删除「${item.word}」，重新部署后生效")
                                        }
                                    }
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "删除词条",
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
                            onFeedback(false, "重新部署失败: ${resultState.message}")
                        } else {
                            onFeedback(true, "✓ 已重新部署「$schemaId」")
                            Toast.makeText(context, "✓ 词库已重新部署生效", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        onFeedback(false, "重新部署失败: ${e.message ?: "未知错误"}")
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
                Text("正在部署...")
            } else {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("重新部署生效", fontWeight = FontWeight.SemiBold)
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
                        onFeedback(false, "添加失败: ${err.message ?: "输入不合法"}")
                    } else {
                        selectedTab = if (targetTopPinned) 0 else 1
                        showAddPhraseDialog = false
                        reloadList()
                        onFeedback(true, "✓ 已写入「$cleanWord ($cleanCode)」，点击重新部署生效")
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

    val derivedCode = remember(wordInput, reverseLookupMap) {
        XiaohePhraseCodeHelper.deriveXiaohe4Code(wordInput, reverseLookupMap)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加个人词条 ($schemaId)") },
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
                        title = "置顶词 (_top.txt)",
                        selected = targetTopPinned,
                        onClick = { targetTopPinned = true },
                        modifier = Modifier.weight(1f)
                    )
                    CodetableSegmentTab(
                        title = "自造词 (_user.txt)",
                        selected = !targetTopPinned,
                        onClick = { targetTopPinned = false },
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = wordInput,
                    onValueChange = { next ->
                        wordInput = next
                        if (!codeManuallyEdited) {
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
                    label = { Text("词条 *") },
                    placeholder = { Text("输入 2~4+ 汉字，如：双拼输入") }
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
                        label = { Text("编码 *") },
                        placeholder = { Text("如：uupb") }
                    )
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
                            text = "自动算小鹤 4 码",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (derivedCode != null) {
                    Text(
                        text = "按单字反查表推算小鹤 4 码：$derivedCode",
                        style = MaterialTheme.typography.labelSmall,
                        color = TypeFreeEmerald,
                        fontWeight = FontWeight.SemiBold
                    )
                } else if (wordInput.trim().isNotEmpty()) {
                    Text(
                        text = "输入 2~4+ 个纯汉字可自动推导小鹤 4 码，也可手动填写自定义编码",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                Text("写入词库")
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
                        text = "`;` 多码快符表 (quick_symbols.yaml)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "供分号引导多码快符（如 `;mq -> ①`）或外接物理键盘使用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CodetableBadgePill(text = "${rimeDataManager.activeSymbolTrie.size} 项", highlight = true)
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
                label = { Text("键码") },
                placeholder = { Text("如 q") }
            )
            OutlinedTextField(
                value = valueInput,
                onValueChange = { valueInput = it },
                modifier = Modifier.weight(1.4f),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                label = { Text("上屏符号") },
                placeholder = { Text("如 ：“ 或 →") }
            )
            Button(
                onClick = {
                    val cleanKey = keyInput.trim()
                    if (cleanKey.isEmpty() || valueInput.isEmpty()) {
                        onFeedback(false, "请输入 1~4 位字母/数字键码和输出符号")
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
                    saveUpdatedEntries(existing, "✓ 已保存快符 ;${cleanKey}")
                },
                enabled = keyInput.isNotBlank() && valueInput.isNotEmpty(),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
            ) {
                Text("保存", fontWeight = FontWeight.Bold)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            currentTrie.entriesList().forEach { item ->
                val preview = when (val act = item.action) {
                    is CommitAction.PairedText -> "${act.left}${act.right} (成对)"
                    is CommitAction.PlainText -> when (act.text) {
                        "\u3000" -> "全角空格"
                        "\t" -> "Tab"
                        else -> act.text
                    }
                    else -> item.displayText
                }
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
                            if (item.label.isNotEmpty()) {
                                Text(
                                    text = item.label,
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
                                    contentDescription = "编辑快符",
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    val remaining = currentTrie.entriesList().filterNot { it.key == item.key }
                                    saveUpdatedEntries(remaining, "已移除快符 ${currentTrie.guideKey}${item.key}")
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "删除快符",
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
                    onFeedback(true, "✓ 快符映射已恢复默认 (${resetTrie.size} 项)")
                    Toast.makeText(context, "✓ 快符映射已恢复默认", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("恢复默认快符映射", fontWeight = FontWeight.SemiBold)
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
                        } ?: throw IllegalArgumentException("无法读取所选词库文件")
                    }
                    refreshSubDicts()
                    onFeedback(true, "✓ 已导入扩展词库「${descriptor.displayName}」，点击重新部署生效")
                    Toast.makeText(context, "✓ 已导入扩展词库「${descriptor.displayName}」", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    onFeedback(false, "导入扩展词库失败: ${e.message ?: "格式错误"}")
                } finally {
                    isImportingExt = false
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (subDicts.isEmpty()) {
            Text(
                text = "未发现可用子词库或 ext/ 扩展词库，可点击下方导入 .dict.yaml / .tsv 词库。",
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
                                CodetableBadgePill(
                                    text = if (desc.isBuiltIn) "内置" else "扩展",
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
                                    onFeedback(true, "${desc.displayName} 已${if (checked) "启用" else "停用"}，重新部署后生效")
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
                    text = if (isImportingExt) "导入中..." else "导入扩展词库 (.dict.yaml / .tsv)",
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
                                onFeedback(false, "重新部署失败: ${resultState.message}")
                            } else {
                                onFeedback(true, "✓ 子词库配置已重新部署生效")
                            }
                        } catch (e: Exception) {
                            onFeedback(false, "重新部署失败: ${e.message ?: "未知错误"}")
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
                    text = if (isReloading) "部署中..." else "重新部署",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "置顶 ${rules.pinnedByCode.values.sumOf { it.size }} 条 · 屏蔽 ${rules.blockedWords.size} 词",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = { refreshRules() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("刷新", style = MaterialTheme.typography.labelMedium)
                }
                if (rules.pinnedByCode.isNotEmpty() || rules.blockedWords.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            applyShadowMutation("✓ 已清空全部置顶与屏蔽规则") { it.clearAll() }
                            Toast.makeText(context, "✓ 已清空全部阴影规则", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("清空全部", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (rules.pinnedByCode.isEmpty() && rules.blockedWords.isEmpty()) {
            Text(
                text = "暂无置顶或屏蔽规则。打字时长按候选词即可快速置顶首选或屏蔽词条。",
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
                        actionLabel = "取消置顶",
                        onAction = {
                            applyShadowMutation("已取消置顶「$word ($code)」") {
                                it.unpinWord(code, word)
                            }
                        }
                    )
                }
            }
            rules.blockedWords.forEach { word ->
                ShadowRuleRow(
                    tag = "已屏蔽",
                    tagHighlight = false,
                    word = word,
                    actionLabel = "取消屏蔽",
                    onAction = {
                        applyShadowMutation("已取消屏蔽「$word」") {
                            it.unblockWord(word)
                        }
                    }
                )
            }
        }
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
