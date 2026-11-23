package com.typefree.ime.data.stats

import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.DictionarySource
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.VoiceIntentMode
import com.typefree.ime.ime.jev.memory.AxisPreferenceStats
import com.typefree.ime.ime.jev.memory.BucketStyleProfile
import com.typefree.ime.ime.jev.memory.FeatureCounter
import com.typefree.ime.ime.jev.memory.JevAxisGroup
import com.typefree.ime.ime.jev.memory.JevPreferenceLearner
import com.typefree.ime.ime.jev.memory.RelationPragmaticMapper
import com.typefree.ime.ime.jev.memory.SessionMemoryRecord
import com.typefree.ime.ime.jev.memory.StyleFeatureKey
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Pure Kotlin aggregator building an immutable [InsightsSnapshot] from zero-PII daily rollup
 * records, optional recent history records, local Jev memory summaries, and L2 prediction counts.
 */
object InsightsAggregator {

    private const val MIN_VALID_WPM_DURATION_MS = 3_000L
    private const val MOBILE_TYPING_CHARS_PER_MIN = 42.0
    private const val PREDICTION_KEYS_PER_MIN = 90.0

    fun buildSnapshot(
        range: StatsTimeRange,
        dailyRecords: List<DailyStatsRecord>,
        history: List<HistoryRecord>,
        settings: AppSettings,
        jevSessions: List<SessionMemoryRecord>,
        jevBuckets: List<BucketStyleProfile>,
        personalBigramCount: Int,
        personalTrigramCount: Int,
        topWordChains: List<String>,
        dbSizeBytes: Long,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): InsightsSnapshot {
        val todayCal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
        val todayYear = todayCal.get(Calendar.YEAR)
        val todayMonth = todayCal.get(Calendar.MONTH) + 1
        val todayKey = calendarDateKey(todayCal)

        val day7StartCal = (todayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -6) }
        val day7StartKey = calendarDateKey(day7StartCal)

        val rangedDays = filterDailyByRange(
            records = dailyRecords,
            range = range,
            todayKey = todayKey,
            day7StartKey = day7StartKey,
            todayYear = todayYear,
            todayMonth = todayMonth
        )
        val rangedHistory = filterHistoryByRange(
            history = history,
            range = range,
            todayKey = todayKey,
            day7StartKey = day7StartKey,
            todayYear = todayYear,
            todayMonth = todayMonth,
            timeZone = timeZone
        )

        val voiceChars = rangedDays.sumOf { it.voiceChars.coerceAtLeast(0) }
        val keyChars = rangedDays.sumOf { it.keyChars.coerceAtLeast(0) }
        val totalChars = voiceChars + keyChars
        val voiceCount = rangedDays.sumOf { it.voiceCount.coerceAtLeast(0) }
        val keyCommits = rangedDays.sumOf { it.keyCommits.coerceAtLeast(0) }
        val snippetHits = rangedDays.sumOf { it.snippetHits.coerceAtLeast(0) }
        val snippetSavedChars = rangedDays.sumOf { it.snippetSavedChars.coerceAtLeast(0) }
        val predictionHits = rangedDays.sumOf { it.predictionHits.coerceAtLeast(0) }
        val predictionSavedKeys = rangedDays.sumOf { it.predictionSavedKeys.coerceAtLeast(0) }

        val lifetimeTotalChars = dailyRecords.sumOf { it.totalChars.coerceAtLeast(0) }
        val lifetimeVoiceCount = dailyRecords.sumOf { it.voiceCount.coerceAtLeast(0) }
        val lifetimeKeyCommits = dailyRecords.sumOf { it.keyCommits.coerceAtLeast(0) }
        val lifetimeActiveDays = dailyRecords.count { it.hasActivity }
        val activeDaysInRange = rangedDays.count { it.hasActivity }

        val jevSessionCount = jevSessions.size
        val jevTotalInteractions = maxOf(
            jevSessions.sumOf { it.interactionCount.coerceAtLeast(0) },
            dailyRecords.sumOf { it.jevAdopts.coerceAtLeast(0) }
        )

        val isColdStart = lifetimeTotalChars == 0 &&
            lifetimeVoiceCount == 0 &&
            lifetimeKeyCommits == 0 &&
            jevTotalInteractions == 0

        // Measured WPM: strictly from timed voice inputs >= 3s (never fake 220, never inflate with 0ms presets)
        val (hasMeasuredWpm, averageWpm, speedMultiplier) = computeMeasuredWpm(
            rangedDays = rangedDays,
            rangedHistory = rangedHistory
        )

        // Saved minutes: voice vs ~42 chars/min thumb typing + snippet saved chars / 42 + prediction saved keys / 90
        val rangedVoiceDurationMs = rangedDays.sumOf { it.voiceDurationMs.coerceAtLeast(0L) }
        val voiceSavedMin = ((voiceChars / MOBILE_TYPING_CHARS_PER_MIN) - (rangedVoiceDurationMs / 60_000.0))
            .coerceAtLeast(0.0)
        val snippetSavedMin = snippetSavedChars / MOBILE_TYPING_CHARS_PER_MIN
        val predictionSavedMin = predictionSavedKeys / PREDICTION_KEYS_PER_MIN
        val totalSavedMinRaw = voiceSavedMin + snippetSavedMin + predictionSavedMin
        val savedMinutes = when {
            totalSavedMinRaw >= 0.5 -> totalSavedMinRaw.roundToInt()
            totalSavedMinRaw > 0.08 && (voiceCount > 0 || snippetHits > 0 || predictionHits > 0) -> 1
            else -> 0
        }

        val latencySum = rangedDays.sumOf { it.voiceLatencySumMs.coerceAtLeast(0L) }
        val latencyCount = rangedDays.sumOf { it.voiceLatencyCount.coerceAtLeast(0) }
        val avgLatencyMs = if (latencyCount > 0) latencySum / latencyCount else 0L

