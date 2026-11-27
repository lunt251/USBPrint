package com.usbprint.app.files

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import com.usbprint.app.doc.DocumentLoader
import java.io.File

data class FileItem(
    val name: String,
    val uri: Uri,
    val file: File?,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val mime: String?,
) {
    val ext get() = DocumentLoader.extensionOf(name)
}

enum class SortMode { NAME, DATE, SIZE }

object FileRepository {

    val root: File get() = Environment.getExternalStorageDirectory()

    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun hasPhotoAccess(context: Context): Boolean = hasAllFilesAccess(context) ||
        (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)

    private fun accepts(name: String, photos: Boolean): Boolean {
        val ext = DocumentLoader.extensionOf(name)
        return if (photos) ext in DocumentLoader.PHOTO_EXT else ext in DocumentLoader.DOCUMENT_EXT
    }

    /** Files changed in the last 7 days anywhere on the phone, newest first. */
    fun recents(context: Context, photos: Boolean, limit: Int = 60): List<FileItem> {
        val since = (System.currentTimeMillis() - 7L * 24 * 3600 * 1000) / 1000
        val collection = if (photos) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATA,
        )
        val out = ArrayList<FileItem>()
        runCatching {
            context.contentResolver.query(
                collection, projection, "${MediaStore.MediaColumns.DATE_MODIFIED} >= ?", arrayOf(since.toString()),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(1) ?: continue
                    if (name.startsWith('.') || !accepts(name, photos)) continue
                    val path = c.getString(5)
                    if (path != null && path.contains("/Android/data/")) continue
                    val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                    out += FileItem(name, uri, path?.let(::File), false, c.getLong(2), c.getLong(3) * 1000, c.getString(4))
                }
            }
        }
        return out
    }

    fun list(dir: File, photos: Boolean, showHidden: Boolean, sort: SortMode): List<FileItem> {
        val children = dir.listFiles() ?: return emptyList()
        val items = children.asSequence()
            .filter { showHidden || !it.name.startsWith('.') }
            .filter { it.isDirectory || accepts(it.name, photos) }
            .map { FileItem(it.name, Uri.fromFile(it), it, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified(), null) }
            .toList()
        val cmp: Comparator<FileItem> = when (sort) {
            SortMode.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortMode.DATE -> compareByDescending { it.modified }
            SortMode.SIZE -> compareByDescending { it.size }
        }
        return items.sortedWith(compareByDescending<FileItem> { it.isDir }.then(cmp))
    }

    /** Recursive name search under [root]. */
    fun search(root: File, query: String, photos: Boolean, showHidden: Boolean, limit: Int = 200): List<FileItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<FileItem>()
        fun walk(dir: File, depth: Int) {
            if (depth > 8 || out.size >= limit) return
            val kids = dir.listFiles() ?: return
            for (f in kids) {
                if (out.size >= limit) return
                if (!showHidden && f.name.startsWith('.')) continue
                if (f.isDirectory) {
                    if (f.name == "Android" && depth == 0) continue
                    walk(f, depth + 1)
                } else if (f.name.lowercase().contains(q) && accepts(f.name, photos)) {
                    out += FileItem(f.name, Uri.fromFile(f), f, false, f.length(), f.lastModified(), null)
                }
            }
        }
        walk(root, 0)
        return out.sortedByDescending { it.modified }
    }

    private val thumbs = LruCache<String, Bitmap>(80)

    fun thumbnail(context: Context, item: FileItem, px: Int): Bitmap? {
        val key = "${item.uri}@$px"
        thumbs.get(key)?.let { return it }
        val bmp = runCatching {
            when {
                item.ext in DocumentLoader.PHOTO_EXT -> {
                    if (Build.VERSION.SDK_INT >= 29 && item.uri.scheme == "content") {
                        context.contentResolver.loadThumbnail(item.uri, Size(px, px), null)
                    } else {
                        val path = item.file?.path ?: return null
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, o)
                        var s = 1
                        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
                        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
                    }
                }
                item.ext == "pdf" -> {
                    val pfd = if (item.file != null && item.file.canRead()) ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.MODE_READ_ONLY)
                    else context.contentResolver.openFileDescriptor(item.uri, "r") ?: return null
                    pfd.use {
                        PdfRenderer(it).use { r ->
                            r.openPage(0).use { p ->
                                val scale = px / maxOf(p.width, p.height).toFloat()
                                val b = Bitmap.createBitmap(maxOf(1, (p.width * scale).toInt()), maxOf(1, (p.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                                b.eraseColor(Color.WHITE)
                                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                b
                            }
                        }
                    }
                }
                else -> null
            }
        }.getOrNull()
        bmp?.let { thumbs.put(key, it) }
        return bmp
    }
}
