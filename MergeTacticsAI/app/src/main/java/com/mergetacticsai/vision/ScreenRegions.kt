package com.mergetacticsai.vision

/** Доли экрана, снятые со скриншотов 1080x2400. Подстраиваются под другой телефон правкой чисел. */
object ScreenRegions {
    data class R(val l: Float, val t: Float, val r: Float, val b: Float)

    val shop = R(0.17f, 0.875f, 0.78f, 0.98f)
    val elixir = R(0.815f, 0.923f, 0.92f, 0.971f)

    /** Центры трёх карточек магазина. */
    val cardX = floatArrayOf(0.273f, 0.477f, 0.681f)
    const val CARD_Y = 0.929f

    /** Зона продажи (появляется под полем, когда тянешь юнита). */
    const val SELL_X = 0.483f
    const val SELL_Y = 0.927f

    /** Центры пяти слотов скамейки игрока. */
    val benchX = floatArrayOf(0.171f, 0.301f, 0.431f, 0.560f, 0.690f)
    const val BENCH_Y = 0.802f
}
