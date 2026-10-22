package com.mergetacticsai.odds

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mergetacticsai.data.Troop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Состояние экрана «Вероятности». Каждая правка сохраняется, расчёт запускается из экрана. */
class OddsController(private val store: OddsStore, private val troops: () -> List<Troop>) {
    var state by mutableStateOf(store.load()); private set
    var report by mutableStateOf<OddsReport?>(null); private set
    var busy by mutableStateOf(false); private set

    fun eligible(): List<Troop> = troops().filter { !it.legacy && it.cost > 0 }.sortedWith(compareBy({ it.cost }, { it.name }))

    fun target(): Troop? = eligible().firstOrNull { it.id == state.targetId }

    private fun fixTarget(s: OddsState): OddsState =
        if (eligible().any { it.id == s.targetId }) s else s.copy(targetId = eligible().firstOrNull()?.id ?: "")

    fun ensureTarget() {
        val fixed = fixTarget(state)
        if (fixed != state) { state = fixed; store.save(fixed) }
    }

    fun update(f: (OddsState) -> OddsState) {
        val s = fixTarget(f(state))
        state = s
        store.save(s)
    }

    fun toggleVerified(key: String) = update { it.copy(verified = if (key in it.verified) it.verified - key else it.verified + key) }

    suspend fun recompute() {
        val t = target()
        if (t == null) { report = null; return }
        val s = state
        val tiers = tiersOf(troops())
        busy = true
        val r = withContext(Dispatchers.Default) { OddsEngine.compute(s, tiers, t.cost) }
        report = r
        busy = false
    }
}
