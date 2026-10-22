package com.mergetacticsai.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mergetacticsai.adapt.AdaptController
import com.mergetacticsai.adapt.Level
import com.mergetacticsai.adapt.OpponentAnalyzer

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun lv(l: Level) = "уверенность: ${l.title}"

/** Противники: видимые составы, востребованность, дефицит, комбинации, угрозы и контрмеры. Скрытые карты — только вероятности. */
@Composable
fun AdaptScreen(c: AdaptController, onBack: () -> Unit) {
    LaunchedEffect(Unit) { c.recompute() }
    val a = c.analysis
    run {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("← Назад") }
                    Text("Противники", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            item {
                Section("Что это") {
                    Text(
                        "Приложение видит только выставленные на поле войска, которые вы задали на карте. Скамейку и купленные карты соперников увидеть нельзя: " +
                            "про них показаны вероятностные оценки с диапазоном, а не утверждения. Числа допущений в базе не проверены.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Раунд сейчас: ${c.currentRound()}. Общая уверенность: ${a?.level?.title ?: "нет данных"}.", fontWeight = FontWeight.Medium)
                }
            }
            item {
                Section("Записать состав") {
                    Text("Выберите соперника и нажмите кнопку: расстановка противника с «Тактической карты» запомнится. При правке карты состав обновляется сам.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (l in c.labels()) FilterChip(selected = c.label == l, onClick = { c.label = l }, label = { Text(l) })
                    }
                    Button(onClick = { c.observe() }) { Text("Запомнить противника с карты") }
                    c.info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (c.snapshots.isNotEmpty()) item {
                Section("Видимые составы") {
                    for (s in c.snapshots) {
                        val age = c.age(s)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("${s.label}: ${s.units.size} юн., неопознано ${s.unknownCount}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "раунд ${s.round}" + (if (age == 0) ", обновлён в этом раунде" else ", $age р. назад") + ", ${s.source.title}" + (if (age > 2) " — устарел" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { c.forget(s.label) }) { Text("Удалить") }
                        }
                    }
                    OutlinedButton(onClick = { c.clearAll() }) { Text("Удалить все снимки") }
                }
            }
            if (a != null && !a.isEmpty) {
                item {
                    Section("Востребованные войска") {
                        if (a.demand.isEmpty()) Text("Нет опознанных войск.", style = MaterialTheme.typography.bodySmall)
                        for (d in a.demand.take(8)) Text("«${d.name}» — у ${d.opponents} соперн., видно ${d.copiesSeen} коп. (${lv(d.level)})", style = MaterialTheme.typography.bodyMedium)
                        if (a.traitDemand.isNotEmpty()) Text("Свойства у соперников: " + a.traitDemand.joinToString { "${it.first} ×${it.second}" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Section("Какие карты могут быть в дефиците") {
                        if (a.scarcity.isEmpty()) Text("Нет данных.", style = MaterialTheme.typography.bodySmall)
                        for (s in a.scarcity.take(8)) {
                            Text("${s.stock.title}: " + s.text, style = MaterialTheme.typography.bodyMedium)
                            Text("Вероятность скрытой копии у кого-то из соперников около ${Math.round(s.hiddenAny * 100)}%, ${lv(s.level)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Section("Какие комбинации собирают") {
                        if (a.combos.isEmpty()) Text("Видимых комбинаций у порога нет.", style = MaterialTheme.typography.bodySmall)
                        for (k in a.combos.take(8)) Text("${k.text} (${lv(k.level)})", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    Section("Угрозы") {
                        for (t in a.threats) {
                            Text("${t.opponent}: угроза ${t.level.title} (${lv(t.confidence)})", fontWeight = FontWeight.Medium)
                            for (r in t.reasons) Text("• $r", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (a != null) {
                item {
                    Section("Контрмеры") {
                        if (a.counters.isEmpty()) Text("Нет подходящих.", style = MaterialTheme.typography.bodySmall)
                        for (k in a.counters) {
                            Text("${k.kind.title}: ${k.text} (${lv(k.level)})", style = MaterialTheme.typography.bodyMedium)
                            Text("Основание: ${k.basis}", style = MaterialTheme.typography.labelSmall)
                        }
                        val bias = OpponentAnalyzer.MAX_BIAS
                        Text(
                            "Влияние на бота: только при уверенности «средняя» или выше бот чуть снижает оценку карт вне сборки, за которые идёт борьба (поправка не больше ${bias}).",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (a.notes.isNotEmpty()) item {
                    Section("Примечания") { for (n in a.notes) Text("• $n", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
