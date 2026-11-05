package com.typefree.ime.data

import java.util.UUID

enum class PipelineMode {
    DUAL_STAGE,         // STT 语音转文字 + LLM 文本润色
    MULTIMODAL_DIRECT   // 多模态大模型音频一步直出（如 Gemini / GPT-4o Audio）
}

enum class MultimodalProtocol {
    GEMINI_NATIVE,      // Google Gemini generateContent (inline_data audio/wav)
    OPENAI_AUDIO_CHAT   // OpenAI /v1/chat/completions (input_audio base64 wav)
}

enum class ThemePreference(val label: String) {
    LIGHT("浅色单色 (TypeFree 经典)"),
    DARK("深色曜石"),
    SYSTEM("跟随系统")
}

enum class VoiceIntentMode(val label: String) {
    DICTATE("口述净写"),
    HELP_ME_WRITE("帮我写"),
    SPEAK_TO_EDIT("语音编辑"),
    TRANSLATE("实时快译")
}

enum class HistoryRetention(val label: String, val maxAgeMs: Long?) {
    FOREVER("永久保留", null),
    DAYS_30("保留 30 天", 30L * 24L * 3600L * 1000L),
    DAYS_7("保留 7 天", 7L * 24L * 3600L * 1000L),
    HOURS_24("保留 24 小时", 24L * 3600L * 1000L),
    NEVER("从不保留", 0L)
}

enum class DictionarySource(val label: String) {
    MANUAL("手动添加"),
    AUTO_LEARNED("自动学习")
}

enum class ModelCapabilitySlot(val label: String) {
    STT("STT 语音识别"),
    LLM("LLM 语义润色"),
    MULTIMODAL("多模态音频直出")
}

data class DiscoveredModelItem(
    val id: String,
    val displayName: String = "",
    val ownedBy: String = "",
    val isRecommended: Boolean = false,
    val badge: String = ""
)

data class SttConfig(
    val presetId: String = "siliconflow",
    val baseUrl: String = "https://api.siliconflow.cn/v1",
    val apiKey: String = "",
    val model: String = "FunAudioLLM/SenseVoiceSmall",
    val language: String = "" // "" = 自动识别, "zh", "en", "ja"
)

data class LlmConfig(
    val enabled: Boolean = true,
    val presetId: String = "deepseek",
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val temperature: Float = 0.2f
)

data class MultimodalConfig(
    val presetId: String = "gemini",
    val protocol: MultimodalProtocol = MultimodalProtocol.GEMINI_NATIVE,
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    val apiKey: String = "",
    val model: String = "gemini-2.5-flash",
    val temperature: Float = 0.2f
)

data class ApiPreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val baseUrl: String,
    val defaultModel: String,
    val protocol: MultimodalProtocol? = null
)

object ApiPresets {
    val sttPresets = listOf(
        ApiPreset(
            id = "siliconflow",
            name = "硅基流动 SenseVoice",
            subtitle = "国内直连极速，中英混合识别率高",
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "FunAudioLLM/SenseVoiceSmall"
        ),
        ApiPreset(
            id = "groq",
            name = "Groq Whisper Turbo",
            subtitle = "海外延迟极低 (<0.3s)",
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "whisper-large-v3-turbo"
        ),
        ApiPreset(
            id = "openai",
            name = "OpenAI Whisper",
            subtitle = "官方标准语音转写接口",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini-transcribe"
        ),
        ApiPreset(
            id = "custom_stt",
            name = "自定义 OpenAI 兼容",
            subtitle = "OneAPI / NewAPI / 本地 whisper.cpp 等",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "whisper-1"
        )
    )

