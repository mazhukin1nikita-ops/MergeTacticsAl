package com.mergetacticsai.automation

/** Ограничение запросов к ИИ: минимальный интервал и лимит в час. */
class CallBudget(
    private val minIntervalMs: Long,
    private val maxPerHour: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val times = ArrayDeque<Long>()

    fun tryAcquire(): Boolean {
        val now = clock()
        while (times.isNotEmpty() && now - times.first() > 3_600_000L) times.removeFirst()
        if (times.isNotEmpty() && now - times.last() < minIntervalMs) return false
        if (times.size >= maxPerHour) return false
        times.addLast(now)
        return true
    }
}
