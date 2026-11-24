package com.typefree.ime.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.DictionarySource
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SnippetMatcher
import com.typefree.core.ime.profile.SchemaArchetype
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.ime.flypy.FlypyDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private fun buildAiDevPresetPack(lang: AppLang): List<DictionaryEntry> = listOf(
    DictionaryEntry(
        term = "Cursor",
        wrongVariants = "科瑟, cursor",
        note = AppStrings.pick(lang, "AI 代码编辑器", "AI Code Editor", "AI 程式碼編輯器"),
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "Antigravity",
        wrongVariants = "安提格拉维蒂, anti gravity",
        note = AppStrings.pick(lang, "Agentic AI 编程环境", "Agentic AI Coding IDE", "Agentic AI 程式開發環境"),
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "Supabase",
        wrongVariants = "苏帕贝斯, super base",
        note = AppStrings.pick(lang, "开源后端云服务", "Open-Source Backend Cloud", "開源後端雲端服務"),
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "Kubernetes",
        wrongVariants = "库伯内蒂斯, k8s",
        note = AppStrings.pick(lang, "容器编排系统", "Container Orchestration", "容器編排系統"),
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "RAG",
        wrongVariants = "瑞格, R A G",
        note = AppStrings.pick(lang, "检索增强生成", "Retrieval-Augmented Generation", "檢索增強生成"),
        source = DictionarySource.MANUAL
    ),
    DictionaryEntry(
        term = "Agent",
        wrongVariants = "艾真特, agent智能体",
        note = AppStrings.pick(lang, "AI 自主智能体", "Autonomous AI Agent", "AI 自主智慧體"),
        source = DictionarySource.MANUAL
    )
)

