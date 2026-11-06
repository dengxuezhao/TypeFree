package com.typefree.ime.network.asr

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.data.SttConfig
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.EndpointResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.UUID

/**
 * Aliyun Bailian (Model Studio / DashScope) real-time WebSocket ASR client.
 *
 * Default endpoint: `wss://dashscope.aliyuncs.com/api-ws/v1/inference`
 * Default model: `paraformer-realtime-v2` (also supports `fun-asr-realtime`, etc.)
 * Auth: Single standard `Authorization: Bearer sk-...` API Key (shared with Bailian Qwen LLMs!).
 */
class DashScopeStreamingAsrClient(
    private val httpClient: OkHttpClient
) : StreamingAsrClient {

    override fun openSession(
        config: SttConfig,
        hotwords: List<String>
    ): StreamingAsrSession {
        val wsUrl = EndpointResolver.resolveDashScopeWsEndpoint(config.baseUrl)
        val apiKey = config.apiKey.trim()
        if (apiKey.isEmpty()) {
            return FailedImmediateSession("请先在「API 配置」中填写阿里云百炼 STT API Key (sk-...)")
        }

        val taskId = UUID.randomUUID().toString().replace("-", "")
        val model = config.model.trim().ifEmpty { DEFAULT_MODEL }

        val req = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("X-DashScope-DataInspection", "enable")
            .build()

        return DashScopeWsSession(
            httpClient = httpClient,
            request = req,
            taskId = taskId,
            model = model,
            language = config.language.trim()
        )
    }

    private class DashScopeWsSession(
        httpClient: OkHttpClient,
        request: Request,
        private val taskId: String,
        private val model: String,
        private val language: String
    ) : StreamingAsrSession {

        private val lock = Any()
        private val _partialText = MutableStateFlow("")
        override val partialText: StateFlow<String> = _partialText.asStateFlow()

        private val finalDeferred = CompletableDeferred<String>()
        private val pendingFrames = ArrayDeque<ByteArray>()
        private val sentenceAggregator = SentenceAggregator()

        private var taskStarted = false
        private var finishRequested = false
        private var finishFrameSent = false
        private var closed = false

        private val webSocket: WebSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val runTaskJson = buildRunTaskJson(
                        taskId = taskId,
                        model = model,
                        language = language
                    )
                    webSocket.send(runTaskJson)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerJson(webSocket, text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val detail = response?.code?.let { "HTTP $it: ${t.message}" }
                        ?: (t.message ?: "网络连接中断")
                    failSession(IOException("百炼流式 ASR 连接失败 ($detail)", t))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val snapshot = synchronized(lock) { sentenceAggregator.buildFullText() }
                    if (!finalDeferred.isCompleted) {
                        if (snapshot.isNotBlank()) {
                            finalDeferred.complete(AiPipelineEngine.cleanRawTranscript(snapshot))
                        } else {
                            finalDeferred.completeExceptionally(
                                IOException("百炼流式连接已关闭 ($code): ${reason.ifBlank { "未返回识别结果" }}")
                            )
                        }
                    }
                }
            }
        )

        private fun handleServerJson(ws: WebSocket, rawJson: String) {
            val parsedEvent = try {
                parseServerEvent(rawJson)
            } catch (e: Exception) {
                return
            }

            when (parsedEvent) {
                is ServerEvent.TaskStarted -> {
                    val framesToFlush = mutableListOf<ByteArray>()
                    var shouldSendFinish = false
                    synchronized(lock) {
                        if (closed) return
                        taskStarted = true
                        while (pendingFrames.isNotEmpty()) {
                            framesToFlush.add(pendingFrames.removeFirst())
                        }
                        if (finishRequested && !finishFrameSent) {
                            finishFrameSent = true
                            shouldSendFinish = true
                        }
                    }
                    for (frame in framesToFlush) {
                        ws.send(frame.toByteString(0, frame.size))
                    }
                    if (shouldSendFinish) {
                        ws.send(buildFinishTaskJson(taskId))
                    }
                }

                is ServerEvent.ResultGenerated -> {
                    val fullNow = synchronized(lock) {
                        sentenceAggregator.update(
                            sentenceId = parsedEvent.sentenceId,
                            beginTime = parsedEvent.beginTime,
                            text = parsedEvent.text,
                            isSentenceEnd = parsedEvent.isSentenceEnd
                        )
                        sentenceAggregator.buildFullText()
                    }
                    _partialText.value = AiPipelineEngine.cleanRawTranscript(fullNow)
                }

                is ServerEvent.TaskFinished -> {
                    val finalTranscript = synchronized(lock) {
                        closed = true
                        sentenceAggregator.buildFullText()
                    }
                    finalDeferred.complete(AiPipelineEngine.cleanRawTranscript(finalTranscript))
                    runCatching { ws.close(1000, "done") }
                }

                is ServerEvent.TaskFailed -> {
                    failSession(
                        IOException("百炼流式 ASR 错误 (${parsedEvent.errorCode}): ${parsedEvent.errorMessage}")
                    )
                    runCatching { ws.close(1000, "error") }
                }

                ServerEvent.Ignored -> Unit
            }
        }

        private fun failSession(error: IOException) {
            synchronized(lock) {
                closed = true
                pendingFrames.clear()
            }
            if (!finalDeferred.isCompleted) {
                finalDeferred.completeExceptionally(error)
            }
        }

        override fun sendPcmFrame(pcm16Le: ByteArray) {
            if (pcm16Le.isEmpty()) return
            var sendDirect = false
            synchronized(lock) {
                if (closed || finishRequested) return
                if (taskStarted) {
                    sendDirect = true
                } else {
                    // Cap pre-handshake buffer to ~12 seconds (384 KB) to avoid unbounded memory
                    while (pendingFrames.size >= 60) {
                        pendingFrames.removeFirst()
                    }
                    pendingFrames.addLast(pcm16Le)
                }
            }
            if (sendDirect) {
                webSocket.send(pcm16Le.toByteString(0, pcm16Le.size))
            }
        }

        override fun resetPrefix() {
            synchronized(lock) {
                if (!taskStarted) {
                    pendingFrames.clear()
                }
                sentenceAggregator.clear()
            }
            _partialText.value = ""
        }

        override suspend fun finishAndAwaitFinal(timeoutMs: Long): Result<String> {
            var sendFinishNow = false
            synchronized(lock) {
                if (!closed && !finishRequested) {
                    finishRequested = true
                    if (taskStarted && !finishFrameSent) {
                        finishFrameSent = true
                        sendFinishNow = true
                    }
                }
            }
            if (sendFinishNow) {
                webSocket.send(buildFinishTaskJson(taskId))
            }

            return try {
                val text = withTimeout(timeoutMs) {
                    finalDeferred.await()
                }
                Result.success(text)
            } catch (e: Throwable) {
                // If server took slightly longer on `task-finished` after last sentence arrived, salvage partial!
                val salvaged = _partialText.value.trim()
                cancel()
                if (salvaged.isNotEmpty() && e !is IOException) {
                    Result.success(salvaged)
                } else {
                    Result.failure(
                        if (e is IOException) e else IOException("百炼流式尾包等待超时，已自动切换兜底", e)
                    )
                }
            }
        }

        override fun cancel() {
            synchronized(lock) {
                closed = true
                pendingFrames.clear()
            }
            if (!finalDeferred.isCompleted) {
                finalDeferred.cancel()
            }
            runCatching { webSocket.cancel() }
        }
    }

    internal class FailedImmediateSession(private val message: String) : StreamingAsrSession {
        override val partialText: StateFlow<String> = MutableStateFlow("")
        override fun sendPcmFrame(pcm16Le: ByteArray) = Unit
        override suspend fun finishAndAwaitFinal(timeoutMs: Long): Result<String> =
            Result.failure(IllegalStateException(message))
        override fun cancel() = Unit
    }

    /**
     * Multi-sentence state accumulator for DashScope `result-generated` events.
     * DashScope sends each utterance separately keyed by `sentence_id` (or `begin_time`),
     * updating its text until `sentence_end == true` / `end_time != null` before starting the next sentence.
     */
    internal class SentenceAggregator {
        private val segments = LinkedHashMap<Int, String>()
        private var fallbackKeyCounter = 1
        private var activeFallbackKeyClosed = false

        fun update(
            sentenceId: Int?,
            beginTime: Int?,
            text: String,
            isSentenceEnd: Boolean
        ) {
            val key = when {
                sentenceId != null -> sentenceId
                beginTime != null -> beginTime
                else -> {
                    if (activeFallbackKeyClosed) {
                        fallbackKeyCounter += 1
                        activeFallbackKeyClosed = false
                    }
                    fallbackKeyCounter
                }
            }
            segments[key] = text
            if (isSentenceEnd) {
                activeFallbackKeyClosed = true
            }
        }

        fun buildFullText(): String {
            return segments.values.joinToString("").trim()
        }

        fun clear() {
            segments.clear()
            fallbackKeyCounter = 1
            activeFallbackKeyClosed = false
        }
    }

    internal sealed interface ServerEvent {
        data object TaskStarted : ServerEvent
        data class ResultGenerated(
            val sentenceId: Int?,
            val beginTime: Int?,
            val text: String,
            val isSentenceEnd: Boolean
        ) : ServerEvent
        data object TaskFinished : ServerEvent
        data class TaskFailed(val errorCode: String, val errorMessage: String) : ServerEvent
        data object Ignored : ServerEvent
    }

    companion object {
        const val DEFAULT_MODEL = "paraformer-realtime-v2"

        fun buildRunTaskJson(
            taskId: String,
            model: String = DEFAULT_MODEL,
            language: String = "",
            sampleRate: Int = WavAudioRecorder.SAMPLE_RATE
        ): String {
            val header = JsonObject().apply {
                addProperty("action", "run-task")
                addProperty("task_id", taskId)
                addProperty("streaming", "duplex")
            }
            val params = JsonObject().apply {
                addProperty("format", "pcm")
                addProperty("sample_rate", sampleRate)
                addProperty("punctuation_prediction_enabled", true)
                addProperty("inverse_text_normalization_enabled", true)
                addProperty("disfluency_removal_enabled", false)
                if (language.isNotBlank()) {
                    add("language_hints", JsonArray().apply { add(language.trim()) })
                }
            }
            val payload = JsonObject().apply {
                addProperty("task_group", "audio")
                addProperty("task", "asr")
                addProperty("function", "recognition")
                addProperty("model", model.ifBlank { DEFAULT_MODEL })
                add("parameters", params)
                add("input", JsonObject())
            }
            return JsonObject().apply {
                add("header", header)
                add("payload", payload)
            }.toString()
        }

        fun buildFinishTaskJson(taskId: String): String {
            val header = JsonObject().apply {
                addProperty("action", "finish-task")
                addProperty("task_id", taskId)
                addProperty("streaming", "duplex")
            }
            val payload = JsonObject().apply {
                add("input", JsonObject())
            }
            return JsonObject().apply {
                add("header", header)
                add("payload", payload)
            }.toString()
        }

        internal fun parseServerEvent(rawJson: String): ServerEvent {
            val root = JsonParser.parseString(rawJson).asJsonObject
            val header = root.getAsJsonObject("header") ?: return ServerEvent.Ignored
            val event = header.get("event")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

            return when (event) {
                "task-started" -> ServerEvent.TaskStarted
                "result-generated" -> {
                    val sentence = root.getAsJsonObject("payload")
                        ?.getAsJsonObject("output")
                        ?.getAsJsonObject("sentence")
                        ?: return ServerEvent.Ignored
                    val text = sentence.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    val sentenceId = sentence.get("sentence_id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                    val beginTime = sentence.get("begin_time")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                    val explicitEnd = sentence.get("sentence_end")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                    val endTimePresent = sentence.get("end_time")?.let { !it.isJsonNull } ?: false
                    val stash = sentence.get("stash")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                    val isEnd = explicitEnd ?: (endTimePresent && stash != true)
                    ServerEvent.ResultGenerated(
                        sentenceId = sentenceId,
                        beginTime = beginTime,
                        text = text,
                        isSentenceEnd = isEnd
                    )
                }
                "task-finished" -> ServerEvent.TaskFinished
                "task-failed" -> {
                    val code = header.get("error_code")?.takeIf { it.isJsonPrimitive }?.asString ?: "UNKNOWN"
                    val msg = header.get("error_message")?.takeIf { it.isJsonPrimitive }?.asString ?: "流式识别任务失败"
                    ServerEvent.TaskFailed(code, msg)
                }
                else -> ServerEvent.Ignored
            }
        }
    }
}
