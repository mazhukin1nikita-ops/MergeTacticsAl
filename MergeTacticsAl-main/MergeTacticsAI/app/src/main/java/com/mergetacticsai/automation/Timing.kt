package com.mergetacticsai.automation

/**
 * Скорость жестов и пауз бота. Старт — самый быстрый уровень. Если жест не сработал (воин не встал, продажа или покупка
 * не подтвердились), скорость падает на уровень и жесты становятся медленнее; после [FASTER_AFTER] удач подряд — снова быстрее.
 * Так бот работает почти мгновенно там, где игра успевает, и сам не ломается там, где не успевает.
 * Значения — допущения: проверены не на всех телефонах. Уровни можно править здесь, в одном месте.
 */
object Timing {
    class Level(val dragMs: Long, val holdMs: Long, val dwellMs: Long, val settleMs: Long)

    val levels = listOf(
        Level(dragMs = 110, holdMs = 120, dwellMs = 60, settleMs = 180),   // 0: почти мгновенно
        Level(dragMs = 200, holdMs = 200, dwellMs = 100, settleMs = 300),  // 1: быстро
        Level(dragMs = 300, holdMs = 350, dwellMs = 200, settleMs = 450),  // 2: надёжно (как раньше)
    )

    /** User-adjustable delays (milliseconds). Values persist through SettingsRepository. */
    @Volatile var buyDelayMs: Long = 350L
    @Volatile var placeDelayMs: Long = 300L
    @Volatile var sellDelayMs: Long = 450L
    @Volatile var mergeDelayMs: Long = 350L
    @Volatile var verifyDelayMs: Long = 300L
    @Volatile var loopDelayMs: Long = 100L

    fun configure(buy: Long, place: Long, sell: Long, merge: Long, verify: Long, loop: Long) {
        buyDelayMs = buy.coerceIn(0L, 2500L)
        placeDelayMs = place.coerceIn(0L, 2500L)
        sellDelayMs = sell.coerceIn(0L, 2500L)
        mergeDelayMs = merge.coerceIn(0L, 2500L)
        verifyDelayMs = verify.coerceIn(0L, 2500L)
        loopDelayMs = loop.coerceIn(25L, 1000L)
    }

    /** Касание. */
    const val TAP_MS = 30L
    /** Пауза цикла между кадрами. */
    const val LOOP_MS = 25L
    /** Пауза после жеста, чтобы следующий кадр успел отразить результат. */
    const val AFTER_MS = 30L
    /** Минимум между двумя расчётами решения. */
    const val ENGINE_GAP_MS = 60L
    /** Пауза между способами продажи с поля. */
    const val BETWEEN_METHODS_MS = 100L
    /** После скольких удач подряд скорость растёт на уровень. */
    const val FASTER_AFTER = 6

    @Volatile var level: Int = 0
        private set
    private var streak = 0

    val dragMs: Long get() = levels[level].dragMs
    val holdMs: Long get() = levels[level].holdMs
    val dwellMs: Long get() = levels[level].dwellMs
    val settleMs: Long get() = levels[level].settleMs

    @Synchronized fun reset() { level = 0; streak = 0 }

    /** Жест сработал. */
    @Synchronized fun ok() {
        streak++
        if (streak >= FASTER_AFTER && level > 0) { level--; streak = 0 }
    }

    /** Жест не сработал: замедляемся. */
    @Synchronized fun fail() {
        streak = 0
        if (level < levels.lastIndex) level++
    }
}
