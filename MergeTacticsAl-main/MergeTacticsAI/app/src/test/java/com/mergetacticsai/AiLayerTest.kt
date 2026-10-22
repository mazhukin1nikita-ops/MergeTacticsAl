package com.mergetacticsai
import com.mergetacticsai.ai.StateParser
import com.mergetacticsai.automation.*
import com.mergetacticsai.vision.FrameSignature
import org.junit.Assert.*
import org.junit.Test

class AiLayerTest {
    private val raw = """{"phase":"deploy","elixir":4,"shop":[{"name":"Рыцарь","cost":3},{"name":"Гоблин","cost":2},{"name":"Лучница","cost":2}],"bench":[null,null,{"name":"Гоблин","level":1},null,null],"board":[]}"""

    @Test fun parsesObservation() {
        val o = StateParser.parse(raw).getOrThrow()
        assertEquals("deploy", o.phase)
        val s = o.state!!
        assertEquals(4, s.elixir); assertEquals(3, s.shop.size); assertEquals(5, s.bench.size)
        assertNull(s.bench[0]); assertEquals("Гоблин", s.bench[2]!!.name)
    }
    @Test fun missingElixirGivesNoState() =
        assertNull(StateParser.parse("""{"phase":"deploy","shop":[{"name":"A","cost":1}]}""").getOrThrow().state)
    @Test fun garbageFails() = assertTrue(StateParser.parse("не json").isFailure)

    @Test fun budgetMinInterval() {
        var t = 0L
        val b = CallBudget(2000, 100) { t }
        assertTrue(b.tryAcquire()); t += 500; assertFalse(b.tryAcquire()); t += 2000; assertTrue(b.tryAcquire())
    }
    @Test fun budgetHourlyLimit() {
        var t = 0L
        val b = CallBudget(0, 2) { t }
        assertTrue(b.tryAcquire()); assertTrue(b.tryAcquire()); assertFalse(b.tryAcquire())
        t += 3_700_000; assertTrue(b.tryAcquire())
    }
    @Test fun signatureDiff() {
        assertEquals(0f, FrameSignature.diff(intArrayOf(1, 2, 3), intArrayOf(1, 2, 3)), 0f)
        assertEquals(10f, FrameSignature.diff(intArrayOf(0, 0), intArrayOf(10, 10)), 0f)
        assertTrue(FrameSignature.diff(intArrayOf(1), intArrayOf(1, 2)) > 1000f)
    }
    @Test fun beginAnalysisFromIdle() {
        val c = AgentController { Permissions(true, true, true) }
        assertTrue(c.beginAnalysis()); assertEquals(AgentState.ANALYZING, c.state.value)
    }
    @Test fun beginAnalysisAfterStop() {
        val c = AgentController { Permissions(true, true, true) }
        c.forceStop(); assertTrue(c.beginAnalysis())
    }
}
