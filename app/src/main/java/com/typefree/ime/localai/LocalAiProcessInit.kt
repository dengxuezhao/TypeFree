package com.typefree.ime.localai

import android.content.Context
import android.util.Log
import com.typefree.ime.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight logger for the `localai` subsystem:
 * - Redacts user text payloads in release builds.
 * - Guards [android.util.Log] calls so pure JVM unit tests never fail on unmocked android.util.Log.
 */
object LocalAiLog {
    private const val TAG = "TypeFreeLocalAi"

    @Volatile
    var redactPayloads: Boolean = !BuildConfig.DEBUG

    fun d(message: String) {
        if (redactPayloads) return
        runCatching { Log.d(TAG, message) }
    }

    fun i(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    fun w(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
        }
    }

    fun safePreview(text: String, maxChars: Int = 24): String {
        if (redactPayloads) return "<redacted:${text.length}ch>"
        return if (text.length <= maxChars) text else text.take(maxChars) + "..."
    }
}

/**
 * Minimal process initializer executed exclusively in the `:local_ai` process.
 *
 * STRICT INVARIANT:
 * Must NEVER access `TypeFreeApp.instance.repository`, `RimeDataManager`, `RimeInputEngine`,
 * `FlypyDictionary`, `TypeFreeBubbleController`, or main-process SharedPreferences / SQLite DBs.
 */
object LocalAiProcessInit {

    private val initialized = AtomicBoolean(false)

    @Volatile
    var initializedProcessName: String? = null
        private set

    val isInitialized: Boolean
        get() = initialized.get()

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        LocalAiLog.redactPayloads = !BuildConfig.DEBUG
        LocalInferenceEngineBootstrap.initialize(context)
        val procName = ProcessUtil.resolveProcessName(context) ?: ProcessUtil.LOCAL_AI_PROCESS_SUFFIX
        initializedProcessName = procName
        LocalAiLog.i(
            "Initialized lightweight local AI process: process=$procName, " +
                "protocolV=$LOCAL_AI_PROTOCOL_VERSION, " +
                "sherpaAsr=${LocalInferenceEngineBootstrap.isSherpaAsrRegistered}, " +
                "slmAvailable=${LocalInferenceEngineRegistry.isSlmEngineAvailable}"
        )
    }

    internal fun resetForTesting() {
        initialized.set(false)
        initializedProcessName = null
    }
}
