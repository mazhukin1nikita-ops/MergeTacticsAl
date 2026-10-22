package com.mergetacticsai

import com.mergetacticsai.map.*
import org.junit.Assert.*
import org.junit.Test

class PathingTest {
    private fun cell(r: Int, c: Int) = HexCell(r, c)
    private fun u(id: String) = PlacedUnit(id)

    // ---- маршрут
    @Test fun pathToSelfIsSingleCell() = assertEquals(listOf(cell(4, 2)), PathFinder.find(cell(4, 2), cell(4, 2)))

    @Test fun straightPath() {
        val p = PathFinder.find(cell(4, 0), cell(4, 3))!!
        assertEquals(4, p.size)
        assertEquals(cell(4, 0), p.first())
        assertEquals(cell(4, 3), p.last())
    }

    @Test fun pathStepsEqualDistanceOnOpenBoard() {
        for (a in HexMap.all) for (b in HexMap.all) assertEquals(HexMap.distance(a, b), PathFinder.steps(a, b))
    }

    @Test fun pathMovesOnlyBetweenNeighbors() {
        val p = PathFinder.find(cell(7, 0), cell(0, 5))!!
        for (i in 1 until p.size) assertTrue(p[i] in HexMap.neighbors(p[i - 1]))
    }

    @Test fun blockedCellForcesDetour() {
        assertEquals(2, PathFinder.steps(cell(4, 0), cell(4, 2)))
        assertEquals(3, PathFinder.steps(cell(4, 0), cell(4, 2), setOf(cell(4, 1))))
    }

    @Test fun blockedTargetIsStillReachable() = assertEquals(1, PathFinder.steps(cell(4, 0), cell(4, 1), setOf(cell(4, 1))))

    @Test fun wallMeansNoPath() {
        val wall = (0 until HexMap.COLS).map { cell(5, it) }.toSet()
        assertNull(PathFinder.find(cell(4, 2), cell(6, 2), wall))
    }

    @Test fun outsideBoardHasNoPath() = assertNull(PathFinder.find(cell(4, 2), cell(9, 9)))

    @Test fun pathIsDeterministic() =
        assertEquals(PathFinder.find(cell(7, 5), cell(0, 0)), PathFinder.find(cell(7, 5), cell(0, 0)))

    @Test fun distanceFieldMatchesDistance() {
        val from = cell(4, 2)
        val f = PathFinder.distanceField(from)
        assertEquals(48, f.size)
        for (c in HexMap.all) assertEquals(HexMap.distance(from, c), f[c])
    }

    // ---- подход на дальность
    @Test fun stepsToRange() {
        assertEquals(2, PathFinder.stepsToRange(cell(4, 0), cell(4, 3), 1))
        assertEquals(1, PathFinder.stepsToRange(cell(4, 0), cell(4, 3), 2))
        assertEquals(0, PathFinder.stepsToRange(cell(4, 0), cell(4, 3), 3))
    }

    @Test fun zeroRangeCannotAttack() = assertNull(PathFinder.stepsToRange(cell(4, 0), cell(4, 3), 0))

    @Test fun fullyBlockedApproachIsUnreachable() {
        val ring = HexMap.neighbors(cell(4, 0)).toSet()
        assertNull(PathFinder.stepsToRange(cell(7, 5), cell(4, 0), 1, ring))
    }

    // ---- время до первого контакта
    @Test fun adjacentUnitsMeetAtOnce() {
        val layout = mapOf(cell(4, 2) to u("knight"), cell(3, 2) to u("x"))
        val e = ContactModel.between(cell(4, 2), cell(3, 2), layout, mapOf("knight" to 1), emptyMap())!!
        assertEquals(0, e.gap)
        assertEquals(0f, e.seconds!!, 0.001f)
    }

    @Test fun gapAndSecondsFromBothSpeeds() {
        val layout = mapOf(cell(5, 2) to u("knight"), cell(1, 2) to u("x"))
        val e = ContactModel.between(cell(5, 2), cell(1, 2), layout, mapOf("knight" to 1), mapOf("knight" to 1f, "x" to 2f))!!
        assertEquals(4, e.pathSteps)
        assertEquals(3, e.gap)
        assertEquals(1f, e.seconds!!, 0.001f)
    }

    @Test fun noSpeedMeansNoSeconds() {
        val layout = mapOf(cell(5, 2) to u("knight"), cell(1, 2) to u("x"))
        val e = ContactModel.between(cell(5, 2), cell(1, 2), layout, mapOf("knight" to 1), emptyMap())!!
        assertEquals(3, e.gap)
        assertNull(e.seconds)
    }

    @Test fun longerRangeShrinksGap() {
        val layout = mapOf(cell(5, 2) to u("archer"), cell(1, 2) to u("x"))
        val e = ContactModel.between(cell(5, 2), cell(1, 2), layout, mapOf("archer" to 3), mapOf("archer" to 1f))!!
        assertEquals(1, e.gap)
        assertEquals(1f, e.seconds!!, 0.001f)
    }

    @Test fun noRadiusMeansNoEngagement() {
        val layout = mapOf(cell(4, 2) to u("a"), cell(3, 2) to u("b"))
        assertNull(ContactModel.between(cell(4, 2), cell(3, 2), layout, emptyMap(), mapOf("a" to 1f)))
    }

    @Test fun unitInTheWayLengthensTheRoute() {
        val radii = mapOf("knight" to 1, "x" to 1, "w" to 1)
        val open = mapOf(cell(4, 0) to u("knight"), cell(4, 2) to u("x"))
        val shut = open + (cell(4, 1) to u("w"))
        assertEquals(1, ContactModel.between(cell(4, 0), cell(4, 2), open, radii, emptyMap())!!.gap)
        assertEquals(2, ContactModel.between(cell(4, 0), cell(4, 2), shut, radii, emptyMap())!!.gap)
    }

    @Test fun reportPicksEarliestPair() {
        val layout = mapOf(
            cell(4, 2) to u("knight"), cell(3, 2) to u("x"),
            cell(7, 0) to u("knight"), cell(0, 5) to u("x"),
        )
        val r = ContactModel.report(layout, mapOf("knight" to 1, "x" to 1), emptyMap())
        assertEquals(cell(4, 2), r.first!!.own)
        assertEquals(cell(3, 2), r.first!!.enemy)
        assertEquals(0, r.skipped)
        assertEquals(4, r.byUnit.size)
        assertEquals(0, r.byUnit[cell(3, 2)]!!.gap)
    }

    @Test fun reportWithoutRadiiSkipsEveryone() {
        val layout = mapOf(cell(4, 2) to u("a"), cell(3, 2) to u("b"))
        val r = ContactModel.report(layout, emptyMap(), emptyMap())
        assertNull(r.first)
        assertEquals(2, r.skipped)
    }

    @Test fun reportOnEmptyBoard() {
        val r = ContactModel.report(emptyMap(), emptyMap(), emptyMap())
        assertNull(r.first)
        assertEquals(0, r.skipped)
    }

    // ---- сохранение скоростей
    @Test fun speedsRoundTripAndLimits() {
        val m = mapOf("knight" to 1.5f, "archer" to 2.0f)
        assertEquals(m, MapCodec.decodeSpeeds(MapCodec.encodeSpeeds(m)))
        assertEquals(mapOf("c" to 1.5f), MapCodec.decodeSpeeds("a=0;b=99;c=1.5;d=x"))
    }
}
