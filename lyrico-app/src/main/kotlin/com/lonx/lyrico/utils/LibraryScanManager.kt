package com.lonx.lyrico.utils

import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.scan.LibraryScanProgress
import com.lonx.lyrico.data.song.scan.LibraryScanRequest
import com.lonx.lyrico.domain.song.usecase.SynchronizeLibraryUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryScanState(
    val isScanning: Boolean = false,
    val progress: LibraryScanProgress? = null,
    val scanningFolderIds: Set<Long> = emptySet(),
    val queuedFolderIds: Set<Long> = emptySet(),
    val queuedScanCount: Int = 0,
    val error: String? = null
)

/**
 * Runs library scans one at a time and reports progress, for however many screens ask for one.
 *
 * The queue exists because scans compete for the same database writer and the same disk; running two
 * at once would interleave their progress readings and let a per-folder scan and a full scan write
 * over each other. Requests that arrive while a scan is running are merged (see
 * [mergePendingRequest]) rather than queued blindly.
 *
 * Two Android responsibilities are gone:
 *
 * - **`Context`**, which existed only to release persisted SAF permissions. There are no persisted
 *   permissions on Windows, and the folder bookkeeping that release depended on — normalising the
 *   path, collapsing a folder into an existing parent, dropping folders a new parent subsumes — now
 *   lives in `FolderDao.upsertAndGetId`, where it applies to the rows themselves rather than to a
 *   grant.
 * - **`addFolderAndScan(path, treeUri)`**: there is no tree uri to record. `addedBySaf` is still
 *   written, because on desktop it means "the user added this root explicitly" and that is what
 *   `getLibraryRootFolders` selects on.
 */
interface LibraryScanManager {
    val state: StateFlow<LibraryScanState>

    fun scanAll(
        fullRescan: Boolean = false,
        onSuccess: (suspend () -> Unit)? = null
    )

    fun scanFolders(folderIds: Set<Long>, fullRescan: Boolean = false)

    fun addFolderAndScan(path: String)
}

class LibraryScanManagerImpl(
    private val appScope: CoroutineScope,
    private val database: LyricoDatabase,
    private val settingsRepository: SettingsRepository,
    private val synchronizeLibraryUseCase: SynchronizeLibraryUseCase
) : LibraryScanManager {

    private val folderDao = database.folderDao()
    private val _state = MutableStateFlow(LibraryScanState())
    override val state: StateFlow<LibraryScanState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private val pendingRequests = ArrayDeque<ScanRequest>()
    private val queueLock = Any()

    override fun scanAll(
        fullRescan: Boolean,
        onSuccess: (suspend () -> Unit)?
    ) {
        enqueueScan(
            ScanRequest(
                fullRescan = fullRescan,
                folderIds = null,
                onSuccessActions = listOfNotNull(onSuccess)
            )
        )
    }

    override fun scanFolders(folderIds: Set<Long>, fullRescan: Boolean) {
        if (folderIds.isEmpty()) return
        enqueueScan(ScanRequest(fullRescan = fullRescan, folderIds = folderIds))
    }

    override fun addFolderAndScan(path: String) {
        appScope.launch {
            val id = folderDao.upsertAndGetId(path = path, addedBySaf = true)
            folderDao.setIgnored(id, false)
            enqueueScan(ScanRequest(fullRescan = false, folderIds = setOf(id)))
        }
    }

    /**
     * Adds a request to the queue, merging it with what is already waiting.
     *
     * Merging is what keeps a burst of requests from turning into a burst of scans: the UI can ask
     * for three folders in three calls and get one scan covering all three.
     *
     * A whole-library request wins outright, because it covers every folder a pending per-folder
     * request could name. Otherwise, pending per-folder requests that agree on `fullRescan` are
     * combined — the flag cannot be merged, since re-reading tags for the union of two folders is not
     * the same as doing it for one of them.
     *
     * This only ever concerns requests that are *waiting*. A request that arrives while a scan is
     * already running is queued even when that scan is a whole-library one: the running scan may have
     * listed the folders before this request's folder existed, so absorbing it could silently skip a
     * scan the user asked for. One redundant scan is the cheaper mistake.
     */
    private fun enqueueScan(request: ScanRequest) {
        synchronized(queueLock) {
            mergePendingRequest(request)
            updateQueuedState()

            if (scanJob?.isActive != true) {
                scanJob = appScope.launch { processQueue() }
            }
        }
    }

    private fun mergePendingRequest(request: ScanRequest) {
        if (request.folderIds == null) {
            pendingRequests.clear()
            pendingRequests.addLast(request)
            return
        }

        if (pendingRequests.any { it.folderIds == null }) return

        val existingIndex = pendingRequests.indexOfFirst {
            it.folderIds != null && it.fullRescan == request.fullRescan
        }
        if (existingIndex >= 0) {
            val existing = pendingRequests[existingIndex]
            pendingRequests[existingIndex] = existing.copy(
                folderIds = existing.folderIds.orEmpty() + request.folderIds,
                onSuccessActions = existing.onSuccessActions + request.onSuccessActions
            )
        } else {
            pendingRequests.addLast(request)
        }
    }

    private suspend fun processQueue() {
        while (true) {
            val request = synchronized(queueLock) {
                if (pendingRequests.isEmpty()) {
                    scanJob = null
                    null
                } else {
                    pendingRequests.removeFirst()
                }
            } ?: break

            updateQueuedState()
            _state.update {
                it.copy(
                    isScanning = true,
                    progress = null,
                    scanningFolderIds = request.folderIds.orEmpty(),
                    error = null
                )
            }

            try {
                synchronizeLibraryUseCase(
                    request = LibraryScanRequest(
                        fullRescan = request.fullRescan,
                        folderIds = request.folderIds,
                        ignoreShortAudio = settingsRepository.ignoreShortAudio.first()
                    ),
                    onProgress = { progress ->
                        _state.update { it.copy(progress = progress) }
                    }
                )
                request.onSuccessActions.forEach { action -> action() }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e::class.java.simpleName) }
            } finally {
                _state.update {
                    it.copy(
                        isScanning = false,
                        progress = null,
                        scanningFolderIds = emptySet()
                    )
                }
            }
        }
    }

    private fun updateQueuedState() {
        val (queuedCount, queuedFolderIds) = synchronized(queueLock) {
            pendingRequests.size to pendingRequests
                .flatMap { request -> request.folderIds.orEmpty() }
                .toSet()
        }
        _state.update {
            it.copy(
                queuedScanCount = queuedCount,
                queuedFolderIds = queuedFolderIds
            )
        }
    }

    private data class ScanRequest(
        val fullRescan: Boolean,
        val folderIds: Set<Long>?,
        val onSuccessActions: List<suspend () -> Unit> = emptyList()
    )
}
