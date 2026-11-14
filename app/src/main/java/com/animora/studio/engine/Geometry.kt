package com.animora.studio.engine

import android.graphics.Path
import com.animora.studio.model.BrushKind
import com.animora.studio.model.Element
import android.graphics.Matrix
import com.animora.studio.model.BitmapElement
import com.animora.studio.model.ColorEffect
import com.animora.studio.model.InstanceElement
import com.animora.studio.model.PathFillElement
import com.animora.studio.model.TextElement
import com.animora.studio.model.StrokeElement
import com.animora.studio.model.lerp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Растущий массив float без упаковки. */
class FloatList(cap: Int = 256) {
    var data = FloatArray(cap)
        private set
    var size = 0
        private set
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun clear() { size = 0 }
    fun toArray(): FloatArray = data.copyOf(size)
    operator fun get(i: Int) = data[i]
    operator fun set(i: Int, v: Float) { data[i] = v }
}

/**
 * Строит контур штриха переменной ширины.
 * Штрих = объединение кругов в точках и трапеций между ними; одинаковая ориентация + WINDING
 * даёт корректное объединение без «наложения» прозрачности.
 */
object StrokePath {
    fun get(s: StrokeElement): Path {
        s.cachedPath?.let { return it }
        val p = build(s.pts, s.count, s.size, s.kind)
        s.cachedPath = p
        return p
    }

    fun build(pts: FloatArray, n: Int, size: Float, kind: BrushKind): Path {
        val path = Path()
        path.fillType = Path.FillType.WINDING
        if (n == 0) return path
        val base = size / 2f
        val minF = when (kind) { BrushKind.PENCIL -> 0.25f; BrushKind.CALLIGRAPHY -> 0.12f; else -> 0.04f }
        val r = FloatArray(n)
        for (i in 0 until n) {
            var f = max(pts[i * 3 + 2], minF)
            if (kind == BrushKind.CALLIGRAPHY && n > 1) {
                val a = max(0, i - 1); val b = min(n - 1, i + 1)
                val ang = atan2(pts[b * 3 + 1] - pts[a * 3 + 1], pts[b * 3] - pts[a * 3])
                f *= 0.15f + 0.85f * abs(sin(ang - Math.PI.toFloat() / 4f))
            }
            r[i] = max(0.35f, base * f)
        }
        if (n == 1) {
            path.addCircle(pts[0], pts[1], r[0], Path.Direction.CW)
            return path
        }
        var lx = pts[0]; var ly = pts[1]; var lr = r[0]
        path.addCircle(lx, ly, lr, Path.Direction.CW)
        for (i in 1 until n) {
            val x = pts[i * 3]; val y = pts[i * 3 + 1]; val rr = r[i]
            val dx = x - lx; val dy = y - ly
            val len = hypot(dx, dy)
            if (len < 0.3f && i != n - 1) continue
            if (len > 0.0001f) {
                val nx = dy / len; val ny = -dx / len
                path.moveTo(lx + nx * lr, ly + ny * lr)
                path.lineTo(x + nx * rr, y + ny * rr)
                path.lineTo(x - nx * rr, y - ny * rr)
                path.lineTo(lx - nx * lr, ly - ny * lr)
                path.close()
            }
            path.addCircle(x, y, rr, Path.Direction.CW)
            lx = x; ly = y; lr = rr
        }
        return path
    }
}

