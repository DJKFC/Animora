package com.animora.studio.model

import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min

/** Свойства твина, для которых можно задать отдельную кривую. */
enum class CurveProp(val title: String) {
    POSITION("Позиция"), ROTATION("Поворот"), SCALE("Масштаб"), ALPHA("Прозрачность"), SHAPE("Форма")
}

/**
 * Опорная точка кривой с рычагами Безье (координаты рычагов абсолютные).
 * x — время (0..1), y — прогресс (обычно 0..1, допускается перелёт).
 */
data class CurvePoint(
    val x: Float, val y: Float,
    val inX: Float = x, val inY: Float = y,
    val outX: Float = x, val outY: Float = y
)

/**
 * Кривая сглаживания из нескольких сегментов кубических Безье — аналог «Пользовательского
 * сглаживания» и редактора движения Adobe Animate. Неизменяемая.
 */
class EaseCurve(val points: List<CurvePoint>) {

    fun eval(xIn: Float): Float {
        if (points.size < 2) return xIn
        val x = xIn.coerceIn(0f, 1f)
        var i = 0
        while (i < points.size - 2 && x > points[i + 1].x) i++
        val p = points[i]; val q = points[i + 1]
        if (q.x - p.x <= 1e-6f) return q.y
        // рычаги по X ограничены сегментом — тогда X(t) монотонна и решается бисекцией
        val x1 = p.outX.coerceIn(p.x, q.x); val x2 = q.inX.coerceIn(p.x, q.x)
        var lo = 0f; var hi = 1f; var t = (x - p.x) / (q.x - p.x)
        repeat(30) {
            val bx = bez(p.x, x1, x2, q.x, t)
            if (bx < x) lo = t else hi = t
            t = (lo + hi) / 2f
        }
        return bez(p.y, p.outY, q.inY, q.y, t)
    }

    /** Точка на сегменте при параметре t (для рисования). */
    fun segmentPoint(i: Int, t: Float): Pair<Float, Float> {
        val p = points[i]; val q = points[i + 1]
        val x1 = p.outX.coerceIn(p.x, q.x); val x2 = q.inX.coerceIn(p.x, q.x)
        return bez(p.x, x1, x2, q.x, t) to bez(p.y, p.outY, q.inY, q.y, t)
    }

    fun with(index: Int, p: CurvePoint) = EaseCurve(points.toMutableList().also { it[index] = p })

    /** Вставка точки на кривой в момент x с плавными рычагами по касательной. */
    fun insertAt(x: Float): Pair<EaseCurve, Int> {
        val xc = x.coerceIn(0.01f, 0.99f)
        val y = eval(xc)
        val d = 0.01f
        val slope = (eval(min(1f, xc + d)) - eval(max(0f, xc - d))) / (2 * d)
        var idx = points.indexOfFirst { it.x > xc }.let { if (it < 0) points.size - 1 else it }
        if (idx == 0) idx = 1
        val prev = points[idx - 1]; val next = points[idx]
        val hl = (xc - prev.x) / 3f; val hr = (next.x - xc) / 3f
        val np = CurvePoint(xc, y, xc - hl, y - slope * hl, xc + hr, y + slope * hr)
        val list = points.toMutableList(); list.add(idx, np)
        return EaseCurve(list) to idx
    }

    fun removeAt(index: Int): EaseCurve =
        if (index <= 0 || index >= points.size - 1) this else EaseCurve(points.toMutableList().also { it.removeAt(index) })

    fun toJson(): JSONArray = JSONArray().apply {
        points.forEach { p -> listOf(p.x, p.y, p.inX, p.inY, p.outX, p.outY).forEach { put(it.toDouble()) } }
    }

    companion object {
        private fun bez(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
            val u = 1 - t
            return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d
        }

        fun linear() = EaseCurve(listOf(CurvePoint(0f, 0f, 0f, 0f, 1 / 3f, 1 / 3f), CurvePoint(1f, 1f, 2 / 3f, 2 / 3f, 1f, 1f)))

        /** Классическая кубическая Безье (как CSS cubic-bezier). */
        fun bezier(x1: Float, y1: Float, x2: Float, y2: Float) =
            EaseCurve(listOf(CurvePoint(0f, 0f, 0f, 0f, x1, y1), CurvePoint(1f, 1f, x2, y2, 1f, 1f)))

        /**
         * Кривая из функции: опорные точки в xs, рычаги — по односторонним производным
         * (сохраняет изломы, например у «отскока»).
         */
        fun fromFunction(f: (Float) -> Float, xs: List<Float>): EaseCurve {
            val d = 0.0005f
            val pts = xs.mapIndexed { i, x ->
                val y = f(x)
                val left = if (x > 0f) (y - f(x - d)) / d else 0f
                val right = if (x < 1f) (f(x + d) - y) / d else 0f
                val hl = if (i > 0) (x - xs[i - 1]) / 3f else 0f
                val hr = if (i < xs.size - 1) (xs[i + 1] - x) / 3f else 0f
                CurvePoint(x, y, x - hl, y - left * hl, x + hr, y + right * hr)
            }
            return EaseCurve(pts)
        }

        fun fromEasing(e: Easing): EaseCurve = when (e) {
            Easing.LINEAR -> linear()
            Easing.HOLD -> EaseCurve(listOf(CurvePoint(0f, 0f, 0f, 0f, 0.33f, 0f), CurvePoint(0.999f, 0f, 0.67f, 0f, 0.999f, 0f), CurvePoint(1f, 1f, 1f, 1f, 1f, 1f)))
            Easing.BOUNCE -> fromFunction({ e.apply(it) }, listOf(0f, 0.2f, 1 / 2.75f, 0.54f, 2 / 2.75f, 0.82f, 2.5f / 2.75f, 0.955f, 1f))
            Easing.ELASTIC -> fromFunction({ e.apply(it) }, (0..16).map { it / 16f })
            else -> fromFunction({ e.apply(it) }, (0..4).map { it / 4f })
        }

        fun fromJson(a: JSONArray): EaseCurve? {
            val n = a.length() / 6
            if (n < 2) return null
            return EaseCurve((0 until n).map { i ->
                fun v(k: Int) = a.getDouble(i * 6 + k).toFloat()
                CurvePoint(v(0), v(1), v(2), v(3), v(4), v(5))
            })
        }
    }
}
