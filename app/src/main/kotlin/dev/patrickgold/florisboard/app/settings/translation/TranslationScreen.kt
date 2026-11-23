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

package dev.patrickgold.florisboard.app.settings.translation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.components.ScrollableScreenColumn
import dev.patrickgold.florisboard.app.components.SegmentedListColumn
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import org.florisboard.lib.compose.stringRes

@Composable
fun TranslationScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val apiKey by prefs.translation.apiKey.collectAsState()
    val baseUrl by prefs.translation.baseUrl.collectAsState()
    val model by prefs.translation.model.collectAsState()
    val emailTo by prefs.teacher.emailTo.collectAsState()
    val smtpHost by prefs.teacher.smtpHost.collectAsState()
    val smtpUser by prefs.teacher.smtpUser.collectAsState()
    val smtpPassword by prefs.teacher.smtpPassword.collectAsState()
    val wordThreshold by prefs.teacher.reportWordThreshold.collectAsState()

    FlorisScreenNg(
        title = stringRes(R.string.settings__translation__title),
        previewFieldVisible = true,
    ) { contentPadding ->
        ScrollableScreenColumn(contentPadding) {
            SegmentedListColumn {
                SwitchPreference(
                    prefs.translation.enabled,
                    title = stringRes(R.string.pref__translation__enabled__label),
                    summary = stringRes(R.string.pref__translation__enabled__summary),
                )
            }
            SegmentedListColumn(title = stringRes(R.string.pref__translation__group_service__label)) {
                val count = 3
                TextEditPreference(
                    title = stringRes(R.string.pref__translation__api_key__label),
                    summary = stringRes(R.string.pref__translation__api_key__summary),
                    value = apiKey,
                    password = true,
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    onSave = { v -> scope.launch { prefs.translation.apiKey.set(v) } },
                )
                TextEditPreference(
                    title = stringRes(R.string.pref__translation__base_url__label),
                    summary = "https://api.deepseek.com",
                    value = baseUrl,
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    onSave = { v -> scope.launch { prefs.translation.baseUrl.set(v) } },
                )
                TextEditPreference(
                    title = stringRes(R.string.pref__translation__model__label),
                    summary = "deepseek-chat",
                    value = model,
                    shapes = ListItemDefaults.segmentedShapes(2, count),
                    onSave = { v -> scope.launch { prefs.translation.model.set(v) } },
                )
            }

            SegmentedListColumn(title = "英语老师 · 报告") {
                val count = 4
                TextEditPreference(
                    title = "满多少词发报告",
                    summary = "当前：每 ${wordThreshold} 词一份",
                    value = wordThreshold.toString(),
                    numeric = true,
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    onSave = { v -> scope.launch { prefs.teacher.reportWordThreshold.set(v.toIntOrNull() ?: 200) } },
                )
                TextEditPreference(
                    title = "收件邮箱",
                    summary = "报告发到这个地址",
                    value = emailTo,
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    onSave = { v -> scope.launch { prefs.teacher.emailTo.set(v) } },
                )
                TextEditPreference(
                    title = "SMTP 服务器",
                    summary = "例如 smtp.qq.com / smtp.163.com",
                    value = smtpHost,
                    shapes = ListItemDefaults.segmentedShapes(2, count),
                    onSave = { v -> scope.launch { prefs.teacher.smtpHost.set(v) } },
                )
                TextEditPreference(
                    title = "SMTP 账号",
                    summary = "通常是完整邮箱地址",
                    value = smtpUser,
                    shapes = ListItemDefaults.segmentedShapes(3, count),
                    onSave = { v -> scope.launch { prefs.teacher.smtpUser.set(v) } },
                )
            }

            SegmentedListColumn(title = "报告") {
                val teacher = dev.patrickgold.florisboard.FlorisApplication.getHack()
                    .imeController.teacher
                val pending by androidx.compose.runtime.collectAsState(
                    teacher.pendingWordCount,
                    initial = teacher.pendingWordCount.value,
                )
                // reportStatus is a plain StateFlow, not a jetpref PreferenceData
                val reportStatus by androidx.compose.runtime.collectAsState(
                    teacher.reportStatus,
                    initial = teacher.reportStatus.value,
                )
                Preference(
                    title = "立即生成并发送报告",
                    summary = reportStatus
                        ?: "已积累 $pending 词（满 ${wordThreshold} 词自动发送）",
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    onClick = { teacher.generateAndSendReport(force = true) },
                )
            }

            SegmentedListColumn(title = "SMTP 密码（授权码）") {
                TextEditPreference(
                    title = "授权码",
                    summary = "邮箱设置里生成的 SMTP 授权码，不是登录密码",
                    value = smtpPassword,
                    password = true,
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    onSave = { v -> scope.launch { prefs.teacher.smtpPassword.set(v) } },
                )
            }
        }
    }
}

@Composable
private fun TextEditPreference(
    title: String,
    summary: String,
    value: String,
    shapes: androidx.compose.material3.ListItemShapes,
    password: Boolean = false,
    numeric: Boolean = false,
    onSave: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Preference(
        title = title,
        summary = if (value.isBlank()) summary else if (password) "••••••••" else value,
        shapes = shapes,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        TextEditDialog(
            title = title,
            initialValue = value,
            password = password,
            numeric = numeric,
            onConfirm = { onSave(it); showDialog = false },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    initialValue: String,
    password: Boolean = false,
    numeric: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (numeric) androidx.compose.ui.text.input.KeyboardType.Number
                    else androidx.compose.ui.text.input.KeyboardType.Text,
                ),
                visualTransformation = if (password) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) {
                Text("确定", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
