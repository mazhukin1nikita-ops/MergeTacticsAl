package com.mergetacticsai.map

import kotlin.math.abs

/** Клетка поля: ряд 0..7 сверху вниз (0-3 противник, 4-7 вы), колонка 0..5 слева направо. */
data class HexCell(val row: Int, val col: Int)

/**
 * Полное поле 8×6 в тех же координатах, что и [com.mergetacticsai.strategy.HexGrid] (снято со скриншотов 1080x2400):
 * шестиугольники «острым углом вверх», нечётные ряды сдвинуты вправо на пол-клетки.
 * Для рядов 4-7 положение совпадает с HexGrid (это проверяет тест). Ряды 0-3 продолжают ту же сетку вверх;
 * на реальных скриншотах они пока не сверялись.
 */
object HexMap {
    const val ROWS = 8
    const val COLS = 6
    const val OWN_FIRST_ROW = 4

    /** Размеры эталонного экрана. */
    const val REF_W = 1080f
    const val REF_H = 2400f

    private const val DX = 135f
    private const val DY = 93.3f
    private const val Y0 = 1045f

    /** Полуширина и вершины шестиугольника в пикселях эталонного экрана. */
    const val HALF_W = 67.5f
    const val TIP = 62.2f
    const val SIDE = 31.1f

    /** Область экрана, которую показывает карта (пиксели эталонного экрана). */
    const val REGION_X0 = 92f
    const val REGION_Y0 = 960f
    const val REGION_W = 896f
    const val REGION_H = 825f

    val all: List<HexCell> = (0 until ROWS).flatMap { r -> (0 until COLS).map { c -> HexCell(r, c) } }
    val ownCells: List<HexCell> = all.filter { isOwn(it) }
    val enemyCells: List<HexCell> = all.filter { !isOwn(it) }

    fun isOwn(c: HexCell) = c.row >= OWN_FIRST_ROW
    fun inBoard(row: Int, col: Int) = row in 0 until ROWS && col in 0 until COLS

    /** Пропорции снимка близки к 1080x2400; иначе клетки на нём могут сместиться. */
    fun ratioOk(w: Int, h: Int): Boolean = w > 0 && (h.toFloat() / w) in 2.0f..2.45f

    /** Центр клетки в пикселях эталонного экрана 1080x2400. */
    fun centerPx(c: HexCell): Pair<Float, Float> {
        val x0 = if (c.row % 2 == 0) 170f else 237.5f
        return (x0 + DX * c.col) to (Y0 + DY * c.row)
    }

    /** Центр клетки в долях экрана (0..1). */
    fun center(c: HexCell): Pair<Float, Float> {
        val (x, y) = centerPx(c)
        return x / REF_W to y / REF_H
    }

    /** Подпись: буква — ряд сверху вниз (A-D противник, E-H вы), цифра — колонка слева направо. */
    fun label(c: HexCell): String = "${'A' + c.row}${c.col + 1}"

    fun neighbors(c: HexCell): List<HexCell> {
        val dirs = if (c.row % 2 == 0)
            listOf(1 to 0, -1 to 0, -1 to -1, 0 to -1, -1 to 1, 0 to 1)
        else
            listOf(1 to 0, -1 to 0, 0 to -1, 1 to -1, 0 to 1, 1 to 1)
        return dirs.map { (dc, dr) -> HexCell(c.row + dr, c.col + dc) }.filter { inBoard(it.row, it.col) }
    }

    /** Расстояние между клетками в шагах по соседним клеткам. */
    fun distance(a: HexCell, b: HexCell): Int {
        val qa = a.col - (a.row - (a.row and 1)) / 2
        val qb = b.col - (b.row - (b.row and 1)) / 2
        val dq = qa - qb
        val dr = a.row - b.row
        return (abs(dq) + abs(dq + dr) + abs(dr)) / 2
    }

    /** Клетки в радиусе [radius] шагов от [c], без самой клетки. */
    fun within(c: HexCell, radius: Int): Set<HexCell> =
        if (radius <= 0) emptySet() else all.filter { it != c && distance(c, it) <= radius }.toSet()

    /** Клетка, ближайшая к точке (пиксели эталонного экрана), или null, если точка далеко от поля. */
    fun nearest(xPx: Float, yPx: Float): HexCell? {
        var best: HexCell? = null
        var bd = Float.MAX_VALUE
        for (c in all) {
            val (cx, cy) = centerPx(c)
            val dx = xPx - cx
            val dy = (yPx - cy) * 1.253f // выравниваем по пропорциям правильного шестиугольника
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = c }
        }
        return if (bd <= 80f * 80f) best else null
    }
}
