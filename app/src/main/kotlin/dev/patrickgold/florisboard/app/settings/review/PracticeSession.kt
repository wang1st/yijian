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

import androidx.compose.runtime.mutableStateOf
import dev.patrickgold.florisboard.ime.translation.review.DiffResult
import dev.patrickgold.florisboard.ime.translation.review.Grade
import dev.patrickgold.florisboard.ime.translation.review.ReviewAi
import dev.patrickgold.florisboard.ime.translation.review.ReviewCard

/**
 * Phases of a practice session.
 *
 * This used to live inside the screen composable together with the answer state, which is how
 * two separate bugs slipped through: a tap handler that returned out of the composable before
 * changing the phase, and three render branches that all required a non-null diff while
 * multiple-choice answers never produce one. Keeping the flow in a plain class means it can be
 * reasoned about (and tested) without Compose.
 */
enum class SessionPhase { LOADING, EMPTY, QUESTION, GRADED, DONE }

/** How the learner assembles an English answer in the recall exercise. */
enum class InputMode { TILES, KEYBOARD }

/** A tapped answer in a multiple-choice exercise. */
data class PickedChoice(val text: String, val correct: Boolean)

/**
 * Everything the practice screen renders, produced only by [PracticeSession].
 *
 * The UI layer stays dumb: it reads this state and forwards events back. All transitions happen
 * in the session, so a phase can never be left stranded.
 */
data class SessionState(
    val phase: SessionPhase = SessionPhase.LOADING,
    val queue: List<ReviewCard> = emptyList(),
    val index: Int = 0,
    val exercise: PreparedExercise? = null,
    val aiExercise: ReviewAi.AiExercise? = null,
    val preparing: Boolean = false,
    val picked: PickedChoice? = null,
    val typed: String = "",
    val inputMode: InputMode = InputMode.TILES,
    val diff: DiffResult? = null,
    val aiComment: String? = null,
    val aiChecking: Boolean = false,
    val detailOpen: Boolean = false,
    val goodCount: Int = 0,
) {
    val card: ReviewCard? get() = queue.getOrNull(index)
    val total: Int get() = queue.size

    /** True when the current exercise is answered by tapping options. */
    val isChoice: Boolean
        get() = (exercise?.choices?.isNotEmpty() == true) ||
            (aiExercise?.choices?.isNotEmpty() == true) ||
            picked != null

    /** The exercise type currently being shown, model-authored or local. */
    val typeName: String
        get() = aiExercise?.type ?: exercise?.type?.name ?: "RECALL"

    /** True once an answer has been committed for this card. */
    val canAdvance: Boolean
        get() = phase == SessionPhase.GRADED && (diff != null || picked != null)
}

/** Side effects the session needs from the outside world (model calls, persistence). */
interface SessionHost {
    suspend fun loadQueue(): List<ReviewCard>
    suspend fun prepareExercise(card: ReviewCard, others: List<ReviewCard>): ReviewAi.AiExercise?
    suspend fun gradeAi(target: String, answer: String, chinese: String): ReviewAi.AiGrade
    suspend fun recordGrade(cardId: String, grade: Grade)
}

/**
 * The practice flow: load a queue, present each card, grade it, advance.
 *
 * Every state change goes through here. The screen never mutates phase, picked or diff itself.
 */
class PracticeSession(private val host: SessionHost) {
    private val stateFlow = mutableStateOf(SessionState())

    /** Current state; the screen observes this and nothing else. */
    var state: SessionState
        get() = stateFlow.value
        private set(value) { stateFlow.value = value }

    /** Loads the queue and prepares the first exercise. */
    suspend fun start() {
        val queue = host.loadQueue()
        if (queue.isEmpty()) {
            state = state.copy(phase = SessionPhase.EMPTY)
            return
        }
        state = state.copy(queue = queue, index = 0, phase = SessionPhase.QUESTION)
        prepare(queue[0])
    }

