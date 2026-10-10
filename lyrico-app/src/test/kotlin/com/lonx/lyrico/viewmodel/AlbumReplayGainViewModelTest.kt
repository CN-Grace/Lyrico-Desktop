package com.lonx.lyrico.viewmodel

import com.lonx.audiotag.model.AudioTagData
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.ReplayGainPeakMode
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRequest
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.domain.song.usecase.PatchSongTagsUseCase
import com.lonx.lyrico.domain.song.usecase.SaveAudioTagsResult
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.album_replay_gain_success
import com.lonx.lyrico.resources.replay_gain_calculate_cancelled
import com.lonx.lyrico.resources.replay_gain_error_codec_exception
import com.lonx.lyrico.resources.replay_gain_error_general
import com.lonx.lyrico.resources.replay_gain_error_no_audio_track
import com.lonx.lyrico.resources.replay_gain_error_zero_sample_count
import com.lonx.lyrico.resources.replay_gain_no_songs
import com.lonx.lyrico.support.AudioFixtures
import com.lonx.lyrico.utils.UiMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
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
 * 专辑 ReplayGain 界面那一半的驱动器：**一次专辑测量真的把专辑标签写到专辑里每个文件上。**
 *
 * 这一层测的是 `AlbumActionsViewModel` 与它背后的真东西接起来之后的行为，而不是数字本身（数字由
 * `ReplayGainScannerTest` 钉住）。所以这里全部是真的：真实的 Koin 图（`desktopAppModule`，也就是
 * `AlbumsPage` 拿到的那个 `AlbumActionsViewModel`）、真实的 Room、真实的库扫描、真实的 ffmpeg 边车、
 * 真实的 TagLib 写回与读回。fixture 是手写 WAV，所以峰值期望值是算出来的（每个文件样本的最大绝对值），
 * 而不是问 ffmpeg。
 *
 * 有意不覆盖：`SaveAudioTagsResult.Failed` 那一条（桌面端要让 TagLib 真的写失败才走得到，测试里制造不出来；
 * 错误分支由下面四条坏输入覆盖），以及界面自己的渲染
 * （`AlbumReplayGainProgressBottomSheetTest`）。
 */
class AlbumReplayGainViewModelTest {

