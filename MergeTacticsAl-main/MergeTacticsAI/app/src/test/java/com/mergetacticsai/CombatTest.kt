package com.mergetacticsai

import com.mergetacticsai.ai.Point
import com.mergetacticsai.game.GameState
import com.mergetacticsai.game.GameUnit
import com.mergetacticsai.map.*
import com.mergetacticsai.strategy.*
import org.junit.Assert.*
import org.junit.Test

class CombatTest {
    private fun cell(r: Int, c: Int) = HexCell(r, c)
    private fun u(id: String, stars: Int = 1) = PlacedUnit(id, stars)
    private val s100 = UnitStats(100f, 10f, 1f)   // dps 10
    private val stats = mapOf("a" to s100, "b" to s100, "tank" to UnitStats(300f, 10f, 1f), "glass" to UnitStats(50f, 30f, 1f))
    private fun eval(layout: Map<HexCell, PlacedUnit>, ctx: CombatContext = CombatContext(), st: Map<String, UnitStats> = stats) =
        CombatModel.evaluate(layout, st, ctx)

    // ---- числа
    @Test fun statsValid() {
        assertTrue(s100.valid)
        assertFalse(UnitStats(0f, 10f, 1f).valid)
        assertEquals(10f, s100.dps, 0.001f)
    }

    @Test fun statsRoundTripAndLimits() {
        val m = mapOf("a" to UnitStats(120f, 14f, 0.8f))
        assertEquals(m, MapCodec.decodeStats(MapCodec.encodeStats(m)))
        assertTrue(MapCodec.decodeStats("a=-1:1:1;b=1:1:99;c=x:1:1;d=0:0:0;e=1:2").isEmpty())
        assertEquals(1, MapCodec.decodeStats("a=0:5:0").size)
    }

    // ---- исход боя
    @Test fun evenFightIsDraw() {
        val r = eval(mapOf(cell(4, 2) to u("a"), cell(3, 2) to u("a")))
        assertEquals(Verdict.DRAW, r.verdict)
        assertEquals(0f, r.margin, 0.001f)
    }

    @Test fun strongerSideWins() {
        val r = eval(mapOf(cell(4, 2) to u("tank"), cell(3, 2) to u("a")))
        assertEquals(Verdict.WIN, r.verdict)
        assertEquals(0f, r.enemyLeft, 0.001f)
        assertTrue(r.ownLeft > 0.5f)
        assertEquals(Verdict.LOSE, eval(mapOf(cell(4, 2) to u("a"), cell(3, 2) to u("tank"))).verdict)
    }

    @Test fun moreUnitsBeatFewer() {
        val own = mapOf(cell(4, 2) to u("a"), cell(4, 3) to u("a"))
        assertEquals(Verdict.WIN, eval(own + (cell(3, 2) to u("a"))).verdict)
    }

    @Test fun noStatsMeansUnknownAndMissingListed() {
        val r = eval(mapOf(cell(4, 2) to u("a"), cell(3, 2) to u("mystery")))
        assertEquals(Verdict.UNKNOWN, r.verdict)
        assertEquals(setOf("mystery"), r.missing)
    }

    @Test fun emptyOrOneSidedBoardIsUnknown() {
        assertEquals(Verdict.UNKNOWN, eval(emptyMap()).verdict)
        assertEquals(Verdict.UNKNOWN, eval(mapOf(cell(4, 2) to u("a"))).verdict)
    }

    @Test fun lateEngagementCostsDamage() {
        // Пара «тайком» далеко: с заданными скоростями бой начинается позже, но исход при равных силах прежний.
        val layout = mapOf(cell(7, 0) to u("a"), cell(0, 5) to u("a"))
        val r = CombatModel.evaluate(layout, stats, CombatContext(), mapOf("a" to 1), mapOf("a" to 1f))
        assertTrue(r.seconds > 1f)
    }

    // ---- звёзды
    @Test fun starFactorStrengthensStars() {
        val layout = mapOf(cell(4, 2) to u("a", 2), cell(3, 2) to u("a"))
        assertEquals(Verdict.DRAW, eval(layout).verdict)
        assertEquals(Verdict.WIN, eval(layout, CombatContext(starFactor = 1.5f)).verdict)
    }

