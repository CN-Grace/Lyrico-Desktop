package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.AppLanguage
import com.lonx.lyrico.data.model.cache.CacheCategory
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.utils.ImageCache
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
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
 * The settings view model against the real graph, a real settings store and a real database.
 *
 * The three things worth pinning here are the three Android did through the framework and desktop had to
 * replace:
 *
 * - **The app language** is now a stored preference (`app_language`) instead of AppCompat state, so this
 *   checks it survives a reread of the store rather than only reaching the UI state.
 * - **Export/import take a `File`** instead of a SAF `Uri`: the round trip is checked against bytes on
 *   disk, including that a backup really carries the language field, and an unreadable path has to be
 *   reported rather than thrown into the caller's coroutine.
 * - **Cache accounting goes through the injected `CacheManager`**, so [RecordingImageCache] proves the
 *   image half is measured and cleared through Coil's interface (`ImageCache`) while the network half is
 *   measured from the real directory. The production binding is exercised by `CoilImageCacheTest`;
 *   binding Coil here would touch a process-wide singleton and a real disk cache for no extra coverage.
 *
 * The view model is created through a real `ViewModelProvider`/`ViewModelStore` for the reason
 * `BatchExportViewModelTest` records: `viewModelScope` only ends at `ViewModelStore.clear()`, and a scope
 * that outlives the test is how a leaked collector reaches the *next* test.
 */
class SettingsViewModelTest {

    private val dataRoot: File = Files.createTempDirectory("lyrico-settings-vm").toFile()
    private val directories = AppDirectories(root = dataRoot, isPortable = true).prepare()

    private val store = ViewModelStore()
    private val createdViewModels = mutableListOf<ViewModel>()
    private val collectors = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val imageCache = RecordingImageCache()
    private val appLog = RecordingAppLogRepository()

    private lateinit var settings: SettingsRepository
    private lateinit var database: LyricoDatabase

