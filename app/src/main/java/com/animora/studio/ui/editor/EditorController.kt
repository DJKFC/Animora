package com.animora.studio.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.animora.studio.engine.Bones
import com.animora.studio.engine.FloodFill
import com.animora.studio.engine.FrameOptions
import com.animora.studio.engine.History
import com.animora.studio.engine.Interp
import com.animora.studio.engine.KritaImporter
import com.animora.studio.engine.Renderer
import com.animora.studio.engine.distToSegment
import com.animora.studio.engine.normalizeDeg
import com.animora.studio.engine.pointInPolygon
import com.animora.studio.export.ExportSettings
import com.animora.studio.export.Exporter
import com.animora.studio.model.BitmapElement
import com.animora.studio.model.Bone
import com.animora.studio.model.BrushLibrary
import com.animora.studio.model.BrushRegistry
import com.animora.studio.model.CustomBrush
import com.animora.studio.model.BrushKind
import com.animora.studio.model.Easing
import com.animora.studio.model.Element
import com.animora.studio.model.Keyframe
import com.animora.studio.model.Layer
import com.animora.studio.model.LayerType
import com.animora.studio.model.Pose
import com.animora.studio.model.Project
import com.animora.studio.model.ProjectStore
import com.animora.studio.model.Serializer
import com.animora.studio.model.StrokeElement
import com.animora.studio.model.Transform
import com.animora.studio.model.TweenType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.TreeMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class BrushSettings(
    val size: Float,
    val opacity: Float,
    val smoothing: Float,
    val pressureSize: Boolean,
    val pressureOpacity: Boolean,
    val taper: Boolean,
    val tiltSize: Boolean,
    val curve: Float = 1f
)

enum class Tool(val title: String) {
    BRUSH("Кисть"), ERASER("Ластик"), FILL("Заливка"), EYEDROPPER("Пипетка"),
    LINE("Линия"), RECT("Прямоугольник"), ELLIPSE("Эллипс"),
    LASSO("Лассо"), TRANSFORM("Трансформация ключа"),
    BONE("Кость"), POSE("Поза"), HAND("Рука")
}

enum class Symmetry(val title: String) { NONE("Выкл"), VERTICAL("Вертикальная"), HORIZONTAL("Горизонтальная"), QUAD("Четверная") }
enum class PlayMode(val title: String) { LOOP("Цикл"), PINGPONG("Туда-обратно"), ONCE("Один раз") }
enum class AutoKeyMode(val title: String) { BLANK("Пустой ключ"), COPY("Копия ключа"), EDIT("Править удерживаемый") }
enum class DragKind { MOVE, SCALE, ROTATE }

class Selection(val layerId: String, val key: Int, val indices: List<Int>)

const val MAX_FRAMES = 3000

