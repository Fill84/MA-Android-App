package io.musicassistant.companion.ui.navigation

import android.content.Intent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.settings.SettingsModule
import io.musicassistant.companion.service.MusicService
import io.musicassistant.companion.service.ServiceLocator
import io.musicassistant.companion.ui.home.HomeScreen
import io.musicassistant.companion.ui.home.HomeViewModel
import io.musicassistant.companion.ui.launcher.LauncherScreen
import io.musicassistant.companion.ui.library.AlbumDetailScreen
import io.musicassistant.companion.ui.library.ArtistDetailScreen
import io.musicassistant.companion.ui.library.LibraryScreen
import io.musicassistant.companion.ui.library.LibraryViewModel
import io.musicassistant.companion.ui.library.PlaylistDetailScreen
import io.musicassistant.companion.ui.player.MiniPlayer
import io.musicassistant.companion.ui.player.NowPlayingScreen
import io.musicassistant.companion.ui.player.PlayerViewModel
import io.musicassistant.companion.ui.player.QueueScreen
import io.musicassistant.companion.ui.search.SearchScreen
import io.musicassistant.companion.ui.search.SearchViewModel
import io.musicassistant.companion.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

object Routes {
    const val LAUNCHER = "launcher"
    const val MAIN = "main"
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val NOW_PLAYING = "now_playing"
    const val QUEUE = "queue"
    const val ARTIST_DETAIL = "artist/{artistId}"
    const val ALBUM_DETAIL = "album/{albumId}"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
}

private data class BottomNavItem(val route: String, val label: String, val icon: ImageVector)

private val bottomNavItems =
        listOf(
                BottomNavItem(Routes.HOME, "Home", Icons.Default.Home),
                BottomNavItem(Routes.SEARCH, "Search", Icons.Default.Search),
                BottomNavItem(Routes.LIBRARY, "Library", Icons.Default.LibraryMusic),
                BottomNavItem(Routes.SETTINGS, "Settings", Icons.Default.Settings)
        )

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsModule.getRepository(context) }
    val scope = rememberCoroutineScope()

    NavHost(navController = navController, startDestination = Routes.LAUNCHER) {
        composable(Routes.LAUNCHER) {
            LauncherScreen(
                    onServerConnected = {
                        // Start the music service to connect APIs
                        val intent =
                                Intent(context, MusicService::class.java).apply {
                                    action = MusicService.ACTION_START
                                }
                        context.startService(intent)

                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.LAUNCHER) { inclusive = true }
                        }
                    }
            )
        }

        composable(Routes.MAIN) {
            MainAppScreen(
                    onSwitchServer = {
                        scope.launch { settingsRepository.clearServer() }
                        ServiceLocator.destroy()
                        navController.navigate(Routes.LAUNCHER) {
                            popUpTo(Routes.MAIN) { inclusive = true }
                        }
                    }
            )
        }
    }
}

