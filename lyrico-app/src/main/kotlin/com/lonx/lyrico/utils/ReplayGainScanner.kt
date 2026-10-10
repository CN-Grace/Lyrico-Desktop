package com.lonx.lyrico.utils

import com.lonx.lyrico.data.model.ReplayGainPeakMode
import com.lonx.lyrico.platform.FfmpegAudioDecoder
import com.lonx.lyrico.platform.FfmpegDecodeException
import com.lonx.lyrico.platform.FfmpegFailure
import com.lonx.lyrico.platform.FfmpegUnavailableException
import com.lonx.lyrico.platform.RiffWaveFormatException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.Path
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

data class ReplayGainAnalysis(
    val loudnessLufs: Double,
    val sampleCount: Long,
    val peak: Double
)

data class AlbumReplayGainAnalysis(
    val loudnessLufs: Double,
    val sampleCount: Long,
    val peak: Double,
    val tracks: List<ReplayGainAnalysis>
)

// 定义具体的错误原因，方便调用方根据枚举或类名进行多语言/自定义处理
//
// Desktop rewrite: Android had two more cases, `UnsupportedCodec(mimeType)` and
// `CodecException(message, isAlacIssue)`. Both came from asking the platform for a decoder before
// touching the file (`MediaCodecList.findDecoderForFormat`) and from the platform ALAC decoder's
// instability. ffmpeg offers neither question: it either decodes the stream or exits non-zero with a
// reason on stderr, and its ALAC decoder has no such defect. Codec problems are therefore reported as
// `CodecException` carrying ffmpeg's own message, and no branch here claims to know whether a codec
// "should" have been available. The `replay_gain_error_unsupported_codec` /
// `replay_gain_error_alac_issue` strings stay unused in the resources until the EditMetadata screen
// is ported and its error mapping is rewritten for the desktop.
sealed interface ReplayGainError {
    data object NoAudioTrack : ReplayGainError
    data object UnknownMimeType : ReplayGainError
    data object ZeroSampleCount : ReplayGainError
    data class CodecException(val message: String?) : ReplayGainError
    data class GeneralException(val message: String?) : ReplayGainError
}

sealed interface ReplayGainCalculateState {
    data class Progress(val percent: Float) : ReplayGainCalculateState
    data object Cancelled : ReplayGainCalculateState
    data class Success(
        val analysis: ReplayGainAnalysis,
        val mimeType: String
    ) : ReplayGainCalculateState

    // 将失败统一为一个状态，携带错误原因对象
    data class Failed(
        val mimeType: String?,
        val error: ReplayGainError
    ) : ReplayGainCalculateState
}

sealed interface AlbumReplayGainCalculateState {
    data class Progress(val percent: Float) : AlbumReplayGainCalculateState
    data object Cancelled : AlbumReplayGainCalculateState
    data class Success(val analysis: AlbumReplayGainAnalysis) : AlbumReplayGainCalculateState
    data class Failed(
        val uriString: String?,
        val mimeType: String?,
        val error: ReplayGainError
    ) : AlbumReplayGainCalculateState
}

/**
 * Measures ITU-R BS.1770 loudness for one track or a whole album.
 *
 * Ported from Android with the decode half replaced. Android pulled PCM out of `MediaExtractor` +
 * `MediaCodec`, so it needed a `Context` and a `uriString` that `ContentResolver` understood; this
 * class now owns an [FfmpegAudioDecoder] instead and treats the `uriString` the way the rest of the
 * desktop tree does — as an absolute Windows path ([com.lonx.lyrico.data.model.entity.SongEntity.path]
 * is `Path.of(uri)`).
 *
 * Everything downstream of the samples is unchanged: the same `LibEbuR128` state object, the same
 * progress cadence (the first 1% step, then every 1%), the same `formatGain`/`formatPeak`/
 * `formatReferenceLoudness` text, and the same album path through
 * [LibEbuR128.loudnessMultiple] over per-track states.
 *
 * Two facts the desktop reports differently, both recorded in PLAN.md:
 *
 * - **`Success.mimeType` is ffmpeg's name for the input codec** (`flac`, `mp3`, `vorbis`, …) rather
 *   than an Android MIME type (`audio/flac`). It is display-only — no caller branches on it — but the
 *   table that turns it into a human format name in the EditMetadata screen has to be rewritten for
 *   these names when that screen is ported.
 * - **`ReplayGainError.UnknownMimeType` is now defensive.** It used to mean "the platform did not
 *   report a MIME type"; here it means "ffmpeg named an audio stream but not its codec", which no
 *   file is expected to produce. It is kept so a `Success` always carries a non-null codec name.
 */
