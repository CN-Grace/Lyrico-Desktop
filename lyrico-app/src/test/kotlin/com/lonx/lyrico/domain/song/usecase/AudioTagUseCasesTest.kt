package com.lonx.lyrico.domain.song.usecase

import com.lonx.audiotag.model.AudioTagData
import com.lonx.audiotag.model.CustomTagField
import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.repository.CustomTagKeyRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepositoryImpl
import com.lonx.lyrico.data.repository.SettingsRepositoryImpl
import com.lonx.lyrico.data.repository.createSettingsDataStore
import com.lonx.lyrico.data.song.file.AudioFileAccess
import com.lonx.lyrico.data.song.library.SongLibraryRepositoryImpl
import com.lonx.lyrico.data.song.mapper.SongMetadataMapper
import com.lonx.lyrico.data.song.mapper.SortKeyUpdater
import com.lonx.lyrico.data.song.search.LyricFtsIndexer
import com.lonx.lyrico.data.song.tag.AudioTagFieldKey
import com.lonx.lyrico.data.song.tag.AudioTagMutation
import com.lonx.lyrico.data.song.tag.AudioTagMutationMode
import com.lonx.lyrico.data.song.tag.AudioTagMutationResolver
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.data.song.tag.AudioTagRepositoryImpl
import com.lonx.lyrico.data.song.tag.AudioTagWriteResult
import com.lonx.lyrico.data.song.tag.CustomTagFieldMutation
import com.lonx.lyrico.data.song.tag.DefaultImageBytesFetcher
import com.lonx.lyrico.data.song.tag.FieldMutation
import com.lonx.lyrico.data.song.tag.ImageMimeTypeDetector
import com.lonx.lyrico.data.song.tag.PictureMutationResolver
import com.lonx.lyrico.data.song.tag.TagMapBuilder
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import com.lonx.lyrico.data.support.TestLibrary
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tag use cases, end to end: real TagLib writes to real files, and a real `lyrico.db` behind them.
 *
 * These two use cases are the seam between the tag layer and the library, so the interesting
 * behaviour is not in their signatures but in what they *do* around the repository call:
 *
 * - [ReadAudioTagsUseCase] builds the read options, and the artist separator in those options comes
 *   from the user's settings. Getting that wrong silently changes how every multi-value tag is
 *   displayed, so it is pinned twice: once against the settings store through a recording repository
 *   (which is the only way to see the options at all) and once against a real file.
 * - [SaveAudioTagsUseCase] wraps the write and the database update in one transaction, and that
 *   transaction is the whole reason it exists: a written file whose row, custom tag keys and lyric
 *   index are not updated is a song the library shows the old way, and one whose index is not
 *   refreshed disappears from lyric search.
 */
class AudioTagUseCasesTest {

    private lateinit var library: TestLibrary
    private lateinit var scope: CoroutineScope
    private lateinit var musicDir: Path
    private lateinit var settings: SettingsRepositoryImpl
    private lateinit var tags: AudioTagRepositoryImpl
    private lateinit var readTags: ReadAudioTagsUseCase
    private lateinit var saveTags: SaveAudioTagsUseCase
    private val log = RecordingAppLogRepository()
    private var rootFolderId: Long? = null

    @BeforeTest
    fun setUp() {
        library = TestLibrary()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        musicDir = Files.createTempDirectory("lyrico-tag-usecases")
        settings = SettingsRepositoryImpl(
            createSettingsDataStore(
                Files.createTempFile("lyrico-tag-usecases-settings", ".preferences_pb"),
                scope,
            )
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
        readTags = ReadAudioTagsUseCase(audioTagRepository = tags, settingsRepository = settings)
        saveTags = SaveAudioTagsUseCase(
            database = library.database,
            songLibraryRepository = SongLibraryRepositoryImpl(library.database),
            audioTagRepository = tags,
            customTagKeyRepository = CustomTagKeyRepository(library.database.songCustomTagKeyDao()),
            libraryIndexRepository = LibraryIndexRepositoryImpl(
                database = library.database,
                songDao = library.database.songDao(),
                indexDao = library.database.libraryIndexDao(),
                settingsRepository = settings,
            ),
            songMetadataMapper = SongMetadataMapper(SortKeyUpdater()),
        )
    }

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        scope.cancel()
        scope.coroutineContext.job.join()
        library.close()
        musicDir.toFile().deleteRecursively()
    }

