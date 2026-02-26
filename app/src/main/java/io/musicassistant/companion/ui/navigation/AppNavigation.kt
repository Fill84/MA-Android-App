package io.musicassistant.companion.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.ui.launcher.LauncherScreen
import io.musicassistant.companion.ui.main.MainScreen
import io.musicassistant.companion.ui.settings.SettingsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Routes {
    const val LAUNCHER = "launcher"
    const val MAIN = "main"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsModule.getRepository(context) }

    NavHost(
        navController = navController,
        startDestination = Routes.LAUNCHER
    ) {
        composable(Routes.LAUNCHER) {
            LauncherScreen(
                onServerConnected = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.LAUNCHER) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.MAIN) {
            MainScreen(
                onSwitchServer = {
                    CoroutineScope(Dispatchers.IO).launch {
                        settingsRepository.clearServer()
                    }
                    navController.navigate(Routes.LAUNCHER) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
