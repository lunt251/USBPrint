package com.usbprint.app.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
import com.usbprint.app.settings.Units
import kotlin.math.abs
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.usbprint.app.R
import com.usbprint.app.doc.DocKind
import com.usbprint.app.edit.DocEdits
import com.usbprint.app.edit.FitMode
import com.usbprint.app.edit.IdSize
import com.usbprint.app.edit.Orientation
import com.usbprint.app.edit.PageEdit
import com.usbprint.app.edit.PhotoMode
import com.usbprint.app.edit.Quad
import com.usbprint.app.edit.TextAlign
import com.usbprint.app.edit.TextFont
import com.usbprint.core.print.PaperSize
import kotlin.math.roundToInt

enum class EditTab(val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    LAYOUT(R.string.edit_layout, Icons.Filled.Dashboard),
    CROP(R.string.edit_crop, Icons.Filled.Crop),
    CLEANUP(R.string.edit_cleanup, Icons.Filled.AutoFixHigh),
    ADJUST(R.string.edit_adjust, Icons.Filled.Tune),
    TEXT(R.string.edit_text, Icons.Filled.TextFields),
    PRINT(R.string.edit_print, Icons.Filled.Print),
    ;

    companion object {
        fun forKind(kind: DocKind) = when (kind) {
            DocKind.TEXT -> listOf(TEXT, LAYOUT, PRINT)
            else -> listOf(LAYOUT, CROP, CLEANUP, ADJUST, PRINT)
        }
    }
}

/** Callbacks shared by all edit controls. [begin] records an undo step once per drag. */
class EditActions(
    val edits: DocEdits,
    /** Content size at scale 1 (points) for the current page, if known. */
    val baseSize: Pair<Float, Float>? = null,
    val units: Units = Units.CM,
    val papers: List<PaperSize> = PaperSize.entries,
    val onAddPaper: (PaperSize) -> Unit = {},
    val page: Int,
    val pageCount: Int,
    val begin: () -> Unit,
    /** Live change without an undo step (call [begin] first). */
    val live: (DocEdits) -> Unit,
    /** Discrete change with its own undo step. */
    val commit: (DocEdits) -> Unit,
) {
    val pageEdit get() = edits.page(page)
    fun livePage(e: PageEdit) = live(edits.withPage(page, e))
    fun commitPage(e: PageEdit) = commit(edits.withPage(page, e))
    fun applyToAll() {
        val src = pageEdit
        var out = edits
        for (i in 0 until pageCount) if (i != page) out = out.withPage(i, out.page(i).styleFrom(src))
        commit(out)
    }
}

@Composable
fun EditControls(tab: EditTab, kind: DocKind, a: EditActions, perspective: Boolean, onPerspective: (Boolean) -> Unit, darknessSupported: Boolean) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (tab) {
            EditTab.LAYOUT -> LayoutControls(kind, a)
            EditTab.CROP -> CropControls(a, perspective, onPerspective)
            EditTab.CLEANUP -> CleanupControls(a)
            EditTab.ADJUST -> AdjustControls(a)
            EditTab.TEXT -> TextControls(a)
            EditTab.PRINT -> PrintControls(a, darknessSupported)
        }
    }
}

