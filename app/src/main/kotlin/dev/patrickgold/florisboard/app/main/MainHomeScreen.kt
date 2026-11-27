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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.ime.translation.EnglishTeacher
import dev.patrickgold.florisboard.ime.translation.review.ReviewAi
import dev.patrickgold.florisboard.ime.translation.review.ReviewSnapshot
import dev.patrickgold.florisboard.ime.translation.review.ReviewStore
import dev.patrickgold.florisboard.ime.translation.review.WeakSpot
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import org.florisboard.lib.compose.FlorisErrorCard
import org.florisboard.lib.compose.FlorisWarningCard
import org.florisboard.lib.compose.stringRes

private val HomeGreen = Color(0xFF58CC02)
private val HomeBlue = Color(0xFF1CB0F6)
private val HomeGold = Color(0xFFFFC800)

/** Daily goals keep the session short enough to actually finish. */
private const val DAILY_TARGET = 20

@Composable
private fun cardBg(): Color = if (isSystemInDarkTheme()) Color(0xFF1F2023) else Color.White

private val SheetShape = RoundedCornerShape(16.dp)

/**
 * Home tab, built around one question: what do I do right now?
 *
 * - one primary action (continue today's session)
 * - a ring showing how far through today's target the user is
 * - the daily quests, each with its own progress
 * - this week's activity, so a zero day is visible at a glance
 * - the weaknesses the model found in the user's own mistakes
 */
@Composable
fun MainHomeScreen() {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val isImeEnabled by InputMethodUtils.observeIsFlorisboardEnabled()
    val isImeSelected by InputMethodUtils.observeIsFlorisboardSelected()

    var snap by remember { mutableStateOf<ReviewSnapshot?>(null) }
    var weakSpots by remember { mutableStateOf<List<WeakSpot>>(emptyList()) }
    var analysing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        snap = ReviewStore.snapshot()
        // Weak-spot analysis is cached for the day; it costs one model call.
        weakSpots = WeakSpotCache.load()
        if (weakSpots.isEmpty() && !WeakSpotCache.checkedToday()) {
            analysing = true
            val entries = EnglishTeacher.readEntriesStatic()
            val samples = entries.flatMap { e ->
                e.corrections.map { "${it.wrong} → ${it.right}" }
            }.takeLast(40)
            if (samples.isNotEmpty()) {
                weakSpots = ReviewAi.analyzeWeaknesses(samples)
                WeakSpotCache.save(weakSpots)
            }
            analysing = false
            snap = ReviewStore.snapshot()
        }
    }

    FlorisScreenNg(
        title = "译键",
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
            if (!isImeEnabled) {
                FlorisErrorCard(
                    showIcon = false,
                    text = stringRes(R.string.settings__home__ime_not_enabled),
                    onClick = { InputMethodUtils.showImeEnablerActivity(context) },
                )
            } else if (!isImeSelected) {
                FlorisWarningCard(
                    showIcon = false,
                    text = stringRes(R.string.settings__home__ime_not_selected),
                    onClick = { InputMethodUtils.showImePicker(context) },
                )
            }

            val s = snap
            if (s == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = HomeGreen) }
                return@Column
            }

            val pending = s.dueCount + s.newCount
            val done = s.reviewedToday

            // Top status: streak and XP, the two numbers worth growing.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusPill("🔥", "${s.streakDays} 天", "连续")
                StatusPill("⚡", "${s.xpToday} XP", "今日")
                StatusPill("🎯", "${s.accuracyToday}%", "今天正确率")
            }

            // The ring: how far through today's target am I?
            Card(
                shape = SheetShape,
                colors = CardDefaults.cardColors(containerColor = cardBg()),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GoalRing(progress = (done.toFloat() / DAILY_TARGET).coerceIn(0f, 1f), done = done)
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = when {
                            done >= DAILY_TARGET -> "今日目标已完成 🎉"
                            pending == 0 -> "卡片都练完了，明天继续"
                            else -> "再练 ${pending.coerceAtMost(DAILY_TARGET - done)} 张完成今日目标"
                        },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    BigActionButton(
                        text = when {
                            pending > 0 -> "开始练习"
                            else -> "练新句子"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            navController.navigate(
                                if (pending > 0) Routes.Settings.ReviewPractice else Routes.Settings.ReviewGenerate
                            )
                        },
                    )
                }
            }

            // Daily quests
            Card(
                shape = SheetShape,
                colors = CardDefaults.cardColors(containerColor = cardBg()),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("今日任务", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    QuestRow("复习 ${DAILY_TARGET} 张卡片", done, DAILY_TARGET)
                    Spacer(modifier = Modifier.height(10.dp))
                    QuestRow("记住 10 张", s.rememberedToday, 10)
                    Spacer(modifier = Modifier.height(10.dp))
                    QuestRow("连对 5 题", s.correctRun, 5)
                }
            }

            // This week, as bars. A missing day is the honest signal that matters.
            Card(
                shape = SheetShape,
                colors = CardDefaults.cardColors(containerColor = cardBg()),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("本周", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "共 ${s.lastSevenDays.sum()} 张",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    WeekBars(s.lastSevenDays)
                }
            }

            // Weaknesses from the user's own corrections, analysed by the model.
            if (analysing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = HomeBlue,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "正在分析你的薄弱点…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            } else if (weakSpots.isNotEmpty()) {
                Card(
                    shape = SheetShape,
                    colors = CardDefaults.cardColors(containerColor = cardBg()),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("薄弱点", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "专项练习 ›",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = HomeBlue,
                                modifier = Modifier.clickable {
                                    navController.navigate(Routes.Settings.ReviewGenerate)
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        val max = weakSpots.maxOf { it.count }.coerceAtLeast(1)
                        weakSpots.forEachIndexed { i, spot ->
                            if (i > 0) Spacer(modifier = Modifier.height(10.dp))
                            WeakSpotRow(spot, max)
                        }
                    }
                }
            }

            Text(
                text = "复习卡片由你翻译过的句子自动生成，按 1 / 3 / 7 / 15 / 30 天的间隔重现。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.padding(vertical = 20.dp),
            )
        }
    }
}

