package com.lonx.lyrico.ui.components.song


import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.resources.dialog_delete_file_content
import com.lonx.lyrico.resources.dialog_delete_file_title
import com.lonx.lyrico.resources.dialog_rename_title
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.ui.components.base.YesNoDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The per-song sheets: the action menu, the info sheet, and the delete/rename confirmations.
 *
 * Three Android mechanisms are gone rather than shimmed:
 *
 * - **The player picker.** Android built an `ACTION_VIEW` intent per installed player and offered them
 *   in [com.lonx.lyrico.ui.components.player.PlayerPickerBottomSheet]-shaped chooser. Windows has no
 *   in-process equivalent (PlaybackRepository documents the decision), so `onPlay` now hands the song
 *   straight to the caller and the system association decides.
 * - **Share.** Android sent `ACTION_SEND` with an `EXTRA_STREAM` `Uri` to the system share sheet. There
 *   is no such sheet here; "share" now means *show the file in Explorer*, which is the closest
 *   desktop equivalent of handing a file to another program, and the caller owns it (`onShare`).
 * - `Context`, `Intent`, `Uri` and `toUri()` as a consequence of the two above.
 */
@Composable
fun SongActionSheets(
    selectedSong: SongEntity?,
    showMenuSheet: Boolean,
    showDetailSheet: Boolean,
    showDeleteDialog: Boolean,
    showRenameDialog: Boolean,
    onDismissMenu: () -> Unit,
    onDismissMenuFinished: () -> Unit,
    onDismissDetail: () -> Unit,
    onDismissDelete: () -> Unit,
    onDismissRename: () -> Unit,
    onShowDetail: () -> Unit,
    onShowDelete: () -> Unit,
    onShowRename: () -> Unit,
    onPlay: (SongEntity) -> Unit,
    onShare: (SongEntity) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (SongEntity) -> Unit,
    onRename: (SongEntity, String) -> Unit
) {
    val song = selectedSong

    if (song != null) {
        SongMenuBottomSheet(
            show = showMenuSheet,
            song = song,
            onDismissRequest = onDismissMenu,
            onDismissFinished = onDismissMenuFinished,
            onPlay = {
                onDismissMenu()
                onPlay(song)
            },
            showInfo = onShowDetail,
            onDelete = onShowDelete,
            onRename = onShowRename,
            onShare = { onShare(song) }
        )

        SongDetailBottomSheet(
            show = showDetailSheet,
            song = song,
            onDismissRequest = onDismissDetail,
            onCopy = onCopy
        )

        YesNoDialog(
            title = stringResource(Res.string.dialog_delete_file_title),
            show = showDeleteDialog,
            summary = formattedStringResource(
                Res.string.dialog_delete_file_content,
                song.fileName
            ),
            onConfirm = {
                onDismissMenu()
                onDelete(song)
            },
            onDismissRequest = onDismissDelete
        )

        RenameSongDialog(
            show = showRenameDialog,
            song = song,
            onDismissRequest = onDismissRename,
            onConfirm = { newFileName ->
                onRename(song, newFileName)
            }
        )
    }
}

@Composable
private fun RenameSongDialog(
    show: Boolean,
    song: SongEntity,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val extensionDot = if (!song.fileExtension.isNullOrEmpty()) {
        ".${song.fileExtension}"
    } else {
        ""
    }

    val oldName = song.fileName.substringBeforeLast('.')

    var newName by remember(song.uri, song.fileName) {
        mutableStateOf(oldName)
    }

    YesNoDialog(
        title = stringResource(Res.string.dialog_rename_title),
        show = show,
        onDismissRequest = onDismissRequest,
        onConfirm = {
            val fullNewName = newName.trim() + extensionDot
            if (newName.isNotBlank() && fullNewName != song.fileName) {
                onConfirm(fullNewName)
            }
        },
        content = {
            TextField(
                value = newName,
                onValueChange = { newName = it },
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (extensionDot.isNotEmpty()) {
                        Text(
                            text = extensionDot,
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }
            )
        }
    )
}
