package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.entity.path
import com.lonx.lyrico.data.repository.CustomTagKeyRepository
import com.lonx.lyrico.data.repository.FileRevealRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepositoryImpl
import com.lonx.lyrico.data.repository.RevealResult
import com.lonx.lyrico.data.repository.SettingsRepositoryImpl
import com.lonx.lyrico.data.repository.createSettingsDataStore
import com.lonx.lyrico.data.song.file.AudioFileAccess
import com.lonx.lyrico.data.song.file.SongFileRepositoryImpl
import com.lonx.lyrico.data.song.library.SongLibraryRepositoryImpl
import com.lonx.lyrico.data.song.mapper.SongMetadataMapper
import com.lonx.lyrico.data.song.mapper.SortKeyUpdater
import com.lonx.lyrico.data.song.tag.AudioTagMutationResolver
import com.lonx.lyrico.data.song.tag.AudioTagRepositoryImpl
import com.lonx.lyrico.data.song.tag.DefaultImageBytesFetcher
import com.lonx.lyrico.data.song.tag.ImageMimeTypeDetector
import com.lonx.lyrico.data.song.tag.PictureMutationResolver
import com.lonx.lyrico.data.song.tag.TagMapBuilder
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.domain.song.usecase.DeleteSongsUseCase
import com.lonx.lyrico.domain.song.usecase.PatchSongTagsUseCase
import com.lonx.lyrico.domain.song.usecase.SaveAudioTagsUseCase
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.album_delete_success
import com.lonx.lyrico.resources.no_player_found
import com.lonx.lyrico.utils.ReplayGainScanner
import com.lonx.lyrico.utils.UiMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The album long-press actions, driven against a real database and real files.
 *
 * The line is the same one `SongSelectionViewModelTest` draws: everything whose failure mode is real
 * I/O is real — a file-backed `lyrico.db`, `DeleteSongsUseCase` unlinking the files, the album index
 * being rebuilt and pruned — and the one seam that would reach outside the process (Explorer) is a
 * recording fake, because a test must not open a real window on the developer's desktop.
 *
 * "Share" is where the port differs most from Android, and that difference is what these tests pin
 * down: there is no `Intent.ACTION_SEND_MULTIPLE` chooser on Windows, so sharing an album means
 * revealing its songs in Explorer, with the same `no_player_found` / `unknown_error` reporting.
 *
 * The messages are asserted as `UiMessage.Localized` resources and their argument lists, never as
 * resolved text, so the machine's locale cannot make a test pass or fail for the wrong reason.
 */
class AlbumActionsViewModelTest {

    private lateinit var library: TestLibrary
    private lateinit var scope: CoroutineScope
    private lateinit var musicDir: Path
    private lateinit var indexes: LibraryIndexRepositoryImpl
    private lateinit var files: SongFileRepositoryImpl
    private lateinit var settings: SettingsRepositoryImpl
    private lateinit var tags: AudioTagRepositoryImpl
    private var folderId: Long = 0L

    private val reveal = RecordingFileRevealRepository()

