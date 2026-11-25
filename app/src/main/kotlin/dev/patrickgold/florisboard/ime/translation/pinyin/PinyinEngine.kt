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
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.util.concurrent.atomic.AtomicReference

/**
 * Pinyin dictionary and candidate generator.
 *
 * Assets file format (`pinyin_dict.txt`), one entry per line:
 * ```
 * nihao,nh,你好,725
 * ```
 * where the fields are compact pinyin, initials, the word, and a frequency. Both the compact
 * form (`nihao`) and the apostrophe form (`ni'hao`) are present, so lookup does not need to
 * normalise the query.
 *
 * The dictionary is loaded lazily on a background thread; [query] returns an empty list until
 * loading finishes, which keeps the first keystroke from blocking on 5 MB of parsing.
 */
class PinyinEngine(private val context: Context) {

    /** Exact pinyin -> [(word, freq)], and initials -> [(word, freq)]. */
    private class Index(
        val byFullPinyin: Map<String, List<Entry>>,
        val byInitials: Map<String, List<Entry>>,
        val singleChars: List<Entry>,
        val singleByPinyin: Map<String, List<Entry>>,
    ) {
        /** Sorted pinyin keys, enabling binary-search prefix lookup instead of a full scan. */
        val sortedKeys: Array<String> by lazy {
            byFullPinyin.keys.sorted().toTypedArray()
        }

        /** Sorted single-character pinyin keys for the tail slots of the candidate row. */
        val sortedSingleKeys: Array<String> by lazy {
            singleByPinyin.keys.sorted().toTypedArray()
        }
    }

    data class Entry(val word: String, val freq: Int)

    private val indexRef = AtomicReference<Index?>(null)

    @Volatile
    private var loading = false

    val isReady: Boolean get() = indexRef.get() != null

    fun preload() {
        if (isReady || loading) return
        loading = true
        Thread({
            try {
                indexRef.set(loadFromAssets())
            } catch (e: Exception) {
                flogError { "yijian pinyin: failed to load dictionary: ${e.message}" }
            } finally {
                loading = false
            }
        }, "yijian-pinyin-load").start()
    }

    private fun loadFromAssets(): Index {
        val byFull = HashMap<String, MutableList<Entry>>(200_000)
        val byInitials = HashMap<String, MutableList<Entry>>(50_000)
        val singles = ArrayList<Entry>(20_000)
        val singleByPinyin = HashMap<String, MutableList<Entry>>(20_000)
        context.assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                val c1 = line.indexOf(',')
                if (c1 <= 0) continue
                val c2 = line.indexOf(',', c1 + 1)
                if (c2 <= c1) continue
                val c3 = line.indexOf(',', c2 + 1)
                if (c3 <= c2) continue
                val full = line.substring(0, c1)
                val initials = line.substring(c1 + 1, c2)
                val word = line.substring(c2 + 1, c3)
                val freq = line.substring(c3 + 1).toIntOrNull() ?: continue
                val entry = Entry(word, freq)
                byFull.getOrPut(full) { ArrayList(4) }.add(entry)
                byInitials.getOrPut(initials) { ArrayList(4) }.add(entry)
                if (word.length == 1) {
                    singles.add(entry)
                    singleByPinyin.getOrPut(full) { ArrayList(2) }.add(entry)
                }
            }
        }
        // Highest frequency first; callers take a prefix.
        byFull.values.forEach { it.sortByDescending { e -> e.freq } }
        byInitials.values.forEach { it.sortByDescending { e -> e.freq } }
        singles.sortByDescending { it.freq }
        singleByPinyin.values.forEach { it.sortByDescending { e -> e.freq } }
        flogError { "yijian pinyin: loaded ${byFull.size} pinyin keys" }
        return Index(byFull, byInitials, singles, singleByPinyin)
    }

    /**
     * Returns up to [limit] candidates for what the user has typed so far.
     *
     * Handles three things a real user does: typing the full pinyin with or without
     * apostrophes, typing only initials, and typing a prefix of a longer word.
     */
    fun query(input: String, limit: Int = 30): List<String> {
        if (input.isEmpty()) return emptyList()
        val index = indexRef.get() ?: run {
            preload()
            return emptyList()
        }
        val typed = input.lowercase().trim()

        // Three buckets, ranked the way mainstream pinyin IMEs do it:
        //   1. single characters whose full reading equals the typed pinyin (frequency order)
        //   2. multi-character words (frequency order)
        //   3. remaining single characters from prefix matches (frequency order)
        val exactSingle = LinkedHashMap<String, Int>(32)
        val multi = LinkedHashMap<String, Int>(64)
        val prefixSingle = LinkedHashMap<String, Int>(32)

        fun add(e: Entry, pinyin: String) {
            val map = when {
                e.word.length == 1 && pinyin == typed -> exactSingle
                e.word.length == 1 -> prefixSingle
                else -> multi
            }
            map[e.word] = maxOf(map[e.word] ?: 0, e.freq)
        }

        fun addAll(entries: List<Entry>?, pinyin: String) {
            entries ?: return
            for (e in entries) {
                if (exactSingle.size + multi.size + prefixSingle.size >= limit * 4) return
                add(e, pinyin)
            }
        }

        // 1. exact match on full pinyin
        addAll(index.byFullPinyin[typed], typed)
        // 2. exact match on initials
        addAll(index.byInitials[typed], typed)
        // 3. prefix match via binary search on full pinyin
        var i = lowerBound(index.sortedKeys, typed)
        while (i < index.sortedKeys.size && index.sortedKeys[i].startsWith(typed)) {
            val key = index.sortedKeys[i]
            addAll(index.byFullPinyin[key], key)
            if (exactSingle.size + multi.size + prefixSingle.size >= limit * 4) break
            i++
        }
        // 4. single characters whose reading starts with the typed prefix
        var j = lowerBound(index.sortedSingleKeys, typed)
        while (j < index.sortedSingleKeys.size && index.sortedSingleKeys[j].startsWith(typed)) {
            val key = index.sortedSingleKeys[j]
            addAll(index.singleByPinyin[key], key)
            j++
        }

        val exactSorted = exactSingle.entries.sortedByDescending { it.value }.map { it.key }
        val multiSorted = multi.entries.sortedByDescending { it.value }.map { it.key }
        val prefixSorted = prefixSingle.entries.sortedByDescending { it.value }.map { it.key }
        // Safety cap against absurdly long matches (e.g. "y" prefix); well above real use.
        return (exactSorted + multiSorted + prefixSorted).take(300)
    }

    /** First index whose value is >= [key]. */
    private fun lowerBound(keys: Array<String>, key: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Candidates for an empty query, useful as a starting row. */
    fun commonCharacters(limit: Int = 20): List<String> {
        val index = indexRef.get() ?: return emptyList()
        return index.singleChars.take(limit).map { it.word }
    }

    companion object {
        private const val ASSET_NAME = "pinyin_dict.txt"
    }
}
