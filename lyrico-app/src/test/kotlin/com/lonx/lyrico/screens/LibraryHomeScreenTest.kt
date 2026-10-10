package com.lonx.lyrico.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_add_folder
import com.lonx.lyrico.resources.album_list_title
import com.lonx.lyrico.resources.artist_list_title
import com.lonx.lyrico.resources.cd_sort
import com.lonx.lyrico.resources.empty_albums_title
import com.lonx.lyrico.resources.empty_artists_title
import com.lonx.lyrico.resources.library_tab_albums
import com.lonx.lyrico.resources.library_tab_artists
import com.lonx.lyrico.resources.library_tab_songs
import com.lonx.lyrico.resources.selection_mode_selected_count
import com.lonx.lyrico.resources.song_list_empty_title
import com.lonx.lyrico.screens.library.placeTaggedFixture
import com.lonx.lyrico.ui.navigation.LyricoNavHost
import com.lonx.lyrico.ui.theme.LyricoTheme
import com.lonx.lyrico.utils.LibraryScanManager
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
 * The library shell: the three tabs, the pager behind them, and the two layouts the shell can take.
 *
 * This is the port's start route (`library_home`), so most of these tests mount the real
 * [LyricoNavHost] with no arguments and assert what the shipped window shows. The tabs are clicked,
 * not called: the shell's `selectTab` is reached the way a user reaches it, through a Miuix navigation
 * item, so a bar that renders the label but wires no click fails here.
 *
 * Three things about the headless test frame are measured rather than assumed, because the shell's
 * layout branch depends on them (`docs/port-evidence`, and the `LayoutProbeTest` that produced them
 * was deleted once these numbers were written down):
 *
 * * The root's size **wraps a fixed-size child** and clamps to 1024x768 otherwise, and its density is
 *   1.0, so `dp` and `px` are the same number here.
 * * A bare `LyricoNavHost()` therefore renders a **1024dp-wide window**, which is past
 *   [LibraryHomeRailMinWidth] and takes the rail branch -- the same branch a real 1180dp window takes.
 * * A plain `Modifier.size` would be coerced by the root; `Modifier.requiredSize` is not, which is how
 *   the two layout tests below force the branch they are about.
 *
 * What the shell deliberately does not have is as much a part of the port as what it has, and each of
 * those pieces is a `PLAN.md` gap rather than a silent omission: Android's `BackHandler` (no system
 * back gesture here), the floating / liquid-glass bottom bar (`floatingBottomBarEnabled` and
 * `floatingBarEffect` are still stored, with no rendering effect), and `SongBatchSelectionActions`
 * (the batch FAB). See `LibraryHomeScreen`'s KDoc for each one.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryHomeScreenTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-library-home").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-library-home-library")

    private lateinit var database: LyricoDatabase

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        database = koin()
        // Every audio fixture TagLib ships is shorter than a minute, and the app's own default is to
        // ignore short audio, so a scan would (correctly) drop them. This is the setting a user with
        // short files turns off, saved through the real settings store.
        runBlocking { settings().saveIgnoreShortAudio(false) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        // Best effort: Room's connection pool may still hold the database file open.
        database.close()
        workingDir.deleteRecursively()
        musicDir.toFile().deleteRecursively()
    }

    private inline fun <reified T : Any> koin(): T = GlobalContext.get().get()

    private fun settings(): SettingsRepository = koin()

    private fun scanLibrary() {
        koin<LibraryScanManager>().addFolderAndScan(musicDir.toRealPath().toString())
    }

    /**
     * The expected text for a string resource, formatted the way the screen formats it.
     *
     * [String.format] rather than `getString(res, *args)`: the library's vararg overload ignores plain
     * `%d`/`%s` placeholders, so building the expectation with it would make every assertion agree with
     * a screen that renders the placeholder instead of the value.
     */
    private fun text(res: StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    private fun SemanticsNodeInteractionsProvider.nodeCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    /**
     * The bounds of the one node carrying [text].
     *
     * The tab labels are matched through their merged navigation item (`onAllNodesWithText` returns the
     * item, not the label `Text`), which is exactly the node a click works on and the node whose
     * position says where the tab strip is.
     */
    private fun SemanticsNodeInteractionsProvider.singleBounds(text: String): Rect =
        onAllNodesWithText(text).fetchSemanticsNodes().single().boundsInRoot

    @Test
    fun `the shipped host boots into the library shell with the songs tab showing`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                // The real host, so the start route is asserted rather than assumed: an unregistered
                // library home leaves this window empty.
                LyricoNavHost()
            }
        }

        listOf(
            Res.string.library_tab_songs,
            Res.string.library_tab_artists,
            Res.string.library_tab_albums,
        ).forEach { tab ->
            assertTrue(
                nodeCount(text(tab)) > 0,
                "the shell has to show a '${text(tab)}' tab",
            )
        }
        onNodeWithText(text(Res.string.song_list_empty_title)).assertExists()
        onNodeWithText(text(Res.string.action_add_folder)).assertExists()
        // The pager composes only the page it is on, so the other two tabs' empty states must not be
        // here. That absence is what makes "the songs tab is the one showing" a real assertion.
        assertEquals(
            0,
            nodeCount(text(Res.string.empty_albums_title)),
            "the albums page must not be composed while the songs tab is the selected one",
        )
        assertEquals(
            0,
            nodeCount(text(Res.string.empty_artists_title)),
            "the artists page must not be composed while the songs tab is the selected one",
        )
    }

    @Test
    fun `clicking a tab moves the pager to that page, and back`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                LyricoNavHost()
            }
        }

        onNodeWithText(text(Res.string.library_tab_albums)).performClick()
        waitUntil("wait for the albums page to replace the songs page", 20_000) {
            nodeCount(text(Res.string.empty_albums_title)) > 0 &&
                nodeCount(text(Res.string.song_list_empty_title)) == 0
        }

        onNodeWithText(text(Res.string.library_tab_artists)).performClick()
        waitUntil("wait for the artists page to replace the albums page", 20_000) {
            nodeCount(text(Res.string.empty_artists_title)) > 0 &&
                nodeCount(text(Res.string.empty_albums_title)) == 0
        }

        onNodeWithText(text(Res.string.library_tab_songs)).performClick()
        waitUntil("wait for the songs page to come back", 20_000) {
            nodeCount(text(Res.string.song_list_empty_title)) > 0 &&
                nodeCount(text(Res.string.empty_artists_title)) == 0
        }
    }

    @Test
    fun `clicking a tab leaves selection mode, so the songs tab returns with its default top bar`() =
        runComposeUiTest {
            placeTaggedFixture(
                musicDir = musicDir,
                fixture = "bladeenc.mp3",
                relativeDir = "Album One",
                title = "Solar Song",
                artist = "An Artist",
                album = "Album One",
                tags = koin(),
            )

            setContent {
                LyricoTheme {
                    LyricoNavHost()
                }
            }

            scanLibrary()
            waitUntil("wait for the scanned song to be composed", 30_000) {
                nodeCount("Solar Song") > 0
            }

            // Long press is the gesture that starts selection, exactly as on the songs page's own test.
            onNodeWithText("Solar Song").performTouchInput { longClick() }
            waitUntil("wait for the selection bar to replace the title bar", 5_000) {
                nodeCount(text(Res.string.selection_mode_selected_count, 1)) > 0
            }

            onNodeWithText(text(Res.string.library_tab_artists)).performClick()
            waitUntil("wait for the artists tab to show the artist the scan indexed", 30_000) {
                nodeCount(text(Res.string.artist_list_title, 1)) > 0
            }

            // Coming *back* is what makes this test worth having: leaving the songs tab disposes its
            // composable but not its view model, so if the shell did not clear the selection the
            // selection bar would still be here when the page is composed again.
            onNodeWithText(text(Res.string.library_tab_songs)).performClick()
            waitUntil("wait for the songs page to come back", 20_000) {
                nodeCount("Solar Song") > 0
            }

            assertEquals(
                0,
                nodeCount(text(Res.string.selection_mode_selected_count, 1)),
                "a tab click exits selection mode, and the shell shares the songs page's " +
                    "SongSelectionViewModel, so nothing may still be selected",
            )
            onNodeWithContentDescription(text(Res.string.cd_sort)).assertExists()
        }

    @Test
    fun `a window wider than the rail threshold stands the tabs beside the page`() = runComposeUiTest {
        // Wider than the shell's own threshold, so the branch under test is decided by the port's rule
        // rather than by a hard-coded number that could drift away from it.
        setContent {
            LyricoTheme {
                Box(
                    modifier = Modifier.requiredSize(
                        width = LibraryHomeRailMinWidth + 160.dp,
                        height = 600.dp,
                    ),
                ) {
                    LyricoNavHost(modifier = Modifier.fillMaxSize())
                }
            }
        }

        val tab = singleBounds(text(Res.string.library_tab_songs))
        val page = singleBounds(text(Res.string.song_list_empty_title))
        assertTrue(
            tab.center.x < page.left,
            "with a rail the tab strip sits beside the page, but the 'Songs' tab is at $tab " +
                "and the page starts at x=${page.left}",
        )
    }

    @Test
    fun `a window narrower than the rail threshold puts the tabs under the page, and they still work`() =
        runComposeUiTest {
            setContent {
                LyricoTheme {
                    Box(
                        modifier = Modifier.requiredSize(
                            width = LibraryHomeRailMinWidth - 320.dp,
                            height = 600.dp,
                        ),
                    ) {
                        LyricoNavHost(modifier = Modifier.fillMaxSize())
                    }
                }
            }

            val tab = singleBounds(text(Res.string.library_tab_songs))
            val page = singleBounds(text(Res.string.song_list_empty_title))
            assertTrue(
                tab.center.y > page.bottom,
                "below the rail threshold the tab strip sits under the page, but the 'Songs' tab " +
                    "is at $tab and the page ends at y=${page.bottom}",
            )

            // The bar's items are clicked too, so the narrow layout is covered end to end rather than
            // only measured.
            onNodeWithText(text(Res.string.library_tab_albums)).performClick()
            waitUntil("wait for the bar's own click to move the pager", 20_000) {
                nodeCount(text(Res.string.empty_albums_title)) > 0
            }
        }
}
