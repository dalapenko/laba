package com.dalapenko.laba.navigation

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
        composable<Player> { backStackEntry ->
            val route = backStackEntry.toRoute<Player>()
            PlayerScreen(
                bookId = route.bookId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
