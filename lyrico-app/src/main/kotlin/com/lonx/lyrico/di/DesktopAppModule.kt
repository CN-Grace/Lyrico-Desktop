package com.lonx.lyrico.di

import com.lonx.lyrico.BuildInfo
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.SharedSelectionManager
import com.lonx.lyrico.data.editfield.EditFieldConfigRepository
import com.lonx.lyrico.data.network.NetworkLoggingInterceptor
import com.lonx.lyrico.data.openLyricoDatabase
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.AppLogRepositoryImpl
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.data.repository.BatchTaskRepositoryImpl
import com.lonx.lyrico.data.repository.CustomTagKeyRepository
import com.lonx.lyrico.data.repository.FileRevealRepository
import com.lonx.lyrico.data.repository.FileRevealRepositoryImpl
import com.lonx.lyrico.data.repository.GhContributorRepository
import com.lonx.lyrico.data.repository.GhContributorRepositoryImpl
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepositoryImpl
import com.lonx.lyrico.data.repository.PlaybackRepository
import com.lonx.lyrico.data.repository.PlaybackRepositoryImpl
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.repository.SettingsRepositoryImpl
import com.lonx.lyrico.data.repository.SourcePluginRepository
import com.lonx.lyrico.data.repository.SourcePluginRepositoryImpl
import com.lonx.lyrico.data.repository.UpdateRepository
import com.lonx.lyrico.data.repository.UpdateRepositoryImpl
import com.lonx.lyrico.data.repository.createSettingsDataStore
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.song.file.AudioFileAccess
import com.lonx.lyrico.data.song.file.SongFileRepository
import com.lonx.lyrico.data.song.file.SongFileRepositoryImpl
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepositoryImpl
import com.lonx.lyrico.data.song.mapper.SongMetadataMapper
import com.lonx.lyrico.data.song.mapper.SortKeyUpdater
import com.lonx.lyrico.data.song.scan.LibraryScanRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRepositoryImpl
import com.lonx.lyrico.data.song.scan.MediaScanner
import com.lonx.lyrico.data.song.search.SongSearchRepository
import com.lonx.lyrico.data.song.search.SongSearchRepositoryImpl
import com.lonx.lyrico.data.song.tag.AudioTagMutationResolver
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.data.song.tag.AudioTagRepositoryImpl
import com.lonx.lyrico.data.song.tag.DefaultImageBytesFetcher
import com.lonx.lyrico.data.song.tag.ImageBytesFetcher
import com.lonx.lyrico.data.song.tag.ImageMimeTypeDetector
import com.lonx.lyrico.data.song.tag.PictureMutationResolver
import com.lonx.lyrico.data.song.tag.TagMapBuilder
import com.lonx.lyrico.domain.SearchSourceConfigApplier
import com.lonx.lyrico.domain.song.usecase.BatchEditSongsUseCase
import com.lonx.lyrico.domain.song.usecase.DeleteSongsUseCase
import com.lonx.lyrico.domain.song.usecase.OverwriteSongTagsUseCase
import com.lonx.lyrico.domain.song.usecase.PatchSongTagsUseCase
import com.lonx.lyrico.domain.song.usecase.ReadAudioTagsUseCase
import com.lonx.lyrico.domain.song.usecase.RenameSongUseCase
import com.lonx.lyrico.domain.song.usecase.SaveAudioTagsUseCase
import com.lonx.lyrico.domain.song.usecase.SynchronizeLibraryUseCase
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.plugin.i18n.PluginLocales
import com.lonx.lyrico.plugin.runtime.HostAppInfo
import com.lonx.lyrico.plugin.runtime.QuickJsHostApi
import com.lonx.lyrico.plugin.runtime.QuickJsRuntime
import com.lonx.lyrico.plugin.source.PluginSearchSourceManager
import com.lonx.lyrico.plugin.source.ScriptSearchSourceFactory
import com.lonx.lyrico.plugin.source.SearchSourceProvider
import com.lonx.lyrico.plugin.source.SourcePluginInstaller
import com.lonx.lyrico.utils.LibraryScanManager
import com.lonx.lyrico.utils.LibraryScanManagerImpl
import com.lonx.lyrico.utils.UpdateManager
import com.lonx.lyrico.utils.UpdateManagerImpl
import com.lonx.lyrico.worker.BatchTaskRunner
import com.lonx.lyrico.worker.BatchTaskScheduler
import com.lonx.lyrico.worker.processor.BatchTaskProcessorFactory
import com.lonx.lyrico.worker.processor.EditTagsProcessor
import com.lonx.lyrico.worker.processor.LyricsFormatProcessor
import com.lonx.lyrico.worker.processor.MatchCoverProcessor
import com.lonx.lyrico.worker.processor.MatchLyricsProcessor
import com.lonx.lyrico.worker.processor.MatchMetadataProcessor
import com.lonx.lyrico.worker.processor.RenameFilesProcessor
import com.lonx.lyrico.viewmodel.AlbumActionsViewModel
import com.lonx.lyrico.viewmodel.AlbumDetailViewModel
import com.lonx.lyrico.viewmodel.AlbumLibraryViewModel
import com.lonx.lyrico.viewmodel.AppLogViewModel
import com.lonx.lyrico.viewmodel.ArtistLibraryViewModel
import com.lonx.lyrico.viewmodel.ArtistSplitSettingsViewModel
import com.lonx.lyrico.viewmodel.CharacterMappingViewModel
import com.lonx.lyrico.viewmodel.CoverSearchViewModel
import com.lonx.lyrico.viewmodel.EditFieldSettingsViewModel
import com.lonx.lyrico.viewmodel.LocalSearchViewModel
import com.lonx.lyrico.viewmodel.LyricsSearchViewModel
import com.lonx.lyrico.viewmodel.PluginViewModel
import com.lonx.lyrico.viewmodel.SearchSourceConfigViewModel
import com.lonx.lyrico.viewmodel.SearchViewModel
import com.lonx.lyrico.viewmodel.SongListViewModel
import com.lonx.lyrico.viewmodel.SongSelectionViewModel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/** The Koin qualifier for the settings `DataStore`, and for the edit-field one. */
private val SettingsStore = named("settingsStore")
private val EditFieldStore = named("editFieldStore")

