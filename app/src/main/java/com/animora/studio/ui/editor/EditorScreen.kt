package com.animora.studio.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.animora.studio.model.BrushKind
import com.animora.studio.model.Project
import com.animora.studio.model.ProjectStore
import com.animora.studio.ui.ColorDot
import com.animora.studio.ui.ColorPickerDialog
import com.animora.studio.ui.Pal
import com.animora.studio.ui.TopButton
import com.animora.studio.ui.UiPrefs
import com.animora.studio.ui.VerticalSlider
import kotlinx.coroutines.delay
import kotlin.math.sqrt

enum class Dlg { COLOR, BRUSH, ONION, PROJECT, EXPORT, FRAME_MENU, PLAYBACK, UI, CURVES }

@Composable
fun EditorScreen(project: Project, store: ProjectStore, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ctl = remember(project.id) { EditorController(project, store, scope, context.applicationContext) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP) { ctl.stop(); if (ctl.dirty) ctl.saveAsync() } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { while (true) { delay(60_000); if (ctl.dirty && !ctl.playing) ctl.saveAsync() } }
    val exit = { ctl.stop(); ctl.saveAsync { onExit() } }
    BackHandler { exit() }

    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var showLayers by remember { mutableStateOf(UiPrefs.layersOpenByDefault) }
    var focus by remember { mutableStateOf(false) }
    val msg = ctl.message
    LaunchedEffect(msg) { if (msg != null) { delay(2600); if (ctl.message == msg) ctl.message = null } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { ctl.importImage(it) } }
    val brushPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> ctl.importBrushes(uris) }
    val importBrushes = { brushPicker.launch(arrayOf("*/*")) }

    val timeline: @Composable () -> Unit = { Timeline(ctl, onMore = { dialog = Dlg.FRAME_MENU }, onCurves = { dialog = Dlg.CURVES }) }
    val layers: @Composable () -> Unit = { LayersPanel(ctl, onImport = { picker.launch("image/*") }) }
    val tools: @Composable () -> Unit = { ToolRail(ctl, onColor = { dialog = Dlg.COLOR }, onBrush = { dialog = Dlg.BRUSH }) }

    Column(Modifier.fillMaxSize().background(Pal.bg).systemBarsPadding()) {
        if (!focus) EditorTopBar(
            ctl, onBack = exit, onDialog = { dialog = it }, showLayers = showLayers,
            onToggleLayers = { showLayers = !showLayers }, onFocus = { focus = true }
        )
        if (!focus && UiPrefs.timelineTop) timeline()
        Row(Modifier.weight(1f)) {
            if (!focus && showLayers && UiPrefs.layersLeft) layers()
            if (!focus && !UiPrefs.toolsRight) tools()
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                AndroidView(
                    factory = { DrawingView(it, ctl) },
                    modifier = Modifier.fillMaxSize(),
                    update = { v -> ctl.renderKey(); ctl.overlayKey(); v.sync() }
                )
                if (UiPrefs.showSliders && ctl.tool in setOf(Tool.BRUSH, Tool.ERASER, Tool.LINE, Tool.RECT, Tool.ELLIPSE)) {
                    BrushSliders(
                        ctl,
                        if (UiPrefs.slidersRight) Modifier.align(Alignment.CenterEnd).padding(end = 8.dp) else Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                    )
                }
                if (!focus) ContextBar(
                    ctl, Modifier.align(if (UiPrefs.contextBarBottom) Alignment.BottomCenter else Alignment.TopCenter).padding(6.dp),
                    onImportBrushes = importBrushes, onBrushDialog = { dialog = Dlg.BRUSH }
                )
                if (focus) Surface(
                    shape = RoundedCornerShape(20.dp), color = Pal.panel.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Icons.Filled.FullscreenExit, "Выйти из фокуса") { focus = false }
                        IconBtn(Icons.AutoMirrored.Filled.Undo, "Отменить") { ctl.undo() }
                        IconBtn(if (ctl.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Воспроизвести", tint = Pal.accent2) { ctl.togglePlay() }
                    }
                }
                Column(
                    Modifier.align(if (UiPrefs.contextBarBottom) Alignment.TopCenter else Alignment.BottomCenter).padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ctl.busy?.let {
                        Surface(shape = RoundedCornerShape(20.dp), color = Pal.panel2) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(it, fontSize = 13.sp)
                            }
                        }
                    }
                    msg?.let {
                        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xEE29253D), modifier = Modifier.padding(top = 6.dp)) {
                            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), fontSize = 13.sp, color = Pal.text)
                        }
                    }
                }
                Text(
                    "${ctl.frame + 1} / ${ctl.project.frameCount}".also { ctl.version },
                    Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    color = Color.White, fontSize = 12.sp
                )
            }
            if (!focus && UiPrefs.toolsRight) tools()
            if (!focus && showLayers && !UiPrefs.layersLeft) layers()
        }
        if (!focus && !UiPrefs.timelineTop) timeline()
    }

    when (dialog) {
        Dlg.COLOR -> ColorPickerDialog(
            initial = ctl.color, palette = ctl.project.palette.toList(), recent = ctl.recentColors.toList(),
            onAddToPalette = { c -> if (c !in ctl.project.palette) { ctl.project.palette.add(c); ctl.changed() } },
            onDismiss = { dialog = null }, onPick = { ctl.color = it; ctl.pushRecentColor(it); dialog = null }
        )
        Dlg.BRUSH -> BrushDialog(ctl, onImport = importBrushes) { dialog = null }
        Dlg.ONION -> OnionDialog(ctl) { dialog = null }
        Dlg.PROJECT -> ProjectDialog(ctl) { dialog = null }
        Dlg.EXPORT -> ExportDialog(ctl) { dialog = null }
        Dlg.FRAME_MENU -> FrameMenuDialog(ctl) { dialog = null }
        Dlg.PLAYBACK -> PlaybackDialog(ctl) { dialog = null }
        Dlg.UI -> UiSettingsDialog { dialog = null }
        Dlg.CURVES -> CurveEditorDialog(ctl) { dialog = null }
        null -> {}
    }
}

