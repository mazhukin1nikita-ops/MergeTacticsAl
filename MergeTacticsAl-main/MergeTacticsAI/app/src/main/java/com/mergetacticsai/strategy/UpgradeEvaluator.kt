package com.mergetacticsai.strategy

import com.mergetacticsai.odds.OddsParams
import com.mergetacticsai.odds.ShopMath
import com.mergetacticsai.odds.num
import com.mergetacticsai.odds.pct

/**
 * Во сколько раз сильнее войско на 2★ и 3★ по сравнению с 1★.
 * Это предположение: проверенных чисел в базе приложения нет, подправьте под свои наблюдения.
 */
data class StarPower(val two: Double = 1.8, val three: Double = 3.2) {
    fun of(stars: Int): Double = when {
        stars <= 0 -> 0.0
        stars == 1 -> 1.0
        stars == 2 -> two
        else -> three
    }
}

/** Правила, от которых зависит оценка: слияние, пул, обновления, сила звёзд. */
data class UpgradeRules(
    /** Сколько одинаковых войск сливаются в следующую звезду. */
    val perMerge: Int = 2,
    /** Копий каждого войска в пуле; 0 — пул не ограничен. */
    val copiesPerTroop: Int = 0,
    /** Копий этого войска, уже взятых соперниками. */
    val takenByOthers: Int = 0,
    val refreshCost: Int = 2,
    val maxRefreshPerRound: Int = 2,
    /** Какую долю дохода можно тратить на прокачку (0..1). */
    val budgetShare: Double = 1.0,
    val power: StarPower = StarPower(),
    /** Насколько сильнее каждая следующая цена: сила 1★ войска = 1 + шаг × (цена − 1). Предположение. */
    val costPowerStep: Double = 0.3,
) {
    companion object {
        /** Берём правила с экрана «Вероятности», чтобы бот и экран считали одинаково. */
        fun from(p: OddsParams) = UpgradeRules(
            perMerge = p.copiesPerMerge.coerceAtLeast(2),
            copiesPerTroop = p.copiesPerTroop,
            takenByOthers = p.takenByOthers,
            refreshCost = p.refreshCost,
            maxRefreshPerRound = p.maxRefreshPerRound,
            budgetShare = p.budgetShare,
        )
    }
}

/**
 * Что у нас есть по одному войску: сколько копий каждой звёздности.
 * targetStar — до какой звезды качаем; по умолчанию до следующей.
 */
data class TroopHolding(
    val id: String,
    val name: String,
    val cost: Int,
    val ones: Int,
    val twos: Int,
    val threes: Int = 0,
    val inBuild: Boolean = false,
    val targetStar: Int? = null,
) {
    /** Лучшая звёздность среди копий; 0 — копий нет. */
    val currentStar: Int get() = when {
        threes > 0 -> 3
        twos > 0 -> 2
        ones > 0 -> 1
        else -> 0
    }
}

enum class UpgradeVerdict(val title: String, val order: Int) {
    READY("можно слить прямо сейчас", 0),
    PUSH("прокачивать", 1),
    WAIT("ждать нужную карточку", 2),
    SKIP("отказаться: шансов мало", 3),
    IMPOSSIBLE("не хватит копий в пуле", 4),
    DONE("уже максимум", 5),
}

/** Оценка прокачки одного войска. Все числа считаются для перехода [currentStar] → [targetStar]. */
data class UpgradeEval(
    val id: String,
    val name: String,
    val currentStar: Int,
    val targetStar: Int,
    /** Сколько копий 1★ в пересчёте уже есть. */
    val copies: Int,
    /** Сколько копий 1★ нужно всего для целевой звезды. */
    val copiesNeeded: Int,
    /** Сколько копий не хватает. */
    val missing: Int,
    /** Сколько копий осталось в пуле; null — пул не ограничен. */
    val poolLeft: Int?,
    /** Шанс, что один слот магазина покажет это войско. */
    val slotChance: Double,
    /** Шанс собрать недостающие копии до конца игры с учётом эликсира. */
    val pFinish: Double,
    val cost: Int,
    /** Цена недостающих копий без обновлений. */
    val totalCost: Int,
    /** Ожидаемые траты вместе с платными обновлениями; бесконечность — недостижимо. */
    val expectedElixir: Double,
    /** Насколько вырастет сила: (сила целевой звезды − сила текущей) × сила войска, вдвое меньше вне сборки. */
    val powerGain: Double,
    val expectedGain: Double,
    /** Ожидаемый прирост силы на один потраченный эликсир. */
    val efficiency: Double,
    val verdict: UpgradeVerdict,
    val reason: String,
)

/**
 * Модуль оценки прокачки. Чистый Kotlin, без Android.
 * Для каждого войска учитывает: текущую звёздность, число копий, оставшийся пул,
 * шанс получить недостающие копии, стоимость покупки и силу улучшения.
 */
object UpgradeEvaluator {
    const val MAX_STAR = 3
    const val PUSH_FROM = 0.5

    private fun unitPower(cost: Int, rules: UpgradeRules): Double = 1.0 + rules.costPowerStep * (cost - 1).coerceAtLeast(0)

    private fun slotChance(h: TroopHolding, equiv: Int, env: Env, rules: UpgradeRules): Double {
        if (env.tiers.isEmpty()) return AdaptivePlanner.FALLBACK_Q
        val p = OddsParams(
            shopSlots = env.shopSlots, weights = env.weights,
            copiesPerTroop = rules.copiesPerTroop, takenByOthers = rules.takenByOthers, startCopies = equiv,
        )
        return ShopMath.slotChance(p, env.tiers, h.cost)
    }