    val llmPresets = listOf(
        ApiPreset(
            id = "deepseek",
            name = "DeepSeek",
            subtitle = "中文去口癖与语义重组极佳",
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat"
        ),
        ApiPreset(
            id = "siliconflow_llm",
            name = "硅基流动 (可与 STT 共用 Key)",
            subtitle = "Qwen2.5 / DeepSeek-V3 高速推理",
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "Qwen/Qwen2.5-7B-Instruct"
        ),
        ApiPreset(
            id = "groq_llm",
            name = "Groq Llama 3.3",
            subtitle = "百毫秒级超快润色输出",
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "llama-3.3-70b-versatile"
        ),
        ApiPreset(
            id = "zhipu",
            name = "智谱 GLM",
            subtitle = "GLM-4-Flash 免费/高速润色",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4-flash"
        ),
        ApiPreset(
            id = "doubao",
            name = "火山方舟豆包",
            subtitle = "字节跳动高并发模型接入点",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            defaultModel = "doubao-1-5-lite-32k-250115"
        ),
        ApiPreset(
            id = "openai_llm",
            name = "OpenAI / 兼容中转",
            subtitle = "GPT-4o-mini 或任意兼容接口",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini"
        ),
        ApiPreset(
            id = "ollama",
            name = "局域网 Ollama",
            subtitle = "本地自托管免云端 API 费用",
            baseUrl = "http://192.168.1.100:11434/v1",
            defaultModel = "qwen2.5:7b"
        )
    )

    val multimodalPresets = listOf(
        ApiPreset(
            id = "gemini",
            name = "Google Gemini",
            subtitle = "原生音频理解，一步完成识别+去口癖+排版",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            defaultModel = "gemini-2.5-flash",
            protocol = MultimodalProtocol.GEMINI_NATIVE
        ),
        ApiPreset(
            id = "newapi_gemini",
            name = "NewAPI / Gemini 反代",
            subtitle = "支持自建网关 /v1beta 透传与 GCP Vertex 渠道",
            baseUrl = "https://your-gateway.example.com/v1beta",
            defaultModel = "gemini-3.5-flash-lite",
            protocol = MultimodalProtocol.GEMINI_NATIVE
        ),
        ApiPreset(
            id = "openai_audio",
            name = "OpenAI GPT-4o Audio",
            subtitle = "OpenAI input_audio 多模态直传",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini-audio-preview",
            protocol = MultimodalProtocol.OPENAI_AUDIO_CHAT
        ),
        ApiPreset(
            id = "openrouter_audio",
            name = "OpenRouter / 兼容多模态",
            subtitle = "支持 input_audio 的聚合网关或 Qwen-Omni",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "google/gemini-2.5-flash",
            protocol = MultimodalProtocol.OPENAI_AUDIO_CHAT
        )
    )
}

data class ToneStyle(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String,
    val summary: String,
    val promptInstruction: String,
    val isBuiltIn: Boolean = false
)

data class AppToneRule(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val appName: String,
    val styleId: String,
    val enabled: Boolean = true
)

data class DictionaryEntry(
    val id: String = UUID.randomUUID().toString(),
    val term: String,
    val wrongVariants: String = "", // 逗号分隔的常见同音误识别词（可选）
    val note: String = "",
    val enabled: Boolean = true,
    val source: DictionarySource = DictionarySource.MANUAL,
    val createdAt: Long = System.currentTimeMillis()
)

data class HistoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val targetAppName: String = "",
    val targetPackage: String = "",
    val styleName: String = "",
    val isSpeakToEdit: Boolean = false,
    val intentMode: VoiceIntentMode = if (isSpeakToEdit) VoiceIntentMode.SPEAK_TO_EDIT else VoiceIntentMode.DICTATE,
    val translateTargetLanguage: String = "",
    val originalSelectedText: String = "",
    val rawTranscript: String = "",
    val polishedText: String = "",
    val durationMs: Long = 0L,
    val latencyMs: Long = 0L,
    val modeLabel: String = "",
    val audioFilePath: String = ""
)

data class ClipboardEntry(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false
)

data class UsageStatsSummary(
    val totalDictations: Int,
    val totalCharacters: Int,
    val averageWpm: Int,
    val savedMinutes: Int,
    val overallPersonalizationPercent: Int,
    val categoryProgress: Map<String, Float>
)

