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
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.OfflineRouteBridge
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.stats.StatsTimeRange
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
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
    val appLang = LocalAppLanguage.current
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
                    text = tr("语音与 AI", "Voice & AI", "語音與 AI"),
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
                                    SetupStatsHelper.formatActivePipelineBadge(settings, registrySnapshot, appLang)
                                is ReadinessState.DegradedRawOnly ->
                                    if (appLang == AppLang.EN) "Local ASR Only" else readiness.badge.shortLabel
                                is ReadinessState.NotReady ->
                                    tr("未配置引擎", "Setup Required", "未設定引擎")
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
                options = listOf(
                    tr("语音与词典", "Voice", "語音與詞典"),
                    tr("AI 助手", "AI Assist", "AI 助手"),
                    tr("模型与 API", "Models", "模型與 API")
                ),
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
                text = tr("语音收音偏好", "Voice Recording Preferences", "語音收音偏好"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            PreferenceSwitchRow(
                title = tr("实时写入输入框", "Live Streaming into Text Box", "即時寫入輸入框"),
                subtitle = tr(
                    "边说边上屏，未定稿文字自动修正；部分应用不支持时自动回退",
                    "Insert words into the editor in real time as you speak, with automatic fallback in unsupported apps",
                    "邊說邊上屏，未定稿文字自動修正；部分應用不支援時自動回退"
                ),
                checked = settings.liveWriteToEditor,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(liveWriteToEditor = enabled) }
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            PreferenceSwitchRow(
                title = tr("录音时自动暂停外放媒体", "Pause Media While Recording", "錄音時自動暫停外放媒體"),
                subtitle = tr(
                    "按住说话时暂停正在播放的音乐或视频，松手恢复",
                    "Temporarily pause background music or video while holding the mic and resume on release",
                    "按住說話時暫停正在播放的音樂或影片，放開恢復"
                ),
                checked = settings.pauseMediaWhileRecording,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(pauseMediaWhileRecording = enabled) }
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            PreferenceSwitchRow(
                title = tr("轻声高灵敏收音", "Whisper High-Sensitivity Mode", "輕聲高靈敏收音"),
                subtitle = tr(
                    "适合安静环境下贴近麦克风轻声口述，语音面板也可随时切换",
                    "Boost mic gain for quiet speaking close to the phone; can also be toggled from the voice panel",
                    "適合安靜環境下貼近麥克風輕聲口述，語音面板也可隨時切換"
                ),
                checked = settings.whisperModeEnabled,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(whisperModeEnabled = enabled) }
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            PreferenceSwitchRow(
                title = tr("光标上文语境感知", "Surrounding Cursor Context", "游標上文語境感知"),
                subtitle = tr(
                    "读取光标前最多 80 字上下文，使标点衔接、人称代词与续写更自然",
                    "Read up to 80 preceding characters before the cursor for natural punctuation, pronouns, and continuation",
                    "讀取游標前最多 80 字上下文，使標點銜接、人稱代名詞與續寫更自然"
                ),
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
    val appLang = LocalAppLanguage.current
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
                text = tr("键盘随手 AI", "Keyboard Quick AI", "鍵盤隨手 AI"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            PreferenceSwitchRow(
                title = tr("🔍 顶栏「问 AI」胶囊", "🔍 Top-Bar \"Ask AI\" Pill", "🔍 頂欄「問 AI」膠囊"),
                subtitle = tr(
                    "打字满 2 字浮现入口，在键盘内弹出解答卡",
                    "Shows an Ask AI pill after typing 2+ characters to answer questions right inside the keyboard",
                    "打字滿 2 字浮現入口，在鍵盤內彈出解答卡"
                ),
                checked = settings.equalEqualAskAiEnabled,
                onCheckedChange = { enabled ->
                    repository.updateSettings { it.copy(equalEqualAskAiEnabled = enabled) }
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            PreferenceSwitchRow(
                title = tr("✨ AI 整句补全", "✨ AI Sentence Completion", "✨ AI 整句補全"),
                subtitle = tr(
                    "停顿后给出续写建议（密码框与无痕模式永不触发）",
                    "Suggest inline sentence continuations when you pause (never runs in passwords or Incognito)",
                    "停頓後給出續寫建議（密碼框與無痕模式永不觸發）"
                ),
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

        // Card 2: Tone Style & Quick Translate Summary Hub
        MochiGroupedCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tr("润色语气与快译语言", "Tone Styles & Quick Translate", "潤飾語氣與快譯語言"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        lockedStyle != null -> {
                            val lockedName = AppStrings.toneStyleName(lockedStyle, appLang)
                            tr(
                                "已全局锁定「${lockedStyle.emoji} $lockedName」",
                                "Pinned globally: ${lockedStyle.emoji} $lockedName",
                                "已全域鎖定「${lockedStyle.emoji} $lockedName」"
                            )
                        }
                        settings.appAwareToneEnabled -> tr(
                            "自动匹配宿主应用（已启用 $enabledRuleCount 条规则）",
                            "Auto-matching host apps ($enabledRuleCount rules active)",
                            "自動比對宿主應用（已啟用 $enabledRuleCount 條規則）"
                        )
                        else -> tr(
                            "当前使用「✨ 通用灵动」默认风格",
                            "Using default '✨ Smart Default' tone",
                            "目前使用「✨ 通用靈動」預設風格"
                        )
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
                        label = { Text(tr("🧭 自动匹配应用", "🧭 Auto-Match App", "🧭 自動比對應用")) },
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
                    val sName = AppStrings.toneStyleName(style, appLang)
                    FilterChip(
                        selected = isLocked,
                        onClick = {
                            repository.updateSettings {
                                it.copy(selectedManualStyleId = style.id)
                            }
                        },
                        label = { Text("${style.emoji} $sName") },
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
                title = tr("语气风格库", "Tone Style Library", "語氣風格庫"),
                trailingValue = tr("${styles.size} 种预设", "${styles.size} presets", "${styles.size} 種預設"),
                onClick = { detailPage = AiAssistantDetailPage.TONE_LIBRARY }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            MochiSummaryNavRow(
                title = tr("应用自动匹配规则", "App-Aware Tone Rules", "應用自動比對規則"),
                trailingValue = tr("已启用 $enabledRuleCount 条", "$enabledRuleCount active", "已啟用 $enabledRuleCount 條"),
                onClick = { detailPage = AiAssistantDetailPage.APP_RULES }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

            MochiSummaryNavRow(
                title = tr("弧形快译目标语言", "Quick Translate Languages", "弧形快譯目標語言"),
                trailingValue = tr(
                    "默认：$activeTarget（共 ${targets.size} 种）",
                    "Default: $activeTarget (${targets.size})",
                    "預設：$activeTarget（共 ${targets.size} 種）"
                ),
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
