package com.mergetacticsai.strategy

/**
 * Забывать войско на клетке можно не по одному кадру, а когда клетка пуста несколько проверок подряд и достаточно долго:
 * мелькнувший кадр без полоски здоровья не должен стирать, кто там стоит.
 */
class MissTracker(private val needMisses: Int = 3, private val needMs: Long = 1200L) {
    private class Miss(val first: Long, var count: Int)
    private val misses = HashMap<Int, Miss>()

    /** Возвращает клетки, чьи записи пора стереть. [keys] — клетки с известными войсками; [occupied] — занятость по картинке. */
    fun update(keys: Collection<Int>, occupied: List<Boolean>, now: Long): Set<Int> {
        misses.keys.retainAll(keys.toSet())
        val drop = HashSet<Int>()
        for (k in keys) {
            if (occupied.getOrElse(k) { false }) { misses.remove(k); continue }
            val m = misses.getOrPut(k) { Miss(now, 0) }
            m.count++
            if (m.count >= needMisses && now - m.first >= needMs) drop += k
        }
        misses.keys.removeAll(drop)
        return drop
    }

    fun clear() = misses.clear()
}
