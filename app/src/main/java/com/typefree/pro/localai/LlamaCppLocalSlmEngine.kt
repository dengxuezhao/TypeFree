package com.typefree.pro.localai

import com.typefree.ime.localai.GenRequest
import com.typefree.ime.localai.LocalAiLog
import com.typefree.ime.localai.LocalSlmEngine
import com.typefree.ime.localai.LocalSlmRuntimeResult
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Proprietary Pro on-device SLM engine backed by `llama.cpp` v1.0.0 (`libtypefree_slm_jni.so`).
 *
 * Features:
 * - Resolves base/merged GGUF (`model.gguf` or non-LoRA `.gguf`) + optional LoRA adapter (`*lora*.gguf`)
 * - Base model `mmap` + dynamic LoRA attachment
 * - Real-time cancellation forwarding from [AtomicBoolean] to native `llama_set_abort_callback`
 * - >= 30ms token batching and per-request / incognito KV & recurrent state wiping
 */
class LlamaCppLocalSlmEngine(
    private val contextSize: Int = DEFAULT_CONTEXT_SIZE,
    private val threadCountProvider: () -> Int = ::defaultThreadCount,
    private val useMmap: Boolean = true
) : LocalSlmEngine {

    private data class ResolvedModelFiles(
        val baseModelPath: String,
        val loraAdapterPath: String?
    )

    private val engineLock = Any()

    @Volatile
    private var backendInitialized: Boolean = false

    @Volatile
    private var currentHandle: Long = 0L

    @Volatile
    private var currentModelId: String? = null

    @Volatile
    private var currentModelFiles: ResolvedModelFiles? = null

    private val abortWatcher: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "typefree-slm-abort-watcher").apply { isDaemon = true }
    }

    override val isAvailable: Boolean = true

    override val loadedModelId: String?
        get() = currentModelId

    override fun loadModel(modelId: String, modelDirPath: String): Result<Unit> {
        if (modelId.isBlank()) {
            return Result.failure(IllegalArgumentException("SLM modelId 不能为空"))
        }
        val resolved = resolveModelFiles(modelDirPath).getOrElse { return Result.failure(it) }

        synchronized(engineLock) {
            if (currentHandle != 0L && currentModelId == modelId && currentModelFiles == resolved) {
                return Result.success(Unit)
            }

            val loadLib = LlamaNativeBridge.ensureLoaded()
            if (loadLib.isFailure) {
                return Result.failure(
                    IllegalStateException(
                        "无法加载 libtypefree_slm_jni.so: ${loadLib.exceptionOrNull()?.message}",
                        loadLib.exceptionOrNull()
                    )
                )
            }

            if (!backendInitialized) {
                LlamaNativeBridge.nativeInitBackend()
                backendInitialized = true
            }

            // Always unload previous model before loading a new one to prevent 2x RAM spikes.
            unloadLocked()

            val threads = threadCountProvider().coerceIn(1, 8)
            val resolvedCtx = resolveContextSize(modelId)
            val handle = LlamaNativeBridge.nativeLoadModel(
                modelPath = resolved.baseModelPath,
                loraPath = resolved.loraAdapterPath,
                nCtx = resolvedCtx,
                nThreads = threads,
                useMmap = useMmap
            )
            if (handle == 0L) {
                return Result.failure(
                    IOException(
                        "llama.cpp 加载模型失败 (modelId=$modelId, base=${File(resolved.baseModelPath).name}, " +
                            "lora=${resolved.loraAdapterPath?.let { File(it).name } ?: "none"})"
                    )
                )
            }

            currentHandle = handle
            currentModelId = modelId
            currentModelFiles = resolved
            LocalAiLog.i(
                "LlamaCppLocalSlmEngine loaded modelId=$modelId, base=${File(resolved.baseModelPath).name}, " +
                    "lora=${resolved.loraAdapterPath?.let { File(it).name } ?: "none"}, " +
                    "nCtx=$resolvedCtx, threads=$threads, mmap=$useMmap"
            )
            return Result.success(Unit)
        }
    }

    override fun unload() {
        val handleToAbort = currentHandle
        if (handleToAbort != 0L) {
            runCatching { LlamaNativeBridge.nativeAbort(handleToAbort) }
        }
        synchronized(engineLock) {
            unloadLocked()
        }
    }

    override fun clearSessionState() {
        synchronized(engineLock) {
            val handle = currentHandle
            if (handle != 0L) {
                runCatching { LlamaNativeBridge.nativeClearKvCache(handle) }
            }
        }
    }

    override fun probe(modelId: String, modelDirPath: String): Result<Unit> {
        val loadRes = loadModel(modelId, modelDirPath)
        if (loadRes.isFailure) return loadRes

        val probeRequest = GenRequest(
            modelId = modelId,
            modelDirPath = modelDirPath,
            taskName = "PROBE",
            systemPrompt = "你是离线文本助手，请简短回复。",
            userPrompt = "你好",
            maxTokens = PROBE_MAX_TOKENS,
            temperature = 0.0f,
            incognito = true
        )
        val probeCancelled = AtomicBoolean(false)
        val genRes = generate(
            requestId = -1L,
            request = probeRequest,
            cancelled = probeCancelled,
            onTokenBatch = { _, _ -> }
        )
        return genRes.fold(
            onSuccess = { stats ->
                clearSessionState()
                LocalAiLog.i(
                    "LlamaCppLocalSlmEngine probe ok: modelId=$modelId, " +
                        "tokens=${stats.totalTokens}, ttft=${stats.ttftMs}ms, total=${stats.totalMs}ms"
                )
                Result.success(Unit)
            },
            onFailure = { err ->
                unload()
                Result.failure(IOException("SLM 探活推理失败 ($modelId): ${err.message}", err))
            }
        )
    }

    override fun generate(
        requestId: Long,
        request: GenRequest,
        cancelled: AtomicBoolean,
        onTokenBatch: (chunk: String, tokenCount: Int) -> Unit
    ): Result<LocalSlmRuntimeResult> {
        if (cancelled.get()) {
            return Result.failure(IOException("SLM generation cancelled before start (rid=$requestId)"))
        }

        val handle: Long
        synchronized(engineLock) {
            if (currentHandle == 0L || currentModelId != request.modelId) {
                val loaded = loadModel(request.modelId, request.modelDirPath)
                if (loaded.isFailure) {
                    return Result.failure(loaded.exceptionOrNull() ?: IOException("模型加载失败"))
                }
            }
            handle = currentHandle
        }

        if (handle == 0L) {
            return Result.failure(IllegalStateException("SLM 模型未就绪"))
        }
        if (cancelled.get()) {
            return Result.failure(IOException("SLM generation cancelled after load (rid=$requestId)"))
        }

        val abortIssued = AtomicBoolean(false)
        val triggerNativeAbort = {
            if (abortIssued.compareAndSet(false, true)) {
                LlamaNativeBridge.nativeAbort(handle)
            }
        }

        val watchFuture = abortWatcher.scheduleWithFixedDelay(
            {
                if (cancelled.get()) {
                    triggerNativeAbort()
                }
            },
            ABORT_POLL_INTERVAL_MS,
            ABORT_POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )

        val batchBuffer = StringBuilder()
        var bufferedTokens = 0
        var lastFlushMs = System.currentTimeMillis()

        fun flushBatch(force: Boolean) {
            if (batchBuffer.isEmpty() && bufferedTokens == 0) return
            val now = System.currentTimeMillis()
            if (force || (now - lastFlushMs) >= TOKEN_BATCH_INTERVAL_MS) {
                val chunk = batchBuffer.toString()
                val count = bufferedTokens.coerceAtLeast(1)
                batchBuffer.setLength(0)
                bufferedTokens = 0
                lastFlushMs = now
                if (!cancelled.get()) {
                    onTokenBatch(chunk, count)
                }
            }
        }

        val safeMaxTokens = request.maxTokens.coerceIn(1, MAX_GENERATE_TOKENS)
        val safeTemp = request.temperature.coerceIn(0.0f, 2.0f)

        val stats = try {
            synchronized(engineLock) {
                if (currentHandle != handle) {
                    return Result.failure(IOException("SLM model unloaded concurrently (rid=$requestId)"))
                }
                LlamaNativeBridge.nativeGenerate(
                    handle = handle,
                    systemPrompt = request.systemPrompt,
                    userPrompt = request.userPrompt,
                    maxTokens = safeMaxTokens,
                    temperature = safeTemp,
                    allowPrefixCache = !request.incognito,
                    callback = TokenCallback { piece ->
                        if (cancelled.get()) {
                            triggerNativeAbort()
                            return@TokenCallback
                        }
                        if (piece.isNotEmpty()) {
                            batchBuffer.append(piece)
                            bufferedTokens += 1
                            flushBatch(force = false)
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            return Result.failure(IOException("SLM native 推理异常 (rid=$requestId): ${t.message}", t))
        } finally {
            watchFuture.cancel(false)
            if (request.incognito) {
                clearSessionState()
            }
        }

        if (cancelled.get() || stats.aborted) {
            return Result.failure(IOException("SLM generation cancelled (rid=$requestId)"))
        }

        if (!stats.errorMessage.isNullOrBlank()) {
            return Result.failure(IOException("SLM 推理失败: ${stats.errorMessage}"))
        }

        flushBatch(force = true)

        return Result.success(
            LocalSlmRuntimeResult(
                text = stats.text,
                ttftMs = stats.ttftMs.coerceAtLeast(0L),
                totalMs = stats.totalMs.coerceAtLeast(1L),
                totalTokens = stats.totalTokens.coerceAtLeast(0),
                isMock = false
            )
        )
    }

    private fun unloadLocked() {
        val handle = currentHandle
        val prevId = currentModelId
        currentHandle = 0L
        currentModelId = null
        currentModelFiles = null
        if (handle != 0L) {
            runCatching { LlamaNativeBridge.nativeUnloadModel(handle) }
            LocalAiLog.i("LlamaCppLocalSlmEngine unloaded modelId=$prevId")
        }
    }

    private fun resolveModelFiles(modelDirPath: String): Result<ResolvedModelFiles> {
        val target = File(modelDirPath)
        if (!target.exists()) {
            return Result.failure(IOException("SLM 模型路径不存在: $modelDirPath"))
        }

        if (target.isFile) {
            if (!target.name.lowercase(Locale.ROOT).endsWith(".gguf") || target.length() <= 0L) {
                return Result.failure(IOException("无效的 GGUF 模型文件: $modelDirPath"))
            }
            return Result.success(ResolvedModelFiles(baseModelPath = target.absolutePath, loraAdapterPath = null))
        }

        val candidates = collectGgufFiles(target)
        if (candidates.isEmpty()) {
            return Result.failure(IOException("模型目录中未找到有效 .gguf 权重文件: $modelDirPath"))
        }

        val loraFiles = candidates.filter { isLoraFilename(it.name) }
        val baseFiles = candidates.filterNot { isLoraFilename(it.name) || isMmprojFilename(it.name) }

        val baseFile = baseFiles.firstOrNull { it.name.equals("model.gguf", ignoreCase = true) }
            ?: baseFiles.maxByOrNull { it.length() }
            ?: return Result.failure(IOException("模型目录缺少底座/合并 GGUF 文件: $modelDirPath"))

        val loraFile = loraFiles.firstOrNull { it.name.equals("lora.gguf", ignoreCase = true) }
            ?: loraFiles.firstOrNull()

        return Result.success(
            ResolvedModelFiles(
                baseModelPath = baseFile.absolutePath,
                loraAdapterPath = loraFile?.absolutePath
            )
        )
    }

    private fun collectGgufFiles(dir: File): List<File> {
        val topLevel = dir.listFiles().orEmpty()
            .filter { it.isFile && it.length() > 0L && it.name.lowercase(Locale.ROOT).endsWith(".gguf") }
            .sortedBy { it.name }
        if (topLevel.isNotEmpty()) return topLevel

        // Fallback: support one nested subdirectory (e.g., extracted archive layout).
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { sub ->
                sub.listFiles().orEmpty()
                    .filter { it.isFile && it.length() > 0L && it.name.lowercase(Locale.ROOT).endsWith(".gguf") }
            }
            .sortedBy { it.name }
    }

    private fun isLoraFilename(name: String): Boolean {
        return name.lowercase(Locale.ROOT).contains("lora")
    }

    private fun isMmprojFilename(name: String): Boolean {
        return name.lowercase(Locale.ROOT).contains("mmproj")
    }

    private fun resolveContextSize(modelId: String): Int {
        return if (modelId.contains("0_8b", ignoreCase = true) || modelId.contains("0.8b", ignoreCase = true)) {
            minOf(contextSize, CORE_0_8B_CONTEXT_SIZE)
        } else {
            contextSize
        }
    }

    companion object {
        const val DEFAULT_CONTEXT_SIZE: Int = 2048
        const val CORE_0_8B_CONTEXT_SIZE: Int = 1024
        const val PROBE_MAX_TOKENS: Int = 4
        const val MAX_GENERATE_TOKENS: Int = 1024
        const val TOKEN_BATCH_INTERVAL_MS: Long = 30L
        const val ABORT_POLL_INTERVAL_MS: Long = 5L

        fun defaultThreadCount(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return (cores / 2).coerceIn(2, 4)
        }
    }
}
