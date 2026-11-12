package com.animora.studio.ui.editor

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animora.studio.model.Easing
import com.animora.studio.model.LayerType
import com.animora.studio.model.TweenType
import com.animora.studio.ui.Pal
import com.animora.studio.ui.UiPrefs
import kotlin.math.max
import kotlin.math.min

private val CELL_W: Dp get() = UiPrefs.timelineCell.dp
private val ROW_H = 28.dp
private val HEAD_H = 22.dp

@Composable
fun Timeline(ctl: EditorController, onMore: () -> Unit, onCurves: () -> Unit) {
    ctl.version
    val p = ctl.project
    val layers = p.layers.reversed()
    val rows = layers.size
    val vs = rememberScrollState()
    val density = LocalDensity.current
    val cellPx = with(density) { CELL_W.toPx() }
    var viewW by remember { mutableIntStateOf(0) }
    // Своя горизонтальная прокрутка: рисуются только видимые кадры, поэтому длина таймлайна не ограничена
    var scrollX by remember { mutableFloatStateOf(0f) }
    val maxScroll = max(0f, p.frameCount * cellPx + with(density) { 60.dp.toPx() } - viewW)
    val maxNow by rememberUpdatedState(maxScroll)
    val hScroll = rememberScrollableState { d ->
        val old = scrollX
        scrollX = (scrollX - d).coerceIn(0f, maxNow)
        old - scrollX
    }
    val sx: () -> Float = { scrollX }

    LaunchedEffect(maxScroll) { scrollX = scrollX.coerceIn(0f, maxScroll) }
    LaunchedEffect(ctl.frame, viewW) {
        val x = ctl.frame * cellPx
        if (viewW > 0 && (x < scrollX || x > scrollX + viewW - cellPx * 2)) scrollX = (x - viewW / 3f).coerceIn(0f, maxNow)
    }

    Column(Modifier.fillMaxWidth().background(Pal.panel)) {
        TimelineControls(ctl, onMore, onCurves)
        val visibleRows = min(max(rows, 1), UiPrefs.timelineRows)
        Row(Modifier.fillMaxWidth().height(HEAD_H + ROW_H * visibleRows)) {
            Column(Modifier.width(118.dp).fillMaxHeight()) {
                Box(Modifier.height(HEAD_H).fillMaxWidth().background(Pal.panel2), contentAlignment = Alignment.CenterStart) {
                    Text("  Слои", fontSize = 11.sp, color = Pal.dim)
                }
                Column(Modifier.verticalScroll(vs)) {
                    layers.forEach { l ->
                        val active = l.id == ctl.activeLayerId
                        Row(
                            Modifier.height(ROW_H).fillMaxWidth().background(if (active) Pal.accent.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable { ctl.selectLayer(l.id) }.padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(4.dp).height(18.dp).background(Color(l.color)))
                            Icon(
                                if (l.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, "Видимость",
                                Modifier.padding(horizontal = 4.dp).size(16.dp).clickable { ctl.updateLayer(l.id) { it.visible = !it.visible } },
                                tint = if (l.visible) Pal.dim else Pal.pink
                            )
                            if (l.type == LayerType.BONES) Icon(Icons.Filled.AccountTree, null, Modifier.size(13.dp), tint = Pal.accent2)
                            Text(l.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (l.guide) Pal.dim else Pal.text)
                        }
                    }
                }
            }
            Box(
                Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewW = it.width }.clipToBounds()
                    .scrollable(hScroll, Orientation.Horizontal)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    TimelineHeader(ctl, cellPx, sx, viewW, Modifier.fillMaxWidth().height(HEAD_H))
                    Box(Modifier.verticalScroll(vs)) {
                        TimelineGrid(ctl, cellPx, sx, viewW, onMore, Modifier.fillMaxWidth().height(ROW_H * max(rows, 1)))
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineHeader(ctl: EditorController, cellPx: Float, sx: () -> Float, viewW: Int, modifier: Modifier) {
    val frameCount = ctl.project.frameCount.also { ctl.version }
    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9C97B5.toInt() } }
    val density = LocalDensity.current
    Canvas(
        modifier.background(Pal.panel2)
            .pointerInputScrub(ctl, cellPx, sx)
    ) {
        textPaint.textSize = with(density) { 10.sp.toPx() }
        val fh = size.height
        val scroll = sx()
        val f0 = max(0, (scroll / cellPx).toInt() - 2)
        val f1 = min(frameCount, ((scroll + viewW) / cellPx).toInt() + 3)
        translate(left = -scroll) {
        if (ctl.useRange) {
            drawRect(Pal.accent2.copy(alpha = 0.18f), Offset(ctl.rangeFrom * cellPx, 0f), Size((ctl.rangeTo - ctl.rangeFrom + 1) * cellPx, fh))
        }
        for (f in f0 until f1) {
            val x = f * cellPx
            val n = f + 1
            if (n == 1 || n % 5 == 0) {
                drawLine(Pal.dim.copy(alpha = 0.5f), Offset(x, fh * 0.55f), Offset(x, fh), 1f)
                drawContext.canvas.nativeCanvas.drawText("$n", x + 3f, fh * 0.62f, textPaint)
            } else drawLine(Pal.line, Offset(x, fh * 0.8f), Offset(x, fh), 1f)
            if (ctl.project.fps > 0 && f % ctl.project.fps == 0 && f > 0) drawLine(Pal.accent2.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, fh), 1.5f)
        }
        val px = ctl.frame * cellPx
        drawRect(Pal.pink, Offset(px, 0f), Size(cellPx, fh), alpha = 0.85f)
        drawContext.canvas.nativeCanvas.drawText("${ctl.frame + 1}", px + 3f, fh * 0.75f, Paint(textPaint).apply { color = android.graphics.Color.WHITE })
        }
    }
}

@Composable
private fun Modifier.pointerInputScrub(ctl: EditorController, cellPx: Float, sx: () -> Float): Modifier {
    val c by rememberUpdatedState(ctl)
    val scroll by rememberUpdatedState(sx)
    return this
        .pointerInput(cellPx) { detectTapGestures { c.stop(); c.seek(((it.x + scroll()) / cellPx).toInt()) } }
        .pointerInput("drag", cellPx) {
            detectHorizontalDragGestures(onDragStart = { c.stop(); c.seek(((it.x + scroll()) / cellPx).toInt()) }) { ch, _ ->
                ch.consume(); c.seek(((ch.position.x + scroll()) / cellPx).toInt())
            }
        }
}

@Composable
private fun TimelineGrid(ctl: EditorController, cellPx: Float, sx: () -> Float, viewW: Int, onMore: () -> Unit, modifier: Modifier) {
    val scroll by rememberUpdatedState(sx)
    ctl.version
    val p = ctl.project
    val layers = p.layers.reversed()
    val density = LocalDensity.current
    val rowPx = with(density) { ROW_H.toPx() }
    val layersNow by rememberUpdatedState(layers)
    val c by rememberUpdatedState(ctl)
    val more by rememberUpdatedState(onMore)
    Canvas(
        modifier.pointerInput(cellPx, rowPx) {
            detectTapGestures(
                onTap = { o ->
                    val r = (o.y / rowPx).toInt()
                    layersNow.getOrNull(r)?.let { c.selectLayer(it.id) }
                    c.stop(); c.seek(((o.x + scroll()) / cellPx).toInt())
                },
                onLongPress = { o ->
                    val r = (o.y / rowPx).toInt()
                    layersNow.getOrNull(r)?.let { c.selectLayer(it.id) }
                    c.stop(); c.seek(((o.x + scroll()) / cellPx).toInt()); more()
                }
            )
        }
    ) {
        val fc = p.frameCount
        val scrollX = sx()
        val f0 = max(0, (scrollX / cellPx).toInt() - 2)
        val f1 = min(fc, ((scrollX + viewW) / cellPx).toInt() + 3)
        translate(left = -scrollX) {
        layers.forEachIndexed { r, l ->
            val y = r * rowPx
            if (l.id == ctl.activeLayerId) drawRect(Pal.accent.copy(alpha = 0.12f), Offset(0f, y), Size(fc * cellPx, rowPx))
            for (f in f0 until f1) {
                val x = f * cellPx
                if ((f + 1) % 5 == 0) drawRect(Color.White.copy(alpha = 0.035f), Offset(x, y), Size(cellPx, rowPx))
                drawLine(Pal.line.copy(alpha = 0.6f), Offset(x, y), Offset(x, y + rowPx), 1f)
            }
            drawLine(Pal.line, Offset(0f, y + rowPx), Offset(fc * cellPx, y + rowPx), 1f)
            val cy = y + rowPx / 2
            if (l.type == LayerType.DRAWING) {
                val keys = l.keyframes.entries.toList()
                keys.forEachIndexed { i, (k, kf) ->
                    val end = if (i + 1 < keys.size) keys[i + 1].key else fc
                    if (end <= f0 && i + 1 < keys.size) return@forEachIndexed
                    if (k > f1) return@forEachIndexed
                    val x0 = k * cellPx; val x1 = min(end, fc) * cellPx
                    val hasNext = i + 1 < keys.size
                    val span = when {
                        kf.tween == TweenType.MOTION && hasNext -> Pal.motion.copy(alpha = 0.35f)
                        kf.tween == TweenType.SHAPE && hasNext -> Pal.shape.copy(alpha = 0.32f)
                        kf.isEmpty -> Color.Transparent
                        else -> Color(l.color).copy(alpha = 0.22f)
                    }
                    drawRect(span, Offset(x0 + 1, y + 4), Size(max(0f, x1 - x0 - 2), rowPx - 8))
                    if (kf.tween != TweenType.NONE && hasNext && kf.hasCustomCurve && x1 - x0 > cellPx * 3) {
                        // значок своей кривой в середине пролёта
                        val mx = (x0 + x1) / 2; val w = cellPx * 0.9f
                        val wave = Path().apply {
                            moveTo(mx - w, cy + 5f)
                            cubicTo(mx - w / 2, cy + 5f, mx - w / 3, cy - 7f, mx, cy - 7f)
                            cubicTo(mx + w / 3, cy - 7f, mx + w / 2, cy + 5f, mx + w, cy + 5f)
                        }
                        drawPath(wave, Pal.accent2, style = Stroke(2f))
                    }
                    if (kf.tween != TweenType.NONE && hasNext) {
                        val col = if (kf.tween == TweenType.MOTION) Pal.motion else Pal.shape
                        drawLine(col, Offset(x0 + cellPx, cy), Offset(x1 - 5, cy), 2f)
                        drawLine(col, Offset(x1 - 5, cy), Offset(x1 - 10, cy - 4), 2f)
                        drawLine(col, Offset(x1 - 5, cy), Offset(x1 - 10, cy + 4), 2f)
                    } else if (!kf.isEmpty && end - k > 1) {
                        drawRect(Color.White.copy(alpha = 0.5f), Offset(x1 - cellPx / 2 - 3, cy - 4), Size(6f, 8f), style = Stroke(1.2f))
                    }
                    val kx = x0 + cellPx / 2
                    if (kf.isEmpty) drawCircle(Color.White, 4.5f * density.density / 1.5f, Offset(kx, cy), style = Stroke(1.6f))
                    else drawCircle(Color.White, 4.5f * density.density / 1.5f, Offset(kx, cy))
                }
            } else {
                l.poses.keys.forEach { k ->
                    val kx = k * cellPx + cellPx / 2
                    val s = 5f * density.density / 1.5f
                    val path = Path().apply { moveTo(kx, cy - s); lineTo(kx + s, cy); lineTo(kx, cy + s); lineTo(kx - s, cy); close() }
                    drawPath(path, Pal.accent2)
                }
                val keys = l.poses.keys.toList()
                for (i in 0 until keys.size - 1) drawLine(Pal.accent2.copy(alpha = 0.5f), Offset(keys[i] * cellPx + cellPx, cy), Offset(keys[i + 1] * cellPx, cy), 2f)
            }
        }
        val px = ctl.frame * cellPx
        drawRect(Pal.pink.copy(alpha = 0.16f), Offset(px, 0f), Size(cellPx, size.height))
        drawLine(Pal.pink, Offset(px + cellPx / 2, 0f), Offset(px + cellPx / 2, size.height), 2f)
        drawLine(Pal.dim, Offset(fc * cellPx, 0f), Offset(fc * cellPx, size.height), 2f)
        }
    }
}

@Composable
private fun TimelineControls(ctl: EditorController, onMore: () -> Unit, onCurves: () -> Unit) {
    ctl.version
    var easeMenu by remember { mutableStateOf(false) }
    val layer = ctl.activeLayer
    val held = ctl.heldKey()
    Row(
        Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBtn(Icons.Filled.SkipPrevious, "В начало") { ctl.stop(); ctl.seek(0) }
        IconBtn(Icons.Filled.FastRewind, "Предыдущий кадр") { ctl.stop(); ctl.seek(ctl.frame - 1) }
        IconBtn(Icons.Filled.FastForward, "Следующий кадр") { ctl.stop(); ctl.seek(ctl.frame + 1) }
        IconBtn(Icons.Filled.SkipNext, "В конец") { ctl.stop(); ctl.seek(ctl.project.frameCount - 1) }
        SmallBtn("◀ ключ") { layer?.keyframes?.lowerKey(ctl.frame)?.let { ctl.seek(it) } }
        SmallBtn("ключ ▶") { layer?.keyframes?.higherKey(ctl.frame)?.let { ctl.seek(it) } }
        Spacer(Modifier.width(8.dp))
        SmallBtn("+ Ключ") { ctl.insertKeyframe() }
        SmallBtn("+ Пустой") { ctl.insertBlankKeyframe() }
        SmallBtn("− Ключ") { ctl.clearKeyframe() }
        SmallBtn("+ Кадр") { ctl.insertFrame(allLayers = true) }
        SmallBtn("− Кадр") { ctl.removeFrame(allLayers = true) }
        Spacer(Modifier.width(8.dp))
        if (layer?.type == LayerType.DRAWING) {
            Text("Твин:", fontSize = 12.sp, color = Pal.dim)
            TweenType.entries.forEach { t -> SmallBtn(t.title, selected = held?.second?.tween == t) { ctl.setTween(t) } }
        }
        Box {
            val curEase = if (layer?.type == LayerType.BONES) layer.poses.floorEntry(ctl.frame)?.value?.easing else held?.second?.easing
            val custom = if (layer?.type == LayerType.BONES) layer.poses.floorEntry(ctl.frame)?.value?.curve != null else held?.second?.hasCustomCurve == true
            SmallBtn("Сглаж.: ${if (custom) "своя кривая" else curEase?.title ?: "—"}") { easeMenu = true }
            DropdownMenu(expanded = easeMenu, onDismissRequest = { easeMenu = false }) {
                DropdownMenuItem(text = { Text("Своя кривая… (редактор)") }, onClick = { easeMenu = false; onCurves() })
                Easing.entries.forEach { e ->
                    DropdownMenuItem(text = { Text(e.title) }, onClick = { ctl.setEasing(e); easeMenu = false }, leadingIcon = {
                        Canvas(Modifier.size(28.dp, 20.dp)) {
                            val path = Path()
                            for (i in 0..24) {
                                val t = i / 24f
                                val v = e.apply(t)
                                val px = t * size.width; val py = size.height - v * size.height * 0.8f - size.height * 0.1f
                                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                            }
                            drawPath(path, Pal.accent, style = Stroke(2f))
                        }
                    })
                }
            }
        }
        SmallBtn("〰 Кривая", onClick = onCurves)
        IconBtn(Icons.Filled.MoreHoriz, "Ещё", onClick = onMore)
    }
}
