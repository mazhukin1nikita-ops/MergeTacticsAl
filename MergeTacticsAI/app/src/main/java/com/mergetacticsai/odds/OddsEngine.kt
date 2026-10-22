package com.mergetacticsai.odds

import kotlin.math.abs
import kotlin.math.roundToInt

/** Результат с диапазоном: low/high получены при пессимистичных и оптимистичных значениях непроверенных параметров. */
data class Range(val low: Double, val nominal: Double, val high: Double) {
    companion object {
        fun of(pess: Double, nom: Double, opt: Double): Range {
            val all = listOf(pess, nom, opt)
            return Range(all.min(), nom, all.max())
        }
    }
}

enum class Confidence(val title: String) { HIGH("высокая"), MEDIUM("средняя"), LOW("низкая") }

data class SimOut(
    val range: Range,
    /** Статистическая точность самого моделирования (95%) для номинального сценария. */
    val ciLow: Double, val ciHigh: Double,
    val meanElixir: Double?, val meanRounds: Double?,
)

data class OddsReport(
    val slotChance: Range,
    val oneShop: Range,
    val afterRefreshes: List<Pair<Int, Range>>,
    /** Сколько ещё копий нужно получить для цели. */
    val neededCopies: Int,
    /** Шанс, что нужное число копий просто выпадет в бесплатных магазинах за все раунды (без эликсира и мест). */
    val copiesAppear: Range?,
    val expectedViews: Range,
    val expectedElixir: Range,
    /** Шанс достичь цели до конца игры (моделирование). */
    val finish: SimOut,
    val confidence: Confidence,
    val unverified: List<String>,
    val warnings: List<String>,
)

object OddsEngine {
    /** Пессимистичный и оптимистичный наборы параметров. Проверенные параметры не меняются. */
    fun scenarios(p: OddsParams, u: Uncertainty, verified: Set<String>): Pair<OddsParams, OddsParams> {
        val oddsS = if (Keys.ODDS in verified) 0.0 else u.oddsSpreadPct / 100.0
        val refS = if (Keys.REFRESH in verified) 0 else u.refreshSpread
        val rndS = if (Keys.ROUNDS in verified) 0 else u.roundsSpread
        val poolS = if (Keys.POOL in verified) 0.0 else u.poolSpreadPct / 100.0
        val othS = if (Keys.OTHERS in verified) 0.0 else u.othersSpreadPct / 100.0

        val pess = p.copy(
            qScale = p.qScale * (1.0 - oddsS).coerceAtLeast(0.05),
            refreshCost = p.refreshCost + refS,
            rounds = (p.rounds - rndS).coerceAtLeast(1),
            copiesPerTroop = if (p.copiesPerTroop > 0) (p.copiesPerTroop * (1.0 - poolS)).roundToInt().coerceAtLeast(1) else 0,
            othersDrawsPerRound = (p.othersDrawsPerRound * (1.0 + othS)).roundToInt(),
        )
        val opt = p.copy(
            qScale = p.qScale * (1.0 + oddsS),
            refreshCost = (p.refreshCost - refS).coerceAtLeast(0),
            rounds = p.rounds + rndS,
            copiesPerTroop = if (p.copiesPerTroop > 0) (p.copiesPerTroop * (1.0 + poolS)).roundToInt().coerceAtLeast(1) else 0,
            othersDrawsPerRound = (p.othersDrawsPerRound * (1.0 - othS)).roundToInt(),
        )
        return pess to opt
    }

    fun neededCopies(s: OddsState): Int {
        val p = s.params
        return when (s.goal.kind) {
            GoalKind.COPIES -> (s.goal.value - p.startCopies).coerceAtLeast(0)
            GoalKind.STARS -> (ShopMath.copiesForStar(s.goal.value, p.copiesPerMerge) - p.startCopies).coerceAtLeast(0)
        }
    }

    /** Какие непроверенные параметры влияют на этот расчёт. */
    fun unverifiedKeys(s: OddsState): List<String> {
        val finite = s.params.copiesPerTroop > 0
        return Keys.all.filter { it !in s.verified && (finite || (it != Keys.OTHERS)) }
    }

