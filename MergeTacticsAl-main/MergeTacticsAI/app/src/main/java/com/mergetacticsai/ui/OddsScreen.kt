package com.mergetacticsai.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mergetacticsai.odds.Confidence
import com.mergetacticsai.odds.GoalKind
import com.mergetacticsai.odds.Goal
import com.mergetacticsai.odds.Keys
import com.mergetacticsai.odds.OddsController
import com.mergetacticsai.odds.OddsParams
import com.mergetacticsai.odds.OddsReport
import com.mergetacticsai.odds.num
import com.mergetacticsai.odds.numRangeText
import com.mergetacticsai.odds.pct
import com.mergetacticsai.odds.rangeText
import com.mergetacticsai.odds.tiersOf
import kotlinx.coroutines.delay

private fun shortKey(k: String) = when (k) {
    Keys.ODDS -> "шансы"
    Keys.POOL -> "пул"
    Keys.OTHERS -> "соперники"
    Keys.REFRESH -> "обновление"
    Keys.ROUNDS -> "раунды"
    else -> k
}

/** Вероятности: шанс увидеть войско, набрать копии, повысить звёздность, ожидаемая стоимость и шанс успеть до конца игры. */
@Composable
fun OddsScreen(c: OddsController, onBack: () -> Unit) {
    LaunchedEffect(Unit) { c.ensureTarget() }
    LaunchedEffect(c.state) {
        delay(300)
        c.recompute()
    }
    val s = c.state
    val p = s.params
    val rep = c.report
    val tiers = tiersOf(c.eligible())

    fun upd(f: (OddsParams) -> OddsParams) = c.update { it.copy(params = f(it.params)) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Назад") }
                Text("Вероятности", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Цель", style = MaterialTheme.typography.titleMedium)
                    var open by remember { mutableStateOf(false) }
                    val t = c.target()
                    Box {
                        OutlinedButton(onClick = { open = true }) { Text(if (t == null) "Выбрать войско" else "${t.cost} · ${t.name}") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            c.eligible().forEach { tr ->
                                DropdownMenuItem(text = { Text("${tr.cost} · ${tr.name}") }, onClick = { c.update { it.copy(targetId = tr.id) }; open = false })
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = s.goal.kind == GoalKind.STARS && s.goal.value == 2, onClick = { c.update { it.copy(goal = Goal(GoalKind.STARS, 2)) } }, label = { Text("Собрать 2★") })
                        FilterChip(selected = s.goal.kind == GoalKind.STARS && s.goal.value == 3, onClick = { c.update { it.copy(goal = Goal(GoalKind.STARS, 3)) } }, label = { Text("Собрать 3★") })
                        FilterChip(selected = s.goal.kind == GoalKind.COPIES, onClick = { c.update { it.copy(goal = Goal(GoalKind.COPIES, 3)) } }, label = { Text("Набрать копии") })
                    }
                    if (s.goal.kind == GoalKind.COPIES) {
                        Stepper("Нужно копий", s.goal.value.toString(), null,
                            { c.update { it.copy(goal = Goal(GoalKind.COPIES, (s.goal.value - 1).coerceAtLeast(1))) } },
                            { c.update { it.copy(goal = Goal(GoalKind.COPIES, (s.goal.value + 1).coerceAtMost(30))) } })
                    }
                }
            }
        }

        item { ResultCard(rep, c.busy, p.rounds, c.target()?.name ?: "войска") }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Параметры игры", style = MaterialTheme.typography.titleMedium)
                    Text("Проверены только эликсир за раунд, число карточек и слияние пары. Остальное — ваши предположения: поправьте по игре.", style = MaterialTheme.typography.bodySmall)
                    Stepper("Раундов до конца игры", p.rounds.toString(), "не проверено",
                        { upd { it.copy(rounds = (it.rounds - 1).coerceAtLeast(1)) } }, { upd { it.copy(rounds = (it.rounds + 1).coerceAtMost(40)) } })
                    Stepper("Цена обновления", p.refreshCost.toString(), "не проверено",
                        { upd { it.copy(refreshCost = (it.refreshCost - 1).coerceAtLeast(0)) } }, { upd { it.copy(refreshCost = (it.refreshCost + 1).coerceAtMost(10)) } })
                    Stepper("Эликсира за раунд", p.income.toString(), "проверено (Supercell blog)",
                        { upd { it.copy(income = (it.income - 1).coerceAtLeast(0)) } }, { upd { it.copy(income = (it.income + 1).coerceAtMost(20)) } })
                    Stepper("Эликсира на старте", p.startElixir.toString(), null,
                        { upd { it.copy(startElixir = (it.startElixir - 1).coerceAtLeast(0)) } }, { upd { it.copy(startElixir = (it.startElixir + 1).coerceAtMost(50)) } })
                    Stepper("Доля эликсира на это войско", "${p.budgetSharePct}%", null,
                        { upd { it.copy(budgetSharePct = (it.budgetSharePct - 10).coerceAtLeast(0)) } }, { upd { it.copy(budgetSharePct = (it.budgetSharePct + 10).coerceAtMost(100)) } })
                    Stepper("Обновлений за раунд, не больше", p.maxRefreshPerRound.toString(), null,
                        { upd { it.copy(maxRefreshPerRound = (it.maxRefreshPerRound - 1).coerceAtLeast(0)) } }, { upd { it.copy(maxRefreshPerRound = (it.maxRefreshPerRound + 1).coerceAtMost(10)) } })
                    Stepper("Мест под копии этого войска", p.freeSlots.toString(), "скамейка и поле, считая занятые им",
                        { upd { it.copy(freeSlots = (it.freeSlots - 1).coerceAtLeast(1)) } }, { upd { it.copy(freeSlots = (it.freeSlots + 1).coerceAtMost(20)) } })
                    Stepper("Копий уже есть (1★)", p.startCopies.toString(), null,
                        { upd { it.copy(startCopies = (it.startCopies - 1).coerceAtLeast(0)) } }, { upd { it.copy(startCopies = (it.startCopies + 1).coerceAtMost(8)) } })
                    Stepper("Карточек в магазине", p.shopSlots.toString(), "по экрану игры",
                        { upd { it.copy(shopSlots = (it.shopSlots - 1).coerceAtLeast(1)) } }, { upd { it.copy(shopSlots = (it.shopSlots + 1).coerceAtMost(6)) } })
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Пул и шансы по цене", style = MaterialTheme.typography.titleMedium)
                    Stepper("Копий каждого войска в пуле", if (p.copiesPerTroop == 0) "без лимита" else p.copiesPerTroop.toString(), "не проверено; 0 — пул не ограничен",
                        { upd { it.copy(copiesPerTroop = (it.copiesPerTroop - 1).coerceAtLeast(0)) } }, { upd { it.copy(copiesPerTroop = (it.copiesPerTroop + 1).coerceAtMost(60)) } })
                    if (p.copiesPerTroop > 0) {
                        Stepper("Копий этого войска у других", p.takenByOthers.toString(), null,
                            { upd { it.copy(takenByOthers = (it.takenByOthers - 1).coerceAtLeast(0)) } }, { upd { it.copy(takenByOthers = (it.takenByOthers + 1).coerceAtMost(60)) } })
                        Stepper("Карт забирают соперники за раунд", p.othersDrawsPerRound.toString(), "не проверено",
                            { upd { it.copy(othersDrawsPerRound = (it.othersDrawsPerRound - 1).coerceAtLeast(0)) } }, { upd { it.copy(othersDrawsPerRound = (it.othersDrawsPerRound + 1).coerceAtMost(60)) } })
                    }
                    Text(
                        if (p.weights.isEmpty()) "Шанс по цене: все войска равновероятны (по числу войск каждой цены)."
                        else "Шанс по цене: заданы веса (проверенных данных нет).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    for ((cost, n) in tiers.entries.sortedBy { it.key }) {
                        val cur = p.weights[cost]
                        Stepper("Вес цены $cost", if (cur == null) "по умолчанию" else num(cur), "войск этой цены: $n",
                            { upd { o -> o.copy(weights = withWeight(o.weights, tiers, cost, (cur ?: n.toDouble()) - 1)) } },
                            { upd { o -> o.copy(weights = withWeight(o.weights, tiers, cost, (cur ?: n.toDouble()) + 1)) } })
                    }
                    if (p.weights.isNotEmpty()) OutlinedButton(onClick = { upd { it.copy(weights = emptyMap()) } }) { Text("Сбросить веса") }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Достоверность", style = MaterialTheme.typography.titleMedium)
                    Text("Отметьте параметр проверенным, только если сверили его с игрой: тогда диапазон по нему не расширяется. Иначе ниже задаётся, насколько вы в нём не уверены.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in Keys.all) FilterChip(selected = k in s.verified, onClick = { c.toggleVerified(k) }, label = { Text(shortKey(k)) })
                    }
                    if (Keys.ODDS !in s.verified) Stepper("Неточность шансов, ±", "${s.unc.oddsSpreadPct}%", null,
                        { c.update { it.copy(unc = it.unc.copy(oddsSpreadPct = (it.unc.oddsSpreadPct - 10).coerceAtLeast(0))) } },
                        { c.update { it.copy(unc = it.unc.copy(oddsSpreadPct = (it.unc.oddsSpreadPct + 10).coerceAtMost(90))) } })
                    if (Keys.REFRESH !in s.verified) Stepper("Неточность цены обновления, ±", s.unc.refreshSpread.toString(), null,
                        { c.update { it.copy(unc = it.unc.copy(refreshSpread = (it.unc.refreshSpread - 1).coerceAtLeast(0))) } },
                        { c.update { it.copy(unc = it.unc.copy(refreshSpread = (it.unc.refreshSpread + 1).coerceAtMost(5))) } })
                    if (Keys.ROUNDS !in s.verified) Stepper("Неточность числа раундов, ±", s.unc.roundsSpread.toString(), null,
                        { c.update { it.copy(unc = it.unc.copy(roundsSpread = (it.unc.roundsSpread - 1).coerceAtLeast(0))) } },
                        { c.update { it.copy(unc = it.unc.copy(roundsSpread = (it.unc.roundsSpread + 1).coerceAtMost(10))) } })
                    if (p.copiesPerTroop > 0 && Keys.POOL !in s.verified) Stepper("Неточность размера пула, ±", "${s.unc.poolSpreadPct}%", null,
                        { c.update { it.copy(unc = it.unc.copy(poolSpreadPct = (it.unc.poolSpreadPct - 5).coerceAtLeast(0))) } },
                        { c.update { it.copy(unc = it.unc.copy(poolSpreadPct = (it.unc.poolSpreadPct + 5).coerceAtMost(90))) } })
                    if (p.copiesPerTroop > 0 && Keys.OTHERS !in s.verified) Stepper("Неточность добора соперников, ±", "${s.unc.othersSpreadPct}%", null,
                        { c.update { it.copy(unc = it.unc.copy(othersSpreadPct = (it.unc.othersSpreadPct - 10).coerceAtLeast(0))) } },
                        { c.update { it.copy(unc = it.unc.copy(othersSpreadPct = (it.unc.othersSpreadPct + 10).coerceAtMost(90))) } })
                }
            }
        }

        item {
            Text(
                "Как считается. Шанс увидеть войско, шанс набрать копии за несколько просмотров и ожидаемая стоимость — точные формулы " +
                    "для модели «карточки в магазине независимы». Шанс успеть до конца игры — моделирование Монте-Карло (20 000 игр на сценарий) " +
                    "с эликсиром, обновлениями, местами, слияниями, конечным пулом и добором соперников. Диапазон — пессимистичный и оптимистичный " +
                    "наборы непроверенных параметров. Правила генерации магазина в приложении не проверены, поэтому это оценка, а не гарантия.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun withWeight(cur: Map<Int, Double>, tiers: Map<Int, Int>, cost: Int, v: Double): Map<Int, Double> {
    val base = if (cur.isEmpty()) tiers.mapValues { it.value.toDouble() } else cur
    return base + (cost to v.coerceIn(0.0, 100.0))
}

@Composable
private fun Stepper(label: String, value: String, note: String?, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onMinus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("−") }
        Text(value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.widthIn(min = 56.dp))
        OutlinedButton(onClick = onPlus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("+") }
    }
}

