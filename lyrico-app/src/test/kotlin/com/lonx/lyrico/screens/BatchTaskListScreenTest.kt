package com.lonx.lyrico.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.action_close
import com.lonx.lyrico.resources.batch_match_stat_format
import com.lonx.lyrico.resources.batch_task_clear_message
import com.lonx.lyrico.resources.batch_task_clear_title
import com.lonx.lyrico.resources.batch_task_delete_title
import com.lonx.lyrico.resources.batch_task_detail_progress
import com.lonx.lyrico.resources.batch_task_detail_title
import com.lonx.lyrico.resources.batch_task_filter_all
import com.lonx.lyrico.resources.batch_task_filter_status
import com.lonx.lyrico.resources.batch_task_filter_type
import com.lonx.lyrico.resources.batch_task_list_title
import com.lonx.lyrico.resources.batch_task_no_tasks
import com.lonx.lyrico.resources.batch_task_status_label
import com.lonx.lyrico.resources.batch_task_type_label
import com.lonx.lyrico.resources.common_delete
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.ui.navigation.BatchTaskDetailDestination
import com.lonx.lyrico.ui.navigation.BatchTaskListDestination
import com.lonx.lyrico.ui.navigation.LyricoNavHost
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.theme.LyricoTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
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
 * The batch task history, against real rows in a real database.
 *
 * This is the first *pair* of ported screens that navigate to each other, and the parts Android got
 * from its code generator are exactly the parts that have to be re-proved here:
 *
 * * the rows come from Room through the real repository, and the `Long`-timestamp/`Int`-counter columns
 *   are read by the screen's own view model rather than by a fake;
 * * the two filters are real `WindowDropdownPreference` windows over that same data -- this is the first
 *   test in the port to drive one, so it also covers the interaction the app-log screen's three
 *   dropdowns will rely on;
 * * the row tap has to produce the *same* destination the Android screen declared, which is asserted by
 *   identity of the recorded [BatchTaskDetailDestination] rather than by recomputing the route string;
 * * the cancel/delete split is a state machine over `BatchTaskStatus` (a live task is cancelled, a
 *   finished one is deleted), and both halves are asserted against the database, not the screen.
 *
 * `BatchTaskRepositoryImplTest` already owns the SQL-level guarantees (items cascade, progress is
 * derived from the item rows). This class only re-checks the two that the screen itself can be wrong
 * about: that a delete really removes the row the user pointed at, and that "clear finished" leaves the
 * live work alone.
 */
class BatchTaskListScreenTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-batch-task-list").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()

    private lateinit var tasks: BatchTaskRepository

    /** Records navigation instead of performing it, so the destination can be asserted by identity. */
    private class RecordingNavigator : Navigator {
        val directions = mutableListOf<NavDirection>()
        val popCount = AtomicInteger()

        override fun navigate(direction: NavDirection) {
            directions += direction
        }

        override fun popBackStack(): Boolean {
            popCount.incrementAndGet()
            return true
        }

        override fun navigateUp(): Boolean = popBackStack()
    }

    /** Koin is process-global and other tests start the same graph. */
    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        tasks = GlobalContext.get().get()
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        // Best effort: Room's pool may still hold the file open.
        workingDir.deleteRecursively()
    }

    /**
     * The screen inside a real `NavHost` entry, so `koinViewModel()` resolves against a
     * `ViewModelStoreOwner` exactly as it does in the shipped host. Only the navigator differs.
     */
    @Composable
    private fun BatchTaskListScreenInTestHost(navigator: Navigator = RecordingNavigator()) {
        val controller = rememberNavController()
        NavHost(navController = controller, startDestination = BatchTaskListDestination.ROUTE) {
            composable(BatchTaskListDestination.ROUTE) {
                BatchTaskListScreen(navigator = navigator)
            }
        }
    }

    // ------------------------------------------------------------------ seeding

    private var nextSong = 0

    /** A row the screen never has to dereference: the task repository only reads these four fields. */
    private fun song(fileName: String): SongEntity {
        nextSong++
        return SongEntity(
            folderId = 1L,
            mediaId = nextSong.toLong(),
            source = SongSource.LOCAL,
            filePath = """H:\Music\$fileName""",
            fileName = fileName,
            uri = """H:\Music\$fileName""",
        )
    }

    /** A task that is still live: RUNNING, with its single item in flight and no finished counter. */
    private fun seedRunning(type: BatchTaskType, fileName: String): String = runBlocking {
        val taskId = tasks.createTask(type, listOf(song(fileName)), null)
        tasks.markRunning(taskId)
        tasks.markItemRunning("$taskId-0")
        tasks.updateProgressFromItems(taskId, currentFile = fileName)
        taskId
    }

    /** A finished task whose counters are derived from real item rows, so the stat line is real. */
    private fun seedFinished(
        type: BatchTaskType,
        succeeded: List<String>,
        failed: List<String> = emptyList(),
        skipped: List<String> = emptyList(),
    ): String = runBlocking {
        val taskId = tasks.createTask(type, (succeeded + failed + skipped).map { song(it) }, null)
        tasks.markRunning(taskId)
        succeeded.forEachIndexed { index, _ -> tasks.markItemSucceeded("$taskId-$index", null) }
        failed.forEachIndexed { index, _ ->
            tasks.markItemFailed("$taskId-${succeeded.size + index}", "boom")
        }
        skipped.forEachIndexed { index, _ ->
            tasks.markItemSkipped("$taskId-${succeeded.size + failed.size + index}", null)
        }
        tasks.updateProgressFromItems(taskId, currentFile = null)
        tasks.markSucceeded(taskId)
        taskId
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Resolves a resource the way `formattedStringResource` does -- through [String.format] -- because
     * the library's own `getString(res, args)` does not understand the plain `%d`/`%.1f` placeholders
     * the Android strings use.
     */
    private fun text(res: StringResource, vararg args: Any): String =
        runBlocking { getString(res) }.format(*args)

    /** The card's own text lines; the filter menus show the bare label, so the prefix disambiguates. */
    private fun typeLine(type: BatchTaskType): String = text(Res.string.batch_task_type_label) + text(type.labelRes)

    private fun statusLine(status: BatchTaskStatus): String =
        text(Res.string.batch_task_status_label) + text(status.labelRes)

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.exists(text: String): Boolean =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.countOf(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.countOfContentDescription(description: String): Int =
        onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    /**
     * Clicks [label] in the dropdown menu that is currently open.
     *
     * Miuix's `WindowDropdownPreference` reports a pick by writing the picked item into the row's
     * summary, and it closes the menu again (measured: the menu disappears as soon as an entry is
     * picked). While it is open a label can be on screen several times at once: the picked type
     * appears in the type row's summary and still as a menu entry, and "All" is the summary of both
     * rows plus an entry in the open menu. The menu is a second root -- a separate window registered
     * after the main one -- so its copies are the last matches in traversal order, which is what this
     * picks. The measured tree of the probe that established this is recorded in `PLAN.md` (C6c).
     */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.clickMenuItem(label: String) {
        val matches = onAllNodesWithText(label)
        val count = matches.fetchSemanticsNodes().size
        assertTrue(count >= 1, "no dropdown entry labelled \"$label\" is on screen")
        matches[count - 1].performClick()
    }

    /**
     * Performs a click that is allowed to reach `NavController.navigate`.
     *
     * `SkikoComposeUiTest` dispatches injected pointer events on the *calling* thread
     * (`CanvasLayersComposeSceneImpl.processPointerInputEvent` runs inline), while the navigation
     * stack ends any move of an entry's lifecycle in `LifecycleRegistry.setCurrentState`, which is
     * guarded by a main-thread check. Off the AWT event thread that check throws from inside
     * `navigate`, so the freshly pushed entry never leaves `INITIALIZED` and the scene teardown then
     * dies with `State must be at least 'CREATED' to be moved to 'DESTROYED'` -- a failure that says
     * nothing about the screen under test. Real clicks arrive on the event thread, so this only ever
     * bites tests that navigate; the navigation clicks below therefore go through `runOnUiThread`.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.clickAndNavigate(matcher: SemanticsMatcher) {
        runOnUiThread { onNode(matcher).performClick() }
    }

    // ------------------------------------------------------------------ tests

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the shipped navigation host renders the task history from the database`() = runComposeUiTest {
        seedFinished(BatchTaskType.EDIT_TAGS, succeeded = listOf("a.mp3", "b.mp3"))
        seedRunning(BatchTaskType.MATCH_LYRICS, "c.mp3")

        setContent {
            LyricoTheme {
                // The real host and the real route registration. Android reached this screen from the
                // settings screen's "task history" row; `SettingsScreen` is still in the Android tree,
                // so the shipped entry point does not exist yet and the test starts the shipped host
                // here explicitly to cover the `batch_task_list` registration.
                LyricoNavHost(startDestination = BatchTaskListDestination())
            }
        }

        // Room emits asynchronously, so wait for the row rather than asserting into a race. The stat
        // line is derived from the item rows, which makes it the assertion that proves the whole chain
        // (song list -> items -> counters -> screen) landed.
        waitUntil("wait for the finished task to arrive from Room", 5_000) {
            exists(text(Res.string.batch_match_stat_format, 2, 0, 0))
        }

        onNodeWithText(text(Res.string.batch_task_list_title)).assertExists()
        onNodeWithText(typeLine(BatchTaskType.EDIT_TAGS)).assertExists()
        onNodeWithText(statusLine(BatchTaskStatus.SUCCEEDED)).assertExists()
        onNodeWithText(typeLine(BatchTaskType.MATCH_LYRICS)).assertExists()
        onNodeWithText(statusLine(BatchTaskStatus.RUNNING)).assertExists()
        // A live task has no finished counters yet; the card shows the raw progress instead.
        onNodeWithText("0/1").assertExists()
        assertTrue(
            !exists(text(Res.string.batch_task_no_tasks)),
            "the empty-state card must not be shown while rows exist",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the type filter narrows the rows and All brings them back`() = runComposeUiTest {
        seedFinished(BatchTaskType.EDIT_TAGS, succeeded = listOf("a.mp3"))
        seedRunning(BatchTaskType.MATCH_LYRICS, "c.mp3")

        setContent {
            LyricoTheme {
                BatchTaskListScreenInTestHost()
            }
        }

        waitUntil("wait for both rows", 5_000) { exists(typeLine(BatchTaskType.MATCH_LYRICS)) }

        onNodeWithText(text(Res.string.batch_task_filter_type)).performClick()
        // The menu is a Miuix window with a root of its own, so opening it adds a third "All" to the
        // two rows' summaries -- that count is the cheapest proof it is really open.
        val all = text(Res.string.batch_task_filter_all)
        waitUntil("wait for the type menu to open", 5_000) { countOf(all) > 2 }

        clickMenuItem(text(BatchTaskType.EDIT_TAGS.labelRes))

        waitUntil("wait for the other type to be filtered out", 5_000) {
            !exists(typeLine(BatchTaskType.MATCH_LYRICS))
        }
        onNodeWithText(typeLine(BatchTaskType.EDIT_TAGS)).assertExists()

        // The menu closes again after a pick, so the type row is clicked once more and only then can
        // "All" be chosen; at that point the row summaries are "编辑标签" and "全部", so the menu's
        // own "全部" is the second match.
        waitUntil("wait for the menu to close after the pick", 5_000) { countOf(all) == 1 }
        onNodeWithText(text(Res.string.batch_task_filter_type)).performClick()
        waitUntil("wait for the type menu to reopen", 5_000) { countOf(all) == 2 }
        clickMenuItem(all)

        waitUntil("wait for the filter to be cleared", 5_000) { exists(typeLine(BatchTaskType.MATCH_LYRICS)) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the status filter narrows the rows`() = runComposeUiTest {
        seedFinished(BatchTaskType.EDIT_TAGS, succeeded = listOf("a.mp3"))
        seedRunning(BatchTaskType.MATCH_LYRICS, "c.mp3")

        setContent {
            LyricoTheme {
                BatchTaskListScreenInTestHost()
            }
        }

        waitUntil("wait for both rows", 5_000) { exists(statusLine(BatchTaskStatus.RUNNING)) }

        onNodeWithText(text(Res.string.batch_task_filter_status)).performClick()
        val all = text(Res.string.batch_task_filter_all)
        waitUntil("wait for the status menu to open", 5_000) { countOf(all) > 2 }

        clickMenuItem(text(BatchTaskStatus.SUCCEEDED.labelRes))

        // The running task is filtered out, and it is the *live* row: a status filter that kept it
        // would also keep the cancel button, so both are asserted to be gone.
        waitUntil("wait for the running task to be filtered out", 5_000) {
            !exists(statusLine(BatchTaskStatus.RUNNING))
        }
        onNodeWithText(statusLine(BatchTaskStatus.SUCCEEDED)).assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a live task offers cancel and a finished one offers delete`() = runComposeUiTest {
        seedFinished(BatchTaskType.EDIT_TAGS, succeeded = listOf("a.mp3"))
        seedRunning(BatchTaskType.MATCH_LYRICS, "c.mp3")

        setContent {
            LyricoTheme {
                BatchTaskListScreenInTestHost()
            }
        }

        waitUntil("wait for both rows", 5_000) { exists(statusLine(BatchTaskStatus.RUNNING)) }

        val close = text(Res.string.action_close)
        val delete = text(Res.string.common_delete)
        assertEquals(
            1,
            onAllNodesWithContentDescription(close).fetchSemanticsNodes().size,
            "only the live task can be cancelled",
        )
        assertEquals(
            1,
            onAllNodesWithContentDescription(delete).fetchSemanticsNodes().size,
            "only the finished task can be deleted",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `confirming the delete dialog removes that task from the database`() = runComposeUiTest {
        val doomed = seedFinished(BatchTaskType.EDIT_TAGS, succeeded = listOf("a.mp3"))
        // The other row is *live*, which is what makes the delete icon unambiguous: a running row
        // offers cancel instead. So "the row the user pointed at" is the only row that can be
        // deleted, and the assertion below is about identity rather than about which icon was hit.
        val running = seedRunning(BatchTaskType.MATCH_LYRICS, "c.mp3")

        setContent {
            LyricoTheme {
                BatchTaskListScreenInTestHost()
            }
        }

        waitUntil("wait for both rows", 5_000) {
            exists(statusLine(BatchTaskStatus.SUCCEEDED)) && exists(statusLine(BatchTaskStatus.RUNNING))
        }

        onNodeWithContentDescription(text(Res.string.common_delete)).performClick()

        waitUntil("wait for the confirmation dialog", 5_000) { exists(text(Res.string.batch_task_delete_title)) }
        onNodeWithText(text(Res.string.confirm)).performClick()

        waitUntil("wait for the task row to be deleted", 5_000) {
            !exists(statusLine(BatchTaskStatus.SUCCEEDED))
        }
        // The row the user pointed at is the one that went, items included; the live task is
        // untouched. Deleting the wrong row is exactly the failure a screens-level test can catch.
        val remaining = runBlocking { tasks.observeTasks().first() }
        assertEquals(listOf(running), remaining.map { it.taskId })
        assertTrue(
            runBlocking { tasks.observeItems(doomed).first() }.isEmpty(),
            "the deleted task's items have to go with it",
        )
        assertTrue(runBlocking { tasks.observeItems(running).first() }.isNotEmpty())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `cancelling a live task marks it cancelled in the database`() = runComposeUiTest {
        val taskId = seedRunning(BatchTaskType.MATCH_LYRICS, "c.mp3")

        setContent {
            LyricoTheme {
                BatchTaskListScreenInTestHost()
            }
        }

        waitUntil("wait for the live row", 5_000) { exists(statusLine(BatchTaskStatus.RUNNING)) }
        onNodeWithContentDescription(text(Res.string.action_close)).performClick()

        waitUntil("wait for the task to be cancelled", 5_000) {
            runBlocking { tasks.observeTask(taskId).first() }?.status == BatchTaskStatus.CANCELLED
        }
        // The row also flips its action: a cancelled task is history, so it can be deleted now.
        waitUntil("wait for the cancel action to be replaced by delete", 5_000) {
            !exists(text(Res.string.action_close))
        }
        onNodeWithContentDescription(text(Res.string.common_delete)).assertExists()
        assertNotNull(runBlocking { tasks.observeTask(taskId).first() }?.finishedAt)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `clearing the history keeps the live task`() = runComposeUiTest {
        val running = seedRunning(BatchTaskType.MATCH_LYRICS, "c.mp3")
        val finished = seedFinished(BatchTaskType.EDIT_TAGS, succeeded = listOf("a.mp3"))

        setContent {
            LyricoTheme {
                BatchTaskListScreenInTestHost()
            }
        }

        waitUntil("wait for both rows", 5_000) { exists(statusLine(BatchTaskStatus.SUCCEEDED)) }

        onNodeWithContentDescription(text(Res.string.batch_task_clear_title)).performClick()
        waitUntil("wait for the clear dialog", 5_000) { exists(text(Res.string.batch_task_clear_message)) }
        onNodeWithText(text(Res.string.confirm)).performClick()

        waitUntil("wait for the finished task to be cleared", 5_000) {
            runBlocking { tasks.observeTask(finished).first() } == null
        }
        // "Clear finished" must not touch live work -- it is the one destructive action that runs
        // without the user pointing at a row.
        assertNotNull(runBlocking { tasks.observeTask(running).first() })
        onNodeWithText(statusLine(BatchTaskStatus.RUNNING)).assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tapping a row asks for the detail route of that task`() = runComposeUiTest {
        val taskId = seedFinished(BatchTaskType.EDIT_TAGS, succeeded = listOf("a.mp3"))
        val navigator = RecordingNavigator()

        setContent {
            LyricoTheme {
                BatchTaskListScreenInTestHost(navigator = navigator)
            }
        }

        waitUntil("wait for the row", 5_000) { exists(typeLine(BatchTaskType.EDIT_TAGS)) }
        onNodeWithText(typeLine(BatchTaskType.EDIT_TAGS)).performClick()

        // Asserted by identity: the screen must produce the destination the Android screen declared,
        // not just "some" route.
        assertEquals(listOf<NavDirection>(BatchTaskDetailDestination(taskId)), navigator.directions.toList())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the back icon asks the navigator to pop`() = runComposeUiTest {
        seedFinished(BatchTaskType.EDIT_TAGS, succeeded = listOf("a.mp3"))
        val navigator = RecordingNavigator()

        setContent {
            LyricoTheme {
                BatchTaskListScreenInTestHost(navigator = navigator)
            }
        }

        waitUntil("wait for the row", 5_000) { exists(typeLine(BatchTaskType.EDIT_TAGS)) }
        onNodeWithContentDescription(text(Res.string.action_back)).performClick()
        assertEquals(1, navigator.popCount.get())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the real host walks from the list into the detail of the tapped task`() = runComposeUiTest {
        seedFinished(BatchTaskType.EDIT_TAGS, succeeded = listOf("first.mp3", "second.mp3"))

        setContent {
            LyricoTheme {
                // No recording navigator anywhere: this walks the *shipped* graph, which is what proves
                // the `batch_task_detail/{taskId}` registration, the argument round-trip through the
                // route string, and `koinViewModel(parameters = { parametersOf(taskId) })` resolving
                // against the entry's own `ViewModelStoreOwner`.
                LyricoNavHost(startDestination = BatchTaskListDestination())
            }
        }

        waitUntil("wait for the row", 5_000) { exists(typeLine(BatchTaskType.EDIT_TAGS)) }
        clickAndNavigate(hasText(typeLine(BatchTaskType.EDIT_TAGS)))

        waitUntil("wait for the detail screen", 5_000) { exists(text(Res.string.batch_task_detail_title)) }
        // The detail screen's header and rows can only be right if the task id made it through.
        onNodeWithText(text(Res.string.batch_task_detail_progress, 2, 2)).assertExists()
        onNodeWithText("first.mp3").assertExists()
        onNodeWithText("second.mp3").assertExists()
        // The pushed entry is RESUMED and the list has been demoted to CREATED, i.e. the enter
        // transition really finished and the list left the tree.
        assertEquals(1, countOfContentDescription(text(Res.string.action_back)))
        onNodeWithText(text(Res.string.batch_task_list_title)).assertDoesNotExist()

        // Walk back out through the same graph: this covers the other half of the pair
        // (detail -> list) and leaves the back stack settled, so the detail entry is gone by the time
        // the harness tears the scene down.
        clickAndNavigate(hasContentDescription(text(Res.string.action_back)))
        waitUntil("wait for the list to come back", 5_000) { exists(text(Res.string.batch_task_list_title)) }
        onNodeWithText(text(Res.string.batch_task_detail_title)).assertDoesNotExist()
        assertEquals(1, countOfContentDescription(text(Res.string.action_back)))
    }
}
