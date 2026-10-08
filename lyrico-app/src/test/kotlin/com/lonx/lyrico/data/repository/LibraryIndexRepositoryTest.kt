package com.lonx.lyrico.data.repository

import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Behavioural tests for the artist/album index that drives the library's browse views.
 *
 * The index is a denormalised projection of `songs` (artist rows, album rows and their cross
 * references), rebuilt inside a transaction. Two things in the port are easy to get wrong and are
 * what these tests pin down: the transaction wrapper that replaced Android's `withTransaction`, and
 * the artist-name splitting that decides how one `artist` tag becomes several index rows.
 */
class LibraryIndexRepositoryTest {

    private lateinit var library: TestLibrary
    private lateinit var songs: SongLibraryRepository
    private lateinit var index: LibraryIndexRepository
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @BeforeTest
    fun setUp() {
        library = TestLibrary()
        songs = SongLibraryRepositoryImpl(library.database)
        index = LibraryIndexRepositoryImpl(
            database = library.database,
            songDao = library.database.songDao(),
            indexDao = library.database.libraryIndexDao(),
            settingsRepository = SettingsRepositoryImpl(
                createSettingsDataStore(Files.createTempFile("lyrico-index-test", ".preferences_pb"), scope)
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        library.close()
    }

    @Test
    fun `rebuild groups songs under their artists and albums`() = runBlocking<Unit> {
        val folderId = library.folder()
        songs.upsertSongs(
            listOf(
                library.song(path = """H:\Music\a.mp3""", title = "So What", artist = "Miles Davis", album = "Kind of Blue", folderId = folderId),
                library.song(path = """H:\Music\b.mp3""", title = "Blue in Green", artist = "Miles Davis", album = "Kind of Blue", folderId = folderId),
                library.song(path = """H:\Music\c.mp3""", title = "Nardis", artist = "Bill Evans", album = "Kind of Blue", folderId = folderId),
            )
        )

        index.rebuildAllIndexes()

        val artists = index.observeArtists().first()
        assertEquals(listOf("Bill Evans", "Miles Davis"), artists.map { it.name }, "artists are sorted by sort key")
        assertEquals(listOf(1, 2), artists.map { it.songCount }, "song counts are aggregated per artist")

        val albums = index.observeAlbums().first()
        assertEquals(listOf("Kind of Blue"), albums.map { it.name })
        assertEquals(3, albums.single().songCount)

        val miles = artists.single { it.name == "Miles Davis" }
        assertEquals(
            listOf("Blue in Green", "So What"),
            index.observeSongsByArtistId(miles.id).first().mapNotNull { it.title }.sorted(),
        )
        val albumId = albums.single().id
        assertEquals(3, index.getSongsByAlbumId(albumId).size)
    }

    @Test
    fun `rebuild splits a multi-value artist tag on the enabled separators only`() = runBlocking<Unit> {
        val folderId = library.folder()
        songs.upsertSongs(
            listOf(
                library.song(path = """H:\Music\collab.mp3""", title = "Collab", artist = "Miles Davis; Bill Evans", folderId = folderId),
                library.song(path = """H:\Music\duo.mp3""", title = "Duo", artist = "Miles Davis & Bill Evans", folderId = folderId),
                library.song(path = """H:\Music\live.mp3""", title = "Live", artist = "Miles Davis feat. Bill Evans", folderId = folderId),
            )
        )

        index.rebuildAllIndexes()

        // `;` is enabled by default; `&` and ` feat. ` are not (see ArtistSplitDefaults), so only the
        // first tag becomes two artists. Asserting the disabled ones too keeps the default set honest.
        assertEquals(
            listOf("Bill Evans", "Miles Davis", "Miles Davis & Bill Evans", "Miles Davis feat. Bill Evans"),
            index.observeArtists().first().map { it.name },
        )
        val bill = index.observeArtists().first().single { it.name == "Bill Evans" }
        assertEquals(listOf("Collab"), index.observeSongsByArtistId(bill.id).first().mapNotNull { it.title })
    }

    @Test
    fun `rebuild keeps artist names on the no-split list whole`() = runBlocking<Unit> {
        val folderId = library.folder()
        songs.upsertSongs(
            listOf(
                library.song(path = """H:\Music\ewf.mp3""", title = "September", artist = "Earth, Wind & Fire", folderId = folderId),
                library.song(path = """H:\Music\solo.mp3""", title = "Solo", artist = "Miles Davis, Bill Evans", folderId = folderId),
            )
        )

        index.rebuildAllIndexes()

        // The comma *is* enabled, so without the builtin no-split list "Earth, Wind & Fire" would
        // shatter into three artists; the second song proves the comma still splits elsewhere.
        assertEquals(
            listOf("Bill Evans", "Earth, Wind & Fire", "Miles Davis"),
            index.observeArtists().first().map { it.name },
        )
    }

    @Test
    fun `reindex moves a song between artists without duplicates`() = runBlocking<Unit> {
        val folderId = library.folder()
        val path = """H:\Music\a.mp3"""
        songs.upsertSongs(
            listOf(library.song(path = path, title = "So What", artist = "Miles Davis", album = "Kind of Blue", folderId = folderId))
        )
        index.rebuildAllIndexes()
        val stored = assertNotNull(songs.getSongByUri(path))

        index.reindexSong(stored.copy(artist = "Bill Evans", album = "Everybody Digs Bill Evans"))

        assertEquals(listOf("Bill Evans"), index.observeArtists().first().map { it.name }, "the old artist row is pruned")
        assertEquals(listOf("Everybody Digs Bill Evans"), index.observeAlbums().first().map { it.name })
    }

    @Test
    fun `removing a song prunes the index rows it kept alive`() = runBlocking<Unit> {
        val folderId = library.folder()
        val path = """H:\Music\a.mp3"""
        songs.upsertSongs(
            listOf(library.song(path = path, title = "So What", artist = "Miles Davis", album = "Kind of Blue", folderId = folderId))
        )
        index.rebuildAllIndexes()
        val stored = assertNotNull(songs.getSongByUri(path))
        assertEquals(1, index.observeArtists().first().size)

        index.removeSongIndex(stored.id)

        assertEquals(emptyList(), index.observeArtists().first())
        assertEquals(emptyList(), index.observeAlbums().first())
    }

    @Test
    fun `index survives reopening the database file`() = runBlocking<Unit> {
        val folderId = library.folder()
        songs.upsertSongs(
            listOf(library.song(path = """H:\Music\a.mp3""", title = "A", artist = "Miles Davis", album = "Kind of Blue", folderId = folderId))
        )
        index.rebuildAllIndexes()

        library.reopen()

        val reopened = LibraryIndexRepositoryImpl(
            database = library.database,
            songDao = library.database.songDao(),
            indexDao = library.database.libraryIndexDao(),
            settingsRepository = SettingsRepositoryImpl(
                createSettingsDataStore(Files.createTempFile("lyrico-index-reopen", ".preferences_pb"), scope)
            ),
        )
        assertEquals(listOf("Miles Davis"), reopened.observeArtists().first().map { it.name })
    }

    @Test
    fun `search matches artists and albums by name`() = runBlocking<Unit> {
        val folderId = library.folder()
        songs.upsertSongs(
            listOf(
                library.song(path = """H:\Music\a.mp3""", title = "A", artist = "Miles Davis", album = "Kind of Blue", folderId = folderId),
                library.song(path = """H:\Music\b.mp3""", title = "B", artist = "Bill Evans", album = "Waltz for Debby", folderId = folderId),
            )
        )
        index.rebuildAllIndexes()

        assertEquals(listOf("Miles Davis"), index.searchArtists("mile").first().map { it.name })
        assertEquals(listOf("Kind of Blue"), index.searchAlbums("kind").first().map { it.name })
    }
}
