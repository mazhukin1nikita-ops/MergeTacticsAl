package com.mergetacticsai.strategy

import com.mergetacticsai.data.Troop

/**
 * Правила выбора войск пользователем.
 * Строгий режим: покупаем только включённые войска (и только выбранных свойств, если они выбраны).
 * Стратегический: выбранное получает приоритет, остальное допускается.
 * Карточка со стрелкой объединения не блокируется никогда (нужна для слияния уже имеющихся).
 */
class TroopPolicy(val enabled: Set<String>, val traits: Set<String>, val strict: Boolean) {
    fun allows(t: Troop?, mergeArrow: Boolean): Boolean {
        if (mergeArrow || !strict) return true
        if (t == null) return false
        if (t.id !in enabled) return false
        return traits.isEmpty() || t.trait in traits
    }

    /** ownedTraitCounts — сколько разных войск каждого свойства уже куплено. */
    fun bonus(t: Troop?, ownedTraitCounts: Map<String, Int>): Int {
        if (t == null) return 0
        var b = 0
        if (t.id in enabled) b += 20 else b -= 20
        if (t.legacy) b -= 50
        if (t.trait in traits) {
            b += 60
            val next = (ownedTraitCounts[t.trait] ?: 0) + 1
            if (next in thresholds(t.trait)) b += 40
        }
        return b
    }

    companion object {
        fun thresholds(trait: String): List<Int> = if (trait == "boss") listOf(1, 2, 3, 4) else listOf(2, 4, 6)
    }
}
