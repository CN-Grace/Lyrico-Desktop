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
import com.lonx.lyrico.utils.ReplayGainCalculateState
import com.lonx.lyrico.utils.ReplayGainScanner
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReplayGainTaskConfig(
    val concurrency: Int,
    val targetLoudness: Double? = null,
    val peakMode: ReplayGainPeakMode? = null
)

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
        var analysisResult: com.lonx.lyrico.utils.ReplayGainAnalysis? = null

        replayGainScanner.analyze(item.songUri, replayGainSettings.peakMode).collect { state ->
            when (state) {
                is ReplayGainCalculateState.Success -> {
                    analysisResult = state.analysis
                    analysisSuccess = true
                }
                is ReplayGainCalculateState.Cancelled,
                is ReplayGainCalculateState.Failed -> {
                    analysisSuccess = false
                }
                is ReplayGainCalculateState.Progress -> {
                    onProgress(state.percent)
                }
            }
        }

        if (!analysisSuccess || analysisResult == null) {
            throw Exception("ReplayGain analysis failed")
        }

        val tagData = AudioTagData(
            replayGainTrackGain = replayGainScanner.formatGain(
                analysisResult,
                replayGainSettings.targetLoudness
            ),
            replayGainTrackPeak = replayGainScanner.formatPeak(analysisResult.peak),
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
