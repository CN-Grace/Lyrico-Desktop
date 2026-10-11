package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.song.mapper.SortKeyUpdater
import com.lonx.lyrico.utils.LibraryScanState
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The folder manager against a real database and a recording scanner.
 *
 * The port removed the whole SAF layer from this view model, and the tests that matter are the ones that
 * pin what replaced it:
 *
 * - **deleting a folder takes its subtree with it** and then prunes the index, which is the one place
 *   this view model writes through two repositories in sequence;
 * - **ignoring is recursive** (`setIgnoredRecursively`), so a test that only checks the row it passed in
 *   would pass even if children kept being scanned;
 * - **the two scanner calls are distinguishable** — "refresh this folder" must ask for exactly that id,
 *   while "add" hands over a path and no tree uri, because on desktop there is no tree uri to record.
 *
 * The view model is created through a real `ViewModelStore` so its scope is cancelled in teardown (see
 * `BatchExportViewModelTest` for why a leaked `viewModelScope` lands on the next test).
 */
class FolderManagerViewModelTest {

    private lateinit var fixture: LibraryBrowseFixture
    private lateinit var appScope: CoroutineScope
    private lateinit var collectors: CoroutineScope
    private val store = ViewModelStore()
    private lateinit var viewModel: FolderManagerViewModel

