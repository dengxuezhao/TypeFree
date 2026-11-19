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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.ui.theme.TypeFreeEmerald

enum class KeyGestureActionKind(val title: String) {
    COMMIT_TEXT("单符号 / 文本"),
    COMMIT_PAIRED("成对居中符号"),
    UNDO_LAST("撤回上屏"),
    REPEAT_LAST("重复上屏"),
    EDITOR_SHORTCUT("编辑快捷操作"),
    NONE("禁用")
}

enum class EditorShortcutOption(val wireName: String, val label: String) {
    SELECT_ALL("SELECT_ALL", "全选"),
    CUT("CUT", "剪切"),
    COPY("COPY", "复制"),
    PASTE("PASTE", "粘贴")
}

data class KeyGestureBindingSpec(
    val type: KeyGestureActionKind,
    val text: String = "",
    val rightText: String = "",
    val label: String = "",
    val editorShortcut: String? = null
) {
    fun displayBadge(): String {
        if (type == KeyGestureActionKind.NONE) return "—"
        if (label.isNotBlank()) return label
        return when (type) {
            KeyGestureActionKind.COMMIT_TEXT -> when (text) {
                "\t" -> "Tab"
                "\u3000" -> "全角"
                else -> text.take(4)
            }
            KeyGestureActionKind.COMMIT_PAIRED -> "${text}${rightText}".take(4)
            KeyGestureActionKind.UNDO_LAST -> "撤回"
            KeyGestureActionKind.REPEAT_LAST -> "重复"
            KeyGestureActionKind.EDITOR_SHORTCUT -> when (editorShortcut) {
                "SELECT_ALL" -> "全"
                "CUT" -> "剪"
                "COPY" -> "复"
                "PASTE" -> "粘"
                else -> "编辑"
            }
            KeyGestureActionKind.NONE -> "—"
        }
    }
}

data class KeyGestureProfileSpec(
    val swipeUp: Map<Char, KeyGestureBindingSpec>,
    val longPress: Map<Char, KeyGestureBindingSpec>
)

object KeyGestureConfigCodec {

    private val prettyGson: Gson = GsonBuilder().disableHtmlEscaping().create()

    val QWERTY_ROWS: List<List<Char>> = listOf(
        listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'),
        listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'),
        listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
    )

    val ALL_KEYS: List<Char> = QWERTY_ROWS.flatten()

