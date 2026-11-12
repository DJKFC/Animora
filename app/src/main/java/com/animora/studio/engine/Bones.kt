package com.animora.studio.engine

import android.graphics.Matrix
import com.animora.studio.model.Bone
import com.animora.studio.model.Layer
import com.animora.studio.model.Project
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
    fun bindMatrix(project: Project, layer: Layer, frame: Int): Matrix? {
        val bl = project.layer(layer.bindLayer) ?: return null
        val boneId = layer.bindBone ?: return null
        val rest = bl.bones.firstOrNull { it.id == boneId } ?: return null
        val world = solve(bl.bones, poseAt(bl, frame))[boneId] ?: return null
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
