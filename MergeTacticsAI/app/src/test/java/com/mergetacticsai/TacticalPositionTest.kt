package com.mergetacticsai

import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.PlacedUnit
import com.mergetacticsai.strategy.PositionContext
import com.mergetacticsai.strategy.PositionEngine
import org.junit.Assert.assertNull
import org.junit.Test

class TacticalPositionTest {
    @Test fun noOpponentMeansNoTacticalMove() {
        val own = mapOf(HexCell(6, 2) to PlacedUnit("archer"))
        val free = listOf(HexCell(7, 2))
        assertNull(PositionEngine.bestTacticalMove(own, free, PositionContext()))
    }

    @Test fun incompleteCombatDataMeansNoTacticalMove() {
        val own = mapOf(HexCell(6, 2) to PlacedUnit("archer"))
        val enemy = mapOf(HexCell(2, 2) to PlacedUnit("knight"))
        val free = listOf(HexCell(7, 2))
        assertNull(PositionEngine.bestTacticalMove(own, free, PositionContext(enemy = enemy)))
    }
}
