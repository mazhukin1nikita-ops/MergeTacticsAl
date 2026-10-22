package com.mergetacticsai.decision

/**
 * Запись о решении: что было на поле и в магазине, кого рассматривали, сколько стоила продажа и покупка,
 * ценность до и после, потерянные и полученные свойства, выбранное действие, причина, уверенность,
 * итог выполнения и результат раунда.
 * [executed]: PLANNED — решено, итога нет; DONE — выполнено; FAILED — не получилось; "" — ничего не делали.
 * [result]: WIN, LOSS или пусто — результат раунда бот сам не распознаёт, его отмечает пользователь.
 */
data class DecisionRecord(
    val id: Long = 0,
    val at: Long,
    val session: String,
    val round: Int?,
    val fieldState: String,
    val shopState: String,
    val elixir: Int?,
    val saleConsidered: String,
    val buyConsidered: String,
    val saleValue: Int,
    val buyCost: Int,
    val valueBefore: Double,
    val valueAfter: Double,
    val lostSynergies: String,
    val gainedSynergies: String,
    val action: String,
    val reason: String,
    val confidence: Double,
    val executed: String = "",
    val executedNote: String = "",
    val result: String = "",
    /** Войска на поле в момент решения через запятую: по ним [ValueLearning] связывает состав с результатом. */
    val fieldTroops: String = "",
)

interface DecisionLog {
    /** Возвращает id или −1: запись не должна ломать бота. */
    fun add(r: DecisionRecord): Long
    fun setExecution(id: Long, status: String, note: String)
    /** Ставит результат всем решениям раунда. Возвращает, сколько записей обновлено. */
    fun setResult(session: String, round: Int, result: String): Int
    /** Новые записи первыми. */
    fun recent(limit: Int): List<DecisionRecord>
    /** Записи, у которых известен результат раунда (для обучения). */
    fun withResults(limit: Int): List<DecisionRecord>
    fun count(): Int
    fun clear()
}

class MemoryDecisionLog : DecisionLog {
    private val items = ArrayList<DecisionRecord>()
    private var next = 1L

    @Synchronized override fun add(r: DecisionRecord): Long { val id = next++; items += r.copy(id = id); return id }

    @Synchronized override fun setExecution(id: Long, status: String, note: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(executed = status, executedNote = note)
    }

    @Synchronized override fun setResult(session: String, round: Int, result: String): Int {
        var n = 0
        for (i in items.indices) {
            val it = items[i]
            if (it.session == session && it.round == round) { items[i] = it.copy(result = result); n++ }
        }
        return n
    }

    @Synchronized override fun recent(limit: Int): List<DecisionRecord> = items.asReversed().take(limit)
    @Synchronized override fun withResults(limit: Int): List<DecisionRecord> = items.asReversed().filter { it.result.isNotEmpty() }.take(limit)
    @Synchronized override fun count(): Int = items.size
    @Synchronized override fun clear() { items.clear() }
}

