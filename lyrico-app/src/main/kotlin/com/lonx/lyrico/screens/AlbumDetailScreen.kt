package com.lonx.lyrico.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.album_action_more
import com.lonx.lyrico.resources.album_detail_title
import com.lonx.lyrico.resources.dialog_delete_album_content
import com.lonx.lyrico.resources.dialog_delete_album_title
import com.lonx.lyrico.resources.menu_action_calculate_album_replay_gain
import com.lonx.lyrico.resources.menu_action_delete_album
import com.lonx.lyrico.resources.menu_action_share_album
import com.lonx.lyrico.resources.msg_copied_to_clipboard
import com.lonx.lyrico.resources.song_count
import com.lonx.lyrico.resources.swipe_selection_enter_selection
import com.lonx.lyrico.resources.swipe_selection_range_end
import com.lonx.lyrico.resources.swipe_selection_range_start
import com.lonx.lyrico.resources.unknown_album_artist
import com.lonx.lyrico.ui.components.bar.SongBatchSelectionActions
import com.lonx.lyrico.ui.components.bar.SongSelectionTopAppBar
import com.lonx.lyrico.ui.components.base.YesNoDialog
import com.lonx.lyrico.ui.components.cover.CoverImage
import com.lonx.lyrico.ui.components.library.AlbumReplayGainProgressBottomSheet
import com.lonx.lyrico.ui.components.scaffoldTopHorizontalPadding
import com.lonx.lyrico.ui.components.song.SongActionSheets
import com.lonx.lyrico.ui.components.song.SongListItem
import com.lonx.lyrico.ui.components.song.SongListItemActions
import com.lonx.lyrico.ui.navigation.EditMetadataDestination
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.viewmodel.AlbumActionsViewModel
import com.lonx.lyrico.viewmodel.AlbumDetailViewModel
import com.lonx.lyrico.viewmodel.SongSelectionViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.awt.datatransfer.StringSelection
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * The album detail page: the album's cover, name, artist and song count in a header, over its track
 * list, with the same selection, batch-action and per-song sheets the library's songs page uses.
 *
 * The port keeps Android's structure and every user-visible string. Five Android mechanisms are gone
 * rather than shimmed:
 *
 * * **`Toast` for the album-action reports** became a Miuix [SnackbarHost] fed by
 *   `UiMessage.resolve()`, which is how every other ported screen reports a message -- `AlbumsPage`
 *   collects the same `AlbumActionsViewModel.events` flow for the album actions it offers on a card.
 * * **`LocalContext`**, which existed only to build the `Toast` and to pass the album to
 *   `shareAlbum(context, songs)`. Sharing is "reveal in Explorer" on desktop and needs no `Context`
 *   (`AlbumActionsViewModel` documents the decision), so that call lost its first argument.
 * * **`BackHandler`.** Android used it to close the batch FAB menu and, otherwise, to leave selection
 *   mode. Compose Desktop has no system back gesture -- `LibraryHomeScreen`'s KDoc drops the same
 *   handler for the same reason -- so the FAB menu closes by tapping the scrim `ExpandableFabMenu`
 *   draws over the window, and `SongSelectionTopAppBar`'s close button is how a user leaves selection
 *   mode. `PLAN.md` records the resulting UX gap instead of hiding it.
 * * **`@Destination<RootGraph>`, the generated `EditMetadataDestination` and `DestinationsNavigator`.**
 *   The route is registered in `LyricoNavHost` and the screen takes the hand-written [Navigator];
 *   clicking a song navigates to `ui.navigation.EditMetadataDestination`, the same hand-written entry
 *   the library pages navigate to.
 * * **`koinActivityViewModel`-style hoisting.** `koinViewModel()` resolves against this route's
 *   `NavBackStackEntry`, so the `SongSelectionViewModel` this page observes is one instance for as long
 *   as the page lives -- the activity scope Android was emulating (the shell documents this).
 *
 * Two call sites differ because the components they call were ported earlier:
 *
 * * `SongActionSheets` takes `onShare` and `onCopy` (Android derived both from the `Context`: an
 *   `ACTION_SEND` intent, and a clipboard `ClipData`). Share is "reveal in Explorer" through
 *   `SongSelectionViewModel.share`, and copy is the AWT-backed `LocalClipboard` the songs page uses.
 * * `SongSelectionViewModel.batchShare` is [SongSelectionViewModel.share] here: on desktop the two
 *   Android share paths -- the per-song intent and the batch one -- became the same reveal call, so the
 *   batch FAB hands `onBatchShare` that method directly.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AlbumDetailScreen(
    navigator: Navigator,
    albumId: Long,
) {
    val viewModel: AlbumDetailViewModel = koinViewModel(
        parameters = { parametersOf(albumId) }
    )
    val selectionViewModel: SongSelectionViewModel = koinViewModel()
    val albumActionsViewModel: AlbumActionsViewModel = koinViewModel()
    val album by viewModel.album.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val albumActionsUiState by albumActionsViewModel.uiState.collectAsStateWithLifecycle()
    val albumName = album?.name.orEmpty()
    val albumArtist = album?.albumArtist?.takeIf { it.isNotBlank() }
        ?: songs.firstNotNullOfOrNull { song ->
            song.albumArtist?.trim()?.takeIf { it.isNotBlank() }
        }
    val isSelectionMode by selectionViewModel.isSelectionMode.collectAsStateWithLifecycle()
    val selectedSongUris by selectionViewModel.selectedSongUris.collectAsStateWithLifecycle()
    val swipeAnchorUri by selectionViewModel.swipeAnchorUri.collectAsStateWithLifecycle()
    val swipeSelectionLabel = stringResource(
        if (!isSelectionMode) {
            Res.string.swipe_selection_enter_selection
        } else if (swipeAnchorUri == null) {
            Res.string.swipe_selection_range_start
        } else {
            Res.string.swipe_selection_range_end
        }
    )
    val swipeSelectionSecondaryLabel = if (!isSelectionMode) {
        stringResource(Res.string.swipe_selection_range_start)
    } else {
        null
    }
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboard.current
    val snackbarHostState = remember { SnackbarHostState() }
    val copiedMessage = stringResource(Res.string.msg_copied_to_clipboard)
    val scope = rememberCoroutineScope()
    var isFabMenuExpanded by remember { mutableStateOf(false) }

    var selectedSong by remember { mutableStateOf<SongEntity?>(null) }
    var showMenuSheet by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteAlbumDialog by remember { mutableStateOf(false) }

    // Publishes what this list shows so the window-level ctrl+A selects these songs and not the whole
    // library; see SharedSelectionManager.publishVisibleUris.
    LaunchedEffect(songs) {
        selectionViewModel.publishVisibleSongs(songs)
    }

    // Android showed these through `Toast.makeText(context, message.asString(context), SHORT)`; the
    // message is resolved where it is displayed so it lands in the language the UI is showing now.
    LaunchedEffect(albumActionsViewModel) {
        albumActionsViewModel.events.collect { message ->
            message.resolve()?.let { text -> snackbarHostState.showSnackbar(text) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                AnimatedContent(
                    targetState = isSelectionMode,
                    label = "AlbumDetailTopBarAnimation",
                    transitionSpec = {
                        val animationDuration = 300
                        val enter = fadeIn(tween(animationDuration)) +
                                slideInVertically(
                                    animationSpec = tween(
                                        animationDuration,
                                        easing = FastOutSlowInEasing
                                    ),
                                    initialOffsetY = { -it / 3 }
                                )
                        val exit = fadeOut(tween(animationDuration)) +
                                slideOutVertically(
                                    animationSpec = tween(
                                        animationDuration,
                                        easing = FastOutSlowInEasing
                                    ),
                                    targetOffsetY = { -it / 3 }
                                )

                        (enter togetherWith exit).using(SizeTransform(clip = false))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { selectionMode ->
                    if (selectionMode) {
                        SongSelectionTopAppBar(
                            songs = songs,
                            selectedSongUris = selectedSongUris,
                            scrollBehavior = topAppBarScrollBehavior,
                            onSelectAll = selectionViewModel::selectAll,
                            onDeselectAll = selectionViewModel::deselectAll,
                            onClose = selectionViewModel::exitSelectionMode
                        )
                    } else {
                        SmallTopAppBar(
                            title = albumName.ifBlank { stringResource(Res.string.album_detail_title) },
                            navigationIcon = {
                                IconButton(onClick = { navigator.popBackStack() }) {
                                    Icon(
                                        imageVector = MiuixIcons.Back,
                                        contentDescription = stringResource(Res.string.action_back)
                                    )
                                }
                            },
                            actions = {
                                OverlayIconDropdownMenu(
                                    entries = listOf(
                                        albumActionsDropdownEntry(
                                            isCalculatingReplayGain = albumActionsUiState.isCalculatingAlbumReplayGain,
                                            onShare = {
                                                albumActionsViewModel.shareAlbum(songs)
                                            },
                                            onDelete = {
                                                showDeleteAlbumDialog = true
                                            },
                                            onCalculateReplayGain = {
                                                albumActionsViewModel.calculateAlbumReplayGain(songs)
                                            }
                                        )
                                    )
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.More,
                                        contentDescription = stringResource(Res.string.album_action_more)
                                    )
                                }
                            },
                            scrollBehavior = topAppBarScrollBehavior
                        )
                    }
                }
            }
        ) { paddingValues ->
            Box {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(scaffoldTopHorizontalPadding(paddingValues))
                ) {
                    AlbumDetailHeader(
                        album = albumName,
                        albumArtist = albumArtist,
                        songCount = songs.size,
                        coverSong = songs.firstOrNull()
                    )

                    LazyColumn(
                        modifier = Modifier
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                            .weight(1f),
                        state = listState,
                        contentPadding = PaddingValues(bottom = 12.dp),
                        overscrollEffect = null
                    ) {
                        items(
                            items = songs,
                            key = { song ->
                                song.uri.takeIf { it.isNotBlank() && it != "0" } ?: "song-${song.id}"
                            }
                        ) { song ->
                            SongListItem(
                                song = song,
                                showTrackNumbers = true,
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedSongUris.contains(song.uri),
                                swipeSelectionLabel = swipeSelectionLabel,
                                swipeSelectionSecondaryLabel = swipeSelectionSecondaryLabel,
                                onClick = {
                                    navigator.navigate(EditMetadataDestination(songFileUri = song.uri))
                                },
                                onToggleSelection = {
                                    selectionViewModel.toggleSelection(song.uri)
                                },
                                onSwipeSelection = {
                                    selectionViewModel.swipeSelect(song, songs)
                                },
                                onRangeSelection = {
                                    selectionViewModel.extendSelection(song, songs)
                                },
                                onSecondaryClick = {
                                    selectedSong = song
                                    showMenuSheet = true
                                },
                                trailingContent = {
                                    Box(modifier = Modifier.padding(end = 8.dp)) {
                                        SongListItemActions(
                                            isSelectionMode = isSelectionMode,
                                            isSelected = selectedSongUris.contains(song.uri),
                                            onToggleSelection = {
                                                selectionViewModel.toggleSelection(song.uri)
                                            },
                                            onShowMenu = {
                                                selectedSong = song
                                                showMenuSheet = true
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                SongActionSheets(
                    selectedSong = selectedSong,
                    showMenuSheet = showMenuSheet,
                    showDetailSheet = showDetailSheet,
                    showDeleteDialog = showDeleteDialog,
                    showRenameDialog = showRenameDialog,
                    onDismissMenu = { showMenuSheet = false },
                    onDismissMenuFinished = { selectedSong = null },
                    onDismissDetail = { showDetailSheet = false },
                    onDismissDelete = { showDeleteDialog = false },
                    onDismissRename = { showRenameDialog = false },
                    onShowDetail = { showDetailSheet = true },
                    onShowDelete = { showDeleteDialog = true },
                    onShowRename = { showRenameDialog = true },
                    onPlay = { song -> selectionViewModel.play(song) },
                    onShare = { song -> selectionViewModel.revealSong(song) },
                    onCopy = { text ->
                        scope.launch {
                            // The same AWT-backed clipboard call the songs page makes; in a headless
                            // test Compose resolves `LocalClipboard` to a no-op, so it stays safe.
                            clipboardManager.setClipEntry(ClipEntry(StringSelection(text)))
                            snackbarHostState.showSnackbar(copiedMessage)
                        }
                    },
                    onDelete = { song -> selectionViewModel.delete(song) },
                    onRename = { song, newFileName ->
                        selectionViewModel.renameSong(song, newFileName)
                    }
                )
            }
        }

        YesNoDialog(
            title = stringResource(Res.string.dialog_delete_album_title),
            show = showDeleteAlbumDialog,
            summary = formattedStringResource(
                Res.string.dialog_delete_album_content,
                songs.size,
                albumName.ifBlank { stringResource(Res.string.album_detail_title) }
            ),
            onConfirm = {
                showDeleteAlbumDialog = false
                albumActionsViewModel.deleteAlbum(songs)
                navigator.popBackStack()
            },
            onDismissRequest = { showDeleteAlbumDialog = false }
        )

        AlbumReplayGainProgressBottomSheet(
            uiState = albumActionsUiState,
            onDismissRequest = albumActionsViewModel::closeAlbumReplayGainProgressDialog,
            onDismissFinished = albumActionsViewModel::clearAlbumReplayGainProgressDialog,
            onAbort = albumActionsViewModel::cancelAlbumReplayGain
        )

        SongBatchSelectionActions(
            navigator = navigator,
            songs = songs,
            show = isSelectionMode,
            expanded = isFabMenuExpanded,
            selectedSongUris = selectedSongUris,
            onExpandedChange = { isFabMenuExpanded = it },
            onSetSelectionUris = selectionViewModel::setSelectionUris,
            onBatchDelete = selectionViewModel::batchDelete,
            // Android's `batchShare`; see the class KDoc -- both share paths became the reveal call.
            onBatchShare = selectionViewModel::share
        )
    }
}

@Composable
private fun albumActionsDropdownEntry(
    isCalculatingReplayGain: Boolean,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onCalculateReplayGain: () -> Unit
): DropdownEntry {
    return DropdownEntry(
        items = listOf(
            DropdownItem(
                text = stringResource(Res.string.menu_action_calculate_album_replay_gain),
                onClick = {
                    if (!isCalculatingReplayGain) {
                        onCalculateReplayGain()
                    }
                }
            ),
            DropdownItem(
                text = stringResource(Res.string.menu_action_share_album),
                onClick = onShare
            ),
            DropdownItem(
                text = stringResource(Res.string.menu_action_delete_album),
                onClick = onDelete
            )
        )
    )
}

@Composable
private fun AlbumDetailHeader(
    album: String,
    albumArtist: String?,
    songCount: Int,
    coverSong: SongEntity?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            uri = coverSong?.uri,
            lastModified = coverSong?.fileLastModified ?: 0L,
            modifier = Modifier.size(80.dp),
            shape = RoundedCornerShape(8.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.ifBlank { stringResource(Res.string.album_detail_title) },
                style = MiuixTheme.textStyles.title3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = albumArtist?.takeIf { it.isNotBlank() }
                    ?: stringResource(Res.string.unknown_album_artist),
                style = MiuixTheme.textStyles.main,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Android passed the count to the library's vararg `stringResource`; Compose Multiplatform
            // only substitutes positional placeholders there, so it goes through the project helper.
            Text(
                text = formattedStringResource(Res.string.song_count, songCount),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}
