package com.lonx.lyrico.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_refresh_folder
import com.lonx.lyrico.resources.artist_poster_back
import com.lonx.lyrico.resources.artist_poster_file_count
import com.lonx.lyrico.resources.artist_poster_folder_add
import com.lonx.lyrico.resources.artist_poster_folder_remove_tip
import com.lonx.lyrico.resources.artist_poster_folder_unavailable
import com.lonx.lyrico.resources.artist_poster_folders_summary
import com.lonx.lyrico.resources.artist_poster_no_files
import com.lonx.lyrico.resources.cd_more_actions
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.dialog_remove_folder_title
import com.lonx.lyrico.resources.folder_action_remove
import com.lonx.lyrico.support.HeadlessMainDispatcher
import com.lonx.lyrico.ui.navigation.ArtistPosterFoldersDestination
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.theme.LyricoTheme
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * 海报文件夹页：真实目录 + 真实 ViewModel + 真实 Koin 模块，只有导航器是记账用的。
 *
 * 海报文件夹用的是普通目录，所以这里比别的页面测试便宜：不需要扫描、不需要音频夹具，直接造几个文件
 * 再让页面去读。被测的仍然是那三件只有界面能证明的事：
 *
 * * **`posters == null` 与空列表显示的是两句不同的话** —— 这两种情形都是真造出来的（删掉目录 / 目录在但没图）。
 * * **只有海报扩展名会显示**，普通文件不进网格。
 * * **移出文件夹只动设置，不动磁盘上的图**，这正是确认框里那句话承诺的。
 *
 * [HeadlessMainDispatcher] 照 `AlbumDetailScreenTest` 的理由装上：这个页面一进来就
 * `koinViewModel()`，它要在这个 NavHost 的 entry 上起生命周期。
 */
