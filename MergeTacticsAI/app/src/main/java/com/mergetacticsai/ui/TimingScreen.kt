package com.mergetacticsai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Названия и границы шести задержек (мс): покупка, выставление, продажа, слияние, ожидание проверки покупки, пауза цикла. */
private class Row6(val title: String, val hint: String, val min: Float, val max: Float, val def: Long)

private val ROWS = listOf(
    Row6("После покупки", "Пауза после нажатия на карточку. Меньше — быстрее покупки, но игра может не успеть обновить магазин.", 0f, 1500f, 100L),
    Row6("После выставления на поле", "Пауза после перетаскивания войска на поле.", 0f, 1500f, 150L),
    Row6("После продажи", "Пауза после того, как войско утянули на зону продажи.", 0f, 1500f, 200L),
    Row6("После слияния", "Пауза после того, как один юнит перетащили на другого.", 0f, 1500f, 300L),
    Row6("Ожидание проверки покупки", "Сколько бот ждёт, пока картинка карточки изменится. Если покупка не подтверждается — увеличьте.", 200f, 2500f, 600L),
    Row6("Пауза цикла", "Как часто бот смотрит на экран. Меньше — быстрее реакция, но больше нагрузка на телефон.", 25f, 1000f, 80L),
)

/** Настройка скорости бота: по одному ползунку на каждое действие. Значения сохраняются при каждом изменении. */
@Composable
fun TimingScreen(initial: List<Long>, onSave: (List<Long>) -> Unit, onBack: () -> Unit) {
    var values by remember { mutableStateOf(ROWS.indices.map { initial.getOrNull(it) ?: ROWS[it].def }) }
    fun set(i: Int, v: Long) {
        values = values.mapIndexed { k, old -> if (k == i) v else old }
        onSave(values)
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Скорость и задержки", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }
        item { Text("Чем меньше число, тем быстрее бот. Если действия начали срываться (не покупает, не ставит, не продаёт), увеличьте нужную паузу. Изменения действуют сразу.", style = MaterialTheme.typography.bodyMedium) }
        ROWS.forEachIndexed { i, r ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(r.title, style = MaterialTheme.typography.titleMedium)
                            Text("${values[i]} мс", color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = values[i].toFloat().coerceIn(r.min, r.max),
                            onValueChange = { set(i, (it / 10f).toInt() * 10L) },
                            valueRange = r.min..r.max,
                        )
                        Text(r.hint, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { values = ROWS.map { it.def }; onSave(values) }, modifier = Modifier.weight(1f)) { Text("Сбросить") }
                Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Готово") }
            }
        }
    }
}
