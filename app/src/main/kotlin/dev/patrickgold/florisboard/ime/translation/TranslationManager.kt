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

package dev.patrickgold.florisboard.ime.translation

import androidx.compose.ui.graphics.vector.ImageVector
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import dev.patrickgold.florisboard.ime.keyboard3.ImeState
import dev.patrickgold.florisboard.ime.nlp.NlpManager
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.asK3String
import java.net.HttpURLConnection
import java.net.URL

/**
 * A suggestion candidate that carries a Chinese translation of what the user typed in English.
 * [text] is the translation (committed on click). The corrected English is kept for logging
 * and the daily notes, but intentionally not shown in the candidates UI.
 */
data class TranslationSuggestionCandidate(
    override val text: CharSequence,
    val correctedEnglish: String,
    val note: String,
    override val confidence: Double = 1.0,
    override val isEligibleForAutoCommit: Boolean = false,
    override val isEligibleForUserRemoval: Boolean = false,
    override val icon: ImageVector? = null,
    override val sourceProvider: SuggestionProvider? = null,
) : SuggestionCandidate {
    override val secondaryText: CharSequence?
        get() = null
}

data class TranslationResult(
    val corrected: String,
    val translations: List<String>,
    val note: String,
)

/**
 * yijian AI translation pipeline: watches for sentence-ending punctuation, sends the
 * (possibly broken) English sentence to DeepSeek together with surrounding context,
 * then replaces the English with the best Chinese translation and offers the other
 * interpretations as smartbar candidates.
 */
