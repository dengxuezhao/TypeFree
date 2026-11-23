package com.typefree.ime.localai

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.typefree.core.offline.AsrModelArch
import com.typefree.core.offline.OfflineModelCatalog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolved file paths inside an extracted sherpa-onnx ASR model directory.
 * Pure Kotlin value hierarchy so unit tests can verify all 8 catalog model layouts on JVM.
 */
sealed class ResolvedAsrModelFiles {
    abstract val arch: AsrModelArch
    abstract val rootDir: File
    abstract val sileroVadPath: String?

    data class OnlineTransducer(
        override val rootDir: File,
        val encoderPath: String,
        val decoderPath: String,
        val joinerPath: String,
        val tokensPath: String,
        val bpeVocabPath: String? = null,
        val modelType: String = "zipformer",
        override val sileroVadPath: String? = null
    ) : ResolvedAsrModelFiles() {
        override val arch: AsrModelArch = AsrModelArch.ONLINE_TRANSDUCER
    }

    data class OnlineParaformer(
        override val rootDir: File,
        val encoderPath: String,
        val decoderPath: String,
        val tokensPath: String,
        override val sileroVadPath: String? = null
    ) : ResolvedAsrModelFiles() {
        override val arch: AsrModelArch = AsrModelArch.ONLINE_PARAFORMER
    }

    data class OfflineSenseVoice(
        override val rootDir: File,
        val modelPath: String,
        val tokensPath: String,
        override val sileroVadPath: String? = null
    ) : ResolvedAsrModelFiles() {
        override val arch: AsrModelArch = AsrModelArch.OFFLINE_SENSE_VOICE
    }

    data class OfflineParaformer(
        override val rootDir: File,
        val modelPath: String,
        val tokensPath: String,
        override val sileroVadPath: String? = null
    ) : ResolvedAsrModelFiles() {
        override val arch: AsrModelArch = AsrModelArch.OFFLINE_PARAFORMER
    }

    data class OfflineFireRedCtc(
        override val rootDir: File,
        val modelPath: String,
        val tokensPath: String,
        override val sileroVadPath: String? = null
    ) : ResolvedAsrModelFiles() {
        override val arch: AsrModelArch = AsrModelArch.OFFLINE_FIRE_RED_CTC
    }

    data class OfflineFunAsrNano(
        override val rootDir: File,
        val encoderAdaptorPath: String,
        val llmPath: String,
        val embeddingPath: String,
        val tokenizerDirPath: String,
        override val sileroVadPath: String? = null
    ) : ResolvedAsrModelFiles() {
        override val arch: AsrModelArch = AsrModelArch.OFFLINE_FUNASR_NANO
    }

    data class OfflineQwen3Asr(
        override val rootDir: File,
        val convFrontendPath: String,
        val encoderPath: String,
        val decoderPath: String,
        val tokenizerDirPath: String,
        override val sileroVadPath: String? = null
    ) : ResolvedAsrModelFiles() {
        override val arch: AsrModelArch = AsrModelArch.OFFLINE_QWEN3_ASR
    }
}

/**
 * Pure Kotlin file layout resolver for all 7 [AsrModelArch] families (8 catalog models).
 *
 * - Prefers `.int8.onnx` over FP32 `.onnx` when both exist.
 * - Transparently unwraps nested archive root subdirectories (e.g., `modelDir/sherpa-onnx-.../`).
 * - Locates `silero_vad.int8.onnx` (or `silero_vad.onnx`) in the model directory, its nested root,
 *   or shared sibling/parent cache directories.
 */
object SherpaOnnxModelFileResolver {

    private val SILERO_VAD_NAMES = listOf("silero_vad.int8.onnx", "silero_vad.onnx")
    private val TOKENIZER_MARKER_FILES = setOf(
        "tokenizer.json",
        "vocab.json",
        "tokenizer_config.json",
        "merges.txt"
    )

    fun resolveForModelId(modelId: String, modelDirPath: String): Result<ResolvedAsrModelFiles> {
        val descriptor = OfflineModelCatalog.findById(modelId)
            ?: return Result.failure(IllegalArgumentException("未知的离线 ASR 模型 ID: $modelId"))
        val arch = descriptor.asrArch
            ?: return Result.failure(IllegalArgumentException("模型 $modelId 未配置 ASR 架构"))
        return resolve(modelDirPath = modelDirPath, arch = arch)
    }

    fun resolve(modelDirPath: String, arch: AsrModelArch): Result<ResolvedAsrModelFiles> {
        if (modelDirPath.isBlank()) {
            return Result.failure(IllegalArgumentException("模型目录路径不能为空"))
        }
        val baseDir = File(modelDirPath)
        if (!baseDir.exists() || !baseDir.isDirectory) {
            return Result.failure(IOException("模型目录不存在: $modelDirPath"))
        }

        val effectiveRoot = unwrapNestedModelRoot(baseDir)
        val allFiles = collectFiles(effectiveRoot, maxDepth = 4)
        val onnxFiles = allFiles.filter { isNonVadOnnx(it) }
        val sileroVad = findSileroVad(effectiveRoot, baseDir)

        return when (arch) {
            AsrModelArch.ONLINE_TRANSDUCER -> {
                val encoder = pickOnnx(onnxFiles, prefixes = listOf("encoder"))
                    ?: return missingFile(arch, "encoder*.onnx", effectiveRoot)
                val decoder = pickOnnx(onnxFiles, prefixes = listOf("decoder"))
                    ?: return missingFile(arch, "decoder*.onnx", effectiveRoot)
                val joiner = pickOnnx(onnxFiles, prefixes = listOf("joiner"))
                    ?: return missingFile(arch, "joiner*.onnx", effectiveRoot)
                val tokens = findTokensFile(allFiles)
                    ?: return missingFile(arch, "tokens.txt", effectiveRoot)
                val bpeVocab = findBpeVocabFile(allFiles)
                val modelType = inferTransducerModelType(effectiveRoot, encoder)
                Result.success(
                    ResolvedAsrModelFiles.OnlineTransducer(
                        rootDir = effectiveRoot,
                        encoderPath = encoder.absolutePath,
                        decoderPath = decoder.absolutePath,
                        joinerPath = joiner.absolutePath,
                        tokensPath = tokens.absolutePath,
                        bpeVocabPath = bpeVocab?.absolutePath,
                        modelType = modelType,
                        sileroVadPath = sileroVad?.absolutePath
                    )
                )
            }

            AsrModelArch.ONLINE_PARAFORMER -> {
                val encoder = pickOnnx(onnxFiles, prefixes = listOf("encoder"))
                    ?: return missingFile(arch, "encoder*.onnx", effectiveRoot)
                val decoder = pickOnnx(onnxFiles, prefixes = listOf("decoder"))
                    ?: return missingFile(arch, "decoder*.onnx", effectiveRoot)
                val tokens = findTokensFile(allFiles)
                    ?: return missingFile(arch, "tokens.txt", effectiveRoot)
                Result.success(
                    ResolvedAsrModelFiles.OnlineParaformer(
                        rootDir = effectiveRoot,
                        encoderPath = encoder.absolutePath,
                        decoderPath = decoder.absolutePath,
                        tokensPath = tokens.absolutePath,
                        sileroVadPath = sileroVad?.absolutePath
                    )
                )
            }

            AsrModelArch.OFFLINE_SENSE_VOICE -> {
                val model = pickSingleModelOnnx(onnxFiles)
                    ?: return missingFile(arch, "model.int8.onnx / model.onnx", effectiveRoot)
                val tokens = findTokensFile(allFiles)
                    ?: return missingFile(arch, "tokens.txt", effectiveRoot)
                Result.success(
                    ResolvedAsrModelFiles.OfflineSenseVoice(
                        rootDir = effectiveRoot,
                        modelPath = model.absolutePath,
                        tokensPath = tokens.absolutePath,
                        sileroVadPath = sileroVad?.absolutePath
                    )
                )
            }

            AsrModelArch.OFFLINE_PARAFORMER -> {
                val model = pickSingleModelOnnx(onnxFiles)
                    ?: return missingFile(arch, "model.int8.onnx / model.onnx", effectiveRoot)
                val tokens = findTokensFile(allFiles)
                    ?: return missingFile(arch, "tokens.txt", effectiveRoot)
                Result.success(
                    ResolvedAsrModelFiles.OfflineParaformer(
                        rootDir = effectiveRoot,
                        modelPath = model.absolutePath,
                        tokensPath = tokens.absolutePath,
                        sileroVadPath = sileroVad?.absolutePath
                    )
                )
            }

            AsrModelArch.OFFLINE_FIRE_RED_CTC -> {
                val model = pickSingleModelOnnx(onnxFiles)
                    ?: return missingFile(arch, "model.int8.onnx / model.onnx", effectiveRoot)
                val tokens = findTokensFile(allFiles)
                    ?: return missingFile(arch, "tokens.txt", effectiveRoot)
                Result.success(
                    ResolvedAsrModelFiles.OfflineFireRedCtc(
                        rootDir = effectiveRoot,
                        modelPath = model.absolutePath,
                        tokensPath = tokens.absolutePath,
                        sileroVadPath = sileroVad?.absolutePath
                    )
                )
            }

            AsrModelArch.OFFLINE_FUNASR_NANO -> {
                val encoderAdaptor = pickOnnx(
                    onnxFiles,
                    prefixes = listOf("encoder_adaptor", "encoder-adaptor", "encoder")
                ) ?: return missingFile(arch, "encoder_adaptor*.onnx", effectiveRoot)
                val llm = pickOnnx(onnxFiles, prefixes = listOf("llm"))
                    ?: return missingFile(arch, "llm*.onnx", effectiveRoot)
                val embedding = pickOnnx(onnxFiles, prefixes = listOf("embedding"))
                    ?: return missingFile(arch, "embedding*.onnx", effectiveRoot)
                val tokenizerDir = findTokenizerDir(
                    root = effectiveRoot,
                    preferredNames = listOf("Qwen3-0.6B", "qwen3-0.6b", "tokenizer")
                ) ?: return missingFile(arch, "tokenizer 目录 (Qwen3-0.6B / tokenizer)", effectiveRoot)
                Result.success(
                    ResolvedAsrModelFiles.OfflineFunAsrNano(
                        rootDir = effectiveRoot,
                        encoderAdaptorPath = encoderAdaptor.absolutePath,
                        llmPath = llm.absolutePath,
                        embeddingPath = embedding.absolutePath,
                        tokenizerDirPath = tokenizerDir.absolutePath,
                        sileroVadPath = sileroVad?.absolutePath
                    )
                )
            }

            AsrModelArch.OFFLINE_QWEN3_ASR -> {
                val convFrontend = pickOnnx(
                    onnxFiles,
                    prefixes = listOf("conv_frontend", "conv-frontend", "frontend")
                ) ?: return missingFile(arch, "conv_frontend*.onnx", effectiveRoot)
                val encoder = pickOnnx(
                    onnxFiles,
                    prefixes = listOf("encoder"),
                    excludeSubstrings = listOf("adaptor", "frontend")
                ) ?: return missingFile(arch, "encoder*.onnx", effectiveRoot)
                val decoder = pickOnnx(onnxFiles, prefixes = listOf("decoder"))
                    ?: return missingFile(arch, "decoder*.onnx", effectiveRoot)
                val tokenizerDir = findTokenizerDir(
                    root = effectiveRoot,
                    preferredNames = listOf("tokenizer", "Qwen3-0.6B")
                ) ?: return missingFile(arch, "tokenizer 目录", effectiveRoot)
                Result.success(
                    ResolvedAsrModelFiles.OfflineQwen3Asr(
                        rootDir = effectiveRoot,
                        convFrontendPath = convFrontend.absolutePath,
                        encoderPath = encoder.absolutePath,
                        decoderPath = decoder.absolutePath,
                        tokenizerDirPath = tokenizerDir.absolutePath,
                        sileroVadPath = sileroVad?.absolutePath
                    )
                )
            }
        }
    }

