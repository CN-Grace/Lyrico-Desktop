package com.lonx.lyrico.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.entity.path
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.platform.DirectoryPicker
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_add_folder
import com.lonx.lyrico.resources.folder_tab_songs
import com.lonx.lyrico.support.HeadlessMainDispatcher
import com.lonx.lyrico.ui.navigation.FolderManagerDestination
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.theme.LyricoTheme
import com.lonx.lyrico.utils.LibraryScanManager
import com.lonx.lyrico.viewmodel.SortBy
import com.lonx.lyrico.viewmodel.SortOrder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The folder manager on a real database, a real scan and a fake directory picker.
 *
 * Two properties are worth the cost of mounting the page in a real `NavHost` (which is what makes
 * `koinViewModel()` resolve against this route, exactly as in the shipped window):
 *
 * * **adding a folder really scans it.** Android's path went through SAF -- the picker returned a tree
 *   uri, the screen asked `UriUtils` for a path and handed both to the scanner. Desktop has neither
 *   half, so the assertions are on what the port put in their place: the picked directory is walked,
 *   read with TagLib, written to Room, and the row then appears in the list.
 * * **a folder's songs come from the database**: the test seeds one song through the real scanner and
 *   then only clicks the folder row, so nothing about the click can supply the song.
 *
 * [HeadlessMainDispatcher] is installed for the reason `AlbumDetailScreenTest` records: this page starts
 * `koinViewModel()` the moment it composes, and lifecycle 2.11 requires that on the main thread.
 */
