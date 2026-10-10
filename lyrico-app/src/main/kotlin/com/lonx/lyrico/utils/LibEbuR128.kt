package com.lonx.lyrico.utils

import com.lonx.audiotag.internal.NativeLibraryLoader
import com.lonx.lyrico.data.model.ReplayGainPeakMode
import java.nio.ByteBuffer

/**
 * JNI bridge to the bundled libebur128 (`ebur128.dll`), which implements the ITU-R BS.1770 loudness
 * measurement behind ReplayGain.
 *
 * Ported from Android with two desktop changes:
 *
 * - `System.loadLibrary("ebur128")` is replaced by [NativeLibraryLoader], which probes the
 *   launcher/`lyrico.native.dir`/`build/native/<platform>` locations and reports every probed path
 *   when it fails. The DLL is on `java.library.path` in neither the development nor the packaged
 *   layout, so a bare `loadLibrary` call would fail with an opaque `UnsatisfiedLinkError`.
 * - The Android-only `@Keep`/R8 concerns are gone; JNI resolves these methods by name and signature.
 *
 * **Chunks must be handed over in a direct [ByteBuffer].** `ebur128.cpp` reads the samples with
 * `GetDirectBufferAddress`, which returns `null` for a heap buffer, and `processDirectNative` then
 * returns early without adding anything — a silent zero-sample measurement rather than an error. The
 * address it uses is the buffer's *base*, so the samples have to sit at index 0; position and limit
 * are not honoured.
 */
class LibEbuR128(
    val channels: Int,
    sampleRate: Int,
    private val peakMode: ReplayGainPeakMode
) : AutoCloseable {

    companion object {
        init {
            NativeLibraryLoader.load(EBUR128)
        }

        /** Library name; the loader maps it to `ebur128.dll` on Windows. */
        const val EBUR128: String = "ebur128"

        const val FORMAT_SHORT = 1
        const val FORMAT_FLOAT = 2

        fun loudnessMultiple(states: List<LibEbuR128>): Double {
            if (states.isEmpty()) return -70.0
            return states.first().getMultipleLoudnessNative(states.map { it.nativePtr }.toLongArray())
        }
    }

    private var nativePtr: Long = 0
    var sampleCount: Long = 0
        private set

    init {
        nativePtr = initNative(channels, sampleRate, peakMode == ReplayGainPeakMode.TRUE_PEAK)
        if (nativePtr == 0L) {
            throw IllegalStateException("Failed to initialize libebur128")
        }
    }

    /**
     * Adds [frameCount] frames from [buffer].
     *
     * [buffer] must be direct and hold the samples at index 0 (see the class KDoc). [isFloat] is
     * `FORMAT_FLOAT`'s switch: `true` for 32-bit float PCM, `false` for 16-bit short PCM. The desktop
     * decoder only ever produces float PCM, so the short path survives purely as part of the ported
     * API surface.
     */
    fun processDirect(buffer: ByteBuffer, isFloat: Boolean, frameCount: Int) {
        if (nativePtr == 0L || frameCount <= 0) return
        val format = if (isFloat) FORMAT_FLOAT else FORMAT_SHORT
        processDirectNative(nativePtr, buffer, format, frameCount)
        sampleCount += frameCount
    }

    val loudness: Double
        get() = if (nativePtr == 0L) -70.0 else getLoudnessNative(nativePtr)

    val peak: Double
        get() = if (nativePtr == 0L) {
            0.0
        } else {
            getPeakNative(nativePtr, channels, peakMode == ReplayGainPeakMode.TRUE_PEAK)
        }

    override fun close() {
        if (nativePtr != 0L) {
            destroyNative(nativePtr)
            nativePtr = 0L
        }
    }

    private external fun initNative(channels: Int, sampleRate: Int, useTruePeak: Boolean): Long
    private external fun destroyNative(statePtr: Long)
    private external fun processDirectNative(statePtr: Long, buffer: ByteBuffer, format: Int, frames: Int)
    private external fun getLoudnessNative(statePtr: Long): Double
    private external fun getPeakNative(statePtr: Long, channels: Int, useTruePeak: Boolean): Double
    private external fun getMultipleLoudnessNative(statePtrs: LongArray): Double
}
