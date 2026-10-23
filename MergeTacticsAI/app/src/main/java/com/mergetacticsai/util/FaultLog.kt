package com.mergetacticsai.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Запись журнала сбоев: что случилось, когда, в каком состоянии был бот и чем закончилось. */
data class FaultEntry(val id: Long, val at: Long, val reason: String, val botState: String, val result: String, val details: String = "")

/** Хранение записей строкой: поля через табуляцию, записи через перевод строки, спецсимволы экранируются. */
object FaultCodec {
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    '\\' -> sb.append('\\')
                    else -> { sb.append(c); sb.append(s[i + 1]) }
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    fun encode(list: List<FaultEntry>): String =
        list.joinToString("\n") { listOf(it.id.toString(), it.at.toString(), esc(it.reason), esc(it.botState), esc(it.result), esc(it.details)).joinToString("\t") }

    fun decode(text: String): List<FaultEntry> = text.split("\n").mapNotNull { line ->
        val p = line.split("\t")
        val id = p.getOrNull(0)?.toLongOrNull()
        val at = p.getOrNull(1)?.toLongOrNull()
        if (p.size < 6 || id == null || at == null) null
        else FaultEntry(id, at, unesc(p[2]), unesc(p[3]), unesc(p[4]), unesc(p[5]))
    }
}

/**
 * Журнал сбоев: причина, время, состояние бота и результат восстановления.
 * Хранится на телефоне (последние [MAX] записей), переживает перезапуск приложения.
 * Пока не вызван [init], записи живут только в памяти.
 */
object FaultLog {
    const val MAX = 100
    private const val KEY = "entries"

    private var prefs: SharedPreferences? = null
    private val lock = Any()
    private val _entries = MutableStateFlow<List<FaultEntry>>(emptyList())

    /** Новые записи сверху. */
    val entries: StateFlow<List<FaultEntry>> = _entries

    fun init(context: Context) {
        synchronized(lock) {
            val p = context.getSharedPreferences("faultlog", Context.MODE_PRIVATE)
            prefs = p
            _entries.value = FaultCodec.decode(p.getString(KEY, "") ?: "").sortedByDescending { it.id }.take(MAX)
        }
    }

    private fun persist(list: List<FaultEntry>) {
        prefs?.edit()?.putString(KEY, FaultCodec.encode(list))?.apply()
    }

    /** Добавляет запись и возвращает её номер, чтобы потом обновить результат через [update]. */
    fun add(reason: String, botState: String, result: String, details: String = "", now: Long = System.currentTimeMillis()): Long =
        synchronized(lock) {
            val id = (_entries.value.maxOfOrNull { it.id } ?: 0L) + 1L
            val list = (listOf(FaultEntry(id, now, reason, botState, result, details)) + _entries.value).take(MAX)
            _entries.value = list
            persist(list)
            id
        }

    /** Записывает итог в уже созданную запись. */
    fun update(id: Long, result: String, details: String? = null) {
        synchronized(lock) {
            val list = _entries.value.map { if (it.id == id) it.copy(result = result, details = details ?: it.details) else it }
            _entries.value = list
            persist(list)
        }
    }

    fun clear() {
        synchronized(lock) { _entries.value = emptyList(); persist(emptyList()) }
    }

    /** Весь журнал одним текстом (для копирования). Старые записи первыми. */
    fun text(): String {
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        return _entries.value.asReversed().joinToString("\n\n") { e ->
            fmt.format(Date(e.at)) + " · " + e.reason + "\nСостояние бота: " + e.botState + "\nРезультат: " + e.result +
                (if (e.details.isNotEmpty()) "\n" + e.details else "")
        }
    }
}
