package com.mergetacticsai

import com.mergetacticsai.decision.*
import com.mergetacticsai.strategy.Build
import com.mergetacticsai.strategy.Env
import org.junit.Assert.*
import org.junit.Test

class ValueEngineTest {
    private fun f(i: Int, id: String, trait: String, cost: Int, stars: Int = 1) = Piece(Where.FIELD, i, id, trait, cost, stars)
    private fun b(i: Int, id: String, trait: String, cost: Int, stars: Int = 1) = Piece(Where.BENCH, i, id, trait, cost, stars)
    private fun offer(i: Int, id: String, trait: String, cost: Int, affordable: Boolean, arrow: Boolean = false) =
        Offer(i, id, trait, cost, affordable, arrow, true)

    private fun snap(
        pieces: List<Piece>, offers: List<Offer> = emptyList(), elixir: Int? = 4, limit: Int? = 2,
        build: Build = Build(emptySet()), unknown: Int = 0, noSell: Set<String> = emptySet(),
    ) = Snapshot(
        pieces = pieces, offers = offers, elixir = elixir, benchSlots = 5, fieldLimit = limit, fieldUnknown = unknown,
        build = build, env = Env(elixir = elixir), noSell = noSell,
    )

    private val bomber get() = f(0, "bomber", "x", 2)
    private val archer get() = f(1, "archer", "y", 2)
    private val pekka get() = offer(0, "pekka", "z", 5, affordable = false)

    // ---- стоимость продажи по правилу игры
    @Test fun refundIsCostMinusOne() {
        val p = ValueParams()
        assertEquals(1, ValueEngine.refund(f(0, "a", "x", 2), p))
        assertEquals(3, ValueEngine.refund(f(0, "a", "x", 2, 2), p)) // слитый: суммарная стоимость 4 минус 1
        assertEquals(0, ValueEngine.refund(f(0, "a", "x", 1), p))
    }

    // ---- пример с ПЕККОЙ
    @Test fun pekkaInBuildSellsTheWeakerOne() {
        val d = ValueEngine.decide(snap(listOf(bomber, archer), listOf(pekka), build = Build(setOf("x", "z"))))
        assertEquals(ActionKind.SELL_AND_BUY, d.kind)
        assertEquals(1, d.sales.size)
        assertEquals("F1", d.sales[0].piece.key) // Лучница вне сборки, Бомбер нужен
        assertEquals(0, d.buy!!.index)
        assertEquals(1, d.sales[0].refund)
        assertTrue(d.after!!.total > d.before.total)
    }

    @Test fun expensiveOffBuildCardIsNotBoughtJustBecauseItCostsMore() {
        val d = ValueEngine.decide(snap(listOf(bomber, archer), listOf(pekka), build = Build(setOf("x"))))
        assertEquals(ActionKind.NONE, d.kind)
        assertTrue(d.sales.isEmpty())
    }

    @Test fun nothingSoldWhenBothAreNeededByTheBuild() {
        val d = ValueEngine.decide(snap(listOf(bomber, archer), listOf(pekka), build = Build(setOf("x", "y"))))
        assertEquals(ActionKind.NONE, d.kind)
    }

    @Test fun withoutKnownElixirNothingIsSoldToBuy() {
        val d = ValueEngine.decide(snap(listOf(bomber, archer), listOf(pekka), elixir = null, build = Build(setOf("x", "z"))))
        assertTrue(d.sales.isEmpty())
    }

    // ---- замена на поле
    @Test fun buildHeroOnBenchReplacesOffBuildUnitOnFullField() {
        val d = ValueEngine.decide(snap(listOf(f(0, "archer", "y", 2), b(0, "bomber", "x", 2)), elixir = 0, limit = 1, build = Build(setOf("x"))))
        assertEquals(ActionKind.SELL, d.kind)
        assertEquals("F0", d.sales[0].piece.key)
    }

    @Test fun equalBuildUnitsAreNotSwapped() {
        val d = ValueEngine.decide(snap(listOf(f(0, "bomber", "x", 2), b(0, "knight", "x", 2)), elixir = 0, limit = 1, build = Build(setOf("x"))))
        assertEquals(ActionKind.NONE, d.kind)
    }

    @Test fun loneUnitIsNeverSoldForNothing() {
        val d = ValueEngine.decide(snap(listOf(archer), elixir = 4, limit = 3, build = Build(setOf("x"))))
        assertEquals(ActionKind.NONE, d.kind)
    }

    // ---- слияния и покупки
    @Test fun buyingTheCopyThatMergesIsChosen() {
        val d = ValueEngine.decide(snap(listOf(bomber), listOf(offer(0, "bomber", "x", 2, true, true)), limit = 3, build = Build(setOf("x"))))
        assertEquals(ActionKind.BUY, d.kind)
        assertEquals(0, d.buy!!.index)
    }

