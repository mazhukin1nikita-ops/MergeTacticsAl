package com.mergetacticsai

import com.mergetacticsai.adapt.*
import com.mergetacticsai.data.CatalogItem
import com.mergetacticsai.data.Troop
import com.mergetacticsai.journal.JournalEntry
import com.mergetacticsai.journal.JournalWriter
import com.mergetacticsai.journal.MemoryJournal
import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.PlacedUnit
import com.mergetacticsai.strategy.*
import org.junit.Assert.*
import org.junit.Test

class OpponentAdaptTest {
    private fun t(id: String, cost: Int, trait: String, legacy: Boolean = false) = Troop(id, id.uppercase(), cost, trait, legacy, "", "")

    // 12 войск в пуле: 4 благородных, 4 клана, 4 без свойства.
    private val troops: Map<String, Troop> = listOf(
        t("n1", 2, "noble"), t("n2", 3, "noble"), t("n3", 3, "noble"), t("n4", 4, "noble"),
        t("c1", 2, "clan"), t("c2", 3, "clan"), t("c3", 3, "clan"), t("c4", 4, "clan"),
        t("x1", 2, ""), t("x2", 3, ""), t("x3", 4, ""), t("x4", 5, ""), t("old", 3, "noble", legacy = true),
    ).associateBy { it.id }
    private val traits = listOf(
        CatalogItem("noble", "Благородный", "", true, listOf(2, 4, 6)),
        CatalogItem("clan", "Клан", "", true, listOf(2, 4, 6)),
    )
    private fun input(pool: Int = 0, own: Map<String, Int> = emptyMap(), build: Set<String> = emptySet(), traitsSel: Set<String> = emptySet(), radii: Map<String, Int> = emptyMap()) =
        AdaptInput(troops, traits, ownCopies = own, buildTroops = build, buildTraits = traitsSel, radii = radii, params = AdaptParams(copiesPerTroop = pool))

    private fun snap(label: String, round: Int, vararg u: Pair<String, Int>, source: Source = Source.MANUAL) =
        Snapshot(label, round, 1000L * round, u.map { Seen(it.first, it.second) }, source)

    private fun book(vararg s: Snapshot) = OpponentBook(s.toList())

    // ---- наблюдения и свежесть
    @Test fun recordReplacesLatestAndDetectsChange() {
        val b = OpponentBook()
        assertTrue(b.record(snap("A", 1, "n1" to 1)))
        assertFalse(b.record(snap("A", 2, "n1" to 1)))
        assertEquals(2, b.current().single().round)
        assertTrue(b.record(snap("A", 3, "n1" to 1, "n2" to 1)))
        assertEquals(2, b.current().single().units.size)
        assertEquals(2, b.history().size)
    }

    @Test fun weightFallsWithAge() {
        val p = AdaptParams()
        assertEquals(1.0, OpponentAnalyzer.weight(0, p), 1e-9)
        assertEquals(1.0, OpponentAnalyzer.weight(p.freshRounds, p), 1e-9)
        assertTrue(OpponentAnalyzer.weight(p.freshRounds + 1, p) < 1.0)
        assertEquals(0.0, OpponentAnalyzer.weight(p.dropRounds, p), 1e-9)
    }

    @Test fun staleSnapshotsAreNotUsed() {
        val a = OpponentAnalyzer.analyze(book(snap("A", 1, "n1" to 1)), 20, input())
        assertTrue(a.isEmpty)
        assertEquals(Level.NONE, a.level)
        assertEquals(1, a.stale.size)
        assertTrue(a.counters.any { it.kind == CounterKind.REFRESH })
    }

    @Test fun emptyBookGivesNoConclusions() {
        val a = OpponentAnalyzer.analyze(OpponentBook(), 3, input())
        assertTrue(a.isEmpty); assertEquals(Level.NONE, a.level)
        assertTrue(a.demand.isEmpty() && a.threats.isEmpty() && a.scarcity.isEmpty())
    }

    // ---- востребованность
    @Test fun demandCountsOpponentsAndCopies() {
        val a = OpponentAnalyzer.analyze(book(snap("A", 3, "n1" to 2, "c1" to 1), snap("B", 3, "n1" to 1)), 3, input())
        val d = a.demand.first { it.troopId == "n1" }
        assertEquals(2, d.opponents)
        assertEquals(3, d.copiesSeen) // 2★ = две копии, 1★ = одна
        assertEquals("n1", a.demand.first().troopId)
        assertEquals(2, a.traitDemand.toMap()["noble"])
    }

