package com.lonx.lyrico.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.model.log.LogRetentionOption
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.platform.FileSavePicker
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.action_delete
import com.lonx.lyrico.resources.action_export_logs
import com.lonx.lyrico.resources.app_log_empty
import com.lonx.lyrico.resources.app_log_title
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.ui.navigation.AppLogsDestination
import com.lonx.lyrico.ui.navigation.LyricoNavHost
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.theme.LyricoTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The first real screen of the desktop port, rendered with real data.
 *
 * This is the runtime proof the UI track needs, and it deliberately uses the *shipped* path wherever
 * possible: the screen is composed through `LyricoNavHost()` (the real route registration), its view
 * model comes from the real Koin graph, and its rows come from a real Room database on disk. Only the
 * two OS-owned seams are replaced:
 *
 * * `fileSavePicker` -- the native save dialog. Everything after the pick (which ids, the bytes
 *   written, the snackbar) stays real code under test.
 * * `navigator` -- `popBackStack` is recorded instead of performed, which is the only way to observe it
 *   without a second screen to land on.
 *
 * The point is not that a composable can be composed. It is that the desktop replacements for
 * Android's platform services -- Koin resolving against a `NavBackStackEntry`, Room on a real file,
 * compose resources, a permissions-free save flow -- work together in one frame.
 */
class AppLogScreenTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-applog-screen").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()

    private lateinit var logRepository: AppLogRepository
    private lateinit var settings: SettingsRepository

    /** Records navigation instead of performing it; this screen's only navigation is "go back". */
    private class RecordingNavigator : Navigator {
        val popCount = AtomicInteger()

        override fun navigate(direction: NavDirection): Unit =
            error("the app log screen does not navigate forward")

        override fun popBackStack(): Boolean {
            popCount.incrementAndGet()
            return true
        }

        override fun navigateUp(): Boolean = popBackStack()
    }

    /** Records what the dialog was pre-filled with, and which file the screen asked to write. */
    private class RecordingFileSavePicker(private val target: File?) : FileSavePicker {
        var suggestedName: String? = null

        override suspend fun pick(defaultFileName: String): File? {
            suggestedName = defaultFileName
            return target
        }
    }

    /** Koin is process-global and other tests start the same graph. */
    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        logRepository = GlobalContext.get().get()
        settings = GlobalContext.get().get()
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        // Best effort: Room's pool and the preference stores may still hold the files open.
        workingDir.deleteRecursively()
    }

    /**
     * The screen inside a real `NavHost` entry, so `koinViewModel()` resolves against a
     * `ViewModelStoreOwner` exactly as it does in the shipped host. Only the explicitly passed seams
     * differ from production.
     */
    @Composable
    private fun AppLogScreenInTestHost(
        navigator: Navigator = RecordingNavigator(),
        fileSavePicker: FileSavePicker? = null,
    ) {
        val controller = rememberNavController()
        NavHost(navController = controller, startDestination = "app_logs") {
            composable("app_logs") {
                AppLogScreen(navigator = navigator, fileSavePicker = fileSavePicker)
            }
        }
    }

    /** The retention gate is checked before writing, so seeding has to open it first. */
    private fun seedLog(tag: String, message: String) = runBlocking<Unit> {
        settings.saveLogRetentionOption(LogRetentionOption.FOREVER)
        logRepository.log(AppLogLevel.INFO, AppLogType.APP, tag, message)
    }

    private fun text(res: org.jetbrains.compose.resources.StringResource): String =
        runBlocking { getString(res) }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the shipped navigation host renders the app log screen with real rows`() = runComposeUiTest {
        seedLog(tag = "ScanService", message = "索引完成：128 首")
        seedLog(tag = "TagWriter", message = "写入标签失败")

        setContent {
            LyricoTheme {
                // The real host and the real route: this asserts the desktop boot path
                // (Koin -> NavHost -> screen -> view model), not just the screen in isolation.
                // The host's *default* start route is the songs page (`SongsPageTest` asserts that);
                // Android reached this route from settings, which is not ported yet, so the test starts
                // the shipped host here explicitly to cover the `app_logs` registration.
                LyricoNavHost(startDestination = AppLogsDestination())
            }
        }

        // Room emits asynchronously, so wait for the row rather than asserting into a race.
        waitUntil("wait for the seeded log row to arrive from Room", 5_000) { onAllNodesWithText("索引完成：128 首").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText(text(Res.string.app_log_title)).assertExists()
        onNodeWithText("TagWriter").assertExists()
        onNodeWithText("写入标签失败").assertExists()
        assertTrue(
            onAllNodesWithText(text(Res.string.app_log_empty)).fetchSemanticsNodes().isEmpty(),
            "the empty-state card must not be shown while rows exist",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `export writes the visible logs to the file the user picked`() = runComposeUiTest {
        seedLog(tag = "Exporter", message = "exported row")
        val target = File(workingDir, "exported.log")
        val picker = RecordingFileSavePicker(target)

        setContent {
            LyricoTheme {
                AppLogScreenInTestHost(fileSavePicker = picker)
            }
        }

        waitUntil("wait for the seeded log row to arrive from Room", 5_000) { onAllNodesWithText("exported row").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription(text(Res.string.action_export_logs)).performClick()

        // The write happens on Dispatchers.IO, hence the wait.
        waitUntil("wait for the export coroutine to write the file", 5_000) { target.exists() && target.length() > 0 }
        val suggested = assertNotNull(picker.suggestedName, "the save dialog was never opened")
        assertTrue(
            suggested.startsWith("lyrico_log_") && suggested.endsWith(".log"),
            "unexpected suggested file name: $suggested",
        )
        val written = target.readText()
        assertTrue(written.contains("exported row"), "the export must contain the row:\n$written")
        assertTrue(
            written.contains("Lyrico"),
            "the export keeps Android's diagnostic header (app name/version), got:\n$written",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `cancelling the save dialog writes nothing and keeps the rows`() = runComposeUiTest {
        seedLog(tag = "Exporter", message = "still here")
        val picker = RecordingFileSavePicker(target = null)

        setContent {
            LyricoTheme {
                AppLogScreenInTestHost(fileSavePicker = picker)
            }
        }

        waitUntil("wait for the seeded log row to arrive from Room", 5_000) { onAllNodesWithText("still here").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription(text(Res.string.action_export_logs)).performClick()

        // A cancelled dialog must not delete rows or write a partial file; the screen just returns.
        // The picker is invoked from a coroutine launched by the click, so wait for that hand-off
        // instead of racing it.
        waitUntil("wait for the save dialog to be opened", 5_000) { picker.suggestedName != null }
        onNodeWithText("still here").assertExists()
        val writtenFiles = workingDir.listFiles { file -> file.name.endsWith(".log") }
        assertTrue(
            writtenFiles.isNullOrEmpty(),
            "a cancelled save must not create a file: ${writtenFiles?.map { it.name }}",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `confirming the delete dialog removes the rows from the database`() = runComposeUiTest {
        seedLog(tag = "Doomed", message = "delete me")

        setContent {
            LyricoTheme {
                AppLogScreenInTestHost()
            }
        }

        waitUntil("wait for the seeded log row to arrive from Room", 5_000) { onAllNodesWithText("delete me").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription(text(Res.string.action_delete)).performClick()
        // The confirmation is a separate Miuix window; its confirm button is what actually deletes.
        waitUntil("wait for the confirmation dialog to appear", 5_000) { onAllNodesWithText(text(Res.string.confirm)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(text(Res.string.confirm)).performClick()

        // Gone from the list *and* from the database -- the second half is what separates a working
        // delete from a list that merely stopped showing the row.
        waitUntil("wait for the deleted row to disappear", 5_000) { onAllNodesWithText("delete me").fetchSemanticsNodes().isEmpty() }
        val remaining = runBlocking { logRepository.getLatest() }.map { it.message }
        assertTrue(remaining.none { it == "delete me" }, "the row was not deleted, remaining=$remaining")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the back icon asks the navigator to pop`() = runComposeUiTest {
        seedLog(tag = "Any", message = "irrelevant")
        val navigator = RecordingNavigator()

        setContent {
            LyricoTheme {
                AppLogScreenInTestHost(navigator = navigator)
            }
        }

        // The top bar back icon exists before any row arrives, so no waiting is needed here.
        onNodeWithContentDescription(text(Res.string.action_back)).performClick()
        assertEquals(1, navigator.popCount.get())
    }
}
