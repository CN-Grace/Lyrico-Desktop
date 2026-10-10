package com.lonx.lyrico.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import com.lonx.lyrico.support.PlatformLogCapture
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hand-written navigation adapter, exercised against a real `NavHost`.
 *
 * Compose Destinations could not come to the desktop (Android-only artifact -- see [NavDirection]), so
 * this adapter is load-bearing for 26 screens. Two things have to hold, and only a real navigation
 * graph can show them:
 *
 * 1. **`Navigator` does what its three methods claim.** `navigate` pushes, `popBackStack` pops and
 *    reports whether it could (one screen branches on that `Boolean` to decide "leave the app"), and
 *    `navigateUp` goes back.
 * 2. **A destination gets its own `ViewModelStoreOwner`.** `koinViewModel()` inside a screen resolves
 *    against the `NavBackStackEntry`, so visiting a route twice must produce two instances and popping
 *    must discard the instance belonging to the destroyed entry. Every ported screen depends on this;
 *    if it silently resolved a singleton, view models would leak across visits.
 *
 * The graph here is a pair of probe routes rather than `LyricoNavHost`: this test is about the adapter,
 * and tying it to whichever screens happen to be ported would make it fail for unrelated reasons.
 * `AppLogScreenTest` renders the shipped host and its real route.
 *
 * The two remaining tests cover the parts of the adapter that only exist because the port is
 * incremental: a `navigate` to a destination whose screen is not ported yet has to be survivable, and
 * a route argument has to survive a real Windows path (a drive letter, backslashes, spaces, CJK) being
 * percent-encoded into a path segment and decoded back out by the library.
 */
class NavigatorTest {

    /** Counts instantiations, so "a new entry means a new view model" is observable. */
    class ProbeViewModel(val serial: Int) : ViewModel()

    private class TestDirection(override val route: String) : NavDirection

    private val created = mutableListOf<ProbeViewModel>()

