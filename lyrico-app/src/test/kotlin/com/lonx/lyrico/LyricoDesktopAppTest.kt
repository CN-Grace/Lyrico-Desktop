package com.lonx.lyrico

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.data.dto.ReleaseInfo
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.platform.DesktopOpener
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.dialog_action_go_update
import com.lonx.lyrico.resources.dialog_title_update_available
import com.lonx.lyrico.support.HeadlessMainDispatcher
import com.lonx.lyrico.utils.UpdateEffect
import com.lonx.lyrico.utils.UpdateManager
import com.lonx.lyrico.utils.UpdateState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shell's update wiring: the check runs at start-up (when the setting allows it) and the dialog it may
 * produce is hosted by the shell, so a release found while the user is on any page is visible.
 *
 * Both were Android's `MainActivity` behaviour -- it checked on start and hosted the dialog above the whole
 * app -- and both were missing on desktop until this batch: `SongListViewModel.checkForUpdate()` existed but
 * nothing called it, and the dialog only appeared on the about page. The fake [UpdateManager] lets the test
 * drive "a newer release exists" deterministically, which the real GitHub check cannot.
 */
@OptIn(ExperimentalTestApi::class)
class LyricoDesktopAppTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-shell-test").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()
    private val mainDispatcher = HeadlessMainDispatcher()
    private val updateManager = FakeUpdateManager()
    private val opener = RecordingDesktopOpener()

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        mainDispatcher.install()
        startKoin {
            modules(
                desktopAppModule(directories),
                module {
                    single<UpdateManager> { updateManager }
                    single<DesktopOpener> { opener }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        runCatching { GlobalContext.get().get<CoroutineScope>().cancel() }
        runCatching { stopKoin() }
        mainDispatcher.restore()
        workingDir.deleteRecursively()
    }

    private fun settings(): SettingsRepository = GlobalContext.get().get()

    private fun text(res: org.jetbrains.compose.resources.StringResource, vararg args: Any): String =
        if (args.isEmpty()) runBlocking { getString(res) } else String.format(runBlocking { getString(res) }, *args)

    @Test
    fun `the start-up check runs when the setting allows it`() {
        assertEquals(true, runBlocking { settings().checkUpdateEnabled.first() }, "the default is enabled")

        runComposeUiTest {
            setContent { LyricoDesktopApp() }
            waitUntil("the start-up check has to be asked for", 20_000) { updateManager.checkCalls.get() == 1 }
        }
    }

    @Test
    fun `the start-up check is skipped when the user turned it off`() {
        runBlocking { settings().saveCheckUpdateEnabled(false) }

        runComposeUiTest {
            setContent { LyricoDesktopApp() }
            waitForIdle()
        }

        assertEquals(0, updateManager.checkCalls.get(), "a disabled check ran anyway")
    }

    @Test
    fun `a found release shows the dialog above the shell and confirm opens its page`() {
        updateManager.publish(
            ReleaseInfo(versionName = "9.9.9", releaseNotes = "## what is new", url = "https://example.com/release"),
        )

        runComposeUiTest {
            setContent { LyricoDesktopApp() }

            waitUntil("the update dialog has to appear over the shell", 20_000) {
                onAllNodesWithText(text(Res.string.dialog_title_update_available, "9.9.9")).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(text(Res.string.dialog_action_go_update)).performClick()
            waitForIdle()
        }

        assertEquals(
            listOf("https://example.com/release"),
            opener.opened,
            "confirming the dialog has to hand the release URL to the shell opener",
        )
        // `YesNoDialog` calls its dismiss callback as well after a confirm, so the state is cleared twice;
        // what matters is that it is cleared rather than left showing.
        assertTrue(updateManager.resets.get() >= 1, "the dialog state has to be cleared after confirming")
    }

    private class FakeUpdateManager : UpdateManager {
        val checkCalls = AtomicInteger()
        val resets = AtomicInteger()
        private val mutableState = MutableStateFlow(UpdateState())

        override val state: StateFlow<UpdateState> = mutableState
        override val effect: Flow<UpdateEffect> = emptyFlow()

        override fun checkForUpdate() {
            checkCalls.incrementAndGet()
        }

        override fun dismissUpdateDialog() = Unit

        override fun resetUpdateState() {
            resets.incrementAndGet()
            mutableState.value = UpdateState()
        }

        fun publish(release: ReleaseInfo) {
            mutableState.value = UpdateState(releaseInfo = release)
        }
    }

    private class RecordingDesktopOpener : DesktopOpener {
        val opened = mutableListOf<String>()

        override fun isSupported(): Boolean = true
        override fun open(file: File) = Unit
        override fun browse(url: String) {
            opened += url
        }
    }
}
