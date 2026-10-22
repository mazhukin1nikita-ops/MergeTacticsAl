package com.mergetacticsai.adapt

import com.mergetacticsai.data.CatalogItem
import com.mergetacticsai.data.Troop
import com.mergetacticsai.odds.ShopMath
import kotlin.math.exp
import kotlin.math.min

/** Диапазон ожидаемого значения. nominal — при среднем допущении, low/high — при крайних. */
data class Span(val low: Double, val nominal: Double, val high: Double)

/** Что кто-то из соперников держит на виду. */
data class Demand(
    val troopId: String, val name: String, val trait: String,
    /** Сколько соперников выставили это войско (свежие снимки). */
    val opponents: Int,
    /** Сколько копий видно у соперников (2★ — две копии, 3★ — четыре). */
    val copiesSeen: Int,
    /** Взвешенная востребованность: свежие снимки весят больше. */
    val weight: Double,
    val level: Level,
)

enum class Stock(val title: String) {
    SCARCE("в дефиците"),
    TIGHT("становится мало"),
    OK("хватает"),
    UNKNOWN("размер пула не задан"),
}

/**
 * Оценка дефицита карты. Видимое — факт со снимков, скрытое — вероятностная оценка.
 * [remaining] — сколько копий может остаться в пуле (null, если размер пула неизвестен).
 */
data class Scarcity(
    val troopId: String, val name: String,
    val seen: Int, val own: Int,
    val hiddenExpected: Span,
    /** Шанс, что хотя бы у одного соперника есть скрытая копия (приближение). */
    val hiddenAny: Double,
    val remaining: Span?,
    val stock: Stock, val level: Level, val text: String,
)

/** Направление сборки соперника. [completeChance] — шанс, что скрытыми картами он дойдёт до следующего порога. */
data class Combo(
    val opponent: String, val trait: String, val traitName: String,
    val count: Int, val tier: Int, val next: Int?, val missing: Int?, val completeChance: Double?,
    val level: Level, val text: String,
)

enum class ThreatLevel(val title: String) { LOW("низкая"), MEDIUM("средняя"), HIGH("высокая") }

data class Threat(
    val opponent: String, val level: ThreatLevel, val score: Double,
    val reasons: List<String>, val confidence: Level, val ageRounds: Int,
)

enum class CounterKind(val title: String) {
    REFRESH("Обновить данные"),
    AVOID("Не гнаться за картой"),
    PAIR_NOW("Докупить пару раньше"),
    OPEN_LANE("Свободное направление"),
    DENY("Перехват карты"),
    FORMATION("Расстановка"),
    NO_DATA("Нет проверенной контрмеры"),
}

data class Counter(val kind: CounterKind, val text: String, val basis: String, val level: Level)

class Analysis(
    val round: Int,
    val used: List<Snapshot>,
    val stale: List<Snapshot>,
    val demand: List<Demand>,
    val traitDemand: List<Pair<String, Int>>,
    val scarcity: List<Scarcity>,
    val combos: List<Combo>,
    val threats: List<Threat>,
    val counters: List<Counter>,
    val level: Level,
    val notes: List<String>,
) {
    val isEmpty: Boolean get() = used.isEmpty()
}

/** Что аналитик знает о нас и о игре. */
class AdaptInput(
    val troops: Map<String, Troop>,
    val traits: List<CatalogItem>,
    /** Копий каждого войска у нас (скамейка и поле). */
    val ownCopies: Map<String, Int> = emptyMap(),
    /** Наши войска на поле: нужны для сравнения силы. */
    val ownUnits: List<Seen> = emptyList(),
    /** Id войск и свойств нашей сборки: их не отговариваем, а предупреждаем о конкуренции. */
    val buildTroops: Set<String> = emptySet(),
    val buildTraits: Set<String> = emptySet(),
    /** Радиусы атаки, которые вы задали на карте. Нет радиуса — войско не относим ни к дальним, ни к ближним. */
    val radii: Map<String, Int> = emptyMap(),
    /** Во сколько раз растут HP и урон за звезду (предположение, если не задано вами). */
    val starFactor: Double = 1.8,
    val params: AdaptParams = AdaptParams(),
)

/**
 * Анализ соперников. Чистый Kotlin, без Android.
 *
 * Правила честности:
 *  - видимое (то, что выставлено на поле) — факт со снимка, с указанием возраста;
 *  - скрытое (скамейка, купленные карты, будущие покупки) — только вероятность в диапазоне, никогда не «у него есть»;
 *  - если размер пула не задан, дефицит не утверждается: показывается лишь конкуренция за карту;
 *  - уровень уверенности выводится из числа свежих снимков, доли неопознанных юнитов и источника данных.
 */
