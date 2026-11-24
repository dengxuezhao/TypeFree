package com.typefree.ime.ui.screens.insights

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.stats.StatsTimeRange
import com.typefree.ime.data.stats.TokenFeatureKeys
import com.typefree.ime.data.stats.TokenShareItem
import com.typefree.ime.data.stats.TokenUsageSnapshot
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ui.components.ContinuousRoundedCornerShape
import com.typefree.ime.ui.components.RollingDigitCounter
import com.typefree.ime.ui.components.SpringSegmentedControl
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeEmeraldLight
import java.util.Locale

private val TOKEN_USAGE_RANGES = listOf(
    StatsTimeRange.DAYS_7,
    StatsTimeRange.THIS_MONTH,
    StatsTimeRange.ALL
)

private fun localizeStatsTimeRangeLabel(range: StatsTimeRange, lang: AppLang): String {
    return when (range) {
        StatsTimeRange.DAYS_7 -> AppStrings.pick(lang, "近 7 天", "7 Days", "近 7 天")
        StatsTimeRange.THIS_MONTH -> AppStrings.pick(lang, "本月", "Month", "本月")
        StatsTimeRange.THIS_YEAR -> AppStrings.pick(lang, "本年", "Year", "本年")
        StatsTimeRange.ALL -> AppStrings.pick(lang, "全部", "All", "全部")
    }
}

private fun localizeTokenShareLabel(item: TokenShareItem, isModelTab: Boolean, lang: AppLang): String {
    if (isModelTab) {
        return if (item.key == "其他模型" || item.label == "其他模型") {
            AppStrings.pick(lang, "其他模型", "Other Models", "其他模型")
        } else {
            item.label
        }
    }
    return when (item.key) {
        TokenFeatureKeys.VOICE_POLISH -> AppStrings.pick(lang, "语音口述润色", "Voice Dictation & Polish", "語音口述潤飾")
        TokenFeatureKeys.SPEAK_TO_EDIT -> AppStrings.pick(lang, "语音/快捷改写", "Speak-to-Edit & Rewrite", "語音/快捷改寫")
        TokenFeatureKeys.HELP_ME_WRITE -> AppStrings.pick(lang, "帮我写 / 灵感成稿", "Help Write / Drafting", "幫我寫 / 靈感成稿")
        TokenFeatureKeys.TRANSLATE -> AppStrings.pick(lang, "实时语音翻译", "Live Voice Translation", "即時語音翻譯")
        TokenFeatureKeys.ASK_AI -> AppStrings.pick(lang, "问 AI / == 直问", "Ask AI / == Query", "問 AI / == 直問")
        TokenFeatureKeys.SENTENCE_COMPLETION -> AppStrings.pick(lang, "AI 整句续写", "AI Sentence Completion", "AI 整句續寫")
        TokenFeatureKeys.MULTIMODAL_AUDIO -> AppStrings.pick(lang, "多模态音频直出", "Multimodal Audio Direct", "多模態音訊直出")
        TokenFeatureKeys.API_TEST -> AppStrings.pick(lang, "连通性实测", "API Connectivity Test", "連通性實測")
        else -> item.label
    }
}

