package com.animora.studio.ui.editor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.animora.studio.model.CurvePoint
import com.animora.studio.model.CurveProp
import com.animora.studio.model.EaseCurve
import com.animora.studio.model.Easing
import com.animora.studio.model.TweenType
import com.animora.studio.ui.Pal
import com.animora.studio.ui.SectionTitle
import com.animora.studio.ui.TopButton
import com.animora.studio.ui.UiPrefs
import com.animora.studio.ui.UiPreset
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

// ============================================================== общий каркас окна

@Composable
private fun BigDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(18.dp), color = Pal.panel,
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.94f)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconBtn(Icons.Filled.Close, "Закрыть", onClick = onDismiss)
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}

@Composable
private fun PrefSwitch(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 14.sp)
        Switch(value, onChange)
    }
}

@Composable
private fun PrefSlider(text: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(text, fontSize = 13.sp, color = Pal.dim, modifier = Modifier.padding(top = 6.dp))
    Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range)
}

@Composable
private fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.3f), CircleShape)
            .clickable(onClick = onClick)
    )
}

// ============================================================== настройка интерфейса

private val ACCENTS = listOf(0xFF8B7CFF, 0xFFFF6B9A, 0xFF3DDC97, 0xFF4FC3F7, 0xFFFFC857, 0xFFFF8A65, 0xFFB388FF, 0xFFE0E0E0).map { it.toInt() }
private val WORKSPACES = listOf(0xFF24222E, 0xFF121212, 0xFF2B2B2B, 0xFF3A3A3A, 0xFF1B2433, 0xFF6E6E6E, 0xFFBDBDBD).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UiSettingsDialog(onDismiss: () -> Unit) {
    val u = UiPrefs
    fun set(block: () -> Unit) { block(); u.save() }
    BigDialog("Настройка интерфейса", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Изменения применяются сразу и сохраняются на устройстве.", fontSize = 12.sp, color = Pal.dim)

            SectionTitle("Готовые раскладки")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                UiPreset.entries.forEach { p -> SmallBtn(p.title) { u.apply(p) } }
            }

            SectionTitle("Расположение панелей")
            PrefSwitch("Инструменты справа", u.toolsRight) { v -> set { u.toolsRight = v } }
            PrefSwitch("Слои слева", u.layersLeft) { v -> set { u.layersLeft = v } }
            PrefSwitch("Таймлайн сверху", u.timelineTop) { v -> set { u.timelineTop = v } }
            PrefSwitch("Ползунки кисти справа", u.slidersRight) { v -> set { u.slidersRight = v } }
            PrefSwitch("Панель параметров инструмента снизу", u.contextBarBottom) { v -> set { u.contextBarBottom = v } }
            PrefSwitch("Показывать ползунки размера и прозрачности", u.showSliders) { v -> set { u.showSliders = v } }
            PrefSwitch("Подписи под инструментами", u.showToolLabels) { v -> set { u.showToolLabels = v } }
            PrefSwitch("Открывать панель слоёв при запуске", u.layersOpenByDefault) { v -> set { u.layersOpenByDefault = v } }

            SectionTitle("Размеры")
            PrefSlider("Кнопки инструментов: ${u.toolSize} dp", u.toolSize.toFloat(), 34f..60f) { v -> set { u.toolSize = v.roundToInt() } }
            PrefSlider("Ширина панели слоёв: ${u.layersWidth} dp", u.layersWidth.toFloat(), 180f..400f) { v -> set { u.layersWidth = v.roundToInt() } }
            PrefSlider("Видимых строк таймлайна: ${u.timelineRows}", u.timelineRows.toFloat(), 1f..10f) { v -> set { u.timelineRows = v.roundToInt() } }
            PrefSlider("Ширина кадра на таймлайне: ${u.timelineCell} dp", u.timelineCell.toFloat(), 14f..40f) { v -> set { u.timelineCell = v.roundToInt() } }
            PrefSlider("Непрозрачность плавающих панелей: ${(u.panelAlpha * 100).roundToInt()}%", u.panelAlpha, 0.4f..1f) { v -> set { u.panelAlpha = v } }

            SectionTitle("Инструменты: видимость и порядок")
            val all = u.tools + Tool.entries.filter { it !in u.tools }
            all.forEach { t ->
                val on = t in u.tools
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { u.toggleTool(t) })
                    Icon(toolIcon(t), null, tint = if (on) Pal.text else Pal.dim, modifier = Modifier.size(22.dp))
                    Text("  " + t.title, Modifier.weight(1f), fontSize = 14.sp, color = if (on) Pal.text else Pal.dim)
                    if (on) {
                        IconBtn(Icons.Filled.ArrowUpward, "Выше", size = 36) { u.moveTool(t, -1) }
                        IconBtn(Icons.Filled.ArrowDownward, "Ниже", size = 36) { u.moveTool(t, 1) }
                    }
                }
            }

            SectionTitle("Кнопки верхней панели")
            TopButton.entries.forEach { b ->
                PrefSwitch(b.title, u.visible(b)) { v -> set { u.hiddenTop = if (v) u.hiddenTop - b else u.hiddenTop + b } }
            }
            Text("Скрытые функции остаются доступны в меню «⋮».", fontSize = 11.sp, color = Pal.dim)

            SectionTitle("Цвет акцента")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ACCENTS.forEach { c -> Swatch(c, u.accent == c) { set { u.accent = c } } } }
            SectionTitle("Фон рабочей области вокруг холста")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { WORKSPACES.forEach { c -> Swatch(c, u.workspace == c) { set { u.workspace = c } } } }

            Row(Modifier.padding(top = 16.dp)) {
                TextButton(onClick = { u.reset() }) { Text("Сбросить всё", color = Pal.pink) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Готово") }
            }
        }
    }
}

