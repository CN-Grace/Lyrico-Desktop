package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.BuildInfo
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.model.log.LogRetentionOption
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.AppLogRepositoryImpl
import com.lonx.lyrico.data.repository.SettingsRepositoryImpl
import com.lonx.lyrico.data.repository.createSettingsDataStore
import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.export_failed
import com.lonx.lyrico.resources.export_success
import com.lonx.lyrico.utils.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The app-log screen's state holder, against a real Room log table, the real settings store, and a real
 * exported file.
 *
 * The export is why this class needed real work on desktop: Android handed it a `DocumentFile` `Uri` and
 * a `ContentResolver`; here it is a plain path the user picked. Writing a real file and reading it back
 * is the only way to catch what actually differs -- the UTF-8 encoding and the diagnostic header.
 */
class AppLogViewModelTest {

    private lateinit var library: TestLibrary
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsRepositoryImpl
    private lateinit var repository: AppLogRepositoryImpl
    private lateinit var viewModel: AppLogViewModel
    private lateinit var collectors: CoroutineScope
    private val events = mutableListOf<AppLogEvent>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        library = TestLibrary()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        settings = SettingsRepositoryImpl(
            createSettingsDataStore(library.workingDir.resolve("settings.preferences_pb").toPath(), scope)
        )
        repository = AppLogRepositoryImpl(library.database.appLogDao(), settings)
        viewModel = AppLogViewModel(repository, settings)
        collectors = CoroutineScope(Dispatchers.Default + SupervisorJob())
        collectors.keepCollecting(viewModel.logs)
        collectors.keepCollecting(viewModel.logRetentionOption)
        collectors.launch { viewModel.events.collect { events += it } }
    }

    @AfterTest
    fun tearDown() {
        collectors.cancel()
        scope.cancel()
        library.close()
        Dispatchers.resetMain()
    }

    /** Logging is skipped entirely under `NONE`, so seeding has to open the gate first. */
    private suspend fun record(message: String): Long {
        settings.saveLogRetentionOption(LogRetentionOption.FOREVER)
        repository.log(AppLogLevel.INFO, AppLogType.APP, "Test", message)
        return repository.getLatest().first { it.message == message }.id
    }

    @Test
    fun `recorded logs show up in the observed state`() = runBlocking<Unit> {
        record("first row")

        awaitUntil(describe = { viewModel.logs.value.map { it.message } }) { it.contains("first row") }
    }

    @Test
    fun `deleting logs removes them from the observed state`() = runBlocking<Unit> {
        val id = record("doomed row")
        record("survivor row")
        awaitUntil(describe = { viewModel.logs.value.size }) { it == 2 }

        viewModel.deleteLogs(listOf(id))

        awaitUntil(describe = { viewModel.logs.value.map { it.message } }) { it == listOf("survivor row") }
    }

    @Test
    fun `changing the retention option is stored and applied immediately`() = runBlocking<Unit> {
        record("about to be cleared")
        awaitUntil(describe = { viewModel.logs.value.size }) { it == 1 }

        viewModel.setLogRetentionOption(LogRetentionOption.NONE)

        awaitUntil(describe = { viewModel.logRetentionOption.value }) { it == LogRetentionOption.NONE }
        awaitUntil(describe = { viewModel.logs.value.size }) { it == 0 }
        assertEquals(
            LogRetentionOption.NONE,
            settings.logRetentionOption.first(),
            "the choice is persisted, not just held in memory",
        )
    }

    @Test
    fun `exporting writes the diagnostic header and the log lines to the chosen file`() = runBlocking<Unit> {
        record("导出测试 · export me")
        awaitUntil(describe = { viewModel.logs.value.size }) { it == 1 }
        val target = library.workingDir.resolve("lyrico-log.txt")

        viewModel.exportLogs(target)

        awaitUntil(describe = { target.isFile }) { it }
        val text = target.readText(Charsets.UTF_8)
        // Anchors rather than the whole text: the system/architecture lines differ per machine.
        assertTrue(text.startsWith("Lyrico diagnostic info"), "the header comes first:\n$text")
        assertTrue(text.contains("App version: ${BuildInfo.VERSION_NAME} (${BuildInfo.VERSION_CODE})"), text)
        assertTrue(text.contains("Lyrico log export"), "the repository's own export header is kept")
        // Real UTF-8 round trip: a CJK message read back intact proves the charset argument is honoured.
        assertTrue(text.contains("导出测试 · export me"), text)
        awaitUntil(describe = { events.size }) { it == 1 }
        assertEquals(Res.string.export_success, assertIs<UiMessage.Localized>(assertIs<AppLogEvent.ShowMessage>(events.single()).message).res)
    }

    @Test
    fun `exporting selected ids leaves the other rows out of the file`() = runBlocking<Unit> {
        val id = record("keep this one")
        record("drop this one")
        val target = library.workingDir.resolve("selected.txt")

        viewModel.exportLogs(target, ids = listOf(id))

        awaitUntil(describe = { target.isFile }) { it }
        val text = target.readText(Charsets.UTF_8)
        assertTrue(text.contains("keep this one"), text)
        assertFalse(text.contains("drop this one"), text)
    }

    @Test
    fun `a failed export is logged and reported instead of being thrown away`() = runBlocking<Unit> {
        record("prompt row")
        // A directory is a real, reproducible write failure on every platform -- no permission tricks,
        // and no fake file system needed.
        viewModel.exportLogs(library.workingDir)

        awaitUntil(describe = { viewModel.logs.value.map { it.message } }) { messages ->
            messages.any { it.startsWith("Failed to export logs") }
        }
        awaitUntil(describe = { events.size }) { it == 1 }
        assertEquals(Res.string.export_failed, assertIs<UiMessage.Localized>(assertIs<AppLogEvent.ShowMessage>(events.single()).message).res)
        assertFalse(library.workingDir.resolve("lyrico-log.txt").exists(), "nothing was half-written")
    }

    @Test
    fun `a cancelled export is not reported as a failure`() = runBlocking<Unit> {
        // The only seam in the export is the repository, so that is where the cancellation is injected: a
        // real `CancellationException` raised by the work the export waits on. Android's generic
        // `catch (e: Exception)` would turn this into a "Failed to export logs" row plus an error toast --
        // i.e. a cancelled job would leave a lie in the log the user is about to send us.
        val delegating = AppLogRepositoryImpl(library.database.appLogDao(), settings)
        val cancelling = object : AppLogRepository by delegating {
            override suspend fun exportText(ids: List<Long>): String = throw CancellationException("export cancelled")
        }
        val target = library.workingDir.resolve("cancelled.txt")

        AppLogViewModel(cancelling, settings).exportLogs(target, ids = listOf(1L))

        // Nothing to poll for: the assertion is that neither the row nor the event ever appears, so the
        // wait has to be a bounded one and then the absence is checked.
        Thread.sleep(500L)
        assertFalse(target.exists(), "the export did not complete")
        assertTrue(
            delegating.getLatest().none { it.message.startsWith("Failed to export logs") },
            "a cancelled export must not be logged as a failure",
        )
        assertEquals(emptyList<AppLogEvent>(), events, "and the user must not be told the export failed")
    }

    private fun CoroutineScope.keepCollecting(flow: StateFlow<*>) {
        launch { flow.collect { } }
    }

}
