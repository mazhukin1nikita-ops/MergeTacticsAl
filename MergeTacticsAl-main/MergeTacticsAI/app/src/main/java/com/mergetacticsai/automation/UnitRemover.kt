package com.mergetacticsai.automation

import com.mergetacticsai.journal.JournalWriter
import com.mergetacticsai.sell.SellAttempt
import com.mergetacticsai.sell.SellGuard
import com.mergetacticsai.sell.SellMethod
import com.mergetacticsai.sell.SellRanker
import com.mergetacticsai.sell.SellStore
import com.mergetacticsai.sell.SellZone
import com.mergetacticsai.vision.Blob
import com.mergetacticsai.vision.ScreenRegions
import com.mergetacticsai.vision.SellButtonFinder

/** Что бот видит на экране в данный момент. buttons — зелёные кнопки в кадре, benchFree — свободные слоты скамейки. */
class Obs(
    val deploy: Boolean,
    val occupied: Boolean,
    val benchFree: List<Boolean>,
    val elixir: Int?,
    val buttons: List<Blob>,
)

/** Всё, что нужно удалителю от устройства. Реальная реализация — AndroidRemoverIO, в тестах — подделка. */
interface RemoverIO {
    suspend fun observe(cellIndex: Int): Obs?
    suspend fun tap(x: Float, y: Float): Boolean
    /** Непрерывный жест: касание → удержание → перетаскивание → пауза над целью → отпускание. */
    suspend fun holdDrag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, dragMs: Long, dwellMs: Long): Boolean
    suspend fun pause(ms: Long)
    fun size(): Pair<Int, Int>
    /** Центр клетки поля в долях экрана. */
    fun cellCenter(cellIndex: Int): Pair<Float, Float>
}

class RemoveRequest(
    val cellIndex: Int,
    val unitId: String,
    val unitName: String,
    /** Подтверждено, что воин не нужен сборке (решает вызывающий по сборке и плану). */
    val unneeded: Boolean,
    val planAllows: Boolean = true,
)

class RemoveResult(
    val success: Boolean,
    val method: SellMethod?,
    /** Если не null — продажа даже не начиналась: причина отказа. */
    val blocked: String?,
    val attempts: Int,
    /** true — остановили, потому что сменилась фаза или бота остановили. */
    val aborted: Boolean = false,
)

/**
 * Убирает воина с поля тремя способами по очереди (кнопка «Продать» → скамейка → зона продажи), после каждого
 * жеста проверяет результат по картинке, каждую попытку пишет в базу и со временем ставит вперёд тот способ,
 * который на этом экране и в этом состоянии чаще срабатывал. Каждый способ пробуется не больше одного раза за запрос.
 */
