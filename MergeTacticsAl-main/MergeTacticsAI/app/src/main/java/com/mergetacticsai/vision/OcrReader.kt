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

    private suspend fun text(bmp: Bitmap, l: Float, t: Float, r: Float, b: Float): String =
        withContext(Dispatchers.Default) {
            val x = (l * bmp.width).toInt().coerceIn(0, bmp.width - 2)
            val y = (t * bmp.height).toInt().coerceIn(0, bmp.height - 2)
            val w = ((r - l) * bmp.width).toInt().coerceIn(2, bmp.width - x)
            val h = ((b - t) * bmp.height).toInt().coerceIn(2, bmp.height - y)
            val crop = Bitmap.createBitmap(bmp, x, y, w, h)
            val big = Bitmap.createScaledBitmap(crop, w * 4, h * 4, true)
            try {
                Tasks.await(rec.process(InputImage.fromBitmap(big, 0))).text
            } catch (e: Exception) {
                ""
            } finally {
                crop.recycle(); big.recycle()
            }
        }

    private fun fix(s: String) = s.map {
        when (it) { 'O', 'o', 'D', 'Q' -> '0'; 'I', 'l', '|' -> '1'; 'S' -> '5'; 'B' -> '8'; '\\' -> '/'; else -> it }
    }.joinToString("")

    /** Эликсир 0..10 из золотой капли. */
    suspend fun readElixir(bmp: Bitmap): Int? {
        val d = fix(text(bmp, 0.819f, 0.923f, 0.917f, 0.969f)).filter { it.isDigit() }.take(2)
        return d.toIntOrNull()?.takeIf { it in 0..10 }
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
