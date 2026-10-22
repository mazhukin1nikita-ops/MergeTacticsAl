package com.mergetacticsai.automation

import com.mergetacticsai.strategy.StrategyConfig
import com.mergetacticsai.strategy.StrategyMode
import com.mergetacticsai.voice.Command
import com.mergetacticsai.voice.CommandParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Permissions(val mic: Boolean, val capture: Boolean, val accessibility: Boolean) {
    val canControlGame: Boolean
        get() = capture && accessibility
}

class AgentController(private val permissions: () -> Permissions) {
    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state

    private val _config = MutableStateFlow(StrategyConfig())
    val config: StateFlow<StrategyConfig> = _config

    var conservativeMerges = false; private set
    var preferElectric = false; private set

    fun setConfig(c: StrategyConfig) { _config.value = c }
    fun setMode(m: StrategyMode) { _config.value = _config.value.copy(mode = m) }
    fun setTargets(t: List<String>) { _config.value = _config.value.copy(targets = t) }

    fun transition(to: AgentState): Boolean {
        val from = _state.value
        if (to !in (AgentState.allowed[from] ?: emptySet())) return false
        if (to == AgentState.EXECUTING && !permissions().canControlGame) return false
        _state.value = to
        return true
    }

    /** Переводит агента в ANALYZING из любого «спокойного» состояния. */
    fun beginAnalysis(): Boolean {
        val st = _state.value
        if (st == AgentState.STOPPED || st == AgentState.ERROR || st == AgentState.MATCH_FINISHED) transition(AgentState.IDLE)
        if (_state.value == AgentState.IDLE || _state.value == AgentState.WAITING_FOR_GAME) transition(AgentState.DETECTING_MODE)
        if (_state.value == AgentState.DETECTING_MODE || _state.value == AgentState.PAUSED) transition(AgentState.ANALYZING)
        return _state.value == AgentState.ANALYZING
    }

    /** Стоп действует немедленно из любого состояния. */
    fun forceStop() { _state.value = AgentState.STOPPED }

    fun handle(cmd: Command, text: String = ""): String = when (cmd) {
        Command.STOP -> { forceStop(); "Остановлено" }
        Command.PAUSE -> if (transition(AgentState.PAUSED)) "Пауза" else "Сейчас нечего ставить на паузу"
        Command.RESUME -> if (transition(AgentState.ANALYZING)) "Продолжаю" else "Нечего продолжать"
        Command.PLAY, Command.OPEN_GAME -> {
            if (_state.value == AgentState.STOPPED) transition(AgentState.IDLE)
            if (transition(AgentState.STARTING_GAME)) "Запускаю игру" else "Сейчас нельзя запустить игру"
        }
        Command.MODE_MERGE -> { setMode(StrategyMode.MERGE_CHEAP); "Режим: объединение дешёвых (приоритет — гоблин)" }
        Command.MODE_CUSTOM -> {
            setMode(StrategyMode.CUSTOM_BUILD)
            val t = _config.value.targets
            if (t.isEmpty()) "Режим: своя сборка. Состав пуст — скажите «собери из гоблина и лучницы»"
            else "Режим: своя сборка (${t.joinToString(", ")})"
        }
        Command.SET_BUILD -> {
            val units = CommandParser.extractUnits(text)
            if (units.isEmpty()) "Не расслышал состав"
            else {
                _config.value = _config.value.copy(mode = StrategyMode.CUSTOM_BUILD, targets = units)
                "Своя сборка: ${units.joinToString(", ")}"
            }
        }
        Command.ELECTRIC -> { preferElectric = true; "Стратегия: электричество (если юниты доступны)" }
        Command.STRONG_TEAM -> { preferElectric = false; "Стратегия: сильная команда" }
        Command.NO_UNNECESSARY_MERGE -> { conservativeMerges = true; "Объединяю только при необходимости" }
        Command.RESTART_ANALYSIS -> if (transition(AgentState.ANALYZING)) "Анализ перезапущен" else "Сейчас нельзя перезапустить анализ"
        Command.EXPLAIN -> "Состояние: ${_state.value}, режим: ${_config.value.mode}"
        Command.UNKNOWN -> "Команда не распознана"
    }
}
