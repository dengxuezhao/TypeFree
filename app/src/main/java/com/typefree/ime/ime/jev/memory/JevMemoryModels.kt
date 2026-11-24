package com.typefree.ime.ime.jev.memory

import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings

/**
 * Abstract clock SPI so all pure-Kotlin relationship memory & preference modules remain
 * deterministic in JVM unit tests and ready for future KMP `:core` extraction.
 */
fun interface JevClock {
    fun nowMs(): Long
}

object JevSystemClock : JevClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

enum class TopologyKind {
    PRIV,
    GRP
}

/**
 * Private 1v1 relationship tags mapped to abstract Brown & Levinson pragmatic boundaries.
 */
enum class PrivateRelationTag(
    val code: String,
    val shortLabel: String,
    val badgeEmoji: String,
    val guideline: String
) {
    UNSET(
        code = "unset",
        shortLabel = "关系",
        badgeEmoji = "⚡",
        guideline = ""
    ),
    SUPERIOR_ELDER(
        code = "superior_elder",
        shortLabel = "上级/长辈",
        badgeEmoji = "👔",
        guideline = "上级/长辈（高权力距离：结论先行，敬语得体，给可拍板选项，禁说教轻佻）"
    ),
    CLIENT_EXTERNAL(
        code = "client_external",
        shortLabel = "客户/外部",
        badgeEmoji = "💼",
        guideline = "客户/外部合作（外部专业边界：严守契约口径，不擅自承诺排期与兜底，礼貌但立场清晰）"
    ),
    PEER_COLLEAGUE(
        code = "peer_colleague",
        shortLabel = "平级同事",
        badgeEmoji = "🤝",
        guideline = "平级同事（平等协作：省去冗余寒暄，聚焦分工对齐与进展，温和留面不卑微）"
    ),
    CLOSE_FRIEND(
        code = "close_friend",
        shortLabel = "熟人朋友",
        badgeEmoji = "🍻",
        guideline = "熟人朋友（高亲密度：口语松弛直给，禁用职场黑话与书面敬语，短句自然）"
    ),
    INTIMATE_FAMILY(
        code = "intimate_family",
        shortLabel = "伴侣/家人",
        badgeEmoji = "❤️",
        guideline = "亲密伴侣/家人（亲密关系：情绪接纳优先于辩输赢，禁用公文腔与冷漠敷衍）"
    ),
    STRANGER_GAME(
        code = "stranger_game",
        shortLabel = "陌生博弈",
        badgeEmoji = "🛡️",
        guideline = "陌生博弈（高防御边界：事实与规则锚定，零情绪暴露，不主动让渡底线）"
    );

    fun shortLabelFor(lang: AppLang): String = when (this) {
        UNSET -> AppStrings.pick(lang, "关系", "Role", "關係")
        SUPERIOR_ELDER -> AppStrings.pick(lang, "上级/长辈", "Manager / Elder", "上級/長輩")
        CLIENT_EXTERNAL -> AppStrings.pick(lang, "客户/外部", "Client / External", "客戶/外部")
        PEER_COLLEAGUE -> AppStrings.pick(lang, "平级同事", "Peer / Teammate", "平級同事")
        CLOSE_FRIEND -> AppStrings.pick(lang, "熟人朋友", "Close Friend", "熟人朋友")
        INTIMATE_FAMILY -> AppStrings.pick(lang, "伴侣/家人", "Partner / Family", "伴侶/家人")
        STRANGER_GAME -> AppStrings.pick(lang, "陌生博弈", "Stranger / Bargain", "陌生博弈")
    }

    fun compactBadgeFor(lang: AppLang): String = when (this) {
        UNSET -> AppStrings.pick(lang, "关系", "Role", "關係")
        SUPERIOR_ELDER -> AppStrings.pick(lang, "上级", "Mgr", "上級")
        CLIENT_EXTERNAL -> AppStrings.pick(lang, "客户", "Client", "客戶")
        PEER_COLLEAGUE -> AppStrings.pick(lang, "同事", "Peer", "同事")
        CLOSE_FRIEND -> AppStrings.pick(lang, "朋友", "Friend", "朋友")
        INTIMATE_FAMILY -> AppStrings.pick(lang, "家人", "Family", "家人")
        STRANGER_GAME -> AppStrings.pick(lang, "陌生", "Guard", "陌生")
    }

    companion object {
        fun fromCode(code: String?): PrivateRelationTag {
            if (code.isNullOrBlank()) return UNSET
            return entries.firstOrNull {
                it.code.equals(code.trim(), ignoreCase = true) ||
                    it.name.equals(code.trim(), ignoreCase = true)
            } ?: UNSET
        }
    }
}

