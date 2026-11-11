package com.typefree.ime.bubble

import android.content.Context
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.AppToneRule
import com.typefree.ime.data.DefaultPresets
import com.typefree.ime.ime.jev.JevDomainProfile
import com.typefree.ime.privacy.LocalPiiRedactor
import com.typefree.ime.privacy.RedactionSession
import kotlin.math.abs

enum class ChatSpeaker(val tag: String) {
    OTHER("[对方]"),
    SELF("[我]"),
    UNKNOWN("[未知]")
}

data class RawScreenTextNode(
    val text: String,
    val boundsTop: Int,
    val boundsBottom: Int,
    val boundsLeft: Int,
    val boundsRight: Int,
    val isEditable: Boolean = false,
    val isClickable: Boolean = false,
    val viewIdResourceName: String = "",
    val className: String = "",
    val isPassword: Boolean = false,
    val isVisibleToUser: Boolean = true
)

data class ExtractedChatMessage(
    val speaker: ChatSpeaker,
    val text: String,
    val boundsTop: Int
) {
    val formattedWithSpeaker: String
        get() = "${speaker.tag} $text"
}

data class CapturedChatContext(
    val packageName: String = "",
    val appName: String = "",
    val messages: List<ExtractedChatMessage> = emptyList(),
    val latestMessagePreview: String = "",
    /**
     * In-memory cropped JPEG Base64 of the chat area above the keyboard (Android 11+ `takeScreenshot`).
     * Never persisted to disk, history, or preferences; cleared immediately on panel close.
     */
    val screenshotJpegBase64: String = "",
    val screenshotWidth: Int = 0,
    val screenshotHeight: Int = 0,
    val capturedAtMs: Long = 0L,
    val redactionSession: RedactionSession = RedactionSession.EMPTY,
    val redactionCount: Int = redactionSession.redactionCount,
    val hasHighRiskRedaction: Boolean = redactionSession.hasHighRiskCredentials,
    val domainProfile: JevDomainProfile = JevDomainProfile.fromPackage(packageName)
) {
    val hasScreenshot: Boolean
        get() = screenshotJpegBase64.isNotBlank()

    val hasContext: Boolean
        get() = messages.isNotEmpty() || hasScreenshot

    val hasMessages: Boolean
        get() = messages.isNotEmpty()

    val messageCount: Int
        get() = messages.size

    val formattedMessages: List<String>
        get() = messages.map { it.formattedWithSpeaker }

    val formattedLines: List<String>
        get() = formattedMessages

    val latestIncomingMessage: String
        get() = (messages.lastOrNull { it.speaker == ChatSpeaker.OTHER } ?: messages.lastOrNull())?.text.orEmpty()

    fun withViewportOutcome(
        outcome: ViewportCaptureOutcome,
        fallbackPackage: String = packageName,
        fallbackAppName: String = appName
    ): CapturedChatContext {
        val resolvedPkg = packageName.ifBlank { fallbackPackage }
        val resolvedApp = appName.ifBlank { fallbackAppName }
        val updatedMessages = if (outcome.mergedMessages.isNotEmpty()) {
            outcome.mergedMessages.mapIndexed { idx, line ->
                ChatScreenContextExtractor.parseFormattedChatMessage(line, fallbackTop = (idx + 1) * 100)
            }
        } else {
            messages
        }
        val updatedSession = if (
            outcome.mergedMessages.isNotEmpty() ||
            outcome.redactionSession.redactionCount > redactionSession.redactionCount ||
            outcome.redactionSession.hasHighRiskCredentials
        ) {
            outcome.redactionSession
        } else {
            redactionSession
        }
        val safeScreenshot = if (updatedSession.hasHighRiskCredentials) "" else outcome.screenshotJpegBase64
        val previewTarget = updatedMessages.lastOrNull { it.speaker == ChatSpeaker.OTHER }
            ?: updatedMessages.lastOrNull()
        val updatedPreview = when {
            previewTarget != null -> {
                val body = if (previewTarget.text.length > 32) {
                    "…" + previewTarget.text.takeLast(30)
                } else {
                    previewTarget.text
                }
                "${previewTarget.speaker.tag} $body"
            }
            safeScreenshot.isNotBlank() -> latestMessagePreview.ifBlank { "📸 已截屏捕获聊天画面与图片" }
            else -> latestMessagePreview
        }
        return copy(
            packageName = resolvedPkg,
            appName = resolvedApp,
            messages = updatedMessages,
            latestMessagePreview = updatedPreview,
            screenshotJpegBase64 = safeScreenshot,
            screenshotWidth = if (safeScreenshot.isNotBlank()) outcome.width else 0,
            screenshotHeight = if (safeScreenshot.isNotBlank()) outcome.height else 0,
            redactionSession = updatedSession,
            redactionCount = updatedSession.redactionCount,
            hasHighRiskRedaction = updatedSession.hasHighRiskCredentials,
            domainProfile = if (resolvedPkg.isNotBlank()) JevDomainProfile.fromPackage(resolvedPkg) else domainProfile
        )
    }

    companion object {
        val EMPTY = CapturedChatContext()
    }
}

