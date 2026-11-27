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

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.ime.translation.review.Grade
import dev.patrickgold.florisboard.ime.translation.review.ReviewAi
import dev.patrickgold.florisboard.ime.translation.review.ReviewCard
import dev.patrickgold.florisboard.ime.translation.review.ReviewStore
import kotlinx.coroutines.launch

/**
 * Practice screen.
 *
 * Rendering only: every decision about the flow lives in [PracticeSession]. The screen reads
 * [SessionState] and forwards taps, which is what keeps the "answered but nothing happens"
 * class of bug out of the UI layer.
 */
@Composable
fun ReviewPracticeScreen() {
    val scope = rememberCoroutineScope()

    val session = remember {
        PracticeSession(object : SessionHost {
            override suspend fun loadQueue() = ReviewStore.dueQueue()

            override suspend fun prepareExercise(card: ReviewCard, others: List<ReviewCard>): ReviewAi.AiExercise? {
                val primary = card.entry.corrections.firstOrNull()
                return ReviewAi.makeExercise(
                    english = card.entry.corrected.ifBlank { card.entry.original },
                    chinese = card.entry.translation,
                    box = card.box,
                    isNew = card.isNew,
                    knownMistakes = card.entry.corrections.map { "${it.wrong} → ${it.right}" },
                    mistakeTag = primary?.tag.orEmpty(),
                    wrongText = primary?.wrong.orEmpty(),
                    rightText = primary?.right.orEmpty(),
                )
            }

            override suspend fun gradeAi(target: String, answer: String, chinese: String) =
                ReviewAi.gradeAnswer(target, answer, chinese)

            override suspend fun recordGrade(cardId: String, grade: Grade) =
                ReviewStore.grade(cardId, grade)
        })
    }
    LaunchedEffect(Unit) { session.start() }

    // Tiles are a view-level concern (shuffle order, placement), so they live here.
    var tiles by remember { androidx.compose.runtime.mutableStateOf<TileState?>(null) }
    var version by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val st = session.state
    LaunchedEffect(st.card?.id, st.typeName) {
        val card = st.card ?: return@LaunchedEffect
        tiles = if (st.typeName == "RECALL") TileState(card.entry.corrected) else null
        version++
    }

    val navController = dev.patrickgold.florisboard.app.LocalNavController.current
    val finished = st.phase == SessionPhase.DONE

    FlorisScreenNg(
        title = "今日练习",
        previewFieldVisible = false,
        // The celebration fills the screen edge to edge.
        navigationIconVisible = !finished,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (finished) PaddingValues(0.dp) else contentPadding)
                .padding(horizontal = if (finished) 0.dp else 16.dp),
        ) {
            when (st.phase) {
                SessionPhase.LOADING -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = DuoGreen) }

                SessionPhase.EMPTY -> SessionEmptyState()

                SessionPhase.DONE -> SessionCelebration(
                    total = st.total,
                    good = st.goodCount,
                    onFinish = {
                        // Back to the review tab rather than deeper into the stack.
                        navController.popBackStack()
                    },
                )

                else -> {
                    val card = st.card ?: return@Column
                    SessionBody(
                        session = session,
                        state = st,
                        card = card,
                        tiles = tiles,
                        version = version,
                        onTilesChanged = { version++ },
                        onCheck = { answer -> scope.launch { session.check(answer) } },
                        onGrade = { g -> scope.launch { session.grade(g) } },
                    )
                }
            }
        }
    }
}

