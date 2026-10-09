package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.model.search.LocalSearchType
import com.lonx.lyrico.data.song.mapper.SortKeyUpdater
import com.lonx.lyrico.data.song.search.SongSearchRepository
import com.lonx.lyrico.data.song.search.SongSearchRepositoryImpl
import com.lonx.lyrico.utils.UpdateEffect
import com.lonx.lyrico.utils.UpdateManager
import com.lonx.lyrico.utils.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The song list's state holder, driven against a real database, real settings and a real FTS index.
 *
 * The interesting parts are the ones a fake would hide:
 *
 * * **Sorting is persisted, not held in memory.** `onSortChange` writes through `SettingsRepository`
 *   (a real DataStore) and the list is a Room query on that setting, so the test changes the sort and
 *   then waits for the *rows* to come back in a new order. Sort keys are computed by the real
 *   `SortKeyUpdater` -- rows inserted with the default `"#"` key would make the order assertion pass for
 *   the wrong reason.
 * * **The folder flag is a database observation**, so it has to flip when a folder appears (and only
 *   then), instead of being a constructor-time snapshot.
 * * **A folder added from the picker only has a path.** `addFolderAndRefresh` is where Android's
 *   `treeUri` parameter was dropped; the scanner fake records exactly what it received, which is the
 *   only way to prove the SAF plumbing is really gone.
 *
 * Not covered here: the `searchQuery`/`searchType`/`isSearching` members. They are vestigial in the
 * Android app as well -- `SongsPage` only ever calls `clearSearch()`, and the search entry point
 * navigates to `LocalSearchScreen`, whose own view model owns querying. They are kept for parity
 * (`clearSearch` is asserted below) but no call site can drive them, so claiming a search behaves
 * correctly would be a claim about code nothing reaches.
 */
class SongListViewModelTest {

    private lateinit var fixture: LibraryBrowseFixture
    private lateinit var search: SongSearchRepository
    private lateinit var updates: RecordingUpdateManager
    private lateinit var viewModel: SongListViewModel
    private lateinit var collectors: CoroutineScope
    private val sortKeys = SortKeyUpdater()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        fixture = LibraryBrowseFixture()
        search = SongSearchRepositoryImpl(fixture.library.database)
        updates = RecordingUpdateManager()
        collectors = CoroutineScope(Dispatchers.Default + SupervisorJob())
        viewModel = SongListViewModel(
            songLibraryRepository = fixture.songs,
            songSearchRepository = search,
            settingsRepository = fixture.settings,
            updateManager = updates,
            libraryScanManager = fixture.scanManager,
            database = fixture.library.database,
        )
        // `songs` and `hasFolders` are `WhileSubscribed`, i.e. they do nothing at all until something
        // collects them -- polling `.value` without this would read the initial empty list forever.
        collectors.keepCollecting(viewModel.songs)
        collectors.keepCollecting(viewModel.hasFolders)
    }

    @AfterTest
    fun tearDown() {
        collectors.cancel()
        fixture.close()
        Dispatchers.resetMain()
    }

    /** Seeds songs whose sort keys are real, so ordering assertions mean something. */
    private suspend fun seedLibrary() {
        val folderId = fixture.library.folder()
        fixture.seed(
            sortKeys.update(
                fixture.library.song(
                    path = """H:\Music\aria.mp3""",
                    title = "Aria",
                    artist = "Aria",
                    album = "A1",
                    folderId = folderId,
                )
            ),
            sortKeys.update(
                fixture.library.song(
                    path = """H:\Music\zebra.mp3""",
                    title = "Zebra",
                    artist = "Zebra Band",
                    album = "Z1",
                    folderId = folderId,
                )
            ),
        )
    }

    private fun CoroutineScope.keepCollecting(flow: StateFlow<*>) {
        launch { flow.collect { } }
    }

    @Test
    fun `the list is ordered by the saved sort and follows a change to it`() = runBlocking<Unit> {
        seedLibrary()
        awaitUntil(describe = { viewModel.songs.value.map { it.title } }) { it.size == 2 }
        assertEquals(
            listOf("Aria", "Zebra"),
            viewModel.songs.value.map { it.title },
            "the default sort is title ascending",
        )

        viewModel.onSortChange(SortInfo(sortBy = SortBy.TITLE, order = SortOrder.DESC))

        awaitUntil(describe = { viewModel.songs.value.map { it.title } }) {
            it == listOf("Zebra", "Aria")
        }
        assertEquals(
            SortInfo(sortBy = SortBy.TITLE, order = SortOrder.DESC),
            fixture.settings.sortInfo.first(),
            "the chosen sort must be persisted, otherwise it resets on the next launch",
        )
    }

    @Test
    fun `clearing the search resets the keyword type and keeps the library listed`() = runBlocking<Unit> {
        seedLibrary()
        awaitUntil(describe = { viewModel.songs.value.size }) { it == 2 }

        viewModel.clearSearch()

        assertEquals("", viewModel.uiState.value.searchQuery)
        assertEquals(LocalSearchType.ALL, viewModel.searchType.value)
        awaitUntil(describe = { viewModel.songs.value.map { it.title } }) {
            it == listOf("Aria", "Zebra")
        }
    }

    @Test
    fun `the folder flag is false until a folder exists`() = runBlocking<Unit> {
        assertTrue(viewModel.hasFolders.value.not(), "a fresh database has no library folder")

        fixture.library.folder()

        awaitUntil(describe = { viewModel.hasFolders.value }) { it }
    }

    @Test
    fun `a manual refresh asks the scanner for a scan`() = runBlocking<Unit> {
        viewModel.refreshSongs()

        assertEquals(1, fixture.scanManager.scanAllCalls.size)
    }

    @Test
    fun `adding a folder hands over the path alone`() = runBlocking<Unit> {
        viewModel.addFolderAndRefresh(path = """H:\Music""")

        assertEquals(listOf("""H:\Music"""), fixture.scanManager.addedPaths)
    }

    @Test
    fun `the update check only asks GitHub when the setting is on`() = runBlocking<Unit> {
        // Measure the enabled path first: it proves the coroutine really does reach the manager, and
        // how long that takes, which is what makes the disabled assertion below more than a sleep.
        val startNanos = System.nanoTime()
        fixture.settings.saveCheckUpdateEnabled(true)
        viewModel.checkForUpdate()
        awaitUntil(describe = { updates.checks }) { it == 1 }
        val enabledLatencyMillis = (System.nanoTime() - startNanos) / 1_000_000L

        fixture.settings.saveCheckUpdateEnabled(false)
        viewModel.checkForUpdate()
        // Far beyond the latency just measured, so "it was just slow" cannot explain a pass.
        delay(enabledLatencyMillis * 20 + 500L)

        assertEquals(1, updates.checks, "the check ran although the setting was off")
    }
}

/** Records update checks instead of hitting GitHub; the network path is covered by `UpdateManagerTest`. */
private class RecordingUpdateManager : UpdateManager {

    private val _state = MutableStateFlow(UpdateState())
    override val state: StateFlow<UpdateState> = _state.asStateFlow()
    override val effect: Flow<UpdateEffect> = emptyFlow()

    var checks = 0
        private set

    override fun checkForUpdate() {
        checks++
    }

    override fun dismissUpdateDialog() = Unit

    override fun resetUpdateState() = Unit
}
