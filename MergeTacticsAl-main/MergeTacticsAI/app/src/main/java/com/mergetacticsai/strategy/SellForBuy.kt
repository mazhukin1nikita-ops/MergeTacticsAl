package com.mergetacticsai.strategy

import com.mergetacticsai.odds.num

enum class Where { BENCH, BOARD }

/**
 * Своё войско, которое можно продать. index — слот скамейки (BENCH) или индекс клетки поля (BOARD).
 * cost == null — цена не известна, такого войска не продаём: без цены нельзя посчитать возврат.
 */
data class OwnedUnit(
    val where: Where, val index: Int, val troopId: String?, val trait: String?, val cost: Int?, val stars: Int,
    val sig: Long? = null, val name: String = "",
) {
    /** Ключ «это одно и то же войско» для подсчёта разных войск свойства и пар для слияния. */
    val key: String? get() = troopId ?: sig?.let { "sig:$it" }
}

/**
 * Допущения оценки. Правило возврата при продаже проверено (Supercell), всё остальное — предположения:
 * проверенных чисел силы войск и звёзд в базе приложения нет, поэтому пороги сделаны осторожными.
 */
data class SwapRules(
    val power: StarPower = StarPower(),
    /** Сила 1★ войска = 1 + шаг × (цена − 1). Совпадает с оценкой прокачки. */
    val costPowerStep: Double = 0.3,
    /** Во сколько раз войско вне сборки ценится ниже войска сборки. */
    val offBuildFactor: Double = 0.5,
    /** Войско на скамейке пока не воюет: ценится ниже выставленного. */
    val benchFactor: Double = 0.7,
    /** Добавка к потере, если продаём стрелка: их держим, чтобы соперники не собрали 3★. */
    val archerKeep: Double = 0.3,
    /** Прибавка/потеря, когда покупка или продажа меняет число войск свойства на пороге (2, 4, 6 или 1–4 у боссов). */
    val traitStep: Double = 0.5,
    /** Во сколько раз ценнее карточка, у которой есть пара для слияния. */
    val mergeBonus: Double = 1.5,
    /** Чистый выигрыш (ценность покупки минус ценность проданного) не меньше этого. */
    val minNet: Double = 0.4,
    /** Ценность покупки минимум во столько раз выше потери: защита от пустых перепродаж. */
    val minRatio: Double = 1.3,
)

/** Предложение «продать X → купить Y» с расчётом. */
data class SwapOffer(
    val card: ShopCard,
    val victim: OwnedUnit,
    val refund: Int,
    val elixirBefore: Int,
    val elixirAfter: Int,
    val gain: Double,
    val loss: Double,
    val net: Double,
    val reason: String,
)

/**
 * Продажа ради покупки. Чистый Kotlin, без Android.
 *
 * Сопоставляет поле (скамейка + выставленные войска), магазин и эликсир и считает, выгодно ли продать своё войско,
 * чтобы купить более ценное войско основной сборки: не хватает эликсира (продажа вернёт часть цены)
 * или нет места на скамейке. Продаём только тех, кто не в сборке и не нужен для слияния.
 *
 * Пример: на скамейке Лучница за 2, в магазине ПЕККА за 5 из сборки, эликсира 4. Продажа Лучницы вернёт 1
 * (цена минус 1), станет 5 — хватает на Пекку. Ценность Пекки выше потерянной ценности Лучницы — продаём.
 */
object SellForBuy {
    /**
     * Сколько эликсира вернёт продажа: цена войска минус 1; у слитого — суммарная цена слитых копий минус 1.
     * Источник: Supercell blog/support (в базе приложения отмечено как проверено).
     */
    fun refund(cost: Int, stars: Int, perMerge: Int = 2): Int {
        var copies = 1
        repeat((stars - 1).coerceIn(0, 2)) { copies *= perMerge.coerceAtLeast(2) }
        return (cost * copies - 1).coerceAtLeast(0)
    }

    fun unitValue(cost: Int, stars: Int, r: SwapRules): Double =
        (1.0 + r.costPowerStep * (cost - 1).coerceAtLeast(0)) * r.power.of(stars.coerceIn(1, 3))

    private fun close(a: Long, b: Long) = java.lang.Long.bitCount(a xor b) <= CardMemory.MAX_DIST

    private fun sameUnit(a: OwnedUnit, b: OwnedUnit): Boolean {
        val ia = a.troopId; val ib = b.troopId
        if (ia != null && ib != null) return ia == ib
        val sa = a.sig; val sb = b.sig
        return sa != null && sb != null && close(sa, sb)
    }

