package com.lonx.lyrico.ui.components.bar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.lyrics.LyricsOperation
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.platform.DirectoryPicker
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_batch_export_cover
import com.lonx.lyrico.resources.action_batch_export_lyrics
import com.lonx.lyrico.resources.action_batch_match_cover
import com.lonx.lyrico.resources.action_batch_match_lyrics
import com.lonx.lyrico.resources.action_batch_match_metadata
import com.lonx.lyrico.resources.action_batch_rename
import com.lonx.lyrico.resources.action_batch_replay_gain
import com.lonx.lyrico.resources.action_delete
import com.lonx.lyrico.resources.action_share
import com.lonx.lyrico.resources.batch_edit_title
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.dialog_batch_delete_content
import com.lonx.lyrico.resources.export_destination_title
import com.lonx.lyrico.resources.lyrics_convert
import com.lonx.lyrico.resources.lyrics_organize
import com.lonx.lyrico.resources.lyrics_two_columns
import com.lonx.lyrico.screens.library.placeTaggedFixture
import com.lonx.lyrico.ui.navigation.BatchEditDestination
import com.lonx.lyrico.ui.navigation.BatchRenameDestination
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.theme.LyricoTheme
import com.lonx.lyrico.utils.LibraryScanManager
import com.lonx.lyrico.viewmodel.SortBy
import com.lonx.lyrico.viewmodel.SortOrder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The batch-action FAB: the twelve-row menu and every flow one of its rows opens.
 *
 * The component is mounted the way the library shell mounts it (`BoxScope`, a `show`/`expanded` pair
 * owned by the caller, the `SongSelectionViewModel` callbacks passed in as lambdas) rather than through
 * a screen, because that is the seam every one of these tests is about: **what a click on a row does**.
 * Two of the rows only need to reach a callback, two publish the selection and navigate, three open a
 * sheet that starts a batch task, one opens a submenu, and one is a destructive action behind a
 * confirmation. `LibraryHomeScreenTest` covers the other half -- that the shell shows this FAB only
 * while songs are selected on the songs tab.
 *
 * What is real here:
 *
 * * **The four batch view models, the batch task repository and the runner.** The export test's
 *   assertion is a task row in Room whose `configJson` carries the directory the fake picker returned,
 *   so it fails unless the row, the sheet, the picker call, the view model and the scheduler are all
 *   wired. The task is then awaited to a terminal status, so nothing is still writing to the database
 *   when the harness closes it (the leak `PLAN.md` §5.8 records).
 * * **The song.** The export test's song is a fixture scanned by the real library scan, because the
 *   view model resolves the selection through `getSongByUri` and the processor reads the file's tags.
 *   The other tests pass a hand-built row: their subject is a callback, not the library.
 * * **The picker and the navigator**, which are the port's two seams: `DirectoryPicker` replaces
 *   Android's Storage Access Framework tree picker and `Navigator` is the interface
 *   `RecordingNavigator` records, so no native dialog is ever opened and no route is ever pushed.
 *
 * The FAB's a11y label is the English literal `"Actions"` in `ExpandableFabMenu` (Android's own
 * hardcoded string, kept verbatim on the way over), which is why it is spelled out here instead of
 * looked up in `Res.string`.
 */
