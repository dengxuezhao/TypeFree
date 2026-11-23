package com.typefree.ime.localai

import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class MemoryPssSnapshot(
    val totalPssKb: Int = 0,
    val dalvikPssKb: Int = 0,
    val nativePssKb: Int = 0,
    val pid: Int = 0
)

/**
 * Core scheduling, cancellation, generation tracking, and metrics controller for `:local_ai`.
 * Factored out of [LocalAiInferenceService] so unit tests can exercise priority preemption,
 * cancellation, generation invalidation, probing, and unloading directly on the JVM.
 */
class LocalAiRuntimeController(
    private val asrEngine: LocalAsrEngine = LocalInferenceEngineRegistry.createAsrEngine(),
    private val slmEngine: LocalSlmEngine = LocalInferenceEngineRegistry.createSlmEngine(),
    private val pssReader: () -> MemoryPssSnapshot = ::readProcessPssSnapshot,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private data class ActiveTask(
        val requestId: Long,
        val slot: Int,
        val priority: Int,
        val generation: Long,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val preempted: AtomicBoolean = AtomicBoolean(false),
        @Volatile var closeOnCancel: Closeable? = null,
        @Volatile var future: Future<*>? = null
    ) {
        fun cancelNow(asPreempted: Boolean) {
            if (asPreempted) preempted.set(true)
            cancelled.set(true)
            runCatching { closeOnCancel?.close() }
        }
    }

    private val asrExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "typefree-localai-asr").apply { isDaemon = true }
    }
    private val slmExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "typefree-localai-slm").apply { isDaemon = true }
    }

    private val slotLock = Any()
    private val tasksById = ConcurrentHashMap<Long, ActiveTask>()

    @Volatile
    private var activeAsrTask: ActiveTask? = null

    @Volatile
    private var activeSlmTask: ActiveTask? = null

    private val generationCounter = AtomicLong(1L)

    @Volatile
    private var lastAsrLatencyMs: Long = 0L

    @Volatile
    private var lastSlmTtftMs: Long = 0L

    @Volatile
    private var lastSlmTokensPerSec: Float = 0f

    val currentGeneration: Long
        get() = generationCounter.get()

    fun protocolVersion(): Int = LOCAL_AI_PROTOCOL_VERSION

    fun openAsrSession(
        sid: Long,
        pcmStream: InputStream,
        params: AsrParams,
        cb: IAsrCallback
    ) {
        val gen = generationCounter.get()
        if (params.expectedGeneration > 0L && params.expectedGeneration != gen) {
            runCatching { pcmStream.close() }
            safeAsrError(cb, sid, ERR_STALE_GENERATION, "ASR generation mismatch", gen)
            return
        }

        val task = ActiveTask(
            requestId = sid,
            slot = SLOT_ASR,
            priority = params.priority.coerceAtLeast(PRIORITY_VOICE),
            generation = gen,
            closeOnCancel = pcmStream
        )

        synchronized(slotLock) {
            activeAsrTask?.let { prev ->
                prev.cancelNow(asPreempted = true)
            }
            activeAsrTask = task
            tasksById[sid] = task
        }

        task.future = asrExecutor.submit {
            try {
                if (task.cancelled.get()) {
                    emitCancelledAsr(cb, task)
                    return@submit
                }
                if (generationCounter.get() != task.generation) {
                    safeAsrError(cb, sid, ERR_STALE_GENERATION, "Model swapped during ASR startup", task.generation)
                    return@submit
                }

                val result = pcmStream.use { stream ->
                    asrEngine.transcribeStream(
                        sessionId = sid,
                        pcmStream = stream,
                        params = params,
                        cancelled = task.cancelled,
                        onPartial = { finalized, interim ->
                            if (!task.cancelled.get() && generationCounter.get() == task.generation) {
                                runCatching { cb.onPartial(sid, finalized, interim, task.generation) }
                            }
                        }
                    )
                }

                if (generationCounter.get() != task.generation) {
                    safeAsrError(cb, sid, ERR_STALE_GENERATION, "Discarded stale ASR result", task.generation)
                    return@submit
                }
                if (task.cancelled.get()) {
                    emitCancelledAsr(cb, task)
                    return@submit
                }

                result.fold(
                    onSuccess = { res ->
                        lastAsrLatencyMs = res.latencyMs
                        runCatching { cb.onFinal(sid, res.text, task.generation, res.latencyMs) }
                    },
                    onFailure = { err ->
                        safeAsrError(cb, sid, ERR_UNKNOWN, err.message ?: "Local ASR failed", task.generation)
                    }
                )
            } catch (t: Throwable) {
                if (task.cancelled.get()) {
                    emitCancelledAsr(cb, task)
                } else {
                    safeAsrError(cb, sid, ERR_UNKNOWN, t.message ?: "Local ASR exception", task.generation)
                }
            } finally {
                clearTask(task)
            }
        }
    }

    fun transcribeFile(
        rid: Long,
        wavPath: String,
        params: AsrParams,
        cb: IAsrCallback
    ) {
        val gen = generationCounter.get()
        if (params.expectedGeneration > 0L && params.expectedGeneration != gen) {
            safeAsrError(cb, rid, ERR_STALE_GENERATION, "ASR generation mismatch", gen)
            return
        }
        if (wavPath.isBlank()) {
            safeAsrError(cb, rid, ERR_INVALID_INPUT, "wavPath is empty", gen)
            return
        }

        val task = ActiveTask(
            requestId = rid,
            slot = SLOT_ASR,
            priority = params.priority,
            generation = gen
        )

        synchronized(slotLock) {
            val prev = activeAsrTask
            if (prev != null && task.priority >= prev.priority) {
                prev.cancelNow(asPreempted = true)
            }
            activeAsrTask = task
            tasksById[rid] = task
        }

        task.future = asrExecutor.submit {
            try {
                if (task.cancelled.get()) {
                    emitCancelledAsr(cb, task)
                    return@submit
                }
                if (generationCounter.get() != task.generation) {
                    safeAsrError(cb, rid, ERR_STALE_GENERATION, "Stale ASR file request", task.generation)
                    return@submit
                }

                val result = asrEngine.transcribeFile(
                    requestId = rid,
                    wavPath = wavPath,
                    params = params,
                    cancelled = task.cancelled,
                    onPartial = { finalized, interim ->
                        if (!task.cancelled.get() && generationCounter.get() == task.generation) {
                            runCatching { cb.onPartial(rid, finalized, interim, task.generation) }
                        }
                    }
                )

                if (generationCounter.get() != task.generation) {
                    safeAsrError(cb, rid, ERR_STALE_GENERATION, "Discarded stale file ASR result", task.generation)
                    return@submit
                }
                if (task.cancelled.get()) {
                    emitCancelledAsr(cb, task)
                    return@submit
                }

                result.fold(
                    onSuccess = { res ->
                        lastAsrLatencyMs = res.latencyMs
                        runCatching { cb.onFinal(rid, res.text, task.generation, res.latencyMs) }
                    },
                    onFailure = { err ->
                        safeAsrError(cb, rid, ERR_UNKNOWN, err.message ?: "Local file ASR failed", task.generation)
                    }
                )
            } catch (t: Throwable) {
                if (task.cancelled.get()) {
                    emitCancelledAsr(cb, task)
                } else {
                    safeAsrError(cb, rid, ERR_UNKNOWN, t.message ?: "Local file ASR exception", task.generation)
                }
            } finally {
                clearTask(task)
            }
        }
    }

    fun generate(
        rid: Long,
        req: GenRequest,
        cb: ITokenCallback
    ) {
        val gen = generationCounter.get()
        if (!slmEngine.isAvailable) {
            safeTokenError(cb, rid, ERR_SLM_UNAVAILABLE, "开源版未内置离线 SLM 推理引擎", gen)
            return
        }
        if (req.expectedGeneration > 0L && req.expectedGeneration != gen) {
            safeTokenError(cb, rid, ERR_STALE_GENERATION, "SLM generation mismatch", gen)
            return
        }

        val task = ActiveTask(
            requestId = rid,
            slot = SLOT_SLM,
            priority = req.priority,
            generation = gen
        )

        synchronized(slotLock) {
            val prev = activeSlmTask
            if (prev != null && !prev.cancelled.get()) {
                when {
                    // Voice (100) > Edit/Write (60) > Completion (20); equal priority -> newest wins
                    task.priority >= prev.priority -> {
                        prev.cancelNow(asPreempted = true)
                    }
                    // Drop low-priority completion immediately when higher-priority request is running
                    task.priority <= PRIORITY_COMPLETION -> {
                        safeTokenError(
                            cb,
                            rid,
                            ERR_PREEMPTED,
                            "Skipped completion while higher-priority SLM task is active",
                            gen
                        )
                        return
                    }
                }
            }
            activeSlmTask = task
            tasksById[rid] = task
        }

        task.future = slmExecutor.submit {
            try {
                if (task.cancelled.get()) {
                    emitCancelledToken(cb, task)
                    return@submit
                }
                if (generationCounter.get() != task.generation) {
                    safeTokenError(cb, rid, ERR_STALE_GENERATION, "Stale SLM request", task.generation)
                    return@submit
                }

                val batcher = TokenBatcher(
                    minIntervalMs = TOKEN_BATCH_INTERVAL_MS,
                    minTokenCount = TOKEN_BATCH_MIN_TOKENS,
                    clockMs = clockMs
                ) { chunk, count ->
                    if (!task.cancelled.get() && generationCounter.get() == task.generation) {
                        runCatching { cb.onTokenBatch(rid, chunk, count, task.generation) }
                    }
                }

                val result = try {
                    slmEngine.generate(
                        requestId = rid,
                        request = req,
                        cancelled = task.cancelled,
                        onTokenBatch = { chunk, count ->
                            if (!task.cancelled.get() && generationCounter.get() == task.generation) {
                                batcher.append(chunk, count)
                            }
                        }
                    )
                } finally {
                    if (req.incognito) {
                        runCatching { slmEngine.clearSessionState() }
                    }
                }

                if (generationCounter.get() != task.generation) {
                    safeTokenError(cb, rid, ERR_STALE_GENERATION, "Discarded stale SLM generation", task.generation)
                    return@submit
                }
                if (task.cancelled.get()) {
                    emitCancelledToken(cb, task)
                    return@submit
                }

                batcher.flush()
                result.fold(
                    onSuccess = { res ->
                        lastSlmTtftMs = res.ttftMs
                        val decodeMs = res.totalMs - res.ttftMs
                        lastSlmTokensPerSec = when {
                            res.totalTokens > 1 && decodeMs > 0L ->
                                ((res.totalTokens - 1) * 1000f) / decodeMs.toFloat()
                            res.totalMs > 0L ->
                                (res.totalTokens * 1000f) / res.totalMs.toFloat()
                            else -> 0f
                        }
                        runCatching {
                            cb.onCompleted(
                                rid,
                                res.text,
                                task.generation,
                                res.ttftMs,
                                res.totalMs,
                                res.totalTokens
                            )
                        }
                    },
                    onFailure = { err ->
                        safeTokenError(cb, rid, ERR_UNKNOWN, err.message ?: "Local SLM failed", task.generation)
                    }
                )
            } catch (t: Throwable) {
                if (task.cancelled.get()) {
                    emitCancelledToken(cb, task)
                } else {
                    safeTokenError(cb, rid, ERR_UNKNOWN, t.message ?: "Local SLM exception", task.generation)
                }
            } finally {
                clearTask(task)
            }
        }
    }

    fun cancel(rid: Long) {
        val task = tasksById[rid] ?: return
        task.cancelNow(asPreempted = false)
    }

    fun prewarmSlot(slot: Int, modelId: String, modelDirPath: String) {
        when (slot) {
            SLOT_ASR -> asrExecutor.submit {
                if (asrEngine.loadedModelId != modelId) {
                    asrEngine.loadModel(modelId, modelDirPath)
                }
            }

            SLOT_SLM -> {
                if (!slmEngine.isAvailable) return
                slmExecutor.submit {
                    if (slmEngine.loadedModelId != modelId) {
                        slmEngine.loadModel(modelId, modelDirPath)
                    }
                }
            }
        }
    }

    fun unloadSlot(slot: Int) {
        generationCounter.incrementAndGet()
        synchronized(slotLock) {
            when (slot) {
                SLOT_ASR -> {
                    activeAsrTask?.cancelNow(asPreempted = false)
                    activeAsrTask = null
                }

                SLOT_SLM -> {
                    activeSlmTask?.cancelNow(asPreempted = false)
                    activeSlmTask = null
                }
            }
        }
        when (slot) {
            SLOT_ASR -> asrExecutor.submit { runCatching { asrEngine.unload() } }
            SLOT_SLM -> slmExecutor.submit { runCatching { slmEngine.unload() } }
        }
    }

    fun unloadAll() {
        generationCounter.incrementAndGet()
        synchronized(slotLock) {
            activeAsrTask?.cancelNow(asPreempted = false)
            activeAsrTask = null
            activeSlmTask?.cancelNow(asPreempted = false)
            activeSlmTask = null
        }
        asrExecutor.submit { runCatching { asrEngine.unload() } }
        slmExecutor.submit { runCatching { slmEngine.unload() } }
    }

    /**
     * Unloads any existing model in [slot] first (avoiding 2x RAM peak), then executes a short
     * probe on [modelId] at [modelDirPath]. Returns empty string on success or error reason on failure.
     */
    fun probeModel(slot: Int, modelId: String, modelDirPath: String): String {
        return when (slot) {
            SLOT_ASR -> {
                synchronized(slotLock) {
                    activeAsrTask?.cancelNow(asPreempted = false)
                    activeAsrTask = null
                }
                val future = asrExecutor.submit<String> {
                    runCatching { asrEngine.unload() }
                    val res = asrEngine.probe(modelId, modelDirPath)
                    generationCounter.incrementAndGet()
                    res.fold(
                        onSuccess = { "" },
                        onFailure = { it.message ?: "ASR 模型探活失败" }
                    )
                }
                awaitProbeFuture(future, "ASR 探活超时")
            }

            SLOT_SLM -> {
                if (!slmEngine.isAvailable) {
                    return "当前构建未内置离线 SLM 推理引擎"
                }
                synchronized(slotLock) {
                    activeSlmTask?.cancelNow(asPreempted = false)
                    activeSlmTask = null
                }
                val future = slmExecutor.submit<String> {
                    runCatching { slmEngine.unload() }
                    val res = slmEngine.probe(modelId, modelDirPath)
                    generationCounter.incrementAndGet()
                    res.fold(
                        onSuccess = { "" },
                        onFailure = { it.message ?: "SLM 模型探活失败" }
                    )
                }
                awaitProbeFuture(future, "SLM 探活超时")
            }

            else -> "未知模型槽位: $slot"
        }
    }

    fun getRuntimeMetrics(): RuntimeMetrics {
        val pss = runCatching { pssReader() }.getOrDefault(MemoryPssSnapshot())
        return RuntimeMetrics(
            protocolVersion = LOCAL_AI_PROTOCOL_VERSION,
            modelGeneration = generationCounter.get(),
            loadedAsrModelId = asrEngine.loadedModelId.orEmpty(),
            loadedSlmModelId = slmEngine.loadedModelId.orEmpty(),
            totalPssKb = pss.totalPssKb,
            dalvikPssKb = pss.dalvikPssKb,
            nativePssKb = pss.nativePssKb,
            lastAsrLatencyMs = lastAsrLatencyMs,
            lastSlmTtftMs = lastSlmTtftMs,
            lastSlmTokensPerSec = lastSlmTokensPerSec,
            activeRequestCount = tasksById.size,
            slmEngineAvailable = slmEngine.isAvailable,
            pid = pss.pid
        )
    }

    fun shutdown() {
        unloadAll()
        asrExecutor.shutdownNow()
        slmExecutor.shutdownNow()
    }

    private fun awaitProbeFuture(future: Future<String>, timeoutMsg: String): String {
        return try {
            future.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            future.cancel(true)
            e.message ?: timeoutMsg
        }
    }

    private fun clearTask(task: ActiveTask) {
        tasksById.remove(task.requestId, task)
        synchronized(slotLock) {
            if (task.slot == SLOT_ASR && activeAsrTask === task) {
                activeAsrTask = null
            } else if (task.slot == SLOT_SLM && activeSlmTask === task) {
                activeSlmTask = null
            }
        }
    }

    private fun emitCancelledAsr(cb: IAsrCallback, task: ActiveTask) {
        val code = if (task.preempted.get()) ERR_PREEMPTED else ERR_CANCELLED
        val msg = if (task.preempted.get()) "Preempted by newer ASR request" else "Cancelled"
        safeAsrError(cb, task.requestId, code, msg, task.generation)
    }

    private fun emitCancelledToken(cb: ITokenCallback, task: ActiveTask) {
        val code = if (task.preempted.get()) ERR_PREEMPTED else ERR_CANCELLED
        val msg = if (task.preempted.get()) "Preempted by higher-priority task" else "Cancelled"
        safeTokenError(cb, task.requestId, code, msg, task.generation)
    }

    private fun safeAsrError(cb: IAsrCallback, sid: Long, code: Int, msg: String, gen: Long) {
        runCatching { cb.onError(sid, code, msg, gen) }
    }

    private fun safeTokenError(cb: ITokenCallback, rid: Long, code: Int, msg: String, gen: Long) {
        runCatching { cb.onError(rid, code, msg, gen) }
    }

    companion object {
        const val TOKEN_BATCH_INTERVAL_MS: Long = 30L
        const val TOKEN_BATCH_MIN_TOKENS: Int = 4
        const val PROBE_TIMEOUT_SECONDS: Long = 15L

        fun readProcessPssSnapshot(): MemoryPssSnapshot {
            return runCatching {
                val memInfo = Debug.MemoryInfo()
                Debug.getMemoryInfo(memInfo)
                MemoryPssSnapshot(
                    totalPssKb = memInfo.totalPss,
                    dalvikPssKb = memInfo.dalvikPss,
                    nativePssKb = memInfo.nativePss,
                    pid = Process.myPid()
                )
            }.getOrDefault(MemoryPssSnapshot())
        }
    }
}

