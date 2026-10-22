package com.mergetacticsai.automation

enum class AgentState {
    IDLE, LISTENING, STARTING_GAME, WAITING_FOR_GAME, DETECTING_MODE, ANALYZING, PLANNING,
    EXECUTING, WAITING_FOR_BATTLE, OBSERVING_BATTLE, ROUND_FINISHED, MATCH_FINISHED,
    PAUSED, ERROR, STOPPED;

    companion object {
        private val common = setOf(ERROR, STOPPED, PAUSED)
        val allowed: Map<AgentState, Set<AgentState>> = mapOf(
            IDLE to setOf(LISTENING, STARTING_GAME, DETECTING_MODE, STOPPED),
            LISTENING to setOf(IDLE, STARTING_GAME, ERROR, STOPPED),
            STARTING_GAME to setOf(WAITING_FOR_GAME) + common,
            WAITING_FOR_GAME to setOf(DETECTING_MODE) + common,
            DETECTING_MODE to setOf(ANALYZING, IDLE) + common,
            ANALYZING to setOf(PLANNING, WAITING_FOR_BATTLE, MATCH_FINISHED) + common,
            PLANNING to setOf(EXECUTING, ANALYZING) + common,
            EXECUTING to setOf(ANALYZING, WAITING_FOR_BATTLE) + common,
            WAITING_FOR_BATTLE to setOf(OBSERVING_BATTLE, ANALYZING) + common,
            OBSERVING_BATTLE to setOf(ROUND_FINISHED) + common,
            ROUND_FINISHED to setOf(ANALYZING, MATCH_FINISHED) + common,
            MATCH_FINISHED to setOf(IDLE, STOPPED),
            PAUSED to setOf(ANALYZING, IDLE, STOPPED),
            ERROR to setOf(IDLE, STOPPED),
            STOPPED to setOf(IDLE),
        )
    }
}
