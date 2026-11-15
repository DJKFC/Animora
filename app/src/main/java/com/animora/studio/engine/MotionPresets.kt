package com.animora.studio.engine

import com.animora.studio.model.Easing

/**
 * Ключ готового движения. t — доля длительности (0..1). Смещения dx/dy — в размерах объекта,
 * dxW — в ширинах кадра (для въездов из-за края). easing — сглаживание участка, который начинается здесь.
 * hold — без твина (резкая смена, для мигания).
 */
class PresetKey(
    val t: Float,
    val dx: Float = 0f, val dy: Float = 0f, val dxW: Float = 0f,
    val rot: Float = 0f, val sx: Float = 1f, val sy: Float = 1f, val alpha: Float = 1f,
    val easing: Easing = Easing.EASE_IN_OUT, val hold: Boolean = false
) {
    /** Сила эффекта: амплитуды умножаются, прозрачность и въезды не меняются. */
    fun scaled(s: Float) = PresetKey(t, dx * s, dy * s, dxW, rot * s, 1 + (sx - 1) * s, 1 + (sy - 1) * s, alpha, easing, hold)
}

enum class MotionPreset(val title: String, val group: String, val ground: Boolean = false) {
    JUMP("Прыжок", "Движение", true),
    BOUNCE("Падение с отскоком", "Движение", true),
    FLOAT("Парение", "Движение"),
    SHAKE("Тряска", "Движение"),
    SLIDE_IN_LEFT("Въезд слева", "Движение"),
    SLIDE_IN_RIGHT("Въезд справа", "Движение"),
    SLIDE_OUT_RIGHT("Уход вправо", "Движение"),
    SLIDE_OUT_LEFT("Уход влево", "Движение"),
    WOBBLE("Покачивание", "Поворот"),
    SPIN("Вращение", "Поворот"),
    PULSE("Пульс", "Масштаб"),
    HEARTBEAT("Сердцебиение", "Масштаб"),
    JELLY("Желе", "Масштаб", true),
    SQUASH("Сжатие-растяжение", "Масштаб", true),
    FADE_IN("Появление", "Появление и исчезновение"),
    POP("Упругое появление", "Появление и исчезновение"),
    FADE_OUT("Исчезновение", "Появление и исчезновение"),
    BLINK("Мигание", "Появление и исчезновение");

    /** Повторяющиеся движения удобно зацикливать. */
    val loopable: Boolean get() = this in setOf(FLOAT, SHAKE, WOBBLE, SPIN, PULSE, HEARTBEAT, JELLY, SQUASH, BLINK, JUMP)

