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

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Records mono 16-bit PCM and wraps it as a WAV file, which is what DeepSeek's
 * `POST /v1/tools/asr` accepts.
 *
 * DeepSeek only supports whole-file recognition (no streaming), max 120 s and 8 MiB, so the
 * recorder stops itself at [MAX_SECONDS].
 */
class VoiceRecorder {
    companion object {
        /** 16 kHz mono is the smallest rate DeepSeek accepts and keeps files small. */
        const val SAMPLE_RATE = 16000
        const val MAX_SECONDS = 120
        const val MAX_BYTES = 8 * 1024 * 1024
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    var isRecording = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** True when a microphone is available at all. */
    fun canRecord(): Boolean {
        return try {
            val min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            min > 0
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (isRecording) return@withContext true
        lastError = null
        pcm.reset()
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                lastError = "无法初始化麦克风"
                rec.release()
                return@withContext false
            }
            record = rec
            rec.startRecording()
            isRecording = true
            thread = Thread {
                val buf = ByteArray(minBuf)
                val started = System.currentTimeMillis()
                try {
                    while (isRecording) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            synchronized(pcm) { pcm.write(buf, 0, n) }
                        }
                        val elapsed = (System.currentTimeMillis() - started) / 1000
                        val size = synchronized(pcm) { pcm.size() }
                        if (elapsed >= MAX_SECONDS || size >= MAX_BYTES) {
                            isRecording = false
                        }
                    }
                } catch (e: Exception) {
                    flogError { "yijian recorder: ${e.message}" }
                }
            }.also { it.start() }
            true
        } catch (e: SecurityException) {
            lastError = "没有麦克风权限"
            false
        } catch (e: Exception) {
            lastError = "录音失败：${e.message}"
            flogError { "yijian recorder start: ${e.message}" }
            false
        }
    }

    /** Stops recording and returns the PCM payload (may be null when nothing was captured). */
    suspend fun stop(): ByteArray? = withContext(Dispatchers.IO) {
        isRecording = false
        runCatching { thread?.join(1500) }
        runCatching {
            record?.stop()
        }
        runCatching { record?.release() }
        record = null
        thread = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.size < 1600) null else data  // <~50ms of audio: treat as empty
    }

    fun reset() {
        synchronized(pcm) { pcm.reset() }
    }

    /** Wraps raw little-endian 16-bit mono PCM in a minimal WAV container. */
    fun toWav(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 44)
        fun le32(v: Int) = byteArrayOf(
            (v and 0xff).toByte(), (v shr 8 and 0xff).toByte(),
            (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte(),
        )
        fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
        val byteRate = SAMPLE_RATE * 2
        out.write("RIFF".toByteArray())
        out.write(le32(36 + data.size))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(1))              // PCM
        out.write(le16(1))              // mono
        out.write(le32(SAMPLE_RATE))
        out.write(le32(byteRate))
        out.write(le16(2))              // block align
        out.write(le16(16))             // bits per sample
        out.write("data".toByteArray())
        out.write(le32(data.size))
        out.write(data)
        return out.toByteArray()
    }
}
