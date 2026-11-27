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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Duolingo-inspired palette ------------------------------------------------------

internal val DuoGreen = Color(0xFF58CC02)
private val DuoGreenShade = Color(0xFF46A302)
internal val DuoBlue = Color(0xFF1CB0F6)
internal val DuoGold = Color(0xFFFFC800)
private val DuoBlueShade = Color(0xFF1899D6)
internal val DuoRed = Color(0xFFFF4B4B)
private val DuoRedShade = Color(0xFFD33131)

@Composable
internal fun duoCardBg(): Color =
    if (isSystemInDarkTheme()) Color(0xFF1F2023) else Color.White

@Composable
internal fun duoBorder(): Color =
    if (isSystemInDarkTheme()) Color(0xFF33353A) else Color(0xFFE5E5E5)

/** A border dark enough to read as an interactive control, not as a divider. */
@Composable
internal fun choiceBorder(): Color =
    if (isSystemInDarkTheme()) Color(0xFF4A4D53) else Color(0xFFD0D0D0)

@Composable
internal fun duoTextMuted(): Color =
    if (isSystemInDarkTheme()) Color(0xFFAFB1B6) else Color(0xFF777777)

/** Bottom-sheet radius used for cards and panels. */
private val SheetShape = RoundedCornerShape(16.dp)

/**
 * Pill button with the signature 3dp "pressed" bottom edge that shrinks on tap, like a
 * physical key. Used for every primary action in the review flow.
 */
@Composable
internal fun DuoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = DuoGreen,
    shade: Color = DuoGreenShade,
) {
    var pressed by remember { mutableStateOf(false) }
    val bg = if (enabled) color else color.copy(alpha = 0.35f)
    val edge = if (enabled) shade else shade.copy(alpha = 0.35f)
    Box(
        modifier = modifier
            .height(52.dp)
            .padding(bottom = if (pressed) 0.dp else 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(edge)
            .clickable(enabled = enabled) {
                pressed = true
                onClick()
                pressed = false
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (pressed) 52.dp else 49.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text.uppercase(),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

@Composable
internal fun DuoSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Color.Transparent else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (enabled) duoBorder() else duoBorder().copy(alpha = 0.4f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            else duoTextMuted().copy(alpha = 0.5f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---- review home --------------------------------------------------------------------
