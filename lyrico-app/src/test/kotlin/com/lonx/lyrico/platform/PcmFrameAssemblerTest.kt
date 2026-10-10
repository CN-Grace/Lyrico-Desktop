package com.lonx.lyrico.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the frame carry-over between reads.
 *
 * The bug this guards against is quiet: a partial frame either gets dropped (a few dozen samples of
 * a five-minute song, invisible in the loudness number) or, worse, gets shifted so every following
 * sample is wrong by half a frame. The test therefore does not check a loudness or a count that
 * happens to be close — it reassembles every byte the assembler emits and compares it with the bytes
 * that went in, so a duplicated, dropped or reordered byte fails.
 */
class PcmFrameAssemblerTest {

    @Test
    fun `reassembles exactly, whatever the read sizes are`() {
        val bytesPerFrame = 6 // stereo float: 65536 is not a multiple of this, and neither is 1
        val frames = 1000
        val source = ByteArray(frames * bytesPerFrame) { (it % 251).toByte() }

        val emitted = runAssembler(bytesPerFrame, source, SPLIT_PATTERN)

        assertEquals(0, emitted.pendingBytes)
        assertArrayEquals(source, emitted.bytes)
    }

    @Test
    fun `reassembles exactly for mono and three channel frames`() {
        for (bytesPerFrame in listOf(4, 12, 3, 256)) {
            val source = ByteArray(777 * bytesPerFrame) { ((it * 7) % 253).toByte() }

            val emitted = runAssembler(bytesPerFrame, source, SPLIT_PATTERN)

            assertArrayEquals("bytesPerFrame=$bytesPerFrame", source, emitted.bytes)
            assertEquals("bytesPerFrame=$bytesPerFrame", 0, emitted.pendingBytes)
        }
    }

    @Test
    fun `holds a partial frame back instead of emitting it`() {
        val assembler = PcmFrameAssembler(bytesPerFrame = 6)

        // Four bytes of a six byte frame: not one whole frame, so nothing may be emitted.
        assertEquals(0, assembler.feed(byteArrayOf(1, 2, 3, 4), 0, 4))
        assertEquals(4, assembler.pendingByteCount)
    }

    @Test
    fun `completes a frame that straddles two reads`() {
        val assembler = PcmFrameAssembler(bytesPerFrame = 6)

        assertEquals(0, assembler.feed(byteArrayOf(1, 2, 3, 4), 0, 4))
        // Two more bytes complete the first frame and leave three bytes of the second pending.
        assertEquals(1, assembler.feed(byteArrayOf(5, 6, 7, 8, 9), 0, 5))

        assertEquals(3, assembler.pendingByteCount)
        val emitted = ByteArray(6)
        assembler.buffer.get(0, emitted)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), emitted)
    }

    @Test
    fun `keeps a whole read's worth of frames when the read is larger than the frame`() {
        // The decoder's read size is 64 KiB; this pins that a full read plus a pending fragment still
        // fits the buffer instead of overflowing it.
        val assembler = PcmFrameAssembler(bytesPerFrame = 256)
        val read = ByteArray(64 * 1024) { (it % 127).toByte() }

        // Prime a fragment first, so the read arrives with bytes already pending.
        assertEquals(0, assembler.feed(ByteArray(255), 0, 255))
        val frames = assembler.feed(read, 0, read.size)

        assertEquals((255 + read.size) / 256, frames)
        assertEquals((255 + read.size) % 256, assembler.pendingByteCount)
        assertTrue(assembler.buffer.capacity() >= 255 + read.size)
    }

    @Test
    fun `ignores an empty read`() {
        val assembler = PcmFrameAssembler(bytesPerFrame = 6)

        assertEquals(0, assembler.feed(ByteArray(0), 0, 0))
        assertEquals(0, assembler.pendingByteCount)
    }

    @Test
    fun `hands over a direct buffer, which is the only kind the native library can read`() {
        val assembler = PcmFrameAssembler(bytesPerFrame = 6)

        assertTrue(
            "a heap buffer would be read as silence by ebur128.cpp",
            assembler.buffer.isDirect,
        )
        assertFalse(assembler.buffer.hasArray())
        assertEquals(0, assembler.buffer.position())
    }

    private class Emitted(val bytes: ByteArray, val pendingBytes: Int)

    /** Feeds [source] in [SPLIT_PATTERN]-sized slices and collects every emitted frame byte. */
    private fun runAssembler(bytesPerFrame: Int, source: ByteArray, pattern: IntArray): Emitted {
        val assembler = PcmFrameAssembler(bytesPerFrame)
        val collected = java.io.ByteArrayOutputStream()
        var offset = 0
        var patternIndex = 0
        while (offset < source.size) {
            val count = minOf(pattern[patternIndex % pattern.size], source.size - offset)
            patternIndex++
            val frames = assembler.feed(source, offset, count)
            if (frames > 0) {
                val chunk = ByteArray(frames * bytesPerFrame)
                assembler.buffer.get(0, chunk)
                collected.write(chunk)
            }
            offset += count
        }
        return Emitted(collected.toByteArray(), assembler.pendingByteCount)
    }

    private companion object {
        /** Read sizes chosen to land mid-frame in as many ways as possible (1, 5, 3, 7, 2, 4, 6). */
        val SPLIT_PATTERN = intArrayOf(1, 5, 3, 7, 2, 4, 6, 13, 1, 1, 64)
    }
}
