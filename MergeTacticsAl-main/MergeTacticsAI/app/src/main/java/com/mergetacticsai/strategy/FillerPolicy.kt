package com.mergetacticsai.strategy

/**
 * Покупка войск не из сборки («заполнители»). Когда сборка задана списком войск («На арену»), бот покупает только их.
 * Заполнитель допускается лишь пока на поле есть свободное место, скамейка не забита и карточка дешёвая — чтобы в первых
 * раундах не стоять пустым. Дальше заполнители заменяются бойцами сборки на своих клетках.
 * Цифры порогов — допущения, не проверенные в игре.
 */
object FillerPolicy {
    /** Заполнитель дороже этого эликсира не покупаем. */
    const val MAX_COST = 3
    /** Свободных слотов скамейки, которые нельзя занимать заполнителем (место под бойцов сборки). */
    const val KEEP_FREE_BENCH = 2
    /** Вместимость поля, если счётчик «N/M» не прочитался и ранее не был виден. */
    const val DEFAULT_LIMIT = 3

    /** held — сколько войск у нас всего (поле + скамейка); limit — вместимость поля. */
    class State(val held: Int, val limit: Int, val benchFree: Int)

    fun allows(placeSelEmpty: Boolean, inBuild: Boolean, mergeArrow: Boolean, cost: Int?, s: State): Boolean {
        if (placeSelEmpty || inBuild || mergeArrow) return true
        if (cost == null) return false // не знаем, что за карточка и сколько стоит: вслепую не берём
        return cost <= MAX_COST && s.held < s.limit && s.benchFree >= KEEP_FREE_BENCH
    }
}
