package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.audiotag.model.AudioTagData
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.entity.path
import com.lonx.lyrico.data.repository.FileRevealRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.RevealResult
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.domain.song.usecase.DeleteSongsUseCase
import com.lonx.lyrico.domain.song.usecase.PatchSongTagsUseCase
import com.lonx.lyrico.domain.song.usecase.SaveAudioTagsResult
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.album_delete_success
import com.lonx.lyrico.resources.album_replay_gain_success
import com.lonx.lyrico.resources.album_replay_gain_write_failed
import com.lonx.lyrico.resources.no_player_found
import com.lonx.lyrico.resources.replay_gain_calculate_cancelled
import com.lonx.lyrico.resources.replay_gain_error_codec_exception
import com.lonx.lyrico.resources.replay_gain_error_general
import com.lonx.lyrico.resources.replay_gain_error_no_audio_track
import com.lonx.lyrico.resources.replay_gain_error_unknown_mime_type
import com.lonx.lyrico.resources.replay_gain_error_zero_sample_count
import com.lonx.lyrico.resources.replay_gain_no_songs
import com.lonx.lyrico.resources.unknown_error
import com.lonx.lyrico.utils.AlbumReplayGainCalculateState
import com.lonx.lyrico.utils.ReplayGainError
import com.lonx.lyrico.utils.ReplayGainScanner
import com.lonx.lyrico.utils.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * The state the album long-press sheet and its ReplayGain progress sheet render from.
 *
 * Every field is one the Android sheet read: the album's name for the progress sheet's title, the
 * progress and the two counters it draws, whether a run is in flight (which is what swaps the abort
 * button for the close button and blocks dismissal), the elapsed time it shows once a run ends, and
 * whether the sheet is up at all.
 */
data class AlbumActionsUiState(
    val albumName: String? = null,
    val isCalculatingAlbumReplayGain: Boolean = false,
    val showAlbumReplayGainProgressDialog: Boolean = false,
    val albumReplayGainProgress: Float? = null,
    val albumReplayGainSongCount: Int = 0,
    val albumReplayGainWrittenCount: Int = 0,
    val albumReplayGainTotalTimeMillis: Long = 0
)

/**
 * The long-press actions for one album: share, delete, and calculate the album's ReplayGain.
 *
 * The ReplayGain half is Android's, with three desktop differences:
 *
 * * **`SaveAudioTagsResult.PermissionRequired` is gone.** Windows has no runtime permission to ask
 *   for; the desktop result type has only `Success` and `Failed`. A write that fails therefore
 *   reports through `album_replay_gain_write_failed` with the throwable's message, which on desktop
 *   is the honest thing to show (it names the file and the reason TagLib gave).
 * * **Two `ReplayGainError` branches are gone with their error types.** `UnsupportedCodec` and the
 *   ALAC special case existed because Android's `MediaMetadataRetriever` reported them separately;
 *   the desktop decoder reports everything it cannot decode as `CodecException` carrying ffmpeg's
 *   own words, so `replay_gain_error_codec_exception` gets that text and no caller loses information.
 * * **A superseded run can no longer write the state of the run that replaced it.** Android set the
 *   "calculating" state *and* the start timestamp inside the launched coroutine, and cancelled the
 *   previous job without checking which job was finishing, so two calls in the same frame could
 *   leave `isCalculating` false while a scan was still running (the sheet then offered "close"
 *   mid-run). The state is now set before the job starts -- which also means a run that is aborted
 *   before its coroutine ever gets a thread still reports zero elapsed time instead of a leftover
 *   timestamp from the run before it -- and the completion write is guarded by a token, which only
 *   suppresses writes from a job that has already been replaced.
 * * **`mapErrorToUiMessage` lost its `mimeType` parameter.** It was read only by the
 *   `UnsupportedCodec` branch, which is gone; the same is true of the `songs.isNotEmpty()` guard
 *   around the write-progress division, which the caller's empty-album return makes unreachable.
 *
 * What replaced Android's `Intent.ACTION_SEND_MULTIPLE`: **`shareAlbum(context, songs)` became
 * [shareAlbum] with no `Context`.** Android built an audio `ACTION_SEND_MULTIPLE` chooser; Windows
 * has no such hand-off, so "share" means "reveal in Explorer" -- [FileRevealRepository]'s `/select,`
 * call, which takes one path and therefore shows the folder with the first existing song of the
 * album selected. The result is handled exactly the way `SongSelectionViewModel.share` handles it,
 * including the copy: the same `no_player_found` / `unknown_error` strings Android used.
 *
 * `UiState`'s remaining change is type-only: `UiMessage.StringResource` became [UiMessage.Localized]
 * with a Compose-resources `StringResource` key, and it is resolved by the page (`UiMessage.resolve()`),
 * which is what lets these reports appear in the user's current language.
 */
