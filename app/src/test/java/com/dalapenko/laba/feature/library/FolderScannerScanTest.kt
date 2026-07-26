package com.dalapenko.laba.feature.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the recursive folder-scanning logic in [FolderScanner] (sorting, audio-file
 * filtering, cover-image preference, and multi-book tree traversal), as opposed to
 * [FolderScannerEntityConversionTest] which only covers the entity-mapping helpers.
 *
 * DocumentFile is mocked directly since it's an abstract, non-final androidx class;
 * MediaMetadataRetriever is left real and unmocked — under `isReturnDefaultValues = true`
 * (see app/build.gradle.kts testOptions) its native calls no-op and return null/0,
 * which is deterministic and sufficient since these tests only assert on file
 * discovery/ordering/cover-selection, not on decoded audio metadata.
 */
class FolderScannerScanTest {

    private val mockContext = mockk<Context>(relaxed = true)
    private lateinit var scanner: FolderScanner

    @Before
    fun setup() {
        scanner = FolderScanner(mockContext)
        mockkStatic(DocumentFile::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(DocumentFile::class)
    }

    private fun fakeUri(value: String): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.toString() } returns value
        return uri
    }

    private fun fakeDocumentFile(
        name: String?,
        isFile: Boolean,
        isDirectory: Boolean,
        type: String? = null,
        children: List<DocumentFile> = emptyList(),
    ): DocumentFile {
        val doc = mockk<DocumentFile>()
        every { doc.name } returns name
        every { doc.isFile } returns isFile
        every { doc.isDirectory } returns isDirectory
        every { doc.type } returns type
        every { doc.uri } returns fakeUri("content://test/${name ?: "unnamed"}")
        every { doc.listFiles() } returns children.toTypedArray()
        return doc
    }

    // ── scanFolder ────────────────────────────────────────────────────────────

    @Test
    fun givenUnresolvableTreeUri_whenScanFolder_thenReturnsNull() = runTest {
        val treeUri = fakeUri("content://tree/missing")
        every { DocumentFile.fromTreeUri(mockContext, treeUri) } returns null

        val result = scanner.scanFolder(treeUri)

        assertNull(result)
    }

    @Test
    fun givenFolderWithNoAudioFiles_whenScanFolder_thenReturnsNull() = runTest {
        val treeUri = fakeUri("content://tree/empty")
        val root = fakeDocumentFile(
            name = "EmptyBook",
            isFile = false,
            isDirectory = true,
            children = listOf(
                fakeDocumentFile("readme.txt", isFile = true, isDirectory = false, type = "text/plain"),
            ),
        )
        every { DocumentFile.fromTreeUri(mockContext, treeUri) } returns root

        val result = scanner.scanFolder(treeUri)

        assertNull(result)
    }

    @Test
    fun givenFolderWithAudioFiles_whenScanFolder_thenTracksAreNaturallySortedAndIndexed() = runTest {
        val treeUri = fakeUri("content://tree/book1")
        val track2 = fakeDocumentFile("track2.mp3", isFile = true, isDirectory = false, type = "audio/mpeg")
        val track10 = fakeDocumentFile("track10.mp3", isFile = true, isDirectory = false, type = "audio/mpeg")
        val track1 = fakeDocumentFile("track1.mp3", isFile = true, isDirectory = false, type = "audio/mpeg")
        val root = fakeDocumentFile(
            name = "My Book",
            isFile = false,
            isDirectory = true,
            children = listOf(track2, track10, track1),
        )
        every { DocumentFile.fromTreeUri(mockContext, treeUri) } returns root

        val result = scanner.scanFolder(treeUri)

        assertEquals(
            listOf("track1.mp3", "track2.mp3", "track10.mp3"),
            result?.tracks?.map { it.fileName },
        )
        assertEquals(listOf(0, 1, 2), result?.tracks?.map { it.sequenceOrder })
    }

    @Test
    fun givenFolderWithNoMetadataTitle_whenScanFolder_thenTitleFallsBackToFolderName() = runTest {
        val treeUri = fakeUri("content://tree/book1")
        val track = fakeDocumentFile("chapter1.mp3", isFile = true, isDirectory = false, type = "audio/mpeg")
        val root = fakeDocumentFile(name = "My Book", isFile = false, isDirectory = true, children = listOf(track))
        every { DocumentFile.fromTreeUri(mockContext, treeUri) } returns root

        val result = scanner.scanFolder(treeUri)

        assertEquals("My Book", result?.title)
    }

    @Test
    fun givenFolderWithNonAudioSiblingFiles_whenScanFolder_thenOnlyAudioFilesBecomeTracks() = runTest {
        val treeUri = fakeUri("content://tree/book2")
        val track = fakeDocumentFile("chapter1.mp3", isFile = true, isDirectory = false, type = "audio/mpeg")
        val notes = fakeDocumentFile("notes.txt", isFile = true, isDirectory = false, type = "text/plain")
        val root = fakeDocumentFile(
            name = "Book Two",
            isFile = false,
            isDirectory = true,
            children = listOf(track, notes),
        )
        every { DocumentFile.fromTreeUri(mockContext, treeUri) } returns root

        val result = scanner.scanFolder(treeUri)

        assertEquals(1, result?.tracks?.size)
        assertEquals("chapter1.mp3", result?.tracks?.first()?.fileName)
    }

    @Test
    fun givenFolderWithCoverImage_whenScanFolder_thenCoverUriResolvedFromImageFile() = runTest {
        val treeUri = fakeUri("content://tree/book3")
        val track = fakeDocumentFile("chapter1.mp3", isFile = true, isDirectory = false, type = "audio/mpeg")
        val cover = fakeDocumentFile("cover.jpg", isFile = true, isDirectory = false, type = "image/jpeg")
        val root = fakeDocumentFile(
            name = "Book Three",
            isFile = false,
            isDirectory = true,
            children = listOf(track, cover),
        )
        every { DocumentFile.fromTreeUri(mockContext, treeUri) } returns root

        val result = scanner.scanFolder(treeUri)

        assertEquals(cover.uri.toString(), result?.coverUri)
    }

    @Test
    fun givenFolderWithMultipleImages_whenScanFolder_thenPreferredCoverNameIsChosen() = runTest {
        val treeUri = fakeUri("content://tree/book4")
        val track = fakeDocumentFile("chapter1.mp3", isFile = true, isDirectory = false, type = "audio/mpeg")
        val randomImage = fakeDocumentFile("random.jpg", isFile = true, isDirectory = false, type = "image/jpeg")
        val coverImage = fakeDocumentFile("cover.jpg", isFile = true, isDirectory = false, type = "image/jpeg")
        val root = fakeDocumentFile(
            name = "Book Four",
            isFile = false,
            isDirectory = true,
            children = listOf(track, randomImage, coverImage),
        )
        every { DocumentFile.fromTreeUri(mockContext, treeUri) } returns root

        val result = scanner.scanFolder(treeUri)

        assertEquals(coverImage.uri.toString(), result?.coverUri)
    }

    // ── scanFolderTree ────────────────────────────────────────────────────────

    @Test
    fun givenFolderTreeWithNoAudioAnywhere_whenScanFolderTree_thenReturnsEmptyList() = runTest {
        val treeUri = fakeUri("content://tree/emptylib")
        val root = fakeDocumentFile(name = "Library", isFile = false, isDirectory = true, children = emptyList())
        every { DocumentFile.fromTreeUri(mockContext, treeUri) } returns root

        val results = scanner.scanFolderTree(treeUri)

        assertTrue(results.isEmpty())
    }

    @Test
    fun givenNestedFolders_whenScanFolderTree_thenCollectsOneBookPerFolderWithDirectAudio() = runTest {
        val treeUri = fakeUri("content://tree/library")
        val chapterA = fakeDocumentFile("chapterA.mp3", isFile = true, isDirectory = false, type = "audio/mpeg")
        val subBook = fakeDocumentFile(
            name = "Sub Book",
            isFile = false,
            isDirectory = true,
            children = listOf(chapterA),
        )
        val chapterB = fakeDocumentFile("chapterB.mp3", isFile = true, isDirectory = false, type = "audio/mpeg")
        val root = fakeDocumentFile(
            name = "Library",
            isFile = false,
            isDirectory = true,
            children = listOf(subBook, chapterB),
        )
        every { DocumentFile.fromTreeUri(mockContext, treeUri) } returns root

        val results = scanner.scanFolderTree(treeUri)

        assertEquals(2, results.size)
        assertEquals(setOf("Sub Book", "Library"), results.map { it.title }.toSet())
    }

    @Test
    fun givenUnresolvableTreeUri_whenScanFolderTree_thenReturnsEmptyList() = runTest {
        val treeUri = fakeUri("content://tree/missing")
        every { DocumentFile.fromTreeUri(mockContext, treeUri) } returns null

        val results = scanner.scanFolderTree(treeUri)

        assertTrue(results.isEmpty())
    }
}
