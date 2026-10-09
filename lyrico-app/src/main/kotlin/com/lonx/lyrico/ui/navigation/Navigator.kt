package com.lonx.lyrico.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController

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

    override fun navigate(direction: NavDirection) {
        controller.navigate(direction.route)
    }

    override fun popBackStack(): Boolean = controller.popBackStack()

    override fun navigateUp(): Boolean = controller.navigateUp()
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
