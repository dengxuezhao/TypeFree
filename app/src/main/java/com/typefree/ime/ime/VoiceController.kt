package com.typefree.ime.ime

import android.view.inputmethod.ExtractedTextRequest
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.bubble.CapturedChatContext
import com.typefree.ime.bubble.ChatScreenContextExtractor
import com.typefree.ime.bubble.TypeFreeAccessibilityService
import com.typefree.ime.bubble.ViewportCaptureOutcome
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.PipelineMode
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.ime.jev.InputConnectionDraftTarget
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.ime.jev.JevDraftSession
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.ime.network.PipelineStage
import com.typefree.ime.network.asr.StreamingAsrSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Controller encapsulating microphone recording, streaming ASR preview / live write,
 * ArcTranslate wheel, Speak-to-Edit, Help-me-Write, `==` Ask AI, and voice pipeline execution.
 */
class VoiceController(
    private val host: ImeHostCallbacks,
    private val audioRecorderProvider: () -> WavAudioRecorder,
    private val onExternalCommitToEngine: (String) -> Unit,
    private val onResetComposing: () -> Unit,
    private val chatContextCaptureProvider: (
        expectedPackage: String,
        appName: String,
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean
    ) -> CapturedChatContext = { pkg, app, s, inc, pwd, ign ->
        TypeFreeAccessibilityService.captureChatContextIfAvailable(pkg, app, s, inc, pwd, ign, forJevCopilot = false)
    },
    private val chatScreenshotCaptureProvider: suspend (
        expectedPackage: String,
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean
    ) -> Triple<String, Int, Int>? = { pkg, s, inc, pwd, ign ->
        TypeFreeAccessibilityService.captureChatScreenshotIfAvailable(pkg, s, inc, pwd, ign, forJevCopilot = false)
    }
) {
    private var activePipelineJob: Job? = null
    private var chatScreenshotJob: Job? = null
    private var jevInferenceJob: Job? = null
    private val jevDraftSession = JevDraftSession()
    private var activeStreamingSession: StreamingAsrSession? = null
    private var streamingPreviewJob: Job? = null
    private var handsFreeSilenceJob: Job? = null
    private var toastDismissJob: Job? = null
    private var activeComposer: StreamingComposer? = null
    private var preSessionSurroundingContext: String = ""
    private var lastKnownSelectionStart: Int = 0
    private var lastKnownSelectionEnd: Int = 0

    @Volatile
    var micPressDownTimeMs: Long = 0L
        private set

    fun warmVoiceConnectionsInBackground() {
        val snap = host.repository.settings.value
        host.scope.launch {
            runCatching {
                host.aiEngine.prewarmLiveStreamingIfNeeded(snap)
                host.aiEngine.prewarmEndpoints(snap)
            }
        }
    }

    fun openOrRestoreVoicePenPanel() {
        host.performKeyFeedback(strong = false)
        finalizeJevDraftAndClearMemory(clearContext = false)
        onResetComposing()
        val stateSnap = host.stateFlow.value
        val isPassword = ImeLogic.isPasswordEditorInputType(stateSnap.currentInputType)
        val editorDraft = if (isPassword) "" else host.readFullEditorTextSafely().trim()
        val isSocialPkg = com.typefree.ime.ime.panels.HelpWriteChipFilter.isSocialContentPackage(stateSnap.targetPackage)
        val defaultPreset = if (isSocialPkg && editorDraft.isBlank()) {
            com.typefree.ime.ime.panels.HelpWriteChipFilter.creativeGenres.firstOrNull { it.id == "genre_seed_note" }
        } else {
            null
        }

        host.stateFlow.update { prev ->
            if (prev.rawSelectedText.isNotBlank()) {
                prev.copy(
                    tabMode = KeyboardTabMode.VOICE,
                    ignoreSelectionForThisTurn = false,
                    voiceSubPanel = VoiceSubPanel.SPEAK_TO_EDIT,
                    activeIntentMode = VoiceIntentMode.SPEAK_TO_EDIT,
                    capturedChatContext = CapturedChatContext(),
                    ignoreChatContextForThisTurn = false,
                    showChatContextDisclosurePrompt = false,
                    currentEditorText = editorDraft,
                    jevCopilotResult = null,
                    selectedJevStrategyIndex = -1,
                    isJevLoading = false,
                    jevUserBottomLine = "",
                    jevExcludedReplies = emptyList(),
                    isFullScreenPeekCapturing = false,
                    isJevContextInspectorOpen = false,
                    selectedHelpWritePreset = null,
                    bigBangClipboardItem = null,
                    isClipboardOpen = false,
                    isToolPanelOpen = false,
                    isPunctuationStripOpen = false,
                    isStylePickerExpanded = false,
                    flypyComposingCode = "",
                    preeditDisplay = "",
                    flypyCandidates = emptyList(),
                    isFlypyCandidateSheetExpanded = false
                )
            } else {
                prev.copy(
                    tabMode = KeyboardTabMode.VOICE,
                    voiceSubPanel = VoiceSubPanel.HELP_ME_WRITE,
                    activeIntentMode = VoiceIntentMode.HELP_ME_WRITE,
                    capturedChatContext = CapturedChatContext(),
                    ignoreChatContextForThisTurn = false,
                    showChatContextDisclosurePrompt = false,
                    currentEditorText = editorDraft,
                    selectedHelpWritePreset = defaultPreset,
                    jevCopilotResult = null,
                    selectedJevStrategyIndex = -1,
                    isJevLoading = false,
                    jevUserBottomLine = "",
                    jevExcludedReplies = emptyList(),
                    isFullScreenPeekCapturing = false,
                    isJevContextInspectorOpen = false,
                    bigBangClipboardItem = null,
                    isClipboardOpen = false,
                    isToolPanelOpen = false,
                    isPunctuationStripOpen = false,
                    isStylePickerExpanded = false,
                    flypyComposingCode = "",
                    preeditDisplay = "",
                    flypyCandidates = emptyList(),
                    isFlypyCandidateSheetExpanded = false
                )
            }
        }
    }

    fun openJevCopilotPanel() {
        val settingsSnap = host.repository.settings.value
        if (!settingsSnap.jevCopilotEnabled) return
        host.performKeyFeedback(strong = false)
        finalizeJevDraftAndClearMemory(clearContext = true)
        onResetComposing()

        val stateSnap = host.stateFlow.value
        val isPassword = ImeLogic.isPasswordEditorInputType(stateSnap.currentInputType)
        val accessibilityConnected =
            TypeFreeAccessibilityService.activeInstance != null || TypeFreeAccessibilityService.debugSimulatedNodesForTest != null
        val shouldPromptDisclosure = accessibilityConnected &&
            ChatScreenContextExtractor.shouldPromptJevFirstTimeDisclosure(
                settings = settingsSnap,
                isIncognitoMode = stateSnap.isIncognitoMode,
                isPasswordInput = isPassword,
                targetInputPackage = stateSnap.targetPackage
            )
        val capturedContext = if (!shouldPromptDisclosure) {
            TypeFreeAccessibilityService.captureChatContextIfAvailable(
                expectedPackage = stateSnap.targetPackage,
                appName = stateSnap.targetAppName,
                settings = settingsSnap,
                isIncognitoMode = stateSnap.isIncognitoMode,
                isPasswordInput = isPassword,
                ignoreChatContextForTurn = false,
                forJevCopilot = true
            )
        } else {
            CapturedChatContext(
                packageName = stateSnap.targetPackage,
                appName = stateSnap.targetAppName,
                domainProfile = JevDomainProfile.fromPackage(stateSnap.targetPackage)
            )
        }

        host.stateFlow.update { prev ->
            prev.copy(
                tabMode = KeyboardTabMode.VOICE,
                voiceSubPanel = VoiceSubPanel.JEV_COPILOT,
                activeIntentMode = VoiceIntentMode.JEV_COPILOT,
                capturedChatContext = capturedContext,
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = shouldPromptDisclosure,
                jevCopilotResult = null,
                selectedJevStrategyIndex = -1,
                isJevLoading = !shouldPromptDisclosure,
                isJevThinking = false,
                jevErrorMessage = null,
                jevUserBottomLine = "",
                jevExcludedReplies = emptyList(),
                isFullScreenPeekCapturing = false,
                isJevContextInspectorOpen = false,
                selectedHelpWritePreset = null,
                bigBangClipboardItem = null,
                isClipboardOpen = false,
                isToolPanelOpen = false,
                isPunctuationStripOpen = false,
                isStylePickerExpanded = false,
                flypyComposingCode = "",
                preeditDisplay = "",
                flypyCandidates = emptyList(),
                isFlypyCandidateSheetExpanded = false
            )
        }

        if (!shouldPromptDisclosure) {
            triggerJevCopilotInference(isRefresh = false)
        } else {
            warmVoiceConnectionsInBackground()
        }
    }

    fun selectJevStrategyCard(index: Int) {
        val stateSnap = host.stateFlow.value
        val option = stateSnap.jevCopilotResult?.options?.getOrNull(index) ?: return
        host.performKeyFeedback(strong = false)
        if (stateSnap.isFlypyComposing) {
            onResetComposing()
        }
        val ic = host.currentInputConnection()
        if (ic != null) {
            jevDraftSession.applyStrategyCard(
                target = InputConnectionDraftTarget(ic),
                cardIndex = index,
                rawStrategyText = option.replyText,
                redactionSession = stateSnap.capturedChatContext.redactionSession
            )
        }
        host.stateFlow.update {
            it.copy(
                selectedJevStrategyIndex = index,
                isJevContextInspectorOpen = false
            )
        }
    }

    fun toggleJevContextInspector() {
        host.performKeyFeedback(strong = false)
        host.stateFlow.update {
            it.copy(isJevContextInspectorOpen = !it.isJevContextInspectorOpen)
        }
    }

    fun refreshJevStrategies() {
        val stateSnap = host.stateFlow.value
        if (stateSnap.isJevLoading) return
        host.performKeyFeedback(strong = false)
        val prevReplies = stateSnap.jevCopilotResult?.options?.map { it.replyText }.orEmpty()
        val updatedExcluded = (stateSnap.jevExcludedReplies + prevReplies)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeLast(6)

        val settingsSnap = host.repository.settings.value
        val isPassword = ImeLogic.isPasswordEditorInputType(stateSnap.currentInputType)
        val refreshedContext = if (!stateSnap.showChatContextDisclosurePrompt) {
            TypeFreeAccessibilityService.captureChatContextIfAvailable(
                expectedPackage = stateSnap.targetPackage,
                appName = stateSnap.targetAppName,
                settings = settingsSnap,
                isIncognitoMode = stateSnap.isIncognitoMode,
                isPasswordInput = isPassword,
                ignoreChatContextForTurn = stateSnap.ignoreChatContextForThisTurn,
                forJevCopilot = true
            )
        } else {
            stateSnap.capturedChatContext
        }

        host.stateFlow.update {
            it.copy(
                capturedChatContext = refreshedContext,
                selectedJevStrategyIndex = -1,
                isJevLoading = true,
                isJevThinking = false,
                jevErrorMessage = null,
                isJevContextInspectorOpen = false,
                jevExcludedReplies = updatedExcluded
            )
        }
        triggerJevCopilotInference(isRefresh = true)
    }

    fun triggerFullScreenPeekCapture(onRequestComputeInsets: () -> Unit) {
        val stateSnap = host.stateFlow.value
        if (stateSnap.isFullScreenPeekCapturing || stateSnap.isJevLoading) return
        host.performKeyFeedback(strong = false)
        jevInferenceJob?.cancel()
        chatScreenshotJob?.cancel()
        host.stateFlow.update {
            it.copy(
                isJevContextInspectorOpen = false,
                isFullScreenPeekCapturing = true,
                isJevLoading = true,
                isJevThinking = false,
                jevErrorMessage = null
            )
        }

        if (TypeFreeAccessibilityService.activeInstance != null) {
            warmVoiceConnectionsInBackground()
        }

        jevInferenceJob = host.scope.launch {
            try {
                host.stateFlow.update {
                    it.copy(
                        isJevContextInspectorOpen = false,
                        isFullScreenPeekCapturing = true,
                        isJevLoading = true,
                        jevErrorMessage = null
                    )
                }
                onRequestComputeInsets()
                delay(240L)

                val curr = host.stateFlow.value
                val settingsSnap = host.repository.settings.value
                val isPassword = ImeLogic.isPasswordEditorInputType(curr.currentInputType)
                val fullScreenCtx = TypeFreeAccessibilityService.captureChatContextIfAvailable(
                    expectedPackage = curr.targetPackage,
                    appName = curr.targetAppName,
                    settings = settingsSnap,
                    isIncognitoMode = curr.isIncognitoMode,
                    isPasswordInput = isPassword,
                    ignoreChatContextForTurn = false,
                    forJevCopilot = true,
                    isFullScreenPeek = true
                )
                val fullScreenOutcome = if (
                    settingsSnap.chatContextSendScreenshot ||
                    ChatScreenContextExtractor.shouldTriggerOcrForCustomCanvas(fullScreenCtx)
                ) {
                    runCatching {
                        TypeFreeAccessibilityService.captureChatViewportOutcomeIfAvailable(
                            expectedPackage = curr.targetPackage,
                            settings = settingsSnap,
                            isIncognitoMode = curr.isIncognitoMode,
                            isPasswordInput = isPassword,
                            ignoreChatContextForTurn = false,
                            forJevCopilot = true,
                            existingMessages = fullScreenCtx.formattedMessages,
                            redactionSession = fullScreenCtx.redactionSession,
                            isFullScreenPeek = true
                        )
                    }.getOrNull()
                } else {
                    null
                }
                val mergedCtx = if (fullScreenOutcome != null) {
                    fullScreenCtx.withViewportOutcome(
                        outcome = fullScreenOutcome,
                        fallbackPackage = curr.targetPackage,
                        fallbackAppName = curr.targetAppName
                    )
                } else {
                    fullScreenCtx
                }

                host.stateFlow.update {
                    it.copy(
                        isFullScreenPeekCapturing = false,
                        isJevContextInspectorOpen = false,
                        capturedChatContext = mergedCtx,
                        ignoreChatContextForThisTurn = false,
                        showChatContextDisclosurePrompt = false,
                        selectedJevStrategyIndex = -1
                    )
                }
                onRequestComputeInsets()
            } catch (e: CancellationException) {
                host.stateFlow.update { it.copy(isFullScreenPeekCapturing = false, isJevLoading = false) }
                onRequestComputeInsets()
                throw e
            } catch (_: Throwable) {
                host.stateFlow.update { it.copy(isFullScreenPeekCapturing = false) }
                onRequestComputeInsets()
            }
            executeJevCopilotInferenceSuspend(isRefresh = false, skipViewportRecapture = true)
        }
    }

    fun triggerJevCopilotInference(
        isRefresh: Boolean = false,
        overrideBottomLine: String? = null,
        wavFile: File? = null,
        pretranscribedSpeech: String? = null
    ) {
        jevInferenceJob?.cancel()

        jevInferenceJob = host.scope.launch {
            executeJevCopilotInferenceSuspend(
                isRefresh = isRefresh,
                overrideBottomLine = overrideBottomLine,
                wavFile = wavFile,
                pretranscribedSpeech = pretranscribedSpeech
            )
        }
    }

    private suspend fun executeJevCopilotInferenceSuspend(
        @Suppress("UNUSED_PARAMETER") isRefresh: Boolean = false,
        overrideBottomLine: String? = null,
        wavFile: File? = null,
        pretranscribedSpeech: String? = null,
        skipViewportRecapture: Boolean = false
    ) {
        val result: Result<Pair<com.typefree.ime.ime.jev.JevCopilotResult, String>> = try {
            val settingsSnap = host.repository.settings.value
            val initialSnap = host.stateFlow.value
            val isPassword = ImeLogic.isPasswordEditorInputType(initialSnap.currentInputType)

            host.stateFlow.update {
                it.copy(
                    isJevLoading = true,
                    isJevThinking = false,
                    jevErrorMessage = null,
                    isJevContextInspectorOpen = false,
                    selectedJevStrategyIndex = -1
                )
            }

            val canUseVision = host.aiEngine.canJevUseVisionScreenshot(settingsSnap)
            val needsViewportCaptureOrOcr = !skipViewportRecapture &&
                !initialSnap.ignoreChatContextForThisTurn &&
                !initialSnap.showChatContextDisclosurePrompt &&
                (
                    (canUseVision && initialSnap.capturedChatContext.screenshotJpegBase64.isBlank()) ||
                        ChatScreenContextExtractor.shouldTriggerOcrForCustomCanvas(initialSnap.capturedChatContext)
                    )
            if (needsViewportCaptureOrOcr) {
                if (TypeFreeAccessibilityService.activeInstance != null) {
                    warmVoiceConnectionsInBackground()
                }
                val capturedOutcome = runCatching {
                    TypeFreeAccessibilityService.captureChatViewportOutcomeIfAvailable(
                        expectedPackage = initialSnap.targetPackage,
                        settings = settingsSnap,
                        isIncognitoMode = initialSnap.isIncognitoMode,
                        isPasswordInput = isPassword,
                        ignoreChatContextForTurn = initialSnap.ignoreChatContextForThisTurn,
                        forJevCopilot = true,
                        existingMessages = initialSnap.capturedChatContext.formattedMessages,
                        redactionSession = initialSnap.capturedChatContext.redactionSession
                    )
                }.getOrNull()
                if (capturedOutcome != null) {
                    host.stateFlow.update { curr ->
                        val base = curr.capturedChatContext
                        curr.copy(
                            capturedChatContext = base.withViewportOutcome(
                                outcome = capturedOutcome,
                                fallbackPackage = initialSnap.targetPackage,
                                fallbackAppName = initialSnap.targetAppName
                            )
                        )
                    }
                }
            }

            val latest = host.stateFlow.value
            val (activeStyle, _) = host.repository.resolveActiveStyle(latest.targetPackage, latest.currentInputType)
            val domainProfile = if (latest.capturedChatContext.packageName.isNotBlank()) {
                latest.capturedChatContext.domainProfile
            } else {
                JevDomainProfile.fromPackage(latest.targetPackage)
            }
            val effectiveBottomLine = overrideBottomLine ?: latest.jevUserBottomLine
            val effectiveTopology = if (latest.ignoreChatContextForThisTurn) {
                com.typefree.ime.ime.jev.ChatTopology()
            } else {
                latest.capturedChatContext.topology
            }

            host.aiEngine.runJevCopilot(
                settings = settingsSnap,
                activeStyle = activeStyle,
                targetAppName = latest.targetAppName,
                domainProfile = domainProfile,
                chatContextMessages = latest.effectiveChatContextMessages,
                chatContextScreenshotBase64 = latest.effectiveChatScreenshotBase64,
                userBottomLine = effectiveBottomLine,
                excludedPreviousReplies = latest.jevExcludedReplies,
                redactionSession = latest.capturedChatContext.redactionSession,
                wavFile = wavFile,
                preTranscribedBottomLine = pretranscribedSpeech,
                chatTopology = effectiveTopology,
                targetPackageName = latest.capturedChatContext.packageName.ifBlank { latest.targetPackage },
                onThinkingStarted = {
                    host.stateFlow.update { curr ->
                        if (curr.isJevLoading && curr.jevCopilotResult == null) {
                            curr.copy(isJevThinking = true)
                        } else {
                            curr
                        }
                    }
                },
                onPartialResult = { partialResult ->
                    host.stateFlow.update { curr ->
                        if (!curr.isJevLoading) {
                            curr
                        } else {
                            val prevSelectedReply = curr.jevCopilotResult?.options
                                ?.getOrNull(curr.selectedJevStrategyIndex)
                                ?.replyText
                            val mappedIndex = if (!prevSelectedReply.isNullOrBlank()) {
                                partialResult.options.indexOfFirst { it.replyText == prevSelectedReply }
                            } else {
                                -1
                            }
                            curr.copy(
                                jevCopilotResult = partialResult,
                                isJevThinking = false,
                                jevErrorMessage = null,
                                selectedJevStrategyIndex = mappedIndex
                            )
                        }
                    }
                }
            )
        } catch (e: CancellationException) {
            host.stateFlow.update { it.copy(isJevLoading = false, isJevThinking = false) }
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            // Wall 4: Ephemeral screenshot bytes & audio file wiped immediately after request
            if (wavFile != null) {
                runCatching { wavFile.delete() }
            }
            host.stateFlow.update { curr ->
                if (curr.capturedChatContext.screenshotJpegBase64.isNotEmpty()) {
                    curr.copy(
                        capturedChatContext = curr.capturedChatContext.copy(
                            screenshotJpegBase64 = ""
                        )
                    )
                } else {
                    curr
                }
            }
        }

        result.fold(
            onSuccess = { (jevResult, resolvedBottomLine) ->
                host.performHaptic(strong = false)
                host.stateFlow.update { curr ->
                    val prevSelectedReply = curr.jevCopilotResult?.options
                        ?.getOrNull(curr.selectedJevStrategyIndex)
                        ?.replyText
                    val preservedSelectedIdx = if (!prevSelectedReply.isNullOrBlank()) {
                        jevResult.options.indexOfFirst { it.replyText == prevSelectedReply }
                    } else {
                        -1
                    }
                    curr.copy(
                        voicePhase = VoicePhase.Idle,
                        jevCopilotResult = jevResult,
                        selectedJevStrategyIndex = preservedSelectedIdx,
                        isJevLoading = false,
                        isJevThinking = false,
                        jevErrorMessage = null,
                        jevUserBottomLine = resolvedBottomLine
                    )
                }
            },
            onFailure = { err ->
                if (err is CancellationException) return
                val friendlyError = formatJevUserFacingError(err)
                host.performHaptic(strong = true)
                host.stateFlow.update { curr ->
                    curr.copy(
                        voicePhase = VoicePhase.Idle,
                        isJevLoading = false,
                        isJevThinking = false,
                        jevErrorMessage = friendlyError
                    )
                }
                host.showQuickActionToast("⚠ ${friendlyError.take(24)}")
            }
        )
    }

    fun finalizeJevDraftAndClearMemory(clearContext: Boolean = true) {
        jevInferenceJob?.cancel()
        jevInferenceJob = null
        if (jevDraftSession.hasActiveDraft) {
            val ic = host.currentInputConnection()
            val committed = if (ic != null) {
                jevDraftSession.commitAndFinishOnExit(InputConnectionDraftTarget(ic))
            } else {
                val draft = jevDraftSession.currentDraftText
                jevDraftSession.resetSessionOnly()
                draft
            }
            if (!committed.isNullOrEmpty()) {
                onExternalCommitToEngine(committed)
                val undo = ImeLogic.createUndoSnapshot(
                    committedText = committed,
                    replacedSelection = ""
                )
                host.pushUndoSnapshot(undo)
                host.stateFlow.update {
                    it.copy(undoSnapshot = host.peekTopUndoSnapshot())
                }
            }
        } else {
            jevDraftSession.resetSessionOnly()
        }
        host.stateFlow.update { prev ->
            prev.copy(
                jevCopilotResult = null,
                selectedJevStrategyIndex = -1,
                isJevLoading = false,
                jevErrorMessage = null,
                jevUserBottomLine = "",
                jevExcludedReplies = emptyList(),
                isFullScreenPeekCapturing = false,
                isJevContextInspectorOpen = false,
                capturedChatContext = if (clearContext) CapturedChatContext() else prev.capturedChatContext
            )
        }
    }

    private fun triggerChatViewportScreenshotCapture(
        expectedPackage: String,
        appName: String,
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean
    ) {
        chatScreenshotJob?.cancel()
        chatScreenshotJob = host.scope.launch {
            val baseSnap = host.stateFlow.value.capturedChatContext
            val outcome = runCatching {
                TypeFreeAccessibilityService.captureChatViewportOutcomeIfAvailable(
                    expectedPackage = expectedPackage,
                    settings = settings,
                    isIncognitoMode = isIncognitoMode,
                    isPasswordInput = isPasswordInput,
                    ignoreChatContextForTurn = false,
                    forJevCopilot = false,
                    existingMessages = baseSnap.formattedMessages,
                    redactionSession = baseSnap.redactionSession
                )
            }.getOrNull() ?: runCatching {
                val legacyShot = chatScreenshotCaptureProvider(
                    expectedPackage,
                    settings,
                    isIncognitoMode,
                    isPasswordInput,
                    false
                )
                if (legacyShot != null && legacyShot.first.isNotBlank()) {
                    ViewportCaptureOutcome(
                        screenshotJpegBase64 = legacyShot.first,
                        width = legacyShot.second,
                        height = legacyShot.third,
                        mergedMessages = baseSnap.formattedMessages,
                        redactionSession = baseSnap.redactionSession
                    )
                } else {
                    null
                }
            }.getOrNull()

            if (outcome != null && (outcome.screenshotJpegBase64.isNotBlank() || outcome.mergedMessages.isNotEmpty())) {
                host.stateFlow.update { curr ->
                    if (curr.voiceSubPanel == VoiceSubPanel.HELP_ME_WRITE) {
                        val baseCtx = curr.capturedChatContext
                        curr.copy(
                            capturedChatContext = baseCtx.withViewportOutcome(
                                outcome = outcome,
                                fallbackPackage = expectedPackage,
                                fallbackAppName = appName
                            )
                        )
                    } else {
                        curr
                    }
                }
            }
        }
    }

    fun acceptChatContextDisclosure() {
        host.performKeyFeedback(strong = false)
        val updatedSettings = host.repository.settings.value.copy(
            chatContextReplyEnabled = true,
            chatContextDisclosureAccepted = true,
            chatContextDisclosureDismissed = false
        )
        host.repository.updateSettings {
            it.copy(
                chatContextReplyEnabled = true,
                chatContextDisclosureAccepted = true,
                chatContextDisclosureDismissed = false
            )
        }
        val stateSnap = host.stateFlow.value
        val isPassword = ImeLogic.isPasswordEditorInputType(stateSnap.currentInputType)
        val isJev = stateSnap.voiceSubPanel == VoiceSubPanel.JEV_COPILOT
        val captured = if (isJev) {
            TypeFreeAccessibilityService.captureChatContextIfAvailable(
                expectedPackage = stateSnap.targetPackage,
                appName = stateSnap.targetAppName,
                settings = updatedSettings,
                isIncognitoMode = stateSnap.isIncognitoMode,
                isPasswordInput = isPassword,
                ignoreChatContextForTurn = false,
                forJevCopilot = true
            )
        } else {
            chatContextCaptureProvider(
                stateSnap.targetPackage,
                stateSnap.targetAppName,
                updatedSettings,
                stateSnap.isIncognitoMode,
                isPassword,
                false
            )
        }
        host.stateFlow.update {
            it.copy(
                capturedChatContext = captured,
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = false,
                isJevLoading = isJev,
                jevErrorMessage = if (isJev) null else it.jevErrorMessage
            )
        }
        if (isJev) {
            triggerJevCopilotInference(isRefresh = false)
        } else {
            triggerChatViewportScreenshotCapture(
                expectedPackage = stateSnap.targetPackage,
                appName = stateSnap.targetAppName,
                settings = updatedSettings,
                isIncognitoMode = stateSnap.isIncognitoMode,
                isPasswordInput = isPassword
            )
        }
    }

    fun declineChatContextDisclosure() {
        host.performKeyFeedback(strong = false)
        chatScreenshotJob?.cancel()
        host.repository.updateSettings {
            it.copy(
                chatContextReplyEnabled = false,
                chatContextDisclosureAccepted = false,
                chatContextDisclosureDismissed = true
            )
        }
        val isJev = host.stateFlow.value.voiceSubPanel == VoiceSubPanel.JEV_COPILOT
        host.stateFlow.update {
            it.copy(
                capturedChatContext = CapturedChatContext(
                    packageName = it.targetPackage,
                    appName = it.targetAppName,
                    domainProfile = JevDomainProfile.fromPackage(it.targetPackage)
                ),
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = false,
                isJevLoading = isJev,
                jevErrorMessage = if (isJev) null else it.jevErrorMessage
            )
        }
        if (isJev) {
            triggerJevCopilotInference(isRefresh = false)
        }
    }

    fun toggleIgnoreChatContextForTurn() {
        host.performKeyFeedback(strong = false)
        host.stateFlow.update {
            it.copy(ignoreChatContextForThisTurn = !it.ignoreChatContextForThisTurn)
        }
    }

    fun toggleHelpWritePreset(preset: HelpWritePromptPreset) {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return
        host.performKeyFeedback(strong = false)
        host.stateFlow.update { curr ->
            val nextSelected = if (curr.selectedHelpWritePreset?.id == preset.id) null else preset
            curr.copy(selectedHelpWritePreset = nextSelected)
        }
    }

    fun runSelectedHelpWritePresetDirect() {
        val selected = host.stateFlow.value.selectedHelpWritePreset ?: return
        runHelpWritePreset(selected)
    }

    fun closeVoiceSubPanel() {
        host.performKeyFeedback(strong = false)
        chatScreenshotJob?.cancel()
        finalizeJevDraftAndClearMemory(clearContext = true)
        if (host.stateFlow.value.voicePhase is VoicePhase.Recording ||
            host.stateFlow.value.voicePhase is VoicePhase.Processing
        ) {
            cancelRecordingOrProcessing()
        }
        host.stateFlow.update {
            it.copy(
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE,
                capturedChatContext = CapturedChatContext(),
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = false,
                isJevContextInspectorOpen = false,
                selectedHelpWritePreset = null
            )
        }
    }

    fun dismissSpeakToEditForTurn() {
        host.performKeyFeedback(strong = false)
        if (host.stateFlow.value.voicePhase is VoicePhase.Recording ||
            host.stateFlow.value.voicePhase is VoicePhase.Processing
        ) {
            cancelRecordingOrProcessing()
        }
        host.stateFlow.update {
            it.copy(
                ignoreSelectionForThisTurn = true,
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE
            )
        }
    }

    fun selectTranslateLanguage(language: String) {
        if (language.isBlank()) return
        host.performKeyFeedback(strong = false)
        host.repository.updateSettings {
            it.copy(activeTranslateTarget = language)
        }
        host.stateFlow.update {
            it.copy(arcSelectedLanguage = language)
        }
    }

    fun togglePunctuationStrip() {
        host.performKeyFeedback(strong = false)
        host.stateFlow.update {
            it.copy(
                isPunctuationStripOpen = !it.isPunctuationStripOpen,
                isStylePickerExpanded = false
            )
        }
    }

    fun enterArcTranslateMode(startedFromHandsFree: Boolean) {
        val curr = host.stateFlow.value
        if (curr.voicePhase !is VoicePhase.Recording) return
        val settings = host.repository.settings.value
        val defaultLang = settings.activeTranslateTarget.ifBlank {
            settings.translationTargets.firstOrNull() ?: DefaultPresets.defaultTranslationTargets().first()
        }
        activeComposer?.cancelSession(rollbackWrittenText = true)
        activeComposer = null
        host.performHaptic(strong = false)
        host.stateFlow.update {
            it.copy(
                isArcTranslateActive = true,
                arcStartedFromHandsFree = startedFromHandsFree,
                isArcCancelHovered = false,
                arcSelectedLanguage = defaultLang,
                activeIntentMode = VoiceIntentMode.TRANSLATE,
                isPunctuationStripOpen = false,
                isStylePickerExpanded = false,
                liveWriteStatus = LiveWriteUiStatus.INACTIVE
            )
        }
    }

    fun updateArcTranslateSelection(language: String, cancelHovered: Boolean) {
        val prev = host.stateFlow.value
        if (!prev.isArcTranslateActive) return
        val changed = prev.arcSelectedLanguage != language || prev.isArcCancelHovered != cancelHovered
        if (!changed) return

        host.performHaptic(strong = false)
        host.stateFlow.update {
            it.copy(
                arcSelectedLanguage = language,
                isArcCancelHovered = cancelHovered
            )
        }
    }

    fun releaseArcTranslate() {
        val snapshot = host.stateFlow.value
        micPressDownTimeMs = 0L
        if (!snapshot.isArcTranslateActive) {
            handleMicPressUp()
            return
        }

        if (snapshot.isArcCancelHovered) {
            cancelRecordingOrProcessing()
            return
        }

        val chosenLang = snapshot.arcSelectedLanguage.ifBlank {
            host.repository.settings.value.activeTranslateTarget.ifBlank { "English (US)" }
        }
        host.repository.updateSettings {
            it.copy(activeTranslateTarget = chosenLang)
        }

        val recorder = runCatching { audioRecorderProvider() }.getOrNull()
        if (ImeLogic.shouldLockArcIntoHandsFree(snapshot.arcStartedFromHandsFree)) {
            host.performHaptic(strong = false)
            recorder?.clearSilencePrefix(skipHapticMs = 120L)
            host.stateFlow.update {
                it.copy(
                    voicePhase = VoicePhase.Recording(isHandsFree = true),
                    isArcTranslateActive = false,
                    arcStartedFromHandsFree = false,
                    isArcCancelHovered = false,
                    arcSelectedLanguage = chosenLang,
                    activeIntentMode = VoiceIntentMode.TRANSLATE
                )
            }
            startHandsFreeSilenceMonitor()
        } else {
            host.performKeyFeedback(strong = true)
            host.stateFlow.update {
                it.copy(
                    isArcTranslateActive = false,
                    arcStartedFromHandsFree = false,
                    isArcCancelHovered = false,
                    arcSelectedLanguage = chosenLang,
                    activeIntentMode = VoiceIntentMode.TRANSLATE
                )
            }
            stopRecordingAndRunPipeline(
                intentOverride = VoiceIntentMode.TRANSLATE,
                translateLangOverride = chosenLang
            )
        }
    }

    fun handleMicPressDown(intentMode: VoiceIntentMode = VoiceIntentMode.DICTATE) {
        val currentPhase = host.stateFlow.value.voicePhase
        if (currentPhase is VoicePhase.Processing) return
        if (currentPhase is VoicePhase.Recording && currentPhase.isHandsFree) return

        host.tryAutoLearnFromRecentEdit()
        toastDismissJob?.cancel()

        val defaultTranslateLang = host.repository.settings.value.activeTranslateTarget
        host.stateFlow.update {
            it.copy(
                activeIntentMode = intentMode,
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false,
                arcSelectedLanguage = defaultTranslateLang,
                isPunctuationStripOpen = false,
                isStylePickerExpanded = false,
                isHistoryDrawerOpen = false
            )
        }

        val audioRecorder = audioRecorderProvider()
        if (!audioRecorder.hasRecordPermission()) {
            micPressDownTimeMs = 0L
            host.performHaptic(strong = true)
            host.stateFlow.update {
                it.copy(
                    voicePhase = VoicePhase.Error(
                        message = "尚未授予麦克风录音权限，请先授权后使用",
                        canRetryAudio = false,
                        needsApiSetup = false,
                        needsMicPermission = true
                    )
                )
            }
            return
        }

        // Fire tactile feedback BEFORE opening AudioRecord so speaker click / motor hum isn't recorded
        host.performHaptic(strong = true)

        abortActiveStreamingSession()
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
        activeComposer?.cancelSession(rollbackWrittenText = false)
        activeComposer = null

        val settingsSnap = host.repository.settings.value
        val prevState = host.stateFlow.value
        val liveSelected = host.readSelectedTextSafely().ifBlank { prevState.rawSelectedText }
        val effectiveSelected = if (prevState.ignoreSelectionForThisTurn) "" else liveSelected
        val resolvedStartIntent = when {
            intentMode != VoiceIntentMode.DICTATE -> intentMode
            effectiveSelected.isNotBlank() -> VoiceIntentMode.SPEAK_TO_EDIT
            else -> VoiceIntentMode.DICTATE
        }
        preSessionSurroundingContext = host.readSurroundingBeforeCursor(80)

        val streamSession = host.aiEngine.openLiveStreamingSession(settingsSnap)
        val enableLiveWrite = StreamingComposer.shouldEnableLiveWrite(
            settings = settingsSnap,
            intentMode = resolvedStartIntent,
            effectiveSelectedText = effectiveSelected,
            hasStreamingSession = streamSession != null,
            targetPackage = prevState.targetPackage,
            isFloatingBubbleChannel = false
        )

        val startRes = audioRecorder.startRecording(
            scope = host.scope,
            onStreamChunk = if (streamSession != null) {
                { pcmChunk -> streamSession.sendPcmFrame(pcmChunk) }
            } else {
                null
            },
            onSilencePrefixCleared = {
                streamSession?.resetPrefix()
                activeComposer?.cancelSession(rollbackWrittenText = true)
                host.stateFlow.update {
                    it.copy(
                        streamingPartialPreview = "",
                        liveWriteStatus = activeComposer?.uiStatus ?: LiveWriteUiStatus.INACTIVE
                    )
                }
            },
            pauseMediaPlayback = settingsSnap.pauseMediaWhileRecording,
            whisperMode = settingsSnap.whisperModeEnabled
        )
        startRes.fold(
            onSuccess = {
                micPressDownTimeMs = System.currentTimeMillis()
                activeStreamingSession = streamSession
                if (enableLiveWrite) {
                    onResetComposing()
                    val anchor = resolveCurrentCursorAnchor()
                    val composer = StreamingComposer(
                        editorBridge = InputConnectionEditorBridge { host.currentInputConnection() }
                    )
                    composer.startSession(
                        anchorCursor = anchor,
                        smartDirectMode = StreamingComposer.isSmartDirectDictation(settingsSnap, resolvedStartIntent)
                    )
                    activeComposer = composer
                } else {
                    activeComposer = null
                }

                if (streamSession != null) {
                    streamingPreviewJob = host.scope.launch {
                        streamSession.transcript.collect { liveTranscript ->
                            val composer = activeComposer
                            if (composer != null && composer.isActive) {
                                composer.onTranscriptUpdate(liveTranscript)
                                host.stateFlow.update {
                                    it.copy(
                                        streamingPartialPreview = liveTranscript.fullText,
                                        liveWriteStatus = composer.uiStatus
                                    )
                                }
                            } else {
                                host.stateFlow.update {
                                    it.copy(streamingPartialPreview = liveTranscript.fullText)
                                }
                            }
                        }
                    }
                }
                // Warm up LLM / HTTP STT TCP+TLS sockets during the first ~250ms of speaking
                host.scope.launch {
                    host.aiEngine.prewarmEndpoints(settingsSnap)
                }
                host.stateFlow.update {
                    it.copy(
                        voicePhase = VoicePhase.Recording(isHandsFree = false),
                        streamingPartialPreview = "",
                        liveWriteStatus = activeComposer?.uiStatus ?: LiveWriteUiStatus.INACTIVE
                    )
                }
            },
            onFailure = { err ->
                streamSession?.cancel()
                activeComposer = null
                micPressDownTimeMs = 0L
                host.performHaptic(strong = true)
                val errorPhase = ImeLogic.classifyErrorFlags(
                    errorMessage = err.message ?: "无法启动麦克风录音",
                    hasCachedAudio = host.hasRetryWavFile(),
                    hasMicPermission = audioRecorder.hasRecordPermission()
                )
                host.stateFlow.update {
                    it.copy(
                        voicePhase = errorPhase,
                        streamingPartialPreview = "",
                        liveWriteStatus = LiveWriteUiStatus.INACTIVE
                    )
                }
            }
        )
    }

    fun abortActiveStreamingSession() {
        streamingPreviewJob?.cancel()
        streamingPreviewJob = null
        activeStreamingSession?.cancel()
        activeStreamingSession = null
    }

    fun handleMicPressUp() {
        if (host.stateFlow.value.isArcTranslateActive) {
            releaseArcTranslate()
            return
        }

        val downTime = micPressDownTimeMs
        micPressDownTimeMs = 0L
        val phase = host.stateFlow.value.voicePhase
        if (downTime <= 0L || phase !is VoicePhase.Recording || phase.isHandsFree) {
            return
        }

        val heldDurationMs = System.currentTimeMillis() - downTime
        val isFromQwertyTopBar = host.stateFlow.value.tabMode == KeyboardTabMode.QWERTY
        if (isFromQwertyTopBar || ImeLogic.isPressAndHoldGesture(heldDurationMs)) {
            host.performKeyFeedback(strong = false)
            stopRecordingAndRunPipeline()
        } else {
            host.performHaptic(strong = false)
            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Recording(isHandsFree = true))
            }
            startHandsFreeSilenceMonitor()
        }
    }

    private fun startHandsFreeSilenceMonitor() {
        handsFreeSilenceJob?.cancel()
        val recorder = runCatching { audioRecorderProvider() }.getOrNull() ?: return
        handsFreeSilenceJob = host.scope.launch {
            var lastVoiceActivityAtMs = System.currentTimeMillis()
            val ampCollectorJob = launch {
                recorder.amplitudes.collect { amps ->
                    if (!isSilentAmplitudeFrame(amps)) {
                        lastVoiceActivityAtMs = System.currentTimeMillis()
                    }
                }
            }
            try {
                while (true) {
                    delay(200L)
                    val curr = host.stateFlow.value
                    val phase = curr.voicePhase
                    if (phase !is VoicePhase.Recording || !phase.isHandsFree) {
                        break
                    }
                    if (curr.isArcTranslateActive) {
                        lastVoiceActivityAtMs = System.currentTimeMillis()
                        continue
                    }
                    if (!isSilentAmplitudeFrame(recorder.amplitudes.value)) {
                        lastVoiceActivityAtMs = System.currentTimeMillis()
                        continue
                    }
                    if (System.currentTimeMillis() - lastVoiceActivityAtMs >= HANDS_FREE_SILENCE_AUTO_STOP_MS) {
                        finishHandsFreeRecording()
                        break
                    }
                }
            } finally {
                ampCollectorJob.cancel()
            }
        }
    }

    private fun resolveCurrentCursorAnchor(): Int {
        val ic = host.currentInputConnection()
        if (ic != null) {
            val extracted = runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
            if (extracted != null && extracted.selectionStart >= 0) {
                return extracted.selectionStart
            }
            val before = runCatching { ic.getTextBeforeCursor(4000, 0) }.getOrNull()
            if (before != null) {
                return before.length
            }
        }
        return lastKnownSelectionStart.coerceAtLeast(0)
    }

    fun finishHandsFreeRecording() {
        val snapshot = host.stateFlow.value
        if (snapshot.voicePhase !is VoicePhase.Recording) return
        host.performKeyFeedback(strong = true)
        if (snapshot.activeIntentMode == VoiceIntentMode.TRANSLATE) {
            val targetLang = snapshot.arcSelectedLanguage.ifBlank {
                host.repository.settings.value.activeTranslateTarget
            }
            stopRecordingAndRunPipeline(
                intentOverride = VoiceIntentMode.TRANSLATE,
                translateLangOverride = targetLang
            )
        } else {
            stopRecordingAndRunPipeline()
        }
    }

    fun cancelRecordingOrProcessing() {
        host.performKeyFeedback(strong = false)
        micPressDownTimeMs = 0L
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
        abortActiveStreamingSession()
        activeComposer?.cancelSession(rollbackWrittenText = true)
        activeComposer = null
        activePipelineJob?.cancel()
        activePipelineJob = null
        val audioRecorder = runCatching { audioRecorderProvider() }.getOrNull()
        if (audioRecorder != null) {
            host.scope.launch {
                audioRecorder.cancelRecording()
            }
        }
        host.stateFlow.update {
            it.copy(
                voicePhase = VoicePhase.Idle,
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false,
                streamingPartialPreview = "",
                liveWriteStatus = LiveWriteUiStatus.INACTIVE
            )
        }
    }

    fun stopRecordingAndRunPipeline(
        intentOverride: VoiceIntentMode? = null,
        translateLangOverride: String? = null
    ) {
        val liveSession = activeStreamingSession
        activeStreamingSession = null
        streamingPreviewJob?.cancel()
        streamingPreviewJob = null
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null

        val audioRecorder = audioRecorderProvider()
        activePipelineJob?.cancel()
        activePipelineJob = host.scope.launch {
            val stopResult = audioRecorder.stopRecording()
            stopResult.fold(
                onSuccess = { recorded ->
                    var preTranscribed: String? = null
                    var tailWaitMs = 0L

                    if (liveSession != null) {
                        host.stateFlow.update {
                            it.copy(
                                voicePhase = VoicePhase.Processing(stageText = "收尾识别中..."),
                                isArcTranslateActive = false,
                                arcStartedFromHandsFree = false,
                                isArcCancelHovered = false,
                                streamingPartialPreview = ""
                            )
                        }
                        val tailStart = System.currentTimeMillis()
                        val streamRes = liveSession.finishAndAwaitFinal()
                        tailWaitMs = (System.currentTimeMillis() - tailStart).coerceAtLeast(0L)

                        val streamErr = streamRes.exceptionOrNull()
                        if (streamErr is IllegalStateException) {
                            activeComposer?.cancelSession(rollbackWrittenText = true)
                            activeComposer = null
                            val errorPhase = ImeLogic.classifyErrorFlags(
                                errorMessage = streamErr.message ?: "请先完成语音 API 配置",
                                hasCachedAudio = recorded.wavFile.exists(),
                                hasMicPermission = audioRecorder.hasRecordPermission()
                            )
                            host.stateFlow.update {
                                it.copy(
                                    voicePhase = errorPhase,
                                    hasCachedAudioForRetry = errorPhase.canRetryAudio,
                                    liveWriteStatus = LiveWriteUiStatus.INACTIVE
                                )
                            }
                            return@launch
                        }

                        val candidate = streamRes.getOrNull()?.trim().orEmpty()
                        if (candidate.isNotEmpty() && !AiPipelineEngine.isHallucinatedSilence(candidate)) {
                            preTranscribed = candidate
                        }
                    }

                    if (preTranscribed == null && !recorded.hasValidSpeech) {
                        liveSession?.cancel()
                        activeComposer?.cancelSession(rollbackWrittenText = true)
                        activeComposer = null
                        val errorPhase = ImeLogic.classifyErrorFlags(
                            errorMessage = "未检测到清晰说话声，请靠近麦克风重试",
                            hasCachedAudio = false,
                            hasMicPermission = audioRecorder.hasRecordPermission()
                        )
                        host.stateFlow.update {
                            it.copy(
                                voicePhase = errorPhase,
                                isArcTranslateActive = false,
                                arcStartedFromHandsFree = false,
                                isArcCancelHovered = false,
                                streamingPartialPreview = "",
                                hasCachedAudioForRetry = false,
                                liveWriteStatus = LiveWriteUiStatus.INACTIVE
                            )
                        }
                        return@launch
                    }

                    executeVoicePipelineOnWav(
                        wavFile = recorded.wavFile,
                        durationMs = recorded.durationMs,
                        intentOverride = intentOverride,
                        translateLangOverride = translateLangOverride,
                        preTranscribedText = preTranscribed,
                        sttTailWaitMs = tailWaitMs
                    )
                },
                onFailure = { err ->
                    liveSession?.cancel()
                    activeComposer?.cancelSession(rollbackWrittenText = true)
                    activeComposer = null
                    if (err is CancellationException) return@launch
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "录音时间过短，请重试",
                        hasCachedAudio = host.hasRetryWavFile(),
                        hasMicPermission = audioRecorder.hasRecordPermission()
                    )
                    host.stateFlow.update {
                        it.copy(
                            voicePhase = errorPhase,
                            isArcTranslateActive = false,
                            arcStartedFromHandsFree = false,
                            isArcCancelHovered = false,
                            streamingPartialPreview = "",
                            hasCachedAudioForRetry = errorPhase.canRetryAudio,
                            liveWriteStatus = LiveWriteUiStatus.INACTIVE
                        )
                    }
                }
            )
        }
    }

    private suspend fun executeVoicePipelineOnWav(
        wavFile: File,
        durationMs: Long,
        intentOverride: VoiceIntentMode? = null,
        translateLangOverride: String? = null,
        preTranscribedText: String? = null,
        sttTailWaitMs: Long = 0L
    ) {
        val settings = host.repository.settings.value
        val snapshot = host.stateFlow.value
        val targetPkg = snapshot.targetPackage
        val targetAppName = snapshot.targetAppName
        val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

        val liveSelection = host.readSelectedTextSafely().ifBlank { snapshot.rawSelectedText }
        val effectiveSelected = if (snapshot.ignoreSelectionForThisTurn) "" else liveSelection

        val resolvedIntent = when {
            intentOverride != null -> intentOverride
            snapshot.voiceSubPanel == VoiceSubPanel.JEV_COPILOT ||
                snapshot.activeIntentMode == VoiceIntentMode.JEV_COPILOT -> VoiceIntentMode.JEV_COPILOT
            snapshot.activeIntentMode == VoiceIntentMode.TRANSLATE -> VoiceIntentMode.TRANSLATE
            snapshot.activeIntentMode == VoiceIntentMode.HELP_ME_WRITE -> VoiceIntentMode.HELP_ME_WRITE
            effectiveSelected.isNotBlank() -> VoiceIntentMode.SPEAK_TO_EDIT
            else -> VoiceIntentMode.DICTATE
        }
        if (resolvedIntent == VoiceIntentMode.JEV_COPILOT) {
            jevInferenceJob?.cancel()
            jevInferenceJob = null
            activeComposer?.cancelSession(rollbackWrittenText = true)
            activeComposer = null
            host.stateFlow.update {
                it.copy(
                    voicePhase = VoicePhase.Idle,
                    isArcTranslateActive = false,
                    arcStartedFromHandsFree = false,
                    isArcCancelHovered = false,
                    streamingPartialPreview = "",
                    liveWriteStatus = LiveWriteUiStatus.INACTIVE,
                    isJevLoading = true,
                    jevErrorMessage = null,
                    selectedJevStrategyIndex = -1
                )
            }
            executeJevCopilotInferenceSuspend(
                isRefresh = false,
                wavFile = wavFile,
                pretranscribedSpeech = preTranscribedText
            )
            return
        }
        val isSpeakToEdit = resolvedIntent == VoiceIntentMode.SPEAK_TO_EDIT
        val targetTranslateLang = if (resolvedIntent == VoiceIntentMode.TRANSLATE) {
            (translateLangOverride ?: settings.activeTranslateTarget).ifBlank { "English (US)" }
        } else {
            ""
        }

        val composer = activeComposer
        val surroundingContext = if (composer != null && !composer.wasFrozenDuringSession) {
            preSessionSurroundingContext
        } else {
            host.readSurroundingBeforeCursor(80)
        }

        if (composer != null && composer.isActive) {
            if (resolvedIntent != VoiceIntentMode.DICTATE) {
                composer.cancelSession(rollbackWrittenText = true)
                activeComposer = null
            } else {
                val needsPolish = !StreamingComposer.isSmartDirectDictation(settings, resolvedIntent)
                composer.onRecordingFinished(
                    finalRawTranscript = preTranscribedText.orEmpty(),
                    needsPolishing = needsPolish
                )
            }
        }

        val initialStage = when {
            settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT -> PipelineStage.MULTIMODAL_PROCESSING
            !preTranscribedText.isNullOrBlank() -> PipelineStage.POLISHING_LLM
            else -> PipelineStage.TRANSCRIBING_STT
        }

        host.stateFlow.update {
            it.copy(
                voicePhase = VoicePhase.Processing(
                    stageText = ImeLogic.stageDisplayTextForIntent(
                        stage = initialStage,
                        intentMode = resolvedIntent,
                        targetLanguage = targetTranslateLang
                    )
                ),
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false,
                streamingPartialPreview = "",
                hasCachedAudioForRetry = wavFile.exists(),
                liveWriteStatus = activeComposer?.uiStatus ?: LiveWriteUiStatus.INACTIVE
            )
        }

        val activeChatContext = if (resolvedIntent == VoiceIntentMode.HELP_ME_WRITE) {
            snapshot.effectiveChatContextMessages
        } else {
            emptyList()
        }
        val activeChatScreenshot = if (resolvedIntent == VoiceIntentMode.HELP_ME_WRITE) {
            snapshot.effectiveChatScreenshotBase64
        } else {
            ""
        }
        val activeAttitudeInstruction = if (resolvedIntent == VoiceIntentMode.HELP_ME_WRITE) {
            snapshot.selectedHelpWritePreset?.instruction.orEmpty()
        } else {
            ""
        }

        val pipelineResult = host.aiEngine.processVoiceAudio(
            wavFile = wavFile,
            settings = settings,
            activeStyle = activeStyle,
            targetAppName = targetAppName,
            selectedText = if (isSpeakToEdit) effectiveSelected else "",
            surroundingBeforeCursor = surroundingContext,
            intentMode = resolvedIntent,
            translateTargetLanguage = targetTranslateLang.ifBlank { settings.activeTranslateTarget },
            preTranscribedText = preTranscribedText,
            sttTailWaitMs = sttTailWaitMs,
            chatContextMessages = activeChatContext,
            chatContextScreenshotBase64 = activeChatScreenshot,
            attitudeInstruction = activeAttitudeInstruction,
            editorDraft = if (resolvedIntent == VoiceIntentMode.HELP_ME_WRITE) snapshot.currentEditorText.trim() else "",
            onStageChanged = { stage ->
                host.stateFlow.update { curr ->
                    curr.copy(
                        voicePhase = VoicePhase.Processing(
                            stageText = ImeLogic.stageDisplayTextForIntent(
                                stage = stage,
                                intentMode = resolvedIntent,
                                targetLanguage = targetTranslateLang
                            )
                        )
                    )
                }
            }
        )

        pipelineResult.fold(
            onSuccess = { exec ->
                val finalOutput = exec.finalOutput
                val displayLatencyMs = ImeLogic.resolveSuccessToastElapsedMs(
                    pipelineLatencyMs = exec.latencyMs,
                    recordingDurationMs = durationMs
                )
                val activeComp = activeComposer
                activeComposer = null
                if (activeComp != null) {
                    finalizeLiveWriteOutput(
                        composer = activeComp,
                        finalText = finalOutput,
                        rawTranscript = exec.rawTranscript,
                        latencyMs = displayLatencyMs
                    )
                } else {
                    if (resolvedIntent == VoiceIntentMode.HELP_ME_WRITE && snapshot.currentEditorText.isNotBlank()) {
                        replaceFullEditorTextSafely(
                            finalOutput = finalOutput,
                            originalText = snapshot.currentEditorText
                        )
                        host.stateFlow.update {
                            it.copy(
                                voicePhase = VoicePhase.SuccessToast(
                                    preview = ImeLogic.formatSelectionPreview(finalOutput, 24),
                                    latencyMs = displayLatencyMs
                                ),
                                voiceSubPanel = VoiceSubPanel.NONE,
                                activeIntentMode = VoiceIntentMode.DICTATE,
                                currentEditorText = finalOutput,
                                selectedHelpWritePreset = null,
                                undoSnapshot = host.peekTopUndoSnapshot(),
                                rawSelectedText = "",
                                ignoreSelectionForThisTurn = false,
                                liveWriteStatus = LiveWriteUiStatus.INACTIVE
                            )
                        }
                        scheduleToastReset()
                    } else {
                        val overwritten = if (isSpeakToEdit) liveSelection else host.readSelectedTextSafely()
                        commitPipelineOutput(
                            finalText = finalOutput,
                            overwrittenSelection = overwritten,
                            latencyMs = displayLatencyMs
                        )
                    }
                }
                val isIncognito = host.stateFlow.value.isIncognitoMode || settings.manualIncognitoMode
                if (isIncognito) {
                    runCatching { wavFile.delete() }
                } else {
                    val historyAudioFile = host.archiveWavForHistory(wavFile, settings.historyRetention)
                    host.repository.addHistory(
                        HistoryRecord(
                            targetAppName = targetAppName,
                            targetPackage = targetPkg,
                            styleName = activeStyle.name,
                            isSpeakToEdit = isSpeakToEdit,
                            intentMode = resolvedIntent,
                            translateTargetLanguage = targetTranslateLang,
                            originalSelectedText = if (isSpeakToEdit) effectiveSelected else "",
                            rawTranscript = exec.rawTranscript,
                            polishedText = finalOutput,
                            durationMs = durationMs,
                            latencyMs = exec.latencyMs,
                            modeLabel = exec.modeSummary,
                            audioFilePath = historyAudioFile.absolutePath
                        )
                    )
                }
                if (host.repository.isImeWindowVisible.value) {
                    warmVoiceConnectionsInBackground()
                }
            },
            onFailure = { err ->
                activeComposer?.cancelSession(rollbackWrittenText = false)
                activeComposer = null
                if (err is CancellationException) return
                host.performHaptic(strong = true)
                val audioRecorder = runCatching { audioRecorderProvider() }.getOrNull()
                val errorPhase = ImeLogic.classifyErrorFlags(
                    errorMessage = err.message ?: "语音处理失败，请检查网络或 API 配置",
                    hasCachedAudio = wavFile.exists(),
                    hasMicPermission = audioRecorder?.hasRecordPermission() == true
                )
                host.stateFlow.update {
                    it.copy(
                        voicePhase = errorPhase,
                        hasCachedAudioForRetry = errorPhase.canRetryAudio,
                        liveWriteStatus = LiveWriteUiStatus.INACTIVE
                    )
                }
            }
        )
    }

    fun runQuickEditChip(chip: QuickEditChip) {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        val liveSelected = host.readSelectedTextSafely().ifBlank { host.stateFlow.value.effectiveSelectedText }
        if (liveSelected.isBlank()) return

        host.performKeyFeedback(strong = false)
        toastDismissJob?.cancel()
        activePipelineJob?.cancel()

        activePipelineJob = host.scope.launch {
            val settings = host.repository.settings.value
            val snapshot = host.stateFlow.value
            val targetPkg = snapshot.targetPackage
            val targetAppName = snapshot.targetAppName
            val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Processing(stageText = "${chip.label}中..."))
            }

            val transformResult = host.aiEngine.runTextTransform(
                settings = settings,
                activeStyle = activeStyle,
                rawOrSelectedText = liveSelected,
                explicitInstruction = chip.instruction,
                targetAppName = targetAppName
            )

            transformResult.fold(
                onSuccess = { exec ->
                    val finalOutput = exec.finalOutput
                    commitPipelineOutput(
                        finalText = finalOutput,
                        overwrittenSelection = liveSelected,
                        latencyMs = exec.latencyMs
                    )
                    if (!host.stateFlow.value.isIncognitoMode) {
                        host.repository.addHistory(
                            HistoryRecord(
                                targetAppName = targetAppName,
                                targetPackage = targetPkg,
                                styleName = activeStyle.name,
                                isSpeakToEdit = true,
                                intentMode = VoiceIntentMode.SPEAK_TO_EDIT,
                                translateTargetLanguage = "",
                                originalSelectedText = liveSelected,
                                rawTranscript = "[快捷改写] ${chip.label}",
                                polishedText = finalOutput,
                                durationMs = 0L,
                                latencyMs = exec.latencyMs,
                                modeLabel = exec.modeSummary,
                                audioFilePath = ""
                            )
                        )
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    host.performHaptic(strong = true)
                    val audioRecorder = runCatching { audioRecorderProvider() }.getOrNull()
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "改写失败，请检查网络或 API 配置",
                        hasCachedAudio = false,
                        hasMicPermission = audioRecorder?.hasRecordPermission() == true
                    )
                    host.stateFlow.update { it.copy(voicePhase = errorPhase) }
                }
            )
        }
    }

    fun runHelpWritePreset(preset: HelpWritePromptPreset) {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        host.performKeyFeedback(strong = false)
        toastDismissJob?.cancel()
        activePipelineJob?.cancel()

        activePipelineJob = host.scope.launch {
            val settings = host.repository.settings.value
            val snapshot = host.stateFlow.value
            val targetPkg = snapshot.targetPackage
            val targetAppName = snapshot.targetAppName
            val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Processing(stageText = "正在起草：${preset.label}..."))
            }

            val activeChatContext = snapshot.effectiveChatContextMessages
            val activeChatScreenshot = snapshot.effectiveChatScreenshotBase64
            val draftInstruction = if (activeChatContext.isNotEmpty() || activeChatScreenshot.isNotBlank()) {
                preset.instruction
            } else {
                "请忽略<selected_text>中的占位文字，直接根据以下写作需求起草完整、自然得体、可直接发送的成稿正文（绝不输出任何寒暄或解释）：${preset.instruction}"
            }

            val transformResult = host.aiEngine.runTextTransform(
                settings = settings,
                activeStyle = activeStyle,
                rawOrSelectedText = preset.label,
                explicitInstruction = draftInstruction,
                targetAppName = targetAppName,
                chatContextMessages = activeChatContext,
                chatContextScreenshotBase64 = activeChatScreenshot
            )

            transformResult.fold(
                onSuccess = { exec ->
                    val finalOutput = exec.finalOutput
                    val existingSel = host.readSelectedTextSafely()
                    commitPipelineOutput(
                        finalText = finalOutput,
                        overwrittenSelection = existingSel,
                        latencyMs = exec.latencyMs
                    )
                    if (!host.stateFlow.value.isIncognitoMode) {
                        host.repository.addHistory(
                            HistoryRecord(
                                targetAppName = targetAppName,
                                targetPackage = targetPkg,
                                styleName = activeStyle.name,
                                isSpeakToEdit = false,
                                intentMode = VoiceIntentMode.HELP_ME_WRITE,
                                translateTargetLanguage = "",
                                originalSelectedText = "",
                                rawTranscript = "[帮我写灵感] ${preset.instruction}",
                                polishedText = finalOutput,
                                durationMs = 0L,
                                latencyMs = exec.latencyMs,
                                modeLabel = exec.modeSummary,
                                audioFilePath = ""
                            )
                        )
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    host.performHaptic(strong = true)
                    val audioRecorder = runCatching { audioRecorderProvider() }.getOrNull()
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "起草失败，请检查网络或 API 配置",
                        hasCachedAudio = false,
                        hasMicPermission = audioRecorder?.hasRecordPermission() == true
                    )
                    host.stateFlow.update { it.copy(voicePhase = errorPhase) }
                }
            )
        }
    }

    fun retryLastAudio() {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        val wavFile = host.getLastRecordingWavFile()
        if (!wavFile.exists() || wavFile.length() <= 44L) {
            host.stateFlow.update {
                it.copy(
                    voicePhase = VoicePhase.Error(
                        message = "未找到可重试的最近录音文件，请重新说话",
                        canRetryAudio = false,
                        needsApiSetup = false,
                        needsMicPermission = false
                    ),
                    hasCachedAudioForRetry = false
                )
            }
            return
        }

        host.performKeyFeedback(strong = false)
        val estimatedDurationMs =
            ((wavFile.length() - 44L).coerceAtLeast(0L) * 1000L) / (WavAudioRecorder.SAMPLE_RATE * 2L)

        activePipelineJob?.cancel()
        activePipelineJob = host.scope.launch {
            executeVoicePipelineOnWav(wavFile, estimatedDurationMs)
        }
    }

    fun dismissErrorBanner() {
        host.performKeyFeedback(strong = false)
        host.stateFlow.update {
            if (it.voicePhase is VoicePhase.Error) {
                it.copy(voicePhase = VoicePhase.Idle)
            } else {
                it
            }
        }
    }

    private fun finalizeLiveWriteOutput(
        composer: StreamingComposer,
        finalText: String,
        rawTranscript: String,
        latencyMs: Long
    ) {
        val outcome = if (composer.state == StreamingComposer.State.Done &&
            finalText == composer.writtenRawText
        ) {
            ComposerFinalizeOutcome(
                appliedToEditor = true,
                wasFrozen = composer.wasFrozenDuringSession,
                rawWrittenText = composer.writtenRawText,
                finalCommittedText = finalText,
                undoSnapshot = ImeLogic.createUndoSnapshot(
                    committedText = finalText,
                    replacedSelection = ""
                )
            )
        } else {
            composer.applyPolishedResult(
                polishedText = finalText,
                fullRawTranscript = rawTranscript
            )
        }

        if (host.stateFlow.value.capturedChatContext.hasContext) {
            host.clearAutoLearnBaseline()
        } else {
            val fullBufferAfterCommit = host.readFullEditorTextSafely().ifBlank { outcome.finalCommittedText }
            host.onDictationCommittedForAutoLearn(fullBufferAfterCommit)
        }

        host.performHaptic(strong = true)
        if (outcome.finalCommittedText.isNotEmpty()) {
            onExternalCommitToEngine(outcome.finalCommittedText)
        }
        host.pushUndoSnapshot(outcome.undoSnapshot)

        host.stateFlow.update {
            it.copy(
                voicePhase = VoicePhase.SuccessToast(
                    preview = ImeLogic.formatSelectionPreview(outcome.finalCommittedText.ifBlank { finalText }, 24),
                    latencyMs = latencyMs
                ),
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE,
                capturedChatContext = CapturedChatContext(),
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = false,
                selectedHelpWritePreset = null,
                undoSnapshot = host.peekTopUndoSnapshot(),
                rawSelectedText = "",
                ignoreSelectionForThisTurn = false,
                liveWriteStatus = LiveWriteUiStatus.INACTIVE
            )
        }
        scheduleToastReset()
    }

    fun commitPipelineOutput(
        finalText: String,
        overwrittenSelection: String,
        latencyMs: Long
    ) {
        val ic = host.currentInputConnection()
        if (ic != null) {
            ic.beginBatchEdit()
            ic.commitText(finalText, 1)
            ic.endBatchEdit()
        }

        if (host.stateFlow.value.capturedChatContext.hasContext) {
            host.clearAutoLearnBaseline()
        } else {
            val fullBufferAfterCommit = host.readFullEditorTextSafely().ifBlank { finalText }
            host.onDictationCommittedForAutoLearn(fullBufferAfterCommit)
        }

        host.performHaptic(strong = true)
        onExternalCommitToEngine(finalText)
        val undo = ImeLogic.createUndoSnapshot(
            committedText = finalText,
            replacedSelection = overwrittenSelection
        )
        host.pushUndoSnapshot(undo)

        host.stateFlow.update {
            it.copy(
                voicePhase = VoicePhase.SuccessToast(
                    preview = ImeLogic.formatSelectionPreview(finalText, 24),
                    latencyMs = latencyMs
                ),
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE,
                capturedChatContext = CapturedChatContext(),
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = false,
                currentEditorText = finalText,
                selectedHelpWritePreset = null,
                undoSnapshot = host.peekTopUndoSnapshot(),
                rawSelectedText = "",
                ignoreSelectionForThisTurn = false,
                liveWriteStatus = LiveWriteUiStatus.INACTIVE
            )
        }
        scheduleToastReset()
    }

    fun replaceFullEditorTextSafely(finalOutput: String, originalText: String = "") {
        val ic = host.currentInputConnection()
        val currentFull = originalText.ifEmpty { host.readFullEditorTextSafely() }
        if (ic != null) {
            ic.beginBatchEdit()
            val extracted = runCatching {
                ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            }.getOrNull()
            if (extracted?.text != null) {
                ic.setSelection(0, extracted.text.length)
                ic.commitText(finalOutput, 1)
            } else {
                val beforeLen = runCatching {
                    ic.getTextBeforeCursor(4000, 0)?.length ?: currentFull.length
                }.getOrDefault(currentFull.length)
                val afterLen = runCatching {
                    ic.getTextAfterCursor(4000, 0)?.length ?: 0
                }.getOrDefault(0)
                ic.deleteSurroundingText(beforeLen, afterLen)
                ic.commitText(finalOutput, 1)
            }
            ic.endBatchEdit()
        }

        host.clearAutoLearnBaseline()
        host.performHaptic(strong = true)
        if (finalOutput.isNotEmpty()) {
            onExternalCommitToEngine(finalOutput)
        }
        val undo = ImeLogic.createUndoSnapshot(
            committedText = finalOutput,
            replacedSelection = currentFull
        )
        host.pushUndoSnapshot(undo)
    }

    fun runHelpWriteDraftTransform(preset: HelpWritePromptPreset) {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        host.performKeyFeedback(strong = false)
        toastDismissJob?.cancel()
        activePipelineJob?.cancel()

        activePipelineJob = host.scope.launch {
            val settings = host.repository.settings.value
            val snapshot = host.stateFlow.value
            val isPassword = ImeLogic.isPasswordEditorInputType(snapshot.currentInputType)
            val currentDraft = if (isPassword) "" else host.readFullEditorTextSafely().trim().ifBlank { snapshot.currentEditorText.trim() }
            if (currentDraft.isBlank()) return@launch

            val targetPkg = snapshot.targetPackage
            val targetAppName = snapshot.targetAppName
            val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Processing(stageText = "正在${preset.label}..."))
            }

            val transformResult = host.aiEngine.runTextTransform(
                settings = settings,
                activeStyle = activeStyle,
                rawOrSelectedText = currentDraft,
                explicitInstruction = preset.instruction,
                targetAppName = targetAppName,
                chatContextMessages = emptyList(),
                chatContextScreenshotBase64 = ""
            )

            transformResult.fold(
                onSuccess = { exec ->
                    val finalOutput = exec.finalOutput
                    replaceFullEditorTextSafely(finalOutput = finalOutput, originalText = currentDraft)
                    host.stateFlow.update {
                        it.copy(
                            voicePhase = VoicePhase.SuccessToast(
                                preview = ImeLogic.formatSelectionPreview(finalOutput, 24),
                                latencyMs = exec.latencyMs
                            ),
                            tabMode = KeyboardTabMode.QWERTY,
                            voiceSubPanel = VoiceSubPanel.NONE,
                            currentEditorText = finalOutput,
                            selectedHelpWritePreset = null,
                            undoSnapshot = host.peekTopUndoSnapshot(),
                            rawSelectedText = "",
                            ignoreSelectionForThisTurn = false,
                            liveWriteStatus = LiveWriteUiStatus.INACTIVE
                        )
                    }
                    scheduleToastReset()
                    host.showQuickActionToast("✓ 已一键成稿 · 可点 ↶ 撤回")
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    host.performHaptic(strong = true)
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "生成失败，请检查网络或 API 配置",
                        hasCachedAudio = false,
                        hasMicPermission = true
                    )
                    host.stateFlow.update { it.copy(voicePhase = errorPhase) }
                }
            )
        }
    }

    private var askAiJob: Job? = null

    fun openAskAiCardPanel() {
        host.performKeyFeedback(strong = false)
        onResetComposing()
        val stateSnap = host.stateFlow.value
        val isPassword = ImeLogic.isPasswordEditorInputType(stateSnap.currentInputType)
        val fullText = if (isPassword) "" else host.readFullEditorTextSafely().trim().ifBlank { stateSnap.currentEditorText.trim() }
        if (fullText.isBlank()) return

        host.stateFlow.update {
            it.copy(
                tabMode = KeyboardTabMode.VOICE,
                voiceSubPanel = VoiceSubPanel.ASK_AI_CARD,
                activeIntentMode = VoiceIntentMode.DICTATE,
                askAiQuery = fullText,
                askAiAnswer = "",
                isAskAiLoading = true,
                askAiErrorMessage = null,
                isClipboardOpen = false,
                isToolPanelOpen = false,
                selectedHelpWritePreset = null
            )
        }
        triggerAskAiInference(fullText)
    }

    fun triggerAskAiInference(query: String) {
        askAiJob?.cancel()
        askAiJob = host.scope.launch {
            val settings = host.repository.settings.value
            val snapshot = host.stateFlow.value
            val result = host.aiEngine.runAskAiCardQuery(
                settings = settings,
                query = query,
                targetAppName = snapshot.targetAppName
            )
            result.fold(
                onSuccess = { exec ->
                    host.stateFlow.update {
                        it.copy(
                            isAskAiLoading = false,
                            askAiAnswer = exec.finalOutput,
                            askAiErrorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    host.stateFlow.update {
                        it.copy(
                            isAskAiLoading = false,
                            askAiErrorMessage = err.message ?: "解答失败，请检查网络或 API 配置"
                        )
                    }
                }
            )
        }
    }

    fun retryAskAiCard() {
        val q = host.stateFlow.value.askAiQuery
        if (q.isBlank()) return
        host.performKeyFeedback(strong = false)
        host.stateFlow.update {
            it.copy(
                isAskAiLoading = true,
                askAiErrorMessage = null
            )
        }
        triggerAskAiInference(q)
    }

    fun returnFromAskAiToQwerty() {
        host.performKeyFeedback(strong = false)
        askAiJob?.cancel()
        host.stateFlow.update {
            it.copy(
                tabMode = KeyboardTabMode.QWERTY,
                voiceSubPanel = VoiceSubPanel.NONE,
                isAskAiLoading = false,
                askAiErrorMessage = null
            )
        }
    }

    fun dismissAskAiAndClearEditor() {
        host.performKeyFeedback(strong = false)
        askAiJob?.cancel()
        val q = host.stateFlow.value.askAiQuery
        replaceFullEditorTextSafely(finalOutput = "", originalText = q)
        host.stateFlow.update {
            it.copy(
                tabMode = KeyboardTabMode.QWERTY,
                voiceSubPanel = VoiceSubPanel.NONE,
                currentEditorText = "",
                undoSnapshot = host.peekTopUndoSnapshot(),
                isAskAiLoading = false,
                askAiErrorMessage = null
            )
        }
        host.showQuickActionToast("✓ 已清空，可点 ↶ 撤回找回")
    }

    fun copyAskAiAnswer() {
        val answer = host.stateFlow.value.askAiAnswer
        if (answer.isBlank()) return
        host.performKeyFeedback(strong = false)
        host.copyTextToClipboard(answer)
        host.showQuickActionToast("✓ 已复制 AI 解答")
    }

    fun commitAskAiAnswerToEditor() {
        val answer = host.stateFlow.value.askAiAnswer
        if (answer.isBlank()) return
        host.performKeyFeedback(strong = false)
        askAiJob?.cancel()
        val q = host.stateFlow.value.askAiQuery
        replaceFullEditorTextSafely(finalOutput = answer, originalText = q)
        host.stateFlow.update {
            it.copy(
                tabMode = KeyboardTabMode.QWERTY,
                voiceSubPanel = VoiceSubPanel.NONE,
                currentEditorText = answer,
                undoSnapshot = host.peekTopUndoSnapshot(),
                isAskAiLoading = false,
                askAiErrorMessage = null
            )
        }
        host.showQuickActionToast("✓ 已填入输入框 · 可点 ↶ 撤回")
    }

    fun scheduleToastReset() {
        toastDismissJob?.cancel()
        toastDismissJob = host.scope.launch {
            delay(2200L)
            host.stateFlow.update { curr ->
                if (curr.voicePhase is VoicePhase.SuccessToast) {
                    curr.copy(voicePhase = VoicePhase.Idle)
                } else {
                    curr
                }
            }
        }
    }

    fun toggleWhisperMode() {
        host.performKeyFeedback(strong = true)
        val nextWhisper = !host.repository.settings.value.whisperModeEnabled
        host.repository.updateSettings { it.copy(whisperModeEnabled = nextWhisper) }
        host.showQuickActionToast(if (nextWhisper) "🤫 已开启轻声耳语高敏收音" else "🎙️ 已恢复标准收音灵敏度")
    }

    fun runEqualEqualAskAi(fullEditorText: String, query: String) {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        host.performKeyFeedback(strong = true)
        toastDismissJob?.cancel()
        activePipelineJob?.cancel()

        activePipelineJob = host.scope.launch {
            val settings = host.repository.settings.value
            val snapshot = host.stateFlow.value
            val targetPkg = snapshot.targetPackage
            val targetAppName = snapshot.targetAppName
            val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Processing(stageText = "🤖 AI 思考解答中..."))
            }
            if (snapshot.tabMode != KeyboardTabMode.VOICE) {
                host.showQuickActionToast("🤖 AI 正在解答「${query.take(10)}」…")
            }

            val directAskInstruction =
                "用户在输入框通过 == 向你直接提问或下达写作指令。请忽略<selected_text>的选中改写语义，直接把<selected_text>当作用户的问题或指令本身，直接输出准确、简洁、立即可发送或使用的结果正文（严禁寒暄、严禁解释前缀、严禁复述问题）。"

            val transformResult = host.aiEngine.runTextTransform(
                settings = settings,
                activeStyle = activeStyle,
                rawOrSelectedText = query,
                explicitInstruction = directAskInstruction,
                targetAppName = targetAppName
            )

            transformResult.fold(
                onSuccess = { exec ->
                    val finalOutput = exec.finalOutput
                    val ic = host.currentInputConnection()
                    val currentFull = host.readFullEditorTextSafely().ifEmpty { fullEditorText }
                    if (ic != null) {
                        ic.beginBatchEdit()
                        val extracted = runCatching {
                            ic.getExtractedText(ExtractedTextRequest(), 0)
                        }.getOrNull()
                        if (extracted?.text != null) {
                            ic.setSelection(0, extracted.text.length)
                            ic.commitText(finalOutput, 1)
                        } else {
                            val beforeLen = runCatching {
                                ic.getTextBeforeCursor(4000, 0)?.length ?: currentFull.length
                            }.getOrDefault(currentFull.length)
                            val afterLen = runCatching {
                                ic.getTextAfterCursor(4000, 0)?.length ?: 0
                            }.getOrDefault(0)
                            ic.deleteSurroundingText(beforeLen, afterLen)
                            ic.commitText(finalOutput, 1)
                        }
                        ic.endBatchEdit()
                    }

                    host.clearAutoLearnBaseline()
                    host.performHaptic(strong = true)
                    onExternalCommitToEngine(finalOutput)

                    val undo = ImeLogic.createUndoSnapshot(
                        committedText = finalOutput,
                        replacedSelection = currentFull
                    )
                    host.pushUndoSnapshot(undo)

                    host.stateFlow.update {
                        it.copy(
                            voicePhase = VoicePhase.SuccessToast(
                                preview = ImeLogic.formatSelectionPreview(finalOutput, 24),
                                latencyMs = exec.latencyMs
                            ),
                            undoSnapshot = host.peekTopUndoSnapshot(),
                            rawSelectedText = "",
                            ignoreSelectionForThisTurn = false
                        )
                    }
                    scheduleToastReset()
                    if (host.stateFlow.value.tabMode != KeyboardTabMode.VOICE) {
                        host.showQuickActionToast("✓ AI 已填入答案 · 可点 ↶ 撤回")
                    }

                    if (!host.stateFlow.value.isIncognitoMode) {
                        host.repository.addHistory(
                            HistoryRecord(
                                targetAppName = targetAppName,
                                targetPackage = targetPkg,
                                styleName = activeStyle.name,
                                isSpeakToEdit = false,
                                intentMode = VoiceIntentMode.HELP_ME_WRITE,
                                translateTargetLanguage = "",
                                originalSelectedText = currentFull,
                                rawTranscript = "[== 问 AI] $query",
                                polishedText = finalOutput,
                                durationMs = 0L,
                                latencyMs = exec.latencyMs,
                                modeLabel = exec.modeSummary,
                                audioFilePath = ""
                            )
                        )
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    host.performHaptic(strong = true)
                    val audioRecorder = runCatching { audioRecorderProvider() }.getOrNull()
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "AI 回答失败，请检查网络或 API 配置",
                        hasCachedAudio = false,
                        hasMicPermission = audioRecorder?.hasRecordPermission() == true
                    )
                    host.stateFlow.update { it.copy(voicePhase = errorPhase) }
                    if (host.stateFlow.value.tabMode != KeyboardTabMode.VOICE) {
                        host.showQuickActionToast("⚠ ${(err.message ?: "AI 回答失败").take(22)}")
                    }
                }
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onSelectionUpdated(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        if (newSelStart >= 0 && newSelEnd >= 0) {
            lastKnownSelectionStart = newSelStart
            lastKnownSelectionEnd = newSelEnd
        }
        val composer = activeComposer ?: return
        if (!composer.isActive) return
        composer.onUpdateSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val nextUiStatus = composer.uiStatus
        if (host.stateFlow.value.liveWriteStatus != nextUiStatus) {
            host.stateFlow.update { it.copy(liveWriteStatus = nextUiStatus) }
        }
    }

    fun stopRecordingOnTabSwitchOrWindowHidden() {
        finalizeJevDraftAndClearMemory(clearContext = true)
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
        activeComposer?.cancelSession(rollbackWrittenText = false)
        activeComposer = null
        if (host.stateFlow.value.voicePhase is VoicePhase.Recording) {
            micPressDownTimeMs = 0L
            abortActiveStreamingSession()
            val recorder = runCatching { audioRecorderProvider() }.getOrNull()
            if (recorder != null) {
                host.scope.launch {
                    recorder.cancelRecording()
                }
            }
        }
        if (host.stateFlow.value.liveWriteStatus != LiveWriteUiStatus.INACTIVE) {
            host.stateFlow.update { it.copy(liveWriteStatus = LiveWriteUiStatus.INACTIVE) }
        }
    }

    fun onDestroy() {
        micPressDownTimeMs = 0L
        jevInferenceJob?.cancel()
        jevInferenceJob = null
        jevDraftSession.resetSessionOnly()
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
        chatScreenshotJob?.cancel()
        chatScreenshotJob = null
        activeComposer?.cancelSession(rollbackWrittenText = false)
        activeComposer = null
        abortActiveStreamingSession()
        host.aiEngine.evictLiveStreamingPrewarm()
        activePipelineJob?.cancel()
        toastDismissJob?.cancel()
    }

    companion object {
        const val HANDS_FREE_SILENCE_AUTO_STOP_MS: Long = 8_000L
        const val HANDS_FREE_SILENCE_AMPLITUDE_THRESHOLD: Float = 0.12f

        fun isSilentAmplitudeFrame(
            amplitudes: List<Float>,
            threshold: Float = HANDS_FREE_SILENCE_AMPLITUDE_THRESHOLD
        ): Boolean {
            return (amplitudes.lastOrNull() ?: 0.08f) <= threshold
        }

        fun formatJevUserFacingError(err: Throwable?): String {
            if (err == null) return "军师推演异常，请点「↻ 换一批」重试"
            val raw = err.message
                ?.substringBefore("\n--- 诊断日志 ---")
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
                .orEmpty()
            val lower = raw.lowercase()
            return when {
                err is SocketTimeoutException ||
                    err is InterruptedIOException ||
                    lower.contains("timeout") ||
                    lower.contains("timed out") ||
                    raw.contains("超时") ->
                    "模型请求超时，请点「↻ 换一批」重试"
                err is UnknownHostException ||
                    err is ConnectException ||
                    lower.contains("unable to resolve host") ||
                    lower.contains("failed to connect") ||
                    lower.contains("network is unreachable") ||
                    lower.contains("connection reset") ||
                    lower.contains("software caused connection abort") ->
                    "网络连接异常，请检查网络后点「↻ 换一批」重试"
                raw.contains("API Key", ignoreCase = true) ||
                    lower.contains("unauthorized") ||
                    raw.contains("(401)") ||
                    raw.contains(" 401") ->
                    if (raw.contains("API Key", ignoreCase = true)) raw else "API Key 鉴权失败 (401)，请前往设置检查"
                raw.contains("(429)") ||
                    raw.contains(" 429") ||
                    lower.contains("rate limit") ||
                    lower.contains("quota") ||
                    raw.contains("限流") ->
                    "模型接口触发限流 (429)，请稍后点「↻ 换一批」重试"
                raw.contains("(500)") ||
                    raw.contains("(502)") ||
                    raw.contains("(503)") ||
                    raw.contains("(504)") ||
                    raw.contains("服务端错误") ->
                    "模型服务端繁忙，请点「↻ 换一批」重试"
                raw.isBlank() ->
                    "军师推演异常，请点「↻ 换一批」重试"
                raw.length > 48 ->
                    raw.take(46) + "…"
                else ->
                    raw
            }
        }
    }
}
