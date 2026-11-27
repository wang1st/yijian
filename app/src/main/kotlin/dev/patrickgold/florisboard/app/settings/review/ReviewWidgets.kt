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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.translation.AudioPlayer
import dev.patrickgold.florisboard.ime.translation.SpeechClient
import dev.patrickgold.florisboard.ime.translation.review.ReviewCard
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val prefs by FlorisPreferenceStore

// ---- choice-based exercise UI -------------------------------------------------------

/** Visual state of a multiple-choice option. */
enum class ChoiceState { IDLE, CORRECT, WRONG }

/** One tappable answer option, styled like Duolingo's bordered answer cards. */
@Composable
internal fun ChoiceRow(text: String, state: ChoiceState, onClick: () -> Unit) {
    val border = when (state) {
        ChoiceState.IDLE -> choiceBorder()
        ChoiceState.CORRECT -> DuoGreen
        ChoiceState.WRONG -> DuoRed
    }
    val bg = when (state) {
        ChoiceState.IDLE -> duoCardBg()
        ChoiceState.CORRECT -> DuoGreen.copy(alpha = if (isSystemInDarkTheme()) 0.18f else 0.10f)
        ChoiceState.WRONG -> DuoRed.copy(alpha = if (isSystemInDarkTheme()) 0.18f else 0.10f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(if (state == ChoiceState.IDLE) 1.5.dp else 2.5.dp, border, RoundedCornerShape(14.dp))
            .clickable(enabled = state == ChoiceState.IDLE, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            when (state) {
                ChoiceState.CORRECT -> Text("✓", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DuoGreen)
                ChoiceState.WRONG -> Text("✕", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DuoRed)
                ChoiceState.IDLE -> Unit
            }
        }
    }
}

/** Listening prompt: a big speaker button plus a replay hint. */
@Composable
internal fun ListenPrompt(sentence: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DuoBlue.copy(alpha = 0.15f))
                .clickable { speakSentence(sentence) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.VolumeUp, contentDescription = "再听一次", tint = DuoBlue)
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = "点喇叭可以再听一次",
            fontSize = 13.sp,
            color = duoTextMuted(),
        )
    }
}

/** Speaks [text] through the existing TTS pipeline. Fire-and-forget. */
internal fun speakSentence(text: String) {
    if (text.isBlank()) return
    // Synthesis blocks, playback does not: fetch on IO, then play on Main.
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        runCatching {
            val client = SpeechClient(prefs.translation.baseUrl.get(), prefs.translation.apiKey.get())
            val wav = client.synthesize(text)
            withContext(Dispatchers.Main) { AudioPlayer.play(wav) }
        }.onFailure { flogError { "yijian exercise tts: ${it.message}" } }
    }
}

/** Human-readable name for an exercise type from either generator. */
internal fun typeHint(type: String): String = when (type) {
    "CLOZE" -> "选出正确的词填进空格"
    "LISTEN" -> "听一句英文，选出你听到的内容"
    "FIX_ERROR" -> "这句话有一处错，选出正确版本"
    "MEANING" -> "这句话是什么意思"
    else -> "把这句话翻回英文"
}

/** Always-available fallback prompt for the listening exercise. */
private fun evidenceSpeak(card: ReviewCard): String =
    card.entry.corrected.ifBlank { card.entry.original }

/**
 * Shows the error this sentence is about, before the question is answered.
 * The learner is meant to fail this point a few times and then stop failing it, so naming the
 * error up front is the whole purpose of the card.
 */
@Composable
internal fun MistakeBanner(wrong: String, right: String, why: String, extra: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DuoGold.copy(alpha = if (isSystemInDarkTheme()) 0.18f else 0.22f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠", fontSize = 13.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "你的易错点",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            if (extra > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "+$extra",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$wrong  →  $right",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (why.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = why,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

// ---- exercise detail (source + history) ---------------------------------------------

/**
 * Everything behind the current question, behind a single "详情" toggle:
 *
 * - where the sentence came from (the user's own message, and when)
 * - what they wrote versus what it should be
 * - every correction, tagged by kind, with the rule
 * - how many times this card has been seen and missed
 *
 * The point is that answering wrong is not a dead end: the learner can immediately see what
 * they were trying to say and why it was wrong.
 */
@Composable
internal fun ExerciseDetail(
    card: ReviewCard,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** Model-written generalisation of the pattern, for cards with no recorded mistake. */
    usage: String = "",
    moreExamples: List<String> = emptyList(),
) {
    val entry = card.entry
    val dateText = remember(entry.timestamp) {
        java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(entry.timestamp))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(duoCardBg()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "题目详情 ▴" else "题目详情 ▾",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = DuoBlue,
                modifier = Modifier.weight(1f),
            )
            // Only worth showing once the card has actually been practised.
            if (card.reps > 0) {
                Text(
                    text = "练过 ${card.reps} 次" + if (card.lapses > 0) " · 忘过 ${card.lapses} 次" else "",
                    fontSize = 10.sp,
                    color = duoTextMuted(),
                )
            }
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                DetailSection("来源") {
                    Text(
                        text = "$dateText · 你当时在译键里输入",
                        fontSize = 11.sp,
                        color = duoTextMuted(),
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "“${entry.original.ifBlank { entry.corrected }}”",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
                if (entry.original.isNotBlank() && entry.original != entry.corrected) {
                    DetailSection("地道说法") {
                        Text(entry.corrected, fontSize = 14.sp, lineHeight = 20.sp, color = DuoGreen)
                    }
                }
                if (entry.translation.isNotBlank()) {
                    DetailSection("中文") {
                        Text(entry.translation, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
                if (entry.corrections.isNotEmpty()) {
                    DetailSection("你当时的错误") {
                        entry.corrections.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TagChip(c.tag)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${c.wrong} → ${c.right}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            if (c.why.isNotBlank()) {
                                Text(
                                    text = c.why,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = duoTextMuted(),
                                    modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 4.dp),
                                )
                            }
                        }
                    }
                }
                if (entry.note.isNotBlank()) {
                    DetailSection("老师点评") {
                        Text(entry.note, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
                // No mistake recorded: the lesson is the pattern, so show how far it reaches.
                if (usage.isNotBlank() || moreExamples.isNotEmpty()) {
                    DetailSection("举一反三") {
                        if (usage.isNotBlank()) {
                            Text(usage, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                        moreExamples.forEach { example ->
                            Row(modifier = Modifier.padding(top = 4.dp)) {
                                Text("·", fontSize = 13.sp, color = DuoGreen)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(example, fontSize = 13.sp, lineHeight = 19.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = title,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = duoTextMuted(),
    )
    Spacer(modifier = Modifier.height(3.dp))
    content()
}

/** Coloured label for the mistake kind, so the weak point is readable at a glance. */
@Composable
private fun TagChip(tag: String) {
    val color = when (tag) {
        "拼写" -> DuoRed
        "语法" -> DuoBlue
        "用词" -> DuoGold
        else -> DuoGreen
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = tag.ifBlank { "表达" },
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}
