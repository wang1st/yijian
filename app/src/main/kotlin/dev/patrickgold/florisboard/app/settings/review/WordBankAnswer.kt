package dev.patrickgold.florisboard.app.settings.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.design.YijianTheme
import kotlin.random.Random

internal class TileState(target: String, val seed: Int = Random.nextInt(), restored: IntArray = intArrayOf()) {
    // Keep contractions, hyphenated words, times and decimals together.
    private val words = Regex("""[\p{Sc}@#]?[\p{L}\p{N}]+(?:['’‐‑.:-][\p{L}\p{N}]+)*%?|[&+]""")
        .findAll(target).map { it.value }.toList()
    val tiles: List<String>
    val placed = mutableStateListOf<Int>()

    init {
        val random = Random(seed)
        val used = words.map { it.lowercase() }.toSet()
        val extra = listOf("at", "on", "to", "from", "is", "are", "did", "does", "then", "still")
            .filterNot { it in used }.shuffled(random).take(2)
        val bank = (words + extra).shuffled(random)
        // A short sentence should not occasionally arrive already assembled.
        tiles = if (words.size > 1 && bank.filter { it.lowercase() in used } == words && words.distinct().size > 1) {
            bank.toMutableList().apply {
                val first = indexOf(words.first())
                val other = indexOfFirst { it.lowercase() in used && it != words.first() }
                java.util.Collections.swap(this, first, other)
            }
        } else bank
        restored.forEach(::place)
    }

    fun place(index: Int) {
        if (index in tiles.indices && index !in placed) placed.add(index)
    }

    fun remove(index: Int) { placed.remove(index) }
    fun answer() = placed.joinToString(" ") { tiles[it] }

    fun hint() {
        val prefix = placed.zip(words).takeWhile { (index, word) -> tiles[index] == word }.size
        while (placed.size > prefix) placed.removeAt(placed.lastIndex)
        val next = words.getOrNull(prefix) ?: return
        place(tiles.indices.first { it !in placed && tiles[it] == next })
    }
}

@Composable
internal fun rememberTileState(id: String, target: String): TileState = key(id, target) {
    val saver = remember(target) {
        listSaver<TileState, Any>(
            save = { listOf(it.seed, it.placed.toIntArray()) },
            restore = { TileState(target, it[0] as Int, it[1] as IntArray) },
        )
    }
    rememberSaveable(saver = saver) { TileState(target) }
}

@Composable
internal fun WordBankAnswer(
    state: TileState, editable: Boolean, onPlaced: () -> Unit = {}, showHint: Boolean = false,
) {
    val colors = YijianTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (editable) Text("点词拼句，再点已选的词可撤回", style = YijianTheme.type.small, color = colors.inkMuted)
        FlowRow(
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).border(1.dp, colors.rule, AnswerRadius).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.placed.isEmpty()) Text("你的回答", style = YijianTheme.type.small, color = colors.inkMuted)
            for (index in state.placed) key(index) {
                PlacedWordTile(state.tiles[index], if (editable) { { state.remove(index) } } else null)
            }
        }
        if (editable) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.tiles.forEachIndexed { index, word ->
                    if (index in state.placed) {
                        // Retain the slot width so the remaining targets stay under the user's fingers.
                        Box(Modifier.height(48.dp).background(colors.rule, AnswerRadius)
                            .clearAndSetSemantics { }.padding(horizontal = 12.dp)) {
                            Text(word, color = Color.Transparent, fontSize = 19.sp, lineHeight = 25.sp)
                        }
                    } else WordTile(word) { state.place(index); onPlaced() }
                }
            }
            if (showHint) TextButton(onClick = { state.hint(); onPlaced() }) { Text("提示下一个词") }
        }
    }
}
