package com.typefree.core.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRoutePlannerTest {

    private fun installedRecord(modelId: String): Pair<String, LocalModelRecord> {
        return modelId to LocalModelRecord(
            modelId = modelId,
            status = LocalModelInstallStatus.INSTALLED,
            downloadedBytes = 1024L,
            totalBytes = 1024L,
            installedVersion = 1,
            sha256 = "abc123",
            localDirPath = "/data/local_models/$modelId"
        )
    }

    private fun corruptedRecord(modelId: String): Pair<String, LocalModelRecord> {
        return modelId to LocalModelRecord(
            modelId = modelId,
            status = LocalModelInstallStatus.CORRUPTED,
            errorReason = "sha256_mismatch"
        )
    }

    // -------------------------------------------------------------------------
    // Truth Table Cases #1 - #10 (Design Plan Section 2.4)
    // -------------------------------------------------------------------------

    @Test
    fun truthTable01_offModeAlwaysUsesCloudEvenWhenLocalModelsInstalled() {
        val registry = LocalModelRegistrySnapshot(
            records = mapOf(
                installedRecord(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL),
                installedRecord(OfflineModelCatalog.ID_SLM_PRO_4B)
            ),
            localServiceAlive = true,
            slmEngineAvailable = true
        )
        val offline = OfflineModelConfigData(
            mode = OfflineMode.OFF,
            asrSlotOn = true,
            slmSlotOn = true,
            selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
            selectedSlmModelId = OfflineModelCatalog.ID_SLM_PRO_4B
        )
        val cloud = CloudAvailability(
            pipelineHint = CloudPipelineHint.DUAL_STAGE,
            hasCloudStt = true,
            cloudSttSupportsLiveWrite = true,
            hasCloudLlm = true,
            llmPolishEnabled = true,
            hasDedicatedJevCloud = true,
            jevCanUseVision = true
        )

        val dictatePlan = OfflineRoutePlanner.resolveRoute(
            RouteInput(task = AiTask.DICTATE, offline = offline, registry = registry, cloud = cloud)
        )
        assertEquals(AsrRoute.Cloud, dictatePlan.asr)
        assertEquals(TextRoute.Cloud, dictatePlan.text)
        assertTrue(dictatePlan.egressAllowed)
        assertTrue(dictatePlan.liveWriteCapable)
        assertFalse(dictatePlan.badge.isPureOffline)
        assertFalse(dictatePlan.badge.isHybrid)

        val jevPlan = OfflineRoutePlanner.resolveRoute(
            RouteInput(task = AiTask.JEV_COPILOT, offline = offline, registry = registry, cloud = cloud)
        )
        assertEquals(AsrRoute.None, jevPlan.asr)
        assertEquals(TextRoute.Cloud, jevPlan.text)
        assertTrue(jevPlan.jevVisionAllowed)
        assertFalse(
            OfflineRoutePlanner.isModelEffective(
                offline,
                registry,
                LocalModelSlot.ASR,
                OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL
            )
        )
    }

    @Test
    fun truthTable02_hybridLocalAsrOnlyNoCloudLlmFallsBackToRawPassthroughWithLiveWrite() {
        val registry = LocalModelRegistrySnapshot(
            records = mapOf(installedRecord(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL)),
            localServiceAlive = true,
            slmEngineAvailable = false
        )
        val offline = OfflineModelConfigData(
            mode = OfflineMode.HYBRID,
            asrSlotOn = true,
            slmSlotOn = true,
            selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL
        )
        val cloud = CloudAvailability(
            pipelineHint = CloudPipelineHint.DUAL_STAGE,
            hasCloudStt = false,
            cloudSttSupportsLiveWrite = false,
            hasCloudLlm = false,
            llmPolishEnabled = true
        )

        val plan = OfflineRoutePlanner.resolveRoute(
            RouteInput(task = AiTask.DICTATE, offline = offline, registry = registry, cloud = cloud)
        )
        assertEquals(AsrRoute.Local(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL), plan.asr)
        assertEquals(TextRoute.RawPassthrough, plan.text)
        assertTrue(plan.egressAllowed)
        // Non-streaming SenseVoice commits cleanly once instead of flashing drafts into editor
        assertFalse(plan.liveWriteCapable)

        // True-streaming Zipformer enables editor liveWrite
        val streamingReg = LocalModelRegistrySnapshot(
            records = mapOf(installedRecord(OfflineModelCatalog.ID_ASR_ZIPFORMER_BILINGUAL)),
            localServiceAlive = true,
            slmEngineAvailable = false
        )
        val streamingPlan = OfflineRoutePlanner.resolveRoute(
            RouteInput(
                task = AiTask.DICTATE,
                offline = offline.copy(selectedAsrModelId = OfflineModelCatalog.ID_ASR_ZIPFORMER_BILINGUAL),
                registry = streamingReg,
                cloud = cloud
            )
        )
        assertTrue(streamingPlan.liveWriteCapable)
    }

    @Test
    fun truthTable03_hybridLocalAsrWithOpenAiAudioMultimodalUsesTextFallbackAndHonestBadge() {
        val registry = LocalModelRegistrySnapshot(
            records = mapOf(installedRecord(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL)),
            localServiceAlive = true,
            slmEngineAvailable = false
        )
        val offline = OfflineModelConfigData(
            mode = OfflineMode.HYBRID,
            asrSlotOn = true,
            selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL
        )
        val cloud = CloudAvailability(
            pipelineHint = CloudPipelineHint.MULTIMODAL_OPENAI_AUDIO,
            hasCloudStt = false,
            hasCloudLlm = false,
            llmPolishEnabled = true,
            hasCloudMultimodal = true
        )

        val plan = OfflineRoutePlanner.resolveRoute(
            RouteInput(task = AiTask.DICTATE, offline = offline, registry = registry, cloud = cloud)
        )
        assertEquals(AsrRoute.Local(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL), plan.asr)
        assertEquals(TextRoute.CloudMultimodalTextFallback, plan.text)
        assertTrue(plan.egressAllowed)
        assertFalse(plan.liveWriteCapable)
        assertEquals("⚡ 混合 · 本机听音 + 云端润色", plan.badge.shortLabel)
        assertTrue(plan.badge.isHybrid)
        assertFalse(plan.badge.isPureOffline)
    }

    @Test
    fun truthTable04_hybrid08bJevUsesCloudIfPresentElseBlocked() {
        val registry = LocalModelRegistrySnapshot(
            records = mapOf(installedRecord(OfflineModelCatalog.ID_SLM_CORE_0_8B)),
            localServiceAlive = true,
            slmEngineAvailable = true
        )
        val offline = OfflineModelConfigData(
            mode = OfflineMode.HYBRID,
            slmSlotOn = true,
            selectedSlmModelId = OfflineModelCatalog.ID_SLM_CORE_0_8B
        )

        val withCloudPlan = OfflineRoutePlanner.resolveRoute(
            RouteInput(
                task = AiTask.JEV_COPILOT,
                offline = offline,
                registry = registry,
                cloud = CloudAvailability(hasDedicatedJevCloud = true, jevCanUseVision = true)
            )
        )
        assertEquals(AsrRoute.None, withCloudPlan.asr)
        assertEquals(TextRoute.Cloud, withCloudPlan.text)
        assertTrue(withCloudPlan.jevVisionAllowed)

        val withoutCloudPlan = OfflineRoutePlanner.resolveRoute(
            RouteInput(
                task = AiTask.JEV_COPILOT,
                offline = offline,
                registry = registry,
                cloud = CloudAvailability()
            )
        )
        assertEquals(TextRoute.Blocked(RouteBlockReason.NO_CLOUD_OR_LOCAL_AVAILABLE), withoutCloudPlan.text)
        assertFalse(withoutCloudPlan.jevVisionAllowed)

        // 0.8B falls back to local SPEAK_TO_EDIT / TEXT_TRANSFORM when offline or without cloud,
        // while still preferring Cloud LLM in HYBRID when cloud is online.
        val editCloudOnline = OfflineRoutePlanner.resolveRoute(
            RouteInput(
                task = AiTask.SPEAK_TO_EDIT,
                offline = offline,
                registry = registry,
                cloud = CloudAvailability(hasCloudStt = true, hasCloudLlm = true),
                isNetworkAvailable = true
            )
        )
        assertEquals(TextRoute.Cloud, editCloudOnline.text)

        val offlineVoiceReg = LocalModelRegistrySnapshot(
            records = mapOf(
                installedRecord(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL),
                installedRecord(OfflineModelCatalog.ID_SLM_CORE_0_8B)
            ),
            localServiceAlive = true,
            slmEngineAvailable = true
        )
        val offlineVoiceCfg = offline.copy(
            asrSlotOn = true,
            selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL
        )
        val editHybridNoCloud = OfflineRoutePlanner.resolveRoute(
            RouteInput(
                task = AiTask.SPEAK_TO_EDIT,
                offline = offlineVoiceCfg,
                registry = offlineVoiceReg,
                cloud = CloudAvailability(),
                isNetworkAvailable = false
            )
        )
        assertEquals(AsrRoute.Local(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL), editHybridNoCloud.asr)
        assertEquals(TextRoute.Local(OfflineModelCatalog.ID_SLM_CORE_0_8B, isFallbackTier = true), editHybridNoCloud.text)

        val transformOfflineOnly = OfflineRoutePlanner.resolveRoute(
            RouteInput(
                task = AiTask.TEXT_TRANSFORM,
                offline = offline.copy(mode = OfflineMode.OFFLINE_ONLY),
                registry = registry,
                cloud = CloudAvailability(hasCloudLlm = true)
            )
        )
        assertEquals(TextRoute.Local(OfflineModelCatalog.ID_SLM_CORE_0_8B, isFallbackTier = true), transformOfflineOnly.text)
        assertFalse(transformOfflineOnly.egressAllowed)
    }

    @Test
    fun truthTable05_hybrid4bJevPrefersDedicatedCloudWhenOnlineAndFallsBackToLocal4bOffline() {
        val registry = LocalModelRegistrySnapshot(
            records = mapOf(installedRecord(OfflineModelCatalog.ID_SLM_PRO_4B)),
            localServiceAlive = true,
            slmEngineAvailable = true
        )
        val offline = OfflineModelConfigData(
            mode = OfflineMode.HYBRID,
            slmSlotOn = true,
            selectedSlmModelId = OfflineModelCatalog.ID_SLM_PRO_4B
        )
        val cloud = CloudAvailability(
            hasDedicatedJevCloud = true,
            jevCanUseVision = true,
            hasCloudLlm = true
        )

        val onlinePlan = OfflineRoutePlanner.resolveRoute(
            RouteInput(
                task = AiTask.JEV_COPILOT,
                offline = offline,
                registry = registry,
                cloud = cloud,
                isNetworkAvailable = true
            )
        )
        assertEquals(TextRoute.Cloud, onlinePlan.text)
        assertTrue(onlinePlan.jevVisionAllowed)

        val offlinePlan = OfflineRoutePlanner.resolveRoute(
            RouteInput(
                task = AiTask.JEV_COPILOT,
                offline = offline,
                registry = registry,
                cloud = cloud,
                isNetworkAvailable = false
            )
        )
        assertEquals(
            TextRoute.Local(OfflineModelCatalog.ID_SLM_PRO_4B, isFallbackTier = true),
            offlinePlan.text
        )
        assertFalse(offlinePlan.jevVisionAllowed)
    }

    @Test
    fun truthTable06_offlineOnlyWithAsrOnlyBlocksHelpMeWriteAndDisallowsEgress() {
        val registry = LocalModelRegistrySnapshot(
            records = mapOf(installedRecord(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL)),
            localServiceAlive = true,
            slmEngineAvailable = false
        )
        val offline = OfflineModelConfigData(
            mode = OfflineMode.OFFLINE_ONLY,
            asrSlotOn = true,
            selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL
        )
        val cloud = CloudAvailability(
            hasCloudStt = true,
            hasCloudLlm = true,
            hasCloudMultimodal = true
        )

        val plan = OfflineRoutePlanner.resolveRoute(
            RouteInput(task = AiTask.HELP_ME_WRITE, offline = offline, registry = registry, cloud = cloud)
        )
        assertFalse(plan.egressAllowed)
        assertEquals(
            TextRoute.Blocked(RouteBlockReason.OFFLINE_ONLY_UNSUPPORTED_TASK),
            plan.text
        )
        assertFalse(plan.liveWriteCapable)
    }

    @Test
    fun truthTable07_secureFieldOverridesAllModesAndTasks() {
        val registry = LocalModelRegistrySnapshot(
            records = mapOf(
                installedRecord(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL),
                installedRecord(OfflineModelCatalog.ID_SLM_PRO_4B)
            ),
            localServiceAlive = true,
            slmEngineAvailable = true
        )
        val cloud = CloudAvailability(hasCloudStt = true, hasCloudLlm = true)

        for (mode in OfflineMode.entries) {
            val offline = OfflineModelConfigData(
                mode = mode,
                selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
                selectedSlmModelId = OfflineModelCatalog.ID_SLM_PRO_4B
            )
            for (task in listOf(AiTask.DICTATE, AiTask.SENTENCE_COMPLETION)) {
                val plan = OfflineRoutePlanner.resolveRoute(
                    RouteInput(
                        task = task,
                        offline = offline,
                        registry = registry,
                        cloud = cloud,
                        isSecureField = true
                    )
                )
                assertEquals(AsrRoute.None, plan.asr)
                assertEquals(TextRoute.Blocked(RouteBlockReason.SECURE_FIELD), plan.text)
                assertFalse(plan.egressAllowed)
                assertFalse(plan.liveWriteCapable)
            }
        }
    }

    @Test
    fun truthTable08_incognitoAllowsLocalSlmCompletionAndSetsClearLocalStateAfterRun() {
        val registry = LocalModelRegistrySnapshot(
            records = mapOf(installedRecord(OfflineModelCatalog.ID_SLM_CORE_0_8B)),
            localServiceAlive = true,
            slmEngineAvailable = true
        )
        val offline = OfflineModelConfigData(
            mode = OfflineMode.HYBRID,
            slmSlotOn = true,
            selectedSlmModelId = OfflineModelCatalog.ID_SLM_CORE_0_8B
        )

        val plan = OfflineRoutePlanner.resolveRoute(
            RouteInput(
                task = AiTask.SENTENCE_COMPLETION,
                offline = offline,
                registry = registry,
                cloud = CloudAvailability(hasCloudLlm = true),
                isIncognito = true
            )
        )
        assertEquals(AsrRoute.None, plan.asr)
        assertEquals(
            TextRoute.Local(OfflineModelCatalog.ID_SLM_CORE_0_8B, isFallbackTier = false),
            plan.text
        )
        assertTrue(plan.clearLocalStateAfterRun)
    }

    @Test
    fun truthTable09_llmPolishDisabledAffectsDictateOnlyNotSpeakToEdit() {
        val registry = LocalModelRegistrySnapshot(
            records = mapOf(
                installedRecord(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL),
                installedRecord(OfflineModelCatalog.ID_SLM_PLUS_2B)
            ),
            localServiceAlive = true,
            slmEngineAvailable = true
        )
        val offline = OfflineModelConfigData(
            mode = OfflineMode.HYBRID,
            asrSlotOn = true,
            slmSlotOn = true,
            selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
            selectedSlmModelId = OfflineModelCatalog.ID_SLM_PLUS_2B
        )
        val cloud = CloudAvailability(
            hasCloudStt = true,
            hasCloudLlm = true,
            llmPolishEnabled = false
        )

        val dictatePlan = OfflineRoutePlanner.resolveRoute(
            RouteInput(task = AiTask.DICTATE, offline = offline, registry = registry, cloud = cloud)
        )
        assertEquals(AsrRoute.Local(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL), dictatePlan.asr)
        assertEquals(TextRoute.RawPassthrough, dictatePlan.text)

        val editPlan = OfflineRoutePlanner.resolveRoute(
            RouteInput(task = AiTask.SPEAK_TO_EDIT, offline = offline, registry = registry, cloud = cloud)
        )
        assertEquals(AsrRoute.Local(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL), editPlan.asr)
        assertEquals(
            TextRoute.Local(OfflineModelCatalog.ID_SLM_PLUS_2B, isFallbackTier = false),
            editPlan.text
        )
    }

    @Test
    fun truthTable10_corruptedModelOrDeadServiceFallbacksRespectModeAndEgressLock() {
        val cloudFull = CloudAvailability(hasCloudStt = true, hasCloudLlm = true)
        val cloudNone = CloudAvailability()

        // 10a. HYBRID + local service dead + cloud available -> falls back to cloud
        val deadRegistry = LocalModelRegistrySnapshot(
            records = mapOf(
                installedRecord(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL),
                installedRecord(OfflineModelCatalog.ID_SLM_PLUS_2B)
            ),
            localServiceAlive = false,
            slmEngineAvailable = true
        )
        val hybridConfig = OfflineModelConfigData(
            mode = OfflineMode.HYBRID,
            selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
            selectedSlmModelId = OfflineModelCatalog.ID_SLM_PLUS_2B
        )
        val hybridDeadWithCloud = OfflineRoutePlanner.resolveRoute(
            RouteInput(AiTask.DICTATE, hybridConfig, deadRegistry, cloudFull)
        )
        assertEquals(AsrRoute.Cloud, hybridDeadWithCloud.asr)
        assertEquals(TextRoute.Cloud, hybridDeadWithCloud.text)

        // 10b. HYBRID + ASR healthy + SLM CORRUPTED + no cloud -> degrades to RawPassthrough
        val slmCorruptedRegistry = LocalModelRegistrySnapshot(
            records = mapOf(
                installedRecord(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL),
                corruptedRecord(OfflineModelCatalog.ID_SLM_PLUS_2B)
            ),
            localServiceAlive = true,
            slmEngineAvailable = true
        )
        val hybridCorruptedNoCloud = OfflineRoutePlanner.resolveRoute(
            RouteInput(AiTask.DICTATE, hybridConfig, slmCorruptedRegistry, cloudNone)
        )
        assertEquals(AsrRoute.Local(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL), hybridCorruptedNoCloud.asr)
        assertEquals(TextRoute.RawPassthrough, hybridCorruptedNoCloud.text)

        // 10c. OFFLINE_ONLY + ASR healthy + SLM CORRUPTED + cloud keys present -> RawPassthrough, never egresses
        val offlineOnlyConfig = hybridConfig.copy(mode = OfflineMode.OFFLINE_ONLY)
        val offlineOnlySlmCorrupted = OfflineRoutePlanner.resolveRoute(
            RouteInput(AiTask.DICTATE, offlineOnlyConfig, slmCorruptedRegistry, cloudFull)
        )
        assertEquals(AsrRoute.Local(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL), offlineOnlySlmCorrupted.asr)
        assertEquals(TextRoute.RawPassthrough, offlineOnlySlmCorrupted.text)
        assertFalse(offlineOnlySlmCorrupted.egressAllowed)

        // 10d. OFFLINE_ONLY + local service dead + cloud keys present -> Blocked(LOCAL_MODEL_UNAVAILABLE), never egresses
        val offlineOnlyDead = OfflineRoutePlanner.resolveRoute(
            RouteInput(AiTask.DICTATE, offlineOnlyConfig, deadRegistry, cloudFull)
        )
        assertEquals(AsrRoute.None, offlineOnlyDead.asr)
        assertEquals(TextRoute.Blocked(RouteBlockReason.LOCAL_MODEL_UNAVAILABLE), offlineOnlyDead.text)
        assertFalse(offlineOnlyDead.egressAllowed)
    }

    // -------------------------------------------------------------------------
    // Thermal Throttling & Additional Route Rules
    // -------------------------------------------------------------------------

    @Test
    fun thermalThrottlingInHybridYieldsSlmToCloudWhenAvailableButKeepsLocalWhenOffline() {
        val registry = LocalModelRegistrySnapshot(
            records = mapOf(
                installedRecord(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL),
                installedRecord(OfflineModelCatalog.ID_SLM_PLUS_2B)
            ),
            localServiceAlive = true,
            slmEngineAvailable = true
        )
        val offline = OfflineModelConfigData(
            mode = OfflineMode.HYBRID,
            selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
            selectedSlmModelId = OfflineModelCatalog.ID_SLM_PLUS_2B
        )
        val cloud = CloudAvailability(hasCloudLlm = true)

        val onlineHotPlan = OfflineRoutePlanner.resolveRoute(
            RouteInput(
                task = AiTask.DICTATE,
                offline = offline,
                registry = registry,
                cloud = cloud,
                isNetworkAvailable = true,
                isThermalThrottled = true
            )
        )
        assertEquals(AsrRoute.Local(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL), onlineHotPlan.asr)
        assertEquals(TextRoute.Cloud, onlineHotPlan.text)

        val offlineHotPlan = OfflineRoutePlanner.resolveRoute(
            RouteInput(
                task = AiTask.DICTATE,
                offline = offline,
                registry = registry,
                cloud = cloud,
                isNetworkAvailable = false,
                isThermalThrottled = true
            )
        )
        assertEquals(
            TextRoute.Local(OfflineModelCatalog.ID_SLM_PLUS_2B, isFallbackTier = true),
            offlineHotPlan.text
        )
    }

    // -------------------------------------------------------------------------
    // Readiness Three States (Ready / DegradedRawOnly / NotReady)
    // -------------------------------------------------------------------------

    @Test
    fun resolveReadinessCoversReadyDegradedRawOnlyAndNotReady() {
        val asrOnlyRegistry = LocalModelRegistrySnapshot(
            records = mapOf(installedRecord(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL)),
            localServiceAlive = true,
            slmEngineAvailable = false
        )
        val fullRegistry = LocalModelRegistrySnapshot(
            records = mapOf(
                installedRecord(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL),
                installedRecord(OfflineModelCatalog.ID_SLM_PLUS_2B)
            ),
            localServiceAlive = true,
            slmEngineAvailable = true
        )

        // 1. Ready (Pure Offline ASR + SLM)
        val readyState = OfflineRoutePlanner.resolveReadiness(
            RouteInput(
                task = AiTask.ASK_AI_CARD, // resolveReadiness always delegates as DICTATE
                offline = OfflineModelConfigData(
                    mode = OfflineMode.OFFLINE_ONLY,
                    selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
                    selectedSlmModelId = OfflineModelCatalog.ID_SLM_PLUS_2B
                ),
                registry = fullRegistry,
                cloud = CloudAvailability()
            )
        )
        assertTrue(readyState is ReadinessState.Ready)
        assertEquals(
            "📱 纯离线 · SenseVoice + 2B",
            (readyState as ReadinessState.Ready).badge.shortLabel
        )

        // 2. DegradedRawOnly (Local ASR only, no SLM, no cloud LLM)
        val degradedState = OfflineRoutePlanner.resolveReadiness(
            RouteInput(
                task = AiTask.DICTATE,
                offline = OfflineModelConfigData(
                    mode = OfflineMode.HYBRID,
                    selectedAsrModelId = OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL
                ),
                registry = asrOnlyRegistry,
                cloud = CloudAvailability()
            )
        )
        assertTrue(degradedState is ReadinessState.DegradedRawOnly)

        // 3. NotReady (OFFLINE_ONLY without installed model)
        val notReadyState = OfflineRoutePlanner.resolveReadiness(
            RouteInput(
                task = AiTask.DICTATE,
                offline = OfflineModelConfigData(
                    mode = OfflineMode.OFFLINE_ONLY,
                    selectedAsrModelId = null
                ),
                registry = LocalModelRegistrySnapshot(),
                cloud = CloudAvailability(hasCloudStt = true, hasCloudLlm = true)
            )
        )
        assertTrue(notReadyState is ReadinessState.NotReady)
    }

    // -------------------------------------------------------------------------
    // Catalog, RAM Tier, RAM Compatibility, and 8GB Serial Execution Rules
    // -------------------------------------------------------------------------

    @Test
    fun catalogContainsExactEightOpenAsrModelsAndThreeProSlmModels() {
        assertEquals(8, OfflineModelCatalog.asrModels.size)
        assertEquals(3, OfflineModelCatalog.slmModels.size)
        assertEquals(11, OfflineModelCatalog.allModels.size)
        assertTrue(OfflineModelCatalog.asrModels.all { !it.isProOnly && it.slot == LocalModelSlot.ASR })
        assertTrue(OfflineModelCatalog.slmModels.all { it.isProOnly && it.slot == LocalModelSlot.SLM })

        val senseVoice = OfflineModelCatalog.findById(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL)
        assertNotNull(senseVoice)
        assertTrue(senseVoice!!.isDefaultRecommended)

        val fireRed = OfflineModelCatalog.findById(OfflineModelCatalog.ID_ASR_FIRERED2_CTC_ZH_EN)
        assertNotNull(fireRed)
        assertFalse(fireRed!!.isPendingVerification)

        // All 8 open-source ASR models must have verified https:// official download URLs
        OfflineModelCatalog.asrModels.forEach { model ->
            assertTrue(
                "Expected https:// downloadUrl for ${model.id}, got ${model.downloadUrl}",
                model.downloadUrl.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/")
            )
            assertTrue("Expected isTarBz2Archive=true for ${model.id}", model.isTarBz2Archive)
            assertTrue("Expected positive sizeBytes for ${model.id}", model.sizeBytes > 0L)
            assertTrue(
                "Expected all mirrorUrls to be https:// for ${model.id}",
                model.mirrorUrls.all { it.startsWith("https://") }
            )
        }
        assertTrue(OfflineModelCatalog.SILERO_VAD_URL.startsWith("https://"))
        assertTrue(OfflineModelCatalog.SILERO_VAD_MIRROR_URLS.all { it.startsWith("https://") })

        val zipformer14m = OfflineModelCatalog.findById(OfflineModelCatalog.ID_ASR_ZIPFORMER_ZH_14M)!!
        assertTrue(zipformer14m.supportsTrueStreaming)
        assertTrue(zipformer14m.supportsNativeHotwords)
        assertEquals(AsrModelArch.ONLINE_TRANSDUCER, zipformer14m.asrArch)

        assertNull(OfflineModelCatalog.findById("non_existent_model"))
        assertNull(OfflineModelCatalog.findById(null))
    }

    @Test
    fun ramTierAndCompatibilityMatchDesignSpec() {
        assertEquals(RamTier.LOW_6GB, OfflineModelCatalog.ramTierFromTotalMb(5800L))
        assertEquals(RamTier.MID_8GB, OfflineModelCatalog.ramTierFromTotalMb(7200L))
        assertEquals(RamTier.MID_8GB, OfflineModelCatalog.ramTierFromTotalMb(8192L))
        assertEquals(RamTier.HIGH_12GB_PLUS, OfflineModelCatalog.ramTierFromTotalMb(10500L))
        assertEquals(RamTier.HIGH_12GB_PLUS, OfflineModelCatalog.ramTierFromTotalMb(12288L))

        val senseVoice = OfflineModelCatalog.findById(OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL)!!
        val funAsrNano = OfflineModelCatalog.findById(OfflineModelCatalog.ID_ASR_FUNASR_NANO)!!
        val qwen3Asr = OfflineModelCatalog.findById(OfflineModelCatalog.ID_ASR_QWEN3_ASR_0_6B)!!
        val slm08b = OfflineModelCatalog.findById(OfflineModelCatalog.ID_SLM_CORE_0_8B)!!
        val slm2b = OfflineModelCatalog.findById(OfflineModelCatalog.ID_SLM_PLUS_2B)!!
        val slm4b = OfflineModelCatalog.findById(OfflineModelCatalog.ID_SLM_PRO_4B)!!

        // 4-6GB tier
        assertEquals(RamCompatibility.RECOMMENDED, OfflineModelCatalog.compatibilityFor(senseVoice, RamTier.LOW_6GB))
        assertEquals(RamCompatibility.RECOMMENDED, OfflineModelCatalog.compatibilityFor(slm08b, RamTier.LOW_6GB))
        assertEquals(RamCompatibility.RISK_INSUFFICIENT, OfflineModelCatalog.compatibilityFor(funAsrNano, RamTier.LOW_6GB))
        assertEquals(RamCompatibility.RISK_INSUFFICIENT, OfflineModelCatalog.compatibilityFor(slm2b, RamTier.LOW_6GB))

        // 8GB tier
        assertEquals(RamCompatibility.RECOMMENDED, OfflineModelCatalog.compatibilityFor(senseVoice, RamTier.MID_8GB))
        assertEquals(RamCompatibility.TIGHT, OfflineModelCatalog.compatibilityFor(funAsrNano, RamTier.MID_8GB))
        assertEquals(RamCompatibility.RISK_INSUFFICIENT, OfflineModelCatalog.compatibilityFor(qwen3Asr, RamTier.MID_8GB))
        assertEquals(RamCompatibility.RECOMMENDED, OfflineModelCatalog.compatibilityFor(slm2b, RamTier.MID_8GB))
        assertEquals(RamCompatibility.RISK_INSUFFICIENT, OfflineModelCatalog.compatibilityFor(slm4b, RamTier.MID_8GB))

        // 12GB+ tier
        assertEquals(RamCompatibility.RECOMMENDED, OfflineModelCatalog.compatibilityFor(funAsrNano, RamTier.HIGH_12GB_PLUS))
        assertEquals(RamCompatibility.RECOMMENDED, OfflineModelCatalog.compatibilityFor(qwen3Asr, RamTier.HIGH_12GB_PLUS))
        assertEquals(RamCompatibility.RECOMMENDED, OfflineModelCatalog.compatibilityFor(slm4b, RamTier.HIGH_12GB_PLUS))
    }

    @Test
    fun serialAsrSlmExecutionTriggeredOn8GbForFunAsrNanoPlus2b() {
        // 8GB: SenseVoice + 2B can co-reside
        assertFalse(
            OfflineModelCatalog.requiresSerialAsrSlmExecution(
                OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
                OfflineModelCatalog.ID_SLM_PLUS_2B,
                RamTier.MID_8GB
            )
        )
        // 8GB: Fun-ASR-Nano + 2B must serialize (unload ASR before loading SLM)
        assertTrue(
            OfflineModelCatalog.requiresSerialAsrSlmExecution(
                OfflineModelCatalog.ID_ASR_FUNASR_NANO,
                OfflineModelCatalog.ID_SLM_PLUS_2B,
                RamTier.MID_8GB
            )
        )
        // 12GB+: Fun-ASR-Nano + 4B allowed to co-reside
        assertFalse(
            OfflineModelCatalog.requiresSerialAsrSlmExecution(
                OfflineModelCatalog.ID_ASR_FUNASR_NANO,
                OfflineModelCatalog.ID_SLM_PRO_4B,
                RamTier.HIGH_12GB_PLUS
            )
        )
        // 6GB: SenseVoice + 0.8B can co-reside
        assertFalse(
            OfflineModelCatalog.requiresSerialAsrSlmExecution(
                OfflineModelCatalog.ID_ASR_SENSEVOICE_SMALL,
                OfflineModelCatalog.ID_SLM_CORE_0_8B,
                RamTier.LOW_6GB
            )
        )
    }
}
