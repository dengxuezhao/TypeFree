package com.typefree.ime.bubble

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.diagnostics.ImeDiagnosticLogger

/**
 * Floating Voice Bubble accessibility helper:
 * 1. Tracks whether an editable text field currently holds input focus across apps.
 * 2. Detects password/sensitive fields and hides the floating bubble automatically.
 * 3. Extracts focused field selection & surrounding context for App-Aware Tone and Speak-to-Edit.
 * 4. Injects AI-polished text directly at the cursor via Android 13+ (`API 33+`)
 *    `AccessibilityInputConnection` (`FLAG_INPUT_METHOD_EDITOR`), falling back cleanly
 *    to `ACTION_SET_TEXT` selection splicing or `ACTION_PASTE`.
 */
class TypeFreeAccessibilityService : AccessibilityService() {

    @Volatile
    private var lastSyncedBubbleImeEnabled: Boolean? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        lastSyncedBubbleImeEnabled = null
        val initialBubbleEnabled = runCatching {
            TypeFreeApp.instance.repository.settings.value.bubbleEnabled
        }.getOrDefault(false)
        syncServiceFlagsForBubbleState(initialBubbleEnabled)
        TypeFreeBubbleController.setAccessibilityConnected(true)
        refreshFocusedInputState()
        TypeFreeBubbleController.syncBubbleService(this)
    }

    /**
     * Dynamically toggles [AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR] on Android 13+ (`TIRAMISU`)
     * so `AccessibilityInputMethod` is bound ONLY when Floating Voice Bubble (`bubbleEnabled == true`)
     * needs direct keyboard-less cursor injection.
     */
    fun syncServiceFlagsForBubbleState(bubbleEnabled: Boolean) {
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            val prevFlags = info.flags
            val updatedFlags = computeUpdatedServiceFlags(
                existingFlags = prevFlags,
                sdkInt = Build.VERSION.SDK_INT,
                bubbleEnabled = bubbleEnabled
            )
            if (lastSyncedBubbleImeEnabled != bubbleEnabled || updatedFlags != prevFlags) {
                info.flags = updatedFlags
                serviceInfo = info
                lastSyncedBubbleImeEnabled = bubbleEnabled
                val imeFlagActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    (updatedFlags and AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR) != 0
                ImeDiagnosticLogger.record(
                    category = "A11Y_FLAGS",
                    event = "sync_service_flags",
                    detail = "bubble=$bubbleEnabled a11yImeFlag=$imeFlagActive changed=${updatedFlags != prevFlags}"
                )
            }
        } catch (t: Exception) {
            ImeDiagnosticLogger.record(
                category = "A11Y_FLAGS",
                event = "sync_service_flags_error",
                detail = "bubble=$bubbleEnabled err=${ImeDiagnosticLogger.sanitizeThrowable(t)}"
            )
        }
    }

    @Volatile
    private var lastContentChangedHandledAtMs: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val bubbleEnabled = runCatching {
            TypeFreeApp.instance.repository.settings.value.bubbleEnabled
        }.getOrDefault(false)
        if (lastSyncedBubbleImeEnabled != bubbleEnabled) {
            syncServiceFlagsForBubbleState(bubbleEnabled)
        }
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
        lastSyncedBubbleImeEnabled = null
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

    companion object {
        const val CONTENT_CHANGED_THROTTLE_MS = 180L

        fun computeUpdatedServiceFlags(
            existingFlags: Int,
            sdkInt: Int,
            bubbleEnabled: Boolean
        ): Int {
            var updated = existingFlags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                updated = if (bubbleEnabled) {
                    updated or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
                } else {
                    updated and AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR.inv()
                }
            }
            return updated
        }

        @Volatile
        var activeInstance: TypeFreeAccessibilityService? = null
            private set

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
    }
}
