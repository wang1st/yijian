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
import androidx.compose.foundation.background
import dev.patrickgold.florisboard.app.design.Space
import androidx.compose.foundation.background
import dev.patrickgold.florisboard.app.design.YijianTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.ime.translation.review.ReviewStore
import dev.patrickgold.florisboard.ime.translation.EnglishTeacher

private val StatsGreen = Color(0xFF58CC02)
private val StatsBlue = Color(0xFF1CB0F6)
private val StatsRed = Color(0xFFFF4B4B)

private data class ReportStats(
    val sentences: Int,
    val words: Int,
    val cleanRate: Int,
    val byTag: List<Pair<String, Int>>,
    val recentMistakes: List<Pair<String, String>>,
    val streakDays: Int,
    val cardsTotal: Int,
)

/**
 * Learning report tab: a compact summary built from the same practice log the email report
 * uses, plus the review numbers. No network calls — everything is computed locally.
 */
@Composable
fun MainStatsScreen() {
    var stats by remember { mutableStateOf<ReportStats?>(null) }

    LaunchedEffect(Unit) {
        val entries = EnglishTeacher.readEntriesStatic()
        val snap = ReviewStore.snapshot()
        val corrections = entries.flatMap { it.corrections }
        val byTag = corrections.groupBy { it.tag.ifBlank { "其他" } }
            .toList()
            .sortedByDescending { it.second.size }
            .map { (tag, list) -> tag to list.size }
        val recent = entries.asReversed().flatMap { e ->
            e.corrections.take(3).map { "${it.wrong} → ${it.right}" to it.why }
        }.take(8)
        stats = ReportStats(
            sentences = entries.size,
            words = entries.sumOf { it.wordCount },
            cleanRate = if (entries.isEmpty()) 100 else {
                ((entries.size - entries.count { it.corrections.isNotEmpty() }) * 100.0 / entries.size).toInt()
            },
            byTag = byTag,
            recentMistakes = recent,
            streakDays = snap.streakDays,
            cardsTotal = snap.totalCards,
        )
    }

    val colors = YijianTheme.colors
    val type = YijianTheme.type

    FlorisScreenNg(
        title = "学习报告",
        previewFieldVisible = false,
        navigationIconVisible = false,
        mainNavigationVisible = true,
        compactAppBar = true,
        background = colors.paper,
    ) { contentPadding ->
        val s = stats
        if (s == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = colors.inkMuted) }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.gutter),
            ) {
                Spacer(modifier = Modifier.height(Space.xxl))

                // The headline number, set as a figure.
                Text(text = "${s.cleanRate}%", style = type.display, color = colors.ink)
                Text(
                    text = "完全没出错的句子占比",
                    style = type.small,
                    color = colors.inkMuted,
                )

                Spacer(modifier = Modifier.height(Space.xl))
                RuleLine()
                Spacer(modifier = Modifier.height(Space.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                    Figure("句子", "${s.sentences}")
                    Figure("词数", "${s.words}")
                    Figure("连续", "${s.streakDays}")
                }

                if (s.byTag.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(Space.xl))
                    RuleLine()
                    Spacer(modifier = Modifier.height(Space.lg))
                    Text(text = "错在哪里", style = type.label, color = colors.inkMuted)
                    Spacer(modifier = Modifier.height(Space.md))
                    val max = s.byTag.first().second.coerceAtLeast(1)
                    s.byTag.forEach { (tag, count) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = tag,
                                style = type.body,
                                color = colors.ink,
                                modifier = Modifier.width(56.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(2.dp)
                                    .background(colors.rule),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(count.toFloat() / max)
                                        .height(2.dp)
                                        .background(colors.ink),
                                )
                            }
                            Spacer(modifier = Modifier.width(Space.md))
                            Text(text = "$count", style = type.figure, color = colors.inkMuted)
                        }
                        Spacer(modifier = Modifier.height(Space.sm))
                    }
                }

                if (s.recentMistakes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(Space.xl))
                    RuleLine()
                    Spacer(modifier = Modifier.height(Space.lg))
                    Text(text = "最近的纠正", style = type.label, color = colors.inkMuted)
                    Spacer(modifier = Modifier.height(Space.md))
                    s.recentMistakes.forEach { (fix, why) ->
                        Text(text = fix, style = type.body, color = colors.ink)
                        if (why.isNotBlank()) {
                            Text(text = why, style = type.small, color = colors.inkMuted)
                        }
                        Spacer(modifier = Modifier.height(Space.sm))
                    }
                }

                Spacer(modifier = Modifier.height(Space.xl))
                RuleLine()
                Spacer(modifier = Modifier.height(Space.lg))
                Text(
                    text = "共 ${s.cardsTotal} 张复习卡片，来自你翻译过的句子。",
                    style = type.small,
                    color = colors.inkMuted,
                )
                Spacer(modifier = Modifier.height(Space.xxl))
            }
        }
    }
}

/** A hairline rule. */
@Composable
private fun RuleLine() {
    val colors = YijianTheme.colors
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
}

/** Label above a figure. */
@Composable
private fun Figure(label: String, value: String) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    Column {
        Text(text = label, style = type.label, color = colors.inkMuted)
        Spacer(modifier = Modifier.height(Space.hair))
        Text(text = value, style = type.figure, color = colors.ink)
    }
}
