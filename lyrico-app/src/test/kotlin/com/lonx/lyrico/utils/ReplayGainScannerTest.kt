package com.lonx.lyrico.utils

import com.lonx.lyrico.data.model.ReplayGainPeakMode
import com.lonx.lyrico.platform.FfmpegAudioDecoder
import com.lonx.lyrico.platform.FfmpegSidecar
import com.lonx.lyrico.support.AudioFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * C6d 的第三层验收：**整条测量链路给出的数字是对的。**
 *
 * fixture 全都是手写 WAV（样本值已知），所以期望值来自算术而不是另一次 ffmpeg 运行：满幅正弦的
 * RMS 是 `1/√2`、声道数加倍是 +3.0103 LU、幅度减半是 −6.0206 LU、静音落在 BS.1770 的绝对门限。
 * ffmpeg 的 `ebur128` 滤镜只作为**独立的第二套接线**出现（它连的是同一份 libebur128）：它验证的是
 * 声道数/采样率/格式/分块边界这些本移植要负责的东西，不是标准本身。
 *
 * 有意不覆盖：K 加权曲线的实现（那是 libebur128 的事）、ffmpeg 自身的行为。
 */
class ReplayGainScannerTest {

    private val scanner = ReplayGainScanner()

    @Test
    fun `the same audio in two channels is three LU louder`() = runBlocking {
        val mono = stateOf(fixtures.monoFull)
        val dualMono = stateOf(fixtures.dualMonoFull)

        // Identical channels, one file with one of them and one with two: BS.1770 sums the channel
        // energies, so the stereo twin is exactly 10·log10(2) = 3.0103 LU louder. Nothing in the
        // pipeline may be folding the file down to mono, and nothing may be measuring only one
        // channel of the stereo file - either mistake reads as 0.00 or 6.02 here.
        assertEquals(10.0 * log10(2.0), dualMono.loudnessLufs - mono.loudnessLufs, 0.02)
        assertEquals("both files are full scale, so their peaks match", 1.0, dualMono.peak, 0.001)
        assertEquals(1.0, mono.peak, 0.001)
    }

    @Test
    fun `halving the amplitude costs six LU`() = runBlocking {
        val full = stateOf(fixtures.monoFull)
        val half = stateOf(fixtures.monoHalf)

        assertEquals(-6.0206, half.loudnessLufs - full.loudnessLufs, 0.02)
    }

    @Test
    fun `the absolute scale is anchored by arithmetic and by ffmpeg`() = runBlocking {
        val full = stateOf(fixtures.monoFull)
        val decibelMatched = stateOf(fixtures.stereoMinus23)

        // A full-scale 1 kHz sine has an RMS of -3.0103 dBFS, and 1 kHz is the K-weighting curve's
        // 0 dB point, so the reading lands on -3.00 LUFS (measured -3.0008).
        assertEquals(-3.0, full.loudnessLufs, 0.02)
        assertEquals(1.0, full.peak, 0.001)

        // The stereo fixture was written at -23 dBFS per channel, i.e. its RMS is 10^(-23/20)·√2
        // amplitude, and the channel sum adds the 3.0103 LU again.
        assertEquals(-23.0 + 10.0 * log10(2.0), decibelMatched.loudnessLufs, 0.05)
        assertEquals(AudioFixtures.rmsAmplitude(-23.0) * sqrt(2.0), decibelMatched.peak, 0.001)
    }

    @Test
    fun `the measurement agrees with ffmpeg's own ebur128 filter`() = runBlocking {
        val measured = mutableListOf<String>()
        for ((name, file) in fixtures.all) {
            val state = stateOf(file)
            val oracle = AudioFixtures.integratedLufs(file)
                ?: error("$name: ffmpeg's filter reported no number for a non-silent fixture")
            val delta = state.loudnessLufs - oracle
            measured += "$name: ours=${state.loudnessLufs} ffmpeg=$oracle delta=$delta"
            // Two different wirings of the same algorithm. The residual is small but not zero - the
            // 400 ms block grid and the relative gate land differently on short inputs, which is the
            // same reason the album test below allows 0.2 - so 0.1 is the agreement this pipeline is
            // required to keep.
            assertTrue("$name: $delta LU apart ($measured)", abs(delta) <= 0.1)
        }
        assertTrue("expected a table of comparisons: $measured", measured.size >= 5)
    }

    @Test
    fun `a silent track is measured at the gate and still gets a finite gain`() = runBlocking {
        val state = stateOf(fixtures.silence)

        // libebur128 answers -inf here; the scanner substitutes BS.1770's absolute gate so the tag
        // text stays writable (see ReplayGainScanner.SILENCE_LOUDNESS_LUFS).
        assertEquals(ReplayGainScanner.SILENCE_LOUDNESS_LUFS, state.loudnessLufs, 0.0)
        assertEquals(0.0, state.peak, 0.0)
        assertEquals(88_200L, state.sampleCount)

        val gain = scanner.formatGain(state)
        assertEquals("52.00 dB", gain)
        assertFalse("a silent track must not produce an infinite gain: $gain", gain.contains("Infinity"))
    }

