package com.typefree.ime.ime

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.typefree.ime.data.InputScheme
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class EditorShortcutAction {
    SELECT_ALL,
    CUT,
    COPY,
    PASTE
}

sealed interface SecondaryKeyAction {
    data class CommitSymbol(val symbol: String) : SecondaryKeyAction
    data class CommitPaired(val open: String, val close: String) : SecondaryKeyAction
    data class EditorShortcut(val action: EditorShortcutAction) : SecondaryKeyAction
}

data class SecondaryKeySpec(
    val keyChar: Char,
    val displayBadge: String,
    val allowSwipeUp: Boolean,
    val action: SecondaryKeyAction
)

/**
 * TypeFree v3.4.0 26-Key Full Secondary Symbol & Paired Punctuation Map.
 * - Row 1 (`Q..P`): `1..0` when `!showNumberRow` (`allowSwipeUp = true`).
 * - Row 2 (`A..L`): `A`=全(`SELECT_ALL`, `allowSwipeUp = false`), `S`=`@`, `D`=`#`, `F`=`¥`/`$`,
 *   `G`=`~`, `H`=`-`, `J`=`“”`/`""` (paired), `K`=`()` -> `（）`/`()` (paired), `L`=`：`/`:`.
 * - Row 3 (`Z..M`): `Z`=`` ` ``, `X`=剪(`CUT`, `allowSwipeUp = false`), `C`=复(`COPY`, `allowSwipeUp = false`),
 *   `V`=粘(`PASTE`, `allowSwipeUp = false`), `B`=`、`/`/`, `N`=`！`/`!`, `M`=`？`/`?`.
 */
object QwertySecondaryKeyMap {

    fun resolve(
        key: String,
        isChinese: Boolean,
        showNumberRow: Boolean = false
    ): SecondaryKeySpec? {
        val ch = key.firstOrNull()?.lowercaseChar() ?: return null
        return resolve(keyChar = ch, isChinese = isChinese, showNumberRow = showNumberRow)
    }

    fun resolve(
        keyChar: Char,
        isChinese: Boolean,
        showNumberRow: Boolean = false
    ): SecondaryKeySpec? {
        val lower = keyChar.lowercaseChar()
        return when (lower) {
            // Row 1: Q W E R T Y U I O P -> 1 2 3 4 5 6 7 8 9 0 (only when 4-row compact mode is active)
            'q' -> if (!showNumberRow) SecondaryKeySpec('q', "1", true, SecondaryKeyAction.CommitSymbol("1")) else null
            'w' -> if (!showNumberRow) SecondaryKeySpec('w', "2", true, SecondaryKeyAction.CommitSymbol("2")) else null
            'e' -> if (!showNumberRow) SecondaryKeySpec('e', "3", true, SecondaryKeyAction.CommitSymbol("3")) else null
            'r' -> if (!showNumberRow) SecondaryKeySpec('r', "4", true, SecondaryKeyAction.CommitSymbol("4")) else null
            't' -> if (!showNumberRow) SecondaryKeySpec('t', "5", true, SecondaryKeyAction.CommitSymbol("5")) else null
            'y' -> if (!showNumberRow) SecondaryKeySpec('y', "6", true, SecondaryKeyAction.CommitSymbol("6")) else null
            'u' -> if (!showNumberRow) SecondaryKeySpec('u', "7", true, SecondaryKeyAction.CommitSymbol("7")) else null
            'i' -> if (!showNumberRow) SecondaryKeySpec('i', "8", true, SecondaryKeyAction.CommitSymbol("8")) else null
            'o' -> if (!showNumberRow) SecondaryKeySpec('o', "9", true, SecondaryKeyAction.CommitSymbol("9")) else null
            'p' -> if (!showNumberRow) SecondaryKeySpec('p', "0", true, SecondaryKeyAction.CommitSymbol("0")) else null

            // Row 2: A S D F G H J K L
            'a' -> SecondaryKeySpec('a', "全", false, SecondaryKeyAction.EditorShortcut(EditorShortcutAction.SELECT_ALL))
            's' -> SecondaryKeySpec('s', "@", true, SecondaryKeyAction.CommitSymbol("@"))
            'd' -> SecondaryKeySpec('d', "#", true, SecondaryKeyAction.CommitSymbol("#"))
            'f' -> {
                val sym = if (isChinese) "¥" else "$"
                SecondaryKeySpec('f', sym, true, SecondaryKeyAction.CommitSymbol(sym))
            }
            'g' -> SecondaryKeySpec('g', "~", true, SecondaryKeyAction.CommitSymbol("~"))
            'h' -> SecondaryKeySpec('h', "-", true, SecondaryKeyAction.CommitSymbol("-"))
            'j' -> {
                val open = if (isChinese) "“" else "\""
                val close = if (isChinese) "”" else "\""
                val badge = if (isChinese) "“”" else "\"\""
                SecondaryKeySpec('j', badge, true, SecondaryKeyAction.CommitPaired(open, close))
            }
            'k' -> {
                val open = if (isChinese) "（" else "("
                val close = if (isChinese) "）" else ")"
                SecondaryKeySpec('k', "()", true, SecondaryKeyAction.CommitPaired(open, close))
            }
            'l' -> {
                val sym = if (isChinese) "：" else ":"
                SecondaryKeySpec('l', sym, true, SecondaryKeyAction.CommitSymbol(sym))
            }

            // Row 3: Z X C V B N M
            'z' -> SecondaryKeySpec('z', "`", true, SecondaryKeyAction.CommitSymbol("`"))
            'x' -> SecondaryKeySpec('x', "剪", false, SecondaryKeyAction.EditorShortcut(EditorShortcutAction.CUT))
            'c' -> SecondaryKeySpec('c', "复", false, SecondaryKeyAction.EditorShortcut(EditorShortcutAction.COPY))
            'v' -> SecondaryKeySpec('v', "粘", false, SecondaryKeyAction.EditorShortcut(EditorShortcutAction.PASTE))
            'b' -> {
                val sym = if (isChinese) "、" else "/"
                SecondaryKeySpec('b', sym, true, SecondaryKeyAction.CommitSymbol(sym))
            }
            'n' -> {
                val sym = if (isChinese) "！" else "!"
                SecondaryKeySpec('n', sym, true, SecondaryKeyAction.CommitSymbol(sym))
            }
            'm' -> {
                val sym = if (isChinese) "？" else "?"
                SecondaryKeySpec('m', sym, true, SecondaryKeyAction.CommitSymbol(sym))
            }
            else -> null
        }
    }

