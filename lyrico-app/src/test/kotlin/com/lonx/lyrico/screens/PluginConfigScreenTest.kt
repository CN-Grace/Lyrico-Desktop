package com.lonx.lyrico.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.plugin.source.SourcePluginInstaller
import com.lonx.lyrico.plugin.support.ManifestConfigField
import com.lonx.lyrico.plugin.support.pluginManifestJson
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.source_config_invalid_source
import com.lonx.lyrico.resources.source_config_required_error
import com.lonx.lyrico.resources.source_config_save
import com.lonx.lyrico.resources.source_config_saved
import com.lonx.lyrico.ui.navigation.LyricoNavHost
import com.lonx.lyrico.ui.navigation.PluginConfigDestination
import com.lonx.lyrico.ui.theme.LyricoTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A plugin's settings page, driven against a real installed plugin.
 *
 * What is being proven here is not "a form renders". The interesting claims are that
 *
 * * the form's fields come from the **manifest on disk** (not from a constant or a stub provider),
 *   which is the whole reason [com.lonx.lyrico.plugin.source.SearchSourceProvider] exists,
 * * a required field blocks the save and shows the port's own localised message,
 * * a successful save lands in the real settings store, and
 * * the markdown field renders through the port's new bridge -- the one substitution in this screen
 *   that is *not* mechanical, because Android had `dev.jeziellago:compose-markdown` and the
 *   multiplatform replacement needs its colours and typography mapped onto this app's theme.
 */
class PluginConfigScreenTest {

    private val workingDir: File = screenTempDir("lyrico-plugin-config")
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()

    private lateinit var installer: SourcePluginInstaller
    private lateinit var settings: SettingsRepository

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        installer = GlobalContext.get().get()
        settings = GlobalContext.get().get()
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        workingDir.deleteRecursively()
    }

    private fun text(res: StringResource): String = runBlocking { getString(res) }

    /** A search source whose shape is entirely described by its manifest. */
    private fun installConfigurable(
        id: String = "net.example.configurable",
        name: String = "Configurable Source",
        configFields: List<ManifestConfigField>,
    ) = PluginScreenFixtures.install(
        installer = installer,
        installRoot = directories.pluginInstallRoot,
        manifestJson = pluginManifestJson(
            id = id,
            name = name,
            capabilities = listOf("searchSongs"),
            configFields = configFields,
        ),
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a required field blocks the save, then the saved value reaches the settings store`() = runComposeUiTest {
        val plugin = installConfigurable(
            configFields = listOf(
                ManifestConfigField(key = "apiKey", title = "API Key", required = true),
                ManifestConfigField(
                    key = "baseUrl",
                    title = "Base URL",
                    required = false,
                    defaultValue = "https://example.com",
                ),
            ),
        )

        setContent {
            LyricoTheme {
                LyricoNavHost(startDestination = PluginConfigDestination(plugin.id))
            }
        }

        // `load(pluginId)` is asynchronous and goes through the plugin manager, so the titles appearing
        // means the manifest was located, parsed and handed to the form.
        waitUntil("the plugin's own name becomes the title", 5_000) {
            onAllNodesWithText("Configurable Source").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("API Key").assertExists()
        onNodeWithText("https://example.com").assertExists()

        onNodeWithContentDescription(text(Res.string.source_config_save)).performClick()
        waitUntil("the empty required field must report the port's message", 5_000) {
            onAllNodesWithText(text(Res.string.source_config_required_error)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            emptyMap(),
            runBlocking { settings.getSourceSettings(plugin.id).values },
            "a rejected save must not write anything",
        )

        onAllNodes(hasSetTextAction())[0].performTextInput("hunter2")
        onNodeWithContentDescription(text(Res.string.source_config_save)).performClick()

        waitUntil("the save confirmation snackbar", 5_000) {
            onAllNodesWithText(text(Res.string.source_config_saved)).fetchSemanticsNodes().isNotEmpty()
        }
        val stored = runBlocking { settings.getSourceSettings(plugin.id).values }
        assertEquals("hunter2", stored["apiKey"])
        assertEquals("https://example.com", stored["baseUrl"], "the manifest default must be saved too")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a markdown field renders through the desktop markdown bridge`() = runComposeUiTest {
        val plugin = installConfigurable(
            id = "net.example.markdown",
            name = "Markdown Source",
            configFields = listOf(
                ManifestConfigField(
                    key = "readme",
                    title = "Read me",
                    type = "markdown",
                    required = false,
                    defaultValue = "## Setup\n\nPaste your token below.",
                ),
            ),
        )

        setContent {
            LyricoTheme {
                LyricoNavHost(startDestination = PluginConfigDestination(plugin.id))
            }
        }

        // The markdown field is the only one, so `hasConfigContent` is true only if the renderer drew
        // it: the heading text is the proof that `MiuixMarkdownText` composed real content rather than
        // drawing nothing.
        waitUntil("the markdown heading must be rendered as text", 5_000) {
            onAllNodesWithText("Setup").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            onAllNodesWithText("Paste your token below.").fetchSemanticsNodes().isNotEmpty(),
            "the markdown paragraph must be rendered too",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an unknown plugin id reports the invalid-source message instead of an empty form`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                LyricoNavHost(startDestination = PluginConfigDestination("net.example.missing"))
            }
        }

        waitUntil("the invalid-source state", 5_000) {
            onAllNodesWithText(text(Res.string.source_config_invalid_source)).fetchSemanticsNodes().isNotEmpty()
        }
        // and no form was drawn for it
        assertEquals(
            0,
            onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size,
            "a missing plugin must not produce an empty form",
        )
    }
}
