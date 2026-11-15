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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animora.studio.engine.Viseme
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
 * Lip-sync. Два способа:
 * — по звукам речи (фонемы): 9 форм рта по схеме мультипликаторов, программа сама слышит А, О, У, М, Ф…;
 * — по громкости: 2–4 формы (закрыт … широко).
 * Формы — это ключевые кадры отдельного слоя или кадры внутри символа рта.
 */
@Composable
fun LipSyncDialog(ctl: EditorController, onDismiss: () -> Unit) {
    ctl.version
    val p = ctl.project
    val audioLayers = p.layers.filter { it.type == LayerType.AUDIO && it.audio != null }
    val drawLayers = p.layers.filter { it.type == LayerType.DRAWING }
    var audio by remember { mutableStateOf(audioLayers.firstOrNull { it.id == ctl.activeLayerId } ?: audioLayers.firstOrNull()) }
    var phon by remember { mutableStateOf(true) }
    var symbolMode by remember { mutableStateOf(false) }
    var src by remember { mutableStateOf<Layer?>(drawLayers.firstOrNull { it.name.contains("рот", true) } ?: drawLayers.firstOrNull()) }
    var target by remember { mutableStateOf<Layer?>(drawLayers.firstOrNull { it.id != src?.id } ?: drawLayers.firstOrNull()) }
    val shapes = remember { mutableStateListOf<Int?>(null, null, null, null) }
    val vis = remember { mutableStateMapOf<Viseme, Int>() }
    var sens by remember { mutableFloatStateOf(1f) }
    // символ рта на целевом слое (для режима «кадры символа»)
    val mouthSym = target?.let { t ->
        val start = audio?.audio?.startFrame ?: 0
        (t.keyframes.floorEntry(start)?.value ?: t.keyframes.ceilingEntry(start)?.value)?.elements
            ?.filterIsInstance<com.animora.studio.model.InstanceElement>()?.firstOrNull()?.let { p.symbol(it.symbolId) }
    }
    val options: List<Int> = if (symbolMode) (0 until (mouthSym?.frameCount ?: 0)).toList() else src?.keyframes?.keys?.toList() ?: emptyList()
    // по умолчанию — формы по порядку
    remember(src?.id, symbolMode, mouthSym?.id) {
        for (i in 0 until 4) shapes[i] = options.getOrNull(i)
        vis.clear(); Viseme.entries.forEachIndexed { i, v -> options.getOrNull(i)?.let { vis[v] = it } }
        0
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lip-sync по звуку") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (audioLayers.isEmpty()) Text("Сначала импортируйте звук (кнопка ♪ на панели слоёв).", color = Pal.pink, fontSize = 13.sp)
                Row {
                    SmallBtn("По звукам речи", selected = phon) { phon = true }
                    SmallBtn("По громкости", selected = !phon) { phon = false }
                }
                Text(
                    if (phon) "Программа различает гласные (А, Э, О, У) и согласные (М-Б-П, Ф-В, Л, С…) по спектру голоса. Нарисуйте до 9 форм рта — недостающие заменятся похожими."
                    else "Простой режим: рот открывается тем шире, чем громче звук (2–4 формы).",
                    fontSize = 12.sp, color = Pal.dim
                )
                SectionTitle("Источники")
                Picker("Звук:", audio?.name ?: "—", audioLayers, { it.name }, { audio = it })
                if (phon) Row {
                    SmallBtn("Формы — ключи слоя", selected = !symbolMode) { symbolMode = false }
                    SmallBtn("Формы — кадры символа", selected = symbolMode) { symbolMode = true }
                }
                if (!symbolMode || !phon) Picker("Формы рта на слое:", src?.name ?: "—", drawLayers, { it.name }, { src = it })
                Picker("Куда расставить:", target?.name ?: "—", drawLayers.filter { symbolMode && phon || it.id != src?.id }, { it.name }, { target = it })
                if (symbolMode && phon) Text(
                    if (mouthSym != null) "Символ «${mouthSym.name}», кадров: ${mouthSym.frameCount}. Каждая форма — кадр внутри символа; экземпляр переключается на нужный кадр."
                    else "На целевом слое нет символа. Нарисуйте рты в символе (по кадру на форму) и поставьте его на этот слой.",
                    fontSize = 11.sp, color = if (mouthSym != null) Pal.dim else Pal.pink
                )
                val fmt: (Int) -> String = { if (symbolMode && phon) "кадр символа ${it + 1}" else "кадр ${it + 1}" }
                if (phon) {
                    SectionTitle("Формы рта")
                    Viseme.entries.forEach { v ->
                        Column(Modifier.padding(vertical = 1.dp)) {
                            Picker("${v.title}:", vis[v]?.let(fmt) ?: "— замена", options, fmt, { f -> if (f == null) vis.remove(v) else vis[v] = f }, allowNone = true)
                            Text(v.hint, fontSize = 10.sp, color = Pal.dim, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                } else {
                    SectionTitle("Формы (ключевые кадры слоя «${src?.name ?: "—"}»)")
                    SHAPES.forEachIndexed { i, n ->
                        Picker("$n:", shapes[i]?.let { "кадр ${it + 1}" } ?: "— не задано", options, { "кадр ${it + 1}" }, { shapes[i] = it }, allowNone = true)
                    }
                }
                SectionTitle("Чувствительность ×${"%.1f".format(sens)}")
                Slider(sens, { sens = it }, valueRange = 0.3f..3f)
                Text("Больше — рот реагирует на тихие звуки.", fontSize = 11.sp, color = Pal.dim)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = audio; val s = src; val t = target
                when {
                    a == null || t == null -> ctl.toast("Выберите звук и слой")
                    phon -> { ctl.lipSyncPhonemes(a.id, if (symbolMode) null else s?.id, vis.toMap(), t.id, sens, symbolMode); onDismiss() }
                    s != null -> { ctl.lipSync(a.id, s.id, shapes.toList(), t.id, sens); onDismiss() }
                    else -> ctl.toast("Выберите слой с формами")
                }
            }) { Text("Сгенерировать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
