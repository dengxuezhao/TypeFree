package com.typefree.core.offline

enum class CloudPipelineHint {
    DUAL_STAGE,
    MULTIMODAL_GEMINI,
    MULTIMODAL_OPENAI_AUDIO
}

data class CloudAvailability(
    val pipelineHint: CloudPipelineHint = CloudPipelineHint.DUAL_STAGE,
    val hasCloudStt: Boolean = false,
    val cloudSttSupportsLiveWrite: Boolean = false,
    val hasCloudLlm: Boolean = false,
    val llmPolishEnabled: Boolean = true,
    val hasCloudMultimodal: Boolean = false
)

data class RouteInput(
    val task: AiTask,
    val offline: OfflineModelConfigData,
    val registry: LocalModelRegistrySnapshot,
    val cloud: CloudAvailability,
    val isNetworkAvailable: Boolean = true,
    val isSecureField: Boolean = false,
    val isIncognito: Boolean = false,
    val isThermalThrottled: Boolean = false,
    val liveWriteSettingEnabled: Boolean = true
)

sealed interface AsrRoute {
    data class Local(val modelId: String) : AsrRoute
    data object Cloud : AsrRoute
    data object None : AsrRoute
}

sealed interface TextRoute {
    data class Local(val modelId: String, val isFallbackTier: Boolean) : TextRoute
    data object Cloud : TextRoute
    data object CloudMultimodalTextFallback : TextRoute
    data object RawPassthrough : TextRoute
    data class Blocked(val reason: RouteBlockReason) : TextRoute
}

enum class RouteBlockReason {
    SECURE_FIELD,
    OFFLINE_ONLY_UNSUPPORTED_TASK,
    OFFLINE_ONLY_NO_MODEL,
    NO_CLOUD_OR_LOCAL_AVAILABLE,
    LOCAL_MODEL_UNAVAILABLE
}

data class RouteBadge(
    val shortLabel: String,
    val detailedLabel: String,
    val isPureOffline: Boolean,
    val isHybrid: Boolean
)

data class RoutePlan(
    val asr: AsrRoute,
    val text: TextRoute,
    val egressAllowed: Boolean,
    val liveWriteCapable: Boolean,
    val clearLocalStateAfterRun: Boolean,
    val badge: RouteBadge
)

sealed interface ReadinessState {
    data class Ready(val badge: RouteBadge) : ReadinessState
    data class DegradedRawOnly(val badge: RouteBadge, val message: String) : ReadinessState
    data class NotReady(val reason: String) : ReadinessState
}

object OfflineRoutePlanner {

    private val VOICE_TASKS: Set<AiTask> = setOf(
        AiTask.DICTATE,
        AiTask.SPEAK_TO_EDIT,
        AiTask.HELP_ME_WRITE,
        AiTask.TRANSLATE,
        AiTask.NOTE_TRANSCRIBE
    )

    fun isModelEffective(
        config: OfflineModelConfigData,
        registry: LocalModelRegistrySnapshot,
        slot: LocalModelSlot,
        modelId: String?
    ): Boolean {
        if (config.mode == OfflineMode.OFF) return false
        if (modelId.isNullOrBlank()) return false
        if (!registry.localServiceAlive) return false
        val slotEnabled = when (slot) {
            LocalModelSlot.ASR -> config.asrSlotOn && config.selectedAsrModelId == modelId
            LocalModelSlot.SLM -> config.slmSlotOn &&
                config.selectedSlmModelId == modelId &&
                registry.slmEngineAvailable
        }
        if (!slotEnabled) return false
        if (!registry.isInstalled(modelId)) return false
        val descriptor = OfflineModelCatalog.findById(modelId) ?: return false
        if (descriptor.slot != slot) return false
        if (descriptor.minEngineVersion > OfflineModelCatalog.CURRENT_ENGINE_VERSION) return false
        if (slot == LocalModelSlot.SLM &&
            descriptor.promptTemplateVersion != OfflineModelCatalog.CURRENT_PROMPT_TEMPLATE_VERSION
        ) {
            return false
        }
        return true
    }

