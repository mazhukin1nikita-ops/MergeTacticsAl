package com.mergetacticsai

import com.mergetacticsai.strategy.*
import org.junit.Assert.*
import org.junit.Test

class AdaptiveTest {
    // В пуле 40 войск: шанс одной карточки в слоте магазина = 1/40.
    private val tiers = mapOf(1 to 8, 2 to 10, 3 to 10, 4 to 8, 5 to 4)
    private fun env(elixir: Int? = 8, rounds: Int = 8) = Env(elixir = elixir, income = 4, roundsLeft = rounds, shopSlots = 3, tiers = tiers)
    private fun card(i: Int, cost: Int?, trait: String? = null, troop: String? = null, arrow: Boolean = false, aff: Boolean = true) =
        ShopCard(i, i.toLong(), cost, trait, troop, aff, arrow, true)
    private val none = Build(emptySet())
    private val clan = Build(setOf("clan"))

    // ---- линия прокачки
    @Test fun needOfSingleCopy() = assertEquals(1, Line("a", "a", null, 2, 1, 0).need)
    @Test fun needOfTwoStar() = assertEquals(2, Line("a", "a", null, 2, 0, 1).need)
    @Test fun needOfTwoStarPlusOne() = assertEquals(1, Line("a", "a", null, 2, 1, 1).need)
    @Test fun needNeverNegative() = assertEquals(0, Line("a", "a", null, 2, 2, 0).need)

    // ---- шансы
    @Test fun moreRoundsMoreChance() {
        val few = AdaptivePlanner.pFinish(1, 2, env(rounds = 1), 100.0)
        val many = AdaptivePlanner.pFinish(1, 2, env(rounds = 10), 100.0)
        assertTrue(many > few)
    }
    @Test fun moreCopiesNeededLessChance() {
        val one = AdaptivePlanner.pFinish(1, 2, env(), 100.0)
        val two = AdaptivePlanner.pFinish(2, 2, env(), 100.0)
        assertTrue(one > two)
    }
    @Test fun noMoneyNoChance() = assertEquals(0.0, AdaptivePlanner.pFinish(2, 4, env(), 0.0), 1e-9)

    // 1) продолжать прокачку
    @Test fun mergeCardContinuesUpgrade() {
        val d = AdaptivePlanner.decide(listOf(card(0, 2), card(1, 3, arrow = true)), listOf("x", "y"),
            listOf(Line("y", "y", null, 3, 1, 0)), none, true, env(), emptySet())
        assertEquals(1, d.index)
        assertEquals(Kind.CONTINUE, d.kind)
    }
    @Test fun buysCopyOfUnitInProgressInBuild() {
        val lines = listOf(Line("k1", "k1", "clan", 3, 1, 0))
        val cards = listOf(card(0, 3, "clan", "k1"), card(1, 1, "noble", "f"))
        val d = AdaptivePlanner.decide(cards, listOf("k1", "f"), lines, clan, true, env(), emptySet())
        assertEquals(0, d.index)
        assertEquals(Kind.CONTINUE, d.kind)
    }

    // 2) отказ от маловероятного улучшения
    @Test fun abandonsHopelessUpgradeLate() {
        val lines = listOf(Line("x", "x", "noble", 2, 1, 0))
        val d = AdaptivePlanner.decide(emptyList(), emptyList(), lines, none, true, env(rounds = 1), emptySet())
        assertTrue("x" in d.abandoned)
    }
    @Test fun visibleCopyKeepsUpgradeAlive() {
        val lines = listOf(Line("x", "x", "noble", 2, 1, 0))
        val d = AdaptivePlanner.decide(listOf(card(0, 2, "noble", "x")), listOf("x"), lines, none, true, env(rounds = 1), emptySet())
        assertFalse("x" in d.abandoned)
        assertEquals(0, d.index)
    }
    @Test fun keepsUpgradeEarly() {
        val lines = listOf(Line("x", "x", "noble", 2, 1, 0))
        val d = AdaptivePlanner.decide(emptyList(), emptyList(), lines, none, true, env(rounds = 10), emptySet())
        assertFalse("x" in d.abandoned)
    }
    @Test fun neverAbandonsBuild() {
        val lines = listOf(Line("x", "x", "clan", 2, 1, 0))
        val d = AdaptivePlanner.decide(emptyList(), emptyList(), lines, clan, true, env(rounds = 1), emptySet())
        assertFalse("x" in d.abandoned)
    }
    @Test fun abandonedStaysAbandoned() {
        val d = AdaptivePlanner.decide(listOf(card(0, 2, "noble", "x")), listOf("x"), emptyList(), none, true, env(rounds = 10), setOf("x"))
        assertTrue("x" in d.abandoned)
        assertNull(d.index)
    }
    @Test fun sellsMostExpensiveAbandoned() {
        val bench = listOf(BenchUnit(0, 1L, 2, "noble", "a", false), BenchUnit(1, 2L, 4, "noble", "b", false), null, null, null)
        val keys = listOf("a", "b", null, null, null)
        assertEquals(1, AdaptivePlanner.pickAbandonedSell(bench, keys, setOf("a", "b"), none))
    }
    @Test fun doesNotSellBuildOrNotAbandoned() {
        val bench = listOf(BenchUnit(0, 1L, 2, "clan", "a", false), BenchUnit(1, 2L, 4, "noble", "b", false))
        val keys = listOf("a", "b")
        assertNull(AdaptivePlanner.pickAbandonedSell(bench, keys, setOf("a"), clan))
    }

