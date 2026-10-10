package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.SharedSelectionManager
import com.lonx.lyrico.data.editfield.EditFieldConfigRepository
import com.lonx.lyrico.data.model.BatchMatchConfig
import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.data.model.metadata.MetadataWriteMode
import com.lonx.lyrico.data.model.plugin.PluginSourceType
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRequest
import com.lonx.lyrico.data.song.tag.AudioTagFieldKey
import com.lonx.lyrico.data.song.tag.AudioTagMutation
import com.lonx.lyrico.data.song.tag.AudioTagMutationMode
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.data.song.tag.AudioTagWriteResult
import com.lonx.lyrico.data.song.tag.FieldMutation
import com.lonx.lyrico.data.support.LocalGitHubServer
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.plugin.source.PluginSearchSourceManager
import com.lonx.lyrico.plugin.source.SearchSourceProvider
import com.lonx.lyrico.plugin.source.SourcePluginInstaller
import com.lonx.lyrico.plugin.support.ManifestConfigField
import com.lonx.lyrico.plugin.support.pluginArchive
import com.lonx.lyrico.plugin.support.pluginManifestJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BatchMatchViewModel] 的验收测试：**界面按下「开始匹配」之后，真插件真的把字段写进了真文件。**
 *
 * 全链路是真的：Koin 图、Room、库扫描、字节级真实的插件 zip（安装器解包）、QuickJS 执行脚本、本机
 * 回环 HTTP 服务、TagLib 读回标签、真实的 [com.lonx.lyrico.worker.BatchTaskRunner]。这一层要钉住的
 * 是**视图模型独有的那一段**——它把「用户选了什么」翻译成任务行里的 `configJson`：
 *
 * 1. 可见字段设置（`EditFieldConfigRepository`）：编辑页里关掉的字段、以及不属于本次匹配类型的字段，
 *    必须在 JSON 里变成 `DISABLED`，处理器才不会去问插件。这是「用户看到什么」与「实际请求什么」的
 *    唯一连接点。
 * 2. 源顺序（`enabledSourceOrderIds`）与源配置（`sourceSettings.baseUrl`）：来自已安装插件表与用户
 *    保存的插件配置，是真值，不是测试塞进去的。
 *
 * 有意不覆盖：对话框本身的渲染（`SongBatchSelectionActions` 是 #35 第二笔提交）；`preferFileName`
 * 一类未被界面暴露的开关（处理器测试已覆盖它们的语义）。
 */
class BatchMatchViewModelTest {

    private val dataRoot: File = Files.createTempDirectory("lyrico-batch-match-vm").toFile()
    private val directories = AppDirectories(root = dataRoot, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-batch-match-vm-music")
    private val server = LocalGitHubServer().start()

    private lateinit var database: LyricoDatabase
    private lateinit var tasks: BatchTaskRepository
    private lateinit var songs: SongLibraryRepository
    private lateinit var tags: AudioTagRepository
    private lateinit var scanner: LibraryScanRepository
    private lateinit var settings: SettingsRepository
    private lateinit var selection: SharedSelectionManager
    private lateinit var editFieldConfig: EditFieldConfigRepository
    private lateinit var searchSourceProvider: SearchSourceProvider
    private lateinit var installer: SourcePluginInstaller

    private val store = ViewModelStore()

    /** 这个测试里建过的视图模型，tearDown 要等它们的作用域真的收完尾。 */
    private val createdViewModels = mutableListOf<ViewModel>()
    private val provider = ViewModelProvider.create(
        store,
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                GlobalContext.get().get(modelClass)
        },
        CreationExtras.Empty,
    )