    fun resolveRoute(input: RouteInput): RoutePlan {
        // Priority 1: Password / secure field disables all AI & voice unconditionally.
        if (input.isSecureField) {
            return RoutePlan(
                asr = AsrRoute.None,
                text = TextRoute.Blocked(RouteBlockReason.SECURE_FIELD),
                egressAllowed = false,
                liveWriteCapable = false,
                clearLocalStateAfterRun = input.isIncognito,
                badge = RouteBadge(
                    shortLabel = "安全输入域禁用",
                    detailedLabel = "密码框或安全输入域已禁用语音与 AI 推理",
                    isPureOffline = false,
                    isHybrid = false
                )
            )
        }

        // Priority 2: Incognito clears local KV/GDN state immediately after run.
        val clearLocalStateAfterRun = input.isIncognito

        // Priority 3: OFFLINE_ONLY egress lock.
        val egressAllowed = input.offline.mode != OfflineMode.OFFLINE_ONLY

        return when (input.offline.mode) {
            OfflineMode.OFF -> resolveOffMode(input, clearLocalStateAfterRun)
            OfflineMode.HYBRID -> resolveHybridMode(input, clearLocalStateAfterRun)
            OfflineMode.OFFLINE_ONLY -> resolveOfflineOnlyMode(input, clearLocalStateAfterRun)
        }
    }

    fun resolveReadiness(input: RouteInput): ReadinessState {
        val dictateInput = input.copy(task = AiTask.DICTATE)
        val plan = resolveRoute(dictateInput)
        return when (val text = plan.text) {
            is TextRoute.Blocked -> ReadinessState.NotReady(reasonForBlock(text.reason))
            is TextRoute.RawPassthrough -> {
                if (plan.asr is AsrRoute.Local) {
                    val message = if (!input.cloud.llmPolishEnabled) {
                        "听写润色已关闭，当前仅本机语音转写直出"
                    } else {
                        "仅本机语音转写直出（无本机大脑或云端润色）"
                    }
                    ReadinessState.DegradedRawOnly(plan.badge, message)
                } else {
                    ReadinessState.Ready(plan.badge)
                }
            }
            is TextRoute.Local,
            is TextRoute.Cloud,
            is TextRoute.CloudMultimodalTextFallback -> ReadinessState.Ready(plan.badge)
        }
    }

    private fun resolveOffMode(
        input: RouteInput,
        clearLocalStateAfterRun: Boolean
    ): RoutePlan {
        val cloudReachable = input.isNetworkAvailable
        val needsAsr = input.task in VOICE_TASKS
        val asr = if (needsAsr && canUseCloudAsr(input.cloud, cloudReachable)) {
            AsrRoute.Cloud
        } else {
            AsrRoute.None
        }

        val text = resolveCloudOnlyTextRoute(input.task, asr, input.cloud, cloudReachable)
        val liveWriteCapable = computeLiveWriteCapable(input, asr, text)
        val badge = buildBadge(input, asr, text, egressAllowed = true)

        return RoutePlan(
            asr = asr,
            text = text,
            egressAllowed = true,
            liveWriteCapable = liveWriteCapable,
            clearLocalStateAfterRun = clearLocalStateAfterRun,
            badge = badge
        )
    }

