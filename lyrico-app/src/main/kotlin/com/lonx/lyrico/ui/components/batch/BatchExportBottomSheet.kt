package com.lonx.lyrico.ui.components.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_abort
import com.lonx.lyrico.resources.action_batch_export_cover
import com.lonx.lyrico.resources.action_batch_export_lyrics
import com.lonx.lyrico.resources.action_close
import com.lonx.lyrico.resources.batch_export_running
import com.lonx.lyrico.resources.batch_replay_gain_failure
import com.lonx.lyrico.resources.batch_replay_gain_skipped
import com.lonx.lyrico.resources.batch_replay_gain_success
import com.lonx.lyrico.resources.batch_replay_gain_total_time
import com.lonx.lyrico.ui.components.base.ActionBottomSheet
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.viewmodel.BatchExportUiState
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BatchExportBottomSheet(
    batchExportUiState: BatchExportUiState,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    onAbort: () -> Unit
) {
    val title = when (batchExportUiState.taskType) {
        BatchTaskType.EXPORT_COVER -> stringResource(Res.string.action_batch_export_cover)
        else -> stringResource(Res.string.action_batch_export_lyrics)
    }

    ActionBottomSheet(
        show = batchExportUiState.showProgressDialog,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        allowDismiss = !batchExportUiState.isRunning,
        title = title,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Column(
                    modifier = Modifier.padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    batchExportUiState.progress?.let { (current, total) ->
                        val progress =
                            if (total > 0) current.toFloat() / total.toFloat() else 0f

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (batchExportUiState.isRunning) {
                                    stringResource(Res.string.batch_export_running)
                                } else {
                                    formattedStringResource(
                                        Res.string.batch_replay_gain_total_time,
                                        batchExportUiState.totalTimeMillis / 1000.0
                                    )
                                },
                                style = MiuixTheme.textStyles.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Text(
                                text = "$current / $total",
                                style = MiuixTheme.textStyles.main,
                                textAlign = TextAlign.End
                            )
                        }

                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formattedStringResource(
                            Res.string.batch_replay_gain_success,
                            batchExportUiState.successCount
                        ),
                        style = MiuixTheme.textStyles.main
                    )
                    Text(
                        text = formattedStringResource(
                            Res.string.batch_replay_gain_skipped,
                            batchExportUiState.skippedCount
                        ),
                        style = MiuixTheme.textStyles.main
                    )
                    Text(
                        text = formattedStringResource(
                            Res.string.batch_replay_gain_failure,
                            batchExportUiState.failureCount
                        ),
                        style = MiuixTheme.textStyles.main
                    )
                }
            }
            Spacer(modifier = Modifier.padding(vertical = 12.dp))
        },
        endAction = {
            TextButton(
                colors = ButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MiuixTheme.colorScheme.primary,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = MiuixTheme.colorScheme.disabledPrimary
                ),
                onClick = {
                    if (batchExportUiState.isRunning) {
                        onAbort()
                    } else {
                        onDismissRequest()
                    }
                }
            ) {
                Text(
                    text = if (batchExportUiState.isRunning) {
                        stringResource(Res.string.action_abort)
                    } else {
                        stringResource(Res.string.action_close)
                    },
                    color = if (batchExportUiState.isRunning) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.primary
                    }
                )
            }
        }
    )
}
