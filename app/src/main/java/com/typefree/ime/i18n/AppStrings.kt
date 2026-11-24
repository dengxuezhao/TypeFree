package com.typefree.ime.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.KeyboardHeightMode
import com.typefree.ime.data.KeycapBadgeMode
import com.typefree.ime.data.ThemePreference
import com.typefree.ime.data.ThinkingMode
import com.typefree.ime.data.ToneStyle
import com.typefree.ime.data.VoiceIntentMode
import java.util.Locale

enum class AppLang(val code: String) {
    EN("en"),
    ZH_CN("zh-CN"),
    ZH_TW("zh-TW")
}

val LocalAppLanguage = compositionLocalOf { AppStrings.resolveLang("system") }

object AppStrings {
    const val LANG_SYSTEM = "system"
    const val LANG_EN = "en"
    const val LANG_ZH_CN = "zh-CN"
    const val LANG_ZH_TW = "zh-TW"

    val LANGUAGE_OPTIONS: List<String> = listOf(
        LANG_SYSTEM,
        LANG_EN,
        LANG_ZH_CN,
        LANG_ZH_TW
    )

    fun normalizeAppLanguage(raw: String?): String {
        val cleaned = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            cleaned == "en" || cleaned.startsWith("en-") || cleaned.startsWith("en_") -> LANG_EN
            cleaned == "zh-tw" || cleaned == "zh_tw" ||
                cleaned == "zh-hk" || cleaned == "zh_hk" ||
                cleaned == "zh-mo" || cleaned == "zh_mo" ||
                cleaned.contains("hant") -> LANG_ZH_TW
            cleaned == "zh" || cleaned == "zh-cn" || cleaned == "zh_cn" ||
                cleaned == "zh-sg" || cleaned == "zh_sg" ||
                cleaned.contains("hans") -> LANG_ZH_CN
            else -> LANG_SYSTEM
        }
    }

    fun currentSystemLocale(): Locale {
        return runCatching {
            val cfg = android.content.res.Resources.getSystem()?.configuration
            val list = cfg?.locales
            if (list != null && !list.isEmpty) list[0] else null
        }.getOrNull() ?: Locale.getDefault()
    }

    fun resolveLang(
        appLanguage: String?,
        systemLocale: Locale = currentSystemLocale()
    ): AppLang {
        return when (normalizeAppLanguage(appLanguage)) {
            LANG_EN -> AppLang.EN
            LANG_ZH_CN -> AppLang.ZH_CN
            LANG_ZH_TW -> AppLang.ZH_TW
            else -> {
                val lang = systemLocale.language.lowercase(Locale.ROOT)
                if (lang.startsWith("zh")) {
                    val country = systemLocale.country.uppercase(Locale.ROOT)
                    val script = systemLocale.script.lowercase(Locale.ROOT)
                    if (country in setOf("TW", "HK", "MO") || script == "hant") {
                        AppLang.ZH_TW
                    } else {
                        AppLang.ZH_CN
                    }
                } else {
                    AppLang.EN
                }
            }
        }
    }

    fun isChinese(
        appLanguage: String?,
        systemLocale: Locale = currentSystemLocale()
    ): Boolean {
        return resolveLang(appLanguage, systemLocale) != AppLang.EN
    }

    fun pick(
        lang: AppLang,
        zhCn: String,
        en: String,
        zhTw: String = zhCn
    ): String {
        return when (lang) {
            AppLang.EN -> en
            AppLang.ZH_TW -> zhTw
            AppLang.ZH_CN -> zhCn
        }
    }

    fun pick(
        appLanguage: String?,
        zhCn: String,
        en: String,
        zhTw: String = zhCn,
        systemLocale: Locale = currentSystemLocale()
    ): String {
        return pick(resolveLang(appLanguage, systemLocale), zhCn, en, zhTw)
    }

    fun languageOptionLabel(option: String, currentLang: AppLang): String {
        return when (normalizeAppLanguage(option)) {
            LANG_EN -> "English"
            LANG_ZH_CN -> "简体中文"
            LANG_ZH_TW -> "繁體中文"
            else -> pick(currentLang, "跟随系统", "System Default", "跟隨系統")
        }
    }

    fun themeLabel(pref: ThemePreference, lang: AppLang): String {
        return when (pref) {
            ThemePreference.LIGHT -> pick(lang, "浅色单色 (TypeFree 经典)", "Light Monochrome", "淺色單色 (TypeFree 經典)")
            ThemePreference.DARK -> pick(lang, "深色曜石", "Obsidian Dark", "深色曜石")
            ThemePreference.SYSTEM -> pick(lang, "跟随系统", "System Default", "跟隨系統")
        }
    }

    fun voiceIntentLabel(mode: VoiceIntentMode, lang: AppLang): String {
        return when (mode) {
            VoiceIntentMode.DICTATE -> pick(lang, "口述净写", "Dictate", "口述淨寫")
            VoiceIntentMode.HELP_ME_WRITE -> pick(lang, "帮我写", "Help Write", "幫我寫")
            VoiceIntentMode.SPEAK_TO_EDIT -> pick(lang, "语音编辑", "Voice Edit", "語音編輯")
            VoiceIntentMode.TRANSLATE -> pick(lang, "实时快译", "Translate", "即時快譯")
        }
    }

    fun inputSchemeLabel(scheme: InputScheme, lang: AppLang): String {
        return when (scheme) {
            InputScheme.RIME_PINYIN -> pick(lang, "全拼（雾凇拼音）", "Full Pinyin (Rime Ice)", "全拼（霧凇拼音）")
            InputScheme.RIME_T9_PINYIN -> pick(lang, "九键拼音（雾凇 T9）", "9-Key Pinyin (T9)", "九鍵拼音（霧凇 T9）")
            InputScheme.RIME_DOUBLE_PINYIN_FLYPY -> pick(lang, "小鹤双拼（整句）", "Double Pinyin (Flypy)", "小鶴雙拼（整句）")
            InputScheme.FLYPY_TABLE -> pick(lang, "小鹤音形（四码）", "Flypy Codetable (4-Code)", "小鶴音形（四碼）")
            InputScheme.RIME_CUSTOM -> pick(lang, "自定义 Rime 方案", "Custom Rime Schema", "自訂 Rime 方案")
        }
    }

    fun inputSchemeShortBadge(scheme: InputScheme, lang: AppLang): String {
        return when (scheme) {
            InputScheme.RIME_PINYIN -> pick(lang, "拼", "PY", "拼")
            InputScheme.RIME_T9_PINYIN -> pick(lang, "九", "T9", "九")
            InputScheme.RIME_DOUBLE_PINYIN_FLYPY -> pick(lang, "拼", "DP", "拼")
            InputScheme.FLYPY_TABLE -> pick(lang, "鹤", "FP", "鶴")
            InputScheme.RIME_CUSTOM -> pick(lang, "拼", "RM", "拼")
        }
    }

    fun historyRetentionLabel(retention: HistoryRetention, lang: AppLang): String {
        return when (retention) {
            HistoryRetention.FOREVER -> pick(lang, "永久保留", "Keep Forever", "永久保留")
            HistoryRetention.DAYS_30 -> pick(lang, "保留 30 天", "30 Days", "保留 30 天")
            HistoryRetention.DAYS_7 -> pick(lang, "保留 7 天", "7 Days", "保留 7 天")
            HistoryRetention.HOURS_24 -> pick(lang, "保留 24 小时", "24 Hours", "保留 24 小時")
            HistoryRetention.NEVER -> pick(lang, "从不保留", "Never Save", "從不保留")
        }
    }

    fun keycapBadgeModeLabel(mode: KeycapBadgeMode, lang: AppLang): String {
        return when (mode) {
            KeycapBadgeMode.SWIPE_UP -> pick(lang, "上滑角标", "Swipe-Up Badges", "上滑角標")
            KeycapBadgeMode.LONG_PRESS -> pick(lang, "长按角标", "Long-Press Badges", "長按角標")
            KeycapBadgeMode.NONE -> pick(lang, "隐藏角标", "Hide Badges", "隱藏角標")
        }
    }

    fun keyboardHeightModeLabel(mode: KeyboardHeightMode, lang: AppLang): String {
        return when (mode) {
            KeyboardHeightMode.STRETCH -> pick(lang, "拉伸", "Stretch", "拉伸")
            KeyboardHeightMode.LIFT -> pick(lang, "垫高", "Bottom Lift", "墊高")
        }
    }

    fun thinkingModeLabel(mode: ThinkingMode, lang: AppLang): String {
        return when (mode) {
            ThinkingMode.DISABLED -> pick(lang, "关闭思考 (极速)", "Thinking Off (Fastest)", "關閉思考 (極速)")
            ThinkingMode.AUTO -> pick(lang, "模型默认", "Model Default", "模型預設")
            ThinkingMode.ENABLED -> pick(lang, "开启思考", "Thinking On", "開啟思考")
        }
    }

    fun toneStyleName(style: ToneStyle, lang: AppLang): String {
        if (!style.isBuiltIn) return style.name
        return when (style.id) {
            "smart_default" -> pick(lang, "通用灵动", "Smart Default", "通用靈動")
            "casual_chat" -> pick(lang, "社交聊天", "Casual Chat", "社交聊天")
            "work_collab" -> pick(lang, "职场干练", "Work & Slack", "職場幹練")
            "formal_doc" -> pick(lang, "正式邮件", "Formal Email", "正式郵件")
            "code_prompt" -> pick(lang, "编程与 Prompt", "Code & Prompt", "程式與 Prompt")
            "translate_en" -> pick(lang, "直出地道英文", "Native English Output", "直出地道英文")
            else -> style.name
        }
    }

    fun toneStyleSummary(style: ToneStyle, lang: AppLang): String {
        if (!style.isBuiltIn) return style.summary
        return when (style.id) {
            "smart_default" -> pick(
                lang,
                "去口癖、识别口误自我纠正、智能标点与分点排版",
                "Strips filler words, merges self-corrections, and adds clean punctuation",
                "去口頭禪、識別口誤自我糾正、智慧標點與分點排版"
            )
            "casual_chat" -> pick(
                lang,
                "适合微信/QQ/小红书，自然松弛、口语亲切不过度书面化",
                "Great for WhatsApp, iMessage, and Telegram — relaxed and natural",
                "適合 LINE/WhatsApp/微信，自然鬆弛、口語親切不過度書面化"
            )
            "work_collab" -> pick(
                lang,
                "适合飞书/钉钉/企微，重点清晰、专业得体、高效沟通",
                "Crisp, action-oriented tone for Slack, Teams, and Lark",
                "適合 Slack/Teams/飛書，重點清晰、專業得體、高效溝通"
            )
            "formal_doc" -> pick(
                lang,
                "适合邮箱/文档/Notion，书面语严谨、结构规范",
                "Polished written prose for Gmail, Outlook, and Notion",
                "適合信箱/文件/Notion，書面語嚴謹、結構規範"
            )
            "code_prompt" -> pick(
                lang,
                "保留代码/API 技术术语大小写，整理为结构化开发表达",
                "Preserves exact API/code casing and structures technical prompts",
                "保留程式碼/API 技術術語大小寫，整理為結構化開發表達"
            )
            "translate_en" -> pick(
                lang,
                "说中文或中英夹杂，直接润色输出地道母语级英文",
                "Speak in any language and output natural, idiomatic English directly",
                "說中文或中英夾雜，直接潤飾輸出道地母語級英文"
            )
            else -> style.summary
        }
    }
}

@Composable
@ReadOnlyComposable
fun tr(
    zhCn: String,
    en: String,
    zhTw: String = zhCn
): String {
    return AppStrings.pick(LocalAppLanguage.current, zhCn, en, zhTw)
}
