package com.mergetacticsai.automation

import android.graphics.Bitmap
import com.mergetacticsai.accessibility.GestureController
import com.mergetacticsai.strategy.HexGrid
import com.mergetacticsai.vision.OcrReader
import com.mergetacticsai.vision.PixelOps
import com.mergetacticsai.vision.SellButtonFinder
import kotlinx.coroutines.delay

/** Реальный экран и жесты для [UnitRemover]. */
class AndroidRemoverIO(
    private val frame: () -> Bitmap?,
    private val screen: () -> Pair<Int, Int>,
    private val ocr: OcrReader,
) : RemoverIO {
    override suspend fun observe(cellIndex: Int): Obs? {
        // Если экран не менялся, новый кадр может не прийти — пробуем несколько раз.
        var bmp: Bitmap? = null
        for (i in 0 until 6) {
            bmp = frame()
            if (bmp != null) break
            delay(40)
        }
        if (bmp == null) return null
        try {
            val cell = HexGrid.cells.getOrNull(cellIndex) ?: return null
            val deploy = PixelOps.deployIconFraction(bmp) >= 0.2f
            val occupied = PixelOps.cellOccupied(bmp, cell)
            val benchFree = (0 until 5).map { PixelOps.woodFraction(bmp, it) >= 0.9f }
            val elixir = ocr.readElixir(bmp)
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return Obs(deploy, occupied, benchFree, elixir, SellButtonFinder.find(px, bmp.width, bmp.height))
        } finally {
            bmp.recycle()
        }
    }

    override suspend fun tap(x: Float, y: Float): Boolean = GestureController.tap(x, y)

    override suspend fun holdDrag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, dragMs: Long, dwellMs: Long): Boolean =
        GestureController.holdDrag(x1, y1, x2, y2, holdMs, dragMs, dwellMs)

    override suspend fun pause(ms: Long) { delay(ms) }
    override fun size(): Pair<Int, Int> = screen()
    override fun cellCenter(cellIndex: Int): Pair<Float, Float> {
        val c = HexGrid.cells[cellIndex]
        return Pair(c.x, c.y)
    }
}