object Interp {
    /** Передискретизация штриха по длине дуги до n точек. */
    fun resample(s: StrokeElement, n: Int): FloatArray {
        val c = s.count
        val out = FloatArray(n * 3)
        if (c == 0) return out
        if (c == 1) {
            for (i in 0 until n) { out[i * 3] = s.pts[0]; out[i * 3 + 1] = s.pts[1]; out[i * 3 + 2] = s.pts[2] }
            return out
        }
        val cum = FloatArray(c)
        for (i in 1 until c) cum[i] = cum[i - 1] + hypot(s.x(i) - s.x(i - 1), s.y(i) - s.y(i - 1))
        val total = cum[c - 1]
        var j = 0
        for (i in 0 until n) {
            val d = if (n == 1) 0f else total * i / (n - 1)
            while (j < c - 2 && cum[j + 1] < d) j++
            val seg = cum[j + 1] - cum[j]
            val t = if (seg <= 0f) 0f else ((d - cum[j]) / seg).coerceIn(0f, 1f)
            for (k in 0..2) out[i * 3 + k] = lerp(s.pts[j * 3 + k], s.pts[(j + 1) * 3 + k], t)
        }
        return out
    }

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        fun ch(sh: Int) = (lerp(((a shr sh) and 0xFF).toFloat(), ((b shr sh) and 0xFF).toFloat(), tt) + 0.5f).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun morph(a: StrokeElement, b: StrokeElement, t: Float): StrokeElement {
        val n = max(2, min(400, max(a.count, b.count)))
        val pa = resample(a, n)
        var pb = resample(b, n)
        // если штрих нарисован в обратную сторону — разворачиваем, чтобы не было «перекрута»
        val direct = hypot(pa[0] - pb[0], pa[1] - pb[1]) + hypot(pa[(n - 1) * 3] - pb[(n - 1) * 3], pa[(n - 1) * 3 + 1] - pb[(n - 1) * 3 + 1])
        val crossed = hypot(pa[0] - pb[(n - 1) * 3], pa[1] - pb[(n - 1) * 3 + 1]) + hypot(pa[(n - 1) * 3] - pb[0], pa[(n - 1) * 3 + 1] - pb[1])
        if (crossed < direct) {
            val rev = FloatArray(n * 3)
            for (i in 0 until n) for (k in 0..2) rev[i * 3 + k] = pb[(n - 1 - i) * 3 + k]
            pb = rev
        }
        val out = FloatArray(n * 3)
        for (i in out.indices) out[i] = lerp(pa[i], pb[i], t)
        return StrokeElement(
            out, lerpColor(a.color, b.color, t), lerp(a.size, b.size, t),
            if (t < 0.5f) a.kind else b.kind, lerp(a.opacity, b.opacity, t.coerceIn(0f, 1f)),
            if (t < 0.5f) a.tipId else b.tipId
        )
    }

    /** Твин формы: попарный морфинг штрихов, лишние штрихи и растровые элементы — плавное появление/исчезновение. */
    fun morphElements(a: List<Element>, b: List<Element>, t: Float): List<Element> {
        val tc = t.coerceIn(0f, 1f)
        val sa = a.filterIsInstance<StrokeElement>()
        val sb = b.filterIsInstance<StrokeElement>()
        val out = ArrayList<Element>()
        a.filterIsInstance<BitmapElement>().forEach { out.add(it.withAlpha(it.alpha * (1f - tc))) }
        b.filterIsInstance<BitmapElement>().forEach { out.add(it.withAlpha(it.alpha * tc)) }
        a.filterIsInstance<PathFillElement>().forEach { out.add(it.withAlpha(it.alpha * (1f - tc))) }
        b.filterIsInstance<PathFillElement>().forEach { out.add(it.withAlpha(it.alpha * tc)) }
        a.filterIsInstance<TextElement>().forEach { out.add(it.copyWith(alpha = it.alpha * (1f - tc))) }
        b.filterIsInstance<TextElement>().forEach { out.add(it.copyWith(alpha = it.alpha * tc)) }
        // экземпляры символов: одинаковые пары — плавный переход матрицы и эффекта, иначе — растворение
        val ia = a.filterIsInstance<InstanceElement>(); val ib = b.filterIsInstance<InstanceElement>()
        if (ia.size == ib.size && ia.indices.all { ia[it].symbolId == ib[it].symbolId }) {
            ia.indices.forEach { i ->
                val va = FloatArray(9); val vb = FloatArray(9); ia[i].matrix.getValues(va); ib[i].matrix.getValues(vb)
                val m = Matrix(); m.setValues(FloatArray(9) { k -> lerp(va[k], vb[k], t) })
                val ea = ia[i].effect; val eb = ib[i].effect
                out.add(InstanceElement(ia[i].symbolId, m, ia[i].loop, ia[i].firstFrame, ColorEffect(
                    lerp(ea.alpha, eb.alpha, tc), if (tc < 0.5f) ea.tint else eb.tint, lerp(ea.tintAmount, eb.tintAmount, tc), lerp(ea.brightness, eb.brightness, tc)
                )))
            }
        } else {
            ia.forEach { out.add(it.with(effect = it.effect.copy(alpha = it.effect.alpha * (1f - tc)))) }
            ib.forEach { out.add(it.with(effect = it.effect.copy(alpha = it.effect.alpha * tc))) }
        }
        val pairs = min(sa.size, sb.size)
        for (i in 0 until pairs) out.add(morph(sa[i], sb[i], t))
        for (i in pairs until sa.size) out.add(sa[i].withOpacity(sa[i].opacity * (1f - tc)))
        for (i in pairs until sb.size) out.add(sb[i].withOpacity(sb[i].opacity * tc))
        return out
    }
}

fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
    var inside = false
    val n = poly.size / 2
    var j = n - 1
    for (i in 0 until n) {
        val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
        val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-6f) + xi) inside = !inside
        j = i
    }
    return inside
}

fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax; val dy = by - ay
    val l2 = dx * dx + dy * dy
    val t = if (l2 <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

fun normalizeDeg(a: Float): Float {
    var d = a % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}
