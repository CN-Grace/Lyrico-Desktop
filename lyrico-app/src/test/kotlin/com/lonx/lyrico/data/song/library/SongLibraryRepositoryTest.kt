package com.lonx.lyrico.data.song.library

import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.data.song.mapper.SortKeyUpdater
import com.lonx.lyrico.viewmodel.SortBy
import com.lonx.lyrico.viewmodel.SortOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for the library repository that writes songs.
 *
 * This is the layer where the desktop port had to change behaviour-carrying code rather than move
 * it: every mutation wraps the DAO writes *and* the FTS index update in a transaction, and Android
 * got that transaction from `androidx.room.withTransaction` — an Android-only API that does not
 * exist in Room's JVM artifacts. The desktop replacement (`RoomDatabase.inTransaction`) is exercised
 * for real here, on the bundled SQLite driver, by asserting both sides of each transaction landed:
 * the song row and its lyric index.
 */
class SongLibraryRepositoryTest {

    private lateinit var library: TestLibrary
    private lateinit var repository: SongLibraryRepository
    private val sortKeys = SortKeyUpdater()

    @BeforeTest
    fun setUp() {
        library = TestLibrary()
        repository = SongLibraryRepositoryImpl(library.database)
    }

    @AfterTest
    fun tearDown() {
        library.close()
    }

    @Test
    fun `upsert writes the song and its lyric index in one transaction`() = runBlocking<Unit> {
        val folderId = library.folder()
        repository.upsertSongs(
            listOf(
                library.song(
                    path = """H:\Music\hello.mp3""",
                    title = "Hello",
                    lyrics = "[00:01.00]Hello wonderful world\n[00:02.00]second line",
                    folderId = folderId,
                )
            )
        )

        assertEquals(1, repository.getSongCount())
        assertEquals(
            listOf("Hello wonderful world", "second line"),
            library.indexedLyricLines("""H:\Music\hello.mp3"""),
        )
    }

    @Test
    fun `upsert replaces an existing row when the caller carries the stored id`() = runBlocking<Unit> {
        val folderId = library.folder()
        val song = library.song(path = """H:\Music\hello.mp3""", title = "Hello", folderId = folderId)

        repository.upsertSongs(listOf(song))
        val storedId = assertNotNull(repository.getSongByUri(song.uri)).id
        repository.upsertSongs(listOf(song.copy(id = storedId, title = "Hello (Remastered)")))

        assertEquals(1, repository.getSongCount())
        assertEquals("Hello (Remastered)", repository.getSongByUri(song.uri)?.title)
    }

    @Test
    fun `upsert without the stored id does not rewrite the existing row`() = runBlocking<Unit> {
        // Room's @Upsert falls back to an UPDATE ... WHERE id = ? when the insert violates the unique
        // `uri` index, so an entity built without the stored id updates zero rows and the conflict is
        // swallowed. This is why the scanner reads `existingId = dbInfo?.id ?: 0L` before upserting:
        // metadata only reaches the database when the scanned entity carries the stored primary key.
        val folderId = library.folder()
        val song = library.song(path = """H:\Music\hello.mp3""", title = "Hello", folderId = folderId)
        repository.upsertSongs(listOf(song))

        repository.upsertSongs(listOf(song.copy(title = "Hello (Remastered)")))

        assertEquals(1, repository.getSongCount())
        assertEquals("Hello", repository.getSongByUri(song.uri)?.title)
    }

    @Test
    fun `update replaces the indexed lyrics of a song`() = runBlocking<Unit> {
        val folderId = library.folder()
        val song = library.song(
            path = """H:\Music\hello.mp3""",
            title = "Hello",
            lyrics = "[00:01.00]the old line",
            folderId = folderId,
        )
        repository.upsertSongs(listOf(song))
        assertEquals(listOf("the old line"), library.indexedLyricLines(song.uri))

        val updated = sortKeys.update(song.copy(lyrics = "[00:01.00]the new line"))
        repository.updateSong(updated)

        assertEquals(
            listOf("the new line"),
            library.indexedLyricLines(song.uri),
            "the stale line must be dropped, not appended",
        )
    }

    @Test
    fun `delete removes the song and its lyric index in one transaction`() = runBlocking<Unit> {
        val folderId = library.folder()
        val path = """H:\Music\hello.mp3"""
        repository.upsertSongs(
            listOf(library.song(path = path, title = "Hello", lyrics = "[00:01.00]Hello world", folderId = folderId))
        )
        assertEquals(1, library.database.songDao().getLyricFtsRowCount())

        repository.deleteSongsByUris(listOf(path))

        assertEquals(0, repository.getSongCount())
        assertEquals(0, library.database.songDao().getLyricFtsRowCount())
    }

