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

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Every answer surface uses a 12 dp radius; buttons use 16 dp. */
internal val AnswerRadius = RoundedCornerShape(12.dp)
internal val ButtonRadius = RoundedCornerShape(16.dp)

/** Visual state of an answer surface, mapped to a colour triple. */
enum class SurfaceState { Neutral, Selected, Correct, Wrong, Done }

private data class SurfaceColors(val fill: Color, val line: Color, val text: Color)

@Composable
private fun colorsFor(state: SurfaceState): SurfaceColors {
    val dark = isSystemInDarkTheme()
    // Dark theme keeps the same semantic mapping but dims the fills so they do not glare.
    fun mix(c: Color) = if (dark) c.copy(alpha = 0.22f) else c
    return when (state) {
        SurfaceState.Neutral -> SurfaceColors(
            fill = if (dark) Color(0xFF1F2023) else Color.White,
            line = if (dark) Color(0xFF4A4D53) else DuoSwan,
            text = if (dark) Color(0xFFEDEDED) else DuoEel,
        )
        SurfaceState.Selected -> SurfaceColors(mix(DuoIguana), DuoBlueJay, whaleText(dark))
        SurfaceState.Correct -> SurfaceColors(mix(DuoSeaSponge), DuoTurtle, DuoGreenShade)
        SurfaceState.Wrong -> SurfaceColors(mix(DuoWalkingFish), DuoRed, DuoRedShade)
        SurfaceState.Done -> SurfaceColors(
            fill = if (dark) Color(0xFF26272B) else DuoSwan,
            line = Color.Transparent,
            text = Color.Transparent,
        )
    }
}

private fun whaleText(dark: Boolean): Color = if (dark) Color(0xFF7FD4F5) else DuoBlueShade

/**
 * The base answer surface: a 2 dp border plus a 2 dp solid lip in the same colour, pressed
 * with a 2 dp sink. This "2 + 2" treatment is what makes every row and word tile read as a
 * physical key rather than a coloured rectangle.
 *
 * Taken from measurement of Duolingo's lesson player rather than impression.
 */
@Composable
internal fun AnswerSurface(
    state: SurfaceState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
    verticalPadding: androidx.compose.ui.unit.Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = colorsFor(state)
    val hasLip = state != SurfaceState.Done
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateDpAsState(
        targetValue = if (pressed && hasLip && onClick != null) CardLip else 0.dp,
        animationSpec = tween(100),
        label = "surfaceSink",
    )
    Box(
        modifier = modifier
            .padding(bottom = if (hasLip) CardLip else 0.dp)
            .offset(y = sink)
            .then(
                if (hasLip) Modifier.drawBehind {
                    // The lip: same rounded rect, extended past the face by 2 dp.
                    drawRoundRect(
                        color = c.line,
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width, size.height + CardLip.toPx()),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                    )
                } else Modifier
            )
            .clip(AnswerRadius)
            .background(c.fill)
            .then(if (hasLip) Modifier.border(2.dp, c.line, AnswerRadius) else Modifier)
            .clickable(
                enabled = onClick != null,
                interactionSource = interaction,
                indication = null,
            ) { onClick?.invoke() }
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides c.text) { content() }
    }
}

/** Colour triple for the primary action button in each verdict state. */
internal enum class ActionTone { Neutral, Correct, Wrong, Skipped }

private fun toneColors(tone: ActionTone): Pair<Color, Color> = when (tone) {
    ActionTone.Neutral -> DuoGreen to DuoGreenShade
    ActionTone.Correct -> DuoGreen to DuoGreenShade
    ActionTone.Wrong -> DuoRed to DuoRedShade
    ActionTone.Skipped -> DuoGold to Color(0xFFE7A601)
}

/**
 * The single bottom action button. Disabled it is flat grey with no lip; enabled it gains a
 * 4 dp darker lip and presses into it. The label and colour are driven by the caller so the
 * same component is "检查" before answering and "继续" after, as Duolingo does.
 */
