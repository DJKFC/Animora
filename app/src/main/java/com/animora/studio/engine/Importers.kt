package com.animora.studio.engine

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Xml
import com.animora.studio.model.BrushKind
import com.animora.studio.model.Element
import com.animora.studio.model.FillStyle
import com.animora.studio.model.PathFillElement
import com.animora.studio.model.StrokeElement
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Импорт SVG: path, rect, circle, ellipse, line, polyline, polygon, группы, transform, стили. */
object SvgImport {
    private class Style(var fill: Int? = 0xFF000000.toInt(), var stroke: Int? = null, var strokeWidth: Float = 1f, var opacity: Float = 1f, var fillOpacity: Float = 1f, var strokeOpacity: Float = 1f) {
        fun copy() = Style(fill, stroke, strokeWidth, opacity, fillOpacity, strokeOpacity)
    }

    /** Элементы в координатах SVG (до подгонки) и габариты. */
    fun parse(text: String): Pair<List<Element>, RectF> {
        val p = Xml.newPullParser()
        p.setInput(StringReader(text))
        val styles = ArrayDeque<Style>(); styles.addLast(Style())
        val mats = ArrayDeque<Matrix>(); mats.addLast(Matrix())
        val out = ArrayList<Element>()
        var skipDepth = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    val name = p.name.substringAfter(':')
                    if (skipDepth > 0 || name in setOf("defs", "clipPath", "mask", "symbol", "style", "title", "desc", "metadata")) { skipDepth++; continue }
                    val st = styles.last().copy(); applyStyle(p, st)
                    val m = Matrix(mats.last()); p.getAttributeValue(null, "transform")?.let { m.preConcat(parseTransform(it)) }
                    styles.addLast(st); mats.addLast(m)
                    val shapes: List<FloatArray>? = when (name) {
                        "path" -> p.getAttributeValue(null, "d")?.let { PathData.flatten(it) }
                        "rect" -> {
                            val x = num(p, "x"); val y = num(p, "y"); val w = num(p, "width"); val h = num(p, "height")
                            listOf(floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h, x, y)).takeIf { w > 0 && h > 0 }
                        }
                        "circle" -> ellipse(num(p, "cx"), num(p, "cy"), num(p, "r"), num(p, "r"))
                        "ellipse" -> ellipse(num(p, "cx"), num(p, "cy"), num(p, "rx"), num(p, "ry"))
                        "line" -> listOf(floatArrayOf(num(p, "x1"), num(p, "y1"), num(p, "x2"), num(p, "y2")))
                        "polyline", "polygon" -> p.getAttributeValue(null, "points")?.let { pts ->
                            val v = numbers(pts)
                            val a = if (name == "polygon" && v.size >= 4) v + floatArrayOf(v[0], v[1]) else v
                            listOf(a)
                        }
                        else -> null
                    }
                    if (shapes != null && shapes.isNotEmpty()) {
                        val mapped = shapes.map { it.copyOf().also { a -> m.mapPoints(a) } }.filter { it.size >= 4 }
                        val scale = m.mapRadius(1f)
                        val fill = st.fill
                        if (fill != null && name != "line" && name != "polyline") {
                            val a = (((fill ushr 24) and 0xFF) * st.fillOpacity * st.opacity).toInt().coerceIn(0, 255)
                            out.add(PathFillElement(mapped.filter { it.size >= 6 }, FillStyle((fill and 0xFFFFFF) or (a shl 24))))
                        } else if (fill != null && name == "polyline") {
                            val a = (((fill ushr 24) and 0xFF) * st.fillOpacity * st.opacity).toInt().coerceIn(0, 255)
                            out.add(PathFillElement(mapped.filter { it.size >= 6 }, FillStyle((fill and 0xFFFFFF) or (a shl 24))))
                        }
                        val stroke = st.stroke
                        if (stroke != null && st.strokeWidth > 0f) {
                            val a = (((stroke ushr 24) and 0xFF) * st.strokeOpacity * st.opacity) / 255f
                            for (c in mapped) {
                                val n = c.size / 2
                                val pts = FloatArray(n * 3) { i -> when (i % 3) { 0 -> c[(i / 3) * 2]; 1 -> c[(i / 3) * 2 + 1]; else -> 1f } }
                                out.add(StrokeElement(pts, stroke or 0xFF000000.toInt(), max(0.5f, st.strokeWidth * scale), BrushKind.PEN, a.coerceIn(0f, 1f)))
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (skipDepth > 0) { skipDepth--; continue }
                    if (styles.size > 1) { styles.removeLast(); mats.removeLast() }
                }
            }
        }
        val r = RectF(); var first = true
        out.forEach { e -> val b = e.bounds(); if (b.width() >= 0) { if (first) { r.set(b); first = false } else r.union(b) } }
        return out to r
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): List<FloatArray>? {
        if (rx <= 0f || ry <= 0f) return null
        val n = 64
        return listOf(FloatArray((n + 1) * 2) { i -> val a = (i / 2) * 2 * PI / n; if (i % 2 == 0) cx + rx * cos(a).toFloat() else cy + ry * sin(a).toFloat() })
    }

    private fun num(p: XmlPullParser, n: String): Float = p.getAttributeValue(null, n)?.let { numbers(it).firstOrNull() } ?: 0f

    fun numbers(s: String): FloatArray {
        val out = ArrayList<Float>()
        Regex("[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?").findAll(s).forEach { out.add(it.value.toFloat()) }
        return out.toFloatArray()
    }

    private fun applyStyle(p: XmlPullParser, st: Style) {
        val props = HashMap<String, String>()
        for (k in listOf("fill", "stroke", "stroke-width", "opacity", "fill-opacity", "stroke-opacity")) p.getAttributeValue(null, k)?.let { props[k] = it }
        p.getAttributeValue(null, "style")?.split(';')?.forEach { kv ->
            val i = kv.indexOf(':'); if (i > 0) props[kv.substring(0, i).trim()] = kv.substring(i + 1).trim()
        }
        props["fill"]?.let { st.fill = parseColor(it) }
        props["stroke"]?.let { st.stroke = parseColor(it) }
        props["stroke-width"]?.let { st.strokeWidth = numbers(it).firstOrNull() ?: st.strokeWidth }
        props["opacity"]?.let { st.opacity *= numbers(it).firstOrNull() ?: 1f }
        props["fill-opacity"]?.let { st.fillOpacity = numbers(it).firstOrNull() ?: 1f }
        props["stroke-opacity"]?.let { st.strokeOpacity = numbers(it).firstOrNull() ?: 1f }
    }

    private val NAMED = mapOf(
        "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF, "yellow" to 0xFFFF00,
        "orange" to 0xFFA500, "purple" to 0x800080, "gray" to 0x808080, "grey" to 0x808080, "pink" to 0xFFC0CB, "brown" to 0xA52A2A,
        "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "lime" to 0x00FF00, "navy" to 0x000080, "silver" to 0xC0C0C0, "gold" to 0xFFD700
    )

    fun parseColor(s0: String): Int? {
        val s = s0.trim().lowercase()
        if (s == "none" || s == "transparent" || s.startsWith("url(")) return if (s.startsWith("url(")) 0xFF888888.toInt() else null
        if (s.startsWith("#")) {
            val h = s.substring(1)
            return when (h.length) {
                3 -> (0xFF000000.toInt()) or (h.map { c -> c.digitToInt(16) * 17 }.let { (r, g, b) -> (r shl 16) or (g shl 8) or b })
                6 -> (0xFF000000 or h.toLong(16)).toInt()
                8 -> h.toLong(16).let { v -> (((v and 0xFF) shl 24) or (v ushr 8)).toInt() }
                else -> null
            }
        }
        if (s.startsWith("rgb")) {
            val v = numbers(s)
            if (v.size >= 3) {
                val pct = s.contains('%')
                fun c(x: Float) = (if (pct) x * 2.55f else x).toInt().coerceIn(0, 255)
                val a = if (v.size >= 4) (v[3] * 255).toInt().coerceIn(0, 255) else 255
                return (a shl 24) or (c(v[0]) shl 16) or (c(v[1]) shl 8) or c(v[2])
            }
        }
        return NAMED[s]?.let { it or 0xFF000000.toInt() }
    }

    fun parseTransform(s: String): Matrix {
        val m = Matrix()
        Regex("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)").findAll(s).forEach { r ->
            val v = numbers(r.groupValues[2])
            val t = Matrix()
            when (r.groupValues[1]) {
                "matrix" -> if (v.size >= 6) t.setValues(floatArrayOf(v[0], v[2], v[4], v[1], v[3], v[5], 0f, 0f, 1f))
                "translate" -> t.setTranslate(v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f })
                "scale" -> t.setScale(v.getOrElse(0) { 1f }, v.getOrElse(1) { v.getOrElse(0) { 1f } })
                "rotate" -> if (v.size >= 3) t.setRotate(v[0], v[1], v[2]) else t.setRotate(v.getOrElse(0) { 0f })
                "skewX" -> t.setSkew(tan(Math.toRadians(v.getOrElse(0) { 0f }.toDouble())).toFloat(), 0f)
                "skewY" -> t.setSkew(0f, tan(Math.toRadians(v.getOrElse(0) { 0f }.toDouble())).toFloat())
            }
            m.preConcat(t)
        }
        return m
    }
}