/**
 * Pure Token & Cloud/Offline Voice Duration Observability Card.
 * When [externalRange] is provided (inside [InsightsScreen]), follows the parent range without
 * rendering a duplicate range switcher.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TokenUsageObservabilityCard(
    repository: AppRepository,
    modifier: Modifier = Modifier,
    externalRange: StatsTimeRange? = null
) {
    val appLang = LocalAppLanguage.current
    val statsRevision by repository.dailyStatsRevision.collectAsState()
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
    var breakdownTab by rememberSaveable { mutableIntStateOf(0) } // 0 = 按功能, 1 = 按模型

    val selectedRange = externalRange ?: TOKEN_USAGE_RANGES.getOrElse(rangeIndex) { StatsTimeRange.DAYS_7 }
    val snapshot = remember(selectedRange, statsRevision) {
        repository.computeTokenUsageSnapshot(selectedRange)
    }

    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val emerald = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val promptColor = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)
    val cachedColor = emerald
    val outputColor = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
    val reasoningColor = TypeFreeAmber

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = ContinuousRoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.50f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = tr("模型 Token 与语音时长用量", "Model Token & Voice Duration Usage", "模型 Token 與語音時長用量"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = tr(
                            "纯用量观测 · 不做费用估算 · 无痕自动零计数",
                            "Pure usage telemetry · No cost estimates · Zero tracking in incognito",
                            "純用量觀測 · 不做費用估算 · 無痕自動零計數"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (snapshot.estimatedCallCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = TypeFreeAmber.copy(alpha = 0.14f),
                        border = BorderStroke(0.6.dp, TypeFreeAmber.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = tr("含估算", "Includes Est.", "含估算"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TypeFreeAmber,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Range Segmented Control (only shown when standalone without parent range)
            if (externalRange == null) {
                SpringSegmentedControl(
                    options = TOKEN_USAGE_RANGES.map { localizeStatsTimeRangeLabel(it, appLang) },
                    selectedIndex = rangeIndex,
                    onSelect = { rangeIndex = it },
                    compact = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!snapshot.hasData) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ContinuousRoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = tr(
                            "当前时间范围暂无模型 Token 或语音转写记录。发起语音润色、帮我写、问 AI 或下方连通性实测后，这里会自动统计真实用量。",
                            "No model token or voice transcription usage in this range yet. Usage is recorded automatically when you run dictation, Help Write, Ask AI, or connectivity tests.",
                            "目前時間範圍暫無模型 Token 或語音轉寫記錄。發起語音潤飾、幫我寫、問 AI 或連通性實測後，這裡會自動統計真實用量。"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                val rangeLabel = localizeStatsTimeRangeLabel(selectedRange, appLang)
                // Hero Total Token Metric
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = tr("${rangeLabel}云端模型消耗", "$rangeLabel Cloud Token Usage", "${rangeLabel}雲端模型消耗"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RollingDigitCounter(
                                text = formatCompactTokens(snapshot.totalTokens),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Tokens",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        if (snapshot.totalTokens >= 1_000_000L) {
                            Text(
                                text = tr(
                                    "精确累计 ${formatExactNumber(snapshot.totalTokens)} Tokens",
                                    "Exact total: ${formatExactNumber(snapshot.totalTokens)} Tokens",
                                    "精確累計 ${formatExactNumber(snapshot.totalTokens)} Tokens"
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (snapshot.cacheHitPercent > 0) {
                        Surface(
                            shape = CircleShape,
                            color = emerald.copy(alpha = 0.14f),
                            border = BorderStroke(0.6.dp, emerald.copy(alpha = 0.40f))
                        ) {
                            Text(
                                text = tr("缓存命中 ${snapshot.cacheHitPercent}%", "Cache Hit ${snapshot.cacheHitPercent}%", "快取命中 ${snapshot.cacheHitPercent}%"),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = emerald,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // 4-Segment Stacked Bar + Legend
                if (snapshot.totalTokens > 0L) {
                    TokenCompositionBar(
                        snapshot = snapshot,
                        promptColor = promptColor,
                        cachedColor = cachedColor,
                        outputColor = outputColor,
                        reasoningColor = reasoningColor
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TokenLegendChip(
                            dotColor = promptColor,
                            label = tr("输入", "Input", "輸入"),
                            value = formatCompactTokens(snapshot.uncachedPromptTokens)
                        )
                        if (snapshot.cachedTokens > 0L) {
                            TokenLegendChip(
                                dotColor = cachedColor,
                                label = tr("缓存命中", "Cached", "快取命中"),
                                value = formatCompactTokens(snapshot.cachedTokens)
                            )
                        }
                        TokenLegendChip(
                            dotColor = outputColor,
                            label = tr("输出", "Output", "輸出"),
                            value = formatCompactTokens(snapshot.visibleOutputTokens)
                        )
                        if (snapshot.reasoningTokens > 0L) {
                            TokenLegendChip(
                                dotColor = reasoningColor,
                                label = tr("思考推理", "Reasoning", "思考推理"),
                                value = formatCompactTokens(snapshot.reasoningTokens)
                            )
                        }
                    }
                }

                // Breakdown by Feature / Model
                if (snapshot.byFeature.isNotEmpty() || snapshot.byModel.isNotEmpty()) {
                    HorizontalDivider(
                        thickness = 0.6.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
                    )

                    SpringSegmentedControl(
                        options = listOf(
                            tr("按功能分布", "By Feature", "依功能分佈"),
                            tr("按模型分布", "By Model", "依模型分佈")
                        ),
                        selectedIndex = breakdownTab,
                        onSelect = { breakdownTab = it },
                        compact = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val activeList = if (breakdownTab == 0) snapshot.byFeature else snapshot.byModel
                    val barTint = if (breakdownTab == 0) promptColor else outputColor
                    if (activeList.isEmpty()) {
                        Text(
                            text = if (breakdownTab == 0) {
                                tr("暂无功能明细", "No feature breakdown yet", "暫無功能明細")
                            } else {
                                tr("暂无模型明细", "No model breakdown yet", "暫無模型明細")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            activeList.forEach { item ->
                                TokenBreakdownRow(
                                    item = item,
                                    displayLabel = localizeTokenShareLabel(item, breakdownTab == 1, appLang),
                                    isMonospaceLabel = breakdownTab == 1,
                                    barColor = barTint
                                )
                            }
                        }
                    }
                }

                // Cloud vs. Offline Voice Duration Strip
                val totalVoiceDurMs = snapshot.cloudVoiceDurationMs + snapshot.offlineVoiceDurationMs
                if (totalVoiceDurMs > 0L || snapshot.offlineSavedCloudCalls > 0) {
                    HorizontalDivider(
                        thickness = 0.6.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
                    )
                    VoiceDurationSplitSection(
                        cloudDurationMs = snapshot.cloudVoiceDurationMs,
                        offlineDurationMs = snapshot.offlineVoiceDurationMs,
                        offlineSavedCloudCalls = snapshot.offlineSavedCloudCalls,
                        cloudColor = promptColor,
                        offlineColor = emerald
                    )
                }
            }

            Text(
                text = tr(
                    "注：仅统计接口返回的 usage 实测值；若第三方反代未回传 usage，会按字符数估算并标记「含估算」。数据仅存于本机。",
                    "Note: Counts exact API `usage` values when returned; gateways omitting `usage` are estimated from character length and marked \"Includes Est.\". Stored on-device only.",
                    "註：僅統計介面回傳的 usage 實測值；若第三方閘道未回傳 usage，會依字元數估算並標記「含估算」。資料僅存於本機。"
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun TokenCompositionBar(
    snapshot: TokenUsageSnapshot,
    promptColor: Color,
    cachedColor: Color,
    outputColor: Color,
    reasoningColor: Color
) {
    val total = snapshot.totalTokens.coerceAtLeast(1L).toFloat()
    val segments = remember(snapshot) {
        listOf(
            snapshot.uncachedPromptTokens to promptColor,
            snapshot.cachedTokens to cachedColor,
            snapshot.visibleOutputTokens to outputColor,
            snapshot.reasoningTokens to reasoningColor
        ).filter { it.first > 0L }
    }
    if (segments.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        segments.forEach { (count, color) ->
            val targetWeight = (count.toFloat() / total).coerceAtLeast(0.03f)
            val animWeight by animateFloatAsState(
                targetValue = targetWeight,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "tokenSegWeight"
            )
            Box(
                modifier = Modifier
                    .weight(animWeight)
                    .fillMaxHeight()
                    .background(color)
            )
        }
    }
}

@Composable
private fun TokenLegendChip(
    dotColor: Color,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontFeatureSettings = "tnum"
            ),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TokenBreakdownRow(
    item: TokenShareItem,
    displayLabel: String,
    isMonospaceLabel: Boolean,
    barColor: Color
) {
    val progress by animateFloatAsState(
        targetValue = (item.percent / 100f).coerceIn(0.02f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tokenBreakdownBar"
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayLabel,
                style = if (isMonospaceLabel) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodySmall
                },
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${formatCompactTokens(item.tokens)} · ${item.percent}%",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum"
                ),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(barColor.copy(alpha = 0.85f))
            )
        }
    }
}

@Composable
private fun VoiceDurationSplitSection(
    cloudDurationMs: Long,
    offlineDurationMs: Long,
    offlineSavedCloudCalls: Int,
    cloudColor: Color,
    offlineColor: Color
) {
    val appLang = LocalAppLanguage.current
    val totalMs = (cloudDurationMs + offlineDurationMs).coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tr("语音转写时长分布", "Voice Transcription Duration", "語音轉寫時長分佈"),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (offlineSavedCloudCalls > 0) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(offlineColor.copy(alpha = 0.13f))
                        .border(0.6.dp, offlineColor.copy(alpha = 0.35f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = tr(
                            "本机离线免去 $offlineSavedCloudCalls 次云端调用",
                            "$offlineSavedCloudCalls cloud calls saved on-device",
                            "本機離線免去 $offlineSavedCloudCalls 次雲端呼叫"
                        ),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = offlineColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        if (cloudDurationMs > 0L || offlineDurationMs > 0L) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (cloudDurationMs > 0L) {
                    Box(
                        modifier = Modifier
                            .weight((cloudDurationMs.toFloat() / totalMs.toFloat()).coerceAtLeast(0.04f))
                            .fillMaxHeight()
                            .background(cloudColor)
                    )
                }
                if (offlineDurationMs > 0L) {
                    Box(
                        modifier = Modifier
                            .weight((offlineDurationMs.toFloat() / totalMs.toFloat()).coerceAtLeast(0.04f))
                            .fillMaxHeight()
                            .background(offlineColor)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TokenLegendChip(
                    dotColor = cloudColor,
                    label = tr("云端转写", "Cloud STT", "雲端轉寫"),
                    value = formatVoiceDuration(cloudDurationMs, appLang)
                )
                TokenLegendChip(
                    dotColor = offlineColor,
                    label = tr("本机离线", "On-Device", "本機離線"),
                    value = formatVoiceDuration(offlineDurationMs, appLang)
                )
            }
        }
    }
}

/**
 * Compact 1-line summary card for `ApiSettingsScreen` that routes users to the unified
 * `InsightsScreen` without cluttering the API configuration flow.
 */
