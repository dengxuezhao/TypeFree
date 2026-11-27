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
import androidx.compose.foundation.interaction.PressInteraction
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
import android.view.HapticFeedbackConstants
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.typefree.ime.data.DoublePinyinLayout
import com.typefree.ime.data.ExpressionTab
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeycapBadgeMode
import com.typefree.ime.data.SpaceLongPressAction
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
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
private val NUM_ROW_2 = listOf("@", "#", "$", "%", "*", "-", "+", "(", ")", "/")
private val NUM_ROW_3 = listOf(";", "=", "？", "！", ":", "\"", "'")

private val SYM_ROW_1 = listOf("[", "]", "{", "}", "<", ">", "^", "&", "=", "~")
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

object BuiltInQwertyHelper {
    /**
     * Returns `true` when Row 2 should expand from 9 keys (`A..L`) to 10 uniform keys (`A..L ;`),
     * matching PC Shuangpin muscle memory for layouts that map rhyme `ing` / `uai` onto `;`
     * (Microsoft, Sogou, and Ziguang Shuangpin) while automatically restoring standard 26-key
     * layout in English mode or symbol layers.
     */
    fun shouldShowSemicolonKey(
        isSymbolLayer: Boolean,
        isFlypyChineseMode: Boolean,
        activeInputScheme: InputScheme,
        doublePinyinLayout: DoublePinyinLayout
    ): Boolean {
        return !isSymbolLayer &&
            isFlypyChineseMode &&
            activeInputScheme == InputScheme.RIME_DOUBLE_PINYIN_FLYPY &&
            doublePinyinLayout.usesSemicolonKey
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BuiltInQwertyKeyboard(
    enterActionInfo: EnterActionInfo,
    isFlypyChineseMode: Boolean,
    activeInputScheme: InputScheme = InputScheme.FLYPY_TABLE,
    doublePinyinLayout: DoublePinyinLayout = DoublePinyinLayout.FLYPY,
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
    backspaceSwipeUpClearEnabled: Boolean = false,
    onSwipeUpBackspaceClear: () -> Unit = {},
    onEditorShortcutAction: (EditorShortcutAction) -> Unit,
    onEnterOrNewline: () -> Unit,
    onSwitchToVoice: () -> Unit,
    onSwitchSystemIme: () -> Unit,
    onHideKeyboard: () -> Unit = {},
    onShowImePicker: () -> Unit = {},
    showNumberRow: Boolean = false,
    inputSessionToken: Int = 0,
    showKeyPopupPreview: Boolean = true,
    keyGestureProfile: KeyGestureProfile? = null,
    onTriggerKeyGesture: ((KeyGestureBinding) -> Unit)? = null,
    fiveKeyBottomRow: Boolean = false,
    onSwipeUpPeriodSlash: (() -> Unit)? = null,
    keycapBadgeMode: KeycapBadgeMode = KeycapBadgeMode.SWIPE_UP,
    autoCapShift: Boolean = false,
    customSchemaDisplayName: String? = null,
    spaceLongPressAction: SpaceLongPressAction = SpaceLongPressAction.VOICE_HOLD,
    isInPlaceVoiceActive: Boolean = false,
    onHoldStartInPlaceVoice: () -> Unit = {},
    onHoldReleaseInPlaceVoice: () -> Unit = {},
    onOpenExpressionPanel: (ExpressionTab) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val appLang = LocalAppLanguage.current
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val hostView = LocalView.current

    var isSymbolLayer by remember { mutableStateOf(false) }
    var isMoreSymbols by remember { mutableStateOf(false) }
    var shiftState by remember {
        mutableStateOf(
            if (!isFlypyChineseMode && autoCapShift) ShiftKeyState.ONCE else ShiftKeyState.OFF
        )
    }
    var shiftSetByAutoCap by remember { mutableStateOf(!isFlypyChineseMode && autoCapShift) }
    var lastShiftTapMs by remember { mutableLongStateOf(0L) }

    var rootOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    var rootSizePx by remember { mutableStateOf(IntSize.Zero) }
    var previewSeq by remember { mutableLongStateOf(0L) }
    var activeKeyPreview by remember { mutableStateOf<PressedKeyPreview?>(null) }

    LaunchedEffect(inputSessionToken) {
        isSymbolLayer = false
        isMoreSymbols = false
        if (!isFlypyChineseMode && autoCapShift) {
            shiftState = ShiftKeyState.ONCE
            shiftSetByAutoCap = true
        } else if (shiftState == ShiftKeyState.ONCE) {
            shiftState = ShiftKeyState.OFF
            shiftSetByAutoCap = false
        }
        activeKeyPreview = null
    }

    LaunchedEffect(autoCapShift, isFlypyChineseMode) {
        if (!isFlypyChineseMode && autoCapShift) {
            if (shiftState == ShiftKeyState.OFF) {
                shiftState = ShiftKeyState.ONCE
                shiftSetByAutoCap = true
            }
        } else if (shiftState == ShiftKeyState.ONCE && shiftSetByAutoCap) {
            shiftState = ShiftKeyState.OFF
            shiftSetByAutoCap = false
        }
    }

    val isUpperCase = shiftState != ShiftKeyState.OFF
    val showUppercaseKeycap = isFlypyChineseMode || isUpperCase
    val hasActiveFlypyComposing = isFlypyChineseMode && flypyComposingCode.isNotEmpty()
    val hasFiveRows = !isSymbolLayer && showNumberRow
    val rowVPad = if (hasFiveRows) KEY_CELL_VPAD_5ROW else KEY_CELL_VPAD_4ROW
    val letterFontSize = ((if (showUppercaseKeycap) 18.5f else 20.5f) * metrics.keyFontScale).sp
    val letterFontWeight = if (showUppercaseKeycap) FontWeight.Medium else FontWeight.Normal

    val effectiveGestureProfile = keyGestureProfile ?: remember(isFlypyChineseMode, showNumberRow) {
        KeyGestureConfig.defaultProfile(
            isChinese = isFlypyChineseMode,
            showNumberRow = showNumberRow
        )
    }

    fun executeKeyGestureBinding(binding: KeyGestureBinding) {
        if (!binding.isEnabled) return
        if (onTriggerKeyGesture != null) {
            onTriggerKeyGesture(binding)
            return
        }
        when (binding.type) {
            KeyGestureActionType.NONE -> Unit
            KeyGestureActionType.COMMIT_TEXT -> if (binding.text.isNotEmpty()) {
                onCommitSecondarySymbol(binding.text)
            }
            KeyGestureActionType.COMMIT_PAIRED -> onInsertPairedSymbol(binding.text, binding.rightText)
            KeyGestureActionType.UNDO_LAST -> onSwipeUpBackspaceUndo()
            KeyGestureActionType.REPEAT_LAST -> Unit
            KeyGestureActionType.EDITOR_SHORTCUT -> binding.editorShortcut?.let(onEditorShortcutAction)
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

                    @Composable
                    fun AlphaLetterKey(
                        ch: String,
                        modifier: Modifier,
                        visualWidthFraction: Float = 1f,
                        visualAlignment: Alignment = Alignment.Center
                    ) {
                        // Chinese mode shows uppercase keycaps (standard desktop/mobile IME convention) while
                        // still committing lowercase code unless Shift is active; English mode follows Shift state.
                        val keycapDisplay = if (showUppercaseKeycap) ch.uppercase() else ch
                        val commitText = if (isUpperCase) ch.uppercase() else ch
                        val keyChar = ch.first()
                        val swipeBinding = effectiveGestureProfile.resolveSwipeUp(keyChar)
                        val longBinding = effectiveGestureProfile.resolveLongPress(keyChar)
                        val visibleCapBadge = when (keycapBadgeMode) {
                            KeycapBadgeMode.SWIPE_UP -> swipeBinding?.compactKeycapBadge()?.ifEmpty { null }
                            KeycapBadgeMode.LONG_PRESS -> longBinding?.compactKeycapBadge()?.ifEmpty { null }
                            KeycapBadgeMode.NONE -> null
                        }
                        QwertyCharKey(
                            label = keycapDisplay,
                            swipeUpBadge = visibleCapBadge,
                            swipeUpPreviewBadge = swipeBinding?.compactKeycapBadge()?.ifEmpty { null },
                            longPressBadge = longBinding?.compactKeycapBadge()?.ifEmpty { null },
                            topCenterBadge = true,
                            fontSize = letterFontSize,
                            fontWeight = letterFontWeight,
                            verticalCellPad = rowVPad,
                            visualWidthFraction = visualWidthFraction,
                            visualAlignment = visualAlignment,
                            onPressPreviewStart = ::showPreviewForKey,
                            onPressPreviewEnd = ::clearPreviewToken,
                            modifier = modifier,
                            onClick = {
                                onCommitChar(commitText)
                                shiftState = ImeLogic.consumeShiftAfterCharCommit(shiftState)
                                shiftSetByAutoCap = false
                            },
                            onSwipeUp = if (swipeBinding != null) {
                                { executeKeyGestureBinding(swipeBinding) }
                            } else {
                                null
                            },
                            onLongPress = if (longBinding != null) {
                                { executeKeyGestureBinding(longBinding) }
                            } else {
                                null
                            }
                        )
                    }

                    // Row 1: 10 uniform 1.0f cells (total = 10.0f) -> q w e r t y u i o p
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        ALPHA_ROW_1.forEach { ch ->
                            AlphaLetterKey(
                                ch = ch,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }

                    val showSemicolonKey = BuiltInQwertyHelper.shouldShowSemicolonKey(
                        isSymbolLayer = isSymbolLayer,
                        isFlypyChineseMode = isFlypyChineseMode,
                        activeInputScheme = activeInputScheme,
                        doublePinyinLayout = doublePinyinLayout
                    )

                    // Row 2:
                    // - Standard 26-key: 9 uniform 1.0f visual keys centered with 0.5f edge-anchored outer hitboxes
                    // - Semicolon Shuangpin (MSPY / Sogou / Ziguang in Chinese mode): 10 uniform 1.0f keys (`a..l ;`)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        if (showSemicolonKey) {
                            ALPHA_ROW_2.forEach { ch ->
                                AlphaLetterKey(
                                    ch = ch,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }
                            val semiBadge = when (keycapBadgeMode) {
                                KeycapBadgeMode.SWIPE_UP -> "："
                                KeycapBadgeMode.LONG_PRESS -> "；"
                                KeycapBadgeMode.NONE -> null
                            }
                            QwertyCharKey(
                                label = ";",
                                swipeUpBadge = semiBadge,
                                swipeUpPreviewBadge = "：",
                                longPressBadge = "；",
                                topCenterBadge = true,
                                fontSize = letterFontSize,
                                fontWeight = letterFontWeight,
                                verticalCellPad = rowVPad,
                                onPressPreviewStart = ::showPreviewForKey,
                                onPressPreviewEnd = ::clearPreviewToken,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onClick = { onCommitChar(";") },
                                onSwipeUp = { onCommitSecondarySymbol("：") },
                                onLongPress = { onCommitSecondarySymbol("；") }
                            )
                        } else {
                            ALPHA_ROW_2.forEachIndexed { idx, ch ->
                                val isFirst = idx == 0
                                val isLast = idx == ALPHA_ROW_2.lastIndex
                                val cellWeight = if (isFirst || isLast) 1.5f else 1f
                                val visualWidthFraction = if (isFirst || isLast) (1f / 1.5f) else 1f
                                val visualAlignment = when {
                                    isFirst -> Alignment.CenterEnd
                                    isLast -> Alignment.CenterStart
                                    else -> Alignment.Center
                                }
                                AlphaLetterKey(
                                    ch = ch,
                                    visualWidthFraction = visualWidthFraction,
                                    visualAlignment = visualAlignment,
                                    modifier = Modifier
                                        .weight(cellWeight)
                                        .fillMaxHeight()
                                )
                            }
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
                                } else if (
                                    ImeLogic.shouldFiveKeyShiftTapToggleLanguage(
                                        fiveKeyBottomRow = fiveKeyBottomRow,
                                        isFlypyChineseMode = isFlypyChineseMode,
                                        hasActiveFlypyComposing = hasActiveFlypyComposing,
                                        isSymbolLayer = isSymbolLayer
                                    )
                                ) {
                                    onToggleFlypyLanguageMode()
                                } else {
                                    val now = System.currentTimeMillis()
                                    val delta = if (lastShiftTapMs > 0L) now - lastShiftTapMs else Long.MAX_VALUE
                                    shiftState = ImeLogic.nextShiftState(shiftState, delta)
                                    shiftSetByAutoCap = false
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
                                    shiftSetByAutoCap = false
                                    lastShiftTapMs = 0L
                                }
                            }
                        )

                        ALPHA_ROW_3.forEach { ch ->
                            AlphaLetterKey(
                                ch = ch,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }

                        QwertyRepeatingBackspaceKey(
                            onBackspace = onBackspace,
                            onSwipeUpAction = if (backspaceSwipeUpClearEnabled) onSwipeUpBackspaceClear else onSwipeUpBackspaceUndo,
                            swipeUpBadge = if (backspaceSwipeUpClearEnabled) tr("清", "Clr", "清") else tr("撤", "Undo", "撤"),
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
                    val handleSymbolClick: (String) -> Unit = { sym ->
                        onCommitSecondarySymbol(sym)
                    }

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
                                onClick = { handleSymbolClick(sym) }
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
                                onClick = { handleSymbolClick(sym) }
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
                                onClick = { handleSymbolClick(sym) }
                            )
                        }

                        QwertyRepeatingBackspaceKey(
                            onBackspace = onBackspace,
                            onSwipeUpAction = if (backspaceSwipeUpClearEnabled) onSwipeUpBackspaceClear else onSwipeUpBackspaceUndo,
                            swipeUpBadge = if (backspaceSwipeUpClearEnabled) tr("清", "Clr", "清") else tr("撤", "Undo", "撤"),
                            verticalCellPad = rowVPad,
                            modifier = Modifier
                                .weight(1.5f)
                                .fillMaxHeight()
                        )
                    }
                }

                // Row 4:
                // - 6-key default (total = 1.35 + 1.00 + 1.00 + 3.95 + 1.00 + 1.70 = 10.00f)
                // - 5-key codetable bottom row (total = 1.40 + 1.40 + 4.10 + 1.40 + 1.70 = 10.00f)
                val w123 = if (fiveKeyBottomRow) 1.40f else 1.35f
                val wComma = if (fiveKeyBottomRow) 1.40f else 1.00f
                val wSpace = if (fiveKeyBottomRow) 4.10f else 3.95f
                val wPeriod = if (fiveKeyBottomRow) 1.40f else 1.00f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.04f),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    QwertySymbolEmojiKey(
                        label = if (isSymbolLayer) "ABC" else "123",
                        topBadge = "↑☺",
                        verticalCellPad = rowVPad,
                        modifier = Modifier
                            .weight(w123)
                            .fillMaxHeight(),
                        onTap = {
                            if (!isSymbolLayer && hasActiveFlypyComposing) {
                                onFlushComposingBeforeLayerSwitch()
                            }
                            isSymbolLayer = !isSymbolLayer
                            isMoreSymbols = false
                        },
                        onOpenEmoji = {
                            if (hasActiveFlypyComposing) {
                                onFlushComposingBeforeLayerSwitch()
                            }
                            isSymbolLayer = false
                            isMoreSymbols = false
                            onOpenExpressionPanel(ExpressionTab.EMOJI)
                        }
                    )

