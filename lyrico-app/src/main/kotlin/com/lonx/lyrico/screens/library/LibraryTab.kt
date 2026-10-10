package com.lonx.lyrico.screens.library

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.library_tab_albums
import com.lonx.lyrico.resources.library_tab_artists
import com.lonx.lyrico.resources.library_tab_songs
import org.jetbrains.compose.resources.StringResource

/**
 * The library shell's three tabs, in pager order.
 *
 * Android carried `@param:StringRes val titleRes: Int`. The desktop has no `R` class: Compose
 * Multiplatform resources expose each string as an extension property on `Res.string`, so the label
 * is a [StringResource] and is resolved with `stringResource(tab.titleRes)` at the call site -- the
 * same call, with the same import (`org.jetbrains.compose.resources.stringResource`) the other ported
 * screens use.
 *
 * The icon is *not* here. Android declared it as a private extension in
 * `ui/components/library/LibraryBottomNavigationBar.kt`, next to the two bars that consume it, and
 * that is where the port keeps it: the enum is the pager's contract, the icon is a bar's business.
 *
 * `SECTIONS_ASC`, `SECTIONS_DESC` and `TopBarState` used to live beside the shell's composable; see
 * `screens/LibrarySections.kt` for why they moved out and who imports them from there now.
 */
enum class LibraryTab(
    val titleRes: StringResource,
) {
    Songs(titleRes = Res.string.library_tab_songs),
    Artists(titleRes = Res.string.library_tab_artists),
    Albums(titleRes = Res.string.library_tab_albums),
}
