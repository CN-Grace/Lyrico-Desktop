package com.lonx.lyrico.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.tag.AudioTagFieldKey
import com.lonx.lyrico.data.song.tag.AudioTagMutation
import com.lonx.lyrico.data.song.tag.AudioTagMutationMode
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.data.song.tag.FieldMutation
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.action_close
import com.lonx.lyrico.resources.album_action_more
import com.lonx.lyrico.resources.album_delete_success
import com.lonx.lyrico.resources.album_detail_title
import com.lonx.lyrico.resources.cd_sort
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.dialog_delete_album_content
import com.lonx.lyrico.resources.library_tab_albums
import com.lonx.lyrico.resources.menu_action_calculate_album_replay_gain
import com.lonx.lyrico.resources.menu_action_delete_album
import com.lonx.lyrico.resources.menu_action_share_album
import com.lonx.lyrico.resources.selection_mode_selected_count
import com.lonx.lyrico.resources.song_count
import com.lonx.lyrico.resources.unknown_album_artist
import com.lonx.lyrico.screens.library.placeTaggedFixture
import com.lonx.lyrico.support.HeadlessMainDispatcher
import com.lonx.lyrico.ui.navigation.AlbumDetailDestination
import com.lonx.lyrico.ui.navigation.EditMetadataDestination
import com.lonx.lyrico.ui.navigation.LyricoNavHost
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.theme.LyricoTheme
import com.lonx.lyrico.utils.LibraryScanManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * The album detail page, rendered from a real scan of real audio files.
 *
 * The whole desktop stack is real: the temp folder is scanned by the app's own scanner, the tags are
 * written and read back through the real tag repository, the album and its songs come out of a real Room
 * database by way of `LibraryIndexRepository`, and the page renders whatever the observable query
 * produced. The only replacement is the navigator in the tests whose subject is a callback (the song row
 * and the header's back icon); the last test walks the *shipped* graph instead, so the `album_detail`
 * registration and the `Long` argument's round-trip are covered without a fake.
 *
 * Two properties of the page decide the shape of these tests:
 *
 * * **The album id is an argument**, so it has to be known before the scene is composed -- a
 *   `ComposeUiTest` owns exactly one scene and cannot re-compose with the id a scan produced later. The
 *   library is therefore scanned *before* `setContent`, by polling the same index the page reads.
 * * **The track-number column is what makes this page not the library's song list.** The fixtures are
 *   given track numbers through the real tag repository, because TagLib's own samples carry none: without
 *   them every row would fall back to the em dash and the order assertion would only be proving the file
 *   order.
 *
 * [HeadlessMainDispatcher] is installed for the reason `EditMetadataScreenTest` documents: a click that
 * reaches `NavController.navigate` is dispatched on the test worker thread, while `androidx.lifecycle`
 * refuses a lifecycle write from anywhere but the main dispatcher. The failure is otherwise invisible --
 * the pushed entry stays `INITIALIZED` and the harness reports it one step later as "State must be at
 * least 'CREATED' to be moved to 'DESTROYED'". It matters for the tests that do not navigate too, since
 * `koinViewModel()` resolves against that same entry.
 */
@OptIn(ExperimentalTestApi::class)
class AlbumDetailScreenTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-album-detail").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-album-detail-library")
    private val mainDispatcher = HeadlessMainDispatcher()

    /** Records what the page asks for, and whether it asked to leave. */
    private class RecordingNavigator : Navigator {
        val directions = mutableListOf<NavDirection>()
        var popCount = 0

        override fun navigate(direction: NavDirection) {
            directions += direction
        }

        override fun popBackStack(): Boolean {
            popCount++
            return true
        }

        override fun navigateUp(): Boolean = false
    }

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        mainDispatcher.install()
        startKoin { modules(desktopAppModule(directories)) }
        // The same setting `SongsPageTest` documents: every fixture TagLib ships is shorter than a
        // minute, and the app's default is to ignore short audio, so a scan would drop them all.
        runBlocking { settings().saveIgnoreShortAudio(false) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        mainDispatcher.restore()
        // Best effort: Room's connection pool may still hold the database file open.
        workingDir.deleteRecursively()
        musicDir.toFile().deleteRecursively()
    }

    // ------------------------------------------------------------------ harness

    private inline fun <reified T : Any> koin(): T = GlobalContext.get().get()

    private fun settings(): SettingsRepository = koin()

    private fun index(): LibraryIndexRepository = koin()

    /**
     * The page inside a real `NavHost` entry, the way the shipped host mounts it.
     *
     * The entry is what `koinViewModel()` resolves against, so composing the page bare would test a
     * different owner than the app uses. The route keeps the real shape -- `AlbumDetailDestination.PATTERN`
     * -- but the argument is deliberately *not* declared here: the id is handed to the screen as a
     * parameter, so the pattern's placeholder stays a placeholder and the graph needs nothing to
     * navigate to it. Decoding the id out of the route string is the shipped graph's job, and that is
     * asserted against the shipped graph in the last test rather than re-implemented here. The batch task
     * detail test hosts its screen the same way.
     */
    @Composable
    private fun AlbumDetailScreenInTestHost(
        albumId: Long,
        navigator: Navigator = RecordingNavigator(),
    ) {
        val controller = rememberNavController()
        NavHost(
            navController = controller,
            startDestination = AlbumDetailDestination.PATTERN,
        ) {
            composable(route = AlbumDetailDestination.PATTERN) {
                AlbumDetailScreen(navigator = navigator, albumId = albumId)
            }
        }
    }

    // ------------------------------------------------------------------ seeding

    /**
     * Places a tagged fixture and gives it a track number.
     *
     * A second, `Patch`-mode write rather than a new parameter on the shared helper: this page is the
     * only one in the port with a track-number column, and `Patch` leaves the tags written a moment ago
     * alone. The DAO orders an album's songs by disc and then track number, so this is also what makes
     * the order assertion below about the album's own order instead of the scan's.
     */
    private fun placeTrackedFixture(
        fixture: String,
        title: String,
        trackNumber: Int,
        fileName: String = fixture,
    ): Path {
        val path = placeTaggedFixture(
            musicDir = musicDir,
            fixture = fixture,
            relativeDir = "Album One",
            title = title,
            artist = "An Artist",
            album = "Album One",
            tags = koin(),
            fileName = fileName,
        )
        runBlocking {
            koin<AudioTagRepository>().overwrite(
                path.toString(),
                AudioTagMutation(
                    mode = AudioTagMutationMode.Patch,
                    fields = mapOf(
                        AudioTagFieldKey.TrackNumber to FieldMutation.Set(trackNumber.toString())
                    ),
                ),
            )
        }
        return path
    }

    /**
     * Stores the temp library as a root, runs the app's own scan, and waits until the album is indexed.
     *
     * `addFolderAndScan` is what the library pages call: it records the root and scans on the app's own
     * `Dispatchers.Default` scope, so the scan really is in flight while this waits. The wait polls the
     * same observable the page subscribes to, which is the only thing available without a frame pump --
     * and the id has to be known before `setContent`, since the scene cannot be composed twice.
     */
    private fun scanAndWaitForAlbumId(): Long {
        koin<LibraryScanManager>().addFolderAndScan(musicDir.toRealPath().toString())
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val albums = albumsInLibrary()
            if (albums.isNotEmpty()) return albums.single().id
            Thread.sleep(20)
        }
        error("the album index was never built for ${musicDir.toRealPath()}")
    }

    private fun albumsInLibrary() = runBlocking { index().observeAlbums().first() }

    private fun songsOfAlbum(albumId: Long): List<SongEntity> =
        runBlocking { index().getSongsByAlbumId(albumId) }

    /**
     * The expected text for a string resource, formatted the way the page formats it.
     *
     * [String.format] rather than `getString(res, *args)`: the library's vararg overload ignores plain
     * `%d`/`%s` placeholders, so building the expectation with it would make the assertion agree with a
     * page that renders the placeholder instead of the value.
     */
    private fun text(res: StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    private fun SemanticsNodeInteractionsProvider.nodeCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    /** The top edge of the row carrying [text]: a row's own (merged) node is the topmost match. */
    private fun SemanticsNodeInteractionsProvider.topOf(text: String): Float =
        onAllNodesWithText(text).fetchSemanticsNodes()
            .ifEmpty { error("no node on screen carries \"$text\"") }
            .minOf { it.boundsInRoot.top }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.exists(text: String): Boolean =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.countOfContentDescription(description: String): Int =
        onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    /**
     * Performs a click that is allowed to reach `NavController.navigate`.
     *
     * `SkikoComposeUiTest` dispatches injected pointer events on the calling thread, while a lifecycle
     * move is guarded by a main-thread check, so a navigating click has to be dispatched the way a real
     * one is. `BatchTaskListScreenTest` documents the same trap from the same failure.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.clickAndNavigate(matcher: SemanticsMatcher) {
        runOnUiThread { onNode(matcher).performClick() }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `the album page shows its header and its songs in the album's own order`() = runComposeUiTest {
        // The filenames deliberately run the other way round from the track numbers: a page that
        // rendered the rows by path, or in the order the scan happened to find them, would put
        // "Late Track" on top.
        placeTrackedFixture("bladeenc.mp3", title = "Early Track", trackNumber = 1, fileName = "b.mp3")
        placeTrackedFixture("silence-44-s.flac", title = "Late Track", trackNumber = 2, fileName = "a.flac")
        val albumId = scanAndWaitForAlbumId()

        setContent {
            LyricoTheme {
                AlbumDetailScreenInTestHost(albumId = albumId)
            }
        }

        val album = albumsInLibrary().single()
        waitUntil("wait for the album name to be composed", 20_000) { nodeCount(album.name) > 0 }

        // The header: the album's name, the artist tag, and the song count substituted into the
        // placeholder rather than printed raw -- the failure the C3 capture caught on the songs title.
        assertTrue(nodeCount("An Artist") > 0, "the header shows the album's artist tag")
        assertEquals(
            0,
            nodeCount(text(Res.string.unknown_album_artist)),
            "the album carries an artist tag, so the fallback label must not be used",
        )
        waitUntil("wait for the formatted song count", 5_000) {
            nodeCount(text(Res.string.song_count, 2)) > 0
        }

        // And the tracks, each with its own track number, in the album's order rather than the scan's.
        val songs = songsOfAlbum(albumId)
        assertEquals(2, songs.size, "both fixtures belong to the album")
        assertEquals(
            listOf("Early Track", "Late Track"),
            songs.map { it.title },
            "the index hands the page the album's own order",
        )
        songs.forEach { song ->
            waitUntil("wait for the row of ${song.fileName}", 20_000) {
                nodeCount(song.title ?: song.fileName) > 0
            }
        }
        assertTrue(
            topOf("Early Track") < topOf("Late Track"),
            "track 1 belongs above track 2, whatever the files are called",
        )
        assertTrue(nodeCount("1") > 0, "the first row shows the track number off the file")
        assertTrue(nodeCount("2") > 0, "the second row shows its track number too")
        assertEquals(
            0,
            nodeCount("—"),
            "every fixture carries a track number, so no row may fall back to the em dash",
        )
    }

    @Test
    fun `tapping a song asks for the metadata editor on that file`() = runComposeUiTest {
        placeTrackedFixture("bladeenc.mp3", title = "Row Song", trackNumber = 7)
        val albumId = scanAndWaitForAlbumId()
        val navigator = RecordingNavigator()

        setContent {
            LyricoTheme {
                AlbumDetailScreenInTestHost(albumId = albumId, navigator = navigator)
            }
        }

        waitUntil("wait for the row to be composed", 20_000) { nodeCount("Row Song") > 0 }

        clickAndNavigate(hasText("Row Song"))

        // Asserted by identity, because the file's path is the editor's argument: navigating to the right
        // screen with the wrong song would still pass a route-name-only check.
        assertEquals(
            listOf<NavDirection>(EditMetadataDestination(songFileUri = songsOfAlbum(albumId).single().uri)),
            navigator.directions.toList(),
        )
    }

    @Test
    fun `a long press enters selection mode and closing it restores the album top bar`() = runComposeUiTest {
        placeTrackedFixture("bladeenc.mp3", title = "Row Song", trackNumber = 1)
        val albumId = scanAndWaitForAlbumId()

        setContent {
            LyricoTheme {
                AlbumDetailScreenInTestHost(albumId = albumId)
            }
        }

        waitUntil("wait for the row to be composed", 20_000) { nodeCount("Row Song") > 0 }
        assertEquals(
            1,
            countOfContentDescription(text(Res.string.action_back)),
            "the album bar carries the back icon before selection starts",
        )

        // Long press is the Android gesture that starts selection; a plain click opens the editor.
        onNodeWithText("Row Song").performTouchInput { longClick() }

        // `Selected %d items` with a count of 1: the selection bar replacing the album bar is also a
        // check on the `AnimatedContent` swap between the two top bars.
        waitUntil("wait for the selection count to appear", 5_000) {
            exists(text(Res.string.selection_mode_selected_count, 1))
        }
        assertEquals(
            0,
            countOfContentDescription(text(Res.string.action_back)),
            "the selection bar is not the album bar, so the back icon has to be gone",
        )
        // Selection mode comes with the batch FAB hub. That hub's own contract (its twelve rows) belongs
        // to `SongBatchSelectionActionsTest`; here it only has to be the one the page mounts.
        assertEquals(1, countOfContentDescription("Actions"), "selection mode offers the batch actions")

        onNodeWithText(text(Res.string.action_close)).performClick()

        waitUntil("wait for the album bar to come back", 5_000) {
            !exists(text(Res.string.selection_mode_selected_count, 1))
        }
        waitUntil("wait for the back icon to come back", 5_000) {
            countOfContentDescription(text(Res.string.action_back)) == 1
        }
        assertEquals(0, countOfContentDescription("Actions"), "leaving selection mode takes the FAB away")
        assertTrue(nodeCount("Row Song") > 0, "the list is still the album's list")
    }

    @Test
    fun `the header menu deletes the whole album, files and rows, and asks to leave the page`() =
        runComposeUiTest {
            placeTrackedFixture("bladeenc.mp3", title = "First Song", trackNumber = 1)
            placeTrackedFixture("silence-44-s.flac", title = "Second Song", trackNumber = 2)
            val albumId = scanAndWaitForAlbumId()
            val navigator = RecordingNavigator()

            setContent {
                LyricoTheme {
                    AlbumDetailScreenInTestHost(albumId = albumId, navigator = navigator)
                }
            }

            val album = albumsInLibrary().single()
            val doomed = songsOfAlbum(albumId)
            assertEquals(2, doomed.size, "the album has to hold both fixtures")
            assertTrue(
                doomed.all { Files.exists(Path.of(it.uri)) },
                "the scan writes paths that exist, so the delete has something to remove",
            )
            waitUntil("wait for the album name to be composed", 20_000) { nodeCount(album.name) > 0 }

            onNodeWithContentDescription(text(Res.string.album_action_more)).performClick()

            // The three rows Android's overflow menu carried. The replay-gain row is asserted to be
            // there rather than clicked: measuring an album is covered by `AlbumsPageTest` and
            // `AlbumReplayGainViewModelTest`, and it would run ffmpeg over the fixtures again here.
            waitUntil("wait for the header menu to open", 5_000) {
                nodeCount(text(Res.string.menu_action_calculate_album_replay_gain)) > 0
            }
            assertTrue(
                nodeCount(text(Res.string.menu_action_share_album)) > 0,
                "the menu offers the album share, which the desktop reveals in Explorer",
            )
            onNodeWithText(text(Res.string.menu_action_delete_album)).performClick()

            // Confirmation is a separate dialog, and it names the album and the number of songs going
            // with it.
            waitUntil("wait for the delete confirmation", 5_000) {
                nodeCount(text(Res.string.dialog_delete_album_content, doomed.size, album.name)) > 0
            }
            onNodeWithText(text(Res.string.confirm)).performClick()

            // The files first: they are the part no fake could stand in for.
            waitUntil("wait for the album's rows to leave the index", 20_000) {
                songsOfAlbum(albumId).isEmpty()
            }
            doomed.forEach { song ->
                assertFalse(
                    Files.exists(Path.of(song.uri)),
                    "${song.fileName} has to be gone from disk, not only from the index",
                )
            }

            // Then the report Android showed in a `Toast` and the port shows in the snackbar, and the pop
            // back to the grid the deleted card belongs to.
            waitUntil("wait for the result snackbar", 5_000) {
                nodeCount(text(Res.string.album_delete_success, doomed.size, doomed.size)) > 0
            }
            assertEquals(1, navigator.popCount, "the page leaves itself once the album is gone")
        }

    @Test
    fun `the shipped host walks from the albums tab into the tapped album's detail`() = runComposeUiTest {
        placeTrackedFixture("bladeenc.mp3", title = "Row Song", trackNumber = 1)
        val albumId = scanAndWaitForAlbumId()
        val album = albumsInLibrary().single()
        assertEquals(albumId, album.id)

        setContent {
            LyricoTheme {
                // No recording navigator and no hand-built entry: this is the shipped graph, so the
                // `album_detail/{albumId}` registration, the argument's round-trip through the route
                // string, and `koinViewModel(parameters = { parametersOf(albumId) })` resolving against
                // the pushed entry's own `ViewModelStoreOwner` are all covered by the walk itself.
                LyricoNavHost()
            }
        }

        // Android reached this page from the albums grid, and the grid is ported, so the walk starts
        // where a user would: the albums tab of the shipped shell.
        onNodeWithText(text(Res.string.library_tab_albums)).performClick()
        waitUntil("wait for the album card to be composed", 20_000) { nodeCount(album.name) > 0 }

        clickAndNavigate(hasText(album.name))

        // The page's rows can only be right if the id survived the route: the grid shows no song titles
        // at all, and an id that failed to decode would leave the page empty.
        waitUntil("wait for the album detail page", 5_000) { exists("Row Song") }
        waitUntil("wait for the formatted song count", 5_000) {
            exists(text(Res.string.song_count, 1))
        }
        assertEquals(
            1,
            countOfContentDescription(text(Res.string.action_back)),
            "the detail bar carries the back icon",
        )
        assertEquals(
            0,
            countOfContentDescription(text(Res.string.cd_sort)),
            "the albums grid is off the tree once the detail page is showing",
        )
        assertEquals(
            0,
            nodeCount(text(Res.string.album_detail_title)),
            "the album has a name, so the page titles itself with it rather than the fallback",
        )

        // Walk back out through the same graph: that covers the other half of the pair, and it leaves the
        // back stack settled so the detail entry is gone before the harness tears the scene down.
        clickAndNavigate(hasContentDescription(text(Res.string.action_back)))
        waitUntil("wait for the albums grid to come back", 5_000) {
            countOfContentDescription(text(Res.string.cd_sort)) == 1
        }
        assertEquals(
            0,
            countOfContentDescription(text(Res.string.action_back)),
            "the grid has no back icon",
        )
        assertTrue(nodeCount(album.name) > 0, "the album card is back")
    }
}
