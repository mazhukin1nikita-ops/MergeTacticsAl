package com.mergetacticsai
import com.mergetacticsai.automation.*
import com.mergetacticsai.game.*
import com.mergetacticsai.strategy.*
import com.mergetacticsai.voice.Command
import org.junit.Assert.*
import org.junit.Test
class BuyPlannerTest {
    private val gob = GameUnit("Гоблин", 1, 2)
    private val arch = GameUnit("Лучница", 1, 2)
    private val giant = GameUnit("Великан", 1, 3)
    private val merge = StrategyConfig()
    private fun st(elixir: Int, shop: List<GameUnit?>, bench: List<GameUnit?> = listOf(null, null, null)) =
        GameState(elixir, shop, bench, listOf(null), 1)

    @Test fun goblinFirstAmongCheap() = assertEquals(Action.Buy(1), BuyPlanner.choose(st(5, listOf(arch, gob, giant)), merge))
    @Test fun pairBeatsGoblin() =
        assertEquals(Action.Buy(0), BuyPlanner.choose(st(5, listOf(arch, gob), listOf(arch, null, null)), merge))
    @Test fun cheapOverExpensive() = assertEquals(Action.Buy(1), BuyPlanner.choose(st(5, listOf(giant, arch)), merge))
    @Test fun unaffordableSkipped() = assertNull(BuyPlanner.choose(st(1, listOf(gob, arch)), merge))
    @Test fun noBenchSpace() = assertNull(BuyPlanner.choose(st(5, listOf(gob), listOf(arch, arch)), merge))
    @Test fun customBuysOnlyTargets() {
        val c = StrategyConfig(StrategyMode.CUSTOM_BUILD, listOf("лучницы"))
        assertNull(BuyPlanner.choose(st(5, listOf(gob, giant)), c))
        assertEquals(Action.Buy(1), BuyPlanner.choose(st(5, listOf(gob, arch)), c))
    }
    @Test fun stemMatching() { assertTrue(BuyPlanner.matches("гоблина", "Гоблин")); assertFalse(BuyPlanner.matches("гоблин", "Рыцарь")) }
    @Test fun controllerSwitchesModes() {
        val c = AgentController { Permissions(true, true, true) }
        c.handle(Command.SET_BUILD, "собери из гоблина и лучницы")
        assertEquals(StrategyMode.CUSTOM_BUILD, c.config.value.mode)
        assertEquals(listOf("гоблина", "лучницы"), c.config.value.targets)
        c.handle(Command.MODE_MERGE)
        assertEquals(StrategyMode.MERGE_CHEAP, c.config.value.mode)
    }
}