    private fun resolveHybridMode(
        input: RouteInput,
        clearLocalStateAfterRun: Boolean
    ): RoutePlan {
        val cloudReachable = input.isNetworkAvailable
        val needsAsr = input.task in VOICE_TASKS

        val effectiveAsr = effectiveDescriptor(input.offline, input.registry, LocalModelSlot.ASR)
        val effectiveSlm = effectiveDescriptor(input.offline, input.registry, LocalModelSlot.SLM)
        val asrBroken = isSelectedSlotBroken(input.offline, input.registry, LocalModelSlot.ASR)
        val slmBroken = isSelectedSlotBroken(input.offline, input.registry, LocalModelSlot.SLM)

        val asr = when {
            !needsAsr -> AsrRoute.None
            effectiveAsr != null -> AsrRoute.Local(effectiveAsr.id)
            canUseCloudAsr(input.cloud, cloudReachable) -> AsrRoute.Cloud
            else -> AsrRoute.None
        }

        val text: TextRoute = when {
            needsAsr && asr == AsrRoute.None -> {
                TextRoute.Blocked(
                    if (asrBroken) RouteBlockReason.LOCAL_MODEL_UNAVAILABLE
                    else RouteBlockReason.NO_CLOUD_OR_LOCAL_AVAILABLE
                )
            }
            input.task == AiTask.NOTE_TRANSCRIBE -> TextRoute.RawPassthrough
            input.task == AiTask.DICTATE && !input.cloud.llmPolishEnabled -> {
                if (asr is AsrRoute.Cloud &&
                    !input.cloud.hasCloudStt &&
                    input.cloud.pipelineHint != CloudPipelineHint.DUAL_STAGE &&
                    input.cloud.hasCloudMultimodal
                ) {
                    TextRoute.Cloud
                } else {
                    TextRoute.RawPassthrough
                }
            }
            asr is AsrRoute.Cloud &&
                !input.cloud.hasCloudStt &&
                input.cloud.pipelineHint != CloudPipelineHint.DUAL_STAGE &&
                input.cloud.hasCloudMultimodal -> {
                TextRoute.Cloud
            }
            else -> resolveHybridTextStage(
                task = input.task,
                asr = asr,
                effectiveSlm = effectiveSlm,
                slmBroken = slmBroken,
                cloud = input.cloud,
                cloudReachable = cloudReachable,
                isThermalThrottled = input.isThermalThrottled
            )
        }

        val liveWriteCapable = computeLiveWriteCapable(input, asr, text)
        val badge = buildBadge(input, asr, text, egressAllowed = true)

        return RoutePlan(
            asr = asr,
            text = text,
            egressAllowed = true,
            liveWriteCapable = liveWriteCapable,
            clearLocalStateAfterRun = clearLocalStateAfterRun,
            badge = badge
        )
    }

    private fun resolveHybridTextStage(
        task: AiTask,
        asr: AsrRoute,
        effectiveSlm: LocalModelDescriptor?,
        slmBroken: Boolean,
        cloud: CloudAvailability,
        cloudReachable: Boolean,
        isThermalThrottled: Boolean
    ): TextRoute {
        val slmPrimary = effectiveSlm != null && task in effectiveSlm.primaryTasks
        val slmFallback = effectiveSlm != null && task in effectiveSlm.fallbackTasks
        val cloudText = resolveCloudTextForStage(
            task = task,
            cloud = cloud,
            cloudReachable = cloudReachable,
            asrIsLocal = asr is AsrRoute.Local
        )

        // Thermal throttling in HYBRID demotes SLM priority so available cloud absorbs compute.
        if (slmPrimary && !isThermalThrottled) {
            return TextRoute.Local(effectiveSlm!!.id, isFallbackTier = false)
        }

        if (cloudText != null) {
            return cloudText
        }

        if (slmPrimary) {
            return TextRoute.Local(effectiveSlm!!.id, isFallbackTier = true)
        }

        if (slmFallback) {
            return TextRoute.Local(effectiveSlm!!.id, isFallbackTier = true)
        }

        if (task == AiTask.DICTATE && asr is AsrRoute.Local) {
            return TextRoute.RawPassthrough
        }

        return TextRoute.Blocked(
            if (slmBroken) RouteBlockReason.LOCAL_MODEL_UNAVAILABLE
            else RouteBlockReason.NO_CLOUD_OR_LOCAL_AVAILABLE
        )
    }

