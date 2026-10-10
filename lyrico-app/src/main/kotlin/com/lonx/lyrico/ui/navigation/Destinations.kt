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
 * `composable(pattern) { entry -> ... }` needs to register and read them back. Reading is where the
 * desktop differs from Android in a way that does not compile either way: `NavBackStackEntry.arguments`
 * is a `SavedState` here, not a `Bundle`, so `entry.arguments?.getLong(...)` is unresolved and the
 * argument is read through the reader instead:
 *
 * ```
 * data class AlbumDetailDestination(val albumId: Long) : NavDirection {
 *     override val route: String get() = "$BASE/$albumId"
 *     companion object {
 *         const val ARG_ALBUM_ID = "albumId"
 *         const val BASE = "album_detail"
 *         const val PATTERN = "$BASE/{$ARG_ALBUM_ID}"
 *     }
 * }
 * // registration: composable(AlbumDetailDestination.PATTERN) { entry ->
 * //     val albumId = entry.arguments?.read {
 * //         getLongOrNull(AlbumDetailDestination.ARG_ALBUM_ID)
 * //     } ?: 0L
 * //     AlbumDetailScreen(albumId = albumId, ...)
 * ```
 *
 * [PATTERN][AlbumDetailDestination.PATTERN] is the template the graph registers; `route` is the
 * concrete route a call site navigates to, so it interpolates the value *into* the placeholder rather
 * than appending to the template (`"$PATTERN/$albumId"` would produce
 * `album_detail/{albumId}/7`, which matches nothing).
 *
 * The `read` block's receiver is a `SavedStateReader`, and it is also what decodes a path argument:
 * the library percent-decodes a path segment before it puts it in the reader, which is why
 * [EditMetadataDestination] encodes its argument going in and reads a plain Windows path coming out.
 *
 * A destination is declared in one of two situations, and [NavControllerNavigator] behaves
 * differently for each:
 *
 * * **Its screen is ported.** Then `LyricoNavHost` registers the route and a `navigate` reaches a
 *   composable. This is the normal case: an unused route would otherwise be an untestable claim.
 * * **A ported screen navigates to it, but its screen is not ported yet.** Then nothing is
 *   registered, and `navigate` logs the miss and does nothing instead of crashing. The declaration
 *   exists so the ported call site is byte-for-byte the Android one and the later batch only has to
 *   add the `composable(...)` block. These are listed under "not ported yet" below.
 */
class AppLogsDestination : NavDirection {
    override val route: String = ROUTE

    companion object {
        const val ROUTE = "app_logs"
    }
}

/** The library's songs tab. Registered by `LyricoNavHost`; the temporary start route. */
class SongsDestination : NavDirection {
    override val route: String = ROUTE

    companion object {
        const val ROUTE = "songs"
    }
}

// ---------------------------------------------------------------------------------------------
// Not ported yet. `SongsPage` navigates to these three, exactly as the Android page did.
// ---------------------------------------------------------------------------------------------

/** The settings screen. Reached from the songs page's navigation icon. */
class SettingsDestination : NavDirection {
    override val route: String = ROUTE

    companion object {
        const val ROUTE = "settings"
    }
}

/** In-library search. Reached from the songs page's search icon. */
data object LocalSearchDestination : NavDirection {
    override val route: String = ROUTE

    const val ROUTE = "local_search"
}

/**
 * The metadata editor for one song. Reached by tapping a song row.
 *
 * The argument is the `songs.uri` value, which on the desktop is an absolute Windows path, so it
 * contains both `:` and `\` plus whatever the tags say. It is percent-encoded with
 * [encodeNavRouteArgument], whose output was measured byte-for-byte against the encoder the navigation
 * library itself decodes with.
 */
data class EditMetadataDestination(val songFileUri: String) : NavDirection {
    override val route: String get() = "$BASE/${encodeNavRouteArgument(songFileUri)}"

    companion object {
        const val ARG_SONG_FILE_URI = "songFileUri"
        const val BASE = "edit_metadata"
        const val PATTERN = "$BASE/{$ARG_SONG_FILE_URI}"
    }
}

/**
 * Percent-encodes one route argument, matching the navigation library's own encoder.
 *
 * The library's encoder is `androidx.navigation.NavUriUtils`, but that class is `internal` in Kotlin
 * (its members are not name-mangled in bytecode, which is why this took a Kotlin compile to find
 * out). Rather than leave a route with a raw `C:\Music\a b.mp3` path in it -- which would split into
 * extra path segments at the first `/` and never match `edit_metadata/{songFileUri}` -- the encoder
 * is reimplemented here. Its table is not guessed: it was measured by calling the library's encoder
 * over all of ASCII and comparing, and the result is pinned in `NavigatorTest` both directly and by
 * navigating to a registered pattern and reading the argument back out.
 *
 * The allowed set is Android's `Uri.encode(s, null)` set -- unreserved characters plus `!'()*-._~` --
 * and everything else is `%XX` of its UTF-8 bytes, so a CJK file name survives too.
 *
 * Delete this once the metadata editor is ported and the route can be asserted end to end.
 */internal fun encodeNavRouteArgument(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val encoded = StringBuilder(bytes.size)
    for (byte in bytes) {
        val unsigned = byte.toInt() and 0xFF
        if (unsigned < 0x80 && ALLOWED_ROUTE_ARGUMENT_CHARS[unsigned]) {
            encoded.append(unsigned.toChar())
        } else {
            encoded.append('%')
                .append(HEX_DIGITS[unsigned shr 4])
                .append(HEX_DIGITS[unsigned and 0x0F])
        }
    }
    return encoded.toString()
}

private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

private val ALLOWED_ROUTE_ARGUMENT_CHARS = BooleanArray(0x80).apply {
    for (char in 'a'..'z') this[char.code] = true
    for (char in 'A'..'Z') this[char.code] = true
    for (char in '0'..'9') this[char.code] = true
    for (char in "!'()*-._~") this[char.code] = true
}