/**
 * The desktop dependency graph.
 *
 * This is a **fresh** module rather than a port of `com.lonx.lyrico.di.appModule` (which still sits
 * in the Android tree and depends on 47 classes that do not exist here yet). It grows one wave at a
 * time, and it only ever names classes that are already in the compiled tree, so a build never
 * depends on a future port.
 *
 * Two substitutions carry the whole difference from Android:
 *
 * 1. **No `Context`.** Every path Android asked the platform for comes from [AppDirectories], which
 *    is resolved once at startup and injected as a value. That is what makes the tests able to build
 *    this graph against a temporary directory and get a real, file-backed application.
 * 2. **No `BuildConfig`.** Version/build metadata is read from the generated `BuildInfo` object by
 *    whoever needs it, instead of through an Android constant class.
 *
 * Everything else is registered exactly as Android registered it: the same repository interfaces,
 * the same single instances, and the same `viewModel { }` definitions, so the screens that call
 * `koinViewModel()` do not care which platform they are running on.
 */
fun desktopAppModule(directories: AppDirectories) = module {

    single { directories }

    /** The application scope: lives as long as the process, cancelled when Koin closes. */
    single<CoroutineScope> {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    } onClose { it?.cancel() }

    single {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            encodeDefaults = true
        }
    }

    single {
        val cache = Cache(directories.httpCacheDir, 15L * 1024 * 1024)
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(get<NetworkLoggingInterceptor>())
            .cache(cache)
            .build()
    }
    single { NetworkLoggingInterceptor(get(), get()) }

    // ---------------------------------------------------------------- storage

    // Room opens the database lazily, so this single only builds a handle; the file appears on the
    // first query. See AppDirectoriesTest for the assertion that the resolved path really works.
    single { openLyricoDatabase(directories.databaseDir) }

    single { get<LyricoDatabase>().batchTaskDao() }
    single { get<LyricoDatabase>().appLogDao() }
    single { get<LyricoDatabase>().folderDao() }
    single { get<LyricoDatabase>().libraryIndexDao() }
    single { get<LyricoDatabase>().songDao() }
    single { get<LyricoDatabase>().songCustomTagKeyDao() }

    // The two preference stores stay separate, as on Android: different screens write them, and one
    // shared file would make every edit-field toggle rewrite the whole settings blob.
    single(SettingsStore) { createSettingsDataStore(directories.settingsFile.toPath(), get()) }
    single(EditFieldStore) {
        createSettingsDataStore(directories.editFieldConfigFile.toPath(), get())
    }

    single<SettingsRepository> { SettingsRepositoryImpl(get(SettingsStore)) }
    single { EditFieldConfigRepository(get(EditFieldStore)) }
    single { CustomTagKeyRepository(get()) }
    single<AppLogRepository> { AppLogRepositoryImpl(get(), get()) }
    single<BatchTaskRepository> { BatchTaskRepositoryImpl(get()) }

    // ---------------------------------------------------------------- repositories

    single<UpdateRepository> { UpdateRepositoryImpl(get(), get()) }
    single<GhContributorRepository> { GhContributorRepositoryImpl(get(), get()) }
    single<PlaybackRepository> { PlaybackRepositoryImpl() }
    single<FileRevealRepository> { FileRevealRepositoryImpl() }
    single { SharedSelectionManager() }
    single<LibraryIndexRepository> {
        LibraryIndexRepositoryImpl(get(), get(), get(), get())
    }
    single { SortKeyUpdater() }
    single { SongMetadataMapper(get()) }
    single<SongLibraryRepository> { SongLibraryRepositoryImpl(get()) }
    single<SongSearchRepository> { SongSearchRepositoryImpl(get()) }

    single { AudioFileAccess() }
    single { ImageMimeTypeDetector() }
    single<TagMapBuilder> { TagMapBuilder() }
    single<ImageBytesFetcher> { DefaultImageBytesFetcher(get(), get()) }
    single { PictureMutationResolver(get(), get()) }
    single { AudioTagMutationResolver(get(), get()) }
    single<AudioTagRepository> { AudioTagRepositoryImpl(get(), get(), get()) }
    single<SongFileRepository> { SongFileRepositoryImpl(get(), get(), get(), get(), get()) }

    single { MediaScanner() }
    single<LibraryScanRepository> {
        LibraryScanRepositoryImpl(get(), get(), get(), get(), get(), get(), get())
    }

    // ---------------------------------------------------------------- use cases and managers

    single { ReadAudioTagsUseCase(get(), get()) }
    single { SaveAudioTagsUseCase(get(), get(), get(), get(), get(), get()) }
    single { DeleteSongsUseCase(get()) }
    single { RenameSongUseCase(get()) }
    single { SynchronizeLibraryUseCase(get()) }
    single { PatchSongTagsUseCase(get()) }
    single { OverwriteSongTagsUseCase(get()) }
    single { BatchEditSongsUseCase(get(), get()) }

    single<LibraryScanManager> { LibraryScanManagerImpl(get(), get(), get(), get()) }
    single<UpdateManager> { UpdateManagerImpl(get(), get()) }

    // ---------------------------------------------------------------- batch tasks

    // The task processors that exist on desktop so far, registered as Android registered them: one
    // typed single each, then the factory built out of them. The remaining three types
    // (SCAN_REPLAY_GAIN, EXPORT_LYRICS, EXPORT_COVER) have no desktop processor yet, so
    // BatchTaskProcessorFactory refuses them with its own message and the runner records that on
    // the task row instead of leaving it RUNNING.
    single { LyricsFormatProcessor(get(), get(), get()) }
    single { RenameFilesProcessor(get(), get(), get()) }
    single { EditTagsProcessor(get(), get()) }
    single { MatchMetadataProcessor(get(), get(), get(), get(), get(), get()) }
    single { MatchLyricsProcessor(get(), get(), get(), get(), get()) }
    single { MatchCoverProcessor(get(), get(), get(), get()) }
    single {
        BatchTaskProcessorFactory(
            mapOf(
                BatchTaskType.CONVERT_LYRICS_FORMAT to get<LyricsFormatProcessor>(),
                BatchTaskType.RENAME_FILES to get<RenameFilesProcessor>(),
                BatchTaskType.EDIT_TAGS to get<EditTagsProcessor>(),
                BatchTaskType.MATCH_METADATA to get<MatchMetadataProcessor>(),
                BatchTaskType.MATCH_LYRICS to get<MatchLyricsProcessor>(),
                BatchTaskType.MATCH_COVER to get<MatchCoverProcessor>()
            )
        )
    }
    single { BatchTaskRunner(get(), get(), get()) }
    single { BatchTaskScheduler(get(), get(), get()) }

    // ---------------------------------------------------------------- plugins

    single { get<LyricoDatabase>().sourcePluginDao() }
    single<SourcePluginRepository> { SourcePluginRepositoryImpl(get()) }

    // One factory for every plugin runtime. Mirrors Android's graph, with three desktop
    // substitutions: the app identity comes from BuildInfo instead of BuildConfig, the host API's
    // cache lives under the data folder instead of `Context.cacheDir`, and PluginLocales reads the
    // JVM default locale instead of a `Context` (see PluginLocales for the documented gaps).
    single {
        val okHttpClient = get<OkHttpClient>()
        PluginLocales.initialize()
        ScriptSearchSourceFactory(
            json = get(),
            appLogRepository = get(),
            runtimeFactory = { plugin, strings ->
                QuickJsRuntime(
                    hostApi = QuickJsHostApi(
                        appInfo = HostAppInfo(
                            name = "Lyrico",
                            packageName = HostAppInfo.DEFAULT_PACKAGE_NAME,
                            versionName = BuildInfo.VERSION_NAME,
                            versionCode = BuildInfo.VERSION_CODE,
                            buildType = BuildInfo.BUILD_TYPE,
                            debug = BuildInfo.DEBUG
                        ),
                        okHttpClient = okHttpClient,
                        pluginId = plugin.id,
                        pluginStrings = strings,
                        cacheRootDir = directories.pluginCacheDir
                    )
                )
            }
        )
    }
    single { SourcePluginInstaller(repository = get(), json = get(), appLogRepository = get()) }
    single {
        PluginSearchSourceManager(
            repository = get(),
            factory = get(),
            installer = get(),
            appLogRepository = get()
        )
    }
    single { SearchSourceProvider(pluginManager = get()) }
    single { SearchSourceConfigApplier(get()) }

    // ---------------------------------------------------------------- view models

    viewModel { AppLogViewModel(get(), get()) }
    viewModel { AlbumLibraryViewModel(get(), get(), get()) }
    viewModel { AlbumActionsViewModel(get(), get(), get()) }
    viewModel { ArtistLibraryViewModel(get(), get(), get()) }
    viewModel { LocalSearchViewModel(get(), get(), get()) }
    viewModel { ArtistSplitSettingsViewModel(get(), get()) }
    viewModel { CharacterMappingViewModel(get()) }
    viewModel { EditFieldSettingsViewModel(get(), get(), get(), get()) }
    viewModel { (albumId: Long) -> AlbumDetailViewModel(libraryIndexRepository = get(), albumId = albumId) }
    viewModel { SongListViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { SongSelectionViewModel(get(), get(), get(), get(), get()) }
    viewModel { SearchViewModel(get(), get(), get(), get()) }
    viewModel { LyricsSearchViewModel(get(), get(), get()) }
    viewModel { CoverSearchViewModel(get(), get(), get(), get()) }
    viewModel { SearchSourceConfigViewModel(get(), get()) }
    viewModel { PluginViewModel(get(), get(), get(), get(), get(), directories.pluginInstallRoot) }
}
