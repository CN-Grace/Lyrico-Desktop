package com.lonx.lyrico.screens

/**
 * The alphabet index shared by the library pages.
 *
 * Android declared these next to `LibraryHomeScreen`, which meant the songs page (the first library
 * page the port built) could not exist without dragging in the whole three-tab shell. They moved here
 * for that reason and stayed: the shell now imports them from this file rather than declaring its own
 * copies, and the album and artist pages take `SECTIONS_ASC`/`SECTIONS_DESC` from here too.
 */
val SECTIONS_ASC = listOf(
    "0"
) + ('A'..'Z').map { it.toString() } + listOf("#")

val SECTIONS_DESC = SECTIONS_ASC.asReversed()

enum class TopBarState {
    Selection, Default
}
