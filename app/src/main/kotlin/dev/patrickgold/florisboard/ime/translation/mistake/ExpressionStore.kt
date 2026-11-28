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

/**
 * One thing a native speaker would say, harvested from the learner's own translations.
 *
 * The goal is to sound like a native speaker, not merely to stop being wrong. Grammar
 * correction gets you out of the red; phrasing is what moves you forward. Every time the
 * model offers a more natural way to say what the learner already expressed correctly, that
 * sentence is a free, real, usable expression — and it belongs to the learner, because they
 * asked for that meaning.
 *
 * This is the difference between a phrase book and a corpus: a phrase book is chosen by
 * someone else, this is chosen by what the learner actually wanted to say.
 */
data class Expression(
    /** What the learner wrote. */
    val original: String,
    /** The more idiomatic version, when the model supplied one; may equal original. */
    val better: String,
    /** The Chinese they were expressing. */
    val translation: String,
    /** The model's sentence about why the better version is better. */
    val why: String,
    /** Short usage note if one can be distilled: register, collocation, situation. */
    val usage: String,
    val timestamp: Long,
) {
    /** True when the model actually offered a different phrasing. */
    val isUpgrade: Boolean
        get() = better.isNotBlank() && better.trim() != original.trim()
}

/**
 * 我的表达 — the learner's own corpus of idiomatic English.
 *
 * Read-mostly: it is filled from the practice log at import time and appended to as new
 * translations arrive. Reviewing an expression is "produce this meaning the way a native
 * speaker would", which is a different question from the mistake drill's "what was wrong".
 */
object ExpressionStore {
    private val mutex = Mutex()
    private val DAY = 24L * 60 * 60 * 1000

    private val file: File by lazy {
        File(FlorisApplication.getHack().filesDir, "yijian_expressions.json")
    }
    /** Which expressions have been practised, keyed by normalized text. */
    private val practiceFile: File by lazy {
        File(FlorisApplication.getHack().filesDir, "yijian_expression_practice.json")
    }

    private fun key(e: Expression): String = e.better.trim().lowercase()

    // ---- import ---------------------------------------------------------------------

    /**
     * Harvests expressions from the practice log.
     *
     * Two sources produce one: a note on a clean sentence ("the sentence is fine, a native
     * speaker would say…"), and any entry where the corrected form differs from what was
     * written. Both carry a real meaning the learner had.
     */
    suspend fun importFromPracticeLog(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = read().toMutableList()
            if (force) {
                // Re-read everything; keep practice state.
                existing.clear()
            }
            val seen = existing.map { key(it) }.toMutableSet()

            val entries = EnglishTeacher.readEntriesStatic()
            var added = 0
            for (e in entries) {
                val original = e.original.ifBlank { e.corrected }
                val better = e.corrected
                val upgrade = better.trim() != original.trim()
                val hasNote = e.note.isNotBlank()
                // Only entries where there is something to say beyond "correct".
                if (!upgrade && !hasNote) continue
                if (original.isBlank() || e.translation.isBlank()) continue

                val candidate = Expression(
                    original = original,
                    better = if (better.isBlank()) original else better,
                    translation = e.translation,
                    why = e.note,
                    usage = "",
                    timestamp = e.timestamp,
                )
                if (seen.add(key(candidate))) {
                    existing.add(candidate)
                    added++
                }
            }

            if (added > 0 || force) write(existing)
            added
        }
    }

    // ---- read -----------------------------------------------------------------------

    fun read(): List<Expression> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    Expression(
                        original = o.optString("original"),
                        better = o.optString("better"),
                        translation = o.optString("translation"),
                        why = o.optString("why"),
                        usage = o.optString("usage"),
                        timestamp = o.optLong("ts"),
                    )
                }
            }
        }.onFailure { flogError { "yijian expressions: read failed: ${it.message}" } }
            .getOrDefault(emptyList())
    }

    /** Newest first. */
    suspend fun all(): List<Expression> = withContext(Dispatchers.IO) {
        mutex.withLock { read().sortedByDescending { it.timestamp } }
    }

    /** The best material for practise: upgrades the learner has not drilled yet. */
    suspend fun reviewQueue(limit: Int = 10): List<Expression> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val practised = readPractice()
            read()
                .filter { it.isUpgrade }
                .sortedWith(
                    // Least-practised first, then newest: the goal is coverage, not mastery.
                    compareBy<Expression> { practised[it.better.lowercase()] ?: 0 }
                        .thenByDescending { it.timestamp }
                )
                .take(limit)
        }
    }

    // ---- write ----------------------------------------------------------------------

    fun write(expressions: List<Expression>) {
        runCatching {
            val arr = JSONArray()
            expressions.sortedByDescending { it.timestamp }.take(1000).forEach { e ->
                arr.put(JSONObject().apply {
                    put("original", e.original)
                    put("better", e.better)
                    put("translation", e.translation)
                    put("why", e.why)
                    put("usage", e.usage)
                    put("ts", e.timestamp)
                })
            }
            atomicRewriteFile(file, arr.toString())
        }.onFailure { flogError { "yijian expressions: write failed: ${it.message}" } }
    }

    /** Counts how many times an expression has been practised, for the queue ordering. */
    suspend fun recordPractice(expression: Expression) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val map = readPractice().toMutableMap()
            val k = expression.better.lowercase()
            map[k] = (map[k] ?: 0) + 1
            runCatching {
                val root = JSONObject()
                map.forEach { (key, value) -> root.put(key, value) }
                atomicRewriteFile(practiceFile, root.toString())
            }.onFailure { flogError { "yijian expressions: practice write failed: ${it.message}" } }
        }
    }

    private fun readPractice(): Map<String, Int> {
        if (!practiceFile.exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(practiceFile.readText())
            root.keys().asSequence().associateWith { root.optInt(it, 0) }
        }.getOrDefault(emptyMap())
    }
}
