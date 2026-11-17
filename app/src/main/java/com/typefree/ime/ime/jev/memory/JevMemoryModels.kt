package com.typefree.ime.ime.jev.memory

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

    fun toCompactXmlBlock(): String {
        if (isEmpty) return ""
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
    }
}
