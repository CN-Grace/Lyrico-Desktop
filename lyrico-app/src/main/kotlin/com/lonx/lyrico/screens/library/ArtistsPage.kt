package com.lonx.lyrico.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.data.model.ArtistSortBy
import com.lonx.lyrico.data.model.ArtistSortInfo
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.artist_list_title
import com.lonx.lyrico.resources.cd_search
import com.lonx.lyrico.resources.cd_sort
import com.lonx.lyrico.resources.empty_artists_title
import com.lonx.lyrico.resources.empty_library_index_summary
import com.lonx.lyrico.resources.pull_to_refresh
import com.lonx.lyrico.resources.refresh
import com.lonx.lyrico.resources.refresh_success
import com.lonx.lyrico.resources.refreshing
import com.lonx.lyrico.resources.release_to_refresh
import com.lonx.lyrico.resources.sort_ascending
import com.lonx.lyrico.resources.sort_descending
import com.lonx.lyrico.screens.SECTIONS_ASC
import com.lonx.lyrico.screens.SECTIONS_DESC
import com.lonx.lyrico.ui.components.CoverCandidate
import com.lonx.lyrico.ui.components.artist.ArtistListItem
import com.lonx.lyrico.ui.components.bar.AlphabetSideBar
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
import com.lonx.lyrico.ui.navigation.ArtistDetailDestination
import com.lonx.lyrico.ui.navigation.LocalSearchDestination
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.navigation.SettingsDestination
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.viewmodel.ArtistLibraryViewModel
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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * The library's artists tab: a grid of artist rows that becomes two columns past 600.dp of width.
 *
 * The port is a straight one apart from the same three replacements `AlbumsPage` documents
 * (`InternalLazyVerticalGridScrollbar` -> [LibraryScrollbar], `Toast` -> nothing here because the
 * page has no actions that report, and `koinViewModel()` from Koin's Compose artifact instead of the
 * Android one), plus the `Uri` removal in the cover candidates:
 * `candidate.uri.toUri()` became the plain path string, because desktop cover candidates are
 * filesystem paths.
 *
 * The column count stays **local state**, exactly as on Android: the artist grid is the one list in
 * the app whose density is derived from the window (`BoxWithConstraints`, 600.dp breakpoint) rather
 * than from a saved setting, so `ArtistLibraryViewModel` has no `gridColumns` and `SettingsRepository`
 * has no artist equivalent of `albumGridColumns`. That asymmetry is inherited, not introduced.
 *
 * Clicking a row navigates to `ArtistDetailDestination`, whose screen comes in a later batch; the
 * route is declared and unregistered, so the navigator logs the miss (see `Destinations.kt`).
 */
@Composable
fun ArtistsPage(
    navigator: Navigator,
    modifier: Modifier = Modifier
) {
    val viewModel: ArtistLibraryViewModel = koinViewModel()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    var artistGridColumns by remember {
        mutableIntStateOf(1)
    }
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val artistCoverCandidates by viewModel.artistCoverCandidates.collectAsStateWithLifecycle()
    val sortInfo by viewModel.sortInfo.collectAsStateWithLifecycle()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val topBarBackdrop = rememberBarBlurBackdrop()
    val gridState = rememberLazyGridState()
    val alphabetScrollController = rememberAlphabetSideBarScrollController(gridState)

    val sections = remember(sortInfo.order) {
        if (sortInfo.order == SortOrder.ASC) SECTIONS_ASC else SECTIONS_DESC
    }
    val sectionIndexMap = remember(artists, sortInfo, artistGridColumns) {
        val map = mutableMapOf<String, Int>()

        if (sortInfo.sortBy.supportsIndex) {
            artists.forEachIndexed { index, artist ->
                if (!map.containsKey(artist.groupKey)) {
                    val rowStartIndex = index - index % artistGridColumns
                    map[artist.groupKey] = rowStartIndex
                }
            }
        }

        map
    }
    val enableIndex = artists.isNotEmpty() && sortInfo.sortBy.supportsIndex
    val refreshTexts = listOf(
        stringResource(Res.string.pull_to_refresh),
        stringResource(Res.string.release_to_refresh),
        stringResource(Res.string.refreshing),
        stringResource(Res.string.refresh_success)
    )
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            BlurredTopBar(backdrop = topBarBackdrop) {
                SmallTopAppBar(
                    title = formattedStringResource(Res.string.artist_list_title, artists.size),
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
                                artistSortDropdownEntry(sortInfo, viewModel::onSortChange),
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
            if (artists.isEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(scaffoldTopHorizontalPadding(paddingValues))
                        .fillMaxSize()
                ) {
                    LibraryEmptyState(
                        title = stringResource(Res.string.empty_artists_title),
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
                    contentPadding = PaddingValues(top = paddingValues.calculateTopPadding()),
                    topAppBarScrollBehavior = topAppBarScrollBehavior,
                    refreshTexts = refreshTexts
                ) {
                    BoxWithConstraints {
                        val targetColumns = if (maxWidth >= 600.dp) 2 else 1

                        LaunchedEffect(targetColumns) {
                            artistGridColumns = targetColumns
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(artistGridColumns),
                            modifier = Modifier
                                .scrollEndHaptic()
                                .overScrollVertical()
                                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                                .fillMaxHeight(),
                            state = gridState,
                            overscrollEffect = null,
                            contentPadding = scaffoldContentPadding(
                                paddingValues = paddingValues,
                                bottomExtra = LocalLibraryBottomContentPadding.current,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            items(
                                items = artists,
                                key = { it.id }
                            ) { artist ->
                                ArtistListItem(
                                    artist = artist,
                                    coverCandidates = artistCoverCandidates[artist.id]
                                        .orEmpty()
                                        .map { candidate ->
                                            CoverCandidate(
                                                uri = candidate.uri,
                                                lastUpdate = candidate.lastModified
                                            )
                                        },
                                    onClick = {
                                        navigator.navigate(
                                            ArtistDetailDestination(artistId = artist.id)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                if (!enableIndex) {
                    LibraryScrollbar(
                        state = gridState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .libraryScrollbarOverlay(paddingValues = paddingValues),
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
}

@Composable
private fun artistSortDropdownEntry(
    sortInfo: ArtistSortInfo,
    onSortChange: (ArtistSortInfo) -> Unit
): DropdownEntry {
    return DropdownEntry(
        items = ArtistSortBy.entries.map { sortBy ->
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
                        ArtistSortInfo(
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
