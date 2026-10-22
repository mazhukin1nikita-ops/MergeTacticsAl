package com.mergetacticsai.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.mergetacticsai.data.Troop

/**
 * Обучение: бот сам сохраняет незнакомые карточки из магазина во время игры (до 12).
 * Вы выбираете, какое это войско, — дальше он узнаёт карточку без интернета.
 */
@Composable
fun LearnScreen(
    pending: List<Pair<Long, Bitmap>>, troops: List<Troop>, learnedCount: Int,
    onAssign: (Long, String) -> Unit, onDiscard: (Long) -> Unit, onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Обучение карточек", style = MaterialTheme.typography.titleLarge)
        }
        Text("Изучено карточек: $learnedCount. Запустите бота в игре — незнакомые карточки появятся здесь.",
            style = MaterialTheme.typography.bodySmall)
        if (pending.isEmpty()) Text("Новых карточек пока нет.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(pending) { (sig, bmp) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(90.dp))
                    Spacer(Modifier.width(12.dp))
                    var open by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { open = true }) { Text("Кто это?") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            troops.forEach { t ->
                                DropdownMenuItem(text = { Text("${t.cost} · ${t.name}") }, onClick = { onAssign(sig, t.id); open = false })
                            }
                        }
                    }
                    TextButton(onClick = { onDiscard(sig) }) { Text("Убрать") }
                }
            }
        }
    }
}
