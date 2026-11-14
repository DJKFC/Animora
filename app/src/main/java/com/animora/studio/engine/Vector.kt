package com.animora.studio.engine

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.animora.studio.model.BitmapElement
import com.animora.studio.model.Element
import com.animora.studio.model.FillStyle
import com.animora.studio.model.GradientType
import com.animora.studio.model.InstanceElement
import com.animora.studio.model.MeshBitmapElement
import com.animora.studio.model.PathFillElement
import com.animora.studio.model.StrokeElement
import com.animora.studio.model.TextElement
import com.animora.studio.model.WarpPin
import com.animora.studio.model.lerp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Контуры области (маски) — для векторной заливки. */
object Contour {
    /**
     * Обход границы пикселей маски: рёбра между «внутри» и «снаружи» с внутренней стороной слева,
     * сшиваются в замкнутые контуры (внешние и дырки). Координаты — углы пикселей.
     */
    fun trace(mask: BooleanArray, w: Int, h: Int): List<FloatArray> {
        fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h && mask[y * w + x]
        // ребро: из (x0,y0) в (x1,y1); ключ вершины = y*(w+1)+x
        val starts = HashMap<Int, ArrayList<Int>>()
        val ex0 = ArrayList<Int>(); val ex1 = ArrayList<Int>()
        fun key(x: Int, y: Int) = y * (w + 1) + x
        fun add(x0: Int, y0: Int, x1: Int, y1: Int) {
            val id = ex0.size
            ex0.add(key(x0, y0)); ex1.add(key(x1, y1))
            starts.getOrPut(key(x0, y0)) { ArrayList(1) }.add(id)
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (!mask[y * w + x]) continue
            if (!inside(x, y - 1)) add(x, y, x + 1, y)
            if (!inside(x + 1, y)) add(x + 1, y, x + 1, y + 1)
            if (!inside(x, y + 1)) add(x + 1, y + 1, x, y + 1)
            if (!inside(x - 1, y)) add(x, y + 1, x, y)
        }
        val used = BooleanArray(ex0.size)
        val out = ArrayList<FloatArray>()
        for (s in ex0.indices) {
            if (used[s]) continue
            val pts = FloatList()
            var e = s
            var guard = 0
            while (!used[e] && guard++ < 4_000_000) {
                used[e] = true
                val k = ex0[e]
                pts.add((k % (w + 1)).toFloat()); pts.add((k / (w + 1)).toFloat())
                val nextList = starts[ex1[e]] ?: break
                e = nextList.firstOrNull { !used[it] } ?: break
            }
            if (pts.size >= 6) out.add(simplifyClosed(pts.toArray(), 0.9f))
        }
        return out.filter { it.size >= 6 }
    }

    /** Рамер–Дуглас–Пекер для замкнутого контура. */
    fun simplifyClosed(p: FloatArray, eps: Float): FloatArray {
        val n = p.size / 2
        if (n < 4) return p
        // разрезаем контур в самой дальней от первой точки вершине
        var far = 0; var fd = -1f
        for (i in 0 until n) { val d = hypot(p[i * 2] - p[0], p[i * 2 + 1] - p[1]); if (d > fd) { fd = d; far = i } }
        val a = rdp(p, 0, far, eps); val b = rdp(p, far, n, eps)
        val out = FloatList()
        for (i in 0 until a.size - 2) out.add(a[i])
        for (i in 0 until b.size - 2) out.add(b[i])
        return out.toArray()
    }

    /** RDP открытой ломаной p[from..to] (to может быть = n: замыкание на точку 0). */
    private fun rdp(p: FloatArray, from: Int, to: Int, eps: Float): FloatArray {
        val n = p.size / 2
        fun x(i: Int) = p[(i % n) * 2]
        fun y(i: Int) = p[(i % n) * 2 + 1]
        val keep = BooleanArray(to - from + 1)
        keep[0] = true; keep[keep.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(from, to))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            var md = -1f; var mi = -1
            for (i in a + 1 until b) {
                val d = distToSegment(x(i), y(i), x(a), y(a), x(b), y(b))
                if (d > md) { md = d; mi = i }
            }
            if (md > eps && mi > 0) { keep[mi - from] = true; stack.addLast(intArrayOf(a, mi)); stack.addLast(intArrayOf(mi, b)) }
        }
        val out = FloatList()
        for (i in keep.indices) if (keep[i]) { out.add(x(from + i)); out.add(y(from + i)) }
        return out.toArray()
    }
}

