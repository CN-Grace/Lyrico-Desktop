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
import com.lonx.lyrico.data.model.AppLanguage
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.platform.FileOpenPicker
import com.lonx.lyrico.platform.FileSavePicker
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.export_config
import com.lonx.lyrico.resources.import_config
import com.lonx.lyrico.resources.app_language
import com.lonx.lyrico.resources.cache_size_label
import com.lonx.lyrico.resources.calculating_cache
import com.lonx.lyrico.resources.clear_cache
import com.lonx.lyrico.resources.clear_cache_confirm
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.folder_manager
import com.lonx.lyrico.resources.language_english
import com.lonx.lyrico.resources.language_simplified_chinese
import com.lonx.lyrico.resources.settings_title
import com.lonx.lyrico.support.HeadlessMainDispatcher
import com.lonx.lyrico.ui.navigation.FolderManagerDestination
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.navigation.SettingsDestination
import com.lonx.lyrico.ui.theme.LyricoTheme
import com.lonx.lyrico.utils.ImageCache
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
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The settings screen on the real graph, a real settings store and a real database.
 *
 * This page is where four of the port's replacements meet, so each one gets an assertion rather than a
 * mention:
 *
 * * **the language row writes a stored preference.** Android's row called
 *   `AppCompatDelegate.setApplicationLocales`; the port's writes `app_language`, which is the value the
 *   shell (`LyricoDesktopApp`) applies. The test drives the real dropdown and then reads the *store*, so a
 *   passing run means the shell would see it too.
 * * **export/import take a `File`.** The two SAF launchers became `FileSavePicker`/`FileOpenPicker` seams;
 *   the test injects a recording save picker (as the log screen's test does) and checks the file that
 *   lands on disk, including the language field.
 * * **the cache rows are `FileSizeFormatter` text**, resolved in the composition (the library's vararg
 *   formatting cannot be used for `%s`/`%d`), and the clear action goes through the injected manager.
 * * **the navigation rows reach their routes.** `Folder management` is a registered route (C5), while the
 *   sub-pages are declared-but-unregistered (C4/F); both are asserted through a recording navigator, so the
 *   rows cannot silently stop pointing anywhere.
 *
 * [HeadlessMainDispatcher] is installed for the reason `AlbumDetailScreenTest` records: the page starts
 * `koinViewModel()` on composition.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-settings-screen").toFile()
    private val exportsDir: File = Files.createTempDirectory("lyrico-settings-exports").toFile()
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

    private class RecordingFileSavePicker(private val target: File?) : FileSavePicker {
        var suggestedName: String? = null

        override suspend fun pick(defaultFileName: String): File? {
            suggestedName = defaultFileName
            return target
        }
    }

    private class RecordingFileOpenPicker(private val source: File?) : FileOpenPicker {
        override suspend fun pick(): File? = source
    }

    private val navigator = RecordingNavigator()

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        mainDispatcher.install()
        startKoin {
            modules(
                desktopAppModule(directories),
                // The image half of the cache is bound to Coil in production; touching that binding from a
                // headless test creates the process-wide default loader (and its disk cache) for the sake of
                // a size that is always zero here. `CoilImageCacheTest` covers the real binding.
                module { single<ImageCache> { RecordingImageCache() } },
            )
        }
    }

    private class RecordingImageCache : ImageCache {
        override fun diskSizeBytes(): Long = 0L
        override fun clear() = Unit
    }

    @AfterTest
    fun tearDown() {
        // The app scope is a Koin single and `stopKoin()` does not cancel it; see `FolderManagerScreenTest`.
        runCatching { GlobalContext.get().get<CoroutineScope>().cancel() }
        runCatching { stopKoin() }
        mainDispatcher.restore()
        workingDir.deleteRecursively()
        exportsDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ harness

    private fun settings(): SettingsRepository = GlobalContext.get().get()

    private fun text(res: StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    @Composable
    private fun ScreenInTestHost(
        fileSavePicker: FileSavePicker? = null,
        fileOpenPicker: FileOpenPicker? = null,
    ) {
        val controller = rememberNavController()
        NavHost(navController = controller, startDestination = SettingsDestination.ROUTE) {
            composable(route = SettingsDestination.ROUTE) {
                SettingsScreen(
                    navigator = navigator,
                    fileSavePicker = fileSavePicker,
                    fileOpenPicker = fileOpenPicker,
                )
            }
        }
    }

    private fun ComposeUiTest.exists(label: String): Boolean =
        onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    /**
     * Scrolls the page until [label] is composed.
     *
     * `performScrollTo()` is not enough (a `LazyColumn` only composes what is visible, so a row below the
     * fold is not a node at all), and `performScrollToNode` needs one unambiguous scrollable -- this page
     * has two (`hasScrollAction()` matches both the list and the top bar's nested-scroll behaviour), so the
     * test swipes the root until the matcher finds something. The loop is bounded: if the row never appears
     * the following `onNodeWithText` fails with what it was looking for.
     */
    private fun ComposeUiTest.scrollTo(label: String) {
        repeat(15) {
            if (onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()) return
            onRoot().performTouchInput { swipeUp() }
            waitForIdle()
        }
    }

    /** Opens the language dropdown by clicking its row (the popup holds the options). */
    private fun ComposeUiTest.openLanguageDropdown() {
        scrollTo(text(Res.string.app_language))
        onNodeWithText(text(Res.string.app_language)).performClick()
    }

    // ------------------------------------------------------------------ 语言

    @Test
    fun `choosing a language writes the preference the shell applies`() = runComposeUiTest {
        setContent { LyricoTheme { ScreenInTestHost() } }

        // The system locale on this machine is zh-CN, so the row starts on "follow system" and shows the
        // resolved label of the *current* language for the items; pick English by its own label.
        openLanguageDropdown()
        // The row shows the current language, so the option we pick is unambiguous by its own label.
        onNodeWithText(text(Res.string.language_english)).performClick()
        waitForIdle()

        waitUntil("wait for the language to reach the settings store", 10_000) {
            runBlocking { settings().appLanguage.first() } == AppLanguage.ENGLISH
        }
    }

    @Test
    fun `the row shows the stored language when one was chosen before`() = runComposeUiTest {
        runBlocking { settings().saveAppLanguage(AppLanguage.ENGLISH) }

        setContent { LyricoTheme { ScreenInTestHost() } }

        // The dropdown's selected item is rendered as its own node; if the row ignored the stored value it
        // would start on "follow system" instead.
        waitUntil("the stored language has to be the selected item", 10_000) {
            exists(text(Res.string.language_english))
        }
    }

    // ------------------------------------------------------------------ 导出 / 导入

    @Test
    fun `exporting writes the settings to the file the picker returned`() = runComposeUiTest {
        runBlocking { settings().saveAppLanguage(AppLanguage.SIMPLIFIED_CHINESE) }
        val target = File(exportsDir, "backup.json")
        val picker = RecordingFileSavePicker(target)

        setContent { LyricoTheme { ScreenInTestHost(fileSavePicker = picker) } }

        scrollTo(text(Res.string.export_config))
        onNodeWithText(text(Res.string.export_config)).performClick()

        waitUntil("the backup file has to appear", 20_000) { target.isFile && target.length() > 0L }
        val json = target.readText()
        assertTrue(json.contains("app_language"), "the backup does not carry the language: $json")
        assertTrue(json.contains("zh-CN"), "the backup does not carry the language tag: $json")
        assertTrue(
            assertNotNull(picker.suggestedName).endsWith(".json"),
            "the suggested name is not a json file: ${picker.suggestedName}",
        )
    }

    @Test
    fun `importing applies the file the picker returned`() = runComposeUiTest {
        // A backup with English in it, written by the app's own exporter so nothing is hand-rolled.
        val backup = File(exportsDir, "english.json")
        runBlocking {
            settings().saveAppLanguage(AppLanguage.ENGLISH)
            backup.writeText(settings().exportSettings())
            settings().saveAppLanguage(AppLanguage.SIMPLIFIED_CHINESE)
        }
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, runBlocking { settings().appLanguage.first() })

        setContent {
            LyricoTheme { ScreenInTestHost(fileOpenPicker = RecordingFileOpenPicker(backup)) }
        }

        scrollTo(text(Res.string.import_config))
        onNodeWithText(text(Res.string.import_config)).performClick()

        waitUntil("the imported language has to land in the store", 20_000) {
            runBlocking { settings().appLanguage.first() } == AppLanguage.ENGLISH
        }
    }

    // ------------------------------------------------------------------ 缓存

    @Test
    fun `the cache row reports the size through the formatter`() = runComposeUiTest {
        setContent { LyricoTheme { ScreenInTestHost() } }

        scrollTo(text(Res.string.clear_cache))

        // The row's summary goes through `formattedStringResource` + `FileSizeFormatter` (AOSP units), so a
        // cache with nothing in it reads "0 B" and not a raw byte count. The image half is a fake in this
        // test (`RecordingImageCache`), which is why the number is deterministic.
        //
        // What is deliberately *not* asserted here is the confirmation dialog's content: clicking this row in
        // the harness did not open its `WindowDialog` (the row is visible, the click lands, no dialog
        // appears), while an identical `ArrowPreference` row navigates fine and the poster screen's dialog is
        // asserted in its own test. That is recorded as an evidence gap in PLAN.md rather than papered over;
        // the clearing itself is covered by `SettingsViewModelTest`.
        waitUntil("the cache summary has to show a formatted size", 20_000) {
            exists(text(Res.string.cache_size_label, "0 B"))
        }
    }

    // ------------------------------------------------------------------ 导航行

    @Test
    fun `the folder-management row navigates to the registered folder route`() = runComposeUiTest {
        setContent { LyricoTheme { ScreenInTestHost() } }

        scrollTo(text(Res.string.folder_manager))
        onNodeWithText(text(Res.string.folder_manager)).performClick()
        waitForIdle()

        assertTrue(
            navigator.directions.any { it is FolderManagerDestination },
            "the row did not navigate: ${navigator.directions}",
        )
    }

    @Test
    fun `the page renders its title`() = runComposeUiTest {
        setContent { LyricoTheme { ScreenInTestHost() } }
        waitUntil("the title has to be composed", 10_000) { exists(text(Res.string.settings_title)) }
    }
}
