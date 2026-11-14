package com.animora.studio.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.animora.studio.engine.FloatList
import com.animora.studio.engine.FrameOptions
import com.animora.studio.engine.Renderer
import com.animora.studio.model.BgType
import com.animora.studio.model.BrushKind
import com.animora.studio.model.LayerType
import com.animora.studio.model.StrokeElement
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Холст: рисование стилусом/пальцем, жесты (щипок, поворот, сдвиг), отмена двумя пальцами,
 * повтор тремя, наведение стилуса, кнопки стилуса, ластик на обратном конце пера.
 */
@SuppressLint("ViewConstructor")
class DrawingView(context: Context, private val ctl: EditorController) : View(context) {
    private val project = ctl.project
    private val renderer = Renderer()
    private val view = Matrix()
    private val inv = Matrix()
    private var fitted = false
    private var appliedFlip = false
    private var appliedReset = 0

    private val previewScale = min(1f, 2560f / max(project.width, project.height))
    private var cache: Bitmap? = null
    private var cacheKey = ""

    private val density = resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checker: Paint

    // --- состояние касания ---
    private enum class Mode { NONE, DRAW, GESTURE }
    private var mode = Mode.NONE
    private var activePointer = -1
    private var usingStylus = false
    private var curTool = Tool.BRUSH
    private var downTime = 0L
    private var maxPointers = 0
    private var gestureMoved = 0f
    private var gcx = 0f; private var gcy = 0f; private var gSpan = 0f; private var gAngle = 0f
    private var gCount = 0

    // --- живой штрих ---
    private val live = FloatList()
    private var liveStroke: StrokeElement? = null
    private var liveKind = BrushKind.PEN
    private var liveTip: String? = null
    private var liveSettings: BrushSettings? = null
    private var sx = 0f; private var sy = 0f; private var sp = 1f
    private var pressureSum = 0f; private var pressureN = 0
    private var lastRawX = 0f; private var lastRawY = 0f

    private var shapeX0 = 0f; private var shapeY0 = 0f; private var shapeX1 = 0f; private var shapeY1 = 0f
    private var shapeActive = false
    private val lasso = FloatList()
    private var lassoActive = false
    private var guideActive = false
    private var selDragging = false
    private var keyDragging = false
    private var poseDragging = false
    private var hoverX = -1f; private var hoverY = -1f; private var hovering = false
    private var pickerColor: Int? = null

    init {
        isFocusable = true
        val s = (8 * density).toInt().coerceAtLeast(4)
        val tile = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
        Canvas(tile).apply {
            drawColor(0xFFFFFFFF.toInt())
            val p = Paint().apply { color = 0xFFD5D5D5.toInt() }
            drawRect(0f, 0f, s.toFloat(), s.toFloat(), p); drawRect(s.toFloat(), s.toFloat(), 2f * s, 2f * s, p)
        }
        checker = Paint().apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    private val viewScale: Float get() = view.mapRadius(1f)

    fun sync() { invalidate() }

    private fun fit() {
        if (width == 0 || height == 0) return
        val s = min(width * 0.88f / project.width, height * 0.88f / project.height)
        view.reset()
        view.postScale(s, s)
        view.postTranslate((width - project.width * s) / 2f, (height - project.height * s) / 2f)
        if (ctl.flipView) view.postScale(-1f, 1f, width / 2f, height / 2f)
        appliedFlip = ctl.flipView
        fitted = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!fitted || oldw == 0) fit() else view.postTranslate((w - oldw) / 2f, (h - oldh) / 2f)
    }

    private fun toDoc(x: Float, y: Float): FloatArray {
        view.invert(inv)
        val p = floatArrayOf(x, y); inv.mapPoints(p); return p
    }

    // ===================== отрисовка =====================
    private fun ensureCache() {
        val key = ctl.renderKey()
        if (key == cacheKey && cache != null) return
        val w = max(1, (project.width * previewScale).toInt()); val h = max(1, (project.height * previewScale).toInt())
        val b = cache?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { cache = it }
        b.eraseColor(Color.TRANSPARENT)
        val c = Canvas(b)
        c.scale(previewScale, previewScale)
        val opt = FrameOptions(
            background = true, includeGuides = true,
            onion = ctl.onionEnabled && !ctl.playing,
            onionPrev = ctl.onionPrev, onionNext = ctl.onionNext, onionOpacity = ctl.onionOpacity,
            onionAllLayers = ctl.onionAllLayers, onionKeysOnly = ctl.onionKeysOnly, onionTint = ctl.onionTint,
            activeLayerId = ctl.activeLayerId,
            maskPreview = true
        )
        renderer.drawFrame(c, project, ctl.frame, opt)
        cacheKey = key
    }

