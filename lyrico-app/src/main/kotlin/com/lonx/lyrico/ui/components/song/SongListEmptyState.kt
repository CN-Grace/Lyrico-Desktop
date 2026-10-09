package com.lonx.lyrico.ui.components.song

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_add_folder
import com.lonx.lyrico.resources.song_list_empty_desc
import com.lonx.lyrico.resources.song_list_empty_title
import com.lonx.lyrico.ui.components.library.LibraryEmptyState
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults

@Composable
fun SongListEmptyState(
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    LibraryEmptyState(
        title = stringResource(Res.string.song_list_empty_title),
        summary = stringResource(Res.string.song_list_empty_desc),
        modifier = modifier,
        action = {
            top.yukonga.miuix.kmp.basic.TextButton(
                text = stringResource(Res.string.action_add_folder),
                onClick = onAddFolder,
                colors = MiuixButtonDefaults.textButtonColorsPrimary()
            )
        }
    )
}
