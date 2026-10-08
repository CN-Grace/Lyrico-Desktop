package com.lonx.lyrico.data.song.scan

import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepositoryImpl
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.repository.SettingsRepositoryImpl
import com.lonx.lyrico.data.repository.createSettingsDataStore
import com.lonx.lyrico.data.song.file.AudioFileAccess
import com.lonx.lyrico.data.song.library.SongLibraryRepositoryImpl
import com.lonx.lyrico.data.song.mapper.SongMetadataMapper
import com.lonx.lyrico.data.song.mapper.SortKeyUpdater
import com.lonx.lyrico.data.song.search.SongSearchRepositoryImpl
import com.lonx.lyrico.data.song.tag.AudioTagFieldKey
import com.lonx.lyrico.data.song.tag.AudioTagMutation
import com.lonx.lyrico.data.song.tag.AudioTagMutationMode
import com.lonx.lyrico.data.song.tag.AudioTagMutationResolver
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepositoryImpl
import com.lonx.lyrico.data.song.tag.AudioTagWriteResult
import com.lonx.lyrico.data.song.tag.DefaultImageBytesFetcher
import com.lonx.lyrico.data.song.tag.FieldMutation
import com.lonx.lyrico.data.song.tag.ImageMimeTypeDetector
import com.lonx.lyrico.data.song.tag.PictureMutationResolver
import com.lonx.lyrico.data.song.tag.TagMapBuilder
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.viewmodel.SortBy
import com.lonx.lyrico.viewmodel.SortOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The P3 acceptance test: **scan a real music directory, insert songs, list them, and edit their
 * tags on disk.**
 *
 * Nothing here is faked. The directory tree holds TagLib's own test audio, the scan walks the real
 * filesystem, the tags are read and written through the real JNI/TagLib layer, the rows go into a
 * real `lyrico.db` file, and the list comes back out through the same `SongQueryBuilder` +
 * `@RawQuery` path the UI uses.
 *
 * The earlier tests each cover one repository in isolation; this one covers the seams between them,
 * which is where a port of this size actually breaks: that the path written into `folders.path` is
 * character-for-character the parent of the path in `songs.uri`, that a re-scan leaves unchanged
 * files alone, that a deleted file takes its FTS and custom-tag rows with it, and that a tag edit
 * meant for the file and the database ends up in both.
 */
class LibraryScanIntegrationTest {

    private lateinit var library: TestLibrary
    private lateinit var scope: CoroutineScope
    private lateinit var musicDir: Path
    private lateinit var settings: SettingsRepository
    private val log = RecordingAppLogRepository()

    private lateinit var tags: AudioTagRepositoryImpl
    private lateinit var songs: SongLibraryRepositoryImpl
    private lateinit var indexes: LibraryIndexRepository
    private lateinit var search: SongSearchRepositoryImpl
    private lateinit var scanner: LibraryScanRepositoryImpl
    private lateinit var mapper: SongMetadataMapper

    @BeforeTest
    fun setUp() {
        library = TestLibrary()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        musicDir = Files.createTempDirectory("lyrico-scan-library")
        settings = SettingsRepositoryImpl(
            createSettingsDataStore(Files.createTempFile("lyrico-scan-settings", ".preferences_pb"), scope)
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
        mapper = SongMetadataMapper(SortKeyUpdater())
        songs = SongLibraryRepositoryImpl(library.database)
        indexes = LibraryIndexRepositoryImpl(
            database = library.database,
            songDao = library.database.songDao(),
            indexDao = library.database.libraryIndexDao(),
            settingsRepository = settings,
        )
        search = SongSearchRepositoryImpl(library.database)
        scanner = LibraryScanRepositoryImpl(
            database = library.database,
            mediaScanner = MediaScanner(),
            settingsRepository = settings,
            audioTagRepository = tags,
            songMetadataMapper = mapper,
            libraryIndexRepository = indexes,
            appLogRepository = log,
        )
    }

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        // Cancelling and waiting releases the DataStore's claim on its file before the directory goes.
        scope.cancel()
        scope.coroutineContext.job.join()
        library.close()
        musicDir.toFile().deleteRecursively()
    }

