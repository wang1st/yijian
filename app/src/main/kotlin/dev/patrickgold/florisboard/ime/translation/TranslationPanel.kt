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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.patrickgold.florisboard.R
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

/** Material 3 error red, adaptive to light/dark instead of a single hardcoded value. */
@Composable
private fun errorColor(): Color = if (isSystemInDarkTheme()) Color(0xFFF2B8B5) else Color(0xFFB3261E)

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
    val voiceBusy by imeController.voiceBusy.collectAsState()
    val recorder = imeController.recorder
    val recording = recorder.isRecording
    val micLevel by recorder.level.collectAsState()
    val translationToCopy by imeController.translationToCopy.collectAsState()
    val lastLesson by imeController.lastEnglishLesson.collectAsState()
    val candidates by imeController.translationCandidates.collectAsState()

    // Pinyin mode takes over the panel: it is what the user is actively typing right now.
    val showPinyin = pinyinActive && (pinyinCandidates.isNotEmpty() || pinyinComposing.isNotEmpty())
    val hasError = status == TranslationManager.Status.ERROR && error != null
    val translating = status == TranslationManager.Status.TRANSLATING
    val showMain = !(candidates.isEmpty() && preedit.isEmpty() && voiceMsg == null && !recording &&
        !hasError && !translating && undoable == null && lastLesson == null)

    // Animating the height keeps the whole keyboard from jumping when the panel
    // appears or disappears.
    AnimatedVisibility(
        visible = showPinyin || showMain,
        enter = expandVertically(animationSpec = tween(150)) + fadeIn(animationSpec = tween(150)),
        exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(150)),
    ) {
        if (showPinyin) {
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
        } else {
            val teacherCard = (candidates.firstOrNull() ?: lastLesson)
                ?.takeIf { it.correctedEnglish.isNotBlank() || it.note.isNotBlank() || it.corrections.isNotEmpty() }

            TranslationPanelContent(
                preedit = preedit,
                translating = translating,
                error = if (status == TranslationManager.Status.ERROR) error else null,
                teacherCard = teacherCard,
                voiceMessage = if (recording) stringResource(R.string.yijian__voice_recording) else voiceMsg,
                micLevel = if (recording) micLevel else null,
                onCancelVoice = if (voiceBusy && !recording) {
                    { imeController.cancelVoiceTranscription() }
                } else null,
                onSpeak = { text -> imeController.speak(text) },
                onCommitEnglish = { english ->
                    imeController.commitTeacherEnglish()
                },
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
    }
}

@Composable
private fun TranslationPanelContent(
    preedit: String,
    translating: Boolean,
    error: String?,
    teacherCard: TranslationSuggestionCandidate?,
    voiceMessage: String?,
    micLevel: Int?,
    onCancelVoice: (() -> Unit)?,
    onSpeak: (String) -> Unit,
    onCommitEnglish: (String) -> Unit,
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
        // Everything scrollable: cards, messages, drafts, candidates.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            if (teacherCard != null) {
                TeacherCard(
                    card = teacherCard,
                    onSurface = onSurface,
                    onSpeak = onSpeak,
                    onCommitEnglish = onCommitEnglish,
                )
                Divider(onSurface)
            }
            if (voiceMessage != null) {
                VoiceRow(
                    message = voiceMessage,
                    onSurface = onSurface,
                    micLevel = micLevel,
                    onCancel = onCancelVoice,
                )
                Divider(onSurface)
            }
            if (translating && preedit.isEmpty()) {
                VoiceRow(message = stringResource(R.string.yijian__translating), onSurface = onSurface)
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
                    recommended = n == 0,
                    onSurface = onSurface,
                    onClick = { onClick(candidate) },
                )
            }
            if (onCopy != null) {
                ActionButton(stringResource(R.string.yijian__copy_translation), onSurface, Modifier.fillMaxWidth(), onClick = onCopy)
            }
        }
        // Footer is pinned below the scroll area so the primary actions are always
        // reachable no matter how tall the content above is.
        if (undoAvailable) {
            Divider(onSurface)
            UndoRow(onSurface = onSurface, onUndo = onUndo)
        }
        if (preedit.isNotEmpty()) {
            Divider(onSurface)
            ActionRows(onSurface = onSurface, onDismiss = onDismiss, onClear = onClear)
        } else if (translating || candidates.isNotEmpty() || error != null || voiceMessage != null || teacherCard != null) {
            Divider(onSurface)
            ActionButton(
                text = stringResource(
                    if (translating || candidates.isNotEmpty()) R.string.yijian__keep_original
                    else R.string.yijian__close
                ),
                onSurface = onSurface,
                modifier = Modifier.fillMaxWidth(),
                onClick = onClear,
            )
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
        error != null -> errorColor()
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
                    text = stringResource(R.string.yijian__translating_short),
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
                color = errorColor(),
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
private fun CandidateRow(
    text: String,
    recommended: Boolean,
    onSurface: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
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
            modifier = Modifier.weight(1f, fill = true),
        )
        if (recommended) {
            // The model orders candidates by naturalness; mark the top one as the pick.
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(onSurface.copy(alpha = 0.14f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = stringResource(R.string.yijian__recommended),
                    color = onSurface.copy(alpha = 0.72f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
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
            text = stringResource(R.string.yijian__type_raw),
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
            text = stringResource(R.string.yijian__clear),
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
    onCommitEnglish: (String) -> Unit,
) {
    var expanded by remember(card.requestId) { mutableStateOf(false) }
    val english = card.correctedEnglish
    val hasEnglish = english.isNotBlank()
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().background(onSurface.copy(alpha = 0.05f))
            .padding(start = 16.dp, end = 12.dp, bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(if (hasEnglish) R.string.yijian__native_english else R.string.yijian__english_notes),
                modifier = Modifier.weight(1f).semantics { heading() },
                color = onSurface.copy(alpha = 0.78f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (hasEnglish) {
                ActionButton(
                    text = stringResource(R.string.yijian__speak),
                    onSurface = onSurface,
                    modifier = Modifier.semantics { contentDescription = context.getString(R.string.yijian__speak_desc) },
                    onClick = { onSpeak(english) },
                )
            }
            ActionButton(
                text = stringResource(if (expanded) R.string.yijian__collapse else R.string.yijian__explain),
                onSurface = onSurface,
                onClick = { expanded = !expanded },
            )
        }
        if (hasEnglish) {
            Text(
                text = english,
                color = onSurface,
                modifier = Modifier.fillMaxWidth().clickable { onCommitEnglish(english) }
                    .semantics { contentDescription = context.getString(R.string.yijian__commit_english_desc) },
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
                    Text(
                        "${context.getString(R.string.yijian__original_label)}　${card.originalEnglish}",
                        color = onSurface.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                    )
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
    micLevel: Int? = null,
    onCancel: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (micLevel != null) {
            MicMeter(level = micLevel, onSurface = onSurface)
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = message,
            color = if (isError) errorColor() else onSurface.copy(alpha = 0.8f),
            fontSize = 14.sp,
            softWrap = true,
            modifier = Modifier.weight(1f, fill = true),
        )
        if (onCancel != null) {
            Spacer(modifier = Modifier.width(8.dp))
            ActionButton(
                text = stringResource(R.string.yijian__cancel),
                onSurface = onSurface,
                onClick = onCancel,
            )
        }
    }
}

/** Eight-bar microphone level meter: confirms that speech is actually being captured. */
@Composable
private fun MicMeter(level: Int, onSurface: androidx.compose.ui.graphics.Color) {
    val lit = (level / 100f * 8).toInt().coerceIn(if (level > 0) 1 else 0, 8)
    Row(verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 8) {
            if (i > 0) Spacer(modifier = Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((6 + i * 2).dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(
                        onSurface.copy(alpha = if (i < lit) 0.85f else 0.15f)
                    ),
            )
        }
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
            text = stringResource(R.string.yijian__undo_restore),
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
                    text = stringResource(R.string.yijian__use_english),
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
                text = stringResource(R.string.yijian__chinese_input),
                color = onSurface.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
        }
        Divider(onSurface)
        // Candidates, horizontally scrollable; a fade at the right edge signals that
        // more candidates exist off-screen.
        val pinyinScroll = rememberScrollState()
        val canScrollForward by remember { derivedStateOf { pinyinScroll.canScrollForward } }
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(pinyinScroll)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                candidates.forEach { word ->
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
            if (canScrollForward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(28.dp)
                        .height(48.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, onSurface.copy(alpha = 0.06f))
                            )
                        ),
                )
            }
        }
    }
}