    fun defaultSwipeUpMap(): Map<Char, KeyGestureBindingSpec> = linkedMapOf(
        'q' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "：“", label = "：“"),
        'w' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "？", label = "？"),
        'e' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "（", label = "（"),
        'r' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "）", label = "）"),
        't' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "\t", label = "Tab"),
        'y' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_PAIRED, text = "《", rightText = "》", label = "《》"),
        'u' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "『", label = "『"),
        'i' to KeyGestureBindingSpec(KeyGestureActionKind.UNDO_LAST, label = "撤回"),
        'o' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_PAIRED, text = "「", rightText = "」", label = "「」"),
        'p' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_PAIRED, text = "『", rightText = "』", label = "『』"),
        'a' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "！", label = "！"),
        's' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "……", label = "……"),
        'd' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "、", label = "、"),
        'f' to KeyGestureBindingSpec(KeyGestureActionKind.REPEAT_LAST, label = "重复"),
        'g' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "·", label = "·"),
        'h' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_PAIRED, text = "[", rightText = "]", label = "[]"),
        'j' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_PAIRED, text = "“", rightText = "”", label = "“”"),
        'k' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_PAIRED, text = "（", rightText = "）", label = "（）"),
        'l' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_PAIRED, text = "〔", rightText = "〕", label = "〔〕"),
        'z' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "”", label = "”"),
        'x' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "→", label = "→"),
        'c' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "’", label = "’"),
        'v' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "——", label = "——"),
        'b' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "\u3000", label = "全角"),
        'n' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "=", label = "="),
        'm' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "@", label = "@")
    )

    fun defaultLongPressMap(): Map<Char, KeyGestureBindingSpec> = linkedMapOf(
        'q' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "1", label = "1"),
        'w' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "2", label = "2"),
        'e' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "3", label = "3"),
        'r' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "4", label = "4"),
        't' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "5", label = "5"),
        'y' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "6", label = "6"),
        'u' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "7", label = "7"),
        'i' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "8", label = "8"),
        'o' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "9", label = "9"),
        'p' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "0", label = "0"),
        'a' to KeyGestureBindingSpec(KeyGestureActionKind.EDITOR_SHORTCUT, label = "全", editorShortcut = "SELECT_ALL"),
        's' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "@", label = "@"),
        'd' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "#", label = "#"),
        'f' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "¥", label = "¥"),
        'g' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "~", label = "~"),
        'h' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "-", label = "-"),
        'j' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_PAIRED, text = "“", rightText = "”", label = "“”"),
        'k' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_PAIRED, text = "（", rightText = "）", label = "（）"),
        'l' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "：", label = "："),
        'z' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "`", label = "`"),
        'x' to KeyGestureBindingSpec(KeyGestureActionKind.EDITOR_SHORTCUT, label = "剪", editorShortcut = "CUT"),
        'c' to KeyGestureBindingSpec(KeyGestureActionKind.EDITOR_SHORTCUT, label = "复", editorShortcut = "COPY"),
        'v' to KeyGestureBindingSpec(KeyGestureActionKind.EDITOR_SHORTCUT, label = "粘", editorShortcut = "PASTE"),
        'b' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "、", label = "、"),
        'n' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "！", label = "！"),
        'm' to KeyGestureBindingSpec(KeyGestureActionKind.COMMIT_TEXT, text = "？", label = "？")
    )

    fun defaultProfile(): KeyGestureProfileSpec = KeyGestureProfileSpec(
        swipeUp = defaultSwipeUpMap(),
        longPress = defaultLongPressMap()
    )

    fun fromJsonOrDefault(rawJson: String): KeyGestureProfileSpec {
        val base = defaultProfile()
        if (rawJson.isBlank()) return base
        return runCatching {
            val root = JsonParser.parseString(rawJson).asJsonObject
            val mergedSwipe = base.swipeUp.toMutableMap()
            val mergedLong = base.longPress.toMutableMap()
            parseKeyMap(root.getAsJsonObject("swipeUp"), mergedSwipe)
            parseKeyMap(root.getAsJsonObject("longPress"), mergedLong)
            KeyGestureProfileSpec(swipeUp = mergedSwipe, longPress = mergedLong)
        }.getOrDefault(base)
    }

    fun toJson(profile: KeyGestureProfileSpec): String {
        val root = JsonObject()
        val swipeObj = JsonObject()
        val longObj = JsonObject()
        ALL_KEYS.forEach { key ->
            profile.swipeUp[key]?.let { binding ->
                swipeObj.add(key.toString(), serializeBinding(binding))
            }
            profile.longPress[key]?.let { binding ->
                longObj.add(key.toString(), serializeBinding(binding))
            }
        }
        root.add("swipeUp", swipeObj)
        root.add("longPress", longObj)
        return prettyGson.toJson(root)
    }

    fun isCustomized(profile: KeyGestureProfileSpec): Boolean {
        val def = defaultProfile()
        return profile.swipeUp != def.swipeUp || profile.longPress != def.longPress
    }

    private fun parseKeyMap(obj: JsonObject?, target: MutableMap<Char, KeyGestureBindingSpec>) {
        if (obj == null) return
        for ((k, el) in obj.entrySet()) {
            val ch = k.trim().lowercase().firstOrNull() ?: continue
            if (ch !in 'a'..'z' || !el.isJsonObject) continue
            val bObj = el.asJsonObject
            val rawType = bObj.get("type")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val kind = KeyGestureActionKind.entries.firstOrNull { it.name.equals(rawType, ignoreCase = true) }
                ?: continue
            val text = bObj.get("text")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val rightText = bObj.get("rightText")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val label = bObj.get("label")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val editorShortcut = bObj.get("editorShortcut")?.takeIf { !it.isJsonNull }?.asString
                ?.takeIf { it.isNotBlank() }
            target[ch] = KeyGestureBindingSpec(
                type = kind,
                text = text,
                rightText = rightText,
                label = label,
                editorShortcut = editorShortcut
            )
        }
    }

    private fun serializeBinding(binding: KeyGestureBindingSpec): JsonObject {
        val obj = JsonObject()
        obj.addProperty("type", binding.type.name)
        obj.addProperty("text", binding.text)
        obj.addProperty("rightText", binding.rightText)
        obj.addProperty("label", binding.label)
        if (!binding.editorShortcut.isNullOrBlank()) {
            obj.addProperty("editorShortcut", binding.editorShortcut)
        }
        return obj
    }
}

