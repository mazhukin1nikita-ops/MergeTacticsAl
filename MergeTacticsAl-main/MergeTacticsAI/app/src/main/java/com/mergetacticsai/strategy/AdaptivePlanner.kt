package com.mergetacticsai.strategy

import com.mergetacticsai.odds.OddsParams
import com.mergetacticsai.odds.ShopMath

/**
 * Линия прокачки: все копии одного войска, которые сейчас есть у бота (скамейка и поле).
 * ones — число 1★, twos — число 2★. Слияние идёт по два: 2 копии 1★ дают 2★, две 2★ дают 3★.
 */
data class Line(val key: String, val troopId: String?, val trait: String?, val cost: Int?, val ones: Int, val twos: Int) {
    /** Сколько одно-звёздочных копий лежит в линии. */
    val equiv: Int get() = ones + 2 * twos

    /** Сколько ещё копий 1★ нужно до следующей звезды. */
    val need: Int get() = ((if (twos > 0) 4 else 2) - equiv).coerceAtLeast(0)

    val price: Int get() = cost ?: LocalBuyPlanner.DEFAULT_COST
}

/**
 * Условия игры для оценки. elixir == null — эликсир не прочитан.
 * tiers — сколько войск каждой цены в пуле; пусто — шанс карточки берётся по запасному значению.
 */
data class Env(
    val elixir: Int?,
    val income: Int = 4,
    val roundsLeft: Int = 8,
    val shopSlots: Int = 3,
    val tiers: Map<Int, Int> = emptyMap(),
    val weights: Map<Int, Double> = emptyMap(),
)

enum class Kind { BUY, CONTINUE, ALTERNATIVE, SAVE, NONE }

/** Один сценарий на будущее и его оценка: чем больше, тем лучше. */
data class Scenario(val label: String, val value: Double)

/**
 * index — какую карточку купить (null — не покупать).
 * abandoned — войска, дособирать которые бот перестал (накапливается за матч).
 */
data class BuyDecision(
    val index: Int?,
    val kind: Kind,
    val reason: String,
    val scenarios: List<Scenario>,
    val abandoned: Set<String>,
)

data class PivotDecision(val build: Build, val valueNow: Double, val valueNew: Double)

/**
 * Адаптивная часть стратегии. Чистый Kotlin, без Android.
 *
 * Умеет: продолжать прокачку текущего войска, отказываться от маловероятного улучшения,
 * выбирать альтернативную карточку, беречь эликсир, менять направление сборки и сравнивать
 * несколько сценариев на будущее.
 *
 * Оценка сценария — «ожидаемое число доведённых улучшений»: для каждой линии считаем шанс
 * дособрать недостающие копии за оставшиеся раунды (формулы из [ShopMath]) с учётом эликсира.
 * Шансы выпадения — модель по умолчанию (непроверенные параметры), поэтому пороги мягкие.
 */
object AdaptivePlanner {
    /** Ниже этого шанса улучшение считается маловероятным. */
    const val ABANDON_BELOW = 0.25

    /** Выше этого шанса ранее брошенное улучшение снова считается живым. */
    const val KEEP_ABOVE = 0.5

    /** Сколько «стоит» один эликсир в единицах оценки: за это бот предпочитает копить, а не тратить впустую. */
    const val ELIXIR_VALUE = 0.03

    /** Насколько новая сборка должна быть лучше текущей, чтобы менять направление. */
    const val PIVOT_MARGIN = 0.6

    const val FALLBACK_Q = 0.05
    const val UNKNOWN_ELIXIR = 3

    fun fmt(v: Double): String = (Math.round(v * 100.0) / 100.0).toString().replace('.', ',')

    /** Шанс, что один слот магазина покажет нужное войско этой цены. */
    fun slotChance(env: Env, cost: Int): Double {
        val n = env.tiers[cost] ?: 0
        if (n <= 0) return FALLBACK_Q
        val q = ShopMath.slotChance(OddsParams(shopSlots = env.shopSlots, weights = env.weights), env.tiers, cost)
        return if (q > 0.0) q else FALLBACK_Q
    }

    /** Сколько эликсира можно потратить до конца игры: текущий плюс доход за оставшиеся раунды. */
    fun budget(env: Env): Double = (env.elixir ?: UNKNOWN_ELIXIR).toDouble() + env.income.toDouble() * env.roundsLeft.coerceAtLeast(0)

    /** Шанс собрать [need] копий по цене [cost] за оставшиеся раунды при бюджете [budget]. */
    fun pFinish(need: Int, cost: Int, env: Env, budget: Double): Double {
        if (need <= 0) return 1.0
        val slots = env.shopSlots * env.roundsLeft.coerceAtLeast(0)
        val appear = ShopMath.pAtLeast(slots, need, slotChance(env, cost))
        val total = need.toDouble() * cost
        val afford = if (total <= 0.0 || budget >= total) 1.0 else (budget / total).coerceIn(0.0, 1.0)
        return appear * afford
    }

