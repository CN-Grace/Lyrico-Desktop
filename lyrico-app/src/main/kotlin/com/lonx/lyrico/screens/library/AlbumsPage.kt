package com.lonx.lyrico.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.data.model.AlbumSortBy
import com.lonx.lyrico.data.model.AlbumSortInfo
import com.lonx.lyrico.data.model.entity.AlbumEntity
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.album_grid_columns_format
import com.lonx.lyrico.resources.album_list_title
import com.lonx.lyrico.resources.cd_search
import com.lonx.lyrico.resources.cd_sort
import com.lonx.lyrico.resources.dialog_delete_album_content
import com.lonx.lyrico.resources.dialog_delete_album_title
import com.lonx.lyrico.resources.empty_albums_title
import com.lonx.lyrico.resources.empty_library_index_summary
import com.lonx.lyrico.resources.pull_to_refresh
import com.lonx.lyrico.resources.refresh
import com.lonx.lyrico.resources.refresh_success
import com.lonx.lyrico.resources.refreshing
import com.lonx.lyrico.resources.release_to_refresh
import com.lonx.lyrico.resources.song_count
import com.lonx.lyrico.resources.sort_ascending
import com.lonx.lyrico.resources.sort_descending
import com.lonx.lyrico.screens.SECTIONS_ASC
import com.lonx.lyrico.screens.SECTIONS_DESC
import com.lonx.lyrico.ui.components.bar.AlphabetSideBar
import com.lonx.lyrico.ui.components.bar.rememberAlphabetSideBarScrollController
import com.lonx.lyrico.ui.components.base.YesNoDialog
import com.lonx.lyrico.ui.components.blur.BlurredTopBar
import com.lonx.lyrico.ui.components.blur.blurSource
import com.lonx.lyrico.ui.components.blur.rememberBarBlurBackdrop
import com.lonx.lyrico.ui.components.library.AlbumActionBottomSheet
import com.lonx.lyrico.ui.components.library.AlbumGridItem
import com.lonx.lyrico.ui.components.library.LibraryEmptyState
import com.lonx.lyrico.ui.components.library.LibraryScrollbar
import com.lonx.lyrico.ui.components.library.LocalLibraryBottomContentPadding
import com.lonx.lyrico.ui.components.library.libraryOverlayInsets
import com.lonx.lyrico.ui.components.library.libraryScrollbarOverlay
import com.lonx.lyrico.ui.components.library.rememberAlbumGridTextStyle
import com.lonx.lyrico.ui.components.scaffoldContentPadding
import com.lonx.lyrico.ui.components.scaffoldTopHorizontalPadding
import com.lonx.lyrico.ui.navigation.AlbumDetailDestination
import com.lonx.lyrico.ui.navigation.LocalSearchDestination
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.navigation.SettingsDestination
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.viewmodel.AlbumActionsViewModel
import com.lonx.lyrico.viewmodel.AlbumLibraryViewModel
import com.lonx.lyrico.viewmodel.SortOrder
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
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
 * The library's albums tab: a grid of album cards with the letter index on the trailing edge.
 *
 * The port keeps the page's structure and every user-visible string, and drops three Android
 * mechanisms:
 *
 * * **`InternalLazyVerticalGridScrollbar`** (Android-only library) became [LibraryScrollbar]'s
 *   `LazyGridState` overload. The range-selection mode that library offered is gone with it, the same
 *   reduction `SongsPage` documents.
 * * **`Toast` for the action results** became a Miuix [SnackbarHost], which is how every other ported
 *   screen reports a message; the messages themselves (`album_delete_success`, `no_player_found`,
 *   `unknown_error`) are the Android ones, resolved through `UiMessage.resolve()`.
 * * **`LocalContext`**, which existed only to build the share `Intent` and to make the `Toast`.
 *
 * The ReplayGain row and its progress sheet are absent, not stubbed: see
 * `ui/components/library/AlbumActionBottomSheet.kt` and `viewmodel/AlbumActionsViewModel.kt` for
 * why, and `PLAN.md` for the P5 entry.
 *
 * [sectionIndexMap] is the one place this page differs from `SongsPage` beyond the container type: a
 * grid needs the index to land on the first row that *contains* the section, so the stored position
 * is snapped back by `index % columns` instead of being the item's own index.
 *
 * Clicking a card navigates to `AlbumDetailDestination`, whose screen comes in a later batch; the
 * route is declared and unregistered, so the navigator logs the miss rather than crashing (see
 * `Destinations.kt`).
 */
