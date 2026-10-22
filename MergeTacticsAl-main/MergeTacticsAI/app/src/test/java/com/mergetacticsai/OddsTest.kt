package com.mergetacticsai

import com.mergetacticsai.data.Troop
import com.mergetacticsai.odds.*
import org.junit.Assert.*
import org.junit.Test

class OddsTest {
    private val tiers = mapOf(2 to 10, 3 to 10, 4 to 5, 5 to 4)
    private val q = 1.0 / 29.0

    private fun troop(id: String, cost: Int, legacy: Boolean = false) = Troop(id, id, cost, "", legacy, "", "")

    // ---- пул и шанс карточки
    @Test fun poolIgnoresLegacyAndSpecials() {
        val t = tiersOf(listOf(troop("a", 2), troop("b", 2), troop("c", 3, legacy = true), troop("d", 0)))
        assertEquals(mapOf(2 to 2), t)
    }

    @Test fun defaultChanceIsUniformPerTroop() = assertEquals(q, ShopMath.slotChance(OddsParams(), tiers, 3), 1e-12)

    @Test fun weightsChangeChance() {
        val p = OddsParams(weights = mapOf(2 to 0.0, 3 to 1.0, 4 to 0.0, 5 to 0.0))
        assertEquals(0.1, ShopMath.slotChance(p, tiers, 3), 1e-12)
        assertEquals(0.0, ShopMath.slotChance(p, tiers, 4), 1e-12)
    }

    @Test fun finitePoolWithFullStockMatchesUniform() =
        assertEquals(q, ShopMath.slotChance(OddsParams(copiesPerTroop = 9), tiers, 3), 1e-12)

    @Test fun takenCopiesLowerChance() {
        val full = ShopMath.slotChance(OddsParams(copiesPerTroop = 9), tiers, 3)
        val less = ShopMath.slotChance(OddsParams(copiesPerTroop = 9, takenByOthers = 5), tiers, 3)
        assertTrue(less < full)
        assertEquals(0.0, ShopMath.slotChance(OddsParams(copiesPerTroop = 9, takenByOthers = 9), tiers, 3), 1e-12)
    }

    @Test fun missingTierGivesZero() = assertEquals(0.0, ShopMath.slotChance(OddsParams(), tiers, 7), 1e-12)

    // ---- точные формулы
    @Test fun seeInOneShop() = assertEquals(0.0999221, ShopMath.pSeeInShops(q, 3, 1), 1e-6)
    @Test fun seeAfterThreeRefreshes() = assertEquals(0.3436728, ShopMath.pSeeInShops(q, 3, 4), 1e-6)
    @Test fun atLeastTwoCopies() = assertEquals(0.2771121, ShopMath.pAtLeast(30, 2, q), 1e-6)
    @Test fun atLeastFourCopies() = assertEquals(0.0189378, ShopMath.pAtLeast(30, 4, q), 1e-6)
    @Test fun atLeastEdges() {
        assertEquals(1.0, ShopMath.pAtLeast(10, 0, 0.3), 0.0)
        assertEquals(0.0, ShopMath.pAtLeast(3, 4, 0.3), 0.0)
        assertEquals(0.0, ShopMath.pAtLeast(10, 1, 0.0), 0.0)
        assertEquals(1.0, ShopMath.pAtLeast(10, 3, 1.0), 0.0)
    }

    @Test fun binomialSumsToOne() = assertEquals(1.0, (0..3).sumOf { ShopMath.binomPmf(3, it, 0.3) }, 1e-12)

    @Test fun expectedViewsForOneCopy() = assertEquals(1.0 / (1.0 - Math.pow(1.0 - q, 3.0)), ShopMath.expectedViews(q, 3, 1), 1e-9)
    @Test fun expectedViewsForTwoCopies() = assertEquals(19.6665, ShopMath.expectedViews(q, 3, 2), 1e-3)
    @Test fun expectedViewsForFourCopies() = assertEquals(39.0, ShopMath.expectedViews(q, 3, 4), 1e-3)
    @Test fun expectedViewsNeverReachable() = assertTrue(ShopMath.expectedViews(0.0, 3, 2).isInfinite())
    @Test fun nothingNeededCostsNothing() = assertEquals(0.0, ShopMath.expectedElixir(q, 3, 0, 3, 2), 0.0)
    @Test fun expectedElixir() = assertEquals(43.3331, ShopMath.expectedElixir(q, 3, 2, 3, 2), 1e-3)

