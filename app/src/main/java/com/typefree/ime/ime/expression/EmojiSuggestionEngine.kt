package com.typefree.ime.ime.expression

import android.content.Context
import com.typefree.ime.ime.flypy.FlypyCandidate
import com.typefree.ime.ime.predict.PredictionStore
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * High-speed local Simplified + Traditional Chinese Emoji suggestion engine for Pinyin & Double Pinyin:
 * - Loads `assets/predict/emoji_zh_lexicon.tsv` (~7,600 SC+TC keywords derived from Unicode CLDR
 *   `zh` + `zh-Hant` annotations + curated daily Chinese chat vocabulary) once on `Dispatchers.IO`
 *   into O(1) maps. Before the IO load completes, returns empty without touching `ExpressionCatalog`
 *   on the main thread so first-keystroke latency stays `0ms`.
 * - Composing suggestions (`suggestForComposing`):
 *   - Immediately suppressed when `preedit` already contains an uncommitted Chinese prefix segment
 *     (Rime multi-step selection) so tapping an emoji can NEVER discard previously selected words.
 *   - Uses strict exact match on Candidate #1 (and Candidate #2 fallback when Candidate #1 is short).
 * - Post-commit suggestions (`suggestForCommittedText`):
 *   - Step 1: Exact match across the full SC+TC lexicon (ignoring trailing terminal punctuation).
 *   - Step 2 & 3: Modal-particle stripping (`笑死了 -> 笑死`, `收到啦 -> 收到`) and `4->3->2`-char
 *     trailing suffix matching (`今天太开心了 -> 开心`, `今天太開心了 -> 開心`) are strictly gated
 *     to the Tier-1 high-confidence chat-phrase whitelist (`col3 == "1"`) to eliminate noisy triggers
 *     on broad CLDR nouns/verbs in everyday sentences.
 * - Resolves saved `emojiSkinTonePrefs` with automatic fallback to base `raw` glyph when a device
 *   font lacks the skin-tone glyph.
 */
class EmojiSuggestionEngine {

    private val lexicon = ConcurrentHashMap<String, List<String>>(8192)
    private val suffixWhitelist = ConcurrentHashMap.newKeySet<String>(512)

    @Volatile
    private var bundledLoaded: Boolean = false

    val loadedKeywordCount: Int
        get() = lexicon.size

    val suffixWhitelistCount: Int
        get() = suffixWhitelist.size

    fun loadFromContextIfAvailable(context: Context): Int {
        if (bundledLoaded && lexicon.isNotEmpty()) return lexicon.size
        val loaded = runCatching {
            context.assets.open(BUNDLED_LEXICON_ASSET_PATH).use { stream ->
                loadFromTsvStream(stream)
            }
        }.getOrDefault(0)
        if (loaded > 0) {
            bundledLoaded = true
        }
        return loaded
    }

