package com.lonx.lyrico.data.song.file

import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepositoryImpl
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.repository.SettingsRepositoryImpl
import com.lonx.lyrico.data.repository.createSettingsDataStore
import com.lonx.lyrico.data.song.mapper.SortKeyUpdater
import com.lonx.lyrico.data.song.search.LyricFtsIndexer
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepositoryImpl
import com.lonx.lyrico.data.song.tag.AudioTagMutationResolver
import com.lonx.lyrico.data.song.tag.DefaultImageBytesFetcher
import com.lonx.lyrico.data.song.tag.ImageMimeTypeDetector
import com.lonx.lyrico.data.song.tag.PictureMutationResolver
import com.lonx.lyrico.data.song.tag.TagMapBuilder
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import com.lonx.lyrico.data.support.TestLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Delete and rename, against a real filesystem and a real `lyrico.db`.
 *
 * What makes these two operations dangerous is not the file move but the rows that are *keyed by the
 * song's path*: `songs.uri` (plus its mirrors `filePath`/`fileName`), the lyric FTS rows and
 * `song_custom_tag_keys`. The Android split the file move (repository) from the row update (use
 * case), and the rename path updated only the song row — so a renamed song kept its lyrics indexed
 * under a path that no longer existed. These tests pin the whole set at once: after a rename that
 * moves the file, the FTS rows and the custom tag keys have to be under the new uri and **absent**
 * under the old one, and the untouched songs have to be exactly as they were.
 *
 * Nothing is faked. The files are copies of TagLib's real test audio, so a renamed file can be
 * re-opened and read back, which is what proves the rename did not merely satisfy the database.
 */
class SongFileRepositoryTest {

    private lateinit var library: TestLibrary
    private lateinit var scope: CoroutineScope
    private lateinit var musicDir: Path
    private lateinit var settings: SettingsRepository
    private lateinit var indexes: LibraryIndexRepository
    private lateinit var tags: AudioTagRepositoryImpl
    private lateinit var files: SongFileRepositoryImpl
    private val log = RecordingAppLogRepository()

