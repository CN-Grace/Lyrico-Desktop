package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.song.search.SongSearchRepository
import com.lonx.lyrico.data.song.search.SongSearchRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The local search screen's state holder.
 *
 * Two behaviours only exist in this class and are what the tests target: the typed query is published
 * immediately while the *search* is debounced, and a blank query short-circuits to an empty state
 * instead of listing the library. Lyric search is behind a settings gate -- the tests toggle the real
 * setting and watch the results appear, since a gate that is read once at construction would pass a
 * simpler test but be wrong.
 */
class LocalSearchViewModelTest {

    private lateinit var fixture: LibraryBrowseFixture
    private lateinit var search: SongSearchRepository
    private lateinit var viewModel: LocalSearchViewModel
    private lateinit var collectors: CoroutineScope

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        fixture = LibraryBrowseFixture()
        search = SongSearchRepositoryImpl(fixture.library.database)
        collectors = CoroutineScope(Dispatchers.Default + SupervisorJob())
        viewModel = LocalSearchViewModel(search, fixture.index, fixture.settings)
        collectors.keepCollecting(viewModel.uiState)
    }

    @AfterTest
    fun tearDown() {
        collectors.cancel()
        fixture.close()
        Dispatchers.resetMain()
    }

    private suspend fun seedLibrary() {
        val folderId = fixture.library.folder()
        fixture.seed(
            fixture.library.song(
                path = """H:\Music\voodoo.mp3""", title = "Miles Runs the Voodoo Down",
                artist = "Miles Davis", album = "Miles Smiles", lyrics = "Autumn leaves drift by", folderId = folderId,
            ),
            fixture.library.song(
                path = """H:\Music\aria.mp3""", title = "Aria", artist = "Aria", album = "A1", folderId = folderId,
            ),
        )
    }

    @Test
    fun `the typed query is published before the search runs`() = runBlocking<Unit> {
        seedLibrary()

        viewModel.onQueryChange("miles")

        assertEquals("miles", viewModel.searchQuery.value, "the text field is not debounced")
        assertEquals("", viewModel.uiState.value.query, "the search behind it is")
    }

    @Test
    fun `songs, albums and artists all match the keyword`() = runBlocking<Unit> {
        seedLibrary()

        viewModel.onQueryChange("miles")

        awaitUntil(describe = { viewModel.uiState.value }) { state ->
            state.songs.map { it.title } == listOf("Miles Runs the Voodoo Down") &&
                state.albums.map { it.name } == listOf("Miles Smiles") &&
                state.artists.map { it.name } == listOf("Miles Davis")
        }
    }

    @Test
    fun `clearing the query empties the results instead of listing the library`() = runBlocking<Unit> {
        seedLibrary()
        viewModel.onQueryChange("miles")
        awaitUntil(describe = { viewModel.uiState.value.songs.size }) { it == 1 }

        viewModel.onQueryChange("")

        awaitUntil(describe = { viewModel.uiState.value }) { state ->
            state.query == "" && state.songs.isEmpty() && state.albums.isEmpty() && state.artists.isEmpty()
        }
    }

    @Test
    fun `whitespace alone is treated as an empty query`() = runBlocking<Unit> {
        seedLibrary()
        viewModel.onQueryChange("miles")
        awaitUntil(describe = { viewModel.uiState.value.songs.size }) { it == 1 }

        viewModel.onQueryChange("   ")

        awaitUntil(describe = { viewModel.uiState.value }) { state ->
            state.query == "   " && state.songs.isEmpty() && state.lyricMatches.isEmpty()
        }
    }

    @Test
    fun `switching the keyword replaces the previous results`() = runBlocking<Unit> {
        seedLibrary()
        viewModel.onQueryChange("miles")
        awaitUntil(describe = { viewModel.uiState.value.songs.map { it.title } }) {
            it == listOf("Miles Runs the Voodoo Down")
        }

        viewModel.onQueryChange("aria")

        awaitUntil(describe = { viewModel.uiState.value.songs.map { it.title } }) { it == listOf("Aria") }
    }

    @Test
    fun `lyric search follows the index setting while the keyword stays put`() = runBlocking<Unit> {
        seedLibrary()
        // The word only appears in the lyrics, so a tag-only search must come back empty.
        fixture.settings.saveLyricIndexEnabled(false)
        viewModel.onQueryChange("autumn")
        awaitUntil(describe = { viewModel.uiState.value.query }) { it == "autumn" }

        assertEquals(false, viewModel.uiState.value.lyricSearchEnabled)
        assertTrue(viewModel.uiState.value.lyricMatches.isEmpty(), "the lyric index is switched off")
        assertTrue(viewModel.uiState.value.songs.isEmpty(), "'autumn' is not in any tag")

        fixture.settings.saveLyricIndexEnabled(true)

        // Same keyword, one setting flipped: the gate is re-read, not captured when the pipeline starts.
        awaitUntil(describe = { viewModel.uiState.value }) { state ->
            state.lyricSearchEnabled && state.lyricMatches.isNotEmpty()
        }
        val match = viewModel.uiState.value.lyricMatches.single()
        assertEquals("Autumn leaves drift by", match.lyricLine)
        assertEquals("Miles Runs the Voodoo Down", match.song.title)
    }

    private fun CoroutineScope.keepCollecting(flow: StateFlow<*>) {
        launch { flow.collect { } }
    }
}
