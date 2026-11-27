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

package dev.patrickgold.florisboard.app.main

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.ime.translation.review.ReviewStore
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import org.florisboard.lib.compose.FlorisErrorCard
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.compose.FlorisWarningCard

private val HomeGreen = Color(0xFF58CC02)

@Composable
private fun cardBg(): Color =
    if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF1F2023) else Color.White

private val SheetShape = RoundedCornerShape(16.dp)

/**
 * Home tab: input-method status, today's review summary and quick access to the two things
 * people actually do — type (nothing to do, just use the keyboard) and practise.
 */
@Composable
fun MainHomeScreen() {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val isImeEnabled by InputMethodUtils.observeIsFlorisboardEnabled()
    val isImeSelected by InputMethodUtils.observeIsFlorisboardSelected()
    var due by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        val s = ReviewStore.snapshot()
        due = s.dueCount + s.newCount
    }

    FlorisScreenNg(
        title = "译键",
        previewFieldVisible = true,
        navigationIconVisible = false,
        mainNavigationVisible = true,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // Status: the keyboard must be enabled and selected to be usable at all.
            if (!isImeEnabled) {
                FlorisErrorCard(
                    showIcon = false,
                    text = stringRes(R.string.settings__home__ime_not_enabled),
                    onClick = { InputMethodUtils.showImeEnablerActivity(context) },
                )
            } else if (!isImeSelected) {
                FlorisWarningCard(
                    showIcon = false,
                    text = stringRes(R.string.settings__home__ime_not_selected),
                    onClick = { InputMethodUtils.showImePicker(context) },
                )
            } else {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = HomeGreen,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "译键已启用，在任意输入框长按空格即可语音翻译",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }

            // Today's practice card
            Card(
                shape = SheetShape,
                colors = CardDefaults.cardColors(containerColor = cardBg()),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clickable { navController.navigate(Routes.Settings.Review) },
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.School,
                        contentDescription = null,
                        tint = HomeGreen,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("今日练习", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = when (val d = due) {
                                null -> "…"
                                0 -> "已全部完成，明天见"
                                else -> "有 $d 张卡片待复习"
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }
                    Text(
                        text = "去练习 ›",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = HomeGreen,
                    )
                }
            }

            // How to use
            Card(
                shape = SheetShape,
                colors = CardDefaults.cardColors(containerColor = cardBg()),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("怎么用", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Tip("①", "在任意输入框里打英文，译键会自动给出地道说法和中文")
                    Tip("②", "长按空格说话，语音直接转成英文再翻译")
                    Tip("③", "翻译过的句子会自动变成复习卡片，去「复习」页巩固")
                }
            }

            // Quick translation settings
            Card(
                shape = SheetShape,
                colors = CardDefaults.cardColors(containerColor = cardBg()),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clickable { navController.navigate(Routes.Settings.Translation) },
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("翻译设置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (prefs.translation.enabled.get()) "AI 翻译已开启" else "AI 翻译未开启",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }
                    Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
            }

            Text(
                text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 20.dp),
            )
        }
    }
}

@Composable
private fun Tip(marker: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(marker, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HomeGreen)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}