/** Разбор атрибута d в ломаные (кривые и дуги разбиваются на отрезки). */
object PathData {
    fun flatten(d: String): List<FloatArray> {
        val tokens = Regex("[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
        val out = ArrayList<FloatArray>()
        var cur = FloatList()
        var i = 0; var cmd = 'M'
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
        var lcx = 0f; var lcy = 0f; var lastCmd = ' '
        fun isCmd(t: String) = t.length == 1 && t[0].isLetter()
        fun n(): Float = tokens.getOrNull(i++)?.toFloatOrNull() ?: 0f
        fun line(nx: Float, ny: Float) { cur.add(nx); cur.add(ny); x = nx; y = ny }
        fun flush() { if (cur.size >= 4) out.add(cur.toArray()); cur = FloatList() }
        fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, ex: Float, ey: Float) {
            val x0 = x; val y0 = y
            for (k in 1..16) {
                val t = k / 16f; val u = 1 - t
                line(u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * ex, u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * ey)
            }
        }
        fun quad(x1: Float, y1: Float, ex: Float, ey: Float) {
            val x0 = x; val y0 = y
            for (k in 1..12) { val t = k / 12f; val u = 1 - t; line(u * u * x0 + 2 * u * t * x1 + t * t * ex, u * u * y0 + 2 * u * t * y1 + t * t * ey) }
        }
        while (i < tokens.size) {
            if (isCmd(tokens[i])) { cmd = tokens[i][0]; i++ }
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0f; val oy = if (rel) y else 0f
            when (cmd.uppercaseChar()) {
                'M' -> { flush(); val nx = n() + ox; val ny = n() + oy; x = nx; y = ny; sx = nx; sy = ny; cur.add(nx); cur.add(ny); cmd = if (rel) 'l' else 'L' }
                'L' -> line(n() + ox, n() + oy)
                'H' -> line(n() + ox, y)
                'V' -> line(x, n() + oy)
                'C' -> { val x1 = n() + ox; val y1 = n() + oy; val x2 = n() + ox; val y2 = n() + oy; val ex = n() + ox; val ey = n() + oy; cubic(x1, y1, x2, y2, ex, ey); lcx = x2; lcy = y2 }
                'S' -> {
                    val x1 = if (lastCmd in "CcSs") 2 * x - lcx else x; val y1 = if (lastCmd in "CcSs") 2 * y - lcy else y
                    val x2 = n() + ox; val y2 = n() + oy; val ex = n() + ox; val ey = n() + oy; cubic(x1, y1, x2, y2, ex, ey); lcx = x2; lcy = y2
                }
                'Q' -> { val x1 = n() + ox; val y1 = n() + oy; val ex = n() + ox; val ey = n() + oy; quad(x1, y1, ex, ey); lcx = x1; lcy = y1 }
                'T' -> {
                    val x1 = if (lastCmd in "QqTt") 2 * x - lcx else x; val y1 = if (lastCmd in "QqTt") 2 * y - lcy else y
                    val ex = n() + ox; val ey = n() + oy; quad(x1, y1, ex, ey); lcx = x1; lcy = y1
                }
                'A' -> { val rx = n(); val ry = n(); val rot = n(); val large = n() != 0f; val sweep = n() != 0f; val ex = n() + ox; val ey = n() + oy; arc(x, y, rx, ry, rot, large, sweep, ex, ey) { px, py -> line(px, py) } }
                'Z' -> { line(sx, sy); flush(); cur.add(sx); cur.add(sy); x = sx; y = sy }
                else -> i++
            }
            lastCmd = cmd
        }
        flush()
        return out
    }

