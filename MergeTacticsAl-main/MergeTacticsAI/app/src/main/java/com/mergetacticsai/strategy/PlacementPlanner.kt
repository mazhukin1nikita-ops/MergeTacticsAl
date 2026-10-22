package com.mergetacticsai.strategy

import com.mergetacticsai.ai.Point
import com.mergetacticsai.game.GameState
import com.mergetacticsai.game.GameUnit
import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.HexMap
import com.mergetacticsai.map.PlacedUnit
import kotlin.math.abs

/** Решает, кого со скамейки выставить на свободные клетки поля. До 3 перемещений за раз. */
object PlacementPlanner {
    private const val MIN_DIST = 0.05f

    /**
     * Возвращает пары (слот скамейки, точка на экране). Пусто, если лимит поля неизвестен или исчерпан.
     * Если передан [position] и известно войско ([unitOf]), клетку выбирает [PositionEngine], иначе берётся первая свободная.
     * [board] — уже стоящие войска игрока; выставленные в этом вызове добавляются к нему.
     */
    fun plan(
        s: GameState, used: Int, max: Int, free: List<Point>, occupied: List<Point>,
        position: PositionContext? = null,
        unitOf: (GameUnit) -> PlacedUnit? = { null },
        board: Map<HexCell, PlacedUnit> = emptyMap(),
    ): List<Pair<Int, Point>> {
        if (used < 0 || max <= 0) return emptyList()
        var capacity = (max - used).coerceAtLeast(0)
        val targets = free.filter { f ->
            f.y in 0.55f..0.78f && occupied.none { o -> abs(o.x - f.x) < MIN_DIST && abs(o.y - f.y) < MIN_DIST }
        }.toMutableList()
        val out = mutableListOf<Pair<Int, Point>>()
        var placed = board
        for ((i, u) in s.bench.withIndex()) {
            if (u == null || capacity == 0 || targets.isEmpty()) continue
            val unit = if (position != null) unitOf(u) else null
            var pick = 0
            if (unit != null) {
                val cells = targets.map { cellAt(it) }
                val best = PositionEngine.rank(unit, cells.filterNotNull(), placed, position!!).firstOrNull()
                if (best != null) {
                    pick = cells.indexOf(best).coerceAtLeast(0)
                    placed = placed + (best to unit)
                }
            }
            out += i to targets.removeAt(pick)
            capacity--
        }
        return out.take(3)
    }

    /** Клетка поля под точкой экрана (доли 0..1) или null. */
    fun cellAt(p: Point): HexCell? = HexMap.nearest(p.x * HexMap.REF_W, p.y * HexMap.REF_H)
}
