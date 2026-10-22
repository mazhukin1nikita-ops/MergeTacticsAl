package com.mergetacticsai.vision

import android.graphics.Bitmap

/** Адаптер Bitmap → BarScanner: копируем только нужную полосу кадра. */
object BitmapBars {
    fun field(bmp: Bitmap, side: BarSide = BarSide.OWN): List<FieldBar> {
        val rows = BarScanner.bandRows(bmp.height)
        val bh = rows.last - rows.first + 1
        if (bh <= 0) return emptyList()
        val buf = IntArray(bmp.width * bh)
        bmp.getPixels(buf, 0, bmp.width, 0, rows.first, bmp.width, bh)
        return BarScanner.scanBand(buf, bmp.width, bh, rows.first, bmp.height)
    }
}
