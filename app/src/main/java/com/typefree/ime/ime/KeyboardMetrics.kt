package com.typefree.ime.ime

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Unified keyboard height and scaling metrics for TypeFree v2.7.
 *
 * Rules:
 * - Only the key area scales; the 34.dp top bar and 1.dp top divider remain fixed across all levels.
 * - All panels (VOICE, QWERTY, TRANSLATE, CLIPBOARD, TOOL_PANEL, HELP_ME_WRITE, SPEAK_TO_EDIT, ArcTranslate)
 *   read [frameHeight] from [LocalKeyboardMetrics] so switching panels causes 0px host relayout.
 * - Fixed-size elements on the Voice panel (mic orb, waveform) scale by [elementScale] = `min(scale, 1f)`
 *   (shrink-only, never bloated in comfortable mode).
 * - Landscape mode automatically applies `COMPACT_SCALE * 0.8f`.
 */
@Immutable
data class KeyboardMetrics(
    val level: Int,
    val isLandscape: Boolean,
    val scale: Float,
    val elementScale: Float,
    val topBarHeight: Dp,
    val dividerHeight: Dp,
    val keyAreaHeight: Dp,
    val frameHeight: Dp,
    val label: String
) {
    companion object {
        const val MIN_LEVEL = -2
        const val MAX_LEVEL = 2
        const val DEFAULT_LEVEL = 0

        const val SCALE_COMPACT = 0.88f
        const val SCALE_LOW = 0.94f
        const val SCALE_STANDARD = 1.00f
        const val SCALE_HIGH = 1.07f
        const val SCALE_COMFORTABLE = 1.14f
        const val LANDSCAPE_FACTOR = 0.80f

        private const val BASE_KEY_AREA_DP = 250f
        private const val FIXED_CHROME_DP = 48 // 1.dp divider + 8.dp top pad + 34.dp top bar + 5.dp gap

        val ALL_LEVELS: List<Int> = listOf(-2, -1, 0, 1, 2)

        fun scaleForLevel(level: Int): Float = when (level.coerceIn(MIN_LEVEL, MAX_LEVEL)) {
            -2 -> SCALE_COMPACT
            -1 -> SCALE_LOW
            0 -> SCALE_STANDARD
            1 -> SCALE_HIGH
            2 -> SCALE_COMFORTABLE
            else -> SCALE_STANDARD
        }

        fun labelForLevel(level: Int): String = when (level.coerceIn(MIN_LEVEL, MAX_LEVEL)) {
            -2 -> "紧凑"
            -1 -> "偏低"
            0 -> "标准"
            1 -> "偏高"
            2 -> "舒适"
            else -> "标准"
        }

        fun fromLevel(level: Int, isLandscape: Boolean = false): KeyboardMetrics {
            val clamped = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
            val effectiveScale = if (isLandscape) {
                SCALE_COMPACT * LANDSCAPE_FACTOR
            } else {
                scaleForLevel(clamped)
            }
            val keyAreaDp = (BASE_KEY_AREA_DP * effectiveScale).roundToInt()
            val frameDp = FIXED_CHROME_DP + keyAreaDp
            return KeyboardMetrics(
                level = clamped,
                isLandscape = isLandscape,
                scale = effectiveScale,
                elementScale = min(effectiveScale, 1.0f),
                topBarHeight = 34.dp,
                dividerHeight = 1.dp,
                keyAreaHeight = keyAreaDp.dp,
                frameHeight = frameDp.dp,
                label = labelForLevel(clamped)
            )
        }
    }
}

val LocalKeyboardMetrics = staticCompositionLocalOf {
    KeyboardMetrics.fromLevel(KeyboardMetrics.DEFAULT_LEVEL)
}
