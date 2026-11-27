package com.typefree.ime.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import android.view.HapticFeedbackConstants
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.typefree.ime.data.ExpressionTab
import com.typefree.ime.data.SpaceLongPressAction
import com.typefree.ime.i18n.tr
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeEmerald
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class T9KeyboardLayer {
    MAIN,
    NUMBER,
    SYMBOL
}

private data class T9KeySpec(
    val digit: String,
    val letters: String,
    val isDelimiterKey: Boolean = false
)

private data class T9PopupPreview(
    val token: Long,
    val label: String,
    val centerXInRootPx: Float,
    val keyTopInRootPx: Float
)

private val T9_GRID_ROWS: List<List<T9KeySpec>> = listOf(
    listOf(
        T9KeySpec(digit = "1", letters = "分词", isDelimiterKey = true),
        T9KeySpec(digit = "2", letters = "ABC"),
        T9KeySpec(digit = "3", letters = "DEF")
    ),
    listOf(
        T9KeySpec(digit = "4", letters = "GHI"),
        T9KeySpec(digit = "5", letters = "JKL"),
        T9KeySpec(digit = "6", letters = "MNO")
    ),
    listOf(
        T9KeySpec(digit = "7", letters = "PQRS"),
        T9KeySpec(digit = "8", letters = "TUV"),
        T9KeySpec(digit = "9", letters = "WXYZ")
    )
)

internal val T9_IDLE_PUNCTUATIONS: List<Pair<String, String?>> = listOf(
    "，" to null,
    "。" to null,
    "@" to null,
    "？" to null,
    "！" to null,
    "、" to null,
    "：" to null,
    "；" to null,
    "……" to null,
    "“”" to "“|”",
    "（）" to "（|）",
    "—" to null
)

private val T9_NUMBER_SIDE_SYMBOLS: List<String> = listOf(
    "+", "-", "*", "/", "=", "%", ":", "(", ")", "#"
)

private val T9_SYMBOL_PAGE_1: List<List<String>> = listOf(
    listOf("，", "。", "？", "！", "、", "：", "；"),
    listOf("“", "”", "（", "）", "《", "》", "【"),
    listOf("】", "……", "——", "~", "@", "#", "¥")
)

private val T9_SYMBOL_PAGE_2: List<List<String>> = listOf(
    listOf(",", ".", "?", "!", "/", ":", ";"),
    listOf("\"", "'", "(", ")", "[", "]", "{"),
    listOf("}", "+", "-", "*", "=", "%", "$")
)

private val T9_CELL_PAD = 1.8.dp
private val T9_KEY_CORNER = 7.5.dp
private val T9_KEY_RIM = 1.25.dp

/**
 * TypeFree v3.8 Built-in 9-Key Chinese Pinyin (T9) Keyboard + 3x4 Telephone Number Pad + Symbol Pad.
 *
 * Fits inside the exact [LocalKeyboardMetrics] height frame (`0px` WindowInsets shift):
 * - Left rail (Row 1–3): scrollable quick punctuation when idle; live Pinyin syllable filter when composing.
 * - Center 3×3 (Row 1–3): `1 分词` + `2 ABC`..`9 WXYZ` (tap for T9 pinyin, swipe-up or long-press for digit `1..9`).
 * - Right rail: Row 1 Backspace (repeat + swipe-up undo), Row 2 Reset (`重输`) when composing or Voice (`语音`) when idle,
 *   Row 3–4 double-height Send / Enter key.
 * - Bottom row (Left + Center): `符号`, `123`, `空格` (with horizontal cursor slide), `中/英` (switches to 26-key English).
 */
