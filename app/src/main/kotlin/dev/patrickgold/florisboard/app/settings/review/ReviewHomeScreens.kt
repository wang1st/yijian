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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.ime.translation.review.MistakeKindStat
import dev.patrickgold.florisboard.ime.translation.review.MistakeProfile
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
    var snapshot by remember { mutableStateOf<ReviewSnapshot?>(null) }
    var recent by remember { mutableStateOf<List<ReviewCard>>(emptyList()) }
    var profile by remember { mutableStateOf<MistakeProfile?>(null) }

    LaunchedEffect(Unit) {
        snapshot = ReviewStore.snapshot()
        recent = ReviewStore.allCards().sortedByDescending { it.entry.timestamp }.take(3)
        profile = MistakeProfile.load()
    }

    FlorisScreenNg(
        title = "复习",
        previewFieldVisible = false,
        navigationIconVisible = false,
        mainNavigationVisible = true,
        compactAppBar = true,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            val s = snapshot
            val pending = (s?.dueCount ?: 0) + (s?.newCount ?: 0)

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = duoCardBg()),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔥", fontSize = 34.sp)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${s?.streakDays ?: 0} 天连续复习",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (s == null) "加载中…"
                                else "共 ${s.totalCards} 张卡片 · 今日已完成 ${s.reviewedToday} 张",
                                fontSize = 12.sp,
                                color = duoTextMuted(),
                            )
                        }
                        TextButton(onClick = { navController.navigate(Routes.Settings.ReviewHistory) }) {
                            Text("历史", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DuoBlue)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    val done = s?.reviewedToday ?: 0
                    val goal = (done + pending).coerceAtLeast(1)
                    LinearProgressIndicator(
                        progress = { done.toFloat() / goal },
                        color = DuoGreen,
                        trackColor = duoBorder(),
                        drawStopIndicator = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when {
                            s == null -> "…"
                            pending == 0 -> "今日已完成，明天见 🎉"
                            else -> "还剩 $pending 张：待复习 ${s.dueCount} · 新句子 ${s.newCount}"
                        },
                        fontSize = 13.sp,
                        color = duoTextMuted(),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (pending == 0) {
                        DuoSecondaryButton(
                            text = "浏览历史",
                            onClick = { navController.navigate(Routes.Settings.ReviewHistory) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        DuoButton(
                            text = "开始练习",
                            onClick = { navController.navigate(Routes.Settings.ReviewPractice) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // How each kind of mistake is receding: the one number that says whether the
            // practice is working.
            val p = profile
            if (p != null && p.kinds.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = duoCardBg()),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("错误类型的消退", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "连续答对越多，说明这个点越稳",
                            fontSize = 11.sp,
                            color = duoTextMuted(),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        p.kinds.take(4).forEachIndexed { i, k ->
                            if (i > 0) Spacer(modifier = Modifier.height(10.dp))
                            MistakeKindRow(k)
                        }
                    }
                }
            }

            if (recent.isNotEmpty()) {
                Text(
                    text = "最近练过",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = duoTextMuted(),
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
                recent.forEach { card ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = duoCardBg()),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                text = card.entry.corrected.ifBlank { card.entry.original },
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = card.entry.translation,
                                fontSize = 12.sp,
                                color = duoTextMuted(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Text(
                text = "你平时用译键翻译过的句子会自动变成复习卡片，按 1 / 3 / 7 / 15 / 30 天的间隔重现；答错降级，明天重来。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = duoTextMuted(),
                modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
            )
        }
    }
}

/** One mistake kind with how far it has receded. */
@Composable
private fun MistakeKindRow(k: MistakeKindStat) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(k.kind, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                text = if (k.correctRun == 0) "错 ${k.totalErrors} 次" else "连对 ${k.correctRun}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (k.mastered) DuoGreen else DuoBlue,
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((k.correctRun / 5f).coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(if (k.mastered) DuoGreen else DuoBlue, RoundedCornerShape(3.dp)),
            )
        }
        if (k.mastered) {
            Spacer(modifier = Modifier.height(3.dp))
            Text("已掌握 ✅", fontSize = 10.sp, color = DuoGreen)
        }
    }
}

/** Full history, grouped by day, with the corrections behind each sentence. */
@Composable
fun ReviewHistoryScreen() {
    var cards by remember { mutableStateOf<List<ReviewCard>?>(null) }
    LaunchedEffect(Unit) { cards = ReviewStore.allCards() }

    FlorisScreenNg(
        title = "练习历史",
        previewFieldVisible = false,
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
