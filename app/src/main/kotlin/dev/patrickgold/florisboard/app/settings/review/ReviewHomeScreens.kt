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

package dev.patrickgold.florisboard.app.settings.review

import androidx.compose.foundation.layout.heightIn
import dev.patrickgold.florisboard.ime.translation.mistake.MistakeStore
import dev.patrickgold.florisboard.app.design.Shape
import androidx.compose.foundation.background
import dev.patrickgold.florisboard.app.design.YijianTheme
import dev.patrickgold.florisboard.app.design.Space
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.ime.translation.mistake.MistakePoint
import dev.patrickgold.florisboard.ime.translation.review.ReviewCard
import dev.patrickgold.florisboard.ime.translation.review.ReviewSnapshot
import dev.patrickgold.florisboard.ime.translation.review.ReviewStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Review tab: where the learner stands today, one primary action, and what they have been
 * practising. Deliberately short — the practice screen is where the work happens.
 */
@Composable
fun ReviewHomeScreen() {
    val navController = LocalNavController.current
    val colors = YijianTheme.colors
    val type = YijianTheme.type

    var snapshot by remember { mutableStateOf<ReviewSnapshot?>(null) }
    var recent by remember { mutableStateOf<List<ReviewCard>>(emptyList()) }
    var weakPoints by remember { mutableStateOf<List<MistakePoint>>(emptyList()) }

    LaunchedEffect(Unit) {
        snapshot = ReviewStore.snapshot()
        recent = ReviewStore.allCards().sortedByDescending { it.entry.timestamp }.take(3)
        // Explicit type parameter: runCatching's R cannot be inferred through the state
        // property on the left-hand side.
        weakPoints = runCatching<List<MistakePoint>> {
            MistakeStore.allPoints().filter { !it.mastered }
                .sortedWith(compareByDescending<MistakePoint> { it.stubborn }.thenByDescending { it.occurrences })
        }.getOrDefault(emptyList())
    }

    FlorisScreenNg(
        title = "复习",
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
            val s = snapshot
            val pending = (s?.dueCount ?: 0) + (s?.newCount ?: 0)
            val done = s?.reviewedToday ?: 0

            Spacer(modifier = Modifier.height(Space.xxl))

            // The headline is the number, set as a figure rather than inside a ring.
            Text(
                text = if (s == null) "…" else "$done",
                style = type.display,
                color = colors.ink,
            )
            Text(
                text = "今天练过 · 共 ${s?.totalCards ?: 0} 张卡片",
                style = type.small,
                color = colors.inkMuted,
            )

            Spacer(modifier = Modifier.height(Space.lg))
            if (pending > 0) {
                PagePrimaryAction("开始练习") {
                    navController.navigate(Routes.Settings.ReviewPractice)
                }
            } else {
                Text(
                    text = "今天已经练完了。",
                    style = type.body,
                    color = colors.inkMuted,
                )
            }

            // Two more ways in, set as text: practising your own recurring mistakes, and
            // seeing whether they are actually going away.
            Spacer(modifier = Modifier.height(Space.md))
            // Three whole rows rather than three bare texts: each is a navigation target and
            // needs a 48 dp touch area, not just the width of its glyphs.
            listOf(
                "错题练习" to Routes.Settings.ReviewMistakes,
                "错误点" to Routes.Settings.MistakeProgress,
                "我的表达" to Routes.Settings.Expressions,
            ).forEach { (label, route) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { navController.navigate(route as Any) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = label, style = type.body, color = colors.ink)
                }
            }

            if (s != null) {
                Spacer(modifier = Modifier.height(Space.xxl))
                Rule()
                Spacer(modifier = Modifier.height(Space.lg))

                Text(text = "状态", style = type.label, color = colors.inkMuted)
                Spacer(modifier = Modifier.height(Space.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                    Figure("连续", "${s.streakDays} 天")
                    Figure("待练", "$pending 张")
                    Figure("正确", "${s.accuracyToday}%")
                }

                // The actual points being failed, not four coarse categories: "very 不能修饰动词"
                // is actionable, "语法" is not.
                val points = weakPoints
                if (points.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(Space.xl))
                    Rule()
                    Spacer(modifier = Modifier.height(Space.lg))
                    Text(
                        text = "还要练的",
                        style = type.label,
                        color = colors.inkMuted,
                        modifier = Modifier.clickable {
                            navController.navigate(Routes.Settings.MistakeProgress)
                        },
                    )
                    Spacer(modifier = Modifier.height(Space.md))
                    points.take(5).forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = p.label,
                                style = type.body,
                                color = colors.ink,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = when {
                                    p.stubborn -> "还在反复"
                                    p.correctStreak > 0 -> "连对 ${p.correctStreak}"
                                    else -> "错 ${p.occurrences} 次"
                                },
                                style = type.small,
                                color = if (p.stubborn) colors.negative else colors.inkMuted,
                            )
                        }
                        Spacer(modifier = Modifier.height(Space.sm))
                    }
                }
            }

            if (recent.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Space.xl))
                Rule()
                Spacer(modifier = Modifier.height(Space.lg))
                Text(text = "最近练过", style = type.label, color = colors.inkMuted)
                Spacer(modifier = Modifier.height(Space.md))
                recent.forEach { card ->
                    Text(
                        text = card.entry.corrected.ifBlank { card.entry.original },
                        style = type.body,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(Space.hair))
                    Text(
                        text = card.entry.translation,
                        style = type.small,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(Space.md))
                }
            }

            Spacer(modifier = Modifier.height(Space.xl))
            Rule()
            Spacer(modifier = Modifier.height(Space.lg))
            Text(
                text = "翻译过的句子会自动变成卡片，按 1 / 3 / 7 / 15 / 30 天重现。",
                style = type.small,
                color = colors.inkMuted,
            )
            Spacer(modifier = Modifier.height(Space.xxl))
        }
    }
}


