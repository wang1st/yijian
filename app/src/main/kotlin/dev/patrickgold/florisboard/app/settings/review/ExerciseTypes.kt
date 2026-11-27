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

import dev.patrickgold.florisboard.ime.translation.review.ReviewCard

/**
 * Exercise types. Rotating them keeps a session from feeling like the same sentence typed over
 * and over: the same card comes back in a different shape each time it is due.
 *
 * Difficulty ordering matters — a card that is still new should be met with a recognition task,
 * and only a familiar one should be asked to produce English from scratch.
 */
enum class ExerciseType(val label: String, val hint: String) {
    /** Chinese prompt, English answer typed or assembled from tiles. Production. */
    RECALL("回译", "把中文翻回英文"),

    /** One word removed from the English sentence, four options. Recognition. */
    CLOZE("选词填空", "选出正确的词"),

    /** English sentence spoken by TTS, pick the matching text. Listening. */
    LISTEN("听音辨句", "听一句英文，选出你听到的内容"),

    /** An error deliberately introduced; find and correct it. Proofreading. */
    FIX_ERROR("改错", "这句话有一处不对，选出并改正"),

    /** English shown, pick the right Chinese among distractors. Recognition. */
    MEANING("看英选中", "这句话是什么意思"),

    ;

    companion object {
        /**
         * Picks a type suited to how well the card is known.
         *
         * New cards get recognition first (low pressure, builds familiarity). Cards that have
         * been recalled correctly get production and proofreading. The rotation is seeded by the
         * card and its repetition count so a card does not always land on the same type.
         */
        fun forCard(card: ReviewCard): ExerciseType {
            val pool = when {
                card.isNew || card.box <= 0 -> listOf(CLOZE, MEANING, LISTEN)
                card.box <= 2 -> listOf(RECALL, CLOZE, LISTEN, MEANING)
                else -> listOf(RECALL, FIX_ERROR, LISTEN)
            }
            val seed = (card.id.hashCode() + card.reps) and Int.MAX_VALUE
            return pool[seed % pool.size]
        }
    }
}

/** A multiple-choice option, with whether it is the right answer. */
data class Choice(val text: String, val correct: Boolean)

/** One prepared question: everything the UI needs to render, regardless of type. */
data class PreparedExercise(
    val type: ExerciseType,
    val card: ReviewCard,
    /** What the user is asked to answer (Chinese prompt, spoken text, sentence to fix...). */
    val prompt: String,
    val choices: List<Choice>,
    /** The sentence as it should read afterwards, shown in the feedback panel. */
    val answer: String,
)

/**
 * Builds exercises from a card. Choices are generated locally from the user's own other cards
 * and from simple transformations, so this costs nothing and works offline. When the local pool
 * is too thin the exercise falls back to RECALL, which needs no distractors.
 */
object ExerciseFactory {
    private val WORD = Regex("[A-Za-z0-9']+")

    fun prepare(card: ReviewCard, others: List<ReviewCard>): PreparedExercise {
        val type = ExerciseType.forCard(card)
        val english = card.entry.corrected.ifBlank { card.entry.original }
        val chinese = card.entry.translation
        return when (type) {
            ExerciseType.RECALL -> PreparedExercise(
                type = type,
                card = card,
                prompt = chinese,
                choices = emptyList(),
                answer = english,
            )

            ExerciseType.CLOZE -> buildCloze(card, english) ?: fallbackRecall(card, english, chinese)

            ExerciseType.LISTEN -> buildListen(card, english, chinese, others)
                ?: fallbackRecall(card, english, chinese)

            ExerciseType.FIX_ERROR -> buildFixError(card, english) ?: fallbackRecall(card, english, chinese)

            ExerciseType.MEANING -> buildMeaning(card, english, chinese, others)
                ?: fallbackRecall(card, english, chinese)
        }
    }

    private fun fallbackRecall(card: ReviewCard, english: String, chinese: String) =
        PreparedExercise(ExerciseType.RECALL, card, chinese, emptyList(), english)

