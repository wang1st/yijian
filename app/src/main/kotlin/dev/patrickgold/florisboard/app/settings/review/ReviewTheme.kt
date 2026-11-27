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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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

/**
 * Semantic palette, taken from Duolingo's live colour tokens rather than eyeballed.
 *
 * Names follow their convention (animals) so the intent survives: a colour is chosen by
 * meaning — correct, wrong, progress, neutral — never by what it happens to look like.
 */
internal val DuoGreen = Color(0xFF58CC02)   // owl       — primary, progress, correct button
internal val DuoGreenShade = Color(0xFF58A700) // tree-frog — green lip, correct heading text
internal val DuoBlue = Color(0xFF1CB0F6)    // macaw     — neutral forward action, audio
internal val DuoGold = Color(0xFFFFC800)    // bee       — skipped / warning
internal val DuoBlueShade = Color(0xFF1899D6)  // whale  — blue lip, selected text
internal val DuoRed = Color(0xFFFF4B4B)     // cardinal  — wrong button face
internal val DuoRedShade = Color(0xFFEA2B2B)   // fire-ant — red lip, error text

// Surfaces for answer states.
internal val DuoSeaSponge = Color(0xFFD7FFB8)  // correct fill
internal val DuoTurtle = Color(0xFFA5ED6E)     // correct border/lip
internal val DuoWalkingFish = Color(0xFFFFDFE0) // wrong fill
internal val DuoIguana = Color(0xFFDDF4FF)     // selected fill
internal val DuoBlueJay = Color(0xFF84D8FF)    // selected border/lip
internal val DuoSwan = Color(0xFFE5E5E5)       // neutral border, disabled, used chip
internal val DuoHare = Color(0xFFAFAFAF)       // disabled label, secondary text
internal val DuoEel = Color(0xFF4B4B4B)        // answer text
internal val DuoInk = Color(0xFF3C3C3C)        // prompt text
internal val DuoBeeSkipped = Color(0xFFFFF5D3) // skipped panel fill
internal val DuoFox = Color(0xFFFF9600)        // mistakes-review chip
internal val DuoGuineaPig = Color(0xFFCD7900)  // skipped heading

/** 2 dp lip under every answer surface; 4 dp under the primary button. */
internal val CardLip = 2.dp
internal val ButtonLip = 4.dp

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
