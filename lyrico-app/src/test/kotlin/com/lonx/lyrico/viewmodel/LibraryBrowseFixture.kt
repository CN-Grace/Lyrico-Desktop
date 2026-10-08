package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepositoryImpl
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.repository.SettingsRepositoryImpl
import com.lonx.lyrico.data.repository.createSettingsDataStore
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepositoryImpl
import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.utils.LibraryScanManager
import com.lonx.lyrico.utils.LibraryScanState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files

/**
 * Shared scaffolding for the two library-browse view models.
 *
 * Everything behind the view models is real: a file-backed Room database, the real index repository
 * and a real DataStore. Only the scan manager is a recording fake, because the property under test is
 * *what the view model asks the scanner for* (and which index rebuild it attaches to scan success) --
 * the scan itself is covered by `LibraryScanManagerTest` / `LibraryIntegrationTest`.
 */
class LibraryBrowseFixture {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val library: TestLibrary = TestLibrary()
    val songs: SongLibraryRepository = SongLibraryRepositoryImpl(library.database)
    val index: LibraryIndexRepository = LibraryIndexRepositoryImpl(
        database = library.database,
        songDao = library.database.songDao(),
        indexDao = library.database.libraryIndexDao(),
        settingsRepository = settings(),
    )
    val settings: SettingsRepository = settings()
    val scanManager = RecordingLibraryScanManager()

    private fun settings() = SettingsRepositoryImpl(
        createSettingsDataStore(Files.createTempFile("lyrico-browse-test", ".preferences_pb"), scope)
    )

    /** Inserts the songs and rebuilds the artist/album index, so the browse flows have rows to sort. */
    suspend fun seed(vararg songs: SongEntity) {
        this.songs.upsertSongs(songs.toList())
        index.rebuildAllIndexes()
    }

    fun close() {
        scope.cancel()
        library.close()
    }
}

/**
 * Records scan requests instead of scanning. The success callback is *not* invoked automatically: the
 * view models are meant to hand the index rebuild to the scanner and let the scanner decide when the
 * scan succeeded, so a test has to fire it explicitly.
 */
class RecordingLibraryScanManager : LibraryScanManager {

    override val state: StateFlow<LibraryScanState> = MutableStateFlow(LibraryScanState())

    data class ScanAllCall(val fullRescan: Boolean, val onSuccess: (suspend () -> Unit)?)

    val scanAllCalls = mutableListOf<ScanAllCall>()
    val scanFolderCalls = mutableListOf<Pair<Set<Long>, Boolean>>()
    val addedPaths = mutableListOf<String>()

    override fun scanAll(fullRescan: Boolean, onSuccess: (suspend () -> Unit)?) {
        scanAllCalls += ScanAllCall(fullRescan, onSuccess)
    }

    override fun scanFolders(folderIds: Set<Long>, fullRescan: Boolean) {
        scanFolderCalls += folderIds to fullRescan
    }

    override fun addFolderAndScan(path: String) {
        addedPaths += path
    }
}

/**
 * Waits for a condition that is produced by work on *real* dispatchers (Room/DataStore do real file
 * IO), so this polls instead of using virtual time. Prints the last observed value on timeout -- a
 * bare "condition not met" is not enough to debug a state flow.
 */
fun <T> awaitUntil(timeoutMillis: Long = 10_000L, describe: () -> T, condition: (T) -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    var last: T = describe()
    while (System.nanoTime() < deadline) {
        last = describe()
        if (condition(last)) return
        Thread.sleep(10L)
    }
    throw AssertionError("condition not met within ${timeoutMillis}ms; last value was $last")
}
