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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import org.k3lp.lib.text.asK3String

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
    val imeController = LocalImeController.current

    val preedit by imeController.translationPreedit.collectAsState()
    val undoable by imeController.translationUndoable.collectAsState()
    val pinyinComposing by imeController.pinyin.composing.collectAsState()
    val pinyinCandidates by imeController.pinyin.candidates.collectAsState()
    val pinyinActive by imeController.pinyin.active.collectAsState()
    val status by imeController.translationStatus.collectAsState()
    val error by imeController.translationError.collectAsState()
    val voiceMsg by imeController.voiceStatus.collectAsState()
    val recorder = imeController.recorder
    val recording = recorder.isRecording
    val translationToCopy by imeController.translationToCopy.collectAsState()
    val lastLesson by imeController.lastEnglishLesson.collectAsState()
    val candidates by imeController.translationCandidates.collectAsState()
    // Pinyin mode takes over the panel: it is what the user is actively typing right now.
    if (pinyinActive && (pinyinCandidates.isNotEmpty() || pinyinComposing.isNotEmpty())) {
        PinyinPanel(
            composing = pinyinComposing,
            candidates = pinyinCandidates,
            onPick = { word ->
                imeController.updateStateBlocking {
                    imeController.pinyin.commit(word, this)
                }
            },
            onRaw = {
                imeController.updateStateBlocking {
                    val raw = imeController.pinyin.rawInput()
                    imeController.pinyin.clear()
                    if (raw.isNotEmpty()) emitTextDirect(raw.asK3String())
                }
            },
        )
        return
    }

    val hasError = status == TranslationManager.Status.ERROR && error != null
    val translating = status == TranslationManager.Status.TRANSLATING
    if (candidates.isEmpty() && preedit.isEmpty() && voiceMsg == null && !recording && !hasError &&
        !translating && undoable == null && lastLesson == null) {
        return
    }

    val teacherCard = (candidates.firstOrNull() ?: lastLesson)
        ?.takeIf { it.correctedEnglish.isNotBlank() || it.note.isNotBlank() || it.corrections.isNotEmpty() }

    TranslationPanelContent(
        preedit = preedit,
        translating = translating,
        error = if (status == TranslationManager.Status.ERROR) error else null,
        teacherCard = teacherCard,
        voiceMessage = if (recording) "🎤 正在录音…（松开结束）" else voiceMsg,
        onSpeak = { text -> imeController.speak(text) },
        candidates = candidates,
        onClick = { candidate ->
            imeController.updateStateBlocking {
                emitCandidate(candidate)
            }
        },
        onDismiss = {
            // Dismissing commits the raw buffered English (if any) and hides the list
            imeController.updateStateBlocking {
                translationCommitBufferAsRaw()
            }
        },
        onClear = {
            // Discard whatever is in the editor for this sentence and hide the panel
            imeController.updateStateBlocking {
                translationClearAll()
            }
        },
        onUndo = {
            imeController.updateStateBlocking { undoLastReplacement() }
        },
        onCopy = translationToCopy?.let { text ->
            {
                imeController.clipboardManager.addNewPlaintext(text)
                imeController.updateStateBlocking { translationClearAll() }
            }
        },
        undoAvailable = undoable != null,
    )
}