    private fun resolveOfflineOnlyMode(
        input: RouteInput,
        clearLocalStateAfterRun: Boolean
    ): RoutePlan {
        val needsAsr = input.task in VOICE_TASKS
        val effectiveAsr = effectiveDescriptor(input.offline, input.registry, LocalModelSlot.ASR)
        val effectiveSlm = effectiveDescriptor(input.offline, input.registry, LocalModelSlot.SLM)
        val asrBroken = isSelectedSlotBroken(input.offline, input.registry, LocalModelSlot.ASR)
        val slmBroken = isSelectedSlotBroken(input.offline, input.registry, LocalModelSlot.SLM)

        val asr = when {
            !needsAsr -> AsrRoute.None
            effectiveAsr != null -> AsrRoute.Local(effectiveAsr.id)
            else -> AsrRoute.None
        }

        val text: TextRoute = when {
            needsAsr && asr == AsrRoute.None -> {
                TextRoute.Blocked(
                    if (asrBroken) RouteBlockReason.LOCAL_MODEL_UNAVAILABLE
                    else RouteBlockReason.OFFLINE_ONLY_NO_MODEL
                )
            }
            input.task == AiTask.NOTE_TRANSCRIBE -> TextRoute.RawPassthrough
            input.task == AiTask.DICTATE && !input.cloud.llmPolishEnabled -> TextRoute.RawPassthrough
            effectiveSlm != null && input.task in effectiveSlm.primaryTasks -> {
                TextRoute.Local(effectiveSlm.id, isFallbackTier = false)
            }
            effectiveSlm != null && input.task in effectiveSlm.fallbackTasks -> {
                TextRoute.Local(effectiveSlm.id, isFallbackTier = true)
            }
            input.task == AiTask.DICTATE && asr is AsrRoute.Local -> TextRoute.RawPassthrough
            else -> {
                val reason = when {
                    slmBroken -> RouteBlockReason.LOCAL_MODEL_UNAVAILABLE
                    effectiveSlm != null -> RouteBlockReason.OFFLINE_ONLY_UNSUPPORTED_TASK
                    input.task == AiTask.ASK_AI_CARD -> RouteBlockReason.OFFLINE_ONLY_UNSUPPORTED_TASK
                    !input.registry.slmEngineAvailable -> RouteBlockReason.OFFLINE_ONLY_UNSUPPORTED_TASK
                    else -> RouteBlockReason.OFFLINE_ONLY_NO_MODEL
                }
                TextRoute.Blocked(reason)
            }
        }

        val liveWriteCapable = computeLiveWriteCapable(input, asr, text)
        val badge = buildBadge(input, asr, text, egressAllowed = false)

        return RoutePlan(
            asr = asr,
            text = text,
            egressAllowed = false,
            liveWriteCapable = liveWriteCapable,
            clearLocalStateAfterRun = clearLocalStateAfterRun,
            badge = badge
        )
    }

    private fun resolveCloudOnlyTextRoute(
        task: AiTask,
        asr: AsrRoute,
        cloud: CloudAvailability,
        cloudReachable: Boolean
    ): TextRoute {
        if (!cloudReachable) {
            return TextRoute.Blocked(RouteBlockReason.NO_CLOUD_OR_LOCAL_AVAILABLE)
        }
        val needsAsr = task in VOICE_TASKS
        if (needsAsr && asr == AsrRoute.None) {
            return TextRoute.Blocked(RouteBlockReason.NO_CLOUD_OR_LOCAL_AVAILABLE)
        }
        if (task == AiTask.NOTE_TRANSCRIBE) {
            return TextRoute.RawPassthrough
        }
        if (needsAsr && cloud.pipelineHint != CloudPipelineHint.DUAL_STAGE) {
            return if (cloud.hasCloudMultimodal) {
                TextRoute.Cloud
            } else if (task == AiTask.DICTATE && !cloud.llmPolishEnabled && cloud.hasCloudStt) {
                TextRoute.RawPassthrough
            } else if (cloud.hasCloudStt && cloud.hasCloudLlm) {
                TextRoute.Cloud
            } else {
                TextRoute.Blocked(RouteBlockReason.NO_CLOUD_OR_LOCAL_AVAILABLE)
            }
        }
        if (task == AiTask.DICTATE && !cloud.llmPolishEnabled) {
            return TextRoute.RawPassthrough
        }
        return resolveCloudTextForStage(
            task = task,
            cloud = cloud,
            cloudReachable = true,
            asrIsLocal = false
        ) ?: TextRoute.Blocked(RouteBlockReason.NO_CLOUD_OR_LOCAL_AVAILABLE)
    }

