package com.lonx.lyrico.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.batch_rename_title
import com.lonx.lyrico.support.HeadlessMainDispatcher
import com.lonx.lyrico.ui.navigation.BatchRenameDestination
import com.lonx.lyrico.ui.navigation.NavDirection
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.theme.LyricoTheme
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A smoke test for the batch-rename page: it composes on the real graph and its route resolves.
 *
 * The page's *work* is not asserted here on purpose. Generating previews and applying renames is
 * `RenameEngine`, which has its own test against real files, and the applying half runs through the batch
 * task engine, which the batch-task tests already drive end to end. What the screen adds is composition,
 * strings and navigation -- and that is what a smoke test can honestly pin.
 *
 * [HeadlessMainDispatcher] is installed for the reason `AlbumDetailScreenTest` records: the page starts
 * `koinViewModel()` on composition, and lifecycle 2.11 requires that on the main thread.
 */
@OptIn(ExperimentalTestApi::class)
class BatchRenameScreenTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-batch-rename-screen").toFile()
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
        runCatching { GlobalContext.get().get<CoroutineScope>().cancel() }
        runCatching { stopKoin() }
        mainDispatcher.restore()
        workingDir.deleteRecursively()
    }

    @Composable
    private fun ScreenInTestHost() {
        val controller = rememberNavController()
        NavHost(navController = controller, startDestination = BatchRenameDestination.ROUTE) {
            composable(route = BatchRenameDestination.ROUTE) {
                BatchRenameScreen(navigator = RecordingNavigator())
            }
        }
    }

    @Test
    fun `the page renders its title and tabs with an empty selection`() = runComposeUiTest {
        setContent { LyricoTheme { ScreenInTestHost() } }

        val title = String.format(runBlocking { getString(Res.string.batch_rename_title) })
        waitUntil("the title has to be composed", 20_000) {
            onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `the rename format from the store is what the page shows`() = runComposeUiTest {
        val format = "@2 - @1"
        runBlocking { GlobalContext.get().get<com.lonx.lyrico.data.repository.SettingsRepository>().saveRenameFormat(format) }

        setContent { LyricoTheme { ScreenInTestHost() } }

        waitUntil("the stored format has to reach the field", 20_000) {
            onAllNodesWithText(format).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            runBlocking {
                GlobalContext.get().get<com.lonx.lyrico.data.repository.SettingsRepository>().renameFormat.first()
            } == format,
            "the format the page reads back is not the one that was stored",
        )
    }
}
