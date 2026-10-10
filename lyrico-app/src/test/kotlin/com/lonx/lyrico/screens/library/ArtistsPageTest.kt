package com.lonx.lyrico.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.ArtistSortBy
import com.lonx.lyrico.data.model.entity.ArtistEntity
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.album_song_count
import com.lonx.lyrico.resources.artist_list_title
import com.lonx.lyrico.resources.cd_sort
import com.lonx.lyrico.resources.empty_artists_title
import com.lonx.lyrico.resources.label_album_count
import com.lonx.lyrico.resources.label_name
import com.lonx.lyrico.resources.label_song_count
import com.lonx.lyrico.ui.navigation.ArtistDetailDestination
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.navigation.rememberNavigator
import com.lonx.lyrico.ui.theme.LyricoTheme
import com.lonx.lyrico.utils.LibraryScanManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The artists tab, rendered from a real scan of real audio files.
 *
 * Same stack as `AlbumsPageTest`: real files, tags written through the real tag repository, a real scan,
 * a real Room index and a real grid. What is specific to this page is the layout rule it inherited — the
 * artist list is the one list in the app whose column count is derived from the *window* (1 column, or 2
 * past 600.dp) instead of from a saved setting — and the artist rows' two-line summary, which is the one
 * string on this page whose formatting would silently print `%1$d albums · %2$d songs` if it went
 * through the library's own vararg overload.
 *
 * Artist counts are asserted against what the scan read back rather than against a written-down constant,
 * because two rows that both say "1 album, 1 song" would make the summary assertion the only thing
 * standing between a correct page and one that shows the same numbers everywhere.
 */