    private fun resolveCloudTextForStage(
        task: AiTask,
        cloud: CloudAvailability,
        cloudReachable: Boolean,
        asrIsLocal: Boolean
    ): TextRoute? {
        if (!cloudReachable) return null
        return when {
            cloud.hasCloudLlm -> TextRoute.Cloud
            cloud.hasCloudMultimodal &&
                (cloud.pipelineHint == CloudPipelineHint.MULTIMODAL_OPENAI_AUDIO || asrIsLocal) ->
                TextRoute.CloudMultimodalTextFallback
            cloud.hasCloudMultimodal -> TextRoute.Cloud
            else -> null
        }
    }

    private fun canUseCloudAsr(cloud: CloudAvailability, cloudReachable: Boolean): Boolean {
        if (!cloudReachable) return false
        return when (cloud.pipelineHint) {
            CloudPipelineHint.DUAL_STAGE -> cloud.hasCloudStt
            CloudPipelineHint.MULTIMODAL_GEMINI,
            CloudPipelineHint.MULTIMODAL_OPENAI_AUDIO -> cloud.hasCloudMultimodal || cloud.hasCloudStt
        }
    }

    private fun effectiveDescriptor(
        config: OfflineModelConfigData,
        registry: LocalModelRegistrySnapshot,
        slot: LocalModelSlot
    ): LocalModelDescriptor? {
        val selectedId = when (slot) {
            LocalModelSlot.ASR -> config.selectedAsrModelId
            LocalModelSlot.SLM -> config.selectedSlmModelId
        }
        if (!isModelEffective(config, registry, slot, selectedId)) return null
        return OfflineModelCatalog.findById(selectedId)
    }

    private fun isSelectedSlotBroken(
        config: OfflineModelConfigData,
        registry: LocalModelRegistrySnapshot,
        slot: LocalModelSlot
    ): Boolean {
        if (config.mode == OfflineMode.OFF) return false
        val slotOn = when (slot) {
            LocalModelSlot.ASR -> config.asrSlotOn
            LocalModelSlot.SLM -> config.slmSlotOn && registry.slmEngineAvailable
        }
        if (!slotOn) return false
        val selectedId = when (slot) {
            LocalModelSlot.ASR -> config.selectedAsrModelId
            LocalModelSlot.SLM -> config.selectedSlmModelId
        } ?: return false
        if (!registry.localServiceAlive) return true
        val status = registry.statusOf(selectedId)
        return status == LocalModelInstallStatus.CORRUPTED ||
            status == LocalModelInstallStatus.UNSUPPORTED_ON_DEVICE
    }

    private fun computeLiveWriteCapable(
        input: RouteInput,
        asr: AsrRoute,
        text: TextRoute
    ): Boolean {
        if (!input.liveWriteSettingEnabled) return false
        if (input.task == AiTask.NOTE_TRANSCRIBE) return false
        if (text is TextRoute.Blocked) return false
        return when (asr) {
            // Only true word-by-word streaming ASR (Zipformer / Streaming Paraformer) should live-write drafts
            // directly into the editor; non-streaming models (SenseVoiceSmall, etc.) commit once cleanly after polish.
            is AsrRoute.Local -> OfflineModelCatalog.findById(asr.modelId)?.supportsTrueStreaming == true
            is AsrRoute.Cloud -> input.cloud.pipelineHint == CloudPipelineHint.DUAL_STAGE &&
                input.cloud.cloudSttSupportsLiveWrite
            is AsrRoute.None -> false
        }
    }

