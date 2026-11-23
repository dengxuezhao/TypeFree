package com.typefree.ime.localai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.typefree.core.offline.LocalModelSlot
import com.typefree.ime.network.asr.LiveTranscript
import com.typefree.ime.network.asr.StreamingAsrSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal runtime bridge consumed by [LocalModelRegistry] so registry unit tests can inject a fake
 * runtime without requiring Android Binder / ServiceConnection.
 */
interface LocalModelRuntimeBridge {
    val isServiceAlive: StateFlow<Boolean>
    suspend fun unloadSlot(slot: LocalModelSlot)
    suspend fun unloadAll()
    suspend fun probeModel(slot: LocalModelSlot, modelId: String, dirPath: String): Result<Unit>
}

/**
 * Main-process Binder client communicating with `:local_ai`'s [LocalAiInferenceService].
 *
 * Key responsibilities:
 * - [preBind] called in `onStartInputView` to hide `:local_ai` process fork & `.so` loading latency.
 * - [IBinder.DeathRecipient] watches for `lmkd` `SIGKILL` of `:local_ai`, flips [isServiceAlive]
 *   to `false`, notifies listeners (e.g. [LocalModelRegistry]), and fails in-flight requests cleanly.
 * - [openStreamingAsrSession] implements [StreamingAsrSession] and streams 16kHz PCM through
 *   `ParcelFileDescriptor.createPipe()` (avoiding the 1MB Binder buffer limit).
 * - [transcribeFile], [generate], [cancel], [probeModel], [unloadSlot], [unloadAll], [getRuntimeMetrics].
 */
