package com.typefree.ime.ui.screens.insights

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.data.stats.AppSceneShareItem
import com.typefree.ime.data.stats.HeatmapDayCell
import com.typefree.ime.data.stats.InsightsSnapshot
import com.typefree.ime.data.stats.IntentShareItem
import com.typefree.ime.data.stats.MilestoneItem
import com.typefree.ime.data.stats.MonthlyTrendPoint
import com.typefree.ime.data.stats.SchemeShareItem
import com.typefree.ime.data.stats.SchemeStatsKeys
import com.typefree.ime.data.stats.StatsTimeRange
import com.typefree.ime.data.stats.TokenFeatureKeys
import com.typefree.ime.data.stats.TokenShareItem
import com.typefree.ime.data.stats.TokenUsageSnapshot
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.i18n.tr
import com.typefree.ime.ui.components.ContinuousRoundedCornerShape
import com.typefree.ime.ui.components.RollingDigitCounter
import com.typefree.ime.ui.components.SmoothDisclosure
import com.typefree.ime.ui.components.SpringSegmentedControl
import com.typefree.ime.ui.components.TypeFreeIcons
import com.typefree.ime.ui.components.horizontalFadeEdge
import com.typefree.ime.ui.components.springPressScale
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald
import com.typefree.ime.ui.theme.TypeFreeEmeraldLight
import com.typefree.ime.ui.theme.TypeFreeRose
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

internal fun formatInsightsCompactChars(chars: Int, lang: AppLang): String {
    val safe = chars.coerceAtLeast(0)
    return if (lang == AppLang.EN) {
        when {
            safe >= 1_000_000 -> String.format(Locale.US, "%.1fM", safe / 1_000_000.0)
            safe >= 10_000 -> String.format(Locale.US, "%.1fk", safe / 1_000.0)
            else -> String.format(Locale.US, "%,d", safe)
        }
    } else if (safe >= 10_000) {
        String.format(Locale.US, "%.1fw", safe / 10_000.0)
    } else {
        String.format(Locale.US, "%,d", safe)
    }
}

internal fun localizedRangeLabel(range: StatsTimeRange, lang: AppLang): String {
    return when (range) {
        StatsTimeRange.DAYS_7 -> AppStrings.pick(lang, "近 7 天", "7 Days", "近 7 天")
        StatsTimeRange.THIS_MONTH -> AppStrings.pick(lang, "本月", "Month", "本月")
        StatsTimeRange.THIS_YEAR -> AppStrings.pick(lang, "本年", "Year", "本年")
        StatsTimeRange.ALL -> AppStrings.pick(lang, "全部", "All", "全部")
    }
}

internal fun localizedPosterRangeBadge(range: StatsTimeRange, lang: AppLang): String {
    return when (range) {
        StatsTimeRange.DAYS_7 -> AppStrings.pick(lang, "近 7 天", "Last 7 Days", "近 7 天")
        StatsTimeRange.THIS_MONTH -> AppStrings.pick(lang, "本月", "This Month", "本月")
        StatsTimeRange.THIS_YEAR -> AppStrings.pick(lang, "年度", "This Year", "年度")
        StatsTimeRange.ALL -> AppStrings.pick(lang, "累计", "All Time", "累計")
    }
}

internal fun localizedSchemeShareName(item: SchemeShareItem, lang: AppLang): String {
    return when (item.key) {
        InputScheme.FLYPY_TABLE.name -> AppStrings.pick(lang, "小鹤音形", "Flypy 4-Code", "小鶴音形")
        InputScheme.RIME_PINYIN.name -> AppStrings.pick(lang, "雾凇全拼", "Full Pinyin", "霧凇全拼")
        InputScheme.RIME_T9_PINYIN.name -> AppStrings.pick(lang, "九键拼音", "9-Key T9", "九鍵拼音")
        InputScheme.RIME_DOUBLE_PINYIN_FLYPY.name -> AppStrings.pick(lang, "小鹤双拼", "Double Pinyin", "小鶴雙拼")
        InputScheme.RIME_CUSTOM.name -> AppStrings.pick(lang, "自定义码表", "Custom Rime", "自訂碼表")
        SchemeStatsKeys.ENGLISH_DIRECT -> AppStrings.pick(lang, "英文直输", "English QWERTY", "英文直輸")
        else -> item.displayName
    }
}

internal fun localizedSceneCategory(rawCategory: String, lang: AppLang): String {
    return when (rawCategory) {
        "即时社交" -> AppStrings.pick(lang, "即时社交", "Social Chat", "即時社交")
        "职场协作" -> AppStrings.pick(lang, "职场协作", "Work Collab", "職場協作")
        "邮件与文档" -> AppStrings.pick(lang, "邮件与文档", "Mail & Docs", "郵件與文件")
        "编程与 AI" -> AppStrings.pick(lang, "编程与 AI", "Code & AI", "程式與 AI")
        "跨应用快输" -> AppStrings.pick(lang, "跨应用快输", "Quick Capsule", "跨應用快輸")
        "日常表达" -> AppStrings.pick(lang, "日常表达", "Daily Typing", "日常表達")
        else -> rawCategory
    }
}

internal fun localizedMetaphorLine(snapshot: InsightsSnapshot, lang: AppLang): String {
    if (lang == AppLang.ZH_CN && snapshot.metaphorLine.isNotBlank()) {
        return snapshot.metaphorLine
    }
    val totalChars = snapshot.totalChars
    val savedMinutes = snapshot.savedMinutes
    return when {
        totalChars <= 0 -> AppStrings.pick(
            lang,
            "每一次开口与落键，都在本地为你沉淀静默的数字注脚。",
            "Every spoken thought and keystroke stays strictly private as local numeric metrics.",
            "每一次開口與落鍵，都在本機為你沉澱靜默的數字註腳。"
        )
        totalChars >= 100_000 -> AppStrings.pick(
            lang,
            "累计输出约等于一本中篇小说，想法从未因打字速度而停顿。",
            "Roughly the length of a novella — your ideas never slowed down for typing.",
            "累計輸出約等於一本中篇小說，想法從未因打字速度而停頓。"
        )
        totalChars >= 20_000 -> {
            val essays = maxOf(1, totalChars / 1500)
            AppStrings.pick(
                lang,
                "约等于 $essays 篇深度长文，表达节奏从容顺手。",
                "Equivalent to ~$essays long-form essays expressed effortlessly.",
                "約等於 $essays 篇深度長文，表達節奏從容順手。"
            )
        }
        totalChars >= 3_000 -> {
            val notes = maxOf(1, totalChars / 800)
            AppStrings.pick(
                lang,
                "相当于 $notes 篇日常随笔，语音与键盘配合默契。",
                "Roughly $notes daily notes — voice and keyboard working in sync.",
                "相當於 $notes 篇日常隨筆，語音與鍵盤配合默契。"
            )
        }
        savedMinutes >= 5 -> AppStrings.pick(
            lang,
            "这段时间已悄悄为你省下约 $savedMinutes 分钟手动敲击。",
            "Quietly saved you ~$savedMinutes minutes of manual thumb typing.",
            "這段時間已悄悄為你省下約 $savedMinutes 分鐘手動敲擊。"
        )
        else -> {
            val formatted = String.format(Locale.US, "%,d", totalChars.coerceAtLeast(0))
            AppStrings.pick(
                lang,
                "已在本地轻声记录 $formatted 字表达，越用越顺手。",
                "Logged $formatted characters locally — getting smoother with every use.",
                "已在本機輕聲記錄 $formatted 字表達，越用越順手。"
            )
        }
    }
}

internal fun localizedRhythmHeadline(snapshot: InsightsSnapshot, lang: AppLang): String {
    if (lang == AppLang.ZH_CN && snapshot.rhythmHeadline.isNotBlank()) {
        return snapshot.rhythmHeadline
    }
    val combined = IntArray(24) { h ->
        snapshot.hourlyVoiceChars.getOrElse(h) { 0 }.coerceAtLeast(0) +
            snapshot.hourlyKeyChars.getOrElse(h) { 0 }.coerceAtLeast(0)
    }
    val total = combined.sum()
    if (total <= 0) {
        return AppStrings.pick(
            lang,
            "开始输入后，这里将浮现你的 24 小时表达节律",
            "Start typing or dictating to uncover your 24-hour rhythm",
            "開始輸入後，這裡將浮現你的 24 小時表達節律"
        )
    }
    var peakHour = 0
    var peakVal = -1
    for (h in 0..23) {
        if (combined[h] > peakVal) {
            peakVal = combined[h]
            peakHour = h
        }
    }
    val rangeStr = String.format(Locale.US, "%02d:00-%02d:00", peakHour, (peakHour + 1) % 24)
    return when (peakHour) {
        in 5..8 -> AppStrings.pick(lang, "清晨思绪清晰，$rangeStr 是你的表达高峰", "Crisp morning focus · Peak output at $rangeStr", "清晨思緒清晰，$rangeStr 是你的表達高峰")
        in 9..11 -> AppStrings.pick(lang, "上午专注高效，$rangeStr 输出最为集中", "Deep morning flow · Peak output at $rangeStr", "上午專注高效，$rangeStr 輸出最為集中")
        in 12..13 -> AppStrings.pick(lang, "午间节奏轻快，$rangeStr 表达最活跃", "Midday momentum · Most active at $rangeStr", "午間節奏輕快，$rangeStr 表達最活躍")
        in 14..17 -> AppStrings.pick(lang, "午后表达渐入佳境，$rangeStr 迎来峰值", "Afternoon stride · Peak expression at $rangeStr", "午後表達漸入佳境，$rangeStr 迎來峰值")
        in 18..21 -> AppStrings.pick(lang, "傍晚与晚间灵感充沛，$rangeStr 最爱表达", "Evening inspiration · Most active at $rangeStr", "傍晚與晚間靈感充沛，$rangeStr 最愛表達")
        else -> AppStrings.pick(lang, "偏爱静谧夜色，$rangeStr 是你的灵感高光时段", "Night owl flow · Peak inspiration at $rangeStr", "偏愛靜謐夜色，$rangeStr 是你的靈感高光時段")
    }
}