    @Test
    fun `a file with no audio stream fails as no audio track`() = runBlocking {
        val failed = failureOf(fixtures.picture)

        assertEquals(ReplayGainError.NoAudioTrack, failed.error)
        assertNull("nothing was measured, so there is no codec to name", failed.mimeType)
    }

    @Test
    fun `an unreadable file fails as a codec exception carrying ffmpeg's words`() = runBlocking {
        val failed = failureOf(fixtures.garbage)

        val error = failed.error
        assertTrue("expected a codec exception, got $error", error is ReplayGainError.CodecException)
        // The message has to be worth showing: ffmpeg's exit code and its own complaint, not "analysis
        // failed".
        val message = (error as ReplayGainError.CodecException).message.orEmpty()
        assertTrue("message should come from ffmpeg: $message", message.contains("ffmpeg exited"))
        assertTrue("message should name the file's problem: $message", message.contains("garbage.mp3"))
    }

    @Test
    fun `a file that is not on disk fails as a general exception`() = runBlocking {
        val failed = failureOf(Path.of(fixtures.directory.toString(), "deleted-since-scan.wav"))

        // A missing file is a library problem, not a codec problem: it must not be reported as one.
        assertTrue("expected a general exception, got ${failed.error}", failed.error is ReplayGainError.GeneralException)
    }

    @Test
    fun `a missing sidecar is reported with the directories that were probed`() = runBlocking {
        val nowhere = fixtures.directory.resolve("ffmpeg-not-installed")
        val orphan = ReplayGainScanner(FfmpegAudioDecoder(FfmpegSidecar(listOf(nowhere))))

        val states = orphan.analyze(fixtures.monoFull.toString()).toList()
        val failed = states.filterIsInstance<ReplayGainCalculateState.Failed>().single()

        val message = (failed.error as ReplayGainError.GeneralException).message.orEmpty()
        assertTrue("should say where it looked: $message", message.contains(nowhere.toString()))
        assertTrue("should say how to install it: $message", message.contains("fetch-ffmpeg.ps1"))
    }

    @Test
    fun `a file with no frames fails as a zero sample count`() = runBlocking {
        val failed = failureOf(fixtures.noFrames)

        // The stream was found and the codec is known, but the measurement has nothing in it - the
        // batch row should not claim a loudness for it.
        assertEquals(ReplayGainError.ZeroSampleCount, failed.error)
    }

    @Test
    fun `an album is measured as one programme over its tracks`() = runBlocking {
        val states = scanner.analyzeAlbum(
            listOf(fixtures.monoFull.toString(), fixtures.monoHalf.toString()),
            ReplayGainPeakMode.SAMPLE_PEAK,
        ).toList()
        val success = states.filterIsInstance<AlbumReplayGainCalculateState.Success>().single()
        val album = success.analysis
        val loud = album.tracks[0]
        val quiet = album.tracks[1]

        assertEquals(2, album.tracks.size)
        assertEquals("tracks keep the order they were passed in", loud.loudnessLufs, stateOf(fixtures.monoFull).loudnessLufs, 0.0)
        assertEquals(quiet.loudnessLufs, stateOf(fixtures.monoHalf).loudnessLufs, 0.0)
        assertEquals(
            "the album carries the sum of the frames it measured",
            loud.sampleCount + quiet.sampleCount,
            album.sampleCount,
        )
        assertEquals("the peak is the loudest track's peak", loud.peak, album.peak, 0.0)

        // Both tracks sit above the gate, so the album number is the duration-weighted mean of their
        // energies: the 5 s track at -3.00 and the 3 s track at -9.02 give -4.43 LUFS before gating
        // (measured -4.389).
        val expected = 10.0 * log10(
            (5.0 * 10.0.pow(loud.loudnessLufs / 10.0) + 3.0 * 10.0.pow(quiet.loudnessLufs / 10.0)) / 8.0,
        )
        assertEquals(expected, album.loudnessLufs, 0.1)
        assertTrue(
            "an album that measured only its first track would read ${loud.loudnessLufs}",
            album.loudnessLufs < loud.loudnessLufs,
        )
    }

    @Test
    fun `an empty album list fails instead of inventing a programme`() = runBlocking {
        val states = scanner.analyzeAlbum(emptyList()).toList()

        val failed = states.filterIsInstance<AlbumReplayGainCalculateState.Failed>().single()
        assertEquals(ReplayGainError.ZeroSampleCount, failed.error)
        assertNull(failed.uriString)
    }

