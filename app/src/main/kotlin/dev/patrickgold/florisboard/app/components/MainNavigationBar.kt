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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.design.Space
import dev.patrickgold.florisboard.app.design.YijianTheme

/**
 * The four top-level destinations.
 *
 * No colour and no mascot per tab: on a paper-and-ink surface, four saturated discs read as
 * stickers on the page. The label carries the meaning and a rule carries the state.
 */
enum class MainTab(
    val label: String,
    val icon: ImageVector,
) {
    HOME("译键", Icons.Outlined.Translate),
    REVIEW("复习", Icons.Outlined.School),
    STATS("报告", YijianIcons.BarChart),
    SETTINGS("设置", Icons.Outlined.Settings),
    ;
}

private fun isOnTab(destination: NavDestination?, tab: MainTab): Boolean {
    if (destination == null) return false
    return when (tab) {
        MainTab.HOME -> destination.hierarchy.any { it.hasRoute(Routes.Main.Home::class) }
        MainTab.REVIEW -> destination.hierarchy.any { it.hasRoute(Routes.Settings.Review::class) }
        MainTab.STATS -> destination.hierarchy.any { it.hasRoute(Routes.Main.Stats::class) }
        MainTab.SETTINGS -> destination.hierarchy.any { it.hasRoute(Routes.Settings.Home::class) }
    }
}

/**
 * Bottom navigation: icon and label over a hairline, the active tab marked by the ink rule
 * above it. Nothing is filled, nothing is coloured.
 */
@Composable
fun MainNavigationBar() {
    val navController: NavController = LocalNavController.current
    val currentDestination by navController.currentBackStackEntryAsState()
    val colors = YijianTheme.colors
    val type = YijianTheme.type

    Column(modifier = Modifier.fillMaxWidth().background(colors.paper)) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = Space.sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainTab.entries.forEach { tab ->
                val selected = isOnTab(currentDestination?.destination, tab)
                // The rule above the label draws in when a tab becomes active.
                val ruleWidth by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = tween(180),
                    label = "tabRule",
                )
                Column(
                    modifier = Modifier
                        .clickable(enabled = !selected) {
                            val route: Any = when (tab) {
                                MainTab.HOME -> Routes.Main.Home
                                MainTab.REVIEW -> Routes.Settings.Review
                                MainTab.STATS -> Routes.Main.Stats
                                MainTab.SETTINGS -> Routes.Settings.Home
                            }
                            navController.navigate(route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        .padding(horizontal = Space.md)
                        .heightIn(min = 52.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The active rule sits above the icon. Fixed width, animated via alpha:
                    // a fractional fillMaxWidth inside this Column would stretch the column to
                    // the row's width and push the other tabs off screen.
                    Box(
                        modifier = Modifier
                            .size(width = 20.dp, height = 2.dp)
                            .alpha(ruleWidth)
                            .background(colors.ink),
                    )
                    Spacer(modifier = Modifier.height(Space.sm))
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = colors.ink,
                        modifier = Modifier
                            .size(22.dp)
                            .alpha(if (selected) 1f else 0.55f),
                    )
                    Spacer(modifier = Modifier.height(Space.xs))
                    Text(
                        text = tab.label,
                        style = type.label,
                        color = colors.ink,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.alpha(if (selected) 1f else 0.55f),
                    )
                }
            }
        }
    }
}
