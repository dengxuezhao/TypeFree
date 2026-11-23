package com.typefree.ime.localai

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

data class LocalAsrRuntimeResult(
    val text: String,
    val latencyMs: Long,
    val isMock: Boolean = false
)

data class LocalSlmRuntimeResult(
    val text: String,
    val ttftMs: Long,
    val totalMs: Long,
    val totalTokens: Int,
    val isMock: Boolean = false
)

/**
 * SPI for on-device speech recognition engines (Phase 1 Mock; Phase 2 sherpa-onnx CPU open-source engines).
 */
interface LocalAsrEngine {
    val loadedModelId: String?

    fun loadModel(modelId: String, modelDirPath: String): Result<Unit>

    fun unload()

    fun probe(modelId: String, modelDirPath: String): Result<Unit>

    fun transcribeStream(
        sessionId: Long,
        pcmStream: InputStream,
        params: AsrParams,
        cancelled: AtomicBoolean,
        onPartial: (finalized: String, interim: String) -> Unit
    ): Result<LocalAsrRuntimeResult>

    fun transcribeFile(
        requestId: Long,
        wavPath: String,
        params: AsrParams,
        cancelled: AtomicBoolean,
        onPartial: (finalized: String, interim: String) -> Unit
    ): Result<LocalAsrRuntimeResult>
}

/**
 * SPI for on-device text SLM engines.
 *
 * Open-source GPL-3.0 builds bind [NoOpLocalSlmEngine] by default; proprietary Pro builds
 * inject a private implementation via [LocalInferenceEngineRegistry] at compile/startup time.
 */
interface LocalSlmEngine {
    val isAvailable: Boolean
    val loadedModelId: String?

    fun loadModel(modelId: String, modelDirPath: String): Result<Unit>

    fun unload()

    /**
     * Immediately wipes KV-cache / recurrent state (e.g. Gated DeltaNet state) for incognito sessions.
     */
    fun clearSessionState()

    fun probe(modelId: String, modelDirPath: String): Result<Unit>

    fun generate(
        requestId: Long,
        request: GenRequest,
        cancelled: AtomicBoolean,
        onTokenBatch: (chunk: String, tokenCount: Int) -> Unit
    ): Result<LocalSlmRuntimeResult>
}

/**
 * Default open-source placeholder SLM engine.
 * Keeps the public GPL-3.0 repository free of any private fine-tuned weights, encryption, or license checks.
 */
object NoOpLocalSlmEngine : LocalSlmEngine {
    override val isAvailable: Boolean = false
    override val loadedModelId: String? = null

    private const val UNAVAILABLE_MESSAGE =
        "当前开源构建未内置自研离线文本大脑（SLM），请使用云端模型或离线语音直出"

    override fun loadModel(modelId: String, modelDirPath: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException(UNAVAILABLE_MESSAGE))
    }

    override fun unload() = Unit

    override fun clearSessionState() = Unit

    override fun probe(modelId: String, modelDirPath: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException(UNAVAILABLE_MESSAGE))
    }

    override fun generate(
        requestId: Long,
        request: GenRequest,
        cancelled: AtomicBoolean,
        onTokenBatch: (chunk: String, tokenCount: Int) -> Unit
    ): Result<LocalSlmRuntimeResult> {
        return Result.failure(UnsupportedOperationException(UNAVAILABLE_MESSAGE))
    }
}

/**
 * Phase 1 simulated streaming ASR engine for end-to-end validation of the `:local_ai` process,
 * PFD pipe audio streaming, incremental `LiveTranscript` emission, cancellation, and safe model probe/swap.
 */
