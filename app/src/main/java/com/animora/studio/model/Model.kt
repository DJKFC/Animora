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

enum class LayerType { DRAWING, BONES, AUDIO, CAMERA, FOLDER }

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
        /** Интерполяция с отдельным прогрессом для позиции, поворота, масштаба и прозрачности. */
        fun lerpProps(a: Transform, b: Transform, tp: Float, tr: Float, ts: Float, ta: Float) = Transform(
            lerp(a.tx, b.tx, tp), lerp(a.ty, b.ty, tp), lerp(a.rot, b.rot, tr),
            lerp(a.sx, b.sx, ts), lerp(a.sy, b.sy, ts), lerp(a.alpha, b.alpha, ta).coerceIn(0f, 1f),
            lerp(a.px, b.px, tp), lerp(a.py, b.py, tp)
        )

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
    var label: String = "",
    /** Своя кривая сглаживания (если задана — заменяет пресет easing). */
    var curve: EaseCurve? = null,
    /** Отдельные кривые для свойств (как в редакторе движения Animate). */
    var separateCurves: Boolean = false,
    val propCurves: HashMap<CurveProp, EaseCurve> = HashMap(),
    /** Направляющая движения (x,y парами, координаты родителя слоя): твин движения идёт вдоль неё. */
    var guide: FloatArray? = null,
    /** Поворачивать объект по касательной к направляющей. */
    var orientToPath: Boolean = false
) {
    fun copy() = Keyframe(elements.toMutableList(), tween, easing, transform, label, curve, separateCurves, HashMap(propCurves), guide, orientToPath)

    /** Прогресс свойства prop при линейном прогрессе raw (0..1) с учётом кривых. */
    fun ease(prop: CurveProp, raw: Float): Float =
        (if (separateCurves) propCurves[prop] else null)?.eval(raw) ?: curve?.eval(raw) ?: easing.apply(raw)

    /** Кривая, которая сейчас действует для свойства (для редактора). */
    fun curveFor(prop: CurveProp?): EaseCurve =
        (if (prop != null && separateCurves) propCurves[prop] else null) ?: curve ?: EaseCurve.fromEasing(easing)

    val hasCustomCurve: Boolean get() = curve != null || (separateCurves && propCurves.isNotEmpty())
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
class Pose(val values: HashMap<String, FloatArray> = HashMap(), var easing: Easing = Easing.EASE_IN_OUT, var curve: EaseCurve? = null) {
    fun copy() = Pose(HashMap(values.mapValues { it.value.copyOf() }), easing, curve)
    fun ease(raw: Float): Float = curve?.eval(raw) ?: easing.apply(raw)
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
    var bindBone: String? = null,
    /** Слой-маска: сам не выводится, а ограничивает видимость слоёв, привязанных к нему. */
    var isMask: Boolean = false,
    /** id слоя-маски, которым маскируется этот слой. */
    var maskedBy: String? = null,
    /** Глубина для камеры: 0 — обычно, больше — дальше (параллакс), −1 — закреплён на экране. */
    var depth: Float = 0f,
    /** Звуковой клип (для слоя типа AUDIO). */
    var audio: AudioClip? = null,
    /** Родительский слой: его трансформация (твин движения) переносится на этот слой. */
    var parentId: String? = null,
    /** Папка, в которой лежит слой. */
    var folderId: String? = null,
    /** Для папки — свёрнута ли она в таймлайне и панели слоёв. */
    var collapsed: Boolean = false
) {
    fun copy(freshId: Boolean = false): Layer {
        val l = Layer(
            if (freshId) newId() else id, name, type, visible, locked, opacity, blend, guide, color,
            bindLayer = bindLayer, bindBone = bindBone, isMask = isMask, maskedBy = maskedBy, depth = depth, audio = audio,
            parentId = parentId, folderId = folderId, collapsed = collapsed
        )
        keyframes.forEach { (f, k) -> l.keyframes[f] = k.copy() }
        l.bones.addAll(bones)
        poses.forEach { (f, p) -> l.poses[f] = p.copy() }
        return l
    }
}

class ProjectState(
    val layers: List<Layer>, val frameCount: Int, val name: String, val fps: Int,
    val bgColor: Int, val bgType: BgType, val symbols: List<Symbol> = emptyList()
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
    var modified: Long = System.currentTimeMillis(),
    /** Библиотека символов (клипов со своим таймлайном). */
    val symbols: MutableList<Symbol> = mutableListOf()
) {
    fun layer(id: String?): Layer? = if (id == null) null else layers.firstOrNull { it.id == id }
    fun symbol(id: String?): Symbol? = if (id == null) null else symbols.firstOrNull { it.id == id }

    fun snapshot() = ProjectState(layers.map { it.copy() }, frameCount, name, fps, bgColor, bgType, symbols.map { it.copy() })

    fun restore(s: ProjectState) {
        layers.clear(); layers.addAll(s.layers.map { it.copy() })
        frameCount = s.frameCount; name = s.name; fps = s.fps; bgColor = s.bgColor; bgType = s.bgType
        symbols.clear(); symbols.addAll(s.symbols.map { it.copy() })
    }

    /** Независимая копия для фонового сохранения/экспорта. */
    fun detachedCopy(): Project = Project(
        id, name, width, height, fps, frameCount, bgColor, bgType,
        layers.map { it.copy() }.toMutableList(), palette.toMutableList(), created, modified,
        symbols.map { it.copy() }.toMutableList()
    )

    companion object {
        fun defaultPalette() = listOf(
            0xFF000000, 0xFFFFFFFF, 0xFF7F7F7F, 0xFFE53935, 0xFFFF7043, 0xFFFFCA28, 0xFF9CCC65, 0xFF43A047,
            0xFF26C6DA, 0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFEC407A, 0xFF8D6E63, 0xFFFFE0BD, 0xFFF1C27D
        ).map { it.toInt() }
    }
}

/**
 * Звуковой клип на слое. Файл лежит в папке проекта (audio/fileName).
 * startFrame — кадр, с которого звучит; offsetMs — сколько отрезать от начала файла.
 */
data class AudioClip(
    val id: String = newId(),
    val name: String,
    val fileName: String,
    val startFrame: Int = 0,
    val offsetMs: Long = 0,
    val durationMs: Long,
    val volume: Float = 1f,
    val muted: Boolean = false
) {
    /** Длительность звучания в кадрах. */
    fun frames(fps: Int): Int = (((durationMs - offsetMs).coerceAtLeast(0) * fps + 999) / 1000).toInt()
    fun endFrame(fps: Int): Int = startFrame + frames(fps)
}

/**
 * Символ — клип со своими слоями и таймлайном (как символ-график в Animate).
 * Точка привязки символа — центр документа (width/2, height/2) в координатах символа.
 */
class Symbol(
    val id: String = newId(),
    var name: String,
    val layers: MutableList<Layer> = mutableListOf(),
    var frameCount: Int = 1
) {
    fun copy() = Symbol(id, name, layers.map { it.copy() }.toMutableList(), frameCount)
}

enum class LoopMode(val title: String) { LOOP("Цикл"), ONCE("Один раз"), SINGLE("Один кадр") }

/** Цветовой эффект экземпляра: прозрачность, тонирование, яркость (−1..1). */
data class ColorEffect(val alpha: Float = 1f, val tint: Int = 0xFFFFFFFF.toInt(), val tintAmount: Float = 0f, val brightness: Float = 0f) {
    val isDefault: Boolean get() = alpha >= 0.999f && tintAmount <= 0.001f && kotlin.math.abs(brightness) <= 0.001f
}

/** Экземпляр символа на кадре. matrix — координаты символа → координаты слоя. */
class InstanceElement(
    val symbolId: String,
    val matrix: Matrix,
    val loop: LoopMode = LoopMode.LOOP,
    val firstFrame: Int = 0,
    val effect: ColorEffect = ColorEffect()
) : Element() {
    override fun bounds(): RectF {
        val r = SymbolRegistry.bounds(symbolId)
        matrix.mapRect(r)
        return r
    }

    override fun transformed(m: Matrix): InstanceElement {
        val mm = Matrix(matrix); mm.postConcat(m)
        return InstanceElement(symbolId, mm, loop, firstFrame, effect)
    }

    fun with(loop: LoopMode = this.loop, firstFrame: Int = this.firstFrame, effect: ColorEffect = this.effect, symbolId: String = this.symbolId) =
        InstanceElement(symbolId, Matrix(matrix), loop, firstFrame, effect)

    /** Кадр символа для локального времени t (кадров от начала ключа). */
    fun symbolFrame(t: Int, count: Int): Int {
        val n = count.coerceAtLeast(1)
        return when (loop) {
            LoopMode.LOOP -> ((firstFrame + t) % n + n) % n
            LoopMode.ONCE -> (firstFrame + t).coerceIn(0, n - 1)
            LoopMode.SINGLE -> firstFrame.coerceIn(0, n - 1)
        }
    }
}

/** Доступ к символам открытого проекта (для габаритов экземпляров). */
object SymbolRegistry {
    @Volatile var project: Project? = null

    private val depth = ThreadLocal<Int>()

    fun bounds(id: String): RectF {
        val d = depth.get() ?: 0
        if (d > 6) return RectF()
        depth.set(d + 1)
        try { return boundsInner(id) } finally { depth.set(d) }
    }

    private fun boundsInner(id: String): RectF {
        val sym = project?.symbol(id) ?: return RectF(-20f, -20f, 20f, 20f)
        var r: RectF? = null
        for (l in sym.layers) for (k in l.keyframes.values) for (e in k.elements) {
            if (e is InstanceElement && e.symbolId == id) continue
            val b = runCatching { e.bounds() }.getOrNull() ?: continue
            k.transform.matrix().mapRect(b)
            if (r == null) r = RectF(b) else r.union(b)
        }
        val p = project
        return r ?: RectF((p?.width ?: 0) / 2f - 20f, (p?.height ?: 0) / 2f - 20f, (p?.width ?: 0) / 2f + 20f, (p?.height ?: 0) / 2f + 20f)
    }
}

enum class TextFont(val title: String) { SANS("Без засечек"), SERIF("С засечками"), MONO("Моноширинный"), HAND("Рукописный"), CONDENSED("Узкий") }
enum class TextAlign(val title: String) { LEFT("Влево"), CENTER("По центру"), RIGHT("Вправо") }

/** Текстовый блок. matrix — координаты текста (0,0 — левый верх первой строки) → слой. */
class TextElement(
    val text: String,
    val size: Float,
    val color: Int,
    val font: TextFont = TextFont.SANS,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: TextAlign = TextAlign.LEFT,
    val matrix: Matrix = Matrix(),
    val alpha: Float = 1f,
    val outline: Float = 0f,
    val outlineColor: Int = 0xFF000000.toInt()
) : Element() {
    val lines: List<String> get() = text.split('\n')

    /** Приблизительная ширина без Paint (для габаритов в модели): уточняется в рендере. */
    private fun approxWidth(): Float = (lines.maxOfOrNull { it.length } ?: 1) * size * (if (font == TextFont.MONO) 0.6f else 0.55f)

    fun localBounds(): RectF {
        val w = measure?.invoke(this) ?: approxWidth()
        val left = when (align) { TextAlign.LEFT -> 0f; TextAlign.CENTER -> -w / 2; TextAlign.RIGHT -> -w }
        return RectF(left - outline, -size * 0.9f - outline, left + w + outline, size * 1.2f * (lines.size - 1) + size * 0.3f + outline)
    }

    override fun bounds(): RectF { val r = localBounds(); matrix.mapRect(r); return r }

    override fun transformed(m: Matrix): TextElement {
        val mm = Matrix(matrix); mm.postConcat(m)
        return copyWith(matrix = mm)
    }

    fun copyWith(
        text: String = this.text, size: Float = this.size, color: Int = this.color, font: TextFont = this.font,
        bold: Boolean = this.bold, italic: Boolean = this.italic, align: TextAlign = this.align,
        matrix: Matrix = Matrix(this.matrix), alpha: Float = this.alpha, outline: Float = this.outline, outlineColor: Int = this.outlineColor
    ) = TextElement(text, size, color, font, bold, italic, align, matrix, alpha, outline, outlineColor)

    companion object {
        /** Точное измерение ширины, подключается рендером (Paint). */
        @Volatile var measure: ((TextElement) -> Float)? = null
    }
}