    override fun onDraw(canvas: Canvas) {
        if (ctl.viewResetToken != appliedReset) { appliedReset = ctl.viewResetToken; fit() }
        if (ctl.flipView != appliedFlip) { view.postScale(-1f, 1f, width / 2f, height / 2f); appliedFlip = ctl.flipView }
        canvas.drawColor(com.animora.studio.ui.UiPrefs.workspace)
        ensureCache()
        val vs = viewScale
        canvas.save()
        canvas.concat(view)
        val docRect = RectF(0f, 0f, project.width.toFloat(), project.height.toFloat())
        overlay.reset(); overlay.color = 0x55000000; overlay.isAntiAlias = true
        canvas.drawRect(docRect.left + 6 / vs, docRect.top + 6 / vs, docRect.right + 6 / vs, docRect.bottom + 6 / vs, overlay)
        if (project.bgType == BgType.TRANSPARENT) {
            canvas.save(); canvas.scale(1f / vs, 1f / vs); canvas.drawRect(0f, 0f, project.width * vs, project.height * vs, checker); canvas.restore()
        }
        cache?.let {
            canvas.save(); canvas.scale(1f / previewScale, 1f / previewScale)
            canvas.drawBitmap(it, 0f, 0f, bmpPaint); canvas.restore()
        }
        drawLive(canvas, vs)
        drawGuides(canvas, vs)
        if (ctl.showBones && !ctl.playing) drawBones(canvas, vs)
        drawSelection(canvas, vs)
        canvas.restore()
        drawHover(canvas)
        pickerColor?.let { c ->
            overlay.reset(); overlay.isAntiAlias = true
            overlay.color = c; canvas.drawCircle(lastRawX, lastRawY - 70 * density, 28 * density, overlay)
            overlay.style = Paint.Style.STROKE; overlay.strokeWidth = 3 * density; overlay.color = Color.WHITE
            canvas.drawCircle(lastRawX, lastRawY - 70 * density, 28 * density, overlay)
        }
    }

    private fun drawLive(canvas: Canvas, vs: Float) {
        liveStroke?.let { s ->
            for (m in ctl.mirroredPreview(s)) {
                if (m.kind == BrushKind.ERASER) {
                    overlay.reset(); overlay.isAntiAlias = true; overlay.color = 0x66FFFFFF
                    canvas.drawPath(com.animora.studio.engine.StrokePath.get(m), overlay)
                    overlay.style = Paint.Style.STROKE; overlay.strokeWidth = 1.5f / vs; overlay.color = 0x99000000.toInt()
                    canvas.drawPath(com.animora.studio.engine.StrokePath.get(m), overlay)
                } else renderer.drawElement(canvas, m)
            }
        }
        if (shapeActive && (curTool == Tool.LINE || curTool == Tool.RECT || curTool == Tool.ELLIPSE)) {
            val s = ctl.shapeStroke(curTool, shapeX0, shapeY0, shapeX1, shapeY1)
            ctl.mirroredPreview(s).forEach { renderer.drawElement(canvas, it) }
        }
        if (lassoActive && lasso.size >= 4) {
            val p = Path(); p.moveTo(lasso[0], lasso[1])
            var i = 2; while (i < lasso.size) { p.lineTo(lasso[i], lasso[i + 1]); i += 2 }
            overlay.reset(); overlay.isAntiAlias = true; overlay.style = Paint.Style.STROKE
            overlay.strokeWidth = 2f / vs; overlay.color = 0xFF7C6CFF.toInt()
            overlay.pathEffect = DashPathEffect(floatArrayOf(10f / vs, 6f / vs), 0f)
            canvas.drawPath(p, overlay)
        }
    }