    @BeforeTest
    fun setUp() {
        library = TestLibrary()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        musicDir = Files.createTempDirectory("lyrico-song-file")
        settings = SettingsRepositoryImpl(
            createSettingsDataStore(Files.createTempFile("lyrico-song-file-settings", ".preferences_pb"), scope)
        )
        indexes = LibraryIndexRepositoryImpl(
            database = library.database,
            songDao = library.database.songDao(),
            indexDao = library.database.libraryIndexDao(),
            settingsRepository = settings,
        )
        tags = AudioTagRepositoryImpl(
            fileAccess = AudioFileAccess(),
            mutationResolver = AudioTagMutationResolver(
                tagMapBuilder = TagMapBuilder(),
                pictureResolver = PictureMutationResolver(
                    imageBytesFetcher = DefaultImageBytesFetcher(AudioFileAccess(), OkHttpClient()),
                    mimeTypeDetector = ImageMimeTypeDetector(),
                ),
            ),
            appLogRepository = log,
        )
        files = SongFileRepositoryImpl(
            database = library.database,
            fileAccess = AudioFileAccess(),
            libraryIndexRepository = indexes,
            sortKeyUpdater = SortKeyUpdater(),
            appLogRepository = log,
        )
    }

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        scope.cancel()
        scope.coroutineContext.job.join()
        library.close()
        musicDir.toFile().deleteRecursively()
    }

    // --- rename -------------------------------------------------------------------------------

    @Test
    fun `rename moves the file and every row keyed by the old path`() = runBlocking<Unit> {
        val song = addSong("bladeenc.mp3", lyrics = "[00:01.00]Alpha line\n[00:02.00]Beta line")

        val result = files.renameSong(song, "Renamed Song.mp3")

        val success = assertIs<RenameSongFileResult.Success>(result)
        val renamed = success.song
        assertEquals(song.uri, success.oldUri)

        // On disk: the file is at the new path, gone from the old one, and still readable as audio.
        val newPath = musicDir.resolve("Renamed Song.mp3")
        assertTrue(Files.isRegularFile(newPath), "the renamed file must exist")
        assertFalse(Files.exists(Path.of(song.uri)), "the old path must be gone")
        assertEquals("Renamed Song.mp3", tags.read(newPath.toString(), AudioTagReadOptions()).fileName)

        // In the database: the row is found by the new uri and not by the old one.
        assertEquals(newPath.toRealPath().toString(), renamed.uri)
        assertEquals(renamed.uri, renamed.filePath)
        assertEquals("Renamed Song.mp3", renamed.fileName)
        assertEquals("MP3", renamed.fileExtension)
        assertTrue(renamed.fileLastModified > 0L, "the new mtime must be read back from the file")
        assertNotNull(library.database.songDao().getSongByUri(renamed.uri))
        assertNull(library.database.songDao().getSongByUri(song.uri), "the old uri must not resolve")
        assertEquals(song.titleSortKey, renamed.titleSortKey, "the title did not change, so its sort key does not either")

        // The lyric index moved with the song: lines under the new uri, nothing left under the old.
        assertEquals(listOf("Alpha line", "Beta line"), library.indexedLyricLines(renamed.uri))
        assertTrue(
            library.indexedLyricLines(song.uri).isEmpty(),
            "FTS rows keyed by the old uri would be unreachable from the library",
        )
        // So did the custom tag keys.
        assertEquals(listOf("MOOD"), library.database.songCustomTagKeyDao().getKeysForSong(renamed.uri))
        assertEquals(
            listOf(renamed.uri),
            library.database.songCustomTagKeyDao().getSongUrisByKey("MOOD"),
        )
    }

    @Test
    fun `renaming one song leaves the other songs index alone`() = runBlocking<Unit> {
        val first = addSong("bladeenc.mp3", lyrics = "[00:01.00]First song line")
        val second = addSong("silence-44-s.flac", lyrics = "[00:01.00]Second song line")

        val renamed = assertIs<RenameSongFileResult.Success>(
            files.renameSong(first, "First Renamed.mp3")
        ).song

        assertEquals(listOf("First song line"), library.indexedLyricLines(renamed.uri))
        assertEquals(
            listOf("Second song line"),
            library.indexedLyricLines(second.uri),
            "a rename must not disturb another song's index",
        )
        assertEquals(listOf("MOOD"), library.database.songCustomTagKeyDao().getKeysForSong(second.uri))
    }

    @Test
    fun `rename to a name that is taken reports a conflict and changes nothing`() = runBlocking<Unit> {
        val song = addSong("bladeenc.mp3", lyrics = "[00:01.00]Alpha line")
        addSong("silence-44-s.flac")

        val result = files.renameSong(song, "silence-44-s.flac")

        assertEquals("silence-44-s.flac", assertIs<RenameSongFileResult.NameConflict>(result).targetName)
        assertTrue(Files.isRegularFile(Path.of(song.uri)), "the song must not have moved")
        assertEquals(song.uri, library.database.songDao().getSongByUri(song.uri)!!.uri)
        assertEquals(listOf("Alpha line"), library.indexedLyricLines(song.uri))
    }

    @Test
    fun `rename of a file that is not there fails and keeps the row`() = runBlocking<Unit> {
        val song = addSong("bladeenc.mp3")
        Files.delete(Path.of(song.uri))

        val result = files.renameSong(song, "Renamed.mp3")

        assertIs<RenameSongFileResult.Failed>(result)
        assertNotNull(library.database.songDao().getSongByUri(song.uri), "the row stays for a failed rename")
        assertFalse(Files.exists(musicDir.resolve("Renamed.mp3")))
    }

    @Test
    fun `rename refuses a name that is a path instead of a file name`() = runBlocking<Unit> {
        val song = addSong("bladeenc.mp3")
        val outside = Files.createDirectories(musicDir.parent.resolve("lyrico-elsewhere"))
        val escapedNames = listOf(
            "..",
            ".",
            "",
            "   ",
            """..\escaped.mp3""",
            "nested/song.mp3",
            """C:\Windows\System32\evil.mp3""",
            "song:name.mp3",
        )

        try {
            escapedNames.forEach { name ->
                val result = files.renameSong(song, name)
                assertIs<RenameSongFileResult.Failed>(
                    result,
                    "the name ${name.ifBlank { "<blank>" }} must be refused",
                )
            }
            assertTrue(Files.isRegularFile(Path.of(song.uri)), "the song must still be where it was")
            assertNotNull(library.database.songDao().getSongByUri(song.uri))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a name without an extension keeps the current one`() = runBlocking<Unit> {
        val song = addSong("bladeenc.mp3")

        val renamed = assertIs<RenameSongFileResult.Success>(files.renameSong(song, "No Extension")).song

        assertEquals("No Extension.mp3", renamed.fileName, "a nameless extension makes the file unreachable")
        assertTrue(Files.isRegularFile(musicDir.resolve("No Extension.mp3")))
    }

    @Test
    fun `a rename that only changes the case really changes it on disk`() = runBlocking<Unit> {
        val song = addSong("bladeenc.mp3")

        val renamed = assertIs<RenameSongFileResult.Success>(files.renameSong(song, "BLADEENC.MP3")).song

        // NTFS is case-insensitive but case-preserving: asking for a name that differs only in case is
        // a legal rename, not a conflict with itself, and the stored spelling has to follow.
        assertEquals("BLADEENC.MP3", renamed.fileName, "the database must hold the spelling on disk")
        assertEquals(
            listOf("BLADEENC.MP3"),
            Files.newDirectoryStream(musicDir).use { stream -> stream.map { it.fileName.toString() }.toList() },
        )
    }

    @Test
    fun `an untitled song takes its sort key from the new file name`() = runBlocking<Unit> {
        val song = addSong("bladeenc.mp3").copy(title = null)
        library.database.songDao().update(song)

        val renamed = assertIs<RenameSongFileResult.Success>(files.renameSong(song, "Renamed.mp3")).song

        // With no title the file name is what the library sorts by, so the sort key has to follow it.
        assertEquals("1_RENAMED.MP3", renamed.titleSortKey)
        assertEquals("R", renamed.titleGroupKey)
    }

    // --- delete -------------------------------------------------------------------------------

    @Test
    fun `delete removes the file and every row keyed by its path`() = runBlocking<Unit> {
        val song = addSong("bladeenc.mp3", lyrics = "[00:01.00]Alpha line")

        val result = files.deleteSong(song)

        assertEquals(DeleteSongFileResult.Deleted, result)
        assertFalse(Files.exists(Path.of(song.uri)), "the file must be gone")
        assertNull(library.database.songDao().getSongByUri(song.uri))
        assertTrue(library.indexedLyricLines(song.uri).isEmpty(), "the FTS rows must be gone")
        assertTrue(library.database.songCustomTagKeyDao().getKeysForSong(song.uri).isEmpty())
        assertEquals(
            0,
            library.database.folderDao().getAllFoldersOnce().first { it.id == song.folderId }.songCount,
        )
    }

    @Test
    fun `delete of a file that is already gone still removes the row`() = runBlocking<Unit> {
        val song = addSong("bladeenc.mp3")
        Files.delete(Path.of(song.uri))

        val result = files.deleteSong(song)

        assertEquals(DeleteSongFileResult.AlreadyMissing, result)
        assertNull(
            library.database.songDao().getSongByUri(song.uri),
            "a row whose file no longer exists can only produce broken playback",
        )
    }

    @Test
    fun `delete reports a failure the filesystem refuses and keeps the row`() = runBlocking<Unit> {
        // A non-empty directory cannot be deleted with a single Files.delete: a deterministic refusal
        // that does not depend on file attributes or on another process holding a lock.
        val directory = Files.createDirectories(musicDir.resolve("not-a-song"))
        Files.writeString(directory.resolve("kept.txt"), "kept")
        val song = addSongAt(directory, fileName = "not-a-song", lyrics = "[00:01.00]Alpha line")

        val result = files.deleteSong(song)

        assertIs<DeleteSongFileResult.Failed>(result)
        assertTrue(Files.isDirectory(directory), "the directory must survive a failed delete")
        assertNotNull(library.database.songDao().getSongByUri(song.uri), "the row must survive as well")
        assertEquals(listOf("Alpha line"), library.indexedLyricLines(song.uri))
        assertTrue(log.exceptions.isNotEmpty(), "a failed delete has to be reported to the user")
    }

    @Test
    fun `a batch delete carries on past a song it cannot delete`() = runBlocking<Unit> {
        val deletable = addSong("bladeenc.mp3", lyrics = "[00:01.00]Alpha line")
        val missing = addSong("silence-44-s.flac")
        Files.delete(Path.of(missing.uri))
        val directory = Files.createDirectories(musicDir.resolve("locked"))
        Files.writeString(directory.resolve("kept.txt"), "kept")
        val failing = addSongAt(directory, fileName = "locked", lyrics = "[00:01.00]Gamma line")

        val result = files.deleteSongs(listOf(deletable, missing, failing))

        assertEquals(listOf(deletable.uri, missing.uri, failing.uri), result.items.map { it.song.uri })
        assertEquals(DeleteSongFileResult.Deleted, result.items[0].result)
        assertEquals(DeleteSongFileResult.AlreadyMissing, result.items[1].result)
        assertIs<DeleteSongFileResult.Failed>(result.items[2].result)
        assertEquals(2, result.deleted)
        assertEquals(1, result.failed)
        assertNull(library.database.songDao().getSongByUri(deletable.uri))
        assertNull(library.database.songDao().getSongByUri(missing.uri))
        assertNotNull(library.database.songDao().getSongByUri(failing.uri), "the failure must keep its row")
        assertEquals(listOf("Gamma line"), library.indexedLyricLines(failing.uri))
    }

    @Test
    fun `deleting nothing does nothing`() = runBlocking<Unit> {
        val song = addSong("bladeenc.mp3")

        val result = files.deleteSongs(emptyList())

        assertTrue(result.items.isEmpty())
        assertEquals(0, result.deleted)
        assertEquals(0, result.failed)
        assertTrue(Files.isRegularFile(Path.of(song.uri)))
        assertNotNull(library.database.songDao().getSongByUri(song.uri))
    }

    // --- helpers ------------------------------------------------------------------------------

    private suspend fun addSong(fixture: String, lyrics: String? = null): SongEntity {
        val target = musicDir.resolve(fixture)
        Files.copy(
            Path.of(System.getProperty("lyrico.audiotag.fixtures.dir"), fixture),
            target,
            StandardCopyOption.REPLACE_EXISTING,
        )
        return addSongAt(target, fileName = fixture, lyrics = lyrics)
    }

    /**
     * Files a row for an existing path, the way the scanner does — and reads it back, because the id
     * is assigned by the database and every later write (the rename's `@Update`) goes through it.
     */
    private suspend fun addSongAt(target: Path, fileName: String, lyrics: String? = null): SongEntity {
        val rootId = rootFolderId()
        // The scanner never inserts a bare entity: it computes the sort keys first, and a row without
        // them would make "the rename kept the sort key" true for the wrong reason.
        val song = SortKeyUpdater().update(
            SongEntity(
                folderId = rootId,
                mediaId = 0,
                source = SongSource.LOCAL,
                filePath = target.toRealPath().toString(),
                fileName = fileName,
                fileSize = Files.size(target),
                fileLastModified = Files.getLastModifiedTime(target).toMillis(),
                title = "Title of $fileName",
                artist = "Artist",
                lyrics = lyrics,
                uri = target.toRealPath().toString(),
            )
        )
        library.database.songDao().insert(song)
        val stored = library.database.songDao().getSongByUri(song.uri)!!
        // Seed the two uri-keyed tables through their real write paths, so the assertions about them
        // moving are not vacuous.
        LyricFtsIndexer.replaceSong(library.database.songDao(), stored)
        library.database.songCustomTagKeyDao().replaceForSong(stored.uri, listOf("MOOD"))
        return stored
    }

    /** The library root row, created once: `folders.path` is unique, so a second insert would fail. */
    private suspend fun rootFolderId(): Long {
        cachedRootId?.let { return it }
        val id = library.database.folderDao().insert(
            FolderEntity(path = musicDir.toRealPath().toString(), addedBySaf = true)
        )
        cachedRootId = id
        return id
    }

    private var cachedRootId: Long? = null
}
