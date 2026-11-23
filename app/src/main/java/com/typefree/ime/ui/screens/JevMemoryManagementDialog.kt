package com.typefree.ime.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.ime.jev.memory.BucketStyleProfile
import com.typefree.ime.ime.jev.memory.GroupSceneTag
import com.typefree.ime.ime.jev.memory.PrivateRelationTag
import com.typefree.ime.ime.jev.memory.RelationPragmaticMapper
import com.typefree.ime.ime.jev.memory.SessionMemoryRecord
import com.typefree.ime.ime.jev.memory.StyleFeatureKey
import com.typefree.ime.ime.jev.memory.TopologyKind
import com.typefree.ime.privacy.LocalPiiRedactor
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose

@Composable
fun JevMemoryManagementDialog(
    repository: AppRepository,
    onDismiss: () -> Unit
) {
    var activeTab by remember { mutableStateOf(0) }
    var refreshTick by remember { mutableStateOf(0) }
    var confirmWipeAll by remember { mutableStateOf(false) }
    var editingSession by remember { mutableStateOf<SessionMemoryRecord?>(null) }

    val allBuckets = remember(refreshTick) {
        val loaded = repository.jevMemoryRepository.listAllBuckets()
        val hasGlobal = loaded.any { it.bucketKey == RelationPragmaticMapper.GLOBAL_BUCKET_KEY }
        if (hasGlobal) {
            loaded.sortedBy { if (it.bucketKey == RelationPragmaticMapper.GLOBAL_BUCKET_KEY) "0" else it.bucketKey }
        } else {
            listOf(BucketStyleProfile(bucketKey = RelationPragmaticMapper.GLOBAL_BUCKET_KEY)) + loaded
        }
    }
    val settings by repository.settings.collectAsState()
    val allSessions = remember(refreshTick) {
        repository.jevMemoryRepository.listAllSessionsSorted()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("✦ Jev 关系记忆与风格规则") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TabRow(selectedTabIndex = activeTab) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = { Text("风格规则") }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = { Text("会话档案 (${allSessions.size})") }
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (activeTab == 0) {
                        Text(
                            text = "全端侧运行，可根据你的手改反馈自动习得或反学解除，也可在此手动锁定或重置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        allBuckets.forEach { bucket ->
                            BucketRuleSectionCard(
                                bucket = bucket,
                                onToggleOverride = { key, value ->
                                    repository.jevMemoryRepository.setBucketFeatureOverride(
                                        bucketKey = bucket.bucketKey,
                                        key = key,
                                        overrideValue = value
                                    )
                                    refreshTick++
                                },
                                onResetFeature = { key ->
                                    repository.jevMemoryRepository.resetBucketFeature(
                                        bucketKey = bucket.bucketKey,
                                        key = key
                                    )
                                    refreshTick++
                                }
                            )
                        }
                    } else {
                        PreferenceSwitchRow(
                            title = "保存首尾字掩码提示（如 张*三）",
                            subtitle = "仅用于在列表中区分会话，关闭后只保留不可逆哈希",
                            checked = settings.jevMaskTitleHintEnabled,
                            onCheckedChange = { enabled ->
                                repository.updateSettings { it.copy(jevMaskTitleHintEnabled = enabled) }
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        if (allSessions.isEmpty()) {
                            Text(
                                text = "暂无已落盘会话记忆。你在聊天窗口打开「✦ 军师 → ⚡关系」设定对方身份/备忘，或采纳候选并发送后会自动建立加密索引。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            allSessions.forEach { sess ->
                                SessionMemoryItemCard(
                                    session = sess,
                                    onTogglePin = {
                                        repository.jevMemoryRepository.updateSessionFromSettings(
                                            sessionUid = sess.sessionUid,
                                            isPinned = !sess.isPinned
                                        )
                                        refreshTick++
                                    },
                                    onEdit = { editingSession = sess },
                                    onDelete = {
                                        repository.jevMemoryRepository.deleteSession(sess.sessionUid)
                                        refreshTick++
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { confirmWipeAll = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = TypeFreeRose)
                    ) {
                        Text("清空全部记忆并轮换密钥")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("完成")
            }
        }
    )

    editingSession?.let { target ->
        EditSessionMemorySubDialog(
            session = target,
            onDismiss = { editingSession = null },
            onSave = { privTag, grpTag, rawNote ->
                val safeNote = LocalPiiRedactor.redactRelationNote(rawNote, maxCodePoints = 40)
                repository.jevMemoryRepository.updateSessionFromSettings(
                    sessionUid = target.sessionUid,
                    privateTag = privTag,
                    groupTag = grpTag,
                    redactedNote = safeNote,
                    isPinned = target.isPinned || safeNote.isNotBlank()
                )
                editingSession = null
                refreshTick++
            }
        )
    }

    if (confirmWipeAll) {
        AlertDialog(
            onDismissRequest = { confirmWipeAll = false },
            title = { Text("确认清空全部军师记忆？") },
            text = {
                Text("此操作会立即清空所有已学风格规则、会话关系定位与备忘，并轮换设备本地 HMAC-SHA256 盐值密钥，清空后不可恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.jevMemoryRepository.wipeAllMemoryAndRotateSalt()
                        repository.jevAnchorExtractor.clearSessionPins()
                        confirmWipeAll = false
                        refreshTick++
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TypeFreeRose)
                ) {
                    Text("确认清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipeAll = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun BucketRuleSectionCard(
    bucket: BucketStyleProfile,
    onToggleOverride: (StyleFeatureKey, Boolean?) -> Unit,
    onResetFeature: (StyleFeatureKey) -> Unit
) {
    val isGlobal = bucket.bucketKey == RelationPragmaticMapper.GLOBAL_BUCKET_KEY
    val keys = remember(isGlobal) {
        StyleFeatureKey.entries.filter { !isGlobal || it.allowGlobalScope }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = formatBucketDisplayTitle(bucket.bucketKey),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            keys.forEach { key ->
                val counter = bucket.featureCounters[key]
                val effectiveOn = counter?.effectiveActive == true
                val statusLabel = when {
                    counter?.userOverride == true -> "手动强制开启"
                    counter?.userOverride == false -> "已手动禁用"
                    counter?.isActive == true -> "自动习得生效"
                    (counter?.opportunityCount ?: 0) > 0 -> "观察中 (${counter?.opportunityCount} 次)"
                    else -> "未激活"
                }
                val statusColor = when {
                    counter?.userOverride == true || counter?.isActive == true -> TypeFreeEmerald
                    counter?.userOverride == false -> TypeFreeRose
                    (counter?.opportunityCount ?: 0) > 0 -> TypeFreeAmber
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = key.displayTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (counter != null && (counter.userOverride != null || counter.opportunityCount > 0)) {
                            TextButton(onClick = { onResetFeature(key) }) {
                                Text("重置")
                            }
                        }
                        Switch(
                            checked = effectiveOn,
                            onCheckedChange = { checked -> onToggleOverride(key, checked) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionMemoryItemCard(
    session: SessionMemoryRecord,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val roleLabel = if (session.topologyKind == TopologyKind.GRP) {
        "${session.groupTag.badgeEmoji} ${session.groupTag.shortLabel}"
    } else {
        "${session.privateTag.badgeEmoji} ${session.privateTag.shortLabel}"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${session.canonicalApp} · ${session.maskedTitleHint ?: "匿名会话"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (session.isPinned) "📌 已置顶" else "交互 ${session.interactionCount} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "关系：$roleLabel",
                style = MaterialTheme.typography.bodySmall
            )
            if (!session.redactedCustomNote.isNullOrBlank()) {
                Text(
                    text = "备忘：${session.redactedCustomNote}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onTogglePin) {
                    Text(if (session.isPinned) "取消置顶" else "置顶")
                }
                TextButton(onClick = onEdit) {
                    Text("编辑")
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = TypeFreeRose)
                ) {
                    Text("删除")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditSessionMemorySubDialog(
    session: SessionMemoryRecord,
    onDismiss: () -> Unit,
    onSave: (PrivateRelationTag, GroupSceneTag, String) -> Unit
) {
    val isGroup = session.topologyKind == TopologyKind.GRP || session.confirmedIsGroup
    var selectedPriv by remember { mutableStateOf(session.privateTag) }
    var selectedGrp by remember { mutableStateOf(session.groupTag) }
    var noteInput by remember { mutableStateOf(session.redactedCustomNote.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑会话记忆") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isGroup) "群聊场景：" else "1v1 关系：",
                    style = MaterialTheme.typography.labelMedium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isGroup) {
                        GroupSceneTag.entries.forEach { tag ->
                            FilterChip(
                                selected = selectedGrp == tag,
                                onClick = { selectedGrp = tag },
                                label = { Text(tag.shortLabel) }
                            )
                        }
                    } else {
                        PrivateRelationTag.entries.forEach { tag ->
                            FilterChip(
                                selected = selectedPriv == tag,
                                onClick = { selectedPriv = tag },
                                label = { Text(tag.shortLabel) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("关系备忘（保存时自动脱敏且限40字）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(selectedPriv, selectedGrp, noteInput) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun formatBucketDisplayTitle(bucketKey: String): String {
    return when {
        bucketKey == RelationPragmaticMapper.GLOBAL_BUCKET_KEY ->
            "全局通用口癖偏好（允许跨场景的规则）"
        bucketKey.startsWith("PRIV::") -> {
            val tag = PrivateRelationTag.fromCode(bucketKey.removePrefix("PRIV::"))
            "私聊分类 · ${tag.badgeEmoji} ${tag.shortLabel}"
        }
        bucketKey.startsWith("GRP::") -> {
            val tag = GroupSceneTag.fromCode(bucketKey.removePrefix("GRP::"))
            "群聊分类 · ${tag.badgeEmoji} ${tag.shortLabel}"
        }
        else -> bucketKey
    }
}
