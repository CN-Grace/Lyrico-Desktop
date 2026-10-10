package com.lonx.lyrico.ui.navigation

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.SavedState
import androidx.savedstate.read
import com.lonx.lyrico.data.model.search.LyricsSearchResult
import com.lonx.lyrico.support.PlatformLogCapture
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two pieces of Compose Destinations' generated layer that the search and plugin screens need.
 *
 * Both are exercised against a real [NavHost] rather than a fake, because both are about how the
 * navigation library actually behaves on the desktop:
 *
 * 1. **[ResultBackNavigator] must deliver a value to the caller and then pop.** The screens that use
 *    it (the three search pages) hand back a `LyricsSearchResult` -- the port of what Android did with
 *    a `Parcelable` through the previous entry's `SavedStateHandle`. The test plays both roles: the
 *    caller registers a recipient-shaped `getStateFlow` observer, the callee calls
 *    `navigateBack(result)`, and the assertions cover the value, the instance identity, that the
 *    caller is back on screen, and that the value arrived through the *flow* (a recipient that reads
 *    the handle once, rather than collecting it, would race).
 * 2. **The five C5b routes must carry their arguments through `route` into `PATTERN` and back out.**
 *    The search pages take query arguments and the plugin config page takes a path argument, and the
 *    keyword values are real search terms: spaces, `&`, `/`, `+`, `%` and CJK. A round trip that only
 *    worked for `abc` would be a navigation that silently loses the query for most of the library.
 */
class ResultBackNavigatorTest {