    private var rootAdded = false

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        val koin = GlobalContext.get()
        database = koin.get()
        tasks = koin.get()
        songs = koin.get()
        tags = koin.get()
        scanner = koin.get()
        settings = koin.get()
        selection = koin.get()
        editFieldConfig = koin.get()
        searchSourceProvider = koin.get()
        installer = koin.get()
        runBlocking {
            settings.saveIgnoreShortAudio(false)
            settings.saveSeparator("/")
        }
    }

    @AfterTest
    fun tearDown() {
        // QuickJS 运行时持有原生内存；管理器是单例，趁 Koin 还记得它的时候关掉。
        runCatching { GlobalContext.get().get<PluginSearchSourceManager>().close() }
        store.clear()
        // `store.clear()` 只是发出取消：`stateIn` / `init` 的协程要在 Main 上补完收尾，而 Main 正在被
        // 使用的时候 `resetMain()` 会抛 "used concurrently"。等它们真的结束再重置——这也是「上一个测试
        // 泄漏的协程」那类 flake 的另一半。
        runBlocking {
            createdViewModels.forEach { viewModel ->
                withTimeoutOrNull(5_000L) {
                    viewModel.viewModelScope.coroutineContext[Job]?.join()
                }
            }
        }
        runCatching { stopKoin() }
        Dispatchers.resetMain()
        server.stop()
        dataRoot.deleteRecursively()
        musicDir.toFile().deleteRecursively()
    }

    // ------------------------------------------------------------------ 真匹配

    @Test
    fun `a metadata match asks the installed plugin, with the settings that were saved`() = runBlocking<Unit> {
        server.respond(
            "/search",
            """
            {"items":[
              {"id":101,"name":"山丘","artists":["李宗盛"],"album":{"name":"山丘专辑"},
               "durationMs":319000,"comment":"来自插件"}
            ]}
            """.trimIndent(),
        )
        installPlugin()
        val song = placeSong(fileName = "山丘.mp3", title = "山丘", artist = "李宗盛")
        assertNull(song.album, "前提：文件本来没有专辑")

        val viewModel = viewModel()
        select(song)
        viewModel.openBatchMatchConfig(BatchMatchType.METADATA)
        assertEquals(BatchMatchType.METADATA, viewModel.uiState.value.matchType)
        assertTrue(viewModel.uiState.value.showBatchConfigDialog, "配置对话框要开")

        // 用户在配置对话框里改完按下确认：配置先存进设置，再拿它发起。
        val config = BatchMatchConfig(
            targetModes = mapOf(
                MetadataFieldTarget.ALBUM to MetadataWriteMode.OVERWRITE,
                MetadataFieldTarget.COMMENT to MetadataWriteMode.SUPPLEMENT,
            ),
            concurrency = 1,
        )
        viewModel.saveBatchMatchConfig(config)
        awaitUntil(
            describe = { viewModel.batchMatchConfig.value.targetModes[MetadataFieldTarget.ALBUM] },
            condition = { it == MetadataWriteMode.OVERWRITE },
        )
        awaitPluginVisible(viewModel, PluginSourceType.METADATA)

        viewModel.batchMatch(listOf(song), config)

        assertFalse(viewModel.uiState.value.showBatchConfigDialog, "发起之后配置对话框关掉")
        assertTrue(viewModel.uiState.value.isRunning)

        awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
        val taskId = assertNotNull(viewModel.uiState.value.currentTaskId)
        val finished = awaitTask(taskId)
        assertEquals(BatchTaskStatus.SUCCEEDED, finished.status)
        assertEquals(BatchTaskType.MATCH_METADATA, finished.type)

        // 1. 契约：视图模型写出的 configJson —— 这就是处理器实际读到的东西。
        val config2 = json(finished).jsonObject
        assertEquals("/", config2["separator"]?.jsonPrimitive?.content)
        assertEquals(
            listOf(PLUGIN_ID),
            config2["enabledSourceOrderIds"]?.jsonArray?.map { it.jsonPrimitive.content },
            "源顺序取自已安装插件表",
        )
        assertEquals(
            baseUrl,
            config2["sourceSettings"]?.jsonObject?.get(PLUGIN_ID)?.jsonObject?.get("baseUrl")?.jsonPrimitive?.content,
            "插件配置取自用户保存的那份（配置文件页写进去的）",
        )
        assertEquals("1", config2["concurrency"]?.jsonPrimitive?.content)
        val modes = config2["matchConfig"]?.jsonObject?.get("targetModes")?.jsonObject
        assertNotNull(modes)
        assertEquals("OVERWRITE", modes[MetadataFieldTarget.ALBUM.name]?.jsonPrimitive?.content)
        assertEquals("SUPPLEMENT", modes[MetadataFieldTarget.COMMENT.name]?.jsonPrimitive?.content)

        // 2. 磁盘：插件真的被调用了（回环 HTTP 有记录），字段真的写进了标签。
        val request = server.requests.first { it.path.startsWith("/search") }
        assertContains(request.path, "q=")
        val written = tags.read(song.uri, AudioTagReadOptions())
        assertEquals("山丘专辑", written.album)
        assertEquals("来自插件", written.comment)
        assertEquals("山丘", written.title, "没被选中的字段不能被动过")
        assertEquals("李宗盛", written.artist)

        val row = assertNotNull(songs.getSongByUri(song.uri))
        assertEquals("山丘专辑", row.album, "行里存的就是文件里现在的值")
        assertEquals(written.comment, row.comment)
        assertEquals(1, songs.getSongCount(), "匹配只更新行，不新增")

        // 3. 状态。
        awaitUntil(describe = { viewModel.uiState.value }, condition = { !it.isRunning })
        val state = viewModel.uiState.value
        assertEquals(1 to 1, state.batchProgress)
        assertEquals(1, state.successCount)
        assertEquals(0, state.failureCount)
        assertEquals("山丘.mp3", state.currentFile, "currentFile 停在最后一个文件上（与 Android 一致）")
        assertEquals(emptyMap(), state.fileProgressMap)
        assertTrue(state.batchTimeMillis >= 0)
    }

    @Test
    fun `a field the user switched off in the edit settings is never asked for`() = runBlocking<Unit> {
        server.respond(
            "/search",
            """{"items":[{"id":101,"name":"山丘","artists":["李宗盛"],"album":{"name":"山丘专辑"},"comment":"来自插件"}]}""",
        )
        installPlugin()
        val song = placeSong(fileName = "山丘.mp3", title = "山丘", artist = "李宗盛")

        // 编辑页里把「专辑」关掉：匹配就不能再去要它。
        val visible = editFieldConfig.configFlow.first().matchTargets()
        assertTrue(MetadataFieldTarget.ALBUM in visible, "前提：专辑默认是可见字段")
        assertTrue(MetadataFieldTarget.COMMENT in visible, "前提：备注默认是可见字段")
        val albumField = editFieldConfig.configFlow.first().allFields
            .first { it.target == MetadataFieldTarget.ALBUM }
        editFieldConfig.setEnabled(albumField.code, false)

        val viewModel = viewModel()
        select(song)
        viewModel.openBatchMatchConfig(BatchMatchType.METADATA)
        val config = BatchMatchConfig(
            targetModes = mapOf(
                MetadataFieldTarget.ALBUM to MetadataWriteMode.OVERWRITE,
                MetadataFieldTarget.COMMENT to MetadataWriteMode.SUPPLEMENT,
            ),
            concurrency = 1,
        )
        awaitPluginVisible(viewModel, PluginSourceType.METADATA)
        viewModel.batchMatch(listOf(song), config)

        awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
        val finished = awaitTask(assertNotNull(viewModel.uiState.value.currentTaskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, finished.status)

        val modes = json(finished).jsonObject["matchConfig"]!!.jsonObject["targetModes"]!!.jsonObject
        assertEquals(
            "DISABLED",
            modes[MetadataFieldTarget.ALBUM.name]?.jsonPrimitive?.content,
            "看不见的字段要变成 DISABLED：$modes",
        )
        assertEquals("SUPPLEMENT", modes[MetadataFieldTarget.COMMENT.name]?.jsonPrimitive?.content)

        val written = tags.read(song.uri, AudioTagReadOptions())
        assertNull(written.album, "关了专辑就不该写专辑")
        assertEquals("来自插件", written.comment, "没关的字段照写")
    }

    @Test
    fun `a lyrics match drops the targets that do not belong to it`() = runBlocking<Unit> {
        server.respond(
            "/search",
            """{"items":[{"id":101,"name":"山丘","artists":["李宗盛"],"album":{"name":"山丘专辑"},"durationMs":319000}]}""",
        )
        server.respond(
            "/lyrics",
            """{"title":"山丘","artist":"李宗盛","album":"山丘专辑","lrc":"[00:01.00]越过山丘\n[00:05.00]虽然已白了头"}""",
        )
        installPlugin()
        val song = placeSong(fileName = "山丘.mp3", title = "山丘", artist = "李宗盛")
        assertNull(song.lyrics, "前提：文件本来没有歌词")

        val viewModel = viewModel()
        select(song)
        viewModel.openBatchMatchConfig(BatchMatchType.LYRICS)

        // 用户在同一次操作里既选了歌词又选了专辑：专辑不属于「歌词匹配」，必须被丢掉。
        val config = BatchMatchConfig(
            targetModes = mapOf(
                MetadataFieldTarget.LYRICS to MetadataWriteMode.OVERWRITE,
                MetadataFieldTarget.ALBUM to MetadataWriteMode.OVERWRITE,
            ),
            concurrency = 1,
        )
        awaitPluginVisible(viewModel, PluginSourceType.LYRICS)
        viewModel.batchMatch(listOf(song), config)

        awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
        val finished = awaitTask(assertNotNull(viewModel.uiState.value.currentTaskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, finished.status)
        assertEquals(BatchTaskType.MATCH_LYRICS, finished.type)

        val modes = json(finished).jsonObject["matchConfig"]!!.jsonObject["targetModes"]!!.jsonObject
        assertEquals("OVERWRITE", modes[MetadataFieldTarget.LYRICS.name]?.jsonPrimitive?.content)
        assertEquals(
            "DISABLED",
            modes[MetadataFieldTarget.ALBUM.name]?.jsonPrimitive?.content,
            "专辑不属于歌词匹配，要变成 DISABLED：$modes",
        )

        val written = tags.read(song.uri, AudioTagReadOptions())
        assertContains(assertNotNull(written.lyrics), "越过山丘")
        assertNull(written.album, "歌词匹配不能顺手写专辑")
    }

    @Test
    fun `a library with no installed plugin skips the files instead of failing`() = runBlocking<Unit> {
        // 全新安装的样子：用户还没加过任何源。
        val song = placeSong(fileName = "山丘.mp3", title = "山丘", artist = "李宗盛")

        val viewModel = viewModel()
        select(song)
        viewModel.openBatchMatchConfig(BatchMatchType.LYRICS)
        viewModel.batchMatch(
            listOf(song),
            BatchMatchConfig(
                targetModes = mapOf(MetadataFieldTarget.LYRICS to MetadataWriteMode.OVERWRITE),
                concurrency = 1,
            ),
        )

        awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
        val finished = awaitTask(assertNotNull(viewModel.uiState.value.currentTaskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, finished.status, "没有源不是失败")

        val config = json(finished).jsonObject
        assertEquals(
            emptyList(),
            config["enabledSourceOrderIds"]?.jsonArray?.map { it.jsonPrimitive.content },
            "一个源都没装，订单就是空的",
        )

        val item = tasks.observeItems(finished.taskId).first().single()
        assertEquals(BatchTaskStatus.SKIPPED, item.status)
        assertEquals("No enabled lyrics source", item.resultJson)
        assertNull(tags.read(song.uri, AudioTagReadOptions()).lyrics, "什么都不能写")
        assertEquals(emptyList(), server.requests, "没有源就没人被请求")

        awaitUntil(describe = { viewModel.uiState.value }, condition = { !it.isRunning })
        assertEquals(1, viewModel.uiState.value.skippedCount)
    }

    // ------------------------------------------------------------------ 什么都没发生的那些情况

    @Test
    fun `a batch match without a selection creates no task and opens no dialog`() = runBlocking<Unit> {
        val song = placeSong(fileName = "山丘.mp3", title = "山丘", artist = "李宗盛")
        val viewModel = viewModel()

        // 没勾选任何歌：连配置对话框都不该打开。
        viewModel.openBatchMatchConfig(BatchMatchType.METADATA)
        assertFalse(viewModel.uiState.value.showBatchConfigDialog, "空选择时对话框不该开")

        // 就算界面把整库的歌都端上来了，只要没勾选，也不能建任务。
        viewModel.batchMatch(
            listOf(song),
            BatchMatchConfig(
                targetModes = mapOf(MetadataFieldTarget.ALBUM to MetadataWriteMode.OVERWRITE),
                concurrency = 1,
            ),
        )
        assertNull(viewModel.uiState.value.currentTaskId)
        assertEquals(emptyList<BatchTaskEntity>(), taskRows())

        // 勾了别的歌、却把不相干的歌端上来：同样什么都不做（视图模型按选择过滤）。
        selection.setUris(setOf("file:///not-in-the-library.mp3"))
        viewModel.openBatchMatchConfig(BatchMatchType.METADATA)
        assertTrue(viewModel.uiState.value.showBatchConfigDialog, "有选择就能开对话框")
        viewModel.batchMatch(
            listOf(song),
            BatchMatchConfig(
                targetModes = mapOf(MetadataFieldTarget.ALBUM to MetadataWriteMode.OVERWRITE),
                concurrency = 1,
            ),
        )
        // 走到这里 `batchMatch` 已经同步把 isRunning 置真，过滤走的是协程：等它自己退回来。
        awaitUntil(describe = { viewModel.uiState.value.isRunning }, condition = { !it })
        assertEquals(emptyList<BatchTaskEntity>(), taskRows())
    }

    // ------------------------------------------------------------------ 恢复

    @Test
    fun `a match running when the window closed comes back on the next one`() = runBlocking<Unit> {
        // 三个匹配类型各有自己的运行中任务，恢复要找全，不能只找第一个。
        installPlugin()
        val song = placeSong(fileName = "山丘.mp3", title = "山丘", artist = "李宗盛")
        val taskId = tasks.createTask(
            BatchTaskType.MATCH_LYRICS,
            listOf(song),
            configJson = """{"separator":"/","enabledSourceOrderIds":["$PLUGIN_ID"]}""",
        )
        tasks.markRunning(taskId)

        val viewModel = viewModel()

        awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
        val state = viewModel.uiState.value
        assertEquals(taskId, state.currentTaskId, "MATCH_LYRICS 的运行中任务也要被认出来")
        assertTrue(state.isRunning)
        assertEquals(0 to 1, state.batchProgress)
    }

    // ------------------------------------------------------------------ 辅助

    private fun viewModel(): BatchMatchViewModel =
        provider[BatchMatchViewModel::class].also { createdViewModels += it }

    /**
     * 让视图模型自己的源缓存跟上。
     *
     * `sourcesByType` 是 `stateIn(Eagerly)`：它在自己的调度器上收集，而 `batchMatch` 是同步读
     * `.value` 的。测试里插件是在视图模型之前装好的，所以缓存只差一次调度。这里先等插件在真实源
     * 列表里出现，再等视图模型的**作用域**跑一个来回（`visibleTargets` 要走一次真实 DataStore 读），
     * 用它当顺序栅栏，而不是靠 `sleep`。
     */
    private suspend fun awaitPluginVisible(viewModel: BatchMatchViewModel, sourceType: PluginSourceType) {
        withTimeout(10_000L) {
            searchSourceProvider.observeSources(sourceType).first { list -> list.any { it.id == PLUGIN_ID } }
        }
        withTimeout(10_000L) { viewModel.visibleTargets.first { it.isNotEmpty() } }
    }

    private fun select(vararg selected: SongEntity) {
        selection.setUris(selected.map { it.uri }.toSet())
    }

    private fun json(task: BatchTaskEntity) = Json.parseToJsonElement(assertNotNull(task.configJson))

    private suspend fun awaitTask(taskId: String, timeoutMillis: Long = 120_000L): BatchTaskEntity {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val task = tasks.getTask(taskId)
            if (task != null && task.status in FINISHED_STATUSES) return task
            kotlinx.coroutines.delay(25L)
        }
        throw AssertionError("task $taskId did not finish; last status was ${tasks.getTask(taskId)?.status}")
    }

    private suspend fun taskRows(): List<BatchTaskEntity> = tasks.observeTasks().first()

    private val baseUrl: String get() = "http://127.0.0.1:${server.port}"

    /** 装插件的路径与插件页完全一样：真实 zip → 安装器解包 → 真实 Room 行（启用）。 */
    private suspend fun installPlugin() {
        val session = installer.prepareImport(
            pluginArchive(
                manifestJson = pluginManifestJson(
                    id = PLUGIN_ID,
                    name = "Match Fixture",
                    includeDirs = listOf("lib"),
                    capabilities = listOf("searchSongs", "getLyrics"),
                    configFields = listOf(ManifestConfigField(key = "baseUrl", title = "API base URL")),
                ),
                script = PLUGIN_SCRIPT,
                extra = listOf("lib/01_http.js" to PLUGIN_HTTP_MODULE),
            ),
            directories.pluginInstallRoot,
        )
        assertEquals(emptyList(), session.failed.map { it.reason }, "夹具压缩包必须能准备")
        val result = installer.installPrepared(session, enabled = true)
        assertEquals(emptyList(), result.failed.map { it.reason }, "夹具插件必须能安装")
        assertEquals(PLUGIN_ID, result.installed.single().id)
        // 用户保存过的插件配置（插件配置页写进去的那条通道）。
        settings.saveSourceSettings(PLUGIN_ID, mapOf("baseUrl" to baseUrl))
    }

    private suspend fun scanLibrary(): List<SongEntity> {
        if (!rootAdded) {
            database.folderDao().insert(
                FolderEntity(path = musicDir.toRealPath().toString(), addedBySaf = true)
            )
            rootAdded = true
        }
        val result = scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))
        assertTrue(result.failures.isEmpty(), "扫描本身不能失败：${result.failures}")
        return songs.observeSongs(SortBy.TITLE, SortOrder.ASC).first()
    }

    private suspend fun placeSong(fileName: String, title: String, artist: String): SongEntity {
        val source = Path.of(System.getProperty("lyrico.audiotag.fixtures.dir"), "bladeenc.mp3")
        assertTrue(Files.isRegularFile(source), "缺少音频夹具 $source")
        val folder = musicDir.resolve("Album")
        Files.createDirectories(folder)
        val target = folder.resolve(fileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        val fields = mapOf(
            AudioTagFieldKey.Title to FieldMutation.Set(title),
            AudioTagFieldKey.Artist to FieldMutation.Set(artist),
        )
        val result = tags.patch(
            target.toString(),
            AudioTagMutation(mode = AudioTagMutationMode.Patch, fields = fields),
        )
        assertIs<AudioTagWriteResult.Success>(result, "夹具必须能写标签")
        val uri = target.toRealPath().toString()
        return scanLibrary().single { it.uri == uri }
    }

    private companion object {
        const val PLUGIN_ID = "net.example.match"

        val FINISHED_STATUSES = setOf(
            BatchTaskStatus.SUCCEEDED,
            BatchTaskStatus.FAILED,
            BatchTaskStatus.CANCELLED,
        )

        /** 入口脚本：插件配置里的 baseUrl 从 `request.config` 读，也就是处理器真正传进去的那份。 */
        val PLUGIN_SCRIPT = """
            include("lib/01_http.js");

            globalThis.searchSongs = function (request) {
              var base = (request.config || {}).baseUrl || "";
              var body = Platform.http.getText(ExampleApi.searchUrl(base, request.keyword, request.page), {
                headers: { "X-Plugin": "match" }
              });
              var payload = JSON.parse(body);
              return (payload.items || []).map(function (item) {
                return ExampleApi.mapSong(item, request.separator);
              }).filter(function (song) { return song.id && song.title; });
            };

            globalThis.getLyrics = function (request) {
              var base = (request.config || {}).baseUrl || "";
              var song = request.song || {};
              var payload = JSON.parse(Platform.http.getText(ExampleApi.lyricsUrl(base, song), {
                headers: { "X-Plugin": "match" }
              }));
              return {
                type: "rawPlainLrc",
                tags: {
                  ti: payload.title,
                  ar: payload.artist,
                  al: payload.album,
                  date: "2013-08-01"
                },
                rawPlainLrc: payload.lrc
              };
            };
        """.trimIndent()

        val PLUGIN_HTTP_MODULE = """
            globalThis.ExampleApi = {
              searchUrl: function (base, keyword, page) {
                return base + "/search?q=" + encodeURIComponent(keyword) + "&page=" + page;
              },
              lyricsUrl: function (base, song) {
                return base + "/lyrics?title=" + encodeURIComponent(song.title || "") +
                       "&artist=" + encodeURIComponent(song.artist || "");
              },
              mapSong: function (item, separator) {
                var artists = (item.artists || []).join(separator || "/");
                var cover = item.cover || "";
                var fields = {
                  title: item.name || "",
                  artist: artists,
                  album: (item.album || {}).name || "",
                  cover_url: cover
                };
                if (item.comment) fields.comment = item.comment;
                return {
                  id: String(item.id || ""),
                  title: item.name || "",
                  artist: artists,
                  album: (item.album || {}).name || "",
                  date: String(item.year || item.releaseDate || item.date || ""),
                  duration: Number(item.durationMs || 0),
                  picUrl: cover,
                  fields: fields,
                  internal: { match_id: String(item.id || "") }
                };
              }
            };
        """.trimIndent()
    }
}