                    if (!fiveKeyBottomRow) {
                        // [中/英] Language Toggle Key: tap = toggle 中文 <-> EN, long-press = system IME picker
                        val langInteraction = remember { MutableInteractionSource() }
                        val langPressed by langInteraction.collectIsPressedAsState()
                        LaunchedEffect(langInteraction) {
                            langInteraction.interactions.collect { interaction ->
                                if (interaction is PressInteraction.Press) {
                                    keyFeedback.onTouchFeedback(HapticTier.FUNCTION_KEY, hostView, KeySoundType.STANDARD, true)
                                }
                            }
                        }
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
                    }

                    val showSecondBadge = hasActiveFlypyComposing && commaPeriodQuickSelect && flypyCandidateCount >= 2
                    val showThirdBadge = hasActiveFlypyComposing && commaPeriodQuickSelect && flypyCandidateCount >= 3
                    val isIdleChineseComma = isFlypyChineseMode && !isSymbolLayer && !hasActiveFlypyComposing
                    val isIdlePeriod = !isSymbolLayer && !hasActiveFlypyComposing

                    QwertyCharKey(
                        label = if (isFlypyChineseMode && !isSymbolLayer) "，" else ",",
                        swipeUpBadge = when {
                            showSecondBadge -> "2"
                            isIdleChineseComma -> ";"
                            else -> null
                        },
                        longPressBadge = if (isIdleChineseComma) ";" else null,
                        highlightSwipeUpBadge = showSecondBadge,
                        fontSize = 17.5.sp,
                        fontWeight = FontWeight.Medium,
                        cornerRadius = FUNC_KEY_CORNER,
                        verticalCellPad = rowVPad,
                        modifier = Modifier
                            .weight(wComma)
                            .fillMaxHeight(),
                        onClick = {
                            if (isSymbolLayer) {
                                onCommitSecondarySymbol(",")
                            } else {
                                onCommaOrPeriodKey(true)
                            }
                        },
                        onSwipeUp = if (isIdleChineseComma) {
                            { onCommitChar(";") }
                        } else {
                            null
                        },
                        onLongPress = if (isIdleChineseComma) {
                            { onCommitChar(";") }
                        } else {
                            null
                        }
                    )