    @BeforeTest
    fun setUp() {
        // `deleteAlbum` and `shareAlbum(albumId)` launch onto `viewModelScope`, which is Main, and the
        // work behind them is real Room and real file IO, so Main is a real dispatcher here rather than
        // a virtual scheduler.
        Dispatchers.setMain(Dispatchers.Default)
        library = TestLibrary()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        musicDir = Files.createTempDirectory("lyrico-album-actions")

        settings = SettingsRepositoryImpl(
            createSettingsDataStore(
                Files.createTempFile("lyrico-album-actions-settings", ".preferences_pb"),
                scope,
            )
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
            appLogRepository = RecordingAppLogRepository(),
        )
        files = SongFileRepositoryImpl(
            database = library.database,
            fileAccess = AudioFileAccess(),
            libraryIndexRepository = indexes,
            sortKeyUpdater = SortKeyUpdater(),
            appLogRepository = RecordingAppLogRepository(),
        )
        folderId = runBlocking {
            library.database.folderDao().insert(FolderEntity(path = musicDir.toString(), addedBySaf = true))
        }
    }

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        Dispatchers.resetMain()
        scope.cancel()
        library.close()
        musicDir.toFile().deleteRecursively()
    }

    /**
     * The view model, with the graph the shipped module builds for it.
     *
     * Three of these dependencies have nothing to do with the album actions asserted below: the tag
     * patcher and the ReplayGain scanner are only reachable through `writeAlbumReplayGain` and
     * `calculateAlbumReplayGain`, whose behaviour is driven end to end (real ffmpeg, real TagLib
     * writes, real album tags read back off disk) by `AlbumReplayGainViewModelTest`. They are built for
     * real here anyway rather than stubbed, because a hand-built graph that quietly stops matching the
     * module's is the kind of drift a passing test cannot see.
     */
    private fun viewModel() = AlbumActionsViewModel(
        libraryIndexRepository = indexes,
        deleteSongsUseCase = DeleteSongsUseCase(files),
        patchSongTagsUseCase = PatchSongTagsUseCase(
            SaveAudioTagsUseCase(
                database = library.database,
                songLibraryRepository = SongLibraryRepositoryImpl(library.database),
                audioTagRepository = tags,
                customTagKeyRepository = CustomTagKeyRepository(library.database.songCustomTagKeyDao()),
                libraryIndexRepository = indexes,
                songMetadataMapper = SongMetadataMapper(SortKeyUpdater()),
            )
        ),
        replayGainScanner = ReplayGainScanner(),
        settingsRepository = settings,
        fileRevealRepository = reveal,
    )

    /** A real file on disk plus its indexed row, inserted the way the scanner does it. */
    private suspend fun seedSong(fileName: String, album: String = "Album One"): SongEntity {
        val file = musicDir.resolve(fileName)
        Files.writeString(file, "not really audio, but a real file")
        val song = SortKeyUpdater().update(
            SongEntity(
                folderId = folderId,
                mediaId = 0,
                source = SongSource.LOCAL,
                filePath = file.toString(),
                fileName = fileName,
                fileSize = Files.size(file),
                fileLastModified = Files.getLastModifiedTime(file).toMillis(),
                title = fileName.substringBeforeLast('.'),
                album = album,
                artist = "An Artist",
                albumArtist = "An Artist",
                uri = file.toString(),
            )
        )
        library.database.songDao().insert(song)
        return library.database.songDao().getSongByUri(song.uri)!!
    }

    /**
     * Two songs in one album, plus the indexed album row they belong to.
     *
     * `reindexSongs` is the same call the scanner makes after it writes rows, so the album id these
     * tests delete through is the real one, not a row inserted by hand.
     */
    private suspend fun seedAlbum(): Pair<Long, List<SongEntity>> {
        val songs = listOf(seedSong("first.mp3"), seedSong("second.mp3"))
        indexes.reindexSongs(songs)
        val album = indexes.observeAlbums().first().single()
        assertEquals(2, album.songCount, "both seeded songs belong to 'Album One'")
        return album.id to songs
    }

    private fun storedSongCount(): Int = runBlocking { library.database.songDao().getSongCount() }

    // ------------------------------------------------------------------ delete

    @Test
    fun `deleting an album removes every file and every row, and reports the count`() = runBlocking<Unit> {
        val (albumId, songs) = seedAlbum()
        val viewModel = viewModel()
        val messages = startCollecting(viewModel)

        viewModel.deleteAlbum(albumId)

        awaitUntil(describe = { storedSongCount() }, condition = { it == 0 })
        songs.forEach { song ->
            assertFalse(
                Files.exists(Path.of(song.uri)),
                "${song.fileName} has to be gone from disk, not only from the index",
            )
        }
        assertTrue(
            indexes.observeAlbums().first().isEmpty(),
            "an album with no songs left is pruned, so the cards cannot outlive the files",
        )

        val message = messages.nextOrNull() as UiMessage.Localized?
        assertEquals(Res.string.album_delete_success, message?.res)
        assertEquals(
            listOf(2, 2),
            message?.args?.toList(),
            "deleted / total, in that order -- the string is 'Deleted %1\$d / %2\$d songs'",
        )
    }

    // ------------------------------------------------------------------- share

    @Test
    fun `sharing an album reveals every one of its songs in Explorer`() = runBlocking<Unit> {
        val (albumId, songs) = seedAlbum()
        val viewModel = viewModel()
        val messages = startCollecting(viewModel)

        viewModel.shareAlbum(albumId)

        awaitUntil(describe = { reveal.revealed.size }, condition = { it == 1 })
        assertEquals(
            songs.map { it.path }.toSet(),
            reveal.revealed.single().toSet(),
            "the album's songs are handed over as paths, because `song.uri` is a path on desktop",
        )
        assertNull(messages.nextOrNull(300L), "a reveal that worked has nothing to report")
    }

    @Test
    fun `a reveal whose files are gone reports no_player_found`() = runBlocking<Unit> {
        val (albumId, songs) = seedAlbum()
        reveal.result = RevealResult.FilesUnavailable(songs.map { it.path })
        val viewModel = viewModel()
        val messages = startCollecting(viewModel)

        viewModel.shareAlbum(albumId)

        assertEquals(
            Res.string.no_player_found,
            messages.nextOrNull()?.let { (it as UiMessage.Localized).res },
            "Android collapsed 'no app can open this' into this one string, and so does the port",
        )
    }

    @Test
    fun `sharing an album with no songs never reaches Explorer`() = runBlocking<Unit> {
        val viewModel = viewModel()

        viewModel.shareAlbum(emptyList())

        assertTrue(
            reveal.revealed.isEmpty(),
            "opening a window for an album with nothing in it would be a surprise",
        )
    }

    // ----------------------------------------------------------------- helpers

    /**
     * A live collector for the view model's one-shot events, attached **before** the action runs.
     *
     * `events` is a `MutableSharedFlow` with no replay, so a collector attached after the tap would be
     * testing a screen that is not listening. Waiting on `subscriptionCount` is part of that: without it
     * the collector could still be starting when the action emits, which is a race rather than a test.
     */
    private class MessageCollector {
        private val received = Channel<UiMessage>(Channel.UNLIMITED)

        suspend fun nextOrNull(timeoutMillis: Long = 2_000L): UiMessage? =
            withTimeoutOrNull(timeoutMillis) { received.receive() }

        suspend fun collect(message: UiMessage) {
            received.send(message)
        }
    }

    private suspend fun startCollecting(viewModel: AlbumActionsViewModel): MessageCollector {
        val collector = MessageCollector()
        val ready = CompletableDeferred<Unit>()
        scope.launch {
            viewModel.events
                .onSubscription { ready.complete(Unit) }
                .collect { message -> collector.collect(message) }
        }
        ready.await()
        return collector
    }

    /** Polls [condition] until it holds, reporting [describe] if it never does. */
    private suspend fun <T> awaitUntil(
        timeoutMillis: Long = 5_000L,
        describe: () -> T,
        condition: (T) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val observed = describe()
            if (condition(observed)) return
            kotlinx.coroutines.delay(25L)
        }
        throw AssertionError("condition was never met within ${timeoutMillis}ms; last value ${describe()}")
    }

    private class RecordingFileRevealRepository : FileRevealRepository {
        var result: RevealResult = RevealResult.NothingToReveal
        val revealed = mutableListOf<List<Path>>()

        override fun reveal(paths: List<Path>): RevealResult {
            revealed.add(paths)
            return result
        }
    }
}
