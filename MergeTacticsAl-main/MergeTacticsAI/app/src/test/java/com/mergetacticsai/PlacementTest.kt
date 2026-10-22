package com.mergetacticsai
import com.mergetacticsai.ai.*
import com.mergetacticsai.game.*
import com.mergetacticsai.strategy.PlacementPlanner
import org.junit.Assert.*
import org.junit.Test
class PlacementTest {
    private val a = GameUnit("A", 1, 2)
    private val b = GameUnit("B", 1, 2)
    private fun st() = GameState(0, listOf(null, null, null), listOf(a, null, b, null, null), listOf(null))
    private val free = listOf(Point(0.5f, 0.7f), Point(0.6f, 0.7f), Point(0.4f, 0.7f))

    @Test fun placesWithinLimit() {
        val m = PlacementPlanner.plan(st(), 1, 3, free, emptyList())
        assertEquals(2, m.size); assertEquals(0, m[0].first); assertEquals(2, m[1].first)
    }
    @Test fun respectsLimit() = assertEquals(1, PlacementPlanner.plan(st(), 2, 3, free, emptyList()).size)
    @Test fun fullBoard() = assertTrue(PlacementPlanner.plan(st(), 3, 3, free, emptyList()).isEmpty())
    @Test fun unknownLimit() = assertTrue(PlacementPlanner.plan(st(), -1, -1, free, emptyList()).isEmpty())
    @Test fun skipsOccupiedAndEnemyHalf() {
        val m = PlacementPlanner.plan(st(), 0, 3, listOf(Point(0.5f, 0.3f), Point(0.5f, 0.7f)), listOf(Point(0.51f, 0.71f)))
        assertTrue(m.isEmpty())
    }
    @Test fun parsesNewFields() {
        val raw = """{"phase":"deploy","elixir":2,"shop":[{"name":"A","cost":2}],"bench":[null],"board":[{"name":"B","level":1,"x":0.5,"y":0.6}],
            "boardLimit":{"used":1,"max":3},"freeHexes":[{"x":0.4,"y":0.7},{"x":2,"y":0.7}],"orbs":[{"x":0.5,"y":0.45}]}"""
        val o = StateParser.parse(raw).getOrThrow()
        assertEquals(1, o.boardUsed); assertEquals(3, o.boardMax)
        assertEquals(1, o.freeHexes.size); assertEquals(1, o.orbs.size); assertEquals(1, o.boardPoints.size)
    }
}
