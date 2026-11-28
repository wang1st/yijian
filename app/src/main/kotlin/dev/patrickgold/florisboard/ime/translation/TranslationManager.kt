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
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import dev.patrickgold.florisboard.ime.keyboard3.ImeEditor
import dev.patrickgold.florisboard.ime.keyboard3.ImeState
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
    val originalEnglish: String,
    val note: String,
    val requestId: Long,
    /** A null range denotes a voice draft, which has never been inserted in the editor. */
    val range: TranslationRange?,
    val corrections: List<CorrectionItem> = emptyList(),
    override val confidence: Double = 1.0,
    override val isEligibleForAutoCommit: Boolean = false,
    override val isEligibleForUserRemoval: Boolean = false,
    override val icon: ImageVector? = null,
    override val sourceProvider: SuggestionProvider? = null,
) : SuggestionCandidate {
    override val secondaryText: CharSequence?
        get() = null
}

/**
 * One concrete problem found in the user's English, with the fix and the short rule behind it.
 * [tag] is a coarse category used for the daily/weekly report (拼写 / 语法 / 用词 / 表达).
 */
data class CorrectionItem(
    val tag: String,
    val wrong: String,
    val right: String,
    val why: String,
)

data class TranslationResult(
    val corrected: String,
    val translations: List<String>,
    val note: String,
    val corrections: List<CorrectionItem> = emptyList(),
)

