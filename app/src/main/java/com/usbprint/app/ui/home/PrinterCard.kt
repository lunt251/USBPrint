package com.usbprint.app.ui.home

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.usbprint.app.R
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
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val connected = state as? PrinterState.Connected

    Card(
        onClick = { if (connected != null) expanded = !expanded },
        enabled = connected != null,
        shape = RoundedCornerShape(28.dp),
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
                PrinterState.Searching -> LoadingRow(stringResource(R.string.printer_searching))
                is PrinterState.Identifying -> LoadingRow(stringResource(R.string.printer_identifying, state.label))
                is PrinterState.NeedsPermission -> PermissionRow(state.label, onAllowAccess)
                is PrinterState.Connected -> ConnectedContent(state.printer, expanded, onTestPage)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(44.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PermissionRow(label: String, onAllow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Usb, contentDescription = null, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.printer_needs_permission),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onAllow) { Text(stringResource(R.string.printer_allow)) }
    }
}

@Composable
private fun ConnectedContent(printer: UsbPrinter, expanded: Boolean, onTestPage: (UsbPrinter) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BrandLogo(printer)
            Spacer(Modifier.height(2.dp))
            Text(printer.displayModel, style = MaterialTheme.typography.titleMedium)
        }
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(R.string.printer_tap_for_details),
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusChip(printer.status)
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = { onTestPage(printer) }) { Text(stringResource(R.string.printer_test_page)) }
    }
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        PrinterDetails(printer)
    }
}

/** Shows `res/drawable/brand_<name>.xml` if present (monochrome, theme-tinted), else the brand as text. */
@SuppressLint("DiscouragedApi")
@Composable
private fun BrandLogo(printer: UsbPrinter) {
    val context = LocalContext.current
    val resName = "brand_" + printer.brand.name.lowercase()
    val resId = context.resources.getIdentifier(resName, "drawable", context.packageName)
    if (resId != 0) {
        Icon(
            painter = painterResource(resId),
            contentDescription = printer.displayBrand,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.height(28.dp),
        )
    } else {
        Text(printer.displayBrand, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusChip(status: PortStatus?) {
    val scheme = MaterialTheme.colorScheme
    val (label, dot) = when (status?.summary) {
        PortStatus.Summary.READY -> R.string.status_ready to Color(0xFF2E7D32)
        PortStatus.Summary.PAPER_OUT -> R.string.status_paper_out to scheme.error
        PortStatus.Summary.OFFLINE -> R.string.status_offline to scheme.outline
        PortStatus.Summary.ERROR -> R.string.status_error to scheme.error
        null -> R.string.status_unknown to scheme.primary
    }
    Surface(shape = CircleShape, color = scheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun PrinterDetails(printer: UsbPrinter) {
    val id = printer.deviceId
    val rows = buildList {
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
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
