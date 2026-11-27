package com.typefree.ime.bubble

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.text.InputType
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.diagnostics.ImeDiagnosticLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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

    private val scanLock = Any()
    private var scanThread: HandlerThread? = null
    private var scanHandler: Handler? = null
    private var pendingAllowMultiWindowScan: Boolean = false
    private var pendingDebounceFirstQueuedAtMs: Long = 0L
    private val pendingScanRunnable = Runnable {
        val allowMulti = synchronized(scanLock) {
            val flag = pendingAllowMultiWindowScan
            pendingAllowMultiWindowScan = false
            pendingDebounceFirstQueuedAtMs = 0L
            flag
        }
        refreshFocusedInputState(allowMultiWindowScan = allowMulti)
    }

    private fun ensureScanHandler(): Handler? {
        synchronized(scanLock) {
            scanHandler?.let { return it }
            return runCatching {
                val thread = HandlerThread("TypeFreeA11yScan", Process.THREAD_PRIORITY_BACKGROUND)
                thread.start()
                val handler = Handler(thread.looper)
                scanThread = thread
                scanHandler = handler
                handler
            }.getOrNull()
        }
    }

    fun scheduleFocusedInputRefresh(
        allowMultiWindowScan: Boolean = true,
        delayMs: Long = FAST_EVENT_DEBOUNCE_DELAY_MS
    ) {
        val handler = ensureScanHandler() ?: return
        val clampedDelay = delayMs.coerceAtLeast(0L)
        synchronized(scanLock) {
            pendingAllowMultiWindowScan = pendingAllowMultiWindowScan || allowMultiWindowScan
            val nowMs = SystemClock.uptimeMillis()
            if (clampedDelay == 0L) {
                pendingDebounceFirstQueuedAtMs = 0L
                handler.removeCallbacks(pendingScanRunnable)
                handler.post(pendingScanRunnable)
                return
            }
            if (pendingDebounceFirstQueuedAtMs == 0L) {
                pendingDebounceFirstQueuedAtMs = nowMs
            }
            val elapsedSinceFirst = (nowMs - pendingDebounceFirstQueuedAtMs).coerceAtLeast(0L)
            val effectiveDelay = if (elapsedSinceFirst >= MAX_DEBOUNCE_WAIT_MS) {
                0L
            } else {
                minOf(clampedDelay, MAX_DEBOUNCE_WAIT_MS - elapsedSinceFirst)
            }
            handler.removeCallbacks(pendingScanRunnable)
            if (effectiveDelay == 0L) {
                pendingDebounceFirstQueuedAtMs = 0L
                handler.post(pendingScanRunnable)
            } else {
                handler.postDelayed(pendingScanRunnable, effectiveDelay)
            }
        }
    }

    suspend fun refreshFocusedInputStateAsync(
        allowMultiWindowScan: Boolean = true
    ): FocusedInputSnapshot {
        val handler = ensureScanHandler() ?: return TypeFreeBubbleController.focusedInput.value
        return withTimeoutOrNull(1_200L) {
            suspendCancellableCoroutine { cont ->
                val task = Runnable {
                    val snap = runCatching {
                        refreshFocusedInputState(allowMultiWindowScan = allowMultiWindowScan)
                    }.getOrElse { TypeFreeBubbleController.focusedInput.value }
                    if (cont.isActive) cont.resume(snap)
                }
                handler.post(task)
                cont.invokeOnCancellation { handler.removeCallbacks(task) }
            }
        } ?: TypeFreeBubbleController.focusedInput.value
    }

    suspend fun insertPolishedTextAsync(
        polishedText: String,
        expectedFingerprint: InputTargetFingerprint? = null,
        expectedTargetKind: BubbleTargetKind? = null
    ): BubbleInsertOutcome {
        if (polishedText.isEmpty()) return BubbleInsertOutcome.COPIED_FALLBACK
        val handler = ensureScanHandler() ?: return BubbleInsertOutcome.COPIED_FALLBACK
        return withTimeoutOrNull(1_500L) {
            suspendCancellableCoroutine { cont ->
                val task = Runnable {
                    val outcome = runCatching {
                        insertPolishedTextWithOutcome(
                            polishedText = polishedText,
                            expectedFingerprint = expectedFingerprint,
                            expectedTargetKind = expectedTargetKind
                        )
                    }.getOrDefault(BubbleInsertOutcome.COPIED_FALLBACK)
                    if (cont.isActive) cont.resume(outcome)
                }
                handler.post(task)
                cont.invokeOnCancellation { handler.removeCallbacks(task) }
            }
        } ?: BubbleInsertOutcome.COPIED_FALLBACK
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        lastSyncedBubbleImeEnabled = null
        ensureScanHandler()
        val initialBubbleEnabled = runCatching {
            TypeFreeApp.instance.repository.settings.value.bubbleEnabled
        }.getOrDefault(false)
        syncServiceFlagsForBubbleState(initialBubbleEnabled)
        TypeFreeBubbleController.setAccessibilityConnected(true)
        scheduleFocusedInputRefresh(allowMultiWindowScan = true, delayMs = 0L)
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val bubbleEnabled = runCatching {
            TypeFreeApp.instance.repository.settings.value.bubbleEnabled
        }.getOrDefault(false)
        if (lastSyncedBubbleImeEnabled != bubbleEnabled) {
            syncServiceFlagsForBubbleState(bubbleEnabled)
        }
        // Main thread only filters event type and schedules trailing-edge debounced work onto
        // the dedicated background HandlerThread so IME rendering on main thread is never blocked.
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!bubbleEnabled) return
                scheduleFocusedInputRefresh(
                    allowMultiWindowScan = false,
                    delayMs = CONTENT_DEBOUNCE_DELAY_MS
                )
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (!bubbleEnabled) return
                scheduleFocusedInputRefresh(
                    allowMultiWindowScan = true,
                    delayMs = FAST_EVENT_DEBOUNCE_DELAY_MS
                )
            }
            else -> Unit
        }
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        lastSyncedBubbleImeEnabled = null
        synchronized(scanLock) {
            scanHandler?.removeCallbacksAndMessages(null)
            scanHandler = null
            scanThread?.quitSafely()
            scanThread = null
            pendingAllowMultiWindowScan = false
            pendingDebounceFirstQueuedAtMs = 0L
        }
        if (activeInstance === this) {
            activeInstance = null
        }
        TypeFreeBubbleController.setAccessibilityConnected(false)
        super.onDestroy()
    }

    private data class CandidateAppWindowContext(
        val root: AccessibilityNodeInfo,
        val occludingRects: List<ScreenRect>
    )

    private fun isSelfOrImeRoot(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val rootPkg = root.packageName?.toString().orEmpty()
        if (rootPkg == packageName) return true
        val winType = runCatching { root.window?.type }.getOrNull()
        return winType == AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }

    private fun resolveDisplaySizePx(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching {
                getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
            }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun resolveCandidateAppWindowContext(allowMultiWindowScan: Boolean): CandidateAppWindowContext? {
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        val windowList = if (allowMultiWindowScan) {
            runCatching { windows }.getOrNull().orEmpty()
        } else {
            emptyList()
        }
        val orderedWindows = if (windowList.size > 1) {
            windowList.sortedByDescending { it.layer }
        } else {
            windowList
        }

        if (activeRoot != null && !isSelfOrImeRoot(activeRoot)) {
            val targetWinId = activeRoot.windowId
            val occluding = ArrayList<ScreenRect>()
            val tmpRect = Rect()
            for (win in orderedWindows) {
                if (win.id == targetWinId) break
                if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val wRoot = win.root
                if (wRoot != null && wRoot.packageName?.toString() == packageName) continue
                win.getBoundsInScreen(tmpRect)
                if (tmpRect.width() > 0 && tmpRect.height() > 0) {
                    occluding.add(ScreenRect(tmpRect.left, tmpRect.top, tmpRect.right, tmpRect.bottom))
                }
            }
            return CandidateAppWindowContext(root = activeRoot, occludingRects = occluding)
        }

        if (!allowMultiWindowScan) return null
        val occluding = ArrayList<ScreenRect>()
        val tmpRect = Rect()
        var fallbackCtx: CandidateAppWindowContext? = null
        for (win in orderedWindows) {
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val winRoot = win.root ?: continue
            if (isSelfOrImeRoot(winRoot)) continue
            if (win.isActive) {
                return CandidateAppWindowContext(root = winRoot, occludingRects = occluding.toList())
            }
            if (fallbackCtx == null) {
                fallbackCtx = CandidateAppWindowContext(root = winRoot, occludingRects = occluding.toList())
            }
            win.getBoundsInScreen(tmpRect)
            if (tmpRect.width() > 0 && tmpRect.height() > 0) {
                occluding.add(ScreenRect(tmpRect.left, tmpRect.top, tmpRect.right, tmpRect.bottom))
            }
        }
        return fallbackCtx
    }

    private val editableScanNodeAccess = object : EditableScanNodeAccess<AccessibilityNodeInfo> {
        override fun isVisibleToUser(node: AccessibilityNodeInfo): Boolean = node.isVisibleToUser

        override fun isEnabled(node: AccessibilityNodeInfo): Boolean = node.isEnabled

        override fun isPassword(node: AccessibilityNodeInfo): Boolean = isPasswordNode(node, node.inputType)

        override fun isEditable(node: AccessibilityNodeInfo): Boolean = node.isEditable

        override fun supportsSetText(node: AccessibilityNodeInfo): Boolean {
            val actions = node.actionList ?: return false
            return actions.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        }

        override fun isDeferredCollectionContainer(node: AccessibilityNodeInfo): Boolean {
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("ScrollView", ignoreCase = true)) {
                return false
            }
            if (node.collectionInfo != null) return true
            return cls.contains("RecyclerView", ignoreCase = true) ||
                cls.contains("ListView", ignoreCase = true) ||
                cls.contains("GridView", ignoreCase = true)
        }

        override fun bounds(node: AccessibilityNodeInfo): ScreenRect? {
            val r = Rect()
            node.getBoundsInScreen(r)
            return ScreenRect(r.left, r.top, r.right, r.bottom)
        }

        override fun viewIdResourceName(node: AccessibilityNodeInfo): String =
            node.viewIdResourceName.orEmpty()

        override fun hintText(node: AccessibilityNodeInfo): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString().orEmpty()
            } else {
                ""
            }

        override fun childCount(node: AccessibilityNodeInfo): Int = node.childCount

        override fun childAt(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? =
            runCatching { node.getChild(index) }.getOrNull()
    }

    private fun scanUnfocusedEditableTarget(
        allowMultiWindowScan: Boolean
    ): DeferredEditableScanOutcome<AccessibilityNodeInfo> {
        val winCtx = resolveCandidateAppWindowContext(allowMultiWindowScan)
            ?: return DeferredEditableScanOutcome()
        val (screenW, screenH) = resolveDisplaySizePx()
        val startNs = System.nanoTime()
        val outcome = scanUnfocusedEditableTreeWithDeferredCollections(
            root = winCtx.root,
            access = editableScanNodeAccess,
            screenWidth = screenW,
            screenHeight = screenH,
            occludingWindowRects = winCtx.occludingRects,
            maxVisitedNodes = MAX_UNFOCUSED_BUBBLE_SCAN_NODES
        )
        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
        val pkg = winCtx.root.packageName?.toString().orEmpty()
        recordScanDiagnosticsIfNeeded(
            scanKind = "bubble_inferred_scan",
            packageName = pkg,
            visitedNodes = outcome.visitedNodes,
            collectedNodes = outcome.totalCandidatesFound,
            capHit = outcome.coverageIncomplete,
            durationMs = elapsedMs
        )
        return outcome
    }

    private fun buildFingerprintFromNode(node: AccessibilityNodeInfo): InputTargetFingerprint {
        val r = Rect()
        node.getBoundsInScreen(r)
        return InputTargetFingerprint(
            packageName = node.packageName?.toString().orEmpty(),
            windowId = node.windowId,
            viewIdResourceName = node.viewIdResourceName.orEmpty(),
            boundsLeft = r.left,
            boundsTop = r.top,
            boundsRight = r.right,
            boundsBottom = r.bottom
        )
    }

    private fun buildSnapshotFromNode(
        node: AccessibilityNodeInfo,
        targetKind: BubbleTargetKind,
        isPassword: Boolean
    ): FocusedInputSnapshot {
        val pkg = node.packageName?.toString().orEmpty()
        val inputType = node.inputType
        val fingerprint = buildFingerprintFromNode(node)
        // Strict privacy rule: NEVER extract or upload text from an INFERRED (unfocused) field.
        if (targetKind != BubbleTargetKind.FOCUSED || isPassword) {
            return FocusedInputSnapshot(
                hasEditableFocus = targetKind == BubbleTargetKind.FOCUSED,
                targetKind = targetKind,
                targetFingerprint = fingerprint,
                isSensitivePassword = isPassword,
                packageName = pkg,
                inputType = inputType,
                selectedText = "",
                surroundingBeforeCursor = ""
            )
        }

        val actualText = readActualNodeText(node)
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        val safeStart = minOf(selStart, selEnd)
        val safeEnd = maxOf(selStart, selEnd)

        val selected = if (
            safeStart >= 0 &&
            safeEnd > safeStart &&
            safeEnd <= actualText.length
        ) {
            actualText.substring(safeStart, safeEnd)
        } else {
            ""
        }

        val beforeCursor = if (
            safeStart > 0 &&
            safeStart <= actualText.length
        ) {
            actualText.substring(0, safeStart).takeLast(80)
        } else {
            actualText.takeLast(80)
        }

        return FocusedInputSnapshot(
            hasEditableFocus = true,
            targetKind = BubbleTargetKind.FOCUSED,
            targetFingerprint = fingerprint,
            isSensitivePassword = false,
            packageName = pkg,
            inputType = inputType,
            selectedText = selected,
            surroundingBeforeCursor = beforeCursor
        )
    }

    fun refreshFocusedInputState(allowMultiWindowScan: Boolean = true): FocusedInputSnapshot {
        val focusedNode = findFocusedEditableNode(allowMultiWindowScan = allowMultiWindowScan)
        if (focusedNode != null) {
            val isPassword = isPasswordNode(focusedNode, focusedNode.inputType)
            val snap = buildSnapshotFromNode(
                node = focusedNode,
                targetKind = BubbleTargetKind.FOCUSED,
                isPassword = isPassword
            )
            TypeFreeBubbleController.updateFocusedInput(snap)
            return snap
        }

        // Prevent debounced TYPE_WINDOW_CONTENT_CHANGED from wiping a valid target when the
        // active window temporarily points to our own floating overlay or a third-party IME window.
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        if (!allowMultiWindowScan && isSelfOrImeRoot(activeRoot)) {
            return TypeFreeBubbleController.focusedInput.value
        }

        val repo = runCatching { TypeFreeApp.instance.repository }.getOrNull()
        val bubbleEnabled = repo?.settings?.value?.bubbleEnabled ?: false
        val imeVisible = repo?.isImeWindowVisible?.value ?: false
        if (bubbleEnabled && !imeVisible) {
            val unfocusedScan = scanUnfocusedEditableTarget(allowMultiWindowScan = allowMultiWindowScan)
            if (unfocusedScan.hasVisiblePassword) {
                val blocked = FocusedInputSnapshot(
                    hasEditableFocus = false,
                    targetKind = BubbleTargetKind.NONE,
                    isSensitivePassword = true
                )
                TypeFreeBubbleController.updateFocusedInput(blocked)
                return blocked
            }
            val inferred = unfocusedScan.selectedTarget
            if (inferred != null) {
                val snap = buildSnapshotFromNode(
                    node = inferred.node,
                    targetKind = BubbleTargetKind.INFERRED,
                    isPassword = false
                )
                TypeFreeBubbleController.updateFocusedInput(snap)
                return snap
            }
        }

        val empty = FocusedInputSnapshot(
            hasEditableFocus = false,
            targetKind = BubbleTargetKind.NONE
        )
        TypeFreeBubbleController.updateFocusedInput(empty)
        return empty
    }

    private fun trySetTextOnNode(node: AccessibilityNodeInfo, polishedText: String): Boolean {
        return try {
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
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun insertPolishedText(
        polishedText: String,
        expectedFingerprint: InputTargetFingerprint? = null,
        expectedTargetKind: BubbleTargetKind? = null
    ): Boolean = insertPolishedTextWithOutcome(
        polishedText = polishedText,
        expectedFingerprint = expectedFingerprint,
        expectedTargetKind = expectedTargetKind
    ) == BubbleInsertOutcome.INSERTED

    /**
     * Injects [polishedText] into the currently focused editable field — or, when no field holds
     * `FOCUS_INPUT` on a non-password screen, the unambiguously inferred visible editable field
     * (deferred-collection priority scan) — after verifying that the target still matches
     * [expectedFingerprint] captured at recording start so mid-recording page switches never
     * leak text into the wrong chat. Never invokes silent `ACTION_FOCUS` on unfocused fields.
     */
    fun insertPolishedTextWithOutcome(
        polishedText: String,
        expectedFingerprint: InputTargetFingerprint? = null,
        expectedTargetKind: BubbleTargetKind? = null
    ): BubbleInsertOutcome {
        if (polishedText.isEmpty()) return BubbleInsertOutcome.COPIED_FALLBACK
        val focusedNode = findFocusedEditableNode()
        if (focusedNode != null && isPasswordNode(focusedNode, focusedNode.inputType)) {
            copyToClipboard(polishedText)
            return BubbleInsertOutcome.COPIED_FALLBACK
        }

        if (focusedNode != null) {
            val currentFp = buildFingerprintFromNode(focusedNode)
            if (expectedFingerprint != null && expectedFingerprint.isValid() && !currentFp.matches(expectedFingerprint)) {
                copyToClipboard(polishedText)
                return BubbleInsertOutcome.TARGET_MISMATCH_COPIED
            }
            // Channel 1: Android 13+ (API 33+) AccessibilityInputConnection (exact cursor insertion!)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val aic = inputMethod?.currentInputConnection
                    if (aic != null) {
                        aic.commitText(polishedText, 1, null)
                        refreshFocusedInputState()
                        return BubbleInsertOutcome.INSERTED
                    }
                } catch (_: Exception) {
                }
            }

            // Channel 2: AccessibilityNodeInfo ACTION_SET_TEXT splicing at selection/cursor
            if (trySetTextOnNode(focusedNode, polishedText)) {
                refreshFocusedInputState()
                return BubbleInsertOutcome.INSERTED
            }

            // Channel 3: Clipboard + ACTION_PASTE fallback
            copyToClipboard(polishedText)
            try {
                if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    refreshFocusedInputState()
                    return BubbleInsertOutcome.INSERTED
                }
            } catch (_: Exception) {
            }
            return BubbleInsertOutcome.COPIED_FALLBACK
        }

        // Unfocused INFERRED path: verify whole-page password shield and recording-start fingerprint
        val unfocusedScan = scanUnfocusedEditableTarget(allowMultiWindowScan = true)
        if (unfocusedScan.hasVisiblePassword) {
            copyToClipboard(polishedText)
            return BubbleInsertOutcome.COPIED_FALLBACK
        }
        val inferred = unfocusedScan.selectedTarget
        if (inferred == null) {
            copyToClipboard(polishedText)
            return if (expectedFingerprint != null && expectedFingerprint.isValid()) {
                BubbleInsertOutcome.TARGET_MISMATCH_COPIED
            } else {
                BubbleInsertOutcome.COPIED_FALLBACK
            }
        }
        val currentFp = buildFingerprintFromNode(inferred.node)
        if (expectedFingerprint != null && expectedFingerprint.isValid() && !currentFp.matches(expectedFingerprint)) {
            copyToClipboard(polishedText)
            return BubbleInsertOutcome.TARGET_MISMATCH_COPIED
        }

        // Direct ACTION_SET_TEXT (preserving existing draft & stripping hint) without calling
        // silent ACTION_FOCUS so we never pop up the soft keyboard and hide the bubble.
        if (trySetTextOnNode(inferred.node, polishedText)) {
            refreshFocusedInputState()
            return BubbleInsertOutcome.INSERTED
        }
        copyToClipboard(polishedText)
        try {
            if (inferred.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                refreshFocusedInputState()
                return BubbleInsertOutcome.INSERTED
            }
        } catch (_: Exception) {
        }
        return BubbleInsertOutcome.COPIED_FALLBACK
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
        const val MAX_VISITED_ACCESSIBILITY_NODES = 360
        const val SLOW_ACCESSIBILITY_SCAN_THRESHOLD_MS = 40L

        internal class TraversalBudget(
            val maxVisitedNodes: Int = MAX_VISITED_ACCESSIBILITY_NODES
        ) {
            var visitedNodes: Int = 0
                private set
            var capHit: Boolean = false
                private set

            fun tryEnterNode(): Boolean {
                if (visitedNodes >= maxVisitedNodes) {
                    capHit = true
                    return false
                }
                visitedNodes += 1
                return true
            }

            fun canDescendIntoChild(): Boolean {
                if (visitedNodes >= maxVisitedNodes) {
                    capHit = true
                    return false
                }
                return true
            }
        }

        const val FAST_EVENT_DEBOUNCE_DELAY_MS = 80L
        const val CONTENT_DEBOUNCE_DELAY_MS = 220L
        const val MAX_DEBOUNCE_WAIT_MS = 600L
        const val MAX_UNFOCUSED_BUBBLE_SCAN_NODES = 240

        internal data class ScreenRect(
            val left: Int,
            val top: Int,
            val right: Int,
            val bottom: Int
        ) {
            val width: Int get() = right - left
            val height: Int get() = bottom - top
            val centerX: Int get() = (left + right) / 2
            val centerY: Int get() = (top + bottom) / 2

            fun containsPoint(x: Int, y: Int): Boolean =
                x in left..right && y in top..bottom

            fun intersectsViewport(screenWidth: Int, screenHeight: Int): Boolean {
                if (width < 20 || height < 12) return false
                if (right <= 0 || bottom <= 0) return false
                if (screenWidth > 0 && left >= screenWidth) return false
                if (screenHeight > 0 && top >= screenHeight) return false
                return true
            }
        }

        internal interface EditableScanNodeAccess<N : Any> {
            fun isVisibleToUser(node: N): Boolean
            fun isEnabled(node: N): Boolean
            fun isPassword(node: N): Boolean
            fun isEditable(node: N): Boolean
            fun supportsSetText(node: N): Boolean
            fun isDeferredCollectionContainer(node: N): Boolean
            fun bounds(node: N): ScreenRect?
            fun viewIdResourceName(node: N): String
            fun hintText(node: N): String
            fun childCount(node: N): Int
            fun childAt(node: N, index: Int): N?
        }

        internal data class InferredEditableCandidate<N : Any>(
            val node: N,
            val bounds: ScreenRect,
            val viewIdResourceName: String = "",
            val hintText: String = "",
            val fromDeferredCollection: Boolean = false
        )

        internal data class DeferredEditableScanOutcome<N : Any>(
            val selectedTarget: InferredEditableCandidate<N>? = null,
            val hasVisiblePassword: Boolean = false,
            val coverageIncomplete: Boolean = false,
            val visitedNodes: Int = 0,
            val totalCandidatesFound: Int = 0
        )

        private fun <N : Any> isLikelySearchCandidate(
            candidate: InferredEditableCandidate<N>,
            screenHeight: Int
        ): Boolean {
            if (screenHeight > 0 && candidate.bounds.bottom > (screenHeight * 0.35f).toInt()) {
                return false
            }
            val idLower = candidate.viewIdResourceName.lowercase()
            val hintLower = candidate.hintText.lowercase()
            return idLower.contains("search") ||
                idLower.contains("query") ||
                idLower.contains("filter") ||
                hintLower.contains("search") ||
                hintLower.contains("搜索") ||
                hintLower.contains("查找")
        }

        /**
         * Selects an unambiguous unfocused target:
         * - 1 candidate -> directly return it (covers WeChat/QQ/Feishu/DingTalk/Telegram chats,
         *   single search bars, single comment composers).
         * - 2+ candidates -> exclude explicit top search/filter bars (`bottom <= 35%` + search/query
         *   id or hint) and accept ONLY when a single bottom composer (`bounds.top >= 60% * screenHeight`)
         *   remains.
         * - Ordinary multi-input forms (address/email/signup) return `null` to prevent filling
         *   the wrong field.
         */
        internal fun <N : Any> selectUnambiguousInferredTarget(
            candidates: List<InferredEditableCandidate<N>>,
            screenHeight: Int
        ): InferredEditableCandidate<N>? {
            if (candidates.isEmpty()) return null
            if (candidates.size == 1) return candidates[0]
            val nonSearch = candidates.filterNot { isLikelySearchCandidate(it, screenHeight) }
            if (nonSearch.size == 1) {
                val sole = nonSearch[0]
                if (screenHeight <= 0 || sole.bounds.top >= (screenHeight * 0.60f).toInt()) {
                    return sole
                }
            }
            return null
        }

        /**
         * Deferred-collection priority scan:
         * - Phase 1 walks all non-collection subtrees (TopBar, fixed ChatFooter, ScrollViews, static
         *   forms) FIRST while deferring `RecyclerView` / `ListView` / `GridView` (`collectionInfo != null`)
         *   into a secondary queue so huge chat lists can never starve a bottom `MMEditText`.
         * - Phase 2 drains deferred collection containers with whatever node budget remains so
         *   password fields or RecyclerView-backed composers are still discovered.
         * - Any visible password node immediately aborts with `hasVisiblePassword = true`.
         * - If `budget.capHit` (`coverageIncomplete == true`) and the selected candidate originated
         *   from a deferred collection, conservatively rejects it (`selectedTarget = null`) so an
         *   unscanned password node inside the same collection can never be bypassed.
         */
        internal fun <N : Any> scanUnfocusedEditableTreeWithDeferredCollections(
            root: N?,
            access: EditableScanNodeAccess<N>,
            screenWidth: Int,
            screenHeight: Int,
            occludingWindowRects: List<ScreenRect> = emptyList(),
            maxVisitedNodes: Int = MAX_UNFOCUSED_BUBBLE_SCAN_NODES,
            maxDepth: Int = 28
        ): DeferredEditableScanOutcome<N> {
            if (root == null) return DeferredEditableScanOutcome()
            val budget = TraversalBudget(maxVisitedNodes)
            val candidates = ArrayList<InferredEditableCandidate<N>>(8)
            val deferredQueue = ArrayDeque<Pair<N, Int>>()
            var hasVisiblePassword = false

            fun collectIfValidCandidate(node: N, fromDeferred: Boolean) {
                if (candidates.size >= 16) return
                if (!access.isEnabled(node) || !access.isEditable(node) || !access.supportsSetText(node)) return
                val rect = access.bounds(node) ?: return
                if (!rect.intersectsViewport(screenWidth, screenHeight)) return
                if (occludingWindowRects.any { it.containsPoint(rect.centerX, rect.centerY) }) return
                candidates.add(
                    InferredEditableCandidate(
                        node = node,
                        bounds = rect,
                        viewIdResourceName = access.viewIdResourceName(node),
                        hintText = access.hintText(node),
                        fromDeferredCollection = fromDeferred
                    )
                )
            }

            fun walkNonCollection(node: N, depth: Int) {
                if (depth > maxDepth || hasVisiblePassword) return
                if (!budget.tryEnterNode()) return
                if (!access.isVisibleToUser(node)) return
                if (access.isPassword(node)) {
                    hasVisiblePassword = true
                    return
                }
                if (access.isDeferredCollectionContainer(node)) {
                    deferredQueue.addLast(node to depth)
                    return
                }
                collectIfValidCandidate(node, fromDeferred = false)
                val count = access.childCount(node)
                for (i in 0 until count) {
                    if (hasVisiblePassword || !budget.canDescendIntoChild()) break
                    val child = access.childAt(node, i) ?: continue
                    walkNonCollection(child, depth + 1)
                }
            }

            fun walkDeferredSubtree(node: N, depth: Int) {
                if (depth > maxDepth || hasVisiblePassword) return
                if (!budget.tryEnterNode()) return
                if (!access.isVisibleToUser(node)) return
                if (access.isPassword(node)) {
                    hasVisiblePassword = true
                    return
                }
                collectIfValidCandidate(node, fromDeferred = true)
                val count = access.childCount(node)
                for (i in 0 until count) {
                    if (hasVisiblePassword || !budget.canDescendIntoChild()) break
                    val child = access.childAt(node, i) ?: continue
                    walkDeferredSubtree(child, depth + 1)
                }
            }

            walkNonCollection(root, 0)
            while (!hasVisiblePassword && deferredQueue.isNotEmpty() && budget.canDescendIntoChild()) {
                val (container, depth) = deferredQueue.removeFirst()
                val count = access.childCount(container)
                for (i in 0 until count) {
                    if (hasVisiblePassword || !budget.canDescendIntoChild()) break
                    val child = access.childAt(container, i) ?: continue
                    walkDeferredSubtree(child, depth + 1)
                }
            }

            if (hasVisiblePassword) {
                return DeferredEditableScanOutcome(
                    selectedTarget = null,
                    hasVisiblePassword = true,
                    coverageIncomplete = budget.capHit,
                    visitedNodes = budget.visitedNodes,
                    totalCandidatesFound = candidates.size
                )
            }

            var selected = selectUnambiguousInferredTarget(candidates, screenHeight)
            if (budget.capHit && selected?.fromDeferredCollection == true) {
                selected = null
            }
            return DeferredEditableScanOutcome(
                selectedTarget = selected,
                hasVisiblePassword = false,
                coverageIncomplete = budget.capHit,
                visitedNodes = budget.visitedNodes,
                totalCandidatesFound = candidates.size
            )
        }

        internal fun recordScanDiagnosticsIfNeeded(
            scanKind: String,
            packageName: String,
            visitedNodes: Int,
            collectedNodes: Int,
            capHit: Boolean,
            durationMs: Long
        ) {
            if (!capHit && durationMs < SLOW_ACCESSIBILITY_SCAN_THRESHOLD_MS) return
            val eventName = when {
                capHit && durationMs >= SLOW_ACCESSIBILITY_SCAN_THRESHOLD_MS -> "${scanKind}_cap_and_slow"
                capHit -> "${scanKind}_node_cap_hit"
                else -> "${scanKind}_slow_scan"
            }
            ImeDiagnosticLogger.record(
                category = "A11Y_SCAN",
                event = eventName,
                detail = "pkg=$packageName visited=$visitedNodes collected=$collectedNodes cap=$MAX_VISITED_ACCESSIBILITY_NODES durMs=$durationMs"
            )
        }

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
