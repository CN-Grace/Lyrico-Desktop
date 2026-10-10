package com.lonx.lyrico.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import com.lonx.lyrico.resources.batch_task_detail_no_records
import com.lonx.lyrico.resources.batch_task_detail_progress
import com.lonx.lyrico.resources.batch_task_detail_title
import com.lonx.lyrico.resources.batch_task_status_failed
import com.lonx.lyrico.resources.batch_task_status_skipped
import com.lonx.lyrico.resources.batch_task_status_succeeded
import com.lonx.lyrico.ui.navigation.BatchTaskDetailDestination
import com.lonx.lyrico.ui.navigation.EditMetadataDestination
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.theme.LyricoTheme
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * One batch task's detail: the header card, the three status tabs and what each tab claims.
 *
 * The list screen test already proves the *route* into this screen (argument round-trip, view model
 * parameters). What is left is the screen's own contract, so every assertion here is about what the
 * rows say rather than about how they were reached:
 *
 * * the header is derived from the task row (`current/total`, the counters, the type/status line), so
 *   it is checked against a task whose counters come from real item rows;
 * * the tabs are a three-page `HorizontalPager` with `beyondViewportPageCount = 1`. That constant is
 *   not cosmetic: a neighbouring page is composed while its rows are still laid out off-screen, so
 *   "the row exists" is not the same as "the row is on the tab the user is looking at". The
 *   assertions therefore use displayed-ness, not existence, which is the property the user sees;
 * * the cancel action is a state machine over `BatchTaskStatus` (only a live task shows it), and the
 *   write it performs is asserted against the database;
 * * a row tap has to produce the *same* destination the Android screen declared.
 */
class BatchTaskDetailScreenTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-batch-task-detail").toFile()
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
     * The screen inside a real `NavHost` entry, so `koinViewModel(parameters = { parametersOf(id) })`
     * resolves against a `ViewModelStoreOwner` exactly as it does in the shipped host.
     */
    @Composable
    private fun BatchTaskDetailScreenInTestHost(
        taskId: String,
        navigator: Navigator = RecordingNavigator()
    ) {
        val controller = rememberNavController()
        NavHost(
            navController = controller,
            startDestination = BatchTaskDetailDestination.PATTERN
        ) {
            composable(BatchTaskDetailDestination.PATTERN) {
                BatchTaskDetailScreen(taskId = taskId, navigator = navigator)
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

    /**
     * A finished task with one item per wanted status, so each tab has real rows to filter.
     *
     * Items are addressed by the `<taskId>-<index>` scheme the repository's `createTask` assigns, and
     * the statuses are written through the same repository calls the worker uses, so the counters the
     * header prints are derived from these rows instead of being injected.
     */
    private fun seedFinished(
        succeeded: List<String> = emptyList(),
        failed: List<String> = emptyList(),
        skipped: List<String> = emptyList(),
    ): String = runBlocking {
        val names = succeeded + failed + skipped
        val taskId = tasks.createTask(BatchTaskType.EDIT_TAGS, names.map { song(it) }, null)
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

    /** A task that is still live: RUNNING, with its only item in flight. */
    private fun seedRunning(type: BatchTaskType = BatchTaskType.EDIT_TAGS): String = runBlocking {
        val taskId = tasks.createTask(type, listOf(song("live.mp3")), null)
        tasks.markRunning(taskId)
        tasks.markItemRunning("$taskId-0")
        tasks.updateProgressFromItems(taskId, currentFile = "live.mp3")
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

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.countOfText(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    /**
     * True when at least one node carrying [text] is actually on screen.
     *
     * The pager keeps a neighbouring page composed, and `onNodeWithText` resolves to the *first*
     * match, which may be that off-screen copy; so a displayed-ness assertion has to be made over all
     * of them.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.isAnyDisplayed(text: String): Boolean {
        val nodes = onAllNodesWithText(text)
        return (0 until nodes.fetchSemanticsNodes().size).any { index ->
            runCatching { nodes[index].assertIsDisplayed() }.isSuccess
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.countOfContentDescription(description: String): Int =
        onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    /**
     * Clicks a node from the AWT event thread.
     *
     * `SkikoComposeUiTest` dispatches injected pointer events on the *calling* thread, while every
     * move of a navigation entry's lifecycle ends in `LifecycleRegistry.setCurrentState`, which is
     * guarded by a main-thread check (see `BatchTaskListScreenTest.clickAndNavigate`, where a click
     * that navigates proved the point). These clicks do not navigate -- the detail screen's navigator
     * is recorded -- but the tab row and the pager run their own animations, so the click is put on
     * the same thread the real UI would call it from.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.clickOnUiThread(text: String) {
        runOnUiThread { onNodeWithText(text).performClick() }
    }

    private suspend fun statusOf(taskId: String): BatchTaskStatus? =
        tasks.getTask(taskId)?.status

    // ------------------------------------------------------------------ tests

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the header counts the items and only a live task offers cancel`() = runComposeUiTest {
        val taskId = seedFinished(
            succeeded = listOf("ok-1.mp3", "ok-2.mp3"),
            failed = listOf("bad.mp3"),
            skipped = listOf("skip.mp3"),
        )
        val navigator = RecordingNavigator()
        setContent { LyricoTheme { BatchTaskDetailScreenInTestHost(taskId, navigator) } }

        waitUntil("wait for the header", 5_000) {
            onAllNodesWithText(text(Res.string.batch_task_detail_title)).fetchSemanticsNodes().isNotEmpty()
        }

        // Everything below comes out of the task row, which is why the counters are seeded through
        // real item rows: `4/4` is the number of processed items out of the created ones.
        onNodeWithText(text(Res.string.batch_task_detail_progress, 4, 4)).assertExists()
        onNodeWithText(text(Res.string.batch_match_stat_format, 2, 1, 1), substring = true).assertExists()
        // The card's first line is `type · status`; taken together it is unique to the card, since
        // the bare status label also names a tab.
        onNodeWithText(
            text(BatchTaskType.EDIT_TAGS.labelRes) + " · " + text(BatchTaskStatus.SUCCEEDED.labelRes),
            substring = true,
        ).assertExists()

        // The three tabs are the three status names; no fourth category exists in the model.
        onNodeWithText(text(Res.string.batch_task_status_succeeded)).assertExists()
        onNodeWithText(text(Res.string.batch_task_status_failed)).assertExists()
        onNodeWithText(text(Res.string.batch_task_status_skipped)).assertExists()

        // A finished task is not cancellable, and the screen has no other top-bar action.
        assertEquals(0, countOfContentDescription(text(Res.string.action_close)))
        assertEquals(1, countOfContentDescription(text(Res.string.action_back)))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `each tab shows the items of its own status`() = runComposeUiTest {
        val taskId = seedFinished(
            succeeded = listOf("ok.mp3"),
            failed = listOf("bad.mp3"),
            skipped = listOf("skip.mp3"),
        )
        setContent { LyricoTheme { BatchTaskDetailScreenInTestHost(taskId) } }

        waitUntil("wait for the first page", 5_000) {
            onAllNodesWithText("ok.mp3").fetchSemanticsNodes().isNotEmpty()
        }

        // The pager composes the neighbouring page, so the failed row is in the tree from the start;
        // what the user sees is decided by whether it is laid out on screen.
        assertTrue(isAnyDisplayed("ok.mp3"))
        assertTrue(!isAnyDisplayed("bad.mp3"))

        clickOnUiThread(text(Res.string.batch_task_status_failed))
        waitUntil("wait for the failed page", 5_000) { isAnyDisplayed("bad.mp3") }
        assertTrue(isAnyDisplayed("bad.mp3"))
        assertTrue(!isAnyDisplayed("ok.mp3"))
        // The failure reason is part of the row, not a tooltip the tab has to fetch.
        onNodeWithText("boom", substring = true).assertExists()

        clickOnUiThread(text(Res.string.batch_task_status_skipped))
        waitUntil("wait for the skipped page", 5_000) { isAnyDisplayed("skip.mp3") }
        assertTrue(isAnyDisplayed("skip.mp3"))
        assertTrue(!isAnyDisplayed("bad.mp3"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a tab with no matching item says so`() = runComposeUiTest {
        val taskId = seedFinished(succeeded = listOf("only.mp3"))
        setContent { LyricoTheme { BatchTaskDetailScreenInTestHost(taskId) } }

        waitUntil("wait for the first page", 5_000) {
            onAllNodesWithText("only.mp3").fetchSemanticsNodes().isNotEmpty()
        }

        clickOnUiThread(text(Res.string.batch_task_status_skipped))
        waitUntil("wait for the empty placeholder", 5_000) {
            isAnyDisplayed(text(Res.string.batch_task_detail_no_records))
        }
        assertTrue(isAnyDisplayed(text(Res.string.batch_task_detail_no_records)))
        // The succeeded row belongs to the page that is no longer on screen.
        assertTrue(!isAnyDisplayed("only.mp3"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `cancelling a live task writes the cancelled status to the database`() = runComposeUiTest {
        val taskId = seedRunning()
        // The screen is driven against the real database, so the precondition is read back from it.
        assertEquals(BatchTaskStatus.RUNNING, runBlocking { statusOf(taskId) })

        setContent { LyricoTheme { BatchTaskDetailScreenInTestHost(taskId) } }

        waitUntil("wait for the cancel action", 5_000) {
            countOfContentDescription(text(Res.string.action_close)) == 1
        }
        onNodeWithContentDescription(text(Res.string.action_close)).performClick()

        waitUntil("wait for the write", 5_000) {
            runBlocking { statusOf(taskId) } == BatchTaskStatus.CANCELLED
        }
        assertEquals(BatchTaskStatus.CANCELLED, runBlocking { statusOf(taskId) })
        // The task is no longer live, so the action it offered is gone again.
        waitUntil("wait for the action to disappear", 5_000) {
            countOfContentDescription(text(Res.string.action_close)) == 0
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tapping a row asks for that file's metadata editor`() = runComposeUiTest {
        val taskId = seedFinished(succeeded = listOf("row.mp3"))
        val navigator = RecordingNavigator()
        setContent { LyricoTheme { BatchTaskDetailScreenInTestHost(taskId, navigator) } }

        waitUntil("wait for the row", 5_000) {
            onAllNodesWithText("row.mp3").fetchSemanticsNodes().isNotEmpty()
        }
        clickOnUiThread("row.mp3")

        // Asserted by identity, and by the argument the route carries: the URI is the song's, not the
        // file name the row happens to print.
        assertEquals(
            listOf<NavDirection>(EditMetadataDestination("""H:\Music\row.mp3""")),
            navigator.directions.toList(),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the back icon asks the navigator to pop`() = runComposeUiTest {
        val taskId = seedFinished(succeeded = listOf("row.mp3"))
        val navigator = RecordingNavigator()
        setContent { LyricoTheme { BatchTaskDetailScreenInTestHost(taskId, navigator) } }

        waitUntil("wait for the back icon", 5_000) {
            countOfContentDescription(text(Res.string.action_back)) == 1
        }
        onNodeWithContentDescription(text(Res.string.action_back)).performClick()
        assertEquals(1, navigator.popCount.get())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an id the database does not know renders an empty detail instead of crashing`() = runComposeUiTest {
        val navigator = RecordingNavigator()
        setContent {
            LyricoTheme { BatchTaskDetailScreenInTestHost("not-a-task-id", navigator) }
        }

        // The title, the tabs and the "no records" placeholder are all that is left: the header card
        // is the only part of the screen that needs the row to exist.
        waitUntil("wait for the empty detail", 5_000) {
            onAllNodesWithText(text(Res.string.batch_task_detail_title)).fetchSemanticsNodes().isNotEmpty()
        }
        // A task that does not exist has no items at all, so *every* page that is composed is empty.
        // Pages within one of the current one are composed (`beyondViewportPageCount = 1`), which is
        // exactly two placeholders while the first page is current -- and no more, so the third tab is
        // not composed ahead of time.
        assertEquals(2, countOfText(text(Res.string.batch_task_detail_no_records)))
        // Nothing about the task is printed, not even a zeroed progress line.
        onNodeWithText(text(Res.string.batch_task_detail_progress, 0, 0)).assertDoesNotExist()
        assertTrue(navigator.directions.isEmpty())
    }
}
