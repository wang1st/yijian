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

import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.lib.devtools.flogError
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Which broader weakness a mistake belongs to, as classified by the model. */
data class WeakSpot(val topic: String, val count: Int, val example: String, val advice: String)

/** A freshly generated practice sentence. */
data class GeneratedCard(
    val english: String,
    val chinese: String,
    val focus: String,
)

/**
 * AI features for the review flow. All calls go through the same OpenAI-compatible endpoint and
 * key the translation feature already uses, so nothing extra needs configuring.
 *
 * Cost is controlled by the caller: grading only reaches the model when the local diff lands in
 * the ambiguous band (see [shouldAskAi]), and analysis / generation run on explicit user action
 * or at most once per day.
 */
object ReviewAi {
    private val prefs by FlorisPreferenceStore

    /** Local similarity band that is worth a model call. */
    private const val BAND_LOW = 55
    private const val BAND_HIGH = 92

    /** True when a local diff is close enough to be worth asking the model about. */
    fun shouldAskAi(similarity: Int): Boolean = similarity in BAND_LOW until BAND_HIGH

    private fun isEnabled(): Boolean = prefs.translation.enabled.get() && prefs.translation.apiKey.get().isNotBlank()

    /** Sends a single-turn prompt and returns the model's raw text reply. */
    private fun chat(system: String, user: String, maxTokens: Int = 700): String {
        val baseUrl = prefs.translation.baseUrl.get().trimEnd('/')
        val apiKey = prefs.translation.apiKey.get()
        val model = prefs.translation.model.get()
        if (apiKey.isBlank()) error("API key not configured")

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("thinking", JSONObject().put("type", "disabled"))
            put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        }

