package com.mergetacticsai
import com.mergetacticsai.data.Catalog
import com.mergetacticsai.data.TroopDb
import com.mergetacticsai.strategy.BenchInfo
import com.mergetacticsai.strategy.SellPlanner
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class CatalogTest {
    private fun load(f: String) = Catalog.parse(File("src/main/assets/$f").readText())
    private fun troops() = TroopDb.parse(File("src/main/assets/troops.json").readText())
    @Test fun rulers() { val r = load("rulers.json"); assertEquals(10, r.size); assertEquals(r.size, r.map { it.id }.toSet().size) }
    @Test fun modifiers() { val m = load("modifiers.json"); assertEquals(26, m.size); assertEquals(m.size, m.map { it.id }.toSet().size) }
    @Test fun bossTiers() { assertEquals(listOf(1, 2, 3, 4), load("traits.json").first { it.id == "boss" }.tiers) }
    @Test fun clanTiers() { assertEquals(listOf(2, 4, 6), load("traits.json").first { it.id == "clan" }.tiers) }
    @Test fun troopsConsistent() {
        val t = troops(); val traitIds = load("traits.json").map { it.id }.toSet()
        assertEquals(42, t.size); assertEquals(t.size, t.map { it.id }.toSet().size)
        assertEquals(10, t.count { it.legacy }); assertEquals(3, t.count { it.cost == 0 })
        assertTrue(t.all { it.trait.isEmpty() || it.trait in traitIds })
        assertTrue(t.all { it.cost in setOf(0, 2, 3, 4, 5) })
    }
    @Test fun defaultEnabledIsCurrentPool() = assertEquals(29, TroopDb.defaultEnabled(troops()).size)
    @Test fun legacyTraitsHaveOnlyLegacyTroops() =
        assertTrue(troops().filter { it.trait == "rider" || it.trait == "electric" }.all { it.legacy })

    @Test fun twoStarSoldFirst() {
        val bench = booleanArrayOf(true, true, true, true, true)
        val info = listOf(BenchInfo(1L, 2), BenchInfo(2L, 5), BenchInfo(3L, 2), null, null)
        val two = booleanArrayOf(false, true, false, false, false)
        assertEquals(1, SellPlanner.choose(bench, info, emptyList(), two))
    }
    @Test fun noTwoStarFallsBackToCheapest() {
        val bench = booleanArrayOf(true, true, true, true, true)
        val info = listOf(BenchInfo(1L, 4), BenchInfo(2L, 2), BenchInfo(3L, 3), BenchInfo(0x7777L, 5), BenchInfo(0x1234L, 4))
        assertEquals(1, SellPlanner.choose(bench, info, emptyList()))
    }
}