@Composable
private fun EditSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    a: EditActions,
    onChange: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = value.coerceIn(range),
        onValueChange = {
            if (!dragging) { dragging = true; a.begin() }
            onChange(it)
        },
        onValueChangeFinished = { dragging = false },
        valueRange = range,
    )
}

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
private fun signedPct(v: Float) = (v * 100).roundToInt().let { if (it > 0) "+$it" else "$it" }

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (v, label) ->
            SegmentedButton(
                selected = v == selected,
                onClick = { onSelect(v) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayoutControls(kind: DocKind, a: EditActions) {
    val l = a.edits.layout
    fun set(nl: com.usbprint.app.edit.LayoutSettings) = a.commit(a.edits.copy(layout = nl))

    SectionLabel(stringResource(R.string.layout_paper))
    var adding by remember { mutableStateOf(false) }
    PaperChips(a.papers, l.paper, onSelect = { set(l.copy(paper = it)) }, onAdd = { adding = true })
    if (adding) {
        CustomPaperDialog(a.units, onDismiss = { adding = false }) { p ->
            adding = false
            a.onAddPaper(p)
            set(l.copy(paper = p))
        }
    }
    SectionLabel(stringResource(R.string.layout_orientation))
    Segmented(
        listOf(Orientation.AUTO to stringResource(R.string.layout_auto), Orientation.PORTRAIT to stringResource(R.string.layout_portrait), Orientation.LANDSCAPE to stringResource(R.string.layout_landscape)),
        l.orientation,
    ) { set(l.copy(orientation = it)) }

    if (kind == DocKind.IMAGES) {
        SectionLabel(stringResource(R.string.layout_photo_layout))
        Segmented(
            listOf(PhotoMode.ONE to stringResource(R.string.layout_one), PhotoMode.GRID to stringResource(R.string.layout_grid), PhotoMode.ID to stringResource(R.string.layout_id)),
            l.photoMode,
        ) { set(l.copy(photoMode = it)) }
        when (l.photoMode) {
            PhotoMode.GRID -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 4, 6, 9).forEach { n ->
                    FilterChip(selected = l.gridCount == n, onClick = { set(l.copy(gridCount = n)) }, label = { Text(stringResource(R.string.layout_per_sheet, n)) })
                }
            }
            PhotoMode.ID -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IdSize.entries.forEach { s ->
                    FilterChip(selected = l.idSize == s, onClick = { set(l.copy(idSize = s)) }, label = { Text(s.label) })
                }
            }
            PhotoMode.ONE -> {}
        }
        SwitchRow(stringResource(R.string.layout_borderless), l.borderless) { set(l.copy(borderless = it)) }
    } else if (kind != DocKind.TEXT) {
        SectionLabel(stringResource(R.string.layout_pages_per_sheet))
        Segmented(listOf(1 to "1", 2 to "2", 4 to "4"), l.pagesPerSheet) { set(l.copy(pagesPerSheet = it)) }
    }

    if (kind != DocKind.TEXT && !(kind == DocKind.IMAGES && l.photoMode != PhotoMode.ONE)) {
        SectionLabel(stringResource(R.string.layout_fit))
        Segmented(listOf(FitMode.FIT to stringResource(R.string.layout_fit_page), FitMode.FILL to stringResource(R.string.layout_fill_page)), l.fit) {
            set(l.copy(fit = it))
        }
        EditSlider(stringResource(R.string.layout_scale), a.pageEdit.scale, 0.1f..3f, ::pct, a) { a.livePage(a.pageEdit.copy(scale = it)) }
        a.baseSize?.let { DimensionFields(a, it) }
    }
    if (!l.borderless && kind != DocKind.TEXT) {
        EditSlider(stringResource(R.string.layout_margins), l.marginMm, 0f..25f, { "${it.roundToInt()} mm" }, a) {
            a.live(a.edits.copy(layout = l.copy(marginMm = it)))
        }
    }
}

@Composable
private fun CropControls(a: EditActions, perspective: Boolean, onPerspective: (Boolean) -> Unit) {
    val e = a.pageEdit
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { a.commitPage(e.copy(rotation = (e.rotation + 270) % 360, crop = Quad.FULL)) }) {
            Icon(Icons.AutoMirrored.Filled.RotateLeft, stringResource(R.string.crop_rotate_left))
        }
        FilledTonalIconButton(onClick = { a.commitPage(e.copy(rotation = (e.rotation + 90) % 360, crop = Quad.FULL)) }) {
            Icon(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.crop_rotate_right))
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { a.commitPage(e.copy(crop = Quad.FULL, rotation = 0)) }) {
            Icon(Icons.Filled.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.reset))
        }
    }
    SwitchRow(stringResource(R.string.crop_perspective), perspective, onPerspective)
    Text(stringResource(R.string.crop_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (a.pageCount > 1) ApplyAll(a)
}

@Composable
private fun ApplyAll(a: EditActions) {
    OutlinedButton(onClick = a::applyToAll, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.edit_apply_all)) }
}

@Composable
private fun CleanupControls(a: EditActions) {
    val e = a.pageEdit
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = e.styleFrom(PageEdit.ENHANCE_SCAN).let { it.whiten == e.whiten && it.deshadow == e.deshadow && e.hasCleanup },
            onClick = { a.commitPage(e.copy(whiten = 0.5f, deshadow = 0.7f, sharpen = 0.3f, contrast = 0.1f)) },
            label = { Text(stringResource(R.string.cleanup_enhance)) },
            leadingIcon = { Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(18.dp)) },
        )
        TextButton(onClick = { a.commitPage(e.copy(whiten = 0f, deshadow = 0f, sharpen = 0f, denoise = 0f, bwScan = false)) }) {
            Text(stringResource(R.string.reset))
        }
    }
    EditSlider(stringResource(R.string.cleanup_whiten), e.whiten, 0f..1f, ::pct, a) { a.livePage(a.pageEdit.copy(whiten = it)) }
    EditSlider(stringResource(R.string.cleanup_shadows), e.deshadow, 0f..1f, ::pct, a) { a.livePage(a.pageEdit.copy(deshadow = it)) }
    EditSlider(stringResource(R.string.cleanup_sharpen), e.sharpen, 0f..1f, ::pct, a) { a.livePage(a.pageEdit.copy(sharpen = it)) }
    EditSlider(stringResource(R.string.cleanup_denoise), e.denoise, 0f..1f, ::pct, a) { a.livePage(a.pageEdit.copy(denoise = it)) }
    SwitchRow(stringResource(R.string.cleanup_bw), e.bwScan) { a.commitPage(a.pageEdit.copy(bwScan = it)) }
    if (a.pageCount > 1) ApplyAll(a)
}

