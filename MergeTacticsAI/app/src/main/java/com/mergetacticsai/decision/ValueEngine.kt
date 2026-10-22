package com.mergetacticsai.decision

import com.mergetacticsai.strategy.AdaptivePlanner
import com.mergetacticsai.strategy.Build
import com.mergetacticsai.strategy.CycleStrategy
import com.mergetacticsai.strategy.Env
import com.mergetacticsai.strategy.StarPower
import com.mergetacticsai.strategy.TroopPolicy

enum class Where { FIELD, BENCH }

/**
 * Одно наше войско. [index] — клетка в HexGrid (поле) или слот скамейки.
 * [identified] — название и цена известны: только такие можно продавать.
 * Неопознанные войска считаются в ценности состава, но бот их не трогает.
 */
data class Piece(
    val where: Where,
    val index: Int,
    val troopId: String?,
    val trait: String?,
    val cost: Int?,
    val stars: Int = 1,
    val onPlannedCell: Boolean = false,
    val hasPlannedCell: Boolean = false,
) {
    val key: String get() = (if (where == Where.FIELD) "F" else "B") + index
    val identified: Boolean get() = troopId != null && !troopId.startsWith("trait:") && cost != null
    /** Сколько копий 1★ вложено (слияние по два: 2★ = 2 копии, 3★ = 4 копии). */
    val copies: Int get() = when { stars >= 3 -> 4; stars == 2 -> 2; else -> 1 }
}

/** Карточка магазина. */
data class Offer(
    val index: Int,
    val troopId: String?,
    val trait: String?,
    val cost: Int?,
    val affordable: Boolean,
    val arrow: Boolean,
    val allowed: Boolean,
)

/**
 * Всё, что бот знает перед решением. [elixir] == null — эликсир не прочитан: продажи ради покупки тогда не считаем.
 * [fieldLimit] == null — вместимость поля неизвестна. [fieldUnknown] — занятые клетки, чьё войско бот не знает.
 */
class Snapshot(
    val pieces: List<Piece>,
    val offers: List<Offer>,
    val elixir: Int?,
    val benchSlots: Int,
    val fieldLimit: Int?,
    val fieldUnknown: Int,
    val build: Build,
    val env: Env,
    val traitTiers: (String) -> List<Int> = { TroopPolicy.thresholds(it) },
    /** Относительная сила войска по введённым HP, урону и скорости ударов; пусто — считаем по цене. */
    val statPower: Map<String, Double> = emptyMap(),
    /** Поправки анализа соперников: id войска → прибавка к силе (−0,1 = −10%). */
    val bias: Map<String, Double> = emptyMap(),
    /** Множители, накопленные по итогам прошлых боёв (см. [ValueLearning]). */
    val learned: Map<String, Double> = emptyMap(),
    /** Ключи войск ([Piece.key]), которые сейчас нельзя продавать (например, продажа с поля не удалась три раза). */
    val noSell: Set<String> = emptySet(),
    /** Счётчик поля не прочитан, но попытка поставить воина уже упиралась в полное поле. */
    val fieldFullHint: Boolean = false,
    /** Эликсир за каждое слияние (Императрица духов и т.п.). */
    val mergeBonus: Int = 0,
    /** Добавки к числу войск свойства (Королева гоблинов, Королева гробницы). */
    val extraTraitCounts: Map<String, Int> = emptyMap(),
)