/**
 * Batches high-frequency token callbacks (>= 30ms or >= 4 tokens) to avoid Binder IPC thrashing.
 */
internal class TokenBatcher(
    private val minIntervalMs: Long = 30L,
    private val minTokenCount: Int = 4,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val onFlush: (chunk: String, tokenCount: Int) -> Unit
) {
    private val buffer = StringBuilder()
    private var bufferedTokens = 0
    private var lastFlushMs = clockMs()

    fun append(chunk: String, tokenCount: Int) {
        if (chunk.isEmpty() && tokenCount <= 0) return
        buffer.append(chunk)
        bufferedTokens += tokenCount.coerceAtLeast(1)
        val now = clockMs()
        if (bufferedTokens >= minTokenCount || (now - lastFlushMs) >= minIntervalMs) {
            flush(now)
        }
    }

    fun flush(nowMs: Long = clockMs()) {
        if (buffer.isEmpty() && bufferedTokens == 0) return
        val out = buffer.toString()
        val count = bufferedTokens
        buffer.setLength(0)
        bufferedTokens = 0
        lastFlushMs = nowMs
        onFlush(out, count)
    }
}

/**
 * Isolated `:local_ai` process Android Service hosting [ILocalAiService].
 * Declared in AndroidManifest with `android:process=":local_ai"` and `android:exported="false"`.
 */
