package com.lonx.lyrico.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lonx.lyrico.data.editfield.EditFieldConfigRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.add_no_split_artist
import com.lonx.lyrico.resources.artist_split_settings_title
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.edit_field_settings_title
import com.lonx.lyrico.resources.invalid_empty_input
import com.lonx.lyrico.resources.non_lyrics_cleanup_rules_title
import com.lonx.lyrico.support.HeadlessMainDispatcher
import com.lonx.lyrico.ui.navigation.ArtistSplitSettingsDestination
import com.lonx.lyrico.ui.navigation.EditFieldSettingsDestination
import com.lonx.lyrico.ui.navigation.LyricsCleanupRulesDestination
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.theme.LyricoTheme
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The three settings sub-pages, each mounted in its real route on the real graph.
 *
 * These are siblings of `SettingsScreen` and they share its port decisions, so the coverage aims at those
 * rather than at re-testing the view models (each has its own test class):
 *
 * * **the pages compose with real stored data.** Every page reads its settings from the live store, so a
 *   screen that forgot to migrate a string -- or that looks up a resource the port does not have -- fails
 *   here instead of in a screenshot.
 * * **`Toast` became a Snackbar.** `ArtistSplitSettingsScreen` was the one reporting validation errors
 *   through a `Toast`; the test drives the rejected input and asserts the message arrives as a snackbar.
 *
 * The interaction depth is deliberately lighter than `SettingsScreenTest`'s: these pages are long forms whose
 * per-widget behaviour is already pinned by their view-model tests, and what the *screen* adds is
 * composition, strings and the message path.
 *
 * [HeadlessMainDispatcher] is installed for the reason `AlbumDetailScreenTest` records: each page starts
 * `koinViewModel()` on composition, and lifecycle 2.11 requires that on the main thread.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsSubPagesTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-settings-subpages").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()
    private val mainDispatcher = HeadlessMainDispatcher()

    private class RecordingNavigator : Navigator {
        val directions = mutableListOf<NavDirection>()

        override fun navigate(direction: NavDirection) {
            directions += direction
        }

        override fun popBackStack(): Boolean = true

        override fun navigateUp(): Boolean = true
    }

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        mainDispatcher.install()
        startKoin { modules(desktopAppModule(directories)) }
    }

    @AfterTest
    fun tearDown() {
        // The app scope is a Koin single and `stopKoin()` does not cancel it; see `FolderManagerScreenTest`.
        runCatching { GlobalContext.get().get<CoroutineScope>().cancel() }
        runCatching { stopKoin() }
        mainDispatcher.restore()
        workingDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ harness

    private fun settings(): SettingsRepository = GlobalContext.get().get()
    private fun editFields(): EditFieldConfigRepository = GlobalContext.get().get()

    private fun text(res: StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    /** Scrolls a page until [label] is composed (a `LazyColumn` only composes what is visible). */
    private fun ComposeUiTest.scrollTo(label: String) {
        repeat(15) {
            if (onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()) return
            onRoot().performTouchInput { swipeUp() }
            waitForIdle()
        }
    }

    private fun ComposeUiTest.exists(label: String): Boolean =
        onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    @Composable
    private fun PageInTestHost(route: String, screen: @Composable (Navigator) -> Unit) {
        val controller = rememberNavController()
        NavHost(navController = controller, startDestination = route) {
            composable(route = route) { screen(RecordingNavigator()) }
        }
    }

    // ------------------------------------------------------------------ 编辑字段设置页

    @Test
    fun `the edit-field page renders its title`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                PageInTestHost(EditFieldSettingsDestination.ROUTE) { EditFieldSettingsScreen(it) }
            }
        }
        waitUntil("the title has to be composed", 20_000) {
            exists(text(Res.string.edit_field_settings_title))
        }
    }

    @Test
    fun `the edit-field page shows a custom tag that is already configured`() = runComposeUiTest {
        val tag = "测试自定义标签"
        runBlocking { editFields().addCustomTag(tag) }

        setContent {
            LyricoTheme {
                PageInTestHost(EditFieldSettingsDestination.ROUTE) { EditFieldSettingsScreen(it) }
            }
        }

        scrollTo(tag)
        waitUntil("the configured custom tag has to be listed", 20_000) { exists(tag) }
    }

    // ------------------------------------------------------------------ 歌词清理规则页

    @Test
    fun `the lyrics-cleanup page renders its title`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                PageInTestHost(LyricsCleanupRulesDestination.ROUTE) { LyricsCleanupRulesScreen(it) }
            }
        }
        waitUntil("the title has to be composed", 20_000) {
            exists(text(Res.string.non_lyrics_cleanup_rules_title))
        }
    }

    @Test
    fun `the lyrics-cleanup page lists the rules from the settings store`() = runComposeUiTest {
        val rule = "[测试作词]"
        runBlocking { settings().saveLyricsTagLineKeywords(listOf(rule)) }

        setContent {
            LyricoTheme {
                PageInTestHost(LyricsCleanupRulesDestination.ROUTE) { LyricsCleanupRulesScreen(it) }
            }
        }

        scrollTo(rule)
        waitUntil("the stored rule has to be listed", 20_000) { exists(rule) }
    }

    // ------------------------------------------------------------------ 艺术家分隔设置页

    @Test
    fun `the artist-split page renders its title`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                PageInTestHost(ArtistSplitSettingsDestination.ROUTE) { ArtistSplitSettingsScreen(it) }
            }
        }
        waitUntil("the title has to be composed", 20_000) {
            exists(text(Res.string.artist_split_settings_title))
        }
    }

    @Test
    fun `the artist-split page reports a rejected input as a snackbar`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                PageInTestHost(ArtistSplitSettingsDestination.ROUTE) { ArtistSplitSettingsScreen(it) }
            }
        }

        // Adding a no-split artist with an empty name is the input the view model rejects; Android reported
        // that with a `Toast`, and the port replaced it with a snackbar. Submitting the dialog without typing
        // anything is the invalid input, so no text injection is needed.
        scrollTo(text(Res.string.add_no_split_artist))
        onNodeWithText(text(Res.string.add_no_split_artist)).performClick()
        waitForIdle()
        onNodeWithText(text(Res.string.confirm)).performClick()

        waitUntil("the validation message has to arrive as a snackbar", 20_000) {
            exists(text(Res.string.invalid_empty_input))
        }
        assertTrue(
            runBlocking { settings().artistSplitConfigFlow.first() }.customNoSplitArtists.isEmpty(),
            "the rejected input was written to the config after all",
        )
    }
}