/** Пишет решения в [DecisionLog]: не чаще раза в [quietMs] для одного и того же состояния, никогда не бросает исключений. */
class DecisionRecorder(
    private val log: DecisionLog?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val quietMs: Long = 15_000L,
) {
    private var session = clock().toString()
    private var round: Int? = null
    private val lastAt = HashMap<String, Long>()

    fun newSession() { session = clock().toString(); round = null; lastAt.clear() }
    fun setRound(r: Int?) { round = r }
    val currentRound: Int? get() = round

    private fun pieceText(p: Piece): String = p.key + ":" + (p.troopId ?: "?") + "★" + p.stars + (p.cost?.let { "($it)" } ?: "")
    private fun offerText(o: Offer): String =
        "К" + (o.index + 1) + ":" + (o.troopId ?: "?") + "(" + (o.cost ?: "?") + (if (o.affordable) ",доступна" else ",дорого") + (if (o.arrow) ",слияние" else "") + ")"

    /** Записывает решение. Возвращает id или −1 (повтор, база выключена или ошибка записи). */
    fun record(s: Snapshot, d: Decision): Long {
        val l = log ?: return -1L
        val now = clock()
        val field = s.pieces.sortedWith(compareBy({ it.where.ordinal }, { it.index })).joinToString(";") { pieceText(it) }
        val shop = s.offers.sortedBy { it.index }.joinToString(";") { offerText(it) }
        val key = d.kind.name + "|" + field + "|" + shop + "|" + s.elixir + "|" + d.sales.joinToString { it.piece.key } + "|" + (d.buy?.index ?: -1)
        val prev = lastAt[key]
        if (prev != null && now - prev < quietMs) return -1L
        lastAt[key] = now
        val action = when (d.kind) {
            ActionKind.NONE -> "ничего не менять"
            ActionKind.BUY -> "купить К" + ((d.buy?.index ?: 0) + 1)
            ActionKind.SELL -> "продать " + d.sales.joinToString(", ") { it.piece.key }
            ActionKind.SELL_AND_BUY -> "продать " + d.sales.joinToString(", ") { it.piece.key } + " и купить К" + ((d.buy?.index ?: 0) + 1)
        }
        val rec = DecisionRecord(
            at = now, session = session, round = round,
            fieldState = field, shopState = shop, elixir = s.elixir,
            saleConsidered = d.saleCandidates.joinToString("; "), buyConsidered = d.buyCandidates.joinToString("; "),
            saleValue = d.sales.sumOf { it.refund }, buyCost = d.buy?.cost ?: 0,
            valueBefore = d.before.total, valueAfter = d.after?.total ?: d.before.total,
            lostSynergies = d.lostTiers.joinToString(), gainedSynergies = d.gainedTiers.joinToString(),
            action = action, reason = d.reason + (if (d.lines.isEmpty()) "" else " | варианты: " + d.lines.joinToString(" / ")),
            confidence = d.confidence,
            executed = if (d.kind == ActionKind.NONE) "" else "PLANNED",
            fieldTroops = s.pieces.filter { it.where == Where.FIELD }.mapNotNull { it.troopId }.joinToString(","),
        )
        return runCatching { l.add(rec) }.getOrDefault(-1L)
    }

    fun outcome(id: Long, ok: Boolean, note: String) {
        if (id < 0) return
        runCatching { log?.setExecution(id, if (ok) "DONE" else "FAILED", note) }
    }

    /** Пользователь отметил результат последнего раунда. Возвращает число обновлённых записей. */
    fun markResult(win: Boolean): Int {
        val r = round ?: return 0
        return runCatching { log?.setResult(session, r, if (win) "WIN" else "LOSS") ?: 0 }.getOrDefault(0)
    }
}

/**
 * Обучение по итогам боёв. Берёт раунды с известным результатом, для каждого войска считает долю побед в раундах,
 * где оно стояло на поле, сравнивает с общей долей и сдвигает его ценность не больше чем на ±[MAX_SHIFT].
 * Мало данных — множитель 1,0. Это статистика совпадений, а не доказательство причины.
 */
object ValueLearning {
    const val MIN_ROUNDS = 6
    const val MIN_SAMPLES = 4
    const val PRIOR = 4.0
    const val MAX_SHIFT = 0.15

    fun multipliers(records: List<DecisionRecord>): Map<String, Double> {
        // Один раунд — одна запись: берём состав из самого позднего решения раунда.
        val byRound = HashMap<String, DecisionRecord>()
        for (r in records) {
            if (r.result.isEmpty() || r.round == null) continue
            val key = r.session + "#" + r.round
            val cur = byRound[key]
            if (cur == null || r.at > cur.at || (r.at == cur.at && r.id > cur.id)) byRound[key] = r
        }
        val rounds = byRound.values.toList()
        if (rounds.size < MIN_ROUNDS) return emptyMap()
        val base = rounds.count { it.result == "WIN" }.toDouble() / rounds.size
        val seen = HashMap<String, Int>()
        val wins = HashMap<String, Int>()
        for (r in rounds) {
            for (id in r.fieldTroops.split(",").filter { it.isNotEmpty() }.toSet()) {
                seen[id] = (seen[id] ?: 0) + 1
                if (r.result == "WIN") wins[id] = (wins[id] ?: 0) + 1
            }
        }
        val out = LinkedHashMap<String, Double>()
        for (id in seen.keys.sorted()) {
            val n = seen.getValue(id)
            if (n < MIN_SAMPLES) continue
            val rate = ((wins[id] ?: 0) + PRIOR * base) / (n + PRIOR)
            out[id] = 1.0 + ((rate - base) * 0.5).coerceIn(-MAX_SHIFT, MAX_SHIFT)
        }
        return out
    }
}
