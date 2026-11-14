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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animora.studio.model.ColorEffect
import com.animora.studio.model.LoopMode
import com.animora.studio.ui.ColorDot
import com.animora.studio.ui.ColorPickerDialog
import com.animora.studio.ui.Pal
import com.animora.studio.ui.SectionTitle
import kotlin.math.max
import kotlin.math.roundToInt

/** Свойства экземпляра символа: воспроизведение, первый кадр, цветовые эффекты, замена символа. */
@Composable
fun InstanceDialog(ctl: EditorController, onDismiss: () -> Unit) {
    ctl.version
    val inst = ctl.selectedInstance()
    val sym = inst?.let { ctl.project.symbol(it.symbolId) }
    var picker by remember { mutableStateOf(false) }
    var swapMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        title = { Text("Экземпляр: ${sym?.name ?: "—"}") },
        text = {
            if (inst == null || sym == null) { Text("Выделите один экземпляр символа инструментом «Лассо».", color = Pal.dim); return@AlertDialog }
            val fx = inst.effect
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("Воспроизведение")
                Row { LoopMode.entries.forEach { m -> SmallBtn(m.title, selected = inst.loop == m) { ctl.updateInstance("loop${System.nanoTime()}") { it.with(loop = m) } } } }
                Text(
                    when (inst.loop) {
                        LoopMode.LOOP -> "Анимация символа повторяется по кругу"
                        LoopMode.ONCE -> "Проигрывается один раз и останавливается на последнем кадре"
                        LoopMode.SINGLE -> "Показывается только выбранный кадр (удобно для поз и сменных рисунков)"
                    }, fontSize = 11.sp, color = Pal.dim
                )
                if (sym.frameCount > 1) {
                    SectionTitle("Первый кадр: ${inst.firstFrame + 1} из ${sym.frameCount}")
                    Slider(inst.firstFrame.toFloat(), { v -> ctl.updateInstance("ff") { it.with(firstFrame = v.roundToInt()) } }, valueRange = 0f..max(1, sym.frameCount - 1).toFloat())
                }
                SectionTitle("Прозрачность ${(fx.alpha * 100).roundToInt()}%")
                Slider(fx.alpha, { v -> ctl.updateInstance("fxa") { it.with(effect = fx.copy(alpha = v)) } })
                SectionTitle("Яркость ${(fx.brightness * 100).roundToInt()}%")
                Slider(fx.brightness, { v -> ctl.updateInstance("fxb") { it.with(effect = fx.copy(brightness = v)) } }, valueRange = -1f..1f)
                SectionTitle("Тонирование ${(fx.tintAmount * 100).roundToInt()}%")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(fx.tint, 30) { picker = true }
                    Slider(fx.tintAmount, { v -> ctl.updateInstance("fxt") { it.with(effect = fx.copy(tintAmount = v)) } }, modifier = Modifier.padding(start = 8.dp))
                }
                Row(Modifier.padding(top = 6.dp)) {
                    SmallBtn("Сбросить эффекты") { ctl.updateInstance("fxr${System.nanoTime()}") { it.with(effect = ColorEffect()) } }
                    Box {
                        SmallBtn("Заменить символ…") { swapMenu = true }
                        DropdownMenu(swapMenu, { swapMenu = false }) {
                            ctl.project.symbols.filter { it.id != sym.id }.forEach { s ->
                                DropdownMenuItem(text = { Text(s.name) }, onClick = { ctl.updateInstance("swap${System.nanoTime()}") { it.with(symbolId = s.id) }; swapMenu = false })
                            }
                        }
                    }
                }
                Text("Эффекты и кадр плавно меняются в твине движения, если у двух ключей стоит один и тот же символ.", fontSize = 11.sp, color = Pal.dim, modifier = Modifier.padding(top = 6.dp))
            }
        }
    )
    if (picker && inst != null) ColorPickerDialog(inst.effect.tint, ctl.project.palette, ctl.recentColors.toList(), {}, { picker = false }) { c ->
        ctl.updateInstance("fxtc${System.nanoTime()}") { it.with(effect = it.effect.copy(tint = c, tintAmount = if (it.effect.tintAmount == 0f) 0.5f else it.effect.tintAmount)) }
        picker = false
    }
}
