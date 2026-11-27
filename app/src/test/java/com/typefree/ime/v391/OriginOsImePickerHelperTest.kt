package com.typefree.ime.v391

import com.typefree.ime.ui.screens.ImePickerFocusCoordinator
import com.typefree.ime.ui.screens.ImePickerFocusCoordinator.DispatchOutcome
import com.typefree.ime.ui.screens.ImePickerFocusCoordinator.GateState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginOsImePickerHelperTest {

    private class FakeFocusAnchor(
        override var isAttached: Boolean = true,
        var requestFocusResult: Boolean = true,
        var postDelayedResult: Boolean = true
    ) : ImePickerFocusCoordinator.FocusAnchor {

        data class ScheduledTask(val delayMs: Long, val action: () -> Unit)

        var requestFocusCalls: Int = 0
            private set

        val scheduledTasks = mutableListOf<ScheduledTask>()

        override fun requestFocus(): Boolean {
            requestFocusCalls += 1
            return requestFocusResult
        }

        override fun postDelayed(delayMs: Long, action: () -> Unit): Boolean {
            if (!postDelayedResult) return false
            scheduledTasks += ScheduledTask(delayMs = delayMs, action = action)
            return true
        }

        fun runAllScheduled() {
            val snapshot = scheduledTasks.toList()
            scheduledTasks.clear()
            snapshot.forEach { it.action() }
        }
    }

    private fun readyGateState(
        currentStep: Int = 0,
        autoTriggerPending: Boolean = true,
        isWindowFocused: Boolean = true,
        isResumed: Boolean = true,
        isImeEnabled: Boolean = true,
        isImeSelected: Boolean = false
    ): GateState = GateState(
        currentStep = currentStep,
        autoTriggerPending = autoTriggerPending,
        isWindowFocused = isWindowFocused,
        isResumed = isResumed,
        isImeEnabled = isImeEnabled,
        isImeSelected = isImeSelected
    )

    @Test
    fun `shouldMountHiddenFocusAnchor mounts only on step 1 while IME is not yet selected`() {
        assertTrue(ImePickerFocusCoordinator.shouldMountHiddenFocusAnchor(currentStep = 0, isImeSelected = false))
        assertFalse(ImePickerFocusCoordinator.shouldMountHiddenFocusAnchor(currentStep = 0, isImeSelected = true))
        assertFalse(ImePickerFocusCoordinator.shouldMountHiddenFocusAnchor(currentStep = 1, isImeSelected = false))
        assertFalse(ImePickerFocusCoordinator.shouldMountHiddenFocusAnchor(currentStep = 2, isImeSelected = false))
        assertFalse(ImePickerFocusCoordinator.shouldMountHiddenFocusAnchor(currentStep = 3, isImeSelected = false))
    }

    @Test
    fun `canAutoTrigger requires window focus, RESUMED lifecycle, enabled IME, unselected IME, and step 1`() {
        assertTrue(ImePickerFocusCoordinator.canAutoTrigger(readyGateState()))

        assertFalse(ImePickerFocusCoordinator.canAutoTrigger(readyGateState(isWindowFocused = false)))
        assertFalse(ImePickerFocusCoordinator.canAutoTrigger(readyGateState(isResumed = false)))
        assertFalse(ImePickerFocusCoordinator.canAutoTrigger(readyGateState(isImeEnabled = false)))
        assertFalse(ImePickerFocusCoordinator.canAutoTrigger(readyGateState(isImeSelected = true)))
        assertFalse(ImePickerFocusCoordinator.canAutoTrigger(readyGateState(autoTriggerPending = false)))
        assertFalse(ImePickerFocusCoordinator.canAutoTrigger(readyGateState(currentStep = 1)))
    }

    @Test
    fun `auto trigger waits 320ms settle delay, acquires hidden EditText focus, then opens picker after 80ms`() = runBlocking {
        var nowMs = 1_000L
        val session = ImePickerFocusCoordinator.Session(clockMs = { nowMs })
        val anchor = FakeFocusAnchor()
        val recordedSettleDelays = mutableListOf<Long>()
        var pendingAuto = true
        var pickerShownCount = 0

        val outcome = session.runAutoTriggerWhenSettled(
            initialState = readyGateState(autoTriggerPending = pendingAuto),
            recheckState = { readyGateState(autoTriggerPending = pendingAuto) },
            onConsumePending = { pendingAuto = false },
            anchorProvider = { anchor },
            showPicker = { pickerShownCount += 1 },
            delayFn = { ms ->
                recordedSettleDelays += ms
                nowMs += ms
            }
        )

        assertEquals(DispatchOutcome.DISPATCHED_WITH_FOCUS_HANDSHAKE, outcome)
        assertEquals(listOf(ImePickerFocusCoordinator.AUTO_TRIGGER_SETTLE_DELAY_MS), recordedSettleDelays)
        assertFalse("Pending auto-trigger flag must be consumed once handshake dispatches", pendingAuto)
        assertEquals(1, anchor.requestFocusCalls)
        assertEquals(1, anchor.scheduledTasks.size)
        assertEquals(ImePickerFocusCoordinator.FOCUS_HANDSHAKE_DELAY_MS, anchor.scheduledTasks.single().delayMs)
        assertEquals("Picker must wait for the 80ms FOCUS_INPUT handshake", 0, pickerShownCount)
        assertTrue(session.isHandshakeInFlight)

        nowMs += ImePickerFocusCoordinator.FOCUS_HANDSHAKE_DELAY_MS
        anchor.runAllScheduled()

        assertFalse(session.isHandshakeInFlight)
        assertEquals(1, pickerShownCount)
    }

    @Test
    fun `window focus flicker during 320ms transition settle preserves pending flag and succeeds on next stable focus`() = runBlocking {
        var nowMs = 2_000L
        val session = ImePickerFocusCoordinator.Session(clockMs = { nowMs })
        val anchor = FakeFocusAnchor()
        var pendingAuto = true
        var pickerShownCount = 0

        // Attempt 1: Window loses focus right as the 320ms settle completes (e.g. OriginOS transition animation)
        val blockedOutcome = session.runAutoTriggerWhenSettled(
            initialState = readyGateState(autoTriggerPending = pendingAuto, isWindowFocused = true),
            recheckState = { readyGateState(autoTriggerPending = pendingAuto, isWindowFocused = false) },
            onConsumePending = { pendingAuto = false },
            anchorProvider = { anchor },
            showPicker = { pickerShownCount += 1 },
            delayFn = { ms -> nowMs += ms }
        )

        assertEquals(DispatchOutcome.BLOCKED_BY_GATE, blockedOutcome)
        assertTrue("Pending flag must remain armed when focus flickers during transition", pendingAuto)
        assertEquals(0, anchor.requestFocusCalls)
        assertEquals(0, pickerShownCount)

        // Attempt 2: Window focus & RESUMED stabilize -> dispatches cleanly
        val secondOutcome = session.runAutoTriggerWhenSettled(
            initialState = readyGateState(autoTriggerPending = pendingAuto, isWindowFocused = true, isResumed = true),
            recheckState = { readyGateState(autoTriggerPending = pendingAuto, isWindowFocused = true, isResumed = true) },
            onConsumePending = { pendingAuto = false },
            anchorProvider = { anchor },
            showPicker = { pickerShownCount += 1 },
            delayFn = { ms -> nowMs += ms }
        )

        assertEquals(DispatchOutcome.DISPATCHED_WITH_FOCUS_HANDSHAKE, secondOutcome)
        assertFalse(pendingAuto)
        anchor.runAllScheduled()
        assertEquals(1, pickerShownCount)
    }

    @Test
    fun `auto trigger clears pending flag and skips picker if TypeFree is already selected`() = runBlocking {
        val session = ImePickerFocusCoordinator.Session(clockMs = { 5_000L })
        val anchor = FakeFocusAnchor()
        var pendingAuto = true
        var pickerShownCount = 0

        val outcome = session.runAutoTriggerWhenSettled(
            initialState = readyGateState(autoTriggerPending = pendingAuto, isImeSelected = true),
            recheckState = { readyGateState(autoTriggerPending = pendingAuto, isImeSelected = true) },
            onConsumePending = { pendingAuto = false },
            anchorProvider = { anchor },
            showPicker = { pickerShownCount += 1 },
            delayFn = {}
        )

        assertEquals(DispatchOutcome.BLOCKED_BY_GATE, outcome)
        assertFalse(pendingAuto)
        assertEquals(0, anchor.requestFocusCalls)
        assertEquals(0, pickerShownCount)
    }

    @Test
    fun `manual trigger acquires hidden EditText focus, clears pending auto trigger, and gates invalid states`() {
        var nowMs = 10_000L
        val session = ImePickerFocusCoordinator.Session(clockMs = { nowMs })
        val anchor = FakeFocusAnchor()
        var pendingAuto = true
        var pickerShownCount = 0

        // Blocked when IME is not enabled yet
        assertEquals(
            DispatchOutcome.BLOCKED_BY_GATE,
            session.triggerManual(
                currentStep = 0,
                isImeEnabled = false,
                isImeSelected = false,
                onClearPendingAuto = { pendingAuto = false },
                anchor = anchor,
                showPicker = { pickerShownCount += 1 }
            )
        )
        assertTrue(pendingAuto)

        // Blocked when IME is already selected
        assertEquals(
            DispatchOutcome.BLOCKED_BY_GATE,
            session.triggerManual(
                currentStep = 0,
                isImeEnabled = true,
                isImeSelected = true,
                onClearPendingAuto = { pendingAuto = false },
                anchor = anchor,
                showPicker = { pickerShownCount += 1 }
            )
        )

        // Valid manual click on Step 1
        val outcome = session.triggerManual(
            currentStep = 0,
            isImeEnabled = true,
            isImeSelected = false,
            onClearPendingAuto = { pendingAuto = false },
            anchor = anchor,
            showPicker = { pickerShownCount += 1 }
        )
        assertEquals(DispatchOutcome.DISPATCHED_WITH_FOCUS_HANDSHAKE, outcome)
        assertFalse(pendingAuto)
        assertEquals(1, anchor.requestFocusCalls)
        assertEquals(0, pickerShownCount)

        nowMs += ImePickerFocusCoordinator.FOCUS_HANDSHAKE_DELAY_MS
        anchor.runAllScheduled()
        assertEquals(1, pickerShownCount)
    }

    @Test
    fun `deduplicates rapid manual clicks and overlapping auto-manual triggers, then allows retry after cooldown`() {
        var nowMs = 20_000L
        val session = ImePickerFocusCoordinator.Session(clockMs = { nowMs })
        val anchor = FakeFocusAnchor()
        var pickerShownCount = 0

        // First trigger at t = 20_000ms
        assertEquals(
            DispatchOutcome.DISPATCHED_WITH_FOCUS_HANDSHAKE,
            session.triggerManual(
                currentStep = 0,
                isImeEnabled = true,
                isImeSelected = false,
                anchor = anchor,
                showPicker = { pickerShownCount += 1 }
            )
        )

        // Rapid second tap at t + 30ms while 80ms postDelayed is still in-flight -> deduplicated
        nowMs += 30L
        assertEquals(
            DispatchOutcome.DEDUPLICATED,
            session.triggerManual(
                currentStep = 0,
                isImeEnabled = true,
                isImeSelected = false,
                anchor = anchor,
                showPicker = { pickerShownCount += 1 }
            )
        )

        // 80ms callback fires -> picker opens once
        nowMs += 50L
        anchor.runAllScheduled()
        assertEquals(1, pickerShownCount)

        // Rapid third tap at t + 200ms (within 500ms cooldown) -> deduplicated
        nowMs += 120L
        assertEquals(
            DispatchOutcome.DEDUPLICATED,
            session.triggerManual(
                currentStep = 0,
                isImeEnabled = true,
                isImeSelected = false,
                anchor = anchor,
                showPicker = { pickerShownCount += 1 }
            )
        )
        assertEquals(1, pickerShownCount)

        // User dismissed picker without selecting TypeFree and taps again after 500ms cooldown -> allowed!
        nowMs += ImePickerFocusCoordinator.DEDUPE_COOLDOWN_MS
        assertEquals(
            DispatchOutcome.DISPATCHED_WITH_FOCUS_HANDSHAKE,
            session.triggerManual(
                currentStep = 0,
                isImeEnabled = true,
                isImeSelected = false,
                anchor = anchor,
                showPicker = { pickerShownCount += 1 }
            )
        )
        anchor.runAllScheduled()
        assertEquals(2, pickerShownCount)
    }

    @Test
    fun `falls back to direct showInputMethodPicker when hidden EditText ref is null, detached, or postDelayed fails`() {
        var nowMs = 30_000L
        val session = ImePickerFocusCoordinator.Session(clockMs = { nowMs })
        var pickerShownCount = 0

        // 1. Null anchor fallback
        assertEquals(
            DispatchOutcome.DISPATCHED_FALLBACK_DIRECT,
            session.dispatchFocusHandshake(
                anchor = null,
                showPicker = { pickerShownCount += 1 }
            )
        )
        assertEquals(1, pickerShownCount)

        // 2. Detached anchor fallback
        nowMs += ImePickerFocusCoordinator.DEDUPE_COOLDOWN_MS + 10L
        val detachedAnchor = FakeFocusAnchor(isAttached = false)
        assertEquals(
            DispatchOutcome.DISPATCHED_FALLBACK_DIRECT,
            session.dispatchFocusHandshake(
                anchor = detachedAnchor,
                showPicker = { pickerShownCount += 1 }
            )
        )
        assertEquals(0, detachedAnchor.requestFocusCalls)
        assertEquals(2, pickerShownCount)

        // 3. postDelayed rejected fallback
        nowMs += ImePickerFocusCoordinator.DEDUPE_COOLDOWN_MS + 10L
        val rejectedPostAnchor = FakeFocusAnchor(isAttached = true, postDelayedResult = false)
        assertEquals(
            DispatchOutcome.DISPATCHED_FALLBACK_DIRECT,
            session.dispatchFocusHandshake(
                anchor = rejectedPostAnchor,
                showPicker = { pickerShownCount += 1 }
            )
        )
        assertEquals(1, rejectedPostAnchor.requestFocusCalls)
        assertFalse(session.isHandshakeInFlight)
        assertEquals(3, pickerShownCount)
    }

    @Test
    fun `retries requestFocus before showInputMethodPicker if initial focus request returned false`() {
        var nowMs = 40_000L
        val session = ImePickerFocusCoordinator.Session(clockMs = { nowMs })
        val anchor = FakeFocusAnchor(requestFocusResult = false)
        var pickerShownCount = 0

        val outcome = session.dispatchFocusHandshake(
            anchor = anchor,
            showPicker = { pickerShownCount += 1 }
        )
        assertEquals(DispatchOutcome.DISPATCHED_WITH_FOCUS_HANDSHAKE, outcome)
        assertEquals(1, anchor.requestFocusCalls)

        anchor.requestFocusResult = true
        nowMs += ImePickerFocusCoordinator.FOCUS_HANDSHAKE_DELAY_MS
        anchor.runAllScheduled()

        assertEquals("Should retry requestFocus once inside the 80ms callback before opening picker", 2, anchor.requestFocusCalls)
        assertEquals(1, pickerShownCount)
    }

    @Test
    fun `recovers cleanly if a View handler ever drops an in-flight postDelayed runnable`() {
        var nowMs = 50_000L
        val session = ImePickerFocusCoordinator.Session(clockMs = { nowMs })
        val anchor = FakeFocusAnchor()
        var pickerShownCount = 0

        assertEquals(
            DispatchOutcome.DISPATCHED_WITH_FOCUS_HANDSHAKE,
            session.dispatchFocusHandshake(anchor = anchor, showPicker = { pickerShownCount += 1 })
        )
        assertTrue(session.isHandshakeInFlight)

        // Simulate handler dropping the runnable without invoking it
        anchor.scheduledTasks.clear()

        // After safety expiry, a subsequent user click must self-heal rather than deadlocking
        nowMs += ImePickerFocusCoordinator.DEDUPE_COOLDOWN_MS + 100L
        assertEquals(
            DispatchOutcome.DISPATCHED_WITH_FOCUS_HANDSHAKE,
            session.dispatchFocusHandshake(anchor = anchor, showPicker = { pickerShownCount += 1 })
        )
        anchor.runAllScheduled()
        assertEquals(1, pickerShownCount)
    }
}
