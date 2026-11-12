package com.animora.studio.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animora.studio.engine.FrameOptions
import com.animora.studio.engine.Renderer
import com.animora.studio.model.BgType
import com.animora.studio.model.BitmapElement
import com.animora.studio.model.Bone
import com.animora.studio.model.BrushKind
import com.animora.studio.model.Easing
import com.animora.studio.model.Keyframe
import com.animora.studio.model.Layer
import com.animora.studio.model.LayerType
import com.animora.studio.model.Project
import com.animora.studio.model.StrokeElement
import com.animora.studio.model.Transform
import com.animora.studio.model.TweenType
import com.animora.studio.ui.ColorDot
import com.animora.studio.ui.ColorPickerDialog
import com.animora.studio.ui.Pal
import com.animora.studio.ui.SectionTitle
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class Preset(val group: String, val name: String, val w: Int, val h: Int)

val PRESETS = listOf(
    Preset("Видео", "HD 720p", 1280, 720),
    Preset("Видео", "Full HD 1080p", 1920, 1080),
    Preset("Видео", "2K QHD", 2560, 1440),
    Preset("Видео", "4K UHD", 3840, 2160),
    Preset("Соцсети", "Reels / TikTok / Shorts", 1080, 1920),
    Preset("Соцсети", "Квадрат", 1080, 1080),
    Preset("Соцсети", "Пост 4:5", 1080, 1350),
    Preset("Другое", "Стикер / эмодзи", 512, 512),
    Preset("Другое", "GIF / аватар", 480, 480),
    Preset("Другое", "Раскадровка 16:9", 1600, 900),
    Preset("Другое", "Пиксель-арт", 320, 180),
    Preset("Другое", "Кино 2.39:1", 2048, 858)
)

enum class Template(val title: String, val desc: String) {
    FRAME_BY_FRAME("Покадровая", "Слои «Фон», «Цвет», «Контур» и направляющий «Набросок»"),
    CUTOUT("Перекладка + кости", "Готовый скелет персонажа и слои, привязанные к костям"),
    TWEEN("Анимация движения", "Пример: мяч с твином движения и отскоком"),
    EMPTY("Пустой", "Один слой рисования")
}

