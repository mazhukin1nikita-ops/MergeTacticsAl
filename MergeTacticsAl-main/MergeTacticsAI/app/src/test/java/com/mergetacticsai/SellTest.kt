package com.mergetacticsai

import com.mergetacticsai.automation.Obs
import com.mergetacticsai.automation.RemoveRequest
import com.mergetacticsai.automation.RemoverIO
import com.mergetacticsai.automation.UnitRemover
import com.mergetacticsai.sell.*
import com.mergetacticsai.strategy.HexGrid
import com.mergetacticsai.vision.Blob
import com.mergetacticsai.vision.SellButtonFinder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FakeIO(
    var deploy: Boolean = true,
    var occupied: Boolean = true,
    var benchFree: MutableList<Boolean> = mutableListOf(true, true, false, false, false),
    var windowOpens: Boolean = true,
    var buttonRemoves: Boolean = true,
    var benchDragWorks: Boolean = true,
    var shopDragWorks: Boolean = true,
    var phaseLostAfterGestures: Int = -1,
) : RemoverIO {
    class Drag(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val hold: Long, val drag: Long, val dwell: Long)

    val taps = ArrayList<Pair<Float, Float>>()
    val drags = ArrayList<Drag>()
    var button: Blob? = null
    private var gestures = 0
    val cell = 10

    private fun countGesture() { gestures++; if (phaseLostAfterGestures in 1..gestures) deploy = false }

    override suspend fun observe(cellIndex: Int): Obs = Obs(deploy, occupied, benchFree.toList(), 5, listOfNotNull(button))

    override suspend fun tap(x: Float, y: Float): Boolean {
        taps.add(Pair(x, y)); countGesture()
        val c = cellCenter(cell)
        val b = button
        if (b != null && x >= b.l * 1000 && x <= b.r * 1000 && y >= b.t * 2000 && y <= b.b * 2000) {
            if (buttonRemoves) occupied = false
            button = null
            return true
        }
        if (abs(x - c.first * 1000) < 40 && abs(y - c.second * 2000) < 40 && windowOpens) button = Blob(0.35f, 0.55f, 0.65f, 0.60f)
        return true
    }

    override suspend fun holdDrag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, dragMs: Long, dwellMs: Long): Boolean {
        drags.add(Drag(x1, y1, x2, y2, holdMs, dragMs, dwellMs)); countGesture()
        val c = cellCenter(cell)
        if (abs(x1 - c.first * 1000) > 40 || abs(y1 - c.second * 2000) > 40) return true
        if (y2 < 1700f) {
            if (benchDragWorks) { occupied = false; val i = benchFree.indexOfFirst { it }; if (i >= 0) benchFree[i] = false }
        } else if (shopDragWorks) occupied = false
        return true
    }

    override suspend fun pause(ms: Long) {}
    override fun size() = Pair(1000, 2000)
    override fun cellCenter(cellIndex: Int) = Pair(HexGrid.cells[cellIndex].x, HexGrid.cells[cellIndex].y)
}

class SellTest {
    private fun req(unneeded: Boolean = true) = RemoveRequest(cellIndex = 10, unitId = "knight", unitName = "Рыцарь", unneeded = unneeded)
    private fun run(io: FakeIO, store: SellStore? = MemorySellStore(), alive: () -> Boolean = { true }, r: RemoveRequest = req()) =
        runBlocking { UnitRemover(io, store, null, {}, alive).remove(r) }

    // ---- порядок способов
    @Test fun defaultOrderIsButtonBenchShop() =
        assertEquals(listOf(SellMethod.SELL_BUTTON, SellMethod.DRAG_TO_BENCH, SellMethod.DRAG_TO_SHOP), SellRanker.order(emptyMap()))

    @Test fun fewSamplesKeepDefaultOrder() =
        assertEquals(SellMethod.SELL_BUTTON, SellRanker.order(mapOf(SellMethod.SELL_BUTTON to Pair(0, 2))).first())

    @Test fun failingMethodDropsAfterEnoughSamples() {
        val o = SellRanker.order(mapOf(SellMethod.SELL_BUTTON to Pair(0, 5), SellMethod.DRAG_TO_SHOP to Pair(5, 5)))
        assertEquals(SellMethod.DRAG_TO_SHOP, o.first()); assertEquals(SellMethod.SELL_BUTTON, o.last())
    }
    @Test fun reliableFirstMethodStaysFirst() =
        assertEquals(SellMethod.SELL_BUTTON, SellRanker.order(mapOf(SellMethod.SELL_BUTTON to Pair(5, 5), SellMethod.DRAG_TO_SHOP to Pair(2, 5))).first())

