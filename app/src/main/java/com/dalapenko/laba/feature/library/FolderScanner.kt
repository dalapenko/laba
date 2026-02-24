package com.dalapenko.laba.feature.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.dalapenko.laba.core.database.entity.BookEntity
import com.dalapenko.laba.core.database.entity.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.net.toUri
import android.util.Log

data class ScannedBook(
    val title: String,
    val author: String? = null,
    val rootUri: String,
    val tracks: List<ScannedTrack>,
    val coverUri: String? = null,
)

data class ScannedTrack(
    val fileUri: String,
    val fileName: String,
    val durationMs: Long,
    val sequenceOrder: Int,
)

/** Holds all metadata extracted from a single audio file in one retriever pass. */
private data class AudioMeta(
    val title: String?,
    val album: String?,
    val author: String?,
    val durationMs: Long,
    val embeddedPictureBytes: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioMeta) return false
        return title == other.title &&
            album == other.album &&
            author == other.author &&
            durationMs == other.durationMs &&
            embeddedPictureBytes.contentEquals(other.embeddedPictureBytes)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + embeddedPictureBytes.contentHashCode()
        return result
    }
}

class FolderScanner(private val context: Context) {

    companion object {
        private const val TAG = "FolderScanner"
    }

    // ── Public scan API ───────────────────────────────────────────────────────

    suspend fun scanFolder(treeUri: Uri): ScannedBook? = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext null
        val audioFiles = collectDirectAudioFiles(root)
        if (audioFiles.isEmpty()) return@withContext null

        val sortedFiles = audioFiles.sortedWith(naturalOrderComparator())
        // Read all metadata from the first file in one retriever pass
        val firstMeta = extractAudioMeta(sortedFiles.first().uri)

        val tracks = sortedFiles.mapIndexed { index, file ->
            ScannedTrack(
                fileUri = file.uri.toString(),
                fileName = file.name ?: "Track ${index + 1}",
                durationMs = if (index == 0) firstMeta.durationMs else extractDuration(file.uri),
                sequenceOrder = index,
            )
        }

        // Prefer an image file in the folder; fall back to embedded art from first track
        val coverUri = findFolderCoverUri(root)
            ?: firstMeta.embeddedPictureBytes?.let { saveEmbeddedArt(it, treeUri.toString()) }

