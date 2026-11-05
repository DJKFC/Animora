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
        // встраиваем использованные импортированные кисти, чтобы проект открывался где угодно
        val used = LinkedHashSet<String>()
        p.layers.forEach { l -> l.keyframes.values.forEach { k -> k.elements.forEach { if (it is StrokeElement && it.tipId != null) used.add(it.tipId) } } }
        put("tips", JSONArray().apply { used.mapNotNull { BrushRegistry.get(it) }.forEach { put(it.toJson(withThumb = false)) } })
    }

    private fun layerToJson(l: Layer): JSONObject = JSONObject().apply {
        put("id", l.id); put("name", l.name); put("type", l.type.name)
        put("visible", l.visible); put("locked", l.locked); put("opacity", l.opacity.toDouble())
        put("blend", l.blend.name); put("guide", l.guide); put("color", l.color)
        l.bindLayer?.let { put("bindLayer", it) }
        l.bindBone?.let { put("bindBone", it) }
        put("keys", JSONArray().apply {
            l.keyframes.forEach { (f, k) ->
                put(JSONObject().apply {
                    put("f", f); put("tween", k.tween.name); put("easing", k.easing.name); put("label", k.label)
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
                    put("f", f); put("e", pose.easing.name)
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
            bindBone = if (o.has("bindBone")) o.getString("bindBone") else null
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
                label = k.optString("label", "")
            )
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
            val pose = Pose(easing = runCatching { Easing.valueOf(po.getString("e")) }.getOrDefault(Easing.EASE_IN_OUT))
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