    /** Эллиптическая дуга SVG (преобразование из конечных точек в центр). */
    private fun arc(x1: Float, y1: Float, rx0: Float, ry0: Float, rotDeg: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float, emit: (Float, Float) -> Unit) {
        var rx = abs(rx0); var ry = abs(ry0)
        if (rx == 0f || ry == 0f) { emit(x2, y2); return }
        val phi = Math.toRadians(rotDeg.toDouble())
        val cp = cos(phi); val sp = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val x1p = cp * dx + sp * dy; val y1p = -sp * dx + cp * dy
        val lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lam > 1) { val s = sqrt(lam).toFloat(); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt(max(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0; val cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val d = (ux * vx + uy * vy) / (sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy))
            val a = acos(d.coerceIn(-1.0, 1.0)); return if (ux * vy - uy * vx < 0) -a else a
        }
        val th1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * PI else if (sweep && dth < 0) dth += 2 * PI
        val steps = max(4, (abs(dth) / (PI / 16)).toInt())
        for (k in 1..steps) {
            val t = th1 + dth * k / steps
            val ex = cx + rx * cos(t) * cp - ry * sin(t) * sp
            val ey = cy + rx * cos(t) * sp + ry * sin(t) * cp
            emit(ex.toFloat(), ey.toFloat())
        }
    }
}

