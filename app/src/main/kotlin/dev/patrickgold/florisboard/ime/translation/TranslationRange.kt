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
    if (translationWindow(range.text.length)?.atEndOf(range) != true) {
        Log.e(tag, "pre-check failed: editor does not end with the source at ${range.start}")
        return false
    }
    beginBatchEdit()
    try {
        if (!finishComposingText()) {
            Log.e(tag, "finishComposingText rejected")
            return false
        }
        if (!setSelection(range.start, range.end)) {
            Log.e(tag, "setSelection rejected")
            return false
        }
        // Verify the selection directly. translationWindow(0) returns null on editors that
        // report nothing for a zero-length window, which used to abort a perfectly good replace.
        val selected = getSelectedText(0)?.toString()
        if (selected != range.text) {
            Log.e(tag, "selection mismatch: got '${selected}' expected '${range.text}'")
            runCatching { setSelection(range.end, range.end) }
            return false
        }
        if (!commitText(replacement, 1)) {
            // Some editors reject the commit while leaving the source highlighted; put the cursor
            // back at the end of the source so the user is not left staring at a stray selection.
            Log.e(tag, "commitText rejected")
            runCatching { setSelection(range.end, range.end) }
            return false
        }
    } finally {
        endBatchEdit()
    }

    // Verify with getTextBeforeCursor, which reads synchronously. The surrounding-text window can
    // still describe the pre-commit state right after endBatchEdit on some editors, which made a
    // successful replace look like a failure ("未能确认替换结果") and left the source selected.
    val before = getTextBeforeCursor(replacement.length + 2, 0)?.toString().orEmpty()
    if (before.endsWith(replacement)) return true

    // Fall back to the window (covers editors that only report through surrounding text).
    val confirmed = translationWindow(replacement.length)?.atEndOf(range.copy(text = replacement)) == true
    if (!confirmed) Log.e(tag, "post-commit verification failed")
    return confirmed
}