object OpponentAnalyzer {
    private const val MIN_WEIGHT = 0.25

    fun weight(age: Int, p: AdaptParams): Double = when {
        age <= p.freshRounds -> 1.0
        age >= p.dropRounds -> 0.0
        else -> 1.0 - (1.0 - MIN_WEIGHT) * (age - p.freshRounds).toDouble() / (p.dropRounds - p.freshRounds).coerceAtLeast(1)
    }

    private fun pool(input: AdaptInput) = input.troops.values.filter { !it.legacy && it.cost > 0 }

    fun analyze(book: OpponentBook, nowRound: Int, input: AdaptInput): Analysis {
        val p = input.params
        val notes = ArrayList<String>()
        val all = book.current()
        val used = all.filter { book.age(it, nowRound) < p.dropRounds && it.units.isNotEmpty() }
        val stale = all.filter { it !in used }
        val fresh = used.filter { book.age(it, nowRound) <= p.freshRounds }
        if (used.isEmpty()) {
            val counters = listOf(
                Counter(CounterKind.REFRESH, if (all.isEmpty()) "Составы соперников не записаны: откройте «Тактическую карту», задайте войска противника и нажмите «Запомнить противника»." else "Все снимки устарели: обновите составы.", "нет свежих данных", Level.NONE),
            )
            return Analysis(nowRound, emptyList(), stale, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), counters, Level.NONE,
                listOf("Выводов о соперниках нет: нет свежих снимков."))
        }

        val poolTroops = pool(input)
        val nPool = poolTroops.size.coerceAtLeast(1)
        val traitSize: Map<String, Int> = poolTroops.groupBy { it.trait }.mapValues { it.value.size }

        // ---- видимые копии и востребованность
        val seenCopies = HashMap<String, Int>()
        val oppCount = HashMap<String, Int>()
        val weightSum = HashMap<String, Double>()
        for (s in used) {
            val w = weight(book.age(s, nowRound), p)
            val perTroop = s.known.groupBy { it.troopId }
            for ((id, us) in perTroop) {
                val copies = us.sumOf { copiesOf(it.stars, p.copiesPerMerge) }
                seenCopies[id] = (seenCopies[id] ?: 0) + copies
                oppCount[id] = (oppCount[id] ?: 0) + 1
                weightSum[id] = (weightSum[id] ?: 0.0) + w * copies
            }
        }
        val unknownTotal = used.sumOf { it.unknownCount }
        val unitsTotal = used.sumOf { it.units.size }.coerceAtLeast(1)
        val unknownShare = unknownTotal.toDouble() / unitsTotal
        val sources = used.map { it.source }.toSet()

        // ---- общий уровень уверенности по видимым данным
        val visibleLevel = when {
            fresh.isEmpty() -> Level.LOW
            fresh.size >= 3 && unknownShare < 0.10 -> Level.HIGH
            fresh.size >= 2 && unknownShare < 0.25 -> Level.MEDIUM
            fresh.size >= 1 && unknownShare < 0.25 -> Level.MEDIUM
            else -> Level.LOW
        }
        // Скрытые карты никогда не выше «средней»; без размера пула и опознанных соперников — низкая.
        val hiddenLevel = when {
            p.copiesPerTroop <= 0 -> Level.LOW
            visibleLevel == Level.HIGH -> Level.MEDIUM
            else -> Level.LOW
        }
        if (unknownShare > 0.0) notes += "Неопознанных юнитов у соперников: $unknownTotal из ${used.sumOf { it.units.size }}. Они в расчёт по составу не входят."
        if (fresh.isEmpty()) notes += "Свежих снимков нет: данные старше ${p.freshRounds} раунд(ов), выводы ослаблены."
        if (SourceHint.scanOnly(sources)) notes += "Составы найдены по скриншоту: название войска на скриншоте не определяется, проверьте их на карте."
        if (stale.isNotEmpty()) notes += "Не используются устаревшие снимки: " + stale.joinToString { "${it.label} (раунд ${it.round})" } + "."
        if (p.copiesPerTroop <= 0) notes += "Размер пула не задан (экран «Вероятности»): дефицит карт не утверждается, показана только конкуренция за карту."