    @Test fun threeStarFantasy() {
        val layout = mapOf(cell(4, 2) to u("a", 3), cell(3, 2) to u("a"))
        assertEquals(Verdict.DRAW, eval(layout).verdict)
        assertEquals(Verdict.WIN, eval(layout, CombatContext(modifiers = setOf("three_star_fantasy"))).verdict)
    }

    // ---- свойства
    @Test fun nobleTierNeedsTwoTroops() {
        val ctx = CombatContext(traitOf = mapOf("a" to "noble", "b" to "noble"))
        assertEquals(0f, Effects.sideBonus(listOf(u("a")), ctx).damage, 0.001f)
        assertEquals(0.15f, Effects.sideBonus(listOf(u("a"), u("b")), ctx).damage, 0.001f)
        assertEquals(0.15f, Effects.sideBonus(listOf(u("a"), u("a"), u("b")), ctx).taken, 0.001f)
    }

    @Test fun nobleMakesEvenFightWinnable() {
        val ctx = CombatContext(traitOf = mapOf("a" to "noble", "b" to "noble"))
        val own = mapOf(cell(4, 2) to u("a"), cell(4, 3) to u("b"))
        val enemy = mapOf(cell(3, 2) to u("tank"), cell(3, 3) to u("tank"))
        val plain = eval(own + enemy)
        val noble = eval(own + enemy, ctx)
        assertTrue(noble.margin > plain.margin)
    }

    @Test fun bossHpOnlyForBossTroops() {
        val ctx = CombatContext(traitOf = mapOf("a" to "boss", "b" to "clan"))
        val one = eval(mapOf(cell(4, 2) to u("a"), cell(3, 2) to u("b")), ctx)
        assertEquals(Verdict.WIN, one.verdict) // +25% HP у босса
    }

    @Test fun tiers() {
        assertEquals(0, Effects.tier("noble", 1))
        assertEquals(2, Effects.tier("noble", 5))
        assertEquals(3, Effects.tier("noble", 9))
        assertEquals(1, Effects.tier("boss", 1))
        assertEquals(4, Effects.tier("boss", 4))
    }

    // ---- правители и неучтённое
    @Test fun goblinQueenCountsAsGoblin() {
        val ctx = CombatContext(ruler = "goblin_queen", traitOf = mapOf("a" to "goblin"))
        assertEquals(2, Effects.traitCounts(listOf(u("a")), ctx)["goblin"])
    }

    @Test fun unmodeledEffectsAreReported() {
        val r = eval(
            mapOf(cell(4, 2) to u("a"), cell(3, 2) to u("a")),
            CombatContext(ruler = "echo_sage", modifiers = setOf("domination", "three_star_fantasy")),
        )
        assertTrue(r.notes.any { "echo_sage" in it && "domination" in it })
        assertFalse(r.notes.any { "three_star_fantasy" in it })
    }

    @Test fun dangerousMarkIsReported() {
        val layout = mapOf(cell(4, 2) to u("a"), cell(3, 2) to u("a"))
        val r = CombatModel.evaluate(layout, stats, CombatContext(), marks = mapOf(cell(4, 2) to CellMark.CURSED))
        assertEquals(1, r.atRisk)
    }

    // ---- позиционный ИИ
    @Test fun noDataKeepsOldOrder() {
        val free = HexGrid.cells.map { it.toHex() }
        assertEquals(HexGrid.preferred.map { it.toHex() }, PositionEngine.rank(u("a"), free, emptyMap()))
    }

    @Test fun bridgeRoundTrip() {
        for (c in HexGrid.cells) assertEquals(c, c.toHex().toGrid())
        assertNull(cell(0, 0).toGrid())
    }

    @Test fun meleeFrontRangedBack() {
        val free = HexMap.ownCells
        val ctx = PositionContext(radii = mapOf("knight" to 1, "archer" to 3))
        assertEquals(4, PositionEngine.rank(u("knight"), free, emptyMap(), ctx).first().row)
        assertEquals(7, PositionEngine.rank(u("archer"), free, emptyMap(), ctx).first().row)
    }

