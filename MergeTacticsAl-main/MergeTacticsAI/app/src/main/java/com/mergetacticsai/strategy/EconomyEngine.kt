package com.mergetacticsai.strategy

import com.mergetacticsai.game.GameState

object EconomyEngine {
    fun canAfford(elixir: Int, cost: Int) = cost in 0..elixir
    fun elixirAfterBuy(s: GameState, shopIndex: Int): Int? {
        val u = s.shop.getOrNull(shopIndex) ?: return null
        return if (canAfford(s.elixir, u.cost)) s.elixir - u.cost else null
    }
}
