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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.design.Shape
import dev.patrickgold.florisboard.app.design.Space
import dev.patrickgold.florisboard.app.design.YijianTheme
import kotlin.math.sin

/**
 * 练习完成.
 *
 * The old version fired confetti in six saturated colours with a glowing ring behind a trophy.
 * That belongs to a game; on a paper surface it is noise, and it also promises a celebration
 * the app cannot repeat every session without becoming tiring.
 *
 * What replaces it: 咕咕 arrives, the counts settle, and the page stays quiet. The reward is
 * the sentence you can read, not the display.
 */
@Composable
fun SessionCelebration(
    total: Int,
    good: Int,
    onFinish: () -> Unit,
) {
    val colors = YijianTheme.colors
    val type = YijianTheme.type

    // 咕咕 rises into place once.
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        rise.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 180f))
    }

    // Numbers settle as the bird lands.
    val count = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        count.animateTo(1f, tween(durationMillis = 700, delayMillis = 150, easing = LinearEasing))
    }

    // A slow breath, so the page is not completely still.
    val breath = rememberInfiniteTransition(label = "breath")
    val drift by breath.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift",
    )

    val allCorrect = total > 0 && good == total
    val shownTotal = (total * count.value).toInt()
    val shownGood = (good * count.value).toInt()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .padding(horizontal = Space.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Image(
            painter = painterResource(
                if (allCorrect) R.drawable.ic_gugu_happy else R.drawable.ic_gugu_front
            ),
            contentDescription = "咕咕",
            modifier = Modifier
                .size(132.dp)
                .graphicsLayer {
                    alpha = rise.value
                    translationY = (1f - rise.value) * 24.dp.toPx()
                    scaleX = 0.92f + rise.value * 0.08f
                    scaleY = 0.92f + rise.value * 0.08f
                    // Only once it has landed, so the entrance is not muddied by the bob.
                    if (rise.value >= 1f) translationY += sin(drift * 2f) * 2.dp.toPx()
                },
        )

        Spacer(modifier = Modifier.height(Space.xl))
        Text(text = "练习完成", style = type.title, color = colors.ink)
        Spacer(modifier = Modifier.height(Space.sm))
        Text(
            text = if (allCorrect) "全部记得" else "$shownGood / $shownTotal 记得",
            style = type.small,
            color = colors.inkMuted,
        )

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(colors.accent, Shape.action)
                .clickable(onClick = onFinish),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "完成",
                style = type.body,
                fontWeight = FontWeight.Medium,
                color = colors.onAccent,
            )
        }
        Spacer(modifier = Modifier.height(Space.xl))
    }
}