@Composable
private fun AdjustControls(a: EditActions) {
    val e = a.pageEdit
    EditSlider(stringResource(R.string.adjust_brightness), e.brightness, -1f..1f, ::signedPct, a) { a.livePage(a.pageEdit.copy(brightness = it)) }
    EditSlider(stringResource(R.string.adjust_contrast), e.contrast, -1f..1f, ::signedPct, a) { a.livePage(a.pageEdit.copy(contrast = it)) }
    EditSlider(stringResource(R.string.adjust_saturation), e.saturation, -1f..1f, ::signedPct, a) { a.livePage(a.pageEdit.copy(saturation = it)) }
    EditSlider(stringResource(R.string.adjust_warmth), e.warmth, -1f..1f, ::signedPct, a) { a.livePage(a.pageEdit.copy(warmth = it)) }
    SwitchRow(stringResource(R.string.adjust_grayscale), e.grayscale) { a.commitPage(a.pageEdit.copy(grayscale = it)) }
    TextButton(onClick = { a.commitPage(e.copy(brightness = 0f, contrast = 0f, saturation = 0f, warmth = 0f, grayscale = false)) }) {
        Text(stringResource(R.string.reset))
    }
    if (a.pageCount > 1) ApplyAll(a)
}

@Composable
private fun TextControls(a: EditActions) {
    val t = a.edits.text
    fun live(nt: com.usbprint.app.edit.TextOptions) = a.live(a.edits.copy(text = nt))
    fun set(nt: com.usbprint.app.edit.TextOptions) = a.commit(a.edits.copy(text = nt))
    SectionLabel(stringResource(R.string.text_font))
    Segmented(
        listOf(TextFont.SANS to stringResource(R.string.text_sans), TextFont.SERIF to stringResource(R.string.text_serif), TextFont.MONO to stringResource(R.string.text_mono)),
        t.font,
    ) { set(t.copy(font = it)) }
    EditSlider(stringResource(R.string.text_size), t.sizePt, 6f..36f, { "${it.roundToInt()} pt" }, a) { live(t.copy(sizePt = it.roundToInt().toFloat())) }
    EditSlider(stringResource(R.string.text_spacing), t.lineSpacing, 0.8f..2.5f, { "%.1f×".format(it) }, a) { live(t.copy(lineSpacing = (it * 10).roundToInt() / 10f)) }
    EditSlider(stringResource(R.string.layout_margins), t.marginMm, 5f..40f, { "${it.roundToInt()} mm" }, a) { live(t.copy(marginMm = it.roundToInt().toFloat())) }
    SectionLabel(stringResource(R.string.text_align))
    Segmented(
        listOf(TextAlign.START to stringResource(R.string.text_left), TextAlign.CENTER to stringResource(R.string.text_center), TextAlign.END to stringResource(R.string.text_right), TextAlign.JUSTIFY to stringResource(R.string.text_justify)),
        t.align,
    ) { set(t.copy(align = it)) }
    SwitchRow(stringResource(R.string.text_bold), t.bold) { set(t.copy(bold = it)) }
}

