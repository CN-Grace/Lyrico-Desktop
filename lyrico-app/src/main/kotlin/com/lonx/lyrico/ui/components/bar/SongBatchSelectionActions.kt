package com.lonx.lyrico.ui.components.bar

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.ExportDestination
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.platform.DirectoryPicker
import com.lonx.lyrico.platform.rememberDirectoryPicker
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_batch_export_cover
import com.lonx.lyrico.resources.action_batch_export_lyrics
import com.lonx.lyrico.resources.action_batch_match_cover
import com.lonx.lyrico.resources.action_batch_match_lyrics
import com.lonx.lyrico.resources.action_batch_match_metadata
import com.lonx.lyrico.resources.action_batch_rename
import com.lonx.lyrico.resources.action_batch_replay_gain
import com.lonx.lyrico.resources.action_delete
import com.lonx.lyrico.resources.action_share
import com.lonx.lyrico.resources.batch_edit_title
import com.lonx.lyrico.resources.dialog_batch_delete_content
import com.lonx.lyrico.resources.lyrics_convert
import com.lonx.lyrico.resources.lyrics_organize
import com.lonx.lyrico.ui.components.base.ExportDestinationBottomSheet
import com.lonx.lyrico.ui.components.base.YesNoDialog
import com.lonx.lyrico.ui.components.batch.BatchExportBottomSheet
import com.lonx.lyrico.ui.components.batch.BatchLyricsFormatBottomSheet
import com.lonx.lyrico.ui.components.batch.BatchLyricsFormatConfigBottomSheet
import com.lonx.lyrico.ui.components.batch.BatchMatchBottomSheet
import com.lonx.lyrico.ui.components.batch.BatchMatchConfigBottomSheet
import com.lonx.lyrico.ui.components.batch.BatchRGBottomSheet
import com.lonx.lyrico.ui.components.batch.BatchRGConfigBottomSheet
import com.lonx.lyrico.ui.components.fab.ExpandableFabMenu
import com.lonx.lyrico.ui.components.fab.ExpandableFabMenuStyle
import com.lonx.lyrico.ui.components.fab.FabMenuItem
import com.lonx.lyrico.ui.components.lyrics.LyricsOrganizationMenu
import com.lonx.lyrico.ui.navigation.BatchEditDestination
import com.lonx.lyrico.ui.navigation.BatchRenameDestination
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.viewmodel.BatchExportViewModel
import com.lonx.lyrico.viewmodel.BatchLyricsFormatViewModel
import com.lonx.lyrico.viewmodel.BatchMatchType
import com.lonx.lyrico.viewmodel.BatchMatchViewModel
import com.lonx.lyrico.viewmodel.BatchReplayGainViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Share

/**
 * Everything the batch-selection FAB opens: the 12-row menu and the four batch task flows behind it
 * (match, ReplayGain, lyrics format, export), each of which is a config sheet followed by a progress
 * sheet.
 *
 * Ported from Android (`SongSelectionSupport.kt`), which mounted it inside the library shell's `Box`.
 * Four things changed, and only the first is a behaviour change:
 *
 * * **The "export to a chosen folder" row no longer asks the Storage Access Framework for a tree
 *   `Uri`.** Android launched `ActivityResultContracts.OpenDocumentTree`, took a persistable
 *   read/write permission on the returned tree and handed the `Uri` to the view model. A Windows app
 *   already has access to the folder the user picks, so the desktop seam is [DirectoryPicker] and the
 *   view model receives `directory.absolutePath` -- the same shape the songs page's "add folder" flow
 *   uses. The picker is a parameter ([directoryPicker], defaulting to the real Swing chooser) for the
 *   same reason it is one there: the export path can then be driven end to end by a test without a
 *   human clicking a native dialog.
 * * **`onBatchShare` takes no `Context`.** Android built an `Intent.ACTION_SEND` chooser; desktop
 *   `SongSelectionViewModel.share` reveals the selected songs in Explorer, which is the behaviour
 *   chosen for this port (see the songs page's KDoc). `Context` is not a thing here at all.
 * * **`navigator` is [Navigator], not `DestinationsNavigator`, and two of the twelve rows point at
 *   routes that are declared but not registered yet.** "Batch rename" and "Batch edit" open screens
 *   that arrive in later batches; the call sites are Android's verbatim and the navigator logs the
 *   miss instead of crashing (see [BatchRenameDestination]).
 * * **Strings come from `Res.string`, with the counted one going through
 *   [formattedStringResource]** -- the Compose Multiplatform `stringResource(res, args)` overload does
 *   not run `String.format` (see `StringFormattingGuardTest`).
 *
 * The four view models are resolved here rather than hoisted to the caller, exactly as on Android, so
 * the sheets and the FAB rows share one instance per task type. They are the same instances the
 * batch-task list screen resolves, because Koin's `viewModel` on this graph is scoped to the nav
 * back-stack entry the shell is mounted in.
 */