@Composable
private fun StatusPill(emoji: String, value: String, label: String) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg()),
        modifier = Modifier.weight(1f),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emoji, fontSize = 16.sp)
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                text = label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

/** Circular progress for today's target, with the count in the middle. */
@Composable
private fun GoalRing(progress: Float, done: Int) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(160.dp)) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = HomeGreen,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$done",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = HomeGreen,
            )
            Text(
                text = "/ $DAILY_TARGET 张",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

/** Rounded pill button with the pressed bottom edge. */
@Composable
private fun BigActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(52.dp)
            .padding(bottom = 3.dp)
            .background(HomeGreen.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(49.dp)
                .background(HomeGreen, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun QuestRow(label: String, current: Int, target: Int) {
    val reached = current >= target
    val fraction = (current.toFloat() / target).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (reached) HomeGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (reached) FontWeight.Medium else FontWeight.Normal,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        RoundedCornerShape(3.dp),
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .background(
                            if (reached) HomeGreen else HomeGold,
                            RoundedCornerShape(3.dp),
                        ),
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "$current/$target",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/** Seven bars, one per day, labelled by weekday initial. */
@Composable
private fun WeekBars(values: List<Int>) {
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val todayIndex = values.lastIndex
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { i, v ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((8 + 40f * v / max).dp)
                        .background(
                            color = when {
                                v == 0 -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                                i == todayIndex -> HomeGreen
                                else -> HomeGreen.copy(alpha = 0.55f)
                            },
                            shape = RoundedCornerShape(4.dp),
                        ),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = labels[i % 7],
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (i == todayIndex) 0.85f else 0.45f
                    ),
                )
            }
        }
    }
}

@Composable
private fun WeakSpotRow(spot: WeakSpot, max: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = spot.topic,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${spot.count} 次",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = HomeBlue,
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
                    .fillMaxWidth(spot.count.toFloat() / max)
                    .height(6.dp)
                    .background(HomeBlue, RoundedCornerShape(3.dp)),
            )
        }
        if (spot.advice.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = spot.advice,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

/** One analysis per day is enough; the result is cached in memory for the session. */
private object WeakSpotCache {
    private var cached: List<WeakSpot> = emptyList()
    private var cachedDay: String = ""

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    fun load(): List<WeakSpot> = if (cachedDay == today()) cached else emptyList()

    fun checkedToday(): Boolean = cachedDay == today()

    fun save(spots: List<WeakSpot>) {
        cached = spots
        cachedDay = today()
    }
}
