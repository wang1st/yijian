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

package dev.patrickgold.florisboard.ime.translation.pinyin

import android.content.Context
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import kotlinx.coroutines.flow.MutableStateFlow
import org.k3lp.lib.text.asK3String

/**
 * Pinyin input mode.
 *
 * While enabled, letters typed on the keyboard accumulate into [composing] instead of being
 * committed; the smartbar shows candidates for that pinyin. Picking a candidate commits the
 * Chinese word. This composes *into* the editor like any IME composition, so the user can see
 * what they are typing and can mix Chinese and English freely mid-sentence.
 */
class PinyinInput(private val context: Context) {

    val engine = PinyinEngine(context)

    /** True when the keyboard is in Chinese (pinyin) mode. */
    val active = MutableStateFlow(false)

    /** The pinyin typed so far, e.g. "nihao". */
    val composing = MutableStateFlow("")

    /** Current candidates for [composing]. */
    val candidates = MutableStateFlow<List<String>>(emptyList())

    fun toggle() {
        active.value = !active.value
        if (!active.value) {
            composing.value = ""
            candidates.value = emptyList()
        } else {
            engine.preload()
        }
    }

    fun setActive(enabled: Boolean) {
        if (active.value == enabled) return
        toggle()
    }

    /**
     * Handles a letter while in pinyin mode. Returns true when the key was consumed (it went
     * into the pinyin buffer instead of the editor).
     */
    fun onLetter(ch: Char): Boolean {
        if (!active.value) return false
        if (!ch.isLetter() || ch.code > 0x7F) return false
        composing.value += ch.lowercaseChar()
        refresh()
        return true
    }

    /** Backspace inside pinyin mode. Returns true when consumed. */
    fun onBackspace(): Boolean {
        if (!active.value) return false
        val cur = composing.value
        if (cur.isEmpty()) return false
        composing.value = cur.substring(0, cur.length - 1)
        refresh()
        return true
    }

    private fun refresh() {
        val q = composing.value
        candidates.value = if (q.isEmpty()) engine.commonCharacters() else engine.query(q)
    }

    /**
     * Commits [word] into the editor and clears the buffer.
     *
     * Not suspend on purpose: it must run inside an existing UpdateImeStateScope (the caller
     * already holds the state lock), and a nested suspend call there would deadlock.
     */
    fun commit(word: String, scope: ImeController.UpdateImeStateScope) {
        scope.emitTextDirect(word.asK3String())
        composing.value = ""
        candidates.value = emptyList()
    }

    /** Drops the pinyin buffer without committing (e.g. Enter or a mode switch). */
    fun clear() {
        composing.value = ""
        candidates.value = emptyList()
    }

    /** The raw pinyin, for use when the user wants the Latin text instead of a Chinese word. */
    fun rawInput(): String = composing.value
}
