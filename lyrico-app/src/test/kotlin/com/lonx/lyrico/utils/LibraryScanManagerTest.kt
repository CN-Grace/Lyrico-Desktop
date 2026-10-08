package com.lonx.lyrico.utils

import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.repository.SettingsRepositoryImpl
import com.lonx.lyrico.data.repository.createSettingsDataStore
import com.lonx.lyrico.data.song.scan.LibraryScanProgress
import com.lonx.lyrico.data.song.scan.LibraryScanRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRequest
import com.lonx.lyrico.data.song.scan.LibraryScanResult
import com.lonx.lyrico.data.song.scan.LibraryScanStage
import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.domain.song.usecase.SynchronizeLibraryUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for the scan queue.
 *
 * The scan itself is not what is under test — `LibraryScanIntegrationTest` covers the real scanner
 * against real files. What this class owns is orchestration, and the parts of it that can go wrong
 * need control over *time*: whether a second request arriving during a scan becomes a second scan or
 * is merged into the one already waiting, whether the reported queue matches what is really pending,
 * and whether a failure leaves a half-finished scan behind. The repository below is therefore a
 * recording fake with a gate the test opens by hand, while the database and the settings store are
 * real.
 */
class LibraryScanManagerTest {

    private val library = TestLibrary()
    private val workingDir: Path = Files.createTempDirectory("lyrico-scan-manager-test")
    private val settingsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val settings = SettingsRepositoryImpl(
        createSettingsDataStore(workingDir.resolve("settings.preferences_pb"), settingsScope)
    )

    @AfterTest
    fun tearDown() {
        settingsScope.cancel()
        library.close()
        workingDir.toFile().deleteRecursively()
    }

    private fun manager(
        scope: CoroutineScope,
        repository: LibraryScanRepository
    ) = LibraryScanManagerImpl(
        appScope = scope,
        database = library.database,
        settingsRepository = settings,
        synchronizeLibraryUseCase = SynchronizeLibraryUseCase(repository)
    )

    /**
     * A scan repository that records what it was asked to do and waits for the test to let it finish.
     *
     * The gate is what makes queueing observable: without it a scan finishes in the same dispatch as
     * the request, and every merge test would be measuring an empty queue. It also counts overlapping
     * calls, because "one scan at a time" is the invariant the queue exists for.
     */
    private class GatedScanRepository : LibraryScanRepository {
        val requests = mutableListOf<LibraryScanRequest>()
        var gate: CompletableDeferred<Unit> = CompletableDeferred()

        /** Closes the gate again, so a later scan can be observed mid-flight too. */
        fun rearm() {
            gate = CompletableDeferred()
        }

        var failure: Throwable? = null
        var result: LibraryScanResult = LibraryScanResult(
            scanned = 0, inserted = 0, updated = 0, deleted = 0, skipped = 0
        )
        var onSynchronize: suspend (LibraryScanRequest, suspend (LibraryScanProgress) -> Unit) -> Unit =
            { _, _ -> }
        var concurrent: Int = 0
            private set
        var maxConcurrent: Int = 0
            private set

        override suspend fun synchronize(
            request: LibraryScanRequest,
            onProgress: suspend (LibraryScanProgress) -> Unit
        ): LibraryScanResult {
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            try {
                requests += request
                onSynchronize(request, onProgress)
                gate.await()
                failure?.let { throw it }
                return result
            } finally {
                concurrent--
            }
        }
    }

    /** Waits for a state the manager reaches on its own, rather than assuming a dispatch order. */
    private suspend fun awaitState(
        manager: LibraryScanManager,
        predicate: (LibraryScanState) -> Boolean
    ): LibraryScanState = withTimeout(10_000) {
        while (!predicate(manager.state.value)) delay(1)
        manager.state.value
    }

    /**
     * Waits until the queue is empty *and* [expectedScans] scans have run.
     *
     * The scan count is not decoration. Between two queued scans the manager briefly reports
     * `isScanning = false` with an empty queue, right before it picks up the next request, so a
     * predicate on the state alone would pass one scan early.
     */
    private suspend fun awaitDrained(
        manager: LibraryScanManager,
        repository: GatedScanRepository,
        expectedScans: Int
    ): LibraryScanState = awaitState(manager) {
        !it.isScanning && it.queuedScanCount == 0 && repository.requests.size >= expectedScans
    }

