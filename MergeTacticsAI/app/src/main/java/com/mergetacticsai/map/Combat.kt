package com.mergetacticsai.map

/**
 * Базовые числа войска на 1★. Вводятся вручную: проверенных значений в базе нет.
 * hp — здоровье, damage — урон за удар, attackSpeed — ударов в секунду.
 */
data class UnitStats(val hp: Float, val damage: Float, val attackSpeed: Float) {
    val dps get() = damage * attackSpeed
    val valid get() = hp > 0f && damage > 0f && attackSpeed > 0f
}

/**
 * Что известно о бое помимо расстановки.
 * [traitOf] — свойство каждого войска (id войска → id свойства).
 * [starFactor] — во сколько раз растут HP и урон за каждую звезду; 1.0 — «не задано», звёзды не усиливают.
 */
data class CombatContext(
    val ruler: String = "",
    val modifiers: Set<String> = emptySet(),
    val traitOf: Map<String, String> = emptyMap(),
    val starFactor: Float = 1f,
)

/**
 * Числа из описаний свойств, правителей и модификаторов (assets/traits.json, rulers.json, modifiers.json).
 * Моделируются только эффекты с числами и без случайности; остальное попадает в [CombatResult.notes].
 */
object Effects {
    /** Пороги свойств и бонусы по порогам (доли). */
    private val NOBLE = listOf(0.15f, 0.30f, 0.60f)      // урон вверх и получаемый урон вниз
    private val FOREST = listOf(0.15f, 0.30f, 0.60f)     // скорость ударов
    private val CLAN_HEAL = listOf(0.30f, 0.50f, 1.00f)  // лечение при HP < 50%, один раз за раунд
    private val BOSS_HP = listOf(0.25f, 0.50f, 1.00f, 3.00f)

    private val MODELED_TRAITS = setOf("noble", "forest", "clan", "boss")
    private val MODELED_RULERS = setOf("goblin_queen", "tomb_queen")
    private val MODELED_MODIFIERS = setOf("three_star_fantasy")

    /** Сколько разных войск каждого свойства стоит на поле (дубликаты считаются один раз). */
    fun traitCounts(units: Collection<PlacedUnit>, ctx: CombatContext): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (id in units.map { it.troopId }.filter { it != PlacedUnit.UNKNOWN }.toSet()) {
            val t = ctx.traitOf[id]?.takeIf { it.isNotEmpty() } ?: continue
            out[t] = (out[t] ?: 0) + 1
        }
        // Королева гоблинов присоединяется к Гоблинам, Королева гробницы считается Нежитью.
        if (ctx.ruler == "goblin_queen") out["goblin"] = (out["goblin"] ?: 0) + 1
        if (ctx.ruler == "tomb_queen") out["undead"] = (out["undead"] ?: 0) + 1
        return out
    }

    /** Номер достигнутого порога (0 — не достигнут). Пороги 2/4/6, у босса 1/2/3/4. */
    fun tier(trait: String, count: Int): Int {
        val th = if (trait == "boss") listOf(1, 2, 3, 4) else listOf(2, 4, 6)
        return th.count { count >= it }
    }

    private fun pick(values: List<Float>, tier: Int) = if (tier <= 0) 0f else values[(tier - 1).coerceAtMost(values.size - 1)]

    /** Бонусы одной стороны. Все значения — доли (0.15 = +15%). */
    data class SideBonus(
        val damage: Float = 0f,
        val taken: Float = 0f,      // снижение получаемого урона
        val attackSpeed: Float = 0f,
        val healShare: Float = 0f,  // доля максимального HP, которую вернёт «Клан»
        val bossHp: Float = 0f,     // прибавка HP войск-боссов
    )

    fun sideBonus(units: Collection<PlacedUnit>, ctx: CombatContext): SideBonus {
        val c = traitCounts(units, ctx)
        return SideBonus(
            damage = pick(NOBLE, tier("noble", c["noble"] ?: 0)),
            taken = pick(NOBLE, tier("noble", c["noble"] ?: 0)),
            attackSpeed = pick(FOREST, tier("forest", c["forest"] ?: 0)),
            healShare = pick(CLAN_HEAL, tier("clan", c["clan"] ?: 0)),
            bossHp = pick(BOSS_HP, tier("boss", c["boss"] ?: 0)),
        )
    }

    /** Множители «Фантазии трёх звёзд»: у 3★ HP ×2, урон ×1.5. */
    fun starMods(stars: Int, ctx: CombatContext): Pair<Float, Float> =
        if ("three_star_fantasy" in ctx.modifiers && stars >= 3) 2f to 1.5f else 1f to 1f

    /** Выбранные правитель, модификаторы и свойства, эффект которых не учтён в числах. */
    fun unmodeled(units: Collection<PlacedUnit>, ctx: CombatContext, names: (String) -> String = { it }): List<String> {
        val out = mutableListOf<String>()
        if (ctx.ruler.isNotEmpty() && ctx.ruler !in MODELED_RULERS) out += "правитель: " + names(ctx.ruler)
        ctx.modifiers.filter { it !in MODELED_MODIFIERS }.sorted().forEach { out += "модификатор: " + names(it) }
        val counts = traitCounts(units, ctx)
        counts.filter { (t, n) -> t !in MODELED_TRAITS && tier(t, n) > 0 }.keys.sorted().forEach { out += "свойство: " + names(it) }
        return out
    }
}

