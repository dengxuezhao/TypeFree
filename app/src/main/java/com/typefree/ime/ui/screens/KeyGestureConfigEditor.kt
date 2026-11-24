package com.typefree.ime.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.KeycapBadgeMode
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ime.EditorShortcutAction
import com.typefree.ime.ime.KeyGestureActionType
import com.typefree.ime.ime.KeyGestureBinding
import com.typefree.ime.ime.KeyGestureConfig
import com.typefree.ime.ime.KeyGestureProfile
import com.typefree.ime.ui.theme.TypeFreeEmerald

enum class KeyGestureActionKind(val title: String) {
    COMMIT_TEXT("单符号 / 文本"),
    COMMIT_PAIRED("成对居中符号"),
    UNDO_LAST("撤回上屏"),
    REPEAT_LAST("重复上屏"),
    EDITOR_SHORTCUT("编辑快捷操作"),
    NONE("禁用");

    fun titleFor(lang: AppLang): String = when (this) {
        COMMIT_TEXT -> AppStrings.pick(lang, "单符号 / 文本", "Symbol / Text", "單符號 / 文字")
        COMMIT_PAIRED -> AppStrings.pick(lang, "成对居中符号", "Paired Brackets", "成對置中符號")
        UNDO_LAST -> AppStrings.pick(lang, "撤回上屏", "Undo Commit", "復原上屏")
        REPEAT_LAST -> AppStrings.pick(lang, "重复上屏", "Repeat Last", "重複上屏")
        EDITOR_SHORTCUT -> AppStrings.pick(lang, "编辑快捷操作", "Edit Shortcut", "編輯快捷操作")
        NONE -> AppStrings.pick(lang, "禁用", "Disabled", "停用")
    }

    fun toCoreType(): KeyGestureActionType = when (this) {
        COMMIT_TEXT -> KeyGestureActionType.COMMIT_TEXT
        COMMIT_PAIRED -> KeyGestureActionType.COMMIT_PAIRED
        UNDO_LAST -> KeyGestureActionType.UNDO_LAST
        REPEAT_LAST -> KeyGestureActionType.REPEAT_LAST
        EDITOR_SHORTCUT -> KeyGestureActionType.EDITOR_SHORTCUT
        NONE -> KeyGestureActionType.NONE
    }

    companion object {
        fun fromCoreType(type: KeyGestureActionType): KeyGestureActionKind = when (type) {
            KeyGestureActionType.COMMIT_TEXT -> COMMIT_TEXT
            KeyGestureActionType.COMMIT_PAIRED -> COMMIT_PAIRED
            KeyGestureActionType.UNDO_LAST -> UNDO_LAST
            KeyGestureActionType.REPEAT_LAST -> REPEAT_LAST
            KeyGestureActionType.EDITOR_SHORTCUT -> EDITOR_SHORTCUT
            KeyGestureActionType.NONE -> NONE
        }
    }
}

enum class EditorShortcutOption(val wireName: String, val label: String) {
    SELECT_ALL("SELECT_ALL", "全选"),
    CUT("CUT", "剪切"),
    COPY("COPY", "复制"),
    PASTE("PASTE", "粘贴");

    fun labelFor(lang: AppLang): String = when (this) {
        SELECT_ALL -> AppStrings.pick(lang, "全选", "Select All", "全選")
        CUT -> AppStrings.pick(lang, "剪切", "Cut", "剪下")
        COPY -> AppStrings.pick(lang, "复制", "Copy", "複製")
        PASTE -> AppStrings.pick(lang, "粘贴", "Paste", "貼上")
    }
}

