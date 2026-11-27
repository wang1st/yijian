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
import dev.patrickgold.florisboard.ime.translation.PracticeEntry
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Self-assessment after seeing the diff. */
enum class Grade { FORGOT, HARD, GOOD }

/**
 * One reviewable sentence. Cards are derived from the practice log (deduplicated by the
 * corrected English) and carry Leitner-box scheduling state on top.
 */
data class ReviewCard(
    /** Stable identity: hash of the normalized corrected sentence. */
    val id: String,
    /** The most recent practice record for this sentence. */
    val entry: PracticeEntry,
    /** How many times this sentence was practiced in total. */
    val timesPracticed: Int,
    /** Leitner box 0..4, or -1 for a card that was never reviewed. */
    val box: Int,
    val dueMillis: Long,
    val reps: Int,
    val lapses: Int,
) {
    val isNew: Boolean get() = box < 0
}

/** Everything the review home screen needs in one load. */
data class ReviewSnapshot(
    val totalCards: Int,
    val dueCount: Int,
    val newCount: Int,
    val reviewedToday: Int,
    val goodToday: Int,
    val streakDays: Int,
    /** Cards answered correctly today, used for the "remembered" daily quest. */
    val rememberedToday: Int = goodToday,
    /** Accuracy over everything reviewed today, 0..100. */
    val accuracyToday: Int = 0,
    /**
     * XP: 10 per review plus a 5-point bonus for remembering it. Cheap to compute and
     * monotonic, so it gives the home screen a number worth growing.
     */
    val xpTotal: Int = 0,
    val xpToday: Int = 0,
    /** Reviews per day for the last 7 days, oldest first. */
    val lastSevenDays: List<Int> = emptyList(),
    /** Consecutive correct answers ending now, the streak that achievements hang off. */
    val correctRun: Int = 0,
)

/**
 * Spaced-repetition store for the review feature.
 *
 * Reads the append-only practice log written by [dev.patrickgold.florisboard.ime.translation.EnglishTeacher]
 * and keeps its own scheduling state in a separate JSON file, so the log format never changes.
 *
 * Scheduling (Leitner): boxes 0..4 map to intervals of 1/3/7/15/30 days.
 * - GOOD promotes one box, FORGOT resets to box 0 (due tomorrow), HARD stays in the box with
 *   half the interval (minimum one day).
 */
object ReviewStore {
    private val INTERVAL_DAYS = intArrayOf(1, 3, 7, 15, 30)
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Maximum cards in one practice session. */
    const val SESSION_LIMIT = 20

    private val mutex = Mutex()
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val practiceFile: File by lazy {
        File(FlorisApplication.getHack().filesDir, "yijian_practice.jsonl")
    }
    private val srsFile: File by lazy {
        File(FlorisApplication.getHack().filesDir, "yijian_srs.json")
    }

    private fun cardId(corrected: String): String =
        corrected.lowercase().replace(Regex("[^a-z0-9']+"), " ").trim().hashCode().toString(16)

    private fun readPracticeEntries(): List<PracticeEntry> {
        if (!practiceFile.exists()) return emptyList()
        return practiceFile.readLines().mapNotNull { line ->
            runCatching { PracticeEntry.fromJson(JSONObject(line)) }.getOrNull()
        }
    }

    /** Collapses the practice log into one card per distinct corrected sentence. */
    private fun buildCards(): List<ReviewCard> {
        val grouped = readPracticeEntries()
            .filter { it.corrected.isNotBlank() && it.translation.isNotBlank() }
            .groupBy { cardId(it.corrected) }
        val state = readSrs()
        return grouped.map { (id, entries) ->
            val latest = entries.maxBy { it.timestamp }
            val s = state.cards[id]
            ReviewCard(
                id = id,
                entry = latest,
                timesPracticed = entries.size,
                box = s?.box ?: -1,
                dueMillis = s?.due ?: 0L,
                reps = s?.reps ?: 0,
                lapses = s?.lapses ?: 0,
            )
        }
    }