    private fun inBuild(l: Line, build: Build) = CycleStrategy.inBuild(l.trait, l.troopId, build)

    private fun weight(l: Line, build: Build): Double = when {
        inBuild(l, build) -> 1.0
        !build.found -> 0.6
        else -> 0.25
    }

    /** Шанс довести линию до следующей звезды с учётом карточек, которые видны в магазине прямо сейчас. */
    fun outlook(l: Line, visibleNow: Int, build: Build, env: Env): Double {
        val share = if (inBuild(l, build)) 1.0 else 0.5
        return pFinish((l.need - visibleNow).coerceAtLeast(0), l.price, env, budget(env) * share)
    }

    /** Оценка набора линий: чем больше, тем больше улучшений мы ожидаем довести. Эликсир делится между линиями по важности. */
    fun value(lines: List<Line>, build: Build, env: Env, budget: Double): Double {
        var left = budget
        var sum = 0.0
        for (l in lines.sortedByDescending { weight(it, build) }) {
            val p = pFinish(l.need, l.price, env, left)
            sum += weight(l, build) * p
            left = (left - l.need.toDouble() * l.price * p).coerceAtLeast(0.0)
        }
        return sum
    }

    private fun withCopy(lines: List<Line>, key: String, troopId: String?, trait: String?, cost: Int?): List<Line> {
        val ex = lines.firstOrNull { it.key == key }
        return if (ex == null) lines + Line(key, troopId, trait, cost, 1, 0)
        else lines.map { if (it.key == key) it.copy(ones = it.ones + 1) else it }
    }

    private class Option(val card: ShopCard, val value: Double, val continues: Boolean)

    /**
     * Решение о покупке.
     * 1) Есть пара для слияния — берём (продолжаем прокачку).
     * 2) Иначе считаем сценарии: «купить каждую доступную карточку» и «копить эликсир», выбираем лучший.
     * 3) Улучшения с шансом ниже порога бросаем: такие карточки не покупаем, а одиночки можно продать.
     * 4) Если нужной карточки нет — берём альтернативу; если ничто не повышает шансы — копим эликсир.
     */
    fun decide(
        cards: List<ShopCard>,
        keys: List<String>,
        lines: List<Line>,
        build: Build,
        benchHasSpace: Boolean,
        env: Env,
        abandonedBefore: Set<String>,
        /** Поправки от анализа соперников: id войска → прибавка к оценке (отрицательная — за карту идёт борьба). Пусто — не влияет. */
        bias: Map<String, Double> = emptyMap(),
    ): BuyDecision {
        val visible = HashMap<String, Int>()
        for (c in cards) {
            if (!c.affordable || !c.allowed) continue
            val k = keys.getOrNull(c.index) ?: continue
            visible[k] = (visible[k] ?: 0) + 1
        }

        // Какие улучшения бросаем. Сборка и стрелки не бросаются; брошенное остаётся брошенным, пока шанс не вырос.
        val abandoned = HashSet<String>(abandonedBefore)
        for (l in lines) {
            if (inBuild(l, build)) { abandoned.remove(l.key); continue }
            if (l.need <= 0 || l.troopId == CycleStrategy.ARCHER) continue
            val p = outlook(l, visible[l.key] ?: 0, build, env)
            if (p < ABANDON_BELOW) abandoned.add(l.key)
            else if (p >= KEEP_ABOVE) abandoned.remove(l.key)
        }

        // Допустимые карточки, не считая стрелок.
        val arrows = ArrayList<ShopCard>()
        val others = ArrayList<ShopCard>()
        for (c in cards) {
            if (!c.affordable || !c.allowed) continue
            if (!benchHasSpace && !c.arrow) continue
            val cost = c.cost ?: LocalBuyPlanner.DEFAULT_COST
            val e = env.elixir
            if (e != null && c.cost != null && cost > e) continue
            if (c.arrow) arrows.add(c) else others.add(c)
        }

        if (arrows.isNotEmpty()) {
            val idx = CycleStrategy.pickBuy(arrows, build, benchHasSpace)
            if (idx != null) return BuyDecision(idx, Kind.CONTINUE, "есть пара для слияния — продолжаю прокачку", emptyList(), abandoned)
        }

        // Сценарии. «Копить» — ничего не покупаем, бюджет остаётся целым.
        val base = budget(env)
        val saveValue = value(lines, build, env, base)
        val scenarios = ArrayList<Scenario>()
        scenarios.add(Scenario("копить эликсир", saveValue))

        // Ближайшее к завершению улучшение сборки: под него держим запас эликсира.
        val focus = lines.filter { inBuild(it, build) && it.need > 0 && it.key !in abandoned }.minByOrNull { it.need }
        val reserve = if (focus != null && focus.need == 1 && env.elixir != null) focus.price else 0

        val options = ArrayList<Option>()
        var reservedSkip = false
        var abandonedSkip = false
        for (c in others) {
            val key = keys.getOrNull(c.index) ?: ("card:" + c.index)
            val cost = c.cost ?: LocalBuyPlanner.DEFAULT_COST
            val existing = lines.firstOrNull { it.key == key }
            val cardInBuild = CycleStrategy.inBuild(c.trait, c.troopId, build)
            if (key in abandoned && !cardInBuild) { abandonedSkip = true; continue }
            // Как в основной стратегии: когда сборка найдена, дорогих вне сборки не берём.
            if (build.found && !cardInBuild && existing == null && cost > 2) continue
            val e = env.elixir
            if (reserve > 0 && e != null && focus != null && key != focus.key && !cardInBuild && e - cost < reserve) {
                reservedSkip = true
                continue
            }
            val after = withCopy(lines, key, c.troopId, c.trait, c.cost)
            var v = value(after, build, env, base - cost)
            v -= ELIXIR_VALUE * cost
            if (!build.found) v += 0.02 * cost
            if (c.troopId == CycleStrategy.ARCHER) v += 0.1
            val adj = c.troopId?.let { bias[it] } ?: 0.0
            v += adj
            val cont = existing != null && existing.need > 0
            options.add(Option(c, v, cont))
            scenarios.add(Scenario("купить карточку ${c.index + 1}" + (if (adj != 0.0) " (поправка по соперникам ${fmt(adj)})" else ""), v))
        }

        if (options.isEmpty()) {
            return if (reservedSkip) BuyDecision(null, Kind.SAVE, "берегу эликсир: он нужен, чтобы доделать улучшение из сборки", scenarios, abandoned)
            else if (abandonedSkip) BuyDecision(null, Kind.NONE, "карточка отброшена: улучшение маловероятно", scenarios, abandoned)
            else BuyDecision(null, Kind.NONE, "", scenarios, abandoned)
        }

        val best = options.maxByOrNull { it.value }!!
        if (best.value <= saveValue) {
            return BuyDecision(null, Kind.SAVE, "покупка не повышает шансы на улучшение — коплю эликсир", scenarios, abandoned)
        }
        val idx = best.card.index
        if (best.continues) {
            return BuyDecision(idx, Kind.CONTINUE, "продолжаю прокачку: докупаю копию, чтобы довести улучшение", scenarios, abandoned)
        }
        val focusMissing = focus != null && (visible[focus.key] ?: 0) == 0
        return if (focusMissing) BuyDecision(idx, Kind.ALTERNATIVE, "нужной карточки нет в магазине — беру альтернативу", scenarios, abandoned)
        else BuyDecision(idx, Kind.BUY, "лучший сценарий — купить карточку ${idx + 1}", scenarios, abandoned)
    }

