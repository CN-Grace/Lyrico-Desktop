package com.lonx.lyrico.screens.library

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.platform.DirectoryPicker
import com.lonx.lyrico.platform.rememberDirectoryPicker
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_add_folder
import com.lonx.lyrico.resources.cd_search
import com.lonx.lyrico.resources.cd_sort
import com.lonx.lyrico.resources.empty_library_index_summary
import com.lonx.lyrico.resources.empty_songs_title
import com.lonx.lyrico.resources.msg_copied_to_clipboard
import com.lonx.lyrico.resources.pull_to_refresh
import com.lonx.lyrico.resources.refresh
import com.lonx.lyrico.resources.refresh_success
import com.lonx.lyrico.resources.refreshing
import com.lonx.lyrico.resources.release_to_refresh
import com.lonx.lyrico.resources.song_list_title
import com.lonx.lyrico.resources.sort_ascending
import com.lonx.lyrico.resources.sort_descending
import com.lonx.lyrico.resources.swipe_selection_enter_selection
import com.lonx.lyrico.resources.swipe_selection_range_end
import com.lonx.lyrico.resources.swipe_selection_range_start
import com.lonx.lyrico.screens.SECTIONS_ASC
import com.lonx.lyrico.screens.SECTIONS_DESC
import com.lonx.lyrico.screens.TopBarState
import com.lonx.lyrico.ui.components.bar.AlphabetSideBar
import com.lonx.lyrico.ui.components.bar.SongSelectionTopAppBar
import com.lonx.lyrico.ui.components.bar.rememberAlphabetSideBarScrollController
import com.lonx.lyrico.ui.components.blur.BlurredTopBar
import com.lonx.lyrico.ui.components.blur.blurSource
import com.lonx.lyrico.ui.components.blur.rememberBarBlurBackdrop
import com.lonx.lyrico.ui.components.library.LibraryEmptyState
import com.lonx.lyrico.ui.components.library.LibraryScrollbar
import com.lonx.lyrico.ui.components.library.LocalLibraryBottomContentPadding
import com.lonx.lyrico.ui.components.library.libraryOverlayInsets
import com.lonx.lyrico.ui.components.library.libraryScrollbarOverlay
import com.lonx.lyrico.ui.components.scaffoldContentPadding
import com.lonx.lyrico.ui.components.scaffoldTopHorizontalPadding
import com.lonx.lyrico.ui.components.song.LibraryScanProgressText
import com.lonx.lyrico.ui.components.song.SongActionSheets
import com.lonx.lyrico.ui.components.song.SongListEmptyState
import com.lonx.lyrico.ui.components.song.SongListItem
import com.lonx.lyrico.ui.components.song.SongListItemActions
import com.lonx.lyrico.ui.navigation.EditMetadataDestination
import com.lonx.lyrico.ui.navigation.LocalSearchDestination
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.navigation.SettingsDestination
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.viewmodel.SongListViewModel
import com.lonx.lyrico.viewmodel.SongSelectionViewModel
import com.lonx.lyrico.viewmodel.SortBy
import com.lonx.lyrico.viewmodel.SortInfo
import com.lonx.lyrico.viewmodel.SortOrder
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import java.awt.datatransfer.StringSelection
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * The library's songs tab: the first library page of the port, now hosted by the three-tab
 * `LibraryHomeScreen` shell.
 *
 * Four Android-only mechanisms are gone rather than shimmed:
 *
 * * **The Storage Access Framework folder picker.** `rememberLauncherForActivityResult` with
 *   `OpenDocumentTree`, the persistable-URI-permission dance and `UriUtils.getFileAbsolutePath` are
 *   all replaced by [DirectoryPicker], which hands back a real directory. `UriUtils` is not ported.
 * * **`LocalContext`.** It was only there to be passed to `SelectionViewModel.play(context, song)`
 *   (an `Intent` launcher) and to the SAF launcher. Playback is now
 *   [com.lonx.lyrico.data.repository.PlaybackRepository]'s system-association call.
 * * **`my.nanihadesuka.compose.InternalLazyColumnScrollbar`** -- Android-only, replaced by
 *   [LibraryScrollbar]. What that costs is documented there.
 * * **`koinActivityViewModel()`.** Desktop has no activity-scoped `ViewModelStore`, and it does not
 *   need one: the shell is a navigation destination, so this page's `koinViewModel()` resolves against
 *   the `library_home` `NavBackStackEntry` and every screen inside the shell shares that one store.
 *   That is the activity scope Android was emulating, so no view model is hoisted and this page keeps
 *   owning its instance through `koinViewModel()` -- the same call the other ported screens make. The
 *   shell itself resolves only `SongSelectionViewModel` (to drop the selection when a tab is clicked),
 *   which is the *same* instance this page observes.
 *
 * Three navigations target screens from later batches (settings, local search, edit metadata). They
 * are wired exactly as Android wired them; the routes resolve once those screens are registered, and
 * until then `Navigation.NavControllerNavigator` logs the miss instead of crashing (see
 * `Destinations.kt`).
 *
 * [directoryPicker] is nullable only because a composable default argument cannot call a composable:
 * production passes nothing and the body remembers the real one, a test passes a fake so the "add a
 * folder" path can be driven without a human clicking a native dialog.
 *
 * Sharing was a `Intent.ACTION_SEND` chooser on Android. Desktop passes the song to
 * `SongSelectionViewModel.share`, which reveals it in Explorer -- the behaviour chosen for this port
 * -- and the song-detail copy button writes the AWT clipboard and confirms with the same
 * "copied to clipboard" text Android toasted, using the Miuix snackbar `AppLogScreen` already uses.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SongsPage(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    directoryPicker: DirectoryPicker? = null,
) {
    val viewModel: SongListViewModel = koinViewModel()
    val selectionViewModel: SongSelectionViewModel = koinViewModel()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboard.current
    val snackbarHostState = remember { SnackbarHostState() }
    val copiedMessage = stringResource(Res.string.msg_copied_to_clipboard)

    val sortInfo by viewModel.sortInfo.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val isSelectionMode by selectionViewModel.isSelectionMode.collectAsState(initial = false)
    val selectedSongUris by selectionViewModel.selectedSongUris.collectAsState()
    val swipeAnchorUri by selectionViewModel.swipeAnchorUri.collectAsState(initial = null)
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
    val hasFolders by viewModel.hasFolders.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val alphabetScrollController = rememberAlphabetSideBarScrollController(listState)
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMenuSheet by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var selectedSong by remember { mutableStateOf<SongEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.clearSearch()
    }

    val scope = rememberCoroutineScope()
    val folderPicker = directoryPicker ?: rememberDirectoryPicker(
        title = stringResource(Res.string.action_add_folder)
    )
    val sectionIndexMap = remember(songs, sortInfo) {
        val map = mutableMapOf<String, Int>()
        if (sortInfo.sortBy.supportsIndex) {
            songs.forEachIndexed { index, song ->
                val key = song.sectionKey(sortInfo.sortBy)
                if (!map.containsKey(key)) {
                    map[key] = index
                }
            }
        }
        map
    }
    val sections = remember(sortInfo.order) {
        if (sortInfo.order == SortOrder.ASC) {
            SECTIONS_ASC
        } else {
            SECTIONS_DESC
        }
    }
    val enableIndex = sections.isNotEmpty() && sortInfo.sortBy.supportsIndex

    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val topBarBackdrop = rememberBarBlurBackdrop()
    val refreshTexts = listOf(
        stringResource(Res.string.pull_to_refresh),
        stringResource(Res.string.release_to_refresh),
        stringResource(Res.string.refreshing),
        stringResource(Res.string.refresh_success)
    )
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            BlurredTopBar(backdrop = topBarBackdrop) {
                val topBarState = when {
                    isSelectionMode -> TopBarState.Selection
                    else -> TopBarState.Default
                }

                AnimatedContent(
                    targetState = topBarState,
                    label = "TopBarAnimation",
                    transitionSpec = {
                        // Fade + a short vertical slide, with SizeTransform so a height difference
                        // between the two top bars animates instead of jumping.
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

                        (enter togetherWith exit).using(
                            SizeTransform(clip = false)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { state ->
                    when (state) {
                        TopBarState.Selection -> {
                            SongSelectionTopAppBar(
                                songs = songs,
                                selectedSongUris = selectedSongUris,
                                scrollBehavior = topAppBarScrollBehavior,
                                color = Color.Transparent,
                                applyInsets = false,
                                onSelectAll = selectionViewModel::selectAll,
                                onDeselectAll = selectionViewModel::deselectAll,
                                onClose = selectionViewModel::exitSelectionMode
                            )
                        }

                        TopBarState.Default -> {
                            SmallTopAppBar(
                                title = formattedStringResource(Res.string.song_list_title, songs.size),
                                color = Color.Transparent,
                                modifier = Modifier,
                                scrollBehavior = topAppBarScrollBehavior,
                                defaultWindowInsetsPadding = false,
                                navigationIcon = {
                                    IconButton(
                                        onClick = { navigator.navigate(SettingsDestination()) }
                                    ) {
                                        Icon(
                                            imageVector = MiuixIcons.Settings,
                                            contentDescription = null
                                        )
                                    }
                                },
                                actions = {
                                    IconButton(onClick = {
                                        navigator.navigate(LocalSearchDestination)
                                    }) {
                                        Icon(
                                            imageVector = MiuixIcons.Search,
                                            contentDescription = stringResource(Res.string.cd_search)
                                        )
                                    }
                                    val sortTypes = SortBy.entries.toList()
                                    val sortEntries = DropdownEntry(
                                        items = sortTypes.mapIndexed { _, sortBy ->
                                            val isSelected = sortInfo.sortBy == sortBy
                                            DropdownItem(
                                                text = stringResource(sortBy.labelRes),
                                                selected = isSelected,
                                                summary = if (isSelected) {
                                                    stringResource(
                                                        when (sortInfo.order) {
                                                            SortOrder.ASC -> Res.string.sort_ascending
                                                            SortOrder.DESC -> Res.string.sort_descending
                                                        }
                                                    )
                                                } else {
                                                    null
                                                },
                                                onClick = {
                                                    val newOrder = if (isSelected) {
                                                        if (sortInfo.order == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC
                                                    } else {
                                                        SortOrder.ASC
                                                    }
                                                    viewModel.onSortChange(
                                                        SortInfo(
                                                            sortBy,
                                                            newOrder
                                                        )
                                                    )
                                                }
                                            )
                                        }
                                    )
                                    OverlayIconDropdownMenu(
                                        entries = listOf(sortEntries),
                                    ) {
                                        Icon(
                                            imageVector = MiuixIcons.Sort,
                                            contentDescription = stringResource(Res.string.cd_sort)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blurSource(topBarBackdrop)
        ) {
            if (songs.isEmpty()) {
                val scanProgress = scanState.progress
                Box(
                    modifier = Modifier
                        .padding(scaffoldTopHorizontalPadding(paddingValues))
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        scanProgress != null -> {
                            LibraryScanProgressText(progress = scanProgress)
                        }

                        !hasFolders -> {
                            SongListEmptyState(
                                onAddFolder = {
                                    scope.launch {
                                        folderPicker.pick()?.let { folder ->
                                            viewModel.addFolderAndRefresh(folder.absolutePath)
                                        }
                                    }
                                }
                            )
                        }

                        else -> {
                            LibraryEmptyState(
                                title = stringResource(Res.string.empty_songs_title),
                                summary = stringResource(Res.string.empty_library_index_summary),
                                action = {
                                    TextButton(
                                        text = stringResource(Res.string.refresh),
                                        onClick = { viewModel.refreshSongs() },
                                        colors = MiuixButtonDefaults.textButtonColorsPrimary()
                                    )
                                }
                            )
                        }
                    }
                }
            } else {
                PullToRefresh(
                    isRefreshing = scanState.isScanning,
                    onRefresh = { viewModel.refreshSongs() },
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = paddingValues.calculateTopPadding()),
                    topAppBarScrollBehavior = topAppBarScrollBehavior,
                    refreshTexts = refreshTexts
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                            .fillMaxHeight(),
                        state = listState,
                        overscrollEffect = null,
                        contentPadding = scaffoldContentPadding(
                            paddingValues = paddingValues,
                            bottomExtra = LocalLibraryBottomContentPadding.current,
                        ),
                    ) {
                        items(
                            items = songs,
                            key = { song ->
                                song.uri.takeIf { it.isNotBlank() && it != "0" }
                                    ?: "song-${song.id}"
                            }
                        ) { song ->
                            SongListItem(
                                song = song,
                                modifier = Modifier.animateItem(),
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
                                trailingContent = {
                                    Box(modifier = Modifier.padding(end = 8.dp)) {
                                        SongListItemActions(
                                            isSelectionMode = isSelectionMode,
                                            isSelected = selectedSongUris.contains(song.uri),
                                            onToggleSelection = {
                                                selectionViewModel.toggleSelection(song.uri)
                                            },
                                            onShowMenu = {
                                                showMenuSheet = true
                                                selectedSong = song
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
            if (!enableIndex && songs.isNotEmpty()) {
                LibraryScrollbar(
                    state = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .libraryScrollbarOverlay(paddingValues = paddingValues),
                )
            }
            if (enableIndex && songs.isNotEmpty()) {
                AlphabetSideBar(
                    sections = sections,
                    sectionIndexMap = sectionIndexMap,
                    order = sortInfo.order,
                    scrollController = alphabetScrollController,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .libraryOverlayInsets(
                            paddingValues = paddingValues,
                            extraTop = 16.dp,
                            extraBottom = 16.dp,
                        )
                        .fillMaxHeight()
                )
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
                onPlay = { song ->
                    selectionViewModel.play(song)
                },
                onShare = { song ->
                    selectionViewModel.share(listOf(song))
                },
                onCopy = { text ->
                    scope.launch {
                        // Same AWT-backed multiplatform clipboard call `AppLogScreen` makes: the entry
                        // is a desktop Transferable, and in a headless test Compose resolves
                        // `LocalClipboard` to a no-op, so this stays safe to call there.
                        clipboardManager.setClipEntry(ClipEntry(StringSelection(text)))
                        snackbarHostState.showSnackbar(copiedMessage)
                    }
                },
                onDelete = { song ->
                    selectionViewModel.delete(song)
                },
                onRename = { song, newFileName ->
                    selectionViewModel.renameSong(song, newFileName)
                }
            )
        }
    }
}

private fun SongEntity.sectionKey(sortBy: SortBy): String {
    return when (sortBy) {
        SortBy.ARTISTS -> artistGroupKey
        SortBy.ALBUM -> albumGroupKey
        else -> titleGroupKey
    }
}
