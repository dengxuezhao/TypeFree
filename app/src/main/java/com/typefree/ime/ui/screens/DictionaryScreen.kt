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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.DictionarySource
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SnippetMatcher
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.ime.flypy.FlypyDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private val AI_DEV_PRESET_PACK = listOf(
    DictionaryEntry(
        term = "Cursor",
        wrongVariants = "科瑟, cursor",
        note = "AI 代码编辑器",
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "Antigravity",
        wrongVariants = "安提格拉维蒂, anti gravity",
        note = "Agentic AI 编程环境",
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "Supabase",
        wrongVariants = "苏帕贝斯, super base",
        note = "开源后端云服务",
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "Kubernetes",
        wrongVariants = "库伯内蒂斯, k8s",
        note = "容器编排系统",
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "RAG",
        wrongVariants = "瑞格, R A G",
        note = "检索增强生成",
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "Agent",
        wrongVariants = "艾真特, agent智能体",
        note = "AI 自主智能体",
        source = DictionarySource.MANUAL
    )
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictionaryScreen(repository: AppRepository) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState()
    val dictionary = settings.dictionary
    val snippets = settings.snippets

    // 0 = 语音热词, 1 = 编码短语 ;xx, 2 = 小鹤/Rime 词库
    var primarySectionTab by rememberSaveable { mutableIntStateOf(0) }
    var showSnippetDialog by remember { mutableStateOf(false) }
    var editingSnippet by remember { mutableStateOf<SnippetEntry?>(null) }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 0 = 全部, 1 = 自动学习 Auto-added, 2 = 手动添加 Manual
    var selectedSourceTab by rememberSaveable { mutableIntStateOf(0) }

    var isBatchMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    var showEditorDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<DictionaryEntry?>(null) }
    var showBatchImportDialog by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    val flypyDict by TypeFreeApp.instance.flypyDictionary.collectAsState()
    val rimeDeployState by TypeFreeApp.instance.rimeDataManager.deploymentState.collectAsState()
    val rimeScope = rememberCoroutineScope()
    val isCustomActive = remember(flypyDict) {
        FlypyDictionary.isCustomDictionaryActive(context)
    }
    var showRimeImportDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }

    val rimeZipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            rimeScope.launch {
                try {
                    val readyState = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            TypeFreeApp.instance.rimeDataManager.importRimeSchemaZipSync(stream)
                        } ?: throw IllegalArgumentException("无法读取所选 ZIP 文件")
                    }
                    repository.updateSettings { it.copy(inputScheme = InputScheme.RIME_CUSTOM) }
                    val label = (readyState as? RimeDeploymentState.Ready)?.customSchemaName ?: "自定义 Rime 方案"
                    Toast.makeText(context, "✓ 已导入并部署「$label」", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "导入 Rime 方案包失败: ${e.message ?: "格式错误"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    val enabledCount = remember(dictionary) { dictionary.count { it.enabled } }
    val autoCount = remember(dictionary) {
        dictionary.count { it.source == DictionarySource.AUTO_LEARNED }
    }
    val manualCount = remember(dictionary) {
        dictionary.count { it.source == DictionarySource.MANUAL }
    }

    val filteredEntries = remember(dictionary, searchQuery, selectedSourceTab) {
        val q = searchQuery.trim()
        dictionary.filter { entry ->
            val sourceMatch = when (selectedSourceTab) {
                1 -> entry.source == DictionarySource.AUTO_LEARNED
                2 -> entry.source == DictionarySource.MANUAL
                else -> true
            }
            val queryMatch = if (q.isEmpty()) {
                true
            } else {
                entry.term.contains(q, ignoreCase = true) ||
                    entry.wrongVariants.contains(q, ignoreCase = true) ||
                    entry.note.contains(q, ignoreCase = true)
            }
            sourceMatch && queryMatch
        }
    }

    // Keep selectedIds valid against current dictionary
    val validSelectedIds = remember(selectedIds, dictionary) {
        val allIds = dictionary.map { it.id }.toSet()
        selectedIds.intersect(allIds)
    }

    val allFilteredSelected = filteredEntries.isNotEmpty() &&
        filteredEntries.all { it.id in validSelectedIds }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Top Header + Batch Mode Toggle
        item(key = "dict_header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                text = when (primarySectionTab) {
                                    0 -> "语音热词"
                                    1 -> "编码短语 ;xx"
                                    else -> "小鹤/Rime 词库"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                val badgeText = when (primarySectionTab) {
                                    0 -> "已启用 $enabledCount"
                                    1 -> "已启用 ${snippets.count { it.enabled }}"
                                    else -> "${flypyDict.codeCount} 编码"
                                }
                                Text(
                                    text = badgeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Text(
                            text = when (primarySectionTab) {
                                0 -> "人名、品牌、技术术语声学热词偏置与自动改错学习"
                                1 -> "说出口令或敲小鹤 ;xx 快捷码，0ms 瞬时展开邮箱、地址与常用长文"
                                else -> "小鹤音形四码 TSV 码表、雾凇拼音完整词库与 Rime 自定义方案包管理"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (primarySectionTab == 0 && dictionary.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                isBatchMode = !isBatchMode
                                if (!isBatchMode) {
                                    selectedIds = emptySet()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isBatchMode) "完成" else "批量选择",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // 3-Segment Primary Switcher: 语音热词 | 编码短语 ;xx | 小鹤/Rime 词库 (R5.2)
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
                        DictPrimaryModePill(
                            title = "语音热词 (${dictionary.size})",
                            selected = primarySectionTab == 0,
                            onClick = { primarySectionTab = 0 },
                            modifier = Modifier.weight(1f)
                        )
                        DictPrimaryModePill(
                            title = "编码短语 ;xx (${snippets.size})",
                            selected = primarySectionTab == 1,
                            onClick = {
                                primarySectionTab = 1
                                isBatchMode = false
                            },
                            modifier = Modifier.weight(1f)
                        )
                        DictPrimaryModePill(
                            title = "小鹤/Rime 词库",
                            selected = primarySectionTab == 2,
                            onClick = {
                                primarySectionTab = 2
                                isBatchMode = false
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (primarySectionTab == 1) {
            item(key = "snippets_intro_and_actions") {
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
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = TypeFreeEmerald.copy(alpha = 0.10f),
                            border = BorderStroke(0.8.dp, TypeFreeEmerald.copy(alpha = 0.40f))
                        ) {
                            Text(
                                text = "用于键盘打字输入 `;` + 缩写码秒出长句；若需点击卡片直接粘贴，请使用键盘剪贴板置顶常用语",
                                style = MaterialTheme.typography.labelMedium,
                                color = TypeFreeEmerald,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)
                            )
                        }
                        Text(
                            text = "双通道瞬时展开（0ms 免经过大模型）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "• 语音触发：按住麦克风说出设定的口令（如「发我的邮箱」），识别命中后直接上屏完整文本，零延迟零幻觉。\n• 小鹤编码触发：在「鹤」模式下输入快捷码（推荐以分号开头，如 ;sd、;yx、;dz），唯一候选自动上屏且不影响内置 ;q / ;w 快符。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    editingSnippet = null
                                    showSnippetDialog = true
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
                                Text("新建口令短语", fontWeight = FontWeight.Bold)
                            }
                            if (snippets.isEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        repository.updateSettings {
                                            it.copy(snippets = DefaultPresets.defaultSnippets())
                                        }
                                        Toast.makeText(context, "已载入示例短语模板", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("载入模板")
                                }
                            }
                        }
                    }
                }
            }

            if (snippets.isEmpty()) {
                item(key = "snippets_empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "尚无口令短语",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "点击上方「新建口令短语」添加邮箱、地址、发票抬头或高频客服回复。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(snippets, key = { "snip_${it.id}" }) { snip ->
                    SnippetEntryCard(
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
                            Toast.makeText(context, "已删除短语「${snip.title}」", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        } else if (primarySectionTab == 2) {

        // 1.5 Custom Rime / Yinxing IME Engine Table Card (Dedicated Segment 2)
        item(key = "dict_rime_custom_card") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
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
                                tint = if (isCustomActive) TypeFreeEmerald else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "四码音形 / Rime 码表",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isCustomActive || settings.inputScheme.usesRime) TypeFreeEmerald.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = when {
                                    settings.inputScheme.usesRime -> "当前：${settings.inputScheme.label} (音形备选 ${flypyDict.codeCount} 编码)"
                                    isCustomActive -> "自定义音形 (${flypyDict.codeCount} 编码)"
                                    else -> "内置四码表 (${flypyDict.codeCount} 编码)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isCustomActive || settings.inputScheme.usesRime) TypeFreeEmerald
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        text = when {
                            settings.inputScheme.usesRime ->
                                "当前键盘激活「${settings.inputScheme.label}」（由上方原生 librime 1.17.0 引擎驱动）。本卡片管理「小鹤音形（四码）」方案的 TSV 顶功码表，导入后切到小鹤音形即可生效。"
                            isCustomActive ->
                                "当前已挂载私有音形码表，单字与词组在键盘中优先生效。可随时导入新码表或一键恢复内置词库。"
                            else ->
                                "当前使用内置开源四码词库。支持导入您个人的 TSV 码表（如小鹤音形、五笔、虎码等）实现四码定长顶功输入。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showRimeImportDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text(
                                text = "导入自定义码表 (TSV)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (isCustomActive) {
                            OutlinedButton(
                                onClick = { showRestoreConfirmDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "恢复默认",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    )

                    // Rime Deployment Status & Progress Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val rimeStatusText = when (val st = rimeDeployState) {
                            is RimeDeploymentState.Idle -> "Rime 引擎：待部署"
                            is RimeDeploymentState.Deploying -> st.message
                            is RimeDeploymentState.Ready -> {
                                val tier = if (st.hasFullRimeIce) "完整雾凇词库" else "内置精简词库"
                                val customSuffix = st.customSchemaName?.let { " · $it" }.orEmpty()
                                val timeSuffix = if (st.deployDurationMs > 0L) " · ${st.deployDurationMs}ms" else ""
                                "Rime 就绪：$tier (${st.wordCount} 词条$timeSuffix)$customSuffix"
                            }
                            is RimeDeploymentState.Error -> "Rime 状态：${st.message}"
                        }
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (rimeDeployState is RimeDeploymentState.Deploying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            Text(
                                text = rimeStatusText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (rimeDeployState is RimeDeploymentState.Error) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    // On-demand full rime-ice download + Rime .zip schema package import
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val isDeployingRime = rimeDeployState is RimeDeploymentState.Deploying
                        val hasFullIce = (rimeDeployState as? RimeDeploymentState.Ready)?.hasFullRimeIce == true
                        FilledTonalButton(
                            onClick = {
                                rimeScope.launch {
                                    try {
                                        val ready = TypeFreeApp.instance.rimeDataManager.downloadFullRimeIceDictionary()
                                        val count = (ready as? RimeDeploymentState.Ready)?.wordCount ?: 0
                                        Toast.makeText(
                                            context,
                                            "✓ 完整雾凇拼音词库已部署 ($count 词条)",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "雾凇词库下载失败: ${e.message ?: "请检查网络"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            enabled = !isDeployingRime,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (hasFullIce) "更新雾凇完整词库" else "按需下载雾凇完整词库",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                rimeZipPickerLauncher.launch(
                                    arrayOf("application/zip", "application/x-zip-compressed", "*/*")
                                )
                            },
                            enabled = !isDeployingRime,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "导入 Rime 方案包 (.zip)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // 1-Row Advanced Toggle: Fixed Candidate Order (`fixedCandidateOrder`)
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
                                text = "固定候选顺序",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "开启后保持词表静态默认排序，关闭动态调频（适合肌肉记忆打字）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                        Switch(
                            checked = settings.fixedCandidateOrder == true,
                            onCheckedChange = { fixed ->
                                repository.updateSettings { it.copy(fixedCandidateOrder = fixed) }
                            }
                        )
                    }
                }
            }
        }
        } else {

        // 2. V2.9 Flagship Feature: Auto-Learn Corrections Switch Card
        item(key = "dict_auto_learn_card") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.52f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.Sparkles,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp)
                            )
                            Text(
                                text = "自动学习打字改错 (Auto-learn)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "当使用内置键盘修正刚口述的专有名词、人名或大小写（如将 deep seek 改为 DeepSeek）时，自动提取差异并加入个人词典。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }

                    Switch(
                        checked = settings.autoLearnDictionary,
                        onCheckedChange = { enabled ->
                            repository.updateSettings { it.copy(autoLearnDictionary = enabled) }
                        }
                    )
                }
            }
        }

        // 3. Action Buttons + Search Box + 3-Segment Source Filter Capsules
        item(key = "dict_controls") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            editingEntry = null
                            showEditorDialog = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("新建词条", fontWeight = FontWeight.Bold)
                    }

                    FilledTonalButton(
                        onClick = { showBatchImportDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "批量文本 / 预设词包导入",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("搜索标准词、同音误识别词或备注...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "清除搜索"
                                )
                            }
                        }
                    } else null
                )

                // 3-Segment Source Filter Capsules
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DictSourceFilterPill(
                            label = "全部 (${dictionary.size})",
                            selected = selectedSourceTab == 0,
                            onClick = { selectedSourceTab = 0 }
                        )
                        DictSourceFilterPill(
                            label = "自动学习 Auto-added ($autoCount)",
                            selected = selectedSourceTab == 1,
                            onClick = { selectedSourceTab = 1 }
                        )
                        DictSourceFilterPill(
                            label = "手动添加 Manual ($manualCount)",
                            selected = selectedSourceTab == 2,
                            onClick = { selectedSourceTab = 2 }
                        )
                    }
                }

                // Batch Selection Action Toolbar
                AnimatedVisibility(visible = isBatchMode && dictionary.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(enabled = filteredEntries.isNotEmpty()) {
                                    selectedIds = if (allFilteredSelected) {
                                        validSelectedIds - filteredEntries.map { it.id }.toSet()
                                    } else {
                                        validSelectedIds + filteredEntries.map { it.id }
                                    }
                                }
                            ) {
                                Checkbox(
                                    checked = allFilteredSelected,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) {
                                            validSelectedIds + filteredEntries.map { it.id }
                                        } else {
                                            validSelectedIds - filteredEntries.map { it.id }.toSet()
                                        }
                                    }
                                )
                                Text(
                                    text = if (allFilteredSelected) "取消全选" else "全选 (${filteredEntries.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Button(
                                onClick = { showBatchDeleteConfirm = true },
                                enabled = validSelectedIds.isNotEmpty(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "删除已选 (${validSelectedIds.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Dictionary Entries List or Empty State
        if (filteredEntries.isEmpty()) {
            item(key = "dict_empty_state") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.Book,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(34.dp)
                        )
                        Text(
                            text = when {
                                dictionary.isEmpty() -> "个人词典为空"
                                selectedSourceTab == 1 && searchQuery.isEmpty() -> "尚无自动学习的改错词条"
                                else -> "未找到匹配的词条"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                selectedSourceTab == 1 && searchQuery.isEmpty() ->
                                    "当你在口述完成后使用内置键盘修正专有名词时，系统会自动学习并在此展示。"
                                dictionary.isEmpty() ->
                                    "添加同事姓名、产品代号或技术词汇，让语音识别与润色永不写错字。"
                                else ->
                                    "尝试调整搜索词或切换上方来源筛选胶囊。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(filteredEntries, key = { it.id }) { entry ->
                val isSelected = entry.id in validSelectedIds
                DictionaryEntryCard(
                    entry = entry,
                    isBatchMode = isBatchMode,
                    isSelected = isSelected,
                    onToggleSelect = {
                        selectedIds = if (isSelected) {
                            validSelectedIds - entry.id
                        } else {
                            validSelectedIds + entry.id
                        }
                    },
                    onToggleEnabled = { enabled ->
                        repository.updateSettings { current ->
                            current.copy(
                                dictionary = current.dictionary.map {
                                    if (it.id == entry.id) it.copy(enabled = enabled) else it
                                }
                            )
                        }
                    },
                    onEdit = {
                        editingEntry = entry
                        showEditorDialog = true
                    },
                    onDelete = {
                        repository.deleteDictionaryEntries(setOf(entry.id))
                        Toast.makeText(context, "已删除词条「${entry.term}」", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        }

        item(key = "dict_bottom_spacer") {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showSnippetDialog) {
        SnippetEditorDialog(
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
                    if (editingSnippet == null) "已新建短语「${saved.title}」" else "已更新短语「${saved.title}」",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    // Create / Edit Entry Dialog
    if (showEditorDialog) {
        DictionaryEntryEditorDialog(
            initialEntry = editingEntry,
            onDismiss = { showEditorDialog = false },
            onSave = { saved ->
                repository.updateSettings { current ->
                    val exists = current.dictionary.any { it.id == saved.id }
                    val updated = if (exists) {
                        current.dictionary.map { if (it.id == saved.id) saved else it }
                    } else {
                        listOf(saved) + current.dictionary
                    }
                    current.copy(dictionary = updated)
                }
                showEditorDialog = false
                Toast.makeText(
                    context,
                    if (editingEntry == null) "已添加词条「${saved.term}」" else "已更新词条「${saved.term}」",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    // Batch Import Dialog (Multi-line Text + AI/Dev Preset Pack)
    if (showBatchImportDialog) {
        BatchImportDictionaryDialog(
            existingTerms = remember(dictionary) {
                dictionary.map { it.term.trim().lowercase() }.toSet()
            },
            onDismiss = { showBatchImportDialog = false },
            onImportEntries = { importedList ->
                if (importedList.isNotEmpty()) {
                    repository.updateSettings { current ->
                        val existingLower = current.dictionary.map { it.term.trim().lowercase() }.toSet()
                        val deduped = importedList.filter { it.term.trim().lowercase() !in existingLower }
                        current.copy(dictionary = deduped + current.dictionary)
                    }
                    Toast.makeText(
                        context,
                        "成功导入 ${importedList.size} 条专属词条",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(context, "所选词条均已存在，无需重复导入", Toast.LENGTH_SHORT).show()
                }
                showBatchImportDialog = false
            }
        )
    }

    // Batch Delete Confirmation Dialog
    if (showBatchDeleteConfirm && validSelectedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("批量删除词条") },
            text = {
                Text("确定要删除已选中的 ${validSelectedIds.size} 个词条吗？删除后无法恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val count = validSelectedIds.size
                        repository.deleteDictionaryEntries(validSelectedIds)
                        selectedIds = emptySet()
                        showBatchDeleteConfirm = false
                        if (dictionary.size <= count) {
                            isBatchMode = false
                        }
                        Toast.makeText(context, "已批量删除 $count 个词条", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text("确认删除 ($countText)".replace("$countText", validSelectedIds.size.toString()))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Custom Rime / Yinxing Table Import Dialog
    if (showRimeImportDialog) {
        RimeDictionaryImportDialog(
            onDismiss = { showRimeImportDialog = false }
        )
    }

    // Restore Default Dictionary Dialog
    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = { Text("恢复内置开源词库") },
            text = {
                Text("确定要清除当前自定义私有码表并恢复默认内置开源双拼词库吗？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
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
                ) {
                    Text("确认恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

private const val countText = "{N}"

@Composable
private fun DictSourceFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictionaryEntryCard(
    entry: DictionaryEntry,
    isBatchMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val variantTokens = remember(entry.wrongVariants) {
        entry.wrongVariants
            .split(',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    val isAutoLearned = entry.source == DictionarySource.AUTO_LEARNED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isBatchMode) {
                    Modifier.clickable { onToggleSelect() }
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isBatchMode && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                entry.enabled -> MaterialTheme.colorScheme.surface
                else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)
            }
        ),
        border = BorderStroke(
            width = if (isBatchMode && isSelected) 1.5.dp else 1.dp,
            color = when {
                isBatchMode && isSelected -> MaterialTheme.colorScheme.primary
                entry.enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.48f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    if (isBatchMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect() }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = entry.term,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (entry.enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isAutoLearned) {
                                    TypeFreeEmerald.copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = if (isAutoLearned) "✨ 自动学习" else "手动添加",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isAutoLearned) {
                                        TypeFreeEmerald
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (entry.note.isNotBlank()) {
                            Text(
                                text = entry.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (!isBatchMode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Switch(
                            checked = entry.enabled,
                            onCheckedChange = onToggleEnabled
                        )
                        IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "编辑词条",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除词条",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (variantTokens.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "同音纠错：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    variantTokens.forEach { wrong ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "$wrong → ${entry.term}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DictionaryEntryEditorDialog(
    initialEntry: DictionaryEntry?,
    onDismiss: () -> Unit,
    onSave: (DictionaryEntry) -> Unit
) {
    var term by rememberSaveable(initialEntry?.id) { mutableStateOf(initialEntry?.term ?: "") }
    var wrongVariants by rememberSaveable(initialEntry?.id) { mutableStateOf(initialEntry?.wrongVariants ?: "") }
    var note by rememberSaveable(initialEntry?.id) { mutableStateOf(initialEntry?.note ?: "") }

    val isValid = term.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialEntry == null) "新建个人词典词条" else "编辑个人词典词条")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text("标准词 / 专有名词 *") },
                    placeholder = { Text("如：TypeFree、DeepSeek、张伟明") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = wrongVariants,
                    onValueChange = { wrongVariants = it },
                    label = { Text("常见同音误识别词（可选，逗号分隔）") },
                    placeholder = { Text("如：太普利斯, type less") },
                    minLines = 2,
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注说明（可选）") },
                    placeholder = { Text("如：产品名 / 同事姓名 / 技术术语") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = isValid,
                onClick = {
                    val normalizedVariants = wrongVariants
                        .replace('，', ',')
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(", ")

                    val entry = if (initialEntry != null) {
                        initialEntry.copy(
                            term = term.trim(),
                            wrongVariants = normalizedVariants,
                            note = note.trim()
                        )
                    } else {
                        DictionaryEntry(
                            term = term.trim(),
                            wrongVariants = normalizedVariants,
                            note = note.trim(),
                            enabled = true,
                            source = DictionarySource.MANUAL
                        )
                    }
                    onSave(entry)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("保存词条")
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
private fun BatchImportDictionaryDialog(
    existingTerms: Set<String>,
    onDismiss: () -> Unit,
    onImportEntries: (List<DictionaryEntry>) -> Unit
) {
    var rawLines by rememberSaveable { mutableStateOf("") }

    val parsedFromText = remember(rawLines, existingTerms) {
        parseBatchDictionaryLines(rawLines, existingTerms)
    }
    val missingPresetEntries = remember(existingTerms) {
        AI_DEV_PRESET_PACK.filter { it.term.trim().lowercase() !in existingTerms }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量导入专属词条") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "每行填写一个词条。支持仅填标准词，或用 `=` / `:` 附带同音误词：\n示例：OpenTypeFree = 欧盆太普, open typefree",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                OutlinedTextField(
                    value = rawLines,
                    onValueChange = { rawLines = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 8,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = {
                        Text("Qwen2.5 = 千问二点五, qwen\nSiliconFlow = 硅基流动\n张伟明 = 张伟民")
                    }
                )

                if (rawLines.isNotBlank()) {
                    Text(
                        text = "已解析到 ${parsedFromText.size} 个可导入的新词条（已自动去重）",
                        style = MaterialTheme.typography.labelMedium,
                        color = TypeFreeEmerald
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                FilledTonalButton(
                    onClick = { onImportEntries(missingPresetEntries) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (missingPresetEntries.isNotEmpty()) {
                            "一键导入内置 AI / 开发术语包 (${missingPresetEntries.size} 条)"
                        } else {
                            "内置 AI / 开发术语包已全部导入"
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = parsedFromText.isNotEmpty(),
                onClick = { onImportEntries(parsedFromText) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("导入文本词条 (${parsedFromText.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private fun parseBatchDictionaryLines(
    rawText: String,
    existingLowerTerms: Set<String>
): List<DictionaryEntry> {
    if (rawText.isBlank()) return emptyList()
    val seen = existingLowerTerms.toMutableSet()
    val results = mutableListOf<DictionaryEntry>()

    rawText.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//")) return@forEach

        val noteSplit = trimmed.split('#', limit = 2)
        val mainPart = noteSplit[0].trim()
        val note = if (noteSplit.size > 1) noteSplit[1].trim() else "批量导入"

        val pairSplit = mainPart.split(Regex("\\s*(=|:|：|->)\\s*"), limit = 2)
        val term = pairSplit[0].trim()
        if (term.isEmpty()) return@forEach

        val lower = term.lowercase()
        if (lower in seen) return@forEach
        seen.add(lower)

        val variants = if (pairSplit.size > 1) {
            pairSplit[1]
                .replace('，', ',')
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
        } else {
            ""
        }

        results += DictionaryEntry(
            term = term,
            wrongVariants = variants,
            note = note,
            enabled = true,
            source = DictionarySource.MANUAL
        )
    }

    return results
}

@Composable
fun RimeDictionaryImportDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var mainDictUri by remember { mutableStateOf<Uri?>(null) }
    var mainDictFileName by remember { mutableStateOf("") }
    var dzDictUri by remember { mutableStateOf<Uri?>(null) }
    var dzDictFileName by remember { mutableStateOf("") }

    var isPasteExpanded by remember { mutableStateOf(false) }
    var rawTsvText by rememberSaveable { mutableStateOf("") }
    var rawDzText by rememberSaveable { mutableStateOf("") }

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
            mainDictFileName = queryFileName(context, uri) ?: uri.lastPathSegment ?: "主词库文件.tsv"
            errorMessage = null
        }
    }

    val dzDictLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            dzDictUri = uri
            dzDictFileName = queryFileName(context, uri) ?: uri.lastPathSegment ?: "反查表文件.tsv"
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isImporting) onDismiss()
        },
        title = {
            Text(
                text = "导入自定义音形 / Rime 码表",
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
                    text = "通过系统安全文件选择器选取手机中的 TSV/TXT 码表文件。覆盖写入应用私有存储空间，纯本地生效，保护隐私。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                // 1. Main Dictionary File Card
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
                            text = "主词库文件 (必需)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "格式: 编码\\t候选词1\\t候选词2...（如小鹤音形、五笔、虎码等）",
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
                                        contentDescription = "清除",
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
                                Text("选择主词库文件 (.tsv/.txt)")
                            }
                        }
                    }
                }

                // 2. Reverse Lookup Single-Char Table (Optional)
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
                            text = "单字四码反查表 (可选)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "格式: 4码编码\\t单字（用于 ` 通配符查音查形）",
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
                                        contentDescription = "清除",
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
                                Text("选择反查表文件 (可选)")
                            }
                        }
                    }
                }

                // 3. Fallback Small Text Paste for Debugging (Collapsible)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isPasteExpanded = !isPasteExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isPasteExpanded) "▼ 收起文本直接粘贴调试区" else "▶ 展开文本直接粘贴调试区 (限小片段)",
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
                            label = { Text("粘贴主词库小片段 (TSV)") },
                            placeholder = { Text("uijm\\t时间\\t实践\\nxhyx\\t小鹤音形") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp),
                            maxLines = 4,
                            enabled = !isImporting
                        )
                    }
                }

                // Error message
                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Loading progress indicator
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
                            text = "正在导入并构建内存索引，请稍候...",
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
                                            ?: throw IllegalStateException("无法读取选中的主词库文件")
                                    }
                                    rawTsvText.isNotBlank() -> {
                                        ByteArrayInputStream(rawTsvText.toByteArray(Charsets.UTF_8))
                                    }
                                    else -> throw IllegalStateException("请选择主词库文件或输入文本")
                                }

                                val dzIn = when {
                                    dzDictUri != null -> {
                                        context.contentResolver.openInputStream(dzDictUri!!)
                                    }
                                    rawDzText.isNotBlank() -> {
                                        ByteArrayInputStream(rawDzText.toByteArray(Charsets.UTF_8))
                                    }
                                    else -> null
                                }

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
                            Toast.makeText(context, "自定义码表导入成功！输入法已热挂载", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (e: Exception) {
                            errorMessage = "导入失败: ${e.message}"
                        } finally {
                            isImporting = false
                        }
                    }
                },
                enabled = canSubmit
            ) {
                Text(if (isImporting) "导入中..." else "确认导入挂载")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isImporting
            ) {
                Text("关闭")
            }
        }
    )
}

private fun queryFileName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else null
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun DictPrimaryModePill(
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
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnippetEntryCard(
    snippet: SnippetEntry,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val voiceList = remember(snippet.voiceTriggers) {
        SnippetMatcher.parseTriggerList(snippet.voiceTriggers)
    }
    val normCode = remember(snippet.flypyCode) {
        SnippetMatcher.normalizeFlypyCode(snippet.flypyCode)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (snippet.enabled) {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.52f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = snippet.title.ifBlank { "未命名短语" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (normCode.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "鹤 $normCode",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Switch(
                    checked = snippet.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            if (voiceList.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    voiceList.forEach { trig ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        ) {
                            Text(
                                text = "🎙️ 「$trig」",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Text(
                    text = snippet.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑")
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SnippetEditorDialog(
    initialSnippet: SnippetEntry?,
    onDismiss: () -> Unit,
    onSave: (SnippetEntry) -> Unit
) {
    var title by rememberSaveable { mutableStateOf(initialSnippet?.title.orEmpty()) }
    var voiceTriggers by rememberSaveable { mutableStateOf(initialSnippet?.voiceTriggers.orEmpty()) }
    var flypyCode by rememberSaveable { mutableStateOf(initialSnippet?.flypyCode.orEmpty()) }
    var expandedContent by rememberSaveable { mutableStateOf(initialSnippet?.content.orEmpty()) }

    val normCode = SnippetMatcher.normalizeFlypyCode(flypyCode)
    val hasAnyTrigger = voiceTriggers.trim().isNotEmpty() || normCode.isNotEmpty()
    val canSave = expandedContent.trim().isNotEmpty() && hasAnyTrigger

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialSnippet == null) "新建口令短语" else "编辑口令短语") },
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
                    label = { Text("短语名称") },
                    placeholder = { Text("如：工作邮箱、默认收货地址") }
                )

                OutlinedTextField(
                    value = voiceTriggers,
                    onValueChange = { voiceTriggers = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("语音触发口令（逗号分隔，选填）") },
                    placeholder = { Text("如：发我的邮箱, 我的工作邮箱") }
                )

                OutlinedTextField(
                    value = flypyCode,
                    onValueChange = { flypyCode = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("小鹤快捷码（推荐 ; 开头 3 码，选填）") },
                    placeholder = { Text("如：;yx、;dz、;sd") }
                )

                OutlinedTextField(
                    value = expandedContent,
                    onValueChange = { expandedContent = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("展开后的完整正文 *") },
                    placeholder = { Text("输入命中后要直接上屏的完整内容…") }
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
                Text("保存并启用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
