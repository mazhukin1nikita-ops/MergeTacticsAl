package com.mergetacticsai

import com.mergetacticsai.strategy.BuyQueue
import com.mergetacticsai.strategy.FillerPolicy
import com.mergetacticsai.strategy.MissTracker
import org.junit.Assert.*
import org.junit.Test

class FillerPolicyTest {
    private fun s(held: Int, limit: Int = 6, benchFree: Int = 4) = FillerPolicy.State(held, limit, benchFree)

    @Test fun buildCardAlwaysAllowed() = assertTrue(FillerPolicy.allows(false, true, false, 5, s(11, 6, 0)))
    @Test fun noBuildListMeansNoRestriction() = assertTrue(FillerPolicy.allows(true, false, false, 5, s(11, 6, 0)))
    @Test fun mergeArrowAllowed() = assertTrue(FillerPolicy.allows(false, false, true, 4, s(11, 6, 0)))

    @Test fun cheapFillerAllowedWhileFieldHasRoom() = assertTrue(FillerPolicy.allows(false, false, false, 2, s(1, 3, 4)))
    @Test fun expensiveFillerBlocked() = assertFalse(FillerPolicy.allows(false, false, false, 4, s(0, 6, 5)))
    @Test fun fillerBlockedWhenWeAlreadyHoldEnough() = assertFalse(FillerPolicy.allows(false, false, false, 2, s(6, 6, 4)))
    @Test fun fillerBlockedWhenBenchAlmostFull() = assertFalse(FillerPolicy.allows(false, false, false, 2, s(2, 6, 1)))
    @Test fun unknownCardWithoutCostNotBoughtBlindly() = assertFalse(FillerPolicy.allows(false, false, false, null, s(0, 6, 5)))

    @Test fun recordedSituationBlocksJunk() {
        // запись: поле 6/6, скамейка 5/5 — Варвар (2), Ведьма (3), Гоблин-машина (4) и «карточка 1» покупались зря
        for (cost in listOf(2, 3, 4, null)) assertFalse(FillerPolicy.allows(false, false, false, cost, s(11, 6, 0)))
    }

    // ---- очередь покупок
    @Test fun twoPurchasesInARowKeepTheirOwnIdentity() {
        val q = BuyQueue<String>(3500L)
        q.push("barbarian", 1000L); q.push("witch", 1277L) // запись: покупки с разницей 0,28 с
        assertEquals("barbarian", q.pop(1500L))              // первый занятый слот — первая покупка
        assertEquals("witch", q.pop(1600L))
        assertNull(q.pop(1700L))
    }

    @Test fun staleEntryIsNeverGivenToAnotherUnit() {
        val q = BuyQueue<String>(3500L)
        q.push("merge-that-took-no-slot", 0L)
        assertNull(q.pop(10_000L))
    }

    @Test fun clearEmptiesQueue() {
        val q = BuyQueue<Int>(); q.push(1, 0L); q.clear(); assertEquals(0, q.size)
    }

    // ---- забывание клетки
    @Test fun oneMissedFrameDoesNotEraseUnit() {
        val t = MissTracker(3, 1200L)
        assertTrue(t.update(listOf(5), listOf(false, false, false, false, false, false), 0L).isEmpty())
        assertTrue(t.update(listOf(5), List(24) { true }, 300L).isEmpty())          // снова виден — счёт сброшен
        assertTrue(t.update(listOf(5), List(24) { false }, 600L).isEmpty())
    }

    @Test fun cellEmptyLongEnoughIsDropped() {
        val t = MissTracker(3, 1200L)
        val empty = List(24) { false }
        assertTrue(t.update(listOf(5), empty, 0L).isEmpty())
        assertTrue(t.update(listOf(5), empty, 700L).isEmpty())
        assertEquals(setOf(5), t.update(listOf(5), empty, 1300L))
        assertTrue(t.update(listOf(5), empty, 1400L).isEmpty()) // запись стёрта, счёт начался заново
    }
}