/** Translates the current editor range or a voice draft on explicit request. */
class TranslationManager(
    private val imeController: ImeController,
) {
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val lastLesson = MutableStateFlow<TranslationSuggestionCandidate?>(null)
    val translationToCopy = MutableStateFlow<String?>(null)
    val candidates = MutableStateFlow<List<TranslationSuggestionCandidate>>(emptyList())
    private val accepted = mutableListOf<TranslationRange>()
    @Volatile private var requestId = 0L

    /** Called inside the controller state update, like publication and acceptance. */
    fun dismissRequest() {
        requestId++
        lastLesson.value = null
        pendingRange = null
        translationToCopy.value = null
        candidates.value = emptyList()
        clearBuffer()
    }

    fun resetEditor() {
        dismissRequest()
        accepted.clear()
        clearUndo()
        clearVoiceStatus()
    }

    /** Pasted text and other external edits shift the editor; drop candidates that no longer match. */
    fun invalidateStaleRequest(editor: ImeEditor) {
        val range = pendingRange ?: return
        val window = editor.ic.get()?.translationWindow(editor.reportedCaret) ?: return
        if (!window.matchesSource(range)) dismissRequest()
    }

    fun isCurrent(candidate: TranslationSuggestionCandidate) =
        // requestId alone identifies the live request: it increments on every dismiss, so
        // any stale candidate carries a dead id. Comparing the full object would reject
        // legitimately modified copies (e.g. committing correctedEnglish instead of text).
        candidate.requestId == requestId

    /** The first live candidate of the current request, if any. */
    fun currentCandidate(): TranslationSuggestionCandidate? =
        candidates.value.firstOrNull { it.requestId == requestId }
            ?: lastLesson.value?.takeIf { it.requestId == requestId }

    fun rememberTranslation(range: TranslationRange) {
        accepted.removeAll { it.end > range.start }
        accepted.add(range)
    }

    private val readTimeoutSeconds = 60

    /** Inputs at or below this length are translated in full rather than tail-only. */
    private val MAX_TRANSLATE_CHARS = 400
    private val MIN_LATIN_LETTERS = 2
    private val MIN_CJK_CHARS = 2

    /** Records every finished sentence and produces the periodic English report. */
    val teacher = EnglishTeacher()

    /** Voice input: records 16 kHz mono WAV and sends it to DeepSeek ASR. */
    val recorder = VoiceRecorder()

    /** True while a recorded clip is being transcribed. */
    val voiceBusy = MutableStateFlow(false)

    /** Last voice-specific message (recognised text or a voice error). */
    val voiceStatus = MutableStateFlow<String?>(null)

    /** Cancelling transcription increments this; in-flight results with a stale ticket are dropped. */
    @Volatile
    private var voiceTicket = 0L

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

    /** Clears any translation error so the panel can close. */
    fun clearStatus() = setStatus(Status.IDLE)

    /** Clears the voice banner (e.g. 「语音识别失败」). */
    fun clearVoiceStatus() {
        voiceStatus.value = null
    }

    /** Surfaces a user-visible error from outside (e.g. the replace guard in ImeController). */
    fun setError(message: String) = setStatus(Status.ERROR, message)

    private fun setStatus(newStatus: Status, error: String? = null) {
        errorMessage.value = error
        status.value = newStatus
    }

    @Deprecated("S1: text is no longer buffered; use the editor content")
    fun hasBufferedText(): Boolean = buffer.isNotEmpty()

    /** True when there is something worth showing in the panel (draft, candidates or an error). */
    fun hasPanelContent(): Boolean =
        buffer.isNotEmpty() || errorMessage.value != null

    private fun preeditActive(state: ImeState): Boolean {
        if (!prefs.translation.enabled.get()) return false
        val info = state.editor.info
        if (info.isRawInputEditor) return false
        // Never translate inside yijian's own screens: the review practice field would
        // otherwise translate the user's answer and give it away before grading.
        if (info.packageName == FlorisApplication.getHack().packageName) return false
        return when (info.inputAttributes.variation) {
            InputAttributes.Variation.PASSWORD,
            InputAttributes.Variation.VISIBLE_PASSWORD,
            InputAttributes.Variation.WEB_PASSWORD -> false
            else -> true
        }
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
     * S1: text is NO LONGER held back. Everything the user types goes straight into the target
     * editor, so it is always visible and survives switching apps. We only track how far the
     * cursor has moved so a translation can replace the sentence in place.
     *
     * @return always false — the emit is never consumed.
     */
    fun observeEmitText(value: K3String, scope: ImeController.UpdateImeStateScope): Boolean {
        lastLesson.value = null
        if (!preeditActive(scope.state)) return false
        val s = value.toText()
        trackedLength += s.length
        if (status.value == Status.ERROR) setStatus(Status.IDLE)
        return false
    }

    /** Backspace is never intercepted either; just keep the tracked length in sync. */
    fun observeBackspace(scope: ImeController.UpdateImeStateScope): Boolean {
        lastLesson.value = null
        if (!preeditActive(scope.state)) return false
        if (trackedLength > 0) trackedLength -= 1
        return false
    }

    private var trackedLength = 0

    class Undo(val range: TranslationRange, val original: String)
    val undoable = MutableStateFlow<Undo?>(null)

    fun offerUndo(range: TranslationRange, original: String) {
        val undo = Undo(range, original)
        undoable.value = undo
        scope.launch {
            kotlinx.coroutines.delay(5000)
            undoable.compareAndSet(undo, null)
        }
    }

    fun forgetTranslation(range: TranslationRange) {
        accepted.remove(range)
    }

    fun clearUndo() {
        undoable.value = null
    }

    /**
     * Legacy hook from the non-pre-edit path. Intentionally a no-op: translation is manual only,
     * triggered by the 「译」 key.
     */
    @Suppress("UNUSED_PARAMETER")
    fun maybeTranslateOnPunct(value: K3String) {
        // no-op
    }


    // ---- Voice input ----------------------------------------------------------------

    /** Resolves a string resource through the app context (safe after app init). */
    private fun str(res: Int): String = FlorisApplication.getHack().getString(res)

    /** Starts recording. Returns false (with a message) when the mic is unavailable. */
    fun startVoice(): Boolean {
        if (recorder.isRecording) return true
        if (!prefs.translation.enabled.get()) {
            voiceStatus.value = str(R.string.yijian__enable_translation_first)
            return false
        }
        if (!recorder.canRecord()) {
            voiceStatus.value = str(R.string.yijian__mic_unavailable)
            return false
        }
        voiceStatus.value = str(R.string.yijian__voice_recording_hint)
        scope.launch {
            val ok = recorder.start()
            if (!ok) {
                voiceStatus.value = recorder.lastError ?: str(R.string.yijian__voice_start_failed)
            }
        }
        return true
    }

    /** Stops recording, transcribes what was said and runs it through the translation flow. */
    fun stopVoiceAndTranslate() {
        if (!recorder.isRecording) return
        val ticket = requestId
        val myVoiceTicket = ++voiceTicket
        val editor = imeController.snapshotState().editor
        scope.launch {
            voiceBusy.value = true
            voiceStatus.value = str(R.string.yijian__recognizing)
            try {
                val pcm = recorder.stop()
                if (pcm == null) {
                    voiceStatus.value = str(R.string.yijian__no_sound)
                    return@launch
                }
                val wav = recorder.toWav(pcm)
                val client = SpeechClient(prefs.translation.baseUrl.get(), prefs.translation.apiKey.get())
                val result = withContext(Dispatchers.IO) { client.transcribe(wav) }
                // The user cancelled while the request was in flight: drop the result.
                if (myVoiceTicket != voiceTicket) return@launch
                val said = result.text.trim()
                if (said.isEmpty()) {
                    voiceStatus.value = str(R.string.yijian__not_clear)
                    return@launch
                }
                imeController.updateState {
                    if (ticket != requestId || state.editor !== editor || !preeditActive(state)) return@updateState
                    dismissRequest()
                    buffer.append(said)
                    preeditText.value = said
                    voiceStatus.value = null
                    val context = editor.getSurroundingText(300, 0).textBefore.takeLast(200)
                    translateSentence(said, context, range = null)
                }
            } catch (e: SpeechException) {
                if (myVoiceTicket != voiceTicket) return@launch
                voiceStatus.value = e.message
                setStatus(Status.ERROR, e.message ?: str(R.string.yijian__voice_failed))
            } catch (e: Exception) {
                if (myVoiceTicket != voiceTicket) return@launch
                flogError { "yijian voice: ${e.message}" }
                voiceStatus.value = str(R.string.yijian__voice_failed)
                setStatus(Status.ERROR, str(R.string.yijian__voice_failed))
            } finally {
                if (myVoiceTicket == voiceTicket) voiceBusy.value = false
                recorder.reset()
            }
        }
    }

    /** Cancels an in-flight transcription: the late result is dropped when it arrives. */
    fun cancelVoiceTranscription() {
        voiceTicket++
        voiceBusy.value = false
        voiceStatus.value = null
    }

    /** Cancels recording without sending anything to the model. */
    fun cancelVoice() {
        scope.launch {
            recorder.stop()
            recorder.reset()
            voiceStatus.value = null
        }
    }

    /**
     * Synthesises [text] with DeepSeek TTS and plays it through the device speakers.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        scope.launch {
            voiceStatus.value = str(R.string.yijian__synthesizing)
            try {
                val client = SpeechClient(prefs.translation.baseUrl.get(), prefs.translation.apiKey.get())
                val wav = withContext(Dispatchers.IO) { client.synthesize(text) }
                voiceStatus.value = null
                withContext(Dispatchers.Main) { AudioPlayer.play(wav) }
            } catch (e: Exception) {
                flogError { "yijian tts: ${e.message}" }
                voiceStatus.value = (e as? SpeechException)?.message ?: str(R.string.yijian__synthesize_failed)
            }
        }
    }

    /** Capture the source verbatim, including its position in the active editor. */
    fun translateNow(state: ImeState) {
        if (!preeditActive(state)) {
            dismissRequest()
            setError(str(R.string.yijian__translation_disabled_here))
            return
        }
        val ic = state.editor.ic.get()
        val window = ic?.translationWindow(state.editor.reportedCaret)
        if (ic == null || window == null || window.selected.isNotEmpty()) {
            dismissRequest()
            setError(str(if (window == null) R.string.yijian__no_cursor else R.string.yijian__caret_at_end))
            return
        }
        val raw = textToTranslate(window.untranslatedSuffix(accepted).currentParagraph())
        if (!hasTranslatableContent(raw, requireTwoWords = false)) {
            dismissRequest()
            setError(str(R.string.yijian__nothing_to_translate))
            return
        }
        val prefix = window.before.dropLast(raw.length)
        val range = TranslationRange(window.start - raw.length, raw, prefix.takeLast(CONTEXT_CHARS),
            ic.getTextAfterCursor(CONTEXT_CHARS, 0)?.toString()?.take(CONTEXT_CHARS))
        if (pendingRange == range && (status.value == Status.TRANSLATING || candidates.value.isNotEmpty())) return
        dismissRequest()
        pendingRange = range
        translateSentence(raw, prefix.takeLast(200), range)
    }

    private var pendingRange: TranslationRange? = null

    private fun translateSentence(english: String, context: String, range: TranslationRange?) {
        val ticket = requestId
        setStatus(Status.TRANSLATING)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { callApiBlocking(english, context, voiceMode = range == null) }
                imeController.updateState {
                    if (ticket != requestId) return@updateState
                    applyTranslation(english, result, range, ticket)
                    setStatus(Status.IDLE)
                }
            } catch (e: Exception) {
                imeController.updateState {
                    if (ticket == requestId) setError(humanizeError(e))
                }
            }
        }
    }

    /**
     * Turns a low-level failure into something the user can act on. The message always names the
     * failing layer (网络 / 服务端 / 模型返回) plus the concrete reason, because "翻译失败" alone
     * tells nobody what to do next.
     */
    /**
     * Posts the chat request. Some OpenAI-compatible providers reject unknown parameters with
     * 400/422, so a rejected "thinking" field is retried once without it instead of failing.
     */
    private fun postChat(baseUrl: String, apiKey: String, body: JSONObject): String {
        fun post(payload: JSONObject): Pair<Int, String> {
            val conn = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = readTimeoutSeconds * 1000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
            }
            return try {
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes() }?.decodeToString() ?: ""
                code to text
            } finally {
                conn.disconnect()
            }
        }

        var (code, text) = post(body)
        if ((code == 400 || code == 422) && body.has("thinking")) {
            flogError { "yijian: provider rejected 'thinking', retrying without it (${text.take(120)})" }
            val retry = JSONObject(body.toString()).apply { remove("thinking") }
            val second = post(retry)
            code = second.first
            text = second.second
        }
        if (code !in 200..299) error("HTTP $code: ${text.take(200)}")
        return text
    }

    /**
     * Output-token budget for one reply. Scaled by sentence length so the JSON answer always has
     * room to finish (the earlier failure mode was a fixed budget being exhausted).
     */
    private fun tokenBudgetFor(english: String): Int {
        val words = english.split(' ', '\t', '\n').count { it.isNotBlank() }
        return (400 + words * 40).coerceIn(600, 3000)
    }

    /**
     * Recovers translations from a reply cut off by max_tokens. Only the "translations" array is
     * scanned, so corrections/free text can never leak in as a candidate. Complete string literals
     * are taken in order; the final partial literal is dropped.
     */
    private fun salvageJson(content: String): JSONObject? {
        val txKey = "\"translations\""
        val txStart = content.indexOf(txKey)
        if (txStart < 0) return null
        val arrStart = content.indexOf('[', txStart)
        if (arrStart < 0) return null
        // Bounds of the translations array: up to the matching ']' or the start of the next key.
        val arrEnd = content.indexOf(']', arrStart).takeIf { it > arrStart } ?: run {
            val nextKey = content.indexOf("\",\"", arrStart).takeIf { it > arrStart } ?: content.length
            nextKey
        }
        val list = JSONArray()
        var i = arrStart + 1
        while (i < arrEnd) {
            val q = content.indexOf('"', i)
            if (q < 0 || q >= arrEnd) break
            val sb = StringBuilder()
            var j = q + 1
            var closed = false
            while (j < content.length) {
                val c = content[j]
                if (c == '\\' && j + 1 < content.length) {
                    sb.append(content[j + 1]); j += 2; continue
                }
                if (c == '"') { closed = true; break }
                sb.append(c); j += 1
            }
            if (!closed) break   // truncated mid-string: drop the fragment
            if (sb.isNotBlank()) list.put(sb.toString())
            i = j + 1
        }
        if (list.length() == 0) return null
        return JSONObject().apply {
            put("translations", list)
            put("corrected", regexString(content, "corrected") ?: "")
            put("note", "")
        }
    }

    private fun regexString(content: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*\"([^\"\\\\]*)").find(content) ?: return null
        return m.groupValues[1]
    }

    private fun humanizeError(e: Throwable): String {
        val msg = e.message.orEmpty()
        val detail = msg.subSequence(0, minOf(msg.length, 160)).toString()
        return when {
            msg.contains("HTTP 401") || msg.contains("HTTP 403") -> str(R.string.yijian__err_key_invalid)
            msg.contains("HTTP 402") -> str(R.string.yijian__err_no_balance)
            msg.contains("HTTP 429") -> str(R.string.yijian__err_rate_limited)
            msg.contains("HTTP 5") -> FlorisApplication.getHack().getString(
                R.string.yijian__err_server, msg.substringBefore(':').trim())
            msg.contains("HTTP ") -> FlorisApplication.getHack().getString(
                R.string.yijian__err_api, detail.substringBefore(':').trim())
            msg.contains("API key not configured") -> str(R.string.yijian__err_no_key)
            msg.contains("timed out") || e is java.net.SocketTimeoutException ->
                FlorisApplication.getHack().getString(R.string.yijian__err_timeout, readTimeoutSeconds)
            e is java.net.UnknownHostException -> str(R.string.yijian__err_no_host)
            e is java.net.ConnectException -> str(R.string.yijian__err_refused)
            e is javax.net.ssl.SSLException -> str(R.string.yijian__err_ssl)
            // These match internal exception messages thrown above, which stay hardcoded
            // on purpose — only the user-facing outputs are extracted.
            msg.contains("模型没有给出译文") -> str(R.string.yijian__err_no_translation)
            msg.contains("回复被截断") -> str(R.string.yijian__err_truncated)
            msg.contains("模型返回为空") -> str(R.string.yijian__err_empty)
            msg.contains("模型返回格式异常") -> str(R.string.yijian__err_bad_format)
            e is org.json.JSONException -> str(R.string.yijian__err_bad_format)
            // Never surface a raw exception class name to the user; keep it in the log instead.
            else -> {
                flogError { "yijian unclassified failure: ${e.javaClass.simpleName}: $detail" }
                str(R.string.yijian__err_generic)
            }
        }
    }

    /**
     * Splits [beforeCursor] (which ends with a trigger punctuation) into the English sentence
     * tail and the preceding context. Returns null if the tail does not look like an
     * English sentence worth translating.
     */
    /**
     * Extracts the last sentence-like tail from [content] (no trailing punctuation assumed).
     */
    /**
     * Extracts the text to translate from [content] (what the user has typed).
     *
     * The whole buffer is used when it is reasonably short; for long input only the trailing
     * sentence is taken so the request stays focused. Mixed Chinese/English is supported: the
     * model handles both, so a CJK character must never make the input "untranslatable".
     */
    private fun extractTail(content: String, requireTwoWords: Boolean = false): Pair<String, String>? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null

        // Short enough: translate the whole thing.
        if (trimmed.length <= MAX_TRANSLATE_CHARS) {
            if (!hasTranslatableContent(trimmed, requireTwoWords)) return null
            return trimmed to ""
        }

        // Long input: always take the END of what was typed, which is what the user is working
        // on. Snap to the nearest sentence start so a word is never cut in half, but only if
        // that does not throw away most of the window.
        val window = trimmed.takeLast(MAX_TRANSLATE_CHARS)
        val boundaries = listOf('.', '!', '?', '\n', '。', '！', '？', '；', ';')
        var snap = -1
        for (b in boundaries) {
            val i = window.indexOf(b)
            if (i >= 0 && (snap < 0 || i < snap)) snap = i
        }
        val candidate = if (snap in 0 until (window.length * 2 / 3)) {
            window.substring(snap + 1).trim()
        } else {
            window.trim()
        }
        if (!hasTranslatableContent(candidate, requireTwoWords)) return null
        val context = trimmed.substring(0, (trimmed.length - candidate.length).coerceAtLeast(0)).takeLast(200)
        return candidate to context
    }

    /**
     * True when [text] has enough substance to be worth a request. Chinese counts as content:
     * the user may type Chinese and ask for an English rendering, or mix both languages.
     */
    private fun hasTranslatableContent(text: String, requireTwoWords: Boolean): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        val latinLetters = trimmed.count { it.isLetter() && it.code < 0x2E7F }
        val cjkChars = trimmed.count { it.code in 0x3400..0x9FFF || it.code in 0xF900..0xFAFF }

        // Chinese input is valid content on its own (and often what the user asks about).
        if (cjkChars >= MIN_CJK_CHARS) return true
        if (latinLetters < MIN_LATIN_LETTERS) return false

        // Punctuation-triggered path: avoid firing on "Mr.", "3.", "e.g." style fragments.
        if (requireTwoWords && trimmed.none { it.isWhitespace() } && trimmed.length < 12) return false
        return true
    }






    /**
     * Returns the text to send to the model.
     *
     * Everything the user has typed goes in one request — no sentence splitting. The model reads
     * the whole message and decides how to interpret it; carving it up on the client only produced
     * partial translations. The length cap exists because the API has a hard limit, and when it is
     * hit the end of the text is kept since that is what the user is still working on.
     */
    private fun textToTranslate(text: String): String {
        val trimmed = text.trimStart(' ', '\t')
        if (trimmed.length <= MAX_TRANSLATE_CHARS) return trimmed

        val window = trimmed.takeLast(MAX_TRANSLATE_CHARS)
        val boundaries = charArrayOf('.', '!', '?', '\n', '。', '！', '？', '；', ';')
        var firstBreak = -1
        for (b in boundaries) {
            val i = window.indexOf(b)
            if (i >= 0 && (firstBreak < 0 || i < firstBreak)) firstBreak = i
        }
        // Snap to a sentence start only when that keeps most of the window.
        return if (firstBreak in 0 until (window.length * 2 / 3)) {
            window.substring(firstBreak + 1).trimStart(' ', '\t')
        } else {
            if (window.first().isLowSurrogate()) window.drop(1) else window
        }
    }

    /**
     * Returns the trailing sentence of [text] — everything after the last sentence boundary.
     * Used to pin down exactly which slice of the editor a translation should replace.
     */
    private fun extractTrailingSentence(text: String): String {
        if (text.isEmpty()) return text
        val boundaries = charArrayOf('.', '!', '?', '\n', '。', '！', '？', '；', ';')
        var last = -1
        for (b in boundaries) {
            val i = text.lastIndexOf(b)
            if (i > last) last = i
        }
        return if (last >= 0 && last < text.length - 1) text.substring(last + 1) else text
    }

    private fun applyTranslation(
        english: String,
        result: TranslationResult,
        range: TranslationRange?,
        ticket: Long,
    ) {
        // Keep the model's own punctuation as-is: no ASCII-to-CJK full stop conversion on the
        // translated text (the user asked to drop it).
        val candidates = result.translations.map { raw ->
            raw.trim()
        }.filter { it.isNotBlank() }
        check(candidates.isNotEmpty()) { "模型没有给出译文" }
        // A sentence becomes practice material when there is something to learn from it —
        // which is not the same as "the sentence had errors".
        //
        // Two distinct cases both qualify:
        //   corrections  the sentence was wrong
        //   note         the sentence was right but a native speaker would have said it
        //                differently; the note carries the better phrasing
        //
        // Excluding the second case would throw away the most useful material there is: the
        // learner already communicates correctly, and the lesson is the upgrade. A sentence
        // with neither is a sentence they already knew how to say, so it is not recorded.
        if (result.corrections.isNotEmpty() || result.note.isNotBlank()) {
            runCatching {
                teacher.record(
                    original = english,
                    corrected = result.corrected,
                    translation = candidates.first(),
                    corrections = result.corrections,
                    note = result.note,
                )
            }
        }
        this.candidates.value = candidates.mapIndexed { i, t ->
            TranslationSuggestionCandidate(
                text = t,
                correctedEnglish = result.corrected,
                originalEnglish = english,
                note = result.note,
                requestId = ticket,
                range = range,
                corrections = result.corrections,
                confidence = 1.0 - i * 0.1,
            )
        }
    }

    private fun callApiBlocking(english: String, context: String, voiceMode: Boolean = false): TranslationResult {
        val baseUrl = prefs.translation.baseUrl.get().trimEnd('/')
        val apiKey = prefs.translation.apiKey.get()
        require(apiKey.isNotBlank()) { "API key not configured" }
        val model = prefs.translation.model.get()

        val systemPrompt = """你是「译键」输入法的私人英语老师兼翻译引擎。用户是英文水平有限的中国人，输入的英文常有拼写、语法错误，或用词不符合英语口语习惯。任务：
1. corrected：给出一句自然、可直接使用的英文，保留原意、礼貌程度和情绪。根据原文或上下文明示的关系选择措辞：朋友聊天用自然口语，工作请求清楚得体。关系不明确时使用中性日常表达，不添加称呼、承诺或原文没有的信息。原句已经地道时保留原句。
2. translations：结合上下文翻译成地道中文（口语/聊天语气）。**默认给 2~3 个候选**，按贴切度排序（第一个是最推荐的），候选之间必须有真实差异，不要只改标点或语序凑数：
   - 称呼、头衔、多义词存在多种合理解读时，必须按不同理解分别给出（例如 "Doctor Gu" 既可能是「顾医生」也可能是「顾博士」，两种都要列）；
   - 语气或礼貌程度有常用变体时给出变体（如「谢谢你，顾医生」/「谢谢您，顾医生」）；
   - 只有确实不存在第二种合理译法时，才允许只给 1 个。
3. corrections：逐条列出他这句话里的问题（最多 5 条，没有就给空数组）。每条：
   - tag：问题类型，只能是「拼写」「语法」「用词」「表达」之一
   - wrong：他写错的部分（原样摘录）
   - right：应该怎么写
   - why：一句话中文说明原因或规则（尽量具体，例如「第三人称单数要加 -s」）
4. note：如果他的句子本身没有错误但不够地道，用一句话说明更自然的说法；完全没问题就留空字符串。

重要：这是键盘输入场景，用户常为图快而用小写开头、省略句号或逗号、用缩写（如 im / u / dont / wanna）。
这些**不算错误**，不要为以下情况生成 corrections：
- 首字母没有大写（句首 i、专有名词、句号后的大写等）
- 缺少或多余空格、句尾缺标点、逗号句号使用不规范
- 聊天常用缩写与口语简写（im、u、dont、cant、wanna、gonna、thx 等）
只有在拼写确实拼错（如 recieve）、语法结构错误（时态/主谓一致/缺冠词）、用词不当或表达不地道时才指出来。
corrected 字段仍给出完全规范的英文（含正确大小写和标点），供参考。

不论用户输入的是英文、中文，还是中英混杂，**永远同时给出两样东西**：
- translations：他这句话的**中文**意思（口语、自然）。输入本来就是中文时，给这句话顺口的中文说法即可，绝不要换成英文。
- corrected：表达这同一个意思时**最地道、最规范的英文**。这才是他要学的。
即：中文看 translations，英文看 corrected。两者必须在说同一件事。
中文输入时不要为语言本身挑错，corrections 只针对他写的英文部分。

严格只输出 JSON，不要输出任何其他内容：
{"corrected":"...","translations":["..."],"corrections":[{"tag":"语法","wrong":"...","right":"...","why":"..."}],"note":"..."}"""

        val userContent = buildString {
            append("上下文：${context.ifBlank { "（对话刚开始）" }}")
            append("\n\n要翻译的句子：$english")
            if (voiceMode) {
                append(
                    "\n\n【语音学习模式】用户是直接说出来的，可能不确定英文该怎么说。" +
                        "请在 corrected 里给出他真正想表达的那句话最地道的英文说法（不要只是逐字直译），" +
                        "并在 corrections 里点出关键表达/搭配问题（例如该用 take the bus 而不是 by bus、该用 look forward to 而不是 wait for）。" +
                        "why 字段请写清楚这条用法的规则或场景。"
                )
            }
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userContent))

        // Progressive budgets. If a reply still comes back truncated (finish_reason=length) or
        // unparsable, the same request is retried with a bigger budget before giving up, so the
        // user never sees a "model format" error that a retry would have fixed.
        val baseBudget = tokenBudgetFor(english)
        val budgets = listOf(baseBudget, (baseBudget * 2).coerceAtMost(6000))

        var lastProblem: String? = null
        for ((attempt, budget) in budgets.withIndex()) {
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", if (attempt == 0) 0.3 else 0.2)
                .put("max_tokens", budget)
                .put("response_format", JSONObject().put("type", "json_object"))
                // Reasoning off: on this task it doubled latency and burned 60-70% of the budget
                // without improving the result.
                .put("thinking", JSONObject().put("type", "disabled"))

            val text = postChat(baseUrl, apiKey, body)
            val choice = JSONObject(text).getJSONArray("choices").getJSONObject(0)
            val finishReason = choice.optString("finish_reason")
            val content = choice.getJSONObject("message").optString("content", "")

            val parsed = runCatching { parseResult(content, truncated = finishReason == "length") }
            if (parsed.isSuccess) return parsed.getOrThrow()

            lastProblem = parsed.exceptionOrNull()?.message
            flogError {
                "yijian: attempt ${attempt + 1}/${budgets.size} unusable " +
                    "(finish=$finishReason, ${content.length} chars): $lastProblem"
            }
        }

        // Nothing usable after retries: fall back to salvaging whatever text we can from the
        // last reply so a short, valid translation still reaches the user.
        error(lastProblem ?: "模型返回格式异常")
    }

    /**
     * True when a correction differs from the original only in letter case or punctuation/spacing
     * — shorthand typing that must not be reported as a mistake.
     */
    private fun isOnlyCasingOrPunctuation(c: CorrectionItem): Boolean {
        fun normalize(text: String): String =
            text.filter { it.isLetterOrDigit() }.lowercase()
        val wrong = normalize(c.wrong)
        val right = normalize(c.right)
        if (wrong.isEmpty()) return true
        return wrong == right
    }

    /**
     * Parses one model reply into a [TranslationResult]. Throws when the reply is unusable so the
     * caller can retry with a larger budget.
     */
    private fun parseResult(content: String, truncated: Boolean): TranslationResult {
        if (content.isBlank()) error("模型返回为空")

        val jo = runCatching { JSONObject(content) }.getOrElse {
            salvageJson(content) ?: error("模型返回格式异常")
        }

        val translationsArr = jo.optJSONArray("translations")
        val translations = if (translationsArr == null) emptyList() else
            (0 until translationsArr.length())
                .mapNotNull { i -> translationsArr.optString(i).takeIf { it.isNotBlank() } }
        if (translations.isEmpty()) {
            error(if (truncated) "回复被截断" else "模型没有给出译文")
        }

        val correctionsArr = jo.optJSONArray("corrections")
        val corrections = if (correctionsArr == null) emptyList() else {
            (0 until correctionsArr.length()).mapNotNull { i ->
                val item = correctionsArr.optJSONObject(i) ?: return@mapNotNull null
                val wrong = item.optString("wrong")
                if (wrong.isBlank()) return@mapNotNull null
                CorrectionItem(
                    tag = item.optString("tag").ifBlank { "表达" },
                    wrong = wrong,
                    right = item.optString("right"),
                    why = item.optString("why"),
                )
            }.filterNot { isOnlyCasingOrPunctuation(it) }
        }

        return TranslationResult(
            corrected = jo.optString("corrected"),
            translations = translations.take(3),
            note = jo.optString("note"),
            corrections = corrections.take(5),
        )
    }

}
