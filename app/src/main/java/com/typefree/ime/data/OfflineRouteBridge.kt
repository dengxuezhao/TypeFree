package com.typefree.ime.data

import com.typefree.core.offline.AiTask
import com.typefree.core.offline.CloudAvailability
import com.typefree.core.offline.CloudPipelineHint
import com.typefree.core.offline.LocalModelRegistrySnapshot
import com.typefree.core.offline.OfflineRoutePlanner
import com.typefree.core.offline.ReadinessState
import com.typefree.core.offline.RouteBadge
import com.typefree.core.offline.RouteInput
import com.typefree.core.offline.RoutePlan
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.EndpointResolver

object OfflineRouteBridge {

    fun buildRouteInput(
        task: AiTask,
        settings: AppSettings,
        registrySnapshot: LocalModelRegistrySnapshot = LocalModelRegistrySnapshot(),
        isNetworkAvailable: Boolean = true,
        isSecureField: Boolean = false,
        isIncognito: Boolean = settings.manualIncognitoMode,
        isThermalThrottled: Boolean = false
    ): RouteInput {
        val cloud = projectCloudAvailability(settings)
        return RouteInput(
            task = task,
            offline = settings.offlineConfig.toCoreData(),
            registry = registrySnapshot,
            cloud = cloud,
            isNetworkAvailable = isNetworkAvailable,
            isSecureField = isSecureField,
            isIncognito = isIncognito,
            isThermalThrottled = isThermalThrottled,
            liveWriteSettingEnabled = settings.liveWriteToEditor
        )
    }

    fun resolvePlan(
        task: AiTask,
        settings: AppSettings,
        registrySnapshot: LocalModelRegistrySnapshot = LocalModelRegistrySnapshot(),
        isNetworkAvailable: Boolean = true,
        isSecureField: Boolean = false,
        isIncognito: Boolean = settings.manualIncognitoMode,
        isThermalThrottled: Boolean = false
    ): RoutePlan {
        val input = buildRouteInput(
            task = task,
            settings = settings,
            registrySnapshot = registrySnapshot,
            isNetworkAvailable = isNetworkAvailable,
            isSecureField = isSecureField,
            isIncognito = isIncognito,
            isThermalThrottled = isThermalThrottled
        )
        return OfflineRoutePlanner.resolveRoute(input)
    }

    fun resolveReadiness(
        settings: AppSettings,
        registrySnapshot: LocalModelRegistrySnapshot = LocalModelRegistrySnapshot(),
        isNetworkAvailable: Boolean = true
    ): ReadinessState {
        val input = buildRouteInput(
            task = AiTask.DICTATE,
            settings = settings,
            registrySnapshot = registrySnapshot,
            isNetworkAvailable = isNetworkAvailable,
            isSecureField = false,
            isIncognito = settings.manualIncognitoMode,
            isThermalThrottled = false
        )
        return OfflineRoutePlanner.resolveReadiness(input)
    }

    fun formatBadgeForDictate(
        settings: AppSettings,
        registrySnapshot: LocalModelRegistrySnapshot = LocalModelRegistrySnapshot(),
        isNetworkAvailable: Boolean = true
    ): RouteBadge {
        return resolvePlan(
            task = AiTask.DICTATE,
            settings = settings,
            registrySnapshot = registrySnapshot,
            isNetworkAvailable = isNetworkAvailable
        ).badge
    }