@Composable
fun AlbumsPage(
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    val viewModel: AlbumLibraryViewModel = koinViewModel()
    val albumActionsViewModel: AlbumActionsViewModel = koinViewModel()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val sortInfo by viewModel.sortInfo.collectAsStateWithLifecycle()
    val albumGridColumns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val albumTextStyle = rememberAlbumGridTextStyle(albumGridColumns)
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val topBarBackdrop = rememberBarBlurBackdrop()
    val gridState = rememberLazyGridState()
    val alphabetScrollController = rememberAlphabetSideBarScrollController(gridState)
    var selectedAlbum by remember { mutableStateOf<AlbumEntity?>(null) }
    var showAlbumActionSheet by remember { mutableStateOf(false) }
    var showDeleteAlbumDialog by remember { mutableStateOf(false) }

    LaunchedEffect(albumActionsViewModel) {
        albumActionsViewModel.events.collect { message ->
            message.resolve()?.let { text -> snackbarHostState.showSnackbar(text) }
        }
    }

    val sections = remember(sortInfo.order) {
        if (sortInfo.order == SortOrder.ASC) SECTIONS_ASC else SECTIONS_DESC
    }
    val sectionIndexMap = remember(albums, sortInfo, albumGridColumns) {
        val map = mutableMapOf<String, Int>()
        val columns = albumGridColumns.coerceAtLeast(1)

        if (sortInfo.sortBy.supportsIndex) {
            albums.forEachIndexed { index, album ->
                if (!map.containsKey(album.groupKey)) {
                    val rowStartIndex = index - index % columns
                    map[album.groupKey] = rowStartIndex
                }
            }
        }
        map
    }
    val enableIndex = albums.isNotEmpty() && sortInfo.sortBy.supportsIndex
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
                SmallTopAppBar(
                    title = formattedStringResource(Res.string.album_list_title, albums.size),
                    color = Color.Transparent,
                    modifier = Modifier,
                    scrollBehavior = topAppBarScrollBehavior,
                    defaultWindowInsetsPadding = false,
                    navigationIcon = {
                        IconButton(onClick = { navigator.navigate(SettingsDestination()) }) {
                            Icon(
                                imageVector = MiuixIcons.Settings,
                                contentDescription = null
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { navigator.navigate(LocalSearchDestination) }) {
                            Icon(
                                imageVector = MiuixIcons.Search,
                                contentDescription = stringResource(Res.string.cd_search)
                            )
                        }
                        OverlayIconDropdownMenu(
                            entries = listOf(
                                albumGridColumnsDropdownEntry(
                                    columns = albumGridColumns,
                                    onColumnsChange = viewModel::setGridColumns
                                ),
                                albumSortDropdownEntry(sortInfo, viewModel::onSortChange),
                            )
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
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blurSource(topBarBackdrop)
        ) {
            if (albums.isEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(scaffoldTopHorizontalPadding(paddingValues))
                        .fillMaxSize()
                ) {
                    LibraryEmptyState(
                        title = stringResource(Res.string.empty_albums_title),
                        summary = stringResource(Res.string.empty_library_index_summary),
                        modifier = Modifier.align(Alignment.Center),
                        action = {
                            TextButton(
                                text = stringResource(Res.string.refresh),
                                onClick = { viewModel.refreshSongs() },
                                colors = MiuixButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                    )
                }
            } else {
                PullToRefresh(
                    isRefreshing = scanState.isScanning,
                    onRefresh = { viewModel.refreshSongs() },
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding() + 12.dp,
                    ),
                    topAppBarScrollBehavior = topAppBarScrollBehavior,
                    refreshTexts = refreshTexts
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(albumGridColumns),
                        state = gridState,
                        modifier = Modifier
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                            .fillMaxSize(),
                        contentPadding = scaffoldContentPadding(
                            paddingValues = paddingValues,
                            topExtra = 12.dp,
                            bottomExtra = 12.dp + LocalLibraryBottomContentPadding.current,
                            horizontalExtra = 12.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        overscrollEffect = null
                    ) {
                        items(
                            items = albums,
                            key = { it.id }
                        ) { album ->
                            AlbumGridItem(
                                albumName = album.name,
                                summary = buildAlbumSummary(
                                    songCountText = formattedStringResource(
                                        Res.string.song_count,
                                        album.songCount
                                    ),
                                    year = album.year
                                ),
                                coverUri = album.coverSongUri,
                                coverLastModified = album.coverSongLastModified,
                                titleStyle = albumTextStyle.title,
                                summaryStyle = albumTextStyle.summary,
                                titleMaxLines = albumTextStyle.titleMaxLines,
                                onClick = {
                                    navigator.navigate(AlbumDetailDestination(albumId = album.id))
                                },
                                onLongClick = {
                                    selectedAlbum = album
                                    showAlbumActionSheet = true
                                }
                            )
                        }
                    }
                }
                if (!enableIndex) {
                    LibraryScrollbar(
                        state = gridState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .libraryScrollbarOverlay(
                                paddingValues = paddingValues,
                                extraTop = 12.dp,
                                extraBottom = 12.dp,
                            ),
                    )
                }
                if (enableIndex) {
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
            }
        }
    }

    selectedAlbum?.let { album ->
        AlbumActionBottomSheet(
            show = showAlbumActionSheet,
            albumName = album.name,
            onDismissRequest = { showAlbumActionSheet = false },
            onDismissFinished = {
                if (!showAlbumActionSheet && !showDeleteAlbumDialog) {
                    selectedAlbum = null
                }
            },
            onShare = {
                showAlbumActionSheet = false
                albumActionsViewModel.shareAlbum(album.id)
            },
            onDelete = {
                showAlbumActionSheet = false
                showDeleteAlbumDialog = true
            }
        )

        YesNoDialog(
            title = stringResource(Res.string.dialog_delete_album_title),
            show = showDeleteAlbumDialog,
            summary = formattedStringResource(
                Res.string.dialog_delete_album_content,
                album.songCount,
                album.name
            ),
            onConfirm = {
                showDeleteAlbumDialog = false
                albumActionsViewModel.deleteAlbum(album.id)
                selectedAlbum = null
            },
            onDismissRequest = {
                showDeleteAlbumDialog = false
                selectedAlbum = null
            }
        )
    }
}

private fun buildAlbumSummary(
    songCountText: String,
    year: String?
): String {
    return listOfNotNull(
        songCountText,
        year?.takeIf { it.isNotBlank() }
    ).joinToString(" ")
}

@Composable
private fun albumGridColumnsDropdownEntry(
    columns: Int,
    onColumnsChange: (Int) -> Unit
): DropdownEntry {
    return DropdownEntry(
        items = listOf(2, 3, 4).map { count ->
            DropdownItem(
                text = formattedStringResource(Res.string.album_grid_columns_format, count),
                selected = columns == count,
                onClick = { onColumnsChange(count) }
            )
        }
    )
}

@Composable
private fun albumSortDropdownEntry(
    sortInfo: AlbumSortInfo,
    onSortChange: (AlbumSortInfo) -> Unit
): DropdownEntry {
    return DropdownEntry(
        items = AlbumSortBy.entries.map { sortBy ->
            val isSelected = sortInfo.sortBy == sortBy
            DropdownItem(
                text = stringResource(sortBy.labelRes),
                selected = isSelected,
                summary = if (isSelected) {
                    stringResource(
                        if (sortInfo.order == SortOrder.ASC) {
                            Res.string.sort_ascending
                        } else {
                            Res.string.sort_descending
                        }
                    )
                } else {
                    null
                },
                onClick = {
                    onSortChange(
                        AlbumSortInfo(
                            sortBy = sortBy,
                            order = if (isSelected && sortInfo.order == SortOrder.ASC) {
                                SortOrder.DESC
                            } else {
                                SortOrder.ASC
                            }
                        )
                    )
                }
            )
        }
    )
}
