package com.mergetacticsai.vision

import android.graphics.Bitmap

/**
 * Определяет свойство войска по круглому значку внизу карточки магазина: клан, нежить, гоблин, благородный, лес, босс.
 * Цвет значка отличает пять свойств; нежить (череп) и лес (дерево) похожи по цвету и различаются по форме.
 * Пороги подобраны по скриншотам игры и сайта (все примеры распознались верно).
 */
object TraitIcons {
    const val G = 16
    val UNDEAD_SHAPE = floatArrayOf(0.43f, 0.14f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.14f, 0.14f, 0.29f, 0.43f, 0.29f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.29f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.14f, 0.14f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.14f, 0.57f, 0.86f, 0.86f, 0.86f, 0.86f, 0.86f, 0.14f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.14f, 0.71f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.86f, 0.29f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.57f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.86f, 0.14f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.86f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.29f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.86f, 0.86f, 0.86f, 1.0f, 1.0f, 1.0f, 1.0f, 0.86f, 0.86f, 1.0f, 0.57f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.86f, 0.29f, 0.14f, 0.14f, 0.86f, 1.0f, 0.29f, 0.14f, 0.14f, 0.86f, 0.29f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.86f, 0.57f, 0.0f, 0.14f, 0.86f, 1.0f, 0.29f, 0.0f, 0.29f, 0.86f, 0.14f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.57f, 0.86f, 0.86f, 0.86f, 0.86f, 0.86f, 0.86f, 0.86f, 0.86f, 0.71f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.14f, 0.14f, 0.71f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.29f, 0.14f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.14f, 0.71f, 1.0f, 1.0f, 0.86f, 0.29f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.14f, 0.14f, 0.14f, 0.14f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.14f, 0.14f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.29f, 0.43f, 0.14f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.29f, 0.71f)
    val FOREST_SHAPE = floatArrayOf(0.33f, 0.17f, 0.17f, 0.0f, 0.0f, 0.0f, 0.17f, 0.17f, 0.17f, 0.17f, 0.0f, 0.0f, 0.17f, 0.33f, 0.33f, 0.5f, 0.17f, 0.17f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.33f, 0.5f, 0.5f, 0.0f, 0.0f, 0.0f, 0.17f, 0.5f, 0.5f, 0.17f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.33f, 0.0f, 1.0f, 0.5f, 0.33f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.17f, 0.33f, 0.0f, 0.17f, 1.0f, 1.0f, 0.5f, 0.17f, 0.0f, 0.0f, 0.0f, 0.17f, 0.0f, 0.0f, 0.0f, 0.17f, 0.5f, 0.0f, 0.0f, 0.5f, 1.0f, 1.0f, 0.67f, 0.5f, 0.17f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.17f, 0.5f, 0.17f, 0.0f, 0.0f, 0.5f, 1.0f, 1.0f, 1.0f, 0.5f, 0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.5f, 0.0f, 0.5f, 0.33f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5f, 0.33f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.5f, 0.17f, 0.67f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.67f, 0.5f, 0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.33f, 0.5f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5f, 0.33f, 0.33f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.33f, 0.0f, 0.5f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.17f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.33f, 0.0f, 0.83f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.67f, 0.0f, 0.0f, 0.0f, 0.33f, 0.33f, 0.5f, 0.33f, 0.0f, 0.0f, 0.5f, 0.83f, 1.0f, 0.83f, 0.83f, 0.5f, 0.5f, 0.17f, 0.17f, 0.33f, 0.33f, 0.67f, 0.5f, 0.0f, 0.0f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.17f, 0.0f, 0.83f, 0.83f, 0.0f, 0.0f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.67f, 0.83f, 0.0f, 0.17f, 0.5f, 0.83f, 0.67f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.83f, 0.5f, 0.33f, 0.5f, 0.5f, 0.33f, 0.67f, 0.83f, 0.83f, 0.83f, 0.83f, 0.83f, 0.83f, 0.83f, 0.83f, 0.67f, 0.33f, 0.67f, 0.5f)

    fun classify(bmp: Bitmap, index: Int): String? {
        val cx = (ScreenRegions.cardX[index] * bmp.width).toInt()
        val cy = (0.9583f * bmp.height).toInt()
        val half = (0.0204f * bmp.width).toInt().coerceAtLeast(6)
        val x = (cx - half).coerceIn(0, bmp.width - 2 * half - 1)
        val y = (cy - half).coerceIn(0, bmp.height - 2 * half - 1)
        val crop = Bitmap.createBitmap(bmp, x, y, 2 * half, 2 * half)
        val small = Bitmap.createScaledBitmap(crop, G, G, true)
        val px = IntArray(G * G)
        small.getPixels(px, 0, G, 0, 0, G, G)
        if (small !== crop) small.recycle()
        crop.recycle()
        return classifyPixels(px)
    }

    /** px — 16x16 пикселей ARGB. Чистая функция, проверяется юнит-тестами. */
    fun classifyPixels(px: IntArray): String? {
        val n = G * G
        if (px.size != n) return null
        val v = FloatArray(n); val s = FloatArray(n); val h = FloatArray(n)
        for (i in 0 until n) {
            val r = (px[i] shr 16) and 0xFF; val g = (px[i] shr 8) and 0xFF; val b = px[i] and 0xFF
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = (mx - mn).toFloat()
            v[i] = mx / 255f
            s[i] = if (mx == 0) 0f else d / mx
            h[i] = if (d == 0f) 0f else {
                val rf = r.toFloat(); val gf = g.toFloat(); val bf = b.toFloat()
                val x = when (mx) {
                    r -> (gf - bf) / d
                    g -> (bf - rf) / d + 2f
                    else -> (rf - gf) / d + 4f
                }
                60f * (((x % 6f) + 6f) % 6f)
            }
        }
        val sorted = v.sortedArray()
        val lo = sorted[(n * 0.10f).toInt()]
        val hi = sorted[((n - 1) * 0.95f).toInt()]
        if (hi - lo <= 0.06f) return null
        val thr = (lo + hi) / 2f
        var cnt = 0; var sc = 0.0; var ss = 0.0
        for (i in 0 until n) if (v[i] > thr && s[i] > 0.3f && v[i] > 0.2f) {
            cnt++
            val a = Math.toRadians(h[i].toDouble())
            sc += Math.cos(a); ss += Math.sin(a)
        }
        if (cnt < 6) return null
        val hue = ((Math.toDegrees(Math.atan2(ss, sc)) % 360.0) + 360.0) % 360.0
        return when {
            hue in 25.0..60.0 -> "noble"
            hue in 270.0..345.0 -> "boss"
            hue in 175.0..235.0 -> "clan"
            hue in 85.0..118.0 -> "goblin"
            hue > 118.0 && hue < 175.0 -> {
                var du = 0f; var df = 0f
                for (i in 0 until n) {
                    val m = if (v[i] > thr) 1f else 0f
                    du += kotlin.math.abs(m - UNDEAD_SHAPE[i]); df += kotlin.math.abs(m - FOREST_SHAPE[i])
                }
                if (du <= df) "undead" else "forest"
            }
            else -> null
        }
    }
}
