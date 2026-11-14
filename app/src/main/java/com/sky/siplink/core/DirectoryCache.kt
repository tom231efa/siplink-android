package com.sky.siplink.core

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sky.siplink.core.logic.NameLookup
import com.sky.siplink.core.model.DirectoryContact

/**
 * Local copy of the directory entries the LDAP server has returned so far, in a SQLite database
 * in the app's private storage. It answers searches at once and without the server.
 *
 * The copy stays below [maxBytes]: when it grows beyond that, the entries the server has not
 * returned for the longest time go first. Entries not returned for [MAX_AGE_MS] are dropped as
 * well, so that people who left the directory do not stay in the copy for ever.
 *
 * Every call blocks on file access: use it from a worker thread.
 */
class DirectoryCache(
    context: Context,
    private val maxBytes: Long = MAX_BYTES,
    fileName: String = FILE_NAME,
) {
    data class Stats(val entries: Long = 0, val bytes: Long = 0, val maxBytes: Long = MAX_BYTES)

    private val helper = object : SQLiteOpenHelper(context, fileName, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            // "key" is the number as NameLookup compares numbers, for finding the name of a caller.
            db.execSQL(
                "CREATE TABLE $TABLE (target TEXT NOT NULL, name TEXT NOT NULL, number TEXT NOT NULL, " +
                    "organization TEXT NOT NULL, key TEXT NOT NULL, seen INTEGER NOT NULL, UNIQUE (target, name))",
            )
            db.execSQL("CREATE INDEX ${TABLE}_seen ON $TABLE (seen)")
            db.execSQL("CREATE INDEX ${TABLE}_key ON $TABLE (key)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // It is only a copy: start again rather than migrate.
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }.apply {
        // Without a write-ahead log the database is a single file, whose length is its size.
        setWriteAheadLoggingEnabled(false)
    }

    /** Adds the entries, or marks the ones already there as seen at [now]. */
    fun store(contacts: List<DirectoryContact>, now: Long = System.currentTimeMillis()) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val insert = db.compileStatement(
                "INSERT OR REPLACE INTO $TABLE (target, name, number, organization, key, seen) VALUES (?, ?, ?, ?, ?, ?)",
            )
            for (contact in contacts) {
                insert.bindString(1, contact.callTarget)
                insert.bindString(2, contact.name)
                insert.bindString(3, contact.number)
                insert.bindString(4, contact.organization)
                insert.bindString(5, NameLookup.key(contact.number).orEmpty())
                insert.bindLong(6, now)
                insert.executeInsert()
            }
            db.delete(TABLE, "seen < ?", arrayOf((now - MAX_AGE_MS).toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        trim(db)
    }

    /** Entries whose name or number contains [query], by name, at most [limit] of them. */
    fun search(query: String, limit: Int): List<DirectoryContact> {
        val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val contacts = ArrayList<DirectoryContact>()
        helper.readableDatabase.rawQuery(
            "SELECT name, number, target, organization FROM $TABLE " +
                "WHERE name LIKE ? ESCAPE '\\' OR number LIKE ? ESCAPE '\\' ORDER BY name LIMIT ?",
            arrayOf(pattern, pattern, limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                contacts += DirectoryContact(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))
            }
        }
        return contacts
    }

    /**
     * The entry of the number behind [key] ([NameLookup.key]): one with exactly that number, else
     * one that is the same line with or without a prefix ([NameLookup.sameLine]).
     */
    fun find(key: String): DirectoryContact? {
        val db = helper.readableDatabase
        one(db, "key = ?", key)?.let { return it }
        if (key.length < NameLookup.MIN_SUFFIX_DIGITS || !key.all(Char::isDigit)) return null
        // No index helps here, but this is one pass over the copy, off the main thread.
        return one(
            db,
            "length(key) >= ${NameLookup.MIN_SUFFIX_DIGITS} AND key NOT GLOB '*[^0-9]*' AND (key LIKE '%' || ?1 OR ?1 LIKE '%' || key)",
            key,
        )
    }

    private fun one(db: SQLiteDatabase, where: String, key: String): DirectoryContact? =
        db.rawQuery("SELECT name, number, target, organization FROM $TABLE WHERE $where ORDER BY seen DESC LIMIT 1", arrayOf(key)).use {
            if (it.moveToFirst()) DirectoryContact(it.getString(0), it.getString(1), it.getString(2), it.getString(3)) else null
        }

    /** The names of the entries with exactly the numbers behind [keys], by key. */
    fun names(keys: Collection<String>): Map<String, String> {
        val names = HashMap<String, String>()
        val db = helper.readableDatabase
        for (chunk in keys.distinct().chunked(MAX_QUERY_ARGUMENTS)) {
            val marks = chunk.joinToString(",") { "?" }
            // Oldest first, so that of two entries with one number the one seen last wins.
            db.rawQuery("SELECT key, name FROM $TABLE WHERE key IN ($marks) ORDER BY seen", chunk.toTypedArray()).use {
                while (it.moveToNext()) names[it.getString(0)] = it.getString(1)
            }
        }
        return names
    }

    fun clear() {
        val db = helper.writableDatabase
        db.delete(TABLE, null, null)
        db.execSQL("VACUUM")
    }

    fun stats(): Stats = Stats(DatabaseUtils.queryNumEntries(helper.readableDatabase, TABLE), sizeBytes(), maxBytes)

    fun close() = helper.close()

    private fun sizeBytes(): Long = java.io.File(helper.readableDatabase.path).length()

    /** Brings the file back under the limit, with room to spare so that this is not needed on every write. */
    private fun trim(db: SQLiteDatabase) {
        var size = sizeBytes()
        var rounds = 0
        while (size > maxBytes && rounds++ < MAX_TRIM_ROUNDS) {
            val entries = DatabaseUtils.queryNumEntries(db, TABLE)
            if (entries == 0L) break
            val target = maxBytes - maxBytes / TRIM_ROOM_DIVISOR
            val drop = (entries * (size - target) / size).coerceIn(1, entries)
            db.execSQL("DELETE FROM $TABLE WHERE rowid IN (SELECT rowid FROM $TABLE ORDER BY seen LIMIT $drop)")
            // The file only shrinks by itself where the database was created with auto-vacuum.
            if (sizeBytes() > maxBytes) db.execSQL("VACUUM")
            size = sizeBytes()
        }
    }

    companion object {
        /** Upper limit of the copy on disk: 100 MB. */
        const val MAX_BYTES = 100L * 1024 * 1024

        /** An entry the server has not returned for this long is dropped: 90 days. */
        const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

        private const val FILE_NAME = "directory-cache.db"
        private const val VERSION = 2
        private const val MAX_QUERY_ARGUMENTS = 500
        private const val TABLE = "contact"
        private const val MAX_TRIM_ROUNDS = 6

        /** Trimming frees a tenth of the limit beyond what is strictly needed. */
        private const val TRIM_ROOM_DIVISOR = 10
    }
}
