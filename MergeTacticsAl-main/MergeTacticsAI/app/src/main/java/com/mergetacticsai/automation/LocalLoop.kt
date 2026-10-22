package com.mergetacticsai.automation

import android.graphics.Bitmap
import com.mergetacticsai.accessibility.GestureController
import com.mergetacticsai.ai.Point
import com.mergetacticsai.capture.ScreenCaptureService
import com.mergetacticsai.strategy.CardMemory
import com.mergetacticsai.strategy.AdaptivePlanner
import com.mergetacticsai.strategy.Env
import com.mergetacticsai.strategy.Kind
import com.mergetacticsai.strategy.Line
import com.mergetacticsai.strategy.ContextEvaluator
import com.mergetacticsai.strategy.MatchupInput
import com.mergetacticsai.strategy.UpgradeContext
import com.mergetacticsai.strategy.UpgradeRules
import com.mergetacticsai.odds.OddsParams
import com.mergetacticsai.adapt.Seen
import com.mergetacticsai.journal.JournalWriter
import com.mergetacticsai.odds.tiersOf
import com.mergetacticsai.strategy.BenchUnit
import com.mergetacticsai.strategy.Build
import com.mergetacticsai.strategy.CycleStrategy
import com.mergetacticsai.strategy.ShopCard
import com.mergetacticsai.strategy.CostCache
import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.PlacedUnit
import com.mergetacticsai.strategy.HexGrid
import com.mergetacticsai.strategy.PositionContext
import com.mergetacticsai.strategy.PositionEngine
import com.mergetacticsai.strategy.toHex
import com.mergetacticsai.strategy.PlaceGate
import com.mergetacticsai.strategy.SwapPlanner
import com.mergetacticsai.strategy.FieldFixer
import com.mergetacticsai.map.HexMap
import com.mergetacticsai.strategy.LoopKnowledge
import com.mergetacticsai.strategy.TraitEngine
import com.mergetacticsai.vision.OcrReader
import com.mergetacticsai.vision.PixelOps
import com.mergetacticsai.vision.TraitIcons
import com.mergetacticsai.vision.ScreenRegions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Автономный режим без API. Стратегия «круга»: дешёвые с парой → слияние до 2★ → продажа 2★ вне сборки.
 * Решения принимаются по картинке:
 * доступность карточки = не затемнена; объединение = зелёная стрелка над карточкой;
 * результат покупки = картинка карточки изменилась; результат выставления/продажи = слот скамейки опустел.
 */
