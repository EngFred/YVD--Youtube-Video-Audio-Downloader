package com.engfred.yvd.ui

import android.app.AlertDialog
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.engfred.yvd.ui.downloads.DownloadsScreen
import com.engfred.yvd.ui.home.HomeScreen
import com.engfred.yvd.ui.home.HomeViewModel
import com.engfred.yvd.util.BubblePermissionHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(homeViewModel: HomeViewModel) {
    val navController = rememberNavController()
    val homeState by homeViewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (!BubblePermissionHelper.canDrawOverlays(context)) {
            AlertDialog.Builder(context)
                .setTitle("Enable Floating Bubble")
                .setMessage("YV Downloader uses a floating bubble so you can quickly return to the app after copying a YouTube link. Please enable 'Appear on top' on the next screen.")
                .setPositiveButton("Grant Permission") { _, _ -> BubblePermissionHelper.openOverlaySettings(context) }
                .setNegativeButton("Not Now", null)
                .show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            composable(
                route = "home",
                enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(400)) + fadeIn() },
                exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(400)) + fadeOut() }
            ) {
                HomeScreen(viewModel = homeViewModel)
            }

            composable(
                route = "downloads",
                enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(400)) + fadeIn() },
                exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(400)) + fadeOut() }
            ) {
                DownloadsScreen()
            }
        }

        // Custom Floating Bottom Navigation
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .padding(horizontal = 48.dp)
                .shadow(16.dp, RoundedCornerShape(32.dp), spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            NavigationBar(
                containerColor = Color.Transparent,
                modifier = Modifier.height(64.dp),
                tonalElevation = 0.dp
            ) {
                val navItemColors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )

                NavigationBarItem(
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = { Text("Home") },
                    selected = currentDestination?.hierarchy?.any { it.route == "home" } == true,
                    colors = navItemColors,
                    onClick = {
                        navController.navigate("home") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = {
                        BadgedBox(badge = {
                            if (homeState.activeDownloadCount > 0) {
                                Badge { Text(homeState.activeDownloadCount.toString()) }
                            }
                        }) {
                            Icon(Icons.Rounded.DownloadForOffline, contentDescription = null)
                        }
                    },
                    label = { Text("Downloads") },
                    selected = currentDestination?.hierarchy?.any { it.route == "downloads" } == true,
                    colors = navItemColors,
                    onClick = {
                        navController.navigate("downloads") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    }
}