/** Числа модели. Это допущения, проверенных значений для них в игре нет — кроме правила продажи. */
data class ValueParams(
    val power: StarPower = StarPower(),
    /** Сила 1★ войска = 1 + шаг × (цена − 1). */
    val costPowerStep: Double = 0.3,
    /** Сколько «силы» стоит один эликсир. Чем больше, тем неохотнее бот тратит. */
    val elixirPower: Double = 0.15,
    /** Какая доля силы войска со скамейки считается запасом (на поле ему пока нет места). */
    val benchShare: Double = 0.3,
    val buildBoost: Double = 1.3,
    val offBuildFactor: Double = 0.6,
    val planBoost: Double = 1.05,
    /** Во сколько раз меньше ценится шанс дособрать слияние у войска вне сборки (когда сборка задана). */
    val offBuildPotential: Double = 0.4,
    val synergyPerTier: Double = 0.2,
    /** Доля ценности будущего слияния, которую мы засчитываем уже сейчас. */
    val potentialShare: Double = 0.5,
    /** Минимальная выгода для покупки без продажи. */
    val minGainBuy: Double = 0.1,
    /** Минимальная выгода, если в варианте есть продажа: продажа рискованнее. */
    val minGainSale: Double = 0.2,
    val maxSales: Int = 2,
    /** Правило продажи из assets/game_rules.json: возврат = вложено − [sellMinus] (проверено). */
    val sellMinus: Int = 1,
    /** Во сколько раз относительная сила по введённым HP и урону переводится в шкалу «цена войска». */
    val statScale: Double = 1.5,
)

enum class ActionKind { NONE, BUY, SELL, SELL_AND_BUY }

class Sale(val piece: Piece, val refund: Int)

/** Разбивка оценки состава. */
data class Breakdown(
    val field: Double,
    val bench: Double,
    val potential: Double,
    val elixir: Double,
    val total: Double,
    val merges: Int,
    /** Активные уровни свойств: свойство → уровень. */
    val tiers: Map<String, Int>,
)

class Option(
    val kind: ActionKind,
    val sales: List<Sale>,
    val buy: Offer?,
    val after: Breakdown,
    val net: Double,
    val elixirAfter: Int,
    val lostTiers: List<String>,
    val gainedTiers: List<String>,
    val note: String,
)

class Decision(
    val kind: ActionKind,
    val sales: List<Sale>,
    val buy: Offer?,
    val reason: String,
    /** 0..1: насколько выгода превышает порог и оторвалась от второго варианта. */
    val confidence: Double,
    val before: Breakdown,
    val after: Breakdown?,
    val options: List<Option>,
    val lostTiers: List<String>,
    val gainedTiers: List<String>,
    val saleCandidates: List<String>,
    val buyCandidates: List<String>,
    val lines: List<String>,
)

/**
 * Единая логика решений о покупке, продаже и замене. Чистый Kotlin, без случайности и без часов:
 * одно и то же состояние всегда даёт одно и то же решение.
 *
 * Идея: считаем ценность состава «до» и «после» для каждого варианта и выбираем тот, где прирост наибольший
 * и выше порога. Ценность состава = сила войск на поле (цена, звёзды, сборка, карта, синергии свойств)
 * + запас на скамейке + шанс дособрать слияния + эликсир. Продажа возвращает «вложено − 1» (правило игры),
 * но сама по себе ценность не растёт: она оправдана только покупкой или освобождением места для лучшего бойца.
 */
object ValueEngine {
    private const val DEFAULT_COST = 3

    fun refund(piece: Piece, p: ValueParams): Int = ((piece.cost ?: 0) * piece.copies - p.sellMinus).coerceAtLeast(0)

    private fun unitPower(cost: Int?, p: ValueParams): Double = 1.0 + p.costPowerStep * ((cost ?: DEFAULT_COST) - 1).coerceAtLeast(0)

    private fun statsUsable(s: Snapshot): Boolean {
        if (s.statPower.isEmpty()) return false
        val ids = s.pieces.mapNotNull { it.troopId } + s.offers.mapNotNull { it.troopId }
        return ids.isNotEmpty() && ids.all { s.statPower[it] != null }
    }

    private fun basePower(troopId: String?, cost: Int?, stars: Int, s: Snapshot, p: ValueParams, useStats: Boolean): Double {
        val unit = if (useStats && troopId != null) (s.statPower[troopId] ?: 1.0) * p.statScale else unitPower(cost, p)
        return unit * p.power.of(stars.coerceIn(1, 3))
    }

