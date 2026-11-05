package com.typefree.ime.bubble

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.typefree.ime.TypeFreeApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FocusedInputSnapshot(
    val hasEditableFocus: Boolean = false,
    val isSensitivePassword: Boolean = false,
    val packageName: String = "",
    val inputType: Int = 0,
    val selectedText: String = "",
    val surroundingBeforeCursor: String = ""
)

object TypeFreeBubbleController {

    private val _focusedInput = MutableStateFlow(FocusedInputSnapshot())
    val focusedInput: StateFlow<FocusedInputSnapshot> = _focusedInput.asStateFlow()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _isBubbleServiceRunning = MutableStateFlow(false)
    val isBubbleServiceRunning: StateFlow<Boolean> = _isBubbleServiceRunning.asStateFlow()

    private val _forcePreviewVisible = MutableStateFlow(false)
    val forcePreviewVisible: StateFlow<Boolean> = _forcePreviewVisible.asStateFlow()

    internal fun setAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.value = connected
        if (!connected) {
            _focusedInput.value = FocusedInputSnapshot()
        }
    }

    internal fun setBubbleServiceRunning(running: Boolean) {
        _isBubbleServiceRunning.value = running
    }

    internal fun updateFocusedInput(snapshot: FocusedInputSnapshot) {
        _focusedInput.value = snapshot
    }

    fun setForcePreviewVisible(visible: Boolean) {
        _forcePreviewVisible.value = visible
    }

    fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isAccessibilityEnabledInSettings(context: Context): Boolean {
        if (_isAccessibilityConnected.value) return true
        return try {
            val expected = ComponentName(context, TypeFreeAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            enabledServices.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == expected
            }
        } catch (_: Exception) {
            false
        }
    }

    fun openOverlayPermissionSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun syncBubbleService(context: Context) {
        val appContext = context.applicationContext
        val settings = TypeFreeApp.instance.repository.settings.value
        val intent = Intent(appContext, TypeFreeBubbleService::class.java)
        if (settings.bubbleEnabled && hasOverlayPermission(appContext)) {
            try {
                appContext.startService(intent)
            } catch (_: Exception) {
            }
        } else {
            try {
                appContext.stopService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
