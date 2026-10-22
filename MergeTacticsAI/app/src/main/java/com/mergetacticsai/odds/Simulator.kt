package com.mergetacticsai.odds

import kotlin.math.sqrt

/**
 * Моделирование Монте-Карло всего процесса: эликсир по раундам, бесплатный магазин каждый раунд,
 * платные обновления, свободные места, слияния, конечный пул и добор соперников.
 * Стратегия простая: покупаем каждую выпавшую копию, пока хватает эликсира и мест; обновляем, пока остаётся эликсир на покупку.
 */
object Simulator {
    class Result(val runs: Int, val successes: Int, val meanSpentOnSuccess: Double, val meanRoundsOnSuccess: Double) {
        val p: Double get() = if (runs == 0) 0.0 else successes.toDouble() / runs

        /** Доверительный интервал Уилсона 95% для доли успехов. */
        fun interval(z: Double = 1.96): Pair<Double, Double> {
            if (runs == 0) return 0.0 to 1.0
            val n = runs.toDouble()
            val d = 1 + z * z / n
            val c = p + z * z / (2 * n)
            val h = z * sqrt(p * (1 - p) / n + z * z / (4 * n * n))
            return ((c - h) / d).coerceIn(0.0, 1.0) to ((c + h) / d).coerceIn(0.0, 1.0)
        }
    }

    private fun cascade(st: IntArray, m: Int) {
        for (s in 1..2) while (st[s] >= m) { st[s] -= m; st[s + 1]++ }
    }

    private fun reached(goal: Goal, st: IntArray, total: Int): Boolean = when (goal.kind) {
        GoalKind.COPIES -> total >= goal.value
        GoalKind.STARS -> (goal.value.coerceIn(1, 3)..3).any { st[it] > 0 }
    }

    fun run(p: OddsParams, tiers: Map<Int, Int>, cost: Int, goal: Goal, runs: Int, seed: Long): Result {
        val rnd = java.util.Random(seed)
        val n = tiers[cost] ?: 0
        val finite = p.copiesPerTroop > 0
        val m = p.copiesPerMerge.coerceAtLeast(2)
        val q0 = ShopMath.slotChance(p, tiers, cost)
        val ptier = ShopMath.tierProb(p, tiers, cost)
        var success = 0
        var spentSum = 0.0
        var roundsSum = 0L

        repeat(runs) {
            val st = IntArray(4)
            st[1] = p.startCopies.coerceAtLeast(0)
            cascade(st, m)
            var total = st[1]
            var r = ShopMath.remainingTarget(p)
            var t = ShopMath.remainingTier(p, n)
            var elixir = p.startElixir.toDouble()
            var spent = 0.0
            var done = reached(goal, st, total)
            var round = 0
            while (!done && round < p.rounds) {
                round++
                elixir += p.income * p.budgetShare
                if (finite) {
                    repeat(p.othersDrawsPerRound) {
                        if (t > 0 && rnd.nextDouble() < ptier) {
                            if (r > 0 && rnd.nextDouble() < r.toDouble() / t) r--
                            t--
                        }
                    }
                }
                var refreshes = 0
                while (true) {
                    var hits = 0
                    var lr = r
                    var lt = t
                    repeat(p.shopSlots) {
                        val q = if (finite) {
                            if (lr <= 0 || lt <= 0) 0.0 else (ptier * p.qScale * lr / lt).coerceAtMost(1.0)
                        } else q0
                        if (rnd.nextDouble() < q) {
                            hits++
                            if (finite) { lr--; lt-- }
                        }
                    }
                    var k = 0
                    while (k < hits && elixir >= cost && (st[1] + st[2] + st[3]) < p.freeSlots) {
                        elixir -= cost
                        spent += cost
                        total++
                        st[1]++
                        cascade(st, m)
                        if (finite) { r--; t-- }
                        k++
                        if (reached(goal, st, total)) { done = true; break }
                    }
                    if (done) break
                    if (refreshes >= p.maxRefreshPerRound || elixir < p.refreshCost + cost) break
                    elixir -= p.refreshCost
                    spent += p.refreshCost
                    refreshes++
                }
            }
            if (done) {
                success++
                spentSum += spent
                roundsSum += round
            }
        }
        return Result(
            runs, success,
            if (success == 0) 0.0 else spentSum / success,
            if (success == 0) 0.0 else roundsSum.toDouble() / success,
        )
    }
}
