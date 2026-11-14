package com.animora.studio.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import com.animora.studio.model.BgType
import com.animora.studio.model.BitmapElement
import com.animora.studio.model.BrushKind
import com.animora.studio.model.BrushRegistry
import com.animora.studio.model.CurveProp
import com.animora.studio.model.Keyframe
import com.animora.studio.model.InstanceElement
import com.animora.studio.model.TextAlign
import com.animora.studio.model.TextElement
import com.animora.studio.model.TextFont
import com.animora.studio.model.CustomBrush
import com.animora.studio.model.Element
import com.animora.studio.model.Layer
import com.animora.studio.model.LayerType
import com.animora.studio.model.Project
import com.animora.studio.model.StrokeElement
import com.animora.studio.model.Transform
import com.animora.studio.model.TweenType
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

class FrameOptions(
    val background: Boolean = true,
    val includeGuides: Boolean = true,
    val onion: Boolean = false,
    val onionPrev: Int = 2,
    val onionNext: Int = 1,
    val onionOpacity: Float = 0.35f,
    val onionAllLayers: Boolean = false,
    val onionKeysOnly: Boolean = true,
    /** В редакторе — показывать фигуры масок полупрозрачными. */
    val maskPreview: Boolean = false,
    val onionTint: Boolean = true,
    val activeLayerId: String? = null,
    val onlyLayerId: String? = null
)

private val SYMBOL_OPT = FrameOptions(background = false, includeGuides = false)

class Resolved(val elements: List<Element>, val matrix: Matrix, val alpha: Float, val keyStart: Int = 0)

