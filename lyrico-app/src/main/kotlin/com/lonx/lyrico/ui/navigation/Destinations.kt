package com.lonx.lyrico.ui.navigation

/**
 * The hand-written replacement for Compose Destinations' generated `*Destination` classes.
 *
 * Each entry keeps the generated class's name and call shape so screen call sites stay unchanged
 * (`navigator.navigate(AppLogsDestination())`). Two shapes appear in the screens and both are
 * preserved, because the call sites are what has to compile:
 *
 * * `navigator.navigate(X)` (no parentheses) needs `data object X`.
 * * `navigator.navigate(X())` needs a class/data class with a no-argument constructor.
 *
 * Screens with arguments additionally declare the route pattern and the argument names, which is what
 * `composable(pattern) { entry -> ... }` needs to register and read them back:
 *
 * ```
 * data class AlbumDetailDestination(val albumId: Long) : NavDirection {
 *     override val route: String get() = "$PATTERN/$albumId"
 *     companion object {
 *         const val ARG_ALBUM_ID = "albumId"
 *         const val PATTERN = "album_detail/{$ARG_ALBUM_ID}"
 *     }
 * }
 * // registration: composable(AlbumDetailDestination.PATTERN) { entry ->
 * //     AlbumDetailScreen(albumId = entry.arguments?.getLong(AlbumDetailDestination.ARG_ALBUM_ID) ?: 0L, ...)
 * ```
 *
 * Destinations are added here as their screens are ported; an unused route would be an
 * untestable claim.
 */
class AppLogsDestination : NavDirection {
    override val route: String = ROUTE

    companion object {
        const val ROUTE = "app_logs"
    }
}
