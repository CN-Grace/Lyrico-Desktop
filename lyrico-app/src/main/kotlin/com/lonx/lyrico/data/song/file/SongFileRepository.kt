package com.lonx.lyrico.data.song.file

import com.lonx.lyrico.data.model.entity.SongEntity

/**
 * Deletes and renames song files, and keeps the database in step with the disk.
 *
 * Android split this in two: the file repository moved the file, and a domain use case
 * (`DeleteSongsUseCase` / `RenameSongUseCase`) updated the database afterwards. That split is where
 * rows were left behind — a rename moved `songs.uri`, `filePath` and `fileName` but nothing moved
 * the tables that are *keyed* by the uri (the lyric FTS rows and `song_custom_tag_keys`), so a
 * renamed song disappeared from lyric search and from custom-tag filters.
 *
 * On desktop the file operation and every database row it implies are one operation on purpose: a
 * caller cannot perform half of it. The transaction spans the song row, the FTS rows, the custom tag
 * keys and the artist/album index.
 */
interface SongFileRepository {

    /** Deletes one song: the file on disk and its rows. */
    suspend fun deleteSong(song: SongEntity): DeleteSongFileResult

    /** Deletes several songs, carrying on when one of them fails. */
    suspend fun deleteSongs(songs: List<SongEntity>): BatchSongFileOperationResult

    /**
     * Renames a song file inside its own folder and updates its rows.
     *
     * [newFileName] is a bare file name, never a path: a rename cannot move a song to another
     * directory.
     */
    suspend fun renameSong(song: SongEntity, newFileName: String): RenameSongFileResult
}
