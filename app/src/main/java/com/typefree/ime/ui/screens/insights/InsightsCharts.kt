package com.typefree.ime.ui.screens.insights

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.stats.HeatmapDayCell
import com.typefree.ime.data.stats.JevAxisTendencySummary
import com.typefree.ime.data.stats.MonthlyTrendPoint
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeEmeraldLight
import java.util.Locale

internal fun formatChartCompactChars(chars: Int): String {
    val safe = chars.coerceAtLeast(0)
    return if (safe >= 10_000) {
        String.format(Locale.US, "%.1fw", safe / 10_000.0)
    } else {
        String.format(Locale.US, "%,d", safe)
    }
}

/**
 * Compact 7-bar vertical activity waveform used in the Home HeroMetricsCard.
 * Highlights today's bar (rightmost) in TypeFreeEmerald while earlier bars graduate in primary alpha.
 */
@Composable
fun MiniSevenDayWaveform(
    values: List<Int>,
    modifier: Modifier = Modifier
) {
    val normalized = remember(values) {
        val tail = values.takeLast(7).map { it.coerceAtLeast(0) }
        List(7 - tail.size) { 0 } + tail
    }
    val maxVal = remember(normalized) {
        normalized.maxOrNull()?.coerceAtLeast(1) ?: 1
    }

    val animatedFractions = normalized.mapIndexed { idx, value ->
        val target = if (value <= 0) 0f else (value.toFloat() / maxVal.toFloat()).coerceIn(0.14f, 1f)
        val animated by animateFloatAsState(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
            label = "miniWaveBar_$idx"
        )
        animated
    }

    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val primaryColor = MaterialTheme.colorScheme.primary
    val todayColor = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val emptyBarColor = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.45f else 0.55f)

    Canvas(
        modifier = modifier.defaultMinSize(minWidth = 76.dp, minHeight = 32.dp)
    ) {
        if (size.width <= 1f || size.height <= 1f) return@Canvas
        val count = 7
        val gap = (size.width * 0.065f).coerceIn(2.5.dp.toPx(), 5.dp.toPx())
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(3.dp.toPx())
        val minBarHeight = (size.height * 0.14f).coerceAtLeast(3.dp.toPx())
        val corner = CornerRadius(barWidth / 2f, barWidth / 2f)

        for (i in 0 until count) {
            val rawValue = normalized[i]
            val frac = animatedFractions[i]
            val barHeight = if (rawValue <= 0) {
                minBarHeight
            } else {
                (minBarHeight + (size.height - minBarHeight) * frac).coerceAtMost(size.height)
            }
            val x = i * (barWidth + gap)
            val y = size.height - barHeight

            val barColor = when {
                rawValue <= 0 -> emptyBarColor
                i == count - 1 -> todayColor
                else -> {
                    val alpha = 0.24f + (i.toFloat() / (count - 1).toFloat()) * 0.42f
                    primaryColor.copy(alpha = alpha)
                }
            }

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = corner
            )
        }
    }
}

/**
 * Dual-track horizontal split bar comparing Voice dictation chars vs Keyboard chars.
 */
