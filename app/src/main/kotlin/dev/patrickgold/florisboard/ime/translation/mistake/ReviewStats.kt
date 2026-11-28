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

import dev.patrickgold.florisboard.FlorisApplication
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

/**
 * The one set of numbers the app shows.
 *
 * There used to be two: the card system counted sentences reviewed, the mistake system counted
 * points failed. Two counters measuring different things meant neither could be trusted — the
 * review tab said "21 cards today, 100% correct" while the mistake screens showed points still
 * failing, and both were technically true.
 *
 * Now everything derives from mistake points, which is the only unit that corresponds to
 * "am I getting this right".
 */
data class ReviewStats(
    /** Mistake points that need work: not mastered. */
    val openPoints: Int,
    /** Points at least five correct in a row. */
    val masteredPoints: Int,
    val totalPoints: Int,
    /** Points reviewed today. */
    val reviewedToday: Int,
    /** Of those, how many were answered correctly at least once. */
    val correctToday: Int,
    /** Consecutive days with at least one review. */
    val streakDays: Int,
    /** Points still failing repeatedly — the ones that matter most. */
    val stubborn: List<MistakePoint>,
    /** Idiomatic expressions collected from the learner's own sentences. */
    val expressions: Int,
) {
    val accuracyToday: Int
        get() = if (reviewedToday == 0) 0 else correctToday * 100 / reviewedToday
}

/**
 * Computes [ReviewStats] from the mistake store and the expression corpus.
 *
 * Daily counts live in a small file rather than being derived from point history, because a
 * point's history keeps outcomes but not the date of each one — the window is deliberately
 * about "recent form", not a log.
 */
object ReviewStatsSource {
    private val mutex = Mutex()
    private const val DAY = 24L * 60 * 60 * 1000

    private val dayFile: File by lazy {
        File(FlorisApplication.getHack().filesDir, "yijian_review_days.json")
    }
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private class DayStat(var reviewed: Int = 0, var correct: Int = 0)

    private fun readDays(): MutableMap<String, DayStat> {
        val map = mutableMapOf<String, DayStat>()
        if (!dayFile.exists()) return map
        runCatching {
            val root = JSONObject(dayFile.readText())
            root.keys().forEach { key ->
                val o = root.optJSONObject(key) ?: return@forEach
                map[key] = DayStat(o.optInt("n"), o.optInt("ok"))
            }
        }.onFailure { flogError { "yijian stats: read days failed: ${it.message}" } }
        return map
    }

    private fun writeDays(map: Map<String, DayStat>) {
        runCatching {
            val root = JSONObject()
            map.forEach { (k, v) ->
                root.put(k, JSONObject().apply {
                    put("n", v.reviewed)
                    put("ok", v.correct)
                })
            }
            dev.patrickgold.florisboard.ime.translation.atomicRewriteFile(dayFile, root.toString())
        }.onFailure { flogError { "yijian stats: write days failed: ${it.message}" } }
    }

    /** Called once per answered point, so the daily figures stay exact. */
    suspend fun recordReview(correct: Boolean, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val days = readDays()
                val key = dayFormat.format(Date(now))
                val stat = days.getOrPut(key) { DayStat() }
                stat.reviewed += 1
                if (correct) stat.correct += 1
                writeDays(days)
            }
        }

    suspend fun snapshot(now: Long = System.currentTimeMillis()): ReviewStats =
        withContext(Dispatchers.IO) {
            val points = runCatching { MistakeStore.allPoints() }.getOrDefault(emptyList())
            val expressions = runCatching { ExpressionStore.all().size }.getOrDefault(0)
            mutex.withLock {
                val days = readDays()
                val today = days[dayFormat.format(Date(now))]
                ReviewStats(
                    openPoints = points.count { !it.mastered },
                    masteredPoints = points.count { it.mastered },
                    totalPoints = points.size,
                    reviewedToday = today?.reviewed ?: 0,
                    correctToday = today?.correct ?: 0,
                    streakDays = computeStreak(days, now),
                    stubborn = points.filter { it.stubborn && !it.mastered }
                        .sortedByDescending { it.occurrences },
                    expressions = expressions,
                )
            }
        }

    /** Consecutive days with at least one review, counting back from today or yesterday. */
    private fun computeStreak(days: Map<String, DayStat>, now: Long): Int {
        var streak = 0
        var cursor = now
        if ((days[dayFormat.format(Date(cursor))]?.reviewed ?: 0) == 0) cursor -= DAY
        while ((days[dayFormat.format(Date(cursor))]?.reviewed ?: 0) > 0) {
            streak++
            cursor -= DAY
        }
        return streak
    }
}
