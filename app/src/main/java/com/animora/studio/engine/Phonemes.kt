package com.animora.studio.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Формы рта (виземы) по классической схеме мультипликации (Престон Блэр).
 * fallback — чем заменить форму, если её не нарисовали.
 */
enum class Viseme(val title: String, val hint: String) {
    REST("Покой", "тишина, рот закрыт"),
    MBP("М Б П", "губы сомкнуты"),
    AI("А И", "широко открыт"),
    E("Э Е", "растянут, видны зубы"),
    O("О", "округлый"),
    U("У Ю", "трубочкой"),
    FV("Ф В", "зубы на нижней губе"),
    L("Л Т Д Н", "приоткрыт, язык у зубов"),
    ETC("С З К Г Р…", "прочие согласные, чуть открыт");

    val fallback: List<Viseme> get() = when (this) {
        REST -> listOf(MBP, ETC)
        MBP -> listOf(REST)
        AI -> listOf(E, O, L, ETC)
        E -> listOf(ETC, AI, L)
        O -> listOf(U, AI)
        U -> listOf(O, MBP)
        FV -> listOf(ETC, E, MBP)
        L -> listOf(ETC, AI, E)
        ETC -> listOf(E, L, AI)
    }
}

/**
 * Распознавание фонемных групп по звуку без словаря и интернета:
 * громкость, частота пересечений нуля, распределение энергии по полосам и
 * оценка первых двух формант (F1 — открытость рта, F2 — растянутость/округлость).
 */
object Phonemes {

    class FrameInfo(val energy: Float, val zcr: Float, val f1: Float, val f2: Float, val hiRatio: Float, val sibilance: Float, val lowRatio: Float)

    /** Виземы по кадрам анимации. start — первый сэмпл, frames — сколько кадров. */
    fun analyze(samples: ShortArray, rate: Int, start: Int, frames: Int, fps: Int, sensitivity: Float = 1f): Array<Viseme> {
        val info = features(samples, rate, start, frames, fps)
        return classify(info, sensitivity)
    }

    fun features(samples: ShortArray, rate: Int, start: Int, frames: Int, fps: Int): Array<FrameInfo> {
        val hop = rate.toDouble() / fps
        var n = 1024
        while (n < hop * 1.1 && n < 4096) n *= 2
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
        val re = FloatArray(n); val im = FloatArray(n)
        val binHz = rate.toFloat() / n
        val half = n / 2
        val mag = FloatArray(half)
        val smooth = FloatArray(half)
        return Array(frames) { f ->
            val center = start + ((f + 0.5) * hop).toInt()
            val a = center - n / 2
            var sum = 0.0; var cnt = 0; var zc = 0; var prev = 0
            for (i in 0 until n) {
                val j = a + i
                val v = if (j in samples.indices) samples[j].toInt() else 0
                re[i] = v / 32768f * win[i]; im[i] = 0f
                // энергия и пересечения нуля — по «своему» отрезку кадра
                if (i >= (n - hop) / 2 && i < (n + hop) / 2) {
                    sum += (v / 32768.0) * (v / 32768.0); cnt++
                    if (cnt > 1 && ((v >= 0) != (prev >= 0)) && abs(v - prev) > 40) zc++
                    prev = v
                }
            }
            val rms = if (cnt == 0) 0f else sqrt(sum / cnt).toFloat()
            fft(re, im)
            for (k in 0 until half) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
            // огибающая спектра: скользящее среднее ~ 180 Гц, чтобы гармоники голоса не мешали искать форманты
            val w = max(2, (180f / binHz).toInt())
            var acc = 0f
            for (k in 0 until half) {
                acc += mag[k]; if (k - w >= 0) acc -= mag[k - w]
                smooth[max(0, k - w / 2)] = acc / w
            }
            fun band(lo: Float, hi: Float): Float {
                var s = 0f
                for (k in (lo / binHz).toInt().coerceIn(0, half - 1)..(hi / binHz).toInt().coerceIn(0, half - 1)) s += mag[k] * mag[k]
                return s
            }
            fun peak(lo: Float, hi: Float): Float {
                var best = lo; var bv = -1f
                for (k in (lo / binHz).toInt().coerceIn(1, half - 2)..(hi / binHz).toInt().coerceIn(1, half - 2)) {
                    if (smooth[k] > bv) { bv = smooth[k]; best = k * binHz }
                }
                return best
            }
            val low = band(60f, 300f); val b1 = band(300f, 1000f); val b2 = band(1000f, 2800f)
            val b3 = band(2800f, 5000f); val b4 = band(5000f, min(11000f, rate / 2f - 1))
            val total = max(1e-9f, low + b1 + b2 + b3 + b4)
            val f1 = peak(250f, 1000f)
            val f2 = peak(max(850f, f1 + 250f), 3000f)
            FrameInfo(
                rms, if (cnt == 0) 0f else zc.toFloat() / cnt, f1, f2,
                (b3 + b4) / total, b4 / total, (low + b1) / total
            )
        }
    }

    fun classify(info: Array<FrameInfo>, sensitivity: Float): Array<Viseme> {
        if (info.isEmpty()) return emptyArray()
        val sorted = info.map { it.energy }.sorted()
        val ref = max(1e-4f, sorted[(sorted.size * 0.95f).toInt().coerceIn(0, sorted.size - 1)])
        val e = FloatArray(info.size) { info[it].energy / ref * sensitivity }
        val out = Array(info.size) { i ->
            val fi = info[i]; val v = e[i]
            when {
                v < 0.08f -> Viseme.REST
                // шипящие и свистящие (С, З, Ш, Щ) — много энергии выше 5 кГц
                fi.sibilance > 0.60f && fi.zcr > 0.12f -> Viseme.ETC
                // глухие фрикативные (Ф, В, Х) — шум без голоса, энергия «размазана» вверх
                fi.hiRatio > 0.40f && fi.zcr > 0.10f -> Viseme.FV
                // носовые и смычки (М, Н, Б) — тихий голос, вся энергия внизу
                v < 0.16f && fi.lowRatio > 0.85f -> Viseme.MBP
                v < 0.16f -> Viseme.ETC
                // гласные по формантам
                fi.f1 > 680f -> Viseme.AI
                fi.f2 > 1750f && fi.f1 < 620f -> Viseme.E
                fi.f1 < 430f && fi.f2 < 1150f -> Viseme.U
                fi.f2 < 1350f -> Viseme.O
                fi.f1 > 560f -> Viseme.AI
                else -> Viseme.L
            }
        }
        // смычка (Б, П, М) — короткий провал громкости посреди речи
        for (i in 1 until out.size - 1) {
            if (out[i] != Viseme.REST && out[i] != Viseme.MBP && e[i] < 0.45f * min(e[i - 1], e[i + 1]) && e[i - 1] > 0.25f && e[i + 1] > 0.25f) out[i] = Viseme.MBP
        }
        // сглаживание: одиночный кадр между двумя одинаковыми поглощается (кроме смычек)
        for (i in 1 until out.size - 1) {
            if (out[i - 1] == out[i + 1] && out[i] != out[i - 1] && out[i] != Viseme.MBP) out[i] = out[i - 1]
        }
        return out
    }

    /** Простое БПФ (радикс-2, на месте). Размер — степень двойки. */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Какую нарисованную форму показать для виземы (с учётом замен). */
    fun resolve(v: Viseme, assigned: Map<Viseme, Int>): Int? {
        assigned[v]?.let { return it }
        for (f in v.fallback) assigned[f]?.let { return it }
        return assigned[Viseme.ETC] ?: assigned[Viseme.AI] ?: assigned.values.firstOrNull()
    }
}
