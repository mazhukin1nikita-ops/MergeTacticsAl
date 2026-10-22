package com.mergetacticsai.strategy

import com.mergetacticsai.map.CellMark
import com.mergetacticsai.map.CombatContext
import com.mergetacticsai.map.CombatModel
import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.HexMap
import com.mergetacticsai.map.PlacedUnit
import com.mergetacticsai.map.Threat
import com.mergetacticsai.map.UnitStats
import kotlin.math.abs

/**
 * Всё, что позиционный ИИ знает помимо своей расстановки. Всё необязательное:
 * без данных выбор клетки совпадает со старым порядком [HexGrid.preferred].
 * [enemy] — известная расстановка противника (стороны 0-3); без неё бой не оценивается.
 */
class PositionContext(
    val enemy: Map<HexCell, PlacedUnit> = emptyMap(),
    val radii: Map<String, Int> = emptyMap(),
    val speeds: Map<String, Float> = emptyMap(),
    val stats: Map<String, UnitStats> = emptyMap(),
    val marks: Map<HexCell, CellMark> = emptyMap(),
    val combat: CombatContext = CombatContext(),
    /** Карта сборки с экрана «Тактическая карта»: где игрок хочет видеть каждое войско своей сборки. Только ваша сторона. */
    val plan: Map<HexCell, PlacedUnit> = emptyMap(),
)

/** Мост между сеткой бота [HexGrid] (экранные клетки) и картой [HexMap]: координаты рядов и колонок общие. */
fun Cell.toHex(): HexCell = HexCell(row, col)
fun HexCell.toGrid(): Cell? = HexGrid.cells.firstOrNull { it.row == row && it.col == col }

/** Одно обоснованное изменение расстановки, основанное на приближённой модели боя. */
data class TacticalMove(
    val from: HexCell,
    val to: HexCell,
    val unit: PlacedUnit,
    val scoreBefore: Float,
    val scoreAfter: Float,
    /** Полнота входных данных, не вероятность победы и не точность прогноза боя. */
    val inputConfidence: Float,
)

object PositionEngine {
    private const val DANGER_MARK = 1000f
    private const val COMBAT_WEIGHT = 100f
    private const val THREAT_WEIGHT = 4f
    private const val ROW_WEIGHT = 10f

    /** Свободные клетки от лучшей к худшей. Чужая сторона и неизвестные клетки отбрасываются. */
    fun rank(
        unit: PlacedUnit,
        free: Collection<HexCell>,
        own: Map<HexCell, PlacedUnit>,
        ctx: PositionContext = PositionContext(),
    ): List<HexCell> {
        val cands = free.filter { HexMap.isOwn(it) && it !in own }.distinct()
        if (cands.isEmpty()) return emptyList()
        val order = HexGrid.preferred.mapIndexed { i, c -> c.toHex() to i }.toMap()
        val radius = ctx.radii[unit.troopId]
        val useCombat = ctx.enemy.isNotEmpty() && !unit.isUnknown

        fun score(cell: HexCell): Float {
            var s = 0f
            if (ctx.marks[cell]?.dangerous == true) s += DANGER_MARK
            val layout = own + ctx.enemy + (cell to unit)
            if (useCombat) {
                val r = CombatModel.evaluate(layout, ctx.stats, ctx.combat, ctx.radii, ctx.speeds, ctx.marks)
                if (r.verdict != com.mergetacticsai.map.Verdict.UNKNOWN) s -= r.margin * COMBAT_WEIGHT
            }
            // Роль: ближний бой вперёд (малый ряд), дальний назад. Радиус неизвестен — только порядок сетки.
            if (radius != null) {
                val depth = cell.row - HexMap.OWN_FIRST_ROW // 0 — линия соприкосновения
                s += if (radius <= 1) depth * ROW_WEIGHT else (HexMap.ROWS - 1 - cell.row) * ROW_WEIGHT
                s += abs(HexMap.centerPx(cell).first - HexMap.REF_W / 2f) / HexMap.REF_W * 2f
            }
            // Опасность: сколько вражеских войск с известным радиусом достают до клетки.
            if (ctx.enemy.isNotEmpty() && ctx.radii.isNotEmpty()) {
                s += (Threat.danger(ctx.enemy, ctx.radii)[cell] ?: 0) * THREAT_WEIGHT * (if (radius != null && radius > 1) 2f else 1f)
            }
            return s
        }

        return cands.map { it to score(it) }
            .sortedWith(compareBy({ it.second }, { order[it.first] ?: Int.MAX_VALUE }))
            .map { it.first }
    }


