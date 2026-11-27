package com.usbprint.app.ui.files

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.usbprint.app.R
import com.usbprint.app.files.FileItem
import com.usbprint.app.files.FileRepository
import com.usbprint.app.files.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    photos: Boolean,
    showHidden: Boolean,
    onShowHidden: (Boolean) -> Unit,
    onOpen: (List<Uri>) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(FileRepository.hasAllFilesAccess(context)) }
    var hasPhotoAccess by remember { mutableStateOf(FileRepository.hasPhotoAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasAccess = FileRepository.hasAllFilesAccess(context)
        hasPhotoAccess = FileRepository.hasPhotoAccess(context)
    }

    var dirPath by rememberSaveable { mutableStateOf(FileRepository.root.path) }
    val dir = File(dirPath)
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortMode.NAME) }
    var menu by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    val selected = remember { mutableStateListOf<Uri>() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasAccess = FileRepository.hasAllFilesAccess(context)
        hasPhotoAccess = FileRepository.hasPhotoAccess(context)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) onOpen(uris)
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onOpen(if (photos) uris else uris.take(1))
    }

    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }.onFailure {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val recents by produceState(emptyList<FileItem>(), hasAccess, hasPhotoAccess, refreshTick) {
        value = if (hasAccess || (photos && hasPhotoAccess)) withContext(Dispatchers.IO) { FileRepository.recents(context, photos) } else emptyList()
    }
    val listing by produceState<List<FileItem>?>(null, dirPath, hasAccess, sort, showHidden, refreshTick) {
        value = null
        value = if (hasAccess) withContext(Dispatchers.IO) { FileRepository.list(dir, photos, showHidden, sort) } else emptyList()
    }
    val results by produceState(emptyList<FileItem>(), query, searching, hasAccess) {
        if (!searching || query.isBlank() || !hasAccess) { value = emptyList(); return@produceState }
        delay(250)
        value = withContext(Dispatchers.IO) { FileRepository.search(FileRepository.root, query, photos, showHidden) }
    }

    val atRoot = dir.path == FileRepository.root.path
    BackHandler(enabled = searching || selected.isNotEmpty() || !atRoot) {
        when {
            selected.isNotEmpty() -> selected.clear()
            searching -> { searching = false; query = "" }
            else -> dirPath = dir.parentFile?.path ?: FileRepository.root.path
        }
    }

    fun tap(item: FileItem) {
        when {
            item.isDir -> dirPath = item.file!!.path
            selected.isNotEmpty() -> if (item.uri in selected) selected.remove(item.uri) else selected.add(item.uri)
            else -> onOpen(listOf(item.uri))
        }
    }

    fun longPress(item: FileItem) {
        if (photos && !item.isDir && item.uri !in selected) selected.add(item.uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            selected.isNotEmpty() -> selected.clear()
                            searching -> { searching = false; query = "" }
                            else -> onBack()
                        }
                    }) {
                        Icon(
                            if (selected.isNotEmpty() || searching) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                title = {
                    when {
                        selected.isNotEmpty() -> Text(stringResource(R.string.files_selected, selected.size))
                        searching -> TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.files_search_hint)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        else -> Text(stringResource(if (photos) R.string.tile_photos else R.string.tile_documents))
                    }
                },
                actions = {
                    if (!searching && selected.isEmpty()) {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, stringResource(R.string.search)) }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.options)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Text(
                                stringResource(R.string.files_sort), style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            listOf(SortMode.NAME to R.string.sort_name, SortMode.DATE to R.string.sort_date, SortMode.SIZE to R.string.sort_size).forEach { (m, label) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    leadingIcon = { RadioButton(selected = sort == m, onClick = null) },
                                    onClick = { sort = m; menu = false },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_show_hidden)) },
                                leadingIcon = { Checkbox(checked = showHidden, onCheckedChange = null) },
                                onClick = { onShowHidden(!showHidden); menu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_system_picker)) },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                                onClick = {
                                    menu = false
                                    docPicker.launch(if (photos) arrayOf("image/*") else arrayOf("*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_refresh)) },
                                onClick = { refreshTick++; menu = false },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (selected.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { val list = selected.toList(); selected.clear(); onOpen(list) },
                    icon = { Icon(Icons.Filled.Print, null) },
                    text = { Text(stringResource(R.string.files_open_selected, selected.size)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 120.dp)) {
            if (!hasAccess) {
                item {
                    PermissionCard(
                        photos = photos,
                        onGrant = ::requestAccess,
                        onPhotoPermission = if (photos && Build.VERSION.SDK_INT >= 33 && !hasPhotoAccess) {
                            { permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES) }
                        } else null,
                        onPicker = {
                            if (photos) photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            else docPicker.launch(arrayOf("*/*"))
                        },
                    )
                }
            }

            if (searching) {
                if (results.isEmpty() && query.isNotBlank()) {
                    item { Text(stringResource(R.string.files_no_results), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
                }
                items(results, key = { "s" + it.uri }) { item ->
                    FileRow(item, item.uri in selected, onClick = { tap(item) }, onLongClick = { longPress(item) }, showPath = true)
                }
                return@LazyColumn
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.files_recent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (photos) {
                        FilledTonalButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.files_photo_picker))
                        }
                    }
                }
            }
            item {
                if (recents.isEmpty()) {
                    Text(
                        stringResource(R.string.files_no_recent), Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(recents, key = { "r" + it.uri }) { item ->
                            RecentTile(item, item.uri in selected, onClick = { tap(item) }, onLongClick = { longPress(item) })
                        }
                    }
                }
            }

            if (hasAccess) {
                item {
                    Breadcrumbs(dir) { dirPath = it.path }
                }
                val list = listing
                if (list == null) {
                    item { Spacer(Modifier.height(48.dp)) }
                } else if (list.isEmpty()) {
                    item { Text(stringResource(R.string.files_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(list, key = { it.uri.toString() }) { item ->
                        FileRow(item, item.uri in selected, onClick = { tap(item) }, onLongClick = { longPress(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(photos: Boolean, onGrant: () -> Unit, onPhotoPermission: (() -> Unit)?, onPicker: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.files_access_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (photos) R.string.files_access_body_photos else R.string.files_access_body), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Button(onClick = onGrant) { Text(stringResource(R.string.files_grant)) }
                if (onPhotoPermission != null) {
                    FilledTonalButton(onClick = onPhotoPermission) { Text(stringResource(R.string.files_grant_photos)) }
                }
                FilledTonalButton(onClick = onPicker) { Text(stringResource(R.string.files_system_picker)) }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(dir: File, onNavigate: (File) -> Unit) {
    val root = FileRepository.root
    val chain = remember(dir.path) {
        generateSequence(dir) { f -> if (f.path == root.path) null else f.parentFile }.toList().reversed()
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chain.forEachIndexed { i, f ->
            if (i > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            AssistChip(
                onClick = { onNavigate(f) },
                label = { Text(if (f.path == root.path) stringResource(R.string.files_internal) else f.name, maxLines = 1) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentTile(item: FileItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.width(112.dp).clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            Thumbnail(item, 112.dp, 140.dp)
            if (selected) SelectedBadge(Modifier.align(Alignment.TopEnd))
        }
        Text(
            item.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun SelectedBadge(modifier: Modifier) {
    Icon(
        Icons.Filled.CheckCircle, null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(6.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50)),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(item: FileItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, showPath: Boolean = false) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        leadingContent = {
            if (item.isDir) {
                Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp).padding(4.dp))
            } else {
                Thumbnail(item, 40.dp, 40.dp)
            }
        },
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val info = if (item.isDir) DateUtils.getRelativeTimeSpanString(item.modified).toString()
            else "${Formatter.formatShortFileSize(context, item.size)} · ${DateUtils.getRelativeTimeSpanString(item.modified)}"
            Text(if (showPath) (item.file?.parent?.removePrefix(FileRepository.root.path)?.ifEmpty { "/" } ?: info) else info, maxLines = 1)
        },
        trailingContent = { if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
    )
}

@Composable
private fun Thumbnail(item: FileItem, width: Dp, height: Dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { maxOf(width, height).roundToPx() }
    val bmp by produceState<android.graphics.Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { FileRepository.thumbnail(context, item, px) }
    }
    Box(
        Modifier.size(width, height).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (height > 60.dp) {
                    Text(item.ext.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