    @Test fun unknownUnitsAreCountedNotAttributed() {
        val a = OpponentAnalyzer.analyze(book(snap("A", 3, "n1" to 1, PlacedUnit.UNKNOWN to 1, PlacedUnit.UNKNOWN to 1)), 3, input())
        assertEquals(1, a.demand.size)
        assertTrue(a.notes.any { it.contains("Неопознанных") })
        assertEquals(Level.LOW, a.level)
    }

    // ---- дефицит
    @Test fun withoutPoolSizeScarcityIsNotClaimed() {
        val a = OpponentAnalyzer.analyze(book(snap("A", 3, "n1" to 2)), 3, input(pool = 0))
        val s = a.scarcity.first { it.troopId == "n1" }
        assertEquals(Stock.UNKNOWN, s.stock)
        assertNull(s.remaining)
        assertEquals(Level.LOW, s.level)
        assertTrue(a.notes.any { it.contains("Размер пула не задан") })
    }

    @Test fun poolShrinksWithSeenAndOwnCopies() {
        val a = OpponentAnalyzer.analyze(book(snap("A", 3, "n1" to 2), snap("B", 3, "n1" to 2)), 3, input(pool = 9, own = mapOf("n1" to 2)))
        val s = a.scarcity.first { it.troopId == "n1" }
        val r = s.remaining!!
        assertTrue(r.low <= r.nominal && r.nominal <= r.high)
        assertTrue(r.high <= 9 - 4 - 2) // видно 4, у вас 2: скрытые только уменьшают
        assertEquals(4, s.seen); assertEquals(2, s.own)
        assertNotEquals(Stock.UNKNOWN, s.stock)
    }

    @Test fun hiddenIsRangeAndGrowsWithHiddenCards() {
        val a = OpponentAnalyzer.analyze(book(snap("A", 3, "n1" to 1, "n2" to 1)), 3, input(pool = 9))
        val h = a.scarcity.first { it.troopId == "n1" }.hiddenExpected
        assertTrue(h.low <= h.nominal && h.nominal <= h.high)
        assertEquals(0.0, h.low, 1e-9) // hiddenLow = 0
        assertTrue(h.high > 0.0)
    }

    @Test fun hiddenCardsFavourOwnTrait() {
        val a = OpponentAnalyzer.analyze(book(snap("A", 3, "n1" to 1, "n2" to 1, "n3" to 1)), 3, input(pool = 9))
        val noble = a.scarcity.first { it.troopId == "n1" }.hiddenExpected.nominal
        val other = OpponentAnalyzer.analyze(book(snap("A", 3, "n1" to 1, "n2" to 1, "n3" to 1)), 3, input(pool = 9, own = mapOf("x1" to 1)))
            .scarcity.first { it.troopId == "x1" }.hiddenExpected.nominal
        assertTrue(noble > other)
    }

    // ---- комбинации
    @Test fun comboNearThresholdShowsMissingAndChance() {
        val a = OpponentAnalyzer.analyze(book(snap("A", 3, "n1" to 1, "n2" to 1, "n3" to 1)), 3, input())
        val c = a.combos.first { it.trait == "noble" }
        assertEquals(1, c.tier); assertEquals(4, c.next); assertEquals(1, c.missing)
        assertTrue(c.completeChance!! in 0.0..1.0)
        assertTrue(c.text.contains("около"))
        assertFalse(c.text.contains("точно"))
    }

    @Test fun comboLevelNeverHighForHidden() {
        val b = book(snap("A", 3, "n1" to 1, "n2" to 1, "n3" to 1), snap("B", 3, "c1" to 1, "c2" to 1), snap("C", 3, "x1" to 1, "x2" to 1))
        val a = OpponentAnalyzer.analyze(b, 3, input(pool = 9))
        assertEquals(Level.HIGH, a.level)
        assertTrue(a.combos.filter { it.completeChance != null }.all { it.level != Level.HIGH })
        assertTrue(a.scarcity.all { it.level != Level.HIGH })
    }

    // ---- угрозы
    @Test fun threeStarAndActiveTraitRaiseThreat() {
        val strong = snap("A", 3, "n1" to 3, "n2" to 2, "n3" to 1)
        val weak = snap("B", 3, "x1" to 1)
        val a = OpponentAnalyzer.analyze(book(strong, weak), 3, input())
        assertEquals("A", a.threats.first().opponent)
        assertTrue(a.threats.first().score > a.threats.last().score)
        assertTrue(a.threats.first().reasons.any { it.contains("3★") })
        assertTrue(a.threats.first().reasons.any { it.contains("Благородный") })
    }

