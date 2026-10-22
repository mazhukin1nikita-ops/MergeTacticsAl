package com.mergetacticsai.map

import android.content.Context

/** Текстовая запись состояния карты (чистый Kotlin, проверяется тестами). */
object MapCodec {
    fun encodeLayout(m: Map<HexCell, PlacedUnit>): String =
        m.entries.sortedWith(compareBy({ it.key.row }, { it.key.col }))
            .joinToString(";") { "${it.key.row},${it.key.col},${it.value.troopId},${it.value.stars}" }

    fun decodeLayout(s: String): Map<HexCell, PlacedUnit> {
        val out = LinkedHashMap<HexCell, PlacedUnit>()
        for (part in s.split(";")) {
            val f = part.split(",")
            if (f.size != 4) continue
            val r = f[0].toIntOrNull() ?: continue
            val c = f[1].toIntOrNull() ?: continue
            val st = f[3].toIntOrNull() ?: continue
            if (!HexMap.inBoard(r, c) || f[2].isBlank()) continue
            out[HexCell(r, c)] = PlacedUnit(f[2], st.coerceIn(1, 3))
        }
        return out
    }

    fun encodeMarks(m: Map<HexCell, CellMark>): String =
        m.entries.sortedWith(compareBy({ it.key.row }, { it.key.col }))
            .joinToString(";") { "${it.key.row},${it.key.col},${it.value.name}" }

    fun decodeMarks(s: String): Map<HexCell, CellMark> {
        val out = LinkedHashMap<HexCell, CellMark>()
        for (part in s.split(";")) {
            val f = part.split(",")
            if (f.size != 3) continue
            val r = f[0].toIntOrNull() ?: continue
            val c = f[1].toIntOrNull() ?: continue
            val mk = runCatching { CellMark.valueOf(f[2]) }.getOrNull() ?: continue
            if (HexMap.inBoard(r, c)) out[HexCell(r, c)] = mk
        }
        return out
    }

    fun encodeRadii(m: Map<String, Int>): String = m.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    fun decodeRadii(s: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (part in s.split(";")) {
            val i = part.lastIndexOf('=')
            if (i <= 0) continue
            val v = part.substring(i + 1).toIntOrNull() ?: continue
            if (v in 1..MAX_RADIUS) out[part.substring(0, i)] = v
        }
        return out
    }

    const val MAX_RADIUS = 8

    /** Скорость в клетках в секунду, вводится вручную; хранится с точностью до десятой. */
    const val MAX_SPEED = 10f

    /** Здоровье, урон за удар и ударов в секунду; вводятся вручную. Нулевое поле = «не задано». */
    const val MAX_HP = 100000f
    const val MAX_DAMAGE = 100000f
    const val MAX_APS = 10f

    fun encodeStats(m: Map<String, UnitStats>): String =
        m.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value.hp}:${it.value.damage}:${it.value.attackSpeed}" }

    fun decodeStats(s: String): Map<String, UnitStats> {
        val out = LinkedHashMap<String, UnitStats>()
        for (part in s.split(";")) {
            val i = part.lastIndexOf('=')
            if (i <= 0) continue
            val f = part.substring(i + 1).split(":")
            if (f.size != 3) continue
            val hp = f[0].toFloatOrNull() ?: continue
            val dmg = f[1].toFloatOrNull() ?: continue
            val aps = f[2].toFloatOrNull() ?: continue
            if (hp < 0f || hp > MAX_HP || dmg < 0f || dmg > MAX_DAMAGE || aps < 0f || aps > MAX_APS) continue
            if (hp == 0f && dmg == 0f && aps == 0f) continue
            out[part.substring(0, i)] = UnitStats(hp, dmg, aps)
        }
        return out
    }

    fun encodeSpeeds(m: Map<String, Float>): String =
        m.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    fun decodeSpeeds(s: String): Map<String, Float> {
        val out = LinkedHashMap<String, Float>()
        for (part in s.split(";")) {
            val i = part.lastIndexOf('=')
            if (i <= 0) continue
            val v = part.substring(i + 1).toFloatOrNull() ?: continue
            if (v > 0f && v <= MAX_SPEED) out[part.substring(0, i)] = v
        }
        return out
    }
}

/** Сохраняет карту в приватном хранилище приложения (отдельно от остальных настроек). */
class MapStore(context: Context) {
    private val p = context.getSharedPreferences("mt_map", Context.MODE_PRIVATE)

    fun loadLayout() = MapCodec.decodeLayout(p.getString("layout", "") ?: "")
    fun loadMarks() = MapCodec.decodeMarks(p.getString("marks", "") ?: "")
    fun loadRadii() = MapCodec.decodeRadii(p.getString("radii", "") ?: "")
    fun loadStats() = MapCodec.decodeStats(p.getString("stats", "") ?: "")
    fun saveStats(m: Map<String, UnitStats>) { p.edit().putString("stats", MapCodec.encodeStats(m)).apply() }
    fun loadStarFactor(): Float = p.getFloat("star_factor", 1f).coerceIn(1f, 3f)
    fun saveStarFactor(f: Float) { p.edit().putFloat("star_factor", f).apply() }
    fun loadSpeeds() = MapCodec.decodeSpeeds(p.getString("speeds", "") ?: "")

    /** Карта сборки: где игрок хочет видеть войска своей сборки (только ваша сторона). */
    fun loadBuildLayout(): Map<HexCell, PlacedUnit> =
        MapCodec.decodeLayout(p.getString("build_layout", "") ?: "").filterKeys { HexMap.isOwn(it) }
    fun saveBuildLayout(m: Map<HexCell, PlacedUnit>) {
        p.edit().putString("build_layout", MapCodec.encodeLayout(m.filterKeys { HexMap.isOwn(it) })).apply()
    }

    fun saveLayout(m: Map<HexCell, PlacedUnit>) { p.edit().putString("layout", MapCodec.encodeLayout(m)).apply() }
    fun saveMarks(m: Map<HexCell, CellMark>) { p.edit().putString("marks", MapCodec.encodeMarks(m)).apply() }
    fun saveRadii(m: Map<String, Int>) { p.edit().putString("radii", MapCodec.encodeRadii(m)).apply() }
    fun saveSpeeds(m: Map<String, Float>) { p.edit().putString("speeds", MapCodec.encodeSpeeds(m)).apply() }
}
