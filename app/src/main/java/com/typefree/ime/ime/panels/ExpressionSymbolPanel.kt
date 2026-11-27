package com.typefree.ime.ime.panels

import android.graphics.Paint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.ExpressionTab
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ime.HapticTier
import com.typefree.ime.ime.ImeUiAction
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeySoundType
import com.typefree.ime.ime.LocalKeyFeedbackTrigger
import com.typefree.ime.ime.LocalKeyboardMetrics
import com.typefree.ime.ime.expression.EmojiCategory
import com.typefree.ime.ime.expression.EmojiItem
import com.typefree.ime.ime.expression.ExpressionCatalog
import com.typefree.ime.ime.expression.KaomojiCategory
import com.typefree.ime.ime.expression.SymbolCategory
import com.typefree.ime.ime.expression.SymbolItem
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeEmerald
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Compact host adapter keeping `KeyboardScreen.kt` strictly under the 650-line architecture guard.
 */
@Composable
fun ExpressionSymbolPanelHost(
    uiState: ImeUiState,
    settings: AppSettings,
    onAction: (ImeUiAction) -> Unit
) {
    ExpressionSymbolPanel(
        activeTab = uiState.activeExpressionTab,
        recentEmojis = settings.recentEmojis,
        skinTonePrefs = settings.emojiSkinTonePrefs,
        onSelectTab = { onAction(ImeUiAction.SelectExpressionTab(it)) },
        onCommitEmoji = { glyph, baseGlyph ->
            onAction(ImeUiAction.CommitExpressionEmoji(glyph = glyph, baseGlyph = baseGlyph))
        },
        onCommitKaomoji = { text -> onAction(ImeUiAction.CommitSecondarySymbol(text)) },
        onCommitSymbol = { sym ->
            if (sym.isPaired && sym.pairedOpen != null && sym.pairedClose != null) {
                onAction(ImeUiAction.InsertPairedSymbol(sym.pairedOpen, sym.pairedClose))
            } else {
                onAction(ImeUiAction.CommitSecondarySymbol(sym.commitText))
            }
        },
        onBackspace = { onAction(ImeUiAction.Backspace) },
        onClose = { onAction(ImeUiAction.CloseExpressionPanel) }
    )
}