                    // Wide Space Bar: Tap commits 1st candidate / space, Hold & Slide horizontally moves cursor,
                    // Hold >= 240ms (VOICE_HOLD default) dictates in-place, or 420ms (TOGGLE_LANGUAGE) toggles 中文 <-> EN
                    QwertySpaceBarKey(
                        isFlypyChineseMode = isFlypyChineseMode && !isSymbolLayer,
                        activeInputScheme = activeInputScheme,
                        doublePinyinLayout = doublePinyinLayout,
                        customSchemaDisplayName = customSchemaDisplayName,
                        fiveKeyBottomRow = fiveKeyBottomRow && !isSymbolLayer,
                        hasComposing = hasActiveFlypyComposing,
                        spaceLongPressAction = spaceLongPressAction,
                        isInPlaceVoiceActive = isInPlaceVoiceActive,
                        verticalCellPad = rowVPad,
                        onTap = onInsertSpace,
                        onMoveCursorByStep = onMoveCursorByStep,
                        onLongPressToggleLanguage = onToggleFlypyLanguageMode,
                        onHoldStartInPlaceVoice = onHoldStartInPlaceVoice,
                        onHoldReleaseInPlaceVoice = onHoldReleaseInPlaceVoice,
                        modifier = Modifier
                            .weight(wSpace)
                            .fillMaxHeight()
                    )

