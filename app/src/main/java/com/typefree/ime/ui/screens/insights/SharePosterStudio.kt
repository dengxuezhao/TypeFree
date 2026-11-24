package com.typefree.ime.ui.screens.insights

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.typefree.ime.data.stats.InsightsSnapshot
import com.typefree.ime.data.stats.SharePosterModule
import com.typefree.ime.data.stats.SharePosterPreset
import com.typefree.ime.data.stats.SharePosterSkin
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ui.components.ContinuousRoundedCornerShape
import com.typefree.ime.ui.components.SmoothDisclosure
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.components.horizontalFadeEdge
import com.typefree.ime.ui.components.springPressScale
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeEmeraldLight
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
private data class PosterPalette(
    val bgTop: Color,
    val bgBottom: Color,
    val cardBg: Color,
    val chipBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentSoft: Color,
    val keyBar: Color,
    val amberAccent: Color,
    val hairline: Color,
    val heatEmpty: Color,
    val heatPeak: Color,
    val swatchFill: Color,
    val swatchRing: Color
)

private fun paletteForSkin(skin: SharePosterSkin): PosterPalette = when (skin) {
    SharePosterSkin.OBSIDIAN_DARK -> PosterPalette(
        bgTop = Color(0xFF0E1015),
        bgBottom = Color(0xFF171922),
        cardBg = Color(0xFF1E212B),
        chipBg = Color(0xFF262936),
        textPrimary = Color(0xFFF6F7FA),
        textSecondary = Color(0xFF9C9FA9),
        accent = TypeFreeEmeraldLight,
        accentSoft = Color(0xFF10B981).copy(alpha = 0.16f),
        keyBar = Color(0xFFD5D7E0),
        amberAccent = TypeFreeAmber,
        hairline = Color.White.copy(alpha = 0.10f),
        heatEmpty = Color(0xFF282B36),
        heatPeak = Color(0xFFF4F5F8),
        swatchFill = Color(0xFF171921),
        swatchRing = Color(0xFF4B4E5C)
    )
    SharePosterSkin.PAPER_LIGHT -> PosterPalette(
        bgTop = Color(0xFFFAF9F6),
        bgBottom = Color(0xFFF1EFEA),
        cardBg = Color(0xFFFFFFFF),
        chipBg = Color(0xFFF3F3F5),
        textPrimary = Color(0xFF17171B),
        textSecondary = Color(0xFF686870),
        accent = Color(0xFF059669),
        accentSoft = Color(0xFF10B981).copy(alpha = 0.12f),
        keyBar = Color(0xFF27272A),
        amberAccent = Color(0xFFD97706),
        hairline = Color.Black.copy(alpha = 0.08f),
        heatEmpty = Color(0xFFE7E6E2),
        heatPeak = Color(0xFF18181B),
        swatchFill = Color(0xFFF8F7F4),
        swatchRing = Color(0xFFC8C6C0)
    )
    SharePosterSkin.EMERALD_NIGHT -> PosterPalette(
        bgTop = Color(0xFF061612),
        bgBottom = Color(0xFF0D261F),
        cardBg = Color(0xFF113128),
        chipBg = Color(0xFF173D32),
        textPrimary = Color(0xFFECFDF5),
        textSecondary = Color(0xFF8BB8A8),
        accent = TypeFreeEmeraldLight,
        accentSoft = Color(0xFF34D399).copy(alpha = 0.18f),
        keyBar = Color(0xFFA7F3D0),
        amberAccent = Color(0xFFFBBF24),
        hairline = Color(0xFF34D399).copy(alpha = 0.18f),
        heatEmpty = Color(0xFF16382E),
        heatPeak = Color(0xFFECFDF5),
        swatchFill = Color(0xFF0C2A21),
        swatchRing = TypeFreeEmeraldLight
    )
}

private fun localizedPosterSkinLabel(skin: SharePosterSkin, lang: AppLang): String {
    return when (skin) {
        SharePosterSkin.OBSIDIAN_DARK -> AppStrings.pick(lang, "曜石黑", "Obsidian Dark", "曜石黑")
        SharePosterSkin.PAPER_LIGHT -> AppStrings.pick(lang, "纸感白", "Paper Light", "紙感白")
        SharePosterSkin.EMERALD_NIGHT -> AppStrings.pick(lang, "暗夜翠", "Emerald Night", "暗夜翠")
    }
}

private fun localizedPosterPresetLabel(preset: SharePosterPreset, lang: AppLang): String {
    return when (preset) {
        SharePosterPreset.EFFICIENCY -> AppStrings.pick(lang, "效率战报", "Efficiency", "效率戰報")
        SharePosterPreset.ANNUAL -> AppStrings.pick(lang, "年度手账", "Annual", "年度手賬")
        SharePosterPreset.KEYBOARD -> AppStrings.pick(lang, "键盘战报", "Keyboard", "鍵盤戰報")
        SharePosterPreset.PERSONA -> AppStrings.pick(lang, "沟通人格", "Persona", "溝通人格")
    }
}

