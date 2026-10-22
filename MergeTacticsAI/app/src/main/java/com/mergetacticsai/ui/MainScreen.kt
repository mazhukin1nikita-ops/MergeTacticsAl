package com.mergetacticsai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mergetacticsai.automation.AgentState
import com.mergetacticsai.automation.LocalLoop
import com.mergetacticsai.strategy.StrategyConfig
import com.mergetacticsai.strategy.StrategyMode

fun label(s: AgentState) = when (s) {
    AgentState.IDLE -> "Ожидание"
    AgentState.LISTENING -> "Слушаю"
    AgentState.STARTING_GAME, AgentState.WAITING_FOR_GAME -> "Запускаю игру"
    AgentState.DETECTING_MODE, AgentState.ANALYZING -> "Анализирую"
    AgentState.PLANNING -> "Планирую"
    AgentState.EXECUTING -> "Действую"
    AgentState.WAITING_FOR_BATTLE, AgentState.OBSERVING_BATTLE -> "Бой"
    AgentState.ROUND_FINISHED, AgentState.MATCH_FINISHED -> "Раунд завершён"
    AgentState.PAUSED -> "Пауза"
    AgentState.ERROR -> "Ошибка"
    AgentState.STOPPED -> "Остановлено"
}

private val RUNNING = setOf(
    AgentState.DETECTING_MODE, AgentState.ANALYZING, AgentState.PLANNING, AgentState.EXECUTING,
    AgentState.WAITING_FOR_BATTLE, AgentState.OBSERVING_BATTLE, AgentState.ROUND_FINISHED,
)

private fun stateColor(s: AgentState): Color = when {
    s in RUNNING -> Color(0xFF2E9E57)
    s == AgentState.PAUSED -> Color(0xFFE0A100)
    s == AgentState.ERROR -> Color(0xFFD93025)
    s == AgentState.STARTING_GAME || s == AgentState.WAITING_FOR_GAME || s == AgentState.LISTENING -> Color(0xFF6D8CFF)
    else -> Color(0xFF9A94A3)
}

/** Вкладки нижней навигации. Остальные экраны (свойства, модификаторы, сборка 7, обучение) открываются поверх. */
val TABS = listOf(
    Triple("main", "🏠", "Главная"),
    Triple("builds", "🧩", "Сборки"),
    Triple("troops", "⚔️", "Войска"),
    Triple("rulers", "👑", "Правители"),
    Triple("settings", "⚙️", "Настройки"),
)

