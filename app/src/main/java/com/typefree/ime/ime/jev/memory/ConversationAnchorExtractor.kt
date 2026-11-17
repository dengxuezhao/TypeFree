package com.typefree.ime.ime.jev.memory

import com.typefree.ime.bubble.RawScreenTextNode
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface AnchorSaltProvider {
    fun getOrCreateDeviceSalt(): ByteArray
    fun rotateDeviceSalt(): ByteArray
}

class InMemoryAnchorSaltProvider(
    initialSalt: ByteArray? = null
) : AnchorSaltProvider {
    private val lock = Any()
    private val random = SecureRandom()
    private var salt: ByteArray = initialSalt?.copyOf() ?: ByteArray(32).also { random.nextBytes(it) }

    override fun getOrCreateDeviceSalt(): ByteArray = synchronized(lock) {
        salt.copyOf()
    }

    override fun rotateDeviceSalt(): ByteArray = synchronized(lock) {
        salt = ByteArray(32).also { random.nextBytes(it) }
        salt.copyOf()
    }
}

object SaltedAnchorHasher {
    private const val HMAC_ALGO = "HmacSHA256"

    fun hmacSha256Hex(salt: ByteArray, input: String): String {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(salt, HMAC_ALGO))
        val digest = mac.doFinal(input.encodeToByteArray())
        val hexChars = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            hexChars[i * 2] = digits[v ushr 4]
            hexChars[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(hexChars)
    }

    fun buildAnchorInput(
        canonicalApp: String,
        topologyKind: TopologyKind,
        normalizedTitle: String,
        secondaryOrgHint: String? = null
    ): String {
        val cleanApp = canonicalApp.trim().lowercase(Locale.ROOT)
        val base = "$cleanApp::${topologyKind.name}::$normalizedTitle"
        return if (!secondaryOrgHint.isNullOrBlank()) {
            "$base::sub_$secondaryOrgHint"
        } else {
            base
        }
    }
}

/**
 * Unifies Android package names and macOS bundle identifiers into cross-platform canonical namespaces
 * so encrypted backups stay 100% compatible between mobile and desktop companions.
 */
object CanonicalAppResolver {
    fun resolve(packageOrBundleId: String, profileQualifier: String = ""): String {
        val clean = packageOrBundleId.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return "unknown"
        val base = when {
            clean == "com.tencent.mm" || clean == "com.tencent.xinwechat" -> "wechat"
            clean in setOf("com.tencent.mobileqq", "com.tencent.tim", "com.tencent.qqlite", "com.tencent.qq") -> "qq"
            clean in setOf("com.ss.android.lark", "com.larksuite.suite", "com.electron.lark") ||
                clean.contains("feishu") || clean.contains("lark") -> "feishu"
            clean in setOf("com.tencent.wework", "com.tencent.weworkmac") || clean.contains("wework") -> "wecom"
            clean in setOf("com.alibaba.android.rimet", "com.alibaba.aliding", "com.alibaba.work") ||
                clean.contains("dingtalk") -> "dingtalk"
            clean in setOf("org.telegram.messenger", "org.telegram.messenger.web", "tw.nekomimi.nekogram", "ru.keepcoder.telegram") -> "telegram"
            clean == "com.taobao.idlefish" || clean.contains("idlefish") -> "xianyu"
            clean == "com.hpbr.bosszhipin" || clean.contains("bosszhipin") -> "boss"
            clean == "com.xingin.xhs" -> "xhs"
            clean == "com.whatsapp" || clean == "com.whatsapp.w4b" -> "whatsapp"
            clean == "com.slack" || clean == "com.tinyspeck.slackmacgap" -> "slack"
            else -> "pkg:$clean"
        }
        return if (profileQualifier.isBlank()) base else "$base#${profileQualifier.trim()}"
    }
}