    private val provider = ViewModelProvider.create(
        store,
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: kotlin.reflect.KClass<T>, extras: CreationExtras): T =
                GlobalContext.get().get(modelClass)
        },
        CreationExtras.Empty,
    )

    @BeforeTest
    fun setUp() {
        // `viewModelScope` runs on Main and everything here is real IO, so Main stays a real dispatcher.
        Dispatchers.setMain(Dispatchers.Default)
        runCatching { stopKoin() }
        startKoin {
            modules(
                desktopAppModule(directories),
                // Last definition wins in Koin. The recording log repository lets a test assert that a
                // failure was *reported*; the image cache keeps Coil out of a unit test.
                module {
                    single<AppLogRepository> { appLog }
                    single<ImageCache> { imageCache }
                },
            )
        }
        settings = GlobalContext.get().get()
        database = GlobalContext.get().get()
    }

    @AfterTest
    fun tearDown() {
        // `viewModelScope` is cancelled only here, and the cancellation is asynchronous: the flows have to
        // finish on Main, which must not be reset while that is still happening.
        store.clear()
        runBlocking {
            createdViewModels.forEach { viewModel ->
                withTimeoutOrNull(5_000L) {
                    viewModel.viewModelScope.coroutineContext[Job]?.join()
                }
            }
        }
        collectors.cancel()
        runCatching { stopKoin() }
        Dispatchers.resetMain()
        dataRoot.deleteRecursively()
    }

    private fun newViewModel(): SettingsViewModel {
        val viewModel = provider[SettingsViewModel::class]
        createdViewModels += viewModel
        // `uiState` is a `WhileSubscribed` state flow, so it produces nothing without a collector; the
        // event flow has no buffer, so leaving it uncollected would suspend every `emit` in the view
        // model -- including the ones after a successful export.
        collectors.launch { viewModel.uiState.collect { } }
        collectors.launch { viewModel.events.collect { } }
        return viewModel
    }

    private fun initializedViewModel(): SettingsViewModel {
        val viewModel = newViewModel()
        awaitUntil(describe = { viewModel.uiState.value.isInitialized }) { it }
        return viewModel
    }

    // ------------------------------------------------------------------ 语言

    @Test
    fun `the app language is stored, and read back from the store`() = runBlocking<Unit> {
        val viewModel = initializedViewModel()
        assertEquals(AppLanguage.SYSTEM, viewModel.uiState.value.appLanguage, "unset has to mean follow system")

        viewModel.setAppLanguage(AppLanguage.TRADITIONAL_CHINESE_HONG_KONG)
        awaitUntil(describe = { viewModel.uiState.value.appLanguage }) {
            it == AppLanguage.TRADITIONAL_CHINESE_HONG_KONG
        }

        // The state could be right while nothing was persisted, so read the store itself.
        assertEquals(
            AppLanguage.TRADITIONAL_CHINESE_HONG_KONG,
            settings.appLanguage.first(),
            "the language never reached the settings store",
        )
    }

    @Test
    fun `a language written before startup is the one the screen opens with`() = runBlocking<Unit> {
        settings.saveAppLanguage(AppLanguage.SIMPLIFIED_CHINESE)

        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, initializedViewModel().uiState.value.appLanguage)
    }

    // ------------------------------------------------------------------ 导出 / 导入

    @Test
    fun `a backup carries the language, and import applies it back`() = runBlocking<Unit> {
        val target = File(dataRoot, "lyrico-settings.json")
        val viewModel = initializedViewModel()

        viewModel.setAppLanguage(AppLanguage.SIMPLIFIED_CHINESE)
        viewModel.setOnlyTranslationIfAvailable(true)
        awaitUntil(describe = { viewModel.uiState.value.appLanguage }) { it == AppLanguage.SIMPLIFIED_CHINESE }
        awaitUntil(describe = { viewModel.uiState.value.onlyTranslationIfAvailable }) { it }

        viewModel.exportSettings(target)
        awaitUntil(describe = { target.takeIf { it.isFile }?.length() ?: 0L }) { it > 0L }

        val json = target.readText()
        assertTrue(json.contains("\"app_language\""), "the backup does not carry the language: $json")
        assertTrue(json.contains("\"zh-CN\""), "the backup does not carry the language tag: $json")
        // The file is written before the log entry, so this waits rather than asserting right away.
        awaitLog("Settings exported")

        // Move both settings away, then let the file put them back.
        viewModel.setAppLanguage(AppLanguage.ENGLISH)
        viewModel.setOnlyTranslationIfAvailable(false)
        awaitUntil(describe = { viewModel.uiState.value.appLanguage }) { it == AppLanguage.ENGLISH }

        viewModel.importSettings(target)
        awaitUntil(describe = { viewModel.uiState.value.appLanguage }) { it == AppLanguage.SIMPLIFIED_CHINESE }
        awaitUntil(describe = { viewModel.uiState.value.onlyTranslationIfAvailable }) { it }
        awaitLog("Settings imported")
    }

    @Test
    fun `a failed export is reported rather than thrown`() = runBlocking<Unit> {
        val viewModel = initializedViewModel()

        // A directory where a file is expected: writing fails on every platform, and the failure has to
        // come back as a message instead of as an exception in the caller's coroutine.
        val target = File(dataRoot, "not-a-file")
        assertTrue(target.mkdirs(), "could not set up the failure case")

        viewModel.exportSettings(target)

        val failure = awaitException("Failed to export settings")
        assertTrue(
            failure.throwable is java.io.IOException,
            "the export failure was reported as ${failure.throwable::class.qualifiedName}",
        )
    }

    @Test
    fun `a missing import file is reported rather than thrown`() = runBlocking<Unit> {
        val viewModel = initializedViewModel()
        viewModel.importSettings(File(dataRoot, "does-not-exist.json"))
        awaitException("Failed to import settings")
    }

    @Test
    fun `an unreadable import file is reported as a failed import`() = runBlocking<Unit> {
        val viewModel = initializedViewModel()
        val target = File(dataRoot, "not-json.txt").apply { writeText("this is not a backup") }

        viewModel.importSettings(target)

        // `importSettings` reports its own failure instead of throwing, and the view model has to pass
        // that on rather than claiming success. The repository was asked, so the log proves the branch.
        awaitUntil(describe = { appLog.entries.size }) { it > 0 }
        assertNotNull(
            appLog.entries.firstOrNull { it.message == "Settings import rejected: invalid format" }
                ?: appLog.entries.firstOrNull { it.message == "Failed to import settings" },
            "an unreadable backup was not reported: ${appLog.entries.map { it.message }}",
        )
    }

    /** Waits for a failure to be *reported*: a thrown exception is logged through `logException`. */
    private suspend fun awaitException(message: String): RecordingAppLogRepository.RecordedException {
        awaitUntil(describe = { appLog.exceptions.firstOrNull { it.message == message } }) { it != null }
        return appLog.exceptions.first { it.message == message }
    }

    /** Waits for a success to be logged. */
    private fun awaitLog(message: String) {
        awaitUntil(describe = { appLog.entries.firstOrNull { it.message == message } }) { it != null }
    }

    // ------------------------------------------------------------------ 缓存

    @Test
    fun `cache sizes are measured through the manager, and clearing asks the image cache to clear`() =
        runBlocking<Unit> {
            val viewModel = initializedViewModel()

            // Something real in the OkHttp cache directory, so the NETWORK entry is a measurement rather
            // than a zero that would also pass for "the manager was never asked".
            val networkFile = File(directories.httpCacheDir, "http-cache-entry").apply {
                parentFile?.mkdirs()
                writeText("x".repeat(64))
            }
            assertTrue(networkFile.isFile, "the network cache fixture was not written")

            imageCache.sizeBytes = 4096L
            viewModel.refreshCache()
            awaitUntil(describe = { viewModel.uiState.value.categorizedCacheSize }) { it.isNotEmpty() }

            val sizes = viewModel.uiState.value.categorizedCacheSize
            assertEquals(4096L, sizes[CacheCategory.IMAGE], "the image size did not come from the seam")
            assertEquals(64L, sizes[CacheCategory.NETWORK], "the network size is not the real directory size")
            assertEquals(4160L, viewModel.uiState.value.totalCacheSize)
            assertTrue(imageCache.sizeQueries >= 1, "the image size was never read")

            viewModel.clearCache()
            awaitUntil(describe = { imageCache.clearCalls }) { it == 1 }

            // `clearCache` refreshes afterwards, so the sizes have to be re-read rather than left stale.
            val queriesBefore = imageCache.sizeQueries
            imageCache.sizeBytes = 0L
            viewModel.refreshCache()
            awaitUntil(describe = { imageCache.sizeQueries }) { it > queriesBefore }
            awaitUntil(describe = { viewModel.uiState.value.categorizedCacheSize }) {
                it[CacheCategory.IMAGE] == 0L
            }
            assertTrue(
                !networkFile.exists() || networkFile.length() == 0L,
                "the network cache survived a full clear",
            )
        }

    // ------------------------------------------------------------------ 清空曲库

    @Test
    fun `clearing the library empties the folder table`() = runBlocking<Unit> {
        database.folderDao().insert(FolderEntity(path = File(dataRoot, "music").absolutePath))
        assertEquals(1, database.folderDao().getFoldersCount(), "the seed row was not inserted")

        val viewModel = initializedViewModel()

        assertTrue(viewModel.clearSongs(), "clearing reported failure while the table still had rows")
        assertEquals(0, database.folderDao().getFoldersCount())
    }

    private class RecordingImageCache : ImageCache {
        var sizeBytes: Long = 0L
        var sizeQueries: Int = 0
        var clearCalls: Int = 0

        override fun diskSizeBytes(): Long {
            sizeQueries++
            return sizeBytes
        }

        override fun clear() {
            clearCalls++
        }
    }
}
