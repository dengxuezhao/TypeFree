package com.typefree.ime.ime.candidates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.InputScheme
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.LocalKeyboardMetrics
import com.typefree.ime.ime.chrome.TopBarToolPanelButton
import com.typefree.ime.ime.flypy.FlypyCandidate
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeRose

/**
 * 34.dp Prediction & AI Completion Strip reusing the Top Bar slot:
 * `[28.dp Expand Button ⊞] [Scrollable L1/L2 Prediction Chips...] [✦ AI Completion Chip (<=12 chars)] [×]`
 */
@Composable
fun PredictionCandidateBar(
    isToolPanelOpen: Boolean,
    predictions: List<FlypyCandidate>,
    aiCompletion: String?,
    onToggleToolPanel: () -> Unit,
    onSelectPrediction: (Int) -> Unit,
    onSelectAiCompletion: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.topBarHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 1. Stationary 28.dp Expand Button (identical position to TypeFreeTopBar)
        TopBarToolPanelButton(
            isOpen = isToolPanelOpen,
            onClick = onToggleToolPanel
        )

        // 2. Scrollable L1/L2 Prediction Chips
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            predictions.forEachIndexed { idx, cand ->
                val visualText = if (cand.text.length > 16 || cand.text.contains('\n')) {
                    cand.text.replace('\n', '↵').take(16) + "…"
                } else {
                    cand.text
                }
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.keyCapBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                        .clickable { onSelectPrediction(idx) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = visualText,
                        color = palette.keyTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }

        // 3. Pinned Right-Side L3 AI Completion Chip (TypeFreeEmerald border + `✦` prefix + max 12 chars)
        val cleanAiText = aiCompletion?.trim().orEmpty()
        if (cleanAiText.isNotEmpty()) {
            val singleLineAi = cleanAiText.replace('\n', '↵')
            val truncatedAi = if (singleLineAi.length > 12) {
                singleLineAi.take(12) + "…"
            } else {
                singleLineAi
            }
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.16f else 0.12f))
                    .border(1.dp, TypeFreeEmerald.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                    .clickable { onSelectAiCompletion(cleanAiText) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✦ $truncatedAi",
                    color = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        // 4. Dismiss Predictions (`×`)
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.keyCapSecondaryBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "×",
                color = palette.keyTextMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Lightweight Floating Preedit Capsule rendered in the `28.dp` transparent overhang right above
 * the keyboard's `1.dp` top divider (zero host viewport resize via `contentTopInsets`):
 * - Displays segmented pinyin / Flypy code (`yz`, `wo'men'ming'tian`) in a crisp `22.dp` elevated pill
 *   floating `4.dp` above the keyboard top edge.
 * - Frees the `34.dp` top bar slot inside the keyboard for a spacious, uncramped single-row candidate strip
 *   while keeping Candidate #1 permanently riveted at `X = 12.dp` (`0px` horizontal shift).
 */
@Composable
fun FloatingPreeditCapsule(
    composingCode: String,
    preeditDisplay: String = composingCode,
    inputScheme: InputScheme = InputScheme.FLYPY_TABLE
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val displayPreedit = remember(preeditDisplay, composingCode, inputScheme) {
        ImeLogic.formatPreeditDisplay(
            preedit = preeditDisplay,
            rawCode = composingCode,
            scheme = inputScheme,
            maxVisibleChars = 38
        )
    }
    if (displayPreedit.isBlank()) return

    val pillShape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .height(22.dp)
            .shadow(elevation = 2.dp, shape = pillShape, clip = false)
            .clip(pillShape)
            .background(palette.keyCapBg)
            .border(1.dp, palette.keyBorder, pillShape)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = displayPreedit,
            style = TextStyle(
                color = palette.keyTextPrimary,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (inputScheme.usesRime) FontFamily.Default else FontFamily.Monospace,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        Box(
            modifier = Modifier
                .width(1.5.dp)
                .height(11.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(TypeFreeEmerald)
        )
    }
}

/**
 * Spacious single-row `34.dp` Candidate Strip inside the constant `42.dp` top chrome slot
 * (paired with [FloatingPreeditCapsule] floating above the keyboard top edge):
 * - 1st candidate riveted at `X = 12.dp` (never pushed right by long pinyin),
 * - Comfortable `29.dp` pill height with generous vertical breathing room,
 * - Followed by scrollable 2..N candidates, right-side `[▾]` expand sheet button and `[×]` clear button.
 */
@Composable
fun CandidateStrip(
    composingCode: String,
    preeditDisplay: String = composingCode,
    inputScheme: InputScheme = InputScheme.FLYPY_TABLE,
    candidates: List<FlypyCandidate>,
    hasMoreCandidates: Boolean = false,
    isSheetExpanded: Boolean,
    onSelectCandidate: (Int) -> Unit,
    onToggleSheet: () -> Unit,
    onClearCode: () -> Unit,
    onRequestMoreCandidates: () -> Unit = {}
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val scrollState = rememberScrollState()

    LaunchedEffect(composingCode) {
        scrollState.scrollTo(0)
    }

    LaunchedEffect(scrollState.value, scrollState.maxValue, hasMoreCandidates) {
        if (hasMoreCandidates && scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue - 48) {
            onRequestMoreCandidates()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.topBarHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (candidates.isEmpty()) {
            val emptyHint = when {
                composingCode.all { it == '`' } -> "反查：输双拼查形(如 ji`) 或 输双形查音(如 `bo)"
                inputScheme.usesRime -> "无匹配候选 · 按回车上屏编码或空格清屏"
                else -> "空码 · 第五码或空格自动清屏"
            }
            Text(
                text = emptyHint,
                style = TextStyle(
                    color = if (composingCode.all { it == '`' }) palette.keyTextMuted else TypeFreeRose,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            // 1. Primary 1st candidate riveted at X = 12.dp (0px horizontal shift regardless of pinyin length)
            val firstCand = candidates.first()
            CandidateChipItem(
                index = 0,
                cand = firstCand,
                isFirst = true,
                onClick = { onSelectCandidate(0) }
            )

            // 2. Scrollable 2..N candidates
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                candidates.drop(1).take(39).forEachIndexed { offsetIdx, cand ->
                    val actualIdx = offsetIdx + 1
                    CandidateChipItem(
                        index = actualIdx,
                        cand = cand,
                        isFirst = false,
                        onClick = { onSelectCandidate(actualIdx) }
                    )
                }
            }
        }

        // 3. Expand All Candidates Grid Toggle (`[▾]`)
        if (candidates.size > 4 || hasMoreCandidates) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSheetExpanded) palette.capsuleSelectedBg else palette.keyCapSecondaryBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleSheet),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isSheetExpanded) "▴" else "▾",
                    style = TextStyle(
                        color = palette.keyTextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Bold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    )
                )
            }
        }

        // 4. 1-Tap Clear Code (`[×]`)
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.keyCapSecondaryBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                .clickable(onClick = onClearCode),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "×",
                style = TextStyle(
                    color = palette.keyTextMuted,
                    fontSize = 14.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Bold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                )
            )
        }
    }
}

