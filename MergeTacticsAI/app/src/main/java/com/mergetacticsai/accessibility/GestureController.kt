package com.mergetacticsai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.mergetacticsai.automation.Timing
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object GestureController {
    fun tap(x: Float, y: Float): Boolean {
        val s = GameAccessibilityService.instance ?: return false
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, Timing.TAP_MS)).build()
        return s.dispatchGesture(g, null, null)
    }

    fun drag(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = Timing.dragMs): Boolean {
        val s = GameAccessibilityService.instance ?: return false
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, durationMs)).build()
        return s.dispatchGesture(g, null, null)
    }

    /**
     * Один непрерывный жест: касание → удержание ([holdMs]) → перетаскивание ([dragMs]) → пауза над целью ([dwellMs]) →
     * отпускание. Палец не отрывается между частями: каждая часть — продолжение предыдущего штриха. Возвращает true,
     * если система выполнила все три части.
     */
    suspend fun holdDrag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, dragMs: Long, dwellMs: Long): Boolean {
        val s = GameAccessibilityService.instance ?: return false
        val start = Path().apply { moveTo(x1, y1) }
        val move = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val end = Path().apply { moveTo(x2, y2) }
        val hold = GestureDescription.StrokeDescription(start, 0, holdMs.coerceAtLeast(1), true)
        if (!dispatchStroke(s, hold)) return false
        val drag = hold.continueStroke(move, 0, dragMs.coerceAtLeast(1), true)
        if (!dispatchStroke(s, drag)) return false
        val drop = drag.continueStroke(end, 0, dwellMs.coerceAtLeast(1), false)
        return dispatchStroke(s, drop)
    }

    private suspend fun dispatchStroke(s: AccessibilityService, stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder().addStroke(stroke).build()
            val accepted = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!accepted && cont.isActive) cont.resume(false)
        }
}
