package com.typefree.ime.ime

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.CandidateFontScale
import com.typefree.ime.data.KeyboardHeightMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Unified keyboard height, bottom lift, and candidate strip scaling metrics for TypeFree.
 *
 * Rules:
 * - Only the key area scales vertically by [scale]; the top bar (`34/38/44.dp` per [candidateFontScale])
 *   and `1.dp` top divider remain fixed across key height levels.
 * - In decoupled v3.9.1+ mode ([resolve] or `fromLevel(..., decoupledLiftDp = ...)`), key-area vertical
 *   scaling (`-2..+2` -> `0.88f..1.14f`) and portrait bottom cushion (`0..56.dp`) combine additively
 *   without resetting [scale] to `1.0f`.
 * - All panels (VOICE, QWERTY, TRANSLATE, CLIPBOARD, TOOL_PANEL, HELP_ME_WRITE, SPEAK_TO_EDIT, ArcTranslate)
 *   read [frameHeight] from [LocalKeyboardMetrics] so switching panels causes 0px host relayout.
 * - Fixed-size elements on the Voice panel (mic orb, waveform) scale by [elementScale] = `min(scale, 1f)`
 *   (shrink-only, never bloated in comfortable mode).
 * - Landscape mode automatically applies `COMPACT_SCALE * 0.8f` and `0.dp` bottom cushion.
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
    val label: String,
    /** Legacy mode flag; preserved for v2.9..v3.9.0 callers. */
    val heightMode: KeyboardHeightMode = KeyboardHeightMode.LIFT,
    val liftLevel: Int = 0,
    /** Blank bottom cushion inside [frameHeight] (0.dp in landscape). */
    val liftHeight: Dp = 0.dp,
    val showNumberRow: Boolean = false,
    /** Gently coupled keycap font multiplier (`0.94f..1.08f` * [widthScale] in portrait) avoiding swipe-badge collisions at `-2`. */
    val keyFontScale: Float = KEY_FONT_SCALE_STANDARD,
    val candidateFontScale: CandidateFontScale = CandidateFontScale.STANDARD,
    val candidateChipHeight: Dp = 29.dp,
    val candidateFontSizeSp: Float = 15.5f,
    val fixedChromeDp: Int = FIXED_CHROME_DP,
    /** Cross-device viewport width scale normalized to Xiaomi 15 (`375.dp` = `1.0f`, clamped `0.92f..1.16f`, capped `1.12f` at `>= 600.dp`). */
    val widthScale: Float = 1.0f,
    val preeditFontSizeSp: Float = 13.5f,
    val preeditCapsuleHeight: Dp = 24.dp,
    val topBarActionButtonSize: Dp = 28.dp
) {
    companion object {
        const val MIN_LEVEL = -2
        const val MAX_LEVEL = 2
        const val DEFAULT_LEVEL = 0

        const val BASELINE_SCREEN_WIDTH_DP = 375
        const val TABLET_WIDTH_CUTOFF_DP = 600
        const val MIN_WIDTH_SCALE = 0.92f
        const val MAX_WIDTH_SCALE = 1.16f
        const val TABLET_MAX_WIDTH_SCALE = 1.12f
        private const val WIDTH_HEIGHT_DAMPING = 0.88f
        private const val CHROME_OVERHEAD_DP = 8 // 1.dp divider + 5.dp top pad + 2.dp gap

        const val SCALE_COMPACT = 0.88f
        const val SCALE_LOW = 0.94f
        const val SCALE_STANDARD = 1.00f
        const val SCALE_HIGH = 1.07f
        const val SCALE_COMFORTABLE = 1.14f
        const val LANDSCAPE_FACTOR = 0.80f

        const val KEY_FONT_SCALE_COMPACT = 0.94f
        const val KEY_FONT_SCALE_LOW = 0.97f
        const val KEY_FONT_SCALE_STANDARD = 1.00f
        const val KEY_FONT_SCALE_HIGH = 1.04f
        const val KEY_FONT_SCALE_COMFORTABLE = 1.08f
        const val KEY_FONT_SCALE_LANDSCAPE = 0.90f

        private const val BASE_KEY_AREA_4ROW_DP = 210f
        private const val BASE_KEY_AREA_5ROW_DP = 252f
        const val FIXED_CHROME_DP = 42 // STANDARD: 1.dp divider + 5.dp top pad + 34.dp top bar + 2.dp gap
        const val FIXED_CHROME_LARGE_DP = 46 // LARGE: 1.dp divider + 5.dp top pad + 38.dp top bar + 2.dp gap
        const val FIXED_CHROME_EXTRA_LARGE_DP = 52 // EXTRA_LARGE: 1.dp divider + 5.dp top pad + 44.dp top bar + 2.dp gap

        /** Transparent floating preedit capsule overhang above the 1.dp keyboard divider (`0px` host viewport loss via `contentTopInsets`). */
        const val FLOATING_PREEDIT_OVERHANG_DP = 28

        const val MIN_BOTTOM_LIFT_DP = 0
        const val MAX_BOTTOM_LIFT_DP = 56
        const val DEFAULT_BOTTOM_LIFT_DP = 0

        fun computeFloatingOverhangContentTopInset(
            baseContentTopInsets: Int,
            floatingOverhangPx: Int,
            decorHeight: Int
        ): Int {
            if (floatingOverhangPx <= 0 || decorHeight <= floatingOverhangPx) {
                return baseContentTopInsets
            }
            return (baseContentTopInsets + floatingOverhangPx).coerceAtMost(decorHeight)
        }

        /**
         * Computes the uncovered bottom system-bar overlap for our IME [ComposeView] inside
         * `SoftInputWindow`:
         * - On ROMs like vivo OriginOS 7 / MagicOS 11 where the system earth/switcher bar overlays
         *   the bottom of `inputArea` (`inputBottomInWindowPx == decorHeightPx`, `reserved == 0`),
         *   returns the full [rawDecorBottomInsetPx] so bottom keys are never covered.
         * - On ROMs like Samsung One UI where the framework already places `inputArea` (or the
         *   entire IME window) above the bottom navigation bar (`reserved >= raw`), deducts the
         *   already-reserved height so bottom padding is never double-counted.
         * - Before the first layout pass (`!inputLaidOut || inputHeightPx <= 0`), retains
         *   [previousEffectiveBottomInsetPx] clamped to `[0, raw]` so pre-layout zero bounds never
         *   falsely wipe out real overlap or cause a transient jump.
         */
        fun resolveDecorOverlapBottomInset(
            rawDecorBottomInsetPx: Int,
            decorHeightPx: Int,
            inputBottomInWindowPx: Int,
            inputHeightPx: Int,
            inputLaidOut: Boolean,
            decorBottomOnScreenPx: Int = 0,
            screenBottomPx: Int = 0,
            previousEffectiveBottomInsetPx: Int = 0
        ): ImeBottomInsetGeometry {
            val safeRaw = rawDecorBottomInsetPx.coerceAtLeast(0)
            val safeDecorH = decorHeightPx.coerceAtLeast(0)
            val safeInputBot = inputBottomInWindowPx.coerceAtLeast(0)
            val safeDecorScrBot = decorBottomOnScreenPx.coerceAtLeast(0)
            val safeScrBot = screenBottomPx.coerceAtLeast(0)
            if (safeRaw == 0) {
                return ImeBottomInsetGeometry(
                    rawDecorBottomInsetPx = 0,
                    reservedBelowInputPx = 0,
                    effectiveBottomInsetPx = 0,
                    decorHeightPx = safeDecorH,
                    inputBottomInWindowPx = safeInputBot,
                    decorBottomOnScreenPx = safeDecorScrBot,
                    screenBottomPx = safeScrBot,
                    inputLaidOut = inputLaidOut
                )
            }
            if (!inputLaidOut || inputHeightPx <= 0 || safeDecorH <= 0 || safeInputBot <= 0) {
                val heldEffective = previousEffectiveBottomInsetPx.coerceIn(0, safeRaw)
                return ImeBottomInsetGeometry(
                    rawDecorBottomInsetPx = safeRaw,
                    reservedBelowInputPx = 0,
                    effectiveBottomInsetPx = heldEffective,
                    decorHeightPx = safeDecorH,
                    inputBottomInWindowPx = safeInputBot,
                    decorBottomOnScreenPx = safeDecorScrBot,
                    screenBottomPx = safeScrBot,
                    inputLaidOut = false
                )
            }
            val inWindowReservedPx = (safeDecorH - safeInputBot).coerceAtLeast(0)
            val rawBelowDecorOnScreenPx = if (safeScrBot > 0 && safeDecorScrBot > 0 && safeScrBot > safeDecorScrBot) {
                safeScrBot - safeDecorScrBot
            } else {
                0
            }
            // Ignore implausibly large screen-bottom gaps (e.g. unsynced WRAP_CONTENT mWindowTop == 0
            // before WindowManager positions the IME window) so OriginOS/MagicOS overlap is never lost.
            val belowDecorOnScreenPx = if (rawBelowDecorOnScreenPx <= safeRaw * 2) {
                rawBelowDecorOnScreenPx
            } else {
                0
            }
            val totalReservedPx = inWindowReservedPx + belowDecorOnScreenPx
            val effectivePx = (safeRaw - totalReservedPx).coerceAtLeast(0)
            return ImeBottomInsetGeometry(
                rawDecorBottomInsetPx = safeRaw,
                reservedBelowInputPx = totalReservedPx,
                inWindowReservedPx = inWindowReservedPx,
                effectiveBottomInsetPx = effectivePx,
                decorHeightPx = safeDecorH,
                inputBottomInWindowPx = safeInputBot,
                decorBottomOnScreenPx = safeDecorScrBot,
                screenBottomPx = safeScrBot,
                inputLaidOut = true
            )
        }

        /**
         * Resolves the height of the opaque bottom backdrop strip placed at index `0` of the IME
         * `DecorView` (strictly below `inputView` and behind the system `NavigationBarFrame`).
         * Prevents underlying app content (such as WeChat bottom links) from bleeding through a
         * transparent navigation bar on Android 15/16 (`targetSdk = 35`) devices like Samsung
         * One UI where `Window.setNavigationBarColor` is ignored and `inputArea` sits above the
         * navigation bar, while staying `0` (`GONE`) on OriginOS 7 / MagicOS 11 or during Jev peek.
         */
        fun resolveNavBarBackdropHeightPx(
            geometry: ImeBottomInsetGeometry,
            isFullScreenPeekCapturing: Boolean
        ): Int {
            if (isFullScreenPeekCapturing || !geometry.inputLaidOut) return 0
            return geometry.inWindowReservedPx.coerceAtLeast(0)
        }

        /**
         * Combines Compose's own `WindowInsets.systemBars` bottom inset (with any already-reserved
         * frame space below `ComposeView` deducted) and the overlap-deducted decor inset.
         */
        fun resolveEffectiveBottomInsetPx(
            composeSystemBarsBottomPx: Int,
            decorEffectiveBottomInsetPx: Int,
            reservedBelowInputPx: Int = 0
        ): Int {
            val deductedComposePx = (
                composeSystemBarsBottomPx.coerceAtLeast(0) - reservedBelowInputPx.coerceAtLeast(0)
            ).coerceAtLeast(0)
            return max(deductedComposePx, decorEffectiveBottomInsetPx.coerceAtLeast(0))
        }

        /**
         * Resolves the bottom safe-area inset for the IME window on Android 15/16 (`targetSdk = 35`)
         * including vivo OriginOS 7's `imeCaptionBar` / `NavigationBarFrame` (🌐 switcher bar) even
         * when `@android:layout/input_method` swallows child [ComposeView] `navigationBars` insets,
         * while deducting any space already reserved below `ComposeView` (e.g. Samsung One UI's
         * system IME navigation bar) so bottom bars are never double-counted.
         */
        @Composable
        fun resolveImeSystemBottomInsetDp(
            decorBottomInsetPx: Int,
            reservedBelowInputPx: Int = 0,
            onComposeSystemBarsBottomPx: ((Int) -> Unit)? = null
        ): Dp {
            val density = LocalDensity.current
            val composeBottomPx = WindowInsets.systemBars.getBottom(density)
            if (onComposeSystemBarsBottomPx != null) {
                SideEffect {
                    onComposeSystemBarsBottomPx(composeBottomPx)
                }
            }
            val effectivePx = resolveEffectiveBottomInsetPx(
                composeSystemBarsBottomPx = composeBottomPx,
                decorEffectiveBottomInsetPx = decorBottomInsetPx,
                reservedBelowInputPx = reservedBelowInputPx
            )
            return with(density) { effectivePx.toDp() }
        }

        val ALL_LEVELS: List<Int> = listOf(-2, -1, 0, 1, 2)

        fun scaleForLevel(level: Int): Float = when (level.coerceIn(MIN_LEVEL, MAX_LEVEL)) {
            -2 -> SCALE_COMPACT
            -1 -> SCALE_LOW
            0 -> SCALE_STANDARD
            1 -> SCALE_HIGH
            2 -> SCALE_COMFORTABLE
            else -> SCALE_STANDARD
        }

        fun keyFontScaleForLevel(level: Int): Float = when (level.coerceIn(MIN_LEVEL, MAX_LEVEL)) {
            -2 -> KEY_FONT_SCALE_COMPACT
            -1 -> KEY_FONT_SCALE_LOW
            0 -> KEY_FONT_SCALE_STANDARD
            1 -> KEY_FONT_SCALE_HIGH
            2 -> KEY_FONT_SCALE_COMFORTABLE
            else -> KEY_FONT_SCALE_STANDARD
        }

        fun labelForLevel(level: Int): String = when (level.coerceIn(MIN_LEVEL, MAX_LEVEL)) {
            -2 -> "紧凑"
            -1 -> "偏低"
            0 -> "标准"
            1 -> "偏高"
            2 -> "舒适"
            else -> "标准"
        }

        fun topBarHeightDpForCandidateScale(scale: CandidateFontScale): Int = when (scale) {
            CandidateFontScale.STANDARD -> 34
            CandidateFontScale.LARGE -> 38
            CandidateFontScale.EXTRA_LARGE -> 44
        }

        fun candidateChipHeightDpForScale(scale: CandidateFontScale): Int = when (scale) {
            CandidateFontScale.STANDARD -> 29
            CandidateFontScale.LARGE -> 33
            CandidateFontScale.EXTRA_LARGE -> 38
        }

        fun candidateFontSizeSpForScale(scale: CandidateFontScale): Float = when (scale) {
            CandidateFontScale.STANDARD -> 15.5f
            CandidateFontScale.LARGE -> 17.0f
            CandidateFontScale.EXTRA_LARGE -> 19.5f
        }

        fun preeditFontSizeSpForScale(scale: CandidateFontScale): Float = when (scale) {
            CandidateFontScale.STANDARD -> 13.5f
            CandidateFontScale.LARGE -> 14.8f
            CandidateFontScale.EXTRA_LARGE -> 16.0f
        }

        fun preeditCapsuleHeightDpForScale(scale: CandidateFontScale): Int = when (scale) {
            CandidateFontScale.STANDARD -> 24
            CandidateFontScale.LARGE -> 25
            CandidateFontScale.EXTRA_LARGE -> 26
        }

        fun resolveWidthScale(
            screenWidthDp: Int,
            isLandscape: Boolean
        ): Float {
            if (isLandscape || screenWidthDp <= 0) return 1.0f
            if (screenWidthDp >= TABLET_WIDTH_CUTOFF_DP) return TABLET_MAX_WIDTH_SCALE
            return (screenWidthDp.toFloat() / BASELINE_SCREEN_WIDTH_DP.toFloat())
                .coerceIn(MIN_WIDTH_SCALE, MAX_WIDTH_SCALE)
        }

        fun resolveDampedSystemFontScale(
            systemFontScale: Float,
            isLandscape: Boolean
        ): Float {
            if (isLandscape || systemFontScale <= 0f) return 1.0f
            return systemFontScale.coerceIn(0.96f, 1.10f)
        }

        fun fixedChromeDpForCandidateScale(scale: CandidateFontScale): Int = when (scale) {
            CandidateFontScale.STANDARD -> FIXED_CHROME_DP
            CandidateFontScale.LARGE -> FIXED_CHROME_LARGE_DP
            CandidateFontScale.EXTRA_LARGE -> FIXED_CHROME_EXTRA_LARGE_DP
        }

        fun expandedGridFontSizeSpForScale(scale: CandidateFontScale): Float = when (scale) {
            CandidateFontScale.STANDARD -> 15.0f
            CandidateFontScale.LARGE -> 17.5f
            CandidateFontScale.EXTRA_LARGE -> 20.0f
        }

        fun expandedGridRowHeightDpForScale(scale: CandidateFontScale): Int = when (scale) {
            CandidateFontScale.STANDARD -> 38
            CandidateFontScale.LARGE -> 42
            CandidateFontScale.EXTRA_LARGE -> 48
        }

        /**
         * Candidate count threshold (`candidates.size > threshold`) above which the right-side `[▾]`
         * expand grid button appears: lowered from `4` to `2` for `LARGE` / `EXTRA_LARGE` so wide
         * 17.0sp / 19.5sp chips always expose `[▾]` even with 3–4 candidates.
         */
        fun expandButtonCandidateThreshold(scale: CandidateFontScale): Int = when (scale) {
            CandidateFontScale.STANDARD -> 4
            CandidateFontScale.LARGE, CandidateFontScale.EXTRA_LARGE -> 2
        }

        fun shouldShowCandidateExpandButton(
            candidateCount: Int,
            hasMoreCandidates: Boolean,
            scale: CandidateFontScale
        ): Boolean = hasMoreCandidates || candidateCount > expandButtonCandidateThreshold(scale)

        // ---------------------------------------------------------------------------------
        // Legacy LIFT mode (垫高): keys keep standard size; a blank bottom cushion raises keyboard.
        // ---------------------------------------------------------------------------------
        const val MIN_LIFT_LEVEL = 0
        const val MAX_LIFT_LEVEL = 4
        const val DEFAULT_LIFT_LEVEL = 0
        private const val LIFT_STEP_DP = 8

        val ALL_LIFT_LEVELS: List<Int> = listOf(0, 1, 2, 3, 4)

        /** Bottom cushion height for a legacy lift level: 0 / 8 / 16 / 24 / 32 dp. */
        fun liftDpForLevel(liftLevel: Int): Int =
            liftLevel.coerceIn(MIN_LIFT_LEVEL, MAX_LIFT_LEVEL) * LIFT_STEP_DP

        fun labelForLiftLevel(liftLevel: Int): String = "${liftDpForLevel(liftLevel)}dp"

        /**
         * Decoupled v3.9.1+ metrics resolver:
         * Combines key-area vertical scale (`level` `-2..+2` -> `0.88f..1.14f`), portrait bottom cushion
         * (`bottomLiftDp` `0..56`, `0` in landscape), and [candidateFontScale] chrome budget additively.
         */
        fun resolve(
            level: Int = DEFAULT_LEVEL,
            bottomLiftDp: Int = DEFAULT_BOTTOM_LIFT_DP,
            isLandscape: Boolean = false,
            showNumberRow: Boolean = false,
            candidateFontScale: CandidateFontScale = CandidateFontScale.STANDARD,
            screenWidthDp: Int = BASELINE_SCREEN_WIDTH_DP,
            systemFontScale: Float = 1.0f
        ): KeyboardMetrics {
            val clampedLevel = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
            val clampedBottomLiftDp = bottomLiftDp.coerceIn(MIN_BOTTOM_LIFT_DP, MAX_BOTTOM_LIFT_DP)
            val widthScale = resolveWidthScale(screenWidthDp = screenWidthDp, isLandscape = isLandscape)
            val dampedSysFont = resolveDampedSystemFontScale(systemFontScale = systemFontScale, isLandscape = isLandscape)
            val keyAreaHeightScale = if (isLandscape) 1.0f else (1.0f + (widthScale - 1.0f) * WIDTH_HEIGHT_DAMPING)
            val effectiveScale = if (isLandscape) {
                SCALE_COMPACT * LANDSCAPE_FACTOR
            } else {
                scaleForLevel(clampedLevel)
            }
            val effectiveKeyFontScale = if (isLandscape) {
                KEY_FONT_SCALE_LANDSCAPE
            } else {
                keyFontScaleForLevel(clampedLevel) * widthScale
            }
            val liftDp = if (isLandscape) 0 else clampedBottomLiftDp
            val baseKeyAreaDp = if (showNumberRow) BASE_KEY_AREA_5ROW_DP else BASE_KEY_AREA_4ROW_DP
            val keyAreaDp = (baseKeyAreaDp * effectiveScale * keyAreaHeightScale).roundToInt()
            val candCombinedScale = widthScale * dampedSysFont
            val topBarDp = (topBarHeightDpForCandidateScale(candidateFontScale) * candCombinedScale).roundToInt()
            val chromeDp = topBarDp + CHROME_OVERHEAD_DP
            val chipDp = (candidateChipHeightDpForScale(candidateFontScale) * candCombinedScale)
                .roundToInt()
                .coerceAtMost((topBarDp - 4).coerceAtLeast(24))
            val candidateSp = candidateFontSizeSpForScale(candidateFontScale) * candCombinedScale
            val preeditScale = candCombinedScale.coerceAtMost(1.15f)
            val preeditSp = preeditFontSizeSpForScale(candidateFontScale) * preeditScale
            val preeditHeightDp = (preeditCapsuleHeightDpForScale(candidateFontScale) * preeditScale.coerceAtMost(1.08f))
                .roundToInt()
                .coerceIn(22, 26)
            val actionBtnDp = (28f * widthScale).roundToInt().coerceAtMost((topBarDp - 4).coerceAtLeast(24))
            val frameDp = chromeDp + keyAreaDp + liftDp
            val nearestLiftLevel = (clampedBottomLiftDp / LIFT_STEP_DP).coerceIn(MIN_LIFT_LEVEL, MAX_LIFT_LEVEL)
            val resolvedLabel = if (liftDp > 0) {
                "${labelForLevel(clampedLevel)} · +${liftDp}dp"
            } else {
                labelForLevel(clampedLevel)
            }
            return KeyboardMetrics(
                level = clampedLevel,
                isLandscape = isLandscape,
                scale = effectiveScale,
                elementScale = min(effectiveScale, 1.0f),
                topBarHeight = topBarDp.dp,
                dividerHeight = 1.dp,
                keyAreaHeight = keyAreaDp.dp,
                frameHeight = frameDp.dp,
                label = resolvedLabel,
                heightMode = if (clampedLevel == DEFAULT_LEVEL) KeyboardHeightMode.LIFT else KeyboardHeightMode.STRETCH,
                liftLevel = nearestLiftLevel,
                liftHeight = liftDp.dp,
                showNumberRow = showNumberRow,
                keyFontScale = effectiveKeyFontScale,
                candidateFontScale = candidateFontScale,
                candidateChipHeight = chipDp.dp,
                candidateFontSizeSp = candidateSp,
                fixedChromeDp = chromeDp,
                widthScale = widthScale,
                preeditFontSizeSp = preeditSp,
                preeditCapsuleHeight = preeditHeightDp.dp,
                topBarActionButtonSize = actionBtnDp.dp
            )
        }

        fun fromSettings(
            settings: AppSettings,
            isLandscape: Boolean = false,
            screenWidthDp: Int = BASELINE_SCREEN_WIDTH_DP,
            systemFontScale: Float = 1.0f
        ): KeyboardMetrics = resolve(
            level = settings.keyboardHeightLevel,
            bottomLiftDp = settings.resolvedBottomLiftDp(),
            isLandscape = isLandscape,
            showNumberRow = settings.qwertyShowNumberRow,
            candidateFontScale = settings.candidateFontScale,
            screenWidthDp = screenWidthDp,
            systemFontScale = systemFontScale
        )

        fun fromLevel(
            level: Int = DEFAULT_LEVEL,
            isLandscape: Boolean = false,
            heightMode: KeyboardHeightMode = KeyboardHeightMode.LIFT,
            liftLevel: Int = DEFAULT_LIFT_LEVEL,
            showNumberRow: Boolean = false,
            decoupledLiftDp: Int? = null,
            candidateFontScale: CandidateFontScale = CandidateFontScale.STANDARD
        ): KeyboardMetrics {
            if (decoupledLiftDp != null) {
                return resolve(
                    level = level,
                    bottomLiftDp = decoupledLiftDp,
                    isLandscape = isLandscape,
                    showNumberRow = showNumberRow,
                    candidateFontScale = candidateFontScale
                )
            }
            val clamped = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
            val isLift = heightMode == KeyboardHeightMode.LIFT
            val effectiveScale = when {
                isLandscape -> SCALE_COMPACT * LANDSCAPE_FACTOR
                isLift -> SCALE_STANDARD
                else -> scaleForLevel(clamped)
            }
            val effectiveKeyFontScale = when {
                isLandscape -> KEY_FONT_SCALE_LANDSCAPE
                isLift -> KEY_FONT_SCALE_STANDARD
                else -> keyFontScaleForLevel(clamped)
            }
            val clampedLift = liftLevel.coerceIn(MIN_LIFT_LEVEL, MAX_LIFT_LEVEL)
            // Landscape never adds a cushion: vertical space is already scarce.
            val liftDp = if (isLift && !isLandscape) liftDpForLevel(clampedLift) else 0
            val baseKeyAreaDp = if (showNumberRow) BASE_KEY_AREA_5ROW_DP else BASE_KEY_AREA_4ROW_DP
            val keyAreaDp = (baseKeyAreaDp * effectiveScale).roundToInt()
            val chromeDp = fixedChromeDpForCandidateScale(candidateFontScale)
            val topBarDp = topBarHeightDpForCandidateScale(candidateFontScale)
            val chipDp = candidateChipHeightDpForScale(candidateFontScale)
            val candidateSp = candidateFontSizeSpForScale(candidateFontScale)
            val frameDp = chromeDp + keyAreaDp + liftDp
            return KeyboardMetrics(
                level = clamped,
                isLandscape = isLandscape,
                scale = effectiveScale,
                elementScale = min(effectiveScale, 1.0f),
                topBarHeight = topBarDp.dp,
                dividerHeight = 1.dp,
                keyAreaHeight = keyAreaDp.dp,
                frameHeight = frameDp.dp,
                label = if (isLift) "垫高 · ${labelForLiftLevel(clampedLift)}" else labelForLevel(clamped),
                heightMode = heightMode,
                liftLevel = clampedLift,
                liftHeight = liftDp.dp,
                showNumberRow = showNumberRow,
                keyFontScale = effectiveKeyFontScale,
                candidateFontScale = candidateFontScale,
                candidateChipHeight = chipDp.dp,
                candidateFontSizeSp = candidateSp,
                fixedChromeDp = chromeDp
            )
        }
    }
}