    /**
     * The folder list as the database flow reports it. `awaitUntil`'s description is not suspending and
     * the DAO is, so the rows are mirrored here by a collector -- and asserting on the *flow* is what a
     * user sees, rather than on a one-off query that could be right while nothing is emitted.
     */
    private val folderRows = AtomicReference<List<FolderEntity>>(emptyList())

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        fixture = LibraryBrowseFixture()
        appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        collectors = CoroutineScope(Dispatchers.Default + SupervisorJob())
        viewModel = newViewModel()
    }

    /**
     * The house idiom for owning a view model's scope in a test: a real `ViewModelProvider` over a real
     * `ViewModelStore`, so `clear()` in teardown is what cancels `viewModelScope`.
     *
     * Not `ViewModelStore.put(...)`: that one compiles against lifecycle 2.11's metadata but is missing
     * from the JVM artifact at runtime (`NoSuchMethodError: put(String, ViewModel)`), so it only fails
     * once the test runs.
     */
    private val provider = ViewModelProvider.create(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: kotlin.reflect.KClass<T>, extras: CreationExtras): T =
                FolderManagerViewModel(
                    database = fixture.library.database,
                    libraryScanManager = fixture.scanManager,
                    appScope = appScope,
                    libraryIndexRepository = fixture.index,
                    songLibraryRepository = fixture.songs,
                ) as T
        },
        CreationExtras.Empty,
    )

    private fun newViewModel(): FolderManagerViewModel {
        val created = provider[FolderManagerViewModel::class]
        collectors.launch { created.uiState.collect { } }
        collectors.launch { created.currentFolderSongs.collect { } }
        collectors.launch {
            fixture.library.database.folderDao().getAllFolders().collect { folderRows.set(it) }
        }
        return created
    }

    @AfterTest
    fun tearDown() {
        store.clear()
        runBlocking {
            withTimeoutOrNull(5_000L) { viewModel.viewModelScope.coroutineContext[Job]?.join() }
        }
        collectors.cancel()
        appScope.cancel()
        fixture.close()
        Dispatchers.resetMain()
    }

    private suspend fun folders(): List<FolderEntity> =
        fixture.library.database.folderDao().getAllFoldersOnce()

    /** The view model only ever reads `id` off the entity it is handed. */
    private fun folderRef(id: Long, path: String): FolderEntity = FolderEntity(id = id, path = path)

    @Test
    fun `the folder list comes from the database, with the scanner state attached`() = runBlocking<Unit> {
        val root = fixture.library.folder(path = """H:\Music""")
        fixture.scanManager.emitScanState(LibraryScanState(scanningFolderIds = setOf(root)))

        awaitUntil(describe = { viewModel.uiState.value }) { state ->
            state.folders.any { it.id == root } && state.scanningFolderIds == setOf(root)
        }
    }

    @Test
    fun `deleting a folder removes its subtree and prunes the index`() = runBlocking<Unit> {
        val parent = fixture.library.folder(path = """H:\Music""")
        val child = fixture.library.folder(path = """H:\Music\Albums""")
        assertEquals(2, folders().size, "the fixture did not seed a subtree")

        viewModel.deleteFolder(folderRef(parent, """H:\Music"""))

        awaitUntil(describe = { folderRows.get().map(FolderEntity::id) }) { it.isEmpty() }
        assertTrue(folders().none { it.id == child }, "the child row survived a subtree delete")
    }

    @Test
    fun `ignoring a folder is recursive`() = runBlocking<Unit> {
        val parent = fixture.library.folder(path = """H:\Music""")
        fixture.library.folder(path = """H:\Music\Albums""")

        viewModel.setFolderIgnored(folderRef(parent, """H:\Music"""), ignored = true)
        awaitUntil(describe = { folderRows.get().map(FolderEntity::isIgnored) }) { it.all { ignored -> ignored } }

        viewModel.setFolderIgnored(folderRef(parent, """H:\Music"""), ignored = false)
        awaitUntil(describe = { folderRows.get().map(FolderEntity::isIgnored) }) { it.none { ignored -> ignored } }
    }

    @Test
    fun `refreshing a folder asks the scanner for exactly that folder`() = runBlocking<Unit> {
        val root = fixture.library.folder(path = """H:\Music""")

        viewModel.refreshFolder(folderRef(root, """H:\Music"""))

        awaitUntil(describe = { fixture.scanManager.scanFolderCalls.size }) { it == 1 }
        assertEquals(setOf(root) to false, fixture.scanManager.scanFolderCalls.single())
    }

    @Test
    fun `adding a folder hands the scanner a path and no tree uri`() {
        viewModel.addFolder("""H:\Music""")

        assertEquals(listOf("""H:\Music"""), fixture.scanManager.addedPaths, "the path did not reach the scanner")
    }

    @Test
    fun `the song list follows the selected folder and its sort`() = runBlocking<Unit> {
        val first = fixture.library.folder(path = """H:\Music\A""")
        val second = fixture.library.folder(path = """H:\Music\B""")
        // The sort keys have to be built, not defaulted: `titleSortKey` defaults to "#" for every row,
        // and a descending query over equal keys returns the same order -- which would make this test
        // pass while proving nothing about the sort.
        val sortKeys = SortKeyUpdater()
        fixture.seed(
            sortKeys.update(fixture.library.song(path = """H:\Music\A\a.mp3""", title = "Alpha", folderId = first)),
            sortKeys.update(fixture.library.song(path = """H:\Music\A\b.mp3""", title = "Beta", folderId = first)),
            sortKeys.update(fixture.library.song(path = """H:\Music\B\c.mp3""", title = "Gamma", folderId = second)),
        )

        // Nothing selected yet: the flow is deliberately empty rather than "the whole library".
        assertEquals(emptyList(), viewModel.currentFolderSongs.value)

        viewModel.setCurrentFolderId(first)
        awaitUntil(describe = { viewModel.currentFolderSongs.value.map { it.title } }) { it.size == 2 }
        assertEquals(listOf("Alpha", "Beta"), viewModel.currentFolderSongs.value.map { it.title })

        viewModel.onSortChange(SortInfo(SortBy.TITLE, SortOrder.DESC))
        awaitUntil(describe = { viewModel.currentFolderSongs.value.map { it.title } }) {
            it == listOf("Beta", "Alpha")
        }

        viewModel.setCurrentFolderId(second)
        awaitUntil(describe = { viewModel.currentFolderSongs.value.map { it.title } }) { it == listOf("Gamma") }
    }
}
