package com.mergetacticsai.strategy

/** Ограничитель постановки на поле: после неудачи (или полного поля) новая попытка только через cooldownMs. */
class PlaceGate(
    private val cooldownMs: Long = 20_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var blockedUntil = 0L

    fun canTry(): Boolean = clock() >= blockedUntil
    fun remainingMs(): Long = (blockedUntil - clock()).coerceAtLeast(0)
    fun onSuccess() { blockedUntil = 0 }
    fun onFail() { blockedUntil = clock() + cooldownMs }
    fun reset() { blockedUntil = 0 }
}
