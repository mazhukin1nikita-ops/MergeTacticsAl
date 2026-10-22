package com.mergetacticsai

import com.mergetacticsai.map.HexCell
import com.mergetacticsai.vision.BarScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO

/**
 * Проверка поиска полосок здоровья на РЕАЛЬНЫХ кадрах: вырезки полосы поля (эталон y 1200..1740) из записи
 * mt_record_20261006 (кадры в половину размера, 540x1200) и из снимка 1080x2400. Эталон — ручной подсчёт по кадрам:
 * на записи 6/6 бойцов: Монах, Мини-ПЕККА и ещё двое крупных в первом ряду (E2..E5 по подписям карты) плюс два в заднем ряду.
 */
class BarScannerFramesTest {
    private fun cells(resource: String, fullH: Int, bandTop: Int): Set<HexCell> {
        val img = ImageIO.read(javaClass.getResourceAsStream("/frames/$resource"))
        val w = img.width; val h = img.height
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = img.getRGB(x, y)
        return BarScanner.scanBand(px, w, h, bandTop, fullH).map { it.cell }.toSet()
    }

    private val six = setOf(HexCell(4, 1), HexCell(4, 2), HexCell(4, 3), HexCell(4, 4), HexCell(7, 1), HexCell(7, 2))

    @Test fun recordedDeployFrameFindsAllSixUnits() {
        // старое окно над клеткой находило на этом кадре только 4 клетки: пропускало (4,1) и (4,4)
        assertEquals(six, cells("deploy_6_units_540.png", 1200, 600))
    }

    @Test fun nextRoundSameArrangementFindsSameSix() {
        assertEquals(six, cells("deploy_6_units_round6_540.png", 1200, 600))
    }

    @Test fun fullSizeScreenshotFindsThreeUnitsIncludingOffsetOne() {
        // снимок раунда 2: третий юнит стоит на ~45 px правее центра клетки, старое окно его пропускало
        assertEquals(setOf(HexCell(7, 1), HexCell(7, 2), HexCell(7, 3)), cells("deploy_3_units_1080.png", 2400, 1200))
    }

    @Test fun bandRowsMatchReferenceAtAnyScale() {
        assertEquals(600..870, BarScanner.bandRows(1200))
        assertEquals(1200..1740, BarScanner.bandRows(2400))
    }

    @Test fun emptyOrTooSmallInputGivesNothing() {
        assertTrue(BarScanner.scanBand(IntArray(0), 540, 10, 600, 1200).isEmpty())
        assertTrue(BarScanner.scanBand(IntArray(540 * 10), 540, 10, 600, 1200).isEmpty())
    }
}
