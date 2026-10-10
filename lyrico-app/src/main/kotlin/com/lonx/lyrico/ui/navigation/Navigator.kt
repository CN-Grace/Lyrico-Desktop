package com.lonx.lyrico.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import com.lonx.lyrico.utils.logging.PlatformLog

/**
 * [Navigator] backed by a real [NavHostController].
 *
 * Navigation Compose is reused rather than hand-rolling a back stack for two concrete reasons:
 *
 * 1. **`NavBackStackEntry` is a `ViewModelStoreOwner`.** `koinViewModel()` inside a
 *    `composable(route) { }` block therefore gets one ViewModel per back-stack entry, which is the
 *    Android behaviour the ported view models were written against. A hand-rolled `when (current)`
 *    host would have to reimplement `ViewModelStore` ownership plus `SavedStateHandle` to match it.
 * 2. **The transition API is the same.** `LyricoApp.kt`'s slide transitions were written against
 *    `AnimatedContentTransitionScope<NavBackStackEntry>`, which is what `NavHost` takes here too.
 */
internal class NavControllerNavigator(
    private val controller: NavHostController,
) : Navigator {

    /**
     * Pushes [direction]'s route, or logs and does nothing when the graph does not have it yet.
     *
     * Three of the songs page's navigation targets (settings, local search, edit metadata) have no
     * screen yet -- their batches come later. Navigation Compose signals "no destination matches this
     * route" with `IllegalArgumentException` (measured in the shipped `NavController` bytecode, not
     * assumed), and letting that escape would take the whole window down the moment a user clicks a
     * song row. So the miss is swallowed here and written to the developer log.
     *
     * This is an *explicit* degradation, not silent: it is logged, it is documented in `PLAN.md`, and
     * [com.lonx.lyrico.ui.navigation.NavigatorTest] pins both halves of the behaviour -- an
     * unregistered route is a no-op, a registered one still navigates. Delete this `catch` once the
     * last screen is registered.
     */
    override fun navigate(direction: NavDirection) {
        try {
            controller.navigate(direction.route)
        } catch (notPortedYet: IllegalArgumentException) {
            PlatformLog.w(
                TAG,
                "No destination for route '${direction.route}'; the screen is not ported yet " +
                    "(${notPortedYet.message})",
            )
        }
    }

    override fun popBackStack(): Boolean = controller.popBackStack()

    override fun navigateUp(): Boolean = controller.navigateUp()

    private companion object {
        const val TAG = "Navigator"
    }
}

/**
 * One navigator per controller, so screens do not capture a stale controller across recomposition.
 *
 * Screens take a `Navigator` parameter rather than reaching for a `CompositionLocal` because that is
 * how they were written (the generated NavHost passed the Destinations navigator down as an
 * argument), and because it keeps a screen renderable in a test without a navigation graph.
 */
@Composable
fun rememberNavigator(controller: NavHostController): Navigator =
    remember(controller) { NavControllerNavigator(controller) }