    /**
     * Консервативно ищет одну полезную перестановку уже стоящего войска.
     * Используется лишь при полном наборе известных параметров обеих команд: без них
     * упрощённая симуляция не является основанием для жеста. Счёт модели остаётся
     * приближённым и не включает неизвестные способности и случайные эффекты.
     */
    fun bestTacticalMove(
        own: Map<HexCell, PlacedUnit>,
        free: Collection<HexCell>,
        ctx: PositionContext,
        minGain: Float = 0.04f,
    ): TacticalMove? {
        if (own.isEmpty() || ctx.enemy.isEmpty()) return null
        val all = own.values + ctx.enemy.values
        if (all.any { it.isUnknown }) return null
        if (all.any { ctx.stats[it.troopId]?.valid != true || ctx.radii[it.troopId] == null || ctx.speeds[it.troopId] == null }) return null
        val layout = own + ctx.enemy
        val baseline = CombatModel.evaluate(layout, ctx.stats, ctx.combat, ctx.radii, ctx.speeds, ctx.marks)
        if (baseline.verdict == com.mergetacticsai.map.Verdict.UNKNOWN || baseline.missing.isNotEmpty()) return null

        val danger = Threat.danger(ctx.enemy, ctx.radii)
        fun utility(candidate: Map<HexCell, PlacedUnit>, margin: Float): Float {
            val exposure = candidate.filterKeys { HexMap.isOwn(it) }.keys.sumOf { danger[it] ?: 0 }
            val marked = candidate.filterKeys { HexMap.isOwn(it) }.keys.count { ctx.marks[it]?.dangerous == true }
            // Угрозы учитываются как слабый штраф, а не как утверждение о гарантированном попадании.
            return margin - exposure * 0.02f - marked * 0.05f
        }
        val before = utility(layout, baseline.margin)
        val ranked = mutableListOf<TacticalMove>()
        for ((from, unit) in own) {
            val planned = SwapPlanner.planned(ctx.plan, unit.troopId)
            val destinations = free.filter { it != from && HexMap.isOwn(it) && it !in layout && (planned.isEmpty() || it in planned) }
            for (to in destinations) {
                val next = (layout - from) + (to to unit)
                val result = CombatModel.evaluate(next, ctx.stats, ctx.combat, ctx.radii, ctx.speeds, ctx.marks)
                if (result.verdict == com.mergetacticsai.map.Verdict.UNKNOWN || result.missing.isNotEmpty()) continue
                val after = utility(next, result.margin)
                if (after - before >= minGain) {
                    ranked += TacticalMove(from, to, unit, before, after, 0.8f)
                }
            }
        }
        return ranked.maxWithOrNull(compareBy<TacticalMove> { it.scoreAfter - it.scoreBefore }
            .thenBy { -it.from.row }.thenBy { -it.from.col }.thenBy { -it.to.row }.thenBy { -it.to.col })
    }


    /**
     * Выбор клетки для [LocalLoop][com.mergetacticsai.automation.LocalLoop]: [occupied] — занятость по [HexGrid.cells]
     * (по картинке), [bad] — клетки, куда не удалось поставить, [board] — что мы сами туда ставили раньше.
     * Если юнит неизвестен, порядок прежний — [HexGrid.preferred].
     */
    fun pickGridCell(
        unit: PlacedUnit?,
        occupied: List<Boolean>,
        bad: Set<Int>,
        board: Map<HexCell, PlacedUnit>,
        ctx: PositionContext = PositionContext(),
    ): Cell? {
        val free = HexGrid.cells.filter { c -> val ix = HexGrid.indexOf(c); !occupied[ix] && ix !in bad }
        if (free.isEmpty()) return null
        if (unit == null || unit.isUnknown) return HexGrid.preferred.firstOrNull { it in free }
        // Сборка важнее расчёта: если игрок поставил это войско на карту сборки и клетка свободна — ставим туда.
        // Если таких клеток несколько (два одинаковых войска), берём лучшую по расчёту.
        val freeHex = free.map { it.toHex() }.toSet()
        val planned = SwapPlanner.planned(ctx.plan, unit.troopId).filter { it in freeHex }
        if (planned.isNotEmpty()) return (rank(unit, planned, board, ctx).firstOrNull() ?: planned.first()).toGrid()
        val best = rank(unit, free.map { it.toHex() }, board, ctx).firstOrNull() ?: return free.first()
        return best.toGrid()
    }
}