/**
 * Group chat scene tags mapped to Layer 2 / Layer 3 / Layer 4 conversational priors.
 */
enum class GroupSceneTag(
    val code: String,
    val shortLabel: String,
    val badgeEmoji: String,
    val guideline: String
) {
    UNSET(
        code = "unset",
        shortLabel = "群场景",
        badgeEmoji = "⚡",
        guideline = ""
    ),
    WORK_FORMAL_GROUP(
        code = "work_formal_group",
        shortLabel = "工作正式群",
        badgeEmoji = "🏢",
        guideline = "工作正式群（公开留痕意识，严谨克制，结论与时间节点明确，旁观态不抖机灵刷屏）"
    ),
    EXTERNAL_DOCKING_GROUP(
        code = "external_docking_group",
        shortLabel = "外部对接群",
        badgeEmoji = "🌐",
        guideline = "外部对接群（代表我方统一口径，维护专业形象，涉及变更/排期预留缓冲确认空间）"
    ),
    FRIENDS_CASUAL_GROUP(
        code = "friends_casual_group",
        shortLabel = "熟人闲聊群",
        badgeEmoji = "🎉",
        guideline = "熟人闲聊群（接梗松弛，避免长篇大论与扫兴说教，允许生活化短句与幽默转场）"
    ),
    TECH_INTEREST_GROUP(
        code = "tech_interest_group",
        shortLabel = "技术/兴趣群",
        badgeEmoji = "💻",
        guideline = "技术/兴趣社群（Layer 3 优先识别认知/技术探讨；高信息密度、内行平视，不灌水客套）"
    );

    fun shortLabelFor(lang: AppLang): String = when (this) {
        UNSET -> AppStrings.pick(lang, "群场景", "Scene", "群場景")
        WORK_FORMAL_GROUP -> AppStrings.pick(lang, "工作正式群", "Work Channel", "工作正式群")
        EXTERNAL_DOCKING_GROUP -> AppStrings.pick(lang, "外部对接群", "Client / Partner Group", "外部對接群")
        FRIENDS_CASUAL_GROUP -> AppStrings.pick(lang, "熟人闲聊群", "Friends Group", "熟人閒聊群")
        TECH_INTEREST_GROUP -> AppStrings.pick(lang, "技术/兴趣群", "Tech / Hobby Group", "技術/興趣群")
    }

    fun compactBadgeFor(lang: AppLang): String = when (this) {
        UNSET -> AppStrings.pick(lang, "场景", "Scene", "場景")
        WORK_FORMAL_GROUP -> AppStrings.pick(lang, "工作", "Work", "工作")
        EXTERNAL_DOCKING_GROUP -> AppStrings.pick(lang, "外部", "Ext", "外部")
        FRIENDS_CASUAL_GROUP -> AppStrings.pick(lang, "熟人", "Casual", "熟人")
        TECH_INTEREST_GROUP -> AppStrings.pick(lang, "技术", "Tech", "技術")
    }

    companion object {
        fun fromCode(code: String?): GroupSceneTag {
            if (code.isNullOrBlank()) return UNSET
            return entries.firstOrNull {
                it.code.equals(code.trim(), ignoreCase = true) ||
                    it.name.equals(code.trim(), ignoreCase = true)
            } ?: UNSET
        }
    }
}

