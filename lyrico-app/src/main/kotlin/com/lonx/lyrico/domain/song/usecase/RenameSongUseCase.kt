package com.lonx.lyrico.domain.song.usecase

import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.song.file.RenameSongFileResult
import com.lonx.lyrico.data.song.file.SongFileRepository

/**
 * Renames a song file.
 *
 * As with [DeleteSongsUseCase], the work this use case used to do moved down a layer on desktop: the
 * rename, the row update, the FTS and custom-tag re-keying, the folder count and the index pruning
 * are one transaction inside `SongFileRepository.renameSong`. Renaming that half-transaction is
 * exactly what used to lose a song from lyric search, so it is deliberately not split again here.
 *
 * All this adds is one domain-level entry point, and the result type the view models were written
 * against — which is why [RenameSongResult] is an alias for the repository's own result rather than a
 * parallel hierarchy that would be a second thing to keep in step.
 */
class RenameSongUseCase(
    private val songFileRepository: SongFileRepository
) {
    suspend operator fun invoke(
        song: SongEntity,
        newFileName: String
    ): RenameSongResult = songFileRepository.renameSong(song, newFileName)
}

typealias RenameSongResult = RenameSongFileResult
