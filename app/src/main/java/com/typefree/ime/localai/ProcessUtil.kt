package com.typefree.ime.localai

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Process identity helper used in [com.typefree.ime.TypeFreeApp.onCreate] to guard against
 * re-initializing main-process singletons (AppRepository, Rime, Flypy, BubbleService)
 * inside the isolated `:local_ai` inference process.
 */
object ProcessUtil {

    const val LOCAL_AI_PROCESS_SUFFIX: String = ":local_ai"
    private const val PROC_SELF_CMDLINE: String = "/proc/self/cmdline"

    /**
     * Returns true if the current process is the isolated `:local_ai` process.
     */
    fun isLocalAiProcess(context: Context? = null): Boolean {
        return isLocalAiProcessName(resolveProcessName(context))
    }

    /**
     * Pure process-name matcher suitable for unit tests and runtime guard checks.
     */
    fun isLocalAiProcessName(processName: String?): Boolean {
        val trimmed = processName?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        return trimmed == LOCAL_AI_PROCESS_SUFFIX || trimmed.endsWith(LOCAL_AI_PROCESS_SUFFIX)
    }

    /**
     * Resolves the current Android process name:
     * - API 28+ (Android 9+): [Application.getProcessName]
     * - API 26-27 (or fallback): reads `/proc/self/cmdline`
     */
    fun resolveProcessName(context: Context? = null): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val fromApi = runCatching { Application.getProcessName() }.getOrNull()
            if (!fromApi.isNullOrBlank()) {
                return fromApi.trim()
            }
        }
        val fromProc = readSelfCmdline()
        if (!fromProc.isNullOrBlank()) {
            return fromProc
        }
        return context?.packageName
    }

    /**
     * Reads and parses `/proc/self/cmdline` (null-terminated bytes on Linux/Android).
     */
    fun readSelfCmdline(cmdlineFile: File = File(PROC_SELF_CMDLINE)): String? {
        return runCatching {
            if (!cmdlineFile.isFile) return null
            parseCmdlineBytes(cmdlineFile.readBytes())
        }.getOrNull()
    }

    /**
     * Extracts the null-terminated argv[0] process name from raw `/proc/self/cmdline` bytes.
     */
    fun parseCmdlineBytes(rawBytes: ByteArray): String? {
        if (rawBytes.isEmpty()) return null
        val endIndex = rawBytes.indexOf(0.toByte()).let { idx ->
            if (idx >= 0) idx else rawBytes.size
        }
        if (endIndex <= 0) return null
        val name = String(rawBytes, 0, endIndex, Charsets.UTF_8).trim()
        return name.ifEmpty { null }
    }
}
