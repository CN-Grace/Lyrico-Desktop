package com.lonx.lyrico.data.song.tag

import com.lonx.audiotag.model.AudioTagData

/**
 * Result of a tag write.
 *
 * The Android version had a third case, `PermissionRequired(IntentSender)`, because SAF writes could
 * need the user to grant access through the system file picker. On Windows the process either may
 * write the file or not: a read-only or locked file comes back as [Failed] with the underlying
 * `IOException`/`AccessDeniedException`, so there is nothing to ask the user for and no
 * permission-resolution flow to carry over.
 */
sealed interface AudioTagWriteResult {
    data class Success(
        val savedData: AudioTagData
    ) : AudioTagWriteResult

    data class Failed(
        val error: Throwable
    ) : AudioTagWriteResult
}
