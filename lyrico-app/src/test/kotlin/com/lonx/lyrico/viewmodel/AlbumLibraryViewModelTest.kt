package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.model.AlbumSortBy
import com.lonx.lyrico.data.model.AlbumSortInfo
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
 * The album list's state holder.
 *
 * Three comparators here are easy to get subtly wrong and each is pinned by a seed that would fail if
 * it were: album artist is compared uppercased, the year sort treats "no year" as the largest value,
 * and the descending year branch returns early -- it builds its own descending comparator and skips
 * the reversal the other branches get, so removing that `return@combine` silently turns "newest
 * first" into "oldest first, unknown last".
 */
class AlbumLibraryViewModelTest {

    private lateinit var fixture: LibraryBrowseFixture
    private lateinit var viewModel: AlbumLibraryViewModel
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
     * Kind of Blue: 1 song, 1959, "Miles Davis". Sunday at the Village Vanguard: 2 songs, no date, no
     * album artist. Waltz for Debby: 1 song, 1961, "bill evans" in lower case.
     *
     * By name the order is Kind, Sunday, Waltz; by album artist it is Sunday (empty), Waltz, Kind
     * (upper-cased); by song count Sunday comes first; by year Kind then Waltz with the unknown one
     * last either way.
     */
    private suspend fun seedLibrary() {
        val folderId = fixture.library.folder()
        fixture.seed(
            fixture.library.song(
                path = """H:\Music\kob.mp3""", title = "So What",
                artist = "Miles Davis", album = "Kind of Blue", albumArtist = "Miles Davis", folderId = folderId,
            ).copy(date = "1959"),
            fixture.library.song(
                path = """H:\Music\sunday1.mp3""", title = "My Favorite Things",
                artist = "John Coltrane", album = "Sunday at the Village Vanguard", folderId = folderId,
            ),
            fixture.library.song(
                path = """H:\Music\sunday2.mp3""", title = "Softly as in a Morning Sunrise",
                artist = "John Coltrane", album = "Sunday at the Village Vanguard", folderId = folderId,
            ),
            fixture.library.song(
                path = """H:\Music\waltz.mp3""", title = "Waltz for Debby",
                artist = "Bill Evans", album = "Waltz for Debby", albumArtist = "bill evans", folderId = folderId,
            ).copy(date = "1961"),
        )
        viewModel = AlbumLibraryViewModel(fixture.index, fixture.scanManager, fixture.settings)
        collectors.keepCollecting(viewModel.albums)
    }

    @Test
    fun `albums are listed by name ascending by default`() = runBlocking<Unit> {
        seedLibrary()

        awaitUntil(describe = { names() }) {
            it == listOf("Kind of Blue", "Sunday at the Village Vanguard", "Waltz for Debby")
        }
    }

    @Test
    fun `sorting by album artist is case-insensitive and keeps the blanks together`() = runBlocking<Unit> {
        seedLibrary()
        awaitUntil(describe = { names() }) { it.size == 3 }

        viewModel.onSortChange(AlbumSortInfo(sortBy = AlbumSortBy.ALBUM_ARTIST, order = SortOrder.ASC))

        // Upper-casing is what puts "bill evans" after "Miles Davis"; a plain case-sensitive compare
        // would order it the other way round ("M" < "b"). The album with no album artist sorts first,
        // since an empty string precedes any name.
        awaitUntil(describe = { names() }) {
            it == listOf("Sunday at the Village Vanguard", "Waltz for Debby", "Kind of Blue")
        }
    }

    @Test
    fun `sorting by song count puts the biggest album first`() = runBlocking<Unit> {
        seedLibrary()
        awaitUntil(describe = { names() }) { it.size == 3 }

        viewModel.onSortChange(AlbumSortInfo(sortBy = AlbumSortBy.SONG_COUNT, order = SortOrder.ASC))

        // Two songs on Sunday at the Village Vanguard against one on each of the others, and it is the
        // last album by name -- so this order cannot be the name order.
        awaitUntil(describe = { names() }) {
            it == listOf("Sunday at the Village Vanguard", "Kind of Blue", "Waltz for Debby")
        }
    }