    // --- reading ------------------------------------------------------------------------------

    @Test
    fun `reads the tags of a real file`() = runBlocking<Unit> {
        for (fixture in FIXTURES) {
            val path = copyFixture(fixture)

            val data = readTags(path.toString())

            assertEquals(fixture, data.fileName, "the name comes from the path, for $fixture")
            assertTrue(data.durationMilliseconds > 0, "the file's duration must be parsed, for $fixture")
        }
    }

    @Test
    fun `the artist separator passed to the tag reader comes from the settings`() = runBlocking<Unit> {
        val repository = RecordingAudioTagRepository()
        val useCase = ReadAudioTagsUseCase(repository, settings)

        settings.saveSeparator("; ")

        val data = useCase("""H:\Music\bladeenc.mp3""")

        assertEquals("; ", repository.readOptions?.multiValueSeparator, "the user's separator must be used")
        assertEquals(repository.data, data)
    }

    @Test
    fun `the separator defaults to the tag layer's own default`() = runBlocking<Unit> {
        val repository = RecordingAudioTagRepository()

        ReadAudioTagsUseCase(repository, settings)("""H:\Music\bladeenc.mp3""")

        assertEquals(
            settings.separator.first(),
            repository.readOptions?.multiValueSeparator,
            "with nothing saved, the default must flow through unchanged",
        )
        assertEquals("/", repository.readOptions?.multiValueSeparator)
    }

    @Test
    fun `the saved separator is the one a later read uses`() = runBlocking<Unit> {
        val repository = RecordingAudioTagRepository()

        settings.saveSeparator(" / ")
        ReadAudioTagsUseCase(repository, settings)("""H:\Music\a.mp3""")
        assertEquals(" / ", repository.readOptions?.multiValueSeparator)

        // …and changing it again must be picked up, not cached from the first read.
        settings.saveSeparator("&")
        ReadAudioTagsUseCase(repository, settings)("""H:\Music\a.mp3""")
        assertEquals("&", repository.readOptions?.multiValueSeparator)
    }

    // --- writing ------------------------------------------------------------------------------

    @Test
    fun `a saved tag lands on disk and in the song's row`() = runBlocking<Unit> {
        val path = copyFixture("bladeenc.mp3")
        val song = addSong(path)

        val result = saveTags(path.toString(), mutation(AudioTagFieldKey.Title to "Saved Title"))

        val success = assertIs<SaveAudioTagsResult.Success>(result)
        assertEquals("Saved Title", success.tagData.title, "the returned snapshot is what was written")

        // On disk: read the file again through the tag layer, not through the mutation.
        assertEquals("Saved Title", tags.read(path.toString(), AudioTagReadOptions(strict = true)).title)

        // In the database: the row follows the file.
        val stored = assertNotNull(library.database.songDao().getSongByUri(song.uri))
        assertEquals("Saved Title", stored.title)
        assertEquals(success.song?.titleSortKey, stored.titleSortKey, "the sort key must be recomputed")
    }

    @Test
    fun `the sort key is recomputed from the saved title, not copied from the old one`() =
        runBlocking<Unit> {
            val path = copyFixture("bladeenc.mp3")
            val song = addSong(path)
            // The old row is written the way the scanner writes one, so its sort key really does
            // describe "Aaa Old Title" and a stale key cannot hide behind an unrelated default.
            val old = SortKeyUpdater().update(song.copy(title = "Aaa Old Title"))
            library.database.songDao().update(old)

            saveTags(path.toString(), mutation(AudioTagFieldKey.Title to "Zzz New Title"))

            val stored = library.database.songDao().getSongByUri(old.uri)!!
            assertEquals("Zzz New Title", stored.title)
            assertTrue(
                stored.titleSortKey != old.titleSortKey,
                "a stale sort key would sort the song by a title it no longer has",
            )
        }