    /**
     * Нужно ли сменить направление сборки. Только для сборки, найденной автоматически (одно свойство):
     * выбор пользователя бот не трогает. Меняем, если другое свойство заметно выгоднее по оценке сценариев.
     */
    fun pivot(lines: List<Line>, cards: List<ShopCard>, build: Build, env: Env): PivotDecision? {
        if (!build.found || build.traits.size != 1 || build.troops.isNotEmpty()) return null
        fun shopBonus(t: String) = 0.1 * cards.count { it.trait == t && it.affordable }
        val b = budget(env)
        val cur = build.traits.first()
        val now = value(lines, build, env, b) + shopBonus(cur)
        val candidates = HashSet<String>()
        for (l in lines) l.trait?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
        for (c in cards) c.trait?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
        candidates.remove(cur)
        var bestTrait: String? = null
        var bestValue = now + PIVOT_MARGIN
        for (t in candidates.sorted()) {
            val v = value(lines, Build(setOf(t)), env, b) + shopBonus(t)
            if (v > bestValue) { bestValue = v; bestTrait = t }
        }
        val t = bestTrait ?: return null
        return PivotDecision(Build(setOf(t)), now, bestValue)
    }

    /** Какого брошенного одиночку продать: сначала самого дорогого (больше вернётся эликсира). Сборку и стрелков не трогаем. */
    fun pickAbandonedSell(bench: List<BenchUnit?>, keys: List<String?>, abandoned: Set<String>, build: Build): Int? =
        bench.filterNotNull()
            .filter { u ->
                val k = keys.getOrNull(u.slot)
                k != null && k in abandoned && !CycleStrategy.inBuild(u.trait, u.troopId, build) && u.troopId != CycleStrategy.ARCHER
            }
            .sortedByDescending { it.cost ?: LocalBuyPlanner.DEFAULT_COST }
            .firstOrNull()?.slot

    fun describe(s: List<Scenario>): String =
        s.sortedByDescending { it.value }.take(3).joinToString("; ") { it.label + " " + fmt(it.value) }
}
