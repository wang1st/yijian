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
import dev.patrickgold.florisboard.ime.translation.EnglishTeacher
import dev.patrickgold.florisboard.ime.translation.atomicRewriteFile
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A point together with the sentences it was failed in, newest first. */
data class PointWithExamples(
    val point: MistakePoint,
    val examples: List<MistakeInstance>,
)

/**
 * Long-term store of mistake points and the sentences behind them.
 *
 * Two sources feed it:
 *  - the practice log, converted once through the model's grouping (see [MistakeGrouper])
 *  - live corrections, appended as the learner types
 *
 * Scheduling is a Leitner ladder with two deliberate departures from a fixed interval table:
 *
 *  - A point whose recent window is nearly all correct jumps to the top stage instead of
 *    climbing one rung per review. Waiting four sessions to admit that something is learned
 *    wastes the learner's time.
 *  - A stubborn point (repeatedly failed, failing again lately) keeps a short interval even
 *    after a correct answer. One right answer after five wrong ones is not mastery.
 *
 * Intervals are 1 / 3 / 7 / 16 / 35 days — closer to the FSRS curve than the old
 * 1/3/7/15/30 while staying a table a person can reason about.
 */
object MistakeStore {
    private val mutex = Mutex()
    private const val DAY = 24L * 60 * 60 * 1000
    private val INTERVALS = longArrayOf(1, 3, 7, 16, 35)

    private val pointsFile: File by lazy {
        File(FlorisApplication.getHack().filesDir, "yijian_mistake_points.json")
    }
    private val instancesFile: File by lazy {
        File(FlorisApplication.getHack().filesDir, "yijian_mistake_instances.json")
    }

    // ---- reading --------------------------------------------------------------------

    fun readPoints(): MutableMap<String, MistakePoint> {
        val map = mutableMapOf<String, MistakePoint>()
        if (!pointsFile.exists()) return map
        runCatching {
            val arr = JSONArray(pointsFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = pointFromJson(o)
                map[p.id] = p
            }
        }.onFailure { flogError { "yijian mistakes: read points failed: ${it.message}" } }
        return map
    }

