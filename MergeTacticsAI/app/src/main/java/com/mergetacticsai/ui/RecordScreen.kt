package com.mergetacticsai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    enabled: Boolean, onToggle: (Boolean) -> Unit, stats: () -> Pair<Int, Int>,
    onShare: () -> Unit, onClear: () -> Unit, onBack: () -> Unit, busy: Boolean, note: String,
) {
    var s by remember { mutableStateOf(stats()) }
    var on by remember { mutableStateOf(enabled) }
    LaunchedEffect(Unit) { while (true) { s = stats(); delay(1000) } }
    Scaffold(topBar = { TopAppBar(title = { Text("Запись экрана") }, navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Записывать, пока бот работает", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(checked = on, onCheckedChange = { on = it; onToggle(it) })
                        }
                        Text("Это не видео, а кадры экрана и хронология действий бота: что он купил, поставил, продал и почему. Кадры пишутся чаще сразу после действий. Хранятся последние 300 кадров.", style = MaterialTheme.typography.bodySmall)
                        Text("Сохранено кадров: ${s.first}, событий: ${s.second}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onShare, enabled = !busy && (s.first > 0 || s.second > 0), modifier = Modifier.fillMaxWidth()) {
                            Text(if (busy) "Собираю архив…" else "Отправить запись")
                        }
                        OutlinedButton(onClick = onClear, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Очистить запись") }
                        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Text("Запись видит весь экран, включая уведомления поверх игры, и лежит только на телефоне, пока вы сами её не отправите. После разбора нажмите «Очистить запись».", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
