package com.mergetacticsai.strategy

import com.mergetacticsai.map.CombatContext
import com.mergetacticsai.map.CombatModel
import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.HexMap
import com.mergetacticsai.map.PlacedUnit
import com.mergetacticsai.map.UnitStats
import com.mergetacticsai.map.Verdict
import com.mergetacticsai.odds.ShopMath
import com.mergetacticsai.odds.num

/**
 * Предмет (снаряжение), закреплённый за войском или свойством. Предметов в базе приложения нет,
 * поэтому список вводится вручную; power — во сколько раз предмет усиливает войско (предположение).
 */
data class ItemBoost(val id: String, val troopId: String? = null, val trait: String? = null, val power: Double = 1.2)

/** Расстановка обеих сторон и числа войск: по ним считается, насколько улучшение помогает против противника. */
data class MatchupInput(val layout: Map<HexCell, PlacedUnit>, val stats: Map<String, UnitStats>, val ctx: CombatContext)

/**
 * Всё, что влияет на ценность улучшения помимо самого войска.
 * lines — что у нас есть (скамейка и поле); traitTiers — пороги свойств из каталога;
 * ruler и modifiers — выбранные правитель и модификаторы; matchup — противник (null — не задан).
 */
data class UpgradeContext(
    val lines: List<Line>,
    val build: Build = Build(emptySet()),
    val traitTiers: Map<String, List<Int>> = emptyMap(),
    val names: (String) -> String = { it },
    val ruler: String = "",
    val modifiers: Set<String> = emptySet(),
    val items: List<ItemBoost> = emptyList(),
    val matchup: MatchupInput? = null,
)

/**
 * Оценка улучшения с учётом контекста. Каждый множитель показывает, во сколько раз контекст
 * повышает (больше 1) или понижает (меньше 1) ценность улучшения.
 */
data class ContextEval(
    /** Оценка самого войска без контекста (с поправкой на доход правителя и модификаторов). */
    val base: UpgradeEval,
    /** Активные и потенциальные черты войска. */
    val traitFactor: Double,
    /** Совпадение с текущей командой и связь с правителем. */
    val synergyFactor: Double,
    /** Как улучшение меняет исход боя против противника (1,0 — нет данных). */
    val matchupFactor: Double,
    /** Правитель и модификаторы: усиление звёзд. */
    val rulerFactor: Double,
    /** Предметы на этом войске или его свойстве. */
    val itemFactor: Double,
    /** Сколько раундов улучшение ещё будет работать после того, как его удастся собрать. */
    val timeFactor: Double,
    /** Произведение всех множителей. */
    val total: Double,
    /** Эликсир, который возвращают правитель и модификаторы за слияние или бесплатную покупку. */
    val refund: Double,
    /** Ожидаемая польза с учётом контекста. */
    val value: Double,
    /** Польза на один потраченный эликсир: по ней сортируются войска. */
    val score: Double,
    val reasons: List<String>,
)

/**
 * Контекстная оценка прокачки. Чистый Kotlin, без Android.
 * Берёт [UpgradeEvaluator] (звёздность, копии, пул, шанс, цена, сила) и добавляет:
 * активные и потенциальные черты, синергию с командой, соответствие противнику,
 * предметы и правителя, оставшиеся раунды.
 *
 * Все множители — предположения с мягкими границами: проверенных чисел для них в базе нет.
 */
object ContextEvaluator {
    const val MIN_TIME = 0.2

    /** Три звезды при «Фантазии трёх звёзд»: HP ×2 и урон ×1,5 дают в среднем около √3. */
    private val FANTASY = Math.sqrt(3.0)
    private const val DOMINATION = 1.2
    private const val STAR_ADVANTAGE = 1.1

    private val MODELED_MODIFIERS = setOf("three_star_fantasy", "domination", "star_advantage", "first_free", "merge_bonus", "trait_gift")
    private val MODELED_RULERS = setOf("spirit_empress", "elixir_loong", "goblin_queen", "tomb_queen")

    private fun tiersOf(ctx: UpgradeContext, trait: String): List<Int> =
        ctx.traitTiers[trait]?.takeIf { it.isNotEmpty() } ?: TroopPolicy.thresholds(trait)

