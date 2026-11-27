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

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.ime.translation.EnglishTeacher
import dev.patrickgold.florisboard.ime.translation.review.GeneratedCard
import dev.patrickgold.florisboard.ime.translation.review.ReviewAi
import dev.patrickgold.florisboard.ime.translation.review.ReviewStore
import kotlinx.coroutines.launch

/**
 * Generates practice sentences with the model, aimed at the user's own weak spots.
 *
 * This is what makes the review queue endless: the practice log only holds sentences the user
 * happened to type, which is not enough to build a learning path from.
 */
@Composable
fun ReviewGenerateScreen() {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(false) }
    var cards by remember { mutableStateOf<List<GeneratedCard>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(0) }
    var focus by remember { mutableStateOf<String?>(null) }

    fun generate() {
        loading = true
        error = null
        saved = 0
        scope.launch {
            val entries = EnglishTeacher.readEntriesStatic()
            val samples = entries.flatMap { e -> e.corrections.map { "${it.wrong} → ${it.right}" } }.takeLast(30)
            focus = if (samples.isEmpty()) null else ReviewAi.analyzeWeaknesses(samples).firstOrNull()?.topic
            val generated = ReviewAi.generateCards(focus, count = 5)
            cards = generated
            if (generated.isEmpty()) error = "没生成成功，检查一下模型设置再试"
            loading = false
        }
    }

    LaunchedEffect(Unit) { generate() }

    FlorisScreenNg(
        title = "专项练习",
        previewFieldVisible = false,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = if (focus.isNullOrBlank()) "根据你的水平生成的日常英语练习"
                else "针对你的薄弱点：$focus",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
            )

            when {
                loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = DuoGreen, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("正在出题…", fontSize = 14.sp, color = duoTextMuted())
                }

                error != null -> Column(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
                    Text(error!!, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(12.dp))
                    DuoSecondaryButton("重试", onClick = { generate() }, modifier = Modifier.fillMaxWidth())
                }

                else -> {
                    cards.forEachIndexed { i, card ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSystemInDarkTheme()) Color(0xFF1F2023) else Color.White
                            ),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${i + 1}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DuoGreen,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = card.focus,
                                        fontSize = 11.sp,
                                        color = duoTextMuted(),
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(card.english, fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(card.chinese, fontSize = 14.sp, color = duoTextMuted())
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    DuoButton(
                        text = if (saved > 0) "已加入 $saved 张" else "全部加入复习",
                        onClick = {
                            if (saved > 0) {
                                navController.navigate(Routes.Settings.Review)
                            } else {
                                scope.launch {
                                    saved = ReviewStore.addGeneratedCards(
                                        cards.map {
                                            ReviewStore.GeneratedSentence(it.english, it.chinese, it.focus)
                                        }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = { generate() },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("换一批", fontSize = 13.sp, color = DuoBlue, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