    @Test
    fun `a whole-library scan reads the settings and runs once`() = runBlocking<Unit> {
        val repository = GatedScanRepository()
        val manager = manager(this, repository)
        settings.saveIgnoreShortAudio(true)

        manager.scanAll(fullRescan = true)
        awaitState(manager) { it.isScanning }
        repository.gate.complete(Unit)
        awaitDrained(manager, repository, expectedScans = 1)

        assertEquals(1, repository.requests.size)
        val request = repository.requests.single()
        assertTrue(request.fullRescan)
        assertNull(request.folderIds, "a whole-library scan names no folders")
        assertTrue(request.ignoreShortAudio, "the setting must reach the scanner")
    }

    @Test
    fun `scanning no folders does nothing`() = runBlocking<Unit> {
        val repository = GatedScanRepository()
        val manager = manager(this, repository)
        repository.gate.complete(Unit)

        manager.scanFolders(emptySet())

        assertEquals(0, repository.requests.size)
        assertFalse(manager.state.value.isScanning)
        assertEquals(0, manager.state.value.queuedScanCount)
    }

    @Test
    fun `progress reported by the scanner reaches the state and is cleared when the scan ends`() =
        runBlocking<Unit> {
            val repository = GatedScanRepository()
            val manager = manager(this, repository)
            val progress = LibraryScanProgress(
                stage = LibraryScanStage.READING_METADATA,
                current = 3,
                total = 9
            )
            repository.onSynchronize = { _, report -> report(progress) }

            manager.scanAll()
            val during = awaitState(manager) { it.progress == progress }
            assertEquals(progress, during.progress)
            assertTrue(during.isScanning, "progress must be reported while the scan is running")

            repository.gate.complete(Unit)
            val after = awaitDrained(manager, repository, expectedScans = 1)
            assertNull(after.progress, "progress must not outlive the scan")
        }

    @Test
    fun `the scanner is told which folders are being scanned`() = runBlocking<Unit> {
        val folderId = library.folder("""H:\Music""")
        val repository = GatedScanRepository()
        val manager = manager(this, repository)

        manager.scanFolders(setOf(folderId))
        val scanning = awaitState(manager) { it.isScanning }

        assertEquals(setOf(folderId), scanning.scanningFolderIds)
        repository.gate.complete(Unit)
        awaitDrained(manager, repository, expectedScans = 1)
        assertTrue(manager.state.value.scanningFolderIds.isEmpty())
    }

    @Test
    fun `per-folder requests that arrive during a scan are merged into one`() = runBlocking<Unit> {
        val first = library.folder("""H:\Music""")
        val second = library.folder("""H:\Other""")
        val third = library.folder("""H:\Third""")
        val repository = GatedScanRepository()
        val manager = manager(this, repository)

        manager.scanFolders(setOf(first))
        awaitState(manager) { it.isScanning }

        // Two more asks while the first scan is still running.
        manager.scanFolders(setOf(second))
        manager.scanFolders(setOf(third))

        val queued = awaitState(manager) { it.queuedScanCount == 1 }
        assertEquals(setOf(second, third), queued.queuedFolderIds)

        repository.gate.complete(Unit)
        awaitDrained(manager, repository, expectedScans = 2)

        assertEquals(2, repository.requests.size, "three asks must not become three scans")
        assertEquals(setOf(second, third), repository.requests[1].folderIds)
    }

    @Test
    fun `per-folder requests are not merged across different rescan flags`() = runBlocking<Unit> {
        val first = library.folder("""H:\Music""")
        val second = library.folder("""H:\Other""")
        val repository = GatedScanRepository()
        val manager = manager(this, repository)

        manager.scanFolders(setOf(first))
        awaitState(manager) { it.isScanning }

        manager.scanFolders(setOf(second), fullRescan = false)
        manager.scanFolders(setOf(second), fullRescan = true)

        // Re-reading tags for one folder is not the same work as not re-reading them, so these stay
        // separate even though they name the same folder.
        val queued = awaitState(manager) { it.queuedScanCount == 2 }
        assertTrue(queued.queuedFolderIds.contains(second))

        repository.gate.complete(Unit)
        awaitDrained(manager, repository, expectedScans = 3)
    }

