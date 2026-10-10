package com.lonx.lyrico.platform

import com.lonx.lyrico.support.AudioFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * C6d 的第一层验收：**解码器真的拿到了音频样本。**
 *
 * [FfmpegAudioDecoder] 是这条链路上唯一说「子进程」的那一环，所以这里的每条断言都落在字节上：
 * 帧数按算术推导、浮点样本与手写 WAV 的原始值逐位比较、进度单调、CJK 路径不被 argv 编码弄坏、
 * 每一种失败都归到它自己的 [FfmpegFailure]。fixture 由 [AudioFixtures] 手写（已知样本值），
 * ffmpeg 只负责它不可替代的部分：编解码、以及「没有音轨」这种输入。
 *
 * 有意不覆盖：编解码器内部实现、`ffmpeg.exe` 自身的行为。这里验证的是「我们怎么用它」。
 */
class FfmpegAudioDecoderTest {

    @Test
    fun `the ffmpeg command line is what this decoder promises`() {
        val executable = Path.of("C:", "tools", "ffmpeg.exe")
        val input = Path.of("C:", "music", "song.flac")

        assertEquals(
            listOf(
                executable.toString(),
                "-nostdin",
                "-hide_banner",
                "-i",
                input.toString(),
                "-map",
                "0:a:0",
                "-c:a",
                "pcm_f32le",
                "-f",
                "wav",
                "-",
            ),
            FfmpegAudioDecoder.buildCommand(executable, input),
        )
    }

    @Test
    fun `a mono float wav decodes frame for frame and sample for sample`() = runBlocking {
        val file = fixture("mono-f32.wav") { path ->
            AudioFixtures.writeWav(path, AudioFixtures.sine(5.0), channels = 1)
        }

        val decoded = decodeFully(file)

        assertEquals(1, decoded.format.channels)
        assertEquals(AudioFixtures.SAMPLE_RATE, decoded.format.sampleRate)
        assertEquals(32, decoded.format.bitsPerSample)
        assertEquals(4, decoded.format.bytesPerFrame)
        assertEquals("pcm_f32le", decoded.info.codecName)
        assertEquals(5.0, decoded.info.durationSeconds ?: -1.0, 0.01)
        assertEquals(220_500L, decoded.info.framesDecoded)
        assertEquals(220_500 * 4, decoded.pcm.size)

        // The fixture's own floats went in; ffmpeg copies pcm_f32le through untouched, so every
        // sample has to come back bit-identical. This is what proves the frame carry-over in
        // PcmFrameAssembler never drops or shifts a sample: 64 KiB reads of float PCM split frames
        // every time (65536 % 12 = 4), and the read() calls are far smaller than 64 KiB here.
        assertSamplesEqual(AudioFixtures.sine(5.0), decoded)
    }

    @Test
    fun `a stereo wav keeps its channel order and frame count`() = runBlocking {
        val samples = AudioFixtures.sine(5.0, channels = 2)
        val file = fixture("stereo-f32.wav") { path ->
            AudioFixtures.writeWav(path, samples, channels = 2)
        }

        val decoded = decodeFully(file)

        assertEquals(2, decoded.format.channels)
        assertEquals(8, decoded.format.bytesPerFrame)
        assertEquals(220_500L, decoded.info.framesDecoded)
        assertEquals(220_500 * 8, decoded.pcm.size)
        assertSamplesEqual(samples, decoded)
    }

    @Test
    fun `a path outside the ASCII range decodes like any other`() = runBlocking {
        // ProcessBuilder hands the argv to CreateProcessW and Kotlin builds it from the Path: if any
        // hop ever went through the platform's ANSI encoding, this is the file that would fail.
        val samples = AudioFixtures.sine(1.0, amplitude = 0.25)
        val file = fixture(Path.of("花心 测试", "花心.wav")) { path ->
            AudioFixtures.writeWav(path, samples, channels = 1)
        }

        val decoded = decodeFully(file)

        assertEquals(44_100L, decoded.info.framesDecoded)
        assertSamplesEqual(samples, decoded)
    }

