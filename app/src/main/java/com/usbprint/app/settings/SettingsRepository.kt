package com.usbprint.app.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val autoOpenOnAttach: Boolean = false,
    val rememberEdits: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val AUTO_OPEN = booleanPreferencesKey("auto_open")
        val REMEMBER_EDITS = booleanPreferencesKey("remember_edits")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            autoOpenOnAttach = p[Keys.AUTO_OPEN] ?: false,
            rememberEdits = p[Keys.REMEMBER_EDITS] ?: true,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC] = enabled }

    suspend fun setRememberEdits(enabled: Boolean) = context.dataStore.edit { it[Keys.REMEMBER_EDITS] = enabled }

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