    /** У войска есть пара для слияния: такая же копия той же звёздности или карточка со стрелкой в магазине. */
    private fun hasPartner(v: OwnedUnit, owned: List<OwnedUnit>, arrowSigs: List<Long>): Boolean {
        val s = v.sig
        if (s != null && arrowSigs.any { close(it, s) }) return true
        return owned.any { o -> o !== v && o.stars == v.stars && sameUnit(o, v) }
    }

    /** Сколько разных войск каждого свойства у нас есть. */
    private fun traitCounts(owned: List<OwnedUnit>): Map<String, Int> {
        val seen = HashMap<String, MutableSet<String>>()
        owned.forEachIndexed { i, u ->
            val t = u.trait?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
            seen.getOrPut(t) { HashSet() }.add(u.key ?: "slot:${u.where}:$i")
        }
        return seen.mapValues { it.value.size }
    }

    fun choose(
        elixir: Int?,
        cards: List<ShopCard>,
        owned: List<OwnedUnit>,
        benchHasSpace: Boolean,
        build: Build,
        arrowSigs: List<Long> = emptyList(),
        names: (String?) -> String = { it ?: "войско" },
        rules: SwapRules = SwapRules(),
        thresholds: (String) -> List<Int> = { t -> TroopPolicy.thresholds(t) },
    ): SwapOffer? {
        val e = elixir ?: return null
        if (!build.found) return null
        val counts = traitCounts(owned)
        var best: SwapOffer? = null

        for (card in cards) {
            val cost = card.cost ?: continue
            if (!card.allowed) continue
            // Покупать можно только войско из основной сборки.
            if (!CycleStrategy.inBuild(card.trait, card.troopId, build)) continue
            // Карточку и так можно купить: продавать нечего.
            val canBuy = cost <= e && (benchHasSpace || card.arrow)
            if (canBuy) continue

            var gain = unitValue(cost, 1, rules)
            if (card.arrow) gain *= rules.mergeBonus
            val ctrait = card.trait?.takeIf { it.isNotEmpty() }
            if (ctrait != null && !card.arrow) {
                val isNew = card.troopId == null || owned.none { it.troopId == card.troopId }
                val next = (counts[ctrait] ?: 0) + (if (isNew) 1 else 0)
                if (isNew && next in thresholds(ctrait)) gain += rules.traitStep
            }

            for (v in owned) {
                val vc = v.cost ?: continue
                if (v.troopId != null && v.troopId == card.troopId) continue
                if (CycleStrategy.inBuild(v.trait, v.troopId, build)) continue
                if (hasPartner(v, owned, arrowSigs)) continue
                // Нужно место на скамейке: продажа с поля его не освобождает.
                if (!benchHasSpace && v.where != Where.BENCH) continue
                val refund = refund(vc, v.stars)
                val after = e + refund
                if (after < cost) continue

                var loss = unitValue(vc, v.stars, rules) * rules.offBuildFactor * (if (v.where == Where.BENCH) rules.benchFactor else 1.0)
                val vtrait = v.trait?.takeIf { it.isNotEmpty() }
                if (vtrait != null) {
                    val lastCopy = owned.none { o -> o !== v && sameUnit(o, v) }
                    if (lastCopy && (counts[vtrait] ?: 0) in thresholds(vtrait)) loss += rules.traitStep
                }
                if (v.troopId == CycleStrategy.ARCHER) loss += rules.archerKeep

                val net = gain - loss
                if (net < rules.minNet || gain < rules.minRatio * loss) continue
                val offer = SwapOffer(card, v, refund, e, after, gain, loss, net, describe(card, v, cost, refund, e, after, gain, loss, names))
                val cur = best
                if (cur == null || offer.net > cur.net + 1e-9 ||
                    (Math.abs(offer.net - cur.net) <= 1e-9 && (v.cost ?: 0) < (cur.victim.cost ?: 0))
                ) best = offer
            }
        }
        return best
    }

    private fun describe(
        card: ShopCard, v: OwnedUnit, cost: Int, refund: Int, before: Int, after: Int,
        gain: Double, loss: Double, names: (String?) -> String,
    ): String {
        val vn = v.name.ifEmpty { names(v.troopId) }
        val cn = names(card.troopId)
        val stars = if (v.stars > 1) " ${v.stars}★" else ""
        val why = if (!card.affordable || cost > before) "эликсира $before, а нужно $cost" else "на скамейке нет места"
        return "Продать «$vn»$stars (+$refund эликсира: $before → $after) → купить «$cn» ($cost) из сборки: $why. " +
            "Ценность покупки ${num(gain)} против потери ${num(loss)}, чистый выигрыш ${num(gain - loss)}"
    }
}
