package com.typefree.ime.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typefree.core.offline.AiTask
import com.typefree.core.offline.LocalModelRegistrySnapshot
import com.typefree.core.offline.OfflineMode
import com.typefree.core.offline.OfflineModelCatalog
import com.typefree.core.offline.ReadinessState
import com.typefree.core.offline.TextRoute
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.OfflineRouteBridge
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.stats.StatsTimeRange
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.ui.AppDestinations
import com.typefree.ime.ui.components.CompletionBackendHint
import com.typefree.ime.ui.components.ContinuousRoundedCornerShape
import com.typefree.ime.ui.components.MochiGroupedCard
import com.typefree.ime.ui.components.SmoothDisclosure
import com.typefree.ime.ui.components.SpringSegmentedControl
import com.typefree.ime.ui.components.rememberLocalModelRegistrySnapshot
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald

private enum class AiAssistantDetailPage {
    NONE,
    TONE_LIBRARY,
    APP_RULES
}

@Composable
fun VoiceAiStudioScreen(
    repository: AppRepository,
    aiEngine: AiPipelineEngine,
    initialSubTab: Int = AppDestinations.SUBTAB_VOICE_MAIN,
    openOfflineModels: Boolean = false,
    onConsumeOpenOfflineModels: () -> Unit = {},
    onOpenInsights: () -> Unit = {},
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
        // Pinned Header + Spring 3-Segment Switcher
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

            SpringSegmentedControl(
                options = listOf("语音与词典", "AI 助手", "模型与 API"),
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
                AppDestinations.SUBTAB_VOICE_MAIN -> {
                    VoiceAndHotwordsPane(
                        repository = repository,
                        settings = settings
                    )
                }

                AppDestinations.SUBTAB_VOICE_AI_STYLE -> {
                    AiAssistantAndTonePane(
                        repository = repository,
                        settings = settings,
                        registrySnapshot = registrySnapshot,
                        onJumpToApiSubTab = { selectSubTab(AppDestinations.SUBTAB_VOICE_API) }
                    )
                }

                AppDestinations.SUBTAB_VOICE_API -> {
                    ApiSettingsScreen(
                        repository = repository,
                        aiEngine = aiEngine,
                        showScreenHeader = false,
                        initialOpenOfflineModels = openOfflineModels,
                        onConsumeOpenOfflineModels = onConsumeOpenOfflineModels,
                        onOpenInsights = onOpenInsights
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        MochiGroupedCard {
            Text(
                text = "语音收音偏好",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            PreferenceSwitchRow(
                title = "实时写入输入框",
                subtitle = "边说边上屏，未定稿文字自动修正；部分应用不支持时自动回退",
                checked = settings.liveWriteToEditor,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(liveWriteToEditor = enabled) }
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            PreferenceSwitchRow(
                title = "录音时自动暂停外放媒体",
                subtitle = "按住说话时暂停正在播放的音乐或视频，松手恢复",
                checked = settings.pauseMediaWhileRecording,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(pauseMediaWhileRecording = enabled) }
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            PreferenceSwitchRow(
                title = "轻声高灵敏收音",
                subtitle = "适合安静环境下贴近麦克风轻声口述，语音面板也可随时切换",
                checked = settings.whisperModeEnabled,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(whisperModeEnabled = enabled) }
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            PreferenceSwitchRow(
                title = "光标上文语境感知",
                subtitle = "读取光标前最多 80 字上下文，使标点衔接、人称代词与续写更自然",
                checked = settings.useSurroundingContext,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(useSurroundingContext = enabled) }
                }
            )
        }

        VoiceHotwordsManagementSection(repository = repository)

        Spacer(modifier = Modifier.height(10.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiAssistantAndTonePane(
    repository: AppRepository,
    settings: AppSettings,
    registrySnapshot: LocalModelRegistrySnapshot,
    onJumpToApiSubTab: () -> Unit
) {
    var detailPage by rememberSaveable { mutableStateOf(AiAssistantDetailPage.NONE) }
    var showTranslateSheet by rememberSaveable { mutableStateOf(false) }

    when (detailPage) {
        AiAssistantDetailPage.TONE_LIBRARY -> {
            StylesAppAwareScreen(
                repository = repository,
                showScreenHeader = false,
                fixedSubTab = 0,
                onBack = { detailPage = AiAssistantDetailPage.NONE }
            )
            return
        }
        AiAssistantDetailPage.APP_RULES -> {
            StylesAppAwareScreen(
                repository = repository,
                showScreenHeader = false,
                fixedSubTab = 1,
                onBack = { detailPage = AiAssistantDetailPage.NONE }
            )
            return
        }
        AiAssistantDetailPage.NONE -> Unit
    }

    val styles = settings.styles.ifEmpty { DefaultPresets.defaultStyles() }
    val enabledRuleCount = settings.appRules.count { it.enabled }
    val targets = settings.translationTargets.ifEmpty { DefaultPresets.defaultTranslationTargets() }
    val activeTarget = settings.activeTranslateTarget.ifBlank { targets.first() }
    val lockedStyle = styles.firstOrNull { it.id == settings.selectedManualStyleId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Card 1: Keyboard Quick AI (Ask AI + Sentence Completion unified here)
        MochiGroupedCard {
            Text(
                text = "键盘随手 AI",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            PreferenceSwitchRow(
                title = "🔍 顶栏「问 AI」胶囊",
                subtitle = "打字满 2 字浮现入口，在键盘内弹出解答卡",
                checked = settings.equalEqualAskAiEnabled,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(equalEqualAskAiEnabled = enabled) }
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            PreferenceSwitchRow(
                title = "✨ AI 整句补全",
                subtitle = "停顿后给出续写建议（密码框与无痕模式永不触发）",
                checked = settings.aiCompletionEnabled,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(aiCompletionEnabled = enabled) }
                }
            )

            SmoothDisclosure(expanded = settings.aiCompletionEnabled) {
                CompletionBackendHint(
                    settings = settings,
                    registrySnapshot = registrySnapshot
                )
            }
        }

        // Card 2: Jev Chat Copilot (Hierarchical parent-child disclosure)
        AiCopilotAndPrivacyCard(
            repository = repository,
            settings = settings,
            registrySnapshot = registrySnapshot,
            onJumpToApiSubTab = onJumpToApiSubTab
        )

        // Card 3: Tone Style & Quick Translate Summary Hub (Zero nested segmented tabs)
        MochiGroupedCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "润色语气与快译语言",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        lockedStyle != null -> "已全局锁定「${lockedStyle.emoji} ${lockedStyle.name}」"
                        settings.appAwareToneEnabled -> "自动匹配宿主应用（已启用 $enabledRuleCount 条规则）"
                        else -> "当前使用「✨ 通用灵动」默认风格"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    val isAutoMatched = settings.selectedManualStyleId == "auto" && settings.appAwareToneEnabled
                    FilterChip(
                        selected = isAutoMatched,
                        onClick = {
                            repository.updateSettings {
                                it.copy(
                                    selectedManualStyleId = "auto",
                                    appAwareToneEnabled = true
                                )
                            }
                        },
                        label = { Text("🧭 自动匹配应用") },
                        leadingIcon = if (isAutoMatched) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        } else null
                    )
                }

                items(styles, key = { "quick_lock_${it.id}" }) { style ->
                    val isLocked = settings.selectedManualStyleId == style.id
                    FilterChip(
                        selected = isLocked,
                        onClick = {
                            repository.updateSettings {
                                it.copy(selectedManualStyleId = style.id)
                            }
                        },
                        label = { Text("${style.emoji} ${style.name}") },
                        leadingIcon = if (isLocked) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        } else null
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            MochiSummaryNavRow(
                title = "语气风格库",
                trailingValue = "${styles.size} 种预设",
                onClick = { detailPage = AiAssistantDetailPage.TONE_LIBRARY }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            MochiSummaryNavRow(
                title = "应用自动匹配规则",
                trailingValue = "已启用 $enabledRuleCount 条",
                onClick = { detailPage = AiAssistantDetailPage.APP_RULES }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            MochiSummaryNavRow(
                title = "弧形快译目标语言",
                trailingValue = "默认：$activeTarget（共 ${targets.size} 种）",
                onClick = { showTranslateSheet = true }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
    }

    if (showTranslateSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showTranslateSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SharedTranslationTargetsCard(repository = repository)
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun MochiSummaryNavRow(
    title: String,
    trailingValue: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = trailingValue,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
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
    val statsRev by repository.dailyStatsRevision.collectAsState()
    val jevInsights = remember(settings, statsRev, showJevMemoryDialog) {
        repository.computeInsightsSnapshot(StatsTimeRange.ALL)
    }
    val activeDnaCount = remember(jevInsights.jevDnaItems) {
        jevInsights.jevDnaItems.count { it.isActive }
    }
    val jevSessionCount = jevInsights.jevSessionCount
    val jevRoutePlan = remember(settings, registrySnapshot) {
        OfflineRouteBridge.resolvePlan(
            task = AiTask.JEV_COPILOT,
            settings = settings,
            registrySnapshot = registrySnapshot
        )
    }

    MochiGroupedCard {
        Text(
            text = "✦ Jev 聊天军师",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        PreferenceSwitchRow(
            title = "✦ 启用 Jev 军师",
            subtitle = "在键盘顶栏、语音面板与帮写顶栏启用潜台词分析与多策略回复草稿",
            checked = settings.jevCopilotEnabled,
            onCheckedChange = { enabled ->
                repository.updateSettings { it.copy(jevCopilotEnabled = enabled) }
            }
        )

        SmoothDisclosure(expanded = settings.jevCopilotEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    shape = ContinuousRoundedCornerShape(12.dp),
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
                            text = "当前军师模型：$activeJevSummary",
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
                                text = "切换 ›",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                PreferenceSwitchRow(
                    title = "读取当前聊天上文",
                    subtitle = "仅在你主动点击 ✦ 军师时按需读取可见聊天记录并端侧脱敏；密码框、无痕与支付类应用自动屏蔽",
                    checked = settings.chatContextReplyEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !settings.chatContextDisclosureAccepted) {
                            showChatContextDisclosureDialog = true
                        } else {
                            repository.updateSettings { it.copy(chatContextReplyEnabled = enabled) }
                        }
                    }
                )

                // Screenshot sub-option strictly disclosed ONLY when chatContextReplyEnabled is ON
                SmoothDisclosure(expanded = settings.chatContextReplyEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        PreferenceSwitchRow(
                            title = "🖼️ 允许附带脱敏截图",
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
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                PreferenceSwitchRow(
                    title = "🧠 关系记忆与口癖自学习",
                    subtitle = "采用设备本地 HMAC-SHA256 匿名锚点记忆对话身份与习惯语体，不存明文姓名或聊天内容",
                    checked = settings.jevMemoryEnabled,
                    onCheckedChange = { enabled ->
                        repository.updateSettings { it.copy(jevMemoryEnabled = enabled) }
                    }
                )

                SmoothDisclosure(expanded = settings.jevMemoryEnabled) {
                    val hasLearnedSignals = activeDnaCount > 0 || jevSessionCount > 0
                    Surface(
                        onClick = { showJevMemoryDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ContinuousRoundedCornerShape(12.dp),
                        color = if (hasLearnedSignals) {
                            TypeFreeEmerald.copy(alpha = 0.10f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (hasLearnedSignals) {
                                TypeFreeEmerald.copy(alpha = 0.34f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
                            }
                        )
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
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (hasLearnedSignals) {
                                                TypeFreeEmerald
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                            }
                                        )
                                )
                                Text(
                                    text = if (hasLearnedSignals) {
                                        "已习得 $activeDnaCount 条语体习惯 · $jevSessionCount 个会话档案"
                                    } else {
                                        "正在端侧学习你的语体习惯"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (hasLearnedSignals) {
                                        TypeFreeEmerald
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Text(
                                text = "管理记忆 ›",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
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
            title = { Text("开启「读取当前聊天上文」说明") },
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
