package com.lonx.lyrico.worker.processor

import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.ReplayGainPeakMode
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.BatchTaskItemEntity
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
import com.lonx.lyrico.platform.FfmpegAudioDecoder
import com.lonx.lyrico.platform.FfmpegSidecar
import com.lonx.lyrico.support.AudioFixtures
import com.lonx.lyrico.utils.ReplayGainScanner
import com.lonx.lyrico.worker.BatchTaskRunner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C6d 的最后一层验收：**一批 ReplayGain 真的落到磁盘上的标签里。**
 *
 * 上面三层（解码器、JNI、扫描器）都在测「数字对不对」。这一层测的是「这个数字有没有变成用户文件里的
 * 字节」：真实的 Koin 图（`desktopAppModule`）、真实的 Room 数据库、真实的库扫描、真实的 TagLib 读回，
 * 由真实的 [BatchTaskRunner] 驱动。fixture 是手写 WAV，所以峰值期望值是算出来的
 * （幅度 0.5 → `0.500000`），增益期望值对着 ffmpeg 的 `ebur128` 滤镜核对。
 *
 * 有意不覆盖：UI 那一半（专辑行与进度面板，见 C6d-5）、任务的取消（`BatchTaskRunnerTest` 已覆盖）。
 */
class ReplayGainProcessorEndToEndTest {

    private val dataRoot = Files.createTempDirectory("lyrico-replay-gain-processor").toFile()
    private val directories = AppDirectories(root = dataRoot, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-replay-gain-music")

    private val json = Json { encodeDefaults = true }

    private lateinit var database: LyricoDatabase
    private lateinit var tasks: BatchTaskRepository
    private lateinit var runner: BatchTaskRunner
    private lateinit var songs: SongLibraryRepository
    private lateinit var tags: AudioTagRepository
    private lateinit var scanner: LibraryScanRepository
    private lateinit var settings: SettingsRepository

    /** The library root row is inserted once per test, on the first scan. */
    private var rootAdded = false

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        val koin = GlobalContext.get()
        database = koin.get()
        tasks = koin.get()
        runner = koin.get()
        songs = koin.get()
        tags = koin.get()
        scanner = koin.get()
        settings = koin.get()
        // The app's default drops short audio; the fixtures are seconds long, and a scan that inserts
        // nothing would make every test below pass for the wrong reason.
        runBlocking { settings.saveIgnoreShortAudio(false) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        dataRoot.deleteRecursively()
        musicDir.toFile().deleteRecursively()
    }

    @Test
    fun `measuring a song writes the three replay gain tags into the file`() = runBlocking<Unit> {
        val audio = placeAudio(amplitude = 0.5)
        val song = scanLibrary().single { it.uri == audio.file.toRealPath().toString() }

        val taskId = createTask(song, targetLoudness = -18.0)
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)
        val item = items(taskId).single()
        assertEquals(BatchTaskStatus.SUCCEEDED, item.status)
        assertNull(item.errorMessage)

        // Read back from disk with the same strict reader the processor used to decide whether to
        // skip: these are the bytes another player will see.
        val written = tags.read(song.uri, AudioTagReadOptions(strict = true))
        assertEquals("-18 LUFS", written.replayGainReferenceLoudness)
        // Exact, because it comes from the fixture's own samples rather than from a measurement.
        assertEquals(audio.peakText, written.replayGainTrackPeak)

        val gain = assertNotNull(written.replayGainTrackGain).removeSuffix(" dB").toDouble()
        // -18 - (-9.02) = -8.98 for this fixture. The independent number is ffmpeg's own ebur128
        // filter (-9.0 on the same file), and the 0.1 allows for the block-grid residual the scanner
        // tests document.
        assertEquals(-18.0 - assertNotNull(AudioFixtures.integratedLufs(audio.file)), gain, 0.1)

        assertEquals(1, songs.getSongCount(), "measuring must not insert or remove library rows")
    }

    @Test
    fun `a second run skips a song that already carries tags for this target`() = runBlocking<Unit> {
        val audio = placeAudio(amplitude = 0.5)
        val song = scanLibrary().single { it.uri == audio.file.toRealPath().toString() }

        val first = createTask(song, targetLoudness = -18.0)
        runner.run(first)
        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(first)?.status)
        val afterFirst = tags.read(song.uri, AudioTagReadOptions(strict = true))
        val modifiedAfterFirst = Files.getLastModifiedTime(audio.file)

