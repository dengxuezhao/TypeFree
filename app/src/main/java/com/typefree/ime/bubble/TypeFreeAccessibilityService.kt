package com.typefree.ime.bubble

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.data.AppSettings
import com.typefree.ime.privacy.ImagePrivacyGuard
import com.typefree.ime.privacy.LocalPiiRedactor
import com.typefree.ime.privacy.RedactionSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Mirrors official `com.typefree.ime.floating.Services.FloatingAccessibilityService`:
 * 1. Tracks whether an editable text field currently holds input focus across apps.
 * 2. Detects password/sensitive fields and hides the bubble automatically.
 * 3. Extracts selected text & surrounding context for App-Aware Tone and Speak-to-Edit.
 * 4. Injects AI-polished text directly at the cursor via Android 13+ (`API 33+`)
 *    `AccessibilityInputConnection` (`FLAG_INPUT_METHOD_EDITOR`), falling back cleanly
 *    to `ACTION_SET_TEXT` selection splicing or `ACTION_PASTE`.
 * 5. On-demand chat context capture (`captureVisibleChatContextOnDemand` + Android 11+ `takeScreenshot`
 *    cropped to the chat viewport above the keyboard) for "AI 帮回".
 */
class TypeFreeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            var updatedFlags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                updatedFlags = updatedFlags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
            }
            info.flags = updatedFlags
            serviceInfo = info
        } catch (_: Exception) {
        }
        TypeFreeBubbleController.setAccessibilityConnected(true)
        refreshFocusedInputState()
        TypeFreeBubbleController.syncBubbleService(this)
    }

    @Volatile
    private var lastContentChangedHandledAtMs: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val bubbleEnabled = runCatching {
            TypeFreeApp.instance.repository.settings.value.bubbleEnabled
        }.getOrDefault(false)
        val nowMs = SystemClock.uptimeMillis()
        val (shouldProcess, allowMultiWindowScan) = shouldProcessAccessibilityEvent(
            eventType = event.eventType,
            bubbleEnabled = bubbleEnabled,
            nowMs = nowMs,
            lastContentChangedMs = lastContentChangedHandledAtMs
        )
        if (!shouldProcess) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            lastContentChangedHandledAtMs = nowMs
        }
        refreshFocusedInputState(allowMultiWindowScan = allowMultiWindowScan)
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        TypeFreeBubbleController.setAccessibilityConnected(false)
        super.onDestroy()
    }

    fun refreshFocusedInputState(allowMultiWindowScan: Boolean = true): FocusedInputSnapshot {
        val focusedNode = findFocusedEditableNode(allowMultiWindowScan = allowMultiWindowScan)
        if (focusedNode == null) {
            val empty = FocusedInputSnapshot(hasEditableFocus = false)
            TypeFreeBubbleController.updateFocusedInput(empty)
            return empty
        }

        val pkg = focusedNode.packageName?.toString().orEmpty()
        val inputType = focusedNode.inputType
        val isPassword = isPasswordNode(focusedNode, inputType)

        val actualText = readActualNodeText(focusedNode)
        val selStart = focusedNode.textSelectionStart
        val selEnd = focusedNode.textSelectionEnd
        val safeStart = minOf(selStart, selEnd)
        val safeEnd = maxOf(selStart, selEnd)

        val selected = if (
            !isPassword &&
            safeStart >= 0 &&
            safeEnd > safeStart &&
            safeEnd <= actualText.length
        ) {
            actualText.substring(safeStart, safeEnd)
        } else {
            ""
        }

        val beforeCursor = if (
            !isPassword &&
            safeStart > 0 &&
            safeStart <= actualText.length
        ) {
            actualText.substring(0, safeStart).takeLast(80)
        } else if (!isPassword) {
            actualText.takeLast(80)
        } else {
            ""
        }

        val snap = FocusedInputSnapshot(
            hasEditableFocus = true,
            isSensitivePassword = isPassword,
            packageName = pkg,
            inputType = inputType,
            selectedText = selected,
            surroundingBeforeCursor = beforeCursor
        )
        TypeFreeBubbleController.updateFocusedInput(snap)
        return snap
    }

    /**
     * Injects [polishedText] into the currently focused editable field without switching keyboards.
     * Returns `true` if directly inserted into the field, `false` if copied to clipboard as fallback.
     */
    fun insertPolishedText(polishedText: String): Boolean {
        if (polishedText.isEmpty()) return false
        val node = findFocusedEditableNode()
        if (node != null && isPasswordNode(node, node.inputType)) {
            return false
        }

        // Channel 1: Android 13+ (API 33+) AccessibilityInputConnection (exact cursor insertion!)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val aic = inputMethod?.currentInputConnection
                if (aic != null) {
                    aic.commitText(polishedText, 1, null)
                    refreshFocusedInputState()
                    return true
                }
            } catch (_: Exception) {
            }
        }

        // Channel 2: AccessibilityNodeInfo ACTION_SET_TEXT splicing at selection/cursor
        if (node != null) {
            try {
                val existing = readActualNodeText(node)
                val selStart = node.textSelectionStart
                val selEnd = node.textSelectionEnd
                val insertStart = if (selStart >= 0 && selEnd >= 0) {
                    minOf(selStart, selEnd).coerceIn(0, existing.length)
                } else {
                    existing.length
                }
                val insertEnd = if (selStart >= 0 && selEnd >= 0) {
                    maxOf(selStart, selEnd).coerceIn(insertStart, existing.length)
                } else {
                    existing.length
                }
                val merged = buildString {
                    append(existing.substring(0, insertStart))
                    append(polishedText)
                    append(existing.substring(insertEnd))
                }
                val setArgs = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        merged
                    )
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) {
                    val newCursor = (insertStart + polishedText.length).coerceAtMost(merged.length)
                    val selArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                    refreshFocusedInputState()
                    return true
                }
            } catch (_: Exception) {
            }
        }

        // Channel 3: Clipboard + ACTION_PASTE fallback
        copyToClipboard(polishedText)
        if (node != null) {
            try {
                if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    refreshFocusedInputState()
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun findFocusedEditableNode(allowMultiWindowScan: Boolean = true): AccessibilityNodeInfo? {
        try {
            val root = rootInActiveWindow
            if (root != null) {
                val inputFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (inputFocused != null && isEditableTarget(inputFocused)) {
                    return inputFocused
                }
            }
            if (!allowMultiWindowScan) {
                return null
            }
            val windowList = windows
            if (windowList != null) {
                for (win in windowList) {
                    val winRoot = win.root ?: continue
                    val focused = winRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null && isEditableTarget(focused)) {
                        return focused
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun isEditableTarget(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString().orEmpty()
        return cls.contains("EditText", ignoreCase = true) ||
            cls.contains("AutoCompleteTextView", ignoreCase = true)
    }

    private fun isPasswordNode(node: AccessibilityNodeInfo, inputType: Int): Boolean {
        if (node.isPassword) return true
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val cls = inputType and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_TEXT) {
            if (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            ) {
                return true
            }
        }
        if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return true
        }
        return false
    }

    private fun readActualNodeText(node: AccessibilityNodeInfo): String {
        if (node.isShowingHintText) return ""
        return node.text?.toString().orEmpty()
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("TypeFree", text))
        } catch (_: Exception) {
        }
    }

    /**
     * Wall 2 Hard Circuit-Breaker: Recursively scans the active window for ANY visible password
     * node (`isPasswordNode(node, node.inputType) == true`). If even a single visible password
     * node exists anywhere on screen, all context and screenshot capture is physically aborted.
     */
    private fun hasAnyVisiblePasswordNodeInTree(
        node: AccessibilityNodeInfo?,
        depth: Int = 0
    ): Boolean {
        if (node == null || depth > 28) return false
        if (!node.isVisibleToUser) return false
        if (isPasswordNode(node, node.inputType)) return true
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            }
            if (child != null && hasAnyVisiblePasswordNodeInTree(child, depth + 1)) {
                return true
            }
        }
        return false
    }

    /**
     * Strictly on-demand snapshot of visible chat/screen messages in the active window.
     * Never called from [onAccessibilityEvent]; only invoked when the user explicitly
     * taps `✍️` (`HELP_ME_WRITE`) or `✦ 军师` (`JEV_COPILOT`) and all privacy & package gates pass.
     */
    fun captureVisibleChatContextOnDemand(
        expectedPackage: String,
        appName: String,
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean,
        forJevCopilot: Boolean = false
    ): CapturedChatContext {
        return try {
            val root = rootInActiveWindow ?: return CapturedChatContext()
            val activePkg = root.packageName?.toString().orEmpty()
            // Wall 2: Full-screen password node circuit-breaker before reading any text
            val hasPasswordNode = hasAnyVisiblePasswordNodeInTree(root)
            val gateAllowed = if (forJevCopilot) {
                ChatScreenContextExtractor.shouldAllowJevContextCapture(
                    settings = settings,
                    isIncognitoMode = isIncognitoMode,
                    isPasswordInput = isPasswordInput,
                    ignoreChatContextForTurn = ignoreChatContextForTurn,
                    activeWindowPackage = activePkg,
                    targetInputPackage = expectedPackage,
                    hasVisiblePasswordNode = hasPasswordNode
                )
            } else {
                ChatScreenContextExtractor.shouldAllowChatContextCapture(
                    settings = settings,
                    isIncognitoMode = isIncognitoMode,
                    isPasswordInput = isPasswordInput,
                    ignoreChatContextForTurn = ignoreChatContextForTurn,
                    activeWindowPackage = activePkg,
                    targetInputPackage = expectedPackage,
                    hasVisiblePasswordNode = hasPasswordNode
                )
            }
            if (!gateAllowed) {
                return CapturedChatContext()
            }

            val rootBounds = Rect()
            root.getBoundsInScreen(rootBounds)
            val dm = resources.displayMetrics
            val screenWidth = rootBounds.width().takeIf { it > 0 } ?: dm.widthPixels
            val screenHeight = rootBounds.height().takeIf { it > 0 } ?: dm.heightPixels

            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            var inputBoxTop = -1
            if (focusedNode != null && isEditableTarget(focusedNode)) {
                val editRect = Rect()
                focusedNode.getBoundsInScreen(editRect)
                if (editRect.top > 0) {
                    inputBoxTop = editRect.top
                }
            }

            val collectedNodes = ArrayList<RawScreenTextNode>(64)
            collectVisibleTextNodes(root, collectedNodes, depth = 0)
            if (inputBoxTop <= 0) {
                val editableCandidate = collectedNodes.firstOrNull { it.isEditable && it.boundsTop > 0 }
                if (editableCandidate != null) {
                    inputBoxTop = editableCandidate.boundsTop
                }
            }
            recordInputBoxTopHint(expectedPackage, inputBoxTop)

            ChatScreenContextExtractor.extractChatContext(
                nodes = collectedNodes,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                inputBoxTop = inputBoxTop,
                packageName = expectedPackage,
                appName = appName,
                redactPii = true,
                context = this
            )
        } catch (_: Exception) {
            CapturedChatContext()
        }
    }

    /**
     * Android 11+ (`API 30+`) on-demand viewport capture + offline Chinese OCR + privacy guard:
     * 1. Bypasses WeChat `MMNeat7extView` / Mobile QQ NT custom `Canvas` text hiding via bundled
     *    on-device ML Kit Chinese OCR ([ViewportOcrExtractor]) even when `chatContextSendScreenshot == false`
     *    or when using pure-text models (whenever accessibility extracted `< 2` messages).
     * 2. Merges and deduplicates accessibility + OCR messages ([ChatScreenContextExtractor.mergeAccessibilityAndOcrMessages]),
     *    then runs [LocalPiiRedactor.redactMessages] across all combined nodes.
     * 3. Circuit-breaker: if any high-risk credential (`PASSWORD_SECRET`, `OTP_CODE`, `ID_CARD`, `BANK_CARD`, `API_KEY`)
     *    is detected by accessibility OR OCR, immediately drops the screenshot (`screenshotJpegBase64 = ""`)
     *    while preserving the redacted text messages.
     * 4. When `settings.chatContextSendScreenshot == true` and no high-risk credentials exist, paints opaque
     *    `#18181B` charcoal blackout rectangles over all reversible contact PII bounds (including OCR coordinates)
     *    before JPEG Base64 encoding. Strictly memory-only; `Bitmap` is recycled immediately.
     */
    suspend fun captureChatViewportOutcomeOnDemand(
        expectedPackage: String,
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean,
        forJevCopilot: Boolean = false,
        existingMessages: List<String> = emptyList(),
        redactionSession: RedactionSession? = null
    ): ViewportCaptureOutcome? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null
        val activePkg = root.packageName?.toString().orEmpty()
        val hasPasswordNode = hasAnyVisiblePasswordNodeInTree(root)
        val gateAllowed = if (forJevCopilot) {
            ChatScreenContextExtractor.shouldAllowJevContextCapture(
                settings = settings,
                isIncognitoMode = isIncognitoMode,
                isPasswordInput = isPasswordInput,
                ignoreChatContextForTurn = ignoreChatContextForTurn,
                activeWindowPackage = activePkg,
                targetInputPackage = expectedPackage,
                hasVisiblePasswordNode = hasPasswordNode
            )
        } else {
            ChatScreenContextExtractor.shouldAllowChatContextCapture(
                settings = settings,
                isIncognitoMode = isIncognitoMode,
                isPasswordInput = isPasswordInput,
                ignoreChatContextForTurn = ignoreChatContextForTurn,
                activeWindowPackage = activePkg,
                targetInputPackage = expectedPackage,
                hasVisiblePasswordNode = hasPasswordNode
            )
        }
        if (!gateAllowed ||
            hasPasswordNode ||
            isIncognitoMode ||
            settings.manualIncognitoMode ||
            ChatScreenContextExtractor.isSensitiveBlacklistedPackage(expectedPackage)
        ) {
            return null
        }

        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        val dm = resources.displayMetrics
        val screenWidth = rootBounds.width().takeIf { it > 0 } ?: dm.widthPixels
        val screenHeight = rootBounds.height().takeIf { it > 0 } ?: dm.heightPixels
        val inputTopHint = resolveInputBoxTopHintForPackage(expectedPackage)

        val a11yNodes = ArrayList<RawScreenTextNode>(64)
        collectVisibleTextNodes(root, a11yNodes, depth = 0)
        val unredactedA11yCtx = ChatScreenContextExtractor.extractChatContext(
            nodes = a11yNodes,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            inputBoxTop = inputTopHint,
            packageName = expectedPackage,
            redactPii = false
        )
        val baseA11yMessages = if (unredactedA11yCtx.formattedMessages.isNotEmpty()) {
            unredactedA11yCtx.formattedMessages
        } else {
            existingMessages
        }
        val needsOcrForCustomCanvas = ChatScreenContextExtractor.shouldTriggerOcrForCustomCanvas(
            ctx = unredactedA11yCtx,
            baseMessages = baseA11yMessages
        )

        if (!settings.chatContextSendScreenshot && !needsOcrForCustomCanvas) {
            return null
        }
        if (redactionSession?.hasHighRiskCredentials == true && !needsOcrForCustomCanvas) {
            return null
        }

        val rawBitmap = withTimeoutOrNull(950L) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val hwBuffer = screenshot.hardwareBuffer
                                val bitmap = try {
                                    val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                    wrapped?.copy(Bitmap.Config.ARGB_8888, true).also {
                                        wrapped?.recycle()
                                    }
                                } catch (_: Throwable) {
                                    null
                                } finally {
                                    try {
                                        hwBuffer.close()
                                    } catch (_: Throwable) {
                                    }
                                }
                                if (cont.isActive) cont.resume(bitmap) else bitmap?.recycle()
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } ?: return null

        return withContext(Dispatchers.Default) {
            var scaledBitmap: Bitmap? = null
            try {
                val cropRect = ChatScreenContextExtractor.computeChatRegionCropRect(
                    bitmapWidth = rawBitmap.width,
                    bitmapHeight = rawBitmap.height,
                    inputBoxTop = inputTopHint
                )
                val cropX = cropRect[0]
                val cropY = cropRect[1]
                val cropW = cropRect[2]
                val cropH = cropRect[3]
                if (cropW <= 0 || cropH <= 0) return@withContext null

                val cropped = Bitmap.createBitmap(rawBitmap, cropX, cropY, cropW, cropH)
                val maxTargetWidth = 720
                val scaleRatio: Float
                scaledBitmap = if (cropped.width > maxTargetWidth) {
                    scaleRatio = maxTargetWidth.toFloat() / cropped.width.toFloat()
                    val targetH = (cropped.height * scaleRatio).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(cropped, maxTargetWidth, targetH, true).also {
                        if (it !== cropped) cropped.recycle()
                    }
                } else {
                    scaleRatio = 1.0f
                    cropped
                }

                val ocrNodes = ViewportOcrExtractor.recognizeViewportNodes(
                    bitmap = scaledBitmap,
                    cropX = cropX,
                    cropY = cropY,
                    scaleRatio = scaleRatio,
                    timeoutMs = 750L
                )
                val fusedExtracted = if (ocrNodes.isNotEmpty()) {
                    val merged2DNodes = ChatScreenContextExtractor.mergeCoordinateNodes(a11yNodes, ocrNodes)
                    ChatScreenContextExtractor.extractChatContext(
                        nodes = merged2DNodes,
                        screenWidth = rawBitmap.width,
                        screenHeight = rawBitmap.height,
                        inputBoxTop = inputTopHint,
                        packageName = expectedPackage,
                        maxMessages = 10,
                        maxTotalChars = 800,
                        redactPii = false
                    )
                } else {
                    unredactedA11yCtx
                }

                val rawMergedMessages = if (a11yNodes.isNotEmpty() && ocrNodes.isNotEmpty()) {
                    fusedExtracted.formattedMessages
                } else {
                    ChatScreenContextExtractor.mergeAccessibilityAndOcrMessages(
                        a11yMessages = baseA11yMessages,
                        ocrMessages = if (ocrNodes.isNotEmpty()) fusedExtracted.formattedMessages else emptyList(),
                        maxMessages = 10
                    )
                }
                val combinedNodes = if (ocrNodes.isNotEmpty()) a11yNodes + ocrNodes else a11yNodes
                val (redactedMergedMessages, mergedRedactionSession) = LocalPiiRedactor.redactMessages(
                    messages = rawMergedMessages,
                    context = this@TypeFreeAccessibilityService,
                    sourceNodes = combinedNodes
                )
                val resolvedTopology = ChatScreenContextExtractor.inferTopologyFromFormattedMessages(
                    messages = redactedMergedMessages,
                    existingTopology = fusedExtracted.topology
                )

                val shouldDrop = ImagePrivacyGuard.shouldDropScreenshot(
                    chatContextSendScreenshot = settings.chatContextSendScreenshot,
                    hasHighRiskCredentials = mergedRedactionSession.hasHighRiskCredentials,
                    hasPasswordNode = hasPasswordNode,
                    isIncognito = isIncognitoMode || settings.manualIncognitoMode,
                    isBlacklistedPackage = ChatScreenContextExtractor.isSensitiveBlacklistedPackage(expectedPackage)
                )
                if (shouldDrop) {
                    return@withContext ViewportCaptureOutcome(
                        screenshotJpegBase64 = "",
                        width = 0,
                        height = 0,
                        mergedMessages = redactedMergedMessages,
                        redactionSession = mergedRedactionSession,
                        topology = resolvedTopology
                    )
                }

                val maskedRects = ImagePrivacyGuard.computeMaskedRectsOnCroppedBitmap(
                    rawScreenRects = mergedRedactionSession.redactedNodeBounds,
                    cropTopPx = cropY,
                    cropHeightPx = cropH,
                    sourceWidthPx = rawBitmap.width,
                    targetWidthPx = scaledBitmap.width,
                    targetHeightPx = scaledBitmap.height,
                    expandPaddingPx = ImagePrivacyGuard.DEFAULT_SAFETY_PADDING_PX
                )
                val finalBitmap = ImagePrivacyGuard.applyCharcoalRedactionOnBitmap(
                    source = scaledBitmap,
                    maskedBitmapRects = maskedRects
                )
                if (finalBitmap !== scaledBitmap) {
                    scaledBitmap = finalBitmap
                }

                val outWidth = finalBitmap.width
                val outHeight = finalBitmap.height
                val baos = ByteArrayOutputStream(36 * 1024)
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 74, baos)

                val bytes = baos.toByteArray()
                val b64 = if (bytes.isNotEmpty()) Base64.getEncoder().encodeToString(bytes) else ""
                ViewportCaptureOutcome(
                    screenshotJpegBase64 = b64,
                    width = if (b64.isNotBlank()) outWidth else 0,
                    height = if (b64.isNotBlank()) outHeight else 0,
                    mergedMessages = redactedMergedMessages,
                    redactionSession = mergedRedactionSession,
                    topology = resolvedTopology
                )
            } catch (_: Throwable) {
                null
            } finally {
                try {
                    if (scaledBitmap != null && !scaledBitmap.isRecycled) {
                        scaledBitmap.recycle()
                    }
                } catch (_: Throwable) {
                }
                try {
                    if (!rawBitmap.isRecycled) rawBitmap.recycle()
                } catch (_: Throwable) {
                }
            }
        }
    }

    suspend fun captureChatViewportScreenshotBase64OnDemand(
        expectedPackage: String,
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean,
        forJevCopilot: Boolean = false,
        redactionSession: RedactionSession? = null
    ): Triple<String, Int, Int>? {
        val outcome = captureChatViewportOutcomeOnDemand(
            expectedPackage = expectedPackage,
            settings = settings,
            isIncognitoMode = isIncognitoMode,
            isPasswordInput = isPasswordInput,
            ignoreChatContextForTurn = ignoreChatContextForTurn,
            forJevCopilot = forJevCopilot,
            redactionSession = redactionSession
        ) ?: return null
        if (outcome.screenshotJpegBase64.isBlank()) return null
        return Triple(outcome.screenshotJpegBase64, outcome.width, outcome.height)
    }

    private fun collectVisibleTextNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<RawScreenTextNode>,
        depth: Int
    ) {
        if (node == null || depth > 28 || out.size >= 260) return
        if (!node.isVisibleToUser) return
        if (isPasswordNode(node, node.inputType)) return

        val editable = isEditableTarget(node)
        val clsName = node.className?.toString().orEmpty()
        val primaryText = if (!node.isShowingHintText) {
            node.text?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        val descText = if (!node.isShowingHintText && primaryText.isEmpty()) {
            node.contentDescription?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        val rawText = primaryText.ifEmpty { descText }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val width = rect.width()
        val height = rect.height()
        val screenW = runCatching { resources.displayMetrics.widthPixels }.getOrDefault(1080)
        val isDescOnlyImage = primaryText.isEmpty() &&
            descText.isNotEmpty() &&
            clsName.contains("Image", ignoreCase = true)
        val isSquareEdgeAvatar = width in 24..180 &&
            height in 24..180 &&
            kotlin.math.abs(width - height) <= 24 &&
            (rect.left <= (screenW * 0.22).toInt().coerceAtLeast(180) || rect.right >= (screenW * 0.78).toInt())
        val skipAsAvatarOrNoiseImage = isDescOnlyImage &&
            (ChatScreenContextExtractor.isNoiseText(descText) || isSquareEdgeAvatar)

        if ((rawText.isNotEmpty() || editable) && width > 0 && height > 0 && !skipAsAvatarOrNoiseImage) {
            out.add(
                RawScreenTextNode(
                    text = rawText,
                    boundsTop = rect.top,
                    boundsBottom = rect.bottom,
                    boundsLeft = rect.left,
                    boundsRight = rect.right,
                    isEditable = editable,
                    isClickable = node.isClickable,
                    viewIdResourceName = node.viewIdResourceName.orEmpty(),
                    className = clsName,
                    isPassword = false,
                    isVisibleToUser = true
                )
            )
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            }
            if (child != null) {
                collectVisibleTextNodes(child, out, depth + 1)
            }
        }
    }

    companion object {
        const val CONTENT_CHANGED_THROTTLE_MS = 180L

        @Volatile
        var activeInstance: TypeFreeAccessibilityService? = null
            private set

        @Volatile
        private var lastKnownInputBoxTopPx: Int = -1

        @Volatile
        private var lastKnownInputBoxPackage: String = ""

        fun recordInputBoxTopHint(packageName: String, inputBoxTopPx: Int) {
            if (packageName.isBlank() || inputBoxTopPx <= 0) {
                if (packageName.isNotBlank() && packageName == lastKnownInputBoxPackage) {
                    lastKnownInputBoxTopPx = -1
                    lastKnownInputBoxPackage = ""
                }
                return
            }
            lastKnownInputBoxPackage = packageName
            lastKnownInputBoxTopPx = inputBoxTopPx
        }

        fun resolveInputBoxTopHintForPackage(expectedPackage: String): Int {
            if (expectedPackage.isBlank()) return -1
            return if (expectedPackage == lastKnownInputBoxPackage && lastKnownInputBoxTopPx > 0) {
                lastKnownInputBoxTopPx
            } else {
                -1
            }
        }

        fun clearInputBoxTopHint() {
            lastKnownInputBoxTopPx = -1
            lastKnownInputBoxPackage = ""
        }

        /**
         * Determines `(shouldProcess, allowMultiWindowScan)` for incoming accessibility events.
         * - `TYPE_WINDOW_CONTENT_CHANGED` is ignored completely when the floating bubble is disabled,
         *   throttled to `>= 180ms`, and strictly forbidden from invoking `getWindows()` multi-window IPC.
         */
        fun shouldProcessAccessibilityEvent(
            eventType: Int,
            bubbleEnabled: Boolean,
            nowMs: Long,
            lastContentChangedMs: Long
        ): Pair<Boolean, Boolean> {
            return when (eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (!bubbleEnabled) {
                        false to false
                    } else if (nowMs - lastContentChangedMs < CONTENT_CHANGED_THROTTLE_MS) {
                        false to false
                    } else {
                        true to false
                    }
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> true to true
                else -> false to false
            }
        }

        @Volatile
        var debugSimulatedNodesForTest: Pair<String, List<RawScreenTextNode>>? = null

        @Volatile
        var debugSimulatedScreenshotBase64ForTest: String? = null

        fun captureChatContextIfAvailable(
            expectedPackage: String,
            appName: String,
            settings: AppSettings,
            isIncognitoMode: Boolean,
            isPasswordInput: Boolean,
            ignoreChatContextForTurn: Boolean,
            forJevCopilot: Boolean = false
        ): CapturedChatContext {
            val sim = debugSimulatedNodesForTest
            if (sim != null) {
                val (simWindowPkg, simNodes) = sim
                val hasPasswordNode = ChatScreenContextExtractor.hasAnyVisiblePasswordNode(simNodes)
                val gateAllowed = if (forJevCopilot) {
                    ChatScreenContextExtractor.shouldAllowJevContextCapture(
                        settings = settings,
                        isIncognitoMode = isIncognitoMode,
                        isPasswordInput = isPasswordInput,
                        ignoreChatContextForTurn = ignoreChatContextForTurn,
                        activeWindowPackage = simWindowPkg,
                        targetInputPackage = expectedPackage,
                        hasVisiblePasswordNode = hasPasswordNode
                    )
                } else {
                    ChatScreenContextExtractor.shouldAllowChatContextCapture(
                        settings = settings,
                        isIncognitoMode = isIncognitoMode,
                        isPasswordInput = isPasswordInput,
                        ignoreChatContextForTurn = ignoreChatContextForTurn,
                        activeWindowPackage = simWindowPkg,
                        targetInputPackage = expectedPackage,
                        hasVisiblePasswordNode = hasPasswordNode
                    )
                }
                if (!gateAllowed) {
                    return CapturedChatContext()
                }
                val editableTop = simNodes.firstOrNull { it.isEditable }?.boundsTop ?: -1
                val baseCtx = ChatScreenContextExtractor.extractChatContext(
                    nodes = simNodes,
                    screenWidth = 1080,
                    screenHeight = 2400,
                    inputBoxTop = editableTop,
                    packageName = expectedPackage,
                    appName = appName,
                    redactPii = true
                )
                val simShot = debugSimulatedScreenshotBase64ForTest.orEmpty()
                val dropShot = ImagePrivacyGuard.shouldDropScreenshot(
                    chatContextSendScreenshot = settings.chatContextSendScreenshot,
                    hasHighRiskCredentials = baseCtx.hasHighRiskRedaction,
                    hasPasswordNode = hasPasswordNode,
                    isIncognito = isIncognitoMode || settings.manualIncognitoMode,
                    isBlacklistedPackage = ChatScreenContextExtractor.isSensitiveBlacklistedPackage(expectedPackage)
                )
                return if (simShot.isNotBlank() && !dropShot) {
                    baseCtx.copy(
                        screenshotJpegBase64 = simShot,
                        screenshotWidth = 720,
                        screenshotHeight = 960,
                        latestMessagePreview = baseCtx.latestMessagePreview.ifBlank { "📸 已截屏捕获聊天画面与图片" }
                    )
                } else {
                    baseCtx
                }
            }
            val instance = activeInstance ?: return CapturedChatContext()
            return instance.captureVisibleChatContextOnDemand(
                expectedPackage = expectedPackage,
                appName = appName,
                settings = settings,
                isIncognitoMode = isIncognitoMode,
                isPasswordInput = isPasswordInput,
                ignoreChatContextForTurn = ignoreChatContextForTurn,
                forJevCopilot = forJevCopilot
            )
        }

        suspend fun captureChatViewportOutcomeIfAvailable(
            expectedPackage: String,
            settings: AppSettings,
            isIncognitoMode: Boolean,
            isPasswordInput: Boolean,
            ignoreChatContextForTurn: Boolean,
            forJevCopilot: Boolean = false,
            existingMessages: List<String> = emptyList(),
            redactionSession: RedactionSession? = null
        ): ViewportCaptureOutcome? {
            val simShot = debugSimulatedScreenshotBase64ForTest
            val simOcrNodes = ViewportOcrExtractor.debugSimulatedOcrNodesForTest
            if (!simShot.isNullOrBlank() || !simOcrNodes.isNullOrEmpty()) {
                val simPkg = debugSimulatedNodesForTest?.first ?: expectedPackage
                val simNodes = debugSimulatedNodesForTest?.second.orEmpty()
                val hasPasswordNode = ChatScreenContextExtractor.hasAnyVisiblePasswordNode(simNodes)
                val gateAllowed = if (forJevCopilot) {
                    ChatScreenContextExtractor.shouldAllowJevContextCapture(
                        settings = settings,
                        isIncognitoMode = isIncognitoMode,
                        isPasswordInput = isPasswordInput,
                        ignoreChatContextForTurn = ignoreChatContextForTurn,
                        activeWindowPackage = simPkg,
                        targetInputPackage = expectedPackage,
                        hasVisiblePasswordNode = hasPasswordNode
                    )
                } else {
                    ChatScreenContextExtractor.shouldAllowChatContextCapture(
                        settings = settings,
                        isIncognitoMode = isIncognitoMode,
                        isPasswordInput = isPasswordInput,
                        ignoreChatContextForTurn = ignoreChatContextForTurn,
                        activeWindowPackage = simPkg,
                        targetInputPackage = expectedPackage,
                        hasVisiblePasswordNode = hasPasswordNode
                    )
                }
                if (!gateAllowed) {
                    return null
                }
                val editableTop = simNodes.firstOrNull { it.isEditable }?.boundsTop ?: -1
                val unredactedA11yCtx = if (simNodes.isNotEmpty()) {
                    ChatScreenContextExtractor.extractChatContext(
                        nodes = simNodes,
                        screenWidth = 1080,
                        screenHeight = 2400,
                        inputBoxTop = editableTop,
                        packageName = expectedPackage,
                        redactPii = false
                    )
                } else {
                    CapturedChatContext.EMPTY
                }
                val unredactedA11y = if (unredactedA11yCtx.formattedMessages.isNotEmpty()) {
                    unredactedA11yCtx.formattedMessages
                } else {
                    existingMessages
                }
                val fusedExtracted = if (!simOcrNodes.isNullOrEmpty()) {
                    val merged2DNodes = ChatScreenContextExtractor.mergeCoordinateNodes(simNodes, simOcrNodes)
                    ChatScreenContextExtractor.extractChatContext(
                        nodes = merged2DNodes,
                        screenWidth = 1080,
                        screenHeight = 2400,
                        inputBoxTop = editableTop,
                        packageName = expectedPackage,
                        maxMessages = 10,
                        maxTotalChars = 800,
                        redactPii = false
                    )
                } else {
                    unredactedA11yCtx
                }
                val rawMerged = if (simNodes.isNotEmpty() && !simOcrNodes.isNullOrEmpty()) {
                    fusedExtracted.formattedMessages
                } else {
                    ChatScreenContextExtractor.mergeAccessibilityAndOcrMessages(
                        a11yMessages = unredactedA11y,
                        ocrMessages = if (!simOcrNodes.isNullOrEmpty()) fusedExtracted.formattedMessages else emptyList(),
                        maxMessages = 10
                    )
                }
                val combinedNodes = simNodes + simOcrNodes.orEmpty()
                val (redactedMerged, mergedSession) = LocalPiiRedactor.redactMessages(
                    messages = rawMerged,
                    context = null,
                    sourceNodes = combinedNodes
                )
                val resolvedTopology = ChatScreenContextExtractor.inferTopologyFromFormattedMessages(
                    messages = redactedMerged,
                    existingTopology = fusedExtracted.topology
                )
                val effectiveHighRisk = mergedSession.hasHighRiskCredentials ||
                    redactionSession?.hasHighRiskCredentials == true
                val dropShot = ImagePrivacyGuard.shouldDropScreenshot(
                    chatContextSendScreenshot = settings.chatContextSendScreenshot,
                    hasHighRiskCredentials = effectiveHighRisk,
                    hasPasswordNode = hasPasswordNode,
                    isIncognito = isIncognitoMode || settings.manualIncognitoMode,
                    isBlacklistedPackage = ChatScreenContextExtractor.isSensitiveBlacklistedPackage(expectedPackage)
                )
                val finalShot = if (!dropShot && !simShot.isNullOrBlank()) simShot else ""
                return ViewportCaptureOutcome(
                    screenshotJpegBase64 = finalShot,
                    width = if (finalShot.isNotBlank()) 720 else 0,
                    height = if (finalShot.isNotBlank()) 960 else 0,
                    mergedMessages = redactedMerged,
                    redactionSession = mergedSession.copy(hasHighRiskCredentials = effectiveHighRisk),
                    topology = resolvedTopology
                )
            }
            val instance = activeInstance ?: return null
            return instance.captureChatViewportOutcomeOnDemand(
                expectedPackage = expectedPackage,
                settings = settings,
                isIncognitoMode = isIncognitoMode,
                isPasswordInput = isPasswordInput,
                ignoreChatContextForTurn = ignoreChatContextForTurn,
                forJevCopilot = forJevCopilot,
                existingMessages = existingMessages,
                redactionSession = redactionSession
            )
        }

        suspend fun captureChatScreenshotIfAvailable(
            expectedPackage: String,
            settings: AppSettings,
            isIncognitoMode: Boolean,
            isPasswordInput: Boolean,
            ignoreChatContextForTurn: Boolean,
            forJevCopilot: Boolean = false,
            redactionSession: RedactionSession? = null
        ): Triple<String, Int, Int>? {
            val outcome = captureChatViewportOutcomeIfAvailable(
                expectedPackage = expectedPackage,
                settings = settings,
                isIncognitoMode = isIncognitoMode,
                isPasswordInput = isPasswordInput,
                ignoreChatContextForTurn = ignoreChatContextForTurn,
                forJevCopilot = forJevCopilot,
                redactionSession = redactionSession
            ) ?: return null
            if (outcome.screenshotJpegBase64.isBlank()) return null
            return Triple(outcome.screenshotJpegBase64, outcome.width, outcome.height)
        }
    }
}