@Composable
fun SmallBtn(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Pal.accent.copy(alpha = 0.35f) else Pal.panel2,
        modifier = Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
            .then(if (selected) Modifier.border(1.dp, Pal.accent, RoundedCornerShape(8.dp)) else Modifier)
    ) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 12.sp, color = if (enabled) Pal.text else Pal.dim, maxLines = 1)
    }
}

@Composable
fun IconBtn(icon: ImageVector, desc: String, selected: Boolean = false, tint: Color = Pal.text, modifier: Modifier = Modifier, size: Int = 42, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) Pal.accent.copy(alpha = 0.35f) else Color.Transparent)
    ) { Icon(icon, desc, tint = if (selected) Color.White else tint) }
}

@Composable
private fun EditorTopBar(ctl: EditorController, onBack: () -> Unit, onDialog: (Dlg) -> Unit, showLayers: Boolean, onToggleLayers: () -> Unit, onFocus: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(50.dp).background(Pal.panel).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onClick = onBack)
        Column(Modifier.weight(1f).padding(horizontal = 6.dp).clickable { onDialog(Dlg.PROJECT) }) {
            Text(ctl.project.name.also { ctl.version }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Pal.text)
            Text("${ctl.project.width}×${ctl.project.height} · ${ctl.project.fps} fps", fontSize = 11.sp, color = Pal.dim, maxLines = 1)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (UiPrefs.visible(TopButton.UNDO)) {
                IconBtn(Icons.AutoMirrored.Filled.Undo, "Отменить") { ctl.undo() }
                IconBtn(Icons.AutoMirrored.Filled.Redo, "Повторить") { ctl.redo() }
                Spacer(Modifier.width(6.dp))
            }
            if (UiPrefs.visible(TopButton.PLAY)) IconBtn(if (ctl.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Воспроизвести", selected = ctl.playing, tint = Pal.accent2) { ctl.togglePlay() }
            if (UiPrefs.visible(TopButton.PLAY_MODE)) SmallBtn(ctl.playMode.title) { onDialog(Dlg.PLAYBACK) }
            if (UiPrefs.visible(TopButton.ONION)) IconBtn(Icons.Filled.FilterNone, "Луковая кожа", selected = ctl.onionEnabled) { ctl.onionEnabled = !ctl.onionEnabled }
            if (UiPrefs.visible(TopButton.ONION_SETTINGS)) IconBtn(Icons.Filled.Tune, "Настройки кальки") { onDialog(Dlg.ONION) }
            if (UiPrefs.visible(TopButton.LAYERS)) IconBtn(Icons.Filled.Layers, "Слои", selected = showLayers, onClick = onToggleLayers)
            if (UiPrefs.visible(TopButton.FOCUS)) IconBtn(Icons.Filled.Fullscreen, "Режим фокуса", onClick = onFocus)
            if (UiPrefs.visible(TopButton.EXPORT)) IconBtn(Icons.Filled.IosShare, "Экспорт", tint = Pal.accent2) { onDialog(Dlg.EXPORT) }
            Box {
                IconBtn(Icons.Filled.MoreVert, "Ещё") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    CheckItem("Сетка и третьи", ctl.showGrid) { ctl.showGrid = !ctl.showGrid }
                    CheckItem("Безопасные зоны", ctl.showSafeArea) { ctl.showSafeArea = !ctl.showSafeArea }
                    CheckItem("Зеркальный вид холста", ctl.flipView) { ctl.flipView = !ctl.flipView }
                    CheckItem("Рисовать только стилусом", ctl.stylusOnly) { ctl.stylusOnly = !ctl.stylusOnly }
                    CheckItem("Показывать кости", ctl.showBones) { ctl.showBones = !ctl.showBones }
                    DropdownMenuItem(text = { Text("Симметрия: ${ctl.symmetry.title}") }, onClick = {
                        ctl.symmetry = Symmetry.entries[(ctl.symmetry.ordinal + 1) % Symmetry.entries.size]
                    })
                    DropdownMenuItem(text = { Text("Рисование на пустом кадре: ${ctl.autoKey.title}") }, onClick = {
                        ctl.autoKey = AutoKeyMode.entries[(ctl.autoKey.ordinal + 1) % AutoKeyMode.entries.size]
                    })
                    DropdownMenuItem(text = { Text("Сбросить вид") }, onClick = { ctl.viewResetToken++; menu = false })
                    DropdownMenuItem(text = { Text("Настроить интерфейс…") }, onClick = { menu = false; onDialog(Dlg.UI) })
                    DropdownMenuItem(text = { Text("Кривые анимации…") }, onClick = { menu = false; onDialog(Dlg.CURVES) })
                    if (!showLayers || !UiPrefs.visible(TopButton.LAYERS)) CheckItem("Панель слоёв", showLayers) { onToggleLayers() }
                    if (!UiPrefs.visible(TopButton.EXPORT)) DropdownMenuItem(text = { Text("Экспорт…") }, onClick = { menu = false; onDialog(Dlg.EXPORT) })
                    DropdownMenuItem(text = { Text("Параметры проекта…") }, onClick = { menu = false; onDialog(Dlg.PROJECT) })
                    DropdownMenuItem(text = { Text("Сохранить сейчас") }, onClick = { menu = false; ctl.saveAsync { ctl.toast("Сохранено") } })
                }
            }
        }
    }
}

