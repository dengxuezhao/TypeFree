package com.typefree.ime.ime.candidates

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.CandidateFontScale
import com.typefree.ime.data.InputScheme
import com.typefree.ime.ime.HapticTier
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.KeySoundType
import com.typefree.ime.ime.KeyboardMetrics
import com.typefree.ime.ime.LocalKeyFeedbackTrigger
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
    onDismiss: () -> Unit,
    passiveMathResult: String? = null,
    onSelectPassiveMath: ((String) -> Unit)? = null,
    phraseSuggestionLabel: String? = null,
    onAcceptPhraseSuggestion: (() -> Unit)? = null,
    onDismissPhraseSuggestion: (() -> Unit)? = null,
    englishSuggestions: List<String> = emptyList(),
    onSelectEnglishSuggestion: ((String) -> Unit)? = null,
    emojiSuggestions: List<String> = emptyList(),
    onSelectEmojiSuggestion: ((String) -> Unit)? = null
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val scrollState = rememberScrollState()
    val emeraldAccentText = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)

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

        // 2. Scrollable Passive Math / L2 Phrase / EN Suggestions / L1-L2 Prediction Chips
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            val cleanMath = passiveMathResult?.trim().orEmpty()
            if (cleanMath.isNotEmpty()) {
                val mathDisplay = if (cleanMath.startsWith("=")) cleanMath else "= $cleanMath"
                Box(
                    modifier = Modifier
                        .height(metrics.candidateChipHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.16f else 0.12f))
                        .border(1.dp, TypeFreeEmerald.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                        .clickable { onSelectPassiveMath?.invoke(cleanMath) }
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mathDisplay,
                        color = emeraldAccentText,
                        fontSize = (metrics.candidateFontSizeSp - 1.0f).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }

            val cleanPhraseLabel = phraseSuggestionLabel?.trim().orEmpty()
            if (cleanPhraseLabel.isNotEmpty()) {
                val displayPhrase = if (
                    cleanPhraseLabel.startsWith("+") ||
                    cleanPhraseLabel.startsWith("录入")
                ) {
                    cleanPhraseLabel
                } else {
                    "+ 录入: $cleanPhraseLabel"
                }
                Row(
                    modifier = Modifier
                        .height(metrics.candidateChipHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.capsuleSelectedBg)
                        .border(1.dp, TypeFreeEmerald.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                        .padding(start = 8.dp, end = if (onDismissPhraseSuggestion != null) 4.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = displayPhrase,
                        color = emeraldAccentText,
                        fontSize = (metrics.candidateFontSizeSp - 2.0f).sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.clickable { onAcceptPhraseSuggestion?.invoke() }
                    )
                    if (onDismissPhraseSuggestion != null) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(palette.keyCapSecondaryBg.copy(alpha = 0.72f))
                                .clickable(onClick = onDismissPhraseSuggestion),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "×",
                                color = palette.keyTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            englishSuggestions.forEach { word ->
                if (word.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .height(metrics.candidateChipHeight)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.keyCapBg)
                            .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                            .clickable { onSelectEnglishSuggestion?.invoke(word) }
                            .padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = word,
                            color = palette.keyTextPrimary,
                            fontSize = metrics.candidateFontSizeSp.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            // Keep top-2 next-word text predictions in their primary left slots, then place
            // post-commit Emoji pills right after them (or directly at front when text predictions are empty).
            val leadPredCount = minOf(2, predictions.size)
            for (idx in 0 until leadPredCount) {
                val cand = predictions[idx]
                val visualText = if (cand.text.length > 16 || cand.text.contains('\n')) {
                    cand.text.replace('\n', '↵').take(16) + "…"
                } else {
                    cand.text
                }
                Box(
                    modifier = Modifier
                        .height(metrics.candidateChipHeight)
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
                        fontSize = metrics.candidateFontSizeSp.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }

            emojiSuggestions.forEach { glyph ->
                if (glyph.isNotBlank()) {
                    EmojiSuggestionChip(
                        glyph = glyph,
                        onClick = { onSelectEmojiSuggestion?.invoke(glyph) }
                    )
                }
            }

            for (idx in leadPredCount until predictions.size) {
                val cand = predictions[idx]
                val visualText = if (cand.text.length > 16 || cand.text.contains('\n')) {
                    cand.text.replace('\n', '↵').take(16) + "…"
                } else {
                    cand.text
                }
                Box(
                    modifier = Modifier
                        .height(metrics.candidateChipHeight)
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
                        fontSize = metrics.candidateFontSizeSp.sp,
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
                    .height(metrics.candidateChipHeight)
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
                    fontSize = (metrics.candidateFontSizeSp - 1.5f).sp,
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
    val metrics = LocalKeyboardMetrics.current
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
            .height(metrics.preeditCapsuleHeight)
            .shadow(elevation = 2.dp, shape = pillShape, clip = false)
            .clip(pillShape)
            .background(palette.keyCapBg)
            .border(1.dp, palette.keyBorder, pillShape)
            .padding(horizontal = 9.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.5.dp)
    ) {
        Text(
            text = displayPreedit,
            style = TextStyle(
                color = palette.keyTextPrimary,
                fontSize = metrics.preeditFontSizeSp.sp,
                lineHeight = (metrics.preeditFontSizeSp + 2f).sp,
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
                .height((12f * metrics.widthScale).dp)
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
    onRequestMoreCandidates: () -> Unit = {},
    codetableEnhancerActive: Boolean = false,
    pinnedWordsForCurrentCode: Set<String> = emptySet(),
    onPinCandidate: (String) -> Unit = {},
    onUnpinCandidate: (String) -> Unit = {},
    onBlockCandidate: (String) -> Unit = {},
    isOrqDateActive: Boolean = false,
    orqDateOffsetDays: Int = 0,
    onStepOrqDate: ((Int) -> Unit)? = null,
    emojiSuggestions: List<String> = emptyList(),
    onSelectEmojiSuggestion: ((String) -> Unit)? = null
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
                codetableEnhancerActive = codetableEnhancerActive,
                isPinned = firstCand.text in pinnedWordsForCurrentCode,
                onClick = { onSelectCandidate(0) },
                onPin = { onPinCandidate(firstCand.text) },
                onUnpin = { onUnpinCandidate(firstCand.text) },
                onBlock = { onBlockCandidate(firstCand.text) }
            )

            // 2. Scrollable 2..N candidates (with Pinyin/Double-Pinyin Emoji suggestion pills right after Candidate #2
            //    so Candidate #1 (Space) and Candidate #2 (`;`) stay at exact muscle-memory positions)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val secondCand = candidates.getOrNull(1)
                if (secondCand != null) {
                    CandidateChipItem(
                        index = 1,
                        cand = secondCand,
                        isFirst = false,
                        codetableEnhancerActive = codetableEnhancerActive,
                        isPinned = secondCand.text in pinnedWordsForCurrentCode,
                        onClick = { onSelectCandidate(1) },
                        onPin = { onPinCandidate(secondCand.text) },
                        onUnpin = { onUnpinCandidate(secondCand.text) },
                        onBlock = { onBlockCandidate(secondCand.text) }
                    )
                }
                emojiSuggestions.forEach { glyph ->
                    if (glyph.isNotBlank()) {
                        EmojiSuggestionChip(
                            glyph = glyph,
                            onClick = { onSelectEmojiSuggestion?.invoke(glyph) }
                        )
                    }
                }
                candidates.drop(2).take(38).forEachIndexed { offsetIdx, cand ->
                    val actualIdx = offsetIdx + 2
                    CandidateChipItem(
                        index = actualIdx,
                        cand = cand,
                        isFirst = false,
                        codetableEnhancerActive = codetableEnhancerActive,
                        isPinned = cand.text in pinnedWordsForCurrentCode,
                        onClick = { onSelectCandidate(actualIdx) },
                        onPin = { onPinCandidate(cand.text) },
                        onUnpin = { onUnpinCandidate(cand.text) },
                        onBlock = { onBlockCandidate(cand.text) }
                    )
                }
            }
        }

        // 2.5 Optional `orq` Date Offset Stepper Buttons (`[-1天]` `[+1天]`)
        if (isOrqDateActive) {
            val stepActiveBorder = if (orqDateOffsetDays != 0) {
                TypeFreeEmerald.copy(alpha = 0.72f)
            } else {
                palette.keyBorder
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(-1 to "-1天", 1 to "+1天").forEach { (delta, label) ->
                    Box(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.keyCapSecondaryBg)
                            .border(1.dp, stepActiveBorder, RoundedCornerShape(8.dp))
                            .clickable { onStepOrqDate?.invoke(delta) }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = TextStyle(
                                color = palette.keyTextPrimary,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // 3. Expand All Candidates Grid Toggle (`[▾]` crisp vector chevron)
        val stripFeedback = LocalKeyFeedbackTrigger.current
        val stripView = LocalView.current
        if (
            KeyboardMetrics.shouldShowCandidateExpandButton(
                candidateCount = candidates.size,
                hasMoreCandidates = hasMoreCandidates,
                scale = metrics.candidateFontScale
            )
        ) {
            Box(
                modifier = Modifier
                    .size(metrics.topBarActionButtonSize)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSheetExpanded) palette.capsuleSelectedBg else palette.keyCapSecondaryBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                    .clickable {
                        stripFeedback.onTouchFeedback(HapticTier.CANDIDATE_TAP, stripView, KeySoundType.STANDARD, false)
                        onToggleSheet()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = TypeFreeIcons.ChevronDown,
                    contentDescription = if (isSheetExpanded) "收起候选" else "展开候选",
                    tint = palette.keyTextPrimary,
                    modifier = Modifier
                        .size((16f * metrics.widthScale).dp)
                        .graphicsLayer {
                            rotationZ = if (isSheetExpanded) 180f else 0f
                        }
                )
            }
        }

        // 4. 1-Tap Clear Code (`[×]` crisp vector close)
        Box(
            modifier = Modifier
                .size(metrics.topBarActionButtonSize)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.keyCapSecondaryBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                .clickable(onClick = onClearCode),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = TypeFreeIcons.Close,
                contentDescription = "清空编码",
                tint = palette.keyTextMuted,
                modifier = Modifier.size((13.5f * metrics.widthScale).dp)
            )
        }
    }
}

@Composable
private fun EmojiSuggestionChip(
    glyph: String,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val hostView = LocalView.current
    val chipShape = RoundedCornerShape(8.dp)

    Box(
        modifier = Modifier
            .height(metrics.candidateChipHeight)
            .widthIn(min = 36.dp)
            .clip(chipShape)
            .background(TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.13f else 0.08f))
            .border(
                width = 1.dp,
                color = TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.42f else 0.34f),
                shape = chipShape
            )
            .clickable {
                keyFeedback.onTouchFeedback(HapticTier.CANDIDATE_TAP, hostView, KeySoundType.STANDARD, true)
                onClick()
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            style = TextStyle(
                fontSize = (metrics.candidateFontSizeSp + 2.5f).sp,
                lineHeight = (metrics.candidateFontSizeSp + 4.5f).sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CandidateChipItem(
    index: Int,
    cand: FlypyCandidate,
    isFirst: Boolean,
    codetableEnhancerActive: Boolean = false,
    isPinned: Boolean = false,
    onClick: () -> Unit,
    onPin: () -> Unit = {},
    onUnpin: () -> Unit = {},
    onBlock: () -> Unit = {}
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val hostView = LocalView.current
    var menuExpanded by remember { mutableStateOf(false) }
    val longPressEnabled = codetableEnhancerActive && cand.text.isNotBlank() && !cand.text.startsWith("[")
    val maxCandChars = when (metrics.candidateFontScale) {
        CandidateFontScale.STANDARD -> 16
        CandidateFontScale.LARGE -> 12
        CandidateFontScale.EXTRA_LARGE -> 10
    }
    val indexFontSp = ((10.8f + metrics.candidateFontScale.level * 0.8f) * metrics.widthScale).sp
    val hintFontSp = ((10.2f + metrics.candidateFontScale.level * 0.8f) * metrics.widthScale).sp

    Box {
        Row(
            modifier = Modifier
                .height(metrics.candidateChipHeight)
                .then(if (isFirst) Modifier.widthIn(max = (210f * metrics.widthScale).dp) else Modifier)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isFirst) palette.capsuleSelectedBg else palette.keyCapBg)
                .border(
                    width = 1.dp,
                    color = if (isFirst) palette.keyTextPrimary.copy(alpha = 0.28f) else palette.keyBorder,
                    shape = RoundedCornerShape(8.dp)
                )
                .combinedClickable(
                    onClick = {
                        keyFeedback.onTouchFeedback(HapticTier.CANDIDATE_TAP, hostView, KeySoundType.STANDARD, true)
                        onClick()
                    },
                    onLongClick = if (longPressEnabled) {
                        {
                            keyFeedback.onTouchFeedback(HapticTier.GESTURE_CONFIRM, hostView, KeySoundType.NONE, false)
                            menuExpanded = true
                        }
                    } else {
                        null
                    }
                )
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.5.dp)
        ) {
            if (index < 3) {
                Text(
                    text = "${index + 1}",
                    style = TextStyle(
                        color = palette.keyTextMuted,
                        fontSize = indexFontSp,
                        lineHeight = (indexFontSp.value + 2f).sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    )
                )
            }
            val rawDisplay = cand.displayText.ifEmpty { cand.text }
            val visualCandText = if (rawDisplay.length > maxCandChars || rawDisplay.contains('\n')) {
                rawDisplay.replace('\n', '↵').take(maxCandChars) + "…"
            } else {
                rawDisplay
            }
            Text(
                text = visualCandText,
                style = TextStyle(
                    color = palette.keyTextPrimary,
                    fontSize = metrics.candidateFontSizeSp.sp,
                    lineHeight = (metrics.candidateFontSizeSp + 2.5f).sp,
                    fontWeight = if (isFirst) FontWeight.Bold else FontWeight.Medium,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (cand.codeHint.isNotEmpty()) {
                Text(
                    text = "〔${cand.codeHint}〕",
                    style = TextStyle(
                        color = palette.keyTextMuted,
                        fontSize = hintFontSp,
                        lineHeight = (hintFontSp.value + 2f).sp,
                        fontFamily = FontFamily.Monospace,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    maxLines = 1
                )
            }
        }

        CandidateActionDropdownMenu(
            expanded = menuExpanded,
            isPinned = isPinned,
            onDismiss = { menuExpanded = false },
            onPin = onPin,
            onUnpin = onUnpin,
            onBlock = onBlock
        )
    }
}

@Composable
private fun CandidateActionDropdownMenu(
    expanded: Boolean,
    isPinned: Boolean,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onBlock: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        if (isPinned) {
            DropdownMenuItem(
                text = { Text("取消置顶", fontSize = 13.sp) },
                onClick = {
                    onDismiss()
                    onUnpin()
                }
            )
        } else {
            DropdownMenuItem(
                text = { Text("置顶首选", fontSize = 13.sp) },
                onClick = {
                    onDismiss()
                    onPin()
                }
            )
        }
        DropdownMenuItem(
            text = { Text("屏蔽该词", fontSize = 13.sp, color = TypeFreeRose) },
            onClick = {
                onDismiss()
                onBlock()
            }
        )
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CandidateGridOverlay(
    candidates: List<FlypyCandidate>,
    hasMoreCandidates: Boolean = false,
    onSelectCandidate: (Int) -> Unit,
    onBackspace: () -> Unit,
    onCollapse: () -> Unit,
    onRequestMoreCandidates: () -> Unit = {},
    codetableEnhancerActive: Boolean = false,
    pinnedWordsForCurrentCode: Set<String> = emptySet(),
    onPinCandidate: (String) -> Unit = {},
    onUnpinCandidate: (String) -> Unit = {},
    onBlockCandidate: (String) -> Unit = {}
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val vScroll = rememberScrollState()
    val gridFontSize = KeyboardMetrics.expandedGridFontSizeSpForScale(metrics.candidateFontScale).sp
    val gridRowHeight = KeyboardMetrics.expandedGridRowHeightDpForScale(metrics.candidateFontScale).dp
    val gridHintFontSize = (11f + metrics.candidateFontScale.level * 1f).sp

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
                    var gridMenuExpanded by remember(cand.text, idx) { mutableStateOf(false) }
                    val longPressEnabled = codetableEnhancerActive && cand.text.isNotBlank() && !cand.text.startsWith("[")
                    Box {
                        Row(
                            modifier = Modifier
                                .height(gridRowHeight)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (idx == 0) palette.capsuleSelectedBg else palette.keyCapBg)
                                .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = { onSelectCandidate(idx) },
                                    onLongClick = if (longPressEnabled) {
                                        { gridMenuExpanded = true }
                                    } else {
                                        null
                                    }
                                )
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            val rawGridDisplay = cand.displayText.ifEmpty { cand.text }
                            val gridCandText = if (rawGridDisplay.length > 22 || rawGridDisplay.contains('\n')) {
                                rawGridDisplay.replace('\n', '↵').take(22) + "…"
                            } else {
                                rawGridDisplay
                            }
                            Text(
                                text = gridCandText,
                                color = palette.keyTextPrimary,
                                fontSize = gridFontSize,
                                fontWeight = if (idx == 0) FontWeight.Bold else FontWeight.Medium
                            )
                            if (cand.codeHint.isNotEmpty()) {
                                Text(
                                    text = "〔${cand.codeHint}〕",
                                    color = palette.keyTextMuted,
                                    fontSize = gridHintFontSize,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        CandidateActionDropdownMenu(
                            expanded = gridMenuExpanded,
                            isPinned = cand.text in pinnedWordsForCurrentCode,
                            onDismiss = { gridMenuExpanded = false },
                            onPin = { onPinCandidate(cand.text) },
                            onUnpin = { onUnpinCandidate(cand.text) },
                            onBlock = { onBlockCandidate(cand.text) }
                        )
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

/**
 * Top-bar T9 Quick Punctuation Strip opened when tapping Key `1 (，@)` in idle state (`Issue #12`):
 * - Displays `，`, `。`, `@`, `？`, `！`, `、`, `：`, `；` at [KeyboardMetrics.candidateChipHeight].
 * - Highlights [activeSymbol] during `1200ms` multi-tap cycling (`， -> 。 -> @ -> ？ -> ！`).
 * - Tapping any symbol chip replaces the just-typed cycle symbol in-place (e.g. `，` -> `@`) and closes.
 */
@Composable
fun T9QuickPunctuationBar(
    isToolPanelOpen: Boolean,
    activeSymbol: String?,
    onToggleToolPanel: () -> Unit,
    onSelectSymbol: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val scrollState = rememberScrollState()
    val emeraldText = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.topBarHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        TopBarToolPanelButton(
            isOpen = isToolPanelOpen,
            onClick = onToggleToolPanel
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            ImeLogic.t9QuickPunctuationItems.forEach { sym ->
                val isSelected = sym == activeSymbol
                Box(
                    modifier = Modifier
                        .height(metrics.candidateChipHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) {
                                TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.20f else 0.14f)
                            } else {
                                palette.keyCapBg
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) TypeFreeEmerald.copy(alpha = 0.75f) else palette.keyBorder,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectSymbol(sym) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = sym,
                        style = TextStyle(
                            color = if (isSelected) emeraldText else palette.keyTextPrimary,
                            fontSize = metrics.candidateFontSizeSp.sp,
                            lineHeight = (metrics.candidateFontSizeSp + 2.5f).sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        maxLines = 1
                    )
                }
            }
        }

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
