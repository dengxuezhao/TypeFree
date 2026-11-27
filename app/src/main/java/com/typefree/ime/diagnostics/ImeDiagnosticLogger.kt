package com.typefree.ime.diagnostics

import java.time.Instant

/**
 * Diagnostic ring-buffer entry containing strictly non-PII operational metadata
 * (lifecycle transitions, target package names, booleans, node counts, durations in ms,
 * window inset coordinates, and sanitized exception types).
 */
data class ImeDiagnosticEvent(
    val timestampMs: Long,
    val category: String,
    val event: String,
    val detail: String = ""
)

/**
 * Pure-memory, thread-safe fixed-capacity (`120` events) ring-buffer diagnostic logger.
 *
 * Strict Zero-PII Guarantee:
 * - Never writes to disk, SharedPreferences, SQLite, or network.
 * - Never accepts user input text, candidate words, clipboard content, or chat messages.
 * - Applies defense-in-depth scrubbing ([sanitizeDetail], [sanitizeThrowable]) to strip
 *   credentials, tokens, emails, quoted strings, 6+ digit numbers (OTP/phone/ID), and
 *   CJK user text from exception messages before storing in memory.
 */
object ImeDiagnosticLogger {

    const val MAX_EVENTS: Int = 120
    private const val MAX_CATEGORY_LEN: Int = 28
    private const val MAX_EVENT_LEN: Int = 48
    private const val MAX_DETAIL_LEN: Int = 180

    private val lock = Any()
    private val buffer = arrayOfNulls<ImeDiagnosticEvent>(MAX_EVENTS)
    private var headIndex: Int = 0
    private var currentSize: Int = 0
    private val runtimeState = LinkedHashMap<String, String>()

