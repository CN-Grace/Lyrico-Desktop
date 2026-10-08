package com.lonx.lyrico.data

import androidx.room.RoomRawQuery
import com.lonx.lyrico.data.model.dao.LocalLyricSearchRow
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.entity.path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Behavioural tests for the ported library database: Room on the JVM with the bundled SQLite driver,
 * the FTS4 lyric index that Room cannot declare, and the two `@RawQuery` DAO entry points.
 *
 * These run against a real database file in a temp directory (not in-memory) so the `onOpen`
 * callback — which recreates the FTS table on every open — is exercised for real.
 */
class LyricoDatabaseTest {

    private lateinit var workingDir: File
    private lateinit var database: LyricoDatabase

    @BeforeTest
    fun setUp() {
        workingDir = Files.createTempDirectory("lyrico-db-test").toFile()
        database = openLyricoDatabase(workingDir)
    }

    @AfterTest
    fun tearDown() {
        database.close()
        workingDir.deleteRecursively()
    }

    @Test
    fun `stores and reads songs back`() = runBlocking {
        val songDao = database.songDao()
        val folderId = insertFolder()
        songDao.upsertAll(listOf(song(SONG_ONE_URI, title = "One", folderId = folderId)))

        assertEquals(1, songDao.getSongCount())
        val stored = assertNotNull(songDao.getSongByUri(SONG_ONE_URI), "song should be readable by uri")
        assertEquals("One", stored.title)
        assertEquals(SONG_ONE_URI, stored.path.toString(), "the uri column carries the absolute path")
        assertEquals(SONG_ONE_URI, stored.filePath)
    }

    @Test
    fun `creates the lyric fts table outside the room schema`() = runBlocking {
        // The table is created by the database callback, so it exists before any index write.
        assertEquals(0, database.songDao().getLyricFtsRowCount())
        database.songDao().clearLyricFts()
    }

    @Test
    fun `raw query returns mapped entities`() = runBlocking {
        val songDao = database.songDao()
        val folderId = insertFolder()
        songDao.upsertAll(listOf(song(SONG_ONE_URI, title = "One", folderId = folderId)))

        val songs = songDao.getSongs(RoomRawQuery("SELECT * FROM songs ORDER BY title ASC")).first()
        assertEquals(1, songs.size)
        assertEquals("One", songs.single().title)
    }

    @Test
    fun `raw query with bound arguments returns mapped rows`() = runBlocking {
        val songDao = database.songDao()
        val folderId = insertFolder()
        songDao.upsertAll(
            listOf(
                song(SONG_ONE_URI, title = "One", artist = "Alice", folderId = folderId),
                song(SONG_TWO_URI, title = "Two", artist = "Bob", folderId = folderId),
            )
        )

        // Binding by position, the way the ported search repository builds its queries.
        val values = songDao.getDistinctSongFieldValues(
            RoomRawQuery("SELECT uri AS sourceUri, artist AS value FROM songs WHERE artist = ?") { statement ->
                statement.bindText(1, "Bob")
            }
        )
        assertEquals(1, values.size)
        assertEquals("Bob", values.single().value)
    }

    @Test
    fun `lyric fts match finds latin prefixes and cjk phrases`() = runBlocking {
        val songDao = database.songDao()
        val folderId = insertFolder()
        songDao.upsertAll(
            listOf(
                song(SONG_ONE_URI, title = "One", artist = "Alice", folderId = folderId),
                song(SONG_TWO_URI, title = "Two", artist = "Bob", folderId = folderId),
            )
        )

        songDao.insertLyricFtsLine(SONG_ONE_URI, 0, "Hello wonderful world", "Hello wonderful world".toFtsIndexText())
        songDao.insertLyricFtsLine(SONG_ONE_URI, 1, "second line", "second line".toFtsIndexText())
        songDao.insertLyricFtsLine(SONG_TWO_URI, 0, "中文歌词", "中文歌词".toFtsIndexText())
        assertEquals(3, songDao.getLyricFtsRowCount())

        val latin = songDao.searchLyricFtsForLocalSearch(localLyricSearchQuery("Hello*")).first()
        assertEquals(1, latin.size, "latin prefix search should hit the first song")
        assertEquals(SONG_ONE_URI, latin.single().uri)
        assertEquals("Hello wonderful world", latin.single().matchedLine)

        val cjk = songDao.searchLyricFtsForLocalSearch(localLyricSearchQuery("\"中 文\"")).first()
        assertEquals(1, cjk.size, "cjk phrase search should hit the second song")
        assertEquals(SONG_TWO_URI, cjk.single().uri)
        assertEquals("中文歌词", cjk.single().matchedLine)
    }

    @Test
    fun `ignored folders are excluded from lyric search`() = runBlocking {
        val songDao = database.songDao()
        val folderId = insertFolder(isIgnored = true)
        songDao.upsertAll(listOf(song(SONG_ONE_URI, title = "One", folderId = folderId)))
        songDao.insertLyricFtsLine(SONG_ONE_URI, 0, "Hello world", "Hello world".toFtsIndexText())

        val hits = songDao.searchLyricFtsForLocalSearch(localLyricSearchQuery("Hello*")).first()
        assertEquals(emptyList<LocalLyricSearchRow>(), hits)
    }

