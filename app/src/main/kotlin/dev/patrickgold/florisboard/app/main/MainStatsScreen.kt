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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    FlorisScreenNg(
        title = "学习报告",
        previewFieldVisible = false,
        navigationIconVisible = false,
        mainNavigationVisible = true,
    ) { contentPadding ->
        val s = stats
        if (s == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = StatsGreen) }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                // Big numbers
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatTile("${s.sentences}", "练过的句子", StatsBlue, Modifier.weight(1f))
                    StatTile("${s.words}", "英文词数", StatsGreen, Modifier.weight(1f))
                    StatTile("${s.streakDays}", "连续复习", StatsRed, Modifier.weight(1f))
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg()),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("英文正确率", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${s.cleanRate}%",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (s.cleanRate >= 80) StatsGreen else StatsBlue,
                        )
                        Text(
                            text = "完全没出错的句子占比",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }

                if (s.byTag.isNotEmpty()) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg()),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text("错在哪里", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(12.dp))
                            val max = s.byTag.first().second.coerceAtLeast(1)
                            s.byTag.forEach { (tag, count) ->
                                Row(
                                    modifier = Modifier.padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(tag, fontSize = 13.sp, modifier = Modifier.width(52.dp))
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 10.dp)
                                            .height(10.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(count.toFloat() / max)
                                                .height(10.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(if (tag == "拼写") StatsRed else StatsGreen),
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("$count", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                if (s.recentMistakes.isNotEmpty()) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg()),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text("最近的纠正", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            s.recentMistakes.forEach { (fix, why) ->
                                Column(modifier = Modifier.padding(vertical = 5.dp)) {
                                    Text(fix, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    if (why.isNotBlank()) {
                                        Text(
                                            text = why,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg()),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("复习卡片", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "共 ${s.cardsTotal} 张，来自你翻译过的句子",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg()),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun cardBg(): Color = if (isSystemInDarkTheme()) Color(0xFF1F2023) else Color.White
