package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.ExportDestination
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRequest
import com.lonx.lyrico.data.song.tag.AudioTagFieldKey
import com.lonx.lyrico.data.song.tag.AudioTagMutation
import com.lonx.lyrico.data.song.tag.AudioTagMutationMode
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
 * [BatchExportViewModel] 的验收测试：**界面按下的那一下，真的在盘上留下文件。**
 *
 * 依赖一个都不是假的：真实 Koin 图、真实 Room、真实库扫描、真实 TagLib、真实 [com.lonx.lyrico.worker.BatchTaskRunner]。
 * 断言分三层，缺一层这句话就不成立：
 *
 * 1. **配置**：视图模型写进任务行的 JSON。这是它与处理器之间唯一的契约，`destinationDirectory`（Android 的
 *    `destinationTreeUri` 的桌面替代）与"`concurrency` 保持缺省"这两件事只有在这里能验。
 * 2. **磁盘**：导出的 `.lrc` 文件字节。任务的 `SUCCEEDED` 只说明处理器自己满意。
 * 3. **状态**：`uiState` 的进度/计数/时长。进度对话框读的就是它。
 *
 * 另一条只有这一层能测的是 `init` 里的**恢复**：上一个进程留下的 RUNNING 任务要重新挂上观察，
 * 否则用户关掉窗口再打开，进度就永远停在那里。
 *
 * 关于 `ViewModelStore`：视图模型从 store 里取，`tearDown` 里 `clear()`。`viewModelScope` 只在
 * `ViewModel.clear()` 时才取消，而 `koin.get()` 直接拿到的实例谁也不会去清 —— 上一阶段的测试套件
 * 里那个"前一个测试泄漏的收集器打到已关闭的 Room 连接池"的偶发失败就是这么来的。这里不再新增一份。
 */
class BatchExportViewModelTest {