private val BG_SWATCHES = listOf(0xFFFFFFFF, 0xFFF6F1E7, 0xFFFFF8DC, 0xFFE8F4FF, 0xFFDDEBD8, 0xFF2B2B30, 0xFF000000, 0xFF0F1B3D).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewProjectScreen(existingCount: Int, onBack: () -> Unit, onCreate: (Project) -> Unit) {
    var name by remember { mutableStateOf("Анимация ${existingCount + 1}") }
    var w by remember { mutableStateOf("1920") }
    var h by remember { mutableStateOf("1080") }
    var fps by remember { mutableIntStateOf(24) }
    var seconds by remember { mutableFloatStateOf(3f) }
    var bgType by remember { mutableStateOf(BgType.SOLID) }
    var bg by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    var template by remember { mutableStateOf(Template.FRAME_BY_FRAME) }
    var picker by remember { mutableStateOf(false) }
    val wi = (w.toIntOrNull() ?: 0).coerceIn(0, 8192)
    val hi = (h.toIntOrNull() ?: 0).coerceIn(0, 8192)
    val valid = wi in 16..4096 && hi in 16..4096
    var framesText by remember { mutableStateOf("") }
    val frames = (framesText.toIntOrNull() ?: (seconds * fps).toInt()).coerceIn(1, com.animora.studio.ui.editor.MAX_FRAMES)

    BoxWithConstraints(Modifier.fillMaxSize().background(Pal.bg).systemBarsPadding()) {
        val wide = maxWidth > 760.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                Text("Новый проект", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(
                    enabled = valid, onClick = { onCreate(createProject(name.ifBlank { "Без названия" }, wi, hi, fps, frames, bgType, bg, template)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Pal.accent), modifier = Modifier.height(48.dp)
                ) { Text("Создать", fontSize = 16.sp) }
            }
            val form: @Composable (Modifier) -> Unit = { mod ->
                Column(mod.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Название проекта") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    SectionTitle("Разрешение")
                    PRESETS.groupBy { it.group }.forEach { (g, list) ->
                        Text(g, fontSize = 12.sp, color = Pal.dim, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            list.forEach { p ->
                                val sel = wi == p.w && hi == p.h
                                PresetCard(p, sel) { w = p.w.toString(); h = p.h.toString() }
                            }
                        }
                    }
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(w, { w = it.filter(Char::isDigit).take(4) }, label = { Text("Ширина") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(130.dp))
                        IconButton(onClick = { val t = w; w = h; h = t }) { Icon(Icons.Filled.SwapHoriz, "Поменять местами") }
                        OutlinedTextField(h, { h = it.filter(Char::isDigit).take(4) }, label = { Text("Высота") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(130.dp))
                    }
                    if (!valid) Text("Размер от 16 до 4096 пикселей", color = Pal.pink, fontSize = 12.sp)

                    SectionTitle("Частота кадров")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(8, 12, 15, 24, 25, 30, 60).forEach { f -> Chip("$f fps", fps == f) { fps = f } }
                    }
                    Text(
                        when (fps) { 12 -> "12 fps — классика «на двойках», меньше рисовать"; 24 -> "24 fps — кино"; 60 -> "60 fps — очень плавно, много кадров"; else -> "" },
                        fontSize = 12.sp, color = Pal.dim, modifier = Modifier.padding(top = 4.dp)
                    )

                    SectionTitle("Длительность: ${"%.1f".format(frames / fps.toFloat())} с = $frames кадров")
                    Slider(seconds.coerceIn(0.5f, 60f), { seconds = (it * 2).toInt() / 2f; framesText = "" }, valueRange = 0.5f..60f)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            framesText, { framesText = it.filter(Char::isDigit).take(6) },
                            label = { Text("Точно, кадров") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(150.dp)
                        )
                        listOf(1, 5, 10, 30).forEach { m ->
                            Chip("$m мин", framesText == (m * 60 * fps).toString()) { framesText = (m * 60 * fps).toString() }
                        }
                    }
                    Text("Ползунок — до минуты; длиннее — введите число кадров (до ${com.animora.studio.ui.editor.MAX_FRAMES}).", fontSize = 11.sp, color = Pal.dim)

                    SectionTitle("Фон")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { BgType.entries.forEach { t -> Chip(t.title, bgType == t) { bgType = t } } }
                    if (bgType != BgType.TRANSPARENT) {
                        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BG_SWATCHES.forEach { c -> ColorDot(c, 34, selected = bg == c) { bg = c } }
                            Box(
                                Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(Pal.panel2).clickable { picker = true },
                                contentAlignment = Alignment.Center
                            ) { Text("+", fontSize = 18.sp) }
                        }
                    }

                    SectionTitle("Шаблон")
                    Template.entries.forEach { t ->
                        Surface(
                            shape = RoundedCornerShape(12.dp), color = if (template == t) Pal.accent.copy(alpha = 0.22f) else Pal.panel,
                            border = if (template == t) BorderStroke(1.dp, Pal.accent) else null,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).clickable { template = t }
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(t.title, fontWeight = FontWeight.SemiBold)
                                Text(t.desc, fontSize = 12.sp, color = Pal.dim)
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            val preview: @Composable (Modifier) -> Unit = { mod ->
                Column(mod.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CanvasPreview(wi, hi, bgType, bg, template, Modifier.fillMaxWidth())
                    Text("${wi}×${hi} · $fps fps · $frames кадров", color = Pal.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    val mp = wi.toLong() * hi / 1_000_000.0
                    Text("≈ ${"%.1f".format(mp)} Мп на кадр", color = Pal.dim, fontSize = 12.sp)
                }
            }
            if (wide) Row(Modifier.fillMaxSize()) { form(Modifier.weight(1.3f)); preview(Modifier.weight(1f)) }
            else Column(Modifier.fillMaxSize()) { preview(Modifier.fillMaxWidth().height(220.dp)); form(Modifier.weight(1f)) }
        }
    }
    if (picker) ColorPickerDialog(bg, BG_SWATCHES, emptyList(), {}, { picker = false }) { bg = it; picker = false }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp), color = if (selected) Pal.accent.copy(alpha = 0.3f) else Pal.panel,
        border = BorderStroke(1.dp, if (selected) Pal.accent else Pal.line),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
    ) { Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp) }
}

@Composable
private fun PresetCard(p: Preset, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp), color = if (selected) Pal.accent.copy(alpha = 0.25f) else Pal.panel,
        border = BorderStroke(1.dp, if (selected) Pal.accent else Pal.line),
        modifier = Modifier.widthIn(min = 120.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val s = 26f / max(p.w, p.h)
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size((p.w * s).dp, (p.h * s).dp).border(1.5.dp, if (selected) Pal.accent2 else Pal.dim, RoundedCornerShape(2.dp)))
            }
            Spacer(Modifier.width(6.dp))
            Column {
                Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("${p.w}×${p.h}", fontSize = 11.sp, color = Pal.dim)
            }
        }
    }
}

@Composable
private fun CanvasPreview(w: Int, h: Int, bgType: BgType, bg: Int, template: Template, modifier: Modifier) {
    val ww = max(16, w); val hh = max(16, h)
    val bmp = remember(ww, hh, bgType, bg, template) {
        val p = createProject("preview", ww, hh, 24, 24, bgType, bg, template)
        val s = 480f / max(ww, hh)
        val b = Renderer().renderBitmap(p, 0, s, FrameOptions(includeGuides = true))
        if (bgType == BgType.TRANSPARENT) {
            val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out); val paint = Paint()
            val cell = 12
            for (y in 0 until b.height step cell) for (x in 0 until b.width step cell) {
                paint.color = if ((x / cell + y / cell) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFD5D5D5.toInt()
                c.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), paint)
            }
            c.drawBitmap(b, 0f, 0f, null); out
        } else b
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(
            bmp.asImageBitmap(), "Предпросмотр холста",
            Modifier.aspectRatio(ww.toFloat() / hh, matchHeightConstraintsFirst = hh > ww).clip(RoundedCornerShape(6.dp)).border(1.dp, Pal.line, RoundedCornerShape(6.dp))
        )
    }
}