    @Test
    fun `a whole-library request replaces the folder requests waiting behind it`() = runBlocking<Unit> {
        val first = library.folder("""H:\Music""")
        val second = library.folder("""H:\Other""")
        val repository = GatedScanRepository()
        val manager = manager(this, repository)

        manager.scanFolders(setOf(first))
        awaitState(manager) { it.isScanning }

        manager.scanFolders(setOf(second))
        awaitState(manager) { it.queuedScanCount == 1 }

        manager.scanAll()

        val queued = awaitState(manager) {
            it.queuedScanCount == 1 && it.queuedFolderIds.isEmpty()
        }
        assertTrue(queued.queuedFolderIds.isEmpty())

        repository.gate.complete(Unit)
        awaitDrained(manager, repository, expectedScans = 2)

        assertNull(repository.requests[1].folderIds, "the whole-library scan covers the folder request")
    }

    @Test
    fun `a folder request arriving during a whole-library scan is still queued`() = runBlocking<Unit> {
        val folderId = library.folder("""H:\Music""")
        val repository = GatedScanRepository()
        val manager = manager(this, repository)

        manager.scanAll()
        awaitState(manager) { it.isScanning }

        manager.scanFolders(setOf(folderId))

        // Only requests *waiting* behind a whole-library request are absorbed by it. A request that
        // arrives while the scan is already running is queued instead: the running scan may have
        // listed the folders before this one was added, so dropping it could lose a scan the user
        // explicitly asked for. A redundant scan is the cheaper mistake.
        val queued = awaitState(manager) { it.queuedScanCount == 1 }
        assertEquals(setOf(folderId), queued.queuedFolderIds)

        repository.gate.complete(Unit)
        awaitDrained(manager, repository, expectedScans = 2)

        assertEquals(setOf(folderId), repository.requests[1].folderIds)
    }

    @Test
    fun `a failing scan reports the reason and stops scanning`() = runBlocking<Unit> {
        val repository = GatedScanRepository()
        repository.failure = IllegalStateException("disk went away")
        val manager = manager(this, repository)

        manager.scanAll()
        awaitState(manager) { it.isScanning }
        repository.gate.complete(Unit)
        val failed = awaitDrained(manager, repository, expectedScans = 1)

        assertEquals("disk went away", failed.error)
        assertNull(failed.progress)
        assertTrue(failed.scanningFolderIds.isEmpty())
    }

    @Test
    fun `a failure does not run the success action`() = runBlocking<Unit> {
        val repository = GatedScanRepository()
        repository.failure = IllegalStateException("disk went away")
        val manager = manager(this, repository)
        var succeeded = false

        manager.scanAll(onSuccess = { succeeded = true })
        awaitState(manager) { it.isScanning }
        repository.gate.complete(Unit)
        awaitDrained(manager, repository, expectedScans = 1)

        assertFalse(succeeded, "the follow-up must not run after a failed scan")
    }

    @Test
    fun `the success action runs after a scan that worked`() = runBlocking<Unit> {
        val repository = GatedScanRepository()
        val manager = manager(this, repository)
        var succeeded = false

        manager.scanAll(onSuccess = { succeeded = true })
        awaitState(manager) { it.isScanning }
        repository.gate.complete(Unit)
        awaitDrained(manager, repository, expectedScans = 1)

        assertTrue(succeeded)
    }

    @Test
    fun `a completed scan clears the previous error`() = runBlocking<Unit> {
        val repository = GatedScanRepository()
        repository.failure = IllegalStateException("first attempt failed")
        val manager = manager(this, repository)

        manager.scanAll()
        awaitState(manager) { it.isScanning }
        repository.gate.complete(Unit)
        awaitDrained(manager, repository, expectedScans = 1)
        assertEquals("first attempt failed", manager.state.value.error)

        repository.failure = null
        // Close the gate again: with an open gate the second scan would finish inside the same
        // dispatch, and `isScanning` could never be observed.
        repository.rearm()
        manager.scanAll()
        val second = awaitState(manager) { it.isScanning }

        assertNull(second.error, "a new scan must not report the previous failure")
        repository.gate.complete(Unit)
        awaitDrained(manager, repository, expectedScans = 2)

        assertNull(manager.state.value.error)
    }

