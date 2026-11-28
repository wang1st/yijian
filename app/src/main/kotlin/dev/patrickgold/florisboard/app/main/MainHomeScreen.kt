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
import dev.patrickgold.florisboard.ime.translation.review.ReviewSnapshot
import dev.patrickgold.florisboard.ime.translation.review.ReviewStore
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

    var snap by remember { mutableStateOf<ReviewSnapshot?>(null) }
    LaunchedEffect(Unit) { snap = ReviewStore.snapshot() }

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

            val s = snap
            val pending = s?.let { it.dueCount + it.newCount } ?: 0
            val doneToday = s?.reviewedToday ?: 0

            Spacer(modifier = Modifier.height(Space.xxl))

            // The one thing the screen is about.
            when {
                s == null -> Text("…", style = type.display, color = colors.inkMuted)
                pending == 0 && doneToday == 0 -> {
                    Text(
                        text = "还没有可练的句子",
                        style = type.display,
                        color = colors.ink,
                    )
                    Spacer(modifier = Modifier.height(Space.sm))
                    Text(
                        text = "用译键翻译几句英文，它们会自动变成复习卡片。",
                        style = type.body,
                        color = colors.inkMuted,
                    )
                }
                pending == 0 -> {
                    Text("今天已经练完", style = type.display, color = colors.ink)
                    Spacer(modifier = Modifier.height(Space.sm))
                    Text(
                        text = "$doneToday 张，全部过了一遍。明天再见。",
                        style = type.body,
                        color = colors.inkMuted,
                    )
                }
                else -> {
                    Text("今天还有 $pending 张", style = type.display, color = colors.ink)
                    Spacer(modifier = Modifier.height(Space.sm))
                    Text(
                        text = "到期 ${s.dueCount} · 新句子 ${s.newCount}",
                        style = type.small,
                        color = colors.inkMuted,
                    )
                }
            }

            Spacer(modifier = Modifier.height(Space.xl))
            PrimaryAction(
                label = when {
                    pending > 0 -> "开始练习"
                    doneToday > 0 -> "再看一遍"
                    else -> "去翻译"
                },
                onClick = {
                    navController.navigate(
                        if (pending > 0 || doneToday > 0) Routes.Settings.ReviewPractice
                        else Routes.Main.Home
                    )
                },
            )

            // Everything below is reference, set small enough not to compete.
            if (s != null) {
                Spacer(modifier = Modifier.height(Space.xxxl))
                RuleLine()
                Spacer(modifier = Modifier.height(Space.lg))
                FigureRow(
                    figures = listOf(
                        "连续" to "${s.streakDays} 天",
                        "今天" to "$doneToday 张",
                        "正确" to "${s.accuracyToday}%",
                    ),
                )

                Spacer(modifier = Modifier.height(Space.xxl))
                WeekLine(s.lastSevenDays)
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
            .background(colors.accent, dev.patrickgold.florisboard.app.design.Shape.action)
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

/** Seven days as a single line of type, not a bar chart. */
@Composable
private fun WeekLine(days: List<Int>) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    if (days.isEmpty()) return
    val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
    Column {
        Text(text = "本周", style = type.label, color = colors.inkMuted)
        Spacer(modifier = Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            days.forEachIndexed { i, count ->
                val active = count > 0
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = weekdays[i % 7],
                        style = type.label,
                        color = if (i == days.lastIndex) colors.ink else colors.inkMuted,
                    )
                    Spacer(modifier = Modifier.height(Space.hair))
                    Text(
                        text = if (active) "$count" else "·",
                        style = type.figure,
                        color = if (active) colors.ink else colors.rule,
                    )
                }
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