    @Test fun threatConfidenceDropsWithAge() {
        val a = OpponentAnalyzer.analyze(book(snap("A", 1, "n1" to 1)), 5, input())
        assertEquals(Level.LOW, a.threats.single().confidence)
        assertEquals(4, a.threats.single().ageRounds)
    }

    @Test fun powerComparisonUsesOurUnitsWhenKnown() {
        val inp = AdaptInput(troops, traits, ownUnits = listOf(Seen("x1", 1)))
        val a = OpponentAnalyzer.analyze(book(snap("A", 3, "x4" to 1)), 3, inp)
        assertTrue(a.threats.single().reasons.any { it.contains("суммарная сила") })
        val b = OpponentAnalyzer.analyze(book(snap("A", 3, "x4" to 1)), 3, input())
        assertTrue(b.threats.single().reasons.any { it.contains("сравнить силу не с чем") })
    }

    // ---- контрмеры
    @Test fun noInventedCounterForTraits() {
        val a = OpponentAnalyzer.analyze(book(snap("A", 3, "n1" to 1, "n2" to 1)), 3, input())
        val k = a.counters.first { it.kind == CounterKind.NO_DATA }
        assertTrue(k.text.contains("не придумывает"))
    }

    @Test fun contestedBuildCardSuggestsPairNow() {
        val b = book(snap("A", 3, "n1" to 1), snap("B", 3, "n1" to 1))
        val a = OpponentAnalyzer.analyze(b, 3, input(pool = 9, own = mapOf("n1" to 1), build = setOf("n1")))
        assertTrue(a.counters.any { it.kind == CounterKind.PAIR_NOW && it.text.contains("N1") })
    }

    @Test fun openLaneWhenNobodyRunsOurTrait() {
        val a = OpponentAnalyzer.analyze(book(snap("A", 3, "n1" to 1, "n2" to 1)), 3, input(own = mapOf("c1" to 1)))
        assertTrue(a.counters.any { it.kind == CounterKind.OPEN_LANE && it.text.contains("Клан") })
    }

    @Test fun formationAdviceNeedsUserRadii() {
        val units = arrayOf("c1" to 1, "c2" to 1, "c3" to 1, "c4" to 1)
        val none = OpponentAnalyzer.analyze(book(snap("A", 3, *units)), 3, input())
        assertTrue(none.counters.none { it.kind == CounterKind.FORMATION })
        val withR = OpponentAnalyzer.analyze(book(snap("A", 3, *units)), 3, input(radii = mapOf("c1" to 4, "c2" to 4, "c3" to 3, "c4" to 1)))
        assertTrue(withR.counters.any { it.kind == CounterKind.FORMATION })
    }

    // ---- влияние на бота
    @Test fun biasEmptyWhenConfidenceLow() {
        val a = OpponentAnalyzer.analyze(book(snap("A", 3, "n1" to 2)), 3, input(pool = 4))
        assertEquals(Level.MEDIUM, a.level)
        val low = OpponentAnalyzer.analyze(book(snap("A", 1, "n1" to 2)), 5, input(pool = 4))
        assertEquals(Level.LOW, low.level)
        assertTrue(OpponentAnalyzer.buyBias(low, input(pool = 4)).isEmpty())
    }

    @Test fun biasPenalisesContestedScarceCardsOutsideBuild() {
        val b = book(snap("A", 3, "x1" to 2), snap("B", 3, "x1" to 2))
        val inp = input(pool = 5)
        val a = OpponentAnalyzer.analyze(b, 3, inp)
        val bias = OpponentAnalyzer.buyBias(a, inp)
        assertTrue(bias.getValue("x1") < 0.0)
        assertTrue(bias.values.all { it >= -OpponentAnalyzer.MAX_BIAS })
        val inBuild = OpponentAnalyzer.buyBias(a, input(pool = 5, build = setOf("x1")))
        assertFalse(inBuild.containsKey("x1"))
    }

