package com.lonx.lyrico.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.platform.DirectoryPicker
import com.lonx.lyrico.screens.library.AlbumsPage
import com.lonx.lyrico.screens.library.ArtistsPage
import com.lonx.lyrico.screens.library.LibraryTab
import com.lonx.lyrico.screens.library.SongsPage
import com.lonx.lyrico.ui.components.LocalScaffoldIncludesStartPadding
import com.lonx.lyrico.ui.components.bar.SongBatchSelectionActions
import com.lonx.lyrico.ui.components.blur.LocalBarBlurEnabled
import com.lonx.lyrico.ui.components.blur.blurSource
import com.lonx.lyrico.ui.components.blur.rememberBarBlurBackdrop
import com.lonx.lyrico.ui.components.blur.rememberBarBlurEnabled
import com.lonx.lyrico.ui.components.library.LibraryBottomNavigationBar
import com.lonx.lyrico.ui.components.library.LibraryNavigationRail
import com.lonx.lyrico.ui.components.library.LocalLibraryBottomContentPadding
import com.lonx.lyrico.ui.components.scaffoldBottomPadding
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.viewmodel.SongListViewModel
import com.lonx.lyrico.viewmodel.SongSelectionViewModel
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.Scaffold

/**
 * The window width at which the shell swaps its bottom bar for a leading rail.
 *
 * Android decided this from the *height* (`maxHeight < 520.dp`, i.e. a phone on its side) because a
 * phone's bottom bar competes with the gesture area. A desktop window has no such constraint and a
 * 1180x780 default (see `Main.kt`), so the port decides from the *width* instead: below this the
 * three tabs fit under the content, at or above it they read better as a rail beside it. 840.dp is
 * the usual "expanded window" breakpoint, which also means **the default window opens with the
 * rail** -- that is the intended desktop layout, not a rendering accident.
 */
internal val LibraryHomeRailMinWidth: Dp = 840.dp

/**
 * The library shell: one tab per library page, with the tabs on the outside.
 *
 * This replaces Android's `LibraryHomeScreen`, which was the app's start route (`library_home`) and
 * is the desktop's start route again now that all three pages exist. The structure is deliberately
 * the Android one: a `HorizontalPager` that the tabs scroll programmatically (`userScrollEnabled =
 * false`, so the pages move only when a tab is clicked, and the page is faded/scaled by its distance
 * from the current page), and a `CompositionLocalProvider` per layout that tells the pages how much
 * room the bar takes and where the alphabet index and scrollbar may draw.
 *
 * Three Android-only pieces are gone rather than shimmed:
 *
 * * **`BackHandler`.** Android used it to close the batch menu, and to leave selection mode on the
 *   songs tab. `androidx.activity.compose` does not exist here and Compose Desktop has no system back
 *   gesture, so neither action has a key of its own. The batch menu closes by tapping the scrim
 *   `ExpandableFabMenu` draws over the window while it is open, and `SongSelectionTopAppBar`'s close
 *   button plus [selectTab] (which always exits selection mode) are how a user leaves selection mode;
 *   `PLAN.md` records the UX gap.
 * * **The floating / liquid-glass bottom bar** (`LibraryBlurBottomBar`, `FloatingBarEffect`). Both
 *   need Android 13's `RenderEffect`; the desktop only has the blurred-surface bar
 *   ([LibraryBottomNavigationBar]) and the rail. The two settings that chose it
 *   (`floatingBottomBarEnabled`, `floatingBarEffect`) are still stored and still round-trip through
 *   the settings screen -- they simply have no rendering effect on desktop, which `PLAN.md` records
 *   as a gap rather than hiding.
 * * **`koinActivityViewModel()`.** Android hoisted one `SongListViewModel` for all three tabs because
 *   the activity was the only scope wide enough. The desktop needs no hoist: each page's
 *   `koinViewModel()` resolves against the `library_home` `NavBackStackEntry`, so every screen inside
 *   this shell already shares one `ViewModelStore` for as long as the shell lives -- the entry *is*
 *   the activity-scope equivalent. That is also why [selectTab] can call `exitSelectionMode()` on a
 *   `SongSelectionViewModel` the songs page is observing at the same time.
 *
 * The batch-action FAB ([SongBatchSelectionActions]) is mounted exactly where Android mounted it --
 * inside the shell's own `Box`, above whichever layout branch is active, with
 * `show = isSelectionMode && selectedTab == LibraryTab.Songs` -- and its callbacks go to the same
 * `SongSelectionViewModel` the songs page uses. Two details differ from Android:
 *
 * * **The song list is not hoisted through the shell's caller.** Android passed the activity-scoped
 *   view model's list down; here the shell resolves its own `SongListViewModel` with `koinViewModel()`,
 *   which is the same instance the songs page resolves (see the `koinActivityViewModel` note above).
 *   The FAB only needs the list to hand the selected songs to the share and delete callbacks -- every
 *   row that acts on a song reads the *selection* from the view model -- so this adds a second
 *   collector of one `StateFlow`, not a second source of truth.
 * * **`bottomBarPadding` is one constant per branch.** Android measured the floating bar's own height
 *   plus the navigation-bar inset so the FAB would clear it. The desktop bar is the one `Scaffold`
 *   reports, so the bar branch passes `scaffoldBottomPadding(paddingValues)` and the rail branch
 *   `0.dp`, and the FAB is offset by that plus nothing else.
 *
 * The rail branch provides `LocalScaffoldIncludesStartPadding provides false` because the rail
 * already owns the leading edge; the bar branch keeps the default and instead reserves
 * `scaffoldBottomPadding` at the end of every list, so the translucent bar has the list underneath it
 * to sample while nothing important hides behind it.
 */
