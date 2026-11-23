package com.typefree.ime.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.typefree.core.offline.OfflineMode
import com.typefree.ime.BuildConfig
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.ThemePreference
import com.typefree.ime.data.stats.StatsTimeRange
import com.typefree.ime.network.AppUpdateChecker
import com.typefree.ime.network.UpdateCheckInfo
import com.typefree.ime.ui.components.ContinuousRoundedCornerShape
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.TypeFreeEmerald
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun GeneralSettingsScreen(
    repository: AppRepository
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState()
    val clipboardItems by repository.clipboardItems.collectAsState()
    val statsRev by repository.dailyStatsRevision.collectAsState()
    val statsMeta = remember(settings, statsRev) {
        repository.computeInsightsSnapshot(StatsTimeRange.ALL)
    }
    val formattedStatsKb = remember(statsMeta.statsDbSizeBytes) {
        val bytes = statsMeta.statsDbSizeBytes.coerceAtLeast(0L)
        when {
            bytes == 0L -> "0 KB"
            bytes < 1024L -> "< 1 KB"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    var showResetStatsConfirm by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateDialogState by remember { mutableStateOf<SharedUpdateDialogState?>(null) }
    var availableUpdate by remember {
        mutableStateOf<UpdateCheckInfo?>(
            AppUpdateChecker.peekCachedUpdate(BuildConfig.VERSION_NAME)
        )
    }
    var dismissedUpdateTag by rememberSaveable { mutableStateOf<String?>(null) }

    // Silently check for updates when opening Settings (strictly skipped under OFFLINE_ONLY egress lock).
    LaunchedEffect(settings.offlineConfig.mode) {
        if (settings.offlineConfig.mode == OfflineMode.OFFLINE_ONLY) return@LaunchedEffect
        val latest = AppUpdateChecker.fetchLatestUpdateSilentlyIfNeeded(
            currentVersion = BuildConfig.VERSION_NAME,
            isPrivateDictBuild = BuildConfig.USE_PRIVATE_DICT
        )
        if (latest != null && latest.hasUpdate) {
            availableUpdate = latest
        }
    }

    fun triggerUpdateCheck() {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        coroutineScope.launch {
            val result = AppUpdateChecker.fetchLatestUpdate(
                currentVersion = BuildConfig.VERSION_NAME,
                isPrivateDictBuild = BuildConfig.USE_PRIVATE_DICT
            )
            isCheckingUpdate = false
            result.onSuccess { info ->
                availableUpdate = info.takeIf { it.hasUpdate }
                updateDialogState = if (info.hasUpdate) {
                    SharedUpdateDialogState.UpdateAvailable(info)
                } else {
                    SharedUpdateDialogState.UpToDate(info)
                }
            }.onFailure { err ->
                updateDialogState = SharedUpdateDialogState.Error(
                    err.message ?: "检查更新失败，请稍后重试或前往 GitHub Releases 手动查看"
                )
            }
        }
    }

    val bannerUpdate = availableUpdate?.takeIf { it.hasUpdate && it.latestTag != dismissedUpdateTag }

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "通用",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Surface(
                    onClick = {
                        val cached = availableUpdate
                        if (cached != null && cached.hasUpdate) {
                            updateDialogState = SharedUpdateDialogState.UpdateAvailable(cached)
                        } else {
                            triggerUpdateCheck()
                        }
                    },
                    enabled = !isCheckingUpdate,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        1.dp,
                        if (availableUpdate?.hasUpdate == true) {
                            TypeFreeEmerald.copy(alpha = 0.60f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(11.dp),
                                strokeWidth = 1.6.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (availableUpdate?.hasUpdate == true) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(TypeFreeEmerald)
                            )
                        }
                        Text(
                            text = when {
                                isCheckingUpdate -> "检查更新中…"
                                availableUpdate?.hasUpdate == true -> "有更新 v${availableUpdate!!.latestVersion}"
                                else -> "v${BuildConfig.VERSION_NAME} · BYOK"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (availableUpdate?.hasUpdate == true) {
                                TypeFreeEmerald
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AnimatedVisibility(visible = bannerUpdate != null) {
                bannerUpdate?.let { info ->
                    SettingsUpdatePromptBanner(
                        info = info,
                        onUpdateClick = {
                            updateDialogState = SharedUpdateDialogState.UpdateAvailable(info)
                        },
                        onDismiss = {
                            dismissedUpdateTag = info.latestTag
                        }
                    )
                }
            }

            // 1. 外观主题
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
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "外观主题",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "切换宿主应用、内置语音键盘与悬浮语音胶囊的视觉配色",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GeneralThemeOptionCard(
                            title = "浅色单色",
                            subtitle = "TypeFree 经典",
                            preference = ThemePreference.LIGHT,
                            selected = settings.themePreference == ThemePreference.LIGHT,
                            onClick = {
                                repository.updateSettings { it.copy(themePreference = ThemePreference.LIGHT) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        GeneralThemeOptionCard(
                            title = "深色曜石",
                            subtitle = "沉浸暗色",
                            preference = ThemePreference.DARK,
                            selected = settings.themePreference == ThemePreference.DARK,
                            onClick = {
                                repository.updateSettings { it.copy(themePreference = ThemePreference.DARK) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        GeneralThemeOptionCard(
                            title = "跟随系统",
                            subtitle = "日夜自适应",
                            preference = ThemePreference.SYSTEM,
                            selected = settings.themePreference == ThemePreference.SYSTEM,
                            onClick = {
                                repository.updateSettings { it.copy(themePreference = ThemePreference.SYSTEM) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 2. 面板记忆、音效与触觉振动
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
                        text = "面板记忆与声音振动",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    PreferenceSwitchRow(
                        title = "记忆上一次使用的键盘模式",
                        subtitle = "收起键盘或切换应用后自动恢复上次面板；密码框临时切英文后自动还原",
                        checked = settings.rememberLastKeyboardMode,
                        onCheckedChange = { enabled ->
                            repository.updateSettings { it.copy(rememberLastKeyboardMode = enabled) }
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    PreferenceSwitchRow(
                        title = "按键与录音提示音",
                        subtitle = "点击麦克风开始聆听、结束录音及按键反馈轻音效",
                        checked = settings.interactionSounds,
                        onCheckedChange = { enabled ->
                            repository.updateSettings { it.copy(interactionSounds = enabled) }
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    PreferenceSwitchRow(
                        title = "触觉振动反馈",
                        subtitle = "按下说话键、弧形转盘选词及文字上屏时提供振动反馈",
                        checked = settings.hapticFeedback,
                        onCheckedChange = { enabled ->
                            repository.updateSettings { it.copy(hapticFeedback = enabled) }
                        }
                    )
                }
            }

            // 3. 内置剪贴板历史管理
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            ) {
                val unpinnedCount = remember(clipboardItems) { clipboardItems.count { !it.isPinned } }

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
                                text = "内置剪贴板历史管理",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "键盘顶栏可唤起双列剪贴板，支持置顶常用语 (${clipboardItems.size} 条)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (unpinnedCount > 0) {
                            TextButton(
                                onClick = {
                                    repository.clearUnpinnedClipboardItems()
                                    Toast.makeText(context, "已清空未置顶剪贴板记录", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("清空未置顶 ($unpinnedCount)")
                            }
                        }
                    }

                    PreferenceSwitchRow(
                        title = "自动记录剪贴板历史",
                        subtitle = "自动收纳最近 200 条复制文本，自动过滤密码管理器敏感内容",
                        checked = settings.clipboardEnabled,
                        onCheckedChange = { enabled ->
                            repository.updateSettings { it.copy(clipboardEnabled = enabled) }
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    PreferenceSwitchRow(
                        title = "顶栏刚复制内容一键粘贴提示",
                        subtitle = "复制短信验证码、链接或文本后在键盘顶栏浮现快捷胶囊，一键填入",
                        checked = settings.clipboardQuickPasteSuggestion,
                        onCheckedChange = { enabled ->
                            repository.updateSettings { it.copy(clipboardQuickPasteSuggestion = enabled) }
                        }
                    )

                    if (clipboardItems.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        Text(
                            text = "最近记录预览（可置顶或删除）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        clipboardItems.take(8).forEach { item ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { repository.togglePinClipboardItem(item.id) },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = TypeFreeIcons.Pin,
                                                contentDescription = if (item.isPinned) "取消置顶" else "置顶",
                                                tint = if (item.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { repository.deleteClipboardItem(item.id) },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "删除",
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
            }

            // 4. 端侧纯数字表达统计
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ContinuousRoundedCornerShape(20.dp),
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
                        Text(
                            text = "端侧纯数字表达统计",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = TypeFreeEmerald.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, TypeFreeEmerald.copy(alpha = 0.34f))
                        ) {
                            Text(
                                text = "0 正文 · 纯本地",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TypeFreeEmerald,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                            )
                        }
                    }

                    PreferenceSwitchRow(
                        title = "开启端侧按日纯数字统计",
                        subtitle = "仅按日累计字数、耗时与使用次数等纯数字指标，绝不记录任何输入正文；无痕模式与密码框自动跳过",
                        checked = settings.inputStatsEnabled,
                        onCheckedChange = { enabled ->
                            repository.setInputStatsEnabled(enabled)
                        }
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = "本地统计表：${statsMeta.statsTrackedDays} 天 · $formattedStatsKb",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "写入方式：内存累加 + 后台异步合并",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "无痕 / 密码框隔离：强制零计数",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { showResetStatsConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "重置累计纯数字统计与里程碑",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 5. 更新与关于隐私
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.LogoMark,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TypeFree",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} · BYOK 自有接口版",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = TypeFreeEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "自有 API 直连 · 数据仅存本机",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "• 自有接口直连：音频与文本由手机直接发送至你配置的模型或自定义网关接口，无第三方中转。\n• 本机存储：API Key、历史记录、个人词库、剪贴板与风格配置均保存在当前设备本地。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 19.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { triggerUpdateCheck() },
                            enabled = !isCheckingUpdate,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("正在检查…", style = MaterialTheme.typography.labelLarge)
                            } else {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("检查更新", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        OutlinedButton(
                            onClick = { openExternalBrowserUrl(context, AppUpdateChecker.GITHUB_RELEASES_PAGE_URL) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("GitHub Releases", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            repository.updateSettings { it.copy(onboardingCompleted = false) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("重新查看首次引导向导", style = MaterialTheme.typography.labelLarge)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "TypeFree Android · v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showResetStatsConfirm) {
        AlertDialog(
            onDismissRequest = { showResetStatsConfirm = false },
            title = { Text("重置累计纯数字统计与里程碑") },
            text = {
                Text(
                    "确定要清零本机累计的 ${statsMeta.statsTrackedDays} 天纯数字表达统计、24 小时节律与里程碑进度吗？此操作不会影响你的识别历史、个人词典或 Jev 记忆，且无法撤销。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.resetDailyStats()
                        showResetStatsConfirm = false
                        Toast.makeText(context, "已重置累计纯数字统计与里程碑", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text("确认重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetStatsConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    updateDialogState?.let { dialogState ->
        SharedAppUpdateDialog(
            state = dialogState,
            onOpenUrl = { url -> openExternalBrowserUrl(context, url) },
            onDismiss = { updateDialogState = null }
        )
    }
}

@Composable
private fun GeneralThemeOptionCard(
    title: String,
    subtitle: String,
    preference: ThemePreference,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        },
        border = BorderStroke(
            width = if (selected) 1.6.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
            ) {
                when (preference) {
                    ThemePreference.LIGHT -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFF6F6F8)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(34.dp)
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFF18181B))
                            )
                        }
                    }
                    ThemePreference.DARK -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF141519)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(34.dp)
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFFF4F4F6))
                            )
                        }
                    }
                    ThemePreference.SYSTEM -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF6F6F8)))
                            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF141519)))
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF18181B))
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