        ScannedBook(
            title = firstMeta.album ?: root.name ?: "Unknown Book",
            author = firstMeta.author,
            rootUri = treeUri.toString(),
            tracks = tracks,
            coverUri = coverUri,
        )
    }

    suspend fun scanSingleFile(uri: Uri): ScannedBook? = withContext(Dispatchers.IO) {
        val file = DocumentFile.fromSingleUri(context, uri) ?: return@withContext null
        if (file.type?.startsWith("audio/") != true) return@withContext null

        val meta = extractAudioMeta(uri)
        val fileName = file.name ?: "Unknown Track"
        val coverUri = meta.embeddedPictureBytes?.let { saveEmbeddedArt(it, uri.toString()) }

        ScannedBook(
            title = meta.title ?: fileName.substringBeforeLast('.'),
            author = meta.author,
            rootUri = uri.toString(),
            tracks = listOf(
                ScannedTrack(
                    fileUri = uri.toString(),
                    fileName = fileName,
                    durationMs = meta.durationMs,
                    sequenceOrder = 0,
                )
            ),
            coverUri = coverUri,
        )
    }

    suspend fun scanFolderTree(treeUri: Uri): List<ScannedBook> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val results = mutableListOf<ScannedBook>()
        collectBooksRecursive(root, treeUri, results, 0, mutableSetOf())
        results
    }

    private fun collectBooksRecursive(
        dir: DocumentFile,
        treeUri: Uri,
        out: MutableList<ScannedBook>,
        depth: Int,
        visited: MutableSet<String>,
    ) {
        if (depth > MAX_DEPTH) return
        val uriKey = dir.uri.toString()
        if (!visited.add(uriKey)) return

        val children = dir.listFiles()
        val directAudio = children.filter { it.isFile && it.type?.startsWith("audio/") == true }
        if (directAudio.isNotEmpty()) {
            val sorted = directAudio.sortedWith(naturalOrderComparator())
            val firstMeta = extractAudioMeta(sorted.first().uri)

            val tracks = sorted.mapIndexed { index, file ->
                ScannedTrack(
                    fileUri = file.uri.toString(),
                    fileName = file.name ?: "Track ${index + 1}",
                    durationMs = if (index == 0) firstMeta.durationMs else extractDuration(file.uri),
                    sequenceOrder = index,
                )
            }

            val coverUri = findFolderCoverUri(dir)
                ?: firstMeta.embeddedPictureBytes?.let { saveEmbeddedArt(it, dir.uri.toString()) }

            out.add(
                ScannedBook(
                    title = firstMeta.album ?: dir.name ?: "Unknown Book",
                    author = firstMeta.author,
                    rootUri = dir.uri.toString(),
                    tracks = tracks,
                    coverUri = coverUri,
                )
            )
        }

        for (child in children) {
            if (child.isDirectory) {
                collectBooksRecursive(child, treeUri, out, depth + 1, visited)
            }
        }
    }

    /**
     * Re-scans an existing book to refresh title, author, cover, and total duration.
     * Track list is NOT modified so in-progress positions remain valid.
     * Returns null if the URI permission was revoked or the book folder is gone.
     */
    suspend fun rescanBookMeta(book: BookEntity): BookEntity? = withContext(Dispatchers.IO) {
        try {
            val uri = book.rootFolderUri.toUri()
            val scanResult = if (isTreeUri(uri)) scanFolder(uri) else scanSingleFile(uri)
            val scanned = scanResult ?: return@withContext null

            // Delete the old locally-cached cover only after the new scan succeeds
            if (book.coverUri != scanned.coverUri) {
                deleteCoverFile(book.coverUri)
            }

            book.copy(
                title = scanned.title,
                author = scanned.author,
                coverUri = scanned.coverUri,
                totalDurationMs = scanned.tracks.sumOf { it.durationMs },
            )
        } catch (_: SecurityException) {
            null // permission revoked — leave the book untouched
        }
    }

    /**
     * Lightweight check: returns true if the root folder/file URI is still accessible.
     * Does NOT open MediaMetadataRetriever — only tests DocumentFile existence.
     * 
     * Uses multiple strategies to bypass DocumentFile caching issues that can
     * occur when files are deleted and then restored from trash.
     */
    suspend fun isBookAvailable(rootFolderUri: String): Boolean = withContext(Dispatchers.IO) {
        val uri = rootFolderUri.toUri()
        val isTree = isTreeUri(uri)
        
        // For single files: try to open input stream (most reliable)
        if (!isTree) {
            try {
                context.contentResolver.openInputStream(uri)?.use { 
                    return@withContext true
                }
            } catch (_: Exception) {
                return@withContext false
            }
        }
        
        // For tree URIs (folders): verify folder exists and has audio files
        try {
            val doc = DocumentFile.fromTreeUri(context, uri) ?: return@withContext false
            
            // First check if folder itself exists
            if (!doc.exists()) return@withContext false
            
            // Force DocumentFile to re-query by checking listFiles()
            // This is more reliable than exists() for tree URIs
            val files = doc.listFiles()
            
            // Check if we have any audio files (not just any files)
            val hasAudioFiles = files.any { file ->
                file.isFile && file.type?.startsWith("audio/") == true
            }
            
            return@withContext hasAudioFiles
        } catch (_: SecurityException) {
            return@withContext false
        } catch (_: Exception) {
            // Last resort: try ContentResolver query fallback
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    return@withContext cursor.count > 0
                }
                return@withContext false
            } catch (_: Exception) {
                return@withContext false
            }
        }
    }

    /**
     * Deletes a cover that was written to internal storage by this scanner.
     * SAF content:// URIs (folder images owned by the user) are never touched.
     */
    fun deleteCoverFile(coverUri: String?) {
        if (coverUri == null) return
        val parsed = coverUri.toUri()
        if (parsed.scheme == "file") parsed.path?.let { File(it).delete() }
    }

    suspend fun deleteBookFiles(rootFolderUri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = rootFolderUri.toUri()
            val doc = if (isTreeUri(uri)) {
                DocumentFile.fromTreeUri(context, uri)
            } else {
                DocumentFile.fromSingleUri(context, uri)
            }
            doc?.delete() == true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    // ── Entity conversion ─────────────────────────────────────────────────────

    fun toBookEntity(scanned: ScannedBook): BookEntity = BookEntity(
        title = scanned.title,
        author = scanned.author,
        rootFolderUri = scanned.rootUri,
        totalDurationMs = scanned.tracks.sumOf { it.durationMs },
        coverUri = scanned.coverUri,
    )

    fun toTrackEntities(scanned: ScannedBook, bookId: Long = 0): List<TrackEntity> =
        scanned.tracks.map { track ->
            TrackEntity(
                bookId = bookId,
                fileUri = track.fileUri,
                fileName = track.fileName,
                durationMs = track.durationMs,
                sequenceOrder = track.sequenceOrder,
            )
        }

    // ── Metadata extraction ───────────────────────────────────────────────────

    private fun extractAudioMeta(uri: Uri): AudioMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            AudioMeta(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                author = author,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                embeddedPictureBytes = retriever.embeddedPicture,
            )
        } catch (_: Exception) {
            AudioMeta(null, null, null, 0L, null)
        } finally {
            retriever.release()
        }
    }

    private fun extractDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    // ── Cover helpers ─────────────────────────────────────────────────────────

    private fun findFolderCoverUri(root: DocumentFile): String? {
        val preferred = setOf("cover", "folder", "artwork", "front", "album", "book")
        val imageTypes = setOf("image/jpeg", "image/png", "image/webp")
        val images = root.listFiles().filter { it.isFile && it.type in imageTypes }
        if (images.isEmpty()) return null
        val nameMatch = images.firstOrNull { file ->
            (file.name ?: "").substringBeforeLast('.').lowercase() in preferred
        }
        return (nameMatch ?: images.first()).uri.toString()
    }

    /**
     * Saves [bytes] (JPEG/PNG embedded art) to `filesDir/covers/<key-hash>.jpg`
     * and returns a `file://` URI string.
     */
    private fun saveEmbeddedArt(bytes: ByteArray, key: String): String? {
        return try {
            val coversDir = File(context.filesDir, "covers").also { it.mkdirs() }
            val name = "${key.hashCode().toLong().and(0xFFFFFFFFL)}.jpg"
            File(coversDir, name).also { it.writeBytes(bytes) }
                .let { Uri.fromFile(it).toString() }
        } catch (_: Exception) {
            null
        }
    }

    // ── File traversal ────────────────────────────────────────────────────────

    /**
     * Collects audio files ONLY from the current directory, not from subdirectories.
     * Used to calculate correct duration when the folder contains nested audiobook folders.
     */
    private fun collectDirectAudioFiles(dir: DocumentFile): List<DocumentFile> {
        val children = dir.listFiles()
        val directFiles = children.filter { it.isFile && it.type?.startsWith("audio/") == true }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val nestedCount = children.count { it.isDirectory }
            if (nestedCount > 0) {
                Log.d(TAG, "Folder '${dir.name}': ${directFiles.size} audio files, $nestedCount subdirs")
            }
        }

        return directFiles
    }

    private fun isTreeUri(uri: Uri): Boolean = uri.pathSegments.contains("tree")

    private fun naturalOrderComparator(): Comparator<DocumentFile> =
        Comparator { a, b -> naturalCompare(a.name ?: "", b.name ?: "") }
}

internal fun naturalCompare(a: String, b: String): Int {
    val aParts = splitIntoChunks(a)
    val bParts = splitIntoChunks(b)
    for (i in 0 until minOf(aParts.size, bParts.size)) {
        val ap = aParts[i]
        val bp = bParts[i]
        val cmp = if (ap.toLongOrNull() != null && bp.toLongOrNull() != null) {
            ap.toLong().compareTo(bp.toLong())
        } else {
            ap.compareTo(bp, ignoreCase = true)
        }
        if (cmp != 0) return cmp
    }
    return aParts.size - bParts.size
}

private fun splitIntoChunks(s: String): List<String> {
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var wasDigit = false
    for (c in s) {
        val isDigit = c.isDigit()
        if (current.isNotEmpty() && isDigit != wasDigit) {
            chunks.add(current.toString())
            current.clear()
        }
        current.append(c)
        wasDigit = isDigit
    }
    if (current.isNotEmpty()) chunks.add(current.toString())
    return chunks
}

private const val MAX_DEPTH = 10