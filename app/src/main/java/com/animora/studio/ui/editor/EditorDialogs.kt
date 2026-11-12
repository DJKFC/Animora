package com.animora.studio.ui.editor

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animora.studio.engine.Renderer
import com.animora.studio.export.ExportFormat
import com.animora.studio.export.ExportSettings
import com.animora.studio.model.BgType
import com.animora.studio.model.BrushKind
import com.animora.studio.model.StrokeElement
import com.animora.studio.ui.ColorDot
import com.animora.studio.ui.ColorPickerDialog
import com.animora.studio.ui.Pal
import com.animora.studio.ui.SectionTitle
import kotlin.math.sin

@Composable
private fun SwitchRow(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 14.sp)
        Switch(value, onChange)
    }
}

@Composable
private fun LabeledSlider(text: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(text, fontSize = 13.sp, color = Pal.dim, modifier = Modifier.padding(top = 6.dp))
    Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrushDialog(ctl: EditorController, onImport: () -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(if (ctl.tool == Tool.ERASER) BrushKind.ERASER else ctl.brushKind) }
    val b = ctl.settingsFor(kind)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (kind != BrushKind.ERASER) { ctl.brushKind = kind; ctl.tool = Tool.BRUSH }; onDismiss() }) { Text("Готово") } },
        title = { Text("Кисти") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrushKind.entries.filter { it != BrushKind.CUSTOM }.forEach { k -> SmallBtn(k.title, selected = k == kind) { kind = k } }
                }
                SectionTitle("Кисти Krita (${ctl.customBrushes.size})")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ctl.customBrushes.forEach { cb ->
                        CustomBrushCard(cb, ctl.color, selected = kind == BrushKind.CUSTOM && ctl.customBrushId == cb.id,
                            onClick = { ctl.selectCustomBrush(cb.id); kind = BrushKind.CUSTOM },
                            onDelete = { ctl.deleteCustomBrush(cb.id); if (kind == BrushKind.CUSTOM && ctl.customBrushId == null) kind = BrushKind.PEN })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    SmallBtn("+ Импорт кистей Krita", onClick = onImport)
                    Text("  .kpp · .bundle · .gbr · .gih · .png", fontSize = 11.sp, color = Pal.dim)
                }
                if (kind == BrushKind.CUSTOM) ctl.customBrushes.firstOrNull { it.id == ctl.customBrushId }?.let { cb ->
                    Text(
                        "Шаг ${(cb.spacing * 100).toInt()}% · поток ${(cb.flow * 100).toInt()}%" +
                            (if (cb.rotateWithStroke) " · поворот по штриху" else "") +
                            (if (cb.randomRotation > 0) " · случайный поворот" else "") +
                            (if (cb.scatter > 0) " · разброс" else "") +
                            (if (cb.colored) " · цветной кончик" else "") +
                            (if (cb.masks.size > 1) " · ${cb.masks.size} кончиков" else ""),
                        fontSize = 11.sp, color = Pal.dim, modifier = Modifier.padding(top = 4.dp)
                    )
                }
                // предпросмотр мазка с имитацией нажима
                val previewTip = if (kind == BrushKind.CUSTOM) ctl.activeTipId else null
                val preview = remember(b, kind, ctl.color, previewTip) {
                    val n = 60
                    val pts = FloatArray(n * 3)
                    for (i in 0 until n) {
                        val t = i / (n - 1f)
                        pts[i * 3] = 20f + t * 520f; pts[i * 3 + 1] = 60f + sin(t * 6.28f) * 28f
                        pts[i * 3 + 2] = if (b.pressureSize) 0.15f + 0.85f * sin(t * 3.14f) else 1f
                    }
                    StrokeElement(pts, if (kind == BrushKind.ERASER) android.graphics.Color.GRAY else ctl.color, b.size.coerceAtMost(60f),
                        when { kind == BrushKind.ERASER -> BrushKind.PEN; kind == BrushKind.CUSTOM && previewTip == null -> BrushKind.PEN; else -> kind },
                        b.opacity, previewTip)
                }
                val r = remember { Renderer() }
                Canvas(Modifier.fillMaxWidth().height(110.dp).padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color.White)) {
                    drawIntoCanvas { c ->
                        c.nativeCanvas.save()
                        c.nativeCanvas.scale(size.width / 560f, size.width / 560f)
                        r.drawElement(c.nativeCanvas, preview)
                        c.nativeCanvas.restore()
                    }
                }
                LabeledSlider("Размер: ${b.size.toInt()} px", b.size, 1f..300f) { v -> ctl.updateBrush(kind) { it.copy(size = v) } }
                LabeledSlider("Непрозрачность: ${(b.opacity * 100).toInt()}%", b.opacity, 0.02f..1f) { v -> ctl.updateBrush(kind) { it.copy(opacity = v) } }
                LabeledSlider("Стабилизатор (сглаживание): ${(b.smoothing * 100).toInt()}%", b.smoothing, 0f..0.95f) { v -> ctl.updateBrush(kind) { it.copy(smoothing = v) } }
                LabeledSlider("Кривая нажима: ${"%.2f".format(b.curve)} (меньше — мягче)", b.curve, 0.3f..3f) { v -> ctl.updateBrush(kind) { it.copy(curve = v) } }
                SwitchRow("Нажим → толщина", b.pressureSize) { v -> ctl.updateBrush(kind) { it.copy(pressureSize = v) } }
                SwitchRow("Нажим → прозрачность", b.pressureOpacity) { v -> ctl.updateBrush(kind) { it.copy(pressureOpacity = v) } }
                SwitchRow("Наклон стилуса → ширина (штриховка)", b.tiltSize) { v -> ctl.updateBrush(kind) { it.copy(tiltSize = v) } }
                SwitchRow("Сужение концов (для пальца)", b.taper) { v -> ctl.updateBrush(kind) { it.copy(taper = v) } }
            }
        }
    )
}

