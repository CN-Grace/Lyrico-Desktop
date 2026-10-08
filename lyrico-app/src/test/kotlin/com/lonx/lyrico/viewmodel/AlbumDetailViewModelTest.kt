package com.lonx.lyrico.viewmodel

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The album detail screen's state holder -- the album row plus its songs, keyed by the id it was opened
 * with.
 *
 * Thin, but it is the first place the port relies on `stateIn(WhileSubscribed)` around a Room flow, so
 * the tests collect the real thing (no subscriber, no emission) rather than reading `.value` on a flow
 * nobody has subscribed to.
 */
class AlbumDetailViewModelTest {

    private lateinit var fixture: LibraryBrowseFixture
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

    @Test
    fun `the requested album and its songs are emitted`() = runBlocking<Unit> {
        val folderId = fixture.library.folder()
        fixture.seed(
            fixture.library.song(path = """H:\Music\kob1.mp3""", title = "So What", artist = "Miles Davis", album = "Kind of Blue", albumArtist = "Miles Davis", folderId = folderId),
            fixture.library.song(path = """H:\Music\kob2.mp3""", title = "Blue in Green", artist = "Miles Davis", album = "Kind of Blue", albumArtist = "Miles Davis", folderId = folderId),
            fixture.library.song(path = """H:\Music\waltz.mp3""", title = "Waltz for Debby", artist = "Bill Evans", album = "Waltz for Debby", folderId = folderId),
        )
        val albumId = fixture.index.observeAlbums().first().single { it.name == "Kind of Blue" }.id

        val viewModel = AlbumDetailViewModel(fixture.index, albumId)
        collectors.keepCollecting(viewModel.album)
        collectors.keepCollecting(viewModel.songs)

        awaitUntil(describe = { viewModel.album.value?.name }) { it == "Kind of Blue" }
        val album = assertNotNull(viewModel.album.value)
        assertEquals("Miles Davis", album.albumArtist)
        assertEquals(2, album.songCount, "the album carries the aggregated count, not just the row")

        awaitUntil(describe = { viewModel.songs.value.mapNotNull { it.title }.sorted() }) {
            it == listOf("Blue in Green", "So What")
        }
    }

    @Test
    fun `the album's song list does not include another album's songs`() = runBlocking<Unit> {
        val folderId = fixture.library.folder()
        fixture.seed(
            fixture.library.song(path = """H:\Music\kob.mp3""", title = "So What", artist = "Miles Davis", album = "Kind of Blue", folderId = folderId),
            fixture.library.song(path = """H:\Music\waltz.mp3""", title = "Waltz for Debby", artist = "Bill Evans", album = "Waltz for Debby", folderId = folderId),
        )
        val albumId = fixture.index.observeAlbums().first().single { it.name == "Waltz for Debby" }.id

        val viewModel = AlbumDetailViewModel(fixture.index, albumId)
        collectors.keepCollecting(viewModel.album)
        collectors.keepCollecting(viewModel.songs)

        awaitUntil(describe = { viewModel.songs.value.map { it.title } }) { it == listOf("Waltz for Debby") }
        assertEquals("Waltz for Debby", viewModel.album.value?.name)
    }

    @Test
    fun `an id that matches nothing yields no album and no songs`() = runBlocking<Unit> {
        seedOneSong()
        val viewModel = AlbumDetailViewModel(fixture.index, albumId = 9_999L)
        collectors.keepCollecting(viewModel.album)
        collectors.keepCollecting(viewModel.songs)

        // Bounded wait rather than a poll for a change: `null` is already the initial value, and a
        // StateFlow does not re-emit an equal value, so there is no distinct emission to wait for.
        Thread.sleep(200L)

        assertNull(viewModel.album.value)
        assertTrue(viewModel.songs.value.isEmpty())
        assertNull(fixture.index.observeAlbumById(9_999L).first(), "the DAO agrees, so null is the real answer")
    }

    private suspend fun seedOneSong() {
        val folderId = fixture.library.folder()
        fixture.seed(
            fixture.library.song(path = """H:\Music\a.mp3""", title = "A", artist = "Aria", album = "A1", folderId = folderId),
        )
    }

    private fun CoroutineScope.keepCollecting(flow: StateFlow<*>) {
        launch { flow.collect { } }
    }
}
