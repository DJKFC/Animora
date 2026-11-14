package com.animora.studio.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animora.studio.ui.Pal
import com.animora.studio.ui.SectionTitle

/** Выравнивание, распределение и точные размеры выделения. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlignDialog(ctl: EditorController, onDismiss: () -> Unit) {
    ctl.version
    var toStage by remember { mutableStateOf(true) }
    val b = ctl.selectionBounds()
    var xs by remember { mutableStateOf(b?.left?.toInt()?.toString() ?: "0") }
    var ys by remember { mutableStateOf(b?.top?.toInt()?.toString() ?: "0") }
    var ws by remember { mutableStateOf(b?.width()?.toInt()?.toString() ?: "0") }
    var hs by remember { mutableStateOf(b?.height()?.toInt()?.toString() ?: "0") }
    var rot by remember { mutableStateOf("0") }
    var keep by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выравнивание и размер") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (b == null) { Text("Выделите элементы лассо.", color = Pal.dim); return@AlertDialog }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Относительно кадра", Modifier.weight(1f), fontSize = 13.sp); Switch(toStage, { toStage = it })
                }
                Text(if (toStage) "Выравнивание по краям и центру кадра" else "Выравнивание по общей рамке выделения", fontSize = 11.sp, color = Pal.dim)
                SectionTitle("По горизонтали")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallBtn("⇤ Влево") { ctl.align(EditorController.AlignOp.LEFT, toStage) }
                    SmallBtn("↔ Центр") { ctl.align(EditorController.AlignOp.HCENTER, toStage) }
                    SmallBtn("Вправо ⇥") { ctl.align(EditorController.AlignOp.RIGHT, toStage) }
                    SmallBtn("Распределить") { ctl.align(EditorController.AlignOp.DIST_H, toStage) }
                }
                SectionTitle("По вертикали")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallBtn("⤒ Верх") { ctl.align(EditorController.AlignOp.TOP, toStage) }
                    SmallBtn("↕ Центр") { ctl.align(EditorController.AlignOp.VCENTER, toStage) }
                    SmallBtn("Низ ⤓") { ctl.align(EditorController.AlignOp.BOTTOM, toStage) }
                    SmallBtn("Распределить") { ctl.align(EditorController.AlignOp.DIST_V, toStage) }
                }
                SectionTitle("Точно (пиксели кадра)")
                val num = KeyboardOptions(keyboardType = KeyboardType.Number)
                Row {
                    OutlinedTextField(xs, { xs = it.filter { c -> c.isDigit() || c == '-' } }, label = { Text("X") }, singleLine = true, keyboardOptions = num, modifier = Modifier.width(100.dp))
                    OutlinedTextField(ys, { ys = it.filter { c -> c.isDigit() || c == '-' } }, label = { Text("Y") }, singleLine = true, keyboardOptions = num, modifier = Modifier.width(100.dp).padding(start = 6.dp))
                }
                Row {
                    OutlinedTextField(ws, { ws = it.filter { c -> c.isDigit() } }, label = { Text("Ширина") }, singleLine = true, keyboardOptions = num, modifier = Modifier.width(100.dp))
                    OutlinedTextField(hs, { hs = it.filter { c -> c.isDigit() } }, label = { Text("Высота") }, singleLine = true, keyboardOptions = num, modifier = Modifier.width(100.dp).padding(start = 6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Сохранять пропорции", Modifier.weight(1f), fontSize = 13.sp); Switch(keep, { keep = it })
                }
                SmallBtn("Применить положение и размер") {
                    ctl.setSelectionRect(xs.toFloatOrNull() ?: b.left, ys.toFloatOrNull() ?: b.top, (ws.toFloatOrNull() ?: b.width()).coerceAtLeast(1f), (hs.toFloatOrNull() ?: b.height()).coerceAtLeast(1f), keep)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedTextField(rot, { rot = it.filter { c -> c.isDigit() || c == '-' || c == '.' } }, label = { Text("Поворот, °") }, singleLine = true, keyboardOptions = num, modifier = Modifier.width(120.dp))
                    SmallBtn("Повернуть") { rot.toFloatOrNull()?.let { ctl.rotateSelection(it) } }
                }
            }
        }
    )
}

/** Сцены: отдельные таймлайны, которые при экспорте идут подряд. */
@Composable
fun ScenesDialog(ctl: EditorController, onDismiss: () -> Unit) {
    ctl.version
    val names = ctl.sceneNames()
    val cur = ctl.project.currentScene.coerceIn(0, (names.size - 1).coerceAtLeast(0))
    var name by remember(cur) { mutableStateOf(names.getOrElse(cur) { "Сцена 1" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сцены") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Сцена — отдельный таймлайн со своими слоями. В экспорте можно склеить все сцены подряд.", fontSize = 12.sp, color = Pal.dim)
                names.forEachIndexed { i, n ->
                    Text(
                        "${i + 1}. $n" + if (i == cur) "  ← текущая" else "",
                        fontSize = 14.sp, fontWeight = if (i == cur) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (i == cur) Pal.accent.copy(alpha = 0.25f) else Pal.panel2)
                            .clickable { ctl.switchScene(i) }.padding(10.dp)
                    )
                }
                OutlinedTextField(name, { name = it; ctl.renameScene(it) }, label = { Text("Имя текущей сцены") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(Modifier.padding(top = 6.dp)) {
                    SmallBtn("+ Сцена") { ctl.addScene() }
                    SmallBtn("Копия") { ctl.duplicateScene() }
                    SmallBtn("▲") { ctl.moveScene(-1) }
                    SmallBtn("▼") { ctl.moveScene(1) }
                }
                if (names.size > 1) SmallBtn("Удалить текущую") { ctl.deleteScene() }
            }
        }
    )
}

/** Импорт: что можно принести в проект. */
@Composable
fun ImportDialog(onSvg: () -> Unit, onPsd: () -> Unit, onVideo: () -> Unit, onImage: () -> Unit, onAudio: () -> Unit, onBrushes: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Импорт") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        text = {
            Column {
                ImportLine("SVG — векторная графика", "Контуры, фигуры, заливки и линии становятся редактируемыми элементами", onSvg)
                ImportLine("PSD — файл Photoshop", "Каждый слой (8 бит, RGB) — отдельный слой с прозрачностью", onPsd)
                ImportLine("Видео для ротоскопии", "До 240 кадров на заблокированный направляющий слой", onVideo)
                ImportLine("Изображение-референс", "PNG, JPG и др. на направляющий слой", onImage)
                ImportLine("Звук", "mp3, m4a, ogg, wav… на звуковой слой", onAudio)
                ImportLine("Кисти Krita", ".kpp, .bundle, .gbr, .gih, .png", onBrushes)
            }
        }
    )
}

@Composable
private fun ImportLine(title: String, desc: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(Pal.panel2).clickable(onClick = onClick).padding(10.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(desc, fontSize = 11.sp, color = Pal.dim)
    }
}
