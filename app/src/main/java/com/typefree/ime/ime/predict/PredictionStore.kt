package com.typefree.ime.ime.predict

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.typefree.ime.data.stats.BigramBackupItem
import com.typefree.ime.data.stats.PredictionBackupArchive
import com.typefree.ime.data.stats.TrigramBackupItem
import com.typefree.ime.ime.flypy.FlypyCandidate
import java.util.concurrent.ConcurrentHashMap

data class BigramRecord(
    val prevWord: String,
    val nextWord: String,
    val count: Int,
    val lastUsedMs: Long
)

data class TrigramRecord(
    val prevPrevWord: String,
    val prevWord: String,
    val nextWord: String,
    val count: Int,
    val lastUsedMs: Long
)

interface PredictionStorageBackend {
    fun upsertBigram(prevWord: String, nextWord: String, nowMs: Long): BigramRecord
    fun queryNext(prevWord: String, limit: Int, nowMs: Long = System.currentTimeMillis()): List<BigramRecord>
    fun getRecord(prevWord: String, nextWord: String): BigramRecord?
    fun upsertTrigram(prevPrevWord: String, prevWord: String, nextWord: String, nowMs: Long): TrigramRecord =
        TrigramRecord(prevPrevWord, prevWord, nextWord, 1, nowMs)
    fun queryNextByTrigram(
        prevPrevWord: String,
        prevWord: String,
        limit: Int,
        nowMs: Long = System.currentTimeMillis()
    ): List<TrigramRecord> = emptyList()
    fun getTrigramRecord(prevPrevWord: String, prevWord: String, nextWord: String): TrigramRecord? = null
    fun totalTrigramCount(): Int = 0
    fun listTopBigrams(limit: Int = 8): List<BigramRecord> = emptyList()
    fun listAllBigrams(): List<BigramRecord> = emptyList()
    fun listAllTrigrams(): List<TrigramRecord> = emptyList()
    fun mergeArchive(archive: PredictionBackupArchive): Pair<Int, Int> = 0 to 0
    fun clearAll()
    fun totalBigramCount(): Int
}

class InMemoryPredictionBackend : PredictionStorageBackend {

    private val table = ConcurrentHashMap<String, ConcurrentHashMap<String, BigramRecord>>()
    private val trigramTable = ConcurrentHashMap<String, ConcurrentHashMap<String, TrigramRecord>>()

    private fun trigramKey(prevPrevWord: String, prevWord: String): String = "$prevPrevWord\u0000$prevWord"

    override fun upsertBigram(prevWord: String, nextWord: String, nowMs: Long): BigramRecord {
        val row = table.getOrPut(prevWord) { ConcurrentHashMap() }
        val updated = row.compute(nextWord) { _, existing ->
            if (existing == null) {
                BigramRecord(
                    prevWord = prevWord,
                    nextWord = nextWord,
                    count = 1,
                    lastUsedMs = nowMs
                )
            } else {
                existing.copy(
                    count = existing.count + 1,
                    lastUsedMs = maxOf(existing.lastUsedMs, nowMs)
                )
            }
        }!!
        return updated
    }

    fun seedRecord(record: BigramRecord) {
        val row = table.getOrPut(record.prevWord) { ConcurrentHashMap() }
        row[record.nextWord] = record
    }

    override fun queryNext(prevWord: String, limit: Int, nowMs: Long): List<BigramRecord> {
        if (limit <= 0) return emptyList()
        val row = table[prevWord] ?: return emptyList()
        val maxLastUsed = row.values.maxOfOrNull { it.lastUsedMs } ?: 0L
        val referenceNowMs = PredictionStore.resolveReferenceNowMs(nowMs, maxLastUsed)
        return row.values
            .sortedWith(
                compareByDescending<BigramRecord> {
                    PredictionStore.computeDecayedScore(it.count, it.lastUsedMs, referenceNowMs)
                }
                    .thenByDescending { it.count }
                    .thenByDescending { it.lastUsedMs }
                    .thenBy { it.nextWord }
            )
            .take(limit)
    }

    override fun getRecord(prevWord: String, nextWord: String): BigramRecord? {
        return table[prevWord]?.get(nextWord)
    }

    override fun upsertTrigram(
        prevPrevWord: String,
        prevWord: String,
        nextWord: String,
        nowMs: Long
    ): TrigramRecord {
        val key = trigramKey(prevPrevWord, prevWord)
        val row = trigramTable.getOrPut(key) { ConcurrentHashMap() }
        return row.compute(nextWord) { _, existing ->
            if (existing == null) {
                TrigramRecord(
                    prevPrevWord = prevPrevWord,
                    prevWord = prevWord,
                    nextWord = nextWord,
                    count = 1,
                    lastUsedMs = nowMs
                )
            } else {
                existing.copy(
                    count = existing.count + 1,
                    lastUsedMs = maxOf(existing.lastUsedMs, nowMs)
                )
            }
        }!!
    }