@Composable
fun KeyGestureCustomizerCard(
    repository: AppRepository,
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
                            text = "26 键上滑 / 长按自定义",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (hasCustomChanges) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = TypeFreeEmerald.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = "已自定义",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TypeFreeEmerald,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "点击下方任意键帽可分别设置「上滑动作（默认快符）」与「长按动作（默认数字/副功能/编辑）」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (hasCustomChanges) {
                    TextButton(
                        onClick = {
                            repository.updateSettings { it.copy(customKeyGesturesJson = "") }
                            Toast.makeText(context, "已恢复 26 键默认上滑与长按手势", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("恢复默认", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "右上角：↑ 上滑",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "底部：⏱ 长按",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 3-Row QWERTY Mini Keyboard Preview
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
                                            text = swipeSpec.displayBadge(),
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
                                            text = longSpec.displayBadge(),
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
                    KeyGestureConfigCodec.toJson(updatedProfile)
                } else {
                    ""
                }
                repository.updateSettings { it.copy(customKeyGesturesJson = newJson) }
                editingKey = null
                Toast.makeText(context, "已保存 ${keyChar.uppercaseChar()} 键手势设置", Toast.LENGTH_SHORT).show()
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
    var gestureTab by rememberSaveable(keyChar) { mutableIntStateOf(0) } // 0 = 上滑, 1 = 长按

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
                Text("自定义 ${keyChar.uppercaseChar()} 键手势")
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
                    Text("恢复该键默认", style = MaterialTheme.typography.labelMedium)
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
                            title = "↑ 上滑动作",
                            selected = gestureTab == 0,
                            onClick = { gestureTab = 0 },
                            modifier = Modifier.weight(1f)
                        )
                        StudioSubTabPill(
                            title = "⏱ 长按动作",
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
                    text = "动作类型",
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
                            label = { Text(kind.title, fontSize = 12.sp) },
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
                            label = { Text("输出符号或文本") },
                            placeholder = { Text("如：：“、→、#、@") }
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("Tab" to "\t", "全角空格" to "\u3000", "……" to "……", "——" to "——", "→" to "→").forEach { (presetLbl, presetVal) ->
                                OutlinedButton(
                                    onClick = {
                                        setText(presetVal)
                                        setLabel(presetLbl.take(2))
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
                            label = { Text("键帽角标（选填，最多 4 字）") }
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
                                label = { Text("左符号") },
                                placeholder = { Text("如 「") }
                            )
                            OutlinedTextField(
                                value = activeRight,
                                onValueChange = { setRight(it) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                label = { Text("右符号") },
                                placeholder = { Text("如 」") }
                            )
                        }
                        OutlinedTextField(
                            value = activeLabel,
                            onValueChange = { setLabel(it.take(4)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            label = { Text("键帽角标（选填）") }
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
                                        setLabel(opt.label.take(1))
                                    },
                                    label = { Text(opt.label) }
                                )
                            }
                        }
                    }
                    KeyGestureActionKind.UNDO_LAST -> {
                        Text(
                            text = "触发后撤回最近一次上屏内容。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    KeyGestureActionKind.REPEAT_LAST -> {
                        Text(
                            text = "触发后重复输入上一次已上屏的文字。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    KeyGestureActionKind.NONE -> {
                        Text(
                            text = "已禁用该按键的此手势触发。",
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
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