@Composable
private fun TranslationPanelContent(
    preedit: String,
    translating: Boolean,
    error: String?,
    teacherCard: TranslationSuggestionCandidate?,
    voiceMessage: String?,
    onSpeak: (String) -> Unit,
    candidates: List<SuggestionCandidate>,
    onClick: (SuggestionCandidate) -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onCopy: (() -> Unit)?,
    undoAvailable: Boolean,
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
            if (teacherCard != null) {
                TeacherCard(
                    card = teacherCard,
                    onSurface = onSurface,
                    onSpeak = onSpeak,
                )
                Divider(onSurface)
            }
            if (voiceMessage != null) {
                VoiceRow(message = voiceMessage, onSurface = onSurface)
                Divider(onSurface)
            }
            if (translating && preedit.isEmpty()) {
                VoiceRow(message = "正在翻译…", onSurface = onSurface)
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
                    onClick = { onClick(candidate) },
                )
            }
            if (onCopy != null) {
                ActionButton("复制译文", onSurface, Modifier.fillMaxWidth(), onClick = onCopy)
            }
            if (undoAvailable) {
                Divider(onSurface)
                UndoRow(onSurface = onSurface, onUndo = onUndo)
            }
            if (preedit.isNotEmpty()) {
                Divider(onSurface)
                ActionRows(onSurface = onSurface, onDismiss = onDismiss, onClear = onClear)
            } else if (translating || candidates.isNotEmpty() || error != null || voiceMessage != null || teacherCard != null) {
                ActionButton(
                    text = if (translating || candidates.isNotEmpty()) "保留原文" else "关闭",
                    onSurface = onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onClear,
                )
            }
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
            // Long drafts scroll and stay pinned to the end so the newest words are always the
            // ones in view.
            val draftScroll = rememberScrollState()
            LaunchedEffect(preedit) {
                draftScroll.animateScrollTo(draftScroll.maxValue)
            }
            Text(
                text = preedit,
                color = onSurface.copy(alpha = 0.88f),
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                softWrap = true,
                modifier = Modifier
                    .weight(1f, fill = true)
                    .heightIn(max = 96.dp)
                    .verticalScroll(draftScroll),
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
            text = "输入识别原文",
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
            color = onSurface.copy(alpha = if (danger) 0.72f else 0.82f),
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
    var expanded by remember(card.requestId) { mutableStateOf(false) }
    val english = card.correctedEnglish
    val hasEnglish = english.isNotBlank()
    Column(
        modifier = Modifier.fillMaxWidth().background(onSurface.copy(alpha = 0.05f))
            .padding(start = 16.dp, end = 12.dp, bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hasEnglish) "地道英文" else "英语讲解",
                modifier = Modifier.weight(1f).semantics { heading() },
                color = onSurface.copy(alpha = 0.78f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (hasEnglish) {
                ActionButton(
                    text = "朗读",
                    onSurface = onSurface,
                    modifier = Modifier.semantics { contentDescription = "朗读英文" },
                    onClick = { onSpeak(english) },
                )
            }
            ActionButton(
                text = if (expanded) "收起" else "讲解",
                onSurface = onSurface,
                onClick = { expanded = !expanded },
            )
        }
        if (hasEnglish) {
            Text(
                text = english,
                color = onSurface,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 20.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Medium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (card.originalEnglish.isNotBlank() && card.originalEnglish != english) {
                    Text("原文　${card.originalEnglish}", color = onSurface.copy(alpha = 0.75f), fontSize = 14.sp)
                }
                for (item in card.corrections) {
                    Text("${item.wrong} → ${item.right}", color = onSurface, fontWeight = FontWeight.Medium)
                    if (item.why.isNotBlank()) Text(item.why, color = onSurface, fontSize = 14.sp)
                }
                if (card.note.isNotBlank()) Text(card.note, color = onSurface, fontSize = 14.sp)
            }
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

/** Shown for a few seconds after a translation replaced the original text. */
@Composable
private fun UndoRow(onSurface: androidx.compose.ui.graphics.Color, onUndo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(onClick = onUndo)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "↩", color = onSurface.copy(alpha = 0.7f), fontSize = 15.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "撤销，恢复原文",
            color = onSurface.copy(alpha = 0.75f),
            fontSize = 13.sp,
        )
    }
}

/** Horizontal candidate strip for pinyin input, plus the composed pinyin above it. */
@Composable
private fun PinyinPanel(
    composing: String,
    candidates: List<String>,
    onPick: (String) -> Unit,
    onRaw: () -> Unit,
) {
    val onSurface = LocalContentColor.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(onSurface.copy(alpha = 0.06f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(onSurface.copy(alpha = 0.18f)),
        )
        // The pinyin the user typed, so the composition is visible
        if (composing.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clickable(onClick = onRaw)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = composing,
                    color = onSurface.copy(alpha = 0.75f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f, fill = true),
                )
                Text(
                    text = "用英文",
                    color = onSurface.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                )
            }
            Divider(onSurface)
        }
        // A persistent mode line: in pinyin mode the keyboard types Chinese, which the user must
        // be able to see at a glance.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .heightIn(min = 12.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(onSurface.copy(alpha = 0.35f)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "中文输入",
                color = onSurface.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
        }
        Divider(onSurface)
        // Candidates, horizontally scrollable
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            candidates.take(40).forEach { word ->
                Box(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onPick(word) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = word, color = onSurface, fontSize = 18.sp)
                }
            }
        }
    }
}
