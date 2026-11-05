package com.animora.studio.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import com.animora.studio.model.BgType
import com.animora.studio.model.BitmapElement
import com.animora.studio.model.BrushKind
import com.animora.studio.model.BrushRegistry
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
    val onionTint: Boolean = true,
    val activeLayerId: String? = null,
    val onlyLayerId: String? = null
)

class Resolved(val elements: List<Element>, val matrix: Matrix, val alpha: Float)

/** Рендерер кадра. Экземпляр не потокобезопасен — у каждого потока свой. */
class Renderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val layerPaint = Paint()
    private val dstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    companion object {
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
                val t = k.easing.apply((frame - e.key).toFloat() / (next.key - e.key))
                return Transform.lerp(k.transform, next.value.transform, t)
            }
            return k.transform
        }

        /** Матрица отображения слоя в кадре: локальные координаты → документ. */
        fun layerMatrix(project: Project, layer: Layer, frame: Int): Matrix {
            val m = (keyTransformAt(layer, frame) ?: Transform()).matrix()
            Bones.bindMatrix(project, layer, frame)?.let { m.postConcat(it) }
            return m
        }

        fun resolve(project: Project, layer: Layer, frame: Int): Resolved? {
            val e = layer.keyframes.floorEntry(frame) ?: return null
            val k = e.value
            val next = layer.keyframes.higherEntry(e.key)
            var elements: List<Element> = k.elements
            var tr = k.transform
            if (next != null && k.tween != TweenType.NONE && frame > e.key) {
                val t = k.easing.apply((frame - e.key).toFloat() / (next.key - e.key))
                tr = Transform.lerp(k.transform, next.value.transform, t)
                if (k.tween == TweenType.SHAPE) elements = Interp.morphElements(k.elements, next.value.elements, t)
            }
            val m = tr.matrix()
            Bones.bindMatrix(project, layer, frame)?.let { m.postConcat(it) }
            return Resolved(elements, m, tr.alpha)
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

    fun drawFrame(c: Canvas, p: Project, frame: Int, opt: FrameOptions) {
        if (opt.background) drawBackground(c, p)
        for (layer in p.layers) {
            if (layer.type != LayerType.DRAWING || !layer.visible) continue
            if (opt.onlyLayerId != null && layer.id != opt.onlyLayerId) continue
            if (layer.guide && !opt.includeGuides) continue
            if (opt.onion && (opt.onionAllLayers || layer.id == opt.activeLayerId)) drawOnion(c, p, layer, frame, opt)
            drawLayer(c, p, layer, frame, 1f, null)
        }
    }

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

    fun drawLayer(c: Canvas, p: Project, layer: Layer, frame: Int, alphaMul: Float, tint: Int?) {
        val r = resolve(p, layer, frame) ?: return
        if (r.elements.isEmpty()) return
        val alpha = layer.opacity * r.alpha * alphaMul
        val needsLayer = tint != null || alpha < 0.999f || layer.blend.mode != null ||
            r.elements.any { it is StrokeElement && it.kind == BrushKind.ERASER }
        if (!needsLayer) {
            val s = c.save(); c.concat(r.matrix)
            for (e in r.elements) drawElement(c, e)
            c.restoreToCount(s)
            return
        }
        layerPaint.reset()
        layerPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        layerPaint.xfermode = if (tint == null) layer.blend.mode?.let { PorterDuffXfermode(it) } else null
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