    @Test
    fun `custom tag keys are replaced by what the file now contains`() = runBlocking<Unit> {
        val path = copyFixture("bladeenc.mp3")
        val song = addSong(path)
        // Pre-existing keys must not survive the write: they describe the old tag contents.
        library.database.songCustomTagKeyDao().replaceForSong(song.uri, listOf("OLDKEY"))
        assertEquals(listOf("OLDKEY"), library.database.songCustomTagKeyDao().getKeysForSong(song.uri))

        // Not "MOOD": the tag reader treats that as an alias for the genre field, so it is reserved
        // and filtered out of the custom fields. A genuinely custom key is what is being tested.
        val result = saveTags(
            path.toString(),
            mutation(AudioTagFieldKey.Title to "Has Custom Tags").copy(
                customFields = listOf(
                    CustomTagFieldMutation.ReplaceAll(listOf(CustomTagField("MYFIELD", "calm")))
                )
            ),
        )

        assertIs<SaveAudioTagsResult.Success>(result)
        assertEquals(
            1,
            result.tagData.customFields.count { it.key == "MYFIELD" },
            "the field must be on disk, was ${result.tagData.customFields}",
        )
        assertEquals(
            listOf("MYFIELD"),
            library.database.songCustomTagKeyDao().getKeysForSong(song.uri),
            "the stored keys must follow the file, not lag behind it",
        )
    }

    @Test
    fun `saved lyrics are indexed for search right away`() = runBlocking<Unit> {
        val path = copyFixture("bladeenc.mp3")
        val song = addSong(path)

        val result = saveTags(
            path.toString(),
            mutation(AudioTagFieldKey.Lyrics to "[00:01.00]First saved line\n[00:02.00]Second saved line"),
        )

        val success = assertIs<SaveAudioTagsResult.Success>(result)
        assertTrue(
            success.tagData.lyrics?.contains("First saved line") == true,
            "the lyrics must have been written to the file",
        )
        assertEquals(
            listOf("First saved line", "Second saved line"),
            library.indexedLyricLines(song.uri),
            "a written lyric that is not indexed is a lyric the user cannot search for",
        )
    }

    @Test
    fun `a song cannot be found by its old lyrics once they are replaced`() = runBlocking<Unit> {
        val path = copyFixture("bladeenc.mp3")
        val song = addSong(path, lyrics = "[00:01.00]Obsolete line")

        saveTags(path.toString(), mutation(AudioTagFieldKey.Lyrics to "[00:01.00]Replacement line"))

        assertEquals(listOf("Replacement line"), library.indexedLyricLines(song.uri))
    }

    @Test
    fun `a write to a file that is not there fails and leaves the row untouched`() = runBlocking<Unit> {
        val path = copyFixture("bladeenc.mp3")
        val song = addSong(path).copy(title = "Original Title").let {
            library.database.songDao().update(it)
            library.database.songDao().getSongByUri(it.uri)!!
        }
        Files.delete(path)

        val result = saveTags(path.toString(), mutation(AudioTagFieldKey.Title to "Never Written"))

        assertIs<SaveAudioTagsResult.Failed>(result)
        val stored = library.database.songDao().getSongByUri(song.uri)!!
        assertEquals("Original Title", stored.title, "a failed write must not change the row")
        assertEquals(song.titleSortKey, stored.titleSortKey)
    }

    @Test
    fun `writing tags for a file the library does not know succeeds without a row`() = runBlocking<Unit> {
        val path = copyFixture("bladeenc.mp3")
        // Deliberately no database row: the file exists, the library has not seen it.
        assertNull(library.database.songDao().getSongByUri(path.toRealPath().toString()))

        val result = saveTags(path.toString(), mutation(AudioTagFieldKey.Title to "Unindexed Song"))

        val success = assertIs<SaveAudioTagsResult.Success>(result)
        assertNull(success.song, "there is no row to update, and inventing one would be wrong")
        assertEquals("Unindexed Song", tags.read(path.toString(), AudioTagReadOptions(strict = true)).title)
    }