    private fun buildBadge(
        input: RouteInput,
        asr: AsrRoute,
        text: TextRoute,
        egressAllowed: Boolean
    ): RouteBadge {
        if (text is TextRoute.Blocked) {
            return when (text.reason) {
                RouteBlockReason.SECURE_FIELD -> RouteBadge(
                    shortLabel = "安全输入域禁用",
                    detailedLabel = "密码框或安全输入域已禁用语音与 AI 推理",
                    isPureOffline = false,
                    isHybrid = false
                )
                RouteBlockReason.OFFLINE_ONLY_UNSUPPORTED_TASK -> RouteBadge(
                    shortLabel = "纯离线不支持",
                    detailedLabel = "纯离线锁已开启，当前任务不支持离线执行",
                    isPureOffline = true,
                    isHybrid = false
                )
                RouteBlockReason.OFFLINE_ONLY_NO_MODEL -> RouteBadge(
                    shortLabel = "纯离线未就绪",
                    detailedLabel = "纯离线锁已开启，请先下载并启用所需本机模型",
                    isPureOffline = true,
                    isHybrid = false
                )
                RouteBlockReason.LOCAL_MODEL_UNAVAILABLE -> RouteBadge(
                    shortLabel = "本机模型不可用",
                    detailedLabel = "已选本机模型损坏或离线推理服务不可用",
                    isPureOffline = !egressAllowed,
                    isHybrid = false
                )
                RouteBlockReason.NO_CLOUD_OR_LOCAL_AVAILABLE -> RouteBadge(
                    shortLabel = "未配置引擎",
                    detailedLabel = "当前无可用本机模型或云端配置",
                    isPureOffline = false,
                    isHybrid = false
                )
            }
        }

        if (asr is AsrRoute.Local && text is TextRoute.Local) {
            val asrTag = shortModelTag(asr.modelId)
            val slmTag = shortModelTag(text.modelId)
            return if (!egressAllowed) {
                RouteBadge(
                    shortLabel = "📱 纯离线 · $asrTag + $slmTag",
                    detailedLabel = "纯离线模式：本机听音（$asrTag）+ 本机大脑（$slmTag），零数据出网",
                    isPureOffline = true,
                    isHybrid = false
                )
            } else {
                val hasCloud = input.cloud.hasCloudLlm ||
                    input.cloud.hasCloudMultimodal
                if (hasCloud) {
                    RouteBadge(
                        shortLabel = "⚡ 混合 · 本机净写 + 云端进阶",
                        detailedLabel = "混合模式：本机听音（$asrTag）+ 本机大脑（$slmTag），进阶任务走云端",
                        isPureOffline = false,
                        isHybrid = true
                    )
                } else {
                    RouteBadge(
                        shortLabel = "⚡ 混合 · $asrTag + $slmTag",
                        detailedLabel = "混合模式：本机听音（$asrTag）+ 本机大脑（$slmTag）",
                        isPureOffline = false,
                        isHybrid = true
                    )
                }
            }
        }

        if (asr is AsrRoute.Local &&
            (text is TextRoute.Cloud || text is TextRoute.CloudMultimodalTextFallback)
        ) {
            val asrTag = shortModelTag(asr.modelId)
            return RouteBadge(
                shortLabel = "⚡ 混合 · 本机听音 + 云端润色",
                detailedLabel = "混合模式：本机听音（$asrTag）+ 云端润色（转写文本会发往云端）",
                isPureOffline = false,
                isHybrid = true
            )
        }

        if (asr is AsrRoute.Local && text is TextRoute.RawPassthrough) {
            val asrTag = shortModelTag(asr.modelId)
            return if (!egressAllowed) {
                RouteBadge(
                    shortLabel = "📱 纯离线 · 仅转写 ($asrTag)",
                    detailedLabel = "纯离线模式：仅本机听音直出（$asrTag），零数据出网",
                    isPureOffline = true,
                    isHybrid = false
                )
            } else {
                RouteBadge(
                    shortLabel = "📱 本机听音 · 仅转写",
                    detailedLabel = "本机听音（$asrTag）原始直出，不经过云端润色",
                    isPureOffline = true,
                    isHybrid = false
                )
            }
        }

        if (asr is AsrRoute.None && text is TextRoute.Local) {
            val slmTag = shortModelTag(text.modelId)
            return if (!egressAllowed) {
                RouteBadge(
                    shortLabel = "📱 纯离线 · $slmTag",
                    detailedLabel = "纯离线模式：本机大脑（$slmTag），零数据出网",
                    isPureOffline = true,
                    isHybrid = false
                )
            } else {
                RouteBadge(
                    shortLabel = "⚡ 混合 · 本机大脑 ($slmTag)",
                    detailedLabel = "混合模式：当前任务由本机大脑（$slmTag）执行",
                    isPureOffline = false,
                    isHybrid = true
                )
            }
        }

        val cloudLabel = when (input.cloud.pipelineHint) {
            CloudPipelineHint.DUAL_STAGE -> "☁️ 云端 · 双阶段"
            CloudPipelineHint.MULTIMODAL_GEMINI -> "☁️ 云端 · Gemini 多模态"
            CloudPipelineHint.MULTIMODAL_OPENAI_AUDIO -> "☁️ 云端 · 音频直连"
        }
        return RouteBadge(
            shortLabel = cloudLabel,
            detailedLabel = "云端模式：语音与文本由已配置的云端接口处理",
            isPureOffline = false,
            isHybrid = false
        )
    }

