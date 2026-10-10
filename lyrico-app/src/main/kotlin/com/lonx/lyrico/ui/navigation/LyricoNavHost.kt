package com.lonx.lyrico.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.SavedState
import androidx.savedstate.read
import com.lonx.lyrico.screens.AppLogScreen
import com.lonx.lyrico.screens.BatchTaskDetailScreen
import com.lonx.lyrico.screens.BatchTaskListScreen
import com.lonx.lyrico.screens.LibraryHomeScreen
import com.lonx.lyrico.screens.PluginConfigScreen
import com.lonx.lyrico.screens.PluginManagerScreen
import com.lonx.lyrico.screens.SearchCoverScreen
import com.lonx.lyrico.screens.SearchLyricsScreen
import com.lonx.lyrico.screens.SearchResultsScreen
import top.yukonga.miuix.kmp.basic.Surface
import androidx.compose.foundation.layout.fillMaxSize

/**
 * The desktop navigation host, replacing `DestinationsNavHost(navGraph = NavGraphs.root, ...)`.
 *
 * Only the generated layer is replaced; the navigation library underneath is the same one the Android
 * app used. Routes are registered here as their screens are ported, one `composable(...)` per
 * [NavDirection], with the transition style carried over verbatim from `LyricoApp.kt` so the ported
 * screens keep the Android slide animation instead of silently dropping it.
 *
 * `composable` gives each entry its own `ViewModelStoreOwner` and `SavedStateHandle`, which is what
 * `koinViewModel()` and the result-recipient flows rely on (see [Navigator]).
 *
 * The start route is the library shell (`library_home`), which is the route Android started on and
 * which is now complete: the shell's three tabs are the ported songs, artists and albums pages. It is
 * the default of [startDestination], so a bare `LyricoNavHost()` boots the shipped entry screen, and a
 * test that wants to start elsewhere (e.g. straight on the app log) passes the route in.
 * `app_logs` stays registered; local search and the metadata editor are still declared-only and are
 * registered by the batches that port their screens. The batch task list and detail (`batch_task_list`,
 * `batch_task_detail/{taskId}`) were registered by C6c, the first pair of routes that navigate to each
 * other inside the ported graph.
 *
 * ### Dev start route
 *
 * `-Dlyrico.start.route=<route>` boots straight into a registered route (see [desktopStartDestination]).
 * Some of the ported screens are reachable only from a screen that is still in the Android tree -- the
 * search pages are opened by the metadata editor, which is a later batch -- so this property is how the
 * real-window evidence and the inspection runs for those screens are taken today. It is an explicit
 * developer affordance, not a user-facing one: unset (always, for a shipped build), the start route is
 * the library shell exactly as before.
 */
