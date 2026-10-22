package com.mergetacticsai.strategy

/** affordable — карточка не затемнена (эликсира хватает). cost == null — цена не прочитана, считаем средней. */
data class CardView(val index: Int, val sig: Long, val cost: Int?, val affordable: Boolean = true, val mergeArrow: Boolean = false,
    val allowed: Boolean = true, val bonus: Int = 0,
)

object LocalBuyPlanner {
    const val DEFAULT_COST = 3

    /**
     * Приоритет: карточка с зелёной стрелкой (объединение) → докупить пару из памяти (быстрое объединение) → дешевле → избранные карточки.
     * Недоступные (затемнённые) карточки пропускаются; если эликсир известен, проверяем и по цене.
     */
    fun choose(cards: List<CardView>, elixir: Int?, memory: CardMemory, favorites: List<Long> = emptyList()): Int? {
        var best = -1
        var bestScore = Int.MIN_VALUE
        for (c in cards) {
            if (!c.affordable || !c.allowed) continue
            val cost = c.cost ?: DEFAULT_COST
            if (elixir != null && c.cost != null && cost > elixir) continue
            var score = (10 - cost).coerceAtLeast(0) * 5
            if (c.mergeArrow) score += 200
            score += c.bonus
            if (memory.completesPair(c.sig)) score += 100
            if (favorites.any { java.lang.Long.bitCount(it xor c.sig) <= CardMemory.MAX_DIST }) score += 30
            if (score > bestScore) { bestScore = score; best = c.index }
        }
        return if (best >= 0) best else null
    }
}
