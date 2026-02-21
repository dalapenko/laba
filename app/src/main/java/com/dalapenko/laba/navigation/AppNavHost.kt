package com.dalapenko.laba.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.dalapenko.laba.feature.library.LibraryScreen
import com.dalapenko.laba.feature.player.PlayerScreen

@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Library) {
        composable<Library> {
            LibraryScreen(
                onBookClick = { bookId ->
                    navController.navigate(Player(bookId))
                },
            )
        }
        composable<Player>(
            enterTransition = {
                slideInVertically(initialOffsetY = { it }) + fadeIn(initialAlpha = 0.3f)
            },
            exitTransition = {
                slideOutVertically(targetOffsetY = { it }) + fadeOut()
            },
            popEnterTransition = {
                slideInVertically(initialOffsetY = { it }) + fadeIn(initialAlpha = 0.3f)
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