        val hwRaw = rangedDays.sumOf { it.helpWriteRawChars.coerceAtLeast(0) }
        val hwPolished = rangedDays.sumOf { it.helpWritePolishedChars.coerceAtLeast(0) }
        val hwCount = rangedDays.sumOf { it.helpWriteCount.coerceAtLeast(0) }
        val helpWriteLeverage = if (hwCount > 0 && hwRaw > 0 && hwPolished > 0) {
            (hwPolished.toFloat() / hwRaw.toFloat()).coerceIn(0.5f, 25.0f)
        } else {
            0f
        }

        val offlineCount = rangedDays.sumOf { it.offlineCount.coerceAtLeast(0) }
        val cloudCount = rangedDays.sumOf { it.cloudCount.coerceAtLeast(0) }
        val splitTotal = offlineCount + cloudCount
        val hasOfflineSplitData = splitTotal > 0
        val offlinePercent = if (splitTotal > 0) {
            ((offlineCount * 100.0) / splitTotal).roundToInt().coerceIn(0, 100)
        } else {
            0
        }

        val byDateKey = HashMap<Int, DailyStatsRecord>(dailyRecords.size * 2)
        for (rec in dailyRecords) {
            val prev = byDateKey[rec.dateKey]
            byDateKey[rec.dateKey] = if (prev == null) rec else mergeSameDay(prev, rec)
        }

        val streakDays = computeStreakDays(byDateKey, todayCal)
        val mini7DayChars = buildMini7DayChars(byDateKey, todayCal)
        val heatmapDays = buildHeatmap56Days(byDateKey, todayCal)
        val monthlyTrend = buildMonthlyTrend(dailyRecords, todayYear, todayMonth)

        val hourlyVoiceChars = sumHourly(rangedDays) { it.hourlyVoiceChars }
        val hourlyKeyChars = sumHourly(rangedDays) { it.hourlyKeyChars }

        val schemeShares = buildSchemeShares(rangedDays)
        val intentShares = buildIntentShares(rangedDays)
        val topAppScenes = buildTopAppScenes(rangedDays)

        val jevDnaItems = buildJevStyleDnaItems(jevBuckets, jevSessions)
        val jevAxisTendency = buildJevAxisTendency(jevBuckets, jevSessions)

        val autoLearnedDictCount = settings.dictionary.count { it.source == DictionarySource.AUTO_LEARNED }
        val totalDictCount = settings.dictionary.size

        val milestones = buildMilestones(
            allDailyRecords = dailyRecords,
            jevDnaItems = jevDnaItems,
            jevTotalInteractions = jevTotalInteractions
        )

        val metaphorLine = buildMetaphorLine(
            totalChars = totalChars,
            savedMinutes = savedMinutes
        )
        val rhythmHeadline = buildRhythmHeadline(
            hourlyVoice = hourlyVoiceChars,
            hourlyKey = hourlyKeyChars
        )