    @Test fun disallowedMethodIsExcluded() {
        val o = SellRanker.order(emptyMap(), setOf(SellMethod.SELL_BUTTON, SellMethod.DRAG_TO_SHOP))
        assertEquals(listOf(SellMethod.SELL_BUTTON, SellMethod.DRAG_TO_SHOP), o)
    }

    // ---- защита
    @Test fun guardReasons() {
        assertNotNull(SellGuard.check(false, true, true, true))
        assertNotNull(SellGuard.check(true, false, true, true))
        assertNotNull(SellGuard.check(true, true, false, true))
        assertNotNull(SellGuard.check(true, true, true, false))
        assertNull(SellGuard.check(true, true, true, true))
    }

    // ---- хранилище
    @Test fun storeGroupsByResolutionAndState() {
        val s = MemorySellStore()
        fun a(m: SellMethod, ok: Boolean, res: String, st: String) = SellAttempt("u", 1, "u", m, 0f, 0f, 0f, 0f, 0, "", ok, "", res, st, 0)
        s.add(a(SellMethod.SELL_BUTTON, true, "1000x2000", "bench_free")); s.add(a(SellMethod.SELL_BUTTON, false, "1000x2000", "bench_free"))
        s.add(a(SellMethod.SELL_BUTTON, true, "500x1000", "bench_free"))
        assertEquals(Pair(1, 2), s.stats("1000x2000", "bench_free")[SellMethod.SELL_BUTTON])
        assertTrue(s.stats("1000x2000", "bench_full").isEmpty())
        assertEquals(Pair(2, 3), s.totals()[SellMethod.SELL_BUTTON])
    }

    // ---- поиск кнопки
    private fun image(w: Int, h: Int, l: Float, t: Float, r: Float, b: Float, color: Int): IntArray {
        val px = IntArray(w * h) { 0xFF3A3A3A.toInt() }
        for (y in (t * h).toInt() until (b * h).toInt()) for (x in (l * w).toInt() until (r * w).toInt()) px[y * w + x] = color
        return px
    }
    private val GREEN = 0xFF50E040.toInt()

    @Test fun findsGreenButton() {
        val f = SellButtonFinder.find(image(540, 1200, 0.3f, 0.55f, 0.7f, 0.60f, GREEN), 540, 1200)
        assertEquals(1, f.size); assertEquals(0.5f, f[0].cx, 0.02f); assertEquals(0.575f, f[0].cy, 0.02f)
    }
    @Test fun sameResultAtOtherResolution() {
        val a = SellButtonFinder.find(image(540, 1200, 0.3f, 0.55f, 0.7f, 0.60f, GREEN), 540, 1200)[0]
        val b = SellButtonFinder.find(image(1080, 2400, 0.3f, 0.55f, 0.7f, 0.60f, GREEN), 1080, 2400)[0]
        assertEquals(a.cx, b.cx, 0.02f); assertEquals(a.cy, b.cy, 0.02f)
    }
    @Test fun ignoresNonGreenAndWrongShapes() {
        assertTrue(SellButtonFinder.find(image(540, 1200, 0.3f, 0.55f, 0.7f, 0.60f, 0xFFE04040.toInt()), 540, 1200).isEmpty())
        assertTrue(SellButtonFinder.find(image(540, 1200, 0.3f, 0.55f, 0.35f, 0.58f, GREEN), 540, 1200).isEmpty())
        assertTrue(SellButtonFinder.find(image(540, 1200, 0.3f, 0.30f, 0.40f, 0.65f, GREEN), 540, 1200).isEmpty())
    }
    @Test fun appearedReportsOnlyNewButtons() {
        val old = Blob(0.1f, 0.1f, 0.4f, 0.15f); val fresh = Blob(0.35f, 0.55f, 0.65f, 0.60f)
        assertNull(SellButtonFinder.appeared(listOf(old), listOf(old)))
        assertSame(fresh, SellButtonFinder.appeared(listOf(old), listOf(old, fresh)))
        assertNull(SellButtonFinder.appeared(emptyList(), emptyList()))
    }