/**
 * Orthogonal 3-axis groups from Jev Layer 4.
 */
enum class JevAxisGroup(
    val routeCode: String,
    val axisLabels: List<String>
) {
    PEER_TECH(
        routeCode = "E",
        axisLabels = listOf("同频升维", "温和勘误", "极客外推")
    ),
    TKI_GAME(
        routeCode = "P",
        axisLabels = listOf("协同破局", "温和设限", "升维重构")
    ),
    GROUP_BYSTANDER(
        routeCode = "G",
        axisLabels = listOf("场外递梗", "顺题聊事", "幽默转场")
    ),
    BOTTOM_LINE_LOCKED(
        routeCode = "B",
        axisLabels = listOf("周全给面", "松弛直给", "锚定闭环")
    );

    companion object {
        fun fromRouteCode(code: String?): JevAxisGroup? {
            if (code.isNullOrBlank()) return null
            val norm = code.trim().uppercase()
            return entries.firstOrNull { it.routeCode == norm || it.name == norm }
        }
    }
}

/**
 * Simplex EMA distribution over the 3 orthogonal axes in a [JevAxisGroup].
 */
data class AxisPreferenceStats(
    val probs: List<Double> = DEFAULT_UNIFORM_PROBS,
    val sampleCount: Int = 0,
    val updatedAtMs: Long = 0L
) {
    fun sanitized(): AxisPreferenceStats {
        if (probs.size != 3 || probs.any { !it.isFinite() || it < 0.0 }) {
            return AxisPreferenceStats(
                probs = DEFAULT_UNIFORM_PROBS,
                sampleCount = sampleCount.coerceAtLeast(0),
                updatedAtMs = updatedAtMs
            )
        }
        val sum = probs.sum()
        if (!sum.isFinite() || sum <= 1e-12) {
            return AxisPreferenceStats(
                probs = DEFAULT_UNIFORM_PROBS,
                sampleCount = sampleCount.coerceAtLeast(0),
                updatedAtMs = updatedAtMs
            )
        }
        return copy(
            probs = probs.map { (it / sum).coerceIn(0.0, 1.0) },
            sampleCount = sampleCount.coerceAtLeast(0)
        )
    }

    companion object {
        val DEFAULT_UNIFORM_PROBS: List<Double> = listOf(1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0)
    }
}

/**
 * Edit-derived stylistic features learned from pre-send manual tweaks.
 */
enum class StyleFeatureKey(
    val displayTitle: String,
    val isHonorificFeature: Boolean = false,
    val allowGlobalScope: Boolean = true,
    val defaultRuleText: String
) {
    DROP_TRAILING_PERIOD(
        displayTitle = "句末不加句号",
        allowGlobalScope = true,
        defaultRuleText = "禁用句末句号（句尾直接收束，不加“。”）"
    ),
    DAMPEN_EXCLAMATION_TILDE(
        displayTitle = "禁用感叹号与波浪号",
        allowGlobalScope = true,
        defaultRuleText = "禁用感叹号“！”与波浪号“～”，保持平实克制语气"
    ),
    STRIP_EMOJI(
        displayTitle = "禁用表情与 Emoji",
        allowGlobalScope = true,
        defaultRuleText = "严禁输出任何 Emoji 或表情符号"
    ),
    HONORIFIC_NIN_TO_NI(
        displayTitle = "称呼用“你”禁用“您”",
        isHonorificFeature = true,
        allowGlobalScope = false,
        defaultRuleText = "第二人称称呼强制使用“你”，严禁使用“您”"
    ),
    HONORIFIC_NI_TO_NIN(
        displayTitle = "称呼强制使用敬称“您”",
        isHonorificFeature = true,
        allowGlobalScope = false,
        defaultRuleText = "第二人称称呼强制使用敬称“您”"
    ),
    TRIM_MODAL_PARTICLE(
        displayTitle = "禁用句末软化语气词",
        allowGlobalScope = true,
        defaultRuleText = "禁用句末软化语气词（哈/呢/呀/啦/喔）"
    ),
    TRIM_POLITE_PREFIX(
        displayTitle = "省去前置客套寒暄",
        allowGlobalScope = true,
        defaultRuleText = "省去前置客套寒暄（如“不好意思/打扰了”），开门见山"
    ),
    COMPRESS_LENGTH(
        displayTitle = "偏好极简短句",
        allowGlobalScope = true,
        defaultRuleText = "极简短句：每条候选严格控制在 25 字以内"
    )
}