class VolatileWindowAnchorCache(
    private val clock: JevClock = JevSystemClock,
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    private data class Entry(
        val canonicalApp: String,
        val anchor: AnchorResolution.Resolved,
        val timestampMs: Long
    )

    private val lock = Any()
    private var lastEntry: Entry? = null

    fun put(canonicalApp: String, anchor: AnchorResolution.Resolved) = synchronized(lock) {
        lastEntry = Entry(
            canonicalApp = canonicalApp,
            anchor = anchor,
            timestampMs = clock.nowMs()
        )
    }

    fun getIfValid(canonicalApp: String): AnchorResolution.Resolved? = synchronized(lock) {
        val current = lastEntry ?: return null
        val elapsed = clock.nowMs() - current.timestampMs
        if (current.canonicalApp != canonicalApp || elapsed < 0L || elapsed > ttlMs) {
            if (current.canonicalApp != canonicalApp || elapsed > ttlMs) {
                lastEntry = null
            }
            return null
        }
        current.anchor.copy(fromPinOrCache = true)
    }

    fun clearIfAppChanged(canonicalApp: String) = synchronized(lock) {
        val current = lastEntry ?: return
        if (current.canonicalApp != canonicalApp) {
            lastEntry = null
        }
    }

    fun clear() = synchronized(lock) {
        lastEntry = null
    }

    companion object {
        const val DEFAULT_TTL_MS = 90_000L
    }
}

/**
 * Pins the resolved conversation anchor to the active Android `inputSessionToken` + `canonicalApp`
 * so transient typing banners (`对方正在输入...`), pinned group announcements, or mid-chat refreshes
 * within the same input box never lose the active conversation identity.
 */
class EditorSessionAnchorPin {
    private data class PinState(
        val sessionToken: Int,
        val canonicalApp: String,
        val anchor: AnchorResolution.Resolved
    )

    private val lock = Any()
    private var pinned: PinState? = null

    fun pin(sessionToken: Int, canonicalApp: String, anchor: AnchorResolution.Resolved) = synchronized(lock) {
        if (sessionToken < 0) return
        pinned = PinState(sessionToken = sessionToken, canonicalApp = canonicalApp, anchor = anchor)
    }

    fun getIfPinned(sessionToken: Int, canonicalApp: String): AnchorResolution.Resolved? = synchronized(lock) {
        if (sessionToken < 0) return null
        val current = pinned ?: return null
        if (current.sessionToken == sessionToken && current.canonicalApp == canonicalApp) {
            current.anchor.copy(fromPinOrCache = true)
        } else {
            null
        }
    }

    fun clear() = synchronized(lock) {
        pinned = null
    }
}

