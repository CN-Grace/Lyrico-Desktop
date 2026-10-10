package com.lonx.lyrico.platform

/**
 * Layout of the PCM stream `ffmpeg` was asked to produce (`-c:a pcm_f32le`).
 *
 * @property channels channel count of the source stream — ffmpeg is deliberately *not* told to
 *   downmix, because forcing `-ac 2` on a mono file changes what BS.1770 measures (duplicating a
 *   mono channel into two raises the reported loudness by 3.01 LU).
 * @property sampleRate sample rate of the source stream, likewise not forced.
 * @property bitsPerSample bits per sample of one channel; 32 for the float PCM used here.
 */
data class PcmFormat(
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
) {
    /** Bytes of one frame: every channel of one sample instant. */
    val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)
}

/** A RIFF/WAVE header that cannot be the 32-bit float PCM ffmpeg was asked for. */
class RiffWaveFormatException(message: String) : Exception(message)

/** Outcome of one [RiffWaveHeaderParser.scan]. */
sealed interface RiffWaveHeaderScan {

    /** The header is not complete yet; call [RiffWaveHeaderParser.scan] again with more bytes. */
    data object NeedMoreBytes : RiffWaveHeaderScan

    /**
     * The `data` chunk was found at [payloadOffset]: everything from that index on is PCM.
     *
     * [format] is the last `fmt ` chunk seen, which is the one that describes [payloadOffset]'s
     * payload.
     */
    data class Ready(val payloadOffset: Int, val format: PcmFormat) : RiffWaveHeaderScan
}

/**
 * Reads the RIFF/WAVE header that ffmpeg writes in front of the PCM payload when asked for
 * `-f wav -`.
 *
 * Android obtained the channel count and sample rate from `MediaFormat` before touching a single
 * sample — `ebur128_init` needs both up front. Piping PCM out of ffmpeg gives the same information,
 * but it arrives as a streaming WAV header, so it has to be parsed incrementally: the header can be
 * split across reads (the first read may end in the middle of the `fmt ` chunk), and its length is
 * not fixed — ffmpeg 63 writes two extra chunks (`LIST` holding `ISFT`, plus a zero-length `JUNK`)
 * before `data`.
 *
 * Three things the RIFF format insists on and a naive parser gets wrong:
 *
 * - **Chunk sizes are unsigned 32-bit and chunks are word-aligned.** An odd-sized chunk is followed
 *   by a pad byte that belongs to no chunk, so the walk advances by `8 + size + (size and 1)`. The
 *   `LIST` chunk is exactly the one that goes odd in practice.
 * - **`data`'s size field must never be trusted.** ffmpeg writes `0xFFFFFFFF` when the target is a
 *   pipe, because it cannot know the length up front — as a signed int that is `-1`, so the sanity
 *   check applied to every other chunk is skipped for this one. The payload length comes from the
 *   stream itself (read to EOF), not from this field.
 * - **The format is only known once `data` is found.** A `fmt ` chunk on its own does not mean the
 *   payload follows immediately; only [RiffWaveHeaderScan.Ready] reports a valid [PcmFormat].
 *
 * The parser holds no copy of the stream: the caller re-scans the bytes it has accumulated, so a
 * header split over three reads costs three scans of at most [maxHeaderBytes] bytes.
 */
