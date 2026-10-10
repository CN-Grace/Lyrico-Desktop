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
 * The long-press sheet for one album: share (reveal in Explorer) and delete.
 *
 * **One row is missing compared to Android.** The Android sheet opened with "Calculate ReplayGain",
 * wired to `AlbumActionsViewModel.calculateReplayGain`, `ReplayGainScanner`, and a second sheet
 * (`AlbumReplayGainProgressBottomSheet`) that streamed per-song progress. None of those three exist
 * in the ported tree yet -- `ReplayGainScanner` and `ReplayGainProgressBottomSheet` are still Java
 * tree files, and the scan needs an external `ffmpeg`-class loudness backend that P5 has to decide
 * about. Rather than render a row that opens nothing, the row, the `isCalculatingReplayGain` state
 * that only existed to disable it, and the `onCalculateReplayGain` callback are all absent. This is
 * a documented gap, not a silent one: the strings `menu_action_calculate_album_replay_gain` and
 * `album_replay_gain_progress_*` are untouched in the resources and the PLAN lists ReplayGain under
 * P5.
 *
 * The remaining two rows are the Android rows verbatim, including the destructive row's
 * `BasicComponentColors(error, disabledOnSecondaryVariant)`.
 */
@Composable
fun AlbumActionBottomSheet(
    show: Boolean,
    albumName: String,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
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
