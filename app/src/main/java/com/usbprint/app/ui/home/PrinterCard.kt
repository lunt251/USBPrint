package com.usbprint.app.ui.home

import android.content.Intent
import androidx.annotation.DrawableRes
import com.usbprint.app.ui.common.Led
import com.usbprint.app.ui.common.StatusLed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.usbprint.app.R
import com.usbprint.core.usb.Brand
import com.usbprint.core.usb.HostBasedFirmware
import com.usbprint.core.usb.PortStatus
import com.usbprint.core.usb.PrinterState
import com.usbprint.core.usb.UsbPrinter

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PrinterCard(
    state: PrinterState,
    onAllowAccess: () -> Unit,
    onTestPage: (UsbPrinter) -> Unit,
    driverNameFor: (UsbPrinter) -> String? = { null },
    /** (id, name) of every built-in driver, for the manual picker. */
    driverChoices: List<Pair<String, String>> = emptyList(),
    onSelectDriver: (UsbPrinter, String?) -> Unit = { _, _ -> },
    /** A job is running: the status chip shows printing. */
    printing: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val connected = state as? PrinterState.Connected

    if (picking && connected != null) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.driver_change)) },
            text = {
                LazyColumn {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.driver_automatic)) },
                            supportingContent = { Text(stringResource(R.string.driver_automatic_summary)) },
                            modifier = Modifier.clickable { picking = false; onSelectDriver(connected.printer, null) },
                        )
                    }
                    items(driverChoices) { (id, name) ->
                        ListItem(
                            headlineContent = { Text(name) },
                            modifier = Modifier.clickable { picking = false; onSelectDriver(connected.printer, id) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.close)) } },
        )
    }

    Card(
        onClick = { if (connected != null) expanded = !expanded },
        enabled = connected != null,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Column(Modifier.padding(20.dp).heightIn(min = 56.dp)) {
            when (state) {
                PrinterState.Searching -> LoadingRow(stringResource(R.string.printer_searching), stringResource(R.string.printer_searching_hint))
                PrinterState.Disconnected -> DisconnectedRow()
                is PrinterState.Identifying -> LoadingRow(stringResource(R.string.printer_identifying, state.label), null)
                is PrinterState.NeedsPermission -> PermissionRow(state.label, onAllowAccess)
                is PrinterState.Connected -> ConnectedContent(state.printer, expanded, printing, onTestPage, driverNameFor(state.printer)) { picking = true }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingRow(text: String, hint: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(48.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.titleMedium)
            if (hint != null) {
                Spacer(Modifier.height(4.dp))
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ConnectionGlyph(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.size(56.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun PrinterGlyph() = ConnectionGlyph {
    Icon(painterResource(R.drawable.ic_printer), contentDescription = null, modifier = Modifier.size(32.dp))
}

@Composable
private fun DisconnectedRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PrinterGlyph()
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.printer_disconnected), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.printer_disconnected_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRow(label: String, onAllow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectionGlyph {
            Icon(Icons.Outlined.Usb, contentDescription = null, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.printer_needs_permission),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onAllow) { Text(stringResource(R.string.printer_allow)) }
    }
}

private fun ledFor(status: PortStatus?): Led = when (status?.summary) {
    PortStatus.Summary.READY, null -> Led.READY
    PortStatus.Summary.OFFLINE -> Led.IDLE
    PortStatus.Summary.PAPER_OUT, PortStatus.Summary.ERROR -> Led.ERROR
}

@Composable
private fun ConnectedContent(
    printer: UsbPrinter,
    expanded: Boolean,
    printing: Boolean,
    onTestPage: (UsbPrinter) -> Unit,
    driverName: String?,
    onChangeDriver: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PrinterGlyph()
        Column(Modifier.weight(1f)) {
            BrandLogo(printer)
            Spacer(Modifier.height(4.dp))
            Text(printer.displayModel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            StatusChip(printer.status, printing)
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { onTestPage(printer) }) { Text(stringResource(R.string.printer_test_page)) }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(if (expanded) R.string.printer_hide_details else R.string.printer_tap_for_details),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        PrinterDetails(printer, driverName, onChangeDriver)
    }
}

@Composable
private fun BrandLogo(printer: UsbPrinter) {
    val resId = brandLogoResource(printer.brand)
    if (resId != null) {
        Icon(
            painter = painterResource(resId),
            contentDescription = printer.displayBrand,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 144.dp).height(24.dp),
        )
    } else {
        Text(printer.displayBrand, style = MaterialTheme.typography.headlineSmall)
    }
}

// Static references keep the bundled vectors available in resource-shrunk builds.
@DrawableRes
private fun brandLogoResource(brand: Brand): Int? = when (brand) {
    Brand.BIXOLON -> R.drawable.brand_bixolon
    Brand.BRADY -> R.drawable.brand_brady
    Brand.BROTHER -> R.drawable.brand_brother
    Brand.CANON -> R.drawable.brand_canon
    Brand.CITIZEN -> R.drawable.brand_citizen
    Brand.DELL -> R.drawable.brand_dell
    Brand.DYMO -> R.drawable.brand_dymo
    Brand.EPSON -> R.drawable.brand_epson
    Brand.HP -> R.drawable.brand_hp
    Brand.KONICA_MINOLTA -> R.drawable.brand_konica_minolta
    Brand.KYOCERA -> R.drawable.brand_kyocera
    Brand.LEXMARK -> R.drawable.brand_lexmark
    Brand.OKI -> R.drawable.brand_oki
    Brand.RICOH -> R.drawable.brand_ricoh
    Brand.SAMSUNG -> R.drawable.brand_samsung
    Brand.SHARP -> R.drawable.brand_sharp
    Brand.TOSHIBA -> R.drawable.brand_toshiba
    Brand.TSC -> R.drawable.brand_tsc
    Brand.XEROX -> R.drawable.brand_xerox
    else -> null
}

@Composable
private fun StatusChip(status: PortStatus?, printing: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val label = when {
        printing -> R.string.status_printing
        else -> when (status?.summary) {
            PortStatus.Summary.READY -> R.string.status_ready
            PortStatus.Summary.PAPER_OUT -> R.string.status_paper_out
            PortStatus.Summary.OFFLINE -> R.string.status_offline
            PortStatus.Summary.ERROR -> R.string.status_error
            null -> R.string.status_unknown
        }
    }
    Surface(shape = MaterialTheme.shapes.small, color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusLed(if (printing) Led.BUSY else ledFor(status), live = printing)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PrinterDetails(printer: UsbPrinter, driverName: String?, onChangeDriver: () -> Unit) {
    val context = LocalContext.current
    val id = printer.deviceId
    val rows = buildList {
        add(R.string.detail_driver to driverName)
        add(R.string.detail_manufacturer to printer.manufacturer)
        add(R.string.detail_model to printer.model)
        add(R.string.detail_description to id?.description)
        add(R.string.detail_serial to printer.serial)
        add(R.string.detail_languages to id?.commandSet?.joinToString(", ")?.ifEmpty { null })
        add(R.string.detail_class to id?.deviceClass)
        add(R.string.detail_usb_ids to "%04X:%04X".format(printer.vendorId, printer.productId))
        add(R.string.detail_usb_version to printer.usbVersion)
        add(R.string.detail_interface to "#${printer.interfaceNumber} (alt ${printer.alternateSetting}), ${printer.protocolName}")
        add(
            R.string.detail_endpoints to buildString {
                append("Bulk OUT #${printer.bulkOutIndex}")
                if (printer.bulkInIndex >= 0) append(", Bulk IN #${printer.bulkInIndex}")
            },
        )
        add(R.string.detail_status to printer.status?.let { "0x%02X".format(it.raw) })
        HostBasedFirmware.firmwareFileFor(printer)?.let {
            add(R.string.detail_firmware to (if (HostBasedFirmware.isLoaded(id)) "Loaded" else "Not loaded") + " ($it)")
        }
    }.filter { it.second != null }

    Column(Modifier.padding(top = 16.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { (label, value) -> DetailRow(stringResource(label), value!!) }
                id?.raw?.let { raw ->
                    Text(stringResource(R.string.detail_raw_id), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(raw, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(12.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onChangeDriver) { Text(stringResource(R.string.driver_change)) }
            OutlinedButton(onClick = {
                val report = buildString {
                    appendLine("USBPrint ${com.usbprint.app.BuildConfig.VERSION_NAME} printer report")
                    appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    rows.forEach { (label, value) -> appendLine("${context.getString(label)}: $value") }
                    id?.raw?.let { appendLine("Device ID: $it") }
                }
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            }) { Text(stringResource(R.string.printer_share_report)) }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