@Composable
fun CheckItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text((if (checked) "✓  " else "     ") + text) }, onClick = onClick)
}

fun toolIcon(t: Tool): ImageVector = when (t) {
    Tool.BRUSH -> Icons.Filled.Brush
    Tool.ERASER -> Icons.Filled.CleaningServices
    Tool.FILL -> Icons.Filled.FormatColorFill
    Tool.EYEDROPPER -> Icons.Filled.Colorize
    Tool.LINE -> Icons.Filled.HorizontalRule
    Tool.RECT -> Icons.Filled.CropSquare
    Tool.ELLIPSE -> Icons.Filled.RadioButtonUnchecked
    Tool.LASSO -> Icons.Filled.Gesture
    Tool.TRANSFORM -> Icons.Filled.OpenWith
    Tool.BONE -> Icons.Filled.AccountTree
    Tool.POSE -> Icons.Filled.AccessibilityNew
    Tool.HAND -> Icons.Filled.PanTool
}

@Composable
private fun ToolRail(ctl: EditorController, onColor: () -> Unit, onBrush: () -> Unit) {
    val btn = UiPrefs.toolSize
    Column(
        Modifier.width((btn + 12).dp).fillMaxHeight().background(Pal.panel).verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        UiPrefs.tools.forEach { t ->
            if (t == Tool.BONE) Box(Modifier.width((btn - 12).dp).height(1.dp).background(Pal.line))
            IconBtn(toolIcon(t), t.title, selected = ctl.tool == t, size = btn) {
                if (ctl.tool == t && (t == Tool.BRUSH || t == Tool.ERASER)) onBrush()
                ctl.tool = t
                if (t != Tool.LASSO) ctl.selection = null
            }
            if (UiPrefs.showToolLabels) Text(t.title.substringBefore(' '), fontSize = 8.sp, color = Pal.dim, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        ColorDot(ctl.color, 36, onClick = onColor)
        Spacer(Modifier.height(6.dp))
        ctl.recentColors.take(5).forEach { c ->
            Box(Modifier.padding(2.dp)) { ColorDot(c, 22) { ctl.color = c } }
        }
    }
}

@Composable
private fun BrushSliders(ctl: EditorController, modifier: Modifier) {
    val kind = if (ctl.tool == Tool.ERASER) BrushKind.ERASER else ctl.brushKind
    val b = ctl.settingsFor(kind)
    // высота ползунков подстраивается под свободное место (низкие экраны телефонов)
    BoxWithConstraints(modifier.fillMaxHeight(), contentAlignment = if (UiPrefs.slidersRight) Alignment.CenterEnd else Alignment.CenterStart) {
        val bar = ((maxHeight - 100.dp) / 2).coerceIn(36.dp, 150.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VerticalSlider(sqrt(((b.size - 1f) / 299f).coerceIn(0f, 1f)), { v -> ctl.updateBrush(kind) { it.copy(size = 1f + 299f * v * v) } }, "${b.size.toInt()}px", barHeight = bar)
            VerticalSlider(b.opacity, { v -> ctl.updateBrush(kind) { it.copy(opacity = v.coerceAtLeast(0.02f)) } }, "${(b.opacity * 100).toInt()}%", color = Pal.accent2, barHeight = bar)
        }
    }
}

@Composable
private fun ContextBar(ctl: EditorController, modifier: Modifier, onImportBrushes: () -> Unit, onBrushDialog: () -> Unit) {
    ctl.version
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Pal.panel.copy(alpha = UiPrefs.panelAlpha), shadowElevation = 6.dp) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (ctl.tool) {
                Tool.BRUSH -> {
                    BrushKind.entries.filter { it != BrushKind.ERASER && it != BrushKind.CUSTOM }.forEach { k ->
                        SmallBtn(k.title, selected = ctl.brushKind == k) { ctl.brushKind = k }
                    }
                    ctl.customBrushes.take(8).forEach { b ->
                        SmallBtn("✦ " + b.name.take(18), selected = ctl.brushKind == BrushKind.CUSTOM && ctl.customBrushId == b.id) { ctl.selectCustomBrush(b.id) }
                    }
                    if (ctl.customBrushes.size > 8) SmallBtn("Все кисти (${ctl.customBrushes.size})…", onClick = onBrushDialog)
                    SmallBtn("+ Кисти Krita", onClick = onImportBrushes)
                    SmallBtn("Стаб. ${(ctl.brush.smoothing * 100).toInt()}%") {
                        val s = ctl.brush.smoothing
                        ctl.updateBrush(ctl.brushKind) { it.copy(smoothing = if (s >= 0.85f) 0f else s + 0.15f) }
                    }
                    SmallBtn("Симметрия: ${ctl.symmetry.title}", selected = ctl.symmetry != Symmetry.NONE) {
                        ctl.symmetry = Symmetry.entries[(ctl.symmetry.ordinal + 1) % Symmetry.entries.size]
                    }
                }
                Tool.ERASER -> {
                    SmallBtn("Пиксельный", selected = !ctl.strokeEraser) { ctl.strokeEraser = false }
                    SmallBtn("Штриховой (целиком)", selected = ctl.strokeEraser) { ctl.strokeEraser = true }
                    SmallBtn("Очистить ключ") { ctl.clearKeyContent() }
                }
                Tool.FILL -> {
                    Text("Допуск ${ctl.fillTolerance}", fontSize = 12.sp, color = Pal.dim)
                    Slider(ctl.fillTolerance.toFloat(), { ctl.fillTolerance = it.toInt() }, valueRange = 0f..200f, modifier = Modifier.width(110.dp))
                    Text("Зазоры ${ctl.fillGap}px", fontSize = 12.sp, color = Pal.dim)
                    Slider(ctl.fillGap.toFloat(), { ctl.fillGap = it.toInt() }, valueRange = 0f..12f, modifier = Modifier.width(100.dp))
                    SmallBtn("Под линиями", selected = ctl.fillBehind) { ctl.fillBehind = !ctl.fillBehind }
                    SmallBtn("По всем слоям", selected = ctl.fillAllLayers) { ctl.fillAllLayers = !ctl.fillAllLayers }
                }
                Tool.LINE, Tool.RECT, Tool.ELLIPSE -> {
                    SmallBtn("С заливкой", selected = ctl.shapeFilled) { ctl.shapeFilled = !ctl.shapeFilled }
                    Text("  Кисть: ${ctl.brushKind.title}", fontSize = 12.sp, color = Pal.dim)
                }
                Tool.LASSO -> {
                    if (ctl.selection != null) {
                        IconBtn(Icons.Filled.Delete, "Удалить") { ctl.deleteSelection() }
                        IconBtn(Icons.Filled.ContentCut, "Вырезать") { ctl.copySelection(cut = true) }
                        IconBtn(Icons.Filled.ContentCopy, "Копировать") { ctl.copySelection() }
                        IconBtn(Icons.Filled.ContentPaste, "Вставить") { ctl.paste() }
                        SmallBtn("Дубль") { ctl.duplicateSelection() }
                        IconBtn(Icons.Filled.Flip, "Отразить горизонтально") { ctl.flipSelection(true) }
                        IconBtn(Icons.Filled.Flip, "Отразить вертикально", modifier = Modifier.rotate(90f)) { ctl.flipSelection(false) }
                        IconBtn(Icons.Filled.FormatColorFill, "Перекрасить") { ctl.recolorSelection() }
                        IconBtn(Icons.Filled.FlipToFront, "Наверх") { ctl.arrangeSelection(true) }
                        IconBtn(Icons.Filled.FlipToBack, "Вниз") { ctl.arrangeSelection(false) }
                        IconBtn(Icons.Filled.Close, "Снять выделение") { ctl.selection = null }
                    } else {
                        Text("Обведите штрихи  ", fontSize = 12.sp, color = Pal.dim)
                        SmallBtn("Выделить всё") { ctl.selectAll() }
                        SmallBtn("Вставить") { ctl.paste() }
                    }
                }
                Tool.TRANSFORM -> {
                    val t = ctl.currentTransform()
                    if (t == null) Text("Нет ключа на слое", fontSize = 12.sp, color = Pal.dim) else {
                        Text("Поворот ${t.rot.toInt()}°", fontSize = 12.sp, color = Pal.dim)
                        Slider(t.rot.coerceIn(-360f, 360f), { v -> ctl.editKeyTransform("rot") { it.copy(rot = v) } }, valueRange = -360f..360f, modifier = Modifier.width(110.dp))
                        Text("Масштаб ${(t.sx * 100).toInt()}%", fontSize = 12.sp, color = Pal.dim)
                        Slider(t.sx.coerceIn(0.05f, 4f), { v -> ctl.editKeyTransform("scale") { it.copy(sx = v, sy = v) } }, valueRange = 0.05f..4f, modifier = Modifier.width(110.dp))
                        Text("Альфа ${(t.alpha * 100).toInt()}%", fontSize = 12.sp, color = Pal.dim)
                        Slider(t.alpha, { v -> ctl.editKeyTransform("alpha") { it.copy(alpha = v) } }, modifier = Modifier.width(90.dp))
                        SmallBtn("Сброс") { ctl.editKeyTransform("reset${System.nanoTime()}") { it.copy(tx = 0f, ty = 0f, rot = 0f, sx = 1f, sy = 1f, alpha = 1f) } }
                    }
                }
                Tool.BONE -> {
                    Text("Тяните — новая кость; от конца кости — дочерняя  ", fontSize = 12.sp, color = Pal.dim)
                    if (ctl.selectedBoneId != null) SmallBtn("Удалить кость") { ctl.deleteSelectedBone() }
                    SmallBtn("К позированию") { ctl.tool = Tool.POSE }
                }
                Tool.POSE -> {
                    SmallBtn("IK (тянуть цепочку)", selected = ctl.ikMode) { ctl.ikMode = !ctl.ikMode }
                    SmallBtn("+ Ключ позы") { ctl.insertPoseKey() }
                    SmallBtn("Сброс позы") { ctl.resetPose() }
                    Text("  Корень тяните за жёлтую точку", fontSize = 12.sp, color = Pal.dim)
                }
                Tool.EYEDROPPER -> Text("Коснитесь цвета на холсте (кнопка 2 стилуса — тоже пипетка)", fontSize = 12.sp, color = Pal.dim)
                Tool.HAND -> Text("Сдвиг одним пальцем · масштаб и поворот двумя", fontSize = 12.sp, color = Pal.dim)
            }
        }
    }
}

@Suppress("unused")
private val dotShape = CircleShape