@Composable
fun BoxScope.SongBatchSelectionActions(
    navigator: Navigator,
    songs: List<SongEntity>,
    show: Boolean,
    expanded: Boolean,
    selectedSongUris: Set<String>,
    modifier: Modifier = Modifier,
    directoryPicker: DirectoryPicker? = null,
    onExpandedChange: (Boolean) -> Unit,
    onSetSelectionUris: () -> Boolean,
    onBatchDelete: (List<SongEntity>) -> Unit,
    onBatchShare: (List<SongEntity>) -> Unit
) {
    val batchMatchViewModel: BatchMatchViewModel = koinViewModel()
    val batchReplayGainViewModel: BatchReplayGainViewModel = koinViewModel()
    val batchLyricsFormatViewModel: BatchLyricsFormatViewModel = koinViewModel()
    val batchExportViewModel: BatchExportViewModel = koinViewModel()
    val batchMatchUiState by batchMatchViewModel.uiState.collectAsStateWithLifecycle()
    val batchReplayGainUiState by batchReplayGainViewModel.uiState.collectAsStateWithLifecycle()
    val batchLyricsFormatUiState by batchLyricsFormatViewModel.uiState.collectAsStateWithLifecycle()
    val batchExportUiState by batchExportViewModel.uiState.collectAsStateWithLifecycle()
    val matchTargets by batchMatchViewModel.visibleTargets.collectAsStateWithLifecycle()
    val batchMatchConfig by batchMatchViewModel.batchMatchConfig.collectAsStateWithLifecycle()
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showExportDestinationSheet by remember { mutableStateOf(false) }
    var pendingExportTaskType by remember { mutableStateOf<BatchTaskType?>(null) }
    val scope = rememberCoroutineScope()
    val exportFolderPicker = directoryPicker ?: rememberDirectoryPicker(
        title = stringResource(Res.string.action_batch_export_lyrics)
    )

    ExportDestinationBottomSheet(
        show = showExportDestinationSheet,
        onDismissRequest = {
            showExportDestinationSheet = false
            pendingExportTaskType = null
        },
        onConfirm = { destination ->
            val taskType = pendingExportTaskType ?: return@ExportDestinationBottomSheet
            showExportDestinationSheet = false
            when (destination) {
                ExportDestination.SELECTED_DIRECTORY -> {
                    pendingExportTaskType = null
                    scope.launch {
                        val directory = exportFolderPicker.pick() ?: return@launch
                        batchExportViewModel.setSelectionUris(selectedSongUris.toList())
                        batchExportViewModel.startBatchExport(
                            taskType = taskType,
                            destination = ExportDestination.SELECTED_DIRECTORY,
                            destinationDirectory = directory.absolutePath
                        )
                    }
                }

                ExportDestination.AUDIO_DIRECTORY -> {
                    pendingExportTaskType = null
                    batchExportViewModel.setSelectionUris(selectedSongUris.toList())
                    batchExportViewModel.startBatchExport(
                        taskType = taskType,
                        destination = ExportDestination.AUDIO_DIRECTORY
                    )
                }
            }
        }
    )

    YesNoDialog(
        show = showBatchDeleteDialog,
        onDismissRequest = { showBatchDeleteDialog = false },
        summary = formattedStringResource(
            Res.string.dialog_batch_delete_content,
            selectedSongUris.size
        ),
        onConfirm = {
            showBatchDeleteDialog = false
            onBatchDelete(songs)
        }
    )

    BatchMatchConfigBottomSheet(
        show = batchMatchUiState.showBatchConfigDialog,
        matchType = batchMatchUiState.matchType,
        initialConfig = batchMatchConfig,
        visibleTargets = matchTargets,
        onDismissRequest = { config ->
            batchMatchViewModel.saveBatchMatchConfig(config)
            batchMatchViewModel.closeBatchMatchConfig()
        },
        onConfirm = { config ->
            batchMatchViewModel.saveBatchMatchConfig(config)
            batchMatchViewModel.batchMatch(songs, config)
        }
    )

    BatchMatchBottomSheet(
        onDismissRequest = {
            if (!batchMatchUiState.isRunning) batchMatchViewModel.closeBatchMatchDialog()
        },
        onDismissFinished = {
            if (!batchMatchUiState.isRunning) batchMatchViewModel.closeBatchMatchDialog()
        },
        enableNestedScroll = false,
        uiState = batchMatchUiState,
        onAbort = { batchMatchViewModel.abortBatchMatch() }
    )

    BatchRGConfigBottomSheet(
        show = batchReplayGainUiState.showConfigDialog,
        initialConcurrency = batchReplayGainUiState.concurrency,
        onDismissRequest = { concurrency ->
            batchReplayGainViewModel.setConcurrency(concurrency)
            batchReplayGainViewModel.closeReplayGainConfig()
        },
        onConfirm = { concurrency ->
            batchReplayGainViewModel.setConcurrency(concurrency)
            batchReplayGainViewModel.startBatchScan()
        }
    )

    BatchRGBottomSheet(
        batchReplayGainUiState = batchReplayGainUiState,
        onDismissRequest = { batchReplayGainViewModel.closeProgressDialog() },
        onAbort = { batchReplayGainViewModel.abortBatchScan() },
        onDismissFinished = {
            if (batchReplayGainUiState.isSuccess) {
                batchReplayGainViewModel.closeProgressDialog()
            }
        }
    )

    BatchLyricsFormatConfigBottomSheet(
        state = batchLyricsFormatUiState,
        onDismiss = batchLyricsFormatViewModel::closeConfig,
        onFormat = batchLyricsFormatViewModel::setTargetFormat,
        onConcurrency = batchLyricsFormatViewModel::setConcurrency,
        onTwoColumnMapping = batchLyricsFormatViewModel::setTwoColumnMapping,
        onThreeColumnMapping = batchLyricsFormatViewModel::setThreeColumnMapping,
        onConfirm = batchLyricsFormatViewModel::startBatchConvert
    )

    BatchLyricsFormatBottomSheet(
        batchLyricsFormatUiState = batchLyricsFormatUiState,
        onDismissRequest = {
            if (!batchLyricsFormatUiState.isRunning) {
                batchLyricsFormatViewModel.closeProgressDialog()
            }
        },
        onDismissFinished = {
            if (batchLyricsFormatUiState.isSuccess) {
                batchLyricsFormatViewModel.clearProgressDialog()
            }
        },
        onAbort = { batchLyricsFormatViewModel.abortBatchConvert() }
    )

    BatchExportBottomSheet(
        batchExportUiState = batchExportUiState,
        onDismissRequest = {
            if (!batchExportUiState.isRunning) {
                batchExportViewModel.closeProgressDialog()
            }
        },
        onDismissFinished = {
            if (batchExportUiState.isSuccess) {
                batchExportViewModel.clearProgressDialog()
            }
        },
        onAbort = { batchExportViewModel.abortBatchExport() }
    )

    ExpandableFabMenu(
        visible = show,
        expanded = expanded,
        enabled = selectedSongUris.isNotEmpty(),
        modifier = modifier,
        style = ExpandableFabMenuStyle.default().copy(
            mainIcon = MiuixIcons.Add
        ),
        itemCount = 12,
        onExpandedChange = onExpandedChange
    ) {
        FabMenuItem(
            label = stringResource(Res.string.action_batch_replay_gain),
            icon = MiuixIcons.Edit,
            onClick = {
                onExpandedChange(false)
                batchReplayGainViewModel.setSelectionUris(selectedSongUris.toList())
                batchReplayGainViewModel.openReplayGainConfig()
            }
        )

        FabMenuItem(
            label = stringResource(Res.string.lyrics_convert),
            icon = MiuixIcons.Edit,
            onClick = {
                onExpandedChange(false)
                batchLyricsFormatViewModel.setSelectionUris(selectedSongUris.toList())
                batchLyricsFormatViewModel.openConfig(batchReplayGainUiState.concurrency)
            }
        )

        LyricsOrganizationMenu(onSelect = { operation ->
            onExpandedChange(false)
            batchLyricsFormatViewModel.setSelectionUris(selectedSongUris.toList())
            batchLyricsFormatViewModel.openConfig(batchReplayGainUiState.concurrency, operation)
        }) { open ->
            FabMenuItem(label = stringResource(Res.string.lyrics_organize), icon = MiuixIcons.Edit, onClick = open)
        }

        FabMenuItem(
            label = stringResource(Res.string.action_batch_export_lyrics),
            icon = MiuixIcons.Share,
            onClick = {
                onExpandedChange(false)
                pendingExportTaskType = BatchTaskType.EXPORT_LYRICS
                showExportDestinationSheet = true
            }
        )

        FabMenuItem(
            label = stringResource(Res.string.action_batch_export_cover),
            icon = MiuixIcons.Share,
            onClick = {
                onExpandedChange(false)
                pendingExportTaskType = BatchTaskType.EXPORT_COVER
                showExportDestinationSheet = true
            }
        )

        FabMenuItem(
            label = stringResource(Res.string.action_batch_rename),
            icon = MiuixIcons.Rename,
            onClick = {
                onExpandedChange(false)
                if (onSetSelectionUris()) {
                    navigator.navigate(BatchRenameDestination)
                }
            }
        )

        FabMenuItem(
            label = stringResource(Res.string.batch_edit_title),
            icon = MiuixIcons.Edit,
            onClick = {
                onExpandedChange(false)
                if (onSetSelectionUris()) {
                    navigator.navigate(BatchEditDestination())
                }
            }
        )

        FabMenuItem(
            label = stringResource(Res.string.action_batch_match_cover),
            icon = MiuixIcons.Edit,
            onClick = {
                onExpandedChange(false)
                if (onSetSelectionUris()) {
                    batchMatchViewModel.openBatchMatchConfig(BatchMatchType.COVER)
                }
            }
        )

        FabMenuItem(
            label = stringResource(Res.string.action_batch_match_lyrics),
            icon = MiuixIcons.Edit,
            onClick = {
                onExpandedChange(false)
                if (onSetSelectionUris()) {
                    batchMatchViewModel.openBatchMatchConfig(BatchMatchType.LYRICS)
                }
            }
        )

        FabMenuItem(
            label = stringResource(Res.string.action_batch_match_metadata),
            icon = MiuixIcons.Edit,
            onClick = {
                onExpandedChange(false)
                if (onSetSelectionUris()) {
                    batchMatchViewModel.openBatchMatchConfig(BatchMatchType.METADATA)
                }
            }
        )

        FabMenuItem(
            label = stringResource(Res.string.action_delete),
            icon = MiuixIcons.Delete,
            onClick = {
                onExpandedChange(false)
                showBatchDeleteDialog = true
            }
        )

        FabMenuItem(
            label = stringResource(Res.string.action_share),
            icon = MiuixIcons.Share,
            onClick = {
                onExpandedChange(false)
                onBatchShare(songs)
            }
        )
    }
}
