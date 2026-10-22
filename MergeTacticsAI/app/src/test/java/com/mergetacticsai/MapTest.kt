package com.mergetacticsai

import com.mergetacticsai.map.*
import com.mergetacticsai.strategy.HexGrid
import org.junit.Assert.*
import org.junit.Test

class MapTest {
    private fun cell(r: Int, c: Int) = HexCell(r, c)
    private fun u(id: String, stars: Int = 1) = PlacedUnit(id, stars)

    // ---- сетка
    @Test fun boardSize() {
        assertEquals(48, HexMap.all.size)
        assertEquals(24, HexMap.ownCells.size)
        assertEquals(24, HexMap.enemyCells.size)
    }

    @Test fun matchesBotGridOnOwnRows() {
        for (g in HexGrid.cells) {
            val (x, y) = HexMap.center(cell(g.row, g.col))
            assertEquals(g.x, x, 0.0001f)
            assertEquals(g.y, y, 0.0001f)
        }
    }

    @Test fun neighborsAreAtDistanceOneAndSymmetric() {
        for (a in HexMap.all) {
            val ns = HexMap.neighbors(a)
            assertTrue(ns.isNotEmpty())
            for (n in ns) {
                assertEquals(1, HexMap.distance(a, n))
                assertTrue(a in HexMap.neighbors(n))
            }
            assertEquals(HexMap.all.filter { HexMap.distance(a, it) == 1 }.toSet(), ns.toSet())
        }
    }

    @Test fun distances() {
        assertEquals(0, HexMap.distance(cell(4, 2), cell(4, 2)))
        assertEquals(1, HexMap.distance(cell(4, 0), cell(3, 0)))
        assertEquals(5, HexMap.distance(cell(0, 0), cell(0, 5)))
        assertEquals(7, HexMap.distance(cell(0, 0), cell(7, 0)))
    }

    @Test fun withinExcludesCenterAndGrowsWithRadius() {
        val c = cell(5, 2)
        assertTrue(HexMap.within(c, 0).isEmpty())
        assertEquals(6, HexMap.within(c, 1).size)
        assertEquals(18, HexMap.within(c, 2).size)
        assertFalse(c in HexMap.within(c, 3))
    }

    @Test fun nearestFindsCellByItsCenter() {
        for (c in HexMap.all) {
            val (x, y) = HexMap.centerPx(c)
            assertEquals(c, HexMap.nearest(x, y))
        }
        assertNull(HexMap.nearest(0f, 0f))
    }

    @Test fun labelsAreUnique() = assertEquals(48, HexMap.all.map { HexMap.label(it) }.toSet().size)

    @Test fun screenshotRatio() {
        assertTrue(HexMap.ratioOk(1080, 2400))
        assertTrue(HexMap.ratioOk(1080, 2340))
        assertFalse(HexMap.ratioOk(1080, 1920))
        assertFalse(HexMap.ratioOk(0, 100))
    }

    // ---- угрозы и цели
    @Test fun dangerCountsOnlyKnownRadiiOnOwnSide() {
        val layout = mapOf(cell(3, 2) to u("x"), cell(2, 2) to u("noRadius"))
        val d = Threat.danger(layout, mapOf("x" to 1))
        assertEquals(mapOf(cell(4, 2) to 1, cell(4, 3) to 1), d)
    }

    @Test fun dangerStacks() {
        val layout = mapOf(cell(3, 2) to u("x"), cell(3, 3) to u("y"))
        val d = Threat.danger(layout, mapOf("x" to 1, "y" to 1))
        assertEquals(2, d[cell(4, 3)])
        assertEquals(1, d[cell(4, 2)])
    }

    @Test fun targetsAreOnlyOpponentUnitsInRange() {
        val layout = mapOf(cell(4, 2) to u("a"), cell(3, 2) to u("e"), cell(4, 3) to u("friend"))
        val zone = Threat.zone(cell(4, 2), layout[cell(4, 2)], mapOf("a" to 1))
        assertEquals(setOf(cell(3, 2)), Threat.targets(cell(4, 2), zone, layout))
    }

    @Test fun noRadiusMeansNoZone() = assertTrue(Threat.zone(cell(4, 2), u("a"), emptyMap()).isEmpty())

    // ---- рекомендация
    @Test fun meleeGoesFrontRangedGoesBack() {
        val layout = mapOf(cell(4, 0) to u("archer"), cell(7, 5) to u("knight"))
        val r = Recommender.recommend(layout, mapOf("archer" to 3, "knight" to 1))
        assertEquals("knight", r.layout[cell(4, 3)]?.troopId)
        assertEquals("archer", r.layout[cell(7, 2)]?.troopId)
        assertEquals(2, r.moves.size)
        assertEquals(2, r.layout.size)
    }

    @Test fun unitsWithoutRadiusStayInPlace() {
        val layout = mapOf(cell(5, 1) to u("mystery"))
        val r = Recommender.recommend(layout, emptyMap())
        assertEquals(layout, r.layout)
        assertTrue(r.moves.isEmpty())
        assertEquals(1, r.skippedUnknown)
    }

    @Test fun alreadyGoodPlacementHasNoMoves() {
        val r = Recommender.recommend(mapOf(cell(4, 3) to u("knight")), mapOf("knight" to 1))
        assertTrue(r.moves.isEmpty())
    }

    @Test fun enemySideIsNeverTouched() {
        val layout = mapOf(cell(1, 1) to u("x"), cell(4, 0) to u("knight"))
        val r = Recommender.recommend(layout, mapOf("knight" to 1, "x" to 3))
        assertEquals(u("x"), r.layout[cell(1, 1)])
    }

    @Test fun recommendationKeepsUnitCount() {
        val layout = HexMap.ownCells.take(10).mapIndexed { i, c -> c to u("t$i") }.toMap()
        val radii = layout.values.mapIndexed { i, v -> v.troopId to (1 + i % 3) }.toMap()
        val r = Recommender.recommend(layout, radii)
        assertEquals(10, r.layout.size)
        assertTrue(r.layout.keys.all { HexMap.isOwn(it) })
    }

    // ---- сохранение
    @Test fun layoutRoundTrip() {
        val m = mapOf(cell(4, 3) to u("goblin", 2), cell(0, 1) to u(PlacedUnit.UNKNOWN))
        assertEquals(m, MapCodec.decodeLayout(MapCodec.encodeLayout(m)))
    }

    @Test fun badLayoutIsIgnored() = assertTrue(MapCodec.decodeLayout("abc;1,2;99,99,x,1;4,3,,1").isEmpty())

    @Test fun starsAreClamped() = assertEquals(3, MapCodec.decodeLayout("4,3,knight,9")[cell(4, 3)]?.stars)

    @Test fun marksRoundTrip() {
        val m = mapOf(cell(5, 0) to CellMark.CURSED, cell(2, 4) to CellMark.JUMP)
        assertEquals(m, MapCodec.decodeMarks(MapCodec.encodeMarks(m)))
        assertTrue(MapCodec.decodeMarks("5,0,NOPE").isEmpty())
    }

    @Test fun radiiRoundTripAndLimits() {
        val m = mapOf("knight" to 1, "archer" to 3)
        assertEquals(m, MapCodec.decodeRadii(MapCodec.encodeRadii(m)))
        assertEquals(mapOf("c" to 2), MapCodec.decodeRadii("a=0;b=99;c=2"))
    }
}
