package com.animora.studio.engine

import android.graphics.Bitmap
import com.animora.studio.model.Project
import com.animora.studio.model.ProjectState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Заливка с допуском, «закрытием зазоров» (заливка не протекает через щели в контуре)
 * и расширением под линии (без светлого ореола).
 */
object FloodFill {
    class Result(val bitmap: Bitmap, val x: Int, val y: Int, val contours: List<FloatArray>? = null)

    fun fill(src: Bitmap, sx: Int, sy: Int, color: Int, tolerance: Int, gap: Int, expand: Int, vector: Boolean = false): Result? {
        val w = src.width; val h = src.height
        if (sx !in 0 until w || sy !in 0 until h) return null
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val target = px[sy * w + sx]
        val wall = BooleanArray(w * h) { !similar(px[it], target, tolerance) }
        var walls = if (gap > 0) dilate(wall, w, h, gap) else wall
        if (walls[sy * w + sx]) walls = wall
        if (walls[sy * w + sx]) return null

        val region = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        val start = sy * w + sx
        region[start] = true; stack[sp++] = start
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            if (x > 0) { val j = i - 1; if (!region[j] && !walls[j]) { region[j] = true; stack[sp++] = j } }
            if (x < w - 1) { val j = i + 1; if (!region[j] && !walls[j]) { region[j] = true; stack[sp++] = j } }
            if (i >= w) { val j = i - w; if (!region[j] && !walls[j]) { region[j] = true; stack[sp++] = j } }
            if (i < w * (h - 1)) { val j = i + w; if (!region[j] && !walls[j]) { region[j] = true; stack[sp++] = j } }
        }
        val grow = (if (walls !== wall) gap else 0) + expand
        val mask = if (grow > 0) dilate(region, w, h, grow) else region

        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) if (mask[row + x]) {
                if (x < l) l = x; if (x > r) r = x
                if (y < t) t = y; if (y > b) b = y
            }
        }
        if (r < 0) return null
        val bw = r - l + 1; val bh = b - t + 1
        val out = IntArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) {
            if (mask[(y + t) * w + x + l]) out[y * bw + x] = color
        }
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, bw, 0, 0, bw, bh)
        val contours = if (!vector) null else {
            val crop = BooleanArray(bw * bh) { i -> mask[(i / bw + t) * w + i % bw + l] }
            Contour.trace(crop, bw, bh).map { c -> FloatArray(c.size) { i -> c[i] + if (i % 2 == 0) l else t } }
        }
        return Result(bmp, l, t, contours)
    }

    private fun similar(a: Int, b: Int, tol: Int): Boolean {
        val aa = (a ushr 24) and 0xFF; val ba = (b ushr 24) and 0xFF
        if (aa < 10 && ba < 10) return true
        if (abs(aa - ba) > tol) return false
        if (abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) > tol) return false
        if (abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) > tol) return false
        return abs((a and 0xFF) - (b and 0xFF)) <= tol
    }

    /** Квадратная дилатация радиуса r за O(N) (два прохода скользящим окном). */
    fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var cnt = 0
            for (x in 0 until min(r, w)) if (src[row + x]) cnt++
            for (x in 0 until w) {
                val add = x + r; if (add < w && src[row + add]) cnt++
                val rem = x - r - 1; if (rem >= 0 && src[row + rem]) cnt--
                tmp[row + x] = cnt > 0
            }
        }
        for (x in 0 until w) {
            var cnt = 0
            for (y in 0 until min(r, h)) if (tmp[y * w + x]) cnt++
            for (y in 0 until h) {
                val add = y + r; if (add < h && tmp[add * w + x]) cnt++
                val rem = y - r - 1; if (rem >= 0 && tmp[rem * w + x]) cnt--
                out[y * w + x] = cnt > 0
            }
        }
        return out
    }
}

/** История отмены на снимках состояния (элементы неизменяемы, поэтому снимки дешёвые). */
class History(private val project: Project, private val limit: Int = 80) {
    private val undoStack = ArrayDeque<ProjectState>()
    private val redoStack = ArrayDeque<ProjectState>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun checkpoint() {
        undoStack.addLast(project.snapshot())
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Отменить последний checkpoint без перехода в redo (для отменённых действий). */
    fun dropLast() { undoStack.removeLastOrNull() }

    fun clear() { undoStack.clear(); redoStack.clear() }

    fun undo(): Boolean {
        val s = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(project.snapshot()); project.restore(s); return true
    }

    fun redo(): Boolean {
        val s = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(project.snapshot()); project.restore(s); return true
    }

    @Suppress("unused")
    private fun clampMax(a: Int, b: Int) = max(a, b)
}
