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

import dev.patrickgold.florisboard.ime.translation.review.ReviewAi
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.security.MessageDigest

/**
 * Groups scattered corrections into a small set of recurring points.
 *
 * A learner does not fail "I very like this movie" — they fail *very modifying a verb*, and
 * they fail it again in the next sentence with different words. Grouping is what turns a list
 * of one-off fixes into a picture of what to work on, so it is done by the model rather than by
 * string matching: the wrong fragments look nothing alike, and only meaning connects them.
 *
 * When the model is unavailable the samples are grouped by their own shape instead, so the
 * feature degrades to "one point per distinct mistake" rather than failing.
 */
object MistakeGrouper {

    /** One correction lifted from the practice log. */
    data class Sample(
        val sentence: String,
        val translation: String,
        val wrong: String,
        val right: String,
        val why: String,
        val tag: String,
        val timestamp: Long,
    )

    /** A group produced by the model: one point, and which samples belong to it. */
    data class Group(
        val id: String,
        val kind: String,
        val label: String,
        val correctForm: String,
        val explanation: String,
        /** Indices into the input list. */
        val sampleIndices: List<Int>,
        /** How many of those samples were not already counted for this point. */
        val newFailures: Int,
    )

    /** Groups samples, calling the model; falls back to shape-based grouping on failure. */
    suspend fun group(samples: List<Sample>): List<Group> {
        if (samples.isEmpty()) return emptyList()
        val fromModel = runCatching { askModel(samples) }
            .onFailure { flogError { "yijian mistakes: grouping failed: ${it.message}" } }
            .getOrNull()
        return fromModel?.takeIf { it.isNotEmpty() } ?: groupByShape(samples)
    }

    private suspend fun askModel(samples: List<Sample>): List<Group>? {
        // The model sees one line per correction; indices are what come back.
        val listing = samples.mapIndexed { i, s ->
            "$i|${s.tag}|${s.wrong} -> ${s.right}|${s.sentence.take(80)}|${s.why.take(60)}"
        }.joinToString("\n")

        val reply = ReviewAi.rawChat(
            system = """你是英语老师，正在整理一个中国学生的历史错误。
把下面每条错误归到**知识点**上：同一个知识点下的条目，错误原因本质相同。

要求：
- id：知识点的英文标识，小写连字符，如 "very-modifies-verb"、"missing-article"、"past-tense-regular"。同一个点必须用同一个 id。
- label：中文短标签，不超过 12 字，如 "very 不能修饰动词"。
- correctForm：这个知识点正确的写法（没有单一形式就写规则，20 字以内）。
- explanation：一句话说明规则，30 字以内。
- indices：属于这个知识点的条目编号数组。
- kind：拼写 / 语法 / 用词 / 表达 之一。

归并原则：
- 同一个词的拼错（recieve / receive）算一个点。
- 同一个语法规则下的不同句子算一个点（"I very like" 和 "I really very like" 都是 very 修饰动词）。
- 明确不同的规则不要硬合并。

关于 tag = 说法 的条目：
- 这些**不是错误**。学生的句子本身对，只是母语者有更地道的说法。
- 这类知识点要说清"什么时候该这么说"，而不是"你错在哪"。
- label 写成"XX 更地道"的形式，如 "口语用 can't 代替 cannot"、"go back to 比 come back 更自然"。
- kind 填 说法。
- 如果多个说法条目属于同一类提升（如都是"口语缩写"），可以合并成一个点。

严格只输出 JSON：
{"points":[{"id":"...","kind":"语法","label":"...","correctForm":"...","explanation":"...","indices":[0,3]}]}""",
            user = listing,
            maxTokens = 1600,
        ) ?: return null

        val obj = ReviewAi.firstJsonObject(reply) ?: return null
        val arr = obj.optJSONArray("points") ?: return null
        val groups = mutableListOf<Group>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim().lowercase().replace(' ', '-')
            if (id.isBlank()) continue
            val idx = mutableListOf<Int>()
            o.optJSONArray("indices")?.let { a ->
                for (k in 0 until a.length()) {
                    val n = a.optInt(k, -1)
                    if (n in samples.indices) idx.add(n)
                }
            }
            if (idx.isEmpty()) continue
            groups.add(
                Group(
                    id = id,
                    kind = o.optString("kind").ifBlank { "表达" },
                    label = o.optString("label").ifBlank { id },
                    correctForm = o.optString("correctForm"),
                    explanation = o.optString("explanation"),
                    sampleIndices = idx.distinct(),
                    newFailures = idx.size,
                )
            )
        }
        return groups
    }

    /** Fallback: one point per distinct (wrong → right) pair, identified by a hash. */
    private fun groupByShape(samples: List<Sample>): List<Group> {
        val byPair = samples.indices.groupBy { i ->
            val s = samples[i]
            "${s.wrong.trim().lowercase()}|${s.right.trim().lowercase()}"
        }
        return byPair.map { (key, indices) ->
            val first = samples[indices.first()]
            Group(
                id = "shape-" + sha1(key).take(10),
                kind = first.tag.ifBlank { "表达" },
                label = first.why.take(14).ifBlank { first.right.take(12) },
                correctForm = first.right,
                explanation = first.why,
                sampleIndices = indices,
                newFailures = indices.size,
            )
        }
    }

    private fun sha1(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