                    QwertyCharKey(
                        label = if (isFlypyChineseMode && !isSymbolLayer) "。" else ".",
                        swipeUpBadge = when {
                            isSymbolLayer -> null
                            showThirdBadge -> "3"
                            isIdlePeriod && isFlypyChineseMode -> "/"
                            else -> null
                        },
                        longPressBadge = if (isIdlePeriod && isFlypyChineseMode) "." else null,
                        highlightSwipeUpBadge = showThirdBadge,
                        fontSize = 17.5.sp,
                        fontWeight = FontWeight.Medium,
                        cornerRadius = FUNC_KEY_CORNER,
                        verticalCellPad = rowVPad,
                        modifier = Modifier
                            .weight(wPeriod)
                            .fillMaxHeight(),
                        onClick = {
                            if (isSymbolLayer) {
                                onCommitSecondarySymbol(".")
                            } else {
                                onCommaOrPeriodKey(false)
                            }
                        },
                        onSwipeUp = if (isIdlePeriod) {
                            { onSwipeUpPeriodSlash?.invoke() ?: onCommitChar("/") }
                        } else {
                            null
                        },
                        onLongPress = if (isIdlePeriod && isFlypyChineseMode) {
                            { onCommitSecondarySymbol(".") }
                        } else {
                            null
                        }
                    )

