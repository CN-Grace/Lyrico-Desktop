package com.lonx.lyrico.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for reading ffmpeg's stderr banner.
 *
 * The two facts pulled out of it — the input codec's name and the input's duration — are the only
 * things the PCM stream itself cannot provide, and both are needed while ffmpeg is still running
 * (the codec for the reported MIME type, the duration for progress). The lines used here are copied
 * verbatim from a real `ffmpeg -hide_banner -i sine.wav` run, including the `Stream #0:0` dump whose
 * *second* `Audio:` line describes the `pcm_f32le` output rather than the source.
 */
class FfmpegStderrSummaryTest {

    @Test
    fun `reads the input codec, not the codec of the pcm it was asked to write`() {
        val summary = FfmpegStderrSummary()

        summary.consume("Input #0, flac, from 'H:\\Music\\song.flac':")
        summary.consume("  Duration: 00:03:20.11, start: 0.000000, bitrate: 702 kb/s")
        summary.consume("  Stream #0:0: Audio: flac, 44100 Hz, stereo, s16")
        summary.consume("Output #0, wav, to 'pipe:':")
        summary.consume("  Stream #0:0: Audio: pcm_f32le ([1][0][0][0] / 0x0003), 44100 Hz, stereo, flt")

        assertEquals("flac", summary.codecName)
        assertEquals(200.11, summary.durationSeconds!!, 1e-9)
    }

    @Test
    fun `names the codec of an mp3 and an opus input`() {
        val mp3 = FfmpegStderrSummary().apply {
            consume("  Stream #0:0: Audio: mp3, 44100 Hz, stereo, fltp, 128 kb/s")
        }
        val opus = FfmpegStderrSummary().apply {
            consume("  Stream #0:0: Audio: opus (Opus [Ogg]), 48000 Hz, stereo, fltp")
        }

        assertEquals("mp3", mp3.codecName)
        assertEquals("opus", opus.codecName)
    }

    @Test
    fun `parses a duration of a track longer than an hour`() {
        val summary = FfmpegStderrSummary()

        summary.consume("  Duration: 01:02:03.25, bitrate: 1000 kb/s")

        assertEquals(3723.25, summary.durationSeconds!!, 1e-9)
    }

    @Test
    fun `reports no duration when ffmpeg has none`() {
        val summary = FfmpegStderrSummary()

        summary.consume("  Duration: N/A, start: 0.000000, bitrate: N/A")

        assertNull(summary.durationSeconds)
        assertNull(summary.codecName)
    }

    @Test
    fun `keeps only the last lines of a long stderr`() {
        val summary = FfmpegStderrSummary()

        repeat(100) { summary.consume("line $it") }

        val tail = summary.tailText().lines()
        assertEquals(20, tail.size)
        assertEquals("line 80", tail.first())
        assertEquals("line 99", tail.last())
    }

    @Test
    fun `classifies a map that matched nothing as a missing audio track`() {
        val summary = FfmpegStderrSummary()
        summary.consume("Stream map '0:a:0' matches no streams.")
        summary.consume("Failed to set value '0:a:0' for option 'map': Invalid argument")

        val error = summary.toException(1)

        assertEquals(FfmpegFailure.NoAudioStream, error.failure)
        assertTrue(error.message!!, error.message!!.contains("no audio stream"))
        assertTrue(error.stderrTail.contains("matches no streams"))
    }

    @Test
    fun `classifies any other non zero exit as a decode failure, carrying the reason`() {
        val summary = FfmpegStderrSummary()
        summary.consume("Input #0, mp3, from 'broken.mp3':")
        summary.consume("[mp3 @ 0000] Header missing")

        val error = summary.toException(1)

        assertEquals(FfmpegFailure.DecodeFailed, error.failure)
        assertTrue(error.stderrTail, error.stderrTail.contains("Header missing"))
        assertTrue(error.message!!, error.message!!.contains("exited with 1"))
    }

    @Test
    fun `reports a clean exit without pcm as a decode failure`() {
        val summary = FfmpegStderrSummary()

        val error = summary.toException(0)

        assertEquals(FfmpegFailure.DecodeFailed, error.failure)
        assertTrue(error.message!!, error.message!!.contains("no PCM"))
    }
}
