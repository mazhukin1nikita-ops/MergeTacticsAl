package com.mergetacticsai

import com.mergetacticsai.strategy.*
import org.junit.Assert.*
import org.junit.Test

class UpgradeEvaluatorTest {
    // В пуле 40 войск: шанс одной карточки в слоте магазина = 1/40.
    private val tiers = mapOf(1 to 8, 2 to 10, 3 to 10, 4 to 8, 5 to 4)
    private fun env(elixir: Int? = 8, rounds: Int = 8) = Env(elixir = elixir, income = 4, roundsLeft = rounds, shopSlots = 3, tiers = tiers)
    private fun troop(ones: Int = 1, twos: Int = 0, threes: Int = 0, cost: Int = 2, inBuild: Boolean = false, target: Int? = null) =
        TroopHolding("a", "Воин", cost, ones, twos, threes, inBuild, target)
    private val rules = UpgradeRules()

    // ---- звёздность и копии
    @Test fun currentStarFromCopies() {
        assertEquals(0, troop(0, 0).currentStar)
        assertEquals(1, troop(1, 0).currentStar)
        assertEquals(2, troop(1, 1).currentStar)
        assertEquals(3, troop(0, 0, 1).currentStar)
    }
    @Test fun missingForFirstUpgrade() {
        val e = UpgradeEvaluator.evaluate(troop(ones = 1), env(), rules)
        assertEquals(1, e.currentStar); assertEquals(2, e.targetStar)
        assertEquals(1, e.copies); assertEquals(2, e.copiesNeeded); assertEquals(1, e.missing)
    }
    @Test fun missingForThirdStar() {
        val e = UpgradeEvaluator.evaluate(troop(ones = 0, twos = 1), env(), rules)
        assertEquals(3, e.targetStar); assertEquals(4, e.copiesNeeded); assertEquals(2, e.copies); assertEquals(2, e.missing)
    }
    @Test fun readyWhenCopiesEnough() {
        val e = UpgradeEvaluator.evaluate(troop(ones = 2), env(), rules)
        assertEquals(UpgradeVerdict.READY, e.verdict)
        assertEquals(0, e.missing)
        assertEquals(1.0, e.pFinish, 1e-9)
    }
    @Test fun doneAtMaxStar() = assertEquals(UpgradeVerdict.DONE, UpgradeEvaluator.evaluate(troop(ones = 0, threes = 1), env(), rules).verdict)
    @Test fun customTargetStar() {
        val e = UpgradeEvaluator.evaluate(troop(ones = 1, target = 3), env(), rules)
        assertEquals(3, e.targetStar); assertEquals(3, e.missing)
    }

    // ---- пул
    @Test fun impossibleWhenPoolTooSmall() {
        val e = UpgradeEvaluator.evaluate(troop(ones = 1), env(), rules.copy(copiesPerTroop = 2, takenByOthers = 1))
        assertEquals(UpgradeVerdict.IMPOSSIBLE, e.verdict)
        assertEquals(0, e.poolLeft)
        assertEquals(0.0, e.pFinish, 1e-9)
    }
    @Test fun poolLeftCountsOwnAndTaken() {
        val e = UpgradeEvaluator.evaluate(troop(ones = 1), env(), rules.copy(copiesPerTroop = 9, takenByOthers = 3))
        assertEquals(5, e.poolLeft)
    }
    @Test fun unlimitedPoolHasNoLimit() = assertNull(UpgradeEvaluator.evaluate(troop(), env(), rules).poolLeft)
    @Test fun smallerPoolLowersChance() {
        val big = UpgradeEvaluator.evaluate(troop(), env(), rules.copy(copiesPerTroop = 20))
        val small = UpgradeEvaluator.evaluate(troop(), env(), rules.copy(copiesPerTroop = 20, takenByOthers = 15))
        assertTrue(big.slotChance > small.slotChance)
    }