        val demand = seenCopies.keys.mapNotNull { id ->
            val t = input.troops[id] ?: return@mapNotNull null
            Demand(id, t.name, t.trait, oppCount[id] ?: 0, seenCopies[id] ?: 0, weightSum[id] ?: 0.0, visibleLevel)
        }.sortedWith(compareByDescending<Demand> { it.weight }.thenByDescending { it.opponents }.thenBy { it.name })

        val traitDemandMap = HashMap<String, Int>()
        for (s in used) for (tr in s.known.mapNotNull { input.troops[it.troopId]?.trait }.filter { it.isNotEmpty() }.toSet()) {
            traitDemandMap[tr] = (traitDemandMap[tr] ?: 0) + 1
        }
        val traitDemand = traitDemandMap.entries.sortedByDescending { it.value }.map { it.key to it.value }

        // ---- скрытые карты (вероятностная модель)
        val hiddenAt = HashMap<String, DoubleArray>() // troopId -> [low, mid, high] ожидаемых копий у всех соперников
        val traitProbPerCard = HashMap<Pair<String, String>, Double>() // (опонент, свойство) -> шанс, что скрытая карта этого свойства
        for (s in used) {
            val known = s.known
            val totalSeen = known.sumOf { copiesOf(it.stars, p.copiesPerMerge) }.coerceAtLeast(1)
            val byTrait = known.mapNotNull { u -> input.troops[u.troopId]?.trait?.takeIf { it.isNotEmpty() }?.let { it to copiesOf(u.stars, p.copiesPerMerge) } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            val traitTotal = byTrait.values.sum().coerceAtLeast(1)
            val ks = doubleArrayOf(p.hiddenLow.toDouble(), p.hiddenMid.toDouble(), p.hiddenHigh.toDouble())
            for (t in poolTroops) {
                val sameTroop = (known.filter { it.troopId == t.id }.sumOf { copiesOf(it.stars, p.copiesPerMerge) }).toDouble() / totalSeen
                val sameTrait = if (t.trait.isEmpty()) 0.0
                else ((byTrait[t.trait] ?: 0).toDouble() / traitTotal) / (traitSize[t.trait] ?: 1).coerceAtLeast(1)
                val sticky = 0.5 * sameTroop + 0.5 * sameTrait
                val perCard = p.sticky * sticky + (1 - p.sticky) / nPool
                val arr = hiddenAt.getOrPut(t.id) { DoubleArray(3) }
                for (i in 0..2) arr[i] += ks[i] * perCard
            }
            for (tr in traitSize.keys) {
                val share = (byTrait[tr] ?: 0).toDouble() / traitTotal
                traitProbPerCard[s.label to tr] = p.sticky * share + (1 - p.sticky) * (traitSize[tr] ?: 0).toDouble() / nPool
            }
        }

        // ---- дефицит
        val scarcity = ArrayList<Scarcity>()
        val interesting = HashSet<String>()
        interesting += seenCopies.keys
        interesting += input.ownCopies.filter { it.value > 0 }.keys
        for (id in interesting) {
            val t = input.troops[id] ?: continue
            if (t.legacy || t.cost <= 0) continue
            val seen = seenCopies[id] ?: 0
            val own = input.ownCopies[id] ?: 0
            val h = hiddenAt[id] ?: DoubleArray(3)
            val hs = Span(h[0], h[1], h[2])
            val any = 1.0 - exp(-h[1])
            val poolN = p.copiesPerTroop
            val rem = if (poolN > 0) Span(
                (poolN - seen - own - h[2]).coerceAtLeast(0.0),
                (poolN - seen - own - h[1]).coerceAtLeast(0.0),
                (poolN - seen - own - h[0]).coerceAtLeast(0.0),
            ) else null
            val stock = when {
                rem == null -> Stock.UNKNOWN
                rem.nominal <= poolN * 0.25 -> Stock.SCARCE
                rem.nominal <= poolN * 0.5 -> Stock.TIGHT
                else -> Stock.OK
            }
            val lvl = if (rem == null) Level.LOW else hiddenLevel
            val text = buildString {
                append("«${t.name}»: у соперников видно $seen коп., у вас $own")
                if (rem != null) append("; в пуле осталось примерно ${fmt(rem.nominal)} из $poolN (вероятный диапазон ${fmt(rem.low)}–${fmt(rem.high)})")
                append("; скрытых копий вероятно ${fmt(hs.low)}–${fmt(hs.high)}")
                append(".")
            }
            scarcity += Scarcity(id, t.name, seen, own, hs, any, rem, stock, lvl, text)
        }
        scarcity.sortWith(compareBy<Scarcity> { it.stock.ordinal }.thenByDescending { it.seen })

        // ---- комбинации соперников
        val combos = ArrayList<Combo>()
        for (s in used) {
            val byTrait = s.known.mapNotNull { input.troops[it.troopId]?.trait?.takeIf { t -> t.isNotEmpty() } }.groupingBy { it }.eachCount()
            for ((tr, cnt) in byTrait.entries.sortedByDescending { it.value }) {
                val item = input.traits.firstOrNull { it.id == tr }
                val tiers = item?.tiers?.takeIf { it.isNotEmpty() } ?: if (tr == "boss") listOf(1, 2, 3, 4) else listOf(2, 4, 6)
                val tier = tiers.count { cnt >= it }
                val next = tiers.firstOrNull { it > cnt }
                if (tier == 0 && (next == null || next - cnt > 1)) continue
                val missing = next?.minus(cnt)
                val q = traitProbPerCard[s.label to tr] ?: 0.0
                val chance = if (missing != null && p.hiddenMid > 0) ShopMath.pAtLeast(p.hiddenMid, missing, q) else null
                val lvl = if (book.age(s, nowRound) > p.freshRounds) Level.LOW else hiddenLevelFor(visibleLevel, chance != null)
                val name = item?.name ?: tr
                val text = buildString {
                    append("${s.label}: «$name» $cnt")
                    append(if (tier > 0) ", активен (уровень $tier)" else ", ещё не активен")
                    if (next != null) append(", до порога $next не хватает $missing")
                    if (chance != null) append("; шанс, что скрытыми картами дойдёт до порога, около ${pctText(chance)}")
                    append(".")
                }
                combos += Combo(s.label, tr, name, cnt, tier, next, missing, chance, lvl, text)
            }
        }
        combos.sortWith(compareByDescending<Combo> { it.tier }.thenByDescending { it.count })

        // ---- угрозы
        val ownPower = input.ownUnits.sumOf { power(it, input) }
        val threats = used.map { s -> threat(s, nowRound, book.age(s, nowRound), input, ownPower, visibleLevel, combos.filter { it.opponent == s.label }) }
            .sortedByDescending { it.score }

        // ---- контрмеры
        val counters = counters(input, used, fresh, demand, traitDemand, scarcity, combos, threats, visibleLevel, hiddenLevel, nowRound)

        return Analysis(nowRound, used, stale, demand, traitDemand, scarcity, combos, threats, counters, visibleLevel, notes)
    }

