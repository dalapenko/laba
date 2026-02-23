package com.dalapenko.laba.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dalapenko.laba.BuildConfig
import com.dalapenko.laba.R
import com.dalapenko.laba.ui.theme.LabaTheme

private const val GITHUB_URL = "https://github.com/dalapenko/laba"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val uriHandler = LocalUriHandler.current
    var showLicenseDialog by remember { mutableStateOf(false) }

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
            // ── General ─────────────────────────────────────────────
            SectionHeader(title = stringResource(R.string.settings_section_general))

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_appearance)) },
                supportingContent = { Text(stringResource(R.string.settings_appearance_value)) },
                leadingContent = {
                    Icon(
                        Icons.Outlined.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                supportingContent = { Text(stringResource(R.string.settings_language_value)) },
                leadingContent = {
                    Icon(
                        Icons.Outlined.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))

            // ── About ───────────────────────────────────────────────
            SectionHeader(title = stringResource(R.string.settings_section_about))

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.settings_version_value,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                        )
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_author)) },
                supportingContent = { Text(stringResource(R.string.settings_author_value)) },
                leadingContent = {
                    Icon(
                        Icons.Outlined.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_github)) },
                supportingContent = {
                    Text(
                        text = GITHUB_URL,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Outlined.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.clickable { uriHandler.openUri(GITHUB_URL) },
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_licenses)) },
                supportingContent = { Text(stringResource(R.string.settings_licenses_description)) },
                leadingContent = {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.clickable { showLicenseDialog = true },
            )
        }
    }

    if (showLicenseDialog) {
        LicenseDialog(onDismiss = { showLicenseDialog = false })
    }
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

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    LabaTheme {
        SettingsScreen(onBack = {})
    }
}
