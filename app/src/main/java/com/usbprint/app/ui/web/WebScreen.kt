package com.usbprint.app.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.usbprint.app.R
import com.usbprint.app.doc.DocumentLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import kotlin.coroutines.resume

private data class Visit(val title: String, val url: String, val time: Long)

private class WebHistory(context: Context) {
    private val file = File(context.filesDir, "web_history.json")
    val items = mutableStateListOf<Visit>().apply {
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { add(Visit(it.optString("t"), it.optString("u"), it.optLong("d"))) }
        }
    }

    fun add(title: String, url: String) {
        if (url.isBlank() || url == "about:blank") return
        items.removeAll { it.url == url }
        items.add(0, Visit(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (items.size > 200) items.removeAt(items.lastIndex)
        save()
    }

    fun clear() { items.clear(); save() }

    private fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        runCatching { file.writeText(arr.toString()) }
    }
}

fun normalizeUrl(input: String): String {
    val s = input.trim()
    if (s.isEmpty()) return "about:blank"
    if (s.matches(Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://.*"""))) return s
    if (!s.contains(' ') && (s.contains('.') || s.startsWith("localhost"))) return "https://$s"
    return "https://duckduckgo.com/?q=" + URLEncoder.encode(s, "UTF-8")
}

/** Simple reader mode: keep the main article, drop navigation/ads, readable typography. */
private const val READER_JS = """
(function(){
  var best=document.querySelector('article')||document.querySelector('main')||document.querySelector('[role=main]');
  if(!best){var max=0;document.querySelectorAll('div,section').forEach(function(e){var n=0;e.querySelectorAll(':scope > p').forEach(function(p){n+=p.innerText.length});if(n>max){max=n;best=e}});}
  if(!best)best=document.body;
  var t=document.title;var h=best.cloneNode(true);
  h.querySelectorAll('script,style,iframe,nav,aside,footer,form,button,noscript,[class*=share],[class*=related],[class*=comment],[class*=advert],[id*=ad-]').forEach(function(e){e.remove()});
  document.head.innerHTML='<meta name=viewport content="width=device-width,initial-scale=1"><title>'+t+'</title>';
  document.body.innerHTML='';document.body.appendChild(h);
  var s=document.createElement('style');
  s.textContent='body{max-width:42em;margin:0 auto;padding:16px;font:18px/1.6 Georgia,serif;color:#111;background:#fff}img,video,figure{max-width:100%;height:auto}a{color:inherit}';
  document.head.appendChild(s);
  if(!h.querySelector('h1')){var t1=document.createElement('h1');t1.textContent=t;document.body.insertBefore(t1,h);}
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WebScreen(readerDefault: Boolean, onBack: () -> Unit, onPrintPdf: (File, String) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val history = remember { WebHistory(context) }

    var address by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(1f) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var reader by remember { mutableStateOf(readerDefault) }
    var showHistory by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    val readerState = remember { arrayOf(readerDefault) }
    readerState[0] = reader

    val web = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    address = url.orEmpty()
                    canBack = view.canGoBack(); canForward = view.canGoForward()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    title = view.title.orEmpty()
                    canBack = view.canGoBack(); canForward = view.canGoForward()
                    history.add(title, url.orEmpty())
                    if (readerState[0]) view.evaluateJavascript(READER_JS, null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
                override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { web.stopLoading(); web.destroy() } }

    fun go(input: String) {
        focus.clearFocus()
        loaded = true
        web.loadUrl(normalizeUrl(input))
    }

    BackHandler(enabled = canBack) { web.goBack() }

    fun print() {
        if (!loaded) return
        capturing = true
        val out = File(context.cacheDir, "web/${System.nanoTime()}.pdf").apply { parentFile?.mkdirs() }
        scope.launch {
            val ok = suspendCancellableCoroutine { cont -> DocumentLoader.capture(web, out) { cont.resume(it) } }
            capturing = false
            if (ok) onPrintPdf(out, (title.ifBlank { "Web page" }) + ".pdf")
            else snackbar.showSnackbar(context.getString(R.string.web_print_failed))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
                    },
                    title = {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.web_hint), maxLines = 1) },
                            shape = RoundedCornerShape(28.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { go(address) }),
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                    },
                )
                if (progress < 1f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    IconButton(onClick = { web.goBack() }, enabled = canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    IconButton(onClick = { web.goForward() }, enabled = canForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.forward)) }
                    IconButton(onClick = { web.reload() }, enabled = loaded) { Icon(Icons.Filled.Refresh, stringResource(R.string.reload)) }
                    IconToggleButton(checked = reader, onCheckedChange = {
                        reader = it
                        readerState[0] = it
                        if (it) web.evaluateJavascript(READER_JS, null) else web.reload()
                    }) { Icon(Icons.AutoMirrored.Filled.MenuBook, stringResource(R.string.web_reader)) }
                    IconButton(onClick = { showHistory = true }) { Icon(Icons.Filled.History, stringResource(R.string.web_history)) }
                },
                floatingActionButton = {
                    FloatingActionButton(onClick = ::print) {
                        if (capturing) LoadingIndicator() else Icon(Icons.Filled.Print, stringResource(R.string.preview_print))
                    }
                },
                modifier = Modifier.imePadding(),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
            if (!loaded) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.web_start), style = MaterialTheme.typography.titleMedium)
                    history.items.take(8).forEach { v ->
                        ListItem(
                            headlineContent = { Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(v.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.clickable { address = v.url; go(v.url) },
                        )
                    }
                }
            }
        }
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text(stringResource(R.string.web_history)) },
            text = {
                if (history.items.isEmpty()) Text(stringResource(R.string.web_history_empty))
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(history.items, key = { it.url }) { v ->
                        ListItem(
                            headlineContent = { Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(v.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.clickable { showHistory = false; address = v.url; go(v.url) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text(stringResource(R.string.close)) } },
            dismissButton = { TextButton(onClick = { history.clear() }) { Text(stringResource(R.string.web_history_clear)) } },
        )
    }
}
