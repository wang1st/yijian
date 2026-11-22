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
    val apiKey by prefs.translation.apiKey.collectAsState()
    val baseUrl by prefs.translation.baseUrl.collectAsState()
    val model by prefs.translation.model.collectAsState()

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
                    onSave = { prefs.translation.apiKey.set(it) },
                )
                TextEditPreference(
                    title = stringRes(R.string.pref__translation__base_url__label),
                    summary = "https://api.deepseek.com",
                    value = baseUrl,
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    onSave = { prefs.translation.baseUrl.set(it) },
                )
                TextEditPreference(
                    title = stringRes(R.string.pref__translation__model__label),
                    summary = "deepseek-chat",
                    value = model,
                    shapes = ListItemDefaults.segmentedShapes(2, count),
                    onSave = { prefs.translation.model.set(it) },
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