    private val whitespaceRegex = Regex("[\\r\\n\\t]+")
    private val multiSpaceRegex = Regex(" {2,}")
    private val secretTokenRegex = Regex(
        "(?i)(bearer\\s+[A-Za-z0-9._~+/=-]+|sk-[A-Za-z0-9_-]{6,}|AIza[A-Za-z0-9_-]{8,}|(?:api[_-]?key|token|secret|password|authorization)\\s*[:=]\\s*[^\\s,;]+)"
    )
    private val urlQueryRegex = Regex("(https?://[^\\s?#]+)\\?[^\\s]*")
    private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val quotedLiteralRegex = Regex("(\"[^\"]*\"|'[^']*'|「[^」]*」|“[^”]*”)")
    private val longDigitsRegex = Regex("\\b\\d{6,}\\b")
    private val cjkCharsRegex = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]+")
    private val unsafeKeyCharsRegex = Regex("[^A-Za-z0-9_.:/-]")

    fun record(category: String, event: String, detail: String = "") {
        recordAt(
            timestampMs = System.currentTimeMillis(),
            category = category,
            event = event,
            detail = detail
        )
    }

    internal fun recordAt(
        timestampMs: Long,
        category: String,
        event: String,
        detail: String = ""
    ) {
        val safeCategory = sanitizeKeyToken(category, MAX_CATEGORY_LEN).ifEmpty { "UNKNOWN" }
        val safeEvent = sanitizeKeyToken(event, MAX_EVENT_LEN).ifEmpty { "unknown" }
        val safeDetail = sanitizeDetail(detail, MAX_DETAIL_LEN)
        val entry = ImeDiagnosticEvent(
            timestampMs = timestampMs,
            category = safeCategory,
            event = safeEvent,
            detail = safeDetail
        )
        synchronized(lock) {
            buffer[headIndex] = entry
            headIndex = (headIndex + 1) % MAX_EVENTS
            if (currentSize < MAX_EVENTS) {
                currentSize += 1
            }
        }
    }

    fun snapshotEvents(): List<ImeDiagnosticEvent> {
        synchronized(lock) {
            if (currentSize == 0) return emptyList()
            val out = ArrayList<ImeDiagnosticEvent>(currentSize)
            val start = if (currentSize < MAX_EVENTS) {
                0
            } else {
                headIndex
            }
            for (i in 0 until currentSize) {
                val idx = (start + i) % MAX_EVENTS
                buffer[idx]?.let { out.add(it) }
            }
            return out
        }
    }

    fun size(): Int = synchronized(lock) { currentSize }

    fun updateRuntimeState(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            for ((rawKey, rawVal) in entries) {
                val key = sanitizeKeyToken(rawKey, maxLen = 40)
                if (key.isNotEmpty()) {
                    runtimeState[key] = sanitizeDetail(rawVal, maxLen = 96)
                }
            }
        }
    }

    fun updateRuntimeStateEntry(key: String, value: String) {
        val safeKey = sanitizeKeyToken(key, maxLen = 40)
        if (safeKey.isEmpty()) return
        val safeVal = sanitizeDetail(value, maxLen = 96)
        synchronized(lock) {
            if (runtimeState[safeKey] != safeVal) {
                runtimeState[safeKey] = safeVal
            }
        }
    }

    fun snapshotRuntimeState(): Map<String, String> = synchronized(lock) {
        LinkedHashMap(runtimeState)
    }

    fun clear() {
        synchronized(lock) {
            buffer.fill(null)
            headIndex = 0
            currentSize = 0
            runtimeState.clear()
        }
    }

    fun exportSnapshot(
        appVersion: String,
        deviceSummary: String,
        extraState: Map<String, String> = emptyMap()
    ): String {
        val nowMs = System.currentTimeMillis()
        val events = snapshotEvents()
        val safeVersion = sanitizeDetail(appVersion, maxLen = 64).ifEmpty { "unknown" }
        val safeDevice = sanitizeDetail(deviceSummary, maxLen = 96).ifEmpty { "unknown" }

        return buildString(512 + events.size * 96) {
            appendLine("=== TypeFree IME Diagnostic Snapshot (Zero-PII Memory Ring Buffer) ===")
            append("generatedAt: ").appendLine(formatIsoUtc(nowMs))
            append("appVersion: ").appendLine(safeVersion)
            append("device: ").appendLine(safeDevice)
            append("eventsCount: ").append(events.size).append('/').appendLine(MAX_EVENTS)
            if (extraState.isNotEmpty()) {
                appendLine("[state]")
                for ((rawKey, rawVal) in extraState) {
                    val key = sanitizeKeyToken(rawKey, maxLen = 40)
                    if (key.isNotEmpty()) {
                        val value = sanitizeDetail(rawVal, maxLen = 96)
                        append(key).append('=').appendLine(value)
                    }
                }
            }
            appendLine("[events]")
            if (events.isEmpty()) {
                appendLine("(empty)")
            } else {
                for (ev in events) {
                    append(formatIsoUtc(ev.timestampMs))
                    append(" | ")
                    append(ev.category)
                    append(" | ")
                    append(ev.event)
                    if (ev.detail.isNotEmpty()) {
                        append(" | ")
                        append(ev.detail)
                    }
                    appendLine()
                }
            }
        }
    }

    fun sanitizeThrowable(throwable: Throwable): String {
        val clsName = throwable::class.java.simpleName.ifBlank {
            throwable::class.java.name.substringAfterLast('.')
        }.ifBlank { "Throwable" }
        val rawMsg = throwable.message?.trim().orEmpty()
        if (rawMsg.isEmpty()) return clsName
        val scrubbed = sanitizeDetail(
            raw = cjkCharsRegex.replace(rawMsg, "[CJK]"),
            maxLen = 96
        )
        return if (scrubbed.isEmpty()) clsName else "$clsName($scrubbed)"
    }

    fun sanitizeDetail(raw: String, maxLen: Int = MAX_DETAIL_LEN): String {
        if (raw.isBlank()) return ""
        var cleaned = whitespaceRegex.replace(raw, " ").trim()
        cleaned = secretTokenRegex.replace(cleaned, "[REDACTED_SECRET]")
        cleaned = urlQueryRegex.replace(cleaned, "$1?[REDACTED_QUERY]")
        cleaned = emailRegex.replace(cleaned, "[REDACTED_EMAIL]")
        cleaned = quotedLiteralRegex.replace(cleaned, "[REDACTED_TEXT]")
        cleaned = longDigitsRegex.replace(cleaned, "[REDACTED_NUM]")
        cleaned = cjkCharsRegex.replace(cleaned, "[REDACTED_CJK]")
        cleaned = multiSpaceRegex.replace(cleaned, " ").trim()
        return if (cleaned.length <= maxLen) {
            cleaned
        } else {
            cleaned.substring(0, maxLen)
        }
    }

    private fun sanitizeKeyToken(raw: String, maxLen: Int): String {
        if (raw.isBlank()) return ""
        val cleaned = unsafeKeyCharsRegex.replace(raw.trim(), "_")
        return if (cleaned.length <= maxLen) cleaned else cleaned.substring(0, maxLen)
    }

    private fun formatIsoUtc(epochMs: Long): String {
        return runCatching { Instant.ofEpochMilli(epochMs).toString() }
            .getOrDefault(epochMs.toString())
    }
}
