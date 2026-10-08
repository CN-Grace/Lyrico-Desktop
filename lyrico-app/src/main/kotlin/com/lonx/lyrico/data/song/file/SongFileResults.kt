package com.lonx.lyrico.data.song.file

import com.lonx.lyrico.data.model.entity.SongEntity

/**
 * Outcome of deleting one song.
 *
 * Android had a `PermissionRequired(IntentSender)` case, because deleting a `MediaStore` row could
 * need the user to confirm through the system dialog. On Windows the process either may delete the
 * file or not, and a refusal (`AccessDeniedException`, a file being written by another program) is a
 * [Failed] with the reason attached — there is nothing to ask the user for.
 */
sealed interface DeleteSongFileResult {
    /** The file was there and is gone. */
    data object Deleted : DeleteSongFileResult

    /**
     * The file was already gone when the delete ran (removed outside the app, an unplugged drive).
     *
     * The database row is still removed: a row pointing at a file that no longer exists can only
     * produce broken playback, so the caller treats this as a successful delete. Kept as a distinct
     * case so the UI can say "already missing" instead of "deleted".
     */
    data object AlreadyMissing : DeleteSongFileResult

    data class Failed(
        val throwable: Throwable
    ) : DeleteSongFileResult
}

data class BatchSongFileOperationResult(
    val items: List<BatchSongFileOperationItem>
) {
    /** Songs whose row is gone: deleted now, or already missing on disk. */
    val deleted: Int
        get() = items.count { it.result is DeleteSongFileResult.Deleted || it.result is DeleteSongFileResult.AlreadyMissing }

    val failed: Int
        get() = items.count { it.result is DeleteSongFileResult.Failed }
}

data class BatchSongFileOperationItem(
    val song: SongEntity,
    val result: DeleteSongFileResult
)

/**
 * Outcome of renaming one song.
 *
 * As with [DeleteSongFileResult], the `PermissionRequired` case of the Android version is gone.
 */
sealed interface RenameSongFileResult {
    /**
     * The file and the database agree on the new name.
     *
     * [song] is the row as stored: new uri, path, file name and sort keys. [oldUri] is what
     * `songs.uri` was before, for callers that keyed anything off it.
     */
    data class Success(
        val song: SongEntity,
        val oldUri: String
    ) : RenameSongFileResult

    /** A different file already has the requested name; nothing was changed. */
    data class NameConflict(
        val targetName: String
    ) : RenameSongFileResult

    /** The name was refused (blank, a path, `..`) or the filesystem or database refused the change. */
    data class Failed(
        val throwable: Throwable
    ) : RenameSongFileResult
}
