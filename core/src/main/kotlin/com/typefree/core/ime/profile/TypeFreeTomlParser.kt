package com.typefree.core.ime.profile

/**
 * Lightweight zero-dependency TOML parser and serializer for `typefree.toml` schema manifests.
 */
object TypeFreeTomlParser {

    fun parse(tomlContent: String, baseProfile: SchemaProfile? = null): SchemaProfile {
        val rawMap = LinkedHashMap<String, String>()
        tomlContent.lineSequence().forEach { rawLine ->
            val clean = stripInlineComment(rawLine).trim()
            if (clean.isEmpty() || clean.startsWith("[")) return@forEach
            val eqIdx = clean.indexOf('=')
            if (eqIdx <= 0) return@forEach
            val key = clean.substring(0, eqIdx).trim()
            val value = clean.substring(eqIdx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                rawMap[key] = value
            }
        }

        val archetype = rawMap["archetype"]
            ?.let(::unquote)
            ?.let { name -> SchemaArchetype.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?: baseProfile?.archetype
            ?: SchemaArchetype.FIXED_YINXING

        val fallback = baseProfile ?: defaultForArchetype(
            schemaId = rawMap["schema_id"]?.let(::unquote) ?: "custom",
            displayName = rawMap["display_name"]?.let(::unquote) ?: "自定义方案",
            archetype = archetype
        )

        val guideRaw = rawMap["overlay_guide_key"]
        val resolvedGuideKey: Char? = when {
            guideRaw == null -> fallback.overlayGuideKey
            guideRaw.equals("none", ignoreCase = true) || unquote(guideRaw).isEmpty() -> null
            else -> unquote(guideRaw).firstOrNull()
        }

        val reverseLookup = rawMap["reverse_lookup_dict"]?.let(::unquote)?.ifBlank { null }
            ?: fallback.reverseLookupDict

        val guardPrefixes = rawMap["gt4_guard_prefixes"]?.let(::parseStringArray)?.toSet()
            ?: fallback.gt4GuardPrefixes

        return fallback.copy(
            schemaId = rawMap["schema_id"]?.let(::unquote)?.ifBlank { null } ?: fallback.schemaId,
            displayName = rawMap["display_name"]?.let(::unquote)?.ifBlank { null } ?: fallback.displayName,
            archetype = archetype,
            maxCodeLength = rawMap["max_code_length"]?.toIntOrNull() ?: fallback.maxCodeLength,
            lockNaturalOrder = rawMap["lock_natural_order"]?.toBooleanStrictOrNull() ?: fallback.lockNaturalOrder,
            allowAutoLearn = rawMap["allow_auto_learn"]?.toBooleanStrictOrNull() ?: fallback.allowAutoLearn,
            overlayGuideKey = resolvedGuideKey,
            overlayShowAllOnEnter = rawMap["overlay_show_all_on_enter"]?.toBooleanStrictOrNull()
                ?: fallback.overlayShowAllOnEnter,
            semicolonSelectsSecond = rawMap["semicolon_selects_second"]?.toBooleanStrictOrNull()
                ?: fallback.semicolonSelectsSecond,
            reverseSplitEnabled = rawMap["reverse_split_enabled"]?.toBooleanStrictOrNull()
                ?: fallback.reverseSplitEnabled,
            reverseSplitSuffixOnly = rawMap["reverse_split_suffix_only"]?.toBooleanStrictOrNull()
                ?: fallback.reverseSplitSuffixOnly,
            fifthKeyTopCommit = rawMap["fifth_key_top_commit"]?.toBooleanStrictOrNull()
                ?: fallback.fifthKeyTopCommit,
            autoCommitUniqueFull = rawMap["auto_commit_unique_full"]?.toBooleanStrictOrNull()
                ?: fallback.autoCommitUniqueFull,
            directCommandsEnabled = rawMap["direct_commands_enabled"]?.toBooleanStrictOrNull()
                ?: fallback.directCommandsEnabled,
            reverseLookupDict = reverseLookup,
            gt4GuardPrefixes = guardPrefixes,
            spellerAlphabet = rawMap["speller_alphabet"]?.let(::unquote)?.ifBlank { null }
                ?: fallback.spellerAlphabet,
            englishPrefixVetoEnabled = rawMap["english_prefix_veto_enabled"]?.toBooleanStrictOrNull()
                ?: fallback.englishPrefixVetoEnabled,
            autoEncodePhrasesEnabled = rawMap["auto_encode_phrases_enabled"]?.toBooleanStrictOrNull()
                ?: fallback.autoEncodePhrasesEnabled
        )
    }

    fun serialize(profile: SchemaProfile): String {
        val guideStr = profile.overlayGuideKey?.toString() ?: ""
        val revDict = profile.reverseLookupDict.orEmpty()
        val prefixes = profile.gt4GuardPrefixes.sorted().joinToString(", ") { "\"$it\"" }
        return buildString {
            appendLine("# TypeFree SchemaProfile Manifest (v3.6)")
            appendLine("schema_id = \"${escape(profile.schemaId)}\"")
            appendLine("display_name = \"${escape(profile.displayName)}\"")
            appendLine("archetype = \"${profile.archetype.name}\"")
            appendLine("max_code_length = ${profile.maxCodeLength}")
            appendLine("lock_natural_order = ${profile.lockNaturalOrder}")
            appendLine("allow_auto_learn = ${profile.allowAutoLearn}")
            appendLine("overlay_guide_key = \"${escape(guideStr)}\"")
            appendLine("overlay_show_all_on_enter = ${profile.overlayShowAllOnEnter}")
            appendLine("semicolon_selects_second = ${profile.semicolonSelectsSecond}")
            appendLine("reverse_split_enabled = ${profile.reverseSplitEnabled}")
            appendLine("reverse_split_suffix_only = ${profile.reverseSplitSuffixOnly}")
            appendLine("fifth_key_top_commit = ${profile.fifthKeyTopCommit}")
            appendLine("auto_commit_unique_full = ${profile.autoCommitUniqueFull}")
            appendLine("direct_commands_enabled = ${profile.directCommandsEnabled}")
            appendLine("english_prefix_veto_enabled = ${profile.englishPrefixVetoEnabled}")
            appendLine("auto_encode_phrases_enabled = ${profile.autoEncodePhrasesEnabled}")
            if (revDict.isNotEmpty()) {
                appendLine("reverse_lookup_dict = \"${escape(revDict)}\"")
            }
            if (profile.gt4GuardPrefixes.isNotEmpty()) {
                appendLine("gt4_guard_prefixes = [$prefixes]")
            }
            if (profile.spellerAlphabet.isNotEmpty()) {
                appendLine("speller_alphabet = \"${escape(profile.spellerAlphabet)}\"")
            }
        }
    }

    private fun defaultForArchetype(
        schemaId: String,
        displayName: String,
        archetype: SchemaArchetype
    ): SchemaProfile {
        return when (archetype) {
            SchemaArchetype.FIXED_YINXING -> SchemaProfile(
                schemaId = schemaId,
                displayName = displayName,
                archetype = SchemaArchetype.FIXED_YINXING,
                maxCodeLength = 4,
                lockNaturalOrder = true,
                allowAutoLearn = false,
                overlayGuideKey = ';',
                reverseSplitEnabled = true,
                reverseSplitSuffixOnly = true,
                fifthKeyTopCommit = true,
                autoCommitUniqueFull = true,
                directCommandsEnabled = true
            )
            SchemaArchetype.PUSH_TOPUP_CODE -> SchemaProfile(
                schemaId = schemaId,
                displayName = displayName,
                archetype = SchemaArchetype.PUSH_TOPUP_CODE,
                maxCodeLength = 0,
                lockNaturalOrder = true,
                allowAutoLearn = false,
                overlayGuideKey = ';',
                reverseSplitEnabled = false,
                fifthKeyTopCommit = false,
                autoCommitUniqueFull = false,
                directCommandsEnabled = true
            )
            SchemaArchetype.FIXED_SHAPECODE -> SchemaProfile(
                schemaId = schemaId,
                displayName = displayName,
                archetype = SchemaArchetype.FIXED_SHAPECODE,
                maxCodeLength = 4,
                lockNaturalOrder = true,
                allowAutoLearn = false,
                overlayGuideKey = ';',
                reverseSplitEnabled = false,
                fifthKeyTopCommit = true,
                autoCommitUniqueFull = true,
                directCommandsEnabled = true
            )
            SchemaArchetype.SENTENCE_PINYIN -> SchemaProfile.defaultSentencePinyin(schemaId, displayName)
        }
    }

    private fun stripInlineComment(line: String): String {
        var inQuotes = false
        var quoteChar = '"'
        for (i in line.indices) {
            val ch = line[i]
            if ((ch == '"' || ch == '\'') && (i == 0 || line[i - 1] != '\\')) {
                if (!inQuotes) {
                    inQuotes = true
                    quoteChar = ch
                } else if (ch == quoteChar) {
                    inQuotes = false
                }
            } else if (ch == '#' && !inQuotes) {
                return line.substring(0, i)
            }
        }
        return line
    }

    private fun unquote(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2) {
            if ((trimmed.startsWith('"') && trimmed.endsWith('"')) ||
                (trimmed.startsWith('\'') && trimmed.endsWith('\''))
            ) {
                return trimmed.substring(1, trimmed.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
        }
        return trimmed
    }

    private fun parseStringArray(raw: String): List<String> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(',')
            .map { unquote(it) }
            .filter { it.isNotEmpty() }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
