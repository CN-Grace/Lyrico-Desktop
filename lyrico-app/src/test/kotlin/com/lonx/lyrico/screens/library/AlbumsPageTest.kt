package com.lonx.lyrico.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.AlbumSortBy
import com.lonx.lyrico.data.model.entity.AlbumEntity
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_close
import com.lonx.lyrico.resources.album_delete_success
import com.lonx.lyrico.resources.album_grid_columns_format
import com.lonx.lyrico.resources.album_list_title
import com.lonx.lyrico.resources.album_replay_gain_calculating
import com.lonx.lyrico.resources.album_replay_gain_success
import com.lonx.lyrico.resources.cd_sort
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.dialog_delete_album_content
import com.lonx.lyrico.resources.empty_albums_title
import com.lonx.lyrico.resources.empty_library_index_summary
import com.lonx.lyrico.resources.label_album_artist
import com.lonx.lyrico.resources.label_year
import com.lonx.lyrico.resources.menu_action_calculate_album_replay_gain
import com.lonx.lyrico.resources.menu_action_delete_album
import com.lonx.lyrico.resources.menu_action_delete_album_sub
import com.lonx.lyrico.resources.menu_action_share_album
import com.lonx.lyrico.resources.refresh
import com.lonx.lyrico.resources.song_count
import com.lonx.lyrico.ui.navigation.AlbumDetailDestination
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.navigation.rememberNavigator
import com.lonx.lyrico.ui.theme.LyricoTheme
import com.lonx.lyrico.utils.LibraryScanManager
import com.lonx.lyrico.viewmodel.SortOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The albums tab, rendered from a real scan of real audio files.
 *
 * The whole desktop stack is real here: the folder is scanned by the real scanner, the tags are read
 * with TagLib from files the test tagged through the real tag repository, the album index is built by
 * `LibraryIndexRepository`, the rows come back out of a real Room database on disk, and the grid renders
 * whatever the observable query produced. The only replacements are the two screens this page
 * *navigates to* (asserted through a recording navigator, because their screens do not exist yet) and
 * Explorer: the delete path is real -- the audio files are unlinked from the temp library and their rows
 * disappear -- while "share" would open a real window on the developer's desktop, so it is exercised in
 * `AlbumActionsViewModelTest` against a recording `FileRevealRepository` instead.
 *
 * The album and artist names are written by [placeFixture] rather than read off TagLib's fixtures. The
 * fixtures ship with almost no usable tags (`bladeenc.mp3` has no album and no artist), and a song with
 * no album tag never becomes an album row, so an untagged fixture would quietly reduce every assertion
 * here to a test of the empty grid. Writing the tags makes the expected album names and counts the
 * test's own, and the counts are still read back out of the database so that what is asserted is what
 * the index actually holds.
 */
