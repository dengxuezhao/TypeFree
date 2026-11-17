package com.typefree.ime.ime.jev.memory

import android.view.inputmethod.EditorInfo
import com.typefree.ime.bubble.ChatScreenContextExtractor
import com.typefree.ime.ime.ImeLogic

/**
 * On-device hard circuit-breaker guarding all Jev relationship memory reads & writes.
 * Immediately bypasses memory when incognito mode, `IME_FLAG_NO_PERSONALIZED_LEARNING`,
 * password fields, or sensitive finance/payment/authenticator packages are active.
 */
object JevPrivacyGuard {

    fun shouldBypassMemory(
        packageName: String,
        inputType: Int,
        imeOptions: Int,
        isIncognitoMode: Boolean,
        manualIncognitoMode: Boolean,
        jevMemoryEnabled: Boolean
    ): Boolean {
        if (!jevMemoryEnabled) return true
        if (isIncognitoMode || manualIncognitoMode) return true
        if ((imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true
        if (ImeLogic.isPasswordEditorInputType(inputType)) return true
        val pkg = packageName.trim()
        if (pkg.isNotEmpty() && ChatScreenContextExtractor.isSensitiveBlacklistedPackage(pkg)) {
            return true
        }
        return false
    }
}