@Composable
fun TokenUsageSummaryJumpStrip(
    repository: AppRepository,
    onOpenInsights: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appLang = LocalAppLanguage.current
    val statsRevision by repository.dailyStatsRevision.collectAsState()
    val weekSnapshot = remember(statsRevision) {
        repository.computeTokenUsageSnapshot(StatsTimeRange.DAYS_7)
    }
    val summarySubtitle = remember(weekSnapshot, appLang) {
        when {
            weekSnapshot.totalTokens > 0L -> {
                val cacheSuffix = if (weekSnapshot.cacheHitPercent > 0) {
                    AppStrings.pick(
                        appLang,
                        " · 缓存命中 ${weekSnapshot.cacheHitPercent}%",
                        " · ${weekSnapshot.cacheHitPercent}% cached",
                        " · 快取命中 ${weekSnapshot.cacheHitPercent}%"
                    )
                } else {
                    ""
                }
                AppStrings.pick(
                    appLang,
                    "近 7 天云端消耗 ${formatCompactTokens(weekSnapshot.totalTokens)} Tokens$cacheSuffix",
                    "Last 7d cloud usage: ${formatCompactTokens(weekSnapshot.totalTokens)} Tokens$cacheSuffix",
                    "近 7 天雲端消耗 ${formatCompactTokens(weekSnapshot.totalTokens)} Tokens$cacheSuffix"
                )
            }
            weekSnapshot.offlineVoiceDurationMs > 0L || weekSnapshot.cloudVoiceDurationMs > 0L -> {
                val totalVoice = weekSnapshot.offlineVoiceDurationMs + weekSnapshot.cloudVoiceDurationMs
                AppStrings.pick(
                    appLang,
                    "近 7 天语音转写 ${formatVoiceDuration(totalVoice, appLang)}",
                    "Last 7d voice transcription: ${formatVoiceDuration(totalVoice, appLang)}",
                    "近 7 天語音轉寫 ${formatVoiceDuration(totalVoice, appLang)}"
                )
            }
            else -> AppStrings.pick(
                appLang,
                "按功能与模型统计真实 Token 与转写时长，已统一归入表达洞察",
                "Real token & voice duration breakdown by feature and model in Insights",
                "依功能與模型統計真實 Token 與轉寫時長，已統一歸入表達洞察"
            )
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedCornerShape(16.dp))
            .clickable(onClick = onOpenInsights),
        shape = ContinuousRoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.48f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = tr("📊 模型 Token 与语音时长统计", "📊 Model Token & Voice Duration Stats", "📊 模型 Token 與語音時長統計"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = summarySubtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = tr("查看统计", "View Stats", "查看統計"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = tr("查看表达洞察", "Open Expression Insights", "查看表達洞察"),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

internal fun formatCompactTokens(tokens: Long): String {
    val safe = tokens.coerceAtLeast(0L)
    return when {
        safe >= 10_000_000L -> String.format(Locale.US, "%.1fM", safe / 1_000_000.0)
        safe >= 1_000_000L -> String.format(Locale.US, "%.2fM", safe / 1_000_000.0)
        safe >= 100_000L -> String.format(Locale.US, "%.1fK", safe / 1_000.0)
        else -> String.format(Locale.US, "%,d", safe)
    }
}

private fun formatExactNumber(value: Long): String {
    return String.format(Locale.US, "%,d", value.coerceAtLeast(0L))
}

internal fun formatVoiceDuration(durationMs: Long, lang: AppLang = AppLang.ZH_CN): String {
    val totalSec = (durationMs.coerceAtLeast(0L) + 500L) / 1000L
    if (totalSec <= 0L) return AppStrings.pick(lang, "0 秒", "0s", "0 秒")
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return when {
        hours > 0L -> AppStrings.pick(lang, "${hours}小时${mins}分", "${hours}h ${mins}m", "${hours}小時${mins}分")
        mins > 0L -> AppStrings.pick(lang, "${mins}分${secs}秒", "${mins}m ${secs}s", "${mins}分${secs}秒")
        else -> AppStrings.pick(lang, "${secs}秒", "${secs}s", "${secs}秒")
    }
}
