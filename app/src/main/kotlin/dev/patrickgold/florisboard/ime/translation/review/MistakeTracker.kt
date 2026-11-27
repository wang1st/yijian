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

import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.ime.translation.EnglishTeacher
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Progress on one kind of mistake (拼写 / 语法 / 用词 / 表达). */
data class MistakeKindStat(
    val kind: String,
    /** Total errors of this kind ever recorded. */
    val totalErrors: Int,
    /** Consecutive correct reviews on cards whose mistake was of this kind. */
    val correctRun: Int,
    /** Reviews since the last miss, used to show the trend. */
    val reviewsSinceMiss: Int,
) {
    /** Five correct in a row is treated as the point being under control. */
    val mastered: Boolean get() = correctRun >= 5
}

/** The learner's mistake profile: which kinds they keep making and which are receding. */
data class MistakeProfile(val kinds: List<MistakeKindStat>) {
    companion object {
        suspend fun load(): MistakeProfile = withContext(Dispatchers.IO) {
            MistakeTracker.snapshot()
        }
    }
}

/**
 * Tracks progress per *kind of mistake* rather than per card.
 *
 * This is the number that answers "am I actually getting better": a card can keep coming back
 * forever, but a kind that has been answered correctly five times in a row is under control and
 * drops off the list. Without this the app could only show activity (XP, streak), never progress.
 *
 * The kind for a card is taken from the first correction recorded on it, which is the same rule
 * the exercise generator uses to decide what to drill.
 */
object MistakeTracker {
    private val mutex = Mutex()

    private val stateFile: File by lazy {
        File(FlorisApplication.getHack().filesDir, "yijian_mistakes.json")
    }

    private const val MASTERY_RUN = 5

    private class KindState(
        var correctRun: Int = 0,
        var reviewsSinceMiss: Int = 0,
        var lastOutcome: Boolean = false,
    )

    private fun read(): MutableMap<String, KindState> {
        val map = mutableMapOf<String, KindState>()
        if (!stateFile.exists()) return map
        runCatching {
            val root = JSONObject(stateFile.readText())
            for (kind in root.keys()) {
                val o = root.optJSONObject(kind) ?: continue
                map[kind] = KindState(
                    correctRun = o.optInt("run", 0),
                    reviewsSinceMiss = o.optInt("since", 0),
                    lastOutcome = o.optBoolean("last", false),
                )
            }
        }.onFailure { flogError { "yijian mistakes: read failed: ${it.message}" } }
        return map
    }

    private fun write(map: Map<String, KindState>) {
        runCatching {
            val root = JSONObject()
            for ((kind, s) in map) {
                root.put(kind, JSONObject().apply {
                    put("run", s.correctRun)
                    put("since", s.reviewsSinceMiss)
                    put("last", s.lastOutcome)
                })
            }
            dev.patrickgold.florisboard.ime.translation.atomicRewriteFile(stateFile, root.toString())
        }.onFailure { flogError { "yijian mistakes: write failed: ${it.message}" } }
    }

    /** Records the outcome of one review for a card whose mistake kind is [kind]. */
    suspend fun record(kind: String, correct: Boolean) = withContext(Dispatchers.IO) {
        if (kind.isBlank()) return@withContext
        mutex.withLock {
            val map = read()
            val s = map.getOrPut(kind) { KindState() }
            if (correct) {
                s.correctRun += 1
                s.reviewsSinceMiss += 1
            } else {
                s.correctRun = 0
                s.reviewsSinceMiss = 0
            }
            s.lastOutcome = correct
            write(map)
        }
    }

    /**
     * Builds the profile: total errors per kind come from the practice log, the streak comes
     * from the tracker.
     */
    suspend fun snapshot(): MistakeProfile = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tracked = read()
            val totals = runCatching {
                EnglishTeacher.readEntriesStatic()
                    .flatMap { it.corrections }
                    .map { it.tag.ifBlank { "表达" } }
                    .groupingBy { it }
                    .eachCount()
            }.getOrDefault(emptyMap())

            val allKinds = (totals.keys + tracked.keys).toSet()
            val kinds = allKinds.map { kind ->
                MistakeKindStat(
                    kind = kind,
                    totalErrors = totals[kind] ?: 0,
                    correctRun = tracked[kind]?.correctRun ?: 0,
                    reviewsSinceMiss = tracked[kind]?.reviewsSinceMiss ?: 0,
                )
            }.sortedWith(
                // Unmastered kinds first, then the ones with the most errors.
                compareBy<MistakeKindStat> { it.mastered }
                    .thenByDescending { it.totalErrors }
            )
            MistakeProfile(kinds)
        }
    }

    /** Kind of the mistake a card is about, or "" when the sentence had none. */
    fun kindOf(card: ReviewCard): String =
        card.entry.corrections.firstOrNull()?.tag.orEmpty()
}