    private fun unwrapNestedModelRoot(baseDir: File): File {
        var current = baseDir
        repeat(2) {
            val children = current.listFiles().orEmpty()
            val hasDirectModelPayload = children.any { child ->
                child.isFile && (isNonVadOnnx(child) || child.name.equals("tokens.txt", ignoreCase = true))
            }
            if (hasDirectModelPayload) return current

            val candidateDirs = children.filter { it.isDirectory && !it.name.startsWith(".") }
            if (candidateDirs.isEmpty()) return current

            val dirWithOnnx = candidateDirs.firstOrNull { dir ->
                collectFiles(dir, maxDepth = 3).any { isNonVadOnnx(it) }
            } ?: return current
            current = dirWithOnnx
        }
        return current
    }

    private fun collectFiles(dir: File, maxDepth: Int): List<File> {
        val result = ArrayList<File>()
        fun walk(node: File, depth: Int) {
            if (depth > maxDepth) return
            val children = node.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isFile && child.length() > 0L) {
                    result.add(child)
                } else if (child.isDirectory) {
                    walk(child, depth + 1)
                }
            }
        }
        walk(dir, 0)
        return result
    }

    private fun isNonVadOnnx(file: File): Boolean {
        val lower = file.name.lowercase(Locale.US)
        if (!lower.endsWith(".onnx")) return false
        return !lower.contains("silero_vad") && !lower.contains("ten-vad") && !lower.contains("ten_vad")
    }

    private fun pickSingleModelOnnx(onnxFiles: List<File>): File? {
        return pickOnnx(onnxFiles, prefixes = listOf("model"))
            ?: onnxFiles.sortedWith(onnxComparator(emptyList())).firstOrNull()
    }

    private fun pickOnnx(
        onnxFiles: List<File>,
        prefixes: List<String>,
        excludeSubstrings: List<String> = emptyList()
    ): File? {
        val lowerPrefixes = prefixes.map { it.lowercase(Locale.US) }
        val lowerExcludes = excludeSubstrings.map { it.lowercase(Locale.US) }
        val matched = onnxFiles.filter { file ->
            val lower = file.name.lowercase(Locale.US)
            if (lowerExcludes.any { lower.contains(it) }) return@filter false
            lowerPrefixes.any { prefix -> lower.startsWith(prefix) || lower.contains(prefix) }
        }
        return matched.sortedWith(onnxComparator(lowerPrefixes)).firstOrNull()
    }

    private fun onnxComparator(lowerPrefixes: List<String>): Comparator<File> {
        return compareBy<File>(
            // 1. Prefer exact prefix priority (earlier prefix in list + startsWith before contains)
            { file ->
                val lower = file.name.lowercase(Locale.US)
                val exactIdx = lowerPrefixes.indexOfFirst { lower.startsWith(it) }
                if (exactIdx >= 0) exactIdx else lowerPrefixes.size + 1
            },
            // 2. Prefer int8 quantized ONNX over FP32
            { file ->
                if (file.name.lowercase(Locale.US).endsWith(".int8.onnx")) 0 else 1
            },
            // 3. Prefer shallower path & deterministic alphabetical order
            { file -> file.absolutePath.count { it == File.separatorChar } },
            { file -> file.name.lowercase(Locale.US) }
        )
    }

    private fun findTokensFile(files: List<File>): File? {
        val exact = files.filter { it.name.equals("tokens.txt", ignoreCase = true) }
            .minByOrNull { it.absolutePath.length }
        if (exact != null) return exact
        return files.filter { it.name.lowercase(Locale.US).endsWith("tokens.txt") }
            .minByOrNull { it.absolutePath.length }
    }

    private fun findBpeVocabFile(files: List<File>): File? {
        val exact = files.filter { it.name.equals("bpe.vocab", ignoreCase = true) }
            .minByOrNull { it.absolutePath.length }
        if (exact != null) return exact
        return files.filter { it.name.lowercase(Locale.US).endsWith(".vocab") }
            .minByOrNull { it.absolutePath.length }
    }

    private fun findTokenizerDir(root: File, preferredNames: List<String>): File? {
        // 1. Check explicitly named subdirectories first
        for (preferred in preferredNames) {
            val direct = File(root, preferred)
            if (direct.isDirectory && hasTokenizerPayload(direct)) {
                return direct
            }
            val caseInsensitive = root.listFiles().orEmpty().firstOrNull {
                it.isDirectory && it.name.equals(preferred, ignoreCase = true) && hasTokenizerPayload(it)
            }
            if (caseInsensitive != null) return caseInsensitive
        }
        // 2. Check any immediate subdirectory containing tokenizer files
        val anySubdir = root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .firstOrNull { hasTokenizerPayload(it) }
        if (anySubdir != null) return anySubdir

        // 3. Fallback: tokenizer files directly placed in root
        if (hasTokenizerPayload(root)) return root
        return null
    }

    private fun hasTokenizerPayload(dir: File): Boolean {
        val children = dir.listFiles().orEmpty()
        return children.any { it.isFile && it.length() > 0L && it.name.lowercase(Locale.US) in TOKENIZER_MARKER_FILES }
    }

    private fun findSileroVad(effectiveRoot: File, baseDir: File): File? {
        val searchDirs = linkedSetOf<File>()
        searchDirs.add(effectiveRoot)
        searchDirs.add(baseDir)
        baseDir.parentFile?.let { parent ->
            searchDirs.add(parent)
            searchDirs.add(File(parent, "vad"))
            parent.parentFile?.let { grandParent ->
                searchDirs.add(grandParent)
                searchDirs.add(File(grandParent, "vad"))
            }
        }

        for (vadName in SILERO_VAD_NAMES) {
            for (dir in searchDirs) {
                val candidate = File(dir, vadName)
                if (candidate.isFile && candidate.length() > 0L) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun inferTransducerModelType(rootDir: File, encoder: File): String {
        val hint = "${rootDir.name} ${encoder.name}".lowercase(Locale.US)
        return when {
            hint.contains("zipformer2") -> "zipformer2"
            hint.contains("zipformer") -> "zipformer"
            hint.contains("conformer") -> "conformer"
            hint.contains("lstm") -> "lstm"
            else -> ""
        }
    }

    private fun missingFile(arch: AsrModelArch, expected: String, root: File): Result<ResolvedAsrModelFiles> {
        return Result.failure(
            IOException("模型目录缺少 $arch 必需文件 ($expected): ${root.absolutePath}")
        )
    }
}

/**
 * Formats user hotwords into sherpa-onnx Transducer `modified_beam_search` hotwords file syntax:
 * - Each CJK Han ideograph is separated by a single space (`小鹤双拼` -> `小 鹤 双 拼`).
 * - Contiguous ASCII/Latin alphanumeric tokens stay grouped and uppercased (`TypeFree输入法` -> `TYPEFREE 输 入 法`).
 * - Blank/duplicate entries and control characters are sanitized.
 */
object SherpaOnnxHotwordsFormatter {

    fun formatHotwordLine(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val tokens = ArrayList<String>()
        val asciiBuf = StringBuilder()

        fun flushAscii() {
            if (asciiBuf.isNotEmpty()) {
                tokens.add(asciiBuf.toString().uppercase(Locale.US))
                asciiBuf.setLength(0)
            }
        }

        var idx = 0
        while (idx < trimmed.length) {
            val cp = trimmed.codePointAt(idx)
            val charCount = Character.charCount(cp)
            when {
                isCjkIdeograph(cp) -> {
                    flushAscii()
                    tokens.add(String(Character.toChars(cp)))
                }

                isAsciiWordChar(cp) -> {
                    asciiBuf.appendCodePoint(cp)
                }

                Character.isWhitespace(cp) -> {
                    flushAscii()
                }

                Character.isLetterOrDigit(cp) -> {
                    // Other non-CJK scripts (e.g., Kana/Hangul/Cyrillic) keep grouped until whitespace
                    asciiBuf.appendCodePoint(cp)
                }

                else -> {
                    // Punctuation / symbols act as token separators
                    flushAscii()
                }
            }
            idx += charCount
        }
        flushAscii()

        return tokens.joinToString(" ")
    }

    fun formatHotwordsFileContent(hotwords: List<String>): String {
        if (hotwords.isEmpty()) return ""
        val seen = LinkedHashSet<String>()
        for (raw in hotwords) {
            val formatted = formatHotwordLine(raw)
            if (formatted.isNotEmpty()) {
                seen.add(formatted)
            }
        }
        return seen.joinToString("\n")
    }

    private fun isAsciiWordChar(cp: Int): Boolean {
        return (cp in 'a'.code..'z'.code) ||
            (cp in 'A'.code..'Z'.code) ||
            (cp in '0'.code..'9'.code) ||
            cp == '\''.code
    }

    private fun isCjkIdeograph(cp: Int): Boolean {
        return (cp in 0x4E00..0x9FFF) ||
            (cp in 0x3400..0x4DBF) ||
            (cp in 0xF900..0xFAFF) ||
            (cp in 0x20000..0x2A6DF) ||
            (cp in 0x2A700..0x2B73F) ||
            (cp in 0x2B740..0x2B81F)
    }
}

data class ParsedWavAudio(
    val sampleRateHz: Int,
    val numChannels: Int,
    val bitsPerSample: Int,
    val samples: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedWavAudio) return false
        return sampleRateHz == other.sampleRateHz &&
            numChannels == other.numChannels &&
            bitsPerSample == other.bitsPerSample &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = sampleRateHz
        result = 31 * result + numChannels
        result = 31 * result + bitsPerSample
        result = 31 * result + samples.contentHashCode()
        return result
    }
}

