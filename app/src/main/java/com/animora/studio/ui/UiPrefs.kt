package com.animora.studio.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.animora.studio.ui.editor.Tool

/** Кнопки верхней панели, которые можно скрывать. */
enum class TopButton(val title: String) {
    UNDO("Отменить / повторить"), PLAY("Воспроизведение"), PLAY_MODE("Режим воспроизведения"),
    ONION("Луковая кожа"), ONION_SETTINGS("Настройки кальки"), LAYERS("Панель слоёв"),
    FOCUS("Режим фокуса"), EXPORT("Экспорт")
}

/** Готовые раскладки интерфейса. */
enum class UiPreset(val title: String) { DEFAULT("Стандарт"), LEFTY("Для левши"), MINIMAL("Минимальный"), TABLET("Планшет"), BIG("Крупные кнопки") }

/**
 * Настраиваемый интерфейс редактора. Значения — Compose-состояния, поэтому изменения
 * применяются мгновенно; сохраняются в SharedPreferences.
 */
object UiPrefs {
    private var sp: SharedPreferences? = null

    var toolsRight by mutableStateOf(false)
    var layersLeft by mutableStateOf(false)
    var timelineTop by mutableStateOf(false)
    var slidersRight by mutableStateOf(false)
    var contextBarBottom by mutableStateOf(false)
    var showSliders by mutableStateOf(true)
    var showToolLabels by mutableStateOf(false)
    var layersOpenByDefault by mutableStateOf(true)
    var toolSize by mutableIntStateOf(42)          // dp
    var layersWidth by mutableIntStateOf(240)      // dp
    var timelineRows by mutableIntStateOf(5)
    var timelineCell by mutableIntStateOf(22)      // dp, ширина кадра
    var panelAlpha by mutableFloatStateOf(0.95f)
    var accent by mutableIntStateOf(0xFF8B7CFF.toInt())
    var workspace by mutableIntStateOf(0xFF24222E.toInt())
    var tools by mutableStateOf(Tool.entries.toList())
    var hiddenTop by mutableStateOf(setOf<TopButton>())

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
        sp = p
        toolsRight = p.getBoolean("toolsRight", false)
        layersLeft = p.getBoolean("layersLeft", false)
        timelineTop = p.getBoolean("timelineTop", false)
        slidersRight = p.getBoolean("slidersRight", false)
        contextBarBottom = p.getBoolean("contextBarBottom", false)
        showSliders = p.getBoolean("showSliders", true)
        showToolLabels = p.getBoolean("showToolLabels", false)
        layersOpenByDefault = p.getBoolean("layersOpen", true)
        toolSize = p.getInt("toolSize", 42)
        layersWidth = p.getInt("layersWidth", 240)
        timelineRows = p.getInt("timelineRows", 5)
        timelineCell = p.getInt("timelineCell", 22)
        panelAlpha = p.getFloat("panelAlpha", 0.95f)
        accent = p.getInt("accent", 0xFF8B7CFF.toInt())
        workspace = p.getInt("workspace", 0xFF24222E.toInt())
        tools = p.getString("tools", null)?.split(",")?.mapNotNull { n -> Tool.entries.firstOrNull { it.name == n } }
            ?.takeIf { it.isNotEmpty() } ?: Tool.entries.toList()
        // новые инструменты появляются на панели у тех, кто уже настраивал её раньше
        if (p.getInt("toolsVer", 1) < 2 && Tool.TEXT !in tools) tools = tools.toMutableList().also { l -> l.add((l.indexOf(Tool.TRANSFORM) + 1).coerceIn(0, l.size), Tool.TEXT) }
        if (p.getInt("toolsVer", 1) < 3) {
            val l = tools.toMutableList()
            listOf(Tool.BEND, Tool.WIDTH, Tool.WARP).forEach { t -> if (t !in l) l.add((l.indexOf(Tool.LASSO) + 1).coerceIn(0, l.size), t) }
            tools = l
        }
        hiddenTop = p.getString("hiddenTop", "")!!.split(",").mapNotNull { n -> TopButton.entries.firstOrNull { it.name == n } }.toSet()
        Pal.accent = Color(accent)
    }

    fun save() {
        val p = sp ?: return
        Pal.accent = Color(accent)
        p.edit()
            .putBoolean("toolsRight", toolsRight).putBoolean("layersLeft", layersLeft)
            .putBoolean("timelineTop", timelineTop).putBoolean("slidersRight", slidersRight)
            .putBoolean("contextBarBottom", contextBarBottom).putBoolean("showSliders", showSliders)
            .putBoolean("showToolLabels", showToolLabels).putBoolean("layersOpen", layersOpenByDefault)
            .putInt("toolSize", toolSize).putInt("layersWidth", layersWidth)
            .putInt("timelineRows", timelineRows).putInt("timelineCell", timelineCell)
            .putFloat("panelAlpha", panelAlpha).putInt("accent", accent).putInt("workspace", workspace)
            .putString("tools", tools.joinToString(",") { it.name }).putInt("toolsVer", 3)
            .putString("hiddenTop", hiddenTop.joinToString(",") { it.name })
            .apply()
    }

    fun visible(b: TopButton) = b !in hiddenTop

    fun moveTool(t: Tool, delta: Int) {
        val l = tools.toMutableList(); val i = l.indexOf(t)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, l.size - 1)
        l.removeAt(i); l.add(j, t); tools = l; save()
    }

    fun toggleTool(t: Tool) {
        tools = if (t in tools) (tools - t).ifEmpty { listOf(Tool.BRUSH) } else tools + t
        save()
    }

    fun apply(preset: UiPreset) {
        reset(saveNow = false)
        when (preset) {
            UiPreset.DEFAULT -> {}
            UiPreset.LEFTY -> { toolsRight = true; layersLeft = true; slidersRight = true }
            UiPreset.MINIMAL -> {
                showSliders = false; layersOpenByDefault = false; timelineRows = 2; toolSize = 38
                tools = listOf(Tool.BRUSH, Tool.ERASER, Tool.FILL, Tool.LASSO, Tool.HAND)
                hiddenTop = setOf(TopButton.PLAY_MODE, TopButton.ONION_SETTINGS)
            }
            UiPreset.TABLET -> { timelineRows = 7; layersWidth = 300; toolSize = 46; timelineCell = 26; showToolLabels = true }
            UiPreset.BIG -> { toolSize = 56; timelineCell = 30; timelineRows = 4; showToolLabels = true }
        }
        save()
    }

    fun reset(saveNow: Boolean = true) {
        toolsRight = false; layersLeft = false; timelineTop = false; slidersRight = false; contextBarBottom = false
        showSliders = true; showToolLabels = false; layersOpenByDefault = true
        toolSize = 42; layersWidth = 240; timelineRows = 5; timelineCell = 22; panelAlpha = 0.95f
        accent = 0xFF8B7CFF.toInt(); workspace = 0xFF24222E.toInt()
        tools = Tool.entries.toList(); hiddenTop = emptySet()
        if (saveNow) save()
    }

    @Suppress("unused")
    fun accentColor() = Color(accent).toArgb()
}
