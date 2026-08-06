package com.jamesm92.nomadportal.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.ui.home.HomeScreen
import com.jamesm92.nomadportal.ui.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
}

@Composable
fun NomadNavHost(
    interfaceController: InterfaceController,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(onOpenSettings = { navController.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                interfaceController = interfaceController,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