@Composable
fun DualTrackRatioBar(
    voiceChars: Int,
    keyChars: Int,
    modifier: Modifier = Modifier
) {
    val safeVoice = voiceChars.coerceAtLeast(0)
    val safeKey = keyChars.coerceAtLeast(0)
    val total = safeVoice + safeKey
    val voicePercent = if (total > 0) ((safeVoice * 100f) / total).toInt().coerceIn(0, 100) else 0
    val keyPercent = if (total > 0) (100 - voicePercent).coerceIn(0, 100) else 0

    val targetVoiceFrac = if (total > 0) safeVoice.toFloat() / total.toFloat() else 0f
    val animatedVoiceFrac by animateFloatAsState(
        targetValue = targetVoiceFrac,
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 340f),
        label = "dualTrackVoiceFraction"
    )

    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val voiceColor = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val keyColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.72f else 0.78f)
    val emptyTrackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(voiceColor)
                )
                Text(
                    text = "语音口述 $voicePercent%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${formatChartCompactChars(safeVoice)} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "${formatChartCompactChars(safeKey)} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "键盘 $keyPercent%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(keyColor)
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
        ) {
            if (size.width <= 1f || size.height <= 1f) return@Canvas
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            if (total <= 0) {
                drawRoundRect(
                    color = emptyTrackColor,
                    size = size,
                    cornerRadius = radius
                )
                return@Canvas
            }

            val gap = 2.5.dp.toPx()
            val minSeg = 8.dp.toPx().coerceAtMost(size.width * 0.25f)

            when {
                safeVoice > 0 && safeKey == 0 -> {
                    drawRoundRect(color = voiceColor, size = size, cornerRadius = radius)
                }
                safeKey > 0 && safeVoice == 0 -> {
                    drawRoundRect(color = keyColor, size = size, cornerRadius = radius)
                }
                else -> {
                    val usableWidth = (size.width - gap).coerceAtLeast(minSeg * 2f)
                    val voiceWidth = (usableWidth * animatedVoiceFrac).coerceIn(minSeg, usableWidth - minSeg)
                    val keyWidth = (usableWidth - voiceWidth).coerceAtLeast(minSeg)

                    drawRoundRect(
                        color = voiceColor,
                        topLeft = Offset.Zero,
                        size = Size(voiceWidth, size.height),
                        cornerRadius = radius
                    )
                    drawRoundRect(
                        color = keyColor,
                        topLeft = Offset(voiceWidth + gap, 0f),
                        size = Size(keyWidth, size.height),
                        cornerRadius = radius
                    )
                }
            }
        }
    }
}

/**
 * Interactive 56-day (4 rows × 14 columns) activity heatmap grid adapting to Light & Dark themes.
 */