    private fun drawGuides(canvas: Canvas, vs: Float) {
        val w = project.width.toFloat(); val h = project.height.toFloat()
        overlay.reset(); overlay.isAntiAlias = true; overlay.style = Paint.Style.STROKE
        overlay.strokeWidth = 1f / vs
        if (ctl.showGrid) {
            overlay.color = 0x407C6CFF
            val step = max(w, h) / 24f
            var x = step; while (x < w) { canvas.drawLine(x, 0f, x, h, overlay); x += step }
            var y = step; while (y < h) { canvas.drawLine(0f, y, w, y, overlay); y += step }
            overlay.color = 0x90FF4081.toInt()
            for (i in 1..2) { canvas.drawLine(w * i / 3, 0f, w * i / 3, h, overlay); canvas.drawLine(0f, h * i / 3, w, h * i / 3, overlay) }
        }
        if (ctl.showSafeArea) {
            overlay.color = 0xA0FFC107.toInt()
            canvas.drawRect(w * 0.05f, h * 0.05f, w * 0.95f, h * 0.95f, overlay)
            canvas.drawRect(w * 0.1f, h * 0.1f, w * 0.9f, h * 0.9f, overlay)
        }
        if (ctl.symmetry != Symmetry.NONE) {
            overlay.color = 0xA000E5FF.toInt(); overlay.pathEffect = DashPathEffect(floatArrayOf(12f / vs, 8f / vs), 0f)
            if (ctl.symmetry == Symmetry.VERTICAL || ctl.symmetry == Symmetry.QUAD) canvas.drawLine(w / 2, 0f, w / 2, h, overlay)
            if (ctl.symmetry == Symmetry.HORIZONTAL || ctl.symmetry == Symmetry.QUAD) canvas.drawLine(0f, h / 2, w, h / 2, overlay)
            overlay.pathEffect = null
        }
    }

    private fun drawBones(canvas: Canvas, vs: Float) {
        for (l in project.layers) {
            if (l.type != LayerType.BONES || !l.visible || l.bones.isEmpty()) continue
            val active = l.id == ctl.activeLayerId
            val world = ctl.posedBones(l)
            val cam = ctl.cameraMatrix()
            val camSave = canvas.save()
            if (cam != null) canvas.concat(cam)
            for (b in l.bones) {
                val w = world[b.id] ?: continue
                val sel = b.id == ctl.selectedBoneId
                val ang = atan2(w.tailY - w.hy, w.tailX - w.hx)
                val wd = max(6f / vs, w.length * 0.12f)
                val nx = -kotlin.math.sin(ang) * wd; val ny = kotlin.math.cos(ang) * wd
                val mx = w.hx + (w.tailX - w.hx) * 0.2f; val my = w.hy + (w.tailY - w.hy) * 0.2f
                val p = Path().apply {
                    moveTo(w.hx, w.hy); lineTo(mx + nx, my + ny); lineTo(w.tailX, w.tailY); lineTo(mx - nx, my - ny); close()
                }
                overlay.reset(); overlay.isAntiAlias = true
                overlay.color = when { sel -> 0xC0FF9800.toInt(); active -> 0x904FC3F7.toInt(); else -> 0x50B0BEC5 }
                canvas.drawPath(p, overlay)
                overlay.style = Paint.Style.STROKE; overlay.strokeWidth = 1.5f / vs; overlay.color = if (sel) Color.WHITE else 0xC0FFFFFF.toInt()
                canvas.drawPath(p, overlay)
                overlay.style = Paint.Style.FILL; overlay.color = if (b.parentId == null) 0xFFFFC107.toInt() else Color.WHITE
                canvas.drawCircle(w.hx, w.hy, 5f / vs, overlay)
            }
            canvas.restoreToCount(camSave)
        }
    }

    /** Координаты холста → координаты сцены (без камеры) — для костей. */
    private fun toScene(d: FloatArray): FloatArray {
        ctl.cameraMatrix()?.let { m -> val inv = Matrix(); m.invert(inv); inv.mapPoints(d) }
        return d
    }