    @Test fun mergeAppliesStarsAndCountsMerge() {
        val (list, merges) = ValueEngine.autoMerge(listOf(f(0, "a", "x", 2), b(1, "a", "x", 2)))
        assertEquals(1, merges)
        assertEquals(1, list.size)
        assertEquals(2, list[0].stars)
        assertEquals(Where.FIELD, list[0].where)
    }

    @Test fun fullBenchSellsABenchUnitToBuyBuildCard() {
        val bench = (0 until 5).map { b(it, "u$it", "y", 2) }
        val d = ValueEngine.decide(snap(listOf(bomber) + bench, listOf(offer(0, "knight", "x", 2, true)), limit = 1, build = Build(setOf("x"))))
        assertEquals(ActionKind.SELL_AND_BUY, d.kind)
        assertEquals(Where.BENCH, d.sales[0].piece.where)
    }

    // ---- ограничения продажи
    @Test fun unidentifiedUnitsAreNeverSold() {
        val unknown = Piece(Where.FIELD, 0, null, "y", 2)
        val d = ValueEngine.decide(snap(listOf(unknown, f(1, "bomber", "x", 2)), listOf(pekka), build = Build(setOf("x", "z"))))
        assertTrue(d.sales.none { it.piece.key == "F0" })
    }

    @Test fun blockedUnitsAreNotSold() {
        val d = ValueEngine.decide(snap(listOf(bomber, archer), listOf(pekka), build = Build(setOf("x", "z")), noSell = setOf("F1")))
        assertTrue(d.sales.none { it.piece.key == "F1" })
    }

    @Test fun buyWithoutSaleNeedsAffordableCard() {
        val d = ValueEngine.decide(snap(listOf(bomber), listOf(offer(0, "knight", "x", 2, affordable = false)), limit = 3, build = Build(setOf("x"))))
        assertEquals(ActionKind.NONE, d.kind)
    }

    // ---- детерминизм и объяснение
    @Test fun sameStateSameDecision() {
        val s = snap(listOf(bomber, archer), listOf(pekka), build = Build(setOf("x", "z")))
        val a = ValueEngine.decide(s)
        val c = ValueEngine.decide(s)
        assertEquals(a.kind, c.kind)
        assertEquals(a.sales.map { it.piece.key }, c.sales.map { it.piece.key })
        assertEquals(a.buy?.index, c.buy?.index)
        assertEquals(a.reason, c.reason)
        assertEquals(a.confidence, c.confidence, 0.0)
    }

    @Test fun decisionExplainsItself() {
        val d = ValueEngine.decide(snap(listOf(bomber, archer), listOf(pekka), build = Build(setOf("x", "z"))))
        assertTrue(d.reason.isNotEmpty())
        assertTrue(d.lines.isNotEmpty())
        assertTrue(d.confidence in 0.0..1.0)
        assertTrue(d.saleCandidates.isNotEmpty())
        assertTrue(d.buyCandidates.isNotEmpty())
    }

    @Test fun synergyGainIsReported() {
        // Две «x» дают порог 2: покупка второго войска свойства x включает синергию.
        val d = ValueEngine.decide(snap(listOf(f(0, "a", "x", 2)), listOf(offer(0, "c", "x", 2, true)), limit = 3))
        assertEquals(ActionKind.BUY, d.kind)
        assertTrue(d.gainedTiers.any { it.startsWith("x") })
    }

    @Test fun statsReplaceCostBasedPower() {
        // По введённым числам Рыцарь втрое сильнее Лучницы, хотя цена одинаковая: он важнее.
        val s = Snapshot(
            pieces = listOf(f(0, "knight", "x", 2), f(1, "archer", "y", 2)), offers = listOf(pekka), elixir = 4, benchSlots = 5,
            fieldLimit = 2, fieldUnknown = 0, build = Build(emptySet()), env = Env(elixir = 4),
            statPower = mapOf("knight" to 1.5, "archer" to 0.5, "pekka" to 1.0),
        )
        val d = ValueEngine.decide(s)
        assertTrue(d.sales.none { it.piece.troopId == "knight" })
    }

    // ---- база решений
    private fun record(round: Int, troops: String, result: String, at: Long = round.toLong()) = DecisionRecord(
        at = at, session = "s", round = round, fieldState = "", shopState = "", elixir = 4, saleConsidered = "", buyConsidered = "",
        saleValue = 0, buyCost = 0, valueBefore = 1.0, valueAfter = 1.0, lostSynergies = "", gainedSynergies = "",
        action = "ничего не менять", reason = "", confidence = 1.0, result = result, fieldTroops = troops,
    )