class LocalAiServiceClient private constructor(
    private val appContext: Context?,
    private val injectedServiceProvider: (() -> ILocalAiService?)? = null,
    private val pipeOpener: () -> PipeHandle = ::openAndroidPipe
) : LocalModelRuntimeBridge {

    constructor(context: Context) : this(
        appContext = context.applicationContext,
        injectedServiceProvider = null,
        pipeOpener = ::openAndroidPipe
    )

    internal interface PipeHandle {
        val readPfd: ParcelFileDescriptor?
        val writeStream: OutputStream
        fun closeLocalReadEnd()
        fun closeWriteEnd()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestIdGen = AtomicLong(1L)
    private val bindLock = Any()

    @Volatile
    private var serviceRef: ILocalAiService? = null

    @Volatile
    private var binderRef: IBinder? = null

    @Volatile
    private var bindRequested: Boolean = false

    @Volatile
    private var connectDeferred: CompletableDeferred<ILocalAiService?>? = null

    private val _isServiceAlive = MutableStateFlow(false)
    override val isServiceAlive: StateFlow<Boolean> = _isServiceAlive.asStateFlow()

    private val _serviceDeathCount = MutableStateFlow(0)
    val serviceDeathCount: StateFlow<Int> = _serviceDeathCount.asStateFlow()

    private val deathListeners = CopyOnWriteArrayList<() -> Unit>()
    private val activeDeathAborters = CopyOnWriteArrayList<(String) -> Unit>()

    private val deathRecipient = IBinder.DeathRecipient {
        handleBinderDied("Local AI process terminated (SIGKILL/lmkd)")
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                completeConnect(null)
                return
            }
            val iface = ILocalAiService.Stub.asInterface(binder)
            runCatching { binder.linkToDeath(deathRecipient, 0) }
            synchronized(bindLock) {
                binderRef = binder
                serviceRef = iface
            }
            _isServiceAlive.value = true
            LocalAiLog.i("Connected to LocalAiInferenceService")
            completeConnect(iface)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleBinderDied("LocalAiInferenceService disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            handleBinderDied("LocalAiInferenceService binding died", unbindDeadConnection = true)
        }
    }

    fun addDeathListener(listener: () -> Unit) {
        deathListeners.add(listener)
    }

    fun removeDeathListener(listener: () -> Unit) {
        deathListeners.remove(listener)
    }

    /**
     * Proactively binds `:local_ai` when the keyboard opens (`onStartInputView`) so process startup
     * finishes before the user presses the microphone button.
     */
    fun preBind() {
        if (injectedServiceProvider != null) {
            val svc = injectedServiceProvider.invoke()
            if (svc != null) {
                serviceRef = svc
                _isServiceAlive.value = true
            }
            return
        }
        val ctx = appContext ?: return
        synchronized(bindLock) {
            if (serviceRef != null && binderRef?.isBinderAlive == true) {
                _isServiceAlive.value = true
                return
            }
            if (bindRequested && connectDeferred != null) return
            val deferred = CompletableDeferred<ILocalAiService?>()
            connectDeferred = deferred
            bindRequested = true
            val intent = Intent(ctx, LocalAiInferenceService::class.java)
            val ok = runCatching {
                // BIND_IMPORTANT keeps :local_ai in the client's foreground scheduling class
                // (preventing MIUI/OEM SmartPower from pinning inference to LITTLE cores at adj=1001),
                // while intentionally omitting BIND_ABOVE_CLIENT so LMKD kills :local_ai before the IME.
                ctx.bindService(
                    intent,
                    serviceConnection,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                )
            }.getOrDefault(false)
            if (!ok) {
                bindRequested = false
                connectDeferred = null
                _isServiceAlive.value = false
                deferred.complete(null)
                LocalAiLog.w("bindService(LocalAiInferenceService) returned false")
            }
        }
    }

    fun unbind() {
        val ctx = appContext ?: return
        synchronized(bindLock) {
            runCatching { binderRef?.unlinkToDeath(deathRecipient, 0) }
            if (bindRequested) {
                runCatching { ctx.unbindService(serviceConnection) }
            }
            bindRequested = false
            binderRef = null
            serviceRef = null
            connectDeferred?.complete(null)
            connectDeferred = null
        }
        _isServiceAlive.value = false
    }

    fun prewarmSlot(slot: LocalModelSlot, modelId: String, dirPath: String) {
        preBind()
        scope.launch {
            val svc = ensureConnected(DEFAULT_BIND_TIMEOUT_MS) ?: return@launch
            runBinderCall { svc.prewarmSlot(slot.toWireSlot(), modelId, dirPath) }
        }
    }

    /**
     * Opens a streaming offline ASR session implementing [StreamingAsrSession].
     * Streams 16kHz 16-bit mono PCM via `ParcelFileDescriptor.createPipe()` so large recordings
     * never hit Binder's 1MB transaction limit.
     */
    fun openStreamingAsrSession(
        modelId: String,
        modelDirPath: String,
        hotwords: List<String> = emptyList(),
        languageHint: String = "zh",
        incognito: Boolean = false,
        expectedGeneration: Long = 0L
    ): StreamingAsrSession {
        preBind()
        val sid = requestIdGen.getAndIncrement()
        val params = AsrParams(
            modelId = modelId,
            modelDirPath = modelDirPath,
            languageHint = languageHint,
            hotwords = hotwords,
            sampleRateHz = 16_000,
            incognito = incognito,
            expectedGeneration = expectedGeneration,
            priority = PRIORITY_VOICE
        )
        return LocalPipeStreamingAsrSession(sid = sid, params = params)
    }

    suspend fun transcribeFile(
        wavPath: String,
        modelId: String,
        modelDirPath: String,
        hotwords: List<String> = emptyList(),
        languageHint: String = "zh",
        incognito: Boolean = false,
        expectedGeneration: Long = 0L,
        timeoutMs: Long = 30_000L
    ): Result<String> = withContext(Dispatchers.IO) {
        val svc = ensureConnected(DEFAULT_BIND_TIMEOUT_MS)
            ?: return@withContext Result.failure(IOException("离线推理服务未连接"))
        val rid = requestIdGen.getAndIncrement()
        val resultDeferred = CompletableDeferred<Result<String>>()
        val aborter: (String) -> Unit = { reason ->
            resultDeferred.complete(Result.failure(IOException(reason)))
        }
        activeDeathAborters.add(aborter)

        val callback = buildAsrCallback(
            onPartialCb = { _, _, _, _ -> Unit },
            onFinalCb = { _, fullText, generation, _ ->
                if (expectedGeneration > 0L && generation != expectedGeneration) {
                    resultDeferred.complete(Result.failure(IOException("模型已切换，丢弃过期识别结果")))
                    return@buildAsrCallback
                }
                resultDeferred.complete(Result.success(fullText.orEmpty()))
            },
            onErrorCb = { _, errorCode, message, _ ->
                resultDeferred.complete(Result.failure(IOException(message ?: "离线音频文件识别失败 ($errorCode)")))
            }
        )

        try {
            val params = AsrParams(
                modelId = modelId,
                modelDirPath = modelDirPath,
                languageHint = languageHint,
                hotwords = hotwords,
                incognito = incognito,
                expectedGeneration = expectedGeneration,
                priority = PRIORITY_VOICE
            )
            val sent = runBinderCall { svc.transcribeFile(rid, wavPath, params, callback) }
            if (sent.isFailure) {
                return@withContext Result.failure(sent.exceptionOrNull()!!)
            }
            withTimeoutOrNull(timeoutMs) {
                resultDeferred.await()
            } ?: run {
                cancel(rid)
                Result.failure(IOException("离线音频文件识别超时"))
            }
        } catch (ce: CancellationException) {
            cancel(rid)
            throw ce
        } finally {
            activeDeathAborters.remove(aborter)
        }
    }

    suspend fun generate(
        request: GenRequest,
        onTokenBatch: ((chunk: String, tokenCount: Int) -> Unit)? = null,
        timeoutMs: Long = 30_000L
    ): Result<LocalSlmRuntimeResult> = withContext(Dispatchers.IO) {
        val svc = ensureConnected(DEFAULT_BIND_TIMEOUT_MS)
            ?: return@withContext Result.failure(IOException("离线推理服务未连接"))
        val rid = requestIdGen.getAndIncrement()
        val resultDeferred = CompletableDeferred<Result<LocalSlmRuntimeResult>>()
        val aborter: (String) -> Unit = { reason ->
            resultDeferred.complete(Result.failure(IOException(reason)))
        }
        activeDeathAborters.add(aborter)

        val callback = buildTokenCallback(
            onTokenBatchCb = { _, tokenChunk, tokenCount, generation ->
                if (request.expectedGeneration > 0L && generation != request.expectedGeneration) return@buildTokenCallback
                if (!tokenChunk.isNullOrEmpty()) {
                    onTokenBatch?.invoke(tokenChunk, tokenCount)
                }
            },
            onCompletedCb = { _, fullText, generation, ttftMs, totalMs, totalTokens ->
                if (request.expectedGeneration > 0L && generation != request.expectedGeneration) {
                    resultDeferred.complete(Result.failure(IOException("模型已切换，丢弃过期生成结果")))
                    return@buildTokenCallback
                }
                resultDeferred.complete(
                    Result.success(
                        LocalSlmRuntimeResult(
                            text = fullText.orEmpty(),
                            ttftMs = ttftMs,
                            totalMs = totalMs,
                            totalTokens = totalTokens
                        )
                    )
                )
            },
            onErrorCb = { _, errorCode, message, _ ->
                resultDeferred.complete(Result.failure(IOException(message ?: "离线推理失败 ($errorCode)")))
            }
        )

        try {
            val sent = runBinderCall { svc.generate(rid, request, callback) }
            if (sent.isFailure) {
                return@withContext Result.failure(sent.exceptionOrNull()!!)
            }
            withTimeoutOrNull(timeoutMs) {
                resultDeferred.await()
            } ?: run {
                cancel(rid)
                Result.failure(IOException("离线文本生成超时"))
            }
        } catch (ce: CancellationException) {
            cancel(rid)
            throw ce
        } finally {
            activeDeathAborters.remove(aborter)
        }
    }

    fun cancel(requestId: Long) {
        val svc = serviceRef ?: return
        runBinderCall { svc.cancel(requestId) }
    }

    override suspend fun probeModel(
        slot: LocalModelSlot,
        modelId: String,
        dirPath: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val svc = ensureConnected(DEFAULT_BIND_TIMEOUT_MS)
            ?: return@withContext Result.failure(IOException("离线推理服务无法连接"))
        val callRes = runBinderCall { svc.probeModel(slot.toWireSlot(), modelId, dirPath) }
        callRes.fold(
            onSuccess = { errMsg ->
                if (errMsg.isNullOrBlank()) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException(errMsg))
                }
            },
            onFailure = { err ->
                Result.failure(err)
            }
        )
    }

    override suspend fun unloadSlot(slot: LocalModelSlot) {
        val svc = serviceRef ?: return
        runBinderCall { svc.unloadSlot(slot.toWireSlot()) }
    }

    override suspend fun unloadAll() {
        val svc = serviceRef ?: return
        runBinderCall { svc.unloadAll() }
    }

    suspend fun getRuntimeMetrics(): RuntimeMetrics? = withContext(Dispatchers.IO) {
        val svc = ensureConnected(DEFAULT_BIND_TIMEOUT_MS) ?: return@withContext null
        runBinderCall { svc.runtimeMetrics }.getOrNull()
    }

    internal fun simulateBinderDeathForTesting(reason: String = "Simulated SIGKILL") {
        handleBinderDied(reason)
    }

    private fun buildAsrCallback(
        onPartialCb: (Long, String?, String?, Long) -> Unit,
        onFinalCb: (Long, String?, Long, Long) -> Unit,
        onErrorCb: (Long, Int, String?, Long) -> Unit
    ): IAsrCallback {
        if (injectedServiceProvider != null) {
            return object : IAsrCallback.Default() {
                override fun onPartial(sid: Long, finalizedText: String?, interimText: String?, generation: Long) {
                    onPartialCb(sid, finalizedText, interimText, generation)
                }

                override fun onFinal(sid: Long, fullText: String?, generation: Long, latencyMs: Long) {
                    onFinalCb(sid, fullText, generation, latencyMs)
                }

                override fun onError(sid: Long, errorCode: Int, message: String?, generation: Long) {
                    onErrorCb(sid, errorCode, message, generation)
                }
            }
        }
        return object : IAsrCallback.Stub() {
            override fun onPartial(sid: Long, finalizedText: String?, interimText: String?, generation: Long) {
                onPartialCb(sid, finalizedText, interimText, generation)
            }

            override fun onFinal(sid: Long, fullText: String?, generation: Long, latencyMs: Long) {
                onFinalCb(sid, fullText, generation, latencyMs)
            }

            override fun onError(sid: Long, errorCode: Int, message: String?, generation: Long) {
                onErrorCb(sid, errorCode, message, generation)
            }
        }
    }

    private fun buildTokenCallback(
        onTokenBatchCb: (Long, String?, Int, Long) -> Unit,
        onCompletedCb: (Long, String?, Long, Long, Long, Int) -> Unit,
        onErrorCb: (Long, Int, String?, Long) -> Unit
    ): ITokenCallback {
        if (injectedServiceProvider != null) {
            return object : ITokenCallback.Default() {
                override fun onTokenBatch(rid: Long, tokenChunk: String?, tokenCount: Int, generation: Long) {
                    onTokenBatchCb(rid, tokenChunk, tokenCount, generation)
                }

                override fun onCompleted(
                    rid: Long,
                    fullText: String?,
                    generation: Long,
                    ttftMs: Long,
                    totalMs: Long,
                    totalTokens: Int
                ) {
                    onCompletedCb(rid, fullText, generation, ttftMs, totalMs, totalTokens)
                }

                override fun onError(rid: Long, errorCode: Int, message: String?, generation: Long) {
                    onErrorCb(rid, errorCode, message, generation)
                }
            }
        }
        return object : ITokenCallback.Stub() {
            override fun onTokenBatch(rid: Long, tokenChunk: String?, tokenCount: Int, generation: Long) {
                onTokenBatchCb(rid, tokenChunk, tokenCount, generation)
            }

            override fun onCompleted(
                rid: Long,
                fullText: String?,
                generation: Long,
                ttftMs: Long,
                totalMs: Long,
                totalTokens: Int
            ) {
                onCompletedCb(rid, fullText, generation, ttftMs, totalMs, totalTokens)
            }

            override fun onError(rid: Long, errorCode: Int, message: String?, generation: Long) {
                onErrorCb(rid, errorCode, message, generation)
            }
        }
    }

    /**
     * Ensures `:local_ai` is bound and alive, automatically re-binding if the remote process was
     * previously killed by LMKD or crashed.
     */
    suspend fun ensureAlive(timeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS): Boolean {
        if (isServiceAlive.value && serviceRef != null && binderRef?.isBinderAlive == true) {
            return true
        }
        return ensureConnected(timeoutMs) != null
    }

    private suspend fun ensureConnected(timeoutMs: Long): ILocalAiService? {
        injectedServiceProvider?.let { provider ->
            val svc = provider.invoke()
            serviceRef = svc
            _isServiceAlive.value = svc != null
            return svc
        }
        val existing = serviceRef
        if (existing != null && binderRef?.isBinderAlive == true) {
            return existing
        }
        preBind()
        val deferred = synchronized(bindLock) { connectDeferred } ?: return serviceRef
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: serviceRef
    }

    private fun completeConnect(service: ILocalAiService?) {
        val pending = synchronized(bindLock) {
            val d = connectDeferred
            connectDeferred = null
            d
        }
        pending?.complete(service)
    }

    private fun handleBinderDied(reason: String, unbindDeadConnection: Boolean = false) {
        LocalAiLog.w("LocalAiServiceClient binder death: $reason")
        val pending: CompletableDeferred<ILocalAiService?>?
        synchronized(bindLock) {
            runCatching { binderRef?.unlinkToDeath(deathRecipient, 0) }
            if (unbindDeadConnection) {
                runCatching { appContext?.unbindService(serviceConnection) }
            }
            binderRef = null
            serviceRef = null
            bindRequested = false
            pending = connectDeferred
            connectDeferred = null
        }
        _isServiceAlive.value = false
        _serviceDeathCount.value = _serviceDeathCount.value + 1
        pending?.complete(null)

        for (aborter in activeDeathAborters) {
            runCatching { aborter.invoke(reason) }
        }
        for (listener in deathListeners) {
            runCatching { listener.invoke() }
        }
    }

    private inline fun <T> runBinderCall(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (dead: DeadObjectException) {
            handleBinderDied("Binder DeadObjectException (possible OOM SIGKILL)")
            Result.failure(IOException("离线推理进程已意外终止", dead))
        } catch (remote: RemoteException) {
            handleBinderDied("Binder RemoteException: ${remote.message}")
            Result.failure(IOException("离线推理跨进程通信失败", remote))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private inner class LocalPipeStreamingAsrSession(
        private val sid: Long,
        private val params: AsrParams
    ) : StreamingAsrSession {

        private val _partialText = MutableStateFlow("")
        override val partialText: StateFlow<String> = _partialText.asStateFlow()

        private val _transcript = MutableStateFlow(LiveTranscript())
        override val transcript: StateFlow<LiveTranscript> = _transcript.asStateFlow()

        private val revisionCounter = AtomicLong(0L)
        private val cancelled = AtomicBoolean(false)
        private val finishing = AtomicBoolean(false)
        private val finalDeferred = CompletableDeferred<Result<String>>()

        // Bounded queue buffers PCM frames while bindService finishes (prevents first-word loss)
        private val pcmQueue = LinkedBlockingQueue<ByteArray>(MAX_BUFFERED_PCM_FRAMES)
        private val eofSentinel = ByteArray(0)

        @Volatile
        private var resetBaselineFinalizedLength: Int = 0

        @Volatile
        private var pipeHandle: PipeHandle? = null

        private val deathAborter: (String) -> Unit = { reason ->
            if (!finalDeferred.isCompleted) {
                finalDeferred.complete(Result.failure(IOException(reason)))
            }
            runCatching { pipeHandle?.closeWriteEnd() }
        }

        private val asrCallback = buildAsrCallback(
            onPartialCb = { _, finalizedText, interimText, generation ->
                if (cancelled.get()) return@buildAsrCallback
                if (params.expectedGeneration > 0L && generation != params.expectedGeneration) return@buildAsrCallback

                val rawFinalized = finalizedText.orEmpty()
                val trimmedFinalized = if (resetBaselineFinalizedLength in 1..rawFinalized.length) {
                    rawFinalized.substring(resetBaselineFinalizedLength)
                } else {
                    rawFinalized
                }
                val interim = interimText.orEmpty()
                val next = LiveTranscript(
                    finalized = trimmedFinalized,
                    interim = interim,
                    revision = revisionCounter.incrementAndGet()
                )
                _transcript.value = next
                _partialText.value = next.fullText
            },
            onFinalCb = { _, fullText, generation, _ ->
                if (cancelled.get()) return@buildAsrCallback
                if (params.expectedGeneration > 0L && generation != params.expectedGeneration) {
                    finalDeferred.complete(Result.failure(IOException("模型已切换，丢弃过期语音结果")))
                    return@buildAsrCallback
                }
                val rawFull = fullText.orEmpty()
                val effective = if (resetBaselineFinalizedLength in 1..rawFull.length) {
                    rawFull.substring(resetBaselineFinalizedLength).trim()
                } else {
                    rawFull.trim()
                }
                val next = LiveTranscript(
                    finalized = effective,
                    interim = "",
                    revision = revisionCounter.incrementAndGet()
                )
                _transcript.value = next
                _partialText.value = effective
                finalDeferred.complete(Result.success(effective))
            },
            onErrorCb = { _, errorCode, message, _ ->
                if (!finalDeferred.isCompleted) {
                    finalDeferred.complete(
                        Result.failure(IOException(message ?: "离线流式语音识别失败 ($errorCode)"))
                    )
                }
            }
        )

        init {
            activeDeathAborters.add(deathAborter)
            scope.launch {
                runPipeWriterLoop()
            }
        }

        private suspend fun runPipeWriterLoop() {
            var localPipe: PipeHandle? = null
            try {
                val svc = ensureConnected(DEFAULT_BIND_TIMEOUT_MS)
                if (svc == null) {
                    finalDeferred.complete(Result.failure(IOException("离线语音推理服务未就绪")))
                    return
                }
                if (cancelled.get()) {
                    finalDeferred.complete(Result.failure(IOException("ASR session cancelled")))
                    return
                }

                localPipe = pipeOpener()
                pipeHandle = localPipe

                val openRes = runBinderCall {
                    svc.openAsrSession(sid, localPipe.readPfd, params, asrCallback)
                }
                // Close parent process copy of read FD immediately after Binder duplicates it
                localPipe.closeLocalReadEnd()
                if (openRes.isFailure) {
                    finalDeferred.complete(Result.failure(openRes.exceptionOrNull()!!))
                    return
                }

                val out = localPipe.writeStream
                while (!cancelled.get()) {
                    val frame = pcmQueue.take()
                    if (frame === eofSentinel) break
                    if (frame.isNotEmpty()) {
                        out.write(frame)
                    }
                }
                runCatching { out.flush() }
            } catch (t: Throwable) {
                if (!cancelled.get() && !finalDeferred.isCompleted) {
                    finalDeferred.complete(Result.failure(IOException("PCM 管道写入失败", t)))
                }
            } finally {
                runCatching { localPipe?.closeWriteEnd() }
            }
        }

        override fun sendPcmFrame(pcm16Le: ByteArray) {
            if (pcm16Le.isEmpty() || cancelled.get() || finishing.get()) return
            val copy = pcm16Le.copyOf()
            if (!pcmQueue.offer(copy)) {
                pcmQueue.poll()
                pcmQueue.offer(copy)
            }
        }

        override fun resetPrefix() {
            resetBaselineFinalizedLength += _transcript.value.finalized.length
            val next = LiveTranscript(
                finalized = "",
                interim = "",
                revision = revisionCounter.incrementAndGet()
            )
            _transcript.value = next
            _partialText.value = ""
        }

        override suspend fun finishAndAwaitFinal(timeoutMs: Long): Result<String> {
            if (cancelled.get()) {
                activeDeathAborters.remove(deathAborter)
                return Result.failure(IOException("ASR session cancelled"))
            }
            if (finishing.compareAndSet(false, true)) {
                if (!pcmQueue.offer(eofSentinel)) {
                    pcmQueue.clear()
                    pcmQueue.offer(eofSentinel)
                }
            }
            return try {
                withTimeoutOrNull(timeoutMs) {
                    finalDeferred.await()
                } ?: run {
                    cancel()
                    val fallback = _transcript.value.fullText.trim()
                    if (fallback.isNotEmpty()) {
                        Result.success(fallback)
                    } else {
                        Result.failure(IOException("离线语音识别等待最终结果超时"))
                    }
                }
            } finally {
                activeDeathAborters.remove(deathAborter)
            }
        }

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            activeDeathAborters.remove(deathAborter)
            pcmQueue.clear()
            pcmQueue.offer(eofSentinel)
            runCatching { pipeHandle?.closeWriteEnd() }
            this@LocalAiServiceClient.cancel(sid)
            if (!finalDeferred.isCompleted) {
                finalDeferred.complete(Result.failure(IOException("ASR session cancelled")))
            }
        }
    }

    companion object {
        const val DEFAULT_BIND_TIMEOUT_MS: Long = 3_000L
        private const val MAX_BUFFERED_PCM_FRAMES: Int = 256

        internal fun openAndroidPipe(): PipeHandle {
            val pair = ParcelFileDescriptor.createPipe()
            val readFd = pair[0]
            val writeFd = pair[1]
            val out = ParcelFileDescriptor.AutoCloseOutputStream(writeFd)
            return object : PipeHandle {
                override val readPfd: ParcelFileDescriptor = readFd
                override val writeStream: OutputStream = out
                override fun closeLocalReadEnd() {
                    runCatching { readFd.close() }
                }

                override fun closeWriteEnd() {
                    runCatching { out.close() }
                }
            }
        }

        internal fun createForTesting(
            serviceProvider: () -> ILocalAiService?,
            pipeOpener: () -> PipeHandle = ::openAndroidPipe
        ): LocalAiServiceClient {
            return LocalAiServiceClient(
                appContext = null,
                injectedServiceProvider = serviceProvider,
                pipeOpener = pipeOpener
            )
        }
    }
}
