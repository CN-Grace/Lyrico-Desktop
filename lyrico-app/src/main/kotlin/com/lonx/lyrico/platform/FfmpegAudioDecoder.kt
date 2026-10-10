package com.lonx.lyrico.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Which of the three ways an ffmpeg run can fail to produce PCM. */
enum class FfmpegFailure {

    /** The input file is missing or is not a regular file (checked before ffmpeg is even started). */
    InputMissing,

    /** ffmpeg started and read the file, but found no audio stream in it (`-map 0:a:0` matched nothing). */
    NoAudioStream,

    /** ffmpeg read an audio stream and gave up: corrupt data, a codec the build cannot decode, a kill. */
    DecodeFailed,
}

/** An ffmpeg run that did not deliver a complete PCM stream. */
class FfmpegDecodeException(
    val failure: FfmpegFailure,
    /** Last lines of ffmpeg's stderr, for the caller's log and error text. */
    val stderrTail: String,
    message: String,
) : Exception(message)

/**
 * What the decoder learned about one file, beyond the PCM itself.
 *
 * @property codecName the *input* codec as ffmpeg names it (`flac`, `mp3`, `vorbis`, …). `null` when
 *   ffmpeg's stream dump did not name one, which no normal file hits.
 * @property durationSeconds duration as ffmpeg read it. `null` when ffmpeg reports no duration
 *   (some piped/streamed inputs), in which case progress stays at 0 until the track ends.
 * @property framesDecoded frames handed to the [FfmpegAudioDecoder.decode] callback; one frame is one
 *   sample instant across all channels.
 */
data class FfmpegDecodeInfo(
    val codecName: String?,
    val durationSeconds: Double?,
    val framesDecoded: Long,
)

/**
 * Decodes an audio file by running the bundled ffmpeg and piping `pcm_f32le` out of it.
 *
 * Android decoded with `MediaExtractor` + `MediaCodec`, where the format arrived through
 * `INFO_OUTPUT_FORMAT_CHANGED` and each output buffer was already float or 16-bit PCM. Here the
 * decoder is a child process, so the equivalent information comes from the WAV header ffmpeg writes
 * before the payload ([RiffWaveHeaderParser]) and from its stderr banner (codec name, duration).
 *
 * The command line is a contract, not a convenience:
 *
 * ```
 * ffmpeg.exe -nostdin -hide_banner -i <file> -map 0:a:0 -c:a pcm_f32le -f wav -
 * ```
 *
 * - `-map 0:a:0` picks the first *audio* stream. For a FLAC with an embedded cover, a plain
 *   `-f wav -` would try to mux the attached picture (a video stream) into WAV and fail. This
 *   argument is why the command has a map at all.
 * - `-c:a pcm_f32le` is what `ebur128.dll` consumes without a conversion step.
 * - **No `-ac`/`-ar`.** Forcing the channel layout or the rate would change the measurement:
 *   duplicating a mono file into two channels raises BS.1770 loudness by 3.01 LU, and resampling
 *   moves energy across the K-weighting filter. The source layout is preserved and read back from
 *   the header.
 * - **No `-v`/`-loglevel`.** `Duration:` is printed at INFO level; silencing it would silently
 *   disable progress reporting. [FfmpegStderrSummary] depends on the default level.
 * - `-nostdin` because the process has no console to answer an interactive prompt; its stdin is
 *   closed as well.
 *
 * The PCM callback receives one **reused direct** [ByteBuffer] per chunk, with the samples always at
 * index 0 and the frame count as the second argument. Two details are load-bearing and are why the
 * buffer is not a heap buffer and is not sliced:
 *
 * - `ebur128.cpp` reads samples with `GetDirectBufferAddress`, which returns `null` for a
 *   non-direct buffer; `processDirectNative` then returns early and **silently drops the chunk**
 *   (`LibEbuR128.sampleCount` stays 0 and the track measures as silence).
 * - that address is the buffer's *base*, ignoring position and limit, so a windowed view would read
 *   the wrong bytes. Samples are therefore written from index 0 every time.
 *
 * Chunks are **frame-aligned**: a read can end in the middle of a frame (a 64 KiB read of stereo
 * float PCM splits a frame every time, because 65536 is not a multiple of 6), and a partial frame
 * must be carried into the next chunk rather than passed on as if complete.
 */