    fun confidence(unverifiedCount: Int): Confidence = when {
        unverifiedCount == 0 -> Confidence.HIGH
        unverifiedCount <= 2 -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    fun compute(s: OddsState, tiers: Map<Int, Int>, targetCost: Int, runs: Int = 20_000, seed: Long = 20_260_410L): OddsReport {
        val (pess, opt) = scenarios(s.params, s.unc, s.verified)
        val nom = s.params
        val need = neededCopies(s)
        val slots = nom.shopSlots

        fun q(p: OddsParams) = ShopMath.slotChance(p, tiers, targetCost)
        fun range(f: (OddsParams) -> Double) = Range.of(f(pess), f(nom), f(opt))

        val warnings = mutableListOf<String>()
        if ((tiers[targetCost] ?: 0) <= 0) warnings += "В текущем пуле нет войск с ценой $targetCost."
        if (q(nom) <= 0.0) warnings += "Шанс выпадения равен нулю: проверьте веса цен и размер пула."
        if (s.goal.kind == GoalKind.STARS) {
            val needSlots = ShopMath.slotsNeeded(s.goal.value, nom.copiesPerMerge)
            if (nom.freeSlots < needSlots) warnings += "Для ${s.goal.value}★ нужно хотя бы $needSlots мест под копии, сейчас указано ${nom.freeSlots}: цель недостижима."
        }
        if (targetCost > nom.income * nom.budgetShare + nom.startElixir && targetCost > 0) {
            warnings += "Цена войска больше дохода за раунд: покупка возможна только с накоплением."
        }
        if (nom.copiesPerTroop > 0 && ShopMath.remainingTarget(nom) < need) {
            warnings += "В пуле осталось меньше копий, чем нужно для цели."
        }

        val oneShop = range { 1.0 - Math.pow(1.0 - q(it), it.shopSlots.toDouble()) }
        val refreshes = listOf(1, 3, 5).map { n -> n to range { ShopMath.pSeeInShops(q(it), it.shopSlots, n + 1) } }
        val copiesAppear = if (need > 0) range { ShopMath.pAtLeast(it.shopSlots * it.rounds, need, q(it)) } else null
        val views = range { ShopMath.expectedViews(q(it), it.shopSlots, need) }
        val elixir = range { ShopMath.expectedElixir(q(it), it.shopSlots, need, targetCost, it.refreshCost) }

        val simNom = Simulator.run(nom, tiers, targetCost, s.goal, runs, seed)
        val simPess = Simulator.run(pess, tiers, targetCost, s.goal, runs, seed + 1)
        val simOpt = Simulator.run(opt, tiers, targetCost, s.goal, runs, seed + 2)
        val ci = simNom.interval()
        val finish = SimOut(
            Range.of(simPess.p, simNom.p, simOpt.p), ci.first, ci.second,
            if (simNom.successes > 0) simNom.meanSpentOnSuccess else null,
            if (simNom.successes > 0) simNom.meanRoundsOnSuccess else null,
        )

        val unverified = unverifiedKeys(s)
        return OddsReport(
            slotChance = range { q(it) },
            oneShop = oneShop,
            afterRefreshes = refreshes,
            neededCopies = need,
            copiesAppear = copiesAppear,
            expectedViews = views,
            expectedElixir = elixir,
            finish = finish,
            confidence = confidence(unverified.size),
            unverified = unverified.map { Keys.title(it) },
            warnings = warnings,
        )
    }
}

// ---- форматирование (чистые функции, без Android) ----

fun pct(d: Double): String {
    val v = d * 100.0
    return when {
        d <= 0.0 -> "0%"
        d >= 1.0 -> "100%"
        v < 0.1 -> "<0,1%"
        v > 99.9 -> ">99,9%"
        v < 10.0 -> {
            val r = (v * 10).roundToInt() / 10.0
            if (r >= 10.0) "10%" else "${r.toString().replace('.', ',')}%"
        }
        else -> "${v.roundToInt()}%"
    }
}

fun rangeText(r: Range): String =
    if (abs(r.high - r.low) < 0.0005) pct(r.nominal) else "${pct(r.nominal)} (диапазон ${pct(r.low)}–${pct(r.high)})"

fun num(d: Double): String =
    if (d.isInfinite() || d.isNaN()) "∞" else ((d * 10).roundToInt() / 10.0).toString().replace('.', ',')

fun numRangeText(r: Range, unit: String = ""): String {
    val u = if (unit.isEmpty()) "" else " $unit"
    return if (r.nominal.isInfinite()) "недостижимо при заданных параметрах"
    else if (abs(r.high - r.low) < 0.05) "${num(r.nominal)}$u"
    else "${num(r.nominal)}$u (диапазон ${num(r.low)}–${num(r.high)})"
}
