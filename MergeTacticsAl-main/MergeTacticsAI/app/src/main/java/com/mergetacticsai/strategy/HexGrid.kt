package com.mergetacticsai.strategy

import kotlin.math.abs

/** Клетка поля. x, y — центр в долях экрана (0..1). */
data class Cell(val row: Int, val col: Int, val x: Float, val y: Float)

/**
 * Сетка поля игрока, снятая со скриншотов 1080x2400: шестиугольники в рядах со сдвигом на пол-клетки.
 * Ряды 0-3 принадлежат сопернику, 4-7 — игроку (проверено по положению юнитов на скриншотах).
 * Ряд 8 рядом со скамейкой не используется.
 */
object HexGrid {
    const val FIRST_ROW = 4
    const val LAST_ROW = 7
    const val COLS = 6
    private const val DX = 135f
    private const val DY = 93.3f

    val cells: List<Cell> = (FIRST_ROW..LAST_ROW).flatMap { r ->
        val x0 = if (r % 2 == 0) 170f else 237.5f
        (0 until COLS).map { c -> Cell(r, c, (x0 + DX * c) / 1080f, (1045f + DY * r) / 2400f) }
    }

    /** Порядок предпочтения: центр поля и средние ряды раньше краёв. */
    val preferred: List<Cell> = cells.sortedBy { abs(it.x - 0.5f) * 2f + abs(it.y - 0.66f) }

    fun indexOf(c: Cell) = cells.indexOf(c)
}
