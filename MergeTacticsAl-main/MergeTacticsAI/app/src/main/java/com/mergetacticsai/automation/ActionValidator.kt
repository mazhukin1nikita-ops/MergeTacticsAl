package com.mergetacticsai.automation

import com.mergetacticsai.game.GameState
import com.mergetacticsai.strategy.Action

data class Validation(val ok: Boolean, val reason: String = "")

class ActionValidator(
    private val minIntervalMs: Long = 400,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var last = 0L

    fun validateTap(x: Float, y: Float, w: Int, h: Int) =
        if (x in 0f..w.toFloat() && y in 0f..h.toFloat()) Validation(true)
        else Validation(false, "Координаты вне экрана")

    fun validate(a: Action, s: GameState, perms: Permissions): Validation {
        if (!perms.canControlGame) return Validation(false, "Нет необходимых разрешений")
        val now = clock()
        if (last != 0L && now - last < minIntervalMs) return Validation(false, "Слишком частые действия")
        val slots = s.slots
        val r = when (a) {
            is Action.Buy -> {
                val u = s.shop.getOrNull(a.shopIndex)
                when {
                    u == null -> Validation(false, "В слоте магазина пусто")
                    u.cost > s.elixir -> Validation(false, "Не хватает эликсира")
                    !s.hasFreeBenchSlot() -> Validation(false, "Нет места на скамейке")
                    else -> Validation(true)
                }
            }
            is Action.Merge -> {
                val x = slots.getOrNull(a.a); val y = slots.getOrNull(a.b)
                when {
                    a.a == a.b -> Validation(false, "Один и тот же слот")
                    x == null || y == null -> Validation(false, "Пустой слот")
                    x.name != y.name || x.level != y.level -> Validation(false, "Юниты не идентичны")
                    x.level >= 3 -> Validation(false, "Максимальный уровень")
                    else -> Validation(true)
                }
            }
            is Action.Move -> when {
                slots.getOrNull(a.from) == null -> Validation(false, "Нечего перемещать")
                a.to !in slots.indices -> Validation(false, "Слот назначения вне диапазона")
                else -> Validation(true)
            }
            is Action.Sell -> if (slots.getOrNull(a.slot) != null) Validation(true) else Validation(false, "Нечего продавать")
            Action.Refresh -> {
                val c = s.refreshCost
                if (c != null && c <= s.elixir) Validation(true) else Validation(false, "Обновление недоступно")
            }
            Action.Wait -> Validation(true)
        }
        if (r.ok && a != Action.Wait) last = now
        return r
    }
}
