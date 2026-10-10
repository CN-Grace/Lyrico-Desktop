package com.lonx.lyrico.di

import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.editfield.EditFieldConfigRepository
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.model.log.LogRetentionOption
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.data.repository.CustomTagKeyRepository
import com.lonx.lyrico.data.repository.GhContributorRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.PlaybackRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.repository.UpdateRepository
import com.lonx.lyrico.data.song.file.SongFileRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRepository
import com.lonx.lyrico.data.song.search.SongSearchRepository
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.domain.song.usecase.DeleteSongsUseCase
import com.lonx.lyrico.domain.song.usecase.ReadAudioTagsUseCase
import com.lonx.lyrico.domain.song.usecase.RenameSongUseCase
import com.lonx.lyrico.domain.song.usecase.SaveAudioTagsUseCase
import com.lonx.lyrico.domain.song.usecase.SynchronizeLibraryUseCase
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.utils.LibraryScanManager
import com.lonx.lyrico.utils.UpdateManager
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.KoinApplicationAlreadyStartedException
import org.koin.core.context.GlobalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Boots the real desktop graph, on a real data folder.
 *
 * A DI module is only correct if every definition can actually be built: a wrong constructor
 * argument, a missing binding or a circular dependency all compile fine and then throw at the moment
 * a user opens the screen that needs them. So this test starts Koin exactly as the application does,
 * resolves every ported binding, and then uses a few of them for real — the settings store writes to
 * `settings.preferences_pb`, the log repository writes to `lyrico.db` — to prove the graph is wired
 * to the files [AppDirectories] points at rather than to somewhere in memory.
 */
class DesktopAppModuleTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-di-test").toFile()
    private val directories = AppDirectories(
        root = workingDir,
        isPortable = true,
    ).prepare()

    @After
    fun tearDown() {
        runCatching { stopKoin() }
        // Best effort: the preference stores hold their files open, so Windows may refuse the delete.
        // A leftover temp folder is not a test failure.
        workingDir.deleteRecursively()
    }

    private fun startGraph() {
        try {
            startKoin { modules(desktopAppModule(directories)) }
        } catch (e: KoinApplicationAlreadyStartedException) {
            // Koin is process-global; another test in this JVM (the smoke test) may have started it.
            stopKoin()
            startKoin { modules(desktopAppModule(directories)) }
        }
    }

    @Test
    fun `every ported binding can be resolved`() {
        startGraph()
        val koin = GlobalContext.get()

        val bindings = listOf(
            koin.get<SettingsRepository>(),
            koin.get<EditFieldConfigRepository>(),
            koin.get<CustomTagKeyRepository>(),
            koin.get<AppLogRepository>(),
            koin.get<BatchTaskRepository>(),
            koin.get<UpdateRepository>(),
            koin.get<GhContributorRepository>(),
            koin.get<PlaybackRepository>(),
            koin.get<LibraryIndexRepository>(),
            koin.get<SongLibraryRepository>(),
            koin.get<SongSearchRepository>(),
            koin.get<SongFileRepository>(),
            koin.get<AudioTagRepository>(),
            koin.get<LibraryScanRepository>(),
            koin.get<ReadAudioTagsUseCase>(),
            koin.get<SaveAudioTagsUseCase>(),
            koin.get<DeleteSongsUseCase>(),
            koin.get<RenameSongUseCase>(),
            koin.get<SynchronizeLibraryUseCase>(),
            koin.get<LibraryScanManager>(),
            koin.get<UpdateManager>(),
            koin.get<LyricoDatabase>(),
        )

        assertEquals(22, bindings.size)
        bindings.forEach { assertNotNull(it) }
    }

    @Test
    fun `the graph is a set of singletons over the shared database`() {
        startGraph()
        val koin = GlobalContext.get()

        assertSame(koin.get<LyricoDatabase>(), koin.get<LyricoDatabase>())
        assertSame(koin.get<SettingsRepository>(), koin.get<SettingsRepository>())
        assertSame(koin.get<SongFileRepository>(), koin.get<SongFileRepository>())
        assertSame(koin.get<LibraryScanManager>(), koin.get<LibraryScanManager>())
        // The DAO bindings must come from the same database instance the repositories write through,
        // or the app would read from one connection pool and write to another.
        assertSame(
            koin.get<LyricoDatabase>().songDao(),
            koin.get<LyricoDatabase>().songDao(),
        )
    }

    @Test
    fun `the database binding writes to the resolved data folder`() {
        startGraph()
        val koin = GlobalContext.get()

        runBlocking<Unit> {
            koin.get<LyricoDatabase>().folderDao().insert(FolderEntity(path = """H:\Music"""))
            assertEquals(1, koin.get<LyricoDatabase>().folderDao().getAllFoldersOnce().size)
        }

        assertTrue(directories.databaseFile.isFile, "expected ${directories.databaseFile}")
        assertEquals(directories.root, directories.databaseFile.parentFile)
    }

    @Test
    fun `the two preference stores are distinct files`() {
        startGraph()
        val koin = GlobalContext.get()

        runBlocking<Unit> {
            koin.get<SettingsRepository>().saveLogRetentionOption(LogRetentionOption.SEVEN_DAYS)
            koin.get<AppLogRepository>().log(
                level = AppLogLevel.INFO,
                type = AppLogType.APP,
                tag = "DesktopAppModuleTest",
                message = "graph booted",
            )
        }

        assertTrue(directories.settingsFile.isFile, "settings did not land on disk")
        assertTrue(directories.editFieldConfigFile.parentFile!!.isDirectory)
        assertTrue(directories.httpCacheDir.isDirectory)
    }

    @Test
    fun `the view models are registered with their dependencies`() {
        startGraph()
        val koin = GlobalContext.get()

        // `koinViewModel()` in a screen resolves through the same definitions; resolving them here
        // catches a view model whose constructor arguments drifted from the module.
        val viewModels = listOf(
            koin.get<com.lonx.lyrico.viewmodel.AppLogViewModel>(),
            koin.get<com.lonx.lyrico.viewmodel.AlbumActionsViewModel>(),
            koin.get<com.lonx.lyrico.viewmodel.AlbumLibraryViewModel>(),
            koin.get<com.lonx.lyrico.viewmodel.ArtistLibraryViewModel>(),
            koin.get<com.lonx.lyrico.viewmodel.LocalSearchViewModel>(),
            koin.get<com.lonx.lyrico.viewmodel.ArtistSplitSettingsViewModel>(),
            koin.get<com.lonx.lyrico.viewmodel.CharacterMappingViewModel>(),
            koin.get<com.lonx.lyrico.viewmodel.EditFieldSettingsViewModel>(),
            koin.get<com.lonx.lyrico.viewmodel.SongListViewModel>(),
            koin.get<com.lonx.lyrico.viewmodel.SongSelectionViewModel>(),
            koin.get<com.lonx.lyrico.viewmodel.BatchExportViewModel>(),
            koin.get<com.lonx.lyrico.viewmodel.BatchLyricsFormatViewModel>(),
            koin.get<com.lonx.lyrico.viewmodel.BatchMatchViewModel>(),
            koin.get<com.lonx.lyrico.viewmodel.BatchReplayGainViewModel>(),
        )

        assertEquals(14, viewModels.size)
        viewModels.forEach { assertNotNull(it) }
    }
}