class MockLocalAsrEngine(
    private val chunkDelayMs: Long = 0L
) : LocalAsrEngine {

    @Volatile
    private var currentModelId: String? = null

    @Volatile
    private var currentModelDir: String? = null

    override val loadedModelId: String?
        get() = currentModelId

    override fun loadModel(modelId: String, modelDirPath: String): Result<Unit> {
        val validation = validateModelDirectory(modelId, modelDirPath)
        if (validation.isFailure) return validation
        currentModelId = modelId
        currentModelDir = modelDirPath
        LocalAiLog.i("MockLocalAsrEngine loaded modelId=$modelId")
        return Result.success(Unit)
    }

    override fun unload() {
        val prev = currentModelId
        currentModelId = null
        currentModelDir = null
        if (prev != null) {
            LocalAiLog.i("MockLocalAsrEngine unloaded modelId=$prev")
        }
    }

    override fun probe(modelId: String, modelDirPath: String): Result<Unit> {
        val loadRes = loadModel(modelId, modelDirPath)
        if (loadRes.isFailure) return loadRes
        val dir = File(modelDirPath)
        if (File(dir, PROBE_FAIL_MARKER).exists()) {
            unload()
            return Result.failure(IllegalStateException("模拟探活失败：设备不支持该模型 ($modelId)"))
        }
        return Result.success(Unit)
    }

    override fun transcribeStream(
        sessionId: Long,
        pcmStream: InputStream,
        params: AsrParams,
        cancelled: AtomicBoolean,
        onPartial: (finalized: String, interim: String) -> Unit
    ): Result<LocalAsrRuntimeResult> {
        val startNs = System.nanoTime()
        if (currentModelId != params.modelId) {
            val loaded = loadModel(params.modelId, params.modelDirPath)
            if (loaded.isFailure) return Result.failure(loaded.exceptionOrNull()!!)
        }

        val buffer = ByteArray(PCM_CHUNK_BYTES)
        var totalBytes = 0L
        var chunkCount = 0
        val finalizedBuilder = StringBuilder()

        try {
            while (true) {
                if (cancelled.get()) {
                    return Result.failure(IOException("ASR session cancelled (sid=$sessionId)"))
                }
                val read = pcmStream.read(buffer)
                if (read < 0) break
                if (read == 0) continue

                totalBytes += read
                chunkCount += 1
                if (chunkDelayMs > 0L) {
                    Thread.sleep(chunkDelayMs)
                }
                if (cancelled.get()) {
                    return Result.failure(IOException("ASR session cancelled (sid=$sessionId)"))
                }

                // Simulate VAD sentence finalization every 4 chunks (~800ms) and interim tail in between
                val segmentIndex = chunkCount / 4
                val phase = chunkCount % 4
                val hotwordHint = params.sanitizedHotwords.firstOrNull()?.let { "($it)" }.orEmpty()
                if (phase == 0) {
                    if (finalizedBuilder.isNotEmpty()) finalizedBuilder.append("，")
                    finalizedBuilder.append("离线语音片段$segmentIndex$hotwordHint")
                    onPartial(finalizedBuilder.toString(), "")
                } else {
                    onPartial(finalizedBuilder.toString(), "正在识别第${segmentIndex + 1}句")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.failure(IOException("ASR stream interrupted", e))
        } catch (e: Exception) {
            if (cancelled.get()) {
                return Result.failure(IOException("ASR session cancelled (sid=$sessionId)", e))
            }
            return Result.failure(e)
        }

        if (cancelled.get()) {
            return Result.failure(IOException("ASR session cancelled (sid=$sessionId)"))
        }

        val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        val durationSec = totalBytes.toDouble() / (params.sampleRateHz.coerceAtLeast(8000) * 2.0)
        val finalText = when {
            totalBytes == 0L -> ""
            finalizedBuilder.isNotEmpty() -> finalizedBuilder.toString()
            else -> {
                val hotwordSuffix = params.sanitizedHotwords.firstOrNull()?.let { " $it" }.orEmpty()
                "离线模拟转写(${"%.1f".format(durationSec)}s)$hotwordSuffix".trim()
            }
        }
        return Result.success(
            LocalAsrRuntimeResult(
                text = finalText,
                latencyMs = latencyMs,
                isMock = true
            )
        )
    }

    override fun transcribeFile(
        requestId: Long,
        wavPath: String,
        params: AsrParams,
        cancelled: AtomicBoolean,
        onPartial: (finalized: String, interim: String) -> Unit
    ): Result<LocalAsrRuntimeResult> {
        val startNs = System.nanoTime()
        if (cancelled.get()) {
            return Result.failure(IOException("File transcription cancelled (rid=$requestId)"))
        }
        val file = File(wavPath)
        if (!file.isFile || file.length() <= 0L) {
            return Result.failure(IOException("音频文件不存在或为空: $wavPath"))
        }
        if (currentModelId != params.modelId) {
            val loaded = loadModel(params.modelId, params.modelDirPath)
            if (loaded.isFailure) return Result.failure(loaded.exceptionOrNull()!!)
        }
        if (cancelled.get()) {
            return Result.failure(IOException("File transcription cancelled (rid=$requestId)"))
        }
        val text = "离线文件转写(${file.name}, ${file.length()}B)"
        onPartial(text, "")
        val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        return Result.success(
            LocalAsrRuntimeResult(
                text = text,
                latencyMs = latencyMs,
                isMock = true
            )
        )
    }

    private fun validateModelDirectory(modelId: String, modelDirPath: String): Result<Unit> {
        if (modelId.isBlank()) {
            return Result.failure(IllegalArgumentException("modelId 不能为空"))
        }
        val dir = File(modelDirPath)
        if (!dir.exists() || !dir.isDirectory) {
            return Result.failure(IOException("模型目录不存在: $modelDirPath"))
        }
        val files = dir.listFiles().orEmpty()
        val hasPayload = files.any { it.isFile && it.length() > 0L && it.name != PROBE_FAIL_MARKER }
        if (!hasPayload) {
            return Result.failure(IOException("模型目录为空或缺少权重文件: $modelDirPath"))
        }
        return Result.success(Unit)
    }

    companion object {
        const val PCM_CHUNK_BYTES: Int = 6_400 // 200ms @ 16kHz 16-bit mono
        const val PROBE_FAIL_MARKER: String = ".probe_fail"
    }
}

/**
 * Optional download source descriptor injected at runtime by a proprietary Pro overlay so
 * commercial/fine-tuned SLM weights (base GGUF + optional LoRA GGUF) can be fetched via the
 * standard HTTPS resumable downloader without embedding private URLs in the open-source repo.
 */
data class SlmDownloadSource(
    val baseUrl: String,
    val baseMirrorUrls: List<String> = emptyList(),
    val baseSizeBytes: Long = 0L,
    val baseExpectedSha256: String = "",
    val loraUrl: String = "",
    val loraMirrorUrls: List<String> = emptyList(),
    val loraSizeBytes: Long = 0L,
    val loraExpectedSha256: String = ""
) {
    val totalSizeBytes: Long
        get() = baseSizeBytes.coerceAtLeast(0L) + loraSizeBytes.coerceAtLeast(0L)
}

/**
 * SPI factory registry allowing proprietary Pro flavors to bind a real [LocalSlmEngine]
 * without introducing any Pro code into the open-source repository.
 */
object LocalInferenceEngineRegistry {

    @Volatile
    private var asrFactory: () -> LocalAsrEngine = { MockLocalAsrEngine() }

    @Volatile
    private var slmFactory: () -> LocalSlmEngine = { NoOpLocalSlmEngine }

    @Volatile
    private var slmAvailableFlag: Boolean = false

    private val slmDownloadSources = java.util.concurrent.ConcurrentHashMap<String, SlmDownloadSource>()

    val isSlmEngineAvailable: Boolean
        get() = slmAvailableFlag

    fun registerAsrFactory(factory: () -> LocalAsrEngine) {
        asrFactory = factory
    }

    fun registerSlmFactory(available: Boolean, factory: () -> LocalSlmEngine) {
        slmAvailableFlag = available
        slmFactory = factory
    }

    fun registerSlmDownloadSource(modelId: String, source: SlmDownloadSource) {
        if (modelId.isNotBlank() && source.baseUrl.isNotBlank()) {
            slmDownloadSources[modelId] = source
        }
    }

    fun getSlmDownloadSource(modelId: String): SlmDownloadSource? = slmDownloadSources[modelId]

    fun hasDownloadableSlmSource(modelId: String): Boolean = slmDownloadSources.containsKey(modelId)

    fun createAsrEngine(): LocalAsrEngine = asrFactory()

    fun createSlmEngine(): LocalSlmEngine = slmFactory()

    fun resetDefaults() {
        asrFactory = { MockLocalAsrEngine() }
        slmFactory = { NoOpLocalSlmEngine }
        slmAvailableFlag = false
        slmDownloadSources.clear()
    }
}

/**
 * Startup SPI bootstrap executed in both the main process (before [LocalModelRegistry] is created)
 * and the isolated `:local_ai` process ([LocalAiProcessInit.init]).
 *
 * - Automatically binds [SherpaOnnxLocalAsrEngine] when running on Android with the open-source
 *   `sherpa-onnx` AAR on the classpath (never triggers native library loading during JVM unit tests).
 * - Optionally reflects into `com.typefree.pro.localai.ProLocalAiRegistrar.register(Context)` when
 *   a proprietary Pro overlay is present; silently remains on [NoOpLocalSlmEngine] in open-source builds.
 */
object LocalInferenceEngineBootstrap {

    const val SHERPA_CLASS_NAME: String = "com.k2fsa.sherpa.onnx.OfflineRecognizer"
    const val PRO_REGISTRAR_CLASS_NAME: String = "com.typefree.pro.localai.ProLocalAiRegistrar"

    private val initialized = AtomicBoolean(false)

    @Volatile
    var isSherpaAsrRegistered: Boolean = false
        private set

    @Volatile
    var isProRegistrarLoaded: Boolean = false
        private set

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        initializeInternal(
            context = context,
            isAndroidRuntime = isAndroidDeviceRuntime(),
            sherpaClassName = SHERPA_CLASS_NAME,
            proRegistrarClassName = PRO_REGISTRAR_CLASS_NAME
        )
    }

    internal fun initializeInternal(
        context: Context?,
        isAndroidRuntime: Boolean,
        sherpaClassName: String = SHERPA_CLASS_NAME,
        proRegistrarClassName: String = PRO_REGISTRAR_CLASS_NAME,
        classLoader: ClassLoader = LocalInferenceEngineBootstrap::class.java.classLoader
            ?: Thread.currentThread().contextClassLoader
    ) {
        if (isAndroidRuntime && isClassPresent(sherpaClassName, classLoader)) {
            LocalInferenceEngineRegistry.registerAsrFactory { SherpaOnnxLocalAsrEngine() }
            isSherpaAsrRegistered = true
            LocalAiLog.i("LocalInferenceEngineBootstrap registered SherpaOnnxLocalAsrEngine")
        } else {
            isSherpaAsrRegistered = false
        }

        isProRegistrarLoaded = tryInvokeProRegistrar(context, proRegistrarClassName, classLoader)
    }

    internal fun isAndroidDeviceRuntime(): Boolean {
        val runtimeName = System.getProperty("java.runtime.name").orEmpty()
        val vmName = System.getProperty("java.vm.name").orEmpty()
        val isAndroidVm = runtimeName.contains("Android", ignoreCase = true) ||
            vmName.contains("Dalvik", ignoreCase = true) ||
            vmName.contains("ART", ignoreCase = true)
        if (!isAndroidVm) return false
        val fingerprint = runCatching { Build.FINGERPRINT }.getOrNull()
        if (fingerprint != null && fingerprint.equals("robolectric", ignoreCase = true)) {
            return false
        }
        return true
    }

    internal fun isClassPresent(className: String, classLoader: ClassLoader): Boolean {
        return try {
            Class.forName(className, false, classLoader)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun tryInvokeProRegistrar(
        context: Context?,
        className: String,
        classLoader: ClassLoader
    ): Boolean {
        return try {
            val clazz = Class.forName(className, true, classLoader)
            val method = clazz.getMethod("register", Context::class.java)
            val receiver = runCatching { clazz.getField("INSTANCE").get(null) }.getOrNull()
            method.invoke(receiver, context)
            LocalAiLog.i("LocalInferenceEngineBootstrap loaded Pro registrar: $className")
            true
        } catch (_: ClassNotFoundException) {
            // Expected in open-source builds: keep NoOpLocalSlmEngine silently.
            false
        } catch (t: Throwable) {
            LocalAiLog.w("LocalInferenceEngineBootstrap failed to invoke $className.register(Context)", t)
            false
        }
    }

    internal fun resetForTesting() {
        initialized.set(false)
        isSherpaAsrRegistered = false
        isProRegistrarLoaded = false
    }
}
