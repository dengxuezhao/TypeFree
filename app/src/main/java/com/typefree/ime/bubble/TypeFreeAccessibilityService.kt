package com.typefree.ime.bubble

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Mirrors official `com.typefree.ime.floating.Services.FloatingAccessibilityService`:
 * 1. Tracks whether an editable text field currently holds input focus across apps.
 * 2. Detects password/sensitive fields and hides the bubble automatically.
 * 3. Extracts selected text & surrounding context for App-Aware Tone and Speak-to-Edit.
 * 4. Injects AI-polished text directly at the cursor via Android 13+ (`API 33+`)
 *    `AccessibilityInputConnection` (`FLAG_INPUT_METHOD_EDITOR`), falling back cleanly
 *    to `ACTION_SET_TEXT` selection splicing or `ACTION_PASTE`.
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

    companion object {
        @Volatile
        var activeInstance: TypeFreeAccessibilityService? = null
            private set
    }
}