    @Test
    fun `year ascending puts the albums without a date last`() = runBlocking<Unit> {
        seedLibrary()
        awaitUntil(describe = { names() }) { it.size == 3 }

        viewModel.onSortChange(AlbumSortInfo(sortBy = AlbumSortBy.YEAR, order = SortOrder.ASC))

        awaitUntil(describe = { names() }) {
            it == listOf("Kind of Blue", "Waltz for Debby", "Sunday at the Village Vanguard")
        }
    }

    @Test
    fun `year descending puts the newest first and still keeps the dateless album last`() = runBlocking<Unit> {
        seedLibrary()
        awaitUntil(describe = { names() }) { it.size == 3 }

        viewModel.onSortChange(AlbumSortInfo(sortBy = AlbumSortBy.YEAR, order = SortOrder.DESC))

        // The descending branch sorts the year itself and returns before the shared `asReversed()`;
        // if that early return were lost, the unknown-year album would float to the top.
        awaitUntil(describe = { names() }) {
            it == listOf("Waltz for Debby", "Kind of Blue", "Sunday at the Village Vanguard")
        }
    }

    @Test
    fun `descending name order is the exact reverse of ascending`() = runBlocking<Unit> {
        seedLibrary()
        awaitUntil(describe = { names() }) { it.size == 3 }

        viewModel.onSortChange(AlbumSortInfo(sortBy = AlbumSortBy.NAME, order = SortOrder.DESC))

        awaitUntil(describe = { names() }) {
            it == listOf("Waltz for Debby", "Sunday at the Village Vanguard", "Kind of Blue")
        }
    }

    @Test
    fun `the grid column count defaults to two and follows the saved setting`() = runBlocking<Unit> {
        seedLibrary()

        assertEquals(2, viewModel.gridColumns.value, "the default before any setting is read")

        viewModel.setGridColumns(4)

        awaitUntil(describe = { viewModel.gridColumns.value }) { it == 4 }
    }

    @Test
    fun `the scan state is the scanner's own state`() {
        val vm = AlbumLibraryViewModel(fixture.index, fixture.scanManager, fixture.settings)

        assertSame(fixture.scanManager.state, vm.scanState)
    }

    @Test
    fun `refresh asks for a scan and rebuilds the album index only once the scan succeeded`() = runBlocking<Unit> {
        seedLibrary()
        awaitUntil(describe = { names() }) { it.size == 3 }
        val dao = fixture.library.database.libraryIndexDao()
        dao.insertArtist(ArtistEntity(name = "Orphan", normalizedName = "orphan", songCount = 1))
        dao.insertAlbum(AlbumEntity(name = "Orphan Album", albumArtist = null, normalizedKey = "orphan album|", songCount = 1))

        viewModel.refreshSongs()

        assertEquals(1, fixture.scanManager.scanAllCalls.size, "one scan per refresh, not one per row")
        val call = fixture.scanManager.scanAllCalls.single()
        assertEquals(false, call.fullRescan, "a refresh is incremental")
        val onSuccess = assertNotNull(call.onSuccess, "the index rebuild must be handed to the scanner")

        assertTrue(albumNames().contains("Orphan Album"), "nothing is rebuilt before the scan reports success")
        onSuccess.invoke()

        assertTrue(albumNames().none { it == "Orphan Album" }, "the album index was rebuilt")
        assertEquals(3, albumNames().size, "the rebuild restores the three real albums")
        assertTrue(artistNames().contains("Orphan"), "the artist index was not touched")
    }

    private fun names(): List<String> = viewModel.albums.value.map { it.name }

    private suspend fun albumNames(): List<String> = fixture.index.observeAlbums().first().map { it.name }

    private suspend fun artistNames(): List<String> = fixture.index.observeArtists().first().map { it.name }

    private fun CoroutineScope.keepCollecting(flow: StateFlow<*>) {
        launch { flow.collect { } }
    }
}
