package com.mergetacticsai.vision

import android.graphics.Bitmap
import org.json.JSONObject

/**
 * Узнаёт войско по картинке карточки магазина. Эталоны сняты с фото каталога (assets/cards, card_refs.json):
 * у каждой карточки — цветовой отпечаток центральной части арта 10x10, нормированный по каналам
 * (не зависит от яркости, поэтому затемнённые карточки тоже узнаются).
 */
class CardMatcher(private val refs: Map<String, FloatArray>) {
    /** Возвращает id войска или null, если уверенности не хватает. */
    fun match(bmp: Bitmap, index: Int): String? {
        val w = bmp.width; val h = bmp.height
        val cx = ScreenRegions.cardX[index]
        val d = describe(w, h, { x, y -> bmp.getPixel(x, y) }, cx - 0.0852f, 0.8792f, cx + 0.0852f, 0.9729f)
        return best(d)
    }

    /**
     * У войска может быть несколько эталонов: "id" — чистое фото, "id#1", "id#2"… — то же фото с помехами экрана
     * (см. tools/make_card_refs.py). Балл войска — лучший из его эталонов; уверенность считается между разными войсками,
     * иначе близнецы одного войска обнуляли бы отрыв.
     */
    fun best(d: FloatArray): String? {
        val scores = HashMap<String, Float>()
        for ((key, r) in refs) {
            var s = 0f
            for (i in d.indices) s += d[i] * r[i]
            s /= d.size
            val id = key.substringBefore('#')
            val prev = scores[id]
            if (prev == null || s > prev) scores[id] = s
        }
        var b: String? = null; var bs = -2f; var second = -2f
        for ((id, s) in scores) {
            if (s > bs) { second = bs; bs = s; b = id } else if (s > second) second = s
        }
        return if (b != null && bs >= MIN_SCORE && bs - second >= MIN_MARGIN) b else null
    }

    companion object {
        const val N = 10
        const val MIN_SCORE = 0.35f
        const val MIN_MARGIN = 0.08f

        fun parse(json: String): CardMatcher {
            val o = JSONObject(json)
            val m = HashMap<String, FloatArray>()
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                m[k] = FloatArray(a.length()) { a.getDouble(it).toFloat() }
            }
            return CardMatcher(m)
        }

        /**
         * Берёт центральную часть карточки (x 22-78%, y 12-62% её рамки), усредняет по клеткам N×N
         * и нормирует каждый канал. Рамка карточки задана долями изображения.
         */
        fun describe(w: Int, h: Int, px: (Int, Int) -> Int, fx0: Float, fy0: Float, fx1: Float, fy1: Float): FloatArray {
            val bx0 = fx0 * w; val by0 = fy0 * h; val bw = (fx1 - fx0) * w; val bh = (fy1 - fy0) * h
            val rx0 = bx0 + 0.22f * bw; val rx1 = bx0 + 0.78f * bw
            val ry0 = by0 + 0.12f * bh; val ry1 = by0 + 0.62f * bh
            val out = FloatArray(N * N * 3)
            for (j in 0 until N) for (i in 0 until N) {
                val xa = Math.round(rx0 + (rx1 - rx0) * i / N)
                val xb = maxOf(xa + 1, Math.round(rx0 + (rx1 - rx0) * (i + 1) / N))
                val ya = Math.round(ry0 + (ry1 - ry0) * j / N)
                val yb = maxOf(ya + 1, Math.round(ry0 + (ry1 - ry0) * (j + 1) / N))
                var r = 0f; var g = 0f; var b = 0f; var n = 0
                for (y in ya until yb) for (x in xa until xb) {
                    val c = px(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                val o = (j * N + i) * 3
                out[o] = r / n; out[o + 1] = g / n; out[o + 2] = b / n
            }
            for (ch in 0 until 3) {
                var mean = 0f
                for (k in 0 until N * N) mean += out[k * 3 + ch]
                mean /= N * N
                var v = 0f
                for (k in 0 until N * N) { val d = out[k * 3 + ch] - mean; v += d * d }
                val std = Math.sqrt((v / (N * N)).toDouble()).toFloat() + 1e-6f
                for (k in 0 until N * N) out[k * 3 + ch] = (out[k * 3 + ch] - mean) / std
            }
            return out
        }
    }
}