    internal fun projectCloudAvailability(settings: AppSettings): CloudAvailability {
        val pipelineHint = when (settings.pipelineMode) {
            PipelineMode.DUAL_STAGE -> CloudPipelineHint.DUAL_STAGE
            PipelineMode.MULTIMODAL_DIRECT -> when (settings.multimodalConfig.protocol) {
                MultimodalProtocol.GEMINI_NATIVE -> CloudPipelineHint.MULTIMODAL_GEMINI
                MultimodalProtocol.OPENAI_AUDIO_CHAT -> CloudPipelineHint.MULTIMODAL_OPENAI_AUDIO
            }
        }

        val effectiveStt = resolveEffectiveSttConfig(settings)
        val hasCloudStt = when (effectiveStt.protocol) {
            SttProtocol.OPENAI_HTTP ->
                effectiveStt.baseUrl.isNotBlank() &&
                    (effectiveStt.apiKey.isNotBlank() || AiPipelineEngine.isLocalEndpoint(effectiveStt.baseUrl))
            SttProtocol.ALIYUN_DASHSCOPE_WS,
            SttProtocol.VOLCENGINE_SAUC_WS,
            SttProtocol.GEMINI_LIVE_WS -> effectiveStt.apiKey.isNotBlank()
        }
        val cloudSttSupportsLiveWrite = hasCloudStt && effectiveStt.protocol != SttProtocol.OPENAI_HTTP

        val effectiveLlm = resolveEffectiveLlmConfig(settings)
        val hasCloudLlm = effectiveLlm.baseUrl.isNotBlank() &&
            (effectiveLlm.apiKey.isNotBlank() || AiPipelineEngine.isLocalEndpoint(effectiveLlm.baseUrl))

        val mmCfg = settings.multimodalConfig
        val hasCloudMultimodal = mmCfg.baseUrl.isNotBlank() &&
            (mmCfg.apiKey.isNotBlank() || AiPipelineEngine.isLocalEndpoint(mmCfg.baseUrl))

        return CloudAvailability(
            pipelineHint = pipelineHint,
            hasCloudStt = hasCloudStt,
            cloudSttSupportsLiveWrite = cloudSttSupportsLiveWrite,
            hasCloudLlm = hasCloudLlm,
            llmPolishEnabled = settings.llmConfig.enabled,
            hasCloudMultimodal = hasCloudMultimodal
        )
    }

    private fun resolveEffectiveSttConfig(settings: AppSettings): SttConfig {
        val primary = settings.sttConfig
        if (primary.apiKey.isNotBlank() || AiPipelineEngine.isLocalEndpoint(primary.baseUrl)) {
            return primary
        }
        val isDeepSeekStt = primary.presetId == "deepseek_asr" ||
            primary.presetId == "deepseek" ||
            EndpointResolver.isDeepSeekAsrEndpoint(primary.baseUrl)
        if (isDeepSeekStt) {
            val vaultKey = settings.providerVault[UnifiedProviderCatalog.ID_DEEPSEEK]?.apiKey.orEmpty()
            val llmKey = settings.llmConfig.apiKey.takeIf {
                UnifiedProviderCatalog.inferLlmProviderId(settings.llmConfig) == UnifiedProviderCatalog.ID_DEEPSEEK
            }.orEmpty()
            val resolvedKey = vaultKey.ifBlank { llmKey }
            if (resolvedKey.isNotBlank()) {
                return primary.copy(
                    apiKey = resolvedKey,
                    baseUrl = primary.baseUrl.ifBlank { EndpointResolver.DEFAULT_DEEPSEEK_ASR_URL },
                    model = primary.model.ifBlank { "deepseek-asr" }
                )
            }
        }
        return primary
    }

