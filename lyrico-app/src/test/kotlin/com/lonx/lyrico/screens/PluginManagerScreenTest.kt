package com.lonx.lyrico.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.data.model.entity.SourcePluginEntity
import com.lonx.lyrico.data.model.entity.isEnabledFor
import com.lonx.lyrico.data.model.plugin.PluginSourceType
import com.lonx.lyrico.data.repository.SourcePluginRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.plugin.source.SourcePluginInstaller
import com.lonx.lyrico.plugin.support.pluginManifestJson
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.plugin_empty
import com.lonx.lyrico.resources.plugin_import_archive
import com.lonx.lyrico.resources.plugin_import_install
import com.lonx.lyrico.resources.plugin_manager_title
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lonx.lyrico.ui.navigation.LyricoNavHost
import com.lonx.lyrico.ui.navigation.PluginManagerDestination
import com.lonx.lyrico.ui.theme.LyricoTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The plugin manager, rendered and driven for real.
 *
 * This screen is where the desktop port's most Android-specific flow had to be rebuilt: the file
 * dialog was a Storage Access Framework launcher returning a `content://` URI, and a plugin archive
 * was read through `Context.contentResolver`. The port replaced that with a filesystem path, so the
 * thing worth proving is that the replacement works end to end -- the user picks a `.zip` on disk,
 * the shipped installer reads it, unpacks it under the app's own install root, writes a Room row, and
 * the list redraws from that row.
 *
 * So the test installs through the real [SourcePluginInstaller] resolved from the app's Koin graph, into
 * the real `AppDirectories.pluginInstallRoot` under a temp data directory, and composes the real route
 * through [LyricoNavHost]. Only two seams are replaced, both OS-owned:
 *
 * * `fileOpenPicker` -- the native dialog; it answers with a real `.zip` file the test wrote.
 * * nothing else: the Koin graph, Room, the installer, the manifest read and the list are shipped code.
 */
class PluginManagerScreenTest {

    private val workingDir: File = screenTempDir("lyrico-plugin-manager")
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()

    private lateinit var installer: SourcePluginInstaller
    private lateinit var repository: SourcePluginRepository

    /** Koin is process-global, so the graph is rebuilt per test. */
    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        installer = GlobalContext.get().get()
        repository = GlobalContext.get().get()
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        workingDir.deleteRecursively()
    }

    private fun text(res: org.jetbrains.compose.resources.StringResource): String =
        runBlocking { getString(res) }

    private fun metadataManifest(name: String) = pluginManifestJson(
        id = "net.example.manager",
        name = name,
        capabilities = listOf("searchSongs"),
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an installed plugin is listed from its real database row and manifest`() = runComposeUiTest {
        PluginScreenFixtures.install(
            installer = installer,
            installRoot = directories.pluginInstallRoot,
            manifestJson = metadataManifest("Example Source"),
        )

        setContent {
            LyricoTheme {
                // The shipped host with the shipped route: this covers the registration, the Koin
                // resolution against a real back-stack entry, and the manifest read off disk.
                LyricoNavHost(startDestination = PluginManagerDestination())
            }
        }

        waitUntil("the plugin row must arrive from Room", 5_000) {
            onAllNodesWithText("Example Source").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(text(Res.string.plugin_manager_title)).assertExists()
        // The manifest's version and host-API versions are rendered from the plugin's own manifest.json,
        // which means the file was found under the install root and parsed -- not read from the row.
        onNodeWithText("1.0.0", substring = true).assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `picking an archive and confirming the import unpacks it and adds it to the list`() = runComposeUiTest {
        val archive = PluginScreenFixtures.archiveFile(
            directory = workingDir,
            fileName = "Picked-Source.zip",
            manifestJson = metadataManifest("Picked Source"),
        )
        val picker = RecordingFileOpenPicker(archive)

        setContent {
            LyricoTheme {
                PluginManagerScreenInHost(picker)
            }
        }

        waitUntil("the empty state", 5_000) {
            onAllNodesWithText(text(Res.string.plugin_empty)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(text(Res.string.plugin_import_archive)).performClick()

        // The install button only exists once `prepareImport` has accepted the archive and produced
        // candidates, so waiting for it is waiting for the real zip to have been read and validated.
        waitUntil("the import dialog must list the archive's candidate", 5_000) {
            onAllNodesWithText(text(Res.string.plugin_import_install)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(text(Res.string.plugin_import_install)).performClick()

        waitUntil("the installed plugin must appear in the list", 5_000) {
            onAllNodesWithText("Picked Source").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, picker.pickCount, "the dialog must be asked exactly once")

        // And it is on disk and in the database, not just on screen: the archive was unpacked under the
        // graph's own install root, and the row the list rendered came from Room.
        val row = runBlocking { repository.observePluginsOnce().single() }
        assertEquals("Picked Source", row.name)
        assertTrue(
            File(row.pluginDir, "manifest.json").isFile,
            "the plugin must be unpacked under ${row.pluginDir}",
        )
        assertTrue(
            row.pluginDir.startsWith(directories.pluginInstallRoot.absolutePath),
            "the plugin must live under the app's install root, was ${row.pluginDir}",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `toggling the switch writes the enable flag to the database`() = runComposeUiTest {
        val installed = PluginScreenFixtures.install(
            installer = installer,
            installRoot = directories.pluginInstallRoot,
            manifestJson = metadataManifest("Toggle Me"),
            enabled = false,
        )

        setContent {
            LyricoTheme {
                PluginManagerScreenInHost(fileOpenPicker = null)
            }
        }

        waitUntil("the plugin row", 5_000) {
            onAllNodesWithText("Toggle Me").fetchSemanticsNodes().isNotEmpty()
        }
        onNode(isToggleable()).assertIsOff().performClick()

        // The switch only turns on because Room emitted the updated row back through the view model, so
        // this is also the assertion that the write landed.
        waitUntil("the switch must reflect the stored flag", 5_000) {
            runCatching { onNode(isToggleable()).assertIsOn() }.isSuccess
        }
        val row = assertNotNull(runBlocking { repository.getPlugin(installed.id) })
        assertTrue(
            row.isEnabledFor(PluginSourceType.METADATA),
            "the metadata enable flag must be persisted, row was $row",
        )
    }

    /**
     * The screen with its picker seam, inside a real back-stack entry so `koinViewModel()` resolves the
     * way it does in the shipped host.
     */
    @Composable
    private fun PluginManagerScreenInHost(
        fileOpenPicker: RecordingFileOpenPicker?,
    ) {
        val controller = rememberNavController()
        NavHost(
            navController = controller,
            startDestination = PluginManagerDestination.ROUTE,
        ) {
            composable(PluginManagerDestination.ROUTE) {
                PluginManagerScreen(navigator = NoOpNavigator, fileOpenPicker = fileOpenPicker)
            }
        }
    }
}

/** A plugin manager that never navigates; this screen's own navigation is a later batch's test. */
private object NoOpNavigator : com.lonx.lyrico.ui.navigation.Navigator {
    override fun navigate(direction: com.lonx.lyrico.ui.navigation.NavDirection): Unit =
        error("the plugin manager does not navigate in this test")

    override fun popBackStack(): Boolean = false

    override fun navigateUp(): Boolean = false
}

/** One shot of the plugin table, for assertions that need the stored row rather than the drawn list. */
private suspend fun SourcePluginRepository.observePluginsOnce(): List<SourcePluginEntity> =
    observePlugins().first()