@Composable
fun CalendarHeatmapGrid(
    days: List<HeatmapDayCell>,
    selectedDateKey: Int?,
    onSelectDay: (HeatmapDayCell) -> Unit,
    modifier: Modifier = Modifier
) {
    if (days.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(84.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无热力足迹数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val selectionRingColor = if (isDark) TypeFreeAmber else MaterialTheme.colorScheme.onSurface

    fun levelColor(level: Int): Color = when (level.coerceIn(0, 4)) {
        0 -> surfaceVariant.copy(alpha = if (isDark) 0.55f else 0.90f)
        1 -> TypeFreeEmerald.copy(alpha = if (isDark) 0.28f else 0.24f)
        2 -> TypeFreeEmerald.copy(alpha = if (isDark) 0.55f else 0.52f)
        3 -> if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
        else -> primaryColor
    }

    val columns = if (days.size <= 28) 7 else 14
    val rows = remember(days, columns) { days.chunked(columns) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        rows.forEach { rowCells ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.5.dp)
            ) {
                rowCells.forEach { cell ->
                    val isSelected = selectedDateKey != null && cell.dateKey == selectedDateKey
                    val tileShape = RoundedCornerShape(4.dp)
                    val interactionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(tileShape)
                            .background(levelColor(cell.intensityLevel))
                            .then(
                                if (isSelected) {
                                    Modifier.border(1.8.dp, selectionRingColor, tileShape)
                                } else {
                                    Modifier.border(
                                        0.5.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                        tileShape
                                    )
                                }
                            )
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                onSelectDay(cell)
                            }
                    )
                }
                if (rowCells.size < columns) {
                    repeat(columns - rowCells.size) {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${days.first().dateLabel} – ${days.last().dateLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "轻",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
                for (lvl in 0..4) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(levelColor(lvl))
                    )
                }
                Text(
                    text = "高产",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }
    }
}

/**
 * 12-month clickable stacked bar chart (TypeFreeEmerald voice on top + primary keyboard below).
 */
@Composable
fun MonthlyStackedTrendChart(
    points: List<MonthlyTrendPoint>,
    selectedMonth: Int,
    onSelectMonth: (MonthlyTrendPoint) -> Unit,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(128.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无月度成长记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val voiceBaseColor = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val keyBaseColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.28f else 0.35f)
    val selectedHighlightBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.55f else 0.75f)
    val futureStubColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)

    val maxTotal = remember(points) {
        points.maxOfOrNull { it.totalChars }?.coerceAtLeast(1) ?: 1
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(126.dp)
        ) {
            // Subtle horizontal reference gridlines
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(102.dp)
                    .align(Alignment.TopCenter)
            ) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                listOf(0.08f, 0.54f, 0.99f).forEachIndexed { idx, yFrac ->
                    val y = size.height * yFrac
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 0.8.dp.toPx(),
                        pathEffect = if (idx < 2) dash else null
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                points.forEach { point ->
                    val isSelected = point.month == selectedMonth
                    val targetTotalFrac = if (point.isFuture || point.totalChars <= 0) {
                        0f
                    } else {
                        (point.totalChars.toFloat() / maxTotal.toFloat()).coerceIn(0.08f, 1f)
                    }
                    val targetVoiceShare = if (point.totalChars > 0) {
                        point.voiceChars.toFloat() / point.totalChars.toFloat()
                    } else {
                        0f
                    }

                    val animTotalFrac by animateFloatAsState(
                        targetValue = targetTotalFrac,
                        animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
                        label = "monthTotal_${point.month}"
                    )
                    val animVoiceShare by animateFloatAsState(
                        targetValue = targetVoiceShare,
                        animationSpec = spring(dampingRatio = 0.80f, stiffness = 360f),
                        label = "monthVoice_${point.month}"
                    )

                    val interactionSource = remember { MutableInteractionSource() }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) selectedHighlightBg else Color.Transparent)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                onSelectMonth(point)
                            }
                            .padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 3.dp, vertical = 2.dp)
                        ) {
                            val barW = (size.width * 0.72f).coerceIn(5.dp.toPx(), 13.dp.toPx())
                            val left = (size.width - barW) / 2f
                            val radius = CornerRadius(barW / 2f, barW / 2f)

                            if (point.isFuture || point.totalChars <= 0) {
                                val stubH = 3.5.dp.toPx()
                                drawRoundRect(
                                    color = if (point.isFuture) futureStubColor else gridColor.copy(alpha = 0.55f),
                                    topLeft = Offset(left, size.height - stubH),
                                    size = Size(barW, stubH),
                                    cornerRadius = radius
                                )
                                return@Canvas
                            }

                            val totalH = (size.height * animTotalFrac).coerceAtLeast(6.dp.toPx())
                            val voiceH = if (point.voiceChars > 0) {
                                (totalH * animVoiceShare).coerceAtLeast(3.dp.toPx())
                            } else {
                                0f
                            }
                            val keyH = if (point.keyChars > 0) {
                                (totalH - voiceH).coerceAtLeast(3.dp.toPx())
                            } else {
                                0f
                            }
                            val vColor = voiceBaseColor.copy(alpha = if (isSelected) 1f else 0.74f)
                            val kColor = keyBaseColor.copy(alpha = if (isSelected) 0.86f else 0.42f)

                            if (keyH > 0f) {
                                val clampedKeyH = keyH.coerceAtMost(size.height)
                                drawRoundRect(
                                    color = kColor,
                                    topLeft = Offset(left, size.height - clampedKeyH),
                                    size = Size(barW, clampedKeyH),
                                    cornerRadius = radius
                                )
                            }
                            if (voiceH > 0f) {
                                val gap = if (keyH > 0f) 1.5.dp.toPx() else 0f
                                val clampedVoiceH = voiceH.coerceAtMost((size.height - keyH - gap).coerceAtLeast(3.dp.toPx()))
                                val topY = (size.height - keyH - gap - clampedVoiceH).coerceAtLeast(0f)
                                drawRoundRect(
                                    color = vColor,
                                    topLeft = Offset(left, topY),
                                    size = Size(barW, clampedVoiceH),
                                    cornerRadius = radius
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${point.month}",
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                isSelected -> MaterialTheme.colorScheme.onSurface
                                point.isFuture -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Compact legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(voiceBaseColor)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "语音",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(keyBaseColor.copy(alpha = 0.72f))
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "键盘",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp
            )
        }
    }
}

/**
 * 24-hour dual-track stacked waveform supporting both tap selection and smooth horizontal drag scrubbing.
 */
