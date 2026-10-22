package com.mergetacticsai.map

/** Войско на клетке. troopId == [UNKNOWN] — юнит найден на скриншоте, но название ещё не задано. */
data class PlacedUnit(val troopId: String, val stars: Int = 1) {
    val isUnknown get() = troopId == UNKNOWN

    companion object { const val UNKNOWN = "?" }
}

/** Ручные пометки клеток. dangerous — клетка вредит войску; остальные — особые. */
enum class CellMark(val title: String, val dangerous: Boolean) {
    CURSED("Проклятая", true),
    ABYSS("Бездна", true),
    POISON("Ядовитая", true),
    JUMP("Прыжок", false),
    WORMHOLE("Червоточина", false),
    SPECIAL("Другая особая", false),
}

enum class MapView { ORIGINAL, RECOMMENDED }

/** Перестановка одного войска в рекомендации. */
data class Move(val unit: PlacedUnit, val from: HexCell, val to: HexCell)

data class Recommendation(val layout: Map<HexCell, PlacedUnit>, val moves: List<Move>, val skippedUnknown: Int)

/**
 * Временная простая рекомендация: ближний бой вперёд, дальний назад.
 * Работает только для войск, у которых задан радиус атаки; остальные остаются на месте.
 * Её заменит позиционный ИИ (этап 3).
 */
object Recommender {
    private fun centerDist(c: HexCell) = abs(HexMap.centerPx(c).first - HexMap.REF_W / 2f)
    private fun abs(v: Float) = if (v < 0) -v else v

    fun recommend(layout: Map<HexCell, PlacedUnit>, radii: Map<String, Int>): Recommendation {
        val own = layout.filterKeys { HexMap.isOwn(it) }
        val movable = own.filter { (_, u) -> radii[u.troopId] != null }
        val fixed = own.filterKeys { it !in movable.keys }
        val skipped = fixed.size
        if (movable.isEmpty()) return Recommendation(layout, emptyList(), skipped)

        val free = HexMap.ownCells.filter { it !in fixed.keys }
        val melee = movable.entries.filter { (radii[it.value.troopId] ?: 0) <= 1 }
            .sortedWith(compareByDescending<Map.Entry<HexCell, PlacedUnit>> { it.value.stars }.thenBy { it.key.row }.thenBy { it.key.col })
        val ranged = movable.entries.filter { (radii[it.value.troopId] ?: 0) > 1 }
            .sortedWith(compareByDescending<Map.Entry<HexCell, PlacedUnit>> { radii[it.value.troopId] ?: 0 }
                .thenByDescending { it.value.stars }.thenBy { it.key.row }.thenBy { it.key.col })

        val frontCells = free.sortedWith(compareBy<HexCell> { it.row }.thenBy { centerDist(it) }.thenBy { it.col })
        val result = fixed.toMutableMap()
        val moves = mutableListOf<Move>()
        val used = mutableSetOf<HexCell>()

        fun assign(e: Map.Entry<HexCell, PlacedUnit>, to: HexCell) {
            result[to] = e.value
            used += to
            if (to != e.key) moves += Move(e.value, e.key, to)
        }

        melee.zip(frontCells).forEach { (e, to) -> assign(e, to) }
        val backCells = free.filter { it !in used }
            .sortedWith(compareByDescending<HexCell> { it.row }.thenBy { centerDist(it) }.thenBy { it.col })
        ranged.zip(backCells).forEach { (e, to) -> assign(e, to) }

        val full = layout.filterKeys { !HexMap.isOwn(it) } + result
        return Recommendation(full, moves, skipped)
    }
}

/** Анализ: кого видит войско, что опасно. Всё считается по начальным позициям, без учёта движения в бою. */
object Threat {
    /** Клетки в радиусе атаки войска на [cell]; пусто, если радиус неизвестен. */
    fun zone(cell: HexCell, unit: PlacedUnit?, radii: Map<String, Int>): Set<HexCell> {
        val r = unit?.let { radii[it.troopId] } ?: return emptySet()
        return HexMap.within(cell, r)
    }

    /** Клетки в радиусе, где стоит вражеское (для войска игрока) или своё (для войска противника) войско. */
    fun targets(cell: HexCell, zone: Set<HexCell>, layout: Map<HexCell, PlacedUnit>): Set<HexCell> =
        zone.filter { layout.containsKey(it) && HexMap.isOwn(it) != HexMap.isOwn(cell) }.toSet()

    /** Сколько вражеских войск с известным радиусом достаёт до каждой клетки вашей стороны. */
    fun danger(layout: Map<HexCell, PlacedUnit>, radii: Map<String, Int>): Map<HexCell, Int> {
        val out = HashMap<HexCell, Int>()
        for ((cell, u) in layout) {
            if (HexMap.isOwn(cell)) continue
            for (t in zone(cell, u, radii)) if (HexMap.isOwn(t)) out[t] = (out[t] ?: 0) + 1
        }
        return out
    }

    /** Сколько вражеских войск достают до клетки [cell] вашей стороны. */
    fun threatenedBy(cell: HexCell, layout: Map<HexCell, PlacedUnit>, radii: Map<String, Int>): Int =
        danger(layout, radii)[cell] ?: 0
}
