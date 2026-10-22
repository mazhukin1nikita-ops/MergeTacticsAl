package com.mergetacticsai.map

/**
 * Поиск маршрута по сетке [HexMap]. Все клетки стоят одну единицу, поэтому достаточно поиска в ширину.
 * Порядок соседей фиксирован, поэтому один и тот же вход всегда даёт один и тот же маршрут.
 * [blocked] — клетки, через которые идти нельзя (стоят юниты). Начало и цель проходят всегда.
 */
object PathFinder {
    /** Кратчайший маршрут от [from] до [to] включительно; null — пути нет. */
    fun find(from: HexCell, to: HexCell, blocked: Set<HexCell> = emptySet()): List<HexCell>? {
        if (!HexMap.inBoard(from.row, from.col) || !HexMap.inBoard(to.row, to.col)) return null
        if (from == to) return listOf(from)
        val prev = HashMap<HexCell, HexCell>()
        val seen = HashSet<HexCell>().apply { add(from) }
        val queue = ArrayDeque<HexCell>().apply { add(from) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (n in HexMap.neighbors(cur)) {
                if (n in seen) continue
                if (n != to && n in blocked) continue
                seen += n
                prev[n] = cur
                if (n == to) return buildPath(prev, from, to)
                queue.add(n)
            }
        }
        return null
    }

    private fun buildPath(prev: Map<HexCell, HexCell>, from: HexCell, to: HexCell): List<HexCell> {
        val out = ArrayList<HexCell>()
        var c: HexCell? = to
        while (c != null) { out += c; c = if (c == from) null else prev[c] }
        return out.reversed()
    }

    /** Длина маршрута в шагах; null — пути нет. */
    fun steps(from: HexCell, to: HexCell, blocked: Set<HexCell> = emptySet()): Int? =
        find(from, to, blocked)?.let { it.size - 1 }

    /** Шаги от [from] до каждой достижимой клетки. Занятые клетки в результат попадают, но дальше через них не идём. */
    fun distanceField(from: HexCell, blocked: Set<HexCell> = emptySet()): Map<HexCell, Int> {
        val dist = HashMap<HexCell, Int>().apply { put(from, 0) }
        val queue = ArrayDeque<HexCell>().apply { add(from) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val d = dist.getValue(cur)
            if (cur != from && cur in blocked) continue
            for (n in HexMap.neighbors(cur)) {
                if (n in dist) continue
                dist[n] = d + 1
                queue.add(n)
            }
        }
        return dist
    }

    /**
     * Сколько шагов надо пройти от [from], чтобы [target] оказался в радиусе [range].
     * 0 — уже достаёт. null — дойти нельзя (все подходы заняты или отрезаны).
     */
    fun stepsToRange(from: HexCell, target: HexCell, range: Int, blocked: Set<HexCell> = emptySet()): Int? {
        if (range < 1) return null
        if (HexMap.distance(from, target) <= range) return 0
        val field = distanceField(from, blocked)
        return field.entries
            .filter { (c, _) -> c != target && c !in blocked && HexMap.distance(c, target) <= range }
            .minOfOrNull { it.value }
    }
}

/** Одна пара «ваш юнит — юнит противника» и время, за которое она впервые сойдётся. */
data class Engagement(
    val own: HexCell,
    val enemy: HexCell,
    /** Длина маршрута между клетками в шагах. */
    val pathSteps: Int,
    /** Сколько клеток ещё нужно пройти двоим вместе, чтобы кто-то достал цель; 0 — достаёт с места. */
    val gap: Int,
    /** Секунды до первого удара; null — скорости не заданы. */
    val seconds: Float?,
)

/** Итог по всему полю. [byUnit] — лучшая (самая ранняя) встреча каждого юнита из пар. */
data class ContactReport(
    val first: Engagement?,
    val byUnit: Map<HexCell, Engagement>,
    /** Юниты, для которых нет радиуса ни у них, ни у возможных соперников: пары не посчитаны. */
    val skipped: Int,
    /** Есть ли пары, в которых считали только по одной известной скорости (оценка сверху). */
    val partialSpeed: Boolean,
)

/**
 * Время до первого контакта.
 *
 * Упрощения (считаются по начальной расстановке, как и [Threat]):
 *  - юниты идут по кратчайшему маршруту, остальные юниты на поле считаются препятствиями и не двигаются;
 *  - пара сходится, когда расстояние между ними не больше большего из двух радиусов;
 *  - оба идут навстречу, поэтому скорости складываются; если известна одна — берём её (время завышено);
 *  - скорость в клетках в секунду вводится вручную, проверенных значений в базе нет;
 *  - время разгона, поворота и задержки перед ударом не учитывается.
 */
object ContactModel {
    /** Если ни у кого скорость не задана, время в секундах не считается, остаётся только gap. */
    fun between(
        a: HexCell, b: HexCell, layout: Map<HexCell, PlacedUnit>,
        radii: Map<String, Int>, speeds: Map<String, Float>,
    ): Engagement? {
        val ua = layout[a] ?: return null
        val ub = layout[b] ?: return null
        val range = maxOf(radii[ua.troopId] ?: 0, radii[ub.troopId] ?: 0)
        if (range < 1) return null
        val blocked = layout.keys - a - b
        val path = PathFinder.steps(a, b, blocked) ?: return null
        val gap = (path - range).coerceAtLeast(0)
        val va = speeds[ua.troopId] ?: 0f
        val vb = speeds[ub.troopId] ?: 0f
        val closing = va + vb
        val seconds = when {
            gap == 0 -> 0f
            closing > 0f -> gap / closing
            else -> null
        }
        return Engagement(a, b, path, gap, seconds)
    }

    fun report(layout: Map<HexCell, PlacedUnit>, radii: Map<String, Int>, speeds: Map<String, Float>): ContactReport {
        val own = layout.keys.filter { HexMap.isOwn(it) }
        val enemy = layout.keys.filter { !HexMap.isOwn(it) }
        val all = ArrayList<Engagement>()
        var partial = false
        val covered = HashSet<HexCell>()
        for (a in own) for (b in enemy) {
            val e = between(a, b, layout, radii, speeds) ?: continue
            all += e
            covered += a; covered += b
            val va = speeds[layout.getValue(a).troopId]
            val vb = speeds[layout.getValue(b).troopId]
            if (e.gap > 0 && (va == null) != (vb == null)) partial = true
        }
        val byUnit = HashMap<HexCell, Engagement>()
        for (e in all) {
            for (c in listOf(e.own, e.enemy)) {
                val cur = byUnit[c]
                if (cur == null || better(e, cur)) byUnit[c] = e
            }
        }
        return ContactReport(
            first = all.minWithOrNull { x, y -> if (better(x, y)) -1 else if (better(y, x)) 1 else 0 },
            byUnit = byUnit,
            skipped = (own.size + enemy.size) - covered.size,
            partialSpeed = partial,
        )
    }

    /** Эта встреча раньше другой: по секундам, а без них — по числу клеток. */
    private fun better(x: Engagement, y: Engagement): Boolean {
        val sx = x.seconds
        val sy = y.seconds
        if (sx != null && sy != null && sx != sy) return sx < sy
        if (x.gap != y.gap) return x.gap < y.gap
        return false
    }
}
