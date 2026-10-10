package com.lonx.lyrico.worker.processor

import com.lonx.audiotag.model.AudioTagData
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.BatchTaskItemEntity
import com.lonx.lyrico.data.model.ReplayGainPeakMode
import com.lonx.lyrico.data.model.ReplayGainSettings
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.domain.song.usecase.PatchSongTagsUseCase
import com.lonx.lyrico.domain.song.usecase.SaveAudioTagsResult
import com.lonx.lyrico.utils.ReplayGainAnalysis
import com.lonx.lyrico.utils.ReplayGainCalculateState
import com.lonx.lyrico.utils.ReplayGainError
import com.lonx.lyrico.utils.ReplayGainScanner
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReplayGainTaskConfig(
    val concurrency: Int,
    val targetLoudness: Double? = null,
    val peakMode: ReplayGainPeakMode? = null
)

/**
 * Batch processor that measures a song's loudness and writes the three ReplayGain tags onto it.
 *
 * Ported from Android essentially unchanged: same skip rules (a song that is already tagged for the
 * configured reference loudness is skipped rather than re-measured), same tag payload built from
 * [ReplayGainScanner.formatGain] / `formatPeak` / `formatReferenceLoudness`, same failure when the
 * analysis does not succeed.
 *
 * One desktop difference: the failure message carries the reason. On Android every analysis failure
 * was reported as the same fixed "ReplayGain analysis failed", but the desktop has a failure the user
 * can actually fix — a missing ffmpeg sidecar — and its message names the directories that were
 * searched. Swallowing that here would leave a user with a silent "failed" row and no way to learn
 * what to install.
 */

class ReplayGainProcessor(
    private val songLibraryRepository: SongLibraryRepository,
    private val patchSongTagsUseCase: PatchSongTagsUseCase,
    private val replayGainScanner: ReplayGainScanner,
    private val settingsRepository: SettingsRepository,
    private val audioTagRepository: AudioTagRepository
) : BatchTaskProcessor {

    override suspend fun process(
        task: BatchTaskEntity,
        item: BatchTaskItemEntity,
        onProgress: suspend (Float) -> Unit
    ): BatchTaskProcessResult {
        val song = songLibraryRepository.getSongByUri(item.songUri)
            ?: throw BatchTaskSkippedException("Song not found")

        val config = task.configJson?.let {
            Json.decodeFromString<ReplayGainTaskConfig>(it)
        } ?: throw BatchTaskSkippedException("No config")
        val replayGainSettings = config.toReplayGainSettingsOrNull()
            ?: settingsRepository.getReplayGainSettings()

        // Library metadata can be stale after tags are removed or edited externally.
        // Only complete track ReplayGain tags for the current target loudness can skip.
        checkReplayGainTags(
            audioTagRepository,
            song.uri,
            replayGainScanner.formatReferenceLoudness(replayGainSettings.targetLoudness)
        )

        var analysisSuccess = false
        var analysisResult: ReplayGainAnalysis? = null
        var failure: ReplayGainCalculateState.Failed? = null

        replayGainScanner.analyze(item.songUri, replayGainSettings.peakMode).collect { state ->
            when (state) {
                is ReplayGainCalculateState.Success -> {
                    analysisResult = state.analysis
                    analysisSuccess = true
                }
                is ReplayGainCalculateState.Cancelled -> {
                    analysisSuccess = false
                }
                is ReplayGainCalculateState.Failed -> {
                    analysisSuccess = false
                    failure = state
                }
                is ReplayGainCalculateState.Progress -> {
                    onProgress(state.percent)
                }
            }
        }

        val analysis = analysisResult
        if (!analysisSuccess || analysis == null) {
            throw Exception("ReplayGain analysis failed: ${failure?.error?.describe() ?: "unknown error"}")
        }

        val tagData = AudioTagData(
            replayGainTrackGain = replayGainScanner.formatGain(
                analysis,
                replayGainSettings.targetLoudness
            ),
            replayGainTrackPeak = replayGainScanner.formatPeak(analysis.peak),
            replayGainReferenceLoudness = replayGainScanner.formatReferenceLoudness(
                replayGainSettings.targetLoudness
            )
        )

        val result = patchSongTagsUseCase(item.songUri, tagData)
        if (result !is SaveAudioTagsResult.Success) {
            throw Exception("Write failed")
        }

        return BatchTaskProcessResult()
    }
}

internal fun ReplayGainTaskConfig.toReplayGainSettingsOrNull(): ReplayGainSettings? {
    val targetLoudness = targetLoudness ?: return null
    val peakMode = peakMode ?: return null
    return ReplayGainSettings(targetLoudness, peakMode)
}

internal suspend fun checkReplayGainTags(
    repository: AudioTagRepository,
    uri: String,
    expectedReferenceLoudness: String
) {
    val tag = repository.read(uri, AudioTagReadOptions(strict = true))
    val hasCompleteTrackReplayGain = !tag.replayGainTrackGain.isNullOrBlank() &&
        !tag.replayGainTrackPeak.isNullOrBlank() &&
        !tag.replayGainReferenceLoudness.isNullOrBlank()
    val hasCurrentReferenceLoudness = tag.replayGainReferenceLoudness == expectedReferenceLoudness
    if (hasCompleteTrackReplayGain && hasCurrentReferenceLoudness) {
        throw BatchTaskSkippedException("ReplayGain already exists")
    }
}

/**
 * Turns an analysis failure into the tail of the batch task's error message.
 *
 * Codes and exceptions carry their own text (`the ffmpeg sidecar is missing, probed …`, `ffmpeg
 * exited with 1 …`); the three states that carry nothing get a fixed label.
 */
internal fun ReplayGainError.describe(): String = when (this) {
    is ReplayGainError.NoAudioTrack -> "the file has no audio track"
    is ReplayGainError.UnknownMimeType -> "the audio codec could not be identified"
    is ReplayGainError.ZeroSampleCount -> "the file decoded to zero samples"
    is ReplayGainError.CodecException -> message ?: "the audio codec failed"
    is ReplayGainError.GeneralException -> message ?: "unknown error"
}
