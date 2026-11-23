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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.nlpManager
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * yijian translation panel, rendered between the smartbar (toolbar) and the keyboard. It shows
 * the pre-edit buffer (typed English that has not reached the app yet) plus the translation
 * candidates. Height adapts to content so all rows are visible without relying on scrolling.
 */
@Composable
fun TranslationPanel() {
    val context = LocalContext.current
    val imeController = LocalImeController.current
    val nlpManager by context.nlpManager()

    val preedit by imeController.translationPreedit.collectAsState()
    val candidates by nlpManager.activeCandidatesFlow.collectAsState()

    val isTranslationCandidates = candidates.isNotEmpty() &&
        candidates.all { it is TranslationSuggestionCandidate }
    if (!isTranslationCandidates && preedit.isEmpty()) {
        return
    }

    TranslationPanelContent(
        preedit = preedit,
        candidates = if (isTranslationCandidates) candidates else emptyList(),
        onClick = { n ->
            imeController.updateStateBlocking {
                emitCandidate(candidates[n])
            }
        },
        onDismiss = {
            // Dismissing commits the raw buffered English (if any) and hides the list
            imeController.updateStateBlocking {
                translationCommitBufferAsRaw()
            }
            nlpManager.clearSuggestions()
        },
    )
}

@Composable
private fun TranslationPanelContent(
    preedit: String,
    candidates: List<SuggestionCandidate>,
    onClick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    SnyggColumn(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 440.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (preedit.isNotEmpty()) {
            SnyggRow(
                elementName = FlorisImeUi.SmartbarCandidateWord.elementName,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SnyggText(
                    elementName = "${FlorisImeUi.SmartbarCandidateWord.elementName}-secondary-text",
                    text = "EN  $preedit▏",
                )
            }
            PanelDivider()
        }
        candidates.forEachIndexed { n, candidate ->
            if (n > 0) {
                PanelDivider()
            }
            SnyggRow(
                elementName = FlorisImeUi.SmartbarCandidateWord.elementName,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(n) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SnyggText(
                    elementName = "${FlorisImeUi.SmartbarCandidateWord.elementName}-text",
                    text = candidate.text.toString(),
                )
            }
        }
        PanelDivider()
        SnyggRow(
            elementName = FlorisImeUi.SmartbarCandidateWord.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggText(
                elementName = "${FlorisImeUi.SmartbarCandidateWord.elementName}-secondary-text",
                text = if (preedit.isNotEmpty()) "✕ 收起（原文直接上屏）" else "✕ 收起",
            )
        }
    }
}

@Composable
private fun PanelDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(LocalContentColor.current.copy(alpha = 0.12f)),
    )
}