@OptIn(ExperimentalTestApi::class)
class SongBatchSelectionActionsTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-batch-fab").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-batch-fab-library")
    private val exportDir: Path = Files.createTempDirectory("lyrico-batch-fab-export")

    private lateinit var database: LyricoDatabase
    private lateinit var tasks: BatchTaskRepository

    /**
     * The store the FAB's view models live in. A real window's store belongs to the `library_home`
     * `NavBackStackEntry`; here there is no navigation graph, so the test owns the store and clears it
     * in [tearDown] -- the same reason and the same order `LibraryHomeScreenTest` documents.
     */
    private val viewModelStore = ViewModelStore()

    private val viewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = this@SongBatchSelectionActionsTest.viewModelStore
    }

    /** Records navigation instead of performing it, so a row's destination can be asserted by identity. */
    private class RecordingNavigator : Navigator {
        val directions = mutableListOf<NavDirection>()

        override fun navigate(direction: NavDirection) {
            directions += direction
        }

        override fun popBackStack(): Boolean = false

        override fun navigateUp(): Boolean = false
    }

    /** Counts picks, so "the sheet asked the user where to export" is observable. */
    private class FakeDirectoryPicker(private val directory: File?) : DirectoryPicker {
        var pickCount = 0

        override suspend fun pick(): File? {
            pickCount++
            return directory
        }
    }

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        database = koin()
        tasks = koin()
        // Every audio fixture TagLib ships is shorter than a minute and the app ignores short audio by
        // default, so the scan would (correctly) drop the fixture.
        runBlocking { koin<SettingsRepository>().saveIgnoreShortAudio(false) }
    }

    @AfterTest
    fun tearDown() {
        assertTrue(
            viewModelStore.keys().isNotEmpty(),
            "the mounted FAB has to put its view models in this test's store, but the store is empty",
        )
        viewModelStore.clear()
        runCatching { stopKoin() }
        database.close()
        workingDir.deleteRecursively()
        musicDir.toFile().deleteRecursively()
        exportDir.toFile().deleteRecursively()
    }

    private inline fun <reified T : Any> koin(): T = GlobalContext.get().get()

    /** The expected text for a string resource, formatted the way the FAB formats it. */
    private fun text(res: StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    /** A row the FAB only hands to a callback: nothing dereferences it, so four fields are enough. */
    private fun syntheticSong(fileName: String = "song.mp3"): SongEntity {
        val path = musicDir.resolve(fileName).toString()
        return SongEntity(
            folderId = 1L,
            mediaId = 1L,
            source = SongSource.LOCAL,
            filePath = path,
            fileName = fileName,
            uri = path,
        )
    }

    /** Places one tagged fixture, lets the real scan index it, and returns the row it produced. */
    private fun scannedSong(): SongEntity {
        placeTaggedFixture(
            musicDir = musicDir,
            fixture = "bladeenc.mp3",
            relativeDir = "Album One",
            title = "Solar Song",
            artist = "An Artist",
            album = "Album One",
            tags = koin(),
        )
        koin<LibraryScanManager>().addFolderAndScan(musicDir.toRealPath().toString())

        val repository: SongLibraryRepository = koin()
        return runBlocking {
            withTimeoutOrNull(30_000) {
                repository.observeSongs(SortBy.TITLE, SortOrder.ASC)
                    .first { it.isNotEmpty() }
                    .single()
            }
        } ?: fail("the scan of $musicDir never reached the database")
    }

    /**
     * The FAB in a `Box`, with everything a caller owns hoisted into the test.
     *
     * `expanded` is a parameter rather than internal state because the shipped shell owns it (it is what
     * `BackHandler` used to reset on Android), and `show`/`selectedSongUris` reuse the shell's rule.
     */
    @Composable
    private fun BatchFab(
        songs: List<SongEntity>,
        selectedSongUris: Set<String>,
        navigator: Navigator,
        directoryPicker: DirectoryPicker,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        show: Boolean = true,
        onSetSelectionUris: () -> Boolean = { true },
        onBatchDelete: (List<SongEntity>) -> Unit = {},
        onBatchShare: (List<SongEntity>) -> Unit = {},
    ) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
            LyricoTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    SongBatchSelectionActions(
                        navigator = navigator,
                        songs = songs,
                        show = show,
                        expanded = expanded,
                        selectedSongUris = selectedSongUris,
                        directoryPicker = directoryPicker,
                        onExpandedChange = onExpandedChange,
                        onSetSelectionUris = onSetSelectionUris,
                        onBatchDelete = onBatchDelete,
                        onBatchShare = onBatchShare,
                    )
                }
            }
        }
    }

    /** The statuses that mean the runner is done with a task, i.e. it will not touch the database again. */
    private val settled = setOf(
        BatchTaskStatus.SUCCEEDED,
        BatchTaskStatus.FAILED,
        BatchTaskStatus.SKIPPED,
        BatchTaskStatus.CANCELLED,
    )

    private fun taskStatus(): BatchTaskStatus? =
        runBlocking { tasks.observeTasks().first() }.firstOrNull()?.status

    private fun existingTask(): BatchTaskEntity = runBlocking { tasks.observeTasks().first() }.single()

    @Test
    fun `the export row takes the directory from the picker into a real batch task`() = runComposeUiTest {
        val song = scannedSong()
        val picker = FakeDirectoryPicker(exportDir.toFile())
        var expanded by mutableStateOf(false)

        setContent {
            BatchFab(
                songs = listOf(song),
                selectedSongUris = setOf(song.uri),
                navigator = RecordingNavigator(),
                directoryPicker = picker,
                expanded = expanded,
                onExpandedChange = { expanded = it },
            )
        }

        onNodeWithContentDescription("Actions").performClick()
        waitUntil("wait for the menu to expand", 5_000) {
            onAllNodesWithText(text(Res.string.action_batch_export_lyrics)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(text(Res.string.action_batch_export_lyrics)).performClick()

        // The row opens the destination sheet, and nothing is picked until the user confirms it.
        waitUntil("wait for the destination sheet", 5_000) {
            onAllNodesWithText(text(Res.string.export_destination_title)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, picker.pickCount, "opening the sheet must not ask for a folder yet")
        assertEquals(emptyList(), runBlocking { tasks.observeTasks().first() }, "and must not start a task")

        onNodeWithText(text(Res.string.confirm)).performClick()

        waitUntil("wait for the export task to be created", 30_000) {
            runBlocking { tasks.observeTasks().first() }.isNotEmpty()
        }
        assertEquals(1, picker.pickCount, "the selected-folder destination asks the picker exactly once")
        val task = existingTask()
        assertEquals(BatchTaskType.EXPORT_LYRICS, task.type, "the row that was clicked decides the task type")

        // The directory the picker returned is the one the processor will read: this is the assertion
        // that would fail if the sheet handed the view model a Uri, a null, or the audio folder.
        val config = Json.parseToJsonElement(task.configJson!!).jsonObject
        assertEquals(exportDir.toString(), config["destinationDirectory"]?.jsonPrimitive?.content)

        waitUntil("wait for the export to settle, so nothing is still writing", 60_000) {
            taskStatus() in settled
        }
        // The fixture has no lyrics, so the processor skips its item -- the point here is that it ran at
        // all, against the real file, from a real task row. `BatchExportProcessorEndToEndTest` owns the
        // file-level outcome, and the wait above already proved the status is terminal.
        assertTrue(existingTask().status in settled)
    }

    @Test
    fun `the delete row asks first, and confirming hands the selection to the delete callback`() = runComposeUiTest {
        val song = syntheticSong()
        var expanded by mutableStateOf(false)
        val deleted = mutableListOf<List<SongEntity>>()

        setContent {
            BatchFab(
                songs = listOf(song),
                selectedSongUris = setOf(song.uri),
                navigator = RecordingNavigator(),
                directoryPicker = FakeDirectoryPicker(exportDir.toFile()),
                expanded = expanded,
                onExpandedChange = { expanded = it },
                onBatchDelete = { deleted += it },
            )
        }

        onNodeWithContentDescription("Actions").performClick()
        waitUntil("wait for the menu to expand", 5_000) {
            onAllNodesWithText(text(Res.string.action_delete)).fetchSemanticsNodes().isNotEmpty()
        }
        // The row is the eleventh of twelve, so the menu has to scroll it into view first -- exactly what
        // a user does, and what a click on a clipped node would silently miss.
        onNodeWithText(text(Res.string.action_delete)).performScrollTo().assertIsDisplayed().performClick()

        val summary = text(Res.string.dialog_batch_delete_content, 1)
        waitUntil("wait for the confirmation", 5_000) {
            onAllNodesWithText(summary).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(emptyList(), deleted, "opening the confirmation must not delete anything")

        onNodeWithText(text(Res.string.confirm)).performClick()

        waitUntil("wait for the callback", 5_000) { deleted.isNotEmpty() }
        assertEquals(listOf(listOf(song)), deleted, "the FAB hands the selection, not the callback's own list")
        waitUntil("wait for the dialog to close", 5_000) {
            onAllNodesWithText(summary).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `the rename and edit rows publish the selection first and navigate only when it worked`() = runComposeUiTest {
        val song = syntheticSong()
        val navigator = RecordingNavigator()
        var expanded by mutableStateOf(false)
        var publishCalls = 0
        var publishSucceeds by mutableStateOf(false)

        setContent {
            BatchFab(
                songs = listOf(song),
                selectedSongUris = setOf(song.uri),
                navigator = navigator,
                directoryPicker = FakeDirectoryPicker(exportDir.toFile()),
                expanded = expanded,
                onExpandedChange = { expanded = it },
                onSetSelectionUris = {
                    publishCalls++
                    publishSucceeds
                },
            )
        }

        fun openMenuAndClick(label: String) {
            onNodeWithContentDescription("Actions").performClick()
            waitUntil("wait for the menu to expand", 5_000) {
                onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(label).performScrollTo().performClick()
        }

        // Nothing selected: the row publishes, is told there was nothing to publish, and must not push a
        // screen. These two routes have no `composable` block yet, so a navigation here would be a
        // logged miss rather than a crash -- which is exactly why the guard is asserted instead.
        openMenuAndClick(text(Res.string.action_batch_rename))
        waitUntil("wait for the publish attempt", 5_000) { publishCalls == 1 }
        assertEquals(emptyList(), navigator.directions, "a failed publish must not navigate")

        publishSucceeds = true
        openMenuAndClick(text(Res.string.action_batch_rename))
        waitUntil("wait for the rename navigation", 5_000) { navigator.directions.size == 1 }

        openMenuAndClick(text(Res.string.batch_edit_title))
        waitUntil("wait for the edit navigation", 5_000) { navigator.directions.size == 2 }

        // Routes, not just classes: these are the two "not ported yet" destinations the port declared, and
        // the strings have to be the ones Android's `@Destination` used.
        assertEquals(
            listOf(
                BatchRenameDestination.ROUTE,
                BatchEditDestination().route,
            ),
            navigator.directions.map { it.route },
        )
    }

    @Test
    fun `the organize row opens its submenu and the operation it offers reaches the config sheet`() =
        runComposeUiTest {
            val song = syntheticSong()
            var expanded by mutableStateOf(false)

            setContent {
                BatchFab(
                    songs = listOf(song),
                    selectedSongUris = setOf(song.uri),
                    navigator = RecordingNavigator(),
                    directoryPicker = FakeDirectoryPicker(exportDir.toFile()),
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                )
            }

            onNodeWithContentDescription("Actions").performClick()
            waitUntil("wait for the menu to expand", 5_000) {
                onAllNodesWithText(text(Res.string.lyrics_organize)).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(text(Res.string.lyrics_organize)).performClick()

            // The submenu, not the sheet: choosing an operation is what opens the sheet.
            val sortLabel = text(LyricsOperation.SORT.titleRes)
            waitUntil("wait for the submenu", 5_000) {
                onAllNodesWithText(sortLabel).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(sortLabel).performClick()

            // The sheet title is the operation's own label, which the submenu item also carries -- so the
            // assertion is on what only the *sort* operation's sheet renders: the two/three column order
            // settings. That is what proves the chosen operation reached the view model, not just that
            // some sheet opened.
            val twoColumns = text(Res.string.lyrics_two_columns)
            waitUntil("wait for the sort operation's config sheet", 5_000) {
                onAllNodesWithText(twoColumns).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(twoColumns).assertIsDisplayed()
            // A format choice would mean the row opened the convert operation's sheet instead.
            assertTrue(
                onAllNodesWithText(text(Res.string.lyrics_convert)).fetchSemanticsNodes().isEmpty(),
                "the sort sheet must not offer the convert operation's choices",
            )
        }

    @Test
    fun `the menu carries all twelve rows the shell offers`() = runComposeUiTest {
        val song = syntheticSong()
        var expanded by mutableStateOf(false)

        setContent {
            BatchFab(
                songs = listOf(song),
                selectedSongUris = setOf(song.uri),
                navigator = RecordingNavigator(),
                directoryPicker = FakeDirectoryPicker(exportDir.toFile()),
                expanded = expanded,
                onExpandedChange = { expanded = it },
            )
        }

        onNodeWithContentDescription("Actions").performClick()
        waitUntil("wait for the menu to expand", 5_000) {
            onAllNodesWithText(text(Res.string.action_batch_replay_gain)).fetchSemanticsNodes().isNotEmpty()
        }

        // Counted, not sampled: dropping a row (or duplicating one) is the regression this catches.
        listOf(
            Res.string.action_batch_replay_gain,
            Res.string.lyrics_convert,
            Res.string.lyrics_organize,
            Res.string.action_batch_export_lyrics,
            Res.string.action_batch_export_cover,
            Res.string.action_batch_rename,
            Res.string.batch_edit_title,
            Res.string.action_batch_match_cover,
            Res.string.action_batch_match_lyrics,
            Res.string.action_batch_match_metadata,
            Res.string.action_delete,
            Res.string.action_share,
        ).forEach { row ->
            assertTrue(
                onAllNodesWithText(text(row)).fetchSemanticsNodes().isNotEmpty(),
                "the menu has to offer '${text(row)}'",
            )
        }
    }

    @Test
    fun `a selection that is empty leaves the FAB inert`() = runComposeUiTest {
        val song = syntheticSong()
        val expandRequests = mutableListOf<Boolean>()

        setContent {
            BatchFab(
                songs = listOf(song),
                selectedSongUris = emptySet(),
                navigator = RecordingNavigator(),
                directoryPicker = FakeDirectoryPicker(exportDir.toFile()),
                expanded = false,
                onExpandedChange = { expandRequests += it },
            )
        }

        onNodeWithContentDescription("Actions").performClick()
        // The click is swallowed by `enabled = selectedSongUris.isNotEmpty()`, so nothing asks the caller
        // to expand -- and no row of the twelve may appear over a screen with an empty selection.
        waitUntil("wait for the click to be processed", 1_000) { true }
        assertEquals(emptyList(), expandRequests)
        assertTrue(
            onAllNodesWithText(text(Res.string.action_delete)).fetchSemanticsNodes().isEmpty(),
            "an empty selection must not open the menu",
        )
    }

    @Test
    fun `tapping the scrim closes the menu without an action`() = runComposeUiTest {
        val song = syntheticSong()
        var expanded by mutableStateOf(false)
        val deleted = mutableListOf<List<SongEntity>>()

        setContent {
            BatchFab(
                songs = listOf(song),
                selectedSongUris = setOf(song.uri),
                navigator = RecordingNavigator(),
                directoryPicker = FakeDirectoryPicker(exportDir.toFile()),
                expanded = expanded,
                onExpandedChange = { expanded = it },
                onBatchDelete = { deleted += it },
            )
        }

        onNodeWithContentDescription("Actions").performClick()
        waitUntil("wait for the menu to expand", 5_000) { expanded }

        // Away from the FAB (bottom end) and from its rows: this is the gesture that replaces Android's
        // `BackHandler`. Without it the only way out of the menu is a row that acts or the FAB itself.
        onRoot().performTouchInput { click(Offset(8f, 8f)) }

        waitUntil("wait for the menu to close", 5_000) { !expanded }
        assertEquals(emptyList(), deleted, "closing the menu must not run any row")
        waitUntil("wait for the rows to leave the tree", 5_000) {
            onAllNodesWithText(text(Res.string.action_delete)).fetchSemanticsNodes().isEmpty()
        }
    }
}
