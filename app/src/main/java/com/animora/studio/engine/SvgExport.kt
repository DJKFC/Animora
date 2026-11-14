package com.animora.studio.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.util.Base64
import com.animora.studio.model.BgType
import com.animora.studio.model.BitmapElement
import com.animora.studio.model.BrushKind
import com.animora.studio.model.Element
import com.animora.studio.model.GradientType
import com.animora.studio.model.InstanceElement
import com.animora.studio.model.Layer
import com.animora.studio.model.LayerType
import com.animora.studio.model.MeshBitmapElement
import com.animora.studio.model.PathFillElement
import com.animora.studio.model.Project
import com.animora.studio.model.StrokeElement
import com.animora.studio.model.TextAlign
import com.animora.studio.model.TextElement
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Экспорт кадра в SVG: штрихи — линии, заливки — контуры (с градиентами), растр — встроенные PNG, текст — <text>. */
object SvgExport {
    fun frame(p: Project, frame: Int, includeGuides: Boolean): String {
        val sb = StringBuilder()
        val defs = StringBuilder()
        var gid = 0
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${p.width}" height="${p.height}" viewBox="0 0 ${p.width} ${p.height}">""").append('\n')
        val bodyStart = sb.length
        if (p.bgType != BgType.TRANSPARENT) sb.append("""<rect width="100%" height="100%" fill="${hex(p.bgColor)}"/>""").append('\n')

        fun f(v: Float) = "%.2f".format(java.util.Locale.US, v)

        fun emit(e: Element, m: Matrix, alpha: Float, localT: Int, depth: Int) {
            when (e) {
                is StrokeElement -> {
                    if (e.kind == BrushKind.ERASER || e.count == 0) return
                    val pts = e.pts.copyOf(); val xy = FloatArray(e.count * 2) { i -> pts[(i / 2) * 3 + i % 2] }
                    m.mapPoints(xy)
                    var avgW = 0f; for (i in 0 until e.count) avgW += e.w(i); avgW /= e.count
                    val w = max(0.3f, e.size * avgW * m.mapRadius(1f))
                    val op = Color.alpha(e.color) / 255f * e.opacity * alpha
                    sb.append("""<polyline fill="none" stroke="${hex(e.color)}" stroke-opacity="${f(op)}" stroke-width="${f(w)}" stroke-linecap="round" stroke-linejoin="round" points="""")
                    for (i in 0 until e.count) sb.append(f(xy[i * 2])).append(',').append(f(xy[i * 2 + 1])).append(' ')
                    sb.append("\"/>\n")
                }
                is PathFillElement -> {
                    val cs = e.contours.map { c -> c.copyOf().also { m.mapPoints(it) } }
                    var fill = hex(e.style.color)
                    if (e.style.gradient != GradientType.NONE) {
                        val id = "g${gid++}"
                        val b = e.transformed(m).bounds()
                        if (e.style.gradient == GradientType.LINEAR) {
                            val a = Math.toRadians(e.style.angle.toDouble()); val r = max(b.width(), b.height()) / 2
                            val dx = (cos(a) * r).toFloat(); val dy = (sin(a) * r).toFloat()
                            defs.append("""<linearGradient id="$id" gradientUnits="userSpaceOnUse" x1="${f(b.centerX() - dx)}" y1="${f(b.centerY() - dy)}" x2="${f(b.centerX() + dx)}" y2="${f(b.centerY() + dy)}">""")
                        } else defs.append("""<radialGradient id="$id" gradientUnits="userSpaceOnUse" cx="${f(b.centerX())}" cy="${f(b.centerY())}" r="${f(max(b.width(), b.height()) / 2)}">""")
                        defs.append("""<stop offset="0" stop-color="${hex(e.style.color)}"/><stop offset="1" stop-color="${hex(e.style.color2)}"/>""")
                        defs.append(if (e.style.gradient == GradientType.LINEAR) "</linearGradient>\n" else "</radialGradient>\n")
                        fill = "url(#$id)"
                    }
                    sb.append("""<path fill-rule="evenodd" fill="$fill" fill-opacity="${f(Color.alpha(e.style.color) / 255f * e.alpha * alpha)}" d="""")
                    for (c in cs) {
                        var i = 0
                        while (i < c.size) { sb.append(if (i == 0) "M" else "L").append(f(c[i])).append(' ').append(f(c[i + 1])).append(' '); i += 2 }
                        sb.append("Z ")
                    }
                    sb.append("\"/>\n")
                }
                is BitmapElement -> {
                    val mm = Matrix(e.matrix); mm.postConcat(m)
                    val v = FloatArray(9); mm.getValues(v)
                    val bos = ByteArrayOutputStream(); e.bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    sb.append("""<image width="${e.bitmap.width}" height="${e.bitmap.height}" opacity="${f(e.alpha * alpha)}" transform="matrix(${f(v[0])} ${f(v[3])} ${f(v[1])} ${f(v[4])} ${f(v[2])} ${f(v[5])})" xlink:href="data:image/png;base64,""")
                    sb.append(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)).append("\"/>\n")
                }
                is TextElement -> {
                    val mm = Matrix(e.matrix); mm.postConcat(m)
                    val v = FloatArray(9); mm.getValues(v)
                    val anchor = when (e.align) { TextAlign.LEFT -> "start"; TextAlign.CENTER -> "middle"; TextAlign.RIGHT -> "end" }
                    sb.append("""<text transform="matrix(${f(v[0])} ${f(v[3])} ${f(v[1])} ${f(v[4])} ${f(v[2])} ${f(v[5])})" font-size="${f(e.size)}" fill="${hex(e.color)}" fill-opacity="${f(e.alpha * alpha)}" text-anchor="$anchor"""")
                    if (e.bold) sb.append(""" font-weight="bold"""")
                    if (e.italic) sb.append(""" font-style="italic"""")
                    sb.append('>')
                    e.lines.forEachIndexed { i, line -> sb.append("""<tspan x="0" y="${f(i * e.size * 1.2f)}">""").append(escape(line)).append("</tspan>") }
                    sb.append("</text>\n")
                }
                is InstanceElement -> {
                    val sym = p.symbol(e.symbolId) ?: return
                    if (depth > 6) return
                    val sf = e.symbolFrame(localT, sym.frameCount)
                    val mm = Matrix(e.matrix); mm.postConcat(m)
                    emitLayers(p, sym.layers, sf, mm, alpha * e.effect.alpha, depth + 1, sym.layers, ::emit)
                }
                is MeshBitmapElement -> {}
            }
        }

        emitLayers(p, p.layers, frame, Matrix(), 1f, 0, null) { e, m, a, t, d -> emit(e, m, a, t, d) }
        if (defs.isNotEmpty()) sb.insert(bodyStart, "<defs>\n$defs</defs>\n")
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun emitLayers(p: Project, layers: List<Layer>, frame: Int, outer: Matrix, alpha: Float, depth: Int, scope: List<Layer>?, emit: (Element, Matrix, Float, Int, Int) -> Unit) {
        for (l in layers) {
            if (l.type != LayerType.DRAWING || l.isMask || l.guide || !Renderer.effectivelyVisible(layers, l)) continue
            val r = Renderer.resolve(p, l, frame, scope) ?: continue
            val m = Matrix(r.matrix); m.postConcat(outer)
            r.elements.forEach { emit(it, m, alpha * l.opacity * r.alpha, frame - r.keyStart, depth) }
        }
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