/** A hairline rule, the only divider the language uses. */
@Composable
internal fun Rule() {
    val colors = YijianTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.rule),
    )
}

/** Label above a figure. */
@Composable
internal fun Figure(label: String, value: String) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    Column {
        Text(text = label, style = type.label, color = colors.inkMuted)
        Spacer(modifier = Modifier.height(Space.hair))
        Text(text = value, style = type.figure, color = colors.ink)
    }
}

/** The page's single filled action. */
@Composable
internal fun PagePrimaryAction(label: String, onClick: () -> Unit) {
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
        Text(text = label, style = type.body, fontWeight = FontWeight.Medium, color = colors.onAccent)
    }
}

/** Full history, grouped by day, with the corrections behind each sentence. */
@Composable
fun ReviewHistoryScreen() {
    var cards by remember { mutableStateOf<List<ReviewCard>?>(null) }
    LaunchedEffect(Unit) { cards = ReviewStore.allCards() }

    val colors = YijianTheme.colors
    FlorisScreenNg(
        title = "练习历史",
        previewFieldVisible = false,
        background = colors.paper,
    ) { contentPadding ->
        val list = cards
        when {
            list == null -> Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = DuoGreen) }

            list.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "还没有练习记录。用译键翻译几句英文，这里就会有内容了。",
                    fontSize = 14.sp,
                    color = duoTextMuted(),
                    modifier = Modifier.padding(32.dp),
                )
            }

            else -> {
                val dayFormat = remember { SimpleDateFormat("M月d日 EEEE", Locale.CHINA) }
                val grouped = remember(list) { list.groupBy { dayFormat.format(Date(it.entry.timestamp)) } }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = contentPadding.calculateTopPadding() + 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                        start = 16.dp,
                        end = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    grouped.forEach { (day, items) ->
                        item(key = "header-$day") {
                            Text(
                                text = day,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = duoTextMuted(),
                                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                            )
                        }
                        items(items, key = { it.id }) { card -> HistoryCard(card) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(card: ReviewCard) {
    var expanded by remember(card.id) { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = duoCardBg()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = card.entry.translation,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = card.entry.corrected, fontSize = 14.sp, color = duoTextMuted())
            if (card.entry.corrections.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (expanded) "收起 ▴" else "✎ ${card.entry.corrections.size} 处纠正 ▾",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = DuoBlue,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 2.dp),
                )
                if (expanded) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (card.entry.original.isNotBlank() && card.entry.original != card.entry.corrected) {
                            Text("原文　${card.entry.original}", fontSize = 13.sp, color = duoTextMuted())
                        }
                        card.entry.corrections.forEach { c ->
                            Text("${c.wrong} → ${c.right}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (c.why.isNotBlank()) {
                                Text(c.why, fontSize = 12.sp, color = duoTextMuted())
                            }
                        }
                    }
                }
            }
        }
    }
}
