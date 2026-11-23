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

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.nlpManager

/** Minimum Android touch target. */
private val TouchTarget = 48.dp

/**
 * yijian translation panel, rendered as its own region between the smartbar (toolbar) and the
 * keyboard. It shows the pre-edit buffer (typed English that has not reached the app yet) plus
 * the translation candidates.
 *
 * Design notes (from the UI audit):
 * - own background surface so it reads as a separate layer from toolbar and keyboard
 * - every selectable row is at least 48dp tall
 * - the pre-edit row is styled as a draft (monospace, accent bar) and shows a translating state
 * - dismiss is a quiet, separate row so it cannot be confused with a candidate
 */
@Composable
fun TranslationPanel() {
    val context = LocalContext.current
    val imeController = LocalImeController.current
    val nlpManager by context.nlpManager()

    val preedit by imeController.translationPreedit.collectAsState()
    val status by imeController.translationStatus.collectAsState()
    val error by imeController.translationError.collectAsState()
    val voiceBusy by imeController.voiceBusy.collectAsState()
    val voiceMsg by imeController.voiceStatus.collectAsState()
    val recorder = imeController.recorder
    val recording = recorder.isRecording
    val candidates by nlpManager.activeCandidatesFlow.collectAsState()

    val isTranslationCandidates = candidates.isNotEmpty() &&
        candidates.all { it is TranslationSuggestionCandidate }
    val hasError = status == TranslationManager.Status.ERROR && error != null
    if (!isTranslationCandidates && preedit.isEmpty() && voiceMsg == null && !recording && !hasError) {
        return
    }

    val teacherCard = (candidates.firstOrNull() as? TranslationSuggestionCandidate)
        ?.takeIf { it.correctedEnglish.isNotBlank() || it.note.isNotBlank() }

    TranslationPanelContent(
        preedit = preedit,
        translating = status == TranslationManager.Status.TRANSLATING,
        error = if (status == TranslationManager.Status.ERROR) error else null,
        teacherCard = teacherCard,
        voiceMessage = if (recording) "🎤 正在录音…（松开结束）" else voiceMsg,
        speakingText = candidates.firstOrNull()?.text?.toString().orEmpty(),
        onSpeak = { text -> imeController.speak(text) },
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
        onClear = {
            // Discard everything typed and everything translated: nothing is sent, nothing chosen
            imeController.updateStateBlocking {
                translationClearAll()
            }
            nlpManager.clearSuggestions()
        },
    )
}

@Composable
private fun TranslationPanelContent(
    preedit: String,
    translating: Boolean,
    error: String?,
    teacherCard: TranslationSuggestionCandidate?,
    voiceMessage: String?,
    speakingText: String,
    onSpeak: (String) -> Unit,
    candidates: List<SuggestionCandidate>,
    onClick: (Int) -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    val onSurface = LocalContentColor.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(onSurface.copy(alpha = 0.06f))
            .heightIn(max = 440.dp),
    ) {
        // 3dp accent bar marking this region as the translation surface
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(onSurface.copy(alpha = 0.18f)),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (voiceMessage != null) {
                VoiceRow(message = voiceMessage, onSurface = onSurface)
                Divider(onSurface)
            }
            // Show the error on its own row when there is no draft row to attach it to
            if (preedit.isEmpty() && error != null) {
                VoiceRow(message = error, onSurface = onSurface, isError = true)
                Divider(onSurface)
            }
            if (preedit.isNotEmpty()) {
                PreeditRow(
                    preedit = preedit,
                    translating = translating,
                    error = error,
                    onSurface = onSurface,
                )
                Divider(onSurface)
            }
            candidates.forEachIndexed { n, candidate ->
                if (n > 0) {
                    Divider(onSurface)
                }
                CandidateRow(
                    text = candidate.text.toString(),
                    onSurface = onSurface,
                    onClick = { onClick(n) },
                )
            }
            if (teacherCard != null) {
                Divider(onSurface)
                TeacherCard(
                    card = teacherCard,
                    onSurface = onSurface,
                    onSpeak = onSpeak,
                )
            }
            Divider(onSurface)
            ActionRows(onSurface = onSurface, onDismiss = onDismiss, onClear = onClear)
        }
    }
}

@Composable
private fun PreeditRow(
    preedit: String,
    translating: Boolean,
    error: String?,
    onSurface: androidx.compose.ui.graphics.Color,
) {
    val accent = when {
        error != null -> androidx.compose.ui.graphics.Color(0xFFB3261E)
        translating -> animateColorAsState(onSurface.copy(alpha = 0.45f), label = "translatingAccent").value
        else -> onSurface.copy(alpha = 0.25f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget - 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // draft marker
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .heightIn(min = 22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = preedit,
                color = onSurface.copy(alpha = 0.88f),
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                maxLines = 3,
                softWrap = true,
                modifier = Modifier.weight(1f, fill = true),
            )
            if (translating) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "翻译中…",
                    color = onSurface.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
        // Errors go on their own full-width line: they are long, must wrap, and must never
        // squeeze the draft text into a one-character column.
        if (error != null && !translating) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = error,
                color = androidx.compose.ui.graphics.Color(0xFFB3261E),
                fontSize = 12.sp,
                softWrap = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 13.dp),
            )
        }
    }
}

@Composable
private fun CandidateRow(text: String, onSurface: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = onSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ActionRows(
    onSurface: androidx.compose.ui.graphics.Color,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        ActionButton(
            text = "放弃翻译，发送原文",
            onSurface = onSurface,
            modifier = Modifier.weight(1f),
            onClick = onDismiss,
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .heightIn(min = TouchTarget - 16.dp)
                .align(Alignment.CenterVertically)
                .background(onSurface.copy(alpha = 0.10f)),
        )
        ActionButton(
            text = "✕ 清除",
            onSurface = onSurface,
            danger = true,
            modifier = Modifier.weight(1f),
            onClick = onClear,
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    onSurface: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .heightIn(min = TouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            color = onSurface.copy(alpha = if (danger) 0.62f else 0.5f),
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun Divider(onSurface: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(onSurface.copy(alpha = 0.10f)),
    )
}

/**
 * The "private English teacher" block: what the sentence should look like and, if anything was
 * wrong, each concrete fix with the rule behind it.
 */
@Composable
private fun TeacherCard(
    card: TranslationSuggestionCandidate,
    onSurface: androidx.compose.ui.graphics.Color,
    onSpeak: (String) -> Unit,
) {
    // The natural phrasing only — no "老师版" label, and the sentence scrolls horizontally so a
    // long correction never gets clipped to two lines. Spelling/grammar breakdown lives in the
    // emailed report.
    val best = card.correctedEnglish.ifBlank { card.note }
    if (best.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = best,
            color = onSurface.copy(alpha = 0.85f),
            fontSize = 14.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .weight(1f, fill = true)
                .horizontalScroll(rememberScrollState()),
        )
        Box(
            modifier = Modifier
                .width(TouchTarget)
                .height(TouchTarget)
                .clickable { onSpeak(best) },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "🔊", fontSize = 16.sp)
        }
    }
}

/** Shows recording / recognition state for voice input. */
@Composable
private fun VoiceRow(
    message: String,
    onSurface: androidx.compose.ui.graphics.Color,
    isError: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = if (isError) androidx.compose.ui.graphics.Color(0xFFB3261E) else onSurface.copy(alpha = 0.8f),
            fontSize = 14.sp,
            softWrap = true,
            modifier = Modifier.weight(1f, fill = true),
        )
    }
}
