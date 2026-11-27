package com.usbprint.app.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.usbprint.core.print.PaperSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val autoOpenOnAttach: Boolean = false,
    val rememberEdits: Boolean = true,
    val defaultPaper: PaperSize = PaperSize.A4,
    val keepHistory: Boolean = true,
    val showHiddenFiles: Boolean = false,
    val readerModeDefault: Boolean = false,
    /** Printer key → driver id chosen by the user. */
    val driverOverrides: Map<String, String> = emptyMap(),
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val AUTO_OPEN = booleanPreferencesKey("auto_open")
        val REMEMBER_EDITS = booleanPreferencesKey("remember_edits")
        val PAPER = stringPreferencesKey("default_paper")
        val HISTORY = booleanPreferencesKey("keep_history")
        val HIDDEN = booleanPreferencesKey("show_hidden")
        val READER = booleanPreferencesKey("reader_default")
        val DRIVERS = stringSetPreferencesKey("driver_overrides")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC] ?: false,
            autoOpenOnAttach = p[Keys.AUTO_OPEN] ?: false,
            rememberEdits = p[Keys.REMEMBER_EDITS] ?: true,
            defaultPaper = p[Keys.PAPER]?.let { runCatching { PaperSize.valueOf(it) }.getOrNull() } ?: PaperSize.A4,
            keepHistory = p[Keys.HISTORY] ?: true,
            showHiddenFiles = p[Keys.HIDDEN] ?: false,
            readerModeDefault = p[Keys.READER] ?: false,
            driverOverrides = p[Keys.DRIVERS].orEmpty().mapNotNull { s ->
                val i = s.lastIndexOf('=')
                if (i <= 0) null else s.substring(0, i) to s.substring(i + 1)
            }.toMap(),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC] = enabled }

    suspend fun setRememberEdits(enabled: Boolean) = context.dataStore.edit { it[Keys.REMEMBER_EDITS] = enabled }

    suspend fun setDefaultPaper(paper: PaperSize) = context.dataStore.edit { it[Keys.PAPER] = paper.name }

    suspend fun setKeepHistory(enabled: Boolean) = context.dataStore.edit { it[Keys.HISTORY] = enabled }

    suspend fun setShowHidden(enabled: Boolean) = context.dataStore.edit { it[Keys.HIDDEN] = enabled }

    suspend fun setReaderDefault(enabled: Boolean) = context.dataStore.edit { it[Keys.READER] = enabled }

    /** [driverId] null = automatic. */
    suspend fun setDriverOverride(printerKey: String, driverId: String?) = context.dataStore.edit { p ->
        val rest = p[Keys.DRIVERS].orEmpty().filterNot { it.startsWith("$printerKey=") }.toSet()
        p[Keys.DRIVERS] = if (driverId == null) rest else rest + "$printerKey=$driverId"
    }

    suspend fun setAutoOpen(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_OPEN] = enabled }
        // The USB_DEVICE_ATTACHED filter lives on an activity-alias so it can be switched on/off.
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, "com.usbprint.app.UsbAttachAlias"),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