    @Test
    fun `scans a real directory, inserts the songs and lists them`() = runBlocking<Unit> {
        placeFixture("bladeenc.mp3", "Album One")
        placeFixture("silence-44-s.flac", "Album One")
        placeFixture("test.ogg", "Album Two")
        placeFixture("alaw.wav", "Album Two")
        // Write tags onto one real file first: then "the scan read the tags" is proven by the scan's
        // own output, instead of relying on whatever the fixture happens to carry.
        tags.patch(
            musicDir.resolve("Album One/bladeenc.mp3").toString(),
            AudioTagMutation(
                AudioTagMutationMode.Patch,
                fields = mapOf(
                    AudioTagFieldKey.Title to FieldMutation.Set("Scanned Title"),
                    AudioTagFieldKey.Artist to FieldMutation.Set("Scanned Artist"),
                    AudioTagFieldKey.Album to FieldMutation.Set("Scanned Album"),
                ),
            ),
        )
        addRoot()

        val result = scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))

        assertEquals(4, result.scanned)
        assertEquals(4, result.inserted)
        assertEquals(0, result.updated)
        assertEquals(0, result.deleted)
        assertTrue(result.failures.isEmpty(), "no failures expected: ${result.failures}")

        // The list the UI renders, through the real query builder.
        val listed = songs.observeSongs(SortBy.TITLE, SortOrder.ASC, folderId = null).first()
        assertEquals(4, listed.size)
        assertTrue(listed.all { it.source == SongSource.LOCAL })

        val one = listed.single { it.fileName == "bladeenc.mp3" }
        val realFile = musicDir.resolve("Album One/bladeenc.mp3").toRealPath()
        assertEquals(realFile.toString(), one.uri, "songs.uri holds the canonical Windows path")
        assertEquals(realFile.toString(), one.filePath)
        assertEquals("MP3", one.fileExtension)
        assertTrue(one.durationMilliseconds > 0, "a real fixture must have a duration after a scan")
        assertTrue(one.fileSize > 0L)
        assertEquals("Scanned Title", one.title, "the scan must read the tags off the real file")
        assertEquals("Scanned Artist", one.artist)
        assertEquals("Scanned Album", one.album)

        // Every scanned song got a folder row, and the two spellings of a directory agree: this is
        // what lets a song be traced back to its folder.
        val folderPaths = library.database.folderDao().getAllFoldersOnce().map { it.path }.toSet()
        val parents = listed.map { Path.of(it.uri).parent.toString() }.toSet()
        assertEquals(parents, folderPaths - musicDir.toRealPath().toString(), "folder rows match song parents")
        assertTrue(musicDir.toRealPath().toString() in folderPaths, "the root folder itself is stored")
    }

    @Test
    fun `a second scan of an unchanged library only reads what changed`() = runBlocking<Unit> {
        val first = placeFixture("bladeenc.mp3", "Album One")
        placeFixture("test.ogg", "Album One")
        addRoot()

        scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))
        val second = scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))

        assertEquals(2, second.scanned)
        assertEquals(0, second.inserted)
        assertEquals(0, second.updated)
        assertTrue(second.deleted == 0)
        assertEquals(2, second.skipped, "unchanged files are skipped, not re-read")
        assertEquals(2, songs.getSongCount())

        // Touch one file: only that one is re-read.
        Files.setLastModifiedTime(first, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        val third = scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))

        assertEquals(1, third.updated)
        assertEquals(0, third.inserted)
        assertEquals(2, songs.getSongCount(), "an update must not duplicate the row")
    }

    @Test
    fun `a full rescan re-reads files even when nothing changed on disk`() = runBlocking<Unit> {
        placeFixture("bladeenc.mp3", "Album One")
        addRoot()
        scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))

        val full = scanner.synchronize(LibraryScanRequest(fullRescan = true, ignoreShortAudio = false))

        assertEquals(1, full.updated)
        assertEquals(0, full.inserted)
        assertEquals(1, songs.getSongCount())
    }

    @Test
    fun `deleting a file removes its row, its lyric index and its custom tag keys`() = runBlocking<Unit> {
        val kept = placeFixture("bladeenc.mp3", "Album One")
        val removed = placeFixture("test.ogg", "Album One")
        // Real lyrics, indexed by the scan, so the assertions below prove a cascade rather than
        // passing because the tables happened to be empty.
        tags.patch(
            removed.toString(),
            AudioTagMutation(
                AudioTagMutationMode.Patch,
                fields = mapOf(
                    AudioTagFieldKey.Lyrics to FieldMutation.Set(
                        "[00:01.00]doomed lyrics\n[00:02.00]second doomed line",
                    ),
                ),
            ),
        )
        settings.saveLyricIndexEnabled(true)
        addRoot()
        scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))

        val removedUri = removed.toRealPath().toString()
        val keptUri = kept.toRealPath().toString()
        assertTrue(library.indexedLyricLines(removedUri).isNotEmpty(), "precondition: the song was indexed")
        library.database.songCustomTagKeyDao().replaceForSong(removedUri, listOf("MOOD"))

        Files.delete(removed)
        val result = scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))

        assertEquals(1, result.deleted)
        assertEquals(1, songs.getSongCount())
        assertEquals(keptUri, songs.observeSongs(SortBy.TITLE, SortOrder.ASC, null).first().single().uri)
        assertTrue(library.indexedLyricLines(removedUri).isEmpty(), "the FTS rows must be deleted too")
        assertTrue(
            library.database.songCustomTagKeyDao().getSongUrisByKey("MOOD").isEmpty(),
            "custom tag keys must not outlive their song",
        )
    }

    @Test
    fun `skips short audio only when asked to`() = runBlocking<Unit> {
        placeFixture("alaw.wav", "Short")
        placeFixture("bladeenc.mp3", "Long")
        addRoot()

        val all = scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))
        assertEquals(2, all.inserted)
        val durations = songs.observeSongs(SortBy.TITLE, SortOrder.ASC, null).first()
            .associate { it.fileName to it.durationMilliseconds }

        val filtered = scanner.synchronize(
            LibraryScanRequest(fullRescan = true, ignoreShortAudio = true)
        )

        // Assert against what the files actually report rather than a hardcoded filename: which of
        // the fixtures is under a minute is a property of the fixture, not of this test.
        val expectedShort = durations.filterValues { it in 1..60_000 }.keys
        assertEquals(expectedShort.size, filtered.skipped, "duration was $durations")
    }

    @Test
    fun `writes lyrics into the search index when lyric indexing is on`() = runBlocking<Unit> {
        val file = placeFixture("bladeenc.mp3", "Album One")
        addRoot()
        // Put real lyrics on the real file, the way an edit would.
        val written = tags.patch(
            file.toString(),
            AudioTagMutation(
                mode = AudioTagMutationMode.Patch,
                fields = mapOf(
                    AudioTagFieldKey.Lyrics to FieldMutation.Set("[00:01.00]Hello from the scan test\n[00:02.00]second line"),
                    AudioTagFieldKey.Title to FieldMutation.Set("Scanned Song"),
                    AudioTagFieldKey.Artist to FieldMutation.Set("Scanned Artist"),
                ),
            ),
        )
        assertIs<AudioTagWriteResult.Success>(written, "the fixture must be writable")

        settings.saveLyricIndexEnabled(true)
        val result = scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))
        assertEquals(1, result.inserted)

        val uri = file.toRealPath().toString()
        assertEquals(
            listOf("Hello from the scan test", "second line"),
            library.indexedLyricLines(uri),
            "the scan must index the lyrics it just read, without the timestamps",
        )
        assertTrue(songs.getSongByUri(uri)!!.lyricSearchText!!.contains("Hello from the scan test"))

        val hits = search.searchLyricsForLocalSearch("second").first()
        assertEquals(1, hits.size)
        assertEquals("Scanned Song", hits.single().song.title)

        // The artist index is refreshed from the scanned tags too.
        assertEquals(
            listOf("Scanned Artist"),
            indexes.observeArtists().first().map { it.name },
        )
    }

    @Test
    fun `an edited tag lands in the file on disk and in the database`() = runBlocking<Unit> {
        val file = placeFixture("bladeenc.mp3", "Album One")
        addRoot()
        scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))
        val uri = file.toRealPath().toString()
        val before = songs.getSongByUri(uri)!!

        // What the (not yet ported) edit use case does: write the file, then re-read it and store the
        // snapshot, so the row can never drift from the file.
        val result = tags.patch(
            uri,
            AudioTagMutation(
                mode = AudioTagMutationMode.Patch,
                fields = mapOf(
                    AudioTagFieldKey.Title to FieldMutation.Set("Edited Title"),
                    AudioTagFieldKey.Album to FieldMutation.Set("Edited Album"),
                ),
            ),
        )
        assertIs<AudioTagWriteResult.Success>(result)
        val reread = tags.read(uri, AudioTagReadOptions())
        songs.updateSong(mapper.applyAudioTagData(before, reread, fileLastModified = Files.getLastModifiedTime(file).toMillis()))

        // On disk: a fresh read of the file sees the new tags.
        assertEquals("Edited Title", tags.read(uri, AudioTagReadOptions()).title)
        // In the database: the same values, and sort keys recomputed from them.
        val after = songs.getSongByUri(uri)!!
        assertEquals("Edited Title", after.title)
        assertEquals("Edited Album", after.album)
        assertEquals("1_EDITED TITLE", after.titleSortKey)
        assertEquals("E", after.titleGroupKey)

        // The write changed the file's size and mtime, but the snapshot the mapper stores carries the
        // tags and the mtime only — so the next scan notices the file changed, re-reads it, and the
        // scan after that is in sync again. What must never happen is the scan undoing the edit.
        val rescan = scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))
        assertEquals(1, rescan.updated)
        assertEquals("Edited Title", songs.getSongByUri(uri)!!.title, "a scan must not undo a tag edit")
        val settled = scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))
        assertEquals(0, settled.updated, "after re-reading the edited file, nothing is left to do")
    }

    @Test
    fun `keeps the songs of a library folder that is not there, unless asked to remove them`() = runBlocking<Unit> {
        placeFixture("bladeenc.mp3", "Album One")
        val rootId = addRoot()
        scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))
        assertEquals(1, songs.getSongCount())

        // The whole root disappears — an unplugged drive, a folder moved away.
        musicDir.toFile().deleteRecursively()

        val kept = scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))
        assertEquals(1, songs.getSongCount(), "an absent drive must not wipe the library")
        assertEquals(1, kept.failures.size, "but the user has to be told the folder is missing")
        assertEquals(LibraryScanFailureStage.Collecting, kept.failures.single().stage)

        val removed = scanner.synchronize(
            LibraryScanRequest(fullRescan = false, ignoreShortAudio = false, removeUnavailableFolders = true)
        )
        assertEquals(1, removed.deleted)
        assertEquals(0, songs.getSongCount())
        assertTrue(
            library.database.folderDao().getAllFoldersOnce().none { it.id == rootId },
            "the folder tree goes with its songs when removal is requested",
        )
    }

    @Test
    fun `rescans only the requested root`() = runBlocking<Unit> {
        placeFixture("bladeenc.mp3", "Kept")
        val keptDir = musicDir
        val otherDir = Files.createTempDirectory("lyrico-scan-other")
        try {
            Files.copy(
                Path.of(System.getProperty("lyrico.audiotag.fixtures.dir"), "test.ogg"),
                otherDir.resolve("other.ogg"),
            )
            val keptRoot = addRoot(path = keptDir)
            val otherRoot = addRoot(path = otherDir)

            val result = scanner.synchronize(
                LibraryScanRequest(fullRescan = false, folderIds = setOf(otherRoot), ignoreShortAudio = false)
            )

            assertEquals(1, result.inserted)
            assertEquals(
                listOf("other.ogg"),
                songs.observeSongs(SortBy.TITLE, SortOrder.ASC, null).first().map { it.fileName },
                "a scan of one root must not touch another root's songs",
            )
            assertTrue(keptRoot != otherRoot)
        } finally {
            otherDir.toFile().deleteRecursively()
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Copies a real audio fixture into [relativeDir] under the music directory. */
    private fun placeFixture(fixture: String, relativeDir: String): Path {
        val source = Path.of(System.getProperty("lyrico.audiotag.fixtures.dir"), fixture)
        assertTrue(Files.isRegularFile(source), "missing audio fixture $source")
        val targetDir = musicDir.resolve(relativeDir)
        Files.createDirectories(targetDir)
        val target = targetDir.resolve(fixture)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /** Adds a library root the way the folder picker does: `addedBySaf` marks it a user-chosen root. */
    private suspend fun addRoot(path: Path = musicDir): Long =
        library.database.folderDao().insert(
            FolderEntity(path = path.toRealPath().toString(), addedBySaf = true)
        )
}
