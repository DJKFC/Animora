package com.animora.studio.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import com.animora.studio.model.AudioClip
import com.animora.studio.model.LayerType
import com.animora.studio.model.Project
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Моно PCM 16 бит. */
class Pcm(val samples: ShortArray, val rate: Int) {
    val durationMs: Long get() = samples.size * 1000L / rate
}

object AudioDecoder {
    const val RATE = 44100

    /** Декодирует любой поддерживаемый Android формат (mp3, m4a/aac, ogg, wav, flac…) в моно 44.1 кГц. */
    fun decode(file: File): Pcm {
        val ex = MediaExtractor()
        ex.setDataSource(file.path)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) { track = i; break }
        }
        require(track >= 0) { "в файле нет звуковой дорожки" }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val out = ShortList()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            when {
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT); rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                oi >= 0 -> {
                    val buf = codec.getOutputBuffer(oi)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = sb.remaining() / max(1, channels)
                        for (i in 0 until frames) {
                            var sum = 0
                            for (c in 0 until channels) sum += sb.get(i * channels + c)
                            out.add((sum / channels).toShort())
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
        }
        codec.stop(); codec.release(); ex.release()
        return resample(Pcm(out.toArray(), rate), RATE)
    }

    fun resample(p: Pcm, rate: Int): Pcm {
        if (p.rate == rate || p.samples.isEmpty()) return Pcm(p.samples, rate)
        val n = (p.samples.size.toLong() * rate / p.rate).toInt()
        val out = ShortArray(n)
        val step = p.rate.toDouble() / rate
        for (i in 0 until n) {
            val pos = i * step
            val a = pos.toInt().coerceAtMost(p.samples.size - 1); val b = min(a + 1, p.samples.size - 1)
            val t = pos - a
            out[i] = (p.samples[a] * (1 - t) + p.samples[b] * t).toInt().toShort()
        }
        return Pcm(out, rate)
    }

    private class ShortList {
        var data = ShortArray(1 shl 16); var size = 0
        fun add(v: Short) { if (size == data.size) data = data.copyOf(data.size * 2); data[size++] = v }
        fun toArray() = data.copyOf(size)
    }
}

/** Кэш декодированного звука и волны по id клипа. */
object AudioCache {
    private val pcm = ConcurrentHashMap<String, Pcm>()
    private val peaks = ConcurrentHashMap<String, FloatArray>()
    const val PEAKS_PER_SEC = 100

    fun file(dir: File, c: AudioClip) = File(dir, c.fileName)

    fun pcm(dir: File, c: AudioClip): Pcm? = pcm[c.id] ?: runCatching { AudioDecoder.decode(file(dir, c)) }.getOrNull()?.also { pcm[c.id] = it }

    fun cachedPeaks(c: AudioClip): FloatArray? = peaks[c.id]

    fun peaks(dir: File, c: AudioClip): FloatArray? = peaks[c.id] ?: pcm(dir, c)?.let { p ->
        val win = p.rate / PEAKS_PER_SEC
        val n = p.samples.size / max(1, win) + 1
        val out = FloatArray(n)
        for (i in 0 until n) {
            var m = 0
            val a = i * win; val b = min(p.samples.size, a + win)
            for (j in a until b) m = max(m, abs(p.samples[j].toInt()))
            out[i] = m / 32768f
        }
        out.also { peaks[c.id] = it }
    }

    /** Громкость (RMS) по кадрам анимации — для lip-sync. */
    fun frameRms(dir: File, c: AudioClip, fps: Int): FloatArray? {
        val p = pcm(dir, c) ?: return null
        val frames = c.frames(fps)
        val out = FloatArray(frames)
        val win = p.rate / fps
        val off = (c.offsetMs * p.rate / 1000).toInt()
        for (f in 0 until frames) {
            val a = off + f * win; val b = min(p.samples.size, a + win)
            var sum = 0.0; var n = 0
            for (j in a until b) { val v = p.samples[j] / 32768.0; sum += v * v; n++ }
            out[f] = if (n == 0) 0f else sqrt(sum / n).toFloat()
        }
        return out
    }
}

