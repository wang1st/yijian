package dev.patrickgold.florisboard.ime.translation.review

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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

    private fun waitUntil(description: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!check()) {
            assertTrue("Timed out waiting for $description", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(50)
        }
    }

    private fun descendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { yieldAll(descendants(it)) }
        }
    }

    private fun tapWord(word: String) {
        val bounds = Rect()
        val targetPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        var visible = emptyList<String>()
        var foreground = ""
        try {
            waitUntil("visible word tile '$word'") {
                val root = automation.rootInActiveWindow ?: return@waitUntil false
                foreground = root.packageName?.toString().orEmpty()
                if (foreground != targetPackage) return@waitUntil false
                // Compose exposes virtual descendants; provider text searches can return empty
                // even when the individual nodes and their on-screen bounds are available.
                val nodes = descendants(root).filter { it.isVisibleToUser }.toList()
                visible = nodes.mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
                val tile = nodes.firstOrNull {
                    it.text?.toString() == word || it.contentDescription?.toString() == word
                } ?: return@waitUntil false
                tile.getBoundsInScreen(bounds)
                !bounds.isEmpty
            }
        } catch (timeout: AssertionError) {
            throw AssertionError("${timeout.message}; foreground=$foreground, visible labels=$visible", timeout)
        }
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue("Touch delivery failed for '$word'", automation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
        automation.waitForIdle(100, 3000)
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
            fun expectAnswer(expected: String) = waitUntil("assembled answer '$expected'") {
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
            assertFalse(descendants(root).any { it.isEditable })
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