@OptIn(ExperimentalTestApi::class)
class ArtistPosterFoldersScreenTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-poster-folders-screen").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()
    private val posterDir: File = Files.createTempDirectory("lyrico-poster-folders-shared").toFile()
    private val mainDispatcher = HeadlessMainDispatcher()

    private class RecordingNavigator : Navigator {
        val directions = mutableListOf<NavDirection>()
        var popCount = 0

        override fun navigate(direction: NavDirection) {
            directions += direction
        }

        override fun popBackStack(): Boolean {
            popCount++
            return true
        }

        override fun navigateUp(): Boolean = false
    }

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        mainDispatcher.install()
        startKoin { modules(desktopAppModule(directories)) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        mainDispatcher.restore()
        workingDir.deleteRecursively()
        posterDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ harness

    private fun settings(): SettingsRepository = GlobalContext.get().get()

    private fun poster(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): File =
        File(posterDir, name).apply { writeBytes(bytes) }

    private fun chooseFolder() = runBlocking {
        settings().setArtistPosterFolder(posterDir.absolutePath)
        settings().artistPosterFolder.first()
    }

    /**
     * 页面挂在一个真实的 `NavHost` entry 上，跟发布版一样 —— `koinViewModel()` 解析的就是这个 entry。
     */
    @Composable
    private fun ScreenInTestHost(navigator: Navigator = RecordingNavigator()) {
        val controller = rememberNavController()
        NavHost(
            navController = controller,
            startDestination = ArtistPosterFoldersDestination.ROUTE,
        ) {
            composable(route = ArtistPosterFoldersDestination.ROUTE) {
                ArtistPosterFoldersScreen(navigator = navigator)
            }
        }
    }

    /** 期望文案按 `formattedStringResource` 的算法拼：`getString` 之后再 `String.format`。 */
    private fun text(res: StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.exists(text: String): Boolean =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.countOfContentDescription(description: String): Int =
        onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    /** 会走到 `NavController.popBackStack()` 的点击，得跟真实点击一样在 UI 线程上派发。 */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.clickAndNavigate(matcher: SemanticsMatcher) {
        runOnUiThread { onNode(matcher).performClick() }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `a chosen folder shows its path, its poster count, and only its posters`() = runComposeUiTest {
        poster("Aria.png")
        poster("Miles Davis.jpeg")
        poster("notes.txt")
        chooseFolder()

        setContent {
            LyricoTheme {
                ScreenInTestHost()
            }
        }

        waitUntil("wait for the folder header to be composed", 20_000) {
            exists(posterDir.absolutePath)
        }
        waitUntil("wait for the formatted poster count", 10_000) {
            exists(text(Res.string.artist_poster_file_count, 2))
        }
        assertTrue(exists("Aria.png"), "the poster's own name is under its thumbnail")
        assertTrue(exists("Miles Davis.jpeg"), "and so is the second one")
        assertEquals(0, onAllNodesWithText("notes.txt").fetchSemanticsNodes().size, "a .txt is not a poster")
        assertEquals(
            0,
            onAllNodesWithText(text(Res.string.artist_poster_folders_summary)).fetchSemanticsNodes().size,
            "a chosen folder replaces the 'choose a folder' explainer",
        )
        assertEquals(
            1,
            countOfContentDescription(text(Res.string.cd_more_actions)),
            "a chosen folder offers the overflow menu",
        )
    }

    @Test
    fun `without a folder the page explains itself, offers the picker, and has no overflow menu`() =
        runComposeUiTest {
            setContent {
                LyricoTheme {
                    ScreenInTestHost()
                }
            }

            waitUntil("wait for the empty state to be composed", 20_000) {
                exists(text(Res.string.artist_poster_folders_summary))
            }
            // The row is not there while the page is still reading the folder -- the spinner takes its place --
            // so this waits for the read to finish rather than for the render to start.
            waitUntil("wait for the folder read to settle and the picker row to appear", 20_000) {
                exists(text(Res.string.artist_poster_folder_add))
            }
            assertEquals(
                0,
                countOfContentDescription(text(Res.string.cd_more_actions)),
                "there is nothing to refresh, change or remove yet",
            )
            assertEquals(
                0,
                countOfContentDescription(text(Res.string.artist_poster_folder_unavailable)),
                "no folder chosen is not the same as a folder that went missing",
            )
        }

    @Test
    fun `the back icon leaves the page`() = runComposeUiTest {
        chooseFolder()
        val navigator = RecordingNavigator()

        setContent {
            LyricoTheme {
                ScreenInTestHost(navigator = navigator)
            }
        }

        waitUntil("wait for the top bar to be composed", 20_000) {
            countOfContentDescription(text(Res.string.artist_poster_back)) == 1
        }

        clickAndNavigate(hasContentDescription(text(Res.string.artist_poster_back)))

        assertEquals(1, navigator.popCount, "the back icon pops this page and nothing else")
        assertTrue(navigator.directions.isEmpty(), "back is not a navigate")
    }

    @Test
    fun `a refresh after the folder is deleted says the folder is gone, not that it is empty`() =
        runComposeUiTest {
            poster("Aria.png")
            chooseFolder()

            setContent {
                LyricoTheme {
                    ScreenInTestHost()
                }
            }

            waitUntil("wait for the poster to be composed", 20_000) { exists("Aria.png") }

            // The folder disappears behind the page's back (a drive unmounted, a sync client moved it).
            // Refreshing is the page's own action, so this walks the real code path rather than poking the
            // view model: the overflow menu's first row.
            assertTrue(posterDir.deleteRecursively(), "the fixture folder has to be removable")
            onNodeWithContentDescription(text(Res.string.cd_more_actions)).performClick()
            waitUntil("wait for the overflow menu to open", 10_000) {
                exists(text(Res.string.action_refresh_folder))
            }
            onNodeWithText(text(Res.string.action_refresh_folder)).performClick()

            waitUntil("wait for the folder to be reported unavailable", 20_000) {
                exists(text(Res.string.artist_poster_folder_unavailable))
            }
            assertEquals(
                0,
                onAllNodesWithText("Aria.png").fetchSemanticsNodes().size,
                "the poster is off the grid once its folder cannot be listed",
            )
        }

    @Test
    fun `an existing but empty folder says it has no posters`() = runComposeUiTest {
        chooseFolder()

        setContent {
            LyricoTheme {
                ScreenInTestHost()
            }
        }

        waitUntil("wait for the empty folder message", 20_000) {
            exists(text(Res.string.artist_poster_no_files))
        }
        assertEquals(
            0,
            onAllNodesWithText(text(Res.string.artist_poster_folder_unavailable)).fetchSemanticsNodes().size,
            "the folder was listed and is simply empty",
        )
    }

    @Test
    fun `removing the folder from the overflow menu unregisters it and keeps the posters on disk`() =
        runComposeUiTest {
            val aria = poster("Aria.png")
            chooseFolder()

            setContent {
                LyricoTheme {
                    ScreenInTestHost()
                }
            }

            waitUntil("wait for the poster to be composed", 20_000) { exists("Aria.png") }
            onNodeWithContentDescription(text(Res.string.cd_more_actions)).performClick()
            waitUntil("wait for the overflow menu to open", 10_000) {
                exists(text(Res.string.folder_action_remove))
            }
            onNodeWithText(text(Res.string.folder_action_remove)).performClick()

            // The confirmation names the folder that is about to be forgotten and warns that removing it is
            // not a delete -- the sentence the last assertion below holds the page to.
            waitUntil("wait for the confirmation dialog", 10_000) {
                exists(text(Res.string.dialog_remove_folder_title))
            }
            assertTrue(exists(posterDir.absolutePath), "the dialog names the folder being removed")
            assertTrue(
                exists(text(Res.string.artist_poster_folder_remove_tip)),
                "and carries the tip Android's dialog had",
            )

            onNodeWithText(text(Res.string.confirm)).performClick()

            waitUntil("wait for the page to fall back to the empty state", 20_000) {
                exists(text(Res.string.artist_poster_folder_add))
            }
            assertNull(
                runBlocking { settings().artistPosterFolder.first() },
                "the folder is unregistered from the settings",
            )
            assertTrue(aria.isFile, "the poster file is still where it was")
            assertTrue(
                byteArrayOf(1, 2, 3).contentEquals(aria.readBytes()),
                "and it is still the same image",
            )
        }
}
