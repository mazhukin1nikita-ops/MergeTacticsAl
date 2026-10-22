package com.mergetacticsai.strategy

import com.mergetacticsai.game.GameState

object BuyPlanner {
    private fun norm(s: String) = s.lowercase().replace('ё', 'е').trim()

    /** Сравнение по основе слова, чтобы «гоблина» совпадало с «Гоблин». */
    fun matches(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        var n = 0
        while (n < x.length && n < y.length && x[n] == y[n]) n++
        return n >= minOf(4, x.length, y.length)
    }

    /** Выбирает, какую карточку магазина купить, либо null, если покупать нечего. */
    fun choose(s: GameState, c: StrategyConfig): Action.Buy? {
        if (!s.hasFreeBenchSlot()) return null
        var bestIndex = -1
        var bestScore = Int.MIN_VALUE
        for ((i, u) in s.shop.withIndex()) {
            if (u == null || u.cost > s.elixir) continue
            val inTargets = c.targets.any { matches(it, u.name) }
            if (c.mode == StrategyMode.CUSTOM_BUILD && !inTargets) continue
            var score = 0
            // Дополняем пару: тот же юнит того же уровня уже есть.
            if (s.slots.any { it != null && norm(it.name) == norm(u.name) && it.level == u.level }) score += 100
            if (inTargets) score += 200
            val cheap = (10 - u.cost).coerceAtLeast(0)
            if (c.mode == StrategyMode.MERGE_CHEAP) {
                score += cheap * 5
                if (matches(c.priorityUnit, u.name)) score += 30
            } else score += cheap
            if (score > bestScore) { bestScore = score; bestIndex = i }
        }
        return if (bestIndex >= 0) Action.Buy(bestIndex) else null
    }

    /** Предполагаемое состояние после покупки: юнит уходит со скамейки-слота, эликсир списывается. */
    fun applyBuy(s: GameState, shopIndex: Int): GameState {
        val u = s.shop.getOrNull(shopIndex) ?: return s
        val shop = s.shop.toMutableList().also { it[shopIndex] = null }
        val bench = s.bench.toMutableList()
        val free = bench.indexOfFirst { it == null }
        if (free >= 0) bench[free] = u
        return s.copy(elixir = s.elixir - u.cost, shop = shop, bench = bench)
    }
}