@Composable
private fun CandidateChipItem(
    index: Int,
    cand: FlypyCandidate,
    isFirst: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        modifier = Modifier
            .height(29.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFirst) palette.capsuleSelectedBg else palette.keyCapBg)
            .border(
                width = 1.dp,
                color = if (isFirst) palette.keyTextPrimary.copy(alpha = 0.28f) else palette.keyBorder,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.5.dp)
    ) {
        if (index < 3) {
            Text(
                text = "${index + 1}",
                style = TextStyle(
                    color = palette.keyTextMuted,
                    fontSize = 10.5.sp,
                    lineHeight = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                )
            )
        }
        val visualCandText = if (cand.text.length > 16 || cand.text.contains('\n')) {
            cand.text.replace('\n', '↵').take(16) + "…"
        } else {
            cand.text
        }
        Text(
            text = visualCandText,
            style = TextStyle(
                color = palette.keyTextPrimary,
                fontSize = 14.5.sp,
                lineHeight = 17.sp,
                fontWeight = if (isFirst) FontWeight.Bold else FontWeight.Medium,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            maxLines = 1
        )
        if (cand.codeHint.isNotEmpty()) {
            Text(
                text = "〔${cand.codeHint}〕",
                style = TextStyle(
                    color = palette.keyTextMuted,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1
            )
        }
    }
}

@Composable
fun FlypyComposingCandidateBar(
    composingCode: String,
    candidates: List<FlypyCandidate>,
    isSheetExpanded: Boolean,
    onSelectCandidate: (Int) -> Unit,
    onToggleSheet: () -> Unit,
    onClearCode: () -> Unit
) {
    CandidateStrip(
        composingCode = composingCode,
        preeditDisplay = composingCode,
        inputScheme = InputScheme.FLYPY_TABLE,
        candidates = candidates,
        isSheetExpanded = isSheetExpanded,
        onSelectCandidate = onSelectCandidate,
        onToggleSheet = onToggleSheet,
        onClearCode = onClearCode
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CandidateGridOverlay(
    candidates: List<FlypyCandidate>,
    hasMoreCandidates: Boolean = false,
    onSelectCandidate: (Int) -> Unit,
    onBackspace: () -> Unit,
    onCollapse: () -> Unit,
    onRequestMoreCandidates: () -> Unit = {}
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val vScroll = rememberScrollState()

    LaunchedEffect(hasMoreCandidates, vScroll.value, vScroll.maxValue) {
        if (hasMoreCandidates && (vScroll.maxValue == 0 || vScroll.value >= vScroll.maxValue - 80)) {
            onRequestMoreCandidates()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.keyboardCanvas)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(vScroll)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                candidates.forEachIndexed { idx, cand ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (idx == 0) palette.capsuleSelectedBg else palette.keyCapBg)
                            .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                            .clickable { onSelectCandidate(idx) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        val gridCandText = if (cand.text.length > 22 || cand.text.contains('\n')) {
                            cand.text.replace('\n', '↵').take(22) + "…"
                        } else {
                            cand.text
                        }
                        Text(
                            text = gridCandText,
                            color = palette.keyTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (idx == 0) FontWeight.Bold else FontWeight.Medium
                        )
                        if (cand.codeHint.isNotEmpty()) {
                            Text(
                                text = "〔${cand.codeHint}〕",
                                color = palette.keyTextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.keyCapSecondaryBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                    .clickable(onClick = onCollapse),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "▴ 返回键盘",
                    color = palette.keyTextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.keyCapSecondaryBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                    .clickable(onClick = onBackspace),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = TypeFreeIcons.Backspace,
                    contentDescription = "退格",
                    tint = palette.keyTextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun FlypyExpandedCandidateGridOverlay(
    candidates: List<FlypyCandidate>,
    onSelectCandidate: (Int) -> Unit,
    onBackspace: () -> Unit,
    onCollapse: () -> Unit
) {
    CandidateGridOverlay(
        candidates = candidates,
        onSelectCandidate = onSelectCandidate,
        onBackspace = onBackspace,
        onCollapse = onCollapse
    )
}
