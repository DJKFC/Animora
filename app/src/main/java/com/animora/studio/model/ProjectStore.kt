package com.animora.studio.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProjectMeta(
    val id: String, val name: String, val width: Int, val height: Int,
    val fps: Int, val frames: Int, val modified: Long, val thumb: File?
)

/** Хранение проектов: files/projects/<id>/{project.json, meta.json, thumb.png} */
class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    private fun dir(id: String) = File(root, id)

    fun list(): List<ProjectMeta> = root.listFiles()?.mapNotNull { d ->
        try {
            val m = JSONObject(File(d, "meta.json").readText())
            ProjectMeta(
                m.getString("id"), m.getString("name"), m.getInt("w"), m.getInt("h"),
                m.getInt("fps"), m.getInt("frames"), m.getLong("modified"),
                File(d, "thumb.png").takeIf { it.exists() }
            )
        } catch (e: Exception) { null }
    }?.sortedByDescending { it.modified } ?: emptyList()

    fun save(p: Project, thumb: Bitmap?) {
        val d = dir(p.id).apply { mkdirs() }
        val tmp = File(d, "project.json.tmp")
        tmp.writeText(Serializer.toJson(p).toString())
        tmp.renameTo(File(d, "project.json"))
        File(d, "meta.json").writeText(JSONObject().apply {
            put("id", p.id); put("name", p.name); put("w", p.width); put("h", p.height)
            put("fps", p.fps); put("frames", p.frameCount); put("modified", p.modified)
        }.toString())
        thumb?.let { b -> File(d, "thumb.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } }
    }

    /** Папка звуковых файлов проекта. */
    fun audioDir(projectId: String) = File(dir(projectId), "audio").apply { mkdirs() }

    fun load(id: String): Project = Serializer.fromJson(JSONObject(File(dir(id), "project.json").readText()))

    fun delete(id: String) { dir(id).deleteRecursively() }

    fun rename(id: String, name: String) {
        val p = load(id); p.name = name; save(p, null)
    }

    fun duplicate(id: String) {
        val src = dir(id)
        val json = Serializer.toJson(load(id))
        val nid = newId()
        json.put("id", nid); json.put("name", json.getString("name") + " (копия)")
        val p = Serializer.fromJson(json)
        p.modified = System.currentTimeMillis()
        File(src, "audio").listFiles()?.forEach { f -> f.copyTo(File(audioDir(nid), f.name), overwrite = true) }
        save(p, File(src, "thumb.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) })
    }

    fun exportJson(id: String): String = File(dir(id), "project.json").readText()

    fun importJson(text: String): Project {
        val json = JSONObject(text)
        json.put("id", newId())
        val p = Serializer.fromJson(json)
        save(p, null)
        return p
    }
}

object Serializer {
    private fun floatsToB64(a: FloatArray): String {
        val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(a)
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
    }

    private fun b64ToFloats(s: String): FloatArray {
        val bytes = Base64.decode(s, Base64.NO_WRAP)
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private fun bitmapToB64(e: BitmapElement): String {
        e.encoded?.let { return it }
        val bos = ByteArrayOutputStream()
        e.bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP).also { e.encoded = it }
    }

    private fun matrixToJson(m: Matrix): JSONArray {
        val v = FloatArray(9); m.getValues(v)
        return JSONArray().apply { v.forEach { put(it.toDouble()) } }
    }

    private fun jsonToMatrix(a: JSONArray): Matrix {
        val v = FloatArray(9) { a.getDouble(it).toFloat() }
        return Matrix().apply { setValues(v) }
    }

    fun toJson(p: Project): JSONObject = JSONObject().apply {
        put("v", 1); put("id", p.id); put("name", p.name)
        put("w", p.width); put("h", p.height); put("fps", p.fps); put("frames", p.frameCount)
        put("bg", p.bgColor); put("bgType", p.bgType.name)
        put("created", p.created); put("modified", p.modified)
        put("palette", JSONArray().apply { p.palette.forEach { put(it) } })
        put("layers", JSONArray().apply { p.layers.forEach { put(layerToJson(it)) } })
        if (p.guidesX.isNotEmpty()) put("guidesX", JSONArray().apply { p.guidesX.forEach { put(it.toDouble()) } })
        if (p.guidesY.isNotEmpty()) put("guidesY", JSONArray().apply { p.guidesY.forEach { put(it.toDouble()) } })
        if (p.scenes.isNotEmpty()) {
            put("currentScene", p.currentScene)
            put("scenes", JSONArray().apply {
                p.scenes.forEachIndexed { i, sc ->
                    put(JSONObject().apply {
                        put("id", sc.id); put("name", sc.name)
                        // текущая сцена хранится в основном списке layers — здесь только её имя
                        if (i != p.currentScene) { put("frames", sc.frameCount); put("layers", JSONArray().apply { sc.layers.forEach { put(layerToJson(it)) } }) }
                    })
                }
            })
        }
        put("symbols", JSONArray().apply {
            p.symbols.forEach { sym ->
                put(JSONObject().apply {
                    put("id", sym.id); put("name", sym.name); put("frames", sym.frameCount)
                    put("layers", JSONArray().apply { sym.layers.forEach { put(layerToJson(it)) } })
                })
            }
        })
        // встраиваем использованные импортированные кисти, чтобы проект открывался где угодно
        val used = LinkedHashSet<String>()
        (p.layers + p.symbols.flatMap { it.layers }).forEach { l -> l.keyframes.values.forEach { k -> k.elements.forEach { if (it is StrokeElement && it.tipId != null) used.add(it.tipId) } } }
        put("tips", JSONArray().apply { used.mapNotNull { BrushRegistry.get(it) }.forEach { put(it.toJson(withThumb = false)) } })
    }

    private fun layerToJson(l: Layer): JSONObject = JSONObject().apply {
        put("id", l.id); put("name", l.name); put("type", l.type.name)
        put("visible", l.visible); put("locked", l.locked); put("opacity", l.opacity.toDouble())
        put("blend", l.blend.name); put("guide", l.guide); put("color", l.color)
        l.bindLayer?.let { put("bindLayer", it) }
        l.bindBone?.let { put("bindBone", it) }
        if (l.isMask) put("isMask", true)
        l.parentId?.let { put("parent", it) }
        l.folderId?.let { put("folder", it) }
        if (l.collapsed) put("collapsed", true)
        if (!l.fx.isDefault) put("fx", JSONArray().apply {
            val f = l.fx
            listOf(f.blur, if (f.shadow) 1f else 0f, f.shadowDx, f.shadowDy, f.shadowBlur, f.glow, f.brightness, f.contrast, f.saturation, f.hue).forEach { put(it.toDouble()) }
            put(f.shadowColor); put(f.glowColor)
        })
        l.maskedBy?.let { put("maskedBy", it) }
        if (l.depth != 0f) put("depth", l.depth.toDouble())
        l.audio?.let { c ->
            put("audio", JSONObject().apply {
                put("id", c.id); put("name", c.name); put("file", c.fileName); put("start", c.startFrame)
                put("offset", c.offsetMs); put("dur", c.durationMs); put("vol", c.volume.toDouble()); put("muted", c.muted)
            })
        }
        put("keys", JSONArray().apply {
            l.keyframes.forEach { (f, k) ->
                put(JSONObject().apply {
                    put("f", f); put("tween", k.tween.name); put("easing", k.easing.name); put("label", k.label)
                    k.curve?.let { put("curve", it.toJson()) }
                    if (k.pins.isNotEmpty()) put("pins", JSONArray().apply { k.pins.forEach { pin -> listOf(pin.rx, pin.ry, pin.x, pin.y).forEach { put(it.toDouble()) } } })
                    k.guide?.let { g -> put("guide", JSONArray().apply { g.forEach { v -> put(v.toDouble()) } }); if (k.orientToPath) put("orient", true) }
                    if (k.separateCurves) put("sepCurves", true)
                    if (k.propCurves.isNotEmpty()) put("propCurves", JSONObject().apply { k.propCurves.forEach { (pr, c) -> put(pr.name, c.toJson()) } })
                    val t = k.transform
                    put("tr", JSONArray().apply { listOf(t.tx, t.ty, t.rot, t.sx, t.sy, t.alpha, t.px, t.py).forEach { put(it.toDouble()) } })
                    put("el", JSONArray().apply {
                        k.elements.forEach { e ->
                            when (e) {
                                is StrokeElement -> put(JSONObject().apply {
                                    put("t", "s"); put("c", e.color); put("sz", e.size.toDouble())
                                    put("k", e.kind.name); put("o", e.opacity.toDouble()); put("p", floatsToB64(e.pts))
                                    e.tipId?.let { put("tip", it) }
                                })
                                is PathFillElement -> put(JSONObject().apply {
                                    put("t", "f"); put("c", e.style.color); put("g", e.style.gradient.name); put("c2", e.style.color2)
                                    put("ang", e.style.angle.toDouble()); put("a", e.alpha.toDouble())
                                    put("cont", JSONArray().apply { e.contours.forEach { put(floatsToB64(it)) } })
                                })
                                is MeshBitmapElement -> {}
                                is TextElement -> put(JSONObject().apply {
                                    put("t", "x"); put("text", e.text); put("sz", e.size.toDouble()); put("c", e.color)
                                    put("font", e.font.name); put("b", e.bold); put("it", e.italic); put("al", e.align.name)
                                    put("m", matrixToJson(e.matrix)); put("a", e.alpha.toDouble()); put("ol", e.outline.toDouble()); put("olc", e.outlineColor)
                                })
                                is InstanceElement -> put(JSONObject().apply {
                                    put("t", "i"); put("sym", e.symbolId); put("m", matrixToJson(e.matrix))
                                    put("loop", e.loop.name); put("ff", e.firstFrame)
                                    val fx = e.effect
                                    if (!fx.isDefault) put("fx", JSONArray().apply { put(fx.alpha.toDouble()); put(fx.tint); put(fx.tintAmount.toDouble()); put(fx.brightness.toDouble()) })
                                })
                                is BitmapElement -> put(JSONObject().apply {
                                    put("t", "b"); put("m", matrixToJson(e.matrix)); put("a", e.alpha.toDouble())
                                    put("png", bitmapToB64(e))
                                })
                            }
                        }
                    })
                })
            }
        })
        put("bones", JSONArray().apply {
            l.bones.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id); put("name", b.name); b.parentId?.let { put("parent", it) }
                    put("hx", b.hx.toDouble()); put("hy", b.hy.toDouble())
                    put("len", b.length.toDouble()); put("ang", b.angle.toDouble())
                })
            }
        })
        put("poses", JSONArray().apply {
            l.poses.forEach { (f, pose) ->
                put(JSONObject().apply {
                    put("f", f); put("e", pose.easing.name); pose.curve?.let { put("curve", it.toJson()) }
                    put("d", JSONObject().apply {
                        pose.values.forEach { (id, v) -> put(id, JSONArray().apply { v.forEach { x -> put(x.toDouble()) } }) }
                    })
                })
            }
        })
    }

    fun fromJson(o: JSONObject): Project {
        val p = Project(
            id = o.getString("id"), name = o.getString("name"),
            width = o.getInt("w"), height = o.getInt("h"), fps = o.getInt("fps"),
            frameCount = o.getInt("frames"), bgColor = o.getInt("bg"),
            bgType = runCatching { BgType.valueOf(o.getString("bgType")) }.getOrDefault(BgType.SOLID),
            created = o.optLong("created", System.currentTimeMillis()),
            modified = o.optLong("modified", System.currentTimeMillis())
        )
        o.optJSONArray("palette")?.let { a -> p.palette.clear(); for (i in 0 until a.length()) p.palette.add(a.getInt(i)) }
        o.optJSONArray("tips")?.let { a ->
            for (i in 0 until a.length()) runCatching {
                val b = CustomBrush.fromJson(a.getJSONObject(i))
                if (BrushRegistry.get(b.id) == null) BrushRegistry.register(b)
            }
        }
        o.optJSONArray("guidesX")?.let { a -> for (i in 0 until a.length()) p.guidesX.add(a.getDouble(i).toFloat()) }
        o.optJSONArray("guidesY")?.let { a -> for (i in 0 until a.length()) p.guidesY.add(a.getDouble(i).toFloat()) }
        o.optJSONArray("scenes")?.let { arr ->
            p.currentScene = o.optInt("currentScene", 0)
            for (i in 0 until arr.length()) {
                val so = arr.getJSONObject(i)
                val sc = Scene(so.getString("id"), so.getString("name"), frameCount = so.optInt("frames", 48))
                so.optJSONArray("layers")?.let { ls -> for (j in 0 until ls.length()) sc.layers.add(layerFromJson(ls.getJSONObject(j))) }
                p.scenes.add(sc)
            }
        }
        o.optJSONArray("symbols")?.let { arr ->
            for (i in 0 until arr.length()) {
                val so = arr.getJSONObject(i)
                val sym = Symbol(so.getString("id"), so.getString("name"), frameCount = so.optInt("frames", 1))
                val ls = so.getJSONArray("layers")
                for (j in 0 until ls.length()) sym.layers.add(layerFromJson(ls.getJSONObject(j)))
                p.symbols.add(sym)
            }
        }
        val layers = o.getJSONArray("layers")
        for (i in 0 until layers.length()) p.layers.add(layerFromJson(layers.getJSONObject(i)))
        return p
    }

    private fun layerFromJson(o: JSONObject): Layer {
        val l = Layer(
            id = o.getString("id"), name = o.getString("name"),
            type = runCatching { LayerType.valueOf(o.getString("type")) }.getOrDefault(LayerType.DRAWING),
            visible = o.optBoolean("visible", true), locked = o.optBoolean("locked", false),
            opacity = o.optDouble("opacity", 1.0).toFloat(),
            blend = runCatching { BlendMode.valueOf(o.getString("blend")) }.getOrDefault(BlendMode.NORMAL),
            guide = o.optBoolean("guide", false), color = o.optInt("color", 0xFF4FC3F7.toInt()),
            bindLayer = if (o.has("bindLayer")) o.getString("bindLayer") else null,
            bindBone = if (o.has("bindBone")) o.getString("bindBone") else null,
            isMask = o.optBoolean("isMask", false),
            parentId = if (o.has("parent")) o.getString("parent") else null,
            folderId = if (o.has("folder")) o.getString("folder") else null,
            collapsed = o.optBoolean("collapsed", false),
            fx = o.optJSONArray("fx")?.let { a ->
                fun v(i: Int) = a.getDouble(i).toFloat()
                LayerFx(v(0), v(1) > 0.5f, v(2), v(3), v(4), a.getInt(10), v(5), a.getInt(11), v(6), v(7), v(8), v(9))
            } ?: LayerFx(),
            maskedBy = if (o.has("maskedBy")) o.getString("maskedBy") else null,
            depth = o.optDouble("depth", 0.0).toFloat(),
            audio = o.optJSONObject("audio")?.let { a ->
                AudioClip(
                    a.getString("id"), a.getString("name"), a.getString("file"), a.optInt("start", 0),
                    a.optLong("offset", 0), a.getLong("dur"), a.optDouble("vol", 1.0).toFloat(), a.optBoolean("muted", false)
                )
            }
        )
        val keys = o.optJSONArray("keys") ?: JSONArray()
        for (i in 0 until keys.length()) {
            val k = keys.getJSONObject(i)
            val tr = k.getJSONArray("tr")
            fun t(j: Int) = tr.getDouble(j).toFloat()
            val kf = Keyframe(
                tween = runCatching { TweenType.valueOf(k.getString("tween")) }.getOrDefault(TweenType.NONE),
                easing = runCatching { Easing.valueOf(k.getString("easing")) }.getOrDefault(Easing.LINEAR),
                transform = Transform(t(0), t(1), t(2), t(3), t(4), t(5), t(6), t(7)),
                label = k.optString("label", ""),
                curve = k.optJSONArray("curve")?.let { EaseCurve.fromJson(it) },
                separateCurves = k.optBoolean("sepCurves", false),
                guide = k.optJSONArray("guide")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } },
                orientToPath = k.optBoolean("orient", false),
                pins = k.optJSONArray("pins")?.let { a -> (0 until a.length() / 4).map { i -> WarpPin(a.getDouble(i * 4).toFloat(), a.getDouble(i * 4 + 1).toFloat(), a.getDouble(i * 4 + 2).toFloat(), a.getDouble(i * 4 + 3).toFloat()) } } ?: emptyList()
            )
            k.optJSONObject("propCurves")?.let { pc ->
                pc.keys().forEach { name ->
                    val prop = runCatching { CurveProp.valueOf(name) }.getOrNull()
                    val c = pc.optJSONArray(name)?.let { EaseCurve.fromJson(it) }
                    if (prop != null && c != null) kf.propCurves[prop] = c
                }
            }
            val els = k.getJSONArray("el")
            for (j in 0 until els.length()) {
                val e = els.getJSONObject(j)
                when (e.getString("t")) {
                    "s" -> kf.elements.add(
                        StrokeElement(
                            b64ToFloats(e.getString("p")), e.getInt("c"), e.getDouble("sz").toFloat(),
                            runCatching { BrushKind.valueOf(e.getString("k")) }.getOrDefault(BrushKind.PEN),
                            e.getDouble("o").toFloat(),
                            if (e.has("tip")) e.getString("tip") else null
                        )
                    )
                    "f" -> {
                        val ca = e.getJSONArray("cont")
                        kf.elements.add(PathFillElement(
                            (0 until ca.length()).map { b64ToFloats(ca.getString(it)) },
                            FillStyle(e.getInt("c"), runCatching { GradientType.valueOf(e.getString("g")) }.getOrDefault(GradientType.NONE), e.optInt("c2", -1), e.optDouble("ang", 90.0).toFloat()),
                            e.optDouble("a", 1.0).toFloat()
                        ))
                    }
                    "x" -> kf.elements.add(TextElement(
                        e.getString("text"), e.getDouble("sz").toFloat(), e.getInt("c"),
                        runCatching { TextFont.valueOf(e.getString("font")) }.getOrDefault(TextFont.SANS),
                        e.optBoolean("b", false), e.optBoolean("it", false),
                        runCatching { TextAlign.valueOf(e.getString("al")) }.getOrDefault(TextAlign.LEFT),
                        jsonToMatrix(e.getJSONArray("m")), e.optDouble("a", 1.0).toFloat(),
                        e.optDouble("ol", 0.0).toFloat(), e.optInt("olc", 0xFF000000.toInt())
                    ))
                    "i" -> {
                        val fx = e.optJSONArray("fx")
                        kf.elements.add(InstanceElement(
                            e.getString("sym"), jsonToMatrix(e.getJSONArray("m")),
                            runCatching { LoopMode.valueOf(e.getString("loop")) }.getOrDefault(LoopMode.LOOP), e.optInt("ff", 0),
                            if (fx == null) ColorEffect() else ColorEffect(fx.getDouble(0).toFloat(), fx.getInt(1), fx.getDouble(2).toFloat(), fx.getDouble(3).toFloat())
                        ))
                    }
                    "b" -> {
                        val b64 = e.getString("png")
                        val bytes = Base64.decode(b64, Base64.NO_WRAP)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                        kf.elements.add(BitmapElement(bmp, jsonToMatrix(e.getJSONArray("m")), e.getDouble("a").toFloat()).also { it.encoded = b64 })
                    }
                }
            }
            l.keyframes[k.getInt("f")] = kf
        }
        val bones = o.optJSONArray("bones") ?: JSONArray()
        for (i in 0 until bones.length()) {
            val b = bones.getJSONObject(i)
            l.bones.add(
                Bone(
                    b.getString("id"), b.getString("name"), if (b.has("parent")) b.getString("parent") else null,
                    b.getDouble("hx").toFloat(), b.getDouble("hy").toFloat(),
                    b.getDouble("len").toFloat(), b.getDouble("ang").toFloat()
                )
            )
        }
        val poses = o.optJSONArray("poses") ?: JSONArray()
        for (i in 0 until poses.length()) {
            val po = poses.getJSONObject(i)
            val pose = Pose(
                easing = runCatching { Easing.valueOf(po.getString("e")) }.getOrDefault(Easing.EASE_IN_OUT),
                curve = po.optJSONArray("curve")?.let { EaseCurve.fromJson(it) }
            )
            val d = po.getJSONObject("d")
            d.keys().forEach { id ->
                val a = d.getJSONArray(id)
                pose.values[id] = FloatArray(3) { j -> if (j < a.length()) a.getDouble(j).toFloat() else 0f }
            }
            l.poses[po.getInt("f")] = pose
        }
        return l
    }
}
