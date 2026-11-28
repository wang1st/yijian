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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.app.design.Shape
import dev.patrickgold.florisboard.app.design.Space
import dev.patrickgold.florisboard.app.design.YijianTheme
import dev.patrickgold.florisboard.ime.translation.mistake.MistakeSession
import dev.patrickgold.florisboard.ime.translation.mistake.MistakeStore
import dev.patrickgold.florisboard.ime.translation.mistake.PointWithExamples
import dev.patrickgold.florisboard.ime.translation.mistake.RetryQueue
import kotlinx.coroutines.launch

/**
 * 错题练习 — one session over the learner's recurring mistakes.
 *
 * Deliberately not a quiz about sentences. Each item opens with the sentence the learner
 * actually wrote, because the point of reviewing your own words is recognising the moment you
 * said it wrong; a generic drill would lose the only thing this app has and a course does not.
 */
private enum class MistakePhase { LOADING, EMPTY, ASK, REVEAL, DONE }

@Composable
fun MistakePracticeScreen() {
    val navController = LocalNavController.current
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(MistakePhase.LOADING) }
    var slots by remember { mutableStateOf<List<MistakeSession.Slot>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var failures by remember { mutableStateOf<List<PointWithExamples>>(emptyList()) }
    val retries = remember { RetryQueue() }
    // Bumped whenever the retry queue changes, so the current item recomputes.
    var queueVersion by remember { mutableIntStateOf(0) }
    var retryItem by remember { mutableStateOf<PointWithExamples?>(null) }

    LaunchedEffect(Unit) {
        runCatching { dev.patrickgold.florisboard.ime.translation.EnglishTeacher.pruneCleanEntriesStatic() }
        runCatching { MistakeStore.importFromPracticeLog() }
        val due = MistakeStore.duePoints()
        slots = MistakeSession.build(due)
        phase = if (slots.isEmpty()) MistakePhase.EMPTY else MistakePhase.ASK
    }

    // The retry tail runs after the main slots: anything answered wrong in this session is
    // replayed until it is answered right (or hits the attempt cap).
    LaunchedEffect(queueVersion) {
        if (retryItem == null && index >= slots.size && !retries.isEmpty()) {
            retryItem = retries.next()
        }
    }

    /**
     * Records an outcome for [item] and moves on.
     *
     * The item is passed in rather than read from state: reading a delegated state property
     * inside a local function made the compiler lose the element type, and taking the argument
     * also makes it obvious which item is being answered.
     */
    fun advance(item: PointWithExamples, correct: Boolean) {
        val currentRetry: PointWithExamples? = retryItem
        val inRetryTail = currentRetry != null && currentRetry.point.id == item.point.id
        if (!correct) {
            val already: List<PointWithExamples> = failures
            if (already.none { f -> f.point.id == item.point.id }) {
                failures = failures + item
            }
            retries.enqueue(item)
        }
        scope.launch {
            MistakeStore.recordReview(item.point.id, correct)
            revealed = false
            if (inRetryTail) {
                val next = retries.next()
                retryItem = next
                if (next == null) phase = MistakePhase.DONE
                queueVersion++
            } else if (index + 1 >= slots.size) {
                val next = retries.next()
                retryItem = next
                phase = if (next == null) MistakePhase.DONE else MistakePhase.ASK
                queueVersion++
            } else {
                index += 1
                phase = MistakePhase.ASK
            }
        }
    }

    FlorisScreenNg(
        title = "错题练习",
        previewFieldVisible = false,
        background = colors.paper,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = Space.gutter),
        ) {
            when (phase) {
                MistakePhase.LOADING -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = colors.inkMuted) }

                MistakePhase.EMPTY -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("没有要复习的错题", style = type.title, color = colors.ink)
                    Spacer(modifier = Modifier.height(Space.sm))
                    Text(
                        "用译键翻译时被纠正过的句子会出现在这里。",
                        style = type.small,
                        color = colors.inkMuted,
                    )
                }

                MistakePhase.DONE -> MistakeSummary(
                    total = slots.size,
                    right = slots.size - failures.size,
                    failures = failures,
                    onFinish = { navController.popBackStack() },
                )

                else -> {
                    // Explicit type: the ?: chain through two delegated states resolves to
                    // Any without it, and every later member access then fails.
                    val item: PointWithExamples? = retryItem ?: slots.getOrNull(index)?.item
                    if (item == null) return@Column

                    SessionProgress(
                        current = if (retryItem != null) slots.size else index + 1,
                        total = slots.size,
                    )
                    Spacer(modifier = Modifier.height(Space.lg))

                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        // The sentence the learner actually wrote.
                        val example = item.examples.firstOrNull()
                        if (example != null && example.sourceSentence.isNotBlank()) {
                            Text("你当时写的", style = type.label, color = colors.inkMuted)
                            Spacer(modifier = Modifier.height(Space.xs))
                            Text(example.sourceSentence, style = type.body, color = colors.ink)
                            Spacer(modifier = Modifier.height(Space.xl))
                        }

                        Text("这次要记住", style = type.label, color = colors.inkMuted)
                        Spacer(modifier = Modifier.height(Space.xs))
                        Text(item.point.label, style = type.display, color = colors.ink)
                        Spacer(modifier = Modifier.height(Space.lg))

                        if (!revealed) {
                            Text(
                                "想清楚正确的说法，再看答案",
                                style = type.small,
                                color = colors.inkMuted,
                            )
                        } else {
                            val wrong = item.point.wrongForms.firstOrNull()
                            if (wrong != null && item.point.correctForm.isNotBlank()) {
                                Text(
                                    text = "$wrong  →  ${item.point.correctForm}",
                                    style = type.title,
                                    color = colors.ink,
                                )
                                Spacer(modifier = Modifier.height(Space.md))
                            }
                            if (item.point.explanation.isNotBlank()) {
                                Text(item.point.explanation, style = type.body, color = colors.ink)
                                Spacer(modifier = Modifier.height(Space.lg))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                                Stat("错过", "${item.point.occurrences} 次")
                                Stat(
                                    "最近",
                                    if (item.point.history.isEmpty()) "—" else "${item.point.recentAccuracy}%",
                                )
                                Stat("连对", "${item.point.correctStreak}")
                            }
                        }

                        if (item.examples.size > 1) {
                            Spacer(modifier = Modifier.height(Space.xl))
                            Text("也在这些句子里错过", style = type.label, color = colors.inkMuted)
                            Spacer(modifier = Modifier.height(Space.sm))
                            item.examples.drop(1).forEach { ex ->
                                Text(
                                    text = "· ${ex.sourceSentence}",
                                    style = type.small,
                                    color = colors.inkMuted,
                                )
                                Spacer(modifier = Modifier.height(Space.xs))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Space.lg))

                    if (!revealed) {
                        BigAction("看答案") { revealed = true }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            BigAction("没想起来", emphasised = false, weight = 1f) { advance(item, false) }
                            Spacer(modifier = Modifier.width(0.dp))
                            BigAction("记住了", weight = 1f) { advance(item, true) }
                        }
                    }
                    Spacer(modifier = Modifier.height(Space.xl))
                }
            }
        }
    }
}