@Composable
fun LibraryHomeScreen(
    navigator: Navigator,
    directoryPicker: DirectoryPicker? = null,
) {
    val tabs = remember { LibraryTab.entries.toList() }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    // Only for `exitSelectionMode()`: the songs page holds the same instance, because both resolve
    // against this shell's `NavBackStackEntry`.
    val selectionViewModel: SongSelectionViewModel = koinViewModel()
    // Same reasoning: this is the songs page's instance, collected here for the FAB's song list.
    val songListViewModel: SongListViewModel = koinViewModel()
    val songs by songListViewModel.songs.collectAsStateWithLifecycle()
    val isSelectionMode by selectionViewModel.isSelectionMode.collectAsStateWithLifecycle()
    val selectedSongUris by selectionViewModel.selectedSongUris.collectAsStateWithLifecycle()
    var isFabMenuExpanded by remember { mutableStateOf(false) }
    var bottomBarPadding by remember { mutableStateOf(0.dp) }
    val barBlurEnabled = rememberBarBlurEnabled()
    val standardBottomBackdrop = rememberBarBlurBackdrop(enabled = barBlurEnabled)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= LibraryHomeRailMinWidth
        val selectedTab = tabs[pagerState.targetPage]

        fun selectTab(tab: LibraryTab) {
            scope.launch {
                selectionViewModel.exitSelectionMode()
                pagerState.animateScrollToPage(tab.ordinal)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (useNavigationRail) {
                SideEffect {
                    bottomBarPadding = 0.dp
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    LibraryNavigationRail(
                        tabs = tabs,
                        selectedTab = selectedTab,
                        onTabSelected = ::selectTab,
                    )
                    CompositionLocalProvider(
                        LocalScaffoldIncludesStartPadding provides false,
                        LocalLibraryBottomContentPadding provides 0.dp,
                        LocalBarBlurEnabled provides barBlurEnabled,
                    ) {
                        LibraryHomePager(
                            tabs = tabs,
                            pagerState = pagerState,
                            navigator = navigator,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            } else {
                Scaffold(
                    bottomBar = {
                        LibraryBottomNavigationBar(
                            tabs = tabs,
                            selectedTab = selectedTab,
                            onTabSelected = ::selectTab,
                            backdrop = standardBottomBackdrop,
                        )
                    },
                ) { paddingValues ->
                    SideEffect {
                        bottomBarPadding = scaffoldBottomPadding(paddingValues)
                    }

                    val layoutDirection = LocalLayoutDirection.current
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .blurSource(standardBottomBackdrop),
                    ) {
                        CompositionLocalProvider(
                            // The pager stays behind the bar so the backdrop can sample it;
                            // lists reserve this same space at their end instead.
                            LocalLibraryBottomContentPadding provides scaffoldBottomPadding(paddingValues),
                            LocalBarBlurEnabled provides barBlurEnabled,
                        ) {
                            LibraryHomePager(
                                tabs = tabs,
                                pagerState = pagerState,
                                navigator = navigator,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        start = paddingValues.calculateStartPadding(layoutDirection),
                                        end = paddingValues.calculateEndPadding(layoutDirection),
                                    ),
                            )
                        }
                    }
                }
            }

            SongBatchSelectionActions(
                navigator = navigator,
                songs = songs,
                show = isSelectionMode && selectedTab == LibraryTab.Songs,
                expanded = isFabMenuExpanded,
                selectedSongUris = selectedSongUris,
                modifier = Modifier.padding(bottom = bottomBarPadding),
                directoryPicker = directoryPicker,
                onExpandedChange = { isFabMenuExpanded = it },
                onSetSelectionUris = selectionViewModel::setSelectionUris,
                onBatchDelete = selectionViewModel::batchDelete,
                onBatchShare = selectionViewModel::share,
            )
        }
    }
}

@Composable
private fun LibraryHomePager(
    tabs: List<LibraryTab>,
    pagerState: PagerState,
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    HorizontalPager(
        state = pagerState,
        userScrollEnabled = false,
        modifier = modifier,
    ) { page ->
        val pageOffset = (
            pagerState.currentPage - page + pagerState.currentPageOffsetFraction
        ).coerceIn(-1f, 1f)
        val distance = pageOffset.absoluteValue
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 1f - distance * 0.02f
                    alpha = 1f - distance * 0.22f
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            when (tabs[page]) {
                LibraryTab.Songs -> SongsPage(navigator = navigator)
                LibraryTab.Artists -> ArtistsPage(navigator = navigator)
                LibraryTab.Albums -> AlbumsPage(navigator = navigator)
            }
        }
    }
}
