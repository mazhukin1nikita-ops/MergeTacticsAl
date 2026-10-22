package com.mergetacticsai.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mergetacticsai.data.CatalogItem
import com.mergetacticsai.data.Troop

/** Портреты войск лежат в assets/portraits/<id>.webp. Читаются один раз и хранятся в памяти. */
object PortraitStore {
    private val cache = HashMap<String, ImageBitmap?>()

    @Synchronized
    fun get(context: Context, id: String): ImageBitmap? {
        if (cache.containsKey(id)) return cache[id]
        val bmp = runCatching { context.assets.open("portraits/$id.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull()?.asImageBitmap()
        cache[id] = bmp
        return bmp
    }
}

/** Цвет свойства, как значки на карточках в игре. */
fun traitColor(trait: String): Color = when (trait) {
    "clan" -> Color(0xFF4FC3F7)
    "goblin" -> Color(0xFF8BC34A)
    "noble" -> Color(0xFFFFC107)
    "undead" -> Color(0xFF26C6A6)
    "forest" -> Color(0xFF43A047)
    "boss" -> Color(0xFFEC4A9B)
    else -> Color(0xFF9E9E9E)
}

/** Портрет войска; если картинки нет, показывается цветная заглушка с первой буквой названия. */
@Composable
fun TroopPortrait(id: String, name: String, modifier: Modifier = Modifier, trait: String = "") {
    val context = LocalContext.current
    val img = remember(id) { PortraitStore.get(context, id) }
    if (img != null) {
        Image(bitmap = img, contentDescription = name, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(traitColor(trait).copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Text(name.take(1), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/** Золотая капля с ценой, как в правом верхнем углу карточки. */
@Composable
private fun CostBadge(cost: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(26.dp).clip(CircleShape).background(Color(0xFFE8A93C)).border(1.5.dp, Color(0xFF9A6A12), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$cost", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF3A2200))
    }
}

@Composable
private fun Chip(text: String, on: Boolean, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        modifier = Modifier.clip(shape)
            .background(if (on) color.copy(alpha = 0.35f) else Color.Transparent)
            .border(1.dp, if (on) color else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun TroopTile(t: Troop, traitName: String, selected: Boolean, dim: Boolean, onClick: () -> Unit) {
    val accent = traitColor(t.trait)
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().alpha(if (dim) 0.45f else 1f).clip(shape)
            .background(if (selected) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            TroopPortrait(t.id, t.name, Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(10.dp)), t.trait)
            CostBadge(t.cost, Modifier.align(Alignment.TopEnd).padding(3.dp))
            if (selected) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Text(
            t.name, modifier = Modifier.padding(top = 4.dp), maxLines = 2, minLines = 2, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
        )
        Text(traitName, color = accent, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * Выбор сборки на арену с портретами: сверху семь слотов выбранных войск, ниже фильтр по свойствам
 * и сетка карточек с ценой в углу. Нажатие по слоту убирает войско.
 */
@Composable
fun BuildPickScreen(
    troops: List<Troop>,
    traits: List<CatalogItem>,
    selected: Set<String>,
    max: Int,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val traitName = remember(traits) { traits.associate { it.id to it.name } }
    val byId = remember(troops) { troops.associateBy { it.id } }
    var filter by remember { mutableStateOf<String?>(null) }
    val shown = if (filter == null) troops else troops.filter { it.trait == filter }
    val chipTraits = troops.map { it.trait }.filter { it.isNotEmpty() }.distinct()
    val full = selected.size >= max
    val chosen = selected.toList()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Сборка на арену", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "Отметьте до $max войск — только их бот будет выставлять на арену. Остальные остаются на скамейке для круга " +
                "(покупка, слияние, продажа). Пусто — бот решает сам.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(max) { i ->
                val id = chosen.getOrNull(i)
                val t = id?.let { byId[it] }
                val slot = RoundedCornerShape(8.dp)
                Box(
                    Modifier.weight(1f).aspectRatio(0.8f).clip(slot)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, if (t != null) traitColor(t.trait) else MaterialTheme.colorScheme.outline, slot)
                        .clickable(enabled = t != null) { if (id != null) onToggle(id) },
                ) {
                    if (t != null) TroopPortrait(t.id, t.name, Modifier.fillMaxSize(), t.trait)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Выбрано: ${selected.size}/$max", style = MaterialTheme.typography.titleSmall)
            if (selected.isNotEmpty()) TextButton(onClick = onClear) { Text("Сбросить выбор") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Chip("Все", filter == null, MaterialTheme.colorScheme.primary) { filter = null } }
            items(chipTraits) { tr -> Chip(traitName[tr] ?: tr, filter == tr, traitColor(tr)) { filter = if (filter == tr) null else tr } }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.id }) { t ->
                val on = t.id in selected
                TroopTile(t, traitName[t.trait] ?: "без свойства", on, full && !on) { onToggle(t.id) }
            }
        }
    }
}
