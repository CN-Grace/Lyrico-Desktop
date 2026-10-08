package com.lonx.lyrico.domain.song.usecase

import com.lonx.audiotag.model.AudioTagData
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.repository.CustomTagKeyRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.mapper.SongMetadataMapper
import com.lonx.lyrico.data.song.search.LyricFtsIndexer
import com.lonx.lyrico.data.song.tag.AudioTagMutation
import com.lonx.lyrico.data.song.tag.AudioTagMutationMode
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.data.song.tag.AudioTagWriteResult
import com.lonx.lyrico.data.utils.inTransaction

class SaveAudioTagsUseCase(
    private val database: LyricoDatabase,
    private val songLibraryRepository: SongLibraryRepository,
    private val audioTagRepository: AudioTagRepository,
    private val customTagKeyRepository: CustomTagKeyRepository,
    private val libraryIndexRepository: LibraryIndexRepository,
    private val songMetadataMapper: SongMetadataMapper
) {
    suspend operator fun invoke(
        uri: String,
        mutation: AudioTagMutation
    ): SaveAudioTagsResult {
        val writeResult = when (mutation.mode) {
            AudioTagMutationMode.Overwrite -> audioTagRepository.overwrite(uri, mutation)
            AudioTagMutationMode.Patch -> audioTagRepository.patch(uri, mutation)
        }

        return when (writeResult) {
            is AudioTagWriteResult.Success -> {
                val savedData = writeResult.savedData
                val song = songLibraryRepository.getSongByUri(uri)
                val updatedSong = if (song != null) {
                    val updated = songMetadataMapper.applyAudioTagData(
                        old = song,
                        tag = savedData
                    )

                    database.inTransaction {
                        database.songDao().update(updated)
                        // The lyric index is keyed by uri and holds the *parsed lines* of the lyrics,
                        // so writing `songs.lyrics` is not enough to keep it in step: without this the
                        // edited lyrics are not searchable and the replaced ones still are. Android
                        // left this out -- `reindexSongInTransaction` only rebuilds the artist/album
                        // index -- which is why every other write path in this port
                        // (`SongLibraryRepositoryImpl.updateSong`, `SongFileRepositoryImpl.renameSong`)
                        // refreshes it explicitly and so does this one.
                        LyricFtsIndexer.replaceSong(database.songDao(), updated)
                        customTagKeyRepository.replaceForSong(updated.uri, savedData.customFields)
                        libraryIndexRepository.reindexSongInTransaction(updated)
                    }
                    updated
                } else {
                    null
                }

                SaveAudioTagsResult.Success(
                    song = updatedSong,
                    tagData = savedData
                )
            }
            is AudioTagWriteResult.Failed -> SaveAudioTagsResult.Failed(writeResult.error)
        }
    }
}

/**
 * Outcome of a tag write.
 *
 * Android had a `PermissionRequired(IntentSender)` case: a SAF write could need the user to grant
 * access through a system dialog. Windows has no such flow -- the write either happened or it is a
 * [Failed] carrying the underlying reason -- so the case is gone, mirroring the ported
 * `AudioTagWriteResult` it is translated from.
 */
sealed interface SaveAudioTagsResult {
    data class Success(
        val song: SongEntity?,
        val tagData: AudioTagData
    ) : SaveAudioTagsResult

    data class Failed(
        val error: Throwable
    ) : SaveAudioTagsResult
}