    fun readInstances(): MutableList<MistakeInstance> {
        val list = mutableListOf<MistakeInstance>()
        if (!instancesFile.exists()) return list
        runCatching {
            val arr = JSONArray(instancesFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    MistakeInstance(
                        pointId = o.optString("pointId"),
                        sourceSentence = o.optString("sentence"),
                        sourceTranslation = o.optString("translation"),
                        wrong = o.optString("wrong"),
                        right = o.optString("right"),
                        why = o.optString("why"),
                        timestamp = o.optLong("ts"),
                    )
                )
            }
        }.onFailure { flogError { "yijian mistakes: read instances failed: ${it.message}" } }
        return list
    }

    // ---- writing --------------------------------------------------------------------

    fun writePoints(points: Map<String, MistakePoint>) {
        runCatching {
            val arr = JSONArray()
            points.values
                .sortedByDescending { it.lastFailedAt }
                .forEach { arr.put(pointToJson(it)) }
            atomicRewriteFile(pointsFile, arr.toString())
        }.onFailure { flogError { "yijian mistakes: write points failed: ${it.message}" } }
    }

    fun writeInstances(instances: List<MistakeInstance>) {
        runCatching {
            val arr = JSONArray()
            // Keep the newest few hundred: enough for review context, bounded on disk.
            instances.sortedByDescending { it.timestamp }.take(600).forEach { ins ->
                arr.put(JSONObject().apply {
                    put("pointId", ins.pointId)
                    put("sentence", ins.sourceSentence)
                    put("translation", ins.sourceTranslation)
                    put("wrong", ins.wrong)
                    put("right", ins.right)
                    put("why", ins.why)
                    put("ts", ins.timestamp)
                })
            }
            atomicRewriteFile(instancesFile, arr.toString())
        }.onFailure { flogError { "yijian mistakes: write instances failed: ${it.message}" } }
    }

    // ---- api ------------------------------------------------------------------------

    /**
     * Converts the practice log into points, once.
     *
     * The log already carries every correction ever recorded; grouping them needs the model,
     * so this runs on first launch (and when explicitly refreshed) rather than per correction.
     * Returns the number of points created.
     */
    suspend fun importFromPracticeLog(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = EnglishTeacher.readEntriesStatic()
            if (entries.isEmpty()) return@withLock 0
            if (!force && pointsFile.exists() && readPoints().isNotEmpty()) return@withLock 0

            val corrections = entries.flatMap { e ->
                e.corrections.map { c ->
                    MistakeGrouper.Sample(
                        sentence = e.original.ifBlank { e.corrected },
                        translation = e.translation,
                        wrong = c.wrong,
                        right = c.right,
                        why = c.why,
                        tag = c.tag,
                        timestamp = e.timestamp,
                    )
                }
            }
            if (corrections.isEmpty()) return@withLock 0

            val grouped = MistakeGrouper.group(corrections)
            if (grouped.isEmpty()) return@withLock 0

            val points = readPoints()
            val instances = readInstances()
            var created = 0

            for (g in grouped) {
                val existing = points[g.id]
                if (existing == null) created++
                val allSamples = g.sampleIndices.mapNotNull { corrections.getOrNull(it) }
                val latest = allSamples.maxByOrNull { it.timestamp }
                points[g.id] = (existing ?: MistakePoint(
                    id = g.id,
                    kind = g.kind,
                    label = g.label,
                    correctForm = g.correctForm,
                    explanation = g.explanation,
                    wrongForms = emptyList(),
                    occurrences = 0,
                    history = emptyList(),
                    stage = -1,
                    dueAt = 0L,
                    lastFailedAt = 0L,
                    lastReviewedAt = 0L,
                )).copy(
                    label = g.label,
                    explanation = g.explanation.ifBlank { existing?.explanation ?: "" },
                    correctForm = g.correctForm.ifBlank { existing?.correctForm ?: "" },
                    wrongForms = (allSamples.map { it.wrong } + (existing?.wrongForms ?: emptyList()))
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(6),
                    occurrences = (existing?.occurrences ?: 0) + g.newFailures,
                    lastFailedAt = maxOf(existing?.lastFailedAt ?: 0L, latest?.timestamp ?: 0L),
                )
                // Keep the sentences, newest first, for context while reviewing.
                for (sample in allSamples.sortedByDescending { it.timestamp }) {
                    val already = instances.any {
                        it.pointId == g.id && it.wrong == sample.wrong && it.timestamp == sample.timestamp
                    }
                    if (!already) {
                        instances.add(
                            MistakeInstance(
                                pointId = g.id,
                                sourceSentence = sample.sentence,
                                sourceTranslation = sample.translation,
                                wrong = sample.wrong,
                                right = sample.right,
                                why = sample.why,
                                timestamp = sample.timestamp,
                            )
                        )
                    }
                }
            }

            writePoints(points)
            writeInstances(instances)
            created
        }
    }

    /** Records one review outcome for a point and reschedules it. */
    suspend fun recordReview(pointId: String, correct: Boolean, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val points = readPoints()
                val p = points[pointId] ?: return@withLock
                val history = (p.history + correct).takeLast(MistakePoint.HISTORY_WINDOW)
                val updated = p.copy(history = history, lastReviewedAt = now)
                val next = reschedule(updated, correct, now)
                points[pointId] = next
                writePoints(points)
            }
        }

    /**
     * The scheduling decision, in one place so it can be reasoned about and tested.
     */
    fun reschedule(p: MistakePoint, correct: Boolean, now: Long): MistakePoint {
        val stage = p.stage.coerceAtLeast(0)
        val nextStage: Int
        val intervalDays: Long

        if (!correct) {
            // A failure resets the ladder but not the history: the record of past success is
            // what distinguishes a one-off slip from a point that keeps slipping.
            nextStage = 0
            intervalDays = 1
        } else {
            val recent = p.history.takeLast(MistakePoint.HISTORY_WINDOW)
            val strong = recent.size >= 4 && recent.count { it } * 100 / recent.size >= 90
            nextStage = when {
                strong -> INTERVALS.lastIndex
                // A stubborn point earns its way out slowly.
                p.stubborn -> stage.coerceAtMost(1) + 1
                else -> (stage + 1).coerceAtMost(INTERVALS.lastIndex)
            }
            intervalDays = INTERVALS[nextStage.coerceIn(0, INTERVALS.lastIndex)]
        }

        return p.copy(
            stage = nextStage,
            dueAt = now + intervalDays * DAY,
        )
    }

    /** Points due now, most urgent first, with their example sentences. */
    suspend fun duePoints(now: Long = System.currentTimeMillis(), limit: Int = 14): List<PointWithExamples> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val points = readPoints()
                val instances = readInstances().groupBy { it.pointId }
                points.values
                    .filter { it.stage >= 0 || it.occurrences > 0 }
                    .sortedWith(
                        // Never-reviewed first, then stubborn, then by how overdue.
                        compareBy<MistakePoint> { it.stage >= 0 }
                            .thenByDescending { it.stubborn }
                            .thenBy { it.dueAt }
                    )
                    .take(limit)
                    .map { p ->
                        PointWithExamples(
                            point = p,
                            examples = instances[p.id].orEmpty()
                                .sortedByDescending { it.timestamp }
                                .take(3),
                        )
                    }
            }
        }

    /** All points, for the progress screen. */
    suspend fun allPoints(): List<MistakePoint> = withContext(Dispatchers.IO) {
        mutex.withLock { readPoints().values.sortedByDescending { it.lastFailedAt } }
    }

    // ---- json -----------------------------------------------------------------------

    private fun pointToJson(p: MistakePoint): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("kind", p.kind)
        put("label", p.label)
        put("correctForm", p.correctForm)
        put("explanation", p.explanation)
        put("wrongForms", JSONArray().apply { p.wrongForms.forEach { put(it) } })
        put("occurrences", p.occurrences)
        put("history", JSONArray().apply { p.history.forEach { put(it) } })
        put("stage", p.stage)
        put("dueAt", p.dueAt)
        put("lastFailedAt", p.lastFailedAt)
        put("lastReviewedAt", p.lastReviewedAt)
    }

    private fun pointFromJson(o: JSONObject): MistakePoint {
        val wrong = mutableListOf<String>()
        o.optJSONArray("wrongForms")?.let { arr ->
            for (i in 0 until arr.length()) wrong.add(arr.optString(i))
        }
        val history = mutableListOf<Boolean>()
        o.optJSONArray("history")?.let { arr ->
            for (i in 0 until arr.length()) history.add(arr.optBoolean(i))
        }
        return MistakePoint(
            id = o.optString("id"),
            kind = o.optString("kind"),
            label = o.optString("label"),
            correctForm = o.optString("correctForm"),
            explanation = o.optString("explanation"),
            wrongForms = wrong,
            occurrences = o.optInt("occurrences"),
            history = history,
            stage = o.optInt("stage", -1),
            dueAt = o.optLong("dueAt"),
            lastFailedAt = o.optLong("lastFailedAt"),
            lastReviewedAt = o.optLong("lastReviewedAt"),
        )
    }
}
