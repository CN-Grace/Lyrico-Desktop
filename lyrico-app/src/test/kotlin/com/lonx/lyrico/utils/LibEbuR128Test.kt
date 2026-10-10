package com.lonx.lyrico.utils

import com.lonx.lyrico.data.model.ReplayGainPeakMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * C6d 的第二层验收：**JNI 桥接真的把样本交到了 libebur128 手里。**
 *
 * 这一层不需要 ffmpeg：样本由测试自己造，所以每条断言都能对着算术核对（幅度 `A` 的正弦 RMS 是
 * `A/√2`，声道数加倍是 +3.01 LU）。这里同时把两个 JNI 陷阱钉死：**必须是 direct buffer**（堆
 * buffer 会静默地什么都不测），以及样本必须落在 index 0。
 */
class LibEbuR128Test {

    @Test
    fun `a full-scale sine measures minus three LUFS`() {
        val samples = sine(seconds = 2.0, amplitude = 1.0)
        val state = LibEbuR128(channels = 1, sampleRate = SAMPLE_RATE, peakMode = ReplayGainPeakMode.SAMPLE_PEAK)

        state.processDirect(directFloats(samples), isFloat = true, frameCount = samples.size)

        assertEquals(samples.size.toLong(), state.sampleCount)
        // RMS = 1/√2 is -3.0103 dBFS, and 1 kHz sits at the K-weighting curve's 0 dB point, so the
        // integrated loudness lands on -3.00 LUFS (measured -3.0008; the tolerance covers the
        // rounding of a finite-length sine).
        assertEquals(-3.0, state.loudness, 0.01)
        assertEquals(1.0, state.peak, 0.001)
        state.close()
    }

    @Test
    fun `a heap buffer measures nothing even though the sample count advances`() {
        val samples = sine(seconds = 2.0, amplitude = 1.0)
        val state = LibEbuR128(channels = 1, sampleRate = SAMPLE_RATE, peakMode = ReplayGainPeakMode.SAMPLE_PEAK)

        // The trap this class documents: ebur128.cpp reads the samples through
        // GetDirectBufferAddress, which is null for a heap buffer, and processDirectNative then
        // returns without adding anything. Kotlin's sampleCount still moves, so the count is not a
        // usable guard - the loudness is.
        val heap = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { heap.putFloat(it.toFloat()) }
        state.processDirect(heap, isFloat = true, frameCount = samples.size)

        assertEquals(samples.size.toLong(), state.sampleCount)
        assertFalse("a heap buffer must not have reached libebur128: ${state.loudness}", state.loudness.isFinite())
        assertEquals(0.0, state.peak, 0.0)
        state.close()
    }

    @Test
    fun `silence is negative infinity at the bridge`() {
        val state = LibEbuR128(channels = 1, sampleRate = SAMPLE_RATE, peakMode = ReplayGainPeakMode.SAMPLE_PEAK)

        state.processDirect(directFloats(DoubleArray(SAMPLE_RATE)), isFloat = true, frameCount = SAMPLE_RATE)

        // This is where ReplayGainScanner's -70 LUFS floor comes from: "-inf" cannot be written into
        // a tag, and this bridge deliberately keeps reporting what libebur128 says.
        assertEquals(Double.NEGATIVE_INFINITY, state.loudness, 0.0)
        assertEquals(0.0, state.peak, 0.0)
        state.close()
    }

    @Test
    fun `inter-sample peaks are caught by true peak mode and missed by sample peak`() {
        // A quarter-sample-rate sine with a 45 degree phase offset: every sample lands on ±0.7071·A
        // while the waveform itself reaches A. That gap is the entire reason true peak exists.
        val samples = DoubleArray(SAMPLE_RATE) { 0.8 * sin(PI / 2.0 * it + PI / 4.0) }

        val samplePeak = peakOf(samples, ReplayGainPeakMode.SAMPLE_PEAK)
        val truePeak = peakOf(samples, ReplayGainPeakMode.TRUE_PEAK)

        assertEquals(0.8 * sqrt(2.0) / 2.0, samplePeak, 0.002)
        assertEquals("true peak should recover the waveform's own maximum: $truePeak", 0.8, truePeak, 0.05)
        assertTrue("true peak must exceed the sample peak: $truePeak vs $samplePeak", truePeak > samplePeak * 1.3)
    }

    @Test
    fun `several states measure together as one programme`() {
        val loud = LibEbuR128(1, SAMPLE_RATE, ReplayGainPeakMode.SAMPLE_PEAK)
        val quiet = LibEbuR128(1, SAMPLE_RATE, ReplayGainPeakMode.SAMPLE_PEAK)
        val loudSamples = sine(seconds = 5.0, amplitude = 1.0)
        val quietSamples = sine(seconds = 3.0, amplitude = 0.5)
        loud.processDirect(directFloats(loudSamples), isFloat = true, frameCount = loudSamples.size)
        quiet.processDirect(directFloats(quietSamples), isFloat = true, frameCount = quietSamples.size)

        val combined = LibEbuR128.loudnessMultiple(listOf(loud, quiet))

        // Both tracks are above the gate, so the album number is the duration-weighted mean of their
        // energies: (5·10^(-3.0008/10) + 3·10^(-9.0214/10)) / 8 = -4.43 LUFS. (Measured -4.389; the
        // residual is libebur128's own relative gate, which is the reason the album test allows 0.2.)
        // 10·log10 of the mean *energy* (each loudness is already a level, so 10^(L/10) is power).
        val expected = 10.0 * kotlin.math.log10(
            (5.0 * 10.0.pow(loud.loudness / 10.0) + 3.0 * 10.0.pow(quiet.loudness / 10.0)) / 8.0,
        )
        assertEquals(expected, combined, 0.1)
        assertTrue("the album must be quieter than its loudest track", combined < loud.loudness)
        assertTrue("the album must be louder than its quietest track", combined > quiet.loudness)

        loud.close()
        quiet.close()
    }

    @Test
    fun `an empty programme keeps the floor and a closed state is inert`() {
        assertEquals(-70.0, LibEbuR128.loudnessMultiple(emptyList()), 0.0)

        val samples = sine(seconds = 1.0, amplitude = 1.0)
        val state = LibEbuR128(1, SAMPLE_RATE, ReplayGainPeakMode.SAMPLE_PEAK)
        state.close()
        state.close() // closing twice must not double-free
        state.processDirect(directFloats(samples), isFloat = true, frameCount = samples.size)

        assertEquals("a closed state must not accumulate", 0L, state.sampleCount)
        assertEquals(-70.0, state.loudness, 0.0)
        assertEquals(0.0, state.peak, 0.0)
    }

    private fun peakOf(samples: DoubleArray, peakMode: ReplayGainPeakMode): Double {
        val state = LibEbuR128(1, SAMPLE_RATE, peakMode)
        state.processDirect(directFloats(samples), isFloat = true, frameCount = samples.size)
        val peak = state.peak
        state.close()
        return peak
    }

    private fun directFloats(samples: DoubleArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it.toFloat()) }
        return buffer
    }

    private fun sine(seconds: Double, amplitude: Double, frequencyHz: Double = 1000.0): DoubleArray {
        val frames = (seconds * SAMPLE_RATE).toInt()
        return DoubleArray(frames) { amplitude * sin(2.0 * PI * frequencyHz * it / SAMPLE_RATE) }
    }

    private companion object {
        const val SAMPLE_RATE = 44100
    }
}
