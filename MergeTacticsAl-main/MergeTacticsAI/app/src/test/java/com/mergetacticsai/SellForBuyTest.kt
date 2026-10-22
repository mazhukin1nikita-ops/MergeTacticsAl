package com.mergetacticsai

import com.mergetacticsai.strategy.*
import org.junit.Assert.*
import org.junit.Test

class SellForBuyTest {
    private val names = mapOf(
        "archer" to "Лучница", "pekka" to "П.Е.К.К.А.", "knight" to "Рыцарь", "giant" to "Гигант", "goblin_cage" to "Клетка гоблинов",
    )
    private fun nm(id: String?) = names[id] ?: "войско"

    // Основная сборка: свойство boss (Пекка) и свойство noble (Рыцарь).
    private val build = Build(setOf("boss"), setOf("knight"))

    private fun card(i: Int, id: String?, trait: String?, cost: Int?, affordable: Boolean, arrow: Boolean = false, sig: Long = 100L + i, allowed: Boolean = true) =
        ShopCard(i, sig, cost, trait, id, affordable, arrow, allowed)

    private fun bench(slot: Int, id: String?, trait: String?, cost: Int?, stars: Int = 1, sig: Long? = 5000L + slot) =
        OwnedUnit(Where.BENCH, slot, id, trait, cost, stars, sig, nm(id))

    private fun board(ix: Int, id: String, trait: String?, cost: Int?, stars: Int = 1) =
        OwnedUnit(Where.BOARD, ix, id, trait, cost, stars, null, nm(id))

    private fun choose(
        elixir: Int?, cards: List<ShopCard>, owned: List<OwnedUnit>, space: Boolean = true, b: Build = build,
        arrows: List<Long> = emptyList(), rules: SwapRules = SwapRules(),
    ) = SellForBuy.choose(elixir, cards, owned, space, b, arrows, { id -> nm(id) }, rules)

    // ---- пример из задачи: Лучница за 2, в магазине ПЕККА за 5, эликсира 4
    @Test fun archerIsSoldToBuyPekka() {
        val offer = choose(4, listOf(card(0, "pekka", "boss", 5, affordable = false)), listOf(bench(0, "archer", "clan", 2)))!!
        assertEquals("archer", offer.victim.troopId)
        assertEquals("pekka", offer.card.troopId)
        assertEquals(1, offer.refund)
        assertEquals(4, offer.elixirBefore)
        assertEquals(5, offer.elixirAfter)
        assertTrue(offer.net > 0.4 && offer.gain > offer.loss)
        assertTrue(offer.reason.contains("Лучница") && offer.reason.contains("П.Е.К.К.А."))
    }

    @Test fun noSaleWhenElixirStillNotEnough() =
        assertNull(choose(2, listOf(card(0, "pekka", "boss", 5, false)), listOf(bench(0, "archer", "clan", 2))))

    @Test fun noSaleWhenCardIsAffordableAndThereIsSpace() =
        assertNull(choose(6, listOf(card(0, "pekka", "boss", 5, true)), listOf(bench(0, "archer", "clan", 2))))

    @Test fun cardOutsideBuildIsNeverTheReason() =
        assertNull(choose(4, listOf(card(0, "giant", "clan", 3, false)), listOf(bench(0, "archer", "clan", 2)), b = Build(setOf("boss"))))

    @Test fun noBuildMeansNoSales() =
        assertNull(choose(4, listOf(card(0, "pekka", "boss", 5, false)), listOf(bench(0, "archer", "clan", 2)), b = Build(emptySet())))

    @Test fun cardBlockedByUserPolicyIsSkipped() =
        assertNull(choose(4, listOf(card(0, "pekka", "boss", 5, false, allowed = false)), listOf(bench(0, "archer", "clan", 2))))

    @Test fun cardWithoutKnownCostIsSkipped() =
        assertNull(choose(4, listOf(card(0, "pekka", "boss", null, false)), listOf(bench(0, "archer", "clan", 2))))

    @Test fun unknownElixirMeansNoDecision() =
        assertNull(choose(null, listOf(card(0, "pekka", "boss", 5, false)), listOf(bench(0, "archer", "clan", 2))))

    // ---- кого можно продавать
    @Test fun buildUnitsAreNeverSold() =
        assertNull(choose(4, listOf(card(0, "pekka", "boss", 5, false)), listOf(bench(0, "knight", "noble", 2))))

    @Test fun unitWithPairOnBenchIsKept() {
        val owned = listOf(bench(0, "archer", "clan", 2), bench(1, "archer", "clan", 2))
        assertNull(choose(4, listOf(card(0, "pekka", "boss", 5, false)), owned))
    }

    @Test fun unitMatchingMergeArrowIsKept() {
        val owned = listOf(bench(0, "archer", "clan", 2, sig = 777L))
        assertNull(choose(4, listOf(card(0, "pekka", "boss", 5, false)), owned, arrows = listOf(777L)))
    }

    @Test fun unitWithUnknownCostIsKept() =
        assertNull(choose(4, listOf(card(0, "pekka", "boss", 5, false)), listOf(bench(0, "archer", "clan", null))))

    @Test fun tooSmallGainDoesNotJustifySale() {
        // Клетка гоблинов (3) из сборки против Лучницы вне сборки: выигрыш около 0,85, а порог поставлен выше.
        val strict = SwapRules(minNet = 5.0)
        assertNull(choose(2, listOf(card(0, "goblin_cage", "goblin", 3, false)), listOf(bench(0, "archer", "clan", 2)), b = Build(setOf("goblin")), rules = strict))
    }

