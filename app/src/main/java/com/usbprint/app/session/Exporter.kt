package com.usbprint.app.session

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.usbprint.app.render.SheetComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

/** Saves the composed sheets as a PDF (Downloads/USBPrint) or PNGs (Pictures/USBPrint). */
object Exporter {

    suspend fun exportPdf(context: Context, composer: SheetComposer, baseName: String): String = withContext(Dispatchers.Default) {
        val name = "${sanitize(baseName)}.pdf"
        val pdf = PdfDocument()
        try {
            for (i in 0 until composer.sheetCount) {
                val (w, h) = composer.sheetSize(i)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(w.roundToInt(), h.roundToInt(), i + 1).create())
                // Draw at 3× so bitmaps are sampled at ~216 dpi, then scale into point space.
                page.canvas.scale(1f / 3f, 1f / 3f)
                composer.drawSheet(i, page.canvas, (w * 3).roundToInt(), (h * 3).roundToInt())
                pdf.finishPage(page)
            }
            withContext(Dispatchers.IO) {
                save(context, name, "application/pdf", Environment.DIRECTORY_DOWNLOADS) { pdf.writeTo(it) }
            }
            "${Environment.DIRECTORY_DOWNLOADS}/USBPrint/$name"
        } finally {
            pdf.close()
        }
    }

    suspend fun exportImages(context: Context, composer: SheetComposer, baseName: String): String = withContext(Dispatchers.Default) {
        val base = sanitize(baseName)
        for (i in 0 until composer.sheetCount) {
            val (w, h) = composer.sheetSize(i)
            val scale = 200f / 72f
            val bmp = composer.renderSheet(i, (w * scale).roundToInt(), (h * scale).roundToInt())
            val name = if (composer.sheetCount == 1) "$base.png" else "${base}_${i + 1}.png"
            withContext(Dispatchers.IO) {
                save(context, name, "image/png", Environment.DIRECTORY_PICTURES) { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bmp.recycle()
        }
        "${Environment.DIRECTORY_PICTURES}/USBPrint"
    }

    private fun sanitize(s: String) = s.substringBeforeLast('.').replace(Regex("""[\\/:*?"<>|]"""), "_").take(80).ifBlank { "USBPrint" }

    private fun save(context: Context, name: String, mime: String, dir: String, write: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/USBPrint")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection: Uri = if (dir == Environment.DIRECTORY_PICTURES) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values) ?: error("Couldn't create $name")
            context.contentResolver.openOutputStream(uri)?.use(write) ?: error("Couldn't write $name")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val target = File(Environment.getExternalStoragePublicDirectory(dir), "USBPrint").apply { mkdirs() }
            File(target, name).outputStream().use(write)
        }
    }
}