class ConversationAnchorExtractor(
    private val saltProvider: AnchorSaltProvider,
    private val clock: JevClock = JevSystemClock,
    val volatileCache: VolatileWindowAnchorCache = VolatileWindowAnchorCache(clock),
    val sessionPin: EditorSessionAnchorPin = EditorSessionAnchorPin()
) {

    private data class CandidateLine(
        val text: String,
        val boundsTop: Int,
        val boundsBottom: Int,
        val boundsLeft: Int,
        val boundsRight: Int
    ) {
        val height: Int get() = (boundsBottom - boundsTop).coerceAtLeast(1)
    }

    fun clearSessionPins() {
        sessionPin.clear()
        volatileCache.clear()
    }

    fun extractAnchor(
        nodes: List<RawScreenTextNode>,
        screenWidth: Int,
        screenHeight: Int,
        packageName: String,
        includeMaskedHint: Boolean = true,
        hintIsGroup: Boolean = false,
        hintGroupMemberCount: Int = 0,
        inputSessionToken: Int = -1
    ): AnchorResolution {
        val canonicalApp = CanonicalAppResolver.resolve(packageName)
        volatileCache.clearIfAppChanged(canonicalApp)

        if (inputSessionToken >= 0) {
            sessionPin.getIfPinned(inputSessionToken, canonicalApp)?.let { return it }
        }

        if (screenWidth <= 0 || screenHeight <= 0 || nodes.isEmpty()) {
            return AnchorResolution.EphemeralUnanchored(canonicalApp = canonicalApp)
        }

        val groupHeaderMaxTop = (screenHeight * 0.16f).toInt().coerceAtLeast(260)
        val statusBarBottomCut = (screenHeight * 0.042f).toInt()
        val statusBarSemanticMaxTop = (screenHeight * 0.055f).toInt()
        val leftBackRightCut = (screenWidth * 0.14f).toInt()
        val leftUnreadMaxLeft = (screenWidth * 0.18f).toInt()
        val rightMenuLeftCut = (screenWidth * 0.80f).toInt()

        var hasExternalBadge = false
        var hasGroupBadge = false
        var isTransientTypingDetected = false
        var subtitleMemberCount = 0

        val splitLines = ArrayList<CandidateLine>(12)

        for (node in nodes) {
            if (!node.isVisibleToUser || node.isPassword || node.isEditable) continue
            if (node.boundsTop > groupHeaderMaxTop) continue
            if (node.boundsBottom <= node.boundsTop || node.boundsRight <= node.boundsLeft) continue
            if (node.boundsBottom <= statusBarBottomCut) continue
            if (node.boundsRight <= leftBackRightCut) continue
            if (node.boundsLeft >= rightMenuLeftCut) continue

            val rawLines = node.text.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (rawLines.isEmpty()) continue

            val totalLines = rawLines.size
            val totalHeight = (node.boundsBottom - node.boundsTop).coerceAtLeast(1)

            rawLines.forEachIndexed { lineIdx, rawLine ->
                val lineTop = node.boundsTop + ((totalHeight * lineIdx) / totalLines.toFloat()).toInt()
                val lineBottom = (node.boundsTop + ((totalHeight * (lineIdx + 1)) / totalLines.toFloat()).toInt())
                    .coerceAtLeast(lineTop + 1)

                if (lineTop <= statusBarSemanticMaxTop && STATUS_BAR_NOISE_REGEX.matches(rawLine)) {
                    return@forEachIndexed
                }
                if (node.boundsLeft <= leftUnreadMaxLeft && LEFT_UNREAD_BADGE_REGEX.matches(rawLine)) {
                    return@forEachIndexed
                }

                var cleanedLine = cleanInlineGlyphs(rawLine)
                if (cleanedLine.isEmpty()) return@forEachIndexed

                if (cleanedLine.startsWith("@") && cleanedLine.length in 2..20) {
                    hasExternalBadge = true
                }

                if (BADGE_ONLY_REGEX.matches(cleanedLine)) {
                    if (EXTERNAL_BADGE_REGEX.matches(cleanedLine)) {
                        hasExternalBadge = true
                    }
                    if (GROUP_BADGE_REGEX.matches(cleanedLine)) {
                        hasGroupBadge = true
                    }
                    return@forEachIndexed
                }

                // Strip inline platform external markers like "陈总 @微信"
                val inlinePlatformMatch = INLINE_PLATFORM_BADGE_SUFFIX_REGEX.find(cleanedLine)
                if (inlinePlatformMatch != null) {
                    hasExternalBadge = true
                    cleanedLine = cleanedLine.removeRange(inlinePlatformMatch.range).trim()
                }
                if (cleanedLine.isEmpty()) return@forEachIndexed

                if (TRANSIENT_TYPING_REGEX.matches(cleanedLine)) {
                    isTransientTypingDetected = true
                    return@forEachIndexed
                }

                if (SUBTITLE_STATUS_REGEX.matches(cleanedLine)) {
                    val memberMatch = SUBTITLE_MEMBER_COUNT_REGEX.find(cleanedLine)
                    if (memberMatch != null) {
                        val parsedCount = memberMatch.groupValues[1].replace(",", "").toIntOrNull() ?: 0
                        if (parsedCount > subtitleMemberCount) {
                            subtitleMemberCount = parsedCount
                        }
                    }
                    return@forEachIndexed
                }

                splitLines.add(
                    CandidateLine(
                        text = cleanedLine,
                        boundsTop = lineTop,
                        boundsBottom = lineBottom,
                        boundsLeft = node.boundsLeft,
                        boundsRight = node.boundsRight
                    )
                )
            }
        }

        if (splitLines.isEmpty()) {
            if (isTransientTypingDetected) {
                volatileCache.getIfValid(canonicalApp)?.let { cached ->
                    if (inputSessionToken >= 0) {
                        sessionPin.pin(inputSessionToken, canonicalApp, cached)
                    }
                    return cached.copy(
                        hasExternalBadge = cached.hasExternalBadge || hasExternalBadge
                    )
                }
            }
            return AnchorResolution.EphemeralUnanchored(
                canonicalApp = canonicalApp,
                hasExternalBadge = hasExternalBadge
            )
        }

        val maxHeight = splitLines.maxOf { it.height }.coerceAtLeast(1).toDouble()
        val minTop = splitLines.minOf { it.boundsTop }.toDouble()
        val spanNorm = (0.12 * screenHeight).coerceAtLeast(1.0)
        val leftMin = 0.12 * screenWidth
        val leftMax = 0.65 * screenWidth

        val scored = splitLines.map { line ->
            val inStandardTitleX = if (line.boundsLeft.toDouble() in leftMin..leftMax) 1.0 else 0.0
            val score = 2.0 * (line.height.toDouble() / maxHeight) -
                1.5 * ((line.boundsTop.toDouble() - minTop) / spanNorm) +
                0.5 * inStandardTitleX
            line to score
        }

        val winnerPair = scored.maxByOrNull { it.second }!!
        val winner = winnerPair.first

        var rawTitle = winner.text
        var headerMemberCount: Int? = null
        val tailCountMatch = GROUP_COUNT_TAIL_REGEX.matchEntire(rawTitle)
        if (tailCountMatch != null) {
            val prefix = tailCountMatch.groupValues[1].trim()
            val count = tailCountMatch.groupValues[2].toIntOrNull() ?: 0
            if (prefix.isNotEmpty() && (count >= 3 || ((hintIsGroup || hasGroupBadge || subtitleMemberCount >= 2) && count >= 2))) {
                rawTitle = prefix
                headerMemberCount = count
            }
        }
        if (headerMemberCount == null && subtitleMemberCount >= 2) {
            headerMemberCount = subtitleMemberCount
        } else if (headerMemberCount == null && hintGroupMemberCount >= 2) {
            headerMemberCount = hintGroupMemberCount
        }

        val normalizedTitle = normalizeTitle(rawTitle)
        if (normalizedTitle == null) {
            if (isTransientTypingDetected) {
                volatileCache.getIfValid(canonicalApp)?.let { return it }
            }
            return AnchorResolution.EphemeralUnanchored(
                canonicalApp = canonicalApp,
                hasExternalBadge = hasExternalBadge
            )
        }

        // Secondary stable org/dept/store subtitle below primary title for disambiguation
        val secondaryOrgHint = splitLines
            .filter { it !== winner && it.boundsTop >= winner.boundsBottom - 8 }
            .sortedBy { it.boundsTop }
            .mapNotNull { normalizeTitle(it.text, maxCodePoints = 24) }
            .firstOrNull { sub ->
                sub.length in 2..24 &&
                    !sub.all { ch -> ch.isDigit() } &&
                    !TRANSIENT_TYPING_REGEX.matches(sub) &&
                    !SUBTITLE_STATUS_REGEX.matches(sub)
            }

        val isGroup = hintIsGroup ||
            hasGroupBadge ||
            (headerMemberCount ?: 0) >= 3 ||
            subtitleMemberCount >= 2
        val topologyKind = if (isGroup) TopologyKind.GRP else TopologyKind.PRIV
        val isAmbiguous = isAmbiguousGenericTitle(normalizedTitle)

        val salt = saltProvider.getOrCreateDeviceSalt()
        val primaryInput = SaltedAnchorHasher.buildAnchorInput(
            canonicalApp = canonicalApp,
            topologyKind = topologyKind,
            normalizedTitle = normalizedTitle,
            secondaryOrgHint = secondaryOrgHint
        )
        val primaryHash = SaltedAnchorHasher.hmacSha256Hex(salt, primaryInput)

        val unscopedHash = if (!secondaryOrgHint.isNullOrBlank()) {
            SaltedAnchorHasher.hmacSha256Hex(
                salt,
                SaltedAnchorHasher.buildAnchorInput(canonicalApp, topologyKind, normalizedTitle, null)
            )
        } else {
            null
        }

        val groupFallbackHash = if (topologyKind == TopologyKind.PRIV) {
            SaltedAnchorHasher.hmacSha256Hex(
                salt,
                SaltedAnchorHasher.buildAnchorInput(canonicalApp, TopologyKind.GRP, normalizedTitle, null)
            )
        } else {
            null
        }

        val maskedHint = if (includeMaskedHint) computeMaskedTitleHint(normalizedTitle) else null

        val resolved = AnchorResolution.Resolved(
            anchorHash = primaryHash,
            secondaryGroupFallbackHash = groupFallbackHash,
            fallbackUnscopedHash = unscopedHash,
            canonicalApp = canonicalApp,
            topologyKind = topologyKind,
            headerMemberCount = headerMemberCount,
            hasExternalBadge = hasExternalBadge,
            maskedTitleHint = maskedHint,
            isAmbiguousGeneric = isAmbiguous,
            fromPinOrCache = false
        )

        if (!isAmbiguous) {
            volatileCache.put(canonicalApp, resolved)
            if (inputSessionToken >= 0) {
                sessionPin.pin(inputSessionToken, canonicalApp, resolved)
            }
        }
        return resolved
    }

    companion object {
        private val ZERO_WIDTH_REGEX = Regex("""[\u200B-\u200F\uFEFF]""")
        private val PRIVATE_USE_ICON_REGEX = Regex("""[\uE000-\uF8FF]""")
        private val CONTROL_GLYPH_CODEPOINTS = setOf(
            0x1F442, // 👂
            0x1F507, // 🔇
            0x1F508, // 🔈
            0x1F509, // 🔉
            0x1F50A, // 🔊
            0x1F512, // 🔒
            0x1F514, // 🔔
            0x1F515, // 🔕
            0x1F4CC  // 📌
        )
        private val ACCESSIBILITY_AUDIO_TAG_REGEX = Regex("""(?:\[听筒]|\[静音]|听筒模式|免打扰)""")
        private val MULTI_SPACE_REGEX = Regex("""\s+""")
        private val PURE_PUNCT_OR_SPACE_REGEX = Regex("""^[\s\p{P}\p{S}]+$""")

        private val STATUS_BAR_NOISE_REGEX = Regex(
            """^(?:\d{1,2}:\d{2}(?:\s*[AP]M)?|\d{1,3}%|(?:5G|4G|3G|LTE|Wi-?Fi|VPN|HD|VoLTE|\d+(?:\.\d+)?\s*[KMG]B?/s)(?:\s+\d{1,3}%)?|中国(?:移动|联通|电信|广电))$""",
            RegexOption.IGNORE_CASE
        )

        private val LEFT_UNREAD_BADGE_REGEX = Regex(
            """^(?:返回|消息|微信|QQ|飞书)?\s*[(（]?\d{1,3}\+?[)）]?$"""
        )

        private val BADGE_ONLY_REGEX = Regex(
            """^(@微信|@企业微信|外部|外部群|全员|部门|官方)$"""
        )
        private val EXTERNAL_BADGE_REGEX = Regex("""^(@微信|@企业微信|外部|外部群)$""")
        private val GROUP_BADGE_REGEX = Regex("""^(外部群|全员|部门)$""")
        private val INLINE_PLATFORM_BADGE_SUFFIX_REGEX = Regex("""\s*@(?:微信|企业微信)$""")

        private val TRANSIENT_TYPING_REGEX = Regex(
            """^(?:对方)?正在(?:输入|说话|讲话|录音)中?(?:\.{0,3}|…)?$|^.+is typing(?:\.{0,3}|…)?$|^typing(?:\.{0,3}|…)?$""",
            RegexOption.IGNORE_CASE
        )

        private val SUBTITLE_STATUS_REGEX = Regex(
            """^(?:在线|离线|刚刚在线|手机在线.*|电脑在线.*|WiFi在线|[45]G在线|\d+\s*(?:分钟|小时|天)前(?:在线|来过)|online|last seen.*|\d[\d,]*\s*(?:人|位成员|人在线|members).*)$""",
            RegexOption.IGNORE_CASE
        )

        private val SUBTITLE_MEMBER_COUNT_REGEX = Regex(
            """(\d[\d,]*)\s*(?:人|位成员|members)""",
            RegexOption.IGNORE_CASE
        )

        private val GROUP_COUNT_TAIL_REGEX = Regex(
            """^(.*\S)\s*[(（](\d{1,5})[)）]$"""
        )

        private val GENERIC_AMBIGUOUS_TITLES = setOf(
            "momo",
            "微信用户",
            "江湖人",
            "客服",
            "官方客服",
            "在线客服",
            "客服小助手",
            "人工客服",
            "文件传输助手",
            "群聊",
            "未命名",
            "未知联系人"
        )
        private val GENERIC_AMBIGUOUS_REGEX = Regex(
            """^(?:用户|手机用户|微信用户|momo|匿名用户)[\s_-]*\d{0,6}$""",
            RegexOption.IGNORE_CASE
        )

        fun cleanInlineGlyphs(raw: String): String {
            val noIcons = buildString(raw.length) {
                var i = 0
                while (i < raw.length) {
                    val cp = raw.codePointAt(i)
                    val charCount = Character.charCount(cp)
                    val segment = raw.substring(i, i + charCount)
                    if (cp in CONTROL_GLYPH_CODEPOINTS) {
                        // skip ear / mute / bell-slash / lock / pushpin emojis
                    } else {
                        append(segment)
                    }
                    i += charCount
                }
            }
            return noIcons
                .replace(PRIVATE_USE_ICON_REGEX, "")
                .replace(ACCESSIBILITY_AUDIO_TAG_REGEX, "")
                .replace(ZERO_WIDTH_REGEX, "")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
        }

        fun normalizeTitle(raw: String, maxCodePoints: Int = 64): String? {
            if (raw.isBlank()) return null
            val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            val collapsed = nfkc
                .replace(ZERO_WIDTH_REGEX, "")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
            if (collapsed.isEmpty() || PURE_PUNCT_OR_SPACE_REGEX.matches(collapsed)) {
                return null
            }
            val cpCount = collapsed.codePointCount(0, collapsed.length)
            return if (cpCount <= maxCodePoints) {
                collapsed
            } else {
                val endOffset = collapsed.offsetByCodePoints(0, maxCodePoints)
                collapsed.substring(0, endOffset).trim().ifEmpty { null }
            }
        }

        fun computeMaskedTitleHint(normalizedTitle: String): String {
            val cpCount = normalizedTitle.codePointCount(0, normalizedTitle.length)
            if (cpCount <= 1) return "*"
            val firstEnd = normalizedTitle.offsetByCodePoints(0, 1)
            val first = normalizedTitle.substring(0, firstEnd)
            if (cpCount == 2) return "$first*"
            val lastStart = normalizedTitle.offsetByCodePoints(normalizedTitle.length, -1)
            val last = normalizedTitle.substring(lastStart)
            return "$first*$last"
        }

        fun isAmbiguousGenericTitle(normalizedTitle: String): Boolean {
            val lower = normalizedTitle.trim().lowercase(Locale.ROOT)
            return lower in GENERIC_AMBIGUOUS_TITLES || GENERIC_AMBIGUOUS_REGEX.matches(lower)
        }
    }
}