data class FeatureCounter(
    val emaValue: Double = 0.0,
    val secondaryEma: Double = 0.0,
    val opportunityCount: Int = 0,
    val isActive: Boolean = false,
    val userOverride: Boolean? = null,
    val updatedAtMs: Long = 0L
) {
    val effectiveActive: Boolean
        get() = userOverride ?: isActive
}

sealed interface AnchorResolution {
    data class Resolved(
        val anchorHash: String,
        val secondaryGroupFallbackHash: String? = null,
        val fallbackUnscopedHash: String? = null,
        val canonicalApp: String,
        val topologyKind: TopologyKind,
        val headerMemberCount: Int? = null,
        val hasExternalBadge: Boolean = false,
        val maskedTitleHint: String? = null,
        val isAmbiguousGeneric: Boolean = false,
        val fromPinOrCache: Boolean = false
    ) : AnchorResolution

    data class EphemeralUnanchored(
        val canonicalApp: String,
        val hasExternalBadge: Boolean = false
    ) : AnchorResolution
}

data class SessionMemoryRecord(
    val sessionUid: String,
    val canonicalApp: String,
    val topologyKind: TopologyKind,
    val confirmedIsGroup: Boolean = false,
    val privateTag: PrivateRelationTag = PrivateRelationTag.UNSET,
    val groupTag: GroupSceneTag = GroupSceneTag.UNSET,
    val redactedCustomNote: String? = null,
    val maskedTitleHint: String? = null,
    val axisStats: Map<JevAxisGroup, AxisPreferenceStats> = emptyMap(),
    val featureCounters: Map<StyleFeatureKey, FeatureCounter> = emptyMap(),
    val interactionCount: Int = 0,
    val isPinned: Boolean = false,
    val createdAtMs: Long = 0L,
    val lastActiveAtMs: Long = 0L
)

data class AnchorAliasRecord(
    val anchorHash: String,
    val sessionUid: String,
    val boundAtMs: Long
)

data class BucketStyleProfile(
    val bucketKey: String,
    val axisStats: Map<JevAxisGroup, AxisPreferenceStats> = emptyMap(),
    val featureCounters: Map<StyleFeatureKey, FeatureCounter> = emptyMap(),
    val updatedAtMs: Long = 0L
)

/**
 * Compact, zero-PII prompt payload injected into User Prompt `<relationship_context>`.
 * Intentionally avoids verbose nested XML to keep incremental prompt tokens <= ~65.
 */
