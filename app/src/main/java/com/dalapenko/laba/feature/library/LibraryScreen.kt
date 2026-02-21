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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
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
    var showFabMenu by remember { mutableStateOf(false) }
    var bookToDelete by remember { mutableStateOf<BookWithProgress?>(null) }
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

    LaunchedEffect(scanResult) {
        when (val result = scanResult) {
            is ScanResult.Empty -> {
                snackbarHostState.showSnackbar("No audio files found")
                viewModel.clearScanResult()
            }
            is ScanResult.PermissionError -> {
                snackbarHostState.showSnackbar("Permission denied. Please re-pick the folder.")
                viewModel.clearScanResult()
            }
            is ScanResult.Success -> {
                snackbarHostState.showSnackbar("Added: ${result.title}")
                viewModel.clearScanResult()
            }
            null -> {}
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("LABA") },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { showFabMenu = true },
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add audiobook")
                }
                DropdownMenu(
                    expanded = showFabMenu,
                    onDismissRequest = { showFabMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Add Folder") },
                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                        onClick = {
                            showFabMenu = false
                            folderPicker.launch(null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add File") },
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
                        BookCard(
                            bookWithProgress = bookWithProgress,
                            isActive = isActive,
                            isPlaying = isActive && playbackStatus.isPlaying,
                            onClick = { onBookClick(bookWithProgress.book.id) },
                            onLongClick = { bookToDelete = bookWithProgress },
                            onCoverClick = if (isActive) viewModel::togglePlayPause else null,
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

    bookToDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("Delete Book") },
            text = { Text("Remove \"${book.book.title}\" from your library?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBook(book)
                    bookToDelete = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text("Cancel")
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
                        label = { Text("Completed") },
                    )
                } else {
                    Text(
                        text = "${(fraction * 100).toInt()}% completed",
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
                contentDescription = "Cover for $title",
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
                    contentDescription = if (isPlaying) "Playing" else "Paused",
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
            imageVector = Icons.Default.LibraryBooks,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Your library is empty",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tap + to add an audiobook folder or file",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