    @Test
    fun `16-bit PCM arrives as full-scale floats`() = runBlocking {
        val samples = AudioFixtures.sine(2.0, amplitude = 0.5, channels = 2)
        val file = fixture("stereo-s16.wav") { path ->
            AudioFixtures.writeWav(path, samples, channels = 2, bitsPerSample = 16)
        }

        val decoded = decodeFully(file)

        assertEquals("pcm_s16le", decoded.info.codecName)
        assertEquals(88_200L, decoded.info.framesDecoded)
        assertEquals(88_200 * 8, decoded.pcm.size)

        // ffmpeg's s16 -> float conversion divides by 32768, and writeWav quantised with 32767, so
        // the round trip is within one quantisation step. A scale error of 32767 versus 32768 would
        // still fit in one step; a wrong endianness or a short/float mix-up could not.
        val floats = decoded.pcm.asFloatArray()
        assertEquals(samples.size, floats.size)
        for (index in samples.indices) {
            assertTrue(
                "sample $index: ${floats[index]} vs ${samples[index]}",
                abs(floats[index] - samples[index].toFloat()) <= 1.0f / 32767.0f,
            )
        }
    }

    @Test
    fun `progress rises towards one and never leaves the unit interval`() = runBlocking {
        val file = fixture("progress.wav") { path ->
            AudioFixtures.writeWav(path, AudioFixtures.sine(5.0), channels = 1)
        }

        val decoded = decodeFully(file)

        assertTrue("progress must be reported for a file with a duration", decoded.progress.size >= 5)
        decoded.progress.forEach { progress ->
            assertTrue("progress $progress out of range", progress > 0f && progress <= 1f)
        }
        assertEquals(
            "progress must be monotonic",
            decoded.progress.sorted(),
            decoded.progress,
        )
        assertTrue("progress should approach the end: ${decoded.progress.last()}", decoded.progress.last() >= 0.9f)
    }

    @Test
    fun `lossy and lossless inputs come back with their codec name and duration`() = runBlocking {
        val source = fixture("source.wav") { path ->
            AudioFixtures.writeWav(path, AudioFixtures.sine(5.0), channels = 1)
        }
        val expected = listOf(
            Triple("sine.flac", arrayOf("-c:a", "flac"), "flac"),
            Triple("sine.mp3", arrayOf("-c:a", "libmp3lame", "-b:a", "192k"), "mp3"),
            Triple("sine.ogg", arrayOf("-c:a", "libvorbis", "-q:a", "5"), "vorbis"),
        )

        for ((name, codecArgs, expectedCodec) in expected) {
            val target = source.resolveSibling(name)
            AudioFixtures.transcode(source, target, *codecArgs)

            val decoded = decodeFully(target)

            assertEquals(name, expectedCodec, decoded.info.codecName)
            assertEquals(name, 5.0, decoded.info.durationSeconds ?: -1.0, 0.05)
            // A lossy codec pads the tail with its own frame size; what matters is that the whole
            // five seconds arrived rather than a truncated prefix.
            assertTrue("$name: ${decoded.info.framesDecoded}", decoded.info.framesDecoded >= 220_500L)
        }
    }

    @Test
    fun `a file that is not there is reported as missing before ffmpeg runs`() = runBlocking {
        val file = directory.resolve("no-such-file.wav")

        val missing = assertFails<FfmpegDecodeException> { decodeFully(file) }
        assertEquals(FfmpegFailure.InputMissing, missing.failure)

        val directoryInput = assertFails<FfmpegDecodeException> { decodeFully(directory) }
        assertEquals(FfmpegFailure.InputMissing, directoryInput.failure)
        assertTrue(directoryInput.message.orEmpty().contains(directory.toString()))
    }

    @Test
    fun `a video-only file is reported as having no audio stream`() = runBlocking {
        val file = directory.resolve("cover.png")
        val generated = AudioFixtures.runFfmpeg(
            "-nostdin", "-hide_banner", "-y",
            "-f", "lavfi", "-i", "color=c=red:s=16x16:d=1",
            "-frames:v", "1",
            file.toString(),
        )
        assertEquals("fixture generation failed: ${generated.stderr}", 0, generated.exitCode)

        val failure = assertFails<FfmpegDecodeException> { decodeFully(file) }
        assertEquals(FfmpegFailure.NoAudioStream, failure.failure)
        assertTrue(
            "the reported tail should be what ffmpeg said: ${failure.stderrTail}",
            failure.stderrTail.contains("matches no streams"),
        )
    }