data class AppSettings(
    val pipelineMode: PipelineMode = PipelineMode.DUAL_STAGE,
    val sttConfig: SttConfig = SttConfig(),
    val llmConfig: LlmConfig = LlmConfig(),
    val multimodalConfig: MultimodalConfig = MultimodalConfig(),
    val themePreference: ThemePreference = ThemePreference.LIGHT,
    val appAwareToneEnabled: Boolean = true,
    val selectedManualStyleId: String = "auto", // "auto" 表示开启应用感知自动匹配，否则固定某风格
    val hapticFeedback: Boolean = true,
    val interactionSounds: Boolean = true,
    val useSurroundingContext: Boolean = true,
    val autoLearnDictionary: Boolean = true,
    val flypyEnabled: Boolean = true,
    val flypyCommaPeriodQuickSelect: Boolean = true,
    val clipboardEnabled: Boolean = true,
    val clipboardQuickPasteSuggestion: Boolean = true,
    val historyRetention: HistoryRetention = HistoryRetention.FOREVER,
    val bubbleEnabled: Boolean = false,
    val bubbleDockLeft: Boolean = false,
    val bubbleYRatio: Float = 0.48f,
    val translationTargets: List<String> = DefaultPresets.defaultTranslationTargets(),
    val activeTranslateTarget: String = "English (US)",
    val styles: List<ToneStyle> = DefaultPresets.defaultStyles(),
    val appRules: List<AppToneRule> = DefaultPresets.defaultAppRules(),
    val dictionary: List<DictionaryEntry> = DefaultPresets.defaultDictionary()
)

object DefaultPresets {
    fun defaultTranslationTargets(): List<String> = listOf(
        "English (US)",
        "日本語",
        "简体中文",
        "Español",
        "Français",
        "한국어"
    )

    val allSupportedLanguages: List<String> = listOf(
        "English (US)",
        "English (UK)",
        "简体中文",
        "繁體中文",
        "日本語",
        "한국어",
        "Español",
        "Français",
        "Deutsch",
        "Português",
        "Русский",
        "Italiano",
        "العربية",
        "ไทย",
        "Tiếng Việt",
        "Bahasa Indonesia"
    )

    fun defaultStyles(): List<ToneStyle> = listOf(
        ToneStyle(
            id = "smart_default",
            name = "通用灵动",
            emoji = "✨",
            summary = "去口癖、识别口误自我纠正、智能标点与分点排版",
            promptInstruction = "保持说话人原本的语气与核心意图。清理所有无意义口头禅，自动修正中途改口的内容，补全自然准确的标点符号；若口述了多个并列事项（如第一、第二），自动整理为清晰易读的序号列表。",
            isBuiltIn = true
        ),
        ToneStyle(
            id = "casual_chat",
            name = "社交聊天",
            emoji = "💬",
            summary = "适合微信/QQ/小红书，自然松弛、口语亲切不过度书面化",
            promptInstruction = "当前处于即时社交聊天场景（如微信/QQ）。请保持自然、亲切、轻松的日常交流感，不要改写成生硬的公文体；去除啰嗦重复与口误，长句可适当按聊天习惯自然断句，句末不必每句都生硬加句号（保留问号、感叹号或自然停顿）。",
            isBuiltIn = true
        ),
        ToneStyle(
            id = "work_collab",
            name = "职场干练",
            emoji = "💼",
            summary = "适合飞书/钉钉/企微，重点清晰、专业得体、高效沟通",
            promptInstruction = "当前处于职场办公协作场景（如飞书/钉钉/企业微信）。请将口语整理得干练、清晰、专业且礼貌得体，突出结论与行动项；若包含多项任务或问题，自动使用简洁的数字序号分点呈现。",
            isBuiltIn = true
        ),
        ToneStyle(
            id = "formal_doc",
            name = "正式邮件",
            emoji = "📝",
            summary = "适合邮箱/文档/Notion，书面语严谨、结构规范",
            promptInstruction = "当前处于正式邮件或文档写作场景。请将口语转换为规范、得体、逻辑严密的书面语，修正口语化病句，使用完整规范的标点符号与段落结构。",
            isBuiltIn = true
        ),
        ToneStyle(
            id = "code_prompt",
            name = "编程与 Prompt",
            emoji = "💻",
            summary = "保留代码/API 技术术语大小写，整理为结构化开发表达",
            promptInstruction = "当前处于编程开发或向 AI Agent 下达指令的场景。请准确还原中英文技术术语的标准拼写与大小写（例如 API、JSON、TypeScript、React、Git、Kotlin、Prompt 等），在中英文之间保留规范空格，并将口述需求整理为逻辑清晰、无歧义的技术指令。",
            isBuiltIn = true
        ),
        ToneStyle(
            id = "translate_en",
            name = "直出地道英文",
            emoji = "🌐",
            summary = "说中文或中英夹杂，直接润色输出地道母语级英文",
            promptInstruction = "无论说话人使用中文、英文还是中英混杂表达，请识别其最终真实意图（自动剔除中途改口和口癖），直接输出自然、地道、符合母语习惯的英文文本，不要附带中文原文或解释。",
            isBuiltIn = true
        )
    )