    private fun drawSelection(canvas: Canvas, vs: Float) {
        val b: RectF? = when {
            ctl.selection != null -> ctl.selectionBounds()
            ctl.tool == Tool.TRANSFORM && !ctl.playing -> ctl.keyContentBounds()
            else -> null
        }
        b ?: return
        val hs = 9f / vs
        overlay.reset(); overlay.isAntiAlias = true; overlay.style = Paint.Style.STROKE
        overlay.strokeWidth = 1.5f / vs; overlay.color = 0xFF7C6CFF.toInt()
        overlay.pathEffect = DashPathEffect(floatArrayOf(8f / vs, 5f / vs), 0f)
        canvas.drawRect(b, overlay)
        overlay.pathEffect = null
        canvas.drawLine(b.centerX(), b.top, b.centerX(), b.top - rotOffset(), overlay)
        overlay.style = Paint.Style.FILL; overlay.color = Color.WHITE
        listOf(b.left to b.top, b.right to b.top, b.right to b.bottom, b.left to b.bottom).forEach { (x, y) ->
            canvas.drawRect(x - hs / 2, y - hs / 2, x + hs / 2, y + hs / 2, overlay)
        }
        canvas.drawCircle(b.centerX(), b.top - rotOffset(), hs * 0.7f, overlay)
        if (ctl.tool == Tool.TRANSFORM) drawMotionPath(canvas, vs)
        if (ctl.tool == Tool.TRANSFORM) ctl.keyPivotDoc()?.let {
            overlay.color = 0xFFFF4081.toInt(); canvas.drawCircle(it.x, it.y, 5f / vs, overlay)
        }
    }

    /** Траектория твина движения: точка на каждый кадр — видно расстановку и ускорение (как «тайминг-чарт»). */
    private fun drawMotionPath(canvas: Canvas, vs: Float) {
        ctl.guideDoc()?.let { g ->
            if (g.size >= 4) {
                overlay.reset(); overlay.isAntiAlias = true; overlay.style = Paint.Style.STROKE
                overlay.strokeWidth = 3f / vs; overlay.color = 0xB03DDC97.toInt()
                val gp = Path(); gp.moveTo(g[0], g[1]); var j = 2
                while (j < g.size) { gp.lineTo(g[j], g[j + 1]); j += 2 }
                canvas.drawPath(gp, overlay)
            }
        }
        val path = ctl.motionPath()
        if (path.size < 2) return
        overlay.reset(); overlay.isAntiAlias = true; overlay.style = Paint.Style.STROKE
        overlay.strokeWidth = 1.5f / vs; overlay.color = 0x90FFC857.toInt()
        overlay.pathEffect = DashPathEffect(floatArrayOf(6f / vs, 5f / vs), 0f)
        val p = Path(); p.moveTo(path[0].x, path[0].y)
        for (i in 1 until path.size) p.lineTo(path[i].x, path[i].y)
        canvas.drawPath(p, overlay)
        overlay.pathEffect = null; overlay.style = Paint.Style.FILL
        val start = ctl.curveTarget()?.start ?: 0
        path.forEachIndexed { i, pt ->
            val cur = start + i == ctl.frame
            overlay.color = if (cur) 0xFFFF6B9A.toInt() else 0xFFFFC857.toInt()
            canvas.drawCircle(pt.x, pt.y, (if (cur) 5.5f else 3.2f) / vs, overlay)
        }
    }

    private fun drawHover(canvas: Canvas) {
        if (!hovering || hoverX < 0) return
        val r = when (ctl.tool) {
            Tool.BRUSH, Tool.ERASER, Tool.LINE, Tool.RECT, Tool.ELLIPSE -> ctl.brush.size / 2f * viewScale
            else -> 4f * density
        }
        overlay.reset(); overlay.isAntiAlias = true; overlay.style = Paint.Style.STROKE
        overlay.strokeWidth = 1.2f * density; overlay.color = 0xCCFFFFFF.toInt()
        canvas.drawCircle(hoverX, hoverY, max(2f, r), overlay)
        overlay.color = 0xCC000000.toInt(); overlay.strokeWidth = 0.8f * density
        canvas.drawCircle(hoverX, hoverY, max(2f, r) + density, overlay)
    }