@Composable
fun BuiltInT9Keyboard(
    enterActionInfo: EnterActionInfo,
    isComposing: Boolean,
    composingCode: String,
    t9Syllables: List<String>,
    onCommitChar: (String) -> Unit,
    onCommitSecondarySymbol: (String) -> Unit,
    onInsertPairedSymbol: (String, String) -> Unit,
    onSelectT9Syllable: (String) -> Unit,
    onMoveCursorByStep: (Int) -> Unit,
    onInsertSpace: () -> Unit,
    onToggleLanguageMode: () -> Unit,
    onClearComposing: () -> Unit,
    onFlushComposingBeforeLayerSwitch: () -> Unit,
    onBackspace: () -> Unit,
    onSwipeUpBackspaceUndo: () -> Unit,
    onEnterOrNewline: () -> Unit,
    onSwitchToVoice: () -> Unit,
    onTapKey1IdlePunctuation: () -> Unit = { onCommitSecondarySymbol("，") },
    spaceLongPressAction: SpaceLongPressAction = SpaceLongPressAction.VOICE_HOLD,
    isInPlaceVoiceActive: Boolean = false,
    onHoldStartInPlaceVoice: () -> Unit = {},
    onHoldReleaseInPlaceVoice: () -> Unit = {},
    onOpenExpressionPanel: ((ExpressionTab) -> Unit)? = null,
    inputSessionToken: Int = 0,
    showKeyPopupPreview: Boolean = true,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    var activeLayer by remember { mutableStateOf(T9KeyboardLayer.MAIN) }
    var isMoreSymbols by remember { mutableStateOf(false) }

    var rootOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    var rootSizePx by remember { mutableStateOf(IntSize.Zero) }
    var previewSeq by remember { mutableLongStateOf(0L) }
    var activePopup by remember { mutableStateOf<T9PopupPreview?>(null) }

    LaunchedEffect(inputSessionToken) {
        activeLayer = T9KeyboardLayer.MAIN
        isMoreSymbols = false
        activePopup = null
    }

    val showPreview: (String, Float, Float) -> Long = { label, cx, top ->
        val token = ++previewSeq
        if (showKeyPopupPreview && label.isNotBlank()) {
            activePopup = T9PopupPreview(token, label, cx, top)
        }
        token
    }
    val hidePreview: (Long) -> Unit = { token ->
        if (activePopup?.token == token) {
            activePopup = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                rootOriginInWindow = coords.positionInWindow()
                rootSizePx = coords.size
            }
    ) {
        when (activeLayer) {
            T9KeyboardLayer.MAIN -> {
                T9MainPad(
                    enterActionInfo = enterActionInfo,
                    isComposing = isComposing,
                    composingCode = composingCode,
                    t9Syllables = t9Syllables,
                    rootOriginInWindow = rootOriginInWindow,
                    onShowPreview = showPreview,
                    onHidePreview = hidePreview,
                    onCommitChar = onCommitChar,
                    onCommitSecondarySymbol = onCommitSecondarySymbol,
                    onInsertPairedSymbol = onInsertPairedSymbol,
                    onSelectT9Syllable = onSelectT9Syllable,
                    onMoveCursorByStep = onMoveCursorByStep,
                    onInsertSpace = onInsertSpace,
                    onToggleLanguageMode = onToggleLanguageMode,
                    onClearComposing = onClearComposing,
                    onOpenNumberLayer = {
                        if (isComposing) onFlushComposingBeforeLayerSwitch()
                        activeLayer = T9KeyboardLayer.NUMBER
                    },
                    onOpenSymbolLayer = {
                        if (isComposing) onFlushComposingBeforeLayerSwitch()
                        if (onOpenExpressionPanel != null) {
                            onOpenExpressionPanel(ExpressionTab.SYMBOL)
                        } else {
                            isMoreSymbols = false
                            activeLayer = T9KeyboardLayer.SYMBOL
                        }
                    },
                    onOpenEmojiPanel = {
                        if (isComposing) onFlushComposingBeforeLayerSwitch()
                        if (onOpenExpressionPanel != null) {
                            onOpenExpressionPanel(ExpressionTab.EMOJI)
                        } else {
                            isMoreSymbols = false
                            activeLayer = T9KeyboardLayer.SYMBOL
                        }
                    },
                    onBackspace = onBackspace,
                    onSwipeUpBackspaceUndo = onSwipeUpBackspaceUndo,
                    onEnterOrNewline = onEnterOrNewline,
                    onSwitchToVoice = onSwitchToVoice,
                    onTapKey1IdlePunctuation = onTapKey1IdlePunctuation,
                    spaceLongPressAction = spaceLongPressAction,
                    isInPlaceVoiceActive = isInPlaceVoiceActive,
                    onHoldStartInPlaceVoice = onHoldStartInPlaceVoice,
                    onHoldReleaseInPlaceVoice = onHoldReleaseInPlaceVoice
                )
            }

            T9KeyboardLayer.NUMBER -> {
                T9NumberPad(
                    enterActionInfo = enterActionInfo,
                    onCommitDigitOrSymbol = onCommitSecondarySymbol,
                    onInsertSpace = onInsertSpace,
                    onBackspace = onBackspace,
                    onSwipeUpBackspaceUndo = onSwipeUpBackspaceUndo,
                    onEnterOrNewline = onEnterOrNewline,
                    onReturnToMain = { activeLayer = T9KeyboardLayer.MAIN }
                )
            }

            T9KeyboardLayer.SYMBOL -> {
                T9SymbolPad(
                    enterActionInfo = enterActionInfo,
                    isMoreSymbols = isMoreSymbols,
                    onToggleMoreSymbols = { isMoreSymbols = !isMoreSymbols },
                    onCommitSymbol = { sym ->
                        onCommitSecondarySymbol(sym)
                    },
                    onInsertSpace = onInsertSpace,
                    onBackspace = onBackspace,
                    onEnterOrNewline = onEnterOrNewline,
                    onReturnToMain = { activeLayer = T9KeyboardLayer.MAIN }
                )
            }
        }

        val popup = activePopup
        if (popup != null && rootSizePx.width > 0) {
            val density = LocalDensity.current
            val popupW = with(density) { 46.dp.toPx() }
            val popupH = with(density) { 48.dp.toPx() }
            val liftPx = with(density) { 10.dp.toPx() }
            val xPx = (popup.centerXInRootPx - popupW / 2f)
                .coerceIn(4f, (rootSizePx.width - popupW - 4f).coerceAtLeast(4f))
            val yPx = (popup.keyTopInRootPx - popupH - liftPx).coerceAtLeast(2f)

            Box(
                modifier = Modifier
                    .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                    .size(width = 46.dp, height = 48.dp)
                    .zIndex(20f)
                    .shadow(6.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.keyPopupBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = popup.label,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.keyTextPrimary,
                    maxLines = 1,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
    }
}

@Composable
private fun T9MainPad(
    enterActionInfo: EnterActionInfo,
    isComposing: Boolean,
    composingCode: String,
    t9Syllables: List<String>,
    rootOriginInWindow: Offset,
    onShowPreview: (String, Float, Float) -> Long,
    onHidePreview: (Long) -> Unit,
    onCommitChar: (String) -> Unit,
    onCommitSecondarySymbol: (String) -> Unit,
    onInsertPairedSymbol: (String, String) -> Unit,
    onSelectT9Syllable: (String) -> Unit,
    onMoveCursorByStep: (Int) -> Unit,
    onInsertSpace: () -> Unit,
    onToggleLanguageMode: () -> Unit,
    onClearComposing: () -> Unit,
    onOpenNumberLayer: () -> Unit,
    onOpenSymbolLayer: () -> Unit,
    onOpenEmojiPanel: () -> Unit,
    onBackspace: () -> Unit,
    onSwipeUpBackspaceUndo: () -> Unit,
    onEnterOrNewline: () -> Unit,
    onSwitchToVoice: () -> Unit,
    onTapKey1IdlePunctuation: () -> Unit,
    spaceLongPressAction: SpaceLongPressAction,
    isInPlaceVoiceActive: Boolean,
    onHoldStartInPlaceVoice: () -> Unit,
    onHoldReleaseInPlaceVoice: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val railItemHeight = (38f * metrics.widthScale).dp
    val railFontSize = (16.5f * metrics.widthScale).sp

    Row(modifier = Modifier.fillMaxSize()) {
        // Left Column: Row 1-3 Scrollable Rail (Syllables when composing, Punctuation when idle) + Row 4 Symbol key
        Column(
            modifier = Modifier
                .weight(0.92f)
                .fillMaxHeight()
        ) {
            Box(
                modifier = Modifier
                    .weight(3f)
                    .fillMaxWidth()
                    .padding(T9_CELL_PAD)
                    .clip(RoundedCornerShape(T9_KEY_CORNER))
                    .background(palette.keyCapSecondaryBg)
                    .border(0.8.dp, palette.keyBorder, RoundedCornerShape(T9_KEY_CORNER))
            ) {
                if (isComposing && t9Syllables.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        itemsIndexed(
                            items = t9Syllables,
                            key = { idx, syl -> "syl_${idx}_$syl" }
                        ) { index, syl ->
                            val isPrimary = index == 0
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(railItemHeight)
                                    .background(
                                        if (isPrimary) palette.primaryMicSolid.copy(alpha = 0.12f) else Color.Transparent
                                    )
                                    .clickable { onSelectT9Syllable(syl) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = syl,
                                    fontSize = railFontSize,
                                    fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isPrimary) palette.primaryMicSolid else palette.keyTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                            }
                            if (index < t9Syllables.lastIndex) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = palette.keyBorder.copy(alpha = 0.55f)
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        itemsIndexed(
                            items = T9_IDLE_PUNCTUATIONS,
                            key = { idx, item -> "punct_${idx}_${item.first}" }
                        ) { index, (label, paired) ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(railItemHeight)
                                    .clickable {
                                        if (paired != null) {
                                            val parts = paired.split('|')
                                            onInsertPairedSymbol(parts[0], parts[1])
                                        } else {
                                            onCommitSecondarySymbol(label)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = railFontSize,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.keyTextPrimary,
                                    maxLines = 1,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                            }
                            if (index < T9_IDLE_PUNCTUATIONS.lastIndex) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = palette.keyBorder.copy(alpha = 0.55f)
                                )
                            }
                        }
                    }
                }
            }

            T9SymbolEmojiKey(
                label = "符号",
                topBadge = "",
                containerColor = palette.keyCapSecondaryBg,
                contentColor = palette.keyTextPrimary,
                onTap = onOpenSymbolLayer,
                onOpenEmoji = onOpenEmojiPanel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }

        // Center Column: 3×3 T9 Grid + Bottom Row (`123`, `空格`, `中/英`)
        Column(
            modifier = Modifier
                .weight(3.15f)
                .fillMaxHeight()
        ) {
            T9_GRID_ROWS.forEach { rowKeys ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    rowKeys.forEach { spec ->
                        T9DigitCharKey(
                            spec = spec,
                            isComposing = isComposing,
                            rootOriginInWindow = rootOriginInWindow,
                            onShowPreview = onShowPreview,
                            onHidePreview = onHidePreview,
                            onTap = {
                                if (spec.isDelimiterKey) {
                                    if (isComposing) {
                                        onCommitChar("'")
                                    } else {
                                        onTapKey1IdlePunctuation()
                                    }
                                } else {
                                    onCommitChar(spec.digit)
                                }
                            },
                            onSecondaryDigit = {
                                onCommitSecondarySymbol(spec.digit)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }

            // Bottom Row: `123` + `空格` (with cursor slide) + `中/英`
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                T9ActionKey(
                    label = "123",
                    containerColor = palette.keyCapSecondaryBg,
                    contentColor = palette.keyTextPrimary,
                    consumeUpAck = isComposing,
                    onClick = onOpenNumberLayer,
                    modifier = Modifier
                        .weight(0.92f)
                        .fillMaxHeight()
                )
                T9SpaceKey(
                    label = if (isComposing) "选词" else "九键",
                    isComposing = isComposing,
                    spaceLongPressAction = spaceLongPressAction,
                    isInPlaceVoiceActive = isInPlaceVoiceActive,
                    onTapSpace = onInsertSpace,
                    onMoveCursorByStep = onMoveCursorByStep,
                    onHoldStartInPlaceVoice = onHoldStartInPlaceVoice,
                    onHoldReleaseInPlaceVoice = onHoldReleaseInPlaceVoice,
                    modifier = Modifier
                        .weight(1.42f)
                        .fillMaxHeight()
                )
                T9ActionKey(
                    label = "中/英",
                    containerColor = palette.keyCapSecondaryBg,
                    contentColor = palette.keyTextPrimary,
                    onClick = onToggleLanguageMode,
                    modifier = Modifier
                        .weight(0.86f)
                        .fillMaxHeight()
                )
            }
        }

        // Right Column: Row 1 Backspace + Row 2 Reset (composing) / Emoji (idle, Scheme B) + Row 3-4 Double-Height Enter/Send
        Column(
            modifier = Modifier
                .weight(0.95f)
                .fillMaxHeight()
        ) {
            T9BackspaceKey(
                onBackspace = onBackspace,
                onSwipeUpUndo = onSwipeUpBackspaceUndo,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            if (isComposing && composingCode.isNotEmpty()) {
                T9ActionKey(
                    label = "重输",
                    containerColor = palette.keyCapSecondaryBg,
                    contentColor = palette.keyTextPrimary,
                    onClick = onClearComposing,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else {
                T9ActionKey(
                    label = tr("☺ 表情", "☺ Emoji", "☺ 表情"),
                    containerColor = palette.keyCapSecondaryBg,
                    contentColor = palette.keyTextPrimary,
                    fontSizeSp = 13.5f,
                    onClick = onOpenEmojiPanel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            T9ActionKey(
                label = enterActionInfo.label,
                containerColor = palette.primaryMicSolid,
                contentColor = palette.onPrimaryMic,
                fontWeight = FontWeight.Bold,
                onClick = onEnterOrNewline,
                modifier = Modifier
                    .weight(2f)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun T9NumberPad(
    enterActionInfo: EnterActionInfo,
    onCommitDigitOrSymbol: (String) -> Unit,
    onInsertSpace: () -> Unit,
    onBackspace: () -> Unit,
    onSwipeUpBackspaceUndo: () -> Unit,
    onEnterOrNewline: () -> Unit,
    onReturnToMain: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val rows = remember {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9")
        )
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Left Column: Math & format symbols rail + Return button
        Column(
            modifier = Modifier
                .weight(0.92f)
                .fillMaxHeight()
        ) {
            Box(
                modifier = Modifier
                    .weight(3f)
                    .fillMaxWidth()
                    .padding(T9_CELL_PAD)
                    .clip(RoundedCornerShape(T9_KEY_CORNER))
                    .background(palette.keyCapSecondaryBg)
                    .border(0.8.dp, palette.keyBorder, RoundedCornerShape(T9_KEY_CORNER))
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    itemsIndexed(T9_NUMBER_SIDE_SYMBOLS) { idx, sym ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .clickable { onCommitDigitOrSymbol(sym) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = sym,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.keyTextPrimary,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                        if (idx < T9_NUMBER_SIDE_SYMBOLS.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = palette.keyBorder.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }

            T9ActionKey(
                label = "返回",
                containerColor = palette.keyCapSecondaryBg,
                contentColor = palette.keyTextPrimary,
                consumeUpAck = false,
                onClick = onReturnToMain,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }

        // Center Column: Large 3×4 telephone-style digits
        Column(
            modifier = Modifier
                .weight(3.15f)
                .fillMaxHeight()
        ) {
            rows.forEach { digits ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    digits.forEach { d ->
                        T9ActionKey(
                            label = d,
                            containerColor = palette.keyCapBg,
                            contentColor = palette.keyTextPrimary,
                            fontSizeSp = 20,
                            fontWeight = FontWeight.Medium,
                            onClick = { onCommitDigitOrSymbol(d) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                T9ActionKey(
                    label = ".",
                    containerColor = palette.keyCapBg,
                    contentColor = palette.keyTextPrimary,
                    fontSizeSp = 20,
                    onClick = { onCommitDigitOrSymbol(".") },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                T9ActionKey(
                    label = "0",
                    containerColor = palette.keyCapBg,
                    contentColor = palette.keyTextPrimary,
                    fontSizeSp = 20,
                    fontWeight = FontWeight.Medium,
                    onClick = { onCommitDigitOrSymbol("0") },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                T9ActionKey(
                    label = "空格",
                    containerColor = palette.keyCapSecondaryBg,
                    contentColor = palette.keyTextPrimary,
                    onClick = onInsertSpace,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }

        // Right Column: Backspace + `@` + Double-height Enter
        Column(
            modifier = Modifier
                .weight(0.95f)
                .fillMaxHeight()
        ) {
            T9BackspaceKey(
                onBackspace = onBackspace,
                onSwipeUpUndo = onSwipeUpBackspaceUndo,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
            T9ActionKey(
                label = "@",
                containerColor = palette.keyCapSecondaryBg,
                contentColor = palette.keyTextPrimary,
                fontSizeSp = 16,
                onClick = { onCommitDigitOrSymbol("@") },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
            T9ActionKey(
                label = enterActionInfo.label,
                containerColor = palette.primaryMicSolid,
                contentColor = palette.onPrimaryMic,
                fontWeight = FontWeight.Bold,
                onClick = onEnterOrNewline,
                modifier = Modifier
                    .weight(2f)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun T9SymbolPad(
    enterActionInfo: EnterActionInfo,
    isMoreSymbols: Boolean,
    onToggleMoreSymbols: () -> Unit,
    onCommitSymbol: (String) -> Unit,
    onInsertSpace: () -> Unit,
    onBackspace: () -> Unit,
    onEnterOrNewline: () -> Unit,
    onReturnToMain: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val grid = if (isMoreSymbols) T9_SYMBOL_PAGE_2 else T9_SYMBOL_PAGE_1

    Column(modifier = Modifier.fillMaxSize()) {
        grid.forEach { rowSyms ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                rowSyms.forEach { sym ->
                    T9ActionKey(
                        label = sym,
                        containerColor = palette.keyCapBg,
                        contentColor = palette.keyTextPrimary,
                        fontSizeSp = 16,
                        onClick = { onCommitSymbol(sym) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            T9ActionKey(
                label = "返回",
                containerColor = palette.keyCapSecondaryBg,
                contentColor = palette.keyTextPrimary,
                consumeUpAck = false,
                onClick = onReturnToMain,
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
            )
            T9ActionKey(
                label = if (isMoreSymbols) "中文符" else "英文符",
                containerColor = palette.keyCapSecondaryBg,
                contentColor = palette.keyTextPrimary,
                consumeUpAck = false,
                onClick = onToggleMoreSymbols,
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight()
            )
            T9ActionKey(
                label = "空格",
                containerColor = palette.keyCapBg,
                contentColor = palette.keyTextPrimary,
                onClick = onInsertSpace,
                modifier = Modifier
                    .weight(1.45f)
                    .fillMaxHeight()
            )
            T9BackspaceKey(
                onBackspace = onBackspace,
                onSwipeUpUndo = {},
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            T9ActionKey(
                label = enterActionInfo.label,
                containerColor = palette.primaryMicSolid,
                contentColor = palette.onPrimaryMic,
                fontWeight = FontWeight.Bold,
                onClick = onEnterOrNewline,
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun T9DigitCharKey(
    spec: T9KeySpec,
    isComposing: Boolean,
    rootOriginInWindow: Offset,
    onShowPreview: (String, Float, Float) -> Long,
    onHidePreview: (Long) -> Unit,
    onTap: () -> Unit,
    onSecondaryDigit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val density = LocalDensity.current
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val hostView = LocalView.current
    val swipeUpThresholdPx = with(density) { 18.dp.toPx() }
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnSecondary by rememberUpdatedState(onSecondaryDigit)
    val latestShowPreview by rememberUpdatedState(onShowPreview)
    val latestHidePreview by rememberUpdatedState(onHidePreview)
    val latestRootOrigin by rememberUpdatedState(rootOriginInWindow)

    var pressed by remember { mutableStateOf(false) }
    var swipeUpArmed by remember { mutableStateOf(false) }
    var keyOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    var keySizePx by remember { mutableStateOf(IntSize.Zero) }

    val primaryText = if (spec.isDelimiterKey) {
        if (isComposing) "分词" else "，@"
    } else {
        spec.letters
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                keyOriginInWindow = coords.positionInWindow()
                keySizePx = coords.size
            }
            .pointerInput(spec.digit) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        pressed = true
                        keyFeedback.onTouchFeedback(HapticTier.ALPHA_KEY, hostView, KeySoundType.STANDARD, true)
                        swipeUpArmed = false
                        val cx = (keyOriginInWindow.x - latestRootOrigin.x) + keySizePx.width / 2f
                        val topY = keyOriginInWindow.y - latestRootOrigin.y
                        var previewToken = latestShowPreview(if (spec.isDelimiterKey) "1" else spec.letters, cx, topY)
                        var longPressFired = false
                        val longPressJob: Job = launch {
                            delay(ImeLogic.SECONDARY_KEY_LONG_PRESS_MS)
                            if (isActive) {
                                longPressFired = true
                                keyFeedback.onTouchFeedback(HapticTier.GESTURE_CONFIRM, hostView, KeySoundType.NONE, false)
                                latestHidePreview(previewToken)
                                previewToken = latestShowPreview(spec.digit, cx, topY)
                                latestOnSecondary()
                            }
                        }

                        var cancelled = false
                        var dy = 0f
                        var dx = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            dx = change.position.x - down.position.x
                            dy = change.position.y - down.position.y
                            val nowSwipe = dy <= -swipeUpThresholdPx && abs(dy) > abs(dx)
                            if (nowSwipe != swipeUpArmed && !longPressFired) {
                                swipeUpArmed = nowSwipe
                                if (nowSwipe) {
                                    longPressJob.cancel()
                                    keyFeedback.onTouchFeedback(HapticTier.GESTURE_CONFIRM, hostView, KeySoundType.NONE, false)
                                    latestHidePreview(previewToken)
                                    previewToken = latestShowPreview(spec.digit, cx, topY)
                                }
                            }
                            if (abs(dx) > keySizePx.width * 1.4f && !nowSwipe) {
                                cancelled = true
                            }
                            change.consume()
                        }

                        longPressJob.cancel()
                        pressed = false
                        val triggerSecondary = swipeUpArmed && !longPressFired
                        swipeUpArmed = false
                        if (!longPressFired && !cancelled) {
                            if (triggerSecondary) {
                                latestOnSecondary()
                            } else {
                                latestOnTap()
                            }
                        }
                        launch {
                            delay(70L)
                            latestHidePreview(previewToken)
                        }
                    }
                }
            }
            .padding(T9_CELL_PAD)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = if (pressed) 0.965f else 1f
                    scaleX = s
                    scaleY = s
                }
                .clip(RoundedCornerShape(T9_KEY_CORNER))
                .background(palette.keyShadow)
                .padding(bottom = T9_KEY_RIM)
                .clip(RoundedCornerShape(T9_KEY_CORNER))
                .background(if (pressed) palette.capsuleSelectedBg else palette.keyCapBg)
                .border(0.6.dp, palette.keyBorder.copy(alpha = 0.7f), RoundedCornerShape(T9_KEY_CORNER))
        ) {
            // Top-center subtle digit badge (hinting swipe-up / long-press digit)
            Text(
                text = spec.digit,
                fontSize = (11.2f * metrics.keyFontScale).sp,
                fontWeight = if (swipeUpArmed) FontWeight.Bold else FontWeight.Medium,
                color = if (swipeUpArmed) palette.primaryMicSolid else palette.keyTextMuted.copy(alpha = 0.80f),
                maxLines = 1,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.5.dp)
            )

            // Center main letters / delimiter label
            Text(
                text = primaryText,
                fontSize = ((if (spec.isDelimiterKey) 15.8f else 18.5f) * metrics.keyFontScale).sp,
                fontWeight = FontWeight.SemiBold,
                color = if (spec.isDelimiterKey && isComposing) palette.primaryMicSolid else palette.keyTextPrimary,
                maxLines = 1,
                letterSpacing = if (spec.isDelimiterKey) 0.sp else 0.6.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 6.5.dp)
            )
        }
    }
}

@Composable
private fun T9SpaceKey(
    label: String,
    isComposing: Boolean,
    spaceLongPressAction: SpaceLongPressAction = SpaceLongPressAction.VOICE_HOLD,
    isInPlaceVoiceActive: Boolean = false,
    onTapSpace: () -> Unit,
    onMoveCursorByStep: (Int) -> Unit,
    onHoldStartInPlaceVoice: (() -> Unit)? = null,
    onHoldReleaseInPlaceVoice: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val view = LocalView.current
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val density = LocalDensity.current
    val slideActivatePx = with(density) { ImeLogic.SPACE_CURSOR_SLIDE_ACTIVATE_DP.dp.toPx() }
    val stepPx = with(density) { ImeLogic.SPACE_CURSOR_SLIDE_STEP_DP.dp.toPx() }
    val latestTap by rememberUpdatedState(onTapSpace)
    val latestMove by rememberUpdatedState(onMoveCursorByStep)
    val latestComposing by rememberUpdatedState(isComposing)
    val latestSpaceAction by rememberUpdatedState(spaceLongPressAction)
    val latestOnHoldStartVoice by rememberUpdatedState(onHoldStartInPlaceVoice)
    val latestOnHoldReleaseVoice by rememberUpdatedState(onHoldReleaseInPlaceVoice)
    var pressed by remember { mutableStateOf(false) }
    var isCursorSliding by remember { mutableStateOf(false) }
    var isVoiceHolding by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                coroutineScope {
                    val outerScope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        pressed = true
                        keyFeedback.onTouchFeedback(HapticTier.FUNCTION_KEY, view, KeySoundType.SPACEBAR, true)
                        isCursorSliding = false
                        isVoiceHolding = false
                        val startX = down.position.x
                        var lastX = startX
                        var accumDx = 0f
                        var didSlide = false
                        var holdTriggered = false
                        var voiceHoldStarted = false
                        var voiceHoldReleased = false

                        val holdJob: Job? = if (
                            !latestComposing &&
                            latestSpaceAction == SpaceLongPressAction.VOICE_HOLD &&
                            latestOnHoldStartVoice != null
                        ) {
                            outerScope.launch {
                                delay(ImeLogic.SPACE_VOICE_HOLD_MS)
                                if (!didSlide && !holdTriggered && !latestComposing) {
                                    holdTriggered = true
                                    voiceHoldStarted = true
                                    isVoiceHolding = true
                                    keyFeedback.onTouchFeedback(HapticTier.MIC_STRONG, view, KeySoundType.NONE, false)
                                    latestOnHoldStartVoice?.invoke()
                                }
                            }
                        } else {
                            null
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                if (!latestComposing && !holdTriggered) {
                                    val totalDx = change.position.x - startX
                                    val deltaX = change.position.x - lastX
                                    lastX = change.position.x
                                    if (!didSlide && abs(totalDx) >= slideActivatePx) {
                                        didSlide = true
                                        isCursorSliding = true
                                        holdJob?.cancel()
                                        accumDx = totalDx
                                    } else if (didSlide) {
                                        accumDx += deltaX
                                    }
                                    if (didSlide) {
                                        val (steps, rem) = ImeLogic.computeSpaceCursorSlideSteps(accumDx, stepPx)
                                        if (steps != 0) {
                                            accumDx = rem
                                            latestMove(steps)
                                        }
                                    }
                                }
                                change.consume()
                            }
                        } finally {
                            pressed = false
                            isCursorSliding = false
                            isVoiceHolding = false
                            holdJob?.cancel()
                            if (voiceHoldStarted && !voiceHoldReleased) {
                                voiceHoldReleased = true
                                latestOnHoldReleaseVoice?.invoke()
                            }
                        }
                        if (!didSlide && !holdTriggered) {
                            latestTap()
                        }
                    }
                }
            }
            .padding(T9_CELL_PAD)
    ) {
        val activeSpaceVoice = isVoiceHolding && isInPlaceVoiceActive
        val faceBg = when {
            activeSpaceVoice -> TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.22f else 0.14f)
            pressed -> palette.capsuleSelectedBg
            else -> palette.keyCapBg
        }
        val borderColor = if (activeSpaceVoice) {
            TypeFreeEmerald.copy(alpha = 0.68f)
        } else {
            palette.keyBorder.copy(alpha = 0.7f)
        }
        val displayLabel = when {
            activeSpaceVoice -> tr("🎙 松手上屏", "🎙 Release", "🎙 放開上屏")
            isCursorSliding -> tr("‹ 滑动 ›", "‹ Slide ›", "‹ 滑動 ›")
            else -> label
        }
        val textColor = when {
            activeSpaceVoice -> if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
            isComposing || isCursorSliding -> palette.primaryMicSolid
            else -> palette.keyTextMuted
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(T9_KEY_CORNER))
                .background(palette.keyShadow)
                .padding(bottom = T9_KEY_RIM)
                .clip(RoundedCornerShape(T9_KEY_CORNER))
                .background(faceBg)
                .border(0.6.dp, borderColor, RoundedCornerShape(T9_KEY_CORNER)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayLabel,
                fontSize = ((if (activeSpaceVoice) 12.0f else 14.2f) * metrics.keyFontScale).sp,
                fontWeight = if (activeSpaceVoice || isComposing) FontWeight.SemiBold else FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }
}

@Composable
private fun T9BackspaceKey(
    onBackspace: () -> Unit,
    onSwipeUpUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val density = LocalDensity.current
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val hostView = LocalView.current
    val swipeUpPx = with(density) { 18.dp.toPx() }
    val latestBackspace by rememberUpdatedState(onBackspace)
    val latestUndo by rememberUpdatedState(onSwipeUpUndo)
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        pressed = true
                        keyFeedback.onTouchFeedback(HapticTier.FUNCTION_KEY, hostView, KeySoundType.DELETE, true)
                        var didSwipeUp = false
                        var didRepeat = false
                        val repeatJob = launch {
                            delay(380L)
                            while (isActive) {
                                didRepeat = true
                                keyFeedback.onTouchFeedback(HapticTier.CURSOR_TICK, hostView, KeySoundType.DELETE, true)
                                latestBackspace()
                                delay(54L)
                            }
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (!didRepeat && !didSwipeUp && dy <= -swipeUpPx && abs(dy) > abs(dx)) {
                                didSwipeUp = true
                                repeatJob.cancel()
                                keyFeedback.onTouchFeedback(HapticTier.GESTURE_CONFIRM, hostView, KeySoundType.NONE, false)
                            }
                            change.consume()
                        }
                        repeatJob.cancel()
                        pressed = false
                        if (didSwipeUp) {
                            latestUndo()
                        } else if (!didRepeat) {
                            latestBackspace()
                        }
                    }
                }
            }
            .padding(T9_CELL_PAD)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(T9_KEY_CORNER))
                .background(palette.keyShadow)
                .padding(bottom = T9_KEY_RIM)
                .clip(RoundedCornerShape(T9_KEY_CORNER))
                .background(if (pressed) palette.capsuleSelectedBg else palette.keyCapSecondaryBg)
                .border(0.6.dp, palette.keyBorder.copy(alpha = 0.7f), RoundedCornerShape(T9_KEY_CORNER)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = TypeFreeIcons.Backspace,
                contentDescription = "删除",
                tint = palette.keyTextPrimary,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

@Composable
private fun T9SymbolEmojiKey(
    label: String,
    topBadge: String,
    containerColor: Color,
    contentColor: Color,
    onTap: () -> Unit,
    onOpenEmoji: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val density = LocalDensity.current
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val hostView = LocalView.current
    val swipeUpPx = with(density) { 18.dp.toPx() }
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnOpenEmoji by rememberUpdatedState(onOpenEmoji)
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        pressed = true
                        keyFeedback.onTouchFeedback(HapticTier.FUNCTION_KEY, hostView, KeySoundType.STANDARD, true)
                        var gestureFired = false
                        val longJob = launch {
                            delay(ImeLogic.SECONDARY_KEY_LONG_PRESS_MS)
                            if (isActive && !gestureFired) {
                                gestureFired = true
                                keyFeedback.onTouchFeedback(HapticTier.GESTURE_CONFIRM, hostView, KeySoundType.NONE, false)
                                latestOnOpenEmoji()
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                if (!gestureFired && dy <= -swipeUpPx && abs(dy) > abs(dx)) {
                                    gestureFired = true
                                    longJob.cancel()
                                    change.consume()
                                    keyFeedback.onTouchFeedback(HapticTier.GESTURE_CONFIRM, hostView, KeySoundType.NONE, false)
                                    latestOnOpenEmoji()
                                }
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                            }
                        } finally {
                            pressed = false
                            longJob.cancel()
                        }
                        if (!gestureFired) {
                            latestOnTap()
                        }
                    }
                }
            }
            .padding(T9_CELL_PAD)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(T9_KEY_CORNER))
                .background(palette.keyShadow)
                .padding(bottom = T9_KEY_RIM)
                .clip(RoundedCornerShape(T9_KEY_CORNER))
                .background(if (pressed) palette.capsuleSelectedBg else containerColor)
                .border(0.6.dp, palette.keyBorder.copy(alpha = 0.7f), RoundedCornerShape(T9_KEY_CORNER))
        ) {
            if (topBadge.isNotEmpty()) {
                Text(
                    text = topBadge,
                    fontSize = (9.0f * metrics.widthScale).sp,
                    lineHeight = (10.5f * metrics.widthScale).sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.keyTextMuted.copy(alpha = 0.80f),
                    maxLines = 1,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 2.5.dp)
                )
            }
            Text(
                text = label,
                fontSize = (14.2f * metrics.keyFontScale).sp,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = if (topBadge.isNotEmpty()) 3.5.dp else 0.dp)
            )
        }
    }
}

@Composable
private fun T9ActionKey(
    label: String,
    containerColor: Color,
    contentColor: Color,
    fontSizeSp: Number = 14.2f,
    fontWeight: FontWeight = FontWeight.Medium,
    consumeUpAck: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val hostView = LocalView.current
    val latestConsumeUpAck by rememberUpdatedState(consumeUpAck)
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { ev ->
            if (ev is PressInteraction.Press) {
                keyFeedback.onTouchFeedback(HapticTier.FUNCTION_KEY, hostView, KeySoundType.STANDARD, latestConsumeUpAck)
            }
        }
    }
    Box(
        modifier = modifier
            .padding(T9_CELL_PAD)
            .clip(RoundedCornerShape(T9_KEY_CORNER))
            .background(palette.keyShadow)
            .padding(bottom = T9_KEY_RIM)
            .clip(RoundedCornerShape(T9_KEY_CORNER))
            .background(containerColor)
            .border(0.6.dp, palette.keyBorder.copy(alpha = 0.7f), RoundedCornerShape(T9_KEY_CORNER))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = (fontSizeSp.toFloat() * metrics.keyFontScale).sp,
            fontWeight = fontWeight,
            color = contentColor,
            maxLines = 1,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

/**
 * Compact host adapter keeping `KeyboardScreen.kt` well under the 650-line architecture guard.
 */
@Composable
fun BuiltInT9KeyboardHost(
    uiState: ImeUiState,
    showKeyPopupPreview: Boolean,
    onAction: (ImeUiAction) -> Unit,
    backspaceSwipeUpClearEnabled: Boolean = false,
    spaceLongPressAction: SpaceLongPressAction = SpaceLongPressAction.VOICE_HOLD,
    modifier: Modifier = Modifier
) {
    BuiltInT9Keyboard(
        enterActionInfo = uiState.enterActionInfo,
        isComposing = uiState.isFlypyComposing,
        composingCode = uiState.flypyComposingCode,
        t9Syllables = uiState.t9Syllables,
        onCommitChar = { onAction(ImeUiAction.CommitQwertyChar(it)) },
        onCommitSecondarySymbol = { onAction(ImeUiAction.CommitSecondarySymbol(it)) },
        onInsertPairedSymbol = { l, r -> onAction(ImeUiAction.InsertPairedSymbol(l, r)) },
        onSelectT9Syllable = { onAction(ImeUiAction.SelectT9Syllable(it)) },
        onMoveCursorByStep = { onAction(ImeUiAction.MoveCursorByChars(it)) },
        onInsertSpace = { onAction(ImeUiAction.InsertSpace) },
        onToggleLanguageMode = { onAction(ImeUiAction.ToggleFlypyLanguageMode) },
        onClearComposing = { onAction(ImeUiAction.ClearFlypyComposing) },
        onFlushComposingBeforeLayerSwitch = { onAction(ImeUiAction.FlushFlypyComposing) },
        onBackspace = { onAction(ImeUiAction.Backspace) },
        onSwipeUpBackspaceUndo = {
            onAction(if (backspaceSwipeUpClearEnabled) ImeUiAction.ClearInputBox else ImeUiAction.TriggerUndo)
        },
        onEnterOrNewline = { onAction(ImeUiAction.EnterOrNewline) },
        onSwitchToVoice = { onAction(ImeUiAction.SelectTab(KeyboardTabMode.VOICE)) },
        onTapKey1IdlePunctuation = { onAction(ImeUiAction.TapT9Key1Punctuation) },
        spaceLongPressAction = spaceLongPressAction,
        isInPlaceVoiceActive = uiState.tabMode == KeyboardTabMode.QWERTY && uiState.voicePhase is VoicePhase.Recording,
        onHoldStartInPlaceVoice = { onAction(ImeUiAction.HoldStartInPlaceVoice) },
        onHoldReleaseInPlaceVoice = { onAction(ImeUiAction.HoldReleaseInPlaceVoice) },
        onOpenExpressionPanel = { onAction(ImeUiAction.OpenExpressionPanel(it)) },
        inputSessionToken = uiState.inputSessionToken,
        showKeyPopupPreview = showKeyPopupPreview,
        modifier = modifier
    )
}
