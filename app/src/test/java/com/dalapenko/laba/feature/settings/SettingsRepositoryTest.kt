package com.dalapenko.laba.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SettingsRepositoryTest {

    private lateinit var fakeDataStore: FakePreferencesDataStore
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        fakeDataStore = FakePreferencesDataStore()
        repository = SettingsRepository(fakeDataStore)
    }

    @Test
    fun givenNoStoredValue_whenObservingThemeMode_thenReturnsSystem() = runTest {
        repository.themeMode.test {
            assertEquals(ThemeMode.SYSTEM, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenDarkModeSaved_whenObservingThemeMode_thenReturnsDark() = runTest {
        fakeDataStore.setStringValue("theme_mode", ThemeMode.DARK.name)

        repository.themeMode.test {
            assertEquals(ThemeMode.DARK, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenLightModeSaved_whenObservingThemeMode_thenReturnsLight() = runTest {
        fakeDataStore.setStringValue("theme_mode", ThemeMode.LIGHT.name)

        repository.themeMode.test {
            assertEquals(ThemeMode.LIGHT, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenSystemMode_whenSetThemeMode_thenFlowEmitsSystem() = runTest {
        repository.setThemeMode(ThemeMode.DARK)
        repository.setThemeMode(ThemeMode.SYSTEM)

        repository.themeMode.test {
            assertEquals(ThemeMode.SYSTEM, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenInitialState_whenSetThemeModeToEachValue_thenEachRoundTripsCorrectly() = runTest {
        ThemeMode.entries.forEach { mode ->
            repository.setThemeMode(mode)
            repository.themeMode.test {
                assertEquals(mode, awaitItem())
                cancelAndConsumeRemainingEvents()
            }
        }
    }

    @Test
    fun givenNoStoredValue_whenObservingLastFixedSleepTimerMinutes_thenReturnsThirty() = runTest {
        repository.lastFixedSleepTimerMinutes.test {
            assertEquals(30, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenStoredFixedSleepTimerMinutes_whenObserving_thenEmitsPersistedValue() = runTest {
        fakeDataStore.setIntValue("last_fixed_sleep_timer_minutes", 75)

        repository.lastFixedSleepTimerMinutes.test {
            assertEquals(75, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenFixedSleepTimerMinutes_whenSet_thenRoundTripsCorrectly() = runTest {
        repository.setLastFixedSleepTimerMinutes(120)

        repository.lastFixedSleepTimerMinutes.test {
            assertEquals(120, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun givenOutOfRangeStoredFixedSleepTimerMinutes_whenObserving_thenReturnsThirty() = runTest {
        fakeDataStore.setIntValue("last_fixed_sleep_timer_minutes", 181)

        repository.lastFixedSleepTimerMinutes.test {
            assertEquals(30, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }
}

/**
 * Minimal in-process DataStore backed by a MutableStateFlow.
 * Supports the subset of DataStore API used by SettingsRepository (data + edit via updateData).
 */
private class FakePreferencesDataStore : DataStore<Preferences> {

    private val prefsFlow = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = prefsFlow

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val mutable = prefsFlow.value.toMutablePreferences()
        val result = transform(mutable)
        prefsFlow.value = result
        return result
    }

    /** Helper for test setup — writes a string key directly. */
    fun setStringValue(key: String, value: String) {
        val mutable = prefsFlow.value.toMutablePreferences()
        mutable[stringPreferencesKey(key)] = value
        prefsFlow.value = mutable.toPreferences()
    }

    /** Helper for test setup — writes an integer key directly. */
    fun setIntValue(key: String, value: Int) {
        val mutable = prefsFlow.value.toMutablePreferences()
        mutable[intPreferencesKey(key)] = value
        prefsFlow.value = mutable.toPreferences()
    }
}
