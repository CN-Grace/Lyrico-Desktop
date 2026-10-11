package com.lonx.lyrico.ui.components.poster

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.label_artist_poster_add_to_folder
import com.lonx.lyrico.resources.label_artist_poster_add_to_folder_no_folder
import com.lonx.lyrico.resources.label_artist_poster_add_to_folder_summary
import com.lonx.lyrico.resources.label_artist_poster_embed_to_songs
import com.lonx.lyrico.resources.label_artist_poster_embed_to_songs_summary
import com.lonx.lyrico.resources.label_set_artist_poster
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * 点艺术家头像后选择本地图片的去向。
 *
 * 两个去向是两套不同的机制，不能合并：
 * - **内嵌**写进这位艺术家每一首歌的标签，随文件走，换设备也在；
 * - **海报文件夹**只写一个文件，不动音频，但要先有已授权的文件夹（没有时点进去会先带用户去添加）。
 *
 * 用户选完去向后，调用方再打开系统选图器选择本地图片。
 */
@Composable
fun ArtistPosterActionsSheet(
    show: Boolean,
    hasPosterFolder: Boolean,
    onEmbedToSongs: () -> Unit,
    onSaveToFolder: () -> Unit,
    onDismissRequest: () -> Unit
) {
    WindowBottomSheet(
        show = show,
        enableNestedScroll = false,
        title = stringResource(Res.string.label_set_artist_poster),
        onDismissRequest = onDismissRequest
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 32.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Card(
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
            ) {
                ArrowPreference(
                    title = stringResource(Res.string.label_artist_poster_embed_to_songs),
                    summary = stringResource(Res.string.label_artist_poster_embed_to_songs_summary),
                    onClick = onEmbedToSongs
                )
                ArrowPreference(
                    title = stringResource(Res.string.label_artist_poster_add_to_folder),
                    summary = if (hasPosterFolder) {
                        stringResource(Res.string.label_artist_poster_add_to_folder_summary)
                    } else {
                        stringResource(Res.string.label_artist_poster_add_to_folder_no_folder)
                    },
                    onClick = onSaveToFolder
                )
            }
        }
    }
}
