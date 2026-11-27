package com.usbprint.app.ui.preview

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.usbprint.core.print.PaperSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.graphics.Color
import com.usbprint.app.ui.common.Island
import com.usbprint.app.ui.common.Led
import com.usbprint.app.ui.common.StatusLed
import com.usbprint.app.ui.common.bidi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbprint.app.R
import com.usbprint.app.USBPrintApp
import com.usbprint.app.doc.DocKind
import com.usbprint.app.edit.Filters
import com.usbprint.app.edit.PageEdit
import com.usbprint.app.render.SheetComposer
import com.usbprint.app.session.Exporter
import com.usbprint.app.session.LoadState
import com.usbprint.core.print.capt.CaptDriver
import com.usbprint.core.print.pcl.PclRasterDriver
import com.usbprint.core.usb.PrinterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreviewScreen(app: USBPrintApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val load by app.session.state.collectAsStateWithLifecycle()
    val edits by app.session.edits.collectAsStateWithLifecycle()
    val undoRedo by app.session.history.collectAsStateWithLifecycle()
    val printerState by app.printers.state.collectAsStateWithLifecycle()
    val printer = (printerState as? PrinterState.Connected)?.printer
    val appSettings by app.currentSettings.collectAsStateWithLifecycle()

    var editMode by rememberSaveable { mutableStateOf(false) }
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var perspective by rememberSaveable { mutableStateOf(false) }
    var compare by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    BackHandler(enabled = editMode) { editMode = false }

    val doc = (load as? LoadState.Ready)?.doc
    val composer = remember(doc, edits) { doc?.let { SheetComposer(it, edits, maxSourcePx = 1800) } }
    val pager = rememberPagerState { composer?.sheetCount ?: 1 }
    val sheet = pager.currentPage.coerceAtMost(((composer?.sheetCount ?: 1) - 1).coerceAtLeast(0))
    val page = composer?.sheetPages(sheet)?.firstOrNull() ?: 0
    val tabs = doc?.let { EditTab.forKind(it.kind) } ?: emptyList()

    // All source pages (ignoring the range) for the checkbox strip.
    val thumbComposer = remember(doc, edits.text, edits.layout.paper, edits.layout.orientation) {
        doc?.let { SheetComposer(it, edits.copy(print = edits.print.copy(pageRange = "")), maxSourcePx = 400) }
    }
    val pageCount = thumbComposer?.pages?.size ?: 0
    val selectedPages = remember(edits.print.pageRange, pageCount) {
        if (edits.print.pageRange.isBlank()) (0 until pageCount).toSet()
        else com.usbprint.app.edit.parsePageRange(edits.print.pageRange, pageCount).toSet()
    }
    val tab = tabs.getOrNull(tabIndex) ?: tabs.firstOrNull()

    // Suggest (never auto-apply) clean-up for photographed documents.
    val suggestCleanup by produceState(false, doc, page) {
        value = false
        val c = composer ?: return@produceState
        if (doc?.kind != DocKind.IMAGES) return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching { Filters.looksLikeScan(c.processed(page, PageEdit.DEFAULT, 256)) }.getOrDefault(false)
        }
    }

    val resolved = printer?.let { app.printing.resolve(it) }
    val darknessSupported = resolved?.driver.let { it is CaptDriver || it is PclRasterDriver }

    fun doPrint() {
        val p = printer
        val d = doc
        if (p == null || d == null) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.preview_no_printer)) }
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        editMode = false
        val err = app.printing.printComposed(p, SheetComposer(d, edits, maxSourcePx = 3000))
        if (err != null) scope.launch { snackbar.showSnackbar(err) }
    }

    fun export(pdf: Boolean) {
        val c = composer ?: return
        val full = SheetComposer(c.source, c.edits, 2400)
        busy = true
        scope.launch {
            val msg = runCatching {
                if (pdf) Exporter.exportPdf(context, full, c.source.title) else Exporter.exportImages(context, full, c.source.title)
            }.fold({ context.getString(R.string.export_saved, it) }, { context.getString(R.string.export_failed, it.message ?: "") })
            busy = false
            snackbar.showSnackbar(msg)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (editMode) editMode = false else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                title = {
                    if (editMode) {
                        Text(stringResource(R.string.preview_edit))
                    } else Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusLed(
                                when {
                                    printer == null -> Led.IDLE
                                    app.printing.isBusy -> Led.BUSY
                                    else -> Led.READY
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                printer?.let { bidi("${it.displayBrand} ${it.displayModel}") } ?: stringResource(R.string.preview_no_printer_short),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        doc?.let {
                            Text(bidi(it.title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    if (editMode) {
                        IconButton(onClick = app.session::undo, enabled = undoRedo.first) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                        IconButton(onClick = app.session::redo, enabled = undoRedo.second) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                        // Hold to compare with the original.
                        Box(
                            Modifier.size(48.dp).pointerInput(Unit) {
                                detectTapGestures(onPress = { compare = true; tryAwaitRelease(); compare = false })
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Compare, stringResource(R.string.edit_compare)) }
                        Button(
                            onClick = { editMode = false },
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) { Text(stringResource(R.string.done)) }
                    }
                    if (doc != null && !editMode) {
                        IconToggleButton(
                            checked = appSettings.showDimensions,
                            onCheckedChange = { on -> scope.launch { app.settings.setShowDimensions(on) } },
                        ) { Icon(Icons.Filled.Straighten, stringResource(R.string.settings_dimensions)) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.options)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.export_pdf)) }, onClick = { menu = false; export(true) }, enabled = !busy)
                                DropdownMenuItem(text = { Text(stringResource(R.string.export_images)) }, onClick = { menu = false; export(false) }, enabled = !busy)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_reset_all)) },
                                    onClick = { menu = false; app.session.update(edits.copy(pages = emptyMap())) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val s = load) {
                LoadState.Idle, LoadState.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.preview_loading))
                    }
                }
                is LoadState.Error -> Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge)
                }
                is LoadState.Ready -> {
                    val c = composer!!
                    doc!!.notice?.let { notice ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Box(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                            .clip(MaterialTheme.shapes.extraLarge)
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        if (editMode && tab == EditTab.CROP) {
                            CropView(
                                composer = c, page = page, edit = edits.page(page), perspective = perspective,
                                onStart = app.session::checkpoint,
                                onQuad = { q -> app.session.update(app.session.edits.value.withPage(page, app.session.edits.value.page(page).copy(crop = q)), record = false) },
                            )
                        } else {
                            HorizontalPager(state = pager, userScrollEnabled = !editMode, modifier = Modifier.fillMaxSize()) { i ->
                                SheetView(
                                    composer = c, sheet = i, compare = compare, singleFingerPan = editMode,
                                    showDimensions = appSettings.showDimensions,
                                    units = appSettings.units,
                                    outline = editMode && tab == EditTab.LAYOUT,
                                    onGestureStart = {
                                        app.session.checkpoint()
                                        // Moving the page is an edit: open Edit on the Layout tab.
                                        if (!editMode) {
                                            editMode = true
                                            tabIndex = tabs.indexOf(EditTab.LAYOUT).coerceAtLeast(0)
                                        }
                                    },
                                    onTransform = { pe ->
                                        val cur = app.session.edits.value
                                        val first = c.sheetPages(i).firstOrNull() ?: return@SheetView
                                        val old = cur.page(first)
                                        app.session.update(cur.withPage(first, old.copy(scale = pe.scale, offsetX = pe.offsetX, offsetY = pe.offsetY)), record = false)
                                    },
                                )
                            }
                        }
                        if (suggestCleanup && !edits.page(page).hasCleanup) {
                            AssistChip(
                                onClick = {
                                    app.session.update(edits.withPage(page, edits.page(page).copy(whiten = 0.5f, deshadow = 0.7f, sharpen = 0.3f, contrast = 0.1f)))
                                },
                                label = { Text(stringResource(R.string.cleanup_suggest)) },
                                leadingIcon = { Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(18.dp)) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.preview_sheet_of, sheet + 1, c.sheetCount) +
                            if (edits.page(page).scale != 1f) " · ${(edits.page(page).scale * 100).toInt()}%" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp),
                    )
                    AnimatedContent(targetState = editMode, label = "bottom") { editing ->
                        if (editing && tab != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
                            ) {
                                Column(Modifier.navigationBarsPadding()) {
                                    val slide = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
                                    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                                    val sizeSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
                                    AnimatedContent(
                                        targetState = tab,
                                        transitionSpec = {
                                            // M3 shared axis X: slide 30dp in the tab direction with a fade-through.
                                            val dir = if (tabs.indexOf(targetState) >= tabs.indexOf(initialState)) 1 else -1
                                            (slideInHorizontally(slide) { dir * it / 8 } + fadeIn(fade)) togetherWith
                                                (slideOutHorizontally(slide) { -dir * it / 8 } + fadeOut(fade)) using
                                                SizeTransform(clip = true) { _, _ -> sizeSpec }
                                        },
                                        label = "edit-tab",
                                    ) { shownTab ->
                                    EditControls(
                                        tab = shownTab,
                                        kind = doc.kind,
                                        a = EditActions(
                                            edits = edits, page = page, pageCount = c.pages.size,
                                            baseSize = c.placement(sheet)?.let { (cell, _) -> c.baseSize(cell, c.contentAspect(cell.page)) },
                                            units = appSettings.units,
                                            papers = PaperSize.entries + appSettings.customPapers,
                                            onAddPaper = { pp -> scope.launch { app.settings.addCustomPaper(pp) } },
                                            begin = app.session::checkpoint,
                                            live = { app.session.update(it, record = false) },
                                            commit = { app.session.update(it) },
                                        ),
                                        perspective = perspective,
                                        onPerspective = { perspective = it },
                                        darknessSupported = darknessSupported,
                                    )
                                    }
                                    PrimaryScrollableTabRow(
                                        selectedTabIndex = tabs.indexOf(tab),
                                        edgePadding = 8.dp,
                                        containerColor = Color.Transparent,
                                        divider = {},
                                    ) {
                                        tabs.forEachIndexed { i, t ->
                                            Tab(
                                                selected = t == tab,
                                                onClick = { tabIndex = i },
                                                text = { Text(stringResource(t.label), maxLines = 1) },
                                                icon = { Icon(t.icon, null) },
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Island(
                                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                                radius = 28.dp,
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    if (pageCount > 1) {
                                        PagePicker(
                                            composer = thumbComposer!!,
                                            selected = selectedPages,
                                            onChange = { set ->
                                                app.session.update(edits.copy(print = edits.print.copy(pageRange = rangeOf(set, pageCount))))
                                            },
                                        )
                                        Spacer(Modifier.height(12.dp))
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        FilledTonalButton(
                                            onClick = { editMode = true },
                                            shapes = ButtonDefaults.shapes(),
                                            modifier = Modifier.weight(1f).height(56.dp),
                                        ) {
                                            Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.preview_edit))
                                        }
                                        Button(
                                            onClick = ::doPrint,
                                            enabled = selectedPages.isNotEmpty(),
                                            shapes = ButtonDefaults.shapes(),
                                            modifier = Modifier.weight(1f).height(56.dp),
                                        ) {
                                            Icon(Icons.Filled.Print, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.preview_print))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(composer?.sheetCount) {
        val n = composer?.sheetCount ?: return@LaunchedEffect
        if (pager.currentPage >= n) pager.scrollToPage(n - 1)
    }
}
