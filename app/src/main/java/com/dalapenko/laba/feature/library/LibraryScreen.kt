package com.dalapenko.laba.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dalapenko.laba.R
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onBookClick: (Long) -> Unit,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val scanResult by viewModel.scanResult.collectAsStateWithLifecycle()
    val playbackStatus by viewModel.playbackStatus.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val msgFileNotAvailable = stringResource(R.string.snackbar_file_not_available)
    val msgFilesDeleted = stringResource(R.string.snackbar_book_and_files_deleted)
    val msgFilesDeleteFailed = stringResource(R.string.snackbar_removed_from_library_no_delete)
    val scanResultMessage: String? = when (val result = scanResult) {
        is ScanResult.Success -> if (result.count == 1)
            stringResource(R.string.snackbar_added_single, result.title)
        else
            pluralStringResource(R.plurals.snackbar_added_books, result.count, result.count)
        is ScanResult.Empty -> stringResource(R.string.snackbar_no_audio_files)
        is ScanResult.PermissionError -> stringResource(R.string.snackbar_permission_denied)
        is ScanResult.Duplicate -> stringResource(R.string.snackbar_already_in_library)
        null -> null
    }
    var showFabMenu by remember { mutableStateOf(false) }
    val bookToDelete = remember { mutableStateOf<BookWithProgress?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.onFolderPicked(it, context.contentResolver) }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.onFilePicked(it, context.contentResolver) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                LibraryEvent.FileNotAvailable ->
                    snackbarHostState.showSnackbar(msgFileNotAvailable)
                LibraryEvent.FilesDeletedSuccessfully ->
                    snackbarHostState.showSnackbar(msgFilesDeleted)
                LibraryEvent.FilesDeleteFailed ->
                    snackbarHostState.showSnackbar(msgFilesDeleteFailed)
            }
        }
    }

    LaunchedEffect(scanResultMessage) {
        if (scanResultMessage != null) {
            snackbarHostState.showSnackbar(scanResultMessage)
            viewModel.clearScanResult()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { showFabMenu = true },
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_audiobook))
                }
                DropdownMenu(
                    expanded = showFabMenu,
                    onDismissRequest = { showFabMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_add_folder)) },
                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                        onClick = {
                            showFabMenu = false
                            folderPicker.launch(null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_add_file)) },
                        leadingIcon = { Icon(Icons.Default.AudioFile, contentDescription = null) },
                        onClick = {
                            showFabMenu = false
                            filePicker.launch(arrayOf("audio/*"))
                        },
                    )
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.resyncAll() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (books.isEmpty() && !isScanning) {
                EmptyLibrary(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(books, key = { it.book.id }) { bookWithProgress ->
                        val isActive = playbackStatus.activeBookId == bookWithProgress.book.id
                        val bookId = bookWithProgress.book.id
                        BookCard(
                            bookWithProgress = bookWithProgress,
                            isActive = isActive,
                            isPlaying = isActive && playbackStatus.isPlaying,
                            isAvailable = bookWithProgress.isAvailable,
                            onClick = {
                                if (bookWithProgress.isAvailable) onBookClick(bookId)
                                else viewModel.onUnavailableBookClicked()
                            },
                            onLongClick = { bookToDelete.value = bookWithProgress },
                            onCoverClick = when {
                                !bookWithProgress.isAvailable -> null
                                isActive && playbackStatus.isMediaLoaded -> viewModel::togglePlayPause
                                isActive -> { { viewModel.prepareAndPlay(bookId) } }
                                else -> null
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isScanning,
                modifier = Modifier.align(Alignment.Center),
            ) {
                CircularProgressIndicator()
            }
        }
    }

    bookToDelete.value?.let { book ->
        var deleteFiles by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { bookToDelete.value = null },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.dialog_delete_message, book.book.title))
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { deleteFiles = !deleteFiles }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = deleteFiles,
                            onCheckedChange = { deleteFiles = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.dialog_delete_also_files),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (deleteFiles) {
                                Text(
                                    text = stringResource(R.string.dialog_delete_irreversible),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBook(book, deleteFiles)
                    bookToDelete.value = null
                }) {
                    Text(
                        text = stringResource(R.string.dialog_delete_confirm),
                        color = if (deleteFiles) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete.value = null }) {
                    Text(stringResource(R.string.dialog_delete_cancel))
                }
            },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    bookWithProgress: BookWithProgress,
    isActive: Boolean,
    isPlaying: Boolean,
    isAvailable: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCoverClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val book = bookWithProgress.book
    val fraction = bookWithProgress.progressFraction
    val isCompleted = bookWithProgress.progress?.isCompleted == true

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isAvailable) 1f else 0.4f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            // Thumbnail
            BookThumbnail(
                coverUri = book.coverUri,
                title = book.title,
                isActive = isActive,
                isPlaying = isPlaying,
                onCoverClick = onCoverClick,
                modifier = Modifier
                    .size(72.dp)
                    .aspectRatio(1f),
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                book.author?.let { author ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))

                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(6.dp))

                if (isCompleted) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.chip_completed)) },
                    )
                } else {
                    Text(
                        text = stringResource(R.string.progress_percent, (fraction * 100).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookThumbnail(
    coverUri: String?,
    title: String,
    isActive: Boolean,
    isPlaying: Boolean,
    onCoverClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier = modifier) {
        if (coverUri != null) {
            AsyncImage(
                model = coverUri,
                contentDescription = stringResource(R.string.cd_cover_for, title),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title.take(2).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        if (isActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                    .then(
                        if (onCoverClick != null) Modifier.clickable(onClick = onCoverClick)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.cd_playing) else stringResource(R.string.cd_paused),
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_library_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_library_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