    @Test
    fun `progress runs from zero to one and the result names the codec`() = runBlocking {
        val states = scanner.analyze(fixtures.monoFull.toString()).toList()

        val progress = states.filterIsInstance<ReplayGainCalculateState.Progress>().map { it.percent }
        assertTrue("progress must be reported", progress.size >= 5)
        assertEquals(0f, progress.first(), 0f)
        assertEquals(1f, progress.last(), 0f)
        assertEquals("progress must be monotonic", progress.sorted(), progress)
        progress.forEach { assertTrue("progress $it out of range", it in 0f..1f) }

        // The success state is last: a half-measured track is never reported as a result.
        val success = states.last()
        assertTrue("the last state must be the result: $success", success is ReplayGainCalculateState.Success)
        // ffmpeg names the codec where Android named a MIME type; no caller branches on it, but it is
        // what the EditMetadata screen will show once it is ported.
        assertEquals("pcm_f32le", (success as ReplayGainCalculateState.Success).mimeType)
    }

    @Test
    fun `cancelling an analysis propagates instead of becoming a failure`() = runBlocking {
        val firstProgress = CompletableDeferred<Unit>()
        val collected = mutableListOf<ReplayGainCalculateState>()

        coroutineScope {
            val job = launch {
                scanner.analyze(fixtures.longTrack.toString()).collect { state ->
                    collected += state
                    // The second state means ffmpeg is running and PCM is flowing; cancelling from the
                    // very first Progress(0f) would only cancel before the process even started.
                    if (collected.size == 2) firstProgress.complete(Unit)
                }
            }
            firstProgress.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }

        // What the batch engine depends on: a cancelled analysis throws, so `BatchTaskRunner` can tell
        // "the user cancelled" from "this song failed". A Failed state here would paint the row red.
        assertTrue("expected progress before the cancel: $collected", collected.size >= 2)
        assertTrue(
            "cancellation must not be reported as a failure: $collected",
            collected.none { it is ReplayGainCalculateState.Failed },
        )
        // Note: the flow also tries to emit `Cancelled` on its way out, but that value cannot reach a
        // collector whose job was just cancelled - `flowOn` has already turned emit into a channel
        // send. The state is ported for API parity (a shared collector outliving the flow would see
        // it); the contract this test pins is the exception, which is what callers act on.
    }

    @Test
    fun `the tag text is formatted for every player's parser`() {
        // Track/Album Gain, the reference it was measured against, and the peak. All of it is
        // "%.2f"/"%.6f" with Locale.US so a comma decimal separator can never reach a tag.
        assertEquals("-15.00 dB", scanner.formatGain(-3.0))
        assertEquals("-5.00 dB", scanner.formatGain(-13.0))
        assertEquals("0.00 dB", scanner.formatGain(-18.0))
        assertEquals("5.00 dB", scanner.formatGain(-23.0))
        assertEquals("-0.50 dB", scanner.formatGain(-23.0, -23.5))
        // Silence, clamped at the gate rather than rendered as an infinity.
        assertEquals("52.00 dB", scanner.formatGain(Double.NEGATIVE_INFINITY))
        assertEquals("52.00 dB", scanner.formatGain(ReplayGainScanner.SILENCE_LOUDNESS_LUFS))

        assertEquals("-18 LUFS", scanner.formatReferenceLoudness(-18.0))
        assertEquals("-23 LUFS", scanner.formatReferenceLoudness(-23.0))
        assertEquals("-14.50 LUFS", scanner.formatReferenceLoudness(-14.5))

        assertEquals("0.999994", scanner.formatPeak(0.9999936))
        assertEquals("1.000000", scanner.formatPeak(1.0))
        assertEquals("1.200000", scanner.formatPeak(1.2))
        assertEquals("0.000000", scanner.formatPeak(0.0))
        assertEquals("a negative peak is not a thing", "0.000000", scanner.formatPeak(-0.5))
    }

    // -------------------------------------------------------------------------------- helpers

    private suspend fun stateOf(file: Path): ReplayGainAnalysis {
        val states = scanner.analyze(file.toString(), ReplayGainPeakMode.SAMPLE_PEAK).toList()
        val failed = states.filterIsInstance<ReplayGainCalculateState.Failed>()
        assertTrue("$file failed to measure: $failed", failed.isEmpty())
        return states.filterIsInstance<ReplayGainCalculateState.Success>().single().analysis
    }

    private suspend fun failureOf(file: Path): ReplayGainCalculateState.Failed {
        val states = scanner.analyze(file.toString(), ReplayGainPeakMode.SAMPLE_PEAK).toList()
        val successes = states.filterIsInstance<ReplayGainCalculateState.Success>()
        assertTrue("$file was expected to fail, but measured $successes", successes.isEmpty())
        return states.filterIsInstance<ReplayGainCalculateState.Failed>().single()
    }

