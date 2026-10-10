package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.ReplayGainPeakMode
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRequest
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.support.AudioFixtures
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
import java.util.Locale
import kotlin.math.abs
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BatchReplayGainViewModel] 的验收测试：**一次批量测量真的把 ReplayGain 标签写到每个文件上。**
 *
 * 真实 Koin 图、真实 Room、真实库扫描、真实 ffmpeg 边车、真实 TagLib 写回与读回。fixture 是手写 WAV，
 * 所以期望的峰值是**算出来的**（写入样本的最大绝对值），增益则是 `目标响度 - 实测响度`，不是问 ffmpeg 拿一个
 * 常数（这条算术与 `ReplayGainProcessorEndToEndTest` 同源）。
 *
 * 这一层唯一无法被处理器测试覆盖的是**设置到任务行的那一段**：用户在设置里选的参考响度与峰值模式，要经过
 * `getReplayGainSettings()` 落到 `configJson` 里。所以两条测量用的目标响度不同（-18 / -23），验的是配置真的
 * 被处理器读走了，而不是断言一个碰巧的数字。
 *
 * 有意不覆盖：进度对话框的逐文件百分比渲染（`fileProgressMap` 由界面读，界面跟 #35 第二笔提交走），以及
 * `SaveAudioTagsResult.Failed`（桌面端要让 TagLib 真的写失败才走得到，制造不出来）。
 */
class BatchReplayGainViewModelTest {