class FfmpegAudioDecoder(
    private val sidecar: FfmpegSidecar = FfmpegSidecar(),
) {

    /**
     * Decodes [file], reporting the stream layout once, then PCM in chunks.
     *
     * @param onFormat called exactly once, before the first PCM chunk, with the layout `ebur128` has
     *   to be initialized with.
     * @param onPcm called once per chunk with whole frames only.
     * @param onProgress intermediate progress in `0f..1f`, derived from decoded frames against the
     *   duration ffmpeg reported; not called when the duration is unknown (the caller owns the 0 and
     *   1 endpoints).
     * @throws FfmpegUnavailableException when the sidecar is not installed.
     * @throws FfmpegDecodeException when the file has no audio stream, cannot be decoded, or ffmpeg
     *   exits non-zero — including the case of a file that decodes part way and then fails, which is
     *   reported rather than written up as a partial measurement.
     */
    suspend fun decode(
        file: Path,
        onFormat: suspend (PcmFormat) -> Unit,
        onPcm: suspend (pcm: ByteBuffer, frameCount: Int) -> Unit,
        onProgress: suspend (Float) -> Unit = {},
    ): FfmpegDecodeInfo = coroutineScope {
        val executable = sidecar.locate()
        if (!Files.isRegularFile(file)) {
            throw FfmpegDecodeException(
                failure = FfmpegFailure.InputMissing,
                stderrTail = "",
                message = "Input is not a regular file: $file",
            )
        }

        val process = ProcessBuilder(buildCommand(executable, file))
            .redirectErrorStream(false)
            .start()
        // Nothing will ever be typed at ffmpeg; closing the pipe also makes "-nostdin" belt and braces.
        process.outputStream.close()

        val stderr = FfmpegStderrSummary()
        val drain = launch(Dispatchers.IO) {
            process.errorStream.bufferedReader().useLines { lines -> lines.forEach(stderr::consume) }
        }

        var exitCode = -1
        try {
            val read = readPcm(process.inputStream, stderr, onFormat, onPcm, onProgress)
            exitCode = process.waitFor()
            // ffmpeg has exited, so stderr is complete: no race on the last lines below.
            drain.join()

            // A null format means ffmpeg never wrote a header; a non-zero exit means it gave up part
            // way. Both are reported from ffmpeg's own stderr rather than as a parse error.
            if (read.format == null || exitCode != 0) throw stderr.toException(exitCode)

            FfmpegDecodeInfo(
                codecName = stderr.codecName,
                durationSeconds = stderr.durationSeconds,
                framesDecoded = read.framesDecoded,
            )
        } finally {
            process.destroyForcibly()
            process.waitFor(REAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            drain.cancel()
        }
    }

    private suspend fun readPcm(
        stdout: InputStream,
        stderr: FfmpegStderrSummary,
        onFormat: suspend (PcmFormat) -> Unit,
        onPcm: suspend (ByteBuffer, Int) -> Unit,
        onProgress: suspend (Float) -> Unit,
    ): PcmRead {
        val readBuffer = ByteArray(READ_BUFFER_BYTES)

        // Phase 1: the header. It is ~80 bytes in practice but may be split across reads, so bytes are
        // accumulated until the parser finds the data chunk. EOF here means ffmpeg never wrote one.
        val parser = RiffWaveHeaderParser()
        val header = ByteArray(RiffWaveHeaderParser.DEFAULT_MAX_HEADER_BYTES)
        var headerLength = 0
        var ready: RiffWaveHeaderScan.Ready? = null
        while (ready == null) {
            currentCoroutineContext().ensureActive()
            val read = stdout.read(readBuffer)
            if (read < 0) return PcmRead(format = null, framesDecoded = 0)
            if (read == 0) continue
            System.arraycopy(readBuffer, 0, header, headerLength, read)
            headerLength += read
            val scan = parser.scan(header, headerLength)
            if (scan is RiffWaveHeaderScan.Ready) ready = scan
        }

        // Phase 2: the payload. Whatever arrived in the same read as the header's end is still sitting
        // in `header`; it is fed first so no sample is lost.
        val format = ready.format
        val assembler = PcmFrameAssembler(format.bytesPerFrame)
        var frames = 0L
        var reportedProgress = 0f
        onFormat(format)

        suspend fun feed(source: ByteArray, from: Int, count: Int) {
            val frameCount = assembler.feed(source, from, count)
            if (frameCount <= 0) return
            frames += frameCount
            onPcm(assembler.buffer, frameCount)

            // The stderr banner and the header arrive on two threads, so the duration may not have
            // been parsed when the first samples show up. Keep asking until it is (or forever, for an
            // input ffmpeg reports no duration for — then progress simply stays at 0).
            val expectedFrames = expectedFramesOf(stderr.durationSeconds, format)
            if (expectedFrames > 0) {
                val progress = (frames.toDouble() / expectedFrames).coerceIn(0.0, 1.0).toFloat()
                if (progress - reportedProgress >= PROGRESS_THRESHOLD) {
                    onProgress(progress)
                    reportedProgress = progress
                }
            }
        }

        feed(header, ready.payloadOffset, headerLength - ready.payloadOffset)

        while (true) {
            currentCoroutineContext().ensureActive()
            val read = stdout.read(readBuffer)
            if (read < 0) break
            if (read == 0) continue
            feed(readBuffer, 0, read)
        }

        return PcmRead(format = format, framesDecoded = frames)
    }

    private fun expectedFramesOf(durationSeconds: Double?, format: PcmFormat): Long {
        val duration = durationSeconds ?: return 0L
        if (duration <= 0.0) return 0L
        return Math.round(duration * format.sampleRate)
    }

    private data class PcmRead(val format: PcmFormat?, val framesDecoded: Long)

    companion object {

        private const val READ_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_THRESHOLD = 0.01f
        private const val REAP_TIMEOUT_MS = 2_000L

        /** The exact command line, kept separate so a test can pin it argument for argument. */
        internal fun buildCommand(executable: Path, file: Path): List<String> = listOf(
            executable.toString(),
            "-nostdin",
            "-hide_banner",
            "-i",
            file.toString(),
            "-map",
            "0:a:0",
            "-c:a",
            "pcm_f32le",
            "-f",
            "wav",
            "-",
        )
    }
}

/**
 * Assembles frame-aligned chunks out of reads that may split a frame.
 *
 * Every chunk handed to `ebur128` has to contain whole frames: a frame is `channels * 4` bytes here,
 * so it is only a multiple of the 64 KiB read size for mono (4 bytes divides 65536) and never for
 * stereo (6) or three channels (12). Two cases, and the second one is where an off-by-one hides:
 *
 * - The pending bytes plus this read reach a frame boundary: complete frames go out, and the
 *   remainder is the *tail of this read*, which is at most `count - 1` bytes (it is
 *   `(pending + count) % bytesPerFrame`, and `pending < bytesPerFrame` forces that below `count`), so
 *   it is always a suffix of `source` and never reaches before `from`.
 * - They do not reach a boundary: nothing goes out, and this read is *appended* to the pending bytes,
 *   which is the only branch where the bytes to keep come from the run itself and not from `source`'s
 *   tail.
 *
 * The buffer is direct by necessity, not by preference: see [FfmpegAudioDecoder]'s class KDoc.
 */
internal class PcmFrameAssembler(
    val bytesPerFrame: Int,
    capacityBytes: Int = DEFAULT_CAPACITY_BYTES,
) {

    init {
        require(bytesPerFrame > 0) { "bytesPerFrame must be positive, was $bytesPerFrame" }
        require(capacityBytes >= bytesPerFrame) {
            "capacity $capacityBytes cannot hold a frame of $bytesPerFrame bytes"
        }
    }

    /** Direct buffer holding the current run of whole frames, samples at index 0. */
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(capacityBytes + bytesPerFrame)

    private val pending = ByteArray(bytesPerFrame)
    private var pendingBytes = 0

    /**
     * Consumes [count] bytes of [source] starting at [from].
     *
     * @return the number of whole frames now waiting in [buffer], 0 when the run is still incomplete.
     */
    fun feed(source: ByteArray, from: Int, count: Int): Int {
        if (count <= 0) return 0

        val total = pendingBytes + count
        val wholeBytes = total - (total % bytesPerFrame)

        buffer.clear()
        if (wholeBytes == 0) {
            // No frame completed: keep everything and wait for more bytes.
            System.arraycopy(source, from, pending, pendingBytes, count)
            pendingBytes = total
            return 0
        }

        if (pendingBytes > 0) buffer.put(pending, 0, pendingBytes)
        buffer.put(source, from, count)

        val remaining = total - wholeBytes
        if (remaining > 0) {
            System.arraycopy(source, from + count - remaining, pending, 0, remaining)
        }
        pendingBytes = remaining
        return wholeBytes / bytesPerFrame
    }

    /** Frames still held back waiting for the rest of their bytes. */
    val pendingByteCount: Int get() = pendingBytes

    companion object {
        const val DEFAULT_CAPACITY_BYTES: Int = 64 * 1024
    }
}

/**
 * Collects the two facts [FfmpegAudioDecoder] needs out of ffmpeg's stderr, plus the tail of the log
 * for error messages, while ffmpeg is still running.
 *
 * The drain is not optional: ffmpeg writes warnings and errors continuously, and an undrained pipe
 * fills up (64 KiB on Windows) and blocks the child process, which would look like a hang rather than
 * an error.
 */
internal class FfmpegStderrSummary(private val tailLimit: Int = 20) {

    /** Input codec name from the stream dump; the first `Audio:` line is the input's, the later one
     *  describes the PCM we asked for. */
    @Volatile
    var codecName: String? = null
        private set

    @Volatile
    var durationSeconds: Double? = null
        private set

    private val tail = ArrayDeque<String>()

    fun consume(line: String) {
        if (codecName == null) {
            codecName = parseCodecName(line)
        }
        if (durationSeconds == null) {
            durationSeconds = parseDurationSeconds(line)
        }
        synchronized(tail) {
            tail.addLast(line)
            while (tail.size > tailLimit) tail.removeFirst()
        }
    }

    /** Last [tailLimit] stderr lines, oldest first. */
    fun tailText(): String = synchronized(tail) { tail.joinToString("\n") }

    /** Classifies a non-zero exit, or a run that produced no PCM despite exiting cleanly. */
    fun toException(exitCode: Int): FfmpegDecodeException {
        val text = tailText()
        val noAudioStream = NO_AUDIO_STREAM_MARKERS.any { text.contains(it) }
        val failure = if (noAudioStream) FfmpegFailure.NoAudioStream else FfmpegFailure.DecodeFailed
        val reason = when {
            noAudioStream -> "ffmpeg found no audio stream (exit $exitCode)"
            exitCode != 0 -> "ffmpeg exited with $exitCode"
            else -> "ffmpeg produced no PCM stream"
        }
        return FfmpegDecodeException(failure, text, "$reason. stderr: $text")
    }

    private fun parseCodecName(line: String): String? {
        val marker = ": Audio: "
        if (!line.contains(marker)) return null
        return line.substringAfter(marker)
            .takeWhile { it.isLetterOrDigit() || it == '_' }
            .takeIf { it.isNotEmpty() }
    }

    private fun parseDurationSeconds(line: String): Double? {
        val match = DURATION.find(line) ?: return null
        val (hours, minutes, seconds) = match.destructured
        return hours.toDouble() * 3600.0 + minutes.toDouble() * 60.0 + seconds.toDouble()
    }

    private companion object {
        val DURATION = Regex("""Duration:\s+(\d+):(\d{2}):(\d{2}(?:\.\d+)?)""")

        /** ffmpeg's wording when `-map 0:a:0` matches nothing. */
        val NO_AUDIO_STREAM_MARKERS = listOf(
            "matches no streams",
            "does not contain any stream",
            "Output file does not contain any stream",
        )
    }
}
