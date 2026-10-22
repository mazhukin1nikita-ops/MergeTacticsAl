package com.mergetacticsai.vision

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Чтение цифр на устройстве (ML Kit, без интернета). Читает только цифры: кириллицу ML Kit не поддерживает. */
class OcrReader {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Что распознавание увидело в последний раз при чтении эликсира (для лога, когда число не прочиталось). */
    @Volatile var lastRaw: String = ""
        private set

    /** Светлые пиксели (цифра) делаем чёрными на белом фоне: так ML Kit читает цифры на цветной капле заметно лучше. */
    private fun binarize(src: Bitmap, bright: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val on = ((c shr 16) and 255) > bright && ((c shr 8) and 255) > bright && (c and 255) > bright
            px[i] = if (on) -0x1000000 else -1
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private suspend fun text(bmp: Bitmap, l: Float, t: Float, r: Float, b: Float, bright: Int = -1): String =
        withContext(Dispatchers.Default) {
            val x = (l * bmp.width).toInt().coerceIn(0, bmp.width - 2)
            val y = (t * bmp.height).toInt().coerceIn(0, bmp.height - 2)
            val w = ((r - l) * bmp.width).toInt().coerceIn(2, bmp.width - x)
            val h = ((b - t) * bmp.height).toInt().coerceIn(2, bmp.height - y)
            val crop = Bitmap.createBitmap(bmp, x, y, w, h)
            val big = Bitmap.createScaledBitmap(crop, w * 4, h * 4, true)
            val src = if (bright >= 0) binarize(big, bright) else big
            try {
                Tasks.await(rec.process(InputImage.fromBitmap(src, 0))).text
            } catch (e: Exception) {
                ""
            } finally {
                crop.recycle(); big.recycle()
                if (src !== big) src.recycle()
            }
        }

    private fun fix(s: String) = s.map {
        when (it) { 'O', 'o', 'D', 'Q' -> '0'; 'I', 'l', '|' -> '1'; 'S' -> '5'; 'B' -> '8'; '\\' -> '/'; else -> it }
    }.joinToString("")

    /** Эликсир 0..10 из золотой капли. */
    suspend fun readElixir(bmp: Bitmap): Int? {
        val raws = ArrayList<String>()
        // Сначала как есть, затем с выделением светлой цифры при двух порогах яркости.
        for (bright in intArrayOf(-1, 200, 160)) {
            val raw = text(bmp, 0.819f, 0.923f, 0.917f, 0.969f, bright)
            raws.add(raw.replace('\n', ' '))
            val v = fix(raw).filter { it.isDigit() }.take(2).toIntOrNull()?.takeIf { it in 0..10 }
            if (v != null) { lastRaw = raws.joinToString(" | "); return v }
        }
        lastRaw = raws.joinToString(" | ")
        return null
    }

    /** Счётчик юнитов на поле «N/M». */
    suspend fun readCounter(bmp: Bitmap): Pair<Int, Int>? {
        val m = Regex("(\\d+)\\s*/\\s*(\\d+)").find(fix(text(bmp, 0.481f, 0.394f, 0.63f, 0.427f))) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        return if (b in 1..20 && a <= b) a to b else null
    }

    /** Цена карточки магазина (цифра в бейдже), индекс 0..2. */
    suspend fun readCost(bmp: Bitmap, index: Int): Int? {
        val cx = floatArrayOf(0.197f, 0.399f, 0.602f)[index]
        val d = fix(text(bmp, cx - 0.033f, 0.870f, cx + 0.033f, 0.900f)).firstOrNull { it.isDigit() } ?: return null
        return d.digitToInt().takeIf { it in 1..9 }
    }
}