data class KeyGestureBindingSpec(
    val type: KeyGestureActionKind,
    val text: String = "",
    val rightText: String = "",
    val label: String = "",
    val editorShortcut: String? = null
) {
    fun toCoreBinding(): KeyGestureBinding = KeyGestureBinding(
        type = type.toCoreType(),
        text = text,
        rightText = rightText,
        label = label,
        editorShortcut = editorShortcut?.let { name ->
            EditorShortcutAction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    )

    fun displayBadge(): String {
        if (type == KeyGestureActionKind.NONE) return "—"
        return toCoreBinding().displayBadge().ifEmpty { "—" }
    }

    fun compactKeycapBadge(): String {
        if (type == KeyGestureActionKind.NONE) return "—"
        return toCoreBinding().compactKeycapBadge().ifEmpty { "—" }
    }

    companion object {
        fun fromCoreBinding(binding: KeyGestureBinding): KeyGestureBindingSpec = KeyGestureBindingSpec(
            type = KeyGestureActionKind.fromCoreType(binding.type),
            text = binding.text,
            rightText = binding.rightText,
            label = binding.label,
            editorShortcut = binding.editorShortcut?.name
        )
    }
}

data class KeyGestureProfileSpec(
    val swipeUp: Map<Char, KeyGestureBindingSpec>,
    val longPress: Map<Char, KeyGestureBindingSpec>
)

/**
 * UI bridge delegating 100% of default profiles and JSON codec directly to [KeyGestureConfig]
 * so settings UI and IME runtime share a single source of truth with zero format drift.
 */
object KeyGestureConfigCodec {

    val QWERTY_ROWS: List<List<Char>> = listOf(
        listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'),
        listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'),
        listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
    )

    val ALL_KEYS: List<Char> = KeyGestureConfig.ALL_QWERTY_KEYS

    private fun coreDefault(): KeyGestureProfile =
        KeyGestureConfig.defaultProfile(isChinese = true, showNumberRow = false)

    private fun fromCoreProfile(profile: KeyGestureProfile): KeyGestureProfileSpec = KeyGestureProfileSpec(
        swipeUp = profile.swipeUpMap.mapValues { (_, v) -> KeyGestureBindingSpec.fromCoreBinding(v) },
        longPress = profile.longPressMap.mapValues { (_, v) -> KeyGestureBindingSpec.fromCoreBinding(v) }
    )

    private fun toCoreProfile(spec: KeyGestureProfileSpec): KeyGestureProfile = KeyGestureProfile(
        swipeUpMap = spec.swipeUp.mapValues { (_, v) -> v.toCoreBinding() },
        longPressMap = spec.longPress.mapValues { (_, v) -> v.toCoreBinding() }
    )

    fun defaultSwipeUpMap(): Map<Char, KeyGestureBindingSpec> = defaultProfile().swipeUp

    fun defaultLongPressMap(): Map<Char, KeyGestureBindingSpec> = defaultProfile().longPress

    fun defaultProfile(): KeyGestureProfileSpec = fromCoreProfile(coreDefault())

    fun fromJsonOrDefault(rawJson: String): KeyGestureProfileSpec {
        return fromCoreProfile(KeyGestureConfig.fromJson(rawJson, coreDefault()))
    }

    fun toJson(profile: KeyGestureProfileSpec): String {
        return KeyGestureConfig.toJson(toCoreProfile(profile))
    }

    fun toDeltaJson(profile: KeyGestureProfileSpec): String {
        return KeyGestureConfig.toDeltaJson(toCoreProfile(profile), coreDefault())
    }

    fun isCustomized(profile: KeyGestureProfileSpec): Boolean {
        val def = defaultProfile()
        return profile.swipeUp != def.swipeUp || profile.longPress != def.longPress
    }
}

private fun localizedPreviewKeycapBadge(spec: KeyGestureBindingSpec, lang: AppLang): String {
    if (spec.type == KeyGestureActionKind.NONE) return "—"
    if (spec.type == KeyGestureActionKind.EDITOR_SHORTCUT) {
        return when (spec.editorShortcut?.uppercase()) {
            "SELECT_ALL" -> AppStrings.pick(lang, "全", "All", "全")
            "CUT" -> AppStrings.pick(lang, "剪", "Cut", "剪")
            "COPY" -> AppStrings.pick(lang, "复", "Cpy", "複")
            "PASTE" -> AppStrings.pick(lang, "粘", "Pst", "貼")
            else -> spec.compactKeycapBadge()
        }
    }
    return when (val raw = spec.compactKeycapBadge()) {
        "全" -> AppStrings.pick(lang, "全", "All", "全")
        "剪" -> AppStrings.pick(lang, "剪", "Cut", "剪")
        "复" -> AppStrings.pick(lang, "复", "Cpy", "複")
        "粘" -> AppStrings.pick(lang, "粘", "Pst", "貼")
        "撤回" -> AppStrings.pick(lang, "撤回", "Undo", "復原")
        "重复" -> AppStrings.pick(lang, "重复", "Redo", "重複")
        "全角" -> AppStrings.pick(lang, "全角", "FW", "全形")
        else -> raw
    }
}

@Composable
fun KeyGestureCustomizerCard(
    repository: AppRepository,
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val profile = remember(settings.customKeyGesturesJson) {
        KeyGestureConfigCodec.fromJsonOrDefault(settings.customKeyGesturesJson)
    }
    val defaults = remember { KeyGestureConfigCodec.defaultProfile() }
    val hasCustomChanges = remember(profile) {
        KeyGestureConfigCodec.isCustomized(profile)
    }
    var editingKey by remember { mutableStateOf<Char?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = tr("26 键上滑 / 长按自定义", "26-Key Swipe-Up & Long-Press", "26 鍵上滑 / 長按自訂"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (hasCustomChanges) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = TypeFreeEmerald.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = tr("已自定义", "Custom", "已自訂"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TypeFreeEmerald,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = tr(
                            "点击下方任意键帽可分别设置「上滑动作（默认快符）」与「长按动作（默认数字/副功能/编辑）」",
                            "Tap any keycap below to customize its Swipe-Up (quick symbols) and Long-Press (digits, shortcuts, edit) actions",
                            "點擊下方任意鍵帽可分別設定「上滑動作（預設快符）」與「長按動作（預設數字/副功能/編輯）」"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (hasCustomChanges) {
                    TextButton(
                        onClick = {
                            repository.updateSettings { it.copy(customKeyGesturesJson = "") }
                            Toast.makeText(
                                context,
                                AppStrings.pick(
                                    appLang,
                                    "已恢复 26 键默认上滑与长按手势",
                                    "Restored default 26-key swipe & long-press gestures",
                                    "已恢復 26 鍵預設上滑與長按手勢"
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(tr("恢复默认", "Reset", "恢復預設"), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Keycap badge display mode selector (Swipe-up / Long-press / Hidden)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = tr("键盘键帽顶部角标", "Keycap Top Badges", "鍵盤鍵帽頂部角標"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    KeycapBadgeMode.entries.forEach { mode ->
                        val selected = settings.keycapBadgeMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = {
                                repository.updateSettings { it.copy(keycapBadgeMode = mode) }
                            },
                            label = {
                                Text(
                                    text = AppStrings.keycapBadgeModeLabel(mode, appLang),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
                Text(
                    text = tr(
                        "选择「隐藏角标」可切换极简纯字母键帽（字母自动居中复原），所有上滑与长按手势仍可正常触发",
                        "Choose \"Hide Badges\" for clean centered letter keycaps while keeping all swipe-up and long-press gestures active",
                        "選擇「隱藏角標」可切換極簡純字母鍵帽（字母自動置中復原），所有上滑與長按手勢仍可正常觸發"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("顶部：↑ 上滑", "Top: ↑ Swipe Up", "頂部：↑ 上滑"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = tr("底部：⏱ 长按", "Bottom: ⏱ Long Press", "底部：⏱ 長按"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 3-Row QWERTY Mini Keyboard Preview (fontScale locked to 1.0f to prevent distortion under large system fonts)
            val baseDensity = LocalDensity.current
            val lockedMiniPreviewDensity = remember(baseDensity.density) {
                Density(density = baseDensity.density, fontScale = 1.0f)
            }
            CompositionLocalProvider(LocalDensity provides lockedMiniPreviewDensity) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        KeyGestureConfigCodec.QWERTY_ROWS.forEachIndexed { rowIdx, rowKeys ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = when (rowIdx) {
                                        1 -> 10.dp
                                        2 -> 24.dp
                                        else -> 0.dp
                                    }),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                rowKeys.forEach { ch ->
                                    val swipeSpec = profile.swipeUp[ch] ?: KeyGestureBindingSpec(KeyGestureActionKind.NONE)
                                    val longSpec = profile.longPress[ch] ?: KeyGestureBindingSpec(KeyGestureActionKind.NONE)
                                    val isKeyModified = swipeSpec != defaults.swipeUp[ch] || longSpec != defaults.longPress[ch]

                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { editingKey = ch },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isKeyModified) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                        border = BorderStroke(
                                            width = if (isKeyModified) 1.2.dp else 0.8.dp,
                                            color = if (isKeyModified) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                            } else {
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                            }
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 2.dp, vertical = 3.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = localizedPreviewKeycapBadge(swipeSpec, appLang),
                                                fontSize = 8.sp,
                                                lineHeight = 9.sp,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Clip
                                            )
                                            Text(
                                                text = ch.uppercaseChar().toString(),
                                                fontSize = 13.sp,
                                                lineHeight = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = localizedPreviewKeycapBadge(longSpec, appLang),
                                                fontSize = 8.sp,
                                                lineHeight = 9.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Clip
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

    editingKey?.let { keyChar ->
        val currentSwipe = profile.swipeUp[keyChar] ?: KeyGestureBindingSpec(KeyGestureActionKind.NONE)
        val currentLong = profile.longPress[keyChar] ?: KeyGestureBindingSpec(KeyGestureActionKind.NONE)
        val defaultSwipe = defaults.swipeUp[keyChar] ?: KeyGestureBindingSpec(KeyGestureActionKind.NONE)
        val defaultLong = defaults.longPress[keyChar] ?: KeyGestureBindingSpec(KeyGestureActionKind.NONE)

        KeyGestureBindingDialog(
            keyChar = keyChar,
            initialSwipe = currentSwipe,
            initialLong = currentLong,
            defaultSwipe = defaultSwipe,
            defaultLong = defaultLong,
            onDismiss = { editingKey = null },
            onSave = { newSwipe, newLong ->
                val updatedSwipeMap = profile.swipeUp.toMutableMap().apply { put(keyChar, newSwipe) }
                val updatedLongMap = profile.longPress.toMutableMap().apply { put(keyChar, newLong) }
                val updatedProfile = KeyGestureProfileSpec(
                    swipeUp = updatedSwipeMap,
                    longPress = updatedLongMap
                )
                val newJson = if (KeyGestureConfigCodec.isCustomized(updatedProfile)) {
                    KeyGestureConfigCodec.toDeltaJson(updatedProfile)
                } else {
                    ""
                }
                repository.updateSettings { it.copy(customKeyGesturesJson = newJson) }
                editingKey = null
                Toast.makeText(
                    context,
                    AppStrings.pick(
                        appLang,
                        "已保存 ${keyChar.uppercaseChar()} 键手势设置",
                        "Saved gestures for key ${keyChar.uppercaseChar()}",
                        "已儲存 ${keyChar.uppercaseChar()} 鍵手勢設定"
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyGestureBindingDialog(
    keyChar: Char,
    initialSwipe: KeyGestureBindingSpec,
    initialLong: KeyGestureBindingSpec,
    defaultSwipe: KeyGestureBindingSpec,
    defaultLong: KeyGestureBindingSpec,
    onDismiss: () -> Unit,
    onSave: (KeyGestureBindingSpec, KeyGestureBindingSpec) -> Unit
) {
    val appLang = LocalAppLanguage.current
    var gestureTab by rememberSaveable(keyChar) { mutableIntStateOf(0) } // 0 = Swipe-Up, 1 = Long-Press

    var swipeKind by rememberSaveable(keyChar) { mutableStateOf(initialSwipe.type) }
    var swipeText by rememberSaveable(keyChar) { mutableStateOf(initialSwipe.text) }
    var swipeRightText by rememberSaveable(keyChar) { mutableStateOf(initialSwipe.rightText) }
    var swipeLabel by rememberSaveable(keyChar) { mutableStateOf(initialSwipe.label) }
    var swipeShortcut by rememberSaveable(keyChar) { mutableStateOf(initialSwipe.editorShortcut ?: EditorShortcutOption.SELECT_ALL.wireName) }

    var longKind by rememberSaveable(keyChar) { mutableStateOf(initialLong.type) }
    var longText by rememberSaveable(keyChar) { mutableStateOf(initialLong.text) }
    var longRightText by rememberSaveable(keyChar) { mutableStateOf(initialLong.rightText) }
    var longLabel by rememberSaveable(keyChar) { mutableStateOf(initialLong.label) }
    var longShortcut by rememberSaveable(keyChar) { mutableStateOf(initialLong.editorShortcut ?: EditorShortcutOption.SELECT_ALL.wireName) }

    fun buildSpec(
        kind: KeyGestureActionKind,
        text: String,
        rightText: String,
        label: String,
        shortcut: String
    ): KeyGestureBindingSpec {
        return when (kind) {
            KeyGestureActionKind.NONE -> KeyGestureBindingSpec(KeyGestureActionKind.NONE, label = "")
            KeyGestureActionKind.COMMIT_TEXT -> KeyGestureBindingSpec(
                type = KeyGestureActionKind.COMMIT_TEXT,
                text = text,
                rightText = "",
                label = label.trim().ifEmpty {
                    when (text) {
                        "\t" -> "Tab"
                        "\u3000" -> "全角"
                        else -> text.take(4)
                    }
                }
            )
            KeyGestureActionKind.COMMIT_PAIRED -> KeyGestureBindingSpec(
                type = KeyGestureActionKind.COMMIT_PAIRED,
                text = text,
                rightText = rightText,
                label = label.trim().ifEmpty { "${text}${rightText}".take(4) }
            )
            KeyGestureActionKind.UNDO_LAST -> KeyGestureBindingSpec(
                type = KeyGestureActionKind.UNDO_LAST,
                label = label.trim().ifEmpty { "撤回" }
            )
            KeyGestureActionKind.REPEAT_LAST -> KeyGestureBindingSpec(
                type = KeyGestureActionKind.REPEAT_LAST,
                label = label.trim().ifEmpty { "重复" }
            )
            KeyGestureActionKind.EDITOR_SHORTCUT -> {
                val opt = EditorShortcutOption.entries.firstOrNull { it.wireName == shortcut }
                    ?: EditorShortcutOption.SELECT_ALL
                KeyGestureBindingSpec(
                    type = KeyGestureActionKind.EDITOR_SHORTCUT,
                    label = label.trim().ifEmpty { opt.label.take(1) },
                    editorShortcut = opt.wireName
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = tr(
                        "自定义 ${keyChar.uppercaseChar()} 键手势",
                        "Key ${keyChar.uppercaseChar()} Gestures",
                        "自訂 ${keyChar.uppercaseChar()} 鍵手勢"
                    ),
                    modifier = Modifier.weight(1f, fill = false)
                )
                TextButton(
                    onClick = {
                        swipeKind = defaultSwipe.type
                        swipeText = defaultSwipe.text
                        swipeRightText = defaultSwipe.rightText
                        swipeLabel = defaultSwipe.label
                        swipeShortcut = defaultSwipe.editorShortcut ?: EditorShortcutOption.SELECT_ALL.wireName

                        longKind = defaultLong.type
                        longText = defaultLong.text
                        longRightText = defaultLong.rightText
                        longLabel = defaultLong.label
                        longShortcut = defaultLong.editorShortcut ?: EditorShortcutOption.SELECT_ALL.wireName
                    }
                ) {
                    Text(
                        tr("恢复该键默认", "Reset Key", "恢復該鍵預設"),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StudioSubTabPill(
                            title = tr("↑ 上滑动作", "↑ Swipe Up", "↑ 上滑動作"),
                            selected = gestureTab == 0,
                            onClick = { gestureTab = 0 },
                            modifier = Modifier.weight(1f)
                        )
                        StudioSubTabPill(
                            title = tr("⏱ 长按动作", "⏱ Long Press", "⏱ 長按動作"),
                            selected = gestureTab == 1,
                            onClick = { gestureTab = 1 },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                val activeKind = if (gestureTab == 0) swipeKind else longKind
                val activeText = if (gestureTab == 0) swipeText else longText
                val activeRight = if (gestureTab == 0) swipeRightText else longRightText
                val activeLabel = if (gestureTab == 0) swipeLabel else longLabel
                val activeShortcut = if (gestureTab == 0) swipeShortcut else longShortcut

                fun setKind(k: KeyGestureActionKind) {
                    if (gestureTab == 0) swipeKind = k else longKind = k
                }
                fun setText(v: String) {
                    if (gestureTab == 0) swipeText = v else longText = v
                }
                fun setRight(v: String) {
                    if (gestureTab == 0) swipeRightText = v else longRightText = v
                }
                fun setLabel(v: String) {
                    if (gestureTab == 0) swipeLabel = v else longLabel = v
                }
                fun setShortcut(v: String) {
                    if (gestureTab == 0) swipeShortcut = v else longShortcut = v
                }

                Text(
                    text = tr("动作类型", "Action Type", "動作類型"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    KeyGestureActionKind.entries.forEach { kind ->
                        FilterChip(
                            selected = activeKind == kind,
                            onClick = { setKind(kind) },
                            label = { Text(kind.titleFor(appLang), fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                when (activeKind) {
                    KeyGestureActionKind.COMMIT_TEXT -> {
                        OutlinedTextField(
                            value = activeText,
                            onValueChange = { setText(it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            label = { Text(tr("输出符号或文本", "Symbol or Text", "輸出符號或文字")) },
                            placeholder = { Text(tr("如：：“、→、#、@", "e.g. ->, #, @, “…", "如：：“、→、#、@")) }
                        )
                        val presetChips = listOf(
                            Triple("Tab", "\t", "Tab"),
                            Triple(
                                tr("全角空格", "Full-Width Space", "全形空白"),
                                "\u3000",
                                tr("全角", "FW", "全形")
                            ),
                            Triple("……", "……", "……"),
                            Triple("——", "——", "——"),
                            Triple("→", "→", "→")
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            presetChips.forEach { (presetLbl, presetVal, defaultBadge) ->
                                OutlinedButton(
                                    onClick = {
                                        setText(presetVal)
                                        setLabel(defaultBadge.take(4))
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(presetLbl, fontSize = 11.sp)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = activeLabel,
                            onValueChange = { setLabel(it.take(4)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            label = {
                                Text(
                                    tr(
                                        "键帽角标（选填，最多 4 字）",
                                        "Keycap Badge (optional, max 4 chars)",
                                        "鍵帽角標（選填，最多 4 字）"
                                    )
                                )
                            }
                        )
                    }
                    KeyGestureActionKind.COMMIT_PAIRED -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = activeText,
                                onValueChange = { setText(it) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                label = { Text(tr("左符号", "Left", "左符號")) },
                                placeholder = { Text(tr("如 「", "e.g. [", "如 「")) }
                            )
                            OutlinedTextField(
                                value = activeRight,
                                onValueChange = { setRight(it) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                label = { Text(tr("右符号", "Right", "右符號")) },
                                placeholder = { Text(tr("如 」", "e.g. ]", "如 」")) }
                            )
                        }
                        OutlinedTextField(
                            value = activeLabel,
                            onValueChange = { setLabel(it.take(4)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            label = { Text(tr("键帽角标（选填）", "Keycap Badge (optional)", "鍵帽角標（選填）")) }
                        )
                    }
                    KeyGestureActionKind.EDITOR_SHORTCUT -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            EditorShortcutOption.entries.forEach { opt ->
                                FilterChip(
                                    selected = activeShortcut == opt.wireName,
                                    onClick = {
                                        setShortcut(opt.wireName)
                                        setLabel(
                                            when (opt) {
                                                EditorShortcutOption.SELECT_ALL -> AppStrings.pick(appLang, "全", "All", "全")
                                                EditorShortcutOption.CUT -> AppStrings.pick(appLang, "剪", "Cut", "剪")
                                                EditorShortcutOption.COPY -> AppStrings.pick(appLang, "复", "Cpy", "複")
                                                EditorShortcutOption.PASTE -> AppStrings.pick(appLang, "粘", "Pst", "貼")
                                            }
                                        )
                                    },
                                    label = { Text(opt.labelFor(appLang)) }
                                )
                            }
                        }
                    }
                    KeyGestureActionKind.UNDO_LAST -> {
                        Text(
                            text = tr(
                                "触发后撤回最近一次上屏内容。",
                                "Reverts the most recently committed text when triggered.",
                                "觸發後復原最近一次上屏內容。"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    KeyGestureActionKind.REPEAT_LAST -> {
                        Text(
                            text = tr(
                                "触发后重复输入上一次已上屏的文字。",
                                "Commits the previously entered text again when triggered.",
                                "觸發後重複輸入上一次已上屏的文字。"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    KeyGestureActionKind.NONE -> {
                        Text(
                            text = tr(
                                "已禁用该按键的此手势触发。",
                                "This gesture is disabled for this key.",
                                "已停用該按鍵的此手勢觸發。"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalSwipe = buildSpec(swipeKind, swipeText, swipeRightText, swipeLabel, swipeShortcut)
                    val finalLong = buildSpec(longKind, longText, longRightText, longLabel, longShortcut)
                    onSave(finalSwipe, finalLong)
                }
            ) {
                Text(tr("保存", "Save", "儲存"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消", "Cancel", "取消"))
            }
        }
    )
}
