package com.typefree.ime.ime.jev.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

class SqliteJevMemoryBackend(
    context: Context
) : SQLiteOpenHelper(
    context.applicationContext ?: context,
    DB_NAME,
    null,
    DB_VERSION
), JevMemoryBackend {

    private val appContext: Context = context.applicationContext ?: context
    private val gson: Gson = GsonBuilder().create()
    private val axisMapType = object : TypeToken<Map<JevAxisGroup, AxisPreferenceStats>>() {}.type
    private val featureMapType = object : TypeToken<Map<StyleFeatureKey, FeatureCounter>>() {}.type

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SESSIONS (
                session_uid TEXT PRIMARY KEY NOT NULL,
                canonical_app TEXT NOT NULL,
                topology_kind TEXT NOT NULL,
                confirmed_is_group INTEGER NOT NULL DEFAULT 0,
                private_tag TEXT NOT NULL DEFAULT 'unset',
                group_tag TEXT NOT NULL DEFAULT 'unset',
                redacted_custom_note TEXT,
                masked_title_hint TEXT,
                axis_stats_json TEXT NOT NULL DEFAULT '{}',
                feature_counters_json TEXT NOT NULL DEFAULT '{}',
                interaction_count INTEGER NOT NULL DEFAULT 0,
                is_pinned INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL,
                last_active_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_jev_session_lru
            ON $TABLE_SESSIONS(is_pinned ASC, last_active_ms ASC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ALIASES (
                anchor_hash TEXT PRIMARY KEY NOT NULL,
                session_uid TEXT NOT NULL,
                bound_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_jev_alias_uid
            ON $TABLE_ALIASES(session_uid)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_BUCKETS (
                bucket_key TEXT PRIMARY KEY NOT NULL,
                axis_stats_json TEXT NOT NULL DEFAULT '{}',
                feature_counters_json TEXT NOT NULL DEFAULT '{}',
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    override fun loadAllSnapshot(): JevMemorySnapshot {
        return safeDbOp(defaultValue = JevMemorySnapshot()) {
            val db = readableDatabase
            val sessions = ArrayList<SessionMemoryRecord>()
            db.query(TABLE_SESSIONS, null, null, null, null, null, null).use { c ->
                val uidIdx = c.getColumnIndexOrThrow("session_uid")
                val appIdx = c.getColumnIndexOrThrow("canonical_app")
                val kindIdx = c.getColumnIndexOrThrow("topology_kind")
                val grpConfirmIdx = c.getColumnIndexOrThrow("confirmed_is_group")
                val privIdx = c.getColumnIndexOrThrow("private_tag")
                val grpIdx = c.getColumnIndexOrThrow("group_tag")
                val noteIdx = c.getColumnIndexOrThrow("redacted_custom_note")
                val hintIdx = c.getColumnIndexOrThrow("masked_title_hint")
                val axisIdx = c.getColumnIndexOrThrow("axis_stats_json")
                val featIdx = c.getColumnIndexOrThrow("feature_counters_json")
                val cntIdx = c.getColumnIndexOrThrow("interaction_count")
                val pinIdx = c.getColumnIndexOrThrow("is_pinned")
                val createdIdx = c.getColumnIndexOrThrow("created_at_ms")
                val activeIdx = c.getColumnIndexOrThrow("last_active_ms")

                while (c.moveToNext()) {
                    val uid = c.getString(uidIdx).orEmpty()
                    if (uid.isEmpty()) continue
                    val kind = runCatching {
                        TopologyKind.valueOf(c.getString(kindIdx).orEmpty())
                    }.getOrDefault(TopologyKind.PRIV)
                    val axisStats = parseAxisMap(c.getString(axisIdx))
                    val featureCounters = parseFeatureMap(c.getString(featIdx))
                    sessions.add(
                        SessionMemoryRecord(
                            sessionUid = uid,
                            canonicalApp = c.getString(appIdx).orEmpty(),
                            topologyKind = kind,
                            confirmedIsGroup = c.getInt(grpConfirmIdx) != 0,
                            privateTag = PrivateRelationTag.fromCode(c.getString(privIdx)),
                            groupTag = GroupSceneTag.fromCode(c.getString(grpIdx)),
                            redactedCustomNote = c.getString(noteIdx),
                            maskedTitleHint = c.getString(hintIdx),
                            axisStats = axisStats,
                            featureCounters = featureCounters,
                            interactionCount = c.getInt(cntIdx),
                            isPinned = c.getInt(pinIdx) != 0,
                            createdAtMs = c.getLong(createdIdx),
                            lastActiveAtMs = c.getLong(activeIdx)
                        )
                    )
                }
            }

            val aliases = ArrayList<AnchorAliasRecord>()
            db.query(TABLE_ALIASES, null, null, null, null, null, null).use { c ->
                val hashIdx = c.getColumnIndexOrThrow("anchor_hash")
                val uidIdx = c.getColumnIndexOrThrow("session_uid")
                val boundIdx = c.getColumnIndexOrThrow("bound_at_ms")
                while (c.moveToNext()) {
                    val hash = c.getString(hashIdx).orEmpty()
                    val uid = c.getString(uidIdx).orEmpty()
                    if (hash.isNotEmpty() && uid.isNotEmpty()) {
                        aliases.add(
                            AnchorAliasRecord(
                                anchorHash = hash,
                                sessionUid = uid,
                                boundAtMs = c.getLong(boundIdx)
                            )
                        )
                    }
                }
            }

            val buckets = ArrayList<BucketStyleProfile>()
            db.query(TABLE_BUCKETS, null, null, null, null, null, null).use { c ->
                val keyIdx = c.getColumnIndexOrThrow("bucket_key")
                val axisIdx = c.getColumnIndexOrThrow("axis_stats_json")
                val featIdx = c.getColumnIndexOrThrow("feature_counters_json")
                val updIdx = c.getColumnIndexOrThrow("updated_at_ms")
                while (c.moveToNext()) {
                    val key = c.getString(keyIdx).orEmpty()
                    if (key.isEmpty()) continue
                    buckets.add(
                        BucketStyleProfile(
                            bucketKey = key,
                            axisStats = parseAxisMap(c.getString(axisIdx)),
                            featureCounters = parseFeatureMap(c.getString(featIdx)),
                            updatedAtMs = c.getLong(updIdx)
                        )
                    )
                }
            }

            JevMemorySnapshot(sessions = sessions, aliases = aliases, buckets = buckets)
        }
    }

    override fun upsertSession(record: SessionMemoryRecord) {
        safeDbOp(Unit) {
            val cv = ContentValues().apply {
                put("session_uid", record.sessionUid)
                put("canonical_app", record.canonicalApp)
                put("topology_kind", record.topologyKind.name)
                put("confirmed_is_group", if (record.confirmedIsGroup) 1 else 0)
                put("private_tag", record.privateTag.code)
                put("group_tag", record.groupTag.code)
                put("redacted_custom_note", record.redactedCustomNote)
                put("masked_title_hint", record.maskedTitleHint)
                put("axis_stats_json", gson.toJson(record.axisStats))
                put("feature_counters_json", gson.toJson(record.featureCounters))
                put("interaction_count", record.interactionCount)
                put("is_pinned", if (record.isPinned) 1 else 0)
                put("created_at_ms", record.createdAtMs)
                put("last_active_ms", record.lastActiveAtMs)
            }
            writableDatabase.insertWithOnConflict(TABLE_SESSIONS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun upsertAlias(alias: AnchorAliasRecord) {
        safeDbOp(Unit) {
            val cv = ContentValues().apply {
                put("anchor_hash", alias.anchorHash)
                put("session_uid", alias.sessionUid)
                put("bound_at_ms", alias.boundAtMs)
            }
            writableDatabase.insertWithOnConflict(TABLE_ALIASES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun upsertBucket(profile: BucketStyleProfile) {
        safeDbOp(Unit) {
            val cv = ContentValues().apply {
                put("bucket_key", profile.bucketKey)
                put("axis_stats_json", gson.toJson(profile.axisStats))
                put("feature_counters_json", gson.toJson(profile.featureCounters))
                put("updated_at_ms", profile.updatedAtMs)
            }
            writableDatabase.insertWithOnConflict(TABLE_BUCKETS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun deleteSessionCascade(sessionUid: String) {
        safeDbOp(Unit) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete(TABLE_ALIASES, "session_uid = ?", arrayOf(sessionUid))
                db.delete(TABLE_SESSIONS, "session_uid = ?", arrayOf(sessionUid))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun pruneSessionsExcept(keepSessionUids: Set<String>) {
        safeDbOp(Unit) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                if (keepSessionUids.isEmpty()) {
                    db.delete(TABLE_ALIASES, null, null)
                    db.delete(TABLE_SESSIONS, null, null)
                } else {
                    val existingUids = ArrayList<String>()
                    db.query(TABLE_SESSIONS, arrayOf("session_uid"), null, null, null, null, null).use { c ->
                        while (c.moveToNext()) {
                            existingUids.add(c.getString(0))
                        }
                    }
                    for (uid in existingUids) {
                        if (uid !in keepSessionUids) {
                            db.delete(TABLE_ALIASES, "session_uid = ?", arrayOf(uid))
                            db.delete(TABLE_SESSIONS, "session_uid = ?", arrayOf(uid))
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun wipeAll() {
        safeDbOp(Unit) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete(TABLE_ALIASES, null, null)
                db.delete(TABLE_SESSIONS, null, null)
                db.delete(TABLE_BUCKETS, null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            runCatching { db.execSQL("VACUUM") }
        }
    }

    private fun parseAxisMap(json: String?): Map<JevAxisGroup, AxisPreferenceStats> {
        if (json.isNullOrBlank() || json == "{}") return emptyMap()
        return runCatching {
            val parsed: Map<JevAxisGroup?, AxisPreferenceStats?>? = gson.fromJson(json, axisMapType)
            val clean = LinkedHashMap<JevAxisGroup, AxisPreferenceStats>()
            parsed?.forEach { (k, v) ->
                if (k != null && v != null) {
                    clean[k] = v.sanitized()
                }
            }
            clean
        }.getOrDefault(emptyMap())
    }

    private fun parseFeatureMap(json: String?): Map<StyleFeatureKey, FeatureCounter> {
        if (json.isNullOrBlank() || json == "{}") return emptyMap()
        return runCatching {
            val parsed: Map<StyleFeatureKey?, FeatureCounter?>? = gson.fromJson(json, featureMapType)
            val clean = LinkedHashMap<StyleFeatureKey, FeatureCounter>()
            parsed?.forEach { (k, v) ->
                if (k != null && v != null) {
                    clean[k] = v
                }
            }
            clean
        }.getOrDefault(emptyMap())
    }

    private inline fun <T> safeDbOp(defaultValue: T, block: () -> T): T {
        return try {
            block()
        } catch (_: SQLiteDatabaseCorruptException) {
            runCatching {
                close()
                val dbFile: File = appContext.getDatabasePath(DB_NAME)
                if (dbFile.exists()) dbFile.delete()
                File(dbFile.path + "-journal").delete()
                File(dbFile.path + "-wal").delete()
            }
            defaultValue
        } catch (_: Throwable) {
            defaultValue
        }
    }

    companion object {
        const val DB_NAME = "jev_memory_v2.db"
        const val DB_VERSION = 1
        private const val TABLE_SESSIONS = "jev_session_memory"
        private const val TABLE_ALIASES = "jev_anchor_alias"
        private const val TABLE_BUCKETS = "jev_bucket_style_profile"
    }
}
