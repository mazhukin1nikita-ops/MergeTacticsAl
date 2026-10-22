package com.mergetacticsai.journal

/**
 * Запись журнала: что произошло, почему и чем кончилось.
 * kind: BUY — покупка, SAVE — копим эликсир, ABANDON — отказ от улучшения, PIVOT — смена сборки,
 * SELL — продажа, ADAPT — анализ соперников, EVENT — прочее.
 * status: PLANNED — решено, результата ещё нет; DONE — выполнено; FAILED — не получилось; INFO — справочная запись.
 * [details] — подробности (сценарии, факторы, уровни уверенности) обычным текстом, по строке на пункт.
 */
data class JournalEntry(
    val id: Long = 0,
    val at: Long,
    val session: String,
    val round: Int?,
    val kind: String,
    val title: String,
    val reason: String,
    val details: String = "",
    val status: String = "INFO",
    val outcome: String = "",
    val outcomeAt: Long? = null,
)

interface DecisionJournal {
    /** Возвращает id записи или −1, если записать не удалось. Журнал не должен ломать работу бота. */
    fun add(e: JournalEntry): Long
    fun setOutcome(id: Long, status: String, outcome: String, at: Long)
    /** Новые записи первыми. */
    fun recent(limit: Int): List<JournalEntry>
    fun count(): Int
    fun clear()
}

/** Журнал в памяти: для тестов и на случай, когда база недоступна. */
class MemoryJournal : DecisionJournal {
    private val items = ArrayList<JournalEntry>()
    private var next = 1L

    @Synchronized override fun add(e: JournalEntry): Long {
        val id = next++
        items += e.copy(id = id)
        return id
    }

    @Synchronized override fun setOutcome(id: Long, status: String, outcome: String, at: Long) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(status = status, outcome = outcome, outcomeAt = at)
    }

    @Synchronized override fun recent(limit: Int): List<JournalEntry> = items.asReversed().take(limit)
    @Synchronized override fun count(): Int = items.size
    @Synchronized override fun clear() { items.clear() }
}

/**
 * Обёртка для бота: задаёт сессию, не пишет одно и то же решение чаще раза в [quietMs]
 * и никогда не бросает исключения наружу.
 */
class JournalWriter(
    private val journal: DecisionJournal?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val quietMs: Long = 15_000L,
) {
    private var session = clock().toString()
    private var round: Int? = null
    private val lastAt = HashMap<String, Long>()

    fun newSession() { session = clock().toString(); round = null; lastAt.clear() }
    fun setRound(r: Int?) { round = r }

    /**
     * Записывает решение. [dedupKey] — одинаковые ключи не пишутся чаще, чем раз в [quietMs]
     * (нужно, потому что бот пересчитывает решение много раз в секунду). Возвращает id или −1.
     */
    fun log(kind: String, title: String, reason: String, details: String = "", status: String = "INFO", dedupKey: String? = null): Long {
        val j = journal ?: return -1L
        val now = clock()
        if (dedupKey != null) {
            val prev = lastAt[dedupKey]
            if (prev != null && now - prev < quietMs) return -1L
            lastAt[dedupKey] = now
        }
        return runCatching {
            j.add(JournalEntry(at = now, session = session, round = round, kind = kind, title = title, reason = reason, details = details, status = status))
        }.getOrDefault(-1L)
    }

    fun outcome(id: Long, ok: Boolean, text: String) {
        if (id < 0) return
        runCatching { journal?.setOutcome(id, if (ok) "DONE" else "FAILED", text, clock()) }
    }
}