@OptIn(ExperimentalTestApi::class)
class AlbumsPageTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-albums-page").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-albums-page-library")

    private lateinit var database: LyricoDatabase

    /** Records the routes a card asks for, for the destinations whose screens are not ported yet. */
    private class RecordingNavigator : Navigator {
        val routes = mutableListOf<String>()

        override fun navigate(direction: NavDirection) {
            routes += direction.route
        }

        override fun popBackStack(): Boolean = false

        override fun navigateUp(): Boolean = false
    }

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        database = koin()
        // The same setting `SongsPageTest` documents: every audio fixture TagLib ships is shorter than
        // a minute, and the app's default is to ignore short audio, so a scan would drop them all.
        runBlocking { settings().saveIgnoreShortAudio(false) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        // Best effort: Room's connection pool may still hold the database file open.
        workingDir.deleteRecursively()
        musicDir.toFile().deleteRecursively()
    }

    private inline fun <reified T : Any> koin(): T = GlobalContext.get().get()

    private fun settings(): SettingsRepository = koin()

    private fun index(): LibraryIndexRepository = koin()

    /**
     * The page inside a real `NavHost` entry, the way the shell will host it.
     *
     * The entry matters for `koinViewModel()`: it resolves against the `NavBackStackEntry`, which is
     * the owner the shipped host uses, so composing the page bare would test a different setup.
     */
    @Composable
    private fun AlbumsPageInTestHost(navigatorOverride: Navigator? = null) {
        val controller = rememberNavController()
        val navigator = rememberNavigator(controller)
        NavHost(navController = controller, startDestination = "albums") {
            composable("albums") {
                AlbumsPage(navigator = navigatorOverride ?: navigator)
            }
        }
    }

    /**
     * Copies a real audio file out of TagLib's vendored test fixtures into the temp library, and tags it.
     *
     * The tags are not decoration. `bladeenc.mp3` is TagLib's own sample and carries no album and no
     * artist at all, so an untagged fixture scans into a song that never becomes an album row -- which
     * would leave these tests asserting what an empty grid looks like, whatever the page does. Writing
     * the tags through the real `AudioTagRepository` is also the path the metadata editor takes, so the
     * scan under test reads tags that were really put on disk.
     */
    private fun placeFixture(
        fixture: String,
        relativeDir: String,
        title: String,
        album: String,
        artist: String = "An Artist",
        fileName: String = fixture,
    ): Path = placeTaggedFixture(
        musicDir = musicDir,
        fixture = fixture,
        relativeDir = relativeDir,
        title = title,
        artist = artist,
        album = album,
        tags = koin(),
        fileName = fileName,
    )

    /**
     * Runs the app's own scan.
     *
     * `addFolderAndScan` is what the desktop shell uses: it records the root and then scans it on the
     * app's own coroutine scope, which is a real `Dispatchers.Default` scope, so the scan makes progress
     * while the test pumps frames instead of waiting for a frame that would never come.
     */
    private fun scanLibrary() {
        koin<LibraryScanManager>().addFolderAndScan(musicDir.toRealPath().toString())
    }

    /**
     * The expected text for a string resource, formatted the way the screen formats it.
     *
     * [String.format] rather than `getString(res, *args)`: the library's vararg overload ignores plain
     * `%d`/`%s` placeholders, so building the expectation with it would make the assertion below agree
     * with a screen that renders the placeholder instead of the value.
     */
    private fun text(res: StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    private fun albumsInLibrary(): List<AlbumEntity> =
        runBlocking { index().observeAlbums().first() }

    private fun songsOfAlbum(albumId: Long) =
        runBlocking { index().getSongsByAlbumId(albumId) }

    private fun SemanticsNodeInteractionsProvider.nodeCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `the albums tab renders a card and a formatted song count for every indexed album`() =
        runComposeUiTest {
            placeFixture("bladeenc.mp3", "Album One", title = "First Song", album = "Album One")
            placeFixture("silence-44-s.flac", "Album One", title = "Second Song", album = "Album One")
            placeFixture(
                "bladeenc.mp3",
                "Album Two",
                title = "Third Song",
                album = "Album Two",
                artist = "Another Artist",
                fileName = "third.mp3",
            )

            setContent {
                LyricoTheme {
                    AlbumsPageInTestHost()
                }
            }

            // The index only exists after a scan has read the tags and written the rows, so this is the
            // real scan -> TagLib -> Room -> index -> Flow chain, not a fixture inserted by hand.
            scanLibrary()
            waitUntil("wait for the album index to be built", 30_000) {
                albumsInLibrary().isNotEmpty()
            }

            val albums = albumsInLibrary()
            assertEquals(
                mapOf("Album One" to 2, "Album Two" to 1),
                albums.associate { it.name to it.songCount },
                "the scan reads the album tags that were written, one row per album; " +
                    "got ${albums.map { "${it.name}=${it.songCount}" }}",
            )

            waitUntil("wait for the album cards to be composed", 20_000) {
                nodeCount(text(Res.string.album_list_title, albums.size)) > 0
            }
            albums.forEach { album ->
                assertTrue(
                    nodeCount(album.name) > 0,
                    "the card for '${album.name}' has to be on screen",
                )
                // The card's summary is the count and the year, joined by a space -- the page's own rule
                // (`buildAlbumSummary`). The year is whatever tag the file carries, so it is read back
                // from the index rather than guessed; the count is what this is really about.
                val summary = buildString {
                    append(text(Res.string.song_count, album.songCount))
                    album.year?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
                }
                assertTrue(
                    nodeCount(summary) > 0,
                    "'${album.name}' holds ${album.songCount} songs, and the card has to say so " +
                        "(expected '$summary')",
                )
            }
            // The count is substituted, not printed. The C3 capture caught the songs title showing a
            // literal "歌曲（%d）"; the same class of bug is possible here because `song_count` is a plain
            // `%d` placeholder that the library's own vararg formatting would leave alone.
            assertEquals(
                0,
                nodeCount("%1\$d songs"),
                "the card must not print the placeholder instead of the count",
            )
        }

    @Test
    fun `a long press opens the album sheet and its delete row asks for confirmation`() =
        runComposeUiTest {
            placeFixture("bladeenc.mp3", "Album One", title = "First Song", album = "Album One")

            setContent {
                LyricoTheme {
                    AlbumsPageInTestHost()
                }
            }

            scanLibrary()
            waitUntil("wait for the album index to be built", 30_000) {
                albumsInLibrary().isNotEmpty()
            }
            val album = albumsInLibrary().single()
            waitUntil("wait for the album card to be composed", 20_000) { nodeCount(album.name) > 0 }

            onNodeWithText(album.name).performTouchInput { longClick() }

            waitUntil("wait for the album action sheet", 5_000) {
                nodeCount(text(Res.string.menu_action_delete_album)) > 0
            }
            // The sheet has to be the albums one: the album's name is its small title, and the rows are
            // the ported actions. The ReplayGain row Android showed first is here too -- the C4 batch left
            // it out while the scanner was still unported and C6d put it back; the row has its own test in
            // `the ReplayGain row measures the album and writes the album tags to every song`.
            assertTrue(nodeCount(text(Res.string.menu_action_calculate_album_replay_gain)) > 0)
            assertTrue(nodeCount(text(Res.string.menu_action_share_album)) > 0)
            assertTrue(nodeCount(text(Res.string.menu_action_delete_album_sub)) > 0)

            onNodeWithText(text(Res.string.menu_action_delete_album)).performClick()

            // Confirmation is a separate dialog, and it names the album and how many songs go with it.
            val summary = text(Res.string.dialog_delete_album_content, album.songCount, album.name)
            waitUntil("wait for the delete confirmation", 5_000) { nodeCount(summary) > 0 }
            assertTrue(
                nodeCount(text(Res.string.confirm)) > 0,
                "the dialog is a yes/no; its confirm button is what commits the delete",
            )
        }

    @Test
    fun `confirming the delete removes the album's files, its rows and reports the count`() =
        runComposeUiTest {
            placeFixture("bladeenc.mp3", "Album One", title = "First Song", album = "Album One")
            placeFixture("silence-44-s.flac", "Album One", title = "Second Song", album = "Album One")
            placeFixture(
                "bladeenc.mp3",
                "Album Two",
                title = "Third Song",
                album = "Album Two",
                artist = "Another Artist",
                fileName = "third.mp3",
            )

            setContent {
                LyricoTheme {
                    AlbumsPageInTestHost()
                }
            }

            scanLibrary()
            waitUntil("wait for the album index to be built", 30_000) {
                albumsInLibrary().isNotEmpty()
            }
            val albums = albumsInLibrary()
            val album = albums.maxByOrNull { it.songCount }!!
            val doomed = songsOfAlbum(album.id)
            val survivors = albums.filter { it.id != album.id }
            assertTrue(doomed.isNotEmpty(), "the album to delete has to have songs to delete")
            assertTrue(
                doomed.all { Files.exists(Path.of(it.uri)) },
                "the scan writes paths that exist, so the delete has something to remove",
            )
            waitUntil("wait for the album card to be composed", 20_000) { nodeCount(album.name) > 0 }

            onNodeWithText(album.name).performTouchInput { longClick() }
            waitUntil("wait for the album action sheet", 5_000) {
                nodeCount(text(Res.string.menu_action_delete_album)) > 0
            }
            onNodeWithText(text(Res.string.menu_action_delete_album)).performClick()
            waitUntil("wait for the delete confirmation", 5_000) {
                nodeCount(text(Res.string.dialog_delete_album_content, album.songCount, album.name)) > 0
            }
            onNodeWithText(text(Res.string.confirm)).performClick()

            // The files first: they are the part no fake could stand in for.
            waitUntil("wait for the album's rows to leave the index", 20_000) {
                songsOfAlbum(album.id).isEmpty()
            }
            doomed.forEach { song ->
                assertFalse(
                    Files.exists(Path.of(song.uri)),
                    "${song.fileName} has to be gone from disk, not only from the index",
                )
            }
            assertEquals(
                survivors.map { it.id }.toSet(),
                albumsInLibrary().map { it.id }.toSet(),
                "the deleted album is pruned from the index; the others are untouched",
            )
            survivors.forEach { survivor ->
                assertTrue(
                    songsOfAlbum(survivor.id).all { Files.exists(Path.of(it.uri)) },
                    "'${survivor.name}' must keep its files",
                )
            }

            // Then the UI: the card is gone, the title recounts, and the snackbar reports the numbers.
            waitUntil("wait for the deleted card to leave the grid", 20_000) {
                nodeCount(album.name) == 0
            }
            waitUntil("wait for the title to recount", 5_000) {
                nodeCount(text(Res.string.album_list_title, albums.size - 1)) > 0
            }
            waitUntil("wait for the result snackbar", 5_000) {
                nodeCount(text(Res.string.album_delete_success, doomed.size, doomed.size)) > 0
            }
        }

    @Test
    fun `the ReplayGain row measures the album and writes the album tags to every song`() =
        runComposeUiTest {
            placeFixture("bladeenc.mp3", "Album One", title = "First Song", album = "Album One")
            placeFixture("silence-44-s.flac", "Album One", title = "Second Song", album = "Album One")

            setContent {
                LyricoTheme {
                    AlbumsPageInTestHost()
                }
            }

            scanLibrary()
            waitUntil("wait for the album index to be built", 30_000) {
                albumsInLibrary().isNotEmpty()
            }
            val album = albumsInLibrary().single()
            val albumSongs = songsOfAlbum(album.id)
            assertEquals(2, albumSongs.size, "the album has to hold both fixtures")
            waitUntil("wait for the album card to be composed", 20_000) { nodeCount(album.name) > 0 }

            onNodeWithText(album.name).performTouchInput { longClick() }
            waitUntil("wait for the album action sheet", 5_000) {
                nodeCount(text(Res.string.menu_action_calculate_album_replay_gain)) > 0
            }
            onNodeWithText(text(Res.string.menu_action_calculate_album_replay_gain)).performClick()

            // Tapping the row hands over to the progress sheet. The action sheet has to be gone -- checking
            // only that the progress sheet appeared would also pass with both sheets up -- and the sheet
            // that stays is the one owning the Close button.
            waitUntil("wait for the action sheet to be dismissed", 5_000) {
                nodeCount(text(Res.string.menu_action_delete_album)) == 0
            }
            waitUntil("wait for the progress sheet to appear", 5_000) {
                nodeCount(text(Res.string.action_close)) > 0 ||
                    nodeCount(text(Res.string.album_replay_gain_calculating)) > 0
            }

            // The real ffmpeg run over real files, and the report it produces.
            waitUntil("wait for the album measurement to be reported", 60_000) {
                nodeCount(text(Res.string.album_replay_gain_success, albumSongs.size)) > 0
            }
            assertTrue(
                nodeCount(text(Res.string.action_close)) > 0,
                "the sheet stays up showing the result until the user closes it",
            )

            // And the tags really landed on disk: the report is only worth anything if the player can read
            // the album's gain off every song of the album.
            albumSongs.forEach { song ->
                val written = runBlocking {
                    koin<AudioTagRepository>().read(song.uri, AudioTagReadOptions(strict = true))
                }
                assertTrue(
                    !written.replayGainAlbumGain.isNullOrBlank(),
                    "${song.fileName} has to carry the album's gain on disk, not only in the report",
                )
            }
        }

    @Test
    fun `the sort menu lists the album fields and the column counts, and saving them reaches the store`() =
        runComposeUiTest {
            placeFixture("bladeenc.mp3", "Album One", title = "First Song", album = "Album One")
            val settings = settings()

            // The default matters to the layout, not only to the order: the A-Z rail is drawn off
            // `AlbumSortInfo`, and `NAME` is the one field that supports it, so any other default would
            // leave the albums tab with a plain scrollbar where the Android page had the rail.
            assertEquals(AlbumSortBy.NAME, runBlocking { settings.albumSortInfo.first() }.sortBy)
            assertEquals(SortOrder.ASC, runBlocking { settings.albumSortInfo.first() }.order)

            // The store is watched, not polled: see [SettingsFlowWatcher] for the Windows-only DataStore
            // failure the polling loop used to trip over.
            val settingsScope = CoroutineScope(Dispatchers.Default)
            val sortSaved = SettingsFlowWatcher(settingsScope, settings.albumSortInfo) {
                it.sortBy == AlbumSortBy.ALBUM_ARTIST
            }
            val columnsSaved = SettingsFlowWatcher(settingsScope, settings.albumGridColumns) { it == 3 }

            try {
                setContent {
                    LyricoTheme {
                        AlbumsPageInTestHost()
                    }
                }

                scanLibrary()
                waitUntil("wait for the album index to be built", 30_000) {
                    albumsInLibrary().isNotEmpty()
                }

                onNodeWithContentDescription(text(Res.string.cd_sort)).performClick()
                val artistField = text(Res.string.label_album_artist)
                val yearField = text(Res.string.label_year)
                waitUntil("wait for the sort menu to open", 5_000) { nodeCount(artistField) > 0 }
                assertTrue(nodeCount(yearField) > 0, "every album sort field belongs in the menu")
                onNodeWithText(artistField).performClick()

                // The choice goes to the real settings store, which is the same flow the grid sorts from.
                waitUntil("wait for the sort choice to be saved", 5_000) { sortSaved.isSatisfied }

                // The column count is the same dropdown's other entry, and it is the only knob that
                // changes the grid's density. The menu is deliberately *not* reopened: Miuix's overlay menu
                // reports a picked item through its summary and stays open, so the column entries are
                // already on screen -- clicking the sort icon again would collapse it.
                val threeColumns = text(Res.string.album_grid_columns_format, 3)
                waitUntil("wait for the column entry", 5_000) { nodeCount(threeColumns) > 0 }
                onNodeWithText(threeColumns).performClick()
                waitUntil("wait for the column count to be saved", 5_000) { columnsSaved.isSatisfied }
            } finally {
                sortSaved.stop()
                columnsSaved.stop()
                settingsScope.cancel()
            }
        }

    @Test
    fun `tapping a card asks for the album detail route it declares`() = runComposeUiTest {
        placeFixture("bladeenc.mp3", "Album One", title = "First Song", album = "Album One")
        val navigator = RecordingNavigator()

        setContent {
            LyricoTheme {
                AlbumsPageInTestHost(navigatorOverride = navigator)
            }
        }

        scanLibrary()
        waitUntil("wait for the album index to be built", 30_000) {
            albumsInLibrary().isNotEmpty()
        }
        val album = albumsInLibrary().single()
        waitUntil("wait for the album card to be composed", 20_000) { nodeCount(album.name) > 0 }

        onNodeWithText(album.name).performClick()

        assertEquals(
            listOf(AlbumDetailDestination(albumId = album.id).route),
            navigator.routes,
            "a card opens the album detail screen the page declared, arguments and all",
        )
    }

    @Test
    fun `an index with no albums shows the empty state, and refresh builds the index for a new root`() =
        runComposeUiTest {
            // A root that holds a song, but no scan has run yet: the state a user sees when the index
            // was never built.
            placeFixture("bladeenc.mp3", "Album One", title = "First Song", album = "Album One")
            runBlocking {
                database.folderDao().insert(
                    FolderEntity(path = musicDir.toRealPath().toString(), addedBySaf = true)
                )
            }

            setContent {
                LyricoTheme {
                    AlbumsPageInTestHost()
                }
            }

            waitUntil("wait for the empty state", 5_000) {
                nodeCount(text(Res.string.empty_albums_title)) > 0
            }
            assertTrue(nodeCount(text(Res.string.empty_library_index_summary)) > 0)

            onNodeWithText(text(Res.string.refresh)).performClick()

            // `refreshSongs()` rescans every root and then rebuilds the album index, so this covers the
            // one path that can recover an index that was never built.
            waitUntil("wait for refresh to build the album index", 30_000) {
                albumsInLibrary().isNotEmpty()
            }
            val album = albumsInLibrary().single()
            waitUntil("wait for the card to appear", 20_000) { nodeCount(album.name) > 0 }
            waitUntil("wait for the empty state to go away", 5_000) {
                nodeCount(text(Res.string.empty_albums_title)) == 0
            }
        }
}
