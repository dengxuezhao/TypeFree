package com.typefree.core.ime.profile

/**
 * Static & probing-assisted Rime `.schema.yaml` inspector that infers [SchemaProfile] across
 * all four major Chinese input archetypes (`FIXED_YINXING`, `PUSH_TOPUP_CODE`, `FIXED_SHAPECODE`,
 * `SENTENCE_PINYIN`).
 */
object SchemaInspector {

    const val YINXING_MULTI_CHAR_SLOT_THRESHOLD: Double = 0.25

    data class ParsedSchemaMetadata(
        val schemaId: String,
        val displayName: String,
        val translators: List<String>,
        val spellerAlphabet: String,
        val maxCodeLength: Int?,
        val autoSelect: Boolean,
        val autoSelectPattern: String,
        val hasSpellerAlgebra: Boolean,
        val spellerAlgebraLines: List<String>,
        val enableSentence: Boolean?,
        val mainDictionary: String?,
        val reverseLookupDict: String?,
        val affixPrefixes: Set<String>,
        val customPhraseUserDicts: List<String>,
        val hasLuaTranslators: Boolean
    )

    fun parseSchemaYaml(schemaYaml: String): ParsedSchemaMetadata {
        var currentSection = ""
        var currentSubSection = ""
        var schemaId = "custom"
        var displayName = "自定义方案"
        val translators = mutableListOf<String>()
        var spellerAlphabet = "abcdefghijklmnopqrstuvwxyz"
        var maxCodeLength: Int? = null
        var autoSelect = false
        var autoSelectPattern = ""
        val spellerAlgebraLines = mutableListOf<String>()
        var enableSentence: Boolean? = null
        var mainDictionary: String? = null
        var reverseLookupDict: String? = null
        val affixPrefixes = linkedSetOf<String>()
        val customPhraseDicts = mutableListOf<String>()
        var hasLuaTranslators = false

        schemaYaml.lineSequence().forEach { rawLine ->
            val noComment = stripYamlComment(rawLine)
            if (noComment.isBlank()) return@forEach
            val indent = noComment.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val trimmed = noComment.trim()

            if (indent == 0 && trimmed.endsWith(":") && !trimmed.startsWith("-")) {
                currentSection = trimmed.removeSuffix(":").trim()
                currentSubSection = ""
                return@forEach
            }
            if (indent in 1..3 && trimmed.endsWith(":") && !trimmed.startsWith("-")) {
                currentSubSection = trimmed.removeSuffix(":").trim()
                return@forEach
            }

            when (currentSection) {
                "schema" -> {
                    if (trimmed.startsWith("schema_id:")) {
                        schemaId = cleanYamlScalar(trimmed.substringAfter("schema_id:"))
                    } else if (trimmed.startsWith("name:") && currentSubSection.isEmpty()) {
                        displayName = cleanYamlScalar(trimmed.substringAfter("name:"))
                    }
                }
                "engine" -> {
                    if (currentSubSection == "translators" && trimmed.startsWith("-")) {
                        val item = cleanYamlScalar(trimmed.removePrefix("-"))
                        translators.add(item)
                        if (item.startsWith("lua_translator")) {
                            hasLuaTranslators = true
                        }
                    }
                }
                "speller" -> {
                    when {
                        trimmed.startsWith("alphabet:") -> {
                            spellerAlphabet = cleanYamlScalar(trimmed.substringAfter("alphabet:"))
                        }
                        trimmed.startsWith("max_code_length:") -> {
                            maxCodeLength = cleanYamlScalar(trimmed.substringAfter("max_code_length:")).toIntOrNull()
                        }
                        trimmed.startsWith("auto_select:") -> {
                            autoSelect = cleanYamlScalar(trimmed.substringAfter("auto_select:"))
                                .equals("true", ignoreCase = true)
                        }
                        trimmed.startsWith("auto_select_pattern:") -> {
                            autoSelectPattern = cleanYamlScalar(trimmed.substringAfter("auto_select_pattern:"))
                        }
                        currentSubSection == "algebra" && trimmed.startsWith("-") -> {
                            spellerAlgebraLines.add(cleanYamlScalar(trimmed.removePrefix("-")))
                        }
                    }
                }
                "translator" -> {
                    when {
                        trimmed.startsWith("dictionary:") -> {
                            mainDictionary = cleanYamlScalar(trimmed.substringAfter("dictionary:")).ifBlank { null }
                        }
                        trimmed.startsWith("enable_sentence:") -> {
                            enableSentence = cleanYamlScalar(trimmed.substringAfter("enable_sentence:"))
                                .toBooleanStrictOrNull()
                        }
                    }
                }
                "reverse_lookup" -> {
                    if (trimmed.startsWith("dictionary:")) {
                        reverseLookupDict = cleanYamlScalar(trimmed.substringAfter("dictionary:")).ifBlank { null }
                    }
                }
                "recognizer" -> {
                    if (currentSubSection == "patterns" && trimmed.contains(":")) {
                        val patternVal = cleanYamlScalar(trimmed.substringAfter(":"))
                        extractAffixGuardsFromPattern(patternVal, affixPrefixes)
                    }
                }
                else -> {
                    if (currentSection.startsWith("custom_phrase") && trimmed.startsWith("user_dict:")) {
                        val udict = cleanYamlScalar(trimmed.substringAfter("user_dict:"))
                        if (udict.isNotEmpty()) customPhraseDicts.add(udict)
                    } else if (currentSection.endsWith("_mode") && trimmed.startsWith("prefix:")) {
                        val pfx = cleanYamlScalar(trimmed.substringAfter("prefix:"))
                        if (pfx.length in 2..3 && pfx.all { it in 'a'..'z' }) {
                            affixPrefixes.add(pfx)
                        }
                    }
                }
            }
        }

        extractAffixGuardsFromPattern(autoSelectPattern, affixPrefixes)

        return ParsedSchemaMetadata(
            schemaId = schemaId,
            displayName = displayName,
            translators = translators,
            spellerAlphabet = spellerAlphabet,
            maxCodeLength = maxCodeLength,
            autoSelect = autoSelect,
            autoSelectPattern = autoSelectPattern,
            hasSpellerAlgebra = spellerAlgebraLines.isNotEmpty(),
            spellerAlgebraLines = spellerAlgebraLines,
            enableSentence = enableSentence,
            mainDictionary = mainDictionary,
            reverseLookupDict = reverseLookupDict,
            affixPrefixes = affixPrefixes,
            customPhraseUserDicts = customPhraseDicts,
            hasLuaTranslators = hasLuaTranslators
        )
    }

