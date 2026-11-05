package com.typefree.ime.network

object EndpointResolver {

    private val versionedSuffixes = listOf("/v1", "/v2", "/v3", "/v4", "/openai", "/v1beta/openai")

    fun resolveSttEndpoint(rawBaseUrl: String): String {
        val base = rawBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return "https://api.openai.com/v1/audio/transcriptions"
        if (base.endsWith("/audio/transcriptions")) return base
        val hasVersionPrefix = versionedSuffixes.any { base.endsWith(it, ignoreCase = true) }
        return if (hasVersionPrefix) {
            "$base/audio/transcriptions"
        } else {
            "$base/v1/audio/transcriptions"
        }
    }

    fun resolveChatEndpoint(rawBaseUrl: String): String {
        val base = rawBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return "https://api.openai.com/v1/chat/completions"
        if (base.endsWith("/chat/completions")) return base
        val hasVersionPrefix = versionedSuffixes.any { base.endsWith(it, ignoreCase = true) }
        return if (hasVersionPrefix) {
            "$base/chat/completions"
        } else {
            "$base/v1/chat/completions"
        }
    }

    fun resolveGeminiEndpoint(rawBaseUrl: String, model: String, apiKey: String): String {
        val base = rawBaseUrl.trim().trimEnd('/').ifEmpty {
            "https://generativelanguage.googleapis.com/v1beta"
        }
        val cleanModel = model.trim().removePrefix("models/").ifEmpty { "gemini-2.5-flash" }
        val urlWithoutQuery = when {
            base.contains(":generateContent") -> base
            base.endsWith("/v1beta") || base.endsWith("/v1") ->
                "$base/models/$cleanModel:generateContent"
            else ->
                "$base/v1beta/models/$cleanModel:generateContent"
        }
        return if (urlWithoutQuery.contains("key=")) {
            urlWithoutQuery
        } else {
            val sep = if (urlWithoutQuery.contains("?")) "&" else "?"
            "$urlWithoutQuery${sep}key=${apiKey.trim()}"
        }
    }

    fun resolveOpenAiModelsEndpoint(rawBaseUrl: String): String {
        var base = rawBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return "https://api.openai.com/v1/models"
        base = base
            .removeSuffix("/chat/completions")
            .removeSuffix("/audio/transcriptions")
            .removeSuffix("/completions")
            .trimEnd('/')
        if (base.endsWith("/models", ignoreCase = true)) return base
        val hasVersionPrefix = versionedSuffixes.any { base.endsWith(it, ignoreCase = true) }
        return if (hasVersionPrefix) {
            "$base/models"
        } else {
            "$base/v1/models"
        }
    }

    fun resolveGeminiModelsEndpoint(rawBaseUrl: String, apiKey: String): String {
        var base = rawBaseUrl.trim().trimEnd('/').ifEmpty {
            "https://generativelanguage.googleapis.com/v1beta"
        }
        val modelsSegmentIdx = base.indexOf("/models/")
        if (modelsSegmentIdx >= 0) {
            base = base.substring(0, modelsSegmentIdx)
        }
        val urlWithoutQuery = when {
            base.endsWith("/models", ignoreCase = true) -> base
            base.endsWith("/v1beta", ignoreCase = true) || base.endsWith("/v1", ignoreCase = true) ->
                "$base/models"
            else ->
                "$base/v1beta/models"
        }
        val params = mutableListOf("pageSize=200")
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isNotEmpty() && !urlWithoutQuery.contains("key=")) {
            params.add("key=$trimmedKey")
        }
        val sep = if (urlWithoutQuery.contains("?")) "&" else "?"
        return "$urlWithoutQuery$sep${params.joinToString("&")}"
    }

    fun resolveGatewayOpenAiModelsFallback(rawBaseUrl: String): String {
        var base = rawBaseUrl.trim().trimEnd('/')
        val modelsSegmentIdx = base.indexOf("/models")
        if (modelsSegmentIdx >= 0) {
            base = base.substring(0, modelsSegmentIdx)
        }
        base = base
            .removeSuffix("/v1beta")
            .removeSuffix("/v1")
            .trimEnd('/')
        return if (base.isEmpty()) {
            "https://api.openai.com/v1/models"
        } else {
            "$base/v1/models"
        }
    }
}