@OptIn(ExperimentalTestApi::class)
class ArtistsPageTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-artists-page").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-artists-page-library")

    private lateinit var database: LyricoDatabase

    /** Records the routes a row asks for, for the destinations whose screens are not ported yet. */
    private class RecordingNavigator : Navigator {
        val routes = mutableListOf<String>()

        override fun navigate(direction: NavDirection) {
            routes += direction.route
        }

        override fun popBackStack(): Boolean = false

        override fun navigateUp(): Boolean = false
    }

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        database = koin()
        runBlocking { settings().saveIgnoreShortAudio(false) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        workingDir.deleteRecursively()
        musicDir.toFile().deleteRecursively()
    }

    private inline fun <reified T : Any> koin(): T = GlobalContext.get().get()

    private fun settings(): SettingsRepository = koin()

    private fun index(): LibraryIndexRepository = koin()

    @Composable
    private fun ArtistsPageInTestHost(navigatorOverride: Navigator? = null) {
        val controller = rememberNavController()
        val navigator = rememberNavigator(controller)
        NavHost(navController = controller, startDestination = "artists") {
            composable("artists") {
                ArtistsPage(navigator = navigatorOverride ?: navigator)
            }
        }
    }

    /**
     * Copies a real audio file out of TagLib's vendored test fixtures into the temp library, and tags it.
     *
     * Tagged rather than raw, for the same reason `AlbumsPageTest` does it: `bladeenc.mp3` ships with no
     * artist and no album, so an untagged fixture scans into a song that never becomes an artist row,
     * and the page under test would have nothing to render. The write goes through the real
     * `AudioTagRepository`, so the scan reads tags that are really on disk.
     */
    private fun placeFixture(
        fixture: String,
        relativeDir: String,
        title: String,
        album: String,
        artist: String,
        fileName: String = fixture,
    ): Path = placeTaggedFixture(
        musicDir = musicDir,
        fixture = fixture,
        relativeDir = relativeDir,
        title = title,
        artist = artist,
        album = album,
        tags = koin(),
        fileName = fileName,
    )

    private fun scanLibrary() {
        koin<LibraryScanManager>().addFolderAndScan(musicDir.toRealPath().toString())
    }

    private fun text(res: StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    private fun artistsInLibrary(): List<ArtistEntity> =
        runBlocking { index().observeArtists().first() }

    private fun SemanticsNodeInteractionsProvider.nodeCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `the artists tab renders a row per artist with a formatted album and song count`() =
        runComposeUiTest {
            // Two artists, with different album and song counts, so the summary line has something to
            // distinguish: "1 album, 2 songs" and "1 album, 1 song" cannot both be right by accident.
            placeFixture(
                "bladeenc.mp3",
                "Album One",
                title = "First Song",
                album = "Album One",
                artist = "An Artist",
            )
            placeFixture(
                "silence-44-s.flac",
                "Album One",
                title = "Second Song",
                album = "Album One",
                artist = "An Artist",
            )
            placeFixture(
                "bladeenc.mp3",
                "Album Two",
                title = "Third Song",
                album = "Album Two",
                artist = "Another Artist",
                fileName = "third.mp3",
            )

            setContent {
                LyricoTheme {
                    ArtistsPageInTestHost()
                }
            }

            scanLibrary()
            waitUntil("wait for the artist index to be built", 30_000) {
                artistsInLibrary().isNotEmpty()
            }

            val artists = artistsInLibrary()
            assertEquals(
                mapOf("An Artist" to (1 to 2), "Another Artist" to (1 to 1)),
                artists.associate { it.name to (it.albumCount to it.songCount) },
                "the scan reads the artist tags that were written; " +
                    "got ${artists.map { "${it.name}=${it.albumCount}/${it.songCount}" }}",
            )

            waitUntil("wait for the artist rows to be composed", 20_000) {
                nodeCount(text(Res.string.artist_list_title, artists.size)) > 0
            }
            artists.forEach { artist ->
                assertTrue(
                    nodeCount(artist.name) > 0,
                    "the row for '${artist.name}' has to be on screen",
                )
                val summary = text(
                    Res.string.album_song_count,
                    artist.albumCount,
                    artist.songCount,
                )
                assertTrue(
                    nodeCount(summary) > 0,
                    "'${artist.name}' has ${artist.albumCount} albums and ${artist.songCount} songs, " +
                        "and its row has to say so",
                )
            }
            // Two positional placeholders on one line: this is the string whose literal form the port
            // would render if the row used the library's own vararg formatting.
            assertEquals(
                0,
                nodeCount("%1\$d albums · %2\$d songs"),
                "the row must not print the placeholders instead of the counts",
            )
        }

    @Test
    fun `an index with no artists shows the artists empty state`() = runComposeUiTest {
        // A root with no audio in it: nothing has been indexed, so there is no artist to show.
        setContent {
            LyricoTheme {
                ArtistsPageInTestHost()
            }
        }

        waitUntil("wait for the empty state", 5_000) {
            nodeCount(text(Res.string.empty_artists_title)) > 0
        }
        assertTrue(nodeCount(text(Res.string.artist_list_title, 0)) > 0)
    }

    @Test
    fun `tapping an artist row asks for the artist detail route it declares`() = runComposeUiTest {
        placeFixture(
            "bladeenc.mp3",
            "Album One",
            title = "First Song",
            album = "Album One",
            artist = "An Artist",
        )
        val navigator = RecordingNavigator()

        setContent {
            LyricoTheme {
                ArtistsPageInTestHost(navigatorOverride = navigator)
            }
        }

        scanLibrary()
        waitUntil("wait for the artist index to be built", 30_000) {
            artistsInLibrary().isNotEmpty()
        }
        val artist = artistsInLibrary().single()
        waitUntil("wait for the artist row to be composed", 20_000) {
            nodeCount(artist.name) > 0
        }

        onNodeWithText(artist.name).performClick()

        assertEquals(
            listOf(ArtistDetailDestination(artistId = artist.id).route),
            navigator.routes,
            "a row opens the artist detail screen the page declared, arguments and all",
        )
    }

    @Test
    fun `the sort menu lists the artist fields, and the chosen one reaches the store`() =
        runComposeUiTest {
            placeFixture(
                "bladeenc.mp3",
                "Album One",
                title = "First Song",
                album = "Album One",
                artist = "An Artist",
            )
            val settings = settings()

            // `NAME` is the only artist sort field the A-Z rail supports, and it is the default.
            assertEquals(ArtistSortBy.NAME, runBlocking { settings.artistSortInfo.first() }.sortBy)

            // Watched, not polled: [SettingsFlowWatcher] has the Windows DataStore failure that a polling
            // loop causes when it re-subscribes while a write is in flight.
            val settingsScope = CoroutineScope(Dispatchers.Default)
            val sortSaved = SettingsFlowWatcher(settingsScope, settings.artistSortInfo) {
                it.sortBy == ArtistSortBy.SONG_COUNT
            }

            try {
                setContent {
                    LyricoTheme {
                        ArtistsPageInTestHost()
                    }
                }

                scanLibrary()
                waitUntil("wait for the artist index to be built", 30_000) {
                    artistsInLibrary().isNotEmpty()
                }

                onNodeWithContentDescription(text(Res.string.cd_sort)).performClick()
                val songCountField = text(Res.string.label_song_count)
                waitUntil("wait for the sort menu to open", 5_000) { nodeCount(songCountField) > 0 }
                assertTrue(
                    nodeCount(text(Res.string.label_album_count)) > 0,
                    "every artist sort field belongs in the menu",
                )
                assertTrue(nodeCount(text(Res.string.label_name)) > 0)
                onNodeWithText(songCountField).performClick()

                waitUntil("wait for the sort choice to be saved", 5_000) { sortSaved.isSatisfied }
            } finally {
                sortSaved.stop()
                settingsScope.cancel()
            }
        }
}
