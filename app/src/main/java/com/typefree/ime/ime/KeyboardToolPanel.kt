package com.typefree.ime.ime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.CandidateFontScale
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.components.rememberLocalModelRegistrySnapshot
import com.typefree.ime.ui.components.resolveCompletionBackendCompactLabel
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeEmerald
import kotlin.math.abs

/**
 * 4x2 Quick Tool Panel rendered inside the key area while keeping the 34.dp Top Bar stationary.
 *
 * Top Strip: 无痕模式 · 轻声耳语模式 · AI 帮写直达 (or Inline Height Adjust Strip when expanded)
 * Row 1: 剪贴板 · 键盘高度 · 输入方案 · 文风
 * Row 2: AI 补全 · 实时写入 · 词库 · 设置
 */
@Composable
fun KeyboardToolPanel(
    uiState: ImeUiState,
    settings: AppSettings,
    activeStyle: ToneStyle,
    clipboardCount: Int,
    onOpenClipboard: () -> Unit,
    onToggleHeightExpanded: () -> Unit,
    onSelectHeightLevel: (Int) -> Unit,
    onSelectHeightMode: (KeyboardHeightMode) -> Unit = {},
    onSelectLiftLevel: (Int) -> Unit = {},
    onCycleInputScheme: () -> Unit,
    onOpenStylePicker: () -> Unit,
    onToggleAiCompletion: () -> Unit,
    onToggleLiveWrite: () -> Unit,
    onToggleWhisperMode: () -> Unit = {},
    onOpenHelpMeWrite: () -> Unit = {},
    onToggleManualIncognito: () -> Unit = {},
    onToggleSingleCharMode: () -> Unit = {},
    onOpenExpressionPanel: () -> Unit = {},
    onOpenDictionary: () -> Unit,
    onOpenSettings: () -> Unit,
    showLanguageTogglePill: Boolean = false,
    onToggleLanguageMode: () -> Unit = {},
    isPhraseRecording: Boolean = false,
    onTogglePhraseRecording: () -> Unit = {},
    onAction: (ImeUiAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val metrics = LocalKeyboardMetrics.current
    val palette = LocalTypeFreeKeyboardPalette.current
    val appLang = LocalAppLanguage.current
    val s = metrics.elementScale

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy((5f * s).dp)
    ) {
        // Inline 3-in-1 Adjustment Strip (高度 / 垫高 / 字号) or Scheme A Quick-Select Strip,
        // or Compact Privacy / Whisper / HelpMeWrite Quick Strip otherwise
        AnimatedVisibility(
            visible = uiState.isToolPanelHeightExpanded,
            enter = fadeIn(tween(120)) + expandVertically(tween(140)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(120))
        ) {
            ToolPanelHeightAdjustStrip(
                activeHeightLevel = settings.keyboardHeightLevel,
                activeBottomLiftDp = settings.resolvedBottomLiftDp(),
                activeFontScale = settings.candidateFontScale,
                onSelectHeightLevel = onSelectHeightLevel,
                onSelectBottomLiftDp = { dp -> onAction(ImeUiAction.SelectKeyboardBottomLiftDp(dp)) },
                onSelectFontScale = { scale -> onAction(ImeUiAction.SelectCandidateFontScale(scale)) },
                onResetAll = { onAction(ImeUiAction.ResetKeyboardAdjustments) }
            )
        }

        val dm = remember { runCatching { com.typefree.ime.TypeFreeApp.instance.rimeDataManager }.getOrNull() }
        val hasCustomSchema = remember(uiState.activeInputScheme, uiState.isToolPanelOpen) {
            dm?.hasCustomSchemaImported() == true
        }
        val customSchemaName = remember(uiState.activeInputScheme, uiState.isToolPanelOpen) {
            dm?.readCustomSchemaName()
        }

        AnimatedVisibility(
            visible = uiState.isToolPanelSchemeExpanded && !uiState.isToolPanelHeightExpanded,
            enter = fadeIn(tween(120)) + expandVertically(tween(140)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(120))
        ) {
            ToolPanelSchemeQuickStrip(
                activeScheme = uiState.activeInputScheme,
                activeDoublePinyinLayout = settings.doublePinyinLayout,
                isDoublePinyinSubOpen = uiState.isToolPanelDoublePinyinSubOpen,
                hasCustomSchema = hasCustomSchema,
                customSchemaName = customSchemaName,
                onSelectScheme = { scheme -> onAction(ImeUiAction.SelectInputScheme(scheme)) },
                onSelectDoublePinyinLayout = { layout -> onAction(ImeUiAction.SelectDoublePinyinLayout(layout)) },
                onToggleDoublePinyinSub = { onAction(ImeUiAction.ToggleToolPanelDoublePinyinSub) }
            )
        }

        if (!uiState.isToolPanelHeightExpanded && !uiState.isToolPanelSchemeExpanded) {
            val isManualIncognito = settings.manualIncognitoMode
            val isEffectiveIncognito = uiState.isIncognitoMode || isManualIncognito
            val whisperEnabled = settings.whisperModeEnabled
            val activeAccent = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)

            val topStripScroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .horizontalScroll(topStripScroll),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // 1. Incognito Mode Pill (`TypeFreeIcons.IncognitoMask`)
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (isEffectiveIncognito) {
                                TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.16f else 0.12f)
                            } else {
                                palette.capsuleTrackBg
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (isEffectiveIncognito) {
                                TypeFreeEmerald.copy(alpha = 0.65f)
                            } else {
                                palette.keyBorder
                            },
                            shape = RoundedCornerShape(9.dp)
                        )
                        .clickable(onClick = onToggleManualIncognito)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.IncognitoMask,
                        contentDescription = null,
                        tint = if (isEffectiveIncognito) activeAccent else palette.keyTextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = when {
                            isManualIncognito -> tr("无痕已开启", "Incognito On", "無痕已開啟")
                            uiState.isIncognitoMode -> tr("输入框自动无痕", "Auto Incognito", "輸入框自動無痕")
                            else -> tr("无痕模式", "Incognito", "無痕模式")
                        },
                        color = if (isEffectiveIncognito) activeAccent else palette.keyTextMuted,
                        fontSize = 10.5.sp,
                        fontWeight = if (isEffectiveIncognito) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 1b. Unified Emoji / Kaomoji / Symbol Panel Discovery Pill
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        .background(palette.capsuleTrackBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(9.dp))
                        .clickable(onClick = onOpenExpressionPanel)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                ) {
                    Text(
                        text = "☺",
                        color = palette.keyTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Text(
                        text = tr("表情", "Emoji", "表情"),
                        color = palette.keyTextPrimary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                // 2. Whisper Mode Toggle Pill (`TypeFreeIcons.WhisperWave`)
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (whisperEnabled) {
                                TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.16f else 0.12f)
                            } else {
                                palette.capsuleTrackBg
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (whisperEnabled) {
                                TypeFreeEmerald.copy(alpha = 0.65f)
                            } else {
                                palette.keyBorder
                            },
                            shape = RoundedCornerShape(9.dp)
                        )
                        .clickable(onClick = onToggleWhisperMode)
                        .padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.WhisperWave,
                        contentDescription = null,
                        tint = if (whisperEnabled) activeAccent else palette.keyTextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (whisperEnabled) tr("轻声开", "Whisper On", "輕聲開") else tr("轻声模式", "Whisper", "輕聲模式"),
                        color = if (whisperEnabled) activeAccent else palette.keyTextMuted,
                        fontSize = 10.5.sp,
                        fontWeight = if (whisperEnabled) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1
                    )
                }

                // 3. Direct AI Help-Me-Write Pill (`TypeFreeIcons.VoicePen`)
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        .background(palette.capsuleTrackBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(9.dp))
                        .clickable(onClick = onOpenHelpMeWrite)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.VoicePen,
                        contentDescription = null,
                        tint = palette.keyTextPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = tr("帮写", "Help Write", "幫寫"),
                        color = palette.keyTextPrimary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                // 4. Codetable Single-Char Mode Toggle Pill (`单字`)
                if (uiState.codetableEnhancerActive) {
                    val singleCharEnabled = uiState.singleCharModeEnabled
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (singleCharEnabled) {
                                    TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.16f else 0.12f)
                                } else {
                                    palette.capsuleTrackBg
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (singleCharEnabled) {
                                    TypeFreeEmerald.copy(alpha = 0.65f)
                                } else {
                                    palette.keyBorder
                                },
                                shape = RoundedCornerShape(9.dp)
                            )
                            .clickable(onClick = onToggleSingleCharMode)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = tr("单字", "1-Char", "單字"),
                            color = if (singleCharEnabled) activeAccent else palette.keyTextMuted,
                            fontSize = 10.5.sp,
                            fontWeight = if (singleCharEnabled) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }

                    // 6. Codetable Phrase Recording Toggle Pill (`造词`)
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (isPhraseRecording) {
                                    TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.18f else 0.14f)
                                } else {
                                    palette.capsuleTrackBg
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (isPhraseRecording) {
                                    TypeFreeEmerald.copy(alpha = 0.72f)
                                } else {
                                    palette.keyBorder
                                },
                                shape = RoundedCornerShape(9.dp)
                            )
                            .clickable(onClick = onTogglePhraseRecording)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isPhraseRecording) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(TypeFreeEmerald)
                            )
                        }
                        Text(
                            text = if (isPhraseRecording) tr("造词中", "Recording…", "造詞中") else tr("造词", "Add Phrase", "造詞"),
                            color = if (isPhraseRecording) activeAccent else palette.keyTextMuted,
                            fontSize = 10.5.sp,
                            fontWeight = if (isPhraseRecording) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }

                // 7. Compact Language Toggle Pill (`中/英`), e.g. when 5-key bottom row hides 🌐
                if (showLanguageTogglePill) {
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(9.dp))
                            .background(palette.capsuleTrackBg)
                            .border(1.dp, palette.keyBorder, RoundedCornerShape(9.dp))
                            .clickable(onClick = onToggleLanguageMode)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = tr("中/英", "CN/EN", "中/英"),
                            color = palette.keyTextPrimary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Row 1: 剪贴板 · 键盘高度 · 输入方案 · 文风
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            ToolPanelTile(
                icon = TypeFreeIcons.Clipboard,
                title = tr("剪贴板", "Clipboard", "剪貼簿"),
                subtitle = if (clipboardCount > 0) {
                    tr("$clipboardCount 条记录", "$clipboardCount items", "$clipboardCount 筆紀錄")
                } else {
                    tr("常用语", "Snippets", "常用語")
                },
                badgeDot = uiState.freshClipboardBannerItem != null,
                onClick = onOpenClipboard,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            val resolvedLiftDp = settings.resolvedBottomLiftDp()
            val isAdjustCustomized = !ToolPanelAdjustHelper.isAllDefault(
                heightLevel = settings.keyboardHeightLevel,
                bottomLiftDp = resolvedLiftDp,
                fontScale = settings.candidateFontScale
            )
            ToolPanelTile(
                icon = TypeFreeIcons.Tune,
                title = tr("键盘调节", "Adjust", "鍵盤調節"),
                subtitle = ToolPanelAdjustHelper.formatCompactSubtitle(
                    heightLevel = settings.keyboardHeightLevel,
                    bottomLiftDp = resolvedLiftDp,
                    fontScale = settings.candidateFontScale,
                    lang = appLang
                ),
                isToggleEnabled = !uiState.isToolPanelHeightExpanded && isAdjustCustomized,
                isExpandedActive = uiState.isToolPanelHeightExpanded,
                onClick = onToggleHeightExpanded,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            ToolPanelTile(
                icon = TypeFreeIcons.KeyboardGrid,
                title = tr("输入方案", "Scheme", "輸入方案"),
                subtitle = if (!uiState.isFlypyChineseMode) {
                    tr("英文键盘", "English", "英文鍵盤")
                } else if (appLang == AppLang.EN) {
                    if (uiState.activeInputScheme == InputScheme.RIME_CUSTOM && !customSchemaName.isNullOrBlank()) {
                        customSchemaName
                    } else {
                        AppStrings.inputSchemeLabel(uiState.activeInputScheme, appLang, settings.doublePinyinLayout)
                    }
                } else {
                    shortInputSchemeLabel(uiState.activeInputScheme, settings.doublePinyinLayout, customSchemaName)
                },
                isExpandedActive = uiState.isToolPanelSchemeExpanded,
                onClick = { onAction(ImeUiAction.ToggleToolPanelScheme) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            ToolPanelTile(
                icon = TypeFreeIcons.Sparkles,
                title = tr("文风", "Tone", "文風"),
                subtitle = AppStrings.toneStyleName(activeStyle, appLang),
                onClick = onOpenStylePicker,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        // Row 2: AI 补全 · 实时写入 · 词库 · 设置
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            val localRegistrySnapshot = rememberLocalModelRegistrySnapshot()
            ToolPanelTile(
                icon = TypeFreeIcons.VoicePen,
                title = tr("AI 补全", "AI Complete", "AI 補全"),
                subtitle = resolveCompletionBackendCompactLabel(settings, localRegistrySnapshot),
                isToggleEnabled = settings.aiCompletionEnabled,
                onClick = onToggleAiCompletion,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            ToolPanelTile(
                icon = TypeFreeIcons.VoiceWaveform,
                title = tr("实时写入", "Live Write", "即時寫入"),
                subtitle = if (settings.liveWriteToEditor) tr("已开启", "On", "已開啟") else tr("已关闭", "Off", "已關閉"),
                isToggleEnabled = settings.liveWriteToEditor,
                onClick = onToggleLiveWrite,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            ToolPanelTile(
                icon = TypeFreeIcons.Book,
                title = tr("词库", "Dictionary", "詞庫"),
                subtitle = tr("词典与方案", "Words & Rime", "詞典與方案"),
                onClick = onOpenDictionary,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            ToolPanelTile(
                icon = TypeFreeIcons.Tune,
                title = tr("设置", "Settings", "設定"),
                subtitle = tr("偏好与模型", "AI & Prefs", "偏好與模型"),
                onClick = onOpenSettings,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

private enum class AdjustSubTab {
    HEIGHT,
    LIFT,
    FONT
}

internal object ToolPanelAdjustHelper {
    val LIFT_PRESET_DPS: List<Int> = listOf(0, 12, 24, 36, 48, 56)

    fun isAllDefault(
        heightLevel: Int,
        bottomLiftDp: Int,
        fontScale: CandidateFontScale
    ): Boolean = heightLevel.coerceIn(-2, 2) == 0 &&
        bottomLiftDp.coerceAtLeast(0) == 0 &&
        fontScale == CandidateFontScale.STANDARD

    fun nearestLiftPresetDp(actualDp: Int): Int =
        LIFT_PRESET_DPS.minByOrNull { abs(it - actualDp.coerceIn(0, 56)) } ?: 0

    fun heightShortLabel(level: Int, lang: AppLang): String = when (level.coerceIn(-2, 2)) {
        -2 -> AppStrings.pick(lang, "紧凑", "Compact", "緊湊")
        -1 -> AppStrings.pick(lang, "偏低", "Low", "偏低")
        0 -> AppStrings.pick(lang, "标准", "Std", "標準")
        1 -> AppStrings.pick(lang, "偏高", "Tall", "偏高")
        2 -> AppStrings.pick(lang, "舒适", "Comfort", "舒適")
        else -> AppStrings.pick(lang, "标准", "Std", "標準")
    }

    /**
     * Delta-only compact subtitle guaranteed to stay <= ~52dp across all 90 combinations
     * so the 4-col tool tile never truncates into `...`.
     */
    fun formatCompactSubtitle(
        heightLevel: Int,
        bottomLiftDp: Int,
        fontScale: CandidateFontScale,
        lang: AppLang
    ): String {
        val lvl = heightLevel.coerceIn(-2, 2)
        val lift = bottomLiftDp.coerceIn(0, 56)
        val hChanged = lvl != 0
        val lChanged = lift > 0
        val fChanged = fontScale != CandidateFontScale.STANDARD
        val changedCount = (if (hChanged) 1 else 0) + (if (lChanged) 1 else 0) + (if (fChanged) 1 else 0)

        if (changedCount == 0) {
            return AppStrings.pick(lang, "默认", "Default", "預設")
        }
        val hLabel = heightShortLabel(lvl, lang)
        if (changedCount == 1) {
            return when {
                hChanged -> hLabel
                lChanged -> AppStrings.pick(lang, "垫高 ${lift}dp", "Lift +${lift}", "墊高 ${lift}dp")
                fontScale == CandidateFontScale.LARGE -> AppStrings.pick(lang, "大字号", "Large", "大字號")
                else -> AppStrings.pick(lang, "超大号", "X-Large", "超大號")
            }
        }
        val fMid = when (fontScale) {
            CandidateFontScale.LARGE -> AppStrings.pick(lang, "大字", "LG", "大字")
            CandidateFontScale.EXTRA_LARGE -> AppStrings.pick(lang, "超大", "XL", "超大")
            CandidateFontScale.STANDARD -> ""
        }
        if (changedCount == 2) {
            return when {
                hChanged && lChanged -> "$hLabel +$lift"
                hChanged && fChanged -> "$hLabel · $fMid"
                else -> "+$lift · $fMid"
            }
        }
        val fTiny = if (fontScale == CandidateFontScale.EXTRA_LARGE) {
            AppStrings.pick(lang, "超", "XL", "超")
        } else {
            AppStrings.pick(lang, "大", "LG", "大")
        }
        return "$hLabel +$lift $fTiny"
    }
}

@Composable
private fun ToolPanelHeightAdjustStrip(
    activeHeightLevel: Int,
    activeBottomLiftDp: Int,
    activeFontScale: CandidateFontScale,
    onSelectHeightLevel: (Int) -> Unit,
    onSelectBottomLiftDp: (Int) -> Unit,
    onSelectFontScale: (CandidateFontScale) -> Unit,
    onResetAll: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val appLang = LocalAppLanguage.current
    val clampedLvl = activeHeightLevel.coerceIn(KeyboardMetrics.MIN_LEVEL, KeyboardMetrics.MAX_LEVEL)
    val clampedLift = activeBottomLiftDp.coerceIn(KeyboardMetrics.MIN_BOTTOM_LIFT_DP, KeyboardMetrics.MAX_BOTTOM_LIFT_DP)
    val isAllDefault = ToolPanelAdjustHelper.isAllDefault(clampedLvl, clampedLift, activeFontScale)

    var subTab by remember {
        mutableStateOf(
            when {
                clampedLvl != 0 -> AdjustSubTab.HEIGHT
                clampedLift > 0 -> AdjustSubTab.LIFT
                activeFontScale != CandidateFontScale.STANDARD -> AdjustSubTab.FONT
                else -> AdjustSubTab.HEIGHT
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(35.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.capsuleTrackBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Left 3-dimension segmented switcher with emerald modified-indicator dots
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(palette.keyCapSecondaryBg)
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.5.dp)
        ) {
            val tabs = listOf(
                Triple(AdjustSubTab.HEIGHT, tr("高度", "H", "高度"), clampedLvl != 0),
                Triple(AdjustSubTab.LIFT, tr("垫高", "Lift", "墊高"), clampedLift > 0),
                Triple(AdjustSubTab.FONT, tr("字号", "Font", "字號"), activeFontScale != CandidateFontScale.STANDARD)
            )
            tabs.forEach { (tab, label, modified) ->
                val active = subTab == tab
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) palette.keyCapBg else Color.Transparent)
                        .clickable { subTab = tab }
                        .padding(horizontal = 5.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = label,
                        color = if (active) palette.keyTextPrimary else palette.keyTextMuted,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    if (modified) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(TypeFreeEmerald)
                        )
                    }
                }
            }
        }

        // Middle options per active sub-tab
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            when (subTab) {
                AdjustSubTab.HEIGHT -> {
                    KeyboardMetrics.ALL_LEVELS.forEach { lvl ->
                        val selected = clampedLvl == lvl
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (selected) palette.primaryMicSolid else Color.Transparent)
                                .clickable { onSelectHeightLevel(lvl) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = ToolPanelAdjustHelper.heightShortLabel(lvl, appLang),
                                color = if (selected) palette.onPrimaryMic else palette.keyTextPrimary,
                                fontSize = 10.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }

                AdjustSubTab.LIFT -> {
                    val highlightedPreset = ToolPanelAdjustHelper.nearestLiftPresetDp(clampedLift)
                    ToolPanelAdjustHelper.LIFT_PRESET_DPS.forEach { dpVal ->
                        val selected = highlightedPreset == dpVal
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (selected) palette.primaryMicSolid else Color.Transparent)
                                .clickable { onSelectBottomLiftDp(dpVal) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (dpVal == 56) "56" else "$dpVal",
                                color = if (selected) palette.onPrimaryMic else palette.keyTextPrimary,
                                fontSize = 10.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }

                AdjustSubTab.FONT -> {
                    CandidateFontScale.entries.forEach { scale ->
                        val selected = activeFontScale == scale
                        val label = when (scale) {
                            CandidateFontScale.STANDARD -> tr("标准", "Std", "標準")
                            CandidateFontScale.LARGE -> tr("偏大", "Large", "偏大")
                            CandidateFontScale.EXTRA_LARGE -> tr("超大", "X-Large", "超大")
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (selected) palette.primaryMicSolid else Color.Transparent)
                                .clickable { onSelectFontScale(scale) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (selected) palette.onPrimaryMic else palette.keyTextPrimary,
                                fontSize = 10.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }
            }
        }

        // Right 1-Tap Reset All button
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(7.dp))
                .background(palette.keyCapBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(7.dp))
                .clickable(enabled = !isAllDefault, onClick = onResetAll)
                .padding(horizontal = 5.5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = tr("重置", "Reset", "重置"),
                color = if (isAllDefault) palette.keyTextMuted.copy(alpha = 0.45f) else palette.keyTextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }
}

@Composable
private fun ToolPanelTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isToggleEnabled: Boolean = false,
    isExpandedActive: Boolean = false,
    badgeDot: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val s = metrics.elementScale

    val bgColor = when {
        isToggleEnabled -> TypeFreeEmerald.copy(alpha = if (palette.isDark) 0.16f else 0.12f)
        isExpandedActive -> palette.capsuleSelectedBg
        else -> palette.keyCapBg
    }
    val borderColor = when {
        isToggleEnabled -> TypeFreeEmerald.copy(alpha = 0.78f)
        isExpandedActive -> palette.keyTextPrimary.copy(alpha = 0.42f)
        else -> palette.keyBorder
    }
    val borderWidth = if (isToggleEnabled || isExpandedActive) 1.25.dp else 1.dp
    val iconTint = when {
        isToggleEnabled -> if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
        else -> palette.keyTextPrimary
    }
    val subtitleColor = when {
        isToggleEnabled -> if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
        else -> palette.keyTextMuted
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = (5f * s).dp),
        contentAlignment = Alignment.Center
    ) {
        if (badgeDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(TypeFreeEmerald)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size((18f * s).dp)
            )
            Spacer(modifier = Modifier.height((3f * s).dp))
            Text(
                text = title,
                color = palette.keyTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = subtitleColor,
                fontSize = 10.5.sp,
                fontWeight = if (isToggleEnabled) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

object ToolPanelSchemeHelper {
    fun visiblePrimarySchemes(
        hasCustomSchema: Boolean,
        includeBuiltinFlypyTable: Boolean = com.typefree.ime.BuildConfig.USE_PRIVATE_DICT,
        currentScheme: InputScheme? = null
    ): List<InputScheme> = buildList {
        add(InputScheme.RIME_PINYIN)
        add(InputScheme.RIME_T9_PINYIN)
        add(InputScheme.RIME_DOUBLE_PINYIN_FLYPY)
        if (includeBuiltinFlypyTable || currentScheme == InputScheme.FLYPY_TABLE) {
            add(InputScheme.FLYPY_TABLE)
        }
        if (hasCustomSchema || currentScheme == InputScheme.RIME_CUSTOM) {
            add(InputScheme.RIME_CUSTOM)
        }
    }
}

@Composable
private fun ToolPanelSchemeQuickStrip(
    activeScheme: InputScheme,
    activeDoublePinyinLayout: com.typefree.ime.data.DoublePinyinLayout,
    isDoublePinyinSubOpen: Boolean,
    hasCustomSchema: Boolean,
    customSchemaName: String?,
    onSelectScheme: (InputScheme) -> Unit,
    onSelectDoublePinyinLayout: (com.typefree.ime.data.DoublePinyinLayout) -> Unit,
    onToggleDoublePinyinSub: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val appLang = LocalAppLanguage.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.capsuleTrackBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 3.dp, vertical = 2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
    ) {
        if (!isDoublePinyinSubOpen) {
            val schemes = ToolPanelSchemeHelper.visiblePrimarySchemes(
                hasCustomSchema = hasCustomSchema,
                includeBuiltinFlypyTable = com.typefree.ime.BuildConfig.USE_PRIVATE_DICT,
                currentScheme = activeScheme
            )
            schemes.forEach { scheme ->
                val selected = activeScheme == scheme
                val dpShort = AppStrings.doublePinyinLayoutShortLabel(activeDoublePinyinLayout, appLang)
                val label = when (scheme) {
                    InputScheme.RIME_PINYIN -> tr("全拼", "Pinyin", "全拼")
                    InputScheme.RIME_T9_PINYIN -> tr("九键", "9-Key", "九鍵")
                    InputScheme.RIME_DOUBLE_PINYIN_FLYPY -> tr("${dpShort}双拼 ▾", "$dpShort ▾", "${dpShort}雙拼 ▾")
                    InputScheme.FLYPY_TABLE -> tr("小鹤音形", "Flypy", "小鶴音形")
                    InputScheme.RIME_CUSTOM -> customSchemaName?.trim()?.takeIf { it.isNotEmpty() }
                        ?: tr("自定义", "Custom", "自訂")
                }
                Box(
                    modifier = Modifier
                        .weight(if (scheme == InputScheme.RIME_DOUBLE_PINYIN_FLYPY) 1.28f else 1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) palette.primaryMicSolid else Color.Transparent)
                        .clickable { onSelectScheme(scheme) }
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) palette.onPrimaryMic else palette.keyTextPrimary,
                        fontSize = 10.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(7.dp))
                    .background(palette.keyCapBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(7.dp))
                    .clickable(onClick = onToggleDoublePinyinSub)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "←",
                    color = palette.keyTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }

            com.typefree.ime.data.DoublePinyinLayout.entries.forEach { layout ->
                val selected = activeScheme == InputScheme.RIME_DOUBLE_PINYIN_FLYPY && activeDoublePinyinLayout == layout
                val shortName = AppStrings.doublePinyinLayoutShortLabel(layout, appLang)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) TypeFreeEmerald else Color.Transparent)
                        .clickable { onSelectDoublePinyinLayout(layout) }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = shortName,
                        color = if (selected) Color.White else palette.keyTextPrimary,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }
    }
}

fun shortInputSchemeLabel(
    scheme: InputScheme,
    doublePinyinLayout: com.typefree.ime.data.DoublePinyinLayout = com.typefree.ime.data.DoublePinyinLayout.FLYPY,
    customSchemaName: String? = null
): String = when (scheme) {
    InputScheme.RIME_PINYIN -> "雾凇全拼"
    InputScheme.RIME_T9_PINYIN -> "九键拼音"
    InputScheme.RIME_DOUBLE_PINYIN_FLYPY -> doublePinyinLayout.labelZhCn
    InputScheme.FLYPY_TABLE -> "小鹤音形"
    InputScheme.RIME_CUSTOM -> customSchemaName?.trim()?.takeIf { it.isNotEmpty() } ?: "自定义方案"
}

fun nextCycledInputScheme(
    current: InputScheme,
    hasCustomSchema: Boolean = false,
    includeBuiltinFlypyTable: Boolean = com.typefree.ime.BuildConfig.USE_PRIVATE_DICT
): InputScheme {
    val list = ToolPanelSchemeHelper.visiblePrimarySchemes(
        hasCustomSchema = hasCustomSchema,
        includeBuiltinFlypyTable = includeBuiltinFlypyTable,
        currentScheme = null
    )
    if (list.isEmpty()) return InputScheme.RIME_PINYIN
    val idx = list.indexOf(current)
    return if (idx < 0) list.first() else list[(idx + 1) % list.size]
}