        val second = createTask(song, targetLoudness = -18.0)
        runner.run(second)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(second)?.status)
        val item = items(second).single()
        assertEquals(BatchTaskStatus.SKIPPED, item.status)
        // `BatchTaskRunner` stores a skip reason in resultJson (a failure stores its message in
        // errorMessage), which is where the row's subtitle reads it from.
        assertEquals("ReplayGain already exists", item.resultJson)
        assertNull(item.errorMessage)

        // Skipped means untouched: the tags are what the first run wrote and the file was not rewritten.
        val afterSecond = tags.read(song.uri, AudioTagReadOptions(strict = true))
        assertEquals(afterFirst.replayGainTrackGain, afterSecond.replayGainTrackGain)
        assertEquals(afterFirst.replayGainTrackPeak, afterSecond.replayGainTrackPeak)
        assertEquals(modifiedAfterFirst, Files.getLastModifiedTime(audio.file))
    }

    @Test
    fun `a different target loudness re-measures instead of trusting the old tags`() = runBlocking<Unit> {
        val audio = placeAudio(amplitude = 0.5)
        val song = scanLibrary().single { it.uri == audio.file.toRealPath().toString() }

        runner.run(createTask(song, targetLoudness = -18.0))
        val first = tags.read(song.uri, AudioTagReadOptions(strict = true))

        val secondTask = createTask(song, targetLoudness = -14.0)
        runner.run(secondTask)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(secondTask)?.status)
        assertEquals(BatchTaskStatus.SUCCEEDED, items(secondTask).single().status)

        val second = tags.read(song.uri, AudioTagReadOptions(strict = true))
        // The reference loudness is part of the tag set: tags measured against -18 say nothing about a
        // user who just switched to -14, so the song has to be measured again.
        assertEquals("-18 LUFS", first.replayGainReferenceLoudness)
        assertEquals("-14 LUFS", second.replayGainReferenceLoudness)
        val gain = assertNotNull(second.replayGainTrackGain).removeSuffix(" dB").toDouble()
        assertEquals(-14.0 - assertNotNull(AudioFixtures.integratedLufs(audio.file)), gain, 0.1)
    }

    @Test
    fun `a task without its own settings falls back to the saved ones`() = runBlocking<Unit> {
        val audio = placeAudio(amplitude = 0.5)
        val song = scanLibrary().single { it.uri == audio.file.toRealPath().toString() }
        settings.saveReplayGainTargetLoudness(-23.0)
        settings.saveReplayGainPeakMode(ReplayGainPeakMode.SAMPLE_PEAK)

        // Only a concurrency: the target and the peak mode are the user's saved preferences, which is
        // the path `toReplayGainSettingsOrNull` returning null has to take.
        val taskId = createTask(song, targetLoudness = null, peakMode = null)
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)
        val written = tags.read(song.uri, AudioTagReadOptions(strict = true))
        assertEquals("-23 LUFS", written.replayGainReferenceLoudness)
        val gain = assertNotNull(written.replayGainTrackGain).removeSuffix(" dB").toDouble()
        assertEquals(-23.0 - assertNotNull(AudioFixtures.integratedLufs(audio.file)), gain, 0.1)
    }

    @Test
    fun `a task with no config at all is skipped rather than guessed at`() = runBlocking<Unit> {
        val audio = placeAudio(amplitude = 0.5)
        val song = scanLibrary().single { it.uri == audio.file.toRealPath().toString() }

        val taskId = tasks.createTask(BatchTaskType.SCAN_REPLAY_GAIN, listOf(song), null)
        runner.run(taskId)

        // A batch row without a config is a bad row, not a licence to measure with defaults: the user
        // would not know which reference loudness the tags it writes belong to.
        val item = items(taskId).single()
        assertEquals(BatchTaskStatus.SKIPPED, item.status)
        assertEquals("No config", item.resultJson)
        assertNull(tags.read(song.uri, AudioTagReadOptions(strict = true)).replayGainTrackGain)
    }

    @Test
    fun `a song that left the library is skipped instead of measured`() = runBlocking<Unit> {
        val audio = placeAudio(amplitude = 0.5)
        val song = scanLibrary().single { it.uri == audio.file.toRealPath().toString() }
        val taskId = createTask(song, targetLoudness = -18.0)
        songs.deleteSongsByUris(listOf(song.uri))

        runner.run(taskId)

        val item = items(taskId).single()
        assertEquals(BatchTaskStatus.SKIPPED, item.status)
        assertEquals("Song not found", item.resultJson)
    }

    @Test
    fun `a missing ffmpeg sidecar fails the row and says how to install it`() = runBlocking<Unit> {
        val audio = placeAudio(amplitude = 0.5)
        val song = scanLibrary().single { it.uri == audio.file.toRealPath().toString() }
        val taskId = createTask(song, targetLoudness = -18.0)
        val task = assertNotNull(tasks.getTask(taskId))
        val item = items(taskId).single()
        val nowhere = musicDir.resolve("ffmpeg-not-installed")

        // The processor is built by hand with a decoder that cannot find the sidecar, so this test does
        // not depend on the machine's ffmpeg. Everything else is the real graph: real task row, real
        // song row, real file. The runner records the message; here the call itself is what is checked.
        val orphaned = ReplayGainProcessor(
            songLibraryRepository = songs,
            patchSongTagsUseCase = GlobalContext.get().get(),
            replayGainScanner = ReplayGainScanner(FfmpegAudioDecoder(FfmpegSidecar(listOf(nowhere)))),
            settingsRepository = settings,
            audioTagRepository = tags,
        )

        val failure = assertFailsWith<Exception> { process(orphaned, task, item) }

        val message = failure.message.orEmpty()
        assertContains(message, nowhere.toString())
        assertContains(message, "fetch-ffmpeg.ps1")
        assertTrue(message.startsWith("ReplayGain analysis failed:"), "the message must reach the user: $message")
        // Nothing was written to the song on the way out.
        assertNull(tags.read(song.uri, AudioTagReadOptions(strict = true)).replayGainTrackGain)
    }

    // -------------------------------------------------------------------------------- helpers

    /**
     * A fixture and the peak text its own samples must produce.
     *
     * The peak is *not* the amplitude: a 1 kHz sine sampled at 44.1 kHz only lands near its crest
     * (44.1 samples per period), so its largest sample is about 0.99999 of the amplitude. Taking the
     * expectation from the written samples keeps the assertion arithmetic instead of approximate.
     */
    private class Fixture(val file: Path, val samples: DoubleArray) {
        val peakText: String = String.format(
            java.util.Locale.US,
            "%.6f",
            samples.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0,
        )
    }

    /** Writes a fixture and returns it, without touching the library. */
    private fun placeAudio(amplitude: Double, seconds: Double = 5.0): Fixture {
        val name = "song-${musicDir.toFile().list()?.size ?: 0}.wav"
        val file = musicDir.resolve(name)
        val samples = AudioFixtures.sine(seconds, amplitude = amplitude)
        AudioFixtures.writeWav(file, samples, channels = 1)
        return Fixture(file, samples)
    }

    /** The scan the app's own manager performs, awaited: real tags read into the real database. */
    private suspend fun scanLibrary(): List<SongEntity> {
        if (!rootAdded) {
            database.folderDao().insert(
                FolderEntity(path = musicDir.toRealPath().toString(), addedBySaf = true)
            )
            rootAdded = true
        }
        val result = scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))
        assertTrue(result.failures.isEmpty(), "the scan itself must not fail: ${result.failures}")
        return songs.observeSongs(com.lonx.lyrico.viewmodel.SortBy.TITLE, com.lonx.lyrico.viewmodel.SortOrder.ASC).first()
    }

    /**
     * Creates the batch task the (not yet ported) ReplayGain screen creates: the config carries the
     * concurrency the engine uses plus the user's target and peak mode, and nothing else.
     */
    private suspend fun createTask(
        song: SongEntity,
        targetLoudness: Double?,
        peakMode: ReplayGainPeakMode? = ReplayGainPeakMode.SAMPLE_PEAK,
    ): String = tasks.createTask(
        BatchTaskType.SCAN_REPLAY_GAIN,
        listOf(song),
        json.encodeToString(
            ReplayGainTaskConfig.serializer(),
            ReplayGainTaskConfig(
                concurrency = 1,
                targetLoudness = targetLoudness,
                peakMode = peakMode,
            ),
        ),
    )

    private suspend fun items(taskId: String): List<BatchTaskItemEntity> =
        tasks.observeItems(taskId).first()

    private suspend fun process(
        processor: BatchTaskProcessor,
        task: BatchTaskEntity,
        item: BatchTaskItemEntity,
    ) = processor.process(task, item) {}
}