@Composable
internal fun LessonButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ActionTone = ActionTone.Neutral,
) {
    val (accent, lip) = toneColors(tone)
    val face = if (enabled) accent else DuoSwan
    val base = if (enabled) lip else Color.Transparent
    val labelColor = if (enabled) Color.White else DuoHare

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateDpAsState(
        targetValue = if (pressed && enabled) ButtonLip else 0.dp,
        animationSpec = tween(100),
        label = "buttonSink",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)          // 50 dp face + 4 dp lip
            .offset(y = sink)
            .drawBehind {
                if (enabled) {
                    drawRoundRect(
                        color = base,
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(16.dp.toPx()),
                    )
                }
            }
            .padding(bottom = ButtonLip)
            .clip(ButtonRadius)
            .background(face)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = labelColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
        )
    }
}

/**
 * A full-bleed feedback panel with a square top edge that slides up from the bottom, holding
 * the Continue button inside it. This replaces the old inline feedback block, which appeared
 * abruptly and shifted the layout.
 */
@Composable
internal fun FeedbackPanelContent(
    correct: Boolean,
    heading: String,
    detail: String?,
    aiComment: String?,
    aiChecking: Boolean,
    onContinue: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val bg = when {
        !correct -> if (dark) Color(0x33FF4B4B) else DuoWalkingFish
        else -> if (dark) Color(0x3358CC02) else DuoSeaSponge
    }
    val ink = if (correct) DuoGreenShade else DuoRedShade
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = heading,
                color = ink,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 29.sp,
                modifier = Modifier.weight(1f),
            )
        }
        if (detail != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = detail, color = ink, fontSize = 17.sp, lineHeight = 24.sp)
        }
        if (aiChecking) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("AI 正在判分…", color = ink, fontSize = 13.sp)
        } else if (aiComment != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(aiComment, color = ink, fontSize = 15.sp, lineHeight = 21.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        LessonButton(
            text = "继续",
            enabled = true,
            onClick = onContinue,
            tone = if (correct) ActionTone.Correct else ActionTone.Wrong,
        )
    }
}

/** Small coloured category tag above the prompt. */
@Composable
internal fun SectionTag(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
    )
}

/** The 32 dp top bar: progress pill only (no hearts — this app has no lives). */
@Composable
internal fun LessonProgressBar(progress: Float, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isSystemInDarkTheme()) Color(0xFF2E3034) else DuoSwan),
        ) {
            // Explicit dp width from the measured constraint: a fractional fillMaxWidth inside a
            // weighted Box resolves against an unbounded width and renders nothing.
            Box(
                modifier = Modifier
                    .width(maxWidth * progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DuoGreen)
                    // The 3 dp shine inset 4 dp from the top, as measured.
                    .drawBehind {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.35f),
                            topLeft = Offset(0f, 4.dp.toPx()),
                            size = Size(size.width, 3.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    },
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DuoHare)
    }
}

/** A word-bank tile: 46 dp tall, 8 dp padding, flows across the line. */
@Composable
internal fun WordTile(text: String, onClick: () -> Unit) {
    AnswerSurface(
        state = SurfaceState.Neutral,
        onClick = onClick,
        horizontalPadding = 12.dp,
        verticalPadding = 0.dp,
        modifier = Modifier.height(46.dp),
    ) {
        Text(text = text, fontSize = 19.sp, lineHeight = 25.sp)
    }
}

/** A consumed word in the answer well: greyed, lip-less, sunk — but still tappable to undo. */
@Composable
internal fun PlacedWordTile(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(46.dp)
            .padding(bottom = CardLip)
            .offset(y = CardLip)
            .clip(AnswerRadius)
            .background(if (isSystemInDarkTheme()) Color(0xFF26272B) else DuoSwan)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Big circular speaker button for listening exercises (89 dp, 25% radius, 4 dp lip). */
@Composable
internal fun SpeakerButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateDpAsState(
        targetValue = if (pressed) ButtonLip else 0.dp,
        animationSpec = tween(100),
        label = "speakerSink",
    )
    Box(
        modifier = Modifier
            .size(89.dp)
            .offset(y = sink)
            .drawBehind {
                drawRoundRect(
                    color = DuoBlueShade,
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(size.width * 0.25f),
                )
            }
            .padding(bottom = ButtonLip)
            .clip(RoundedCornerShape(percent = 25))
            .background(DuoBlue)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.VolumeUp,
            contentDescription = "再听一次",
            tint = Color.White,
            modifier = Modifier.size(36.dp),
        )
    }
}

/** A misfire-proof icon button used in the feedback row. */
@Composable
internal fun SmallIconButton(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
    }
}