    @Test fun biasDoesNotBlockMergeAndLowersBuyScore() {
        val tiers = mapOf(2 to 10, 3 to 10)
        val env = Env(elixir = 8, income = 4, roundsLeft = 8, shopSlots = 3, tiers = tiers)
        val cards = listOf(ShopCard(0, 0L, 2, null, "x1", true, false, true))
        val noBias = AdaptivePlanner.decide(cards, listOf("x1"), emptyList(), Build(emptySet()), true, env, emptySet())
        val withBias = AdaptivePlanner.decide(cards, listOf("x1"), emptyList(), Build(emptySet()), true, env, emptySet(), mapOf("x1" to -0.1))
        val a = noBias.scenarios.first { it.label.startsWith("купить") }.value
        val b = withBias.scenarios.first { it.label.startsWith("купить") }.value
        assertEquals(a - 0.1, b, 1e-9)
        val merge = listOf(ShopCard(0, 0L, 2, null, "x1", true, true, true))
        val d = AdaptivePlanner.decide(merge, listOf("x1"), listOf(Line("x1", "x1", null, 2, 1, 0)), Build(emptySet()), true, env, emptySet(), mapOf("x1" to -0.1))
        assertEquals(0, d.index)
    }

    // ---- хранение
    @Test fun snapshotCodecRoundTrips() {
        val list = listOf(snap("Соперник 1", 3, "n1" to 2, PlacedUnit.UNKNOWN to 1), Snapshot("A|B;C", 4, 5L, listOf(Seen("c1", 3)), Source.SCAN))
        val back = SnapshotCodec.decode(SnapshotCodec.encode(list))
        assertEquals(2, back.size)
        assertEquals(list[0], back[0])
        assertEquals("A B C", back[1].label)
        assertEquals(Source.SCAN, back[1].source)
        assertTrue(SnapshotCodec.decode("мусор\n||||").isEmpty())
    }

    @Test fun snapshotFromLayoutTakesOnlyEnemySide() {
        val layout = mapOf(HexCell(0, 0) to PlacedUnit("n1", 2), HexCell(5, 1) to PlacedUnit("c1", 1), HexCell(1, 2) to PlacedUnit("?", 1))
        val s = Snapshot.fromLayout("A", 2, 0L, layout, Source.MANUAL)
        assertEquals(2, s.units.size)
        assertEquals(1, s.unknownCount)
    }

    // ---- журнал
    @Test fun journalStoresReasonAndOutcome() {
        val j = MemoryJournal()
        val w = JournalWriter(j, clock = { 1000L })
        w.setRound(4)
        val id = w.log("BUY", "Покупка", "лучший сценарий", "детали", "PLANNED")
        w.outcome(id, true, "куплено")
        val e = j.recent(10).single()
        assertEquals("DONE", e.status); assertEquals("куплено", e.outcome)
        assertEquals(4, e.round); assertEquals("лучший сценарий", e.reason)
        assertEquals(1000L, e.outcomeAt)
    }

    @Test fun journalDedupSuppressesRepeats() {
        var now = 0L
        val j = MemoryJournal()
        val w = JournalWriter(j, clock = { now }, quietMs = 15_000L)
        w.log("SAVE", "копим", "r", dedupKey = "k")
        now = 5_000; w.log("SAVE", "копим", "r", dedupKey = "k")
        assertEquals(1, j.count())
        now = 20_000; w.log("SAVE", "копим", "r", dedupKey = "k")
        assertEquals(2, j.count())
        w.log("BUY", "x", "r"); w.log("BUY", "x", "r")
        assertEquals(4, j.count())
    }

    @Test fun journalWriterSurvivesFailingStorage() {
        val bad = object : com.mergetacticsai.journal.DecisionJournal {
            override fun add(e: JournalEntry): Long = throw IllegalStateException("диск")
            override fun setOutcome(id: Long, status: String, outcome: String, at: Long) = throw IllegalStateException("диск")
            override fun recent(limit: Int) = emptyList<JournalEntry>()
            override fun count() = 0
            override fun clear() {}
        }
        val w = JournalWriter(bad)
        assertEquals(-1L, w.log("BUY", "x", "r"))
        w.outcome(5L, true, "x")
        assertEquals(-1L, JournalWriter(null).log("BUY", "x", "r"))
    }

    @Test fun controllerSummaryNamesConfidence() {
        val a = OpponentAnalyzer.analyze(book(snap("A", 3, "n1" to 1, "n2" to 1, "n3" to 1)), 3, input(pool = 9))
        assertTrue(a.level != Level.NONE)
        assertTrue(a.scarcity.all { it.text.contains("вероятно") })
    }
}
