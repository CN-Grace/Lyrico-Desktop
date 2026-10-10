package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.lyrics.LyricFormat
import com.lonx.lyrico.data.model.lyrics.LyricsOperation
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
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BatchLyricsFormatViewModel] 的验收测试：**一次「歌词处理」真的把处理后的文本写回标签。**
 *
 * 这一层全部是真的：真实 Koin 图、真实 Room、真实库扫描、真实 TagLib、真实
 * [com.lonx.lyrico.worker.BatchTaskRunner]，断言读到的是文件里现在的歌词文本。因此每条路径都要验三样东西：
 * 视图模型写出的 [LyricsFormatConfig]（它是界面与处理器之间唯一的契约）、磁盘上标签里的文本、`uiState`。
 *
 * 有意不覆盖：`LyricsColumnMapping` 的具体编辑器（`LyricsProcessingSheet` 的界面渲染，跟 #35 的第二笔
 * 提交走；列映射本身由 `LyricsDocumentPipelineTest` 与 `BatchTaskEngineEndToEndTest` 钉住）。
 *
 * 视图模型从真实 `ViewModelStore` 里取（见 `BatchExportViewModelTest` 类头解释的那个泄漏问题）。
 */
class BatchLyricsFormatViewModelTest {

    private val dataRoot = Files.createTempDirectory("lyrico-batch-lyrics-vm").toFile()
    private val directories = AppDirectories(root = dataRoot, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-batch-lyrics-vm-music")

    private lateinit var database: LyricoDatabase
    private lateinit var tasks: BatchTaskRepository
    private lateinit var songs: SongLibraryRepository
    private lateinit var tags: AudioTagRepository
    private lateinit var scanner: LibraryScanRepository
    private lateinit var settings: SettingsRepository

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
        runBlocking { settings.saveIgnoreShortAudio(false) }
    }

    @AfterTest
    fun tearDown() {
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
        dataRoot.deleteRecursively()
        musicDir.toFile().deleteRecursively()
    }

    // ------------------------------------------------------------------ 真文本回到标签里

