package com.typefree.ime.v341

import com.typefree.ime.ime.KeyboardMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V341RealDeviceBugfixTest {

    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "app/src/main").exists()) {
            cwd
        } else {
            cwd.parentFile ?: cwd
        }
    }

    @Test
    fun floatingPreeditOverhangMetrics_onComputeInsetsShift_andSpaciousSingleRowCandidateStrip_matchV341Spec() {
        assertEquals(28, KeyboardMetrics.FLOATING_PREEDIT_OVERHANG_DP)

        // When SoftInputWindow mInputFrame top is at Y=1566 on a 2400px screen and floating overhang is 84px (28dp @ 3x),
        // contentTopInsets shifts down to Y=1650 (leaving the 252dp = 756px keyboard frame intact for the host app).
        val shiftedInset = KeyboardMetrics.computeFloatingOverhangContentTopInset(
            baseContentTopInsets = 1566,
            floatingOverhangPx = 84,
            decorHeight = 2400
        )
        assertEquals(1650, shiftedInset)

        // Safety clamp: never exceed decorHeight
        val clampedInset = KeyboardMetrics.computeFloatingOverhangContentTopInset(
            baseContentTopInsets = 2360,
            floatingOverhangPx = 84,
            decorHeight = 2400
        )
        assertEquals(2400, clampedInset)

        val root = resolveProjectRoot()
        val candidateStripsSource = File(root, "app/src/main/java/com/typefree/ime/ime/candidates/CandidateStrips.kt").readText()
        assertTrue(
            "CandidateStrips.kt must define FloatingPreeditCapsule",
            candidateStripsSource.contains("fun FloatingPreeditCapsule(")
        )
        assertTrue(
            "CandidateStrip must be restored to spacious single-row topBarHeight",
            candidateStripsSource.contains(".height(metrics.topBarHeight)")
        )

        val keyboardScreenFile = File(root, "app/src/main/java/com/typefree/ime/ime/KeyboardScreen.kt")
        val keyboardScreenSource = keyboardScreenFile.readText()
        val keyboardScreenLines = keyboardScreenFile.readLines().size
        assertTrue(
            "KeyboardScreen.kt must render FloatingPreeditCapsule in the top overhang",
            keyboardScreenSource.contains("FloatingPreeditCapsule(")
        )
        assertTrue(
            "KeyboardScreen.kt must stay <= 650 lines (actual: $keyboardScreenLines)",
            keyboardScreenLines <= 650
        )

        val imsSource = File(root, "app/src/main/java/com/typefree/ime/ime/TypeFreeInputMethodService.kt").readText()
        assertTrue(
            "TypeFreeInputMethodService.kt must shift contentTopInsets via computeFloatingOverhangContentTopInset",
            imsSource.contains("computeFloatingOverhangContentTopInset")
        )
        assertTrue(
            "TypeFreeInputMethodService.kt must set TOUCHABLE_INSETS_CONTENT so overhang touches pass through",
            imsSource.contains("Insets.TOUCHABLE_INSETS_CONTENT")
        )
    }
}
