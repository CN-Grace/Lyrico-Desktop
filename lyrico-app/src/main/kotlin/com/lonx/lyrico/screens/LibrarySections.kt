package com.lonx.lyrico.screens

/**
 * The alphabet index shared by the library pages.
 *
 * Android declared these next to `LibraryHomeScreen`, which meant the songs page (the only page
 * ported so far) could not exist without dragging in the whole three-tab shell. The shell lands in a
 * later batch together with the album and artist pages; until then this file is the home of the two
 * constants and the enum. When the shell is ported it drops its own copies and keeps importing from
 * here, so nothing changes at the call sites.
 */
val SECTIONS_ASC = listOf(
    "0"
) + ('A'..'Z').map { it.toString() } + listOf("#")

val SECTIONS_DESC = SECTIONS_ASC.asReversed()

enum class TopBarState {
    Selection, Default
}
