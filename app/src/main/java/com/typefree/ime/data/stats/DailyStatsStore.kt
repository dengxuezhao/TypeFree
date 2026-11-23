package com.typefree.ime.data.stats

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.typefree.ime.data.HistoryRecord
import com.typefree.ime.data.VoiceIntentMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Storage backend contract for zero-PII daily numeric rollup statistics (`daily_input_stats`).
 */
interface DailyStatsBackend {
    fun loadAll(): List<DailyStatsRecord>
    fun upsertBatch(records: List<DailyStatsRecord>)
    fun deleteAll()
    fun getDbSizeBytes(): Long
}

/**
 * Pure in-memory backend for JVM unit tests and context-less [com.typefree.ime.data.AppRepository] instances.
 */
class InMemoryDailyStatsBackend : DailyStatsBackend {
    private val rows = ConcurrentHashMap<Int, DailyStatsRecord>()

    override fun loadAll(): List<DailyStatsRecord> {
        return rows.values.sortedBy { it.dateKey }
    }

    override fun upsertBatch(records: List<DailyStatsRecord>) {
        for (record in records) {
            rows[record.dateKey] = record
        }
    }

    override fun deleteAll() {
        rows.clear()
    }

    override fun getDbSizeBytes(): Long = 0L

    fun snapshotSize(): Int = rows.size
}

/**
 * SQLite persistence backend (`typefree_daily_stats.db`, table `daily_input_stats`)
 * storing strictly numeric counts, durations, and coarse bucketed maps — zero raw text.
 */
