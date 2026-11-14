package com.animora.studio.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animora.studio.model.Layer
import com.animora.studio.model.LayerType
import com.animora.studio.ui.Pal
import com.animora.studio.ui.SectionTitle

private val SHAPES = listOf("Закрыт (тишина)", "Чуть открыт", "Средне", "Широко")

@Composable
private fun <T> Picker(label: String, value: String, items: List<T>, title: (T) -> String, onPick: (T?) -> Unit, allowNone: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label  ", fontSize = 13.sp, color = Pal.dim)
        Box {
            SmallBtn(value, enabled = items.isNotEmpty() || allowNone) { open = true }
            DropdownMenu(open, { open = false }) {
                if (allowNone) DropdownMenuItem(text = { Text("— не задано") }, onClick = { onPick(null); open = false })
                items.forEach { it -> DropdownMenuItem(text = { Text(title(it)) }, onClick = { onPick(it); open = false }) }
            }
        }
    }
}

/**
 * Lip-sync: на отдельном слое нарисуйте 2–4 формы рта на разных ключевых кадрах,
 * затем программа расставит их копии на целевом слое по громкости речи.
 */
@Composable
fun LipSyncDialog(ctl: EditorController, onDismiss: () -> Unit) {
    ctl.version
    val p = ctl.project
    val audioLayers = p.layers.filter { it.type == LayerType.AUDIO && it.audio != null }
    val drawLayers = p.layers.filter { it.type == LayerType.DRAWING }
    var audio by remember { mutableStateOf(audioLayers.firstOrNull { it.id == ctl.activeLayerId } ?: audioLayers.firstOrNull()) }
    var src by remember { mutableStateOf<Layer?>(drawLayers.firstOrNull { it.name.contains("рот", true) } ?: drawLayers.firstOrNull()) }
    var target by remember { mutableStateOf<Layer?>(drawLayers.firstOrNull { it.id != src?.id }) }
    val shapes = remember { mutableStateListOf<Int?>(null, null, null, null) }
    var sens by remember { mutableFloatStateOf(1f) }
    // по умолчанию — первые ключи исходного слоя по порядку
    remember(src?.id) {
        val keys = src?.keyframes?.keys?.toList() ?: emptyList()
        for (i in 0 until 4) shapes[i] = keys.getOrNull(i)
        0
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lip-sync по звуку") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "1) Создайте слой «Рот» и нарисуйте формы рта на разных ключевых кадрах (например, 1–4).\n" +
                        "2) Выберите здесь звук, слой с формами и слой, куда расставить анимацию.\n" +
                        "Формы подбираются по громкости речи (не по звукам-фонемам).",
                    fontSize = 12.sp, color = Pal.dim
                )
                if (audioLayers.isEmpty()) Text("Сначала импортируйте звук (кнопка ♪ на панели слоёв).", color = Pal.pink, fontSize = 13.sp)
                SectionTitle("Источники")
                Picker("Звук:", audio?.name ?: "—", audioLayers, { it.name }, { audio = it })
                Picker("Формы рта на слое:", src?.name ?: "—", drawLayers, { it.name }, { src = it })
                Picker("Куда расставить:", target?.name ?: "—", drawLayers.filter { it.id != src?.id }, { it.name }, { target = it })
                SectionTitle("Формы (ключевые кадры слоя «${src?.name ?: "—"}»)")
                val keys = src?.keyframes?.keys?.toList() ?: emptyList()
                SHAPES.forEachIndexed { i, n ->
                    Picker("$n:", shapes[i]?.let { "кадр ${it + 1}" } ?: "— не задано", keys, { "кадр ${it + 1}" }, { shapes[i] = it }, allowNone = true)
                }
                SectionTitle("Чувствительность ×${"%.1f".format(sens)}")
                Slider(sens, { sens = it }, valueRange = 0.3f..3f)
                Text("Больше — рот открывается шире на тихих звуках.", fontSize = 11.sp, color = Pal.dim)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = audio; val s = src; val t = target
                if (a != null && s != null && t != null) { ctl.lipSync(a.id, s.id, shapes.toList(), t.id, sens); onDismiss() }
                else ctl.toast("Выберите звук и слои")
            }) { Text("Сгенерировать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
