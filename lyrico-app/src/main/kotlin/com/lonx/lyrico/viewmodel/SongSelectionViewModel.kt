package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.data.SharedSelectionManager
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.entity.path
import com.lonx.lyrico.data.repository.FileRevealRepository
import com.lonx.lyrico.data.repository.PlaybackRepository
import com.lonx.lyrico.data.repository.PlaybackResult
import com.lonx.lyrico.data.repository.RevealResult
import com.lonx.lyrico.domain.song.usecase.DeleteSongsUseCase
import com.lonx.lyrico.domain.song.usecase.RenameSongUseCase
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.no_player_found
import com.lonx.lyrico.resources.unknown_error
import com.lonx.lyrico.utils.UiMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

sealed class SongSelectionEvent {
    data class ShowMessage(val message: UiMessage) : SongSelectionEvent()
}

/**
 * Selection state plus the five things a selected song can be used for: play, share, delete, rename,
 * and leaving selection mode.
 *
 * Three Android mechanisms are gone, and none of them is emulated:
 *
 * - **`play(context, song)`** became `play(song)` returning nothing. Android needed a `Context` to
 *   build the `ACTION_VIEW` intent and took a `Uri`; desktop hands the *path* to
 *   [PlaybackRepository], which uses the system association (`Desktop.open`) and returns a
 *   [PlaybackResult] instead of drawing a `Toast` — so the failure is an event here and a snackbar in
 *   the UI.
 * - **`batchShare(context, songs)`** became [share]. There is no `ACTION_SEND_MULTIPLE` on Windows;
 *   "share" now reveals the first existing file in Explorer, which is also why it needs the repository
 *   rather than an `Intent`.
 * - **`Context` itself, and `treeUri`/`toUri()`** on the way: song identity is a Windows path
 *   everywhere, so `song.uri` is handed over as-is.
 *
 * The failure copy is deliberately the Android one, misleading parts included: Android's
 * `PlaybackRepositoryImpl` caught `ActivityNotFoundException` for *both* "no app handles this" and
 * "the file is gone", and reported `no_player_found` for both, while a real exception became
 * `unknown_error`. The desktop result type can tell those apart, but inventing new copy for
 * `FileUnavailable` would be a translation the Android user never saw, so both collapse into
 * `no_player_found` exactly as before.
 */
class SongSelectionViewModel(
    private val deleteSongsUseCase: DeleteSongsUseCase,
    private val renameSongUseCase: RenameSongUseCase,
    private val playbackRepository: PlaybackRepository,
    private val fileRevealRepository: FileRevealRepository,
    private val selectionManager: SharedSelectionManager
) : ViewModel() {

    val selectedSongUris = selectionManager.selectedUris
    val isSelectionMode = selectionManager.isSelectionMode
    val swipeAnchorUri = selectionManager.swipeAnchorUri

    private val _events = MutableSharedFlow<SongSelectionEvent>()
    val events = _events.asSharedFlow()

    fun toggleSelection(uri: String) {
        selectionManager.toggle(uri)
    }

    fun swipeSelect(song: SongEntity, visibleSongs: List<SongEntity>) {
        selectionManager.selectSwipeRange(
            uri = song.uri,
            visibleUris = visibleSongs.map { it.uri }
        )
    }

    fun exitSelectionMode() {
        selectionManager.exitSelectionMode()
    }

    fun deselectAll() {
        selectionManager.deselectAll()
    }

    fun selectAll(songs: List<SongEntity>) {
        selectionManager.selectAll(songs.map { it.uri }.toSet())
    }

    /**
     * Publishes the current selection to [SharedSelectionManager] for the screens that read it
     * (batch rename/edit, which are not ported yet) and reports whether there was anything to publish.
     */
    fun setSelectionUris(): Boolean {
        val selectedUris = selectedSongUris.value
        if (selectedUris.isEmpty()) return false

        selectionManager.setUris(selectedUris)
        return true
    }

    fun play(song: SongEntity) {
        when (val result = playbackRepository.open(song.path)) {
            PlaybackResult.Opened -> Unit
            is PlaybackResult.Failed ->
                sendMessage(Res.string.unknown_error, result.throwable.message.orEmpty())

            is PlaybackResult.FileUnavailable, PlaybackResult.Unsupported ->
                sendMessage(Res.string.no_player_found)
        }
    }

    fun delete(song: SongEntity) {
        viewModelScope.launch {
            deleteSongsUseCase(listOf(song))
        }
    }

    fun batchDelete(songs: List<SongEntity>) {
        val selectedUris = selectedSongUris.value
        val toDelete = songs.filter { it.uri in selectedUris }

        viewModelScope.launch {
            deleteSongsUseCase(toDelete)
            exitSelectionMode()
        }
    }

    /**
     * Reveals the first selected song in Explorer. Unselected songs in [songs] are ignored, as they
     * were on Android, so the action always operates on the selection rather than on what is on screen.
     */
    fun share(songs: List<SongEntity>) {
        val selectedUris = selectedSongUris.value
        val toShare = songs.filter { it.uri in selectedUris }
        if (toShare.isEmpty()) return

        when (val result = fileRevealRepository.reveal(toShare.map { it.path })) {
            is RevealResult.Revealed, RevealResult.NothingToReveal -> Unit
            is RevealResult.Failed ->
                sendMessage(Res.string.unknown_error, result.throwable.message.orEmpty())

            is RevealResult.FilesUnavailable -> sendMessage(Res.string.no_player_found)
        }
    }

    fun renameSong(song: SongEntity, newFileName: String) {
        viewModelScope.launch {
            renameSongUseCase(song, newFileName)
        }
    }

    private fun sendMessage(res: StringResource, vararg args: Any) {
        viewModelScope.launch {
            _events.emit(SongSelectionEvent.ShowMessage(UiMessage.Localized(res, *args)))
        }
    }
}