    fun loadFromTsvStream(inputStream: InputStream): Int {
        var count = 0
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val rawLine = reader.readLine() ?: break
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split('\t')
                if (parts.size < 2) continue
                val key = parts[0].trim()
                if (key.isEmpty()) continue
                val emojis = parts[1]
                    .split(' ')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                if (emojis.isNotEmpty()) {
                    lexicon[key] = emojis
                    if (parts.size >= 3 && parts[2].trim() == "1") {
                        suffixWhitelist.add(key)
                    }
                    count++
                }
            }
        }
        if (count > 0) {
            bundledLoaded = true
        }
        return count
    }

    private fun lookupExact(keyword: String): List<String> {
        if (keyword.isEmpty() || !bundledLoaded) return emptyList()
        return lexicon[keyword].orEmpty()
    }

    private fun lookupTier1Suffix(keyword: String): List<String> {
        if (keyword.isEmpty() || !bundledLoaded) return emptyList()
        if (!suffixWhitelist.contains(keyword)) return emptyList()
        return lexicon[keyword].orEmpty()
    }

    private fun finalizeSuggestions(
        rawCandidates: Iterable<String>,
        skinTonePrefs: Map<String, String>,
        limit: Int,
        isGlyphSupported: (String) -> Boolean
    ): List<String> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<String>(limit)
        val seen = HashSet<String>(limit * 2)
        for (raw in rawCandidates) {
            if (raw.isBlank()) continue
            val resolved = if (skinTonePrefs.isEmpty()) {
                raw
            } else {
                ExpressionCatalog.resolveDisplayGlyphForRaw(raw, skinTonePrefs)
            }
            val candidate = if (runCatching { isGlyphSupported(resolved) }.getOrDefault(true)) {
                resolved
            } else if (resolved != raw && runCatching { isGlyphSupported(raw) }.getOrDefault(true)) {
                raw
            } else {
                continue
            }
            if (seen.add(candidate)) {
                out.add(candidate)
                if (out.size >= limit) break
            }
        }
        return out
    }

    /**
     * Composing-phase suggestions shown inside `CandidateStrip`:
     * - Suppressed immediately if [preedit] already holds an uncommitted CJK prefix segment
     *   (Rime multi-step selection) so tapping an emoji cannot discard earlier selected words.
     * - Strict exact match on Candidate #1 (and Candidate #2 fallback when Candidate #1 is short).
     */
    fun suggestForComposing(
        candidates: List<FlypyCandidate>,
        preedit: String = "",
        rawCode: String = "",
        skinTonePrefs: Map<String, String> = emptyMap(),
        limit: Int = DEFAULT_COMPOSING_LIMIT,
        isGlyphSupported: (String) -> Boolean = { true }
    ): List<String> {
        if (limit <= 0 || !bundledLoaded || candidates.isEmpty()) return emptyList()
        if (hasUncommittedSelectedPrefix(preedit, rawCode)) return emptyList()

        val firstText = candidates[0].text.trim()
        if (firstText.isEmpty() || firstText.length > MAX_EXACT_KEYWORD_LEN) return emptyList()
        if (PredictionStore.isPunctuationOrSymbolOnly(firstText)) return emptyList()

        val rawHits = ArrayList<String>(limit * 2)
        rawHits.addAll(lookupExact(firstText))

        if (rawHits.isEmpty() && firstText.length <= 4 && candidates.size >= 2) {
            val secondText = candidates[1].text.trim()
            if (
                secondText.isNotEmpty() &&
                secondText.length in 2..MAX_EXACT_KEYWORD_LEN &&
                !PredictionStore.isPunctuationOrSymbolOnly(secondText)
            ) {
                rawHits.addAll(lookupExact(secondText))
            }
        }
        if (rawHits.isEmpty()) return emptyList()
        return finalizeSuggestions(rawHits, skinTonePrefs, limit, isGlyphSupported)
    }

    /**
     * Post-commit suggestions shown in `PredictionCandidateBar`:
     * 1. Exact match on the full SC+TC lexicon (stripping trailing `!`/`！`/`?`/`？`/`。`/`~`).
     * 2. Modal-particle stripping (`笑死了 -> 笑死`, `收到啦 -> 收到`) restricted to Tier-1 chat phrases.
     * 3. Longest `4 -> 3 -> 2`-char trailing suffix match (`今天太开心了 -> 开心`) restricted to Tier-1 chat phrases.
     */
    fun suggestForCommittedText(
        committedText: String,
        skinTonePrefs: Map<String, String> = emptyMap(),
        limit: Int = DEFAULT_POST_COMMIT_LIMIT,
        isGlyphSupported: (String) -> Boolean = { true }
    ): List<String> {
        if (limit <= 0 || !bundledLoaded) return emptyList()
        val trimmed = committedText.trim()
        if (trimmed.isEmpty() || PredictionStore.isPunctuationOrSymbolOnly(trimmed)) return emptyList()
        if (trimmed.last() in CLAUSE_BREAK_PUNCTUATION) return emptyList()

        val clean = trimmed.dropLastWhile { it in TERMINAL_STRIP_PUNCTUATION }.trim()
        if (clean.isEmpty() || PredictionStore.isPunctuationOrSymbolOnly(clean)) return emptyList()

        val rawHits = ArrayList<String>(limit * 2)
        fun collect(hits: List<String>) {
            for (h in hits) {
                if (rawHits.size >= limit * 2) return
                if (h !in rawHits) rawHits.add(h)
            }
        }

        // 1. Direct exact match against full SC+TC lexicon
        if (clean.length <= MAX_EXACT_KEYWORD_LEN) {
            collect(lookupExact(clean))
        }

        // 2. Strip up to 2 trailing Chinese modal particles — Tier-1 high-confidence chat phrases only
        val modalStripped = stripTrailingModalParticles(clean)
        if (
            rawHits.size < limit &&
            modalStripped != clean &&
            modalStripped.isNotEmpty() &&
            modalStripped.length <= MAX_EXACT_KEYWORD_LEN
        ) {
            collect(lookupTier1Suffix(modalStripped))
        }

        // 3. Longest 4..2 char trailing suffix match — Tier-1 high-confidence chat phrases only
        if (rawHits.size < limit) {
            collectTier1TrailingSuffixMatches(modalStripped, ::collect)
        }
        if (rawHits.size < limit && modalStripped != clean) {
            collectTier1TrailingSuffixMatches(clean, ::collect)
        }

        if (rawHits.isEmpty()) return emptyList()
        return finalizeSuggestions(rawHits, skinTonePrefs, limit, isGlyphSupported)
    }

    private fun collectTier1TrailingSuffixMatches(
        text: String,
        collector: (List<String>) -> Unit
    ) {
        val len = text.length
        if (len < 2) return
        val maxSuffix = minOf(MAX_SUFFIX_LEN, len)
        for (subLen in maxSuffix downTo 2) {
            if (subLen == len) continue // Already checked by exact match
            val startIdx = len - subLen
            if (isNegatedBefore(text, startIdx)) continue
            val suffix = text.substring(startIdx, len)
            val hits = lookupTier1Suffix(suffix)
            if (hits.isNotEmpty()) {
                collector(hits)
                return
            }
        }
    }

    private fun isNegatedBefore(text: String, suffixStart: Int): Boolean {
        if (suffixStart <= 0) return false
        val prev = text[suffixStart - 1]
        if (prev in NEGATION_PREFIX_CHARS) return true
        if (suffixStart >= 2 && prev in DEGREE_ADVERB_CHARS && text[suffixStart - 2] in NEGATION_PREFIX_CHARS) {
            return true
        }
        return false
    }

    private fun stripTrailingModalParticles(text: String): String {
        var end = text.length
        var stripped = 0
        while (end > 1 && stripped < 2 && text[end - 1] in TRAILING_MODAL_PARTICLES) {
            end--
            stripped++
        }
        return if (end == text.length) text else text.substring(0, end)
    }

    companion object {
        const val BUNDLED_LEXICON_ASSET_PATH = "predict/emoji_zh_lexicon.tsv"
        const val DEFAULT_COMPOSING_LIMIT = 2
        const val DEFAULT_POST_COMMIT_LIMIT = 3
        private const val MAX_EXACT_KEYWORD_LEN = 6
        private const val MAX_SUFFIX_LEN = 4

        private val CLAUSE_BREAK_PUNCTUATION = setOf('，', ',', '、', '：', ':', '；', ';')
        private val TERMINAL_STRIP_PUNCTUATION = setOf('！', '!', '？', '?', '。', '.', '~', '～')
        private val TRAILING_MODAL_PARTICLES = setOf(
            '了', '啦', '呀', '啊', '哇', '呢', '吧', '哦', '噢', '咯', '嘛', '呐', '哒'
        )
        private val NEGATION_PREFIX_CHARS = setOf('不', '没', '沒', '别', '別', '勿', '莫')
        private val DEGREE_ADVERB_CHARS = setOf('太', '很', '挺', '够', '夠', '算', '怎', '么', '麼')

        /**
         * Returns true when [preedit] contains any already-selected uncommitted prefix segment:
         * - Any Unicode ideograph (`Character.isIdeographic`, covering BMP + CJK Extensions B-G)
         * - Any full-width / CJK punctuation (`\u3000..\u303F`, `\uFF00..\uFFEF`)
         * - Any uppercase ASCII segment (e.g. Rime selected `"AI kai xin"`) when [rawCode] is lowercase
         */
        fun hasUncommittedSelectedPrefix(preedit: String, rawCode: String = ""): Boolean {
            if (preedit.isEmpty()) return false
            val rawAllLower = rawCode.isNotEmpty() && rawCode.none { it in 'A'..'Z' }
            var i = 0
            while (i < preedit.length) {
                val cp = Character.codePointAt(preedit, i)
                if (Character.isIdeographic(cp)) return true
                if (cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF) return true
                if (rawAllLower && cp in 'A'.code..'Z'.code) return true
                i += Character.charCount(cp)
            }
            return false
        }
    }
}