enum class Verdict(val title: String) { WIN("победа"), LOSE("поражение"), DRAW("ничья"), UNKNOWN("нет данных") }

data class CombatResult(
    val verdict: Verdict,
    /** Доля HP, оставшаяся у вашей стороны / у противника к концу боя (0..1). */
    val ownLeft: Float,
    val enemyLeft: Float,
    val seconds: Float,
    /** Войска без введённых числа HP, урона и скорости ударов: они в расчёт не вошли. */
    val missing: Set<String>,
    val notes: List<String>,
    /** Ваши юниты на опасных клетках (проклятая, бездна, яд): их потери здесь не считаются. */
    val atRisk: Int,
) {
    /** От −1 (полное поражение) до +1 (чистая победа). Для сравнения расстановок. */
    val margin get() = ownLeft - enemyLeft
}

/**
 * Оценка боя по начальной расстановке. Это не точная симуляция, а грубая прикидка:
 *  - каждый юнит бьёт, когда дошёл до противника ([ContactModel]; без скоростей — сразу);
 *  - урон идёт в ближайшего к стороне живого юнита противника, переполнение переходит на следующего;
 *  - считаются только числовые бонусы (см. [Effects]); способности и случайные эффекты не считаются;
 *  - «Нежить» (усиление за павших), щиты, отбрасывание, оглушение и невидимость не учтены.
 */
object CombatModel {
    private const val DT = 0.1f
    private const val MAX_T = 60f

    private class F(val cell: HexCell, val own: Boolean, val maxHp: Float, val dps: Float, val engage: Float) {
        var hp = maxHp
        val alive get() = hp > 0.0001f
    }

