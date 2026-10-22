package com.mergetacticsai.voice

enum class Command {
    PLAY, STOP, RESUME, PAUSE, OPEN_GAME, EXPLAIN, ELECTRIC, STRONG_TEAM,
    NO_UNNECESSARY_MERGE, RESTART_ANALYSIS, MODE_MERGE, MODE_CUSTOM, SET_BUILD, UNKNOWN
}

object CommandParser {
    private val buildKeys = listOf("собери из", "в сборку", "сборка из", "сборка:", "состав:")

    // Порядок важен: более специфичные фразы проверяются раньше общих.
    private val rules: List<Pair<Command, List<String>>> = listOf(
        Command.STOP to listOf("остановись", "стоп", "останови", "хватит"),
        Command.PAUSE to listOf("пауза", "приостанови"),
        Command.RESUME to listOf("продолжай", "продолжи"),
        Command.NO_UNNECESSARY_MERGE to listOf("не объединяй"),
        Command.SET_BUILD to buildKeys,
        Command.MODE_CUSTOM to listOf("свою сборк", "своя сборк", "спец сборк", "специальную сборк", "специальная сборк"),
        Command.MODE_MERGE to listOf("режим объединени", "объединяй дешев", "через объединени"),
        Command.ELECTRIC to listOf("электричес"),
        Command.STRONG_TEAM to listOf("сильную команду", "сильная команда"),
        Command.RESTART_ANALYSIS to listOf("перезапусти анализ"),
        Command.EXPLAIN to listOf("объясни", "что сейчас происходит"),
        Command.OPEN_GAME to listOf("открой clash royale", "открой клэш рояль", "открой клеш рояль", "открой клаш рояль"),
        Command.PLAY to listOf("играй", "начни играть", "начать игру"),
    )

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').trim()

    fun parse(text: String): Command {
        val t = norm(text)
        for ((cmd, keys) in rules) if (keys.any { t.contains(it) }) return cmd
        return Command.UNKNOWN
    }

    /** «гоблин, лучница и рыцарь» → [гоблин, лучница, рыцарь]. */
    fun splitUnits(raw: String): List<String> =
        raw.split(Regex("[,;]|\\sи\\s")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Достаёт список воинов из фразы вроде «собери из гоблина и лучницы». */
    fun extractUnits(text: String): List<String> {
        val t = norm(text)
        val key = buildKeys.map { it to t.indexOf(it) }.filter { it.second >= 0 }.minByOrNull { it.second }
            ?: return emptyList()
        return splitUnits(t.substring(key.second + key.first.length))
    }
}
