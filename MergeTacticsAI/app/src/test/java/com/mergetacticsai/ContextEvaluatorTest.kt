package com.mergetacticsai

import com.mergetacticsai.map.CombatContext
import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.PlacedUnit
import com.mergetacticsai.map.UnitStats
import com.mergetacticsai.strategy.*
import org.junit.Assert.*
import org.junit.Test

class ContextEvaluatorTest {
    // В пуле 40 войск: шанс одной карточки в слоте магазина = 1/40.
    private val tiers = mapOf(1 to 8, 2 to 10, 3 to 10, 4 to 8, 5 to 4)
    private fun env(elixir: Int? = 8, rounds: Int = 8) = Env(elixir = elixir, income = 4, roundsLeft = rounds, shopSlots = 3, tiers = tiers)
    private val rules = UpgradeRules()
    private val clanTiers = mapOf("clan" to listOf(2, 4, 6), "noble" to listOf(2, 4, 6), "goblin" to listOf(2, 4, 6), "x" to listOf(4, 6))
    private fun line(key: String, trait: String? = null, cost: Int = 2, ones: Int = 1, twos: Int = 0) = Line(key, key, trait, cost, ones, twos)
    private fun ev(l: Line, ctx: UpgradeContext, e: Env = env()) = ContextEvaluator.evaluate(l, "Воин", ctx, e, rules)

    // ---- активные и потенциальные черты
    @Test fun activeTraitRaisesFactor() {
        val a = line("a", "clan"); val b = line("b", "clan")
        assertEquals(1.3, ev(a, UpgradeContext(listOf(a, b), traitTiers = clanTiers)).traitFactor, 1e-9)
    }
    @Test fun almostActiveTraitIsPotential() {
        val a = line("a", "noble")
        assertEquals(1.2, ev(a, UpgradeContext(listOf(a), traitTiers = clanTiers)).traitFactor, 1e-9)
    }
    @Test fun farTraitIsWorthLess() {
        val a = line("a", "x")
        assertEquals(0.9, ev(a, UpgradeContext(listOf(a), traitTiers = clanTiers)).traitFactor, 1e-9)
    }
    @Test fun noTraitIsNeutral() {
        val a = line("a")
        assertEquals(1.0, ev(a, UpgradeContext(listOf(a))).traitFactor, 1e-9)
    }
    @Test fun counts_includeRulerBonus() {
        val g = line("g", "goblin")
        assertEquals(2, ContextEvaluator.traitCounts(UpgradeContext(listOf(g), ruler = "goblin_queen"))["goblin"])
    }

    // ---- синергия с командой
    @Test fun teamOverlapRaisesSynergy() {
        val lines = listOf(line("c1", "clan"), line("c2", "clan"), line("c3", "clan"), line("n1", "noble"))
        assertEquals(1.0 + 0.5 * 2.0 / 3.0, ev(lines[0], UpgradeContext(lines, traitTiers = clanTiers)).synergyFactor, 1e-9)
    }
    @Test fun loneTroopHasNoSynergy() {
        val a = line("a", "clan")
        assertEquals(1.0, ev(a, UpgradeContext(listOf(a), traitTiers = clanTiers)).synergyFactor, 1e-9)
    }
    @Test fun rulerTieAddsSynergy() {
        val g = line("g", "goblin")
        assertEquals(1.1, ev(g, UpgradeContext(listOf(g), traitTiers = clanTiers, ruler = "goblin_queen")).synergyFactor, 1e-9)
    }

    // ---- противник
    private fun fight(starFactor: Float = 2f): MatchupInput {
        val layout = mapOf(HexCell(5, 3) to PlacedUnit("a", 1), HexCell(1, 3) to PlacedUnit("e", 1))
        val stats = mapOf("a" to UnitStats(100f, 10f, 1f), "e" to UnitStats(150f, 10f, 1f))
        return MatchupInput(layout, stats, CombatContext(starFactor = starFactor))
    }
    @Test fun noOpponentIsNeutral() {
        val a = line("a")
        assertEquals(1.0, ev(a, UpgradeContext(listOf(a))).matchupFactor, 1e-9)
    }
    @Test fun upgradeThatWinsFightIsWorthMore() {
        val a = line("a")
        assertTrue(ev(a, UpgradeContext(listOf(a), matchup = fight())).matchupFactor > 1.0)
    }
    @Test fun unitNotOnFieldIsNeutral() {
        val b = line("b")
        assertEquals(1.0, ev(b, UpgradeContext(listOf(b), matchup = fight())).matchupFactor, 1e-9)
    }
    @Test fun starsWithoutFactorChangeNothing() {
        val a = line("a")
        assertEquals(1.0, ev(a, UpgradeContext(listOf(a), matchup = fight(starFactor = 1f))).matchupFactor, 1e-9)
    }

