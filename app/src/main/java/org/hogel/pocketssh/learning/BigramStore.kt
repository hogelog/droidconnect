package org.hogel.pocketssh.learning

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite-backed bigram counts that drive the dynamic shortcut suggestions.
 *
 * Each row counts the number of times [next] has been observed immediately
 * after [prev] under the foreground command [context]. The row-head pseudo
 * token [BOL] is recorded as the [prev] of the first token on a line so the
 * same table also supplies "what command to start a new line with" candidates.
 *
 * The table is intentionally tiny — no FK, no indexes beyond the primary key.
 * All hot reads are served by `(context, prev)` look-ups against the PK.
 */
class BigramStore(context: Context) {

    private val helper = Helper(context.applicationContext)

    /** Increment the count for `(context, prev) -> next`, inserting if missing. */
    fun record(context: String, prev: String, next: String) {
        if (next.isEmpty()) return
        helper.writableDatabase.execSQL(
            "INSERT INTO bigram(context, prev, next, count) VALUES(?,?,?,1) " +
                "ON CONFLICT(context, prev, next) DO UPDATE SET count = count + 1",
            arrayOf(context, prev, next),
        )
    }

    /**
     * Return up to [limit] candidates for `(context, prev)` ordered by descending
     * count. Entries below [minCount] are filtered out so a single accidental
     * keystroke never sticks around in the suggestion bar.
     */
    fun topNext(
        context: String,
        prev: String,
        limit: Int,
        minCount: Int = DEFAULT_MIN_COUNT,
    ): List<String> {
        val cursor = helper.readableDatabase.rawQuery(
            "SELECT next FROM bigram WHERE context=? AND prev=? AND count>=? " +
                "ORDER BY count DESC, next ASC LIMIT ?",
            arrayOf(context, prev, minCount.toString(), limit.toString()),
        )
        val results = mutableListOf<String>()
        cursor.use { c ->
            while (c.moveToNext()) results.add(c.getString(0))
        }
        return results
    }

    /** Wipe every row. Used by the "clear learned suggestions" settings action. */
    fun clear() {
        helper.writableDatabase.delete("bigram", null, null)
    }

    /** Delete a single learned bigram so it stops appearing in suggestions. */
    fun delete(context: String, prev: String, next: String) {
        helper.writableDatabase.delete(
            "bigram",
            "context=? AND prev=? AND next=?",
            arrayOf(context, prev, next),
        )
    }

    /** Every row in the table, in primary-key order. Used by settings export. */
    fun snapshot(): List<Bigram> {
        val cursor = helper.readableDatabase.rawQuery(
            "SELECT context, prev, next, count FROM bigram ORDER BY context, prev, next",
            null,
        )
        val rows = mutableListOf<Bigram>()
        cursor.use { c ->
            while (c.moveToNext()) {
                rows += Bigram(c.getString(0), c.getString(1), c.getString(2), c.getInt(3))
            }
        }
        return rows
    }

    /** Rows scoped to a single [context], ordered by descending count. */
    fun snapshotByContext(context: String): List<Bigram> {
        val cursor = helper.readableDatabase.rawQuery(
            "SELECT context, prev, next, count FROM bigram WHERE context=? " +
                "ORDER BY count DESC, prev ASC, next ASC",
            arrayOf(context),
        )
        val rows = mutableListOf<Bigram>()
        cursor.use { c ->
            while (c.moveToNext()) {
                rows += Bigram(c.getString(0), c.getString(1), c.getString(2), c.getInt(3))
            }
        }
        return rows
    }

    /** Distinct contexts and how many bigrams are stored under each, alphabetical. */
    fun contextSummaries(): List<ContextSummary> {
        val cursor = helper.readableDatabase.rawQuery(
            "SELECT context, COUNT(*) FROM bigram GROUP BY context ORDER BY context ASC",
            null,
        )
        val rows = mutableListOf<ContextSummary>()
        cursor.use { c ->
            while (c.moveToNext()) {
                rows += ContextSummary(c.getString(0), c.getInt(1))
            }
        }
        return rows
    }

    /**
     * Replace every row with [rows] in a single transaction. Used by settings
     * import so a partial failure can't leave a half-written table.
     */
    fun replaceAll(rows: List<Bigram>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("bigram", null, null)
            val stmt = db.compileStatement(
                "INSERT INTO bigram(context, prev, next, count) VALUES(?,?,?,?)",
            )
            for (r in rows) {
                stmt.bindString(1, r.context)
                stmt.bindString(2, r.prev)
                stmt.bindString(3, r.next)
                stmt.bindLong(4, r.count.toLong())
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    data class Bigram(val context: String, val prev: String, val next: String, val count: Int)

    data class ContextSummary(val context: String, val count: Int)

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE bigram (
                    context TEXT NOT NULL,
                    prev TEXT NOT NULL,
                    next TEXT NOT NULL,
                    count INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(context, prev, next)
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // No upgrades yet. When the schema changes, decide per-migration
            // whether the learned data is worth migrating or can be wiped.
        }
    }

    companion object {
        const val BOL = "<BOL>"
        const val ENTER = "<ENTER>"
        const val UNKNOWN_CONTEXT = "(unknown)"
        private const val DB_NAME = "pocket_ssh.db"
        private const val DB_VERSION = 1
        private const val DEFAULT_MIN_COUNT = 2
    }
}
