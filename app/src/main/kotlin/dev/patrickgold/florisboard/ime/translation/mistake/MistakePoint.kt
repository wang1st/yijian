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

package dev.patrickgold.florisboard.ime.translation.mistake

/**
 * One recurring error, tracked as a point of knowledge rather than as a sentence.
 *
 * The unit matters. If the same slip happens in three different sentences —
 * "I very like this movie", "I very like the film", "I really very like it" — a store keyed
 * by sentence holds three unrelated failures. Keyed by point it holds one problem: *very*
 * cannot modify a verb. Only the second reading can answer "am I getting better at this?"
 *
 * [id] comes from the model, which groups corrections into a small set of stable topics
 * ("very-modifies-verb", "missing-article", …). Corrections that cannot be grouped keep a
 * synthetic id derived from their own shape, so nothing is lost.
 */
data class MistakePoint(
    val id: String,
    /**
     * 拼写 / 语法 / 用词 / 表达 for actual errors, or 说法 for an upgrade.
     *
     * 说法 is a different kind of lesson: the sentence was not wrong, a native speaker would
     * simply have put it another way. It is worth reviewing precisely because the learner
     * already gets the meaning across — the thing to learn is the phrasing, not the grammar.
     */
    val kind: String,
    /** Short human label shown in the app, e.g. "very 不能修饰动词". */
    val label: String,
    /** The correct form, when the point has a single obvious one. */
    val correctForm: String,
    /** The rule, in one or two sentences. */
    val explanation: String,
    /** Every distinct wrong form seen, most recent first. */
    val wrongForms: List<String>,
    /** How many times this point has been failed, ever. */
    val occurrences: Int,
    /**
     * The last [HISTORY_WINDOW] review outcomes, oldest first. `true` = answered correctly.
     * This is the number that answers "getting better": a point that failed twelve times but
     * passed the last eight is in a different state from one that failed three times and just
     * failed again.
     */
    val history: List<Boolean>,
    /** Leitner stage 0..4, drives [dueAt]. -1 means never reviewed. */
    val stage: Int,
    /** Epoch millis when this point next needs review. */
    val dueAt: Long,
    /** Timestamp of the most recent failure. */
    val lastFailedAt: Long,
    /** Timestamp of the most recent review, succeeded or not. */
    val lastReviewedAt: Long,
    val importedKeys: Set<String> = emptySet(),
) {
    /** Consecutive correct reviews at the end of [history]. */
    val correctStreak: Int
        get() = history.reversed().takeWhile { it }.size

    /** Longest correct run anywhere in [history]. */
    val bestStreak: Int
        get() {
            var best = 0
            var run = 0
            for (ok in history) {
                run = if (ok) run + 1 else 0
                if (run > best) best = run
            }
            return best
        }

    /** Accuracy over the recent window, 0..100. */
    val recentAccuracy: Int
        get() = if (history.isEmpty()) 0 else history.count { it } * 100 / history.size

    /** Considered learned once five correct reviews in a row have landed. */
    val mastered: Boolean get() = correctStreak >= MASTERED_STREAK

    /** Kept failing recently: this is what the review session should prioritise. */
    val stubborn: Boolean
        get() = occurrences >= 3 && history.takeLast(3).count { !it } >= 2

    companion object {
        /** How many recent outcomes are kept per point. */
        const val HISTORY_WINDOW = 10
        const val MASTERED_STREAK = 5
    }
}

/**
 * One concrete failure, kept so a review can show the learner the sentence they actually wrote.
 *
 * Points say what to practise; instances say what it looked like. Reviewing a point with your
 * own sentence is a different experience from reviewing a drill — you recognise the moment.
 */
data class MistakeInstance(
    /** The point this failure belongs to. */
    val pointId: String,
    /** The full sentence the user produced. */
    val sourceSentence: String,
    /** What they meant, in Chinese. */
    val sourceTranslation: String,
    /** The concrete wrong fragment. */
    val wrong: String,
    /** What it should have been. */
    val right: String,
    /** The model's sentence about why. */
    val why: String,
    val timestamp: Long,
)