    fun evaluate(
        layout: Map<HexCell, PlacedUnit>,
        stats: Map<String, UnitStats>,
        ctx: CombatContext,
        radii: Map<String, Int> = emptyMap(),
        speeds: Map<String, Float> = emptyMap(),
        marks: Map<HexCell, CellMark> = emptyMap(),
        names: (String) -> String = { it },
    ): CombatResult {
        val ownUnits = layout.filterKeys { HexMap.isOwn(it) }
        val enemyUnits = layout.filterKeys { !HexMap.isOwn(it) }
        val missing = layout.values.map { it.troopId }
            .filter { it == PlacedUnit.UNKNOWN || stats[it]?.valid != true }
            .filter { it != PlacedUnit.UNKNOWN }.toSet()
        val unknownCount = layout.values.count { it.isUnknown }
        val notes = mutableListOf<String>()
        if (unknownCount > 0) notes += "Неопознанных юнитов: $unknownCount — в расчёт не вошли."
        if (missing.isNotEmpty()) notes += "Нет HP/урона/скорости ударов: " + missing.sorted().joinToString { names(it) }
        if (ctx.starFactor == 1f && layout.values.any { it.stars > 1 }) notes += "Множитель за звезду не задан: звёзды не усиливают."
        val un = Effects.unmodeled(layout.values, ctx, names)
        if (un.isNotEmpty()) notes += "Не учтено в числах: " + un.joinToString("; ")

        val contact = ContactModel.report(layout, radii, speeds)
        val ownBonus = Effects.sideBonus(ownUnits.values, ctx)
        val enemyBonus = Effects.sideBonus(enemyUnits.values, ctx)

        fun build(units: Map<HexCell, PlacedUnit>, own: Boolean, b: Effects.SideBonus): List<F> =
            units.mapNotNull { (cell, u) ->
                val s = stats[u.troopId]?.takeIf { it.valid } ?: return@mapNotNull null
                val star = Math.pow(ctx.starFactor.toDouble(), (u.stars - 1).toDouble()).toFloat()
                val (hpMod, dmgMod) = Effects.starMods(u.stars, ctx)
                val boss = if (ctx.traitOf[u.troopId] == "boss") b.bossHp else 0f
                val hp = s.hp * star * hpMod * (1f + boss) * (1f + b.healShare)
                val dmg = s.damage * star * dmgMod * (1f + b.damage)
                val aps = s.attackSpeed * (1f + b.attackSpeed)
                F(cell, own, hp, dmg * aps, contact.byUnit[cell]?.seconds ?: 0f)
            }

        val own = build(ownUnits, true, ownBonus)
        val enemy = build(enemyUnits, false, enemyBonus)
        val atRisk = ownUnits.keys.count { marks[it]?.dangerous == true }
        if (atRisk > 0) notes += "Ваших юнитов на опасных клетках: $atRisk — их потери не считались."
        if (own.isEmpty() || enemy.isEmpty()) {
            return CombatResult(Verdict.UNKNOWN, 1f, 1f, 0f, missing, notes, atRisk)
        }

        // Мишени идут от линии столкновения: у вас — от верхнего ряда вниз, у противника — от нижнего вверх.
        val ownOrder = own.sortedWith(compareBy({ it.cell.row }, { it.cell.col }))
        val enemyOrder = enemy.sortedWith(compareBy({ -it.cell.row }, { it.cell.col }))
        val ownTotal = own.sumOf { it.maxHp.toDouble() }.toFloat()
        val enemyTotal = enemy.sumOf { it.maxHp.toDouble() }.toFloat()
        val ownTaken = 1f - ownBonus.taken
        val enemyTaken = 1f - enemyBonus.taken

        fun hit(order: List<F>, damage: Float) {
            var rest = damage
            for (t in order) {
                if (rest <= 0f) break
                if (!t.alive) continue
                val d = minOf(t.hp, rest)
                t.hp -= d; rest -= d
            }
        }

        var t = 0f
        while (t < MAX_T && own.any { it.alive } && enemy.any { it.alive }) {
            val toEnemy = own.filter { it.alive && it.engage <= t }.sumOf { it.dps.toDouble() }.toFloat() * DT * enemyTaken
            val toOwn = enemy.filter { it.alive && it.engage <= t }.sumOf { it.dps.toDouble() }.toFloat() * DT * ownTaken
            hit(enemyOrder, toEnemy)
            hit(ownOrder, toOwn)
            t += DT
        }
        val ownLeft = own.sumOf { it.hp.coerceAtLeast(0f).toDouble() }.toFloat() / ownTotal
        val enemyLeft = enemy.sumOf { it.hp.coerceAtLeast(0f).toDouble() }.toFloat() / enemyTotal
        val verdict = when {
            ownLeft - enemyLeft > 0.01f -> Verdict.WIN
            enemyLeft - ownLeft > 0.01f -> Verdict.LOSE
            else -> Verdict.DRAW
        }
        return CombatResult(verdict, ownLeft, enemyLeft, t, missing, notes, atRisk)
    }
}