@Composable
fun DualTrack24hWaveform(
    hourlyVoice: List<Int>,
    hourlyKey: List<Int>,
    selectedHour: Int,
    onSelectHour: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeVoice = remember(hourlyVoice) {
        List(24) { idx -> hourlyVoice.getOrElse(idx) { 0 }.coerceAtLeast(0) }
    }
    val safeKey = remember(hourlyKey) {
        List(24) { idx -> hourlyKey.getOrElse(idx) { 0 }.coerceAtLeast(0) }
    }
    val safeSelectedHour = selectedHour.coerceIn(0, 23)
    val maxTotal = remember(safeVoice, safeKey) {
        (0 until 24).maxOfOrNull { safeVoice[it] + safeKey[it] }?.coerceAtLeast(1) ?: 1
    }

    val latestOnSelect by rememberUpdatedState(onSelectHour)
    val haptic = LocalHapticFeedback.current
    var lastHapticHour by remember { mutableIntStateOf(safeSelectedHour) }

    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val voiceColor = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val keyColor = MaterialTheme.colorScheme.primary
    val stubColor = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.3f else 0.42f)
    val highlightPillColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.72f else 0.9f)
    val baselineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)

    // Smoothly animate overall amplitude changes when switching time ranges
    val animatedTotals = (0 until 24).map { hour ->
        val total = safeVoice[hour] + safeKey[hour]
        val target = if (total <= 0) 0f else (total.toFloat() / maxTotal.toFloat()).coerceIn(0.08f, 1f)
        val frac by animateFloatAsState(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.80f, stiffness = 380f),
            label = "hourBar_$hour"
        )
        frac
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val hour = ((offset.x / size.width.coerceAtLeast(1)) * 24f)
                            .toInt()
                            .coerceIn(0, 23)
                        lastHapticHour = hour
                        latestOnSelect(hour)
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val hour = ((offset.x / size.width.coerceAtLeast(1)) * 24f)
                                .toInt()
                                .coerceIn(0, 23)
                            if (hour != lastHapticHour) {
                                lastHapticHour = hour
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            latestOnSelect(hour)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val hour = ((change.position.x / size.width.coerceAtLeast(1)) * 24f)
                                .toInt()
                                .coerceIn(0, 23)
                            if (hour != lastHapticHour) {
                                lastHapticHour = hour
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                latestOnSelect(hour)
                            }
                        }
                    )
                }
        ) {
            if (size.width <= 1f || size.height <= 1f) return@Canvas
            val slotW = size.width / 24f
            val barW = (slotW * 0.56f).coerceIn(3.5.dp.toPx(), 9.dp.toPx()).coerceAtMost(slotW)
            val chartBottom = (size.height - 2.dp.toPx()).coerceAtLeast(1f)
            val maxBarH = (chartBottom - 6.dp.toPx()).coerceAtLeast(1f)
            val barCorner = CornerRadius(barW / 2f, barW / 2f)

            // Baseline
            drawLine(
                color = baselineColor,
                start = Offset(0f, chartBottom),
                end = Offset(size.width, chartBottom),
                strokeWidth = 0.8.dp.toPx()
            )

            // Selected hour soft highlight column
            val selCenterX = safeSelectedHour * slotW + slotW / 2f
            val pillW = (slotW * 1.15f)
                .coerceAtLeast(barW + 6.dp.toPx())
                .coerceAtMost(size.width)
            val maxPillX = (size.width - pillW).coerceAtLeast(0f)
            drawRoundRect(
                color = highlightPillColor,
                topLeft = Offset((selCenterX - pillW / 2f).coerceIn(0f, maxPillX), 0f),
                size = Size(pillW, chartBottom),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )

            for (hour in 0 until 24) {
                val v = safeVoice[hour]
                val k = safeKey[hour]
                val total = v + k
                val isSelected = hour == safeSelectedHour
                val left = hour * slotW + (slotW - barW) / 2f

                if (total <= 0) {
                    val stubH = 3.dp.toPx()
                    drawRoundRect(
                        color = if (isSelected) keyColor.copy(alpha = 0.45f) else stubColor,
                        topLeft = Offset(left, chartBottom - stubH),
                        size = Size(barW, stubH),
                        cornerRadius = barCorner
                    )
                    continue
                }

                val totalH = (maxBarH * animatedTotals[hour]).coerceAtLeast(6.dp.toPx())
                val voiceShare = v.toFloat() / total.toFloat()
                val voiceH = if (v > 0) (totalH * voiceShare).coerceAtLeast(3.dp.toPx()) else 0f
                val keyH = if (k > 0) (totalH - voiceH).coerceAtLeast(3.dp.toPx()) else 0f

                val kAlpha = if (isSelected) 0.90f else 0.42f
                val vAlpha = if (isSelected) 1.0f else 0.72f

                if (keyH > 0f) {
                    val clampedK = keyH.coerceAtMost(maxBarH)
                    drawRoundRect(
                        color = keyColor.copy(alpha = kAlpha),
                        topLeft = Offset(left, chartBottom - clampedK),
                        size = Size(barW, clampedK),
                        cornerRadius = barCorner
                    )
                }
                if (voiceH > 0f) {
                    val gap = if (keyH > 0f) 1.2.dp.toPx() else 0f
                    val clampedV = voiceH.coerceAtMost((maxBarH - keyH - gap).coerceAtLeast(3.dp.toPx()))
                    val topY = (chartBottom - keyH - gap - clampedV).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(
                        color = voiceColor.copy(alpha = vAlpha),
                        topLeft = Offset(left, topY),
                        size = Size(barW, clampedV),
                        cornerRadius = barCorner
                    )
                }
            }
        }

        // 5-point time axis labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("00:00", "06:00", "12:00", "18:00", "23:00").forEach { tick ->
                Text(
                    text = tick,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
                )
            }
        }
    }
}

