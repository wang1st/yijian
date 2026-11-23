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

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.ByteArrayOutputStream

/**
 * Plays a WAV payload returned by DeepSeek TTS through the device speakers.
 *
 * The TTS stream is often chunked (RIFF header with 0xFFFFFFFF sizes), so the container is
 * parsed tolerantly instead of relying on the declared lengths.
 */
object AudioPlayer {
    /** Keeps a reference so the track is not garbage collected mid-playback. */
    @Volatile
    private var activeTrack: AudioTrack? = null

    fun play(wav: ByteArray) {
        try {
            stop()
            val pcm = extractPcm(wav)
            if (pcm == null || pcm.isEmpty()) {
                flogError { "yijian player: no PCM data in TTS payload" }
                return
            }
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(fmt)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setPlaybackHeadPosition(0)
            track.play()
            activeTrack = track
            // Release once playback is done (best effort; no callback needed for short clips).
            Thread {
                try {
                    val ms = (pcm.size.toLong() * 1000 / (24000 * 2)) + 500
                    Thread.sleep(ms)
                } catch (_: InterruptedException) {
                } finally {
                    runCatching { track.stop() }
                    runCatching { track.release() }
                    if (activeTrack === track) activeTrack = null
                }
            }.start()
        } catch (e: Exception) {
            flogError { "yijian player: ${e.message}" }
        }
    }

    fun stop() {
        activeTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        activeTrack = null
    }

    /** Extracts the `data` chunk payload from a WAV container. */
    private fun extractPcm(wav: ByteArray): ByteArray? {
        if (wav.size < 44) return null
        var i = 12
        val out = ByteArrayOutputStream()
        while (i + 8 <= wav.size) {
            val id = String(wav, i, 4, Charsets.US_ASCII)
            var size = (wav[i + 4].toInt() and 0xff) or
                ((wav[i + 5].toInt() and 0xff) shl 8) or
                ((wav[i + 6].toInt() and 0xff) shl 16) or
                ((wav[i + 7].toInt() and 0xff) shl 24)
            i += 8
            if (id == "data") {
                val available = wav.size - i
                // Streaming WAVs may declare 0 or -1; fall back to the real remaining length.
                if (size <= 0 || size > available) size = available
                out.write(wav, i, size)
                return out.toByteArray()
            }
            if (size <= 0 || i + size > wav.size) break
            i += size
            if (size % 2 == 1 && i < wav.size) i++ // chunks are word aligned
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }
}
