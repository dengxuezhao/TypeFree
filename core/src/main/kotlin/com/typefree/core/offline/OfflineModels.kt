package com.typefree.core.offline

enum class OfflineMode {
    OFF,
    HYBRID,
    OFFLINE_ONLY
}

enum class LocalModelSlot {
    ASR,
    SLM
}

enum class AsrModelArch {
    ONLINE_TRANSDUCER,
    ONLINE_PARAFORMER,
    OFFLINE_SENSE_VOICE,
    OFFLINE_PARAFORMER,
    OFFLINE_FIRE_RED_CTC,
    OFFLINE_FUNASR_NANO,
    OFFLINE_QWEN3_ASR
}

enum class AsrTierGroup {
    COMPACT_FAST,
    LARGE_ACCURATE
}

enum class LocalModelInstallStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    INSTALLED,
    CORRUPTED,
    UNSUPPORTED_ON_DEVICE
}

enum class RamTier(
    val label: String,
    val minTotalRamMb: Long
) {
    LOW_6GB("4–6GB 档", 0L),
    MID_8GB("8GB 档", 7200L),
    HIGH_12GB_PLUS("12GB+ 档", 10500L)
}

enum class RamCompatibility {
    RECOMMENDED,
    TIGHT,
    RISK_INSUFFICIENT
}

enum class AiTask {
    DICTATE,
    SPEAK_TO_EDIT,
    HELP_ME_WRITE,
    TRANSLATE,
    JEV_COPILOT,
    ASK_AI_CARD,
    TEXT_TRANSFORM,
    NOTE_TRANSCRIBE,
    SENTENCE_COMPLETION,
    BUBBLE_COPILOT
}

enum class ExecutionSource {
    CLOUD,
    LOCAL,
    HYBRID_LOCAL_ASR_CLOUD_LLM,
    LOCAL_RAW_PASSTHROUGH,
    BLOCKED
}

data class LocalModelDescriptor(
    val id: String,
    val slot: LocalModelSlot,
    val displayName: String,
    val subtitle: String,
    val sizeBytes: Long,
    val approxRamMb: Int,
    val minRamMb: Int,
    val isProOnly: Boolean,
    val asrArch: AsrModelArch? = null,
    val asrTierGroup: AsrTierGroup? = null,
    val supportsTrueStreaming: Boolean = false,
    val supportsNativeHotwords: Boolean = false,
    val isDefaultRecommended: Boolean = false,
    val isPendingVerification: Boolean = false,
    val primaryTasks: Set<AiTask> = emptySet(),
    val fallbackTasks: Set<AiTask> = emptySet(),
    val promptTemplateVersion: Int = 1,
    val minEngineVersion: Int = 1,
    val downloadUrl: String = "",
    val expectedSha256: String = "",
    val mirrorUrls: List<String> = emptyList(),
    val isTarBz2Archive: Boolean = false,
    val requiresSileroVad: Boolean = false
)

object OfflineModelCatalog {

    const val CURRENT_ENGINE_VERSION: Int = 1
    const val CURRENT_PROMPT_TEMPLATE_VERSION: Int = 1
    const val IDLE_UNLOAD_TIMEOUT_SEC: Int = 180
    const val KEEP_SINGLE_MODEL_PER_SLOT: Boolean = false

    const val SHERPA_ASR_RELEASE_BASE_URL: String =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    const val SILERO_VAD_FILE_NAME: String = "silero_vad.int8.onnx"
    const val SILERO_VAD_URL: String =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.int8.onnx"

    private fun githubReleaseMirrors(githubUrl: String): List<String> = listOf(
        "https://gh-proxy.com/$githubUrl",
        "https://ghfast.top/$githubUrl"
    )

    val SILERO_VAD_MIRROR_URLS: List<String> = githubReleaseMirrors(SILERO_VAD_URL)

    const val ID_ASR_ZIPFORMER_ZH_14M: String = "asr_zipformer_zh_14m"
    const val ID_ASR_PARAFORMER_ZH_SMALL: String = "asr_paraformer_zh_small"
    const val ID_ASR_ZIPFORMER_BILINGUAL: String = "asr_zipformer_bilingual"
    const val ID_ASR_SENSEVOICE_SMALL: String = "asr_sensevoice_small"
    const val ID_ASR_STREAMING_PARAFORMER_ZH_EN: String = "asr_streaming_paraformer_zh_en"
    const val ID_ASR_FIRERED2_CTC_ZH_EN: String = "asr_firered2_ctc_zh_en"
    const val ID_ASR_FUNASR_NANO: String = "asr_funasr_nano"
    const val ID_ASR_QWEN3_ASR_0_6B: String = "asr_qwen3_asr_0_6b"

