package com.animora.studio.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animora.studio.model.BlendMode
import com.animora.studio.model.Layer
import com.animora.studio.model.LayerType
import com.animora.studio.ui.Pal
import com.animora.studio.ui.SectionTitle

@Composable
fun LayersPanel(ctl: EditorController, onImport: () -> Unit) {
    ctl.version
    val p = ctl.project
    Column(Modifier.width(com.animora.studio.ui.UiPrefs.layersWidth.dp).fillMaxHeight().background(Pal.panel).padding(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Слои", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
            IconBtn(Icons.Filled.Add, "Слой рисования") { ctl.addLayer(LayerType.DRAWING) }
            IconBtn(Icons.Filled.AccountTree, "Слой костей", tint = Pal.accent2) { ctl.addLayer(LayerType.BONES) }
            IconBtn(Icons.Filled.Image, "Импорт изображения", onClick = onImport)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            p.layers.reversed().forEach { l -> LayerRow(ctl, l) }
        }
        HorizontalDivider(color = Pal.line)
        ctl.activeLayer?.let { LayerProps(ctl, it) }
    }
}

@Composable
private fun LayerRow(ctl: EditorController, l: Layer) {
    ctl.version
    val active = l.id == ctl.activeLayerId
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) Pal.accent.copy(alpha = 0.25f) else Pal.panel2)
            .then(if (active) Modifier.border(1.dp, Pal.accent, RoundedCornerShape(8.dp)) else Modifier)
            .clickable { ctl.selectLayer(l.id) }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).height(24.dp).background(Color(l.color), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        if (l.type == LayerType.BONES) Icon(Icons.Filled.AccountTree, null, Modifier.size(16.dp), tint = Pal.accent2)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(l.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildList {
                if (l.type == LayerType.BONES) add("${l.bones.size} кост.") else add("${l.keyframes.size} ключ.")
                if (l.guide) add("направляющий")
                if (l.bindBone != null) add("на кости")
                if (l.opacity < 1f) add("${(l.opacity * 100).toInt()}%")
            }.joinToString(" · ")
            Text(sub, fontSize = 10.sp, color = Pal.dim, maxLines = 1)
        }
        Icon(
            if (l.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, "Видимость",
            Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).clickable { ctl.updateLayer(l.id) { it.visible = !it.visible } }.padding(6.dp),
            tint = if (l.visible) Pal.text else Pal.pink
        )
        Icon(
            if (l.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, "Блокировка",
            Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).clickable { ctl.updateLayer(l.id) { it.locked = !it.locked } }.padding(6.dp),
            tint = if (l.locked) Pal.accent2 else Pal.dim
        )
    }
}

@Composable
private fun LayerProps(ctl: EditorController, l: Layer) {
    ctl.version
    var blendMenu by remember { mutableStateOf(false) }
    var bindMenu by remember { mutableStateOf(false) }
    var name by remember(l.id) { mutableStateOf(l.name) }
    Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { v -> name = v; ctl.updateLayer(l.id, "name") { it.name = v } },
            label = { Text("Имя слоя") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        if (l.type == LayerType.DRAWING) {
            SectionTitle("Непрозрачность ${(l.opacity * 100).toInt()}%")
            Slider(l.opacity, { v -> ctl.updateLayer(l.id, "opacity") { it.opacity = v } })
            Box {
                SmallBtn("Наложение: ${l.blend.title}") { blendMenu = true }
                DropdownMenu(blendMenu, { blendMenu = false }) {
                    BlendMode.entries.forEach { b -> DropdownMenuItem(text = { Text(b.title) }, onClick = { ctl.updateLayer(l.id) { it.blend = b }; blendMenu = false }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("Направляющий (не в экспорт)", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(l.guide, { v -> ctl.updateLayer(l.id) { it.guide = v } })
            }
            val boneLayers = ctl.project.layers.filter { it.type == LayerType.BONES && it.bones.isNotEmpty() }
            Box {
                val boundName = ctl.project.layer(l.bindLayer)?.bones?.firstOrNull { it.id == l.bindBone }?.name
                SmallBtn("Кость: ${boundName ?: "нет"}", enabled = boneLayers.isNotEmpty()) { bindMenu = true }
                DropdownMenu(bindMenu, { bindMenu = false }) {
                    DropdownMenuItem(text = { Text("Не привязан") }, onClick = { ctl.bindLayer(l, null, null); bindMenu = false })
                    boneLayers.forEach { bl ->
                        bl.bones.forEach { b ->
                            DropdownMenuItem(text = { Text("${bl.name} › ${b.name}") }, onClick = { ctl.bindLayer(l, bl, b.id); bindMenu = false })
                        }
                    }
                }
            }
        } else {
            SectionTitle("Кости")
            l.bones.forEach { b ->
                val sel = b.id == ctl.selectedBoneId
                Text(
                    (if (b.parentId == null) "◆ " else "   └ ") + b.name, fontSize = 12.sp,
                    color = if (sel) Pal.accent2 else Pal.text,
                    modifier = Modifier.fillMaxWidth().clickable { ctl.selectedBoneId = b.id }.padding(vertical = 4.dp)
                )
            }
            ctl.selectedBoneId?.let { id ->
                val b = l.bones.firstOrNull { it.id == id }
                if (b != null) {
                    var bn by remember(id) { mutableStateOf(b.name) }
                    OutlinedTextField(bn, { bn = it; ctl.renameBone(id, it) }, label = { Text("Имя кости") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
            Text("Привяжите слои рисования к костям в их свойствах.", fontSize = 11.sp, color = Pal.dim, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.padding(top = 6.dp)) {
            IconBtn(Icons.Filled.ArrowUpward, "Выше") { ctl.moveLayer(l.id, true) }
            IconBtn(Icons.Filled.ArrowDownward, "Ниже") { ctl.moveLayer(l.id, false) }
            IconBtn(Icons.Filled.ContentCopy, "Дублировать") { ctl.duplicateLayer(l.id) }
            IconBtn(Icons.Filled.MergeType, "Слить с нижним") { ctl.mergeDown(l.id) }
            IconBtn(Icons.Filled.Delete, "Удалить", tint = Pal.pink) { ctl.deleteLayer(l.id) }
        }
    }
}
