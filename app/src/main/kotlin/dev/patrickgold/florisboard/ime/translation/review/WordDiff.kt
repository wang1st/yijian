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

package dev.patrickgold.florisboard.ime.translation.review

/** One word of the target sentence with whether the answer covered it. */
data class TargetWord(val text: String, val matched: Boolean)

data class DiffResult(
    /** 0..100, Dice similarity over word tokens. */
    val similarity: Int,
    /** The target sentence, word by word, in order. */
    val targetWords: List<TargetWord>,
    /** Words the answer had that the target does not. */
    val extraWords: List<String>,
) {
    /** Suggested self-grade from the similarity. */
    val suggestedGrade: Grade
        get() = when {
            similarity >= 90 -> Grade.GOOD
            similarity >= 60 -> Grade.HARD
            else -> Grade.FORGOT
        }
}

/**
 * Local, API-free answer grading: word-level LCS alignment, ignoring case and punctuation.
 * "Thank you, Doctor Gu." and "thank you doctor gu" are a perfect match.
 */
object WordDiff {
    private val WORD = Regex("[A-Za-z0-9']+")

    private fun tokens(s: String): List<String> = WORD.findAll(s).map { it.value }.toList()

    fun grade(target: String, answer: String): DiffResult {
        val t = tokens(target)
        val a = tokens(answer)
        if (t.isEmpty()) return DiffResult(0, emptyList(), a)
        val tl = t.map { it.lowercase() }
        val al = a.map { it.lowercase() }

        // Classic LCS table over the two token lists.
        val dp = Array(tl.size + 1) { IntArray(al.size + 1) }
        for (i in tl.indices) {
            for (j in al.indices) {
                dp[i + 1][j + 1] = if (tl[i] == al[j]) dp[i][j] + 1
                else maxOf(dp[i][j + 1], dp[i + 1][j])
            }
        }
        // Walk back to find which tokens are part of the LCS.
        val tMatched = BooleanArray(tl.size)
        val aMatched = BooleanArray(al.size)
        var i = tl.size
        var j = al.size
        while (i > 0 && j > 0) {
            when {
                tl[i - 1] == al[j - 1] -> {
                    tMatched[i - 1] = true
                    aMatched[j - 1] = true
                    i--; j--
                }
                dp[i - 1][j] >= dp[i][j - 1] -> i--
                else -> j--
            }
        }
        val lcs = dp[tl.size][al.size]
        val similarity = ((2.0 * lcs) / (t.size + a.size) * 100).toInt().coerceIn(0, 100)
        return DiffResult(
            similarity = similarity,
            targetWords = t.mapIndexed { idx, w -> TargetWord(w, tMatched[idx]) },
            extraWords = a.filterIndexed { idx, _ -> !aMatched[idx] },
        )
    }
}
