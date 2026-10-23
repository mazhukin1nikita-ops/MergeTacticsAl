package com.mergetacticsai.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mergetacticsai.util.FaultLog
import com.mergetacticsai.util.FaultSim
import com.mergetacticsai.util.NetworkProbe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Безопасный режим тестирования. Условия сети (нет сети, задержка, потеря пакетов) действуют только на запросы
 * самого приложения: ИИ Gemini и пробные запросы отсюда. Интернет телефона и соединение игры не меняются.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, botState: () -> String, onCrashTest: () -> Unit, crashBusy: Boolean) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }
    val entries by FaultLog.entries.collectAsState()

    var enabled by remember { mutableStateOf(FaultSim.enabled) }
    var offline by remember { mutableStateOf(FaultSim.offline) }
    var latency by remember { mutableStateOf(FaultSim.latencyMs.toFloat()) }
    var loss by remember { mutableStateOf(FaultSim.lossPercent.toFloat()) }
    var remaining by remember { mutableStateOf(FaultSim.remainingMs()) }
    var probeBusy by remember { mutableStateOf(false) }
    var probeText by remember { mutableStateOf("") }

    // Режим сам выключается через 10 минут: здесь только обновляем отсчёт.
    LaunchedEffect(Unit) {
        while (true) { delay(1000); enabled = FaultSim.enabled; remaining = FaultSim.remainingMs() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Безопасный режим тестирования", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }
        item {
            Text(
                "Проверьте, как бот переживает сбои. Тесты действуют только на запросы самого приложения (ИИ Gemini и пробные запросы). " +
                    "Интернет телефона и соединение игры не меняются. После перезапуска приложения всё выключено, а включённый режим сам гаснет через 10 минут.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Режим тестирования", style = MaterialTheme.typography.titleMedium)
                        Switch(checked = enabled, onCheckedChange = { enabled = it; FaultSim.setEnabled(it); remaining = FaultSim.remainingMs() })
                    }
                    Text(
                        if (enabled) "Включён: " + FaultSim.describe() + ". Автовыключение через ${(remaining + 59_999L) / 60_000L} мин."
                        else "Выключен. Включите, чтобы условия ниже начали действовать.",
                        color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Условия сети", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Нет интернета")
                        Switch(checked = offline, onCheckedChange = { offline = it; FaultSim.offline = it })
                    }
                    Text("Задержка ответа: ${latency.toInt()} мс")
                    Slider(value = latency, onValueChange = { latency = (it / 100f).toInt() * 100f; FaultSim.latencyMs = latency.toLong() }, valueRange = 0f..5000f)
                    Text("Потеря пакетов: ${loss.toInt()}%")
                    Slider(value = loss, onValueChange = { loss = (it / 5f).toInt() * 5f; FaultSim.lossPercent = loss.toInt() }, valueRange = 0f..100f)
                    Button(
                        onClick = {
                            probeBusy = true; probeText = "Выполняю пробные запросы…"
                            scope.launch {
                                val state = botState()
                                val r = NetworkProbe.run()
                                probeText = r.summary()
                                FaultLog.add("Тест сети: " + FaultSim.describe(), state, r.verdict(), r.failures.take(8).joinToString("\n"))
                                probeBusy = false
                            }
                        },
                        enabled = !probeBusy, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Проверить реакцию (пробные запросы)") }
                    if (probeText.isNotEmpty()) Text(probeText, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Бот при сбое запроса ИИ ждёт всё дольше (3, 6, 12, 24, 30 с), повторяет запрос и записывает сбой и восстановление в журнал ниже. " +
                            "Локальный режим без Gemini сеть не использует, поэтому на нём условия сети не заметны.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Авария и восстановление бота", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Резко останавливает цикл бота, запускает его заново и сверяет настройки до и после (сборка, правитель, свойства, задержки и остальное). " +
                            "Игра и само приложение не закрываются.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onCrashTest, enabled = !crashBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (crashBusy) "Идёт проверка…" else "Имитировать аварию бота")
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    FaultSim.reset(); enabled = false; offline = false; latency = 0f; loss = 0f; remaining = 0L
                    Toast.makeText(ctx, "Все симуляции выключены", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Выключить все симуляции") }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Журнал сбоев (${entries.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        clip.setText(AnnotatedString(FaultLog.text()))
                        Toast.makeText(ctx, "Журнал сбоев скопирован", Toast.LENGTH_SHORT).show()
                    },
                    enabled = entries.isNotEmpty(),
                ) { Text("Копировать") }
                OutlinedButton(onClick = { FaultLog.clear() }, enabled = entries.isNotEmpty()) { Text("Очистить") }
            }
        }
        if (entries.isEmpty()) {
            item { Text("Сбоев пока нет. Реальные сбои бота и результаты тестов появятся здесь.", style = MaterialTheme.typography.bodySmall) }
        }
        items(entries.take(60), key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(fmt.format(Date(e.at)) + " · " + e.reason, style = MaterialTheme.typography.titleSmall)
                    Text("Состояние бота: " + e.botState, style = MaterialTheme.typography.bodySmall)
                    Text("Результат: " + e.result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (e.details.isNotEmpty()) Text(e.details, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад") } }
    }
}
