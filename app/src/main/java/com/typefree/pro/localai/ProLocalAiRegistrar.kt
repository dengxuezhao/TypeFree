package com.typefree.pro.localai

import android.content.Context
import com.typefree.core.offline.OfflineModelCatalog
import com.typefree.ime.localai.LocalAiLog
import com.typefree.ime.localai.LocalInferenceEngineRegistry
import com.typefree.ime.localai.SlmDownloadSource

/**
 * Proprietary Pro engine registrar invoked via reflection by `LocalInferenceEngineBootstrap`.
 *
 * Only compiled into the `xiaohe` flavor when `-I local_private/pro-localai/pro.init.gradle.kts`
 * is passed at build time; never present in the public GPL-3.0 `standard` flavor.
 */
object ProLocalAiRegistrar {

    @JvmStatic
    fun register(context: Context) {
        LocalInferenceEngineRegistry.registerSlmFactory(available = true) {
            LlamaCppLocalSlmEngine()
        }
        LocalInferenceEngineRegistry.registerSlmDownloadSource(
            modelId = OfflineModelCatalog.ID_SLM_CORE_0_8B,
            source = SlmDownloadSource(
                baseUrl = "https://modelscope.cn/models/unsloth/Qwen3.5-0.8B-GGUF/resolve/master/Qwen3.5-0.8B-Q4_K_M.gguf",
                baseMirrorUrls = listOf(
                    "https://hf-mirror.com/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
                    "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
                ),
                baseSizeBytes = 532_517_120L,
                baseExpectedSha256 = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
                loraUrl = "https://typefree.xuezhao.space/downloads/models/typefree_slm_0_8b_v3-lora.gguf",
                loraSizeBytes = 12_793_440L,
                loraExpectedSha256 = "d4af48a389b25057ad69830142e1275f6ce87aeba861b1560108085e8d94f0cf"
            )
        )
        LocalInferenceEngineRegistry.registerSlmDownloadSource(
            modelId = OfflineModelCatalog.ID_SLM_PLUS_2B,
            source = SlmDownloadSource(
                baseUrl = "https://modelscope.cn/models/unsloth/Qwen3.5-2B-GGUF/resolve/master/Qwen3.5-2B-Q4_K_M.gguf",
                baseMirrorUrls = listOf(
                    "https://hf-mirror.com/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
                    "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf"
                ),
                baseSizeBytes = 1_280_835_840L,
                baseExpectedSha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
                loraUrl = "https://typefree.xuezhao.space/downloads/models/typefree_slm_2b_v3-lora.gguf",
                loraSizeBytes = 21_837_408L,
                loraExpectedSha256 = "707a21203a142a11e1ace0082c3d2f1a39e1481ba8de923317a056e4ad5384b8"
            )
        )
        LocalAiLog.i(
            "ProLocalAiRegistrar registered LlamaCppLocalSlmEngine + SLM download sources (pkg=${context.packageName})"
        )
    }
}