    @Test fun recorderStoresAllFieldsAndDedups() {
        var t = 1000L
        val log = MemoryDecisionLog()
        val rec = DecisionRecorder(log, { t }, 15_000L)
        rec.setRound(3)
        val s = snap(listOf(bomber, archer), listOf(pekka), build = Build(setOf("x", "z")))
        val d = ValueEngine.decide(s)
        val id = rec.record(s, d)
        assertTrue(id > 0)
        val r = log.recent(1)[0]
        assertEquals(3, r.round)
        assertEquals(4, r.elixir)
        assertEquals(1, r.saleValue)
        assertEquals(5, r.buyCost)
        assertEquals(d.before.total, r.valueBefore, 1e-9)
        assertEquals(d.after!!.total, r.valueAfter, 1e-9)
        assertTrue(r.fieldState.contains("bomber"))
        assertTrue(r.shopState.contains("pekka"))
        assertTrue(r.saleConsidered.isNotEmpty() && r.buyConsidered.isNotEmpty())
        assertTrue(r.action.contains("продать") && r.action.contains("купить"))
        assertEquals("PLANNED", r.executed)
        assertEquals("bomber,archer", r.fieldTroops)
        assertEquals(-1L, rec.record(s, d))
        t += 20_000L
        assertTrue(rec.record(s, d) > 0)
        rec.outcome(id, true, "ok")
        assertEquals("DONE", log.recent(5).first { it.id == id }.executed)
        assertEquals(2, rec.markResult(true))
        assertTrue(log.recent(5).all { it.result == "WIN" })
    }

    @Test fun markResultWithoutRoundDoesNothing() {
        val rec = DecisionRecorder(MemoryDecisionLog())
        assertEquals(0, rec.markResult(true))
    }

    // ---- обучение по итогам боёв
    @Test fun learningNeedsEnoughRounds() {
        val recs = (1..5).map { record(it, "a", "WIN") }
        assertTrue(ValueLearning.multipliers(recs).isEmpty())
    }

    @Test fun learningShiftsWithinLimit() {
        val recs = (1..12).map { r -> if (r % 2 == 0) record(r, "a,b", "WIN") else record(r, "a", "LOSS") }
        val m = ValueLearning.multipliers(recs)
        assertEquals(1.0, m.getValue("a"), 1e-9)
        assertEquals(1.0 + ValueLearning.MAX_SHIFT, m.getValue("b"), 1e-9)
        assertTrue(m.values.all { it in 0.85..1.15 })
    }

    @Test fun learningIgnoresRarelySeenTroops() {
        val recs = (1..12).map { r -> record(r, if (r == 2) "a,rare" else "a", if (r % 2 == 0) "WIN" else "LOSS") }
        assertFalse(ValueLearning.multipliers(recs).containsKey("rare"))
    }

    @Test fun learningUsesLatestDecisionOfEachRound() {
        val old = record(1, "a,old", "LOSS", at = 1)
        val newer = record(1, "a", "LOSS", at = 5)
        val rest = (2..12).map { record(it, "a", "LOSS") }
        assertFalse(ValueLearning.multipliers(listOf(old, newer) + rest).containsKey("old"))
    }

    @Test fun learnedMultiplierChangesValue() {
        val base = snap(listOf(f(0, "a", "x", 2)))
        val boosted = Snapshot(
            pieces = base.pieces, offers = base.offers, elixir = 4, benchSlots = 5, fieldLimit = 2, fieldUnknown = 0,
            build = Build(emptySet()), env = Env(elixir = 4), learned = mapOf("a" to 1.15),
        )
        val v0 = ValueEngine.value(base.pieces, 4, base, ValueParams()).total
        val v1 = ValueEngine.value(boosted.pieces, 4, boosted, ValueParams()).total
        assertTrue(v1 > v0)
    }
}

class BuildReplacementTest {
    private fun f(i: Int, id: String, trait: String, cost: Int = 2) = Piece(Where.FIELD, i, id, trait, cost, 1)
    private fun b(i: Int, id: String, trait: String, cost: Int = 2) = Piece(Where.BENCH, i, id, trait, cost, 1)
    private fun offer(i: Int, id: String, trait: String, cost: Int, aff: Boolean) = Offer(i, id, trait, cost, aff, false, true)
    private val build = Build(setOf("x"))

    private fun snap(
        pieces: List<Piece>, offers: List<Offer> = emptyList(), elixir: Int? = 0, limit: Int? = 2, unknown: Int = 0,
        noSell: Set<String> = emptySet(), hint: Boolean = false, b: Build = build,
    ) = Snapshot(pieces, offers, elixir, 5, limit, unknown, b, Env(elixir = elixir), noSell = noSell, fieldFullHint = hint)

