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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.app.components.ScrollableScreenColumn
import dev.patrickgold.florisboard.app.components.SegmentedListColumn
import dev.patrickgold.florisboard.ime.translation.AudioPlayer
import dev.patrickgold.florisboard.ime.translation.SpeechClient
import dev.patrickgold.florisboard.ime.translation.review.DiffResult
import dev.patrickgold.florisboard.ime.translation.review.Grade
import dev.patrickgold.florisboard.ime.translation.review.ReviewCard
import dev.patrickgold.florisboard.ime.translation.review.ReviewSnapshot
import dev.patrickgold.florisboard.ime.translation.review.ReviewStore
import dev.patrickgold.florisboard.ime.translation.review.WordDiff
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.jetpref.datastore.ui.Preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Match/miss colors for the diff, adaptive to light/dark. */
@Composable
private fun matchColor(): Color = if (isSystemInDarkTheme()) Color(0xFF7BD88F) else Color(0xFF2E7D32)

@Composable
private fun missColor(): Color = if (isSystemInDarkTheme()) Color(0xFFF2B8B5) else Color(0xFFB3261E)

private val prefs by FlorisPreferenceStore

/** Reads [text] aloud through the existing DeepSeek TTS pipeline. */
private fun speakText(scope: CoroutineScope, text: String) {
    scope.launch(Dispatchers.IO) {
        runCatching {
            val client = SpeechClient(prefs.translation.baseUrl.get(), prefs.translation.apiKey.get())
            val wav = client.synthesize(text)
            withContext(Dispatchers.Main) { AudioPlayer.play(wav) }
        }.onFailure { flogError { "yijian review tts: ${it.message}" } }
    }
}

@Composable
fun ReviewHomeScreen() {
    val navController = LocalNavController.current
    var snapshot by remember { mutableStateOf<ReviewSnapshot?>(null) }
    LaunchedEffect(Unit) { snapshot = ReviewStore.snapshot() }

    FlorisScreenNg(
        title = "复习与练习",
        previewFieldVisible = false,
    ) { contentPadding ->
        ScrollableScreenColumn(contentPadding) {
            val s = snapshot
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "🔥 ${s?.streakDays ?: 0} 天",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (s == null) "加载中…"
                    else "连续复习 · 共 ${s.totalCards} 张卡片 · 今日已复习 ${s.reviewedToday} 张",
                    fontSize = 13.sp,
                    color = LocalContentColor.current.copy(alpha = 0.65f),
                )
            }
            SegmentedListColumn {
                val count = 2
                val pending = (s?.dueCount ?: 0) + (s?.newCount ?: 0)
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    icon = Icons.Outlined.PlayArrow,
                    title = "开始今日练习",
                    summary = when {
                        s == null -> "…"
                        pending == 0 -> "今日没有待复习的卡片 🎉"
                        else -> "待复习 ${s.dueCount} 张 + 新句子 ${s.newCount} 张"
                    },
                    onClick = { if (pending > 0) navController.navigate(Routes.Settings.ReviewPractice) },
                )
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    icon = Icons.Outlined.History,
                    title = "浏览历史",
                    summary = "所有练过的句子",
                    onClick = { navController.navigate(Routes.Settings.ReviewHistory) },
                )
            }
            Text(
                text = "你平时用译键翻译过的句子会自动变成复习卡片，按 1 / 3 / 7 / 15 / 30 天的间隔重现；答错降级，明天重来。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = LocalContentColor.current.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
    }
}

private enum class Phase { LOADING, EMPTY, QUESTION, GRADED, DONE }