// ============================================================== редактор кривых

private val EXTRA_PRESETS: List<Pair<String, EaseCurve>> = listOf(
    "Мягко (ease)" to EaseCurve.bezier(0.25f, 0.1f, 0.25f, 1f),
    "Резкий старт" to EaseCurve.bezier(0.16f, 1f, 0.3f, 1f),
    "Резкий финиш" to EaseCurve.bezier(0.7f, 0f, 0.84f, 0f),
    "Перелёт и назад" to EaseCurve.bezier(0.34f, 1.56f, 0.64f, 1f),
    "Замах (антиципация)" to EaseCurve(
        listOf(
            CurvePoint(0f, 0f, 0f, 0f, 0.12f, 0f),
            CurvePoint(0.3f, -0.15f, 0.2f, -0.15f, 0.42f, -0.15f),
            CurvePoint(1f, 1f, 0.7f, 1.05f, 1f, 1f)
        )
    ),
    "Медленно в середине" to EaseCurve.bezier(0.1f, 0.8f, 0.9f, 0.2f)
)

private fun mirrored(c: EaseCurve) = EaseCurve(
    c.points.reversed().map { p -> CurvePoint(1 - p.x, 1 - p.y, 1 - p.outX, 1 - p.outY, 1 - p.inX, 1 - p.inY) }
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CurveEditorDialog(ctl: EditorController, onDismiss: () -> Unit) {
    ctl.version
    val target = ctl.curveTarget()
    var prop by remember { mutableStateOf<CurveProp?>(null) }
    var smooth by remember { mutableStateOf(true) }
    BigDialog("Кривые анимации", onDismiss) {
        if (target == null) {
            Text("Выберите на таймлайне кадр слоя, где есть ключевой кадр (или ключ позы для костей).", color = Pal.dim)
            return@BigDialog
        }
        val key = target.key
        val separate = key?.separateCurves == true
        val editProp = if (separate) (prop ?: CurveProp.POSITION) else null
        val curve = ctl.currentCurve(editProp) ?: EaseCurve.linear()
        val frames = (target.end ?: (target.start + 12)) - target.start
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val what = if (target.pose != null) "ключ позы" else "ключ"
            Text(
                "Слой «${target.layer.name}», $what в кадре ${target.start + 1}" +
                    (target.end?.let { " → ${it + 1} ($frames кадр.)" } ?: ""),
                fontSize = 13.sp, color = Pal.dim
            )
            if (target.end == null) Text(
                "Дальше нет ключа — твину не к чему двигаться. Добавьте ключевой кадр правее на таймлайне.",
                fontSize = 12.sp, color = Pal.pink, modifier = Modifier.padding(top = 4.dp)
            )
            if (key != null && key.tween == TweenType.NONE && target.end != null) Text(
                "Твин для этого ключа не включён — при изменении кривой включится твин движения.",
                fontSize = 12.sp, color = Pal.accent2, modifier = Modifier.padding(top = 4.dp)
            )
            if (key != null) {
                PrefSwitch("Отдельные кривые для свойств", separate) { ctl.setSeparateCurves(it) }
                if (separate) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CurveProp.entries.filter { it != CurveProp.SHAPE || key.tween == TweenType.SHAPE }.forEach { p ->
                        SmallBtn(p.title, selected = editProp == p) { prop = p }
                    }
                }
            }

            CurveGraph(
                curve, frames.coerceAtLeast(1), smooth,
                onChange = { ctl.setCurve(editProp, it) },
                modifier = Modifier.fillMaxWidth().height(300.dp).padding(top = 8.dp)
            )
            Text(
                "Тяните точки и рычаги · двойное касание — новая точка · долгое касание по точке — удалить. " +
                    "Жёлтые точки — положение в каждом кадре: чем они реже, тем быстрее движение.",
                fontSize = 11.sp, color = Pal.dim, modifier = Modifier.padding(top = 4.dp)
            )
            CurvePreview(curve, frames.coerceAtLeast(1), ctl.project.fps)

            Row(verticalAlignment = Alignment.CenterVertically) {
                PrefSwitchInline("Сглаженные точки", smooth) { smooth = it }
            }
            SectionTitle("Пресеты")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Easing.entries.forEach { e -> SmallBtn(e.title) { ctl.setCurve(editProp, EaseCurve.fromEasing(e), "preset${System.nanoTime()}") } }
                EXTRA_PRESETS.forEach { (n, c) -> SmallBtn(n) { ctl.setCurve(editProp, c, "preset${System.nanoTime()}") } }
            }
            SectionTitle("Действия")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallBtn("Зеркально") { ctl.setCurve(editProp, mirrored(curve), "mirror${System.nanoTime()}") }
                SmallBtn("Копировать") { ctl.copyCurve(editProp) }
                SmallBtn("Вставить") { ctl.pasteCurve(editProp) }
                SmallBtn("Ко всем ключам слоя") { ctl.applyCurveToLayer() }
                SmallBtn("Сбросить к пресету") { ctl.resetCurve() }
            }
        }
    }
}