    private fun resolveEffectiveLlmConfig(settings: AppSettings): LlmConfig {
        val primary = settings.llmConfig
        if (primary.apiKey.isNotBlank() || AiPipelineEngine.isLocalEndpoint(primary.baseUrl)) {
            return primary
        }
        val deepSeekVaultKey = settings.providerVault[UnifiedProviderCatalog.ID_DEEPSEEK]?.apiKey.orEmpty()
        val deepSeekKey = when {
            EndpointResolver.isDeepSeekAsrEndpoint(settings.sttConfig.baseUrl) && settings.sttConfig.apiKey.isNotBlank() ->
                settings.sttConfig.apiKey
            (primary.presetId == "deepseek" || EndpointResolver.isOfficialDeepSeekHost(primary.baseUrl)) && deepSeekVaultKey.isNotBlank() ->
                deepSeekVaultKey
            else -> ""
        }
        if (deepSeekKey.isNotBlank()) {
            val deepSeekBase = settings.providerVault[UnifiedProviderCatalog.ID_DEEPSEEK]?.customBaseUrl
                ?.takeIf { it.isNotBlank() }
                ?.removeSuffix("/tools/asr")
                ?: primary.baseUrl.takeIf { it.isNotBlank() }?.removeSuffix("/tools/asr")
                ?: "https://api.deepseek.com/v1"
            return primary.copy(
                presetId = "deepseek",
                baseUrl = deepSeekBase,
                apiKey = deepSeekKey,
                model = primary.model.ifBlank { "deepseek-chat" }
            )
        }
        val googleVaultKey = settings.providerVault[UnifiedProviderCatalog.ID_GOOGLE]?.apiKey.orEmpty()
        val geminiKey = when {
            settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS && settings.sttConfig.apiKey.isNotBlank() ->
                settings.sttConfig.apiKey
            settings.multimodalConfig.protocol == MultimodalProtocol.GEMINI_NATIVE && settings.multimodalConfig.apiKey.isNotBlank() ->
                settings.multimodalConfig.apiKey
            googleVaultKey.isNotBlank() -> googleVaultKey
            else -> ""
        }
        if (geminiKey.isNotBlank()) {
            val geminiBase = settings.providerVault[UnifiedProviderCatalog.ID_GOOGLE]?.customBaseUrl
                ?.takeIf { it.isNotBlank() }
                ?: settings.sttConfig.baseUrl.takeIf {
                    settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS && it.isNotBlank() && !it.startsWith("wss://", true)
                }
                ?: "https://generativelanguage.googleapis.com/v1beta"
            return primary.copy(
                presetId = "gemini_llm",
                baseUrl = geminiBase,
                apiKey = geminiKey,
                model = "gemini-3.5-flash-lite",
                thinkingMode = ThinkingMode.DISABLED
            )
        }
        return primary
    }
}

object LocalDictionaryPostProcessor {

    private val SENSE_VOICE_TAG_REGEX = Regex("<\\|[^|>]+\\|>")
    private val MULTI_SPACE_REGEX = Regex("[ \\t]{2,}")
    private val VARIANT_SPLIT_REGEX = Regex("[,，;；|\\n]")
    private val LATIN_TOKEN_REGEX = Regex("[A-Za-z][A-Za-z0-9_+.-]{3,}")
    private val NON_CONTENT_CHAR_REGEX = Regex("[\\p{P}\\p{S}\\s]+")

