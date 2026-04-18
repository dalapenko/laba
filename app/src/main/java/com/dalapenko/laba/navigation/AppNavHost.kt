package com.dalapenko.laba.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.dalapenko.laba.feature.library.LibraryScreen
import com.dalapenko.laba.feature.player.PlayerScreen
import com.dalapenko.laba.feature.settings.SettingsScreen

private const val SETTINGS_TRANSITION_MS = 380
private const val PLAYER_TRANSITION_ALPHA = 0.3f

@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Library) {
        composable<Library> {
            LibraryScreen(
                onBookClick = { bookId ->
                    navController.navigate(Player(bookId))
                },
                onSettingsClick = {
                    navController.navigate(Settings)
                },
            )
        }
        composable<Settings>(
            enterTransition = {
                slideInHorizontally(tween(SETTINGS_TRANSITION_MS, easing = FastOutSlowInEasing)) { it }
            },
            exitTransition = {
                slideOutHorizontally(tween(SETTINGS_TRANSITION_MS, easing = FastOutSlowInEasing)) { it }
            },
            popEnterTransition = {
                slideInHorizontally(tween(SETTINGS_TRANSITION_MS, easing = FastOutSlowInEasing)) { -it }
            },
            popExitTransition = {
                slideOutHorizontally(tween(SETTINGS_TRANSITION_MS, easing = FastOutSlowInEasing)) { it }
            },
        ) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<Player>(
            enterTransition = {
                slideInVertically(initialOffsetY = { it }) + fadeIn(initialAlpha = PLAYER_TRANSITION_ALPHA)
            },
            exitTransition = {
                slideOutVertically(targetOffsetY = { it }) + fadeOut()
            },
            popEnterTransition = {
                slideInVertically(initialOffsetY = { it }) + fadeIn(initialAlpha = PLAYER_TRANSITION_ALPHA)
            },
            popExitTransition = {
                slideOutVertically(targetOffsetY = { it }) + fadeOut()
            },
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<Player>()
            PlayerScreen(
                bookId = route.bookId,
                autoPlay = route.autoPlay,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