/** Рендерер кадра. Экземпляр не потокобезопасен — у каждого потока свой. */
class Renderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val layerPaint = Paint()
    private val dstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    companion object {
        fun configureText(p: Paint, e: TextElement) {
            p.reset(); p.isAntiAlias = true
            p.textSize = e.size
            val style = when { e.bold && e.italic -> Typeface.BOLD_ITALIC; e.bold -> Typeface.BOLD; e.italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
            val family = when (e.font) {
                TextFont.SANS -> "sans-serif"; TextFont.SERIF -> "serif"; TextFont.MONO -> "monospace"
                TextFont.HAND -> "casual"; TextFont.CONDENSED -> "sans-serif-condensed"
            }
            p.typeface = Typeface.create(family, style)
            p.textAlign = when (e.align) { TextAlign.LEFT -> Paint.Align.LEFT; TextAlign.CENTER -> Paint.Align.CENTER; TextAlign.RIGHT -> Paint.Align.RIGHT }
        }

        init {
            val mp = Paint(Paint.ANTI_ALIAS_FLAG)
            TextElement.measure = { e -> synchronized(mp) { configureText(mp, e); e.lines.maxOfOrNull { mp.measureText(it) } ?: 0f } }
        }

        const val ONION_PREV = 0xFFE53950.toInt()
        const val ONION_NEXT = 0xFF2BB673.toInt()
        @Volatile private var paper: Bitmap? = null

        fun paperTile(): Bitmap {
            paper?.let { return it }
            val s = 256
            val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val px = IntArray(s * s)
            val rnd = Random(7)
            for (i in px.indices) {
                val v = rnd.nextInt(0, 26)
                val fiber = if (rnd.nextInt(0, 90) == 0) 22 else 0
                px[i] = Color.argb(v + fiber, 90, 70, 40)
            }
            b.setPixels(px, 0, s, 0, 0, s, s)
            paper = b
            return b
        }

        fun keyTransformAt(layer: Layer, frame: Int): Transform? {
            val e = layer.keyframes.floorEntry(frame) ?: return null
            val k = e.value
            val next = layer.keyframes.higherEntry(e.key)
            if (next != null && k.tween != TweenType.NONE && frame > e.key) {
                return tweenTransform(k, next.value, (frame - e.key).toFloat() / (next.key - e.key))
            }
            return k.transform
        }

        /** Трансформация твина с учётом кривых по свойствам. */
        fun tweenTransform(k: Keyframe, next: Keyframe, raw: Float): Transform {
            val base = Transform.lerpProps(
                k.transform, next.transform,
                k.ease(CurveProp.POSITION, raw), k.ease(CurveProp.ROTATION, raw),
                k.ease(CurveProp.SCALE, raw), k.ease(CurveProp.ALPHA, raw)
            )
            val g = k.guide ?: return base
            if (g.size < 4) return base
            // движение по направляющей: центр объекта идёт по кривой, скорость — по кривой позиции
            val p = pathAt(g, k.ease(CurveProp.POSITION, raw))
            var r = base.copy(tx = p[0] - base.px, ty = p[1] - base.py)
            if (k.orientToPath) r = r.copy(rot = base.rot + normalizeDeg(p[2] - pathAt(g, 0f)[2]))
            return r
        }

        /** Точка и угол касательной (°) на ломаной xy-пар по доле длины t. */
        fun pathAt(g: FloatArray, t: Float): FloatArray {
            val n = g.size / 2
            var total = 0f
            for (i in 1 until n) total += hypot(g[i * 2] - g[i * 2 - 2], g[i * 2 + 1] - g[i * 2 - 1])
            if (total <= 0f) return floatArrayOf(g[0], g[1], 0f)
            var d = t.coerceIn(0f, 1f) * total
            for (i in 1 until n) {
                val ax = g[i * 2 - 2]; val ay = g[i * 2 - 1]; val bx = g[i * 2]; val by = g[i * 2 + 1]
                val len = hypot(bx - ax, by - ay)
                if (d <= len || i == n - 1) {
                    val u = if (len <= 0f) 0f else (d / len).coerceIn(0f, 1f)
                    val ang = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()
                    return floatArrayOf(ax + (bx - ax) * u, ay + (by - ay) * u, ang)
                }
                d -= len
            }
            return floatArrayOf(g[g.size - 2], g[g.size - 1], 0f)
        }

        /** Цепочка родительских слоёв: их трансформации ключей применяются к дочернему слою. */
        fun parentMatrix(layers: List<Layer>, layer: Layer, frame: Int): Matrix? {
            var cur = layers.firstOrNull { it.id == layer.parentId }
            var m: Matrix? = null
            var guard = 0
            while (cur != null && guard++ < 16) {
                val pm = (keyTransformAt(cur, frame) ?: Transform()).matrix()
                if (m == null) m = pm else m.postConcat(pm)
                val pid = cur.parentId
                cur = layers.firstOrNull { it.id == pid }
            }
            return m
        }

        /** Видим ли слой с учётом папок. */
        fun effectivelyVisible(layers: List<Layer>, layer: Layer): Boolean {
            if (!layer.visible) return false
            var f = layers.firstOrNull { it.id == layer.folderId }
            var guard = 0
            while (f != null && guard++ < 16) {
                if (!f.visible) return false
                val fid = f.folderId
                f = layers.firstOrNull { it.id == fid }
            }
            return true
        }

        /** Матрица отображения слоя в кадре: локальные координаты → документ. */
        fun layerMatrix(project: Project, layer: Layer, frame: Int): Matrix {
            val m = (keyTransformAt(layer, frame) ?: Transform()).matrix()
            parentMatrix(project.layers, layer, frame)?.let { m.postConcat(it) }
            Bones.bindMatrix(project, layer, frame)?.let { m.postConcat(it) }
            cameraMatrix(project, frame, layer.depth)?.let { m.postConcat(it) }
            return m
        }

        /** Активный слой камеры (первый видимый). */
        fun cameraLayer(project: Project): Layer? = project.layers.firstOrNull { it.type == LayerType.CAMERA && it.visible }

        /**
         * Матрица камеры: сцена → кадр. tx/ty — панорама, sx — зум, rot — поворот.
         * depth > 0 — слой дальше, двигается и масштабируется меньше (параллакс); depth < 0 — закреплён на экране.
         */
        fun cameraMatrix(project: Project, frame: Int, depth: Float = 0f): Matrix? {
            if (depth < 0f) return null
            val cam = cameraLayer(project) ?: return null
            val t = keyTransformAt(cam, frame) ?: return null
            if (t.tx == 0f && t.ty == 0f && t.rot == 0f && t.sx == 1f) return null
            val k = 1f / (1f + depth)
            val cx = project.width / 2f; val cy = project.height / 2f
            val zoom = max(0.01f, 1f + (t.sx - 1f) * k)
            return Matrix().apply {
                setTranslate(-(cx + t.tx * k), -(cy + t.ty * k))
                postRotate(-t.rot)
                postScale(zoom, zoom)
                postTranslate(cx, cy)
            }
        }

        /** scope != null — слой внутри символа: кости ищутся среди слоёв символа, камера не действует. */
        fun resolve(project: Project, layer: Layer, frame: Int, scope: List<Layer>? = null): Resolved? {
            val e = layer.keyframes.floorEntry(frame) ?: return null
            val k = e.value
            val next = layer.keyframes.higherEntry(e.key)
            var elements: List<Element> = k.elements
            var tr = k.transform
            if (next != null && k.tween != TweenType.NONE && frame > e.key) {
                val raw = (frame - e.key).toFloat() / (next.key - e.key)
                tr = tweenTransform(k, next.value, raw)
                if (k.tween == TweenType.SHAPE) elements = Interp.morphElements(k.elements, next.value.elements, k.ease(CurveProp.SHAPE, raw))
            }
            val m = tr.matrix()
            parentMatrix(scope ?: project.layers, layer, frame)?.let { m.postConcat(it) }
            Bones.bindMatrix(project, layer, frame, scope)?.let { m.postConcat(it) }
            if (scope == null) cameraMatrix(project, frame, layer.depth)?.let { m.postConcat(it) }
            return Resolved(elements, m, tr.alpha, e.key)
        }
    }

    fun drawBackground(c: Canvas, p: Project) {
        when (p.bgType) {
            BgType.SOLID -> { paint.reset(); paint.color = p.bgColor; c.drawRect(0f, 0f, p.width.toFloat(), p.height.toFloat(), paint) }
            BgType.PAPER -> {
                paint.reset(); paint.color = p.bgColor
                c.drawRect(0f, 0f, p.width.toFloat(), p.height.toFloat(), paint)
                paint.shader = BitmapShader(paperTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                c.drawRect(0f, 0f, p.width.toFloat(), p.height.toFloat(), paint)
                paint.shader = null
            }
            BgType.TRANSPARENT -> {}
        }
    }

    private var proj: Project? = null
    private var localT = 0
    private var symDepth = 0

    fun drawFrame(c: Canvas, p: Project, frame: Int, opt: FrameOptions) {
        proj = p
        if (opt.background) drawBackground(c, p)
        drawLayerList(c, p, p.layers, frame, opt, null)
    }

    /** Отрисовка набора слоёв (сцены или символа). */
    private fun drawLayerList(c: Canvas, p: Project, layers: List<Layer>, frame: Int, opt: FrameOptions, scope: List<Layer>?) {
        for (layer in layers) {
            if (layer.type != LayerType.DRAWING || !effectivelyVisible(layers, layer)) continue
            if (opt.onlyLayerId != null && layer.id != opt.onlyLayerId) continue
            if (layer.guide && !opt.includeGuides) continue
            if (layer.isMask) {
                // маска не выводится; в редакторе — лёгкая голубая подсказка формы
                if (opt.maskPreview) drawLayer(c, p, layer, frame, 0.25f, 0xFF4FC3F7.toInt(), scope = scope)
                continue
            }
            if (opt.onion && (opt.onionAllLayers || layer.id == opt.activeLayerId)) drawOnion(c, p, layer, frame, opt)
            val mask = layers.firstOrNull { it.id == layer.maskedBy }?.takeIf { it.isMask && it.visible && it.type == LayerType.DRAWING }
            if (mask == null) { drawLayer(c, p, layer, frame, 1f, null, scope = scope); continue }
            val s1 = c.saveLayer(null, null)
            drawLayer(c, p, layer, frame, 1f, null, scope = scope)
            val mp = Paint().apply { xfermode = dstIn }
            val s2 = c.saveLayer(null, mp)
            drawLayer(c, p, mask, frame, 1f, null, forMask = true, scope = scope)
            c.restoreToCount(s2)
            c.restoreToCount(s1)
        }
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawText(c: Canvas, e: TextElement) {
        configureText(textPaint, e)
        val s = c.save()
        c.concat(e.matrix)
        e.lines.forEachIndexed { i, line ->
            val y = i * e.size * 1.2f
            if (e.outline > 0f) {
                textPaint.style = Paint.Style.STROKE; textPaint.strokeWidth = e.outline * 2
                textPaint.color = e.outlineColor; textPaint.alpha = (Color.alpha(e.outlineColor) * e.alpha).toInt().coerceIn(0, 255)
                c.drawText(line, 0f, y, textPaint)
            }
            textPaint.style = Paint.Style.FILL
            textPaint.color = e.color; textPaint.alpha = (Color.alpha(e.color) * e.alpha).toInt().coerceIn(0, 255)
            c.drawText(line, 0f, y, textPaint)
        }
        c.restoreToCount(s)
    }

    /** Экземпляр символа: кадр символа по локальному времени ключа, цветовой эффект, вложенность до 6 уровней. */
    private fun drawInstance(c: Canvas, e: InstanceElement) {
        val p = proj ?: return
        val sym = p.symbol(e.symbolId) ?: return
        if (symDepth > 6) return
        val sf = e.symbolFrame(localT, sym.frameCount)
        val save = if (e.effect.isDefault) c.save() else {
            val fx = e.effect
            val a = fx.tintAmount.coerceIn(0f, 1f); val br = fx.brightness * 255f
            val cm = ColorMatrix(floatArrayOf(
                1 - a, 0f, 0f, 0f, Color.red(fx.tint) * a + br,
                0f, 1 - a, 0f, 0f, Color.green(fx.tint) * a + br,
                0f, 0f, 1 - a, 0f, Color.blue(fx.tint) * a + br,
                0f, 0f, 0f, 1f, 0f
            ))
            val fp = Paint().apply { colorFilter = ColorMatrixColorFilter(cm); alpha = (255 * fx.alpha).toInt().coerceIn(0, 255) }
            c.saveLayer(null, fp)
        }
        c.concat(e.matrix)
        symDepth++
        val oldT = localT
        drawLayerList(c, p, sym.layers, sf, SYMBOL_OPT, sym.layers)
        localT = oldT
        symDepth--
        c.restoreToCount(save)
    }

    private val maskPaint = Paint()
    private val dstIn = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

    private fun drawOnion(c: Canvas, p: Project, layer: Layer, frame: Int, opt: FrameOptions) {
        val prev = ArrayList<Int>(); val next = ArrayList<Int>()
        if (opt.onionKeysOnly) {
            val cur = layer.keyframes.floorKey(frame)
            var k: Int? = if (cur == null) layer.keyframes.lowerKey(frame) else layer.keyframes.lowerKey(cur)
            while (k != null && prev.size < opt.onionPrev) { prev.add(k); k = layer.keyframes.lowerKey(k) }
            k = layer.keyframes.higherKey(frame)
            while (k != null && next.size < opt.onionNext) { next.add(k); k = layer.keyframes.higherKey(k) }
        } else {
            for (i in 1..opt.onionPrev) if (frame - i >= 0) prev.add(frame - i)
            for (i in 1..opt.onionNext) if (frame + i < p.frameCount) next.add(frame + i)
        }
        prev.forEachIndexed { i, f ->
            val a = opt.onionOpacity * (1f - i.toFloat() / (opt.onionPrev + 1))
            drawLayer(c, p, layer, f, a, if (opt.onionTint) ONION_PREV else null)
        }
        next.forEachIndexed { i, f ->
            val a = opt.onionOpacity * (1f - i.toFloat() / (opt.onionNext + 1))
            drawLayer(c, p, layer, f, a, if (opt.onionTint) ONION_NEXT else null)
        }
    }

    fun drawLayer(c: Canvas, p: Project, layer: Layer, frame: Int, alphaMul: Float, tint: Int?, forMask: Boolean = false, scope: List<Layer>? = null) {
        proj = proj ?: p
        val r = resolve(p, layer, frame, scope) ?: return
        if (r.elements.isEmpty()) return
        val prevT = localT
        localT = frame - r.keyStart
        try { drawResolved(c, layer, r, alphaMul, tint, forMask) } finally { localT = prevT }
    }

    private fun drawResolved(c: Canvas, layer: Layer, r: Resolved, alphaMul: Float, tint: Int?, forMask: Boolean) {
        val alpha = if (forMask) 1f else layer.opacity * r.alpha * alphaMul
        val needsLayer = tint != null || alpha < 0.999f || (layer.blend.mode != null && !forMask) ||
            r.elements.any { it is StrokeElement && it.kind == BrushKind.ERASER }
        if (!needsLayer) {
            val s = c.save(); c.concat(r.matrix)
            for (e in r.elements) drawElement(c, e)
            c.restoreToCount(s)
            return
        }
        layerPaint.reset()
        layerPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        layerPaint.xfermode = if (tint == null && !forMask) layer.blend.mode?.let { PorterDuffXfermode(it) } else null
        val save = c.saveLayer(null, layerPaint)
        c.concat(r.matrix)
        for (e in r.elements) drawElement(c, e)
        if (tint != null) c.drawColor(tint, PorterDuff.Mode.SRC_ATOP)
        c.restoreToCount(save)
    }

    fun drawElement(c: Canvas, e: Element) {
        when (e) {
            is StrokeElement -> {
                if (e.count == 0) return
                if (e.kind == BrushKind.CUSTOM) BrushRegistry.get(e.tipId)?.let { drawStamps(c, e, it); return }
                val path = StrokePath.get(e)
                paint.reset()
                paint.isAntiAlias = true
                paint.style = Paint.Style.FILL
                if (e.kind == BrushKind.ERASER) {
                    paint.color = Color.BLACK
                    paint.alpha = (255 * e.opacity).toInt().coerceIn(0, 255)
                    paint.xfermode = dstOut
                } else {
                    paint.color = e.color
                    paint.alpha = (Color.alpha(e.color) * e.opacity).toInt().coerceIn(0, 255)
                    if (e.kind == BrushKind.SOFT) paint.maskFilter = BlurMaskFilter(max(1f, e.size * 0.35f), BlurMaskFilter.Blur.NORMAL)
                }
                c.drawPath(path, paint)
            }
            is InstanceElement -> drawInstance(c, e)
            is TextElement -> drawText(c, e)
            is BitmapElement -> {
                bmpPaint.alpha = (255 * e.alpha).toInt().coerceIn(0, 255)
                c.drawBitmap(e.bitmap, e.matrix, bmpPaint)
            }
        }
    }

    private val dabMatrix = Matrix()
    private val dabPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stampLayerPaint = Paint()

    /**
     * Штамповая кисть (как в Krita): отпечатки кончика вдоль штриха с шагом spacing,
     * поворотом по направлению/случайным, разбросом. Поток (flow) — на отпечаток,
     * непрозрачность — предел для всего штриха (через отдельный слой).
     */
    private fun drawStamps(c: Canvas, e: StrokeElement, b: CustomBrush) {
        val masks = b.drawMasks()
        if (masks.isEmpty()) return
        val bounds = e.bounds()
        val pad = e.size * (1f + b.scatter)
        bounds.inset(-pad, -pad)
        stampLayerPaint.reset()
        stampLayerPaint.alpha = (e.opacity * Color.alpha(e.color)).toInt().coerceIn(0, 255)
        val save = c.saveLayer(bounds, stampLayerPaint)
        dabPaint.reset(); dabPaint.isAntiAlias = true; dabPaint.isFilterBitmap = true
        dabPaint.color = e.color or 0xFF000000.toInt()
        dabPaint.alpha = (255 * b.flow).toInt().coerceIn(1, 255)
        var seed = e.pts.size * 7919L + (e.x(0) * 13f).toLong()
        fun rnd(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33).toInt() and 0xFFFFFF) / 16777216f
        }
        fun dab(x: Float, y: Float, w: Float, dir: Float) {
            val s = e.size * w
            if (s < 0.3f) return
            val m = masks[if (masks.size > 1) (rnd() * masks.size).toInt().coerceIn(0, masks.size - 1) else 0]
            var ang = b.angle
            if (b.rotateWithStroke) ang += dir
            if (b.randomRotation > 0f) ang += (rnd() * 2f - 1f) * b.randomRotation
            var dx = 0f; var dy = 0f
            if (b.scatter > 0f) { dx = (rnd() * 2f - 1f) * b.scatter * s; dy = (rnd() * 2f - 1f) * b.scatter * s }
            val sc = s / max(m.width, m.height)
            dabMatrix.setTranslate(-m.width / 2f, -m.height / 2f)
            dabMatrix.postScale(sc, sc)
            dabMatrix.postRotate(ang)
            dabMatrix.postTranslate(x + dx, y + dy)
            c.drawBitmap(m, dabMatrix, dabPaint)
        }
        val n = e.count
        if (n == 1) dab(e.x(0), e.y(0), e.w(0), 0f) else {
            var next = 0f; var acc = 0f; var dabs = 0
            for (i in 1 until n) {
                val ax = e.x(i - 1); val ay = e.y(i - 1); val bx = e.x(i); val by = e.y(i)
                val len = hypot(bx - ax, by - ay)
                if (len <= 0f) continue
                val dir = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()
                while (next <= acc + len && dabs < 20000) {
                    val t = (next - acc) / len
                    val w = e.w(i - 1) + (e.w(i) - e.w(i - 1)) * t
                    dab(ax + (bx - ax) * t, ay + (by - ay) * t, w, dir)
                    dabs++
                    next += max(0.5f, e.size * max(w, 0.05f) * b.spacing)
                }
                acc += len
            }
        }
        c.restoreToCount(save)
    }

    /** Отрисовка кадра в новый битмап (экспорт, миниатюры, заливка). */
    fun renderBitmap(p: Project, frame: Int, scale: Float, opt: FrameOptions, forceOpaque: Boolean = false): Bitmap {
        val w = max(1, (p.width * scale).toInt()); val h = max(1, (p.height * scale).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (forceOpaque && (p.bgType == BgType.TRANSPARENT || !opt.background)) c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        drawFrame(c, p, frame, opt)
        return bmp
    }
}
