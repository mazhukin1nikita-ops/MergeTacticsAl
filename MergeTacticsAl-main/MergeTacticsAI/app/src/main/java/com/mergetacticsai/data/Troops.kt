package com.mergetacticsai.data

import android.content.Context
import org.json.JSONArray

/** Войско из troops.json. trait — id свойства (noble, clan, ...). legacy — убрано из текущего пула. */
data class Troop(
    val id: String, val name: String, val cost: Int, val trait: String,
    val legacy: Boolean, val rarity: String, val desc: String,
)

object TroopDb {
    fun parse(text: String): List<Troop> {
        val a = JSONArray(text)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Troop(o.getString("id"), o.getString("name"), o.getInt("cost"), o.optString("trait", ""),
                o.optBoolean("legacy", false), o.optString("rarity", ""), o.optString("desc", ""))
        }
    }

    fun load(context: Context): List<Troop> =
        runCatching { parse(context.assets.open("troops.json").bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())

    /** По умолчанию включены все текущие войска (без legacy и без специальных объектов за 0). */
    fun defaultEnabled(all: List<Troop>): Set<String> = all.filter { !it.legacy && it.cost > 0 }.map { it.id }.toSet()
}
