package com.lonx.lyrico.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepositoryImpl
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.support.HeadlessMainDispatcher
import com.lonx.lyrico.ui.navigation.LocalSearchDestination
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.theme.LyricoTheme
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The in-library search page on the real graph and a real database.
 *
 * The page's own port decision is the one this test is aimed at: Android read the query from a `TextField`
 * state and searched *the songs it already had in memory*; the desktop port keeps that, but the field tabs
 * come from `LocalSearchField`, whose label used to be an Android string id and is now a
 * `StringResource` -- the extraction that a `@StringRes Int` could not survive. So the test types a query
 * and asserts both that the matching song shows up and that a non-matching one does not.
 *
 * [HeadlessMainDispatcher] is installed for the reason `AlbumDetailScreenTest` records: the page starts
 * `koinViewModel()` on composition, and lifecycle 2.11 requires that on the main thread.
 */
@OptIn(ExperimentalTestApi::class)
class LocalSearchScreenTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-local-search-screen").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()
    private val mainDispatcher = HeadlessMainDispatcher()

    private lateinit var database: LyricoDatabase
    private lateinit var songs: SongLibraryRepository

    private class RecordingNavigator : Navigator {
        val directions = mutableListOf<NavDirection>()

        override fun navigate(direction: NavDirection) {
            directions += direction
        }

        override fun popBackStack(): Boolean = true

        override fun navigateUp(): Boolean = true
    }

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        mainDispatcher.install()
        startKoin { modules(desktopAppModule(directories)) }
        // Seed through the graph's own database: a separate `TestLibrary` would open a second database file
        // and the page (which reads the graph's) would correctly show nothing.
        database = GlobalContext.get().get()
        songs = SongLibraryRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        runCatching { GlobalContext.get().get<CoroutineScope>().cancel() }
        runCatching { stopKoin() }
        mainDispatcher.restore()
        workingDir.deleteRecursively()
    }

    @Composable
    private fun ScreenInTestHost() {
        val controller = rememberNavController()
        NavHost(navController = controller, startDestination = LocalSearchDestination.ROUTE) {
            composable(route = LocalSearchDestination.ROUTE) {
                LocalSearchScreen(navigator = RecordingNavigator())
            }
        }
    }

    private fun ComposeUiTest.exists(label: String): Boolean =
        onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    private fun seed(vararg titles: String) = runBlocking {
        val folderId = database.folderDao().insert(FolderEntity(path = """H:\Music"""))
        songs.upsertSongs(
            titles.mapIndexed { index, title ->
                SongEntity(
                    folderId = folderId,
                    mediaId = 0,
                    source = SongSource.LOCAL,
                    filePath = """H:\Music\$title.mp3""",
                    fileName = "$title.mp3",
                    fileSize = 1_000L,
                    fileLastModified = 1_700_000_000_000L,
                    title = title,
                    artist = "Artist",
                    album = "Album",
                    durationMilliseconds = 180_000,
                    uri = """H:\Music\$title.mp3""",
                )
            },
        )
    }

    @Test
    fun `typing a query shows the matching song and hides the others`() = runComposeUiTest {
        seed("Alpha", "Beta")

        setContent { LyricoTheme { ScreenInTestHost() } }

        onAllNodes(hasSetTextAction())[0].performTextInput("Alpha")

        waitUntil("the matching song has to be listed", 20_000) { exists("Alpha") }
        assertEquals(
            false,
            exists("Beta"),
            "a song that does not match the query is listed anyway",
        )
    }

    @Test
    fun `the page renders before anything is typed`() = runComposeUiTest {
        seed("Alpha")

        setContent { LyricoTheme { ScreenInTestHost() } }
        waitForIdle()

        // What matters here is that the page composes with a real library and a real graph; which songs an
        // empty query shows is the page's own choice and is asserted by the view-model test.
        assertEquals(true, onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty())
    }
}