    fun keys(): List<PresetKey> {
        val io = Easing.EASE_IN_OUT; val o = Easing.EASE_OUT; val i = Easing.EASE_IN; val lin = Easing.LINEAR
        return when (this) {
            JUMP -> listOf(
                PresetKey(0f, easing = o),
                PresetKey(0.18f, sx = 1.18f, sy = 0.8f, easing = o),          // замах: присел
                PresetKey(0.5f, dy = -1.6f, sx = 0.92f, sy = 1.12f, easing = i), // вершина, вытянут
                PresetKey(0.82f, sx = 1.15f, sy = 0.82f, easing = o),          // приземление
                PresetKey(1f)
            )
            BOUNCE -> listOf(
                PresetKey(0f, dy = -2.5f, sx = 0.94f, sy = 1.08f, easing = i),
                PresetKey(0.4f, sx = 1.25f, sy = 0.78f, easing = o),
                PresetKey(0.46f, easing = o),
                PresetKey(0.66f, dy = -0.9f, easing = i),
                PresetKey(0.82f, sx = 1.12f, sy = 0.9f, easing = o),
                PresetKey(0.9f, dy = -0.25f, easing = i),
                PresetKey(1f)
            )
            FLOAT -> listOf(PresetKey(0f), PresetKey(0.25f, dy = -0.25f, rot = 2f), PresetKey(0.5f), PresetKey(0.75f, dy = -0.25f, rot = -2f), PresetKey(1f))
            SHAKE -> (0..10).map { k -> val d = if (k == 0 || k == 10) 0f else (if (k % 2 == 0) 0.12f else -0.12f) * (1f - k / 10f); PresetKey(k / 10f, dx = d, easing = lin) }
            SLIDE_IN_LEFT -> listOf(PresetKey(0f, dxW = -1.1f, easing = o), PresetKey(0.82f, dx = 0.15f, easing = io), PresetKey(1f))
            SLIDE_IN_RIGHT -> listOf(PresetKey(0f, dxW = 1.1f, easing = o), PresetKey(0.82f, dx = -0.15f, easing = io), PresetKey(1f))
            SLIDE_OUT_RIGHT -> listOf(PresetKey(0f, easing = io), PresetKey(0.2f, dx = -0.15f, easing = i), PresetKey(1f, dxW = 1.1f))
            SLIDE_OUT_LEFT -> listOf(PresetKey(0f, easing = io), PresetKey(0.2f, dx = 0.15f, easing = i), PresetKey(1f, dxW = -1.1f))
            WOBBLE -> listOf(0f to 0f, 0.17f to 12f, 0.34f to -10f, 0.5f to 7f, 0.67f to -4f, 0.84f to 2f, 1f to 0f).map { (t, r) -> PresetKey(t, rot = r) }
            SPIN -> listOf(PresetKey(0f, easing = lin), PresetKey(1f, rot = 360f))
            PULSE -> listOf(PresetKey(0f), PresetKey(0.25f, sx = 1.15f, sy = 1.15f), PresetKey(0.5f), PresetKey(0.75f, sx = 1.15f, sy = 1.15f), PresetKey(1f))
            HEARTBEAT -> listOf(
                PresetKey(0f, easing = o), PresetKey(0.1f, sx = 1.2f, sy = 1.2f), PresetKey(0.2f, easing = o),
                PresetKey(0.3f, sx = 1.14f, sy = 1.14f), PresetKey(0.45f), PresetKey(1f)
            )
            JELLY -> listOf(
                PresetKey(0f), PresetKey(0.15f, sx = 1.25f, sy = 0.8f), PresetKey(0.3f, sx = 0.85f, sy = 1.15f),
                PresetKey(0.45f, sx = 1.1f, sy = 0.92f), PresetKey(0.6f, sx = 0.96f, sy = 1.04f), PresetKey(0.8f, sx = 1.02f, sy = 0.98f), PresetKey(1f)
            )
            SQUASH -> listOf(PresetKey(0f), PresetKey(0.3f, sx = 1.2f, sy = 0.8f), PresetKey(0.65f, sx = 0.88f, sy = 1.15f), PresetKey(1f))
            FADE_IN -> listOf(PresetKey(0f, alpha = 0f, sx = 0.6f, sy = 0.6f, easing = o), PresetKey(0.7f, sx = 1.06f, sy = 1.06f), PresetKey(1f))
            POP -> listOf(
                PresetKey(0f, alpha = 0f, sx = 0.05f, sy = 0.05f, easing = o), PresetKey(0.5f, sx = 1.2f, sy = 1.2f),
                PresetKey(0.7f, sx = 0.92f, sy = 0.92f), PresetKey(0.85f, sx = 1.04f, sy = 1.04f), PresetKey(1f)
            )
            FADE_OUT -> listOf(PresetKey(0f, easing = i), PresetKey(1f, alpha = 0f, sx = 0.6f, sy = 0.6f))
            BLINK -> (0..6).map { k -> PresetKey(k / 6f, alpha = if (k % 2 == 1) 0.1f else 1f, hold = true) }
        }
    }

    companion object {
        /** Ключи с учётом силы и повторов, t растянуто на все повторы (0..1). */
        fun build(p: MotionPreset, strength: Float, loops: Int): List<PresetKey> {
            val base = p.keys().map { it.scaled(strength) }
            val n = loops.coerceAtLeast(1)
            val out = ArrayList<PresetKey>()
            for (r in 0 until n) for ((idx, k) in base.withIndex()) {
                if (r > 0 && idx == 0) continue
                out.add(PresetKey((r + k.t) / n, k.dx, k.dy, k.dxW, k.rot, k.sx, k.sy, k.alpha, k.easing, k.hold))
            }
            return out
        }

        /** Значение готового движения в момент t (0..1) — для ключей, оказавшихся внутри. */
        fun eval(keys: List<PresetKey>, t: Float): PresetKey {
            if (keys.isEmpty()) return PresetKey(t)
            if (t <= keys.first().t) return keys.first()
            for (j in 0 until keys.size - 1) {
                val a = keys[j]; val b = keys[j + 1]
                if (t <= b.t) {
                    if (a.hold) return a
                    val r = a.easing.apply(((t - a.t) / (b.t - a.t).coerceAtLeast(1e-6f)).coerceIn(0f, 1f))
                    fun l(x: Float, y: Float) = x + (y - x) * r
                    return PresetKey(t, l(a.dx, b.dx), l(a.dy, b.dy), l(a.dxW, b.dxW), l(a.rot, b.rot), l(a.sx, b.sx), l(a.sy, b.sy), l(a.alpha, b.alpha), a.easing)
                }
            }
            return keys.last()
        }
    }
}