class ReplayGainScanner(private val decoder: FfmpegAudioDecoder = FfmpegAudioDecoder()) {
    companion object {
        const val DEFAULT_TARGET_LOUDNESS_LUFS = -18.0

        /**
         * Loudness reported for a track that never rises above BS.1770's absolute gate, i.e. digital
         * silence.
         *
         * libebur128 answers `-inf` for such input, and an infinity cannot go into a tag: `"%.2f".
         * format` writes `"Infinity dB"`, which no player parses. `-70` is that same absolute gate,
         * and it is what ffmpeg's own `ebur128` filter prints for the identical file (measured: our
         * bridge `-Infinity`, the filter `-70.0 LUFS`), so the floor is used instead. The JNI bridge
         * itself still reports the raw value — the substitution happens only on the way into a
         * [ReplayGainAnalysis].
         */
        const val SILENCE_LOUDNESS_LUFS = -70.0

        private const val PROGRESS_UPDATE_THRESHOLD = 0.01

        /** `-inf`/`NaN` from libebur128 becomes the silence floor; everything else passes through. */
        private fun Double.atLeastSilenceFloor(): Double =
            if (isFinite()) this else SILENCE_LOUDNESS_LUFS
    }

    fun analyze(
        uriString: String,
        peakMode: ReplayGainPeakMode = ReplayGainPeakMode.SAMPLE_PEAK
    ): Flow<ReplayGainCalculateState> = flow {
        var decoded: ReplayGainDecodeResult? = null
        try {
            emit(ReplayGainCalculateState.Progress(0f))
            decoded = decodeToState(uriString, peakMode) { progress ->
                emit(ReplayGainCalculateState.Progress(progress))
            }
            emit(ReplayGainCalculateState.Progress(1.0f))
            emit(
                ReplayGainCalculateState.Success(
                    analysis = decoded.analysis,
                    mimeType = decoded.mimeType
                )
            )
        } catch (e: CancellationException) {
            emit(ReplayGainCalculateState.Cancelled)
            throw e
        } catch (e: ReplayGainScanException) {
            emit(ReplayGainCalculateState.Failed(e.mimeType, e.error))
        } finally {
            runCatching { decoded?.state?.close() }
        }
    }.flowOn(Dispatchers.IO)

