package com.mergetacticsai.util

import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.random.Random

/** Сбой сети, который создал сам безопасный режим тестирования (настоящая сеть не затрагивается). */
class SimulatedNetworkException(message: String) : IOException(message)

/**
 * Безопасный режим тестирования: имитирует отсутствие сети, медленный ответ и потерю пакетов.
 *
 * Действует только на запросы самого приложения (ИИ Gemini и пробные запросы на экране «Сетевые тесты»).
 * Интернет телефона и соединение игры не меняются. Режим всегда выключен после запуска приложения
 * и сам выключается через [AUTO_OFF_MS], чтобы его нельзя было забыть включённым.
 */
object FaultSim {
    const val AUTO_OFF_MS = 10 * 60 * 1000L

    @Volatile var offline = false
    @Volatile var latencyMs = 0L
    @Volatile var lossPercent = 0
    @Volatile private var until = 0L

    /** Для тестов: свои часы. */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    val enabled: Boolean get() = until > clock()

    fun setEnabled(on: Boolean) { until = if (on) clock() + AUTO_OFF_MS else 0L }

    /** Сколько миллисекунд осталось до автоматического выключения (0 — режим выключен). */
    fun remainingMs(): Long = (until - clock()).coerceAtLeast(0L)

    /** Выключает режим и сбрасывает все условия. */
    fun reset() { until = 0L; offline = false; latencyMs = 0L; lossPercent = 0 }

    /** Условия сети человеческим языком. */
    fun describe(): String {
        if (!enabled) return "режим тестирования выключен"
        val parts = ArrayList<String>()
        if (offline) parts.add("нет сети")
        if (latencyMs > 0) parts.add("задержка $latencyMs мс")
        if (lossPercent > 0) parts.add("потеря пакетов $lossPercent%")
        return if (parts.isEmpty()) "сеть без помех" else parts.joinToString(", ")
    }

    /**
     * Пропускает запрос через симуляцию. Режим выключен — запрос выполняется как есть.
     * Включён: сначала задержка, затем «нет сети» или случайная потеря, иначе запрос выполняется.
     */
    suspend fun <T> gate(block: suspend () -> T): T {
        if (!enabled) return block()
        val lat = latencyMs
        if (lat > 0) delay(lat)
        if (offline) throw SimulatedNetworkException("Симуляция: нет соединения")
        val loss = lossPercent
        if (loss > 0 && Random.nextInt(100) < loss) throw SimulatedNetworkException("Симуляция: пакет потерян")
        return block()
    }
}