@Composable
private fun PrefSwitchInline(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clickable { onChange(!value) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(value, onChange); Text("  $text", fontSize = 13.sp)
    }
}

private const val Y_MIN = -0.5f
private const val Y_MAX = 1.5f

/** Перетаскиваемая часть: точка или её рычаг. */
private data class Grab(val index: Int, val part: Int) // 0 — точка, 1 — входящий рычаг, 2 — исходящий

@Composable
fun CurveGraph(curve: EaseCurve, frames: Int, smooth: Boolean, onChange: (EaseCurve) -> Unit, modifier: Modifier) {
    val cur by rememberUpdatedState(curve)
    val cb by rememberUpdatedState(onChange)
    val sm by rememberUpdatedState(smooth)
    var selected by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val pad = with(density) { 22.dp.toPx() }
    val hitR = with(density) { 26.dp.toPx() }

    fun toScreen(sz: IntSize, x: Float, y: Float): Offset {
        val w = sz.width - 2 * pad; val h = sz.height - 2 * pad
        return Offset(pad + x * w, pad + (Y_MAX - y) / (Y_MAX - Y_MIN) * h)
    }
    fun toCurve(sz: IntSize, o: Offset): Pair<Float, Float> {
        val w = sz.width - 2 * pad; val h = sz.height - 2 * pad
        return ((o.x - pad) / w) to (Y_MAX - (o.y - pad) / h * (Y_MAX - Y_MIN))
    }
    fun hit(sz: IntSize, o: Offset, handles: Boolean): Grab? {
        var best: Grab? = null; var bd = hitR
        cur.points.forEachIndexed { i, p ->
            val cand = mutableListOf(Grab(i, 0) to toScreen(sz, p.x, p.y))
            if (handles && (i == selected || cur.points.size <= 3)) {
                if (i > 0) cand.add(Grab(i, 1) to toScreen(sz, p.inX, p.inY))
                if (i < cur.points.size - 1) cand.add(Grab(i, 2) to toScreen(sz, p.outX, p.outY))
            }
            for ((g, s) in cand) {
                val d = hypot(s.x - o.x, s.y - o.y)
                if (d < bd || (d == bd && g.part != 0)) { bd = d; best = g }
            }
        }
        return best
    }

    Canvas(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF15131F))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o -> selected = hit(size, o, false)?.index ?: -1 },
                    onDoubleTap = { o ->
                        val (x, _) = toCurve(size, o)
                        if (x in 0.02f..0.98f) { val (c, i) = cur.insertAt(x); selected = i; cb(c) }
                    },
                    onLongPress = { o ->
                        val g = hit(size, o, false)
                        if (g != null && g.index > 0 && g.index < cur.points.size - 1) { selected = -1; cb(cur.removeAt(g.index)) }
                    }
                )
            }
            .pointerInput(Unit) {
                var grab: Grab? = null
                detectDragGestures(
                    onDragStart = { o -> grab = hit(size, o, true); grab?.let { selected = it.index } },
                    onDragEnd = { grab = null }, onDragCancel = { grab = null }
                ) { ch, _ ->
                    val g = grab ?: return@detectDragGestures
                    ch.consume()
                    val (x, y) = toCurve(size, ch.position)
                    cb(moveGrab(cur, g, x, y, sm))
                }
            }
    ) {
        val sz = IntSize(size.width.toInt(), size.height.toInt())
        val w = size.width - 2 * pad; val h = size.height - 2 * pad
        // фон: зона перелёта, сетка
        val y0 = toScreen(sz, 0f, 0f).y; val y1 = toScreen(sz, 0f, 1f).y
        drawRect(Color.White.copy(alpha = 0.03f), Offset(pad, y1), Size(w, y0 - y1))
        for (i in 0..10) {
            val x = pad + w * i / 10
            drawLine(Color.White.copy(alpha = if (i % 5 == 0) 0.14f else 0.05f), Offset(x, pad), Offset(x, pad + h), 1f)
        }
        for (k in -2..6) {
            val y = toScreen(sz, 0f, k * 0.25f).y
            drawLine(Color.White.copy(alpha = if (k == 0 || k == 4) 0.3f else 0.05f), Offset(pad, y), Offset(pad + w, y), if (k == 0 || k == 4) 1.5f else 1f)
        }
        // линейная опора
        drawLine(Color.White.copy(alpha = 0.15f), toScreen(sz, 0f, 0f), toScreen(sz, 1f, 1f), 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        // кривая
        val path = Path()
        val first = toScreen(sz, cur.points[0].x, cur.points[0].y)
        path.moveTo(first.x, first.y)
        for (i in 0 until cur.points.size - 1) for (s in 1..40) {
            val (px, py) = cur.segmentPoint(i, s / 40f)
            val o = toScreen(sz, px, py); path.lineTo(o.x, o.y)
        }
        drawPath(path, Pal.accent, style = Stroke(3.5f))
        // точки кадров (тайминг)
        for (f in 0..frames) {
            val x = f.toFloat() / frames
            drawCircle(Color(0xFFFFC857), 3.5f, toScreen(sz, x, cur.eval(x)))
        }
        // рычаги и точки
        cur.points.forEachIndexed { i, p ->
            val a = toScreen(sz, p.x, p.y)
            val showHandles = i == selected || cur.points.size <= 3
            if (showHandles) {
                if (i > 0) { val hp = toScreen(sz, p.inX, p.inY); drawLine(Color.White.copy(alpha = 0.6f), a, hp, 1.5f); drawCircle(Color.White, 7f, hp) }
                if (i < cur.points.size - 1) { val hp = toScreen(sz, p.outX, p.outY); drawLine(Color.White.copy(alpha = 0.6f), a, hp, 1.5f); drawCircle(Color.White, 7f, hp) }
            }
            val end = i == 0 || i == cur.points.size - 1
            drawCircle(if (i == selected) Pal.pink else if (end) Color(0xFF9C97B5) else Pal.accent, if (i == selected) 11f else 9f, a)
            drawCircle(Color.White, if (i == selected) 11f else 9f, a, style = Stroke(2f))
        }
    }
}