                    // Adaptive Enter / Action Key (commits raw code when composing in Flypy!)
                    val isPrimaryAction = !hasActiveFlypyComposing && enterActionInfo.performEditorActionId != null
                    val enterInteraction = remember { MutableInteractionSource() }
                    val enterPressed by enterInteraction.collectIsPressedAsState()
                    LaunchedEffect(enterInteraction) {
                        enterInteraction.interactions.collect { interaction ->
                            if (interaction is PressInteraction.Press) {
                                keyFeedback.onTouchFeedback(HapticTier.FUNCTION_KEY, hostView, KeySoundType.RETURN, true)
                            }
                        }
                    }
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
                                    text = tr("编码", "Code", "編碼"),
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
                                    val showReturnIconOnly = !isPrimaryAction && enterActionInfo.label.equals("Return", ignoreCase = true)
                                    if (!isPrimaryAction) {
                                        Icon(
                                            imageVector = TypeFreeIcons.ReturnEnter,
                                            contentDescription = enterActionInfo.label,
                                            tint = enterContentColor,
                                            modifier = Modifier.size(if (showReturnIconOnly) 18.dp else 15.dp)
                                        )
                                    }
                                    if (!showReturnIconOnly && enterActionInfo.label.isNotBlank()) {
                                        Text(
                                            text = enterActionInfo.label,
                                            color = enterContentColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
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
    swipeUpBadge: String? = null,
    swipeUpPreviewBadge: String? = swipeUpBadge,
    longPressBadge: String? = null,
    highlightSwipeUpBadge: Boolean = false,
    topCenterBadge: Boolean = false,
    fontSize: TextUnit = 20.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    cornerRadius: Dp = ALPHA_KEY_CORNER,
    verticalCellPad: Dp = KEY_CELL_VPAD_5ROW,
    visualWidthFraction: Float = 1f,
    visualAlignment: Alignment = Alignment.Center,
    onPressPreviewStart: ((String, Offset, IntSize) -> Long)? = null,
    onPressPreviewEnd: ((Long) -> Unit)? = null,
    onClick: () -> Unit,
    onSwipeUp: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val density = LocalDensity.current
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val hostView = LocalView.current
    val touchSlopPx = with(density) { 28.dp.toPx() }
    val swipeUpThresholdPx = with(density) { ImeLogic.SECONDARY_KEY_SWIPE_UP_DP.dp.toPx() }

    val currentLabel by rememberUpdatedState(label)
    val currentSwipeUpBadge by rememberUpdatedState(swipeUpPreviewBadge)
    val currentLongPressBadge by rememberUpdatedState(longPressBadge)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnSwipeUp by rememberUpdatedState(onSwipeUp)
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
                        keyFeedback.onTouchFeedback(HapticTier.ALPHA_KEY, hostView, KeySoundType.STANDARD, true)
                        var previewToken = currentOnPreviewStart?.invoke(
                            currentLabel,
                            keyCapOriginInWindow,
                            keyCapSizePx
                        ) ?: 0L
                        var gestureFired = false
                        var cancelled = false

                        val longJob: Job? = if (currentOnLongPress != null) {
                            outerScope.launch {
                                delay(KEY_LONG_PRESS_MS)
                                if (!cancelled && !gestureFired) {
                                    gestureFired = true
                                    keyFeedback.onTouchFeedback(HapticTier.GESTURE_CONFIRM, hostView, KeySoundType.NONE, false)
                                    currentOnPreviewEnd?.invoke(previewToken)
                                    val badge = currentLongPressBadge
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

                                // Swipe-up trigger (>= 18.dp upward & vertical-dominant), cancels long-press timer
                                if (
                                    !gestureFired &&
                                    !cancelled &&
                                    currentOnSwipeUp != null &&
                                    dy <= -swipeUpThresholdPx &&
                                    abs(dy) > abs(dx)
                                ) {
                                    gestureFired = true
                                    longJob?.cancel()
                                    change.consume()
                                    keyFeedback.onTouchFeedback(HapticTier.GESTURE_CONFIRM, hostView, KeySoundType.NONE, false)
                                    currentOnPreviewEnd?.invoke(previewToken)
                                    val badge = currentSwipeUpBadge
                                    if (!badge.isNullOrEmpty()) {
                                        previewToken = currentOnPreviewStart?.invoke(
                                            badge,
                                            keyCapOriginInWindow,
                                            keyCapSizePx
                                        ) ?: 0L
                                    }
                                    currentOnSwipeUp?.invoke()
                                } else if (
                                    !gestureFired &&
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

                        if (!cancelled && !gestureFired) {
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
                val hasTopCenterBadge = topCenterBadge && !swipeUpBadge.isNullOrEmpty()
                if (!swipeUpBadge.isNullOrEmpty()) {
                    val isPairedWide = topCenterBadge && swipeUpBadge.length >= 2 &&
                        swipeUpBadge.any { it.code > 0x7F }
                    val badgeSp = when {
                        highlightSwipeUpBadge -> (10.8f * metrics.widthScale).sp
                        topCenterBadge -> (10.2f * metrics.widthScale).sp
                        else -> (9.0f * metrics.widthScale).sp
                    }
                    Text(
                        text = swipeUpBadge,
                        style = TextStyle(
                            color = if (highlightSwipeUpBadge) {
                                palette.keyTextPrimary
                            } else {
                                palette.keyTextMuted.copy(alpha = if (topCenterBadge) 0.86f else 0.74f)
                            },
                            fontSize = badgeSp,
                            lineHeight = (badgeSp.value + 1.5f).sp,
                            letterSpacing = if (isPairedWide) (-0.4).sp else 0.sp,
                            fontWeight = if (highlightSwipeUpBadge) FontWeight.Bold else FontWeight.SemiBold,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = if (topCenterBadge) {
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 2.dp, start = 1.dp, end = 1.dp)
                        } else {
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 1.5.dp, end = 2.5.dp)
                        }
                    )
                }
                val opticalOffsetX = if (label == "，" || label == "。") 3.5.dp else 0.dp
                val opticalOffsetY = when {
                    label == "，" || label == "。" -> (-1).dp
                    // Shift primary letter gently down ONLY when top-center badge is visible;
                    // restores exact center (`0.dp`) when badge is hidden!
                    hasTopCenterBadge -> 3.8.dp
                    else -> 0.dp
                }
                Text(
                    text = label,
                    style = TextStyle(
                        color = palette.keyTextPrimary,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
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
    doublePinyinLayout: DoublePinyinLayout = DoublePinyinLayout.FLYPY,
    customSchemaDisplayName: String? = null,
    fiveKeyBottomRow: Boolean = false,
    hasComposing: Boolean,
    spaceLongPressAction: SpaceLongPressAction = SpaceLongPressAction.VOICE_HOLD,
    isInPlaceVoiceActive: Boolean = false,
    verticalCellPad: Dp = KEY_CELL_VPAD_5ROW,
    onTap: () -> Unit,
    onMoveCursorByStep: (Int) -> Unit = {},
    onLongPressToggleLanguage: (() -> Unit)? = null,
    onHoldStartInPlaceVoice: (() -> Unit)? = null,
    onHoldReleaseInPlaceVoice: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val view = LocalView.current
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val density = LocalDensity.current
    val touchSlopPx = with(density) { 28.dp.toPx() }
    val slideActivatePx = with(density) { ImeLogic.SPACE_CURSOR_SLIDE_ACTIVATE_DP.dp.toPx() }
    val slideStepPx = with(density) { ImeLogic.SPACE_CURSOR_SLIDE_STEP_DP.dp.toPx() }
    val swipeUpThresholdPx = with(density) { ImeLogic.SECONDARY_KEY_SWIPE_UP_DP.dp.toPx() }

    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnMoveCursor by rememberUpdatedState(onMoveCursorByStep)
    val currentOnLongPressToggleLanguage by rememberUpdatedState(onLongPressToggleLanguage)
    val currentOnHoldStartVoice by rememberUpdatedState(onHoldStartInPlaceVoice)
    val currentOnHoldReleaseVoice by rememberUpdatedState(onHoldReleaseInPlaceVoice)
    val currentSpaceAction by rememberUpdatedState(spaceLongPressAction)
    val currentFiveKeyBottomRow by rememberUpdatedState(fiveKeyBottomRow)
    var isPressed by remember { mutableStateOf(false) }
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
                        val startPos = down.position
                        val startX = startPos.x
                        var lastX = startX
                        var accumulatedDx = 0f
                        var slidingActive = false
                        var swipedUp = false
                        var holdTriggered = false
                        var voiceHoldStarted = false
                        var voiceHoldReleased = false
                        var cancelled = false
                        isPressed = true
                        isCursorSliding = false
                        isVoiceHolding = false
                        keyFeedback.onTouchFeedback(HapticTier.FUNCTION_KEY, view, KeySoundType.SPACEBAR, true)

                        val actionAtDown = currentSpaceAction
                        val holdJob: Job? = when {
                            actionAtDown == SpaceLongPressAction.VOICE_HOLD && currentOnHoldStartVoice != null -> {
                                outerScope.launch {
                                    delay(ImeLogic.SPACE_VOICE_HOLD_MS)
                                    if (!cancelled && !slidingActive && !swipedUp && !holdTriggered) {
                                        holdTriggered = true
                                        voiceHoldStarted = true
                                        isVoiceHolding = true
                                        keyFeedback.onTouchFeedback(HapticTier.MIC_STRONG, view, KeySoundType.NONE, false)
                                        currentOnHoldStartVoice?.invoke()
                                    }
                                }
                            }
                            actionAtDown == SpaceLongPressAction.TOGGLE_LANGUAGE && currentOnLongPressToggleLanguage != null -> {
                                outerScope.launch {
                                    delay(ImeLogic.SPACE_LANGUAGE_LONG_PRESS_MS)
                                    if (!cancelled && !slidingActive && !swipedUp && !holdTriggered) {
                                        holdTriggered = true
                                        keyFeedback.onTouchFeedback(HapticTier.GESTURE_CONFIRM, view, KeySoundType.NONE, false)
                                        currentOnLongPressToggleLanguage?.invoke()
                                    }
                                }
                            }
                            else -> null
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    cancelled = true
                                    holdJob?.cancel()
                                    break
                                }
                                val pos = change.position
                                val totalDx = pos.x - startX
                                val totalDy = pos.y - startPos.y
                                val frameDx = pos.x - lastX
                                lastX = pos.x

                                if (
                                    !holdTriggered &&
                                    !slidingActive &&
                                    !swipedUp &&
                                    !cancelled &&
                                    currentOnLongPressToggleLanguage != null &&
                                    ImeLogic.shouldSwipeUpSpaceToggleLanguage(
                                        fiveKeyBottomRow = currentFiveKeyBottomRow,
                                        spaceLongPressAction = actionAtDown,
                                        dxPx = totalDx,
                                        dyPx = totalDy,
                                        thresholdPx = swipeUpThresholdPx
                                    )
                                ) {
                                    swipedUp = true
                                    holdJob?.cancel()
                                    change.consume()
                                    keyFeedback.onTouchFeedback(HapticTier.GESTURE_CONFIRM, view, KeySoundType.NONE, false)
                                    currentOnLongPressToggleLanguage?.invoke()
                                } else if (!holdTriggered && !swipedUp && !slidingActive && abs(totalDx) >= slideActivatePx) {
                                    slidingActive = true
                                    isCursorSliding = true
                                    holdJob?.cancel()
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
                                    !holdTriggered &&
                                    !swipedUp &&
                                    (pos.y < -touchSlopPx ||
                                        pos.y > size.height + touchSlopPx)
                                ) {
                                    cancelled = true
                                    holdJob?.cancel()
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
                            isVoiceHolding = false
                            holdJob?.cancel()
                            if (voiceHoldStarted && !voiceHoldReleased) {
                                voiceHoldReleased = true
                                currentOnHoldReleaseVoice?.invoke()
                            }
                        }

                        if (!cancelled && !slidingActive && !swipedUp && !holdTriggered) {
                            currentOnTap()
                        }
                    }
                }
            }
            .padding(horizontal = KEY_CELL_HPAD, vertical = verticalCellPad)
    ) {
        val activeSpaceVoice = isVoiceHolding && isInPlaceVoiceActive
        val faceColor = when {
            activeSpaceVoice -> TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.22f else 0.14f)
            isPressed -> palette.keyCapSecondaryBg
            else -> palette.keyCapBg
        }
        val borderColor = if (activeSpaceVoice) {
            TypeFreeEmerald.copy(alpha = 0.68f)
        } else {
            palette.keyBorder.copy(alpha = if (palette.isDark) 0.58f else 0.50f)
        }
        TactileKeycapSurface(
            isPressed = isPressed,
            cornerRadius = FUNC_KEY_CORNER,
            faceColor = faceColor,
            borderColor = borderColor,
            shadowColor = palette.keyShadow
        ) {
            val spaceLang = LocalAppLanguage.current
            val resolvedChineseName = when {
                activeInputScheme == InputScheme.RIME_CUSTOM && !customSchemaDisplayName.isNullOrBlank() -> {
                    val clean = customSchemaDisplayName.trim()
                    if (spaceLang == AppLang.EN && clean.contains("小鹤")) "Flypy" else clean
                }
                activeInputScheme == InputScheme.RIME_CUSTOM -> tr("自定义码表", "Rime", "自訂碼表")
                spaceLang == AppLang.EN -> AppStrings.inputSchemeLabel(activeInputScheme, spaceLang, doublePinyinLayout)
                else -> shortInputSchemeLabel(activeInputScheme, doublePinyinLayout)
            }
            val label = when {
                activeSpaceVoice -> tr("🎙 聆听中 · 松手上屏", "🎙 Listening · Release", "🎙 聆聽中 · 放開上屏")
                isCursorSliding -> tr("‹ 滑动移光标 ›", "‹ Slide cursor ›", "‹ 滑動移游標 ›")
                else -> ImeLogic.resolveQwertySpaceBarLabel(
                    lang = spaceLang,
                    isSpaceVoiceActive = false,
                    isCursorSliding = false,
                    hasComposing = hasComposing,
                    isFlypyChineseMode = isFlypyChineseMode,
                    fiveKeyBottomRow = fiveKeyBottomRow,
                    resolvedChineseSchemeName = resolvedChineseName
                )
            }
            val labelColor = when {
                activeSpaceVoice -> if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                hasComposing || isCursorSliding -> palette.keyTextPrimary
                else -> palette.keyTextMuted
            }
            Text(
                text = label,
                color = labelColor,
                fontSize = 12.sp,
                fontWeight = if (activeSpaceVoice || hasComposing || isCursorSliding) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 4.dp)
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
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val hostView = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                keyFeedback.onTouchFeedback(HapticTier.FUNCTION_KEY, hostView, KeySoundType.STANDARD, false)
            }
        }
    }

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

/**
 * Bottom-left `123` / `ABC` key with default Emoji/Kaomoji/Symbol panel entry:
 * - Tap: toggles numeric/symbol layer (`123` <-> `ABC`) with zero regression to fast digit typing.
 * - Swipe-up (`>= 18.dp`) or Long-press: opens the unified Expression & Symbol panel on the Emoji tab.
 */
@Composable
private fun QwertySymbolEmojiKey(
    label: String,
    topBadge: String,
    verticalCellPad: Dp = KEY_CELL_VPAD_5ROW,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onOpenEmoji: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val density = LocalDensity.current
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val hostView = LocalView.current
    val touchSlopPx = with(density) { 28.dp.toPx() }
    val swipeUpThresholdPx = with(density) { ImeLogic.SECONDARY_KEY_SWIPE_UP_DP.dp.toPx() }

    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnOpenEmoji by rememberUpdatedState(onOpenEmoji)
    var isPressed by remember { mutableStateOf(false) }

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
                        keyFeedback.onTouchFeedback(HapticTier.FUNCTION_KEY, hostView, KeySoundType.STANDARD, false)
                        var gestureFired = false
                        var cancelled = false

                        val longJob = outerScope.launch {
                            delay(KEY_LONG_PRESS_MS)
                            if (!cancelled && !gestureFired) {
                                gestureFired = true
                                keyFeedback.onTouchFeedback(HapticTier.GESTURE_CONFIRM, hostView, KeySoundType.NONE, true)
                                currentOnOpenEmoji()
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
                                val dx = pos.x - startPos.x
                                val dy = pos.y - startPos.y
                                if (!gestureFired && !cancelled && dy <= -swipeUpThresholdPx && abs(dy) > abs(dx)) {
                                    gestureFired = true
                                    longJob.cancel()
                                    change.consume()
                                    keyFeedback.onTouchFeedback(HapticTier.GESTURE_CONFIRM, hostView, KeySoundType.NONE, true)
                                    currentOnOpenEmoji()
                                } else if (
                                    !gestureFired &&
                                    (pos.x < -touchSlopPx ||
                                        pos.x > size.width + touchSlopPx ||
                                        pos.y < -touchSlopPx ||
                                        pos.y > size.height + touchSlopPx)
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

                        if (!cancelled && !gestureFired) {
                            currentOnTap()
                        }
                    }
                }
            }
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
                text = topBadge,
                style = TextStyle(
                    color = palette.keyTextMuted.copy(alpha = 0.82f),
                    fontSize = (9.5f * metrics.widthScale).sp,
                    lineHeight = (11f * metrics.widthScale).sp,
                    fontWeight = FontWeight.SemiBold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
            )
            Text(
                text = label,
                style = TextStyle(
                    color = palette.keyTextPrimary,
                    fontSize = (12.5f * metrics.keyFontScale).sp,
                    fontWeight = FontWeight.SemiBold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = 3.5.dp)
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
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val hostView = LocalView.current
    val currentClearCode by rememberUpdatedState(isClearCodeMode)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                keyFeedback.onTouchFeedback(HapticTier.FUNCTION_KEY, hostView, KeySoundType.STANDARD, currentClearCode)
            }
        }
    }

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
                    text = tr("清码", "Clear", "清碼"),
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
    onSwipeUpAction: () -> Unit,
    swipeUpBadge: String = "撤",
    verticalCellPad: Dp = KEY_CELL_VPAD_5ROW,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val density = LocalDensity.current
    val keyFeedback = LocalKeyFeedbackTrigger.current
    val hostView = LocalView.current
    val swipeUpThresholdPx = with(density) { 24.dp.toPx() }

    val currentOnBackspace by rememberUpdatedState(onBackspace)
    val currentOnSwipeUpAction by rememberUpdatedState(onSwipeUpAction)
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
                        keyFeedback.onTouchFeedback(HapticTier.FUNCTION_KEY, hostView, KeySoundType.DELETE, true)
                        var swipedUp = false
                        var repeatStarted = false

                        val repeatJob = outerScope.launch {
                            delay(320L)
                            if (!swipedUp) {
                                repeatStarted = true
                                while (isActive && !swipedUp) {
                                    keyFeedback.onTouchFeedback(HapticTier.CURSOR_TICK, hostView, KeySoundType.DELETE, true)
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
                                    keyFeedback.onTouchFeedback(HapticTier.GESTURE_CONFIRM, hostView, KeySoundType.NONE, false)
                                    currentOnSwipeUpAction()
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
                text = swipeUpBadge,
                color = palette.keyTextMuted.copy(alpha = 0.56f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 1.5.dp, end = 3.5.dp)
            )
            Icon(
                imageVector = TypeFreeIcons.Backspace,
                contentDescription = if (swipeUpBadge == "清") "退格删除（上划清空输入框）" else "退格删除（上划撤回上屏）",
                tint = palette.keyTextPrimary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(19.dp)
            )
        }
    }
}