    // ---- удаление воина
    @Test fun buttonMethodWorks() {
        val io = FakeIO(); val store = MemorySellStore()
        val r = run(io, store)
        assertTrue(r.success); assertEquals(SellMethod.SELL_BUTTON, r.method); assertEquals(1, r.attempts)
        assertTrue(io.drags.isEmpty()); assertEquals(2, io.taps.size)
        val a = store.recent(5).single()
        assertTrue(a.success); assertEquals("1000x2000", a.resolution); assertEquals("bench_free", a.gameState)
        assertEquals("knight", a.unitId); assertEquals(10, a.unitPosition)
    }
    @Test fun fallsBackToBenchWhenNoSellWindow() {
        val io = FakeIO(windowOpens = false); val store = MemorySellStore()
        val r = run(io, store)
        assertTrue(r.success); assertEquals(SellMethod.DRAG_TO_BENCH, r.method); assertEquals(2, r.attempts)
        val att = store.recent(5).reversed()
        assertEquals(listOf(SellMethod.SELL_BUTTON, SellMethod.DRAG_TO_BENCH), att.map { it.method })
        assertEquals("no_sell_button", att[0].errorType); assertFalse(att[0].success)
    }
    @Test fun fallsBackToShopLast() {
        val io = FakeIO(windowOpens = false, benchDragWorks = false); val store = MemorySellStore()
        val r = run(io, store)
        assertTrue(r.success); assertEquals(SellMethod.DRAG_TO_SHOP, r.method); assertEquals(3, r.attempts)
        assertEquals("still_on_field", store.recent(5).reversed()[1].errorType)
    }
    @Test fun allMethodsFailIsReportedNotRepeated() {
        val io = FakeIO(windowOpens = false, benchDragWorks = false, shopDragWorks = false)
        val r = run(io)
        assertFalse(r.success); assertEquals(3, r.attempts); assertNull(r.blocked); assertEquals(2, io.drags.size)
    }
    @Test fun dragsAreOneContinuousGesture() {
        val io = FakeIO(windowOpens = false)
        run(io)
        val d = io.drags.single()
        assertTrue(d.hold > 0 && d.drag > 0 && d.dwell > 0)
    }
    @Test fun neededUnitIsNeverTouched() {
        val io = FakeIO(); val store = MemorySellStore()
        val r = run(io, store, r = req(unneeded = false))
        assertFalse(r.success); assertNotNull(r.blocked); assertTrue(io.taps.isEmpty() && io.drags.isEmpty()); assertEquals(0, store.count())
    }
    @Test fun noSaleDuringBattle() {
        val io = FakeIO(deploy = false)
        val r = run(io)
        assertNotNull(r.blocked); assertTrue(io.taps.isEmpty() && io.drags.isEmpty())
    }
    @Test fun unconfirmedUnitIsNotSold() {
        val io = FakeIO(occupied = false)
        val r = run(io)
        assertNotNull(r.blocked); assertTrue(io.taps.isEmpty() && io.drags.isEmpty())
    }
    @Test fun fullBenchSkipsBenchMethod() {
        val io = FakeIO(benchFree = MutableList(5) { false }, windowOpens = false); val store = MemorySellStore()
        val r = run(io, store)
        assertTrue(r.success); assertEquals(SellMethod.DRAG_TO_SHOP, r.method)
        assertEquals(listOf(SellMethod.SELL_BUTTON, SellMethod.DRAG_TO_SHOP), store.recent(5).reversed().map { it.method })
        assertEquals("bench_full", store.recent(1).single().gameState)
    }
    @Test fun stoppedBotMakesNoGestures() {
        val io = FakeIO()
        val r = run(io, alive = { false })
        assertTrue(r.aborted); assertTrue(io.taps.isEmpty() && io.drags.isEmpty())
    }
    @Test fun roundTransitionStopsTheLadder() {
        val io = FakeIO(windowOpens = false, phaseLostAfterGestures = 1)
        val r = run(io)
        assertTrue(r.aborted); assertFalse(r.success); assertTrue(io.drags.isEmpty())
    }
    @Test fun learnedOrderIsUsedOnNextRequest() {
        val store = MemorySellStore()
        fun a(m: SellMethod, ok: Boolean) = SellAttempt("u", 1, "u", m, 0f, 0f, 0f, 0f, 0, "", ok, "", "1000x2000", "bench_free", 0)
        repeat(5) { store.add(a(SellMethod.SELL_BUTTON, false)); store.add(a(SellMethod.DRAG_TO_SHOP, true)) }
        val io = FakeIO()
        val r = run(io, store)
        assertEquals(SellMethod.DRAG_TO_SHOP, r.method); assertEquals(1, r.attempts); assertTrue(io.taps.isEmpty())
    }
    @Test fun sellZoneScalesWithResolution() {
        val a = SellZone.center(1000, 2000); val b = SellZone.center(500, 1000)
        assertEquals(a.first / 1000f, b.first / 500f, 0.0001f); assertEquals(a.second / 2000f, b.second / 1000f, 0.0001f)
    }
}
