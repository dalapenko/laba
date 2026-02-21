package com.dalapenko.laba

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.dalapenko.laba.navigation.AppNavHost
import com.dalapenko.laba.ui.theme.LabaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LabaTheme {
                val navController = rememberNavController()
                AppNavHost(navController)
            }
        }
    }
}