@Composable
private fun ResultCard(rep: OddsReport?, busy: Boolean, rounds: Int, name: String) {
    if (rep == null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) { Text(if (busy) "Считаю…" else "Выберите войско, чтобы увидеть расчёт.", style = MaterialTheme.typography.bodyMedium) }
        }
        return
    }
    val container = when (rep.confidence) {
        Confidence.HIGH -> MaterialTheme.colorScheme.primaryContainer
        Confidence.MEDIUM -> MaterialTheme.colorScheme.secondaryContainer
        Confidence.LOW -> MaterialTheme.colorScheme.errorContainer
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Результат для «$name»" + if (busy) " (обновляется…)" else "", style = MaterialTheme.typography.titleMedium)
            Text(
                "Достоверность: ${rep.confidence.title}." +
                    if (rep.unverified.isEmpty()) " Все параметры отмечены проверенными, но это всё равно модель, а не сама игра."
                    else " Не проверено: ${rep.unverified.joinToString(", ")}.",
                style = MaterialTheme.typography.bodySmall,
            )
            for (w in rep.warnings) Text("⚠ $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

            Text("Появление в магазине", style = MaterialTheme.typography.titleSmall)
            Text("Шанс на одну карточку: ${rangeText(rep.slotChance)}", style = MaterialTheme.typography.bodyMedium)
            Text("Увидеть в одном магазине: ${rangeText(rep.oneShop)}", style = MaterialTheme.typography.bodyMedium)
            for ((n, r) in rep.afterRefreshes) {
                Text("За $n ${refreshWord(n)}: ${rangeText(r)}", style = MaterialTheme.typography.bodyMedium)
            }

            Text("Цель", style = MaterialTheme.typography.titleSmall)
            Text("Нужно ещё копий: ${rep.neededCopies}", style = MaterialTheme.typography.bodyMedium)
            rep.copiesAppear?.let {
                Text("Нужные копии просто выпадут в бесплатных магазинах за $rounds раундов: ${rangeText(it)}", style = MaterialTheme.typography.bodyMedium)
            }
            Text("Ожидаемо просмотров магазина: ${numRangeText(rep.expectedViews)}", style = MaterialTheme.typography.bodyMedium)
            Text("Ожидаемая стоимость при платных обновлениях: ${numRangeText(rep.expectedElixir, "эликсира")}", style = MaterialTheme.typography.bodyMedium)

            Text("До конца игры", style = MaterialTheme.typography.titleSmall)
            Text("Шанс достичь цели за $rounds раундов: ${rangeText(rep.finish.range)}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Точность моделирования ±${pct((rep.finish.ciHigh - rep.finish.ciLow) / 2.0)}.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (rep.finish.meanElixir != null && rep.finish.meanRounds != null) {
                Text("Если цель достигнута: в среднем ${num(rep.finish.meanElixir)} эликсира и ${num(rep.finish.meanRounds)} раунда.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun refreshWord(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "обновление"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "обновления"
    else -> "обновлений"
}
