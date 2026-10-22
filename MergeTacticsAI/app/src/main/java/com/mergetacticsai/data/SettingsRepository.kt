package com.mergetacticsai.data

import android.content.Context
import com.mergetacticsai.strategy.StrategyConfig
import com.mergetacticsai.strategy.StrategyMode

/** Хранит настройки в приватном хранилище приложения (ключ не шифруется). */
class SettingsRepository(context: Context) {
    private val p = context.getSharedPreferences("mt_ai", Context.MODE_PRIVATE)

    fun load(): StrategyConfig {
        val mode = runCatching { StrategyMode.valueOf(p.getString("mode", "") ?: "") }
            .getOrDefault(StrategyMode.MERGE_CHEAP)
        val targets = (p.getString("targets", "") ?: "").split("|").filter { it.isNotBlank() }
        return StrategyConfig(mode, targets)
    }

    fun save(c: StrategyConfig) {
        p.edit().putString("mode", c.mode.name).putString("targets", c.targets.joinToString("|")).apply()
    }

    fun apiKey(): String = p.getString("apiKey", "") ?: ""
    fun maxPerHour(): Int = p.getInt("maxPerHour", 120)
    fun minIntervalMs(): Long = p.getLong("minIntervalMs", 6000L)
    fun timingBuy() = p.getLong("timingBuy", 100L)
    fun timingPlace() = p.getLong("timingPlace", 150L)
    fun timingSell() = p.getLong("timingSell", 200L)
    fun timingMerge() = p.getLong("timingMerge", 300L)
    fun timingVerify() = p.getLong("timingVerify", 600L)
    fun timingLoop() = p.getLong("timingLoop", 80L)
    fun saveTimings(buy: Long, place: Long, sell: Long, merge: Long, verify: Long, loop: Long) {
        p.edit().putLong("timingBuy", buy.coerceIn(0L, 2500L))
            .putLong("timingPlace", place.coerceIn(0L, 2500L))
            .putLong("timingSell", sell.coerceIn(0L, 2500L))
            .putLong("timingMerge", merge.coerceIn(0L, 2500L))
            .putLong("timingVerify", verify.coerceIn(0L, 2500L))
            .putLong("timingLoop", loop.coerceIn(25L, 1000L)).apply()
    }
    fun model(): String = p.getString("model", "gemini-3.5-flash") ?: "gemini-3.5-flash"

    fun useGemini(): Boolean = p.getBoolean("useGemini", false)
    fun setUseGemini(v: Boolean) { p.edit().putBoolean("useGemini", v).apply() }

    fun ruler(): String = p.getString("ruler", "") ?: ""
    fun setRuler(id: String) { p.edit().putString("ruler", id).apply() }
    fun traits(): Set<String> = (p.getString("traits", "") ?: "").split("|").filter { it.isNotBlank() }.toSet()
    fun setTraits(s: Set<String>) { p.edit().putString("traits", s.joinToString("|")).apply() }
    fun modifiers(): Set<String> = (p.getString("modifiers", "") ?: "").split("|").filter { it.isNotBlank() }.toSet()
    fun setModifiers(s: Set<String>) { p.edit().putString("modifiers", s.joinToString("|")).apply() }
    fun strictTraits(): Boolean = p.getBoolean("strictTraits", false)
    fun setStrictTraits(v: Boolean) { p.edit().putBoolean("strictTraits", v).apply() }

    fun enabledTroops(default: Set<String>): Set<String> =
        if (p.contains("troopsOn")) (p.getString("troopsOn", "") ?: "").split("|").filter { it.isNotBlank() }.toSet() else default
    fun setEnabledTroops(s: Set<String>) { p.edit().putString("troopsOn", s.joinToString("|")).apply() }
    fun knownCards(): String = p.getString("known", "") ?: ""
    fun setKnownCards(s: String) { p.edit().putString("known", s).apply() }

    fun placeSel(): Set<String> = (p.getString("placeSel", "") ?: "").split("|").filter { it.isNotBlank() }.toSet()
    fun setPlaceSel(s: Set<String>) { p.edit().putString("placeSel", s.joinToString("|")).apply() }

    fun saveAi(key: String, maxPerHour: Int) {
        val e = p.edit()
        if (key.isNotBlank()) e.putString("apiKey", key.trim())
        e.putInt("maxPerHour", maxPerHour.coerceIn(10, 2000)).apply()
    }

    fun clearKey() { p.edit().remove("apiKey").apply() }

    fun clear() { p.edit().clear().apply() }
}
