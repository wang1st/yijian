package dev.patrickgold.florisboard.ime.translation

import android.os.Build
import android.text.Selection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.filters.SdkSuppress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranslationRangeTest {
    private val prefix = "前文🙂\r\n".repeat(160)
    private val original = "I  need a cigarattte! "
    private val chinese = "我需要一根烟。"
    private val range = TranslationRange(prefix.length, original)

    private fun withEditor(test: (EditText, InputConnection) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val field = EditText(instrumentation.targetContext)
            field.setSingleLine(false)
            field.setText(prefix + original + "后文")
            field.setSelection(range.end)
            test(field, requireNotNull(field.onCreateInputConnection(EditorInfo())))
        }
    }

    @Test fun replacesAndUndoesExactRangeAroundComposingText() = withEditor { field, ic ->
        ic.setComposingRegion(range.start + 3, range.end)
        assertTrue(ic.replaceTranslation(range, chinese))
        assertEquals(prefix + chinese + "后文", field.text.toString())
        assertTrue(ic.replaceTranslation(range.copy(text = chinese), original))
        assertEquals(prefix + original + "后文", field.text.toString())
        assertEquals(range.end, Selection.getSelectionStart(field.text))
    }

    @Test fun refusesMovedCursorAndSameLengthEdit() = withEditor { field, ic ->
        field.setSelection(range.end - 1)
        assertFalse(ic.replaceTranslation(range, chinese))
        field.text.replace(range.start, range.start + 1, "X")
        field.setSelection(range.end)
        assertFalse(ic.replaceTranslation(range, chinese))
        assertEquals(prefix + "X" + original.drop(1) + "后文", field.text.toString())
    }

    @Test fun ignoredDeletionCannotAppendTranslationOrMoveTheCaret() = withEditor { field, ic ->
        val ignoresSelection = object : InputConnectionWrapper(ic, false) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) = true
            override fun setSelection(start: Int, end: Int): Boolean = error("unexpected absolute selection")
        }
        assertFalse(ignoresSelection.replaceTranslation(range, chinese))
        assertEquals(prefix + original + "后文", field.text.toString())
    }

    @Test fun onlyCurrentParagraphIsEligible() {
        for (separator in listOf("\n", "\r", "\r\n", "\u2028", "\u2029")) {
            assertEquals(original, ("上一段" + separator + original).currentParagraph())
            assertEquals("", ("上一段" + separator).currentParagraph())
        }
    }

    @Test fun canCommitExactlySelectedSourceWithoutReadingInsideABatch() = withEditor { field, ic ->
        field.setSelection(range.start, range.end)
        val host = object : InputConnectionWrapper(ic, false) {
            override fun beginBatchEdit(): Boolean = error("reads cannot depend on an open batch")
            override fun setSelection(start: Int, end: Int): Boolean = error("already selected")
        }
        assertTrue(host.replaceTranslation(range, chinese))
        assertEquals(prefix + chinese + "后文", field.text.toString())
    }

    @Test fun rejectedDeleteInDocumentLeavesParagraphAndCaretAlone() = withEditor { field, ic ->
        val host = object : InputConnectionWrapper(ic, false) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) = false
            override fun setSelection(start: Int, end: Int): Boolean = error("must not jump to a window offset")
            override fun endBatchEdit(): Boolean = error("no unmatched batch")
        }
        assertFalse(host.replaceTranslation(range, chinese))
        assertEquals(prefix + original + "后文", field.text.toString())
        assertEquals(range.end, field.selectionStart)
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun reportExampleWithInaccurateSurroundingOffset() = withEditor { field, ic ->
        val source = "thats really wondaful"
        field.setText(prefix + source + "后文")
        field.setSelection(prefix.length + source.length)
        val host = object : InputConnectionWrapper(ic, false) {
            override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
                val result = ic.getSurroundingText(beforeLength, afterLength, flags) ?: return null
                return SurroundingText(result.text, result.selectionStart, result.selectionEnd, 0)
            }
            override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
            override fun setSelection(start: Int, end: Int): Boolean = error("unsafe window offset")
        }
        val window = requireNotNull(host.translationWindow())
        assertTrue(window.start < field.selectionStart)
        val captured = TranslationRange(window.start - source.length, source, window.before.dropLast(source.length).takeLast(CONTEXT_CHARS), "后文")
        assertTrue(host.replaceTranslation(captured, chinese, reportedCaret = field.selectionStart))
        assertEquals(prefix + chinese + "后文", field.text.toString())
        assertEquals(prefix.length + chinese.length, field.selectionStart)
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test fun confirmedModernRangeUsesOnlyOneRangeEdit() = withEditor { field, ic ->
        var writes = 0
        val host = object : InputConnectionWrapper(ic, false) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = error("unexpected delete")
            override fun setSelection(start: Int, end: Int): Boolean = error("unexpected selection")
            override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int,
                                     textAttribute: android.view.inputmethod.TextAttribute?): Boolean {
                writes++
                return ic.replaceText(start, end, text, newCursorPosition, textAttribute)
            }
        }
        assertTrue(host.replaceTranslation(range, chinese, reportedCaret = range.end))
        assertEquals(1, writes)
        assertEquals(prefix + chinese + "后文", field.text.toString())
    }

    @Test fun acceptedSpanKeepsRepeatAtDifferentPositionAndLeadingSpaces() {
        val text = chinese + "  " + chinese + original
        val window = EditorWindow(text, "", prefix.length + text.length)
        val previous = listOf(TranslationRange(prefix.length, chinese))
        assertEquals("  " + chinese + original, window.untranslatedSuffix(previous))
    }
}