    // ---- шанс и стоимость
    @Test fun moreRoundsMoreChance() {
        assertTrue(UpgradeEvaluator.evaluate(troop(), env(rounds = 8), rules).pFinish > UpgradeEvaluator.evaluate(troop(), env(rounds = 2), rules).pFinish)
    }
    @Test fun moreMissingLessChance() {
        val one = UpgradeEvaluator.evaluate(troop(ones = 1), env(), rules)
        val two = UpgradeEvaluator.evaluate(troop(ones = 0, twos = 1), env(), rules)
        assertTrue(one.pFinish > two.pFinish)
    }
    @Test fun totalCostIsMissingTimesCost() = assertEquals(6, UpgradeEvaluator.evaluate(troop(ones = 0, twos = 1, cost = 3), env(), rules).totalCost)
    @Test fun poorBudgetLowersChanceOfExpensiveTroop() {
        val poor = env(elixir = 0, rounds = 1)
        val cheap = UpgradeEvaluator.evaluate(troop(cost = 2), poor, rules)
        val dear = UpgradeEvaluator.evaluate(troop(cost = 5), poor, rules)
        assertTrue(cheap.pFinish > dear.pFinish)
    }

    // ---- сила улучшения
    @Test fun thirdStarGivesMorePowerThanSecond() {
        val second = UpgradeEvaluator.evaluate(troop(ones = 1, inBuild = true), env(), rules)
        val third = UpgradeEvaluator.evaluate(troop(ones = 0, twos = 1, inBuild = true), env(), rules)
        assertTrue(third.powerGain > second.powerGain)
    }
    @Test fun buildDoublesPower() {
        val inB = UpgradeEvaluator.evaluate(troop(inBuild = true), env(), rules)
        val out = UpgradeEvaluator.evaluate(troop(inBuild = false), env(), rules)
        assertEquals(inB.powerGain, out.powerGain * 2, 1e-9)
    }
    @Test fun expensiveTroopIsStronger() =
        assertTrue(UpgradeEvaluator.evaluate(troop(cost = 5), env(), rules).powerGain > UpgradeEvaluator.evaluate(troop(cost = 1), env(), rules).powerGain)

    // ---- вердикты
    @Test fun pushWhenChanceHigh() = assertEquals(UpgradeVerdict.PUSH, UpgradeEvaluator.evaluate(troop(), env(elixir = 8, rounds = 8), rules).verdict)
    @Test fun waitWhenChanceMedium() = assertEquals(UpgradeVerdict.WAIT, UpgradeEvaluator.evaluate(troop(), env(elixir = 4, rounds = 2), rules).verdict)
    @Test fun skipWhenChanceLow() = assertEquals(UpgradeVerdict.SKIP, UpgradeEvaluator.evaluate(troop(), env(elixir = 0, rounds = 1), rules).verdict)
    @Test fun worksWithoutPoolData() {
        val e = UpgradeEvaluator.evaluate(troop(), Env(elixir = 5, roundsLeft = 5), rules)
        assertTrue(e.slotChance > 0.0)
    }

    // ---- ранжирование
    @Test fun readyFirstImpossibleLast() {
        val list = listOf(
            TroopHolding("imp", "Нет", 2, 1, 0),
            TroopHolding("push", "Копить", 2, 1, 0),
            TroopHolding("ready", "Слить", 2, 2, 0),
        )
        // У «imp» нет войск такой цены в пуле: цена 9.
        val r = UpgradeEvaluator.rank(listOf(list[0].copy(cost = 9), list[1], list[2]), env(), rules)
        assertEquals("ready", r.first().id)
        assertEquals("imp", r.last().id)
    }
    @Test fun fromLinesKeepsCountsAndBuild() {
        val lines = listOf(Line("k", "k", "clan", 3, 1, 1))
        val h = UpgradeEvaluator.fromLines(lines, Build(setOf("clan"))) { "Имя" }.single()
        assertEquals(1, h.ones); assertEquals(1, h.twos); assertEquals(3, h.cost); assertTrue(h.inBuild)
    }
    @Test fun describeMentionsName() {
        val e = UpgradeEvaluator.evaluate(troop(), env(), rules)
        assertTrue(UpgradeEvaluator.describe(e).contains("Воин"))
    }
}