data class JevMemoryPromptPayload(
    val relationLabel: String? = null,
    val guideline: String? = null,
    val externalBoundaryHint: String? = null,
    val redactedCustomNote: String? = null,
    val deterministicStyleRules: List<String> = emptyList(),
    val axisTendencyHint: String? = null,
    val includesLengthCapRule: Boolean = false
) {
    val isEmpty: Boolean
        get() = relationLabel.isNullOrBlank() &&
            guideline.isNullOrBlank() &&
            externalBoundaryHint.isNullOrBlank() &&
            redactedCustomNote.isNullOrBlank() &&
            deterministicStyleRules.isEmpty() &&
            axisTendencyHint.isNullOrBlank()

    fun toCompactXmlBlock(isChineseLocale: Boolean = true): String {
        if (isEmpty) return ""
        if (!isChineseLocale) {
            val lines = mutableListOf<String>()
            val relEn = translateRelationLabelEn(relationLabel)
            val guideEn = translateGuidelineEn(guideline)
            if (!relEn.isNullOrBlank()) {
                val g = guideEn?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                lines.add("Relationship: ${escapeXml(relEn)}${escapeXml(g)}")
            } else if (!guideEn.isNullOrBlank()) {
                lines.add("Boundary: ${escapeXml(guideEn)}")
            }
            if (!externalBoundaryHint.isNullOrBlank()) {
                val extEn = if (externalBoundaryHint.contains("外部")) {
                    "External cross-org badge detected — maintain clear organizational boundaries"
                } else {
                    externalBoundaryHint
                }
                lines.add("Badge: ${escapeXml(extEn)}")
            }
            if (!redactedCustomNote.isNullOrBlank()) {
                lines.add("Note: ${escapeXml(redactedCustomNote)}")
            }
            if (deterministicStyleRules.isNotEmpty()) {
                val numbered = deterministicStyleRules.mapIndexed { idx, r ->
                    "${idx + 1}. ${escapeXml(translateRuleEn(r))}"
                }.joinToString("; ")
                val bottomLineOverride = if (includesLengthCapRule) {
                    " (prioritize conveying <user_bottom_line> completely when present)"
                } else {
                    ""
                }
                lines.add("Style rules: $numbered$bottomLineOverride")
            }
            if (!axisTendencyHint.isNullOrBlank()) {
                lines.add("Style tendency: ${escapeXml(translateTendencyEn(axisTendencyHint))}")
            }
            return "<relationship_context>\n${lines.joinToString("\n")}\n</relationship_context>\n"
        }
        val lines = mutableListOf<String>()
        if (!relationLabel.isNullOrBlank()) {
            val g = guideline?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            lines.add("关系：${escapeXml(relationLabel)}${escapeXml(g)}")
        } else if (!guideline.isNullOrBlank()) {
            lines.add("关系边界：${escapeXml(guideline)}")
        }
        if (!externalBoundaryHint.isNullOrBlank()) {
            lines.add("标识：${escapeXml(externalBoundaryHint)}")
        }
        if (!redactedCustomNote.isNullOrBlank()) {
            lines.add("备忘：${escapeXml(redactedCustomNote)}")
        }
        if (deterministicStyleRules.isNotEmpty()) {
            val numbered = deterministicStyleRules.mapIndexed { idx, r ->
                "${idx + 1}.${escapeXml(r)}"
            }.joinToString("；")
            val bottomLineOverride = if (includesLengthCapRule) {
                "（若存在<user_bottom_line>则优先完整传达底牌）"
            } else {
                ""
            }
            lines.add("语体规则：$numbered$bottomLineOverride")
        }
        if (!axisTendencyHint.isNullOrBlank()) {
            lines.add("语感倾向：${escapeXml(axisTendencyHint)}")
        }
        return "<relationship_context>\n${lines.joinToString("\n")}\n</relationship_context>\n"
    }

    companion object {
        fun escapeXml(raw: String): String {
            return raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        }

        private fun translateRelationLabelEn(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            PrivateRelationTag.entries.firstOrNull { it.shortLabel == raw }?.let { return it.shortLabelFor(AppLang.EN) }
            GroupSceneTag.entries.firstOrNull { it.shortLabel == raw }?.let { return it.shortLabelFor(AppLang.EN) }
            return raw
        }

        private fun translateGuidelineEn(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            PrivateRelationTag.entries.firstOrNull { it.guideline == raw }?.let { tag ->
                return when (tag) {
                    PrivateRelationTag.SUPERIOR_ELDER -> "High power distance: lead with conclusion, respectful & actionable options, zero lecturing"
                    PrivateRelationTag.CLIENT_EXTERNAL -> "External client boundary: uphold scope & contract terms, do not over-promise timelines"
                    PrivateRelationTag.PEER_COLLEAGUE -> "Equal peer collaboration: skip fluff, align clearly on ownership & next steps"
                    PrivateRelationTag.CLOSE_FRIEND -> "Close friend: relaxed casual phrasing, short natural sentences, zero corporate jargon"
                    PrivateRelationTag.INTIMATE_FAMILY -> "Partner/family: emotional attunement over winning arguments, warm and genuine"
                    PrivateRelationTag.STRANGER_GAME -> "High-defense negotiation: anchor on facts & rules, protect bottom line"
                    PrivateRelationTag.UNSET -> ""
                }
            }
            GroupSceneTag.entries.firstOrNull { it.guideline == raw }?.let { scene ->
                return when (scene) {
                    GroupSceneTag.WORK_FORMAL_GROUP -> "Formal work channel: crisp, accountable, clear owners & dates"
                    GroupSceneTag.EXTERNAL_DOCKING_GROUP -> "External partner group: unified professional stance, leave buffer on timeline changes"
                    GroupSceneTag.FRIENDS_CASUAL_GROUP -> "Casual friends group: light, witty, conversational, avoid lecturing"
                    GroupSceneTag.TECH_INTEREST_GROUP -> "Tech/interest community: high signal density, peer-expert footing, zero filler"
                    GroupSceneTag.UNSET -> ""
                }
            }
            return raw
        }

        private fun translateRuleEn(raw: String): String {
            StyleFeatureKey.entries.firstOrNull { it.defaultRuleText == raw }?.let { key ->
                return when (key) {
                    StyleFeatureKey.DROP_TRAILING_PERIOD -> "Omit trailing period at end of casual messages"
                    StyleFeatureKey.DAMPEN_EXCLAMATION_TILDE -> "Avoid exclamation marks (!) and tildes (~); keep calm tone"
                    StyleFeatureKey.STRIP_EMOJI -> "Do not output any emojis"
                    StyleFeatureKey.HONORIFIC_NIN_TO_NI -> "Use casual '你' instead of formal '您'"
                    StyleFeatureKey.HONORIFIC_NI_TO_NIN -> "Use respectful honorific '您'"
                    StyleFeatureKey.TRIM_MODAL_PARTICLE -> "Omit trailing softening particles"
                    StyleFeatureKey.TRIM_POLITE_PREFIX -> "Skip apologetic preambles and get straight to the point"
                    StyleFeatureKey.COMPRESS_LENGTH -> "Keep replies ultra-concise (under 25 chars / 15 words)"
                }
            }
            if (raw.startsWith("极简短句：")) {
                val cap = raw.filter { it.isDigit() }.ifEmpty { "25" }
                return "Ultra-concise phrasing: keep each reply within $cap characters"
            }
            return raw
        }

        private fun translateTendencyEn(raw: String): String {
            val axisMap = mapOf(
                "同频升维" to "Mechanism Elevation",
                "温和勘误" to "Crisp Counterexample",
                "极客外推" to "Dry Geek Wit",
                "协同破局" to "Collaborative Bridge",
                "温和设限" to "Firm Boundary",
                "升维重构" to "Reframe & Defuse",
                "场外递梗" to "Playful Riff",
                "顺题聊事" to "On-Topic Take",
                "幽默转场" to "Smooth Topic Pivot",
                "周全给面" to "Warm Cushion",
                "松弛直给" to "Crisp Direct",
                "锚定闭环" to "Action Lock-In"
            )
            for ((zh, en) in axisMap) {
                if (raw.contains(zh)) {
                    return "User often prefers the \"$en\" angle in similar threads while keeping 3 distinct options"
                }
            }
            return raw
        }
    }
}