@OptIn(ExperimentalTestApi::class)
class FolderManagerScreenTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-folder-manager-screen").toFile()
    private val librariesDir: File = Files.createTempDirectory("lyrico-folder-manager-libs").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()
    private val mainDispatcher = HeadlessMainDispatcher()

    private class RecordingNavigator : Navigator {
        val directions = mutableListOf<NavDirection>()

        override fun navigate(direction: NavDirection) {
            directions += direction
        }

        override fun popBackStack(): Boolean = true

        override fun navigateUp(): Boolean = true
    }

    private class FakeDirectoryPicker(private val directory: File?) : DirectoryPicker {
        override suspend fun pick(): File? = directory
    }

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        mainDispatcher.install()
        startKoin { modules(desktopAppModule(directories)) }
        // Every TagLib fixture is shorter than a minute and the app's own default is to ignore short
        // audio, so a scan would correctly find nothing -- and every assertion below would then pass or
        // fail for the wrong reason. This is the preference a user with short files turns off.
        runBlocking { settings().saveIgnoreShortAudio(false) }
    }

    @AfterTest
    fun tearDown() {
        // `stopKoin()` does not cancel the app scope (it is just a single), so a scan this test started
        // would keep walking its temp directory after the test ends -- the directory is deleted below, and
        // the leftover work shows up as another class's timeout. Cancel it before closing the graph.
        runCatching { GlobalContext.get().get<CoroutineScope>().cancel() }
        runCatching { stopKoin() }
        mainDispatcher.restore()
        workingDir.deleteRecursively()
        librariesDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ harness

    private fun songs(): SongLibraryRepository = GlobalContext.get().get()
    private fun settings(): SettingsRepository = GlobalContext.get().get()
    private fun scanner(): LibraryScanManager = GlobalContext.get().get()

    /** Copies a real audio file out of TagLib's vendored fixtures into a folder that will be scanned. */
    private fun placeFixture(fixture: String, relativeDir: String): Path {
        val source = Path.of(System.getProperty("lyrico.audiotag.fixtures.dir"), fixture)
        assertTrue(Files.isRegularFile(source), "missing audio fixture $source")
        val targetDir = librariesDir.toPath().resolve(relativeDir)
        Files.createDirectories(targetDir)
        return Files.copy(source, targetDir.resolve(fixture), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun text(res: StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    @Composable
    private fun ScreenInTestHost(directoryPicker: DirectoryPicker) {
        val controller = rememberNavController()
        NavHost(navController = controller, startDestination = FolderManagerDestination.ROUTE) {
            composable(route = FolderManagerDestination.ROUTE) {
                FolderManagerScreen(
                    navigator = RecordingNavigator(),
                    directoryPicker = directoryPicker,
                )
            }
        }
    }

    private fun ComposeUiTest.exists(label: String): Boolean =
        onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    /** The page's rows are folder *names*; every seed below is a single-segment path. */
    private suspend fun folderNames(): List<String> =
        GlobalContext.get().get<LyricoDatabase>().folderDao().getAllFoldersOnce()
            .map { File(it.path).name }

    private suspend fun storedSongs(): List<SongEntity> =
        songs().observeSongs(SortBy.TITLE, SortOrder.ASC, null).first()

    // ------------------------------------------------------------------ tests

    @Test
    fun `picking a folder scans it and shows the folder row`() = runComposeUiTest {
        val folder = File(librariesDir, "Picked")
        assertTrue(folder.mkdirs(), "could not create the fixture folder")

        setContent {
            LyricoTheme { ScreenInTestHost(FakeDirectoryPicker(folder)) }
        }

        onNodeWithContentDescription(text(Res.string.action_add_folder)).performClick()

        // Real scan: directory walk, TagLib, Room, then the observable folder query.
        waitUntil("wait for the picked folder to reach the database", 20_000) {
            runBlocking { folderNames() }.contains("Picked")
        }
        waitUntil("wait for the picked folder to be composed", 20_000) { exists("Picked") }
    }

    @Test
    fun `a cancelled pick adds nothing`() = runComposeUiTest {
        setContent {
            LyricoTheme { ScreenInTestHost(FakeDirectoryPicker(null)) }
        }

        onNodeWithContentDescription(text(Res.string.action_add_folder)).performClick()
        waitForIdle()

        assertEquals(emptyList(), runBlocking { folderNames() }, "a cancelled picker inserted a folder")
    }

    @Test
    fun `an existing folder is listed without going through the picker`() = runComposeUiTest {
        // A root added outside this screen, which is what a library from a previous session looks like.
        val seeded = File(librariesDir, "Existing")
        assertTrue(seeded.mkdirs(), "could not create the fixture folder")
        runBlocking { scanner().addFolderAndScan(seeded.absolutePath) }
        waitUntil("seed the folder through the scanner", 20_000) {
            runBlocking { folderNames() }.contains("Existing")
        }

        setContent {
            LyricoTheme { ScreenInTestHost(FakeDirectoryPicker(null)) }
        }

        waitUntil("the existing folder has to be listed", 20_000) { exists("Existing") }
    }

    @Test
    fun `clicking a folder lists the songs inside it`() = runComposeUiTest {
        val folder = File(librariesDir, "Album One")
        val file = placeFixture("bladeenc.mp3", "Album One")
        runBlocking { scanner().addFolderAndScan(folder.absolutePath) }
        waitUntil("the seeded folder has to be scanned first", 20_000) {
            runBlocking { storedSongs() }.isNotEmpty()
        }

        setContent {
            LyricoTheme { ScreenInTestHost(FakeDirectoryPicker(null)) }
        }

        // The row is the folder's name; clicking it selects that folder, which filters the song list.
        val stored = runBlocking { storedSongs() }.single()
        val label = stored.title?.takeIf { it.isNotBlank() } ?: stored.fileName

        // Drill into the folder, then switch to the songs tab: the two tabs are the page's own split,
        // and a song row is only composed on the second one. The tab label carries a count, so it is
        // matched by substring rather than by an exact string.
        onNodeWithText("Album One").performClick()
        // Exact label, not a substring: the browser's own count line ("0 folders, 1 song") contains the
        // same word and would make the matcher ambiguous.
        onNodeWithText("${text(Res.string.folder_tab_songs)} (1)").performClick()
        waitUntil("wait for the folder's songs to be composed", 20_000) { exists(label) }

        assertEquals(
            file.fileName.toString(),
            stored.path.fileName.toString(),
            "the song shown for the folder is not the file that was scanned",
        )
    }
}
