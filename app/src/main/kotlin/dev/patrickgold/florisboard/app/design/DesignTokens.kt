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

package dev.patrickgold.florisboard.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 译键 design tokens — 「安静的纸与清晰的墨」.
 *
 * This app is not a course. The real scenario is: someone is in a chat, types a line of
 * English, wants the natural phrasing, and carries on. Borrowing a gamified course's visual
 * language for that (coloured cards competing for attention, a daily ring, streak counters)
 * tells the user they are behind, which is the opposite of what a writing aid should say.
 *
 * So the system is built on three decisions:
 *
 * 1. Near-monochrome. Paper and ink carry the interface; a single accent marks the one thing
 *    that needs your attention right now. Colour earns its place by meaning something, never
 *    by filling space.
 * 2. Hierarchy through space and type, not containers. Sections are separated by rhythm and
 *    weight; a rule line replaces a card where a card would only add noise.
 * 3. Numbers set as data, not as trophies. Figures sit in the text at text scale, in a
 *    tabular face, instead of inside rings and badges.
 *
 * Every value below is a semantic name. Screen code takes from here and never writes a
 * literal hex, a font size, a corner radius or a spacing number of its own.
 */

// ---- Colour --------------------------------------------------------------------------

/**
 * Ink and paper, per surface.
 *
 * Only five roles exist. Anything that needs a sixth gets it by weight or spacing, which is
 * what keeps screens from turning into a pile of shades.
 */
data class YijianColors(
    /** Page background — the "paper". */
    val paper: Color,
    /** The surface of a raised element, one step off the paper. */
    val sheet: Color,
    /** Primary text and anything drawn with the same authority. */
    val ink: Color,
    /** Secondary text: labels, metadata, supporting sentences. */
    val inkMuted: Color,
    /** Hairline rules and borders. Never a fill. */
    val rule: Color,
    /** The single accent. Reserved for the one action or state that matters now. */
    val accent: Color,
    /** Text that sits on [accent]. */
    val onAccent: Color,
    /** A correct result. Used sparingly — a single element per answer, not a whole panel. */
    val positive: Color,
    /** An incorrect result. */
    val negative: Color,
    /** Something needs attention before you can use the app (e.g. keyboard not selected). */
    val warning: Color,
)

private val LightColors = YijianColors(
    paper = Color(0xFFFBFAF7),      // warm off-white, like uncoated stock
    sheet = Color(0xFFFFFFFF),
    ink = Color(0xFF1A1A1A),
    inkMuted = Color(0xFF6B6B6B),
    rule = Color(0xFFE2E0DA),
    accent = Color(0xFF2F6B45),     // deep green, closer to ink than to grass
    onAccent = Color(0xFFFFFFFF),
    positive = Color(0xFF2F6B45),
    negative = Color(0xFF9B2C2C),
    warning = Color(0xFF8A5A00),
)

private val DarkColors = YijianColors(
    paper = Color(0xFF121212),
    sheet = Color(0xFF1C1C1C),
    ink = Color(0xFFEDEAE4),
    inkMuted = Color(0xFF9A968E),
    rule = Color(0xFF2E2E2E),
    accent = Color(0xFF7FB08F),     // lifted so it carries on a dark ground
    onAccent = Color(0xFF10231A),
    positive = Color(0xFF7FB08F),
    negative = Color(0xFFD98A8A),
    warning = Color(0xFFD9AE6A),
)

/** The active palette and type scale. Screens read `YijianTheme.colors` / `.type`. */
object YijianTheme {
    val colors: YijianColors
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) DarkColors else LightColors

    /** The type scale. Not theme-dependent — colour carries the theme, type does not. */
    val type: YijianType get() = BaseType
}

// ---- Type ----------------------------------------------------------------------------

/**
 * Five steps, no more.
 *
 * The old screens carried fourteen sizes between 10 and 56 sp; 11/12/13/14/15 sat close
 * enough that the differences read as inconsistency rather than hierarchy. Each step here
 * differs from its neighbour by at least a third, so the eye can actually use it.
 */
data class YijianType(
    /** The one thing the screen is about. Used once per screen. */
    val display: TextStyle,
    /** Section headings. */
    val title: TextStyle,
    /** The default reading size for body content and prompts. */
    val body: TextStyle,
    /** Secondary sentences, captions, metadata. */
    val small: TextStyle,
    /** All-caps labels and overlines. */
    val label: TextStyle,
    /** Tabular figures, for numbers that sit in body text. */
    val figure: TextStyle,
)

private val BaseType = YijianType(
    display = TextStyle(
        fontSize = 32.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    ),
    title = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    body = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    small = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
    ),
    label = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
    ),
    // Tabular so columns of numbers line up and do not jitter as they change.
    figure = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
    ),
)

// ---- Shape ---------------------------------------------------------------------------

/**
 * Two radii, because there are two kinds of thing: things you touch, and things that hold
 * content. Nothing else justifies a third size.
 */
object Shape {
    /** Buttons, chips, anything with a hit target. */
    val action = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    /** Content containers. */
    val sheet = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    /** Pills. */
    val pill = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)
}

// ---- Space ---------------------------------------------------------------------------

/**
 * A 4-based scale with names that say what they are for, so a screen never has to invent a
 * number. `Space.section` between blocks, `Space.gutter` at the page edge, and so on.
 */
object Space {
    val hair = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Page edge inset. */
    val gutter = 20.dp
    /** Between two distinct blocks on a screen. */
    val section = 28.dp
}

// ---- Elevation and strokes -----------------------------------------------------------

object Stroke {
    val hairline = 1.dp
    val emphasis = 2.dp
}

/** Standard opacity for de-emphasised content, applied to [YijianColors.inkMuted]. */
const val MUTED = 1f