private fun localizeProfileDisplayName(schemaId: String, rawDisplayName: String, lang: AppLang): String {
    return when (schemaId.lowercase()) {
        "flypy" -> AppStrings.pick(lang, "小鹤音形", "Flypy", "小鶴音形")
        "wubi86" -> AppStrings.pick(lang, "五笔 86", "Wubi 86", "五筆 86")
        "tiger" -> AppStrings.pick(lang, "虎码", "Tiger Code", "虎碼")
        "xkjd6" -> AppStrings.pick(lang, "键道 6", "XKJD 6", "鍵道 6")
        "rime_ice" -> AppStrings.pick(lang, "雾凇拼音", "Rime Ice Pinyin", "霧凇拼音")
        else -> rawDisplayName
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictionaryScreen(repository: AppRepository) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val coroutineScope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState()
    val dictionary = settings.dictionary
    val snippets = settings.snippets

    // 0 = 语音热词, 1 = 编码短语 ;xx, 2 = 小鹤/Rime 词库
    var primarySectionTab by rememberSaveable { mutableIntStateOf(0) }
    var showSnippetDialog by remember { mutableStateOf(false) }
    var editingSnippet by remember { mutableStateOf<SnippetEntry?>(null) }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 0 = 全部, 1 = 自动学习 Auto-added, 2 = 手动添加 Manual
    var selectedSourceTab by rememberSaveable { mutableIntStateOf(0) }

    var isBatchMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    var showEditorDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<DictionaryEntry?>(null) }
    var showBatchImportDialog by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    val flypyDict by TypeFreeApp.instance.flypyDictionary.collectAsState()
    val rimeDeployState by TypeFreeApp.instance.rimeDataManager.deploymentState.collectAsState()
    val rimeScope = rememberCoroutineScope()
    val isCustomActive = remember(flypyDict) {
        FlypyDictionary.isCustomDictionaryActive(context)
    }
    val xiaoheSchemaInstalled = remember(rimeDeployState) {
        TypeFreeApp.instance.rimeDataManager.hasXiaoheSchemaInstalled()
    }
    val metadataVersion by TypeFreeApp.instance.rimeDataManager.metadataVersion.collectAsState()
    val activeProfile = remember(rimeDeployState, settings.inputScheme, metadataVersion) {
        TypeFreeApp.instance.rimeDataManager.activeSchemaProfile
    }
    val isCodetableMode = activeProfile.archetype != SchemaArchetype.SENTENCE_PINYIN ||
        settings.inputScheme == InputScheme.FLYPY_TABLE
    val isEnhancedXiaoheActive = settings.inputScheme == InputScheme.FLYPY_TABLE &&
        (rimeDeployState is RimeDeploymentState.Ready || xiaoheSchemaInstalled) &&
        xiaoheSchemaInstalled
    var showRimeImportDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }

    val rimeZipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            rimeScope.launch {
                try {
                    val importResult = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            TypeFreeApp.instance.rimeDataManager.importEnhancedSchemaPackage(stream)
                        } ?: throw IllegalArgumentException(
                            AppStrings.pick(appLang, "无法读取所选 ZIP 文件", "Unable to read selected ZIP file", "無法讀取所選 ZIP 檔案")
                        )
                    }
                    TypeFreeApp.instance.rimeInputEngine.setInputScheme(InputScheme.RIME_CUSTOM)
                    repository.updateSettings { it.copy(inputScheme = InputScheme.RIME_CUSTOM) }
                    val label = importResult.displayName.ifBlank {
                        AppStrings.pick(appLang, "自定义 Rime 方案", "Custom Rime Schema", "自訂 Rime 方案")
                    }
                    Toast.makeText(
                        context,
                        AppStrings.pick(appLang, "✓ 已导入并部署「$label」", "✓ Imported and deployed \"$label\"", "✓ 已匯入並部署「$label」"),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    val fallbackErr = AppStrings.pick(appLang, "格式错误", "Invalid format", "格式錯誤")
                    Toast.makeText(
                        context,
                        AppStrings.pick(
                            appLang,
                            "导入 Rime 方案包失败: ${e.message ?: fallbackErr}",
                            "Failed to import Rime package: ${e.message ?: fallbackErr}",
                            "匯入 Rime 方案包失敗: ${e.message ?: fallbackErr}"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    val enabledCount = remember(dictionary) { dictionary.count { it.enabled } }
    val autoCount = remember(dictionary) {
        dictionary.count { it.source == DictionarySource.AUTO_LEARNED }
    }
    val manualCount = remember(dictionary) {
        dictionary.count { it.source == DictionarySource.MANUAL }
    }

    val filteredEntries = remember(dictionary, searchQuery, selectedSourceTab) {
        val q = searchQuery.trim()
        dictionary.filter { entry ->
            val sourceMatch = when (selectedSourceTab) {
                1 -> entry.source == DictionarySource.AUTO_LEARNED
                2 -> entry.source == DictionarySource.MANUAL
                else -> true
            }
            val queryMatch = if (q.isEmpty()) {
                true
            } else {
                entry.term.contains(q, ignoreCase = true) ||
                    entry.wrongVariants.contains(q, ignoreCase = true) ||
                    entry.note.contains(q, ignoreCase = true)
            }
            sourceMatch && queryMatch
        }
    }

    // Keep selectedIds valid against current dictionary
    val validSelectedIds = remember(selectedIds, dictionary) {
        val allIds = dictionary.map { it.id }.toSet()
        selectedIds.intersect(allIds)
    }

    val allFilteredSelected = filteredEntries.isNotEmpty() &&
        filteredEntries.all { it.id in validSelectedIds }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Top Header + Batch Mode Toggle
        item(key = "dict_header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                text = when (primarySectionTab) {
                                    0 -> tr("语音热词", "Voice Hotwords", "語音熱詞")
                                    1 -> tr("编码短语 ;xx", "Snippets", "編碼片語 ;xx")
                                    else -> tr("小鹤/Rime 词库", "Rime & Codetables", "小鶴/Rime 詞庫")
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                val badgeText = when (primarySectionTab) {
                                    0 -> tr("已启用 $enabledCount", "$enabledCount Active", "已啟用 $enabledCount")
                                    1 -> tr("已启用 ${snippets.count { it.enabled }}", "${snippets.count { it.enabled }} Active", "已啟用 ${snippets.count { it.enabled }}")
                                    else -> tr("${flypyDict.codeCount} 编码", "${flypyDict.codeCount} Codes", "${flypyDict.codeCount} 編碼")
                                }
                                Text(
                                    text = badgeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Text(
                            text = when (primarySectionTab) {
                                0 -> tr(
                                    "人名、品牌、技术术语声学热词偏置与自动改错学习",
                                    "Acoustic hotword boosting and auto-correction learning for names, brands, and tech terms",
                                    "人名、品牌、技術術語聲學熱詞偏置與自動改錯學習"
                                )
                                1 -> tr(
                                    "说出口令或敲小鹤 ;xx 快捷码，0ms 瞬时展开邮箱、地址与常用长文",
                                    "Speak a voice trigger or type a shortcut code to expand emails, addresses, and templates in 0ms",
                                    "說出口令或輸入 ;xx 快捷碼，0ms 瞬時展開信箱、地址與常用長文"
                                )
                                else -> tr(
                                    "小鹤音形四码 TSV 码表、雾凇拼音完整词库与 Rime 自定义方案包管理",
                                    "Manage Flypy 4-code TSV tables, full Rime Ice Pinyin dictionary, and custom Rime schema packages",
                                    "小鶴音形四碼 TSV 碼表、霧凇拼音完整詞庫與 Rime 自訂方案包管理"
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (primarySectionTab == 0 && dictionary.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                isBatchMode = !isBatchMode
                                if (!isBatchMode) {
                                    selectedIds = emptySet()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isBatchMode) tr("完成", "Done", "完成") else tr("批量选择", "Select", "批次選擇"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // 3-Segment Primary Switcher: 语音热词 | 编码短语 ;xx | 小鹤/Rime 词库 (R5.2)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DictPrimaryModePill(
                            title = tr("语音热词 (${dictionary.size})", "Hotwords (${dictionary.size})", "語音熱詞 (${dictionary.size})"),
                            selected = primarySectionTab == 0,
                            onClick = { primarySectionTab = 0 },
                            modifier = Modifier.weight(1f)
                        )
                        DictPrimaryModePill(
                            title = tr("编码短语 ;xx (${snippets.size})", "Snippets (${snippets.size})", "編碼片語 (${snippets.size})"),
                            selected = primarySectionTab == 1,
                            onClick = {
                                primarySectionTab = 1
                                isBatchMode = false
                            },
                            modifier = Modifier.weight(1f)
                        )
                        DictPrimaryModePill(
                            title = tr("小鹤/Rime 词库", "Rime Dict", "小鶴/Rime 詞庫"),
                            selected = primarySectionTab == 2,
                            onClick = {
                                primarySectionTab = 2
                                isBatchMode = false
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (primarySectionTab == 1) {
            item(key = "snippets_intro_and_actions") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.52f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = TypeFreeEmerald.copy(alpha = 0.10f),
                            border = BorderStroke(0.8.dp, TypeFreeEmerald.copy(alpha = 0.40f))
                        ) {
                            Text(
                                text = tr(
                                    "用于键盘打字输入 `;` + 缩写码秒出长句；若需点击卡片直接粘贴，请使用键盘剪贴板置顶常用语",
                                    "Type `;` or `/` + shortcut code on the keyboard to expand phrases; to tap-to-paste cards, pin items in the keyboard clipboard.",
                                    "用於鍵盤打字輸入 `;` + 縮寫碼秒出長句；若需點擊卡片直接貼上，請使用鍵盤剪貼簿置頂常用語"
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = TypeFreeEmerald,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)
                            )
                        }
                        Text(
                            text = tr("双通道瞬时展开（0ms 免经过大模型）", "Dual-Channel Instant Expansion (0ms, No LLM)", "雙通道瞬時展開（0ms 免經過大模型）"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr(
                                "• 语音触发：按住麦克风说出设定的口令（如「发我的邮箱」），识别命中后直接上屏完整文本，零延迟零幻觉。\n• 小鹤编码触发：在「鹤」模式下输入快捷码（推荐以分号开头，如 ;sd、;yx、;dz），唯一候选自动上屏且不影响内置 ;q / ;w 快符。",
                                "• Voice trigger: Hold Mic and speak a trigger phrase (e.g. \"send my email\") to insert the full text immediately with zero latency.\n• Code trigger: Type a shortcut code (e.g. /sd, /yx, /dz or ;xx) on the keyboard to auto-expand without interfering with quick symbols.",
                                "• 語音觸發：按住麥克風說出設定的口令（如「發我的信箱」），識別命中後直接上屏完整文字，零延遲零幻覺。\n• 編碼觸發：在鍵盤輸入快捷碼（如 ;sd、;yx、;dz），唯一候選自動上屏且不影響內建快符。"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    editingSnippet = null
                                    showSnippetDialog = true
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("新建口令短语", "New Snippet", "新增口令片語"), fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            if (snippets.isEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        repository.updateSettings {
                                            it.copy(snippets = DefaultPresets.defaultSnippets())
                                        }
                                        Toast.makeText(
                                            context,
                                            AppStrings.pick(appLang, "已载入示例短语模板", "Sample snippet templates loaded", "已載入範例片語範本"),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(tr("载入模板", "Load Samples", "載入範本"), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            if (snippets.isEmpty()) {
                item(key = "snippets_empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = tr("尚无口令短语", "No Snippets Yet", "尚無口令片語"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = tr(
                                    "点击上方「新建口令短语」添加邮箱、地址、发票抬头或高频客服回复。",
                                    "Tap \"New Snippet\" above to add emails, addresses, billing info, or frequent replies.",
                                    "點選上方「新增口令片語」新增信箱、地址、發票抬頭或高頻回覆。"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(snippets, key = { "snip_${it.id}" }) { snip ->
                    SnippetEntryCard(
                        snippet = snip,
                        onToggleEnabled = { enabled ->
                            repository.updateSettings { curr ->
                                curr.copy(
                                    snippets = curr.snippets.map {
                                        if (it.id == snip.id) it.copy(enabled = enabled) else it
                                    }
                                )
                            }
                        },
                        onEdit = {
                            editingSnippet = snip
                            showSnippetDialog = true
                        },
                        onDelete = {
                            repository.updateSettings { curr ->
                                curr.copy(snippets = curr.snippets.filterNot { it.id == snip.id })
                            }
                            Toast.makeText(
                                context,
                                AppStrings.pick(appLang, "已删除短语「${snip.title}」", "Deleted snippet \"${snip.title}\"", "已刪除片語「${snip.title}」"),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        } else if (primarySectionTab == 2) {

        // 1.5 Top Status Banner: SchemaProfile + Active Codetable Badge + Zip Import
        item(key = "dict_rime_custom_card") {
            val isDeployingRime = rimeDeployState is RimeDeploymentState.Deploying
            val archetypeTag = when (activeProfile.archetype) {
                SchemaArchetype.FIXED_YINXING -> tr("四码定长音形", "4-Code Yinxing", "四碼定長音形")
                SchemaArchetype.PUSH_TOPUP_CODE -> tr("顶功变长方案", "Push-Commit Code", "頂功變長方案")
                SchemaArchetype.FIXED_SHAPECODE -> tr("纯形定长码表", "Fixed Shape Code", "純形定長碼表")
                SchemaArchetype.SENTENCE_PINYIN -> tr("整句拼音", "Sentence Pinyin", "整句拼音")
            }
            val dispProfileName = localizeProfileDisplayName(activeProfile.schemaId, activeProfile.displayName, appLang)
            val dispSchemeLabel = AppStrings.inputSchemeLabel(settings.inputScheme, appLang)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.52f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.KeyboardGrid,
                                contentDescription = null,
                                tint = if (isCustomActive || isCodetableMode) TypeFreeEmerald else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = tr("四码音形 / Rime 码表", "Rime & Codetables", "四碼音形 / Rime 碼表"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isCustomActive || settings.inputScheme.usesRime || isEnhancedXiaoheActive) TypeFreeEmerald.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = when {
                                    isEnhancedXiaoheActive -> tr("当前：小鹤音形 (Rime 原生码表)", "Active: Flypy (Native Rime)", "目前：小鶴音形 (Rime 原生碼表)")
                                    settings.inputScheme == InputScheme.RIME_CUSTOM -> tr("当前：$dispProfileName ($archetypeTag)", "Active: $dispProfileName ($archetypeTag)", "目前：$dispProfileName ($archetypeTag)")
                                    settings.inputScheme.usesRime -> tr("当前：$dispSchemeLabel ($archetypeTag)", "Active: $dispSchemeLabel ($archetypeTag)", "目前：$dispSchemeLabel ($archetypeTag)")
                                    isCustomActive -> tr("自定义音形 (${flypyDict.codeCount} 编码)", "Custom Table (${flypyDict.codeCount} codes)", "自訂音形 (${flypyDict.codeCount} 編碼)")
                                    else -> tr("内置四码表 (${flypyDict.codeCount} 编码)", "Built-in Table (${flypyDict.codeCount} codes)", "內建四碼表 (${flypyDict.codeCount} 編碼)")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isCustomActive || settings.inputScheme.usesRime || isEnhancedXiaoheActive) TypeFreeEmerald
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        text = when {
                            isEnhancedXiaoheActive ->
                                tr(
                                    "当前键盘激活「小鹤音形」（由上方原生 librime 1.17.0 引擎驱动，一简/二简/四码字词默认锁定码表原序，并启用空码二简逆切分与 ; 双模态快符）。",
                                    "Active keyboard: Flypy (powered by native librime 1.17.0 with locked candidate order, 2-code fallback split, and `;` quick symbols).",
                                    "目前鍵盤啟用「小鶴音形」（由上方原生 librime 1.17.0 引擎驅動，一簡/二簡/四碼字詞預設鎖定碼表原序，並啟用空碼二簡逆切分與 ; 雙模態快符）。"
                                )
                            isCodetableMode ->
                                tr(
                                    "当前处于码表模式「$dispProfileName」（$archetypeTag · schema_id=${activeProfile.schemaId}）。可通过下方进阶面板管理方案、置顶/自造词、; 快符与扩展词库。",
                                    "Codetable mode active: \"$dispProfileName\" ($archetypeTag · schema_id=${activeProfile.schemaId}). Manage schemas, pinned/custom words, `;` symbols, and sub-dictionaries below.",
                                    "目前處於碼表模式「$dispProfileName」（$archetypeTag · schema_id=${activeProfile.schemaId}）。可透過下方進階面板管理方案、置頂/自造詞、; 快符與擴充詞庫。"
                                )
                            settings.inputScheme.usesRime ->
                                tr(
                                    "当前键盘激活「$dispSchemeLabel」（由上方原生 librime 1.17.0 引擎驱动）。支持导入 Rime 方案包或切换开源预设码表。",
                                    "Active keyboard: \"$dispSchemeLabel\" (powered by native librime 1.17.0). Import Rime schema packages or switch open-source codetables.",
                                    "目前鍵盤啟用「$dispSchemeLabel」（由上方原生 librime 1.17.0 引擎驅動）。支援匯入 Rime 方案包或切換開源預設碼表。"
                                )
                            isCustomActive ->
                                tr(
                                    "当前已挂载私有音形码表，单字与词组在键盘中优先生效。可随时导入新码表或一键恢复内置词库。",
                                    "Custom codetable mounted and prioritized on the keyboard. Import a new TSV table or restore the built-in dictionary anytime.",
                                    "目前已掛載私有音形碼表，單字與詞組在鍵盤中優先生效。可隨時匯入新碼表或一鍵恢復內建詞庫。"
                                )
                            else ->
                                tr(
                                    "当前使用内置开源四码词库。支持导入 Rime 方案包或个人 TSV 码表实现定长/顶功输入。",
                                    "Using built-in open-source 4-code dictionary. Import a Rime schema package (.zip) or custom TSV table.",
                                    "目前使用內建開源四碼詞庫。支援匯入 Rime 方案包或個人 TSV 碼表實現定長/頂功輸入。"
                                )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    // Rime Deployment Status & Progress Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val rimeStatusText = when (val st = rimeDeployState) {
                            is RimeDeploymentState.Idle -> tr("Rime 引擎：待部署", "Rime Engine: Idle", "Rime 引擎：待部署")
                            is RimeDeploymentState.Deploying -> st.message
                            is RimeDeploymentState.Ready -> {
                                val tier = if (st.hasFullRimeIce) {
                                    tr("完整雾凇词库", "Full Rime Ice", "完整霧凇詞庫")
                                } else {
                                    tr("内置精简词库", "Compact Built-in", "內建精簡詞庫")
                                }
                                val customSuffix = st.customSchemaName?.let { " · $it" }.orEmpty()
                                val timeSuffix = if (st.deployDurationMs > 0L) " · ${st.deployDurationMs}ms" else ""
                                tr(
                                    "Rime 就绪：$tier (${st.wordCount} 词条$timeSuffix)$customSuffix",
                                    "Rime Ready: $tier (${st.wordCount} words$timeSuffix)$customSuffix",
                                    "Rime 就緒：$tier (${st.wordCount} 詞條$timeSuffix)$customSuffix"
                                )
                            }
                            is RimeDeploymentState.Error -> tr("Rime 状态：${st.message}", "Rime Error: ${st.message}", "Rime 狀態：${st.message}")
                        }
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isDeployingRime) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            Text(
                                text = rimeStatusText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (rimeDeployState is RimeDeploymentState.Error) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            rimeZipPickerLauncher.launch(
                                arrayOf("application/zip", "application/x-zip-compressed", "*/*")
                            )
                        },
                        enabled = !isDeployingRime,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = tr("导入 Rime 方案包 (.zip)", "Import Rime Schema (.zip)", "匯入 Rime 方案包 (.zip)"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 1-Row Advanced Toggle: Fixed Candidate Order (`fixedCandidateOrder`)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = tr("固定候选顺序", "Lock Candidate Order", "固定候選順序"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = tr(
                                    "开启后保持词表静态默认排序，关闭动态调频（适合肌肉记忆打字）。",
                                    "Keep static dictionary order and disable dynamic frequency reordering (ideal for muscle memory).",
                                    "開啟後保持詞表靜態預設排序，關閉動態調頻（適合肌肉記憶打字）。"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                        Switch(
                            checked = settings.fixedCandidateOrder ?: (isEnhancedXiaoheActive || settings.inputScheme == InputScheme.FLYPY_TABLE),
                            onCheckedChange = { fixed ->
                                repository.updateSettings { it.copy(fixedCandidateOrder = fixed) }
                            }
                        )
                    }
                }
            }
        }

        // 1.6 Codetable Advanced Management Card
        item(key = "codetable_advanced_management_card") {
            CodetableAdvancedManagementCard(
                repository = repository,
                settings = settings,
                activeProfile = activeProfile,
                rimeDeployState = rimeDeployState,
                defaultExpanded = isCodetableMode
            )
        }

        // 1.7 Pinyin & Legacy TSV Compatibility Tools (collapsed automatically in codetable mode)
        item(key = "dict_pinyin_compat_tools_card") {
            val isDeployingRime = rimeDeployState is RimeDeploymentState.Deploying
            val hasFullIce = (rimeDeployState as? RimeDeploymentState.Ready)?.hasFullRimeIce == true
            var compatToolsExpanded by rememberSaveable(isCodetableMode) {
                mutableStateOf(!isCodetableMode)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { compatToolsExpanded = !compatToolsExpanded }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isCodetableMode) {
                                tr(
                                    "拼音词库与兼容工具（码表模式下已收起，点击展开）",
                                    "Pinyin & Legacy TSV Tools (collapsed in codetable mode)",
                                    "拼音詞庫與相容工具（碼表模式下已收起，點擊展開）"
                                )
                            } else {
                                tr("拼音词库与兼容工具", "Pinyin & Legacy TSV Tools", "拼音詞庫與相容工具")
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )
                        Icon(
                            imageVector = if (compatToolsExpanded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = if (compatToolsExpanded) tr("收起", "Collapse", "收起") else tr("展开", "Expand", "展開"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(visible = compatToolsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            )

                            FilledTonalButton(
                                onClick = {
                                    rimeScope.launch {
                                        try {
                                            val ready = TypeFreeApp.instance.rimeDataManager.downloadFullRimeIceDictionary()
                                            val count = (ready as? RimeDeploymentState.Ready)?.wordCount ?: 0
                                            Toast.makeText(
                                                context,
                                                AppStrings.pick(
                                                    appLang,
                                                    "✓ 完整雾凇拼音词库已部署 ($count 词条)",
                                                    "✓ Full Rime Ice dictionary deployed ($count words)",
                                                    "✓ 完整霧凇拼音詞庫已部署 ($count 詞條)"
                                                ),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } catch (e: Exception) {
                                            val netHint = AppStrings.pick(appLang, "请检查网络", "Check network", "請檢查網路")
                                            Toast.makeText(
                                                context,
                                                AppStrings.pick(
                                                    appLang,
                                                    "雾凇词库下载失败: ${e.message ?: netHint}",
                                                    "Rime Ice download failed: ${e.message ?: netHint}",
                                                    "霧凇詞庫下載失敗: ${e.message ?: netHint}"
                                                ),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                },
                                enabled = !isDeployingRime,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (hasFullIce) {
                                        tr("更新雾凇完整词库", "Update Full Rime Ice Dict", "更新霧凇完整詞庫")
                                    } else {
                                        tr("按需下载雾凇完整词库", "Download Full Rime Ice Dict", "按需下載霧凇完整詞庫")
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { showRimeImportDialog = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Text(
                                        text = tr("导入自定义码表 (TSV)", "Import Custom TSV", "匯入自訂碼表 (TSV)"),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (isCustomActive) {
                                    OutlinedButton(
                                        onClick = { showRestoreConfirmDialog = true },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = tr("恢复默认", "Restore Default", "恢復預設"),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        } else {

        // 2. V2.9 Flagship Feature: Auto-Learn Corrections Switch Card
        item(key = "dict_auto_learn_card") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.52f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.Sparkles,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(17.dp)
                                    .align(Alignment.CenterVertically)
                            )
                            Text(
                                text = tr("自动学习打字改错", "Auto-Learn Typing Corrections", "自動學習打字改錯"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = TypeFreeAmber.copy(alpha = 0.16f),
                                border = BorderStroke(0.8.dp, TypeFreeAmber.copy(alpha = 0.65f)),
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                Text(
                                    text = tr("实验功能", "Experimental", "實驗功能"),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) TypeFreeAmber else Color(0xFFB45309),
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = tr(
                                "当使用内置键盘修正刚口述的专有名词、人名或大小写（如将 deep seek 改为 DeepSeek）时，自动提取差异并加入个人词典。",
                                "When you use the built-in keyboard to fix proper nouns, names, or casing right after dictation (e.g. deep seek → DeepSeek), corrections are learned automatically.",
                                "當使用內建鍵盤修正剛口述的專有名詞、人名或大小寫（如將 deep seek 改為 DeepSeek）時，自動擷取差異並加入個人詞典。"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }

                    Switch(
                        checked = settings.autoLearnDictionary,
                        onCheckedChange = { enabled ->
                            repository.updateSettings { it.copy(autoLearnDictionary = enabled) }
                        }
                    )
                }
            }
        }

        // 3. Action Buttons + Search Box + 3-Segment Source Filter Capsules
        item(key = "dict_controls") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            editingEntry = null
                            showEditorDialog = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(tr("新建词条", "New Entry", "新增詞條"), fontWeight = FontWeight.Bold, maxLines = 1)
                    }

                    FilledTonalButton(
                        onClick = { showBatchImportDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = tr("批量文本 / 预设词包导入", "Batch / Preset Import", "批次文字 / 預設詞包匯入"),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = {
                        Text(tr("搜索标准词、同音误识别词或备注...", "Search term, misrecognized variants, or notes...", "搜尋標準詞、同音誤識別詞或備註..."))
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = tr("清除搜索", "Clear search", "清除搜尋")
                                )
                            }
                        }
                    } else null
                )

                // 3-Segment Source Filter Capsules
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DictSourceFilterPill(
                            label = tr("全部 (${dictionary.size})", "All (${dictionary.size})", "全部 (${dictionary.size})"),
                            selected = selectedSourceTab == 0,
                            onClick = { selectedSourceTab = 0 }
                        )
                        DictSourceFilterPill(
                            label = tr("自动学习 ($autoCount)", "Auto-Learned ($autoCount)", "自動學習 ($autoCount)"),
                            selected = selectedSourceTab == 1,
                            onClick = { selectedSourceTab = 1 }
                        )
                        DictSourceFilterPill(
                            label = tr("手动添加 ($manualCount)", "Manual ($manualCount)", "手動新增 ($manualCount)"),
                            selected = selectedSourceTab == 2,
                            onClick = { selectedSourceTab = 2 }
                        )
                    }
                }

                // Batch Selection Action Toolbar
                AnimatedVisibility(visible = isBatchMode && dictionary.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(enabled = filteredEntries.isNotEmpty()) {
                                    selectedIds = if (allFilteredSelected) {
                                        validSelectedIds - filteredEntries.map { it.id }.toSet()
                                    } else {
                                        validSelectedIds + filteredEntries.map { it.id }
                                    }
                                }
                            ) {
                                Checkbox(
                                    checked = allFilteredSelected,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) {
                                            validSelectedIds + filteredEntries.map { it.id }
                                        } else {
                                            validSelectedIds - filteredEntries.map { it.id }.toSet()
                                        }
                                    }
                                )
                                Text(
                                    text = if (allFilteredSelected) {
                                        tr("取消全选", "Deselect All", "取消全選")
                                    } else {
                                        tr("全选 (${filteredEntries.size})", "Select All (${filteredEntries.size})", "全選 (${filteredEntries.size})")
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Button(
                                onClick = { showBatchDeleteConfirm = true },
                                enabled = validSelectedIds.isNotEmpty(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = tr("删除已选 (${validSelectedIds.size})", "Delete (${validSelectedIds.size})", "刪除已選 (${validSelectedIds.size})"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Dictionary Entries List or Empty State
        if (filteredEntries.isEmpty()) {
            item(key = "dict_empty_state") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.Book,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(34.dp)
                        )
                        Text(
                            text = when {
                                dictionary.isEmpty() -> tr("个人词典为空", "Personal Dictionary Is Empty", "個人詞典為空")
                                selectedSourceTab == 1 && searchQuery.isEmpty() -> tr("尚无自动学习的改错词条", "No Auto-Learned Corrections Yet", "尚無自動學習的改錯詞條")
                                else -> tr("未找到匹配的词条", "No Matching Entries", "未找到符合的詞條")
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                selectedSourceTab == 1 && searchQuery.isEmpty() ->
                                    tr(
                                        "当你在口述完成后使用内置键盘修正专有名词时，系统会自动学习并在此展示。",
                                        "When you fix proper nouns on the built-in keyboard right after dictation, learned corrections appear here automatically.",
                                        "當你在口述完成後使用內建鍵盤修正專有名詞時，系統會自動學習並在此顯示。"
                                    )
                                dictionary.isEmpty() ->
                                    tr(
                                        "添加同事姓名、产品代号或技术词汇，让语音识别与润色永不写错字。",
                                        "Add names, product codenames, or technical terms so speech recognition and polishing get them right every time.",
                                        "新增同事姓名、產品代號或技術詞彙，讓語音識別與潤飾不再寫錯字。"
                                    )
                                else ->
                                    tr(
                                        "尝试调整搜索词或切换上方来源筛选胶囊。",
                                        "Try adjusting your search query or switching the source filter above.",
                                        "嘗試調整搜尋詞或切換上方來源篩選膠囊。"
                                    )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(filteredEntries, key = { it.id }) { entry ->
                val isSelected = entry.id in validSelectedIds
                DictionaryEntryCard(
                    entry = entry,
                    isBatchMode = isBatchMode,
                    isSelected = isSelected,
                    onToggleSelect = {
                        selectedIds = if (isSelected) {
                            validSelectedIds - entry.id
                        } else {
                            validSelectedIds + entry.id
                        }
                    },
                    onToggleEnabled = { enabled ->
                        repository.updateSettings { current ->
                            current.copy(
                                dictionary = current.dictionary.map {
                                    if (it.id == entry.id) it.copy(enabled = enabled) else it
                                }
                            )
                        }
                    },
                    onEdit = {
                        editingEntry = entry
                        showEditorDialog = true
                    },
                    onDelete = {
                        repository.deleteDictionaryEntries(setOf(entry.id))
                        Toast.makeText(
                            context,
                            AppStrings.pick(appLang, "已删除词条「${entry.term}」", "Deleted entry \"${entry.term}\"", "已刪除詞條「${entry.term}」"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
        }

        item(key = "dict_bottom_spacer") {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showSnippetDialog) {
        SnippetEditorDialog(
            initialSnippet = editingSnippet,
            onDismiss = { showSnippetDialog = false },
            onSave = { saved ->
                repository.updateSettings { curr ->
                    val exists = curr.snippets.any { it.id == saved.id }
                    val updated = if (exists) {
                        curr.snippets.map { if (it.id == saved.id) saved else it }
                    } else {
                        listOf(saved) + curr.snippets
                    }
                    curr.copy(snippets = updated)
                }
                showSnippetDialog = false
                Toast.makeText(
                    context,
                    if (editingSnippet == null) {
                        AppStrings.pick(appLang, "已新建短语「${saved.title}」", "Created snippet \"${saved.title}\"", "已新增片語「${saved.title}」")
                    } else {
                        AppStrings.pick(appLang, "已更新短语「${saved.title}」", "Updated snippet \"${saved.title}\"", "已更新片語「${saved.title}」")
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    // Create / Edit Entry Dialog
    if (showEditorDialog) {
        DictionaryEntryEditorDialog(
            initialEntry = editingEntry,
            onDismiss = { showEditorDialog = false },
            onSave = { saved ->
                repository.updateSettings { current ->
                    val exists = current.dictionary.any { it.id == saved.id }
                    val updated = if (exists) {
                        current.dictionary.map { if (it.id == saved.id) saved else it }
                    } else {
                        listOf(saved) + current.dictionary
                    }
                    current.copy(dictionary = updated)
                }
                showEditorDialog = false
                Toast.makeText(
                    context,
                    if (editingEntry == null) {
                        AppStrings.pick(appLang, "已添加词条「${saved.term}」", "Added entry \"${saved.term}\"", "已新增詞條「${saved.term}」")
                    } else {
                        AppStrings.pick(appLang, "已更新词条「${saved.term}」", "Updated entry \"${saved.term}\"", "已更新詞條「${saved.term}」")
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    // Batch Import Dialog (Multi-line Text + AI/Dev Preset Pack)
    if (showBatchImportDialog) {
        BatchImportDictionaryDialog(
            existingTerms = remember(dictionary) {
                dictionary.map { it.term.trim().lowercase() }.toSet()
            },
            onDismiss = { showBatchImportDialog = false },
            onImportEntries = { importedList ->
                if (importedList.isNotEmpty()) {
                    repository.updateSettings { current ->
                        val existingLower = current.dictionary.map { it.term.trim().lowercase() }.toSet()
                        val deduped = importedList.filter { it.term.trim().lowercase() !in existingLower }
                        current.copy(dictionary = deduped + current.dictionary)
                    }
                    Toast.makeText(
                        context,
                        AppStrings.pick(
                            appLang,
                            "成功导入 ${importedList.size} 条专属词条",
                            "Imported ${importedList.size} dictionary entries",
                            "成功匯入 ${importedList.size} 條專屬詞條"
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        AppStrings.pick(appLang, "所选词条均已存在，无需重复导入", "All selected entries already exist", "所選詞條均已存在，無需重複匯入"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                showBatchImportDialog = false
            }
        )
    }

    // Batch Delete Confirmation Dialog
    if (showBatchDeleteConfirm && validSelectedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(tr("批量删除词条", "Delete Selected Entries", "批次刪除詞條")) },
            text = {
                Text(
                    tr(
                        "确定要删除已选中的 ${validSelectedIds.size} 个词条吗？删除后无法恢复。",
                        "Delete ${validSelectedIds.size} selected entries? This cannot be undone.",
                        "確定要刪除已選取的 ${validSelectedIds.size} 個詞條嗎？刪除後無法復原。"
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val count = validSelectedIds.size
                        repository.deleteDictionaryEntries(validSelectedIds)
                        selectedIds = emptySet()
                        showBatchDeleteConfirm = false
                        if (dictionary.size <= count) {
                            isBatchMode = false
                        }
                        Toast.makeText(
                            context,
                            AppStrings.pick(appLang, "已批量删除 $count 个词条", "Deleted $count entries", "已批次刪除 $count 個詞條"),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text(tr("确认删除 (${validSelectedIds.size})", "Delete (${validSelectedIds.size})", "確認刪除 (${validSelectedIds.size})"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text(tr("取消", "Cancel", "取消"))
                }
            }
        )
    }

    // Custom Rime / Yinxing Table Import Dialog
    if (showRimeImportDialog) {
        RimeDictionaryImportDialog(
            onDismiss = { showRimeImportDialog = false }
        )
    }

    // Restore Default Dictionary Dialog
    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = { Text(tr("恢复内置开源词库", "Restore Built-in Dictionary", "恢復內建開源詞庫")) },
            text = {
                Text(
                    tr(
                        "确定要清除当前自定义私有码表并恢复默认内置开源双拼词库吗？",
                        "Clear the custom codetable and restore the default built-in open-source dictionary?",
                        "確定要清除目前自訂私有碼表並恢復預設內建開源雙拼詞庫嗎？"
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    FlypyDictionary.restoreDefaultDictionary(context)
                                    TypeFreeApp.instance.reloadFlypyDictionary()
                                }
                                Toast.makeText(
                                    context,
                                    AppStrings.pick(appLang, "已恢复内置默认开源词库", "Restored default built-in dictionary", "已恢復內建預設開源詞庫"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    AppStrings.pick(appLang, "恢复失败: ${e.message}", "Restore failed: ${e.message}", "恢復失敗: ${e.message}"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            showRestoreConfirmDialog = false
                        }
                    }
                ) {
                    Text(tr("确认恢复", "Restore", "確認恢復"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmDialog = false }) {
                    Text(tr("取消", "Cancel", "取消"))
                }
            }
        )
    }
}

@Composable
private fun DictSourceFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictionaryEntryCard(
    entry: DictionaryEntry,
    isBatchMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val variantTokens = remember(entry.wrongVariants) {
        entry.wrongVariants
            .split(',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    val isAutoLearned = entry.source == DictionarySource.AUTO_LEARNED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isBatchMode) {
                    Modifier.clickable { onToggleSelect() }
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isBatchMode && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                entry.enabled -> MaterialTheme.colorScheme.surface
                else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)
            }
        ),
        border = BorderStroke(
            width = if (isBatchMode && isSelected) 1.5.dp else 1.dp,
            color = when {
                isBatchMode && isSelected -> MaterialTheme.colorScheme.primary
                entry.enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.48f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    if (isBatchMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect() }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = entry.term,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (entry.enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isAutoLearned) {
                                    TypeFreeEmerald.copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = if (isAutoLearned) tr("✨ 自动学习", "✨ Auto-Learned", "✨ 自動學習") else tr("手动添加", "Manual", "手動新增"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isAutoLearned) {
                                        TypeFreeEmerald
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (entry.note.isNotBlank()) {
                            Text(
                                text = entry.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (!isBatchMode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Switch(
                            checked = entry.enabled,
                            onCheckedChange = onToggleEnabled
                        )
                        IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = tr("编辑词条", "Edit entry", "編輯詞條"),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = tr("删除词条", "Delete entry", "刪除詞條"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (variantTokens.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = tr("同音纠错：", "Fixes:", "同音糾錯："),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    variantTokens.forEach { wrong ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "$wrong → ${entry.term}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DictionaryEntryEditorDialog(
    initialEntry: DictionaryEntry?,
    onDismiss: () -> Unit,
    onSave: (DictionaryEntry) -> Unit
) {
    var term by rememberSaveable(initialEntry?.id) { mutableStateOf(initialEntry?.term ?: "") }
    var wrongVariants by rememberSaveable(initialEntry?.id) { mutableStateOf(initialEntry?.wrongVariants ?: "") }
    var note by rememberSaveable(initialEntry?.id) { mutableStateOf(initialEntry?.note ?: "") }

    val isValid = term.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialEntry == null) {
                    tr("新建个人词典词条", "New Dictionary Entry", "新增個人詞典詞條")
                } else {
                    tr("编辑个人词典词条", "Edit Dictionary Entry", "編輯個人詞典詞條")
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text(tr("标准词 / 专有名词 *", "Target Term / Proper Noun *", "標準詞 / 專有名詞 *")) },
                    placeholder = { Text(tr("如：TypeFree、DeepSeek、张伟明", "e.g. TypeFree, DeepSeek, Alex Chen", "如：TypeFree、DeepSeek、張偉明")) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = wrongVariants,
                    onValueChange = { wrongVariants = it },
                    label = { Text(tr("常见同音误识别词（可选，逗号分隔）", "Common Misrecognitions (optional, comma-separated)", "常見同音誤識別詞（選填，逗號分隔）")) },
                    placeholder = { Text(tr("如：太普利斯, type less", "e.g. type less, deep seek", "如：太普利斯, type less")) },
                    minLines = 2,
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(tr("备注说明（可选）", "Note (optional)", "備註說明（選填）")) },
                    placeholder = { Text(tr("如：产品名 / 同事姓名 / 技术术语", "e.g. Product name / Colleague / Tech term", "如：產品名 / 同事姓名 / 技術術語")) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = isValid,
                onClick = {
                    val normalizedVariants = wrongVariants
                        .replace('，', ',')
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(", ")

                    val entry = if (initialEntry != null) {
                        initialEntry.copy(
                            term = term.trim(),
                            wrongVariants = normalizedVariants,
                            note = note.trim()
                        )
                    } else {
                        DictionaryEntry(
                            term = term.trim(),
                            wrongVariants = normalizedVariants,
                            note = note.trim(),
                            enabled = true,
                            source = DictionarySource.MANUAL
                        )
                    }
                    onSave(entry)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(tr("保存词条", "Save", "儲存詞條"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消", "Cancel", "取消"))
            }
        }
    )
}

@Composable
private fun BatchImportDictionaryDialog(
    existingTerms: Set<String>,
    onDismiss: () -> Unit,
    onImportEntries: (List<DictionaryEntry>) -> Unit
) {
    val appLang = LocalAppLanguage.current
    var rawLines by rememberSaveable { mutableStateOf("") }

    val parsedFromText = remember(rawLines, existingTerms, appLang) {
        parseBatchDictionaryLines(rawLines, existingTerms, appLang)
    }
    val missingPresetEntries = remember(existingTerms, appLang) {
        buildAiDevPresetPack(appLang).filter { it.term.trim().lowercase() !in existingTerms }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("批量导入专属词条", "Batch Import Dictionary Entries", "批次匯入專屬詞條")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = tr(
                        "每行填写一个词条。支持仅填标准词，或用 `=` / `:` 附带同音误词：\n示例：OpenTypeFree = 欧盆太普, open typefree",
                        "Enter one entry per line. Add optional misrecognized variants after `=` or `:`:\nExample: OpenTypeFree = open type free, type less",
                        "每行填寫一個詞條。支援僅填標準詞，或用 `=` / `:` 附帶同音誤詞：\n範例：OpenTypeFree = 歐盆太普, open typefree"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                OutlinedTextField(
                    value = rawLines,
                    onValueChange = { rawLines = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 8,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = {
                        Text(
                            tr(
                                "Qwen2.5 = 千问二点五, qwen\nSiliconFlow = 硅基流动\n张伟明 = 张伟民",
                                "Qwen2.5 = qwen two point five, qwen\nDeepSeek = deep seek\nKubernetes = k8s",
                                "Qwen2.5 = 千問二點五, qwen\nSiliconFlow = 矽基流動\n張偉明 = 張偉民"
                            )
                        )
                    }
                )

                if (rawLines.isNotBlank()) {
                    Text(
                        text = tr(
                            "已解析到 ${parsedFromText.size} 个可导入的新词条（已自动去重）",
                            "Parsed ${parsedFromText.size} new entries ready to import (deduplicated)",
                            "已解析到 ${parsedFromText.size} 個可匯入的新詞條（已自動去重）"
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = TypeFreeEmerald
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                FilledTonalButton(
                    onClick = { onImportEntries(missingPresetEntries) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (missingPresetEntries.isNotEmpty()) {
                            tr(
                                "一键导入内置 AI / 开发术语包 (${missingPresetEntries.size} 条)",
                                "Import AI / Dev Preset Pack (${missingPresetEntries.size})",
                                "一鍵匯入內建 AI / 開發術語包 (${missingPresetEntries.size} 條)"
                            )
                        } else {
                            tr("内置 AI / 开发术语包已全部导入", "AI / Dev Preset Pack Already Imported", "內建 AI / 開發術語包已全部匯入")
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = parsedFromText.isNotEmpty(),
                onClick = { onImportEntries(parsedFromText) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(tr("导入文本词条 (${parsedFromText.size})", "Import (${parsedFromText.size})", "匯入文字詞條 (${parsedFromText.size})"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("关闭", "Close", "關閉"))
            }
        }
    )
}

private fun parseBatchDictionaryLines(
    rawText: String,
    existingLowerTerms: Set<String>,
    lang: AppLang = AppLang.ZH_CN
): List<DictionaryEntry> {
    if (rawText.isBlank()) return emptyList()
    val seen = existingLowerTerms.toMutableSet()
    val results = mutableListOf<DictionaryEntry>()
    val defaultNote = AppStrings.pick(lang, "批量导入", "Batch Import", "批次匯入")

    rawText.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//")) return@forEach

        val noteSplit = trimmed.split('#', limit = 2)
        val mainPart = noteSplit[0].trim()
        val note = if (noteSplit.size > 1) noteSplit[1].trim() else defaultNote

        val pairSplit = mainPart.split(Regex("\\s*(=|:|：|->)\\s*"), limit = 2)
        val term = pairSplit[0].trim()
        if (term.isEmpty()) return@forEach

        val lower = term.lowercase()
        if (lower in seen) return@forEach
        seen.add(lower)

        val variants = if (pairSplit.size > 1) {
            pairSplit[1]
                .replace('，', ',')
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
        } else {
            ""
        }

        results += DictionaryEntry(
            term = term,
            wrongVariants = variants,
            note = note,
            enabled = true,
            source = DictionarySource.MANUAL
        )
    }

    return results
}

@Composable
fun RimeDictionaryImportDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val coroutineScope = rememberCoroutineScope()

    var mainDictUri by remember { mutableStateOf<Uri?>(null) }
    var mainDictFileName by remember { mutableStateOf("") }
    var dzDictUri by remember { mutableStateOf<Uri?>(null) }
    var dzDictFileName by remember { mutableStateOf("") }

    var isPasteExpanded by remember { mutableStateOf(false) }
    var rawTsvText by rememberSaveable { mutableStateOf("") }
    var rawDzText by rememberSaveable { mutableStateOf("") }

    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val fileMimeTypes = remember {
        arrayOf("text/*", "application/octet-stream", "*/*")
    }

    val mainDictLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            mainDictUri = uri
            mainDictFileName = queryFileName(context, uri)
                ?: uri.lastPathSegment
                ?: AppStrings.pick(appLang, "主词库文件.tsv", "main_dict.tsv", "主詞庫檔案.tsv")
            errorMessage = null
        }
    }

    val dzDictLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            dzDictUri = uri
            dzDictFileName = queryFileName(context, uri)
                ?: uri.lastPathSegment
                ?: AppStrings.pick(appLang, "反查表文件.tsv", "reverse_lookup.tsv", "反查表檔案.tsv")
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isImporting) onDismiss()
        },
        title = {
            Text(
                text = tr("导入自定义音形 / Rime 码表", "Import Custom Codetable (TSV)", "匯入自訂音形 / Rime 碼表"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = tr(
                        "通过系统安全文件选择器选取手机中的 TSV/TXT 码表文件。覆盖写入应用私有存储空间，纯本地生效，保护隐私。",
                        "Select a TSV/TXT codetable file via the system file picker. Stored strictly in private on-device storage.",
                        "透過系統安全檔案選擇器選取手機中的 TSV/TXT 碼表檔案。覆蓋寫入應用私有儲存空間，純本機生效，保護隱私。"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                // 1. Main Dictionary File Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = tr("主词库文件 (必需)", "Main Dictionary File (Required)", "主詞庫檔案 (必需)"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr(
                                "格式: 编码\\t候选词1\\t候选词2...（如小鹤音形、五笔、虎码等）",
                                "Format: code\\tcandidate1\\tcandidate2... (e.g. Flypy, Wubi, Tiger)",
                                "格式: 編碼\\t候選詞1\\t候選詞2...（如小鶴音形、五筆、虎碼等）"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (mainDictUri != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(TypeFreeEmerald.copy(alpha = 0.12f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = mainDictFileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TypeFreeEmerald,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        mainDictUri = null
                                        mainDictFileName = ""
                                    },
                                    enabled = !isImporting,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = tr("清除", "Clear", "清除"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { mainDictLauncher.launch(fileMimeTypes) },
                                enabled = !isImporting,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text(tr("选择主词库文件 (.tsv/.txt)", "Select Main File (.tsv/.txt)", "選擇主詞庫檔案 (.tsv/.txt)"))
                            }
                        }
                    }
                }

                // 2. Reverse Lookup Single-Char Table (Optional)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = tr("单字四码反查表 (可选)", "Single-Char Reverse Lookup (Optional)", "單字四碼反查表 (選填)"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tr(
                                "格式: 4码编码\\t单字（用于 ` 通配符查音查形）",
                                "Format: 4_char_code\\tcharacter (for `` ` `` wildcard reverse lookup)",
                                "格式: 4碼編碼\\t單字（用於 ` 萬用字元查音查形）"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (dzDictUri != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(TypeFreeEmerald.copy(alpha = 0.12f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = dzDictFileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TypeFreeEmerald,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        dzDictUri = null
                                        dzDictFileName = ""
                                    },
                                    enabled = !isImporting,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = tr("清除", "Clear", "清除"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { dzDictLauncher.launch(fileMimeTypes) },
                                enabled = !isImporting,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text(tr("选择反查表文件 (可选)", "Select Reverse Lookup File (Optional)", "選擇反查表檔案 (選填)"))
                            }
                        }
                    }
                }

                // 3. Fallback Small Text Paste for Debugging (Collapsible)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isPasteExpanded = !isPasteExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isPasteExpanded) {
                                tr("▼ 收起文本直接粘贴调试区", "▼ Hide Direct TSV Paste Area", "▼ 收起文字直接貼上除錯區")
                            } else {
                                tr("▶ 展开文本直接粘贴调试区 (限小片段)", "▶ Paste Small TSV Snippet Directly", "▶ 展開文字直接貼上除錯區 (限小片段)")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (isPasteExpanded) {
                        OutlinedTextField(
                            value = rawTsvText,
                            onValueChange = {
                                rawTsvText = it
                                if (it.isNotBlank()) mainDictUri = null
                            },
                            label = { Text(tr("粘贴主词库小片段 (TSV)", "Paste TSV Snippet", "貼上主詞庫小片段 (TSV)")) },
                            placeholder = { Text(tr("uijm\\t时间\\t实践\\nxhyx\\t小鹤音形", "uijm\\t时间\\t实践\\nxhyx\\tFlypy", "uijm\\t時間\\t實踐\\nxhyx\\t小鶴音形")) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp),
                            maxLines = 4,
                            enabled = !isImporting
                        )
                    }
                }

                // Error message
                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Loading progress indicator
                if (isImporting) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = tr("正在导入并构建内存索引，请稍候...", "Importing and building index...", "正在匯入並建立記憶體索引，請稍候..."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            val canSubmit = (mainDictUri != null || rawTsvText.isNotBlank()) && !isImporting
            Button(
                onClick = {
                    isImporting = true
                    errorMessage = null
                    coroutineScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val dictIn = when {
                                    mainDictUri != null -> {
                                        context.contentResolver.openInputStream(mainDictUri!!)
                                            ?: throw IllegalStateException(
                                                AppStrings.pick(appLang, "无法读取选中的主词库文件", "Unable to read selected main dictionary file", "無法讀取選取的主詞庫檔案")
                                            )
                                    }
                                    rawTsvText.isNotBlank() -> {
                                        ByteArrayInputStream(rawTsvText.toByteArray(Charsets.UTF_8))
                                    }
                                    else -> throw IllegalStateException(
                                        AppStrings.pick(appLang, "请选择主词库文件或输入文本", "Please select a main file or paste TSV text", "請選擇主詞庫檔案或輸入文字")
                                    )
                                }

                                val dzIn = when {
                                    dzDictUri != null -> {
                                        context.contentResolver.openInputStream(dzDictUri!!)
                                    }
                                    rawDzText.isNotBlank() -> {
                                        ByteArrayInputStream(rawDzText.toByteArray(Charsets.UTF_8))
                                    }
                                    else -> null
                                }

                                dictIn.use { dStream ->
                                    if (dzIn != null) {
                                        dzIn.use { zStream ->
                                            FlypyDictionary.importCustomDictionary(context, dStream, zStream)
                                        }
                                    } else {
                                        FlypyDictionary.importCustomDictionary(context, dStream, null)
                                    }
                                }

                                TypeFreeApp.instance.reloadFlypyDictionary()
                            }
                            Toast.makeText(
                                context,
                                AppStrings.pick(appLang, "自定义码表导入成功！输入法已热挂载", "Custom codetable imported and hot-mounted", "自訂碼表匯入成功！輸入法已熱掛載"),
                                Toast.LENGTH_SHORT
                            ).show()
                            onDismiss()
                        } catch (e: Exception) {
                            errorMessage = AppStrings.pick(
                                appLang,
                                "导入失败: ${e.message}",
                                "Import failed: ${e.message}",
                                "匯入失敗: ${e.message}"
                            )
                        } finally {
                            isImporting = false
                        }
                    }
                },
                enabled = canSubmit
            ) {
                Text(if (isImporting) tr("导入中...", "Importing...", "匯入中...") else tr("确认导入挂载", "Import & Mount", "確認匯入掛載"))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isImporting
            ) {
                Text(tr("关闭", "Close", "關閉"))
            }
        }
    )
}

private fun queryFileName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else null
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun DictPrimaryModePill(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp
    ) {
        Box(
            modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnippetEntryCard(
    snippet: SnippetEntry,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val appLang = LocalAppLanguage.current
    val voiceList = remember(snippet.voiceTriggers) {
        SnippetMatcher.parseTriggerList(snippet.voiceTriggers)
    }
    val normCode = remember(snippet.flypyCode) {
        SnippetMatcher.normalizeFlypyCode(snippet.flypyCode)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (snippet.enabled) {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.52f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = snippet.title.ifBlank { tr("未命名短语", "Untitled Snippet", "未命名片語") },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (normCode.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = tr("鹤 $normCode", "Code $normCode", "鶴 $normCode"),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Switch(
                    checked = snippet.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            if (voiceList.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    voiceList.forEach { trig ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        ) {
                            Text(
                                text = if (appLang == AppLang.EN) "🎙️ \"$trig\"" else "🎙️ 「$trig」",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Text(
                    text = snippet.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(tr("编辑", "Edit", "編輯"))
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(tr("删除", "Delete", "刪除"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SnippetEditorDialog(
    initialSnippet: SnippetEntry?,
    onDismiss: () -> Unit,
    onSave: (SnippetEntry) -> Unit
) {
    var title by rememberSaveable { mutableStateOf(initialSnippet?.title.orEmpty()) }
    var voiceTriggers by rememberSaveable { mutableStateOf(initialSnippet?.voiceTriggers.orEmpty()) }
    var flypyCode by rememberSaveable { mutableStateOf(initialSnippet?.flypyCode.orEmpty()) }
    var expandedContent by rememberSaveable { mutableStateOf(initialSnippet?.content.orEmpty()) }

    val normCode = SnippetMatcher.normalizeFlypyCode(flypyCode)
    val hasAnyTrigger = voiceTriggers.trim().isNotEmpty() || normCode.isNotEmpty()
    val canSave = expandedContent.trim().isNotEmpty() && hasAnyTrigger

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialSnippet == null) {
                    tr("新建口令短语", "New Snippet", "新增口令片語")
                } else {
                    tr("编辑口令短语", "Edit Snippet", "編輯口令片語")
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(tr("短语名称", "Snippet Title", "片語名稱")) },
                    placeholder = { Text(tr("如：工作邮箱、默认收货地址", "e.g. Work Email, Shipping Address", "如：工作信箱、預設收件地址")) }
                )

                OutlinedTextField(
                    value = voiceTriggers,
                    onValueChange = { voiceTriggers = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(tr("语音触发口令（逗号分隔，选填）", "Voice Triggers (comma-separated, optional)", "語音觸發口令（逗號分隔，選填）")) },
                    placeholder = { Text(tr("如：发我的邮箱, 我的工作邮箱", "e.g. send my email, my work email", "如：發我的信箱, 我的工作信箱")) }
                )

                OutlinedTextField(
                    value = flypyCode,
                    onValueChange = { flypyCode = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(tr("小鹤快捷码（推荐 ; 开头 3 码，选填）", "Shortcut Code (e.g. /yx or ;yx, optional)", "快捷碼（推薦 ; 或 / 開頭，選填）")) },
                    placeholder = { Text(tr("如：;yx、;dz、;sd", "e.g. /yx, /dz, /sd", "如：;yx、;dz、;sd")) }
                )

                OutlinedTextField(
                    value = expandedContent,
                    onValueChange = { expandedContent = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(tr("展开后的完整正文 *", "Expanded Text *", "展開後的完整內文 *")) },
                    placeholder = { Text(tr("输入命中后要直接上屏的完整内容…", "Enter the full text to insert when triggered…", "輸入命中後要直接上屏的完整內容…")) }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    val cleanTitle = title.trim().ifBlank {
                        normCode.ifBlank { voiceTriggers.trim().take(10) }
                    }
                    val base = initialSnippet ?: SnippetEntry(
                        title = cleanTitle,
                        voiceTriggers = "",
                        flypyCode = "",
                        content = ""
                    )
                    onSave(
                        base.copy(
                            title = cleanTitle,
                            voiceTriggers = voiceTriggers.trim(),
                            flypyCode = normCode,
                            content = expandedContent.trim(),
                            enabled = true
                        )
                    )
                }
            ) {
                Text(tr("保存并启用", "Save & Enable", "儲存並啟用"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消", "Cancel", "取消"))
            }
        }
    )
}