    @Test
    fun `a file ffmpeg cannot read is a failure that keeps ffmpeg's own words`() = runBlocking {
        val garbage = directory.resolve("garbage.mp3")
        // Deterministic pseudo-random bytes: no mp3 header can be found in them.
        Files.write(garbage, ByteArray(300_000) { ((it * 31) % 251).toByte() })
        val empty = directory.resolve("empty.mp3")
        Files.write(empty, ByteArray(0))

        for (file in listOf(garbage, empty)) {
            val failure = assertFails<FfmpegDecodeException> { decodeFully(file) }
            assertEquals(file.fileName.toString(), FfmpegFailure.DecodeFailed, failure.failure)
            // The tail is what a user sees in the task row and the log; without it the message would
            // be an opaque "exit code -1094995529".
            assertTrue("${file.fileName}: empty tail", failure.stderrTail.isNotBlank())
            assertTrue(
                "${file.fileName}: tail should name the file: ${failure.stderrTail}",
                failure.stderrTail.contains(file.fileName.toString()),
            )
        }
    }

    @Test
    fun `a truncated wav reports the audio that is really there`() = runBlocking {
        val source = fixture("full.wav") { path ->
            AudioFixtures.writeWav(path, AudioFixtures.sine(5.0), channels = 1)
        }
        // A header that still claims five seconds, with only a tenth of the payload behind it.
        val truncated = directory.resolve("truncated.wav")
        val bytes = Files.readAllBytes(source)
        Files.write(truncated, bytes.copyOf(44 + (bytes.size - 44) / 10))

        // Measured behaviour of the pinned ffmpeg build: a truncated payload is *not* an error, it
        // exits 0 with the frames it could read. The header's size field is never trusted, so the
        // decoder reports 0.5 s of audio rather than 5 s of which 4.5 s are absent.
        val decoded = decodeFully(truncated)

        assertEquals(22_050L, decoded.info.framesDecoded)
        assertEquals(22_050 * 4, decoded.pcm.size)
    }

    @Test
    fun `a decode without a sidecar fails with the paths that were probed`() = runBlocking {
        val file = fixture("mono.wav") { path ->
            AudioFixtures.writeWav(path, AudioFixtures.sine(1.0), channels = 1)
        }
        val nowhere = directory.resolve("not-installed")
        val decoder = FfmpegAudioDecoder(FfmpegSidecar(listOf(nowhere)))

        val failure = assertFails<FfmpegUnavailableException> { decodeFully(file, decoder) }

        val message = failure.message.orEmpty()
        assertTrue("should name the directory it looked in: $message", message.contains(nowhere.toString()))
        assertTrue("should say how to install it: $message", message.contains("fetch-ffmpeg.ps1"))
    }

