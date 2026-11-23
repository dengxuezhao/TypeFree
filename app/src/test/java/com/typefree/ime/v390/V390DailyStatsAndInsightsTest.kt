package com.typefree.ime.v390

import android.text.InputType
import android.view.inputmethod.InputConnection
import com.typefree.ime.data.AppRepository
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DictionaryEntry
import com.typefree.ime.data.DictionarySource
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.SecretVault
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.data.stats.DailyStatsRecord
import com.typefree.ime.data.stats.DailyStatsStore
import com.typefree.ime.data.stats.InMemoryDailyStatsBackend
import com.typefree.ime.data.stats.InsightsAggregator
import com.typefree.ime.data.stats.SchemeStatsKeys
import com.typefree.ime.data.stats.StatsTimeRange
import com.typefree.ime.ime.ImeHostCallbacks
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.KeyboardTabMode
import com.typefree.ime.ime.TypingController
import com.typefree.ime.ime.UndoSnapshot
import com.typefree.ime.ime.VoiceController
import com.typefree.ime.ime.flypy.FlypyCandidate
import com.typefree.ime.ime.jev.memory.AxisPreferenceStats
import com.typefree.ime.ime.jev.memory.BucketStyleProfile
import com.typefree.ime.ime.jev.memory.FeatureCounter
import com.typefree.ime.ime.jev.memory.JevAxisGroup
import com.typefree.ime.ime.jev.memory.RelationPragmaticMapper
import com.typefree.ime.ime.jev.memory.SessionMemoryRecord
import com.typefree.ime.ime.jev.memory.StyleFeatureKey
import com.typefree.ime.ime.jev.memory.TopologyKind
import com.typefree.ime.ime.predict.PredictionStore
import com.typefree.ime.network.AiPipelineEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class V390DailyStatsAndInsightsTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0): Long {
        return Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
    }

    private fun createInMemoryRepository(
        publicStore: InMemoryKeyValueStore = InMemoryKeyValueStore()
    ): AppRepository {
        val secretStore = InMemoryKeyValueStore()
        val vault = SecretVault(store = secretStore, cipher = JvmAesGcmCipher())
        return AppRepository(
            appContext = null,
            publicStore = publicStore,
            secretVault = vault
        )
    }

    @Test
    fun `DailyStatsStore records voice, keyboard, snippet, prediction, Jev, backfills once, flushes, and resets`() {
        val backend = InMemoryDailyStatsBackend()
        val t1 = utcMillis(2026, 11, 23, hour = 9)
        val t2 = utcMillis(2026, 11, 23, hour = 14)
        val store = DailyStatsStore(backend = backend, clock = { t1 }, debounceMs = 60_000L, timeZone = utc)

        val initialRev = store.revision.value

        // Voice dictation (LOCAL offline)
        store.recordVoiceAndAi(
            HistoryRecord(
                timestamp = t1,
                targetAppName = "飞书",
                styleName = "职场干练",
                intentMode = VoiceIntentMode.DICTATE,
                rawTranscript = "今天下午三点同步版本进度",
                polishedText = "今天下午三点同步版本进度。",
                durationMs = 6_000L,
                latencyMs = 420L,
                executionSource = "LOCAL"
            )
        )

        // Voice HELP_ME_WRITE (real spoken prompt, NOT bracket-prefixed preset)
        store.recordVoiceAndAi(
            HistoryRecord(
                timestamp = t2,
                targetAppName = "微信",
                styleName = "社交聊天",
                intentMode = VoiceIntentMode.HELP_ME_WRITE,
                rawTranscript = "帮我委婉拒绝周末加班",
                polishedText = "周末家里已经提前安排好行程了，这周实在抽不开身，下周一我第一时间优先跟进处理。",
                durationMs = 4_000L,
                latencyMs = 780L,
                executionSource = "CLOUD"
            )
        )

        // Bracket-prefixed preset HELP_ME_WRITE should NOT inflate helpWriteRawChars/helpWriteCount
        store.recordVoiceAndAi(
            HistoryRecord(
                timestamp = t2,
                targetAppName = "微信",
                styleName = "社交聊天",
                intentMode = VoiceIntentMode.HELP_ME_WRITE,
                rawTranscript = "[帮我写灵感] 写一封感谢邮件",
                polishedText = "非常感谢您的支持与配合。",
                durationMs = 0L,
                latencyMs = 500L,
                executionSource = "CLOUD"
            )
        )

        store.recordKeyboardCommit(
            charCount = 12,
            schemeKey = SchemeStatsKeys.fromInputScheme(InputScheme.FLYPY_TABLE, isChineseMode = true),
            appName = "飞书",
            nowMs = t1
        )
        store.recordSnippetHit(savedChars = 20, nowMs = t2)
        store.recordPredictionHit(savedKeys = 6, nowMs = t2)
        store.recordJevAdopt(appName = "微信", nowMs = t2)

        assertTrue(store.revision.value > initialRev)
        assertTrue(store.flushBlocking(2_000L))
        assertEquals(1, backend.snapshotSize())

        val records = store.listAllRecords()
        assertEquals(1, records.size)
        val day = records.first()
        assertEquals(20261123, day.dateKey)
        assertEquals(3, day.voiceCount)
        assertEquals(10_000L, day.voiceDurationMs)
        assertEquals(1_700L, day.voiceLatencySumMs)
        assertEquals(3, day.voiceLatencyCount)
        assertEquals(1, day.offlineCount)
        assertEquals(2, day.cloudCount)
        assertEquals(1, day.helpWriteCount)
        assertEquals("帮我委婉拒绝周末加班".length, day.helpWriteRawChars)
        assertEquals(12, day.keyChars)
        assertEquals(1, day.keyCommits)
        assertEquals(12, day.schemeChars[InputScheme.FLYPY_TABLE.name])
        assertEquals(1, day.snippetHits)
        assertEquals(20, day.snippetSavedChars)
        assertEquals(1, day.predictionHits)
        assertEquals(6, day.predictionSavedKeys)
        assertEquals(1, day.jevAdopts)
        assertEquals(12, day.hourlyKeyChars[9])
        assertTrue(day.hourlyVoiceChars[9] > 0)
        assertTrue(day.hourlyVoiceChars[14] > 0)
        assertEquals(1, day.intentCounts[VoiceIntentMode.DICTATE.name])
        assertEquals(2, day.intentCounts[VoiceIntentMode.HELP_ME_WRITE.name])

        // Backfill must be a no-op when store already has records
        val backfilledAgain = store.backfillFromHistoryIfEmpty(
            listOf(HistoryRecord(timestamp = utcMillis(2026, 11, 10), polishedText = "旧记录"))
        )
        assertFalse(backfilledAgain)
        assertEquals(1, store.listAllRecords().size)

        // Reset clears both memory and backend
        store.resetAll()
        assertTrue(store.flushBlocking(2_000L))
        assertTrue(store.listAllRecords().isEmpty())
        assertEquals(0, backend.snapshotSize())

        // Backfill works once store is empty
        val backfilledNow = store.backfillFromHistoryIfEmpty(
            listOf(
                HistoryRecord(timestamp = utcMillis(2026, 11, 20), polishedText = "历史记录一", durationMs = 5_000L),
                HistoryRecord(timestamp = utcMillis(2026, 11, 21), polishedText = "历史记录二", durationMs = 4_000L)
            )
        )
        assertTrue(backfilledNow)
        assertTrue(store.flushBlocking(2_000L))
        assertEquals(2, store.listAllRecords().size)
        assertEquals(2, backend.snapshotSize())
    }

    @Test
    fun `DailyStatsStore enforces strict zero-recording when disabled or in incognito mode`() {
        val backend = InMemoryDailyStatsBackend()
        val now = utcMillis(2026, 11, 23, hour = 11)
        val store = DailyStatsStore(backend = backend, clock = { now }, debounceMs = 10_000L, timeZone = utc)

        val rev0 = store.revision.value
        val sampleVoice = HistoryRecord(timestamp = now, polishedText = "机密内容", durationMs = 4_000L)

        // Explicitly disabled
        store.recordVoiceAndAi(sampleVoice, enabled = false)
        store.recordKeyboardCommit(10, "ENGLISH", "微信", now, enabled = false)
        store.recordSnippetHit(15, now, enabled = false)
        store.recordPredictionHit(4, now, enabled = false)
        store.recordJevAdopt("微信", now, enabled = false)
        assertEquals(rev0, store.revision.value)
        assertTrue(store.listAllRecords().isEmpty())

        // Explicitly incognito
        store.recordVoiceAndAi(sampleVoice, incognito = true)
        store.recordKeyboardCommit(10, "ENGLISH", "微信", now, incognito = true)
        store.recordSnippetHit(15, now, incognito = true)
        store.recordPredictionHit(4, now, incognito = true)
        store.recordJevAdopt("微信", now, incognito = true)
        assertEquals(rev0, store.revision.value)
        assertTrue(store.listAllRecords().isEmpty())

        // Store-level flags
        store.isStatsEnabled = false
        store.recordKeyboardCommit(5, "FLYPY_TABLE", nowMs = now)
        assertTrue(store.listAllRecords().isEmpty())

        store.isStatsEnabled = true
        store.isIncognitoMode = true
        store.recordKeyboardCommit(5, "FLYPY_TABLE", nowMs = now)
        assertTrue(store.listAllRecords().isEmpty())
    }

    @Test
    fun `InsightsAggregator handles cold start cleanly without faking WPM`() {
        val now = utcMillis(2026, 11, 23, hour = 10)
        val snapshot = InsightsAggregator.buildSnapshot(
            range = StatsTimeRange.ALL,
            dailyRecords = emptyList(),
            history = emptyList(),
            settings = AppSettings(),
            jevSessions = emptyList(),
            jevBuckets = emptyList(),
            personalBigramCount = 0,
            personalTrigramCount = 0,
            topWordChains = emptyList(),
            dbSizeBytes = 0L,
            nowMs = now,
            timeZone = utc
        )

        assertTrue(snapshot.isColdStart)
        assertFalse(snapshot.hasMeasuredWpm)
        assertEquals(0, snapshot.averageWpm)
        assertEquals(0f, snapshot.speedMultiplier, 0.0001f)
        assertEquals(0, snapshot.savedMinutes)
        assertEquals(0, snapshot.streakDays)
        assertEquals(7, snapshot.mini7DayChars.size)
        assertTrue(snapshot.mini7DayChars.all { it == 0 })
        assertEquals(56, snapshot.heatmapDays.size)
        assertTrue(snapshot.heatmapDays.all { it.intensityLevel == 0 })
        assertEquals(20261123, snapshot.heatmapDays.last().dateKey)
        assertEquals(12, snapshot.monthlyTrend.size)
        assertFalse(snapshot.monthlyTrend[10].isFuture) // November (index 10)
        assertTrue(snapshot.monthlyTrend[11].isFuture) // December (index 11)
        assertNull(snapshot.jevAxisTendency)
        assertEquals(6, snapshot.milestones.size)
        assertTrue(snapshot.milestones.none { it.unlocked })
    }

    @Test
    fun `InsightsAggregator filters all 4 StatsTimeRanges accurately and excludes 0ms quick edits from WPM`() {
        val now = utcMillis(2026, 11, 23, hour = 15)
        val recToday = DailyStatsRecord(
            dateKey = 20261123,
            voiceChars = 180,
            voiceCount = 2,
            voiceDurationMs = 30_000L, // 30s timed speech + 1 quick preset
            keyChars = 100,
            keyCommits = 10,
            offlineCount = 1,
            cloudCount = 1
        )
        val rec5DaysAgo = DailyStatsRecord(
            dateKey = 20261118,
            voiceChars = 90,
            voiceCount = 1,
            voiceDurationMs = 30_000L,
            keyChars = 50,
            keyCommits = 5,
            offlineCount = 1,
            cloudCount = 0
        )
        val recEarlierThisMonth = DailyStatsRecord(
            dateKey = 20261102,
            voiceChars = 200,
            voiceCount = 2,
            voiceDurationMs = 60_000L,
            keyChars = 300,
            keyCommits = 20
        )
        val recEarlierThisYear = DailyStatsRecord(
            dateKey = 20260515,
            voiceChars = 400,
            voiceCount = 4,
            voiceDurationMs = 120_000L,
            keyChars = 600,
            keyCommits = 40
        )
        val recLastYear = DailyStatsRecord(
            dateKey = 20251220,
            voiceChars = 1000,
            voiceCount = 10,
            voiceDurationMs = 300_000L,
            keyChars = 500,
            keyCommits = 30
        )
        val allDays = listOf(recLastYear, recEarlierThisYear, recEarlierThisMonth, rec5DaysAgo, recToday)

        // History with 0ms quick-edit preset on today PLUS a real 30s dictation of 90 chars (= 180 wpm)
        val history = listOf(
            HistoryRecord(
                timestamp = now,
                polishedText = "a".repeat(90),
                durationMs = 30_000L
            ),
            HistoryRecord(
                timestamp = now,
                polishedText = "b".repeat(90),
                durationMs = 0L // quick edit preset must NOT inflate WPM to 360
            ),
            HistoryRecord(
                timestamp = utcMillis(2026, 11, 18),
                polishedText = "c".repeat(90),
                durationMs = 30_000L
            )
        )

        val d7 = InsightsAggregator.buildSnapshot(
            range = StatsTimeRange.DAYS_7,
            dailyRecords = allDays,
            history = history,
            settings = AppSettings(),
            jevSessions = emptyList(),
            jevBuckets = emptyList(),
            personalBigramCount = 0,
            personalTrigramCount = 0,
            topWordChains = emptyList(),
            dbSizeBytes = 1024L,
            nowMs = now,
            timeZone = utc
        )
        assertFalse(d7.isColdStart)
        assertEquals(270 + 150, d7.totalChars)
        assertEquals(270, d7.voiceChars)
        assertEquals(150, d7.keyChars)
        assertTrue(d7.hasMeasuredWpm)
        // Timed history: 90 + 90 = 180 chars over 60s -> exact 180 wpm (0ms quick preset excluded!)
        assertEquals(180, d7.averageWpm)
        assertEquals((180f / 42f), d7.speedMultiplier, 0.02f)

        val monthSnap = InsightsAggregator.buildSnapshot(
            range = StatsTimeRange.THIS_MONTH,
            dailyRecords = allDays,
            history = history,
            settings = AppSettings(),
            jevSessions = emptyList(),
            jevBuckets = emptyList(),
            personalBigramCount = 0,
            personalTrigramCount = 0,
            topWordChains = emptyList(),
            dbSizeBytes = 1024L,
            nowMs = now,
            timeZone = utc
        )
        assertEquals(470 + 450, monthSnap.totalChars)

        val yearSnap = InsightsAggregator.buildSnapshot(
            range = StatsTimeRange.THIS_YEAR,
            dailyRecords = allDays,
            history = history,
            settings = AppSettings(),
            jevSessions = emptyList(),
            jevBuckets = emptyList(),
            personalBigramCount = 0,
            personalTrigramCount = 0,
            topWordChains = emptyList(),
            dbSizeBytes = 1024L,
            nowMs = now,
            timeZone = utc
        )
        assertEquals(870 + 1050, yearSnap.totalChars)
        // Monthly trend check: May (month 5 -> idx 4) and Nov (month 11 -> idx 10)
        assertEquals(400, yearSnap.monthlyTrend[4].voiceChars)
        assertEquals(600, yearSnap.monthlyTrend[4].keyChars)
        assertEquals(470, yearSnap.monthlyTrend[10].voiceChars)
        assertEquals(450, yearSnap.monthlyTrend[10].keyChars)
        assertTrue(yearSnap.monthlyTrend[11].isFuture)

        val allSnap = InsightsAggregator.buildSnapshot(
            range = StatsTimeRange.ALL,
            dailyRecords = allDays,
            history = history,
            settings = AppSettings(),
            jevSessions = emptyList(),
            jevBuckets = emptyList(),
            personalBigramCount = 0,
            personalTrigramCount = 0,
            topWordChains = emptyList(),
            dbSizeBytes = 1024L,
            nowMs = now,
            timeZone = utc
        )
        assertEquals(1870 + 1550, allSnap.totalChars)

        // Only 0ms quick edits -> hasMeasuredWpm MUST be false, averageWpm == 0
        val zeroDurOnly = InsightsAggregator.buildSnapshot(
            range = StatsTimeRange.DAYS_7,
            dailyRecords = listOf(DailyStatsRecord(dateKey = 20261123, voiceChars = 500, voiceCount = 3, voiceDurationMs = 0L)),
            history = listOf(HistoryRecord(timestamp = now, polishedText = "x".repeat(500), durationMs = 0L)),
            settings = AppSettings(),
            jevSessions = emptyList(),
            jevBuckets = emptyList(),
            personalBigramCount = 0,
            personalTrigramCount = 0,
            topWordChains = emptyList(),
            dbSizeBytes = 0L,
            nowMs = now,
            timeZone = utc
        )
        assertFalse(zeroDurOnly.hasMeasuredWpm)
        assertEquals(0, zeroDurOnly.averageWpm)
        assertEquals(0f, zeroDurOnly.speedMultiplier, 0.0001f)
    }

    @Test
    fun `InsightsAggregator calculates streak, 56-day heatmap intensities, Jev DNA, axis tendency, and milestones`() {
        val now = utcMillis(2026, 11, 23, hour = 20)
        // Active on Nov 20, 21, 22 (yesterday) — today (Nov 23) not typed yet -> streak should still be 3
        val streakDaysRecords = listOf(
            DailyStatsRecord(
                dateKey = 20261120,
                voiceChars = 100,
                voiceCount = 2,
                voiceDurationMs = 30_000L,
                keyChars = 2000,
                keyCommits = 50,
                offlineCount = 5,
                cloudCount = 1,
                snippetHits = 10,
                snippetSavedChars = 150,
                appCounts = mapOf("飞书" to 10)
            ),
            DailyStatsRecord(
                dateKey = 20261121,
                voiceChars = 400,
                voiceCount = 50,
                voiceDurationMs = 120_000L,
                keyChars = 4000,
                keyCommits = 80,
                offlineCount = 5,
                cloudCount = 2,
                snippetHits = 20,
                snippetSavedChars = 300,
                appCounts = mapOf("微信" to 25)
            ),
            DailyStatsRecord(
                dateKey = 20261122,
                voiceChars = 800,
                voiceCount = 50,
                voiceDurationMs = 180_000L,
                keyChars = 5000,
                keyCommits = 100,
                offlineCount = 4,
                cloudCount = 1,
                snippetHits = 5,
                snippetSavedChars = 80,
                appCounts = mapOf("微信" to 15)
            )
        )

        val globalBucket = BucketStyleProfile(
            bucketKey = RelationPragmaticMapper.GLOBAL_BUCKET_KEY,
            axisStats = mapOf(
                JevAxisGroup.TKI_GAME to AxisPreferenceStats(
                    probs = listOf(0.55, 0.30, 0.15),
                    sampleCount = 12,
                    updatedAtMs = now
                ),
                JevAxisGroup.GROUP_BYSTANDER to AxisPreferenceStats(
                    probs = listOf(0.34, 0.33, 0.33),
                    sampleCount = 3,
                    updatedAtMs = now
                )
            ),
            featureCounters = mapOf(
                StyleFeatureKey.DROP_TRAILING_PERIOD to FeatureCounter(
                    emaValue = 0.86,
                    opportunityCount = 7,
                    isActive = true,
                    updatedAtMs = now
                ),
                StyleFeatureKey.STRIP_EMOJI to FeatureCounter(
                    emaValue = 0.65,
                    opportunityCount = 2,
                    isActive = false,
                    updatedAtMs = now
                ),
                StyleFeatureKey.COMPRESS_LENGTH to FeatureCounter(
                    emaValue = 0.55,
                    secondaryEma = 18.0,
                    opportunityCount = 4,
                    isActive = false,
                    updatedAtMs = now
                )
            )
        )
        val privBucket = BucketStyleProfile(
            bucketKey = "PRIV::close_friend",
            featureCounters = mapOf(
                StyleFeatureKey.HONORIFIC_NIN_TO_NI to FeatureCounter(
                    emaValue = 0.90,
                    opportunityCount = 4,
                    isActive = true,
                    userOverride = true,
                    updatedAtMs = now
                )
            )
        )
        val sessions = listOf(
            SessionMemoryRecord(
                sessionUid = "s_1",
                canonicalApp = "wechat",
                topologyKind = TopologyKind.PRIV,
                interactionCount = 8,
                createdAtMs = now - 86_400_000L,
                lastActiveAtMs = now
            )
        )
        val settings = AppSettings(
            dictionary = listOf(
                DictionaryEntry(term = "TypeFree", source = DictionarySource.MANUAL),
                DictionaryEntry(term = "小鹤音形", source = DictionarySource.AUTO_LEARNED),
                DictionaryEntry(term = "雾凇拼音", source = DictionarySource.AUTO_LEARNED)
            )
        )

        val snap = InsightsAggregator.buildSnapshot(
            range = StatsTimeRange.ALL,
            dailyRecords = streakDaysRecords,
            history = emptyList(),
            settings = settings,
            jevSessions = sessions,
            jevBuckets = listOf(globalBucket, privBucket),
            personalBigramCount = 18,
            personalTrigramCount = 4,
            topWordChains = listOf("下午 → 开会", "收到 → 谢谢"),
            dbSizeBytes = 4096L,
            nowMs = now,
            timeZone = utc
        )

        assertEquals(3, snap.streakDays)
        assertEquals(56, snap.heatmapDays.size)
        val nov22Cell = snap.heatmapDays.first { it.dateKey == 20261122 }
        val nov23Cell = snap.heatmapDays.first { it.dateKey == 20261123 }
        assertTrue(nov22Cell.intensityLevel in 1..4)
        assertEquals(0, nov23Cell.intensityLevel)
        assertEquals("微信", nov22Cell.topAppName)

        // Jev DNA: active rules sorted BEFORE observing rules
        assertEquals(4, snap.jevDnaItems.size)
        assertTrue(snap.jevDnaItems[0].isActive)
        assertTrue(snap.jevDnaItems[1].isActive)
        assertFalse(snap.jevDnaItems[2].isActive)
        assertFalse(snap.jevDnaItems[3].isActive)
        val stripEmojiItem = snap.jevDnaItems.first { it.key == StyleFeatureKey.STRIP_EMOJI }
        assertEquals(2, stripEmojiItem.currentCount)
        assertEquals(4, stripEmojiItem.requiredCount)
        val compressItem = snap.jevDnaItems.first { it.key == StyleFeatureKey.COMPRESS_LENGTH }
        assertEquals(4, compressItem.currentCount)
        assertEquals(6, compressItem.requiredCount)

        // Jev Axis tendency selects highest-sample group (TKI_GAME, 12 samples)
        val axis = snap.jevAxisTendency
        assertNotNull(axis)
        assertEquals(JevAxisGroup.TKI_GAME, axis!!.group)
        assertEquals(12, axis.sampleCount)
        assertEquals(100, axis.percents.sum())
        assertEquals(3, axis.labels.size)

        // Dict & prediction assets
        assertEquals(2, snap.autoLearnedDictCount)
        assertEquals(3, snap.totalDictCount)
        assertEquals(18, snap.personalBigramCount)
        assertEquals(4, snap.personalTrigramCount)
        assertEquals(2, snap.topWordChains.size)

        // All 6 lifetime milestones unlocked with the above totals:
        // chars = 12300 >= 10000, voice = 102 >= 100, keyChars = 11000 >= 10000,
        // snippetHits = 35 >= 30, offline = 14 / 18 (78% >= 50%), activeDna = 2 >= 2
        assertEquals(6, snap.milestones.size)
        assertTrue(
            "Expected all 6 milestones unlocked, got: ${snap.milestones}",
            snap.milestones.all { it.unlocked && it.progressPercent == 100 }
        )
    }

    @Test
    fun `PredictionStore listTopBigrams returns entries sorted by count DESC then lastUsedMs DESC`() {
        val store = PredictionStore.inMemory()
        store.recordBigram("今天", "天气", nowMs = 1_000L)
        store.recordBigram("明天", "开会", nowMs = 2_000L)
        store.recordBigram("明天", "开会", nowMs = 3_000L)
        store.recordBigram("明天", "开会", nowMs = 4_000L)
        store.recordBigram("下午", "同步", nowMs = 5_000L)
        store.recordBigram("下午", "同步", nowMs = 6_000L)

        val top2 = store.listTopBigrams(limit = 2)
        assertEquals(2, top2.size)
        assertEquals("明天", top2[0].prevWord)
        assertEquals("开会", top2[0].nextWord)
        assertEquals(3, top2[0].count)
        assertEquals("下午", top2[1].prevWord)
        assertEquals("同步", top2[1].nextWord)
        assertEquals(2, top2[1].count)
    }

    @Test
    fun `AppRepository hydrates inputStatsEnabled default true, supports clearHistory(alsoResetStats), and does not resurrect after reset`() {
        val publicStore = InMemoryKeyValueStore()
        // Simulate legacy pre-v3.9 settings JSON without `inputStatsEnabled`
        publicStore.putString(AppRepository.KEY_SETTINGS, """{"flypyEnabled":true}""")
        val repo = createInMemoryRepository(publicStore)

        assertTrue(repo.settings.value.inputStatsEnabled)

        val now = utcMillis(2026, 11, 23, hour = 10)
        repo.addHistory(
            HistoryRecord(
                timestamp = now,
                targetAppName = "微信",
                polishedText = "你好，下午三点我们同步一下方案。",
                durationMs = 5_000L,
                latencyMs = 380L,
                executionSource = "LOCAL"
            )
        )
        assertEquals(1, repo.history.value.size)
        assertEquals(1, repo.dailyStatsStore.listAllRecords().size)

        // Default clearHistory(alsoResetStats = false) clears history while preserving daily stats
        repo.clearHistory()
        assertTrue(repo.history.value.isEmpty())
        assertEquals(1, repo.dailyStatsStore.listAllRecords().size)

        val snapPreserved = repo.computeInsightsSnapshot(StatsTimeRange.ALL, nowMs = now)
        assertFalse(snapPreserved.isColdStart)
        assertEquals(1, snapPreserved.voiceCount)

        // Adding another history item and clearing with alsoResetStats = true resets both
        repo.addHistory(
            HistoryRecord(
                timestamp = now,
                targetAppName = "飞书",
                polishedText = "测试重置",
                durationMs = 4_000L,
                executionSource = "CLOUD"
            )
        )
        repo.clearHistory(alsoResetStats = true)
        assertTrue(repo.history.value.isEmpty())
        assertTrue(repo.dailyStatsStore.listAllRecords().isEmpty())

        // Disabling inputStatsEnabled stops recording on addHistory
        repo.setInputStatsEnabled(false)
        assertFalse(repo.settings.value.inputStatsEnabled)
        repo.addHistory(
            HistoryRecord(
                timestamp = now,
                targetAppName = "飞书",
                polishedText = "关闭统计后不应记录",
                durationMs = 4_000L
            )
        )
        assertEquals(1, repo.history.value.size)
        assertTrue(repo.dailyStatsStore.listAllRecords().isEmpty())

        // Re-initializing repository after explicit reset must NOT re-backfill old history
        val repoReloaded = createInMemoryRepository(publicStore)
        assertFalse(repoReloaded.settings.value.inputStatsEnabled)
        assertTrue(repoReloaded.dailyStatsStore.listAllRecords().isEmpty())
    }

    @Test
    fun `TypingController records snippet and prediction hits only when enabled and non-incognito`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = createInMemoryRepository()
            repo.updateSettings {
                it.copy(
                    inputScheme = InputScheme.FLYPY_TABLE,
                    snippets = listOf(
                        SnippetEntry(
                            id = "s1",
                            title = "邮箱",
                            voiceTriggers = "发邮箱",
                            flypyCode = "/yx",
                            content = "hello.typefree@example.com",
                            enabled = true
                        )
                    )
                )
            }
            val state = MutableStateFlow(
                ImeUiState(
                    tabMode = KeyboardTabMode.QWERTY,
                    isFlypyChineseMode = true,
                    targetAppName = "微信",
                    predictionCandidates = listOf(FlypyCandidate(text = "方案评审"))
                )
            )
            val host = object : ImeHostCallbacks {
                override val scope: CoroutineScope = scope
                override val repository: AppRepository = repo
                override val aiEngine: AiPipelineEngine = AiPipelineEngine()
                override val stateFlow: MutableStateFlow<ImeUiState> = state
                override fun currentInputConnection(): InputConnection? = null
                override fun performKeyFeedback(strong: Boolean) {}
                override fun performHaptic(strong: Boolean) {}
                override fun showQuickActionToast(message: String) {}
                override fun commitDirectText(
                    text: String,
                    recordUndo: Boolean,
                    markManualEdit: Boolean,
                    playFeedback: Boolean
                ) {}
                override fun pushUndoSnapshot(snapshot: UndoSnapshot?) {}
                override fun peekTopUndoSnapshot(): UndoSnapshot? = null
                override fun recordManualEditTouch() {}
                override fun tryAutoLearnFromRecentEdit() {}
                override fun onDictationCommittedForAutoLearn(fullBufferAfterCommit: String) {}
                override fun clearAutoLearnBaseline() {}
                override fun readSelectedTextSafely(): String = ""
                override fun readSurroundingBeforeCursor(maxChars: Int): String = ""
                override fun readFullEditorTextSafely(): String = ""
                override fun readSystemClipboardText(): String = ""
                override fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean) {}
                override fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File = wavFile
                override fun getLastRecordingWavFile(): File = File("dummy.wav")
                override fun hasRetryWavFile(): Boolean = false
            }

            val controller = TypingController(host = host)
            controller.updateSnippets(repo.settings.value.snippets)

            // 1. Trigger slash snippet `/yx` + Space
            controller.commitQwertyChar("/")
            controller.commitQwertyChar("y")
            controller.commitQwertyChar("x")
            controller.insertSpace()

            // 2. Tap L2 prediction candidate (re-seed after key presses cleared previous predictions)
            state.update {
                it.copy(predictionCandidates = listOf(FlypyCandidate(text = "方案评审")))
            }
            controller.selectPredictionCandidate(0)

            val day = repo.dailyStatsStore.listAllRecords().single()
            assertEquals(1, day.snippetHits)
            assertEquals("hello.typefree@example.com".length - "/yx".length, day.snippetSavedChars)
            assertEquals(1, day.predictionHits)
            assertEquals(4, day.predictionSavedKeys)

            // 3. In password field -> ZERO additional recording
            state.update {
                it.copy(
                    currentInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    predictionCandidates = listOf(FlypyCandidate(text = "密码联想"))
                )
            }
            controller.selectPredictionCandidate(0)
            val afterPwd = repo.dailyStatsStore.listAllRecords().single()
            assertEquals(1, afterPwd.predictionHits)
            assertEquals(4, afterPwd.predictionSavedKeys)

            // 4. In incognito mode -> ZERO additional recording
            state.update {
                it.copy(
                    currentInputType = InputType.TYPE_CLASS_TEXT,
                    isIncognitoMode = true,
                    predictionCandidates = listOf(FlypyCandidate(text = "无痕联想"))
                )
            }
            controller.setIncognitoMode(true)
            controller.selectPredictionCandidate(0)
            val afterIncognito = repo.dailyStatsStore.listAllRecords().single()
            assertEquals(1, afterIncognito.predictionHits)
            assertEquals(4, afterIncognito.predictionSavedKeys)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `VoiceController formatSuccessToastPreview adds subtle saved-minutes suffix only for long outputs`() {
        val shortText = "今天下午三点开会同步进度。"
        val shortPreview = VoiceController.formatSuccessToastPreview(shortText)
        assertEquals(shortText, shortPreview)
        assertFalse(shortPreview.contains("约省"))

        val longText = "这是一段超过八十个字的较长语音输出内容，用来验证当用户一口气口述一整段想法并润色上屏时，提示文案会在保留简洁预览的同时附带克制的省时反馈提示，不会过度打扰正常短句输入体验。"
        assertTrue(longText.length >= 80)
        val longPreview = VoiceController.formatSuccessToastPreview(longText)
        assertTrue("Expected saved minutes suffix, got: $longPreview", longPreview.contains("· 约省 "))
        assertTrue(longPreview.endsWith("分钟"))
    }
}
