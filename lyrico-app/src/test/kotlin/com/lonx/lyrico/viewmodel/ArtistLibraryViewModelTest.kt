package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.model.ArtistSortBy
import com.lonx.lyrico.data.model.ArtistSortInfo
import com.lonx.lyrico.data.model.entity.AlbumEntity
import com.lonx.lyrico.data.model.entity.ArtistEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The artist list's state holder: sorting, the scan hand-off, and the persisted sort preference.
 *
 * Sorting happens in the view model (the DAO hands back rows in index order), so it is only
 * observable through the state flow -- hence a real database plus a live collector rather than a
 * fake. The seed is built so that all three sort keys give a *different* order: a comparator reading
 * the wrong column would otherwise still pass.
 */
class ArtistLibraryViewModelTest {

    private lateinit var fixture: LibraryBrowseFixture
    private lateinit var viewModel: ArtistLibraryViewModel
    private lateinit var collectors: CoroutineScope

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        fixture = LibraryBrowseFixture()
        collectors = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @AfterTest
    fun tearDown() {
        collectors.cancel()
        fixture.close()
        Dispatchers.resetMain()
    }

    /**
     * Aria: 3 songs across 3 albums. Bill Evans: 2 songs in 1 album. Miles Davis: 4 songs across 2
     * albums. By name the order is Aria, Bill Evans, Miles Davis -- and a different permutation for
     * each of the two count sorts.
     */
    private suspend fun seedLibrary() {
        val folderId = fixture.library.folder()
        fixture.seed(
            fixture.library.song(path = """H:\Music\aria1.mp3""", title = "Aria 1", artist = "Aria", album = "A1", folderId = folderId),
            fixture.library.song(path = """H:\Music\aria2.mp3""", title = "Aria 2", artist = "Aria", album = "A2", folderId = folderId),
            fixture.library.song(path = """H:\Music\aria3.mp3""", title = "Aria 3", artist = "Aria", album = "A3", folderId = folderId),
            fixture.library.song(path = """H:\Music\bill1.mp3""", title = "Bill 1", artist = "Bill Evans", album = "Waltz for Debby", folderId = folderId),
            fixture.library.song(path = """H:\Music\bill2.mp3""", title = "Bill 2", artist = "Bill Evans", album = "Waltz for Debby", folderId = folderId),
            fixture.library.song(path = """H:\Music\miles1.mp3""", title = "Miles 1", artist = "Miles Davis", album = "Kind of Blue", folderId = folderId),
            fixture.library.song(path = """H:\Music\miles2.mp3""", title = "Miles 2", artist = "Miles Davis", album = "Kind of Blue", folderId = folderId),
            fixture.library.song(path = """H:\Music\miles3.mp3""", title = "Miles 3", artist = "Miles Davis", album = "Bitches Brew", folderId = folderId),
            fixture.library.song(path = """H:\Music\miles4.mp3""", title = "Miles 4", artist = "Miles Davis", album = "Bitches Brew", folderId = folderId),
        )
        viewModel = ArtistLibraryViewModel(fixture.index, fixture.scanManager, fixture.settings)
        collectors.keepCollecting(viewModel.artists)
    }

    @Test
    fun `artists are listed by name ascending by default`() = runBlocking<Unit> {
        seedLibrary()

        awaitUntil(describe = { names() }) { it == listOf("Aria", "Bill Evans", "Miles Davis") }
    }

    @Test
    fun `choosing a descending name order is persisted and re-sorts the list`() = runBlocking<Unit> {
        seedLibrary()
        awaitUntil(describe = { names() }) { it == listOf("Aria", "Bill Evans", "Miles Davis") }

        viewModel.onSortChange(ArtistSortInfo(sortBy = ArtistSortBy.NAME, order = SortOrder.DESC))

        awaitUntil(describe = { names() }) { it == listOf("Miles Davis", "Bill Evans", "Aria") }
        assertEquals(
            ArtistSortInfo(sortBy = ArtistSortBy.NAME, order = SortOrder.DESC),
            fixture.settings.artistSortInfo.first(),
            "the choice has to outlive the screen, so it is written to settings",
        )
    }

    @Test
    fun `sorting by song count puts the busiest artist first`() = runBlocking<Unit> {
        seedLibrary()
        awaitUntil(describe = { names() }) { it == listOf("Aria", "Bill Evans", "Miles Davis") }

        viewModel.onSortChange(ArtistSortInfo(sortBy = ArtistSortBy.SONG_COUNT, order = SortOrder.ASC))

        // The count comparators are descending and the ASC flag returns their result as it stands, so
        // "ascending" by song count means most songs first. Identical to Android, pinned here so that
        // any future change of that meaning is a deliberate one.
        awaitUntil(describe = { names() }) { it == listOf("Miles Davis", "Aria", "Bill Evans") }
    }