    /**
     * Computes the fraction of 676 two-letter `aa..zz` slots whose top-1 candidate is a
     * multi-character phrase (`codePoints >= 2`), e.g. `41.0%` (`277/676`) on Xiaohe Flypy vs `0%` on Wubi 86.
     */
    fun computeTwoBriefMultiCharRatioFromDict(dictYamlContent: CharSequence): Double {
        val top1BySlot = BooleanArray(676)
        val seenSlot = BooleanArray(676)
        var inBody = false
        var sawYamlHeader = false

        val lines = dictYamlContent.toString().lineSequence()
        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed == "---") {
                sawYamlHeader = true
                continue
            }
            if (sawYamlHeader && !inBody) {
                if (trimmed == "...") inBody = true
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val tab1 = raw.indexOf('\t')
            if (tab1 <= 0 || tab1 >= raw.length - 1) continue
            val col0 = raw.substring(0, tab1).trim()
            val rest = raw.substring(tab1 + 1)
            val tab2 = rest.indexOf('\t')
            val col1 = (if (tab2 >= 0) rest.substring(0, tab2) else rest).trim()

            // Rime .dict.yaml is `word \t code`, whereas TSV may be `code \t word`
            val (word, code) = if (sawYamlHeader || !col0.all { it in 'a'..'z' || it == ';' }) {
                col0 to col1
            } else {
                col1 to col0
            }

            if (code.length == 2 && code[0] in 'a'..'z' && code[1] in 'a'..'z') {
                if (word.contains('$') || word.contains('{')) continue
                val idx = (code[0] - 'a') * 26 + (code[1] - 'a')
                if (!seenSlot[idx]) {
                    seenSlot[idx] = true
                    top1BySlot[idx] = word.codePointCount(0, word.length) >= 2
                }
            }
        }