/** The whole question area: progress, mistake banner, prompt, answer input, feedback, actions. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SessionBody(
    session: PracticeSession,
    state: SessionState,
    card: ReviewCard,
    tiles: TileState?,
    version: Int,
    onTilesChanged: () -> Unit,
    onCheck: (String) -> Unit,
    onGrade: (Grade) -> Unit,
) {
    // 32 dp top bar: progress pill + counter. No title chrome, one decision per screen.
    LessonProgressBar(
        progress = (state.index + if (state.phase == SessionPhase.GRADED) 1 else 0) / state.total.toFloat(),
        label = "${state.index + 1}/${state.total}",
    )
    Spacer(modifier = Modifier.height(24.dp))

    // Choice questions render options instead of an assembly area; computed once up here so
    // both the scroll content and the bottom action can branch on it.
    val choices = state.aiExercise?.choices?.map { PickedChoice(it.first, it.second) }
        ?: state.exercise?.choices?.map { PickedChoice(it.text, it.correct) }
        ?: emptyList()

    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        // The error this card exists to drill, named before the question.
        val mistakes = card.entry.corrections
        if (mistakes.isNotEmpty()) {
            MistakeBanner(
                wrong = mistakes.first().wrong,
                right = mistakes.first().right,
                why = mistakes.first().why,
                extra = mistakes.size - 1,
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        PromptCard(state = state)
        Spacer(modifier = Modifier.height(14.dp))

        if (choices.isNotEmpty()) {
            // 56 dp rows, 8 dp apart, 12 dp radius, "2 + 2" border and lip.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEachIndexed { i, choice ->
                    val surface = when {
                        state.phase != SessionPhase.GRADED -> SurfaceState.Neutral
                        choice.correct -> SurfaceState.Correct
                        state.picked?.text == choice.text -> SurfaceState.Wrong
                        // Never hide the options the learner did not pick: on a multiple
                        // choice question they are the context that makes the answer mean
                        // something. They stay fully legible, just slightly recessed.
                        else -> SurfaceState.Recessed
                    }
                    AnswerSurface(
                        state = surface,
                        // min 56 dp as measured, but grows when the option wraps: Chinese
                        // translations here are often two lines.
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        onClick = if (state.phase == SessionPhase.QUESTION) {
                            { session.pick(choice) }
                        } else null,
                        verticalPadding = 0.dp,
                    ) {
                        Text(
                            text = "${i + 1}.  ${choice.text}",
                            fontSize = 19.sp,
                            lineHeight = 25.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (state.phase == SessionPhase.QUESTION) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "点上面任一选项作答",
                    fontSize = 12.sp,
                    color = DuoHare,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        } else {
            AnswerArea(
                session = session,
                state = state,
                card = card,
                tiles = tiles,
                version = version,
                onTilesChanged = onTilesChanged,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        ExerciseDetail(
            card = card,
            expanded = state.detailOpen,
            onToggle = { session.toggleDetail() },
            usage = state.aiExercise?.usage.orEmpty(),
            moreExamples = state.aiExercise?.moreExamples.orEmpty(),
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    // One action per state: CHECK (disabled until an answer exists) then CONTINUE.
    // Grading is automatic — the self-assessment row is gone, the diff decides.
    if (state.phase == SessionPhase.QUESTION && choices.isEmpty()) {
        val canCheck = if (state.inputMode == InputMode.TILES) {
            tiles?.isComplete == true
        } else {
            state.typed.isNotBlank()
        }
        LessonButton(
            text = "检查",
            enabled = canCheck,
            onClick = { onCheck(if (state.inputMode == InputMode.TILES) tiles?.answer() ?: "" else state.typed) },
        )
    } else if (state.canAdvance) {
        // Feedback lives in the panel below the action; the button keeps the verdict colour.
        val correct = state.diff?.let { it.similarity >= 60 } ?: (state.picked?.correct == true)
        LessonButton(
            text = "继续",
            enabled = true,
            tone = if (correct) ActionTone.Correct else ActionTone.Wrong,
            onClick = { onGrade(session.autoGrade()) },
        )
    }
    Spacer(modifier = Modifier.height(19.dp))
}

/** Purple marks new material, orange marks error review — same convention as the source. */
private fun sectionTagColor(type: String): Color = when (type) {
    "FIX_ERROR" -> DuoGuineaPig
    "CLOZE" -> Color(0xFFCE82FF)
    else -> DuoBlue
}

@Composable
private fun PromptCard(state: SessionState) {
    val isListen = state.typeName == "LISTEN"
    val spoken = state.aiExercise?.speak ?: state.card?.entry?.corrected.orEmpty()
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = duoCardBg()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTag(text = typeHint(state.typeName), color = sectionTagColor(state.typeName))
                if (state.preparing) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(
                        color = DuoBlue,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (isListen) {
                LaunchedEffect(spoken) { speakSentence(spoken) }
                ListenPrompt(spoken)
            } else {
                Text(
                    text = state.aiExercise?.prompt
                        ?: state.exercise?.prompt
                        ?: state.card?.entry?.translation.orEmpty(),
                    fontSize = 28.sp,
                    lineHeight = 35.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSystemInDarkTheme()) Color(0xFFEDEDED) else DuoInk,
                )
            }
        }
    }
}

/** Word tiles or a text field, depending on how the learner wants to answer. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AnswerArea(
    session: PracticeSession,
    state: SessionState,
    card: ReviewCard,
    tiles: TileState?,
    version: Int,
    onTilesChanged: () -> Unit,
) {
    if (state.inputMode == InputMode.TILES && tiles != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, duoBorder(), RoundedCornerShape(12.dp))
                .padding(10.dp),
        ) {
            if (tiles.placed.isEmpty()) {
                Text("点下面的单词拼出句子", fontSize = 13.sp, color = duoTextMuted())
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    key(version) {
                        tiles.placed.forEachIndexed { pos, source ->
                            PlacedWordTile(text = tiles.tiles[source]) {
                                if (state.phase == SessionPhase.QUESTION) {
                                    tiles.placed.removeAt(pos)
                                    onTilesChanged()
                                }
                            }
                        }
                    }
                }
            }
        }
        if (state.phase == SessionPhase.QUESTION) {
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                key(version) {
                    tiles.tiles.forEachIndexed { source, tile ->
                        if (source !in tiles.placed) {
                            WordTile(text = tile) {
                                tiles.placed.add(source)
                                session.markAnswerStarted()
                                onTilesChanged()
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DuoSecondaryButton(
                    "撤销",
                    onClick = {
                        if (tiles.placed.isNotEmpty()) {
                            tiles.placed.removeAt(tiles.placed.lastIndex)
                            onTilesChanged()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                DuoSecondaryButton(
                    "提示",
                    onClick = {
                        if (tiles.placed.size < tiles.tiles.size) {
                            tiles.placed.add(tiles.placed.size)
                            onTilesChanged()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { session.toggleInputMode() }, modifier = Modifier.weight(1f)) {
                    Text("打字", fontSize = 12.sp, color = DuoBlue, fontWeight = FontWeight.Bold)
                }
            }
        }
    } else {
        OutlinedTextField(
            value = state.typed,
            onValueChange = { session.setTyped(it) },
            enabled = state.phase == SessionPhase.QUESTION,
            placeholder = { Text("打出对应的英文…") },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.phase == SessionPhase.QUESTION && tiles != null) {
            TextButton(onClick = { session.toggleInputMode() }, modifier = Modifier.align(Alignment.End)) {
                Text("改用点词拼句", fontSize = 12.sp, color = DuoBlue, fontWeight = FontWeight.Bold)
            }
        }
    }
}


@Composable
private fun SessionEmptyState() {
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

