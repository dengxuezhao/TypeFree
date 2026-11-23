package com.typefree.ime.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typefree.core.offline.AiTask
import com.typefree.core.offline.LocalModelRegistrySnapshot
import com.typefree.core.offline.OfflineMode
import com.typefree.core.offline.OfflineModelCatalog
import com.typefree.core.offline.ReadinessState
import com.typefree.core.offline.TextRoute
import com.typefree.ime.bubble.TypeFreeBubbleController
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.OfflineRouteBridge
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.rememberLocalModelRegistrySnapshot
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald

@Composable
fun VoiceAiStudioScreen(
    repository: AppRepository,
    aiEngine: AiPipelineEngine,
    initialSubTab: Int = AppDestinations.SUBTAB_VOICE_MAIN,
    openOfflineModels: Boolean = false,
    onConsumeOpenOfflineModels: () -> Unit = {},
    onSubTabChanged: ((Int) -> Unit)? = null
) {
    val settings by repository.settings.collectAsState()
    val registrySnapshot = rememberLocalModelRegistrySnapshot()
    val readiness = remember(settings, registrySnapshot) {
        SetupStatsHelper.resolveReadiness(settings, registrySnapshot)
    }
    val isFullReady = readiness is ReadinessState.Ready

    var activeSubTab by rememberSaveable {
        mutableIntStateOf(
            initialSubTab.coerceIn(AppDestinations.SUBTAB_VOICE_MAIN, AppDestinations.SUBTAB_VOICE_API)
        )
    }

    LaunchedEffect(initialSubTab) {
        val coerced = initialSubTab.coerceIn(AppDestinations.SUBTAB_VOICE_MAIN, AppDestinations.SUBTAB_VOICE_API)
        if (activeSubTab != coerced) {
            activeSubTab = coerced
        }
    }

    LaunchedEffect(openOfflineModels) {
        if (openOfflineModels && activeSubTab != AppDestinations.SUBTAB_VOICE_API) {
            activeSubTab = AppDestinations.SUBTAB_VOICE_API
            onSubTabChanged?.invoke(AppDestinations.SUBTAB_VOICE_API)
        }
    }

    fun selectSubTab(idx: Int) {
        val coerced = idx.coerceIn(AppDestinations.SUBTAB_VOICE_MAIN, AppDestinations.SUBTAB_VOICE_API)
        activeSubTab = coerced
        onSubTabChanged?.invoke(coerced)
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
                    text = "语音与 AI",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Surface(
                    onClick = { selectSubTab(AppDestinations.SUBTAB_VOICE_API) },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        1.dp,
                        if (isFullReady) TypeFreeEmerald.copy(alpha = 0.45f) else TypeFreeAmber.copy(alpha = 0.55f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isFullReady) TypeFreeEmerald else TypeFreeAmber)
                        )
                        Text(
                            text = when (readiness) {
                                is ReadinessState.Ready ->
                                    SetupStatsHelper.formatActivePipelineBadge(settings, registrySnapshot)
                                is ReadinessState.DegradedRawOnly ->
                                    readiness.badge.shortLabel
                                is ReadinessState.NotReady ->
                                    "未配置引擎"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

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
                    StudioSubTabPill(
                        title = "语音与热词",
                        selected = activeSubTab == AppDestinations.SUBTAB_VOICE_MAIN,
                        onClick = { selectSubTab(AppDestinations.SUBTAB_VOICE_MAIN) },
                        modifier = Modifier.weight(1f)
                    )
                    StudioSubTabPill(
                        title = "AI 与风格",
                        selected = activeSubTab == AppDestinations.SUBTAB_VOICE_AI_STYLE,
                        onClick = { selectSubTab(AppDestinations.SUBTAB_VOICE_AI_STYLE) },
                        modifier = Modifier.weight(1f)
                    )
                    StudioSubTabPill(
                        title = "模型与 API",
                        selected = activeSubTab == AppDestinations.SUBTAB_VOICE_API,
                        onClick = { selectSubTab(AppDestinations.SUBTAB_VOICE_API) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (activeSubTab) {
                AppDestinations.SUBTAB_VOICE_MAIN -> {
                    VoiceAndHotwordsPane(
                        repository = repository,
                        settings = settings
                    )
                }

                AppDestinations.SUBTAB_VOICE_AI_STYLE -> {
                    StylesAppAwareScreen(
                        repository = repository,
                        showScreenHeader = false,
                        headerSlot = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                AiCopilotAndPrivacyCard(
                                    repository = repository,
                                    settings = settings,
                                    registrySnapshot = registrySnapshot,
                                    onJumpToApiSubTab = { selectSubTab(AppDestinations.SUBTAB_VOICE_API) }
                                )
                                SharedTranslationTargetsCard(repository = repository)
                            }
                        }
                    )
                }

                AppDestinations.SUBTAB_VOICE_API -> {
                    ApiSettingsScreen(
                        repository = repository,
                        aiEngine = aiEngine,
                        showScreenHeader = false,
                        initialOpenOfflineModels = openOfflineModels,
                        onConsumeOpenOfflineModels = onConsumeOpenOfflineModels
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceAndHotwordsPane(
    repository: AppRepository,
    settings: AppSettings
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 语音收音、上下文与悬浮语音胶囊
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
                    text = "语音收音与语境感知",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                PreferenceSwitchRow(
                    title = "实时写入输入框",
                    subtitle = "边说边上屏，未确定的部分会自动修正；部分 App 不支持时自动回退",
                    checked = settings.liveWriteToEditor,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(liveWriteToEditor = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                PreferenceSwitchRow(
                    title = "🎵 录音时自动暂停外放媒体",
                    subtitle = "录音时自动暂停正在播放的音乐或视频，松手后恢复",
                    checked = settings.pauseMediaWhileRecording,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(pauseMediaWhileRecording = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                PreferenceSwitchRow(
                    title = "🤫 轻声高灵敏收音",
                    subtitle = "适合安静环境下贴近麦克风轻声说话，语音面板也可随时切换",
                    checked = settings.whisperModeEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(whisperModeEnabled = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                PreferenceSwitchRow(
                    title = "光标上文语境感知",
                    subtitle = "自动读取光标前最多 80 字上下文，使标点衔接、人称代词与续写更自然",
                    checked = settings.useSurroundingContext,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(useSurroundingContext = enabled) }
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                PreferenceSwitchRow(
                    title = "启用悬浮语音胶囊（免切键盘语音输入）",
                    subtitle = if (settings.bubbleDockLeft) {
                        "当前停靠在屏幕左侧边缘 · 可自由拖拽改变位置"
                    } else {
                        "当前停靠在屏幕右侧边缘 · 可自由拖拽改变位置"
                    },
                    checked = settings.bubbleEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !TypeFreeBubbleController.hasOverlayPermission(context)) {
                            TypeFreeBubbleController.openOverlayPermissionSettings(context)
                        }
                        repository.updateSettings { it.copy(bubbleEnabled = enabled) }
                        TypeFreeBubbleController.syncBubbleService(context)
                    }
                )

                if (settings.bubbleEnabled) {
                    OutlinedButton(
                        onClick = {
                            repository.updateSettings {
                                it.copy(
                                    bubbleDockLeft = !it.bubbleDockLeft,
                                    bubbleYRatio = 0.48f
                                )
                            }
                            TypeFreeBubbleController.syncBubbleService(context)
                            Toast.makeText(context, "已重置悬浮胶囊停靠位置", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (settings.bubbleDockLeft) "切换至右侧停靠并居中" else "切换至左侧停靠并居中",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // 语音热词管理列表 + 批量导入 + 自动学习
        VoiceHotwordsManagementSection(repository = repository)

        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun AiCopilotAndPrivacyCard(
    repository: AppRepository,
    settings: AppSettings,
    registrySnapshot: LocalModelRegistrySnapshot,
    onJumpToApiSubTab: () -> Unit
) {
    var showChatContextDisclosureDialog by remember { mutableStateOf(false) }
    var showJevMemoryDialog by remember { mutableStateOf(false) }
    val jevRoutePlan = remember(settings, registrySnapshot) {
        OfflineRouteBridge.resolvePlan(
            task = AiTask.JEV_COPILOT,
            settings = settings,
            registrySnapshot = registrySnapshot
        )
    }

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
                text = "AI 助手与 ✦ Jev 军师",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            PreferenceSwitchRow(
                title = "🔍 顶栏「问AI」随手查胶囊",
                subtitle = "在普通输入框打字满 2 个字后，键盘顶栏自动浮现「🔍 问AI」，点击在键盘内弹出解答卡片",
                checked = settings.equalEqualAskAiEnabled,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(equalEqualAskAiEnabled = enabled) }
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            PreferenceSwitchRow(
                title = "✦ 启用 Jev 军师模式",
                subtitle = "在键盘顶栏、语音面板与帮写顶栏启用潜台词分析与多策略回复草稿",
                checked = settings.jevCopilotEnabled,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(jevCopilotEnabled = enabled) }
                }
            )

            if (settings.jevCopilotEnabled) {
                val activeJevSummary = if (settings.jevUseDedicatedModel) {
                    "独立模型 · ${settings.jevLlmConfig.model.substringAfterLast('/')}"
                } else {
                    val fallbackModel = if (settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT) {
                        settings.multimodalConfig.model.substringAfterLast('/')
                    } else {
                        settings.llmConfig.model.substringAfterLast('/')
                    }
                    "跟随默认 · $fallbackModel"
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "当前军师推理引擎：$activeJevSummary",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(
                            onClick = onJumpToApiSubTab,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = "去指定模型 →",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            PreferenceSwitchRow(
                title = "✦ Jev 军师读取聊天上文",
                subtitle = "仅在你主动点击 ✦ 军师时按需读取当前窗口可见聊天记录，端侧脱敏后发给你配置的模型；密码框、无痕模式与支付类应用自动屏蔽",
                checked = settings.chatContextReplyEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && !settings.chatContextDisclosureAccepted) {
                        showChatContextDisclosureDialog = true
                    } else {
                        repository.updateSettings { it.copy(chatContextReplyEnabled = enabled) }
                    }
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            PreferenceSwitchRow(
                title = "🖼️ 读屏允许附带脱敏截图",
                subtitle = "开启后可识别表情包与图片内容，端侧自动遮挡敏感信息；关闭则仅发送纯文本",
                checked = settings.chatContextSendScreenshot,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(chatContextSendScreenshot = enabled) }
                }
            )

            val isLocalJevActive = jevRoutePlan.text is TextRoute.Local
            val isOffline4bFallbackConfigured = settings.offlineConfig.mode != OfflineMode.OFF &&
                settings.offlineConfig.slmSlotOn &&
                settings.offlineConfig.selectedSlmModelId == OfflineModelCatalog.ID_SLM_PRO_4B &&
                registrySnapshot.isInstalled(OfflineModelCatalog.ID_SLM_PRO_4B)
            if (isLocalJevActive || isOffline4bFallbackConfigured || settings.offlineConfig.mode == OfflineMode.OFFLINE_ONLY) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = TypeFreeAmber.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, TypeFreeAmber.copy(alpha = 0.36f))
                ) {
                    Text(
                        text = if (isLocalJevActive || settings.offlineConfig.mode == OfflineMode.OFFLINE_ONLY) {
                            "ℹ️ 离线军师仅使用端侧 OCR 文本，不支持截图/表情包视觉理解"
                        } else {
                            "ℹ️ 断网回退至本机离线 4B 军师时，仅使用端侧 OCR 文本，不支持截图/表情包视觉理解"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

            PreferenceSwitchRow(
                title = "🧠 Jev 关系记忆与个人口癖自学习",
                subtitle = "采用设备本地 HMAC-SHA256 匿名锚点记忆对话身份与习惯语体，不存明文姓名或聊天内容",
                checked = settings.jevMemoryEnabled,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(jevMemoryEnabled = enabled) }
                }
            )

            AnimatedVisibility(visible = settings.jevMemoryEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreferenceSwitchRow(
                        title = "保存首尾字掩码提示（如 张*三）",
                        subtitle = "仅用于在管理列表中区分会话，关闭后只保留不可逆哈希",
                        checked = settings.jevMaskTitleHintEnabled,
                        onCheckedChange = { enabled ->
                            repository.updateSettings { it.copy(jevMaskTitleHintEnabled = enabled) }
                        }
                    )
                    OutlinedButton(
                        onClick = { showJevMemoryDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("管理已学风格规则与会话记忆")
                    }
                }
            }
        }
    }

    if (showJevMemoryDialog) {
        JevMemoryManagementDialog(
            repository = repository,
            onDismiss = { showJevMemoryDialog = false }
        )
    }

    if (showChatContextDisclosureDialog) {
        AlertDialog(
            onDismissRequest = { showChatContextDisclosureDialog = false },
            title = { Text("开启「✦ Jev 军师读取聊天上文」说明") },
            text = {
                Text(
                    "仅在你主动点击 ✦ 军师时按需读取当前窗口可见聊天记录，端侧脱敏后发给你配置的模型，不保存、不写入历史。\n\n密码框、无痕模式与支付类应用自动屏蔽，退出面板后内存立即清空。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showChatContextDisclosureDialog = false
                        repository.updateSettings {
                            it.copy(
                                chatContextReplyEnabled = true,
                                chatContextDisclosureAccepted = true,
                                chatContextDisclosureDismissed = false
                            )
                        }
                    }
                ) { Text("同意并启用") }
            },
            dismissButton = {
                TextButton(onClick = { showChatContextDisclosureDialog = false }) { Text("暂不开启") }
            }
        )
    }
}