    @Test fun benchBuildHeroReplacesOffBuildOnFullField() {
        val d = ValueEngine.decide(snap(listOf(f(0, "archer", "y"), f(1, "bomber", "x"), b(0, "knight", "x"))))
        assertEquals(ActionKind.SELL, d.kind)
        assertEquals("F0", d.sales[0].piece.key)
        assertTrue(d.reason.isNotEmpty())
    }

    @Test fun ruleBeatsThresholdEvenForCheapDifference() {
        // Сильное войско не из сборки (цена 5) против дешёвого из сборки (цена 1): порог выгоды не помешает.
        val d = ValueEngine.decide(snap(listOf(f(0, "big", "y", 5), b(0, "tiny", "x", 1)), limit = 1))
        assertEquals(ActionKind.SELL, d.kind)
        assertEquals("F0", d.sales[0].piece.key)
    }

    @Test fun shopBuildHeroIsBoughtAfterSale() {
        val d = ValueEngine.decide(snap(listOf(f(0, "archer", "y"), f(1, "bomber", "x")), listOf(offer(0, "knight", "x", 2, true)), elixir = 4))
        assertEquals("F0", d.sales[0].piece.key)
        assertTrue(d.kind == ActionKind.SELL_AND_BUY || d.kind == ActionKind.SELL)
    }

    @Test fun notEnoughElixirSellsAndBuysWithRefund() {
        val d = ValueEngine.decide(snap(listOf(f(0, "archer", "y", 4), f(1, "bomber", "x")), listOf(offer(0, "knight", "x", 3, false)), elixir = 0))
        assertEquals(ActionKind.SELL_AND_BUY, d.kind)
        assertEquals("F0", d.sales[0].piece.key)
        assertEquals(0, d.buy!!.index)
    }

    @Test fun bothInBuildNothingChanges() {
        val d = ValueEngine.decide(snap(listOf(f(0, "a", "x"), f(1, "c", "x"), b(0, "knight", "x"))))
        assertEquals(ActionKind.NONE, d.kind)
    }

    @Test fun buildUnitOnFieldIsNeverSoldForOffBuild() {
        val d = ValueEngine.decide(snap(listOf(f(0, "a", "x"), f(1, "c", "x"), b(0, "off", "y"))))
        assertTrue(d.sales.isEmpty())
    }

    @Test fun freeCellMeansNoSale() {
        val d = ValueEngine.decide(snap(listOf(f(0, "archer", "y"), b(0, "knight", "x")), limit = 3))
        assertTrue(d.sales.isEmpty())
    }

    @Test fun unreadLimitWithHintStillReplaces() {
        val d = ValueEngine.decide(snap(listOf(f(0, "archer", "y"), b(0, "knight", "x")), limit = null, hint = true))
        assertEquals("F0", d.sales[0].piece.key)
    }

    @Test fun unreadLimitWithoutHintExplainsWhy() {
        val s = snap(listOf(f(0, "archer", "y"), b(0, "knight", "x")), limit = null)
        assertEquals("вместимость поля не прочитана", ValueEngine.replacementBlocker(s))
    }

    @Test fun unknownFieldUnitsAreReportedNotSold() {
        val s = snap(listOf(f(0, "bomber", "x"), b(0, "knight", "x")), limit = 3, unknown = 2)
        val d = ValueEngine.decide(s)
        assertTrue(d.sales.isEmpty())
        assertTrue(ValueEngine.replacementBlocker(s)!!.contains("неопознанных"))
    }

    @Test fun blockedSaleIsExplained() {
        val s = snap(listOf(f(0, "archer", "y"), f(1, "bomber", "x"), b(0, "knight", "x")), noSell = setOf("F0"))
        assertTrue(ValueEngine.replacementBlocker(s)!!.contains("заблокирована"))
        assertTrue(ValueEngine.decide(s).sales.none { it.piece.key == "F0" })
    }

    @Test fun noBuildMeansNoRule() {
        val s = snap(listOf(f(0, "archer", "y"), b(0, "knight", "x")), b = Build(emptySet()))
        assertEquals("сборка не задана или не найдена", ValueEngine.replacementBlocker(s))
    }

    @Test fun weakestOffBuildUnitIsChosen() {
        val d = ValueEngine.decide(snap(listOf(f(0, "big", "y", 5), f(1, "small", "y", 1), b(0, "knight", "x")), limit = 2))
        assertEquals("F1", d.sales[0].piece.key)
    }

    @Test fun sameStateSameDecision() {
        val s = snap(listOf(f(0, "archer", "y"), f(1, "bomber", "x"), b(0, "knight", "x")))
        val a = ValueEngine.decide(s); val c = ValueEngine.decide(s)
        assertEquals(a.sales.map { it.piece.key }, c.sales.map { it.piece.key })
        assertEquals(a.reason, c.reason)
    }
}