    /**
     * Evaluates whether an upward swipe vector (`dxPx`, `dyPx`) should trigger [spec]:
     * - Returns `false` for `A/X/C/V` (`allowSwipeUp == false`) so fast upward typing never
     *   accidentally triggers Select All / Cut / Copy / Paste.
     * - Returns `true` when `dyPx <= -thresholdPx` (`18.dp`) and vertical motion dominates (`abs(dyPx) > abs(dxPx)`).
     */
    fun shouldTriggerSwipeUp(
        spec: SecondaryKeySpec?,
        dxPx: Float,
        dyPx: Float,
        thresholdPx: Float
    ): Boolean {
        if (spec == null || !spec.allowSwipeUp || thresholdPx <= 0f) return false
        return dyPx <= -thresholdPx && abs(dyPx) > abs(dxPx)
    }

    /**
     * Pure helper computing `(committedText, newCursorOffsetFromStart)` for paired punctuation (`J`/`K`):
     * - When [selectedText] is non-empty, wraps it as `"$open$selectedText$close"` and places cursor at end.
     * - When [selectedText] is empty, inserts `"$open$close"` and places cursor at `open.length` (center).
     */
    fun formatPairedInsertion(
        open: String,
        close: String,
        selectedText: String = ""
    ): Pair<String, Int> {
        return if (selectedText.isNotEmpty()) {
            val wrapped = "$open$selectedText$close"
            wrapped to wrapped.length
        } else {
            val pair = "$open$close"
            pair to open.length
        }
    }

    /**
     * Pure helper verifying that `Z` (`\``) routes to the composing engine ONLY when
     * [inputScheme] is [InputScheme.FLYPY_TABLE] and [isComposing] is true (preserving Xiaohe
     * Yinxing stroke reverse-lookup `ji\``), and flushes + commits `\`` otherwise.
     */
    fun shouldRouteBacktickToComposingEngine(
        symbol: String,
        inputScheme: InputScheme,
        isComposing: Boolean,
        isChineseMode: Boolean = true
    ): Boolean {
        return symbol == "`" &&
            inputScheme == InputScheme.FLYPY_TABLE &&
            isChineseMode &&
            isComposing
    }
}

private data class PressedKeyPreview(
    val token: Long,
    val label: String,
    val centerXInRootPx: Float,
    val keyTopInRootPx: Float
)

private val ALPHA_ROW_1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
private val ALPHA_ROW_2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
private val ALPHA_ROW_3 = listOf("z", "x", "c", "v", "b", "n", "m")

private val NUM_ROW_1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
private val NUM_ROW_2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
private val NUM_ROW_3 = listOf("，", "。", "？", "！", ":", "\"", "'")

private val SYM_ROW_1 = listOf("[", "]", "{", "}", "<", ">", "^", "*", "=", "~")
private val SYM_ROW_2 = listOf("_", "\\", "|", "、", "；", "“", "”", "‘", "’", "…")
private val SYM_ROW_3 = listOf("¥", "€", "£", "°", "•", "`", "?")

private const val KEY_LONG_PRESS_MS = ImeLogic.SECONDARY_KEY_LONG_PRESS_MS

