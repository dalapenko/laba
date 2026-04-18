package com.dalapenko.laba.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dalapenko.laba.BuildConfig
import com.dalapenko.laba.R
import org.koin.androidx.compose.koinViewModel

private const val GITHUB_URL = "https://github.com/dalapenko/laba"
private const val PRIVACY_POLICY_URL = "https://dalapenko.github.io/laba/privacy-policy"

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val viewModel: SettingsViewModel = koinViewModel()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

    val appLocales = AppCompatDelegate.getApplicationLocales()
    val languageLabel = when {
        appLocales.isEmpty -> stringResource(R.string.settings_language_system)
        appLocales.toLanguageTags().startsWith("ru") -> stringResource(R.string.settings_language_ru)
        else -> stringResource(R.string.settings_language_en)
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val uriHandler = LocalUriHandler.current
    val showLicenseDialog = remember { mutableStateOf(false) }
    val showThemeDialog = remember { mutableStateOf(false) }
    val showLanguageDialog = remember { mutableStateOf(false) }
    val themeLabel = when (themeMode) {
        ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
        ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGeneralSection(
                themeLabel = themeLabel,
                languageLabel = languageLabel,
                onThemeClick = { showThemeDialog.value = true },
                onLanguageClick = { showLanguageDialog.value = true },
            )
            SettingsAboutSection(
                uriHandler = uriHandler,
                onLicenseClick = { showLicenseDialog.value = true },
            )
        }
    }

    if (showThemeDialog.value) {
        ThemeDialog(
            current = themeMode,
            onSelect = { viewModel.setTheme(it) },
            onDismiss = { showThemeDialog.value = false },
        )
    }

    if (showLanguageDialog.value) {
        LanguageDialog(
            currentTag = if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                null
            } else {
                AppCompatDelegate.getApplicationLocales().toLanguageTags()
            },
            onSelect = { viewModel.setLanguage(it) },
            onDismiss = { showLanguageDialog.value = false },
        )
    }

    if (showLicenseDialog.value) {
        LicenseDialog(onDismiss = { showLicenseDialog.value = false })
    }
}

@Composable
private fun SettingsGeneralSection(
    themeLabel: String,
    languageLabel: String,
    onThemeClick: () -> Unit,
    onLanguageClick: () -> Unit,
) {
    SectionHeader(title = stringResource(R.string.settings_section_general))

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_appearance)) },
        supportingContent = { Text(themeLabel) },
        leadingContent = {
            SettingsItemIcon(Icons.Outlined.Palette)
        },
        modifier = Modifier.clickable(onClick = onThemeClick),
    )

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_language)) },
        supportingContent = { Text(languageLabel) },
        leadingContent = {
            SettingsItemIcon(Icons.Outlined.Language)
        },
        modifier = Modifier.clickable(onClick = onLanguageClick),
    )

    Spacer(Modifier.height(8.dp))
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingsAboutSection(
    uriHandler: androidx.compose.ui.platform.UriHandler,
    onLicenseClick: () -> Unit,
) {
    SectionHeader(title = stringResource(R.string.settings_section_about))

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_version)) },
        supportingContent = { Text(settingsVersionText()) },
        leadingContent = { SettingsItemIcon(Icons.Outlined.Info) },
    )

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_author)) },
        supportingContent = { Text(stringResource(R.string.settings_author_value)) },
        leadingContent = { SettingsItemIcon(Icons.Outlined.Person) },
    )

    ExternalLinkItem(
        title = stringResource(R.string.settings_github),
        url = GITHUB_URL,
        icon = { SettingsItemIcon(Icons.Outlined.Code) },
        uriHandler = uriHandler,
    )

    ExternalLinkItem(
        title = stringResource(R.string.settings_privacy_policy),
        url = PRIVACY_POLICY_URL,
        icon = { SettingsItemIcon(Icons.Outlined.Policy) },
        uriHandler = uriHandler,
    )

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_licenses)) },
        supportingContent = { Text(stringResource(R.string.settings_licenses_description)) },
        leadingContent = { SettingsItemIcon(Icons.Outlined.Description) },
        modifier = Modifier.clickable(onClick = onLicenseClick),
    )
}

@Composable
private fun SettingsItemIcon(imageVector: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExternalLinkItem(
    title: String,
    url: String,
    icon: @Composable () -> Unit,
    uriHandler: androidx.compose.ui.platform.UriHandler,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = url,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        leadingContent = icon,
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable { uriHandler.openUri(url) },
    )
}

@Composable
private fun settingsVersionText(): String {
    val storeLabel = if (BuildConfig.DEBUG) "debug" else BuildConfig.STORE_LABEL
    return stringResource(
        R.string.settings_version_value,
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
    ) + if (storeLabel.isNotEmpty()) " $storeLabel" else ""
}

@Composable
private fun ThemeDialog(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_appearance)) },
        text = {
            Column {
                options.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(mode)
                                onDismiss()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = current == mode,
                            onClick = {
                                onSelect(mode)
                                onDismiss()
                            },
                        )
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_licenses_close))
            }
        },
    )
}

@Composable
private fun LanguageDialog(
    currentTag: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // null = system, "en" = English, "ru" = Russian
    val options = listOf(
        null to stringResource(R.string.settings_language_system),
        "en" to stringResource(R.string.settings_language_en),
        "ru" to stringResource(R.string.settings_language_ru),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                options.forEach { (tag, label) ->
                    val selected = when {
                        tag == null -> currentTag == null
                        else -> currentTag?.startsWith(tag) == true
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(tag)
                                onDismiss()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = {
                                onSelect(tag)
                                onDismiss()
                            },
                        )
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_licenses_close))
            }
        },
    )
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_licenses)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.settings_app_license_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_app_license_text),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_third_party_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_licenses_text),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_licenses_close))
            }
        },
    )
}
