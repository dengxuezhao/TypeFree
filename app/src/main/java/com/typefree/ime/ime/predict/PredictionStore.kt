package com.typefree.ime.ime.predict

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.typefree.ime.ime.flypy.FlypyCandidate
import java.util.concurrent.ConcurrentHashMap

data class BigramRecord(
    val prevWord: String,
    val nextWord: String,
    val count: Int,
    val lastUsedMs: Long
)

interface PredictionStorageBackend {
    fun upsertBigram(prevWord: String, nextWord: String, nowMs: Long): BigramRecord
    fun queryNext(prevWord: String, limit: Int): List<BigramRecord>
    fun getRecord(prevWord: String, nextWord: String): BigramRecord?
    fun clearAll()
    fun totalBigramCount(): Int
}

class InMemoryPredictionBackend : PredictionStorageBackend {

    private val table = ConcurrentHashMap<String, ConcurrentHashMap<String, BigramRecord>>()

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

    override fun queryNext(prevWord: String, limit: Int): List<BigramRecord> {
        if (limit <= 0) return emptyList()
        val row = table[prevWord] ?: return emptyList()
        return row.values
            .sortedWith(
                compareByDescending<BigramRecord> { it.count }
                    .thenByDescending { it.lastUsedMs }
                    .thenBy { it.nextWord }
            )
            .take(limit)
    }

    override fun getRecord(prevWord: String, nextWord: String): BigramRecord? {
        return table[prevWord]?.get(nextWord)
    }

    override fun clearAll() {
        table.clear()
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

    override fun queryNext(prevWord: String, limit: Int): List<BigramRecord> {
        ensureHydrated()
        return memoryCache.queryNext(prevWord, limit)
    }

    override fun getRecord(prevWord: String, nextWord: String): BigramRecord? {
        ensureHydrated()
        return memoryCache.getRecord(prevWord, nextWord)
    }

    @Synchronized
    override fun clearAll() {
        cacheHydrated = true
        memoryCache.clearAll()
        runCatching {
            writableDatabase.delete(PredictionStore.TABLE_BIGRAMS, null, null)
        }
    }

    override fun totalBigramCount(): Int {
        ensureHydrated()
        return memoryCache.totalBigramCount()
    }
}

/**
 * L2 Personal Bigram Prediction Store (`prevWord -> nextWord`) for TypeFree v2.7:
 * - Tracks transition frequency (`count`) and recency (`lastUsedMs`).
 * - Ranks candidates by `count DESC`, then `lastUsedMs DESC`.
 * - Filters out pure whitespace, pure punctuation/symbols, single-char ASCII letters/digits/control chars,
 *   and self-repeating words (`prevWord == nextWord`).
 * - Strictly enforces zero writes when `isIncognitoMode == true` (`IME_FLAG_NO_PERSONALIZED_LEARNING`),
 *   while allowing read-only queries of previously learned bigrams.
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

    fun queryRecords(prevWord: String, limit: Int = DEFAULT_LIMIT): List<BigramRecord> {
        val cleanPrev = prevWord.trim()
        if (!isValidWord(cleanPrev) || limit <= 0) return emptyList()
        return backend.queryNext(cleanPrev, limit)
            .filter { it.nextWord != cleanPrev }
    }

    fun queryNextWords(prevWord: String, limit: Int = DEFAULT_LIMIT): List<String> {
        return queryRecords(prevWord, limit).map { it.nextWord }
    }

    fun queryCandidates(prevWord: String, limit: Int = DEFAULT_LIMIT): List<FlypyCandidate> {
        return queryRecords(prevWord, limit).map { FlypyCandidate(text = it.nextWord) }
    }

    fun getRecord(prevWord: String, nextWord: String): BigramRecord? {
        return backend.getRecord(prevWord.trim(), nextWord.trim())
    }

    fun getBigramCount(prevWord: String, nextWord: String): Int {
        return getRecord(prevWord, nextWord)?.count ?: 0
    }

    fun clearAll() {
        backend.clearAll()
    }

    fun totalBigramCount(): Int = backend.totalBigramCount()

    companion object {
        const val DB_NAME = "typefree_predictions.db"
        const val DB_VERSION = 1
        const val TABLE_BIGRAMS = "user_bigrams"
        const val DEFAULT_LIMIT = 8

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

                    override fun queryNext(prevWord: String, limit: Int): List<BigramRecord> =
                        backend().queryNext(prevWord, limit)

                    override fun getRecord(prevWord: String, nextWord: String): BigramRecord? =
                        backend().getRecord(prevWord, nextWord)

                    override fun clearAll() = backend().clearAll()

                    override fun totalBigramCount(): Int = backend().totalBigramCount()
                }
            )

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
