package com.mergetacticsai.strategy

import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.HexMap
import com.mergetacticsai.map.PlacedUnit

/**
 * Замена на поле: поле заполнено (N/N), а на скамейке стоит войско из сборки. Решает, кого убрать (продать) с поля.
 *
 * Приоритет войска (больше — важнее): сборка (+1000) > стоит на карте сборки (+500) > звёзды (×100) > цена (0..9).
 * Убрать можно только того, кто важнее на целых [STAR_W] очков меньше, чем входящий: равных по статусу и звёздам не меняем,
 * чтобы бот не продавал одного бойца сборки ради другого такого же.
 * Рассматриваются только войска, которых бот сам выставил и чьё название известно: по картинке название не определить.
 */
object SwapPlanner {
    const val BUILD_W = 1000
    const val PLAN_W = 500
    const val STAR_W = 100

    class Victim(val cellIndex: Int, val unit: PlacedUnit, val score: Int)

    fun planned(plan: Map<HexCell, PlacedUnit>, troopId: String): Set<HexCell> =
        plan.filter { (cell, u) -> HexMap.isOwn(cell) && u.troopId == troopId }.keys

    fun score(
        u: PlacedUnit, build: Build, plan: Map<HexCell, PlacedUnit>,
        traitOf: (String) -> String?, costOf: (String) -> Int?,
    ): Int {
        val inBuild = CycleStrategy.inBuild(traitOf(u.troopId), u.troopId, build)
        val inPlan = planned(plan, u.troopId).isNotEmpty()
        return (if (inBuild) BUILD_W else 0) + (if (inPlan) PLAN_W else 0) +
            u.stars.coerceIn(1, 3) * STAR_W + (costOf(u.troopId) ?: 3).coerceIn(0, 9)
    }

    /**
     * [board] — клетки [HexGrid.cells] → войско, которое бот туда поставил; [occupied] — занятость по картинке;
     * [blocked] — клетки, с которыми нельзя работать. Возвращает клетку, откуда убрать войско, или null.
     * Если на карте сборки у входящего есть своя клетка, а на ней стоит менее важное войско, убираем его первым.
     */
    fun pickVictim(
        board: Map<Int, PlacedUnit>, occupied: List<Boolean>, incoming: PlacedUnit, build: Build,
        plan: Map<HexCell, PlacedUnit>, traitOf: (String) -> String?, costOf: (String) -> Int?,
        blocked: Set<Int> = emptySet(), protectedIds: Set<String> = emptySet(),
    ): Victim? {
        if (incoming.isUnknown) return null
        val inc = score(incoming, build, plan, traitOf, costOf)
        if (inc < PLAN_W) return null // входящий не из сборки и не на карте сборки — ради него ничего не продаём
        val want = planned(plan, incoming.troopId)
        var best: Victim? = null
        var bestKey = Int.MAX_VALUE
        for ((ix, u) in board) {
            if (ix !in occupied.indices || !occupied[ix] || ix in blocked || u.isUnknown || u.troopId in protectedIds) continue
            val s = score(u, build, plan, traitOf, costOf)
            if (inc - s < STAR_W) continue
            val key = s - (if (HexGrid.cells[ix].toHex() in want) 10_000 else 0)
            if (key < bestKey) { bestKey = key; best = Victim(ix, u, s) }
        }
        return best
    }
}