    private fun factor(piece: Piece, s: Snapshot, p: ValueParams): Double {
        var f = 1.0
        if (s.build.found) f *= if (CycleStrategy.inBuild(piece.trait, piece.troopId, s.build)) p.buildBoost else p.offBuildFactor
        if (piece.onPlannedCell || piece.hasPlannedCell) f *= p.planBoost
        val id = piece.troopId
        if (id != null) {
            f *= (1.0 + (s.bias[id] ?: 0.0)).coerceIn(0.5, 1.5)
            f *= (s.learned[id] ?: 1.0).coerceIn(0.8, 1.2)
        }
        return f
    }

    private fun tierOf(trait: String, count: Int, s: Snapshot): Int = s.traitTiers(trait).count { count >= it }

    /** Объединяет пары: две одинаковые звёздности одного войска дают +1★. Побеждает копия на поле. Возвращает (новый список, число слияний). */
    fun autoMerge(pieces: List<Piece>): Pair<List<Piece>, Int> {
        val list = pieces.toMutableList()
        var merges = 0
        var changed = true
        while (changed) {
            changed = false
            val groups = list.filter { it.troopId != null && !it.troopId.startsWith("trait:") && it.stars < 3 }
                .groupBy { it.troopId + "#" + it.stars }
            for (key in groups.keys.sorted()) {
                val g = groups.getValue(key)
                if (g.size < 2) continue
                val ordered = g.sortedWith(compareBy({ if (it.where == Where.FIELD) 0 else 1 }, { it.index }))
                val keep = ordered[0]
                val gone = ordered[1]
                list.remove(gone)
                val at = list.indexOf(keep)
                list[at] = keep.copy(stars = keep.stars + 1, onPlannedCell = keep.onPlannedCell || gone.onPlannedCell)
                merges++
                changed = true
                break
            }
        }
        return list to merges
    }

    /** Свободные места на поле заполняются лучшими бойцами со скамейки. */
    private fun fill(pieces: List<Piece>, s: Snapshot, p: ValueParams, useStats: Boolean): List<Piece> {
        val limit = s.fieldLimit ?: return pieces
        val onField = pieces.count { it.where == Where.FIELD }
        var free = limit - onField - s.fieldUnknown
        if (free <= 0) return pieces
        val bench = pieces.filter { it.where == Where.BENCH }
            .sortedWith(compareBy<Piece>({ -basePower(it.troopId, it.cost, it.stars, s, p, useStats) * factor(it, s, p) }, { it.troopId ?: "" }, { it.index }))
        val move = bench.take(free).toSet()
        return pieces.map { if (it in move) it.copy(where = Where.FIELD) else it }
    }

    private fun traitCounts(field: List<Piece>, s: Snapshot): Map<String, Int> {
        val seen = HashSet<String>()
        val out = HashMap<String, Int>()
        for (pc in field) {
            val t = pc.trait?.takeIf { it.isNotEmpty() } ?: continue
            val id = pc.troopId ?: continue
            if (seen.add(t + "#" + id)) out[t] = (out[t] ?: 0) + 1
        }
        for ((t, n) in s.extraTraitCounts) out[t] = (out[t] ?: 0) + n
        return out
    }

    /** Ценность состава. [elixir] — сколько эликсира останется. */
    fun value(pieces: List<Piece>, elixir: Int, s: Snapshot, p: ValueParams, merges: Int = 0): Breakdown {
        val useStats = statsUsable(s)
        val placed = fill(pieces, s, p, useStats)
        val field = placed.filter { it.where == Where.FIELD }
        val bench = placed.filter { it.where == Where.BENCH }
        val counts = traitCounts(field, s)
        val tiers = HashMap<String, Int>()
        for ((t, n) in counts) { val k = tierOf(t, n, s); if (k > 0) tiers[t] = k }
        var f = 0.0
        for (pc in field) {
            val syn = 1.0 + p.synergyPerTier * (pc.trait?.let { tiers[it] } ?: 0)
            f += basePower(pc.troopId, pc.cost, pc.stars, s, p, useStats) * factor(pc, s, p) * syn
        }
        var b = 0.0
        for (pc in bench) b += basePower(pc.troopId, pc.cost, pc.stars, s, p, useStats) * factor(pc, s, p) * p.benchShare
        val pot = potential(placed, s, p, useStats)
        val e = elixir.toDouble() * p.elixirPower + merges.toDouble() * s.mergeBonus * p.elixirPower
        return Breakdown(f, b, pot, e, f + b + pot + e, merges, tiers)
    }