@Composable
fun OnionDialog(ctl: EditorController, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        title = { Text("Луковая кожа (калька)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SwitchRow("Включена", ctl.onionEnabled) { ctl.onionEnabled = it }
                LabeledSlider("Предыдущих: ${ctl.onionPrev}", ctl.onionPrev.toFloat(), 0f..6f) { ctl.onionPrev = it.toInt() }
                LabeledSlider("Следующих: ${ctl.onionNext}", ctl.onionNext.toFloat(), 0f..6f) { ctl.onionNext = it.toInt() }
                LabeledSlider("Непрозрачность: ${(ctl.onionOpacity * 100).toInt()}%", ctl.onionOpacity, 0.05f..0.9f) { ctl.onionOpacity = it }
                SwitchRow("Только ключевые кадры (рисунки)", ctl.onionKeysOnly) { ctl.onionKeysOnly = it }
                SwitchRow("Цветная (красный — до, зелёный — после)", ctl.onionTint) { ctl.onionTint = it }
                SwitchRow("Для всех слоёв", ctl.onionAllLayers) { ctl.onionAllLayers = it }
            }
        }
    )
}

@Composable
fun PlaybackDialog(ctl: EditorController, onDismiss: () -> Unit) {
    val p = ctl.project
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        title = { Text("Воспроизведение") },
        text = {
            Column {
                PlayMode.entries.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { ctl.playMode = m }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(ctl.playMode == m, { ctl.playMode = m }); Text(m.title)
                    }
                }
                SwitchRow("Играть только диапазон", ctl.useRange) { ctl.useRange = it }
                if (ctl.useRange) {
                    LabeledSlider("Начало: ${ctl.rangeFrom + 1}", ctl.rangeFrom.toFloat(), 0f..maxOf(1, p.frameCount - 1).toFloat()) { ctl.rangeFrom = it.toInt().coerceAtMost(ctl.rangeTo) }
                    LabeledSlider("Конец: ${ctl.rangeTo + 1}", ctl.rangeTo.toFloat(), 0f..maxOf(1, p.frameCount - 1).toFloat()) { ctl.rangeTo = it.toInt().coerceAtLeast(ctl.rangeFrom) }
                    Row {
                        SmallBtn("Начало = текущий") { ctl.rangeFrom = ctl.frame.coerceAtMost(ctl.rangeTo) }
                        SmallBtn("Конец = текущий") { ctl.rangeTo = ctl.frame.coerceAtLeast(ctl.rangeFrom) }
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectDialog(ctl: EditorController, onDismiss: () -> Unit) {
    val p = ctl.project
    var name by remember { mutableStateOf(p.name) }
    var fps by remember { mutableIntStateOf(p.fps) }
    var frames by remember { mutableStateOf(p.frameCount.toString()) }
    var bgType by remember { mutableStateOf(p.bgType) }
    var bg by remember { mutableIntStateOf(p.bgColor) }
    var picker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                ctl.checkpoint()
                p.name = name.ifBlank { p.name }; p.fps = fps; p.bgType = bgType; p.bgColor = bg
                ctl.changed()
                frames.toIntOrNull()?.let { ctl.setFrameCount(it) }
                onDismiss()
            }) { Text("Применить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text("Параметры проекта") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Размер холста: ${p.width}×${p.height}", fontSize = 13.sp, color = Pal.dim, modifier = Modifier.padding(top = 8.dp))
                SectionTitle("Частота кадров")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(6, 8, 12, 15, 24, 25, 30, 48, 60).forEach { f -> SmallBtn("$f", selected = fps == f) { fps = f } }
                }
                OutlinedTextField(
                    frames, { frames = it.filter { c -> c.isDigit() }.take(6) }, label = { Text("Длина, кадров (≈ ${"%.1f".format((frames.toIntOrNull() ?: 0) / fps.toFloat())} с)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                SectionTitle("Фон")
                Row { BgType.entries.forEach { t -> SmallBtn(t.title, selected = bgType == t) { bgType = t } } }
                if (bgType != BgType.TRANSPARENT) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(bg, 34) { picker = true }; Text("  Цвет фона", fontSize = 13.sp)
                }
            }
        }
    )
    if (picker) ColorPickerDialog(bg, p.palette, emptyList(), {}, { picker = false }) { bg = it; picker = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportDialog(ctl: EditorController, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val p = ctl.project
    var format by remember { mutableStateOf(ExportFormat.MP4) }
    var scale by remember { mutableFloatStateOf(1f) }
    var useRange by remember { mutableStateOf(ctl.useRange) }
    var transparent by remember { mutableStateOf(p.bgType == BgType.TRANSPARENT) }
    var pingPong by remember { mutableStateOf(false) }
    var guides by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Uri?>(null) }
    var resultMime by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val progress = ctl.exportProgress

    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        confirmButton = {
            if (result != null) {
                TextButton(onClick = {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = resultMime; putExtra(Intent.EXTRA_STREAM, result); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(i, "Поделиться"))
                }) { Text("Поделиться") }
            } else TextButton(enabled = progress == null, onClick = {
                error = null
                val s = ExportSettings(
                    format, scale,
                    if (useRange) ctl.rangeFrom else 0, if (useRange) ctl.rangeTo else p.frameCount - 1,
                    transparent, guides, 0, ctl.frame, pingPong
                )
                if (format == ExportFormat.PROJECT) ctl.saveAsync()
                ctl.export(s) { uri, err -> result = uri; error = err; resultMime = format.mime }
            }) { Text("Экспорт") }
        },
        dismissButton = { TextButton(enabled = progress == null, onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Экспорт") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ExportFormat.entries.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { format = f; result = null }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(format == f, { format = f; result = null }); Text(f.title, fontSize = 14.sp)
                    }
                }
                if (format != ExportFormat.PROJECT) {
                    SectionTitle("Масштаб (${(p.width * scale).toInt()}×${(p.height * scale).toInt()})")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0.25f, 0.5f, 0.75f, 1f, 2f).forEach { s -> SmallBtn("${(s * 100).toInt()}%", selected = scale == s) { scale = s; result = null } }
                    }
                    SwitchRow("Только диапазон воспроизведения", useRange) { useRange = it }
                    if (format != ExportFormat.MP4) SwitchRow("Прозрачный фон", transparent) { transparent = it }
                    if (format == ExportFormat.MP4 || format == ExportFormat.GIF) SwitchRow("Туда-обратно (пинг-понг)", pingPong) { pingPong = it }
                    SwitchRow("Включить направляющие слои", guides) { guides = it }
                }
                progress?.let {
                    Text("Экспорт… ${(it * 100).toInt()}%", fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                result?.let { Text("Готово! Файл сохранён в «Загрузки/Animora».", color = Pal.shape, modifier = Modifier.padding(top = 10.dp)) }
                error?.let { Text("Ошибка: $it", color = Pal.pink, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
    )
}

@Composable
fun FrameMenuDialog(ctl: EditorController, onDismiss: () -> Unit) {
    var repeat by remember { mutableStateOf("1") }
    var label by remember { mutableStateOf(ctl.heldKey()?.second?.label ?: "") }
    fun act(a: () -> Unit) { a(); onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Кадр ${ctl.frame + 1} · ${ctl.activeLayer?.name ?: ""}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionTitle("Ключевые кадры")
                MenuLine("Вставить ключевой кадр (копия)") { act { ctl.insertKeyframe() } }
                MenuLine("Вставить пустой ключевой кадр") { act { ctl.insertBlankKeyframe() } }
                MenuLine("Удалить ключевой кадр") { act { ctl.clearKeyframe() } }
                MenuLine("Копировать ключ") { act { ctl.copyKeyframe() } }
                MenuLine("Вставить ключ сюда") { act { ctl.pasteKeyframe() } }
                MenuLine("Очистить содержимое ключа") { act { ctl.clearKeyContent() } }
                SectionTitle("Кадры")
                MenuLine("Вставить кадр (все слои)") { act { ctl.insertFrame(true) } }
                MenuLine("Удалить кадр (все слои)") { act { ctl.removeFrame(true) } }
                MenuLine("Удлинить экспозицию (только слой)") { act { ctl.insertFrame(false) } }
                MenuLine("Укоротить экспозицию (только слой)") { act { ctl.removeFrame(false) } }
                SectionTitle("Анимация")
                MenuLine("Твин движения") { act { ctl.setTween(com.animora.studio.model.TweenType.MOTION) } }
                MenuLine("Твин формы (морфинг штрихов)") { act { ctl.setTween(com.animora.studio.model.TweenType.SHAPE) } }
                MenuLine("Убрать твин") { act { ctl.setTween(com.animora.studio.model.TweenType.NONE) } }
                MenuLine("Запечь твин в промежуточные кадры") { act { ctl.bakeTween() } }
                MenuLine("Развернуть анимацию слоя") { act { ctl.reverseLayer() } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(repeat, { repeat = it.filter { c -> c.isDigit() }.take(2) }, label = { Text("Повторов") }, singleLine = true, modifier = Modifier.width(110.dp))
                    SmallBtn("Зациклить слой") { act { ctl.repeatCycle(repeat.toIntOrNull() ?: 1) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(label, { label = it }, label = { Text("Метка ключа") }, singleLine = true, modifier = Modifier.width(170.dp))
                    SmallBtn("OK") { act { ctl.heldKey()?.second?.let { k -> ctl.checkpoint(); k.label = label; ctl.changed() } } }
                }
            }
        }
    )
}

@Composable
private fun MenuLine(text: String, onClick: () -> Unit) {
    Text(
        text, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp)
    )
}


@Composable
private fun CustomBrushCard(b: com.animora.studio.model.CustomBrush, color: Int, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val r = remember { Renderer() }
    val stroke = remember(b.id, color) {
        val n = 40
        val pts = FloatArray(n * 3)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            pts[i * 3] = 12f + t * 136f; pts[i * 3 + 1] = 30f + sin(t * 6.28f) * 10f; pts[i * 3 + 2] = 0.3f + 0.7f * sin(t * 3.14f)
        }
        StrokeElement(pts, color, 22f, BrushKind.CUSTOM, 1f, b.id)
    }
    Column(
        Modifier.width(160.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) Pal.accent.copy(alpha = 0.3f) else Pal.panel2)
            .clickable(onClick = onClick).padding(4.dp)
    ) {
        Canvas(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)) {
            drawIntoCanvas { c ->
                c.nativeCanvas.save(); c.nativeCanvas.scale(size.width / 160f, size.height / 60f)
                r.drawElement(c.nativeCanvas, stroke)
                c.nativeCanvas.restore()
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(b.name, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 2.dp))
            Text("✕", fontSize = 13.sp, color = Pal.pink, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDelete).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}