    private val dataRoot = Files.createTempDirectory("lyrico-batch-rg-vm").toFile()
    private val directories = AppDirectories(root = dataRoot, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-batch-rg-vm-music")

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
        runBlocking {
            settings.saveIgnoreShortAudio(false)
            settings.saveReplayGainTargetLoudness(-18.0)
            settings.saveReplayGainPeakMode(ReplayGainPeakMode.SAMPLE_PEAK)
        }
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

    // ------------------------------------------------------------------ 真测量

    @Test
    fun `a batch scan measures every file and writes the tags the settings asked for`() = runBlocking<Unit> {
        AudioFixtures.requireFfmpeg()
        val fixture = placeAudio("一.wav", amplitude = 0.5)
        placeAudio("二.wav", amplitude = 0.25)
        val scanned = scanLibrary()
        assertEquals(2, scanned.size)
        val song = scanned.single { it.fileName == "一.wav" }

        val viewModel = viewModel()
        assertFalse(viewModel.uiState.value.showConfigDialog, "还没点发起")
        viewModel.openReplayGainConfig()
        assertTrue(viewModel.uiState.value.showConfigDialog)
        assertFalse(viewModel.uiState.value.showProgressDialog, "打开配置还什么都没跑")

        viewModel.setSelectionUris(scanned.map { it.uri })
        viewModel.setConcurrency(1)
        viewModel.startBatchScan()

        assertFalse(viewModel.uiState.value.showConfigDialog, "确认后配置对话框换成进度对话框")
        assertTrue(viewModel.uiState.value.showProgressDialog)

        awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
        val finished = awaitTask(assertNotNull(viewModel.uiState.value.currentTaskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, finished.status)
        assertEquals(2, finished.successCount)

        // 1. 配置：目标响度与峰值模式来自设置（不是代码里的默认），并发度是用户拖的那个。
        val config = Json.parseToJsonElement(assertNotNull(finished.configJson)).jsonObject
        assertEquals("-18.0", config["targetLoudness"]?.jsonPrimitive?.content)
        assertEquals("SAMPLE_PEAK", config["peakMode"]?.jsonPrimitive?.content)
        assertEquals("1", config["concurrency"]?.jsonPrimitive?.content)

        // 2. 磁盘：三个标签，值与算术对得上。
        val written = tags.read(song.uri, AudioTagReadOptions())
        assertEquals("-18 LUFS", written.replayGainReferenceLoudness)
        assertEquals(fixture.peakText, written.replayGainTrackPeak)
        val gain = assertNotNull(written.replayGainTrackGain).removeSuffix(" dB").toDouble()
        assertEquals(
            -18.0 - assertNotNull(AudioFixtures.integratedLufs(fixture.file)),
            gain,
            0.1,
            "增益必须是 目标响度 - 实测响度",
        )

        // 行里存的就是文件里现在的值（`SaveAudioTagsUseCase` 重新读了一遍文件）。
        val row = assertNotNull(songs.getSongByUri(song.uri))
        assertEquals(written.replayGainTrackGain, row.replayGainTrackGain)
        assertEquals(written.replayGainTrackPeak, row.replayGainTrackPeak)
        assertEquals(2, songs.getSongCount(), "测量不能增删库里的行")

        // 3. 状态。
        awaitUntil(describe = { viewModel.uiState.value }, condition = { !it.isRunning })
        val state = viewModel.uiState.value
        assertEquals(2 to 2, state.progress)
        assertEquals(2, state.successCount)
        assertEquals(0, state.failureCount)
        assertTrue(state.isSuccess)
        assertEquals(1, state.concurrency)
        assertEquals(emptyMap(), state.fileProgressMap, "跑完了就没有还在跑的文件了")
        assertTrue(state.totalTimeMillis >= 0)
    }

    @Test
    fun `changing the reference loudness in the settings changes what the next run writes`() = runBlocking<Unit> {
        AudioFixtures.requireFfmpeg()
        val fixture = placeAudio("三.wav", amplitude = 0.5)
        val song = scanLibrary().single()

        settings.saveReplayGainTargetLoudness(-23.0)

        val viewModel = viewModel()
        viewModel.setSelectionUris(listOf(song.uri))
        viewModel.startBatchScan()

        awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
        val finished = awaitTask(assertNotNull(viewModel.uiState.value.currentTaskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, finished.status)

        val config = Json.parseToJsonElement(assertNotNull(finished.configJson)).jsonObject
        assertEquals("-23.0", config["targetLoudness"]?.jsonPrimitive?.content)

        val written = tags.read(song.uri, AudioTagReadOptions())
        assertEquals("-23 LUFS", written.replayGainReferenceLoudness)
        val gain = assertNotNull(written.replayGainTrackGain).removeSuffix(" dB").toDouble()
        assertEquals(-23.0 - assertNotNull(AudioFixtures.integratedLufs(fixture.file)), gain, 0.1)
    }

    // ------------------------------------------------------------------ 什么都没发生的那些情况

    @Test
    fun `starting with an empty selection creates no task`() = runBlocking<Unit> {
        val viewModel = viewModel()
        viewModel.openReplayGainConfig()

        viewModel.startBatchScan()

        assertNull(viewModel.uiState.value.currentTaskId, "空选择不该建任务")
        assertFalse(viewModel.uiState.value.showProgressDialog)
        assertEquals(emptyList<BatchTaskEntity>(), taskRows())
    }

    @Test
    fun `the concurrency is clamped to the range the engine allows`() {
        val viewModel = viewModel()

        viewModel.setConcurrency(0)
        assertEquals(1, viewModel.uiState.value.concurrency)
        viewModel.setConcurrency(9)
        assertEquals(5, viewModel.uiState.value.concurrency)
    }

    @Test
    fun `closing the progress dialog clears the progress it was showing`() {
        val viewModel = viewModel()
        viewModel.openReplayGainConfig()
        assertTrue(viewModel.uiState.value.showConfigDialog)

        viewModel.closeReplayGainConfig()
        assertFalse(viewModel.uiState.value.showConfigDialog)

        viewModel.closeProgressDialog()
        assertFalse(viewModel.uiState.value.showProgressDialog)
        assertNull(viewModel.uiState.value.progress)
        assertFalse(viewModel.uiState.value.isRunning)
    }

    // ------------------------------------------------------------------ 恢复

    @Test
    fun `a running scan left by the previous process is picked up again`() = runBlocking<Unit> {
        AudioFixtures.requireFfmpeg()
        placeAudio("恢复.wav", amplitude = 0.5)
        val song = scanLibrary().single()
        val taskId = tasks.createTask(BatchTaskType.SCAN_REPLAY_GAIN, listOf(song), configJson = null)
        tasks.markRunning(taskId)

        val viewModel = viewModel()

        awaitUntil(describe = { viewModel.uiState.value.currentTaskId }, condition = { it != null })
        val state = viewModel.uiState.value
        assertEquals(taskId, state.currentTaskId)
        assertTrue(state.isRunning)
        assertTrue(state.showProgressDialog)
    }

    // ------------------------------------------------------------------ 辅助

    private fun viewModel(): BatchReplayGainViewModel =
        provider[BatchReplayGainViewModel::class].also { createdViewModels += it }

    private suspend fun awaitTask(taskId: String, timeoutMillis: Long = 120_000L): BatchTaskEntity {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val task = tasks.getTask(taskId)
            if (task != null && task.status in FINISHED_STATUSES) return task
            delay(50L)
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

    /**
     * 一个真的能测量的夹具：5 秒单声道 WAV（写的是样本，不是编码过的音乐，所以输出可预测）。
     *
     * `peakText` 取自**写进文件的样本**：1 kHz 正弦在 44.1 kHz 上每周期只有 44.1 个样本，永远踩不到
     * 波峰，所以峰值不是幅度，这条断言才是算术而不是近似。
     */
    private fun placeAudio(name: String, amplitude: Double): Fixture {
        val file = musicDir.resolve(name)
        val samples = AudioFixtures.sine(5.0, amplitude = amplitude)
        AudioFixtures.writeWav(file, samples, channels = 1)
        return Fixture(file, samples)
    }

    private class Fixture(val file: Path, val samples: DoubleArray) {
        val peakText: String = String.format(
            Locale.US,
            "%.6f",
            samples.maxOfOrNull { abs(it) } ?: 0.0,
        )
    }

    private companion object {
        val FINISHED_STATUSES = setOf(
            BatchTaskStatus.SUCCEEDED,
            BatchTaskStatus.FAILED,
            BatchTaskStatus.CANCELLED,
        )
    }
}
