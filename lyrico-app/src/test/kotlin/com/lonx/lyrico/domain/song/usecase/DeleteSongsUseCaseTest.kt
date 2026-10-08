package com.lonx.lyrico.domain.song.usecase

import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.song.file.BatchSongFileOperationItem
import com.lonx.lyrico.data.song.file.BatchSongFileOperationResult
import com.lonx.lyrico.data.song.file.DeleteSongFileResult
import com.lonx.lyrico.data.song.file.RenameSongFileResult
import com.lonx.lyrico.data.song.file.SongFileRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the delete use case adds on top of the repository — nothing more.
 *
 * On desktop the transaction that removes the rows, the custom tag keys, the folder counts and the
 * pruned search indexes lives inside `SongFileRepository.deleteSongs` (see `SongFileRepositoryTest`
 * for that, against real files). So this class deliberately does *not* re-test the deletion. It pins
 * the three things the use case itself is responsible for: not calling the repository for an empty
 * request, reporting the request size and the outcome as two separate numbers, and passing the
 * repository's per-song results through unchanged.
 *
 * The repository here is a recording fake because those are exactly the properties a fake can
 * observe — which songs were handed over, and what came back.
 */
class DeleteSongsUseCaseTest {

    private class RecordingSongFileRepository(
        private val result: BatchSongFileOperationResult = BatchSongFileOperationResult(emptyList())
    ) : SongFileRepository {
        var deleteSongsCalls = 0
            private set
        var received: List<SongEntity>? = null
            private set

        override suspend fun deleteSong(song: SongEntity): DeleteSongFileResult =
            error("deleteSong is not part of this use case")

        override suspend fun deleteSongs(songs: List<SongEntity>): BatchSongFileOperationResult {
            deleteSongsCalls++
            received = songs
            return result
        }

        override suspend fun renameSong(song: SongEntity, newFileName: String): RenameSongFileResult =
            error("renameSong is not part of this use case")
    }

    private fun song(uri: String): SongEntity = SongEntity(
        folderId = 1L,
        mediaId = 0,
        source = SongSource.LOCAL,
        filePath = uri,
        fileName = uri.substringAfterLast('/'),
        uri = uri,
    )

    @Test
    fun `deleting nothing reports nothing and never touches the repository`() = runBlocking<Unit> {
        val repository = RecordingSongFileRepository()

        val result = DeleteSongsUseCase(repository)(emptyList())

        assertEquals(0, result.total)
        assertEquals(0, result.deleted)
        assertTrue(result.items.isEmpty())
        assertEquals(0, repository.deleteSongsCalls, "an empty request must not reach the repository")
    }

    @Test
    fun `total counts what was asked for, not what the repository managed`() = runBlocking<Unit> {
        val asked = listOf(song("""H:\Music\a.mp3"""), song("""H:\Music\b.mp3"""), song("""H:\Music\c.mp3"""))
        // One file was locked by another program: the repository reports two of the three as gone.
        val repository = RecordingSongFileRepository(
            BatchSongFileOperationResult(
                listOf(
                    BatchSongFileOperationItem(asked[0], DeleteSongFileResult.Deleted),
                    BatchSongFileOperationItem(asked[1], DeleteSongFileResult.Failed(IllegalStateException("locked"))),
                    BatchSongFileOperationItem(asked[2], DeleteSongFileResult.AlreadyMissing),
                )
            )
        )

        val result = DeleteSongsUseCase(repository)(asked)

        assertEquals(3, result.total, "the UI reports how many were asked for")
        assertEquals(2, result.deleted, "and how many are actually gone")
    }

    @Test
    fun `the songs handed over are the songs that were asked for`() = runBlocking<Unit> {
        val repository = RecordingSongFileRepository()
        val asked = listOf(song("""H:\Music\a.mp3"""), song("""H:\Music\b.mp3"""))

        DeleteSongsUseCase(repository)(asked)

        assertEquals(1, repository.deleteSongsCalls)
        assertEquals(asked, repository.received, "no song may be dropped or reordered on the way down")
    }

    @Test
    fun `per-song outcomes are passed through in the repository's order`() = runBlocking<Unit> {
        val asked = listOf(song("""H:\Music\a.mp3"""), song("""H:\Music\b.mp3"""))
        val failure = IllegalStateException("access denied")
        val repository = RecordingSongFileRepository(
            BatchSongFileOperationResult(
                listOf(
                    BatchSongFileOperationItem(asked[1], DeleteSongFileResult.Failed(failure)),
                    BatchSongFileOperationItem(asked[0], DeleteSongFileResult.Deleted),
                )
            )
        )

        val result = DeleteSongsUseCase(repository)(asked)

        assertEquals(2, result.items.size)
        assertEquals(asked[1], result.items[0].song)
        assertEquals(failure, (result.items[0].result as DeleteSongFileResult.Failed).throwable)
        assertEquals(asked[0], result.items[1].song)
        assertEquals(DeleteSongFileResult.Deleted, result.items[1].result)
    }

    @Test
    fun `a song that was already gone still counts as deleted`() = runBlocking<Unit> {
        val asked = listOf(song("""H:\Music\a.mp3"""))
        val repository = RecordingSongFileRepository(
            BatchSongFileOperationResult(
                listOf(BatchSongFileOperationItem(asked[0], DeleteSongFileResult.AlreadyMissing))
            )
        )

        val result = DeleteSongsUseCase(repository)(asked)

        // The row is gone either way, so from the library's point of view this delete succeeded.
        assertEquals(1, result.deleted)
    }
}