@Composable
fun ReviewPracticeScreen() {
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<List<ReviewCard>>(emptyList()) }
    var phase by remember { mutableStateOf(Phase.LOADING) }
    var index by remember { mutableIntStateOf(0) }
    var answer by remember { mutableStateOf("") }
    var diff by remember { mutableStateOf<DiffResult?>(null) }
    var goodCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        queue = ReviewStore.dueQueue()
        phase = if (queue.isEmpty()) Phase.EMPTY else Phase.QUESTION
    }

    FlorisScreenNg(
        title = "今日练习",
        previewFieldVisible = false,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp),
        ) {
            when (phase) {
                Phase.LOADING -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                Phase.EMPTY -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("今日没有待复习的卡片 🎉", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "平时用译键翻译的句子会自动变成卡片。",
                            fontSize = 13.sp,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                        )
                    }
                }
                Phase.DONE -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("练习完成！", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "共 ${queue.size} 张 · 记得 $goodCount 张",
                            fontSize = 14.sp,
                            color = LocalContentColor.current.copy(alpha = 0.7f),
                        )
                    }
                }
                else -> {
                    val card = queue[index]
                    Text(
                        text = "第 ${index + 1} / ${queue.size} 张",
                        fontSize = 12.sp,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    LinearProgressIndicator(
                        progress = { index / queue.size.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "把这句话翻回英文：",
                                fontSize = 12.sp,
                                color = LocalContentColor.current.copy(alpha = 0.6f),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = card.entry.translation, fontSize = 20.sp, lineHeight = 28.sp)
                            if (card.timesPracticed > 1) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "练过 ${card.timesPracticed} 次",
                                    fontSize = 11.sp,
                                    color = LocalContentColor.current.copy(alpha = 0.45f),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it },
                        enabled = phase == Phase.QUESTION,
                        placeholder = { Text("打出对应的英文…") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (phase == Phase.QUESTION) {
                        Button(
                            onClick = {
                                diff = WordDiff.grade(card.entry.corrected, answer)
                                phase = Phase.GRADED
                            },
                            enabled = answer.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("检查")
                        }
                    } else {
                        val d = diff
                        if (d != null) {
                            GradedView(diff = d, card = card, scope = scope)
                            Spacer(modifier = Modifier.height(12.dp))
                            GradeRow(suggested = d.suggestedGrade) { g ->
                                scope.launch {
                                    ReviewStore.grade(card.id, g)
                                    if (g == Grade.GOOD) goodCount++
                                    if (index + 1 >= queue.size) {
                                        phase = Phase.DONE
                                    } else {
                                        index++
                                        answer = ""
                                        diff = null
                                        phase = Phase.QUESTION
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The diff result plus the correct sentence and what was wrong with it originally. */
@Composable
private fun GradedView(diff: DiffResult, card: ReviewCard, scope: CoroutineScope) {
    val good = matchColor()
    val bad = missColor()
    Column(modifier = Modifier.fillMaxWidth()) {
        // Target sentence with per-word hit/miss coloring
        val annotated: AnnotatedString = buildAnnotatedString {
            diff.targetWords.forEachIndexed { i, w ->
                if (i > 0) append(" ")
                withStyle(SpanStyle(color = if (w.matched) good else bad, fontWeight = FontWeight.Medium)) {
                    append(w.text)
                }
            }
        }
        Text(text = annotated, fontSize = 18.sp, lineHeight = 26.sp)
        if (diff.extraWords.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "多写了：${diff.extraWords.joinToString(" ")}",
                fontSize = 13.sp,
                color = bad.copy(alpha = 0.85f),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "相似度 ${diff.similarity}%",
                fontSize = 13.sp,
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            IconButton(onClick = { speakText(scope, card.entry.corrected) }) {
                Icon(Icons.Outlined.VolumeUp, contentDescription = "朗读")
            }
        }
        // What the user originally got wrong on this sentence, as a refresher
        if (card.entry.corrections.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            card.entry.corrections.forEach { c ->
                Text(
                    text = "${c.wrong} → ${c.right}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (c.why.isNotBlank()) {
                    Text(
                        text = c.why,
                        fontSize = 12.sp,
                        color = LocalContentColor.current.copy(alpha = 0.65f),
                    )
                }
            }
        } else if (card.entry.note.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = card.entry.note,
                fontSize = 12.sp,
                color = LocalContentColor.current.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
private fun GradeRow(suggested: Grade, onGrade: (Grade) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        @Composable
        fun gradeButton(label: String, grade: Grade, modifier: Modifier) {
            if (suggested == grade) {
                Button(onClick = { onGrade(grade) }, modifier = modifier) { Text(label) }
            } else {
                OutlinedButton(onClick = { onGrade(grade) }, modifier = modifier) { Text(label) }
            }
        }
        gradeButton("忘了", Grade.FORGOT, Modifier.weight(1f))
        gradeButton("模糊", Grade.HARD, Modifier.weight(1f))
        gradeButton("记得", Grade.GOOD, Modifier.weight(1f))
    }
}

// ---- history browser ---------------------------------------------------------------

@Composable
fun ReviewHistoryScreen() {
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<ReviewCard>?>(null) }
    LaunchedEffect(Unit) { cards = ReviewStore.allCards() }

    FlorisScreenNg(
        title = "练习历史",
        previewFieldVisible = false,
    ) { contentPadding ->
        val list = cards
        when {
            list == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            list.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "还没有练习记录。用译键翻译几句英文，这里就会有内容了。",
                        fontSize = 14.sp,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                        modifier = Modifier.padding(32.dp),
                    )
                }
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    grouped.forEach { (day, items) ->
                        item(key = "header-$day") {
                            Text(
                                text = day,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LocalContentColor.current.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                        items(items, key = { it.id }) { card ->
                            HistoryCard(card = card, scope = scope)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(card: ReviewCard, scope: CoroutineScope) {
    var expanded by remember(card.id) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
            Text(
                text = card.entry.translation,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.entry.corrected,
                    fontSize = 14.sp,
                    color = LocalContentColor.current.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f, fill = true),
                )
                IconButton(onClick = { speakText(scope, card.entry.corrected) }) {
                    Icon(
                        Icons.Outlined.VolumeUp,
                        contentDescription = "朗读",
                        tint = LocalContentColor.current.copy(alpha = 0.6f),
                    )
                }
            }
            if (card.entry.corrections.isNotEmpty()) {
                Text(
                    text = if (expanded) "收起 ▴" else "✎ ${card.entry.corrections.size} 处纠正 ▾",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                )
                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (card.entry.original.isNotBlank() && card.entry.original != card.entry.corrected) {
                            Text(
                                text = "原文　${card.entry.original}",
                                fontSize = 13.sp,
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                            )
                        }
                        card.entry.corrections.forEach { c ->
                            Text(
                                text = "${c.wrong} → ${c.right}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            if (c.why.isNotBlank()) {
                                Text(
                                    text = c.why,
                                    fontSize = 12.sp,
                                    color = LocalContentColor.current.copy(alpha = 0.65f),
                                )
                            }
                        }
                        if (card.entry.note.isNotBlank()) {
                            Text(
                                text = card.entry.note,
                                fontSize = 12.sp,
                                color = LocalContentColor.current.copy(alpha = 0.65f),
                            )
                        }
                    }
                }
            }
        }
    }
}
