package com.lonx.lyrico.data.support

import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.SongSource
import androidx.room.RoomRawQuery
import com.lonx.lyrico.data.openLyricoDatabase
import java.io.File
import java.nio.file.Files

/**
 * A real, file-backed library database in a temp directory, shared by the P3 repository tests.
 *
 * The ported repositories are thin wrappers over Room; what can actually go wrong at this layer is
 * the dialect and transaction handling (FTS4 tables Room cannot declare, `@RawQuery` argument
 * binding, `inTransaction` on the JVM driver) — none of which an in-memory fake would exercise. The
 * database is therefore opened exactly the way the app opens it, on a real file, and the tests
 * insert real rows through the real DAOs.
 */
class TestLibrary {

    val workingDir: File = Files.createTempDirectory("lyrico-library-test").toFile()
    var database: LyricoDatabase = openLyricoDatabase(workingDir)
        private set

    /** Closes and reopens the same database file, to prove rows survive a process-lifetime boundary. */
    fun reopen() {
        database.close()
        database = openLyricoDatabase(workingDir)
    }

    fun close() {
        database.close()
        workingDir.deleteRecursively()
    }

    /**
     * Reads the indexed lyric lines of [uri] straight out of the FTS4 table.
     *
     * The tests inspect the index through raw SQLite rather than through the search repository, so a
     * passing assertion means the row is really in the table and not just visible through the same
     * code path that wrote it. `lineIndex` is stored as text, hence the `CAST`.
     */
    suspend fun indexedLyricLines(uri: String): List<String> {
        // Two aliases because the DAO projects a `SongFieldValue`; the ORDER BY keeps the lines in
        // their original position even though fts4 does not preserve insertion order.
        val query = RoomRawQuery(
            "SELECT songUri AS sourceUri, lineText AS value FROM song_lyric_lines_fts " +
                "WHERE songUri = ? ORDER BY CAST(lineIndex AS INTEGER)"
        ) { statement -> statement.bindText(1, uri) }
        return database.songDao().getDistinctSongFieldValues(query).map { it.value }
    }

    /** A library folder. `path` is a Windows-style absolute path, the desktop identity of a root. */
    suspend fun folder(path: String = """H:\Music""", isIgnored: Boolean = false): Long =
        database.folderDao().insert(FolderEntity(path = path, isIgnored = isIgnored))

    fun song(
        path: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        albumArtist: String? = null,
        lyrics: String? = null,
        folderId: Long,
        fileSize: Long = 1_000L,
        fileLastModified: Long = 1_700_000_000_000L,
        durationMilliseconds: Int = 180_000,
    ): SongEntity = SongEntity(
        folderId = folderId,
        mediaId = 0,
        source = SongSource.LOCAL,
        filePath = path,
        fileName = path.substringAfterLast('\\'),
        fileSize = fileSize,
        fileLastModified = fileLastModified,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        lyrics = lyrics,
        durationMilliseconds = durationMilliseconds,
        uri = path,
    )
}
