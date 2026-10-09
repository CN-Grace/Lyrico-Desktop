package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.SharedSelectionManager
import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.entity.path
import com.lonx.lyrico.data.repository.FileRevealRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepositoryImpl
import com.lonx.lyrico.data.repository.PlaybackRepository
import com.lonx.lyrico.data.repository.PlaybackResult
import com.lonx.lyrico.data.repository.RevealResult
import com.lonx.lyrico.data.repository.SettingsRepositoryImpl
import com.lonx.lyrico.data.repository.createSettingsDataStore
import com.lonx.lyrico.data.song.file.AudioFileAccess
import com.lonx.lyrico.data.song.file.SongFileRepositoryImpl
import com.lonx.lyrico.data.song.mapper.SortKeyUpdater
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.domain.song.usecase.DeleteSongsUseCase
import com.lonx.lyrico.domain.song.usecase.RenameSongUseCase
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.no_player_found
import com.lonx.lyrico.resources.unknown_error
import com.lonx.lyrico.utils.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
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
 * What selection mode can do to the songs in it, driven against a real database and real files.
 *
 * The line is drawn where the rest of the port draws it: everything whose failure mode is *real I/O* is
 * real — a file-backed `lyrico.db`, `DeleteSongsUseCase` deleting files on disk, `RenameSongUseCase`
 * moving them — and only the two seams that would reach outside the process are recording fakes: the
 * shell (`PlaybackRepository`) and Explorer (`FileRevealRepository`), neither of which a test may
 * actually invoke. A fake file repository would be the wrong trade here: it would happily report
 * "deleted" for a file it never touched, which is the one thing these tests exist to check.
 *
 * The messages are asserted as `UiMessage.Localized` resources, never as resolved text, so a locale
 * change on the test machine cannot make them pass or fail for the wrong reason.
 */
class SongSelectionViewModelTest {

    private lateinit var library: TestLibrary
    private lateinit var scope: CoroutineScope
    private lateinit var musicDir: Path
    private lateinit var files: SongFileRepositoryImpl
    private var folderId: Long = 0L

    private val selectionManager = SharedSelectionManager()
    private val playback = RecordingPlaybackRepository()
    private val reveal = RecordingFileRevealRepository()

    @BeforeTest
    fun setUp() {
        // The view model launches onto `viewModelScope`, which is Main. Real Room and real file IO both
        // need a real dispatcher, so Main is a real one here rather than a virtual scheduler.
        Dispatchers.setMain(Dispatchers.Default)
        library = TestLibrary()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        musicDir = Files.createTempDirectory("lyrico-selection")

        val indexes = LibraryIndexRepositoryImpl(
            database = library.database,
            songDao = library.database.songDao(),
            indexDao = library.database.libraryIndexDao(),
            settingsRepository = SettingsRepositoryImpl(
                createSettingsDataStore(
                    Files.createTempFile("lyrico-selection-settings", ".preferences_pb"),
                    scope,
                )
            ),
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

    private fun viewModel() = SongSelectionViewModel(
        deleteSongsUseCase = DeleteSongsUseCase(files),
        renameSongUseCase = RenameSongUseCase(files),
        playbackRepository = playback,
        fileRevealRepository = reveal,
        selectionManager = selectionManager,
    )

    /**
     * The row count, read from a *blocking* helper so `awaitUntil` can poll it.
     *
     * Awaiting the file disappearing is not enough: the file is unlinked first and the rows are
     * removed afterwards, in the same `withContext(Dispatchers.IO)` block, so a test that stopped at the
     * file would race the database half of the delete.
     */
    private fun songCount(): Int = runBlocking { library.database.songDao().getSongCount() }

    private fun storedFileName(uri: String): String? =
        runBlocking { library.database.songDao().getSongByUri(uri)?.fileName }

    /** A real file on disk plus its indexed row, inserted the way the scanner does it. */
    private suspend fun seedSong(fileName: String): SongEntity {
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
                uri = file.toString(),
            )
        )
        library.database.songDao().insert(song)
        return library.database.songDao().getSongByUri(song.uri)!!
    }

    // ------------------------------------------------------------------ play

    @Test
    fun `playing an available song hands the shell the song's path and stays quiet`() = runBlocking<Unit> {
        val song = seedSong("song.mp3")
        val viewModel = viewModel()
        val messages = startCollecting(viewModel)

        viewModel.play(song)

        assertEquals(
            listOf(song.path),
            playback.opened,
            "the repository takes a Path, and `song.uri` is the path string it has to come from",
        )
        assertNull(messages.nextOrNull(300L), "a successful play must not raise a message")
    }

