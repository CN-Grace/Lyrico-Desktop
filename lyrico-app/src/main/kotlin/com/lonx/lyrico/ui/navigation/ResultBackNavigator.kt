package com.lonx.lyrico.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import com.lonx.lyrico.utils.logging.PlatformLog

/**
 * Hands a value back to the screen that opened this one, then closes this one.
 *
 * Android got this from Compose Destinations, whose generated layer provided both halves: the callee
 * declared `resultNavigator: ResultBackNavigator<LyricsSearchResult>` and called
 * `navigateBack(result)`, the caller declared `onLyricsResult: ResultRecipient<SearchResultsDestination,
 * LyricsSearchResult>` and implemented its `onResult`. Compose Destinations publishes no JVM artifact
 * (see [NavDirection]), so -- like [Navigator] itself -- the two halves are replaced here. This file is
 * the **sending** half only: nothing in the ported tree receives a result yet, because the only caller
 * (`EditMetadataScreen`) belongs to a later batch. The receiving half and the key function it must use
 * are described below so the batch that ports that screen is not guessing.
 *
 * ### How a result travels
 *
 * The value is written into the `SavedStateHandle` of the *previous* back-stack entry -- the screen
 * that will be returned to -- under [resultKey], and then this entry is popped. Three facts make this
 * safe here, and each was measured rather than assumed:
 *
 * * **The transport accepts the value as-is.** Android's `SavedStateHandle` only took `Parcelable`s and
 *   primitives, which is why `LyricsSearchResult` was `@Parcelize`d. On the JVM the multiplatform
 *   `SavedStateHandle` stores arbitrary objects: `SavedStateHandleProbeTest` pins that a
 *   `LyricsSearchResult` -- with its `Set`, `Map` and enum fields -- comes back as the same instance.
 *   So the port transports the object itself, and a serialization step would be dead weight that could
 *   silently drop fields.
 * * **The previous entry's handle outlives this entry.** The value is stored on the screen being
 *   returned to, not on the screen being popped, so popping does not take it away.
 * * **The receiving end is a flow.** `SavedStateHandle` exposes `getStateFlow`, which is what a
 *   recipient has to collect: a result can be delivered before the caller's composition has finished
 *   setting up, so reading it once inside a `LaunchedEffect` body would be a race. The recipient
 *   half must therefore follow the same shape the generated code used -- observe, do not poll.
 *
 * ### The key convention (the receiving half must match this exactly)
 *
 * A result is keyed by the route of the screen that *sends* it, not by the route that receives it:
 * one screen can return different results from different sub-screens (the metadata editor gets lyrics
 * from both the search page and the lyrics search page), so the caller has to be able to tell them
 * apart. [resultKey] is the single definition of that key; the later batch reads
 * `entry.savedStateHandle.getStateFlow<T?>(resultKey(SomeDestination.ROUTE))`.
 *
 * This is an interface rather than a class so a screen can be rendered in a test with a recording
 * implementation -- the screens' own tests pass a lambda that captures whatever the user's click
 * produced, without needing a navigation graph.
 */
fun interface ResultBackNavigator<T> {
    /** Stores [result] for the previous screen and pops this one. */
    fun navigateBack(result: T)
}

/**
 * The key a result from the screen registered at [route] is stored under.
 *
 * Public and documented because two batches have to agree on it: this one writes it, the batch that
 * ports `EditMetadataScreen` reads it. It is a function of the route rather than a hand-written
 * constant per destination so the two sides cannot drift.
 */
fun resultKey(route: String): String = "$route:$RESULT_SUFFIX"

private const val RESULT_SUFFIX = "result"

private const val TAG = "ResultBackNavigator"

/**
 * The real [ResultBackNavigator] for a screen registered at [route].
 *
 * `previousBackStackEntry` is read at call time rather than captured, so the navigator stays correct
 * when more screens are pushed on top of this one. A navigate-back with nothing behind it (the screen
 * is the graph's start destination, or the call arrives after the stack was already unwound) is logged
 * and does nothing: popping would take the window down to an empty graph, and a missing result has a
 * defined meaning downstream ("nothing was chosen").
 */
@Composable
fun <T> rememberResultBackNavigator(
    controller: NavHostController,
    route: String,
): ResultBackNavigator<T> = remember(controller, route) {
    ResultBackNavigator { result ->
        val target = controller.previousBackStackEntry
        if (target == null) {
            PlatformLog.w(TAG, "No previous entry to return a result to from '$route'")
        } else {
            target.savedStateHandle[resultKey(route)] = result
        }
        controller.popBackStack()
    }
}