    @Test
    fun `songs and the lyric index survive reopening the database`() = runBlocking {
        val songDao = database.songDao()
        val folderId = insertFolder()
        songDao.upsertAll(listOf(song(SONG_ONE_URI, title = "One", folderId = folderId)))
        songDao.insertLyricFtsLine(SONG_ONE_URI, 0, "Hello world", "Hello world".toFtsIndexText())

        database.close()
        database = openLyricoDatabase(workingDir)

        assertEquals(1, database.songDao().getSongCount())
        assertEquals(1, database.songDao().getLyricFtsRowCount())
        assertEquals(
            1,
            database.songDao().searchLyricFtsForLocalSearch(localLyricSearchQuery("Hello*")).first().size,
        )
    }

    // --- helpers ------------------------------------------------------------------------------

    private suspend fun insertFolder(isIgnored: Boolean = false): Long = database.folderDao()
        .insert(
            FolderEntity(
                path = """H:\Music""",
                isIgnored = isIgnored,
            )
        )

    private fun song(
        uri: String,
        title: String,
        artist: String? = null,
        folderId: Long,
    ) = SongEntity(
        folderId = folderId,
        mediaId = 0,
        filePath = uri,
        fileName = uri.substringAfterLast('\\'),
        title = title,
        artist = artist,
        uri = uri,
    )

    /**
     * The lyric search query exactly as the search repository builds it, including the
     * `song_lyric_lines_fts` join and the `isIgnored` filter. It has to list every column of
     * [LocalLyricSearchRow] because the raw query bypasses Room's query validation.
     */
    private fun localLyricSearchQuery(ftsQuery: String, limit: Int = 50): RoomRawQuery {
        return RoomRawQuery(
            sql = """
                SELECT
                    fts.lineText AS matchedLine,
                    s.id AS id,
                    s.folderId AS folderId,
                    s.mediaId AS mediaId,
                    s.source AS source,
                    s.filePath AS filePath,
                    s.fileName AS fileName,
                    s.fileSize AS fileSize,
                    s.fileExtension AS fileExtension,
                    s.title AS title,
                    s.artist AS artist,
                    s.albumArtist AS albumArtist,
                    s.discNumber AS discNumber,
                    s.composer AS composer,
                    s.lyricist AS lyricist,
                    s.comment AS comment,
                    s.album AS album,
                    s.genre AS genre,
                    s.language AS language,
                    s.trackerNumber AS trackerNumber,
                    s.date AS date,
                    s.copyright AS copyright,
                    s.rating AS rating,
                    s.replayGainTrackGain AS replayGainTrackGain,
                    s.replayGainTrackPeak AS replayGainTrackPeak,
                    s.replayGainAlbumGain AS replayGainAlbumGain,
                    s.replayGainAlbumPeak AS replayGainAlbumPeak,
                    s.replayGainReferenceLoudness AS replayGainReferenceLoudness,
                    s.durationMilliseconds AS durationMilliseconds,
                    s.bitrate AS bitrate,
                    s.sampleRate AS sampleRate,
                    s.channels AS channels,
                    s.fileLastModified AS fileLastModified,
                    s.fileAdded AS fileAdded,
                    s.dbUpdateTime AS dbUpdateTime,
                    s.titleGroupKey AS titleGroupKey,
                    s.titleSortKey AS titleSortKey,
                    s.artistGroupKey AS artistGroupKey,
                    s.artistSortKey AS artistSortKey,
                    s.albumGroupKey AS albumGroupKey,
                    s.albumSortKey AS albumSortKey,
                    s.uri AS uri
                FROM (
                    SELECT
                        songUri,
                        MIN(CAST(lineIndex AS INTEGER)) AS firstMatchIndex
                    FROM song_lyric_lines_fts
                    WHERE song_lyric_lines_fts MATCH ?
                    GROUP BY songUri
                ) AS best
                INNER JOIN song_lyric_lines_fts AS fts
                    ON fts.songUri = best.songUri
                    AND CAST(fts.lineIndex AS INTEGER) = best.firstMatchIndex
                INNER JOIN songs AS s ON s.uri = fts.songUri
                INNER JOIN folders AS f ON s.folderId = f.id
                WHERE f.isIgnored = 0
                ORDER BY s.title ASC, s.fileName ASC, fts.lineIndex ASC
                LIMIT ?
            """.trimIndent(),
        ) { statement ->
            statement.bindText(1, ftsQuery)
            statement.bindInt(2, limit)
        }
    }

    private companion object {
        const val SONG_ONE_URI = """H:\Music\one.flac"""
        const val SONG_TWO_URI = """H:\Music\two.flac"""

        /**
         * Mirrors the index text built by `LyricFtsIndexer` (each CJK character becomes its own
         * token, latin words are kept whole). The indexer itself is ported with the lyrics
         * subsystem; until then the convention is repeated here, and this test is the place that
         * pins the FTS4 `unicode61` behaviour it depends on.
         */
        fun String.toFtsIndexText(): String = buildString {
            for (ch in this@toFtsIndexText) {
                val code = ch.code
                val isCjk = code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF ||
                    code in 0xF900..0xFAFF || code in 0x3040..0x30FF || code in 0xAC00..0xD7AF
                if (isCjk) {
                    append(' ')
                    append(ch)
                    append(' ')
                } else {
                    append(ch)
                }
            }
        }.replace(Regex("\\s+"), " ").trim()
    }
}
