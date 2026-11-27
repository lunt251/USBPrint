package com.usbprint.app.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SquareFoot
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.usbprint.app.settings.Units
import com.usbprint.app.ui.preview.CustomPaperDialog
import com.usbprint.app.ui.preview.PaperChips
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.usbprint.app.BuildConfig
import com.usbprint.app.R
import com.usbprint.app.settings.AppSettings
import com.usbprint.app.settings.ThemeMode
import com.usbprint.core.print.PaperSize

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onAutoOpen: (Boolean) -> Unit,
    onRememberEdits: (Boolean) -> Unit,
    onDefaultPaper: (PaperSize) -> Unit,
    onKeepHistory: (Boolean) -> Unit,
    onReaderDefault: (Boolean) -> Unit,
    onShowHidden: (Boolean) -> Unit,
    onOpenHistory: () -> Unit,
    onClearEdits: () -> Unit,
    onUnits: (Units) -> Unit,
    onShowDimensions: (Boolean) -> Unit,
    onAddPaper: (PaperSize) -> Unit,
    onRemovePaper: (PaperSize) -> Unit,
) {
    var addingPaper by remember { mutableStateOf(false) }
    if (addingPaper) {
        CustomPaperDialog(settings.units, onDismiss = { addingPaper = false }) { addingPaper = false; onAddPaper(it) }
    }
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.tile_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                Group(R.string.settings_appearance) {
                    Row(
                        Icons.Outlined.DarkMode, R.string.settings_theme,
                        supporting = {
                            val modes = ThemeMode.entries
                            val labels = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark, R.string.theme_black)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                modes.forEachIndexed { i, mode ->
                                    SegmentedButton(
                                        selected = settings.themeMode == mode,
                                        onClick = { onThemeMode(mode) },
                                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                        icon = {},
                                    ) {
                                        BasicText(
                                            stringResource(labels[i]),
                                            style = LocalTextStyle.current.copy(color = LocalContentColor.current),
                                            maxLines = 1,
                                            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                                        )
                                    }
                                }
                            }
                        },
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(Icons.Outlined.Palette, R.string.settings_dynamic_color, R.string.settings_dynamic_color_summary, settings.dynamicColor, onDynamicColor)
                    }
                }

                Group(R.string.settings_printer) {
                    SwitchRow(Icons.Outlined.Usb, R.string.settings_auto_open, R.string.settings_auto_open_summary, settings.autoOpenOnAttach, onAutoOpen)
                    Row(
                        Icons.Outlined.Description, R.string.settings_default_paper,
                        supporting = {
                            Column(Modifier.padding(top = 4.dp)) {
                                PaperChips(PaperSize.entries + settings.customPapers, settings.defaultPaper, onDefaultPaper) { addingPaper = true }
                            }
                        },
                    )
                    if (settings.customPapers.isNotEmpty()) {
                        Row(
                            Icons.Outlined.Straighten, R.string.settings_custom_papers,
                            supporting = {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    settings.customPapers.forEach { p ->
                                        InputChip(
                                            selected = false,
                                            onClick = { onRemovePaper(p) },
                                            label = { Text(p.displayName) },
                                            trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.delete), Modifier.size(18.dp)) },
                                        )
                                    }
                                }
                            },
                        )
                    }
                    Row(
                        Icons.Outlined.SquareFoot, R.string.settings_units,
                        supporting = {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                listOf(Units.CM to R.string.units_cm, Units.INCH to R.string.units_inch).forEachIndexed { i, (u, label) ->
                                    SegmentedButton(
                                        selected = settings.units == u,
                                        onClick = { onUnits(u) },
                                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                                    ) { Text(stringResource(label)) }
                                }
                            }
                        },
                    )
                    SwitchRow(Icons.Outlined.Straighten, R.string.settings_dimensions, R.string.settings_dimensions_summary, settings.showDimensions, onShowDimensions)
                    Row(
                        Icons.Outlined.Print, R.string.settings_print_service, R.string.settings_print_service_summary,
                        trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                        onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) } },
                    )
                }

                Group(R.string.history_title) {
                    SwitchRow(Icons.Outlined.Restore, R.string.settings_keep_history, R.string.settings_keep_history_summary, settings.keepHistory, onKeepHistory)
                    Row(Icons.Outlined.History, R.string.history_title, R.string.settings_history_summary, onClick = onOpenHistory)
                }

                Group(R.string.settings_editing) {
                    SwitchRow(Icons.Outlined.AutoFixHigh, R.string.settings_remember_edits, R.string.settings_remember_edits_summary, settings.rememberEdits, onRememberEdits)
                    Row(Icons.Outlined.DeleteSweep, R.string.settings_clear_edits, onClick = onClearEdits)
                }

                Group(R.string.settings_files_web) {
                    SwitchRow(Icons.Outlined.VisibilityOff, R.string.files_show_hidden, R.string.settings_show_hidden_summary, settings.showHiddenFiles, onShowHidden)
                    SwitchRow(Icons.AutoMirrored.Outlined.ChromeReaderMode, R.string.settings_reader_default, R.string.settings_reader_default_summary, settings.readerModeDefault, onReaderDefault)
                }

                Group(R.string.settings_about) {
                    Row(Icons.Outlined.Info, R.string.settings_version, supportingText = BuildConfig.VERSION_NAME)
                    Row(Icons.Outlined.Gavel, R.string.settings_license, R.string.settings_license_value)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Android 16-style settings group: a header, then rows in one rounded container split by 2dp gaps. */
@Composable
private fun Group(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
    Column(
        Modifier.clip(RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
private fun Row(
    icon: ImageVector,
    title: Int,
    summary: Int? = null,
    supportingText: String? = null,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val sub: (@Composable () -> Unit)? = supporting
        ?: summary?.let { s -> @Composable { Text(stringResource(s)) } }
        ?: supportingText?.let { t -> @Composable { Text(t) } }
    ListItem(
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = sub,
        trailingContent = trailing,
    )
}

@Composable
private fun SwitchRow(icon: ImageVector, title: Int, summary: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(icon, title, summary, trailing = { Switch(checked = checked, onCheckedChange = onChange) }, onClick = { onChange(!checked) })
}