class EditorController(
    val project: Project,
    private val store: ProjectStore,
    private val scope: CoroutineScope,
    private val appContext: Context
) {
    val history = History(project)

    var version by mutableIntStateOf(0)
        private set
    var frame by mutableIntStateOf(0)
        private set
    var activeLayerId by mutableStateOf(project.layers.lastOrNull { it.type == LayerType.DRAWING }?.id ?: project.layers.lastOrNull()?.id)
    var tool by mutableStateOf(Tool.BRUSH)
    var brushKind by mutableStateOf(BrushKind.PEN)
    val brushSettings = mutableStateMapOf(
        BrushKind.PEN to BrushSettings(6f, 1f, 0.35f, true, false, true, false),
        BrushKind.PENCIL to BrushSettings(3f, 0.85f, 0.2f, true, true, false, true),
        BrushKind.MARKER to BrushSettings(18f, 0.6f, 0.3f, false, false, false, false),
        BrushKind.SOFT to BrushSettings(40f, 0.35f, 0.3f, true, true, false, false),
        BrushKind.CALLIGRAPHY to BrushSettings(14f, 1f, 0.45f, true, false, true, false),
        BrushKind.ERASER to BrushSettings(24f, 1f, 0.2f, true, false, false, false),
        BrushKind.CUSTOM to BrushSettings(20f, 1f, 0.2f, true, false, false, false)
    )

    // импортированные кисти (Krita .kpp / .bundle / .gbr / .gih / .png)
    private val brushLibrary = BrushLibrary(appContext)
    val customBrushes = mutableStateListOf<CustomBrush>()
    var customBrushId by mutableStateOf<String?>(null)
    val customSettings = mutableStateMapOf<String, BrushSettings>()
    var color by mutableIntStateOf(Color.BLACK)
    val recentColors = mutableStateListOf<Int>()

    // воспроизведение
    var playing by mutableStateOf(false)
        private set
    var playMode by mutableStateOf(PlayMode.LOOP)
    var rangeFrom by mutableIntStateOf(0)
    var rangeTo by mutableIntStateOf(project.frameCount - 1)
    var useRange by mutableStateOf(false)

    // луковая кожа
    var onionEnabled by mutableStateOf(true)
    var onionPrev by mutableIntStateOf(2)
    var onionNext by mutableIntStateOf(1)
    var onionOpacity by mutableFloatStateOf(0.35f)
    var onionAllLayers by mutableStateOf(false)
    var onionKeysOnly by mutableStateOf(true)
    var onionTint by mutableStateOf(true)

    // параметры инструментов и холста
    var stylusOnly by mutableStateOf(false)
    var stylusDetected by mutableStateOf(false)
    var symmetry by mutableStateOf(Symmetry.NONE)
    var fillTolerance by mutableIntStateOf(40)
    var fillGap by mutableIntStateOf(2)
    var fillBehind by mutableStateOf(true)
    var fillAllLayers by mutableStateOf(true)
    var strokeEraser by mutableStateOf(false)
    var autoKey by mutableStateOf(AutoKeyMode.BLANK)
    var ikMode by mutableStateOf(true)
    var showGrid by mutableStateOf(false)
    var showSafeArea by mutableStateOf(false)
    var flipView by mutableStateOf(false)
    var showBones by mutableStateOf(true)
    var shapeFilled by mutableStateOf(false)
    var viewResetToken by mutableIntStateOf(0)

    var selection by mutableStateOf<Selection?>(null)
    var selectedBoneId by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var exportProgress by mutableStateOf<Float?>(null)

    var dirty = false
    private var clipboard: List<Element>? = null
    private var keyClipboard: Keyframe? = null

    fun settingsFor(kind: BrushKind): BrushSettings =
        if (kind == BrushKind.CUSTOM) customBrushId?.let { customSettings[it] } ?: brushSettings[BrushKind.CUSTOM]!!
        else brushSettings[kind]!!

    val brush: BrushSettings get() = settingsFor(if (tool == Tool.ERASER) BrushKind.ERASER else brushKind)

    fun updateBrush(kind: BrushKind, f: (BrushSettings) -> BrushSettings) {
        val id = customBrushId
        if (kind == BrushKind.CUSTOM && id != null) customSettings[id] = f(settingsFor(kind))
        else brushSettings[kind] = f(brushSettings[kind]!!)
    }

    /** id штамповой кисти для новых штрихов (или null для встроенных кистей). */
    val activeTipId: String? get() = if (brushKind == BrushKind.CUSTOM) customBrushId?.takeIf { BrushRegistry.get(it) != null } else null

    fun selectCustomBrush(id: String) {
        val b = customBrushes.firstOrNull { it.id == id } ?: return
        if (!customSettings.containsKey(id)) customSettings[id] = BrushSettings(
            b.defaultSize, b.defaultOpacity, 0.25f, b.pressureSize, b.pressureOpacity, false, false
        )
        customBrushId = id; brushKind = BrushKind.CUSTOM; tool = Tool.BRUSH
    }

    init {
        scope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { brushLibrary.loadAll() }.getOrDefault(emptyList()) }
            customBrushes.clear(); customBrushes.addAll(list)
        }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        }
        return uri.lastPathSegment ?: "brush"
    }

    /** Импорт кистей Krita из выбранных файлов. */
    fun importBrushes(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = "Импорт кистей…"
        scope.launch {
            val warnings = ArrayList<String>()
            val added = withContext(Dispatchers.IO) {
                val out = ArrayList<CustomBrush>()
                for (u in uris) {
                    val name = displayName(u)
                    runCatching {
                        val bytes = appContext.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: error("нет доступа")
                        val r = KritaImporter.import(name, bytes)
                        r.brushes.forEach { brushLibrary.save(it); out.add(it) }
                        warnings.addAll(r.warnings)
                    }.onFailure { warnings.add("$name: ${it.message ?: "ошибка"}") }
                }
                out
            }
            busy = null
            customBrushes.addAll(added)
            added.firstOrNull()?.let { selectCustomBrush(it.id) }
            toast(buildString {
                append(if (added.isEmpty()) "Кисти не импортированы" else "Импортировано кистей: ${added.size}")
                if (warnings.isNotEmpty()) append(". ").append(warnings.take(2).joinToString("; "))
                if (warnings.size > 2) append(" (+${warnings.size - 2})")
            })
        }
    }

    fun deleteCustomBrush(id: String) {
        brushLibrary.delete(id)
        customBrushes.removeAll { it.id == id }
        customSettings.remove(id)
        if (customBrushId == id) { customBrushId = null; if (brushKind == BrushKind.CUSTOM) brushKind = BrushKind.PEN }
    }
    val activeLayer: Layer? get() = project.layer(activeLayerId)

    fun changed() { version++; dirty = true }
    fun toast(s: String) { message = s }

    fun renderKey(): String =
        "$version|$frame|$onionEnabled|$onionPrev|$onionNext|$onionOpacity|$onionAllLayers|$onionKeysOnly|$onionTint|$activeLayerId|$playing"

    fun overlayKey(): String =
        "${selection?.indices?.size}|$selectedBoneId|$showGrid|$showSafeArea|$showBones|$flipView|$viewResetToken|$tool|$symmetry|$color"

    // ---------------- история ----------------
    fun checkpoint() = history.checkpoint()

    private var lastTag = ""; private var lastTagTime = 0L
    /** Один checkpoint на серию изменений (слайдеры). */
    fun checkpointOnce(tag: String) {
        val now = SystemClock.uptimeMillis()
        if (tag != lastTag || now - lastTagTime > 1200) checkpoint()
        lastTag = tag; lastTagTime = now
    }

    fun undo() { if (history.undo()) { afterRestore(); toast("Отменено") } else toast("Нечего отменять") }
    fun redo() { if (history.redo()) { afterRestore(); toast("Повторено") } else toast("Нечего повторять") }

    private fun afterRestore() {
        selection = null
        if (project.layer(activeLayerId) == null) activeLayerId = project.layers.lastOrNull()?.id
        frame = frame.coerceIn(0, project.frameCount - 1)
        rangeTo = rangeTo.coerceAtMost(project.frameCount - 1)
        changed()
    }

    // ---------------- кадры / ключи ----------------
    fun seek(f: Int) {
        val nf = f.coerceIn(0, project.frameCount - 1)
        if (nf != frame) { frame = nf; selection = null }
    }

    fun editableLayer(silent: Boolean = false): Layer? {
        val l = activeLayer ?: return null
        val err = when {
            l.type != LayerType.DRAWING -> "Выберите слой рисования"
            l.locked -> "Слой заблокирован"
            !l.visible -> "Слой скрыт"
            else -> null
        }
        if (err != null) { if (!silent) toast(err); return null }
        return l
    }

    /** Ключ для рисования в текущем кадре (вызывать после checkpoint). */
    fun keyForDrawing(l: Layer): Keyframe {
        l.keyframes[frame]?.let { return it }
        val held = l.keyframes.floorEntry(frame)
        if (held != null && autoKey == AutoKeyMode.EDIT) return held.value
        val tr = if (held != null) Renderer.keyTransformAt(l, frame) ?: Transform() else Transform()
        val k = if (held != null && autoKey == AutoKeyMode.COPY) held.value.copy().also { it.transform = tr; it.tween = TweenType.NONE }
        else Keyframe(transform = tr)
        l.keyframes[frame] = k
        return k
    }

    private fun inverseLayerMatrix(l: Layer): Matrix {
        val inv = Matrix(); Renderer.layerMatrix(project, l, frame).invert(inv); return inv
    }

    fun insertKeyframe() {
        val l = activeLayer ?: return
        if (l.type == LayerType.BONES) { insertPoseKey(); return }
        if (l.keyframes.containsKey(frame)) { toast("Здесь уже есть ключевой кадр"); return }
        checkpoint()
        val held = l.keyframes.floorEntry(frame)
        val k = held?.value?.copy() ?: Keyframe()
        k.transform = Renderer.keyTransformAt(l, frame) ?: Transform()
        if (held != null && held.value.tween == TweenType.SHAPE) {
            val next = l.keyframes.higherEntry(held.key)
            if (next != null) {
                val t = held.value.easing.apply((frame - held.key).toFloat() / (next.key - held.key))
                k.elements.clear(); k.elements.addAll(Interp.morphElements(held.value.elements, next.value.elements, t))
            }
        }
        l.keyframes[frame] = k
        changed(); toast("Ключевой кадр добавлен")
    }

    fun insertBlankKeyframe() {
        val l = activeLayer ?: return
        if (l.type != LayerType.DRAWING) return
        checkpoint()
        l.keyframes[frame] = Keyframe(transform = Renderer.keyTransformAt(l, frame) ?: Transform())
        changed()
    }

    fun clearKeyframe() {
        val l = activeLayer ?: return
        if (l.type == LayerType.BONES) {
            if (l.poses.containsKey(frame)) { checkpoint(); l.poses.remove(frame); changed() } else toast("Нет ключа позы в этом кадре")
            return
        }
        if (!l.keyframes.containsKey(frame)) { toast("В этом кадре нет ключа"); return }
        checkpoint(); l.keyframes.remove(frame); selection = null; changed()
    }

    fun clearKeyContent() {
        val l = editableLayer() ?: return
        val k = l.keyframes.floorEntry(frame)?.value ?: return
        checkpoint(); k.elements.clear(); selection = null; changed()
    }

    private fun shiftKeys(map: TreeMap<Int, Keyframe>, at: Int, delta: Int) {
        val moved = map.tailMap(at, false).entries.map { it.key to it.value }
        moved.forEach { map.remove(it.first) }
        moved.forEach { map[it.first + delta] = it.second }
    }

    private fun shiftPoses(map: TreeMap<Int, Pose>, at: Int, delta: Int) {
        val moved = map.tailMap(at, false).entries.map { it.key to it.value }
        moved.forEach { map.remove(it.first) }
        moved.forEach { map[it.first + delta] = it.second }
    }

    fun insertFrame(allLayers: Boolean) {
        if (project.frameCount >= MAX_FRAMES) { toast("Максимум $MAX_FRAMES кадров"); return }
        checkpoint()
        val targets = if (allLayers) project.layers else listOfNotNull(activeLayer)
        targets.forEach { shiftKeys(it.keyframes, frame, 1); shiftPoses(it.poses, frame, 1) }
        project.frameCount += 1
        rangeTo = project.frameCount - 1
        changed()
    }

    fun removeFrame(allLayers: Boolean) {
        if (project.frameCount <= 1) return
        checkpoint()
        val targets = if (allLayers) project.layers else listOfNotNull(activeLayer)
        for (l in targets) {
            val old = TreeMap(l.keyframes); l.keyframes.clear()
            for ((k, v) in old) when {
                k < frame -> l.keyframes[k] = v
                k == frame -> if (!old.containsKey(frame + 1)) l.keyframes[k] = v
                else -> l.keyframes[k - 1] = v
            }
            val oldP = TreeMap(l.poses); l.poses.clear()
            for ((k, v) in oldP) when {
                k < frame -> l.poses[k] = v
                k == frame -> if (!oldP.containsKey(frame + 1)) l.poses[k] = v
                else -> l.poses[k - 1] = v
            }
        }
        if (allLayers) project.frameCount -= 1
        frame = frame.coerceIn(0, project.frameCount - 1)
        rangeTo = rangeTo.coerceAtMost(project.frameCount - 1)
        changed()
    }

    fun setFrameCount(n: Int) {
        val c = n.coerceIn(1, MAX_FRAMES)
        if (c == project.frameCount) return
        checkpoint(); project.frameCount = c
        frame = frame.coerceIn(0, c - 1); rangeTo = c - 1; rangeFrom = rangeFrom.coerceAtMost(c - 1)
        changed()
    }

    fun copyKeyframe() {
        val l = activeLayer ?: return
        keyClipboard = l.keyframes.floorEntry(frame)?.value?.copy()
        toast(if (keyClipboard != null) "Ключ скопирован" else "Нет ключа")
    }

    fun pasteKeyframe() {
        val l = editableLayer() ?: return
        val k = keyClipboard ?: run { toast("Буфер пуст"); return }
        checkpoint(); l.keyframes[frame] = k.copy(); changed()
    }

    fun heldKey(l: Layer? = activeLayer): Pair<Int, Keyframe>? = l?.keyframes?.floorEntry(frame)?.let { it.key to it.value }

    fun setTween(t: TweenType) {
        val l = activeLayer ?: return
        val (key, k) = heldKey(l) ?: run { toast("Нет ключевого кадра"); return }
        checkpoint()
        k.tween = t
        if (t != TweenType.NONE && l.keyframes.higherKey(key) == null) {
            // как в Animate: создаём конечный ключ автоматически
            val end = if (frame > key) frame else min(project.frameCount - 1, key + max(6, project.fps))
            if (end > key) {
                l.keyframes[end] = k.copy().also { it.tween = TweenType.NONE }
                toast("Создан конечный ключ в кадре ${end + 1} — измените его")
            }
        }
        if (t == TweenType.SHAPE) {
            val next = l.keyframes.higherEntry(key)?.value
            val a = k.elements.count { it is StrokeElement }; val b = next?.elements?.count { it is StrokeElement } ?: 0
            if (next != null && a != b) toast("Число штрихов разное ($a / $b): лишние будут проявляться")
        }
        changed()
    }

    fun setEasing(e: Easing) {
        val l = activeLayer ?: return
        if (l.type == LayerType.BONES) {
            val p = l.poses.floorEntry(frame)?.value ?: return
            checkpoint(); p.easing = e; changed(); return
        }
        val (_, k) = heldKey(l) ?: return
        checkpoint(); k.easing = e; changed()
    }

    /** Новое: «запечь» твин в реальные промежуточные кадры для ручной доработки. */
    fun bakeTween() {
        val l = editableLayer() ?: return
        val (a, k) = heldKey(l) ?: return
        val b = l.keyframes.higherKey(a)
        if (k.tween == TweenType.NONE || b == null) { toast("Нужен твин между двумя ключами"); return }
        checkpoint()
        for (f in a + 1 until b) {
            val tr = Renderer.keyTransformAt(l, f) ?: Transform()
            val els = if (k.tween == TweenType.SHAPE) {
                val t = k.easing.apply((f - a).toFloat() / (b - a))
                Interp.morphElements(k.elements, l.keyframes[b]!!.elements, t)
            } else k.elements
            val m = tr.matrix()
            l.keyframes[f] = Keyframe(els.map { it.transformed(m) }.toMutableList(), transform = Transform(alpha = tr.alpha))
        }
        k.tween = TweenType.NONE
        changed(); toast("Создано ${b - a - 1} промежуточных кадров")
    }

    /** Новое: развернуть анимацию слоя во времени. */
    fun reverseLayer() {
        val l = activeLayer ?: return
        if (l.keyframes.isEmpty()) return
        checkpoint()
        val first = l.keyframes.firstKey()
        val end = project.frameCount
        val entries = l.keyframes.entries.map { it.key to it.value }
        l.keyframes.clear()
        entries.forEachIndexed { i, (k, v) ->
            val e = if (i + 1 < entries.size) entries[i + 1].first else end
            l.keyframes[first + end - e] = v
        }
        changed(); toast("Порядок кадров развёрнут")
    }

    /** Новое: повторить цикл ключей слоя N раз (для ходьбы, мигания и т.п.). */
    fun repeatCycle(times: Int) {
        val l = activeLayer ?: return
        if (l.keyframes.isEmpty() || times < 1) return
        val first = l.keyframes.firstKey()
        val len = project.frameCount - first
        if (project.frameCount + len * times > MAX_FRAMES) { toast("Получится больше $MAX_FRAMES кадров"); return }
        checkpoint()
        val entries = l.keyframes.entries.map { it.key to it.value }
        for (r in 1..times) entries.forEach { (k, v) -> l.keyframes[k + len * r] = v.copy() }
        project.frameCount += len * times
        rangeTo = project.frameCount - 1
        changed()
    }

    // ---------------- воспроизведение ----------------
    private var playJob: Job? = null

    fun togglePlay() { if (playing) stop() else play() }

    fun play() {
        if (playing) return
        playing = true; selection = null
        val from = if (useRange) rangeFrom else 0
        val to = if (useRange) rangeTo else project.frameCount - 1
        if (frame !in from..to || (playMode == PlayMode.ONCE && frame == to)) frame = from
        var dir = 1
        playJob = scope.launch {
            var next = SystemClock.uptimeMillis().toDouble()
            while (isActive && playing) {
                next += 1000.0 / project.fps
                val wait = (next - SystemClock.uptimeMillis()).toLong()
                if (wait > 0) delay(wait) else yield()
                var f = frame + dir
                when (playMode) {
                    PlayMode.LOOP -> if (f > to) f = from
                    PlayMode.ONCE -> if (f > to) { f = to; playing = false }
                    PlayMode.PINGPONG -> if (f > to || f < from) { dir = -dir; f = (frame + dir).coerceIn(from, to) }
                }
                frame = f
            }
        }
    }

    fun stop() { playing = false; playJob?.cancel(); playJob = null }

    // ---------------- рисование ----------------
    fun pushRecentColor(c: Int) {
        recentColors.remove(c); recentColors.add(0, c)
        while (recentColors.size > 12) recentColors.removeAt(recentColors.lastIndex)
    }

    private fun symmetricCopies(s: StrokeElement): List<StrokeElement> {
        val cx = project.width / 2f; val cy = project.height / 2f
        fun m(sx: Float, sy: Float) = s.transformed(Matrix().apply { setScale(sx, sy, cx, cy) })
        return when (symmetry) {
            Symmetry.NONE -> listOf(s)
            Symmetry.VERTICAL -> listOf(s, m(-1f, 1f))
            Symmetry.HORIZONTAL -> listOf(s, m(1f, -1f))
            Symmetry.QUAD -> listOf(s, m(-1f, 1f), m(1f, -1f), m(-1f, -1f))
        }
    }

    fun mirroredPreview(s: StrokeElement) = symmetricCopies(s)

    fun commitStroke(s: StrokeElement) {
        val l = editableLayer() ?: return
        if (s.count == 0) return
        checkpoint()
        val k = keyForDrawing(l)
        val inv = inverseLayerMatrix(l)
        for (st in symmetricCopies(s)) k.elements.add(st.transformed(inv))
        pushRecentColor(color)
        changed()
    }

    fun shapeStroke(t: Tool, x0: Float, y0: Float, x1: Float, y1: Float): StrokeElement {
        val pts = ArrayList<Float>()
        fun add(x: Float, y: Float) { pts.add(x); pts.add(y); pts.add(1f) }
        when (t) {
            Tool.LINE -> for (i in 0..24) { val u = i / 24f; add(x0 + (x1 - x0) * u, y0 + (y1 - y0) * u) }
            Tool.RECT -> {
                val c = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1, x0 to y0)
                for (e in 0 until 4) for (i in 0 until 12) {
                    val u = i / 12f
                    add(c[e].first + (c[e + 1].first - c[e].first) * u, c[e].second + (c[e + 1].second - c[e].second) * u)
                }
                add(x0, y0)
            }
            else -> {
                val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2; val rx = (x1 - x0) / 2; val ry = (y1 - y0) / 2
                for (i in 0..72) {
                    val a = i / 72.0 * Math.PI * 2
                    add(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
                }
            }
        }
        val b = settingsFor(brushKind)
        val tip = activeTipId
        val kind = when {
            brushKind == BrushKind.ERASER -> BrushKind.PEN
            brushKind == BrushKind.CUSTOM && tip == null -> BrushKind.PEN
            else -> brushKind
        }
        return StrokeElement(pts.toFloatArray(), color, b.size, kind, b.opacity, tip)
    }

    fun commitShape(t: Tool, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (hypot(x1 - x0, y1 - y0) < 2f) return
        val s = shapeStroke(t, x0, y0, x1, y1)
        val l = editableLayer() ?: return
        checkpoint()
        val k = keyForDrawing(l)
        val inv = inverseLayerMatrix(l)
        if (shapeFilled && t != Tool.LINE) {
            // заливка фигуры — растровый элемент под контуром
            val b = s.bounds()
            val w = max(1, b.width().toInt()); val h = max(1, b.height().toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = this@EditorController.color }
            val path = android.graphics.Path()
            path.moveTo(s.x(0) - b.left, s.y(0) - b.top)
            for (i in 1 until s.count) path.lineTo(s.x(i) - b.left, s.y(i) - b.top)
            path.close(); c.drawPath(path, p)
            val m = Matrix().apply { setTranslate(b.left, b.top); postConcat(inv) }
            k.elements.add(BitmapElement(bmp, m))
        }
        for (st in symmetricCopies(s)) k.elements.add(st.transformed(inv))
        pushRecentColor(color)
        changed()
    }

    private var eraseCheckpointed = false
    private var erasedAny = false
    fun beginStrokeErase() { eraseCheckpointed = false; erasedAny = false }
    fun endStrokeErase() { if (eraseCheckpointed && !erasedAny) history.dropLast() }

    /** Штриховой ластик: удаляет целые штрихи и заливки под курсором. */
    fun eraseStrokesAt(x: Float, y: Float, radius: Float) {
        val l = editableLayer(silent = true) ?: return
        val k = l.keyframes.floorEntry(frame)?.value ?: return
        val m = Renderer.layerMatrix(project, l, frame)
        val inv = Matrix(); m.invert(inv)
        val p = floatArrayOf(x, y); inv.mapPoints(p)
        val lr = radius / max(0.01f, m.mapRadius(1f))
        val hit = k.elements.firstOrNull { e -> hits(e, p[0], p[1], lr) } ?: return
        if (!eraseCheckpointed) { checkpoint(); eraseCheckpointed = true }
        if (!l.keyframes.containsKey(frame) && autoKey != AutoKeyMode.EDIT) {
            // редактируем копию, чтобы не испортить удерживаемый кадр
            val copy = k.copy(); copy.transform = Renderer.keyTransformAt(l, frame) ?: k.transform; copy.tween = TweenType.NONE
            copy.elements.remove(hit); l.keyframes[frame] = copy
        } else k.elements.remove(hit)
        erasedAny = true
        changed()
    }

    private fun hits(e: Element, x: Float, y: Float, r: Float): Boolean = when (e) {
        is StrokeElement -> {
            var hit = false
            if (e.count == 1) hit = hypot(e.x(0) - x, e.y(0) - y) < r + e.size / 2
            for (i in 1 until e.count) {
                if (distToSegment(x, y, e.x(i - 1), e.y(i - 1), e.x(i), e.y(i)) < r + e.size * e.w(i) / 2) { hit = true; break }
            }
            hit
        }
        is BitmapElement -> {
            val inv = Matrix(); e.matrix.invert(inv)
            val p = floatArrayOf(x, y); inv.mapPoints(p)
            val px = p[0].toInt(); val py = p[1].toInt()
            px in 0 until e.bitmap.width && py in 0 until e.bitmap.height && Color.alpha(e.bitmap.getPixel(px, py)) > 20
        }
    }

    fun fillAt(x: Float, y: Float) {
        val l = editableLayer() ?: return
        if (busy != null) return
        val snap = project.detachedCopy()
        val f = frame; val c = color; val tol = fillTolerance; val gap = fillGap
        val opt = FrameOptions(background = false, includeGuides = false, onlyLayerId = if (fillAllLayers) null else l.id)
        busy = "Заливка…"
        scope.launch {
            val res = withContext(Dispatchers.Default) {
                val bmp = Renderer().renderBitmap(snap, f, 1f, opt)
                FloodFill.fill(bmp, x.toInt(), y.toInt(), c, tol, gap, 1)
            }
            busy = null
            if (res == null) { toast("Область не найдена"); return@launch }
            if (res.bitmap.width >= project.width - 1 && res.bitmap.height >= project.height - 1 && fillGap > 0)
                toast("Заливка заняла весь холст — проверьте замкнутость контура")
            checkpoint()
            val k = keyForDrawing(l)
            val m = Matrix().apply { setTranslate(res.x.toFloat(), res.y.toFloat()); postConcat(inverseLayerMatrix(l)) }
            val e = BitmapElement(res.bitmap, m)
            if (fillBehind) k.elements.add(0, e) else k.elements.add(e)
            pushRecentColor(c)
            changed()
        }
    }

    // ---------------- выделение ----------------
    private fun selectionKey(): Keyframe? {
        val s = selection ?: return null
        val k = project.layer(s.layerId)?.keyframes?.get(s.key) ?: return null
        if (s.indices.any { it !in k.elements.indices }) { selection = null; return null }
        return k
    }

    fun selectLasso(poly: FloatArray) {
        val l = activeLayer ?: return
        if (l.type != LayerType.DRAWING) return
        val e = l.keyframes.floorEntry(frame) ?: run { selection = null; return }
        val m = Renderer.layerMatrix(project, l, frame)
        val idx = e.value.elements.indices.filter { i ->
            when (val el = e.value.elements[i]) {
                is StrokeElement -> {
                    var inside = 0
                    val p = FloatArray(2)
                    for (j in 0 until el.count) { p[0] = el.x(j); p[1] = el.y(j); m.mapPoints(p); if (pointInPolygon(p[0], p[1], poly)) inside++ }
                    inside * 2 > el.count
                }
                is BitmapElement -> {
                    val r = el.bounds(); m.mapRect(r); pointInPolygon(r.centerX(), r.centerY(), poly)
                }
            }
        }
        selection = if (idx.isEmpty()) null else Selection(l.id, e.key, idx)
        if (idx.isNotEmpty()) toast("Выделено: ${idx.size}")
    }

    fun selectAll() {
        val l = activeLayer ?: return
        val e = l.keyframes.floorEntry(frame) ?: return
        if (e.value.elements.isEmpty()) return
        selection = Selection(l.id, e.key, e.value.elements.indices.toList())
    }

    fun selectionBounds(): RectF? {
        val s = selection ?: return null
        val k = selectionKey() ?: return null
        val l = project.layer(s.layerId) ?: return null
        val m = Renderer.layerMatrix(project, l, frame)
        val r = RectF(); var first = true
        for (i in s.indices) {
            val b = k.elements[i].bounds(); m.mapRect(b)
            if (first) { r.set(b); first = false } else r.union(b)
        }
        return if (first) null else r
    }

    private var dragKind = DragKind.MOVE
    private var dragOriginals: List<Element> = emptyList()
    private val dragStart = PointF(); private val dragCenter = PointF()

    /** Ручки рамки: углы — масштаб, кружок сверху — поворот, внутри — перемещение. */
    fun hitHandles(b: RectF, x: Float, y: Float, tol: Float, rotOffset: Float): DragKind? {
        val corners = listOf(b.left to b.top, b.right to b.top, b.right to b.bottom, b.left to b.bottom)
        if (corners.any { hypot(it.first - x, it.second - y) < tol }) return DragKind.SCALE
        if (hypot(b.centerX() - x, b.top - rotOffset - y) < tol) return DragKind.ROTATE
        val inflated = RectF(b); inflated.inset(-tol / 2, -tol / 2)
        if (inflated.contains(x, y)) return DragKind.MOVE
        return null
    }

    fun beginSelectionDrag(x: Float, y: Float, tol: Float, rotOffset: Float): Boolean {
        val b = selectionBounds() ?: return false
        val kind = hitHandles(b, x, y, tol, rotOffset) ?: return false
        val k = selectionKey() ?: return false
        checkpoint()
        dragKind = kind
        dragOriginals = selection!!.indices.map { k.elements[it] }
        dragStart.set(x, y); dragCenter.set(b.centerX(), b.centerY())
        return true
    }

    private fun dragMatrix(x: Float, y: Float): Matrix {
        val t = Matrix()
        when (dragKind) {
            DragKind.MOVE -> t.setTranslate(x - dragStart.x, y - dragStart.y)
            DragKind.SCALE -> {
                val f = hypot(x - dragCenter.x, y - dragCenter.y) / max(1f, hypot(dragStart.x - dragCenter.x, dragStart.y - dragCenter.y))
                t.setScale(f, f, dragCenter.x, dragCenter.y)
            }
            DragKind.ROTATE -> {
                val a = Math.toDegrees((atan2(y - dragCenter.y, x - dragCenter.x) - atan2(dragStart.y - dragCenter.y, dragStart.x - dragCenter.x)).toDouble()).toFloat()
                t.setRotate(a, dragCenter.x, dragCenter.y)
            }
        }
        return t
    }

    fun updateSelectionDrag(x: Float, y: Float) {
        val s = selection ?: return
        val k = selectionKey() ?: return
        val l = project.layer(s.layerId) ?: return
        applyDocTransform(l, k, s.indices, dragOriginals, dragMatrix(x, y))
    }

    private fun applyDocTransform(l: Layer, k: Keyframe, idx: List<Int>, originals: List<Element>, t: Matrix) {
        val lm = Renderer.layerMatrix(project, l, frame)
        val inv = Matrix(); lm.invert(inv)
        val m = Matrix(lm); m.postConcat(t); m.postConcat(inv)
        idx.forEachIndexed { i, ei -> k.elements[ei] = originals[i].transformed(m) }
        changed()
    }

    fun transformSelection(t: Matrix) {
        val s = selection ?: return
        val k = selectionKey() ?: return
        val l = project.layer(s.layerId) ?: return
        checkpoint()
        applyDocTransform(l, k, s.indices, s.indices.map { k.elements[it] }, t)
    }

    fun flipSelection(horizontal: Boolean) {
        val b = selectionBounds() ?: return
        transformSelection(Matrix().apply { if (horizontal) setScale(-1f, 1f, b.centerX(), b.centerY()) else setScale(1f, -1f, b.centerX(), b.centerY()) })
    }

    fun deleteSelection() {
        val s = selection ?: return
        val k = selectionKey() ?: return
        checkpoint()
        s.indices.sortedDescending().forEach { k.elements.removeAt(it) }
        selection = null; changed()
    }

    fun copySelection(cut: Boolean = false) {
        val s = selection ?: return
        val k = selectionKey() ?: return
        val l = project.layer(s.layerId) ?: return
        val m = Renderer.layerMatrix(project, l, frame)
        clipboard = s.indices.map { k.elements[it].transformed(m) }
        if (cut) deleteSelection() else toast("Скопировано: ${s.indices.size}")
    }

    fun paste(offset: Float = 0f) {
        val items = clipboard ?: run { toast("Буфер пуст"); return }
        val l = editableLayer() ?: return
        checkpoint()
        val k = keyForDrawing(l)
        val inv = inverseLayerMatrix(l)
        if (offset != 0f) inv.preTranslate(offset, offset)
        val start = k.elements.size
        items.forEach { k.elements.add(it.transformed(inv)) }
        selection = Selection(l.id, frame, (start until k.elements.size).toList())
        tool = Tool.LASSO
        changed()
    }

    fun duplicateSelection() { copySelection(); paste(24f) }

    /** Новое: перекрасить выделенное в текущий цвет (линии и заливки). */
    fun recolorSelection() {
        val s = selection ?: return
        val k = selectionKey() ?: return
        checkpoint()
        for (i in s.indices) {
            k.elements[i] = when (val e = k.elements[i]) {
                is StrokeElement -> if (e.kind == BrushKind.ERASER) e else e.withColor(color)
                is BitmapElement -> {
                    val w = e.bitmap.width; val h = e.bitmap.height
                    val px = IntArray(w * h); e.bitmap.getPixels(px, 0, w, 0, 0, w, h)
                    val rgb = color and 0x00FFFFFF
                    for (j in px.indices) px[j] = (px[j] and 0xFF000000.toInt()) or rgb
                    BitmapElement(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888), Matrix(e.matrix), e.alpha)
                }
            }
        }
        changed()
    }

    fun arrangeSelection(toFront: Boolean) {
        val s = selection ?: return
        val k = selectionKey() ?: return
        checkpoint()
        val picked = s.indices.sorted().map { k.elements[it] }
        s.indices.sortedDescending().forEach { k.elements.removeAt(it) }
        val start: Int
        if (toFront) { start = k.elements.size; k.elements.addAll(picked) } else { start = 0; k.elements.addAll(0, picked) }
        selection = Selection(s.layerId, s.key, (start until start + picked.size).toList())
        changed()
    }

    // ---------------- трансформация ключа (твин движения) ----------------
    private var ktStart = Transform()
    private var ktKey: Keyframe? = null
    private val ktPivotDoc = PointF()
    private var ktBoneInv: Matrix? = null

    fun keyContentBounds(): RectF? {
        val l = activeLayer ?: return null
        if (l.type != LayerType.DRAWING) return null
        val k = l.keyframes.floorEntry(frame)?.value ?: return null
        if (k.elements.isEmpty()) return null
        val r = RectF(k.elements[0].bounds())
        k.elements.drop(1).forEach { r.union(it.bounds()) }
        Renderer.layerMatrix(project, l, frame).mapRect(r)
        return r
    }

    fun keyPivotDoc(): PointF? {
        val l = activeLayer ?: return null
        val tr = Renderer.keyTransformAt(l, frame) ?: return null
        val p = floatArrayOf(tr.px + tr.tx, tr.py + tr.ty)
        Bones.bindMatrix(project, l, frame)?.mapPoints(p)
        return PointF(p[0], p[1])
    }

    /** Ключ в текущем кадре для трансформации; при отсутствии — создаётся копией (как F6). */
    private fun keyForTransform(l: Layer): Keyframe? {
        l.keyframes[frame]?.let { return it }
        val held = l.keyframes.floorEntry(frame) ?: return null
        val k = held.value.copy()
        k.transform = Renderer.keyTransformAt(l, frame) ?: held.value.transform
        k.tween = TweenType.NONE
        l.keyframes[frame] = k
        return k
    }

    private fun ensurePivot(l: Layer, k: Keyframe) {
        val t = k.transform
        if (t.px == 0f && t.py == 0f && t.rot == 0f && t.sx == 1f && t.sy == 1f && k.elements.isNotEmpty()) {
            val r = RectF(k.elements[0].bounds()); k.elements.drop(1).forEach { r.union(it.bounds()) }
            k.transform = t.copy(px = r.centerX(), py = r.centerY())
            // для всех ключей слоя без своего центра — тот же центр, чтобы твин был согласован
            l.keyframes.values.forEach { o ->
                val ot = o.transform
                if (o !== k && ot.px == 0f && ot.py == 0f && ot.rot == 0f && ot.sx == 1f && ot.sy == 1f) o.transform = ot.copy(px = r.centerX(), py = r.centerY())
            }
        }
    }

    fun beginKeyTransform(x: Float, y: Float, tol: Float, rotOffset: Float): Boolean {
        val l = editableLayer() ?: return false
        val bounds = keyContentBounds() ?: run { toast("Ключ пуст"); return false }
        val kind = hitHandles(bounds, x, y, tol, rotOffset) ?: DragKind.MOVE
        checkpoint()
        val k = keyForTransform(l) ?: return false
        ensurePivot(l, k)
        ktKey = k; ktStart = k.transform; dragKind = kind
        dragStart.set(x, y)
        keyPivotDoc()?.let { ktPivotDoc.set(it.x, it.y) }
        ktBoneInv = Bones.bindMatrix(project, l, frame)?.let { b -> Matrix().also { b.invert(it) } }
        return true
    }

    fun updateKeyTransform(x: Float, y: Float) {
        val k = ktKey ?: return
        val st = ktStart
        k.transform = when (dragKind) {
            DragKind.MOVE -> {
                val p = floatArrayOf(dragStart.x, dragStart.y, x, y)
                ktBoneInv?.mapPoints(p)
                st.copy(tx = st.tx + p[2] - p[0], ty = st.ty + p[3] - p[1])
            }
            DragKind.ROTATE -> {
                val a = Math.toDegrees((atan2(y - ktPivotDoc.y, x - ktPivotDoc.x) - atan2(dragStart.y - ktPivotDoc.y, dragStart.x - ktPivotDoc.x)).toDouble()).toFloat()
                st.copy(rot = st.rot + normalizeDeg(a))
            }
            DragKind.SCALE -> {
                val f = hypot(x - ktPivotDoc.x, y - ktPivotDoc.y) / max(1f, hypot(dragStart.x - ktPivotDoc.x, dragStart.y - ktPivotDoc.y))
                st.copy(sx = st.sx * f, sy = st.sy * f)
            }
        }
        changed()
    }

    fun endKeyTransform() { ktKey = null }

    fun editKeyTransform(tag: String, f: (Transform) -> Transform) {
        val l = editableLayer() ?: return
        checkpointOnce(tag)
        val k = keyForTransform(l) ?: return
        ensurePivot(l, k)
        k.transform = f(k.transform)
        changed()
    }

    fun currentTransform(): Transform? = activeLayer?.let { Renderer.keyTransformAt(it, frame) }

    // ---------------- кости ----------------
    fun activeBoneLayer(): Layer? = activeLayer?.takeIf { it.type == LayerType.BONES }

    fun posedBones(l: Layer) = Bones.solve(l.bones, Bones.poseAt(l, frame))

    fun addBone(x0: Float, y0: Float, x1: Float, y1: Float, snapTol: Float) {
        val l = activeBoneLayer() ?: run { toast("Выберите или создайте слой костей"); return }
        if (l.locked) { toast("Слой заблокирован"); return }
        val parent = l.bones.minByOrNull { hypot(it.tailX - x0, it.tailY - y0) }
            ?.takeIf { hypot(it.tailX - x0, it.tailY - y0) < snapTol }
            ?: selectedBoneId?.let { id -> l.bones.firstOrNull { it.id == id } }?.takeIf { hypot(it.tailX - x0, it.tailY - y0) < snapTol * 2 }
        val hx = parent?.tailX ?: x0; val hy = parent?.tailY ?: y0
        val len = hypot(x1 - hx, y1 - hy)
        if (len < 6f) return
        checkpoint()
        val b = Bone(name = "Кость ${l.bones.size + 1}", parentId = parent?.id, hx = hx, hy = hy, length = len,
            angle = Math.toDegrees(atan2((y1 - hy).toDouble(), (x1 - hx).toDouble())).toFloat())
        l.bones.add(b)
        selectedBoneId = b.id
        changed()
    }

    fun deleteSelectedBone() {
        val l = activeBoneLayer() ?: return
        val id = selectedBoneId ?: return
        checkpoint()
        val remove = HashSet<String>(); remove.add(id)
        var grew = true
        while (grew) { grew = false; l.bones.forEach { if (it.parentId in remove && remove.add(it.id)) grew = true } }
        l.bones.removeAll { it.id in remove }
        l.poses.values.forEach { p -> remove.forEach { p.values.remove(it) } }
        project.layers.forEach { if (it.bindLayer == l.id && it.bindBone in remove) { it.bindLayer = null; it.bindBone = null } }
        selectedBoneId = null
        changed()
    }

    fun renameBone(id: String, name: String) {
        val l = activeBoneLayer() ?: return
        val i = l.bones.indexOfFirst { it.id == id }; if (i < 0) return
        checkpoint(); l.bones[i] = l.bones[i].copy(name = name); changed()
    }

    fun bindLayer(layer: Layer, boneLayer: Layer?, boneId: String?) {
        checkpoint()
        if (boneLayer == null || boneId == null) { layer.bindLayer = null; layer.bindBone = null } else {
            // компенсируем текущую позу, чтобы рисунок не «прыгнул»
            layer.bindLayer = boneLayer.id; layer.bindBone = boneId
        }
        changed()
    }

    fun insertPoseKey() {
        val l = activeBoneLayer() ?: return
        checkpoint()
        l.poses[frame] = Pose(HashMap((Bones.poseAt(l, frame) ?: emptyMap()).mapValues { it.value.copyOf() }))
        changed(); toast("Ключ позы добавлен")
    }

    private var poseBoneId: String? = null
    private var poseTranslate = false
    private var poseStartOff = FloatArray(3)

    fun hitBone(l: Layer, x: Float, y: Float, tol: Float): Bone? {
        val w = posedBones(l)
        return l.bones.minByOrNull { b -> w[b.id]?.let { distToSegment(x, y, it.hx, it.hy, it.tailX, it.tailY) } ?: Float.MAX_VALUE }
            ?.takeIf { b -> w[b.id]?.let { distToSegment(x, y, it.hx, it.hy, it.tailX, it.tailY) < tol } == true }
    }

    fun beginPose(x: Float, y: Float, tol: Float): Boolean {
        val l = activeBoneLayer() ?: run { toast("Выберите слой костей"); return false }
        val b = hitBone(l, x, y, tol) ?: run { selectedBoneId = null; return false }
        selectedBoneId = b.id
        checkpoint()
        val pose = l.poses[frame] ?: Pose(HashMap((Bones.poseAt(l, frame) ?: emptyMap()).mapValues { it.value.copyOf() }),
            l.poses.floorEntry(frame)?.value?.easing ?: Easing.EASE_IN_OUT).also { l.poses[frame] = it }
        val w = posedBones(l)[b.id]!!
        poseBoneId = b.id
        poseTranslate = b.parentId == null && hypot(x - w.hx, y - w.hy) < tol * 0.9f
        poseStartOff = (pose.values[b.id] ?: FloatArray(3)).copyOf()
        dragStart.set(x, y)
        return true
    }

    fun dragPose(x: Float, y: Float) {
        val l = activeBoneLayer() ?: return
        val id = poseBoneId ?: return
        val pose = l.poses[frame] ?: return
        val bone = l.bones.firstOrNull { it.id == id } ?: return
        val arr = pose.values.getOrPut(id) { FloatArray(3) }
        when {
            poseTranslate -> { arr[1] = poseStartOff[1] + x - dragStart.x; arr[2] = poseStartOff[2] + y - dragStart.y }
            ikMode && bone.parentId != null -> Bones.ik(l.bones, pose.values, id, x, y)
            else -> {
                val w = Bones.solve(l.bones, pose.values)[id] ?: return
                val target = Math.toDegrees(atan2((y - w.hy).toDouble(), (x - w.hx).toDouble())).toFloat()
                arr[0] += normalizeDeg(target - w.angle)
            }
        }
        changed()
    }

    fun endPose() { poseBoneId = null }

    fun resetPose() {
        val l = activeBoneLayer() ?: return
        checkpoint(); l.poses[frame] = Pose(); changed()
    }

    // ---------------- слои ----------------
    private val layerColors = listOf(0xFF4FC3F7, 0xFFFF8A65, 0xFFAED581, 0xFFBA68C8, 0xFFFFD54F, 0xFFF06292, 0xFF4DB6AC).map { it.toInt() }

    fun addLayer(type: LayerType) {
        checkpoint()
        val idx = project.layers.indexOfFirst { it.id == activeLayerId }.let { if (it < 0) project.layers.size else it + 1 }
        val n = project.layers.count { it.type == type } + 1
        val l = Layer(name = if (type == LayerType.BONES) "Скелет $n" else "Слой $n", type = type, color = layerColors[project.layers.size % layerColors.size])
        if (type == LayerType.DRAWING) l.keyframes[0] = Keyframe()
        project.layers.add(idx, l)
        activeLayerId = l.id
        if (type == LayerType.BONES) { tool = Tool.BONE; toast("Проведите линию — это кость. Начните от конца кости — получится дочерняя") }
        changed()
    }

    fun selectLayer(id: String) {
        if (activeLayerId != id) { activeLayerId = id; selection = null }
        val l = project.layer(id) ?: return
        if (l.type == LayerType.BONES && tool !in listOf(Tool.BONE, Tool.POSE, Tool.HAND)) tool = Tool.POSE
        if (l.type == LayerType.DRAWING && (tool == Tool.BONE || tool == Tool.POSE)) tool = Tool.BRUSH
    }

    fun deleteLayer(id: String) {
        if (project.layers.size <= 1) { toast("Нельзя удалить последний слой"); return }
        checkpoint()
        val i = project.layers.indexOfFirst { it.id == id }
        project.layers.removeAt(i)
        project.layers.forEach { if (it.bindLayer == id) { it.bindLayer = null; it.bindBone = null } }
        activeLayerId = project.layers[max(0, i - 1)].id
        selection = null; changed()
    }

    fun duplicateLayer(id: String) {
        val l = project.layer(id) ?: return
        checkpoint()
        val c = l.copy(freshId = true); c.name = l.name + " копия"
        project.layers.add(project.layers.indexOf(l) + 1, c)
        activeLayerId = c.id; changed()
    }

    fun moveLayer(id: String, up: Boolean) {
        val i = project.layers.indexOfFirst { it.id == id }
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j !in project.layers.indices) return
        checkpoint()
        val l = project.layers.removeAt(i); project.layers.add(j, l)
        changed()
    }

    /** Слить слой с нижним: ключи объединяются, трансформации «запекаются». */
    fun mergeDown(id: String) {
        val i = project.layers.indexOfFirst { it.id == id }
        if (i <= 0) return
        val top = project.layers[i]; val bottom = project.layers[i - 1]
        if (top.type != LayerType.DRAWING || bottom.type != LayerType.DRAWING) { toast("Сливаются только слои рисования"); return }
        checkpoint()
        val frames = (top.keyframes.keys + bottom.keyframes.keys).toSortedSet()
        val merged = TreeMap<Int, Keyframe>()
        for (f in frames) {
            val els = ArrayList<Element>()
            for (src in listOf(bottom, top)) {
                val r = Renderer.resolve(project, src, f) ?: continue
                val m = Matrix(r.matrix)
                Bones.bindMatrix(project, bottom, f)?.let { b -> val bi = Matrix(); b.invert(bi); m.postConcat(bi) }
                r.elements.forEach { els.add(it.transformed(m)) }
            }
            merged[f] = Keyframe(els)
        }
        bottom.keyframes.clear(); bottom.keyframes.putAll(merged)
        bottom.bindLayer = null; bottom.bindBone = null
        project.layers.removeAt(i)
        activeLayerId = bottom.id
        changed(); toast("Слои объединены")
    }

    fun updateLayer(id: String, tag: String? = null, f: (Layer) -> Unit) {
        val l = project.layer(id) ?: return
        if (tag != null) checkpointOnce(tag + id) else checkpoint()
        f(l); changed()
    }

    fun importImage(uri: Uri) {
        busy = "Импорт…"
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    var sample = 1
                    while (opts.outWidth / sample > project.width * 2 || opts.outHeight / sample > project.height * 2) sample *= 2
                    appContext.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    }
                }.getOrNull()
            }
            busy = null
            if (bmp == null) { toast("Не удалось открыть изображение"); return@launch }
            checkpoint()
            val s = min(project.width * 0.9f / bmp.width, project.height * 0.9f / bmp.height).coerceAtMost(1f)
            val m = Matrix().apply { setScale(s, s); postTranslate((project.width - bmp.width * s) / 2, (project.height - bmp.height * s) / 2) }
            val l = Layer(name = "Референс", opacity = 0.6f, guide = true, locked = false, color = 0xFF90A4AE.toInt())
            l.keyframes[0] = Keyframe(mutableListOf(BitmapElement(bmp, m)))
            val idx = project.layers.indexOfFirst { it.id == activeLayerId }.let { if (it < 0) 0 else it }
            project.layers.add(idx, l)
            activeLayerId = l.id
            changed(); toast("Изображение добавлено как направляющий слой (не экспортируется)")
        }
    }

    // ---------------- сохранение / экспорт ----------------
    fun saveAsync(after: (() -> Unit)? = null) {
        project.modified = System.currentTimeMillis()
        val copy = project.detachedCopy()
        val f = frame
        dirty = false
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val s = 360f / max(copy.width, copy.height)
                    val thumb = Renderer().renderBitmap(copy, f, s, FrameOptions(includeGuides = false), forceOpaque = true)
                    store.save(copy, thumb)
                }
            }
            after?.invoke()
        }
    }

    fun export(settings: ExportSettings, onDone: (Uri?, String?) -> Unit) {
        if (exportProgress != null) return
        stop()
        val copy = project.detachedCopy()
        exportProgress = 0f
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val json = if (settings.format == com.animora.studio.export.ExportFormat.PROJECT) Serializer.toJson(copy).toString() else null
                    Exporter.export(appContext, copy, settings, json) { p -> scope.launch(Dispatchers.Main) { exportProgress = p } }
                }
            }
            exportProgress = null
            result.onSuccess { onDone(it, null) }.onFailure { onDone(null, it.message ?: it.javaClass.simpleName) }
        }
    }

    fun projectSettingsChanged() { checkpoint(); changed() }
}
