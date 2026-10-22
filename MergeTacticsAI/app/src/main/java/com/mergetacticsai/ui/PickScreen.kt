package com.mergetacticsai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mergetacticsai.data.CatalogItem

/** Универсальный экран выбора: один элемент (правитель) или несколько (свойства, модификаторы). */
@Composable
fun PickScreen(
    title: String,
    note: String,
    items: List<CatalogItem>,
    selected: Set<String>,
    multi: Boolean,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    header: @Composable () -> Unit = {},
    showBack: Boolean = true,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) TextButton(onClick = onBack) { Text("← Назад") }
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(note, style = MaterialTheme.typography.bodySmall)
        header()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(items) { it ->
                Row(Modifier.fillMaxWidth().clickable { onToggle(it.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top) {
                    if (multi) Checkbox(checked = it.id in selected, onCheckedChange = { _ -> onToggle(it.id) })
                    else RadioButton(selected = it.id in selected, onClick = { onToggle(it.id) })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(it.name, style = MaterialTheme.typography.titleSmall)
                        Text(it.desc, style = MaterialTheme.typography.bodySmall)
                        if (!it.verified) Text("не сверено с текущей версией игры", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
