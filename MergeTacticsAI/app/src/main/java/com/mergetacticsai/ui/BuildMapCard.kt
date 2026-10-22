package com.mergetacticsai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mergetacticsai.data.Troop
import com.mergetacticsai.map.CellMark
import com.mergetacticsai.map.CombatContext
import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.HexMap
import com.mergetacticsai.map.MapController
import com.mergetacticsai.map.PlacedUnit
import com.mergetacticsai.strategy.PositionContext

private val BUILD_DANGER = Color(0xFFD93025)
private val BUILD_MARK = Color(0xFFE65100)

/** Нижняя половина поля (ряды 4–7) в пикселях эталонного экрана 1080×2400. */
private const val OWN_Y0 = 1335f
private const val OWN_H = 450f

/**
 * Карта сборки — такое же поле, как на тактической карте, но только ваша сторона. Игрок ставит на неё войска из своей
 * сборки (экран «Сборка 7»). Бот выставляет каждое войско на его клетку; если поле заполнено, а нужный боец ждёт на
 * скамейке, бот продаёт лишнего с поля. Войско без клетки на карте ставится по расчёту позиционного ИИ.
 */
@Composable
fun BuildMapCard(
    c: MapController, troops: List<Troop>, buildIds: Set<String>,
    ruler: String = "", modifiers: Set<String> = emptySet(),
) {
    val buildTroops = remember(troops, buildIds) { troops.filter { it.id in buildIds } }
    val nameOf = remember(troops) { troops.associate { it.id to it.name } }
    val layout = c.buildLayout
    val sel = c.buildSelected
    val outside = layout.values.count { it.troopId !in buildIds }
    val danger = c.danger(c.layout)
    val tm = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Карта сборки", style = MaterialTheme.typography.titleMedium)
            Text(
                "Нажмите клетку и выберите войско из своей сборки. Бот поставит каждого на его клетку; если поле заполнено, " +
                    "а нужный боец ждёт на скамейке, продаст лишнего с поля. Без клетки на карте войско ставится по расчёту.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (buildIds.isEmpty()) {
                Text("Сборка на арену пуста: выберите до 7 войск в разделе «Сборка 7», затем вернитесь сюда.", style = MaterialTheme.typography.bodyMedium)
            }

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(HexMap.REGION_W / OWN_H)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { off ->
                            val s = size.width / HexMap.REGION_W
                            c.buildTap(HexMap.nearest(off.x / s + HexMap.REGION_X0, off.y / s + OWN_Y0))
                        })
                    },
            ) {
                drawBuildBoard(tm, cs, layout, sel, danger, c.marks, nameOf, buildIds)
            }

            // Состав сборки и где кто стоит.
            for (t in buildTroops) {
                val cells = layout.filter { it.value.troopId == t.id }.keys.sortedWith(compareBy({ it.row }, { it.col }))
                Text(
                    "${t.name}: " + if (cells.isEmpty()) "клетка не задана — по расчёту" else cells.joinToString { HexMap.label(it) },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (outside > 0) {
                Text("На карте войск не из сборки: $outside — бот их игнорирует.", style = MaterialTheme.typography.bodySmall)
            }

            if (sel != null) {
                val u = layout[sel]
                Text("Клетка ${HexMap.label(sel)}" + (u?.let { " · " + (nameOf[it.troopId] ?: it.troopId) } ?: ""), style = MaterialTheme.typography.titleSmall)
                var open by remember(sel) { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(onClick = { open = true }, enabled = buildTroops.isNotEmpty()) {
                            Text(if (u == null) "Поставить войско" else "Заменить")
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            buildTroops.forEach { t ->
                                DropdownMenuItem(text = { Text("${t.cost} · ${t.name}") }, onClick = { c.setBuildTroop(sel, t.id); open = false })
                            }
                        }
                    }
                    if (u != null) TextButton(onClick = { c.removeBuildUnit(sel) }) { Text("Убрать") }
                }
            } else {
                Text("Клетка не выбрана.", style = MaterialTheme.typography.bodySmall)
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = buildTroops.isNotEmpty(),
                    onClick = {
                        val ctx = PositionContext(
                            enemy = c.layout.filterKeys { !HexMap.isOwn(it) },
                            radii = c.radii, speeds = c.speeds, stats = c.stats, marks = c.marks,
                            combat = CombatContext(ruler, modifiers, troops.associate { it.id to it.trait }, c.starFactor),
                        )
                        c.autoBuild(buildTroops.map { it.id }, ctx)
                    },
                ) { Text("Расставить недостающих") }
                OutlinedButton(onClick = { c.clearBuild() }, enabled = layout.isNotEmpty()) { Text("Очистить") }
            }
            Text(
                "«Расставить недостающих» учитывает радиус атаки, пометки клеток и противника с тактической карты; уже стоящих не двигает. " +
                    "Красные клетки — до них достают вражеские войска с тактической карты (если у них задан радиус).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun DrawScope.labelAt(tm: TextMeasurer, text: String, center: Offset, style: TextStyle) {
    val r = tm.measure(text, style)
    drawText(r, topLeft = Offset(center.x - r.size.width / 2f, center.y - r.size.height / 2f))
}