    fun evaluate(h: TroopHolding, env: Env, rules: UpgradeRules = UpgradeRules()): UpgradeEval {
        val m = rules.perMerge.coerceAtLeast(2)
        val cur = h.currentStar
        val target = (h.targetStar ?: (cur + 1)).coerceIn(1, MAX_STAR)
        val equiv = h.ones + m * h.twos + m * m * h.threes
        val poolLeft: Int? = if (rules.copiesPerTroop > 0) (rules.copiesPerTroop - equiv - rules.takenByOthers).coerceAtLeast(0) else null
        val needed = ShopMath.copiesForStar(target, m)
        val missing = (needed - equiv).coerceAtLeast(0)
        val buildFactor = if (h.inBuild) 1.0 else 0.5
        val gain = ((rules.power.of(target) - rules.power.of(cur)) * unitPower(h.cost, rules) * buildFactor).coerceAtLeast(0.0)
        val q = slotChance(h, equiv, env, rules)

        fun result(
            p: Double, exp: Double, eff: Double, v: UpgradeVerdict, reason: String,
        ) = UpgradeEval(
            id = h.id, name = h.name, currentStar = cur, targetStar = target, copies = equiv, copiesNeeded = needed,
            missing = missing, poolLeft = poolLeft, slotChance = q, pFinish = p, cost = h.cost,
            totalCost = missing * h.cost, expectedElixir = exp, powerGain = gain, expectedGain = p * gain,
            efficiency = eff, verdict = v, reason = reason,
        )

        if (cur >= MAX_STAR || cur >= target) {
            return result(1.0, 0.0, 0.0, UpgradeVerdict.DONE, "звёздность уже максимальная для цели")
        }
        if (missing == 0) {
            return result(1.0, 0.0, gain, UpgradeVerdict.READY, "копий хватает: сливаем и получаем ${target}★")
        }
        if ((poolLeft != null && poolLeft < missing) || q <= 0.0) {
            val why = if (poolLeft != null && poolLeft < missing) "в пуле осталось $poolLeft, а нужно $missing" else "в пуле нет такого войска"
            return result(0.0, Double.POSITIVE_INFINITY, 0.0, UpgradeVerdict.IMPOSSIBLE, why)
        }

        val rounds = env.roundsLeft.coerceAtLeast(0)
        val budget = (env.elixir ?: AdaptivePlanner.UNKNOWN_ELIXIR).toDouble() + env.income.toDouble() * rounds * rules.budgetShare
        val total = missing.toDouble() * h.cost
        // Свободный эликсир идёт на платные обновления магазина: больше просмотров — выше шанс.
        val spare = (budget - total).coerceAtLeast(0.0)
        val maxPaid = rules.maxRefreshPerRound.coerceAtLeast(0) * rounds
        val paid = if (rules.refreshCost <= 0) maxPaid else minOf(maxPaid, (spare / rules.refreshCost).toInt())
        val slots = env.shopSlots * (rounds + paid)
        val appear = ShopMath.pAtLeast(slots, missing, q)
        val afford = if (total <= 0.0 || budget >= total) 1.0 else (budget / total).coerceIn(0.0, 1.0)
        val p = appear * afford

        val exp = ShopMath.expectedElixir(q, env.shopSlots, missing, h.cost, rules.refreshCost)
        val spend = if (exp.isInfinite() || exp.isNaN()) Double.POSITIVE_INFINITY else maxOf(1.0, exp)
        val eff = if (spend.isInfinite()) 0.0 else p * gain / spend

        val verdict = when {
            p >= PUSH_FROM -> UpgradeVerdict.PUSH
            p >= AdaptivePlanner.ABANDON_BELOW -> UpgradeVerdict.WAIT
            else -> UpgradeVerdict.SKIP
        }
        val reason = when (verdict) {
            UpgradeVerdict.PUSH -> "шансы хорошие"
            UpgradeVerdict.WAIT -> "шансы средние: покупаем, если карточка выпадет"
            else -> if (afford < 1.0) "не хватает эликсира на недостающие копии" else "осталось мало раундов или слишком редкое войско"
        }
        return result(p, exp, eff, verdict, reason)
    }

    /** Оценивает все войска и сортирует: можно слить → лучшая отдача на эликсир → отказ → невозможное. */
    fun rank(holdings: List<TroopHolding>, env: Env, rules: UpgradeRules = UpgradeRules()): List<UpgradeEval> =
        holdings.map { evaluate(it, env, rules) }
            .sortedWith(compareBy<UpgradeEval> { it.verdict.order }.thenByDescending { it.efficiency }.thenByDescending { it.pFinish })

    /** Линии бота → войска для оценки. */
    fun fromLines(lines: List<Line>, build: Build, nameOf: (Line) -> String): List<TroopHolding> =
        lines.map { l ->
            TroopHolding(l.key, nameOf(l), l.price, l.ones, l.twos, 0, CycleStrategy.inBuild(l.trait, l.troopId, build))
        }

    /** Короткая строка для лога. */
    fun describe(e: UpgradeEval): String {
        val star = "${e.currentStar}★→${e.targetStar}★"
        return when (e.verdict) {
            UpgradeVerdict.DONE -> "${e.name}: ${e.verdict.title}"
            UpgradeVerdict.READY -> "${e.name} $star: ${e.verdict.title}"
            UpgradeVerdict.IMPOSSIBLE -> "${e.name} $star: ${e.reason}"
            else -> "${e.name} $star: копий ${e.copies}/${e.copiesNeeded}, шанс ${pct(e.pFinish)}, " +
                "цена ${e.totalCost}, сила +${num(e.powerGain)} — ${e.verdict.title}"
        }
    }
}
