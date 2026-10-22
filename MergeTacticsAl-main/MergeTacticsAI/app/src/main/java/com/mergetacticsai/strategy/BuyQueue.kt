package com.mergetacticsai.strategy

/**
 * Очередь покупок, ещё не появившихся на скамейке. Новый занятый слот забирает САМУЮ СТАРУЮ покупку: покупки идут друг
 * за другом, и слоты заполняются в том же порядке. Раньше хранилась одна последняя покупка, и две покупки подряд
 * получали одно и то же название. Записи старше [ttlMs] выбрасываются (слияние не занимает слот, и такая запись не должна
 * достаться чужому юниту).
 */
class BuyQueue<T>(private val ttlMs: Long = 3500L) {
    private class Entry<T>(val item: T, val at: Long)
    private val q = ArrayDeque<Entry<T>>()

    fun push(item: T, now: Long) { q.addLast(Entry(item, now)) }

    fun pop(now: Long): T? {
        while (q.isNotEmpty() && now - q.first().at > ttlMs) q.removeFirst()
        return if (q.isEmpty()) null else q.removeFirst().item
    }

    fun clear() = q.clear()
    val size: Int get() = q.size
}