    @Test fun finiteShopWithoutRepeats() {
        assertEquals(0.100294, ShopMath.pSeeOneShopFinite(9, 261, 3), 1e-6)
        assertEquals(0.0, ShopMath.pSeeOneShopFinite(0, 100, 3), 0.0)
        assertEquals(1.0, ShopMath.pSeeOneShopFinite(1, 3, 3), 0.0)
    }

    @Test fun starsNeedCopiesAndSlots() {
        assertEquals(1, ShopMath.copiesForStar(1, 2))
        assertEquals(2, ShopMath.copiesForStar(2, 2))
        assertEquals(4, ShopMath.copiesForStar(3, 2))
        assertEquals(2, ShopMath.slotsNeeded(2, 2))
        assertEquals(3, ShopMath.slotsNeeded(3, 2))
    }

    // ---- моделирование
    private fun sim(p: OddsParams, goal: Goal, runs: Int = 40_000, cost: Int = 3) = Simulator.run(p, tiers, cost, goal, runs, 7L)

    @Test fun simTwoStarsIn10Rounds() = assertEquals(0.772, sim(OddsParams(), Goal(GoalKind.STARS, 2)).p, 0.02)
    @Test fun simThreeStarsIn10Rounds() = assertEquals(0.226, sim(OddsParams(), Goal(GoalKind.STARS, 3)).p, 0.02)
    @Test fun simFourCopiesEqualsThreeStars() = assertEquals(0.226, sim(OddsParams(), Goal(GoalKind.COPIES, 4)).p, 0.02)
    @Test fun simWithOneCopyAlreadyOwned() = assertEquals(0.947, sim(OddsParams(startCopies = 1), Goal(GoalKind.STARS, 2)).p, 0.02)
    @Test fun simTooFewSlotsMakesGoalImpossible() = assertEquals(0, sim(OddsParams(freeSlots = 1), Goal(GoalKind.STARS, 2), 2000).successes)
    @Test fun simGoalAlreadyReached() = assertEquals(1.0, sim(OddsParams(startCopies = 2), Goal(GoalKind.STARS, 2), 100).p, 0.0)
    @Test fun simMoreRoundsHelp() {
        val a = sim(OddsParams(rounds = 6), Goal(GoalKind.STARS, 2)).p
        val b = sim(OddsParams(rounds = 12), Goal(GoalKind.STARS, 2)).p
        assertTrue(b > a)
    }
    @Test fun simFinitePoolTooSmall() =
        assertEquals(0, sim(OddsParams(copiesPerTroop = 2), Goal(GoalKind.STARS, 3), 2000).successes)
    @Test fun simOtherPlayersTakingCardsHurts() {
        val a = sim(OddsParams(copiesPerTroop = 9, othersDrawsPerRound = 0), Goal(GoalKind.STARS, 2)).p
        val b = sim(OddsParams(copiesPerTroop = 9, othersDrawsPerRound = 30), Goal(GoalKind.STARS, 2)).p
        assertTrue(b < a)
    }
    @Test fun simIsRepeatableWithSameSeed() =
        assertEquals(sim(OddsParams(), Goal(GoalKind.STARS, 2), 3000).successes, sim(OddsParams(), Goal(GoalKind.STARS, 2), 3000).successes)

    @Test fun wilsonIntervalContainsEstimate() {
        val r = Simulator.Result(1000, 250, 0.0, 0.0)
        val (lo, hi) = r.interval()
        assertTrue(lo < 0.25 && 0.25 < hi && hi - lo < 0.06)
    }

    // ---- отчёт, диапазоны, достоверность
    private fun report(s: OddsState) = OddsEngine.compute(s, tiers, 3, runs = 20_000)

    @Test fun rangesAreOrderedAndWide() {
        val r = report(OddsState(targetId = "x"))
        assertTrue(r.oneShop.low < r.oneShop.nominal && r.oneShop.nominal < r.oneShop.high)
        assertTrue(r.finish.range.low < r.finish.range.nominal && r.finish.range.nominal < r.finish.range.high)
        assertTrue(r.expectedElixir.low < r.expectedElixir.high)
        assertEquals(2, r.neededCopies)
    }

    @Test fun verifiedParamsCollapseExactRanges() {
        val r = report(OddsState(targetId = "x", verified = Keys.all.toSet()))
        assertEquals(r.oneShop.low, r.oneShop.high, 1e-12)
        assertEquals(Confidence.HIGH, r.confidence)
        assertTrue(r.unverified.isEmpty())
    }

