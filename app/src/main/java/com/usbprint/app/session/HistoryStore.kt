package com.usbprint.app.session

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val id: Long,
    val title: String,
    val time: Long,
    val printer: String,
    val sheets: Int,
    val status: String,
    val files: List<String>,
    val editsJson: String?,
)

/** Print history in a small JSON file; sources are copied so jobs can be reprinted later. */
class HistoryStore(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(context.filesDir, "history.json")
    private val dir = File(context.filesDir, "history")
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init {
        scope.launch { _entries.value = read() }
    }

    /** Records a job and copies its files. Returns the entry id. */
    fun add(title: String, printer: String, sheets: Int, sources: List<File>, editsJson: String?): Long {
        val id = System.currentTimeMillis()
        val entryDir = File(dir, id.toString())
        val keep = sources.filter { it.length() <= MAX_COPY_BYTES }
        val copies = keep.map { File(entryDir, it.name).path }
        scope.launch {
            entryDir.mkdirs()
            keep.forEach { src -> runCatching { src.copyTo(File(entryDir, src.name), overwrite = true) } }
        }
        val e = HistoryEntry(id, title, id, printer, sheets, "Printing", copies, editsJson)
        mutate { listOf(e) + it }
        return id
    }

    fun setStatus(id: Long, status: String) = mutate { list -> list.map { if (it.id == id) it.copy(status = status) else it } }

    fun delete(id: Long) {
        mutate { list -> list.filterNot { it.id == id } }
        scope.launch { File(dir, id.toString()).deleteRecursively() }
    }

    fun clear() {
        mutate { emptyList() }
        scope.launch { dir.deleteRecursively() }
    }

    private fun mutate(block: (List<HistoryEntry>) -> List<HistoryEntry>) {
        synchronized(this) {
            var list = block(_entries.value)
            if (list.size > MAX_ENTRIES) {
                list.drop(MAX_ENTRIES).forEach { old -> scope.launch { File(dir, old.id.toString()).deleteRecursively() } }
                list = list.take(MAX_ENTRIES)
            }
            _entries.value = list
            val snapshot = list
            scope.launch { write(snapshot) }
        }
    }

    private fun read(): List<HistoryEntry> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val files = o.optJSONArray("files")
            HistoryEntry(
                id = o.getLong("id"), title = o.optString("title"), time = o.optLong("time"),
                printer = o.optString("printer"), sheets = o.optInt("sheets"), status = o.optString("status"),
                files = if (files == null) emptyList() else List(files.length()) { files.getString(it) },
                editsJson = o.optString("edits").ifEmpty { null },
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun write(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id); put("title", e.title); put("time", e.time); put("printer", e.printer)
                put("sheets", e.sheets); put("status", e.status); put("files", JSONArray(e.files))
                e.editsJson?.let { put("edits", it) }
            })
        }
        runCatching { file.writeText(arr.toString()) }
    }

    companion object {
        private const val MAX_ENTRIES = 50
        private const val MAX_COPY_BYTES = 64L shl 20
    }
}