    @Test
    fun `cancelling a decode kills the child process`() = runBlocking {
        // Long enough that ffmpeg cannot finish before the cancellation lands: the pipe holds 64 KiB,
        // so the child blocks writing and stays alive until someone reads.
        val source = AudioFixtures.sine(1.0, amplitude = 0.5)
        val wav = fixture("long-source.wav") { path ->
            AudioFixtures.writeWav(path, source, channels = 1)
        }
        val long = directory.resolve("long.flac")
        AudioFixtures.runFfmpeg(
            "-nostdin", "-hide_banner", "-y",
            "-stream_loop", "59",
            "-i", wav.toString(),
            "-c:a", "flac",
            long.toString(),
        )

        val firstChunk = CompletableDeferred<Unit>()
        var framesBeforeCancel = 0L
        coroutineScope {
            val job = launch {
                FfmpegAudioDecoder().decode(
                    long,
                    onFormat = {},
                    onPcm = { _, frames ->
                        framesBeforeCancel += frames
                        firstChunk.complete(Unit)
                        // Stays suspended: ffmpeg is now blocked on a full pipe, which is exactly the
                        // state a user's "cancel batch" hits.
                        awaitCancellation()
                    },
                )
            }

            firstChunk.await()
            assertTrue("the child must be running for this test to mean anything", ffmpegChildren().isNotEmpty())
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }

        assertTrue("the decode really was interrupted: $framesBeforeCancel", framesBeforeCancel < source.size)

        // The finally block in decode() forcibly destroys the process; CreateProcess cleanup is not
        // instantaneous, so poll rather than sampling once.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var remaining = ffmpegChildren()
        while (remaining.isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50)
            remaining = ffmpegChildren()
        }
        assertTrue("ffmpeg still alive after cancellation: $remaining", remaining.isEmpty())
    }

    // -------------------------------------------------------------------------------- helpers

    private class Decoded(
        val info: FfmpegDecodeInfo,
        val format: PcmFormat,
        val pcm: ByteArray,
        val progress: List<Float>,
    )

    private suspend fun decodeFully(
        file: Path,
        decoder: FfmpegAudioDecoder = FfmpegAudioDecoder(),
    ): Decoded {
        val pcm = ByteArrayOutputStream()
        val progress = mutableListOf<Float>()
        var format: PcmFormat? = null
        val info = decoder.decode(
            file,
            onFormat = { format = it },
            onPcm = { buffer, frames ->
                // Exactly what the JNI bridge expects as well: whole frames at index 0 of a direct
                // buffer, read absolutely so the decoder's buffer state is not disturbed.
                val chunk = ByteArray(frames * (format?.bytesPerFrame ?: error("no format yet")))
                buffer.get(0, chunk)
                pcm.write(chunk)
            },
            onProgress = { progress += it },
        )
        return Decoded(
            info,
            format ?: error("onFormat must run before any PCM arrives"),
            pcm.toByteArray(),
            progress,
        )
    }

    private fun ByteArray.asFloatArray(): FloatArray {
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size / 4) { buffer.getFloat() }
    }

    private fun assertSamplesEqual(expected: DoubleArray, decoded: Decoded) {
        val actual = decoded.pcm.asFloatArray()
        assertEquals("sample count", expected.size, actual.size)
        var worst = 0
        var worstIndex = -1
        var maxDelta = 0.0f
        for (index in expected.indices) {
            val delta = abs(actual[index] - expected[index].toFloat())
            if (delta > maxDelta) {
                maxDelta = delta
                worstIndex = index
            }
            if (delta != 0f) worst++
        }
        assertEquals("samples differ at $worstIndex (max delta $maxDelta)", 0, worst)
    }

    private suspend inline fun <reified T : Throwable> assertFails(block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("expected ${T::class.simpleName} but got $error", error)
        }
        throw AssertionError("expected ${T::class.simpleName} but nothing was thrown")
    }

    /**
     * Live ffmpeg children of *this* JVM.
     *
     * `ProcessHandle.allProcesses()` was tried first and is useless here: on this machine
     * `info().commandLine()` comes back empty for processes the JVM did not spawn, so the file path
     * filter matched nothing. Our own children are both queryable and the exact set that matters —
     * if one survives a cancelled decode it is this decoder that leaked it.
     */
    private fun ffmpegChildren(): List<String> = ProcessHandle.current().children()
        .filter { it.isAlive }
        .map { handle ->
            "pid=${handle.pid()} command=${handle.info().command().orElse("?")} " +
                "line=${handle.info().commandLine().orElse("?")}"
        }
        .filter { it.contains("ffmpeg", ignoreCase = true) }
        .toList()

    private fun fixture(name: String, write: (Path) -> Unit): Path = fixture(Path.of(name), write)

    private fun fixture(relative: Path, write: (Path) -> Unit): Path {
        val target = directory.resolve(relative)
        write(target)
        return target
    }

    companion object {
        private lateinit var directory: Path

        @BeforeClass
        @JvmStatic
        fun createDirectory() {
            directory = Files.createTempDirectory("lyrico-ffmpeg-decoder")
        }

        @AfterClass
        @JvmStatic
        fun removeDirectory() {
            directory.toFile().deleteRecursively()
        }
    }
}