    private val dataRoot = Files.createTempDirectory("lyrico-batch-export-vm").toFile()
    private val directories = AppDirectories(root = dataRoot, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-batch-export-vm-music")

    /** `SELECTED_DIRECTORY` 的目标：真实目录，运行前就存在（选择器保证这一点）。 */
    private val exportDir: Path = Files.createTempDirectory("lyrico-batch-export-vm-target")

    private lateinit var database: LyricoDatabase
    private lateinit var tasks: BatchTaskRepository
    private lateinit var songs: SongLibraryRepository
    private lateinit var tags: AudioTagRepository
    private lateinit var scanner: LibraryScanRepository

    private val store = ViewModelStore()

    /** 这个测试里建过的视图模型，tearDown 要等它们的作用域真的收完尾。 */
    private val createdViewModels = mutableListOf<ViewModel>()

    /** 真实的创建路径：`ViewModelProvider` 问 Koin 要实例，和 `koinViewModel()` 在屏幕里做的一样。 */
    private val provider = ViewModelProvider.create(
        store,
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: kotlin.reflect.KClass<T>, extras: CreationExtras): T =
                GlobalContext.get().get(modelClass)
        },
        CreationExtras.Empty,
    )

    /** 库根目录行只在第一次扫描时插入。 */
    private var rootAdded = false

    @BeforeTest
    fun setUp() {
        // `viewModelScope` 停在 Main 上，而这里的每件事都是真实 IO：Main 用真调度器
        // （与 `AlbumActionsViewModelTest` 同一条线），不用虚拟时间。
        Dispatchers.setMain(Dispatchers.Default)
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        val koin = GlobalContext.get()
        database = koin.get()
        tasks = koin.get()
        songs = koin.get()
        tags = koin.get()
        scanner = koin.get()
        // 应用默认丢弃短音频，fixture 只有几秒；扫描插不进去的话下面每条断言都会因为错误的理由通过。
        runBlocking { koin.get<SettingsRepository>().saveIgnoreShortAudio(false) }
    }

    @AfterTest
    fun tearDown() {
        // `viewModelScope` 的取消只发生在这一步（见类头）。
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
        exportDir.toFile().deleteRecursively()
    }

    // ------------------------------------------------------------------ 真文件落盘

    @Test
    fun `exporting lyrics into a chosen folder writes the tag text and mirrors it into the state`() =
        runBlocking<Unit> {
            val lyrics = "[00:01.00]朋友一生一起走\n[00:05.00]那些日子不再有"
            placeSong("朋友.mp3", lyrics = lyrics)
            val song = scanLibrary().single()

            val viewModel = viewModel()
            viewModel.setSelectionUris(listOf(song.uri))
            viewModel.startBatchExport(BatchTaskType.EXPORT_LYRICS, ExportDestination.SELECTED_DIRECTORY, exportDir.toString())

            // 任务的创建在 `viewModelScope` 上（真实 IO），所以 id 不是同步出现的。
            awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
            val taskId = assertNotNull(viewModel.uiState.value.currentTaskId, "启动后要出现一个任务 id")
            val finished = awaitTask(taskId)
            assertEquals(BatchTaskStatus.SUCCEEDED, finished.status)

            // 1. 配置：用户选的目录真的是处理器读到的那个，且没有凭空的 concurrency。
            //    `destination` 没出现是因为 `SELECTED_DIRECTORY` 就是它的缺省值，而编码器不写缺省字段
            //    （`encodeDefaults = false`，与 Android 一模一样）。
            val config = Json.parseToJsonElement(assertNotNull(finished.configJson)).jsonObject
            assertEquals(exportDir.toString(), config["destinationDirectory"]?.jsonPrimitive?.content)
            assertFalse("destination" in config, "缺省值不写进 JSON：$config")
            assertFalse("concurrency" in config, "缺省值不写进 JSON：$config")

            // 2. 磁盘：字节，不是处理器的返回值。
            val exported = exportDir.resolve("朋友.lrc")
            assertTrue(Files.isRegularFile(exported), "expected the export at $exported")
            assertEquals(lyrics, Files.readString(exported))

            // 3. 状态：进度对话框关掉之前读到的就是这些。
            awaitUntil(describe = { viewModel.uiState.value }, condition = { !it.isRunning })
            val state = viewModel.uiState.value
            assertEquals(1 to 1, state.progress)
            assertEquals(1, state.successCount)
            assertEquals(0, state.failureCount)
            assertEquals(0, state.skippedCount)
            assertTrue(state.isSuccess)
            assertEquals(BatchTaskType.EXPORT_LYRICS, state.taskType)
            assertEquals(taskId, state.currentTaskId)
            assertTrue(state.showProgressDialog, "进度对话框还在，用户要自己关掉")
            assertTrue(state.totalTimeMillis >= 0, "任务行有 startedAt/finishedAt 才有时长")
        }

    @Test
    fun `exporting lyrics next to the audio writes one file per song`() = runBlocking<Unit> {
        placeSong("朋友.mp3", lyrics = "[00:01.00]朋友一生一起走")
        placeSong("花心.mp3", lyrics = "[00:02.00]花的心藏在蕊中")
        val scanned = scanLibrary()
        assertEquals(2, scanned.size)

        val viewModel = viewModel()
        viewModel.setSelectionUris(scanned.map { it.uri })
        viewModel.startBatchExport(BatchTaskType.EXPORT_LYRICS, ExportDestination.AUDIO_DIRECTORY)

        awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
        val finished = awaitTask(assertNotNull(viewModel.uiState.value.currentTaskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, finished.status)
        assertEquals(2, finished.successCount)
        // 非缺省的 `destination` 才会写进 JSON：这里写的是它自己的值。
        val config = Json.parseToJsonElement(assertNotNull(finished.configJson)).jsonObject
        assertEquals("AUDIO_DIRECTORY", config["destination"]?.jsonPrimitive?.content)
        assertFalse("destinationDirectory" in config, "音频目录不需要目录参数：$config")

        val folder = musicDir.resolve("Album")
        assertEquals("[00:01.00]朋友一生一起走", Files.readString(folder.resolve("朋友.lrc")))
        assertEquals("[00:02.00]花的心藏在蕊中", Files.readString(folder.resolve("花心.lrc")))

        awaitUntil(describe = { viewModel.uiState.value }, condition = { !it.isRunning })
        assertEquals(2 to 2, viewModel.uiState.value.progress)
        assertTrue(viewModel.uiState.value.isSuccess)
    }

    // ------------------------------------------------------------------ 什么都没发生的那些情况

    @Test
    fun `choosing the selected folder without a directory starts nothing`() = runBlocking<Unit> {
        val viewModel = viewModel()
        viewModel.setSelectionUris(listOf(musicDir.resolve("Album/朋友.mp3").toString()))

        // Android 的守卫：选了"自选目录"却没有 Uri，不能猜一个目录出来。
        viewModel.startBatchExport(BatchTaskType.EXPORT_LYRICS, ExportDestination.SELECTED_DIRECTORY)

        assertEquals(BatchExportUiState(), viewModel.uiState.value, "状态不能动，更不能弹对话框")
        assertEquals(emptyList<BatchTaskEntity>(), taskRows())
    }

    @Test
    fun `a task type this screen does not own starts nothing`() = runBlocking<Unit> {
        val viewModel = viewModel()
        viewModel.setSelectionUris(listOf(musicDir.resolve("Album/朋友.mp3").toString()))

        viewModel.startBatchExport(BatchTaskType.SCAN_REPLAY_GAIN, ExportDestination.AUDIO_DIRECTORY)

        assertEquals(BatchExportUiState(), viewModel.uiState.value)
        assertEquals(emptyList<BatchTaskEntity>(), taskRows())
    }

    @Test
    fun `starting with an empty selection starts nothing`() = runBlocking<Unit> {
        val viewModel = viewModel()

        viewModel.startBatchExport(BatchTaskType.EXPORT_LYRICS, ExportDestination.AUDIO_DIRECTORY)

        assertEquals(BatchExportUiState(), viewModel.uiState.value)
        assertEquals(emptyList<BatchTaskEntity>(), taskRows())
    }

    @Test
    fun `a selection that no longer resolves closes the dialog instead of creating an empty task`() =
        runBlocking<Unit> {
            val viewModel = viewModel()
            // 用户选了歌，然后在按下导出之前把文件删了/重新扫描过。
            viewModel.setSelectionUris(listOf(musicDir.resolve("Album/不存在.mp3").toString()))

            viewModel.startBatchExport(BatchTaskType.EXPORT_LYRICS, ExportDestination.AUDIO_DIRECTORY)

            awaitUntil(
                describe = { viewModel.uiState.value },
                condition = { !it.showProgressDialog && !it.isRunning },
            )
            assertNull(viewModel.uiState.value.currentTaskId)
            assertEquals(emptyList<BatchTaskEntity>(), taskRows(), "空任务不该进数据库")
        }

    // ------------------------------------------------------------------ 恢复

    @Test
    fun `a running export left by the previous process is picked up again`() = runBlocking<Unit> {
        placeSong("朋友.mp3", lyrics = "[00:01.00]朋友一生一起走")
        val song = scanLibrary().single()
        // 上一个进程被关掉时留下的行：状态 RUNNING，没有 finishedAt。
        val taskId = tasks.createTask(BatchTaskType.EXPORT_LYRICS, listOf(song), configJson = null)
        tasks.markRunning(taskId)

        val viewModel = viewModel()

        awaitUntil(describe = { viewModel.uiState.value }, condition = { it.currentTaskId != null })
        val state = viewModel.uiState.value
        assertEquals(taskId, state.currentTaskId)
        assertEquals(BatchTaskType.EXPORT_LYRICS, state.taskType)
        assertTrue(state.isRunning, "还是 RUNNING，所以要接着观察")
        assertTrue(state.showProgressDialog, "恢复的进度对话框要自己弹出来")
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 视图模型通过真实的 `ViewModelProvider` + `ViewModelStore` 取：`viewModelScope` 唯一的取消点是
     * `ViewModel.clear()`，而它只有 `ViewModelStore.clear()` 会触发（见类头）。直接 `koin.get()` 拿到的
     * 实例谁也不会去清，前一个阶段的偶发失败就是这么来的。
     */
    private fun viewModel(): BatchExportViewModel =
        provider[BatchExportViewModel::class].also { createdViewModels += it }

    /**
     * 任务真的跑完了（`BatchTaskRunner` 由调度器在真实协程里驱动）。
     *
     * 用手写轮询而不是 `awaitUntil`：后者的 `describe` 不是 suspend 的，而读一个任务行是真实 IO。
     */
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

    /** 一个真的 mp3（TagLib 自带的测试文件），标签真的写进去、扫描时真的读回来。 */
    private fun placeSong(fileName: String, lyrics: String? = null): Path {
        val source = Path.of(System.getProperty("lyrico.audiotag.fixtures.dir"), "bladeenc.mp3")
        assertTrue(Files.isRegularFile(source), "missing audio fixture $source")
        val folder = musicDir.resolve("Album")
        Files.createDirectories(folder)
        val target = folder.resolve(fileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        val fields = buildMap {
            put(AudioTagFieldKey.Title, FieldMutation.Set(fileName.substringBeforeLast('.')))
            put(AudioTagFieldKey.Artist, FieldMutation.Set("An Artist"))
            if (lyrics != null) put(AudioTagFieldKey.Lyrics, FieldMutation.Set(lyrics))
        }
        val result = runBlocking {
            tags.patch(target.toString(), AudioTagMutation(mode = AudioTagMutationMode.Patch, fields = fields))
        }
        assertIs<AudioTagWriteResult.Success>(result, "fixture 必须能写标签")
        return target
    }

    private companion object {
        /** 三种终态：任何一条都意味着任务已经不再动了。 */
        val FINISHED_STATUSES = setOf(
            BatchTaskStatus.SUCCEEDED,
            BatchTaskStatus.FAILED,
            BatchTaskStatus.CANCELLED,
        )
    }
}
