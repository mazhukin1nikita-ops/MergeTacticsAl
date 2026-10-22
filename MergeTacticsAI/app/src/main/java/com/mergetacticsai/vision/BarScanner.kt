package com.mergetacticsai.vision

import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.HexMap
import kotlin.math.abs
import kotlin.math.roundToInt

enum class BarSide { OWN }

/** Полоска здоровья вашего юнита, привязанная к клетке. offX — смещение центра полоски от центра клетки по горизонтали (px эталона 1080x2400). */
data class FieldBar(val side: BarSide, val cell: HexCell, val cx: Float, val cy: Float, val w: Float, val h: Float, val fill: Float, val offX: Float)

/**
 * Находит полоски здоровья ваших юнитов на поле по связным областям цвета и привязывает каждую к клетке.
 *
 * Почему не окно фиксированной высоты над клеткой (PixelOps.cellOccupied): центр полоски лежит на 90-150 px выше центра клетки
 * в зависимости от роста юнита. На записи mt_record_20261006 (6 бойцов на поле, 6/6) окно находило 4 клетки: два юнита первого
 * ряда пропускались. Здесь ряд берётся по формуле с запасом ±46 px (BAR_DY — середина диапазона), колонка — по ближайшему центру.
 *
 * Проверено на 300 кадрах той записи (в стабильных кадрах расстановки находит все 6 юнитов, окно — 4) и на одном снимке 1080x2400
 * (3 юнита из 3). Полоски противника и очень крупные юниты вне этих кадров не сверялись.
 * Чистый Kotlin (IntArray ARGB): проверяется JVM-тестами.
 */
object BarScanner {
    const val BAR_DY = 127f
    private const val ROW_Y0 = 1045f
    private const val ROW_DY = 93.3f
    private const val MAX_DX = 74f

    /** Цвет полоски вашего юнита (те же пороги, что в PixelOps.cellOccupied). */
    fun isOwn(c: Int): Boolean {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return b > 150 && r < 120 && g in 91..209 && b - r > 80
    }

    /** Строки кадра высотой [h], где могут быть полоски ваших юнитов (эталон y 1200..1740; ниже — полоски скамейки). */
    fun bandRows(h: Int): IntRange {
        val sy = 2400f / h
        return (1200f / sy).toInt()..(1740f / sy).toInt().coerceAtMost(h - 1)
    }

    /** [band] — пиксели строк [bandTop].. кадра шириной [w] и высотой [fullH]. */
    fun scanBand(band: IntArray, w: Int, bandH: Int, bandTop: Int, fullH: Int): List<FieldBar> {
        if (w <= 0 || bandH <= 0 || fullH <= 0 || band.size < w * bandH) return emptyList()
        val sx = HexMap.REF_W / w
        val sy = HexMap.REF_H / fullH
        val mask = BooleanArray(w * bandH) { isOwn(band[it]) }
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val best = HashMap<HexCell, FieldBar>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start; seen[start] = true
            var minX = w; var maxX = 0; var minY = bandH; var maxY = 0; var n = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % w; val cy = cur / w
                n++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                if (cx > 0 && mask[cur - 1] && !seen[cur - 1]) { seen[cur - 1] = true; stack[sp++] = cur - 1 }
                if (cx < w - 1 && mask[cur + 1] && !seen[cur + 1]) { seen[cur + 1] = true; stack[sp++] = cur + 1 }
                if (cy > 0 && mask[cur - w] && !seen[cur - w]) { seen[cur - w] = true; stack[sp++] = cur - w }
                if (cy < bandH - 1 && mask[cur + w] && !seen[cur + w]) { seen[cur + w] = true; stack[sp++] = cur + w }
            }
            val bw = (maxX - minX + 1) * sx
            val bh = (maxY - minY + 1) * sy
            if (bw < 55f || bw > 140f || bh < 8f || bh > 36f || bw < 2f * bh) continue
            val fill = n * sx * sy / (bw * bh)
            if (fill < 0.3f) continue
            val cxRef = (minX + maxX + 1) / 2f * sx
            val cyRef = ((minY + maxY + 1) / 2f + bandTop) * sy
            val row = ((cyRef + BAR_DY - ROW_Y0) / ROW_DY).roundToInt()
            if (row !in HexMap.OWN_FIRST_ROW until HexMap.ROWS) continue
            val x0 = HexMap.centerPx(HexCell(row, 0)).first
            val col = ((cxRef - x0) / 135f).roundToInt()
            if (col !in 0 until HexMap.COLS) continue
            val off = cxRef - (x0 + 135f * col)
            if (abs(off) > MAX_DX) continue
            val cell = HexCell(row, col)
            val bar = FieldBar(BarSide.OWN, cell, cxRef, cyRef, bw, bh, fill, off)
            val prev = best[cell]
            if (prev == null || abs(bar.offX) < abs(prev.offX)) best[cell] = bar
        }
        return best.values.sortedWith(compareBy({ it.cell.row }, { it.cell.col }))
    }

    /** Весь кадр целиком (для тестов): вырезает полосу и сканирует. */
    fun scan(px: IntArray, w: Int, h: Int): List<FieldBar> {
        val rows = bandRows(h)
        val bh = rows.last - rows.first + 1
        if (w <= 0 || bh <= 0 || px.size < w * h) return emptyList()
        return scanBand(px.copyOfRange(rows.first * w, (rows.last + 1) * w), w, bh, rows.first, h)
    }
}
