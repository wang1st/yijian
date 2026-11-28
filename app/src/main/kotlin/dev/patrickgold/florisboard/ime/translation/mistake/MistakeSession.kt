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
 * How a session is built.
 *
 * The old queue was "everything that is due", which could be two items or forty. An unknown
 * length is a reason not to start, so the length is fixed and the contents are chosen to serve
 * three different purposes in order:
 *
 *   warm-up     points the learner has mostly got — starting on wins
 *   body        the points actually due, hardest first among them
 *   challenge   the best-known points, met with the hardest exercise type
 *   retry       everything answered wrong in this session, replayed until answered right
 *
 * The retry tail is the one mechanic worth taking from a course app: failing is not a dead end
 * and not a punishment, it is simply the rest of the session.
 */
object MistakeSession {

    const val WARM_UP = 3
    const val BODY = 7
    const val CHALLENGE = 2
    const val MAX_LENGTH = WARM_UP + BODY + CHALLENGE

    /** A slot in the session, with why it is there. */
    data class Slot(
        val item: PointWithExamples,
        val segment: Segment,
    )

    enum class Segment { WARM_UP, BODY, CHALLENGE }

    /**
     * Builds the session.
     *
     * Points with a strong recent record open the session; the hardest are left for the end.
     * A point never appears twice in the main run — repetition belongs to the retry tail,
     * where it serves a purpose.
     */
    fun build(due: List<PointWithExamples>, limit: Int = MAX_LENGTH): List<Slot> {
        if (due.isEmpty()) return emptyList()

        // Most comfortable first: high recent accuracy, long correct streak, high stage.
        val byComfort = due.sortedWith(
            compareByDescending<PointWithExamples> { it.point.correctStreak }
                .thenByDescending { it.point.recentAccuracy }
                .thenByDescending { it.point.stage }
        )

        val slots = mutableListOf<Slot>()
        val used = mutableSetOf<String>()

        fun take(n: Int, from: List<PointWithExamples>, segment: Segment) {
            for (item in from) {
                if (slots.size >= limit || n <= 0) return
                if (item.point.id in used) continue
                used.add(item.point.id)
                slots.add(Slot(item, segment))
                if (slots.count { it.segment == segment } >= n) return
            }
        }

        // Warm-up: comfortable points, but only ones that have been reviewed at least once —
        // a never-seen point cannot warm anything up.
        take(WARM_UP, byComfort.filter { it.point.stage >= 0 && it.point.correctStreak > 0 }, Segment.WARM_UP)

        // Challenge: the two hardest by stage, taken last in the session ordering.
        val hardest = due.sortedWith(
            compareByDescending<PointWithExamples> { it.point.stubborn }
                .thenByDescending { it.point.stage }
                .thenBy { it.point.recentAccuracy }
        ).filter { it.point.id !in used }
            .take(CHALLENGE)

        // Body: whatever is left, in the original due order (most urgent first). The cap
        // leaves room for the challenge slots at the end.
        take(BODY, due.filter { it.point.id !in used && it !in hardest }, Segment.BODY)
        for (item in hardest) {
            if (slots.size >= limit) break
            if (item.point.id in used) continue
            used.add(item.point.id)
            slots.add(Slot(item, Segment.CHALLENGE))
        }

        return slots
    }
}

/**
 * The retry tail of one session.
 *
 * Items answered wrong are appended back to the queue as they fail, so the tail grows while the
 * session runs and the learner only leaves when nothing is left to redo. A cap stops a point
 * that simply will not stick from trapping someone in an endless loop: after [MAX_ATTEMPTS]
 * appearances it is left for tomorrow, which is also what its schedule now says.
 */
class RetryQueue {
    private val pending = ArrayDeque<PointWithExamples>()
    private val attempts = mutableMapOf<String, Int>()
    private val failedOnce = mutableListOf<PointWithExamples>()

    /** Points that were answered wrong at least once, for the summary. */
    fun failures(): List<PointWithExamples> = failedOnce.toList()

    fun enqueue(item: PointWithExamples) {
        val n = (attempts[item.point.id] ?: 0) + 1
        attempts[item.point.id] = n
        if (n == 1) failedOnce.add(item)
        if (n <= MAX_ATTEMPTS) pending.addLast(item)
    }

    fun next(): PointWithExamples? = pending.removeFirstOrNull()

    fun isEmpty(): Boolean = pending.isEmpty()

    companion object {
        /** A point appears at most this many times in one session's retry tail. */
        const val MAX_ATTEMPTS = 3
    }
}
