package com.mergetacticsai.odds

import android.content.Context

/** Текстовая запись настроек расчёта: «ключ=значение;ключ=значение». Чистый Kotlin, проверяется тестами. */
object OddsCodec {
    fun encode(s: OddsState): String {
        val p = s.params
        val u = s.unc
        return listOf(
            "t=${s.targetId}", "gk=${s.goal.kind.name}", "gv=${s.goal.value}",
            "slots=${p.shopSlots}",
            "w=" + p.weights.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" },
            "pool=${p.copiesPerTroop}", "taken=${p.takenByOthers}", "oth=${p.othersDrawsPerRound}",
            "ref=${p.refreshCost}", "inc=${p.income}", "se=${p.startElixir}", "rounds=${p.rounds}",
            "free=${p.freeSlots}", "share=${p.budgetSharePct}", "maxref=${p.maxRefreshPerRound}",
            "start=${p.startCopies}", "merge=${p.copiesPerMerge}",
            "uo=${u.oddsSpreadPct}", "ur=${u.refreshSpread}", "un=${u.roundsSpread}", "up=${u.poolSpreadPct}", "ut=${u.othersSpreadPct}",
            "ver=" + s.verified.sorted().joinToString(","),
        ).joinToString(";")
    }

    fun decode(text: String): OddsState {
        val m = HashMap<String, String>()
        for (part in text.split(";")) {
            val i = part.indexOf('=')
            if (i > 0) m[part.substring(0, i)] = part.substring(i + 1)
        }
        fun int(k: String, d: Int, r: IntRange) = (m[k]?.toIntOrNull() ?: d).coerceIn(r.first, r.last)
        val def = OddsParams()
        val du = Uncertainty()
        val weights = LinkedHashMap<Int, Double>()
        for (e in (m["w"] ?: "").split(",")) {
            val f = e.split(":")
            if (f.size != 2) continue
            val c = f[0].toIntOrNull() ?: continue
            val v = f[1].toDoubleOrNull() ?: continue
            if (c in 1..10 && v >= 0.0 && v <= 1000.0) weights[c] = v
        }
        val kind = runCatching { GoalKind.valueOf(m["gk"] ?: "") }.getOrDefault(GoalKind.STARS)
        val gv = if (kind == GoalKind.STARS) int("gv", 2, 2..3) else int("gv", 2, 1..30)
        return OddsState(
            targetId = m["t"] ?: "",
            goal = Goal(kind, gv),
            params = OddsParams(
                shopSlots = int("slots", def.shopSlots, 1..6),
                weights = weights,
                copiesPerTroop = int("pool", def.copiesPerTroop, 0..60),
                takenByOthers = int("taken", def.takenByOthers, 0..60),
                othersDrawsPerRound = int("oth", def.othersDrawsPerRound, 0..60),
                refreshCost = int("ref", def.refreshCost, 0..10),
                income = int("inc", def.income, 0..20),
                startElixir = int("se", def.startElixir, 0..50),
                rounds = int("rounds", def.rounds, 1..40),
                freeSlots = int("free", def.freeSlots, 1..20),
                budgetSharePct = int("share", def.budgetSharePct, 0..100),
                maxRefreshPerRound = int("maxref", def.maxRefreshPerRound, 0..10),
                startCopies = int("start", def.startCopies, 0..8),
                copiesPerMerge = int("merge", def.copiesPerMerge, 2..4),
            ),
            unc = Uncertainty(
                oddsSpreadPct = int("uo", du.oddsSpreadPct, 0..90),
                refreshSpread = int("ur", du.refreshSpread, 0..5),
                roundsSpread = int("un", du.roundsSpread, 0..10),
                poolSpreadPct = int("up", du.poolSpreadPct, 0..90),
                othersSpreadPct = int("ut", du.othersSpreadPct, 0..90),
            ),
            verified = (m["ver"] ?: "").split(",").filter { it in Keys.all }.toSet(),
        )
    }
}

class OddsStore(context: Context) {
    private val p = context.getSharedPreferences("mt_odds", Context.MODE_PRIVATE)
    fun load(): OddsState = OddsCodec.decode(p.getString("state", "") ?: "")
    fun save(s: OddsState) { p.edit().putString("state", OddsCodec.encode(s)).apply() }
}