class RiffWaveHeaderParser(
    private val maxHeaderBytes: Int = DEFAULT_MAX_HEADER_BYTES,
) {

    private var ready: RiffWaveHeaderScan.Ready? = null

    /**
     * Scans [length] bytes of [buffer] (from index 0) looking for the payload.
     *
     * Calling this again after it returned [RiffWaveHeaderScan.Ready] keeps returning the same offset,
     * so a caller that appends payload bytes to [buffer] cannot mistake them for another header.
     *
     * @throws RiffWaveFormatException when the bytes cannot be a RIFF/WAVE stream carrying 32-bit
     *   float PCM, or when no `data` chunk shows up within [maxHeaderBytes].
     */
    fun scan(buffer: ByteArray, length: Int): RiffWaveHeaderScan {
        ready?.let { return it }

        // "RIFF" <u32 size> "WAVE" — the size field is ignored on purpose (see class KDoc).
        if (length >= 4 && !buffer.startsWith(RIFF, 0)) {
            throw RiffWaveFormatException("Not a RIFF stream: ${buffer.ascii(0, 4)}")
        }
        if (length >= 12 && !buffer.startsWith(WAVE, 8)) {
            throw RiffWaveFormatException("RIFF stream is not WAVE: ${buffer.ascii(8, 4)}")
        }
        if (length < 12) return RiffWaveHeaderScan.NeedMoreBytes

        var format: PcmFormat? = null
        var offset = 12
        while (true) {
            if (offset + CHUNK_HEADER_BYTES > length) {
                if (offset > maxHeaderBytes) throw noDataChunk()
                return RiffWaveHeaderScan.NeedMoreBytes
            }
            val chunkId = buffer.ascii(offset, 4)
            val chunkSize = buffer.leInt(offset + 4)

            // `data` comes first, and its size field is deliberately not validated: ffmpeg writes
            // `0xFFFFFFFF` there when the output is a pipe, because it cannot know the length in
            // advance. Read as a signed int that is -1, so the guard below would reject exactly the
            // header ffmpeg always produces. The payload ends at EOF, not at this number.
            if (chunkId == DATA) {
                val payloadOffset = offset + CHUNK_HEADER_BYTES
                val found = RiffWaveHeaderScan.Ready(
                    payloadOffset,
                    format ?: throw RiffWaveFormatException("data chunk before any fmt chunk"),
                )
                ready = found
                return found
            }

            if (chunkSize < 0) {
                throw RiffWaveFormatException("Chunk '$chunkId' declares an implausible size $chunkSize")
            }

            if (chunkId == FMT) {
                // Only the first 16 bytes are fixed; ffmpeg 63 writes an 18-byte `fmt ` with
                // cbSize=0 for float PCM, so `bitsPerSample` at +14 is always in range.
                if (chunkSize < 16) throw RiffWaveFormatException("fmt chunk is $chunkSize bytes, expected >= 16")
                val body = offset + CHUNK_HEADER_BYTES
                if (body + 16 > length) return RiffWaveHeaderScan.NeedMoreBytes
                format = readPcmFormat(buffer, body)
            }

            offset += CHUNK_HEADER_BYTES + chunkSize + (chunkSize and 1)
            if (offset > maxHeaderBytes) throw noDataChunk()
        }
    }

    private fun readPcmFormat(buffer: ByteArray, at: Int): PcmFormat {
        val audioFormat = buffer.leShort(at)
        val channels = buffer.leShort(at + 2)
        val sampleRate = buffer.leInt(at + 4)
        val bitsPerSample = buffer.leShort(at + 14)

        // 0x0003 is WAVE_FORMAT_IEEE_FLOAT; 0xFFFE is WAVE_FORMAT_EXTENSIBLE, which wraps the same
        // format tag in a GUID. Anything else means the command line did not do what it was told, so
        // this is reported as a format error rather than parsed as if it were float PCM.
        if (audioFormat != IEEE_FLOAT && audioFormat != EXTENSIBLE) {
            throw RiffWaveFormatException(
                "Expected float PCM (format 0x0003/0xFFFE), got format 0x${audioFormat.toString(16).padStart(4, '0')}"
            )
        }
        if (bitsPerSample != 32) {
            throw RiffWaveFormatException("Expected 32-bit samples, got $bitsPerSample")
        }
        if (channels < 1) throw RiffWaveFormatException("Stream declares $channels channels")
        if (sampleRate < 1) throw RiffWaveFormatException("Stream declares sample rate $sampleRate")
        return PcmFormat(channels = channels, sampleRate = sampleRate, bitsPerSample = bitsPerSample)
    }

    private fun noDataChunk(): RiffWaveFormatException =
        RiffWaveFormatException("No data chunk within the first $maxHeaderBytes bytes")

    private fun ByteArray.ascii(at: Int, size: Int): String = String(this, at, size, Charsets.US_ASCII)

    private fun ByteArray.startsWith(prefix: String, at: Int): Boolean {
        if (at + prefix.length > size) return false
        for (i in prefix.indices) {
            if (this[at + i].toInt() != prefix[i].code) return false
        }
        return true
    }

    private fun ByteArray.leShort(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.leInt(at: Int): Int =
        leShort(at) or (leShort(at + 2) shl 16)

    companion object {
        /**
         * Ceiling for the header. Real ffmpeg output is ~80 bytes; the limit only exists so that a
         * stream that never contains a `data` chunk fails instead of growing the caller's buffer.
         */
        const val DEFAULT_MAX_HEADER_BYTES: Int = 64 * 1024

        private const val CHUNK_HEADER_BYTES = 8
        private const val FMT = "fmt "
        private const val DATA = "data"
        private const val RIFF = "RIFF"
        private const val WAVE = "WAVE"
        private const val IEEE_FLOAT = 0x0003
        private const val EXTENSIBLE = 0xFFFE
    }
}