    const val ID_SLM_CORE_0_8B: String = "slm_core_0_8b"
    const val ID_SLM_PLUS_2B: String = "slm_plus_2b"
    const val ID_SLM_PRO_4B: String = "slm_pro_4b"

    private const val MB: Long = 1024L * 1024L
    private const val MID_8GB_SERIAL_THRESHOLD_MB: Int = 2400
    private const val LOW_6GB_SERIAL_THRESHOLD_MB: Int = 1400

    val asrModels: List<LocalModelDescriptor> = listOf(
        LocalModelDescriptor(
            id = ID_ASR_ZIPFORMER_ZH_14M,
            slot = LocalModelSlot.ASR,
            displayName = "极致迷你流式（Zipformer 14M）",
            subtitle = "体积最小、几乎零内存压力；纯中文真逐字流式与原生热词偏置，适合 4GB 老机",
            sizeBytes = 54_316_000L,
            approxRamMb = 120,
            minRamMb = 0,
            isProOnly = false,
            asrArch = AsrModelArch.ONLINE_TRANSDUCER,
            asrTierGroup = AsrTierGroup.COMPACT_FAST,
            supportsTrueStreaming = true,
            supportsNativeHotwords = true,
            downloadUrl = "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile.tar.bz2",
            mirrorUrls = githubReleaseMirrors(
                "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile.tar.bz2"
            ),
            isTarBz2Archive = true,
            requiresSileroVad = false
        ),
        LocalModelDescriptor(
            id = ID_ASR_PARAFORMER_ZH_SMALL,
            slot = LocalModelSlot.ASR,
            displayName = "超轻中文（Paraformer Small）",
            subtitle = "单文件约 78MB、中文准流式转写稳健，4–6GB 机型极低负担",
            sizeBytes = 77_909_000L,
            approxRamMb = 220,
            minRamMb = 0,
            isProOnly = false,
            asrArch = AsrModelArch.OFFLINE_PARAFORMER,
            asrTierGroup = AsrTierGroup.COMPACT_FAST,
            supportsTrueStreaming = false,
            supportsNativeHotwords = false,
            downloadUrl = "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2",
            mirrorUrls = githubReleaseMirrors(
                "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2"
            ),
            isTarBz2Archive = true,
            requiresSileroVad = true
        ),
        LocalModelDescriptor(
            id = ID_ASR_ZIPFORMER_BILINGUAL,
            slot = LocalModelSlot.ASR,
            displayName = "双语流式热词版（Zipformer 中英）",
            subtitle = "原生支持解码期热词偏置的中英双语真流式模型，边说边出字、术语命中高",
            sizeBytes = 346_974_000L,
            approxRamMb = 360,
            minRamMb = 0,
            isProOnly = false,
            asrArch = AsrModelArch.ONLINE_TRANSDUCER,
            asrTierGroup = AsrTierGroup.COMPACT_FAST,
            supportsTrueStreaming = true,
            supportsNativeHotwords = true,
            downloadUrl = "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20-mobile.tar.bz2",
            mirrorUrls = githubReleaseMirrors(
                "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20-mobile.tar.bz2"
            ),
            isTarBz2Archive = true,
            requiresSileroVad = false
        ),
        LocalModelDescriptor(
            id = ID_ASR_SENSEVOICE_SMALL,
            slot = LocalModelSlot.ASR,
            displayName = "默认均衡推荐（SenseVoiceSmall）",
            subtitle = "6GB/8GB 默认推荐：非自回归极速，支持中/粤/英/日/韩多语与自动标点",
            sizeBytes = 163_002_883L,
            approxRamMb = 420,
            minRamMb = 0,
            isProOnly = false,
            asrArch = AsrModelArch.OFFLINE_SENSE_VOICE,
            asrTierGroup = AsrTierGroup.COMPACT_FAST,
            supportsTrueStreaming = false,
            supportsNativeHotwords = false,
            isDefaultRecommended = true,
            downloadUrl = "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            mirrorUrls = githubReleaseMirrors(
                "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
            ),
            isTarBz2Archive = true,
            requiresSileroVad = true
        ),
        LocalModelDescriptor(
            id = ID_ASR_STREAMING_PARAFORMER_ZH_EN,
            slot = LocalModelSlot.ASR,
            displayName = "双语流式（Paraformer 中英）",
            subtitle = "中英混读真逐字流式体验顺滑（官方完整流式包，含中英双语权重）",
            sizeBytes = 1_047_318_000L,
            approxRamMb = 450,
            minRamMb = 0,
            isProOnly = false,
            asrArch = AsrModelArch.ONLINE_PARAFORMER,
            asrTierGroup = AsrTierGroup.COMPACT_FAST,
            supportsTrueStreaming = true,
            supportsNativeHotwords = false,
            downloadUrl = "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-streaming-paraformer-bilingual-zh-en.tar.bz2",
            mirrorUrls = githubReleaseMirrors(
                "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-streaming-paraformer-bilingual-zh-en.tar.bz2"
            ),
            isTarBz2Archive = true,
            requiresSileroVad = false
        ),
        LocalModelDescriptor(
            id = ID_ASR_FIRERED2_CTC_ZH_EN,
            slot = LocalModelSlot.ASR,
            displayName = "方言口音增强（FireRedASR2-CTC）",
            subtitle = "非自回归 CTC 架构，中英与方言口音识别强，适合 8GB+ 机型",
            sizeBytes = 520_513_000L,
            approxRamMb = 700,
            minRamMb = 7200,
            isProOnly = false,
            asrArch = AsrModelArch.OFFLINE_FIRE_RED_CTC,
            asrTierGroup = AsrTierGroup.LARGE_ACCURATE,
            supportsTrueStreaming = false,
            supportsNativeHotwords = false,
            isPendingVerification = false,
            downloadUrl = "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.tar.bz2",
            mirrorUrls = githubReleaseMirrors(
                "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.tar.bz2"
            ),
            isTarBz2Archive = true,
            requiresSileroVad = true
        ),
        LocalModelDescriptor(
            id = ID_ASR_FUNASR_NANO,
            slot = LocalModelSlot.ASR,
            displayName = "精准多方言大模型（Fun-ASR-Nano）",
            subtitle = "12GB+ 旗舰推荐（8GB 偏紧）：覆盖 7 大方言组与 26 种口音及上下文标点",
            sizeBytes = 841_692_000L,
            approxRamMb = 1250,
            minRamMb = 7200,
            isProOnly = false,
            asrArch = AsrModelArch.OFFLINE_FUNASR_NANO,
            asrTierGroup = AsrTierGroup.LARGE_ACCURATE,
            supportsTrueStreaming = false,
            supportsNativeHotwords = false,
            downloadUrl = "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-funasr-nano-int8-2025-12-30.tar.bz2",
            mirrorUrls = githubReleaseMirrors(
                "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-funasr-nano-int8-2025-12-30.tar.bz2"
            ),
            isTarBz2Archive = true,
            requiresSileroVad = true
        ),
        LocalModelDescriptor(
            id = ID_ASR_QWEN3_ASR_0_6B,
            slot = LocalModelSlot.ASR,
            displayName = "千问语音大模型（Qwen3-ASR-0.6B）",
            subtitle = "复杂长句与中英混读语义理解强，仅推荐 12GB+ 旗舰机尝鲜",
            sizeBytes = 878_706_000L,
            approxRamMb = 1400,
            minRamMb = 10500,
            isProOnly = false,
            asrArch = AsrModelArch.OFFLINE_QWEN3_ASR,
            asrTierGroup = AsrTierGroup.LARGE_ACCURATE,
            supportsTrueStreaming = false,
            supportsNativeHotwords = false,
            downloadUrl = "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
            mirrorUrls = githubReleaseMirrors(
                "$SHERPA_ASR_RELEASE_BASE_URL/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"
            ),
            isTarBz2Archive = true,
            requiresSileroVad = true
        )
    )

    val slmModels: List<LocalModelDescriptor> = listOf(
        LocalModelDescriptor(
            id = ID_SLM_CORE_0_8B,
            slot = LocalModelSlot.SLM,
            displayName = "轻巧净写版（0.8B）",
            subtitle = "Qwen3.5-0.8B Q4_K_M：主接管净写与整句补全，断网/离线兜底选中改写与润色，6GB 机型友好",
            sizeBytes = 510L * MB,
            approxRamMb = 680,
            minRamMb = 0,
            isProOnly = true,
            primaryTasks = setOf(
                AiTask.DICTATE,
                AiTask.SENTENCE_COMPLETION
            ),
            fallbackTasks = setOf(
                AiTask.SPEAK_TO_EDIT,
                AiTask.TEXT_TRANSFORM
            ),
            promptTemplateVersion = CURRENT_PROMPT_TEMPLATE_VERSION,
            minEngineVersion = CURRENT_ENGINE_VERSION
        ),
        LocalModelDescriptor(
            id = ID_SLM_PLUS_2B,
            slot = LocalModelSlot.SLM,
            displayName = "均衡三件套版（2B）",
            subtitle = "Qwen3.5-2B Q4_K_M：主接管净写/补全/选中改写，断网兜底翻译与帮写",
            sizeBytes = 1260L * MB,
            approxRamMb = 1550,
            minRamMb = 7200,
            isProOnly = true,
            primaryTasks = setOf(
                AiTask.DICTATE,
                AiTask.SENTENCE_COMPLETION,
                AiTask.SPEAK_TO_EDIT
            ),
            fallbackTasks = setOf(
                AiTask.TRANSLATE,
                AiTask.HELP_ME_WRITE,
                AiTask.TEXT_TRANSFORM
            ),
            promptTemplateVersion = CURRENT_PROMPT_TEMPLATE_VERSION,
            minEngineVersion = CURRENT_ENGINE_VERSION
        ),
        LocalModelDescriptor(
            id = ID_SLM_PRO_4B,
            slot = LocalModelSlot.SLM,
            displayName = "旗舰全能版（4B）",
            subtitle = "Qwen3.5-4B Q4_K_M：全能离线大脑，断网兜底翻译/帮写/离线军师（纯 OCR 文本）",
            sizeBytes = 2673L * MB,
            approxRamMb = 3100,
            minRamMb = 10500,
            isProOnly = true,
            primaryTasks = setOf(
                AiTask.DICTATE,
                AiTask.SENTENCE_COMPLETION,
                AiTask.SPEAK_TO_EDIT,
                AiTask.TEXT_TRANSFORM
            ),
            fallbackTasks = setOf(
                AiTask.TRANSLATE,
                AiTask.HELP_ME_WRITE,
                AiTask.JEV_COPILOT,
                AiTask.BUBBLE_COPILOT
            ),
            promptTemplateVersion = CURRENT_PROMPT_TEMPLATE_VERSION,
            minEngineVersion = CURRENT_ENGINE_VERSION
        )
    )

    val allModels: List<LocalModelDescriptor> = asrModels + slmModels

    private val byId: Map<String, LocalModelDescriptor> = allModels.associateBy { it.id }

    fun findById(id: String?): LocalModelDescriptor? {
        if (id.isNullOrBlank()) return null
        return byId[id]
    }

    fun ramTierFromTotalMb(totalRamMb: Long): RamTier {
        return when {
            totalRamMb >= RamTier.HIGH_12GB_PLUS.minTotalRamMb -> RamTier.HIGH_12GB_PLUS
            totalRamMb >= RamTier.MID_8GB.minTotalRamMb -> RamTier.MID_8GB
            else -> RamTier.LOW_6GB
        }
    }

    fun compatibilityFor(model: LocalModelDescriptor, ramTier: RamTier): RamCompatibility {
        if (ramTier.minTotalRamMb < model.minRamMb.toLong()) {
            return RamCompatibility.RISK_INSUFFICIENT
        }
        if (ramTier == RamTier.MID_8GB &&
            model.slot == LocalModelSlot.ASR &&
            model.asrTierGroup == AsrTierGroup.LARGE_ACCURATE
        ) {
            return RamCompatibility.TIGHT
        }
        return RamCompatibility.RECOMMENDED
    }

    fun requiresSerialAsrSlmExecution(
        asrId: String?,
        slmId: String?,
        ramTier: RamTier
    ): Boolean {
        val asr = findById(asrId) ?: return false
        val slm = findById(slmId) ?: return false
        if (asr.slot != LocalModelSlot.ASR || slm.slot != LocalModelSlot.SLM) return false
        val combinedRamMb = asr.approxRamMb + slm.approxRamMb
        return when (ramTier) {
            RamTier.HIGH_12GB_PLUS -> false
            RamTier.MID_8GB -> combinedRamMb >= MID_8GB_SERIAL_THRESHOLD_MB
            RamTier.LOW_6GB -> combinedRamMb >= LOW_6GB_SERIAL_THRESHOLD_MB
        }
    }
}

