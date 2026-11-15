package com.animora.studio.engine

import android.graphics.Matrix
import com.animora.studio.model.Bone
import com.animora.studio.model.Layer
import com.animora.studio.model.Project
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class BoneWorld(val hx: Float, val hy: Float, val angle: Float, val length: Float) {
    val tailX: Float get() = hx + length * cos(Math.toRadians(angle.toDouble())).toFloat()
    val tailY: Float get() = hy + length * sin(Math.toRadians(angle.toDouble())).toFloat()
}

/** Костная система: прямая кинематика, интерполяция поз, обратная кинематика (CCD). */
object Bones {

    /** Интерполированная поза кадра (с учётом сглаживания ключа позы). */
    fun poseAt(layer: Layer, frame: Int): Map<String, FloatArray>? {
        val a = layer.poses.floorEntry(frame) ?: return layer.poses.firstEntry()?.value?.values
        val b = layer.poses.higherEntry(a.key) ?: return a.value.values
        if (frame <= a.key) return a.value.values
        val t = a.value.ease((frame - a.key).toFloat() / (b.key - a.key))
        val out = HashMap<String, FloatArray>()
        val keys = a.value.values.keys + b.value.values.keys
        for (k in keys) {
            val va = a.value.values[k] ?: FloatArray(3)
            val vb = b.value.values[k] ?: FloatArray(3)
            out[k] = floatArrayOf(
                va[0] + normalizeDeg(vb[0] - va[0]) * t,
                va[1] + (vb[1] - va[1]) * t,
                va[2] + (vb[2] - va[2]) * t
            )
        }
        return out
    }

    private const val SUB = 4

    /**
     * Поза для показа: ключевая поза плюс пружины. Кости с пружиной отстают от движения родителя,
     * перелетают и покачиваются (волосы, хвост, уши, одежда). Симуляция детерминирована:
     * каждый раз считается заново с кадра (frame − 4 c), поэтому перемотка и экспорт дают один результат.
     */
    fun displayPose(layer: Layer, frame: Int, fps: Int): Map<String, FloatArray>? {
        if (layer.bones.none { it.spring > 0.001f } || layer.poses.isEmpty()) return poseAt(layer, frame)
        // один и тот же кадр запрашивают все привязанные к костям слои — считаем один раз
        var sig = layer.bones.hashCode() * 31 + fps
        for ((f, p) in layer.poses) {
            sig = sig * 31 + f; sig = sig * 31 + p.easing.hashCode() + System.identityHashCode(p.curve)
            for ((id, v) in p.values) sig = sig * 31 + id.hashCode() * 7 + v.contentHashCode()
        }
        val key = "${layer.id}:$frame:$sig"
        synchronized(springCache) { springCache[key]?.let { return it } }
        val r = simulate(layer, frame, fps)
        synchronized(springCache) { springCache[key] = r }
        return r
    }