class TranslationManager(
    private val imeController: ImeController,
) {
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Resolved lazily: [NlpManager]'s constructor reads [FlorisApplication.imeController],
     * so it must not be instantiated while [FlorisApplication.onCreate] is still running.
     */
    private val nlpManager: NlpManager
        get() = dev.patrickgold.florisboard.FlorisApplication.getHack().nlpManager.value

    @Volatile private var inFlight = false

    /** The most recent translation auto-committed by us; used to swap it for an alternative. */
    @Volatile internal var lastCommitted: String? = null

    /**
     * English text (including any typed trailing punctuation) that is waiting in the editor to be
     * replaced once the user picks a translation from the candidates list.
     */
    @Volatile internal var pendingEnglish: String? = null

    /**
     * Pre-edit buffer: while translation is enabled, typed text does NOT go to the target app.
     * It accumulates here and is surfaced via [preeditText] in the IME panel; it only reaches
     * the editor when the user picks a translation or dismisses the panel.
     */
    private val buffer = StringBuilder()
    val preeditText = MutableStateFlow("")

    /**
     * Translation lifecycle state for the panel. [Translating] means an API call is in flight;
     * [Idle] with a non-empty buffer means the text simply has not been translated yet. The
     * last error is surfaced to the user instead of silently spinning.
     */
    enum class Status { IDLE, TRANSLATING, ERROR }
    val status = MutableStateFlow(Status.IDLE)
    val errorMessage = MutableStateFlow<String?>(null)

    private fun setStatus(newStatus: Status, error: String? = null) {
        errorMessage.value = error
        status.value = newStatus
    }

    fun hasBufferedText(): Boolean = buffer.isNotEmpty()

    private fun preeditActive(state: ImeState): Boolean {
        if (!prefs.translation.enabled.get()) return false
        val info = state.editor.info
        if (info.isRawInputEditor) return false
        if (info.inputAttributes.variation == InputAttributes.Variation.PASSWORD) return false
        return true
    }

    fun clearBuffer() {
        buffer.setLength(0)
        preeditText.value = ""
        setStatus(Status.IDLE)
    }

    /** Commits the buffered English as-is into the target editor (user dismissed the panel). */
    fun commitBufferAsRaw(scope: ImeController.UpdateImeStateScope) {
        if (buffer.isEmpty()) return
        val text = buffer.toString()
        clearBuffer()
        scope.emitTextDirect(text.asK3String())
    }

    /**
     * Intercepts a text emit while pre-edit is active. Returns true if the emit was consumed
     * into the buffer (sentence-ending punctuation additionally triggers a translation).
     */
    fun interceptEmitText(value: K3String, scope: ImeController.UpdateImeStateScope): Boolean {
        if (!preeditActive(scope.state)) return false
        val s = value.toText()
        val last = s.lastOrNull()
        buffer.append(s)
        preeditText.value = buffer.toString()
        if (status.value == Status.ERROR) {
            setStatus(Status.IDLE)
        }
        if (last == '.' || last == '!' || last == '?') {
            val english = buffer.toString().trimEnd('.', '!', '?').trim()
            if (isTranslatableTail(english, requireTwoWords = true)) {
                translateSentence(english, scope.state)
            }
        }
        return true
    }

    /** Intercepts backspace while pre-edit is active. Returns true if consumed. */
    fun interceptBackspace(scope: ImeController.UpdateImeStateScope): Boolean {
        if (!preeditActive(scope.state)) return false
        if (buffer.isEmpty()) return false
        // delete last UTF-16 code point (handle surrogate pairs)
        var end = buffer.length - 1
        if (end > 0 && buffer[end].isLowSurrogate()) end--
        buffer.setLength(end)
        preeditText.value = buffer.toString()
        return true
    }

    /**
     * Called before a normal Enter handling: flushes the buffer so the raw English is in the
     * editor and Enter behaves as usual.
     */
    fun flushBufferOnEnter(scope: ImeController.UpdateImeStateScope) {
        if (preeditActive(scope.state)) {
            commitBufferAsRaw(scope)
        }
    }

    /**
     * Called from [ImeController]'s emitText for every committed character. Triggers a
     * translation when a sentence-ending character is typed.
     */
    fun maybeTranslateOnPunct(value: K3String) {
        if (!prefs.translation.enabled.get()) return
        val typed = value.toText()
        val trigger = typed.lastOrNull() ?: return
        if (trigger != '.' && trigger != '!' && trigger != '?' && trigger != '\n') return
        if (inFlight) return
        val state = imeController.snapshotState()
        val info = state.editor.info
        // Never touch password fields or raw input editors (games, terminals)
        if (info.isRawInputEditor) return
        if (info.inputAttributes.variation == InputAttributes.Variation.PASSWORD) return
        val beforeCursor = state.editor.getSurroundingText(500, 0).textBefore
        val (english, context) = extractSentence(beforeCursor) ?: return
        inFlight = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { callApiBlocking(english, context) }
                applyTranslation(english, trigger, result, trackPending = true)
            } catch (e: Exception) {
                flogError { "yijian translation failed: ${e.message}" }
            } finally {
                inFlight = false
            }
        }
    }

    fun resetLastCommitted() {
        lastCommitted = null
    }

    /**
     * Manual translation trigger (the 「译」 key). With a non-empty pre-edit buffer the buffered
     * sentence is translated; otherwise the last sentence already in the editor is used.
     */
    fun translateNow() {
        if (!prefs.translation.enabled.get()) return
        if (inFlight) return
        val state = imeController.snapshotState()
        if (buffer.isNotEmpty()) {
            val english = buffer.toString().trimEnd('.', '!', '?').trim()
            if (isTranslatableTail(english, requireTwoWords = false)) {
                translateSentence(english, state)
            }
            return
        }
        val info = state.editor.info
        if (info.isRawInputEditor) return
        if (info.inputAttributes.variation == InputAttributes.Variation.PASSWORD) return
        val beforeCursor = state.editor.getSurroundingText(500, 0).textBefore
        val (english, context) = extractTail(beforeCursor) ?: return
        translateSentence(english, state, context)
    }

    private fun translateSentence(english: String, state: ImeState, givenContext: String? = null) {
        if (inFlight) return
        val context = givenContext ?: state.editor.getSurroundingText(300, 0).textBefore.takeLast(200)
        inFlight = true
        setStatus(Status.TRANSLATING)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { callApiBlocking(english, context) }
                applyTranslation(english, null, result, trackPending = givenContext != null)
                setStatus(Status.IDLE)
            } catch (e: Exception) {
                flogError { "yijian translation failed: ${e.message}" }
                setStatus(Status.ERROR, humanizeError(e))
            } finally {
                inFlight = false
            }
        }
    }

    private fun humanizeError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("HTTP 401") || msg.contains("HTTP 403") -> "API Key 无效"
            msg.contains("HTTP 402") -> "API 余额不足"
            msg.contains("HTTP 429") -> "请求太频繁，稍后再试"
            msg.contains("API key not configured") -> "还没填 API Key"
            msg.contains("timed out") || e is java.net.SocketTimeoutException -> "网络超时"
            msg.contains("Unable to resolve host") || e is java.net.UnknownHostException -> "网络不可用"
            msg.contains("returned no translations") -> "模型没给出译文"
            else -> "翻译失败"
        }
    }

    /**
     * Splits [beforeCursor] (which ends with a trigger punctuation) into the English sentence
     * tail and the preceding context. Returns null if the tail does not look like an
     * English sentence worth translating.
     */
    private fun extractSentence(beforeCursor: String): Pair<String, String>? {
        return extractTail(beforeCursor.dropLast(1), requireTwoWords = true)
    }

    /**
     * Extracts the last sentence-like tail from [content] (no trailing punctuation assumed).
     */
    private fun extractTail(content: String, requireTwoWords: Boolean = false): Pair<String, String>? {
        val boundaries = listOf('.', '!', '?', '\n', '。', '！', '？')
        var idx = -1
        for (b in boundaries) {
            val i = content.lastIndexOf(b)
            if (i > idx) idx = i
        }
        val tail = content.substring(idx + 1).trim()
        if (!isTranslatableTail(tail, requireTwoWords)) return null
        val context = content.substring(0, idx + 1).takeLast(200)
        return tail to context
    }

    private fun isTranslatableTail(tail: String, requireTwoWords: Boolean): Boolean {
        if (tail.isEmpty()) return false
        // Already Chinese? Don't re-translate.
        if (tail.any { it.code > 0x2E7F }) return false
        val letters = tail.count { it.isLetter() && it.code < 128 }
        if (letters < 2) return false
        // When triggered by punctuation require at least two words or a reasonably long
        // single token, to avoid triggering on "Mr.", "3.", "e.g." style fragments.
        if (requireTwoWords && !tail.any { it.isWhitespace() } && tail.length < 12) return false
        return true
    }

    private fun zhPunct(src: Char): String = when (src) {
        '.' -> "。"
        '!' -> "！"
        '?' -> "？"
        else -> ""
    }

    /**
     * @param typedPunct the punctuation character the user typed that triggered the translation,
     *  or null for a manual trigger via the 「译」 key (no punctuation is removed, but the
     *  translation is still terminated with a Chinese full stop).
     * @param trackPending if true, remember the English as [pendingEnglish] so a candidate click
     *  replaces it in the editor. False for the pre-edit buffer flow, where the English never
     *  reached the editor.
     */
    private fun applyTranslation(
        english: String,
        typedPunct: Char?,
        result: TranslationResult,
        trackPending: Boolean,
    ) {
        val punct = typedPunct?.let { zhPunct(it) } ?: "。"
        val candidates = result.translations.map { raw ->
            raw.trim().trimEnd('。', '！', '？', '.', '!', '?') + punct
        }.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return
        if (trackPending) {
            // The English sits in the editor; remember it so a candidate click can replace it.
            pendingEnglish = english + (typedPunct?.toString() ?: "")
        }
        // Offer the interpretations in the smartbar
        nlpManager.suggestDirectly(candidates.mapIndexed { i, t ->
            TranslationSuggestionCandidate(
                text = t,
                correctedEnglish = result.corrected,
                note = result.note,
                confidence = 1.0 - i * 0.1,
            )
        })
    }

    private fun callApiBlocking(english: String, context: String): TranslationResult {
        val baseUrl = prefs.translation.baseUrl.get().trimEnd('/')
        val apiKey = prefs.translation.apiKey.get()
        require(apiKey.isNotBlank()) { "API key not configured" }
        val model = prefs.translation.model.get()

        val systemPrompt = """你是「译键」输入法的翻译引擎。用户是英文水平有限的中国人，输入的英文常有拼写和语法错误，也可能用词不符合口语习惯。任务：
1. 纠正他的英文（corrected）。
2. 结合上下文把他的意思翻译成地道的中文（口语/聊天语气）。
3. 如果这句话有不止一种合理解释，在 translations 里给出最多 3 个不同意图的译文，按贴切度排序；句意明确时只给 1 个。
4. 用 note 用中文一句话指出他的拼写/语法/表达问题（没有则空字符串）。

严格只输出 JSON，格式：{"corrected":"...","translations":["..."],"note":"..."}。不要输出任何其他内容。"""

        val userContent = "上下文：${context.ifBlank { "（对话刚开始）" }}\n\n要翻译的句子：$english"

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.3)
            .put("max_tokens", 800)
            .put("response_format", JSONObject().put("type", "json_object"))

        val conn = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 45000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() }?.decodeToString() ?: error("empty response")
            if (code !in 200..299) error("HTTP $code: ${text.take(200)}")
            val content = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            val jo = JSONObject(content)
            val corrected = jo.optString("corrected")
            val translationsArr = jo.getJSONArray("translations")
            val translations = (0 until translationsArr.length())
                .map { translationsArr.getString(it) }
                .filter { it.isNotBlank() }
            if (translations.isEmpty()) error("model returned no translations")
            return TranslationResult(
                corrected = corrected,
                translations = translations.take(3),
                note = jo.optString("note"),
            )
        } finally {
            conn.disconnect()
        }
    }
}
