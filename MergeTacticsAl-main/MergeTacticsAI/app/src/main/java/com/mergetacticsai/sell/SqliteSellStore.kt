package com.mergetacticsai.sell

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Попытки продажи в локальной базе (mt_sell.db, только на этом устройстве). Хранятся последние [KEEP] записей. */
class SqliteSellStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "mt_sell.db", null, 1), SellStore {
    private var inserts = 0

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sell_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT, unit_id TEXT, unit_position INTEGER, " +
                "selected_unit TEXT, method TEXT, start_x REAL, start_y REAL, target_x REAL, target_y REAL, " +
                "gesture_ms INTEGER, result TEXT, success INTEGER, error_type TEXT, resolution TEXT, game_state TEXT, ts INTEGER)",
        )
        db.execSQL("CREATE INDEX idx_sell_method ON sell_attempts(resolution, game_state, method)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun add(a: SellAttempt) {
        runCatching {
            val v = ContentValues().apply {
                put("unit_id", a.unitId); put("unit_position", a.unitPosition); put("selected_unit", a.selectedUnit)
                put("method", a.method.name); put("start_x", a.startX); put("start_y", a.startY)
                put("target_x", a.targetX); put("target_y", a.targetY); put("gesture_ms", a.gestureMs)
                put("result", a.result); put("success", if (a.success) 1 else 0); put("error_type", a.errorType)
                put("resolution", a.resolution); put("game_state", a.gameState); put("ts", a.at)
            }
            writableDatabase.insert("sell_attempts", null, v)
            if (++inserts % 50 == 0) writableDatabase.execSQL("DELETE FROM sell_attempts WHERE id <= (SELECT MAX(id) FROM sell_attempts) - $KEEP")
        }
    }

    override fun stats(resolution: String, gameState: String): Map<SellMethod, Pair<Int, Int>> = runCatching {
        readableDatabase.rawQuery(
            "SELECT method, SUM(success), COUNT(*) FROM sell_attempts WHERE resolution = ? AND game_state = ? GROUP BY method",
            arrayOf(resolution, gameState),
        ).use { c ->
            val out = HashMap<SellMethod, Pair<Int, Int>>()
            while (c.moveToNext()) {
                val m = runCatching { SellMethod.valueOf(c.getString(0)) }.getOrNull() ?: continue
                out[m] = Pair(c.getInt(1), c.getInt(2))
            }
            out
        }
    }.getOrDefault(emptyMap())

    override fun recent(limit: Int): List<SellAttempt> = runCatching {
        readableDatabase.query("sell_attempts", null, null, null, null, null, "id DESC", limit.coerceIn(1, 500).toString()).use { c ->
            val out = ArrayList<SellAttempt>()
            fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n)) ?: ""
            while (c.moveToNext()) {
                val m = runCatching { SellMethod.valueOf(s("method")) }.getOrNull() ?: continue
                out.add(SellAttempt(
                    unitId = s("unit_id"), unitPosition = c.getInt(c.getColumnIndexOrThrow("unit_position")), selectedUnit = s("selected_unit"),
                    method = m, startX = c.getFloat(c.getColumnIndexOrThrow("start_x")), startY = c.getFloat(c.getColumnIndexOrThrow("start_y")),
                    targetX = c.getFloat(c.getColumnIndexOrThrow("target_x")), targetY = c.getFloat(c.getColumnIndexOrThrow("target_y")),
                    gestureMs = c.getLong(c.getColumnIndexOrThrow("gesture_ms")), result = s("result"),
                    success = c.getInt(c.getColumnIndexOrThrow("success")) == 1, errorType = s("error_type"),
                    resolution = s("resolution"), gameState = s("game_state"), at = c.getLong(c.getColumnIndexOrThrow("ts")),
                ))
            }
            out
        }
    }.getOrDefault(emptyList())

    override fun count(): Int = runCatching {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sell_attempts", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.getOrDefault(0)

    override fun totals(): Map<SellMethod, Pair<Int, Int>> = runCatching {
        readableDatabase.rawQuery("SELECT method, SUM(success), COUNT(*) FROM sell_attempts GROUP BY method", null).use { c ->
            val out = HashMap<SellMethod, Pair<Int, Int>>()
            while (c.moveToNext()) {
                val m = runCatching { SellMethod.valueOf(c.getString(0)) }.getOrNull() ?: continue
                out[m] = Pair(c.getInt(1), c.getInt(2))
            }
            out
        }
    }.getOrDefault(emptyMap())

    override fun clear() { runCatching { writableDatabase.delete("sell_attempts", null, null) } }

    companion object { const val KEEP = 2000 }
}
