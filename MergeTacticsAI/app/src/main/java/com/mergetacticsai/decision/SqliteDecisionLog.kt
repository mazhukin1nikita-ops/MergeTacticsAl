package com.mergetacticsai.decision

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * База решений: файл mt_decisions.db в приватной папке приложения, наружу не отправляется.
 * Хранится не больше [KEEP] последних записей.
 */
class SqliteDecisionLog(context: Context) : SQLiteOpenHelper(context.applicationContext, "mt_decisions.db", null, 1), DecisionLog {
    private var inserts = 0

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE value_decisions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, session TEXT, round INTEGER, " +
                "field_state TEXT, shop_state TEXT, elixir INTEGER, sale_considered TEXT, buy_considered TEXT, " +
                "sale_value INTEGER, buy_cost INTEGER, value_before REAL, value_after REAL, " +
                "lost_synergies TEXT, gained_synergies TEXT, action TEXT, reason TEXT, confidence REAL, " +
                "executed TEXT, executed_note TEXT, result TEXT, field_troops TEXT)",
        )
        db.execSQL("CREATE INDEX idx_value_decisions_round ON value_decisions(session, round)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Схема пока одна. При её смене старые записи нужно переносить, а не удалять.
    }

    override fun add(r: DecisionRecord): Long = runCatching {
        val v = ContentValues().apply {
            put("at", r.at); put("session", r.session)
            if (r.round != null) put("round", r.round) else putNull("round")
            put("field_state", r.fieldState); put("shop_state", r.shopState)
            if (r.elixir != null) put("elixir", r.elixir) else putNull("elixir")
            put("sale_considered", r.saleConsidered); put("buy_considered", r.buyConsidered)
            put("sale_value", r.saleValue); put("buy_cost", r.buyCost)
            put("value_before", r.valueBefore); put("value_after", r.valueAfter)
            put("lost_synergies", r.lostSynergies); put("gained_synergies", r.gainedSynergies)
            put("action", r.action); put("reason", r.reason); put("confidence", r.confidence)
            put("executed", r.executed); put("executed_note", r.executedNote)
            put("result", r.result); put("field_troops", r.fieldTroops)
        }
        val id = writableDatabase.insert("value_decisions", null, v)
        if (++inserts % 50 == 0) trim()
        id
    }.getOrDefault(-1L)

    private fun trim() {
        runCatching { writableDatabase.execSQL("DELETE FROM value_decisions WHERE id <= (SELECT MAX(id) FROM value_decisions) - $KEEP") }
    }

    override fun setExecution(id: Long, status: String, note: String) {
        if (id < 0) return
        runCatching {
            val v = ContentValues().apply { put("executed", status); put("executed_note", note) }
            writableDatabase.update("value_decisions", v, "id = ?", arrayOf(id.toString()))
        }
    }

    override fun setResult(session: String, round: Int, result: String): Int = runCatching {
        val v = ContentValues().apply { put("result", result) }
        writableDatabase.update("value_decisions", v, "session = ? AND round = ?", arrayOf(session, round.toString()))
    }.getOrDefault(0)

    override fun recent(limit: Int): List<DecisionRecord> = query(null, limit)

    override fun withResults(limit: Int): List<DecisionRecord> = query("result IS NOT NULL AND result <> ''", limit)

    private fun query(where: String?, limit: Int): List<DecisionRecord> = runCatching {
        readableDatabase.query("value_decisions", null, where, null, null, null, "id DESC", limit.coerceIn(1, 5000).toString()).use { c ->
            val out = ArrayList<DecisionRecord>()
            while (c.moveToNext()) out += read(c)
            out
        }
    }.getOrDefault(emptyList())

    override fun count(): Int = runCatching {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM value_decisions", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.getOrDefault(0)

    override fun clear() { runCatching { writableDatabase.delete("value_decisions", null, null) } }

    private fun read(c: Cursor): DecisionRecord {
        fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name)) ?: ""
        fun l(name: String): Long? { val i = c.getColumnIndexOrThrow(name); return if (c.isNull(i)) null else c.getLong(i) }
        fun d(name: String): Double { val i = c.getColumnIndexOrThrow(name); return if (c.isNull(i)) 0.0 else c.getDouble(i) }
        return DecisionRecord(
            id = c.getLong(c.getColumnIndexOrThrow("id")), at = c.getLong(c.getColumnIndexOrThrow("at")),
            session = s("session"), round = l("round")?.toInt(),
            fieldState = s("field_state"), shopState = s("shop_state"), elixir = l("elixir")?.toInt(),
            saleConsidered = s("sale_considered"), buyConsidered = s("buy_considered"),
            saleValue = (l("sale_value") ?: 0L).toInt(), buyCost = (l("buy_cost") ?: 0L).toInt(),
            valueBefore = d("value_before"), valueAfter = d("value_after"),
            lostSynergies = s("lost_synergies"), gainedSynergies = s("gained_synergies"),
            action = s("action"), reason = s("reason"), confidence = d("confidence"),
            executed = s("executed"), executedNote = s("executed_note"), result = s("result"), fieldTroops = s("field_troops"),
        )
    }

    companion object { const val KEEP = 5000 }
}
