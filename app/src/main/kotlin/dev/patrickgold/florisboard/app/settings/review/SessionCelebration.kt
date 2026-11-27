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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import dev.patrickgold.florisboard.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** One piece of confetti with its own launch angle, speed and colour. */
private data class Confetti(
    val angle: Float,
    val speed: Float,
    val spin: Float,
    val size: Float,
    val color: Color,
    val delayMillis: Int,
)

private val ConfettiColors = listOf(
    DuoGreen, DuoBlue, DuoGold, DuoRed,
    Color(0xFFCE82FF), Color(0xFFFF9600),
)

/**
 * End-of-session celebration: the trophy pops in, confetti bursts outward, the numbers count
 * up, and a button returns to the review tab.
 *
 * Deliberately short — about 1.6s — so it feels like a reward rather than a wait.
 */
@Composable
fun SessionCelebration(
    total: Int,
    good: Int,
    onFinish: () -> Unit,
) {
    // Trophy entrance: a single spring pop.
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 220f))
    }

    // Confetti travels outward once, then stops (no looping animation to drain battery).
    val burst = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        burst.animateTo(1f, tween(durationMillis = 1400, easing = LinearEasing))
    }

    val confetti = remember {
        List(38) { i ->
            Confetti(
                angle = (i / 38f) * 360f + Random.nextFloat() * 10f - 5f,
                speed = 0.55f + Random.nextFloat() * 0.65f,
                spin = Random.nextFloat() * 720f - 360f,
                size = 5f + Random.nextFloat() * 6f,
                color = ConfettiColors[Random.nextInt(ConfettiColors.size)],
                delayMillis = Random.nextInt(0, 220),
            )
        }
    }

    // A gentle glow behind the trophy while the screen is open.
    val glow = rememberInfiniteTransition(label = "glow")
    val glowScale by glow.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowScale",
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Confetti layer, drawn behind the content.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val originX = size.width / 2f
            val originY = size.height * 0.34f
            val maxTravel = size.minDimension * 0.55f
            confetti.forEach { c ->
                val t = burst.value
                if (t <= 0f) return@forEach
                // Stagger each piece's start so the burst looks natural.
                val local = ((t * 1400f - c.delayMillis) / 1000f).coerceIn(0f, 1f)
                if (local <= 0f) return@forEach
                // Ease out plus slight gravity.
                val eased = 1f - (1f - local) * (1f - local)
                val rad = Math.toRadians(c.angle.toDouble())
                val dist = maxTravel * c.speed * eased
                val x = originX + (cos(rad) * dist).toFloat()
                val y = originY + (sin(rad) * dist).toFloat() + 120f * local * local
                val alpha = (1f - local * 0.75f).coerceIn(0f, 1f)
                rotate(degrees = c.spin * local, pivot = Offset(x, y)) {
                    drawRect(
                        color = c.color.copy(alpha = alpha),
                        topLeft = Offset(x - c.size / 2f, y - c.size / 4f),
                        size = Size(c.size, c.size / 2f),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 咕咕 takes the trophy's place: the mascot celebrating with you reads warmer
            // than a generic cup, and it can express the result — wings up when every card
            // came back, calm and friendly otherwise.
            val allCorrect = total > 0 && good == total
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(200.dp)) {
                    drawCircle(
                        color = DuoBlue.copy(alpha = 0.14f),
                        radius = (size.minDimension / 2f) * glowScale,
                    )
                }
                Image(
                    painter = painterResource(
                        if (allCorrect) R.drawable.ic_gugu_happy else R.drawable.ic_gugu_front
                    ),
                    contentDescription = "咕咕",
                    modifier = Modifier
                        .size(150.dp)
                        .graphicsLayer {
                            scaleX = pop.value.coerceIn(0f, 1.2f)
                            scaleY = pop.value.coerceIn(0f, 1.2f)
                            // A slow breathing bob once it has landed.
                            translationY = if (pop.value >= 1f) {
                                kotlin.math.sin(glowScale * 3f) * 3.dp.toPx()
                            } else 0f
                        },
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "练习完成！",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = DuoGreen,
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Numbers count up as the burst settles.
            val progress = burst.value.coerceIn(0f, 1f)
            val shownTotal = (total * progress).toInt()
            val shownGood = (good * progress).toInt()
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                ScoreChip(value = "$shownTotal", label = "练习", color = DuoBlue)
                ScoreChip(value = "$shownGood", label = "记得", color = DuoGreen)
            }

            if (total > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "正确率 ${shownGood * 100 / total.coerceAtLeast(1)}%",
                    fontSize = 13.sp,
                    color = duoTextMuted(),
                )
            }

            Spacer(modifier = Modifier.height(36.dp))
            DuoButton(
                text = "完成",
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (good == total) "全对，太厉害了" else "记住 $good 句，明天见",
                fontSize = 12.sp,
                color = duoTextMuted(),
            )
        }
    }
}

@Composable
private fun ScoreChip(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = color)
        Text(text = label, fontSize = 11.sp, color = duoTextMuted())
    }
}
