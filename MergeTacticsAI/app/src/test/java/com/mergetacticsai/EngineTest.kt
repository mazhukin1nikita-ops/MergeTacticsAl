package com.mergetacticsai
import com.mergetacticsai.ai.DecisionParser
import com.mergetacticsai.automation.*
import com.mergetacticsai.game.*
import com.mergetacticsai.strategy.*
import com.mergetacticsai.voice.Command
import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    private val knight = GameUnit("A", 1, 3)
    private val ok = Permissions(mic = true, capture = true, accessibility = true)
    private fun state(elixir: Int = 5, bench: List<GameUnit?> = listOf(knight, knight, null)) =
        GameState(elixir, listOf(knight, null), bench, listOf(null, null), refreshCost = 1)

    @Test fun mergeFound() = assertEquals(listOf(Action.Merge(0, 1)), MergeEngine.findMerges(state()))
    @Test fun noMergeAtMaxLevel() {
        val m = GameUnit("A", 3, 3)
        assertTrue(MergeEngine.findMerges(state(bench = listOf(m, m, null))).isEmpty())
    }
    @Test fun conservativeSkipsWhenSpace() = assertTrue(MergeEngine.plan(state(), true).isEmpty())
    @Test fun conservativeMergesWhenFull() =
        assertEquals(1, MergeEngine.plan(state(bench = listOf(knight, knight)), true).size)
    @Test fun economy() { assertEquals(2, EconomyEngine.elixirAfterBuy(state(), 0)); assertNull(EconomyEngine.elixirAfterBuy(state(2), 0)) }

    @Test fun validatorRejectsWithoutPermissions() =
        assertFalse(ActionValidator().validate(Action.Buy(0), state(), Permissions(true, false, false)).ok)
    @Test fun validatorRejectsUnaffordable() = assertFalse(ActionValidator().validate(Action.Buy(0), state(1), ok).ok)
    @Test fun validatorAcceptsBuy() = assertTrue(ActionValidator().validate(Action.Buy(0), state(), ok).ok)
    @Test fun validatorRateLimit() {
        var t = 1000L
        val v = ActionValidator(400) { t }
        assertTrue(v.validate(Action.Refresh, state(), ok).ok)
        t += 100
        assertFalse(v.validate(Action.Refresh, state(), ok).ok)
    }
    @Test fun validatorRejectsMismatchedMerge() {
        val s = state(bench = listOf(knight, GameUnit("B", 1, 2), null))
        assertFalse(ActionValidator().validate(Action.Merge(0, 1), s, ok).ok)
    }
    @Test fun tapBounds() { val v = ActionValidator(); assertTrue(v.validateTap(10f, 10f, 100, 100).ok); assertFalse(v.validateTap(-1f, 10f, 100, 100).ok) }

    @Test fun parserOk() {
        val r = DecisionParser.parse("""{"actions":[{"type":"buy","shopIndex":1},{"type":"wait"}]}""")
        assertEquals(listOf(Action.Buy(1), Action.Wait), r.getOrThrow())
    }
    @Test fun parserRejectsJunk() {
        assertTrue(DecisionParser.parse("не json").isFailure)
        assertTrue(DecisionParser.parse("""{"actions":[{"type":"tap","x":1}]}""").isFailure)
    }

    @Test fun stopFromAnywhere() {
        val c = AgentController { ok }
        c.handle(Command.PLAY); c.handle(Command.STOP)
        assertEquals(AgentState.STOPPED, c.state.value)
    }
    @Test fun invalidTransitionRejected() = assertFalse(AgentController { ok }.transition(AgentState.OBSERVING_BATTLE))
    @Test fun executingNeedsPermissions() {
        val c = AgentController { Permissions(true, false, false) }
        for (s in listOf(AgentState.STARTING_GAME, AgentState.WAITING_FOR_GAME, AgentState.DETECTING_MODE,
            AgentState.ANALYZING, AgentState.PLANNING)) assertTrue(c.transition(s))
        assertFalse(c.transition(AgentState.EXECUTING))
    }
}
