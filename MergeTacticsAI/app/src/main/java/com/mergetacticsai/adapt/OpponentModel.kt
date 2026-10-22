package com.mergetacticsai.adapt

import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.HexMap
import com.mergetacticsai.map.PlacedUnit

/** Насколько можно доверять выводу. Считается из объёма и свежести видимых данных, а не из «ощущения». */
enum class Level(val title: String) {
    NONE("нет данных"),
    LOW("низкая"),
    MEDIUM("средняя"),
    HIGH("высокая"),
}

/** Откуда взят состав: вы задали его на карте вручную или он найден по скриншоту. */
enum class Source(val title: String) { MANUAL("введено вручную"), SCAN("по скриншоту") }

/** Видимое войско соперника. troopId == [PlacedUnit.UNKNOWN] — юнит есть, название не задано. */
data class Seen(val troopId: String, val stars: Int)

/**
 * Один снимок видимого состава соперника. [label] — как вы его назвали (например «Соперник 2»).
 * Скамейку соперника и купленные, но не выставленные карты увидеть нельзя: в снимке их нет.
 */
data class Snapshot(val label: String, val round: Int, val at: Long, val units: List<Seen>, val source: Source = Source.MANUAL) {
    val known: List<Seen> get() = units.filter { it.troopId != PlacedUnit.UNKNOWN }
    val unknownCount: Int get() = units.size - known.size

    companion object {
        /** Снимок из расстановки соперника на карте (ряды 0-3). */
        fun fromLayout(label: String, round: Int, at: Long, layout: Map<HexCell, PlacedUnit>, source: Source): Snapshot =
            Snapshot(
                label, round, at,
                layout.filterKeys { !HexMap.isOwn(it) }.entries
                    .sortedWith(compareBy({ it.key.row }, { it.key.col }))
                    .map { Seen(it.value.troopId, it.value.stars.coerceIn(1, 3)) },
                source,
            )
    }
}

/** Допущения расчёта. Ни одно из чисел не проверено по игре; все они показываются на экране как предположения. */
data class AdaptParams(
    /** Сколько раундов снимок считается свежим. После этого его вес падает. */
    val freshRounds: Int = 2,
    /** Снимок старше этого числа раундов в расчёт не входит. */
    val dropRounds: Int = 6,
    /** Сколько карт соперник в среднем держит вне поля. Диапазон — [hiddenLow, hiddenHigh]. */
    val hiddenLow: Int = 0,
    val hiddenMid: Int = 3,
    val hiddenHigh: Int = 5,
    /** Доля скрытых карт, которая продолжает уже видимое направление соперника (остальное — случайные карты). */
    val sticky: Double = 0.5,
    /** Копий каждого войска в пуле; 0 — размер пула неизвестен. */
    val copiesPerTroop: Int = 0,
    /** Сколько копий делают одну звезду (слияние по 2). */
    val copiesPerMerge: Int = 2,
    /** Сколько соперников в матче; нужно для оценки «доли соперников, у кого есть войско». 0 — неизвестно. */
    val opponents: Int = 0,
)

/** Копий в войске звёздности [stars]: 1★ = 1, 2★ = m, 3★ = m². */
fun copiesOf(stars: Int, merge: Int): Int {
    var n = 1
    repeat((stars - 1).coerceIn(0, 2)) { n *= merge.coerceAtLeast(2) }
    return n
}

/**
 * Книга наблюдений: последний снимок каждого соперника плюс короткая история.
 * Обновление заменяет прежний снимок того же соперника, поэтому «видимый состав» всегда самый свежий.
 */
class OpponentBook(snapshots: List<Snapshot> = emptyList()) {
    private val latest = LinkedHashMap<String, Snapshot>()
    private val history = ArrayList<Snapshot>()

    init { snapshots.forEach { record(it) } }

    /** Возвращает true, если состав изменился по сравнению с прежним снимком этого соперника. */
    fun record(s: Snapshot): Boolean {
        val prev = latest[s.label]
        val changed = prev == null || prev.units != s.units
        latest[s.label] = if (changed) s else prev!!.copy(round = maxOf(prev.round, s.round), at = s.at)
        if (changed) {
            history += s
            while (history.size > MAX_HISTORY) history.removeAt(0)
        }
        return changed
    }

    fun forget(label: String) { latest.remove(label); history.removeAll { it.label == label } }
    fun clear() { latest.clear(); history.clear() }

    fun current(): List<Snapshot> = latest.values.toList()
    fun history(): List<Snapshot> = history.toList()
    fun isEmpty() = latest.isEmpty()

    /** Возраст снимка в раундах (0 — виден в этом раунде). */
    fun age(s: Snapshot, nowRound: Int): Int = (nowRound - s.round).coerceAtLeast(0)

    companion object { const val MAX_HISTORY = 60 }
}
