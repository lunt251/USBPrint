package com.usbprint.app.doc

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.print.PdfCapture
import android.print.PrintAttributes
import android.provider.OpenableColumns
import android.util.Xml
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile
import kotlin.coroutines.resume

class UnsupportedDocumentException(message: String) : Exception(message)

object DocumentLoader {

    private val TEXT_EXT = setOf("txt", "text", "md", "csv", "log", "json", "xml", "ini", "conf", "yaml", "yml", "kt", "java", "py", "c", "h", "cpp", "js", "sh")
    private val HTML_EXT = setOf("html", "htm", "xhtml", "mht")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val OFFICE_ONLY = setOf("doc", "xls", "xlsx", "ppt", "pptx", "odp", "ods", "pages", "numbers", "key")

    /** Extensions the file browser should show as printable. */
    val DOCUMENT_EXT = setOf("pdf", "epub", "rtf", "odt", "docx") + TEXT_EXT + HTML_EXT
    val PHOTO_EXT = IMAGE_EXT

    fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()

    fun isSupportedName(name: String) = extensionOf(name).let { it in DOCUMENT_EXT || it in IMAGE_EXT }

    /** Copies the inputs into app storage and opens them as one document. */
    suspend fun open(context: Context, uris: List<Uri>): SourceDocument = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty())
        val dir = File(context.cacheDir, "docs/${System.nanoTime()}").apply { mkdirs() }
        val files = uris.mapIndexed { i, uri -> copyIn(context, uri, dir, i) }
        val types = files.map { (file, mime) -> kindOf(file.name, mime) }

        if (types.all { it == "image" }) {
            val title = if (files.size == 1) files[0].first.name else "${files.size} photos"
            return@withContext ImageSource(title, files.map { it.first })
        }
        val (file, mime) = files.first()
        openFile(context, file, kindOf(file.name, mime))
    }

    /** Opens a file already in app storage (history reprint). */
    suspend fun openFiles(context: Context, files: List<File>): SourceDocument = withContext(Dispatchers.IO) {
        val kinds = files.map { kindOf(it.name, null) }
        if (kinds.all { it == "image" }) ImageSource(if (files.size == 1) files[0].name else "${files.size} photos", files)
        else openFile(context, files.first(), kinds.first())
    }

    private suspend fun openFile(context: Context, file: File, kind: String): SourceDocument {
        val title = file.name
        return when (kind) {
            "pdf" -> try {
                PdfSource(title, file)
            } catch (e: SecurityException) {
                throw UnsupportedDocumentException("This PDF is password protected.")
            }
            "image" -> ImageSource(title, listOf(file))
            "text" -> TextSource(title, readText(file), listOf(file))
            "html" -> PdfSource(title, captureHtml(context, Uri.fromFile(file), File(file.parentFile, "$title.pdf")), DocKind.WEB, listOf(file))
            "epub" -> {
                val html = Epub.toHtml(file, File(file.parentFile, "epub"))
                PdfSource(title, captureHtml(context, Uri.fromFile(html), File(file.parentFile, "$title.pdf")), DocKind.WEB, listOf(file))
            }
            "docx", "odt", "rtf" -> TextSource(
                title, OfficeText.extract(file, kind), listOf(file),
                notice = "Showing the text only. For the exact layout, open it in an Office app and choose Print → USBPrint.",
            )
            "office" -> throw UnsupportedDocumentException(
                "This file type can't be opened directly. Open it in an Office app and choose Print → USBPrint.",
            )
            else -> throw UnsupportedDocumentException("Unsupported file type.")
        }
    }

    private fun kindOf(name: String, mime: String?): String {
        val ext = extensionOf(name)
        return when {
            ext == "pdf" || mime == "application/pdf" -> "pdf"
            ext in IMAGE_EXT || mime?.startsWith("image/") == true -> "image"
            ext == "epub" || mime == "application/epub+zip" -> "epub"
            ext in HTML_EXT || mime == "text/html" -> "html"
            ext == "docx" -> "docx"
            ext == "odt" -> "odt"
            ext == "rtf" || mime == "application/rtf" || mime == "text/rtf" -> "rtf"
            ext in OFFICE_ONLY -> "office"
            ext in TEXT_EXT || mime?.startsWith("text/") == true -> "text"
            else -> "unknown"
        }
    }

    private fun copyIn(context: Context, uri: Uri, dir: File, index: Int): Pair<File, String?> {
        val cr = context.contentResolver
        var name: String? = null
        if (uri.scheme == "content") {
            runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0)
                }
            }
        } else if (uri.scheme == "file") {
            name = uri.lastPathSegment
        }
        val mime = runCatching { cr.getType(uri) }.getOrNull()
        var safe = (name ?: "document_$index").replace(Regex("""[\\/:*?"<>|]"""), "_")
        if (!safe.contains('.') && mime != null) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { safe += ".$it" }
        }
        val target = File(dir, if (File(dir, safe).exists()) "${index}_$safe" else safe)
        val input: InputStream = cr.openInputStream(uri) ?: throw UnsupportedDocumentException("Couldn't open the file.")
        input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
        return target to mime
    }

    fun readText(file: File, limit: Int = 4 shl 20): String {
        val bytes = file.inputStream().use { it.readNBytesCompat(limit) }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }.removePrefix("\uFEFF")
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Loads a page in an off-screen WebView and saves its print output as PDF. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun captureHtml(context: Context, uri: Uri, out: File): File = withContext(Dispatchers.Main) {
        withTimeout(60_000) {
            suspendCancellableCoroutine { cont ->
                val web = WebView(context.applicationContext)
                web.settings.allowFileAccess = true
                web.settings.javaScriptEnabled = false
                web.webViewClient = object : WebViewClient() {
                    var started = false
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (started) return
                        started = true
                        // Give layout/images a moment to settle.
                        Handler(Looper.getMainLooper()).postDelayed({
                            capture(web, out) { ok ->
                                web.destroy()
                                if (cont.isActive) {
                                    if (ok) cont.resume(out)
                                    else cont.resumeWith(Result.failure(UnsupportedDocumentException("Couldn't convert the page.")))
                                }
                            }
                        }, 400)
                    }
                }
                web.loadUrl(uri.toString())
                cont.invokeOnCancellation { web.post { web.destroy() } }
            }
        }
    }

    fun capture(web: WebView, out: File, done: (Boolean) -> Unit) {
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setMinMargins(PrintAttributes.Margins(400, 400, 400, 400))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        PdfCapture.write(web.createPrintDocumentAdapter(out.nameWithoutExtension), attrs, out) { done(it) }
    }
}

