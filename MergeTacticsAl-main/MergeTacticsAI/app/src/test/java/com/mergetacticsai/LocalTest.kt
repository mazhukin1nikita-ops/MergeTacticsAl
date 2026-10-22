package com.mergetacticsai
import com.mergetacticsai.strategy.*
import org.junit.Assert.*
import org.junit.Test
class LocalTest {
    @Test fun pairCompletes() {
        val m = CardMemory(); val s = 0x0F0F0F0F0F0F0F0FL
        assertFalse(m.completesPair(s)); m.recordBuy(s); assertTrue(m.completesPair(s))
        m.recordBuy(s); assertFalse(m.completesPair(s))
    }
    @Test fun nearSignaturesMatch() {
        val m = CardMemory(); val s = 0x123456789ABCDEFL
        m.recordBuy(s); assertTrue(m.completesPair(s xor 0b101L))
        assertFalse(m.completesPair(s.inv()))
    }
    @Test fun pairBeatsCheaper() {
        val m = CardMemory(); val a = 0xF0F0F0F0F0F0F0F0uL.toLong(); val b = 0x0FF00FF00FF00FF0L
        m.recordBuy(a)
        assertEquals(0, LocalBuyPlanner.choose(listOf(CardView(0, a, 3), CardView(1, b, 2)), 5, m))
    }
    @Test fun skipsDimmedCards() {
        val m = CardMemory()
        assertNull(LocalBuyPlanner.choose(listOf(CardView(0, 1L, 2, false), CardView(1, 2L, 3, false)), null, m))
        assertEquals(1, LocalBuyPlanner.choose(listOf(CardView(0, 1L, 2, false), CardView(1, 2L, 4, true)), null, m))
    }
    @Test fun unknownCostStillBuyable() {
        val m = CardMemory()
        assertEquals(0, LocalBuyPlanner.choose(listOf(CardView(0, 1L, null, true)), null, m))
    }
    @Test fun cheaperPreferredWhenCostKnown() {
        val m = CardMemory()
        assertEquals(1, LocalBuyPlanner.choose(listOf(CardView(0, 1L, 4), CardView(1, 0xFFFFL, 2)), null, m))
    }
    @Test fun elixirLimitsKnownCosts() {
        val m = CardMemory()
        assertNull(LocalBuyPlanner.choose(listOf(CardView(0, 1L, 4)), 3, m))
    }
    @Test fun favoriteBonus() {
        val m = CardMemory(); val fav = 0x00FF00FF00FF00FFL; val other = 0x3333333333333333L
        assertEquals(1, LocalBuyPlanner.choose(listOf(CardView(0, other, 2), CardView(1, fav, 2)), 5, m, listOf(fav)))
    }
    @Test fun costCache() { val c = CostCache(); c.put(0xABCDL, 3); assertEquals(3, c.get(0xABCDL xor 1L)); assertNull(c.get(0x7777777777777777L)) }
    @Test fun hexCandidates() { assertEquals(12, HexCandidates.points.size); assertTrue(HexCandidates.points.all { it.y in 0.55f..0.78f }) }

    @Test fun arrowCardWins() {
        val m = CardMemory()
        assertEquals(0, LocalBuyPlanner.choose(listOf(CardView(0, 1L, 4, true, true), CardView(1, 0xFFFFL, 2, true, false)), null, m))
    }
    @Test fun unaffordableArrowSkipped() {
        val m = CardMemory()
        assertEquals(1, LocalBuyPlanner.choose(listOf(CardView(0, 1L, 4, false, true), CardView(1, 0xFFFFL, 2, true, false)), null, m))
    }
    @Test fun sellsCheapestLastOnTie() {
        val bench = booleanArrayOf(true, true, true, true, true)
        val info = listOf(BenchInfo(1L, 4), BenchInfo(2L, 2), BenchInfo(3L, 3), BenchInfo(0x7777L, 2), null)
        assertEquals(3, SellPlanner.choose(bench, info, emptyList()))
    }
    @Test fun doesNotSellMergePartner() {
        val bench = booleanArrayOf(true, true, true, true, true)
        val info = listOf(BenchInfo(1L, 4), BenchInfo(2L, 2), BenchInfo(3L, 3), BenchInfo(0x7777L, 2), null)
        assertEquals(1, SellPlanner.choose(bench, info, listOf(0x7777L)))
    }
    @Test fun nothingToSellOnEmptyBench() = assertNull(SellPlanner.choose(BooleanArray(5), emptyList(), emptyList()))
}