    @Test fun confidenceFallsWithUnverifiedParams() {
        assertEquals(Confidence.LOW, report(OddsState(targetId = "x")).confidence)
        val partial = report(OddsState(targetId = "x", verified = setOf(Keys.ODDS, Keys.POOL, Keys.REFRESH)))
        assertEquals(Confidence.MEDIUM, partial.confidence)
    }

    @Test fun othersParamCountsOnlyWithFinitePool() {
        assertFalse(Keys.OTHERS in OddsEngine.unverifiedKeys(OddsState()))
        assertTrue(Keys.OTHERS in OddsEngine.unverifiedKeys(OddsState(params = OddsParams(copiesPerTroop = 9))))
    }

    @Test fun warningsForImpossibleGoal() {
        val r = report(OddsState(targetId = "x", goal = Goal(GoalKind.STARS, 3), params = OddsParams(freeSlots = 2)))
        assertTrue(r.warnings.any { it.contains("недостижима") })
        assertEquals(0.0, r.finish.range.nominal, 0.0)
    }

    @Test fun warningForMissingTier() {
        val r = OddsEngine.compute(OddsState(targetId = "x"), tiers, 9, runs = 500)
        assertTrue(r.warnings.isNotEmpty())
        assertEquals(0.0, r.oneShop.nominal, 0.0)
    }

    @Test fun scenariosKeepVerifiedParamsFixed() {
        val p = OddsParams(refreshCost = 2, rounds = 10)
        val (pess, opt) = OddsEngine.scenarios(p, Uncertainty(), setOf(Keys.REFRESH, Keys.ROUNDS))
        assertEquals(2, pess.refreshCost); assertEquals(2, opt.refreshCost)
        assertEquals(10, pess.rounds); assertEquals(10, opt.rounds)
        assertTrue(pess.qScale < 1.0 && opt.qScale > 1.0)
    }

    @Test fun neededCopiesAccountsForOwnedOnes() {
        assertEquals(1, OddsEngine.neededCopies(OddsState(params = OddsParams(startCopies = 1))))
        assertEquals(3, OddsEngine.neededCopies(OddsState(goal = Goal(GoalKind.STARS, 3), params = OddsParams(startCopies = 1))))
        assertEquals(0, OddsEngine.neededCopies(OddsState(goal = Goal(GoalKind.COPIES, 2), params = OddsParams(startCopies = 5))))
    }

    // ---- сохранение и формат вывода
    @Test fun stateRoundTrip() {
        val s = OddsState(
            targetId = "goblin", goal = Goal(GoalKind.COPIES, 5),
            params = OddsParams(weights = mapOf(2 to 3.0, 3 to 1.5), copiesPerTroop = 12, takenByOthers = 3, rounds = 14, refreshCost = 1),
            unc = Uncertainty(oddsSpreadPct = 30), verified = setOf(Keys.REFRESH, Keys.ROUNDS),
        )
        assertEquals(s, OddsCodec.decode(OddsCodec.encode(s)))
    }

    @Test fun badSavedStateFallsBackToDefaults() {
        val s = OddsCodec.decode("slots=99;rounds=-4;gk=NOPE;ver=zzz,odds;w=2:abc,x:1")
        assertEquals(6, s.params.shopSlots)
        assertEquals(1, s.params.rounds)
        assertEquals(GoalKind.STARS, s.goal.kind)
        assertEquals(setOf(Keys.ODDS), s.verified)
        assertTrue(s.params.weights.isEmpty())
        assertEquals(OddsState(), OddsCodec.decode(""))
    }

    @Test fun percentFormat() {
        assertEquals("0%", pct(0.0)); assertEquals("100%", pct(1.0)); assertEquals("<0,1%", pct(0.0005))
        assertEquals("3,4%", pct(0.034)); assertEquals("10%", pct(0.0999)); assertEquals("50%", pct(0.5)); assertEquals(">99,9%", pct(0.9995))
    }

    @Test fun rangeFormat() {
        assertEquals("50%", rangeText(Range(0.5, 0.5, 0.5)))
        assertEquals("50% (диапазон 40%–60%)", rangeText(Range(0.4, 0.5, 0.6)))
        assertEquals("недостижимо при заданных параметрах", numRangeText(Range(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)))
        assertEquals("43,3 эликсира", numRangeText(Range(43.3, 43.3, 43.3), "эликсира"))
    }
}
