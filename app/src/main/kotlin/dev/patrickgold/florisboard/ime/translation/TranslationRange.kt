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
data class TranslationRange(val start: Int, val text: String) {
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

internal fun InputConnection.translationWindow(limit: Int): EditorWindow? {
    fun window(text: CharSequence?, offset: Int, start: Int, end: Int): EditorWindow? {
        if (text == null || offset < 0 || start !in 0..text.length || end !in 0..text.length) return null
        val left = minOf(start, end)
        return EditorWindow(
            text.subSequence((left - limit).coerceAtLeast(0), left).toString(),
            text.subSequence(left, maxOf(start, end)).toString(),
            offset + left,
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSurroundingText(limit, 0, 0)?.let {
            window(it.text, it.offset, it.selectionStart, it.selectionEnd)?.let { result -> return result }
        }
    }
    val request = ExtractedTextRequest().apply { hintMaxChars = limit + 1 }
    val extracted = getExtractedText(request, 0) ?: return null
    if (extracted.partialStartOffset >= 0) return null
    return window(extracted.text, extracted.startOffset, extracted.selectionStart, extracted.selectionEnd)
}

/** Commit once, only after the editor confirms that the exact original range is selected. */
internal fun InputConnection.replaceTranslation(range: TranslationRange, replacement: String): Boolean {
    val tag = "YIJIAN-REPLACE"

    // Primary strategy: count the delete length from the LIVE text before the cursor and use
    // deleteSurroundingText. Absolute positions (setSelection) drift in multi-line editors such
    // as WPS: their internal offsets count newlines differently from what getSurroundingText
    // reports, so selection landed on the wrong span and the cursor jumped around.
    val liveBefore = getTextBeforeCursor(range.text.length + 80, 0)?.toString()
    if (liveBefore == null) {
        Log.e(tag, "cannot read text before cursor")
        return false
    }
    var matchLen = commonSuffixLength(liveBefore, range.text)
    // Trailing punctuation/spacing can differ (typed punctuation, editor normalisation); allow
    // a small tail trim so the core sentence still matches.
    if (matchLen < range.text.length / 2) {
        val trimmedText = range.text.trimEnd(' ', '\t', '\n', '\r', '.', '!', '?', '。', '！', '？')
        val trimmedLen = commonSuffixLength(liveBefore, trimmedText)
        if (trimmedLen >= trimmedText.length / 2 && trimmedLen >= range.text.length / 2) {
            matchLen = trimmedLen
        }
    }
    if (matchLen == 0 || matchLen < range.text.length / 2) {
        Log.e(tag, "source no longer at cursor: matched $matchLen of ${range.text.length}")
        return false
    }

    beginBatchEdit()
    try {
        finishComposingText()
        if (!deleteSurroundingText(matchLen, 0)) {
            // Some editors silently ignore deleteSurroundingText (WeChat). Fall back to the
            // absolute-position selection path, which works in single-line editors.
            endBatchEdit()
            return replaceViaSelectionFallback(range, replacement)
        }
        if (!commitText(replacement, 1)) {
            Log.e(tag, "commitText rejected after delete")
            return false
        }
    } finally {
        endBatchEdit()
    }

    val after = getTextBeforeCursor(replacement.length + 2, 0)?.toString().orEmpty()
    if (after.endsWith(replacement)) return true
    Log.e(tag, "post-commit verification failed")
    return false
}

/** Absolute-position replacement for editors that ignore deleteSurroundingText. */
private fun InputConnection.replaceViaSelectionFallback(range: TranslationRange, replacement: String): Boolean {
    val tag = "YIJIAN-REPLACE"
    if (!setSelection(range.start, range.end)) {
        Log.e(tag, "fallback setSelection rejected")
        return false
    }
    if (!commitText(replacement, 1)) {
        Log.e(tag, "fallback commitText rejected")
        runCatching { setSelection(range.end, range.end) }
        return false
    }
    val after = getTextBeforeCursor(replacement.length + 2, 0)?.toString().orEmpty()
    return after.endsWith(replacement)
}

/** Length of the suffix shared by [a] and [b], walking backwards from both tails. */
private fun commonSuffixLength(a: String, b: String): Int {
    var i = a.length
    var j = b.length
    while (i > 0 && j > 0 && a[i - 1] == b[j - 1]) {
        i--
        j--
    }
    return a.length - i
}