/**
 * Unified Emoji / Kaomoji / Extended Symbol Panel locked to [LocalKeyboardMetrics.frameHeight]
 * (`0px` host viewport jitter):
 * - Top 34.dp header: `[Emoji | 颜文字 | 符号]` segmented pills + quick mood search toggle + close.
 * - Middle body: 8-col Emoji grid (with skin-tone long-press popup & glyph filter) /
 *   adaptive-width Kaomoji flow cards / 6-col categorized Extended Symbol grid.
 * - Bottom 34.dp thumb bar: `[ABC]` + category quick-switch icons/chips + repeating `[⌫]`.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ExpressionSymbolPanel(
    activeTab: ExpressionTab,
    recentEmojis: List<String>,
    skinTonePrefs: Map<String, String>,
    onSelectTab: (ExpressionTab) -> Unit,
    onCommitEmoji: (glyph: String, baseGlyph: String?) -> Unit,
    onCommitKaomoji: (String) -> Unit,
    onCommitSymbol: (SymbolItem) -> Unit,
    onBackspace: () -> Unit,
    onClose: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val appLang = LocalAppLanguage.current
    val feedback = LocalKeyFeedbackTrigger.current
    val view = LocalView.current

    var activeEmojiCat by remember { mutableStateOf(EmojiCategory.SMILEYS) }
    var activeKaomojiCat by remember { mutableStateOf(KaomojiCategory.HAPPY) }
    var activeSymbolCat by remember { mutableStateOf(SymbolCategory.COMMON_CN) }
    var isSearchStripOpen by remember { mutableStateOf(false) }
    var activeSearchQuery by remember { mutableStateOf("") }
    var skinTonePopupItem by remember { mutableStateOf<EmojiItem?>(null) }

    val glyphSupportCache = remember { HashMap<String, Boolean>(256) }
    val glyphPaint = remember { Paint() }
    val isGlyphSupported: (String) -> Boolean = remember(glyphPaint) {
        { glyph ->
            glyphSupportCache.getOrPut(glyph) {
                runCatching { glyphPaint.hasGlyph(glyph) }.getOrDefault(true)
            }
        }
    }

    val visibleEmojis = remember(activeEmojiCat, activeSearchQuery, recentEmojis) {
        val raw = if (activeSearchQuery.isNotBlank()) {
            ExpressionCatalog.searchEmojis(activeSearchQuery)
        } else if (activeEmojiCat == EmojiCategory.RECENT) {
            ExpressionCatalog.resolveRecentEmojis(recentEmojis)
        } else {
            ExpressionCatalog.EMOJIS_BY_CATEGORY[activeEmojiCat].orEmpty()
        }
        ExpressionCatalog.filterSupportedEmojis(raw, isGlyphSupported)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 1. Top Header Bar (`metrics.topBarHeight`, matches Clipboard & QWERTY top bar slot)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.topBarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 3 Main Tabs: Emoji | 颜文字 | 符号
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(palette.capsuleTrackBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(15.dp))
                    .padding(2.5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ExpressionTab.entries.forEach { tab ->
                    val selected = activeTab == tab
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) palette.primaryMicSolid else Color.Transparent)
                            .clickable {
                                feedback.onTouchFeedback(HapticTier.CANDIDATE_TAP, view, KeySoundType.STANDARD, false)
                                skinTonePopupItem = null
                                if (tab != ExpressionTab.EMOJI) {
                                    isSearchStripOpen = false
                                    activeSearchQuery = ""
                                }
                                onSelectTab(tab)
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = ExpressionCatalog.tabLabel(tab, appLang),
                            color = if (selected) palette.onPrimaryMic else palette.keyTextMuted,
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }

            // Right Controls: Quick Mood Search Toggle + Close
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val searchActive = activeTab == ExpressionTab.EMOJI && isSearchStripOpen
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (searchActive) {
                                TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.22f else 0.14f)
                            } else {
                                palette.keyCapBg
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (searchActive) TypeFreeEmerald.copy(alpha = 0.7f) else palette.keyBorder,
                            shape = CircleShape
                        )
                        .clickable {
                            feedback.onTouchFeedback(HapticTier.CANDIDATE_TAP, view, KeySoundType.STANDARD, false)
                            skinTonePopupItem = null
                            if (activeTab != ExpressionTab.EMOJI) {
                                onSelectTab(ExpressionTab.EMOJI)
                                isSearchStripOpen = true
                            } else {
                                isSearchStripOpen = !isSearchStripOpen
                                if (!isSearchStripOpen) activeSearchQuery = ""
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.Search,
                        contentDescription = tr("按关键词筛表情", "Filter Emoji", "按關鍵詞篩表情"),
                        tint = if (searchActive) {
                            if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                        } else {
                            palette.keyTextMuted
                        },
                        modifier = Modifier.size(13.5.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(palette.capsuleSelectedBg)
                        .border(1.dp, palette.keyBorder, CircleShape)
                        .clickable {
                            feedback.onTouchFeedback(HapticTier.FUNCTION_KEY, view, KeySoundType.STANDARD, true)
                            onClose()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.Close,
                        contentDescription = tr("返回键盘", "Back to Keyboard", "返回鍵盤"),
                        tint = palette.keyTextPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // 1.5 Optional Quick Mood/Keyword Filter Strip (Zero InputConnection focus conflict inside IME!)
        if (activeTab == ExpressionTab.EMOJI && isSearchStripOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                ExpressionCatalog.QUICK_SEARCH_CHIPS.forEach { chip ->
                    val selected = activeSearchQuery == chip.query
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) {
                                    TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.22f else 0.14f)
                                } else {
                                    palette.keyCapBg
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) TypeFreeEmerald.copy(alpha = 0.72f) else palette.keyBorder,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                feedback.onTouchFeedback(HapticTier.CANDIDATE_TAP, view, KeySoundType.STANDARD, false)
                                activeSearchQuery = if (selected) "" else chip.query
                            }
                            .padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = chip.label(appLang),
                            color = if (selected) {
                                if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                            } else {
                                palette.keyTextPrimary
                            },
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }
        }

        // 2. Main Scrollable Content Grid (with in-frame Skin Tone floating popover)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (activeTab) {
                ExpressionTab.EMOJI -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(8),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(
                            items = visibleEmojis,
                            key = { "${activeEmojiCat.name}_${activeSearchQuery}_${it.glyph}" }
                        ) { item ->
                            val displayGlyph = ExpressionCatalog.resolveDisplayGlyph(item, skinTonePrefs)
                            val hasSkins = item.skinVariants.isNotEmpty()
                            Box(
                                modifier = Modifier
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(
                                        onClick = {
                                            skinTonePopupItem = null
                                            feedback.onTouchFeedback(
                                                HapticTier.CANDIDATE_TAP,
                                                view,
                                                KeySoundType.STANDARD,
                                                true
                                            )
                                            onCommitEmoji(displayGlyph, if (hasSkins) item.glyph else null)
                                        },
                                        onLongClick = if (hasSkins) {
                                            {
                                                feedback.onTouchFeedback(
                                                    HapticTier.GESTURE_CONFIRM,
                                                    view,
                                                    KeySoundType.NONE,
                                                    false
                                                )
                                                skinTonePopupItem = item
                                            }
                                        } else {
                                            null
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = displayGlyph,
                                    fontSize = 21.sp,
                                    maxLines = 1,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                                if (hasSkins) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(bottom = 3.dp, end = 4.dp)
                                            .size(3.5.dp)
                                            .clip(CircleShape)
                                            .background(palette.keyTextMuted.copy(alpha = 0.55f))
                                    )
                                }
                            }
                        }
                    }

                    // Skin-tone variant picker floating bar
                    val activeSkinItem = skinTonePopupItem
                    if (activeSkinItem != null) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 4.dp)
                                .zIndex(10f)
                                .shadow(6.dp, RoundedCornerShape(14.dp))
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.keyPopupBg)
                                .border(1.dp, palette.keyBorder, RoundedCornerShape(14.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            activeSkinItem.skinVariants.forEach { variant ->
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            feedback.onTouchFeedback(
                                                HapticTier.CANDIDATE_TAP,
                                                view,
                                                KeySoundType.STANDARD,
                                                true
                                            )
                                            skinTonePopupItem = null
                                            onCommitEmoji(variant, activeSkinItem.glyph)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = variant,
                                        fontSize = 21.sp,
                                        maxLines = 1,
                                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                    )
                                }
                            }
                        }
                    }
                }

                ExpressionTab.KAOMOJI -> {
                    val kaomojis = ExpressionCatalog.KAOMOJI_BY_CATEGORY[activeKaomojiCat].orEmpty()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 2.dp)
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            kaomojis.forEach { kaomoji ->
                                Box(
                                    modifier = Modifier
                                        .height(35.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(palette.keyCapBg)
                                        .border(0.8.dp, palette.keyBorder, RoundedCornerShape(9.dp))
                                        .clickable {
                                            feedback.onTouchFeedback(
                                                HapticTier.CANDIDATE_TAP,
                                                view,
                                                KeySoundType.STANDARD,
                                                true
                                            )
                                            onCommitKaomoji(kaomoji)
                                        }
                                        .padding(horizontal = 11.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = kaomoji,
                                        color = palette.keyTextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                    )
                                }
                            }
                        }
                    }
                }

                ExpressionTab.SYMBOL -> {
                    val symbols = ExpressionCatalog.SYMBOLS_BY_CATEGORY[activeSymbolCat].orEmpty()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        items(
                            items = symbols,
                            key = { "${activeSymbolCat.name}_${it.display}_${it.commitText}" }
                        ) { sym ->
                            Box(
                                modifier = Modifier
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(palette.keyCapBg)
                                    .border(0.8.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                                    .clickable {
                                        feedback.onTouchFeedback(
                                            HapticTier.ALPHA_KEY,
                                            view,
                                            KeySoundType.STANDARD,
                                            true
                                        )
                                        onCommitSymbol(sym)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = sym.display,
                                    color = palette.keyTextPrimary,
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Bottom 32.dp Thumb Ergonomic Bar: [ABC] + Category Quick Strip + Repeating [⌫]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.keyCapSecondaryBg)
                    .border(0.8.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                    .clickable {
                        feedback.onTouchFeedback(HapticTier.FUNCTION_KEY, view, KeySoundType.STANDARD, true)
                        onClose()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ABC",
                    color = palette.keyTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }

            // Center Category Strip (right in the thumb arc!)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.keyCapBg)
                    .border(0.8.dp, palette.keyBorder, RoundedCornerShape(8.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                when (activeTab) {
                    ExpressionTab.EMOJI -> {
                        EmojiCategory.entries.forEach { cat ->
                            val selected = activeEmojiCat == cat && activeSearchQuery.isBlank()
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) palette.capsuleSelectedBg else Color.Transparent)
                                    .border(
                                        width = if (selected) 1.dp else 0.dp,
                                        color = if (selected) TypeFreeEmerald.copy(alpha = 0.65f) else Color.Transparent,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        feedback.onTouchFeedback(HapticTier.CANDIDATE_TAP, view, KeySoundType.NONE, false)
                                        skinTonePopupItem = null
                                        activeSearchQuery = ""
                                        activeEmojiCat = cat
                                    }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = cat.icon,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                            }
                        }
                    }

                    ExpressionTab.KAOMOJI -> {
                        KaomojiCategory.entries.forEach { cat ->
                            val selected = activeKaomojiCat == cat
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) palette.capsuleSelectedBg else Color.Transparent)
                                    .border(
                                        width = if (selected) 1.dp else 0.dp,
                                        color = if (selected) TypeFreeEmerald.copy(alpha = 0.65f) else Color.Transparent,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        feedback.onTouchFeedback(HapticTier.CANDIDATE_TAP, view, KeySoundType.NONE, false)
                                        activeKaomojiCat = cat
                                    }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = ExpressionCatalog.kaomojiCategoryLabel(cat, appLang),
                                    color = if (selected) palette.keyTextPrimary else palette.keyTextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                            }
                        }
                    }

                    ExpressionTab.SYMBOL -> {
                        SymbolCategory.entries.forEach { cat ->
                            val selected = activeSymbolCat == cat
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) palette.capsuleSelectedBg else Color.Transparent)
                                    .border(
                                        width = if (selected) 1.dp else 0.dp,
                                        color = if (selected) TypeFreeEmerald.copy(alpha = 0.65f) else Color.Transparent,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        feedback.onTouchFeedback(HapticTier.CANDIDATE_TAP, view, KeySoundType.NONE, false)
                                        activeSymbolCat = cat
                                    }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = ExpressionCatalog.symbolCategoryLabel(cat, appLang),
                                    color = if (selected) palette.keyTextPrimary else palette.keyTextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                            }
                        }
                    }
                }
            }

            // Repeating Backspace Key with 0ms DOWN + CURSOR_TICK repeat feel
            ExpressionRepeatingBackspace(
                onBackspace = onBackspace,
                modifier = Modifier
                    .width(46.dp)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun ExpressionRepeatingBackspace(
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val feedback = LocalKeyFeedbackTrigger.current
    val view = LocalView.current
    val currentOnBackspace by rememberUpdatedState(onBackspace)
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) palette.keyBorder else palette.keyCapSecondaryBg)
            .border(0.8.dp, palette.keyBorder, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                coroutineScope {
                    val outerScope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        pressed = true
                        feedback.onTouchFeedback(HapticTier.FUNCTION_KEY, view, KeySoundType.DELETE, true)
                        var repeatStarted = false
                        val repeatJob = outerScope.launch {
                            delay(320L)
                            repeatStarted = true
                            while (isActive) {
                                feedback.onTouchFeedback(HapticTier.CURSOR_TICK, view, KeySoundType.DELETE, true)
                                currentOnBackspace()
                                delay(52L)
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                            }
                        } finally {
                            pressed = false
                            repeatJob.cancel()
                        }
                        if (!repeatStarted) {
                            currentOnBackspace()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = TypeFreeIcons.Backspace,
            contentDescription = tr("退格删除", "Backspace", "退格刪除"),
            tint = palette.keyTextPrimary,
            modifier = Modifier.size(17.dp)
        )
    }
}
