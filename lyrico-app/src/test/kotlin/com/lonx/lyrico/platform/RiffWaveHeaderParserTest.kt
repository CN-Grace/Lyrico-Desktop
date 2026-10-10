package com.lonx.lyrico.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for the incremental RIFF walk that turns ffmpeg's `-f wav -` output into a
 * [PcmFormat].
 *
 * The parser is the only place where a wrong byte offset silently corrupts a measurement (samples
 * read from the wrong place, or the header bytes themselves measured as audio), so the header shapes
 * are built by hand here rather than taken from a real ffmpeg run: an odd-sized chunk with its pad
 * byte, a `fmt ` split across reads, an 18-byte `fmt `, and a `data` field that lies about its length
 * are all easy to construct and hard to produce on demand.
 */
class RiffWaveHeaderParserTest {

    @Test
    fun `parses the header ffmpeg writes for float pcm`() {
        val header = wave(
            fmtChunk(channels = 2, sampleRate = 44100, bitsPerSample = 32),
            chunk("LIST", "INFOISFT".toByteArray() + leInt(12) + "Lavf63.8.100".toByteArray()),
            dataChunk(declaredSize = 0xFFFFFFFF.toInt()),
        )

        val scan = RiffWaveHeaderParser().scan(header, header.size)

        val ready = scan as RiffWaveHeaderScan.Ready
        assertEquals(PcmFormat(channels = 2, sampleRate = 44100, bitsPerSample = 32), ready.format)
        assertEquals(2 * 4, ready.format.bytesPerFrame)
        // The payload starts right after the data chunk's 8-byte header, and the header we built has
        // nothing after it, so the offset has to be the whole array's length.
        assertEquals(header.size, ready.payloadOffset)
    }

    @Test
    fun `walks an odd sized chunk with its pad byte`() {
        // "JUNK" with a 3-byte body: RIFF requires a pad byte after it so the next chunk stays
        // word-aligned. Skipping that byte is what keeps `fmt ` readable.
        val junk = chunk("JUNK", byteArrayOf(1, 2, 3)) + byteArrayOf(0)
        val header = wave(
            junk,
            fmtChunk(channels = 1, sampleRate = 48000, bitsPerSample = 32),
            dataChunk(declaredSize = 100),
        )

        val ready = RiffWaveHeaderParser().scan(header, header.size) as RiffWaveHeaderScan.Ready

        assertEquals(48000, ready.format.sampleRate)
        assertEquals(1, ready.format.channels)
        assertEquals(header.size, ready.payloadOffset)
    }

    @Test
    fun `accepts an eighteen byte fmt chunk with extensible float`() {
        val header = wave(
            fmtChunk(
                channels = 6,
                sampleRate = 96000,
                bitsPerSample = 32,
                formatTag = 0xFFFE,
                extraBytes = byteArrayOf(0, 0),
            ),
            dataChunk(declaredSize = 64),
        )

        val ready = RiffWaveHeaderParser().scan(header, header.size) as RiffWaveHeaderScan.Ready

        assertEquals(PcmFormat(channels = 6, sampleRate = 96000, bitsPerSample = 32), ready.format)
        assertEquals(24, ready.format.bytesPerFrame)
    }

    @Test
    fun `reports nothing until the header is complete, then keeps reporting the same offset`() {
        val header = wave(
            fmtChunk(channels = 2, sampleRate = 44100, bitsPerSample = 32),
            chunk("LIST", "INFOISFT".toByteArray() + leInt(12) + "Lavf63.8.100".toByteArray()),
            dataChunk(declaredSize = 0xFFFFFFFF.toInt()),
        )
        val parser = RiffWaveHeaderParser()
        val expectedOffset = (RiffWaveHeaderParser().scan(header, header.size) as RiffWaveHeaderScan.Ready).payloadOffset

        // One byte at a time: the fmt body and the data id both end up split across scans.
        var ready: RiffWaveHeaderScan.Ready? = null
        for (length in 1..header.size) {
            val scan = parser.scan(header.copyOf(length), length)
            if (scan is RiffWaveHeaderScan.Ready) {
                assertEquals("header became ready early", header.size, length)
                ready = scan
                break
            }
        }

        assertEquals(expectedOffset, ready?.payloadOffset)

        // After Ready the parser is idempotent: appending payload bytes to the same buffer must not be
        // mistaken for another chunk walk.
        val withPayload = header + ByteArray(64) { 0x7F }
        val again = parser.scan(withPayload, withPayload.size) as RiffWaveHeaderScan.Ready
        assertEquals(expectedOffset, again.payloadOffset)
        assertEquals(ready?.format, again.format)
    }