    // ===================== ввод =====================
    override fun onHoverEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> { hovering = true; hoverX = e.x; hoverY = e.y; invalidate() }
            MotionEvent.ACTION_HOVER_EXIT -> { hovering = false; invalidate() }
        }
        return true
    }

    private fun tolDoc() = 26f * density / viewScale
    private fun rotOffset() = 34f * density / viewScale

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(e)
                hovering = false
                val tt = e.getToolType(0)
                val stylus = tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER
                if (stylus && !ctl.stylusDetected) {
                    ctl.stylusDetected = true; ctl.stylusOnly = true
                    ctl.toast("Обнаружен стилус: рисование только пером, пальцы — навигация")
                }
                downTime = e.eventTime; maxPointers = 1; gestureMoved = 0f
                if (ctl.playing) ctl.stop()
                if (ctl.tool == Tool.HAND || (ctl.stylusOnly && !stylus)) { beginGesture(e, -1); return true }
                mode = Mode.DRAW
                activePointer = e.getPointerId(0)
                usingStylus = stylus
                curTool = when {
                    stylus && (e.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 -> Tool.EYEDROPPER
                    tt == MotionEvent.TOOL_TYPE_ERASER || (stylus && (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) -> Tool.ERASER
                    else -> ctl.tool
                }
                toolDown(e, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                if (mode == Mode.DRAW) {
                    if (usingStylus) return true // ладонь/палец во время рисования пером игнорируются
                    toolCancel()
                }
                beginGesture(e, -1)
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> { val i = e.findPointerIndex(activePointer); if (i >= 0) toolMove(e, i) }
                Mode.GESTURE -> gestureMove(e)
                else -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.GESTURE) beginGesture(e, e.actionIndex)
                else if (mode == Mode.DRAW && e.getPointerId(e.actionIndex) == activePointer) { toolUp(e, e.actionIndex); mode = Mode.NONE }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.DRAW) {
                    val i = e.findPointerIndex(activePointer)
                    toolUp(e, if (i >= 0) i else 0)
                } else if (mode == Mode.GESTURE) {
                    val quick = e.eventTime - downTime < 300 && gestureMoved < 30 * density
                    if (quick && maxPointers == 2) ctl.undo() else if (quick && maxPointers >= 3) ctl.redo()
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> { if (mode == Mode.DRAW) toolCancel(); mode = Mode.NONE }
        }
        return true
    }

    private fun beginGesture(e: MotionEvent, exclude: Int) {
        mode = Mode.GESTURE
        computeGesture(e, exclude).let { (cx, cy, span, ang) -> gcx = cx; gcy = cy; gSpan = span; gAngle = ang }
        gCount = e.pointerCount - if (exclude >= 0) 1 else 0
    }

    private data class G(val cx: Float, val cy: Float, val span: Float, val angle: Float)

    private fun computeGesture(e: MotionEvent, exclude: Int): G {
        var cx = 0f; var cy = 0f; var n = 0
        val idx = ArrayList<Int>()
        for (i in 0 until e.pointerCount) if (i != exclude) { cx += e.getX(i); cy += e.getY(i); n++; idx.add(i) }
        if (n == 0) return G(0f, 0f, 0f, 0f)
        cx /= n; cy /= n
        if (idx.size < 2) return G(cx, cy, 0f, 0f)
        val a = idx[0]; val b = idx[1]
        return G(cx, cy, hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b)),
            Math.toDegrees(atan2((e.getY(b) - e.getY(a)).toDouble(), (e.getX(b) - e.getX(a)).toDouble())).toFloat())
    }

    private fun gestureMove(e: MotionEvent) {
        val g = computeGesture(e, -1)
        if (e.pointerCount != gCount) { beginGesture(e, -1); return }
        view.postTranslate(g.cx - gcx, g.cy - gcy)
        gestureMoved += abs(g.cx - gcx) + abs(g.cy - gcy)
        if (e.pointerCount >= 2 && gSpan > 10f && g.span > 10f) {
            val f = g.span / gSpan
            val cur = viewScale
            val nf = (cur * f).coerceIn(0.02f, 60f) / cur
            view.postScale(nf, nf, g.cx, g.cy)
            var da = g.angle - gAngle
            if (da > 180) da -= 360f; if (da < -180) da += 360f
            view.postRotate(da, g.cx, g.cy)
            gestureMoved += abs(g.span - gSpan)
        }
        gcx = g.cx; gcy = g.cy; gSpan = g.span; gAngle = g.angle
        invalidate()
    }

    // ---------------- инструменты ----------------
    private fun pressureOf(e: MotionEvent, i: Int, h: Int): Float {
        if (!usingStylus) return 1f
        val p = if (h >= 0) e.getHistoricalPressure(i, h) else e.getPressure(i)
        return p.coerceIn(0f, 1f)
    }

    private fun tiltOf(e: MotionEvent, i: Int, h: Int): Float {
        if (!usingStylus) return 0f
        val t = if (h >= 0) e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h) else e.getAxisValue(MotionEvent.AXIS_TILT, i)
        return (t / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
    }

    private fun toolDown(e: MotionEvent, i: Int) {
        val d = toDoc(e.getX(i), e.getY(i))
        lastRawX = e.getX(i); lastRawY = e.getY(i)
        when (curTool) {
            Tool.BRUSH, Tool.ERASER -> {
                if (curTool == Tool.ERASER && ctl.strokeEraser) { ctl.beginStrokeErase(); ctl.eraseStrokesAt(d[0], d[1], ctl.brushSettings[BrushKind.ERASER]!!.size / 2f) }
                else startStroke(d[0], d[1], pressureOf(e, i, -1), tiltOf(e, i, -1))
            }
            Tool.LINE, Tool.RECT, Tool.ELLIPSE -> { shapeX0 = d[0]; shapeY0 = d[1]; shapeX1 = d[0]; shapeY1 = d[1]; shapeActive = true }
            Tool.LASSO -> {
                selDragging = ctl.selection != null && ctl.beginSelectionDrag(d[0], d[1], tolDoc(), rotOffset())
                if (!selDragging) { ctl.selection = null; lasso.clear(); lasso.add(d[0]); lasso.add(d[1]); lassoActive = true }
            }
            Tool.TRANSFORM -> if (ctl.drawingGuide) { lasso.clear(); lasso.add(d[0]); lasso.add(d[1]); lassoActive = true; guideActive = true }
                else keyDragging = ctl.beginKeyTransform(d[0], d[1], tolDoc(), rotOffset())
            Tool.POSE -> { toScene(d); poseDragging = ctl.beginPose(d[0], d[1], tolDoc()) }
            Tool.BONE -> { toScene(d); shapeX0 = d[0]; shapeY0 = d[1]; shapeX1 = d[0]; shapeY1 = d[1]; shapeActive = false }
            Tool.EYEDROPPER -> pick(d[0], d[1])
            else -> {}
        }
        invalidate()
    }

    private fun toolMove(e: MotionEvent, i: Int) {
        lastRawX = e.getX(i); lastRawY = e.getY(i)
        when (curTool) {
            Tool.BRUSH, Tool.ERASER -> {
                for (h in 0 until e.historySize) {
                    val d = toDoc(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                    if (curTool == Tool.ERASER && ctl.strokeEraser) ctl.eraseStrokesAt(d[0], d[1], ctl.brushSettings[BrushKind.ERASER]!!.size / 2f)
                    else addPoint(d[0], d[1], pressureOf(e, i, h), tiltOf(e, i, h))
                }
                val d = toDoc(e.getX(i), e.getY(i))
                if (curTool == Tool.ERASER && ctl.strokeEraser) ctl.eraseStrokesAt(d[0], d[1], ctl.brushSettings[BrushKind.ERASER]!!.size / 2f)
                else { addPoint(d[0], d[1], pressureOf(e, i, -1), tiltOf(e, i, -1)); rebuildLive() }
            }
            Tool.LINE, Tool.RECT, Tool.ELLIPSE -> {
                val d = toDoc(e.getX(i), e.getY(i))
                shapeX1 = d[0]; shapeY1 = d[1]
                if (curTool == Tool.LINE) snapLine()
            }
            Tool.LASSO -> {
                val d = toDoc(e.getX(i), e.getY(i))
                if (selDragging) ctl.updateSelectionDrag(d[0], d[1]) else { lasso.add(d[0]); lasso.add(d[1]) }
            }
            Tool.TRANSFORM -> if (guideActive) { val d = toDoc(e.getX(i), e.getY(i)); lasso.add(d[0]); lasso.add(d[1]) }
                else if (keyDragging) { val d = toDoc(e.getX(i), e.getY(i)); ctl.updateKeyTransform(d[0], d[1]) }
            Tool.POSE -> if (poseDragging) { val d = toScene(toDoc(e.getX(i), e.getY(i))); ctl.dragPose(d[0], d[1]) }
            Tool.BONE -> { val d = toScene(toDoc(e.getX(i), e.getY(i))); shapeX1 = d[0]; shapeY1 = d[1]; shapeActive = true; curBonePreview() }
            Tool.EYEDROPPER -> { val d = toDoc(e.getX(i), e.getY(i)); pick(d[0], d[1]) }
            else -> {}
        }
        invalidate()
    }

    private fun toolUp(e: MotionEvent, i: Int) {
        when (curTool) {
            Tool.BRUSH, Tool.ERASER -> {
                if (curTool == Tool.ERASER && ctl.strokeEraser) ctl.endStrokeErase()
                else {
                    val d = toDoc(e.getX(i), e.getY(i))
                    // догоняем стабилизатор до реального конца пера
                    if (hypot(d[0] - sx, d[1] - sy) > 0.5f) { live.add(d[0]); live.add(d[1]); live.add(widthFactor(sp, 0f)) }
                    finishStroke()
                }
            }
            Tool.LINE, Tool.RECT, Tool.ELLIPSE -> { if (shapeActive) ctl.commitShape(curTool, shapeX0, shapeY0, shapeX1, shapeY1); shapeActive = false }
            Tool.LASSO -> {
                if (selDragging) { selDragging = false }
                else if (lassoActive) {
                    lassoActive = false
                    if (lasso.size >= 6 && lassoLength() > tolDoc()) ctl.selectLasso(lasso.toArray())
                    else ctl.selectAt(lasso[0], lasso[1], tolDoc() * 0.6f)
                    lasso.clear()
                }
            }
            Tool.TRANSFORM -> {
                if (guideActive) { guideActive = false; lassoActive = false; if (lasso.size >= 4) ctl.setGuide(lasso.toArray()); lasso.clear() }
                if (keyDragging) ctl.endKeyTransform(); keyDragging = false
            }
            Tool.TEXT -> {
                if (e.eventTime - downTime < 500) {
                    val d = toDoc(e.getX(i), e.getY(i))
                    ctl.selectAt(d[0], d[1], tolDoc() * 0.5f)
                    if (ctl.selectedText() != null) ctl.editTextRequested = true else { ctl.selection = null; ctl.requestText(d[0], d[1]) }
                }
            }
            Tool.POSE -> { if (poseDragging) ctl.endPose(); poseDragging = false }
            Tool.BONE -> {
                liveStroke = null
                if (shapeActive) ctl.addBone(shapeX0, shapeY0, shapeX1, shapeY1, tolDoc())
                else ctl.activeBoneLayer()?.let { l -> ctl.selectedBoneId = ctl.hitBone(l, shapeX0, shapeY0, tolDoc())?.id }
                shapeActive = false
            }
            Tool.FILL -> {
                if (e.eventTime - downTime < 600) { val d = toDoc(e.getX(i), e.getY(i)); ctl.fillAt(d[0], d[1]) }
            }
            Tool.EYEDROPPER -> { pickerColor?.let { ctl.pushRecentColor(it) }; pickerColor = null }
            else -> {}
        }
        invalidate()
    }

    private fun toolCancel() {
        liveStroke = null; live.clear(); shapeActive = false; lassoActive = false; lasso.clear(); pickerColor = null
        if (selDragging || keyDragging || poseDragging) { ctl.history.undo(); ctl.changed() }
        selDragging = false; keyDragging = false; poseDragging = false
        invalidate()
    }

    private fun lassoLength(): Float {
        var len = 0f; var i = 2
        while (i < lasso.size) { len += hypot(lasso[i] - lasso[i - 2], lasso[i + 1] - lasso[i - 1]); i += 2 }
        return len
    }

    private fun snapLine() {
        // привязка линии к 15° при почти горизонтальном/вертикальном/диагональном направлении
        val dx = shapeX1 - shapeX0; val dy = shapeY1 - shapeY0
        val len = hypot(dx, dy); if (len < 1f) return
        val a = Math.toDegrees(atan2(dy, dx).toDouble())
        val snapped = Math.round(a / 15.0) * 15.0
        if (abs(a - snapped) < 3.0) {
            val r = Math.toRadians(snapped)
            shapeX1 = shapeX0 + (len * kotlin.math.cos(r)).toFloat(); shapeY1 = shapeY0 + (len * kotlin.math.sin(r)).toFloat()
        }
    }

    private fun curBonePreview() {
        // предпросмотр кости рисуем как линию
        val pp = floatArrayOf(shapeX0, shapeY0, shapeX1, shapeY1)
        ctl.cameraMatrix()?.mapPoints(pp)
        liveStroke = StrokeElement(floatArrayOf(pp[0], pp[1], 1f, pp[2], pp[3], 0.3f), 0xFFFF9800.toInt(), 10f / viewScale, BrushKind.PEN, 0.8f)
    }

    private fun pick(x: Float, y: Float) {
        val b = cache ?: return
        val px = (x * previewScale).toInt(); val py = (y * previewScale).toInt()
        if (px !in 0 until b.width || py !in 0 until b.height) return
        val c = b.getPixel(px, py)
        if (Color.alpha(c) < 10) return
        val opaque = c or 0xFF000000.toInt()
        ctl.color = opaque; pickerColor = opaque
    }

    private fun widthFactor(p: Float, tilt: Float): Float {
        val s = liveSettings ?: return 1f
        var f = if (s.pressureSize && usingStylus) 0.08f + 0.92f * p.pow(s.curve) else 1f
        if (s.tiltSize && usingStylus) f *= 1f + tilt * 2f
        return f
    }

    private fun startStroke(x: Float, y: Float, p: Float, tilt: Float) {
        liveKind = if (curTool == Tool.ERASER) BrushKind.ERASER else ctl.brushKind
        liveSettings = ctl.settingsFor(liveKind)
        liveTip = if (liveKind == BrushKind.CUSTOM) ctl.activeTipId else null
        if (liveKind == BrushKind.CUSTOM && liveTip == null) liveKind = BrushKind.PEN
        live.clear(); pressureSum = 0f; pressureN = 0
        sx = x; sy = y; sp = p
        live.add(x); live.add(y); live.add(widthFactor(p, tilt))
        pressureSum += p; pressureN++
        rebuildLive()
    }

    private fun addPoint(x: Float, y: Float, p: Float, tilt: Float) {
        val s = liveSettings ?: return
        val k = 1f - s.smoothing.coerceIn(0f, 0.95f)
        sx += (x - sx) * k; sy += (y - sy) * k
        sp += (p - sp) * 0.6f
        val n = live.size
        if (n >= 3 && hypot(sx - live[n - 3], sy - live[n - 2]) < 0.6f / max(0.2f, viewScale)) return
        live.add(sx); live.add(sy); live.add(widthFactor(sp, tilt))
        pressureSum += p; pressureN++
    }

    private fun currentOpacity(): Float {
        val s = liveSettings ?: return 1f
        return if (s.pressureOpacity && usingStylus && pressureN > 0) s.opacity * (0.25f + 0.75f * (pressureSum / pressureN)) else s.opacity
    }

    private fun rebuildLive() {
        val s = liveSettings ?: return
        liveStroke = StrokeElement(live.toArray(), ctl.color, s.size, liveKind, currentOpacity(), liveTip)
    }

    private fun finishStroke() {
        val s = liveSettings ?: return
        val pts = live.toArray()
        val n = pts.size / 3
        if (n == 0) { liveStroke = null; return }
        if (s.taper && n > 3) {
            val cum = FloatArray(n)
            for (i in 1 until n) cum[i] = cum[i - 1] + hypot(pts[i * 3] - pts[(i - 1) * 3], pts[i * 3 + 1] - pts[(i - 1) * 3 + 1])
            val total = cum[n - 1]
            val tl = min(total * 0.3f, s.size * 5f)
            if (tl > 0f) for (i in 0 until n) {
                val a = (cum[i] / tl).coerceAtMost(1f); val b = ((total - cum[i]) / tl).coerceAtMost(1f)
                val f = min(a, b)
                pts[i * 3 + 2] *= 0.15f + 0.85f * (f * f * (3 - 2 * f))
            }
        }
        val stroke = StrokeElement(pts, ctl.color, s.size, liveKind, currentOpacity(), liveTip)
        liveStroke = null
        live.clear()
        ctl.commitStroke(stroke)
    }
}
