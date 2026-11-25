package dev.patrickgold.florisboard.ime.keyboard3

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.k3lp.lib.text.asK3String
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference

@RunWith(AndroidJUnit4::class)
class DocumentTypingTest {
    private val controller get() = FlorisApplication.getHack().imeController

    private fun withEditor(multiline: Boolean = true, test: ImeController.UpdateImeStateScope.(EditText, List<String>, List<Int>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val field = EditText(instrumentation.targetContext)
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            field.setText("段落🙂\n".repeat(100))
            field.setSelection(field.length())
            val info = EditorInfo()
            val target = requireNotNull(field.onCreateInputConnection(info))
            info.imeOptions = if (multiline) EditorInfo.IME_ACTION_NONE else EditorInfo.IME_ACTION_SEARCH
            val writes = mutableListOf<String>()
            val actions = mutableListOf<Int>()
            val host = object : InputConnectionWrapper(target, false) {
                override fun setSelection(start: Int, end: Int): Boolean = error("Unexpected absolute caret write")
                override fun setComposingRegion(start: Int, end: Int): Boolean = error("Unexpected context rewrite")
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    writes.add(text.toString())
                    return super.commitText(text, newCursorPosition)
                }
                override fun performEditorAction(editorAction: Int): Boolean {
                    actions.add(editorAction)
                    return true
                }
            }
            controller.updateStateBlocking {
                handleStartInputView(WeakReference<InputConnection>(host), FlorisEditorInfo.wrap(info))
                try {
                    test(field, writes, actions)
                } finally {
                    handleFinishInputView()
                }
            }
        }
    }

    @Test fun newlineAndTypingNeverReplayThePreviousLineWithDelayedSelection() = withEditor { field, writes, _ ->
        val prefix = field.text.toString()
        handleUpdateSelection(K3TextRange(4)) // viewport-local offset in a long document
        val sequence = "if if if\nif\nif"
        for (ch in sequence) {
            if (ch == '\n') emitDescriptor(ImeActions.Enter) else emitText(ch.toString().asK3String())
        }
        assertEquals(sequence.map { it.toString() }, writes)
        assertEquals(prefix + sequence, field.text.toString())
        assertEquals(field.length(), field.selectionStart)

        field.setSelection(prefix.length, prefix.length + 2) // edit before the selection callback arrives
        emitText("OK".asK3String())
        assertEquals(prefix + "OK if if\nif\nif", field.text.toString())
    }

    @Test fun shiftIsOneShotAndRapidTapsCancel() = withEditor { _, _, _ ->
        cycleInputShiftState(false)
        assertEquals(InputShiftState.SHIFTED_MANUAL, state.flags.inputShiftState)
        cycleInputShiftState(true)
        assertEquals(InputShiftState.UNSHIFTED, state.flags.inputShiftState)
        cycleInputShiftState(false)
        emitText("I".asK3String())
        assertEquals(InputShiftState.UNSHIFTED, state.flags.inputShiftState)
    }

    @Test fun enterStillPerformsSingleLineSearch() = withEditor(false) { _, writes, actions ->
        emitDescriptor(ImeActions.Enter)
        assertTrue(writes.isEmpty())
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), actions)
    }

    @Test fun returnAndTranslationHaveSeparateTouchTargetsOnBothLetterLayers() = runBlocking {
        val model = withTimeout(15000) { controller.activeState.first { it.model.layersByForm.touch.isNotEmpty() }.model }
        for (enabled in listOf(false, true)) {
            val options = TouchModelOptions.Default.copy(showTranslationKey = enabled)
            val keyboard = controller.touchModelCache.getOrComputeFor(model, options).selectKeyboard(70)
            for (id in listOf(ImeLayerIds.Base, ImeLayerIds.Shift)) {
                val keys = keyboard.layers.getValue(id).keys
                val enter = keys.single { it.attrs.output == ImeActions.Enter }
                val translate = keys.filter { it.attrs.output == ImeActions.Translate }
                assertEquals(if (enabled) 1 else 0, translate.size)
                if (enabled) assertTrue(enter.bounds.right <= translate.single().bounds.left)
            }
        }
    }
}
