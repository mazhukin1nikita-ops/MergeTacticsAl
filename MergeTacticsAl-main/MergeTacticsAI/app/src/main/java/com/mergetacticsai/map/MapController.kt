package com.mergetacticsai.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.mergetacticsai.strategy.PositionContext
import com.mergetacticsai.strategy.PositionEngine

/** Состояние экрана «Тактическая карта». Каждая правка сразу сохраняется. */
class MapController(private val store: MapStore, private val nameOf: (String) -> String) {
    var layout by mutableStateOf(store.loadLayout()); private set
    var marks by mutableStateOf(store.loadMarks()); private set
    var radii by mutableStateOf(store.loadRadii()); private set
    var speeds by mutableStateOf(store.loadSpeeds()); private set
    var stats by mutableStateOf(store.loadStats()); private set
    var starFactor by mutableStateOf(store.loadStarFactor()); private set
    var selected by mutableStateOf<HexCell?>(null); private set
    /** Карта сборки: войска вашей сборки на желаемых клетках. Бот ставит каждое войско на его клетку. */
    var buildLayout by mutableStateOf(store.loadBuildLayout()); private set
    var buildSelected by mutableStateOf<HexCell?>(null); private set
    var view by mutableStateOf(MapView.ORIGINAL)
    var showRange by mutableStateOf(true)
    var showDanger by mutableStateOf(true)
    var showSpecial by mutableStateOf(true)
    var screenshot by mutableStateOf<ImageBitmap?>(null); private set
    var info by mutableStateOf<String?>(null); private set

    fun recommendation(): Recommendation = Recommender.recommend(layout, radii)

    fun shown(): Map<HexCell, PlacedUnit> = if (view == MapView.RECOMMENDED) recommendation().layout else layout

    fun editable() = view == MapView.ORIGINAL

    fun nameFor(u: PlacedUnit) = if (u.isUnknown) "Неопознан" else nameOf(u.troopId)

    fun tap(c: HexCell?) { selected = if (c == null || c == selected) null else c }

    private fun putLayout(m: Map<HexCell, PlacedUnit>) { layout = m; store.saveLayout(m) }

    fun setTroop(cell: HexCell, troopId: String) {
        if (!editable()) return
        val stars = layout[cell]?.stars ?: 1
        putLayout(layout + (cell to PlacedUnit(troopId, stars)))
    }

    fun setStars(cell: HexCell, stars: Int) {
        if (!editable()) return
        val u = layout[cell] ?: return
        putLayout(layout + (cell to u.copy(stars = stars.coerceIn(1, 3))))
    }

    fun addUnknown(cell: HexCell) {
        if (!editable() || layout.containsKey(cell)) return
        putLayout(layout + (cell to PlacedUnit(PlacedUnit.UNKNOWN)))
    }

    fun removeUnit(cell: HexCell) {
        if (!editable()) return
        putLayout(layout - cell)
    }

    fun setMark(cell: HexCell, mark: CellMark?) {
        if (!editable()) return
        val m = if (mark == null) marks - cell else marks + (cell to mark)
        marks = m; store.saveMarks(m)
    }

    /** Радиус атаки в клетках; null — «не задан». Значение вводится вручную. */
    fun setRadius(troopId: String, radius: Int?) {
        if (troopId == PlacedUnit.UNKNOWN) return
        val m = if (radius == null || radius < 1) radii - troopId else radii + (troopId to radius.coerceAtMost(MapCodec.MAX_RADIUS))
        radii = m; store.saveRadii(m)
    }

    /** Скорость в клетках в секунду; null — «не задана». Шаг 0.1. */
    fun setSpeed(troopId: String, speed: Float?) {
        if (troopId == PlacedUnit.UNKNOWN) return
        val v = speed?.let { Math.round(it * 10f) / 10f }
        val m = if (v == null || v <= 0f) speeds - troopId else speeds + (troopId to v.coerceAtMost(MapCodec.MAX_SPEED))
        speeds = m; store.saveSpeeds(m)
    }

    /** HP, урон и скорость ударов войска на 1★. Все нули — значения удаляются. */
    fun setStats(troopId: String, s: UnitStats) {
        if (troopId == PlacedUnit.UNKNOWN) return
        val v = UnitStats(
            (Math.round(s.hp / 10f) * 10f).coerceIn(0f, MapCodec.MAX_HP),
            (Math.round(s.damage)).toFloat().coerceIn(0f, MapCodec.MAX_DAMAGE),
            (Math.round(s.attackSpeed * 100f) / 100f).coerceIn(0f, MapCodec.MAX_APS),
        )
        val m = if (v.hp == 0f && v.damage == 0f && v.attackSpeed == 0f) stats - troopId else stats + (troopId to v)
        stats = m; store.saveStats(m)
    }

    /** Во сколько раз растут HP и урон за звезду; 1.0 — не задано. */
    fun changeStarFactor(f: Float) {
        val v = (Math.round(f * 10f) / 10f).coerceIn(1f, 3f)
        starFactor = v; store.saveStarFactor(v)
    }

