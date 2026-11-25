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

import android.os.Build
import android.util.Log
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** UTF-16 positions in the editor, including the offset of a truncated text window. */
data class TranslationRange(
    val start: Int,
    val text: String,
    val left: String = "",
    val right: String? = null,
) {
    val end: Int get() = start + text.length
}

internal data class EditorWindow(
    val before: String,
    val selected: String,
    val start: Int,
) {
    val offset: Int get() = start - before.length

    fun atEndOf(range: TranslationRange) =
        selected.isEmpty() && start == range.end && before.endsWith(range.text)

    /** Only skip an accepted translation when it still occupies its recorded position. */
    fun untranslatedSuffix(history: List<TranslationRange>): String {
        var from = 0
        for (span in history) {
            val left = maxOf(offset, span.start)
            if (left >= span.end || span.end > start) continue
            if (before.regionMatches(left - offset, span.text, left - span.start, span.end - left)) {
                from = maxOf(from, span.end - offset)
            }
        }
        return before.substring(from)
    }
}

internal const val TRANSLATION_WINDOW = 440
internal const val CONTEXT_CHARS = 32

/** Paragraph boundaries are explicit editor characters; soft wrapping does not split a sentence. */
internal fun String.currentParagraph(): String = substring(lastIndexOfAny(charArrayOf('\n', '\r', '\u2028', '\u2029')) + 1)

internal fun InputConnection.translationWindow(reportedCaret: Int? = null): EditorWindow? {
    val limit = TRANSLATION_WINDOW
    fun window(text: CharSequence?, offset: Int, start: Int, end: Int): EditorWindow? {
        if (text == null || offset < 0 || start !in 0..text.length || end !in 0..text.length) return null
        val left = minOf(start, end)
        return EditorWindow(
            text.subSequence((left - limit).coerceAtLeast(0), left).toString(),
            text.subSequence(left, maxOf(start, end)).toString(),
            offset + left,
        )
    }
    val surrounding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSurroundingText(limit, 0, 0)?.let { window(it.text, it.offset, it.selectionStart, it.selectionEnd) }
    } else null
    if (surrounding != null && (reportedCaret == null || surrounding.start == reportedCaret)) return surrounding
    val e = getExtractedText(ExtractedTextRequest().apply { hintMaxChars = limit + 1 }, 0)
    val extracted = e?.takeIf { it.partialStartOffset < 0 }?.let {
        window(it.text, it.startOffset, it.selectionStart, it.selectionEnd)
    }
    return if (extracted != null && (surrounding == null || extracted.start == reportedCaret)) extracted else surrounding
}

internal fun EditorWindow.matchesSource(range: TranslationRange): Boolean =
    (atEndOf(range) && before.endsWith(range.left + range.text)) ||
        (start == range.start && selected == range.text && before.endsWith(range.left))

private fun InputConnection.before(count: Int): String? =
    getTextBeforeCursor(count, 0)?.toString()?.takeLast(count)

private fun InputConnection.after(): String? =
    getTextAfterCursor(CONTEXT_CHARS, 0)?.toString()?.take(CONTEXT_CHARS)

private fun rejected(stage: String): Boolean {
    Log.w("YIJIAN-REPLACE", stage)
    return false
}

/** Verify the two sides of the caret, even when the editor only reports paragraph-local offsets. */
private fun InputConnection.hasEdges(left: String, right: String): Boolean =
    getSelectedText(0).isNullOrEmpty() && before(left.length.coerceAtLeast(1)) == left && after() == right

internal fun InputConnection.replaceTranslation(
    range: TranslationRange,
    replacement: String,
    reportedCaret: Int? = null,
    singleLine: Boolean = false,
): Boolean {
    val window = translationWindow(reportedCaret) ?: return rejected("source window unavailable")
    if (!window.matchesSource(range) || range.text.isEmpty()) return rejected("source or caret changed")
    val selected = window.selected.isNotEmpty()
    val count = if (selected) 0 else range.text.length
    val leftAndSource = before(count + CONTEXT_CHARS) ?: return rejected("left context unavailable")
    val right = after() ?: return rejected("right context unavailable")
    if ((!selected && !leftAndSource.endsWith(range.text)) || (range.right != null && right != range.right)) {
        return rejected("live source or right context changed")
    }
    val left = leftAndSource.dropLast(count)
    if (!left.endsWith(range.left)) return rejected("left context changed")

    // A selected source can be committed directly. Re-read after finalising composition:
    // custom editors may move the caret when their final composing word is committed.
    if (!finishComposingText()) return rejected("composition unavailable")
    if (selected) {
        if (getSelectedText(0)?.toString() != range.text || translationWindow(reportedCaret)?.matchesSource(range) != true ||
            before(CONTEXT_CHARS) != left || after() != right) return rejected("selection changed")
        commitText(replacement, 1)
    } else {
        if (!hasEdges(left + range.text, right) || translationWindow(reportedCaret)?.atEndOf(range) != true) {
            return rejected("caret moved after composition")
        }
        // Only use absolute Android commands if an independent editor selection and an
        // extracted text snapshot both confirm the same position and literal source.
        val absolute = reportedCaret == range.end && confirmedExtractedRange(range)
        if (absolute && Build.VERSION.SDK_INT >= 34) {
            replaceText(range.start, range.end, replacement, 1, null)
        } else {
            if (range.text.currentParagraph() != range.text) return rejected("relative edit crosses paragraphs")
            val indistinguishableTail = left.isNotEmpty() && (left + range.text).endsWith(left)
            if (indistinguishableTail && !absolute) return rejected("ambiguous relative deletion")
            deleteSurroundingText(range.text.length, 0)
            if (hasEdges(left, right) && (!indistinguishableTail || translationWindow()?.start == range.start)) {
                // Do not batch the read with the delete: some editors publish changes only
                // at endBatchEdit. Never insert until removal at this caret is observable.
                val submitted = commitText(replacement, 1)
                if (!submitted && hasEdges(left, right)) {
                    commitText(range.text, 1)
                    return rejected("translation rejected; attempted to restore original")
                }
            } else if (singleLine && absolute && hasEdges(left + range.text, right)) {
                if (!setSelection(range.start, range.end) ||
                    getSelectedText(0)?.toString() != range.text ||
                    before(CONTEXT_CHARS) != left || after() != right) {
                    return rejected("single-line selection not confirmed")
                }
                commitText(replacement, 1)
            } else {
                return rejected("relative deletion not confirmed")
            }
        }
    }
    if (hasEdges(left + replacement, right)) return true
    return rejected("replacement not confirmed; no second write")
}

private fun InputConnection.confirmedExtractedRange(range: TranslationRange): Boolean {
    val e = getExtractedText(ExtractedTextRequest().apply { hintMaxChars = TRANSLATION_WINDOW }, 0) ?: return false
    val text = e.text ?: return false
    val end = e.selectionEnd
    val start = end - range.text.length
    return e.partialStartOffset < 0 && e.startOffset >= 0 && e.selectionStart == end &&
        start >= 0 && end <= text.length && e.startOffset + end == range.end &&
        text.subSequence(start, end).toString() == range.text
}
