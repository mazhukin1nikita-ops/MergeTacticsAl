package com.mergetacticsai.journal

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Журнал в локальной базе SQLite (файл mt_journal.db в приватной папке приложения, наружу не отправляется).
 * Хранится не больше [KEEP] последних записей.
 */
class SqliteJournal(context: Context) : SQLiteOpenHelper(context.applicationContext, "mt_journal.db", null, 1), DecisionJournal {
    private var inserts = 0

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE decisions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, session TEXT, round INTEGER, " +
                "kind TEXT, title TEXT, reason TEXT, details TEXT, status TEXT, outcome TEXT, outcome_at INTEGER)",
        )
        db.execSQL("CREATE INDEX idx_decisions_at ON decisions(at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Схема пока одна. При её смене старые записи нужно переносить, а не удалять.
    }

    override fun add(e: JournalEntry): Long = runCatching {
        val v = ContentValues().apply {
            put("at", e.at); put("session", e.session)
            if (e.round != null) put("round", e.round) else putNull("round")
            put("kind", e.kind); put("title", e.title); put("reason", e.reason); put("details", e.details)
            put("status", e.status); put("outcome", e.outcome)
            if (e.outcomeAt != null) put("outcome_at", e.outcomeAt) else putNull("outcome_at")
        }
        val id = writableDatabase.insert("decisions", null, v)
        if (++inserts % 50 == 0) trim()
        id
    }.getOrDefault(-1L)

    private fun trim() {
        runCatching { writableDatabase.execSQL("DELETE FROM decisions WHERE id <= (SELECT MAX(id) FROM decisions) - $KEEP") }
    }

    override fun setOutcome(id: Long, status: String, outcome: String, at: Long) {
        if (id < 0) return
        runCatching {
            val v = ContentValues().apply { put("status", status); put("outcome", outcome); put("outcome_at", at) }
            writableDatabase.update("decisions", v, "id = ?", arrayOf(id.toString()))
        }
    }

    override fun recent(limit: Int): List<JournalEntry> = runCatching {
        readableDatabase.query("decisions", null, null, null, null, null, "id DESC", limit.coerceIn(1, 1000).toString()).use { c ->
            val out = ArrayList<JournalEntry>()
            while (c.moveToNext()) out += read(c)
            out
        }
    }.getOrDefault(emptyList())

    override fun count(): Int = runCatching {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM decisions", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.getOrDefault(0)

    override fun clear() { runCatching { writableDatabase.delete("decisions", null, null) } }

    private fun read(c: Cursor): JournalEntry {
        fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name)) ?: ""
        fun l(name: String): Long? { val i = c.getColumnIndexOrThrow(name); return if (c.isNull(i)) null else c.getLong(i) }
        return JournalEntry(
            id = c.getLong(c.getColumnIndexOrThrow("id")), at = c.getLong(c.getColumnIndexOrThrow("at")),
            session = s("session"), round = l("round")?.toInt(), kind = s("kind"), title = s("title"),
            reason = s("reason"), details = s("details"), status = s("status"), outcome = s("outcome"), outcomeAt = l("outcome_at"),
        )
    }

    companion object { const val KEEP = 3000 }
}
