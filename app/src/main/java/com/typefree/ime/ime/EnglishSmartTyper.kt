package com.typefree.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.util.Locale

/**
 * Pure-Kotlin native-grade English typing assistant for QWERTY `EN` mode:
 * - Smart sentence/start-of-field auto-capitalization (`. `, `! `, `? `, `\n`, empty field)
 * - Unambiguous apostrophe-free contractions (`im` -> `I'm`, `dont` -> `don't`, standalone `i` -> `I`)
 *   without ever corrupting valid English words (`ill`, `were`, `well`, `hell`, `shell`, `id`, `its`)
 * - High-confidence common English typo auto-fixes (`teh` -> `the`, `recieve` -> `receive`, etc.)
 * - Strict code/URL/email/password/`NO_SUGGESTIONS`/`FORCE_ASCII` bypass
 * - 1-tap immediate Backspace revert (`don't ` -> `dont`) with temporary suppression so the user
 *   is never trapped in an autocorrect loop.
 */
class EnglishSmartTyper {

    data class AutoCorrectCommit(
        val deleteBeforeLength: Int,
        val textToCommit: String,
        val originalToken: String,
        val replacementText: String
    )

    data class BackspaceRevert(
        val deleteBeforeLength: Int,
        val restoredToken: String
    )

    private data class PendingRevertState(
        val originalToken: String,
        val committedTextWithDelimiter: String
    )

    private var pendingRevert: PendingRevertState? = null
    private var suppressedLowerToken: String? = null

    fun resetSession() {
        pendingRevert = null
        suppressedLowerToken = null
    }

    fun onRegularInputCommitted() {
        pendingRevert = null
        suppressedLowerToken = null
    }

    /**
     * Evaluates whether committing [delimiter] (e.g. `" "`, `"."`, `","`, `"!"`, `"?"`) right after
     * [textBeforeCursor] should replace the trailing English word token.
     */
    fun onCommitDelimiter(
        textBeforeCursor: String,
        delimiter: String,
        inputType: Int,
        imeOptions: Int,
        isChineseMode: Boolean,
        englishAutoCorrectEnabled: Boolean
    ): AutoCorrectCommit? {
        pendingRevert = null
        if (!englishAutoCorrectEnabled) {
            suppressedLowerToken = null
            return null
        }
        if (!shouldEnableEnglishProseAssist(inputType, imeOptions, isChineseMode)) {
            suppressedLowerToken = null
            return null
        }
        if (delimiter.isEmpty()) return null

        val token = extractProseTrailingWord(textBeforeCursor) ?: run {
            suppressedLowerToken = null
            return null
        }

        val lower = token.lowercase(Locale.ROOT)
        if (suppressedLowerToken == lower) {
            suppressedLowerToken = null
            return null
        }
        suppressedLowerToken = null

        val replacement = resolveReplacement(token) ?: return null
        if (replacement == token) return null

        val committedWithDelimiter = replacement + delimiter
        pendingRevert = PendingRevertState(
            originalToken = token,
            committedTextWithDelimiter = committedWithDelimiter
        )
        return AutoCorrectCommit(
            deleteBeforeLength = token.length,
            textToCommit = committedWithDelimiter,
            originalToken = token,
            replacementText = replacement
        )
    }

    /**
     * Checks whether an immediate Backspace press should revert the most recent auto-replacement
     * (e.g. `"don't "` -> `"dont"`) and temporarily suppress re-correcting that word on the next space.
     */
    fun onBackspace(textBeforeCursor: String): BackspaceRevert? {
        val pending = pendingRevert ?: return null
        pendingRevert = null
        if (!textBeforeCursor.endsWith(pending.committedTextWithDelimiter)) {
            return null
        }
        suppressedLowerToken = pending.originalToken.lowercase(Locale.ROOT)
        return BackspaceRevert(
            deleteBeforeLength = pending.committedTextWithDelimiter.length,
            restoredToken = pending.originalToken
        )
    }

