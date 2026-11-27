package com.usbprint.app.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.ui.platform.LocalContext
import com.usbprint.core.print.PaperSize
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.usbprint.app.BuildConfig
import com.usbprint.app.R
import com.usbprint.app.settings.AppSettings
import com.usbprint.app.settings.ThemeMode

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
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.tile_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(R.string.settings_appearance)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_theme)) },
                supportingContent = {
                    val modes = ThemeMode.entries
                    val labels = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark, R.string.theme_black)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        modes.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { onThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) { Text(stringResource(labels[i])) }
                        }
                    }
                },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchItem(R.string.settings_dynamic_color, R.string.settings_dynamic_color_summary, settings.dynamicColor, onDynamicColor)
            }

            SectionHeader(R.string.settings_printer)
            SwitchItem(R.string.settings_auto_open, R.string.settings_auto_open_summary, settings.autoOpenOnAttach, onAutoOpen)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_default_paper)) },
                supportingContent = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        PaperSize.entries.forEach { p ->
                            FilterChip(selected = settings.defaultPaper == p, onClick = { onDefaultPaper(p) }, label = { Text(p.displayName) })
                        }
                    }
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_print_service)) },
                supportingContent = { Text(stringResource(R.string.settings_print_service_summary)) },
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
                },
            )
            SwitchItem(R.string.settings_keep_history, R.string.settings_keep_history_summary, settings.keepHistory, onKeepHistory)
            ListItem(
                headlineContent = { Text(stringResource(R.string.history_title)) },
                supportingContent = { Text(stringResource(R.string.settings_history_summary)) },
                modifier = Modifier.clickable(onClick = onOpenHistory),
            )

            SectionHeader(R.string.settings_editing)
            SwitchItem(R.string.settings_remember_edits, R.string.settings_remember_edits_summary, settings.rememberEdits, onRememberEdits)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_clear_edits)) },
                modifier = Modifier.clickable(onClick = onClearEdits),
            )

            SectionHeader(R.string.settings_files_web)
            SwitchItem(R.string.files_show_hidden, R.string.settings_show_hidden_summary, settings.showHiddenFiles, onShowHidden)
            SwitchItem(R.string.settings_reader_default, R.string.settings_reader_default_summary, settings.readerModeDefault, onReaderDefault)

            SectionHeader(R.string.settings_about)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_license)) },
                supportingContent = { Text(stringResource(R.string.settings_license_value)) },
            )
        }
    }
}

@Composable
private fun SectionHeader(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchItem(@StringRes title: Int, @StringRes summary: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(summary)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier,
    )
}
