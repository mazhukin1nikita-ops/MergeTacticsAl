package com.mergetacticsai

import com.mergetacticsai.map.*
import com.mergetacticsai.strategy.*
import org.junit.Assert.*
import org.junit.Test

/** Замена на поле и карта сборки: бот сначала смотрит на сборку, потом на расчёт позиционного ИИ. */
class SwapTest {
    private fun cell(r: Int, c: Int) = HexCell(r, c)
    private fun u(id: String, stars: Int = 1) = PlacedUnit(id, stars)
    private fun ix(r: Int, c: Int) = HexGrid.indexOf(HexGrid.cells.first { it.row == r && it.col == c })
    private val noTrait: (String) -> String? = { null }
    private val cost2: (String) -> Int? = { 2 }
    private fun occ(vararg i: Int) = HexGrid.cells.indices.map { it in i }

    // ---- кого убрать с поля
    @Test fun sellsUnitOutsideBuildForBuildUnit() {
        val build = Build(emptySet(), setOf("knight", "hero"))
        val board = mapOf(ix(5, 1) to u("archer"), ix(5, 2) to u("knight"))
        val v = SwapPlanner.pickVictim(board, occ(ix(5, 1), ix(5, 2)), u("hero"), build, emptyMap(), noTrait, cost2)
        assertNotNull(v)
        assertEquals(ix(5, 1), v!!.cellIndex)   // лучница вне сборки; рыцаря из сборки не трогаем
    }

    @Test fun doesNotSwapEqualBuildUnits() {
        val build = Build(emptySet(), setOf("knight", "hero"))
        val board = mapOf(ix(5, 2) to u("knight"))
        assertNull(SwapPlanner.pickVictim(board, occ(ix(5, 2)), u("hero"), build, emptyMap(), noTrait, cost2))
    }

    @Test fun planBeatsBuildMembership() {
        // Оба из сборки, но герой стоит на карте сборки, а лучница нет — лучница уступает место.
        val build = Build(emptySet(), setOf("archer", "hero"))
        val plan = mapOf(cell(6, 3) to u("hero"))
        val board = mapOf(ix(5, 1) to u("archer"))
        val v = SwapPlanner.pickVictim(board, occ(ix(5, 1)), u("hero"), build, plan, noTrait, cost2)
        assertEquals(ix(5, 1), v!!.cellIndex)
    }

    @Test fun victimOnPlannedCellGoesFirst() {
        val build = Build(emptySet(), setOf("hero"))
        val plan = mapOf(cell(6, 3) to u("hero"))
        val board = mapOf(ix(5, 0) to u("a"), ix(6, 3) to u("b"))
        val v = SwapPlanner.pickVictim(board, occ(ix(5, 0), ix(6, 3)), u("hero"), build, plan, noTrait, cost2)
        assertEquals(ix(6, 3), v!!.cellIndex)
    }

    @Test fun twoStarIncomingReplacesOneStarOfSameStatus() {
        val build = Build(emptySet(), setOf("hero"))
        val board = mapOf(ix(5, 0) to u("hero", 1))
        assertNotNull(SwapPlanner.pickVictim(board, occ(ix(5, 0)), u("hero", 2), build, emptyMap(), noTrait, cost2))
    }

    @Test fun nothingSoldForOutsiderOrUnknown() {
        val build = Build(emptySet(), setOf("hero"))
        val board = mapOf(ix(5, 0) to u("a"))
        assertNull(SwapPlanner.pickVictim(board, occ(ix(5, 0)), u("outsider"), build, emptyMap(), noTrait, cost2))
        assertNull(SwapPlanner.pickVictim(board, occ(ix(5, 0)), PlacedUnit(PlacedUnit.UNKNOWN), build, emptyMap(), noTrait, cost2))
    }

