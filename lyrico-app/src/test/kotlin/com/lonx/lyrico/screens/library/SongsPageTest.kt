package com.lonx.lyrico.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
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
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.utils.SongQueryBuilder
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.platform.DirectoryPicker
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.song_list_title
import com.lonx.lyrico.resources.action_add_folder
import com.lonx.lyrico.resources.action_close
import com.lonx.lyrico.resources.cd_search
import com.lonx.lyrico.resources.cd_sort
import com.lonx.lyrico.resources.empty_songs_title
import com.lonx.lyrico.resources.label_artists
import com.lonx.lyrico.resources.selection_mode_selected_count
import com.lonx.lyrico.resources.song_list_empty_title
import com.lonx.lyrico.support.PlatformLogCapture
import com.lonx.lyrico.ui.navigation.LocalSearchDestination
import com.lonx.lyrico.ui.navigation.LyricoNavHost
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.navigation.rememberNavigator
import com.lonx.lyrico.ui.theme.LyricoTheme
import com.lonx.lyrico.viewmodel.SortBy
import com.lonx.lyrico.viewmodel.SortInfo
import com.lonx.lyrico.viewmodel.SortOrder
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
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The songs page, rendered from real scans of real audio files.
 *
 * This is the port's first library screen and, until the three-tab shell lands, the app's entry route.
 * The test drives the whole desktop stack the way a user would: pick a folder (the one OS seam that is
 * replaced), walk it, read the audio tags with TagLib, write rows into a real Room database on disk,
 * and render whatever comes back out of the observable query -- then long-press a row to enter
 * selection mode, and press a song to exercise the navigation that has no screen behind it yet.
 *
 * What is *not* exercised here is equally deliberate. Every Android-only mechanism this screen used is
 * gone rather than shimmed -- the SAF permission dance, the `Intent.ACTION_SEND` chooser, the
 * `Intent`-based player picker, `UriUtils`, `LocalContext`, `koinActivityViewModel()` and
 * `my.nanihadesuka.compose.InternalLazyColumnScrollbar` -- and each replacement states what it dropped
 * (see `DirectoryPicker`, `LibraryScrollbar`, `Destinations.kt`, and `SongsPage`'s own KDoc).
 */
@OptIn(ExperimentalTestApi::class)
class SongsPageTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-songs-page").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-songs-page-library")

    private lateinit var database: LyricoDatabase

    /**
     * The native folder dialog, replaced by a value this test controls.
     *
     * Everything downstream of the pick stays real: the path goes to
     * `SongListViewModel.addFolderAndRefresh`, which inserts the folder row and scans the directory
     * with the real scanner.
     */
    private class FakeDirectoryPicker(private val directory: File?) : DirectoryPicker {
        var pickCount = 0

        override suspend fun pick(): File? {
            pickCount++
            return directory
        }
    }

    /** Records the routes a screen asks for, for the destinations whose screens are not ported yet. */
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
        database = GlobalContext.get().get()
        // Every audio fixture TagLib ships is shorter than a minute, and the app's own default is to
        // ignore short audio, so a scan would (correctly) drop them. This is the setting a user with
        // short files turns off, saved through the real settings store.
        runBlocking { settings().saveIgnoreShortAudio(false) }
    }

    private fun settings(): SettingsRepository = GlobalContext.get().get()

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        // Best effort: Room's connection pool may still hold the database file open.
        workingDir.deleteRecursively()
        musicDir.toFile().deleteRecursively()
    }

    /**
     * The page inside a real `NavHost` entry.
     *
     * The entry matters: `koinViewModel()` resolves against the `NavBackStackEntry`, which is how the
     * shipped host builds these view models, so composing the page bare would test a different setup.
     * `navigatorOverride` is used where a test wants to observe a route instead of performing it; left
     * null, the page runs against the real `NavControllerNavigator`, which is the production path.
     */
    @Composable
    private fun SongsPageInTestHost(
        directoryPicker: DirectoryPicker,
        navigatorOverride: Navigator? = null,
    ) {
        val controller = rememberNavController()
        val navigator = rememberNavigator(controller)
        NavHost(navController = controller, startDestination = "songs") {
            composable("songs") {
                SongsPage(
                    navigator = navigatorOverride ?: navigator,
                    directoryPicker = directoryPicker,
                )
            }
        }
    }

    /** Copies a real audio file out of TagLib's vendored test fixtures into the temp library. */
    private fun placeFixture(fixture: String, relativeDir: String = "."): Path {
        val source = Path.of(System.getProperty("lyrico.audiotag.fixtures.dir"), fixture)
        assertTrue(Files.isRegularFile(source), "missing audio fixture $source")
        val targetDir = musicDir.resolve(relativeDir)
        Files.createDirectories(targetDir)
        val target = targetDir.resolve(fixture)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /**
     * The expected text for a string resource, formatted the way the screen formats it.
     *
     * [String.format] rather than `getString(res, *args)`: the library's vararg overload ignores
     * plain `%d`/`%s` placeholders, so building the expectation with it would make every assertion
     * below agree with a screen that renders the placeholder instead of the value.
     */
    private fun text(res: StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    private suspend fun songsInLibrary(): List<SongEntity> =
        database.songDao()
            .getSongs(SongQueryBuilder.build(SortInfo(SortBy.TITLE, SortOrder.ASC)))
            .first()

    /**
     * Whether the scan has reached the database yet.
     *
     * The scan runs on the app's own coroutine scope, so there is nothing to await by handle. This is
     * polled from inside `waitUntil` on purpose: the folder pick is launched from the composition's own
     * scope, so the test thread has to keep pumping the composition for that coroutine to run at all.
     * Blocking on the database outside the frame pump would wait for work that never gets scheduled
     * (which is exactly how this test failed the first time).
     */
    private fun scanReachedDatabase(): Boolean = runBlocking { songsInLibrary() }.isNotEmpty()

    /** The labels the row shows, read back from the database rather than assumed from the fixture. */
    private fun rowTitle(song: SongEntity): String =
        song.title?.takeIf(String::isNotBlank) ?: song.fileName

    @Test
    fun `the shipped host starts on the songs page and shows the add-folder empty state`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                // The real host, so the temporary start route is asserted rather than assumed: this
                // guard fails if the songs page stops being reachable from the app's own boot path.
                LyricoNavHost()
            }
        }

        onNodeWithText(text(Res.string.song_list_empty_title)).assertExists()
        onNodeWithText(text(Res.string.action_add_folder)).assertExists()
        assertTrue(
            onAllNodesWithText(text(Res.string.empty_songs_title)).fetchSemanticsNodes().isEmpty(),
            "the refresh prompt is for a library that has a root but no songs, not for a fresh install",
        )
    }

    @Test
    fun `picking a folder scans it and renders the songs that were found`() = runComposeUiTest {
        val fixture = placeFixture("bladeenc.mp3", "Album One")
        val picker = FakeDirectoryPicker(musicDir.toFile())

        setContent {
            LyricoTheme {
                SongsPageInTestHost(directoryPicker = picker)
            }
        }

        onNodeWithText(text(Res.string.action_add_folder)).performClick()

        // Real scan: directory walk, TagLib read, Room write, then the observable query.
        waitUntil("wait for the scan to reach the database", 20_000) { scanReachedDatabase() }
        val songs = runBlocking { songsInLibrary() }
        assertEquals(1, songs.size, "the fixture directory holds exactly one audio file")
        val title = rowTitle(songs.single())

        // Then the last hop, Flow -> view model -> LazyColumn, which needs a frame to be composed.
        waitUntil("wait for the scanned row to be composed", 20_000) {
            onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, picker.pickCount, "the folder dialog is opened once per press")
        assertEquals(
            fixture.toRealPath().toString(),
            songs.single().uri,
            "a song's identity is its real absolute path, not a content uri",
        )
        assertTrue(
            onAllNodesWithText(text(Res.string.song_list_empty_title)).fetchSemanticsNodes().isEmpty(),
            "the add-folder empty state is gone once the library has songs",
        )
        // The title bar counts the songs. A real screenshot of the running window caught this showing
        // the literal "歌曲（%d）", which is what the vararg formatting in `formattedStringResource`
        // exists to prevent, so the count is asserted as text rather than left to the eye.
        onNodeWithText(text(Res.string.song_list_title, songs.size)).assertExists()
    }

    @Test
    fun `a long press enters selection mode and closing it leaves the default top bar`() = runComposeUiTest {
        placeFixture("bladeenc.mp3", "Album One")

        setContent {
            LyricoTheme {
                SongsPageInTestHost(directoryPicker = FakeDirectoryPicker(musicDir.toFile()))
            }
        }

        onNodeWithText(text(Res.string.action_add_folder)).performClick()
        waitUntil("wait for the scan to reach the database", 20_000) { scanReachedDatabase() }
        val title = rowTitle(runBlocking { songsInLibrary() }.single())
        waitUntil("wait for the scanned row to be composed", 20_000) {
            onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }

        // Long press is the Android gesture that starts selection; a plain click opens the song instead.
        onNodeWithText(title).performTouchInput { longClick() }

        // `Selected %d items` with a count of 1: the selection bar replacing the title bar is also a
        // check on the AnimatedContent swap between the two top bars.
        waitUntil("wait for the selection count to appear", 5_000) {
            onAllNodesWithText(text(Res.string.selection_mode_selected_count, 1))
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(text(Res.string.action_close)).performClick()
        waitUntil("wait for the default title bar to come back", 5_000) {
            onAllNodesWithText(text(Res.string.selection_mode_selected_count, 1))
                .fetchSemanticsNodes().isEmpty()
        }
        onNodeWithContentDescription(text(Res.string.cd_sort)).assertExists()
    }

    @Test
    fun `pressing a song logs the metadata route it cannot reach yet and stays on the page`() =
        runComposeUiTest {
            placeFixture("bladeenc.mp3", "Album One")
            val log = PlatformLogCapture().install()

            setContent {
                LyricoTheme {
                    // No navigator override: this is the real `NavControllerNavigator` and a real graph
                    // that does not have the metadata editor in it yet.
                    SongsPageInTestHost(directoryPicker = FakeDirectoryPicker(musicDir.toFile()))
                }
            }

            try {
                onNodeWithText(text(Res.string.action_add_folder)).performClick()
                waitUntil("wait for the scan to reach the database", 20_000) { scanReachedDatabase() }
                val song = runBlocking { songsInLibrary() }.single()
                val title = rowTitle(song)
                waitUntil("wait for the scanned row to be composed", 20_000) {
                    onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
                }

                onNodeWithText(title).performClick()
                waitUntil("wait for the missing route to be reported", 5_000) {
                    log.warnings.any { it.contains("edit_metadata/") }
                }

                // The degradation is logged *and* survivable: the window is still showing the list.
                onNodeWithText(title).assertExists()
                onNodeWithText(text(Res.string.song_list_empty_title)).assertDoesNotExist()
                val warning = log.warnings.single { it.contains("edit_metadata/") }
                assertTrue(
                    warning.contains(song.fileName),
                    "the logged route must carry the encoded song path, got: $warning",
                )
                assertTrue(
                    warning.contains("not ported yet"),
                    "the log line has to say why the navigation was dropped, got: $warning",
                )
            } finally {
                log.restore()
            }
        }

    @Test
    fun `the sort menu lists the sort fields and saves the chosen one`() = runComposeUiTest {
        placeFixture("bladeenc.mp3", "Album One")
        val settings = settings()

        setContent {
            LyricoTheme {
                SongsPageInTestHost(directoryPicker = FakeDirectoryPicker(musicDir.toFile()))
            }
        }

        onNodeWithText(text(Res.string.action_add_folder)).performClick()
        waitUntil("wait for the scan to reach the database", 20_000) { scanReachedDatabase() }

        onNodeWithContentDescription(text(Res.string.cd_sort)).performClick()
        val artistsLabel = text(Res.string.label_artists)
        waitUntil("wait for the sort menu to open", 5_000) {
            onAllNodesWithText(artistsLabel).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(artistsLabel).performClick()

        // The choice goes to the real settings store, which is the same flow the list sorts from.
        waitUntil("wait for the sort choice to be saved", 5_000) {
            runBlocking { settings.sortInfo.first().sortBy } == SortBy.ARTISTS
        }
    }

    @Test
    fun `search opens the declared local search route`() = runComposeUiTest {
        val navigator = RecordingNavigator()

        setContent {
            LyricoTheme {
                SongsPageInTestHost(
                    directoryPicker = FakeDirectoryPicker(null),
                    navigatorOverride = navigator,
                )
            }
        }

        onNodeWithContentDescription(text(Res.string.cd_search)).performClick()
        assertEquals(
            listOf(LocalSearchDestination.route),
            navigator.routes,
            "the page still asks for the search screen by the route it declared",
        )
    }

    @Test
    fun `a library that already has a root shows the refresh prompt instead of the add-folder button`() =
        runComposeUiTest {
            // A root with no audio in it: the state Android showed as "the library is empty".
            runBlocking {
                database.folderDao().insert(
                    FolderEntity(path = musicDir.toRealPath().toString(), addedBySaf = true)
                )
            }

            setContent {
                LyricoTheme {
                    SongsPageInTestHost(directoryPicker = FakeDirectoryPicker(null))
                }
            }

            waitUntil("wait for the has-folders flow to reach the page", 5_000) {
                onAllNodesWithText(text(Res.string.empty_songs_title)).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                onAllNodesWithText(text(Res.string.action_add_folder)).fetchSemanticsNodes().isEmpty(),
                "a library that already has a root must not ask for another one first",
            )
        }
}
