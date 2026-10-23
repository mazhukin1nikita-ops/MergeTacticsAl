package com.mergetacticsai.util

/** Итог восстановления: сколько сбоев подряд было и как долго связи не было. */
data class Recovery(val failures: Int, val downMs: Long)

/**
 * Считает сбои запросов подряд и отвечает, сколько ждать перед повтором: 3, 6, 12, 24 и дальше 30 секунд.
 * Первый успешный запрос после сбоев возвращает [Recovery].
 */
class FailureTracker(private val clock: () -> Long = System::currentTimeMillis) {
    var consecutive = 0
        private set
    private var firstAt = 0L

    /** Запрос не удался. Возвращает паузу перед повтором в миллисекундах. */
    fun onFailure(): Long {
        if (consecutive == 0) firstAt = clock()
        consecutive++
        return backoffMs(consecutive)
    }

    /** Запрос удался. Если до этого были сбои — возвращает итог восстановления. */
    fun onSuccess(): Recovery? {
        if (consecutive == 0) return null
        val r = Recovery(consecutive, clock() - firstAt)
        consecutive = 0
        return r
    }

    companion object {
        fun backoffMs(failures: Int): Long = (3000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(30_000L)
    }
}