class LocalLoop(
    private val controller: AgentController,
    private val perms: () -> Permissions,
    private val screen: () -> Pair<Int, Int>,
    private val log: (String) -> Unit,
    private val frame: () -> Bitmap? = { ScreenCaptureService.captureFrame() },
    private val ocr: OcrReader = OcrReader(),
    private val knowledge: () -> LoopKnowledge = { LoopKnowledge.EMPTY },
    private val onUnknownCard: (Long, Bitmap) -> Unit = { _, b -> b.recycle() },
    private val position: () -> PositionContext = { PositionContext() },
    /** Настройки с экрана «Вероятности»: доход, число раундов, места в магазине — по ним бот оценивает сценарии. */
    private val oddsParams: () -> OddsParams = { OddsParams() },
    /** Поправки покупки от анализа соперников (пусто — не влияет). */
    private val adaptBias: () -> Map<String, Double> = { emptyMap() },
    /** Вызывается в начале каждого раунда. */
    private val onRound: (Int) -> Unit = {},
    /** Журнал решений: что бот сделал, почему и чем кончилось. */
    private val journal: JournalWriter? = null,
) {
    private class Held(val sig: Long?, val cost: Int?, val trait: String?, val troopId: String?)
    private class PendingBuy(val index: Int, val sig: Long, val cost: Int?, val trait: String?, val troopId: String?, val at: Long, val jid: Long = -1L)
    private class PendingPlace(val slot: Int, val cell: Int, val at: Long, val unit: PlacedUnit? = null)
    private class PendingSell(val slot: Int, val at: Long, val held: Held?, val jid: Long = -1L)
    /** Продажа лишнего войска прямо с поля (тянем клетку на зону продажи), чтобы освободить место для бойца сборки. */
    private class PendingBoardSell(val cell: Int, val at: Long, val unit: PlacedUnit)

    /** Лишнее войско уходит с поля на свободный слот скамейки (когда она не полная). */
    private class PendingBoardMove(val cell: Int, val slot: Int, val at: Long, val unit: PlacedUnit)
    /** Боец сборки стоит не на своей клетке по карте — переставляем. */
    private class PendingRelocate(val from: Int, val to: Int, val at: Long, val unit: PlacedUnit)

    /** Краткий статус распознавания для главного экрана. */
    data class Info(val inMatch: Boolean = false, val deploy: Boolean = false, val bench: Int = 0, val bought: Int = 0)
    private val _info = MutableStateFlow(Info())
    val info: StateFlow<Info> = _info

    private var job: Job? = null
    private val active = setOf(AgentState.ANALYZING, AgentState.WAITING_FOR_BATTLE)
    private val memory = CardMemory()
    private val costs = CostCache()
    private val costTried = mutableSetOf<Long>()
    private var pendingBuy: PendingBuy? = null
    private var pendingPlace: PendingPlace? = null
    private var pendingSell: PendingSell? = null
    private var pendingBoardSell: PendingBoardSell? = null
    private var pendingBoardMove: PendingBoardMove? = null
    private var pendingRelocate: PendingRelocate? = null
    private var relocFails = 0
    private var lastRelocAt = 0L
    private var tacticalMoveRound = -1
    private val relocGiveUp = mutableSetOf<Pair<Int, Int>>()
    private var swapFails = 0
    private var lastSwapAt = 0L
    private var noVictimLogAt = 0L
    /** Поле, скорее всего, полное: выставление не удалось, а счётчик «N/M» прочитать не получилось. */
    private var boardFullHint = false
    /** Сразу после продажи с поля счётчик на экране ещё показывает старое значение — до этого момента его игнорируем. */
    private var ignoreFullUntil = 0L
    private val badCells = mutableSetOf<Int>()
    /** Кого бот сам выставил на поле: индекс клетки в [HexGrid.cells] → войско. Нужен позиционному ИИ. */
    private val board = HashMap<Int, PlacedUnit>()
    private val placeGate = PlaceGate()
    private val pairSince = HashMap<Pair<Int, Int>, Long>()
    private val mergeTries = HashMap<Pair<Int, Int>, Int>()
    private val matchCache = HashMap<Long, String?>()
    private var buyFails = 0
    private var sellFails = 0
    private var counter: Pair<Int, Int>? = null
    private var counterAt = 0L
    private var lastOrb: Point? = null
    private var orbRepeat = 0
    private var lastDebug = 0L
    private var wasDeploy = false
    private var prevBench = BooleanArray(5)
    private val held = arrayOfNulls<Held>(5)
    private val ownedSigs = mutableListOf<Pair<Long, String>>()
    private var autoBuild = Build(emptySet())
    private var recentBuy: PendingBuy? = null
    private val owned = mutableSetOf<String>()
    private val loggedUnknown = mutableSetOf<Long>()

    // ---- адаптивная стратегия: прокачка, отказ от маловероятного, альтернатива, запас эликсира, смена сборки
    /** Номер текущего раунда матча: растёт каждый раз, когда начинается расстановка. */
    private var roundNo = 0
    /** Номер раунда для других частей приложения. */
    val round: Int get() = roundNo
    /** Сколько копий каждого войска у нас сейчас (скамейка и поле, без 3★) и что стоит на поле. Нужны анализу соперников. */
    @Volatile var ownCopies: Map<String, Int> = emptyMap(); private set
    @Volatile var ownBoard: List<Seen> = emptyList(); private set
    /** Войска, дособирать которые бот бросил (накапливается за матч, чтобы не покупать и не продавать по кругу). */
    private val abandoned = mutableSetOf<String>()
    private var elixirCache: Pair<Int, Long>? = null
    private var lastPivotAt = 0L
    private var lastPlan = ""
    private var lastPlanAt = 0L
    private var lastEvalAt = 0L

    fun stop() { job?.cancel(); job = null }

    fun start(scope: CoroutineScope) {
        stop()
        memory.clear(); pendingBuy = null; pendingPlace = null; pendingSell = null
        pendingBoardSell = null; swapFails = 0; lastSwapAt = 0L; noVictimLogAt = 0L; boardFullHint = false; ignoreFullUntil = 0L
        tacticalMoveRound = -1
        pendingBoardMove = null; pendingRelocate = null; relocFails = 0; lastRelocAt = 0L; relocGiveUp.clear()
        badCells.clear(); board.clear(); placeGate.reset(); pairSince.clear(); mergeTries.clear(); matchCache.clear()
        buyFails = 0; sellFails = 0; counter = null
        lastOrb = null; orbRepeat = 0; wasDeploy = false
        prevBench = BooleanArray(5); held.fill(null); recentBuy = null
        ownedSigs.clear(); autoBuild = Build(emptySet())
        owned.clear(); loggedUnknown.clear()
        roundNo = 0; abandoned.clear(); elixirCache = null; lastPivotAt = 0L; lastPlan = ""; lastPlanAt = 0L; lastEvalAt = 0L
        _info.value = Info()
        job = scope.launch(Dispatchers.Default) { loop() }
    }

    private suspend fun loop() {
        while (true) {
            delay(80)
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
            try {
                step(bmp)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                log("Ошибка шага: ${e.message}"); delay(1000)
            } finally {
                bmp.recycle()
            }
        }
    }

    private inline fun act(block: () -> Boolean): Boolean {
        if (!controller.transition(AgentState.PLANNING)) return false
        if (!controller.transition(AgentState.EXECUTING)) { controller.transition(AgentState.ANALYZING); return false }
        val ok = block()
        controller.transition(AgentState.ANALYZING)
        return ok
    }

    /** Сферы любого цвета, в расстановке и в бою. */
    private suspend fun handleOrb(bmp: Bitmap): Boolean {
        val o = PixelOps.findOrb(bmp)
        if (o == null) { lastOrb = null; orbRepeat = 0; return false }
        if (!perms().canControlGame) return false
        val last = lastOrb
        if (last != null && abs(last.x - o.x) < 0.05f && abs(last.y - o.y) < 0.05f) orbRepeat++ else orbRepeat = 0
        lastOrb = o
        if (orbRepeat >= 3) return false
        val (w, h) = screen()
        val ok = GestureController.tap(o.x * w, o.y * h)
        if (ok) log("Нажимаю на сферу")
        delay(300)
        return ok
    }

    /** Следим, какая карточка попала в какой слот скамейки (нужно, чтобы знать свойство и цену юнита). */
    private fun trackBench(bench: BooleanArray, now: Long) {
        for (i in 0 until 5) {
            if (!bench[i]) held[i] = null
            else if (!prevBench[i]) {
                val rb = recentBuy
                held[i] = if (rb != null && now - rb.at < 4000) Held(rb.sig, rb.cost, rb.trait, rb.troopId) else null
            }
        }
        prevBench = bench.copyOf()
        _info.value = _info.value.copy(bench = bench.count { it }, bought = ownedSigs.size)
    }

    private var planDetails = ""
    private var planReason = ""

    private fun keyName(key: String, k: LoopKnowledge): String = k.troops[key]?.name ?: "войско без названия"

    private fun decisionDetails(s: List<com.mergetacticsai.strategy.Scenario>, bias: Map<String, Double>, env: Env): String =
        buildString {
            appendLine("Эликсир: ${env.elixir ?: "не прочитан"}, доход: ${env.income}, раундов осталось: ${env.roundsLeft}")
            s.sortedByDescending { it.value }.forEach { appendLine("Сценарий: ${it.label} — ${AdaptivePlanner.fmt(it.value)}") }
            if (bias.isNotEmpty()) appendLine("Поправки по соперникам действуют на ${bias.size} карт(ы)")
            append("Шансы выпадения и сила звёзд — допущения, не проверенные по игре")
        }

    private fun close(a: Long, b: Long) = java.lang.Long.bitCount(a xor b) <= CardMemory.MAX_DIST

    /** Сколько разных войск каждого свойства куплено (дубликаты — это слияния и считаются один раз). */
    private fun ownedTraitCounts(): Map<String, Int> = ownedSigs.map { it.second }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()

    private fun traitName(id: String, k: LoopKnowledge) = k.traits.firstOrNull { it.id == id }?.name ?: id

    /** После подтверждённой покупки: учитываем войско и показываем прогресс синергии. */
    private fun onBought(p: PendingBuy) {
        val k = knowledge()
        if (ownedSigs.none { close(it.first, p.sig) }) ownedSigs += p.sig to (p.trait ?: "")
        p.troopId?.let { owned += it }
        // Цена — это эликсир (капля на карточке), свойство — значок на карточке, а число войск свойства — отдельная величина.
        val name = p.troopId?.let { k.troops[it]?.name } ?: "карточка ${p.index + 1}"
        val price = p.cost?.let { "цена $it эликсира" } ?: "цена не прочитана"
        val trait = p.trait?.takeIf { it.isNotEmpty() }
        if (trait == null) { log("Куплено: «$name», $price"); return }
        val c = ownedTraitCounts()[trait] ?: 0
        val tiers = k.traits.firstOrNull { it.id == trait }?.tiers ?: emptyList()
        val next = tiers.firstOrNull { it > c }
        log("Куплено: «$name», $price, свойство «${traitName(trait, k)}». " +
            "Разных войск этого свойства в команде: $c" + (next?.let { ", до порога $it нужно ещё ${it - c}" } ?: ", порог максимальный"))
    }

    /** Узнаём войско по эталонам из фото каталога; результат запоминаем по отпечатку карточки. */
    private fun matchTroop(k: LoopKnowledge, sig: Long, bmp: Bitmap, index: Int): com.mergetacticsai.data.Troop? {
        val m = k.matcher ?: return null
        val cached = matchCache.entries.firstOrNull { close(it.key, sig) }
        val id = if (cached != null) cached.value else m.match(bmp, index).also { matchCache[sig] = it }
        return id?.let { k.troops[it] }
    }

    private fun currentBuild(k: LoopKnowledge): Build {
        val sel = k.policy.traits
        if (k.placeSel.isNotEmpty()) return Build(sel, k.placeSel)
        if (sel.isNotEmpty()) return Build(sel)
        if (!autoBuild.found) {
            val found = CycleStrategy.detectBuild(emptySet(), ownedTraitCounts())
            if (found.found) {
                autoBuild = found
                log("Сборка найдена: " + found.traits.joinToString { traitName(it, k) })
            }
        }
        return autoBuild
    }

    /** Идентификатор войска для поля: настоящий id, а если карточка не узнана — по свойству со значка ("trait:clan"). */
    private fun idOf(troopId: String?, trait: String?): String? =
        troopId ?: trait?.takeIf { it.isNotEmpty() }?.let { FieldFixer.pseudoId(it) }

    private fun unitName(k: LoopKnowledge, u: PlacedUnit): String =
        k.troops[u.troopId]?.name ?: if (FieldFixer.isPseudo(u.troopId)) "войско (${traitName(u.troopId.removePrefix(FieldFixer.TRAIT_PREFIX), k)})" else u.troopId

    private fun heldFor(u: PlacedUnit, k: LoopKnowledge): Held =
        if (FieldFixer.isPseudo(u.troopId)) Held(null, null, u.troopId.removePrefix(FieldFixer.TRAIT_PREFIX), null)
        else Held(null, k.troops[u.troopId]?.cost, k.troops[u.troopId]?.trait?.takeIf { it.isNotEmpty() }, u.troopId)

    /** Эликсир с экрана: читаем не чаще раза в 1,2 с; после покупки значение уменьшаем на цену. */
    private suspend fun elixirNow(bmp: Bitmap, now: Long): Int? {
        val c = elixirCache
        if (c != null && now - c.second < 1200) return c.first
        val e = ocr.readElixir(bmp)
        elixirCache = if (e != null) e to now else null
        return e
    }

    /** Ключ войска для подсчёта копий: настоящий id, а если карточка не узнана — по отпечатку. */
    private fun repKey(sig: Long): String = "sig:" + (ownedSigs.firstOrNull { close(it.first, sig) }?.first ?: sig)

    private fun benchKey(i: Int): String? {
        val h = held[i] ?: return null
        return h.troopId ?: h.sig?.let { repKey(it) }
    }

    private class Acc(val key: String, val troopId: String?, val trait: String?, val cost: Int?) {
        var ones = 0
        var twos = 0
    }

    /** Линии прокачки: сколько копий каждого войска у нас на скамейке и на поле. */
    private fun buildLines(k: LoopKnowledge, bench: BooleanArray, twoStar: BooleanArray): List<Line> {
        val acc = LinkedHashMap<String, Acc>()
        for (i in 0 until 5) {
            if (!bench[i]) continue
            val key = benchKey(i) ?: continue
            val h = held[i]
            val a = acc.getOrPut(key) { Acc(key, h?.troopId, h?.trait, h?.cost) }
            if (twoStar[i]) a.twos++ else a.ones++
        }
        for (u in board.values) {
            if (FieldFixer.isPseudo(u.troopId) || u.stars >= 3) continue
            val t = k.troops[u.troopId]
            val a = acc.getOrPut(u.troopId) { Acc(u.troopId, u.troopId, t?.trait?.takeIf { it.isNotEmpty() }, t?.cost) }
            if (u.stars >= 2) a.twos++ else a.ones++
        }
        return acc.values.map { Line(it.key, it.troopId, it.trait, it.cost, it.ones, it.twos) }
    }

    /**
     * Освобождает место на поле для [bu] из сборки: лишнего (не из сборки) убирает с поля —
     * на свободный слот скамейки, а если скамейка полна, продаёт. Результат проверяется на следующих кадрах.
     */
    private fun trySwap(k: LoopKnowledge, build: Build, bu: BenchUnit, bench: BooleanArray, bmp: Bitmap, w: Int, h: Int, now: Long): Boolean {
        val tid = idOf(bu.troopId, bu.trait) ?: return false
        if (swapFails >= 3 || now - lastSwapAt < 2500) return false
        val occupied = HexGrid.cells.map { PixelOps.cellOccupied(bmp, it) }
        board.keys.retainAll { it in occupied.indices && occupied[it] }
        val plan = position().plan
        val troopTrait: (String) -> String? = { k.troops[it]?.trait?.takeIf { t -> t.isNotEmpty() } }
        val traitOf: (String) -> String? = { FieldFixer.traitOf(it, troopTrait) }
        val costOf: (String) -> Int? = { k.troops[it]?.cost }
        val incoming = PlacedUnit(tid, if (bu.twoStar) 2 else 1)

        // Сначала те, кто заведомо не нужен сборке; если таких нет — слабее входящего по важности (прежнее правило).
        val victim: FieldFixer.Victim? = FieldFixer.pickUnneeded(board, occupied, build, plan, troopTrait, costOf, badCells)
            ?: SwapPlanner.pickVictim(
                board.filterValues { !FieldFixer.isPseudo(it.troopId) || FieldFixer.isUnneeded(it, build, plan, troopTrait) },
                occupied, incoming, build, plan, traitOf, costOf, badCells,
            )?.let { FieldFixer.Victim(it.cellIndex, it.unit) }

        val name = unitName(k, incoming)
        if (victim == null) {
            if (now - noVictimLogAt > 15000) {
                noVictimLogAt = now
                log("Поле заполнено, «$name» из сборки ждёт на скамейке, но убирать некого: нет выставленных ботом войск, которые не нужны сборке")
            }
            return false
        }
        val cell = HexGrid.cells[victim.cellIndex]
        val vName = unitName(k, victim.unit)
        val where = "ряд ${cell.row - HexGrid.FIRST_ROW + 1}, клетка ${cell.col + 1}"
        lastSwapAt = now
        val freeSlot = bench.indexOfFirst { !it }
        if (freeSlot >= 0) {
            val ok = act { GestureController.drag(cell.x * w, cell.y * h, ScreenRegions.benchX[freeSlot] * w, ScreenRegions.BENCH_Y * h, 450) }
            if (ok) {
                log("Поле заполнено — переношу лишнего «$vName» ($where) на скамейку (слот ${freeSlot + 1}), чтобы поставить «$name» из сборки")
                pendingBoardMove = PendingBoardMove(victim.cellIndex, freeSlot, now, victim.unit)
            }
            return ok
        }
        val ok = act { GestureController.drag(cell.x * w, cell.y * h, ScreenRegions.SELL_X * w, ScreenRegions.SELL_Y * h, 450) }
        if (ok) {
            log("Поле и скамейка заполнены — продаю лишнего «$vName» ($where), чтобы поставить «$name» из сборки")
            pendingBoardSell = PendingBoardSell(victim.cellIndex, now, victim.unit)
        }
        return ok
    }

    private suspend fun step(bmp: Bitmap) {
        val inMatch = (0 until 5).count { PixelOps.woodFraction(bmp, it) >= 0.15f } >= 4
        if (_info.value.inMatch != inMatch) _info.value = _info.value.copy(inMatch = inMatch)
        if (!inMatch) {
            if (roundNo != 0) { roundNo = 0; abandoned.clear(); elixirCache = null }
            return
        }
        if (handleOrb(bmp)) return

        val deploy = PixelOps.deployIconFraction(bmp) >= 0.2f
        if (_info.value.deploy != deploy) _info.value = _info.value.copy(deploy = deploy)
        if (!deploy) {
            controller.transition(AgentState.WAITING_FOR_BATTLE)
            if (wasDeploy) {
                wasDeploy = false
                badCells.clear(); placeGate.reset(); pairSince.clear(); mergeTries.clear()
                pendingBuy = null; pendingPlace = null; pendingSell = null
                pendingBoardSell = null; swapFails = 0; boardFullHint = false; ignoreFullUntil = 0L
                pendingBoardMove = null; pendingRelocate = null; relocFails = 0; relocGiveUp.clear()
                sellFails = 0; counter = null
            }
            return
        }
        controller.transition(AgentState.ANALYZING)
        if (!wasDeploy) { roundNo++; journal?.setRound(roundNo); runCatching { onRound(roundNo) } }
        wasDeploy = true
        if (!perms().canControlGame) return

        val now = System.currentTimeMillis()
        val bench = BooleanArray(5) { PixelOps.woodFraction(bmp, it) < 0.9f }
        trackBench(bench, now)
        val sigs = LongArray(3) { PixelOps.cardSignature(bmp, it) }
        val afford = BooleanArray(3) { PixelOps.cardAffordable(bmp, it) }
        val arrow = BooleanArray(3) { PixelOps.cardMergeArrow(bmp, it) }

        if (now - lastDebug > 5000) {
            lastDebug = now
            val e = ocr.readElixir(bmp)
            log("Вижу: эликсир=${e ?: "?"}, доступно=${afford.joinToString("") { if (it) "✓" else "✗" }}, стрелки=${arrow.joinToString("") { if (it) "↑" else "·" }}, скамейка=${bench.count { it }}/5, поле=${counter?.let { "${it.first}/${it.second}" } ?: "?"}")
        }

        // Проверка покупки: картинка карточки должна измениться.
        pendingBuy?.let { p ->
            if (java.lang.Long.bitCount(p.sig xor sigs[p.index]) > CardMemory.MAX_DIST) {
                memory.recordBuy(p.sig); onBought(p); recentBuy = PendingBuy(p.index, p.sig, p.cost, p.trait, p.troopId, System.currentTimeMillis())
                journal?.outcome(p.jid, true, "картинка карточки изменилась — покупка прошла")
                buyFails = 0; pendingBuy = null
                p.cost?.let { cst -> elixirCache?.let { (e, t) -> elixirCache = (e - cst).coerceAtLeast(0) to t } }
            } else if (now - p.at > 600) {
                journal?.outcome(p.jid, false, "картинка карточки не изменилась за 0,6 с — покупка не подтверждена")
                buyFails++; pendingBuy = null
            } else return
        }
        if (buyFails >= 3) {
            log("Тап по карточке не покупает юнита — возможно, нужно перетаскивание. Пауза.")
            buyFails = 0; controller.transition(AgentState.PAUSED); return
        }
        // Проверка выставления: слот скамейки должен опустеть. Неудача — пауза 20 секунд.
        pendingPlace?.let { p ->
            if (!bench[p.slot]) { placeGate.onSuccess(); p.unit?.let { board[p.cell] = it }; pendingPlace = null }
            else if (now - p.at > 700) {
                badCells += p.cell; pendingPlace = null; placeGate.onFail(); boardFullHint = true
                log("Не получилось поставить на поле — следующая попытка через 20 с")
            } else return
        }
        // Проверка продажи.
        pendingSell?.let { p ->
            if (!bench[p.slot]) {
                journal?.outcome(p.jid, true, "слот скамейки опустел — продано")
                sellFails = 0; pendingSell = null; elixirCache = null; log("Продан лишний юнит")
                val hs = p.held?.sig
                if (hs != null && held.none { it?.sig != null && close(it.sig, hs) }) ownedSigs.removeAll { close(it.first, hs) }
            }
            else if (now - p.at > 900) { journal?.outcome(p.jid, false, "слот не опустел за 0,9 с — продажа не подтверждена"); sellFails++; pendingSell = null }
            else return
        }
        // Проверка продажи с поля: полоска здоровья над клеткой должна исчезнуть.
        pendingBoardSell?.let { p ->
            if (!PixelOps.cellOccupied(bmp, HexGrid.cells[p.cell])) {
                board.remove(p.cell); pendingBoardSell = null; swapFails = 0; boardFullHint = false
                placeGate.reset(); counter = null; counterAt = 0L; ignoreFullUntil = now + 2500; lastSwapAt = now
                log("Убрал с поля «${knowledge().troops[p.unit.troopId]?.name ?: p.unit.troopId}» — место свободно")
            } else if (now - p.at > 1500) {
                swapFails++; pendingBoardSell = null
                log("Не удалось убрать войско с поля (попытка $swapFails из 3)")
            } else return
        }

        // Проверка переноса лишнего с поля на скамейку: клетка пуста, слот скамейки занят.
        pendingBoardMove?.let { p ->
            val moved = !PixelOps.cellOccupied(bmp, HexGrid.cells[p.cell]) && bench[p.slot]
            if (moved) {
                board.remove(p.cell); pendingBoardMove = null; swapFails = 0; boardFullHint = false
                placeGate.reset(); counter = null; counterAt = 0L; ignoreFullUntil = now + 2500; lastSwapAt = now
                held[p.slot] = heldFor(p.unit, knowledge())
                log("Убрал «${unitName(knowledge(), p.unit)}» с поля на скамейку — место свободно")
            } else if (now - p.at > 1500) {
                swapFails++; pendingBoardMove = null
                log("Не удалось перенести войско с поля на скамейку (попытка $swapFails из 3)")
            } else return
        }
        // Проверка перестановки по карте: старая клетка пуста, новая занята.
        pendingRelocate?.let { p ->
            val gone = !PixelOps.cellOccupied(bmp, HexGrid.cells[p.from])
            val there = PixelOps.cellOccupied(bmp, HexGrid.cells[p.to])
            if (gone && there) {
                board.remove(p.from); board[p.to] = p.unit; pendingRelocate = null; relocFails = 0
                log("Войско теперь на своей клетке по карте (${HexMap.label(HexGrid.cells[p.to].toHex())})")
            } else if (now - p.at > 1200) {
                relocGiveUp += p.from to p.to; relocFails++; pendingRelocate = null
                log("Не удалось переставить войско на клетку по карте (попытка $relocFails из 3)")
            } else return
        }

        val (w, h) = screen()
        val k = knowledge()
        val build = currentBuild(k)
        val twoStar = BooleanArray(5) { PixelOps.benchTwoStar(bmp, it) }
        val benchUnits: List<BenchUnit?> = (0 until 5).map { i ->
            if (!bench[i]) null else BenchUnit(i, held[i]?.sig, held[i]?.cost, held[i]?.trait, held[i]?.troopId, twoStar[i])
        }

        // Карточки магазина: цена, свойство (из «Обучения» или по значку), допуск по выбору пользователя.
        val cards = (0..2).map { i ->
            val troop = k.known.lookup(sigs[i])?.let { k.troops[it] } ?: matchTroop(k, sigs[i], bmp, i)
            if (troop == null && sigs[i] !in loggedUnknown) {
                loggedUnknown += sigs[i]
                onUnknownCard(sigs[i], PixelOps.cardCrop(bmp, i))
            }
            val trait = troop?.trait?.takeIf { it.isNotEmpty() } ?: TraitIcons.classify(bmp, i)
            var cost = troop?.cost ?: costs.get(sigs[i])
            if (cost == null && afford[i] && sigs[i] !in costTried) {
                costTried += sigs[i]
                cost = ocr.readCost(bmp, i)?.also { costs.put(sigs[i], it) }
            }
            val pol = k.policy
            val allowed = when {
                !pol.strict || arrow[i] -> true
                pol.traits.isNotEmpty() && trait != null -> trait in pol.traits && (troop == null || troop.id in pol.enabled)
                else -> pol.allows(troop, false)
            }
            ShopCard(i, sigs[i], cost, trait, troop?.id, afford[i], arrow[i], allowed)
        }

        // 0) Две одинаковые 1★ на скамейке не слились за 4 секунды — тянем один на другого.
        val seenPairs = HashSet<Pair<Int, Int>>()
        for (i in 0 until 5) for (j in i + 1 until 5) {
            val si = held[i]?.sig; val sj = held[j]?.sig
            if (!bench[i] || !bench[j] || si == null || sj == null || !close(si, sj) || twoStar[i] || twoStar[j]) continue
            val key = i to j
            seenPairs += key
            val since = pairSince.getOrPut(key) { now }
            if (now - since > 4000 && (mergeTries[key] ?: 0) < 2) {
                mergeTries[key] = (mergeTries[key] ?: 0) + 1
                val ok = act {
                    GestureController.drag(ScreenRegions.benchX[i] * w, ScreenRegions.BENCH_Y * h,
                        ScreenRegions.benchX[j] * w, ScreenRegions.BENCH_Y * h, 400)
                }
                if (ok) log("Слияние не произошло само — сливаю слоты ${i + 1} и ${j + 1}")
                pairSince[key] = now
                delay(300); return
            }
        }
        pairSince.keys.retainAll(seenPairs)

        // 1) Выставляем со скамейки на поле по сетке: только на свободные клетки, не чаще раза в 20 с после неудачи.
        val slot = CycleStrategy.pickPlacement(benchUnits, build, k.placeSel)
        val wanted = slot?.let { benchUnits[it] }
        val wantedInBuild = wanted != null && CycleStrategy.inBuild(wanted.trait, wanted.troopId, build)
        // Боец сборки ждёт на скамейке, а поле полное — заменяем: лишнего продаём прямо с поля, затем ставим бойца сборки.
        if (slot != null && wanted != null && wantedInBuild && now >= ignoreFullUntil && pendingBoardSell == null) {
            if (now - counterAt > 800) { counter = ocr.readCounter(bmp); counterAt = System.currentTimeMillis() }
            val cc = counter
            val full = if (cc != null) cc.first >= cc.second else boardFullHint
            if (full && trySwap(k, build, wanted, bench, bmp, w, h, now)) { delay(200); return }
        }
        if (slot != null && placeGate.canTry()) {
            if (now - counterAt > 800) { counter = ocr.readCounter(bmp); counterAt = System.currentTimeMillis() }
            val c = counter
            if (c != null && c.first >= c.second && now >= ignoreFullUntil) {
                placeGate.onFail()
                log("Поле заполнено (${c.first}/${c.second}) — следующая попытка через 20 с")
            } else {
                val occupied = HexGrid.cells.map { PixelOps.cellOccupied(bmp, it) }
                board.keys.retainAll { occupied[it] }
                val bu = benchUnits[slot]
                val tid = bu?.let { idOf(it.troopId, it.trait) }
                val unit = if (bu != null && tid != null) PlacedUnit(tid, if (bu.twoStar) 2 else 1) else null
                val ownBoard: Map<HexCell, PlacedUnit> = board.entries.associate { HexGrid.cells[it.key].toHex() to it.value }
                val target = PositionEngine.pickGridCell(unit, occupied, badCells, ownBoard, position())
                if (target == null) {
                    placeGate.onFail()
                    log("Нет свободных клеток на поле — следующая попытка через 20 с")
                } else {
                    val ix = HexGrid.indexOf(target)
                    val ok = act { GestureController.drag(ScreenRegions.benchX[slot] * w, ScreenRegions.BENCH_Y * h, target.x * w, target.y * h) }
                    if (ok) {
                        log("Выставляю на поле (ряд ${target.row - HexGrid.FIRST_ROW + 1}, клетка ${target.col + 1})" + if (twoStar[slot]) " 2★" else "")
                        pendingPlace = PendingPlace(slot, ix, System.currentTimeMillis(), unit)
                    }
                    delay(150); return
                }
            }
        }

        // 1б) Боец сборки стоит не на своей клетке по карте — переставляем на свободную клетку по плану.
        val planNow = position().plan
        if (planNow.isNotEmpty() && pendingRelocate == null && pendingBoardSell == null && pendingBoardMove == null &&
            relocFails < 3 && now - lastRelocAt > 2500
        ) {
            val occ = HexGrid.cells.map { PixelOps.cellOccupied(bmp, it) }
            board.keys.retainAll { it in occ.indices && occ[it] }
            val rel = FieldFixer.pickRelocation(board, occ, planNow, badCells, relocGiveUp)
            if (rel != null) {
                lastRelocAt = now
                val from = HexGrid.cells[rel.fromIx]; val to = HexGrid.cells[rel.toIx]
                val ok = act { GestureController.drag(from.x * w, from.y * h, to.x * w, to.y * h, 450) }
                if (ok) {
                    log("«${unitName(k, rel.unit)}» стоит не по карте — переставляю на ${HexMap.label(to.toHex())}")
                    pendingRelocate = PendingRelocate(rel.fromIx, rel.toIx, now, rel.unit)
                }
                delay(200); return
            }
        }

        // 1в) Тактическая перестановка: только в фазе расстановки, при полном наборе
        // проверенных вручную данных и заметном выигрыше модели. Не меняем бойца из карты сборки.
        if (tacticalMoveRound != roundNo && pendingRelocate == null && pendingBoardSell == null && pendingBoardMove == null) {
            val occ = HexGrid.cells.map { PixelOps.cellOccupied(bmp, it) }
            board.keys.retainAll { it in occ.indices && occ[it] }
            val ownLayout = board.entries.associate { HexGrid.cells[it.key].toHex() to it.value }
            val freeCells = HexGrid.cells.filterIndexed { ix, cell -> !occ[ix] && ix !in badCells }.map { it.toHex() }
            tacticalMoveRound = roundNo
            val posCtx = position()
            val move = PositionEngine.bestTacticalMove(ownLayout, freeCells, posCtx)
            if (move != null && move.inputConfidence >= 0.75f) {
                val fromIx = HexGrid.cells.indexOfFirst { it.toHex() == move.from }
                val toIx = HexGrid.cells.indexOfFirst { it.toHex() == move.to }
                if (fromIx >= 0 && toIx >= 0 && occ[fromIx] && !occ[toIx]) {
                    val from = HexGrid.cells[fromIx]; val to = HexGrid.cells[toIx]
                    val ok = act { GestureController.holdDrag(from.x * w, from.y * h, to.x * w, to.y * h, Timing.holdMs, Timing.dragMs, Timing.dwellMs) }
                    if (ok) {
                        tacticalMoveRound = roundNo
                        log("Тактическая перестановка: ${unitName(k, move.unit)} ${HexMap.label(move.from)} → ${HexMap.label(move.to)}; оценка ${"%.3f".format(move.scoreBefore)} → ${"%.3f".format(move.scoreAfter)}, уверенность входных данных ${"%.0f".format(move.inputConfidence * 100)}%. Оценка боя приблизительная")
                        pendingRelocate = PendingRelocate(fromIx, toIx, now, move.unit)
                    }
                    delay(200); return
                }
            }
        }

        // 2) Продажа по порядку: 2★ вне сборки → дорогие одиночки → остальные. Продаём, если это 2★ (круг),
        //    скамейка полна, или нужно получить эликсир на карточку со слиянием.
        val arrowSigs = (0..2).filter { arrow[it] }.map { sigs[it] }
        val sellSlot = CycleStrategy.pickSell(benchUnits, arrowSigs, build)
        if (sellSlot != null && sellFails < 3) {
            val benchFull = bench.all { it }
            val needMoney = cards.any { it.arrow && !it.affordable }
            val reason = when {
                twoStar[sellSlot] -> "2★ вне сборки — продаю, круг даёт эликсир"
                benchFull -> "скамейка полна — продаю самого дорогого одиночку вне сборки"
                needMoney -> "нужен эликсир для слияния — продаю"
                else -> null
            }
            if (reason != null) {
                val ok = act {
                    GestureController.drag(ScreenRegions.benchX[sellSlot] * w, ScreenRegions.BENCH_Y * h,
                        ScreenRegions.SELL_X * w, ScreenRegions.SELL_Y * h, 450)
                }
                if (ok) {
                    log("Слот ${sellSlot + 1}: $reason")
                    val jid = journal?.log("SELL", "Продажа: слот ${sellSlot + 1}", reason, "Войско: " + (held[sellSlot]?.troopId?.let { k.troops[it]?.name } ?: "не опознано") + "\n2★: " + twoStar[sellSlot] + "\nСкамейка полна: " + benchFull, "PLANNED") ?: -1L
                    pendingSell = PendingSell(sellSlot, System.currentTimeMillis(), held[sellSlot], jid)
                }
                delay(200); return
            }
        }

        // 2б) Маловероятное улучшение: одиночку из брошенных продаём — эликсир вернётся и пойдёт на лучшее.
        if (abandoned.isNotEmpty() && sellFails < 3 && pendingSell == null) {
            val keys = (0 until 5).map { benchKey(it) }
            val s2 = AdaptivePlanner.pickAbandonedSell(benchUnits, keys, abandoned, build)
            if (s2 != null) {
                val ok = act {
                    GestureController.drag(ScreenRegions.benchX[s2] * w, ScreenRegions.BENCH_Y * h,
                        ScreenRegions.SELL_X * w, ScreenRegions.SELL_Y * h, 450)
                }
                if (ok) {
                    log("Слот ${s2 + 1}: шансов дособрать улучшение мало — продаю одиночку, эликсир пойдёт на лучшее")
                    val jid = journal?.log("SELL", "Продажа брошенного улучшения: слот ${s2 + 1}", "Шансов дособрать улучшение мало, эликсир пойдёт на лучшее.",
                        "Войско: " + (held[s2]?.troopId?.let { k.troops[it]?.name } ?: "не опознано"), "PLANNED") ?: -1L
                    pendingSell = PendingSell(s2, System.currentTimeMillis(), held[s2], jid)
                }
                delay(200); return
            }
        }

        // 3) Покупка. Бот оценивает несколько сценариев на будущее: купить каждую из карточек или копить эликсир.
        //    Есть пара для слияния — берём; нужной карточки нет — альтернатива; ничто не помогает — копим эликсир.
        val benchHasSpace = bench.any { !it }
        if (cards.none { it.affordable && it.allowed && (benchHasSpace || it.arrow) }) return
        val op = oddsParams()
        val env = Env(
            elixir = elixirNow(bmp, now), income = op.income,
            roundsLeft = (op.rounds - roundNo + 1).coerceAtLeast(1),
            shopSlots = op.shopSlots, tiers = tiersOf(k.troops.values.toList()), weights = op.weights,
        )
        val lines = buildLines(k, bench, twoStar)
        val cardKeys = cards.map { it.troopId ?: repKey(it.sig) }

        // Смена направления сборки: только для найденной автоматически, выбор пользователя не трогаем.
        if (k.policy.traits.isEmpty() && k.placeSel.isEmpty() && autoBuild.found && now - lastPivotAt > 30000) {
            val pv = AdaptivePlanner.pivot(lines, cards, autoBuild, env)
            if (pv != null) {
                log("Меняю направление сборки: " + autoBuild.traits.joinToString { traitName(it, k) } + " → " +
                    pv.build.traits.joinToString { traitName(it, k) } +
                    " (оценка " + AdaptivePlanner.fmt(pv.valueNow) + " → " + AdaptivePlanner.fmt(pv.valueNew) + ")")
                journal?.log("PIVOT", "Смена направления сборки",
                    "Другое свойство заметно выгоднее по оценке сценариев: " + AdaptivePlanner.fmt(pv.valueNow) + " → " + AdaptivePlanner.fmt(pv.valueNew) + " (порог разницы " + AdaptivePlanner.PIVOT_MARGIN + ").",
                    "Было: " + autoBuild.traits.joinToString { traitName(it, k) } + "\nСтало: " + pv.build.traits.joinToString { traitName(it, k) }, "DONE")
                autoBuild = pv.build
                lastPivotAt = now
            }
        }
        val buildNow = currentBuild(k)
        ownCopies = lines.mapNotNull { l -> l.troopId?.let { it to (l.ones + 2 * l.twos) } }.toMap()
        ownBoard = board.values.filter { !FieldFixer.isPseudo(it.troopId) }.map { Seen(it.troopId, it.stars) }
        val bias = adaptBias()
        val dec = AdaptivePlanner.decide(cards, cardKeys, lines, buildNow, benchHasSpace, env, abandoned, bias)
        val fresh = dec.abandoned - abandoned
        if (fresh.isNotEmpty()) {
            log("Отказываюсь от маловероятного улучшения (${fresh.size}): шансов собрать мало, эликсир не трачу")
            journal?.log("ABANDON", "Отказ от улучшения: " + fresh.joinToString { keyName(it, k) },
                "Шанс дособрать недостающие копии за оставшиеся раунды ниже порога ${AdaptivePlanner.ABANDON_BELOW}; эликсир не тратится.",
                "Раундов осталось: ${env.roundsLeft}\nЭликсир: ${env.elixir ?: "не прочитан"}\nШансы выпадения — модель по умолчанию (не проверено по игре)", "INFO", dedupKey = "abandon-" + fresh.sorted().joinToString())
        }
        abandoned.clear(); abandoned.addAll(dec.abandoned)
        if (dec.kind != Kind.NONE && dec.reason.isNotEmpty()) {
            val text = dec.kind.name + dec.reason
            if (text != lastPlan || now - lastPlanAt > 6000) {
                lastPlan = text; lastPlanAt = now
                log("План: ${dec.reason}. Сценарии: ${AdaptivePlanner.describe(dec.scenarios)}")
                if (dec.index == null) {
                    journal?.log(if (dec.kind == Kind.SAVE) "SAVE" else "EVENT", dec.kind.name + ": " + dec.reason, dec.reason,
                        decisionDetails(dec.scenarios, bias, env), "INFO", dedupKey = "plan-" + dec.kind.name + dec.reason)
                }
            }
        }
        planDetails = decisionDetails(dec.scenarios, bias, env)
        planReason = dec.kind.name + ": " + dec.reason
        // Оценка прокачки: звёздность, копии, пул, шанс, цена и сила улучшения по каждому войску.
        if (lines.isNotEmpty() && now - lastEvalAt > 15000) {
            lastEvalAt = now
            val posCtx = position()
            val boardLayout: Map<HexCell, PlacedUnit> = board.entries.associate { HexGrid.cells[it.key].toHex() to it.value }
            val uctx = UpgradeContext(
                lines = lines, build = buildNow,
                traitTiers = k.traits.associate { it.id to it.tiers },
                names = { id -> k.traits.firstOrNull { it.id == id }?.name ?: id },
                ruler = posCtx.combat.ruler, modifiers = posCtx.combat.modifiers,
                matchup = if (posCtx.enemy.isNotEmpty()) MatchupInput(boardLayout + posCtx.enemy, posCtx.stats, posCtx.combat) else null,
            )
            val top = ContextEvaluator.rank(uctx, env, UpgradeRules.from(op)) { l -> l.troopId?.let { k.troops[it]?.name } ?: "войско" }.take(3)
            log("Оценка прокачки: " + top.joinToString(" | ") { ContextEvaluator.describe(it) })
            journal?.log("EVAL", "Оценка прокачки", "Плановая оценка войск: звёздность, копии, пул, шанс, цена, сила и контекст.",
                top.joinToString("\n") { ContextEvaluator.describe(it) }, "INFO", dedupKey = "eval")
        }
        val pick = dec.index ?: return
        val pc = cards[pick]
        val ok = act { GestureController.tap(ScreenRegions.cardX[pick] * w, ScreenRegions.CARD_Y * h) }
        if (ok) {
            log("Покупаю карточку ${pick + 1}" + (pc.troopId?.let { " «" + (k.troops[it]?.name ?: it) + "»" } ?: "") +
                ": цена " + (pc.cost?.let { "$it эликсира" } ?: "не прочитана") +
                (pc.trait?.let { ", свойство «${traitName(it, k)}»" } ?: "") + (if (pc.arrow) " (слияние)" else ""))
            val jid = journal?.log("BUY", "Покупка: карточка ${pick + 1}" + (pc.troopId?.let { " «" + (k.troops[it]?.name ?: it) + "»" } ?: ""),
                planReason.ifEmpty { dec.reason },
                "Цена: ${pc.cost?.let { "$it эликсира" } ?: "не прочитана"}\nСвойство: ${pc.trait?.let { traitName(it, k) } ?: "не определено"}\nСлияние: ${pc.arrow}\n" + planDetails, "PLANNED") ?: -1L
            pendingBuy = PendingBuy(pick, sigs[pick], pc.cost, pc.trait, pc.troopId, System.currentTimeMillis(), jid)
        }
        delay(100)
    }
}
