package com.mergetacticsai.ai

/**
 * Интерфейс подключения мультимодальной модели (облачной или локальной).
 * Ответ — JSON вида {"actions":[{"type":"buy","shopIndex":0}]}; он проходит DecisionParser
 * и ActionValidator до любого касания. Скриншоты уходят только с согласия пользователя.
 */
interface AIProvider {
    suspend fun requestPlan(frameJpeg: ByteArray, prompt: String): String
}