    // 3) альтернативная карточка
    @Test fun takesAlternativeWhenNeededCardMissing() {
        val lines = listOf(Line("c1", "c1", "clan", 3, 1, 0))
        val cards = listOf(card(0, 1, "clan", "c2"))
        val d = AdaptivePlanner.decide(cards, listOf("c2"), lines, clan, true, env(), emptySet())
        assertEquals(0, d.index)
        assertEquals(Kind.ALTERNATIVE, d.kind)
    }
    @Test fun skipsUnaffordableAndPicksOther() {
        val cards = listOf(card(0, 4, "clan", "a", aff = false), card(1, 2, "clan", "b"))
        val d = AdaptivePlanner.decide(cards, listOf("a", "b"), emptyList(), clan, true, env(), emptySet())
        assertEquals(1, d.index)
    }

    // 4) сохранить эликсир
    @Test fun savesElixirForBuildUpgrade() {
        val lines = listOf(Line("c1", "c1", "clan", 4, 1, 0))
        val d = AdaptivePlanner.decide(listOf(card(0, 2, "noble", "n1")), listOf("n1"), lines, clan, true, env(elixir = 5), emptySet())
        assertNull(d.index)
        assertEquals(Kind.SAVE, d.kind)
    }
    @Test fun nothingToBuyIsNotSave() {
        val d = AdaptivePlanner.decide(emptyList(), emptyList(), emptyList(), none, true, env(), emptySet())
        assertNull(d.index)
        assertEquals(Kind.NONE, d.kind)
    }

    // 5) смена направления сборки
    @Test fun pivotsToClearlyBetterTrait() {
        val lines = listOf(Line("c1", "c1", "clan", 2, 1, 0)) + (1..4).map { Line("n$it", "n$it", "noble", 2, 1, 0) }
        val p = AdaptivePlanner.pivot(lines, emptyList(), clan, env(elixir = 8))
        assertEquals(setOf("noble"), p?.build?.traits)
    }
    @Test fun noPivotWhenSimilar() {
        val lines = listOf(Line("c1", "c1", "clan", 2, 1, 0), Line("c2", "c2", "clan", 2, 1, 0), Line("n1", "n1", "noble", 2, 1, 0), Line("n2", "n2", "noble", 2, 1, 0))
        assertNull(AdaptivePlanner.pivot(lines, emptyList(), clan, env(elixir = 8)))
    }
    @Test fun noPivotWithoutBuild() = assertNull(AdaptivePlanner.pivot(listOf(Line("n1", "n1", "noble", 2, 1, 0)), emptyList(), none, env()))
    @Test fun noPivotForManualTroops() =
        assertNull(AdaptivePlanner.pivot(emptyList(), emptyList(), Build(setOf("clan"), setOf("c1")), env()))

    // 6) несколько сценариев
    @Test fun reportsSeveralScenarios() {
        val cards = listOf(card(0, 2, "clan", "a"), card(1, 1, "clan", "b"))
        val d = AdaptivePlanner.decide(cards, listOf("a", "b"), emptyList(), none, true, env(), emptySet())
        assertTrue(d.scenarios.size >= 3)
        assertTrue(d.scenarios.any { it.label.startsWith("копить") })
    }
    @Test fun elixirCoversOnlyAffordableUpgrade() {
        val rich = AdaptivePlanner.value(listOf(Line("a", "a", "clan", 3, 1, 0)), clan, env(), 100.0)
        val poor = AdaptivePlanner.value(listOf(Line("a", "a", "clan", 3, 1, 0)), clan, env(), 0.5)
        assertTrue(rich > poor)
    }
}
