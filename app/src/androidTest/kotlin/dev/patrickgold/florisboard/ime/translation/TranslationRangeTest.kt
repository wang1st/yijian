package dev.patrickgold.florisboard.ime.translation

import android.text.Selection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranslationRangeTest {
    private val prefix = "前文🙂".repeat(160)
    private val original = "I  need a cigarattte! "
    private val chinese = "我需要一根烟。"
    private val range = TranslationRange(prefix.length, original)

    private fun withEditor(test: (EditText, InputConnection) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val field = EditText(instrumentation.targetContext)
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

    @Test fun ignoredSelectionCannotAppendTranslation() = withEditor { field, ic ->
        val ignoresSelection = object : InputConnectionWrapper(ic, false) {
            override fun setSelection(start: Int, end: Int) = true
        }
        assertFalse(ignoresSelection.replaceTranslation(range, chinese))
        assertEquals(prefix + original + "后文", field.text.toString())
    }

    @Test fun acceptedSpanKeepsRepeatAtDifferentPositionAndLeadingSpaces() {
        val text = chinese + "  " + chinese + original
        val window = EditorWindow(text, "", prefix.length + text.length)
        val previous = listOf(TranslationRange(prefix.length, chinese))
        assertEquals("  " + chinese + original, window.untranslatedSuffix(previous))
    }
}