    /**
     * Koin is process-global and other tests in this JVM start the real graph, so a plain `startKoin`
     * would either throw or leave a stale graph behind.
     */
    private fun startProbeGraph(module: Module): KoinApplication {
        runCatching { stopKoin() }
        return startKoin { modules(module) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `navigate pushes, popBackStack pops, navigateUp goes back`() = runComposeUiTest {
        val navigatorRef = AtomicReference<Navigator>()
        setContent {
            val controller = rememberNavController()
            // One navigator per controller, exactly as the shipped host builds it.
            navigatorRef.set(rememberNavigator(controller))
            NavHost(navController = controller, startDestination = "a") {
                composable("a") { Text("screen-a") }
                composable("b") { Text("screen-b") }
                composable("c") { Text("screen-c") }
            }
        }

        onNodeWithText("screen-a").assertExists()

        runOnIdle { navigatorRef.get().navigate(TestDirection("b")) }
        onNodeWithText("screen-b").assertExists()

        // navigateUp is the platform back gesture; it reports that it actually moved.
        assertTrue(runOnIdle { navigatorRef.get().navigateUp() })
        onNodeWithText("screen-a").assertExists()

        runOnIdle { navigatorRef.get().navigate(TestDirection("b")) }
        runOnIdle { navigatorRef.get().navigate(TestDirection("c")) }
        onNodeWithText("screen-c").assertExists()

        // popBackStack unwinds one entry at a time, deepest first.
        assertTrue(runOnIdle { navigatorRef.get().popBackStack() })
        onNodeWithText("screen-b").assertExists()
        assertTrue(runOnIdle { navigatorRef.get().popBackStack() })
        onNodeWithText("screen-a").assertExists()

        // On the start destination there is nothing left to pop. `LibraryHomeScreen` reads exactly this
        // `false` to decide that a back request means "leave the app", so it must not be `true` and it
        // must not throw.
        assertEquals(false, runOnIdle { navigatorRef.get().popBackStack() })
        onNodeWithText("screen-a").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `each back stack entry owns its view model and popping discards it`() = runComposeUiTest {
        startProbeGraph(
            module {
                viewModel { ProbeViewModel(created.size).also { created += it } }
            }
        )
        try {
            val navigatorRef = AtomicReference<Navigator>()
            setContent {
                val controller = rememberNavController()
                navigatorRef.set(rememberNavigator(controller))
                NavHost(navController = controller, startDestination = "a") {
                    composable("a") {
                        val vm: ProbeViewModel = koinViewModel()
                        Text("a-serial-${vm.serial}")
                    }
                    composable("b") {
                        val vm: ProbeViewModel = koinViewModel()
                        Text("b-serial-${vm.serial}")
                    }
                }
            }

            assertEquals(1, runOnIdle { created.size }, "the start destination must build exactly one view model")

            runOnIdle { navigatorRef.get().navigate(TestDirection("b")) }
            // The new entry is composed by a later frame, so wait for its content before counting; a
            // count read straight after `navigate` would race the recomposition.
            onNodeWithText("b-serial-1").assertExists()
            assertEquals(2, runOnIdle { created.size }, "a new entry must get a new view model, not a shared one")

            runOnIdle { navigatorRef.get().navigate(TestDirection("b")) }
            onNodeWithText("b-serial-2").assertExists()
            assertEquals(3, runOnIdle { created.size }, "visiting the same route again is a new entry")

            runOnIdle { navigatorRef.get().popBackStack() }
            onNodeWithText("b-serial-1").assertExists()
            runOnIdle { navigatorRef.get().popBackStack() }
            // Back on the original entry, whose view model is still the first one: entries below the top
            // are retained rather than recreated. (A recreated entry would render `a-serial-3`.)
            onNodeWithText("a-serial-0").assertExists()

            // The discarded entries are gone from the graph, so going to `b` again builds another one.
            runOnIdle { navigatorRef.get().navigate(TestDirection("b")) }
            onNodeWithText("b-serial-3").assertExists()
            assertEquals(4, runOnIdle { created.size })

            assertEquals(
                created.size,
                created.map { it.serial }.distinct().size,
                "every entry must get its own instance, got ${created.map { it.serial }}",
            )
        } finally {
            stopKoin()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a route with no destination is logged and dropped instead of taking the window down`() =
        runComposeUiTest {
            val log = PlatformLogCapture().install()
            try {
                val navigatorRef = AtomicReference<Navigator>()
                setContent {
                    val controller = rememberNavController()
                    navigatorRef.set(rememberNavigator(controller))
                    NavHost(navController = controller, startDestination = "a") {
                        composable("a") { Text("screen-a") }
                    }
                }

                onNodeWithText("screen-a").assertExists()

                // `NavController.navigate` throws when nothing in the graph matches. The ported screens
                // call `navigate` for destinations that are declared but not registered yet (the settings
                // screen, in-library search, the metadata editor), and the user must not lose the window
                // for pressing one of them.
                runOnIdle { navigatorRef.get().navigate(TestDirection("not_ported_yet")) }
                onNodeWithText("screen-a").assertExists()

                val warning = log.warnings.single { it.contains("not_ported_yet") }
                assertTrue(
                    warning.contains("not ported yet"),
                    "the log line must say why nothing happened, got: $warning",
                )

                // Dropped, not broken: the controller is still usable afterwards.
                runOnIdle { navigatorRef.get().navigate(TestDirection("a")) }
                onNodeWithText("screen-a").assertExists()
            } finally {
                log.restore()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a route argument survives a real graph, windows path and all`() = runComposeUiTest {
        // Deliberately hostile: a drive letter and backslashes (encoded, or the route splits at them),
        // spaces, non-ASCII, and a dot that must stay literal. This is a real path shape from a Chinese
        // Windows install, taken from the bug the encoder exists to prevent.
        val path = "C:\\Users\\Grace\\音乐\\Naïve - Track 01.mp3"
        val argsRef = AtomicReference<String>()

        setContent {
            val controller = rememberNavController()
            val navigator = rememberNavigator(controller)
            NavHost(navController = controller, startDestination = "a") {
                composable("a") {
                    Text("screen-a")
                    // The navigation library decodes the path segment before it hands the argument over,
                    // so whatever comes back out is what the encoder must have put in.
                    LaunchedEffect(Unit) {
                        navigator.navigate(EditMetadataDestination(path))
                    }
                }
                composable(EditMetadataDestination.PATTERN) { entry ->
                    // `arguments` is a `SavedState` on the desktop, not a `Bundle`, so the argument is read
                    // through the reader -- and the library has already percent-decoded it by this point.
                    val decoded = entry.arguments?.read {
                        getStringOrNull(EditMetadataDestination.ARG_SONG_FILE_URI)
                    }
                    argsRef.set(decoded)
                    Text("metadata-$decoded")
                }
            }
        }

        assertEquals(path, runOnIdle { argsRef.get() }, "the argument must round trip unchanged")
        onNodeWithText("metadata-$path").assertExists()

        // The encoding has to be doing something, or the assertion above would pass on a raw path too.
        val route = EditMetadataDestination(path).route
        assertTrue(route.contains("%5C"), "backslashes must be encoded, got: $route")
        assertTrue(route.contains("%E9%9F%B3"), "non-ASCII must be UTF-8 percent-encoded, got: $route")
        assertTrue(route.endsWith("Track%2001.mp3"), "spaces encoded, dots left alone, got: $route")
    }

    @Test
    fun `the route argument encoder matches the character table it was measured against`() {
        // Unreserved characters plus `!'()*-._~`: the set Android's own `Uri.encode` leaves alone, which
        // is what the navigation library's `internal` encoder does too. The table was measured by
        // calling that encoder over every ASCII byte; this pins the result so a future edit to the
        // allowed set cannot pass unnoticed.
        val allowed = "!'()*-.0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz~"
        assertEquals(allowed, encodeNavRouteArgument(allowed))

        assertEquals("%20", encodeNavRouteArgument(" "))
        assertEquals("%5C", encodeNavRouteArgument("\\"))
        assertEquals("%2F", encodeNavRouteArgument("/"))
        assertEquals("%3A", encodeNavRouteArgument(":"))
        assertEquals("%3F", encodeNavRouteArgument("?"))
        assertEquals("%23", encodeNavRouteArgument("#"))
        assertEquals("%26", encodeNavRouteArgument("&"))
        assertEquals("%2B", encodeNavRouteArgument("+"))
        assertEquals("%E4%B8%AD%E6%96%87", encodeNavRouteArgument("中文"))
        assertEquals("", encodeNavRouteArgument(""))
        assertEquals(
            "a%20b%5Cc%2Fd.mp3",
            encodeNavRouteArgument("a b\\c/d.mp3"),
            "only the disallowed characters change",
        )
    }
}
