package com.mergetacticsai.strategy

import com.mergetacticsai.game.GameState

object MergeEngine {
    const val MAX_LEVEL = 3

    /** Все допустимые объединения, старшие уровни первыми. */
    fun findMerges(s: GameState): List<Action.Merge> {
        val slots = s.slots
        val out = mutableListOf<Action.Merge>()
        for (i in slots.indices) for (j in i + 1 until slots.size) {
            val a = slots[i] ?: continue
            val b = slots[j] ?: continue
            if (a.name == b.name && a.level == b.level && a.level < MAX_LEVEL) out += Action.Merge(i, j)
        }
        return out.sortedByDescending { slots[it.a]?.level ?: 0 }
    }

    /**
     * Эвристика: в осторожном режиме объединяем только если скамейка заполнена
     * (иначе нет места для покупок). Не объединяем «просто потому что можно».
     */
    fun plan(s: GameState, conservative: Boolean): List<Action.Merge> {
        val all = findMerges(s)
        return if (conservative && s.hasFreeBenchSlot()) emptyList() else all
    }
}