    private val dataRoot = Files.createTempDirectory("lyrico-album-replay-gain").toFile()
    private val directories = AppDirectories(root = dataRoot, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-album-replay-gain-music")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var database: LyricoDatabase
    private lateinit var library: SongLibraryRepository
    private lateinit var tags: AudioTagRepository
    private lateinit var scans: LibraryScanRepository
    private lateinit var settings: SettingsRepository
    private lateinit var index: LibraryIndexRepository
    private lateinit var patch: PatchSongTagsUseCase
    private lateinit var viewModel: AlbumActionsViewModel

    /** 库根目录只插一次；扫描器按文件夹同步。 */
    private var rootAdded = false

    @BeforeTest
    fun setUp() {
        // `calculateAlbumReplayGain` 用 `viewModelScope` 启动，也就是 Main；停在它上面的工作是真实 IO，
        // 所以 Main 用一个真调度器而不是虚拟调度器（与 `AlbumActionsViewModelTest` 同一条线）。
        Dispatchers.setMain(Dispatchers.Default)
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        val koin = GlobalContext.get()
        database = koin.get()
        library = koin.get()
        tags = koin.get()
        scans = koin.get()
        settings = koin.get()
        index = koin.get()
        patch = koin.get()
        viewModel = koin.get()
        // 应用默认会忽略短音频，而 fixture 只有几秒：不改这个设置，扫描什么都插不进去，下面每条断言都会
        // 为错误的理由通过。
        runBlocking {
            settings.saveIgnoreShortAudio(false)
            settings.saveReplayGainTargetLoudness(-18.0)
            settings.saveReplayGainPeakMode(ReplayGainPeakMode.SAMPLE_PEAK)
        }
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        Dispatchers.resetMain()
        scope.cancel()
        dataRoot.deleteRecursively()
        musicDir.toFile().deleteRecursively()
    }

    // ------------------------------------------------------------------ 一次成功的专辑测量

    @Test
    fun `measuring an album writes the album tags to every song and reports the count`() = runBlocking<Unit> {
        AudioFixtures.requireFfmpeg()
        val loudest = placeAudio("first.wav", amplitude = 0.5)
        val quieter = placeAudio("second.wav", amplitude = 0.25)
        val albumSongs = scanAlbum()
        val album = index.observeAlbums().first().single()

        val states = startCollectingStates()
        val messages = startCollecting()
        viewModel.calculateAlbumReplayGain(album.id)

        val success = messages.nextLocalized("the run has to end with a report")
        assertEquals(Res.string.album_replay_gain_success, success.res)
        assertEquals(listOf(2), success.args.toList(), "both songs were written")
        assertNull(messages.nextOrNull(300L), "a clean run reports exactly once")

        // 进度：分析的 90% 与写标签的 10% 都走过一遍，最后一格是 1。
        val progresses = states.mapNotNull { it.albumReplayGainProgress }
        assertEquals(0f, progresses.first(), 1e-6f, "the bar starts empty")
        assertEquals(1f, progresses.last(), 1e-6f, "the bar ends full")
        assertTrue(
            progresses.zipWithNext().all { (before, after) -> after >= before },
            "progress must not go backwards: $progresses",
        )
        assertTrue(progresses.all { it in 0f..1f }, "progress stays in range: $progresses")
        assertTrue(
            states.any { it.isCalculatingAlbumReplayGain && it.albumReplayGainWrittenCount == 0 },
            "the in-flight state has to be observable, or the sheet could not show 'calculating'",
        )

        val finished = states.last()
        assertFalse(finished.isCalculatingAlbumReplayGain, "the run is over")
        assertEquals(2, finished.albumReplayGainSongCount)
        assertEquals(2, finished.albumReplayGainWrittenCount)
        assertEquals("Album One", finished.albumName, "the sheet's title comes from the state, not the page")
        assertTrue(finished.albumReplayGainTotalTimeMillis > 0L, "an elapsed time is reported once it stops")
        assertTrue(
            finished.showAlbumReplayGainProgressDialog,
            "the sheet stays up after the run so the user can read the result; only `close` takes it down",
        )

        // 磁盘上的标签：两个文件拿到**同一份**专辑标签，因为专辑响度是专辑的属性，播放器要能在任何一首歌上
        // 找到它。
        val loudestTags = tags.read(loudest.file.toString(), AudioTagReadOptions(strict = true))
        val quieterTags = tags.read(quieter.file.toString(), AudioTagReadOptions(strict = true))
        assertEquals("-18 LUFS", loudestTags.replayGainReferenceLoudness)
        assertEquals("-18 LUFS", quieterTags.replayGainReferenceLoudness)
        assertEquals(
            assertNotNull(loudestTags.replayGainAlbumGain),
            quieterTags.replayGainAlbumGain,
            "every song of the album carries the album's gain, not its own",
        )
        assertEquals(
            loudest.peakText,
            loudestTags.replayGainAlbumPeak,
            "the album peak is the album's loudest sample, and the fixture's own samples decide it",
        )
        assertEquals(loudest.peakText, quieterTags.replayGainAlbumPeak)
        assertNull(loudestTags.replayGainTrackGain, "an album run must not touch the track tags")
        assertNull(loudestTags.replayGainTrackPeak)
        assertNull(quieterTags.replayGainTrackGain)
        assertNull(quieterTags.replayGainTrackPeak)

        // 独立核对增益：两首等长歌曲的专辑响度是它们**能量**的均值（不是分贝值的算术均值 —— 分贝是对数的，
        // 直接平均会差将近 1 dB），所以期望值可以自己算。扫描器自己的公式在 `ReplayGainScannerTest`
        // 里已经用同样这个式子钉到 ±0.1。
        val albumLufs = 10.0 * log10(
            listOf(loudest, quieter)
                .map { assertNotNull(AudioFixtures.integratedLufs(it.file), "ffmpeg must measure ${it.file}") }
                .map { 10.0.pow(it / 10.0) }
                .average(),
        )
        val gain = assertNotNull(loudestTags.replayGainAlbumGain).removeSuffix(" dB").toDouble()
        assertEquals(-18.0 - albumLufs, gain, 0.2)

        // 关掉之后计数器清空：下一次打开这张专辑不会看到上一次的数字。
        viewModel.closeAlbumReplayGainProgressDialog()
        assertFalse(viewModel.uiState.value.showAlbumReplayGainProgressDialog)
        viewModel.clearAlbumReplayGainProgressDialog()
        val cleared = viewModel.uiState.value
        assertNull(cleared.albumReplayGainProgress)
        assertEquals(0, cleared.albumReplayGainSongCount)
        assertEquals(0, cleared.albumReplayGainWrittenCount)
        assertEquals(0L, cleared.albumReplayGainTotalTimeMillis)
        assertEquals(2, albumSongs.size)
    }

    @Test
    fun `a second tap while the measurement is running is ignored`() = runBlocking<Unit> {
        AudioFixtures.requireFfmpeg()
        placeAudio("first.wav", amplitude = 0.5)
        placeAudio("second.wav", amplitude = 0.25)
        scanAlbum()

        val messages = startCollecting()
        viewModel.calculateAlbumReplayGain(index.observeAlbums().first().single().id)
        awaitTrue("the run has to start", 10_000L) { viewModel.uiState.value.isCalculatingAlbumReplayGain }
        // 同一个 job 还在跑的时候再点一次：守卫必须挡住，否则会有两份测量同时往同一批文件里写。
        viewModel.calculateAlbumReplayGain(index.observeAlbums().first().single().id)

        val success = messages.nextLocalized("the first run still has to finish")
        assertEquals(Res.string.album_replay_gain_success, success.res)
        assertEquals(listOf(2), success.args.toList(), "the count is the album's, not twice the album's")
        assertNull(messages.nextOrNull(500L), "the ignored tap must not produce a second report")
        assertEquals(2, viewModel.uiState.value.albumReplayGainSongCount)
    }

    // ---------------------------------------------------------------------- 取消与重启

    @Test
    fun `aborting a run stops it, reports it, and leaves nothing behind`() = runBlocking<Unit> {
        AudioFixtures.requireFfmpeg()
        val long = placeLongAudio()
        val albumSongs = scanLibrary()
        assertEquals(1, albumSongs.size, "the long fixture is the whole album")

        val messages = startCollecting()
        viewModel.calculateAlbumReplayGain(albumSongs)
        awaitTrue("the long run has to start", 10_000L) { viewModel.uiState.value.isCalculatingAlbumReplayGain }

        // 测量还在跑的时候，面板不许被关掉：那会把正在进行的动作藏起来。
        viewModel.closeAlbumReplayGainProgressDialog()
        assertTrue(viewModel.uiState.value.showAlbumReplayGainProgressDialog)
        assertTrue(viewModel.uiState.value.isCalculatingAlbumReplayGain)

        viewModel.cancelAlbumReplayGain()

        assertEquals(
            Res.string.replay_gain_calculate_cancelled,
            messages.nextLocalized("aborting is reported, the way Android reported it", 5_000L).res,
        )
        assertNull(messages.nextOrNull(2_000L), "an aborted run reports no success")
        awaitTrue("the aborted run has to stop", 5_000L) { !viewModel.uiState.value.isCalculatingAlbumReplayGain }
        assertNull(
            tags.read(long.file.toString(), AudioTagReadOptions(strict = true)).replayGainAlbumGain,
            "an aborted measurement must not leave album tags behind",
        )
        assertTrue(
            viewModel.uiState.value.showAlbumReplayGainProgressDialog,
            "the sheet stays up after an abort so the user can close it deliberately",
        )
    }

    @Test
    fun `a run that replaces an aborted one is not marked finished by the run it replaced`() = runBlocking<Unit> {
        AudioFixtures.requireFfmpeg()
        placeLongAudio()
        val albumSongs = scanLibrary()

        // 状态与消息都记进同一条日志，这样"谁先发生"是可判定的：被取消的那个 job 的 `finally` 会在重启之后
        // 几毫秒内跑，而替补的那次测量要跑大约一秒（30 分钟的 flac，`ffmpeg -af ebur128` 实测 0.97 s）。
        val log = java.util.Collections.synchronizedList(mutableListOf<Any>())
        val restart = Any()
        val messages = startCollecting()
        scope.launch {
            viewModel.uiState.collect { log.add(LoggedState(it.isCalculatingAlbumReplayGain)) }
        }
        scope.launch { viewModel.events.collect { log.add(LoggedMessage(it)) } }

        viewModel.calculateAlbumReplayGain(albumSongs)
        awaitTrue("the first run has to start", 10_000L) { viewModel.uiState.value.isCalculatingAlbumReplayGain }
        viewModel.cancelAlbumReplayGain()
        // 取消会把 `isCalculating` 立刻置回 false，所以紧接着的这一次会被放行 —— 而被取消的那个 job 的
        // `finally` 还在后面排队。没有 token 的话它会把替补测量的"计算中"抹掉，面板就会在扫描中途变成
        // "关闭"，用户还能再点一次，发起重复测量。
        log.add(restart)
        viewModel.calculateAlbumReplayGain(albumSongs)

        // 中止自己会先报一次；再往下才是替补测量的结果。
        assertEquals(
            Res.string.replay_gain_calculate_cancelled,
            messages.nextLocalized("the abort has to be reported").res,
        )
        val success = messages.nextLocalized("the replacement run has to finish")
        assertEquals(Res.string.album_replay_gain_success, success.res)
        awaitTrue("the replacement run has to stop", 5_000L) { !viewModel.uiState.value.isCalculatingAlbumReplayGain }

        val afterRestart = log.drop(log.indexOf(restart))
        val reportedAt = afterRestart.indexOfFirst { entry ->
            entry is LoggedMessage && (entry.message as? UiMessage.Localized)?.res == Res.string.album_replay_gain_success
        }
        val stoppedAt = afterRestart.indexOfFirst { it is LoggedState && !it.calculating }
        assertTrue(stoppedAt >= 0, "the replacement run has to be observed as finished: $afterRestart")
        assertTrue(
            reportedAt in 0 until stoppedAt,
            "the run must stay 'calculating' until it reports; a `false` before the report is the run it " +
                "replaced writing the state of the run that replaced it: $afterRestart",
        )
    }

    // ------------------------------------------------------------------------ 错误与空专辑

    @Test
    fun `an album with no songs reports that there is nothing to measure`() = runBlocking<Unit> {
        val messages = startCollecting()

        // 不存在的专辑 id：索引里没有歌，所以既没有可测量的东西，也不该去动 ffmpeg。
        viewModel.calculateAlbumReplayGain(albumId = 9_999L)

        assertEquals(Res.string.replay_gain_no_songs, messages.nextLocalized("an empty album is reported", 10_000L).res)
        assertFalse(viewModel.uiState.value.isCalculatingAlbumReplayGain)
        assertFalse(viewModel.uiState.value.showAlbumReplayGainProgressDialog)
    }

    @Test
    fun `every way the decoder can refuse a file is reported as its own message`() = runBlocking<Unit> {
        AudioFixtures.requireFfmpeg()
        val real = placeAudio("first.wav", amplitude = 0.5)
        val song = scanLibrary().single()

        val broken = listOf(
            // 有内容但不是音频：ffmpeg 退出码非 0。
            Res.string.replay_gain_error_codec_exception to placeBytes(
                "garbage.mp3",
                ByteArray(300_000) { ((it * 31) % 251).toByte() },
            ),
            // 合法头、零帧：ffmpeg 成功，但没有任何样本可测。
            Res.string.replay_gain_error_zero_sample_count to
                musicDir.resolve("empty.wav").also { AudioFixtures.writeWav(it, DoubleArray(0), channels = 1) },
            // 有内容、认得出来，但不是音频：没有音频轨。
            Res.string.replay_gain_error_no_audio_track to placePng("picture.png"),
            // 文件不见了：连读都读不到。
            Res.string.replay_gain_error_general to musicDir.resolve("absent.flac"),
        )

        for ((expected, file) in broken) {
            val messages = startCollecting()
            viewModel.calculateAlbumReplayGain(listOf(song.copy(uri = file.toString(), filePath = file.toString())))

            val message = messages.nextLocalized("${file.fileName} has to be reported", 30_000L)
            assertEquals(expected, message.res, "wrong message for ${file.fileName}")
            assertNull(messages.nextOrNull(300L), "${file.fileName} reports once")
            assertFalse(viewModel.uiState.value.isCalculatingAlbumReplayGain)
            if (expected == Res.string.replay_gain_error_codec_exception) {
                // 解码器自己的话要能到用户手里，而不是被换成"不支持这种编码"。
                val detail = message.args.joinToString()
                assertContains(detail, "ffmpeg")
                assertContains(detail, file.fileName.toString())
            }
            assertNull(
                tags.read(real.file.toString(), AudioTagReadOptions(strict = true)).replayGainAlbumGain,
                "a failed measurement must not write album tags, and never to the wrong file",
            )
        }
    }

    // ---------------------------------------------------------------------------- helpers

    /**
     * 一个 fixture 与它自己的样本必须算出来的峰值文本。
     *
     * 峰值**不是**幅度：1 kHz 正弦在 44.1 kHz 上每个周期只有 44.1 个样本，永远踩不到波峰，最大样本约是
     * 幅度的 0.99999 倍。期望值取自写进文件的样本，这条断言才是算术而不是近似。
     */
    private class Fixture(val file: Path, val samples: DoubleArray) {
        val peakText: String = String.format(
            Locale.US,
            "%.6f",
            samples.maxOfOrNull { abs(it) } ?: 0.0,
        )
    }

    /** 写一个 WAV 并打好专辑标签：专辑标签在扫描前就在盘上，所以索引里会长出真正的专辑行。 */
    private suspend fun placeAudio(
        name: String,
        amplitude: Double,
        album: String = "Album One",
        seconds: Double = 5.0,
    ): Fixture {
        val file = musicDir.resolve(name)
        val samples = AudioFixtures.sine(seconds, amplitude = amplitude)
        AudioFixtures.writeWav(file, samples, channels = 1)
        writeAlbumTag(file, album)
        return Fixture(file, samples)
    }

    /**
     * 五分钟的 flac，用 ffmpeg 把 5 秒 fixture 循环出来。
     *
     * 取消与"被替换的运行"都需要测量真的还在跑；几秒的 WAV 会在测试来得及按取消之前就总结完。五分钟足够
     * 让分析持续若干秒，而 flac 让文件不至于上百 MB。
     */
    /**
     * 一个真的能跑上一会儿的夹具：30 分钟的单声道 flac（5 秒正弦循环 360 次）。
     *
     * 纯正弦的 flac 编码/解码都比真实音乐快得多（实测编码 0.75 s、`ebur128` 0.97 s），所以长度得往
     * 半小时这个量级堆，才能让"测量还在跑"这件事有肉眼可见的窗口 —— 取消与重启这两项测试都靠它。
     * 原来的 5 分钟版本只跑 0.19 s：取消后的 `finally` 与替补测量只差几毫秒，判定会变成毫秒级赛跑。
     */
    private fun placeLongAudio(name: String = "long.flac", album: String = "Album One"): Fixture {
        val source = musicDir.resolve("long-source.wav")
        val samples = AudioFixtures.sine(5.0, amplitude = 0.5)
        AudioFixtures.writeWav(source, samples, channels = 1)
        val target = musicDir.resolve(name)
        val run = AudioFixtures.runFfmpeg(
            "-nostdin", "-hide_banner", "-y",
            "-stream_loop", "359", "-i", source.toString(),
            "-c:a", "flac",
            target.toString(),
        )
        assertEquals(0, run.exitCode, "ffmpeg must build the long fixture: ${run.stderr}")
        Files.delete(source)
        check(Files.size(target) > 1_000_000L) { "the long fixture has to be a real decode job" }
        return Fixture(target, samples)
    }

    private fun placeBytes(name: String, bytes: ByteArray): Path {
        val file = musicDir.resolve(name)
        Files.write(file, bytes)
        return file
    }

    /** 一张真的 PNG（用 ffmpeg 生成）：有内容、认得出来，但没有音频轨。 */
    private fun placePng(name: String): Path {
        val file = musicDir.resolve(name)
        val run = AudioFixtures.runFfmpeg(
            "-nostdin", "-hide_banner", "-y",
            "-f", "lavfi", "-i", "color=c=red:s=16x16:d=1",
            "-frames:v", "1",
            file.toString(),
        )
        assertEquals(0, run.exitCode, "ffmpeg must build the PNG fixture: ${run.stderr}")
        return file
    }

    private suspend fun writeAlbumTag(file: Path, album: String) {
        val result = patch(file.toString(), AudioTagData(album = album, artist = "An Artist"))
        assertTrue(result is SaveAudioTagsResult.Success, "tagging $file: $result")
    }

    /** 应用自己的扫描：先把库根目录记下来，再按文件夹同步，读的是刚才写到盘上的标签。 */
    private suspend fun scanLibrary(): List<SongEntity> {
        if (!rootAdded) {
            database.folderDao().insert(
                FolderEntity(path = musicDir.toRealPath().toString(), addedBySaf = true)
            )
            rootAdded = true
        }
        val result = scans.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))
        assertTrue(result.failures.isEmpty(), "the scan itself must not fail: ${result.failures}")
        return library.observeSongs(SortBy.TITLE, SortOrder.ASC).first()
    }

    /** 扫描并返回那个真专辑行里的歌，用来驱动 `calculateAlbumReplayGain(albumId)`。 */
    private suspend fun scanAlbum(): List<SongEntity> {
        val scanned = scanLibrary()
        assertEquals(2, scanned.size, "the album has exactly the two fixtures: ${scanned.map { it.fileName }}")
        val album = index.observeAlbums().first().single()
        assertEquals("Album One", album.name)
        assertEquals(2, album.songCount)
        return index.getSongsByAlbumId(album.id)
    }

    /** 真实消息流的一条记录；`events` 无重放，所以收集器必须在动作之前就挂上。 */
    private class MessageCollector {
        private val received = Channel<UiMessage>(Channel.UNLIMITED)

        suspend fun nextOrNull(timeoutMillis: Long): UiMessage? =
            withTimeoutOrNull(timeoutMillis) { received.receive() }

        suspend fun collect(message: UiMessage) {
            received.send(message)
        }
    }

    private suspend fun startCollecting(): MessageCollector {
        val collector = MessageCollector()
        val ready = CompletableDeferred<Unit>()
        scope.launch {
            viewModel.events
                .onSubscription { ready.complete(Unit) }
                .collect { message -> collector.collect(message) }
        }
        ready.await()
        return collector
    }

    /** `uiState` 的实时快照序列；StateFlow 会重放当前值，所以挂上就能看到起点。 */
    private suspend fun startCollectingStates(): List<AlbumActionsUiState> {
        val seen = java.util.Collections.synchronizedList(mutableListOf<AlbumActionsUiState>())
        val ready = CompletableDeferred<Unit>()
        scope.launch {
            viewModel.uiState
                .onSubscription { ready.complete(Unit) }
                .collect { seen.add(it) }
        }
        ready.await()
        return seen
    }

    /**
     * 把两条流记进一条日志，用来判定"谁先发生"。
     *
     * 直接断言两个收集器各自的列表做不到这一点：状态与消息是两条流，它们的先后顺序只在真实时间上有意义。
     */
    private class LoggedState(val calculating: Boolean)

    private class LoggedMessage(val message: UiMessage)

    /**
     * 一条**本地化**消息，等不到就直接失败。
     *
     * 事件在这一层只以 [UiMessage.Localized] 的形式出现，断言的是资源与参数，不是解析后的文本 ——
     * 否则机器的语言会让测试因为错误的理由通过。
     */
    private suspend fun MessageCollector.nextLocalized(
        message: String,
        timeoutMillis: Long = 60_000L,
    ): UiMessage.Localized = assertIs(assertNotNull(nextOrNull(timeoutMillis), message))

    private suspend fun awaitTrue(message: String, timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10L)
        }
        throw AssertionError("$message (waited ${timeoutMillis}ms)")
    }
}
