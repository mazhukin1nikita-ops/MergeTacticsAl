package com.mergetacticsai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mergetacticsai.data.Troop
import com.mergetacticsai.map.CellMark
import com.mergetacticsai.map.HexCell
import com.mergetacticsai.map.HexMap
import com.mergetacticsai.map.MapController
import com.mergetacticsai.map.MapView
import com.mergetacticsai.map.PlacedUnit

private val ZONE_COLOR = Color(0xFF2196F3)
private val DANGER_COLOR = Color(0xFFD93025)
private val MARK_DANGER_COLOR = Color(0xFFE65100)
private val MARK_SPECIAL_COLOR = Color(0xFF8E24AA)
private val TARGET_COLOR = Color(0xFFFFB300)

private fun DrawScope.centeredText(tm: TextMeasurer, text: String, center: Offset, style: TextStyle) {
    val r = tm.measure(text, style)
    drawText(r, topLeft = Offset(center.x - r.size.width / 2f, center.y - r.size.height / 2f))
}

/** Тактическая карта: поле 8×6 (вверху противник, внизу вы), скриншот из галереи, радиус атаки, цели и опасные клетки. */
@Composable
fun MapScreen(
    c: MapController, troops: List<Troop>, onPickScreenshot: () -> Unit, onBack: () -> Unit,
    ruler: String = "", modifiers: Set<String> = emptySet(), names: (String) -> String = { it },
    buildIds: Set<String> = emptySet(),
) {
    val rec = remember(c.layout, c.radii) { c.recommendation() }
    val shown = if (c.view == MapView.RECOMMENDED) rec.layout else c.layout
    val zone = c.zone(shown)
    val targets = c.targets(shown, zone)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Назад") }
                Text("Тактическая карта", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Скриншот из галереи", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onPickScreenshot) { Text("Выбрать снимок") }
                        if (c.screenshot != null) OutlinedButton(onClick = { c.clearScreenshot() }) { Text("Убрать снимок") }
                    }
                    Text(
                        c.info ?: "Приложение найдёт клетки с юнитами. Названия войск и звёзды задайте вручную — нажмите на клетку.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = c.view == MapView.ORIGINAL, onClick = { c.view = MapView.ORIGINAL }, label = { Text("Исходная") })
                    FilterChip(selected = c.view == MapView.RECOMMENDED, onClick = { c.view = MapView.RECOMMENDED }, label = { Text("Рекомендованная") })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = c.showRange, onClick = { c.showRange = !c.showRange }, label = { Text("Радиус и цели") })
                    FilterChip(selected = c.showDanger, onClick = { c.showDanger = !c.showDanger }, label = { Text("Опасные") })
                    FilterChip(selected = c.showSpecial, onClick = { c.showSpecial = !c.showSpecial }, label = { Text("Особые") })
                }
            }
        }

        item { BoardCanvas(c, shown, zone, targets) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Вверху — противник (ряды A–D), внизу — вы (E–H). Цифра — колонка слева направо.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendItem(ZONE_COLOR, "радиус")
                    LegendItem(TARGET_COLOR, "цель")
                    LegendItem(DANGER_COLOR, "достают враги")
                    LegendItem(MARK_DANGER_COLOR, "опасная")
                    LegendItem(MARK_SPECIAL_COLOR, "особая")
                }
            }
        }

        item { ContactPanel(c, shown) }

        item { CombatPanel(c, troops, shown, ruler, modifiers, names) }

        item { SelectionPanel(c, troops, shown, zone, targets) }

        item { BuildMapCard(c, troops, buildIds, ruler, modifiers) }

        if (c.view == MapView.RECOMMENDED) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Рекомендованная расстановка", style = MaterialTheme.typography.titleMedium)
                        if (rec.moves.isEmpty()) {
                            Text("Перестановок нет: расстановка уже подходит под правило или радиус атаки не задан у ваших войск.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            for (m in rec.moves) Text(c.moveText(m), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (rec.skippedUnknown > 0) {
                            Text("Остались на месте (радиус не задан): ${rec.skippedUnknown}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Это простое правило: ближний бой вперёд, дальний назад. Позиционный ИИ (этап 3) заменит его расчётом.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { c.clearSide(false) }, enabled = c.editable()) { Text("Очистить противника") }
                OutlinedButton(onClick = { c.clearSide(true) }, enabled = c.editable()) { Text("Очистить своё") }
                OutlinedButton(onClick = { c.clearMarks() }, enabled = c.editable()) { Text("Сбросить пометки") }
            }
        }

        item {
            Text(
                "Радиус атаки задаётся вручную: проверенных значений в базе пока нет. Опасные клетки считаются по начальным позициям " +
                    "и только для вражеских войск с заданным радиусом (сейчас без радиуса: ${c.enemiesWithoutRadius(shown)}). " +
                    "Красные полоски противника на скриншоте распознаются приблизительно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun BoardCanvas(c: MapController, shown: Map<HexCell, PlacedUnit>, zone: Set<HexCell>, targets: Set<HexCell>) {
    val tm = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    val danger: Map<HexCell, Int> = if (c.showDanger) c.danger(shown) else emptyMap()
    val shot = c.screenshot
    val selected = c.selected
    val marks = c.marks
    val showRange = c.showRange
    val showDanger = c.showDanger
    val showSpecial = c.showSpecial
    val names: Map<HexCell, String> = shown.mapValues { (_, u) -> c.nameFor(u) }

    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(HexMap.REGION_W / HexMap.REGION_H)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceContainerHighest)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { off ->
                    val s = size.width / HexMap.REGION_W
                    c.tap(HexMap.nearest(off.x / s + HexMap.REGION_X0, off.y / s + HexMap.REGION_Y0))
                })
            },
    ) {
        val sc = size.width / HexMap.REGION_W
        fun px(x: Float, y: Float) = Offset((x - HexMap.REGION_X0) * sc, (y - HexMap.REGION_Y0) * sc)

        if (shot != null) {
            val bw = shot.width
            val bh = shot.height
            val sx = (HexMap.REGION_X0 / HexMap.REF_W * bw).toInt().coerceIn(0, bw - 1)
            val sy = (HexMap.REGION_Y0 / HexMap.REF_H * bh).toInt().coerceIn(0, bh - 1)
            val sw = (HexMap.REGION_W / HexMap.REF_W * bw).toInt().coerceIn(1, bw - sx)
            val sh = (HexMap.REGION_H / HexMap.REF_H * bh).toInt().coerceIn(1, bh - sy)
            drawImage(
                shot,
                srcOffset = IntOffset(sx, sy),
                srcSize = IntSize(sw, sh),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            )
        }

        val hw = HexMap.HALF_W * 0.95f * sc
        val tip = HexMap.TIP * 0.95f * sc
        val side = HexMap.SIDE * 0.95f * sc
        val unitR = 30f * sc
        val smallStyle = TextStyle(color = cs.onSurface.copy(alpha = 0.75f), fontSize = 7.sp)

        for (cell in HexMap.all) {
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
            val isOwn = HexMap.isOwn(cell)
            drawPath(path, (if (isOwn) cs.primary else cs.error).copy(alpha = if (shot != null) 0.08f else 0.16f))

            val dc = danger[cell]
            if (showDanger && dc != null) drawPath(path, DANGER_COLOR.copy(alpha = (0.18f + 0.14f * dc).coerceAtMost(0.7f)))
            val mark = marks[cell]
            if (mark != null && ((mark.dangerous && showDanger) || (!mark.dangerous && showSpecial))) {
                drawPath(path, (if (mark.dangerous) MARK_DANGER_COLOR else MARK_SPECIAL_COLOR).copy(alpha = 0.5f))
                centeredText(
                    tm, mark.title.first().toString(), Offset(ctr.x, ctr.y - 46f * sc),
                    TextStyle(color = cs.onSurface, fontSize = 8.sp, fontWeight = FontWeight.Bold),
                )
            }
            if (showRange && cell in zone) drawPath(path, ZONE_COLOR.copy(alpha = 0.40f))
            drawPath(path, cs.outline.copy(alpha = 0.7f), style = Stroke(width = 1.2.dp.toPx()))
            if (cell == selected) drawPath(path, cs.onSurface, style = Stroke(width = 3.dp.toPx()))

            centeredText(tm, HexMap.label(cell), Offset(ctr.x, ctr.y + 46f * sc), smallStyle)

            val u = shown[cell]
            if (u != null) {
                val fill = when {
                    u.isUnknown -> cs.outline
                    isOwn -> cs.primary
                    else -> cs.error
                }
                val onFill = when {
                    u.isUnknown -> cs.surface
                    isOwn -> cs.onPrimary
                    else -> cs.onError
                }
                drawCircle(fill, radius = unitR, center = ctr)
                val nm = if (u.isUnknown) "?" else (names[cell] ?: "?").take(4)
                centeredText(
                    tm, nm, Offset(ctr.x, ctr.y - 2.5.dp.toPx()),
                    TextStyle(color = onFill, fontSize = 8.sp, fontWeight = FontWeight.Bold),
                )
                if (!u.isUnknown) {
                    centeredText(tm, "★".repeat(u.stars), Offset(ctr.x, ctr.y + 5.dp.toPx()), TextStyle(color = onFill, fontSize = 6.sp))
                }
                if (showRange && cell in targets) {
                    drawCircle(TARGET_COLOR, radius = unitR + 4.dp.toPx(), center = ctr, style = Stroke(width = 3.dp.toPx()))
                }
            }
        }

        val midY = 1045f + 93.3f * 3.5f
        drawLine(
            cs.onSurface.copy(alpha = 0.55f),
            px(HexMap.REGION_X0, midY), px(HexMap.REGION_X0 + HexMap.REGION_W, midY),
            strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
        )
    }
}