/** Перемещение точки или рычага с ограничениями (монотонность по времени). */
private fun moveGrab(c: EaseCurve, g: Grab, x: Float, y: Float, smooth: Boolean): EaseCurve {
    val pts = c.points
    val p = pts[g.index]
    val prevX = if (g.index > 0) pts[g.index - 1].x else 0f
    val nextX = if (g.index < pts.size - 1) pts[g.index + 1].x else 1f
    val yc = y.coerceIn(-1f, 2f)
    val isEnd = g.index == 0 || g.index == pts.size - 1
    return when (g.part) {
        0 -> {
            if (isEnd) return c // начало (0,0) и конец (1,1) фиксированы
            val nx = x.coerceIn(prevX + 0.01f, nextX - 0.01f)
            val dx = nx - p.x; val dy = yc - p.y
            c.with(g.index, CurvePoint(nx, yc,
                (p.inX + dx).coerceIn(prevX, nx), p.inY + dy,
                (p.outX + dx).coerceIn(nx, nextX), p.outY + dy))
        }
        1 -> {
            val hx = x.coerceIn(prevX, p.x)
            var np = p.copy(inX = hx, inY = yc)
            if (smooth && !isEnd) {
                val vx = p.x - hx; val vy = p.y - yc; val l = hypot(vx, vy)
                val ol = hypot(p.outX - p.x, p.outY - p.y)
                if (l > 1e-4f) np = np.copy(outX = (p.x + vx / l * ol).coerceIn(p.x, nextX), outY = p.y + vy / l * ol)
            }
            c.with(g.index, np)
        }
        else -> {
            val hx = x.coerceIn(p.x, nextX)
            var np = p.copy(outX = hx, outY = yc)
            if (smooth && !isEnd) {
                val vx = p.x - hx; val vy = p.y - yc; val l = hypot(vx, vy)
                val il = hypot(p.inX - p.x, p.inY - p.y)
                if (l > 1e-4f) np = np.copy(inX = (p.x + vx / l * il).coerceIn(prevX, p.x), inY = p.y + vy / l * il)
            }
            c.with(g.index, np)
        }
    }
}