    /** Оценка боя по показанной расстановке: ruler и modifiers — выбор пользователя в приложении. */
    fun combat(
        shown: Map<HexCell, PlacedUnit>, traitOf: Map<String, String>, ruler: String, modifiers: Set<String>,
        names: (String) -> String = { it },
    ): CombatResult = CombatModel.evaluate(
        shown, stats, CombatContext(ruler, modifiers, traitOf, starFactor), radii, speeds, marks, names,
    )

    fun clearSide(own: Boolean) {
        if (!editable()) return
        putLayout(layout.filterKeys { HexMap.isOwn(it) != own })
        if (selected?.let { HexMap.isOwn(it) == own } == true) selected = null
    }

    // ---- карта сборки ----

    private fun putBuild(m: Map<HexCell, PlacedUnit>) { buildLayout = m; store.saveBuildLayout(m) }

    /** Выбор клетки на карте сборки; клетки противника не выбираются. */
    fun buildTap(c: HexCell?) { buildSelected = if (c == null || !HexMap.isOwn(c) || c == buildSelected) null else c }

    fun setBuildTroop(cell: HexCell, troopId: String) {
        if (!HexMap.isOwn(cell) || troopId == PlacedUnit.UNKNOWN) return
        putBuild(buildLayout + (cell to PlacedUnit(troopId)))
    }

    fun removeBuildUnit(cell: HexCell) { putBuild(buildLayout - cell) }

    fun clearBuild() { putBuild(emptyMap()); buildSelected = null }

    /**
     * Ставит на карту сборки тех, кого там ещё нет, по расчёту позиционного ИИ. Уже расставленных не трогает.
     * Ближний бой раньше дальнего (по заданному радиусу), чтобы фронт занял лучшие клетки; без радиуса — в конце.
     * Противник берётся с тактической карты (если он там задан).
     */
    fun autoBuild(ids: Collection<String>, ctx: PositionContext) {
        val have = buildLayout.values.map { it.troopId }.toSet()
        val todo = ids.filter { it !in have }.sortedWith(compareBy({ ctx.radii[it] ?: 99 }, { it }))
        var cur = buildLayout
        for (id in todo) {
            val free = HexMap.ownCells.filter { it !in cur }
            val unit = PlacedUnit(id)
            val cell = PositionEngine.rank(unit, free, cur, ctx).firstOrNull() ?: break
            cur = cur + (cell to unit)
        }
        putBuild(cur)
    }

    fun clearMarks() { marks = emptyMap(); store.saveMarks(marks) }

    fun clearScreenshot() { screenshot = null; info = null }

    fun failScan(text: String) { info = text }

    /** Применяет результат распознавания: занятые клетки становятся юнитами, известные названия сохраняются. */
    fun applyScan(image: ImageBitmap, r: BoardScan.Result) {
        val found = r.own + r.enemy
        val merged = LinkedHashMap<HexCell, PlacedUnit>()
        for (c in found) merged[c] = layout[c] ?: PlacedUnit(PlacedUnit.UNKNOWN)
        putLayout(merged)
        screenshot = image
        view = MapView.ORIGINAL
        selected = null
        info = buildString {
            append("Найдено юнитов: ваших ${r.own.size}, противника ${r.enemy.size}. ")
            append("Названия и звёзды задайте вручную: нажмите клетку. ")
            append("Снимок должен быть из фазы расстановки, в бою юниты сдвигаются.")
            if (!r.aspectOk) append(" Пропорции снимка отличаются от 1080×2400, клетки могут быть смещены.")
        }
        view = MapView.ORIGINAL
    }

    // ---- подсветка ----

    fun zone(shown: Map<HexCell, PlacedUnit>): Set<HexCell> {
        val s = selected ?: return emptySet()
        return Threat.zone(s, shown[s], radii)
    }

    fun targets(shown: Map<HexCell, PlacedUnit>, zone: Set<HexCell>): Set<HexCell> {
        val s = selected ?: return emptySet()
        return Threat.targets(s, zone, shown)
    }

    fun danger(shown: Map<HexCell, PlacedUnit>): Map<HexCell, Int> = Threat.danger(shown, radii)

    /** Время до первого контакта по показанной расстановке. */
    fun contact(shown: Map<HexCell, PlacedUnit>): ContactReport = ContactModel.report(shown, radii, speeds)

    fun engagementText(e: Engagement): String {
        val t = e.seconds?.let { " · %.1f с".format(it) } ?: ""
        return "${HexMap.label(e.own)} ↔ ${HexMap.label(e.enemy)}: путь ${e.pathSteps} кл., сойдутся через ${e.gap} кл.$t"
    }

    /** Вражеские войска с неизвестным радиусом: их угроза в расчёте не учтена. */
    fun enemiesWithoutRadius(shown: Map<HexCell, PlacedUnit>): Int =
        shown.entries.count { !HexMap.isOwn(it.key) && radii[it.value.troopId] == null }

    fun moveText(m: Move): String =
        "${nameFor(m.unit)}${if (m.unit.stars > 1) " ${m.unit.stars}★" else ""}: ${HexMap.label(m.from)} → ${HexMap.label(m.to)}"
}
