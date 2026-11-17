package com.typefree.ime.ime.jev.memory

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.typefree.ime.bubble.ChatScreenContextExtractor
import com.typefree.ime.ime.jev.ChatTopology
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class JevMemorySnapshot(
    val sessions: List<SessionMemoryRecord> = emptyList(),
    val aliases: List<AnchorAliasRecord> = emptyList(),
    val buckets: List<BucketStyleProfile> = emptyList()
)

interface JevMemoryBackend {
    fun loadAllSnapshot(): JevMemorySnapshot
    fun upsertSession(record: SessionMemoryRecord)
    fun upsertAlias(alias: AnchorAliasRecord)
    fun upsertBucket(profile: BucketStyleProfile)
    fun deleteSessionCascade(sessionUid: String)
    fun pruneSessionsExcept(keepSessionUids: Set<String>)
    fun wipeAll()
}

class InMemoryJevMemoryBackend : JevMemoryBackend {
    private val lock = Any()
    private val sessions = LinkedHashMap<String, SessionMemoryRecord>()
    private val aliases = LinkedHashMap<String, AnchorAliasRecord>()
    private val buckets = LinkedHashMap<String, BucketStyleProfile>()

    override fun loadAllSnapshot(): JevMemorySnapshot = synchronized(lock) {
        JevMemorySnapshot(
            sessions = sessions.values.toList(),
            aliases = aliases.values.toList(),
            buckets = buckets.values.toList()
        )
    }

    override fun upsertSession(record: SessionMemoryRecord) = synchronized(lock) {
        sessions[record.sessionUid] = record
    }

    override fun upsertAlias(alias: AnchorAliasRecord) = synchronized(lock) {
        aliases[alias.anchorHash] = alias
    }

    override fun upsertBucket(profile: BucketStyleProfile) = synchronized(lock) {
        buckets[profile.bucketKey] = profile
    }

    override fun deleteSessionCascade(sessionUid: String) = synchronized(lock) {
        sessions.remove(sessionUid)
        val iter = aliases.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value.sessionUid == sessionUid) {
                iter.remove()
            }
        }
    }

    override fun pruneSessionsExcept(keepSessionUids: Set<String>) = synchronized(lock) {
        val sIter = sessions.keys.iterator()
        while (sIter.hasNext()) {
            if (sIter.next() !in keepSessionUids) {
                sIter.remove()
            }
        }
        val aIter = aliases.entries.iterator()
        while (aIter.hasNext()) {
            if (aIter.next().value.sessionUid !in keepSessionUids) {
                aIter.remove()
            }
        }
    }

    override fun wipeAll() = synchronized(lock) {
        sessions.clear()
        aliases.clear()
        buckets.clear()
    }
}

/**
 * Volatile in-memory LRU tracker of recent conversation tail bubble 64-bit FNV-1a fingerprints
 * (`maxEntries = 12`). Never persisted to disk; cleared on screen lock / incognito / wipe.
 * Enables seamless re-linking when a user edits a contact remark name in WeChat/QQ/Feishu.
 */
class RecentBubbleContinuityTracker(
    private val maxEntries: Int = 12
) {
    private data class TrackedBubbles(
        val sessionUid: String,
        val canonicalApp: String,
        val topologyKind: TopologyKind,
        val fingerprints: List<Long>
    )

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, TrackedBubbles>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackedBubbles>?): Boolean {
            return size > maxEntries
        }
    }

    fun recordSessionBubbles(
        sessionUid: String,
        canonicalApp: String,
        topologyKind: TopologyKind,
        formattedMessages: List<String>
    ) {
        val fps = computeTailFingerprints(formattedMessages)
        if (fps.isEmpty()) return
        synchronized(lock) {
            entries[sessionUid] = TrackedBubbles(
                sessionUid = sessionUid,
                canonicalApp = canonicalApp,
                topologyKind = topologyKind,
                fingerprints = fps
            )
        }
    }

    fun findMatchingSessionUid(
        canonicalApp: String,
        topologyKind: TopologyKind,
        formattedMessages: List<String>
    ): String? {
        val currentFps = computeTailFingerprints(formattedMessages)
        if (currentFps.size < 2) return null
        val currentSet = currentFps.toSet()
        synchronized(lock) {
            var bestUid: String? = null
            var bestOverlap = 0
            // Iterate most recent last -> latest match wins on ties
            for (entry in entries.values) {
                if (entry.canonicalApp != canonicalApp || entry.topologyKind != topologyKind) continue
                val overlap = entry.fingerprints.count { it in currentSet }
                if (overlap >= 2 && overlap >= bestOverlap) {
                    bestOverlap = overlap
                    bestUid = entry.sessionUid
                }
            }
            return bestUid
        }
    }

    fun removeSession(sessionUid: String) = synchronized(lock) {
        entries.remove(sessionUid)
        Unit
    }

    fun clear() = synchronized(lock) {
        entries.clear()
    }

    companion object {
        private val BODY_STRIP_PREFIX_REGEX = Regex("""^\s*\[(?:对方|我|未知|群友[A-Z](?:\([^\n]{1,32}\))?)]\s*:?\s*""")
        private val BODY_PUNCT_SPACE_REGEX = Regex("""[\s\p{P}\p{S}]+""")

        fun computeTailFingerprints(formattedMessages: List<String>): List<Long> {
            return formattedMessages
                .takeLast(3)
                .mapNotNull { line ->
                    val norm = line
                        .replace(BODY_STRIP_PREFIX_REGEX, "")
                        .lowercase()
                        .replace(BODY_PUNCT_SPACE_REGEX, "")
                    if (norm.length < 2) null else fnv1a64(norm)
                }
        }

        private fun fnv1a64(text: String): Long {
            var hash = -0x340d631b7bdddcdbL // 14695981039346656037UL
            val prime = 0x100000001b3L
            for (ch in text) {
                hash = hash xor (ch.code.toLong() and 0xFFFFL)
                hash *= prime
            }
            return hash
        }
    }
}