/** Сборка пути векторной заливки (сглаженные квадратичными кривыми углы) и шейдер градиента. */
object FillPath {
    fun get(e: PathFillElement): Path {
        e.cachedPath?.let { return it }
        val path = Path(); path.fillType = Path.FillType.EVEN_ODD
        for (c in e.contours) addSmooth(path, c)
        e.cachedPath = path
        return path
    }

    private fun addSmooth(path: Path, c: FloatArray) {
        val n = c.size / 2
        if (n < 3) return
        fun mx(i: Int) = (c[(i % n) * 2] + c[((i + 1) % n) * 2]) / 2
        fun my(i: Int) = (c[(i % n) * 2 + 1] + c[((i + 1) % n) * 2 + 1]) / 2
        path.moveTo(mx(0), my(0))
        for (i in 1..n) path.quadTo(c[(i % n) * 2], c[(i % n) * 2 + 1], mx(i), my(i))
        path.close()
    }

    fun shader(style: FillStyle, b: RectF): Shader? = when (style.gradient) {
        GradientType.NONE -> null
        GradientType.LINEAR -> {
            val a = Math.toRadians(style.angle.toDouble())
            val r = max(b.width(), b.height()) / 2
            val dx = (cos(a) * r).toFloat(); val dy = (sin(a) * r).toFloat()
            LinearGradient(b.centerX() - dx, b.centerY() - dy, b.centerX() + dx, b.centerY() + dy, style.color, style.color2, Shader.TileMode.CLAMP)
        }
        GradientType.RADIAL -> RadialGradient(b.centerX(), b.centerY(), max(1f, max(b.width(), b.height()) / 2), style.color, style.color2, Shader.TileMode.CLAMP)
    }
}

/** Деформация «булавками» — подобие (similarity) Moving Least Squares, как Asset Warp. */
object Warp {
    fun lerpPins(a: List<WarpPin>, b: List<WarpPin>, t: Float): List<WarpPin> =
        if (a.size != b.size) a else a.indices.map { i -> a[i].copy(x = lerp(a[i].x, b[i].x, t), y = lerp(a[i].y, b[i].y, t)) }

    fun isIdentity(pins: List<WarpPin>) = pins.all { abs(it.x - it.rx) < 0.01f && abs(it.y - it.ry) < 0.01f }

    fun map(pins: List<WarpPin>, x: Float, y: Float, out: FloatArray) {
        var sw = 0f; var psx = 0f; var psy = 0f; var qsx = 0f; var qsy = 0f
        val w = FloatArray(pins.size)
        for (i in pins.indices) {
            val p = pins[i]
            val d2 = (p.rx - x) * (p.rx - x) + (p.ry - y) * (p.ry - y)
            w[i] = 1f / (d2 + 1f)
            sw += w[i]; psx += w[i] * p.rx; psy += w[i] * p.ry; qsx += w[i] * p.x; qsy += w[i] * p.y
        }
        psx /= sw; psy /= sw; qsx /= sw; qsy /= sw
        var mu = 0f; var a = 0f; var b = 0f
        for (i in pins.indices) {
            val p = pins[i]
            val phx = p.rx - psx; val phy = p.ry - psy; val qhx = p.x - qsx; val qhy = p.y - qsy
            mu += w[i] * (phx * phx + phy * phy)
            a += w[i] * (phx * qhx + phy * qhy)
            b += w[i] * (phx * qhy - phy * qhx)
        }
        val dx = x - psx; val dy = y - psy
        if (mu < 1e-6f) { out[0] = x - psx + qsx; out[1] = y - psy + qsy; return }
        a /= mu; b /= mu
        out[0] = a * dx - b * dy + qsx
        out[1] = b * dx + a * dy + qsy
    }

    /** Найти исходное место для точки, которая сейчас (после деформации) находится в (x, y). */
    fun unmap(pins: List<WarpPin>, x: Float, y: Float): FloatArray {
        if (pins.isEmpty() || isIdentity(pins)) return floatArrayOf(x, y)
        var rx = x; var ry = y
        val o = FloatArray(2)
        repeat(20) { map(pins, rx, ry, o); rx += x - o[0]; ry += y - o[1] }
        return floatArrayOf(rx, ry)
    }