    private class Fixtures(val directory: Path) {
        /** 5 s of a full-scale 1 kHz sine, one channel. */
        val monoFull: Path = wav("mono-full.wav", AudioFixtures.sine(5.0), channels = 1)

        /** The same signal in two identical channels: exactly +3.0103 LU when measured correctly. */
        val dualMonoFull: Path = wav("dual-mono-full.wav", AudioFixtures.sine(5.0, channels = 2), channels = 2)

        /** Half the amplitude: -6.0206 LU, and short enough to weight the album mean differently. */
        val monoHalf: Path = wav("mono-half.wav", AudioFixtures.sine(3.0, amplitude = 0.5), channels = 1)

        /** -23 dBFS per channel in stereo, the EBU 3341 style calibration fixture. */
        val stereoMinus23: Path = wav(
            "stereo-minus23.wav",
            AudioFixtures.sine(5.0, amplitude = AudioFixtures.rmsAmplitude(-23.0) * sqrt(2.0), channels = 2),
            channels = 2,
        )

        val silence: Path = wav("silence.wav", AudioFixtures.silence(2.0), channels = 1)

        /** A valid header with no frames behind it. */
        val noFrames: Path = wav("no-frames.wav", DoubleArray(0), channels = 1)

        /** 16-bit PCM, to prove the format byte reaches the JNI call as float. */
        val sixteenBit: Path = wav(
            "stereo-s16.wav",
            AudioFixtures.sine(2.0, amplitude = 0.5, channels = 2),
            channels = 2,
            bitsPerSample = 16,
        )

        /** A path outside the ASCII range. */
        val cjk: Path = wav(Path.of("花心 测试", "花心.wav"), AudioFixtures.sine(1.0, amplitude = 0.25), channels = 1)

        /** No audio stream at all. */
        val picture: Path = directory.resolve("cover.png").also { path ->
            AudioFixtures.runFfmpeg(
                "-nostdin", "-hide_banner", "-y",
                "-f", "lavfi", "-i", "color=c=red:s=16x16:d=1",
                "-frames:v", "1",
                path.toString(),
            )
        }

        /** Bytes no demuxer can make sense of. */
        val garbage: Path = directory.resolve("garbage.mp3").also { path ->
            Files.write(path, ByteArray(300_000) { ((it * 31) % 251).toByte() })
        }

        /** Ten minutes of audio, so a cancellation can land in the middle of it. */
        val longTrack: Path = directory.resolve("long.flac").also { path ->
            val run = AudioFixtures.runFfmpeg(
                "-nostdin", "-hide_banner", "-y",
                "-stream_loop", "119",
                "-i", monoFull.toString(),
                "-c:a", "flac",
                path.toString(),
            )
            assertEquals("fixture generation failed: ${run.stderr}", 0, run.exitCode)
            assertTrue("the long fixture has to be long: ${Files.size(path)} bytes", Files.size(path) > 1_000_000)
        }

        /** Codec variants of the same five seconds, for the ffmpeg cross-check. */
        val all: List<Pair<String, Path>> = buildList {
            add("mono-f32.wav" to monoFull)
            add("dual-mono.wav" to dualMonoFull)
            add("mono-half.wav" to monoHalf)
            add("stereo-minus23.wav" to stereoMinus23)
            add("s16-stereo.wav" to sixteenBit)
            add("cjk.wav" to cjk)
            for ((name, codecArgs) in listOf(
                "sine.flac" to arrayOf("-c:a", "flac"),
                "sine.mp3" to arrayOf("-c:a", "libmp3lame", "-b:a", "192k"),
                "sine.ogg" to arrayOf("-c:a", "libvorbis", "-q:a", "5"),
            )) {
                val target = directory.resolve(name)
                AudioFixtures.transcode(monoFull, target, *codecArgs)
                add(name to target)
            }
        }

        private fun wav(relative: String, samples: DoubleArray, channels: Int, bitsPerSample: Int = 32): Path =
            wav(Path.of(relative), samples, channels, bitsPerSample)

        private fun wav(relative: Path, samples: DoubleArray, channels: Int, bitsPerSample: Int = 32): Path {
            val target = directory.resolve(relative)
            AudioFixtures.writeWav(target, samples, channels = channels, bitsPerSample = bitsPerSample)
            return target
        }
    }

    private companion object {
        private lateinit var fixtures: Fixtures

        @BeforeClass
        @JvmStatic
        fun createFixtures() {
            AudioFixtures.requireFfmpeg()
            fixtures = Fixtures(Files.createTempDirectory("lyrico-replay-gain"))
        }

        @AfterClass
        @JvmStatic
        fun removeFixtures() {
            fixtures.directory.toFile().deleteRecursively()
        }
    }
}