    /** Шанс дособрать следующую звезду для войск, у которых есть копии, но пары ещё нет. */
    private fun potential(pieces: List<Piece>, s: Snapshot, p: ValueParams, useStats: Boolean): Double {
        var sum = 0.0
        val groups = pieces.filter { it.troopId != null && !it.troopId.startsWith("trait:") && it.stars < 3 }.groupBy { it.troopId }
        for (id in groups.keys.sortedBy { it ?: "" }) {
            val g = groups.getValue(id)
            val ones = g.count { it.stars == 1 }
            val twos = g.count { it.stars == 2 }
            val equiv = ones + 2 * twos
            val need = ((if (twos > 0) 4 else 2) - equiv).coerceAtLeast(0)
            if (need <= 0) continue
            val head = g.first()
            val cost = head.cost ?: DEFAULT_COST
            val pf = AdaptivePlanner.pFinish(need, cost, s.env, AdaptivePlanner.budget(s.env))
            val cur = if (twos > 0) 2 else 1
            val gain = (p.power.of(cur + 1) - p.power.of(cur)) * basePower(head.troopId, head.cost, 1, s, p, useStats) * factor(head, s, p)
            val w = if (!s.build.found || CycleStrategy.inBuild(head.trait, head.troopId, s.build)) 1.0 else p.offBuildPotential
            sum += pf * gain * p.potentialShare * w
        }
        return sum
    }

    private fun benchFree(pieces: List<Piece>, s: Snapshot): Int = s.benchSlots - pieces.count { it.where == Where.BENCH }

    private fun offerPiece(o: Offer): Piece = Piece(Where.BENCH, 99, o.troopId, o.trait, o.cost, 1)

    private fun tierDiff(before: Map<String, Int>, after: Map<String, Int>): Pair<List<String>, List<String>> {
        val lost = ArrayList<String>()
        val gained = ArrayList<String>()
        for (t in (before.keys + after.keys).toSortedSet()) {
            val a = before[t] ?: 0
            val b = after[t] ?: 0
            if (b < a) lost += "$t $a→$b"
            if (b > a) gained += "$t $a→$b"
        }
        return lost to gained
    }

    private fun combos(items: List<Piece>, maxSize: Int): List<List<Piece>> {
        val out = ArrayList<List<Piece>>()
        for (i in items.indices) {
            out += listOf(items[i])
            if (maxSize >= 2) for (j in i + 1 until items.size) out += listOf(items[i], items[j])
        }
        return out
    }

