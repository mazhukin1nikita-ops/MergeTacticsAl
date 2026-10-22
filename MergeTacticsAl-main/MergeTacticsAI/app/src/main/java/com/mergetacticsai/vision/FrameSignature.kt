package com.mergetacticsai.vision

import android.graphics.Bitmap
import android.graphics.Color

/** Дешёвый «отпечаток» области кадра: яркость в сетке точек. Нужен, чтобы заметить изменение без ИИ. */
object FrameSignature {
    fun of(bmp: Bitmap, r: ScreenRegions.R, gx: Int = 16, gy: Int = 8): IntArray {
        val x0 = (r.l * bmp.width).toInt(); val x1 = (r.r * bmp.width).toInt()
        val y0 = (r.t * bmp.height).toInt(); val y1 = (r.b * bmp.height).toInt()
        val out = IntArray(gx * gy)
        for (j in 0 until gy) for (i in 0 until gx) {
            val px = (x0 + (x1 - x0) * (2 * i + 1) / (2 * gx)).coerceIn(0, bmp.width - 1)
            val py = (y0 + (y1 - y0) * (2 * j + 1) / (2 * gy)).coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(px, py)
            out[j * gx + i] = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
        }
        return out
    }

    fun diff(a: IntArray, b: IntArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        var sum = 0L
        for (i in a.indices) sum += kotlin.math.abs(a[i] - b[i])
        return sum.toFloat() / a.size
    }
}