/** Создание проекта по шаблону. */
fun createProject(name: String, w: Int, h: Int, fps: Int, frames: Int, bgType: BgType, bg: Int, template: Template): Project {
    val p = Project(name = name, width = w, height = h, fps = fps, frameCount = frames, bgColor = bg, bgType = bgType)
    fun drawing(n: String, color: Int, op: Float = 1f, guide: Boolean = false) =
        Layer(name = n, color = color, opacity = op, guide = guide).also { it.keyframes[0] = Keyframe() }
    when (template) {
        Template.EMPTY -> p.layers.add(drawing("Слой 1", 0xFF4FC3F7.toInt()))
        Template.FRAME_BY_FRAME -> {
            p.layers.add(drawing("Фон", 0xFF90A4AE.toInt()))
            p.layers.add(drawing("Цвет", 0xFFFFD54F.toInt()))
            p.layers.add(drawing("Контур", 0xFF4FC3F7.toInt()))
            p.layers.add(drawing("Набросок", 0xFFF06292.toInt(), 0.45f, guide = true))
        }
        Template.TWEEN -> {
            p.layers.add(drawing("Фон", 0xFF90A4AE.toInt()))
            val r = min(w, h) * 0.08f
            val cx = w * 0.18f; val cy = h * 0.25f
            val n = 64
            val pts = FloatArray((n + 1) * 3)
            for (i in 0..n) {
                val a = i / n.toDouble() * Math.PI * 2
                pts[i * 3] = cx + r * cos(a).toFloat(); pts[i * 3 + 1] = cy + r * sin(a).toFloat(); pts[i * 3 + 2] = 1f
            }
            val size = max(2f, r * 0.08f)
            val fillBmp = Bitmap.createBitmap((r * 2).toInt() + 2, (r * 2).toInt() + 2, Bitmap.Config.ARGB_8888)
            Canvas(fillBmp).drawCircle(r + 1, r + 1, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF6B9A.toInt() })
            val fill = BitmapElement(fillBmp, Matrix().apply { setTranslate(cx - r - 1, cy - r - 1) })
            val ball = Layer(name = "Мяч", color = 0xFFFF8A65.toInt())
            ball.keyframes[0] = Keyframe(
                mutableListOf(fill, StrokeElement(pts, 0xFF1E1B2E.toInt(), size, BrushKind.PEN, 1f)),
                tween = TweenType.MOTION, easing = Easing.BOUNCE, transform = Transform(px = cx, py = cy)
            )
            val end = max(1, frames - 1)
            ball.keyframes[end] = Keyframe(
                mutableListOf(fill, StrokeElement(pts, 0xFF1E1B2E.toInt(), size, BrushKind.PEN, 1f)),
                transform = Transform(tx = w * 0.62f, ty = h * 0.55f, rot = 360f, px = cx, py = cy)
            )
            p.layers.add(ball)
        }
        Template.CUTOUT -> {
            p.layers.add(drawing("Фон", 0xFF90A4AE.toInt()))
            val u = h / 100f
            val cx = w / 2f; val hip = h * 0.62f
            val rig = Layer(name = "Скелет", type = LayerType.BONES, color = 0xFFFFC857.toInt())
            fun add(name: String, parent: Bone?, hx: Float, hy: Float, len: Float, ang: Float): Bone =
                Bone(name = name, parentId = parent?.id, hx = hx, hy = hy, length = len, angle = ang).also { rig.bones.add(it) }
            val torso = add("Туловище", null, cx, hip, 22 * u, -90f)
            val neck = add("Шея", torso, torso.tailX, torso.tailY, 4 * u, -90f)
            val head = add("Голова", neck, neck.tailX, neck.tailY, 10 * u, -90f)
            val armL = add("Плечо Л", torso, torso.tailX, torso.tailY, 12 * u, 120f)
            add("Предплечье Л", armL, armL.tailX, armL.tailY, 11 * u, 100f)
            val armR = add("Плечо П", torso, torso.tailX, torso.tailY, 12 * u, 60f)
            add("Предплечье П", armR, armR.tailX, armR.tailY, 11 * u, 80f)
            val legL = add("Бедро Л", torso, cx - 3 * u, hip, 14 * u, 100f)
            add("Голень Л", legL, legL.tailX, legL.tailY, 14 * u, 92f)
            val legR = add("Бедро П", torso, cx + 3 * u, hip, 14 * u, 80f)
            add("Голень П", legR, legR.tailX, legR.tailY, 14 * u, 88f)
            val body = drawing("Тело", 0xFF4FC3F7.toInt()).also { it.bindLayer = rig.id; it.bindBone = torso.id }
            val headL = drawing("Голова", 0xFFF06292.toInt()).also { it.bindLayer = rig.id; it.bindBone = head.id }
            p.layers.add(body); p.layers.add(headL); p.layers.add(rig)
        }
    }
    return p
}
