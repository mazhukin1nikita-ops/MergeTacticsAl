package com.mergetacticsai.automation

import android.graphics.Bitmap
import com.mergetacticsai.accessibility.GestureController
import com.mergetacticsai.ai.AIProvider
import com.mergetacticsai.ai.Observation
import com.mergetacticsai.ai.Point
import com.mergetacticsai.ai.Prompts
import com.mergetacticsai.ai.RateLimitException
import com.mergetacticsai.ai.StateParser
import com.mergetacticsai.capture.ScreenCaptureService
import com.mergetacticsai.strategy.BuyPlanner
import com.mergetacticsai.strategy.PlacementPlanner
import com.mergetacticsai.vision.FrameSignature
import com.mergetacticsai.vision.ScreenRegions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Цикл: кадр ~2 раза в секунду → локально смотрим, изменился ли магазин/эликсир →
 * если да, один запрос ИИ «опиши экран» → наши правила решают → проверка → касание → новый кадр.
 */
class AgentLoop(
    private val controller: AgentController,
    private val provider: () -> AIProvider?,
    private val newBudget: () -> CallBudget,
    private val perms: () -> Permissions,
    private val screen: () -> Pair<Int, Int>,
    private val log: (String) -> Unit,
    private val frame: () -> Bitmap? = { ScreenCaptureService.captureFrame() },
) {
    companion object { const val MAX_BATCH = 3 }

    private var job: Job? = null
    private val active = setOf(AgentState.ANALYZING, AgentState.WAITING_FOR_BATTLE)
    private val validator = ActionValidator()
    private var lastBuy: Pair<Int, Int>? = null
    private var sameBuy = 0
    private var lastOrb: Point? = null
    private var orbRepeat = 0

    fun stop() { job?.cancel(); job = null }

    fun start(scope: CoroutineScope) {
        stop()
        lastBuy = null; sameBuy = 0
        job = scope.launch(Dispatchers.Default) { loop() }
    }

    private suspend fun loop() {
        val budget = newBudget()
        var lastSig: IntArray? = null
        while (true) {
            delay(500)
            val st = controller.state.value
            if (st !in active) {
                if (st == AgentState.STOPPED || st == AgentState.ERROR) break
                continue
            }
            if (!perms().capture) {
                log("Захват экрана потерян — остановка")
                controller.transition(AgentState.ERROR); break
            }
            val bmp = frame() ?: continue
            val sig = FrameSignature.of(bmp, ScreenRegions.shop) + FrameSignature.of(bmp, ScreenRegions.elixir)
            val prev = lastSig
            if (prev != null && FrameSignature.diff(prev, sig) <= 12f) { bmp.recycle(); continue }
            val p = provider()
            if (p == null) {
                bmp.recycle(); log("Не задан API-ключ — пауза")
                controller.transition(AgentState.PAUSED); break
            }
            if (!budget.tryAcquire()) { bmp.recycle(); continue }
            lastSig = sig
            val jpeg = toJpeg(bmp); bmp.recycle()
            val raw = try {
                p.requestPlan(jpeg, Prompts.OBSERVE)
            } catch (e: CancellationException) { throw e
            } catch (e: RateLimitException) {
                val wait = e.retryAfterMs.coerceIn(10_000L, 120_000L)
                log("Лимит Google (429): пауза ${wait / 1000} с")
                delay(wait); lastSig = null; continue
            } catch (e: Exception) {
                log("Ошибка ИИ: ${e.message}"); delay(3000); lastSig = null; continue
            }
            val obs = StateParser.parse(raw).getOrNull()
            if (obs == null) { log("Ответ ИИ не разобран"); continue }
            if (act(obs)) lastSig = null
        }
    }

    /** Нажимает на сферу (не чаще двух раз по одному и тому же месту). */
    private suspend fun tapOrb(orbs: List<Point>): Boolean {
        val o = orbs.firstOrNull { it.y in 0.12f..0.84f } ?: return false
        val last = lastOrb
        if (last != null && abs(last.x - o.x) < 0.04f && abs(last.y - o.y) < 0.04f) orbRepeat++
        else { orbRepeat = 0; lastOrb = o }
        if (orbRepeat >= 2) return false
        val (w, h) = screen()
        val x = o.x * w; val y = o.y * h
        if (!validator.validateTap(x, y, w, h).ok) return false
        val ok = GestureController.tap(x, y)
        if (ok) log("Нажимаю на сферу")
        delay(500)
        return ok
    }

    /** true, если было касание и нужно заново посмотреть экран. */
    private suspend fun act(obs: Observation): Boolean {
        var acted = false
        if (perms().canControlGame && obs.orbs.isNotEmpty() && tapOrb(obs.orbs)) acted = true
        when (obs.phase) {
            "battle" -> { controller.transition(AgentState.WAITING_FOR_BATTLE); return acted }
            "deploy" -> controller.transition(AgentState.ANALYZING)
            else -> return acted
        }
        val s = obs.state ?: return acted
        if (!controller.transition(AgentState.PLANNING)) return acted
        val cfg = controller.config.value
        val moves = PlacementPlanner.plan(s, obs.boardUsed, obs.boardMax, obs.freeHexes, obs.boardPoints)
        if (moves.isEmpty() && BuyPlanner.choose(s, cfg) == null) {
            log("Делать нечего (эликсир ${s.elixir})"); controller.transition(AgentState.ANALYZING); return acted
        }
        if (!controller.transition(AgentState.EXECUTING)) {
            log("Нет разрешений на управление"); controller.transition(AgentState.ANALYZING); return acted
        }
        val (w, h) = screen()
        var sim = s

        // 1) Выставляем со скамейки на поле.
        for ((slot, p) in moves) {
            if (slot !in ScreenRegions.benchX.indices) break
            val x1 = ScreenRegions.benchX[slot] * w; val y1 = ScreenRegions.BENCH_Y * h
            val x2 = p.x * w; val y2 = p.y * h
            if (!validator.validateTap(x1, y1, w, h).ok || !validator.validateTap(x2, y2, w, h).ok) break
            val name = sim.bench.getOrNull(slot)?.name ?: "?"
            if (GestureController.drag(x1, y1, x2, y2)) { log("Выставляю на поле: $name"); acted = true }
            else { log("Не удалось перетащить юнита"); break }
            sim = sim.copy(bench = sim.bench.toMutableList().also { it[slot] = null })
            delay(900)
        }

        // 2) Покупаем (до MAX_BATCH карточек за один запрос).
        var bought = 0
        while (bought < MAX_BATCH) {
            val a = BuyPlanner.choose(sim, cfg) ?: break
            val key = a.shopIndex to sim.elixir
            if (key == lastBuy) sameBuy++ else { sameBuy = 0; lastBuy = key }
            if (sameBuy >= 2) {
                log("Тап по карточке не покупает юнита — возможно, нужно перетаскивание. Пауза.")
                controller.transition(AgentState.PAUSED); return false
            }
            val v = validator.validate(a, sim, perms())
            if (!v.ok) { log("Действие отклонено: ${v.reason}"); break }
            val x = ScreenRegions.cardX[a.shopIndex.coerceIn(0, 2)] * w
            val y = ScreenRegions.CARD_Y * h
            val name = sim.shop[a.shopIndex]?.name ?: "?"
            if (validator.validateTap(x, y, w, h).ok && GestureController.tap(x, y)) log("Покупаю: $name")
            else { log("Не удалось нажать на карточку"); break }
            sim = BuyPlanner.applyBuy(sim, a.shopIndex)
            bought++; acted = true
            delay(700)
        }
        delay(300)
        controller.transition(AgentState.ANALYZING)
        return acted
    }

    private fun toJpeg(b: Bitmap): ByteArray {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 70, o)
        return o.toByteArray()
    }
}
