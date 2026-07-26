package com.dalapenko.laba.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.cash.turbine.test
import com.dalapenko.laba.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val mockRepository = mockk<SettingsRepository>()
    private val themeModeFlow = MutableStateFlow(ThemeMode.SYSTEM)

    @Before
    fun setup() {
        every { mockRepository.themeMode } returns themeModeFlow
        coEvery { mockRepository.setThemeMode(any()) } returns Unit
        mockkStatic(AppCompatDelegate::class)
        mockkStatic(LocaleListCompat::class)
        every { AppCompatDelegate.setApplicationLocales(any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkStatic(AppCompatDelegate::class)
        unmockkStatic(LocaleListCompat::class)
    }

    private fun createViewModel() = SettingsViewModel(mockRepository)

    @Test
    fun givenRepositoryThemeMode_whenViewModelCreated_thenExposesCurrentValue() = runTest {
        themeModeFlow.value = ThemeMode.DARK
        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, vm.themeMode.value)
    }

    @Test
    fun givenRepositoryEmitsNewValue_whenObservingThemeMode_thenViewModelStateUpdates() = runTest {
        val vm = createViewModel()

        vm.themeMode.test {
            assertEquals(ThemeMode.SYSTEM, awaitItem())
            themeModeFlow.value = ThemeMode.LIGHT
            assertEquals(ThemeMode.LIGHT, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun whenSetTheme_thenRepositorySetThemeModeCalledWithSameMode() = runTest {
        val vm = createViewModel()

        vm.setTheme(ThemeMode.DARK)
        advanceUntilIdle()

        coVerify { mockRepository.setThemeMode(ThemeMode.DARK) }
    }

    @Test
    fun givenLanguageTag_whenSetLanguage_thenLocalesResolvedFromTag() {
        val locales = mockk<LocaleListCompat>()
        every { LocaleListCompat.forLanguageTags("ru") } returns locales
        val vm = createViewModel()

        vm.setLanguage("ru")

        verify { AppCompatDelegate.setApplicationLocales(locales) }
    }

    @Test
    fun givenNullLanguageTag_whenSetLanguage_thenEmptyLocaleListApplied() {
        val emptyLocales = mockk<LocaleListCompat>()
        every { LocaleListCompat.getEmptyLocaleList() } returns emptyLocales
        val vm = createViewModel()

        vm.setLanguage(null)

        verify { AppCompatDelegate.setApplicationLocales(emptyLocales) }
    }
}