    fun seedTrigramRecord(record: TrigramRecord) {
        val key = trigramKey(record.prevPrevWord, record.prevWord)
        val row = trigramTable.getOrPut(key) { ConcurrentHashMap() }
        row[record.nextWord] = record
    }

    override fun queryNextByTrigram(
        prevPrevWord: String,
        prevWord: String,
        limit: Int,
        nowMs: Long
    ): List<TrigramRecord> {
        if (limit <= 0) return emptyList()
        val row = trigramTable[trigramKey(prevPrevWord, prevWord)] ?: return emptyList()
        val maxLastUsed = row.values.maxOfOrNull { it.lastUsedMs } ?: 0L
        val referenceNowMs = PredictionStore.resolveReferenceNowMs(nowMs, maxLastUsed)
        return row.values
            .sortedWith(
                compareByDescending<TrigramRecord> {
                    PredictionStore.computeDecayedScore(it.count, it.lastUsedMs, referenceNowMs)
                }
                    .thenByDescending { it.count }
                    .thenByDescending { it.lastUsedMs }
                    .thenBy { it.nextWord }
            )
            .take(limit)
    }

    override fun getTrigramRecord(prevPrevWord: String, prevWord: String, nextWord: String): TrigramRecord? {
        return trigramTable[trigramKey(prevPrevWord, prevWord)]?.get(nextWord)
    }

    override fun totalTrigramCount(): Int {
        return trigramTable.values.sumOf { it.size }
    }

    override fun listTopBigrams(limit: Int): List<BigramRecord> {
        if (limit <= 0) return emptyList()
        return table.values
            .asSequence()
            .flatMap { it.values.asSequence() }
            .sortedWith(
                compareByDescending<BigramRecord> { it.count }
                    .thenByDescending { it.lastUsedMs }
                    .thenBy { it.prevWord }
                    .thenBy { it.nextWord }
            )
            .take(limit)
            .toList()
    }

    override fun listAllBigrams(): List<BigramRecord> {
        return table.values
            .asSequence()
            .flatMap { it.values.asSequence() }
            .sortedWith(
                compareByDescending<BigramRecord> { it.count }
                    .thenByDescending { it.lastUsedMs }
                    .thenBy { it.prevWord }
                    .thenBy { it.nextWord }
            )
            .toList()
    }

    override fun listAllTrigrams(): List<TrigramRecord> {
        return trigramTable.values
            .asSequence()
            .flatMap { it.values.asSequence() }
            .sortedWith(
                compareByDescending<TrigramRecord> { it.count }
                    .thenByDescending { it.lastUsedMs }
                    .thenBy { it.prevPrevWord }
                    .thenBy { it.prevWord }
                    .thenBy { it.nextWord }
            )
            .toList()
    }

    override fun mergeArchive(archive: PredictionBackupArchive): Pair<Int, Int> {
        var mergedBigrams = 0
        for (item in archive.bigrams) {
            val prev = item.prevWord.trim()
            val next = item.nextWord.trim()
            if (!PredictionStore.isValidWord(prev) || !PredictionStore.isValidWord(next) || prev == next) continue
            val safeCount = item.count.coerceAtLeast(1)
            val safeTs = item.lastUsedMs.coerceAtLeast(0L)
            val row = table.getOrPut(prev) { ConcurrentHashMap() }
            row.compute(next) { _, existing ->
                if (existing == null) {
                    BigramRecord(prev, next, safeCount, safeTs)
                } else {
                    existing.copy(
                        count = maxOf(existing.count, safeCount),
                        lastUsedMs = maxOf(existing.lastUsedMs, safeTs)
                    )
                }
            }
            mergedBigrams++
        }
        var mergedTrigrams = 0
        for (item in archive.trigrams) {
            val pp = item.prevPrevWord.trim()
            val prev = item.prevWord.trim()
            val next = item.nextWord.trim()
            if (!PredictionStore.isValidWord(pp) || !PredictionStore.isValidWord(prev) || !PredictionStore.isValidWord(next) || prev == next) continue
            val safeCount = item.count.coerceAtLeast(1)
            val safeTs = item.lastUsedMs.coerceAtLeast(0L)
            val row = trigramTable.getOrPut(trigramKey(pp, prev)) { ConcurrentHashMap() }
            row.compute(next) { _, existing ->
                if (existing == null) {
                    TrigramRecord(pp, prev, next, safeCount, safeTs)
                } else {
                    existing.copy(
                        count = maxOf(existing.count, safeCount),
                        lastUsedMs = maxOf(existing.lastUsedMs, safeTs)
                    )
                }
            }
            mergedTrigrams++
        }
        return mergedBigrams to mergedTrigrams
    }

    override fun clearAll() {
        table.clear()
        trigramTable.clear()
    }

    override fun totalBigramCount(): Int {
        return table.values.sumOf { it.size }
    }
}