    fun analyzeAlbum(
        uriStrings: List<String>,
        peakMode: ReplayGainPeakMode = ReplayGainPeakMode.SAMPLE_PEAK
    ): Flow<AlbumReplayGainCalculateState> = flow {
        if (uriStrings.isEmpty()) {
            emit(AlbumReplayGainCalculateState.Failed(null, null, ReplayGainError.ZeroSampleCount))
            return@flow
        }

        val decodedTracks = mutableListOf<ReplayGainDecodeResult>()
        try {
            emit(AlbumReplayGainCalculateState.Progress(0f))
            uriStrings.forEachIndexed { index, uriString ->
                val decoded = decodeToState(uriString, peakMode) { trackProgress ->
                    val albumProgress = (index + trackProgress) / uriStrings.size.toFloat()
                    emit(AlbumReplayGainCalculateState.Progress(albumProgress.coerceIn(0f, 1f)))
                }
                decodedTracks += decoded
            }

            val states = decodedTracks.map { it.state }
            val albumLoudness = LibEbuR128.loudnessMultiple(states).atLeastSilenceFloor()
            val albumPeak = decodedTracks.maxOf { it.analysis.peak }
            val sampleCount = decodedTracks.sumOf { it.analysis.sampleCount }

            emit(AlbumReplayGainCalculateState.Progress(1.0f))
            emit(
                AlbumReplayGainCalculateState.Success(
                    AlbumReplayGainAnalysis(
                        loudnessLufs = albumLoudness,
                        sampleCount = sampleCount,
                        peak = albumPeak,
                        tracks = decodedTracks.map { it.analysis }
                    )
                )
            )
        } catch (e: CancellationException) {
            emit(AlbumReplayGainCalculateState.Cancelled)
            throw e
        } catch (e: ReplayGainScanException) {
            emit(AlbumReplayGainCalculateState.Failed(e.uriString, e.mimeType, e.error))
        } finally {
            decodedTracks.forEach { decoded ->
                runCatching { decoded.state.close() }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun decodeToState(
        uriString: String,
        peakMode: ReplayGainPeakMode,
        onProgress: suspend (Float) -> Unit
    ): ReplayGainDecodeResult {
        var mimeType: String? = null
        var ebuR128: LibEbuR128? = null
        var completed = false

        try {
            val info = decoder.decode(
                file = Path.of(uriString),
                onFormat = { format ->
                    // ffmpeg's header is the desktop stand-in for INFO_OUTPUT_FORMAT_CHANGED, and it
                    // precedes every sample, so this runs exactly once before the first chunk.
                    ebuR128?.close()
                    ebuR128 = LibEbuR128(format.channels, format.sampleRate, peakMode)
                },
                onPcm = { pcm, frameCount ->
                    val analyzer = ebuR128
                        ?: throw IllegalStateException("PCM arrived before the stream format")
                    // Always float: the decoder asks ffmpeg for `pcm_f32le`.
                    analyzer.processDirect(pcm, isFloat = true, frameCount = frameCount)
                },
                onProgress = onProgress
            )

            mimeType = info.codecName
            onProgress(1.0f)

            if (mimeType == null) {
                throw ReplayGainScanException(uriString, null, ReplayGainError.UnknownMimeType)
            }

            val analyzer = ebuR128
            if (analyzer == null || analyzer.sampleCount == 0L) {
                throw ReplayGainScanException(uriString, mimeType, ReplayGainError.ZeroSampleCount)
            }

            completed = true
            return ReplayGainDecodeResult(
                analysis = ReplayGainAnalysis(
                    loudnessLufs = analyzer.loudness.atLeastSilenceFloor(),
                    sampleCount = analyzer.sampleCount,
                    peak = analyzer.peak
                ),
                mimeType = mimeType,
                state = analyzer
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: ReplayGainScanException) {
            throw e
        } catch (e: FfmpegUnavailableException) {
            // Nothing to decode with: reported as-is, with the directories that were probed, rather
            // than as "this song failed".
            throw ReplayGainScanException(
                uriString = uriString,
                mimeType = mimeType,
                error = ReplayGainError.GeneralException(e.message)
            )
        } catch (e: FfmpegDecodeException) {
            throw ReplayGainScanException(uriString, mimeType, e.toReplayGainError())
        } catch (e: RiffWaveFormatException) {
            throw ReplayGainScanException(
                uriString = uriString,
                mimeType = mimeType,
                error = ReplayGainError.CodecException(e.message)
            )
        } catch (e: IllegalStateException) {
            // Android mapped the platform's own `IllegalStateException` ("Executing states") the same
            // way; on desktop this is the decoder's or the JNI bridge's invariant giving way.
            throw ReplayGainScanException(
                uriString = uriString,
                mimeType = mimeType,
                error = ReplayGainError.CodecException(e.message)
            )
        } catch (e: Exception) {
            throw ReplayGainScanException(
                uriString = uriString,
                mimeType = mimeType,
                error = ReplayGainError.GeneralException(e.message)
            )
        } finally {
            if (!completed) {
                runCatching { ebuR128?.close() }
            }
        }
    }

    private data class ReplayGainDecodeResult(
        val analysis: ReplayGainAnalysis,
        val mimeType: String,
        val state: LibEbuR128
    )

    private class ReplayGainScanException(
        val uriString: String?,
        val mimeType: String?,
        val error: ReplayGainError
    ) : Exception(
        when (error) {
            is ReplayGainError.NoAudioTrack -> "No audio track"
            is ReplayGainError.UnknownMimeType -> "Unknown MIME type"
            is ReplayGainError.ZeroSampleCount -> "Zero sample count"
            is ReplayGainError.CodecException -> error.message
            is ReplayGainError.GeneralException -> error.message
        }
    )

    /**
     * 将解析出的 LUFS 响度格式化为 Track/Album Gain
     */
    fun formatGain(
        loudnessLufs: Double,
        targetLoudnessLufs: Double = DEFAULT_TARGET_LOUDNESS_LUFS
    ): String {
        // Gain = 目标参考响度 - 实际测量响度
        // A caller that hands over a raw libebur128 reading of silence gets the floor, not "Infinity".
        val gainDb = targetLoudnessLufs - loudnessLufs.atLeastSilenceFloor()
        return "%.2f dB".format(Locale.US, gainDb)
    }

    /**
     * 将解析出的 LUFS 响度格式化为 Track Gain
     */
    fun formatGain(
        analysis: ReplayGainAnalysis,
        targetLoudnessLufs: Double = DEFAULT_TARGET_LOUDNESS_LUFS
    ): String {
        return formatGain(analysis.loudnessLufs, targetLoudnessLufs)
    }

    /**
     * 将目标参考响度格式化为写入标签的文本，例如 "-18 LUFS"
     */
    fun formatReferenceLoudness(targetLoudnessLufs: Double): String {
        val value = if (targetLoudnessLufs % 1.0 == 0.0) {
            "%.0f".format(Locale.US, targetLoudnessLufs)
        } else {
            "%.2f".format(Locale.US, targetLoudnessLufs)
        }
        return "$value LUFS"
    }

    /** 格式化归一化峰值。 */
    fun formatPeak(peak: Double): String {
        // 真实峰值可能超过 1.0，因此这里不做上限截断。
        return "%.6f".format(Locale.US, peak.coerceAtLeast(0.0))
    }
}

/** Maps the decoder's three failure kinds onto the states callers already switch on. */
private fun FfmpegDecodeException.toReplayGainError(): ReplayGainError = when (failure) {
    // ffmpeg's `-map 0:a:0` matched nothing: the file has no audio stream to measure.
    FfmpegFailure.NoAudioStream -> ReplayGainError.NoAudioTrack
    // The path does not exist / is not a file — a library problem, not a codec problem.
    FfmpegFailure.InputMissing -> ReplayGainError.GeneralException(message)
    // A codec that gave up: corrupt data, an unsupported stream, a truncated file.
    FfmpegFailure.DecodeFailed -> ReplayGainError.CodecException(message)
}
