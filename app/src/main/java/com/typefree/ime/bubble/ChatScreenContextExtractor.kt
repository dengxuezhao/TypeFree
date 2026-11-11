package com.typefree.ime.bubble

import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.AppToneRule
import com.typefree.ime.data.DefaultPresets
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
    val className: String = ""
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
    val capturedAtMs: Long = 0L
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
}

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
        "com.ss.android.lark",
        "com.larksuite.suite",
        "com.alibaba.android.rimet",
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

    private val MESSAGING_STYLE_IDS = setOf("casual_chat", "work_collab")

    private val UI_CHROME_NOISE_EXACT = setOf(
        "发送",
        "按住 说话",
        "按住说话",
        "松开 结束",
        "切换到语音",
        "切换到键盘",
        "表情",
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
        "群主",
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
        """^(?:LV|Lv|lv|VIP|vip)\.?\s*\d{1,3}$"""
    )

    private val TAG_ESCAPE_REGEX = Regex(
        """<(/?\s*(?:chat_context|writing_request|system|user|assistant|cursor_before_context|personal_dictionary|app_and_tone)\b[^>]*)>""",
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
        val matchedRule = appRules.firstOrNull { it.packageName == pkg }
        return matchedRule != null && matchedRule.styleId in MESSAGING_STYLE_IDS
    }

    fun shouldPromptFirstTimeDisclosure(
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        targetInputPackage: String
    ): Boolean {
        if (settings.chatContextDisclosureAccepted || settings.chatContextDisclosureDismissed) return false
        if (isIncognitoMode || isPasswordInput) return false
        if (isSensitiveBlacklistedPackage(targetInputPackage)) return false
        return isSupportedMessagingApp(targetInputPackage, settings.appRules)
    }

    fun shouldAllowChatContextCapture(
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean,
        activeWindowPackage: String,
        targetInputPackage: String
    ): Boolean {
        if (!settings.chatContextReplyEnabled || !settings.chatContextDisclosureAccepted) return false
        if (isIncognitoMode || isPasswordInput || ignoreChatContextForTurn) return false
        val activePkg = activeWindowPackage.trim()
        val targetPkg = targetInputPackage.trim()
        if (activePkg.isEmpty() || targetPkg.isEmpty() || activePkg != targetPkg) return false
        if (isSensitiveBlacklistedPackage(targetPkg)) return false
        return isSupportedMessagingApp(targetPkg, settings.appRules)
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
        if (UNREAD_BADGE_REGEX.matches(trimmed)) return true
        if (PURE_TIME_OR_DATE_REGEX.matches(trimmed)) return true
        if (FULL_DATE_HEADER_REGEX.matches(trimmed)) return true
        if (SENDER_AND_TIME_HEADER_REGEX.matches(trimmed)) return true
        if (BUILDING_UNIT_NICKNAME_REGEX.matches(trimmed)) return true
        if (GROUP_TITLE_MEMBER_COUNT_REGEX.matches(trimmed)) return true
        if (LEVEL_BADGE_REGEX.matches(trimmed)) return true
        return false
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
        nowMs: Long = System.currentTimeMillis()
    ): CapturedChatContext {
        if (nodes.isEmpty()) {
            return CapturedChatContext(packageName = packageName, appName = appName, capturedAtMs = nowMs)
        }

        val topBarThreshold = if (screenHeight > 0) (screenHeight * 0.11).toInt() else 160
        val bottomFallbackThreshold = if (screenHeight > 0) (screenHeight * 0.89).toInt() else Int.MAX_VALUE
        val effectiveBottomLimit = if (inputBoxTop > topBarThreshold) {
            inputBoxTop - 4
        } else {
            bottomFallbackThreshold
        }

        val rawCandidates = nodes
            .asSequence()
            .filter { node ->
                !node.isEditable &&
                    node.boundsBottom > topBarThreshold &&
                    node.boundsTop < effectiveBottomLimit &&
                    node.boundsBottom > node.boundsTop &&
                    node.boundsRight > node.boundsLeft
            }
            .mapNotNull { node ->
                val clean = node.text
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                if (isNoiseText(clean)) {
                    null
                } else {
                    node.copy(text = sanitizeChatContextTagEscape(clean))
                }
            }
            .sortedBy { it.boundsTop }
            .toList()

        if (rawCandidates.isEmpty()) {
            return CapturedChatContext(packageName = packageName, appName = appName, capturedAtMs = nowMs)
        }

        // Filter out standalone sender nickname headers that sit immediately above a message bubble
        // (e.g. small height label `玩不起玩不起` 4..36px above the actual bubble `我找到了蓝猫台词的音频`)
        val filteredNodes = mutableListOf<RawScreenTextNode>()
        for (i in rawCandidates.indices) {
            val curr = rawCandidates[i]
            val next = rawCandidates.getOrNull(i + 1)
            val currHeight = curr.boundsBottom - curr.boundsTop
            if (next != null) {
                val nextHeight = next.boundsBottom - next.boundsTop
                val verticalGap = next.boundsTop - curr.boundsBottom
                val sameSide = abs(curr.boundsLeft - next.boundsLeft) <= (screenWidth * 0.15).toInt()
                val looksLikeShortNickname = curr.text.length <= 14 &&
                    !curr.text.any { it in "，。？！：；,.?!" } &&
                    currHeight <= 56 &&
                    nextHeight >= (currHeight * 1.35).toInt()
                if (verticalGap in -6..36 && sameSide && looksLikeShortNickname) {
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

        val finalMessages = selectedReversed.asReversed()
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
            capturedAtMs = nowMs
        )
    }
}
