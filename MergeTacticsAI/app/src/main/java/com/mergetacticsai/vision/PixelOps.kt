package com.mergetacticsai.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.mergetacticsai.ai.Point

/** Локальное распознавание по цвету. Пороги подобраны по скриншотам 1080x2400 и работают в долях экрана. */
object PixelOps {
    private class Px(val data: IntArray, val w: Int, val h: Int)

    private fun grab(bmp: Bitmap, l: Float, t: Float, r: Float, b: Float): Px {
        val x = (l * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val y = (t * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val w = ((r - l) * bmp.width).toInt().coerceIn(1, bmp.width - x)
        val h = ((b - t) * bmp.height).toInt().coerceIn(1, bmp.height - y)
        val d = IntArray(w * h)
        bmp.getPixels(d, 0, w, x, y, w, h)
        return Px(d, w, h)
    }

    /** Доля «деревянных» пикселей в слоте скамейки: ~1.0 — пусто, меньше 0.9 — там юнит. */
    fun woodFraction(bmp: Bitmap, slot: Int): Float {
        val cx = ScreenRegions.benchX[slot]
        val px = grab(bmp, cx - 0.0519f, 0.7854f, cx + 0.0519f, 0.8188f)
        val hsv = FloatArray(3)
        var n = 0
        for (c in px.data) {
            Color.colorToHSV(c, hsv)
            if (hsv[0] in 10f..40f && hsv[1] in 0.2f..0.7f && hsv[2] > 0.5f) n++
        }
        return n.toFloat() / px.data.size
    }

    /** Значок человечка у счётчика «N/M»: виден только в фазе расстановки (доля ≈0.4, иначе ≈0). */
    fun deployIconFraction(bmp: Bitmap): Float {
        val px = grab(bmp, 0.398f, 0.3875f, 0.4676f, 0.4271f)
        var n = 0
        for (c in px.data) {
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            if (mn > 120 && mx - mn < 45) n++
        }
        return n.toFloat() / px.data.size
    }

    /**
     * Ищет сферу любого цвета: на поле выделяются пятна, не похожие на траву, и берётся то из них,
     * что похоже на круг размером со сферу (юниты, цифры и контуры гексов под этот критерий не подходят).
     */
    fun findOrb(bmp: Bitmap): Point? {
        val bw = bmp.width; val bh = bmp.height
        val x0 = (0.1296f * bw).toInt(); val x1 = (0.8704f * bw).toInt()
        val y0 = (0.3875f * bh).toInt(); val y1 = (0.7625f * bh).toInt()
        val rw = x1 - x0; val rh = y1 - y0
        val s = (bw / 270).coerceAtLeast(1)
        val gw = rw / s; val gh = rh / s
        if (gw < 10 || gh < 10) return null
        val arr = IntArray(rw * rh)
        bmp.getPixels(arr, 0, rw, x0, y0, rw, rh)
        val n = gw * gh
        val gr = IntArray(n); val gg = IntArray(n); val gb = IntArray(n)
        for (y in 0 until gh) for (x in 0 until gw) {
            val c = arr[(y * s) * rw + x * s]
            val i = y * gw + x
            gr[i] = Color.red(c); gg[i] = Color.green(c); gb[i] = Color.blue(c)
        }
        val mr = gr.sortedArray()[n / 2]; val mg = gg.sortedArray()[n / 2]; val mb = gb.sortedArray()[n / 2]
        val fg = BooleanArray(n)
        for (i in 0 until n) {
            val dr = gr[i] - mr; val dg = gg[i] - mg; val db = gb[i] - mb
            fg[i] = dr * dr + dg * dg + db * db > 4900
        }
        val k = 1080f / bw
        val seen = BooleanArray(n)
        val queue = IntArray(n)
        var best = 0f; var bx = 0f; var by = 0f
        for (start in 0 until n) {
            if (!fg[start] || seen[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            var minX = gw; var maxX = 0; var minY = gh; var maxY = 0
            while (head < tail) {
                val cur = queue[head++]
                val cx = cur % gw; val cy = cur / gw
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                if (cx > 0 && fg[cur - 1] && !seen[cur - 1]) { seen[cur - 1] = true; queue[tail++] = cur - 1 }
                if (cx < gw - 1 && fg[cur + 1] && !seen[cur + 1]) { seen[cur + 1] = true; queue[tail++] = cur + 1 }
                if (cy > 0 && fg[cur - gw] && !seen[cur - gw]) { seen[cur - gw] = true; queue[tail++] = cur - gw }
                if (cy < gh - 1 && fg[cur + gw] && !seen[cur + gw]) { seen[cur + gw] = true; queue[tail++] = cur + gw }
            }
            val w = (maxX - minX + 1) * s * k; val h = (maxY - minY + 1) * s * k
            if (w < 80f || w > 170f || h < 80f || h > 170f) continue
            val aspect = w / h
            if (aspect < 0.8f || aspect > 1.25f) continue
            val area = tail * (s * k) * (s * k)
            val circ = area / (Math.PI.toFloat() * w * h / 4f)
            if (circ >= 0.8f && circ > best) {
                best = circ
                bx = (x0 + (minX + maxX) / 2 * s).toFloat() / bw
                by = (y0 + (minY + maxY) / 2 * s).toFloat() / bh
            }
        }
        return if (best > 0f) Point(bx, by) else null
    }

    /** Клетка занята, если над ней видна синяя полоска здоровья своего юнита (надёжнее, чем сравнение с травой). */
    fun cellOccupied(bmp: Bitmap, cell: com.mergetacticsai.strategy.Cell): Boolean {
        val px = grab(bmp, cell.x - 0.0556f, cell.y - 0.0563f, cell.x + 0.0556f, cell.y - 0.0417f)
        var n = 0
        for (c in px.data) {
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            if (b > 150 && r < 120 && g in 91..209 && b - r > 80) n++
        }
        return n.toFloat() / px.data.size >= 0.1f
    }

    /** Две серебристые звезды под юнитом на скамейке = двухзвёздочный (одна бронзовая звезда = обычный). */
    fun benchTwoStar(bmp: Bitmap, slot: Int): Boolean {
        val cx = ScreenRegions.benchX[slot]
        val px = grab(bmp, cx - 0.0352f, 0.8158f, cx + 0.0352f, 0.8325f)
        val hsv = FloatArray(3)
        var n = 0
        for (c in px.data) {
            Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.3f && hsv[2] > 0.62f && hsv[0] in 140f..230f) n++
        }
        return n.toFloat() / px.data.size >= 0.04f
    }

    /** Зелёная стрелка над карточкой: покупка этого юнита даст объединение с уже имеющимся. */
    fun cardMergeArrow(bmp: Bitmap, index: Int): Boolean {
        val cx = ScreenRegions.cardX[index]
        val px = grab(bmp, cx + 0.0324f, 0.8688f, cx + 0.1157f, 0.9042f)
        var n = 0
        for (c in px.data) {
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            if (g > 170 && r < 150 && b < 120 && g - r > 50) n++
        }
        return n.toFloat() / px.data.size >= 0.06f
    }

    /** Хватает ли эликсира на карточку: недоступные карточки в игре затемняются (яркость нижней полосы ≈0.33 против ≈0.7). */
    fun cardAffordable(bmp: Bitmap, index: Int): Boolean {
        val cx = ScreenRegions.cardX[index]
        val px = grab(bmp, cx - 0.0648f, 0.9542f, cx + 0.0648f, 0.9688f)
        var sum = 0f
        for (c in px.data) sum += maxOf(Color.red(c), Color.green(c), Color.blue(c)) / 255f
        return sum / px.data.size >= 0.5f
    }

    /** Картинка карточки магазина (для вкладки «Обучение»). */
    fun cardCrop(bmp: Bitmap, index: Int): Bitmap {
        val cx = ScreenRegions.cardX[index]
        val x = ((cx - 0.0648f) * bmp.width).toInt().coerceIn(0, bmp.width - 2)
        val y = (0.8917f * bmp.height).toInt().coerceIn(0, bmp.height - 2)
        val w = (0.1296f * bmp.width).toInt().coerceIn(2, bmp.width - x)
        val h = (0.0625f * bmp.height).toInt().coerceIn(2, bmp.height - y)
        return Bitmap.createBitmap(bmp, x, y, w, h)
    }

    /** 64-битный dHash портрета карточки магазина. Одинаковые карточки дают расстояние 0-2, разные 25+. */
    fun cardSignature(bmp: Bitmap, index: Int): Long {
        val cx = ScreenRegions.cardX[index]
        val x = ((cx - 0.0648f) * bmp.width).toInt().coerceIn(0, bmp.width - 2)
        val y = (0.8917f * bmp.height).toInt().coerceIn(0, bmp.height - 2)
        val w = (0.1296f * bmp.width).toInt().coerceIn(2, bmp.width - x)
        val h = (0.0625f * bmp.height).toInt().coerceIn(2, bmp.height - y)
        val crop = Bitmap.createBitmap(bmp, x, y, w, h)
        val small = Bitmap.createScaledBitmap(crop, 9, 8, true)
        val lum = IntArray(72)
        small.getPixels(lum, 0, 9, 0, 0, 9, 8)
        for (i in lum.indices) {
            val c = lum[i]
            lum[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        var sig = 0L
        var bit = 0
        for (row in 0 until 8) for (col in 0 until 8) {
            if (lum[row * 9 + col + 1] > lum[row * 9 + col]) sig = sig or (1L shl bit)
            bit++
        }
        if (crop !== bmp) crop.recycle()
        small.recycle()
        return sig
    }
}