@Composable
fun AppBottomBar(current: String, onSelect: (String) -> Unit) {
    NavigationBar {
        for ((id, icon, name) in TABS) {
            NavigationBarItem(
                selected = current == id,
                onClick = { onSelect(id) },
                icon = { Text(icon) },
                label = { Text(name, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun InfoRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Tile(emoji: String, text: String, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, style = MaterialTheme.typography.titleLarge)
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Главная: запуск, остановка, статус, режим, быстрый доступ. Редкие настройки — на вкладке «Настройки». */
@Composable
fun HomeScreen(
    state: AgentState, log: List<String>, config: StrategyConfig, useGemini: Boolean,
    captureOn: Boolean, accessibilityOn: Boolean, info: LocalLoop.Info, summary: List<String>,
    onStartAi: () -> Unit, onOpenGame: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit,
    onMode: (StrategyMode) -> Unit, onGrantCapture: () -> Unit, onOpenAccessibility: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val running = state in RUNNING
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Merge Tactics AI", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary) }

        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(stateColor(state))
                        Text(label(state), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Button(
                        onClick = onStartAi,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth().height(68.dp),
                        shape = RoundedCornerShape(22.dp),
                    ) { Text(if (running) "Бот работает" else "▶  Запустить бота", style = MaterialTheme.typography.titleLarge) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state == AgentState.PAUSED) {
                            Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("⏵ Продолжить") }
                        } else {
                            OutlinedButton(onClick = onPause, enabled = running, modifier = Modifier.weight(1f)) { Text("⏸ Пауза") }
                        }
                        OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("⏹ Стоп") }
                    }
                    TextButton(onClick = onOpenGame, modifier = Modifier.fillMaxWidth()) { Text("Открыть Clash Royale и начать") }
                }
            }
        }

        if (!captureOn || !accessibilityOn) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Нужны разрешения для работы бота", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!captureOn) OutlinedButton(onClick = onGrantCapture) { Text("Захват экрана") }
                            if (!accessibilityOn) OutlinedButton(onClick = onOpenAccessibility) { Text("Доступность") }
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Режим", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = config.mode == StrategyMode.MERGE_CHEAP, onClick = { onMode(StrategyMode.MERGE_CHEAP) }, label = { Text("🔀 Объединение") })
                        FilterChip(selected = config.mode == StrategyMode.CUSTOM_BUILD, onClick = { onMode(StrategyMode.CUSTOM_BUILD) }, label = { Text("🎯 Сборка") })
                    }
                    InfoRow("ИИ", if (useGemini) "Gemini" else "Локальный")
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Распознавание экрана", style = MaterialTheme.typography.titleMedium)
                    InfoRow("Захват экрана", if (captureOn) "включён ✓" else "выключен")
                    InfoRow("Служба доступности", if (accessibilityOn) "включена ✓" else "выключена")
                    val game = when {
                        !running -> "бот не запущен"
                        info.inMatch -> "матч распознан ✓"
                        else -> "экран игры не найден"
                    }
                    InfoRow("Игра", game)
                    if (running && info.inMatch) InfoRow("Фаза", if (info.deploy) "расстановка" else "бой / ожидание")
                    InfoRow("Войска на скамейке", info.bench.toString())
                    InfoRow("Куплено за матч", info.bought.toString())
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("🧩", "Сборки", Modifier.weight(1f)) { onOpen("builds") }
                Tile("⚔️", "Войска", Modifier.weight(1f)) { onOpen("troops") }
                Tile("👑", "Правители", Modifier.weight(1f)) { onOpen("rulers") }
                Tile("⚙️", "Настройки", Modifier.weight(1f)) { onOpen("settings") }
            }
        }

        item { HubCard("⏱️ Скорость и задержки", "Отдельно настройте задержку покупки, перестановки, продажи и других действий") { onOpen("timing") } }

        item { HubCard("🗺️ Тактическая карта", "Поля, радиус атаки, скриншот, расстановка") { onOpen("map") } }

        item { HubCard("🎲 Вероятности", "Шанс увидеть войско, набрать копии и собрать звёзды") { onOpen("odds") } }

        item { HubCard("🕵️ Противники", "Видимые составы, дефицит карт, угрозы и контрмеры (скрытое — только вероятности)") { onOpen("adapt") } }

        item { HubCard("📒 Журнал решений", "Что бот сделал, почему и чем кончилось") { onOpen("journal") } }

        item { HubCard("🎥 Запись экрана", "Кадры и хронология действий бота для разбора") { onOpen("record") } }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Текущая настройка", style = MaterialTheme.typography.titleMedium)
                    for (line in summary) Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Журнал", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.OutlinedButton(onClick = {
                    clip.setText(androidx.compose.ui.text.AnnotatedString(com.mergetacticsai.util.Logger.text()))
                    android.widget.Toast.makeText(ctx, "Лог скопирован", android.widget.Toast.LENGTH_SHORT).show()
                }, enabled = log.isNotEmpty()) { Text("Копировать") }
            }
        }
        if (log.isEmpty()) item { Text("Пока пусто", style = MaterialTheme.typography.bodySmall) }
        items(log.take(30)) { androidx.compose.foundation.text.selection.SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun HubCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Вкладка «Сборки»: сборка на арену, свойства, модификаторы. */
@Composable
fun BuildsScreen(
    config: StrategyConfig, placeCount: Int, traitsText: String, modsCount: Int,
    onBuild: (String) -> Unit, onOpen: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Сборки", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }
        item { HubCard("Сборка на арену (до 7 карт)", "Выбрано: $placeCount/7") { onOpen("build") } }
        item { HubCard("Свойства", traitsText) { onOpen("traits") } }
        item { HubCard("Модификаторы", "Отмечено: $modsCount") { onOpen("mods") } }
        if (config.mode == StrategyMode.CUSTOM_BUILD) {
            item {
                var build by remember(config.targets) { mutableStateOf(config.targets.joinToString(", ")) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Состав для режима «Сборка»", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            build, { build = it }, label = { Text("Воины: гоблин, лучница…") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            trailingIcon = { TextButton(onClick = { onBuild(build) }) { Text("OK") } },
                        )
                    }
                }
            }
        }
    }
}