/** Синхронное с таймлайном воспроизведение звуковых слоёв + «звук при прокрутке». */
class AudioPlayback(private val dirProvider: () -> File) {
    private val players = HashMap<String, MediaPlayer>()
    private var scrubTrack: AudioTrack? = null

    fun start(project: Project, frame: Int) {
        stop()
        val dir = dirProvider()
        for (l in project.layers) {
            if (l.type != LayerType.AUDIO || !l.visible) continue
            val c = l.audio ?: continue
            if (c.muted) continue
            val posMs = (frame - c.startFrame) * 1000L / project.fps + c.offsetMs
            if (posMs >= c.durationMs) continue
            runCatching {
                val mp = MediaPlayer()
                mp.setDataSource(AudioCache.file(dir, c).path)
                mp.prepare()
                mp.setVolume(c.volume, c.volume)
                if (posMs >= c.offsetMs) { mp.seekTo(posMs.toInt()); mp.start() }
                else {
                    // звук начнётся позже — запускаем с задержкой
                    mp.seekTo(c.offsetMs.toInt())
                    val delay = c.offsetMs - posMs
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { if (players[c.id] === mp) mp.start() } }, delay)
                }
                players[c.id] = mp
            }
        }
    }

    fun stop() {
        players.values.forEach { runCatching { it.stop(); it.release() } }
        players.clear()
    }

    /** Короткий отрывок длиной в кадр — слышно звук при перемотке. */
    fun scrub(project: Project, frame: Int) {
        val dir = dirProvider()
        val mix = ShortArray(AudioDecoder.RATE / project.fps)
        var any = false
        for (l in project.layers) {
            if (l.type != LayerType.AUDIO || !l.visible) continue
            val c = l.audio ?: continue
            if (c.muted) continue
            val p = AudioCache.pcm(dir, c) ?: continue
            val start = ((frame - c.startFrame) * 1000L / project.fps + c.offsetMs) * p.rate / 1000
            if (start < 0 || start >= p.samples.size) continue
            for (i in mix.indices) {
                val j = (start + i).toInt(); if (j >= p.samples.size) break
                mix[i] = (mix[i] + p.samples[j] * c.volume).toInt().coerceIn(-32768, 32767).toShort()
            }
            any = true
        }
        if (!any) return
        runCatching {
            scrubTrack?.release()
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AudioDecoder.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(mix.size * 2)
                .build()
            t.write(mix, 0, mix.size)
            t.play()
            scrubTrack = t
        }
    }

    fun release() { stop(); scrubTrack?.release(); scrubTrack = null }
}

/** Сведение всех звуковых слоёв в одну дорожку для экспорта видео. */
object AudioMixer {
    fun mix(project: Project, dir: File, fromFrame: Int, frames: Int): ShortArray? {
        val total = (frames.toLong() * AudioDecoder.RATE / project.fps).toInt()
        val acc = FloatArray(total)
        var any = false
        for (l in project.layers) {
            if (l.type != LayerType.AUDIO || !l.visible) continue
            val c = l.audio ?: continue
            if (c.muted) continue
            val p = AudioCache.pcm(dir, c) ?: continue
            val clipStartSample = ((c.startFrame - fromFrame).toLong() * AudioDecoder.RATE / project.fps).toInt()
            val off = (c.offsetMs * AudioDecoder.RATE / 1000).toInt()
            for (i in off until p.samples.size) {
                val d = clipStartSample + i - off
                if (d < 0) continue
                if (d >= total) break
                acc[d] += p.samples[i] * c.volume
            }
            any = true
        }
        if (!any) return null
        return ShortArray(total) { acc[it].toInt().coerceIn(-32768, 32767).toShort() }
    }
}
