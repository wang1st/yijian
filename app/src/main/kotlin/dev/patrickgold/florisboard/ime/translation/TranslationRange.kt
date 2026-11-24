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
    if (translationWindow(range.text.length)?.atEndOf(range) != true) return false
    beginBatchEdit()
    try {
        if (!finishComposingText() || !setSelection(range.start, range.end)) return false
        val selection = translationWindow(0)
        if (selection?.start != range.start || selection.selected != range.text) return false
        if (!commitText(replacement, 1)) {
            // Some editors reject the command while leaving the source highlighted.
            val after = translationWindow(0)
            if (after?.start == range.start && after.selected == range.text) {
                setSelection(range.end, range.end)
            }
            return false
        }
    } finally {
        endBatchEdit()
    }
    return translationWindow(replacement.length)?.atEndOf(range.copy(text = replacement)) == true
}
