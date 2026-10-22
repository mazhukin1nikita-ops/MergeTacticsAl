package com.mergetacticsai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mergetacticsai.journal.DecisionJournal
import com.mergetacticsai.journal.JournalEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val STATUS = mapOf("PLANNED" to "решено, итога нет", "DONE" to "выполнено", "FAILED" to "не получилось", "INFO" to "справка")
private val KIND = mapOf(
    "BUY" to "Покупка", "SAVE" to "Копим эликсир", "ABANDON" to "Отказ", "PIVOT" to "Смена сборки",
    "SELL" to "Продажа", "ADAPT" to "Соперники", "EVAL" to "Оценка прокачки", "EVENT" to "Событие", "DECISION" to "Решение",
)

/** Журнал решений бота: что произошло, почему и чем кончилось. Хранится в локальной базе на устройстве. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    journal: DecisionJournal, onBack: () -> Unit, sellLines: () -> List<String> = { emptyList() },
    markResult: (Boolean) -> Int = { 0 }, decisionLines: () -> List<String> = { emptyList() },
) {
    var resultNote by remember { mutableStateOf("") }
    var stats by remember { mutableStateOf(decisionLines()) }
    var items by remember { mutableStateOf(journal.recent(200)) }
    var open by remember { mutableStateOf<Long?>(null) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale("ru")) }
    run {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("← Назад") }
                    Text("Журнал решений", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { items = journal.recent(200) }) { Text("Обновить") }
                    OutlinedButton(onClick = { journal.clear(); items = emptyList() }) { Text("Очистить") }
                }
                Text("Записей: ${journal.count()}. Файл базы хранится только на этом устройстве.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Результат последнего раунда", style = MaterialTheme.typography.titleSmall)
                        Text("Бот сам не видит, выиграли вы или проиграли. Отметьте итог раунда: по нему оценка войск постепенно корректируется.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { val n = markResult(true); resultNote = if (n > 0) "Записано: победа ($n)" else "Нет решений этого раунда"; stats = decisionLines() }) { Text("Победа") }
                            OutlinedButton(onClick = { val n = markResult(false); resultNote = if (n > 0) "Записано: поражение ($n)" else "Нет решений этого раунда"; stats = decisionLines() }) { Text("Поражение") }
                        }
                        if (resultNote.isNotEmpty()) Text(resultNote, style = MaterialTheme.typography.labelMedium)
                        for (l in stats) Text(l, style = MaterialTheme.typography.bodySmall)
                        Text("Поправка не больше ±15% и только после нескольких раундов; это статистика совпадений, а не доказательство причины.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val sell = sellLines()
            if (sell.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Продажа с поля: надёжность способов", style = MaterialTheme.typography.titleSmall)
                        for (l in sell) Text(l, style = MaterialTheme.typography.bodySmall)
                        Text("Бот сам ставит вперёд способ, который чаще срабатывал на этом экране (после 3 попыток). Это подсчёт частот, а не машинное обучение.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (items.isEmpty()) item { Text("Пока пусто: записи появляются, когда бот принимает решения.") }
            items(items, key = { it.id }) { e -> EntryCard(e, fmt, open == e.id) { open = if (open == e.id) null else e.id } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryCard(e: JournalEntry, fmt: SimpleDateFormat, expanded: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${fmt.format(Date(e.at))} · " + (e.round?.let { "раунд $it · " } ?: "") + (KIND[e.kind] ?: e.kind) + " · " + (STATUS[e.status] ?: e.status),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(e.title, fontWeight = FontWeight.Medium)
            Text("Причина: ${e.reason}", style = MaterialTheme.typography.bodySmall)
            if (e.outcome.isNotEmpty()) Text("Итог: ${e.outcome}", style = MaterialTheme.typography.bodySmall)
            if (expanded && e.details.isNotEmpty()) Text(e.details, style = MaterialTheme.typography.bodySmall)
            else if (e.details.isNotEmpty()) Text("Нажмите, чтобы раскрыть подробности", style = MaterialTheme.typography.labelSmall)
        }
    }
}
