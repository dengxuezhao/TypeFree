package com.typefree.ime.ime.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppSettings
import com.typefree.ime.ime.ImeUiAction
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.jev.memory.GroupSceneTag
import com.typefree.ime.ime.jev.memory.PrivateRelationTag
import com.typefree.ime.ime.jev.memory.TopologyKind
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette

private val QUICK_NOTE_CHIPS = listOf(
    "禁承诺排期",
    "先给结论",
    "多给台阶",
    "不发语音",
    "只聊正事"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JevRelationQuickOverlay(
    uiState: ImeUiState,
    settings: AppSettings,
    isBusy: Boolean,
    onAction: (ImeUiAction) -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val isGroupChat = uiState.capturedChatContext.topology.isGroupChat

    val statusText: String
    val statusColor: Color
    when {
        !settings.jevMemoryEnabled -> {
            statusText = "记忆已关闭·仅本轮"
            statusColor = palette.keyTextMuted
        }
        uiState.jevMemoryBypassedByPrivacy -> {
            statusText = "隐私保护不落盘"
            statusColor = palette.keyTextMuted
        }
        uiState.jevIsEphemeralSession -> {
            statusText = "泛化标题·仅本轮生效"
            statusColor = if (palette.isDark) Color(0xFFFBBF24) else Color(0xFFB45309)
        }
        !uiState.jevAutoRelinkedNotice.isNullOrBlank() -> {
            statusText = "自动续接记忆"
            statusColor = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
        }
        uiState.jevActiveSessionUid != null -> {
            statusText = "已记住当前会话"
            statusColor = palette.primaryMicSolid
        }
        else -> {
            statusText = "选择后即本地记住"
            statusColor = palette.keyTextMuted
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.keyCapBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "⚡ 会话关系与备忘",
                    style = TextStyle(
                        color = palette.keyTextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = statusText,
                    style = TextStyle(
                        color = statusColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }

            Row(
                modifier = Modifier
                    .height(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(palette.keyCapSecondaryBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(11.dp))
                    .clickable(enabled = !uiState.isFullScreenPeekCapturing) {
                        onAction(ImeUiAction.ToggleJevRelationOverlay)
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "收起 ▴",
                    style = TextStyle(
                        color = palette.keyTextPrimary,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // Section 1: 1-tap Relation / Group Scene Chips
        Text(
            text = if (isGroupChat) "群聊场景定位（轻点切换并重算）：" else "1v1 关系定位（轻点切换并重算）：",
            style = TextStyle(
                color = palette.keyTextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (isGroupChat) {
                val scenes = listOf(
                    GroupSceneTag.WORK_FORMAL_GROUP,
                    GroupSceneTag.EXTERNAL_DOCKING_GROUP,
                    GroupSceneTag.FRIENDS_CASUAL_GROUP,
                    GroupSceneTag.TECH_INTEREST_GROUP,
                    GroupSceneTag.UNSET
                )
                scenes.forEach { scene ->
                    val selected = uiState.jevGroupSceneTag == scene && scene != GroupSceneTag.UNSET
                    val label = if (scene == GroupSceneTag.UNSET) {
                        "清除定位"
                    } else {
                        "${scene.badgeEmoji} ${scene.shortLabel}"
                    }
                    OverlayActionChip(
                        text = label,
                        selected = selected,
                        enabled = !isBusy,
                        onClick = { onAction(ImeUiAction.SelectJevGroupScene(scene)) }
                    )
                }
            } else {
                val tags = listOf(
                    PrivateRelationTag.SUPERIOR_ELDER,
                    PrivateRelationTag.CLIENT_EXTERNAL,
                    PrivateRelationTag.PEER_COLLEAGUE,
                    PrivateRelationTag.CLOSE_FRIEND,
                    PrivateRelationTag.INTIMATE_FAMILY,
                    PrivateRelationTag.STRANGER_GAME,
                    PrivateRelationTag.UNSET
                )
                tags.forEach { tag ->
                    val selected = uiState.jevPrivateRelationTag == tag && tag != PrivateRelationTag.UNSET
                    val label = if (tag == PrivateRelationTag.UNSET) {
                        "清除定位"
                    } else {
                        "${tag.badgeEmoji} ${tag.shortLabel}"
                    }
                    OverlayActionChip(
                        text = label,
                        selected = selected,
                        enabled = !isBusy,
                        onClick = { onAction(ImeUiAction.SelectJevPrivateRelation(tag)) }
                    )
                }
            }
        }

        // Section 2: Zero-EditText Note Capture (<=40 chars PII redacted)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "关系备忘（≤40字端侧自动脱敏）：",
                style = TextStyle(
                    color = palette.keyTextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                )
            )
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OverlayActionChip(
                    text = "📋 粘贴",
                    selected = false,
                    enabled = !isBusy,
                    onClick = { onAction(ImeUiAction.PasteJevNoteFromClipboard) }
                )
                if (uiState.jevCustomRelationNote.isNotBlank()) {
                    OverlayActionChip(
                        text = "清空",
                        selected = false,
                        enabled = !isBusy,
                        onClick = { onAction(ImeUiAction.ClearJevRelationNote) }
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(palette.keyCapSecondaryBg.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = uiState.jevCustomRelationNote.ifBlank {
                    "可按住底部麦克风口述备忘，或轻点下方快捷标签追加"
                },
                style = TextStyle(
                    color = if (uiState.jevCustomRelationNote.isNotBlank()) {
                        palette.keyTextPrimary
                    } else {
                        palette.keyTextMuted
                    },
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = if (uiState.jevCustomRelationNote.isNotBlank()) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    }
                )
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            QUICK_NOTE_CHIPS.forEach { chip ->
                val alreadyIncluded = uiState.jevCustomRelationNote.contains(chip)
                OverlayActionChip(
                    text = "+ $chip",
                    selected = alreadyIncluded,
                    enabled = !isBusy,
                    onClick = { onAction(ImeUiAction.AppendJevNoteQuickChip(chip)) }
                )
            }
        }

        // Section 3: Conditional Alias Rebind after contact/group rename
        if (uiState.jevRecentReusableSessions.isNotEmpty() && !uiState.jevMemoryBypassedByPrivacy) {
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = "对方刚改过备注？可一键关联近期记忆：",
                style = TextStyle(
                    color = palette.keyTextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                )
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                uiState.jevRecentReusableSessions.take(3).forEach { sess ->
                    val roleBadge = if (sess.topologyKind == TopologyKind.GRP) {
                        "${sess.groupTag.badgeEmoji}${sess.groupTag.shortLabel}"
                    } else {
                        "${sess.privateTag.badgeEmoji}${sess.privateTag.shortLabel}"
                    }
                    val hintLabel = sess.maskedTitleHint ?: "近期会话"
                    OverlayActionChip(
                        text = "$hintLabel · $roleBadge",
                        selected = false,
                        enabled = !isBusy,
                        onClick = { onAction(ImeUiAction.RebindJevSessionAlias(sess.sessionUid)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayActionChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) palette.capsuleSelectedBg else palette.keyCapSecondaryBg)
            .border(
                width = 1.dp,
                color = if (selected) palette.primaryMicSolid else palette.keyBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = if (selected) palette.primaryMicSolid else palette.keyTextPrimary,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}
