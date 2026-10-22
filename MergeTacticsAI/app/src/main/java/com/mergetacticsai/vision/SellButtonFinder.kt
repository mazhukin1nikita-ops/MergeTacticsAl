package com.mergetacticsai.vision

/** Прямоугольник в долях кадра. */
class Blob(val l: Float, val t: Float, val r: Float, val b: Float) {
    val cx: Float get() = (l + r) / 2f
    val cy: Float get() = (t + b) / 2f
}

/**
 * Ищет зелёную кнопку («Продать») в окне выбранного воина по цвету и форме: сплошной зелёный скруглённый
 * прямоугольник заметной ширины и невысокий. Название на кнопке не читается (OCR для кириллицы нет),
 * поэтому кнопку засчитываем только если она ПОЯВИЛАСЬ после нажатия на воина ([appeared]).
 * Пороги цвета подобраны под обычную зелёную кнопку Clash Royale; при другом оттенке правятся здесь.
 */
object SellButtonFinder {
    fun isGreen(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return g >= 170 && r in 30..150 && b <= 130 && g - r >= 55 && g - b >= 60
    }

    fun find(px: IntArray, w: Int, h: Int): List<Blob> {
        if (w < 40 || h < 40 || px.size < w * h) return emptyList()
        val step = maxOf(2, w / 180)
        val cw = w / step; val ch = h / step
        val mask = BooleanArray(cw * ch)
        for (cy in 0 until ch) for (cx in 0 until cw) mask[cy * cw + cx] = isGreen(px[(cy * step) * w + cx * step])
        val seen = BooleanArray(cw * ch)
        val out = ArrayList<Blob>()
        val stack = IntArray(cw * ch)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var minX = cw; var maxX = -1; var minY = ch; var maxY = -1; var n = 0
            while (sp > 0) {
                val cur = stack[--sp]; val x = cur % cw; val y = cur / cw
                n++
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && mask[cur - 1] && !seen[cur - 1]) { seen[cur - 1] = true; stack[sp++] = cur - 1 }
                if (x < cw - 1 && mask[cur + 1] && !seen[cur + 1]) { seen[cur + 1] = true; stack[sp++] = cur + 1 }
                if (y > 0 && mask[cur - cw] && !seen[cur - cw]) { seen[cur - cw] = true; stack[sp++] = cur - cw }
                if (y < ch - 1 && mask[cur + cw] && !seen[cur + cw]) { seen[cur + cw] = true; stack[sp++] = cur + cw }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val wf = bw.toFloat() / cw; val hf = bh.toFloat() / ch
            val aspect = (bw * step).toFloat() / (bh * step)
            val fill = n.toFloat() / (bw * bh)
            if (wf in 0.10f..0.50f && hf in 0.02f..0.10f && aspect in 1.8f..8f && fill >= 0.6f) {
                out.add(Blob(minX.toFloat() / cw, minY.toFloat() / ch, (maxX + 1).toFloat() / cw, (maxY + 1).toFloat() / ch))
            }
        }
        return out.sortedByDescending { (it.r - it.l) * (it.b - it.t) }
    }

    private fun overlap(a: Blob, b: Blob): Float {
        val l = maxOf(a.l, b.l); val t = maxOf(a.t, b.t); val r = minOf(a.r, b.r); val bt = minOf(a.b, b.b)
        if (r <= l || bt <= t) return 0f
        val inter = (r - l) * (bt - t)
        val union = (a.r - a.l) * (a.b - a.t) + (b.r - b.l) * (b.b - b.t) - inter
        return inter / union
    }

    /** Кнопка, которой не было на кадре «до». Самая крупная из новых или null. */
    fun appeared(before: List<Blob>, after: List<Blob>): Blob? =
        after.firstOrNull { a -> before.none { overlap(a, it) > 0.3f } }
}
