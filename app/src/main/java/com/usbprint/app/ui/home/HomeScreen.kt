package com.usbprint.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.usbprint.app.R
import com.usbprint.app.ui.Routes
import com.usbprint.app.ui.common.containerTransform
import com.usbprint.core.usb.PrinterState
import com.usbprint.core.usb.UsbPrinter
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    printerState: PrinterState,
    onAllowAccess: () -> Unit,
    /** Starts a test page; returns an error message if it can't start. */
    onTestPage: (UsbPrinter) -> String?,
    driverNameFor: (UsbPrinter) -> String?,
    driverChoices: List<Pair<String, String>>,
    onSelectDriver: (UsbPrinter, String?) -> Unit,
    onOpen: (String) -> Unit,
    printing: Boolean = false,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
                )

                PrinterCard(
                    state = printerState,
                    onAllowAccess = onAllowAccess,
                    driverNameFor = driverNameFor,
                    driverChoices = driverChoices,
                    onSelectDriver = onSelectDriver,
                    printing = printing,
                    onTestPage = { printer ->
                        onTestPage(printer)?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                    },
                )

                ActionGrid(onOpen)
                // Room for the print card so it never covers the grid.
                Spacer(Modifier.height(120.dp))
            }
        }
    }
}

private data class Tile(val route: String, val label: Int, val icon: ImageVector)

private val tiles = listOf(
    Tile(Routes.DOCUMENTS, R.string.tile_documents, Icons.Outlined.Description),
    Tile(Routes.PHOTOS, R.string.tile_photos, Icons.Outlined.PhotoLibrary),
    Tile(Routes.WEB, R.string.tile_web, Icons.Outlined.Public),
    Tile(Routes.SETTINGS, R.string.tile_settings, Icons.Outlined.Settings),
)

/** 2x2 on phones, 1x4 on tablets / unfolded foldables. */
@Composable
private fun ActionGrid(onOpen: (String) -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val rows = if (wide) listOf(tiles) else tiles.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile ->
                    ActionTile(tile, colorIndex = tiles.indexOf(tile), onOpen = onOpen, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionTile(tile: Tile, colorIndex: Int, onOpen: (String) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    // Tonal role pairs, so the tiles follow the wallpaper colours.
    val (container, content, badge) = when (colorIndex) {
        0 -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
        1 -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary)
        2 -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary)
        else -> Triple(scheme.surfaceContainerHighest, scheme.onSurface, scheme.onSurfaceVariant)
    }
    val shape = when (colorIndex) {
        0 -> MaterialShapes.Cookie9Sided
        1 -> MaterialShapes.Flower
        2 -> MaterialShapes.Clover4Leaf
        else -> MaterialShapes.Cookie7Sided
    }
    Card(
        onClick = { onOpen(tile.route) },
        modifier = modifier.aspectRatio(1f).containerTransform("tile-${tile.route}", MaterialTheme.shapes.extraLarge, isSource = true),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier.size(56.dp).clip(shape.toShape()).background(badge),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tile.icon, contentDescription = null, tint = container, modifier = Modifier.size(28.dp))
            }
            Text(stringResource(tile.label), style = MaterialTheme.typography.titleLarge)
        }
    }
}