/**
 * 3-segment proportional strategy balance bar for Jev communication axis tendency.
 */
@Composable
fun JevAxisBalanceBar(
    tendency: JevAxisTendencySummary,
    modifier: Modifier = Modifier
) {
    val count = minOf(tendency.labels.size, tendency.percents.size)
    if (count == 0) return

    val safePercents = remember(tendency) {
        (0 until count).map { tendency.percents[it].coerceAtLeast(0) }
    }
    val sum = remember(safePercents) { safePercents.sum() }
    val fractions = safePercents.mapIndexed { idx, pct ->
        val target = if (sum > 0) pct.toFloat() / sum.toFloat() else 1f / count.toFloat()
        val animated by animateFloatAsState(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.80f, stiffness = Spring.StiffnessMediumLow),
            label = "jevAxisSeg_$idx"
        )
        animated
    }

    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val segmentColors = listOf(
        if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald,
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.76f else 0.82f),
        TypeFreeAmber,
        MaterialTheme.colorScheme.outline
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tendency.groupTitle,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${tendency.sampleCount} 次参考",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(9.dp)
        ) {
            val nonZeroIndices = (0 until count).filter { fractions[it] > 0.005f }
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            if (nonZeroIndices.isEmpty()) {
                drawRoundRect(
                    color = segmentColors.last().copy(alpha = 0.3f),
                    size = size,
                    cornerRadius = radius
                )
                return@Canvas
            }

            val gap = 2.5.dp.toPx()
            val totalGap = gap * (nonZeroIndices.size - 1)
            val usableW = (size.width - totalGap).coerceAtLeast(1f)
            val activeSum = nonZeroIndices.sumOf { fractions[it].toDouble() }.toFloat().coerceAtLeast(0.001f)
            var cursorX = 0f

            nonZeroIndices.forEachIndexed { pos, segIdx ->
                val segW = if (pos == nonZeroIndices.lastIndex) {
                    (size.width - cursorX).coerceAtLeast(0f)
                } else {
                    (usableW * (fractions[segIdx] / activeSum)).coerceAtLeast(4.dp.toPx())
                }
                drawRoundRect(
                    color = segmentColors[segIdx % segmentColors.size],
                    topLeft = Offset(cursorX, 0f),
                    size = Size(segW, size.height),
                    cornerRadius = radius
                )
                cursorX += segW + gap
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until count) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(segmentColors[i % segmentColors.size])
                    )
                    Text(
                        text = "${tendency.labels[i]} ${safePercents[i]}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