    private val springCache = object : LinkedHashMap<String, Map<String, FloatArray>?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, FloatArray>?>?) = size > 96
    }

    private fun simulate(layer: Layer, frame: Int, fps: Int): Map<String, FloatArray>? {
        val raw = poseAt(layer, frame)
        val first = layer.poses.firstKey()
        val start = max(first, frame - fps.coerceAtLeast(1) * 4)
        if (frame <= start) return raw
        val order = topo(layer.bones).filter { it.spring > 0.001f }
        val n = order.size
        val off = FloatArray(n); val vel = FloatArray(n)                 // отклонение от ключевой позы, °, и скорость, °/с
        val prevT = FloatArray(n); val prevTV = FloatArray(n)
        val prevHx = FloatArray(n); val prevHy = FloatArray(n); val prevHvx = FloatArray(n); val prevHvy = FloatArray(n)
        var steps = 0
        val dt = 1f / (fps.coerceAtLeast(1) * SUB)
        var pa = poseAt(layer, start) ?: emptyMap()
        var values = HashMap<String, FloatArray>()
        for (f in start until frame) {
            val pb = poseAt(layer, f + 1) ?: emptyMap()
            for (s in 1..SUB) {
                val t = s.toFloat() / SUB
                values = lerpPose(pa, pb, t)
                for ((i, b) in order.withIndex()) values.getOrPut(b.id) { FloatArray(3) }[0] += off[i]
                for ((i, b) in order.withIndex()) {
                    val w = solve(layer.bones, values)[b.id] ?: continue
                    val target = w.angle - off[i]                          // куда смотрела бы кость без пружины
                    // производные цели и основания кости — конечные разности
                    val tv = if (steps > 0) normalizeDeg(target - prevT[i]) / dt else 0f
                    val ta = if (steps > 1) (tv - prevTV[i]) / dt else 0f
                    val hvx = if (steps > 0) (w.hx - prevHx[i]) / dt else 0f
                    val hvy = if (steps > 0) (w.hy - prevHy[i]) / dt else 0f
                    val hax = if (steps > 1) (hvx - prevHvx[i]) / dt else 0f
                    val hay = if (steps > 1) (hvy - prevHvy[i]) / dt else 0f
                    prevT[i] = target; prevTV[i] = tv; prevHx[i] = w.hx; prevHy[i] = w.hy; prevHvx[i] = hvx; prevHvy[i] = hvy
                    val soft = b.spring.coerceIn(0f, 1f)
                    val k = 900f * (1f - soft) * (1f - soft) + 25f            // жёсткость, 1/с²
                    val c = 2f * sqrt(k) * (0.06f + 0.94f * b.damping.coerceIn(0f, 1f))
                    val a = Math.toRadians(w.angle.toDouble())
                    // инерция: ускорение основания раскачивает кость как маятник
                    val inertial = Math.toDegrees(((hax * sin(a) - hay * cos(a)) / max(10f, b.length)).toDouble()).toFloat() * soft
                    val acc = -k * off[i] - c * vel[i] - ta * soft + inertial
                    vel[i] += acc * dt
                    off[i] = (off[i] + vel[i] * dt).coerceIn(-150f, 150f)
                    values.getOrPut(b.id) { FloatArray(3) }[0] = (pa[b.id]?.get(0) ?: 0f) + ((pb[b.id]?.get(0) ?: 0f) - (pa[b.id]?.get(0) ?: 0f)) * t + off[i]
                }
                steps++
            }
            pa = pb
        }
        val out = HashMap<String, FloatArray>()
        (raw ?: emptyMap()).forEach { (k, v) -> out[k] = v.copyOf() }
        for ((i, b) in order.withIndex()) out.getOrPut(b.id) { FloatArray(3) }[0] += off[i]
        return out
    }

    private fun lerpPose(a: Map<String, FloatArray>, b: Map<String, FloatArray>, t: Float): HashMap<String, FloatArray> {
        val out = HashMap<String, FloatArray>()
        for (k in a.keys + b.keys) {
            val va = a[k] ?: FloatArray(3); val vb = b[k] ?: FloatArray(3)
            out[k] = floatArrayOf(va[0] + normalizeDeg(vb[0] - va[0]) * t, va[1] + (vb[1] - va[1]) * t, va[2] + (vb[2] - va[2]) * t)
        }
        return out
    }

    /** Кости в порядке «родитель раньше детей». */
    fun topo(bones: List<Bone>): List<Bone> {
        val byId = bones.associateBy { it.id }
        fun depth(b: Bone): Int { var d = 0; var p = b.parentId?.let { byId[it] }; while (p != null && d < 64) { d++; p = p.parentId?.let { byId[it] } }; return d }
        return bones.sortedBy { depth(it) }
    }

    fun solve(bones: List<Bone>, d: Map<String, FloatArray>?): HashMap<String, BoneWorld> {
        val byId = bones.associateBy { it.id }
        val out = HashMap<String, BoneWorld>()
        fun calc(b: Bone, depth: Int): BoneWorld {
            out[b.id]?.let { return it }
            val dd = d?.get(b.id)
            val rot = dd?.get(0) ?: 0f
            val p = b.parentId?.let { byId[it] }
            val w = if (p == null || depth > 64) {
                BoneWorld(b.hx + (dd?.get(1) ?: 0f), b.hy + (dd?.get(2) ?: 0f), b.angle + rot, b.length)
            } else {
                val pw = calc(p, depth + 1)
                val rx = b.hx - p.hx; val ry = b.hy - p.hy
                val a0 = Math.toRadians(-p.angle.toDouble())
                val lx = (rx * cos(a0) - ry * sin(a0)).toFloat()
                val ly = (rx * sin(a0) + ry * cos(a0)).toFloat()
                val a1 = Math.toRadians(pw.angle.toDouble())
                val wx = pw.hx + (lx * cos(a1) - ly * sin(a1)).toFloat()
                val wy = pw.hy + (lx * sin(a1) + ly * cos(a1)).toFloat()
                BoneWorld(wx, wy, pw.angle + (b.angle - p.angle) + rot, b.length)
            }
            out[b.id] = w
            return w
        }
        bones.forEach { calc(it, 0) }
        return out
    }

    /** Матрица, переносящая привязанный слой из позы покоя в текущую позу кости. */
    fun bindMatrix(project: Project, layer: Layer, frame: Int, scope: List<Layer>? = null): Matrix? {
        val bl = (if (scope != null) scope.firstOrNull { it.id == layer.bindLayer } else project.layer(layer.bindLayer)) ?: return null
        val boneId = layer.bindBone ?: return null
        val rest = bl.bones.firstOrNull { it.id == boneId } ?: return null
        val world = solve(bl.bones, displayPose(bl, frame, project.fps))[boneId] ?: return null
        return Matrix().apply {
            setTranslate(-rest.hx, -rest.hy)
            postRotate(world.angle - rest.angle)
            postTranslate(world.hx, world.hy)
        }
    }

    /** CCD-IK: тянем конец кости endId к цели, вращая до maxChain костей вверх по цепочке. */
    fun ik(bones: List<Bone>, values: HashMap<String, FloatArray>, endId: String, tx: Float, ty: Float, maxChain: Int = 3, iterations: Int = 12) {
        val byId = bones.associateBy { it.id }
        val chain = ArrayList<Bone>()
        var b = byId[endId]
        while (b != null && chain.size < maxChain) {
            chain.add(b); b = b.parentId?.let { byId[it] }
        }
        repeat(iterations) {
            for (bone in chain) {
                val w = solve(bones, values)
                val end = w[endId] ?: return
                val bw = w[bone.id] ?: continue
                val a1 = atan2(end.tailY - bw.hy, end.tailX - bw.hx)
                val a2 = atan2(ty - bw.hy, tx - bw.hx)
                val delta = normalizeDeg(Math.toDegrees((a2 - a1).toDouble()).toFloat())
                values.getOrPut(bone.id) { FloatArray(3) }[0] += delta
            }
        }
    }
}
