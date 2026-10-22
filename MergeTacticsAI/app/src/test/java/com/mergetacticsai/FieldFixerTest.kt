package com.mergetacticsai

import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.PlacedUnit
import com.mergetacticsai.strategy.Build
import com.mergetacticsai.strategy.FieldFixer
import com.mergetacticsai.strategy.HexGrid
import org.junit.Assert.*
import org.junit.Test

class FieldFixerTest {
    private val trait = mapOf("knight" to "noble", "prince" to "noble", "archer" to "clan", "barbarian" to "clan")
    private val troopTrait: (String) -> String? = { trait[it] }
    private val costOf: (String) -> Int? = { mapOf("knight" to 2, "prince" to 5, "archer" to 2, "barbarian" to 2)[it] }
    private fun ix(row: Int, col: Int) = HexGrid.cells.indexOfFirst { it.row == row && it.col == col }
    private val allTaken = List(HexGrid.cells.size) { true }

    @Test fun pseudoIdsKnowTheirTrait() {
        assertEquals("clan", FieldFixer.traitOf(FieldFixer.pseudoId("clan"), troopTrait))
        assertEquals("noble", FieldFixer.traitOf("knight", troopTrait))
        assertTrue(FieldFixer.isPseudo("trait:boss")); assertFalse(FieldFixer.isPseudo("knight"))
    }

    @Test fun unneededByTraitBuild() {
        val build = Build(setOf("clan"))
        assertTrue(FieldFixer.isUnneeded(PlacedUnit("knight"), build, emptyMap(), troopTrait))
        assertFalse(FieldFixer.isUnneeded(PlacedUnit("archer"), build, emptyMap(), troopTrait))
    }

    @Test fun unneededByTroopBuild() {
        val build = Build(emptySet(), setOf("knight", "prince"))
        assertFalse(FieldFixer.isUnneeded(PlacedUnit("knight"), build, emptyMap(), troopTrait))
        assertTrue(FieldFixer.isUnneeded(PlacedUnit("archer"), build, emptyMap(), troopTrait))
    }

    @Test fun traitOnlyUnitIsUnneededOnlyIfTraitNotInBuild() {
        val build = Build(emptySet(), setOf("knight")) // сборка из рыцаря → нужна «благородный»
        assertFalse(FieldFixer.isUnneeded(PlacedUnit(FieldFixer.pseudoId("noble")), build, emptyMap(), troopTrait))
        assertTrue(FieldFixer.isUnneeded(PlacedUnit(FieldFixer.pseudoId("clan")), build, emptyMap(), troopTrait))
    }

    @Test fun unknownAndPlannedUnitsAreNeverUnneeded() {
        val build = Build(setOf("clan"))
        assertFalse(FieldFixer.isUnneeded(PlacedUnit(PlacedUnit.UNKNOWN), build, emptyMap(), troopTrait))
        val plan = mapOf(HexCell(4, 2) to PlacedUnit("knight"))
        assertFalse(FieldFixer.isUnneeded(PlacedUnit("knight"), build, plan, troopTrait))
    }

    @Test fun picksCheapestUnneededUnit() {
        val build = Build(setOf("clan"))
        val board = mapOf(ix(4, 1) to PlacedUnit("archer"), ix(5, 1) to PlacedUnit("prince", 1), ix(6, 1) to PlacedUnit("knight", 2))
        val v = FieldFixer.pickUnneeded(board, allTaken, build, emptyMap(), troopTrait, costOf)!!
        assertEquals(ix(5, 1), v.cellIndex) // принц 1★ дешевле по очкам, чем рыцарь 2★; лучница из сборки не трогаем
    }

    @Test fun nothingToRemoveWhenAllNeeded() {
        val build = Build(setOf("clan"))
        val board = mapOf(ix(4, 1) to PlacedUnit("archer"), ix(5, 1) to PlacedUnit("barbarian"))
        assertNull(FieldFixer.pickUnneeded(board, allTaken, build, emptyMap(), troopTrait, costOf))
    }

    @Test fun ignoresCellsThatAreAlreadyEmptyOrBlocked() {
        val build = Build(setOf("clan"))
        val i = ix(5, 1)
        val board = mapOf(i to PlacedUnit("knight"))
        val empty = List(HexGrid.cells.size) { it != i }
        assertNull(FieldFixer.pickUnneeded(board, empty, build, emptyMap(), troopTrait, costOf))
        assertNull(FieldFixer.pickUnneeded(board, allTaken, build, emptyMap(), troopTrait, costOf, blocked = setOf(i)))
    }