    /** Blank out one content word and offer distractors taken from the same sentence family. */
    private fun buildCloze(card: ReviewCard, english: String): PreparedExercise? {
        val words = WORD.findAll(english).toList()
        // Prefer words long enough to be meaningful, and not the first word.
        val target = words.drop(1).filter { it.value.length >= 3 }.randomOrNull() ?: return null
        val blanked = english.replaceRange(target.range, "____")
        val correct = target.value
        // Distractors: other words from the same sentence (plausible, same topic) plus a
        // couple of common confusions.
        val pool = words.map { it.value }
            .filter { !it.equals(correct, ignoreCase = true) && it.length >= 3 }
            .distinctBy { it.lowercase() }
            .shuffled()
        val extras = listOf("not", "does", "been", "much", "very")
        val options = (pool + extras)
            .filter { !it.equals(correct, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(3)
        if (options.size < 3) return null
        val choices = (options + correct).shuffled().map { Choice(it, it.equals(correct, ignoreCase = true)) }
        return PreparedExercise(ExerciseType.CLOZE, card, blanked, choices, english)
    }

    /** Speak the sentence with TTS, then ask which of several texts was heard. */
    private fun buildListen(
        card: ReviewCard,
        english: String,
        chinese: String,
        others: List<ReviewCard>,
    ): PreparedExercise {
        val distractors = others
            .map { it.entry.corrected }
            .filter { it.isNotBlank() && it != english }
            .distinct()
            .shuffled()
            .take(2)
        if (distractors.isEmpty()) {
            // Without a distractor the question is trivial, so give two altered variants.
            val words = english.split(" ").toMutableList()
            val variants = mutableListOf<String>()
            if (words.size > 3) {
                val swapped = words.toMutableList().apply { removeAt(words.lastIndex - 1) }
                variants.add(swapped.joinToString(" "))
                val reordered = words.toMutableList().apply {
                    add(1, removeAt(lastIndex))
                }
                variants.add(reordered.joinToString(" "))
            }
            if (variants.isEmpty()) error("no variants")
            val choices = (variants + english).shuffled().map { Choice(it, it == english) }
            return PreparedExercise(ExerciseType.LISTEN, card, english, choices, english)
        }
        val choices = (distractors + english).shuffled().map { Choice(it, it == english) }
        return PreparedExercise(ExerciseType.LISTEN, card, english, choices, english)
    }

    /** Introduce one realistic error, then ask which variant is correct. */
    private fun buildFixError(card: ReviewCard, english: String): PreparedExercise? {
        val words = english.split(" ").toMutableList()
        if (words.size < 4) return null
        val brokenCandidates = mutableListOf<String>()

        // Drop an article: the single most common learner error.
        val articleIndex = words.indexOfFirst { it.lowercase() in setOf("a", "an", "the") }
        if (articleIndex > 0) {
            brokenCandidates.add(words.toMutableList().apply { removeAt(articleIndex) }.joinToString(" "))
        }
        // Third-person singular: drop the -s.
        val sIndex = words.indexOfFirst { it.endsWith("s") && it.length > 3 && !it.endsWith("ss") }
        if (sIndex >= 0) {
            brokenCandidates.add(words.toMutableList().apply {
                this[sIndex] = this[sIndex].dropLast(1)
            }.joinToString(" "))
        }
        // Past tense marker.
        val edIndex = words.indexOfFirst { it.endsWith("ed") && it.length > 4 }
        if (edIndex >= 0) {
            brokenCandidates.add(words.toMutableList().apply {
                this[edIndex] = this[edIndex].dropLast(2)
            }.joinToString(" "))
        }
        if (brokenCandidates.isEmpty()) return null

        val broken = brokenCandidates.random()
        val choices = (listOf(broken) + listOf(english)).shuffled()
            .map { Choice(it, it == english) }
        return PreparedExercise(ExerciseType.FIX_ERROR, card, broken, choices, english)
    }

    /** Show English, pick the matching Chinese among the user's other translations. */
    private fun buildMeaning(
        card: ReviewCard,
        english: String,
        chinese: String,
        others: List<ReviewCard>,
    ): PreparedExercise {
        // Distractors should be plausible, not obviously unrelated. Prefer translations of a
        // similar length, which is what makes the choice require actually reading the English.
        val pool = others
            .map { it.entry.translation }
            .filter { it.isNotBlank() && it != chinese }
            .distinct()
        val byLength = pool.sortedBy { kotlin.math.abs(it.length - chinese.length) }
        val distractors = byLength.take(2)
        val choices = (distractors + chinese).shuffled().map { Choice(it, it == chinese) }
        // Two options is still a fair question when the pool is thin.
        return PreparedExercise(ExerciseType.MEANING, card, english, choices, chinese)
    }
}

/**
 * Shuffled word bank for the recall exercise: which tiles exist, and which have been placed in
 * the answer line so far. View state, so it stays out of [PracticeSession].
 */
class TileState(val target: String) {
    private val words: List<String> =
        Regex("[A-Za-z0-9']+").findAll(target).map { it.value }.toList()

    /** Display order is shuffled; the index is the tile's identity once placed. */
    val tiles: List<String> = words.shuffled()

    /** Indices into [tiles], in the order the learner tapped them. */
    val placed = mutableListOf<Int>()

    val isComplete: Boolean get() = placed.size == tiles.size

    fun answer(): String = placed.joinToString(" ") { tiles[it] }
}
