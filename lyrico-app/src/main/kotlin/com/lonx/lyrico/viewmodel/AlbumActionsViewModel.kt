package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.entity.path
import com.lonx.lyrico.data.repository.FileRevealRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.RevealResult
import com.lonx.lyrico.domain.song.usecase.DeleteSongsUseCase
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.album_delete_success
import com.lonx.lyrico.resources.no_player_found
import com.lonx.lyrico.resources.unknown_error
import com.lonx.lyrico.utils.UiMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * The long-press actions for one album: share and delete.
 *
 * The Android class was 250 lines and most of it was ReplayGain -- `ReplayGainScanner`,
 * `AlbumReplayGainCalculateState`, a progress `UiState` with six fields, a write-back loop through
 * `PatchSongTagsUseCase`, and seven `ReplayGainError` mappings. **None of that is here.** The
 * scanner is not ported (it is still a Java tree file, and it depends on an external loudness
 * backend that P5 has to choose), so the ported class carries only what has a real implementation
 * behind it: `shareAlbum` and `deleteAlbum`. The KDoc of
 * `ui/components/library/AlbumActionBottomSheet.kt` states the same gap from the UI side and
 * `PLAN.md` lists ReplayGain under P5.
 *
 * What replaced Android's `Intent.ACTION_SEND_MULTIPLE`:
 *
 * * **`shareAlbum(context, songs)` became [shareAlbum] with no `Context`.** Android built an audio
 *   `ACTION_SEND_MULTIPLE` chooser; Windows has no such hand-off, so "share" means "reveal in
 *   Explorer" -- [FileRevealRepository]'s `/select,` call, which takes one path and therefore shows
 *   the folder with the first existing song of the album selected. The result is handled exactly the
 *   way `SongSelectionViewModel.share` handles it, including the copy: the same
 *   `no_player_found` / `unknown_error` strings Android used.
 * * **`UiState` is gone entirely.** The only state it held was the ReplayGain progress;
 *   `AlbumActionsUiState` had no other field that was read. Worse, its `albumName` field was written
 *   *only* while calculating ReplayGain, and the page never read the state at all. What is left is
 *   [events].
 * * **`UiMessage.StringResource` became [UiMessage.Localized]** with a Compose-resources
 *   `StringResource` key, and it is resolved by the page (`UiMessage.resolve()`), which is what lets
 *   the delete confirmation report in the user's current language.
 */
class AlbumActionsViewModel(
    private val libraryIndexRepository: LibraryIndexRepository,
    private val deleteSongsUseCase: DeleteSongsUseCase,
    private val fileRevealRepository: FileRevealRepository
) : ViewModel() {

    private val _events = MutableSharedFlow<UiMessage>()
    val events: SharedFlow<UiMessage> = _events.asSharedFlow()

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

    private fun sendMessage(res: StringResource, vararg args: Any) {
        viewModelScope.launch {
            _events.emit(UiMessage.Localized(res, *args))
        }
    }
}