    private fun subdivide(c: FloatArray, maxLen: Float): FloatArray {
        val n = c.size / 2
        val out = FloatList()
        for (i in 0 until n) {
            val x0 = c[i * 2]; val y0 = c[i * 2 + 1]; val x1 = c[((i + 1) % n) * 2]; val y1 = c[((i + 1) % n) * 2 + 1]
            val steps = max(1, (hypot(x1 - x0, y1 - y0) / maxLen).roundToInt())
            for (s in 0 until steps) { val t = s.toFloat() / steps; out.add(x0 + (x1 - x0) * t); out.add(y0 + (y1 - y0) * t) }
        }
        return out.toArray()
    }

    fun apply(pins: List<WarpPin>, elements: List<Element>): List<Element> {
        if (pins.isEmpty() || isIdentity(pins)) return elements
        val o = FloatArray(2)
        return elements.map { e ->
            when (e) {
                is StrokeElement -> {
                    val p = e.pts.copyOf()
                    for (i in 0 until e.count) { map(pins, p[i * 3], p[i * 3 + 1], o); p[i * 3] = o[0]; p[i * 3 + 1] = o[1] }
                    StrokeElement(p, e.color, e.size, e.kind, e.opacity, e.tipId)
                }
                is PathFillElement -> PathFillElement(e.contours.map { c ->
                    val d = subdivide(c, 12f)
                    var i = 0; while (i < d.size) { map(pins, d[i], d[i + 1], o); d[i] = o[0]; d[i + 1] = o[1]; i += 2 }
                    d
                }, e.style, e.alpha)
                is BitmapElement -> {
                    val mw = 20; val mh = 20
                    val v = FloatArray((mw + 1) * (mh + 1) * 2)
                    val pt = FloatArray(2)
                    for (j in 0..mh) for (i in 0..mw) {
                        pt[0] = e.bitmap.width * i / mw.toFloat(); pt[1] = e.bitmap.height * j / mh.toFloat()
                        e.matrix.mapPoints(pt)
                        map(pins, pt[0], pt[1], o)
                        val k = (j * (mw + 1) + i) * 2; v[k] = o[0]; v[k + 1] = o[1]
                    }
                    MeshBitmapElement(e.bitmap, mw, mh, v, e.alpha)
                }
                is TextElement, is InstanceElement -> {
                    val org = floatArrayOf(0f, 0f)
                    val m = if (e is TextElement) e.matrix else (e as InstanceElement).matrix
                    m.mapPoints(org)
                    map(pins, org[0], org[1], o)
                    e.transformed(Matrix().apply { setTranslate(o[0] - org[0], o[1] - org[1]) })
                }
                else -> e
            }
        }
    }
}

/** Операции над векторными штрихами: изгиб, ширина, сглаживание, упрощение. */
object StrokeOps {
    private fun cum(s: StrokeElement): FloatArray {
        val c = FloatArray(s.count)
        for (i in 1 until s.count) c[i] = c[i - 1] + hypot(s.x(i) - s.x(i - 1), s.y(i) - s.y(i - 1))
        return c
    }

    fun nearestIndex(s: StrokeElement, x: Float, y: Float): Pair<Int, Float> {
        var bi = 0; var bd = Float.MAX_VALUE
        for (i in 0 until s.count) { val d = hypot(s.x(i) - x, s.y(i) - y); if (d < bd) { bd = d; bi = i } }
        return bi to bd
    }

    private fun falloff(d: Float, r: Float): Float { val u = d / max(1f, r); return if (u >= 1f) 0f else (1 - u * u).let { it * it } }

    /** Изгиб как инструментом «Выделение» в Animate: тянем точку линии, соседние следуют плавно. */
    fun bend(s: StrokeElement, idx: Int, dx: Float, dy: Float, radius: Float): StrokeElement {
        val c = cum(s); val p = s.pts.copyOf()
        for (i in 0 until s.count) {
            val f = falloff(abs(c[i] - c[idx]), radius)
            p[i * 3] += dx * f; p[i * 3 + 1] += dy * f
        }
        return StrokeElement(p, s.color, s.size, s.kind, s.opacity, s.tipId)
    }