    fun defaultAppRules(): List<AppToneRule> = listOf(
        // 社交聊天
        AppToneRule(packageName = "com.tencent.mm", appName = "微信", styleId = "casual_chat"),
        AppToneRule(packageName = "com.tencent.mobileqq", appName = "QQ", styleId = "casual_chat"),
        AppToneRule(packageName = "com.xingin.xhs", appName = "小红书", styleId = "casual_chat"),
        AppToneRule(packageName = "org.telegram.messenger", appName = "Telegram", styleId = "casual_chat"),
        AppToneRule(packageName = "com.whatsapp", appName = "WhatsApp", styleId = "casual_chat"),
        // 职场干练
        AppToneRule(packageName = "com.ss.android.lark", appName = "飞书", styleId = "work_collab"),
        AppToneRule(packageName = "com.larksuite.suite", appName = "Lark", styleId = "work_collab"),
        AppToneRule(packageName = "com.alibaba.android.rimet", appName = "钉钉", styleId = "work_collab"),
        AppToneRule(packageName = "com.tencent.wework", appName = "企业微信", styleId = "work_collab"),
        AppToneRule(packageName = "com.Slack", appName = "Slack", styleId = "work_collab"),
        // 正式邮件与文档
        AppToneRule(packageName = "com.google.android.gm", appName = "Gmail", styleId = "formal_doc"),
        AppToneRule(packageName = "com.microsoft.office.outlook", appName = "Outlook", styleId = "formal_doc"),
        AppToneRule(packageName = "com.tencent.androidqqmail", appName = "QQ 邮箱", styleId = "formal_doc"),
        AppToneRule(packageName = "com.netease.mobimail", appName = "网易邮箱", styleId = "formal_doc"),
        AppToneRule(packageName = "notion.id", appName = "Notion", styleId = "formal_doc"),
        AppToneRule(packageName = "cn.wps.moffice_eng", appName = "WPS Office", styleId = "formal_doc"),
        // 编程与 AI
        AppToneRule(packageName = "com.termux", appName = "Termux 终端", styleId = "code_prompt"),
        AppToneRule(packageName = "com.github.android", appName = "GitHub", styleId = "code_prompt"),
        AppToneRule(packageName = "com.openai.chatgpt", appName = "ChatGPT", styleId = "code_prompt"),
        AppToneRule(packageName = "com.anthropic.claude", appName = "Claude", styleId = "code_prompt")
    )

    fun defaultDictionary(): List<DictionaryEntry> = listOf(
        DictionaryEntry(
            term = "TypeFree",
            wrongVariants = "太普利斯, type less",
            note = "AI 语音输入产品名",
            source = DictionarySource.MANUAL
        ),
        DictionaryEntry(
            term = "DeepSeek",
            wrongVariants = "迪普西克, deep seek",
            note = "大模型名称",
            source = DictionarySource.MANUAL
        ),
        DictionaryEntry(
            term = "Claude Code",
            wrongVariants = "克劳德扣, claude code",
            note = "AI 编程工具",
            source = DictionarySource.MANUAL
        )
    )
}