class SqliteDailyStatsBackend(
    context: Context
) : SQLiteOpenHelper(
    context.applicationContext ?: context,
    DB_NAME,
    null,
    DB_VERSION
), DailyStatsBackend {

    private val appContext: Context = context.applicationContext ?: context
    private val gson: Gson = GsonBuilder().create()
    private val intListType = object : TypeToken<List<Int>>() {}.type
    private val stringIntMapType = object : TypeToken<Map<String?, Int?>>() {}.type
    private val stringNumberMapType = object : TypeToken<Map<String?, Number?>>() {}.type

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DAILY_STATS (
                date_key INTEGER PRIMARY KEY NOT NULL,
                voice_chars INTEGER NOT NULL DEFAULT 0,
                voice_count INTEGER NOT NULL DEFAULT 0,
                voice_duration_ms INTEGER NOT NULL DEFAULT 0,
                voice_latency_sum_ms INTEGER NOT NULL DEFAULT 0,
                voice_latency_count INTEGER NOT NULL DEFAULT 0,
                help_write_raw_chars INTEGER NOT NULL DEFAULT 0,
                help_write_polished_chars INTEGER NOT NULL DEFAULT 0,
                help_write_count INTEGER NOT NULL DEFAULT 0,
                key_chars INTEGER NOT NULL DEFAULT 0,
                key_commits INTEGER NOT NULL DEFAULT 0,
                offline_count INTEGER NOT NULL DEFAULT 0,
                cloud_count INTEGER NOT NULL DEFAULT 0,
                snippet_hits INTEGER NOT NULL DEFAULT 0,
                snippet_saved_chars INTEGER NOT NULL DEFAULT 0,
                prediction_hits INTEGER NOT NULL DEFAULT 0,
                prediction_saved_keys INTEGER NOT NULL DEFAULT 0,
                jev_adopts INTEGER NOT NULL DEFAULT 0,
                hourly_voice_json TEXT NOT NULL DEFAULT '[]',
                hourly_key_json TEXT NOT NULL DEFAULT '[]',
                scheme_chars_json TEXT NOT NULL DEFAULT '{}',
                intent_counts_json TEXT NOT NULL DEFAULT '{}',
                app_counts_json TEXT NOT NULL DEFAULT '{}',
                style_counts_json TEXT NOT NULL DEFAULT '{}',
                cloud_prompt_tokens INTEGER NOT NULL DEFAULT 0,
                cloud_cached_tokens INTEGER NOT NULL DEFAULT 0,
                cloud_completion_tokens INTEGER NOT NULL DEFAULT 0,
                cloud_reasoning_tokens INTEGER NOT NULL DEFAULT 0,
                cloud_estimated_token_calls INTEGER NOT NULL DEFAULT 0,
                cloud_voice_duration_ms INTEGER NOT NULL DEFAULT 0,
                offline_voice_duration_ms INTEGER NOT NULL DEFAULT 0,
                token_by_model_json TEXT NOT NULL DEFAULT '{}',
                token_by_feature_json TEXT NOT NULL DEFAULT '{}'
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
        if (oldVersion < 2) {
            ensureV2Columns(db)
        }
    }

    private fun ensureV2Columns(db: SQLiteDatabase) {
        val existingCols = HashSet<String>()
        db.rawQuery("PRAGMA table_info($TABLE_DAILY_STATS)", null).use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            if (nameIdx >= 0) {
                while (cursor.moveToNext()) {
                    cursor.getString(nameIdx)?.let { existingCols.add(it) }
                }
            }
        }
        val v2Additions = listOf(
            "cloud_prompt_tokens" to "INTEGER NOT NULL DEFAULT 0",
            "cloud_cached_tokens" to "INTEGER NOT NULL DEFAULT 0",
            "cloud_completion_tokens" to "INTEGER NOT NULL DEFAULT 0",
            "cloud_reasoning_tokens" to "INTEGER NOT NULL DEFAULT 0",
            "cloud_estimated_token_calls" to "INTEGER NOT NULL DEFAULT 0",
            "cloud_voice_duration_ms" to "INTEGER NOT NULL DEFAULT 0",
            "offline_voice_duration_ms" to "INTEGER NOT NULL DEFAULT 0",
            "token_by_model_json" to "TEXT NOT NULL DEFAULT '{}'",
            "token_by_feature_json" to "TEXT NOT NULL DEFAULT '{}'"
        )
        for ((colName, colDef) in v2Additions) {
            if (colName !in existingCols) {
                db.execSQL("ALTER TABLE $TABLE_DAILY_STATS ADD COLUMN $colName $colDef")
            }
        }
    }

    override fun loadAll(): List<DailyStatsRecord> {
        return safeDbOp(defaultValue = emptyList()) {
            val db = readableDatabase
            val out = ArrayList<DailyStatsRecord>()
            db.query(
                TABLE_DAILY_STATS,
                null,
                null,
                null,
                null,
                null,
                "date_key ASC"
            ).use { c ->
                val dateKeyIdx = c.getColumnIndexOrThrow("date_key")
                val voiceCharsIdx = c.getColumnIndexOrThrow("voice_chars")
                val voiceCountIdx = c.getColumnIndexOrThrow("voice_count")
                val voiceDurIdx = c.getColumnIndexOrThrow("voice_duration_ms")
                val latSumIdx = c.getColumnIndexOrThrow("voice_latency_sum_ms")
                val latCntIdx = c.getColumnIndexOrThrow("voice_latency_count")
                val hwRawIdx = c.getColumnIndexOrThrow("help_write_raw_chars")
                val hwPolIdx = c.getColumnIndexOrThrow("help_write_polished_chars")
                val hwCntIdx = c.getColumnIndexOrThrow("help_write_count")
                val keyCharsIdx = c.getColumnIndexOrThrow("key_chars")
                val keyCommitsIdx = c.getColumnIndexOrThrow("key_commits")
                val offlineIdx = c.getColumnIndexOrThrow("offline_count")
                val cloudIdx = c.getColumnIndexOrThrow("cloud_count")
                val snipHitsIdx = c.getColumnIndexOrThrow("snippet_hits")
                val snipSavedIdx = c.getColumnIndexOrThrow("snippet_saved_chars")
                val predHitsIdx = c.getColumnIndexOrThrow("prediction_hits")
                val predSavedIdx = c.getColumnIndexOrThrow("prediction_saved_keys")
                val jevAdoptsIdx = c.getColumnIndexOrThrow("jev_adopts")
                val hourlyVoiceIdx = c.getColumnIndexOrThrow("hourly_voice_json")
                val hourlyKeyIdx = c.getColumnIndexOrThrow("hourly_key_json")
                val schemeIdx = c.getColumnIndexOrThrow("scheme_chars_json")
                val intentIdx = c.getColumnIndexOrThrow("intent_counts_json")
                val appIdx = c.getColumnIndexOrThrow("app_counts_json")
                val styleIdx = c.getColumnIndexOrThrow("style_counts_json")
                val promptTokIdx = c.getColumnIndex("cloud_prompt_tokens")
                val cachedTokIdx = c.getColumnIndex("cloud_cached_tokens")
                val compTokIdx = c.getColumnIndex("cloud_completion_tokens")
                val reasonTokIdx = c.getColumnIndex("cloud_reasoning_tokens")
                val estCallsIdx = c.getColumnIndex("cloud_estimated_token_calls")
                val cloudVoiceDurIdx = c.getColumnIndex("cloud_voice_duration_ms")
                val offlineVoiceDurIdx = c.getColumnIndex("offline_voice_duration_ms")
                val tokByModelIdx = c.getColumnIndex("token_by_model_json")
                val tokByFeatureIdx = c.getColumnIndex("token_by_feature_json")

                while (c.moveToNext()) {
                    val dateKey = c.getInt(dateKeyIdx)
                    if (dateKey <= 0) continue
                    val promptTok = if (promptTokIdx >= 0) c.getLong(promptTokIdx).coerceAtLeast(0L) else 0L
                    val cachedTok = if (cachedTokIdx >= 0) c.getLong(cachedTokIdx).coerceIn(0L, promptTok) else 0L
                    out.add(
                        DailyStatsRecord(
                            dateKey = dateKey,
                            voiceChars = c.getInt(voiceCharsIdx).coerceAtLeast(0),
                            voiceCount = c.getInt(voiceCountIdx).coerceAtLeast(0),
                            voiceDurationMs = c.getLong(voiceDurIdx).coerceAtLeast(0L),
                            voiceLatencySumMs = c.getLong(latSumIdx).coerceAtLeast(0L),
                            voiceLatencyCount = c.getInt(latCntIdx).coerceAtLeast(0),
                            helpWriteRawChars = c.getInt(hwRawIdx).coerceAtLeast(0),
                            helpWritePolishedChars = c.getInt(hwPolIdx).coerceAtLeast(0),
                            helpWriteCount = c.getInt(hwCntIdx).coerceAtLeast(0),
                            keyChars = c.getInt(keyCharsIdx).coerceAtLeast(0),
                            keyCommits = c.getInt(keyCommitsIdx).coerceAtLeast(0),
                            offlineCount = c.getInt(offlineIdx).coerceAtLeast(0),
                            cloudCount = c.getInt(cloudIdx).coerceAtLeast(0),
                            snippetHits = c.getInt(snipHitsIdx).coerceAtLeast(0),
                            snippetSavedChars = c.getInt(snipSavedIdx).coerceAtLeast(0),
                            predictionHits = c.getInt(predHitsIdx).coerceAtLeast(0),
                            predictionSavedKeys = c.getInt(predSavedIdx).coerceAtLeast(0),
                            jevAdopts = c.getInt(jevAdoptsIdx).coerceAtLeast(0),
                            hourlyVoiceChars = parseHourlyList(c.getString(hourlyVoiceIdx)),
                            hourlyKeyChars = parseHourlyList(c.getString(hourlyKeyIdx)),
                            schemeChars = parseCountMap(c.getString(schemeIdx)),
                            intentCounts = parseCountMap(c.getString(intentIdx)),
                            appCounts = parseCountMap(c.getString(appIdx)),
                            styleCounts = parseCountMap(c.getString(styleIdx)),
                            cloudPromptTokens = promptTok,
                            cloudCachedTokens = cachedTok,
                            cloudCompletionTokens = if (compTokIdx >= 0) c.getLong(compTokIdx).coerceAtLeast(0L) else 0L,
                            cloudReasoningTokens = if (reasonTokIdx >= 0) c.getLong(reasonTokIdx).coerceAtLeast(0L) else 0L,
                            cloudEstimatedTokenCalls = if (estCallsIdx >= 0) c.getInt(estCallsIdx).coerceAtLeast(0) else 0,
                            cloudVoiceDurationMs = if (cloudVoiceDurIdx >= 0) c.getLong(cloudVoiceDurIdx).coerceAtLeast(0L) else 0L,
                            offlineVoiceDurationMs = if (offlineVoiceDurIdx >= 0) c.getLong(offlineVoiceDurIdx).coerceAtLeast(0L) else 0L,
                            tokenByModel = if (tokByModelIdx >= 0) parseLongCountMap(c.getString(tokByModelIdx)) else emptyMap(),
                            tokenByFeature = if (tokByFeatureIdx >= 0) parseLongCountMap(c.getString(tokByFeatureIdx)) else emptyMap()
                        )
                    )
                }
            }
            out
        }
    }

    override fun upsertBatch(records: List<DailyStatsRecord>) {
        if (records.isEmpty()) return
        safeDbOp(defaultValue = Unit) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (record in records) {
                    val values = ContentValues().apply {
                        put("date_key", record.dateKey)
                        put("voice_chars", record.voiceChars.coerceAtLeast(0))
                        put("voice_count", record.voiceCount.coerceAtLeast(0))
                        put("voice_duration_ms", record.voiceDurationMs.coerceAtLeast(0L))
                        put("voice_latency_sum_ms", record.voiceLatencySumMs.coerceAtLeast(0L))
                        put("voice_latency_count", record.voiceLatencyCount.coerceAtLeast(0))
                        put("help_write_raw_chars", record.helpWriteRawChars.coerceAtLeast(0))
                        put("help_write_polished_chars", record.helpWritePolishedChars.coerceAtLeast(0))
                        put("help_write_count", record.helpWriteCount.coerceAtLeast(0))
                        put("key_chars", record.keyChars.coerceAtLeast(0))
                        put("key_commits", record.keyCommits.coerceAtLeast(0))
                        put("offline_count", record.offlineCount.coerceAtLeast(0))
                        put("cloud_count", record.cloudCount.coerceAtLeast(0))
                        put("snippet_hits", record.snippetHits.coerceAtLeast(0))
                        put("snippet_saved_chars", record.snippetSavedChars.coerceAtLeast(0))
                        put("prediction_hits", record.predictionHits.coerceAtLeast(0))
                        put("prediction_saved_keys", record.predictionSavedKeys.coerceAtLeast(0))
                        put("jev_adopts", record.jevAdopts.coerceAtLeast(0))
                        put("hourly_voice_json", gson.toJson(normalizeHourlyList(record.hourlyVoiceChars)))
                        put("hourly_key_json", gson.toJson(normalizeHourlyList(record.hourlyKeyChars)))
                        put("scheme_chars_json", gson.toJson(record.schemeChars))
                        put("intent_counts_json", gson.toJson(record.intentCounts))
                        put("app_counts_json", gson.toJson(record.appCounts))
                        put("style_counts_json", gson.toJson(record.styleCounts))
                        val safePrompt = record.cloudPromptTokens.coerceAtLeast(0L)
                        put("cloud_prompt_tokens", safePrompt)
                        put("cloud_cached_tokens", record.cloudCachedTokens.coerceIn(0L, safePrompt))
                        put("cloud_completion_tokens", record.cloudCompletionTokens.coerceAtLeast(0L))
                        put("cloud_reasoning_tokens", record.cloudReasoningTokens.coerceAtLeast(0L))
                        put("cloud_estimated_token_calls", record.cloudEstimatedTokenCalls.coerceAtLeast(0))
                        put("cloud_voice_duration_ms", record.cloudVoiceDurationMs.coerceAtLeast(0L))
                        put("offline_voice_duration_ms", record.offlineVoiceDurationMs.coerceAtLeast(0L))
                        put("token_by_model_json", gson.toJson(record.tokenByModel))
                        put("token_by_feature_json", gson.toJson(record.tokenByFeature))
                    }
                    db.insertWithOnConflict(
                        TABLE_DAILY_STATS,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun deleteAll() {
        safeDbOp(defaultValue = Unit) {
            writableDatabase.delete(TABLE_DAILY_STATS, null, null)
        }
    }

    override fun getDbSizeBytes(): Long {
        return runCatching {
            val dbFile = appContext.getDatabasePath(DB_NAME) ?: return 0L
            var total = 0L
            if (dbFile.exists()) total += dbFile.length()
            val walFile = File("${dbFile.path}-wal")
            if (walFile.exists()) total += walFile.length()
            val journalFile = File("${dbFile.path}-journal")
            if (journalFile.exists()) total += journalFile.length()
            total.coerceAtLeast(0L)
        }.getOrDefault(0L)
    }

    private fun parseHourlyList(rawJson: String?): List<Int> {
        if (rawJson.isNullOrBlank()) return List(24) { 0 }
        val parsed = runCatching {
            gson.fromJson<List<Int>>(rawJson, intListType)
        }.getOrNull() ?: return List(24) { 0 }
        return normalizeHourlyList(parsed)
    }

    private fun normalizeHourlyList(raw: List<Int>): List<Int> {
        return List(24) { idx -> raw.getOrElse(idx) { 0 }.coerceAtLeast(0) }
    }

    private fun parseCountMap(rawJson: String?): Map<String, Int> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        val parsed = runCatching {
            gson.fromJson<Map<String?, Int?>>(rawJson, stringIntMapType)
        }.getOrNull() ?: return emptyMap()
        if (parsed.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Int>(parsed.size)
        for ((k, v) in parsed) {
            val cleanKey = k?.trim().orEmpty()
            val count = v ?: 0
            if (cleanKey.isNotEmpty() && count > 0) {
                out[cleanKey] = count
            }
        }
        return out
    }

    private fun parseLongCountMap(rawJson: String?): Map<String, Long> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        val parsed = runCatching {
            gson.fromJson<Map<String?, Number?>>(rawJson, stringNumberMapType)
        }.getOrNull() ?: return emptyMap()
        if (parsed.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Long>(parsed.size)
        for ((k, v) in parsed) {
            val cleanKey = k?.trim().orEmpty()
            val count = v?.toLong() ?: 0L
            if (cleanKey.isNotEmpty() && count > 0L) {
                out[cleanKey] = count
            }
        }
        return out
    }

    private fun <T> safeDbOp(defaultValue: T, block: () -> T): T {
        return try {
            block()
        } catch (_: SQLiteDatabaseCorruptException) {
            runCatching {
                close()
                appContext.deleteDatabase(DB_NAME)
            }
            defaultValue
        } catch (_: Exception) {
            defaultValue
        }
    }

    companion object {
        const val DB_NAME = "typefree_daily_stats.db"
        const val DB_VERSION = 2
        const val TABLE_DAILY_STATS = "daily_input_stats"
    }
}

/**
 * High-performance, privacy-first daily numeric statistics store:
 * - Zero raw typed text or words are ever persisted — only daily numeric counts, durations, and bucket maps.
 * - Hot-path typing/voice mutations update an in-memory [ConcurrentHashMap] immediately and batch SQLite
 *   writes on a single-thread background executor with 30-second debouncing.
 * - Immediately short-circuits when disabled or in incognito mode.
 */
class DailyStatsStore(
    private val backend: DailyStatsBackend,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val timeZone: TimeZone = TimeZone.getDefault()
) {
    private val recordsMap = ConcurrentHashMap<Int, DailyStatsRecord>()
    private val dirtyKeys = ConcurrentHashMap.newKeySet<Int>()

    private val writeExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "DailyStatsWriteQueue").apply { isDaemon = true }
    }
    private val pendingFlushTask = AtomicReference<ScheduledFuture<*>?>(null)

    @Volatile
    private var hydrated = false
    private val hydrateLock = Any()

    @Volatile
    var isStatsEnabled: Boolean = true

    @Volatile
    var isIncognitoMode: Boolean = false

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun ensureHydrated() {
        if (hydrated) return
        synchronized(hydrateLock) {
            if (hydrated) return
            val loaded = runCatching { backend.loadAll() }.getOrDefault(emptyList())
            recordsMap.clear()
            for (rec in loaded) {
                if (rec.dateKey > 0) {
                    recordsMap[rec.dateKey] = rec
                }
            }
            hydrated = true
        }
    }

    fun recordVoiceAndAi(
        record: HistoryRecord,
        nowMs: Long = record.timestamp,
        enabled: Boolean = isStatsEnabled,
        incognito: Boolean = isIncognitoMode
    ) {
        if (!enabled || incognito) return
        ensureHydrated()
        val effectiveTs = if (nowMs > 0L) nowMs else clock()
        val (dateKey, hour) = dateKeyAndHour(effectiveTs, timeZone)
        mutateDay(dateKey) { existing ->
            accumulateVoiceRecord(existing, record, hour)
        }
    }

    fun recordKeyboardCommit(
        charCount: Int,
        schemeKey: String,
        appName: String = "",
        nowMs: Long = clock(),
        enabled: Boolean = isStatsEnabled,
        incognito: Boolean = isIncognitoMode
    ) {
        if (!enabled || incognito || charCount <= 0) return
        ensureHydrated()
        val (dateKey, hour) = dateKeyAndHour(nowMs, timeZone)
        val normalizedScheme = schemeKey.trim().ifEmpty { SchemeStatsKeys.ENGLISH_DIRECT }
        val normalizedApp = normalizeCoarseAppName(appName)
        mutateDay(dateKey) { existing ->
            existing.copy(
                keyChars = existing.keyChars + charCount,
                keyCommits = existing.keyCommits + 1,
                hourlyKeyChars = incrementHourly(existing.hourlyKeyChars, hour, charCount),
                schemeChars = incrementMap(existing.schemeChars, normalizedScheme, charCount),
                appCounts = if (normalizedApp.isNotEmpty()) {
                    incrementMap(existing.appCounts, normalizedApp, 1)
                } else {
                    existing.appCounts
                }
            )
        }
    }

    fun recordSnippetHit(
        savedChars: Int,
        nowMs: Long = clock(),
        enabled: Boolean = isStatsEnabled,
        incognito: Boolean = isIncognitoMode
    ) {
        if (!enabled || incognito) return
        ensureHydrated()
        val safeSaved = savedChars.coerceAtLeast(1)
        val (dateKey, _) = dateKeyAndHour(nowMs, timeZone)
        mutateDay(dateKey) { existing ->
            existing.copy(
                snippetHits = existing.snippetHits + 1,
                snippetSavedChars = existing.snippetSavedChars + safeSaved
            )
        }
    }

    fun recordPredictionHit(
        savedKeys: Int,
        nowMs: Long = clock(),
        enabled: Boolean = isStatsEnabled,
        incognito: Boolean = isIncognitoMode
    ) {
        if (!enabled || incognito) return
        ensureHydrated()
        val safeKeys = savedKeys.coerceAtLeast(1)
        val (dateKey, _) = dateKeyAndHour(nowMs, timeZone)
        mutateDay(dateKey) { existing ->
            existing.copy(
                predictionHits = existing.predictionHits + 1,
                predictionSavedKeys = existing.predictionSavedKeys + safeKeys
            )
        }
    }

    fun recordJevAdopt(
        appName: String = "",
        nowMs: Long = clock(),
        enabled: Boolean = isStatsEnabled,
        incognito: Boolean = isIncognitoMode
    ) {
        if (!enabled || incognito) return
        ensureHydrated()
        val (dateKey, _) = dateKeyAndHour(nowMs, timeZone)
        val normalizedApp = normalizeCoarseAppName(appName)
        mutateDay(dateKey) { existing ->
            existing.copy(
                jevAdopts = existing.jevAdopts + 1,
                appCounts = if (normalizedApp.isNotEmpty()) {
                    incrementMap(existing.appCounts, normalizedApp, 1)
                } else {
                    existing.appCounts
                }
            )
        }
    }

    fun recordTokenUsage(
        usage: TokenUsage?,
        modelName: String,
        featureKey: String,
        nowMs: Long = clock(),
        enabled: Boolean = isStatsEnabled,
        incognito: Boolean = isIncognitoMode
    ) {
        if (!enabled || incognito || usage == null || !usage.hasUsage) return
        ensureHydrated()
        val totalTok = usage.totalTokens
        if (totalTok <= 0L) return
        val effectiveTs = if (nowMs > 0L) nowMs else clock()
        val (dateKey, _) = dateKeyAndHour(effectiveTs, timeZone)
        val normalizedModel = normalizeModelTag(modelName)
        val normalizedFeature = featureKey.trim().take(32).ifEmpty { TokenFeatureKeys.VOICE_POLISH }
        mutateDay(dateKey) { existing ->
            val nextPrompt = existing.cloudPromptTokens + usage.safePromptTokens
            val nextCached = (existing.cloudCachedTokens + usage.safeCachedTokens).coerceAtMost(nextPrompt)
            existing.copy(
                cloudPromptTokens = nextPrompt,
                cloudCachedTokens = nextCached,
                cloudCompletionTokens = existing.cloudCompletionTokens + usage.visibleOutputTokens,
                cloudReasoningTokens = existing.cloudReasoningTokens + usage.safeReasoningTokens,
                cloudEstimatedTokenCalls = existing.cloudEstimatedTokenCalls + if (usage.isEstimated) 1 else 0,
                tokenByModel = if (normalizedModel.isNotEmpty()) {
                    incrementLongMap(existing.tokenByModel, normalizedModel, totalTok)
                } else {
                    existing.tokenByModel
                },
                tokenByFeature = incrementLongMap(existing.tokenByFeature, normalizedFeature, totalTok)
            )
        }
    }

    /**
     * Losslessly merges imported [DailyStatsRecord]s per `dateKey` using idempotent per-metric max
     * reconciliation so re-importing the same backup never double-counts and existing local days are preserved.
     * Returns the number of valid daily records merged or inserted.
     */
    fun mergeImportedRecords(imported: List<DailyStatsRecord>): Int {
        if (imported.isEmpty()) return 0
        ensureHydrated()
        var mergedCount = 0
        synchronized(hydrateLock) {
            for (raw in imported) {
                if (raw.dateKey <= 0) continue
                val sanitized = sanitizeImportedRecord(raw)
                recordsMap.compute(sanitized.dateKey) { _, existing ->
                    if (existing == null) sanitized else mergeIdempotentDay(existing, sanitized)
                }
                dirtyKeys.add(sanitized.dateKey)
                mergedCount++
            }
        }
        if (mergedCount > 0) {
            _revision.update { it + 1 }
            flushAsync()
        }
        return mergedCount
    }

    /**
     * Seeds historical voice/AI records once when upgrading an existing user who already has
     * [HistoryRecord]s but 0 days in the new daily stats store.
     */
    fun backfillFromHistoryIfEmpty(history: List<HistoryRecord>): Boolean {
        if (history.isEmpty()) return false
        ensureHydrated()
        var seeded = false
        synchronized(hydrateLock) {
            if (recordsMap.isNotEmpty()) return false
            for (record in history) {
                val ts = if (record.timestamp > 0L) record.timestamp else clock()
                val (dateKey, hour) = dateKeyAndHour(ts, timeZone)
                recordsMap.compute(dateKey) { _, existing ->
                    accumulateVoiceRecord(existing ?: DailyStatsRecord(dateKey = dateKey), record, hour)
                }
                dirtyKeys.add(dateKey)
                seeded = true
            }
        }
        if (seeded) {
            _revision.update { it + 1 }
            flushAsync()
        }
        return seeded
    }

    fun listAllRecords(): List<DailyStatsRecord> {
        ensureHydrated()
        return recordsMap.values.sortedBy { it.dateKey }
    }

    fun resetAll() {
        ensureHydrated()
        pendingFlushTask.getAndSet(null)?.cancel(false)
        recordsMap.clear()
        dirtyKeys.clear()
        _revision.update { it + 1 }
        runCatching {
            writeExecutor.execute {
                runCatching { backend.deleteAll() }
            }
        }
    }

    fun dbSizeBytes(): Long {
        return runCatching { backend.getDbSizeBytes() }.getOrDefault(0L)
    }

    fun flushAsync() {
        pendingFlushTask.getAndSet(null)?.cancel(false)
        if (dirtyKeys.isEmpty()) return
        runCatching {
            writeExecutor.execute {
                flushDirtyNow()
            }
        }
    }

    fun flushBlocking(timeoutMs: Long = 2_000L): Boolean {
        pendingFlushTask.getAndSet(null)?.cancel(false)
        return runCatching {
            writeExecutor.submit {
                flushDirtyNow()
            }.get(timeoutMs.coerceAtLeast(50L), TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)
    }

    private fun mutateDay(dateKey: Int, transform: (DailyStatsRecord) -> DailyStatsRecord) {
        recordsMap.compute(dateKey) { _, existing ->
            transform(existing ?: DailyStatsRecord(dateKey = dateKey))
        }
        dirtyKeys.add(dateKey)
        _revision.update { it + 1 }
        scheduleDebouncedFlush()
    }

    private fun scheduleDebouncedFlush() {
        val current = pendingFlushTask.get()
        if (current != null && !current.isDone) return
        if (debounceMs <= 0L) {
            flushAsync()
            return
        }
        runCatching {
            val scheduled = writeExecutor.schedule(
                {
                    pendingFlushTask.set(null)
                    flushDirtyNow()
                },
                debounceMs,
                TimeUnit.MILLISECONDS
            )
            if (!pendingFlushTask.compareAndSet(current, scheduled)) {
                scheduled.cancel(false)
            }
        }
    }

    private fun flushDirtyNow() {
        if (dirtyKeys.isEmpty()) return
        val keysToFlush = ArrayList<Int>(dirtyKeys.size)
        val iter = dirtyKeys.iterator()
        while (iter.hasNext()) {
            val k = iter.next()
            if (dirtyKeys.remove(k)) {
                keysToFlush.add(k)
            }
        }
        if (keysToFlush.isEmpty()) return
        val batch = ArrayList<DailyStatsRecord>(keysToFlush.size)
        for (k in keysToFlush) {
            recordsMap[k]?.let { batch.add(it) }
        }
        if (batch.isNotEmpty()) {
            runCatching { backend.upsertBatch(batch) }
        }
    }

    private fun accumulateVoiceRecord(
        existing: DailyStatsRecord,
        record: HistoryRecord,
        hour: Int
    ): DailyStatsRecord {
        val chars = record.polishedText.length.coerceAtLeast(0)
        val duration = record.durationMs.coerceAtLeast(0L)
        val latency = record.latencyMs.coerceAtLeast(0L)

        val execSrc = record.executionSource.trim().uppercase()
        val addOffline = if (execSrc == "LOCAL" || execSrc == "LOCAL_RAW_PASSTHROUGH") 1 else 0
        val addCloud = if (execSrc == "CLOUD" || execSrc == "HYBRID_LOCAL_ASR_CLOUD_LLM") 1 else 0
        val isLocalAsr = execSrc == "LOCAL" ||
            execSrc == "LOCAL_RAW_PASSTHROUGH" ||
            execSrc == "HYBRID_LOCAL_ASR_CLOUD_LLM"
        val addOfflineVoiceDur = if (isLocalAsr) duration else 0L
        val addCloudVoiceDur = if (!isLocalAsr) duration else 0L

        val trimmedRaw = record.rawTranscript.trim()
        val isVoiceHelpWrite = record.intentMode == VoiceIntentMode.HELP_ME_WRITE &&
            trimmedRaw.isNotEmpty() &&
            !trimmedRaw.startsWith("[") &&
            chars > 0

        val isVoiceSnippet = record.modeLabel.contains("口令展开") && chars > 0
        val voiceSnippetSaved = if (isVoiceSnippet) {
            (chars - trimmedRaw.length).coerceAtLeast(1)
        } else {
            0
        }

        val appLabel = normalizeCoarseAppName(record.targetAppName)
        val styleLabel = record.styleName.trim().take(32)

        return existing.copy(
            voiceChars = existing.voiceChars + chars,
            voiceCount = existing.voiceCount + 1,
            voiceDurationMs = existing.voiceDurationMs + duration,
            voiceLatencySumMs = existing.voiceLatencySumMs + latency,
            voiceLatencyCount = existing.voiceLatencyCount + if (latency > 0L) 1 else 0,
            helpWriteRawChars = existing.helpWriteRawChars + if (isVoiceHelpWrite) trimmedRaw.length else 0,
            helpWritePolishedChars = existing.helpWritePolishedChars + if (isVoiceHelpWrite) chars else 0,
            helpWriteCount = existing.helpWriteCount + if (isVoiceHelpWrite) 1 else 0,
            offlineCount = existing.offlineCount + addOffline,
            cloudCount = existing.cloudCount + addCloud,
            snippetHits = existing.snippetHits + if (isVoiceSnippet) 1 else 0,
            snippetSavedChars = existing.snippetSavedChars + voiceSnippetSaved,
            hourlyVoiceChars = if (chars > 0) {
                incrementHourly(existing.hourlyVoiceChars, hour, chars)
            } else {
                existing.hourlyVoiceChars
            },
            intentCounts = incrementMap(existing.intentCounts, record.intentMode.name, 1),
            appCounts = if (appLabel.isNotEmpty()) {
                incrementMap(existing.appCounts, appLabel, 1)
            } else {
                existing.appCounts
            },
            styleCounts = if (styleLabel.isNotEmpty()) {
                incrementMap(existing.styleCounts, styleLabel, 1)
            } else {
                existing.styleCounts
            },
            cloudVoiceDurationMs = existing.cloudVoiceDurationMs + addCloudVoiceDur,
            offlineVoiceDurationMs = existing.offlineVoiceDurationMs + addOfflineVoiceDur
        )
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 30_000L

        fun toDateKey(timestampMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Int {
            return dateKeyAndHour(timestampMs, timeZone).first
        }

        internal fun dateKeyAndHour(
            timestampMs: Long,
            timeZone: TimeZone = TimeZone.getDefault()
        ): Pair<Int, Int> {
            val cal = Calendar.getInstance(timeZone).apply { timeInMillis = timestampMs }
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val hour = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            return (year * 10000 + month * 100 + day) to hour
        }

        private fun normalizeCoarseAppName(raw: String): String {
            val clean = raw.trim().take(32)
            if (clean.isEmpty() || clean == "未知应用") return ""
            return clean
        }

        private fun incrementHourly(list: List<Int>, hour: Int, delta: Int): List<Int> {
            if (delta <= 0) return list
            val safeHour = hour.coerceIn(0, 23)
            val out = MutableList(24) { idx -> list.getOrElse(idx) { 0 }.coerceAtLeast(0) }
            out[safeHour] = out[safeHour] + delta
            return out
        }

        private fun incrementMap(map: Map<String, Int>, key: String, delta: Int): Map<String, Int> {
            if (key.isEmpty() || delta <= 0) return map
            val out = LinkedHashMap<String, Int>(map.size + 1)
            out.putAll(map)
            out[key] = (out[key] ?: 0) + delta
            return out
        }

        private fun incrementLongMap(map: Map<String, Long>, key: String, delta: Long): Map<String, Long> {
            if (key.isEmpty() || delta <= 0L) return map
            val out = LinkedHashMap<String, Long>(map.size + 1)
            out.putAll(map)
            out[key] = (out[key] ?: 0L) + delta
            return out
        }

        internal fun normalizeModelTag(rawModel: String): String {
            val trimmed = rawModel.trim()
            if (trimmed.isEmpty()) return ""
            return trimmed.substringAfterLast('/').trim().ifEmpty { trimmed }.take(48)
        }

        private fun sanitizeImportedRecord(rec: DailyStatsRecord): DailyStatsRecord {
            val safePrompt = rec.cloudPromptTokens.coerceAtLeast(0L)
            return rec.copy(
                voiceChars = rec.voiceChars.coerceAtLeast(0),
                voiceCount = rec.voiceCount.coerceAtLeast(0),
                voiceDurationMs = rec.voiceDurationMs.coerceAtLeast(0L),
                voiceLatencySumMs = rec.voiceLatencySumMs.coerceAtLeast(0L),
                voiceLatencyCount = rec.voiceLatencyCount.coerceAtLeast(0),
                helpWriteRawChars = rec.helpWriteRawChars.coerceAtLeast(0),
                helpWritePolishedChars = rec.helpWritePolishedChars.coerceAtLeast(0),
                helpWriteCount = rec.helpWriteCount.coerceAtLeast(0),
                keyChars = rec.keyChars.coerceAtLeast(0),
                keyCommits = rec.keyCommits.coerceAtLeast(0),
                offlineCount = rec.offlineCount.coerceAtLeast(0),
                cloudCount = rec.cloudCount.coerceAtLeast(0),
                snippetHits = rec.snippetHits.coerceAtLeast(0),
                snippetSavedChars = rec.snippetSavedChars.coerceAtLeast(0),
                predictionHits = rec.predictionHits.coerceAtLeast(0),
                predictionSavedKeys = rec.predictionSavedKeys.coerceAtLeast(0),
                jevAdopts = rec.jevAdopts.coerceAtLeast(0),
                hourlyVoiceChars = List(24) { idx -> rec.hourlyVoiceChars.getOrElse(idx) { 0 }.coerceAtLeast(0) },
                hourlyKeyChars = List(24) { idx -> rec.hourlyKeyChars.getOrElse(idx) { 0 }.coerceAtLeast(0) },
                schemeChars = rec.schemeChars.filter { it.key.isNotBlank() && it.value > 0 },
                intentCounts = rec.intentCounts.filter { it.key.isNotBlank() && it.value > 0 },
                appCounts = rec.appCounts.filter { it.key.isNotBlank() && it.value > 0 },
                styleCounts = rec.styleCounts.filter { it.key.isNotBlank() && it.value > 0 },
                cloudPromptTokens = safePrompt,
                cloudCachedTokens = rec.cloudCachedTokens.coerceIn(0L, safePrompt),
                cloudCompletionTokens = rec.cloudCompletionTokens.coerceAtLeast(0L),
                cloudReasoningTokens = rec.cloudReasoningTokens.coerceAtLeast(0L),
                cloudEstimatedTokenCalls = rec.cloudEstimatedTokenCalls.coerceAtLeast(0),
                cloudVoiceDurationMs = rec.cloudVoiceDurationMs.coerceAtLeast(0L),
                offlineVoiceDurationMs = rec.offlineVoiceDurationMs.coerceAtLeast(0L),
                tokenByModel = rec.tokenByModel.filter { it.key.isNotBlank() && it.value > 0L },
                tokenByFeature = rec.tokenByFeature.filter { it.key.isNotBlank() && it.value > 0L }
            )
        }

        internal fun mergeIdempotentDay(a: DailyStatsRecord, b: DailyStatsRecord): DailyStatsRecord {
            val (latSum, latCnt) = when {
                b.voiceLatencyCount > a.voiceLatencyCount -> b.voiceLatencySumMs to b.voiceLatencyCount
                a.voiceLatencyCount > b.voiceLatencyCount -> a.voiceLatencySumMs to a.voiceLatencyCount
                else -> maxOf(a.voiceLatencySumMs, b.voiceLatencySumMs) to a.voiceLatencyCount
            }
            val (hwRaw, hwPol, hwCnt) = when {
                b.helpWriteCount > a.helpWriteCount -> Triple(b.helpWriteRawChars, b.helpWritePolishedChars, b.helpWriteCount)
                a.helpWriteCount > b.helpWriteCount -> Triple(a.helpWriteRawChars, a.helpWritePolishedChars, a.helpWriteCount)
                else -> Triple(
                    maxOf(a.helpWriteRawChars, b.helpWriteRawChars),
                    maxOf(a.helpWritePolishedChars, b.helpWritePolishedChars),
                    a.helpWriteCount
                )
            }
            val mergedPrompt = maxOf(a.cloudPromptTokens, b.cloudPromptTokens)
            val mergedCached = maxOf(a.cloudCachedTokens, b.cloudCachedTokens).coerceAtMost(mergedPrompt)
            return DailyStatsRecord(
                dateKey = a.dateKey,
                voiceChars = maxOf(a.voiceChars, b.voiceChars),
                voiceCount = maxOf(a.voiceCount, b.voiceCount),
                voiceDurationMs = maxOf(a.voiceDurationMs, b.voiceDurationMs),
                voiceLatencySumMs = latSum,
                voiceLatencyCount = latCnt,
                helpWriteRawChars = hwRaw,
                helpWritePolishedChars = hwPol,
                helpWriteCount = hwCnt,
                keyChars = maxOf(a.keyChars, b.keyChars),
                keyCommits = maxOf(a.keyCommits, b.keyCommits),
                offlineCount = maxOf(a.offlineCount, b.offlineCount),
                cloudCount = maxOf(a.cloudCount, b.cloudCount),
                snippetHits = maxOf(a.snippetHits, b.snippetHits),
                snippetSavedChars = maxOf(a.snippetSavedChars, b.snippetSavedChars),
                predictionHits = maxOf(a.predictionHits, b.predictionHits),
                predictionSavedKeys = maxOf(a.predictionSavedKeys, b.predictionSavedKeys),
                jevAdopts = maxOf(a.jevAdopts, b.jevAdopts),
                hourlyVoiceChars = List(24) { h ->
                    maxOf(a.hourlyVoiceChars.getOrElse(h) { 0 }, b.hourlyVoiceChars.getOrElse(h) { 0 })
                },
                hourlyKeyChars = List(24) { h ->
                    maxOf(a.hourlyKeyChars.getOrElse(h) { 0 }, b.hourlyKeyChars.getOrElse(h) { 0 })
                },
                schemeChars = mergeMaxIntMap(a.schemeChars, b.schemeChars),
                intentCounts = mergeMaxIntMap(a.intentCounts, b.intentCounts),
                appCounts = mergeMaxIntMap(a.appCounts, b.appCounts),
                styleCounts = mergeMaxIntMap(a.styleCounts, b.styleCounts),
                cloudPromptTokens = mergedPrompt,
                cloudCachedTokens = mergedCached,
                cloudCompletionTokens = maxOf(a.cloudCompletionTokens, b.cloudCompletionTokens),
                cloudReasoningTokens = maxOf(a.cloudReasoningTokens, b.cloudReasoningTokens),
                cloudEstimatedTokenCalls = maxOf(a.cloudEstimatedTokenCalls, b.cloudEstimatedTokenCalls),
                cloudVoiceDurationMs = maxOf(a.cloudVoiceDurationMs, b.cloudVoiceDurationMs),
                offlineVoiceDurationMs = maxOf(a.offlineVoiceDurationMs, b.offlineVoiceDurationMs),
                tokenByModel = mergeMaxLongMap(a.tokenByModel, b.tokenByModel),
                tokenByFeature = mergeMaxLongMap(a.tokenByFeature, b.tokenByFeature)
            )
        }

        private fun mergeMaxIntMap(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val out = LinkedHashMap<String, Int>(a.size + b.size)
            out.putAll(a)
            for ((k, v) in b) {
                val prev = out[k] ?: 0
                if (v > prev) out[k] = v
            }
            return out
        }

        private fun mergeMaxLongMap(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val out = LinkedHashMap<String, Long>(a.size + b.size)
            out.putAll(a)
            for ((k, v) in b) {
                val prev = out[k] ?: 0L
                if (v > prev) out[k] = v
            }
            return out
        }
    }
}