private fun localizedPosterModuleChip(module: SharePosterModule, lang: AppLang): String {
    return when (module) {
        SharePosterModule.HERO -> AppStrings.pick(lang, "大字账单", "Hero Stats", "大字帳單")
        SharePosterModule.DUAL_TRACK -> AppStrings.pick(lang, "双轨占比", "Dual-Track", "雙軌佔比")
        SharePosterModule.SHORTCUTS -> AppStrings.pick(lang, "省键账单", "Key Savings", "省鍵帳單")
        SharePosterModule.HEATMAP -> AppStrings.pick(lang, "热力足迹", "Heatmap", "熱力足跡")
        SharePosterModule.MONTHLY_TREND -> AppStrings.pick(lang, "月度走势", "12M Trend", "月度走勢")
        SharePosterModule.RHYTHM_24H -> AppStrings.pick(lang, "24h 节律", "24h Rhythm", "24h 節律")
        SharePosterModule.JEV_DNA -> AppStrings.pick(lang, "沟通 DNA", "Style DNA", "溝通 DNA")
        SharePosterModule.SCENES -> AppStrings.pick(lang, "场景分布", "Top Scenes", "場景分佈")
        SharePosterModule.MILESTONES -> AppStrings.pick(lang, "离线印记", "Milestones", "離線印記")
    }
}

/**
 * Full-screen customizable Share Poster Studio overlay.
 * Privacy guarantee: never renders raw typed text, word chains, or contact names.
 */
