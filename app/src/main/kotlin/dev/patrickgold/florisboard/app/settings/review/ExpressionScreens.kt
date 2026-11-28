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

package dev.patrickgold.florisboard.app.settings.review

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.app.design.Shape
import dev.patrickgold.florisboard.app.design.Space
import dev.patrickgold.florisboard.app.design.YijianTheme
import dev.patrickgold.florisboard.ime.translation.mistake.Expression
import dev.patrickgold.florisboard.ime.translation.mistake.ExpressionStore
import kotlinx.coroutines.launch

/**
 * 我的表达 — everything a native speaker would say, that the learner has already meant.
 *
 * The point of a corpus rather than a course: every line here came from a sentence the learner
 * themselves wanted to say. A phrase book is somebody else's selection; this is a record of
 * their own. Reading back through it is itself the practice.
 */
@Composable
fun ExpressionLibraryScreen() {
    val colors = YijianTheme.colors
    val type = YijianTheme.type

    var list by remember { mutableStateOf<List<Expression>?>(null) }
    LaunchedEffect(Unit) {
        runCatching { ExpressionStore.importFromPracticeLog() }
        list = ExpressionStore.all()
    }

    FlorisScreenNg(
        title = "我的表达",
        previewFieldVisible = false,
        background = colors.paper,
    ) { contentPadding ->
        val items = list
        if (items == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = colors.inkMuted) }
            return@FlorisScreenNg
        }
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有积累表达。用译键翻译时，模型给出的更地道说法会存到这里。",
                    style = type.small,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(Space.xxl),
                )
            }
            return@FlorisScreenNg
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter),
        ) {
            Spacer(modifier = Modifier.height(Space.xxl))
            Text("${items.size}", style = type.display, color = colors.ink)
            Text("条可以拿去用的表达", style = type.small, color = colors.inkMuted)
            Spacer(modifier = Modifier.height(Space.xl))

            items.forEach { e ->
                ExpressionRow(e)
                Spacer(modifier = Modifier.height(Space.lg))
                Rule()
                Spacer(modifier = Modifier.height(Space.lg))
            }
            Spacer(modifier = Modifier.height(Space.xxl))
        }
    }
}

/** One expression: the meaning, the idiomatic form, and what was said instead. */
@Composable
private fun ExpressionRow(e: Expression) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type

    Column(modifier = Modifier.fillMaxWidth()) {
        // The meaning comes first — it is what the learner wanted to say.
        Text(
            text = e.translation,
            style = type.small,
            color = colors.inkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(Space.xs))
        Text(text = e.better, style = type.title, color = colors.ink)

        if (e.isUpgrade) {
            Spacer(modifier = Modifier.height(Space.xs))
            Text(
                text = "你当时说 ${e.original}",
                style = type.small,
                color = colors.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (e.why.isNotBlank()) {
            Spacer(modifier = Modifier.height(Space.xs))
            Text(text = e.why, style = type.small, color = colors.inkMuted)
        }
    }
}

/**
 * 练表达 — the production drill.
 *
 * Shows the meaning and asks for the idiomatic English, then reveals. Self-reported, like the
 * mistake drill: the judgement of "did I say it naturally" is one only the learner can make
 * honestly, and a diff against one accepted phrasing would punish equally good alternatives.
 */
@Composable
fun ExpressionPracticeScreen() {
    val navController = LocalNavController.current
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    val scope = rememberCoroutineScope()

    var queue by remember { mutableStateOf<List<Expression>?>(null) }
    var index by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { ExpressionStore.importFromPracticeLog() }
        queue = ExpressionStore.reviewQueue()
    }

    val items = queue.orEmpty()
    val current = items.getOrNull(index)

    /** Records the practice and advances; the callback resets the reveal state. */
    fun next(scopeRef: kotlinx.coroutines.CoroutineScope, after: () -> Unit) {
        val e = current ?: return
        scopeRef.launch {
            runCatching { ExpressionStore.recordPractice(e) }
            after()
            index += 1
        }
    }

    FlorisScreenNg(
        title = "练表达",
        previewFieldVisible = false,
        background = colors.paper,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = Space.gutter),
        ) {
            when {
                queue == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = colors.inkMuted) }

                items.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "还没有可练的表达。多翻译几句，模型给出更地道说法时会自动积累。",
                        style = type.small,
                        color = colors.inkMuted,
                    )
                }

                current == null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("这一轮结束了", style = type.title, color = colors.ink)
                    Spacer(modifier = Modifier.height(Space.xl))
                    Text("练了 ${items.size} 条", style = type.small, color = colors.inkMuted)
                    Spacer(modifier = Modifier.height(Space.xl))
                    Action("完成") { navController.popBackStack() }
                    Spacer(modifier = Modifier.height(Space.xl))
                }

                else -> {
                    Progress(index + 1, items.size)
                    Spacer(modifier = Modifier.height(Space.lg))

                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text("这个意思，母语者会怎么说", style = type.label, color = colors.inkMuted)
                        Spacer(modifier = Modifier.height(Space.xs))
                        Text(current.translation, style = type.display, color = colors.ink)

                        if (revealed) {
                            Spacer(modifier = Modifier.height(Space.xl))
                            Text(current.better, style = type.title, color = colors.positive)
                            if (current.isUpgrade) {
                                Spacer(modifier = Modifier.height(Space.sm))
                                Text(
                                    "你当时说 ${current.original}",
                                    style = type.small,
                                    color = colors.inkMuted,
                                )
                            }
                            if (current.why.isNotBlank()) {
                                Spacer(modifier = Modifier.height(Space.sm))
                                Text(current.why, style = type.small, color = colors.inkMuted)
                            }
                        } else {
                            Spacer(modifier = Modifier.height(Space.lg))
                            Text(
                                "先在心里说一遍，再看答案",
                                style = type.small,
                                color = colors.inkMuted,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Space.lg))
                    if (!revealed) {
                        Action("看答案") { revealed = true }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            Action("还想不起来", emphasised = false) { next(scope) { revealed = false } }
                            Action("能用了") { next(scope) { revealed = false } }
                        }
                    }
                    Spacer(modifier = Modifier.height(Space.xl))
                }
            }
        }
    }
}

@Composable
private fun Progress(current: Int, total: Int) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(2.dp).background(colors.rule)) {
        Box(
            modifier = Modifier
                .width(maxWidth * (current.toFloat() / total.coerceAtLeast(1)))
                .fillMaxHeight()
                .background(colors.ink),
        )
    }
    Spacer(modifier = Modifier.height(Space.sm))
    Text("$current / $total", style = type.label, color = colors.inkMuted)
}

/** The single page action: accent when a next step exists, outlined when it is a skip. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Action(
    label: String,
    emphasised: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    Box(
        modifier = Modifier
            .weight(1f)
            .height(52.dp)
            .background(if (emphasised) colors.accent else colors.paper, Shape.action)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.body,
            fontWeight = FontWeight.Medium,
            color = if (emphasised) colors.onAccent else colors.inkMuted,
        )
    }
}

/** Full-width variant for screens with only one action. */
@Composable
private fun Action(label: String, onClick: () -> Unit) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(colors.accent, Shape.action)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.body,
            fontWeight = FontWeight.Medium,
            color = colors.onAccent,
        )
    }
}
