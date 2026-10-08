package com.lonx.lyrico.utils

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * A message a view model hands to the UI: either already-final text, or a string resource plus its
 * arguments to be resolved at the point of display (so the language is the UI's current one rather
 * than whatever it was when the message was created).
 *
 * Two things changed on the way from Android:
 *
 * - The resource is a Compose-resources [StringResource] instead of an `@StringRes Int`. That is what
 *   makes the same message work on desktop, and it moves the "does this key exist" check from runtime
 *   to compile time.
 * - **The non-composable entry point is suspending, not `Context`-taking.** Android had
 *   `asString(context: Context)` because that was the only way to resolve a resource id outside a
 *   composition. There is no `Context` here, and `getString` is `suspend` — but every one of the five
 *   call sites in the Android tree was already inside a coroutine (`LaunchedEffect { ... collect { } }`),
 *   passing `context` only to satisfy the signature, so nothing is lost.
 *
 * The two paths have different names because Kotlin will not let a `suspend` function overload a
 * non-suspend one: use [asString] inside a composition, [resolve] inside a coroutine.
 */
sealed interface UiMessage {

    /** Text that is already final — a filename, an exception message, a value from a plugin. */
    data class DynamicString(val value: String?) : UiMessage

    /**
     * A string resource and its format arguments.
     *
     * Not a data class on purpose, as on Android: `vararg` in a data class would give the generated
     * `equals` array-identity semantics, which is a worse lie than the identity equality this has.
     * Two equal-looking `Localized` values therefore compare unequal — do not use them as a state
     * diffing key.
     */
    class Localized(val res: StringResource, vararg val args: Any) : UiMessage

    /** Resolves this message for display in a composition. */
    @Composable
    fun asString(): String? = when (this) {
        is DynamicString -> value
        is Localized -> stringResource(res, *args)
    }

    /** Resolves this message outside a composition — from a coroutine, such as a `Snackbar` host. */
    suspend fun resolve(): String? = when (this) {
        is DynamicString -> value
        is Localized -> getString(res, *args)
    }
}
