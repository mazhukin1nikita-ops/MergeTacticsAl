package com.mergetacticsai.odds

/** Точные формулы для модели «карточки в магазине независимы» (и для одного магазина при конечном пуле). */
object ShopMath {
    /** Вероятность, что выбранная карточка имеет цену [cost]. */
    fun tierProb(p: OddsParams, tiers: Map<Int, Int>, cost: Int): Double {
        if ((tiers[cost] ?: 0) <= 0) return 0.0
        fun w(c: Int): Double {
            val n = tiers[c] ?: 0
            if (n <= 0) return 0.0
            return if (p.weights.isEmpty()) n.toDouble() else (p.weights[c] ?: 0.0).coerceAtLeast(0.0)
        }
        val total = tiers.keys.sumOf { w(it) }
        return if (total <= 0.0) 0.0 else w(cost) / total
    }

    fun remainingTarget(p: OddsParams): Int =
        (p.copiesPerTroop - p.startCopies - p.takenByOthers).coerceAtLeast(0)

    fun remainingTier(p: OddsParams, troopsInTier: Int): Int =
        (troopsInTier * p.copiesPerTroop - p.startCopies - p.takenByOthers).coerceAtLeast(0)

    /** Шанс, что один слот магазина покажет искомое войско. */
    fun slotChance(p: OddsParams, tiers: Map<Int, Int>, cost: Int): Double {
        val n = tiers[cost] ?: 0
        if (n <= 0) return 0.0
        val pt = tierProb(p, tiers, cost)
        val base = if (p.copiesPerTroop <= 0) {
            pt / n
        } else {
            val r = remainingTarget(p)
            val t = remainingTier(p, n)
            if (r <= 0 || t <= 0) 0.0 else pt * r.toDouble() / t
        }
        return (base * p.qScale).coerceIn(0.0, 1.0)
    }

    fun binomPmf(n: Int, k: Int, q: Double): Double {
        if (k < 0 || k > n) return 0.0
        if (q <= 0.0) return if (k == 0) 1.0 else 0.0
        if (q >= 1.0) return if (k == n) 1.0 else 0.0
        var pmf = Math.pow(1 - q, n.toDouble())
        for (i in 0 until k) pmf *= (n - i).toDouble() / (i + 1) * q / (1 - q)
        return pmf
    }

    /** P(в [n] независимых слотах выпадет не меньше [k] копий). */
    fun pAtLeast(n: Int, k: Int, q: Double): Double {
        if (k <= 0) return 1.0
        if (n < k || q <= 0.0) return 0.0
        if (q >= 1.0) return 1.0
        var pmf = Math.pow(1 - q, n.toDouble())
        var below = 0.0
        for (i in 0 until k) {
            below += pmf
            pmf *= (n - i).toDouble() / (i + 1) * q / (1 - q)
        }
        return (1.0 - below).coerceIn(0.0, 1.0)
    }

    /** Шанс увидеть войско хотя бы раз за [shops] просмотров магазина. */
    fun pSeeInShops(q: Double, slots: Int, shops: Int): Double = pAtLeast(slots * shops, 1, q)

    /** Конечный пул, один магазин без повторов: R копий искомого из T карточек цены. */
    fun pSeeOneShopFinite(r: Int, t: Int, slots: Int): Double {
        if (r <= 0 || t <= 0) return 0.0
        if (slots >= t) return 1.0
        var none = 1.0
        for (i in 0 until slots) none *= (t - r - i).toDouble().coerceAtLeast(0.0) / (t - i)
        return (1.0 - none).coerceIn(0.0, 1.0)
    }

    /** Точное среднее число просмотров магазина, чтобы набрать [k] копий (покупаем всё нужное). */
    fun expectedViews(q: Double, slots: Int, k: Int): Double {
        if (k <= 0) return 0.0
        if (q <= 0.0) return Double.POSITIVE_INFINITY
        val pm = DoubleArray(slots + 1) { binomPmf(slots, it, q) }
        if (pm[0] >= 1.0) return Double.POSITIVE_INFINITY
        val f = DoubleArray(k + 1)
        for (j in 1..k) {
            var s = 1.0
            for (x in 1..slots) s += pm[x] * f[maxOf(0, j - x)]
            f[j] = s / (1.0 - pm[0])
        }
        return f[k]
    }

    /** Ожидаемый эликсир: цена копий плюс оплаченные обновления (первый просмотр бесплатный). */
    fun expectedElixir(q: Double, slots: Int, k: Int, cost: Int, refreshCost: Int): Double {
        if (k <= 0) return 0.0
        val v = expectedViews(q, slots, k)
        return if (v.isInfinite()) v else k.toDouble() * cost + refreshCost.toDouble() * (v - 1.0).coerceAtLeast(0.0)
    }

    /** Сколько одно-звёздочных копий нужно для звёздности [stars]. */
    fun copiesForStar(stars: Int, perMerge: Int): Int {
        var n = 1
        repeat((stars - 1).coerceAtLeast(0)) { n *= perMerge.coerceAtLeast(2) }
        return n
    }

    /** Сколько мест нужно, чтобы собрать звёздность [stars], пока покупаете очередную копию. */
    fun slotsNeeded(stars: Int, perMerge: Int): Int = (perMerge.coerceAtLeast(2) - 1) * (stars - 1).coerceAtLeast(0) + 1
}
