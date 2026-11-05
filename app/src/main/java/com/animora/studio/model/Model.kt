package com.animora.studio.model

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import java.util.TreeMap
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

enum class BrushKind(val title: String) {
    PEN("Перо"),
    PENCIL("Карандаш"),
    MARKER("Маркер"),
    SOFT("Аэрограф"),
    CALLIGRAPHY("Каллиграфия"),
    ERASER("Ластик"),
    CUSTOM("Импортированная")
}

enum class BlendMode(val title: String, val mode: PorterDuff.Mode?) {
    NORMAL("Обычный", null),
    MULTIPLY("Умножение", PorterDuff.Mode.MULTIPLY),
    SCREEN("Экран", PorterDuff.Mode.SCREEN),
    OVERLAY("Перекрытие", PorterDuff.Mode.OVERLAY),
    DARKEN("Затемнение", PorterDuff.Mode.DARKEN),
    LIGHTEN("Осветление", PorterDuff.Mode.LIGHTEN),
    ADD("Добавление", PorterDuff.Mode.ADD)
}

enum class LayerType { DRAWING, BONES }

enum class BgType(val title: String) { SOLID("Цвет"), TRANSPARENT("Прозрачный"), PAPER("Бумага") }

enum class TweenType(val title: String) { NONE("Нет"), MOTION("Движение"), SHAPE("Форма") }

enum class Easing(val title: String) {
    LINEAR("Линейно"),
    EASE_IN("Разгон"),
    EASE_OUT("Торможение"),
    EASE_IN_OUT("Плавно"),
    BACK("Перелёт"),
    BOUNCE("Отскок"),
    ELASTIC("Пружина"),
    HOLD("Ступенька");

    fun apply(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> t
            EASE_IN -> t * t
            EASE_OUT -> 1f - (1f - t) * (1f - t)
            EASE_IN_OUT -> if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).pow(2) / 2f
            BACK -> {
                val c1 = 1.70158f; val c3 = c1 + 1f
                1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
            }
            BOUNCE -> bounce(t)
            ELASTIC -> if (t == 0f || t == 1f) t else {
                val c4 = (2.0 * PI / 3.0).toFloat()
                2f.pow(-10f * t) * sin((t * 10f - 0.75f) * c4) + 1f
            }
            HOLD -> if (t >= 1f) 1f else 0f
        }
    }

    private fun bounce(t0: Float): Float {
        var t = t0
        val n1 = 7.5625f; val d1 = 2.75f
        return when {
            t < 1f / d1 -> n1 * t * t
            t < 2f / d1 -> { t -= 1.5f / d1; n1 * t * t + 0.75f }
            t < 2.5f / d1 -> { t -= 2.25f / d1; n1 * t * t + 0.9375f }
            else -> { t -= 2.625f / d1; n1 * t * t + 0.984375f }
        }
    }
}

/** Элемент рисунка. Все элементы неизменяемы — изменения создают новые объекты (дёшево для undo). */
sealed class Element {
    abstract fun bounds(): RectF
    abstract fun transformed(m: Matrix): Element
}

/**
 * Векторный штрих. pts = тройки (x, y, w) где w — множитель ширины (давление, сужение, наклон уже учтены).
 */
class StrokeElement(
    val pts: FloatArray,
    val color: Int,
    val size: Float,
    val kind: BrushKind,
    val opacity: Float,
    /** id импортированной (штамповой) кисти для kind == CUSTOM */
    val tipId: String? = null
) : Element() {
    val count: Int get() = pts.size / 3
    var cachedPath: Path? = null

    fun x(i: Int) = pts[i * 3]
    fun y(i: Int) = pts[i * 3 + 1]
    fun w(i: Int) = pts[i * 3 + 2]

    override fun bounds(): RectF {
        if (count == 0) return RectF()
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until count) {
            val x = x(i); val y = y(i)
            if (x < l) l = x; if (x > r) r = x
            if (y < t) t = y; if (y > b) b = y
        }
        val pad = size / 2f + 2f
        return RectF(l - pad, t - pad, r + pad, b + pad)
    }

    override fun transformed(m: Matrix): StrokeElement {
        val out = pts.copyOf()
        val tmp = FloatArray(2)
        for (i in 0 until count) {
            tmp[0] = pts[i * 3]; tmp[1] = pts[i * 3 + 1]
            m.mapPoints(tmp)
            out[i * 3] = tmp[0]; out[i * 3 + 1] = tmp[1]
        }
        return StrokeElement(out, color, size * m.mapRadius(1f), kind, opacity, tipId)
    }

    fun withColor(c: Int) = StrokeElement(pts, c, size, kind, opacity, tipId).also { it.cachedPath = cachedPath }
    fun withOpacity(o: Float) = StrokeElement(pts, color, size, kind, o, tipId).also { it.cachedPath = cachedPath }
}

/** Растровый элемент: заливка или импортированное изображение. matrix — локальная → координаты слоя. */
class BitmapElement(val bitmap: Bitmap, val matrix: Matrix, val alpha: Float = 1f) : Element() {
    var encoded: String? = null

    override fun bounds(): RectF {
        val r = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        matrix.mapRect(r)
        return r
    }

    override fun transformed(m: Matrix): BitmapElement {
        val mm = Matrix(matrix); mm.postConcat(m)
        return BitmapElement(bitmap, mm, alpha).also { it.encoded = encoded }
    }

