package com.mergetacticsai.strategy

enum class StrategyMode { MERGE_CHEAP, CUSTOM_BUILD }

/**
 * MERGE_CHEAP — объединяем дешёвых, приоритет у priorityUnit (по умолчанию гоблин).
 * CUSTOM_BUILD — покупаем только воинов из targets (пользователь вписывает вручную).
 */
data class StrategyConfig(
    val mode: StrategyMode = StrategyMode.MERGE_CHEAP,
    val targets: List<String> = emptyList(),
    val priorityUnit: String = "гоблин",
)
