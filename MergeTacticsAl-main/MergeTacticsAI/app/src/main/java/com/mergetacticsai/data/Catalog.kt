package com.mergetacticsai.data

import android.content.Context
import org.json.JSONArray

/** Элемент каталога (свойство, правитель, модификатор). tiers — пороги синергии свойства. */
data class CatalogItem(
    val id: String, val name: String, val desc: String, val verified: Boolean,
    val tiers: List<Int> = emptyList(), val legacy: Boolean = false,
)

object Catalog {
    fun parse(text: String): List<CatalogItem> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val t = o.optJSONArray("tiers")
            CatalogItem(
                o.getString("id"), o.getString("name"), o.optString("desc", ""), o.optBoolean("verified", false),
                tiers = if (t == null) emptyList() else (0 until t.length()).map { i -> t.getInt(i) },
                legacy = o.optBoolean("legacy", false),
            )
        }
    }

    fun load(context: Context, file: String): List<CatalogItem> =
        runCatching { parse(context.assets.open(file).bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())
}