    @Test
    fun `clearAll empties the library and the lyric index`() = runBlocking<Unit> {
        val folderId = library.folder()
        repository.upsertSongs(
            listOf(
                library.song(path = """H:\Music\a.mp3""", title = "A", lyrics = "[00:01.00]line a", folderId = folderId),
                library.song(path = """H:\Music\b.mp3""", title = "B", lyrics = "[00:01.00]line b", folderId = folderId),
            )
        )

        repository.clearAll()

        assertEquals(0, repository.getSongCount())
        assertEquals(0, library.database.songDao().getLyricFtsRowCount())
    }

    @Test
    fun `observes songs ordered by the stored sort keys`() = runBlocking<Unit> {
        val folderId = library.folder()
        repository.upsertSongs(
            listOf("beta", "Alpha", "中文歌")
                .mapIndexed { index, title ->
                    sortKeys.update(
                        library.song(
                            path = """H:\Music\$index.mp3""",
                            title = title,
                            folderId = folderId,
                        )
                    )
                }
        )

        val ascending = repository.observeSongs(SortBy.TITLE, SortOrder.ASC).first()
        assertEquals(listOf("1_ALPHA", "1_BETA", "1_ZHONGWENGE"), ascending.map { it.titleSortKey })
        assertEquals("Alpha", ascending.first().title)

        val descending = repository.observeSongs(SortBy.TITLE, SortOrder.DESC).first()
        assertEquals("中文歌", descending.first().title)
    }

    @Test
    fun `observes songs of one folder only and skips ignored folders`() = runBlocking<Unit> {
        val music = library.folder(path = """H:\Music""")
        val podcast = library.folder(path = """H:\Podcasts""")
        val ignored = library.folder(path = """H:\Private""", isIgnored = true)
        repository.upsertSongs(
            listOf(
                library.song(path = """H:\Music\a.mp3""", title = "A", folderId = music),
                library.song(path = """H:\Podcasts\b.mp3""", title = "B", folderId = podcast),
                library.song(path = """H:\Private\c.mp3""", title = "C", folderId = ignored),
            )
        )

        assertEquals(listOf("A"), repository.observeSongs(SortBy.TITLE, SortOrder.ASC, music).first().map { it.title })
        assertEquals(
            listOf("A", "B"),
            repository.observeSongs(SortBy.TITLE, SortOrder.ASC).first().map { it.title },
            "ignored folders must not reach the song list",
        )
    }

    @Test
    fun `reads songs by uri, by uri batch and by album`() = runBlocking<Unit> {
        val folderId = library.folder()
        val a = library.song(path = """H:\Music\a.mp3""", title = "A", artist = "Alice", album = "First", folderId = folderId)
        val b = library.song(path = """H:\Music\b.mp3""", title = "B", artist = "Alice", album = "First", folderId = folderId)
        repository.upsertSongs(listOf(a, b))

        assertEquals("A", repository.getSongByUri(a.uri)?.title)
        assertNull(repository.getSongByUri("""H:\Music\missing.mp3"""))
        assertEquals(listOf("A", "B"), repository.getSongsByUris(listOf(a.uri, b.uri, """H:\Music\missing.mp3""")).map { it.title })
        assertEquals(emptyList(), repository.getSongsByUris(emptyList()))
        assertEquals(listOf("A", "B"), repository.getSongsByAlbum("First", "Alice").map { it.title })
        assertEquals(emptyList(), repository.getSongsByAlbum("Second", "Alice"))
    }

    @Test
    fun `songs and their lyric index survive reopening the database file`() = runBlocking<Unit> {
        val folderId = library.folder()
        repository.upsertSongs(
            listOf(library.song(path = """H:\Music\a.mp3""", title = "A", lyrics = "[00:01.00]Hello world", folderId = folderId))
        )

        library.reopen()

        val reopened = SongLibraryRepositoryImpl(library.database)
        assertEquals(1, reopened.getSongCount())
        assertEquals(1, library.database.songDao().getLyricFtsRowCount())
    }

    @Test
    fun `empty batches are no-ops`() = runBlocking<Unit> {
        repository.upsertSongs(emptyList())
        repository.updateSongs(emptyList())
        repository.deleteSongsByUris(emptyList())

        assertEquals(0, repository.getSongCount())
        assertTrue(library.database.songDao().getLyricFtsRowCount() == 0)
    }
}
