package dev.patrickgold.florisboard.ime.translation.review

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.app.settings.review.TileState
import dev.patrickgold.florisboard.app.settings.review.WordBankAnswer
import dev.patrickgold.florisboard.app.settings.review.rememberTileState
import org.junit.Assert.*
import org.junit.Test

class WordBankTest {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun waitUntil(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!check()) {
            assertTrue("Timed out waiting for word bank", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(50)
        }
    }

    private fun tapWord(word: String) = waitUntil {
        val root = automation.rootInActiveWindow ?: return@waitUntil false
        root.findAccessibilityNodeInfosByText(word).any { match ->
            if (match.text?.toString() != word) return@any false
            var node: AccessibilityNodeInfo? = match
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    @Test fun tapUndoAndRestoreWithoutTextEntry() {
        lateinit var current: TileState
        ActivityScenario.launch(ComponentActivity::class.java).use { screen ->
            fun render() {
                screen.onActivity { activity ->
                    activity.setContent {
                        current = rememberTileState("test-task", "Please call me.")
                        WordBankAnswer(current, editable = true)
                    }
                }
            }
            fun expectAnswer(expected: String) = waitUntil {
                var matches = false
                screen.onActivity { matches = current.answer() == expected }
                matches
            }
            render()
            tapWord("Please")
            expectAnswer("Please")
            tapWord("call")
            expectAnswer("Please call")
            val bank = current.tiles.toList()
            screen.recreate()
            render()
            tapWord("Please")
            expectAnswer("call")
            assertEquals(bank, current.tiles)
            tapWord("Please")
            expectAnswer("call Please")
            val root = requireNotNull(automation.rootInActiveWindow)
            fun hasEditor(node: AccessibilityNodeInfo): Boolean =
                node.isEditable || (0 until node.childCount).any { node.getChild(it)?.let(::hasEditor) == true }
            assertFalse(hasEditor(root))
        }
    }

    @Test fun duplicatesContractionsAndNumbersAreSolvableAcrossShuffles() {
        val expected = listOf("I", "can’t", "re-check", "the", "time", "the", "fare", "is", "£2.50", "at", "8:30")
        repeat(20) { seed ->
            val state = TileState("I can’t re-check the time; the fare is £2.50 at 8:30.", seed)
            val conjunction = state.tiles.indices.first { state.tiles[it] !in expected }
            state.place(conjunction)
            state.hint()
            assertEquals("I", state.answer())
            val first = state.placed.single()
            state.place(first)
            assertEquals(1, state.placed.size)
            repeat(expected.size - 1) { state.hint() }
            assertEquals(expected.joinToString(" "), state.answer())
            assertEquals(expected.size, state.placed.distinct().size)
            assertEquals(2, state.tiles.size - state.placed.size)
        }
    }
}
