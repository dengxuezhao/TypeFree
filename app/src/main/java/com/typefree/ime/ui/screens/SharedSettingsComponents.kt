package com.typefree.ime.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.DictionarySource
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SnippetMatcher
import com.typefree.ime.network.AppUpdateChecker
import com.typefree.ime.network.UpdateCheckInfo
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald

@Composable
fun StudioSubTabPill(
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

@Composable
fun PreferenceSwitchRow(
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SlashSnippetsManagementSection(
    repository: AppRepository,
    snippets: List<SnippetEntry>
) {
    val context = LocalContext.current
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
                        text = "/ 编码短语 (${snippets.count { it.enabled }}/${snippets.size})",
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
                        Text("新建", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = "空闲态上滑句号（.）出 / 触发短码（如 /sd、/yx），亦可语音口令展开；点击下方卡片可编辑或删除",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp
                )

                if (snippets.size > 4) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = { Text("搜索短码、标题或内容…", fontSize = 12.sp) },
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
                                        contentDescription = "清除",
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                repository.updateSettings {
                                    it.copy(snippets = DefaultPresets.defaultSnippets())
                                }
                                Toast.makeText(context, "已载入示例短语模板", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("载入示例短语模板")
                        }
                    }
                } else if (visibleSnippets.isEmpty()) {
                    Text(
                        text = "未找到匹配「$searchQuery」的编码短语",
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
                                    CompactSnippetGridTile(
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
                                    text = if (expandedAll) "收起列表" else "展开全部 ${filteredSnippets.size} 条短语",
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
        SharedSnippetEditorDialog(
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
}

@Composable
private fun CompactSnippetGridTile(
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
                        text = normCode.ifEmpty { "语音" },
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
                        contentDescription = "删除短语",
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
                text = snippet.title.ifBlank { normCode.ifEmpty { "未命名短语" } },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharedSnippetEntryCard(
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
                                text = normCode,
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
fun SharedSnippetEditorDialog(
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
        title = { Text(if (initialSnippet == null) "新建 `/` 编码短语" else "编辑 `/` 编码短语") },
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
                    label = { Text("编码快捷码（`/` 引导，如 `/sd`、`/yx`，选填）") },
                    placeholder = { Text("如：/yx、/dz、/sd（旧版 ;xx 自动转 /xx）") },
                    supportingText = if (normCode.isNotEmpty()) {
                        { Text("将保存为：$normCode") }
                    } else null
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

private val SHARED_AI_DEV_PRESET_PACK = listOf(
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
fun VoiceHotwordsManagementSection(
    repository: AppRepository
) {
    val context = LocalContext.current
    val settings by repository.settings.collectAsState()
    val dictionary = settings.dictionary

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedSourceTab by rememberSaveable { mutableIntStateOf(0) }
    var isBatchMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    var showEditorDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<DictionaryEntry?>(null) }
    var showBatchImportDialog by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

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
    val validSelectedIds = remember(selectedIds, dictionary) {
        val allIds = dictionary.map { it.id }.toSet()
        selectedIds.intersect(allIds)
    }
    val allFilteredSelected = filteredEntries.isNotEmpty() &&
        filteredEntries.all { it.id in validSelectedIds }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Auto-learn switch card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                            text = "自动学习打字改错",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "当使用内置键盘修正刚口述的专有名词、人名或大小写（如将 deep seek 改为 DeepSeek）时，自动提取差异并加入语音热词。",
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

        // Actions + Search + Filter
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "语音热词列表 (${dictionary.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (dictionary.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        isBatchMode = !isBatchMode
                        if (!isBatchMode) selectedIds = emptySet()
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
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("新建热词", fontWeight = FontWeight.Bold)
            }

            FilledTonalButton(
                onClick = { showBatchImportDialog = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "批量文本 / 预设词包导入",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
            leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "清除搜索")
                    }
                }
            } else null
        )

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
                HotwordFilterPill("全部 (${dictionary.size})", selectedSourceTab == 0) { selectedSourceTab = 0 }
                HotwordFilterPill("自动学习 ($autoCount)", selectedSourceTab == 1) { selectedSourceTab = 1 }
                HotwordFilterPill("手动添加 ($manualCount)", selectedSourceTab == 2) { selectedSourceTab = 2 }
            }
        }

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
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("删除已选 (${validSelectedIds.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (filteredEntries.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.Book,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = when {
                            dictionary.isEmpty() -> "语音热词为空"
                            selectedSourceTab == 1 && searchQuery.isEmpty() -> "尚无自动学习的改错词条"
                            else -> "未找到匹配的热词"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "添加同事姓名、产品代号或技术词汇，让语音识别与润色永不写错字。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            filteredEntries.forEach { entry ->
                val isSelected = entry.id in validSelectedIds
                SharedHotwordEntryCard(
                    entry = entry,
                    isBatchMode = isBatchMode,
                    isSelected = isSelected,
                    onToggleSelect = {
                        selectedIds = if (isSelected) validSelectedIds - entry.id else validSelectedIds + entry.id
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
                        Toast.makeText(context, "已删除热词「${entry.term}」", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    if (showEditorDialog) {
        SharedHotwordEditorDialog(
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
                    if (editingEntry == null) "已添加热词「${saved.term}」" else "已更新热词「${saved.term}」",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    if (showBatchImportDialog) {
        SharedBatchImportHotwordsDialog(
            existingTerms = remember(dictionary) { dictionary.map { it.term.trim().lowercase() }.toSet() },
            onDismiss = { showBatchImportDialog = false },
            onImportEntries = { importedList ->
                if (importedList.isNotEmpty()) {
                    repository.updateSettings { current ->
                        val existingLower = current.dictionary.map { it.term.trim().lowercase() }.toSet()
                        val deduped = importedList.filter { it.term.trim().lowercase() !in existingLower }
                        current.copy(dictionary = deduped + current.dictionary)
                    }
                    Toast.makeText(context, "成功导入 ${importedList.size} 条语音热词", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "所选词条均已存在，无需重复导入", Toast.LENGTH_SHORT).show()
                }
                showBatchImportDialog = false
            }
        )
    }

    if (showBatchDeleteConfirm && validSelectedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("批量删除热词") },
            text = { Text("确定要删除已选中的 ${validSelectedIds.size} 个热词吗？删除后无法恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        val count = validSelectedIds.size
                        repository.deleteDictionaryEntries(validSelectedIds)
                        selectedIds = emptySet()
                        showBatchDeleteConfirm = false
                        if (dictionary.size <= count) isBatchMode = false
                        Toast.makeText(context, "已批量删除 $count 个热词", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text("确认删除 (${validSelectedIds.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun HotwordFilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
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
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedHotwordEntryCard(
    entry: DictionaryEntry,
    isBatchMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val variantTokens = remember(entry.wrongVariants) {
        entry.wrongVariants.split(',', '，', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }
    }
    val isAutoLearned = entry.source == DictionarySource.AUTO_LEARNED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isBatchMode) Modifier.clickable { onToggleSelect() } else Modifier),
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
                        Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
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
                                color = if (entry.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isAutoLearned) TypeFreeEmerald.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = if (isAutoLearned) "✨ 自动学习" else "手动添加",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isAutoLearned) TypeFreeEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = entry.enabled, onCheckedChange = onToggleEnabled)
                        IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = "编辑热词", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "删除热词", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (variantTokens.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "同音纠错：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    variantTokens.forEach { wrong ->
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
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
private fun SharedHotwordEditorDialog(
    initialEntry: DictionaryEntry?,
    onDismiss: () -> Unit,
    onSave: (DictionaryEntry) -> Unit
) {
    var term by rememberSaveable(initialEntry?.id) { mutableStateOf(initialEntry?.term ?: "") }
    var wrongVariants by rememberSaveable(initialEntry?.id) { mutableStateOf(initialEntry?.wrongVariants ?: "") }
    var note by rememberSaveable(initialEntry?.id) { mutableStateOf(initialEntry?.note ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialEntry == null) "新建语音热词" else "编辑语音热词") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = term.trim().isNotEmpty(),
                onClick = {
                    val normalizedVariants = wrongVariants.replace('，', ',')
                        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                    val entry = initialEntry?.copy(
                        term = term.trim(),
                        wrongVariants = normalizedVariants,
                        note = note.trim()
                    ) ?: DictionaryEntry(
                        term = term.trim(),
                        wrongVariants = normalizedVariants,
                        note = note.trim(),
                        enabled = true,
                        source = DictionarySource.MANUAL
                    )
                    onSave(entry)
                }
            ) { Text("保存词条") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SharedBatchImportHotwordsDialog(
    existingTerms: Set<String>,
    onDismiss: () -> Unit,
    onImportEntries: (List<DictionaryEntry>) -> Unit
) {
    var rawLines by rememberSaveable { mutableStateOf("") }
    val parsedFromText = remember(rawLines, existingTerms) {
        parseSharedHotwordBatchLines(rawLines, existingTerms)
    }
    val missingPresetEntries = remember(existingTerms) {
        SHARED_AI_DEV_PRESET_PACK.filter { it.term.trim().lowercase() !in existingTerms }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量导入语音热词") },
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
                    placeholder = { Text("Qwen2.5 = 千问二点五, qwen\nSiliconFlow = 硅基流动\n张伟明 = 张伟民") }
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
                    Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
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
            Button(enabled = parsedFromText.isNotEmpty(), onClick = { onImportEntries(parsedFromText) }) {
                Text("导入文本词条 (${parsedFromText.size})")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private fun parseSharedHotwordBatchLines(
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
            pairSplit[1].replace('，', ',').split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        } else ""
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SharedTranslationTargetsCard(repository: AppRepository) {
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
                            text = "弧形快译目标语言",
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
                            it.copy(translationTargets = defaults, activeTranslateTarget = defaults.first())
                        }
                        Toast.makeText(context, "已恢复默认翻译目标语言列表", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("默认", style = MaterialTheme.typography.labelMedium)
                }
            }

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
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "共 ${targets.size} 种激活语言",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEachIndexed { index, lang ->
                    val isDefault = lang == activeTarget
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDefault) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                        border = BorderStroke(
                            width = if (isDefault) 1.2.dp else 1.dp,
                            color = if (isDefault) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
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
                                            if (isDefault) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDefault) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = lang,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isDefault) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!isDefault) {
                                    TextButton(
                                        onClick = { repository.updateSettings { it.copy(activeTranslateTarget = lang) } },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) { Text("设为默认", style = MaterialTheme.typography.labelSmall) }
                                }
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val mutable = targets.toMutableList()
                                            val tmp = mutable[index - 1]
                                            mutable[index - 1] = mutable[index]
                                            mutable[index] = tmp
                                            repository.updateSettings { it.copy(translationTargets = mutable) }
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(30.dp)
                                ) { Icon(imageVector = Icons.Default.KeyboardArrowUp, contentDescription = "上移", modifier = Modifier.size(18.dp)) }
                                IconButton(
                                    onClick = {
                                        if (index < targets.lastIndex) {
                                            val mutable = targets.toMutableList()
                                            val tmp = mutable[index + 1]
                                            mutable[index + 1] = mutable[index]
                                            mutable[index] = tmp
                                            repository.updateSettings { it.copy(translationTargets = mutable) }
                                        }
                                    },
                                    enabled = index < targets.lastIndex,
                                    modifier = Modifier.size(30.dp)
                                ) { Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "下移", modifier = Modifier.size(18.dp)) }
                                if (targets.size > 1) {
                                    IconButton(
                                        onClick = {
                                            val remaining = targets.filterIndexed { i, _ -> i != index }
                                            val nextDefault = if (activeTarget == lang) remaining.first() else activeTarget
                                            repository.updateSettings {
                                                it.copy(translationTargets = remaining, activeTranslateTarget = nextDefault)
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
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("添加翻译目标语言", fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showAddLangDialog) {
        SharedAddTranslationLanguageDialog(
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
                            curr.copy(translationTargets = existing + clean, activeTranslateTarget = clean)
                        }
                    }
                    Toast.makeText(context, "已添加并设为默认快译语言：$clean", Toast.LENGTH_SHORT).show()
                }
                showAddLangDialog = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedAddTranslationLanguageDialog(
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
                    label = { Text("或输入自定义语言 / 方言") },
                    placeholder = { Text("如：粤语、Nederlands、商务敬语日文") }
                )
            }
        },
        confirmButton = {
            Button(enabled = effectiveChoice.isNotEmpty(), onClick = { onAddLanguage(effectiveChoice) }) {
                Text("添加语言")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

sealed interface SharedUpdateDialogState {
    data class UpdateAvailable(val info: UpdateCheckInfo) : SharedUpdateDialogState
    data class UpToDate(val info: UpdateCheckInfo) : SharedUpdateDialogState
    data class Error(val message: String) : SharedUpdateDialogState
}

@Composable
fun SharedAppUpdateDialog(
    state: SharedUpdateDialogState,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        is SharedUpdateDialogState.UpdateAvailable -> {
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
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = TypeFreeAmber, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = "当前为私有小鹤全量词库定制包，GitHub 公开 Release APK 仅含公开词表，直接覆盖安装会替换内置私有词库。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "v${info.currentVersion} → v${info.latestVersion}",
                                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                                    fontWeight = FontWeight.Bold
                                )
                                val dateText = info.publishedAt.take(10)
                                if (dateText.isNotBlank()) {
                                    Text(text = dateText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
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
                    Button(onClick = {
                        onOpenUrl(info.apkDownloadUrl ?: info.htmlUrl)
                        onDismiss()
                    }) { Text("浏览器下载 APK") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            onOpenUrl(info.htmlUrl)
                            onDismiss()
                        }) { Text("打开发布页") }
                        TextButton(onClick = onDismiss) { Text("暂不更新") }
                    }
                }
            )
        }
        is SharedUpdateDialogState.UpToDate -> {
            val info = state.info
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("已是最新版本") },
                text = {
                    Text(
                        text = "当前版本 v${info.currentVersion} 已是最新正式版（GitHub 最新版本：${info.latestTag}）。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
                dismissButton = {
                    TextButton(onClick = {
                        onOpenUrl(info.htmlUrl)
                        onDismiss()
                    }) { Text("查看 Releases") }
                }
            )
        }
        is SharedUpdateDialogState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("检查更新失败") },
                text = { Text(text = state.message, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    Button(onClick = {
                        onOpenUrl(AppUpdateChecker.GITHUB_RELEASES_PAGE_URL)
                        onDismiss()
                    }) { Text("手动查看 Releases") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
            )
        }
    }
}

fun openExternalBrowserUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "未能打开系统浏览器，请手动访问 GitHub Releases", Toast.LENGTH_SHORT).show()
    }
}
