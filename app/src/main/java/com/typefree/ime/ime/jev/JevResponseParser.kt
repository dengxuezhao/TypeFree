package com.typefree.ime.ime.jev

import com.google.gson.JsonParser
import com.typefree.ime.privacy.RedactionSession

/**
 * 3-Tier Anti-Fragile Parser (`JevResponseParser`) for `✦ Jev 军师`:
 * 1. Tier 1 (Primary): `<jev><lv>1-9</lv><tag>...</tag><subtext>...</subtext><opt label="...">...</opt></jev>`
 *    XML tags — 100% immune to unescaped quotes inside Chinese dialogue.
 * 2. Tier 2 (Secondary): First `{...}` JSON object fallback (compatible with `lv`/`level`,
 *    `tag`/`category`, `subtext`/`analysis`, `options`/`strategies`).
 * 3. Tier 3 (Tertiary): Numbered / line-split plain text fallback with default `Lv.4 局势分析`,
 *    guaranteeing 1–3 selectable strategy cards on any custom API or small local `Ollama` model.
 */
object JevResponseParser {

    private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val CODE_FENCE_REGEX = Regex("```(?:xml|json|text)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    private val LV_TAG_REGEX = Regex("<lv>\\s*(?:lv\\.?\\s*)?([1-9])\\s*</lv>", RegexOption.IGNORE_CASE)
    private val TAG_ELEM_REGEX = Regex("<tag>([\\s\\S]*?)</tag>", RegexOption.IGNORE_CASE)
    private val SUBTEXT_ELEM_REGEX = Regex("<subtext>([\\s\\S]*?)</subtext>", RegexOption.IGNORE_CASE)
    private val OPT_ELEM_REGEX = Regex(
        """<opt(?:\s+label\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?\s*>([\s\S]*?)</opt>""",
        RegexOption.IGNORE_CASE
    )

    private val NUMBERED_LINE_REGEX = Regex(
        """^\s*(?:[1-3][.、)）]|①|②|③|[-•*])\s*(?:[\[【]([^\]】]{1,10})[\]】]\s*[:：]?\s*)?(.+)$"""
    )
    private val BRACKET_PREFIX_REGEX = Regex("""^\s*[\[【]([^\]】]{1,10})[\]】]\s*[:：]?\s*(.+)$""")

    private val DEFAULT_FALLBACK_LABELS = listOf("稳妥顺接", "温和设限", "高维反客")

    fun parse(
        rawModelOutput: String,
        redactionSession: RedactionSession? = null,
        usedVision: Boolean = false,
        redactionCount: Int = redactionSession?.redactionCount ?: 0
    ): JevCopilotResult {
        val cleaned = sanitizeRawOutput(rawModelOutput)

        val rawResult = parseXmlTier1(cleaned)
            ?: parseJsonTier2(cleaned)
            ?: parsePlainTextTier3(cleaned)

        val finalOptions = rawResult.options.take(3).mapIndexed { index, opt ->
            val cleanLabel = opt.label.trim().ifEmpty {
                DEFAULT_FALLBACK_LABELS.getOrElse(index) { "策略 ${index + 1}" }
            }.take(8)
            val cleanReply = cleanOptionText(opt.replyText)
            val restoredReply = if (redactionSession != null) {
                redactionSession.restoreSafePlaceholders(cleanReply)
            } else {
                cleanReply
            }
            JevStrategyOption(
                label = cleanLabel,
                replyText = restoredReply
            )
        }

        return rawResult.copy(
            level = rawResult.level.coerceIn(1, 9),
            tag = rawResult.tag.trim().ifEmpty { "局势分析" }.take(10),
            subtext = rawResult.subtext.trim().ifEmpty { "已结合当前上下文提炼高筹码回复方案" }.take(46),
            options = finalOptions,
            usedVision = usedVision,
            redactionCount = redactionCount
        )
    }

    private fun sanitizeRawOutput(raw: String): String {
        var text = raw.replace(THINK_BLOCK_REGEX, "").trim()
        val fenceMatch = CODE_FENCE_REGEX.find(text)
        if (fenceMatch != null && (text.startsWith("```") || text.contains("<jev") || text.contains("{"))) {
            val inner = fenceMatch.groupValues[1].trim()
            if (inner.isNotEmpty()) {
                text = inner
            }
        }
        return text.trim()
    }

    internal fun parseXmlTier1(text: String): JevCopilotResult? {
        val optMatches = OPT_ELEM_REGEX.findAll(text).toList()
        if (optMatches.isEmpty()) return null

        val level = LV_TAG_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 9) ?: 4
        val tag = TAG_ELEM_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()
            ?.replace("\n", " ")
            .orEmpty()
            .ifEmpty { "局势分析" }
        val subtext = SUBTEXT_ELEM_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()
            ?.replace("\n", " ")
            .orEmpty()
            .ifEmpty { "已拆解对方潜台词并生成差异化应对策略" }

        val options = optMatches.mapIndexedNotNull { idx, m ->
            val label = (m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value)
                ?.trim()
                ?.ifEmpty { null }
                ?: DEFAULT_FALLBACK_LABELS.getOrElse(idx) { "策略 ${idx + 1}" }
            val body = cleanOptionText(m.groups[4]?.value.orEmpty())
            if (body.isBlank()) null else JevStrategyOption(label = label, replyText = body)
        }

        if (options.isEmpty()) return null
        return JevCopilotResult(
            level = level,
            tag = tag,
            subtext = subtext,
            options = options.take(3)
        )
    }