/** Flattens an EPUB's spine into a single HTML file. */
object Epub {
    fun toHtml(epub: File, dir: File): File {
        dir.mkdirs()
        ZipFile(epub).use { zip ->
            // Extract everything (images, css) so relative links work.
            for (e in zip.entries()) {
                val target = File(dir, e.name)
                if (!target.canonicalPath.startsWith(dir.canonicalPath)) continue
                if (e.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(e).use { i -> target.outputStream().use { o -> i.copyTo(o) } }
                }
            }
        }
        val opfPath = Regex("""full-path\s*=\s*"([^"]+)"""")
            .find(File(dir, "META-INF/container.xml").readText())?.groupValues?.get(1)
            ?: throw UnsupportedDocumentException("Invalid EPUB.")
        val opf = File(dir, opfPath)
        val base = opf.parentFile ?: dir

        val manifest = HashMap<String, String>()
        val spine = ArrayList<String>()
        var bookTitle = ""
        opf.inputStream().use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            var inTitle = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                        "item" -> manifest[p.getAttributeValue(null, "id") ?: ""] = p.getAttributeValue(null, "href") ?: ""
                        "itemref" -> spine += p.getAttributeValue(null, "idref") ?: ""
                        "title" -> inTitle = bookTitle.isEmpty()
                    }
                    XmlPullParser.TEXT -> if (inTitle) bookTitle += p.text
                    XmlPullParser.END_TAG -> inTitle = false
                }
            }
        }

        val body = StringBuilder()
        val bodyRe = Regex("""<body[^>]*>(.*)</body>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val linkRe = Regex("""(src|href|xlink:href)\s*=\s*"(?!https?:|data:|#|/)([^"]+)"""", RegexOption.IGNORE_CASE)
        for (id in spine) {
            val href = manifest[id] ?: continue
            val chapter = File(base, Uri.decode(href))
            if (!chapter.exists()) continue
            val rel = chapter.parentFile?.relativeTo(base)?.path.orEmpty().replace('\\', '/')
            val html = chapter.readText()
            var content = bodyRe.find(html)?.groupValues?.get(1) ?: html
            if (rel.isNotEmpty()) content = content.replace(linkRe) { m -> "${m.groupValues[1]}=\"$rel/${m.groupValues[2]}\"" }
            body.append("<section style=\"page-break-before:always\">").append(content).append("</section>\n")
        }
        val out = File(base, "_usbprint_book.html")
        out.writeText(
            """<!DOCTYPE html><html><head><meta charset="utf-8"><title>${bookTitle.trim()}</title>
            <style>body{font-family:serif;font-size:12pt;line-height:1.45}img,svg{max-width:100%;height:auto}
            section:first-child{page-break-before:auto}</style></head><body>$body</body></html>""",
        )
        return out
    }
}

/** Best-effort text extraction for Office formats (no layout). */
object OfficeText {
    fun extract(file: File, kind: String): String = when (kind) {
        "docx" -> zipXmlText(file, "word/document.xml", para = setOf("p"), text = setOf("t"), tab = setOf("tab"), br = setOf("br", "cr"))
        "odt" -> zipXmlText(file, "content.xml", para = setOf("p", "h"), text = emptySet(), tab = setOf("tab"), br = setOf("line-break"), space = "s")
        else -> rtfToText(DocumentLoader.readText(file))
    }

    private fun zipXmlText(
        file: File, entry: String, para: Set<String>, text: Set<String>, tab: Set<String>, br: Set<String>, space: String? = null,
    ): String {
        val sb = StringBuilder()
        ZipFile(file).use { zip ->
            val e = zip.getEntry(entry) ?: throw UnsupportedDocumentException("Couldn't read this document.")
            zip.getInputStream(e).use { input ->
                val p = Xml.newPullParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                p.setInput(input, "UTF-8")
                var inText = text.isEmpty()
                var depthPara = 0
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    val local = p.name?.substringAfter(':')
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (local) {
                            in para -> depthPara++
                            in text -> inText = true
                            in tab -> sb.append('\t')
                            in br -> sb.append('\n')
                            space -> sb.append(" ".repeat((p.getAttributeValue(null, "text:c")?.toIntOrNull() ?: 1).coerceIn(1, 50)))
                        }
                        XmlPullParser.TEXT -> if (inText && depthPara > 0) sb.append(p.text)
                        XmlPullParser.END_TAG -> when (local) {
                            in para -> { depthPara--; if (depthPara == 0) sb.append('\n') }
                            in text -> inText = false
                        }
                    }
                }
            }
        }
        return sb.toString()
    }

    fun rtfToText(rtf: String): String {
        val sb = StringBuilder()
        val skipStack = ArrayList<Boolean>()
        var skip = false
        var i = 0
        var ucSkip = 0
        val skipDest = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "object", "listtable", "listoverridetable", "rsidtbl", "generator", "themedata", "datastore", "latentstyles")
        while (i < rtf.length) {
            val c = rtf[i]
            when {
                c == '{' -> { skipStack += skip; i++ }
                c == '}' -> { skip = skipStack.removeLastOrNull() ?: false; i++ }
                c == '\\' && i + 1 < rtf.length -> {
                    val n = rtf[i + 1]
                    when {
                        n == '\\' || n == '{' || n == '}' -> { if (!skip) sb.append(n); i += 2 }
                        n == '*' -> { skip = true; i += 2 }
                        n == '\'' && i + 3 < rtf.length -> {
                            val code = rtf.substring(i + 2, i + 4).toIntOrNull(16)
                            if (!skip && code != null) sb.append(String(byteArrayOf(code.toByte()), charset("windows-1252")))
                            i += 4
                        }
                        n.isLetter() -> {
                            var j = i + 1
                            while (j < rtf.length && rtf[j].isLetter()) j++
                            val word = rtf.substring(i + 1, j)
                            var k = j
                            if (k < rtf.length && (rtf[k] == '-' || rtf[k].isDigit())) {
                                k++
                                while (k < rtf.length && rtf[k].isDigit()) k++
                            }
                            val param = rtf.substring(j, k).toIntOrNull()
                            if (k < rtf.length && rtf[k] == ' ') k++
                            i = k
                            if (word in skipDest) skip = true
                            if (!skip) when (word) {
                                "par", "line", "sect", "page" -> sb.append('\n')
                                "tab" -> sb.append('\t')
                                "u" -> if (param != null) {
                                    sb.append((if (param < 0) param + 65536 else param).toChar())
                                    ucSkip = 1
                                }
                            }
                            if (word == "u" && ucSkip > 0 && i < rtf.length && rtf[i] != '\\' && rtf[i] != '{' && rtf[i] != '}') {
                                i++; ucSkip = 0
                            }
                        }
                        else -> i += 2
                    }
                }
                c == '\r' || c == '\n' -> i++
                else -> { if (!skip) sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
