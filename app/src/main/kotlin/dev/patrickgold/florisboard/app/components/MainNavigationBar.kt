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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes

/**
 * Top-level destinations of the app.
 *
 * Each tab carries its own colour, as Duolingo's bar does: the bar reads as a row of distinct
 * destinations rather than a set of neutral glyphs, and the active one is obvious without
 * needing a label.
 */
enum class MainTab(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val shade: Color,
) {
    HOME("译键", Icons.Outlined.Translate, Color(0xFF58CC02), Color(0xFF46A302)),
    REVIEW("复习", Icons.Outlined.School, Color(0xFF1CB0F6), Color(0xFF1899D6)),
    STATS("学习报告", Icons.Outlined.StarOutline, Color(0xFFFFC800), Color(0xFFE7A601)),
    SETTINGS("设置", Icons.Outlined.Settings, Color(0xFFCE82FF), Color(0xFFA568CC)),
    ;

    /** Fill used when the tab is not selected — desaturated but still recognisable. */
    val idle: Color
        get() = Color(0xFF7C7C7C)
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

/**
 * Bottom navigation in Duolingo's style: a coloured, raised icon per tab with no labels, and
 * the active tab enclosed in a rounded outline. Labels are gone on purpose — the icons carry
 * the meaning and the space saved keeps the bar shallow.
 */
@Composable
fun MainNavigationBar() {
    val navController: NavController = LocalNavController.current
    val currentDestination by navController.currentBackStackEntryAsState()
    val dark = isSystemInDarkTheme()
    val barColor = if (dark) Color(0xFF16171A) else Color.White

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEach { tab ->
            val selected = isOnTab(currentDestination?.destination, tab)
            TabButton(
                tab = tab,
                selected = selected,
                onClick = {
                    if (selected) return@TabButton
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
            )
        }
    }
}

/** One tab: raised coloured disc, outline when active, sinks on press. */
@Composable
private fun TabButton(tab: MainTab, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateDpAsState(
        targetValue = if (pressed) 2.dp else 0.dp,
        animationSpec = tween(100),
        label = "tabSink",
    )
    val fill by animateColorAsState(
        targetValue = if (selected) tab.color else tab.idle,
        animationSpec = tween(180),
        label = "tabFill",
    )
    val outline by animateColorAsState(
        targetValue = if (selected) tab.color.copy(alpha = 0.55f) else Color.Transparent,
        animationSpec = tween(180),
        label = "tabOutline",
    )
    val lip = if (selected) tab.shade else Color(0xFF5A5A5A)

    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                // The selection frame sits outside the disc, as in the reference bar.
                if (selected) Modifier.border(2.dp, outline, RoundedCornerShape(16.dp)) else Modifier
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .padding(bottom = 3.dp)
                .offset(y = sink)
                .then(
                    Modifier.drawBehind {
                        // The lip: the disc's colour, one shade darker, showing 3 dp below.
                        drawRoundRect(
                            color = lip,
                            size = Size(size.width, size.height + 3.dp.toPx()),
                            cornerRadius = CornerRadius(14.dp.toPx()),
                        )
                    }
                )
                .clip(RoundedCornerShape(14.dp))
                .background(fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