    internal fun parseJsonTier2(text: String): JevCopilotResult? {
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) return null

        return try {
            val jsonCandidate = text.substring(firstBrace, lastBrace + 1)
            val obj = JsonParser.parseString(jsonCandidate).asJsonObject

            val rawLv = obj.get("lv") ?: obj.get("level")
            val level = when {
                rawLv == null || rawLv.isJsonNull -> 4
                rawLv.isJsonPrimitive && rawLv.asJsonPrimitive.isNumber -> rawLv.asInt
                rawLv.isJsonPrimitive -> {
                    Regex("([1-9])").find(rawLv.asString)?.groupValues?.get(1)?.toIntOrNull() ?: 4
                }
                else -> 4
            }.coerceIn(1, 9)

            val tag = (obj.get("tag") ?: obj.get("category") ?: obj.get("title"))
                ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                .ifEmpty { "局势分析" }

            val subtext = (obj.get("subtext") ?: obj.get("analysis") ?: obj.get("intent"))
                ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                .ifEmpty { "已拆解对方潜台词并生成差异化应对策略" }

            val arr = (obj.getAsJsonArray("options")
                ?: obj.getAsJsonArray("strategies")
                ?: obj.getAsJsonArray("replies"))
                ?: return null

            val options = mutableListOf<JevStrategyOption>()
            for (i in 0 until arr.size()) {
                val elem = arr[i]
                val defaultLabel = DEFAULT_FALLBACK_LABELS.getOrElse(i) { "策略 ${i + 1}" }
                if (elem.isJsonObject) {
                    val itemObj = elem.asJsonObject
                    val label = (itemObj.get("label") ?: itemObj.get("tag") ?: itemObj.get("title"))
                        ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                        .ifEmpty { defaultLabel }
                    val reply = (itemObj.get("reply")
                        ?: itemObj.get("text")
                        ?: itemObj.get("replyText")
                        ?: itemObj.get("content"))
                        ?.takeIf { it.isJsonPrimitive }?.asString?.let(::cleanOptionText).orEmpty()
                    if (reply.isNotBlank()) {
                        options.add(JevStrategyOption(label = label, replyText = reply))
                    }
                } else if (elem.isJsonPrimitive) {
                    val rawLine = cleanOptionText(elem.asString)
                    if (rawLine.isNotBlank()) {
                        val bracket = BRACKET_PREFIX_REGEX.find(rawLine)
                        if (bracket != null) {
                            options.add(
                                JevStrategyOption(
                                    label = bracket.groupValues[1].trim(),
                                    replyText = cleanOptionText(bracket.groupValues[2])
                                )
                            )
                        } else {
                            options.add(JevStrategyOption(label = defaultLabel, replyText = rawLine))
                        }
                    }
                }
            }

            if (options.isEmpty()) return null
            JevCopilotResult(
                level = level,
                tag = tag,
                subtext = subtext,
                options = options.take(3)
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun parsePlainTextTier3(text: String): JevCopilotResult {
        val rawLines = text
            .replace(Regex("</?[a-zA-Z][^>]*>"), " ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val options = mutableListOf<JevStrategyOption>()
        var detectedSubtext = ""

        for (line in rawLines) {
            val numbered = NUMBERED_LINE_REGEX.find(line)
            if (numbered != null) {
                val label = numbered.groupValues[1].trim().ifEmpty {
                    DEFAULT_FALLBACK_LABELS.getOrElse(options.size) { "策略 ${options.size + 1}" }
                }
                val body = cleanOptionText(numbered.groupValues[2])
                if (body.isNotBlank()) {
                    options.add(JevStrategyOption(label = label, replyText = body))
                }
                continue
            }
            val bracket = BRACKET_PREFIX_REGEX.find(line)
            if (bracket != null) {
                val label = bracket.groupValues[1].trim()
                val body = cleanOptionText(bracket.groupValues[2])
                if (body.isNotBlank()) {
                    options.add(JevStrategyOption(label = label, replyText = body))
                    continue
                }
            }
            if (options.isEmpty() && detectedSubtext.isEmpty() &&
                (line.startsWith("潜台词") || line.startsWith("局势") || line.startsWith("分析"))
            ) {
                detectedSubtext = line.replace(Regex("^(?:潜台词|局势|分析)\\s*[:：]?\\s*"), "").trim()
            } else {
                val cleanedLine = cleanOptionText(line)
                if (cleanedLine.isNotBlank()) {
                    options.add(
                        JevStrategyOption(
                            label = DEFAULT_FALLBACK_LABELS.getOrElse(options.size) { "策略 ${options.size + 1}" },
                            replyText = cleanedLine
                        )
                    )
                }
            }
        }

        if (options.isEmpty()) {
            val fallbackSingle = cleanOptionText(text).ifBlank { "收到，我先确认一下细节稍后回复你。" }
            options.add(
                JevStrategyOption(
                    label = DEFAULT_FALLBACK_LABELS[0],
                    replyText = fallbackSingle
                )
            )
        }

        return JevCopilotResult(
            level = 4,
            tag = "局势分析",
            subtext = detectedSubtext.ifBlank { "已根据当前上下文生成可直接点选的回复草稿" },
            options = options.take(3)
        )
    }

    private fun cleanOptionText(raw: String): String {
        return raw.trim()
            .removeSurrounding("\"")
            .removeSurrounding("“", "”")
            .removeSurrounding("「", "」")
            .trim()
    }
}