    /**
     * Просматривает все варианты: ничего, купить карточку, продать (одного-двух), продать и купить.
     * Выбирает вариант с наибольшим приростом ценности, если он выше порога; иначе ничего не меняет.
     */
    fun decide(s: Snapshot, p: ValueParams = ValueParams()): Decision {
        val elixir = s.elixir
        val (mergedNow, mergesNow) = autoMerge(s.pieces)
        val before = value(mergedNow, elixir ?: 0, s, p, mergesNow)

        val sellable = s.pieces.filter { it.identified && it.key !in s.noSell }.sortedWith(compareBy({ it.where.ordinal }, { it.index }))
        val buyable = s.offers.filter { it.allowed && (it.cost != null || it.affordable) }.sortedBy { it.index }
        val options = ArrayList<Option>()

        fun addOption(sales: List<Sale>, buy: Offer?) {
            val soldKeys = sales.map { it.piece.key }.toSet()
            val kept = s.pieces.filter { it.key !in soldKeys }
            val refund = sales.sumOf { it.refund }
            val cost = buy?.cost ?: if (buy != null) DEFAULT_COST else 0
            val have = (elixir ?: 0) + refund
            if (buy != null) {
                // Без продажи карточка должна быть доступна на экране: тап по затемнённой ничего не купит.
                if (sales.isEmpty() && !buy.affordable) return
                if (elixir == null) {
                    if (sales.isNotEmpty()) return
                } else if (have < cost) return
            }
            val withBuy = if (buy != null) kept + offerPiece(buy) else kept
            if (buy != null) {
                val freeBench = benchFree(kept, s)
                val completes = buy.troopId != null && kept.any { it.troopId == buy.troopId && it.stars == 1 }
                if (freeBench <= 0 && !completes && !buy.arrow) return
            }
            val (merged, merges) = autoMerge(withBuy)
            val left = (elixir ?: 0) + refund - cost
            val after = value(merged, left.coerceAtLeast(0), s, p, merges)
            val (lost, gained) = tierDiff(before.tiers, after.tiers)
            val kind = when {
                buy != null && sales.isNotEmpty() -> ActionKind.SELL_AND_BUY
                buy != null -> ActionKind.BUY
                else -> ActionKind.SELL
            }
            options += Option(kind, sales, buy, after, after.total - before.total, left.coerceAtLeast(0), lost, gained, "")
        }

        for (o in buyable) addOption(emptyList(), o)
        val saleSets = combos(sellable, p.maxSales.coerceIn(1, 3)).map { set -> set.map { Sale(it, refund(it, p)) } }
        for (set in saleSets) {
            addOption(set, null)
            if (elixir != null) for (o in buyable) addOption(set, o)
        }

        // Лучший вариант: больший прирост; при равенстве — меньше продаж, затем дешевле покупка, затем по порядку (детерминированно).
        val ranked = options.sortedWith(
            compareBy<Option>({ -it.net }, { it.sales.size }, { it.buy?.cost ?: 0 }, { it.buy?.index ?: -1 }, { it.sales.joinToString { x -> x.piece.key } }),
        )
        // Правило сборки: поле полное, воин из сборки ждёт (скамейка или магазин) — слабейший воин НЕ из сборки уходит
        // с поля независимо от порога. Воина из сборки за воина не из сборки не меняем никогда.
        val rule = ranked.firstOrNull { o -> isBuildReplacement(o, s, mergedNow) }
        val best = rule ?: ranked.firstOrNull { o -> o.net >= (if (o.sales.isEmpty()) p.minGainBuy else p.minGainSale) }
        val saleNames = sellable.map { it.key + ":" + (it.troopId ?: "?") + " (" + refund(it, p) + ")" }
        val buyNames = buyable.map { "К" + (it.index + 1) + ":" + (it.troopId ?: "?") + " (" + (it.cost ?: "?") + ")" }
        val lines = ranked.take(5).map { describe(it) }

        if (best == null) {
            val top = ranked.firstOrNull()
            val why = when {
                s.offers.none { it.allowed } && s.pieces.isEmpty() -> "нечего покупать и продавать"
                top == null -> "нет доступных вариантов: не хватает эликсира или места"
                else -> "лучший вариант даёт ${fmt(top.net)} при пороге ${fmt(if (top.sales.isEmpty()) p.minGainBuy else p.minGainSale)} — ничего не меняю"
            } + (replacementBlocker(s)?.let { "; замена по сборке не идёт: $it" } ?: "")
            val th0 = if (top == null || top.sales.isEmpty()) p.minGainBuy else p.minGainSale
            val noneConf = if (top == null) 1.0 else Math.round((1.0 - top.net / th0).coerceIn(0.0, 1.0) * 100.0) / 100.0
            return Decision(ActionKind.NONE, emptyList(), null, why, noneConf, before, null, ranked, emptyList(), emptyList(), saleNames, buyNames, lines)
        }
        val second = ranked.firstOrNull { it !== best }
        val th = if (best.sales.isEmpty()) p.minGainBuy else p.minGainSale
        val overThreshold = ((best.net - th) / (th * 4.0)).coerceIn(0.0, 1.0)
        val lead = if (second == null) 1.0 else ((best.net - second.net) / (Math.abs(best.net) + 0.05)).coerceIn(0.0, 1.0)
        val conf = Math.round((0.5 * overThreshold + 0.5 * lead) * 100.0) / 100.0
        return Decision(
            best.kind, best.sales, best.buy, explain(best, before), conf, before, best.after, ranked,
            best.lostTiers, best.gainedTiers, saleNames, buyNames, lines,
        )
    }

