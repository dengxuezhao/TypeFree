package com.typefree.ime.network

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointResolverTest {

    @Test
    fun `resolveSttEndpoint handles standard and custom base URLs`() {
        assertEquals(
            "https://api.siliconflow.cn/v1/audio/transcriptions",
            EndpointResolver.resolveSttEndpoint("https://api.siliconflow.cn/v1")
        )
        assertEquals(
            "https://api.groq.com/openai/v1/audio/transcriptions",
            EndpointResolver.resolveSttEndpoint("https://api.groq.com/openai/v1/")
        )
        assertEquals(
            "http://192.168.1.50:8080/v1/audio/transcriptions",
            EndpointResolver.resolveSttEndpoint("http://192.168.1.50:8080")
        )
        assertEquals(
            "https://custom.proxy.com/custom/audio/transcriptions",
            EndpointResolver.resolveSttEndpoint("https://custom.proxy.com/custom/audio/transcriptions")
        )
        // DeepSeek Official ASR endpoint resolution
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            EndpointResolver.resolveSttEndpoint("https://api.deepseek.com")
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            EndpointResolver.resolveSttEndpoint("https://api.deepseek.com/v1")
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            EndpointResolver.resolveSttEndpoint("https://api.deepseek.com/v1/tools/asr/")
        )
        // Custom reverse proxy ending with /tools/asr preserved as-is
        assertEquals(
            "https://my-deepseek-proxy.example.com/v1/tools/asr",
            EndpointResolver.resolveSttEndpoint("https://my-deepseek-proxy.example.com/v1/tools/asr/")
        )
    }

    @Test
    fun `resolveChatEndpoint supports v1, v3, v4, openai, and bare hosts`() {
        // DeepSeek / OpenAI standard /v1
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            EndpointResolver.resolveChatEndpoint("https://api.deepseek.com/v1/")
        )
        // Bare domain without /v1
        assertEquals(
            "https://my-oneapi.example.com/v1/chat/completions",
            EndpointResolver.resolveChatEndpoint("https://my-oneapi.example.com")
        )
        // Volcengine Doubao /api/v3
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            EndpointResolver.resolveChatEndpoint("https://ark.cn-beijing.volces.com/api/v3")
        )
        // Zhipu GLM /api/paas/v4
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            EndpointResolver.resolveChatEndpoint("https://open.bigmodel.cn/api/paas/v4")
        )
        // Gemini OpenAI compatibility endpoint
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            EndpointResolver.resolveChatEndpoint("https://generativelanguage.googleapis.com/v1beta/openai")
        )
        // Already full endpoint
        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            EndpointResolver.resolveChatEndpoint("http://localhost:11434/v1/chat/completions")
        )
    }

    @Test
    fun `resolveGeminiEndpoint builds native and proxy generateContent URLs`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=AIzaTest",
            EndpointResolver.resolveGeminiEndpoint(
                "https://generativelanguage.googleapis.com/v1beta",
                "gemini-2.5-flash",
                "AIzaTest"
            )
        )
        // Bare reverse proxy host without /v1beta and with models/ prefix
        assertEquals(
            "https://gemini-proxy.worker.dev/v1beta/models/gemini-2.5-pro:generateContent?key=k123",
            EndpointResolver.resolveGeminiEndpoint(
                "https://gemini-proxy.worker.dev/",
                "models/gemini-2.5-pro",
                "k123"
            )
        )
    }

    @Test
    fun `resolveOpenAiModelsEndpoint and Gemini models discovery endpoints handle all providers`() {
        assertEquals(
            "https://my-newapi.example.com/v1/models",
            EndpointResolver.resolveOpenAiModelsEndpoint("https://my-newapi.example.com")
        )
        assertEquals(
            "https://my-newapi.example.com/v1/models",
            EndpointResolver.resolveOpenAiModelsEndpoint("https://my-newapi.example.com/v1/chat/completions")
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/models",
            EndpointResolver.resolveOpenAiModelsEndpoint("https://open.bigmodel.cn/api/paas/v4")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=AIzaKey",
            EndpointResolver.resolveGeminiModelsEndpoint(
                "https://generativelanguage.googleapis.com/v1beta",
                "AIzaKey"
            )
        )
        assertEquals(
            "https://my-newapi.example.com/v1/models",
            EndpointResolver.resolveGatewayOpenAiModelsFallback("https://my-newapi.example.com/v1beta")
        )
    }
}
