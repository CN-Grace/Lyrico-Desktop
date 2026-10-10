package com.lonx.lyrico.ui.components.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.album_detail_title
import com.lonx.lyrico.resources.menu_action_calculate_album_replay_gain
import com.lonx.lyrico.resources.menu_action_delete_album
import com.lonx.lyrico.resources.menu_action_delete_album_sub
import com.lonx.lyrico.resources.menu_action_share_album
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * The long-press sheet for one album: calculate its ReplayGain, share (reveal in Explorer), delete.
 *
 * The ReplayGain row is back. The C4 batch that ported this sheet left it out on purpose -- the
 * scanner behind it was still a Java tree file and needed a loudness backend, neither of which
 * existed yet -- and said so here. C6d ported that chain (ffmpeg sidecar + `ReplayGainScanner`), so the
 * row, the `isCalculatingReplayGain` state that exists to disable it while a run is in flight, and
 * the `onCalculateReplayGain` callback are all restored from the Android sheet, in Android's order.
 *
 * The row is disabled rather than removed while a measurement runs: a second tap would cancel the
 * run the progress sheet is showing.
 *
 * The other rows are the Android rows verbatim, including the destructive row's
 * `BasicComponentColors(error, disabledOnSecondaryVariant)`.
 */
@Composable
fun AlbumActionBottomSheet(
    show: Boolean,
    albumName: String,
    isCalculatingReplayGain: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onCalculateReplayGain: () -> Unit
) {
    WindowBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SmallTitle(
                text = albumName.ifBlank { stringResource(Res.string.album_detail_title) },
                insideMargin = PaddingValues(4.dp)
            )
            Card(
                modifier = Modifier.padding(bottom = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.secondaryContainer
                )
            ) {
                ArrowPreference(
                    title = stringResource(Res.string.menu_action_calculate_album_replay_gain),
                    onClick = {
                        if (!isCalculatingReplayGain) {
                            onCalculateReplayGain()
                        }
                    }
                )
                ArrowPreference(
                    title = stringResource(Res.string.menu_action_share_album),
                    onClick = onShare
                )
                ArrowPreference(
                    title = stringResource(Res.string.menu_action_delete_album),
                    summary = stringResource(Res.string.menu_action_delete_album_sub),
                    titleColor = BasicComponentColors(
                        MiuixTheme.colorScheme.error,
                        MiuixTheme.colorScheme.disabledOnSecondaryVariant
                    ),
                    onClick = onDelete
                )
            }
        }
    }
}