    private fun inBuild(trait: String?, troopId: String?, s: Snapshot) = s.build.found && CycleStrategy.inBuild(trait, troopId, s.build)

    /** Поле заполнено: по счётчику, с учётом неопознанных, либо по подсказке «не влезло». */
    private fun fieldFull(s: Snapshot): Boolean {
        val limit = s.fieldLimit ?: return s.fieldFullHint
        return s.pieces.count { it.where == Where.FIELD } + s.fieldUnknown >= limit
    }

    /** Есть воин сборки, который ждёт места: на скамейке или в магазине (доступен или станет доступен после продажи). */
    private fun buildWaiting(s: Snapshot, merged: List<Piece>): Boolean =
        merged.any { it.where == Where.BENCH && inBuild(it.trait, it.troopId, s) } ||
            s.offers.any { it.allowed && inBuild(it.trait, it.troopId, s) }

    /** Вариант «продать одного воина не из сборки с полного поля»: на его место встаёт воин сборки со скамейки или из магазина. */
    private fun isBuildReplacement(o: Option, s: Snapshot, merged: List<Piece>): Boolean {
        if (!s.build.found || !fieldFull(s) || o.sales.size != 1) return false
        val sold = o.sales[0].piece
        if (sold.where != Where.FIELD || inBuild(sold.trait, sold.troopId, s)) return false
        val buy = o.buy
        return if (buy != null) inBuild(buy.trait, buy.troopId, s)
        else merged.any { it.where == Where.BENCH && inBuild(it.trait, it.troopId, s) }
    }

    /** Почему замена по сборке не состоялась, если воин сборки ждёт места. null — причин не найдено или замена не нужна. */
    fun replacementBlocker(s: Snapshot): String? {
        if (!s.build.found) return "сборка не задана или не найдена"
        val (merged, _) = autoMerge(s.pieces)
        if (!buildWaiting(s, merged)) return null
        if (s.fieldLimit == null && !s.fieldFullHint) return "вместимость поля не прочитана"
        if (!fieldFull(s)) return null
        val offBuildField = s.pieces.filter { it.where == Where.FIELD && !inBuild(it.trait, it.troopId, s) }
        if (offBuildField.isEmpty()) return if (s.fieldUnknown > 0) "на поле ${s.fieldUnknown} неопознанных воинов: бот не знает, кто они, и их не трогает" else "на поле нет воинов не из сборки"
        if (offBuildField.none { it.identified }) return "воины не из сборки на поле неопознанны"
        if (offBuildField.filter { it.identified }.all { it.key in s.noSell }) return "продажа с поля временно заблокирована после неудач"
        return null
    }

    fun fmt(v: Double): String = (Math.round(v * 100.0) / 100.0).toString().replace('.', ',')

    private fun label(o: Option): String {
        val sold = o.sales.joinToString(", ") { it.piece.key + " «" + (it.piece.troopId ?: "?") + "» +" + it.refund }
        val buy = o.buy?.let { "К" + (it.index + 1) + " «" + (it.troopId ?: "?") + "» −" + (it.cost ?: "?") } ?: ""
        return when (o.kind) {
            ActionKind.BUY -> "купить $buy"
            ActionKind.SELL -> "продать $sold"
            ActionKind.SELL_AND_BUY -> "продать $sold, купить $buy"
            ActionKind.NONE -> "ничего"
        }
    }

    private fun describe(o: Option): String = label(o) + ": " + (if (o.net >= 0) "+" else "") + fmt(o.net) + ", эликсира останется " + o.elixirAfter

    private fun explain(o: Option, before: Breakdown): String {
        val syn = (if (o.gainedTiers.isEmpty()) "" else "; получаем свойства: " + o.gainedTiers.joinToString()) +
            (if (o.lostTiers.isEmpty()) "" else "; теряем свойства: " + o.lostTiers.joinToString())
        return "ценность состава ${fmt(before.total)} → ${fmt(o.after.total)} (${if (o.net >= 0) "+" else ""}${fmt(o.net)}): " + label(o) + syn
    }
}