@Immutable
data class ImeBottomInsetGeometry(
    val rawDecorBottomInsetPx: Int = 0,
    val reservedBelowInputPx: Int = 0,
    val inWindowReservedPx: Int = 0,
    val effectiveBottomInsetPx: Int = 0,
    val decorHeightPx: Int = 0,
    val inputBottomInWindowPx: Int = 0,
    val decorBottomOnScreenPx: Int = 0,
    val screenBottomPx: Int = 0,
    val inputLaidOut: Boolean = false
) {
    fun toDiagnosticDetail(): String =
        "raw=${rawDecorBottomInsetPx}px reserved=${reservedBelowInputPx}px eff=${effectiveBottomInsetPx}px " +
            "decorH=$decorHeightPx inputBot=$inputBottomInWindowPx decorScrBot=$decorBottomOnScreenPx " +
            "scrBot=$screenBottomPx laidOut=$inputLaidOut"

    fun toDiagnosticStateMap(composeSystemBarsBottomPx: Int? = null): Map<String, String> =
        linkedMapOf<String, String>().apply {
            put("imeInsetRawPx", rawDecorBottomInsetPx.toString())
            put("imeInsetReservedPx", reservedBelowInputPx.toString())
            put("imeInsetEffPx", effectiveBottomInsetPx.toString())
            if (composeSystemBarsBottomPx != null) {
                put("imeInsetComposePx", composeSystemBarsBottomPx.toString())
            }
            put("imeDecorH", decorHeightPx.toString())
            put("imeInputBot", inputBottomInWindowPx.toString())
            put("imeDecorScrBot", decorBottomOnScreenPx.toString())
            put("imeScrBot", screenBottomPx.toString())
            put("imeLaidOut", inputLaidOut.toString())
        }
}

val LocalKeyboardMetrics = staticCompositionLocalOf {
    KeyboardMetrics.fromLevel(KeyboardMetrics.DEFAULT_LEVEL)
}
