package com.typefree.pro.localai

/**
 * Callback invoked from native llama.cpp decode loop for each decoded UTF-8 piece.
 */
fun interface TokenCallback {
    fun onToken(text: String)
}

/**
 * Result and latency metrics returned from [LlamaNativeBridge.nativeGenerate].
 */
data class GenerateStats(
    val text: String,
    val ttftMs: Long,
    val totalMs: Long,
    val totalTokens: Int,
    val aborted: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Pure JNI bridge to `libtypefree_slm_jni.so` (llama.cpp v1.0.0 C++17 engine).
 *
 * Supports:
 * - Qwen3 & Qwen3.5 (including Gated DeltaNet hybrid linear attention) GGUF models
 * - Base model `mmap` + optional dynamic LoRA GGUF loading
 * - Millisecond-level native prefill & decode abort via `llama_set_abort_callback`
 * - Full KV + recurrent state wipe (`llama_memory_clear`) for incognito & per-request isolation
 */
object LlamaNativeBridge {

    @Volatile
    private var libraryLoaded: Boolean = false

    @Volatile
    private var loadError: Throwable? = null

    fun ensureLoaded(): Result<Unit> {
        if (libraryLoaded) return Result.success(Unit)
        synchronized(this) {
            if (libraryLoaded) return Result.success(Unit)
            return runCatching {
                System.loadLibrary("typefree_slm_jni")
                libraryLoaded = true
                loadError = null
            }.onFailure { err ->
                loadError = err
            }
        }
    }

    fun isLibraryLoaded(): Boolean = libraryLoaded

    fun lastLoadError(): Throwable? = loadError

    @JvmStatic
    external fun nativeInitBackend()

    @JvmStatic
    external fun nativeFreeBackend()

    /**
     * Loads a GGUF base model (and optional LoRA adapter GGUF) and creates a context.
     *
     * @param modelPath Absolute path to the base/merged `.gguf` file.
     * @param loraPath Optional absolute path to a LoRA `.gguf` file, or null.
     * @param nCtx Context window size in tokens.
     * @param nThreads Number of CPU threads for generation/prefill.
     * @param useMmap Whether to memory-map the base GGUF weights.
     * @return Non-zero opaque session handle on success, or `0L` on failure.
     */
    @JvmStatic
    external fun nativeLoadModel(
        modelPath: String,
        loraPath: String?,
        nCtx: Int,
        nThreads: Int,
        useMmap: Boolean
    ): Long

    /**
     * Runs Qwen3 / Qwen3.5 non-thinking ChatML generation.
     *
     * @param allowPrefixCache Enables static prompt prefix KV/recurrent state caching (disabled in incognito).
     */
    @JvmStatic
    external fun nativeGenerate(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        allowPrefixCache: Boolean,
        callback: TokenCallback
    ): GenerateStats

    /**
     * Signals atomic abort for an active prefill/decode on [handle].
     * Thread-safe and safe to call even if [handle] has already been unloaded.
     */
    @JvmStatic
    external fun nativeAbort(handle: Long)

    /**
     * Clears KV cache and linear-attention / recurrent state (`llama_memory_clear`).
     */
    @JvmStatic
    external fun nativeClearKvCache(handle: Long)

    /**
     * Releases LoRA adapter, context, and model associated with [handle].
     */
    @JvmStatic
    external fun nativeUnloadModel(handle: Long)
}