    @Test fun ratioGuardStopsChurn() {
        val rules = SwapRules(minNet = 0.0, minRatio = 10.0)
        assertNull(choose(4, listOf(card(0, "pekka", "boss", 5, false)), listOf(bench(0, "archer", "clan", 2)), rules = rules))
    }

    // ---- выбор лучшей жертвы
    @Test fun cheapestWorthlessVictimIsChosen() {
        val owned = listOf(bench(0, "giant", "clan", 3), bench(1, "bomber", "undead", 2))
        // Эликсира 4: хватит продать любого, но потеря Подрывника меньше.
        val offer = choose(4, listOf(card(0, "pekka", "boss", 5, false)), owned)!!
        assertEquals(1, offer.victim.index)
    }

    @Test fun onlyTheNeededRefundIsConsidered() {
        // Эликсира 1: Лучница вернёт 1 (всего 2 — мало), Гигант вернёт 2 (всего 3 — мало), 2★ Лучница вернёт 3 (всего 4 — мало).
        assertNull(choose(1, listOf(card(0, "pekka", "boss", 5, false)), listOf(bench(0, "archer", "clan", 2), bench(1, "giant", "clan", 3))))
        val offer = choose(1, listOf(card(0, "pekka", "boss", 5, false)), listOf(bench(0, "giant", "clan", 3, stars = 2)))!!
        assertEquals(5, offer.refund)
    }

    // ---- возврат при продаже
    @Test fun refundRule() {
        assertEquals(1, SellForBuy.refund(2, 1))
        assertEquals(4, SellForBuy.refund(5, 1))
        assertEquals(3, SellForBuy.refund(2, 2))
        assertEquals(7, SellForBuy.refund(2, 3))
        assertEquals(0, SellForBuy.refund(0, 1))
    }

    // ---- нет места на скамейке
    @Test fun fullBenchSellsBenchUnitEvenIfElixirEnough() {
        val owned = listOf(bench(0, "archer", "clan", 2))
        val offer = choose(6, listOf(card(0, "pekka", "boss", 5, true)), owned, space = false)!!
        assertEquals(Where.BENCH, offer.victim.where)
        assertTrue(offer.reason.contains("нет места"))
    }

    @Test fun fullBenchDoesNotSellFromBoard() =
        assertNull(choose(6, listOf(card(0, "pekka", "boss", 5, true)), listOf(board(10, "archer", "clan", 2)), space = false))

    @Test fun boardUnitCanBeSoldForElixirWhenBenchHasSpace() {
        val offer = choose(4, listOf(card(0, "pekka", "boss", 5, false)), listOf(board(10, "archer", "clan", 2)))!!
        assertEquals(Where.BOARD, offer.victim.where)
        assertEquals(10, offer.victim.index)
    }

    @Test fun mergeArrowCardWithFullBenchNeedsNoSale() =
        assertNull(choose(6, listOf(card(0, "pekka", "boss", 5, true, arrow = true)), listOf(bench(0, "archer", "clan", 2)), space = false))

    // ---- свойства и стрелки
    @Test fun sellingLastUnitAtTraitThresholdCostsExtra() {
        val c = listOf(card(0, "pekka", "boss", 5, false))
        // Клан набрал 2 разных войска — порог. Продажа Гиганта ломает его: потеря на 0,5 выше, чем без порога.
        val withThreshold = choose(4, c, listOf(bench(0, "giant", "clan", 3), bench(1, "archer", "clan", 2)))!!
        val alone = choose(4, c, listOf(bench(0, "giant", "clan", 3)))!!
        assertEquals("giant", withThreshold.victim.troopId)
        assertEquals(0.5, withThreshold.loss - alone.loss, 1e-9)
    }

    @Test fun archerIsKeptHarderThanOtherUnits() {
        val c = listOf(card(0, "pekka", "boss", 5, false))
        val archer = choose(4, c, listOf(bench(0, "archer", "clan", 2)))!!
        val barbarian = choose(4, c, listOf(bench(0, "barbarian", "clan", 2)))!!
        assertTrue(archer.loss > barbarian.loss)
    }

    @Test fun firstBossGivesTraitBonus() {
        val c = listOf(card(0, "pekka", "boss", 5, false))
        val plain = SwapRules(traitStep = 0.0)
        val withBonus = choose(4, c, listOf(bench(0, "archer", "clan", 2)))!!
        val without = choose(4, c, listOf(bench(0, "archer", "clan", 2)), rules = plain)!!
        assertEquals(0.5, withBonus.gain - without.gain, 1e-9)
    }

    @Test fun mergeArrowCardIsWorthMore() {
        val plain = choose(4, listOf(card(0, "pekka", "boss", 5, false)), listOf(bench(0, "archer", "clan", 2)))!!
        val arrow = choose(4, listOf(card(0, "pekka", "boss", 5, false, arrow = true)), listOf(bench(0, "archer", "clan", 2)))!!
        assertTrue(arrow.gain > plain.gain)
    }

    @Test fun bestCardIsPickedAmongSeveral() {
        val cards = listOf(card(0, "knight", "noble", 2, false), card(1, "pekka", "boss", 5, false))
        val offer = choose(4, cards, listOf(bench(0, "archer", "clan", 2)))!!
        assertEquals("pekka", offer.card.troopId)
    }

    @Test fun unitValueGrowsWithCostAndStars() {
        val r = SwapRules()
        assertTrue(SellForBuy.unitValue(5, 1, r) > SellForBuy.unitValue(2, 1, r))
        assertTrue(SellForBuy.unitValue(2, 2, r) > SellForBuy.unitValue(2, 1, r))
        assertEquals(2.2, SellForBuy.unitValue(5, 1, r), 1e-9)
    }
}
