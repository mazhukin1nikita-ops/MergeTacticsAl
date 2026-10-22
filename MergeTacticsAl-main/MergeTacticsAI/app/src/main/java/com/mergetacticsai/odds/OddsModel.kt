package com.mergetacticsai.odds

import com.mergetacticsai.data.Troop

enum class GoalKind { COPIES, STARS }

/** COPIES: value — сколько копий войска нужно получить. STARS: value — какую звёздность нужно собрать (2 или 3). */
data class Goal(val kind: GoalKind, val value: Int)

/** Параметры, которые можно отметить как проверенные. Для проверенных диапазон неопределённости не расширяется. */
object Keys {
    const val ODDS = "odds"
    const val POOL = "pool"
    const val OTHERS = "others"
    const val REFRESH = "refresh"
    const val ROUNDS = "rounds"

    val all = listOf(ODDS, POOL, OTHERS, REFRESH, ROUNDS)

    fun title(k: String) = when (k) {
        ODDS -> "шансы выпадения по цене"
        POOL -> "размер пула"
        OTHERS -> "добор карт соперниками"
        REFRESH -> "цена обновления"
        ROUNDS -> "число раундов в игре"
        else -> k
    }
}

/**
 * Параметры модели магазина и игры. Значения по умолчанию, кроме [shopSlots], [income] и [copiesPerMerge],
 * — предположения: в базе приложения нет проверенных правил генерации магазина.
 */
data class OddsParams(
    /** Карточек в магазине (три карточки на экране игры). */
    val shopSlots: Int = 3,
    /** Вес каждой цены при выборе карточки. Пусто — шанс пропорционален числу войск каждой цены (все войска равновероятны). */
    val weights: Map<Int, Double> = emptyMap(),
    /** Копий каждого войска в общем пуле; 0 — пул не ограничен (каждая карточка независима). */
    val copiesPerTroop: Int = 0,
    /** Копий искомого войска, уже взятых другими игроками. */
    val takenByOthers: Int = 0,
    /** Сколько карт из пула забирают соперники суммарно за раунд. */
    val othersDrawsPerRound: Int = 0,
    val refreshCost: Int = 2,
    /** Эликсира за раунд (Supercell blog, проверено). */
    val income: Int = 4,
    val startElixir: Int = 0,
    val rounds: Int = 10,
    /** Мест под копии этого войска (скамейка и поле), считая уже занятые его копиями. */
    val freeSlots: Int = 5,
    /** Какую долю дохода вы тратите на это войско, в процентах. */
    val budgetSharePct: Int = 100,
    val maxRefreshPerRound: Int = 2,
    /** Одно-звёздочных копий уже есть. */
    val startCopies: Int = 0,
    /** Сколько одинаковых войск сливаются в следующую звезду (слияние пары в логике бота). */
    val copiesPerMerge: Int = 2,
    /** Служебный множитель шанса карточки: используется только для сценариев неопределённости. */
    val qScale: Double = 1.0,
) {
    val budgetShare: Double get() = budgetSharePct / 100.0
}

/** Насколько вы не уверены в непроверенных параметрах: от этого зависит диапазон результата. */
data class Uncertainty(
    val oddsSpreadPct: Int = 50,
    val refreshSpread: Int = 1,
    val roundsSpread: Int = 2,
    val poolSpreadPct: Int = 25,
    val othersSpreadPct: Int = 50,
)

data class OddsState(
    val targetId: String = "",
    val goal: Goal = Goal(GoalKind.STARS, 2),
    val params: OddsParams = OddsParams(),
    val unc: Uncertainty = Uncertainty(),
    val verified: Set<String> = emptySet(),
)

/** Сколько войск каждой цены в текущем пуле: без Legacy и без специальных объектов за 0. */
fun tiersOf(troops: List<Troop>): Map<Int, Int> =
    troops.filter { !it.legacy && it.cost > 0 }.groupingBy { it.cost }.eachCount()
