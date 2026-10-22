package com.mergetacticsai.map

import android.graphics.Bitmap
import android.graphics.Color
import com.mergetacticsai.strategy.Cell
import com.mergetacticsai.vision.PixelOps

/**
 * Находит на скриншоте клетки, где стоят юниты. Определяется только факт «клетка занята»:
 * название войска и звёзды по картинке не распознаются, их вы задаёте вручную.
 * Ваша сторона: синяя полоска здоровья (тот же метод, что использует бот в игре, проверен).
 * Сторона противника: красная полоска здоровья, пороги подобраны по аналогии и на реальных снимках не сверялись.
 */
object BoardScan {
    data class Result(
        val own: Set<HexCell>, val enemy: Set<HexCell>,
        /** false, если пропорции снимка заметно отличаются от 1080x2400 и клетки могут сместиться. */
        val aspectOk: Boolean,
    )

    fun scan(bmp: Bitmap): Result {
        val own = HashSet<HexCell>()
        val enemy = HashSet<HexCell>()
        for (c in HexMap.all) {
            val (x, y) = HexMap.center(c)
            if (HexMap.isOwn(c)) {
                if (PixelOps.cellOccupied(bmp, Cell(c.row, c.col, x, y))) own += c
            } else if (enemyOccupied(bmp, x, y)) enemy += c
        }
        return Result(own, enemy, HexMap.ratioOk(bmp.width, bmp.height))
    }

    /** Красная полоска здоровья над клеткой противника. */
    private fun enemyOccupied(bmp: Bitmap, cx: Float, cy: Float): Boolean {
        val bw = bmp.width; val bh = bmp.height
        val x = ((cx - 0.0556f) * bw).toInt().coerceIn(0, bw - 1)
        val y = ((cy - 0.0563f) * bh).toInt().coerceIn(0, bh - 1)
        val w = ((0.1112f) * bw).toInt().coerceIn(1, bw - x)
        val h = ((0.0146f) * bh).toInt().coerceIn(1, bh - y)
        val d = IntArray(w * h)
        bmp.getPixels(d, 0, w, x, y, w, h)
        var n = 0
        for (c in d) {
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            if (r > 170 && g < 110 && b < 110 && r - g > 80) n++
        }
        return n.toFloat() / d.size >= 0.1f
    }
}
