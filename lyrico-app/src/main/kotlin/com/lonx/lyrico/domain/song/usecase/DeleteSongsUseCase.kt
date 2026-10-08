package com.lonx.lyrico.domain.song.usecase

import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.song.file.BatchSongFileOperationItem
import com.lonx.lyrico.data.song.file.SongFileRepository

/**
 * Deletes songs — the files and their rows together.
 *
 * On Android this was a substantial use case, because it sequenced work the layer below could not do
 * at once: `SongFileRepository` deleted the files, and the transaction that removed the rows, the
 * custom tag keys, the folder counts and then pruned the search indexes lived here.
 *
 * On desktop that transaction is inside `SongFileRepository.deleteSongs` (its KDoc explains why the
 * split was the source of rows being left behind). What is left here is the empty-input guard, the
 * `total`/`deleted` summary the UI reports, and a single domain-level entry point for the view
 * models — not a second implementation of the delete.
 */
class DeleteSongsUseCase(
    private val songFileRepository: SongFileRepository
) {
    suspend operator fun invoke(songs: List<SongEntity>): DeleteSongsResult {
        if (songs.isEmpty()) {
            return DeleteSongsResult(total = 0, deleted = 0, items = emptyList())
        }

        val fileResult = songFileRepository.deleteSongs(songs)

        return DeleteSongsResult(
            total = songs.size,
            deleted = fileResult.deleted,
            items = fileResult.items.map { item -> DeleteSongsItemResult(item.song, item.result) }
        )
    }
}

data class DeleteSongsResult(
    val total: Int,
    val deleted: Int,
    val items: List<DeleteSongsItemResult>
)

/**
 * One song's outcome. An alias rather than a copy: [BatchSongFileOperationItem] is this same pair,
 * and a parallel declaration would be one more thing to keep in step for no gain.
 */
typealias DeleteSongsItemResult = BatchSongFileOperationItem
