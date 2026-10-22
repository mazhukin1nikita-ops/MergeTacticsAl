package com.mergetacticsai.strategy

/** Запоминает купленные карточки по «отпечатку» портрета, чтобы докупать пары и объединять быстрее. */
class CardMemory {
    companion object { const val MAX_DIST = 8 }

    private class Entry(val sig: Long, var l1: Int = 0, var l2: Int = 0)
    private val entries = mutableListOf<Entry>()

    private fun find(sig: Long) = entries.firstOrNull { java.lang.Long.bitCount(it.sig xor sig) <= MAX_DIST }

    /** true, если одна копия уже куплена и покупка этой карточки даст объединение. */
    fun completesPair(sig: Long): Boolean = (find(sig)?.l1 ?: 0) == 1

    fun recordBuy(sig: Long) {
        val e = find(sig) ?: Entry(sig).also { entries += it }
        e.l1++
        if (e.l1 >= 2) { e.l1 = 0; e.l2++; if (e.l2 >= 2) e.l2 = 0 }
    }

    fun clear() = entries.clear()
}

/** Цена карточки по отпечатку: один раз прочитали — дальше без распознавания. */
class CostCache {
    private val items = mutableListOf<Pair<Long, Int>>()
    fun get(sig: Long): Int? = items.firstOrNull { java.lang.Long.bitCount(it.first xor sig) <= CardMemory.MAX_DIST }?.second
    fun put(sig: Long, cost: Int) { if (get(sig) == null) items += sig to cost }
}