    private fun hiddenLevelFor(visible: Level, haveChance: Boolean): Level =
        if (!haveChance) visible else if (visible == Level.HIGH) Level.MEDIUM else Level.LOW

    private fun power(u: Seen, input: AdaptInput): Double {
        val cost = input.troops[u.troopId]?.cost?.takeIf { it > 0 } ?: 3
        var v = cost.toDouble()
        repeat((u.stars - 1).coerceIn(0, 2)) { v *= input.starFactor * input.starFactor }
        return v
    }

    private fun threat(s: Snapshot, nowRound: Int, age: Int, input: AdaptInput, ownPower: Double, vis: Level, myCombos: List<Combo>): Threat {
        val reasons = ArrayList<String>()
        var score = 0.0
        val known = s.known
        val power = known.sumOf { power(it, input) }
        if (ownPower > 0.0 && power > 0.0) {
            val ratio = power / ownPower
            val txt = "суммарная сила выставленных войск ${num(ratio)}× от вашей (оценка по цене и звёздам, предположение)"
            reasons += txt
            score += (ratio - 1.0).coerceIn(-0.5, 1.5) * 2.0
        } else if (power > 0.0) reasons += "сравнить силу не с чем: ваши войска на поле неизвестны"
        val threeStar = known.count { it.stars >= 3 }
        if (threeStar > 0) { reasons += "3★ на поле: $threeStar"; score += 1.5 * threeStar }
        val twoStar = known.count { it.stars == 2 }
        if (twoStar > 0) { reasons += "2★ на поле: $twoStar"; score += 0.4 * twoStar }
        for (c in myCombos.filter { it.tier > 0 }) {
            val eff = effectText(c.trait, c.tier)
            reasons += "«${c.traitName}» ${c.count}, активен" + (eff?.let { ": $it" } ?: ": числовой эффект в приложении не смоделирован")
            score += 0.8 * c.tier
        }
        val closing = myCombos.filter { it.tier == 0 && it.missing == 1 }
        if (closing.isNotEmpty()) reasons += "рядом с порогом: " + closing.joinToString { "«${it.traitName}»" }
        val ranged = known.count { (input.radii[it.troopId] ?: 0) >= 2 }
        val melee = known.count { (input.radii[it.troopId] ?: 99) <= 1 }
        if (ranged + melee > 0 && ranged + melee >= known.size / 2) {
            reasons += "по заданным радиусам: дальних $ranged, ближних $melee"
            if (ranged >= 3) score += 0.5
        }
        if (s.unknownCount > 0) reasons += "неопознанных юнитов: ${s.unknownCount} (не учтены)"
        val lvl = when {
            age > 2 -> Level.LOW
            s.unknownCount * 4 > s.units.size -> Level.LOW
            else -> vis
        }
        val level = when {
            score >= 4.0 -> ThreatLevel.HIGH
            score >= 1.5 -> ThreatLevel.MEDIUM
            else -> ThreatLevel.LOW
        }
        return Threat(s.label, level, score, reasons, lvl, age)
    }