internal fun localizedMilestoneTexts(
    item: MilestoneItem,
    snapshot: InsightsSnapshot,
    lang: AppLang
): Triple<String, String, String> {
    if (lang == AppLang.ZH_CN) {
        return Triple(item.title, item.description, item.progressText)
    }
    return when (item.id) {
        "chars_expression" -> {
            val is100k = snapshot.lifetimeTotalChars >= 100_000 || item.title.contains("十万")
            val title = if (is100k) {
                AppStrings.pick(lang, "十万字表达", "100k Chars", "十萬字表達")
            } else {
                AppStrings.pick(lang, "万字表达", "10k Chars", "萬字表達")
            }
            val desc = if (is100k) {
                AppStrings.pick(lang, item.description, "Surpassed 100,000 cumulative voice & keyboard characters", "累計語音與鍵盤輸出突破 100,000 字")
            } else {
                AppStrings.pick(lang, item.description, "Reached 10,000 cumulative voice & keyboard characters", "累計語音與鍵盤輸出滿 10,000 字")
            }
            val countStr = String.format(Locale.US, "%,d", snapshot.lifetimeTotalChars.coerceAtLeast(0))
            val prog = if (item.unlocked) {
                AppStrings.pick(lang, item.progressText, "Unlocked · $countStr chars", "已達成 · 累計 $countStr 字")
            } else {
                AppStrings.pick(lang, item.progressText, "$countStr / 10,000 chars", "$countStr / 10,000 字")
            }
            Triple(title, desc, prog)
        }
        "voice_master" -> {
            val title = AppStrings.pick(lang, "口述达人", "Voice Master", "口述達人")
            val desc = AppStrings.pick(lang, item.description, "Complete 100 voice dictations", "累計完成 100 次語音輸入")
            val num = item.progressText.filter { it.isDigit() || it == '/' }.substringBefore('/').ifBlank { "0" }
            val prog = if (item.unlocked) {
                val c = item.progressText.filter { it.isDigit() }.ifBlank { "100" }
                AppStrings.pick(lang, item.progressText, "Unlocked · $c sessions", "已達成 · $c 次")
            } else {
                AppStrings.pick(lang, item.progressText, "$num / 100 sessions", "$num / 100 次")
            }
            Triple(title, desc, prog)
        }
        "keyboard_artisan" -> {
            val title = AppStrings.pick(lang, "键盘匠人", "Key Artisan", "鍵盤匠人")
            val desc = AppStrings.pick(lang, item.description, "Type 10,000 characters on the keyboard", "鍵盤累計輸出滿 10,000 字")
            val prog = if (item.unlocked) {
                val num = item.progressText.substringAfter("·").replace("字", "").trim()
                AppStrings.pick(lang, item.progressText, "Unlocked · $num chars", "已達成 · $num 字")
            } else {
                val raw = item.progressText.replace("字", "").trim()
                AppStrings.pick(lang, item.progressText, "$raw chars", "$raw 字")
            }
            Triple(title, desc, prog)
        }
        "snippet_magician" -> {
            val title = AppStrings.pick(lang, "短语魔术师", "Snippet Pro", "短語魔術師")
            val desc = AppStrings.pick(lang, item.description, "Expand slash or voice snippets 30 times", "快捷短語累計命中 30 次")
            val prog = if (item.unlocked) {
                val c = item.progressText.filter { it.isDigit() }.ifBlank { "30" }
                AppStrings.pick(lang, item.progressText, "Unlocked · $c hits", "已達成 · $c 次")
            } else {
                val num = item.progressText.substringBefore('/').trim()
                AppStrings.pick(lang, item.progressText, "$num / 30 hits", "$num / 30 次")
            }
            Triple(title, desc, prog)
        }
        "offline_guardian" -> {
            val title = AppStrings.pick(lang, "离线守护者", "Offline Guard", "離線守護者")
            val desc = AppStrings.pick(lang, item.description, "Run ≥10 on-device offline inferences with ≥50% share", "本機離線推理滿 10 次且佔比 ≥ 50%")
            val prog = if (item.unlocked) {
                val pct = item.progressText.substringAfterLast("占比").trim().ifBlank { "${snapshot.offlinePercent}%" }
                AppStrings.pick(lang, item.progressText, "Unlocked · $pct offline", "已達成 · 離線佔比 $pct")
            } else {
                val replaced = item.progressText
                    .replace("次", " runs")
                    .replace("占比 ", "")
                val tw = item.progressText.replace("占比", "佔比")
                AppStrings.pick(lang, item.progressText, "$replaced offline", tw)
            }
            Triple(title, desc, prog)
        }
        else -> Triple(item.title, item.description, item.progressText)
    }
}

internal fun localizedHeatmapDateLabel(cell: HeatmapDayCell, lang: AppLang): String {
    val month = (cell.dateKey / 100) % 100
    val day = cell.dateKey % 100
    if (month !in 1..12 || day !in 1..31) return cell.dateLabel
    return if (lang == AppLang.EN) {
        val mShort = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.US)
        "$mShort $day"
    } else {
        "${month}月${day}日"
    }
}

internal fun localizedMonthHeaderLabel(point: MonthlyTrendPoint, lang: AppLang): String {
    if (point.month !in 1..12) return "${point.year} ${point.label}"
    return if (lang == AppLang.EN) {
        val mShort = Month.of(point.month).getDisplayName(TextStyle.SHORT, Locale.US)
        "$mShort ${point.year}"
    } else {
        "${point.year}年${point.month}月"
    }
}

internal fun localizedTokenFeatureLabel(key: String, fallbackLabel: String, lang: AppLang): String {
    return when (key) {
        TokenFeatureKeys.VOICE_POLISH -> AppStrings.pick(lang, "语音口述润色", "Voice Dictate & Polish", "語音口述潤飾")
        TokenFeatureKeys.SPEAK_TO_EDIT -> AppStrings.pick(lang, "语音/快捷改写", "Voice / Quick Edit", "語音/快捷改寫")
        TokenFeatureKeys.HELP_ME_WRITE -> AppStrings.pick(lang, "帮我写 / 灵感成稿", "Help Me Write", "幫我寫 / 靈感成稿")
        TokenFeatureKeys.TRANSLATE -> AppStrings.pick(lang, "实时语音翻译", "Live Voice Translate", "即時語音翻譯")
        TokenFeatureKeys.ASK_AI -> AppStrings.pick(lang, "问 AI / == 直问", "Ask AI", "問 AI / == 直問")
        TokenFeatureKeys.SENTENCE_COMPLETION -> AppStrings.pick(lang, "AI 整句续写", "AI Sentence Completion", "AI 整句續寫")
        TokenFeatureKeys.MULTIMODAL_AUDIO -> AppStrings.pick(lang, "多模态音频直出", "Multimodal Audio Direct", "多模態音訊直出")
        TokenFeatureKeys.API_TEST -> AppStrings.pick(lang, "连通性实测", "API Connection Test", "連通性實測")
        else -> fallbackLabel
    }
}

