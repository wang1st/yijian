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

package dev.patrickgold.florisboard.ime.translation

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.lib.devtools.flogError
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports the practice history and review schedule as a single JSON file.
 *
 * This data is the user's own learning record and cannot be regenerated (only logged from now
 * on), so there is always a way to get a copy off the device without relying on Android's
 * auto-backup, which is switched off on many ROMs and never reaches the cloud here.
 *
 * Saved to the public Downloads collection so any file manager, cloud app or chat app can pick
 * it up; no storage permission is required on API 29+.
 */
object PracticeExporter {
    data class Result(val ok: Boolean, val uri: Uri? = null, val fileName: String = "", val message: String = "")

    fun export(): Result {
        val context = FlorisApplication.getHack()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val fileName = "yijian-practice-$stamp.json"
        return try {
            val payload = buildPayload(context).toString(2)
            val uri = writeToDownloads(context, fileName, payload)
            Result(ok = uri != null, uri = uri, fileName = fileName,
                message = if (uri != null) "已导出：$fileName" else "导出失败")
        } catch (e: Exception) {
            flogError { "yijian export: ${e.message}" }
            Result(ok = false, message = "导出失败：${e.message}")
        }
    }

    private fun buildPayload(context: Context): JSONObject {
        val entries = readAll(context)
        return JSONObject().apply {
            put("exportedAt", System.currentTimeMillis())
            put("app", "yijian")
            put("version", 1)
            put("count", entries.size)
            put("entries", org.json.JSONArray().apply {
                entries.forEach { put(it) }
            })
        }
    }

    private fun readAll(context: Context): List<JSONObject> {
        val file = File(context.filesDir, "yijian_practice.jsonl")
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
    }

    private fun writeToDownloads(context: Context, fileName: String, content: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        // API 26-28: app-specific external dir needs no permission and is user-visible.
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "")
        dir.mkdirs()
        val out = File(dir, fileName)
        out.writeText(content)
        return Uri.fromFile(out)
    }
}
