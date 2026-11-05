package com.animora.studio.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.util.Xml
import com.animora.studio.model.CustomBrush
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Импорт кистей Krita:
 *  .kpp    — пресет кисти (PNG-превью с XML-описанием в текстовом чанке «preset»)
 *  .bundle — набор ресурсов Krita (ZIP: папки paintoppresets и brushes)
 *  .gbr    — кончик GIMP/Krita, .gih — анимированный (несколько кончиков), .png — кончик-изображение
 */
object KritaImporter {

    class Result(val brushes: List<CustomBrush>, val warnings: List<String>)

    class Tip(val masks: List<Bitmap>, val colored: Boolean, val spacing: Float?)

    private const val MAX_TIP = 512

    fun import(fileName: String, bytes: ByteArray): Result {
        val lower = fileName.lowercase()
        val warnings = ArrayList<String>()
        val brushes = ArrayList<CustomBrush>()
        when {
            isZip(bytes) -> return importBundle(fileName, bytes)
            lower.endsWith(".abr") -> warnings.add("$fileName: формат Photoshop .abr не поддерживается — экспортируйте кисти из Krita в .bundle")
            lower.endsWith(".gbr") -> parseGbr(bytes, 0)?.let { brushes.add(tipBrush(baseName(fileName), it.first)) }
                ?: warnings.add("$fileName: не удалось прочитать .gbr")
            lower.endsWith(".gih") -> parseGih(bytes)?.let { brushes.add(tipBrush(baseName(fileName), it)) }
                ?: warnings.add("$fileName: не удалось прочитать .gih")
            isPng(bytes) -> {
                val preset = pngTextChunks(bytes)["preset"]
                if (preset != null) importKpp(fileName, bytes, preset, emptyMap(), warnings)?.let { brushes.add(it) }
                else parsePngTip(bytes)?.let { brushes.add(tipBrush(baseName(fileName), it)) }
                    ?: warnings.add("$fileName: не удалось прочитать изображение")
            }
            else -> warnings.add("$fileName: неизвестный формат")
        }
        return Result(brushes, warnings)
    }