@Composable
private fun PrintControls(a: EditActions, darknessSupported: Boolean) {
    val p = a.edits.print
    fun set(np: com.usbprint.app.edit.PrintSettings) = a.commit(a.edits.copy(print = np))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.print_copies), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = { set(p.copy(copies = (p.copies - 1).coerceAtLeast(1))) }) { Icon(Icons.Filled.Remove, null) }
        Text("${p.copies}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
        FilledTonalIconButton(onClick = { set(p.copy(copies = (p.copies + 1).coerceAtMost(99))) }) { Icon(Icons.Filled.Add, null) }
    }
    var range by remember(p.pageRange) { mutableStateOf(p.pageRange) }
    OutlinedTextField(
        value = range,
        onValueChange = { v ->
            range = v.filter { it.isDigit() || it == '-' || it == ',' || it == ' ' }
            a.commit(a.edits.copy(print = p.copy(pageRange = range)))
        },
        label = { Text(stringResource(R.string.print_range)) },
        placeholder = { Text(stringResource(R.string.print_range_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    if (darknessSupported) {
        SwitchRow(stringResource(R.string.print_darkness_auto), p.darkness < 0) { set(p.copy(darkness = if (it) -1 else 8)) }
        if (p.darkness >= 0) {
            EditSlider(stringResource(R.string.print_darkness), p.darkness.toFloat(), 0f..15f, { "${it.roundToInt()}" }, a) {
                a.live(a.edits.copy(print = p.copy(darkness = it.roundToInt())))
            }
        }
        SwitchRow(stringResource(R.string.print_toner_save), p.tonerSave) { set(p.copy(tonerSave = it)) }
    }
}

private fun toPt(v: Float, units: Units) = if (units == Units.CM) v / 2.54f * 72f else v * 72f
private fun fromPt(pt: Float, units: Units) = if (units == Units.CM) pt / 72f * 2.54f else pt / 72f
private fun unitLabel(units: Units) = if (units == Units.CM) "cm" else "in"
private fun fmt(pt: Float, units: Units) = if (units == Units.CM) "%.1f".format(fromPt(pt, units)) else "%.2f".format(fromPt(pt, units))

/** Horizontally scrolling paper sizes: presets, then custom sizes, then "Add size". */
@Composable
fun PaperChips(papers: List<PaperSize>, selected: PaperSize, onSelect: (PaperSize) -> Unit, onAdd: () -> Unit) {
    val list = if (selected in papers) papers else papers + selected
    // Plain scrolling Row (not a lazy list) so it can live inside ListItem, which measures intrinsics.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { p ->
            FilterChip(selected = p == selected, onClick = { onSelect(p) }, label = { Text(p.displayName) })
        }
        AssistChip(
            onClick = onAdd,
            label = { Text(stringResource(R.string.paper_add)) },
            leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) },
        )
    }
}

/** Name + width Ã— height in the user's units. */
@Composable
fun CustomPaperDialog(units: Units, onDismiss: () -> Unit, onSave: (PaperSize) -> Unit) {
    var name by remember { mutableStateOf("") }
    var w by remember { mutableStateOf("") }
    var h by remember { mutableStateOf("") }
    val wv = w.replace(',', '.').toFloatOrNull()
    val hv = h.replace(',', '.').toFloatOrNull()
    val valid = wv != null && hv != null && wv > 0f && hv > 0f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paper_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.paper_name)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        w, { w = it }, label = { Text(stringResource(R.string.dim_width)) }, singleLine = true,
                        suffix = { Text(unitLabel(units)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        h, { h = it }, label = { Text(stringResource(R.string.dim_height)) }, singleLine = true,
                        suffix = { Text(unitLabel(units)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val wp = toPt(wv!!, units).roundToInt()
                val hp = toPt(hv!!, units).roundToInt()
                val label = name.trim().ifEmpty { "${fmt(wp.toFloat(), units)} Ã— ${fmt(hp.toFloat(), units)} ${unitLabel(units)}" }
                onSave(PaperSize.custom(label, wp, hp))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Width/height of the content; locked keeps proportions, unlocked stretches. */
@Composable
private fun DimensionFields(a: EditActions, base: Pair<Float, Float>) {
    val e = a.pageEdit
    val units = a.units
    val wPt = base.first * e.scale * e.stretchX
    val hPt = base.second * e.scale * e.stretchY
    var locked by remember { mutableStateOf(abs(e.stretchX - e.stretchY) < 0.001f) }

    fun applyWidth(pt: Float) {
        a.commitPage(
            if (locked) e.copy(scale = (pt / (base.first * e.stretchX)).coerceIn(0.05f, 10f))
            else e.copy(stretchX = (pt / (base.first * e.scale)).coerceIn(0.05f, 10f)),
        )
    }
    fun applyHeight(pt: Float) {
        a.commitPage(
            if (locked) e.copy(scale = (pt / (base.second * e.stretchY)).coerceIn(0.05f, 10f))
            else e.copy(stretchY = (pt / (base.second * e.scale)).coerceIn(0.05f, 10f)),
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DimField(stringResource(R.string.dim_width), wPt, units, Modifier.weight(1f), ::applyWidth)
        FilledTonalIconToggleButton(
            checked = locked,
            onCheckedChange = { on ->
                locked = on
                // Locking returns to proportional, keeping the current width.
                if (on && abs(e.stretchX - e.stretchY) > 0.001f) a.commitPage(e.copy(scale = e.scale * e.stretchX, stretchX = 1f, stretchY = 1f))
            },
        ) {
            Icon(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, stringResource(if (locked) R.string.dim_locked else R.string.dim_unlocked))
        }
        DimField(stringResource(R.string.dim_height), hPt, units, Modifier.weight(1f), ::applyHeight)
    }
}

@Composable
private fun DimField(label: String, valuePt: Float, units: Units, modifier: Modifier, onApply: (Float) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(fmt(valuePt, units)) }
    LaunchedEffect(valuePt, units, focused) { if (!focused) text = fmt(valuePt, units) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            v.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }?.let { onApply(toPt(it, units)) }
        },
        label = { Text(label) },
        suffix = { Text(unitLabel(units)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    )
}
