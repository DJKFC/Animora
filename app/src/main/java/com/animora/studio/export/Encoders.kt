package com.animora.studio.export

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.OutputStream

/** Анимированный GIF89a: локальная палитра на кадр (популярность 15-бит цветов) + LZW. */
class GifEncoder(
    private val out: OutputStream,
    private val w: Int,
    private val h: Int,
    private val delayCs: Int,
    private val transparent: Boolean,
    private val loops: Int = 0
) {
    fun start() {
        out.write("GIF89a".toByteArray())
        le16(w); le16(h)
        out.write(0x00); out.write(0); out.write(0)
        // NETSCAPE2.0 — зацикливание
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(0x03, 0x01)); le16(loops); out.write(0)
    }

    fun addFrame(bmp: Bitmap) {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val transIndex = 255
        // гистограмма по 5 бит на канал
        val count = IntArray(32768); val sr = LongArray(32768); val sg = LongArray(32768); val sb = LongArray(32768)
        for (c in px) {
            if (transparent && (c ushr 24) < 128) continue
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val k = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            count[k]++; sr[k] += r.toLong(); sg[k] += g.toLong(); sb[k] += b.toLong()
        }
        val used = (0 until 32768).filter { count[it] > 0 }.sortedByDescending { count[it] }
        val maxColors = if (transparent) 255 else 256
        val pal = used.take(maxColors)
        val pr = IntArray(256); val pg = IntArray(256); val pb = IntArray(256)
        pal.forEachIndexed { i, k ->
            pr[i] = (sr[k] / count[k]).toInt(); pg[i] = (sg[k] / count[k]).toInt(); pb[i] = (sb[k] / count[k]).toInt()
        }
        val map = IntArray(32768)
        for (k in used) {
            val r = (sr[k] / count[k]).toInt(); val g = (sg[k] / count[k]).toInt(); val b = (sb[k] / count[k]).toInt()
            var best = 0; var bd = Int.MAX_VALUE
            for (i in pal.indices) {
                val dr = r - pr[i]; val dg = g - pg[i]; val db = b - pb[i]
                val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (d < bd) { bd = d; best = i; if (d == 0) break }
            }
            map[k] = best
        }
        val idx = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            idx[i] = if (transparent && (c ushr 24) < 128) transIndex.toByte() else {
                val k = ((((c shr 16) and 0xFF) shr 3) shl 10) or ((((c shr 8) and 0xFF) shr 3) shl 5) or ((c and 0xFF) shr 3)
                map[k].toByte()
            }
        }
        // Graphic Control Extension
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04))
        out.write(if (transparent) (2 shl 2) or 1 else (1 shl 2))
        le16(delayCs)
        out.write(if (transparent) transIndex else 0)
        out.write(0)
        // Image descriptor + локальная палитра на 256 цветов
        out.write(0x2C); le16(0); le16(0); le16(w); le16(h)
        out.write(0x80 or 7)
        for (i in 0 until 256) { out.write(pr[i]); out.write(pg[i]); out.write(pb[i]) }
        lzw(idx)
    }

    fun finish() { out.write(0x3B); out.flush() }

    private fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    private inner class BitWriter {
        private val block = ByteArray(255); private var bn = 0
        private var acc = 0; private var nbits = 0
        fun write(code: Int, size: Int) {
            acc = acc or (code shl nbits); nbits += size
            while (nbits >= 8) { byte(acc and 0xFF); acc = acc ushr 8; nbits -= 8 }
        }
        private fun byte(b: Int) {
            block[bn++] = b.toByte()
            if (bn == 255) { out.write(255); out.write(block, 0, 255); bn = 0 }
        }
        fun flush() {
            if (nbits > 0) { byte(acc and 0xFF); acc = 0; nbits = 0 }
            if (bn > 0) { out.write(bn); out.write(block, 0, bn); bn = 0 }
        }
    }

    private fun lzw(data: ByteArray) {
        val minCode = 8
        out.write(minCode)
        val clear = 256; val eoi = 257
        val bw = BitWriter()
        var codeSize = 9
        var next = 258
        val dict = HashMap<Int, Int>(8192)
        bw.write(clear, codeSize)
        var prefix = data[0].toInt() and 0xFF
        for (i in 1 until data.size) {
            val k = data[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val found = dict[key]
            if (found != null) { prefix = found; continue }
            bw.write(prefix, codeSize)
            if (next >= (1 shl codeSize) && codeSize < 12) codeSize++
            if (next < 4096) {
                dict[key] = next++
            } else {
                bw.write(clear, codeSize)
                dict.clear(); next = 258; codeSize = 9
            }
            prefix = k
        }
        bw.write(prefix, codeSize)
        if (next >= (1 shl codeSize) && codeSize < 12) codeSize++
        bw.write(eoi, codeSize)
        bw.flush()
        out.write(0)
    }
}