    /** Инструмент «Ширина»: factor > 1 — толще вокруг точки, < 1 — тоньше. */
    fun width(s: StrokeElement, idx: Int, factor: Float, radius: Float): StrokeElement {
        val c = cum(s); val p = s.pts.copyOf()
        for (i in 0 until s.count) {
            val f = falloff(abs(c[i] - c[idx]), radius)
            p[i * 3 + 2] = (p[i * 3 + 2] * (1 + (factor - 1) * f)).coerceIn(0.02f, 8f)
        }
        return StrokeElement(p, s.color, s.size, s.kind, s.opacity, s.tipId)
    }

    fun smooth(s: StrokeElement, iterations: Int = 3): StrokeElement {
        var p = s.pts.copyOf()
        val n = s.count
        if (n < 3) return s
        repeat(iterations) {
            val q = p.copyOf()
            for (i in 1 until n - 1) for (k in 0..1) q[i * 3 + k] = p[(i - 1) * 3 + k] * 0.25f + p[i * 3 + k] * 0.5f + p[(i + 1) * 3 + k] * 0.25f
            p = q
        }
        return StrokeElement(p, s.color, s.size, s.kind, s.opacity, s.tipId)
    }

    fun simplify(s: StrokeElement, eps: Float = 1.2f): StrokeElement {
        val n = s.count
        if (n < 4) return s
        val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
        val st = ArrayDeque<IntArray>(); st.addLast(intArrayOf(0, n - 1))
        while (st.isNotEmpty()) {
            val (a, b) = st.removeLast().let { it[0] to it[1] }
            var md = -1f; var mi = -1
            for (i in a + 1 until b) { val d = distToSegment(s.x(i), s.y(i), s.x(a), s.y(a), s.x(b), s.y(b)); if (d > md) { md = d; mi = i } }
            if (md > eps && mi > 0) { keep[mi] = true; st.addLast(intArrayOf(a, mi)); st.addLast(intArrayOf(mi, b)) }
        }
        val out = FloatList()
        for (i in 0 until n) if (keep[i]) { out.add(s.x(i)); out.add(s.y(i)); out.add(s.w(i)) }
        return StrokeElement(out.toArray(), s.color, s.size, s.kind, s.opacity, s.tipId)
    }
}

/** Быстрое размытие (3 прохода скользящего среднего ≈ гаусс) с понижением разрешения. */
object Blur {
    fun blur(src: Bitmap, radius: Float): Bitmap {
        if (radius < 0.5f) return src
        val down = max(1, (radius / 6f).toInt())
        val sw = max(1, src.width / down); val sh = max(1, src.height / down)
        val small = if (down > 1) Bitmap.createScaledBitmap(src, sw, sh, true) else src.copy(Bitmap.Config.ARGB_8888, true)
        val r = max(1, (radius / down / 1.7f).roundToInt())
        val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val a = IntArray(px.size); val rr = IntArray(px.size); val g = IntArray(px.size); val b = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]; val al = (c ushr 24) and 0xFF
            a[i] = al; rr[i] = ((c shr 16) and 0xFF) * al / 255; g[i] = ((c shr 8) and 0xFF) * al / 255; b[i] = (c and 0xFF) * al / 255
        }
        repeat(3) { for (ch in arrayOf(a, rr, g, b)) { boxH(ch, sw, sh, r); boxV(ch, sw, sh, r) } }
        for (i in px.indices) {
            val al = a[i].coerceIn(0, 255)
            px[i] = if (al == 0) 0 else (al shl 24) or ((rr[i] * 255 / al).coerceIn(0, 255) shl 16) or ((g[i] * 255 / al).coerceIn(0, 255) shl 8) or (b[i] * 255 / al).coerceIn(0, 255)
        }
        val out = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)
        return if (down > 1) Bitmap.createScaledBitmap(out, src.width, src.height, true) else out
    }

    private fun boxH(c: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(w); val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w; var sum = 0
            for (i in -r..r) sum += c[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[x] = sum / div
                sum += c[row + min(w - 1, x + r + 1)] - c[row + max(0, x - r)]
            }
            System.arraycopy(tmp, 0, c, row, w)
        }
    }

    private fun boxV(c: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(h); val div = 2 * r + 1
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += c[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                tmp[y] = sum / div
                sum += c[min(h - 1, y + r + 1) * w + x] - c[max(0, y - r) * w + x]
            }
            for (y in 0 until h) c[y * w + x] = tmp[y]
        }
    }

    @Suppress("unused")
    private fun clampExp(v: Float) = exp(v)
}
