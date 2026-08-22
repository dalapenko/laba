package com.dalapenko.laba.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dalapenko.laba.feature.library.LibrarySortOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private val themeModePref = stringPreferencesKey("theme_mode")
    private val lastFixedSleepTimerMinutesPref = intPreferencesKey("last_fixed_sleep_timer_minutes")
    private val librarySortOptionPref = stringPreferencesKey("library_sort_option")

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        ThemeMode.valueOf(prefs[themeModePref] ?: ThemeMode.SYSTEM.name)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[themeModePref] = mode.name }
    }

    val lastFixedSleepTimerMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[lastFixedSleepTimerMinutesPref]
            ?.takeIf { it in MIN_SLEEP_TIMER_MINUTES..MAX_SLEEP_TIMER_MINUTES }
            ?: DEFAULT_SLEEP_TIMER_MINUTES
    }

    suspend fun setLastFixedSleepTimerMinutes(minutes: Int) {
        dataStore.edit { it[lastFixedSleepTimerMinutesPref] = minutes }
    }

    val librarySortOption: Flow<LibrarySortOption> = dataStore.data.map { prefs ->
        LibrarySortOption.entries.firstOrNull { it.name == prefs[librarySortOptionPref] }
            ?: DEFAULT_LIBRARY_SORT_OPTION
    }

    suspend fun setLibrarySortOption(option: LibrarySortOption) {
        dataStore.edit { it[librarySortOptionPref] = option.name }
    }
}

private val DEFAULT_LIBRARY_SORT_OPTION = LibrarySortOption.ADDED_AT_DESC
private const val DEFAULT_SLEEP_TIMER_MINUTES = 30
private const val MIN_SLEEP_TIMER_MINUTES = 1
private const val MAX_SLEEP_TIMER_MINUTES = 180
