package com.mergetacticsai.data

import com.mergetacticsai.strategy.CardMemory

/** Какой отпечаток карточки магазина какому войску соответствует (заполняется во вкладке «Обучение»). */
class KnownCards(initial: List<Pair<Long, String>> = emptyList()) {
    private val items = initial.toMutableList()

    fun lookup(sig: Long): String? {
        val best = items.minByOrNull { java.lang.Long.bitCount(it.first xor sig) } ?: return null
        return if (java.lang.Long.bitCount(best.first xor sig) <= CardMemory.MAX_DIST) best.second else null
    }

    fun add(sig: Long, troopId: String) {
        items.removeAll { java.lang.Long.bitCount(it.first xor sig) <= 2 }
        items += sig to troopId
    }

    fun learnedIds(): Set<String> = items.map { it.second }.toSet()
    fun size() = items.size

    fun serialize(): String = items.joinToString("|") { "${java.lang.Long.toHexString(it.first)}:${it.second}" }

    companion object {
        fun parse(s: String): KnownCards = KnownCards(
            s.split("|").filter { it.contains(':') }.mapNotNull {
                val (h, id) = it.split(":", limit = 2)
                runCatching { java.lang.Long.parseUnsignedLong(h, 16) to id }.getOrNull()
            }
        )
    }
}