    /** Сколько разных войск каждого свойства у нас есть; Королева гоблинов и Королева гробницы добавляют по одному. */
    fun traitCounts(ctx: UpgradeContext): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (l in ctx.lines) {
            val t = l.trait?.takeIf { it.isNotEmpty() } ?: continue
            out[t] = (out[t] ?: 0) + 1
        }
        if (ctx.ruler == "goblin_queen") out["goblin"] = (out["goblin"] ?: 0) + 1
        if (ctx.ruler == "tomb_queen") out["undead"] = (out["undead"] ?: 0) + 1
        return out
    }

    /** Сколько свойств сейчас активны (достигли первого порога). */
    fun activeTraits(ctx: UpgradeContext): Int = traitCounts(ctx).count { e -> tiersOf(ctx, e.key).any { e.value >= it } }

    /** Что выбрано, но в оценку не вошло. */
    fun unmodeled(ctx: UpgradeContext): List<String> {
        val out = ArrayList<String>()
        if (ctx.ruler.isNotEmpty() && ctx.ruler !in MODELED_RULERS) out.add("правитель: " + ctx.names(ctx.ruler))
        for (m in ctx.modifiers.filter { it !in MODELED_MODIFIERS }.sorted()) out.add("модификатор: " + ctx.names(m))
        return out
    }

    private fun traitFactor(ctx: UpgradeContext, counts: Map<String, Int>, trait: String?, reasons: MutableList<String>): Double {
        if (trait.isNullOrEmpty()) return 1.0
        val c = counts[trait] ?: 0
        val tiers = tiersOf(ctx, trait)
        val active = tiers.count { c >= it }
        val next = tiers.firstOrNull { it > c }
        val missing = if (next == null) null else next - c
        var f = 1.0 + 0.2 * active
        if (missing != null) {
            if (missing == 1) {
                f += 0.2
            } else if (missing == 2) {
                f += 0.1
            } else if (active == 0) {
                f = 0.9
            }
        }
        val name = ctx.names(trait)
        val tail = if (missing != null) ", до следующего порога ещё $missing" else ", максимум"
        reasons.add(if (active > 0) "черта «$name» активна (уровень $active)$tail" else "черта «$name» пока не активна$tail")
        return f.coerceIn(0.8, 1.8)
    }

    private fun synergyFactor(ctx: UpgradeContext, trait: String?, reasons: MutableList<String>): Double {
        val n = ctx.lines.size
        val same = if (trait.isNullOrEmpty()) 0 else ctx.lines.count { it.trait == trait }
        val share = if (n <= 1) 0.0 else (same - 1).coerceAtLeast(0).toDouble() / (n - 1)
        val tie = (ctx.ruler == "goblin_queen" && trait == "goblin") || (ctx.ruler == "tomb_queen" && trait == "undead")
        if (share > 0.0) reasons.add("в команде ещё ${same - 1} с той же чертой")
        if (tie) reasons.add("правитель усиливает эту черту")
        val f = 1.0 + 0.5 * share + (if (tie) 0.1 else 0.0)
        return f.coerceIn(1.0, 1.6)
    }

    private fun matchupFactor(ctx: UpgradeContext, troopId: String?, reasons: MutableList<String>): Double {
        val m = ctx.matchup ?: return 1.0
        val id = troopId ?: return 1.0
        fun upgradable(c: HexCell, u: PlacedUnit) = HexMap.isOwn(c) && u.troopId == id && u.stars < 3
        if (m.layout.none { e -> upgradable(e.key, e.value) }) return 1.0
        val before = CombatModel.evaluate(m.layout, m.stats, m.ctx)
        if (before.verdict == Verdict.UNKNOWN) return 1.0
        val upgraded = m.layout.mapValues { e -> if (upgradable(e.key, e.value)) e.value.copy(stars = e.value.stars + 1) else e.value }
        val after = CombatModel.evaluate(upgraded, m.stats, m.ctx)
        val delta = (after.margin - before.margin).toDouble()
        reasons.add("против противника запас боя ${if (delta >= 0) "+" else ""}${num(delta)}")
        return (1.0 + 2.0 * delta).coerceIn(0.7, 1.6)
    }

    /** Множитель силы от правителя и модификаторов и возврат эликсира. Возвращает пару (множитель, возврат). */
    private fun rulerEffects(ctx: UpgradeContext, base: UpgradeEval, reasons: MutableList<String>): Pair<Double, Double> {
        var mult = 1.0
        var refund = 0.0
        val mods = ctx.modifiers
        if (base.targetStar >= 3 && "three_star_fantasy" in mods) { mult *= FANTASY; reasons.add("«Фантазия трёх звёзд»: 3★ заметно сильнее") }
        if (base.targetStar >= 3 && "domination" in mods) { mult *= DOMINATION; reasons.add("«Доминирование»: бонус за 3★") }
        if ("star_advantage" in mods) { mult *= STAR_ADVANTAGE; reasons.add("«Звёздное преимущество»: больше звёзд — сильнее") }
        if (ctx.ruler == "spirit_empress") { refund += 1.0; reasons.add("Императрица духов: слияние даёт +1 эликсир") }
        if ("merge_bonus" in mods) { refund += 1.0; reasons.add("«Бонус слияния»: слияние даёт ещё одно войско") }
        if ("first_free" in mods && base.missing > 0) { refund += base.cost.toDouble(); reasons.add("«Первый бесплатно»: одна копия за ход даром") }
        return mult to refund
    }

    private fun itemFactor(ctx: UpgradeContext, line: Line, reasons: MutableList<String>): Double {
        var f = 1.0
        for (i in ctx.items) {
            val byTroop = i.troopId != null && i.troopId == line.troopId
            val byTrait = i.trait != null && i.trait == line.trait
            if (byTroop || byTrait) { f *= i.power; reasons.add("предмет «${i.id}» усиливает это войско") }
        }
        return f.coerceIn(0.5, 2.0)
    }

    /**
     * Сколько улучшение будет приносить пользу до конца игры. Оцениваем, за сколько раундов его удастся собрать
     * (доход раунда идёт на платные обновления магазина), и берём долю оставшихся раундов после этого.
     */
    private fun timeFactor(base: UpgradeEval, env: Env, rules: UpgradeRules, reasons: MutableList<String>): Double {
        val rounds = env.roundsLeft.coerceAtLeast(1)
        when (base.verdict) {
            UpgradeVerdict.READY -> return 1.0
            UpgradeVerdict.DONE -> return 1.0
            UpgradeVerdict.IMPOSSIBLE -> return MIN_TIME
            else -> Unit
        }
        val paidPerRound = if (rules.refreshCost <= 0) rules.maxRefreshPerRound
        else minOf(rules.maxRefreshPerRound, (env.income * rules.budgetShare / rules.refreshCost).toInt())
        val views = ShopMath.expectedViews(base.slotChance, env.shopSlots, base.missing)
        val eta = if (views.isInfinite() || views.isNaN()) rounds.toDouble() else views / (1.0 + paidPerRound.coerceAtLeast(0))
        val useful = (rounds - eta).coerceAtLeast(0.0)
        reasons.add("раундов осталось $rounds, дособирать ожидаемо ${num(eta)}")
        return (useful / rounds).coerceIn(MIN_TIME, 1.0)
    }

    fun evaluate(line: Line, name: String, ctx: UpgradeContext, env: Env, rules: UpgradeRules = UpgradeRules()): ContextEval {
        val reasons = ArrayList<String>()
        val counts = traitCounts(ctx)
        val trait = line.trait
        val bonusIncome = (if (ctx.ruler == "elixir_loong") 1 else 0) + (if ("trait_gift" in ctx.modifiers) activeTraits(ctx) else 0)
        if (bonusIncome > 0) reasons.add("доход раунда +$bonusIncome (правитель/модификатор)")
        val env2 = env.copy(income = env.income + bonusIncome)
        val holding = TroopHolding(line.key, name, line.price, line.ones, line.twos, 0, CycleStrategy.inBuild(line.trait, line.troopId, ctx.build))
        val base = UpgradeEvaluator.evaluate(holding, env2, rules)

        val tf = traitFactor(ctx, counts, trait, reasons)
        val sf = synergyFactor(ctx, trait, reasons)
        val mf = matchupFactor(ctx, line.troopId, reasons)
        val (rf, refund) = rulerEffects(ctx, base, reasons)
        val itf = itemFactor(ctx, line, reasons)
        val timef = timeFactor(base, env2, rules, reasons)

        val total = (tf * sf * mf * rf * itf * timef).coerceIn(0.1, 8.0)
        val spend = base.expectedElixir
        val denom = if (spend.isInfinite() || spend.isNaN()) Double.POSITIVE_INFINITY else maxOf(1.0, spend - refund)
        val dead = base.verdict == UpgradeVerdict.IMPOSSIBLE || base.verdict == UpgradeVerdict.DONE
        val score = if (dead || denom.isInfinite()) 0.0 else base.pFinish * base.powerGain * total / denom
        return ContextEval(base, tf, sf, mf, rf, itf, timef, total, refund, base.expectedGain * total, score, reasons)
    }

    private fun group(v: UpgradeVerdict): Int = when (v) {
        UpgradeVerdict.READY -> 0
        UpgradeVerdict.PUSH, UpgradeVerdict.WAIT -> 1
        UpgradeVerdict.SKIP -> 2
        UpgradeVerdict.IMPOSSIBLE -> 3
        UpgradeVerdict.DONE -> 4
    }

    /** Все войска из контекста: можно слить → лучшая польза на эликсир (прокачивать и ждать вместе) → отказ → невозможное. */
    fun rank(ctx: UpgradeContext, env: Env, rules: UpgradeRules = UpgradeRules(), nameOf: (Line) -> String = { it.key }): List<ContextEval> =
        ctx.lines.map { evaluate(it, nameOf(it), ctx, env, rules) }
            .sortedWith(compareBy<ContextEval> { group(it.base.verdict) }.thenByDescending { it.score })

    /** Короткая строка для лога: оценка войска, общий множитель и две главные причины. */
    fun describe(e: ContextEval): String =
        UpgradeEvaluator.describe(e.base) + "; контекст ×" + num(e.total) +
            (if (e.reasons.isEmpty()) "" else " (" + e.reasons.take(2).joinToString(", ") + ")")
}
