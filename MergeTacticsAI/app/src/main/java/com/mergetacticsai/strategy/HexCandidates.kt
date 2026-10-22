package com.mergetacticsai.strategy

import com.mergetacticsai.ai.Point
import kotlin.math.abs

/** Точки в нижней половине поля, куда пробуем ставить юнитов. Игра сама «притягивает» юнита к ближайшему гексу. */
object HexCandidates {
    val points: List<Point> = run {
        val xs = listOf(0.306f, 0.435f, 0.565f, 0.694f)
        val ys = listOf(0.604f, 0.650f, 0.696f)
        ys.flatMap { y -> xs.map { x -> Point(x, y) } }.sortedBy { abs(it.x - 0.5f) + abs(it.y - 0.67f) }
    }
}