/**
 * Pure Kotlin WAV parser supporting arbitrary RIFF chunks (`LIST`, `JUNK`, `INFO`, `fact`, `bext`, etc.),
 * odd chunk padding alignment, 16-bit signed PCM and 32-bit IEEE float, mono or multi-channel downmix,
 * and cooperative cancellation via [AtomicBoolean].
 */
object SherpaOnnxWavParser {

    private const val AUDIO_FORMAT_PCM = 1
    private const val AUDIO_FORMAT_IEEE_FLOAT = 3
    private const val READ_BUFFER_BYTES = 16 * 1024

    fun readWavFile(
        file: File,
        cancelled: AtomicBoolean = AtomicBoolean(false)
    ): Result<ParsedWavAudio> {
        if (!file.isFile || file.length() < 12L) {
            return Result.failure(IOException("WAV 文件不存在或过小: ${file.absolutePath}"))
        }
        return try {
            BufferedInputStream(FileInputStream(file), READ_BUFFER_BYTES).use { input ->
                readWavStream(input, cancelled)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun readWavStream(
        input: InputStream,
        cancelled: AtomicBoolean = AtomicBoolean(false)
    ): Result<ParsedWavAudio> {
        if (cancelled.get()) {
            return Result.failure(IOException("WAV decoding cancelled"))
        }

        val riffHeader = readExactBytes(input, 12)
            ?: return Result.failure(IOException("WAV 头部不足 12 字节"))
        val riffId = String(riffHeader, 0, 4, Charsets.US_ASCII)
        val waveId = String(riffHeader, 8, 4, Charsets.US_ASCII)
        if (riffId != "RIFF" || waveId != "WAVE") {
            return Result.failure(IOException("不支持的 WAV 头标识: $riffId/$waveId"))
        }

        var audioFormat = -1
        var numChannels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var foundFmt = false

        val chunkHeader = ByteArray(8)
        while (true) {
            if (cancelled.get()) {
                return Result.failure(IOException("WAV decoding cancelled"))
            }
            val got = readExactBytesInto(input, chunkHeader, 8)
            if (got < 8) {
                return Result.failure(IOException("WAV 文件中未找到 data 数据块"))
            }
            val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSizeUnsigned = ByteBuffer.wrap(chunkHeader, 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int.toLong() and 0xFFFF_FFFFL

            when (chunkId) {
                "fmt " -> {
                    if (chunkSizeUnsigned < 16L || chunkSizeUnsigned > 1024L * 1024L) {
                        return Result.failure(IOException("WAV fmt 块大小异常: $chunkSizeUnsigned"))
                    }
                    val fmtBytes = readExactBytes(input, chunkSizeUnsigned.toInt())
                        ?: return Result.failure(IOException("WAV fmt 块被截断"))
                    val bb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                    audioFormat = bb.short.toInt() and 0xFFFF
                    numChannels = bb.short.toInt() and 0xFFFF
                    sampleRate = bb.int
                    bb.int // byteRate
                    bb.short // blockAlign
                    bitsPerSample = bb.short.toInt() and 0xFFFF
                    foundFmt = true
                    if (chunkSizeUnsigned % 2L == 1L) {
                        skipFully(input, 1L)
                    }
                }

                "data" -> {
                    if (!foundFmt) {
                        return Result.failure(IOException("WAV data 块出现在 fmt 块之前"))
                    }
                    if (numChannels <= 0 || sampleRate <= 0) {
                        return Result.failure(IOException("WAV 声道数或采样率无效: ch=$numChannels, sr=$sampleRate"))
                    }
                    if (!isSupportedFormat(audioFormat, bitsPerSample)) {
                        return Result.failure(
                            IOException("仅支持 16-bit PCM 或 32-bit Float WAV（当前 format=$audioFormat, bits=$bitsPerSample）")
                        )
                    }
                    val samples = decodeDataChunk(
                        input = input,
                        declaredBytes = chunkSizeUnsigned,
                        audioFormat = audioFormat,
                        numChannels = numChannels,
                        bitsPerSample = bitsPerSample,
                        cancelled = cancelled
                    ) ?: return Result.failure(IOException("WAV decoding cancelled"))
                    return Result.success(
                        ParsedWavAudio(
                            sampleRateHz = sampleRate,
                            numChannels = numChannels,
                            bitsPerSample = bitsPerSample,
                            samples = samples
                        )
                    )
                }

                else -> {
                    // Skip LIST, INFO, JUNK, fact, bext, id3, etc., plus 1-byte RIFF word alignment pad if odd
                    val bytesToSkip = chunkSizeUnsigned + (chunkSizeUnsigned % 2L)
                    if (!skipFully(input, bytesToSkip)) {
                        return Result.failure(IOException("跳过 WAV 扩展块 '$chunkId' 时遇到意外 EOF"))
                    }
                }
            }
        }
    }

    private fun isSupportedFormat(audioFormat: Int, bitsPerSample: Int): Boolean {
        return (audioFormat == AUDIO_FORMAT_PCM && bitsPerSample == 16) ||
            (audioFormat == AUDIO_FORMAT_IEEE_FLOAT && bitsPerSample == 32)
    }

    private fun decodeDataChunk(
        input: InputStream,
        declaredBytes: Long,
        audioFormat: Int,
        numChannels: Int,
        bitsPerSample: Int,
        cancelled: AtomicBoolean
    ): FloatArray? {
        val bytesPerSample = bitsPerSample / 8
        val frameBytes = bytesPerSample * numChannels
        val readUntilEof = declaredBytes == 0L || declaredBytes == 0xFFFF_FFFFL
        var remaining = if (readUntilEof) Long.MAX_VALUE else declaredBytes

        val out = FloatArrayBuilder(
            initialCapacity = if (!readUntilEof && declaredBytes < 64L * 1024L * 1024L) {
                (declaredBytes / frameBytes).toInt().coerceAtLeast(256)
            } else {
                16_000
            }
        )

        val buf = ByteArray(READ_BUFFER_BYTES)
        val carry = ByteArray(frameBytes)
        var carryLen = 0

        while (remaining > 0L) {
            if (cancelled.get()) return null
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val read = input.read(buf, 0, toRead)
            if (read <= 0) break
            if (!readUntilEof) remaining -= read

            var offset = 0
            if (carryLen > 0) {
                val needed = frameBytes - carryLen
                if (read < needed) {
                    System.arraycopy(buf, 0, carry, carryLen, read)
                    carryLen += read
                    continue
                }
                System.arraycopy(buf, 0, carry, carryLen, needed)
                out.append(decodeFrameToMonoFloat(carry, 0, audioFormat, numChannels, bytesPerSample))
                carryLen = 0
                offset = needed
            }

            val fullFrames = (read - offset) / frameBytes
            for (i in 0 until fullFrames) {
                out.append(decodeFrameToMonoFloat(buf, offset, audioFormat, numChannels, bytesPerSample))
                offset += frameBytes
            }

            val leftover = read - offset
            if (leftover > 0) {
                System.arraycopy(buf, offset, carry, 0, leftover)
                carryLen = leftover
            }
        }

        return out.toFloatArray()
    }

    private fun decodeFrameToMonoFloat(
        bytes: ByteArray,
        offset: Int,
        audioFormat: Int,
        numChannels: Int,
        bytesPerSample: Int
    ): Float {
        if (numChannels == 1) {
            return decodeSingleSample(bytes, offset, audioFormat)
        }
        var sum = 0f
        var pos = offset
        for (ch in 0 until numChannels) {
            sum += decodeSingleSample(bytes, pos, audioFormat)
            pos += bytesPerSample
        }
        return sum / numChannels.toFloat()
    }

    private fun decodeSingleSample(bytes: ByteArray, offset: Int, audioFormat: Int): Float {
        return if (audioFormat == AUDIO_FORMAT_PCM) {
            val lo = bytes[offset].toInt() and 0xFF
            val hi = bytes[offset + 1].toInt()
            val pcm16 = (hi shl 8) or lo
            pcm16 / 32768.0f
        } else {
            val bits = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
            java.lang.Float.intBitsToFloat(bits).coerceIn(-1.0f, 1.0f)
        }
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray? {
        val out = ByteArray(count)
        return if (readExactBytesInto(input, out, count) == count) out else null
    }

    private fun readExactBytesInto(input: InputStream, dest: ByteArray, count: Int): Int {
        var total = 0
        while (total < count) {
            val read = input.read(dest, total, count - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun skipFully(input: InputStream, bytes: Long): Boolean {
        var remaining = bytes
        val scratch = ByteArray(4096)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                if (read < 0) return false
                remaining -= read
            }
        }
        return true
    }
}

private class FloatArrayBuilder(initialCapacity: Int = 4096) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(64))
    var size: Int = 0
        private set

    fun append(value: Float) {
        ensureCapacity(size + 1)
        data[size++] = value
    }

    fun appendAll(src: FloatArray, offset: Int = 0, length: Int = src.size - offset) {
        if (length <= 0) return
        ensureCapacity(size + length)
        System.arraycopy(src, offset, data, size, length)
        size += length
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity <= data.size) return
        var newCap = data.size * 2
        if (newCap < minCapacity) newCap = minCapacity
        data = data.copyOf(newCap)
    }

    fun toFloatArray(): FloatArray = data.copyOf(size)
}

/**
 * Real multi-architecture CPU offline ASR engine powered by open-source `sherpa-onnx` 2.0.2.
 *
 * Supports all 7 [AsrModelArch] families across the 8 catalog models:
 * - True streaming: [AsrModelArch.ONLINE_TRANSDUCER] (with native `modified_beam_search` hotwords),
 *   [AsrModelArch.ONLINE_PARAFORMER].
 * - Quasi-streaming (Silero VAD segmentation + offline decode, with graceful whole-utterance fallback
 *   when `silero_vad.int8.onnx` is absent): [AsrModelArch.OFFLINE_SENSE_VOICE] (`useInverseTextNormalization=true`),
 *   [AsrModelArch.OFFLINE_PARAFORMER], [AsrModelArch.OFFLINE_FIRE_RED_CTC],
 *   [AsrModelArch.OFFLINE_FUNASR_NANO], [AsrModelArch.OFFLINE_QWEN3_ASR].
 */
class SherpaOnnxLocalAsrEngine(
    private val threadCountProvider: () -> Int = { computeOptimalThreadCount() }
) : LocalAsrEngine {

    private val engineLock = Any()

    @Volatile
    private var currentModelId: String? = null

    @Volatile
    private var currentModelDir: String? = null

    private var currentResolved: ResolvedAsrModelFiles? = null
    private var currentHotwordsContent: String = ""
    private var tempHotwordsFile: File? = null

    private var onlineRecognizer: OnlineRecognizer? = null
    private var offlineRecognizer: OfflineRecognizer? = null
    private var sileroVad: Vad? = null

    override val loadedModelId: String?
        get() = currentModelId

    override fun loadModel(modelId: String, modelDirPath: String): Result<Unit> {
        return synchronized(engineLock) {
            loadModelLocked(modelId = modelId, modelDirPath = modelDirPath, hotwords = emptyList())
        }
    }

    override fun unload() {
        synchronized(engineLock) {
            releaseNativeHandlesLocked()
            val prev = currentModelId
            currentModelId = null
            currentModelDir = null
            currentResolved = null
            currentHotwordsContent = ""
            if (prev != null) {
                LocalAiLog.i("SherpaOnnxLocalAsrEngine unloaded modelId=$prev")
            }
        }
    }

    override fun probe(modelId: String, modelDirPath: String): Result<Unit> {
        return synchronized(engineLock) {
            val loaded = loadModelLocked(modelId = modelId, modelDirPath = modelDirPath, hotwords = emptyList())
            if (loaded.isFailure) return@synchronized loaded

            try {
                val silence = FloatArray(PROBE_SILENCE_SAMPLES) // 0.5s @ 16kHz
                val online = onlineRecognizer
                val offline = offlineRecognizer
                when {
                    online != null -> {
                        val stream = online.createStream()
                        try {
                            stream.acceptWaveform(silence, DEFAULT_SAMPLE_RATE_HZ)
                            stream.inputFinished()
                            while (online.isReady(stream)) {
                                online.decode(stream)
                            }
                            online.getResult(stream)
                        } finally {
                            stream.release()
                        }
                    }

                    offline != null -> {
                        decodeOfflineSamples(offline, silence, DEFAULT_SAMPLE_RATE_HZ)
                        sileroVad?.let { vad ->
                            vad.reset()
                            vad.acceptWaveform(FloatArray(SILERO_VAD_WINDOW_SIZE))
                            vad.reset()
                        }
                    }

                    else -> {
                        return@synchronized Result.failure(
                            IllegalStateException("模型加载后未生成有效 Recognizer: $modelId")
                        )
                    }
                }
                LocalAiLog.i("SherpaOnnxLocalAsrEngine probe succeeded: modelId=$modelId")
                Result.success(Unit)
            } catch (t: Throwable) {
                LocalAiLog.e("SherpaOnnxLocalAsrEngine probe failed: modelId=$modelId", t)
                releaseNativeHandlesLocked()
                currentModelId = null
                currentModelDir = null
                currentResolved = null
                currentHotwordsContent = ""
                Result.failure(
                    if (t is Exception) t else IllegalStateException("离线语音模型探活失败: ${t.message}", t)
                )
            }
        }
    }

    override fun transcribeStream(
        sessionId: Long,
        pcmStream: InputStream,
        params: AsrParams,
        cancelled: AtomicBoolean,
        onPartial: (finalized: String, interim: String) -> Unit
    ): Result<LocalAsrRuntimeResult> {
        val startNs = System.nanoTime()
        if (cancelled.get()) {
            return Result.failure(IOException("ASR session cancelled (sid=$sessionId)"))
        }

        return synchronized(engineLock) {
            val hotwords = params.sanitizedHotwords
            val loadRes = ensureLoadedForParamsLocked(params.modelId, params.modelDirPath, hotwords)
            if (loadRes.isFailure) {
                return@synchronized Result.failure(loadRes.exceptionOrNull()!!)
            }

            val sampleRate = params.sampleRateHz.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE_HZ
            val online = onlineRecognizer
            val offline = offlineRecognizer

            try {
                val finalText = when {
                    online != null -> runOnlineStreaming(
                        sessionId = sessionId,
                        recognizer = online,
                        pcmStream = pcmStream,
                        sampleRate = sampleRate,
                        cancelled = cancelled,
                        onPartial = onPartial
                    )

                    offline != null -> runOfflineQuasiStreaming(
                        sessionId = sessionId,
                        recognizer = offline,
                        vad = sileroVad,
                        pcmStream = pcmStream,
                        sampleRate = sampleRate,
                        cancelled = cancelled,
                        onPartial = onPartial
                    )

                    else -> return@synchronized Result.failure(
                        IllegalStateException("ASR 引擎未就绪: ${params.modelId}")
                    )
                }

                if (cancelled.get()) {
                    return@synchronized Result.failure(IOException("ASR session cancelled (sid=$sessionId)"))
                }

                val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                Result.success(
                    LocalAsrRuntimeResult(
                        text = finalText,
                        latencyMs = latencyMs,
                        isMock = false
                    )
                )
            } catch (e: CancellationAbortException) {
                Result.failure(IOException("ASR session cancelled (sid=$sessionId)", e))
            } catch (t: Throwable) {
                if (cancelled.get()) {
                    Result.failure(IOException("ASR session cancelled (sid=$sessionId)", t))
                } else {
                    LocalAiLog.e("SherpaOnnx transcribeStream failed (sid=$sessionId)", t)
                    Result.failure(if (t is Exception) t else IllegalStateException("离线语音转写失败: ${t.message}", t))
                }
            }
        }
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
        val wavFile = File(wavPath)
        val parsedRes = SherpaOnnxWavParser.readWavFile(wavFile, cancelled)
        if (parsedRes.isFailure) {
            return Result.failure(parsedRes.exceptionOrNull()!!)
        }
        val wav = parsedRes.getOrThrow()
        if (cancelled.get()) {
            return Result.failure(IOException("File transcription cancelled (rid=$requestId)"))
        }

        return synchronized(engineLock) {
            val loadRes = ensureLoadedForParamsLocked(params.modelId, params.modelDirPath, params.sanitizedHotwords)
            if (loadRes.isFailure) {
                return@synchronized Result.failure(loadRes.exceptionOrNull()!!)
            }

            try {
                val online = onlineRecognizer
                val offline = offlineRecognizer
                val finalText = when {
                    online != null -> transcribeSamplesOnline(
                        recognizer = online,
                        samples = wav.samples,
                        sampleRate = wav.sampleRateHz,
                        cancelled = cancelled,
                        onPartial = onPartial
                    )

                    offline != null -> transcribeSamplesOffline(
                        recognizer = offline,
                        vad = sileroVad,
                        samples = wav.samples,
                        sampleRate = wav.sampleRateHz,
                        cancelled = cancelled,
                        onPartial = onPartial
                    )

                    else -> return@synchronized Result.failure(
                        IllegalStateException("ASR 引擎未就绪: ${params.modelId}")
                    )
                }

                if (cancelled.get()) {
                    return@synchronized Result.failure(IOException("File transcription cancelled (rid=$requestId)"))
                }

                val latencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                Result.success(
                    LocalAsrRuntimeResult(
                        text = finalText,
                        latencyMs = latencyMs,
                        isMock = false
                    )
                )
            } catch (e: CancellationAbortException) {
                Result.failure(IOException("File transcription cancelled (rid=$requestId)", e))
            } catch (t: Throwable) {
                if (cancelled.get()) {
                    Result.failure(IOException("File transcription cancelled (rid=$requestId)", t))
                } else {
                    LocalAiLog.e("SherpaOnnx transcribeFile failed (rid=$requestId)", t)
                    Result.failure(if (t is Exception) t else IllegalStateException("离线文件转写失败: ${t.message}", t))
                }
            }
        }
    }

    private fun ensureLoadedForParamsLocked(
        modelId: String,
        modelDirPath: String,
        hotwords: List<String>
    ): Result<Unit> {
        val arch = OfflineModelCatalog.findById(modelId)?.asrArch
        val desiredHotwordsContent = if (arch == AsrModelArch.ONLINE_TRANSDUCER) {
            SherpaOnnxHotwordsFormatter.formatHotwordsFileContent(hotwords)
        } else {
            ""
        }

        val alreadyLoaded = currentModelId == modelId &&
            currentModelDir == modelDirPath &&
            (onlineRecognizer != null || offlineRecognizer != null) &&
            currentHotwordsContent == desiredHotwordsContent

        if (alreadyLoaded) {
            return Result.success(Unit)
        }
        return loadModelLocked(modelId = modelId, modelDirPath = modelDirPath, hotwords = hotwords)
    }

    private fun loadModelLocked(
        modelId: String,
        modelDirPath: String,
        hotwords: List<String>
    ): Result<Unit> {
        val resolvedRes = SherpaOnnxModelFileResolver.resolveForModelId(modelId, modelDirPath)
        if (resolvedRes.isFailure) {
            return Result.failure(resolvedRes.exceptionOrNull()!!)
        }
        val resolved = resolvedRes.getOrThrow()

        // Release previous native handles BEFORE allocating new model to avoid 2x RAM peak
        releaseNativeHandlesLocked()
        currentModelId = null
        currentModelDir = null
        currentResolved = null
        currentHotwordsContent = ""

        val numThreads = threadCountProvider().coerceIn(2, 4)

        return try {
            when (resolved) {
                is ResolvedAsrModelFiles.OnlineTransducer -> {
                    val formattedHotwords = SherpaOnnxHotwordsFormatter.formatHotwordsFileContent(hotwords)
                    val hotwordsFile = if (formattedHotwords.isNotEmpty()) {
                        writeTempHotwordsFile(formattedHotwords)
                    } else {
                        null
                    }
                    tempHotwordsFile = hotwordsFile
                    val useHotwords = hotwordsFile != null

                    val modelingUnit = when {
                        useHotwords && resolved.bpeVocabPath != null -> "cjkchar+bpe"
                        useHotwords -> "cjkchar"
                        else -> ""
                    }
                    val bpeVocab = if (useHotwords && resolved.bpeVocabPath != null) {
                        resolved.bpeVocabPath
                    } else {
                        ""
                    }

                    val config = OnlineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = DEFAULT_SAMPLE_RATE_HZ, featureDim = 80),
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = resolved.encoderPath,
                                decoder = resolved.decoderPath,
                                joiner = resolved.joinerPath
                            ),
                            tokens = resolved.tokensPath,
                            numThreads = numThreads,
                            debug = false,
                            provider = "cpu",
                            modelType = resolved.modelType,
                            modelingUnit = modelingUnit,
                            bpeVocab = bpeVocab
                        ),
                        endpointConfig = buildDefaultEndpointConfig(),
                        enableEndpoint = true,
                        decodingMethod = if (useHotwords) "modified_beam_search" else "greedy_search",
                        maxActivePaths = 4,
                        hotwordsFile = hotwordsFile?.absolutePath.orEmpty(),
                        hotwordsScore = DEFAULT_HOTWORDS_SCORE
                    )
                    onlineRecognizer = OnlineRecognizer(assetManager = null, config = config)
                    currentHotwordsContent = formattedHotwords
                }

                is ResolvedAsrModelFiles.OnlineParaformer -> {
                    val config = OnlineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = DEFAULT_SAMPLE_RATE_HZ, featureDim = 80),
                        modelConfig = OnlineModelConfig(
                            paraformer = OnlineParaformerModelConfig(
                                encoder = resolved.encoderPath,
                                decoder = resolved.decoderPath
                            ),
                            tokens = resolved.tokensPath,
                            numThreads = numThreads,
                            debug = false,
                            provider = "cpu",
                            modelType = "paraformer"
                        ),
                        endpointConfig = buildDefaultEndpointConfig(),
                        enableEndpoint = true,
                        decodingMethod = "greedy_search"
                    )
                    onlineRecognizer = OnlineRecognizer(assetManager = null, config = config)
                }