    @Test
    fun `a missing file reports no_player_found, exactly as Android did`() = runBlocking<Unit> {
        val song = seedSong("song.mp3")
        playback.result = PlaybackResult.FileUnavailable(song.path)
        val viewModel = viewModel()
        val messages = startCollecting(viewModel)

        viewModel.play(song)

        assertEquals(
            Res.string.no_player_found,
            messages.nextOrNull()?.let { (it as UiMessage.Localized).res },
            "Android collapsed ActivityNotFoundException, which covered both cases, into this copy",
        )
    }

    @Test
    fun `a refused shell call reports unknown_error together with the reason`() = runBlocking<Unit> {
        val song = seedSong("song.mp3")
        playback.result = PlaybackResult.Failed(song.path, IllegalStateException("association broken"))
        val viewModel = viewModel()
        val messages = startCollecting(viewModel)

        viewModel.play(song)

        val message = messages.nextOrNull() as UiMessage.Localized?
        assertEquals(Res.string.unknown_error, message?.res)
        assertEquals(
            listOf("association broken"),
            message?.args?.toList(),
            "the throwable's message is what makes unknown_error actionable, so it must survive the port",
        )
    }

    // ------------------------------------------------------- delete and rename

    @Test
    fun `deleting one song removes the file and its row`() = runBlocking<Unit> {
        val song = seedSong("song.mp3")
        val viewModel = viewModel()

        viewModel.delete(song)

        awaitUntil(describe = { songCount() }, condition = { it == 0 })
        assertFalse(Files.exists(Path.of(song.uri)), "the file itself has to be gone, not only its row")
    }

    @Test
    fun `a finished batch delete leaves selection mode`() = runBlocking<Unit> {
        val first = seedSong("first.mp3")
        val second = seedSong("second.mp3")
        val viewModel = viewModel()
        viewModel.selectAll(listOf(first, second))

        viewModel.batchDelete(listOf(first, second))

        awaitUntil(describe = { songCount() }, condition = { it == 0 })
        assertFalse(
            viewModel.isSelectionMode.value,
            "with nothing left to act on, the selection bar has to close itself",
        )
        assertTrue(viewModel.selectedSongUris.value.isEmpty())
    }

    @Test
    fun `a batch delete leaves songs outside the selection alone`() = runBlocking<Unit> {
        val selected = seedSong("selected.mp3")
        val untouched = seedSong("untouched.mp3")
        val viewModel = viewModel()
        viewModel.toggleSelection(selected.uri)

        viewModel.batchDelete(listOf(selected, untouched))

        awaitUntil(describe = { songCount() }, condition = { it == 1 })
        assertTrue(Files.exists(Path.of(untouched.uri)), "a song outside the selection must not be deleted")
        assertFalse(Files.exists(Path.of(selected.uri)), "the selected song's file must be gone")
    }

    @Test
    fun `renaming a song moves the file and keeps exactly one row`() = runBlocking<Unit> {
        val song = seedSong("old name.mp3")
        val renamed = musicDir.resolve("new name.mp3")
        val viewModel = viewModel()

        viewModel.renameSong(song, "new name.mp3")

        // The move happens before the row is re-keyed, so polling the file would win the race and read
        // the pre-update database. The row is the observable that means "the rename is done".
        awaitUntil(
            describe = { storedFileName(renamed.toString()) },
            condition = { it == "new name.mp3" },
        )

        assertFalse(Files.exists(Path.of(song.uri)), "the old name must be gone, not left behind")
        assertTrue(Files.exists(renamed), "the renamed file has to exist at the path the row now names")
        assertEquals(1, songCount(), "a rename updates the row, it does not add one")
        assertNull(
            library.database.songDao().getSongByUri(song.uri),
            "the row has to be re-keyed to the new path, or the library would point at a missing file",
        )
    }

    // ----------------------------------------------------------------- share

    @Test
    fun `sharing reveals the selected songs in Explorer`() = runBlocking<Unit> {
        val first = seedSong("first.mp3")
        val second = seedSong("second.mp3")
        val viewModel = viewModel()
        val messages = startCollecting(viewModel)
        viewModel.selectAll(listOf(first, second))

        viewModel.share(listOf(first, second))

        assertEquals(listOf(listOf(first.path, second.path)), reveal.revealed)
        assertNull(messages.nextOrNull(300L), "a reveal that worked has nothing to report")
    }