class SqlitePredictionBackend(
    context: Context
) : SQLiteOpenHelper(
    context.applicationContext ?: context,
    PredictionStore.DB_NAME,
    null,
    PredictionStore.DB_VERSION
), PredictionStorageBackend {

    private val memoryCache = InMemoryPredictionBackend()

    @Volatile
    private var cacheHydrated = false

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ${PredictionStore.TABLE_BIGRAMS} (
                prev_word TEXT NOT NULL,
                next_word TEXT NOT NULL,
                freq_count INTEGER NOT NULL DEFAULT 1,
                last_used_ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (prev_word, next_word)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_user_bigrams_prev_sort
            ON ${PredictionStore.TABLE_BIGRAMS} (prev_word, freq_count DESC, last_used_ms DESC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ${PredictionStore.TABLE_TRIGRAMS} (
                prev_prev_word TEXT NOT NULL,
                prev_word TEXT NOT NULL,
                next_word TEXT NOT NULL,
                freq_count INTEGER NOT NULL DEFAULT 1,
                last_used_ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (prev_prev_word, prev_word, next_word)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_user_trigrams_context_sort
            ON ${PredictionStore.TABLE_TRIGRAMS} (prev_prev_word, prev_word, freq_count DESC, last_used_ms DESC)
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    @Synchronized
    private fun ensureHydrated() {
        if (cacheHydrated) return
        cacheHydrated = true
        runCatching {
            val db = readableDatabase
            db.query(
                PredictionStore.TABLE_BIGRAMS,
                arrayOf("prev_word", "next_word", "freq_count", "last_used_ms"),
                null,
                null,
                null,
                null,
                null
            ).use { cursor ->
                val prevIdx = cursor.getColumnIndexOrThrow("prev_word")
                val nextIdx = cursor.getColumnIndexOrThrow("next_word")
                val countIdx = cursor.getColumnIndexOrThrow("freq_count")
                val lastUsedIdx = cursor.getColumnIndexOrThrow("last_used_ms")
                while (cursor.moveToNext()) {
                    val prev = cursor.getString(prevIdx).orEmpty()
                    val next = cursor.getString(nextIdx).orEmpty()
                    val count = cursor.getInt(countIdx).coerceAtLeast(1)
                    val lastUsed = cursor.getLong(lastUsedIdx)
                    if (prev.isNotEmpty() && next.isNotEmpty()) {
                        memoryCache.seedRecord(
                            BigramRecord(
                                prevWord = prev,
                                nextWord = next,
                                count = count,
                                lastUsedMs = lastUsed
                            )
                        )
                    }
                }
            }
            runCatching {
                db.query(
                    PredictionStore.TABLE_TRIGRAMS,
                    arrayOf("prev_prev_word", "prev_word", "next_word", "freq_count", "last_used_ms"),
                    null,
                    null,
                    null,
                    null,
                    null
                ).use { cursor ->
                    val prevPrevIdx = cursor.getColumnIndexOrThrow("prev_prev_word")
                    val prevIdx = cursor.getColumnIndexOrThrow("prev_word")
                    val nextIdx = cursor.getColumnIndexOrThrow("next_word")
                    val countIdx = cursor.getColumnIndexOrThrow("freq_count")
                    val lastUsedIdx = cursor.getColumnIndexOrThrow("last_used_ms")
                    while (cursor.moveToNext()) {
                        val prevPrev = cursor.getString(prevPrevIdx).orEmpty()
                        val prev = cursor.getString(prevIdx).orEmpty()
                        val next = cursor.getString(nextIdx).orEmpty()
                        val count = cursor.getInt(countIdx).coerceAtLeast(1)
                        val lastUsed = cursor.getLong(lastUsedIdx)
                        if (prevPrev.isNotEmpty() && prev.isNotEmpty() && next.isNotEmpty()) {
                            memoryCache.seedTrigramRecord(
                                TrigramRecord(
                                    prevPrevWord = prevPrev,
                                    prevWord = prev,
                                    nextWord = next,
                                    count = count,
                                    lastUsedMs = lastUsed
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    override fun upsertBigram(prevWord: String, nextWord: String, nowMs: Long): BigramRecord {
        ensureHydrated()
        val updated = memoryCache.upsertBigram(prevWord, nextWord, nowMs)
        runCatching {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("prev_word", updated.prevWord)
                put("next_word", updated.nextWord)
                put("freq_count", updated.count)
                put("last_used_ms", updated.lastUsedMs)
            }
            db.insertWithOnConflict(
                PredictionStore.TABLE_BIGRAMS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
        return updated
    }

    override fun queryNext(prevWord: String, limit: Int, nowMs: Long): List<BigramRecord> {
        ensureHydrated()
        return memoryCache.queryNext(prevWord, limit, nowMs)
    }

    override fun getRecord(prevWord: String, nextWord: String): BigramRecord? {
        ensureHydrated()
        return memoryCache.getRecord(prevWord, nextWord)
    }

    @Synchronized
    override fun upsertTrigram(
        prevPrevWord: String,
        prevWord: String,
        nextWord: String,
        nowMs: Long
    ): TrigramRecord {
        ensureHydrated()
        val updated = memoryCache.upsertTrigram(prevPrevWord, prevWord, nextWord, nowMs)
        runCatching {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("prev_prev_word", updated.prevPrevWord)
                put("prev_word", updated.prevWord)
                put("next_word", updated.nextWord)
                put("freq_count", updated.count)
                put("last_used_ms", updated.lastUsedMs)
            }
            db.insertWithOnConflict(
                PredictionStore.TABLE_TRIGRAMS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
        return updated
    }

    override fun queryNextByTrigram(
        prevPrevWord: String,
        prevWord: String,
        limit: Int,
        nowMs: Long
    ): List<TrigramRecord> {
        ensureHydrated()
        return memoryCache.queryNextByTrigram(prevPrevWord, prevWord, limit, nowMs)
    }

    override fun getTrigramRecord(prevPrevWord: String, prevWord: String, nextWord: String): TrigramRecord? {
        ensureHydrated()
        return memoryCache.getTrigramRecord(prevPrevWord, prevWord, nextWord)
    }

    override fun totalTrigramCount(): Int {
        ensureHydrated()
        return memoryCache.totalTrigramCount()
    }

    @Synchronized
    override fun clearAll() {
        cacheHydrated = true
        memoryCache.clearAll()
        runCatching {
            writableDatabase.delete(PredictionStore.TABLE_BIGRAMS, null, null)
            writableDatabase.delete(PredictionStore.TABLE_TRIGRAMS, null, null)
        }
    }

    override fun totalBigramCount(): Int {
        ensureHydrated()
        return memoryCache.totalBigramCount()
    }

    override fun listTopBigrams(limit: Int): List<BigramRecord> {
        ensureHydrated()
        return memoryCache.listTopBigrams(limit)
    }

    override fun listAllBigrams(): List<BigramRecord> {
        ensureHydrated()
        return memoryCache.listAllBigrams()
    }

    override fun listAllTrigrams(): List<TrigramRecord> {
        ensureHydrated()
        return memoryCache.listAllTrigrams()
    }

    @Synchronized
    override fun mergeArchive(archive: PredictionBackupArchive): Pair<Int, Int> {
        ensureHydrated()
        val (mergedBigrams, mergedTrigrams) = memoryCache.mergeArchive(archive)
        if (mergedBigrams == 0 && mergedTrigrams == 0) return 0 to 0
        runCatching {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (item in archive.bigrams) {
                    val prev = item.prevWord.trim()
                    val next = item.nextWord.trim()
                    val rec = memoryCache.getRecord(prev, next) ?: continue
                    val values = ContentValues().apply {
                        put("prev_word", rec.prevWord)
                        put("next_word", rec.nextWord)
                        put("freq_count", rec.count)
                        put("last_used_ms", rec.lastUsedMs)
                    }
                    db.insertWithOnConflict(
                        PredictionStore.TABLE_BIGRAMS,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
                for (item in archive.trigrams) {
                    val pp = item.prevPrevWord.trim()
                    val prev = item.prevWord.trim()
                    val next = item.nextWord.trim()
                    val rec = memoryCache.getTrigramRecord(pp, prev, next) ?: continue
                    val values = ContentValues().apply {
                        put("prev_prev_word", rec.prevPrevWord)
                        put("prev_word", rec.prevWord)
                        put("next_word", rec.nextWord)
                        put("freq_count", rec.count)
                        put("last_used_ms", rec.lastUsedMs)
                    }
                    db.insertWithOnConflict(
                        PredictionStore.TABLE_TRIGRAMS,
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
        return mergedBigrams to mergedTrigrams
    }
}

/**
 * L2 Personal Bigram & Trigram Prediction Store (`prevWord -> nextWord`, `(prevPrevWord, prevWord) -> nextWord`):
 * - Tracks transition frequency (`count`) and recency (`lastUsedMs`) with 30-day half-life decay.
 * - Ranks candidates by decayed score DESC, then `count DESC`, then `lastUsedMs DESC`.
 * - Filters out pure whitespace, pure punctuation/symbols, single-char ASCII letters/digits/control chars,
 *   and self-repeating words (`prevWord == nextWord`).
 * - Strictly enforces zero writes when `isIncognitoMode == true` (`IME_FLAG_NO_PERSONALIZED_LEARNING`),
 *   while allowing read-only queries of previously learned entries.
 */
class PredictionStore(
    private val backend: PredictionStorageBackend
) {
    @Volatile
    var isIncognitoMode: Boolean = false

    /**
     * Records a bigram transition `prevWord -> nextWord` unless in incognito mode or filtered out.
     * Returns `true` if the transition was recorded, or `false` if skipped.
     */
    fun recordBigram(
        prevWord: String,
        nextWord: String,
        isIncognito: Boolean = isIncognitoMode,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (isIncognito || this.isIncognitoMode) {
            return false
        }
        val cleanPrev = prevWord.trim()
        val cleanNext = nextWord.trim()
        if (!isValidWord(cleanPrev) || !isValidWord(cleanNext)) {
            return false
        }
        if (cleanPrev == cleanNext) {
            return false
        }
        backend.upsertBigram(cleanPrev, cleanNext, nowMs)
        return true
    }

    /**
     * Records a 2-word context trigram transition `(prevPrevWord, prevWord) -> nextWord`.
     */
    fun recordTrigram(
        prevPrevWord: String,
        prevWord: String,
        nextWord: String,
        isIncognito: Boolean = isIncognitoMode,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (isIncognito || this.isIncognitoMode) {
            return false
        }
        val cleanPrevPrev = prevPrevWord.trim()
        val cleanPrev = prevWord.trim()
        val cleanNext = nextWord.trim()
        if (!isValidWord(cleanPrevPrev) || !isValidWord(cleanPrev) || !isValidWord(cleanNext)) {
            return false
        }
        if (cleanPrev == cleanNext) {
            return false
        }
        backend.upsertTrigram(cleanPrevPrev, cleanPrev, cleanNext, nowMs)
        return true
    }

    fun queryRecords(
        prevWord: String,
        limit: Int = DEFAULT_LIMIT,
        nowMs: Long = System.currentTimeMillis()
    ): List<BigramRecord> {
        val cleanPrev = prevWord.trim()
        if (!isValidWord(cleanPrev) || limit <= 0) return emptyList()
        return backend.queryNext(cleanPrev, limit, nowMs)
            .filter { it.nextWord != cleanPrev }
    }

    fun queryTrigramRecords(
        prevPrevWord: String,
        prevWord: String,
        limit: Int = DEFAULT_LIMIT,
        nowMs: Long = System.currentTimeMillis()
    ): List<TrigramRecord> {
        val cleanPrevPrev = prevPrevWord.trim()
        val cleanPrev = prevWord.trim()
        if (!isValidWord(cleanPrevPrev) || !isValidWord(cleanPrev) || limit <= 0) return emptyList()
        return backend.queryNextByTrigram(cleanPrevPrev, cleanPrev, limit, nowMs)
            .filter { it.nextWord != cleanPrev }
    }

    fun queryNextWords(
        prevWord: String,
        limit: Int = DEFAULT_LIMIT,
        nowMs: Long = System.currentTimeMillis()
    ): List<String> {
        return queryRecords(prevWord, limit, nowMs).map { it.nextWord }
    }

    /**
     * Queries personal L2 next words prioritizing 2-word context trigrams before 1-word bigrams.
     */
    fun queryNextWordsWithContext(
        prevPrevWord: String?,
        prevWord: String,
        limit: Int = DEFAULT_LIMIT,
        nowMs: Long = System.currentTimeMillis()
    ): List<String> {
        if (limit <= 0) return emptyList()
        val cleanPrev = prevWord.trim()
        if (!isValidWord(cleanPrev)) return emptyList()

        val merged = ArrayList<String>(limit)
        val seen = HashSet<String>(limit * 2)
        seen.add(cleanPrev)

        if (!prevPrevWord.isNullOrBlank()) {
            for (rec in queryTrigramRecords(prevPrevWord, cleanPrev, limit, nowMs)) {
                if (seen.add(rec.nextWord)) {
                    merged.add(rec.nextWord)
                    if (merged.size >= limit) return merged
                }
            }
        }
        for (rec in queryRecords(cleanPrev, limit, nowMs)) {
            if (seen.add(rec.nextWord)) {
                merged.add(rec.nextWord)
                if (merged.size >= limit) return merged
            }
        }
        return merged
    }

    fun queryCandidates(
        prevWord: String,
        limit: Int = DEFAULT_LIMIT,
        nowMs: Long = System.currentTimeMillis()
    ): List<FlypyCandidate> {
        return queryRecords(prevWord, limit, nowMs).map { FlypyCandidate(text = it.nextWord) }
    }

    fun getRecord(prevWord: String, nextWord: String): BigramRecord? {
        return backend.getRecord(prevWord.trim(), nextWord.trim())
    }

    fun getBigramCount(prevWord: String, nextWord: String): Int {
        return getRecord(prevWord, nextWord)?.count ?: 0
    }

    fun getTrigramCount(prevPrevWord: String, prevWord: String, nextWord: String): Int {
        return backend.getTrigramRecord(prevPrevWord.trim(), prevWord.trim(), nextWord.trim())?.count ?: 0
    }

    fun clearAll() {
        backend.clearAll()
    }

    fun totalBigramCount(): Int = backend.totalBigramCount()

    fun totalTrigramCount(): Int = backend.totalTrigramCount()

    fun listTopBigrams(limit: Int = DEFAULT_LIMIT): List<BigramRecord> = backend.listTopBigrams(limit)

    fun exportArchive(): PredictionBackupArchive {
        return PredictionBackupArchive(
            bigrams = backend.listAllBigrams().map {
                BigramBackupItem(
                    prevWord = it.prevWord,
                    nextWord = it.nextWord,
                    count = it.count,
                    lastUsedMs = it.lastUsedMs
                )
            },
            trigrams = backend.listAllTrigrams().map {
                TrigramBackupItem(
                    prevPrevWord = it.prevPrevWord,
                    prevWord = it.prevWord,
                    nextWord = it.nextWord,
                    count = it.count,
                    lastUsedMs = it.lastUsedMs
                )
            }
        )
    }

    fun mergeArchive(archive: PredictionBackupArchive): Pair<Int, Int> {
        if (archive.isEmpty) return 0 to 0
        return backend.mergeArchive(archive)
    }

    companion object {
        const val DB_NAME = "typefree_predictions.db"
        const val DB_VERSION = 2
        const val TABLE_BIGRAMS = "user_bigrams"
        const val TABLE_TRIGRAMS = "user_trigrams"
        const val DEFAULT_LIMIT = 8
        const val RECENCY_HALF_LIFE_DAYS = 30.0
        private const val MS_PER_DAY = 86_400_000.0
        private const val REAL_EPOCH_THRESHOLD_MS = 946_684_800_000L // 2000-01-01T00:00:00Z

        internal fun resolveReferenceNowMs(nowMs: Long, maxLastUsedMs: Long): Long {
            if (maxLastUsedMs > 0L && maxLastUsedMs < REAL_EPOCH_THRESHOLD_MS && nowMs >= REAL_EPOCH_THRESHOLD_MS) {
                return maxLastUsedMs
            }
            return maxOf(nowMs, maxLastUsedMs)
        }

        /**
         * Computes a recency-decayed ranking score with a 30-day half-life:
         * `score = count * 0.5^(ageDays / 30)`.
         * Records within the same 1-hour window are treated without decay jitter so
         * tie-breaking falls cleanly to `count DESC`, then `lastUsedMs DESC`.
         */
        fun computeDecayedScore(count: Int, lastUsedMs: Long, referenceNowMs: Long): Double {
            if (count <= 0) return 0.0
            if (lastUsedMs <= 0L || referenceNowMs <= lastUsedMs) return count.toDouble()
            val ageMs = referenceNowMs - lastUsedMs
            if (ageMs <= 3_600_000L) return count.toDouble()
            val ageDays = ageMs / MS_PER_DAY
            val decay = Math.pow(0.5, ageDays / RECENCY_HALF_LIFE_DAYS)
            return (count * decay).coerceAtLeast(1e-9)
        }

        private val EXPLICIT_PUNCTUATION_CHARS: Set<Char> = setOf(
            '，', '。', '？', '！', '；', '：', '、', '“', '”', '‘', '’',
            '（', '）', '【', '】', '《', '》', '…', '—', '～', '·', '「', '」',
            '『', '』', '〖', '〗', '￥', '％',
            ',', '.', '?', '!', ';', ':', '\'', '"', '(', ')', '[', ']', '{', '}',
            '<', '>', '-', '_', '+', '=', '*', '/', '\\', '|', '@', '#', '$', '%',
            '^', '&', '~', '`'
        )

        fun inMemory(): PredictionStore = PredictionStore(InMemoryPredictionBackend())

        fun create(context: Context): PredictionStore =
            PredictionStore(SqlitePredictionBackend(context))

        fun fromContextOrInMemory(contextProvider: () -> Context?): PredictionStore =
            PredictionStore(
                object : PredictionStorageBackend {
                    @Volatile
                    private var resolvedBackend: PredictionStorageBackend? = null
                    private val fallbackMemory = InMemoryPredictionBackend()

                    private fun backend(): PredictionStorageBackend {
                        resolvedBackend?.let { return it }
                        val ctx = runCatching {
                            val raw = contextProvider()
                            val app = raw?.applicationContext ?: raw
                            if (app != null && app.filesDir != null) app else null
                        }.getOrNull()
                        if (ctx != null) {
                            val sqlite = SqlitePredictionBackend(ctx)
                            resolvedBackend = sqlite
                            return sqlite
                        }
                        return fallbackMemory
                    }

                    override fun upsertBigram(prevWord: String, nextWord: String, nowMs: Long): BigramRecord =
                        backend().upsertBigram(prevWord, nextWord, nowMs)

                    override fun queryNext(prevWord: String, limit: Int, nowMs: Long): List<BigramRecord> =
                        backend().queryNext(prevWord, limit, nowMs)

                    override fun getRecord(prevWord: String, nextWord: String): BigramRecord? =
                        backend().getRecord(prevWord, nextWord)

                    override fun upsertTrigram(
                        prevPrevWord: String,
                        prevWord: String,
                        nextWord: String,
                        nowMs: Long
                    ): TrigramRecord = backend().upsertTrigram(prevPrevWord, prevWord, nextWord, nowMs)

                    override fun queryNextByTrigram(
                        prevPrevWord: String,
                        prevWord: String,
                        limit: Int,
                        nowMs: Long
                    ): List<TrigramRecord> = backend().queryNextByTrigram(prevPrevWord, prevWord, limit, nowMs)

                    override fun getTrigramRecord(
                        prevPrevWord: String,
                        prevWord: String,
                        nextWord: String
                    ): TrigramRecord? = backend().getTrigramRecord(prevPrevWord, prevWord, nextWord)

                    override fun totalTrigramCount(): Int = backend().totalTrigramCount()

                    override fun listTopBigrams(limit: Int): List<BigramRecord> = backend().listTopBigrams(limit)

                    override fun listAllBigrams(): List<BigramRecord> = backend().listAllBigrams()

                    override fun listAllTrigrams(): List<TrigramRecord> = backend().listAllTrigrams()

                    override fun mergeArchive(archive: PredictionBackupArchive): Pair<Int, Int> =
                        backend().mergeArchive(archive)

                    override fun clearAll() = backend().clearAll()

                    override fun totalBigramCount(): Int = backend().totalBigramCount()
                }
            )

        /**
         * Safely reads `(bigramCount, trigramCount, topWordChains)` directly from `typefree_predictions.db`
         * in read-only mode so Insights UI queries never interfere with active IME prediction writes.
         */
        fun readTopWordChainsSummary(
            context: Context,
            limit: Int = DEFAULT_LIMIT
        ): Triple<Int, Int, List<String>> {
            val appCtx = context.applicationContext ?: context
            val dbFile = runCatching { appCtx.getDatabasePath(DB_NAME) }.getOrNull()
            if (dbFile == null || !dbFile.exists() || !dbFile.isFile) {
                return Triple(0, 0, emptyList())
            }
            return runCatching {
                SQLiteDatabase.openDatabase(
                    dbFile.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                ).use { db ->
                    val bigramCount = runCatching {
                        db.rawQuery("SELECT COUNT(*) FROM $TABLE_BIGRAMS", null).use { c ->
                            if (c.moveToFirst()) c.getInt(0).coerceAtLeast(0) else 0
                        }
                    }.getOrDefault(0)
                    val trigramCount = runCatching {
                        db.rawQuery("SELECT COUNT(*) FROM $TABLE_TRIGRAMS", null).use { c ->
                            if (c.moveToFirst()) c.getInt(0).coerceAtLeast(0) else 0
                        }
                    }.getOrDefault(0)
                    val chains = if (limit > 0 && bigramCount > 0) {
                        runCatching {
                            val out = ArrayList<String>(limit)
                            db.query(
                                TABLE_BIGRAMS,
                                arrayOf("prev_word", "next_word"),
                                null,
                                null,
                                null,
                                null,
                                "freq_count DESC, last_used_ms DESC",
                                limit.toString()
                            ).use { c ->
                                val prevIdx = c.getColumnIndexOrThrow("prev_word")
                                val nextIdx = c.getColumnIndexOrThrow("next_word")
                                while (c.moveToNext()) {
                                    val prev = c.getString(prevIdx).orEmpty().trim()
                                    val next = c.getString(nextIdx).orEmpty().trim()
                                    if (prev.isNotEmpty() && next.isNotEmpty()) {
                                        out.add("$prev → $next")
                                    }
                                }
                            }
                            out
                        }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                    Triple(bigramCount, trigramCount, chains)
                }
            }.getOrDefault(Triple(0, 0, emptyList()))
        }

        /**
         * Exports all personal L2 bigrams and trigrams from `typefree_predictions.db` in read-only mode
         * for lossless cross-device/cross-package migration.
         */
        fun exportBackupArchive(context: Context): PredictionBackupArchive {
            val appCtx = context.applicationContext ?: context
            val dbFile = runCatching { appCtx.getDatabasePath(DB_NAME) }.getOrNull()
            if (dbFile == null || !dbFile.exists() || !dbFile.isFile) {
                return PredictionBackupArchive()
            }
            return runCatching {
                SQLiteDatabase.openDatabase(
                    dbFile.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                ).use { db ->
                    val bigrams = ArrayList<BigramBackupItem>()
                    runCatching {
                        db.query(
                            TABLE_BIGRAMS,
                            arrayOf("prev_word", "next_word", "freq_count", "last_used_ms"),
                            null,
                            null,
                            null,
                            null,
                            "freq_count DESC, last_used_ms DESC"
                        ).use { c ->
                            val prevIdx = c.getColumnIndexOrThrow("prev_word")
                            val nextIdx = c.getColumnIndexOrThrow("next_word")
                            val cntIdx = c.getColumnIndexOrThrow("freq_count")
                            val tsIdx = c.getColumnIndexOrThrow("last_used_ms")
                            while (c.moveToNext()) {
                                val prev = c.getString(prevIdx).orEmpty().trim()
                                val next = c.getString(nextIdx).orEmpty().trim()
                                if (isValidWord(prev) && isValidWord(next) && prev != next) {
                                    bigrams.add(
                                        BigramBackupItem(
                                            prevWord = prev,
                                            nextWord = next,
                                            count = c.getInt(cntIdx).coerceAtLeast(1),
                                            lastUsedMs = c.getLong(tsIdx).coerceAtLeast(0L)
                                        )
                                    )
                                }
                            }
                        }
                    }
                    val trigrams = ArrayList<TrigramBackupItem>()
                    runCatching {
                        db.query(
                            TABLE_TRIGRAMS,
                            arrayOf("prev_prev_word", "prev_word", "next_word", "freq_count", "last_used_ms"),
                            null,
                            null,
                            null,
                            null,
                            "freq_count DESC, last_used_ms DESC"
                        ).use { c ->
                            val ppIdx = c.getColumnIndexOrThrow("prev_prev_word")
                            val prevIdx = c.getColumnIndexOrThrow("prev_word")
                            val nextIdx = c.getColumnIndexOrThrow("next_word")
                            val cntIdx = c.getColumnIndexOrThrow("freq_count")
                            val tsIdx = c.getColumnIndexOrThrow("last_used_ms")
                            while (c.moveToNext()) {
                                val pp = c.getString(ppIdx).orEmpty().trim()
                                val prev = c.getString(prevIdx).orEmpty().trim()
                                val next = c.getString(nextIdx).orEmpty().trim()
                                if (isValidWord(pp) && isValidWord(prev) && isValidWord(next) && prev != next) {
                                    trigrams.add(
                                        TrigramBackupItem(
                                            prevPrevWord = pp,
                                            prevWord = prev,
                                            nextWord = next,
                                            count = c.getInt(cntIdx).coerceAtLeast(1),
                                            lastUsedMs = c.getLong(tsIdx).coerceAtLeast(0L)
                                        )
                                    )
                                }
                            }
                        }
                    }
                    PredictionBackupArchive(bigrams = bigrams, trigrams = trigrams)
                }
            }.getOrDefault(PredictionBackupArchive())
        }

        /**
         * Losslessly merges an imported [PredictionBackupArchive] into `typefree_predictions.db`,
         * keeping `maxOf(existing.count, imported.count)` and `maxOf(existing.lastUsedMs, imported.lastUsedMs)`
         * for every bigram and trigram so re-importing is idempotent and local learning is never wiped.
         */
        fun importAndMergeArchive(
            context: Context,
            archive: PredictionBackupArchive
        ): Pair<Int, Int> {
            if (archive.isEmpty) return 0 to 0
            return runCatching {
                val backend = SqlitePredictionBackend(context)
                try {
                    backend.mergeArchive(archive)
                } finally {
                    runCatching { backend.close() }
                }
            }.getOrDefault(0 to 0)
        }

        fun isPunctuationChar(ch: Char): Boolean {
            if (ch in EXPLICIT_PUNCTUATION_CHARS) return true
            return when (Character.getType(ch).toByte()) {
                Character.CONNECTOR_PUNCTUATION,
                Character.DASH_PUNCTUATION,
                Character.START_PUNCTUATION,
                Character.END_PUNCTUATION,
                Character.INITIAL_QUOTE_PUNCTUATION,
                Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION,
                Character.MATH_SYMBOL,
                Character.CURRENCY_SYMBOL,
                Character.MODIFIER_SYMBOL,
                Character.OTHER_SYMBOL -> true
                else -> false
            }
        }

        fun isPunctuationOrSymbolOnly(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            return trimmed.all { isPunctuationChar(it) || it.isWhitespace() }
        }

        /**
         * Validates whether [word] can participate in bigram learning/lookup:
         * - Rejects blank strings or strings containing control characters
         * - Rejects strings containing any punctuation/symbol characters
         * - Rejects single-character ASCII letters/digits/control chars (`length == 1 && code < 128`)
         * - Rejects pure digits
         */
        fun isValidWord(word: String): Boolean {
            val trimmed = word.trim()
            if (trimmed.isEmpty() || trimmed.length > 24) return false
            if (trimmed.any { it.isISOControl() || it.isWhitespace() || isPunctuationChar(it) }) {
                return false
            }
            if (trimmed.length == 1 && trimmed[0].code < 128) {
                return false
            }
            if (trimmed.all { it.isDigit() }) {
                return false
            }
            return true
        }
    }
}
