package com.animora.studio.ui.editor

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animora.studio.engine.MotionPreset
import com.animora.studio.engine.Renderer
import com.animora.studio.engine.Viseme
import com.animora.studio.model.Easing
import com.animora.studio.model.LayerType
import com.animora.studio.model.TweenType
import com.animora.studio.ui.Pal
import com.animora.studio.ui.SectionTitle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ======================= готовые движения =======================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MotionPresetsDialog(ctl: EditorController, onDismiss: () -> Unit) {
    val fps = ctl.project.fps
    var sel by remember { mutableStateOf(MotionPreset.JUMP) }
    var dur by remember { mutableIntStateOf(fps) }
    var strength by remember { mutableFloatStateOf(1f) }
    var loops by remember { mutableIntStateOf(1) }
    val n = if (sel.loopable) loops else 1
    val keys = remember(sel, strength, n) { MotionPreset.build(sel, strength, n) }
    var t by remember { mutableFloatStateOf(0f) }
    val total = dur * n
    LaunchedEffect(sel, total) {
        val start = withFrameNanos { it }
        // проигрываем по кругу с короткой паузой в конце
        while (true) withFrameNanos { now -> t = (((now - start) / 1e9f) * fps / total) % 1.25f }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Готовые движения") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Движение добавляется на активный слой начиная с кадра ${ctl.frame + 1}: создаются ключи с твином, которые потом можно править. " +
                        "Удобнее всего — символ на отдельном слое.",
                    fontSize = 12.sp, color = Pal.dim
                )
                // живой предпросмотр
                Canvas(Modifier.fillMaxWidth().padding(top = 8.dp).height(120.dp).background(Pal.panel2, RoundedCornerShape(10.dp))) {
                    val pk = MotionPreset.eval(keys, min(t, 1f))
                    val u = 34.dp.toPx()
                    val floor = size.height * 0.78f
                    drawLine(Pal.line, Offset(0f, floor), Offset(size.width, floor), 2f)
                    val cx = size.width / 2 + pk.dx * u + pk.dxW * size.width
                    val cy = (if (sel.ground) floor else floor - u / 2) + pk.dy * u
                    translate(cx, cy) {
                        rotate(pk.rot, pivot = Offset.Zero) {
                            scale(pk.sx, pk.sy, pivot = Offset.Zero) {
                                val top = if (sel.ground) -u else -u / 2
                                drawRoundRect(Pal.accent.copy(alpha = pk.alpha.coerceIn(0f, 1f)), Offset(-u / 2, top), Size(u, u), CornerRadius(8f, 8f))
                                drawCircle(Color.White.copy(alpha = pk.alpha.coerceIn(0f, 1f)), u * 0.09f, Offset(-u * 0.15f, top + u * 0.35f))
                                drawCircle(Color.White.copy(alpha = pk.alpha.coerceIn(0f, 1f)), u * 0.09f, Offset(u * 0.15f, top + u * 0.35f))
                            }
                        }
                    }
                }
                MotionPreset.entries.groupBy { it.group }.forEach { (g, list) ->
                    SectionTitle(g)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        list.forEach { p -> SmallBtn(p.title, selected = sel == p) { sel = p } }
                    }
                }
                SectionTitle("Длительность: $dur кадр. (${"%.2f".format(dur.toFloat() / fps)} с)")
                Slider(dur.toFloat(), { dur = it.roundToInt() }, valueRange = 4f..(fps * 5f).coerceAtLeast(10f))
                SectionTitle("Сила: ${(strength * 100).roundToInt()}%")
                Slider(strength, { strength = it }, valueRange = 0.2f..2.5f)
                if (sel.loopable) {
                    SectionTitle("Повторов: $loops")
                    Slider(loops.toFloat(), { loops = it.roundToInt() }, valueRange = 1f..8f, steps = 6)
                }
            }
        },
        confirmButton = { TextButton(onClick = { ctl.applyMotionPreset(sel, dur, strength, n); onDismiss() }) { Text("Применить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

// ======================= экспозиционный лист =======================

private fun Viseme.short(): String = when (this) {
    Viseme.REST -> "·"; Viseme.MBP -> "М"; Viseme.AI -> "А"; Viseme.E -> "Э"; Viseme.O -> "О"
    Viseme.U -> "У"; Viseme.FV -> "Ф"; Viseme.L -> "Л"; Viseme.ETC -> "С"
}

/**
 * Экспозиционный лист (X-sheet) как в классической мультипликации: кадры идут сверху вниз,
 * слои — столбцами. Номер — рисунок, «│» — рисунок держится, «↓» — твин.
 */
@Composable
fun XSheetDialog(ctl: EditorController, onDismiss: () -> Unit) {
    ctl.version
    val p = ctl.project
    val layers = p.layers.filter { it.type == LayerType.DRAWING }
    val clip = p.layers.firstOrNull { it.type == LayerType.AUDIO && it.audio != null }?.audio
    LaunchedEffect(clip?.id, clip?.offsetMs) { clip?.let { ctl.requestVisemes(it) } }
    val vis = clip?.let { ctl.cachedVisemes(it) }
    val nums = layers.associate { it.id to ctl.drawingNumbers(it) }
    var labelEdit by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = max(0, ctl.frame - 6))
    val active = ctl.activeLayer?.takeIf { it.type == LayerType.DRAWING }
    val cellW = 54.dp; val frameW = 54.dp; val soundW = 46.dp; val labelW = 130.dp; val rowH = 26.dp

    BigDialog("Экспозиционный лист", onDismiss) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text("${active?.name ?: "слой не выбран"} · кадр ${ctl.frame + 1}  ", fontSize = 12.sp, color = Pal.dim)
                listOf(1, 2, 3, 4).forEach { k -> SmallBtn("По $k", enabled = active != null) { active?.let { ctl.setExposure(it.id, k, 0) } } }
                SmallBtn("Длиннее +1", enabled = active != null) { active?.let { ctl.changeHold(it.id, ctl.frame, 1) } }
                SmallBtn("Короче −1", enabled = active != null) { active?.let { ctl.changeHold(it.id, ctl.frame, -1) } }
                SmallBtn("+ Ключ") { ctl.insertKeyframe() }
                SmallBtn("+ Пустой") { ctl.insertBlankKeyframe() }
                SmallBtn("Метка…", enabled = active != null) { active?.let { labelEdit = it.id to ctl.frame } }
            }
            Text(
                "Нажмите клетку — перейти к кадру и слою. «По 2» — каждый рисунок держится 2 кадра (классика для 12 рис./с).",
                fontSize = 11.sp, color = Pal.dim, modifier = Modifier.padding(vertical = 4.dp)
            )
            Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState())) {
                val totalW = frameW + (if (clip != null) soundW else 0.dp) + cellW * layers.size + labelW
                Column(Modifier.width(totalW).fillMaxHeight()) {
                    // шапка
                    Row(Modifier.background(Pal.panel2)) {
                        HeadCell("Кадр", frameW)
                        if (clip != null) HeadCell("Звук", soundW)
                        layers.forEach { l -> HeadCell(l.name, cellW, l.id == ctl.activeLayerId) { ctl.activeLayerId = l.id } }
                        HeadCell("Действие / метка", labelW)
                    }
                    LazyColumn(state = list, modifier = Modifier.weight(1f)) {
                        items(p.frameCount) { f ->
                            val cur = f == ctl.frame
                            val secLine = f % p.fps == 0
                            Row(
                                Modifier.height(rowH).background(if (cur) Pal.accent.copy(alpha = 0.18f) else if ((f / p.fps) % 2 == 0) Pal.panel else Pal.bg)
                                    .then(if (secLine) Modifier.border(0.5.dp, Pal.line) else Modifier)
                            ) {
                                Text(
                                    "${f + 1}" + if (secLine && f > 0) "  ${f / p.fps}с" else "",
                                    Modifier.width(frameW).align(Alignment.CenterVertically).clickable { ctl.seek(f) },
                                    fontSize = 11.sp, color = if (secLine) Pal.text else Pal.dim, fontWeight = if (secLine) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center
                                )
                                if (clip != null) {
                                    val i = f - clip.startFrame
                                    val v = vis?.getOrNull(i)
                                    val prev = vis?.getOrNull(i - 1)
                                    Text(
                                        when { i < 0 || vis != null && i >= vis.size -> ""; v == null -> "…"; v == prev -> "│"; else -> v.short() },
                                        Modifier.width(soundW).align(Alignment.CenterVertically),
                                        fontSize = 12.sp, color = Pal.shape, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold
                                    )
                                }
                                layers.forEach { l ->
                                    val k = l.keyframes[f]
                                    val held = l.keyframes.floorEntry(f)
                                    val tweening = held != null && held.key < f && held.value.tween != TweenType.NONE && l.keyframes.higherKey(held.key) != null
                                    val txt = when {
                                        k != null -> nums[l.id]?.get(f)?.toString() ?: "×"
                                        held == null -> ""
                                        tweening -> "↓"
                                        held.value.elements.isEmpty() -> ""
                                        else -> "│"
                                    }
                                    val sel = cur && l.id == ctl.activeLayerId
                                    Box(
                                        Modifier.width(cellW).fillMaxHeight()
                                            .background(if (sel) Pal.accent.copy(alpha = 0.45f) else Color.Transparent)
                                            .border(0.5.dp, Pal.line.copy(alpha = 0.5f))
                                            .clickable { ctl.activeLayerId = l.id; ctl.seek(f) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            txt, fontSize = if (k != null) 13.sp else 12.sp,
                                            color = when { k != null -> Pal.text; tweening -> Pal.motion; else -> Pal.dim },
                                            fontWeight = if (k != null) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                                val label = layers.firstNotNullOfOrNull { l -> l.keyframes[f]?.label?.takeIf { it.isNotBlank() } } ?: ""
                                Text(
                                    label, Modifier.width(labelW).align(Alignment.CenterVertically).padding(start = 6.dp)
                                        .clickable { (active ?: layers.firstOrNull())?.let { labelEdit = it.id to f } },
                                    fontSize = 12.sp, color = Pal.accent2, maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    labelEdit?.let { (lid, f) ->
        val l = p.layer(lid)
        var text by remember(lid, f) { mutableStateOf(l?.keyframes?.get(f)?.label ?: "") }
        AlertDialog(
            onDismissRequest = { labelEdit = null },
            title = { Text("Метка кадра ${f + 1}") },
            text = {
                Column {
                    Text("Слой «${l?.name}». Например: «замах», «удар», «моргает». В этом кадре появится ключ.", fontSize = 12.sp, color = Pal.dim)
                    OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { ctl.setKeyLabel(lid, f, text); labelEdit = null }) { Text("Готово") } },
            dismissButton = { TextButton(onClick = { labelEdit = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun HeadCell(text: String, w: androidx.compose.ui.unit.Dp, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Text(
        text, Modifier.width(w).height(30.dp).background(if (selected) Pal.accent.copy(alpha = 0.35f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 3.dp, vertical = 7.dp),
        fontSize = 11.sp, maxLines = 1, color = Pal.text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
    )
}

// ======================= редактор графиков =======================

/**
 * Редактор графиков: значения свойств слоя по всему таймлайну (как Graph Editor в After Effects).
 * Точки — ключи: тяните вверх-вниз, чтобы менять значение, вбок — чтобы сдвинуть ключ во времени.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraphEditorDialog(ctl: EditorController, onCurves: () -> Unit, onDismiss: () -> Unit) {
    ctl.version
    val layer = ctl.graphLayer()
    val props = EditorController.GraphProp.entries
    var activeProp by remember { mutableStateOf(EditorController.GraphProp.X) }
    var shown by remember { mutableStateOf(setOf(EditorController.GraphProp.X, EditorController.GraphProp.Y, EditorController.GraphProp.ROT)) }
    val fc = ctl.project.frameCount
    val firstKey = layer?.keyframes?.firstKey() ?: 0
    val lastKey = layer?.keyframes?.lastKey() ?: (fc - 1)
    var viewStart by remember(layer?.id) { mutableIntStateOf((firstKey - 2).coerceIn(0, max(0, fc - 1))) }
    var viewLen by remember(layer?.id) { mutableIntStateOf((lastKey - firstKey + 6).coerceIn(min(12, fc), fc)) }
    var selKey by remember(layer?.id) { mutableStateOf<Int?>(null) }

    BigDialog("Редактор графиков", onDismiss) {
        if (layer == null) {
            Text("Выберите слой рисования или камеру — здесь видны его графики движения.", color = Pal.dim)
            return@BigDialog
        }
        Column(Modifier.fillMaxSize()) {
            Text("Слой «${layer.name}». Точки — ключи: вверх-вниз — значение, вбок — время. Касание графика — переход к кадру.", fontSize = 11.sp, color = Pal.dim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                props.forEach { pr ->
                    val on = pr in shown
                    Text(
                        pr.title,
                        Modifier.clip(RoundedCornerShape(8.dp)).background(if (pr == activeProp) Color(pr.color).copy(alpha = 0.45f) else if (on) Pal.panel2 else Color.Transparent)
                            .border(1.dp, Color(pr.color).copy(alpha = if (on) 1f else 0.35f), RoundedCornerShape(8.dp))
                            .clickable {
                                if (pr == activeProp) { if (shown.size > 1) { shown = shown - pr; activeProp = shown.first() } }
                                else { shown = shown + pr; activeProp = pr }
                            }.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontSize = 12.sp, color = Pal.text
                    )
                }
            }
            Text("Нажмите свойство — сделать главным (его точки можно тянуть); ещё раз — скрыть.", fontSize = 10.sp, color = Pal.dim)

            // диапазоны значений замораживаются на время перетаскивания, чтобы график не «уезжал»
            val frozen = remember { mutableStateOf<Map<EditorController.GraphProp, Pair<Float, Float>>?>(null) }
            fun ranges(): Map<EditorController.GraphProp, Pair<Float, Float>> = frozen.value ?: props.associateWith { pr ->
                var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
                val step = max(1, viewLen / 300)
                var f = viewStart
                while (f <= min(fc - 1, viewStart + viewLen)) {
                    Renderer.keyTransformAt(layer, f)?.let { t -> val v = pr.of(t); lo = min(lo, v); hi = max(hi, v) }
                    f += step
                }
                if (lo > hi) { lo = 0f; hi = 1f }
                val minSpan = when (pr) { EditorController.GraphProp.X, EditorController.GraphProp.Y -> 40f; EditorController.GraphProp.ROT -> 20f; else -> 0.2f }
                if (hi - lo < minSpan) { val c = (hi + lo) / 2; lo = c - minSpan / 2; hi = c + minSpan / 2 }
                val pad = (hi - lo) * 0.12f
                (lo - pad) to (hi + pad)
            }
            val rangesNow = ranges()
            val st by rememberUpdatedState(Triple(viewStart, viewLen, rangesNow))
            val prop by rememberUpdatedState(activeProp)

            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp).background(Pal.bg, RoundedCornerShape(10.dp))) {
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(layer.id) {
                            detectTapGestures { pos ->
                                val (vs, vl, rg) = st
                                val f = (vs + (pos.x - 40f) / (size.width - 50f) * vl).roundToInt().coerceIn(0, fc - 1)
                                // ближайший ключ — выбрать, иначе просто перейти к кадру
                                val near = layer.keyframes.keys.minByOrNull { abs(it - f) }
                                val r = rg[prop]!!
                                if (near != null) {
                                    val kx = 40f + (near - vs).toFloat() / vl * (size.width - 50f)
                                    val ky = size.height - 24f - (prop.of(layer.keyframes[near]!!.transform) - r.first) / (r.second - r.first) * (size.height - 40f)
                                    if (abs(kx - pos.x) < 40f && abs(ky - pos.y) < 48f) { selKey = near; ctl.seek(near); return@detectTapGestures }
                                }
                                ctl.seek(f)
                            }
                        }
                        .pointerInput(layer.id) {
                            var dragKey: Int? = null
                            var startVal = 0f; var acc = Offset.Zero
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val (vs, vl, rg) = st
                                    val r = rg[prop]!!
                                    dragKey = layer.keyframes.keys.filter { it in vs..(vs + vl) }.minByOrNull { k ->
                                        val kx = 40f + (k - vs).toFloat() / vl * (size.width - 50f)
                                        val ky = size.height - 24f - (prop.of(layer.keyframes[k]!!.transform) - r.first) / (r.second - r.first) * (size.height - 40f)
                                        abs(kx - pos.x) + abs(ky - pos.y)
                                    }?.takeIf { k ->
                                        val kx = 40f + (k - vs).toFloat() / vl * (size.width - 50f)
                                        val ky = size.height - 24f - (prop.of(layer.keyframes[k]!!.transform) - r.first) / (r.second - r.first) * (size.height - 40f)
                                        abs(kx - pos.x) < 56f && abs(ky - pos.y) < 64f
                                    }
                                    dragKey?.let { startVal = prop.of(layer.keyframes[it]!!.transform); selKey = it; frozen.value = rg }
                                    acc = Offset.Zero
                                    if (dragKey == null) frozen.value = null
                                },
                                onDragEnd = { dragKey = null; frozen.value = null },
                                onDragCancel = { dragKey = null; frozen.value = null }
                            ) { ch, d ->
                                ch.consume()
                                val (vs, vl, rg) = st
                                val k = dragKey
                                if (k == null) {
                                    // пустое место: протяжка по кадрам
                                    ctl.seek((vs + (ch.position.x - 40f) / (size.width - 50f) * vl).roundToInt().coerceIn(0, fc - 1))
                                    return@detectDragGestures
                                }
                                acc += d
                                val r = rg[prop]!!
                                val v = startVal - acc.y / (size.height - 40f) * (r.second - r.first)
                                ctl.setKeyValue(layer.id, k, prop, v)
                                val target = (vs + (ch.position.x - 40f) / (size.width - 50f) * vl).roundToInt()
                                if (target != k && abs(acc.x) > 12f && ctl.moveKey(layer.id, k, target)) { dragKey = target; selKey = target }
                            }
                        }
                ) {
                    val (vs, vl, rg) = st
                    val w = size.width - 50f; val h = size.height - 40f
                    fun fx(f: Float) = 40f + (f - vs) / vl * w
                    fun fy(pr: EditorController.GraphProp, v: Float): Float { val r = rg[pr]!!; return size.height - 24f - (v - r.first) / (r.second - r.first) * h }
                    val paint = Paint().apply { isAntiAlias = true; textSize = 11.sp.toPx(); color = Pal.dim.toArgb() }
                    // сетка: секунды и кадры
                    val fps = ctl.project.fps
                    val tick = when { vl <= 30 -> 1; vl <= 120 -> 5; vl <= 600 -> fps; else -> fps * 10 }
                    var f = (vs / tick) * tick
                    while (f <= vs + vl) {
                        val x = fx(f.toFloat())
                        if (x >= 40f) {
                            drawLine(if (f % fps == 0) Pal.line else Pal.line.copy(alpha = 0.4f), Offset(x, 8f), Offset(x, size.height - 20f), 1f)
                            drawContext.canvas.nativeCanvas.drawText("${f + 1}", x + 2f, size.height - 6f, paint)
                        }
                        f += tick
                    }
                    // подписи главного свойства
                    val ra = rg[activeProp]!!
                    paint.color = Color(activeProp.color).toArgb()
                    for (q in 0..4) {
                        val v = ra.first + (ra.second - ra.first) * q / 4f
                        val y = fy(activeProp, v)
                        drawLine(Pal.line.copy(alpha = 0.35f), Offset(40f, y), Offset(size.width - 10f, y), 1f)
                        val label = if (activeProp == EditorController.GraphProp.SX || activeProp == EditorController.GraphProp.SY || activeProp == EditorController.GraphProp.ALPHA) "${(v * 100).roundToInt()}%" else "${v.roundToInt()}"
                        drawContext.canvas.nativeCanvas.drawText(label, 2f, y + 4f, paint)
                    }
                    // курсор текущего кадра
                    val cx = fx(ctl.frame.toFloat())
                    drawLine(Pal.pink, Offset(cx, 8f), Offset(cx, size.height - 20f), 2f)
                    // графики: сначала второстепенные, сверху — главный
                    val order = shown.sortedBy { if (it == activeProp) 1 else 0 }
                    val step = max(1, vl / 400)
                    for (pr in order) {
                        val path = Path(); var first = true
                        var fr = vs
                        while (fr <= min(fc - 1, vs + vl)) {
                            Renderer.keyTransformAt(layer, fr)?.let { t ->
                                val x = fx(fr.toFloat()); val y = fy(pr, pr.of(t))
                                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                            }
                            fr += step
                        }
                        val main = pr == activeProp
                        drawPath(path, Color(pr.color).copy(alpha = if (main) 1f else 0.45f), style = Stroke(if (main) 3.5f else 2f, pathEffect = if (main) null else PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                        if (main) for ((kf, k) in layer.keyframes.subMap(vs, true, vs + vl, true)) {
                            val x = fx(kf.toFloat()); val y = fy(pr, pr.of(k.transform))
                            val selected = kf == selKey
                            drawCircle(Color(pr.color), if (selected) 11f else 8f, Offset(x, y))
                            if (k.tween == TweenType.NONE) drawCircle(Pal.bg, if (selected) 6f else 4f, Offset(x, y))
                            if (selected) drawCircle(Color.White, 14f, Offset(x, y), style = Stroke(2f))
                        }
                    }
                }
            }
            // масштаб времени
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text("Видно кадров: $viewLen  ", fontSize = 12.sp, color = Pal.dim)
                SmallBtn("−") { viewLen = (viewLen * 3 / 2).coerceAtMost(fc); viewStart = viewStart.coerceIn(0, max(0, fc - viewLen)) }
                SmallBtn("+") { viewLen = (viewLen * 2 / 3).coerceAtLeast(min(8, fc)) }
                SmallBtn("По ключам") { viewStart = (firstKey - 2).coerceIn(0, max(0, fc - 1)); viewLen = (lastKey - firstKey + 6).coerceIn(min(8, fc), fc) }
                SmallBtn("Весь") { viewStart = 0; viewLen = fc }
                SmallBtn("◀") { viewStart = (viewStart - max(1, viewLen / 3)).coerceAtLeast(0) }
                SmallBtn("▶") { viewStart = (viewStart + max(1, viewLen / 3)).coerceAtMost(max(0, fc - viewLen)) }
            }
            // выбранный ключ: значение и сглаживание участка после него
            val k = selKey?.let { layer.keyframes[it] }
            if (k != null && selKey != null) {
                val kf = selKey!!
                var txt by remember(kf, activeProp, ctl.version) {
                    val v = activeProp.of(k.transform)
                    mutableStateOf(if (activeProp == EditorController.GraphProp.SX || activeProp == EditorController.GraphProp.SY || activeProp == EditorController.GraphProp.ALPHA) "${(v * 100).roundToInt()}" else "%.1f".format(v))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ключ ${kf + 1} · ${activeProp.title}: ", fontSize = 12.sp, color = Pal.text)
                    OutlinedTextField(
                        txt, { s -> txt = s; s.replace(',', '.').toFloatOrNull()?.let { v ->
                            val real = if (activeProp == EditorController.GraphProp.SX || activeProp == EditorController.GraphProp.SY || activeProp == EditorController.GraphProp.ALPHA) v / 100f else v
                            ctl.setKeyValue(layer.id, kf, activeProp, real)
                        } },
                        singleLine = true, modifier = Modifier.width(110.dp)
                    )
                    Text("  Участок после: ", fontSize = 12.sp, color = Pal.dim)
                    listOf(Easing.LINEAR, Easing.EASE_IN_OUT, Easing.EASE_IN, Easing.EASE_OUT).forEach { e ->
                        SmallBtn(e.title, selected = k.tween != TweenType.NONE && !k.hasCustomCurve && k.easing == e) { ctl.setSegmentEasing(layer.id, kf, e) }
                    }
                    SmallBtn("Своя кривая…") { ctl.seek(kf); onCurves() }
                    SmallBtn("Без твина", selected = k.tween == TweenType.NONE) { ctl.seek(kf); ctl.setTween(TweenType.NONE) }
                }
            } else Text("Коснитесь точки-ключа, чтобы ввести точное значение и выбрать сглаживание.", fontSize = 11.sp, color = Pal.dim, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

