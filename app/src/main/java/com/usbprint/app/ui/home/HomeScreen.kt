package com.usbprint.app.ui.home

import androidx.compose.foundation.layout.Arrangement
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
                    style = MaterialTheme.typography.headlineMedium,
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

@Composable
private fun ActionTile(tile: Tile, colorIndex: Int, onOpen: (String) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = { onOpen(tile.route) },
        modifier = modifier.aspectRatio(1.15f),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.onSurface.copy(alpha = 0.08f)),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer, contentColor = scheme.onSurface),
    ) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // The first tile is where most jobs start, so it carries the accent.
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (colorIndex == 0) scheme.primaryContainer else scheme.surfaceContainerHighest,
                contentColor = if (colorIndex == 0) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            ) {
                Icon(tile.icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(24.dp))
            }
            Text(stringResource(tile.label), style = MaterialTheme.typography.titleMedium)
        }
    }
}
