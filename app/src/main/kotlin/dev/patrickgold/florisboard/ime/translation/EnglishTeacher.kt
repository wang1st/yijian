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

import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single sentence the user produced, kept so the periodic report can grade it.
 */
data class PracticeEntry(
    val timestamp: Long,
    val original: String,
    val corrected: String,
    val translation: String,
    val corrections: List<CorrectionItem>,
    val note: String,
) {
    /** Number of English words, used for the report threshold. */
    val wordCount: Int
        get() = original.split(Regex("\\s+")).count { it.any { c -> c.isLetter() } }

    fun toJson(): JSONObject = JSONObject().apply {
        put("ts", timestamp)
        put("original", original)
        put("corrected", corrected)
        put("translation", translation)
        put("note", note)
        put("corrections", JSONArray().apply {
            corrections.forEach { c ->
                put(JSONObject().apply {
                    put("tag", c.tag)
                    put("wrong", c.wrong)
                    put("right", c.right)
                    put("why", c.why)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): PracticeEntry {
            val arr = o.optJSONArray("corrections")
            val items = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    CorrectionItem(
                        tag = it.optString("tag"),
                        wrong = it.optString("wrong"),
                        right = it.optString("right"),
                        why = it.optString("why"),
                    )
                }
            }
            return PracticeEntry(
                timestamp = o.optLong("ts"),
                original = o.optString("original"),
                corrected = o.optString("corrected"),
                translation = o.optString("translation"),
                corrections = items,
                note = o.optString("note"),
            )
        }
    }
}

/**
 * The "private English teacher": collects every translated sentence, and once the configured
 * number of words has accumulated, writes a Markdown report (what was wrong, the correct usage,
 * and the recurring patterns) and emails it to the configured address.
 */
class EnglishTeacher {
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards the practice file and the word counter against concurrent updates. */
    private val recordMutex = Mutex()

    /** How many words have been collected since the last report. */
    val pendingWordCount = MutableStateFlow(0)

    private val storeFile: File by lazy {
        File(dev.patrickgold.florisboard.FlorisApplication.getHack().filesDir, "yijian_practice.jsonl")
    }

    init {
        scope.launch {
            recordMutex.withLock {
                pendingWordCount.value = readEntries().sumOf { it.wordCount }
            }
        }
    }

    fun record(
        original: String,
        corrected: String,
        translation: String,
        corrections: List<CorrectionItem>,
        note: String,
    ) {
        if (original.isBlank()) return
        val entry = PracticeEntry(
            timestamp = System.currentTimeMillis(),
            original = original,
            corrected = corrected,
            translation = translation,
            corrections = corrections,
            note = note,
        )
        // Everything below runs under one lock: concurrent records used to read the same
        // stale counter and silently drop words.
        recordMutex.withLock {
            runCatching {
                storeFile.appendText(entry.toJson().toString() + "\n")
            }.onFailure { flogError { "yijian teacher: failed to record: ${it.message}" } }

            // Always derive the total from what is actually on disk, so the counter can never
            // drift from the stored records.
            val total = readEntries().sumOf { it.wordCount }
            pendingWordCount.value = total

            val threshold = prefs.teacher.reportWordThreshold.get()
            if (threshold > 0 && total >= threshold) {
                generateAndSendReport()
            }
        }
    }

    /** All recorded entries (oldest first). */
    suspend fun readEntries(): List<PracticeEntry> = withContext(Dispatchers.IO) {
        if (!storeFile.exists()) return@withContext emptyList()
        storeFile.readLines().mapNotNull { line ->
            runCatching { PracticeEntry.fromJson(JSONObject(line)) }.getOrNull()
        }
    }

    /**
     * Builds the report from the accumulated entries, sends it if email is configured, and —
     * only when the send succeeded (or email is disabled) — clears the accumulated entries so the
     * user is not charged twice for the same content.
     */
    /** Result of the most recent report attempt, for the UI to show. */
    val reportStatus = MutableStateFlow<String?>(null)

    /**
     * Builds the report from what has been recorded and emails it.
     *
     * @param force when true the report is sent even if the word threshold has not been reached
     *  (used by the manual "send report now" action).
     */
    fun generateAndSendReport(force: Boolean = false) {
        scope.launch {
            // Snapshot under the lock; the SMTP round trip happens outside it so recording a new
            // sentence never waits on the network.
            var entries: List<PracticeEntry> = emptyList()
            var words = 0
            recordMutex.withLock {
                entries = readEntries()
                words = entries.sumOf { it.wordCount }
            }
            if (entries.isEmpty()) {
                reportStatus.value = "还没有练习记录"
                return@launch
            }
            if (!force && words < prefs.teacher.reportWordThreshold.get()) {
                val need = prefs.teacher.reportWordThreshold.get() - words
                reportStatus.value = "已积累 $words 词，还差 $need 词"
                return@launch
            }

            reportStatus.value = "正在生成报告…"
            val md = buildReport(entries)
            val mailed = Mailer.send(
                context = dev.patrickgold.florisboard.FlorisApplication.getHack(),
                subject = "译键 · 英语学习报告（${entries.size} 句 / $words 词）",
                body = md,
            )
            if (mailed) {
                recordMutex.withLock {
                    // Only drop what was actually reported, so sentences typed during the send
                    // are not lost.
                    val reported = entries.map { it.timestamp }.toSet()
                    val remaining = readEntries().filterNot { it.timestamp in reported }
                    storeFile.writeText("")
                    if (remaining.isNotEmpty()) {
                        remaining.forEach { storeFile.appendText(it.toJson().toString() + "\n") }
                    }
                    pendingWordCount.value = remaining.sumOf { it.wordCount }
                }
                reportStatus.value = "报告已发送（${entries.size} 句 / $words 词）"
            } else {
                // Keep the entries: the user must be able to retry without losing practice data.
                reportStatus.value = "发送失败：${Mailer.lastError ?: "未知原因"}"
            }
        }
    }

    private fun buildReport(entries: List<PracticeEntry>): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val totalWords = entries.sumOf { it.wordCount }
        val allCorrections = entries.flatMap { it.corrections }
        val byTag = allCorrections.groupBy { it.tag.ifBlank { "其他" } }
            .toList().sortedByDescending { it.second.size }
        val sentencesWithIssues = entries.count { it.corrections.isNotEmpty() }

        return buildString {
            appendLine("# 译键 · 英语学习报告")
            appendLine()
            appendLine("生成时间：${df.format(Date())}")
            appendLine()
            appendLine("## 本期概况")
            appendLine()
            appendLine("- 练习句数：**${entries.size}** 句")
            appendLine("- 英文词数：**$totalWords** 词")
            appendLine("- 有问题的句子：**$sentencesWithIssues** 句")
            appendLine(
                "- 正确率：" + if (entries.isEmpty()) "-" else
                    "%.0f%%".format((entries.size - sentencesWithIssues) * 100.0 / entries.size)
            )
            appendLine()
            if (byTag.isNotEmpty()) {
                appendLine("## 错误分布")
                appendLine()
                appendLine("| 类型 | 次数 |")
                appendLine("| --- | --- |")
                byTag.forEach { (tag, items) -> appendLine("| $tag | ${items.size} |") }
                appendLine()
                appendLine("## 最需要练的 5 个点")
                appendLine()
                byTag.flatMap { it.second }
                    .groupBy { it.why.ifBlank { it.right } }
                    .entries.sortedByDescending { it.value.size }
                    .take(5)
                    .forEach { (why, items) ->
                        val sample = items.first()
                        appendLine("1. **${items.size} 次**：`${sample.wrong}` → `${sample.right}`")
                        if (why.isNotBlank()) appendLine("   - $why")
                    }
                appendLine()
            }
            appendLine("## 逐句批改")
            appendLine()
            entries.forEachIndexed { i, e ->
                appendLine("### ${i + 1}. ${df.format(Date(e.timestamp))}")
                appendLine()
                appendLine("- ✍️ 你写的：`${e.original}`")
                appendLine("- ✅ 规范说法：`${e.corrected}`")
                if (e.translation.isNotBlank()) appendLine("- 中文：${e.translation}")
                if (e.corrections.isNotEmpty()) {
                    appendLine("- 问题：")
                    e.corrections.forEach { c ->
                        appendLine("  - **${c.tag}** `${c.wrong}` → `${c.right}`" +
                            if (c.why.isNotBlank()) "（${c.why}）" else "")
                    }
                }
                if (e.note.isNotBlank()) appendLine("- 💡 老师点评：${e.note}")
                appendLine()
            }
            appendLine("---")
            appendLine()
            appendLine("_由译键自动生成。坚持用英文输入，报告会越来越短。_")
        }
    }


}
