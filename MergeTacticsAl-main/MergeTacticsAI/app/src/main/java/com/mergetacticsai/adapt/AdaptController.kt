package com.mergetacticsai.adapt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mergetacticsai.journal.JournalWriter
import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.HexMap
import com.mergetacticsai.map.PlacedUnit

/**
 * Состояние экрана «Противники» и связь с ботом.
 * Составы обновляются двумя способами: кнопкой «Запомнить противника» и автоматически, когда вы меняете расстановку
 * противника на карте (после скриншота или вручную). Каждый новый раунд анализ пересчитывается: старые снимки теряют вес.
 */
class AdaptController(
    private val store: AdaptStore,
    private val input: () -> AdaptInput,
    private val layout: () -> Map<HexCell, PlacedUnit>,
    private val round: () -> Int,
    private val journal: JournalWriter?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val book = OpponentBook(store.load())

    var analysis by mutableStateOf<Analysis?>(null); private set
    var label by mutableStateOf("Соперник 1")
    var info by mutableStateOf<String?>(null); private set
    var snapshots by mutableStateOf(book.current()); private set

    @Volatile private var cachedBias: Map<String, Double> = emptyMap()
    @Volatile private var biasAt = 0L
    @Volatile private var lastAutoUnits: List<Seen>? = null

    fun labels(): List<String> = listOf("Соперник 1", "Соперник 2", "Соперник 3")

    private fun nowRound() = round().coerceAtLeast(0)

    fun recompute(): Analysis {
        val a = OpponentAnalyzer.analyze(book, nowRound(), input())
        analysis = a
        cachedBias = OpponentAnalyzer.buyBias(a, input())
        biasAt = clock()
        return a
    }

    /** Поправки для покупки; пересчитываются не чаще раза в 3 секунды. */
    fun bias(): Map<String, Double> {
        if (clock() - biasAt > 3000) runCatching { recompute() }
        return cachedBias
    }

    /** Записывает расстановку противника с карты как состав соперника [label]. Возвращает false, если противника на карте нет. */
    fun observe(auto: Boolean = false, source: Source = Source.MANUAL): Boolean {
        val snap = Snapshot.fromLayout(label, nowRound(), clock(), layout(), source)
        if (snap.units.isEmpty()) {
            if (!auto) info = "На карте нет войск противника (ряды A–D). Добавьте их на «Тактической карте»."
            return false
        }
        val changed = book.record(snap)
        store.save(book.current())
        snapshots = book.current()
        val a = recompute()
        if (changed || !auto) {
            info = if (changed) "Состав «${snap.label}» обновлён (${snap.units.size} юнитов, раунд ${snap.round})." else "Состав «${snap.label}» не изменился."
            if (changed) journal?.log(
                "ADAPT", "Обновлён состав «${snap.label}»",
                "Видимых юнитов ${snap.units.size}, из них неопознанных ${snap.unknownCount}. Скрытые карты (скамейка, будущие покупки) не видны и оцениваются только вероятностью.",
                summaryLines(a), "INFO",
            )
        }
        return changed
    }

    /** Вызывается из бота, когда начался новый раунд. */
    fun onRound(r: Int) {
        journal?.setRound(r)
        if (book.isEmpty()) return
        val a = runCatching { recompute() }.getOrNull() ?: return
        journal?.log(
            "ADAPT", "Раунд $r: пересчёт по соперникам",
            "Свежих снимков: ${a.used.size - a.used.count { book.age(it, r) > 2 }} из ${a.used.size}; уверенность: ${a.level.title}.",
            summaryLines(a), "INFO", dedupKey = "adapt-round-$r",
        )
    }

    /** Автообновление: расстановка противника на карте изменилась. */
    fun autoSync() {
        val enemy = layout().filterKeys { !HexMap.isOwn(it) }
        val units = Snapshot.fromLayout(label, nowRound(), clock(), enemy, Source.MANUAL).units
        if (units.isEmpty() || units == lastAutoUnits) return
        lastAutoUnits = units
        observe(auto = true)
    }

    fun forget(l: String) {
        book.forget(l); store.save(book.current()); snapshots = book.current(); recompute()
        info = "Снимок «$l» удалён."
    }

    fun clearAll() {
        book.clear(); store.save(book.current()); snapshots = book.current(); lastAutoUnits = null; recompute()
        info = "Все снимки удалены."
    }

    fun age(s: Snapshot): Int = book.age(s, nowRound())
    fun currentRound(): Int = nowRound()

    /** Краткое содержание анализа для журнала: каждая строка с уровнем уверенности. */
    fun summaryLines(a: Analysis): String = buildString {
        appendLine("Общая уверенность по видимым данным: ${a.level.title}")
        a.demand.take(3).forEach { appendLine("Востребовано: «${it.name}» — у ${it.opponents} соперн., видно ${it.copiesSeen} коп. (уверенность: ${it.level.title})") }
        a.scarcity.take(3).forEach { appendLine("Дефицит: ${it.text} Статус: ${it.stock.title}; уверенность: ${it.level.title}") }
        a.combos.take(3).forEach { appendLine("Комбинация: ${it.text} (уверенность: ${it.level.title})") }
        a.threats.take(3).forEach { appendLine("Угроза ${it.opponent}: ${it.level.title}; " + it.reasons.take(2).joinToString("; ") + " (уверенность: ${it.confidence.title})") }
        a.counters.take(4).forEach { appendLine("Контрмера [${it.kind.title}]: ${it.text} (уверенность: ${it.level.title})") }
        a.notes.forEach { appendLine("Примечание: $it") }
    }.trim()
}