    @Test
    fun `adding a folder records it as a user root and scans it`() = runBlocking<Unit> {
        val repository = GatedScanRepository()
        val manager = manager(this, repository)

        manager.addFolderAndScan("""H:\Music""")
        val scanning = awaitState(manager) { it.isScanning }

        val folder = library.database.folderDao().getAllFoldersOnce().single()
        assertTrue(folder.addedBySaf, "a folder the user added is a library root")
        assertFalse(folder.isIgnored)
        assertEquals(setOf(folder.id), scanning.scanningFolderIds)

        repository.gate.complete(Unit)
        awaitDrained(manager, repository, expectedScans = 1)
    }

    @Test
    fun `adding a folder below an existing root does not create a second root`() = runBlocking<Unit> {
        val repository = GatedScanRepository()
        val manager = manager(this, repository)
        repository.gate.complete(Unit)

        val parent = library.folder("""H:\Music""")
        manager.addFolderAndScan("""H:\Music\Albums""")
        awaitDrained(manager, repository, expectedScans = 1)

        // The DAO collapses the child into the existing parent, so the scan targets the parent and the
        // library does not end up with two roots covering the same songs.
        val folders: List<FolderEntity> = library.database.folderDao().getAllFoldersOnce()
        assertEquals(listOf(parent), folders.map { it.id })
        assertEquals(listOf(parent), repository.requests.mapNotNull { it.folderIds?.single() })
    }

    @Test
    fun `adding the same folder twice does not duplicate the root`() = runBlocking<Unit> {
        val repository = GatedScanRepository()
        val manager = manager(this, repository)
        repository.gate.complete(Unit)

        manager.addFolderAndScan("""H:\Music""")
        awaitDrained(manager, repository, expectedScans = 1)
        manager.addFolderAndScan("""H:\Music""")
        awaitDrained(manager, repository, expectedScans = 2)

        assertEquals(1, library.database.folderDao().getAllFoldersOnce().size)
        assertEquals(repository.requests[0].folderIds, repository.requests[1].folderIds)
    }

    @Test
    fun `an ignored folder is un-ignored when the user adds it again`() = runBlocking<Unit> {
        val repository = GatedScanRepository()
        val manager = manager(this, repository)
        repository.gate.complete(Unit)

        val existing = library.folder("""H:\Music""", isIgnored = true)
        manager.addFolderAndScan("""H:\Music""")
        awaitDrained(manager, repository, expectedScans = 1)

        val folder = library.database.folderDao().getAllFoldersOnce().single()
        assertEquals(existing, folder.id)
        assertFalse(folder.isIgnored, "an ignored folder the user adds back must become visible again")
        assertTrue(folder.addedBySaf)
    }

    @Test
    fun `scans run one at a time`() = runBlocking<Unit> {
        val repository = GatedScanRepository()
        val manager = manager(this, repository)

        manager.scanAll()
        awaitState(manager) { it.isScanning }

        // A second request while the first scan is still blocked on the gate. A manager that ran them
        // concurrently would enter the second scan before the gate opens.
        manager.scanAll()
        assertEquals(1, repository.maxConcurrent, "a queued scan must not start while one is running")

        repository.gate.complete(Unit)
        awaitDrained(manager, repository, expectedScans = 2)

        assertEquals(1, repository.maxConcurrent, "two scans must never overlap")
    }

    @Test
    fun `the queue is reported empty after everything runs`() = runBlocking<Unit> {
        val repository = GatedScanRepository()
        val manager = manager(this, repository)
        repository.gate.complete(Unit)

        manager.scanAll()
        awaitDrained(manager, repository, expectedScans = 1)

        val state = manager.state.value
        assertEquals(0, state.queuedScanCount)
        assertTrue(state.queuedFolderIds.isEmpty())
        assertFalse(state.isScanning)
        assertNull(state.progress)
    }
}
