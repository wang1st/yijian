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

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.app.design.Space
import dev.patrickgold.florisboard.app.design.YijianTheme
import dev.patrickgold.florisboard.ime.translation.EnglishTeacher
import dev.patrickgold.florisboard.ime.translation.mistake.MistakePoint
import dev.patrickgold.florisboard.ime.translation.mistake.MistakeStore

/**
 * 错误点 — the screen that answers "am I actually getting better?".
 *
 * Every course app can show that you practised. What it cannot show is which of *your* errors
 * has stopped happening, because it never had your errors — only its own exercises. That is
 * the whole reason this screen exists, so it leads with the trend rather than the count:
 * a point that failed twelve times but passed the last eight is nearly done, and a point that
 * failed three times and just failed again is not.
 */
@Composable
fun MistakeProgressScreen() {
    val colors = YijianTheme.colors
    val type = YijianTheme.type

    var points by remember { mutableStateOf<List<MistakePoint>?>(null) }
    LaunchedEffect(Unit) {
        // One-time cleanup of entries recorded before the "only corrections become practice"
        // rule, then re-import so points reflect the pruned log.
        runCatching { EnglishTeacher.pruneCleanEntriesStatic() }
        runCatching { MistakeStore.importFromPracticeLog() }
        points = MistakeStore.allPoints()
    }

    FlorisScreenNg(
        title = "错误点",
        previewFieldVisible = false,
        background = colors.paper,
    ) { contentPadding ->
        val list = points
        if (list == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = colors.inkMuted) }
            return@FlorisScreenNg
        }
        if (list.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有积累错误点。用译键翻译几句英文，被纠正过的用法会出现在这里。",
                    style = type.small,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(Space.xxl),
                )
            }
            return@FlorisScreenNg
        }

        // Unmastered first — what still needs work belongs at the top — then by failure count.
        val sorted = list.sortedWith(
            compareBy<MistakePoint> { it.mastered }
                .thenByDescending { it.occurrences }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter),
        ) {
            Spacer(modifier = Modifier.height(Space.xxl))

            val mastered = list.count { it.mastered }
            Text("$mastered / ${list.size}", style = type.display, color = colors.ink)
            Text("已经掌握的用法", style = type.small, color = colors.inkMuted)

            Spacer(modifier = Modifier.height(Space.xl))
            Rule()
            Spacer(modifier = Modifier.height(Space.lg))

            sorted.forEach { p ->
                PointRow(p)
                Spacer(modifier = Modifier.height(Space.lg))
            }
            Spacer(modifier = Modifier.height(Space.xxl))
        }
    }
}

/** One point: label, how it is trending, and a ten-mark history strip. */
@Composable
private fun PointRow(p: MistakePoint) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = p.label,
                style = type.body,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    p.mastered -> "已掌握"
                    p.kind == "说法" -> "可选说法"
                    p.stubborn -> "还在反复"
                    p.correctStreak > 0 -> "连对 ${p.correctStreak}"
                    else -> "错过 ${p.occurrences} 次"
                },
                style = type.small,
                color = when {
                    p.mastered -> colors.positive
                    p.stubborn -> colors.negative
                    else -> colors.inkMuted
                },
            )
        }

        // The history strip: one mark per recent review, oldest on the left. This is the
        // improvement curve in its most honest form — no averaging, no score.
        if (p.history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                p.history.forEach { ok ->
                    Box(
                        modifier = Modifier
                            .width(14.dp)
                            .height(3.dp)
                            .background(if (ok) colors.ink else colors.rule),
                    )
                }
            }
            Spacer(modifier = Modifier.height(Space.xs))
            Text(
                text = buildString {
                    append("最近 ${p.history.size} 次对 ${p.history.count { it }} 次")
                    if (p.wrongForms.isNotEmpty()) {
                        append(" · ")
                        append(p.wrongForms.first())
                        if (p.correctForm.isNotBlank()) append(" → ${p.correctForm}")
                    }
                },
                style = type.small,
                color = colors.inkMuted,
            )
        } else {
            Spacer(modifier = Modifier.height(Space.xs))
            Text(
                text = "还没复习过" + if (p.wrongForms.isNotEmpty()) " · ${p.wrongForms.first()}" else "",
                style = type.small,
                color = colors.inkMuted,
            )
        }
    }
}