    /** Эффект активного порога из traits.json, если он смоделирован в приложении. */
    private fun effectText(trait: String, tier: Int): String? = when (trait) {
        "noble" -> pick(listOf("+15% урона и −15% полученного", "+30% / −30%", "+60% / −60%"), tier)
        "forest" -> pick(listOf("+15% скорости ударов", "+30%", "+60%"), tier)
        "clan" -> pick(listOf("лечение 30% и ускорение при HP < 50%", "50%", "100%"), tier)
        "boss" -> pick(listOf("+25% HP боссам", "+50%", "+100%", "+300%"), tier)
        "undead" -> pick(listOf("+1% HP и урона за павшего союзника", "+2%", "+4%"), tier)
        else -> null
    }

    private fun pick(l: List<String>, tier: Int) = l.getOrNull((tier - 1).coerceIn(0, l.size - 1))

    private fun counters(
        input: AdaptInput, used: List<Snapshot>, fresh: List<Snapshot>, demand: List<Demand>, traitDemand: List<Pair<String, Int>>,
        scarcity: List<Scarcity>, combos: List<Combo>, threats: List<Threat>, vis: Level, hidden: Level, nowRound: Int,
    ): List<Counter> {
        val out = ArrayList<Counter>()
        if (fresh.size < used.size || fresh.isEmpty()) {
            out += Counter(CounterKind.REFRESH, "Обновите составы: часть снимков старше ${input.params.freshRounds} раунд(ов).", "возраст снимков", Level.HIGH)
        }
        val traitName = { id: String -> input.traits.firstOrNull { it.id == id }?.name ?: id }
        val mine = { id: String -> id in input.buildTroops || input.troops[id]?.trait?.let { it in input.buildTraits } == true }

        // Конкуренция за карты нашей сборки
        for (sc in scarcity.filter { it.own > 0 || mine(it.troopId) }.take(4)) {
            val opp = demand.firstOrNull { it.troopId == sc.troopId }?.opponents ?: 0
            if (opp == 0) continue
            val tight = sc.stock == Stock.SCARCE || sc.stock == Stock.TIGHT
            if (mine(sc.troopId) || sc.own > 0) {
                if (tight || opp >= 2) out += Counter(
                    CounterKind.PAIR_NOW,
                    "«${sc.name}»: её выставили $opp соперн. Если она нужна, докупайте пару сразу, как увидите.",
                    sc.text, if (sc.stock == Stock.UNKNOWN) Level.LOW else sc.level,
                )
            } else if (tight) out += Counter(CounterKind.AVOID, "Не рассчитывайте дособирать «${sc.name}»: карту собирают соперники.", sc.text, sc.level)
        }
        for (sc in scarcity.filter { it.own == 0 && !mine(it.troopId) && (it.stock == Stock.SCARCE || it.stock == Stock.TIGHT) }.take(2)) {
            if (out.none { it.text.contains("«${sc.name}»") }) out += Counter(CounterKind.AVOID, "Не рассчитывайте дособирать «${sc.name}»: карту собирают соперники.", sc.text, sc.level)
        }
        // Свободные направления: свойства, которых нет у свежих соперников, а у нас есть хотя бы одно войско
        val have = input.ownCopies.filter { it.value > 0 }.keys.mapNotNull { input.troops[it]?.trait?.takeIf { t -> t.isNotEmpty() } }.toSet()
        val crowd = traitDemand.toMap()
        val free = have.filter { (crowd[it] ?: 0) == 0 && input.traits.firstOrNull { c -> c.id == it }?.legacy != true }
        if (free.isNotEmpty() && fresh.isNotEmpty()) out += Counter(
            CounterKind.OPEN_LANE,
            "Свободное направление: " + free.joinToString { "«${traitName(it)}»" } + " — среди видимых составов этого свойства нет, меньше конкуренции за карты.",
            "видимые снимки; скрытые карты соперников неизвестны", if (vis == Level.HIGH) Level.MEDIUM else Level.LOW,
        )
        // Перехват
        val contested = scarcity.firstOrNull { (demand.firstOrNull { d -> d.troopId == it.troopId }?.opponents ?: 0) >= 2 && it.own == 0 }
        if (contested != null) out += Counter(
            CounterKind.DENY,
            "Если нужной карты нет, а эликсир лишний, можно взять «${contested.name}»: её собирают минимум двое. Выгода не проверена, цена — потраченный эликсир.",
            contested.text, Level.LOW,
        )
        // Расстановка
        val top = threats.firstOrNull()
        if (top != null) {
            val snap = used.firstOrNull { it.label == top.opponent }
            val ranged = snap?.known?.count { (input.radii[it.troopId] ?: 0) >= 2 } ?: 0
            val melee = snap?.known?.count { (input.radii[it.troopId] ?: 99) <= 1 } ?: 0
            if (ranged >= 3 && ranged > melee) out += Counter(
                CounterKind.FORMATION,
                "У «${top.opponent}» дальних бойцов $ranged (по вашим радиусам): держите быстрых рейдеров и ближний бой так, чтобы они быстро сходились с его тылом.",
                "радиусы заданы вами; расчёт позиций — на «Тактической карте»", Level.LOW,
            ) else if (melee >= 4 && melee > ranged) out += Counter(
                CounterKind.FORMATION,
                "У «${top.opponent}» ближних бойцов $melee: ставьте дальних за фронтом, чтобы они били, пока враг идёт.",
                "радиусы заданы вами; расчёт позиций — на «Тактической карте»", Level.LOW,
            )
            val active = combos.filter { it.opponent == top.opponent && it.tier > 0 }
            if (active.isNotEmpty()) out += Counter(
                CounterKind.NO_DATA,
                "У «${top.opponent}» активно: " + active.joinToString { "«${it.traitName}»" } + ". Проверенных по игре контрмер на эти свойства в базе нет — приложение их не придумывает.",
                "traits.json содержит только описания свойств", Level.HIGH,
            )
        }
        return out
    }