    @Test fun relocatesBuildUnitToItsPlannedFreeCell() {
        val plan = mapOf(HexCell(4, 2) to PlacedUnit("knight"))
        val from = ix(6, 1); val to = ix(4, 2)
        val occupied = List(HexGrid.cells.size) { it == from }
        val r = FieldFixer.pickRelocation(mapOf(from to PlacedUnit("knight")), occupied, plan)!!
        assertEquals(from, r.fromIx); assertEquals(to, r.toIx)
    }

    @Test fun noRelocationIfAlreadyOnPlannedCell() {
        val plan = mapOf(HexCell(4, 2) to PlacedUnit("knight"))
        val at = ix(4, 2)
        assertNull(FieldFixer.pickRelocation(mapOf(at to PlacedUnit("knight")), List(HexGrid.cells.size) { it == at }, plan))
    }

    @Test fun noRelocationIfPlannedCellIsTaken() {
        val plan = mapOf(HexCell(4, 2) to PlacedUnit("knight"))
        val from = ix(6, 1); val to = ix(4, 2)
        val occupied = List(HexGrid.cells.size) { it == from || it == to }
        assertNull(FieldFixer.pickRelocation(mapOf(from to PlacedUnit("knight")), occupied, plan))
    }

    @Test fun relocationGivesUpAfterFailure() {
        val plan = mapOf(HexCell(4, 2) to PlacedUnit("knight"))
        val from = ix(6, 1); val to = ix(4, 2)
        val occupied = List(HexGrid.cells.size) { it == from }
        assertNull(FieldFixer.pickRelocation(mapOf(from to PlacedUnit("knight")), occupied, plan, giveUp = setOf(from to to)))
    }

    @Test fun twoSameTroopsFillBothPlannedCells() {
        val plan = mapOf(HexCell(4, 1) to PlacedUnit("knight"), HexCell(4, 3) to PlacedUnit("knight"))
        val a = ix(4, 1); val b = ix(6, 0); val target = ix(4, 3)
        val occupied = List(HexGrid.cells.size) { it == a || it == b }
        val r = FieldFixer.pickRelocation(mapOf(a to PlacedUnit("knight"), b to PlacedUnit("knight")), occupied, plan)!!
        assertEquals(b, r.fromIx); assertEquals(target, r.toIx)
    }

    @Test fun unitsWithoutPlanOrIdentityStayPut() {
        val plan = mapOf(HexCell(4, 2) to PlacedUnit("knight"))
        val from = ix(6, 1)
        val occupied = List(HexGrid.cells.size) { it == from }
        assertNull(FieldFixer.pickRelocation(mapOf(from to PlacedUnit("archer")), occupied, plan))
        assertNull(FieldFixer.pickRelocation(mapOf(from to PlacedUnit(FieldFixer.pseudoId("noble"))), occupied, plan))
        assertNull(FieldFixer.pickRelocation(mapOf(from to PlacedUnit(PlacedUnit.UNKNOWN)), occupied, plan))
    }

    @Test fun keptTroopsAreNeverPickedForSale() {
        val build = Build(setOf("clan"))
        val board = mapOf(ix(4, 1) to PlacedUnit("knight"), ix(5, 1) to PlacedUnit("prince"))
        val v = FieldFixer.pickUnneeded(board, allTaken, build, emptyMap(), troopTrait, costOf, keep = setOf("knight"))!!
        assertEquals(ix(5, 1), v.cellIndex)
        assertNull(FieldFixer.pickUnneeded(board, allTaken, build, emptyMap(), troopTrait, costOf, keep = setOf("knight", "prince")))
    }

    @Test fun offBuildUnitOnFieldIsPickedWhileBuildUnitsStay() {
        val build = Build(emptySet(), setOf("archer", "barbarian"))
        val board = mapOf(ix(4, 1) to PlacedUnit("archer"), ix(4, 2) to PlacedUnit("barbarian"), ix(5, 1) to PlacedUnit("prince", 2))
        val v = FieldFixer.pickUnneeded(board, allTaken, build, emptyMap(), troopTrait, costOf)!!
        assertEquals("prince", v.unit.troopId)
    }

    @Test fun swapNeverSacrificesProtectedBuildTroops() {
        val build = Build(emptySet(), setOf("knight", "prince"))
        val board = mapOf(ix(4, 1) to PlacedUnit("knight", 1))
        val incoming = PlacedUnit("prince", 2)
        val tr: (String) -> String? = { troopTrait(it) }
        val free = com.mergetacticsai.strategy.SwapPlanner.pickVictim(board, allTaken, incoming, build, emptyMap(), tr, costOf)
        assertNotNull(free) // без защиты 1★ рыцарь слабее 2★ принца и мог бы уйти
        val guarded = com.mergetacticsai.strategy.SwapPlanner.pickVictim(board, allTaken, incoming, build, emptyMap(), tr, costOf, emptySet(), setOf("knight", "prince"))
        assertNull(guarded)
    }
}