// Ergonomic Gboard / iOS / WeType Zero-Dead-Zone Cell Insets inside the compact 252.dp 4-row IME frame:
// Touch hitboxes fill 100% of the cell (`0.dp` dead space), while visual keycaps inset cleanly
// to hold a golden keycap aspect ratio with generous vertical row separation.
private val KEY_CELL_HPAD = 2.4.dp
private val KEY_CELL_VPAD_5ROW = 3.2.dp
private val KEY_CELL_VPAD_4ROW = 3.2.dp
private val NUM_ROW_VPAD = 2.8.dp
private val ALPHA_KEY_CORNER = 6.5.dp
private val NUM_KEY_CORNER = 6.0.dp
private val FUNC_KEY_CORNER = 7.5.dp
private val KEY_BOTTOM_RIM = 1.25.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BuiltInQwertyKeyboard(
    enterActionInfo: EnterActionInfo,
    isFlypyChineseMode: Boolean,
    activeInputScheme: InputScheme = InputScheme.FLYPY_TABLE,
    flypyComposingCode: String,
    flypyCandidateCount: Int,
    commaPeriodQuickSelect: Boolean,
    onCommitChar: (String) -> Unit,
    onCommitSecondarySymbol: (String) -> Unit = onCommitChar,
    onInsertPairedSymbol: (String, String) -> Unit = { l, r -> onCommitChar("$l$r") },
    onMoveCursorByStep: (Int) -> Unit = {},
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
    onHideKeyboard: () -> Unit = {},
    onShowImePicker: () -> Unit = {},
    showNumberRow: Boolean = false,
    inputSessionToken: Int = 0,
    showKeyPopupPreview: Boolean = true,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current

    var isSymbolLayer by remember { mutableStateOf(false) }
    var isMoreSymbols by remember { mutableStateOf(false) }
    var shiftState by remember { mutableStateOf(ShiftKeyState.OFF) }
    var lastShiftTapMs by remember { mutableLongStateOf(0L) }

    var rootOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    var rootSizePx by remember { mutableStateOf(IntSize.Zero) }
    var previewSeq by remember { mutableLongStateOf(0L) }
    var activeKeyPreview by remember { mutableStateOf<PressedKeyPreview?>(null) }

    LaunchedEffect(inputSessionToken) {
        isSymbolLayer = false
        isMoreSymbols = false
        if (shiftState == ShiftKeyState.ONCE) {
            shiftState = ShiftKeyState.OFF
        }
        activeKeyPreview = null
    }

    val isUpperCase = shiftState != ShiftKeyState.OFF
    val hasActiveFlypyComposing = isFlypyChineseMode && flypyComposingCode.isNotEmpty()
    val hasFiveRows = !isSymbolLayer && showNumberRow
    val rowVPad = if (hasFiveRows) KEY_CELL_VPAD_5ROW else KEY_CELL_VPAD_4ROW
    val letterFontSize = if (isUpperCase) 18.5.sp else 20.5.sp
    val letterFontWeight = if (isUpperCase) FontWeight.Medium else FontWeight.Normal

    fun executeSecondarySpec(spec: SecondaryKeySpec) {
        when (val act = spec.action) {
            is SecondaryKeyAction.CommitSymbol -> onCommitSecondarySymbol(act.symbol)
            is SecondaryKeyAction.CommitPaired -> onInsertPairedSymbol(act.open, act.close)
            is SecondaryKeyAction.EditorShortcut -> onEditorShortcutAction(act.action)
        }
    }

    fun showPreviewForKey(label: String, keyOriginInWindow: Offset, keySize: IntSize): Long {
        if (!showKeyPopupPreview || keySize.width <= 0) return 0L
        val nextToken = previewSeq + 1L
        previewSeq = nextToken
        activeKeyPreview = PressedKeyPreview(
            token = nextToken,
            label = label,
            centerXInRootPx = (keyOriginInWindow.x - rootOriginInWindow.x) + keySize.width / 2f,
            keyTopInRootPx = keyOriginInWindow.y - rootOriginInWindow.y
        )
        return nextToken
    }

    fun clearPreviewToken(token: Long) {
        if (token != 0L && activeKeyPreview?.token == token) {
            activeKeyPreview = null
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
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Main Key Grid (5-row when Top Number Row is ON, 4-row when OFF or in Symbol Layer)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (!isSymbolLayer) {
                    // Optional Row 0: Dedicated Top Number Row (`1 2 3 4 5 6 7 8 9 0`)
                    // When Flypy is composing (`di`), tapping `1` flushes 1st candidate (`第`) then commits `1` -> `第1`.
                    if (showNumberRow) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.82f),
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            NUM_ROW_1.forEach { digit ->
                                QwertyCharKey(
                                    label = digit,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Normal,
                                    cornerRadius = NUM_KEY_CORNER,
                                    verticalCellPad = NUM_ROW_VPAD,
                                    onPressPreviewStart = ::showPreviewForKey,
                                    onPressPreviewEnd = ::clearPreviewToken,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    onClick = {
                                        onCommitChar(digit)
                                    }
                                )
                            }
                        }
                    }

                    // Row 1: 10 uniform 1.0f cells (total = 10.0f) -> q w e r t y u i o p
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        ALPHA_ROW_1.forEach { ch ->
                            val display = if (isUpperCase) ch.uppercase() else ch
                            val secSpec = QwertySecondaryKeyMap.resolve(
                                key = ch,
                                isChinese = isFlypyChineseMode,
                                showNumberRow = showNumberRow
                            )
                            QwertyCharKey(
                                label = display,
                                subLabel = secSpec?.displayBadge,
                                allowSwipeUpSecondary = secSpec?.allowSwipeUp ?: false,
                                fontSize = letterFontSize,
                                fontWeight = letterFontWeight,
                                verticalCellPad = rowVPad,
                                onPressPreviewStart = ::showPreviewForKey,
                                onPressPreviewEnd = ::clearPreviewToken,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onClick = {
                                    onCommitChar(display)
                                    shiftState = ImeLogic.consumeShiftAfterCharCommit(shiftState)
                                },
                                onLongPress = if (secSpec != null) {
                                    { executeSecondarySpec(secSpec) }
                                } else {
                                    null
                                }
                            )
                        }
                    }

                    // Row 2: 9 uniform 1.0f visual keys centered with 0.5f edge-anchored outer hitboxes
                    // (`a` and `l` capture outer margin taps while keeping exact 1:1 visual width with Row 1!)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        ALPHA_ROW_2.forEachIndexed { idx, ch ->
                            val display = if (isUpperCase) ch.uppercase() else ch
                            val isFirst = idx == 0
                            val isLast = idx == ALPHA_ROW_2.lastIndex
                            val cellWeight = if (isFirst || isLast) 1.5f else 1f
                            val visualWidthFraction = if (isFirst || isLast) (1f / 1.5f) else 1f
                            val visualAlignment = when {
                                isFirst -> Alignment.CenterEnd
                                isLast -> Alignment.CenterStart
                                else -> Alignment.Center
                            }
                            val secSpec = QwertySecondaryKeyMap.resolve(
                                key = ch,
                                isChinese = isFlypyChineseMode,
                                showNumberRow = showNumberRow
                            )
                            QwertyCharKey(
                                label = display,
                                subLabel = secSpec?.displayBadge,
                                allowSwipeUpSecondary = secSpec?.allowSwipeUp ?: false,
                                fontSize = letterFontSize,
                                fontWeight = letterFontWeight,
                                verticalCellPad = rowVPad,
                                visualWidthFraction = visualWidthFraction,
                                visualAlignment = visualAlignment,
                                onPressPreviewStart = ::showPreviewForKey,
                                onPressPreviewEnd = ::clearPreviewToken,
                                modifier = Modifier
                                    .weight(cellWeight)
                                    .fillMaxHeight(),
                                onClick = {
                                    onCommitChar(display)
                                    shiftState = ImeLogic.consumeShiftAfterCharCommit(shiftState)
                                },
                                onLongPress = if (secSpec != null) {
                                    { executeSecondarySpec(secSpec) }
                                } else {
                                    null
                                }
                            )
                        }
                    }

                    // Row 3: 1.5f Shift/清码 + 7 × 1.0f letters + 1.5f Backspace (total = 10.0f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        QwertyShiftKey(
                            shiftState = shiftState,
                            isClearCodeMode = hasActiveFlypyComposing,
                            verticalCellPad = rowVPad,
                            modifier = Modifier
                                .weight(1.5f)
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
                            val secSpec = QwertySecondaryKeyMap.resolve(
                                key = ch,
                                isChinese = isFlypyChineseMode,
                                showNumberRow = showNumberRow
                            )
                            QwertyCharKey(
                                label = display,
                                subLabel = secSpec?.displayBadge,
                                allowSwipeUpSecondary = secSpec?.allowSwipeUp ?: false,
                                fontSize = letterFontSize,
                                fontWeight = letterFontWeight,
                                verticalCellPad = rowVPad,
                                onPressPreviewStart = ::showPreviewForKey,
                                onPressPreviewEnd = ::clearPreviewToken,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onClick = {
                                    onCommitChar(display)
                                    shiftState = ImeLogic.consumeShiftAfterCharCommit(shiftState)
                                },
                                onLongPress = if (secSpec != null) {
                                    { executeSecondarySpec(secSpec) }
                                } else {
                                    null
                                }
                            )
                        }

                        QwertyRepeatingBackspaceKey(
                            onBackspace = onBackspace,
                            onSwipeUpUndo = onSwipeUpBackspaceUndo,
                            verticalCellPad = rowVPad,
                            modifier = Modifier
                                .weight(1.5f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    val row1 = if (isMoreSymbols) SYM_ROW_1 else NUM_ROW_1
                    val row2 = if (isMoreSymbols) SYM_ROW_2 else NUM_ROW_2
                    val row3 = if (isMoreSymbols) SYM_ROW_3 else NUM_ROW_3

                    // Symbol Row 1 (10 × 1.0f = 10.0f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        row1.forEach { sym ->
                            QwertyCharKey(
                                label = sym,
                                fontSize = 17.5.sp,
                                fontWeight = FontWeight.Normal,
                                verticalCellPad = rowVPad,
                                onPressPreviewStart = ::showPreviewForKey,
                                onPressPreviewEnd = ::clearPreviewToken,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onClick = { onCommitChar(sym) }
                            )
                        }
                    }

                    // Symbol Row 2 (10 × 1.0f = 10.0f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        row2.forEach { sym ->
                            QwertyCharKey(
                                label = sym,
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.Normal,
                                verticalCellPad = rowVPad,
                                onPressPreviewStart = ::showPreviewForKey,
                                onPressPreviewEnd = ::clearPreviewToken,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onClick = { onCommitChar(sym) }
                            )
                        }
                    }

                    // Symbol Row 3: 1.5f (#+= / 123) + 7 × 1.0f punctuation + 1.5f ⌫ (total = 10.0f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        QwertyFunctionKey(
                            label = if (isMoreSymbols) "123" else "#+=",
                            verticalCellPad = rowVPad,
                            modifier = Modifier
                                .weight(1.5f)
                                .fillMaxHeight(),
                            onClick = { isMoreSymbols = !isMoreSymbols }
                        )

                        row3.forEach { sym ->
                            QwertyCharKey(
                                label = sym,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Normal,
                                verticalCellPad = rowVPad,
                                onPressPreviewStart = ::showPreviewForKey,
                                onPressPreviewEnd = ::clearPreviewToken,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onClick = { onCommitChar(sym) }
                            )
                        }

                        QwertyRepeatingBackspaceKey(
                            onBackspace = onBackspace,
                            onSwipeUpUndo = onSwipeUpBackspaceUndo,
                            verticalCellPad = rowVPad,
                            modifier = Modifier
                                .weight(1.5f)
                                .fillMaxHeight()
                        )
                    }
                }

                // Row 4 (total = 1.35 + 1.00 + 1.00 + 3.95 + 1.00 + 1.70 = 10.00f):
                // 123/ABC | 🌐 | , (次选2) | Space (小鹤音形 / English, 长按切中英) | . (三选3) | Enter/Action
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.04f),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    QwertyFunctionKey(
                        label = if (isSymbolLayer) "ABC" else "123",
                        verticalCellPad = rowVPad,
                        modifier = Modifier
                            .weight(1.35f)
                            .fillMaxHeight(),
                        onClick = {
                            if (!isSymbolLayer && hasActiveFlypyComposing) {
                                onFlushComposingBeforeLayerSwitch()
                            }
                            isSymbolLayer = !isSymbolLayer
                            isMoreSymbols = false
                        }
                    )

                    // [中/英] Language Toggle Key: tap = toggle 中文 <-> EN, long-press = system IME picker
                    val langInteraction = remember { MutableInteractionSource() }
                    val langPressed by langInteraction.collectIsPressedAsState()
                    val effectiveChinese = isFlypyChineseMode && !isSymbolLayer
                    Box(
                        modifier = Modifier
                            .weight(1.00f)
                            .fillMaxHeight()
                            .combinedClickable(
                                interactionSource = langInteraction,
                                indication = null,
                                onClick = onToggleFlypyLanguageMode,
                                onLongClick = onSwitchSystemIme
                            )
                            .padding(horizontal = KEY_CELL_HPAD, vertical = rowVPad)
                    ) {
                        TactileKeycapSurface(
                            isPressed = langPressed,
                            cornerRadius = FUNC_KEY_CORNER,
                            faceColor = if (langPressed) palette.keyBorder else palette.keyCapSecondaryBg,
                            borderColor = palette.keyBorder.copy(alpha = 0.65f),
                            shadowColor = palette.keyShadow
                        ) {
                            Text(
                                text = if (effectiveChinese) "中" else "EN",
                                color = palette.keyTextPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(x = (-2).dp, y = (-1).dp)
                            )
                            Text(
                                text = if (effectiveChinese) "英" else "中",
                                color = palette.keyTextMuted.copy(alpha = 0.62f),
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(bottom = 2.dp, end = 4.dp)
                            )
                        }
                    }

                    val showSecondBadge = hasActiveFlypyComposing && commaPeriodQuickSelect && flypyCandidateCount >= 2
                    val showThirdBadge = hasActiveFlypyComposing && commaPeriodQuickSelect && flypyCandidateCount >= 3

                    QwertyCharKey(
                        label = if (isFlypyChineseMode && !isSymbolLayer) "，" else ",",
                        subLabel = if (showSecondBadge) "2" else if (isFlypyChineseMode && !hasActiveFlypyComposing) "," else null,
                        highlightSubLabel = showSecondBadge,
                        allowSwipeUpSecondary = isFlypyChineseMode && !hasActiveFlypyComposing,
                        fontSize = 17.5.sp,
                        fontWeight = FontWeight.Medium,
                        cornerRadius = FUNC_KEY_CORNER,
                        verticalCellPad = rowVPad,
                        modifier = Modifier
                            .weight(1.00f)
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

                    // Wide Space Bar: Tap commits 1st candidate / space, Hold & Slide horizontally moves cursor
                    QwertySpaceBarKey(
                        isFlypyChineseMode = isFlypyChineseMode && !isSymbolLayer,
                        activeInputScheme = activeInputScheme,
                        hasComposing = hasActiveFlypyComposing,
                        verticalCellPad = rowVPad,
                        onTap = onInsertSpace,
                        onMoveCursorByStep = onMoveCursorByStep,
                        modifier = Modifier
                            .weight(3.95f)
                            .fillMaxHeight()
                    )

                    QwertyCharKey(
                        label = if (isFlypyChineseMode && !isSymbolLayer) "。" else ".",
                        subLabel = if (showThirdBadge) "3" else if (isFlypyChineseMode && !hasActiveFlypyComposing) "." else null,
                        highlightSubLabel = showThirdBadge,
                        allowSwipeUpSecondary = isFlypyChineseMode && !hasActiveFlypyComposing,
                        fontSize = 17.5.sp,
                        fontWeight = FontWeight.Medium,
                        cornerRadius = FUNC_KEY_CORNER,
                        verticalCellPad = rowVPad,
                        modifier = Modifier
                            .weight(1.00f)
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
                    val enterBg = when {
                        isPrimaryAction && enterPressed -> palette.primaryMicSolid.copy(alpha = 0.86f)
                        isPrimaryAction -> palette.primaryMicSolid
                        enterPressed -> palette.keyBorder
                        else -> palette.keyCapSecondaryBg
                    }
                    val enterContentColor = if (isPrimaryAction) palette.onPrimaryMic else palette.keyTextPrimary

                    Box(
                        modifier = Modifier
                            .weight(1.70f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = enterInteraction,
                                indication = null,
                                onClick = onEnterOrNewline
                            )
                            .padding(horizontal = KEY_CELL_HPAD, vertical = rowVPad)
                    ) {
                        TactileKeycapSurface(
                            isPressed = enterPressed,
                            cornerRadius = FUNC_KEY_CORNER,
                            faceColor = enterBg,
                            borderColor = if (isPrimaryAction) Color.Transparent else palette.keyBorder.copy(alpha = 0.65f),
                            shadowColor = palette.keyShadow
                        ) {
                            if (hasActiveFlypyComposing) {
                                Text(
                                    text = "编码",
                                    color = enterContentColor,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            } else {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(horizontal = 4.dp),
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

            // Unified 6.dp bottom anti-mistouch margin for both 4-row (252.dp) and 5-row (294.dp) modes,
            // releasing the full 210.dp / 252.dp key area to large tactile keycaps without a fake bottom tray.
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
        }

        // Non-clipping Floating Key-Press Preview Magnifier Bubble (Gboard / iOS callout style)
        val preview = activeKeyPreview
        if (showKeyPopupPreview && preview != null && rootSizePx.width > 0) {
            QwertyKeyMagnifierBubble(
                preview = preview,
                rootWidthPx = rootSizePx.width
            )
        }
    }
}

/**
 * Tactile 3D-rimmed keycap surface modeled after Gboard Material 3, iOS System Keyboard,
 * and WeType: renders a crisp bottom shadow ledge ([KEY_BOTTOM_RIM]) that physically
 * depresses downward on finger press instead of shrinking inward.
 */
@Composable
private fun TactileKeycapSurface(
    isPressed: Boolean,
    cornerRadius: Dp,
    faceColor: Color,
    borderColor: Color,
    shadowColor: Color,
    borderWidth: Dp = 0.65.dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val density = LocalDensity.current
    val pressTranslatePx = with(density) { 1.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = if (isPressed) pressTranslatePx else 0f
            }
            .clip(shape)
            .background(if (isPressed) Color.Transparent else shadowColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (isPressed) 0.dp else KEY_BOTTOM_RIM)
                .clip(shape)
                .background(faceColor)
                .then(
                    if (borderColor != Color.Transparent) {
                        Modifier.border(borderWidth, borderColor, shape)
                    } else {
                        Modifier
                    }
                ),
            content = content
        )
    }
}

@Composable
private fun QwertyKeyMagnifierBubble(
    preview: PressedKeyPreview,
    rootWidthPx: Int
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val density = LocalDensity.current
    val bubbleWidthDp = 46.dp
    val bubbleHeightDp = 48.dp
    val bubbleWidthPx = with(density) { bubbleWidthDp.toPx() }
    val bubbleHeightPx = with(density) { bubbleHeightDp.toPx() }
    val overlapPx = with(density) { 6.dp.toPx() }
    val edgeMarginPx = with(density) { 2.dp.roundToPx() }
    val minTopYInRootPx = with(density) { (-41).dp.roundToPx() }

    val maxX = (rootWidthPx - bubbleWidthPx.roundToInt() - edgeMarginPx).coerceAtLeast(edgeMarginPx)
    val offsetX = (preview.centerXInRootPx - bubbleWidthPx / 2f).roundToInt().coerceIn(edgeMarginPx, maxX)
    val offsetY = (preview.keyTopInRootPx - bubbleHeightPx + overlapPx).roundToInt()
        .coerceAtLeast(minTopYInRootPx)

    val bubbleShape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .zIndex(12f)
            .offset { IntOffset(offsetX, offsetY) }
            .size(width = bubbleWidthDp, height = bubbleHeightDp)
            .shadow(elevation = 6.dp, shape = bubbleShape, clip = false)
            .clip(bubbleShape)
            .background(palette.keyPopupBg)
            .border(0.85.dp, palette.keyBorder, bubbleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = preview.label,
            color = palette.keyTextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun QwertyCharKey(
    label: String,
    modifier: Modifier = Modifier,
    subLabel: String? = null,
    highlightSubLabel: Boolean = false,
    allowSwipeUpSecondary: Boolean = false,
    fontSize: TextUnit = 20.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    cornerRadius: Dp = ALPHA_KEY_CORNER,
    verticalCellPad: Dp = KEY_CELL_VPAD_5ROW,
    visualWidthFraction: Float = 1f,
    visualAlignment: Alignment = Alignment.Center,
    onPressPreviewStart: ((String, Offset, IntSize) -> Long)? = null,
    onPressPreviewEnd: ((Long) -> Unit)? = null,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val density = LocalDensity.current
    val touchSlopPx = with(density) { 28.dp.toPx() }
    val swipeUpThresholdPx = with(density) { ImeLogic.SECONDARY_KEY_SWIPE_UP_DP.dp.toPx() }

    val currentLabel by rememberUpdatedState(label)
    val currentSubLabel by rememberUpdatedState(subLabel)
    val currentAllowSwipeUp by rememberUpdatedState(allowSwipeUpSecondary)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnPreviewStart by rememberUpdatedState(onPressPreviewStart)
    val currentOnPreviewEnd by rememberUpdatedState(onPressPreviewEnd)

    var isPressed by remember { mutableStateOf(false) }
    var keyCapOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    var keyCapSizePx by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                coroutineScope {
                    val outerScope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val startPos = down.position
                        isPressed = true
                        var previewToken = currentOnPreviewStart?.invoke(
                            currentLabel,
                            keyCapOriginInWindow,
                            keyCapSizePx
                        ) ?: 0L
                        var secondaryFired = false
                        var cancelled = false

                        val longJob: Job? = if (currentOnLongPress != null) {
                            outerScope.launch {
                                delay(KEY_LONG_PRESS_MS)
                                if (!cancelled && !secondaryFired) {
                                    secondaryFired = true
                                    currentOnPreviewEnd?.invoke(previewToken)
                                    val badge = currentSubLabel
                                    if (!badge.isNullOrEmpty()) {
                                        previewToken = currentOnPreviewStart?.invoke(
                                            badge,
                                            keyCapOriginInWindow,
                                            keyCapSizePx
                                        ) ?: 0L
                                    }
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
                                val dx = pos.x - startPos.x
                                val dy = pos.y - startPos.y

                                // Swipe-up quick secondary trigger (>= 18.dp upward when allowSwipeUpSecondary is true)
                                if (
                                    !secondaryFired &&
                                    !cancelled &&
                                    currentAllowSwipeUp &&
                                    currentOnLongPress != null &&
                                    dy <= -swipeUpThresholdPx &&
                                    abs(dy) > abs(dx)
                                ) {
                                    secondaryFired = true
                                    longJob?.cancel()
                                    change.consume()
                                    currentOnPreviewEnd?.invoke(previewToken)
                                    currentOnLongPress?.invoke()
                                } else if (
                                    !secondaryFired &&
                                    (pos.x < -touchSlopPx ||
                                        pos.x > size.width + touchSlopPx ||
                                        pos.y < -touchSlopPx ||
                                        pos.y > size.height + touchSlopPx)
                                ) {
                                    cancelled = true
                                    longJob?.cancel()
                                    isPressed = false
                                    currentOnPreviewEnd?.invoke(previewToken)
                                }
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                            }
                        } finally {
                            isPressed = false
                            longJob?.cancel()
                            currentOnPreviewEnd?.invoke(previewToken)
                        }

                        if (!cancelled && !secondaryFired) {
                            currentOnClick()
                        }
                    }
                }
            },
        contentAlignment = visualAlignment
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(visualWidthFraction)
                .padding(horizontal = KEY_CELL_HPAD, vertical = verticalCellPad)
                .onGloballyPositioned { coords ->
                    keyCapOriginInWindow = coords.positionInWindow()
                    keyCapSizePx = coords.size
                }
        ) {
            TactileKeycapSurface(
                isPressed = isPressed,
                cornerRadius = cornerRadius,
                faceColor = if (isPressed) palette.keyCapSecondaryBg else palette.keyCapBg,
                borderColor = palette.keyBorder.copy(alpha = if (palette.isDark) 0.58f else 0.50f),
                shadowColor = palette.keyShadow
            ) {
                if (!subLabel.isNullOrEmpty()) {
                    Text(
                        text = subLabel,
                        color = if (highlightSubLabel) {
                            palette.keyTextPrimary
                        } else {
                            palette.keyTextMuted.copy(alpha = 0.68f)
                        },
                        fontSize = if (highlightSubLabel) 9.5.sp else 8.sp,
                        fontWeight = if (highlightSubLabel) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 1.5.dp, end = 3.dp)
                    )
                }
                val opticalOffsetX = if (label == "，" || label == "。") 3.5.dp else 0.dp
                val opticalOffsetY = if (label == "，" || label == "。") (-1).dp else 0.dp
                Text(
                    text = label,
                    color = palette.keyTextPrimary,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = opticalOffsetX, y = opticalOffsetY)
                )
            }
        }
    }
}