                is ResolvedAsrModelFiles.OfflineSenseVoice -> {
                    val config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = DEFAULT_SAMPLE_RATE_HZ, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(
                                model = resolved.modelPath,
                                language = "",
                                useInverseTextNormalization = true
                            ),
                            tokens = resolved.tokensPath,
                            numThreads = numThreads,
                            debug = false,
                            provider = "cpu"
                        ),
                        decodingMethod = "greedy_search"
                    )
                    offlineRecognizer = OfflineRecognizer(assetManager = null, config = config)
                    sileroVad = createSileroVadIfPresent(resolved.sileroVadPath)
                }

                is ResolvedAsrModelFiles.OfflineParaformer -> {
                    val config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = DEFAULT_SAMPLE_RATE_HZ, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            paraformer = OfflineParaformerModelConfig(
                                model = resolved.modelPath
                            ),
                            tokens = resolved.tokensPath,
                            numThreads = numThreads,
                            debug = false,
                            provider = "cpu",
                            modelType = "paraformer"
                        ),
                        decodingMethod = "greedy_search"
                    )
                    offlineRecognizer = OfflineRecognizer(assetManager = null, config = config)
                    sileroVad = createSileroVadIfPresent(resolved.sileroVadPath)
                }

                is ResolvedAsrModelFiles.OfflineFireRedCtc -> {
                    val config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = DEFAULT_SAMPLE_RATE_HZ, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            fireRedAsrCtc = OfflineFireRedAsrCtcModelConfig(
                                model = resolved.modelPath
                            ),
                            tokens = resolved.tokensPath,
                            numThreads = numThreads,
                            debug = false,
                            provider = "cpu"
                        ),
                        decodingMethod = "greedy_search"
                    )
                    offlineRecognizer = OfflineRecognizer(assetManager = null, config = config)
                    sileroVad = createSileroVadIfPresent(resolved.sileroVadPath)
                }

                is ResolvedAsrModelFiles.OfflineFunAsrNano -> {
                    val config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = DEFAULT_SAMPLE_RATE_HZ, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            funasrNano = OfflineFunAsrNanoModelConfig(
                                encoderAdaptor = resolved.encoderAdaptorPath,
                                llm = resolved.llmPath,
                                embedding = resolved.embeddingPath,
                                tokenizer = resolved.tokenizerDirPath,
                                itn = true
                            ),
                            tokens = "",
                            numThreads = numThreads,
                            debug = false,
                            provider = "cpu"
                        ),
                        decodingMethod = "greedy_search"
                    )
                    offlineRecognizer = OfflineRecognizer(assetManager = null, config = config)
                    sileroVad = createSileroVadIfPresent(resolved.sileroVadPath)
                }

                is ResolvedAsrModelFiles.OfflineQwen3Asr -> {
                    val config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = DEFAULT_SAMPLE_RATE_HZ, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            qwen3Asr = OfflineQwen3AsrModelConfig(
                                convFrontend = resolved.convFrontendPath,
                                encoder = resolved.encoderPath,
                                decoder = resolved.decoderPath,
                                tokenizer = resolved.tokenizerDirPath
                            ),
                            tokens = "",
                            numThreads = numThreads,
                            debug = false,
                            provider = "cpu"
                        ),
                        decodingMethod = "greedy_search"
                    )
                    offlineRecognizer = OfflineRecognizer(assetManager = null, config = config)
                    sileroVad = createSileroVadIfPresent(resolved.sileroVadPath)
                }
            }

            currentModelId = modelId
            currentModelDir = modelDirPath
            currentResolved = resolved
            LocalAiLog.i(
                "SherpaOnnxLocalAsrEngine loaded modelId=$modelId, arch=${resolved.arch}, " +
                    "threads=$numThreads, hasVad=${sileroVad != null}, hotwords=${currentHotwordsContent.isNotEmpty()}"
            )
            Result.success(Unit)
        } catch (t: Throwable) {
            releaseNativeHandlesLocked()
            LocalAiLog.e("SherpaOnnxLocalAsrEngine failed to load modelId=$modelId", t)
            Result.failure(if (t is Exception) t else IllegalStateException("离线 ASR 模型加载失败: ${t.message}", t))
        }
    }

    private fun runOnlineStreaming(
        sessionId: Long,
        recognizer: OnlineRecognizer,
        pcmStream: InputStream,
        sampleRate: Int,
        cancelled: AtomicBoolean,
        onPartial: (finalized: String, interim: String) -> Unit
    ): String {
        val stream = recognizer.createStream()
        val finalizedBuilder = StringBuilder()
        var lastEmittedFinalized = ""
        var lastEmittedInterim = ""

        fun emitIfChanged(interim: String) {
            val fin = finalizedBuilder.toString()
            if (fin != lastEmittedFinalized || interim != lastEmittedInterim) {
                lastEmittedFinalized = fin
                lastEmittedInterim = interim
                onPartial(fin, interim)
            }
        }

        val byteBuffer = ByteArray(STREAM_READ_CHUNK_BYTES)
        var hasCarryByte = false
        var carryByte: Byte = 0

        return try {
            while (true) {
                if (cancelled.get()) throw CancellationAbortException("sid=$sessionId")
                val read = pcmStream.read(byteBuffer)
                if (read < 0) break
                if (read == 0) continue
                if (cancelled.get()) throw CancellationAbortException("sid=$sessionId")

                val samples = pcm16LeBytesToFloats(
                    bytes = byteBuffer,
                    length = read,
                    hasCarryByte = hasCarryByte,
                    carryByte = carryByte
                ) { nextHasCarry, nextCarry ->
                    hasCarryByte = nextHasCarry
                    carryByte = nextCarry
                }
                if (samples.isEmpty()) continue

                stream.acceptWaveform(samples, sampleRate)
                while (recognizer.isReady(stream)) {
                    if (cancelled.get()) throw CancellationAbortException("sid=$sessionId")
                    recognizer.decode(stream)
                }

                val currentText = recognizer.getResult(stream).text.trim()
                if (recognizer.isEndpoint(stream)) {
                    if (currentText.isNotEmpty()) {
                        appendSegmentText(finalizedBuilder, currentText)
                    }
                    recognizer.reset(stream)
                    emitIfChanged("")
                } else {
                    emitIfChanged(currentText)
                }
            }

            if (cancelled.get()) throw CancellationAbortException("sid=$sessionId")

            // Append short tail silence so streaming transducer/paraformer flushes final syllables cleanly
            val tailPadding = FloatArray((sampleRate * STREAM_TAIL_SILENCE_SEC).toInt().coerceAtLeast(1600))
            stream.acceptWaveform(tailPadding, sampleRate)
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                if (cancelled.get()) throw CancellationAbortException("sid=$sessionId")
                recognizer.decode(stream)
            }

            val tailText = recognizer.getResult(stream).text.trim()
            if (tailText.isNotEmpty()) {
                appendSegmentText(finalizedBuilder, tailText)
            }
            val finalText = finalizedBuilder.toString()
            if (finalText.isNotEmpty()) {
                emitIfChanged("")
            }
            finalText
        } finally {
            runCatching { stream.release() }
        }
    }

    private fun runOfflineQuasiStreaming(
        sessionId: Long,
        recognizer: OfflineRecognizer,
        vad: Vad?,
        pcmStream: InputStream,
        sampleRate: Int,
        cancelled: AtomicBoolean,
        onPartial: (finalized: String, interim: String) -> Unit
    ): String {
        val byteBuffer = ByteArray(STREAM_READ_CHUNK_BYTES)
        var hasCarryByte = false
        var carryByte: Byte = 0

        if (vad == null || sampleRate != DEFAULT_SAMPLE_RATE_HZ) {
            // Fallback: buffer PCM until mic release, then run single offline decode
            val allSamples = FloatArrayBuilder(sampleRate * 4)
            val maxSamples = sampleRate * MAX_BUFFERED_AUDIO_SEC
            while (true) {
                if (cancelled.get()) throw CancellationAbortException("sid=$sessionId")
                val read = pcmStream.read(byteBuffer)
                if (read < 0) break
                if (read == 0) continue

                val samples = pcm16LeBytesToFloats(
                    bytes = byteBuffer,
                    length = read,
                    hasCarryByte = hasCarryByte,
                    carryByte = carryByte
                ) { nextHasCarry, nextCarry ->
                    hasCarryByte = nextHasCarry
                    carryByte = nextCarry
                }
                val spaceLeft = maxSamples - allSamples.size
                if (spaceLeft > 0 && samples.isNotEmpty()) {
                    allSamples.appendAll(samples, 0, minOf(samples.size, spaceLeft))
                }
            }
            if (cancelled.get()) throw CancellationAbortException("sid=$sessionId")
            val audio = allSamples.toFloatArray()
            if (audio.isEmpty()) return ""
            val text = decodeOfflineSamples(recognizer, audio, sampleRate)
            if (text.isNotEmpty()) {
                onPartial(text, "")
            }
            return text
        }

        // Quasi-streaming with Silero VAD: decode completed speech segments on the fly into `finalized`,
        // then flush residual speech on release.
        val finalizedBuilder = StringBuilder()
        val fallbackRawBuffer = FloatArrayBuilder(DEFAULT_SAMPLE_RATE_HZ * 4)
        val fallbackCapSamples = DEFAULT_SAMPLE_RATE_HZ * VAD_FALLBACK_MAX_SEC
        val vadWindow = FloatArray(SILERO_VAD_WINDOW_SIZE)
        var vadWindowPos = 0

        vad.reset()
        return try {
            fun drainVadSegments() {
                while (!vad.empty()) {
                    if (cancelled.get()) throw CancellationAbortException("sid=$sessionId")
                    val segment = vad.front()
                    vad.pop()
                    val segSamples = segment.samples
                    if (segSamples.isEmpty()) continue
                    val segText = decodeOfflineSamples(recognizer, segSamples, DEFAULT_SAMPLE_RATE_HZ)
                    if (segText.isNotEmpty()) {
                        appendSegmentText(finalizedBuilder, segText)
                        onPartial(finalizedBuilder.toString(), "")
                    }
                }
            }

            while (true) {
                if (cancelled.get()) throw CancellationAbortException("sid=$sessionId")
                val read = pcmStream.read(byteBuffer)
                if (read < 0) break
                if (read == 0) continue
                if (cancelled.get()) throw CancellationAbortException("sid=$sessionId")

                val samples = pcm16LeBytesToFloats(
                    bytes = byteBuffer,
                    length = read,
                    hasCarryByte = hasCarryByte,
                    carryByte = carryByte
                ) { nextHasCarry, nextCarry ->
                    hasCarryByte = nextHasCarry
                    carryByte = nextCarry
                }
                if (samples.isEmpty()) continue

                if (finalizedBuilder.isEmpty() && fallbackRawBuffer.size < fallbackCapSamples) {
                    val toCopy = minOf(samples.size, fallbackCapSamples - fallbackRawBuffer.size)
                    fallbackRawBuffer.appendAll(samples, 0, toCopy)
                }

                var offset = 0
                while (offset < samples.size) {
                    val toFill = minOf(SILERO_VAD_WINDOW_SIZE - vadWindowPos, samples.size - offset)
                    System.arraycopy(samples, offset, vadWindow, vadWindowPos, toFill)
                    vadWindowPos += toFill
                    offset += toFill
                    if (vadWindowPos == SILERO_VAD_WINDOW_SIZE) {
                        vad.acceptWaveform(vadWindow)
                        vadWindowPos = 0
                        drainVadSegments()
                    }
                }
            }

            if (cancelled.get()) throw CancellationAbortException("sid=$sessionId")

            if (vadWindowPos > 0) {
                vadWindow.fill(0f, vadWindowPos, SILERO_VAD_WINDOW_SIZE)
                vad.acceptWaveform(vadWindow)
                vadWindowPos = 0
            }
            vad.flush()
            drainVadSegments()

            // If utterance was very short/quiet and VAD emitted zero segments, decode raw buffer as safety net
            if (finalizedBuilder.isEmpty() && fallbackRawBuffer.size >= MIN_FALLBACK_SAMPLES) {
                val fallbackText = decodeOfflineSamples(
                    recognizer = recognizer,
                    samples = fallbackRawBuffer.toFloatArray(),
                    sampleRate = DEFAULT_SAMPLE_RATE_HZ
                )
                if (fallbackText.isNotEmpty()) {
                    appendSegmentText(finalizedBuilder, fallbackText)
                    onPartial(finalizedBuilder.toString(), "")
                }
            }
            finalizedBuilder.toString()
        } finally {
            runCatching { vad.reset() }
        }
    }

    private fun transcribeSamplesOnline(
        recognizer: OnlineRecognizer,
        samples: FloatArray,
        sampleRate: Int,
        cancelled: AtomicBoolean,
        onPartial: (finalized: String, interim: String) -> Unit
    ): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        val finalizedBuilder = StringBuilder()
        val chunkSamples = sampleRate.coerceAtLeast(8_000)

        return try {
            var offset = 0
            while (offset < samples.size) {
                if (cancelled.get()) throw CancellationAbortException("file-online")
                val end = minOf(offset + chunkSamples, samples.size)
                val slice = samples.copyOfRange(offset, end)
                offset = end

                stream.acceptWaveform(slice, sampleRate)
                while (recognizer.isReady(stream)) {
                    if (cancelled.get()) throw CancellationAbortException("file-online")
                    recognizer.decode(stream)
                }

                val currentText = recognizer.getResult(stream).text.trim()
                if (recognizer.isEndpoint(stream)) {
                    if (currentText.isNotEmpty()) {
                        appendSegmentText(finalizedBuilder, currentText)
                    }
                    recognizer.reset(stream)
                    onPartial(finalizedBuilder.toString(), "")
                } else if (currentText.isNotEmpty()) {
                    onPartial(finalizedBuilder.toString(), currentText)
                }
            }

            if (cancelled.get()) throw CancellationAbortException("file-online")
            val tailPadding = FloatArray((sampleRate * STREAM_TAIL_SILENCE_SEC).toInt().coerceAtLeast(1600))
            stream.acceptWaveform(tailPadding, sampleRate)
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                if (cancelled.get()) throw CancellationAbortException("file-online")
                recognizer.decode(stream)
            }
            val tailText = recognizer.getResult(stream).text.trim()
            if (tailText.isNotEmpty()) {
                appendSegmentText(finalizedBuilder, tailText)
            }
            val finalText = finalizedBuilder.toString()
            if (finalText.isNotEmpty()) {
                onPartial(finalText, "")
            }
            finalText
        } finally {
            runCatching { stream.release() }
        }
    }

    private fun transcribeSamplesOffline(
        recognizer: OfflineRecognizer,
        vad: Vad?,
        samples: FloatArray,
        sampleRate: Int,
        cancelled: AtomicBoolean,
        onPartial: (finalized: String, interim: String) -> Unit
    ): String {
        if (samples.isEmpty()) return ""
        val longFileThreshold = DEFAULT_SAMPLE_RATE_HZ * LONG_FILE_VAD_THRESHOLD_SEC
        if (vad != null && sampleRate == DEFAULT_SAMPLE_RATE_HZ && samples.size > longFileThreshold) {
            val finalizedBuilder = StringBuilder()
            vad.reset()
            return try {
                var offset = 0
                while (offset < samples.size) {
                    if (cancelled.get()) throw CancellationAbortException("file-offline-vad")
                    val remaining = samples.size - offset
                    val frame = if (remaining >= SILERO_VAD_WINDOW_SIZE) {
                        samples.copyOfRange(offset, offset + SILERO_VAD_WINDOW_SIZE)
                    } else {
                        FloatArray(SILERO_VAD_WINDOW_SIZE).also {
                            System.arraycopy(samples, offset, it, 0, remaining)
                        }
                    }
                    offset += SILERO_VAD_WINDOW_SIZE
                    vad.acceptWaveform(frame)

                    while (!vad.empty()) {
                        if (cancelled.get()) throw CancellationAbortException("file-offline-vad")
                        val seg = vad.front()
                        vad.pop()
                        val text = decodeOfflineSamples(recognizer, seg.samples, DEFAULT_SAMPLE_RATE_HZ)
                        if (text.isNotEmpty()) {
                            appendSegmentText(finalizedBuilder, text)
                            onPartial(finalizedBuilder.toString(), "")
                        }
                    }
                }
                vad.flush()
                while (!vad.empty()) {
                    if (cancelled.get()) throw CancellationAbortException("file-offline-vad")
                    val seg = vad.front()
                    vad.pop()
                    val text = decodeOfflineSamples(recognizer, seg.samples, DEFAULT_SAMPLE_RATE_HZ)
                    if (text.isNotEmpty()) {
                        appendSegmentText(finalizedBuilder, text)
                        onPartial(finalizedBuilder.toString(), "")
                    }
                }
                if (finalizedBuilder.isEmpty()) {
                    val fallback = decodeOfflineSamples(recognizer, samples, sampleRate)
                    if (fallback.isNotEmpty()) {
                        appendSegmentText(finalizedBuilder, fallback)
                        onPartial(finalizedBuilder.toString(), "")
                    }
                }
                finalizedBuilder.toString()
            } finally {
                runCatching { vad.reset() }
            }
        }

        if (cancelled.get()) throw CancellationAbortException("file-offline")
        val text = decodeOfflineSamples(recognizer, samples, sampleRate)
        if (text.isNotEmpty()) {
            onPartial(text, "")
        }
        return text
    }

    private fun decodeOfflineSamples(
        recognizer: OfflineRecognizer,
        samples: FloatArray,
        sampleRate: Int
    ): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            val raw = recognizer.getResult(stream).text
            SENSE_VOICE_TAG_REGEX.replace(raw, "").trim()
        } finally {
            runCatching { stream.release() }
        }
    }

    private fun createSileroVadIfPresent(vadPath: String?): Vad? {
        if (vadPath.isNullOrBlank()) return null
        val file = File(vadPath)
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadPath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = SILERO_VAD_WINDOW_SIZE,
                    maxSpeechDuration = 15.0f
                ),
                sampleRate = DEFAULT_SAMPLE_RATE_HZ,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
            Vad(assetManager = null, config = vadConfig)
        } catch (t: Throwable) {
            LocalAiLog.w("Failed to initialize Silero VAD at $vadPath; falling back to buffered offline ASR", t)
            null
        }
    }

    private fun buildDefaultEndpointConfig(): EndpointConfig {
        return EndpointConfig(
            rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0.0f),
            rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.2f, minUtteranceLength = 0.0f),
            rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.0f, minUtteranceLength = 20.0f)
        )
    }

    private fun writeTempHotwordsFile(content: String): File {
        val file = File.createTempFile("typefree_asr_hotwords_", ".txt")
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    private fun releaseNativeHandlesLocked() {
        onlineRecognizer?.let { rec ->
            runCatching { rec.release() }
        }
        onlineRecognizer = null

        offlineRecognizer?.let { rec ->
            runCatching { rec.release() }
        }
        offlineRecognizer = null

        sileroVad?.let { vad ->
            runCatching { vad.release() }
        }
        sileroVad = null

        tempHotwordsFile?.let { file ->
            runCatching { file.delete() }
        }
        tempHotwordsFile = null
    }

    private class CancellationAbortException(message: String) : RuntimeException(message)

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000
        const val PROBE_SILENCE_SAMPLES: Int = 8_000 // 0.5s @ 16kHz
        const val SILERO_VAD_WINDOW_SIZE: Int = 512
        const val STREAM_READ_CHUNK_BYTES: Int = 3_200 // 100ms @ 16kHz 16-bit mono
        const val DEFAULT_HOTWORDS_SCORE: Float = 1.5f

        private const val STREAM_TAIL_SILENCE_SEC: Float = 0.4f
        private const val MAX_BUFFERED_AUDIO_SEC: Int = 120
        private const val VAD_FALLBACK_MAX_SEC: Int = 30
        private const val MIN_FALLBACK_SAMPLES: Int = 2_400 // 150ms @ 16kHz
        private const val LONG_FILE_VAD_THRESHOLD_SEC: Int = 15
        private val SENSE_VOICE_TAG_REGEX = Regex("<\\|[^|>]+\\|>")

        internal fun computeOptimalThreadCount(
            availableProcessors: Int = Runtime.getRuntime().availableProcessors()
        ): Int {
            return (availableProcessors / 2).coerceIn(2, 4)
        }

        internal fun pcm16LeBytesToFloats(
            bytes: ByteArray,
            length: Int,
            hasCarryByte: Boolean,
            carryByte: Byte,
            updateCarry: (Boolean, Byte) -> Unit
        ): FloatArray {
            if (length <= 0) {
                updateCarry(hasCarryByte, carryByte)
                return FloatArray(0)
            }
            val totalBytes = length + if (hasCarryByte) 1 else 0
            val sampleCount = totalBytes / 2
            if (sampleCount == 0) {
                updateCarry(true, bytes[0])
                return FloatArray(0)
            }

            val samples = FloatArray(sampleCount)
            var outIdx = 0
            var byteIdx = 0
            if (hasCarryByte) {
                val lo = carryByte.toInt() and 0xFF
                val hi = bytes[0].toInt()
                samples[outIdx++] = ((hi shl 8) or lo) / 32768.0f
                byteIdx = 1
            }
            while (byteIdx + 1 < length) {
                val lo = bytes[byteIdx].toInt() and 0xFF
                val hi = bytes[byteIdx + 1].toInt()
                samples[outIdx++] = ((hi shl 8) or lo) / 32768.0f
                byteIdx += 2
            }
            if (byteIdx < length) {
                updateCarry(true, bytes[byteIdx])
            } else {
                updateCarry(false, 0)
            }
            return samples
        }

        internal fun appendSegmentText(builder: StringBuilder, segment: String) {
            val cleaned = segment.trim()
            if (cleaned.isEmpty()) return
            if (builder.isNotEmpty()) {
                val lastChar = builder[builder.length - 1]
                val firstChar = cleaned[0]
                if (isAsciiAlphaNum(lastChar) && isAsciiAlphaNum(firstChar)) {
                    builder.append(' ')
                }
            }
            builder.append(cleaned)
        }

        private fun isAsciiAlphaNum(c: Char): Boolean {
            return (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')
        }
    }
}
