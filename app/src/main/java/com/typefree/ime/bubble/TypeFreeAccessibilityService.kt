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
import android.text.InputType
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.typefree.ime.data.AppSettings
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                refreshFocusedInputState()
            }
            else -> Unit
        }
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

    fun refreshFocusedInputState(): FocusedInputSnapshot {
        val focusedNode = findFocusedEditableNode()
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

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        try {
            val root = rootInActiveWindow
            if (root != null) {
                val inputFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (inputFocused != null && isEditableTarget(inputFocused)) {
                    return inputFocused
                }
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
     * Strictly on-demand snapshot of visible chat messages in the active window.
     * Never called from [onAccessibilityEvent]; only invoked when the user explicitly
     * taps `✍️` (`HELP_ME_WRITE`) and all privacy & package gates pass.
     */
    fun captureVisibleChatContextOnDemand(
        expectedPackage: String,
        appName: String,
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean
    ): CapturedChatContext {
        return try {
            val root = rootInActiveWindow ?: return CapturedChatContext()
            val activePkg = root.packageName?.toString().orEmpty()
            if (!ChatScreenContextExtractor.shouldAllowChatContextCapture(
                    settings = settings,
                    isIncognitoMode = isIncognitoMode,
                    isPasswordInput = isPasswordInput,
                    ignoreChatContextForTurn = ignoreChatContextForTurn,
                    activeWindowPackage = activePkg,
                    targetInputPackage = expectedPackage
                )
            ) {
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
            lastKnownInputBoxTopPx = inputBoxTop

            ChatScreenContextExtractor.extractChatContext(
                nodes = collectedNodes,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                inputBoxTop = inputBoxTop,
                packageName = expectedPackage,
                appName = appName
            )
        } catch (_: Exception) {
            CapturedChatContext()
        }
    }

    /**
     * Android 11+ (`API 30+`) on-demand screenshot capture of the chat region above the keyboard.
     * Bypasses WeChat `MMNeat7extView` / QQ NT custom `Canvas` text hiding and captures inline chat images.
     * Strictly memory-only; never written to disk.
     */
    suspend fun captureChatViewportScreenshotBase64OnDemand(
        expectedPackage: String,
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean
    ): Triple<String, Int, Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null
        val activePkg = root.packageName?.toString().orEmpty()
        if (!ChatScreenContextExtractor.shouldAllowChatContextCapture(
                settings = settings,
                isIncognitoMode = isIncognitoMode,
                isPasswordInput = isPasswordInput,
                ignoreChatContextForTurn = ignoreChatContextForTurn,
                activeWindowPackage = activePkg,
                targetInputPackage = expectedPackage
            )
        ) {
            return null
        }

        val inputTopHint = lastKnownInputBoxTopPx
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
                                    wrapped?.copy(Bitmap.Config.ARGB_8888, false).also {
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
                val finalBitmap = if (cropped.width > maxTargetWidth) {
                    val ratio = maxTargetWidth.toFloat() / cropped.width.toFloat()
                    val targetH = (cropped.height * ratio).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(cropped, maxTargetWidth, targetH, true).also {
                        if (it !== cropped) cropped.recycle()
                    }
                } else {
                    cropped
                }

                val outWidth = finalBitmap.width
                val outHeight = finalBitmap.height
                val baos = ByteArrayOutputStream(36 * 1024)
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 74, baos)
                finalBitmap.recycle()

                val bytes = baos.toByteArray()
                if (bytes.isEmpty()) return@withContext null
                val b64 = Base64.getEncoder().encodeToString(bytes)
                Triple(b64, outWidth, outHeight)
            } catch (_: Throwable) {
                null
            } finally {
                try {
                    if (!rawBitmap.isRecycled) rawBitmap.recycle()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun collectVisibleTextNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<RawScreenTextNode>,
        depth: Int
    ) {
        if (node == null || depth > 28 || out.size >= 160) return
        if (!node.isVisibleToUser) return
        if (isPasswordNode(node, node.inputType)) return

        val editable = isEditableTarget(node)
        val rawText = if (!node.isShowingHintText) {
            val primary = node.text?.toString()?.trim().orEmpty()
            if (primary.isNotEmpty()) {
                primary
            } else {
                node.contentDescription?.toString()?.trim().orEmpty()
            }
        } else {
            ""
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if ((rawText.isNotEmpty() || editable) && rect.width() > 0 && rect.height() > 0) {
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
                    className = node.className?.toString().orEmpty()
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
        @Volatile
        var activeInstance: TypeFreeAccessibilityService? = null
            private set

        @Volatile
        private var lastKnownInputBoxTopPx: Int = -1

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
            ignoreChatContextForTurn: Boolean
        ): CapturedChatContext {
            val sim = debugSimulatedNodesForTest
            if (sim != null) {
                val (simWindowPkg, simNodes) = sim
                if (!ChatScreenContextExtractor.shouldAllowChatContextCapture(
                        settings = settings,
                        isIncognitoMode = isIncognitoMode,
                        isPasswordInput = isPasswordInput,
                        ignoreChatContextForTurn = ignoreChatContextForTurn,
                        activeWindowPackage = simWindowPkg,
                        targetInputPackage = expectedPackage
                    )
                ) {
                    return CapturedChatContext()
                }
                val editableTop = simNodes.firstOrNull { it.isEditable }?.boundsTop ?: -1
                val baseCtx = ChatScreenContextExtractor.extractChatContext(
                    nodes = simNodes,
                    screenWidth = 1080,
                    screenHeight = 2400,
                    inputBoxTop = editableTop,
                    packageName = expectedPackage,
                    appName = appName
                )
                val simShot = debugSimulatedScreenshotBase64ForTest.orEmpty()
                return if (simShot.isNotBlank()) {
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
                ignoreChatContextForTurn = ignoreChatContextForTurn
            )
        }

        suspend fun captureChatScreenshotIfAvailable(
            expectedPackage: String,
            settings: AppSettings,
            isIncognitoMode: Boolean,
            isPasswordInput: Boolean,
            ignoreChatContextForTurn: Boolean
        ): Triple<String, Int, Int>? {
            val simShot = debugSimulatedScreenshotBase64ForTest
            if (!simShot.isNullOrBlank()) {
                val simPkg = debugSimulatedNodesForTest?.first ?: expectedPackage
                if (!ChatScreenContextExtractor.shouldAllowChatContextCapture(
                        settings = settings,
                        isIncognitoMode = isIncognitoMode,
                        isPasswordInput = isPasswordInput,
                        ignoreChatContextForTurn = ignoreChatContextForTurn,
                        activeWindowPackage = simPkg,
                        targetInputPackage = expectedPackage
                    )
                ) {
                    return null
                }
                return Triple(simShot, 720, 960)
            }
            val instance = activeInstance ?: return null
            return instance.captureChatViewportScreenshotBase64OnDemand(
                expectedPackage = expectedPackage,
                settings = settings,
                isIncognitoMode = isIncognitoMode,
                isPasswordInput = isPasswordInput,
                ignoreChatContextForTurn = ignoreChatContextForTurn
            )
        }
    }
}
