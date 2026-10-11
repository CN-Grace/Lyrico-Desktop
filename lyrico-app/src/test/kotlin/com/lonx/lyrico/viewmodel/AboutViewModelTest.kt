package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.dto.ContributorInfo
import com.lonx.lyrico.data.repository.GhContributorRepository
import com.lonx.lyrico.platform.DesktopOpener
import java.io.File
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The about page's view model: what it asks for, and what it hands to the shell.
 *
 * The page's two irreversible actions are "ask GitHub who contributed" and "open this link in the browser",
 * and the port replaced the second one's mechanism entirely: Android started an `ACTION_VIEW` intent with a
 * `Context`, the desktop goes through [DesktopOpener]. The test pins that hand-off (a `Context`-shaped call
 * would not compile here at all) and that the contributors come from the *fork's* repository rather than the
 * Android upstream -- a mistake that would show the wrong project's contributors and offer the wrong
 * download.
 */
class AboutViewModelTest {

    private class RecordingDesktopOpener : DesktopOpener {
        val opened = mutableListOf<String>()

        override fun isSupported(): Boolean = true
        override fun open(file: File) = Unit
        override fun browse(url: String) {
            opened += url
        }
    }

    private class RecordingContributorRepository : GhContributorRepository {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun getContributors(owner: String, repo: String): Result<List<ContributorInfo>> {
            calls += owner to repo
            return Result.success(emptyList())
        }
    }

    @Test
    fun `opening a link goes through the desktop seam`() {
        val opener = RecordingDesktopOpener()
        val viewModel = viewModel(opener = opener)

        viewModel.openBrowser("https://example.com/contributors")

        assertEquals(listOf("https://example.com/contributors"), opener.opened)
    }

    @Test
    fun `the contributor list is asked for from the fork, not the android upstream`() = kotlinx.coroutines.runBlocking<Unit> {
        val repository = RecordingContributorRepository()
        val viewModel = viewModel(contributors = repository)

        // `init` loads the contributors; wait for the call rather than assuming it happened inline.
        awaitUntil(describe = { repository.calls }) { it.isNotEmpty() }

        val (owner, repo) = repository.calls.single()
        assertEquals("CN-Grace", owner, "the about page asks the wrong project for contributors")
        assertEquals("Lyrico-Desktop", repo, "the about page asks the wrong project for contributors")
        assertTrue(viewModel.contributors.value.isEmpty())
    }

    private fun viewModel(
        opener: DesktopOpener = RecordingDesktopOpener(),
        contributors: GhContributorRepository = RecordingContributorRepository(),
    ): AboutViewModel {
        // The graph's settings store and update manager are real here: this test is about the two seams the
        // port changed, not about a fake graph.
        val settings = org.koin.core.context.GlobalContext.get().get<com.lonx.lyrico.data.repository.SettingsRepository>()
        val updateManager = org.koin.core.context.GlobalContext.get().get<com.lonx.lyrico.utils.UpdateManager>()
        return AboutViewModel(
            settingsRepository = settings,
            updateManager = updateManager,
            contributorRepository = contributors,
            desktopOpener = opener,
        )
    }

    @org.junit.Before
    fun setUp() {
        runCatching { org.koin.core.context.stopKoin() }
        val dir = java.nio.file.Files.createTempDirectory("lyrico-about-vm").toFile()
        org.koin.core.context.startKoin {
            modules(
                com.lonx.lyrico.di.desktopAppModule(
                    com.lonx.lyrico.platform.AppDirectories(root = dir, isPortable = true).prepare()
                )
            )
        }
        workingDir = dir
    }

    @org.junit.After
    fun tearDown() {
        runCatching {
            org.koin.core.context.GlobalContext.get().get<kotlinx.coroutines.CoroutineScope>().cancel()
        }
        runCatching { org.koin.core.context.stopKoin() }
        workingDir.deleteRecursively()
    }

    private lateinit var workingDir: File
}
