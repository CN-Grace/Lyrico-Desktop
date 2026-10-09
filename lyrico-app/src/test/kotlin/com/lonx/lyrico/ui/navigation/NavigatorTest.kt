package com.lonx.lyrico.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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

            assertEquals(
                created.size,
                created.map { it.serial }.distinct().size,
                "every entry must get its own instance, got ${created.map { it.serial }}",
            )
        } finally {
            stopKoin()
        }
    }
}