class UnitRemover(
    private val io: RemoverIO,
    private val store: SellStore?,
    private val journal: JournalWriter?,
    private val log: (String) -> Unit,
    private val alive: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Check(val ok: Boolean, val error: String, val text: String, val phaseLost: Boolean = false)

    suspend fun remove(req: RemoveRequest): RemoveResult {
        val first = io.observe(req.cellIndex) ?: return RemoveResult(false, null, "кадр не получен", 0)
        val why = SellGuard.check(first.deploy, first.occupied, req.unneeded, req.planAllows)
        if (why != null) return RemoveResult(false, null, why, 0)

        val (w, h) = io.size()
        val resolution = "${w}x$h"
        val benchKnownFree = first.benchFree.any { it }
        val state = if (benchKnownFree) "bench_free" else "bench_full"
        val allowed = SellMethod.values().filter { it != SellMethod.DRAG_TO_BENCH || benchKnownFree }.toSet()
        val order = SellRanker.order(store?.stats(resolution, state) ?: emptyMap(), allowed)
        val c = io.cellCenter(req.cellIndex)
        val sx = c.first * w; val sy = c.second * h
        var attempts = 0

        for (method in order) {
            if (!alive()) return RemoveResult(false, null, null, attempts, aborted = true)
            val before = io.observe(req.cellIndex)
            if (before == null || !before.deploy) return RemoveResult(false, null, "фаза изменилась", attempts, aborted = true)
            if (!before.occupied) return RemoveResult(true, null, null, attempts)

            if (method == SellMethod.DRAG_TO_BENCH && before.benchFree.none { it }) continue
            attempts++
            val t0 = clock()
            var tx = sx; var ty = sy; var gestureMs = 0L
            var res: Check
            val jid = journal?.log("SELL", "Убираю «${req.unitName}» с поля: ${method.title}",
                "Воин не нужен текущей сборке (клетка ${req.cellIndex + 1}).", "Состояние: $state\nЭкран: $resolution", "PLANNED") ?: -1L

            when (method) {
                SellMethod.SELL_BUTTON -> {
                    val tapped = io.tap(sx, sy)
                    io.pause(Timing.settleMs)
                    val win = if (tapped) io.observe(req.cellIndex) else null
                    val btn = if (win != null && win.deploy) SellButtonFinder.appeared(before.buttons, win.buttons) else null
                    if (btn == null) {
                        res = Check(false, if (!tapped) "gesture_failed" else "no_sell_button", "кнопка «Продать» после нажатия не появилась")
                    } else {
                        tx = btn.cx * w; ty = btn.cy * h; gestureMs = 50L
                        val ok = io.tap(tx, ty)
                        io.pause(Timing.settleMs)
                        res = if (!ok) Check(false, "gesture_failed", "нажатие на кнопку не выполнено")
                        else verify(req, before, io.observe(req.cellIndex), method)
                    }
                }
                SellMethod.DRAG_TO_BENCH -> {
                    val slot = before.benchFree.indexOfFirst { it }
                    tx = ScreenRegions.benchX[slot] * w; ty = ScreenRegions.BENCH_Y * h
                    gestureMs = Timing.holdMs + Timing.dragMs + Timing.dwellMs
                    val ok = io.holdDrag(sx, sy, tx, ty, Timing.holdMs, Timing.dragMs, Timing.dwellMs)
                    io.pause(Timing.settleMs)
                    res = if (!ok) Check(false, "gesture_failed", "жест не выполнен") else verify(req, before, io.observe(req.cellIndex), method)
                }
                SellMethod.DRAG_TO_SHOP -> {
                    val z = SellZone.center(w, h)
                    tx = z.first; ty = z.second
                    gestureMs = Timing.holdMs + Timing.dragMs + Timing.dwellMs
                    val ok = io.holdDrag(sx, sy, tx, ty, Timing.holdMs, Timing.dragMs, Timing.dwellMs)
                    io.pause(Timing.settleMs)
                    res = if (!ok) Check(false, "gesture_failed", "жест не выполнен") else verify(req, before, io.observe(req.cellIndex), method)
                }
            }

            store?.add(SellAttempt(
                unitId = req.unitId, unitPosition = req.cellIndex, selectedUnit = req.unitName, method = method,
                startX = sx, startY = sy, targetX = tx, targetY = ty, gestureMs = gestureMs, result = res.text,
                success = res.ok, errorType = res.error, resolution = resolution, gameState = state, at = t0,
            ))
            journal?.outcome(jid, res.ok, res.text)
            log("Продажа с поля, способ «${method.title}»: ${if (res.ok) "получилось" else "не получилось"} — ${res.text}")
            if (res.ok) { Timing.ok(); return RemoveResult(true, method, null, attempts) }
            if (!res.phaseLost) Timing.fail()
            if (res.phaseLost) return RemoveResult(false, null, "фаза изменилась", attempts, aborted = true)
            io.pause(Timing.BETWEEN_METHODS_MS)
        }
        return RemoveResult(false, null, null, attempts)
    }

    private fun verify(req: RemoveRequest, before: Obs, after: Obs?, method: SellMethod): Check {
        if (after == null) return Check(false, "no_frame", "кадр после жеста не получен")
        if (!after.deploy) return Check(false, "phase_changed", "фаза игры изменилась во время продажи", phaseLost = true)
        if (after.occupied) return Check(false, "still_on_field", "воин остался на поле")
        val parts = ArrayList<String>()
        parts.add("воин исчез с поля")
        val benchBefore = before.benchFree.count { !it }; val benchAfter = after.benchFree.count { !it }
        if (benchAfter > benchBefore) parts.add("появился на скамейке")
        else if (method == SellMethod.DRAG_TO_BENCH) parts.add("на скамейке не найден (возможно, продан)")
        val e0 = before.elixir; val e1 = after.elixir
        if (e0 != null && e1 != null) parts.add(if (e1 > e0) "эликсир +${e1 - e0}" else "эликсир без роста (ориентир, не критерий)")
        return Check(true, "", parts.joinToString(", "))
    }
}
