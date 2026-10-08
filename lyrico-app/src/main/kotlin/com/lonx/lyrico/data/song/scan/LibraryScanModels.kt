package com.lonx.lyrico.data.song.scan

/**
 * What a scan should do. Produced by the UI (a full rescan button, a per-folder rescan, a rescan
 * after adding songs).
 */
data class LibraryScanRequest(
    /** Re-read tags for every file even when size and modification time are unchanged. */
    val fullRescan: Boolean,
    /** Scan only these roots (and their subfolders); `null` scans every library root. */
    val folderIds: Set<Long>? = null,
    /**
     * Drop a library root — and the songs under it — when its directory cannot be found.
     *
     * Android's equivalent (`removeMissingSafFolders`) deleted the songs of a folder whose SAF
     * permission had been revoked, because a revoked grant means the folder is genuinely gone from
     * the app's point of view. A Windows library root is often a removable or network drive, so a
     * missing directory usually means "not plugged in right now", and deleting the library would be
     * destructive. The default is therefore to keep the songs and report a failure instead; the
     * behaviour can still be asked for explicitly.
     */
    val removeUnavailableFolders: Boolean = false,
    /** Skip files whose duration is at most one minute. */
    val ignoreShortAudio: Boolean
)

data class LibraryScanProgress(
    val stage: LibraryScanStage,
    val current: Int = 0,
    val total: Int = 0,
    val currentFile: String? = null
)

enum class LibraryScanStage {
    LISTING_FILES,
    READING_METADATA,
    WRITING_DATABASE,
    FINISHED
}

data class LibraryScanResult(
    val scanned: Int,
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val skipped: Int,
    val failures: List<LibraryScanFailure> = emptyList()
)

data class LibraryScanFailure(
    /** The file path (or the root folder path) the failure is about. */
    val path: String?,
    val fileName: String?,
    val stage: LibraryScanFailureStage,
    val message: String,
    val throwable: Throwable? = null
)

enum class LibraryScanFailureStage {
    Collecting,
    ReadingMetadata,
    WritingDatabase
}