@Composable
fun LyricoNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: NavDirection = desktopStartDestination(),
) {
    val navigator = rememberNavigator(navController)
    Surface(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                )
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                )
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                )
            },
        ) {
            composable(AppLogsDestination.ROUTE) {
                AppLogScreen(navigator = navigator)
            }
            composable(LibraryHomeDestination.ROUTE) {
                LibraryHomeScreen(navigator = navigator)
            }
            composable(
                route = SearchResultsDestination.PATTERN,
                arguments = listOf(optionalStringArgument(SearchResultsDestination.ARG_KEYWORD)),
            ) { entry ->
                SearchResultsScreen(
                    keyword = entry.arguments.readOptionalString(SearchResultsDestination.ARG_KEYWORD),
                    // Keyed by the destination's base route, not the pattern: `resultKey` is the single
                    // definition of the result convention and `ResultBackNavigatorTest` pins the base
                    // form (`search_results:result`). The batch that ports `EditMetadataScreen` reads
                    // the same key -- it is documented on `ResultBackNavigator`.
                    resultNavigator = rememberResultBackNavigator(
                        controller = navController,
                        route = SearchResultsDestination.BASE,
                    ),
                )
            }
            composable(
                route = SearchLyricsDestination.PATTERN,
                arguments = listOf(
                    optionalStringArgument(SearchLyricsDestination.ARG_TITLE),
                    optionalStringArgument(SearchLyricsDestination.ARG_ARTIST),
                    optionalStringArgument(SearchLyricsDestination.ARG_ALBUM),
                    optionalStringArgument(SearchLyricsDestination.ARG_DATE),
                ),
            ) { entry ->
                // The Android call sites pass `.orEmpty()`, so an absent argument and an empty one mean
                // the same thing here; the screen's own defaults cover the absent case.
                SearchLyricsScreen(
                    title = entry.arguments.readOptionalString(SearchLyricsDestination.ARG_TITLE).orEmpty(),
                    artist = entry.arguments.readOptionalString(SearchLyricsDestination.ARG_ARTIST).orEmpty(),
                    album = entry.arguments.readOptionalString(SearchLyricsDestination.ARG_ALBUM).orEmpty(),
                    date = entry.arguments.readOptionalString(SearchLyricsDestination.ARG_DATE).orEmpty(),
                    resultNavigator = rememberResultBackNavigator(
                        controller = navController,
                        route = SearchLyricsDestination.BASE,
                    ),
                )
            }
            composable(
                route = SearchCoverDestination.PATTERN,
                arguments = listOf(optionalStringArgument(SearchCoverDestination.ARG_KEYWORD)),
            ) { entry ->
                SearchCoverScreen(
                    keyword = entry.arguments.readOptionalString(SearchCoverDestination.ARG_KEYWORD),
                    resultNavigator = rememberResultBackNavigator(
                        controller = navController,
                        route = SearchCoverDestination.BASE,
                    ),
                )
            }
            composable(BatchTaskListDestination.ROUTE) {
                BatchTaskListScreen(navigator = navigator)
            }
            composable(
                route = BatchTaskDetailDestination.PATTERN,
                // A path argument, so required rather than optional: the route only matches with a
                // non-empty segment, and the repository never generates an empty task id.
                arguments = listOf(
                    navArgument(BatchTaskDetailDestination.ARG_TASK_ID) { type = NavType.StringType }
                ),
            ) { entry ->
                BatchTaskDetailScreen(
                    taskId = entry.arguments
                        .readOptionalString(BatchTaskDetailDestination.ARG_TASK_ID)
                        .orEmpty(),
                    navigator = navigator,
                )
            }
            composable(PluginManagerDestination.ROUTE) {
                PluginManagerScreen(navigator = navigator)
            }
            composable(
                route = PluginConfigDestination.PATTERN,
                // A path argument, so it is required rather than optional: the route only matches with a
                // non-empty segment, and the installer has already validated the id as a reverse-DNS one.
                arguments = listOf(
                    navArgument(PluginConfigDestination.ARG_PLUGIN_ID) { type = NavType.StringType }
                ),
            ) { entry ->
                PluginConfigScreen(
                    pluginId = entry.arguments.readOptionalString(PluginConfigDestination.ARG_PLUGIN_ID)
                        .orEmpty(),
                    navigator = navigator,
                )
            }
        }
    }
}

/**
 * Declares an optional string query argument.
 *
 * The three search routes carry their pre-filled values as query parameters rather than path segments,
 * because the Android call sites pass `.orEmpty()` -- i.e. `""` is a normal value -- and a path
 * segment `{arg}` does not match an empty one. `nullable = true` plus a `null` default is what makes an
 * *absent* parameter (a route built without the argument at all) arrive as `null` rather than as a
 * build-time failure. The reasoning and the round-trip measurements are in `Destinations.kt`.
 */
private fun optionalStringArgument(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

/**
 * Reads an optional string argument, tolerating both "omitted" and "present but empty".
 *
 * `entry.arguments` is a nullable `SavedState` on desktop and the reader throws for a missing key, so
 * the absence is handled once here instead of at six call sites.
 */
private fun SavedState?.readOptionalString(key: String): String? =
    this?.read { getStringOrNull(key) }

/**
 * The route the app boots into: the library shell, unless the developer asked for another one.
 *
 * `-Dlyrico.start.route=search_results?keyword=x` (any route string the graph registers) is read here
 * rather than in a screen, because the start destination is a property of the graph. The value is used
 * verbatim -- it is a raw route, not a [NavDirection] -- so a developer can pass arguments the same way
 * a call site would build them. An unregistered route is not a crash: `NavHost` fails on an unknown
 * *start* destination, so the mistake surfaces immediately instead of rendering an empty window.
 *
 * Non-composable on purpose, so it can be a default parameter expression (a composable default cannot
 * call `remember`). [DevStartDestination] compares by route, so recomposition does not rebuild the
 * graph the way a fresh instance per composition would.
 */
fun desktopStartDestination(): NavDirection =
    System.getProperty(START_ROUTE_PROPERTY)
        ?.takeIf { it.isNotBlank() }
        ?.let { DevStartDestination(it) }
        ?: LibraryHomeDestination()

/** The system property that overrides the start route; see [desktopStartDestination]. */
const val START_ROUTE_PROPERTY = "lyrico.start.route"

/**
 * A raw route string from [START_ROUTE_PROPERTY], wrapped so it can be a start destination.
 *
 * A `data class` rather than a plain one so two reads of the same property compare equal; `NavHost`
 * remembers its graph under the start destination, and a value that never compares equal would rebuild
 * it on every recomposition.
 */
data class DevStartDestination(private val rawRoute: String) : NavDirection {
    override val route: String = rawRoute
}
