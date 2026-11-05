package com.animora.studio.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.animora.studio.engine.FrameOptions
import com.animora.studio.engine.Renderer
import com.animora.studio.model.BgType
import com.animora.studio.model.Project
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

enum class ExportFormat(val title: String, val ext: String, val mime: String) {
    MP4("Видео MP4", "mp4", "video/mp4"),
    GIF("Анимированный GIF", "gif", "image/gif"),
    PNG_ZIP("PNG-секвенция (ZIP)", "zip", "application/zip"),
    SPRITE("Спрайт-лист PNG", "png", "image/png"),
    FRAME("Текущий кадр PNG", "png", "image/png"),
    PROJECT("Файл проекта (.json)", "json", "application/json")
}

class ExportSettings(
    val format: ExportFormat,
    val scale: Float,
    val from: Int,
    val to: Int,
    val transparent: Boolean,
    val includeGuides: Boolean = false,
    val loops: Int = 0,
    val currentFrame: Int = 0,
    val pingPong: Boolean = false
)

object Exporter {

    /** Возвращает Uri файла в «Загрузках». */
    fun export(ctx: Context, project: Project, s: ExportSettings, projectJson: String?, progress: (Float) -> Unit): Uri {
        val renderer = Renderer()
        val bgOn = !(s.transparent && s.format != ExportFormat.MP4) && project.bgType != BgType.TRANSPARENT
        val opt = FrameOptions(background = bgOn || (s.format == ExportFormat.MP4 && project.bgType != BgType.TRANSPARENT), includeGuides = s.includeGuides)
        var frames = (s.from..s.to).toList()
        if (s.pingPong && frames.size > 2) frames = frames + frames.subList(1, frames.size - 1).reversed()
        val safeName = project.name.replace(Regex("[^\\p{L}\\p{N}_ -]"), "_").ifBlank { "animation" }
        val tmp = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.${s.format.ext}")
        fun render(f: Int, opaque: Boolean = false): Bitmap = renderer.renderBitmap(project, f, s.scale, opt, opaque)

        when (s.format) {
            ExportFormat.MP4 -> {
                val w = max(16, (project.width * s.scale).toInt() / 2 * 2)
                val h = max(16, (project.height * s.scale).toInt() / 2 * 2)
                val enc = VideoEncoder(tmp, w, h, project.fps)
                frames.forEachIndexed { i, f ->
                    val b = render(f, true)
                    val bb = if (b.width != w || b.height != h) Bitmap.createScaledBitmap(b, w, h, true) else b
                    enc.addFrame(bb)
                    progress((i + 1f) / frames.size)
                }
                enc.finish()
            }
            ExportFormat.GIF -> {
                val w = max(1, (project.width * s.scale).toInt()); val h = max(1, (project.height * s.scale).toInt())
                tmp.outputStream().buffered().use { os ->
                    val g = GifEncoder(os, w, h, max(2, 100 / project.fps), s.transparent || project.bgType == BgType.TRANSPARENT, s.loops)
                    g.start()
                    frames.forEachIndexed { i, f -> g.addFrame(render(f)); progress((i + 1f) / frames.size) }
                    g.finish()
                }
            }
            ExportFormat.PNG_ZIP -> {
                ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                    frames.forEachIndexed { i, f ->
                        zip.putNextEntry(ZipEntry("%s_%04d.png".format(safeName, i + 1)))
                        render(f).compress(Bitmap.CompressFormat.PNG, 100, zip)
                        zip.closeEntry()
                        progress((i + 1f) / frames.size)
                    }
                }
            }
            ExportFormat.SPRITE -> {
                val n = frames.size
                val cols = ceil(sqrt(n.toDouble())).toInt()
                val rows = ceil(n / cols.toDouble()).toInt()
                val fw = max(1, (project.width * s.scale).toInt()); val fh = max(1, (project.height * s.scale).toInt())
                val sheet = Bitmap.createBitmap(fw * cols, fh * rows, Bitmap.Config.ARGB_8888)
                val c = Canvas(sheet)
                frames.forEachIndexed { i, f ->
                    c.drawBitmap(render(f), (i % cols * fw).toFloat(), (i / cols * fh).toFloat(), null)
                    progress((i + 1f) / n)
                }
                tmp.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            ExportFormat.FRAME -> {
                tmp.outputStream().use { render(s.currentFrame).compress(Bitmap.CompressFormat.PNG, 100, it) }
                progress(1f)
            }
            ExportFormat.PROJECT -> {
                tmp.writeText(projectJson ?: "{}"); progress(1f)
            }
        }
        val uri = saveToDownloads(ctx, tmp, "${safeName}_${System.currentTimeMillis() / 1000}.${s.format.ext}", s.format.mime)
        tmp.delete()
        return uri
    }

    private fun saveToDownloads(ctx: Context, src: File, name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Animora")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Не удалось создать файл в Загрузках")
        resolver.openOutputStream(uri)?.use { os -> src.inputStream().use { it.copyTo(os) } }
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