    @Test
    fun `clearing a field removes it from the file and from the row`() = runBlocking<Unit> {
        val path = copyFixture("bladeenc.mp3")
        val song = addSong(path)
        saveTags(path.toString(), mutation(AudioTagFieldKey.Title to "Title To Lose"))
        assertEquals(
            "Title To Lose",
            library.database.songDao().getSongByUri(song.uri)!!.title,
            "the setup must really have written the title",
        )

        // An explicit clear, which is what the edit screen sends when the user empties a field. An
        // empty mutation would mean "change nothing" — the resolver applies only what it is given.
        val result = saveTags(
            path.toString(),
            AudioTagMutation(
                mode = AudioTagMutationMode.Overwrite,
                fields = mapOf(AudioTagFieldKey.Title to FieldMutation.Clear),
            ),
        )

        assertIs<SaveAudioTagsResult.Success>(result)
        assertNull(tags.read(path.toString(), AudioTagReadOptions(strict = true)).title)
        assertNull(library.database.songDao().getSongByUri(song.uri)!!.title)
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun mutation(vararg fields: Pair<AudioTagFieldKey, String>) = AudioTagMutation(
        mode = AudioTagMutationMode.Patch,
        fields = fields.associate { (key, value) -> key to FieldMutation.Set(value) },
    )

    /** A recording repository, for the one property that only its options argument can show. */
    private class RecordingAudioTagRepository : AudioTagRepository {
        val data = AudioTagData()
        var readOptions: AudioTagReadOptions? = null
            private set

        override suspend fun read(uri: String, options: AudioTagReadOptions): AudioTagData {
            readOptions = options
            return data
        }

        override suspend fun overwrite(uri: String, mutation: AudioTagMutation): AudioTagWriteResult =
            error("overwrite is not part of the read use case")

        override suspend fun patch(uri: String, mutation: AudioTagMutation): AudioTagWriteResult =
            error("patch is not part of the read use case")
    }

    private fun copyFixture(name: String): Path {
        val fixture = Path.of(System.getProperty(FIXTURES_DIR_PROPERTY), name)
        assertTrue(
            Files.isRegularFile(fixture),
            "missing audio fixture ${fixture.toAbsolutePath()} — set $FIXTURES_DIR_PROPERTY",
        )
        val target = musicDir.resolve(name)
        Files.copy(fixture, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /**
     * Files a row for a real file, the way the scanner does — sort keys included, and with the lyric
     * index seeded through its own write path so that "the index changed" cannot be true by accident.
     */
    private suspend fun addSong(path: Path, lyrics: String? = null): SongEntity {
        val uri = path.toRealPath().toString()
        val song = SortKeyUpdater().update(
            SongEntity(
                folderId = requireNotNull(rootFolderId ?: rootFolder()),
                mediaId = 0,
                source = SongSource.LOCAL,
                filePath = uri,
                fileName = path.fileName.toString(),
                fileSize = Files.size(path),
                fileLastModified = Files.getLastModifiedTime(path).toMillis(),
                title = "Title of ${path.fileName}",
                artist = "Artist",
                lyrics = lyrics,
                uri = uri,
            )
        )
        library.database.songDao().insert(song)
        val stored = library.database.songDao().getSongByUri(uri)!!
        LyricFtsIndexer.replaceSong(library.database.songDao(), stored)
        return stored
    }

    private suspend fun rootFolder(): Long {
        val id = library.database.folderDao().insert(
            FolderEntity(path = musicDir.toRealPath().toString(), addedBySaf = true)
        )
        rootFolderId = id
        return id
    }

    private companion object {
        const val FIXTURES_DIR_PROPERTY = "lyrico.audiotag.fixtures.dir"

        /**
         * An ID3v2 file and a Vorbis comment file: the two containers write tags through different
         * TagLib paths, and only a real file can show which one a use case actually reached.
         */
        val FIXTURES = listOf("bladeenc.mp3", "silence-44-s.flac")
    }
}