    companion object {
        /**
         * Unambiguous apostrophe-free English contractions.
         * Real English words (`ill`, `were`, `well`, `hell`, `shell`, `id`, `wed`, `its`, `lets`, `hed`, `shed`)
         * are intentionally excluded so valid prose is never corrupted.
         */
        private val CONTRACTION_MAP: Map<String, String> = mapOf(
            "i" to "I",
            "im" to "I'm",
            "ive" to "I've",
            "dont" to "don't",
            "cant" to "can't",
            "wont" to "won't",
            "didnt" to "didn't",
            "doesnt" to "doesn't",
            "isnt" to "isn't",
            "arent" to "aren't",
            "wasnt" to "wasn't",
            "werent" to "weren't",
            "hasnt" to "hasn't",
            "havent" to "haven't",
            "hadnt" to "hadn't",
            "wouldnt" to "wouldn't",
            "couldnt" to "couldn't",
            "shouldnt" to "shouldn't",
            "thats" to "that's",
            "whats" to "what's",
            "heres" to "here's",
            "theres" to "there's",
            "youre" to "you're",
            "theyre" to "they're"
        )

        /**
         * High-confidence unambiguous English misspellings.
         */
        private val TYPO_MAP: Map<String, String> = mapOf(
            "teh" to "the",
            "recieve" to "receive",
            "becuase" to "because",
            "definately" to "definitely",
            "seperate" to "separate",
            "occured" to "occurred",
            "adress" to "address",
            "thier" to "their",
            "wierd" to "weird",
            "untill" to "until",
            "alot" to "a lot",
            "wich" to "which",
            "tommorow" to "tomorrow",
            "accomodate" to "accommodate"
        )

        private val PROSE_WORD_BOUNDARY_CHARS: Set<Char> = setOf(
            ' ', '\n', '\t', '\r',
            '"', '“', '‘', '(', '[', '{', '—'
        )

        /**
         * Returns `true` only when typing in `EN` QWERTY mode on a normal prose text field.
         * Skips Chinese mode, password fields, URI/email/filter variations, `TYPE_TEXT_FLAG_NO_SUGGESTIONS`,
         * `IME_FLAG_FORCE_ASCII` code/terminal fields, and numeric/phone/datetime editors.
         */
        fun shouldEnableEnglishProseAssist(
            inputType: Int,
            imeOptions: Int,
            isChineseMode: Boolean
        ): Boolean {
            if (isChineseMode) return false
            if (ImeLogic.isPasswordEditorInputType(inputType)) return false
            if (ImeLogic.isAsciiPreferredEditor(inputType, imeOptions)) return false
            if ((imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) != 0) return false
            if ((inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false

            val cls = inputType and InputType.TYPE_MASK_CLASS
            if (cls != 0 && cls != InputType.TYPE_CLASS_TEXT) {
                return false
            }
            if (cls == InputType.TYPE_CLASS_TEXT) {
                val variation = inputType and InputType.TYPE_MASK_VARIATION
                if (
                    variation == InputType.TYPE_TEXT_VARIATION_URI ||
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_FILTER
                ) {
                    return false
                }
            }
            return true
        }

        /**
         * Determines whether one-shot Shift should automatically activate in `EN` QWERTY mode:
         * - At the start of an empty field (or leading spaces)
         * - Immediately after `\n`
         * - After sentence-ending `. `, `! `, or `? `
         */
        fun shouldAutoCapitalize(
            textBeforeCursor: String,
            inputType: Int,
            imeOptions: Int,
            isChineseMode: Boolean,
            englishAutoCapEnabled: Boolean
        ): Boolean {
            if (!englishAutoCapEnabled) return false
            if (!shouldEnableEnglishProseAssist(inputType, imeOptions, isChineseMode)) return false
            if ((inputType and InputType.TYPE_TEXT_VARIATION_PERSON_NAME) != 0 && textBeforeCursor.endsWith(" ")) {
                return true
            }

            if (textBeforeCursor.isEmpty()) return true
            if (textBeforeCursor.all { it == ' ' || it == '\t' }) return true

            val lastChar = textBeforeCursor.last()
            if (lastChar == '\n') return true

            if (lastChar == ' ' || lastChar == '\t') {
                var idx = textBeforeCursor.length - 1
                while (idx >= 0 && (textBeforeCursor[idx] == ' ' || textBeforeCursor[idx] == '\t')) {
                    idx--
                }
                if (idx < 0) return true
                if (textBeforeCursor[idx] == '\n') return true
                while (
                    idx >= 0 &&
                    (textBeforeCursor[idx] == '"' ||
                        textBeforeCursor[idx] == '”' ||
                        textBeforeCursor[idx] == '\'' ||
                        textBeforeCursor[idx] == '’' ||
                        textBeforeCursor[idx] == ')' ||
                        textBeforeCursor[idx] == ']')
                ) {
                    idx--
                }
                if (idx >= 0) {
                    val punct = textBeforeCursor[idx]
                    if (punct == '.' || punct == '!' || punct == '?') {
                        return true
                    }
                }
            }
            return false
        }

        /**
         * Extracts the trailing `[A-Za-z]+` prose word from [textBeforeCursor], returning `null`
         * if preceded by URL/email/path/code characters (`/`, `.`, `@`, `_`, `-`, `:`, `#`, digits, etc.).
         */
        fun extractProseTrailingWord(textBeforeCursor: String): String? {
            if (textBeforeCursor.isEmpty()) return null
            var start = textBeforeCursor.length
            while (start > 0) {
                val c = textBeforeCursor[start - 1]
                if ((c in 'a'..'z') || (c in 'A'..'Z')) {
                    start--
                } else {
                    break
                }
            }
            if (start == textBeforeCursor.length) return null
            if (start > 0) {
                val prev = textBeforeCursor[start - 1]
                if (prev !in PROSE_WORD_BOUNDARY_CHARS) {
                    return null
                }
            }
            return textBeforeCursor.substring(start)
        }

        /**
         * Resolves contraction or typo replacement for [word], preserving initial capitalization
         * (`Dont` -> `Don't`, `Teh` -> `The`, `DONT` -> `DON'T`) while leaving mixed-case identifiers
         * (`dOnT`) and the uppercase acronym `"IM"` untouched.
         */
        fun resolveReplacement(word: String): String? {
            if (word.isEmpty()) return null
            val lower = word.lowercase(Locale.ROOT)
            val canonical = CONTRACTION_MAP[lower] ?: TYPO_MAP[lower] ?: return null

            if (lower == "i") {
                return if (word == "i") "I" else null
            }
            if (word == "IM") {
                return null
            }

            val isAllLower = word.all { it.isLowerCase() }
            if (isAllLower) {
                return canonical
            }

            val isTitleCase = word[0].isUpperCase() && word.drop(1).all { it.isLowerCase() }
            if (isTitleCase) {
                return canonical.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }
            }

            val isAllUpper = word.length >= 2 && word.all { it.isUpperCase() }
            if (isAllUpper) {
                return canonical.uppercase(Locale.ROOT)
            }

            // Mixed internal casing (e.g. camelCase code identifier) -> never touch
            return null
        }
    }
}
