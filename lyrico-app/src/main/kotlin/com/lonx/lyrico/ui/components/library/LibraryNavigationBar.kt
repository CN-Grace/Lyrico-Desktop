package com.lonx.lyrico.ui.components.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.library_tab_albums
import com.lonx.lyrico.resources.library_tab_artists
import com.lonx.lyrico.resources.library_tab_songs
import com.lonx.lyrico.screens.library.LibraryTab
import com.lonx.lyrico.ui.components.blur.BlurredBar
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Music

/**
 * The library shell's two navigation bars: the bottom bar and the wide-window rail.
 *
 * Android's file was `LibraryBottomNavigationBar.kt` and held three composables -- the bar, the rail,
 * and `LibraryBlurBottomBar`, the phone-only floating/liquid-glass bar. The desktop shell drops the
 * floating bar (and the `floatingBottomBarEnabled` / `floatingBarEffect` settings it read, see
 * `LibraryHomeScreen`'s KDoc), so this file takes a new name rather than pretending to be the old
 * trio; the floating bar's own building blocks are still in the Java tree
 * (`ui/components/library/ReferenceFloatingBar.kt`) for the record.
 *
 * Two Android-only lines are gone from both composables:
 *
 * * **`LocalHapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)`** on every tab click.
 *   Desktop has no haptics to fire; the same call was dropped from `SongListItem` for the same reason.
 *   The click itself is unchanged.
 * * **`stringResource(tab.titleRes)`'s `Int` argument** -- now a [org.jetbrains.compose.resources.StringResource],
 *   resolved with the Compose Multiplatform overload of the same name.
 *
 * [LibraryBottomNavigationBar] keeps its Android behaviour of painting a `BlurredBar` surface behind
 * the Miuix `NavigationBar` (a blurred copy of the pager when the user enabled bar blur, an opaque
 * surface otherwise) so the list keeps scrolling underneath it. The bar itself stays transparent, as
 * on Android: the surface belongs to the backdrop.
 */
@Composable
fun LibraryBottomNavigationBar(
    tabs: List<LibraryTab>,
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
) {
    BlurredBar(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth(),
    ) {
        NavigationBar(
            modifier = modifier,
            color = Color.Transparent,
        ) {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selectedTab,
                    onClick = { onTabSelected(tab) },
                    icon = tab.icon,
                    label = stringResource(tab.titleRes),
                )
            }
        }
    }
}

/** The same three tabs standing on the leading edge, for windows wide enough to afford it. */
@Composable
fun LibraryNavigationRail(
    tabs: List<LibraryTab>,
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier,
    ) {
        tabs.forEach { tab ->
            NavigationRailItem(
                selected = tab == selectedTab,
                onClick = { onTabSelected(tab) },
                icon = tab.icon,
                label = stringResource(tab.titleRes),
            )
        }
    }
}

private val LibraryTab.icon: ImageVector
    get() = when (this) {
        LibraryTab.Songs -> MiuixIcons.Music
        LibraryTab.Artists -> MiuixIcons.Contacts
        LibraryTab.Albums -> MiuixIcons.Album
    }