@Composable
fun SharePosterStudioDialog(
    snapshot: InsightsSnapshot,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    val graphicsLayer = rememberGraphicsLayer()

    var selectedSkin by remember { mutableStateOf(SharePosterSkin.OBSIDIAN_DARK) }
    var selectedModules by remember {
        mutableStateOf(SharePosterPreset.EFFICIENCY.modules.toSet())
    }
    var hideAppNames by remember { mutableStateOf(true) }
    var isExporting by remember { mutableStateOf(false) }

    val orderedModules = remember(selectedModules) {
        SharePosterModule.entries.filter { it in selectedModules }
    }

    Dialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        CompositionLocalProvider(LocalAppLanguage provides appLang) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    // Top bar: Cancel | Title | 3 Skin Circles
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = { if (!isExporting) onDismiss() }
                        ) {
                            Text(
                                text = tr("取消", "Cancel", "取消"),
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = tr("定制表达手账", "Share Poster Studio", "定製表達手賬"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            SharePosterSkin.entries.forEach { skin ->
                                val skinPalette = remember(skin) { paletteForSkin(skin) }
                                val isSelected = selectedSkin == skin
                                val ringColor by animateColorAsState(
                                    targetValue = if (isSelected) TypeFreeEmerald else skinPalette.swatchRing,
                                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                                    label = "skinRing_${skin.name}"
                                )
                                val interactionSource = remember { MutableInteractionSource() }
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .springPressScale(0.90f, interactionSource)
                                        .clip(CircleShape)
                                        .border(
                                            width = if (isSelected) 2.2.dp else 1.dp,
                                            color = ringColor,
                                            shape = CircleShape
                                        )
                                        .padding(3.dp)
                                        .clip(CircleShape)
                                        .background(skinPalette.swatchFill)
                                        .clickable(
                                            interactionSource = interactionSource,
                                            indication = null
                                        ) {
                                            selectedSkin = skin
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(TypeFreeEmerald)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    )

                    // Live Scrollable Preview Area with GraphicsLayer Capture
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawWithContent {
                                    graphicsLayer.record {
                                        this@drawWithContent.drawContent()
                                    }
                                    drawLayer(graphicsLayer)
                                }
                        ) {
                            SharePosterCard(
                                snapshot = snapshot,
                                selectedModules = orderedModules,
                                skin = selectedSkin,
                                hideAppNames = hideAppNames
                            )
                        }
                    }

                    // Bottom Fixed Customization & Export Sheet
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        ),
                        shadowElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(11.dp)
                        ) {
                            // Row 1: 4 One-Tap Presets + Count Hint
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalFadeEdge(startFadeWidth = 4.dp, endFadeWidth = 14.dp)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SharePosterPreset.entries.forEach { preset ->
                                        val isPresetActive = selectedModules == preset.modules.toSet()
                                        val pLabel = localizedPosterPresetLabel(preset, appLang)
                                        Surface(
                                            onClick = {
                                                selectedModules = preset.modules.toSet()
                                            },
                                            shape = CircleShape,
                                            color = if (isPresetActive) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                            }
                                        ) {
                                            Text(
                                                text = pLabel,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isPresetActive) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = tr(
                                        "已选 ${selectedModules.size}/${SharePosterModule.entries.size}",
                                        "${selectedModules.size}/${SharePosterModule.entries.size} on",
                                        "已選 ${selectedModules.size}/${SharePosterModule.entries.size}"
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }

                            // Row 2: Horizontal Scrollable Module Toggle Chips
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalFadeEdge(startFadeWidth = 6.dp, endFadeWidth = 18.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SharePosterModule.entries.forEach { module ->
                                    val isChecked = module in selectedModules
                                    val mLabel = localizedPosterModuleChip(module, appLang)
                                    Surface(
                                        onClick = {
                                            if (isChecked) {
                                                if (selectedModules.size <= 1) {
                                                    Toast.makeText(
                                                        context,
                                                        AppStrings.pick(
                                                            appLang,
                                                            "至少保留 1 个手账模块",
                                                            "Keep at least 1 poster module",
                                                            "至少保留 1 個手賬模組"
                                                        ),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    selectedModules = selectedModules - module
                                                }
                                            } else {
                                                selectedModules = selectedModules + module
                                            }
                                        },
                                        shape = ContinuousRoundedCornerShape(12.dp),
                                        color = if (isChecked) {
                                            TypeFreeEmerald.copy(alpha = 0.14f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                        },
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = if (isChecked) {
                                                TypeFreeEmerald.copy(alpha = 0.55f)
                                            } else {
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                            }
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if (isChecked) {
                                                Icon(
                                                    imageVector = Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = TypeFreeEmerald,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                            Text(
                                                text = mLabel,
                                                fontSize = 12.sp,
                                                fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isChecked) {
                                                    MaterialTheme.colorScheme.onSurface
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Conditional Privacy Toggle when SCENES is selected
                            SmoothDisclosure(expanded = SharePosterModule.SCENES in selectedModules) {
                                Surface(
                                    onClick = { hideAppNames = !hideAppNames },
                                    shape = ContinuousRoundedCornerShape(12.dp),
                                    color = if (hideAppNames) {
                                        TypeFreeEmerald.copy(alpha = 0.10f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    },
                                    border = BorderStroke(
                                        width = 0.8.dp,
                                        color = if (hideAppNames) {
                                            TypeFreeEmerald.copy(alpha = 0.45f)
                                        } else {
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                        }
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Lock,
                                                contentDescription = null,
                                                tint = if (hideAppNames) TypeFreeEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = tr(
                                                    "隐藏具体 App 名（仅展示社交/职场等场景）",
                                                    "Hide specific app names (show scene categories only)",
                                                    "隱藏具體 App 名（僅顯示社交/職場等場景）"
                                                ),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (hideAppNames) TypeFreeEmerald else Color.Transparent
                                                )
                                                .border(
                                                    width = 1.4.dp,
                                                    color = if (hideAppNames) TypeFreeEmerald else MaterialTheme.colorScheme.outline,
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (hideAppNames) {
                                                Icon(
                                                    imageVector = Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Row 3: Save to Gallery & Share Poster
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (isExporting) return@OutlinedButton
                                        isExporting = true
                                        scope.launch {
                                            val result = runCatching {
                                                val bmp = capturePosterBitmap(graphicsLayer, appLang)
                                                try {
                                                    savePosterToGallery(context, bmp, appLang)
                                                } finally {
                                                    bmp.recycle()
                                                }
                                            }
                                            isExporting = false
                                            result.onSuccess { message ->
                                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                            }.onFailure { err ->
                                                Toast.makeText(
                                                    context,
                                                    AppStrings.pick(
                                                        appLang,
                                                        "保存失败：${err.message ?: "请稍后重试"}",
                                                        "Save failed: ${err.message ?: "Please try again"}",
                                                        "儲存失敗：${err.message ?: "請稍後重試"}"
                                                    ),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    enabled = !isExporting,
                                    shape = ContinuousRoundedCornerShape(14.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
                                    ),
                                    contentPadding = PaddingValues(vertical = 12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = tr("保存相册", "Save Image", "儲存相簿"),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Button(
                                    onClick = {
                                        if (isExporting) return@Button
                                        isExporting = true
                                        scope.launch {
                                            val result = runCatching {
                                                val bmp = capturePosterBitmap(graphicsLayer, appLang)
                                                val file = try {
                                                    writePosterToShareCache(context, bmp)
                                                } finally {
                                                    bmp.recycle()
                                                }
                                                launchPosterShareIntent(context, file, appLang)
                                            }
                                            isExporting = false
                                            result.onFailure { err ->
                                                Toast.makeText(
                                                    context,
                                                    AppStrings.pick(
                                                        appLang,
                                                        "分享失败：${err.message ?: "请稍后重试"}",
                                                        "Share failed: ${err.message ?: "Please try again"}",
                                                        "分享失敗：${err.message ?: "請稍後重試"}"
                                                    ),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    enabled = !isExporting,
                                    shape = ContinuousRoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    contentPadding = PaddingValues(vertical = 12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isExporting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.Share,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = tr("分享海报", "Share Poster", "分享海報"),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pure-Compose editorial share poster card rendered both live in the Studio preview
 * and offscreen via [GraphicsLayer] for crisp PNG export.
 * Strictly zero raw text, zero word chains, zero contact identifiers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharePosterCard(
    snapshot: InsightsSnapshot,
    selectedModules: List<SharePosterModule>,
    skin: SharePosterSkin,
    hideAppNames: Boolean,
    modifier: Modifier = Modifier
) {
    val appLang = LocalAppLanguage.current
    val palette = remember(skin) { paletteForSkin(skin) }
    val posterShape = ContinuousRoundedCornerShape(24.dp)
    val dateStamp = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.US))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(posterShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(palette.bgTop, palette.bgBottom)
                )
            )
            .border(1.dp, palette.hairline, posterShape)
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Poster Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(ContinuousRoundedCornerShape(9.dp))
                            .background(palette.accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.LogoMark,
                            contentDescription = null,
                            tint = Color(0xFF061612),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Column {
                        Text(
                            text = tr("TypeFree 表达手账", "TypeFree Expression Journal", "TypeFree 表達手賬"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = palette.textPrimary,
                            letterSpacing = (-0.3).sp
                        )
                        Text(
                            text = dateStamp,
                            fontSize = 10.sp,
                            color = palette.textSecondary
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(palette.accentSoft)
                        .border(0.8.dp, palette.accent.copy(alpha = 0.4f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = localizedPosterRangeBadge(snapshot.range, appLang),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.accent,
                        maxLines = 1
                    )
                }
            }

            // Selected Modules
            selectedModules.forEach { module ->
                when (module) {
                    SharePosterModule.HERO -> PosterHeroModule(snapshot, palette)
                    SharePosterModule.DUAL_TRACK -> PosterDualTrackModule(snapshot, palette)
                    SharePosterModule.SHORTCUTS -> PosterShortcutsModule(snapshot, palette)
                    SharePosterModule.HEATMAP -> PosterHeatmapModule(snapshot, palette)
                    SharePosterModule.MONTHLY_TREND -> PosterMonthlyTrendModule(snapshot, palette)
                    SharePosterModule.RHYTHM_24H -> PosterRhythm24hModule(snapshot, palette)
                    SharePosterModule.JEV_DNA -> PosterJevDnaModule(snapshot, palette)
                    SharePosterModule.SCENES -> PosterScenesModule(snapshot, palette, hideAppNames)
                    SharePosterModule.MILESTONES -> PosterMilestonesModule(snapshot, palette)
                }
            }

            // Editorial Footer
            HorizontalDivider(thickness = 0.6.dp, color = palette.hairline)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr(
                        "Speak, don't type. · 端侧零正文统计",
                        "Speak, don't type. · Zero-Text Local Stats",
                        "Speak, don't type. · 端側零正文統計"
                    ),
                    fontSize = 10.sp,
                    color = palette.textSecondary
                )
                Text(
                    text = "TypeFree",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = palette.textPrimary.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun PosterSectionCard(
    palette: PosterPalette,
    content: @Composable () -> Unit
) {
    val shape = ContinuousRoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.cardBg)
            .border(0.6.dp, palette.hairline, shape)
            .padding(14.dp)
    ) {
        content()
    }
}

@Composable
private fun PosterHeroModule(
    snapshot: InsightsSnapshot,
    palette: PosterPalette
) {
    val appLang = LocalAppLanguage.current
    val badgeLbl = localizedPosterRangeBadge(snapshot.range, appLang)
    PosterSectionCard(palette) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("${badgeLbl}总表达", "$badgeLbl Total Output", "${badgeLbl}總表達"),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textSecondary
                )
                if (snapshot.streakDays > 0) {
                    Text(
                        text = tr(
                            "连续活跃 ${snapshot.streakDays} 天",
                            "${snapshot.streakDays}d Streak",
                            "連續活躍 ${snapshot.streakDays} 天"
                        ),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.accent,
                        maxLines = 1
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = formatInsightsCompactChars(snapshot.totalChars, appLang),
                    fontSize = 36.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1.0).sp,
                    color = palette.textPrimary
                )
                Text(
                    text = tr("字", "chars", "字"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(palette.accentSoft)
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = tr(
                            "省下 ${snapshot.savedMinutes} 分钟",
                            "Saved ${snapshot.savedMinutes} min",
                            "省下 ${snapshot.savedMinutes} 分鐘"
                        ),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.accent,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if (snapshot.hasMeasuredWpm) {
                val mult = String.format(Locale.US, "%.1f", snapshot.speedMultiplier)
                Text(
                    text = tr(
                        "实测口述 ${snapshot.averageWpm} 字/分 · 约 ${mult}× 拇指打字",
                        "Measured ${snapshot.averageWpm} CPM · ~${mult}× thumb typing",
                        "實測口述 ${snapshot.averageWpm} 字/分 · 約 ${mult}× 拇指打字"
                    ),
                    fontSize = 11.5.sp,
                    color = palette.textSecondary
                )
            }

            val metaphor = localizedMetaphorLine(snapshot, appLang)
            if (metaphor.isNotBlank()) {
                Text(
                    text = metaphor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textPrimary.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun PosterDualTrackModule(
    snapshot: InsightsSnapshot,
    palette: PosterPalette
) {
    val appLang = LocalAppLanguage.current
    val v = snapshot.voiceChars.coerceAtLeast(0)
    val k = snapshot.keyChars.coerceAtLeast(0)
    val total = v + k
    val voicePct = if (total > 0) ((v * 100f) / total).toInt().coerceIn(0, 100) else 0
    val keyPct = if (total > 0) (100 - voicePct).coerceIn(0, 100) else 0
    val vStr = formatInsightsCompactChars(v, appLang)
    val kStr = formatInsightsCompactChars(k, appLang)

    PosterSectionCard(palette) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = tr("语音 $voicePct% · $vStr 字", "Voice $voicePct% · $vStr", "語音 $voicePct% · $vStr 字"),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.accent,
                    maxLines = 1
                )
                Text(
                    text = tr("键盘 $keyPct% · $kStr 字", "Keyboard $keyPct% · $kStr", "鍵盤 $keyPct% · $kStr 字"),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    maxLines = 1
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
            ) {
                val radius = CornerRadius(size.height / 2f, size.height / 2f)
                if (total <= 0) {
                    drawRoundRect(color = palette.heatEmpty, size = size, cornerRadius = radius)
                    return@Canvas
                }
                val gap = 2.5.dp.toPx()
                val frac = v.toFloat() / total.toFloat()
                when {
                    v > 0 && k == 0 -> drawRoundRect(color = palette.accent, size = size, cornerRadius = radius)
                    k > 0 && v == 0 -> drawRoundRect(color = palette.keyBar, size = size, cornerRadius = radius)
                    else -> {
                        val minSeg = 8.dp.toPx().coerceAtMost(size.width * 0.25f)
                        val usable = (size.width - gap).coerceAtLeast(minSeg * 2f)
                        val vW = (usable * frac).coerceIn(minSeg, usable - minSeg)
                        drawRoundRect(
                            color = palette.accent,
                            topLeft = Offset.Zero,
                            size = Size(vW, size.height),
                            cornerRadius = radius
                        )
                        drawRoundRect(
                            color = palette.keyBar,
                            topLeft = Offset(vW + gap, 0f),
                            size = Size(usable - vW, size.height),
                            cornerRadius = radius
                        )
                    }
                }
            }

            if (snapshot.schemeShares.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    snapshot.schemeShares.take(3).forEach { scheme ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.chipBg)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${localizedSchemeShareName(scheme, appLang)} ${scheme.percent}%",
                                fontSize = 10.5.sp,
                                color = palette.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterShortcutsModule(
    snapshot: InsightsSnapshot,
    palette: PosterPalette
) {
    val appLang = LocalAppLanguage.current
    PosterSectionCard(palette) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = tr("少敲键效率账单", "Keystroke Savings", "少敲鍵效率帳單"),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${formatInsightsCompactChars(snapshot.snippetSavedChars, appLang)} ${tr("字", "chars", "字")}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = palette.textPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = tr("快捷短语 · ${snapshot.snippetHits} 次", "Snippets · ${snapshot.snippetHits} hits", "快捷短語 · ${snapshot.snippetHits} 次"),
                        fontSize = 10.5.sp,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${formatInsightsCompactChars(snapshot.predictionSavedKeys, appLang)} ${tr("键", "keys", "鍵")}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = palette.textPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = tr("L2 联想 · ${snapshot.predictionHits} 次", "L2 Predict · ${snapshot.predictionHits} uses", "L2 聯想 · ${snapshot.predictionHits} 次"),
                        fontSize = 10.5.sp,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (snapshot.helpWriteLeverage > 1.0f) {
                    Column(modifier = Modifier.weight(0.85f)) {
                        Text(
                            text = "${String.format(Locale.US, "%.1f", snapshot.helpWriteLeverage)}×",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = palette.accent,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = tr("帮我写杠杆", "Help Write", "幫我寫槓桿"),
                            fontSize = 10.5.sp,
                            color = palette.textSecondary,
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
private fun PosterHeatmapModule(
    snapshot: InsightsSnapshot,
    palette: PosterPalette
) {
    val cells = snapshot.heatmapDays
    val columns = if (cells.size <= 28) 7 else 14
    val rows = remember(cells, columns) { cells.chunked(columns) }

    fun heatColor(level: Int): Color = when (level.coerceIn(0, 4)) {
        0 -> palette.heatEmpty
        1 -> palette.accent.copy(alpha = 0.28f)
        2 -> palette.accent.copy(alpha = 0.56f)
        3 -> palette.accent
        else -> palette.heatPeak
    }

    PosterSectionCard(palette) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = tr("陪伴热力足迹", "Activity Heatmap", "陪伴熱力足跡"),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textSecondary
                )
                Text(
                    text = tr(
                        "累计活跃 ${snapshot.lifetimeActiveDays} 天",
                        "${snapshot.lifetimeActiveDays}d Active",
                        "累計活躍 ${snapshot.lifetimeActiveDays} 天"
                    ),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.accent
                )
            }

            if (rows.isEmpty()) {
                Text(
                    text = tr("暂无足迹记录", "No activity yet", "尚無足跡記錄"),
                    fontSize = 11.sp,
                    color = palette.textSecondary
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            row.forEach { cell ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(heatColor(cell.intensityLevel))
                                )
                            }
                            if (row.size < columns) {
                                repeat(columns - row.size) {
                                    Spacer(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterMonthlyTrendModule(
    snapshot: InsightsSnapshot,
    palette: PosterPalette
) {
    val points = snapshot.monthlyTrend
    val maxTotal = remember(points) {
        points.maxOfOrNull { it.totalChars }?.coerceAtLeast(1) ?: 1
    }

    PosterSectionCard(palette) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = tr("12 月成长走势", "12-Month Trend", "12 月成長走勢"),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textSecondary
                )
                Text(
                    text = tr("语音 × 键盘", "Voice × Key", "語音 × 鍵盤"),
                    fontSize = 10.5.sp,
                    color = palette.textSecondary
                )
            }

            if (points.isEmpty()) {
                Text(
                    text = tr("暂无月度数据", "No monthly data", "尚無月度資料"),
                    fontSize = 11.sp,
                    color = palette.textSecondary
                )
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                ) {
                    if (size.width <= 1f || size.height <= 1f) return@Canvas
                    val slotW = size.width / points.size
                    val barW = (slotW * 0.54f).coerceIn(4.dp.toPx(), 11.dp.toPx()).coerceAtMost(slotW)
                    val corner = CornerRadius(barW / 2f, barW / 2f)
                    val minBarH = 6.dp.toPx().coerceAtMost(size.height)

                    points.forEachIndexed { idx, p ->
                        val left = idx * slotW + (slotW - barW) / 2f
                        if (p.isFuture || p.totalChars <= 0) {
                            val stub = 3.dp.toPx().coerceAtMost(size.height)
                            drawRoundRect(
                                color = palette.heatEmpty,
                                topLeft = Offset(left, size.height - stub),
                                size = Size(barW, stub),
                                cornerRadius = corner
                            )
                            return@forEachIndexed
                        }
                        val totalH = (size.height * (p.totalChars.toFloat() / maxTotal.toFloat()))
                            .coerceIn(minBarH, size.height)
                        val vShare = p.voiceChars.toFloat() / p.totalChars.toFloat()
                        val vH = if (p.voiceChars > 0) (totalH * vShare).coerceAtLeast(3.dp.toPx()) else 0f
                        val kH = if (p.keyChars > 0) (totalH - vH).coerceAtLeast(3.dp.toPx()) else 0f
                        if (kH > 0f) {
                            drawRoundRect(
                                color = palette.keyBar.copy(alpha = 0.65f),
                                topLeft = Offset(left, size.height - kH.coerceAtMost(size.height)),
                                size = Size(barW, kH.coerceAtMost(size.height)),
                                cornerRadius = corner
                            )
                        }
                        if (vH > 0f) {
                            val gap = if (kH > 0f) 1.2.dp.toPx() else 0f
                            val topY = (size.height - kH - gap - vH).coerceAtLeast(0f)
                            drawRoundRect(
                                color = palette.accent,
                                topLeft = Offset(left, topY),
                                size = Size(barW, vH.coerceAtMost((size.height - kH - gap).coerceAtLeast(3.dp.toPx()))),
                                cornerRadius = corner
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    points.forEach { p ->
                        Text(
                            text = "${p.month}",
                            fontSize = 9.sp,
                            color = palette.textSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterRhythm24hModule(
    snapshot: InsightsSnapshot,
    palette: PosterPalette
) {
    val appLang = LocalAppLanguage.current
    val safeVoice = remember(snapshot.hourlyVoiceChars) {
        List(24) { idx -> snapshot.hourlyVoiceChars.getOrElse(idx) { 0 }.coerceAtLeast(0) }
    }
    val safeKey = remember(snapshot.hourlyKeyChars) {
        List(24) { idx -> snapshot.hourlyKeyChars.getOrElse(idx) { 0 }.coerceAtLeast(0) }
    }
    val maxTotal = remember(safeVoice, safeKey) {
        (0 until 24).maxOfOrNull { safeVoice[it] + safeKey[it] }?.coerceAtLeast(1) ?: 1
    }
    val headline = remember(snapshot, appLang) {
        localizedRhythmHeadline(snapshot, appLang)
    }

    PosterSectionCard(palette) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("24h 双轨作息节律", "24h Activity Rhythm", "24h 雙軌作息節律"),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textSecondary
                )
                if (headline.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = headline,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                if (size.width <= 1f || size.height <= 1f) return@Canvas
                val slotW = size.width / 24f
                val barW = (slotW * 0.56f).coerceIn(3.dp.toPx(), 7.dp.toPx()).coerceAtMost(slotW)
                val corner = CornerRadius(barW / 2f, barW / 2f)
                val minBarH = 5.dp.toPx().coerceAtMost(size.height)

                for (h in 0 until 24) {
                    val v = safeVoice[h]
                    val k = safeKey[h]
                    val total = v + k
                    val left = h * slotW + (slotW - barW) / 2f
                    if (total <= 0) {
                        val stub = 2.5.dp.toPx().coerceAtMost(size.height)
                        drawRoundRect(
                            color = palette.heatEmpty,
                            topLeft = Offset(left, size.height - stub),
                            size = Size(barW, stub),
                            cornerRadius = corner
                        )
                        continue
                    }
                    val totalH = (size.height * (total.toFloat() / maxTotal.toFloat()))
                        .coerceIn(minBarH, size.height)
                    val vH = if (v > 0) (totalH * (v.toFloat() / total.toFloat())).coerceAtLeast(2.5.dp.toPx()) else 0f
                    val kH = if (k > 0) (totalH - vH).coerceAtLeast(2.5.dp.toPx()) else 0f
                    if (kH > 0f) {
                        drawRoundRect(
                            color = palette.keyBar.copy(alpha = 0.65f),
                            topLeft = Offset(left, size.height - kH.coerceAtMost(size.height)),
                            size = Size(barW, kH.coerceAtMost(size.height)),
                            cornerRadius = corner
                        )
                    }
                    if (vH > 0f) {
                        val gap = if (kH > 0f) 1.dp.toPx() else 0f
                        val topY = (size.height - kH - gap - vH).coerceAtLeast(0f)
                        drawRoundRect(
                            color = palette.accent,
                            topLeft = Offset(left, topY),
                            size = Size(barW, vH.coerceAtMost((size.height - kH - gap).coerceAtLeast(2.5.dp.toPx()))),
                            cornerRadius = corner
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("00:00", "06:00", "12:00", "18:00", "23:00").forEach { tick ->
                    Text(
                        text = tick,
                        fontSize = 9.sp,
                        color = palette.textSecondary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PosterJevDnaModule(
    snapshot: InsightsSnapshot,
    palette: PosterPalette
) {
    val appLang = LocalAppLanguage.current
    PosterSectionCard(palette) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("✦ Jev 沟通语体 DNA", "✦ Jev Style DNA", "✦ Jev 溝通語體 DNA"),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textSecondary
                )
                if (snapshot.jevTotalInteractions > 0) {
                    Text(
                        text = tr(
                            "${snapshot.jevTotalInteractions} 次互动沉淀",
                            "${snapshot.jevTotalInteractions} interactions",
                            "${snapshot.jevTotalInteractions} 次互動沉澱"
                        ),
                        fontSize = 10.5.sp,
                        color = palette.accent
                    )
                }
            }

            if (snapshot.jevDnaItems.isEmpty()) {
                Text(
                    text = tr("端侧语体模型观察积累中", "Learning your style DNA locally…", "端側語體模型觀察累積中"),
                    fontSize = 11.5.sp,
                    color = palette.textSecondary
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    snapshot.jevDnaItems.take(5).forEach { dna ->
                        val badgeColor = if (dna.isActive) palette.accent else palette.amberAccent
                        val dnaTitle = localizedDnaTitle(dna.key, appLang)
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(badgeColor.copy(alpha = 0.16f))
                                .border(0.6.dp, badgeColor.copy(alpha = 0.45f), CircleShape)
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (dna.isActive) {
                                    "✓ $dnaTitle"
                                } else {
                                    tr("$dnaTitle · 观察中", "$dnaTitle · Learning", "$dnaTitle · 觀察中")
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                        }
                    }
                }
            }

            snapshot.jevAxisTendency?.let { tendency ->
                val labels = localizedAxisLabels(tendency.group, appLang)
                val count = minOf(labels.size, tendency.percents.size)
                if (count > 0) {
                    val segColors = listOf(palette.accent, palette.keyBar, palette.amberAccent)
                    val total = (0 until count).sumOf { tendency.percents[it].coerceAtLeast(0) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp)
                        ) {
                            val r = CornerRadius(size.height / 2f, size.height / 2f)
                            val gap = 2.dp.toPx()
                            val usable = (size.width - gap * (count - 1)).coerceAtLeast(1f)
                            var x = 0f
                            for (i in 0 until count) {
                                val pct = tendency.percents[i].coerceAtLeast(0)
                                val frac = if (total > 0) pct.toFloat() / total.toFloat() else 1f / count
                                val w = if (i == count - 1) (size.width - x).coerceAtLeast(0f) else usable * frac
                                if (w > 1f) {
                                    drawRoundRect(
                                        color = segColors[i % segColors.size],
                                        topLeft = Offset(x, 0f),
                                        size = Size(w, size.height),
                                        cornerRadius = r
                                    )
                                    x += w + gap
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            for (i in 0 until count) {
                                Text(
                                    text = "${labels[i]} ${tendency.percents[i]}%",
                                    fontSize = 10.sp,
                                    color = palette.textSecondary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class PosterSceneRow(
    val label: String,
    val percent: Int
)

@Composable
private fun PosterScenesModule(
    snapshot: InsightsSnapshot,
    palette: PosterPalette,
    hideAppNames: Boolean
) {
    val appLang = LocalAppLanguage.current
    val rows = remember(snapshot.topAppScenes, hideAppNames, appLang) {
        if (snapshot.topAppScenes.isEmpty()) {
            emptyList()
        } else if (hideAppNames) {
            val grouped = linkedMapOf<String, Int>()
            snapshot.topAppScenes.forEach { item ->
                val cat = if (item.sceneCategory.isBlank()) {
                    AppStrings.pick(appLang, "常用表达场景", "General Apps", "常用表達場景")
                } else {
                    localizedSceneCategory(item.sceneCategory, appLang)
                }
                grouped[cat] = (grouped[cat] ?: 0) + item.count.coerceAtLeast(1)
            }
            val total = grouped.values.sum().coerceAtLeast(1)
            grouped.entries
                .sortedByDescending { it.value }
                .take(4)
                .map { (cat, count) ->
                    PosterSceneRow(
                        label = cat,
                        percent = ((count * 100f) / total).toInt().coerceIn(1, 100)
                    )
                }
        } else {
            snapshot.topAppScenes.take(4).map { item ->
                val suffix = if (item.sceneCategory.isNotBlank()) {
                    " · ${localizedSceneCategory(item.sceneCategory, appLang)}"
                } else {
                    ""
                }
                PosterSceneRow(
                    label = "${item.appName}$suffix",
                    percent = item.percent.coerceIn(0, 100)
                )
            }
        }
    }

    PosterSectionCard(palette) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = tr("主要表达场景", "Top Scenes", "主要表達場景"),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textSecondary
                )
                if (hideAppNames) {
                    Text(
                        text = tr("已脱敏 App 名", "App Names Hidden", "已去識別化 App 名"),
                        fontSize = 10.sp,
                        color = palette.accent
                    )
                }
            }

            if (rows.isEmpty()) {
                Text(
                    text = tr("暂无场景分布数据", "No scene breakdown yet", "尚無場景分佈資料"),
                    fontSize = 11.sp,
                    color = palette.textSecondary
                )
            } else {
                rows.forEach { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = row.label,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${row.percent}%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textSecondary
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(palette.heatEmpty)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth((row.percent / 100f).coerceIn(0.05f, 1f))
                                    .height(5.dp)
                                    .clip(CircleShape)
                                    .background(palette.accent)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PosterMilestonesModule(
    snapshot: InsightsSnapshot,
    palette: PosterPalette
) {
    val appLang = LocalAppLanguage.current
    val unlocked = remember(snapshot.milestones) {
        snapshot.milestones.filter { it.unlocked }
    }

    PosterSectionCard(palette) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (snapshot.hasOfflineSplitData) {
                        tr(
                            "本机离线引擎静默护航 ${snapshot.offlinePercent}% · 0 字节出网",
                            "On-device offline AI handled ${snapshot.offlinePercent}% · 0 bytes sent",
                            "本機離線引擎靜默護航 ${snapshot.offlinePercent}% · 0 位元組出網"
                        )
                    } else {
                        tr(
                            "端侧纯数字累计统计 · 0 正文留存",
                            "100% Local Numeric Counters · Zero Raw Text",
                            "端側純數字累計統計 · 0 正文留存"
                        )
                    },
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary
                )
            }

            if (unlocked.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    unlocked.take(4).forEach { badge ->
                        val (mTitle, _, _) = localizedMilestoneTexts(badge, snapshot, appLang)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(palette.chipBg)
                                .border(0.6.dp, palette.accent.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${badge.glyph} $mTitle",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.textPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun capturePosterBitmap(graphicsLayer: GraphicsLayer, lang: AppLang): Bitmap {
    val imageBitmap = graphicsLayer.toImageBitmap()
    val raw = imageBitmap.asAndroidBitmap()
    return raw.copy(Bitmap.Config.ARGB_8888, false)
        ?: error(AppStrings.pick(lang, "无法生成海报位图", "Failed to capture poster bitmap", "無法產生海報點陣圖"))
}

private suspend fun savePosterToGallery(context: Context, bitmap: Bitmap, lang: AppLang): String =
    withContext(Dispatchers.IO) {
        val filename = "TypeFree_Poster_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/TypeFree"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error(AppStrings.pick(lang, "无法创建相册图片条目", "Cannot create gallery entry", "無法建立相簿圖片項目"))
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        AppStrings.pick(lang, "PNG 编码失败", "PNG encoding failed", "PNG 編碼失敗")
                    }
                } ?: error(AppStrings.pick(lang, "无法写入相册", "Cannot write to gallery", "無法寫入相簿"))
                val doneValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                AppStrings.pick(
                    lang,
                    "已保存到系统相册 Pictures/TypeFree",
                    "Saved to Pictures/TypeFree",
                    "已儲存到系統相簿 Pictures/TypeFree"
                )
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        } else {
            val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: File(context.cacheDir, "shared_posters")
            if (!targetDir.exists()) targetDir.mkdirs()
            val outFile = File(targetDir, filename)
            FileOutputStream(outFile).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    AppStrings.pick(lang, "PNG 编码失败", "PNG encoding failed", "PNG 編碼失敗")
                }
            }
            AppStrings.pick(
                lang,
                "已保存至应用图片目录（可点击「分享海报」发送）",
                "Saved to app pictures folder",
                "已儲存至應用程式圖片目錄（可點擊「分享海報」傳送）"
            )
        }
    }

private suspend fun writePosterToShareCache(context: Context, bitmap: Bitmap): File =
    withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared_posters")
        if (!dir.exists()) dir.mkdirs()
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(5)
            ?.forEach { runCatching { it.delete() } }

        val outFile = File(dir, "typefree_poster_${System.currentTimeMillis()}.png")
        FileOutputStream(outFile).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "PNG encoding failed"
            }
        }
        outFile
    }

private fun launchPosterShareIntent(context: Context, file: File, lang: AppLang) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(
            Intent.EXTRA_SUBJECT,
            AppStrings.pick(lang, "我的 TypeFree 表达手账", "My TypeFree Expression Poster", "我的 TypeFree 表達手賬")
        )
        clipData = ClipData.newRawUri("TypeFree Poster", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(
        shareIntent,
        AppStrings.pick(lang, "分享 TypeFree 表达手账", "Share TypeFree Poster", "分享 TypeFree 表達手賬")
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