private fun formatLocalizedVoiceDuration(durationMs: Long, lang: AppLang): String {
    val totalSec = (durationMs.coerceAtLeast(0L) + 500L) / 1000L
    if (totalSec <= 0L) return AppStrings.pick(lang, "0 秒", "0s", "0 秒")
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (lang == AppLang.EN) {
        when {
            hours > 0L -> "${hours}h ${mins}m"
            mins > 0L -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    } else {
        when {
            hours > 0L -> AppStrings.pick(lang, "${hours}小时${mins}分", "${hours}h ${mins}m", "${hours}小時${mins}分")
            mins > 0L -> "${mins}分${secs}秒"
            else -> "${secs}秒"
        }
    }
}

@Composable
fun InsightsScreen(
    repository: AppRepository,
    onBack: () -> Unit,
    onOpenHistoryForDate: (Int?) -> Unit,
    onOpenPrivacySettings: () -> Unit,
    onOpenStylesSettings: () -> Unit,
    onOpenPlayground: () -> Unit,
    onOpenApiUsage: () -> Unit = {}
) {
    val context = LocalContext.current
    val appLang = LocalAppLanguage.current
    val statsRevision by repository.dailyStatsRevision.collectAsState()
    val history by repository.history.collectAsState()
    val settings by repository.settings.collectAsState()

    var selectedRange by rememberSaveable { mutableStateOf(StatsTimeRange.DAYS_7) }
    val snapshot = remember(selectedRange, statsRevision, history, settings) {
        repository.computeInsightsSnapshot(selectedRange)
    }
    val allTokenSnapshot = remember(statsRevision) {
        repository.computeTokenUsageSnapshot(StatsTimeRange.ALL)
    }
    val isGlobalColdStart = snapshot.isColdStart &&
        snapshot.lifetimeTotalChars == 0 &&
        snapshot.lifetimeActiveDays == 0 &&
        !allTokenSnapshot.hasData
    val isRangeQuietOnly = snapshot.isColdStart && !isGlobalColdStart

    var showPosterStudio by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val isScrolled by remember { derivedStateOf { scrollState.value > 8 } }

    val topBarBg by animateColorAsState(
        targetValue = if (isScrolled) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        } else {
            MaterialTheme.colorScheme.background
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "insightsTopBarBg"
    )
    val hairlineAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.42f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "insightsHairlineAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Sticky Scroll-Aware Frosted Top Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(topBarBg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = tr("返回", "Back", "返回"),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = tr("表达洞察", "Expression Insights", "表達洞察"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(TypeFreeEmerald.copy(alpha = 0.14f))
                            .border(0.6.dp, TypeFreeEmerald.copy(alpha = 0.35f), CircleShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = tr("永久统计", "Lifetime", "永久統計"),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            color = if (LocalTypeFreeKeyboardPalette.current.isDark) {
                                TypeFreeEmeraldLight
                            } else {
                                Color(0xFF059669)
                            }
                        )
                    }
                }

                val shareInteraction = remember { MutableInteractionSource() }
                Surface(
                    onClick = {
                        if (isGlobalColdStart) {
                            Toast.makeText(
                                context,
                                AppStrings.pick(
                                    appLang,
                                    "先说几句或打几行字，即可生成专属表达手账",
                                    "Dictate or type a few words first to generate your poster",
                                    "先說幾句或打幾行字，即可產生專屬表達手賬"
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            showPosterStudio = true
                        }
                    },
                    interactionSource = shareInteraction,
                    shape = CircleShape,
                    color = if (isGlobalColdStart) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .springPressScale(0.94f, shareInteraction)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = null,
                            tint = if (isGlobalColdStart) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = tr("定制手账", "Poster", "定製手賬"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            color = if (isGlobalColdStart) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            }
                        )
                    }
                }
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = hairlineAlpha)
            )
        }

        // Scrollable Body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isGlobalColdStart) {
                InsightsColdStartCard(onOpenPlayground = onOpenPlayground)
                InsightsPrivacyFooterCard(
                    snapshot = snapshot,
                    onOpenPrivacySettings = onOpenPrivacySettings
                )
            } else {
                // 1. Time Range Segmented Control
                SpringSegmentedControl(
                    options = StatsTimeRange.entries.map { range ->
                        localizedRangeLabel(range, appLang)
                    },
                    selectedIndex = StatsTimeRange.entries.indexOf(selectedRange),
                    onSelect = { idx ->
                        selectedRange = StatsTimeRange.entries.getOrElse(idx) { StatsTimeRange.DAYS_7 }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (isRangeQuietOnly) {
                    InsightsRangeQuietCard(
                        rangeLabel = localizedRangeLabel(selectedRange, appLang),
                        lifetimeChars = snapshot.lifetimeTotalChars,
                        lifetimeDays = snapshot.lifetimeActiveDays,
                        onSwitchToAll = { selectedRange = StatsTimeRange.ALL },
                        onOpenPlayground = onOpenPlayground
                    )
                } else {
                    // 2. Main Hero & Dual-Track Expression Card
                    HeroExpressionCard(snapshot = snapshot)

                    // 3. Keystroke Savings Efficiency Bill Card
                    EfficiencyBillCard(snapshot = snapshot)
                }

                // 3.5 Unified Model Token & Voice Duration Observability Card
                LocalizedTokenUsageObservabilityCard(
                    repository = repository,
                    externalRange = selectedRange
                )

                // 4. Companion Growth Trajectory Card (Monthly Trend / Heatmap)
                GrowthTrajectoryCard(
                    snapshot = snapshot,
                    onOpenHistoryForDate = onOpenHistoryForDate
                )

                if (!isRangeQuietOnly) {
                    // 5. 24h Dual-Track Rhythm Card
                    Rhythm24hCard(snapshot = snapshot)

                    // 6. Where You Express Most (Intent & App Scenes)
                    ScenesAndIntentsCard(
                        snapshot = snapshot,
                        onOpenStylesSettings = onOpenStylesSettings
                    )
                }

                // 7. Lexicon & Habitual Word Chains Card (Anti-peek blur)
                LexiconAndWordChainsCard(snapshot = snapshot)

                // 9. Lifetime Milestones Wall
                MilestonesWallCard(snapshot = snapshot)

                // 10. Bottom Transparent Privacy Footer Card
                InsightsPrivacyFooterCard(
                    snapshot = snapshot,
                    onOpenPrivacySettings = onOpenPrivacySettings
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }

    if (showPosterStudio) {
        SharePosterStudioDialog(
            snapshot = snapshot,
            onDismiss = { showPosterStudio = false }
        )
    }
}

@Composable
private fun InsightsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = ContinuousRoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 0.8.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun InsightsColdStartCard(
    onOpenPlayground: () -> Unit
) {
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val emerald = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald

    InsightsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(ContinuousRoundedCornerShape(18.dp))
                    .background(emerald.copy(alpha = 0.14f))
                    .border(1.dp, emerald.copy(alpha = 0.35f), ContinuousRoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = TypeFreeIcons.VoiceWaveform,
                    contentDescription = null,
                    tint = emerald,
                    modifier = Modifier.size(28.dp)
                )
            }

            Text(
                text = tr(
                    "正在认识你的表达习惯",
                    "Getting to Know Your Style",
                    "正在認識你的表達習慣"
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = tr(
                    "当你开口说话或敲击键盘时，TypeFree 会在本机记录纯数字的表达节奏、语音与键盘比例，以及常用输入场景——不保存任何原文，无痕与密码框自动零计数。",
                    "As you dictate or type, TypeFree tallies pure numeric metrics on-device — your voice vs. keyboard ratio, 24h rhythm, and active scenes — with zero raw text stored and zero tracking in Incognito or password fields.",
                    "當你開口說話或敲擊鍵盤時，TypeFree 會在本機記錄純數字的表達節奏、語音與鍵盤比例，以及常用輸入場景——不儲存任何原文，無痕與密碼框自動零計數。"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    tr("双轨字数账单", "Dual-Track", "雙軌字數帳單"),
                    tr("24h 作息节律", "24h Rhythm", "24h 作息節律"),
                    tr("零正文端侧统计", "Zero-Text Local", "零正文端側統計")
                ).forEach { tag ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(ContinuousRoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tag,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val ctaInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = onOpenPlayground,
                interactionSource = ctaInteraction,
                shape = ContinuousRoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .springPressScale(0.97f, ctaInteraction)
            ) {
                Icon(
                    imageVector = TypeFreeIcons.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = tr("去语音沙盒试说一下", "Try Voice Sandbox", "去語音沙盒試說一下"),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun InsightsRangeQuietCard(
    rangeLabel: String,
    lifetimeChars: Int,
    lifetimeDays: Int,
    onSwitchToAll: () -> Unit,
    onOpenPlayground: () -> Unit
) {
    val appLang = LocalAppLanguage.current
    val charsFormatted = formatInsightsCompactChars(lifetimeChars, appLang)
    InsightsCard {
        Text(
            text = tr(
                "${rangeLabel}暂无新的输入记录",
                "No input activity in $rangeLabel",
                "${rangeLabel}尚無新的輸入記錄"
            ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = tr(
                "你在 TypeFree 已累计陪伴 $lifetimeDays 天、共表达 $charsFormatted 字，可切换到「全部」查看完整累计数据。",
                "You've used TypeFree across $lifetimeDays active days ($charsFormatted chars total). Switch to 'All' to view your full history.",
                "你在 TypeFree 已累計陪伴 $lifetimeDays 天、共表達 $charsFormatted 字，可切換到「全部」檢視完整累計資料。"
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 19.sp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSwitchToAll,
                shape = ContinuousRoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = tr("查看全部累计", "View All-Time", "檢視全部累計"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp,
                    maxLines = 1
                )
            }
            Surface(
                onClick = onOpenPlayground,
                shape = ContinuousRoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tr("去语音沙盒试试", "Voice Sandbox", "去語音沙盒試試"),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroExpressionCard(snapshot: InsightsSnapshot) {
    val appLang = LocalAppLanguage.current
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val emerald = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = tr("解放双手 · 全量表达", "Hands-Free · Total Expression", "解放雙手 · 全量表達"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (snapshot.streakDays > 0) {
                            TypeFreeAmber.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        }
                    )
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (snapshot.streakDays > 0) {
                        tr(
                            "🔥 连续 ${snapshot.streakDays} 天",
                            "🔥 ${snapshot.streakDays}d Streak",
                            "🔥 連續 ${snapshot.streakDays} 天"
                        )
                    } else {
                        tr(
                            "活跃 ${snapshot.activeDaysInRange} 天",
                            "${snapshot.activeDaysInRange}d Active",
                            "活躍 ${snapshot.activeDaysInRange} 天"
                        )
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = if (snapshot.streakDays > 0) {
                        if (isDark) TypeFreeAmber else Color(0xFFB45309)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // Main Rolling Total Chars + Saved Minutes Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                RollingDigitCounter(
                    text = formatInsightsCompactChars(snapshot.totalChars, appLang),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 38.sp,
                        lineHeight = 42.sp,
                        letterSpacing = (-1.0).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = tr("字", "chars", "字"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }

            Surface(
                shape = CircleShape,
                color = emerald.copy(alpha = 0.14f),
                border = BorderStroke(0.7.dp, emerald.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = tr("省下", "Saved", "省下"),
                        fontSize = 11.sp,
                        color = emerald,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false
                    )
                    RollingDigitCounter(
                        text = "${snapshot.savedMinutes}",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                        color = emerald,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = tr("分钟", "min", "分鐘"),
                        fontSize = 11.sp,
                        color = emerald,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Measured WPM & Latency readout
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val multStr = String.format(Locale.US, "%.1f", snapshot.speedMultiplier)
            val speedSummary = if (snapshot.hasMeasuredWpm) {
                tr(
                    "实测口述 ${snapshot.averageWpm} 字/分（约 ${multStr}× 拇指打字）",
                    "Measured ${snapshot.averageWpm} CPM (~${multStr}× thumb typing)",
                    "實測口述 ${snapshot.averageWpm} 字/分（約 ${multStr}× 拇指打字）"
                )
            } else {
                tr(
                    "口述满 3 秒后自动测算实测语速",
                    "Speak for 3s+ to measure live dictation speed",
                    "口述滿 3 秒後自動測算實測語速"
                )
            }
            val latencySuffix = if (snapshot.avgLatencyMs > 0L) {
                val lat = formatLatency(snapshot.avgLatencyMs)
                tr(" · 响应 $lat", " · Latency $lat", " · 回應 $lat")
            } else {
                ""
            }
            Text(
                text = speedSummary + latencySuffix,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val metaphorText = remember(snapshot, appLang) {
            localizedMetaphorLine(snapshot, appLang)
        }
        if (metaphorText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousRoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    text = metaphorText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Dual Track Ratio Bar
        LocalizedDualTrackRatioBar(
            voiceChars = snapshot.voiceChars,
            keyChars = snapshot.keyChars
        )

        // 2x2 Keyboard Schemes Grid
        if (snapshot.schemeShares.isNotEmpty()) {
            val topSchemes = remember(snapshot.schemeShares) { snapshot.schemeShares.take(4) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                topSchemes.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { scheme ->
                            SchemeMiniTile(
                                item = scheme,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Offline Privacy Escort Strip
        val escortShape = ContinuousRoundedCornerShape(13.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(escortShape)
                .background(
                    if (snapshot.hasOfflineSplitData) {
                        emerald.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    }
                )
                .border(
                    0.6.dp,
                    if (snapshot.hasOfflineSplitData) {
                        emerald.copy(alpha = 0.30f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    },
                    escortShape
                )
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = if (snapshot.hasOfflineSplitData) emerald else MaterialTheme.colorScheme.onSurfaceVariant,
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
                        "本机纯数字累计护航 · 不保存也不上传任何正文",
                        "100% local numeric stats · Zero raw text stored or uploaded",
                        "本機純數字累計護航 · 不儲存也不上傳任何正文"
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LocalizedDualTrackRatioBar(
    voiceChars: Int,
    keyChars: Int,
    modifier: Modifier = Modifier
) {
    val appLang = LocalAppLanguage.current
    val safeVoice = voiceChars.coerceAtLeast(0)
    val safeKey = keyChars.coerceAtLeast(0)
    val total = safeVoice + safeKey
    val voicePercent = if (total > 0) ((safeVoice * 100f) / total).toInt().coerceIn(0, 100) else 0
    val keyPercent = if (total > 0) (100 - voicePercent).coerceIn(0, 100) else 0

    val targetVoiceFrac = if (total > 0) safeVoice.toFloat() / total.toFloat() else 0f
    val animatedVoiceFrac by animateFloatAsState(
        targetValue = targetVoiceFrac,
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 340f),
        label = "dualTrackVoiceFraction"
    )

    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val voiceColor = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val keyColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.72f else 0.78f)
    val emptyTrackColor = MaterialTheme.colorScheme.surfaceVariant
    val unitStr = tr("字", "chars", "字")

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(voiceColor)
                )
                Text(
                    text = tr("语音口述 $voicePercent%", "Voice $voicePercent%", "語音口述 $voicePercent%"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = "${formatInsightsCompactChars(safeVoice, appLang)} $unitStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "${formatInsightsCompactChars(safeKey, appLang)} $unitStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = tr("键盘 $keyPercent%", "Keyboard $keyPercent%", "鍵盤 $keyPercent%"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(keyColor)
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
        ) {
            if (size.width <= 1f || size.height <= 1f) return@Canvas
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            if (total <= 0) {
                drawRoundRect(
                    color = emptyTrackColor,
                    size = size,
                    cornerRadius = radius
                )
                return@Canvas
            }

            val gap = 2.5.dp.toPx()
            val minSeg = 8.dp.toPx().coerceAtMost(size.width * 0.25f)

            when {
                safeVoice > 0 && safeKey == 0 -> {
                    drawRoundRect(color = voiceColor, size = size, cornerRadius = radius)
                }
                safeKey > 0 && safeVoice == 0 -> {
                    drawRoundRect(color = keyColor, size = size, cornerRadius = radius)
                }
                else -> {
                    val usableWidth = (size.width - gap).coerceAtLeast(minSeg * 2f)
                    val voiceWidth = (usableWidth * animatedVoiceFrac).coerceIn(minSeg, usableWidth - minSeg)
                    val keyWidth = (usableWidth - voiceWidth).coerceAtLeast(minSeg)

                    drawRoundRect(
                        color = voiceColor,
                        topLeft = Offset.Zero,
                        size = Size(voiceWidth, size.height),
                        cornerRadius = radius
                    )
                    drawRoundRect(
                        color = keyColor,
                        topLeft = Offset(voiceWidth + gap, 0f),
                        size = Size(keyWidth, size.height),
                        cornerRadius = radius
                    )
                }
            }
        }
    }
}

@Composable
private fun SchemeMiniTile(
    item: SchemeShareItem,
    modifier: Modifier = Modifier
) {
    val appLang = LocalAppLanguage.current
    val tileShape = ContinuousRoundedCornerShape(14.dp)
    val animatedPct by animateFloatAsState(
        targetValue = (item.percent / 100f).coerceIn(0.04f, 1f),
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 360f),
        label = "schemePct_${item.key}"
    )
    Column(
        modifier = modifier
            .clip(tileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = localizedSchemeShareName(item, appLang),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "${item.percent}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
        Text(
            text = "${formatInsightsCompactChars(item.chars, appLang)} ${tr("字", "chars", "字")}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedPct)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.72f))
            )
        }
    }
}

@Composable
private fun EfficiencyBillCard(snapshot: InsightsSnapshot) {
    val appLang = LocalAppLanguage.current
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val emerald = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val tileShape = ContinuousRoundedCornerShape(16.dp)

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = tr("⚡ 少敲键效率账单", "⚡ Keystroke Savings", "⚡ 少敲鍵效率帳單"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = tr("短语 · 联想 · 杠杆", "Snippets · L2 · AI", "短語 · 聯想 · 槓桿"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(tileShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = tr("快捷短语命中", "Slash Snippets", "快捷短語命中"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    RollingDigitCounter(
                        text = formatInsightsCompactChars(snapshot.snippetSavedChars, appLang),
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = tr("字", "chars", "字"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                Text(
                    text = tr(
                        "省下输入 · 触发 ${snapshot.snippetHits} 次",
                        "Saved · ${snapshot.snippetHits} hits",
                        "省下輸入 · 觸發 ${snapshot.snippetHits} 次"
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(tileShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = tr("L2 私人联想采纳", "L2 Predictions", "L2 私人聯想採納"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    RollingDigitCounter(
                        text = formatInsightsCompactChars(snapshot.predictionSavedKeys, appLang),
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = tr("键", "keys", "鍵"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                Text(
                    text = tr(
                        "免敲按键 · 采纳 ${snapshot.predictionHits} 次",
                        "Saved · ${snapshot.predictionHits} adopts",
                        "免敲按鍵 · 採納 ${snapshot.predictionHits} 次"
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (snapshot.helpWriteLeverage > 1.0f) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousRoundedCornerShape(14.dp))
                    .background(emerald.copy(alpha = 0.11f))
                    .border(0.6.dp, emerald.copy(alpha = 0.30f), ContinuousRoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("「帮我写」平均表达杠杆倍率", "\"Help Write\" Expansion Leverage", "「幫我寫」平均表達槓桿倍率"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = tr(
                            "简短口述扩展为完整可发送成文",
                            "Brief voice prompts expanded into ready-to-send drafts",
                            "簡短口述擴展為完整可傳送成文"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${String.format(Locale.US, "%.1f", snapshot.helpWriteLeverage)}×",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = emerald,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalizedTokenUsageObservabilityCard(
    repository: AppRepository,
    externalRange: StatsTimeRange
) {
    val appLang = LocalAppLanguage.current
    val statsRevision by repository.dailyStatsRevision.collectAsState()
    var breakdownTab by rememberSaveable { mutableIntStateOf(0) }

    val snapshot: TokenUsageSnapshot = remember(externalRange, statsRevision) {
        repository.computeTokenUsageSnapshot(externalRange)
    }

    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val emerald = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val promptColor = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)
    val cachedColor = emerald
    val outputColor = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
    val reasoningColor = TypeFreeAmber

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ContinuousRoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.50f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = tr("模型 Token 与语音时长用量", "Model Tokens & Voice Duration", "模型 Token 與語音時長用量"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = tr(
                            "纯用量观测 · 不做费用估算 · 无痕自动零计数",
                            "Pure usage telemetry · No price estimates · Zero count in Incognito",
                            "純用量觀測 · 不做費用估算 · 無痕自動零計數"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (snapshot.estimatedCallCount > 0) {
                    Surface(
                        shape = CircleShape,
                        color = TypeFreeAmber.copy(alpha = 0.14f),
                        border = BorderStroke(0.6.dp, TypeFreeAmber.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = tr("含估算", "Est.", "含估算"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TypeFreeAmber,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            if (!snapshot.hasData) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ContinuousRoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = tr(
                            "当前时间范围暂无模型 Token 或语音转写记录。发起语音润色、帮我写或问 AI 后，这里会自动统计真实用量。",
                            "No model token or voice transcription records in this range yet. Real usage appears here after using Dictate, Help Write, or Ask AI.",
                            "目前時間範圍尚無模型 Token 或語音轉寫記錄。發起語音潤飾、幫我寫或問 AI 後，這裡會自動統計真實用量。"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                val rangeLbl = localizedRangeLabel(externalRange, appLang)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = tr("${rangeLbl}云端模型消耗", "$rangeLbl Cloud Token Usage", "${rangeLbl}雲端模型消耗"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RollingDigitCounter(
                                text = formatCompactTokens(snapshot.totalTokens),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Tokens",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        if (snapshot.totalTokens >= 1_000_000L) {
                            val exact = String.format(Locale.US, "%,d", snapshot.totalTokens.coerceAtLeast(0L))
                            Text(
                                text = tr("精确累计 $exact Tokens", "Exact total: $exact Tokens", "精確累計 $exact Tokens"),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (snapshot.cacheHitPercent > 0) {
                        Surface(
                            shape = CircleShape,
                            color = emerald.copy(alpha = 0.14f),
                            border = BorderStroke(0.6.dp, emerald.copy(alpha = 0.40f))
                        ) {
                            Text(
                                text = tr(
                                    "缓存命中 ${snapshot.cacheHitPercent}%",
                                    "Cached ${snapshot.cacheHitPercent}%",
                                    "快取命中 ${snapshot.cacheHitPercent}%"
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = emerald,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (snapshot.totalTokens > 0L) {
                    val total = snapshot.totalTokens.coerceAtLeast(1L).toFloat()
                    val segments = remember(snapshot) {
                        listOf(
                            snapshot.uncachedPromptTokens to promptColor,
                            snapshot.cachedTokens to cachedColor,
                            snapshot.visibleOutputTokens to outputColor,
                            snapshot.reasoningTokens to reasoningColor
                        ).filter { it.first > 0L }
                    }
                    if (segments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            segments.forEach { (count, color) ->
                                val targetWeight = (count.toFloat() / total).coerceAtLeast(0.03f)
                                val animWeight by animateFloatAsState(
                                    targetValue = targetWeight,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    label = "tokenSegWeight"
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(animWeight)
                                        .fillMaxHeight()
                                        .background(color)
                                )
                            }
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LocalizedTokenLegendChip(
                            dotColor = promptColor,
                            label = tr("输入", "Input", "輸入"),
                            value = formatCompactTokens(snapshot.uncachedPromptTokens)
                        )
                        if (snapshot.cachedTokens > 0L) {
                            LocalizedTokenLegendChip(
                                dotColor = cachedColor,
                                label = tr("缓存命中", "Cached", "快取命中"),
                                value = formatCompactTokens(snapshot.cachedTokens)
                            )
                        }
                        LocalizedTokenLegendChip(
                            dotColor = outputColor,
                            label = tr("输出", "Output", "輸出"),
                            value = formatCompactTokens(snapshot.visibleOutputTokens)
                        )
                        if (snapshot.reasoningTokens > 0L) {
                            LocalizedTokenLegendChip(
                                dotColor = reasoningColor,
                                label = tr("思考推理", "Reasoning", "思考推理"),
                                value = formatCompactTokens(snapshot.reasoningTokens)
                            )
                        }
                    }
                }

                if (snapshot.byFeature.isNotEmpty() || snapshot.byModel.isNotEmpty()) {
                    HorizontalDivider(
                        thickness = 0.6.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
                    )

                    SpringSegmentedControl(
                        options = listOf(
                            tr("按功能分布", "By Feature", "按功能分佈"),
                            tr("按模型分布", "By Model", "按模型分佈")
                        ),
                        selectedIndex = breakdownTab,
                        onSelect = { breakdownTab = it },
                        compact = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val activeList = if (breakdownTab == 0) snapshot.byFeature else snapshot.byModel
                    val barTint = if (breakdownTab == 0) promptColor else outputColor
                    if (activeList.isEmpty()) {
                        Text(
                            text = if (breakdownTab == 0) {
                                tr("暂无功能明细", "No feature breakdown yet", "尚無功能明細")
                            } else {
                                tr("暂无模型明细", "No model breakdown yet", "尚無模型明細")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            activeList.forEach { item ->
                                val displayLabel = if (breakdownTab == 0) {
                                    localizedTokenFeatureLabel(item.key, item.label, appLang)
                                } else {
                                    item.label
                                }
                                LocalizedTokenBreakdownRow(
                                    item = item,
                                    displayLabel = displayLabel,
                                    isMonospaceLabel = breakdownTab == 1,
                                    barColor = barTint
                                )
                            }
                        }
                    }
                }

                val totalVoiceDurMs = snapshot.cloudVoiceDurationMs + snapshot.offlineVoiceDurationMs
                if (totalVoiceDurMs > 0L || snapshot.offlineSavedCloudCalls > 0) {
                    HorizontalDivider(
                        thickness = 0.6.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
                    )
                    val totalMs = totalVoiceDurMs.coerceAtLeast(1L)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tr("语音转写时长分布", "Voice Transcription Duration", "語音轉寫時長分佈"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (snapshot.offlineSavedCloudCalls > 0) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(emerald.copy(alpha = 0.13f))
                                        .border(0.6.dp, emerald.copy(alpha = 0.35f), CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = tr(
                                            "本机离线免去 ${snapshot.offlineSavedCloudCalls} 次云端调用",
                                            "Saved ${snapshot.offlineSavedCloudCalls} cloud calls offline",
                                            "本機離線免去 ${snapshot.offlineSavedCloudCalls} 次雲端呼叫"
                                        ),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = emerald,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        if (snapshot.cloudVoiceDurationMs > 0L || snapshot.offlineVoiceDurationMs > 0L) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (snapshot.cloudVoiceDurationMs > 0L) {
                                    Box(
                                        modifier = Modifier
                                            .weight((snapshot.cloudVoiceDurationMs.toFloat() / totalMs.toFloat()).coerceAtLeast(0.04f))
                                            .fillMaxHeight()
                                            .background(promptColor)
                                    )
                                }
                                if (snapshot.offlineVoiceDurationMs > 0L) {
                                    Box(
                                        modifier = Modifier
                                            .weight((snapshot.offlineVoiceDurationMs.toFloat() / totalMs.toFloat()).coerceAtLeast(0.04f))
                                            .fillMaxHeight()
                                            .background(emerald)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                LocalizedTokenLegendChip(
                                    dotColor = promptColor,
                                    label = tr("云端转写", "Cloud ASR", "雲端轉寫"),
                                    value = formatLocalizedVoiceDuration(snapshot.cloudVoiceDurationMs, appLang)
                                )
                                LocalizedTokenLegendChip(
                                    dotColor = emerald,
                                    label = tr("本机离线", "Offline ASR", "本機離線"),
                                    value = formatLocalizedVoiceDuration(snapshot.offlineVoiceDurationMs, appLang)
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = tr(
                    "注：仅统计接口返回的 usage 实测值；若第三方反代未回传 usage，会按字符数估算并标记「含估算」。数据仅存于本机。",
                    "Note: Tallies provider-reported token usage (approximated and badged 'Est.' if a gateway omits usage metadata). Stored strictly on-device.",
                    "註：僅統計介面回傳的 usage 實測值；若第三方反代未回傳 usage，會按字元數估算並標記「含估算」。資料僅存於本機。"
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun LocalizedTokenLegendChip(
    dotColor: Color,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontFeatureSettings = "tnum"
            ),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun LocalizedTokenBreakdownRow(
    item: TokenShareItem,
    displayLabel: String,
    isMonospaceLabel: Boolean,
    barColor: Color
) {
    val progress by animateFloatAsState(
        targetValue = (item.percent / 100f).coerceIn(0.02f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tokenBreakdownBar"
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayLabel,
                style = if (isMonospaceLabel) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodySmall
                },
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${formatCompactTokens(item.tokens)} · ${item.percent}%",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum"
                ),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(barColor.copy(alpha = 0.85f))
            )
        }
    }
}

@Composable
private fun GrowthTrajectoryCard(
    snapshot: InsightsSnapshot,
    onOpenHistoryForDate: (Int?) -> Unit
) {
    val appLang = LocalAppLanguage.current
    var trajectoryTab by rememberSaveable { mutableIntStateOf(0) }

    val defaultMonth = remember(snapshot.monthlyTrend) {
        snapshot.monthlyTrend
            .lastOrNull { !it.isFuture && it.totalChars > 0 }?.month
            ?: LocalDate.now().monthValue
    }
    var selectedMonth by remember(snapshot.monthlyTrend) { mutableIntStateOf(defaultMonth) }

    val defaultHeatmapDateKey = remember(snapshot.heatmapDays) {
        snapshot.heatmapDays
            .lastOrNull { it.totalChars > 0 }?.dateKey
            ?: snapshot.heatmapDays.lastOrNull()?.dateKey
    }
    var selectedHeatmapKey by remember(snapshot.heatmapDays) {
        mutableStateOf(defaultHeatmapDateKey)
    }

    val lifetimeCharsStr = formatInsightsCompactChars(snapshot.lifetimeTotalChars, appLang)

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tr("陪伴成长轨迹", "Growth Trajectory", "陪伴成長軌跡"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = tr(
                        "累计陪伴 ${snapshot.lifetimeActiveDays} 天 · 累计 $lifetimeCharsStr 字",
                        "${snapshot.lifetimeActiveDays} active days · $lifetimeCharsStr chars lifetime",
                        "累計陪伴 ${snapshot.lifetimeActiveDays} 天 · 累計 $lifetimeCharsStr 字"
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            SpringSegmentedControl(
                options = listOf(
                    tr("月度趋势", "Monthly", "月度趨勢"),
                    tr("热力格", "Heatmap", "熱力格")
                ),
                selectedIndex = trajectoryTab,
                onSelect = { trajectoryTab = it },
                compact = true,
                modifier = Modifier.width(142.dp)
            )
        }

        if (trajectoryTab == 0) {
            LocalizedMonthlyStackedTrendChart(
                points = snapshot.monthlyTrend,
                selectedMonth = selectedMonth,
                onSelectMonth = { selectedMonth = it.month }
            )
            val activePoint = snapshot.monthlyTrend.firstOrNull { it.month == selectedMonth }
            if (activePoint != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ContinuousRoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = localizedMonthHeaderLabel(activePoint, appLang),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val totStr = formatInsightsCompactChars(activePoint.totalChars, appLang)
                    val vStr = formatInsightsCompactChars(activePoint.voiceChars, appLang)
                    val kStr = formatInsightsCompactChars(activePoint.keyChars, appLang)
                    Text(
                        text = if (activePoint.totalChars > 0) {
                            tr(
                                "共 $totStr 字 · 语音 $vStr / 键盘 $kStr",
                                "$totStr chars · Voice $vStr / Key $kStr",
                                "共 $totStr 字 · 語音 $vStr / 鍵盤 $kStr"
                            )
                        } else {
                            tr("本月暂无输入记录", "No records this month", "本月尚無輸入記錄")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            LocalizedCalendarHeatmapGrid(
                days = snapshot.heatmapDays,
                selectedDateKey = selectedHeatmapKey,
                onSelectDay = { selectedHeatmapKey = it.dateKey }
            )
            val activeCell = snapshot.heatmapDays.firstOrNull { it.dateKey == selectedHeatmapKey }
            if (activeCell != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ContinuousRoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val dateStr = localizedHeatmapDateLabel(activeCell, appLang)
                        val totStr = formatInsightsCompactChars(activeCell.totalChars, appLang)
                        val vStr = formatInsightsCompactChars(activeCell.voiceChars, appLang)
                        val kStr = formatInsightsCompactChars(activeCell.keyChars, appLang)
                        Text(
                            text = tr(
                                "$dateStr · 共 $totStr 字",
                                "$dateStr · $totStr chars",
                                "$dateStr · 共 $totStr 字"
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val detail = buildString {
                            append(tr("语音 $vStr · 键盘 $kStr", "Voice $vStr · Key $kStr", "語音 $vStr · 鍵盤 $kStr"))
                            if (activeCell.topAppName.isNotBlank()) {
                                append(tr(" · 常用于 ${activeCell.topAppName}", " · Top: ${activeCell.topAppName}", " · 常用於 ${activeCell.topAppName}"))
                            }
                        }
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        onClick = { onOpenHistoryForDate(activeCell.dateKey) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            0.7.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tr("看当日", "History", "看當日"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalizedCalendarHeatmapGrid(
    days: List<HeatmapDayCell>,
    selectedDateKey: Int?,
    onSelectDay: (HeatmapDayCell) -> Unit,
    modifier: Modifier = Modifier
) {
    val appLang = LocalAppLanguage.current
    if (days.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(84.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = tr("暂无热力足迹数据", "No heatmap data yet", "尚無熱力足跡資料"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val selectionRingColor = if (isDark) TypeFreeAmber else MaterialTheme.colorScheme.onSurface

    fun levelColor(level: Int): Color = when (level.coerceIn(0, 4)) {
        0 -> surfaceVariant.copy(alpha = if (isDark) 0.55f else 0.90f)
        1 -> TypeFreeEmerald.copy(alpha = if (isDark) 0.28f else 0.24f)
        2 -> TypeFreeEmerald.copy(alpha = if (isDark) 0.55f else 0.52f)
        3 -> if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
        else -> primaryColor
    }

    val columns = if (days.size <= 28) 7 else 14
    val rows = remember(days, columns) { days.chunked(columns) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        rows.forEach { rowCells ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.5.dp)
            ) {
                rowCells.forEach { cell ->
                    val isSelected = selectedDateKey != null && cell.dateKey == selectedDateKey
                    val tileShape = RoundedCornerShape(4.dp)
                    val interactionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(tileShape)
                            .background(levelColor(cell.intensityLevel))
                            .then(
                                if (isSelected) {
                                    Modifier.border(1.8.dp, selectionRingColor, tileShape)
                                } else {
                                    Modifier.border(
                                        0.5.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                        tileShape
                                    )
                                }
                            )
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                onSelectDay(cell)
                            }
                    )
                }
                if (rowCells.size < columns) {
                    repeat(columns - rowCells.size) {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${localizedHeatmapDateLabel(days.first(), appLang)} – ${localizedHeatmapDateLabel(days.last(), appLang)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = tr("轻", "Less", "輕"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
                for (lvl in 0..4) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(levelColor(lvl))
                    )
                }
                Text(
                    text = tr("高产", "More", "高產"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun LocalizedMonthlyStackedTrendChart(
    points: List<MonthlyTrendPoint>,
    selectedMonth: Int,
    onSelectMonth: (MonthlyTrendPoint) -> Unit,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(128.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = tr("暂无月度成长记录", "No monthly records yet", "尚無月度成長記錄"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val voiceBaseColor = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val keyBaseColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.28f else 0.35f)
    val selectedHighlightBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.55f else 0.75f)
    val futureStubColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)

    val maxTotal = remember(points) {
        points.maxOfOrNull { it.totalChars }?.coerceAtLeast(1) ?: 1
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(126.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(102.dp)
                    .align(Alignment.TopCenter)
            ) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                listOf(0.08f, 0.54f, 0.99f).forEachIndexed { idx, yFrac ->
                    val y = size.height * yFrac
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 0.8.dp.toPx(),
                        pathEffect = if (idx < 2) dash else null
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                points.forEach { point ->
                    val isSelected = point.month == selectedMonth
                    val targetTotalFrac = if (point.isFuture || point.totalChars <= 0) {
                        0f
                    } else {
                        (point.totalChars.toFloat() / maxTotal.toFloat()).coerceIn(0.08f, 1f)
                    }
                    val targetVoiceShare = if (point.totalChars > 0) {
                        point.voiceChars.toFloat() / point.totalChars.toFloat()
                    } else {
                        0f
                    }

                    val animTotalFrac by animateFloatAsState(
                        targetValue = targetTotalFrac,
                        animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
                        label = "monthTotal_${point.month}"
                    )
                    val animVoiceShare by animateFloatAsState(
                        targetValue = targetVoiceShare,
                        animationSpec = spring(dampingRatio = 0.80f, stiffness = 360f),
                        label = "monthVoice_${point.month}"
                    )

                    val interactionSource = remember { MutableInteractionSource() }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) selectedHighlightBg else Color.Transparent)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                onSelectMonth(point)
                            }
                            .padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 3.dp, vertical = 2.dp)
                        ) {
                            val barW = (size.width * 0.72f).coerceIn(5.dp.toPx(), 13.dp.toPx())
                            val left = (size.width - barW) / 2f
                            val radius = CornerRadius(barW / 2f, barW / 2f)

                            if (point.isFuture || point.totalChars <= 0) {
                                val stubH = 3.5.dp.toPx()
                                drawRoundRect(
                                    color = if (point.isFuture) futureStubColor else gridColor.copy(alpha = 0.55f),
                                    topLeft = Offset(left, size.height - stubH),
                                    size = Size(barW, stubH),
                                    cornerRadius = radius
                                )
                                return@Canvas
                            }

                            val totalH = (size.height * animTotalFrac).coerceAtLeast(6.dp.toPx())
                            val voiceH = if (point.voiceChars > 0) {
                                (totalH * animVoiceShare).coerceAtLeast(3.dp.toPx())
                            } else {
                                0f
                            }
                            val keyH = if (point.keyChars > 0) {
                                (totalH - voiceH).coerceAtLeast(3.dp.toPx())
                            } else {
                                0f
                            }
                            val vColor = voiceBaseColor.copy(alpha = if (isSelected) 1f else 0.74f)
                            val kColor = keyBaseColor.copy(alpha = if (isSelected) 0.86f else 0.42f)

                            if (keyH > 0f) {
                                val clampedKeyH = keyH.coerceAtMost(size.height)
                                drawRoundRect(
                                    color = kColor,
                                    topLeft = Offset(left, size.height - clampedKeyH),
                                    size = Size(barW, clampedKeyH),
                                    cornerRadius = radius
                                )
                            }
                            if (voiceH > 0f) {
                                val gap = if (keyH > 0f) 1.5.dp.toPx() else 0f
                                val clampedVoiceH = voiceH.coerceAtMost((size.height - keyH - gap).coerceAtLeast(3.dp.toPx()))
                                val topY = (size.height - keyH - gap - clampedVoiceH).coerceAtLeast(0f)
                                drawRoundRect(
                                    color = vColor,
                                    topLeft = Offset(left, topY),
                                    size = Size(barW, clampedVoiceH),
                                    cornerRadius = radius
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${point.month}",
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                isSelected -> MaterialTheme.colorScheme.onSurface
                                point.isFuture -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(voiceBaseColor)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = tr("语音", "Voice", "語音"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(keyBaseColor.copy(alpha = 0.72f))
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = tr("键盘", "Keyboard", "鍵盤"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp
            )
        }
    }
}

@Composable
private fun Rhythm24hCard(snapshot: InsightsSnapshot) {
    val appLang = LocalAppLanguage.current
    val peakHour = remember(snapshot.hourlyVoiceChars, snapshot.hourlyKeyChars) {
        var bestHour = -1
        var bestTotal = 0
        for (h in 0 until 24) {
            val total = snapshot.hourlyVoiceChars.getOrElse(h) { 0 } +
                snapshot.hourlyKeyChars.getOrElse(h) { 0 }
            if (total > bestTotal) {
                bestTotal = total
                bestHour = h
            }
        }
        if (bestHour >= 0) bestHour else LocalTime.now().hour
    }
    var selectedHour by remember(snapshot.range, peakHour) { mutableIntStateOf(peakHour) }

    val hourVoice = snapshot.hourlyVoiceChars.getOrElse(selectedHour) { 0 }.coerceAtLeast(0)
    val hourKey = snapshot.hourlyKeyChars.getOrElse(selectedHour) { 0 }.coerceAtLeast(0)
    val hourTotal = hourVoice + hourKey
    val hourVoicePct = if (hourTotal > 0) ((hourVoice * 100f) / hourTotal).toInt() else 0
    val hourKeyPct = if (hourTotal > 0) (100 - hourVoicePct) else 0

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = tr("24h 双轨作息节律", "24h Dual-Track Rhythm", "24h 雙軌作息節律"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                val timeStr = String.format(Locale.US, "%02d:00", selectedHour)
                Text(
                    text = if (hourTotal > 0) {
                        tr(
                            "$timeStr · 语$hourVoicePct% / 键$hourKeyPct%",
                            "$timeStr · V $hourVoicePct% / K $hourKeyPct%",
                            "$timeStr · 語$hourVoicePct% / 鍵$hourKeyPct%"
                        )
                    } else {
                        tr("$timeStr · 无记录", "$timeStr · Idle", "$timeStr · 無記錄")
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }

        DualTrack24hWaveform(
            hourlyVoice = snapshot.hourlyVoiceChars,
            hourlyKey = snapshot.hourlyKeyChars,
            selectedHour = selectedHour,
            onSelectHour = { selectedHour = it }
        )

        val headlineText = remember(snapshot, appLang) {
            localizedRhythmHeadline(snapshot, appLang)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContinuousRoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = headlineText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (hourTotal > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${formatInsightsCompactChars(hourTotal, appLang)} ${tr("字", "chars", "字")}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenesAndIntentsCard(
    snapshot: InsightsSnapshot,
    onOpenStylesSettings: () -> Unit
) {
    val appLang = LocalAppLanguage.current
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val emerald = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val helpWriteColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.80f)

    fun intentColor(mode: VoiceIntentMode): Color = when (mode) {
        VoiceIntentMode.DICTATE -> emerald
        VoiceIntentMode.HELP_ME_WRITE -> helpWriteColor
        VoiceIntentMode.SPEAK_TO_EDIT -> TypeFreeAmber
        VoiceIntentMode.TRANSLATE -> Color(0xFF0EA5E9)
    }

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = tr("你在哪最爱表达", "Top Apps & Intents", "你在哪最愛表達"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onOpenStylesSettings)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("场景风格", "App Styles", "場景風格"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        if (snapshot.intentShares.isNotEmpty()) {
            IntentStackedBar(
                items = snapshot.intentShares,
                colorOf = { intentColor(it.mode) }
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                snapshot.intentShares.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(intentColor(item.mode))
                        )
                        Text(
                            text = "${AppStrings.voiceIntentLabel(item.mode, appLang)} ${item.percent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (snapshot.intentShares.isNotEmpty() && snapshot.topAppScenes.isNotEmpty()) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        }

        if (snapshot.topAppScenes.isEmpty()) {
            Text(
                text = tr(
                    "在不同应用中使用语音或键盘后，这里会呈现你的常用表达场景",
                    "Use voice or keyboard across apps to see your top communication scenes here",
                    "在不同應用程式中使用語音或鍵盤後，這裡會呈現你的常用表達場景"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                snapshot.topAppScenes.take(5).forEach { scene ->
                    AppSceneBarRow(
                        item = scene,
                        barColor = emerald,
                        onClick = onOpenStylesSettings
                    )
                }
            }
        }
    }
}

@Composable
private fun IntentStackedBar(
    items: List<IntentShareItem>,
    colorOf: @Composable (IntentShareItem) -> Color
) {
    val colors = items.map { colorOf(it) }
    val total = remember(items) { items.sumOf { it.count.coerceAtLeast(0) } }
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(9.dp)
    ) {
        val r = CornerRadius(size.height / 2f, size.height / 2f)
        val nonZero = items.indices.filter { items[it].count > 0 }
        if (total <= 0 || nonZero.isEmpty()) {
            drawRoundRect(color = emptyColor, size = size, cornerRadius = r)
            return@Canvas
        }
        val gap = 2.dp.toPx()
        val usable = (size.width - gap * (nonZero.size - 1)).coerceAtLeast(1f)
        var x = 0f
        nonZero.forEachIndexed { pos, idx ->
            val segW = if (pos == nonZero.lastIndex) {
                (size.width - x).coerceAtLeast(0f)
            } else {
                (usable * (items[idx].count.toFloat() / total.toFloat())).coerceAtLeast(4.dp.toPx())
            }
            drawRoundRect(
                color = colors[idx],
                topLeft = Offset(x, 0f),
                size = Size(segW, size.height),
                cornerRadius = r
            )
            x += segW + gap
        }
    }
}

@Composable
private fun AppSceneBarRow(
    item: AppSceneShareItem,
    barColor: Color,
    onClick: () -> Unit
) {
    val appLang = LocalAppLanguage.current
    val animatedFrac by animateFloatAsState(
        targetValue = (item.percent / 100f).coerceIn(0.05f, 1f),
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 360f),
        label = "appScene_${item.appName}"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.appName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.sceneCategory.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = localizedSceneCategory(item.sceneCategory, appLang),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = tr(
                    "${item.count} 次 · ${item.percent}%",
                    "${item.count} uses · ${item.percent}%",
                    "${item.count} 次 · ${item.percent}%"
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFrac)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(barColor.copy(alpha = 0.85f))
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LexiconAndWordChainsCard(snapshot: InsightsSnapshot) {
    var obscured by rememberSaveable { mutableStateOf(true) }
    val supportsRenderBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = tr("词海与惯用词链", "Personal Lexicon & Word Chains", "詞海與慣用詞鏈"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Surface(
                onClick = { obscured = !obscured },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (obscured) TypeFreeIcons.EyeOff else TypeFreeIcons.Eye,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (obscured) {
                            tr("防窥模糊", "Privacy Blur", "防窺模糊")
                        } else {
                            tr("已显示", "Visible", "已顯示")
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LexiconCounterTile(
                title = tr("自动纠错词条", "Auto-Learned", "自動糾錯詞條"),
                value = "${snapshot.autoLearnedDictCount}",
                subtitle = tr("词典共 ${snapshot.totalDictCount}", "${snapshot.totalDictCount} in dict", "詞典共 ${snapshot.totalDictCount}"),
                modifier = Modifier.weight(1f)
            )
            LexiconCounterTile(
                title = tr("私人二元连词", "L2 Bigrams", "私人二元連詞"),
                value = "${snapshot.personalBigramCount}",
                subtitle = tr("L2 端侧联想", "Word pairs", "L2 端側聯想"),
                modifier = Modifier.weight(1f)
            )
            LexiconCounterTile(
                title = tr("私人三元词链", "L2 Trigrams", "私人三元詞鏈"),
                value = "${snapshot.personalTrigramCount}",
                subtitle = tr("上下文预测", "3-word chains", "上下文預測"),
                modifier = Modifier.weight(1f)
            )
        }

        if (snapshot.topWordChains.isEmpty()) {
            Text(
                text = tr(
                    "日常打字积累后，你的高频专属词链会在这里浮现（仅保存在本机，永不进入分享海报）。",
                    "As you type, your top recurring word chains will appear here (stored strictly offline and never included in shared posters).",
                    "日常打字累積後，你的高頻專屬詞鏈會在這裡浮現（僅儲存在本機，永不進入分享海報）。"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousRoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                    .clickable { obscured = !obscured }
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (obscured) Modifier.blur(6.dp) else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    snapshot.topWordChains.take(12).forEach { chain ->
                        val displayChain = if (obscured && !supportsRenderBlur) {
                            "•".repeat(chain.length.coerceIn(3, 6))
                        } else {
                            chain
                        }
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .border(
                                    0.6.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    CircleShape
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = displayChain,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (obscured) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                            .border(
                                0.7.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                CircleShape
                            )
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = tr("点按临时查看本机高频词链", "Tap to reveal local word chains", "點按臨時檢視本機高頻詞鏈"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LexiconCounterTile(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(ContinuousRoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            fontSize = 19.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = subtitle,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MilestonesWallCard(snapshot: InsightsSnapshot) {
    val appLang = LocalAppLanguage.current
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val emerald = if (isDark) TypeFreeEmeraldLight else TypeFreeEmerald
    val unlockedCount = remember(snapshot.milestones) {
        snapshot.milestones.count { it.unlocked }
    }
    var selectedMilestoneId by remember { mutableStateOf<String?>(null) }
    val selectedMilestone: MilestoneItem? = remember(snapshot.milestones, selectedMilestoneId) {
        selectedMilestoneId?.let { id -> snapshot.milestones.firstOrNull { it.id == id } }
    }

    InsightsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = tr("永久里程碑印记", "Lifetime Milestones", "永久里程碑印記"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = tr(
                    "已点亮 $unlockedCount/${snapshot.milestones.size}",
                    "$unlockedCount/${snapshot.milestones.size} Unlocked",
                    "已點亮 $unlockedCount/${snapshot.milestones.size}"
                ),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (unlockedCount > 0) emerald else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (snapshot.milestones.isEmpty()) {
            Text(
                text = tr(
                    "你的每一次表达都会永久累积里程碑印记。",
                    "Every word you speak or type builds toward lifetime milestones.",
                    "你的每一次表達都會永久累積里程碑印記。"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                snapshot.milestones.chunked(3).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { item ->
                            val isSelected = selectedMilestoneId == item.id
                            val (mTitle, _, _) = localizedMilestoneTexts(item, snapshot, appLang)
                            MilestoneTile(
                                item = item,
                                displayTitle = mTitle,
                                isSelected = isSelected,
                                emerald = emerald,
                                onClick = {
                                    selectedMilestoneId = if (isSelected) null else item.id
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowItems.size < 3) {
                            repeat(3 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            SmoothDisclosure(expanded = selectedMilestone != null) {
                selectedMilestone?.let { ms ->
                    val (mTitle, mDesc, mProg) = localizedMilestoneTexts(ms, snapshot, appLang)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ContinuousRoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .border(
                                0.6.dp,
                                if (ms.unlocked) emerald.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                ContinuousRoundedCornerShape(14.dp)
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${ms.glyph} $mTitle",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = mProg,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (ms.unlocked) emerald else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = mDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MilestoneTile(
    item: MilestoneItem,
    displayTitle: String,
    isSelected: Boolean,
    emerald: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tileShape = ContinuousRoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val bgColor = when {
        item.unlocked -> emerald.copy(alpha = if (isSelected) 0.20f else 0.12f)
        isSelected -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val borderColor = when {
        isSelected && item.unlocked -> emerald
        item.unlocked -> emerald.copy(alpha = 0.42f)
        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }

    Column(
        modifier = modifier
            .springPressScale(0.95f, interactionSource)
            .clip(tileShape)
            .background(bgColor)
            .border(if (isSelected) 1.2.dp else 0.7.dp, borderColor, tileShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = item.glyph,
            fontSize = 20.sp,
            color = if (item.unlocked) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            }
        )
        Text(
            text = displayTitle,
            fontSize = 11.5.sp,
            fontWeight = if (item.unlocked) FontWeight.Bold else FontWeight.Medium,
            color = if (item.unlocked) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (item.unlocked) {
            Text(
                text = tr("已点亮", "Unlocked", "已點亮"),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = emerald,
                maxLines = 1
            )
        } else {
            val progress = (item.progressPercent / 100f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(3.5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceAtLeast(0.06f))
                        .height(3.5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                )
            }
            Text(
                text = "${item.progressPercent}%",
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun InsightsPrivacyFooterCard(
    snapshot: InsightsSnapshot,
    onOpenPrivacySettings: () -> Unit
) {
    val shape = ContinuousRoundedCornerShape(20.dp)
    val dbSizeStr = formatDbSize(snapshot.statsDbSizeBytes)
    Surface(
        onClick = onOpenPrivacySettings,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = tr(
                        "🔒 所有累计统计均为纯数字本地存储 · 0 正文",
                        "🔒 100% Local Numeric Counters · Zero Raw Text",
                        "🔒 所有累計統計均為純數字本機儲存 · 0 正文"
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = tr(
                        "已记录 ${snapshot.statsTrackedDays} 天 · 约 $dbSizeStr · 无痕与密码框强制零计数",
                        "Tracked ${snapshot.statsTrackedDays} days · ~$dbSizeStr · Zero count in Incognito & passwords",
                        "已記錄 ${snapshot.statsTrackedDays} 天 · 約 $dbSizeStr · 無痕與密碼框強制零計數"
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tr("管理与重置", "Manage", "管理與重設"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun formatLatency(latencyMs: Long): String {
    return if (latencyMs >= 1000L) {
        String.format(Locale.US, "%.1fs", latencyMs / 1000.0)
    } else {
        "${latencyMs}ms"
    }
}

private fun formatDbSize(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe < 1024L -> "${safe} B"
        safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", safe / (1024.0 * 1024.0))
    }
}
