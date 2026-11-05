package com.animora.studio.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Импортированная (штамповая) кисть — аналог кисти Krita на основе кончика.
 * masks: кончики; для маскирующих кистей это ARGB (чёрный + альфа = форма),
 * для цветных — исходное изображение.
 */
class CustomBrush(
    val id: String = newId(),
    val name: String,
    val masks: List<Bitmap>,
    val colored: Boolean = false,
    val spacing: Float = 0.1f,          // шаг отпечатков в долях диаметра
    val angle: Float = 0f,              // базовый угол кончика, градусы
    val rotateWithStroke: Boolean = false,
    val randomRotation: Float = 0f,     // разброс поворота, градусы
    val scatter: Float = 0f,            // разброс в долях диаметра
    val flow: Float = 1f,               // непрозрачность одного отпечатка
    val defaultSize: Float = 20f,
    val defaultOpacity: Float = 1f,
    val pressureSize: Boolean = true,
    val pressureOpacity: Boolean = false,
    val source: String = "",            // имя исходного файла / пресета Krita
    val thumb: Bitmap? = null
) {
    /** Маски для отрисовки: ALPHA_8 (рисуются цветом кисти) либо цветные. */
    @Volatile private var runtime: List<Bitmap>? = null

    fun drawMasks(): List<Bitmap> {
        runtime?.let { return it }
        val r = if (colored) masks else masks.map { it.extractAlpha() }
        runtime = r
        return r
    }

    fun toJson(withThumb: Boolean = true): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("colored", colored)
        put("spacing", spacing.toDouble()); put("angle", angle.toDouble()); put("rotate", rotateWithStroke)
        put("randRot", randomRotation.toDouble()); put("scatter", scatter.toDouble()); put("flow", flow.toDouble())
        put("size", defaultSize.toDouble()); put("opacity", defaultOpacity.toDouble())
        put("pSize", pressureSize); put("pOpacity", pressureOpacity); put("source", source)
        put("masks", JSONArray().apply { masks.forEach { put(bmpToB64(it)) } })
        if (withThumb) thumb?.let { put("thumb", bmpToB64(it)) }
    }

    companion object {
        fun bmpToB64(b: Bitmap): String {
            val bos = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.PNG, 100, bos)
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        }

        fun b64ToBmp(s: String): Bitmap? {
            val bytes = Base64.decode(s, Base64.NO_WRAP)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        fun fromJson(o: JSONObject): CustomBrush {
            val arr = o.getJSONArray("masks")
            val masks = (0 until arr.length()).mapNotNull { b64ToBmp(arr.getString(it)) }
            return CustomBrush(
                id = o.getString("id"), name = o.getString("name"), masks = masks,
                colored = o.optBoolean("colored", false),
                spacing = o.optDouble("spacing", 0.1).toFloat(), angle = o.optDouble("angle", 0.0).toFloat(),
                rotateWithStroke = o.optBoolean("rotate", false), randomRotation = o.optDouble("randRot", 0.0).toFloat(),
                scatter = o.optDouble("scatter", 0.0).toFloat(), flow = o.optDouble("flow", 1.0).toFloat(),
                defaultSize = o.optDouble("size", 20.0).toFloat(), defaultOpacity = o.optDouble("opacity", 1.0).toFloat(),
                pressureSize = o.optBoolean("pSize", true), pressureOpacity = o.optBoolean("pOpacity", false),
                source = o.optString("source", ""),
                thumb = if (o.has("thumb")) b64ToBmp(o.getString("thumb")) else null
            )
        }
    }
}

/** Глобальный реестр кистей, доступный рендереру (библиотека + кисти, встроенные в открытые проекты). */
object BrushRegistry {
    private val map = ConcurrentHashMap<String, CustomBrush>()
    fun get(id: String?): CustomBrush? = id?.let { map[it] }
    fun register(b: CustomBrush) { if (b.masks.isNotEmpty()) map[b.id] = b }
    fun remove(id: String) { map.remove(id) }
}

/** Библиотека импортированных кистей приложения: files/brushes/<id>.json */
class BrushLibrary(context: Context) {
    private val dir = File(context.filesDir, "brushes").apply { mkdirs() }

    fun loadAll(): List<CustomBrush> = dir.listFiles { f -> f.name.endsWith(".json") }
        ?.mapNotNull { f -> runCatching { CustomBrush.fromJson(JSONObject(f.readText())) }.getOrNull() }
        ?.filter { it.masks.isNotEmpty() }
        ?.sortedBy { it.name.lowercase() }
        ?.onEach { BrushRegistry.register(it) }
        ?: emptyList()

    fun save(b: CustomBrush) {
        File(dir, "${b.id}.json").writeText(b.toJson().toString())
        BrushRegistry.register(b)
    }

    /** Удаляем из библиотеки; в реестре кисть остаётся, чтобы уже нарисованные штрихи не сломались. */
    fun delete(id: String) { File(dir, "$id.json").delete() }
}