    // ---- правитель, модификаторы, предметы
    @Test fun spiritEmpressRefundsMerge() {
        val a = line("a", cost = 3)
        assertEquals(1.0, ev(a, UpgradeContext(listOf(a), ruler = "spirit_empress")).refund, 1e-9)
    }
    @Test fun firstFreeRefundsOneCopy() {
        val a = line("a", cost = 3)
        assertEquals(3.0, ev(a, UpgradeContext(listOf(a), modifiers = setOf("first_free"))).refund, 1e-9)
    }
    @Test fun firstFreeDoesNotHelpReadyMerge() {
        val a = line("a", cost = 3, ones = 2)
        assertEquals(0.0, ev(a, UpgradeContext(listOf(a), modifiers = setOf("first_free"))).refund, 1e-9)
    }
    @Test fun threeStarFantasyBoostsOnlyThirdStar() {
        val third = line("a", ones = 0, twos = 1)
        val second = line("b", ones = 1)
        val mods = setOf("three_star_fantasy")
        assertEquals(Math.sqrt(3.0), ev(third, UpgradeContext(listOf(third), modifiers = mods)).rulerFactor, 1e-9)
        assertEquals(1.0, ev(second, UpgradeContext(listOf(second), modifiers = mods)).rulerFactor, 1e-9)
    }
    @Test fun loongIncomeRaisesChance() {
        val a = line("a", cost = 5)
        val poor = env(elixir = 0, rounds = 1)
        val plain = ev(a, UpgradeContext(listOf(a)), poor).base.pFinish
        val loong = ev(a, UpgradeContext(listOf(a), ruler = "elixir_loong"), poor).base.pFinish
        assertTrue(loong > plain)
    }
    @Test fun traitGiftAddsIncomePerActiveTrait() {
        val a = line("a", "clan", cost = 5); val b = line("b", "clan")
        val poor = env(elixir = 0, rounds = 1)
        val plain = ev(a, UpgradeContext(listOf(a, b), traitTiers = clanTiers), poor).base.pFinish
        val gift = ev(a, UpgradeContext(listOf(a, b), traitTiers = clanTiers, modifiers = setOf("trait_gift")), poor).base.pFinish
        assertTrue(gift > plain)
    }
    @Test fun itemBoostsItsTroopOnly() {
        val a = line("a"); val b = line("b")
        val ctx = UpgradeContext(listOf(a, b), items = listOf(ItemBoost("кинжал", troopId = "a", power = 1.2)))
        assertEquals(1.2, ev(a, ctx).itemFactor, 1e-9)
        assertEquals(1.0, ev(b, ctx).itemFactor, 1e-9)
    }
    @Test fun unmodeledListsUnknownRulerAndModifiers() {
        val u = ContextEvaluator.unmodeled(UpgradeContext(emptyList(), ruler = "ronin", modifiers = setOf("echo_bench", "first_free")))
        assertEquals(2, u.size)
        assertTrue(u.any { it.startsWith("правитель") }); assertTrue(u.any { it.startsWith("модификатор") })
    }

    // ---- оставшееся время
    @Test fun moreRoundsMoreUsefulTime() {
        val a = line("a")
        val ctx = UpgradeContext(listOf(a))
        assertTrue(ev(a, ctx, env(rounds = 30)).timeFactor > ev(a, ctx, env(rounds = 8)).timeFactor)
    }
    @Test fun lateGameHitsFloor() {
        val a = line("a")
        assertEquals(ContextEvaluator.MIN_TIME, ev(a, UpgradeContext(listOf(a)), env(rounds = 2)).timeFactor, 1e-9)
    }
    @Test fun readyMergeIgnoresTime() {
        val a = line("a", ones = 2)
        assertEquals(1.0, ev(a, UpgradeContext(listOf(a)), env(rounds = 1)).timeFactor, 1e-9)
    }

    // ---- ранжирование
    @Test fun readyFirstImpossibleLast() {
        val lines = listOf(line("imp", cost = 9), line("push"), line("ready", ones = 2))
        val r = ContextEvaluator.rank(UpgradeContext(lines), env(), rules) { it.key }
        assertEquals("ready", r.first().base.id)
        assertEquals("imp", r.last().base.id)
    }
    @Test fun describeMentionsContext() {
        val a = line("a", "clan")
        assertTrue(ContextEvaluator.describe(ev(a, UpgradeContext(listOf(a), traitTiers = clanTiers))).contains("контекст"))
    }
}
