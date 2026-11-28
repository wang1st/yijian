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

package dev.patrickgold.florisboard.app.main

import androidx.compose.foundation.layout.heightIn
import dev.patrickgold.florisboard.ime.translation.review.PersonalPractice
import dev.patrickgold.florisboard.ime.translation.review.PracticeSnapshot
import dev.patrickgold.florisboard.app.design.Shape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.app.design.Space
import dev.patrickgold.florisboard.app.design.YijianTheme
import dev.patrickgold.florisboard.ime.translation.mistake.ReviewStats
import dev.patrickgold.florisboard.ime.translation.mistake.ReviewStatsSource
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import org.florisboard.lib.compose.stringRes

/**
 * 首页.
 *
 * One question, answered in reading order: what is left to do today, and what do I press.
 * Everything else on the page is set at a size that can be read but cannot compete — the old
 * screen put seven equally loud cards in the user's way, and none of them told them anything
 * the first line does not.
 *
 * The visual language is deliberately quiet: paper ground, ink text, hairline rules, and a
 * single accent that appears only on the thing you can act on.
 */
@Composable
fun MainHomeScreen() {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colors = YijianTheme.colors
    val type = YijianTheme.type

    val isImeEnabled by InputMethodUtils.observeIsFlorisboardEnabled()
    val isImeSelected by InputMethodUtils.observeIsFlorisboardSelected()

    var stats by remember { mutableStateOf<ReviewStats?>(null) }
    var practice by remember { mutableStateOf<PracticeSnapshot?>(null) }
    LaunchedEffect(Unit) {
        practice = withContext(Dispatchers.IO) { PersonalPractice.shared.snapshot() }
        stats = runCatching { ReviewStatsSource.snapshot() }.getOrNull()
    }

    FlorisScreenNg(
        title = "译键",
        previewFieldVisible = false,
        navigationIconVisible = false,
        mainNavigationVisible = true,
        compactAppBar = true,
        background = colors.paper,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter),
        ) {
            // Status, said plainly and only when it is wrong.
            if (!isImeEnabled || !isImeSelected) {
                Spacer(modifier = Modifier.height(Space.lg))
                Notice(
                    text = if (!isImeEnabled) {
                        stringRes(R.string.settings__home__ime_not_enabled)
                    } else {
                        stringRes(R.string.settings__home__ime_not_selected)
                    },
                    onClick = {
                        if (!isImeEnabled) {
                            InputMethodUtils.showImeEnablerActivity(context)
                        } else {
                            InputMethodUtils.showImePicker(context)
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(Space.xxl))
            val snapshot = practice
            val task = snapshot?.task
            Text("今天的场景", style = type.label, color = colors.inkMuted)
            Spacer(modifier = Modifier.height(Space.sm))
            Text(when {
                snapshot == null -> "…"
                task != null -> task.title
                snapshot.sources > 0 -> "今天的场景练完了"
                else -> "从你的英语记录开始"
            }, style = type.display, color = colors.ink)
            Spacer(modifier = Modifier.height(Space.sm))
            Text(when {
                task != null -> task.prompt
                snapshot != null && snapshot.sources > 0 -> "近 28 天首答通过 ${snapshot.passed} / ${snapshot.graded}"
                else -> "导入个人练习库，在新情境里试试自己想说的话。"
            }, style = type.body, color = colors.inkMuted)

            Spacer(modifier = Modifier.height(Space.xl))
            PrimaryAction(
                label = "开始场景练习",
                onClick = { navController.navigate(Routes.Settings.PersonalPractice) },
            )

            Spacer(modifier = Modifier.height(Space.sm))
            // Full-width tappable row: a bare Text with clickable only covers the glyphs, which
            // is a target far below the 48 dp minimum and easy to miss.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { navController.navigate(Routes.Settings.ExpressionPractice) },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "练地道说法",
                    style = type.body,
                    color = colors.ink,
                )
            }

            // Everything below is reference, set small enough not to compete. The figures are
            // about points, not cards: a card count says how much work is queued, a point count
            // says how much of your English is still wrong.
            val st = stats
            if (st != null) {
                Spacer(modifier = Modifier.height(Space.xxxl))
                RuleLine()
                Spacer(modifier = Modifier.height(Space.lg))
                FigureRow(
                    figures = listOf(
                        "待解决" to "${st.openPoints} 个",
                        "已掌握" to "${st.masteredPoints} 个",
                        "连续" to "${st.streakDays} 天",
                    ),
                )

                if (st.expressions > 0) {
                    Spacer(modifier = Modifier.height(Space.lg))
                    Text(
                        text = "已积累 ${st.expressions} 条地道说法",
                        style = type.small,
                        color = colors.inkMuted,
                        modifier = Modifier.clickable {
                            navController.navigate(Routes.Settings.Expressions)
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(Space.xxl))
            RuleLine()
            Spacer(modifier = Modifier.height(Space.lg))
            Link("翻译设置") { navController.navigate(Routes.Settings.Translation) }
            Spacer(modifier = Modifier.height(Space.xxl))
        }
    }
}

/** A single filled action. The only element on the page allowed to use the accent. */
@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(colors.accent, Shape.action)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.body,
            fontWeight = FontWeight.Medium,
            color = colors.onAccent,
        )
    }
}

/** Three figures in a row, set as data rather than as trophies. */
@Composable
private fun FigureRow(figures: List<Pair<String, String>>) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
        figures.forEach { (label, value) ->
            Column {
                Text(text = label, style = type.label, color = colors.inkMuted)
                Spacer(modifier = Modifier.height(Space.hair))
                Text(text = value, style = type.figure, color = colors.ink)
            }
        }
    }
}


/** A quiet inline notice. One line of colour, not a filled banner. */
@Composable
private fun Notice(text: String, onClick: () -> Unit) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .background(colors.warning),
        )
        Spacer(modifier = Modifier.width(Space.md))
        Text(text = text, style = type.small, color = colors.ink)
    }
}

/** A hairline that separates without boxing. */
@Composable
private fun RuleLine() {
    val colors = YijianTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.rule),
    )
}

/** A text link, underlined by weight rather than by colour. */
@Composable
private fun Link(label: String, onClick: () -> Unit) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    Text(
        text = label,
        style = type.body,
        color = colors.ink,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