    @Test
    fun `rejects a stream that is not RIFF`() {
        val bytes = "OGGS".toByteArray() + ByteArray(64)

        val error = assertThrows(RiffWaveFormatException::class.java) {
            RiffWaveHeaderParser().scan(bytes, bytes.size)
        }

        assertTrue(error.message!!, error.message!!.contains("Not a RIFF stream"))
    }

    @Test
    fun `rejects a RIFF stream that is not WAVE`() {
        val bytes = "RIFF".toByteArray() + leInt(0) + "AVI ".toByteArray() + ByteArray(64)

        val error = assertThrows(RiffWaveFormatException::class.java) {
            RiffWaveHeaderParser().scan(bytes, bytes.size)
        }

        assertTrue(error.message!!, error.message!!.contains("not WAVE"))
    }

    @Test
    fun `rejects sixteen bit pcm, which the command line never asks for`() {
        val header = wave(
            fmtChunk(channels = 2, sampleRate = 44100, bitsPerSample = 16),
            dataChunk(declaredSize = 0xFFFFFFFF.toInt()),
        )

        val error = assertThrows(RiffWaveFormatException::class.java) {
            RiffWaveHeaderParser().scan(header, header.size)
        }

        assertTrue(error.message!!, error.message!!.contains("32-bit"))
    }

    @Test
    fun `rejects pcm without a fmt chunk before it`() {
        val header = wave(dataChunk(declaredSize = 0xFFFFFFFF.toInt()))

        val error = assertThrows(RiffWaveFormatException::class.java) {
            RiffWaveHeaderParser().scan(header, header.size)
        }

        assertTrue(error.message!!, error.message!!.contains("before any fmt chunk"))
    }

    @Test
    fun `rejects a chunk that declares an implausible size`() {
        // 0xFFFFFF00 as a signed int is negative: a size that large cannot be a real chunk.
        val header = wave(
            fmtChunk(channels = 2, sampleRate = 44100, bitsPerSample = 32),
            chunk("LIST", ByteArray(4), declaredSize = 0xFFFFFF00.toInt()),
        )

        val error = assertThrows(RiffWaveFormatException::class.java) {
            RiffWaveHeaderParser().scan(header, header.size)
        }

        assertTrue(error.message!!, error.message!!.contains("implausible size"))
    }

    @Test
    fun `gives up when no data chunk appears within the header budget`() {
        val header = wave(
            fmtChunk(channels = 2, sampleRate = 44100, bitsPerSample = 32),
            chunk("LIST", ByteArray(1024), declaredSize = 1024),
        )

        val error = assertThrows(RiffWaveFormatException::class.java) {
            RiffWaveHeaderParser(maxHeaderBytes = 512).scan(header, header.size)
        }

        assertTrue(error.message!!, error.message!!.contains("No data chunk within the first 512 bytes"))
    }

    // ------------------------------------------------------------------ header construction

    private fun wave(vararg chunks: ByteArray): ByteArray {
        val body = chunks.reduce { acc, bytes -> acc + bytes }
        return "RIFF".toByteArray() + leInt(4 + body.size) + "WAVE".toByteArray() + body
    }

    private fun chunk(id: String, body: ByteArray, declaredSize: Int = body.size): ByteArray =
        id.toByteArray() + leInt(declaredSize) + body

    private fun fmtChunk(
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        formatTag: Int = 0x0003,
        extraBytes: ByteArray = ByteArray(0),
    ): ByteArray {
        val bytesPerSample = bitsPerSample / 8
        val body = ByteArrayOutputStream()
        body.write(leShort(formatTag))
        body.write(leShort(channels))
        body.write(leInt(sampleRate))
        body.write(leInt(sampleRate * channels * bytesPerSample))
        body.write(leShort(channels * bytesPerSample))
        body.write(leShort(bitsPerSample))
        body.write(extraBytes)
        return chunk("fmt ", body.toByteArray())
    }

    private fun dataChunk(declaredSize: Int): ByteArray = chunk("data", ByteArray(0), declaredSize = declaredSize)

    private fun leShort(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    private fun leInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
