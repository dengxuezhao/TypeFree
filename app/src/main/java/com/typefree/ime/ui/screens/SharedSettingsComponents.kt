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
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.DictionarySource
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SnippetMatcher
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.network.AppUpdateChecker
import com.typefree.ime.network.UpdateCheckInfo
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
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
                        text = tr(
                            "/ 编码短语 (${snippets.count { it.enabled }}/${snippets.size})",
                            "/ Snippets (${snippets.count { it.enabled }}/${snippets.size})",
                            "/ 編碼片語 (${snippets.count { it.enabled }}/${snippets.size})"
                        ),
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
                        "Swipe up on period (.) when idle to type `/` codes (e.g. /sd, /yx) or trigger by voice; tap any card to edit or delete",
                        "閒置時上滑句號（.）輸入 / 觸發短碼（如 /sd、/yx），亦可語音口令展開；點選下方卡片可編輯或刪除"
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
                                tr("搜索短码、标题或内容…", "Search code, title, or content…", "搜尋短碼、標題或內容…"),
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                repository.updateSettings {
                                    it.copy(snippets = DefaultPresets.defaultSnippets())
                                }
                                Toast.makeText(
                                    context,
                                    AppStrings.pick(appLang, "已载入示例短语模板", "Sample snippet templates loaded", "已載入範例片語範本"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(tr("载入示例短语模板", "Load Sample Snippets", "載入範例片語範本"))
                        }
                    }
                } else if (visibleSnippets.isEmpty()) {
                    Text(
                        text = tr(
                            "未找到匹配「$searchQuery」的编码短语",
                            "No snippets matching \"$searchQuery\"",
                            "未找到符合「$searchQuery」的編碼片語"
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
                                            Toast.makeText(
                                                context,
                                                AppStrings.pick(
                                                    appLang,
                                                    "已删除短语「${snip.title}」",
                                                    "Deleted snippet \"${snip.title}\"",
                                                    "已刪除片語「${snip.title}」"
                                                ),
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
                                        tr("收起列表", "Collapse", "收起清單")
                                    } else {
                                        tr(
                                            "展开全部 ${filteredSnippets.size} 条短语",
                                            "Show all ${filteredSnippets.size} snippets",
                                            "展開全部 ${filteredSnippets.size} 條片語"
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
                    if (editingSnippet == null) {
                        AppStrings.pick(appLang, "已新建短语「${saved.title}」", "Created snippet \"${saved.title}\"", "已新增片語「${saved.title}」")
                    } else {
                        AppStrings.pick(appLang, "已更新短语「${saved.title}」", "Updated snippet \"${saved.title}\"", "已更新片語「${saved.title}」")
                    },
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
                        contentDescription = tr("删除短语", "Delete snippet", "刪除片語"),
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
                text = snippet.title.ifBlank { normCode.ifEmpty { tr("未命名短语", "Untitled Snippet", "未命名片語") } },
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
    val appLang = LocalAppLanguage.current
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
                        text = snippet.title.ifBlank { tr("未命名短语", "Untitled Snippet", "未命名片語") },
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
                                text = if (appLang == AppLang.EN) "🎙️ \"$trig\"" else "🎙️ 「$trig」",
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
                    Text(tr("编辑", "Edit", "編輯"))
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(tr("删除", "Delete", "刪除"), color = MaterialTheme.colorScheme.error)
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
        title = {
            Text(
                if (initialSnippet == null) {
                    tr("新建 `/` 编码短语", "New `/` Snippet", "新增 `/` 編碼片語")
                } else {
                    tr("编辑 `/` 编码短语", "Edit `/` Snippet", "編輯 `/` 編碼片語")
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
                    label = { Text(tr("短语名称", "Snippet Title", "片語名稱")) },
                    placeholder = { Text(tr("如：工作邮箱、默认收货地址", "e.g. Work Email, Shipping Address", "如：工作信箱、預設收件地址")) }
                )

                OutlinedTextField(
                    value = voiceTriggers,
                    onValueChange = { voiceTriggers = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(tr("语音触发口令（逗号分隔，选填）", "Voice Triggers (comma-separated, optional)", "語音觸發口令（逗號分隔，選填）")) },
                    placeholder = { Text(tr("如：发我的邮箱, 我的工作邮箱", "e.g. send my email, my work email", "如：發我的信箱, 我的工作信箱")) }
                )

                OutlinedTextField(
                    value = flypyCode,
                    onValueChange = { flypyCode = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(tr("编码快捷码（`/` 引导，如 `/sd`、`/yx`，选填）", "Shortcut Code (`/` prefix, e.g. `/sd`, `/yx`, optional)", "編碼快捷碼（`/` 引導，如 `/sd`、`/yx`，選填）")) },
                    placeholder = { Text(tr("如：/yx、/dz、/sd（旧版 ;xx 自动转 /xx）", "e.g. /yx, /dz, /sd (;xx auto-converts to /xx)", "如：/yx、/dz、/sd（舊版 ;xx 自動轉 /xx）")) },
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
                    label = { Text(tr("展开后的完整正文 *", "Expanded Text *", "展開後的完整內文 *")) },
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

private fun buildSharedAiDevPresetPack(lang: AppLang): List<DictionaryEntry> = listOf(
    DictionaryEntry(
        term = "Cursor",
        wrongVariants = "科瑟, cursor",
        note = AppStrings.pick(lang, "AI 代码编辑器", "AI Code Editor", "AI 程式碼編輯器"),
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "Antigravity",
        wrongVariants = "安提格拉维蒂, anti gravity",
        note = AppStrings.pick(lang, "Agentic AI 编程环境", "Agentic AI Coding IDE", "Agentic AI 程式開發環境"),
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "Supabase",
        wrongVariants = "苏帕贝斯, super base",
        note = AppStrings.pick(lang, "开源后端云服务", "Open-Source Backend Cloud", "開源後端雲端服務"),
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "Kubernetes",
        wrongVariants = "库伯内蒂斯, k8s",
        note = AppStrings.pick(lang, "容器编排系统", "Container Orchestration", "容器編排系統"),
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "RAG",
        wrongVariants = "瑞格, R A G",
        note = AppStrings.pick(lang, "检索增强生成", "Retrieval-Augmented Generation", "檢索增強生成"),
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "Agent",
        wrongVariants = "艾真特, agent智能体",
        note = AppStrings.pick(lang, "AI 自主智能体", "Autonomous AI Agent", "AI 自主智慧體"),
        source = DictionarySource.MANUAL
    )
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceHotwordsManagementSection(
    repository: AppRepository
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
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
    var expandedAllHotwords by rememberSaveable { mutableStateOf(false) }

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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.Sparkles,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(17.dp)
                                .align(Alignment.CenterVertically)
                        )
                        Text(
                            text = tr("自动学习打字改错", "Auto-Learn Typing Corrections", "自動學習打字改錯"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = TypeFreeAmber.copy(alpha = 0.16f),
                            border = BorderStroke(0.8.dp, TypeFreeAmber.copy(alpha = 0.65f)),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) {
                            Text(
                                text = tr("实验功能", "Experimental", "實驗功能"),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) TypeFreeAmber else Color(0xFFB45309),
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = tr(
                            "当使用内置键盘修正刚口述的专有名词、人名或大小写（如将 deep seek 改为 DeepSeek）时，自动提取差异并加入语音热词。",
                            "When you use the built-in keyboard to fix proper nouns, names, or casing right after dictation (e.g. deep seek → DeepSeek), corrections are learned automatically.",
                            "當使用內建鍵盤修正剛口述的專有名詞、人名或大小寫（如將 deep seek 改為 DeepSeek）時，自動擷取差異並加入語音熱詞。"
                        ),
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
                text = tr(
                    "语音热词列表 (${dictionary.size})",
                    "Voice Hotwords (${dictionary.size})",
                    "語音熱詞清單 (${dictionary.size})"
                ),
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
                        text = if (isBatchMode) tr("完成", "Done", "完成") else tr("批量选择", "Select", "批次選擇"),
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
                Text(tr("新建热词", "New Hotword", "新增熱詞"), fontWeight = FontWeight.Bold, maxLines = 1)
            }

            FilledTonalButton(
                onClick = { showBatchImportDialog = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = tr("批量文本 / 预设词包导入", "Batch / Preset Import", "批次文字 / 預設詞包匯入"),
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
            placeholder = {
                Text(tr("搜索标准词、同音误识别词或备注...", "Search term, misrecognized variants, or notes...", "搜尋標準詞、同音誤識別詞或備註..."))
            },
            leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = tr("清除搜索", "Clear search", "清除搜尋"))
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
                HotwordFilterPill(tr("全部 (${dictionary.size})", "All (${dictionary.size})", "全部 (${dictionary.size})"), selectedSourceTab == 0) { selectedSourceTab = 0 }
                HotwordFilterPill(tr("自动学习 ($autoCount)", "Auto-Learned ($autoCount)", "自動學習 ($autoCount)"), selectedSourceTab == 1) { selectedSourceTab = 1 }
                HotwordFilterPill(tr("手动添加 ($manualCount)", "Manual ($manualCount)", "手動新增 ($manualCount)"), selectedSourceTab == 2) { selectedSourceTab = 2 }
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
                            text = if (allFilteredSelected) {
                                tr("取消全选", "Deselect All", "取消全選")
                            } else {
                                tr("全选 (${filteredEntries.size})", "Select All (${filteredEntries.size})", "全選 (${filteredEntries.size})")
                            },
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
                        Text(
                            tr("删除已选 (${validSelectedIds.size})", "Delete (${validSelectedIds.size})", "刪除已選 (${validSelectedIds.size})"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
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
                            dictionary.isEmpty() -> tr("语音热词为空", "No Voice Hotwords Yet", "語音熱詞為空")
                            selectedSourceTab == 1 && searchQuery.isEmpty() -> tr("尚无自动学习的改错词条", "No Auto-Learned Corrections Yet", "尚無自動學習的改錯詞條")
                            else -> tr("未找到匹配的热词", "No Matching Hotwords", "未找到符合的熱詞")
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = tr(
                            "添加同事姓名、产品代号或技术词汇，让语音识别与润色永不写错字。",
                            "Add names, product codenames, or technical terms so speech recognition and polishing get them right every time.",
                            "新增同事姓名、產品代號或技術詞彙，讓語音識別與潤飾不再寫錯字。"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            val visibleEntries = if (expandedAllHotwords || searchQuery.isNotBlank() || isBatchMode || filteredEntries.size <= 6) {
                filteredEntries
            } else {
                filteredEntries.take(6)
            }
            visibleEntries.forEach { entry ->
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
                        Toast.makeText(
                            context,
                            AppStrings.pick(appLang, "已删除热词「${entry.term}」", "Deleted hotword \"${entry.term}\"", "已刪除熱詞「${entry.term}」"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
            if (searchQuery.isBlank() && !isBatchMode && filteredEntries.size > 6) {
                TextButton(
                    onClick = { expandedAllHotwords = !expandedAllHotwords },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (expandedAllHotwords) {
                            tr("收起热词列表", "Collapse Hotwords", "收起熱詞清單")
                        } else {
                            tr("展开全部 ${filteredEntries.size} 条热词", "Show all ${filteredEntries.size} hotwords", "展開全部 ${filteredEntries.size} 條熱詞")
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
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
                    if (editingEntry == null) {
                        AppStrings.pick(appLang, "已添加热词「${saved.term}」", "Added hotword \"${saved.term}\"", "已新增熱詞「${saved.term}」")
                    } else {
                        AppStrings.pick(appLang, "已更新热词「${saved.term}」", "Updated hotword \"${saved.term}\"", "已更新熱詞「${saved.term}」")
                    },
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
                    Toast.makeText(
                        context,
                        AppStrings.pick(
                            appLang,
                            "成功导入 ${importedList.size} 条语音热词",
                            "Imported ${importedList.size} voice hotwords",
                            "成功匯入 ${importedList.size} 條語音熱詞"
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        AppStrings.pick(appLang, "所选词条均已存在，无需重复导入", "All selected entries already exist", "所選詞條均已存在，無需重複匯入"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                showBatchImportDialog = false
            }
        )
    }

    if (showBatchDeleteConfirm && validSelectedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(tr("批量删除热词", "Delete Selected Hotwords", "批次刪除熱詞")) },
            text = {
                Text(
                    tr(
                        "确定要删除已选中的 ${validSelectedIds.size} 个热词吗？删除后无法恢复。",
                        "Delete ${validSelectedIds.size} selected hotwords? This cannot be undone.",
                        "確定要刪除已選取的 ${validSelectedIds.size} 個熱詞嗎？刪除後無法復原。"
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val count = validSelectedIds.size
                        repository.deleteDictionaryEntries(validSelectedIds)
                        selectedIds = emptySet()
                        showBatchDeleteConfirm = false
                        if (dictionary.size <= count) isBatchMode = false
                        Toast.makeText(
                            context,
                            AppStrings.pick(appLang, "已批量删除 $count 个热词", "Deleted $count hotwords", "已批次刪除 $count 個熱詞"),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text(tr("确认删除 (${validSelectedIds.size})", "Delete (${validSelectedIds.size})", "確認刪除 (${validSelectedIds.size})"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text(tr("取消", "Cancel", "取消")) }
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
                                    text = if (isAutoLearned) tr("✨ 自动学习", "✨ Auto-Learned", "✨ 自動學習") else tr("手动添加", "Manual", "手動新增"),
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
                            Icon(imageVector = Icons.Default.Edit, contentDescription = tr("编辑热词", "Edit hotword", "編輯熱詞"), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = tr("删除热词", "Delete hotword", "刪除熱詞"), modifier = Modifier.size(18.dp))
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
                        text = tr("同音纠错：", "Fixes:", "同音糾錯："),
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
        title = {
            Text(
                if (initialEntry == null) {
                    tr("新建语音热词", "New Voice Hotword", "新增語音熱詞")
                } else {
                    tr("编辑语音热词", "Edit Voice Hotword", "編輯語音熱詞")
                }
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text(tr("标准词 / 专有名词 *", "Target Term / Proper Noun *", "標準詞 / 專有名詞 *")) },
                    placeholder = { Text(tr("如：TypeFree、DeepSeek、张伟明", "e.g. TypeFree, DeepSeek, Alex Chen", "如：TypeFree、DeepSeek、張偉明")) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = wrongVariants,
                    onValueChange = { wrongVariants = it },
                    label = { Text(tr("常见同音误识别词（可选，逗号分隔）", "Common Misrecognitions (optional, comma-separated)", "常見同音誤識別詞（選填，逗號分隔）")) },
                    placeholder = { Text(tr("如：太普利斯, type less", "e.g. type less, deep seek", "如：太普利斯, type less")) },
                    minLines = 2,
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(tr("备注说明（可选）", "Note (optional)", "備註說明（選填）")) },
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
            ) { Text(tr("保存词条", "Save", "儲存詞條")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel", "取消")) } }
    )
}

@Composable
private fun SharedBatchImportHotwordsDialog(
    existingTerms: Set<String>,
    onDismiss: () -> Unit,
    onImportEntries: (List<DictionaryEntry>) -> Unit
) {
    val appLang = LocalAppLanguage.current
    var rawLines by rememberSaveable { mutableStateOf("") }
    val parsedFromText = remember(rawLines, existingTerms, appLang) {
        parseSharedHotwordBatchLines(rawLines, existingTerms, appLang)
    }
    val missingPresetEntries = remember(existingTerms, appLang) {
        buildSharedAiDevPresetPack(appLang).filter { it.term.trim().lowercase() !in existingTerms }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("批量导入语音热词", "Batch Import Voice Hotwords", "批次匯入語音熱詞")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = tr(
                        "每行填写一个词条。支持仅填标准词，或用 `=` / `:` 附带同音误词：\n示例：OpenTypeFree = 欧盆太普, open typefree",
                        "Enter one entry per line. Add optional misrecognized variants after `=` or `:`:\nExample: OpenTypeFree = open type free, type less",
                        "每行填寫一個詞條。支援僅填標準詞，或用 `=` / `:` 附帶同音誤詞：\n範例：OpenTypeFree = 歐盆太普, open typefree"
                    ),
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
                        Text(
                            tr(
                                "Qwen2.5 = 千问二点五, qwen\nSiliconFlow = 硅基流动\n张伟明 = 张伟民",
                                "Qwen2.5 = qwen two point five, qwen\nDeepSeek = deep seek\nKubernetes = k8s",
                                "Qwen2.5 = 千問二點五, qwen\nSiliconFlow = 矽基流動\n張偉明 = 張偉民"
                            )
                        )
                    }
                )
                if (rawLines.isNotBlank()) {
                    Text(
                        text = tr(
                            "已解析到 ${parsedFromText.size} 个可导入的新词条（已自动去重）",
                            "Parsed ${parsedFromText.size} new entries ready to import (deduplicated)",
                            "已解析到 ${parsedFromText.size} 個可匯入的新詞條（已自動去重）"
                        ),
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
                            tr(
                                "一键导入内置 AI / 开发术语包 (${missingPresetEntries.size} 条)",
                                "Import AI / Dev Preset Pack (${missingPresetEntries.size})",
                                "一鍵匯入內建 AI / 開發術語包 (${missingPresetEntries.size} 條)"
                            )
                        } else {
                            tr("内置 AI / 开发术语包已全部导入", "AI / Dev Preset Pack Already Imported", "內建 AI / 開發術語包已全部匯入")
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = parsedFromText.isNotEmpty(), onClick = { onImportEntries(parsedFromText) }) {
                Text(tr("导入文本词条 (${parsedFromText.size})", "Import (${parsedFromText.size})", "匯入文字詞條 (${parsedFromText.size})"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("关闭", "Close", "關閉")) } }
    )
}

private fun parseSharedHotwordBatchLines(
    rawText: String,
    existingLowerTerms: Set<String>,
    lang: AppLang = AppLang.ZH_CN
): List<DictionaryEntry> {
    if (rawText.isBlank()) return emptyList()
    val seen = existingLowerTerms.toMutableSet()
    val results = mutableListOf<DictionaryEntry>()
    val defaultNote = AppStrings.pick(lang, "批量导入", "Batch Import", "批次匯入")
    rawText.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//")) return@forEach
        val noteSplit = trimmed.split('#', limit = 2)
        val mainPart = noteSplit[0].trim()
        val note = if (noteSplit.size > 1) noteSplit[1].trim() else defaultNote
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
    val appLang = LocalAppLanguage.current
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
                            text = tr("弧形快译目标语言", "Arc Quick-Translate Languages", "弧形快譯目標語言"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr(
                                "键盘长按说话上滑唤起弧形转盘，松手直译目标外语",
                                "Hold Mic and swipe up to open the arc dial; release to translate instantly",
                                "鍵盤長按說話上滑喚起弧形轉盤，鬆手直譯目標外語"
                            ),
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
                        Toast.makeText(
                            context,
                            AppStrings.pick(appLang, "已恢复默认翻译目标语言列表", "Restored default target languages", "已恢復預設翻譯目標語言清單"),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(tr("默认", "Reset", "預設"), style = MaterialTheme.typography.labelMedium)
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
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.Globe,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = tr("默认快译目标：$activeTarget", "Default Target: $activeTarget", "預設快譯目標：$activeTarget"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tr("共 ${targets.size} 种激活语言", "${targets.size} active", "共 ${targets.size} 種啟用語言"),
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
                                    ) { Text(tr("设为默认", "Set Default", "設為預設"), style = MaterialTheme.typography.labelSmall) }
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
                                ) { Icon(imageVector = Icons.Default.KeyboardArrowUp, contentDescription = tr("上移", "Move up", "上移"), modifier = Modifier.size(18.dp)) }
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
                                ) { Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = tr("下移", "Move down", "下移"), modifier = Modifier.size(18.dp)) }
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
                                            contentDescription = tr("移除语言", "Remove language", "移除語言"),
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
                Text(tr("添加翻译目标语言", "Add Target Language", "新增翻譯目標語言"), fontWeight = FontWeight.SemiBold)
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
                    Toast.makeText(
                        context,
                        AppStrings.pick(appLang, "已添加并设为默认快译语言：$clean", "Added and set default target language: $clean", "已新增並設為預設快譯語言：$clean"),
                        Toast.LENGTH_SHORT
                    ).show()
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
        title = { Text(tr("添加弧形快译目标语言", "Add Quick-Translate Language", "新增弧形快譯目標語言")) },
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
                    label = { Text(tr("或输入自定义语言 / 方言", "Or enter a custom language / dialect", "或輸入自訂語言 / 方言")) },
                    placeholder = { Text(tr("如：粤语、Nederlands、商务敬语日文", "e.g. Cantonese, Nederlands, Formal Japanese", "如：粵語、Nederlands、商務敬語日文")) }
                )
            }
        },
        confirmButton = {
            Button(enabled = effectiveChoice.isNotEmpty(), onClick = { onAddLanguage(effectiveChoice) }) {
                Text(tr("添加语言", "Add Language", "新增語言"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel", "取消")) } }
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
                title = { Text(tr("发现新版本 v${info.latestVersion}", "New Version Available v${info.latestVersion}", "發現新版本 v${info.latestVersion}")) },
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
                                        text = tr(
                                            "当前为私有小鹤全量词库定制包，GitHub 公开 Release APK 仅含公开词表，直接覆盖安装会替换内置私有词库。",
                                            "This is a custom private Flypy dictionary build. Public GitHub Release APKs contain open dictionaries only and will overwrite the built-in private dictionary.",
                                            "目前為私有小鶴全量詞庫客製包，GitHub 公開 Release APK 僅含公開詞表，直接覆蓋安裝會替換內建私有詞庫。"
                                        ),
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
                    }) { Text(tr("浏览器下载 APK", "Download APK", "瀏覽器下載 APK")) }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            onOpenUrl(info.htmlUrl)
                            onDismiss()
                        }) { Text(tr("打开发布页", "Release Page", "開啟發佈頁")) }
                        TextButton(onClick = onDismiss) { Text(tr("暂不更新", "Later", "暫不更新")) }
                    }
                }
            )
        }
        is SharedUpdateDialogState.UpToDate -> {
            val info = state.info
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(tr("已是最新版本", "Up to Date", "已是最新版本")) },
                text = {
                    Text(
                        text = tr(
                            "当前版本 v${info.currentVersion} 已是最新正式版（GitHub 最新版本：${info.latestTag}）。",
                            "Current version v${info.currentVersion} is up to date (latest GitHub release: ${info.latestTag}).",
                            "目前版本 v${info.currentVersion} 已是最新正式版（GitHub 最新版本：${info.latestTag}）。"
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text(tr("知道了", "Got It", "知道了")) } },
                dismissButton = {
                    TextButton(onClick = {
                        onOpenUrl(info.htmlUrl)
                        onDismiss()
                    }) { Text(tr("查看 Releases", "View Releases", "查看 Releases")) }
                }
            )
        }
        is SharedUpdateDialogState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(tr("检查更新失败", "Update Check Failed", "檢查更新失敗")) },
                text = { Text(text = state.message, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    Button(onClick = {
                        onOpenUrl(AppUpdateChecker.GITHUB_RELEASES_PAGE_URL)
                        onDismiss()
                    }) { Text(tr("手动查看 Releases", "Open Releases", "手動查看 Releases")) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(tr("关闭", "Close", "關閉")) } }
            )
        }
    }
}

/**
 * Compact inline update banner displayed at the top of Settings when a newer release is available:
 * shows version tag, a single-line key update highlight summary, a primary "更新" action button,
 * and a session-level dismiss button.
 */
@Composable
fun SettingsUpdatePromptBanner(
    info: UpdateCheckInfo,
    onUpdateClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = TypeFreeEmerald.copy(alpha = 0.10f),
        border = BorderStroke(1.2.dp, TypeFreeEmerald.copy(alpha = 0.50f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(TypeFreeEmerald)
                    )
                    Text(
                        text = tr("发现新版本 v${info.latestVersion}", "New Version v${info.latestVersion}", "發現新版本 v${info.latestVersion}"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = info.keyHighlightSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onUpdateClick,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = tr("更新", "Update", "更新"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = tr("暂不提示", "Dismiss", "暫不提示"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
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
        val lang = runCatching {
            AppStrings.resolveLang(TypeFreeApp.instance.repository.settings.value.appLanguage)
        }.getOrDefault(AppStrings.resolveLang("system"))
        Toast.makeText(
            context,
            AppStrings.pick(
                lang,
                "未能打开系统浏览器，请手动访问发布页",
                "Could not open browser. Please visit the release page manually.",
                "未能開啟系統瀏覽器，請手動前往發佈頁面"
            ),
            Toast.LENGTH_SHORT
        ).show()
    }
}
