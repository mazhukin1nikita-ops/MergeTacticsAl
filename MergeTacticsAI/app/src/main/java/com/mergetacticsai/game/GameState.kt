package com.mergetacticsai.game

data class GameUnit(val name: String, val level: Int, val cost: Int)

/** Слоты нумеруются единым списком: сначала скамейка, затем поле (board). */
data class GameState(
    val elixir: Int,
    val shop: List<GameUnit?>,
    val bench: List<GameUnit?>,
    val board: List<GameUnit?>,
    val refreshCost: Int? = null,
) {
    val slots: List<GameUnit?>
        get() = bench + board

    fun hasFreeBenchSlot() = bench.any { it == null }
}