class LocalAiInferenceService : Service() {

    private lateinit var controller: LocalAiRuntimeController

    private val binder = object : ILocalAiService.Stub() {
        override fun protocolVersion(): Int = controller.protocolVersion()

        override fun openAsrSession(
            sid: Long,
            pcmPipe: ParcelFileDescriptor?,
            params: AsrParams?,
            cb: IAsrCallback?
        ) {
            if (cb == null) {
                runCatching { pcmPipe?.close() }
                return
            }
            if (pcmPipe == null || params == null) {
                runCatching { pcmPipe?.close() }
                runCatching {
                    cb.onError(sid, ERR_INVALID_INPUT, "Missing pcmPipe or AsrParams", controller.currentGeneration)
                }
                return
            }
            val stream = ParcelFileDescriptor.AutoCloseInputStream(pcmPipe)
            controller.openAsrSession(sid = sid, pcmStream = stream, params = params, cb = cb)
        }

        override fun transcribeFile(
            rid: Long,
            wavPath: String?,
            params: AsrParams?,
            cb: IAsrCallback?
        ) {
            if (cb == null) return
            if (wavPath.isNullOrBlank() || params == null) {
                runCatching {
                    cb.onError(rid, ERR_INVALID_INPUT, "Missing wavPath or AsrParams", controller.currentGeneration)
                }
                return
            }
            controller.transcribeFile(rid = rid, wavPath = wavPath, params = params, cb = cb)
        }

        override fun generate(
            rid: Long,
            req: GenRequest?,
            cb: ITokenCallback?
        ) {
            if (cb == null) return
            if (req == null) {
                runCatching {
                    cb.onError(rid, ERR_INVALID_INPUT, "Missing GenRequest", controller.currentGeneration)
                }
                return
            }
            controller.generate(rid = rid, req = req, cb = cb)
        }

        override fun cancel(rid: Long) {
            controller.cancel(rid)
        }

        override fun prewarmSlot(slot: Int, modelId: String?, modelDirPath: String?) {
            if (modelId.isNullOrBlank() || modelDirPath.isNullOrBlank()) return
            controller.prewarmSlot(slot, modelId, modelDirPath)
        }

        override fun unloadSlot(slot: Int) {
            controller.unloadSlot(slot)
        }

        override fun unloadAll() {
            controller.unloadAll()
        }

        override fun probeModel(slot: Int, modelId: String?, modelDirPath: String?): String {
            if (modelId.isNullOrBlank() || modelDirPath.isNullOrBlank()) {
                return "探活参数无效：modelId 或目录路径为空"
            }
            return controller.probeModel(slot, modelId, modelDirPath)
        }

        override fun getRuntimeMetrics(): RuntimeMetrics {
            return controller.getRuntimeMetrics()
        }
    }

    override fun onCreate() {
        super.onCreate()
        controller = LocalAiRuntimeController()
        LocalAiLog.i("LocalAiInferenceService created in process=${ProcessUtil.resolveProcessName(this)}")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        LocalAiLog.i("LocalAiInferenceService destroying")
        if (::controller.isInitialized) {
            controller.shutdown()
        }
        super.onDestroy()
    }
}
