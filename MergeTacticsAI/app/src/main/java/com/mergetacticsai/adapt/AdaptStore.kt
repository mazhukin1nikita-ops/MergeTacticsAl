package com.mergetacticsai.adapt

import android.content.Context

/** Запись снимков в одну строку: «метка|раунд|время|источник|id:звёзды,id:звёзды;…». Чистый Kotlin, проверяется тестами. */
object SnapshotCodec {
    private fun clean(s: String) = s.replace("|", " ").replace(";", " ").replace("\n", " ").trim()

    fun encode(list: List<Snapshot>): String = list.joinToString("\n") { s ->
        listOf(clean(s.label), s.round, s.at, s.source.name, s.units.joinToString(",") { "${it.troopId}:${it.stars}" }).joinToString("|")
    }

    fun decode(text: String): List<Snapshot> = text.lines().mapNotNull { line ->
        val f = line.split("|")
        if (f.size < 5) return@mapNotNull null
        val round = f[1].toIntOrNull() ?: return@mapNotNull null
        val at = f[2].toLongOrNull() ?: 0L
        val src = runCatching { Source.valueOf(f[3]) }.getOrDefault(Source.MANUAL)
        val units = f[4].split(",").filter { it.isNotBlank() }.mapNotNull {
            val i = it.lastIndexOf(':')
            if (i <= 0) null else Seen(it.substring(0, i), (it.substring(i + 1).toIntOrNull() ?: 1).coerceIn(1, 3))
        }
        if (f[0].isBlank()) null else Snapshot(f[0], round, at, units, src)
    }
}

class AdaptStore(context: Context) {
    private val p = context.getSharedPreferences("mt_adapt", Context.MODE_PRIVATE)
    fun load(): List<Snapshot> = SnapshotCodec.decode(p.getString("snapshots", "") ?: "")
    fun save(list: List<Snapshot>) { p.edit().putString("snapshots", SnapshotCodec.encode(list)).apply() }
}
