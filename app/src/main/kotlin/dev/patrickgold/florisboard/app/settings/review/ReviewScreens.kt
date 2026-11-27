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

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.ime.translation.review.DiffResult
import dev.patrickgold.florisboard.ime.translation.review.Grade
import dev.patrickgold.florisboard.ime.translation.review.ReviewCard
import dev.patrickgold.florisboard.ime.translation.review.ReviewSnapshot
import dev.patrickgold.florisboard.ime.translation.review.ReviewStore
import dev.patrickgold.florisboard.ime.translation.review.WordDiff
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- Duolingo-inspired palette ------------------------------------------------------

private val DuoGreen = Color(0xFF58CC02)
private val DuoGreenShade = Color(0xFF46A302)
private val DuoBlue = Color(0xFF1CB0F6)
private val DuoBlueShade = Color(0xFF1899D6)
private val DuoRed = Color(0xFFFF4B4B)
private val DuoRedShade = Color(0xFFD33131)

@Composable
private fun duoCardBg(): Color =
    if (isSystemInDarkTheme()) Color(0xFF1F2023) else Color.White

@Composable
private fun duoBorder(): Color =
    if (isSystemInDarkTheme()) Color(0xFF33353A) else Color(0xFFE5E5E5)

@Composable
private fun duoTextMuted(): Color =
    if (isSystemInDarkTheme()) Color(0xFFAFB1B6) else Color(0xFF777777)

/** Bottom-sheet radius used for cards and panels. */
private val SheetShape = RoundedCornerShape(16.dp)

/**
 * Pill button with the signature 3dp "pressed" bottom edge that shrinks on tap, like a
 * physical key. Used for every primary action in the review flow.
 */