class AlbumActionsViewModel(
    private val libraryIndexRepository: LibraryIndexRepository,
    private val deleteSongsUseCase: DeleteSongsUseCase,
    private val patchSongTagsUseCase: PatchSongTagsUseCase,
    private val replayGainScanner: ReplayGainScanner,
    private val settingsRepository: SettingsRepository,
    private val fileRevealRepository: FileRevealRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumActionsUiState())
    val uiState: StateFlow<AlbumActionsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiMessage>()
    val events: SharedFlow<UiMessage> = _events.asSharedFlow()

    private var replayGainJob: Job? = null
    private var replayGainStartedAt: Long = 0L

    /** Identifies the run that is allowed to write the finished state; see the class KDoc. */
    private var replayGainRun: Any? = null

    fun shareAlbum(albumId: Long) {
        viewModelScope.launch {
            shareAlbum(libraryIndexRepository.getSongsByAlbumId(albumId))
        }
    }

    fun shareAlbum(songs: List<SongEntity>) {
        if (songs.isEmpty()) return

        when (val result = fileRevealRepository.reveal(songs.map { it.path })) {
            is RevealResult.Revealed, RevealResult.NothingToReveal -> Unit
            is RevealResult.Failed ->
                sendMessage(Res.string.unknown_error, result.throwable.message.orEmpty())

            is RevealResult.FilesUnavailable -> sendMessage(Res.string.no_player_found)
        }
    }

    fun deleteAlbum(albumId: Long) {
        viewModelScope.launch {
            deleteAlbum(libraryIndexRepository.getSongsByAlbumId(albumId))
        }
    }

    fun deleteAlbum(songs: List<SongEntity>) {
        viewModelScope.launch {
            val result = deleteSongsUseCase(songs)
            _events.emit(
                UiMessage.Localized(
                    Res.string.album_delete_success,
                    result.deleted,
                    result.total
                )
            )
        }
    }

    /**
     * Measures the album as one programme and writes the album ReplayGain tags onto **every** song in
     * it, because an album's loudness is a property of the album and a player has to find it wherever
     * it opens one of the songs.
     */
    fun calculateAlbumReplayGain(albumId: Long) {
        viewModelScope.launch {
            calculateAlbumReplayGain(libraryIndexRepository.getSongsByAlbumId(albumId))
        }
    }

    fun calculateAlbumReplayGain(songs: List<SongEntity>) {
        if (_uiState.value.isCalculatingAlbumReplayGain) return
        if (songs.isEmpty()) {
            viewModelScope.launch {
                _events.emit(UiMessage.Localized(Res.string.replay_gain_no_songs))
            }
            return
        }

        replayGainJob?.cancel()
        val run = Any()
        replayGainRun = run
        replayGainStartedAt = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                isCalculatingAlbumReplayGain = true,
                showAlbumReplayGainProgressDialog = true,
                albumReplayGainProgress = 0f,
                albumReplayGainSongCount = songs.size,
                albumReplayGainWrittenCount = 0,
                albumReplayGainTotalTimeMillis = 0L,
                albumName = songs.firstOrNull()?.album
            )
        }

        replayGainJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val replayGainSettings = settingsRepository.getReplayGainSettings()
                replayGainScanner.analyzeAlbum(
                    songs.map { it.uri },
                    replayGainSettings.peakMode
                ).collect { state ->
                    when (state) {
                        is AlbumReplayGainCalculateState.Progress -> {
                            _uiState.update {
                                it.copy(albumReplayGainProgress = (state.percent * ANALYZE_PROGRESS_WEIGHT).coerceIn(0f, ANALYZE_PROGRESS_WEIGHT))
                            }
                        }
                        AlbumReplayGainCalculateState.Cancelled -> {
                            _events.emit(UiMessage.Localized(Res.string.replay_gain_calculate_cancelled))
                        }
                        is AlbumReplayGainCalculateState.Failed -> {
                            _events.emit(mapErrorToUiMessage(state.error))
                        }
                        is AlbumReplayGainCalculateState.Success -> {
                            writeAlbumReplayGain(songs, state, replayGainSettings.targetLoudness)
                        }
                    }
                }
            } finally {
                // A run that was replaced by a newer one must not report the newer run's progress as
                // finished (see the class KDoc); everything else -- including an aborted run -- still
                // reports its elapsed time, which is what the sheet shows once it stops.
                if (replayGainRun === run) {
                    val duration = System.currentTimeMillis() - replayGainStartedAt
                    _uiState.update {
                        it.copy(
                            isCalculatingAlbumReplayGain = false,
                            albumReplayGainProgress = it.albumReplayGainProgress ?: 0f,
                            albumReplayGainTotalTimeMillis = duration
                        )
                    }
                }
            }
        }
    }

    fun cancelAlbumReplayGain() {
        replayGainJob?.cancel()
        _uiState.update {
            it.copy(
                isCalculatingAlbumReplayGain = false,
                albumReplayGainProgress = 0f
            )
        }
        viewModelScope.launch {
            _events.emit(UiMessage.Localized(Res.string.replay_gain_calculate_cancelled))
        }
    }

    fun closeAlbumReplayGainProgressDialog() {
        if (_uiState.value.isCalculatingAlbumReplayGain) return
        _uiState.update { it.copy(showAlbumReplayGainProgressDialog = false) }
    }

    fun clearAlbumReplayGainProgressDialog() {
        if (_uiState.value.isCalculatingAlbumReplayGain || _uiState.value.showAlbumReplayGainProgressDialog) return
        _uiState.update {
            it.copy(
                albumReplayGainProgress = null,
                albumReplayGainSongCount = 0,
                albumReplayGainWrittenCount = 0,
                albumReplayGainTotalTimeMillis = 0L
            )
        }
    }

    private suspend fun writeAlbumReplayGain(
        songs: List<SongEntity>,
        state: AlbumReplayGainCalculateState.Success,
        targetLoudness: Double
    ) {
        val tagData = AudioTagData(
            replayGainAlbumGain = replayGainScanner.formatGain(state.analysis.loudnessLufs, targetLoudness),
            replayGainAlbumPeak = replayGainScanner.formatPeak(state.analysis.peak),
            replayGainReferenceLoudness = replayGainScanner.formatReferenceLoudness(targetLoudness)
        )

        var written = 0
        for (song in songs) {
            when (val result = patchSongTagsUseCase(song.uri, tagData)) {
                is SaveAudioTagsResult.Success -> {
                    written += 1
                    val writeProgress = WRITE_PROGRESS_WEIGHT * written.toFloat() / songs.size.toFloat()
                    _uiState.update {
                        it.copy(
                            albumReplayGainWrittenCount = written,
                            albumReplayGainProgress = (ANALYZE_PROGRESS_WEIGHT + writeProgress).coerceIn(0f, 1f)
                        )
                    }
                }
                is SaveAudioTagsResult.Failed -> {
                    _events.emit(
                        UiMessage.Localized(
                            Res.string.album_replay_gain_write_failed,
                            result.error.message ?: result.error::class.java.simpleName
                        )
                    )
                    return
                }
            }
        }

        _events.emit(
            UiMessage.Localized(
                Res.string.album_replay_gain_success,
                written
            )
        )
    }

    /**
     * The five `ReplayGainError` cases the desktop decoder can actually produce.
     *
     * Android matched seven: `UnsupportedCodec` and the ALAC case of `CodecException` described the
     * ways `MediaMetadataRetriever` refused a file. Desktop has one decoder and it reports what
     * ffmpeg said, so both of those collapse into `replay_gain_error_codec_exception` with the
     * decoder's own message -- which is strictly more informative than "this codec is not supported".
     */
    private fun mapErrorToUiMessage(error: ReplayGainError): UiMessage {
        return when (error) {
            is ReplayGainError.NoAudioTrack -> UiMessage.Localized(Res.string.replay_gain_error_no_audio_track)
            is ReplayGainError.UnknownMimeType -> UiMessage.Localized(Res.string.replay_gain_error_unknown_mime_type)
            is ReplayGainError.ZeroSampleCount -> UiMessage.Localized(Res.string.replay_gain_error_zero_sample_count)
            is ReplayGainError.CodecException -> UiMessage.Localized(Res.string.replay_gain_error_codec_exception, error.message ?: "")
            is ReplayGainError.GeneralException -> UiMessage.Localized(Res.string.replay_gain_error_general, error.message ?: "")
        }
    }

    private fun sendMessage(res: StringResource, vararg args: Any) {
        viewModelScope.launch {
            _events.emit(UiMessage.Localized(res, *args))
        }
    }

    private companion object {
        /** The analysis owns the first 90% of the bar; writing the tags to N files owns the last 10%. */
        const val ANALYZE_PROGRESS_WEIGHT = 0.9f
        const val WRITE_PROGRESS_WEIGHT = 0.1f
    }
}