    @Test
    fun `converting to ttml rewrites the tag and leaves the config dialog closed`() = runBlocking<Unit> {
        placeSong("歌词测试.mp3", lyrics = "[00:01.000]Original line\n[00:01.000]翻译行")
        val song = scanLibrary().single()

        val viewModel = viewModel()
        viewModel.setSelectionUris(listOf(song.uri))
        viewModel.openConfig(3, LyricsOperation.CONVERT)
        assertTrue(viewModel.uiState.value.showConfigDialog, "配置对话框由「发起」打开")
        assertFalse(viewModel.uiState.value.isRunning, "打开配置还什么都没跑")

        viewModel.setConcurrency(2)
        viewModel.setTargetFormat(LyricFormat.TTML)
        viewModel.startBatchConvert()

        assertEquals(2, viewModel.uiState.value.concurrency)
        assertFalse(viewModel.uiState.value.showConfigDialog, "按下确认后配置对话框关掉，换成进度对话框")
        assertTrue(viewModel.uiState.value.showProgressDialog)

        awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
        val taskId = assertNotNull(viewModel.uiState.value.currentTaskId)
        val finished = awaitTask(taskId)
        assertEquals(BatchTaskStatus.SUCCEEDED, finished.status)

        // 1. 配置：操作、目标格式、并发度都真的传下去了。
        val config = Json.parseToJsonElement(assertNotNull(finished.configJson)).jsonObject
        assertFalse("operation" in config, "`CONVERT` 是缺省值，不写进 JSON：$config")
        assertEquals("TTML", config["targetFormat"]?.jsonPrimitive?.content)
        assertEquals("2", config["concurrency"]?.jsonPrimitive?.content)
        assertEquals("false", config["formatLineOrder"]?.jsonPrimitive?.content, "纯格式转换不重排行序")

        // 2. 磁盘：标签里现在是 TTML，翻译行还在（渲染器真的跑了）。
        val converted = assertNotNull(tags.read(song.uri, AudioTagReadOptions()).lyrics)
        assertTrue(converted.contains("""itunes:key="L1""""), "expected TTML on disk, got:\n$converted")
        assertTrue(converted.contains("""<text for="L1">翻译行</text>"""), "翻译行必须活下来")
        assertTrue(converted.contains("Original line"))

        // 行里存的与文件一致，与扫描时的快照不同。
        assertEquals(converted, songs.getSongByUri(song.uri)?.lyrics)

        // 3. 状态。
        awaitUntil(describe = { viewModel.uiState.value }, condition = { !it.isRunning })
        val state = viewModel.uiState.value
        assertEquals(1 to 1, state.progress)
        assertEquals(1, state.successCount)
        assertTrue(state.isSuccess)
        assertEquals(LyricsOperation.CONVERT, state.operation)
        assertEquals(LyricFormat.TTML, state.targetFormat)
    }

    @Test
    fun `removing empty lines uses the text-operation branch without a target format`() = runBlocking<Unit> {
        placeSong("空行.mp3", lyrics = "[00:01.000]第一行\n\n[00:02.000]第二行")
        val song = scanLibrary().single()

        val viewModel = viewModel()
        viewModel.setSelectionUris(listOf(song.uri))
        viewModel.openConfig(3, LyricsOperation.REMOVE_EMPTY)
        viewModel.startBatchConvert()

        awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
        val finished = awaitTask(assertNotNull(viewModel.uiState.value.currentTaskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, finished.status)

        val config = Json.parseToJsonElement(assertNotNull(finished.configJson)).jsonObject
        assertEquals("REMOVE_EMPTY", config["operation"]?.jsonPrimitive?.content)
        assertFalse("targetFormat" in config, "文本操作不带目标格式：$config")
        assertEquals("true", config["removeEmptyLines"]?.jsonPrimitive?.content)

        val converted = assertNotNull(tags.read(song.uri, AudioTagReadOptions()).lyrics)
        assertFalse(converted.contains("\n\n"), "空行必须被去掉，得到：\n$converted")
        assertTrue(converted.contains("第一行") && converted.contains("第二行"), "两行歌词都还在：\n$converted")
    }

    @Test
    fun `removing tag lines takes the keywords the user saved in the settings`() = runBlocking<Unit> {
        // 用户在自己的设置里改过关键词：视图模型必须用设置里的，而不是代码里的默认表。
        settings.saveLyricsTagLineKeywords(listOf("作词："))
        placeSong("标签行.mp3", lyrics = "作词：某人\n[00:01.000]第一行")
        val song = scanLibrary().single()

        val viewModel = viewModel()
        // `init` 里收集的设置已经到状态上了（不是这里从后门塞进去的）。
        awaitUntil(
            describe = { viewModel.uiState.value.tagLineKeywords },
            condition = { it == listOf("作词：") },
        )
        viewModel.setSelectionUris(listOf(song.uri))
        viewModel.openConfig(3, LyricsOperation.REMOVE_TAGS)
        viewModel.startBatchConvert()

        awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
        val finished = awaitTask(assertNotNull(viewModel.uiState.value.currentTaskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, finished.status)

        val config = Json.parseToJsonElement(assertNotNull(finished.configJson)).jsonObject
        assertEquals("REMOVE_TAGS", config["operation"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("作词："),
            config["tagLineKeywords"]?.jsonArray?.map { it.jsonPrimitive.content },
        )

        val converted = assertNotNull(tags.read(song.uri, AudioTagReadOptions()).lyrics)
        assertFalse(converted.contains("作词："), "用户删掉的那行必须没了，得到：\n$converted")
        assertTrue(converted.contains("第一行"))
    }

    // ------------------------------------------------------------------ 什么都没发生的那些情况

    @Test
    fun `a conversion with no target format and no text operation starts nothing`() = runBlocking<Unit> {
        placeSong("没目标.mp3", lyrics = "[00:01.000]第一行")
        val song = scanLibrary().single()

        val viewModel = viewModel()
        viewModel.setSelectionUris(listOf(song.uri))
        viewModel.openConfig(3, LyricsOperation.CONVERT)
        // 用户把目标格式清掉（界面允许"不转换格式"）：这时没有任何动作可做，不能建一个空任务。
        viewModel.setTargetFormat(null)
        viewModel.startBatchConvert()

        assertEquals(3, viewModel.uiState.value.concurrency)
        assertTrue(viewModel.uiState.value.showConfigDialog, "什么都没发生，配置对话框还在")
        assertFalse(viewModel.uiState.value.showProgressDialog)
        assertNull(viewModel.uiState.value.currentTaskId)
        assertEquals(emptyList<BatchTaskEntity>(), taskRows())
    }

    @Test
    fun `starting with an empty selection creates no task`() = runBlocking<Unit> {
        val viewModel = viewModel()
        viewModel.openConfig(3, LyricsOperation.CONVERT)

        viewModel.startBatchConvert()

        assertNull(viewModel.uiState.value.currentTaskId)
        assertEquals(emptyList<BatchTaskEntity>(), taskRows())
    }

    @Test
    fun `the concurrency is clamped to the range the engine allows`() {
        val viewModel = viewModel()

        viewModel.setConcurrency(0)
        assertEquals(1, viewModel.uiState.value.concurrency)
        viewModel.setConcurrency(99)
        assertEquals(5, viewModel.uiState.value.concurrency)

        viewModel.openConfig(9)
        assertEquals(5, viewModel.uiState.value.concurrency, "打开配置时也要夹一次")
        viewModel.openConfig(0)
        assertEquals(1, viewModel.uiState.value.concurrency)
    }

    // ------------------------------------------------------------------ 恢复

    @Test
    fun `a running conversion left by the previous process restores its own configuration`() = runBlocking<Unit> {
        placeSong("恢复.mp3", lyrics = "[00:01.000]第一行")
        val song = scanLibrary().single()
        val config = LyricsFormatConfig(
            targetFormat = null,
            concurrency = 1,
            removeEmptyLines = true,
            operation = LyricsOperation.REMOVE_EMPTY,
        )
        val taskId = tasks.createTask(
            BatchTaskType.CONVERT_LYRICS_FORMAT,
            listOf(song),
            Json.encodeToString(LyricsFormatConfig.serializer(), config),
        )
        tasks.markRunning(taskId)

        val viewModel = viewModel()

        awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
        val state = viewModel.uiState.value
        assertEquals(taskId, state.currentTaskId)
        assertTrue(state.isRunning)
        assertTrue(state.showProgressDialog)
        // 用户关掉窗口再打开，配置对话框要回到上次那个操作上：状态是从任务行的 configJson 解出来的，
        // 而那是任务行的第一帧，比 id 晚一点（真实 IO）。
        awaitUntil(
            describe = { viewModel.uiState.value.operation },
            condition = { it == LyricsOperation.REMOVE_EMPTY },
        )
        assertNull(viewModel.uiState.value.targetFormat)
    }

    // ------------------------------------------------------------------ 辅助

    private fun viewModel(): BatchLyricsFormatViewModel =
        provider[BatchLyricsFormatViewModel::class].also { createdViewModels += it }

    private suspend fun awaitTask(taskId: String, timeoutMillis: Long = 60_000L): BatchTaskEntity {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val task = tasks.getTask(taskId)
            if (task != null && task.status in FINISHED_STATUSES) return task
            delay(20L)
        }
        throw AssertionError("task $taskId did not finish; last status was ${tasks.getTask(taskId)?.status}")
    }

    private suspend fun taskRows(): List<BatchTaskEntity> = tasks.observeTasks().first()

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

    private fun placeSong(fileName: String, lyrics: String): Path {
        val source = Path.of(System.getProperty("lyrico.audiotag.fixtures.dir"), "bladeenc.mp3")
        assertTrue(Files.isRegularFile(source), "missing audio fixture $source")
        val folder = musicDir.resolve("Album")
        Files.createDirectories(folder)
        val target = folder.resolve(fileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        val fields = mapOf(
            AudioTagFieldKey.Title to FieldMutation.Set(fileName.substringBeforeLast('.')),
            AudioTagFieldKey.Artist to FieldMutation.Set("An Artist"),
            AudioTagFieldKey.Lyrics to FieldMutation.Set(lyrics),
        )
        val result = runBlocking {
            tags.patch(target.toString(), AudioTagMutation(mode = AudioTagMutationMode.Patch, fields = fields))
        }
        assertIs<AudioTagWriteResult.Success>(result, "fixture 必须能写标签")
        return target
    }

    private companion object {
        val FINISHED_STATUSES = setOf(
            BatchTaskStatus.SUCCEEDED,
            BatchTaskStatus.FAILED,
            BatchTaskStatus.CANCELLED,
        )
    }
}