/** Вкладка «Настройки»: ИИ и ключ Gemini, разрешения, голос и текстовые команды, обучение карточек. */
@Composable
fun SettingsScreen(
    useGemini: Boolean, apiKeySet: Boolean, maxPerHour: Int,
    captureOn: Boolean, accessibilityOn: Boolean, version: String,
    onSetGemini: (Boolean) -> Unit, onSaveAi: (String, Int) -> Unit, onClearKey: () -> Unit,
    onGrantCapture: () -> Unit, onOpenAccessibility: () -> Unit,
    onMic: () -> Unit, onText: (String) -> Unit, onOpen: (String) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var limit by remember(maxPerHour) { mutableStateOf(maxPerHour.toString()) }
    var text by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Настройки", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Режим ИИ", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = useGemini, onCheckedChange = onSetGemini)
                        Text(if (useGemini) "Gemini (нужны ключ и интернет)" else "Локальный (без ключа и интернета)", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (useGemini && !apiKeySet) {
                        Text("Ключ не задан — Gemini не запустится.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gemini API", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        key, { key = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(if (apiKeySet) "API-ключ сохранён (введите новый, чтобы заменить)" else "API-ключ Gemini") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        limit, { limit = it.filter { c -> c.isDigit() } }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("Запросов в час") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSaveAi(key, limit.toIntOrNull() ?: maxPerHour); key = "" }) { Text("Сохранить") }
                        if (apiKeySet) OutlinedButton(onClick = onClearKey) { Text("Удалить ключ") }
                    }
                    Text("Ключ хранится только на этом устройстве и в интерфейсе не показывается.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Разрешения", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onGrantCapture) { Text(if (captureOn) "Захват ✓" else "Захват экрана") }
                        OutlinedButton(onClick = onOpenAccessibility) { Text(if (accessibilityOn) "Доступность ✓" else "Доступность") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Голос и команды", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = onMic) { Text("🎤 Голосовая команда") }
                    OutlinedTextField(
                        text, { text = it }, label = { Text("Команда текстом") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        trailingIcon = { TextButton(onClick = { onText(text); text = "" }) { Text("OK") } },
                    )
                }
            }
        }

        item { HubCard("Обучение карточек", "Назвать карточки, которые бот сохранил во время игры") { onOpen("learn") } }

        item {
            Text("Версия $version", style = MaterialTheme.typography.bodySmall)
            Text("Автоматизация может противоречить правилам игры и привести к ограничениям аккаунта.", style = MaterialTheme.typography.bodySmall)
        }
    }
}


/** Отдельный экран управления скоростью автоматических действий. Значения сохраняются на устройстве. */
@Composable
fun TimingSettingsScreen(
    buy: Long, place: Long, sell: Long, merge: Long, verify: Long, loop: Long,
    onSave: (Long, Long, Long, Long, Long, Long) -> Unit,
    onBack: () -> Unit,
) {
    var b by remember(buy) { mutableStateOf(buy.toFloat()) }
    var p by remember(place) { mutableStateOf(place.toFloat()) }
    var s by remember(sell) { mutableStateOf(sell.toFloat()) }
    var m by remember(merge) { mutableStateOf(merge.toFloat()) }
    var v by remember(verify) { mutableStateOf(verify.toFloat()) }
    var l by remember(loop) { mutableStateOf(loop.toFloat()) }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Скорость и задержки", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }
        item { Text("Увеличьте задержку, если игра не успевает реагировать. Уменьшите, если действия стабильно подтверждаются. Изменения сохраняются и применяются без перезапуска.", style = MaterialTheme.typography.bodyMedium) }
        item { TimingSlider("Покупка карты", b, 0f..2500f, { b = it }) }
        item { TimingSlider("Выставление / перестановка", p, 0f..2500f, { p = it }) }
        item { TimingSlider("Продажа юнита", s, 0f..2500f, { s = it }) }
        item { TimingSlider("Слияние юнитов", m, 0f..2500f, { m = it }) }
        item { TimingSlider("Ожидание и проверка результата", v, 0f..2500f, { v = it }) }
        item { TimingSlider("Пауза между циклами распознавания", l, 25f..1000f, { l = it }) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(b.toLong(), p.toLong(), s.toLong(), m.toLong(), v.toLong(), l.toLong()) }, modifier = Modifier.weight(1f)) { Text("Сохранить") }
                OutlinedButton(onClick = { b=350f; p=300f; s=450f; m=350f; v=300f; l=100f }) { Text("По умолчанию") }
            }
        }
        item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад") } }
    }
}

@Composable
private fun TimingSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text("${value.toInt()} мс · ${"%.2f".format(value / 1000f)} с", style = MaterialTheme.typography.bodySmall)
            }
            Slider(value = value, onValueChange = onChange, valueRange = range)
        }
    }
}
