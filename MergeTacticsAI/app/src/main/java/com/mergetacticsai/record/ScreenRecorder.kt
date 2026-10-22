package com.mergetacticsai.record

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Внутренняя запись экрана: не видео, а последовательность кадров (JPEG) и хронология событий бота.
 * Кадры берутся из того же захвата, что и для распознавания, поэтому записать можно только то, что бот видит, пока работает.
 * Хранится не больше [MAX_FRAMES] последних кадров в приватной папке приложения; наружу уходит только когда вы сами
 * нажмёте «Отправить запись». Запись видит весь экран, включая уведомления поверх игры.
 */
object ScreenRecorder {
    const val MAX_FRAMES = 300
    private const val JPEG_QUALITY = 50
    private const val MAX_TIMELINE_BYTES = 1_500_000L
    private const val PREFS = "mt_record"

    @Volatile var enabled: Boolean = true
        private set

    private var dir: File? = null
    private var appContext: Context? = null
    private val plan = RecordPlan()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "mt-recorder").apply { isDaemon = true } }
    private val pending = AtomicInteger(0)
    private val frames = ArrayDeque<String>()
    private var nextIndex = 1
    @Volatile private var lastFrame = ""
    @Volatile private var eventCount = 0
    private var timeline: BufferedWriter? = null

    @Synchronized fun init(ctx: Context) {
        if (dir != null) return
        appContext = ctx.applicationContext
        val d = File(ctx.filesDir, "record").apply { mkdirs() }
        dir = d
        enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)
        val names = d.list()?.toList().orEmpty()
        val sorted = names.mapNotNull { n -> RecordNames.indexOf(n)?.let { it to n } }.sortedBy { it.first }
        sorted.forEach { frames.addLast(it.second) }
        nextIndex = (sorted.lastOrNull()?.first ?: 0) + 1
        lastFrame = sorted.lastOrNull()?.second.orEmpty()
        eventCount = File(d, "timeline.txt").takeIf { it.exists() }?.useLines { it.count() } ?: 0
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("enabled", on)?.apply()
        event(if (on) "Запись экрана включена" else "Запись экрана выключена")
    }

    private fun clock(now: Long, forFile: Boolean): String =
        SimpleDateFormat(if (forFile) "HH-mm-ss-SSS" else "yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now))

    /** Вызывается циклом бота на каждом кадре. Тяжёлая работа — в фоне; при задержке кадры пропускаются, цикл не тормозит. */
    fun onFrame(bmp: Bitmap, now: Long = System.currentTimeMillis()) {
        val d = dir ?: return
        if (!enabled || !plan.shouldSave(now)) return
        if (pending.get() >= 3) return
        val copy = runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        pending.incrementAndGet()
        exec.execute {
            try {
                val idx = synchronized(this) { nextIndex++ }
                val name = RecordNames.frame(idx, clock(now, true))
                FileOutputStream(File(d, name)).use { copy.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                lastFrame = name
                val drop = synchronized(this) {
                    frames.addLast(name)
                    RecordNames.toDelete(frames.toList(), MAX_FRAMES).also { del -> frames.removeAll(del.toSet()) }
                }
                drop.forEach { File(d, it).delete() }
            } catch (_: Throwable) {
            } finally {
                copy.recycle(); pending.decrementAndGet()
            }
        }
    }

    /** Строка хронологии: время, ближайший сохранённый кадр и текст. Включает частую запись кадров на пару секунд. */
    fun event(text: String, now: Long = System.currentTimeMillis()) {
        val d = dir ?: return
        if (!enabled) return
        plan.onEvent(now)
        val frame = lastFrame
        eventCount++
        exec.execute {
            try {
                val f = File(d, "timeline.txt")
                if (f.exists() && f.length() > MAX_TIMELINE_BYTES) {
                    val keep = f.readLines().takeLast(5000 / 2)
                    timeline?.close(); timeline = null
                    f.writeText(keep.joinToString("\n") + "\n")
                }
                val w = timeline ?: BufferedWriter(FileWriter(f, true)).also { timeline = it }
                w.write("[" + clock(now, false) + "] " + (if (frame.isEmpty()) "-" else frame) + " | " + text.replace('\n', ' '))
                w.newLine(); w.flush()
            } catch (_: Throwable) {
                timeline = null
            }
        }
    }

    /** Сколько кадров и событий сейчас записано. */
    fun stats(): Pair<Int, Int> = synchronized(this) { frames.size } to eventCount

    /** Удаляет все кадры и хронологию. */
    fun clear() {
        val d = dir ?: return
        plan.reset()
        exec.execute {
            try {
                timeline?.close(); timeline = null
                d.listFiles()?.forEach { it.delete() }
            } catch (_: Throwable) {}
            synchronized(this) { frames.clear(); nextIndex = 1 }
            lastFrame = ""; eventCount = 0
        }
    }

    /**
     * Собирает архив: кадры, хронология (timeline.txt), описание (info.txt) и дополнительные файлы [extra] (имя → текст).
     * Вызывать не из главного потока. null — записывать нечего или ошибка.
     */
    fun export(extra: Map<String, String>, info: List<String>): File? {
        val d = dir ?: return null
        val ctx = appContext ?: return null
        return try {
            exec.submit {}.get() // дождаться записанных кадров и событий
            timeline?.flush()
            val names = d.list()?.toList().orEmpty()
            val framesSorted = names.mapNotNull { n -> RecordNames.indexOf(n)?.let { it to n } }.sortedBy { it.first }.map { it.second }
            if (framesSorted.isEmpty() && !File(d, "timeline.txt").exists()) return null
            val out = File(ctx.cacheDir, "share").apply { mkdirs() }
            out.listFiles()?.forEach { it.delete() }
            val zip = File(out, "mt_record_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".zip")
            ZipOutputStream(FileOutputStream(zip)).use { z ->
                z.setLevel(0)
                val head = buildString {
                    appendLine("Запись экрана Merge Tactics AI")
                    appendLine("Экспорт: " + clock(System.currentTimeMillis(), false))
                    appendLine("Телефон: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE)
                    appendLine("Кадров: ${framesSorted.size} (JPEG, половина размера экрана), событий: $eventCount")
                    appendLine("Имя кадра: f_<номер>_<часы-минуты-секунды-мс>.jpg; в timeline.txt у каждой строки указан ближайший кадр.")
                    info.forEach { appendLine(it) }
                }
                put(z, "info.txt", head.toByteArray())
                File(d, "timeline.txt").takeIf { it.exists() }?.let { put(z, "timeline.txt", it.readBytes()) }
                extra.forEach { (n, t) -> put(z, n, t.toByteArray()) }
                for (n in framesSorted) put(z, "frames/$n", File(d, n).readBytes())
            }
            zip
        } catch (_: Throwable) { null }
    }

    private fun put(z: ZipOutputStream, name: String, bytes: ByteArray) {
        val e = ZipEntry(name)
        e.method = ZipEntry.STORED
        e.size = bytes.size.toLong()
        e.compressedSize = bytes.size.toLong()
        val crc = java.util.zip.CRC32().apply { update(bytes) }
        e.crc = crc.value
        z.putNextEntry(e); z.write(bytes); z.closeEntry()
    }
}
