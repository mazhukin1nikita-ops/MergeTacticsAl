package com.mergetacticsai.ai

import com.mergetacticsai.strategy.Action
import org.json.JSONObject

/** Разбирает JSON-план от ИИ. Любой неверный ответ отклоняется целиком. */
object DecisionParser {
    const val MAX_ACTIONS = 10

    fun parse(raw: String): Result<List<Action>> = runCatching {
        val clean = raw.replace("```json", "").replace("```", "").trim()
        val arr = JSONObject(clean).getJSONArray("actions")
        require(arr.length() <= MAX_ACTIONS) { "Слишком много действий" }
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            when (val t = o.getString("type")) {
                "buy" -> Action.Buy(o.getInt("shopIndex"))
                "merge" -> Action.Merge(o.getInt("a"), o.getInt("b"))
                "move" -> Action.Move(o.getInt("from"), o.getInt("to"))
                "sell" -> Action.Sell(o.getInt("slot"))
                "refresh" -> Action.Refresh
                "wait" -> Action.Wait
                else -> throw IllegalArgumentException("Неизвестное действие: $t")
            }
        }
    }
}