    /**
     * Strips SenseVoice emotion / language / event special tags such as
     * `<|zh|>`, `<|HAPPY|>`, `<|BGM|>`, `<|Speech|>`, `<|woitn|>`.
     */
    fun cleanSenseVoiceOutput(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(SENSE_VOICE_TAG_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    /**
     * Deterministic post-ASR correction using [DictionaryEntry.wrongVariants], followed by
     * high-confidence fuzzy correction for garbled Latin/English ASR tokens (e.g. `SEEPSEK` -> `DeepSeek`).
     */
    fun applyWrongVariantsCorrection(
        text: String,
        dictionary: List<DictionaryEntry>
    ): String {
        if (text.isBlank() || dictionary.isEmpty()) return text
        val replacementByLowerVariant = LinkedHashMap<String, Pair<String, String>>()
        for (entry in dictionary) {
            if (!entry.enabled) continue
            val term = entry.term.trim()
            if (term.isEmpty() || entry.wrongVariants.isBlank()) continue
            val variants = entry.wrongVariants.split(VARIANT_SPLIT_REGEX)
            for (rawVariant in variants) {
                val variant = rawVariant.trim()
                if (variant.isEmpty() || variant.equals(term, ignoreCase = true)) continue
                val key = variant.lowercase()
                if (!replacementByLowerVariant.containsKey(key)) {
                    replacementByLowerVariant[key] = variant to term
                }
            }
        }
        val exactFixed = if (replacementByLowerVariant.isEmpty()) {
            text
        } else {
            val orderedPairs = replacementByLowerVariant.values
                .sortedByDescending { it.first.length }
            val pattern = orderedPairs.joinToString("|") { Regex.escape(it.first) }
            val lookup = orderedPairs.associate { it.first.lowercase() to it.second }
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            regex.replace(text) { matchResult ->
                lookup[matchResult.value.lowercase()] ?: matchResult.value
            }
        }
        return applyFuzzyLatinDictionaryCorrection(exactFixed, dictionary)
    }

    /**
     * Corrects single garbled ASCII/English ASR tokens (length >= 5, e.g. `SEEPSEK` -> `DeepSeek`)
     * when a single enabled ASCII dictionary term matches uniquely with edit distance <= 2
     * and similarity >= 0.70.
     */
    fun applyFuzzyLatinDictionaryCorrection(
        text: String,
        dictionary: List<DictionaryEntry>
    ): String {
        if (text.isBlank() || dictionary.isEmpty()) return text
        val singleWordLatinTerms = dictionary
            .asSequence()
            .filter { it.enabled }
            .map { it.term.trim() }
            .filter { it.length >= 5 && !it.contains(' ') && it.all { ch -> ch.code < 128 } }
            .distinctBy { it.lowercase() }
            .toList()
        if (singleWordLatinTerms.isEmpty()) return text

        return LATIN_TOKEN_REGEX.replace(text) { match ->
            val token = match.value
            if (token.length < 5) return@replace token
            val tokenLower = token.lowercase()
            // Exact case-insensitive hit: normalize casing to dictionary term
            singleWordLatinTerms.firstOrNull { it.equals(token, ignoreCase = true) }?.let { exact ->
                return@replace exact
            }
            var bestTerm: String? = null
            var bestSim = 0.0
            var secondBestSim = 0.0
            for (term in singleWordLatinTerms) {
                val dist = levenshteinDistance(tokenLower, term.lowercase())
                val maxLen = maxOf(tokenLower.length, term.length)
                val sim = 1.0 - (dist.toDouble() / maxLen.toDouble())
                if (dist <= 2 && sim >= 0.70) {
                    if (sim > bestSim) {
                        secondBestSim = bestSim
                        bestSim = sim
                        bestTerm = term
                    } else if (sim > secondBestSim) {
                        secondBestSim = sim
                    }
                }
            }
            // Require clear winner (no ambiguous tie)
            if (bestTerm != null && (bestSim - secondBestSim) >= 0.05) bestTerm else token
        }
    }

    /**
     * Selects ONLY dictionary entries genuinely relevant to [text] (max [maxItems], default 3)
     * formatted in the exact training style `term (常被误识别为：wrongVariants)` so small local
     * SLMs (0.8B/2B) never receive a wall of unrelated terms that can trigger regurgitation hallucinations.
     */
    fun selectRelevantDictionaryLinesForLocalSlm(
        text: String,
        dictionary: List<DictionaryEntry>,
        maxItems: Int = 3
    ): List<String> {
        if (text.isBlank() || dictionary.isEmpty() || maxItems <= 0) return emptyList()
        val textLower = text.lowercase()
        val latinTokens = LATIN_TOKEN_REGEX.findAll(text).map { it.value.lowercase() }.toList()
        val textCjk = text.filter { it.code >= 0x4E00 }

        data class ScoredEntry(val formatted: String, val score: Double)
        val matched = mutableListOf<ScoredEntry>()

        for (entry in dictionary) {
            if (!entry.enabled) continue
            val term = entry.term.trim()
            if (term.isEmpty()) continue
            val termLower = term.lowercase()
            val variants = entry.wrongVariants
                .split(VARIANT_SPLIT_REGEX)
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            var score = 0.0
            if (textLower.contains(termLower)) {
                score = maxOf(score, 1.0)
            }
            for (v in variants) {
                if (textLower.contains(v.lowercase())) {
                    score = maxOf(score, 0.95)
                }
            }
            if (score < 0.9 && latinTokens.isNotEmpty()) {
                val termWords = termLower.split(Regex("\\s+")).filter { it.length >= 4 && it.all { ch -> ch.code < 128 } }
                for (tok in latinTokens) {
                    if (tok.length < 4) continue
                    for (tw in termWords) {
                        val dist = levenshteinDistance(tok, tw)
                        val maxLen = maxOf(tok.length, tw.length)
                        val sim = 1.0 - (dist.toDouble() / maxLen.toDouble())
                        if (dist <= 2 && sim >= 0.60) {
                            score = maxOf(score, sim)
                        }
                    }
                }
            }
            if (score < 0.6 && textCjk.length >= 2) {
                val termCjk = term.filter { it.code >= 0x4E00 }
                if (termCjk.length >= 2) {
                    var bigramHit = false
                    for (i in 0 until termCjk.length - 1) {
                        if (textCjk.contains(termCjk.substring(i, i + 2))) {
                            bigramHit = true
                            break
                        }
                    }
                    if (bigramHit) score = maxOf(score, 0.65)
                }
            }

            if (score >= 0.60) {
                val formatted = if (variants.isNotEmpty()) {
                    "$term (常被误识别为：${variants.joinToString(", ")})"
                } else {
                    term
                }
                matched.add(ScoredEntry(formatted, score))
            }
        }

        return matched
            .sortedByDescending { it.score }
            .distinctBy { it.formatted }
            .take(maxItems)
            .map { it.formatted }
    }

    /**
     * Safety net for small on-device SLMs (0.8B/2B) on DICTATE tasks:
     * detects severe hallucination where the model ignores the spoken transcript and emits
     * unrelated content (e.g. regurgitating dictionary items or unrelated completions).
     * Carefully allows valid self-corrections (e.g. "两点半哦不对三点半开会吧" -> "三点半开会吧")
     * by checking both full-string and second-half character overlap.
     */
    fun isLikelyHallucinatedDictation(
        rawTranscript: String,
        polishedText: String
    ): Boolean {
        val rawClean = NON_CONTENT_CHAR_REGEX.replace(rawTranscript, "").lowercase()
        val polClean = NON_CONTENT_CHAR_REGEX.replace(polishedText, "").lowercase()
        if (rawClean.length < 4 || polClean.isEmpty()) return false

        // Dictation polishing should clean/shorten speech, never balloon its length dramatically
        if (polClean.length > rawClean.length * 2 + 8) {
            return true
        }

        val fullOverlap = charOverlapRatio(rawClean, polClean)
        val tailStart = rawClean.length / 2
        val tailClean = rawClean.substring(tailStart)
        val tailOverlap = if (tailClean.length >= 2) charOverlapRatio(tailClean, polClean) else fullOverlap

        return fullOverlap < 0.30 && tailOverlap < 0.35
    }

    private fun charOverlapRatio(source: String, target: String): Double {
        if (source.isEmpty() || target.isEmpty()) return 0.0
        val targetCounts = HashMap<Char, Int>()
        for (ch in target) {
            targetCounts[ch] = (targetCounts[ch] ?: 0) + 1
        }
        var matched = 0
        for (ch in source) {
            val rem = targetCounts[ch] ?: 0
            if (rem > 0) {
                matched++
                targetCounts[ch] = rem - 1
            }
        }
        return matched.toDouble() / source.length.toDouble()
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            System.arraycopy(curr, 0, prev, 0, prev.size)
        }
        return prev[b.length]
    }
}
