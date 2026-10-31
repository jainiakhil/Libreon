// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** App preferences, kept in SharedPreferences and exposed as state. */
class Settings(context: Context) {
    enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM,
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    /** Whether the tab strip shows on wide screens (it takes vertical room in landscape). */
    private val _tabBar = MutableStateFlow(prefs.getBoolean(KEY_TAB_BAR, true))
    val tabBar: StateFlow<Boolean> = _tabBar.asStateFlow()

    /** Tabs whose document is shown with light pages while the app is dark. */
    private val _lightTabs = MutableStateFlow(prefs.getStringSet(KEY_LIGHT_TABS, emptySet()).orEmpty().toSet())
    val lightTabs: StateFlow<Set<String>> = _lightTabs.asStateFlow()

    /** The one-time tip on selecting text in Office documents has been dismissed. */
    private val _officeTipSeen = MutableStateFlow(prefs.getBoolean(KEY_OFFICE_TIP, false))
    val officeTipSeen: StateFlow<Boolean> = _officeTipSeen.asStateFlow()

    fun dismissOfficeTip() {
        _officeTipSeen.value = true
        prefs.edit().putBoolean(KEY_OFFICE_TIP, true).apply()
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun setTabBar(visible: Boolean) {
        _tabBar.value = visible
        prefs.edit().putBoolean(KEY_TAB_BAR, visible).apply()
    }

    fun setLightPages(tabId: String, light: Boolean) {
        _lightTabs.update { if (light) it + tabId else it - tabId }
        prefs.edit().putStringSet(KEY_LIGHT_TABS, _lightTabs.value).apply()
    }

    /** Drops entries for tabs that no longer exist. */
    fun retainLightTabs(openTabIds: Set<String>) {
        if (_lightTabs.value.all { it in openTabIds }) return
        _lightTabs.update { it intersect openTabIds }
        prefs.edit().putStringSet(KEY_LIGHT_TABS, _lightTabs.value).apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_TAB_BAR = "tabBar"
        const val KEY_LIGHT_TABS = "lightTabs"
        const val KEY_OFFICE_TIP = "officeTipSeen"
    }
}