    /**
     * Builds the question for [card]. The local version renders immediately, the model's
     * version replaces it when it arrives (and is skipped silently if it never does).
     */
    private suspend fun prepare(card: ReviewCard) {
        val local = ExerciseFactory.prepare(card, state.queue.filter { it.id != card.id })
        state = state.copy(
            exercise = local,
            aiExercise = null,
            preparing = true,
            picked = null,
            diff = null,
            typed = "",
            aiComment = null,
            aiChecking = false,
            detailOpen = false,
            // Tiles are only meaningful for recall; other types render their own input.
            inputMode = InputMode.TILES,
        )
        val ai = host.prepareExercise(card, state.queue.filter { it.id != card.id })
        // The user may have answered already while the model was thinking.
        if (state.phase == SessionPhase.QUESTION && state.card?.id == card.id) {
            state = state.copy(aiExercise = ai, preparing = false)
        } else {
            state = state.copy(preparing = false)
        }
    }

    // ---- events ---------------------------------------------------------------------

    fun setTyped(value: String) {
        if (state.phase == SessionPhase.QUESTION) state = state.copy(typed = value)
    }

    fun toggleInputMode() {
        state = state.copy(
            inputMode = if (state.inputMode == InputMode.TILES) InputMode.KEYBOARD else InputMode.TILES
        )
    }

    fun toggleDetail() {
        state = state.copy(detailOpen = !state.detailOpen)
    }

    /** A multiple-choice answer: graded immediately, there is nothing to assemble. */
    fun pick(choice: PickedChoice) {
        if (state.phase != SessionPhase.QUESTION) return
        val answer = state.aiExercise?.answer ?: state.exercise?.answer ?: state.card?.entry?.corrected.orEmpty()
        state = state.copy(
            picked = choice,
            diff = null,
            aiComment = if (choice.correct) "答对了：$answer" else "正确答案：$answer",
            phase = SessionPhase.GRADED,
        )
    }

    /** A produced answer (tiles or typing): diff locally, ask the model only when ambiguous. */
    suspend fun check(answer: String) {
        if (state.phase != SessionPhase.QUESTION) return
        val card = state.card ?: return
        val local = dev.patrickgold.florisboard.ime.translation.review.WordDiff
            .grade(card.entry.corrected, answer)
        state = state.copy(diff = local, aiComment = null, phase = SessionPhase.GRADED)
        if (!dev.patrickgold.florisboard.ime.translation.review.ReviewAi.shouldAskAi(local.similarity)) return
        state = state.copy(aiChecking = true)
        val verdict = host.gradeAi(card.entry.corrected, answer, card.entry.translation)
        val comment = when {
            verdict.accepted -> verdict.feedback.ifBlank { "AI 判为同义表达" }
            verdict.fix.isNotBlank() -> "AI 认为还差：${verdict.fix}"
            verdict.feedback.isNotBlank() -> "AI：${verdict.feedback}"
            else -> null
        }
        state = state.copy(aiComment = comment, aiChecking = false)
    }

    /** Commits the self-grade and moves on. */
    suspend fun grade(grade: Grade) {
        if (!state.canAdvance) return
        val card = state.card ?: return
        host.recordGrade(card.id, grade)
        // Feed the mistake-kind tracker: this is what turns review activity into a visible
        // "this error type is receding" number.
        dev.patrickgold.florisboard.ime.translation.review.MistakeTracker.record(
            kind = dev.patrickgold.florisboard.ime.translation.review.MistakeTracker.kindOf(card),
            correct = grade == Grade.GOOD,
        )
        val nextIndex = state.index + 1
        val good = state.goodCount + if (grade == Grade.GOOD) 1 else 0
        if (nextIndex >= state.queue.size) {
            state = state.copy(phase = SessionPhase.DONE, goodCount = good)
            return
        }
        state = state.copy(index = nextIndex, goodCount = good, phase = SessionPhase.QUESTION)
        prepare(state.queue[nextIndex])
    }

    /** The grade the UI should propose, based on how the answer landed. */
    fun suggestedGrade(): Grade =
        state.diff?.suggestedGrade
            ?: if (state.picked?.correct == true) Grade.GOOD else Grade.FORGOT
}
