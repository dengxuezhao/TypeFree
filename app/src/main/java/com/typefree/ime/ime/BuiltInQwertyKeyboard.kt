package com.typefree.ime.ime

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class EditorShortcutAction {
    SELECT_ALL,
    CUT,
    COPY,
    PASTE
}

private val ALPHA_ROW_1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
private val ALPHA_ROW_2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
private val ALPHA_ROW_3 = listOf("z", "x", "c", "v", "b", "n", "m")

private val NUM_ROW_1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
private val NUM_ROW_2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
private val NUM_ROW_3 = listOf("，", "。", "？", "！", ":", "\"", "'")

private val SYM_ROW_1 = listOf("[", "]", "{", "}", "<", ">", "^", "*", "=", "~")
private val SYM_ROW_2 = listOf("_", "\\", "|", "、", "；", "“", "”", "‘", "’", "…")
private val SYM_ROW_3 = listOf("¥", "€", "£", "°", "•", "`", "?")

private const val KEY_LONG_PRESS_MS = 290L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BuiltInQwertyKeyboard(
    enterActionInfo: EnterActionInfo,
    isFlypyChineseMode: Boolean,
    flypyComposingCode: String,
    flypyCandidateCount: Int,
    commaPeriodQuickSelect: Boolean,
    onCommitChar: (String) -> Unit,
    onCommaOrPeriodKey: (Boolean) -> Unit,
    onInsertSpace: () -> Unit,
    onToggleFlypyLanguageMode: () -> Unit,
    onClearComposing: () -> Unit,
    onFlushComposingBeforeLayerSwitch: () -> Unit,
    onBackspace: () -> Unit,
    onSwipeUpBackspaceUndo: () -> Unit,
    onEditorShortcutAction: (EditorShortcutAction) -> Unit,
    onEnterOrNewline: () -> Unit,
    onSwitchToVoice: () -> Unit,
    onSwitchSystemIme: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current

    var isSymbolLayer by remember { mutableStateOf(false) }
    var isMoreSymbols by remember { mutableStateOf(false) }
    var shiftState by remember { mutableStateOf(ShiftKeyState.OFF) }
    var lastShiftTapMs by remember { mutableLongStateOf(0L) }

    val isUpperCase = shiftState != ShiftKeyState.OFF
    val hasActiveFlypyComposing = isFlypyChineseMode && flypyComposingCode.isNotEmpty()

    fun subHintForKey(ch: String): String? = when (ch) {
        "a" -> "全"
        "x" -> "剪"
        "c" -> "复"
        "v" -> "粘"
        "z" -> "`"
        "l" -> ";"
        else -> null
    }

    fun longPressActionForKey(ch: String): (() -> Unit)? = when (ch) {
        "a" -> ({ onEditorShortcutAction(EditorShortcutAction.SELECT_ALL) })
        "x" -> ({ onEditorShortcutAction(EditorShortcutAction.CUT) })
        "c" -> ({ onEditorShortcutAction(EditorShortcutAction.COPY) })
        "v" -> ({ onEditorShortcutAction(EditorShortcutAction.PASTE) })
        "z" -> ({ onCommitChar("`") })
        "l" -> ({ onCommitChar(";") })
        else -> null
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (!isSymbolLayer) {
            // Row 1: q w e r t y u i o p
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                ALPHA_ROW_1.forEach { ch ->
                    val display = if (isUpperCase) ch.uppercase() else ch
                    QwertyCharKey(
                        label = display,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onClick = {
                            onCommitChar(display)
                            shiftState = ImeLogic.consumeShiftAfterCharCommit(shiftState)
                        }
                    )
                }
            }

            // Row 2: indented a s d f g h j k l
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Spacer(modifier = Modifier.weight(0.48f))
                ALPHA_ROW_2.forEach { ch ->
                    val display = if (isUpperCase) ch.uppercase() else ch
                    QwertyCharKey(
                        label = display,
                        subLabel = subHintForKey(ch),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onClick = {
                            onCommitChar(display)
                            shiftState = ImeLogic.consumeShiftAfterCharCommit(shiftState)
                        },
                        onLongPress = longPressActionForKey(ch)
                    )
                }
                Spacer(modifier = Modifier.weight(0.48f))
            }

            // Row 3: ⇧/清码 z x c v b n m ⌫(上划撤回)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                QwertyShiftKey(
                    shiftState = shiftState,
                    isClearCodeMode = hasActiveFlypyComposing,
                    modifier = Modifier
                        .weight(1.48f)
                        .fillMaxHeight(),
                    onTap = {
                        if (hasActiveFlypyComposing) {
                            onClearComposing()
                        } else {
                            val now = System.currentTimeMillis()
                            val delta = if (lastShiftTapMs > 0L) now - lastShiftTapMs else Long.MAX_VALUE
                            shiftState = ImeLogic.nextShiftState(shiftState, delta)
                            lastShiftTapMs = now
                        }
                    },
                    onLongPress = {
                        if (hasActiveFlypyComposing) {
                            onClearComposing()
                        } else {
                            shiftState = if (shiftState == ShiftKeyState.CAPS_LOCK) {
                                ShiftKeyState.OFF
                            } else {
                                ShiftKeyState.CAPS_LOCK
                            }
                            lastShiftTapMs = 0L
                        }
                    }
                )

                ALPHA_ROW_3.forEach { ch ->
                    val display = if (isUpperCase) ch.uppercase() else ch
                    QwertyCharKey(
                        label = display,
                        subLabel = subHintForKey(ch),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onClick = {
                            onCommitChar(display)
                            shiftState = ImeLogic.consumeShiftAfterCharCommit(shiftState)
                        },
                        onLongPress = longPressActionForKey(ch)
                    )
                }

                QwertyRepeatingBackspaceKey(
                    onBackspace = onBackspace,
                    onSwipeUpUndo = onSwipeUpBackspaceUndo,
                    modifier = Modifier
                        .weight(1.48f)
                        .fillMaxHeight()
                )
            }
        } else {
            val row1 = if (isMoreSymbols) SYM_ROW_1 else NUM_ROW_1
            val row2 = if (isMoreSymbols) SYM_ROW_2 else NUM_ROW_2
            val row3 = if (isMoreSymbols) SYM_ROW_3 else NUM_ROW_3

            // Symbol Row 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                row1.forEach { sym ->
                    QwertyCharKey(
                        label = sym,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onClick = { onCommitChar(sym) }
                    )
                }
            }

            // Symbol Row 2
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                row2.forEach { sym ->
                    QwertyCharKey(
                        label = sym,
                        fontSize = 15.5.sp,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onClick = { onCommitChar(sym) }
                    )
                }
            }

            // Symbol Row 3: #+= / 123 + common ZH/EN punctuation + ⌫
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                QwertyFunctionKey(
                    label = if (isMoreSymbols) "123" else "#+=",
                    modifier = Modifier
                        .weight(1.48f)
                        .fillMaxHeight(),
                    onClick = { isMoreSymbols = !isMoreSymbols }
                )

                row3.forEach { sym ->
                    QwertyCharKey(
                        label = sym,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onClick = { onCommitChar(sym) }
                    )
                }

                QwertyRepeatingBackspaceKey(
                    onBackspace = onBackspace,
                    onSwipeUpUndo = onSwipeUpBackspaceUndo,
                    modifier = Modifier
                        .weight(1.48f)
                        .fillMaxHeight()
                )
            }
        }

        // Row 4: 123/ABC | 🌐 | , (次选2) | Space (小鹤音形 / English, 长按切中英) | . (三选3) | Enter/Action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.04f),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            QwertyFunctionKey(
                label = if (isSymbolLayer) "ABC" else "123",
                modifier = Modifier
                    .weight(1.36f)
                    .fillMaxHeight(),
                onClick = {
                    if (!isSymbolLayer && hasActiveFlypyComposing) {
                        onFlushComposingBeforeLayerSwitch()
                    }
                    isSymbolLayer = !isSymbolLayer
                    isMoreSymbols = false
                }
            )

            // Globe: tap = switch back to Voice mode, long-press = system IME picker / previous IME
            val globeInteraction = remember { MutableInteractionSource() }
            val globePressed by globeInteraction.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .weight(1.05f)
                    .fillMaxHeight()
                    .scale(if (globePressed) 0.95f else 1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(palette.keyCapSecondaryBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(9.dp))
                    .combinedClickable(
                        interactionSource = globeInteraction,
                        indication = null,
                        onClick = onSwitchToVoice,
                        onLongClick = onSwitchSystemIme
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = TypeFreeIcons.Globe,
                    contentDescription = "切回语音或切换输入法",
                    tint = palette.keyTextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }

            val showSecondBadge = hasActiveFlypyComposing && commaPeriodQuickSelect && flypyCandidateCount >= 2
            val showThirdBadge = hasActiveFlypyComposing && commaPeriodQuickSelect && flypyCandidateCount >= 3

            QwertyCharKey(
                label = if (isFlypyChineseMode && !isSymbolLayer) "，" else ",",
                subLabel = if (showSecondBadge) "2" else if (isFlypyChineseMode && !hasActiveFlypyComposing) "," else null,
                highlightSubLabel = showSecondBadge,
                fontSize = 17.sp,
                modifier = Modifier
                    .weight(0.95f)
                    .fillMaxHeight(),
                onClick = {
                    if (isSymbolLayer) {
                        onCommitChar(",")
                    } else {
                        onCommaOrPeriodKey(true)
                    }
                },
                onLongPress = if (isFlypyChineseMode && !hasActiveFlypyComposing) {
                    { onCommitChar(",") }
                } else {
                    null
                }
            )

            // Wide Space Bar: Tap commits 1st candidate / space, Long-Press toggles 鹤 (中文) <-> EN
            QwertySpaceBarKey(
                isFlypyChineseMode = isFlypyChineseMode && !isSymbolLayer,
                hasComposing = hasActiveFlypyComposing,
                onTap = onInsertSpace,
                onLongPress = onToggleFlypyLanguageMode,
                modifier = Modifier
                    .weight(3.55f)
                    .fillMaxHeight()
            )

            QwertyCharKey(
                label = if (isFlypyChineseMode && !isSymbolLayer) "。" else ".",
                subLabel = if (showThirdBadge) "3" else if (isFlypyChineseMode && !hasActiveFlypyComposing) "." else null,
                highlightSubLabel = showThirdBadge,
                fontSize = 17.sp,
                modifier = Modifier
                    .weight(0.95f)
                    .fillMaxHeight(),
                onClick = {
                    if (isSymbolLayer) {
                        onCommitChar(".")
                    } else {
                        onCommaOrPeriodKey(false)
                    }
                },
                onLongPress = if (isFlypyChineseMode && !hasActiveFlypyComposing) {
                    { onCommitChar(".") }
                } else {
                    null
                }
            )

            // Adaptive Enter / Action Key (commits raw code when composing in Flypy!)
            val isPrimaryAction = !hasActiveFlypyComposing && enterActionInfo.performEditorActionId != null
            val enterInteraction = remember { MutableInteractionSource() }
            val enterPressed by enterInteraction.collectIsPressedAsState()
            val enterBg = if (isPrimaryAction) palette.primaryMicSolid else palette.keyCapSecondaryBg
            val enterContentColor = if (isPrimaryAction) palette.onPrimaryMic else palette.keyTextPrimary

            Box(
                modifier = Modifier
                    .weight(1.68f)
                    .fillMaxHeight()
                    .scale(if (enterPressed) 0.95f else 1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(enterBg)
                    .border(
                        width = 1.dp,
                        color = if (isPrimaryAction) Color.Transparent else palette.keyBorder,
                        shape = RoundedCornerShape(9.dp)
                    )
                    .clickable(
                        interactionSource = enterInteraction,
                        indication = null,
                        onClick = onEnterOrNewline
                    )
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                if (hasActiveFlypyComposing) {
                    Text(
                        text = "编码",
                        color = enterContentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (!isPrimaryAction) {
                            Icon(
                                imageVector = TypeFreeIcons.ReturnEnter,
                                contentDescription = enterActionInfo.label,
                                tint = enterContentColor,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Text(
                            text = enterActionInfo.label,
                            color = enterContentColor,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QwertyCharKey(
    label: String,
    modifier: Modifier = Modifier,
    subLabel: String? = null,
    highlightSubLabel: Boolean = false,
    fontSize: TextUnit = 17.sp,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val density = LocalDensity.current
    val touchSlopPx = with(density) { 28.dp.toPx() }

    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .scale(if (isPressed) 0.93f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPressed) palette.keyCapSecondaryBg else palette.keyCapBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                coroutineScope {
                    val outerScope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isPressed = true
                        var longPressFired = false
                        var cancelled = false

                        val longJob: Job? = if (currentOnLongPress != null) {
                            outerScope.launch {
                                delay(KEY_LONG_PRESS_MS)
                                if (!cancelled) {
                                    longPressFired = true
                                    currentOnLongPress?.invoke()
                                }
                            }
                        } else {
                            null
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    cancelled = true
                                    break
                                }
                                val pos = change.position
                                if (
                                    pos.x < -touchSlopPx ||
                                    pos.x > size.width + touchSlopPx ||
                                    pos.y < -touchSlopPx ||
                                    pos.y > size.height + touchSlopPx
                                ) {
                                    cancelled = true
                                    longJob?.cancel()
                                    isPressed = false
                                }
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                            }
                        } finally {
                            isPressed = false
                            longJob?.cancel()
                        }

                        if (!cancelled && !longPressFired) {
                            currentOnClick()
                        }
                    }
                }
            }
    ) {
        if (!subLabel.isNullOrEmpty()) {
            Text(
                text = subLabel,
                color = if (highlightSubLabel) {
                    palette.keyTextPrimary
                } else {
                    palette.keyTextMuted.copy(alpha = 0.68f)
                },
                fontSize = if (highlightSubLabel) 10.sp else 8.5.sp,
                fontWeight = if (highlightSubLabel) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 3.5.dp)
            )
        }
        Text(
            text = label,
            color = palette.keyTextPrimary,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun QwertySpaceBarKey(
    isFlypyChineseMode: Boolean,
    hasComposing: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val density = LocalDensity.current
    val touchSlopPx = with(density) { 28.dp.toPx() }

    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .scale(if (isPressed) 0.98f else 1f)
            .clip(RoundedCornerShape(9.dp))
            .background(if (isPressed) palette.keyCapSecondaryBg else palette.keyCapBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(9.dp))
            .pointerInput(Unit) {
                coroutineScope {
                    val outerScope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isPressed = true
                        var longFired = false
                        var cancelled = false

                        val longJob = outerScope.launch {
                            delay(KEY_LONG_PRESS_MS)
                            if (!cancelled) {
                                longFired = true
                                currentOnLongPress()
                            }
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    cancelled = true
                                    break
                                }
                                val pos = change.position
                                if (
                                    pos.x < -touchSlopPx ||
                                    pos.x > size.width + touchSlopPx ||
                                    pos.y < -touchSlopPx ||
                                    pos.y > size.height + touchSlopPx
                                ) {
                                    cancelled = true
                                    longJob.cancel()
                                    isPressed = false
                                }
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                            }
                        } finally {
                            isPressed = false
                            longJob.cancel()
                        }

                        if (!cancelled && !longFired) {
                            currentOnTap()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val label = when {
            hasComposing -> "空格首选"
            isFlypyChineseMode -> "小鹤音形"
            else -> "English"
        }
        Text(
            text = label,
            color = if (hasComposing) palette.keyTextPrimary else palette.keyTextMuted,
            fontSize = 12.5.sp,
            fontWeight = if (hasComposing) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
        Text(
            text = if (isFlypyChineseMode) "EN" else "鹤",
            color = palette.keyTextMuted.copy(alpha = 0.55f),
            fontSize = 8.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 2.5.dp, end = 6.dp)
        )
    }
}

@Composable
private fun QwertyFunctionKey(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .scale(if (isPressed) 0.94f else 1f)
            .clip(RoundedCornerShape(9.dp))
            .background(if (isPressed) palette.keyBorder else palette.keyCapSecondaryBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(9.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = palette.keyTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QwertyShiftKey(
    shiftState: ShiftKeyState,
    isClearCodeMode: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor = when {
        isClearCodeMode -> palette.keyCapSecondaryBg
        shiftState == ShiftKeyState.CAPS_LOCK -> palette.primaryMicSolid
        shiftState == ShiftKeyState.ONCE -> palette.keyCapBg
        else -> palette.keyCapSecondaryBg
    }
    val borderColor = when {
        isClearCodeMode -> palette.keyTextPrimary.copy(alpha = 0.4f)
        shiftState == ShiftKeyState.CAPS_LOCK -> Color.Transparent
        shiftState == ShiftKeyState.ONCE -> palette.keyTextPrimary.copy(alpha = 0.55f)
        else -> palette.keyBorder
    }
    val textColor = when {
        isClearCodeMode -> palette.keyTextPrimary
        shiftState == ShiftKeyState.CAPS_LOCK -> palette.onPrimaryMic
        else -> palette.keyTextPrimary
    }

    Box(
        modifier = modifier
            .scale(if (isPressed) 0.94f else 1f)
            .clip(RoundedCornerShape(9.dp))
            .background(bgColor)
            .border(if (shiftState == ShiftKeyState.ONCE) 1.4.dp else 1.dp, borderColor, RoundedCornerShape(9.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap,
                onLongClick = onLongPress
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isClearCodeMode) {
            Text(
                text = "清码",
                color = textColor,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        } else {
            Text(
                text = if (shiftState == ShiftKeyState.CAPS_LOCK) "⇪" else "⇧",
                color = textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun QwertyRepeatingBackspaceKey(
    onBackspace: () -> Unit,
    onSwipeUpUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val density = LocalDensity.current
    val swipeUpThresholdPx = with(density) { 24.dp.toPx() }

    val currentOnBackspace by rememberUpdatedState(onBackspace)
    val currentOnSwipeUpUndo by rememberUpdatedState(onSwipeUpUndo)
    var isHolding by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .scale(if (isHolding) 0.94f else 1f)
            .clip(RoundedCornerShape(9.dp))
            .background(if (isHolding) palette.keyBorder else palette.keyCapSecondaryBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(9.dp))
            .pointerInput(Unit) {
                coroutineScope {
                    val outerScope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val startPos = down.position
                        isHolding = true
                        var swipedUp = false
                        var repeatStarted = false

                        val repeatJob = outerScope.launch {
                            delay(320L)
                            if (!swipedUp) {
                                repeatStarted = true
                                while (isActive && !swipedUp) {
                                    currentOnBackspace()
                                    delay(48L)
                                }
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val dy = change.position.y - startPos.y
                                if (!repeatStarted && !swipedUp && dy <= -swipeUpThresholdPx) {
                                    swipedUp = true
                                    repeatJob.cancel()
                                    change.consume()
                                    currentOnSwipeUpUndo()
                                }
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                            }
                        } finally {
                            isHolding = false
                            repeatJob.cancel()
                        }

                        if (!swipedUp && !repeatStarted) {
                            currentOnBackspace()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "撤",
            color = palette.keyTextMuted.copy(alpha = 0.58f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 2.dp, end = 3.5.dp)
        )
        Icon(
            imageVector = TypeFreeIcons.Backspace,
            contentDescription = "退格删除（上划撤回上屏）",
            tint = palette.keyTextPrimary,
            modifier = Modifier.size(19.dp)
        )
    }
}