    private companion object {
        const val CALLER_ROUTE = "caller"
        const val CALLEE_ROUTE = "callee"
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a result reaches the caller through its saved state flow and the callee is popped`() =
        runComposeUiTest {
            val expected = LyricsSearchResult(
                title = "山丘",
                artist = "李宗盛",
                album = null,
                lyrics = "[00:01.00]越过山丘",
                date = null,
                trackerNumber = null,
                picUrl = null,
                pluginId = "demo",
                pluginName = "Demo"
            )
            val controllerRef = AtomicReference<NavHostController>()
            val calleeNavigator = AtomicReference<ResultBackNavigator<LyricsSearchResult>>()
            val received = AtomicReference<LyricsSearchResult?>(null)
            val receivedInstances = mutableListOf<LyricsSearchResult>()

            setContent {
                val controller = rememberNavController()
                controllerRef.set(controller)
                NavHost(navController = controller, startDestination = CALLER_ROUTE) {
                    composable(CALLER_ROUTE) { entry ->
                        // The receiving half in the shape the later batch has to write it: observe,
                        // never poll. `collectAsState` is what a screen would use; the test records
                        // every emission instead of rendering it.
                        val result by entry.savedStateHandle
                            .getStateFlow<LyricsSearchResult?>(resultKey(CALLEE_ROUTE), null)
                            .collectAsState()
                        // Recorded from an effect, not from the composition body: a recomposition
                        // would otherwise append the same value again and the "exactly once"
                        // assertion below would be meaningless.
                        LaunchedEffect(result) {
                            result?.let {
                                received.set(it)
                                receivedInstances += it
                            }
                        }
                        Text("caller")
                    }
                    composable(CALLEE_ROUTE) { entry ->
                        calleeNavigator.set(
                            rememberResultBackNavigator<LyricsSearchResult>(
                                controller = controller,
                                route = CALLEE_ROUTE
                            )
                        )
                        Text("callee")
                        // Reading the *callee's* handle must show nothing: the result belongs to the
                        // screen being returned to, so it cannot be lost by this entry's destruction.
                        assertNull(
                            entry.savedStateHandle.get<LyricsSearchResult>(resultKey(CALLEE_ROUTE)),
                            "the result must not be stored on the entry that is about to be popped"
                        )
                    }
                }
            }

            onNodeWithText("caller").assertExists()
            // Navigation is driven from `runOnIdle`, not from a synthesized click: the navigation
            // library moves the popped entry's lifecycle through its own dispatcher, and a click
            // routed through the test's input injection lands off it (measured: "State must be at
            // least 'CREATED' to be moved to 'DESTROYED'"). What the screens do with this navigator
            // is covered by their own tests, which pass a recording navigator; what is under test
            // here is the navigation-library contract, and it has to run on the UI thread.
            runOnIdle { controllerRef.get().navigate(CALLEE_ROUTE) }
            onNodeWithText("callee").assertExists()

            runOnIdle { calleeNavigator.get().navigateBack(expected) }

            // Popped: the caller is showing again, and the callee is gone.
            onNodeWithText("caller").assertExists()
            onNodeWithText("callee").assertDoesNotExist()

            val delivered = received.get()
            assertEquals(expected, delivered)
            assertTrue(delivered === expected, "the same instance must travel, not a copy")
            assertEquals(
                1,
                receivedInstances.size,
                "the flow must emit exactly once -- a second emission would mean a lost result"
            )
            // The back stack really is one deep again, so `previousBackStackEntry` cannot be stale.
            assertEquals(
                CALLER_ROUTE,
                controllerRef.get().currentBackStackEntry?.destination?.route,
            )
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `navigating back from the start destination logs and does not throw`() = runComposeUiTest {
        val log = PlatformLogCapture().install()
        val navigatorRef = AtomicReference<ResultBackNavigator<String>>()
        try {
            setContent {
                val controller = rememberNavController()
                NavHost(navController = controller, startDestination = CALLEE_ROUTE) {
                    composable(CALLEE_ROUTE) {
                        navigatorRef.set(
                            rememberResultBackNavigator<String>(
                                controller = controller,
                                route = CALLEE_ROUTE
                            )
                        )
                        Text("root")
                    }
                }
            }
            onNodeWithText("root").assertExists()
            runOnIdle { navigatorRef.get().navigateBack("nowhere to go") }
            // Still alive, still on the same screen: a missing result means "nothing was chosen".
            onNodeWithText("root").assertExists()
            assertTrue(
                log.warnings.any { it.contains("No previous entry to return a result to") },
                "the miss must be explained in the log, got: ${log.lines}"
            )
        } finally {
            log.restore()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the search routes round trip their arguments`() = runComposeUiTest {
        val read = mutableMapOf<String, String?>()
        val controllerRef = AtomicReference<NavHostController>()

        setContent {
            val controller = rememberNavController()
            controllerRef.set(controller)
            NavHost(navController = controller, startDestination = CALLER_ROUTE) {
                composable(CALLER_ROUTE) { Text("caller") }
                composable(
                    route = SearchResultsDestination.PATTERN,
                    arguments = listOf(stringArg(SearchResultsDestination.ARG_KEYWORD))
                ) { entry ->
                    read["keyword"] = entry.arguments.readString(SearchResultsDestination.ARG_KEYWORD)
                    Text("results")
                }
                composable(
                    route = SearchLyricsDestination.PATTERN,
                    arguments = listOf(
                        stringArg(SearchLyricsDestination.ARG_TITLE),
                        stringArg(SearchLyricsDestination.ARG_ARTIST),
                        stringArg(SearchLyricsDestination.ARG_ALBUM),
                        stringArg(SearchLyricsDestination.ARG_DATE)
                    )
                ) { entry ->
                    read["title"] = entry.arguments.readString(SearchLyricsDestination.ARG_TITLE)
                    read["artist"] = entry.arguments.readString(SearchLyricsDestination.ARG_ARTIST)
                    read["album"] = entry.arguments.readString(SearchLyricsDestination.ARG_ALBUM)
                    read["date"] = entry.arguments.readString(SearchLyricsDestination.ARG_DATE)
                    Text("lyrics")
                }
                composable(
                    route = PluginConfigDestination.PATTERN,
                    arguments = listOf(
                        navArgument(PluginConfigDestination.ARG_PLUGIN_ID) {
                            type = NavType.StringType
                        }
                    )
                ) { entry ->
                    read["pluginId"] = entry.arguments.readString(
                        PluginConfigDestination.ARG_PLUGIN_ID
                    )
                    Text("config")
                }
            }
        }

        val controller = controllerRef.get()

        // A keyword that exercises every character the encoder has to handle: space, ampersand,
        // slash, plus, percent sign, CJK -- i.e. what a tag actually contains.
        val keyword = "周华健 朋友 & 朋友/朋友+50%"
        runOnIdle { controller.navigate(SearchResultsDestination(keyword).route) }
        waitForIdle()
        assertEquals(keyword, read["keyword"], "search_results keyword must round trip")
        onNodeWithText("results").assertExists()

        runOnIdle { controller.popBackStack() }
        waitForIdle()

        // `null` keyword: the optional query parameter is omitted entirely rather than sent empty,
        // because "no pre-filled query" and "pre-filled with the empty string" are the same thing to
        // the screen but only one of them is what Android sent.
        runOnIdle { controller.navigate(SearchResultsDestination(null).route) }
        waitForIdle()
        assertNull(read["keyword"], "a null keyword must arrive as null, not as \"\"")
        onNodeWithText("results").assertExists()

        runOnIdle { controller.popBackStack() }
        waitForIdle()

        // Empty strings are the normal case for the lyrics page: the metadata editor sends
        // `editingTagData?.title.orEmpty()` for every one of the four fields.
        runOnIdle {
            controller.navigate(SearchLyricsDestination(title = "", artist = "", album = "", date = "").route)
        }
        waitForIdle()
        assertEquals("", read["title"], "an empty path segment would not have matched at all")
        assertEquals("", read["artist"])
        assertEquals("", read["album"])
        assertEquals("", read["date"])
        onNodeWithText("lyrics").assertExists()

        runOnIdle { controller.popBackStack() }
        waitForIdle()

        runOnIdle {
            controller.navigate(
                SearchLyricsDestination(
                    title = "山丘",
                    artist = "李宗盛",
                    album = "山丘",
                    date = "2013"
                ).route
            )
        }
        waitForIdle()
        assertEquals("山丘", read["title"])
        assertEquals("李宗盛", read["artist"])
        assertEquals("山丘", read["album"])
        assertEquals("2013", read["date"])
        onNodeWithText("lyrics").assertExists()

        runOnIdle { controller.popBackStack() }
        waitForIdle()

        runOnIdle { controller.navigate(PluginConfigDestination("com.example.demo").route) }
        waitForIdle()
        assertEquals("com.example.demo", read["pluginId"], "the plugin id is a path argument")
        onNodeWithText("config").assertExists()

        // The patterns must not be registered as anything but themselves; a route typo in a
        // destination object would otherwise only show up when a user clicked the button.
        assertEquals("search_results?keyword={keyword}", SearchResultsDestination.PATTERN)
        assertEquals("search_lyrics?title={title}&artist={artist}&album={album}&date={date}",
            SearchLyricsDestination.PATTERN)
        assertEquals("search_cover?keyword={keyword}", SearchCoverDestination.PATTERN)
        assertEquals("plugin_manager", PluginManagerDestination.ROUTE)
        assertEquals("plugin_config/{pluginId}", PluginConfigDestination.PATTERN)
        // The route built for a value interpolates into the placeholder, never appends to a template.
        assertTrue(SearchResultsDestination("a b").route == "search_results?keyword=a%20b")
        assertTrue(SearchCoverDestination(null).route == "search_cover")
        assertTrue(PluginConfigDestination("x").route == "plugin_config/x")
    }

    @Test
    fun `result keys are namespaced by the sending route`() {
        assertEquals("search_lyrics:result", resultKey(SearchLyricsDestination.BASE))
        assertEquals("search_results:result", resultKey(SearchResultsDestination.BASE))
        // Different senders must not collide: the metadata editor receives lyrics from both of these.
        assertTrue(
            resultKey(SearchLyricsDestination.BASE) != resultKey(SearchResultsDestination.BASE)
        )
    }

    private fun stringArg(name: String) = navArgument(name) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }
}

/** `entry.arguments` is a `SavedState` on the desktop, so an argument is read through the reader. */
private fun SavedState?.readString(key: String): String? =
    this?.read { getStringOrNull(key) }
