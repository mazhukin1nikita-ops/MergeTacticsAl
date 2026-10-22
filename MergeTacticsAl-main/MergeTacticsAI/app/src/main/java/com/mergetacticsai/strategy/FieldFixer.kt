package com.mergetacticsai.strategy

import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.PlacedUnit

/**
 * Правки того, что уже стоит на поле:
 * 1) убрать лишнее войско (не из сборки), когда для бойца сборки на скамейке нет места на поле;
 * 2) переставить бойца сборки на его клетку по карте сборки.
 *
 * Войско, чьё название по картинке не определилось, но известно свойство (значок на карточке), хранится как
 * «trait:<свойство>». Его считаем лишним только если это свойство сборке не нужно вообще.
 */
object FieldFixer {
    const val TRAIT_PREFIX = "trait:"

    fun pseudoId(trait: String) = TRAIT_PREFIX + trait
    fun isPseudo(id: String) = id.startsWith(TRAIT_PREFIX)

    fun traitOf(id: String, troopTrait: (String) -> String?): String? =
        if (isPseudo(id)) id.removePrefix(TRAIT_PREFIX) else troopTrait(id)

    /** Свойства, нужные сборке: выбранные свойства и свойства войск из выбранной «сборки на 7 карт». */
    fun buildTraits(build: Build, troopTrait: (String) -> String?): Set<String> =
        build.traits + build.troops.mapNotNull(troopTrait).filter { it.isNotEmpty() }

    /** true только когда мы уверены, что войско сборке не нужно. Неизвестное («?») и стоящее на карте сборки — нужное. */
    fun isUnneeded(u: PlacedUnit, build: Build, plan: Map<HexCell, PlacedUnit>, troopTrait: (String) -> String?): Boolean {
        if (u.isUnknown) return false
        if (SwapPlanner.planned(plan, u.troopId).isNotEmpty()) return false
        if (isPseudo(u.troopId)) return u.troopId.removePrefix(TRAIT_PREFIX) !in buildTraits(build, troopTrait)
        return !CycleStrategy.inBuild(troopTrait(u.troopId), u.troopId, build)
    }

    class Victim(val cellIndex: Int, val unit: PlacedUnit)

    /**
     * Самое малоценное из лишних войск на поле (меньше звёзд и дешевле — раньше).
     * [keep] — войска, которых не продаём никогда: «7 карт на арену», Лучница, те, у кого на скамейке есть копия для слияния.
     */
    fun pickUnneeded(
        board: Map<Int, PlacedUnit>, occupied: List<Boolean>, build: Build, plan: Map<HexCell, PlacedUnit>,
        troopTrait: (String) -> String?, costOf: (String) -> Int?, blocked: Set<Int> = emptySet(),
        keep: Set<String> = emptySet(),
    ): Victim? =
        board.entries
            .filter { (ix, u) ->
                ix in occupied.indices && occupied[ix] && ix !in blocked && u.troopId !in keep && isUnneeded(u, build, plan, troopTrait)
            }
            .minByOrNull { (_, u) -> u.stars.coerceIn(1, 3) * 100 + (costOf(u.troopId) ?: 3).coerceIn(0, 9) }
            ?.let { Victim(it.key, it.value) }

    class Relocation(val fromIx: Int, val toIx: Int, val unit: PlacedUnit)

    /**
     * Боец сборки стоит не на своей клетке по карте, а одна из его клеток свободна — переставляем.
     * [giveUp] — пары (откуда, куда), которые уже не получились.
     */
    fun pickRelocation(
        board: Map<Int, PlacedUnit>, occupied: List<Boolean>, plan: Map<HexCell, PlacedUnit>,
        blocked: Set<Int> = emptySet(), giveUp: Set<Pair<Int, Int>> = emptySet(),
    ): Relocation? {
        for ((ix, u) in board.toSortedMap()) {
            if (u.isUnknown || isPseudo(u.troopId) || ix !in HexGrid.cells.indices || ix in blocked) continue
            val want = SwapPlanner.planned(plan, u.troopId)
            if (want.isEmpty() || HexGrid.cells[ix].toHex() in want) continue
            val targets = want.mapNotNull { it.toGrid() }.map { HexGrid.indexOf(it) }
            val to = targets.firstOrNull { t -> t in occupied.indices && !occupied[t] && t !in blocked && (ix to t) !in giveUp }
            if (to != null) return Relocation(ix, to, u)
        }
        return null
    }
}