@Composable
private fun QwertySpaceBarKey(
    isFlypyChineseMode: Boolean,
    activeInputScheme: InputScheme = InputScheme.FLYPY_TABLE,
    hasComposing: Boolean,
    verticalCellPad: Dp = KEY_CELL_VPAD_5ROW,
    onTap: () -> Unit,
    onMoveCursorByStep: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val density = LocalDensity.current
    val touchSlopPx = with(density) { 28.dp.toPx() }
    val slideActivatePx = with(density) { ImeLogic.SPACE_CURSOR_SLIDE_ACTIVATE_DP.dp.toPx() }
    val slideStepPx = with(density) { ImeLogic.SPACE_CURSOR_SLIDE_STEP_DP.dp.toPx() }

    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnMoveCursor by rememberUpdatedState(onMoveCursorByStep)
    var isPressed by remember { mutableStateOf(false) }
    var isCursorSliding by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val startX = down.position.x
                    var lastX = startX
                    var accumulatedDx = 0f
                    var slidingActive = false
                    var cancelled = false
                    isPressed = true
                    isCursorSliding = false

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                cancelled = true
                                break
                            }
                            val pos = change.position
                            val totalDx = pos.x - startX
                            val frameDx = pos.x - lastX
                            lastX = pos.x

                            if (!slidingActive && abs(totalDx) >= slideActivatePx) {
                                slidingActive = true
                                isCursorSliding = true
                                accumulatedDx = totalDx
                            } else if (slidingActive) {
                                accumulatedDx += frameDx
                            }

                            if (slidingActive) {
                                change.consume()
                                val (steps, rem) = ImeLogic.computeSpaceCursorSlideSteps(
                                    accumulatedDxPx = accumulatedDx,
                                    stepPx = slideStepPx
                                )
                                if (steps != 0) {
                                    accumulatedDx = rem
                                    currentOnMoveCursor(steps)
                                }
                            } else if (
                                pos.y < -touchSlopPx ||
                                pos.y > size.height + touchSlopPx
                            ) {
                                cancelled = true
                                isPressed = false
                            }

                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isPressed = false
                        isCursorSliding = false
                    }

                    if (!cancelled && !slidingActive) {
                        currentOnTap()
                    }
                }
            }
            .padding(horizontal = KEY_CELL_HPAD, vertical = verticalCellPad)
    ) {
        TactileKeycapSurface(
            isPressed = isPressed,
            cornerRadius = FUNC_KEY_CORNER,
            faceColor = if (isPressed) palette.keyCapSecondaryBg else palette.keyCapBg,
            borderColor = palette.keyBorder.copy(alpha = if (palette.isDark) 0.58f else 0.50f),
            shadowColor = palette.keyShadow
        ) {
            val label = when {
                isCursorSliding -> "‹ 滑动移光标 ›"
                hasComposing -> "空格首选"
                isFlypyChineseMode -> shortInputSchemeLabel(activeInputScheme)
                else -> "English"
            }
            Text(
                text = label,
                color = if (hasComposing || isCursorSliding) palette.keyTextPrimary else palette.keyTextMuted,
                fontSize = 12.sp,
                fontWeight = if (hasComposing || isCursorSliding) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun QwertyFunctionKey(
    label: String,
    verticalCellPad: Dp = KEY_CELL_VPAD_5ROW,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = KEY_CELL_HPAD, vertical = verticalCellPad)
    ) {
        TactileKeycapSurface(
            isPressed = isPressed,
            cornerRadius = FUNC_KEY_CORNER,
            faceColor = if (isPressed) palette.keyBorder else palette.keyCapSecondaryBg,
            borderColor = palette.keyBorder.copy(alpha = 0.65f),
            shadowColor = palette.keyShadow
        ) {
            Text(
                text = label,
                color = palette.keyTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QwertyShiftKey(
    shiftState: ShiftKeyState,
    isClearCodeMode: Boolean,
    verticalCellPad: Dp = KEY_CELL_VPAD_5ROW,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor = when {
        isClearCodeMode -> if (isPressed) palette.keyBorder else palette.keyCapSecondaryBg
        shiftState == ShiftKeyState.CAPS_LOCK -> palette.primaryMicSolid
        shiftState == ShiftKeyState.ONCE -> palette.keyCapBg
        isPressed -> palette.keyBorder
        else -> palette.keyCapSecondaryBg
    }
    val borderColor = when {
        isClearCodeMode -> palette.keyTextPrimary.copy(alpha = 0.38f)
        shiftState == ShiftKeyState.CAPS_LOCK -> Color.Transparent
        shiftState == ShiftKeyState.ONCE -> palette.keyTextPrimary.copy(alpha = 0.55f)
        else -> palette.keyBorder.copy(alpha = 0.65f)
    }
    val textColor = when {
        isClearCodeMode -> palette.keyTextPrimary
        shiftState == ShiftKeyState.CAPS_LOCK -> palette.onPrimaryMic
        else -> palette.keyTextPrimary
    }

    Box(
        modifier = modifier
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap,
                onLongClick = onLongPress
            )
            .padding(horizontal = KEY_CELL_HPAD, vertical = verticalCellPad)
    ) {
        TactileKeycapSurface(
            isPressed = isPressed,
            cornerRadius = FUNC_KEY_CORNER,
            faceColor = bgColor,
            borderColor = borderColor,
            shadowColor = palette.keyShadow,
            borderWidth = if (shiftState == ShiftKeyState.ONCE) 1.3.dp else 0.65.dp
        ) {
            if (isClearCodeMode) {
                Text(
                    text = "清码",
                    color = textColor,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                val shiftIcon = when (shiftState) {
                    ShiftKeyState.OFF -> TypeFreeIcons.ShiftOff
                    ShiftKeyState.ONCE -> TypeFreeIcons.ShiftOnce
                    ShiftKeyState.CAPS_LOCK -> TypeFreeIcons.ShiftCapsLock
                }
                Icon(
                    imageVector = shiftIcon,
                    contentDescription = "Shift 大小写切换",
                    tint = textColor,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(19.dp)
                )
            }
        }
    }
}

@Composable
private fun QwertyRepeatingBackspaceKey(
    onBackspace: () -> Unit,
    onSwipeUpUndo: () -> Unit,
    verticalCellPad: Dp = KEY_CELL_VPAD_5ROW,
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
            }
            .padding(horizontal = KEY_CELL_HPAD, vertical = verticalCellPad)
    ) {
        TactileKeycapSurface(
            isPressed = isHolding,
            cornerRadius = FUNC_KEY_CORNER,
            faceColor = if (isHolding) palette.keyBorder else palette.keyCapSecondaryBg,
            borderColor = palette.keyBorder.copy(alpha = 0.65f),
            shadowColor = palette.keyShadow
        ) {
            Text(
                text = "撤",
                color = palette.keyTextMuted.copy(alpha = 0.56f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 1.5.dp, end = 3.5.dp)
            )
            Icon(
                imageVector = TypeFreeIcons.Backspace,
                contentDescription = "退格删除（上划撤回上屏）",
                tint = palette.keyTextPrimary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(19.dp)
            )
        }
    }
}