@Composable
private fun MainAppScreen(onSwitchServer: () -> Unit) {
    val innerNavController = rememberNavController()
    val navBackStackEntry by innerNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Shared ViewModels
    val playerViewModel: PlayerViewModel = viewModel()
    val libraryViewModel: LibraryViewModel = viewModel()
    val homeViewModel: HomeViewModel = viewModel()
    val searchViewModel: SearchViewModel = viewModel()

    val showBottomBar =
            currentRoute in listOf(Routes.HOME, Routes.SEARCH, Routes.LIBRARY, Routes.SETTINGS)
    val showMiniPlayer = currentRoute != Routes.NOW_PLAYING && currentRoute != Routes.QUEUE

    Scaffold(
            bottomBar = {
                Column {
                    if (showMiniPlayer) {
                        MiniPlayer(
                                playerViewModel = playerViewModel,
                                onClick = { innerNavController.navigate(Routes.NOW_PLAYING) }
                        )
                    }
                    if (showBottomBar) {
                        NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 0.dp,
                        ) {
                            bottomNavItems.forEach { item ->
                                NavigationBarItem(
                                        selected = currentRoute == item.route,
                                        onClick = {
                                            innerNavController.navigate(item.route) {
                                                popUpTo(
                                                        innerNavController.graph
                                                                .findStartDestination()
                                                                .id
                                                ) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(item.icon, contentDescription = item.label) },
                                        label = {
                                            Text(
                                                    item.label,
                                                    style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            NavHost(navController = innerNavController, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                            homeViewModel = homeViewModel,
                            playerViewModel = playerViewModel,
                            onAlbumClick = { albumId ->
                                innerNavController.navigate("album/$albumId")
                            },
                            onTrackClick = { track ->
                                playerViewModel.playMedia(track.uri, MediaType.TRACK, "play")
                            },
                            onPlayerClick = { player ->
                                playerViewModel.selectPlayer(player.playerId)
                                innerNavController.navigate(Routes.NOW_PLAYING)
                            }
                    )
                }

                composable(Routes.SEARCH) {
                    SearchScreen(
                            searchViewModel = searchViewModel,
                            onArtistClick = { innerNavController.navigate("artist/$it") },
                            onAlbumClick = { innerNavController.navigate("album/$it") },
                            onTrackClick = { track ->
                                playerViewModel.playMedia(track.uri, MediaType.TRACK, "play")
                            },
                            onPlaylistClick = { innerNavController.navigate("playlist/$it") },
                            onRadioClick = { radio ->
                                playerViewModel.playMedia(radio.uri, MediaType.RADIO, "play")
                            }
                    )
                }

                composable(Routes.LIBRARY) {
                    LibraryScreen(
                            libraryViewModel = libraryViewModel,
                            onArtistClick = { innerNavController.navigate("artist/$it") },
                            onAlbumClick = { innerNavController.navigate("album/$it") },
                            onTrackClick = { track ->
                                playerViewModel.playMedia(track.uri, MediaType.TRACK, "play")
                            },
                            onPlaylistClick = { innerNavController.navigate("playlist/$it") },
                            onRadioClick = { radio ->
                                playerViewModel.playMedia(radio.uri, MediaType.RADIO, "play")
                            }
                    )
                }

                composable(Routes.SETTINGS) { SettingsScreen(onBack = { onSwitchServer() }) }

                composable(
                        Routes.NOW_PLAYING,
                        enterTransition = {
                            slideInVertically(initialOffsetY = { it })
                        },
                        exitTransition = {
                            slideOutVertically(targetOffsetY = { it })
                        },
                        popEnterTransition = {
                            slideInVertically(initialOffsetY = { -it })
                        },
                        popExitTransition = {
                            slideOutVertically(targetOffsetY = { it })
                        }
                ) {
                    NowPlayingScreen(
                            playerViewModel = playerViewModel,
                            onBack = { innerNavController.popBackStack() },
                            onOpenQueue = { innerNavController.navigate(Routes.QUEUE) }
                    )
                }

                composable(
                        Routes.QUEUE,
                        enterTransition = {
                            slideInVertically(initialOffsetY = { it })
                        },
                        exitTransition = {
                            slideOutVertically(targetOffsetY = { it })
                        },
                        popExitTransition = {
                            slideOutVertically(targetOffsetY = { it })
                        }
                ) {
                    QueueScreen(
                            playerViewModel = playerViewModel,
                            onBack = { innerNavController.popBackStack() }
                    )
                }

                composable(
                        Routes.ARTIST_DETAIL,
                        arguments = listOf(navArgument("artistId") { type = NavType.StringType })
                ) { entry ->
                    val artistId = entry.arguments?.getString("artistId") ?: return@composable
                    ArtistDetailScreen(
                            artistId = artistId,
                            libraryViewModel = libraryViewModel,
                            playerViewModel = playerViewModel,
                            onAlbumClick = { innerNavController.navigate("album/$it") },
                            onBack = { innerNavController.popBackStack() }
                    )
                }

                composable(
                        Routes.ALBUM_DETAIL,
                        arguments = listOf(navArgument("albumId") { type = NavType.StringType })
                ) { entry ->
                    val albumId = entry.arguments?.getString("albumId") ?: return@composable
                    AlbumDetailScreen(
                            albumId = albumId,
                            libraryViewModel = libraryViewModel,
                            playerViewModel = playerViewModel,
                            onBack = { innerNavController.popBackStack() }
                    )
                }

                composable(
                        Routes.PLAYLIST_DETAIL,
                        arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
                ) { entry ->
                    val playlistId = entry.arguments?.getString("playlistId") ?: return@composable
                    PlaylistDetailScreen(
                            playlistId = playlistId,
                            libraryViewModel = libraryViewModel,
                            playerViewModel = playerViewModel,
                            onBack = { innerNavController.popBackStack() }
                    )
                }
            }
        }
    }
}