data class ResolvedJevMemoryContext(
    val anchor: AnchorResolution,
    val session: SessionMemoryRecord?,
    val bucketProfile: BucketStyleProfile?,
    val globalProfile: BucketStyleProfile?,
    val promptPayload: JevMemoryPromptPayload,
    val correctedTopology: ChatTopology,
    val recentReusableSessions: List<SessionMemoryRecord>,
    val autoRelinkedAlias: Boolean = false
)

data class EncryptedJevMemoryArchive(
    val version: Int = 1,
    val kdfIterations: Int = 120_000,
    val kdfSaltB64: String,
    val ivB64: String,
    val cipherTextB64: String
)

data class JevMemoryPlainBackupPayload(
    val schemaVersion: Int = 1,
    val exportedAtMs: Long,
    val deviceSaltBase64: String,
    val sessions: List<SessionMemoryRecord>,
    val aliases: List<AnchorAliasRecord>,
    val buckets: List<BucketStyleProfile>
)

data class JevMemoryImportSummary(
    val importedSessions: Int,
    val importedAliases: Int,
    val mergedBuckets: Int,
    val adoptedDeviceSalt: Boolean
)

object JevMemoryBackupCrypto {
    private const val KDF_ITERATIONS = 120_000
    private const val GCM_TAG_BITS = 128
    private val gson: Gson = GsonBuilder().create()

    fun encryptPayload(
        payload: JevMemoryPlainBackupPayload,
        passphrase: CharArray,
        wipePassphraseAfterUse: Boolean = true
    ): EncryptedJevMemoryArchive {
        require(passphrase.isNotEmpty()) { "备份密码不能为空" }
        val rng = SecureRandom()
        val kdfSalt = ByteArray(16).also { rng.nextBytes(it) }
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        return try {
            val key = deriveAesKey(passphrase, kdfSalt, KDF_ITERATIONS)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plainBytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
            val cipherBytes = cipher.doFinal(plainBytes)
            val b64 = Base64.getEncoder()
            EncryptedJevMemoryArchive(
                version = 1,
                kdfIterations = KDF_ITERATIONS,
                kdfSaltB64 = b64.encodeToString(kdfSalt),
                ivB64 = b64.encodeToString(iv),
                cipherTextB64 = b64.encodeToString(cipherBytes)
            )
        } finally {
            if (wipePassphraseAfterUse) {
                Arrays.fill(passphrase, '\u0000')
            }
        }
    }

    fun decryptPayload(
        archive: EncryptedJevMemoryArchive,
        passphrase: CharArray,
        wipePassphraseAfterUse: Boolean = true
    ): Result<JevMemoryPlainBackupPayload> {
        if (passphrase.isEmpty()) {
            return Result.failure(IllegalArgumentException("导入军师加密记忆需要输入备份密码"))
        }
        return try {
            val dec = Base64.getDecoder()
            val kdfSalt = dec.decode(archive.kdfSaltB64)
            val iv = dec.decode(archive.ivB64)
            val ct = dec.decode(archive.cipherTextB64)
            val iters = archive.kdfIterations.coerceIn(10_000, 600_000)
            val key = deriveAesKey(passphrase, kdfSalt, iters)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plainJson = String(cipher.doFinal(ct), Charsets.UTF_8)
            val parsed = gson.fromJson(plainJson, JevMemoryPlainBackupPayload::class.java)
                ?: return Result.failure(IllegalStateException("军师记忆载荷为空"))
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("军师记忆解密失败（密码错误或数据已损坏）：${e.message ?: "校验失败"}"))
        } finally {
            if (wipePassphraseAfterUse) {
                Arrays.fill(passphrase, '\u0000')
            }
        }
    }

    private fun deriveAesKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }
}

