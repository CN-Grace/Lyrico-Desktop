package com.lonx.lyrico.ui.components.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_abort
import com.lonx.lyrico.resources.action_close
import com.lonx.lyrico.resources.album_replay_gain_calculating
import com.lonx.lyrico.resources.batch_replay_gain_success
import com.lonx.lyrico.resources.batch_replay_gain_total_time
import com.lonx.lyrico.ui.components.base.ActionBottomSheet
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.viewmodel.AlbumActionsUiState
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The progress sheet for "Calculate Album ReplayGain": a bar, the elapsed time, and how many songs
 * the album tags reached.
 *
 * Ported from Android with two adaptations, both forced by the ported tree rather than chosen:
 *
 * * **`R.string` became `Res.string`, and the two strings that take arguments go through
 *   [formattedStringResource].** On Compose Multiplatform 1.12 the library's own
 *   `stringResource(res, args)` overload does not run `String.format`, so `%.2f` in
 *   `batch_replay_gain_total_time` would reach the screen verbatim; see that helper's KDoc and
 *   `StringFormattingGuardTest`, which fails the build if the overload is used directly.
 * * **The title is `uiState.albumName`, not the page's selected album.** Android did the same: the
 *   action sheet is dismissed the moment the user taps the row, so by the time this sheet is on
 *   screen `selectedAlbum` is already null and the name has to come from the view model's state.
 *
 * Everything else is Android's, including the details that matter to a user mid-run: the sheet
 * cannot be dismissed while the measurement is running (`allowDismiss`, driven by the same flag that
 * swaps the abort button for the close button), and the bar is `albumReplayGainProgress` where the
 * analysis owns 0..0.9 and writing the tags to N files owns the rest.
 */
@Composable
fun AlbumReplayGainProgressBottomSheet(
    uiState: AlbumActionsUiState,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    onAbort: () -> Unit
) {
    ActionBottomSheet(
        show = uiState.showAlbumReplayGainProgressDialog,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        allowDismiss = !uiState.isCalculatingAlbumReplayGain,
        title = uiState.albumName,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val progress = uiState.albumReplayGainProgress ?: 0f
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            uiState.isCalculatingAlbumReplayGain -> stringResource(Res.string.album_replay_gain_calculating)
                            else -> formattedStringResource(
                                Res.string.batch_replay_gain_total_time,
                                uiState.albumReplayGainTotalTimeMillis / 1000.0
                            )
                        },
                        style = MiuixTheme.textStyles.subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MiuixTheme.textStyles.main,
                        textAlign = TextAlign.End
                    )
                }

                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth()
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formattedStringResource(
                                Res.string.batch_replay_gain_success,
                                uiState.albumReplayGainWrittenCount
                            ),
                            style = MiuixTheme.textStyles.main
                        )
                        Text(
                            text = "${uiState.albumReplayGainWrittenCount} / ${uiState.albumReplayGainSongCount}",
                            style = MiuixTheme.textStyles.main,
                            textAlign = TextAlign.End
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        },
        endAction = {
            TextButton(
                colors = ButtonColors(
                    containerColor = MiuixTheme.colorScheme.surface,
                    contentColor = MiuixTheme.colorScheme.primary,
                    disabledContainerColor = MiuixTheme.colorScheme.surface,
                    disabledContentColor = MiuixTheme.colorScheme.disabledPrimary
                ),
                onClick = {
                    if (uiState.isCalculatingAlbumReplayGain) {
                        onAbort()
                    } else {
                        onDismissRequest()
                    }
                }
            ) {
                Text(
                    text = when {
                        uiState.isCalculatingAlbumReplayGain -> stringResource(Res.string.action_abort)
                        else -> stringResource(Res.string.action_close)
                    },
                    color = when {
                        uiState.isCalculatingAlbumReplayGain -> MiuixTheme.colorScheme.error
                        else -> MiuixTheme.colorScheme.primary
                    }
                )
            }
        }
    )
}