        val multiCount = top1BySlot.count { it }
        return multiCount.toDouble() / 676.0
    }

    fun inspect(
        schemaYaml: String,
        dictYamlContent: CharSequence? = null,
        probedTwoBriefMultiCharRatio: Double? = null,
        tomlOverrideContent: String? = null
    ): SchemaProfile {
        val meta = parseSchemaYaml(schemaYaml)
        val ratio = probedTwoBriefMultiCharRatio
            ?: dictYamlContent?.let(::computeTwoBriefMultiCharRatioFromDict)

        val isSentenceScript = meta.translators.any { it == "script_translator" || it.startsWith("script_translator@") } &&
            meta.enableSentence != false &&
            meta.translators.none { it == "table_translator" }

        val isPushTopup = !isSentenceScript && isPushTopupPattern(meta)

        val archetype = when {
            isSentenceScript -> SchemaArchetype.SENTENCE_PINYIN
            isPushTopup -> SchemaArchetype.PUSH_TOPUP_CODE
            ratio != null -> {
                if (ratio >= YINXING_MULTI_CHAR_SLOT_THRESHOLD) {
                    SchemaArchetype.FIXED_YINXING
                } else {
                    SchemaArchetype.FIXED_SHAPECODE
                }
            }
            meta.schemaId.contains("flypy", ignoreCase = true) ||
                meta.displayName.contains("小鹤音形") -> SchemaArchetype.FIXED_YINXING
            else -> SchemaArchetype.FIXED_SHAPECODE
        }

        val sanitizedAlphabet = if (archetype == SchemaArchetype.FIXED_YINXING) {
            meta.spellerAlphabet.replace(";", "")
        } else {
            meta.spellerAlphabet
        }
        val guideKey: Char? = if (';' in sanitizedAlphabet) null else ';'

        val inferred = when (archetype) {
            SchemaArchetype.FIXED_YINXING -> SchemaProfile(
                schemaId = meta.schemaId,
                displayName = meta.displayName,
                archetype = SchemaArchetype.FIXED_YINXING,
                maxCodeLength = 4,
                lockNaturalOrder = true,
                allowAutoLearn = false,
                overlayGuideKey = guideKey,
                overlayShowAllOnEnter = true,
                semicolonSelectsSecond = true,
                reverseSplitEnabled = true,
                reverseSplitSuffixOnly = true,
                fifthKeyTopCommit = true,
                autoCommitUniqueFull = true,
                directCommandsEnabled = true,
                reverseLookupDict = meta.reverseLookupDict,
                gt4GuardPrefixes = meta.affixPrefixes.ifEmpty {
                    if (meta.schemaId == "flypy") setOf("ok", "os") else emptySet()
                },
                spellerAlphabet = sanitizedAlphabet
            )

            SchemaArchetype.PUSH_TOPUP_CODE -> SchemaProfile(
                schemaId = meta.schemaId,
                displayName = meta.displayName,
                archetype = SchemaArchetype.PUSH_TOPUP_CODE,
                maxCodeLength = meta.maxCodeLength ?: 0,
                lockNaturalOrder = true,
                allowAutoLearn = false,
                overlayGuideKey = guideKey,
                overlayShowAllOnEnter = true,
                semicolonSelectsSecond = guideKey != null,
                reverseSplitEnabled = false,
                reverseSplitSuffixOnly = false,
                fifthKeyTopCommit = false,
                autoCommitUniqueFull = false,
                directCommandsEnabled = true,
                reverseLookupDict = meta.reverseLookupDict,
                gt4GuardPrefixes = meta.affixPrefixes,
                spellerAlphabet = sanitizedAlphabet
            )

            SchemaArchetype.FIXED_SHAPECODE -> SchemaProfile(
                schemaId = meta.schemaId,
                displayName = meta.displayName,
                archetype = SchemaArchetype.FIXED_SHAPECODE,
                maxCodeLength = meta.maxCodeLength ?: 4,
                lockNaturalOrder = true,
                allowAutoLearn = false,
                overlayGuideKey = guideKey,
                overlayShowAllOnEnter = true,
                semicolonSelectsSecond = true,
                reverseSplitEnabled = false,
                reverseSplitSuffixOnly = true,
                fifthKeyTopCommit = true,
                autoCommitUniqueFull = true,
                directCommandsEnabled = true,
                reverseLookupDict = meta.reverseLookupDict,
                gt4GuardPrefixes = meta.affixPrefixes,
                spellerAlphabet = sanitizedAlphabet
            )

            SchemaArchetype.SENTENCE_PINYIN -> SchemaProfile(
                schemaId = meta.schemaId,
                displayName = meta.displayName,
                archetype = SchemaArchetype.SENTENCE_PINYIN,
                maxCodeLength = 0,
                lockNaturalOrder = false,
                allowAutoLearn = true,
                overlayGuideKey = guideKey,
                overlayShowAllOnEnter = true,
                semicolonSelectsSecond = true,
                reverseSplitEnabled = false,
                reverseSplitSuffixOnly = false,
                fifthKeyTopCommit = false,
                autoCommitUniqueFull = false,
                directCommandsEnabled = true,
                reverseLookupDict = meta.reverseLookupDict,
                gt4GuardPrefixes = emptySet(),
                spellerAlphabet = sanitizedAlphabet
            )
        }

        return if (!tomlOverrideContent.isNullOrBlank()) {
            TypeFreeTomlParser.parse(tomlOverrideContent, baseProfile = inferred)
        } else {
            inferred
        }
    }

    private fun isPushTopupPattern(meta: ParsedSchemaMetadata): Boolean {
        val sid = meta.schemaId.lowercase()
        val name = meta.displayName
        if (sid.startsWith("xkjd") || sid.contains("jiandao") || sid.startsWith("sbzr") ||
            sid.startsWith("sbfm") || sid.startsWith("sbjm") || name.contains("键道") ||
            name.contains("顶功") || name.contains("声笔")
        ) {
            return true
        }
        val pat = meta.autoSelectPattern
        // Character-space separation push-topup regexes (e.g. Keytao 6 `[aiouv]` stroke separation)
        if (pat.contains("[aiouv]") || pat.contains("[aeiou]") || pat.contains("[vui]") ||
            (pat.contains("[") && !pat.contains("[a-z]") && !pat.contains("[ks]"))
        ) {
            return true
        }
        // Complex speller algebra (feijian / topup derivations) on a table_translator schema
        if (meta.translators.any { it.startsWith("table_translator") } && meta.spellerAlgebraLines.size >= 3) {
            val nonWildcardRules = meta.spellerAlgebraLines.count { !it.contains("`") }
            if (nonWildcardRules >= 2) return true
        }
        return false
    }

    private fun extractAffixGuardsFromPattern(pattern: String, target: MutableSet<String>) {
        if (pattern.isEmpty()) return
        // Match explicit `o[ks]` style character classes or `^ok` / `^os`
        val bracketRegex = Regex("""([a-z])\[([a-z]+)]""")
        bracketRegex.findAll(pattern).forEach { m ->
            val head = m.groupValues[1]
            for (ch in m.groupValues[2]) {
                target.add("$head$ch")
            }
        }
        val plainPrefixRegex = Regex("""\^([a-z]{2})\[\^?a-z""")
        plainPrefixRegex.findAll(pattern).forEach { m ->
            target.add(m.groupValues[1])
        }
    }

    private fun stripYamlComment(line: String): String {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '\'' && !inDouble) inSingle = !inSingle
            else if (c == '"' && !inSingle && (i == 0 || line[i - 1] != '\\')) inDouble = !inDouble
            else if (c == '#' && !inSingle && !inDouble && (i == 0 || line[i - 1].isWhitespace())) {
                return line.substring(0, i)
            }
        }
        return line
    }

    private fun cleanYamlScalar(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2) {
            if ((trimmed.startsWith('"') && trimmed.endsWith('"')) ||
                (trimmed.startsWith('\'') && trimmed.endsWith('\''))
            ) {
                return trimmed.substring(1, trimmed.length - 1)
            }
        }
        return trimmed
    }
}
