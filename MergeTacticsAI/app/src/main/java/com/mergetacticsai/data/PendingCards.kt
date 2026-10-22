package com.mergetacticsai.data

import android.content.Context
import android.graphics.Bitmap
import com.mergetacticsai.strategy.CardMemory
import java.io.File

/** Картинки карточек, которые бот видел, но ещё не знает. Хранятся во внутренней памяти приложения (макс. 12). */
class PendingCards(context: Context) {
    private val dir = File(context.filesDir, "pending").also { it.mkdirs() }

    fun list(): List<Pair<Long, File>> =
        (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
            runCatching { java.lang.Long.parseUnsignedLong(f.nameWithoutExtension, 16) to f }.getOrNull()
        }.sortedBy { it.second.lastModified() }

    fun save(sig: Long, bmp: Bitmap): Boolean {
        val cur = list()
        if (cur.size >= 12 || cur.any { java.lang.Long.bitCount(it.first xor sig) <= CardMemory.MAX_DIST }) return false
        return runCatching {
            File(dir, java.lang.Long.toHexString(sig) + ".png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
    }

    fun remove(sig: Long) { File(dir, java.lang.Long.toHexString(sig) + ".png").delete() }
    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}