    @Test fun ignoresEmptyOrBlockedCells() {
        val build = Build(emptySet(), setOf("hero"))
        val board = mapOf(ix(5, 0) to u("a"), ix(5, 1) to u("b"))
        // клетка 5,0 уже пуста по картинке, клетка 5,1 заблокирована
        assertNull(SwapPlanner.pickVictim(board, occ(ix(5, 1)), u("hero"), build, emptyMap(), noTrait, cost2, blocked = setOf(ix(5, 1))))
    }

    @Test fun traitBuildCountsAsBuild() {
        val build = Build(setOf("noble"))
        val traitOf: (String) -> String? = { if (it == "knight" || it == "princess") "noble" else "clan" }
        val board = mapOf(ix(5, 0) to u("archer"), ix(5, 1) to u("knight"))
        val v = SwapPlanner.pickVictim(board, occ(ix(5, 0), ix(5, 1)), u("princess"), build, emptyMap(), traitOf, cost2)
        assertEquals(ix(5, 0), v!!.cellIndex)
    }

    @Test fun cheaperOutsiderSoldFirst() {
        val build = Build(emptySet(), setOf("hero"))
        val costOf: (String) -> Int? = { if (it == "cheap") 2 else 5 }
        val board = mapOf(ix(5, 0) to u("dear"), ix(5, 1) to u("cheap"))
        val v = SwapPlanner.pickVictim(board, occ(ix(5, 0), ix(5, 1)), u("hero"), build, emptyMap(), noTrait, costOf)
        assertEquals(ix(5, 1), v!!.cellIndex)
    }

    // ---- карта сборки задаёт клетку
    @Test fun buildMapCellIsUsed() {
        val ctx = PositionContext(plan = mapOf(cell(6, 2) to u("archer")))
        val got = PositionEngine.pickGridCell(u("archer"), HexGrid.cells.map { false }, emptySet(), emptyMap(), ctx)!!
        assertEquals(6, got.row); assertEquals(2, got.col)
    }

    @Test fun busyBuildMapCellFallsBackToRanking() {
        val ctx = PositionContext(radii = mapOf("archer" to 3), plan = mapOf(cell(6, 2) to u("archer")))
        val occupied = HexGrid.cells.map { it.row == 6 && it.col == 2 }
        val got = PositionEngine.pickGridCell(u("archer"), occupied, emptySet(), emptyMap(), ctx)!!
        assertFalse(got.row == 6 && got.col == 2)
        assertEquals(7, got.row)  // дальний бой — назад, как и без карты
    }

    @Test fun otherUnitsIgnoreBuildMap() {
        val ctx = PositionContext(plan = mapOf(cell(6, 2) to u("archer")))
        val got = PositionEngine.pickGridCell(u("knight"), HexGrid.cells.map { false }, emptySet(), emptyMap(), ctx)!!
        assertEquals(HexGrid.preferred[0], got)
    }

    @Test fun twoCopiesInPlanPickFreeOne() {
        val plan = mapOf(cell(5, 1) to u("archer"), cell(5, 4) to u("archer"))
        val occupied = HexGrid.cells.map { it.row == 5 && it.col == 1 }
        val got = PositionEngine.pickGridCell(u("archer"), occupied, emptySet(), emptyMap(), PositionContext(plan = plan))!!
        assertEquals(5, got.row); assertEquals(4, got.col)
    }

    @Test fun badCellInPlanIsSkipped() {
        val plan = mapOf(cell(6, 2) to u("archer"))
        val bad = setOf(ix(6, 2))
        val got = PositionEngine.pickGridCell(u("archer"), HexGrid.cells.map { false }, bad, emptyMap(), PositionContext(plan = plan))!!
        assertFalse(got.row == 6 && got.col == 2)
    }

    // ---- хранение карты сборки
    @Test fun enemySideDroppedFromBuildLayoutCodec() {
        val m = mapOf(cell(6, 2) to u("archer"), cell(1, 1) to u("x"))
        val back = MapCodec.decodeLayout(MapCodec.encodeLayout(m)).filterKeys { HexMap.isOwn(it) }
        assertEquals(mapOf(cell(6, 2) to u("archer")), back)
    }
}
