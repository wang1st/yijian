/*
 * Copyright (C) 2026 The yijian Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.getValue

/**
 * Top-level destinations of the app. The app is organised as four pages reachable from the
 * bottom bar; everything else (individual settings screens) is pushed on top of them.
 */
enum class MainTab(
    val label: String,
    val icon: ImageVector,
) {
    HOME("译键", Icons.Outlined.Translate),
    REVIEW("复习", Icons.Outlined.School),
    STATS("学习报告", Icons.Outlined.StarOutline),
    SETTINGS("设置", Icons.Outlined.Settings),
}

/** True when [destination] belongs to [tab]'s top-level page. */
private fun isOnTab(destination: NavDestination?, tab: MainTab): Boolean {
    if (destination == null) return false
    return when (tab) {
        MainTab.HOME -> destination.hierarchy.any { it.hasRoute(Routes.Main.Home::class) }
        MainTab.REVIEW -> destination.hierarchy.any { it.hasRoute(Routes.Settings.Review::class) }
        MainTab.STATS -> destination.hierarchy.any { it.hasRoute(Routes.Main.Stats::class) }
        MainTab.SETTINGS -> destination.hierarchy.any { it.hasRoute(Routes.Settings.Home::class) }
    }
}

/** Bottom navigation for the four main pages. */
@Composable
fun MainNavigationBar() {
    val navController = LocalNavController.current
    val currentDestination by navController.currentBackStackEntryAsState()
    NavigationBar {
        MainTab.entries.forEach { tab ->
            val selected = isOnTab(currentDestination?.destination, tab)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (selected) return@NavigationBarItem
                    val route: Any = when (tab) {
                        MainTab.HOME -> Routes.Main.Home
                        MainTab.REVIEW -> Routes.Settings.Review
                        MainTab.STATS -> Routes.Main.Stats
                        MainTab.SETTINGS -> Routes.Settings.Home
                    }
                    navController.navigate(route) {
                        // Switching tabs replaces the current page instead of stacking them.
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(),
            )
        }
    }
}
