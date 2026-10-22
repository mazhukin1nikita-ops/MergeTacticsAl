package com.mergetacticsai
import com.mergetacticsai.strategy.*
import com.mergetacticsai.vision.TraitIcons
import org.junit.Assert.*
import org.junit.Test

class CycleTest {
    private fun card(i: Int, cost: Int?, trait: String? = null, arrow: Boolean = false, aff: Boolean = true, allowed: Boolean = true, troop: String? = null, sig: Long = i.toLong() * 0x0F0F0F0F0F0F0F0FL + i) =
        ShopCard(i, sig, cost, trait, troop, aff, arrow, allowed)
    private fun unit(slot: Int, cost: Int?, trait: String? = null, two: Boolean = false, sig: Long? = null, troop: String? = null) = BenchUnit(slot, sig, cost, trait, troop, two)
    private val none = Build(emptySet()); private val clan = Build(setOf("clan"))

    // ---- покупка
    @Test fun cheapestMergeFirst() = assertEquals(1, CycleStrategy.pickBuy(listOf(card(0, 4, arrow = true), card(1, 2, arrow = true)), none, true))
    @Test fun mergeBeatsSingle() = assertEquals(2, CycleStrategy.pickBuy(listOf(card(0, 2), card(1, 3, "clan"), card(2, 4, arrow = true)), clan, true))
    @Test fun buildMergeBeatsOutsideMerge() = assertEquals(0, CycleStrategy.pickBuy(listOf(card(0, 4, "clan", arrow = true), card(1, 2, arrow = true)), clan, true))
    @Test fun stageAAllowsExpensive() = assertEquals(0, CycleStrategy.pickBuy(listOf(card(0, 5)), none, true))
    @Test fun stageBSkipsExpensiveOutsideBuild() = assertNull(CycleStrategy.pickBuy(listOf(card(0, 5)), clan, true))
    @Test fun stageBBuysCheapFirstCopy() = assertEquals(0, CycleStrategy.pickBuy(listOf(card(0, 2)), clan, true))
    @Test fun fullBenchOnlyMerge() {
        assertNull(CycleStrategy.pickBuy(listOf(card(0, 2)), none, false))
        assertEquals(1, CycleStrategy.pickBuy(listOf(card(0, 2), card(1, 3, arrow = true)), none, false))
    }
    @Test fun skipsDimAndDisallowed() = assertNull(CycleStrategy.pickBuy(listOf(card(0, 2, aff = false), card(1, 2, allowed = false)), none, true))
    @Test fun archerPreferred() = assertEquals(1, CycleStrategy.pickBuy(listOf(card(0, 2), card(1, 2, troop = "archer")), none, true))

    // ---- продажа
    @Test fun twoStarFirst() = assertEquals(1, CycleStrategy.pickSell(listOf(unit(0, 5), unit(1, 2, two = true)), emptyList(), clan))
    @Test fun expensiveSingleBeforeCheap() = assertEquals(2, CycleStrategy.pickSell(listOf(unit(0, 2), unit(1, 3), unit(2, 5)), emptyList(), clan))
    @Test fun neverSellsBuildOrArcher() {
        assertNull(CycleStrategy.pickSell(listOf(unit(0, 5, "clan"), unit(1, 2, troop = "archer")), emptyList(), clan))
    }
    @Test fun keepsUnitWithPairInShop() =
        assertEquals(1, CycleStrategy.pickSell(listOf(unit(0, 2, sig = 0x1234L), unit(1, 3)), listOf(0x1234L), none))
    @Test fun keepsDuplicatesOnBench() =
        assertEquals(2, CycleStrategy.pickSell(listOf(unit(0, 2, sig = 0xAAAAL), unit(1, 2, sig = 0xAAAAL), unit(2, 3)), emptyList(), none))
    @Test fun nothingToSell() = assertNull(CycleStrategy.pickSell(listOf(null, null), emptyList(), none))

    // ---- расстановка и сборка
    @Test fun stageAPlacesMostExpensive() = assertEquals(2, CycleStrategy.pickPlacement(listOf(unit(0, 2), unit(1, 3), unit(2, 5)), none))
    @Test fun twoStarBeforeCost() = assertEquals(0, CycleStrategy.pickPlacement(listOf(unit(0, 2, two = true), unit(1, 5)), none))
    @Test fun stageBPlacesBuildFirst() = assertEquals(0, CycleStrategy.pickPlacement(listOf(unit(0, 2, "clan"), unit(1, 5)), clan))
    @Test fun selectedTraitsAreBuild() = assertEquals(setOf("noble"), CycleStrategy.detectBuild(setOf("noble"), mapOf("clan" to 5)).traits)
    @Test fun autoCoreNeedsThree() {
        assertFalse(CycleStrategy.detectBuild(emptySet(), mapOf("clan" to 2)).found)
        assertEquals(setOf("clan"), CycleStrategy.detectBuild(emptySet(), mapOf("clan" to 3, "boss" to 1)).traits)
    }

    // ---- значки свойств
    private fun icon(r: Int, g: Int, b: Int, shape: FloatArray? = null): IntArray {
        val bg = (0xFF shl 24) or (25 shl 16) or (25 shl 8) or 25
        val fg = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        return IntArray(256) { i ->
            val on = if (shape != null) shape[i] > 0.5f else (i / 16 in 4..11 && i % 16 in 4..11)
            if (on) fg else bg
        }
    }
    @Test fun noble() = assertEquals("noble", TraitIcons.classifyPixels(icon(240, 190, 60)))
    @Test fun clanBlue() = assertEquals("clan", TraitIcons.classifyPixels(icon(70, 170, 235)))
    @Test fun bossPink() = assertEquals("boss", TraitIcons.classifyPixels(icon(230, 50, 170)))
    @Test fun goblinGreen() = assertEquals("goblin", TraitIcons.classifyPixels(icon(120, 205, 45)))
    @Test fun skullIsUndead() = assertEquals("undead", TraitIcons.classifyPixels(icon(80, 235, 160, TraitIcons.UNDEAD_SHAPE)))
    @Test fun treeIsForest() = assertEquals("forest", TraitIcons.classifyPixels(icon(60, 200, 110, TraitIcons.FOREST_SHAPE)))
    @Test fun blankIsUnknown() = assertNull(TraitIcons.classifyPixels(IntArray(256) { (0xFF shl 24) or (40 shl 16) or (40 shl 8) or 40 }))
}
