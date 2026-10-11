package com.lonx.lyrico.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.SavedStateHandle

/**
 * Receives a value sent back by a screen registered at another route.
 *
 * This is the **receiving** half of the protocol [ResultBackNavigator] documents; read that file first,
 * because it defines what travels and how. Short version: the sending screen writes its result into
 * *this* screen's `SavedStateHandle` under [resultKey] and pops itself, and this half observes that value
 * as a flow -- observe, never poll, because the result can land before this screen's composition has
 * finished setting itself up.
 *
 * Android got this type from Compose Destinations, where a screen declared
 * `onLyricsResult: ResultRecipient<SearchResultsDestination, LyricsSearchResult>` and wrote
 * `onLyricsResult.onResult { ... }` in its body. The generated layer is gone (see [NavDirection]); the
 * call shape is kept, including the type name, so the ported screen body reads the same as the Android
 * one. The destination type parameter is gone as well: it only existed to generate the key, which is now
 * a function of the *sending* route (see [resultKey]), so one `ResultRecipient<T>` per sender is enough.
 *
 * Instances come from [rememberResultRecipient], which is the only way to build one -- a recipient is
 * meaningless without a handle to observe.
 */
class ResultRecipient<T> internal constructor(
    /** The live value in the handle; a `State` rather than a read-once snapshot, see the class KDoc. */
    private val state: State<T?>,
    private val clearValue: () -> Unit,
) {
    /** The result that has arrived and not yet been handled, or `null` when there is none. */
    val result: T? get() = state.value

    /**
     * Forgets the current result, so returning to this screen again does not replay it.
     *
     * Called for you by [onResult]; public because a screen that reads [result] itself needs it too.
     */
    fun consume() = clearValue()
}

/**
 * The [ResultRecipient] for results sent by the screen registered at [route], observing [handle].
 *
 * [route] is the **sending** screen's base route ([com.lonx.lyrico.ui.navigation.NavDirection]'s `BASE`),
 * never the pattern: one screen returns results from several sub-screens, and the key has to tell them
 * apart. The handle is passed in rather than looked up from a controller so the receiving half stays
 * testable without a navigation graph -- it is the *sending* half's job to reach the previous entry.
 */
@Composable
fun <T> rememberResultRecipient(handle: SavedStateHandle, route: String): ResultRecipient<T> {
    val key = resultKey(route)
    val state = handle.getStateFlow<T?>(key, null).collectAsState()
    return remember(handle, key, state) {
        ResultRecipient(state = state, clearValue = { handle.remove<T>(key) })
    }
}

/**
 * Handles each result that arrives from the sender this recipient was built for, exactly once.
 *
 * [block] runs outside the composition (inside a `LaunchedEffect` keyed on the value), so it may call
 * suspending or non-composable code such as a view model method -- which is what every call site does.
 * The value is consumed after [block] returns, so the same result is not replayed when this screen is
 * recomposed or returned to later; and a *repeated* result still fires, because the handle goes back to
 * `null` in between and therefore so does the effect's key.
 *
 * A result that arrives while [block] is still running waits its turn: the effect restarts with the newer
 * value, and the older one has already been consumed.
 */
@Composable
fun <T> ResultRecipient<T>.onResult(block: (T) -> Unit) {
    val value = result ?: return
    LaunchedEffect(value) {
        block(value)
        consume()
    }
}
