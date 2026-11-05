package com.typefree.ime.ime.clipboard

import com.typefree.ime.data.ClipboardEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ClipboardFilterTab(val label: String) {
    ALL("全部"),
    PINNED("📌 常用"),
    SMART("✨ 提取")
}

enum class SmartClipKind(val badgePrefix: String) {
    OTP_CODE("🔢 验证码"),
    URL("🔗 链接"),
    EMAIL("✉️ 邮箱"),
    PHONE("📱 号码")
}

data class SmartClipExtraction(
    val kind: SmartClipKind,
    val extractedValue: String,
    val chipLabel: String
)

/**
 * Pure Kotlin smart entity extractor & formatter for the built-in IME Clipboard Manager.
 * Recognizes verification codes (OTP), URLs, emails, and phone numbers inside copied messages
 * so users can paste just the extracted token in a single tap.
 */
object ClipboardSmartExtractor {

    private val OTP_KEYWORDS_REGEX = Regex(
        "(验证码|校验码|动态码|确认码|授权码|提货码|取件码|安全码|verification|passcode|otp|auth\\s*code|login\\s*code)",
        RegexOption.IGNORE_CASE
    )
    private val OTP_CANDIDATE_REGEX = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")
    private val URL_REGEX = Regex("https?://[^\\s<>\"'，。！？；：）】\\]]+", RegexOption.IGNORE_CASE)
    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val CN_MOBILE_REGEX = Regex("(?<!\\d)(?:\\+?86[-\\s]?)?(1[3-9]\\d[-\\s]?\\d{4}[-\\s]?\\d{4})(?!\\d)")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Extracts the highest-priority actionable token from [rawText], or null if none is present.
     */
    fun extractPrimary(rawText: String): SmartClipExtraction? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null

        // 1. Verification Code (OTP 4-8 digits when message contains OTP context keywords)
        if (OTP_KEYWORDS_REGEX.containsMatchIn(trimmed)) {
            val match = OTP_CANDIDATE_REGEX.findAll(trimmed)
                .map { it.groupValues[1] }
                .firstOrNull { candidate ->
                    // Avoid matching 4-digit current years like 2024..2030 when a real 6-digit code follows
                    !(candidate.length == 4 && candidate.toIntOrNull() in 2020..2035)
                }
            if (match != null) {
                return SmartClipExtraction(
                    kind = SmartClipKind.OTP_CODE,
                    extractedValue = match,
                    chipLabel = "🔢 仅填 $match"
                )
            }
        }

        // 2. HTTP / HTTPS URL
        URL_REGEX.find(trimmed)?.value?.trimEnd('.', ',', ';', ')', ']')?.let { url ->
            if (url.length >= 10) {
                val host = url.removePrefix("https://").removePrefix("http://").substringBefore('/')
                val shortHost = if (host.length > 16) host.take(15) + "…" else host
                return SmartClipExtraction(
                    kind = SmartClipKind.URL,
                    extractedValue = url,
                    chipLabel = "🔗 $shortHost"
                )
            }
        }

        // 3. Email Address
        EMAIL_REGEX.find(trimmed)?.value?.let { email ->
            val shortEmail = if (email.length > 18) email.take(17) + "…" else email
            return SmartClipExtraction(
                kind = SmartClipKind.EMAIL,
                extractedValue = email,
                chipLabel = "✉️ $shortEmail"
            )
        }

        // 4. Mobile Phone Number
        CN_MOBILE_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { rawPhone ->
            val digitsOnly = rawPhone.filter { it.isDigit() }
            if (digitsOnly.length == 11) {
                return SmartClipExtraction(
                    kind = SmartClipKind.PHONE,
                    extractedValue = digitsOnly,
                    chipLabel = "📱 $digitsOnly"
                )
            }
        }

        return null
    }

    /**
     * Filters [items] according to the active [ClipboardFilterTab].
     */
    fun filterItems(
        items: List<ClipboardEntry>,
        tab: ClipboardFilterTab
    ): List<ClipboardEntry> {
        return when (tab) {
            ClipboardFilterTab.ALL -> items
            ClipboardFilterTab.PINNED -> items.filter { it.isPinned }
            ClipboardFilterTab.SMART -> items.filter { extractPrimary(it.text) != null }
        }
    }

    /**
     * Formats compact single-line preview for top-bar suggestion capsules.
     */
    fun formatSingleLinePreview(text: String, maxChars: Int = 12): String {
        val clean = text.trim().replace(WHITESPACE_REGEX, " ")
        if (clean.length <= maxChars) return clean
        return clean.take(maxChars.coerceAtLeast(1)).trimEnd() + "…"
    }

    /**
     * Formats human-friendly relative timestamp (`刚刚`, `5分钟前`, `2小时前`, `MM-dd HH:mm`).
     */
    fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diffSec = ((nowMs - timestampMs).coerceAtLeast(0L)) / 1000L
        return when {
            diffSec < 45L -> "刚刚"
            diffSec < 3600L -> "${diffSec / 60L}分钟前"
            diffSec < 86_400L -> "${diffSec / 3600L}小时前"
            diffSec < 172_800L -> "昨天"
            else -> SimpleDateFormat("MM-dd", Locale.US).format(Date(timestampMs))
        }
    }
}
