package com.mergetacticsai.strategy

sealed class Action {
    data class Buy(val shopIndex: Int) : Action()
    data class Merge(val a: Int, val b: Int) : Action()
    data class Move(val from: Int, val to: Int) : Action()
    data class Sell(val slot: Int) : Action()
    object Refresh : Action()
    object Wait : Action()
}
