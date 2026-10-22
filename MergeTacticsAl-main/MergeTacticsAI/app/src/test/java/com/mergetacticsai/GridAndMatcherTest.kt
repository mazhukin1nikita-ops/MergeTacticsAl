package com.mergetacticsai
import com.mergetacticsai.strategy.*
import com.mergetacticsai.vision.CardMatcher
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GridAndMatcherTest {
    // ---- сетка
    @Test fun gridSize() = assertEquals(24, HexGrid.cells.size)
    @Test fun gridInsidePlayerHalf() = assertTrue(HexGrid.cells.all { it.y in 0.55f..0.74f && it.x in 0.1f..0.9f })
    @Test fun rowsAreOffsetByHalfCell() {
        val a = HexGrid.cells.first { it.row == 4 && it.col == 0 }; val b = HexGrid.cells.first { it.row == 5 && it.col == 0 }
        assertEquals(67.5f / 1080f, b.x - a.x, 0.001f)
    }
    @Test fun gridIsSymmetricAroundCenter() {
        val xs = HexGrid.cells.map { it.x }
        assertEquals(0.5f, (xs.min() + xs.max()) / 2f, 0.01f)
    }
    @Test fun knownUnitsSitOnCells() {
        val c4 = HexGrid.cells.first { it.row == 4 && it.col == 3 }; val c7 = HexGrid.cells.first { it.row == 7 && it.col == 3 }
        assertEquals(575f, c4.x * 1080f, 1f); assertEquals(642.5f, c7.x * 1080f, 1f)
    }
    @Test fun preferredStartsNearCenter() = assertTrue(kotlin.math.abs(HexGrid.preferred.first().x - 0.5f) < 0.12f)

    // ---- пауза 20 секунд
    @Test fun gateBlocksFor20s() {
        var t = 1000L; val g = PlaceGate(20_000) { t }
        assertTrue(g.canTry()); g.onFail()
        assertFalse(g.canTry()); t += 19_999; assertFalse(g.canTry()); t += 2; assertTrue(g.canTry())
    }
    @Test fun gateResetsOnSuccess() { var t = 0L; val g = PlaceGate(20_000) { t }; g.onFail(); g.onSuccess(); assertTrue(g.canTry()) }

    // ---- выбор 7 карт для арены
    private fun unit(slot: Int, cost: Int, troop: String?, two: Boolean = false) = BenchUnit(slot, null, cost, null, troop, two)
    @Test fun onlyChosenTroopsGoToArena() {
        val bench = listOf(unit(0, 5, "pekka"), unit(1, 2, "knight"), unit(2, 3, null))
        assertEquals(1, CycleStrategy.pickPlacement(bench, Build(emptySet(), setOf("knight")), setOf("knight")))
    }
    @Test fun nothingPlacedIfNoChosenTroopOnBench() =
        assertNull(CycleStrategy.pickPlacement(listOf(unit(0, 5, "pekka")), Build(emptySet(), setOf("knight")), setOf("knight")))
    @Test fun emptyChoiceKeepsOldBehaviour() =
        assertEquals(0, CycleStrategy.pickPlacement(listOf(unit(0, 5, "pekka"), unit(1, 2, "knight")), Build(emptySet())))

    // ---- узнавание карточек по эталонам
    private fun ref(seed: Int) = FloatArray(300) { i -> (Math.sin((i * (seed + 3)).toDouble()) * 1.2).toFloat() }
    @Test fun matcherPicksClosest() {
        val m = CardMatcher(mapOf("a" to ref(1), "b" to ref(5), "c" to ref(9)))
        assertEquals("b", m.best(ref(5)))
    }
    @Test fun matcherRejectsLowConfidence() {
        val m = CardMatcher(mapOf("a" to ref(1), "b" to ref(5)))
        assertNull(m.best(FloatArray(300)))
    }
    @Test fun refsFileHasAllCurrentPoolCards() {
        val o = org.json.JSONObject(File("src/main/assets/card_refs.json").readText())
        assertEquals(29, o.keys().asSequence().map { it.substringBefore('#') }.toSet().size)
        assertTrue(o.keys().asSequence().all { o.getJSONArray(it).length() == 300 })
    }
    @Test fun everyTroopHasCleanRefAndAugmentedOnes() {
        val o = org.json.JSONObject(File("src/main/assets/card_refs.json").readText())
        val ids = o.keys().asSequence().map { it.substringBefore('#') }.toSet()
        for (id in ids) {
            assertTrue("нет чистого эталона $id", o.has(id))
            assertTrue("нет эталонов с помехами $id", o.has("$id#1"))
        }
    }
    @Test fun matcherScoresTroopByItsBestRefAndIgnoresOwnTwins() {
        // два эталона одного войска почти одинаковы: отрыв считается до другого войска, а не до близнеца
        val m = CardMatcher(mapOf("a" to ref(1), "a#1" to ref(1).map { it * 0.99f }.toFloatArray(), "b" to ref(5)))
        assertEquals("a", m.best(ref(1)))
    }
    @Test fun augmentedRefIsUsedForDistortedCard() {
        val clean = ref(1)
        val shifted = FloatArray(300) { i -> clean[i] * 0.8f + ref(7)[i] * 0.45f } // «сдвинутая рамка»: ближе к другому узору
        val withAug = CardMatcher(mapOf("a" to clean, "a#1" to shifted, "b" to ref(5)))
        assertEquals("a", withAug.best(shifted))
    }
    @Test fun everyCurrentTroopHasAPhoto() {
        val current = com.mergetacticsai.data.TroopDb.parse(File("src/main/assets/troops.json").readText())
            .filter { !it.legacy && it.cost > 0 }.map { it.id }
        val missing = current.filter { !File("src/main/assets/cards/$it.webp").exists() }
        assertTrue("нет фото: $missing", missing.isEmpty())
    }
    @Test fun refsCoverCurrentPoolOfTroops() {
        val refs = org.json.JSONObject(File("src/main/assets/card_refs.json").readText()).keys().asSequence().map { it.substringBefore('#') }.toSet()
        val current = com.mergetacticsai.data.TroopDb.parse(File("src/main/assets/troops.json").readText())
            .filter { !it.legacy && it.cost > 0 }.map { it.id }.toSet()
        assertEquals(current, refs)
    }
}
