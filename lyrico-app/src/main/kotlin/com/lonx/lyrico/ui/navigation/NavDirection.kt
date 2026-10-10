package com.lonx.lyrico.ui.navigation

/**
 * A typed navigation target, the desktop replacement for the classes Compose Destinations used to
 * generate from `@Destination`.
 *
 * Android navigated with `navigator.navigate(AlbumDetailDestination(albumId = 3))`: a data class per
 * screen carrying real Kotlin types, which the code generator translated into a route string. The
 * generated classes cannot exist on desktop (Compose Destinations publishes no jvm artifact -- see
 * PLAN section 6 risk 3), but the *call shape* is worth keeping: 41 call sites across the screens
 * pass a typed direction, and a typed direction cannot silently lose a screen argument the way
 * `navigate("album_detail/3")` can.
 *
 * So each screen gets a small hand-written class implementing this interface, with the same name and
 * constructor the generated one had. It exposes [route] -- the fully built route including argument
 * values -- and, where the screen takes arguments, a `PATTERN` for the `composable(...)` registration
 * and argument names for reading them back.
 */
interface NavDirection {
    /**
     * The concrete route, arguments already substituted (e.g. `"album_detail/3"`).
     *
     * Navigation Compose matches this against the registered patterns, so a screen with arguments
     * builds it here rather than at each call site.
     */
    val route: String
}

/**
 * The navigation operations the screens use, matching the subset of `DestinationsNavigator` they
 * actually called (41 `navigate`, 20 `popBackStack`, 3 `navigateUp` -- measured, not guessed).
 *
 * `popBackStack`/`navigateUp` return `Boolean` because one call site branches on it
 * (`LibraryHomeScreen` only exits the app when the back stack is empty).
 */
interface Navigator {
    /**
     * Navigates to [direction].
     *
     * The production implementation ([NavControllerNavigator]) treats a route the graph does not know
     * as a logged no-op, because three of the songs page's targets have no screen yet. A test that
     * asserts on navigation should pass its own implementation and record the directions it was
     * handed -- that is the call site under test, not the graph lookup.
     */
    fun navigate(direction: NavDirection)
    fun popBackStack(): Boolean
    fun navigateUp(): Boolean
}