@Composable
private fun ContactPanel(c: MapController, shown: Map<HexCell, PlacedUnit>) {
    val rep = remember(shown, c.radii, c.speeds) { c.contact(shown) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Первый контакт", style = MaterialTheme.typography.titleMedium)
            val f = rep.first
            if (f == null) {
                Text("Нужны юниты на обеих сторонах и радиус атаки хотя бы у одного из пары.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(c.engagementText(f), style = MaterialTheme.typography.bodyMedium)
                if (f.seconds == null && f.gap > 0) Text("Задайте скорость войск, чтобы получить секунды.", style = MaterialTheme.typography.bodySmall)
                if (rep.partialSpeed) Text("У части пар известна одна скорость: время завышено.", style = MaterialTheme.typography.bodySmall)
                if (rep.skipped > 0) Text("Не посчитано юнитов: ${rep.skipped} (не задан радиус).", style = MaterialTheme.typography.bodySmall)
            }
            Text("Считается по начальной расстановке; скорость введена вручную.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CombatPanel(
    c: MapController, troops: List<Troop>, shown: Map<HexCell, PlacedUnit>,
    ruler: String, modifiers: Set<String>, names: (String) -> String,
) {
    val traitOf = remember(troops) { troops.associate { it.id to it.trait } }
    val r = remember(shown, c.stats, c.radii, c.speeds, c.starFactor, c.marks, ruler, modifiers) {
        c.combat(shown, traitOf, ruler, modifiers, names)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Оценка боя", style = MaterialTheme.typography.titleMedium)
            if (r.verdict == com.mergetacticsai.map.Verdict.UNKNOWN) {
                Text("Нужны юниты с заданными HP, уроном и скоростью ударов на обеих сторонах.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "${r.verdict.title.replaceFirstChar { it.uppercase() }}: у вас останется ${(r.ownLeft * 100).toInt()}% HP, " +
                        "у противника ${(r.enemyLeft * 100).toInt()}% · ${"%.0f".format(r.seconds)} с",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Множитель за звезду: " + if (c.starFactor == 1f) "не задан" else "×%.1f".format(c.starFactor),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { c.changeStarFactor(c.starFactor - 0.1f) }) { Text("−") }
                OutlinedButton(onClick = { c.changeStarFactor(c.starFactor + 0.1f) }) { Text("+") }
            }
            r.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Грубая прикидка по начальной расстановке: числа введены вручную, способности не считаются.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SelectionPanel(
    c: MapController, troops: List<Troop>, shown: Map<HexCell, PlacedUnit>,
    zone: Set<HexCell>, targets: Set<HexCell>,
) {
    val sel = c.selected
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sel == null) {
                Text("Нажмите клетку, чтобы задать войско, звёзды, радиус атаки или пометку.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val u = shown[sel]
                val own = HexMap.isOwn(sel)
                Text(
                    "Клетка ${HexMap.label(sel)} · " + if (own) "ваша сторона" else "сторона противника",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!c.editable()) {
                    Text("Режим рекомендации: правки отключены, переключитесь на «Исходная».", style = MaterialTheme.typography.bodySmall)
                }

                var open by remember(sel) { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { open = true }, enabled = c.editable()) {
                        Text(if (u == null) "Поставить войско" else "Войско: " + c.nameFor(u))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text("Неопознанный юнит") }, onClick = { c.setTroop(sel, PlacedUnit.UNKNOWN); open = false })
                        troops.forEach { t ->
                            DropdownMenuItem(text = { Text("${t.cost} · ${t.name}") }, onClick = { c.setTroop(sel, t.id); open = false })
                        }
                    }
                }

                if (u != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (s in 1..3) {
                            FilterChip(
                                selected = u.stars == s, enabled = c.editable(),
                                onClick = { c.setStars(sel, s) }, label = { Text("$s★") },
                            )
                        }
                        TextButton(onClick = { c.removeUnit(sel) }, enabled = c.editable()) { Text("Убрать") }
                    }

                    if (!u.isUnknown) {
                        val r = c.radii[u.troopId]
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Радиус атаки: " + (r?.let { "$it кл." } ?: "не задан"),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { c.setRadius(u.troopId, if (r == null || r <= 1) null else r - 1) }) { Text("−") }
                            OutlinedButton(onClick = { c.setRadius(u.troopId, (r ?: 0) + 1) }) { Text("+") }
                        }
                        Text(
                            "Радиус общий для всех «${c.nameFor(u)}» и введён вручную: в базе нет проверенного значения.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (r != null) {
                            Text("Клеток в радиусе: ${zone.size} · целей: ${targets.size}", style = MaterialTheme.typography.bodyMedium)
                        }
                        val sp = c.speeds[u.troopId]
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Скорость: " + (sp?.let { "%.1f кл/с".format(it) } ?: "не задана"),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { c.setSpeed(u.troopId, if (sp == null || sp <= 0.1f) null else sp - 0.1f) }) { Text("−") }
                            OutlinedButton(onClick = { c.setSpeed(u.troopId, (sp ?: 0f) + 0.1f) }) { Text("+") }
                        }
                        val st = c.stats[u.troopId] ?: com.mergetacticsai.map.UnitStats(0f, 0f, 0f)
                        Text(
                            "На 1★: HP " + (if (st.hp > 0f) st.hp.toInt().toString() else "—") +
                                " · урон " + (if (st.damage > 0f) st.damage.toInt().toString() else "—") +
                                " · ударов/с " + (if (st.attackSpeed > 0f) "%.2f".format(st.attackSpeed) else "—"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { c.setStats(u.troopId, st.copy(hp = (st.hp - 50f).coerceAtLeast(0f))) }) { Text("HP−") }
                            OutlinedButton(onClick = { c.setStats(u.troopId, st.copy(hp = st.hp + 50f)) }) { Text("HP+") }
                            OutlinedButton(onClick = { c.setStats(u.troopId, st.copy(damage = (st.damage - 5f).coerceAtLeast(0f))) }) { Text("Ур−") }
                            OutlinedButton(onClick = { c.setStats(u.troopId, st.copy(damage = st.damage + 5f)) }) { Text("Ур+") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { c.setStats(u.troopId, st.copy(attackSpeed = (st.attackSpeed - 0.1f).coerceAtLeast(0f))) }) { Text("Уд/с−") }
                            OutlinedButton(onClick = { c.setStats(u.troopId, st.copy(attackSpeed = st.attackSpeed + 0.1f)) }) { Text("Уд/с+") }
                        }
                        val eng = c.contact(shown).byUnit[sel]
                        if (eng != null) {
                            Text("Первая встреча: " + c.engagementText(eng), style = MaterialTheme.typography.bodyMedium)
                        } else if (r != null) {
                            Text("Встреча не посчитана: нет противника или пути.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (own) {
                    val n = c.danger(shown)[sel] ?: 0
                    Text(
                        if (n == 0) "До этой клетки не достают вражеские войска с заданным радиусом."
                        else "Достают до этой клетки: $n вражеских войск.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Text("Пометка клетки", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = c.marks[sel] == null, enabled = c.editable(),
                        onClick = { c.setMark(sel, null) }, label = { Text("Обычная") },
                    )
                    for (m in CellMark.values()) {
                        FilterChip(
                            selected = c.marks[sel] == m, enabled = c.editable(),
                            onClick = { c.setMark(sel, m) }, label = { Text(m.title) },
                        )
                    }
                }
            }
        }
    }
}
