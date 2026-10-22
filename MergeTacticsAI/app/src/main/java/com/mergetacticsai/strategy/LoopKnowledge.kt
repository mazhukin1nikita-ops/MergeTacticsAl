package com.mergetacticsai.strategy

import com.mergetacticsai.data.CatalogItem
import com.mergetacticsai.data.KnownCards
import com.mergetacticsai.data.Troop

/** Что бот знает: войска, правила выбора пользователя (TroopPolicy), изученные карточки и свойства. */
class LoopKnowledge(
    val troops: Map<String, Troop>,
    val policy: TroopPolicy,
    val known: KnownCards,
    val traits: List<CatalogItem>,
    val matcher: com.mergetacticsai.vision.CardMatcher? = null,
    val placeSel: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = LoopKnowledge(emptyMap(), TroopPolicy(emptySet(), emptySet(), false), KnownCards(), emptyList())
    }
}