/** H.264/MP4 через MediaCodec (YUV420 Flexible) + MediaMuxer. */
class VideoEncoder(private val file: File, private val w: Int, private val h: Int, private val fps: Int, audioPcm: ShortArray? = null) {
    private val audio: AacData? = audioPcm?.let { runCatching { encodeAac(it) }.getOrNull() }
    private var audioTrack = -1
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxerStarted = false
    private val info = MediaCodec.BufferInfo()
    private var frameIndex = 0L

    init {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        f.setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * fps * 0.2).toInt().coerceIn(1_000_000, 40_000_000))
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun addFrame(bmp: Bitmap) {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var idx: Int
        do { idx = codec.dequeueInputBuffer(10_000); if (idx < 0) drain(false) } while (idx < 0)
        val img = codec.getInputImage(idx) ?: throw IllegalStateException("Кодек не поддерживает YUV420 Flexible")
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = px[row + x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yb.put(y * yP.rowStride + x * yP.pixelStride, yy.coerceIn(0, 255).toByte())
                if (y % 2 == 0 && x % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val cy = y / 2; val cx = x / 2
                    ub.put(cy * uP.rowStride + cx * uP.pixelStride, u.coerceIn(0, 255).toByte())
                    vb.put(cy * vP.rowStride + cx * vP.pixelStride, v.coerceIn(0, 255).toByte())
                }
            }
        }
        val pts = frameIndex * 1_000_000L / fps
        codec.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0)
        frameIndex++
        drain(false)
    }

    fun finish() {
        var idx: Int
        do { idx = codec.dequeueInputBuffer(10_000); if (idx < 0) drain(false) } while (idx < 0)
        codec.queueInputBuffer(idx, 0, 0, frameIndex * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        codec.stop(); codec.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
    }

    private fun drain(eos: Boolean) {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, 10_000)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    audio?.let { audioTrack = muxer.addTrack(it.format) }
                    muxer.start(); muxerStarted = true
                    // звук закодирован заранее — пишем его сразу после старта
                    audio?.samples?.forEach { (bytes, inf) -> muxer.writeSampleData(audioTrack, java.nio.ByteBuffer.wrap(bytes), inf) }
                }
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0 && muxerStarted && buf != null) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }
}

class AacData(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

/** Кодирование моно PCM 44.1 кГц в AAC-LC. */
fun encodeAac(pcm: ShortArray, rate: Int = 44100): AacData {
    val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1)
    fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
    fmt.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
    fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()
    val out = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
    var outFormat: MediaFormat? = null
    val info = MediaCodec.BufferInfo()
    var pos = 0
    var inputDone = false
    while (true) {
        if (!inputDone) {
            val ii = codec.dequeueInputBuffer(10_000)
            if (ii >= 0) {
                val buf = codec.getInputBuffer(ii)!!
                buf.clear()
                val n = minOf(pcm.size - pos, buf.capacity() / 2, 2048)
                val pts = pos * 1_000_000L / rate
                if (n <= 0) { codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                else {
                    val bb = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) bb.putShort(pcm[pos + i])
                    codec.queueInputBuffer(ii, 0, n * 2, pts, 0)
                    pos += n
                }
            }
        }
        val oi = codec.dequeueOutputBuffer(info, 10_000)
        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = codec.outputFormat
        else if (oi >= 0) {
            val buf = codec.getOutputBuffer(oi)
            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && buf != null) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset); buf.get(bytes)
                val copy = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()) }
                out.add(bytes to copy)
            }
            codec.releaseOutputBuffer(oi, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
        }
    }
    val f = outFormat ?: codec.outputFormat
    codec.stop(); codec.release()
    return AacData(f, out)
}
