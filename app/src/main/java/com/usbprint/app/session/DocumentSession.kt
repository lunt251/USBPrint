package com.usbprint.app.session

import android.content.Context
import android.net.Uri
import com.usbprint.app.doc.DocumentLoader
import com.usbprint.app.doc.PdfSource
import com.usbprint.app.doc.DocKind
import com.usbprint.app.doc.SourceDocument
import com.usbprint.app.edit.DocEdits
import com.usbprint.app.render.PageCache
import com.usbprint.app.render.SheetComposer
import com.usbprint.app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

sealed interface LoadState {
    data object Idle : LoadState
    data object Loading : LoadState
    data class Ready(val doc: SourceDocument) : LoadState
    data class Error(val message: String) : LoadState
}

/** The document currently open in preview/edit, with its edits and undo history. */
class DocumentSession(private val context: Context, private val settings: () -> AppSettings) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var editsKey: String? = null

    private val _state = MutableStateFlow<LoadState>(LoadState.Idle)
    val state: StateFlow<LoadState> = _state.asStateFlow()

    private val _edits = MutableStateFlow(DocEdits())
    val edits: StateFlow<DocEdits> = _edits.asStateFlow()

    private val undo = ArrayDeque<DocEdits>()
    private val redo = ArrayDeque<DocEdits>()
    private val _canUndo = MutableStateFlow(false to false)
    /** (canUndo, canRedo) */
    val history: StateFlow<Pair<Boolean, Boolean>> = _canUndo.asStateFlow()

    /** Bumped when something (share intent, file tap) wants the preview shown. */
    private val _openToken = MutableStateFlow(0L)
    val openToken: StateFlow<Long> = _openToken.asStateFlow()

    val document: SourceDocument? get() = (_state.value as? LoadState.Ready)?.doc

    /** Set by the print controller so a document being printed isn't closed underneath it. */
    var inUse: (SourceDocument) -> Boolean = { false }

    fun open(uris: List<Uri>) = load { DocumentLoader.open(context, uris) }

    fun openFiles(files: List<File>) = load { DocumentLoader.openFiles(context, files) }

    fun openPdf(file: File, title: String) = load { withContext(Dispatchers.IO) { PdfSource(title, file, DocKind.WEB) } }

    private fun load(block: suspend () -> SourceDocument) {
        loadJob?.cancel()
        closeCurrent()
        _state.value = LoadState.Loading
        _openToken.value++
        loadJob = scope.launch {
            try {
                val doc = block()
                val s = settings()
                val defaults = DocEdits().let { it.copy(layout = it.layout.copy(paper = s.defaultPaper)) }
                editsKey = keyFor(doc)
                val remembered = if (s.rememberEdits) withContext(Dispatchers.IO) { loadEdits(editsKey!!) } else null
                undo.clear(); redo.clear(); publishHistory()
                _edits.value = remembered ?: defaults
                _state.value = LoadState.Ready(doc)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = LoadState.Error(e.message ?: "Couldn't open this file.")
            }
        }
    }

    fun composer(edits: DocEdits = _edits.value, maxSourcePx: Int = 3000): SheetComposer? =
        document?.let { SheetComposer(it, edits, maxSourcePx) }

    /** Replace the edits; [record] = add an undo step. */
    fun update(newEdits: DocEdits, record: Boolean = true) {
        val old = _edits.value
        if (newEdits == old) return
        if (record) {
            undo.addLast(old)
            if (undo.size > 100) undo.removeFirst()
            redo.clear()
            publishHistory()
        }
        _edits.value = newEdits
        scheduleSave()
    }

    /** Push an undo point without changing anything (start of a drag / slider move). */
    fun checkpoint() {
        undo.addLast(_edits.value)
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
        publishHistory()
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(_edits.value)
        _edits.value = prev
        publishHistory(); scheduleSave()
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(_edits.value)
        _edits.value = next
        publishHistory(); scheduleSave()
    }

    private fun publishHistory() {
        _canUndo.value = undo.isNotEmpty() to redo.isNotEmpty()
    }

    fun close() {
        loadJob?.cancel()
        closeCurrent()
        _state.value = LoadState.Idle
    }

    private fun closeCurrent() {
        val doc = document ?: return
        saveNow()
        // Let any print job finish with its own reference before closing the renderer.
        scope.launch {
            delay(500)
            while (inUse(doc)) delay(1000)
            runCatching { doc.close() }
        }
        PageCache.clear()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(800)
            saveNow()
        }
    }

    private fun saveNow() {
        val key = editsKey ?: return
        if (!settings().rememberEdits) return
        val json = _edits.value.toJson().toString()
        scope.launch(Dispatchers.IO) {
            runCatching { File(editsDir(), "$key.json").writeText(json) }
        }
    }

    private fun editsDir() = File(context.filesDir, "edits").apply { mkdirs() }

    private fun loadEdits(key: String): DocEdits? = runCatching {
        val f = File(editsDir(), "$key.json")
        if (f.exists()) DocEdits.fromJson(JSONObject(f.readText())) else null
    }.getOrNull()

    fun clearRememberedEdits() {
        scope.launch(Dispatchers.IO) { editsDir().listFiles()?.forEach { it.delete() } }
    }

    private fun keyFor(doc: SourceDocument): String {
        val md = MessageDigest.getInstance("SHA-1")
        for (f in doc.files) {
            md.update(f.name.toByteArray())
            md.update(f.length().toString().toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