    // ------------------------------------------------------------------ bundle
    private fun importBundle(fileName: String, bytes: ByteArray): Result {
        val warnings = ArrayList<String>()
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) entries[e.name] = zip.readBytes()
            }
        }
        val tips = HashMap<String, Tip>()
        for ((path, data) in entries) {
            val n = path.lowercase()
            if (!n.startsWith("brushes/")) continue
            val tip = when {
                n.endsWith(".gbr") -> parseGbr(data, 0)?.first
                n.endsWith(".gih") -> parseGih(data)
                n.endsWith(".png") -> parsePngTip(data)
                else -> null
            }
            if (tip != null) tips[baseName(path).lowercase()] = tip
        }
        val brushes = ArrayList<CustomBrush>()
        for ((path, data) in entries) {
            if (!path.lowercase().startsWith("paintoppresets/") || !path.lowercase().endsWith(".kpp")) continue
            val preset = pngTextChunks(data)["preset"] ?: continue
            importKpp(path, data, preset, tips, warnings)?.let { brushes.add(it) }
        }
        if (brushes.isEmpty()) tips.forEach { (n, t) -> brushes.add(tipBrush(n.substringBeforeLast('.'), t)) }
        if (brushes.isEmpty()) warnings.add("$fileName: в наборе не найдено кистей")
        return Result(brushes, warnings)
    }

    // ------------------------------------------------------------------ kpp
    private fun importKpp(fileName: String, png: ByteArray, xml: String, bundleTips: Map<String, Tip>, warnings: MutableList<String>): CustomBrush? {
        val params = HashMap<String, String>()
        val resources = HashMap<String, ByteArray>()
        var presetName = baseName(fileName).substringBeforeLast('.')
        var paintop = ""
        try {
            val p = Xml.newPullParser()
            p.setInput(StringReader(xml))
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "Preset" -> {
                        p.getAttributeValue(null, "name")?.takeIf { it.isNotBlank() }?.let { presetName = it }
                        paintop = p.getAttributeValue(null, "paintopid") ?: ""
                    }
                    "param" -> {
                        val name = p.getAttributeValue(null, "name") ?: continue
                        val type = p.getAttributeValue(null, "type") ?: "string"
                        val text = p.nextText() ?: ""
                        params[name] = if (type == "bytearray") String(Base64.decode(text.trim(), Base64.DEFAULT)) else text
                    }
                    "resource" -> {
                        val fn = p.getAttributeValue(null, "filename") ?: p.getAttributeValue(null, "name") ?: continue
                        runCatching { resources[baseName(fn).lowercase()] = Base64.decode(p.nextText().trim(), Base64.DEFAULT) }
                    }
                }
            }
        } catch (e: Exception) {
            warnings.add("$presetName: ошибка разбора пресета (${e.message})")
            return null
        }

        val def = params["brush_definition"]
        var tip: Tip? = null
        var size = 20f
        var spacing = 0.1f
        var angle = 0f
        if (def != null && def.isNotBlank()) {
            val b = parseBrushDefinition(def)
            spacing = b.attr("spacing")?.toFloatOrNull() ?: 0.1f
            if (b.attr("useAutoSpacing") == "1") spacing = 0.1f * (b.attr("autoSpacingCoeff")?.toFloatOrNull() ?: 0.8f)
            angle = Math.toDegrees((b.attr("angle")?.toDoubleOrNull() ?: 0.0)).toFloat()
            when (b.attr("type")) {
                "auto_brush" -> {
                    val m = b.mask
                    val d = m["diameter"]?.toFloatOrNull() ?: 20f
                    size = d
                    tip = Tip(listOf(autoMask(
                        d, m["ratio"]?.toFloatOrNull() ?: 1f,
                        max(m["hfade"]?.toFloatOrNull() ?: 0f, m["vfade"]?.toFloatOrNull() ?: 0f),
                        m["spikes"]?.toIntOrNull() ?: 2, m["type"] == "rect", m["id"] == "soft"
                    )), false, null)
                }
                else -> {
                    val fn = b.attr("filename")?.let { baseName(it).lowercase() }
                    val raw = fn?.let { resources[it] }
                    tip = when {
                        raw != null && fn != null -> when {
                            fn.endsWith(".gih") -> parseGih(raw)
                            fn.endsWith(".gbr") -> parseGbr(raw, 0)?.first
                            else -> parsePngTip(raw)
                        }
                        fn != null -> bundleTips[fn]
                        else -> null
                    }
                    val scale = b.attr("scale")?.toFloatOrNull() ?: 1f
                    tip?.masks?.firstOrNull()?.let { size = max(it.width, it.height) * scale }
                    val maskOnly = b.attr("ColorAsMask") == "1" || b.attr("brushApplication") == "0"
                    if (tip != null && maskOnly && tip.colored) tip = Tip(tip.masks.map { toMask(it) }, false, tip.spacing)
                    if (tip == null) {
                        warnings.add("$presetName: кончик «${b.attr("filename") ?: "?"}» не найден — используется круглый. Импортируйте .bundle целиком")
                        tip = Tip(listOf(autoMask(size, 1f, 0.1f, 2, rect = false, soft = false)), false, null)
                    }
                }
            }
        } else {
            warnings.add("$presetName: движок «$paintop» упрощён до штамповой кисти")
            tip = Tip(listOf(autoMask(20f, 1f, 0.3f, 2, rect = false, soft = false)), false, null)
        }

        fun bool(k: String, def: Boolean) = params[k]?.let { it == "true" || it == "1" } ?: def
        val rotSensor = params["PressureRotationSensor"] ?: ""
        val rotateOn = bool("PressureRotation", false)
        return CustomBrush(
            name = presetName, masks = tip!!.masks, colored = tip.colored,
            spacing = spacing.coerceIn(0.01f, 5f), angle = angle,
            rotateWithStroke = rotateOn && rotSensor.contains("drawingangle"),
            randomRotation = if (rotateOn && (rotSensor.contains("fuzzy") || rotSensor.contains("random"))) 180f else 0f,
            scatter = if (bool("PressureScatter", false)) (params["ScatterValue"]?.toFloatOrNull() ?: 1f).coerceIn(0f, 5f) else 0f,
            flow = (params["FlowValue"]?.toFloatOrNull() ?: 1f).coerceIn(0.02f, 1f),
            defaultSize = size.coerceIn(1f, 300f),
            defaultOpacity = (params["OpacityValue"]?.toFloatOrNull() ?: 1f).coerceIn(0.02f, 1f),
            pressureSize = bool("PressureSize", true),
            pressureOpacity = bool("PressureOpacity", false),
            source = baseName(fileName),
            thumb = runCatching { BitmapFactory.decodeByteArray(png, 0, png.size) }.getOrNull()?.let { scaleDown(it, 96) }
        )
    }

    private class BrushDef(val attrs: Map<String, String>, val mask: Map<String, String>) {
        fun attr(k: String) = attrs[k]
    }

    private fun parseBrushDefinition(xml: String): BrushDef {
        val attrs = HashMap<String, String>(); val mask = HashMap<String, String>()
        runCatching {
            val p = Xml.newPullParser()
            p.setInput(StringReader(xml))
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                val target = when (p.name) { "Brush" -> attrs; "MaskGenerator" -> mask; else -> null } ?: continue
                for (i in 0 until p.attributeCount) target[p.getAttributeName(i)] = p.getAttributeValue(i)
            }
        }
        return BrushDef(attrs, mask)
    }

    // ------------------------------------------------------------------ кончики
    /** GIMP/Krita .gbr: заголовок (BE) + маска 8 бит или RGBA. Возвращает кончик и смещение конца. */
    fun parseGbr(bytes: ByteArray, off: Int): Pair<Tip, Int>? = runCatching {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        bb.position(off)
        val header = bb.int; val version = bb.int
        val w = bb.int; val h = bb.int; val depth = bb.int
        var spacing: Float? = null
        if (version >= 2) { bb.int /* GIMP */; spacing = bb.int / 100f }
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null
        val start = off + header
        val px = IntArray(w * h)
        var colored = false
        when (depth) {
            1 -> for (i in px.indices) px[i] = Color.argb(bytes[start + i].toInt() and 0xFF, 0, 0, 0)
            4 -> for (i in px.indices) {
                val o = start + i * 4
                val r = bytes[o].toInt() and 0xFF; val g = bytes[o + 1].toInt() and 0xFF
                val b = bytes[o + 2].toInt() and 0xFF; val a = bytes[o + 3].toInt() and 0xFF
                if (a > 0 && (abs(r - g) > 8 || abs(g - b) > 8)) colored = true
                px[i] = Color.argb(a, r, g, b)
            }
            else -> return null
        }
        var bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        if (depth == 4 && !colored) bmp = toMask(bmp)
        Tip(listOf(scaleDown(bmp, MAX_TIP)), colored, spacing) to (start + w * h * depth)
    }.getOrNull()

    /** .gih: «имя\nпараметры\n» + последовательность .gbr. */
    fun parseGih(bytes: ByteArray): Tip? {
        var nl = 0; var pos = 0
        while (pos < bytes.size && nl < 2) { if (bytes[pos] == '\n'.code.toByte()) nl++; pos++ }
        val masks = ArrayList<Bitmap>(); var colored = false; var spacing: Float? = null
        while (pos < bytes.size - 28 && masks.size < 64) {
            val (tip, next) = parseGbr(bytes, pos) ?: break
            masks.addAll(tip.masks); colored = colored || tip.colored; spacing = spacing ?: tip.spacing
            pos = next
        }
        return if (masks.isEmpty()) null else Tip(masks, colored, spacing)
    }

    /** PNG-кончик: серый → маска (чёрное = краска, учитывается альфа), цветной → цветная кисть. */
    fun parsePngTip(bytes: ByteArray): Tip? {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val bmp = scaleDown(src.copy(Bitmap.Config.ARGB_8888, false), MAX_TIP)
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        var colored = false
        for (c in px) {
            if ((c ushr 24) < 16) continue
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if (abs(r - g) > 10 || abs(g - b) > 10) { colored = true; break }
        }
        return Tip(listOf(if (colored) bmp else toMask(bmp)), colored, null)
    }

    /** Серое изображение → маска: альфа = непрозрачность × (1 − яркость). */
    fun toMask(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = (c ushr 24) and 0xFF
            val lum = (((c shr 16) and 0xFF) * 30 + ((c shr 8) and 0xFF) * 59 + (c and 0xFF) * 11) / 100
            px[i] = Color.argb(a * (255 - lum) / 255, 0, 0, 0)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Процедурный кончик Krita «auto_brush»: круг/квадрат, сплющивание, мягкость, лучи. */
    fun autoMask(diameter: Float, ratio: Float, fade: Float, spikes: Int, rect: Boolean, soft: Boolean): Bitmap {
        val w = diameter.coerceIn(8f, 256f).toInt()
        val h = max(1, (w * ratio.coerceIn(0.02f, 1f)).toInt())
        val hard = (1f - fade.coerceIn(0f, 1f)).coerceAtMost(0.999f)
        val px = IntArray(w * h)
        val seg = (2 * PI / max(2, spikes)).toFloat()
        for (y in 0 until h) for (x in 0 until w) {
            val nx = (x + 0.5f - w / 2f) / (w / 2f); val ny = (y + 0.5f - h / 2f) / (h / 2f)
            var d = if (rect) max(abs(nx), abs(ny)) else hypot(nx, ny)
            if (spikes > 2 && !rect) {
                val th = atan2(ny, nx)
                val a = ((th % seg) + seg) % seg - seg / 2
                d *= cos(a) / cos(seg / 2)
            }
            var v = when { d >= 1f -> 0f; d <= hard -> 1f; else -> (1f - d) / (1f - hard) }
            if (soft) v = v * v * (3 - 2 * v)
            px[y * w + x] = Color.argb((v * 255).toInt(), 0, 0, 0)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun tipBrush(name: String, t: Tip): CustomBrush {
        val m = t.masks.first()
        return CustomBrush(
            name = name.substringBeforeLast('.'), masks = t.masks, colored = t.colored,
            spacing = (t.spacing ?: 0.15f).coerceIn(0.01f, 5f),
            randomRotation = if (t.masks.size > 1) 180f else 0f,
            defaultSize = max(m.width, m.height).toFloat().coerceIn(4f, 200f),
            source = name
        )
    }

    // ------------------------------------------------------------------ утилиты
    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2].toInt() == 3 && b[3].toInt() == 4
    private fun isPng(b: ByteArray) = b.size > 8 && (b[0].toInt() and 0xFF) == 0x89 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
    private fun baseName(p: String) = p.substringAfterLast('/').substringAfterLast('\\')

    fun scaleDown(b: Bitmap, maxDim: Int): Bitmap {
        val m = max(b.width, b.height)
        if (m <= maxDim) return b
        val s = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true)
    }

    /** Текстовые чанки PNG (tEXt, zTXt, iTXt) — в них Krita хранит XML пресета. */
    fun pngTextChunks(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        if (!isPng(bytes)) return out
        var pos = 8
        while (pos + 8 <= bytes.size) {
            val len = ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.BIG_ENDIAN).int
            if (len < 0 || pos + 12 + len > bytes.size) break
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val ds = pos + 8; val de = ds + len
            runCatching {
                when (type) {
                    "tEXt" -> {
                        val z = indexOf0(bytes, ds, de)
                        out[String(bytes, ds, z - ds, Charsets.ISO_8859_1)] = String(bytes, z + 1, de - z - 1, Charsets.UTF_8)
                    }
                    "zTXt" -> {
                        val z = indexOf0(bytes, ds, de)
                        out[String(bytes, ds, z - ds, Charsets.ISO_8859_1)] = String(inflate(bytes, z + 2, de - z - 2), Charsets.UTF_8)
                    }
                    "iTXt" -> {
                        val z = indexOf0(bytes, ds, de)
                        val key = String(bytes, ds, z - ds, Charsets.ISO_8859_1)
                        val compressed = bytes[z + 1].toInt() == 1
                        val langEnd = indexOf0(bytes, z + 3, de)
                        val tkEnd = indexOf0(bytes, langEnd + 1, de)
                        val ts = tkEnd + 1
                        out[key] = if (compressed) String(inflate(bytes, ts, de - ts), Charsets.UTF_8) else String(bytes, ts, de - ts, Charsets.UTF_8)
                    }
                }
            }
            if (type == "IEND") break
            pos = de + 4
        }
        return out
    }

    private fun indexOf0(b: ByteArray, from: Int, to: Int): Int {
        for (i in from until to) if (b[i].toInt() == 0) return i
        return to
    }

    private fun inflate(b: ByteArray, off: Int, len: Int): ByteArray {
        val inf = Inflater(); inf.setInput(b, off, len)
        val bos = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            bos.write(buf, 0, n)
        }
        inf.end()
        return bos.toByteArray()
    }

    @Suppress("unused")
    private fun clampInt(v: Int, lo: Int, hi: Int) = min(max(v, lo), hi)
}