    @Test
    fun `sharing with an empty selection never reaches the shell`() = runBlocking<Unit> {
        val song = seedSong("song.mp3")
        val viewModel = viewModel()

        viewModel.share(listOf(song))

        assertTrue(reveal.revealed.isEmpty(), "opening a window for an empty selection would be a surprise")
    }

    @Test
    fun `a reveal whose files are gone reports no_player_found`() = runBlocking<Unit> {
        val song = seedSong("song.mp3")
        reveal.result = RevealResult.FilesUnavailable(listOf(song.path))
        val viewModel = viewModel()
        val messages = startCollecting(viewModel)
        viewModel.toggleSelection(song.uri)

        viewModel.share(listOf(song))

        assertEquals(
            Res.string.no_player_found,
            messages.nextOrNull()?.let { (it as UiMessage.Localized).res },
        )
    }

    // ------------------------------------------------------------- selection

    @Test
    fun `publishing an empty selection reports that there is nothing to publish`() {
        val viewModel = viewModel()

        assertFalse(viewModel.setSelectionUris())
    }

    @Test
    fun `publishing a selection hands the keys to the shared manager`() {
        val viewModel = viewModel()
        viewModel.toggleSelection("a.mp3")

        assertTrue(viewModel.setSelectionUris())
        assertEquals(setOf("a.mp3"), selectionManager.selectedUris.value)
    }

    @Test
    fun `exiting selection mode clears the selection and the swipe anchor`() {
        val viewModel = viewModel()
        viewModel.toggleSelection("a.mp3")

        viewModel.exitSelectionMode()

        assertTrue(viewModel.selectedSongUris.value.isEmpty())
        assertFalse(viewModel.isSelectionMode.value)
        assertNull(viewModel.swipeAnchorUri.value)
    }

    @Test
    fun `swipe selecting twice covers the range between the anchor and the finger`() = runBlocking<Unit> {
        val a = seedSong("a.mp3")
        val b = seedSong("b.mp3")
        val c = seedSong("c.mp3")
        val visible = listOf(a, b, c)
        val viewModel = viewModel()

        // The first swipe *is* what sets the anchor: a `toggle` never does, so the gesture has to be
        // modelled the way the list drives it rather than by pre-selecting one row.
        viewModel.swipeSelect(a, visible)
        viewModel.swipeSelect(c, visible)

        assertEquals(setOf(a.uri, b.uri, c.uri), viewModel.selectedSongUris.value)
        assertNull(
            viewModel.swipeAnchorUri.value,
            "a completed range clears the anchor, so the next swipe starts a new range",
        )
    }

    // ----------------------------------------------------------------- helpers

    /**
     * A live collector for the view model's one-shot events, attached **before** the action runs.
     *
     * This has to be attached first, because `events` is a `MutableSharedFlow` with no replay: it is the
     * screen's `LaunchedEffect { events.collect { ... } }` that is subscribed while the user taps, so
     * subscribing after the tap would be testing a screen that is not listening. [startCollecting] waits
     * on `subscriptionCount` for the same reason — without that wait the collector could still be
     * starting when the action emits, which is a race, not a test.
     */
    private class MessageCollector {
        private val received = Channel<UiMessage>(Channel.UNLIMITED)

        suspend fun nextOrNull(timeoutMillis: Long = 2_000L): UiMessage? =
            withTimeoutOrNull(timeoutMillis) { received.receive() }

        suspend fun collect(message: UiMessage) {
            received.send(message)
        }
    }

    private suspend fun startCollecting(viewModel: SongSelectionViewModel): MessageCollector {
        val collector = MessageCollector()
        val ready = CompletableDeferred<Unit>()
        scope.launch {
            viewModel.events
                .onSubscription { ready.complete(Unit) }
                .collect { event ->
                collector.collect((event as SongSelectionEvent.ShowMessage).message)
            }
        }
        ready.await()
        return collector
    }

    private class RecordingPlaybackRepository : PlaybackRepository {
        var result: PlaybackResult = PlaybackResult.Opened
        val opened = mutableListOf<Path>()

        override fun open(path: Path): PlaybackResult {
            // `Path` is an `Iterable<Path>`, so `+=` here would compile to `addAll` rather than `add`.
            opened.add(path)
            return result
        }
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