        val conn = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() }?.decodeToString() ?: ""
            if (code !in 200..299) error("HTTP $code: ${text.take(120)}")
            JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally {
            conn.disconnect()
        }
    }

    /** Extracts the first JSON object out of a reply that may be wrapped in prose or fences. */
    private fun jsonObject(reply: String): JSONObject {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        require(start >= 0 && end > start) { "model did not return JSON" }
        return JSONObject(reply.substring(start, end + 1))
    }

    // ---- grading ----------------------------------------------------------------------

    data class AiGrade(
        /** True when the answer expresses the same thing as the target. */
        val accepted: Boolean,
        /** One short sentence of feedback, in Chinese. */
        val feedback: String,
        /** The concrete difference worth remembering, empty when the answer was accepted. */
        val fix: String,
    )

    /**
     * Judges whether [answer] and [target] mean the same thing, and if not, why.
     * Only called when the local diff is ambiguous, so the model sees a close pair.
     */
    suspend fun gradeAnswer(target: String, answer: String, chinese: String): AiGrade {
        if (!isEnabled()) return AiGrade(false, "", "")
        val system = """你是英语老师在批改回译练习。学生看到中文，要写出对应的英文；给出参考答案。
判断学生的答案是否和参考答案表达**同一个意思**：
- 同义改写、换用别的表达方式、语序不同，只要意思一致，就算正确
- 漏掉关键信息、意思变了、语法错误导致读不通，算错误
- 大小写和标点差异一律忽略，不算错误

严格只输出 JSON：
{"accepted":true或false,"feedback":"一句中文点评，15字以内","fix":"如果错误，写出最关键的那处不同，20字以内；正确则空字符串"}"""
        val user = "中文原句：$chinese\n参考答案：$target\n学生的答案：$answer"
        return runCatching {
            val o = jsonObject(chat(system, user, maxTokens = 200))
            AiGrade(
                accepted = o.optBoolean("accepted", false),
                feedback = o.optString("feedback").trim(),
                fix = o.optString("fix").trim(),
            )
        }.onFailure { flogError { "yijian ai grade: ${it.message}" } }
            .getOrDefault(AiGrade(false, "", ""))
    }

    // ---- weakness analysis ------------------------------------------------------------

    /**
     * Groups the user's historical mistakes into a handful of named weaknesses, so the home
     * screen can show what to work on rather than raw counts by tag.
     */
    suspend fun analyzeWeaknesses(samples: List<String>): List<WeakSpot> {
        if (!isEnabled() || samples.isEmpty()) return emptyList()
        val system = """你是英语老师，正在分析一个中国学生的历史错误记录。
把错误归纳成**具体、可练的语法或表达点**（不要用「语法」这种大分类，要写成「冠词 a/the 的使用」「过去式与现在完成时混用」这种具体项）。
- 每个点必须能对应回至少一条记录
- 按出现次数从多到少排序，最多 5 个
- advice 是一句可直接照做的口诀或规则，20 字以内

严格只输出 JSON：
{"spots":[{"topic":"具体的点","count":出现次数整数,"example":"学生实际写错的片段","advice":"一句口诀"}]}"""
        val user = "错误记录：\n" + samples.joinToString("\n") { "- $it" }
        return runCatching {
            val o = jsonObject(chat(system, user, maxTokens = 600))
            val arr = o.optJSONArray("spots") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    WeakSpot(
                        topic = it.optString("topic"),
                        count = it.optInt("count", 1),
                        example = it.optString("example"),
                        advice = it.optString("advice"),
                    )
                }
            }.filter { it.topic.isNotBlank() }
        }.onFailure { flogError { "yijian ai weakspots: ${it.message}" } }
            .getOrDefault(emptyList())
    }

    // ---- sentence generation ----------------------------------------------------------

    /**
     * Generates new practice sentences aimed at [focus] (a weakness) or, when null, at the
     * user's general level. This is what makes the review queue endless.
     */
    suspend fun generateCards(focus: String?, count: Int = 5): List<GeneratedCard> {
        if (!isEnabled()) return emptyList()
        val system = """你是英语老师，为学生编写回译练习题。学生是中文母语者，英文水平中等偏下。
要求：
- 每句英文 8~18 个单词，是日常或工作里真会说的话，不要教科书腔
- 中文译文要自然口语，不要直译腔
- 句子要能练到指定的知识点
- 不要出现生僻词

严格只输出 JSON：
{"cards":[{"english":"英文句子","chinese":"中文译文","focus":"这句主要练什么，10字以内"}]}"""
        val user = buildString {
            if (focus.isNullOrBlank()) {
                append("请生成 $count 个一般难度的日常英语练习句。")
            } else {
                append("学生最近的薄弱点是：$focus\n请生成 $count 个专门练这个点的句子。")
            }
        }
        return runCatching {
            val o = jsonObject(chat(system, user, maxTokens = 1200))
            val arr = o.optJSONArray("cards") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    GeneratedCard(
                        english = it.optString("english").trim(),
                        chinese = it.optString("chinese").trim(),
                        focus = it.optString("focus").trim(),
                    )
                }
            }.filter { it.english.isNotBlank() && it.chinese.isNotBlank() }
        }.onFailure { flogError { "yijian ai generate: ${it.message}" } }
            .getOrDefault(emptyList())
    }

    /**
     * Expands terse correction items into readable learner-facing feedback: what was wrong,
     * why it matters, and how to remember it.
     */
    suspend fun explainMistakes(items: List<String>): Map<String, String> {
        if (!isEnabled() || items.isEmpty()) return emptyMap()
        val system = """你是英语老师。下面是学生写错的片段，格式是「他写的 → 正确的」。
对每一条，用一句中文说清**为什么错、怎么记住**，不要复述规则名，要讲人话，30 字以内。

严格只输出 JSON：
{"items":[{"key":"原样的「他写的 → 正确的」这一条","explain":"一句解释"}]}"""
        val user = items.joinToString("\n") { "- $it" }
        return runCatching {
            val o = jsonObject(chat(system, user, maxTokens = 800))
            val arr = o.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val it = arr.optJSONObject(i) ?: return@mapNotNull null
                val key = it.optString("key").trim()
                val explain = it.optString("explain").trim()
                if (key.isBlank() || explain.isBlank()) null else key to explain
            }.toMap()
        }.onFailure { flogError { "yijian ai explain: ${it.message}" } }
            .getOrDefault(emptyMap())
    }

    // ---- AI-authored exercises ---------------------------------------------------------

    /** One exercise written by the model, ready to render. */
    data class AiExercise(
        val type: String,
        /** What the learner sees (Chinese prompt, sentence to fix, or "听写" placeholder). */
        val prompt: String,
        /** Text to speak for LISTEN, empty otherwise. */
        val speak: String,
        val choices: List<Pair<String, Boolean>>,
        /** The sentence as it should read, shown after answering. */
        val answer: String,
        /** One line explaining the point being practised. */
        val tip: String,
        /** Why this point generalises (only for cards with no recorded mistake). */
        val usage: String = "",
        /** Two or three other sentences using the same pattern. */
        val moreExamples: List<String> = emptyList(),
    )

    /**
     * Has the model write the exercise itself, choosing the type that suits the card.
     *
     * Local generation could only shuffle words the sentence already had, which produced
     * distractors that were obviously wrong (same-sentence neighbours) and errors that were
     * mechanical (dropped article). The model knows what a plausible mistake looks like, so
     * every type is authored here.
     *
     * @param box how well the card is known; new cards get recognition tasks only.
     */
    suspend fun makeExercise(
        english: String,
        chinese: String,
        box: Int,
        isNew: Boolean,
        knownMistakes: List<String>,
        /** Correction tag of the mistake this card is about: 拼写 / 语法 / 用词 / 表达. */
        mistakeTag: String = "",
        wrongText: String = "",
        rightText: String = "",
    ): AiExercise? {
        if (!isEnabled()) return null
        // A spelling slip is an input problem: drill the word. A grammar slip is a pattern
        // problem: drill the pattern on another sentence, not the same one again.
        val focusRule = when (mistakeTag) {
            "拼写" -> "这道题必须直接考拼写：让学生补全或选出他写错的那个词（$wrongText → $rightText），不要让句子上下文泄答案。"
            "语法" -> "这道题必须考同一个语法点，但换一个不同场景的句子，让学生把规则用在新句子上，而不是背这一句。"
            "用词" -> "这道题考搭配：在相近的候选词之间选对的那个，干扰项要是学生常见的中式搭配。"
            "表达" -> "这道题考更地道的说法：给出口语但不够地道的表达，让学生选出母语者会用的版本。"
            // No mistake means the sentence introduced a usable pattern: the job is to
            // generalise it, not to re-test what the learner already got right.
            else -> "这句学生写得没错。请找出句子里一个**可以举一反三的常用用法或搭配**，" +
                "把考点放在这个用法上，并让学生在新语境里用一次；tip 里写清这条用法还能用在什么场合。"
        }
        val allowed = if (isNew || box <= 0) {
            "CLOZE、MEANING、LISTEN"
        } else if (box <= 2) {
            "RECALL、CLOZE、LISTEN、MEANING"
        } else {
            "RECALL、FIX_ERROR、LISTEN"
        }
        val system = """你是英语老师，为学生出一道复习题。学生是中文母语者，水平中等偏下。
可选题型（只允许这几种）：RECALL（看中文写英文）、CLOZE（句中挖一个词，四选一）、
LISTEN（听英文选正确文本）、FIX_ERROR（句中有一处错误，选出正确版本）、MEANING（看英文选正确中文）。

各题型要求：
- CLOZE：从原句中挖掉一个**实词**（名词/动词/形容词），用 ____ 代替；给出 4 个选项，干扰项要和原文语境相配、长度相近，让学生必须靠语法或搭配判断，不能一眼排除。
- LISTEN：把原句作为正确选项，再写 2 个**同主题、长度相近、有细微差别**的干扰句（换时态、换介词、换单复数），不要写明显无关的句子。
- FIX_ERROR：在原句基础上制造**一个**学生常犯的错误（缺冠词、第三人称单数少 s、时态错、介词错），给出错误版和正确版两个选项。
- MEANING：中文译文作为正确选项，再写 2 个意思接近但不同的干扰译文。
- RECALL：不需要选项，prompt 填中文，answer 填英文。

如果学生以前在这句上犯过错，出题要针对同样的点。
$focusRule

严格只输出 JSON：
{"type":"题型","prompt":"学生看到的内容","speak":"需要朗读的英文（仅 LISTEN，其他空字符串）","choices":[{"text":"选项","correct":true或false}],"answer":"正确答案全文","tip":"一句话考点提示，15字以内","usage":"这条用法的规则或适用场合（学生没错时必填，否则空字符串）","more":"同一条用法的另外两句英文例句，用换行分隔（没有就给空字符串）"}"""
        val user = buildString {
            append("原句英文：$english\n")
            append("中文译文：$chinese\n")
            append("允许的题型：$allowed\n")
            if (knownMistakes.isNotEmpty()) {
                append("学生以前在这句上的错误：${knownMistakes.joinToString("；")}\n")
            }
        }
        return runCatching {
            val o = jsonObject(chat(system, user, maxTokens = 900))
            val type = o.optString("type").uppercase().trim()
            if (type !in setOf("RECALL", "CLOZE", "LISTEN", "FIX_ERROR", "MEANING")) return@runCatching null
            val arr = o.optJSONArray("choices") ?: JSONArray()
            val choices = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { c ->
                    val text = c.optString("text").trim()
                    if (text.isBlank()) null else text to c.optBoolean("correct", false)
                }
            }
            val answer = o.optString("answer").ifBlank { english }
            // A choice exercise with no correct option is unusable.
            if (type != "RECALL" && (choices.size < 2 || choices.none { it.second })) return@runCatching null
            AiExercise(
                type = type,
                prompt = o.optString("prompt").ifBlank { chinese },
                speak = o.optString("speak").ifBlank { english },
                choices = choices,
                answer = answer,
                tip = o.optString("tip").trim(),
                usage = o.optString("usage").trim(),
                moreExamples = o.optString("more").trim()
                    .split("\n")
                    .map { it.trim().removePrefix("-").trim() }
                    .filter { it.isNotBlank() }
                    .take(3),
            )
        }.onFailure { flogError { "yijian ai exercise: ${it.message}" } }.getOrNull()
    }

}
