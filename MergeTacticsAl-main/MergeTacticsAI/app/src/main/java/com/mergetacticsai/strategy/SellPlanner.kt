package com.mergetacticsai.strategy

class BenchInfo(val sig: Long, val cost: Int?, val troopId: String? = null)

object SellPlanner {
    /**
     * Какого юнита со скамейки продать, когда она заполнена: сначала двухзвёздочных (правило пользователя), затем самого дешёвого.
     * Юниты, которые совпадают с карточкой-партнёром в магазине (зелёная стрелка), не продаём.
     */
    fun choose(bench: BooleanArray, info: List<BenchInfo?>, mergeSigs: List<Long>, twoStar: BooleanArray = BooleanArray(bench.size)): Int? {
        var best = -1
        var bestCost = Int.MAX_VALUE
        val anyTwoStar = bench.indices.any { bench[it] && twoStar.getOrElse(it) { false } }
        for (i in bench.indices) {
            if (!bench[i]) continue
            if (anyTwoStar && !twoStar.getOrElse(i) { false }) continue
            val inf = info.getOrNull(i)
            if (inf != null && mergeSigs.any { java.lang.Long.bitCount(it xor inf.sig) <= CardMemory.MAX_DIST }) continue
            val c = inf?.cost ?: LocalBuyPlanner.DEFAULT_COST
            if (c <= bestCost) { bestCost = c; best = i }
        }
        return if (best >= 0) best else null
    }
}
