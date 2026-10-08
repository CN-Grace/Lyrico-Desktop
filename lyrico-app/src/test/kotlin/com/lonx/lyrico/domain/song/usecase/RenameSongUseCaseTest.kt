package com.lonx.lyrico.domain.song.usecase

import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.song.file.BatchSongFileOperationResult
import com.lonx.lyrico.data.song.file.DeleteSongFileResult
import com.lonx.lyrico.data.song.file.RenameSongFileResult
import com.lonx.lyrico.data.song.file.SongFileRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The rename use case is a pass-through, and that is the point.
 *
 * Renaming a song on Android was split between this use case (which re-keyed some rows) and the file
 * repository (which moved the file), and the split is what lost a renamed song from lyric search. On
 * desktop all of it is one repository transaction, so this use case must not grow its own half back:
 * any logic that appeared here would be logic the repository already applied, applied twice. All it
 * is allowed to do is hand the request down and hand the answer back.
 *
 * `SongFileRepositoryTest` proves the rename itself against real files and a real database. This
 * class only pins that nothing is added or lost in the hand-off.
 */
class RenameSongUseCaseTest {

    private class RecordingSongFileRepository(
        private val result: RenameSongFileResult
    ) : SongFileRepository {
        var receivedSong: SongEntity? = null
            private set
        var receivedFileName: String? = null
            private set

        override suspend fun deleteSong(song: SongEntity): DeleteSongFileResult =
            error("deleteSong is not part of this use case")

        override suspend fun deleteSongs(songs: List<SongEntity>): BatchSongFileOperationResult =
            error("deleteSongs is not part of this use case")

        override suspend fun renameSong(song: SongEntity, newFileName: String): RenameSongFileResult {
            receivedSong = song
            receivedFileName = newFileName
            return result
        }
    }

    private val song = SongEntity(
        folderId = 1L,
        mediaId = 0,
        source = SongSource.LOCAL,
        filePath = """H:\Music\bladeenc.mp3""",
        fileName = "bladeenc.mp3",
        title = "Blade Encoder",
        uri = """H:\Music\bladeenc.mp3""",
    )

    @Test
    fun `the song and the new name reach the repository unchanged`() = runBlocking<Unit> {
        val repository = RecordingSongFileRepository(
            RenameSongFileResult.Success(song = song, oldUri = song.uri)
        )

        RenameSongUseCase(repository)(song, "Renamed Song.mp3")

        assertEquals(song, repository.receivedSong)
        assertEquals("Renamed Song.mp3", repository.receivedFileName)
    }

    @Test
    fun `the repository's result is returned as it is`() = runBlocking<Unit> {
        val renamed = song.copy(fileName = "Renamed Song.mp3", uri = """H:\Music\Renamed Song.mp3""")
        val success = RenameSongFileResult.Success(song = renamed, oldUri = song.uri)

        val result = RenameSongUseCase(RecordingSongFileRepository(success))(song, "Renamed Song.mp3")

        // Same instance, not a copy: a re-wrapped result is a second type to keep in step.
        assertIs<RenameSongFileResult.Success>(result)
        assertEquals(renamed, result.song)
        assertEquals(song.uri, result.oldUri)
    }

    @Test
    fun `a conflict is not turned into a failure on the way up`() = runBlocking<Unit> {
        val result = RenameSongUseCase(
            RecordingSongFileRepository(RenameSongFileResult.NameConflict("Taken.mp3"))
        )(song, "Taken.mp3")

        // The UI has to be able to tell "that name is taken" from "the rename went wrong".
        assertEquals("Taken.mp3", assertIs<RenameSongFileResult.NameConflict>(result).targetName)
    }
}
