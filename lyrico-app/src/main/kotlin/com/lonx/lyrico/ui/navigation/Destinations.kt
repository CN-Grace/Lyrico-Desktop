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

/**
 * The library shell: the songs, artists and albums tabs behind one tab bar.
 *
 * Android declared it `@Destination<RootGraph>(start = true, route = "library_home")`, and the port
 * keeps both the route string and the start position, so `LyricoNavHost()` with no arguments boots
 * into the shipped entry screen. The songs page used to be the temporary start route while the album
 * and artist pages did not exist; it is now a tab like the other two.
 */
class LibraryHomeDestination : NavDirection {
    override val route: String = ROUTE

    companion object {
        const val ROUTE = "library_home"
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

// ---------------------------------------------------------------------------------------------
// Not ported yet. `AlbumsPage` and `ArtistsPage` navigate to these two, exactly as the Android pages
// did. Both screens arrive in a later batch; until then the navigator logs the miss.
// ---------------------------------------------------------------------------------------------

/** One album's detail screen. Reached by tapping a card in the albums grid. */
data class AlbumDetailDestination(val albumId: Long) : NavDirection {
    override val route: String get() = "$BASE/$albumId"

    companion object {
        const val ARG_ALBUM_ID = "albumId"
        const val BASE = "album_detail"
        const val PATTERN = "$BASE/{$ARG_ALBUM_ID}"
    }
}

/** One artist's detail screen. Reached by tapping a row in the artists list. */
data class ArtistDetailDestination(val artistId: Long) : NavDirection {
    override val route: String get() = "$BASE/$artistId"

    companion object {
        const val ARG_ARTIST_ID = "artistId"
        const val BASE = "artist_detail"
        const val PATTERN = "$BASE/{$ARG_ARTIST_ID}"
    }
}

// ---------------------------------------------------------------------------------------------
// Ported in C5b: the plugin manager, the plugin config form, and the three search pages.
//
// The three search pages are reached from the metadata editor and the plugin manager is reached
// from the settings screen, and *none* of those callers is ported yet, so nothing in a shipped
// window navigates to these five routes today. They are declared and registered so the ported
// screens are real destinations rather than untestable claims, and the dev start-route override
// (`-Dlyrico.start.route=<route>`, see `LyricoNavHost`) is what makes them reachable for
// real-window evidence. The real entry points arrive with the `SettingsScreen` and
// `EditMetadataScreen` batches, which add the `navigate(...)` calls and nothing else.
//
// Their arguments travel as **query parameters**, not path segments. That is a deviation from what
// the Compose Destinations generator emitted (non-null parameters went into the path), and it is
// deliberate: the call sites pass empty strings for the lyrics search (`title = ""` is what the
// metadata editor sends when a tag is blank) and an empty path segment does not match `{arg}`, so a
// path-shaped route would fail to navigate for exactly the songs that need the search most. A query
// parameter carries an empty value without complaint. Values are percent-encoded with the ported
// [encodeNavRouteArgument] so a keyword containing a space, an `&`, a `/` or CJK survives.
// ---------------------------------------------------------------------------------------------

/**
 * The remote song-search page. `keyword` is the pre-filled query; `null` means "start empty".
 *
 * Returns a `LyricsSearchResult` to the caller -- see [resultKey] and [ResultBackNavigator].
 */
data class SearchResultsDestination(val keyword: String?) : NavDirection {
    override val route: String
        get() = if (keyword == null) BASE else "$BASE?$ARG_KEYWORD=${encodeNavRouteArgument(keyword)}"

    companion object {
        const val ARG_KEYWORD = "keyword"
        const val BASE = "search_results"
        const val PATTERN = "$BASE?$ARG_KEYWORD={$ARG_KEYWORD}"
    }
}

/**
 * The lyrics-search page, opened for one song. All four values come from the metadata editor's
 * tags and may legitimately be empty, which is why they are query parameters (see the note above).
 *
 * Returns a `LyricsSearchResult` to the caller.
 */
data class SearchLyricsDestination(
    val title: String,
    val artist: String,
    val album: String,
    val date: String,
) : NavDirection {
    override val route: String
        get() = "$BASE?$ARG_TITLE=${encodeNavRouteArgument(title)}" +
            "&$ARG_ARTIST=${encodeNavRouteArgument(artist)}" +
            "&$ARG_ALBUM=${encodeNavRouteArgument(album)}" +
            "&$ARG_DATE=${encodeNavRouteArgument(date)}"

    companion object {
        const val ARG_TITLE = "title"
        const val ARG_ARTIST = "artist"
        const val ARG_ALBUM = "album"
        const val ARG_DATE = "date"
        const val BASE = "search_lyrics"
        const val PATTERN = "$BASE?$ARG_TITLE={$ARG_TITLE}&$ARG_ARTIST={$ARG_ARTIST}" +
            "&$ARG_ALBUM={$ARG_ALBUM}&$ARG_DATE={$ARG_DATE}"
    }
}

/** The cover-search page. `keyword` pre-fills the query; `null` means "start empty". Returns the
 * chosen cover's URL as a `String`. */
data class SearchCoverDestination(val keyword: String?) : NavDirection {
    override val route: String
        get() = if (keyword == null) BASE else "$BASE?$ARG_KEYWORD=${encodeNavRouteArgument(keyword)}"

    companion object {
        const val ARG_KEYWORD = "keyword"
        const val BASE = "search_cover"
        const val PATTERN = "$BASE?$ARG_KEYWORD={$ARG_KEYWORD}"
    }
}

/** The plugin manager. Reached from the settings screen once that screen is ported. */
class PluginManagerDestination : NavDirection {
    override val route: String = ROUTE

    companion object {
        const val ROUTE = "plugin_manager"
    }
}

/**
 * One plugin's configuration form, opened by tapping a plugin in the manager.
 *
 * `pluginId` is a path argument, unlike the search pages' query parameters: it is a non-empty
 * reverse-DNS identifier that the installer already validated, so an empty segment cannot occur.
 */
data class PluginConfigDestination(val pluginId: String) : NavDirection {
    override val route: String get() = "$BASE/${encodeNavRouteArgument(pluginId)}"

    companion object {
        const val ARG_PLUGIN_ID = "pluginId"
        const val BASE = "plugin_config"
        const val PATTERN = "$BASE/{$ARG_PLUGIN_ID}"
    }
}

// ---------------------------------------------------------------------------------------------
// Ported in C6c: the batch task history and one task's detail.
//
// Android reached the list from the settings screen's "task history" row; `SettingsScreen` is still
// in the Android tree, so the shipped entry point does not exist yet and the dev start-route override
// (`-Dlyrico.start.route=batch_task_list`, see `LyricoNavHost`) is how the real-window evidence is
// taken. Both routes are registered, so the list's own "open one task" navigation -- and the detail
// screen's "open one item's metadata" navigation -- are real hops inside the ported graph.
// ---------------------------------------------------------------------------------------------

/** The batch task history list. Reached from the settings screen once that screen is ported. */
class BatchTaskListDestination : NavDirection {
    override val route: String = ROUTE

    companion object {
        const val ROUTE = "batch_task_list"
    }
}

/**
 * One batch task's detail, with its items split by status.
 *
 * `taskId` is a path argument (the repository generates UUID-shaped ids, so an empty segment cannot
 * occur) and it is the *only* thing the route carries: the screen's view model takes it as a Koin
 * parameter, exactly as the Android screen did through `koinViewModel { parametersOf(taskId) }`.
 */
data class BatchTaskDetailDestination(val taskId: String) : NavDirection {
    override val route: String get() = "$BASE/${encodeNavRouteArgument(taskId)}"

    companion object {
        const val ARG_TASK_ID = "taskId"
        const val BASE = "batch_task_detail"
        const val PATTERN = "$BASE/{$ARG_TASK_ID}"
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
 */
internal fun encodeNavRouteArgument(value: String): String {
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