/** Рисует ряды 4–7: шестиугольники, клетки опасности и пометки, войска сборки (серым — войска не из сборки). */
private fun DrawScope.drawBuildBoard(
    tm: TextMeasurer, cs: ColorScheme, layout: Map<HexCell, PlacedUnit>, selected: HexCell?,
    danger: Map<HexCell, Int>, marks: Map<HexCell, CellMark>, names: Map<String, String>, buildIds: Set<String>,
) {
    val sc = size.width / HexMap.REGION_W
    fun px(x: Float, y: Float) = Offset((x - HexMap.REGION_X0) * sc, (y - OWN_Y0) * sc)
    val hw = HexMap.HALF_W * 0.95f * sc
    val tip = HexMap.TIP * 0.95f * sc
    val side = HexMap.SIDE * 0.95f * sc
    val unitR = 30f * sc
    val small = TextStyle(color = cs.onSurface.copy(alpha = 0.75f), fontSize = 7.sp)
    for (cell in HexMap.ownCells) {
        val (cx0, cy0) = HexMap.centerPx(cell)
        val ctr = px(cx0, cy0)
        val path = Path().apply {
            moveTo(ctr.x, ctr.y - tip)
            lineTo(ctr.x + hw, ctr.y - side)
            lineTo(ctr.x + hw, ctr.y + side)
            lineTo(ctr.x, ctr.y + tip)
            lineTo(ctr.x - hw, ctr.y + side)
            lineTo(ctr.x - hw, ctr.y - side)
            close()
        }
        drawPath(path, cs.primary.copy(alpha = 0.14f))
        val dc = danger[cell]
        if (dc != null) drawPath(path, BUILD_DANGER.copy(alpha = (0.18f + 0.14f * dc).coerceAtMost(0.7f)))
        val mark = marks[cell]
        if (mark != null) {
            drawPath(path, BUILD_MARK.copy(alpha = if (mark.dangerous) 0.5f else 0.3f))
            labelAt(tm, mark.title.first().toString(), Offset(ctr.x, ctr.y - 46f * sc), TextStyle(color = cs.onSurface, fontSize = 8.sp, fontWeight = FontWeight.Bold))
        }
        drawPath(path, cs.outline.copy(alpha = 0.7f), style = Stroke(width = 1.2.dp.toPx()))
        if (cell == selected) drawPath(path, cs.onSurface, style = Stroke(width = 3.dp.toPx()))
        labelAt(tm, HexMap.label(cell), Offset(ctr.x, ctr.y + 46f * sc), small)
        val u = layout[cell]
        if (u != null) {
            val inBuild = u.troopId in buildIds
            drawCircle(if (inBuild) cs.primary else cs.outline, radius = unitR, center = ctr)
            labelAt(
                tm, (names[u.troopId] ?: "?").take(4), ctr,
                TextStyle(color = if (inBuild) cs.onPrimary else cs.surface, fontSize = 8.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}