    /** Cards due right now (overdue first), then never-reviewed cards (newest first). */
    suspend fun dueQueue(now: Long = System.currentTimeMillis()): List<ReviewCard> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val cards = buildCards()
                val due = cards.filter { !it.isNew && it.dueMillis <= now }.sortedBy { it.dueMillis }
                val fresh = cards.filter { it.isNew }.sortedByDescending { it.entry.timestamp }
                (due + fresh).take(SESSION_LIMIT)
            }
        }

    suspend fun snapshot(now: Long = System.currentTimeMillis()): ReviewSnapshot =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val cards = buildCards()
                val state = readSrs()
                val today = dayFormat.format(Date(now))
                val todayStats = state.days[today]
                val allDays = state.days.values
                val done = todayStats?.done ?: 0
                val good = todayStats?.good ?: 0
                // 10 XP per review, +5 for a remembered one.
                val xpFor = { d: DayStats? -> (d?.done ?: 0) * 10 + (d?.good ?: 0) * 5 }
                val week = (6 downTo 0).map { back ->
                    val day = dayFormat.format(Date(now - back * DAY_MS))
                    state.days[day]?.done ?: 0
                }
                ReviewSnapshot(
                    totalCards = cards.size,
                    dueCount = cards.count { !it.isNew && it.dueMillis <= now },
                    newCount = cards.count { it.isNew },
                    reviewedToday = done,
                    goodToday = good,
                    streakDays = computeStreak(state, now),
                    accuracyToday = if (done == 0) 0 else (good * 100 / done),
                    xpTotal = allDays.sumOf { xpFor(it) },
                    xpToday = xpFor(todayStats),
                    lastSevenDays = week,
                    correctRun = state.correctRun,
                )
            }
        }

    /** All distinct sentences, newest first, for the history browser. */
    suspend fun allCards(): List<ReviewCard> = withContext(Dispatchers.IO) {
        mutex.withLock { buildCards().sortedByDescending { it.entry.timestamp } }
    }

    suspend fun grade(cardId: String, grade: Grade, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val state = readSrs()
                val old = state.cards[cardId] ?: CardState(box = 0, due = 0, reps = 0, lapses = 0)
                val startBox = if (old.reps == 0 && old.due == 0L) 0 else old.box
                val (newBox, due) = when (grade) {
                    Grade.GOOD -> {
                        val b = (startBox + 1).coerceAtMost(INTERVAL_DAYS.lastIndex)
                        b to now + INTERVAL_DAYS[b] * DAY_MS
                    }
                    Grade.HARD -> {
                        val half = (INTERVAL_DAYS[startBox] / 2).coerceAtLeast(1)
                        startBox to now + half * DAY_MS
                    }
                    Grade.FORGOT -> 0 to now + DAY_MS
                }
                state.cards[cardId] = old.copy(
                    box = newBox,
                    due = due,
                    reps = old.reps + 1,
                    lapses = old.lapses + if (grade == Grade.FORGOT) 1 else 0,
                )
                state.correctRun = if (grade == Grade.GOOD) state.correctRun + 1 else 0
                val today = dayFormat.format(Date(now))
                val stats = state.days[today] ?: DayStats()
                state.days[today] = stats.copy(
                    done = stats.done + 1,
                    good = stats.good + if (grade == Grade.GOOD) 1 else 0,
                )
                writeSrs(state)
            }
        }

    /**
     * Adds model-generated sentences to the review pool.
     *
     * They are written to the practice log as ordinary entries (original == corrected, no
     * corrections), which keeps one storage path and means the existing card derivation picks
     * them up. The Chinese side is stored as the translation, which is what the exercise prompts
     * with.
     *
     * @return how many cards were added.
     */
    suspend fun addGeneratedCards(cards: List<GeneratedSentence>): Int = withContext(Dispatchers.IO) {
        if (cards.isEmpty()) return@withContext 0
        mutex.withLock {
            val existing = runCatching { buildCards().map { it.id }.toSet() }.getOrDefault(emptySet())
            var added = 0
            runCatching {
                practiceFile.parentFile?.mkdirs()
                practiceFile.appendText(buildString {
                    for (c in cards) {
                        if (cardId(c.english) in existing) continue
                        val entry = PracticeEntry(
                            timestamp = System.currentTimeMillis(),
                            original = c.english,
                            corrected = c.english,
                            translation = c.chinese,
                            corrections = emptyList(),
                            note = c.focus,
                        )
                        append(entry.toJson().toString())
                        append("\n")
                        added++
                    }
                })
            }.onFailure { flogError { "yijian review: failed to add generated cards: ${it.message}" } }
            added
        }
    }

    /** A generated sentence waiting to be stored. */
    data class GeneratedSentence(val english: String, val chinese: String, val focus: String)

    // ---- persistence -------------------------------------------------------------

    private data class CardState(val box: Int, val due: Long, val reps: Int, val lapses: Int)
    private data class DayStats(val done: Int = 0, val good: Int = 0)
    private class SrsState(
        val cards: MutableMap<String, CardState> = mutableMapOf(),
        val days: MutableMap<String, DayStats> = mutableMapOf(),
        /** Consecutive remembered answers; reset by a miss. */
        var correctRun: Int = 0,
    )

    private fun readSrs(): SrsState {
        val state = SrsState()
        if (!srsFile.exists()) return state
        runCatching {
            val root = JSONObject(srsFile.readText())
            state.correctRun = root.optInt("correctRun", 0)
            val cards = root.optJSONObject("cards")
            if (cards != null) {
                for (key in cards.keys()) {
                    val o = cards.optJSONObject(key) ?: continue
                    state.cards[key] = CardState(
                        box = o.optInt("box", 0),
                        due = o.optLong("due", 0),
                        reps = o.optInt("reps", 0),
                        lapses = o.optInt("lapses", 0),
                    )
                }
            }
            val days = root.optJSONObject("days")
            if (days != null) {
                for (key in days.keys()) {
                    val o = days.optJSONObject(key) ?: continue
                    state.days[key] = DayStats(o.optInt("done", 0), o.optInt("good", 0))
                }
            }
        }.onFailure { flogError { "yijian review: failed to read srs state: ${it.message}" } }
        return state
    }

    private fun writeSrs(state: SrsState) {
        runCatching {
            val cards = JSONObject()
            for ((id, s) in state.cards) {
                cards.put(id, JSONObject().apply {
                    put("box", s.box)
                    put("due", s.due)
                    put("reps", s.reps)
                    put("lapses", s.lapses)
                })
            }
            val days = JSONObject()
            for ((day, s) in state.days) {
                days.put(day, JSONObject().apply {
                    put("done", s.done)
                    put("good", s.good)
                })
            }
            val content = JSONObject().apply {
                put("cards", cards)
                put("days", days)
                put("correctRun", state.correctRun)
            }.toString()
            dev.patrickgold.florisboard.ime.translation.atomicRewriteFile(srsFile, content)
        }.onFailure { flogError { "yijian review: failed to write srs state: ${it.message}" } }
    }

    /** Consecutive days (ending today or yesterday) with at least one review. */
    private fun computeStreak(state: SrsState, now: Long): Int {
        var streak = 0
        var cursor = now
        // A streak is still alive if today has no reviews yet but yesterday does.
        if (state.days[dayFormat.format(Date(cursor))]?.done ?: 0 == 0) {
            cursor -= DAY_MS
        }
        while ((state.days[dayFormat.format(Date(cursor))]?.done ?: 0) > 0) {
            streak++
            cursor -= DAY_MS
        }
        return streak
    }
}
