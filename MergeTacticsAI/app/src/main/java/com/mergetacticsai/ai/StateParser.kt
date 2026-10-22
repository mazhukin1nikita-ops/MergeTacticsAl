package com.mergetacticsai.ai

import com.mergetacticsai.game.GameState
import com.mergetacticsai.game.GameUnit
import org.json.JSONObject

/** Точка на экране в долях ширины/высоты (0..1). */
data class Point(val x: Float, val y: Float)

/** state == null, если модель не смогла описать магазин и эликсир. */
data class Observation(
    val phase: String,
    val state: GameState?,
    val boardUsed: Int = -1,
    val boardMax: Int = -1,
    val boardPoints: List<Point> = emptyList(),
    val freeHexes: List<Point> = emptyList(),
    val orbs: List<Point> = emptyList(),
)

object StateParser {
    fun parse(raw: String): Result<Observation> = runCatching {
        val o = JSONObject(raw.replace("```json", "").replace("```", "").trim())
        val phase = o.optString("phase", "other")
        val elixir = o.optInt("elixir", -1)

        fun units(key: String): List<GameUnit?> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { i ->
                val u = a.optJSONObject(i)
                if (u == null || u.optString("name").isBlank()) null
                else GameUnit(u.getString("name"), u.optInt("level", 1), u.optInt("cost", 0))
            }
        }

        fun points(key: String): List<Point> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val p = a.optJSONObject(i) ?: return@mapNotNull null
                val x = p.optDouble("x", Double.NaN); val y = p.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN() || x !in 0.0..1.0 || y !in 0.0..1.0) null
                else Point(x.toFloat(), y.toFloat())
            }
        }

        val shop = units("shop")
        val state = if (elixir < 0 || shop.isEmpty()) null
        else GameState(elixir, shop, units("bench"), units("board"))
        val lim = o.optJSONObject("boardLimit")
        Observation(
            phase, state,
            boardUsed = lim?.optInt("used", -1) ?: -1,
            boardMax = lim?.optInt("max", -1) ?: -1,
            boardPoints = points("board"),
            freeHexes = points("freeHexes"),
            orbs = points("orbs"),
        )
    }
}