data class LocalModelRecord(
    val modelId: String,
    val status: LocalModelInstallStatus = LocalModelInstallStatus.NOT_INSTALLED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val installedVersion: Int = 1,
    val sha256: String = "",
    val localDirPath: String = "",
    val errorReason: String = ""
)

data class LocalModelRegistrySnapshot(
    val records: Map<String, LocalModelRecord> = emptyMap(),
    val localServiceAlive: Boolean = true,
    val slmEngineAvailable: Boolean = false,
    val modelGeneration: Long = 0L
) {
    fun isInstalled(modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) return false
        return records[modelId]?.status == LocalModelInstallStatus.INSTALLED
    }

    fun statusOf(modelId: String): LocalModelInstallStatus {
        return records[modelId]?.status ?: LocalModelInstallStatus.NOT_INSTALLED
    }
}

data class OfflineModelConfigData(
    val mode: OfflineMode = OfflineMode.OFF,
    val asrSlotOn: Boolean = true,
    val slmSlotOn: Boolean = true,
    val selectedAsrModelId: String? = null,
    val selectedSlmModelId: String? = null,
    val schemaVersion: Int = 1
)

data class PendingOfflineRestore(
    val mode: OfflineMode = OfflineMode.OFF,
    val selectedAsrModelId: String? = null,
    val selectedSlmModelId: String? = null
)