/** Живой предпросмотр: шарик движется по кадрам с этой кривой, «призраки» — позиции всех кадров. */
@Composable
private fun CurvePreview(curve: EaseCurve, frames: Int, fps: Int) {
    val ms = max(400, (frames * 1000f / max(1, fps)).roundToInt())
    val anim = rememberInfiniteTransition(label = "curvePreview")
    val t by anim.animateFloat(
        0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "t"
    )
    Canvas(Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF15131F))) {
        val pad = 24f
        val w = size.width - 2 * pad
        // запас по краям, чтобы был виден перелёт (значения < 0 и > 1)
        fun xAt(v: Float) = pad + w * ((v.coerceIn(-0.25f, 1.25f) + 0.25f) / 1.5f)
        val cy = size.height / 2
        drawLine(Color.White.copy(alpha = 0.15f), Offset(xAt(0f), cy), Offset(xAt(1f), cy), 2f)
        for (f in 0..frames) drawCircle(Color(0xFFFFC857).copy(alpha = 0.35f), 4f, Offset(xAt(curve.eval(f.toFloat() / frames)), cy))
        val tf = floor(t * frames) / frames
        drawCircle(Pal.pink, 11f, Offset(xAt(curve.eval(tf)), cy))
        if (abs(curve.eval(1f) - 1f) > 0.01f) drawCircle(Color.Red, 3f, Offset(xAt(1f), cy))
    }
}
