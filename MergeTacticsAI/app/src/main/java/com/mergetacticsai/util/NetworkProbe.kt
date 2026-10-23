package com.mergetacticsai.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Итог пробных запросов. */
data class ProbeResult(
    val requests: Int,
    /** Запросов, которые в итоге получили ответ. */
    val ok: Int,
    /** Из них получили ответ только после повтора. */
    val recovered: Int,
    /** Всего неудачных попыток (включая те, что потом удались). */
    val failedAttempts: Int,
    val avgMs: Long,
    val worstMs: Long,
    val timedOut: Boolean,
    val failures: List<String>,
) {
    val lost: Int get() = requests - ok

    fun summary(): String =
        "Запросов: $requests, с ответом: $ok" + (if (recovered > 0) " (после повтора: $recovered)" else "") +
            ", без ответа: $lost, неудачных попыток: $failedAttempts, среднее время: $avgMs мс, самое долгое: $worstMs мс" +
            (if (timedOut) ". Проверка прервана по времени." else "")

    /** Вывод для журнала сбоев. */
    fun verdict(): String = when {
        requests == 0 -> "запросов не было"
        ok == requests && failedAttempts == 0 -> "связь стабильна: все запросы выполнены с первой попытки"
        ok == requests -> "восстановилось: все запросы выполнены, повторы помогли ($recovered из $requests)"
        ok == 0 -> "восстановить не удалось: ни один запрос не получил ответ"
        else -> "частично: $ok из $requests запросов получили ответ"
    }
}

/**
 * Пробные запросы через [FaultSim]: показывают, как логика повторов бота ведёт себя при сбоях сети.
 * Запрос здесь — крошечная локальная задержка, в интернет ничего не уходит.
 */
object NetworkProbe {
    suspend fun run(
        requests: Int = 6,
        retries: Int = 2,
        backoffMs: Long = 300L,
        totalTimeoutMs: Long = 30_000L,
        request: suspend () -> Unit = { delay(20) },
    ): ProbeResult {
        var ok = 0
        var recovered = 0
        var failedAttempts = 0
        var sumMs = 0L
        var worst = 0L
        val failures = ArrayList<String>()
        val finished = withTimeoutOrNull(totalTimeoutMs) {
            for (n in 1..requests) {
                val t0 = System.currentTimeMillis()
                var success = false
                var attempt = 0
                while (attempt <= retries && !success) {
                    try {
                        FaultSim.gate { request() }
                        success = true
                    } catch (e: SimulatedNetworkException) {
                        failedAttempts++
                        failures.add("запрос $n, попытка ${attempt + 1}: ${e.message}")
                        if (attempt < retries) delay(backoffMs * (attempt + 1))
                    }
                    attempt++
                }
                val ms = System.currentTimeMillis() - t0
                if (success) { ok++; sumMs += ms; if (ms > worst) worst = ms; if (attempt > 1) recovered++ }
            }
            true
        }
        return ProbeResult(requests, ok, recovered, failedAttempts, if (ok > 0) sumMs / ok else 0L, worst, finished == null, failures)
    }
}

/** Сравнение настроек до и после аварии. */
object SettingsSnapshot {
    /** Названия настроек, значения которых разошлись (или появились/пропали). */
    fun diff(before: Map<String, String>, after: Map<String, String>): List<String> =
        (before.keys + after.keys).sorted().filter { before[it] != after[it] }
}