/** Импорт PSD (8 бит, RGB): каждый слой Photoshop → слой Animora со своим положением и прозрачностью. */
object PsdImport {
    class PsdLayer(val name: String, val left: Int, val top: Int, val bitmap: Bitmap, val opacity: Float, val visible: Boolean)
    class Psd(val width: Int, val height: Int, val layers: List<PsdLayer>)

    fun parse(bytes: ByteArray): Psd {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "8BPS") { "это не PSD" }
        b.position(4)
        val version = b.short.toInt(); require(version == 1) { "PSB (большие документы) не поддерживается" }
        b.position(12)
        val channels = b.short.toInt(); val height = b.int; val width = b.int
        val depth = b.short.toInt(); val mode = b.short.toInt()
        require(depth == 8) { "поддерживается только 8 бит на канал (в файле $depth)" }
        require(mode == 3) { "поддерживается только режим RGB" }
        b.position(b.position() + b.int)   // color mode data
        b.position(b.position() + b.int)   // image resources
        val lmLen = b.int.toLong() and 0xFFFFFFFFL
        val result = ArrayList<PsdLayer>()
        if (lmLen > 0) {
            val liLen = b.int.toLong() and 0xFFFFFFFFL
            if (liLen > 0) {
                val count = abs(b.short.toInt())
                class Rec(val t: Int, val l: Int, val bm: Int, val r: Int, val ch: List<Pair<Int, Int>>, val op: Int, val vis: Boolean, val name: String)
                val recs = ArrayList<Rec>()
                for (li in 0 until count) {
                    val t = b.int; val l = b.int; val bm = b.int; val r = b.int
                    val nch = b.short.toInt()
                    val ch = (0 until nch).map { b.short.toInt() to b.int }
                    b.position(b.position() + 8)       // сигнатура + режим наложения
                    val op = b.get().toInt() and 0xFF
                    b.get()                            // clipping
                    val flags = b.get().toInt() and 0xFF
                    b.get()
                    val extra = b.int; val extraEnd = b.position() + extra
                    b.position(b.position() + b.int)   // маска
                    b.position(b.position() + b.int)   // blending ranges
                    val nl = b.get().toInt() and 0xFF
                    val name = String(bytes, b.position(), nl, Charsets.ISO_8859_1)
                    b.position(extraEnd)
                    recs.add(Rec(t, l, bm, r, ch, op, (flags and 0x02) == 0, name))
                }
                for (rec in recs) {
                    val w = rec.r - rec.l; val h = rec.bm - rec.t
                    val planes = HashMap<Int, ByteArray>()
                    for ((id, len) in rec.ch) {
                        val start = b.position()
                        val comp = b.short.toInt()
                        if (w > 0 && h > 0 && id >= -1) planes[id] = when (comp) {
                            0 -> ByteArray(w * h).also { b.get(it) }
                            1 -> rle(b, w, h)
                            else -> ByteArray(w * h)
                        }
                        b.position(start + len)
                    }
                    if (w <= 0 || h <= 0 || planes.isEmpty()) continue
                    val px = IntArray(w * h)
                    val rP = planes[0]; val gP = planes[1]; val bP = planes[2]; val aP = planes[-1]
                    for (i in px.indices) {
                        val a = aP?.get(i)?.toInt()?.and(0xFF) ?: 255
                        px[i] = (a shl 24) or ((rP?.get(i)?.toInt()?.and(0xFF) ?: 0) shl 16) or ((gP?.get(i)?.toInt()?.and(0xFF) ?: 0) shl 8) or (bP?.get(i)?.toInt()?.and(0xFF) ?: 0)
                    }
                    if (rec.name == "</Layer group>" || rec.name == "</Layer set>") continue
                    result.add(PsdLayer(rec.name.ifBlank { "Слой" }, rec.l, rec.t, Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888), rec.op / 255f, rec.vis))
                }
            }
        }
        if (result.isEmpty()) {
            // нет слоёв — берём сведённое изображение в конце файла
            b.position(26); b.position(b.position() + b.int); b.position(b.position() + b.int)
            b.position((b.position() + (b.int.toLong() and 0xFFFFFFFFL)).toInt())
            val comp = b.short.toInt()
            val planes = ArrayList<ByteArray>()
            if (comp == 1) {
                val counts = IntArray(height * channels) { b.short.toInt() and 0xFFFF }
                for (c in 0 until channels) planes.add(packbits(b, width, height, counts, c * height))
            } else for (c in 0 until channels) planes.add(ByteArray(width * height).also { b.get(it) })
            val px = IntArray(width * height) { i ->
                val a = if (planes.size > 3) planes[3][i].toInt() and 0xFF else 255
                (a shl 24) or ((planes[0][i].toInt() and 0xFF) shl 16) or ((planes[1][i].toInt() and 0xFF) shl 8) or (planes[2][i].toInt() and 0xFF)
            }
            result.add(PsdLayer("Изображение", 0, 0, Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888), 1f, true))
        }
        return Psd(width, height, result)
    }

    private fun rle(b: ByteBuffer, w: Int, h: Int): ByteArray {
        val counts = IntArray(h) { b.short.toInt() and 0xFFFF }
        return packbits(b, w, h, counts, 0)
    }

    private fun packbits(b: ByteBuffer, w: Int, h: Int, counts: IntArray, off: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val end = b.position() + counts[off + y]
            var x = 0
            while (b.position() < end && x < w) {
                val n = b.get().toInt()
                if (n >= 0) { val c = min(n + 1, w - x); for (k in 0 until n + 1) { val v = b.get(); if (k < c) out[y * w + x + k] = v }; x += c }
                else if (n != -128) { val v = b.get(); val c = min(1 - n, w - x); for (k in 0 until c) out[y * w + x + k] = v; x += c }
            }
            b.position(end)
        }
        return out
    }
}
