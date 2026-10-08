package com.lonx.lyrico.data.song.search

import com.lonx.lyrico.data.model.search.LocalSearchType
import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepositoryImpl
import com.lonx.lyrico.utils.LyricsSearchTextExtractor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioural tests for lyric search: the whole desktop lyric chain, end to end, on a real database.
 *
 * This is the test that matters for the ported decode stack. A raw `MATCH` query in SQLite only
 * matches what a previous stage put into the FTS4 table, so a passing search here means all of the
 * following worked on the JVM: `LyricDecoder` parsed the LRC/TTML text, `LyricsSearchTextExtractor`
 * turned it into searchable lines, `LyricFtsIndexer` tokenised and wrote the rows, and
 * `SongSearchRepositoryImpl` bound its `@RawQuery` placeholders correctly (`RoomRawQuery` replaced
 * Android's `SimpleSQLiteQuery`, where the binding API is different and mistakes are invisible).
 */
class SongSearchRepositoryTest {

    private lateinit var library: TestLibrary
    private lateinit var songs: SongLibraryRepository
    private lateinit var search: SongSearchRepository

    @BeforeTest
    fun setUp() {
        library = TestLibrary()
        songs = SongLibraryRepositoryImpl(library.database)
        search = SongSearchRepositoryImpl(library.database)
    }

    @AfterTest
    fun tearDown() {
        library.close()
    }

    @Test
    fun `finds a song by a word in its lyrics and reports the matching line`() = runBlocking<Unit> {
        val folderId = library.folder()
        songs.upsertSongs(
            listOf(
                library.song(
                    path = """H:\Music\hello.mp3""",
                    title = "Hello",
                    lyrics = "[00:01.00]instrumental intro\n[00:12.30]Hello, wonderful world",
                    folderId = folderId,
                )
            )
        )

        val results = search.searchLyricsForLocalSearch("wonderful").first()

        assertEquals(1, results.size)
        assertEquals("Hello", results.single().song.title)
        assertEquals("Hello, wonderful world", results.single().lyricLine)
    }

    @Test
    fun `matches CJK lyrics through the unicode61 tokenizer`() = runBlocking<Unit> {
        val folderId = library.folder()
        songs.upsertSongs(
            listOf(
                library.song(
                    path = """H:\Music\zhongwen.flac""",
                    title = "中文歌",
                    artist = "歌手",
                    lyrics = "[00:01.00]中文歌词第一行\n[00:02.00]English second line",
                    folderId = folderId,
                )
            )
        )

        val cjk = search.searchLyricsForLocalSearch("中文").first()
        assertEquals(listOf("中文歌词第一行"), cjk.map { it.lyricLine })

        val latin = search.searchLyricsForLocalSearch("second").first()
        assertEquals(listOf("English second line"), latin.map { it.lyricLine })
    }

    @Test
    fun `ranks the earliest matching line of each song and ignores non-lyric input`() = runBlocking<Unit> {
        val folderId = library.folder()
        songs.upsertSongs(
            listOf(
                library.song(
                    path = """H:\Music\a.mp3""",
                    title = "A",
                    lyrics = "[00:01.00]later hello\n[00:05.00]early hello",
                    folderId = folderId,
                )
            )
        )

        val results = search.searchLyricsForLocalSearch("hello").first()
        assertEquals(1, results.size, "one row per song, not one per matching line")
        assertEquals("later hello", results.single().lyricLine, "the first line of the song wins")

        // A query that tokenises to nothing must not reach SQLite as an invalid MATCH expression.
        assertTrue(search.searchLyricsForLocalSearch("   ").first().isEmpty())
        assertTrue(search.searchLyricsForLocalSearch("!!!").first().isEmpty())
    }

    @Test
    fun `query syntax characters cannot break the FTS match expression`() = runBlocking<Unit> {
        val folderId = library.folder()
        songs.upsertSongs(
            listOf(
                library.song(
                    path = """H:\Music\a.mp3""",
                    title = "A",
                    lyrics = """[00:01.00]a "quoted" line with * stars and (parens)""",
                    folderId = folderId,
                )
            )
        )

        // Unescaped, these would be FTS operators and would either throw or match everything.
        assertEquals(listOf("""a "quoted" line with * stars and (parens)"""), search.searchLyricsForLocalSearch("quoted").first().map { it.lyricLine })
        assertTrue(search.searchLyricsForLocalSearch("\" OR 1=1 --").first().isEmpty())
    }

    @Test
    fun `songs in ignored folders are excluded from lyric search`() = runBlocking<Unit> {
        val music = library.folder(path = """H:\Music""")
        val ignored = library.folder(path = """H:\Private""", isIgnored = true)
        songs.upsertSongs(
            listOf(
                library.song(path = """H:\Music\a.mp3""", title = "A", lyrics = "[00:01.00]shared word", folderId = music),
                library.song(path = """H:\Private\b.mp3""", title = "B", lyrics = "[00:01.00]shared word", folderId = ignored),
            )
        )

        val results = search.searchLyricsForLocalSearch("shared").first()
        assertEquals(listOf("A"), results.map { it.song.title })
    }

    @Test
    fun `rebuilds the lyric index for rows that never got one`() = runBlocking<Unit> {
        val folderId = library.folder()
        // Write through the DAO so the index is untouched — this is the state a database imported
        // from the Android app is in, and the state `lyricIndexEnabled = false` leaves behind.
        library.database.songDao().upsertAll(
            listOf(library.song(path = """H:\Music\a.mp3""", title = "A", lyrics = "[00:01.00]rebuild me", folderId = folderId))
        )
        assertEquals(0, library.database.songDao().getLyricFtsRowCount())

        search.rebuildMissingLyricSearchTextIndex()

        val stored = library.database.songDao().getSongByUri("""H:\Music\a.mp3""")
        assertEquals("rebuild me", stored?.lyricSearchText)
        assertEquals(listOf("rebuild me"), library.indexedLyricLines("""H:\Music\a.mp3"""))
        assertEquals(listOf("rebuild me"), search.searchLyricsForLocalSearch("rebuild").first().map { it.lyricLine })
    }

    @Test
    fun `rebuild leaves an already populated index alone`() = runBlocking<Unit> {
        val folderId = library.folder()
        songs.upsertSongs(
            listOf(library.song(path = """H:\Music\a.mp3""", title = "A", lyrics = "[00:01.00]indexed line", folderId = folderId))
        )

        search.rebuildMissingLyricSearchTextIndex()

        assertEquals(listOf("indexed line"), library.indexedLyricLines("""H:\Music\a.mp3"""))
        assertEquals(1, library.database.songDao().getLyricFtsRowCount(), "no duplicate rows after a rebuild")
    }

    @Test
    fun `searches songs by title, artist, album and file name`() = runBlocking<Unit> {
        val folderId = library.folder()
        songs.upsertSongs(
            listOf(
                library.song(
                    path = """H:\Music\01 - Kind of Blue.mp3""",
                    title = "So What",
                    artist = "Miles Davis",
                    album = "Kind of Blue",
                    folderId = folderId,
                )
            )
        )

        fun titles(query: String, type: LocalSearchType) =
            runBlocking { search.searchSongs(query, type).first().map { it.title } }

        assertEquals(listOf("So What"), titles("So What", LocalSearchType.TITLE))
        assertEquals(listOf("So What"), titles("Miles", LocalSearchType.ARTIST))
        assertEquals(listOf("So What"), titles("Kind", LocalSearchType.ALBUM))
        assertEquals(listOf("So What"), titles("01 - Kind", LocalSearchType.FILENAME))
        assertEquals(listOf("So What"), titles("Miles", LocalSearchType.ALL))
        assertEquals(emptyList(), titles("Nothing", LocalSearchType.ALL))
    }

    @Test
    fun `collects one value per distinct field, ordered by the input selection`() = runBlocking<Unit> {
        val folderId = library.folder()
        val a = """H:\Music\a.mp3"""
        val b = """H:\Music\b.mp3"""
        val c = """H:\Music\c.mp3"""
        songs.upsertSongs(
            listOf(
                library.song(path = a, title = "A", artist = "Miles Davis", album = "Kind of Blue", folderId = folderId),
                library.song(path = b, title = "B", artist = "Miles Davis", album = "Bitches Brew", folderId = folderId),
                library.song(path = c, title = "C", artist = "Bill Evans", album = "Kind of Blue", folderId = folderId),
            )
        )

        // Grouped by value (the duplicated "Miles Davis" appears once), ordered by first selection.
        val artists = search.getDistinctSongFieldValues(listOf(a, b, c), "artist")
        assertEquals(listOf("Miles Davis", "Bill Evans"), artists.map { it.value })
        assertEquals(listOf(a, c), artists.map { it.sourceUri })

        // Album is a different grouping of the same rows; the order follows the caller's list.
        assertEquals(
            listOf("Kind of Blue", "Bitches Brew"),
            search.getDistinctSongFieldValues(listOf(a, b, c), "album").map { it.value },
        )
        assertEquals(
            listOf("Bitches Brew", "Kind of Blue"),
            search.getDistinctSongFieldValues(listOf(b, a, c), "album").map { it.value },
        )

        assertEquals(emptyList(), search.getDistinctSongFieldValues(emptyList(), "artist"))
        assertEquals(
            emptyList(),
            search.getDistinctSongFieldValues(listOf(a), "notAColumn"),
            "unknown columns are rejected before reaching SQLite",
        )
    }

    @Test
    fun `reads a numeric field as text for distinct values`() = runBlocking<Unit> {
        val folderId = library.folder()
        val a = """H:\Music\a.mp3"""
        val b = """H:\Music\b.mp3"""
        songs.upsertSongs(
            listOf(
                library.song(path = a, title = "A", folderId = folderId).copy(discNumber = 1),
                library.song(path = b, title = "B", folderId = folderId).copy(discNumber = 2),
            )
        )

        val discs = search.getDistinctSongFieldValues(listOf(a, b), "discNumber")
        assertEquals(listOf("1", "2"), discs.map { it.value }, "the CAST branch must return text")
    }

    @Test
    fun `indexed search text skips timestamps and markup`() = runBlocking<Unit> {
        val folderId = library.folder()
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="00:00:01.000" end="00:00:03.000">First ttml line</p>
                <p begin="00:00:03.000" end="00:00:05.000">Second ttml line</p>
              </div></body>
            </tt>
        """.trimIndent()
        val path = """H:\Music\a.ttml.flac"""
        songs.upsertSongs(
            listOf(
                library.song(path = path, title = "A", lyrics = ttml, folderId = folderId)
                    // The scan/import path stores the extracted plain text next to the raw lyrics
                    // (`SongMetadataMapper`); the FTS lines are then written from this column.
                    .copy(lyricSearchText = LyricsSearchTextExtractor.toSearchText(ttml))
            )
        )

        val results = search.searchLyricsForLocalSearch("ttml").first()
        // Search reports one hit per song — the earliest matching line — so the second line is
        // verified through the index itself rather than through the search projection.
        assertEquals(listOf("First ttml line"), results.map { it.lyricLine })
        assertEquals(
            listOf("First ttml line", "Second ttml line"),
            library.indexedLyricLines("""H:\Music\a.ttml.flac"""),
        )

        val stored = library.database.songDao().getSongByUri(path)
        assertEquals(
            LyricsSearchTextExtractor.toSearchText(ttml),
            stored?.lyricSearchText,
            "the stored plain text must be exactly what the extractor produces",
        )
        assertEquals(
            listOf("First ttml line", "Second ttml line"),
            library.indexedLyricLines(path),
            "the index must be built from the timestamps-free text, not from the raw TTML",
        )
    }
}
