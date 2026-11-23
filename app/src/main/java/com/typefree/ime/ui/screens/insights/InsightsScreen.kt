package com.typefree.ime.ui.screens.insights

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.data.stats.AppSceneShareItem
import com.typefree.ime.data.stats.InsightsSnapshot
import com.typefree.ime.data.stats.IntentShareItem
import com.typefree.ime.data.stats.JevStyleDnaItem
import com.typefree.ime.data.stats.MilestoneItem
import com.typefree.ime.data.stats.SchemeShareItem
import com.typefree.ime.data.stats.StatsTimeRange
import com.typefree.ime.ime.jev.memory.StyleFeatureKey
import com.typefree.ime.ui.components.ContinuousRoundedCornerShape
import com.typefree.ime.ui.components.RollingDigitCounter
import com.typefree.ime.ui.components.SmoothDisclosure
import com.typefree.ime.ui.components.SpringSegmentedControl
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.components.horizontalFadeEdge
import com.typefree.ime.ui.components.springPressScale
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeEmeraldLight
import com.typefree.ime.ui.theme.TypeFreeRose
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

@Composable
fun InsightsScreen(
    repository: AppRepository,
    onBack: () -> Unit,
    onOpenHistoryForDate: (Int?) -> Unit,
    onOpenPrivacySettings: () -> Unit,
    onOpenStylesSettings: () -> Unit,
    onOpenPlayground: () -> Unit,
    onOpenJevMemory: () -> Unit,
    onOpenApiUsage: () -> Unit = {}
) {
    val context = LocalContext.current
    val statsRevision by repository.dailyStatsRevision.collectAsState()
    val history by repository.history.collectAsState()
    val settings by repository.settings.collectAsState()

    var selectedRange by rememberSaveable { mutableStateOf(StatsTimeRange.DAYS_7) }
    val snapshot = remember(selectedRange, statsRevision, history, settings) {
        repository.computeInsightsSnapshot(selectedRange)
    }
    val allTokenSnapshot = remember(statsRevision) {
        repository.computeTokenUsageSnapshot(StatsTimeRange.ALL)
    }
    val isGlobalColdStart = snapshot.isColdStart &&
        snapshot.lifetimeTotalChars == 0 &&
        snapshot.lifetimeActiveDays == 0 &&
        !allTokenSnapshot.hasData
    val isRangeQuietOnly = snapshot.isColdStart && !isGlobalColdStart

    var showPosterStudio by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val isScrolled by remember { derivedStateOf { scrollState.value > 8 } }

    val topBarBg by animateColorAsState(
        targetValue = if (isScrolled) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        } else {
            MaterialTheme.colorScheme.background
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "insightsTopBarBg"
    )
    val hairlineAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.42f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "insightsHairlineAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Sticky Scroll-Aware Frosted Top Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(topBarBg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "表达洞察",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(TypeFreeEmerald.copy(alpha = 0.14f))
                            .border(0.6.dp, TypeFreeEmerald.copy(alpha = 0.35f), CircleShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "永久统计",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (LocalTypeFreeKeyboardPalette.current.isDark) {
                                TypeFreeEmeraldLight
                            } else {
                                Color(0xFF059669)
                            }
                        )
                    }
                }

                val shareInteraction = remember { MutableInteractionSource() }
                Surface(
                    onClick = {
                        if (isGlobalColdStart) {
                            Toast.makeText(
                                context,
                                "先说几句或打几行字，即可生成专属表达手账",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            showPosterStudio = true
                        }
                    },
                    interactionSource = shareInteraction,
                    shape = CircleShape,
                    color = if (isGlobalColdStart) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .springPressScale(0.94f, shareInteraction)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = null,
                            tint = if (isGlobalColdStart) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "定制手账",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isGlobalColdStart) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            }
                        )
                    }
                }
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = hairlineAlpha)
            )
        }

        // Scrollable Body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isGlobalColdStart) {
                InsightsColdStartCard(onOpenPlayground = onOpenPlayground)
                InsightsPrivacyFooterCard(
                    snapshot = snapshot,
                    onOpenPrivacySettings = onOpenPrivacySettings
                )
            } else {
                // 1. Time Range Segmented Control
                SpringSegmentedControl(
                    options = StatsTimeRange.entries.map { it.label },
                    selectedIndex = StatsTimeRange.entries.indexOf(selectedRange),
                    onSelect = { idx ->
                        selectedRange = StatsTimeRange.entries.getOrElse(idx) { StatsTimeRange.DAYS_7 }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (isRangeQuietOnly) {
                    InsightsRangeQuietCard(
                        rangeLabel = selectedRange.label,
                        lifetimeChars = snapshot.lifetimeTotalChars,
                        lifetimeDays = snapshot.lifetimeActiveDays,
                        onSwitchToAll = { selectedRange = StatsTimeRange.ALL },
                        onOpenPlayground = onOpenPlayground
                    )
                } else {
                    // 2. Main Hero & Dual-Track Expression Card
                    HeroExpressionCard(snapshot = snapshot)

                    // 3. Keystroke Savings Efficiency Bill Card
                    EfficiencyBillCard(snapshot = snapshot)
                }

                // 3.5 Unified Model Token & Voice Duration Observability Card
                TokenUsageObservabilityCard(
                    repository = repository,
                    externalRange = selectedRange
                )

                // 4. Companion Growth Trajectory Card (Monthly Trend / Heatmap)
                GrowthTrajectoryCard(
                    snapshot = snapshot,
                    onOpenHistoryForDate = onOpenHistoryForDate
                )

                if (!isRangeQuietOnly) {
                    // 5. 24h Dual-Track Rhythm Card
                    Rhythm24hCard(snapshot = snapshot)

                    // 6. Where You Express Most (Intent & App Scenes)
                    ScenesAndIntentsCard(
                        snapshot = snapshot,
                        onOpenStylesSettings = onOpenStylesSettings
                    )
                }

                // 7. Jev Communication Persona & Style DNA Card
                JevStyleDnaCard(
                    snapshot = snapshot,
                    onOpenJevMemory = onOpenJevMemory
                )

                // 8. Lexicon & Habitual Word Chains Card (Anti-peek blur)
                LexiconAndWordChainsCard(snapshot = snapshot)

                // 9. Lifetime Milestones Wall
                MilestonesWallCard(snapshot = snapshot)

                // 10. Bottom Transparent Privacy Footer Card
                InsightsPrivacyFooterCard(
                    snapshot = snapshot,
                    onOpenPrivacySettings = onOpenPrivacySettings
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }

    if (showPosterStudio) {
        SharePosterStudioDialog(
            snapshot = snapshot,
            onDismiss = { showPosterStudio = false }
        )
    }
}

@Composable
private fun InsightsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = ContinuousRoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun InsightsColdStartCard(
    onOpenPlayground: () -> Unit
) {
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val emerald = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald

    InsightsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(ContinuousRoundedCornerShape(18.dp))
                    .background(emerald.copy(alpha = 0.14f))
                    .border(1.dp, emerald.copy(alpha = 0.35f), ContinuousRoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = TypeFreeIcons.VoiceWaveform,
                    contentDescription = null,
                    tint = emerald,
                    modifier = Modifier.size(28.dp)
                )
            }

            Text(
                text = "正在认识你的表达习惯",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "当你开口说话或敲击键盘时，TypeFree 会在本机记录纯数字的表达节奏、语音与键盘比例，以及 Jev 军师的语体偏好——不保存任何原文，无痕与密码框自动零计数。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("双轨字数账单", "24h 作息节律", "零正文端侧统计").forEach { tag ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(ContinuousRoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tag,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val ctaInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = onOpenPlayground,
                interactionSource = ctaInteraction,
                shape = ContinuousRoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .springPressScale(0.97f, ctaInteraction)
            ) {
                Icon(
                    imageVector = TypeFreeIcons.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "去语音沙盒试说一下",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun InsightsRangeQuietCard(
    rangeLabel: String,
    lifetimeChars: Int,
    lifetimeDays: Int,
    onSwitchToAll: () -> Unit,
    onOpenPlayground: () -> Unit
) {
    InsightsCard {
        Text(
            text = "${rangeLabel}暂无新的输入记录",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "你在 TypeFree 已累计陪伴 $lifetimeDays 天、共表达 ${
                formatChartCompactChars(lifetimeChars)
            } 字，可切换到「全部」查看完整累计数据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 19.sp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSwitchToAll,
                shape = ContinuousRoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "查看全部累计", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            }
            Surface(
                onClick = onOpenPlayground,
                shape = ContinuousRoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "去语音沙盒试试",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroExpressionCard(snapshot: InsightsSnapshot) {
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val emerald = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald

    InsightsCard {
        // Header + Streak badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "解放双手 · 全量表达",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (snapshot.streakDays > 0) {
                            TypeFreeAmber.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        }
                    )
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (snapshot.streakDays > 0) {
                        "🔥 连续 ${snapshot.streakDays} 天"
                    } else {
                        "活跃 ${snapshot.activeDaysInRange} 天"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (snapshot.streakDays > 0) {
                        if (isDark) TypeFreeAmber else Color(0xFFB45309)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // Main Rolling Total Chars + Saved Minutes Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                RollingDigitCounter(
                    text = formatChartCompactChars(snapshot.totalChars),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 38.sp,
                        lineHeight = 42.sp,
                        letterSpacing = (-1.0).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "字",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }

            Surface(
                shape = CircleShape,
                color = emerald.copy(alpha = 0.14f),
                border = BorderStroke(0.7.dp, emerald.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "省下",
                        fontSize = 11.sp,
                        color = emerald,
                        fontWeight = FontWeight.Medium
                    )
                    RollingDigitCounter(
                        text = "${snapshot.savedMinutes}",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                        color = emerald,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "分钟",
                        fontSize = 11.sp,
                        color = emerald,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Measured WPM & Latency readout (never faked)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val speedSummary = if (snapshot.hasMeasuredWpm) {
                "实测口述 ${snapshot.averageWpm} 字/分（约 ${
                    String.format(Locale.US, "%.1f", snapshot.speedMultiplier)
                }× 拇指打字）"
            } else {
                "口述满 3 秒后自动测算实测语速"
            }
            val latencySuffix = if (snapshot.avgLatencyMs > 0L) {
                " · 响应 ${formatLatency(snapshot.avgLatencyMs)}"
            } else {
                ""
            }
            Text(
                text = speedSummary + latencySuffix,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Dynamic Metaphor Line
        if (snapshot.metaphorLine.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousRoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    text = snapshot.metaphorLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Dual Track Ratio Bar
        DualTrackRatioBar(
            voiceChars = snapshot.voiceChars,
            keyChars = snapshot.keyChars
        )

        // 2x2 Keyboard Schemes Grid
        if (snapshot.schemeShares.isNotEmpty()) {
            val topSchemes = remember(snapshot.schemeShares) { snapshot.schemeShares.take(4) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                topSchemes.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { scheme ->
                            SchemeMiniTile(
                                item = scheme,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Offline Privacy Escort Strip
        val escortShape = ContinuousRoundedCornerShape(13.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(escortShape)
                .background(
                    if (snapshot.hasOfflineSplitData) {
                        emerald.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    }
                )
                .border(
                    0.6.dp,
                    if (snapshot.hasOfflineSplitData) {
                        emerald.copy(alpha = 0.30f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    },
                    escortShape
                )
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = if (snapshot.hasOfflineSplitData) emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = if (snapshot.hasOfflineSplitData) {
                    "本机离线引擎静默护航 ${snapshot.offlinePercent}% · 0 字节出网"
                } else {
                    "本机纯数字累计护航 · 不保存也不上传任何正文"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SchemeMiniTile(
    item: SchemeShareItem,
    modifier: Modifier = Modifier
) {
    val tileShape = ContinuousRoundedCornerShape(14.dp)
    val animatedPct by animateFloatAsState(
        targetValue = (item.percent / 100f).coerceIn(0.04f, 1f),
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 360f),
        label = "schemePct_${item.key}"
    )
    Column(
        modifier = modifier
            .clip(tileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${item.percent}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "${formatChartCompactChars(item.chars)} 字",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedPct)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.72f))
            )
        }
    }
}

@Composable
private fun EfficiencyBillCard(snapshot: InsightsSnapshot) {
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val emerald = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val tileShape = ContinuousRoundedCornerShape(16.dp)

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "⚡ 少敲键效率账单",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "短语 · 联想 · 杠杆",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(tileShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "快捷短语命中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    RollingDigitCounter(
                        text = formatChartCompactChars(snapshot.snippetSavedChars),
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "字",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                Text(
                    text = "省下输入 · 触发 ${snapshot.snippetHits} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(tileShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "L2 私人联想采纳",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    RollingDigitCounter(
                        text = formatChartCompactChars(snapshot.predictionSavedKeys),
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "键",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                Text(
                    text = "免敲按键 · 采纳 ${snapshot.predictionHits} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (snapshot.helpWriteLeverage > 1.0f) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousRoundedCornerShape(14.dp))
                    .background(emerald.copy(alpha = 0.11f))
                    .border(0.6.dp, emerald.copy(alpha = 0.30f), ContinuousRoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "「帮我写」平均表达杠杆倍率",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "简短口述扩展为完整可发送成文",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${String.format(Locale.US, "%.1f", snapshot.helpWriteLeverage)}×",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = emerald
                )
            }
        }
    }
}

@Composable
private fun GrowthTrajectoryCard(
    snapshot: InsightsSnapshot,
    onOpenHistoryForDate: (Int?) -> Unit
) {
    var trajectoryTab by rememberSaveable { mutableIntStateOf(0) }

    val defaultMonth = remember(snapshot.monthlyTrend) {
        snapshot.monthlyTrend
            .lastOrNull { !it.isFuture && it.totalChars > 0 }?.month
            ?: LocalDate.now().monthValue
    }
    var selectedMonth by remember(snapshot.monthlyTrend) { mutableIntStateOf(defaultMonth) }

    val defaultHeatmapDateKey = remember(snapshot.heatmapDays) {
        snapshot.heatmapDays
            .lastOrNull { it.totalChars > 0 }?.dateKey
            ?: snapshot.heatmapDays.lastOrNull()?.dateKey
    }
    var selectedHeatmapKey by remember(snapshot.heatmapDays) {
        mutableStateOf(defaultHeatmapDateKey)
    }

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "陪伴成长轨迹",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "累计陪伴 ${snapshot.lifetimeActiveDays} 天 · 累计 ${
                        formatChartCompactChars(snapshot.lifetimeTotalChars)
                    } 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SpringSegmentedControl(
                options = listOf("月度趋势", "热力格"),
                selectedIndex = trajectoryTab,
                onSelect = { trajectoryTab = it },
                compact = true,
                modifier = Modifier.width(136.dp)
            )
        }

        if (trajectoryTab == 0) {
            MonthlyStackedTrendChart(
                points = snapshot.monthlyTrend,
                selectedMonth = selectedMonth,
                onSelectMonth = { selectedMonth = it.month }
            )
            val activePoint = snapshot.monthlyTrend.firstOrNull { it.month == selectedMonth }
            if (activePoint != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ContinuousRoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${activePoint.year}年${activePoint.label}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (activePoint.totalChars > 0) {
                            "共 ${formatChartCompactChars(activePoint.totalChars)} 字 · 语音 ${
                                formatChartCompactChars(activePoint.voiceChars)
                            } / 键盘 ${formatChartCompactChars(activePoint.keyChars)}"
                        } else {
                            "本月暂无输入记录"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            CalendarHeatmapGrid(
                days = snapshot.heatmapDays,
                selectedDateKey = selectedHeatmapKey,
                onSelectDay = { selectedHeatmapKey = it.dateKey }
            )
            val activeCell = snapshot.heatmapDays.firstOrNull { it.dateKey == selectedHeatmapKey }
            if (activeCell != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ContinuousRoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${activeCell.dateLabel} · 共 ${
                                formatChartCompactChars(activeCell.totalChars)
                            } 字",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val detail = buildString {
                            append("语音 ${formatChartCompactChars(activeCell.voiceChars)} · 键盘 ${formatChartCompactChars(activeCell.keyChars)}")
                            if (activeCell.topAppName.isNotBlank()) {
                                append(" · 常用于 ${activeCell.topAppName}")
                            }
                        }
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        onClick = { onOpenHistoryForDate(activeCell.dateKey) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            0.7.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "看当日",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Rhythm24hCard(snapshot: InsightsSnapshot) {
    val peakHour = remember(snapshot.hourlyVoiceChars, snapshot.hourlyKeyChars) {
        var bestHour = -1
        var bestTotal = 0
        for (h in 0 until 24) {
            val total = snapshot.hourlyVoiceChars.getOrElse(h) { 0 } +
                snapshot.hourlyKeyChars.getOrElse(h) { 0 }
            if (total > bestTotal) {
                bestTotal = total
                bestHour = h
            }
        }
        if (bestHour >= 0) bestHour else LocalTime.now().hour
    }
    var selectedHour by remember(snapshot.range, peakHour) { mutableIntStateOf(peakHour) }

    val hourVoice = snapshot.hourlyVoiceChars.getOrElse(selectedHour) { 0 }.coerceAtLeast(0)
    val hourKey = snapshot.hourlyKeyChars.getOrElse(selectedHour) { 0 }.coerceAtLeast(0)
    val hourTotal = hourVoice + hourKey
    val hourVoicePct = if (hourTotal > 0) ((hourVoice * 100f) / hourTotal).toInt() else 0
    val hourKeyPct = if (hourTotal > 0) (100 - hourVoicePct) else 0

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "24h 双轨作息节律",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (hourTotal > 0) {
                        String.format(
                            Locale.US,
                            "%02d:00 · 语%d%% / 键%d%%",
                            selectedHour,
                            hourVoicePct,
                            hourKeyPct
                        )
                    } else {
                        String.format(Locale.US, "%02d:00 · 无记录", selectedHour)
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        DualTrack24hWaveform(
            hourlyVoice = snapshot.hourlyVoiceChars,
            hourlyKey = snapshot.hourlyKeyChars,
            selectedHour = selectedHour,
            onSelectHour = { selectedHour = it }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContinuousRoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = snapshot.rhythmHeadline.ifBlank { "滑动柱状条探索你的全天表达节奏" },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (hourTotal > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${formatChartCompactChars(hourTotal)} 字",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenesAndIntentsCard(
    snapshot: InsightsSnapshot,
    onOpenStylesSettings: () -> Unit
) {
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val emerald = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val helpWriteColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.80f)

    fun intentColor(mode: VoiceIntentMode): Color = when (mode) {
        VoiceIntentMode.DICTATE -> emerald
        VoiceIntentMode.HELP_ME_WRITE -> helpWriteColor
        VoiceIntentMode.SPEAK_TO_EDIT -> TypeFreeAmber
        VoiceIntentMode.TRANSLATE -> Color(0xFF0EA5E9)
        VoiceIntentMode.JEV_COPILOT -> TypeFreeRose
    }

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "你在哪最爱表达",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onOpenStylesSettings)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "场景风格",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        // 5-intent stacked distribution bar
        if (snapshot.intentShares.isNotEmpty()) {
            IntentStackedBar(
                items = snapshot.intentShares,
                colorOf = { intentColor(it.mode) }
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                snapshot.intentShares.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(intentColor(item.mode))
                        )
                        Text(
                            text = "${item.label} ${item.percent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (snapshot.intentShares.isNotEmpty() && snapshot.topAppScenes.isNotEmpty()) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        }

        // Top App Scene Bars
        if (snapshot.topAppScenes.isEmpty()) {
            Text(
                text = "在不同应用中使用语音或键盘后，这里会呈现你的常用表达场景",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                snapshot.topAppScenes.take(5).forEach { scene ->
                    AppSceneBarRow(
                        item = scene,
                        barColor = emerald,
                        onClick = onOpenStylesSettings
                    )
                }
            }
        }
    }
}

@Composable
private fun IntentStackedBar(
    items: List<IntentShareItem>,
    colorOf: @Composable (IntentShareItem) -> Color
) {
    val colors = items.map { colorOf(it) }
    val total = remember(items) { items.sumOf { it.count.coerceAtLeast(0) } }
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(9.dp)
    ) {
        val r = CornerRadius(size.height / 2f, size.height / 2f)
        val nonZero = items.indices.filter { items[it].count > 0 }
        if (total <= 0 || nonZero.isEmpty()) {
            drawRoundRect(color = emptyColor, size = size, cornerRadius = r)
            return@Canvas
        }
        val gap = 2.dp.toPx()
        val usable = (size.width - gap * (nonZero.size - 1)).coerceAtLeast(1f)
        var x = 0f
        nonZero.forEachIndexed { pos, idx ->
            val segW = if (pos == nonZero.lastIndex) {
                (size.width - x).coerceAtLeast(0f)
            } else {
                (usable * (items[idx].count.toFloat() / total.toFloat())).coerceAtLeast(4.dp.toPx())
            }
            drawRoundRect(
                color = colors[idx],
                topLeft = Offset(x, 0f),
                size = Size(segW, size.height),
                cornerRadius = r
            )
            x += segW + gap
        }
    }
}

@Composable
private fun AppSceneBarRow(
    item: AppSceneShareItem,
    barColor: Color,
    onClick: () -> Unit
) {
    val animatedFrac by animateFloatAsState(
        targetValue = (item.percent / 100f).coerceIn(0.05f, 1f),
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 360f),
        label = "appScene_${item.appName}"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.appName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.sceneCategory.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = item.sceneCategory,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                text = "${item.count} 次 · ${item.percent}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFrac)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(barColor.copy(alpha = 0.85f))
            )
        }
    }
}

@Composable
private fun JevStyleDnaCard(
    snapshot: InsightsSnapshot,
    onOpenJevMemory: () -> Unit
) {
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val emerald = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    var selectedDnaKey by remember { mutableStateOf<StyleFeatureKey?>(null) }
    val expandedItem: JevStyleDnaItem? = remember(snapshot.jevDnaItems, selectedDnaKey) {
        selectedDnaKey?.let { key -> snapshot.jevDnaItems.firstOrNull { it.key == key } }
    }

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "✦ Jev 沟通人格与语体 DNA",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (snapshot.jevSessionCount > 0 || snapshot.jevTotalInteractions > 0) {
                        "${snapshot.jevSessionCount} 个关系档案 · ${snapshot.jevTotalInteractions} 次互动沉淀"
                    } else {
                        "在对话中呼出 Jev 军师后，将自动在端侧学习你的语体 DNA"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onOpenJevMemory)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "管理",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        if (snapshot.jevDnaItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousRoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(12.dp)
            ) {
                Text(
                    text = "尚未形成显著语体标签。多采纳几次 Jev 军师建议，它会记住你偏爱的直接度、标点松弛度与回复节奏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalFadeEdge(startFadeWidth = 6.dp, endFadeWidth = 18.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                snapshot.jevDnaItems.forEach { item ->
                    val isSelected = selectedDnaKey == item.key
                    val accent = if (item.isActive) emerald else TypeFreeAmber
                    val chipText = if (item.isActive) {
                        "✓ ${item.title}"
                    } else {
                        "${item.title} · 观察中 ${item.currentCount}/${item.requiredCount}"
                    }
                    Surface(
                        onClick = {
                            selectedDnaKey = if (isSelected) null else item.key
                        },
                        shape = CircleShape,
                        color = accent.copy(alpha = if (isSelected) 0.22f else 0.12f),
                        border = BorderStroke(
                            width = if (isSelected) 1.3.dp else 0.8.dp,
                            color = accent.copy(alpha = if (isSelected) 0.78f else 0.38f)
                        )
                    ) {
                        Text(
                            text = chipText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            SmoothDisclosure(expanded = expandedItem != null) {
                expandedItem?.let { dna ->
                    val accent = if (dna.isActive) emerald else TypeFreeAmber
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ContinuousRoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .border(0.6.dp, accent.copy(alpha = 0.35f), ContinuousRoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dna.title + if (dna.isManualOverride) " · 手动锁定" else "",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (dna.isActive) {
                                    "已生效 · 倾向 ${dna.emaPercent}%"
                                } else {
                                    "观察中 ${dna.currentCount}/${dna.requiredCount}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = accent
                            )
                        }
                        Text(
                            text = dna.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        snapshot.jevAxisTendency?.let { tendency ->
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
            JevAxisBalanceBar(tendency = tendency)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LexiconAndWordChainsCard(snapshot: InsightsSnapshot) {
    var obscured by rememberSaveable { mutableStateOf(true) }
    val supportsRenderBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "词海与惯用词链",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Surface(
                onClick = { obscured = !obscured },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (obscured) TypeFreeIcons.EyeOff else TypeFreeIcons.Eye,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (obscured) "防窥模糊" else "已显示",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LexiconCounterTile(
                title = "自动纠错词条",
                value = "${snapshot.autoLearnedDictCount}",
                subtitle = "词典共 ${snapshot.totalDictCount}",
                modifier = Modifier.weight(1f)
            )
            LexiconCounterTile(
                title = "私人二元连词",
                value = "${snapshot.personalBigramCount}",
                subtitle = "L2 端侧联想",
                modifier = Modifier.weight(1f)
            )
            LexiconCounterTile(
                title = "私人三元词链",
                value = "${snapshot.personalTrigramCount}",
                subtitle = "上下文预测",
                modifier = Modifier.weight(1f)
            )
        }

        if (snapshot.topWordChains.isEmpty()) {
            Text(
                text = "日常打字积累后，你的高频专属词链会在这里浮现（仅保存在本机，永不进入分享海报）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousRoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                    .clickable { obscured = !obscured }
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (obscured) Modifier.blur(6.dp) else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    snapshot.topWordChains.take(12).forEach { chain ->
                        val displayChain = if (obscured && !supportsRenderBlur) {
                            "•".repeat(chain.length.coerceIn(3, 6))
                        } else {
                            chain
                        }
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .border(
                                    0.6.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    CircleShape
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = displayChain,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (obscured) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                            .border(
                                0.7.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                CircleShape
                            )
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "点按临时查看本机高频词链",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LexiconCounterTile(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(ContinuousRoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            fontSize = 19.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = subtitle,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MilestonesWallCard(snapshot: InsightsSnapshot) {
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val emerald = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val unlockedCount = remember(snapshot.milestones) {
        snapshot.milestones.count { it.unlocked }
    }
    var selectedMilestoneId by remember { mutableStateOf<String?>(null) }
    val selectedMilestone: MilestoneItem? = remember(snapshot.milestones, selectedMilestoneId) {
        selectedMilestoneId?.let { id -> snapshot.milestones.firstOrNull { it.id == id } }
    }

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "永久里程碑印记",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "已点亮 $unlockedCount/${snapshot.milestones.size}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (unlockedCount > 0) emerald else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (snapshot.milestones.isEmpty()) {
            Text(
                text = "你的每一次表达都会永久累积里程碑印记。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                snapshot.milestones.chunked(3).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { item ->
                            val isSelected = selectedMilestoneId == item.id
                            MilestoneTile(
                                item = item,
                                isSelected = isSelected,
                                emerald = emerald,
                                onClick = {
                                    selectedMilestoneId = if (isSelected) null else item.id
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowItems.size < 3) {
                            repeat(3 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            SmoothDisclosure(expanded = selectedMilestone != null) {
                selectedMilestone?.let { ms ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ContinuousRoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .border(
                                0.6.dp,
                                if (ms.unlocked) emerald.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                ContinuousRoundedCornerShape(14.dp)
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${ms.glyph} ${ms.title}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = ms.progressText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (ms.unlocked) emerald else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = ms.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MilestoneTile(
    item: MilestoneItem,
    isSelected: Boolean,
    emerald: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tileShape = ContinuousRoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val bgColor = when {
        item.unlocked -> emerald.copy(alpha = if (isSelected) 0.20f else 0.12f)
        isSelected -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val borderColor = when {
        isSelected && item.unlocked -> emerald
        item.unlocked -> emerald.copy(alpha = 0.42f)
        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }

    Column(
        modifier = modifier
            .springPressScale(0.95f, interactionSource)
            .clip(tileShape)
            .background(bgColor)
            .border(if (isSelected) 1.2.dp else 0.7.dp, borderColor, tileShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = item.glyph,
            fontSize = 20.sp,
            color = if (item.unlocked) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            }
        )
        Text(
            text = item.title,
            fontSize = 11.5.sp,
            fontWeight = if (item.unlocked) FontWeight.Bold else FontWeight.Medium,
            color = if (item.unlocked) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (item.unlocked) {
            Text(
                text = "已点亮",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = emerald
            )
        } else {
            val progress = (item.progressPercent / 100f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(3.5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceAtLeast(0.06f))
                        .height(3.5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                )
            }
            Text(
                text = "${item.progressPercent}%",
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InsightsPrivacyFooterCard(
    snapshot: InsightsSnapshot,
    onOpenPrivacySettings: () -> Unit
) {
    val shape = ContinuousRoundedCornerShape(20.dp)
    Surface(
        onClick = onOpenPrivacySettings,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "🔒 所有累计统计均为纯数字本地存储 · 0 正文",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "已记录 ${snapshot.statsTrackedDays} 天 · 约 ${
                        formatDbSize(snapshot.statsDbSizeBytes)
                    } · 无痕与密码框强制零计数",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "管理与重置",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
}

private fun formatLatency(latencyMs: Long): String {
    return if (latencyMs >= 1000L) {
        String.format(Locale.US, "%.1fs", latencyMs / 1000.0)
    } else {
        "${latencyMs}ms"
    }
}

private fun formatDbSize(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe < 1024L -> "${safe} B"
        safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", safe / (1024.0 * 1024.0))
    }
}
