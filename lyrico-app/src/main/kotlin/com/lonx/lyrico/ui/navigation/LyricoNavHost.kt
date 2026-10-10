package com.lonx.lyrico.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lonx.lyrico.screens.AppLogScreen
import com.lonx.lyrico.screens.library.SongsPage
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
 * The start route is the songs page for this batch. Android's start route was `library_home`, the
 * three-tab shell; that shell needs `SongsPage` **and** `AlbumsPage`/`ArtistsPage`, so it becomes its
 * own batch once the album and artist pages exist (see `PLAN.md`). Until then the ported songs page is
 * the app's entry screen, which is a real screen backed by real data rather than a placeholder.
 * `app_logs` stays registered.
 */
@Composable
fun LyricoNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: NavDirection = SongsDestination(),
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
            composable(SongsDestination.ROUTE) {
                SongsPage(navigator = navigator)
            }
        }
    }
}