    fun fmt(d: Double): String = (Math.round(d * 10.0) / 10.0).toString().replace('.', ',')
    private fun num(d: Double) = (Math.round(d * 100.0) / 100.0).toString().replace('.', ',')
    private fun pctText(d: Double): String = "${Math.round(min(1.0, d) * 100.0)}%"

    /**
     * Поправки для покупки в боте: id войска → прибавка к оценке сценария (те же единицы, что в [com.mergetacticsai.strategy.AdaptivePlanner]).
     * Берутся только при средней и высокой уверенности и только для карт вне сборки: ослабляем карты, за которые идёт борьба.
     * Поправка маленькая и ограничена: заведомо не отменяет слияние и сборку.
     */
    fun buyBias(a: Analysis, input: AdaptInput): Map<String, Double> {
        if (a.level == Level.NONE || a.level == Level.LOW) return emptyMap()
        val out = HashMap<String, Double>()
        for (sc in a.scarcity) {
            val id = sc.troopId
            if (id in input.buildTroops || input.troops[id]?.trait?.let { it in input.buildTraits } == true) continue
            if (sc.own > 0) continue
            val opp = a.demand.firstOrNull { it.troopId == id }?.opponents ?: 0
            if (opp >= 2 && (sc.stock == Stock.SCARCE || sc.stock == Stock.TIGHT)) out[id] = -MAX_BIAS
            else if (opp >= 1 && sc.stock == Stock.SCARCE) out[id] = -MAX_BIAS / 2
        }
        return out
    }

    const val MAX_BIAS = 0.1
}

private object SourceHint {
    fun scanOnly(s: Set<Source>) = s.isNotEmpty() && s.all { it == Source.SCAN }
}