    private fun shortModelTag(modelId: String): String {
        return when (modelId) {
            OfflineModelCatalog.ID_ASR_ZIPFORMER_ZH_14M -> "Zipformer 14M"
            OfflineModelCatalog.ID_ASR_PARAFORMER_ZH_SMALL -> "Paraformer"
            OfflineModelCatalog.ID_ASR_ZIPFORMER_BILINGUAL -> "Zipformer 双语"
            OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL -> "SenseVoice"
            OfflineModelCatalog.ID_ASR_STREAMING_PARAFORMER_ZH_EN -> "Paraformer 流式"
            OfflineModelCatalog.ID_ASR_FIRERED2_CTC_ZH_EN -> "FireRed2"
            OfflineModelCatalog.ID_ASR_FUNASR_NANO -> "Fun-ASR-Nano"
            OfflineModelCatalog.ID_ASR_QWEN3_ASR_0_6B -> "Qwen3-ASR"
            OfflineModelCatalog.ID_SLM_CORE_0_8B -> "0.8B"
            OfflineModelCatalog.ID_SLM_PLUS_2B -> "2B"
            OfflineModelCatalog.ID_SLM_PRO_4B -> "4B"
            else -> OfflineModelCatalog.findById(modelId)?.displayName ?: modelId
        }
    }

    private fun reasonForBlock(reason: RouteBlockReason): String {
        return when (reason) {
            RouteBlockReason.SECURE_FIELD -> "当前为密码框或安全输入域，语音与 AI 已禁用"
            RouteBlockReason.OFFLINE_ONLY_UNSUPPORTED_TASK -> "纯离线锁已开启，当前任务不支持离线执行"
            RouteBlockReason.OFFLINE_ONLY_NO_MODEL -> "纯离线锁已开启，请先下载并启用离线模型"
            RouteBlockReason.LOCAL_MODEL_UNAVAILABLE -> "已选本机模型损坏或离线推理服务不可用，请重新校验或切换模型"
            RouteBlockReason.NO_CLOUD_OR_LOCAL_AVAILABLE -> "尚未配置云端 API 或启用本机离线模型"
        }
    }
}
