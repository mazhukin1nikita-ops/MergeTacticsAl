package com.mergetacticsai.strategy

/** Карточка в магазине. trait — свойство (из «Обучения» или по значку), cost — цена, arrow — есть пара для слияния. */
data class ShopCard(
    val index: Int, val sig: Long, val cost: Int?, val trait: String?, val troopId: String?,
    val affordable: Boolean, val arrow: Boolean, val allowed: Boolean,
)

/** Юнит на скамейке. sig == null — юнит куплен не ботом (личность неизвестна). */
data class BenchUnit(val slot: Int, val sig: Long?, val cost: Int?, val trait: String?, val troopId: String?, val twoStar: Boolean)

/** Основная сборка: выбранные свойства или найденное автоматически «ядро». */
data class Build(val traits: Set<String>, val troops: Set<String> = emptySet()) {
    val found: Boolean get() = traits.isNotEmpty() || troops.isNotEmpty()
}

/**
 * «Круг» из стратегии пользователя (Императрица духов: +1 эликсир за слияние):
 * покупаем самых дешёвых с парой → сливаем до 2★ → продаём 2★ вне сборки → повторяем.
 * Стадия А — сборки нет, Б — сборка найдена.
 */
object CycleStrategy {
    const val ARCHER = "archer"
    private fun close(a: Long, b: Long) = java.lang.Long.bitCount(a xor b) <= CardMemory.MAX_DIST

    fun inBuild(trait: String?, troopId: String?, b: Build): Boolean =
        (troopId != null && troopId in b.troops) || (!trait.isNullOrEmpty() && trait in b.traits)

    /** Выбранные свойства важнее; иначе ядро — свойство, которого уже 3+ разных войска. */
    fun detectBuild(selected: Set<String>, ownedTraitCounts: Map<String, Int>): Build {
        if (selected.isNotEmpty()) return Build(selected)
        val core = ownedTraitCounts.filter { it.key.isNotEmpty() && it.value >= 3 }.maxByOrNull { it.value }?.key
        return if (core != null) Build(setOf(core)) else Build(emptySet())
    }

    /**
     * Приоритеты покупки: 1) дешёвый с парой (слияние), сборка — выше; 2) юнит сборки;
     * 3) дорогие вне сборки — только в стадии А. Стрелков держим, чтобы соперники не собрали 3★.
     */
    fun pickBuy(cards: List<ShopCard>, build: Build, benchHasSpace: Boolean): Int? {
        var best = -1
        var bestScore = Int.MIN_VALUE
        for (c in cards) {
            if (!c.affordable || !c.allowed) continue
            if (!benchHasSpace && !c.arrow) continue
            val cost = c.cost ?: 3
            val inB = inBuild(c.trait, c.troopId, build)
            val base: Int? = when {
                c.arrow && inB -> 330 - cost * 10
                c.arrow -> 300 - cost * 10
                inB -> 150 - cost
                !build.found -> if (cost <= 2) 60 - cost else 40 + cost * 2
                cost <= 2 -> 20 - cost
                else -> null
            }
            if (base == null) continue
            val score = base + if (c.troopId == ARCHER) 50 else 0
            if (score > bestScore) { bestScore = score; best = c.index }
        }
        return if (best >= 0) best else null
    }

    private fun hasPair(u: BenchUnit, bench: List<BenchUnit>, arrowSigs: List<Long>): Boolean {
        val s = u.sig ?: return false
        if (arrowSigs.any { close(it, s) }) return true
        return bench.any { o -> o.slot != u.slot && o.sig != null && close(o.sig, s) && o.twoStar == u.twoStar }
    }

    /**
     * Порядок продажи: 1) 2★ вне сборки; 2) самые дорогие одиночки вне сборки; 3) остальные одиночки.
     * Не продаём: юнитов сборки, стрелков и тех, у кого есть пара для слияния.
     */
    fun pickSell(bench: List<BenchUnit?>, arrowSigs: List<Long>, build: Build): Int? {
        val units = bench.filterNotNull()
        val sellable = units.filter { u ->
            !inBuild(u.trait, u.troopId, build) && u.troopId != ARCHER && !hasPair(u, units, arrowSigs)
        }
        return sellable.sortedWith(compareByDescending<BenchUnit> { it.twoStar }.thenByDescending { it.cost ?: 3 }).firstOrNull()?.slot
    }

    /**
     * Кого выставлять на поле: сборка → 2★ → самый дорогой (стадия А: дорогие помогают не терять HP).
     * Если игрок выбрал 7 карт для арены (onlyTroops), на поле идут только они.
     */
    fun pickPlacement(bench: List<BenchUnit?>, build: Build, onlyTroops: Set<String> = emptySet()): Int? =
        bench.filterNotNull().filter { onlyTroops.isEmpty() || it.troopId in onlyTroops }.sortedWith(
            compareByDescending<BenchUnit> { if (build.found && inBuild(it.trait, it.troopId, build)) 1 else 0 }
                .thenByDescending { it.twoStar }.thenByDescending { it.cost ?: 3 }
        ).firstOrNull()?.slot
}
