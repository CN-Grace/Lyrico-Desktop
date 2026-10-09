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
 */
@Composable
fun LyricoNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    // Temporary start route for this batch: AppLogScreen is the only ported screen so far, and it is
    // a real-data screen (Room-backed log list) rather than a placeholder. Switched to
    // LibraryHomeDestination once the library-home wave lands.
    startDestination: NavDirection = AppLogsDestination(),
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
        }
    }
}