@Composable
private fun DuoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = DuoGreen,
    shade: Color = DuoGreenShade,
) {
    var pressed by remember { mutableStateOf(false) }
    val bg = if (enabled) color else color.copy(alpha = 0.35f)
    val edge = if (enabled) shade else shade.copy(alpha = 0.35f)
    Box(
        modifier = modifier
            .height(52.dp)
            .padding(bottom = if (pressed) 0.dp else 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(edge)
            .clickable(enabled = enabled) {
                pressed = true
                onClick()
                pressed = false
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (pressed) 52.dp else 49.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text.uppercase(),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

@Composable
private fun DuoSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Color.Transparent else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (enabled) duoBorder() else duoBorder().copy(alpha = 0.4f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            else duoTextMuted().copy(alpha = 0.5f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---- review home --------------------------------------------------------------------

@Composable
fun ReviewHomeScreen() {
    val navController = LocalNavController.current
    var snapshot by remember { mutableStateOf<ReviewSnapshot?>(null) }
    LaunchedEffect(Unit) { snapshot = ReviewStore.snapshot() }

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

            // One hero card carries the whole decision: how am I doing, and what do I do now?
            Card(
                shape = SheetShape,
                colors = CardDefaults.cardColors(containerColor = duoCardBg()),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
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
                    // Today's workload, expressed as a bar so it reads at a glance.
                    val done = s?.reviewedToday ?: 0
                    val goal = (done + pending).coerceAtLeast(1)
                    LinearProgressIndicator(
                        progress = { done.toFloat() / goal },
                        color = DuoGreen,
                        trackColor = duoBorder(),
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

            Text(
                text = "你平时用译键翻译过的句子会自动变成复习卡片，按 1 / 3 / 7 / 15 / 30 天的间隔重现；答错降级，明天重来。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = duoTextMuted(),
                modifier = Modifier.padding(top = 16.dp, bottom = 20.dp),
            )
        }
    }
}

// ---- practice session ---------------------------------------------------------------

private enum class Phase { LOADING, EMPTY, QUESTION, GRADED, DONE }

/** How the user assembles the English answer. */
private enum class InputMode { TILES, KEYBOARD }

/**
 * One tile in the word bank. [source] is the word's position in the target sentence (used for
 * hints and to separate duplicate words); it doubles as a stable key.
 */
private data class Tile(val text: String, val source: Int)

/** A shuffled word bank plus which tiles have been placed and which were revealed for free. */
private class TileState(val target: String) {
    private val words: List<String> =
        Regex("[A-Za-z0-9']+").findAll(target).map { it.value }.toList()

    /** Display order is shuffled; each tile remembers where it belongs in the sentence. */
    val tiles: List<Tile> = words.mapIndexed { i, w -> Tile(w, i) }.shuffled()

    /** Source indices placed so far, in the order the user tapped them. */
    val placed = mutableListOf<Int>()

    /** The first word is pre-placed so the answer never starts from a blank slate. */
    val freeCount = if (words.isEmpty()) 0 else 1

    init {
        if (freeCount > 0) placed.add(0)
    }

    val isComplete: Boolean get() = placed.size == tiles.size

    fun hint(): Boolean {
        val next = placed.size
        if (next >= tiles.size) return false
        placed.add(next)
        return true
    }

    fun answer(): String = placed.joinToString(" ") { tiles[it].text }
}

@Composable
fun ReviewPracticeScreen() {
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<List<ReviewCard>>(emptyList()) }
    var phase by remember { mutableStateOf(Phase.LOADING) }
    var index by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(InputMode.TILES) }
    var typed by remember { mutableStateOf("") }
    var tiles by remember { mutableStateOf<TileState?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var diff by remember { mutableStateOf<DiffResult?>(null) }
    var goodCount by remember { mutableIntStateOf(0) }

    fun startCard(card: ReviewCard) {
        val wordCount = Regex("[A-Za-z0-9']+").findAll(card.entry.corrected).count()
        // Very long sentences are easier to type than to tap out tile by tile.
        tiles = if (wordCount in 2..16) TileState(card.entry.corrected) else null
        if (tiles == null) mode = InputMode.KEYBOARD
        typed = ""
        diff = null
        version++
    }

    LaunchedEffect(Unit) {
        queue = ReviewStore.dueQueue()
        if (queue.isEmpty()) {
            phase = Phase.EMPTY
        } else {
            phase = Phase.QUESTION
            startCard(queue[0])
        }
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
                Phase.LOADING -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = DuoGreen) }

                Phase.EMPTY -> ReviewEmptyState()

                Phase.DONE -> ReviewDoneState(total = queue.size, good = goodCount)

                else -> {
                    val card = queue[index]
                    sessionContent(
                        card = card,
                        index = index,
                        total = queue.size,
                        tiles = tiles,
                        mode = mode,
                        phase = phase,
                        typed = typed,
                        diff = diff,
                        version = version,
                        onTyped = { typed = it },
                        onPick = { source ->
                            tiles?.let {
                                if (phase == Phase.QUESTION) {
                                    it.placed.add(source)
                                    version++
                                }
                            }
                        },
                        onUnpick = { pos ->
                            tiles?.let {
                                if (phase == Phase.QUESTION && pos >= it.freeCount) {
                                    it.placed.removeAt(pos)
                                    version++
                                }
                            }
                        },
                        onUndo = { tiles?.let { if (it.placed.size > it.freeCount) { it.placed.removeLast(); version++ } } },
                        onHint = { tiles?.let { if (it.hint()) version++ } },
                        onSwitchMode = { mode = if (mode == InputMode.TILES) InputMode.KEYBOARD else InputMode.TILES },
                        onCheck = {
                            val answer = if (mode == InputMode.TILES) tiles?.answer() ?: "" else typed
                            diff = WordDiff.grade(card.entry.corrected, answer)
                            phase = Phase.GRADED
                        },
                        onGrade = { g ->
                            scope.launch {
                                ReviewStore.grade(card.id, g)
                                if (g == Grade.GOOD) goodCount++
                                if (index + 1 >= queue.size) {
                                    phase = Phase.DONE
                                } else {
                                    index++
                                    startCard(queue[index])
                                    phase = Phase.QUESTION
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Session body: progress bar, prompt card, answer area and the sticky action bar. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.sessionContent(
    card: ReviewCard,
    index: Int,
    total: Int,
    tiles: TileState?,
    mode: InputMode,
    phase: Phase,
    typed: String,
    diff: DiffResult?,
    version: Int,
    onTyped: (String) -> Unit,
    onPick: (Int) -> Unit,
    onUnpick: (Int) -> Unit,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    onSwitchMode: () -> Unit,
    onCheck: () -> Unit,
    onGrade: (Grade) -> Unit,
) {
    // Progress + close row
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { (index + if (phase == Phase.GRADED) 1 else 0) / total.toFloat() },
            color = DuoGreen,
            trackColor = duoBorder(),
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "${index + 1}/$total",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = duoTextMuted(),
        )
    }
    Spacer(modifier = Modifier.height(18.dp))

    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        // Prompt: the Chinese sentence to translate back
        Card(
            shape = SheetShape,
            colors = CardDefaults.cardColors(containerColor = duoCardBg()),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "把这句话翻回英文",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = DuoBlue,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = card.entry.translation, fontSize = 21.sp, lineHeight = 30.sp)
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        if (tiles != null && mode == InputMode.TILES) {
            // Answer line: placed words
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, duoBorder(), RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                if (tiles.placed.isEmpty()) {
                    Text(
                        text = "点下面的单词拼出句子",
                        fontSize = 13.sp,
                        color = duoTextMuted(),
                        modifier = Modifier.padding(4.dp),
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        key(version) {
                            tiles.placed.forEachIndexed { pos, source ->
                                val tile = tiles.tiles[source]
                                WordChip(
                                    text = tile.text,
                                    locked = pos < tiles.freeCount,
                                    onClick = { onUnpick(pos) },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            if (phase == Phase.QUESTION) {
                // Word bank
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    key(version) {
                        tiles.tiles.forEachIndexed { source, tile ->
                            if (source !in tiles.placed) {
                                WordChip(text = tile.text, onClick = { onPick(source) })
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DuoSecondaryButton("撤销", onClick = onUndo, modifier = Modifier.weight(1f))
                    DuoSecondaryButton("提示", onClick = onHint, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSwitchMode, modifier = Modifier.weight(1f)) {
                        Text("打字", fontSize = 12.sp, color = DuoBlue, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = typed,
                onValueChange = onTyped,
                enabled = phase == Phase.QUESTION,
                placeholder = { Text("打出对应的英文…") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (phase == Phase.QUESTION && tiles != null) {
                TextButton(onClick = onSwitchMode, modifier = Modifier.align(Alignment.End)) {
                    Text("改用点词拼句", fontSize = 12.sp, color = DuoBlue, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (phase == Phase.GRADED && diff != null) {
            Spacer(modifier = Modifier.height(12.dp))
            GradeFeedback(diff = diff, card = card)
        }
    }

    // Sticky bottom action bar, kept clear of the bottom navigation's gesture area.
    Spacer(modifier = Modifier.height(10.dp))
    if (phase == Phase.QUESTION) {
        val canCheck = if (mode == InputMode.TILES) tiles?.isComplete == true else typed.isNotBlank()
        DuoButton(
            text = "检查",
            onClick = onCheck,
            enabled = canCheck,
            modifier = Modifier.fillMaxWidth(),
        )
    } else if (phase == Phase.GRADED && diff != null) {
        DuoButton(
            text = "继续",
            onClick = { onGrade(diff.suggestedGrade) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DuoSecondaryButton("忘了", onClick = { onGrade(Grade.FORGOT) }, modifier = Modifier.weight(1f))
            DuoSecondaryButton("模糊", onClick = { onGrade(Grade.HARD) }, modifier = Modifier.weight(1f))
            DuoSecondaryButton("记得", onClick = { onGrade(Grade.GOOD) }, modifier = Modifier.weight(1f))
        }
    }
    Spacer(modifier = Modifier.height(14.dp))
}

/** Green/red feedback panel shown after checking, Douolingo style. */
@Composable
private fun GradeFeedback(diff: DiffResult, card: ReviewCard) {
    val perfect = diff.similarity >= 90
    val accent = if (perfect) DuoGreen else DuoRed
    val bg = if (isSystemInDarkTheme()) {
        accent.copy(alpha = 0.14f)
    } else {
        accent.copy(alpha = 0.09f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SheetShape)
            .background(bg)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    perfect -> "太棒了！"
                    diff.similarity >= 60 -> "接近了，注意细节"
                    else -> "再练一次会更好"
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text("${diff.similarity}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
        }
        Spacer(modifier = Modifier.height(10.dp))
        // Correct sentence, per-word hit/miss
        val annotated: AnnotatedString = buildAnnotatedString {
            diff.targetWords.forEachIndexed { i, w ->
                if (i > 0) append(" ")
                withStyle(
                    SpanStyle(
                        color = if (w.matched) MaterialTheme.colorScheme.onSurface else DuoRed,
                        fontWeight = if (w.matched) FontWeight.Normal else FontWeight.Bold,
                    )
                ) { append(w.text) }
            }
        }
        Text(text = annotated, fontSize = 17.sp, lineHeight = 25.sp)
        if (card.entry.corrections.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            card.entry.corrections.take(3).forEach { c ->
                Text(
                    text = "${c.wrong} → ${c.right}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (c.why.isNotBlank()) {
                    Text(text = c.why, fontSize = 12.sp, color = duoTextMuted())
                }
            }
        }
    }
}

/** A tappable word tile with the pressed-edge look. */
@Composable
private fun WordChip(
    text: String,
    onClick: () -> Unit,
    locked: Boolean = false,
) {
    val container = animateColorAsState(
        targetValue = if (locked) duoBorder().copy(alpha = 0.55f) else duoCardBg(),
        label = "chipBg",
    ).value
    val edge = duoBorder()
    Box(
        modifier = Modifier
            .height(48.dp)
            .padding(bottom = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(edge)
            .clickable(enabled = !locked, onClick = onClick),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            // Hug the word instead of filling the row: tiles flow across the line like
            // Duolingo's word bank, so a sentence fits in a couple of rows.
            modifier = Modifier
                .height(45.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(container)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = if (locked) duoTextMuted() else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReviewEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🎉", fontSize = 56.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text("今日没有待复习的卡片", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text("平时用译键翻译的句子会自动变成卡片。", fontSize = 13.sp, color = duoTextMuted())
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReviewDoneState(total: Int, good: Int) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🏆", fontSize = 64.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text("练习完成！", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text("共 $total 张 · 记得 $good 张", fontSize = 14.sp, color = duoTextMuted())
    }
}

// ---- history browser ---------------------------------------------------------------

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
        shape = SheetShape,
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
            Text(
                text = card.entry.corrected,
                fontSize = 14.sp,
                color = duoTextMuted(),
            )
            if (card.entry.corrections.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (expanded) "收起 ▴" else "✎ ${card.entry.corrections.size} 处纠正 ▾",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = DuoBlue,
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(vertical = 2.dp),
                )
                if (expanded) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (card.entry.original.isNotBlank() && card.entry.original != card.entry.corrected) {
                            Text(
                                text = "原文　${card.entry.original}",
                                fontSize = 13.sp,
                                color = duoTextMuted(),
                            )
                        }
                        card.entry.corrections.forEach { c ->
                            Text(
                                text = "${c.wrong} → ${c.right}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            if (c.why.isNotBlank()) {
                                Text(text = c.why, fontSize = 12.sp, color = duoTextMuted())
                            }
                        }
                    }
                }
            }
        }
    }
}
