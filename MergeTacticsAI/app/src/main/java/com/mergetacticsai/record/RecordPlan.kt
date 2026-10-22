package com.mergetacticsai.record

/**
 * Когда сохранять кадр: редко, пока ничего не происходит, и часто в течение [BURST_MS] после каждого события бота
 * (покупка, постановка, продажа, решение). Чистая логика без Android — проверяется тестами.
 */
class RecordPlan(
    private val idleMs: Long = 700L,
    private val burstEveryMs: Long = 250L,
) {
    companion object { const val BURST_MS = 2500L }

    private var lastSaved = 0L
    private var burstUntil = 0L

    /** Бот что-то сделал: ближайшие секунды пишем кадры часто. */
    fun onEvent(now: Long) { burstUntil = maxOf(burstUntil, now + BURST_MS) }

    /** true — кадр на момент [now] нужно сохранить (и считается сохранённым). */
    fun shouldSave(now: Long): Boolean {
        val gap = if (now < burstUntil) burstEveryMs else idleMs
        if (lastSaved != 0L && now - lastSaved < gap) return false
        lastSaved = now
        return true
    }

    fun reset() { lastSaved = 0L; burstUntil = 0L }
}

/** Имена файлов и отсечение старых кадров. */
object RecordNames {
    fun frame(index: Int, clock: String): String = "f_" + index.toString().padStart(6, '0') + "_" + clock + ".jpg"

    /** Индекс кадра из имени или null. */
    fun indexOf(name: String): Int? =
        if (name.startsWith("f_") && name.endsWith(".jpg")) name.substring(2).takeWhile { it.isDigit() }.toIntOrNull() else null

    /** Какие из [names] удалить, чтобы осталось не больше [keep] самых новых (по индексу). */
    fun toDelete(names: List<String>, keep: Int): List<String> {
        val frames = names.mapNotNull { n -> indexOf(n)?.let { it to n } }.sortedBy { it.first }
        return if (frames.size <= keep) emptyList() else frames.take(frames.size - keep).map { it.second }
    }
}
