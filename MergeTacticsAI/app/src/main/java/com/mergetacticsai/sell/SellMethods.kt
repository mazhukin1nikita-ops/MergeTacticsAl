package com.mergetacticsai.sell

import com.mergetacticsai.vision.ScreenRegions

/** Три способа убрать воина с поля. Порядок объявления = приоритет по умолчанию. */
enum class SellMethod(val title: String) {
    SELL_BUTTON("Нажать воина → «Продать»"),
    DRAG_TO_BENCH("Перетащить на скамейку"),
    DRAG_TO_SHOP("Перетащить в зону продажи"),
}

/** Одна попытка продажи — ровно те поля, которые нужны для сравнения способов. */
data class SellAttempt(
    val unitId: String,
    val unitPosition: Int,
    val selectedUnit: String,
    val method: SellMethod,
    val startX: Float,
    val startY: Float,
    val targetX: Float,
    val targetY: Float,
    val gestureMs: Long,
    val result: String,
    val success: Boolean,
    val errorType: String,
    val resolution: String,
    val gameState: String,
    val at: Long,
)

/** Хранилище попыток. В приложении — SQLite, в тестах — память. */
interface SellStore {
    fun add(a: SellAttempt)
    /** Способ → (успехов, попыток) для данного разрешения экрана и состояния игры. */
    fun stats(resolution: String, gameState: String): Map<SellMethod, Pair<Int, Int>>
    fun recent(limit: Int): List<SellAttempt>
    fun count(): Int
    fun clear()
    /** Способ → (успехов, попыток) за всё время. */
    fun totals(): Map<SellMethod, Pair<Int, Int>>
}

class MemorySellStore : SellStore {
    private val items = ArrayList<SellAttempt>()
    @Synchronized override fun add(a: SellAttempt) { items.add(a) }
    @Synchronized override fun stats(resolution: String, gameState: String): Map<SellMethod, Pair<Int, Int>> {
        val out = HashMap<SellMethod, Pair<Int, Int>>()
        for (a in items) {
            if (a.resolution != resolution || a.gameState != gameState) continue
            val p = out[a.method] ?: Pair(0, 0)
            out[a.method] = Pair(p.first + if (a.success) 1 else 0, p.second + 1)
        }
        return out
    }
    @Synchronized override fun recent(limit: Int): List<SellAttempt> = items.asReversed().take(limit)
    @Synchronized override fun count(): Int = items.size
    @Synchronized override fun clear() { items.clear() }
    @Synchronized override fun totals(): Map<SellMethod, Pair<Int, Int>> {
        val out = HashMap<SellMethod, Pair<Int, Int>>()
        for (a in items) {
            val p = out[a.method] ?: Pair(0, 0)
            out[a.method] = Pair(p.first + if (a.success) 1 else 0, p.second + 1)
        }
        return out
    }
}

object SellRanker {
    /** Пока попыток меньше этого числа, порядок остаётся по умолчанию (1 → 2 → 3). */
    const val MIN_SAMPLES = 3

    /**
     * Порядок способов: по умолчанию 1 → 2 → 3; после накопления статистики — по сглаженной доле успехов
     * (успехи+1)/(попытки+2), при равенстве выигрывает способ с меньшим номером.
     * Это обычный подсчёт частот, а не машинное обучение.
     */
    fun order(stats: Map<SellMethod, Pair<Int, Int>>, allowed: Set<SellMethod> = SellMethod.values().toSet()): List<SellMethod> {
        val defaults = SellMethod.values().filter { it in allowed }
        val total = stats.filterKeys { it in allowed }.values.sumOf { it.second }
        if (total < MIN_SAMPLES) return defaults
        fun score(m: SellMethod): Double {
            val p = stats[m] ?: Pair(0, 0)
            return (p.first + 1.0) / (p.second + 2.0) + (SellMethod.values().size - m.ordinal) * 0.001
        }
        return defaults.sortedByDescending { score(it) }
    }
}

/** Проверки перед продажей. Возвращает причину отказа или null, если продавать можно. */
object SellGuard {
    fun check(deployPhase: Boolean, unitConfirmed: Boolean, unneeded: Boolean, planAllows: Boolean): String? = when {
        !deployPhase -> "идёт бой или переход между раундами"
        !unitConfirmed -> "воин на клетке не подтверждён распознаванием"
        !unneeded -> "воин нужен сборке"
        !planAllows -> "продажа нарушит текущий план"
        else -> null
    }
}

/** Зона продажи под полем. Координаты в долях экрана, поэтому не привязаны к одному устройству. */
object SellZone {
    fun center(w: Int, h: Int): Pair<Float, Float> = Pair(ScreenRegions.SELL_X * w, ScreenRegions.SELL_Y * h)
}
