package com.mergetacticsai
import com.mergetacticsai.game.*
import com.mergetacticsai.strategy.*
import org.junit.Assert.*
import org.junit.Test
class BatchTest {
    private val gob = GameUnit("Гоблин", 1, 2)
    private val arch = GameUnit("Лучница", 1, 2)
    @Test fun applyBuyUpdatesState() {
        val s = GameState(5, listOf(gob, arch), listOf(null, null), listOf(null))
        val n = BuyPlanner.applyBuy(s, 0)
        assertEquals(3, n.elixir); assertNull(n.shop[0]); assertEquals("Гоблин", n.bench[0]!!.name)
    }
    @Test fun batchBuysSeveral() {
        var s = GameState(6, listOf(gob, arch, gob), listOf(null, null, null, null, null), listOf(null))
        val picks = mutableListOf<Int>()
        repeat(3) { val a = BuyPlanner.choose(s, StrategyConfig()) ?: return@repeat; picks += a.shopIndex; s = BuyPlanner.applyBuy(s, a.shopIndex) }
        assertEquals(3, picks.size); assertEquals(0, s.elixir)
    }
}
