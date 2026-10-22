package com.mergetacticsai.strategy

import com.mergetacticsai.data.CatalogItem
import com.mergetacticsai.data.Troop

/** Прогресс синергий: сколько войск каждого свойства собрано и сколько не хватает до следующего порога. */
object TraitEngine {
    data class Progress(val traitId: String, val count: Int, val activeTier: Int, val next: Int?, val missing: Int?)

    fun countsByTrait(owned: Set<String>, troops: Map<String, Troop>): Map<String, Int> =
        owned.mapNotNull { troops[it]?.trait }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()

    fun progress(owned: Set<String>, troops: Map<String, Troop>, traits: List<CatalogItem>): List<Progress> {
        val counts = countsByTrait(owned, troops)
        return traits.filter { it.tiers.isNotEmpty() }.map { t ->
            val c = counts[t.id] ?: 0
            val active = t.tiers.lastOrNull { c >= it } ?: 0
            val next = t.tiers.firstOrNull { it > c }
            Progress(t.id, c, active, next, next?.minus(c))
        }
    }

    fun describe(p: Progress, traitName: String): String =
        "$traitName: ${p.count}" + (p.next?.let { ", до порога $it нужно ещё ${p.missing}" } ?: ", максимум")
}