    fun withAlpha(a: Float) = BitmapElement(bitmap, matrix, a).also { it.encoded = encoded }
}

/** Трансформация ключевого кадра (для классического твина движения). */
data class Transform(
    val tx: Float = 0f, val ty: Float = 0f,
    val rot: Float = 0f,
    val sx: Float = 1f, val sy: Float = 1f,
    val alpha: Float = 1f,
    val px: Float = 0f, val py: Float = 0f
) {
    fun matrix(m: Matrix = Matrix()): Matrix {
        m.setTranslate(-px, -py)
        m.postScale(sx, sy)
        m.postRotate(rot)
        m.postTranslate(px + tx, py + ty)
        return m
    }

    val isIdentity: Boolean get() = tx == 0f && ty == 0f && rot == 0f && sx == 1f && sy == 1f && alpha == 1f

    companion object {
        fun lerp(a: Transform, b: Transform, t: Float) = Transform(
            lerp(a.tx, b.tx, t), lerp(a.ty, b.ty, t), lerp(a.rot, b.rot, t),
            lerp(a.sx, b.sx, t), lerp(a.sy, b.sy, t), lerp(a.alpha, b.alpha, t).coerceIn(0f, 1f),
            lerp(a.px, b.px, t), lerp(a.py, b.py, t)
        )
    }
}

class Keyframe(
    val elements: MutableList<Element> = mutableListOf(),
    var tween: TweenType = TweenType.NONE,
    var easing: Easing = Easing.LINEAR,
    var transform: Transform = Transform(),
    var label: String = ""
) {
    fun copy() = Keyframe(elements.toMutableList(), tween, easing, transform, label)
    val isEmpty: Boolean get() = elements.isEmpty()
}

/** Кость в позе покоя (мировые координаты документа). angle — в градусах. */
data class Bone(
    val id: String = newId(),
    val name: String,
    val parentId: String?,
    val hx: Float, val hy: Float,
    val length: Float,
    val angle: Float
) {
    val tailX: Float get() = hx + length * cos(Math.toRadians(angle.toDouble())).toFloat()
    val tailY: Float get() = hy + length * sin(Math.toRadians(angle.toDouble())).toFloat()
}

/** Поза: для каждой кости [поворот°, dx, dy] относительно позы покоя. */
class Pose(val values: HashMap<String, FloatArray> = HashMap(), var easing: Easing = Easing.EASE_IN_OUT) {
    fun copy() = Pose(HashMap(values.mapValues { it.value.copyOf() }), easing)
}

class Layer(
    val id: String = newId(),
    var name: String,
    var type: LayerType = LayerType.DRAWING,
    var visible: Boolean = true,
    var locked: Boolean = false,
    var opacity: Float = 1f,
    var blend: BlendMode = BlendMode.NORMAL,
    var guide: Boolean = false,
    var color: Int = 0xFF4FC3F7.toInt(),
    val keyframes: TreeMap<Int, Keyframe> = TreeMap(),
    val bones: MutableList<Bone> = mutableListOf(),
    val poses: TreeMap<Int, Pose> = TreeMap(),
    var bindLayer: String? = null,
    var bindBone: String? = null
) {
    fun copy(freshId: Boolean = false): Layer {
        val l = Layer(
            if (freshId) newId() else id, name, type, visible, locked, opacity, blend, guide, color,
            bindLayer = bindLayer, bindBone = bindBone
        )
        keyframes.forEach { (f, k) -> l.keyframes[f] = k.copy() }
        l.bones.addAll(bones)
        poses.forEach { (f, p) -> l.poses[f] = p.copy() }
        return l
    }
}

class ProjectState(
    val layers: List<Layer>, val frameCount: Int, val name: String, val fps: Int,
    val bgColor: Int, val bgType: BgType
)

class Project(
    val id: String = newId(),
    var name: String,
    var width: Int,
    var height: Int,
    var fps: Int = 24,
    var frameCount: Int = 48,
    var bgColor: Int = Color.WHITE,
    var bgType: BgType = BgType.SOLID,
    val layers: MutableList<Layer> = mutableListOf(),
    val palette: MutableList<Int> = defaultPalette().toMutableList(),
    var created: Long = System.currentTimeMillis(),
    var modified: Long = System.currentTimeMillis()
) {
    fun layer(id: String?): Layer? = if (id == null) null else layers.firstOrNull { it.id == id }

    fun snapshot() = ProjectState(layers.map { it.copy() }, frameCount, name, fps, bgColor, bgType)

    fun restore(s: ProjectState) {
        layers.clear(); layers.addAll(s.layers.map { it.copy() })
        frameCount = s.frameCount; name = s.name; fps = s.fps; bgColor = s.bgColor; bgType = s.bgType
    }

    /** Независимая копия для фонового сохранения/экспорта. */
    fun detachedCopy(): Project = Project(
        id, name, width, height, fps, frameCount, bgColor, bgType,
        layers.map { it.copy() }.toMutableList(), palette.toMutableList(), created, modified
    )

    companion object {
        fun defaultPalette() = listOf(
            0xFF000000, 0xFFFFFFFF, 0xFF7F7F7F, 0xFFE53935, 0xFFFF7043, 0xFFFFCA28, 0xFF9CCC65, 0xFF43A047,
            0xFF26C6DA, 0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFEC407A, 0xFF8D6E63, 0xFFFFE0BD, 0xFFF1C27D
        ).map { it.toInt() }
    }
}
