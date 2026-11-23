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

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek语音接口：
 * - `POST /v1/tools/asr`  multipart `file` 字段，16-bit PCM WAV 或 Ogg Opus，≤8 MiB / ≤120 s
 * - `POST /v1/tools/tts`  JSON `{text, voice_id, format}`，返回 audio/wav 流
 *
 * 语言由服务端自动判定（ASR 支持中/英/粤/日/韩等，TTS 支持中/英/粤/日/韩等），
 * 所以中英文混说不需要额外参数。
 */
class SpeechClient(private val baseUrl: String, private val apiKey: String) {

    data class Transcript(val text: String, val durationMs: Long)

    /**
     * Transcribes a WAV payload. Throws with a user-presentable Chinese message on failure.
     */
    fun transcribe(wav: ByteArray): Transcript {
        require(apiKey.isNotBlank()) { "还没填 API Key" }
        val boundary = "----yijian" + System.nanoTime()
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val body = ByteArrayOutputStream(head.size + wav.size + tail.size).apply {
            write(head); write(wav); write(tail)
        }.toByteArray()

        val conn = (URL("${baseUrl.trimEnd('/')}/v1/tools/asr").openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() }?.decodeToString().orEmpty()
            if (code !in 200..299) throw SpeechException(asrError(code, text))
            val jo = JSONObject(text)
            return Transcript(
                text = jo.optString("text").trim(),
                durationMs = jo.optLong("duration_ms"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Synthesises [text] to WAV bytes. Used for "read the translation aloud".
     */
    fun synthesize(text: String, voiceId: String = "stella"): ByteArray {
        require(apiKey.isNotBlank()) { "还没填 API Key" }
        val payload = JSONObject()
            .put("text", text)
            .put("voice_id", voiceId)
            .put("format", "wav")
            .toString()
            .toByteArray(Charsets.UTF_8)
        val conn = (URL("${baseUrl.trimEnd('/')}/v1/tools/tts").openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }
        try {
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { it.readBytes() }?.decodeToString().orEmpty()
                throw SpeechException(ttsError(code, err))
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun asrError(code: Int, body: String): String = when (code) {
        400 -> "音频格式不被支持"
        401, 403 -> "Key 无效（$code）"
        408 -> "上传超时"
        413 -> "录音太大（超过 8 MiB）"
        429 -> "请求太频繁，稍后再试"
        503 -> "服务繁忙，稍后再试"
        504 -> "识别超时，稍后再试"
        else -> "语音识别失败（HTTP $code）"
    }

    private fun ttsError(code: Int, body: String): String = when (code) {
        400 -> if (body.contains("Content Exists Risk")) "内容未通过审核" else "没有可朗读的文本"
        401, 403 -> "Key 无效（$code）"
        429 -> "请求太频繁，稍后再试"
        503 -> "服务繁忙，稍后再试"
        504 -> "合成超时，稍后再试"
        else -> "语音合成失败（HTTP $code）"
    }
}

class SpeechException(message: String) : Exception(message)