        return InsightsSnapshot(
            range = range,
            isColdStart = isColdStart,
            totalChars = totalChars,
            voiceChars = voiceChars,
            keyChars = keyChars,
            voiceCount = voiceCount,
            keyCommits = keyCommits,
            savedMinutes = savedMinutes,
            averageWpm = averageWpm,
            hasMeasuredWpm = hasMeasuredWpm,
            speedMultiplier = speedMultiplier,
            avgLatencyMs = avgLatencyMs,
            helpWriteLeverage = helpWriteLeverage,
            offlinePercent = offlinePercent,
            hasOfflineSplitData = hasOfflineSplitData,
            streakDays = streakDays,
            activeDaysInRange = activeDaysInRange,
            lifetimeActiveDays = lifetimeActiveDays,
            lifetimeTotalChars = lifetimeTotalChars,
            metaphorLine = metaphorLine,
            rhythmHeadline = rhythmHeadline,
            mini7DayChars = mini7DayChars,
            heatmapDays = heatmapDays,
            monthlyTrend = monthlyTrend,
            hourlyVoiceChars = hourlyVoiceChars,
            hourlyKeyChars = hourlyKeyChars,
            schemeShares = schemeShares,
            intentShares = intentShares,
            topAppScenes = topAppScenes,
            snippetHits = snippetHits,
            snippetSavedChars = snippetSavedChars,
            predictionHits = predictionHits,
            predictionSavedKeys = predictionSavedKeys,
            jevDnaItems = jevDnaItems,
            jevAxisTendency = jevAxisTendency,
            jevSessionCount = jevSessionCount,
            jevTotalInteractions = jevTotalInteractions,
            autoLearnedDictCount = autoLearnedDictCount,
            totalDictCount = totalDictCount,
            personalBigramCount = personalBigramCount.coerceAtLeast(0),
            personalTrigramCount = personalTrigramCount.coerceAtLeast(0),
            topWordChains = topWordChains,
            milestones = milestones,
            statsDbSizeBytes = dbSizeBytes.coerceAtLeast(0L),
            statsTrackedDays = lifetimeActiveDays
        )
    }

    /**
     * Builds a pure Token & Cloud/Offline Voice Duration observability snapshot (strictly zero cost/price estimation).
     */
    fun buildTokenUsageSnapshot(
        range: StatsTimeRange,
        dailyRecords: List<DailyStatsRecord>,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): TokenUsageSnapshot {
        val todayCal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
        val todayYear = todayCal.get(Calendar.YEAR)
        val todayMonth = todayCal.get(Calendar.MONTH) + 1
        val todayKey = calendarDateKey(todayCal)
        val day7StartCal = (todayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -6) }
        val day7StartKey = calendarDateKey(day7StartCal)

        val rangedDays = filterDailyByRange(
            records = dailyRecords,
            range = range,
            todayKey = todayKey,
            day7StartKey = day7StartKey,
            todayYear = todayYear,
            todayMonth = todayMonth
        )

        val promptTokens = rangedDays.sumOf { it.cloudPromptTokens.coerceAtLeast(0L) }
        val cachedTokens = rangedDays.sumOf {
            it.cloudCachedTokens.coerceIn(0L, it.cloudPromptTokens.coerceAtLeast(0L))
        }.coerceAtMost(promptTokens)
        val uncachedPromptTokens = (promptTokens - cachedTokens).coerceAtLeast(0L)
        val visibleOutputTokens = rangedDays.sumOf { it.cloudCompletionTokens.coerceAtLeast(0L) }
        val reasoningTokens = rangedDays.sumOf { it.cloudReasoningTokens.coerceAtLeast(0L) }
        val totalTokens = promptTokens + visibleOutputTokens + reasoningTokens
        val cacheHitPercent = if (promptTokens > 0L) {
            ((cachedTokens * 100.0) / promptTokens.toDouble()).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        val estimatedCalls = rangedDays.sumOf { it.cloudEstimatedTokenCalls.coerceAtLeast(0) }
        val cloudVoiceDur = rangedDays.sumOf { it.cloudVoiceDurationMs.coerceAtLeast(0L) }
        val offlineVoiceDur = rangedDays.sumOf { it.offlineVoiceDurationMs.coerceAtLeast(0L) }
        val offlineSavedCalls = rangedDays.sumOf { it.offlineCount.coerceAtLeast(0) }

        val byFeature = buildTokenShareItems(
            records = rangedDays,
            selector = { it.tokenByFeature },
            labelProvider = { TokenFeatureKeys.displayNameForKey(it) },
            maxItems = 8,
            otherLabel = null
        )
        val byModel = buildTokenShareItems(
            records = rangedDays,
            selector = { it.tokenByModel },
            labelProvider = { it },
            maxItems = 6,
            otherLabel = "其他模型"
        )

        val hasData = totalTokens > 0L ||
            cloudVoiceDur > 0L ||
            offlineVoiceDur > 0L ||
            offlineSavedCalls > 0

        return TokenUsageSnapshot(
            range = range,
            hasData = hasData,
            totalTokens = totalTokens,
            promptTokens = promptTokens,
            cachedTokens = cachedTokens,
            uncachedPromptTokens = uncachedPromptTokens,
            visibleOutputTokens = visibleOutputTokens,
            reasoningTokens = reasoningTokens,
            cacheHitPercent = cacheHitPercent,
            estimatedCallCount = estimatedCalls,
            cloudVoiceDurationMs = cloudVoiceDur,
            offlineVoiceDurationMs = offlineVoiceDur,
            offlineSavedCloudCalls = offlineSavedCalls,
            byFeature = byFeature,
            byModel = byModel
        )
    }

    private fun buildTokenShareItems(
        records: List<DailyStatsRecord>,
        selector: (DailyStatsRecord) -> Map<String, Long>,
        labelProvider: (String) -> String,
        maxItems: Int,
        otherLabel: String?
    ): List<TokenShareItem> {
        val totals = LinkedHashMap<String, Long>()
        for (rec in records) {
            for ((k, v) in selector(rec)) {
                val clean = k.trim()
                if (clean.isNotEmpty() && v > 0L) {
                    totals[clean] = (totals[clean] ?: 0L) + v
                }
            }
        }
        if (totals.isEmpty()) return emptyList()
        val sorted = totals.entries.sortedByDescending { it.value }
        val collapsed: List<Pair<String, Long>> = if (otherLabel != null && sorted.size > maxItems) {
            val head = sorted.take(maxItems - 1).map { it.key to it.value }
            val tailSum = sorted.drop(maxItems - 1).sumOf { it.value }
            head + (otherLabel to tailSum)
        } else {
            sorted.take(maxItems).map { it.key to it.value }
        }
        val grandTotal = collapsed.sumOf { it.second }.coerceAtLeast(1L)
        val percents = computeNormalizedLongPercents(collapsed.map { it.second }, grandTotal)
        return collapsed.mapIndexed { idx, (key, tokens) ->
            TokenShareItem(
                key = key,
                label = if (key == otherLabel) otherLabel else labelProvider(key),
                tokens = tokens,
                percent = percents[idx]
            )
        }
    }

    private fun computeNormalizedLongPercents(values: List<Long>, total: Long): List<Int> {
        if (values.isEmpty()) return emptyList()
        val safeTotal = total.coerceAtLeast(1L).toDouble()
        val raw = values.map { ((it * 100.0) / safeTotal).roundToInt().coerceAtLeast(1) }.toMutableList()
        val diff = 100 - raw.sum()
        if (diff != 0) {
            val maxIdx = values.indices.maxByOrNull { values[it] } ?: 0
            raw[maxIdx] = (raw[maxIdx] + diff).coerceAtLeast(1)
        }
        return raw
    }

    private fun computeMeasuredWpm(
        rangedDays: List<DailyStatsRecord>,
        rangedHistory: List<HistoryRecord>
    ): Triple<Boolean, Int, Float> {
        val validTimedHistory = rangedHistory.filter {
            it.durationMs >= MIN_VALID_WPM_DURATION_MS && it.polishedText.isNotEmpty()
        }
        val histDurationMs = validTimedHistory.sumOf { it.durationMs }
        val histChars = validTimedHistory.sumOf { it.polishedText.length }

        val validTimedDays = rangedDays.filter {
            it.voiceDurationMs >= MIN_VALID_WPM_DURATION_MS && it.voiceChars > 0
        }
        val dayDurationMs = validTimedDays.sumOf { it.voiceDurationMs }
        val dayChars = validTimedDays.sumOf { it.voiceChars }

        // Prefer exact per-record timed history when it covers the measured voice duration so
        // 0ms quick-edit/preset outputs in the same day never distort spoken WPM.
        val (measuredChars, measuredDurationMs) = when {
            histDurationMs >= MIN_VALID_WPM_DURATION_MS &&
                histChars > 0 &&
                histDurationMs >= (dayDurationMs / 2L) -> histChars to histDurationMs
            dayDurationMs >= MIN_VALID_WPM_DURATION_MS && dayChars > 0 -> dayChars to dayDurationMs
            else -> return Triple(false, 0, 0f)
        }

        val minutes = measuredDurationMs / 60_000.0
        if (minutes <= 0.0) return Triple(false, 0, 0f)
        val wpm = (measuredChars / minutes).roundToInt().coerceIn(30, 360)
        val multiplier = (wpm / MOBILE_TYPING_CHARS_PER_MIN.toFloat()).coerceIn(1.0f, 8.0f)
        return Triple(true, wpm, multiplier)
    }

    private fun filterDailyByRange(
        records: List<DailyStatsRecord>,
        range: StatsTimeRange,
        todayKey: Int,
        day7StartKey: Int,
        todayYear: Int,
        todayMonth: Int
    ): List<DailyStatsRecord> {
        return when (range) {
            StatsTimeRange.DAYS_7 -> records.filter { it.dateKey in day7StartKey..todayKey }
            StatsTimeRange.THIS_MONTH -> {
                val targetYm = todayYear * 100 + todayMonth
                records.filter { (it.dateKey / 100) == targetYm }
            }
            StatsTimeRange.THIS_YEAR -> records.filter { (it.dateKey / 10000) == todayYear }
            StatsTimeRange.ALL -> records
        }
    }

    private fun filterHistoryByRange(
        history: List<HistoryRecord>,
        range: StatsTimeRange,
        todayKey: Int,
        day7StartKey: Int,
        todayYear: Int,
        todayMonth: Int,
        timeZone: TimeZone
    ): List<HistoryRecord> {
        if (history.isEmpty() || range == StatsTimeRange.ALL) return history
        val targetYm = todayYear * 100 + todayMonth
        return history.filter { rec ->
            val key = DailyStatsStore.toDateKey(rec.timestamp, timeZone)
            when (range) {
                StatsTimeRange.DAYS_7 -> key in day7StartKey..todayKey
                StatsTimeRange.THIS_MONTH -> (key / 100) == targetYm
                StatsTimeRange.THIS_YEAR -> (key / 10000) == todayYear
                StatsTimeRange.ALL -> true
            }
        }
    }

    private fun computeStreakDays(
        byDateKey: Map<Int, DailyStatsRecord>,
        todayCal: Calendar
    ): Int {
        val cursor = todayCal.clone() as Calendar
        val todayKey = calendarDateKey(cursor)
        if (byDateKey[todayKey]?.hasActivity != true) {
            cursor.add(Calendar.DAY_OF_YEAR, -1)
        }
        var streak = 0
        while (byDateKey[calendarDateKey(cursor)]?.hasActivity == true) {
            streak++
            cursor.add(Calendar.DAY_OF_YEAR, -1)
            if (streak >= 3650) break
        }
        return streak
    }

    private fun buildMini7DayChars(
        byDateKey: Map<Int, DailyStatsRecord>,
        todayCal: Calendar
    ): List<Int> {
        val result = ArrayList<Int>(7)
        for (offset in 6 downTo 0) {
            val c = (todayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -offset) }
            val key = calendarDateKey(c)
            result.add(byDateKey[key]?.totalChars ?: 0)
        }
        return result
    }

    private fun buildHeatmap56Days(
        byDateKey: Map<Int, DailyStatsRecord>,
        todayCal: Calendar
    ): List<HeatmapDayCell> {
        data class RawCell(
            val dateKey: Int,
            val dateLabel: String,
            val record: DailyStatsRecord?
        )

        val rawCells = ArrayList<RawCell>(56)
        for (offset in 55 downTo 0) {
            val c = (todayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -offset) }
            val key = calendarDateKey(c)
            val month = c.get(Calendar.MONTH) + 1
            val day = c.get(Calendar.DAY_OF_MONTH)
            rawCells.add(
                RawCell(
                    dateKey = key,
                    dateLabel = "${month}月${day}日",
                    record = byDateKey[key]
                )
            )
        }

        val activeTotals = rawCells
            .mapNotNull { it.record?.totalChars?.takeIf { total -> total > 0 } }
            .sorted()

        val maxVal = activeTotals.lastOrNull() ?: 0
        val q25 = if (activeTotals.size >= 4) activeTotals[(activeTotals.size * 0.25).toInt()] else 0
        val q50 = if (activeTotals.size >= 4) activeTotals[(activeTotals.size * 0.50).toInt()] else 0
        val q75 = if (activeTotals.size >= 4) activeTotals[(activeTotals.size * 0.75).toInt()] else 0
        val useRatioFallback = activeTotals.size < 4 || q25 == q75

        return rawCells.map { cell ->
            val rec = cell.record
            val total = rec?.totalChars ?: 0
            val level = when {
                rec == null || (!rec.hasActivity && total == 0) -> 0
                total == 0 -> 1
                maxVal <= 0 -> 1
                useRatioFallback -> {
                    val ratio = total.toFloat() / maxVal.toFloat()
                    when {
                        ratio <= 0.25f -> 1
                        ratio <= 0.55f -> 2
                        ratio <= 0.85f -> 3
                        else -> 4
                    }
                }
                total <= q25 -> 1
                total <= q50 -> 2
                total <= q75 -> 3
                else -> 4
            }
            val topApp = rec?.appCounts?.entries?.maxByOrNull { it.value }?.key.orEmpty()
            HeatmapDayCell(
                dateKey = cell.dateKey,
                dateLabel = cell.dateLabel,
                voiceChars = rec?.voiceChars ?: 0,
                keyChars = rec?.keyChars ?: 0,
                voiceCount = rec?.voiceCount ?: 0,
                topAppName = topApp,
                intensityLevel = level
            )
        }
    }

    private fun buildMonthlyTrend(
        dailyRecords: List<DailyStatsRecord>,
        year: Int,
        currentMonth: Int
    ): List<MonthlyTrendPoint> {
        val voiceByMonth = IntArray(13)
        val keyByMonth = IntArray(13)
        for (rec in dailyRecords) {
            if (rec.dateKey / 10000 != year) continue
            val month = (rec.dateKey / 100) % 100
            if (month in 1..12) {
                voiceByMonth[month] += rec.voiceChars.coerceAtLeast(0)
                keyByMonth[month] += rec.keyChars.coerceAtLeast(0)
            }
        }
        return (1..12).map { m ->
            MonthlyTrendPoint(
                year = year,
                month = m,
                label = "${m}月",
                voiceChars = voiceByMonth[m],
                keyChars = keyByMonth[m],
                isFuture = m > currentMonth
            )
        }
    }

    private fun sumHourly(
        records: List<DailyStatsRecord>,
        selector: (DailyStatsRecord) -> List<Int>
    ): List<Int> {
        val sums = IntArray(24)
        for (rec in records) {
            val list = selector(rec)
            val n = minOf(24, list.size)
            for (h in 0 until n) {
                sums[h] += list[h].coerceAtLeast(0)
            }
        }
        return sums.toList()
    }

    private fun buildSchemeShares(records: List<DailyStatsRecord>): List<SchemeShareItem> {
        val totals = LinkedHashMap<String, Int>()
        for (rec in records) {
            for ((k, v) in rec.schemeChars) {
                if (k.isNotBlank() && v > 0) {
                    totals[k] = (totals[k] ?: 0) + v
                }
            }
        }
        if (totals.isEmpty()) return emptyList()
        val sorted = totals.entries.sortedByDescending { it.value }
        val grandTotal = sorted.sumOf { it.value }.coerceAtLeast(1)
        val percents = computeNormalizedPercents(sorted.map { it.value }, grandTotal)
        return sorted.mapIndexed { idx, (key, chars) ->
            SchemeShareItem(
                key = key,
                displayName = SchemeStatsKeys.displayNameForKey(key),
                chars = chars,
                percent = percents[idx]
            )
        }
    }

    private fun buildIntentShares(records: List<DailyStatsRecord>): List<IntentShareItem> {
        val totals = LinkedHashMap<VoiceIntentMode, Int>()
        for (rec in records) {
            for ((k, v) in rec.intentCounts) {
                if (v <= 0) continue
                val mode = runCatching { VoiceIntentMode.valueOf(k) }.getOrNull() ?: continue
                totals[mode] = (totals[mode] ?: 0) + v
            }
        }
        if (totals.isEmpty()) return emptyList()
        val sorted = totals.entries.sortedByDescending { it.value }
        val grandTotal = sorted.sumOf { it.value }.coerceAtLeast(1)
        val percents = computeNormalizedPercents(sorted.map { it.value }, grandTotal)
        return sorted.mapIndexed { idx, (mode, count) ->
            IntentShareItem(
                mode = mode,
                label = mode.label,
                count = count,
                percent = percents[idx]
            )
        }
    }

    private fun buildTopAppScenes(records: List<DailyStatsRecord>): List<AppSceneShareItem> {
        val totals = LinkedHashMap<String, Int>()
        for (rec in records) {
            for ((app, count) in rec.appCounts) {
                val clean = app.trim()
                if (clean.isEmpty() || clean == "未知应用" || count <= 0) continue
                totals[clean] = (totals[clean] ?: 0) + count
            }
        }
        if (totals.isEmpty()) return emptyList()
        val grandTotal = totals.values.sum().coerceAtLeast(1)
        val topEntries = totals.entries
            .sortedByDescending { it.value }
            .take(6)
        return topEntries.map { (app, count) ->
            AppSceneShareItem(
                appName = app,
                sceneCategory = classifySceneCategory(app),
                count = count,
                percent = ((count * 100.0) / grandTotal).roundToInt().coerceIn(1, 100)
            )
        }
    }

    private fun buildJevStyleDnaItems(
        buckets: List<BucketStyleProfile>,
        sessions: List<SessionMemoryRecord>
    ): List<JevStyleDnaItem> {
        val globalBucket = buckets.firstOrNull { it.bucketKey == RelationPragmaticMapper.GLOBAL_BUCKET_KEY }
        val relationBuckets = buckets.filterNot { it.bucketKey == RelationPragmaticMapper.GLOBAL_BUCKET_KEY }
        val items = ArrayList<JevStyleDnaItem>()

        for (key in StyleFeatureKey.entries) {
            val candidates = ArrayList<FeatureCounter>()
            if (key.allowGlobalScope) {
                globalBucket?.featureCounters?.get(key)?.let { candidates.add(it) }
            }
            for (b in relationBuckets) {
                b.featureCounters[key]?.let { candidates.add(it) }
            }
            for (s in sessions) {
                s.featureCounters[key]?.let { candidates.add(it) }
            }

            if (candidates.isEmpty()) continue

            val relevant = candidates.filter { it.userOverride == true || (it.userOverride != false && (it.isActive || it.opportunityCount > 0)) }
            if (relevant.isEmpty()) continue

            val best = relevant.maxWithOrNull(
                compareBy<FeatureCounter> { it.userOverride == true }
                    .thenBy { it.effectiveActive }
                    .thenBy { it.opportunityCount }
                    .thenBy {
                        if (key == StyleFeatureKey.COMPRESS_LENGTH) {
                            1.0 - it.emaValue
                        } else {
                            it.emaValue
                        }
                    }
            ) ?: continue

            val required = requiredOpportunitiesFor(key)
            val isManual = best.userOverride == true
            val isActive = best.effectiveActive
            val emaPct = when {
                isManual && best.opportunityCount == 0 -> 100
                key == StyleFeatureKey.COMPRESS_LENGTH ->
                    ((1.0 - best.emaValue).coerceIn(0.0, 1.0) * 100.0).roundToInt()
                else -> (best.emaValue.coerceIn(0.0, 1.0) * 100.0).roundToInt()
            }
            val ruleText = JevPreferenceLearner.formatRuleText(key, best)
            val explanation = when {
                isManual ->
                    "你已手动锁定此项语体习惯：$ruleText，Jev 军师生成回复时将始终遵循。"
                isActive ->
                    "已在 ${best.opportunityCount} 次真实采纳中稳定识别到你的表达偏好（置信度 $emaPct%），Jev 军师会自动应用「$ruleText」。"
                else ->
                    "端侧正在安静观察你的改稿倾向（已累计 ${best.opportunityCount}/$required 次样本），稳定后将自动生效。"
            }

            items.add(
                JevStyleDnaItem(
                    key = key,
                    title = key.displayTitle,
                    isActive = isActive,
                    isManualOverride = isManual,
                    currentCount = best.opportunityCount.coerceAtLeast(0),
                    requiredCount = required,
                    emaPercent = emaPct.coerceIn(0, 100),
                    explanation = explanation
                )
            )
        }

        return items.sortedWith(
            compareByDescending<JevStyleDnaItem> { it.isActive }
                .thenByDescending { it.isManualOverride }
                .thenByDescending { it.currentCount.toFloat() / it.requiredCount.coerceAtLeast(1).toFloat() }
                .thenByDescending { it.currentCount }
        )
    }

    private fun buildJevAxisTendency(
        buckets: List<BucketStyleProfile>,
        sessions: List<SessionMemoryRecord>
    ): JevAxisTendencySummary? {
        val globalBucket = buckets.firstOrNull { it.bucketKey == RelationPragmaticMapper.GLOBAL_BUCKET_KEY }
        val globalHasSamples = globalBucket?.axisStats?.values?.any { it.sampleCount > 0 } == true

        val aggregated: Map<JevAxisGroup, AxisPreferenceStats> = when {
            globalHasSamples -> globalBucket!!.axisStats.mapValues { it.value.sanitized() }
            buckets.any { b -> b.axisStats.values.any { it.sampleCount > 0 } } ->
                aggregateAxisStats(buckets.map { it.axisStats })
            else ->
                aggregateAxisStats(sessions.map { it.axisStats })
        }

        val bestEntry = aggregated.entries
            .filter { it.value.sampleCount > 0 }
            .maxByOrNull { it.value.sampleCount }
            ?: return null

        val group = bestEntry.key
        val stats = bestEntry.value.sanitized()
        val percents = JevPreferenceLearner.normalizeToIntPercentages(stats.probs)
        return JevAxisTendencySummary(
            group = group,
            groupTitle = axisGroupTitle(group),
            sampleCount = stats.sampleCount,
            labels = group.axisLabels,
            percents = percents
        )
    }

    private fun aggregateAxisStats(
        maps: List<Map<JevAxisGroup, AxisPreferenceStats>>
    ): Map<JevAxisGroup, AxisPreferenceStats> {
        val result = LinkedHashMap<JevAxisGroup, AxisPreferenceStats>()
        for (group in JevAxisGroup.entries) {
            var totalSamples = 0
            var latestUpdated = 0L
            val weighted = DoubleArray(3)
            for (m in maps) {
                val s = m[group]?.sanitized() ?: continue
                if (s.sampleCount <= 0) continue
                totalSamples += s.sampleCount
                latestUpdated = maxOf(latestUpdated, s.updatedAtMs)
                for (i in 0..2) {
                    weighted[i] += s.probs[i] * s.sampleCount
                }
            }
            if (totalSamples > 0) {
                result[group] = AxisPreferenceStats(
                    probs = List(3) { i -> weighted[i] / totalSamples.toDouble() },
                    sampleCount = totalSamples,
                    updatedAtMs = latestUpdated
                ).sanitized()
            }
        }
        return result
    }

    private fun buildMilestones(
        allDailyRecords: List<DailyStatsRecord>,
        jevDnaItems: List<JevStyleDnaItem>,
        jevTotalInteractions: Int
    ): List<MilestoneItem> {
        val lifetimeChars = allDailyRecords.sumOf { it.totalChars.coerceAtLeast(0) }
        val lifetimeVoiceCount = allDailyRecords.sumOf { it.voiceCount.coerceAtLeast(0) }
        val lifetimeKeyChars = allDailyRecords.sumOf { it.keyChars.coerceAtLeast(0) }
        val lifetimeSnippetHits = allDailyRecords.sumOf { it.snippetHits.coerceAtLeast(0) }
        val lifetimeOffline = allDailyRecords.sumOf { it.offlineCount.coerceAtLeast(0) }
        val lifetimeCloud = allDailyRecords.sumOf { it.cloudCount.coerceAtLeast(0) }
        val lifetimeSplit = lifetimeOffline + lifetimeCloud
        val lifetimeOfflinePct = if (lifetimeSplit > 0) {
            ((lifetimeOffline * 100.0) / lifetimeSplit).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        val activeDnaCount = jevDnaItems.count { it.isActive }

        val charsUnlocked = lifetimeChars >= 10_000
        val charsTitle = if (lifetimeChars >= 100_000) "十万字表达" else "万字表达"
        val charsDesc = if (lifetimeChars >= 100_000) {
            "累计语音与键盘输出突破 100,000 字"
        } else {
            "累计语音与键盘输出满 10,000 字"
        }
        val charsPct = if (charsUnlocked) 100 else ((lifetimeChars * 100L) / 10_000L).toInt().coerceIn(0, 99)
        val charsProgressText = if (charsUnlocked) {
            "已达成 · 累计 ${formatCount(lifetimeChars)} 字"
        } else {
            "${formatCount(lifetimeChars)} / 10,000 字"
        }

        val voiceUnlocked = lifetimeVoiceCount >= 100
        val voicePct = if (voiceUnlocked) 100 else ((lifetimeVoiceCount * 100) / 100).coerceIn(0, 99)

        val keyUnlocked = lifetimeKeyChars >= 10_000
        val keyPct = if (keyUnlocked) 100 else ((lifetimeKeyChars * 100L) / 10_000L).toInt().coerceIn(0, 99)

        val snippetUnlocked = lifetimeSnippetHits >= 30
        val snippetPct = if (snippetUnlocked) 100 else ((lifetimeSnippetHits * 100) / 30).coerceIn(0, 99)

        val offlineUnlocked = lifetimeOffline >= 10 && lifetimeOfflinePct >= 50
        val offlinePctProgress = if (offlineUnlocked) {
            100
        } else {
            val countPct = ((lifetimeOffline * 100) / 10).coerceIn(0, 100)
            val sharePct = if (lifetimeOffline > 0) ((lifetimeOfflinePct * 100) / 50).coerceIn(0, 100) else 0
            minOf(countPct, sharePct).coerceIn(0, 99)
        }

        val jevUnlocked = activeDnaCount >= 2 || jevTotalInteractions >= 5
        val jevPctProgress = if (jevUnlocked) {
            100
        } else {
            maxOf(
                (activeDnaCount * 100) / 2,
                (jevTotalInteractions * 100) / 5
            ).coerceIn(0, 99)
        }

        return listOf(
            MilestoneItem(
                id = "chars_expression",
                glyph = "📖",
                title = charsTitle,
                description = charsDesc,
                unlocked = charsUnlocked,
                progressPercent = charsPct,
                progressText = charsProgressText
            ),
            MilestoneItem(
                id = "voice_master",
                glyph = "🎙",
                title = "口述达人",
                description = "累计完成 100 次语音输入",
                unlocked = voiceUnlocked,
                progressPercent = voicePct,
                progressText = if (voiceUnlocked) "已达成 · $lifetimeVoiceCount 次" else "$lifetimeVoiceCount / 100 次"
            ),
            MilestoneItem(
                id = "keyboard_artisan",
                glyph = "⌨",
                title = "键盘匠人",
                description = "键盘累计输出满 10,000 字",
                unlocked = keyUnlocked,
                progressPercent = keyPct,
                progressText = if (keyUnlocked) {
                    "已达成 · ${formatCount(lifetimeKeyChars)} 字"
                } else {
                    "${formatCount(lifetimeKeyChars)} / 10,000 字"
                }
            ),
            MilestoneItem(
                id = "snippet_magician",
                glyph = "⚡",
                title = "短语魔术师",
                description = "快捷短语累计命中 30 次",
                unlocked = snippetUnlocked,
                progressPercent = snippetPct,
                progressText = if (snippetUnlocked) "已达成 · $lifetimeSnippetHits 次" else "$lifetimeSnippetHits / 30 次"
            ),
            MilestoneItem(
                id = "offline_guardian",
                glyph = "🔒",
                title = "离线守护者",
                description = "本机离线推理满 10 次且占比 ≥ 50%",
                unlocked = offlineUnlocked,
                progressPercent = offlinePctProgress,
                progressText = if (offlineUnlocked) {
                    "已达成 · 离线占比 $lifetimeOfflinePct%"
                } else {
                    "$lifetimeOffline/10 次 · 占比 $lifetimeOfflinePct%"
                }
            ),
            MilestoneItem(
                id = "jev_partner",
                glyph = "✦",
                title = "军师默契派",
                description = "习得 2 条语体习惯或采纳军师 5 次",
                unlocked = jevUnlocked,
                progressPercent = jevPctProgress,
                progressText = if (jevUnlocked) {
                    "已达成 · $activeDnaCount 条习惯 / $jevTotalInteractions 次采纳"
                } else {
                    "$activeDnaCount/2 习惯 · $jevTotalInteractions/5 采纳"
                }
            )
        )
    }

    private fun buildMetaphorLine(totalChars: Int, savedMinutes: Int): String {
        return when {
            totalChars <= 0 -> "每一次开口与落键，都在本地为你沉淀静默的数字注脚。"
            totalChars >= 100_000 -> "累计输出约等于一本中篇小说，想法从未因打字速度而停顿。"
            totalChars >= 20_000 -> "约等于 ${totalChars / 1500} 篇深度长文，表达节奏从容顺手。"
            totalChars >= 3_000 -> "相当于 ${maxOf(1, totalChars / 800)} 篇日常随笔，语音与键盘配合默契。"
            savedMinutes >= 5 -> "这段时间已悄悄为你省下约 $savedMinutes 分钟手动敲击。"
            else -> "已在本地轻声记录 ${formatCount(totalChars)} 字表达，越用越顺手。"
        }
    }

    private fun buildRhythmHeadline(hourlyVoice: List<Int>, hourlyKey: List<Int>): String {
        val combined = IntArray(24) { h ->
            hourlyVoice.getOrElse(h) { 0 }.coerceAtLeast(0) +
                hourlyKey.getOrElse(h) { 0 }.coerceAtLeast(0)
        }
        val total = combined.sum()
        if (total <= 0) {
            return "开始输入后，这里将浮现你的 24 小时表达节律"
        }
        var peakHour = 0
        var peakVal = -1
        for (h in 0..23) {
            if (combined[h] > peakVal) {
                peakVal = combined[h]
                peakHour = h
            }
        }
        val rangeStr = formatHourWindow(peakHour)
        return when (peakHour) {
            in 5..8 -> "清晨思绪清晰，$rangeStr 是你的表达高峰"
            in 9..11 -> "上午专注高效，$rangeStr 输出最为集中"
            in 12..13 -> "午间节奏轻快，$rangeStr 表达最活跃"
            in 14..17 -> "午后表达渐入佳境，$rangeStr 迎来峰值"
            in 18..21 -> "傍晚与晚间灵感充沛，$rangeStr 最爱表达"
            else -> "偏爱静谧夜色，$rangeStr 是你的灵感高光时段"
        }
    }

    private fun formatHourWindow(hour: Int): String {
        val h1 = hour.coerceIn(0, 23)
        val h2 = (h1 + 1) % 24
        return String.format(Locale.US, "%02d:00-%02d:00", h1, h2)
    }

    internal fun requiredOpportunitiesFor(key: StyleFeatureKey): Int {
        return when (key) {
            StyleFeatureKey.DROP_TRAILING_PERIOD -> 5
            StyleFeatureKey.DAMPEN_EXCLAMATION_TILDE -> 5
            StyleFeatureKey.STRIP_EMOJI -> 4
            StyleFeatureKey.HONORIFIC_NIN_TO_NI -> 3
            StyleFeatureKey.HONORIFIC_NI_TO_NIN -> 3
            StyleFeatureKey.TRIM_MODAL_PARTICLE -> 5
            StyleFeatureKey.TRIM_POLITE_PREFIX -> 5
            StyleFeatureKey.COMPRESS_LENGTH -> 6
        }
    }

    private fun axisGroupTitle(group: JevAxisGroup): String {
        return when (group) {
            JevAxisGroup.PEER_TECH -> "技术与同频探讨"
            JevAxisGroup.TKI_GAME -> "沟通博弈策略"
            JevAxisGroup.GROUP_BYSTANDER -> "群聊旁观互动"
            JevAxisGroup.BOTTOM_LINE_LOCKED -> "底牌锁定表达"
        }
    }

    internal fun classifySceneCategory(appName: String): String {
        val lower = appName.lowercase(Locale.ROOT)
        return when {
            lower.contains("微信") && !lower.contains("企业") ||
                lower == "qq" ||
                lower.contains("telegram") ||
                lower.contains("whatsapp") ||
                lower.contains("小红书") ||
                lower.contains("微博") ||
                lower.contains("抖音") ||
                lower.contains("豆瓣") -> "即时社交"
            lower.contains("飞书") ||
                lower.contains("lark") ||
                lower.contains("钉钉") ||
                lower.contains("企业微信") ||
                lower.contains("企微") ||
                lower.contains("slack") ||
                lower.contains("teams") -> "职场协作"
            lower.contains("mail") ||
                lower.contains("邮箱") ||
                lower.contains("outlook") ||
                lower.contains("notion") ||
                lower.contains("wps") ||
                lower.contains("备忘") ||
                lower.contains("笔记") ||
                lower.contains("docs") ||
                lower.contains("obsidian") -> "邮件与文档"
            lower.contains("termux") ||
                lower.contains("github") ||
                lower.contains("chatgpt") ||
                lower.contains("claude") ||
                lower.contains("gemini") ||
                lower.contains("deepseek") ||
                lower.contains("终端") ||
                lower.contains("code") -> "编程与 AI"
            lower.contains("胶囊") -> "跨应用快输"
            else -> "日常表达"
        }
    }

    private fun computeNormalizedPercents(values: List<Int>, total: Int): List<Int> {
        if (values.isEmpty()) return emptyList()
        val safeTotal = total.coerceAtLeast(1).toDouble()
        val raw = values.map { ((it * 100.0) / safeTotal).roundToInt().coerceAtLeast(1) }.toMutableList()
        val diff = 100 - raw.sum()
        if (diff != 0) {
            val maxIdx = values.indices.maxByOrNull { values[it] } ?: 0
            raw[maxIdx] = (raw[maxIdx] + diff).coerceAtLeast(1)
        }
        return raw
    }

    private fun mergeSameDay(a: DailyStatsRecord, b: DailyStatsRecord): DailyStatsRecord {
        return DailyStatsRecord(
            dateKey = a.dateKey,
            voiceChars = a.voiceChars + b.voiceChars,
            voiceCount = a.voiceCount + b.voiceCount,
            voiceDurationMs = a.voiceDurationMs + b.voiceDurationMs,
            voiceLatencySumMs = a.voiceLatencySumMs + b.voiceLatencySumMs,
            voiceLatencyCount = a.voiceLatencyCount + b.voiceLatencyCount,
            helpWriteRawChars = a.helpWriteRawChars + b.helpWriteRawChars,
            helpWritePolishedChars = a.helpWritePolishedChars + b.helpWritePolishedChars,
            helpWriteCount = a.helpWriteCount + b.helpWriteCount,
            keyChars = a.keyChars + b.keyChars,
            keyCommits = a.keyCommits + b.keyCommits,
            offlineCount = a.offlineCount + b.offlineCount,
            cloudCount = a.cloudCount + b.cloudCount,
            snippetHits = a.snippetHits + b.snippetHits,
            snippetSavedChars = a.snippetSavedChars + b.snippetSavedChars,
            predictionHits = a.predictionHits + b.predictionHits,
            predictionSavedKeys = a.predictionSavedKeys + b.predictionSavedKeys,
            jevAdopts = a.jevAdopts + b.jevAdopts,
            hourlyVoiceChars = List(24) { h ->
                a.hourlyVoiceChars.getOrElse(h) { 0 } + b.hourlyVoiceChars.getOrElse(h) { 0 }
            },
            hourlyKeyChars = List(24) { h ->
                a.hourlyKeyChars.getOrElse(h) { 0 } + b.hourlyKeyChars.getOrElse(h) { 0 }
            },
            schemeChars = mergeMaps(a.schemeChars, b.schemeChars),
            intentCounts = mergeMaps(a.intentCounts, b.intentCounts),
            appCounts = mergeMaps(a.appCounts, b.appCounts),
            styleCounts = mergeMaps(a.styleCounts, b.styleCounts),
            cloudPromptTokens = a.cloudPromptTokens + b.cloudPromptTokens,
            cloudCachedTokens = (a.cloudCachedTokens + b.cloudCachedTokens)
                .coerceAtMost(a.cloudPromptTokens + b.cloudPromptTokens),
            cloudCompletionTokens = a.cloudCompletionTokens + b.cloudCompletionTokens,
            cloudReasoningTokens = a.cloudReasoningTokens + b.cloudReasoningTokens,
            cloudEstimatedTokenCalls = a.cloudEstimatedTokenCalls + b.cloudEstimatedTokenCalls,
            cloudVoiceDurationMs = a.cloudVoiceDurationMs + b.cloudVoiceDurationMs,
            offlineVoiceDurationMs = a.offlineVoiceDurationMs + b.offlineVoiceDurationMs,
            tokenByModel = mergeLongMaps(a.tokenByModel, b.tokenByModel),
            tokenByFeature = mergeLongMaps(a.tokenByFeature, b.tokenByFeature)
        )
    }

    private fun mergeMaps(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = LinkedHashMap<String, Int>(a.size + b.size)
        out.putAll(a)
        for ((k, v) in b) {
            out[k] = (out[k] ?: 0) + v
        }
        return out
    }

    private fun mergeLongMaps(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = LinkedHashMap<String, Long>(a.size + b.size)
        out.putAll(a)
        for ((k, v) in b) {
            out[k] = (out[k] ?: 0L) + v
        }
        return out
    }

    private fun calendarDateKey(cal: Calendar): Int {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return y * 10000 + m * 100 + d
    }

    private fun formatCount(n: Int): String {
        return String.format(Locale.US, "%,d", n.coerceAtLeast(0))
    }
}