    @Test
    fun `the descending flag reverses a song count sort`() = runBlocking<Unit> {
        seedLibrary()
        awaitUntil(describe = { names() }) { it == listOf("Aria", "Bill Evans", "Miles Davis") }

        viewModel.onSortChange(ArtistSortInfo(sortBy = ArtistSortBy.SONG_COUNT, order = SortOrder.DESC))

        awaitUntil(describe = { names() }) { it == listOf("Bill Evans", "Aria", "Miles Davis") }
    }

    @Test
    fun `sorting by album count reads the album column, not the song column`() = runBlocking<Unit> {
        seedLibrary()
        awaitUntil(describe = { names() }) { it == listOf("Aria", "Bill Evans", "Miles Davis") }

        viewModel.onSortChange(ArtistSortInfo(sortBy = ArtistSortBy.ALBUM_COUNT, order = SortOrder.ASC))

        // Aria has the most songs (3) but the most albums (3) too, so if this comparator read songCount
        // the order would come out as the song-count one: Miles, Aria, Bill.
        awaitUntil(describe = { names() }) { it == listOf("Aria", "Miles Davis", "Bill Evans") }
    }

    @Test
    fun `the stored sort preference is what the list starts from`() = runBlocking<Unit> {
        val folderId = fixture.library.folder()
        fixture.seed(
            fixture.library.song(path = """H:\Music\a.mp3""", title = "A", artist = "Aria", album = "A1", folderId = folderId),
            fixture.library.song(path = """H:\Music\b.mp3""", title = "B", artist = "Miles Davis", album = "Kind of Blue", folderId = folderId),
        )
        fixture.settings.saveArtistSortInfo(ArtistSortInfo(sortBy = ArtistSortBy.NAME, order = SortOrder.DESC))

        val vm = ArtistLibraryViewModel(fixture.index, fixture.scanManager, fixture.settings)
        collectors.keepCollecting(vm.artists)

        awaitUntil(describe = { vm.artists.value.map { it.name } }) { it == listOf("Miles Davis", "Aria") }
    }

    @Test
    fun `the scan state is the scanner's own state`() {
        val vm = ArtistLibraryViewModel(fixture.index, fixture.scanManager, fixture.settings)

        assertSame(fixture.scanManager.state, vm.scanState)
    }

    @Test
    fun `refresh asks for a scan and rebuilds the artist index only once the scan succeeded`() = runBlocking<Unit> {
        seedLibrary()
        awaitUntil(describe = { names() }) { it == listOf("Aria", "Bill Evans", "Miles Davis") }
        // Two rows that no song references, both visible because `observeArtists`/`observeAlbums` filter
        // on `songCount > 0` rather than on the presence of a cross reference. `rebuildArtistIndex`
        // clears and repopulates the artist tables, so the orphan artist disappears while the orphan
        // album survives -- that difference is how this test tells an artist rebuild from an album one.
        val dao = fixture.library.database.libraryIndexDao()
        dao.insertArtist(ArtistEntity(name = "Orphan", normalizedName = "orphan", songCount = 1))
        dao.insertAlbum(AlbumEntity(name = "Orphan Album", albumArtist = null, normalizedKey = "orphan album|", songCount = 1))

        viewModel.refreshSongs()

        assertEquals(1, fixture.scanManager.scanAllCalls.size, "one scan per refresh, not one per row")
        val call = fixture.scanManager.scanAllCalls.single()
        assertEquals(false, call.fullRescan, "a refresh is incremental")
        val onSuccess = assertNotNull(call.onSuccess, "the index rebuild must be handed to the scanner")

        assertTrue(artistNames().contains("Orphan"), "nothing is rebuilt before the scan reports success")
        onSuccess.invoke()

        assertTrue(artistNames().none { it == "Orphan" }, "the artist index was rebuilt")
        assertEquals(
            listOf("Aria", "Bill Evans", "Miles Davis"),
            artistNames(),
            "the rebuild restores the real artists, which is only true if the stats were refreshed",
        )
        assertTrue(albumNames().contains("Orphan Album"), "the album index was not touched")
    }

    private fun names(): List<String> = viewModel.artists.value.map { it.name }

    private suspend fun artistNames(): List<String> = fixture.index.observeArtists().first().map { it.name }

    private suspend fun albumNames(): List<String> = fixture.index.observeAlbums().first().map { it.name }

    private fun CoroutineScope.keepCollecting(flow: StateFlow<*>) {
        launch { flow.collect { } }
    }
}