data class ViewportCaptureOutcome(
    val screenshotJpegBase64: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val mergedMessages: List<String> = emptyList(),
    val redactionSession: RedactionSession = RedactionSession.EMPTY
)

/**
 * Pure geometric heuristic extractor, screenshot crop calculator, and privacy gatekeeper
 * for on-demand "AI 帮回" (`VoiceSubPanel.HELP_ME_WRITE`).
 *
 * Security & privacy guarantees:
 * 1. Never runs in background accessibility event streams; invoked strictly on-demand when
 *    the user taps `✍️` in a supported messaging app.
 * 2. Requires explicit user opt-in (`chatContextReplyEnabled && chatContextDisclosureAccepted`),
 *    non-incognito mode, non-password field, and exact package match (`activeWindowPackage == targetInputPackage`).
 * 3. Blocks banking, payment, password manager, and system settings packages unconditionally.
 * 4. Classifies message speaker (`[对方]` / `[我]` / `[未知]`) using horizontal bubble geometry.
 * 5. Escapes XML boundary tags inside untrusted chat messages to prevent prompt injection breakout.
 */
object ChatScreenContextExtractor {

    private val SENSITIVE_PACKAGE_EXACT_BLACKLIST = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.typefree.ime",
        // Payment & Banking
        "com.eg.android.AlipayGphone",
        "com.unionpay",
        "com.icbc",
        "com.chinamworld.main",
        "com.chinamworld.bocmbci",
        "com.android.bankabc",
        "cmb.pb",
        "com.cmbchina.ccd.pluto.cmbActivity",
        "com.pingan.paces.ccms",
        "com.paypal.android.p2pmobile",
        // Password Managers & Authenticators
        "com.x8bit.bitwarden",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "com.keepassdroid",
        "keepass2android.keepass2android",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator"
    )

    private val SENSITIVE_PACKAGE_KEYWORD_BLACKLIST = listOf(
        "bank",
        "alipay",
        "wallet",
        "pay",
        "keepass",
        "bitwarden",
        "1password",
        "onepassword",
        "authenticator",
        "keychain",
        "password"
    )

    private val DEFAULT_MESSAGING_PACKAGES = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.tencent.qqlite",
        "com.ss.android.lark",
        "com.larksuite.suite",
        "com.alibaba.android.rimet",
        "com.alibaba.aliding",
        "com.alibaba.work",
        "com.tencent.wework",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "tw.nekomimi.nekogram",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.Slack",
        "com.xingin.xhs",
        "com.discord",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging"
    )

    private val ENTERPRISE_IM_KEYWORDS = listOf(
        "dingtalk",
        "aliding",
        "rimet",
        "wework",
        "lark",
        "feishu"
    )

    private val MESSAGING_STYLE_IDS = setOf("casual_chat", "work_collab")

    private val UI_CHROME_NOISE_EXACT = setOf(
        "发送",
        "按住 说话",
        "按住说话",
        "松开 结束",
        "切换到语音",
        "切换到键盘",
        "表情",
        "动画表情",
        "贴纸",
        "查看原图",
        "双击查看",
        "更多功能按钮",
        "更多",
        "返回",
        "语音通话",
        "视频通话",
        "拍摄",
        "相册",
        "文件",
        "红包",
        "转账",
        "位置",
        "已读",
        "未读",
        "送达",
        "以下为新消息",
        "以下是新消息",
        "你撤回了一条消息",
        "对方撤回了一条消息",
        "以上是打招呼的内容",
        "对方正在输入...",
        "正在输入...",
        // Group chat role badges & generic accessibility labels
        "管理员",
        "[管理员]",
        "【管理员】",
        "群主",
        "[群主]",
        "【群主】",
        "群管理员",
        "活跃",
        "龙王",
        "学霸",
        "翰林",
        "潜水",
        "冒泡",
        "头像",
        "图片",
        "视频",
        "语音",
        "语音消息",
        "小程序",
        "个人名片",
        "群公告",
        "置顶",
        "Send",
        "Message",
        "Write a message...",
        "Type a message",
        "Hold to talk",
        "Unread Messages",
        "+"
    )

    private val PURE_TIME_OR_DATE_REGEX = Regex(
        """^(?:(?:昨天|今天|前天|星期[一二三四五六日天]|周[一二三四五六日天]|Yesterday|Today)\s*)?(?:上午|下午|凌晨|晚上)?\s*\d{1,2}:\d{2}(?::\d{2})?(?:\s*(?:AM|PM|am|pm))?$"""
    )

    private val FULL_DATE_HEADER_REGEX = Regex(
        """^(?:\d{4}[年/-])?\d{1,2}[月/-]\d{1,2}日?(?:\s+\d{1,2}:\d{2})?$"""
    )

    private val SENDER_AND_TIME_HEADER_REGEX = Regex(
        """^.{1,16}\s*[·•]\s*\d{1,2}:\d{2}$"""
    )

    private val UNREAD_BADGE_REGEX = Regex(
        """^(?:\d{1,4}|\d{1,3}\+|99\+|·)$"""
    )

    /**
     * Matches standalone property/community room number nicknames such as `2-102`, `3-602`, `12-1-301`, `5栋202`.
     */
    private val BUILDING_UNIT_NICKNAME_REGEX = Regex(
        """^\d{1,3}\s*[-—–栋号单元]\s*(?:\d{1,3}\s*[-—–单元]\s*)?\d{2,4}[A-Za-z]?$"""
    )

    /**
     * Matches top-bar group chat titles with member counts such as `未成年成长交流群(16)` or `金泰和序-【真】业主群(435)`.
     */
    private val GROUP_TITLE_MEMBER_COUNT_REGEX = Regex(
        """^.{1,30}[(（]\d{1,5}[)）]$"""
    )

    /**
     * Matches level/badge tags in QQ/forum groups such as `LV.12`, `Lv8`, `VIP3`.
     */
    private val LEVEL_BADGE_REGEX = Regex(
        """^(?:LV|Lv|lv|VIP|vip|SVIP|svip)\.?\s*\d{1,3}$"""
    )

    /**
     * Matches Mobile QQ / WeChat avatar accessibility labels such as `"玄冰是仓鼠 资料卡"`, `"张三的头像"`.
     */
    private val AVATAR_PROFILE_CARD_REGEX = Regex(
        """^(.{1,28}?)\s*(?:的)?(?:资料卡|个人资料卡|头像|个人主页|名片)$"""
    )

    /**
     * Matches DingTalk / WeWork enterprise group affiliation tags such as `"归属于 蚂蚁集团"`, `"内部群"`.
     */
    private val GROUP_AFFILIATION_HEADER_REGEX = Regex(
        """^(?:归属于\s*.+|内部群|外部群|全员群|部门群)$"""
    )

    /**
     * Matches bracketed or plain group role badges such as `"[管理员]"`, `"【群主】"`.
     */
    private val ROLE_BADGE_REGEX = Regex(
        """^[\[【(（]?\s*(?:管理员|群主|群管理员|活跃|龙王|学霸|翰林|潜水|冒泡|群机器人|机器人)\s*[\]】)）]?$"""
    )

    private val SPEAKER_PREFIX_REGEX = Regex("""^\s*\[(?:对方|我|未知)]\s*:?\s*""")
    private val NORMALIZE_PUNCT_SPACE_REGEX = Regex("""[\s\p{P}\p{S}]+""")

    private val TAG_ESCAPE_REGEX = Regex(
        """<(/?\s*(?:chat_context|writing_request|system|user|assistant|cursor_before_context|personal_dictionary|app_and_tone|jev|domain_profile|user_bottom_line|excluded_previous_replies|opt|subtext|lv|tag)\b[^>]*)>""",
        RegexOption.IGNORE_CASE
    )

    fun isSensitiveBlacklistedPackage(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return true
        if (pkg in SENSITIVE_PACKAGE_EXACT_BLACKLIST) return true
        val lower = pkg.lowercase()
        return SENSITIVE_PACKAGE_KEYWORD_BLACKLIST.any { keyword -> lower.contains(keyword) }
    }

    fun isSupportedMessagingApp(
        packageName: String,
        appRules: List<AppToneRule> = DefaultPresets.defaultAppRules()
    ): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty() || isSensitiveBlacklistedPackage(pkg)) return false
        if (pkg in DEFAULT_MESSAGING_PACKAGES) return true
        val lower = pkg.lowercase()
        if (ENTERPRISE_IM_KEYWORDS.any { lower.contains(it) }) return true
        val matchedRule = appRules.firstOrNull { it.packageName == pkg }
        return matchedRule != null && matchedRule.styleId in MESSAGING_STYLE_IDS
    }

    fun hasAnyVisiblePasswordNode(nodes: List<RawScreenTextNode>): Boolean {
        return nodes.any { it.isVisibleToUser && it.isPassword }
    }

    fun shouldPromptFirstTimeDisclosure(
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        targetInputPackage: String,
        hasVisiblePasswordNode: Boolean = false
    ): Boolean {
        if (settings.chatContextDisclosureAccepted || settings.chatContextDisclosureDismissed) return false
        if (isIncognitoMode || settings.manualIncognitoMode || isPasswordInput || hasVisiblePasswordNode) return false
        if (isSensitiveBlacklistedPackage(targetInputPackage)) return false
        return isSupportedMessagingApp(targetInputPackage, settings.appRules)
    }

    fun shouldAllowChatContextCapture(
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean,
        activeWindowPackage: String,
        targetInputPackage: String,
        hasVisiblePasswordNode: Boolean = false
    ): Boolean {
        if (!settings.chatContextReplyEnabled || !settings.chatContextDisclosureAccepted) return false
        if (isIncognitoMode || settings.manualIncognitoMode || isPasswordInput || hasVisiblePasswordNode || ignoreChatContextForTurn) return false
        val activePkg = activeWindowPackage.trim()
        val targetPkg = targetInputPackage.trim()
        if (activePkg.isEmpty() || targetPkg.isEmpty() || activePkg != targetPkg) return false
        if (isSensitiveBlacklistedPackage(targetPkg)) return false
        return isSupportedMessagingApp(targetPkg, settings.appRules)
    }

    /**
     * All-Scene Jev Copilot first-time disclosure gate: unlocked across all non-blacklisted apps
     * (IM, Xianyu/Commerce, BossZhipin/Career, Xiaohongshu/Social, Mail/Docs/Web) when `✦ Jev 军师`
     * is enabled and not in incognito/password state.
     */
    fun shouldPromptJevFirstTimeDisclosure(
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        targetInputPackage: String,
        hasVisiblePasswordNode: Boolean = false
    ): Boolean {
        if (!settings.jevCopilotEnabled) return false
        if (settings.chatContextDisclosureAccepted || settings.chatContextDisclosureDismissed) return false
        if (isIncognitoMode || settings.manualIncognitoMode || isPasswordInput || hasVisiblePasswordNode) return false
        val pkg = targetInputPackage.trim()
        if (pkg.isEmpty() || isSensitiveBlacklistedPackage(pkg)) return false
        return true
    }

    /**
     * All-Scene Jev Copilot context capture gate: allows on-demand screen reading in ANY non-blacklisted
     * host app once the user has accepted first-time disclosure, while enforcing hard circuit-breakers
     * on password inputs, visible password nodes anywhere on screen, incognito mode, and sensitive
     * finance/payment/authenticator/password-manager apps.
     */
    fun shouldAllowJevContextCapture(
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean,
        activeWindowPackage: String,
        targetInputPackage: String,
        hasVisiblePasswordNode: Boolean = false
    ): Boolean {
        if (!settings.jevCopilotEnabled) return false
        if (!settings.chatContextReplyEnabled || !settings.chatContextDisclosureAccepted) return false
        if (isIncognitoMode || settings.manualIncognitoMode || isPasswordInput || hasVisiblePasswordNode || ignoreChatContextForTurn) return false
        val activePkg = activeWindowPackage.trim()
        val targetPkg = targetInputPackage.trim()
        if (activePkg.isEmpty() || targetPkg.isEmpty() || activePkg != targetPkg) return false
        if (isSensitiveBlacklistedPackage(targetPkg)) return false
        return true
    }

    fun sanitizeChatContextTagEscape(text: String): String {
        if (text.isEmpty()) return ""
        return TAG_ESCAPE_REGEX.replace(text) { matchResult ->
            "＜${matchResult.groupValues[1]}＞"
        }
    }

    fun classifySpeaker(
        node: RawScreenTextNode,
        screenWidth: Int
    ): ChatSpeaker {
        if (screenWidth <= 0) return ChatSpeaker.UNKNOWN
        val left = node.boundsLeft.coerceAtLeast(0)
        val right = node.boundsRight.coerceAtMost(screenWidth)
        if (right <= left) return ChatSpeaker.UNKNOWN

        val centerX = (left + right) / 2.0
        val midX = screenWidth / 2.0
        val leftMargin = left.toDouble()
        val rightMargin = (screenWidth - right).toDouble()
        val width = (right - left).toDouble()

        // Full-width banner or symmetrically centered system hint -> UNKNOWN
        if (width >= screenWidth * 0.86) return ChatSpeaker.UNKNOWN
        if (abs(centerX - midX) <= screenWidth * 0.035 &&
            abs(leftMargin - rightMargin) <= screenWidth * 0.025
        ) {
            return ChatSpeaker.UNKNOWN
        }

        // Right-aligned bubble -> SELF ([我])
        if (centerX >= screenWidth * 0.525 ||
            (rightMargin + screenWidth * 0.05 < leftMargin && left >= screenWidth * 0.14)
        ) {
            return ChatSpeaker.SELF
        }

        // Left-aligned bubble -> OTHER ([对方])
        if (centerX <= screenWidth * 0.475 ||
            (leftMargin < rightMargin && right <= screenWidth * 0.88)
        ) {
            return ChatSpeaker.OTHER
        }

        return ChatSpeaker.UNKNOWN
    }

    fun isNoiseText(rawText: String): Boolean {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed in UI_CHROME_NOISE_EXACT) return true
        if (ROLE_BADGE_REGEX.matches(trimmed)) return true
        if (AVATAR_PROFILE_CARD_REGEX.matches(trimmed)) return true
        if (GROUP_AFFILIATION_HEADER_REGEX.matches(trimmed)) return true
        if (UNREAD_BADGE_REGEX.matches(trimmed)) return true
        if (PURE_TIME_OR_DATE_REGEX.matches(trimmed)) return true
        if (FULL_DATE_HEADER_REGEX.matches(trimmed)) return true
        if (SENDER_AND_TIME_HEADER_REGEX.matches(trimmed)) return true
        if (BUILDING_UNIT_NICKNAME_REGEX.matches(trimmed)) return true
        if (GROUP_TITLE_MEMBER_COUNT_REGEX.matches(trimmed)) return true
        if (LEVEL_BADGE_REGEX.matches(trimmed)) return true
        return false
    }

    fun isLikelyAvatarImageNode(
        node: RawScreenTextNode,
        screenWidth: Int = 1080
    ): Boolean {
        if (!node.className.contains("Image", ignoreCase = true)) return false
        val width = node.boundsRight - node.boundsLeft
        val height = node.boundsBottom - node.boundsTop
        if (width !in 24..180 || height !in 24..180 || abs(width - height) > 24) return false
        val edgeLimit = if (screenWidth > 0) (screenWidth * 0.22).toInt().coerceAtLeast(180) else 180
        return node.boundsLeft <= edgeLimit || (screenWidth > 0 && node.boundsRight >= screenWidth - edgeLimit)
    }

    internal fun normalizeMessageForDedup(msg: String): String {
        val withoutSpeaker = msg.replace(SPEAKER_PREFIX_REGEX, "").trim()
        return withoutSpeaker
            .lowercase()
            .replace(NORMALIZE_PUNCT_SPACE_REGEX, "")
    }

    fun parseFormattedChatMessage(
        formatted: String,
        fallbackTop: Int = 0
    ): ExtractedChatMessage {
        val trimmed = formatted.trim()
        return when {
            trimmed.startsWith(ChatSpeaker.OTHER.tag) -> ExtractedChatMessage(
                speaker = ChatSpeaker.OTHER,
                text = trimmed.removePrefix(ChatSpeaker.OTHER.tag).trim(),
                boundsTop = fallbackTop
            )
            trimmed.startsWith(ChatSpeaker.SELF.tag) -> ExtractedChatMessage(
                speaker = ChatSpeaker.SELF,
                text = trimmed.removePrefix(ChatSpeaker.SELF.tag).trim(),
                boundsTop = fallbackTop
            )
            trimmed.startsWith(ChatSpeaker.UNKNOWN.tag) -> ExtractedChatMessage(
                speaker = ChatSpeaker.UNKNOWN,
                text = trimmed.removePrefix(ChatSpeaker.UNKNOWN.tag).trim(),
                boundsTop = fallbackTop
            )
            else -> ExtractedChatMessage(
                speaker = ChatSpeaker.OTHER,
                text = trimmed,
                boundsTop = fallbackTop
            )
        }
    }

    /**
     * Merges and deduplicates messages extracted from the Accessibility tree ([a11yMessages])
     * with messages recognized by on-device viewport OCR ([ocrMessages]):
     * - When [a11yMessages] is empty (e.g. Mobile QQ NT / WeChat custom-canvas bubbles), directly
     *   uses [ocrMessages].
     * - When both are non-empty, deduplicates overlapping messages by normalized body text
     *   (stripping `[对方]`/`[我]` prefixes, whitespace, and punctuation) while appending
     *   custom-drawn bubbles or inline image text found only by OCR, keeping the last [maxMessages].
     */
    fun mergeAccessibilityAndOcrMessages(
        a11yMessages: List<String>,
        ocrMessages: List<String>,
        maxMessages: Int = 10
    ): List<String> {
        val limit = maxMessages.coerceAtLeast(1)
        val cleanA11y = a11yMessages.map { it.trim() }.filter { it.isNotEmpty() }
        val cleanOcr = ocrMessages.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanA11y.isEmpty()) {
            return cleanOcr.takeLast(limit)
        }
        if (cleanOcr.isEmpty()) {
            return cleanA11y.takeLast(limit)
        }

        val merged = ArrayList<String>(cleanA11y.size + cleanOcr.size)
        val normalizedMerged = ArrayList<String>(cleanA11y.size + cleanOcr.size)

        for (msg in cleanA11y) {
            val norm = normalizeMessageForDedup(msg)
            if (norm.isNotEmpty()) {
                merged.add(msg)
                normalizedMerged.add(norm)
            }
        }

        for (ocrMsg in cleanOcr) {
            val normOcr = normalizeMessageForDedup(ocrMsg)
            if (normOcr.isEmpty()) continue
            val existingIdx = normalizedMerged.indexOfFirst { normExisting ->
                normExisting == normOcr ||
                    (normOcr.length >= 2 && normExisting.contains(normOcr)) ||
                    (normExisting.length >= 2 && normOcr.contains(normExisting))
            }
            if (existingIdx >= 0) {
                // If OCR captured a significantly fuller line than a truncated accessibility node, upgrade it
                val existingNorm = normalizedMerged[existingIdx]
                if (normOcr.length > existingNorm.length + 4 && normOcr.contains(existingNorm)) {
                    merged[existingIdx] = ocrMsg
                    normalizedMerged[existingIdx] = normOcr
                }
            } else {
                merged.add(ocrMsg)
                normalizedMerged.add(normOcr)
            }
        }

        return merged.takeLast(limit)
    }

    const val MAX_SAFE_VIEWPORT_BOTTOM_RATIO = 0.76f

    /**
     * Computes the crop rectangle `[left, top, width, height]` of the chat message viewport
     * above the input box / soft keyboard, excluding the top status/app bar and the bottom keyboard.
     * Enforces [MAX_SAFE_VIEWPORT_BOTTOM_RATIO] so stale pre-`adjustResize` bottom coordinates
     * never accidentally include the soft keyboard itself in the screenshot.
     */
    fun computeChatRegionCropRect(
        bitmapWidth: Int,
        bitmapHeight: Int,
        inputBoxTop: Int = -1
    ): IntArray {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) {
            return intArrayOf(0, 0, 0, 0)
        }
        val cropTop = (bitmapHeight * 0.088f).toInt().coerceIn(0, bitmapHeight - 1)
        val defaultBottom = (bitmapHeight * 0.58f).toInt().coerceIn(cropTop + 1, bitmapHeight)
        val maxSafeBottom = (bitmapHeight * MAX_SAFE_VIEWPORT_BOTTOM_RATIO)
            .toInt()
            .coerceIn(defaultBottom, bitmapHeight)
        val cropBottom = if (inputBoxTop in (cropTop + 100)..maxSafeBottom) {
            inputBoxTop
        } else {
            defaultBottom
        }
        val cropHeight = (cropBottom - cropTop).coerceAtLeast(1)
        return intArrayOf(0, cropTop, bitmapWidth, cropHeight)
    }

    fun extractChatContext(
        nodes: List<RawScreenTextNode>,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400,
        inputBoxTop: Int = -1,
        packageName: String = "",
        appName: String = "",
        maxMessages: Int = 5,
        maxTotalChars: Int = 500,
        nowMs: Long = System.currentTimeMillis(),
        redactPii: Boolean = true,
        context: Context? = null
    ): CapturedChatContext {
        if (nodes.isEmpty()) {
            return CapturedChatContext(packageName = packageName, appName = appName, capturedAtMs = nowMs)
        }
        // Wall 2 Hard Circuit-Breaker: Any visible password node on screen or blacklisted app aborts immediately
        if (hasAnyVisiblePasswordNode(nodes) ||
            (packageName.isNotBlank() && isSensitiveBlacklistedPackage(packageName))
        ) {
            return CapturedChatContext(packageName = packageName, appName = appName, capturedAtMs = nowMs)
        }

        val topBarThreshold = if (screenHeight > 0) (screenHeight * 0.11).toInt() else 160
        val bottomFallbackThreshold = if (screenHeight > 0) (screenHeight * 0.89).toInt() else Int.MAX_VALUE
        val effectiveBottomLimit = if (inputBoxTop > topBarThreshold) {
            inputBoxTop - 4
        } else {
            bottomFallbackThreshold
        }

        // Pre-scan avatar profile cards ("玄冰是仓鼠 资料卡" -> "玄冰是仓鼠") and role badge Y positions
        // so standalone sender nickname headers in QQ NT / DingTalk are filtered even when custom Canvas
        // hides the bubble text node below them.
        val knownAvatarNicknames = HashSet<String>()
        val roleBadgeTops = ArrayList<Int>(8)
        for (node in nodes) {
            if (!node.isVisibleToUser) continue
            val t = node.text.replace(Regex("""\s+"""), " ").trim()
            if (t.isEmpty()) continue
            AVATAR_PROFILE_CARD_REGEX.matchEntire(t)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                knownAvatarNicknames.add(it)
            }
            if (ROLE_BADGE_REGEX.matches(t) || LEVEL_BADGE_REGEX.matches(t) || t in listOf("管理员", "群主", "群管理员")) {
                roleBadgeTops.add(node.boundsTop)
            }
        }

        val rawCandidates = nodes
            .asSequence()
            .filter { node ->
                node.isVisibleToUser &&
                    !node.isEditable &&
                    !node.isPassword &&
                    !isLikelyAvatarImageNode(node, screenWidth) &&
                    node.boundsBottom > topBarThreshold &&
                    node.boundsTop < effectiveBottomLimit &&
                    node.boundsBottom > node.boundsTop &&
                    node.boundsRight > node.boundsLeft
            }
            .mapNotNull { node ->
                val clean = node.text
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                val height = node.boundsBottom - node.boundsTop
                val isAvatarNickname = clean in knownAvatarNicknames && height <= 80
                val isSameRowAsRoleBadge = roleBadgeTops.any { abs(node.boundsTop - it) <= 24 } &&
                    clean.length <= 16 &&
                    height <= 64 &&
                    !clean.any { it in "，。？！：；,.?!" }
                if (isNoiseText(clean) || isAvatarNickname || isSameRowAsRoleBadge) {
                    null
                } else {
                    node.copy(text = sanitizeChatContextTagEscape(clean))
                }
            }
            .sortedBy { it.boundsTop }
            .toList()

        if (rawCandidates.isEmpty()) {
            val emptyNodeSession = if (redactPii) {
                LocalPiiRedactor.redactText("", context = context, sourceNodes = nodes)
            } else {
                RedactionSession.EMPTY
            }
            return CapturedChatContext(
                packageName = packageName,
                appName = appName,
                capturedAtMs = nowMs,
                redactionSession = emptyNodeSession
            )
        }

        // Filter out standalone sender nickname headers that sit immediately above a message bubble
        // (e.g. small height label `玩不起玩不起` 4..42px above the actual bubble `我找到了蓝猫台词的音频`)
        val filteredNodes = mutableListOf<RawScreenTextNode>()
        for (i in rawCandidates.indices) {
            val curr = rawCandidates[i]
            val next = rawCandidates.getOrNull(i + 1)
            val currHeight = curr.boundsBottom - curr.boundsTop
            if (next != null) {
                val nextHeight = next.boundsBottom - next.boundsTop
                val verticalGap = next.boundsTop - curr.boundsBottom
                val sameSide = abs(curr.boundsLeft - next.boundsLeft) <= (screenWidth * 0.25).toInt()
                val looksLikeShortNickname = curr.text.length <= 14 &&
                    !curr.text.any { it in "，。？！：；,.?!" } &&
                    currHeight <= 56 &&
                    (nextHeight >= (currHeight * 1.25).toInt() ||
                        (currHeight <= 48 && nextHeight >= currHeight && next.text.length > curr.text.length + 2))
                if (verticalGap in -6..42 && sameSide && looksLikeShortNickname) {
                    continue
                }
            }
            filteredNodes.add(curr)
        }

        val candidates = filteredNodes.map { node ->
            ExtractedChatMessage(
                speaker = classifySpeaker(node, screenWidth),
                text = node.text,
                boundsTop = node.boundsTop
            )
        }

        if (candidates.isEmpty()) {
            return CapturedChatContext(packageName = packageName, appName = appName, capturedAtMs = nowMs)
        }

        // Deduplicate adjacent identical or substring-overlapping nodes at almost identical Y positions
        val deduplicated = mutableListOf<ExtractedChatMessage>()
        for (msg in candidates) {
            val prev = deduplicated.lastOrNull()
            if (prev != null && abs(msg.boundsTop - prev.boundsTop) <= 24) {
                if (prev.text == msg.text || prev.text.contains(msg.text)) {
                    continue
                }
                if (msg.text.contains(prev.text)) {
                    deduplicated[deduplicated.lastIndex] = msg
                    continue
                }
            }
            deduplicated.add(msg)
        }

        // Take last `maxMessages` and enforce `maxTotalChars` budget from bottom (most recent) up
        val tailWindow = deduplicated.takeLast(maxMessages.coerceAtLeast(1))
        val selectedReversed = mutableListOf<ExtractedChatMessage>()
        var charCount = 0
        for (msg in tailWindow.asReversed()) {
            val clippedText = if (msg.text.length > 180) {
                msg.text.take(180) + "…"
            } else {
                msg.text
            }
            if (charCount + clippedText.length > maxTotalChars && selectedReversed.isNotEmpty()) {
                break
            }
            selectedReversed.add(msg.copy(text = clippedText))
            charCount += clippedText.length
        }

        val rawFinalMessages = selectedReversed.asReversed()
        val redactionSession = if (redactPii && rawFinalMessages.isNotEmpty()) {
            val joinedText = rawFinalMessages.joinToString("\n") { it.text }
            LocalPiiRedactor.redactText(
                rawText = joinedText,
                context = context,
                sourceNodes = nodes
            )
        } else if (redactPii) {
            LocalPiiRedactor.redactText("", context = context, sourceNodes = nodes)
        } else {
            RedactionSession.EMPTY
        }

        val finalMessages = if (redactPii && redactionSession.redactionCount > 0) {
            val redactedLines = redactionSession.redactedText.split("\n")
            rawFinalMessages.mapIndexed { idx, msg ->
                msg.copy(text = redactedLines.getOrElse(idx) { msg.text })
            }
        } else {
            rawFinalMessages
        }

        val previewTarget = finalMessages.lastOrNull { it.speaker == ChatSpeaker.OTHER }
            ?: finalMessages.lastOrNull()

        val previewText = if (previewTarget != null) {
            val body = if (previewTarget.text.length > 32) {
                "…" + previewTarget.text.takeLast(30)
            } else {
                previewTarget.text
            }
            "${previewTarget.speaker.tag} $body"
        } else {
            ""
        }

        return CapturedChatContext(
            packageName = packageName,
            appName = appName,
            messages = finalMessages,
            latestMessagePreview = previewText,
            capturedAtMs = nowMs,
            redactionSession = redactionSession
        )
    }
}
