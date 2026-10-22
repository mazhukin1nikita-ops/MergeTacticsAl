package com.mergetacticsai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Local-only diagnostics: simulates conditions for the app's own recovery UI; does not alter game traffic. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    var simulatedOffline by remember { mutableStateOf(false) }
    var latency by remember { mutableStateOf(250f) }
    var packetLoss by remember { mutableStateOf(0f) }
    var recoveryTest by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf(listOf("Диагностика готова")) }

    fun record(message: String) { events = (listOf(message) + events).take(12) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Сетевые тесты и восстановление", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }
        item { Text("Тесты воздействуют только на диагностический экран приложения. Они не отключают интернет телефона и не меняют соединение самой игры.", style = MaterialTheme.typography.bodyMedium) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Симуляция потери соединения", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(if (simulatedOffline) "Офлайн (симуляция)" else "Соединение доступно")
                        Switch(checked = simulatedOffline, onCheckedChange = {
                            simulatedOffline = it
                            record(if (it) "Включена локальная симуляция офлайн" else "Локальное соединение восстановлено")
                        })
                    }
                    Text(if (simulatedOffline) "Проверьте, что интерфейс показывает ошибку сети и предлагает повторить действие." else "Включите переключатель, чтобы проверить состояние ошибки в интерфейсе.")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Задержка ответа (симуляция)", style = MaterialTheme.typography.titleMedium)
                    Text("${latency.toInt()} мс")
                    Slider(value = latency, onValueChange = { latency = it }, valueRange = 0f..3000f)
                    Text("Условная потеря пакетов: ${packetLoss.toInt()}%")
                    Slider(value = packetLoss, onValueChange = { packetLoss = it }, valueRange = 0f..50f, steps = 9)
                    Button(onClick = { record("Тест параметров: задержка ${latency.toInt()} мс, потеря ${packetLoss.toInt()}%") }, modifier = Modifier.fillMaxWidth()) { Text("Запустить тест интерфейса") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Проверка восстановления", style = MaterialTheme.typography.titleMedium)
                    Text("Имитирует ошибку и проверяет сценарий возврата в рабочее состояние; приложение и игра не завершаются принудительно.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { recoveryTest = true; record("Тест: обнаружена условная ошибка") }, modifier = Modifier.weight(1f)) { Text("Имитировать сбой") }
                        OutlinedButton(onClick = { recoveryTest = false; simulatedOffline = false; record("Тест: состояние восстановлено") }, modifier = Modifier.weight(1f)) { Text("Восстановить") }
                    }
                    Text(if (recoveryTest) "Состояние: ошибка — ожидается восстановление" else "Состояние: ${if (simulatedOffline) "офлайн-симуляция" else "готово"}", color = if (recoveryTest || simulatedOffline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Журнал событий", style = MaterialTheme.typography.titleMedium)
                    events.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { events = listOf("Журнал очищен") }, modifier = Modifier.fillMaxWidth()) { Text("Очистить журнал") }
                }
            }
        }
        item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад") } }
    }
}