    @Test fun dangerousCellsGoLast() {
        val free = HexMap.ownCells
        val marks = free.filter { it.row == 4 }.associateWith { CellMark.CURSED }
        val ctx = PositionContext(radii = mapOf("knight" to 1), marks = marks)
        val ranked = PositionEngine.rank(u("knight"), free, emptyMap(), ctx)
        assertEquals(5, ranked.first().row)
        assertTrue(ranked.takeLast(6).all { it.row == 4 })
    }

    @Test fun occupiedAndEnemyCellsAreSkipped() {
        val own = mapOf(cell(4, 3) to u("knight"))
        val r = PositionEngine.rank(u("a"), listOf(cell(4, 3), cell(2, 1), cell(5, 1)), own)
        assertEquals(listOf(cell(5, 1)), r)
    }

    @Test fun combatPicksCellThatImprovesFight() {
        // Враг стреляет в ближайшего; танк впереди выигрывает, хрупкий стрелок впереди проигрывает.
        val enemy = mapOf(cell(3, 2) to u("a"))
        val ctx = PositionContext(enemy = enemy, stats = stats, radii = mapOf("a" to 1, "tank" to 1, "glass" to 1))
        val own = mapOf(cell(4, 2) to u("glass"))
        val r = PositionEngine.rank(u("tank"), listOf(cell(4, 3), cell(7, 0)), own, ctx)
        assertEquals(2, r.size)
    }

    @Test fun pickGridCellSkipsOccupiedAndBad() {
        val occ = HexGrid.cells.map { false }.toMutableList()
        val first = HexGrid.preferred[0]
        occ[HexGrid.indexOf(first)] = true
        val second = HexGrid.preferred[1]
        val bad = setOf(HexGrid.indexOf(second))
        val got = PositionEngine.pickGridCell(null, occ, bad, emptyMap())
        assertEquals(HexGrid.preferred[2], got)
    }

    @Test fun pickGridCellWithUnitUsesRole() {
        val occ = HexGrid.cells.map { false }
        val ctx = PositionContext(radii = mapOf("archer" to 3))
        assertEquals(7, PositionEngine.pickGridCell(PlacedUnit("archer"), occ, emptySet(), emptyMap(), ctx)!!.row)
    }

    @Test fun pickGridCellFullBoardIsNull() =
        assertNull(PositionEngine.pickGridCell(null, HexGrid.cells.map { true }, emptySet(), emptyMap()))

    // ---- PlacementPlanner
    private val ga = GameUnit("Лучница", 1, 2)
    private val gb = GameUnit("Рыцарь", 1, 2)
    private fun state() = GameState(0, listOf(null, null, null), listOf(ga, gb, null, null, null), listOf(null))
    private fun pt(c: HexCell) = HexMap.center(c).let { Point(it.first, it.second) }

    @Test fun plannerWithoutPositionKeepsOrder() {
        val free = listOf(pt(cell(4, 0)), pt(cell(7, 5)))
        val m = PlacementPlanner.plan(state(), 0, 5, free, emptyList())
        assertEquals(free[0], m[0].second)
        assertEquals(free[1], m[1].second)
    }

    @Test fun plannerPutsMeleeFrontAndRangedBack() {
        val front = cell(4, 2); val back = cell(7, 2)
        // Точки должны попадать в окно y 0.55..0.78: ряды 4..7 при Y0=1045 дают y от 0.58 до 0.74.
        val free = listOf(pt(back), pt(front))
        val ctx = PositionContext(radii = mapOf("archer" to 3, "knight" to 1))
        val ids = mapOf("Лучница" to "archer", "Рыцарь" to "knight")
        val m = PlacementPlanner.plan(state(), 0, 5, free, emptyList(), ctx, { g -> ids[g.name]?.let { PlacedUnit(it) } })
        assertEquals(2, m.size)
        assertEquals(pt(back), m.first { it.first == 0 }.second)   // лучница назад
        assertEquals(pt(front), m.first { it.first == 1 }.second)  // рыцарь вперёд
    }

    @Test fun plannerIgnoresUnknownUnits() {
        val free = listOf(pt(cell(7, 2)), pt(cell(4, 2)))
        val m = PlacementPlanner.plan(state(), 0, 5, free, emptyList(), PositionContext(radii = mapOf("x" to 1)), { null })
        assertEquals(free[0], m[0].second)
    }
}
