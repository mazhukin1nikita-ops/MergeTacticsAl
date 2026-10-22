package com.mergetacticsai

import com.mergetacticsai.record.RecordNames
import com.mergetacticsai.record.RecordPlan
import org.junit.Assert.*
import org.junit.Test

class RecordPlanTest {
    @Test fun firstFrameIsSavedThenIdleGapApplies() {
        val p = RecordPlan(idleMs = 700, burstEveryMs = 250)
        assertTrue(p.shouldSave(1000))
        assertFalse(p.shouldSave(1500))
        assertTrue(p.shouldSave(1700))
    }

    @Test fun eventSwitchesToFrequentFramesThenBack() {
        val p = RecordPlan(700, 250)
        assertTrue(p.shouldSave(1000))
        p.onEvent(1100)
        assertTrue(p.shouldSave(1300))
        assertFalse(p.shouldSave(1400))
        assertTrue(p.shouldSave(1550))
        // после окна всплеска снова редко
        assertTrue(p.shouldSave(1100 + RecordPlan.BURST_MS + 10))
        assertFalse(p.shouldSave(1100 + RecordPlan.BURST_MS + 400))
    }

    @Test fun resetStartsOver() {
        val p = RecordPlan()
        assertTrue(p.shouldSave(1000))
        p.reset()
        assertTrue(p.shouldSave(1001))
    }

    @Test fun frameNamesRoundTrip() {
        val n = RecordNames.frame(42, "12-30-05-120")
        assertEquals("f_000042_12-30-05-120.jpg", n)
        assertEquals(42, RecordNames.indexOf(n))
        assertNull(RecordNames.indexOf("timeline.txt"))
    }

    @Test fun oldestFramesAreDropped() {
        val names = (1..10).map { RecordNames.frame(it, "t") } + "timeline.txt"
        val del = RecordNames.toDelete(names.shuffled(), 7)
        assertEquals((1..3).map { RecordNames.frame(it, "t") }.toSet(), del.toSet())
        assertTrue(RecordNames.toDelete(names, 20).isEmpty())
    }
}