class JevMemoryRepository(
    private val backend: JevMemoryBackend,
    private val saltProvider: AnchorSaltProvider,
    private val clock: JevClock = JevSystemClock,
    private val writeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "JevMemoryWriteQueue").apply { isDaemon = true }
    }
) {
    private val sessionsMap = ConcurrentHashMap<String, SessionMemoryRecord>()
    private val aliasesMap = ConcurrentHashMap<String, AnchorAliasRecord>()
    private val bucketsMap = ConcurrentHashMap<String, BucketStyleProfile>()

    @Volatile
    private var hydrated = false
    private val hydrateLock = Any()

    @Volatile
    private var lastMaintenanceMs = 0L

    val bubbleContinuityTracker = RecentBubbleContinuityTracker(maxEntries = 12)

    fun warmUpAsync() {
        writeExecutor.execute {
            ensureHydrated()
        }
    }

    fun ensureHydrated() {
        if (hydrated) return
        synchronized(hydrateLock) {
            if (hydrated) return
            val snap = runCatching { backend.loadAllSnapshot() }.getOrDefault(JevMemorySnapshot())
            sessionsMap.clear()
            aliasesMap.clear()
            bucketsMap.clear()
            snap.sessions.forEach { sessionsMap[it.sessionUid] = it }
            snap.aliases.forEach { aliasesMap[it.anchorHash] = it }
            snap.buckets.forEach { bucketsMap[it.bucketKey] = it }
            hydrated = true
        }
    }

    fun resolveContext(
        anchor: AnchorResolution,
        topology: ChatTopology,
        recentFormattedMessages: List<String> = emptyList(),
        ephemeralPrivateTag: PrivateRelationTag = PrivateRelationTag.UNSET,
        ephemeralGroupTag: GroupSceneTag = GroupSceneTag.UNSET,
        ephemeralCustomNote: String? = null
    ): ResolvedJevMemoryContext {
        ensureHydrated()
        val nowMs = clock.nowMs()
        var session: SessionMemoryRecord? = null
        var autoRelinked = false

        if (anchor is AnchorResolution.Resolved && !anchor.isAmbiguousGeneric) {
            var matchedUid = aliasesMap[anchor.anchorHash]?.sessionUid
            if (matchedUid == null && anchor.fallbackUnscopedHash != null) {
                matchedUid = aliasesMap[anchor.fallbackUnscopedHash]?.sessionUid
            }
            if (matchedUid == null && anchor.secondaryGroupFallbackHash != null) {
                val fallbackUid = aliasesMap[anchor.secondaryGroupFallbackHash]?.sessionUid
                val fallbackSess = fallbackUid?.let { sessionsMap[it] }
                if (fallbackSess?.confirmedIsGroup == true) {
                    matchedUid = fallbackUid
                }
            }
            if (matchedUid != null) {
                session = sessionsMap[matchedUid]
                if (session != null && nowMs - session.lastActiveAtMs >= TOUCH_RENEW_THROTTLE_MS) {
                    val updated = session.copy(lastActiveAtMs = nowMs)
                    sessionsMap[matchedUid] = updated
                    enqueueWrite { backend.upsertSession(updated) }
                    session = updated
                }
            } else if (recentFormattedMessages.isNotEmpty()) {
                val relinkedUid = bubbleContinuityTracker.findMatchingSessionUid(
                    canonicalApp = anchor.canonicalApp,
                    topologyKind = anchor.topologyKind,
                    formattedMessages = recentFormattedMessages
                )
                if (relinkedUid != null && sessionsMap.containsKey(relinkedUid)) {
                    val newAlias = AnchorAliasRecord(
                        anchorHash = anchor.anchorHash,
                        sessionUid = relinkedUid,
                        boundAtMs = nowMs
                    )
                    aliasesMap[anchor.anchorHash] = newAlias
                    enqueueWrite { backend.upsertAlias(newAlias) }
                    session = sessionsMap[relinkedUid]?.copy(lastActiveAtMs = nowMs)?.also { updated ->
                        sessionsMap[relinkedUid] = updated
                        enqueueWrite { backend.upsertSession(updated) }
                    }
                    autoRelinked = true
                }
            }

            if (session != null && recentFormattedMessages.isNotEmpty()) {
                bubbleContinuityTracker.recordSessionBubbles(
                    sessionUid = session.sessionUid,
                    canonicalApp = session.canonicalApp,
                    topologyKind = session.topologyKind,
                    formattedMessages = recentFormattedMessages
                )
            }
        }

        val correctedTopology = if (session?.confirmedIsGroup == true && !topology.isGroupChat) {
            topology.copy(
                isGroupChat = true,
                roleMode = ChatScreenContextExtractor.resolveRoleMode(
                    isGroupChat = true,
                    distinctOtherSendersCount = topology.distinctOtherSenders.size,
                    recentSelfTurns = topology.recentSelfTurns,
                    hasExplicitMentionMe = topology.hasExplicitMentionMe
                )
            )
        } else {
            topology
        }

        val effectiveKind = if (correctedTopology.isGroupChat) TopologyKind.GRP else TopologyKind.PRIV
        val effPriv = session?.privateTag?.takeIf { it != PrivateRelationTag.UNSET } ?: ephemeralPrivateTag
        val effGrp = session?.groupTag?.takeIf { it != GroupSceneTag.UNSET } ?: ephemeralGroupTag
        val bucketKey = RelationPragmaticMapper.toBucketKey(effectiveKind, effPriv, effGrp)
        val bucketProfile = bucketKey?.let { bucketsMap[it] }
        val globalProfile = bucketsMap[RelationPragmaticMapper.GLOBAL_BUCKET_KEY]

        val payload = RelationPragmaticMapper.buildPromptPayload(
            topology = correctedTopology,
            anchor = anchor,
            session = session,
            ephemeralPrivateTag = ephemeralPrivateTag,
            ephemeralGroupTag = ephemeralGroupTag,
            ephemeralCustomNote = ephemeralCustomNote,
            bucketProfile = bucketProfile,
            globalProfile = globalProfile,
            nowMs = nowMs
        )

        val reusable = if (session == null && anchor is AnchorResolution.Resolved && !anchor.isAmbiguousGeneric) {
            sessionsMap.values
                .asSequence()
                .filter {
                    it.canonicalApp == anchor.canonicalApp &&
                        it.topologyKind == anchor.topologyKind &&
                        (it.privateTag != PrivateRelationTag.UNSET ||
                            it.groupTag != GroupSceneTag.UNSET ||
                            !it.redactedCustomNote.isNullOrBlank())
                }
                .sortedByDescending { it.lastActiveAtMs }
                .take(3)
                .toList()
        } else {
            emptyList()
        }

        return ResolvedJevMemoryContext(
            anchor = anchor,
            session = session,
            bucketProfile = bucketProfile,
            globalProfile = globalProfile,
            promptPayload = payload,
            correctedTopology = correctedTopology,
            recentReusableSessions = reusable,
            autoRelinkedAlias = autoRelinked
        )
    }

    fun recordBubblesForActiveSession(
        sessionUid: String?,
        canonicalApp: String,
        topologyKind: TopologyKind,
        formattedMessages: List<String>
    ) {
        if (sessionUid.isNullOrBlank() || formattedMessages.isEmpty()) return
        bubbleContinuityTracker.recordSessionBubbles(
            sessionUid = sessionUid,
            canonicalApp = canonicalApp,
            topologyKind = topologyKind,
            formattedMessages = formattedMessages
        )
    }

    fun saveExplicitRelation(
        anchor: AnchorResolution,
        topology: ChatTopology,
        privateTag: PrivateRelationTag,
        groupTag: GroupSceneTag,
        redactedCustomNote: String?,
        includeMaskedHint: Boolean = true
    ): SessionMemoryRecord? {
        ensureHydrated()
        if (anchor !is AnchorResolution.Resolved || anchor.isAmbiguousGeneric) {
            return null
        }
        val nowMs = clock.nowMs()
        val existingUid = findUidForAnchor(anchor)
        val existing = existingUid?.let { sessionsMap[it] }
        val uid = existing?.sessionUid ?: "s_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val cleanNote = redactedCustomNote?.trim()?.ifEmpty { null }
        val isGroup = topology.isGroupChat || anchor.topologyKind == TopologyKind.GRP || groupTag != GroupSceneTag.UNSET
        val maskedHint = if (includeMaskedHint) {
            anchor.maskedTitleHint ?: existing?.maskedTitleHint
        } else {
            null
        }
        val pinned = (existing?.isPinned == true) || cleanNote != null

        val record = (existing ?: SessionMemoryRecord(
            sessionUid = uid,
            canonicalApp = anchor.canonicalApp,
            topologyKind = if (isGroup) TopologyKind.GRP else TopologyKind.PRIV,
            createdAtMs = nowMs,
            lastActiveAtMs = nowMs
        )).copy(
            confirmedIsGroup = existing?.confirmedIsGroup == true || isGroup,
            privateTag = if (isGroup) existing?.privateTag ?: PrivateRelationTag.UNSET else privateTag,
            groupTag = if (isGroup) groupTag else existing?.groupTag ?: GroupSceneTag.UNSET,
            redactedCustomNote = cleanNote,
            maskedTitleHint = maskedHint,
            isPinned = pinned,
            lastActiveAtMs = nowMs
        )
        sessionsMap[uid] = record

        val alias = AnchorAliasRecord(anchorHash = anchor.anchorHash, sessionUid = uid, boundAtMs = nowMs)
        aliasesMap[anchor.anchorHash] = alias

        enqueueWrite {
            backend.upsertSession(record)
            backend.upsertAlias(alias)
        }
        maybeRunMaintenance()
        return record
    }

    fun rebindAnchorToExistingSession(
        anchor: AnchorResolution,
        targetSessionUid: String
    ): SessionMemoryRecord? {
        ensureHydrated()
        if (anchor !is AnchorResolution.Resolved || anchor.isAmbiguousGeneric) return null
        val target = sessionsMap[targetSessionUid] ?: return null
        val nowMs = clock.nowMs()
        val alias = AnchorAliasRecord(
            anchorHash = anchor.anchorHash,
            sessionUid = targetSessionUid,
            boundAtMs = nowMs
        )
        aliasesMap[anchor.anchorHash] = alias
        val updated = target.copy(lastActiveAtMs = nowMs)
        sessionsMap[targetSessionUid] = updated
        enqueueWrite {
            backend.upsertAlias(alias)
            backend.upsertSession(updated)
        }
        return updated
    }

    fun recordFeedbackLearning(
        anchor: AnchorResolution,
        topology: ChatTopology,
        ephemeralPrivateTag: PrivateRelationTag,
        ephemeralGroupTag: GroupSceneTag,
        adoptedAxisGroup: JevAxisGroup?,
        adoptedRawIndex: Int?,
        wasTopPinnedSlot: Boolean,
        featureObservations: List<FeatureObservation>,
        includeMaskedHint: Boolean = true
    ) {
        ensureHydrated()
        val nowMs = clock.nowMs()
        val canPersistSession = anchor is AnchorResolution.Resolved && !anchor.isAmbiguousGeneric

        var targetSession: SessionMemoryRecord? = null
        if (canPersistSession) {
            val resolved = anchor as AnchorResolution.Resolved
            val existingUid = findUidForAnchor(resolved)
            val uid = existingUid ?: "s_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val existing = sessionsMap[uid]
            val isGroup = existing?.confirmedIsGroup == true || topology.isGroupChat || resolved.topologyKind == TopologyKind.GRP
            var nextAxisStats = existing?.axisStats ?: emptyMap()
            if (adoptedAxisGroup != null && adoptedRawIndex != null && adoptedRawIndex in 0..2) {
                val updatedAxis = JevPreferenceLearner.updateAxisStats(
                    current = nextAxisStats[adoptedAxisGroup],
                    adoptedIndex = adoptedRawIndex,
                    wasTopPinnedSlot = wasTopPinnedSlot,
                    nowMs = nowMs
                )
                nextAxisStats = nextAxisStats.toMutableMap().apply { put(adoptedAxisGroup, updatedAxis) }
            }
            val nextFeatures = if (featureObservations.isNotEmpty()) {
                JevPreferenceLearner.updateFeatureCounters(
                    current = existing?.featureCounters ?: emptyMap(),
                    observations = featureObservations,
                    nowMs = nowMs
                )
            } else {
                existing?.featureCounters ?: emptyMap()
            }

            val updatedRecord = (existing ?: SessionMemoryRecord(
                sessionUid = uid,
                canonicalApp = resolved.canonicalApp,
                topologyKind = if (isGroup) TopologyKind.GRP else TopologyKind.PRIV,
                privateTag = ephemeralPrivateTag,
                groupTag = ephemeralGroupTag,
                maskedTitleHint = if (includeMaskedHint) resolved.maskedTitleHint else null,
                createdAtMs = nowMs,
                lastActiveAtMs = nowMs
            )).copy(
                confirmedIsGroup = isGroup,
                axisStats = nextAxisStats,
                featureCounters = nextFeatures,
                interactionCount = (existing?.interactionCount ?: 0) + 1,
                lastActiveAtMs = nowMs
            )
            sessionsMap[uid] = updatedRecord
            targetSession = updatedRecord

            val alias = if (existingUid == null) {
                AnchorAliasRecord(resolved.anchorHash, uid, nowMs).also { aliasesMap[resolved.anchorHash] = it }
            } else {
                null
            }
            enqueueWrite {
                backend.upsertSession(updatedRecord)
                if (alias != null) backend.upsertAlias(alias)
            }
        }

        // Update relation bucket profile
        val effKind = if (topology.isGroupChat || targetSession?.confirmedIsGroup == true) TopologyKind.GRP else TopologyKind.PRIV
        val effPriv = targetSession?.privateTag?.takeIf { it != PrivateRelationTag.UNSET } ?: ephemeralPrivateTag
        val effGrp = targetSession?.groupTag?.takeIf { it != GroupSceneTag.UNSET } ?: ephemeralGroupTag
        val bucketKey = RelationPragmaticMapper.toBucketKey(effKind, effPriv, effGrp)
        if (bucketKey != null) {
            updateBucketInternal(
                bucketKey = bucketKey,
                adoptedAxisGroup = adoptedAxisGroup,
                adoptedRawIndex = adoptedRawIndex,
                wasTopPinnedSlot = wasTopPinnedSlot,
                observations = featureObservations,
                nowMs = nowMs
            )
        }

        // Update GLOBAL bucket profile (axis + global-allowed style features)
        val globalObs = featureObservations.filter { it.key.allowGlobalScope }
        if (globalObs.isNotEmpty() || (adoptedAxisGroup != null && adoptedRawIndex != null)) {
            updateBucketInternal(
                bucketKey = RelationPragmaticMapper.GLOBAL_BUCKET_KEY,
                adoptedAxisGroup = adoptedAxisGroup,
                adoptedRawIndex = adoptedRawIndex,
                wasTopPinnedSlot = wasTopPinnedSlot,
                observations = globalObs,
                nowMs = nowMs
            )
        }

        maybeRunMaintenance()
    }

    private fun updateBucketInternal(
        bucketKey: String,
        adoptedAxisGroup: JevAxisGroup?,
        adoptedRawIndex: Int?,
        wasTopPinnedSlot: Boolean,
        observations: List<FeatureObservation>,
        nowMs: Long
    ) {
        val prev = bucketsMap[bucketKey] ?: BucketStyleProfile(bucketKey = bucketKey)
        var nextAxis = prev.axisStats
        if (adoptedAxisGroup != null && adoptedRawIndex != null && adoptedRawIndex in 0..2) {
            val updated = JevPreferenceLearner.updateAxisStats(
                current = nextAxis[adoptedAxisGroup],
                adoptedIndex = adoptedRawIndex,
                wasTopPinnedSlot = wasTopPinnedSlot,
                nowMs = nowMs
            )
            nextAxis = nextAxis.toMutableMap().apply { put(adoptedAxisGroup, updated) }
        }
        val nextFeatures = if (observations.isNotEmpty()) {
            JevPreferenceLearner.updateFeatureCounters(
                current = prev.featureCounters,
                observations = observations,
                nowMs = nowMs
            )
        } else {
            prev.featureCounters
        }
        val updatedBucket = prev.copy(
            axisStats = nextAxis,
            featureCounters = nextFeatures,
            updatedAtMs = nowMs
        )
        bucketsMap[bucketKey] = updatedBucket
        enqueueWrite { backend.upsertBucket(updatedBucket) }
    }

    private fun findUidForAnchor(anchor: AnchorResolution.Resolved): String? {
        aliasesMap[anchor.anchorHash]?.sessionUid?.let { return it }
        if (anchor.fallbackUnscopedHash != null) {
            aliasesMap[anchor.fallbackUnscopedHash]?.sessionUid?.let { return it }
        }
        if (anchor.secondaryGroupFallbackHash != null) {
            val uid = aliasesMap[anchor.secondaryGroupFallbackHash]?.sessionUid
            if (uid != null && sessionsMap[uid]?.confirmedIsGroup == true) {
                return uid
            }
        }
        return null
    }

    fun getActiveStyleFeaturesForSession(
        anchor: AnchorResolution,
        topology: ChatTopology,
        ephemeralPrivateTag: PrivateRelationTag = PrivateRelationTag.UNSET,
        ephemeralGroupTag: GroupSceneTag = GroupSceneTag.UNSET
    ): Set<StyleFeatureKey> {
        ensureHydrated()
        val session = if (anchor is AnchorResolution.Resolved && !anchor.isAmbiguousGeneric) {
            findUidForAnchor(anchor)?.let { sessionsMap[it] }
        } else {
            null
        }
        val effKind = if (topology.isGroupChat || session?.confirmedIsGroup == true) TopologyKind.GRP else TopologyKind.PRIV
        val effPriv = session?.privateTag?.takeIf { it != PrivateRelationTag.UNSET } ?: ephemeralPrivateTag
        val effGrp = session?.groupTag?.takeIf { it != GroupSceneTag.UNSET } ?: ephemeralGroupTag
        val bucketKey = RelationPragmaticMapper.toBucketKey(effKind, effPriv, effGrp)
        val bucket = bucketKey?.let { bucketsMap[it] }
        val global = bucketsMap[RelationPragmaticMapper.GLOBAL_BUCKET_KEY]

        val out = LinkedHashSet<StyleFeatureKey>()
        for (key in StyleFeatureKey.entries) {
            val sessActive = session?.featureCounters?.get(key)?.effectiveActive == true
            val bucketActive = bucket?.featureCounters?.get(key)?.effectiveActive == true
            val globalActive = key.allowGlobalScope && global?.featureCounters?.get(key)?.effectiveActive == true
            if (sessActive || bucketActive || globalActive) {
                out.add(key)
            }
        }
        return out
    }

    // --- Settings / Management Screen APIs ---

    fun listAllSessionsSorted(): List<SessionMemoryRecord> {
        ensureHydrated()
        return sessionsMap.values.sortedWith(
            compareByDescending<SessionMemoryRecord> { it.isPinned }
                .thenByDescending { it.lastActiveAtMs }
        )
    }

    fun listAllBuckets(): List<BucketStyleProfile> {
        ensureHydrated()
        return bucketsMap.values.sortedBy { it.bucketKey }
    }

    fun getBucket(bucketKey: String): BucketStyleProfile? {
        ensureHydrated()
        return bucketsMap[bucketKey]
    }

    fun updateSessionFromSettings(
        sessionUid: String,
        privateTag: PrivateRelationTag? = null,
        groupTag: GroupSceneTag? = null,
        redactedNote: String? = null,
        isPinned: Boolean? = null
    ) {
        ensureHydrated()
        val existing = sessionsMap[sessionUid] ?: return
        val nowMs = clock.nowMs()
        val updated = existing.copy(
            privateTag = privateTag ?: existing.privateTag,
            groupTag = groupTag ?: existing.groupTag,
            redactedCustomNote = redactedNote?.trim()?.ifEmpty { null },
            isPinned = isPinned ?: existing.isPinned,
            lastActiveAtMs = nowMs
        )
        sessionsMap[sessionUid] = updated
        enqueueWrite { backend.upsertSession(updated) }
    }

    fun deleteSession(sessionUid: String) {
        ensureHydrated()
        sessionsMap.remove(sessionUid)
        val iter = aliasesMap.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value.sessionUid == sessionUid) iter.remove()
        }
        bubbleContinuityTracker.removeSession(sessionUid)
        enqueueWrite { backend.deleteSessionCascade(sessionUid) }
    }

    fun setBucketFeatureOverride(
        bucketKey: String,
        key: StyleFeatureKey,
        overrideValue: Boolean?
    ) {
        ensureHydrated()
        val nowMs = clock.nowMs()
        val prev = bucketsMap[bucketKey] ?: BucketStyleProfile(bucketKey = bucketKey)
        val existingCounter = prev.featureCounters[key] ?: FeatureCounter()
        val updatedCounter = existingCounter.copy(userOverride = overrideValue, updatedAtMs = nowMs)
        val nextMap = prev.featureCounters.toMutableMap().apply { put(key, updatedCounter) }
        val updatedBucket = prev.copy(featureCounters = nextMap, updatedAtMs = nowMs)
        bucketsMap[bucketKey] = updatedBucket
        enqueueWrite { backend.upsertBucket(updatedBucket) }
    }

    fun resetBucketFeature(bucketKey: String, key: StyleFeatureKey) {
        ensureHydrated()
        val prev = bucketsMap[bucketKey] ?: return
        val nextMap = prev.featureCounters.toMutableMap().apply { remove(key) }
        val updatedBucket = prev.copy(featureCounters = nextMap, updatedAtMs = clock.nowMs())
        bucketsMap[bucketKey] = updatedBucket
        enqueueWrite { backend.upsertBucket(updatedBucket) }
    }

    fun wipeAllMemoryAndRotateSalt() {
        ensureHydrated()
        sessionsMap.clear()
        aliasesMap.clear()
        bucketsMap.clear()
        bubbleContinuityTracker.clear()
        saltProvider.rotateDeviceSalt()
        enqueueWrite { backend.wipeAll() }
    }

    // --- TTL + Weighted LRU Maintenance ---

    fun maybeRunMaintenance(force: Boolean = false) {
        val nowMs = clock.nowMs()
        if (!force && sessionsMap.size <= MAINTENANCE_TRIGGER_COUNT && nowMs - lastMaintenanceMs < MAINTENANCE_INTERVAL_MS) {
            return
        }
        lastMaintenanceMs = nowMs

        val unlabelledCutoff = nowMs - UNLABELLED_TTL_MS
        val labelledCutoff = nowMs - LABELLED_TTL_MS
        val survivors = sessionsMap.values.filter { rec ->
            if (rec.isPinned) return@filter true
            val hasExplicitLabel = rec.privateTag != PrivateRelationTag.UNSET ||
                rec.groupTag != GroupSceneTag.UNSET ||
                !rec.redactedCustomNote.isNullOrBlank()
            if (hasExplicitLabel) {
                rec.lastActiveAtMs >= labelledCutoff
            } else {
                rec.lastActiveAtMs >= unlabelledCutoff
            }
        }

        // Enforce pinned cap (200) and total cap (500)
        val pinnedList = survivors.filter { it.isPinned }
            .sortedByDescending { it.lastActiveAtMs }
            .take(MAX_PINNED_SESSIONS)
        val unpinnedRemaining = MAX_SESSIONS - pinnedList.size
        val unpinnedList = survivors.filterNot { it.isPinned }
            .sortedWith(
                compareByDescending<SessionMemoryRecord> {
                    it.privateTag != PrivateRelationTag.UNSET ||
                        it.groupTag != GroupSceneTag.UNSET ||
                        !it.redactedCustomNote.isNullOrBlank()
                }.thenByDescending { it.lastActiveAtMs }
            )
            .take(unpinnedRemaining.coerceAtLeast(0))

        val keepUids = (pinnedList + unpinnedList).map { it.sessionUid }.toHashSet()
        if (keepUids.size < sessionsMap.size) {
            val sIter = sessionsMap.keys.iterator()
            while (sIter.hasNext()) {
                if (sIter.next() !in keepUids) sIter.remove()
            }
            val aIter = aliasesMap.entries.iterator()
            while (aIter.hasNext()) {
                if (aIter.next().value.sessionUid !in keepUids) aIter.remove()
            }
            enqueueWrite { backend.pruneSessionsExcept(keepUids) }
        }
    }

    // --- Encrypted Export & Import ---

    fun exportEncryptedArchive(
        passphrase: CharArray,
        wipePassphraseAfterUse: Boolean = true
    ): EncryptedJevMemoryArchive {
        ensureHydrated()
        val saltB64 = Base64.getEncoder().encodeToString(saltProvider.getOrCreateDeviceSalt())
        val sanitizedSessions = sessionsMap.values.map { it.copy(maskedTitleHint = null) }
        val payload = JevMemoryPlainBackupPayload(
            schemaVersion = 1,
            exportedAtMs = clock.nowMs(),
            deviceSaltBase64 = saltB64,
            sessions = sanitizedSessions,
            aliases = aliasesMap.values.toList(),
            buckets = bucketsMap.values.toList()
        )
        return JevMemoryBackupCrypto.encryptPayload(
            payload = payload,
            passphrase = passphrase,
            wipePassphraseAfterUse = wipePassphraseAfterUse
        )
    }

    fun importEncryptedArchive(
        archive: EncryptedJevMemoryArchive,
        passphrase: CharArray,
        adoptDeviceSaltCallback: ((ByteArray) -> Unit)? = null,
        wipePassphraseAfterUse: Boolean = true
    ): Result<JevMemoryImportSummary> {
        ensureHydrated()
        val decrypted = JevMemoryBackupCrypto.decryptPayload(
            archive = archive,
            passphrase = passphrase,
            wipePassphraseAfterUse = wipePassphraseAfterUse
        ).getOrElse { return Result.failure(it) }

        val localIsEmpty = sessionsMap.isEmpty() && bucketsMap.isEmpty()
        val localSaltB64 = Base64.getEncoder().encodeToString(saltProvider.getOrCreateDeviceSalt())
        val sameSalt = localSaltB64 == decrypted.deviceSaltBase64

        return if (localIsEmpty || sameSalt) {
            var adopted = false
            if (localIsEmpty && decrypted.deviceSaltBase64.isNotBlank() && adoptDeviceSaltCallback != null) {
                runCatching {
                    val decodedSalt = Base64.getDecoder().decode(decrypted.deviceSaltBase64)
                    if (decodedSalt.size == 32) {
                        adoptDeviceSaltCallback(decodedSalt)
                        adopted = true
                    }
                }
            }
            decrypted.sessions.forEach { s ->
                sessionsMap[s.sessionUid] = s
                enqueueWrite { backend.upsertSession(s) }
            }
            decrypted.aliases.forEach { a ->
                aliasesMap[a.anchorHash] = a
                enqueueWrite { backend.upsertAlias(a) }
            }
            decrypted.buckets.forEach { b ->
                bucketsMap[b.bucketKey] = b
                enqueueWrite { backend.upsertBucket(b) }
            }
            Result.success(
                JevMemoryImportSummary(
                    importedSessions = decrypted.sessions.size,
                    importedAliases = decrypted.aliases.size,
                    mergedBuckets = decrypted.buckets.size,
                    adoptedDeviceSalt = adopted
                )
            )
        } else {
            // Preserve local sessions & deviceSalt; sample-weighted merge on style buckets
            var mergedCount = 0
            for (incoming in decrypted.buckets) {
                val local = bucketsMap[incoming.bucketKey]
                val merged = if (local == null) {
                    incoming
                } else {
                    mergeBucketsWeighted(local, incoming, clock.nowMs())
                }
                bucketsMap[merged.bucketKey] = merged
                enqueueWrite { backend.upsertBucket(merged) }
                mergedCount++
            }
            Result.success(
                JevMemoryImportSummary(
                    importedSessions = 0,
                    importedAliases = 0,
                    mergedBuckets = mergedCount,
                    adoptedDeviceSalt = false
                )
            )
        }
    }

    private fun mergeBucketsWeighted(
        local: BucketStyleProfile,
        incoming: BucketStyleProfile,
        nowMs: Long
    ): BucketStyleProfile {
        val mergedAxis = LinkedHashMap<JevAxisGroup, AxisPreferenceStats>()
        for (group in JevAxisGroup.entries) {
            val l = local.axisStats[group]?.sanitized()
            val r = incoming.axisStats[group]?.sanitized()
            when {
                l != null && r != null -> {
                    val nl = l.sampleCount.coerceAtLeast(1)
                    val nr = r.sampleCount.coerceAtLeast(1)
                    val total = (nl + nr).toDouble()
                    val probs = List(3) { i -> (nl * l.probs[i] + nr * r.probs[i]) / total }
                    mergedAxis[group] = AxisPreferenceStats(
                        probs = probs,
                        sampleCount = l.sampleCount + r.sampleCount,
                        updatedAtMs = maxOf(l.updatedAtMs, r.updatedAtMs)
                    ).sanitized()
                }
                l != null -> mergedAxis[group] = l
                r != null -> mergedAxis[group] = r
            }
        }
        val mergedFeatures = LinkedHashMap<StyleFeatureKey, FeatureCounter>()
        for (key in StyleFeatureKey.entries) {
            val l = local.featureCounters[key]
            val r = incoming.featureCounters[key]
            when {
                l != null && r != null -> {
                    val nl = l.opportunityCount.coerceAtLeast(1)
                    val nr = r.opportunityCount.coerceAtLeast(1)
                    val total = (nl + nr).toDouble()
                    val ema = (nl * l.emaValue + nr * r.emaValue) / total
                    val sec = (nl * l.secondaryEma + nr * r.secondaryEma) / total
                    mergedFeatures[key] = FeatureCounter(
                        emaValue = ema,
                        secondaryEma = sec,
                        opportunityCount = l.opportunityCount + r.opportunityCount,
                        isActive = l.isActive || r.isActive,
                        userOverride = l.userOverride ?: r.userOverride,
                        updatedAtMs = maxOf(l.updatedAtMs, r.updatedAtMs)
                    )
                }
                l != null -> mergedFeatures[key] = l
                r != null -> mergedFeatures[key] = r
            }
        }
        return BucketStyleProfile(
            bucketKey = local.bucketKey,
            axisStats = mergedAxis,
            featureCounters = mergedFeatures,
            updatedAtMs = nowMs
        )
    }

    fun awaitPendingWrites(timeoutMs: Long = 2_000L): Boolean {
        return runCatching {
            writeExecutor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)
    }

    private fun enqueueWrite(task: () -> Unit) {
        runCatching {
            writeExecutor.execute {
                runCatching { task() }
            }
        }
    }

    companion object {
        const val MAX_SESSIONS = 500
        const val MAX_PINNED_SESSIONS = 200
        const val MAINTENANCE_TRIGGER_COUNT = 520
        const val MAINTENANCE_INTERVAL_MS = 6L * 3600_000L
        const val UNLABELLED_TTL_MS = 90L * 86_400_000L
        const val LABELLED_TTL_MS = 365L * 86_400_000L
        private const val TOUCH_RENEW_THROTTLE_MS = 60_000L
    }
}