@Composable
private fun SessionProgress(current: Int, total: Int) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(colors.rule),
    ) {
        Box(
            modifier = Modifier
                .width(maxWidth * (current.toFloat() / total.coerceAtLeast(1)))
                .fillMaxHeight()
                .background(colors.ink),
        )
    }
    Spacer(modifier = Modifier.height(Space.sm))
    Text("$current / $total", style = type.label, color = colors.inkMuted)
}

@Composable
private fun Stat(label: String, value: String) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    Column {
        Text(label, style = type.label, color = colors.inkMuted)
        Spacer(modifier = Modifier.height(Space.hair))
        Text(value, style = type.figure, color = colors.ink)
    }
}

@Composable
private fun BigAction(
    label: String,
    weight: Float = 1f,
    emphasised: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    Box(
        modifier = Modifier
            .fillMaxWidth(weight)
            .height(52.dp)
            .background(if (emphasised) colors.accent else colors.paper, Shape.action)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.body,
            fontWeight = FontWeight.Medium,
            color = if (emphasised) colors.onAccent else colors.inkMuted,
        )
    }
}

/** End of session: what changed, not what was scored. */
@Composable
private fun MistakeSummary(
    total: Int,
    right: Int,
    failures: List<PointWithExamples>,
    onFinish: () -> Unit,
) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.weight(1f))
        Text("这一轮结束了", style = type.title, color = colors.ink)
        Spacer(modifier = Modifier.height(Space.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
            Stat("复习", "$total 个点")
            Stat("记住", "$right 个")
        }
        if (failures.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Space.xl))
            Text("还要再练的", style = type.label, color = colors.inkMuted)
            Spacer(modifier = Modifier.height(Space.sm))
            failures.take(5).forEach { f ->
                Text(f.point.label, style = type.body, color = colors.ink)
                Spacer(modifier = Modifier.height(Space.xs))
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        BigAction("完成", onClick = onFinish)
        Spacer(modifier = Modifier.height(Space.xl))
    }
}
