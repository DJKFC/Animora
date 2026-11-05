package com.animora.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Pal {
    val bg = Color(0xFF14121F)
    val panel = Color(0xFF1E1B2E)
    val panel2 = Color(0xFF29253D)
    val line = Color(0xFF3A3552)
    val accent = Color(0xFF8B7CFF)
    val accent2 = Color(0xFFFFC857)
    val pink = Color(0xFFFF6B9A)
    val text = Color(0xFFECEAF6)
    val dim = Color(0xFF9C97B5)
    val motion = Color(0xFF8B7CFF)
    val shape = Color(0xFF3DDC97)
}

@Composable
fun AnimoraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.accent, secondary = Pal.accent2, tertiary = Pal.pink,
            background = Pal.bg, surface = Pal.panel, surfaceVariant = Pal.panel2,
            onPrimary = Color.White, onSurface = Pal.text, onBackground = Pal.text, outline = Pal.line
        ),
        content = content
    )
}

fun Int.toCompose() = Color(this)

/** Вертикальный ползунок (для размера/прозрачности кисти). */
@Composable
fun VerticalSlider(value: Float, onChange: (Float) -> Unit, label: String, modifier: Modifier = Modifier, color: Color = Pal.accent) {
    val cb by rememberUpdatedState(onChange)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(30.dp).height(150.dp).clip(RoundedCornerShape(15.dp)).background(Pal.panel.copy(alpha = 0.92f))
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { o -> cb(1f - (o.y / size.height).coerceIn(0f, 1f)) }) { ch, _ ->
                        cb(1f - (ch.position.y / size.height).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) { detectTapGestures { o -> cb(1f - (o.y / size.height).coerceIn(0f, 1f)) } }
        ) {
            Canvas(Modifier.matchParentSizeCompat()) {
                val h = size.height * value.coerceIn(0f, 1f)
                drawRoundRect(color.copy(alpha = 0.55f), topLeft = Offset(0f, size.height - h), size = Size(size.width, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2))
                drawCircle(Color.White, radius = size.width * 0.32f, center = Offset(size.width / 2, (size.height - h).coerceIn(size.width / 2, size.height - size.width / 2)))
            }
        }
        Text(label, fontSize = 10.sp, color = Pal.dim, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun Modifier.matchParentSizeCompat() = this.then(Modifier.fillMaxWidth().height(150.dp))

@Composable
fun ColorDot(color: Int, size: Int = 28, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) Pal.accent2 else Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onClick)
    )
}

@Composable
fun SectionTitle(t: String) {
    Text(t, color = Pal.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

/** HSV-пикер с альфой, HEX, палитрой проекта и недавними цветами. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(
    initial: Int,
    palette: List<Int>,
    recent: List<Int>,
    onAddToPalette: (Int) -> Unit,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    val hsv0 = FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) }
    var h by remember { mutableFloatStateOf(hsv0[0]) }
    var s by remember { mutableFloatStateOf(hsv0[1]) }
    var v by remember { mutableFloatStateOf(hsv0[2]) }
    var a by remember { mutableFloatStateOf(android.graphics.Color.alpha(initial) / 255f) }
    fun current(): Int = android.graphics.Color.HSVToColor((a * 255).toInt(), floatArrayOf(h, s, v))
    var hex by remember { mutableStateOf(String.format("%08X", initial)) }
    fun setFrom(c: Int) {
        val t = FloatArray(3); android.graphics.Color.colorToHSV(c, t)
        h = t[0]; s = t[1]; v = t[2]; a = android.graphics.Color.alpha(c) / 255f
        hex = String.format("%08X", c)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(current()) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text("Цвет") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(200.dp).clip(RoundedCornerShape(8.dp))
                            .pointerInput(Unit) {
                                fun upd(o: Offset) { s = (o.x / size.width).coerceIn(0f, 1f); v = 1f - (o.y / size.height).coerceIn(0f, 1f); hex = String.format("%08X", current()) }
                                detectDragGestures(onDragStart = { upd(it) }) { c, _ -> upd(c.position) }
                            }
                            .pointerInput(Unit) { detectTapGestures { o -> s = (o.x / size.width).coerceIn(0f, 1f); v = 1f - (o.y / size.height).coerceIn(0f, 1f); hex = String.format("%08X", current()) } }
                    ) {
                        Canvas(Modifier.size(200.dp)) {
                            val hue = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f)))
                            drawRect(Brush.horizontalGradient(listOf(Color.White, hue)))
                            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                            drawCircle(Color.White, 9.dp.toPx(), Offset(s * size.width, (1 - v) * size.height), style = Stroke(2.dp.toPx()))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    // полоса тона
                    Box(
                        Modifier.width(26.dp).height(200.dp).clip(RoundedCornerShape(8.dp))
                            .pointerInput(Unit) {
                                detectDragGestures(onDragStart = { h = (it.y / size.height).coerceIn(0f, 1f) * 360f }) { c, _ -> h = (c.position.y / size.height).coerceIn(0f, 1f) * 360f; hex = String.format("%08X", current()) }
                            }
                            .pointerInput(Unit) { detectTapGestures { o -> h = (o.y / size.height).coerceIn(0f, 1f) * 360f; hex = String.format("%08X", current()) } }
                    ) {
                        Canvas(Modifier.width(26.dp).height(200.dp)) {
                            drawRect(Brush.verticalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
                            val y = h / 360f * size.height
                            drawRect(Color.White, Offset(0f, y - 2.dp.toPx()), Size(size.width, 4.dp.toPx()), style = Stroke(2.dp.toPx()))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Непрозрачность ${(a * 100).toInt()}%", fontSize = 12.sp, color = Pal.dim)
                Box(
                    Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp))
                        .pointerInput(Unit) { detectDragGestures(onDragStart = { a = (it.x / size.width).coerceIn(0f, 1f) }) { c, _ -> a = (c.position.x / size.width).coerceIn(0f, 1f) } }
                        .pointerInput(Unit) { detectTapGestures { a = (it.x / size.width).coerceIn(0f, 1f) } }
                ) {
                    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                        val c = Color(current())
                        drawRect(Brush.horizontalGradient(listOf(c.copy(alpha = 0f), c.copy(alpha = 1f))))
                        drawCircle(Color.White, size.height / 2.4f, Offset(a * size.width, size.height / 2), style = Stroke(2.dp.toPx()))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(initial)))
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(current())))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = hex, onValueChange = { t ->
                            hex = t.uppercase().take(8)
                            val clean = hex.removePrefix("#")
                            runCatching {
                                val c = when (clean.length) { 6 -> (0xFF000000 or clean.toLong(16)).toInt(); 8 -> clean.toLong(16).toInt(); else -> null }
                                if (c != null) { val t2 = FloatArray(3); android.graphics.Color.colorToHSV(c, t2); h = t2[0]; s = t2[1]; v = t2[2]; a = android.graphics.Color.alpha(c) / 255f }
                            }
                        }, label = { Text("HEX (AARRGGBB)") }, singleLine = true, modifier = Modifier.width(170.dp)
                    )
                    TextButton(onClick = { onAddToPalette(current()) }) { Text("+ в палитру") }
                }
                SectionTitle("Палитра проекта")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    palette.forEach { c -> ColorDot(c, 26) { setFrom(c) } }
                }
                if (recent.isNotEmpty()) {
                    SectionTitle("Недавние")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { recent.forEach { c -> ColorDot(c, 24) { setFrom(c) } } }
                }
            }
        }
    )
}
